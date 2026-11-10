// Noise reduction tests (owner request DN-01..DN-08, spatial half). The
// chroma stage (stage 7) and the luma stage (stage 8) are independent,
// edge-aware, and neutral at 0 — the binding requirements:
//   - neutral bit-identity: 0 must be an exact bypass (golden hashes),
//   - noise variance drops monotonically with strength,
//   - edges survive at moderate strength,
//   - chroma-only leaves luma untouched, luma-only leaves chroma
//     differences (per-pixel r-g / b-g) untouched,
//   - the two stages are independent (each slider moves only its own plane).
// All fixtures are synthetic CFA buffers (same helpers as test_develop.cpp)
// with deterministic LCG noise — no real clip needed.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdint>
#include <vector>

using namespace rawedit;

namespace {

// Same synthetic CFA builder as test_develop.cpp: a flat linear patch per
// channel, black=64/white=1023, identity color matrix, wbGains 1.
struct Synthetic {
    std::vector<uint16_t> cfa;
    FrameInfo info;
    RawMeta meta;
};

Synthetic syntheticFlat(CfaPattern pattern, float lr, float lg, float lb,
                       uint32_t w = 8, uint32_t h = 8) {
    Synthetic s;
    s.info.width = w;
    s.info.height = h;
    s.info.bitDepth = 16;
    s.info.cfa = pattern;
    s.info.activeArea = Rect{0, 0, static_cast<int>(w), static_cast<int>(h)};

    s.meta.black[0] = 64; s.meta.black[1] = 64;
    s.meta.black[2] = 64; s.meta.black[3] = 64;
    s.meta.white = 1023;
    s.meta.wbGains[0] = 1.0f; s.meta.wbGains[1] = 1.0f; s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    const uint16_t vals[3] = {
        static_cast<uint16_t>(64 + lr * (1023 - 64)),
        static_cast<uint16_t>(64 + lg * (1023 - 64)),
        static_cast<uint16_t>(64 + lb * (1023 - 64)),
    };

    s.cfa.assign(static_cast<size_t>(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            int site;
            switch (pattern) {
                case CfaPattern::RGGB: site = (y % 2 == 0) ? (x % 2 == 0 ? 0 : 1) : (x % 2 == 0 ? 1 : 2); break;
                case CfaPattern::GRBG: site = (y % 2 == 0) ? (x % 2 == 0 ? 1 : 0) : (x % 2 == 0 ? 2 : 1); break;
                case CfaPattern::GBRG: site = (y % 2 == 0) ? (x % 2 == 0 ? 1 : 2) : (x % 2 == 0 ? 0 : 1); break;
                case CfaPattern::BGGR: site = (y % 2 == 0) ? (x % 2 == 0 ? 2 : 1) : (x % 2 == 0 ? 1 : 0); break;
                default: site = 0; break;
            }
            s.cfa[static_cast<size_t>(y) * w + x] = vals[site];
        }
    }
    return s;
}

// FNV-1a over the raw bytes of the whole rgb buffer (same as test_develop).
uint64_t fnv1a(const ImageF& img) {
    uint64_t h = 1469598103934665603ULL;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(img.rgb.data());
    const size_t n = img.rgb.size() * sizeof(float);
    for (size_t i = 0; i < n; ++i) {
        h ^= p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

// Deterministic LCG noise (xorshift-style, fixed seed): zero allocation
// surprises, reproducible hashes.
struct Lcg {
    uint64_t s;
    explicit Lcg(uint64_t seed) : s(seed * 6364136223846793005ULL + 1442695040888963407ULL) {}
    uint32_t next() {
        s ^= s << 13; s ^= s >> 7; s ^= s << 17;
        return static_cast<uint32_t>(s >> 32);
    }
    float unit() { return (next() & 0xFFFFFF) / float(0xFFFFFF); } // [0,1)
    float symm() { return unit() * 2.0f - 1.0f; }                  // [-1,1)
};

// Adds symmetric uniform noise of amplitude a (in linear stops of the raw
// count) to every CFA site, per site color, deterministically.
void addNoise(Synthetic& s, float a, uint64_t seed) {
    Lcg rng(seed);
    for (uint32_t y = 0; y < s.info.height; ++y)
        for (uint32_t x = 0; x < s.info.width; ++x) {
            const size_t i = static_cast<size_t>(y) * s.info.width + x;
            const float v = static_cast<float>(s.cfa[i]) + rng.symm() * a * (1023.0f - 64.0f);
            s.cfa[i] = static_cast<uint16_t>(
                std::min(std::max(v + 0.5f, 0.0f), 1023.0f));
        }
}

// Variance of channel c over the interior (drops the 1-px border, which the
// clamped kernels treat specially).
float interiorVariance(const ImageF& img, int c) {
    const uint32_t w = img.w, h = img.h;
    double sum = 0, sumSq = 0;
    uint64_t n = 0;
    for (uint32_t y = 1; y + 1 < h; ++y)
        for (uint32_t x = 1; x + 1 < w; ++x) {
            const double v = img.rgb[(static_cast<size_t>(y) * w + x) * 3 + c];
            sum += v; sumSq += v * v; ++n;
        }
    const double mean = sum / n;
    return static_cast<float>(sumSq / n - mean * mean);
}

// Mean of the Rec.709 luma over the interior.
float interiorLumaMean(const ImageF& img) {
    const uint32_t w = img.w, h = img.h;
    double sum = 0;
    uint64_t n = 0;
    for (uint32_t y = 1; y + 1 < h; ++y)
        for (uint32_t x = 1; x + 1 < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3;
            sum += 0.2126 * img.rgb[i + 0] + 0.7152 * img.rgb[i + 1] + 0.0722 * img.rgb[i + 2];
            ++n;
        }
    return static_cast<float>(sum / n);
}

// Mean absolute per-pixel chroma difference (r-g and b-g) over the interior.
float interiorChromaMean(const ImageF& img) {
    const uint32_t w = img.w, h = img.h;
    double sum = 0;
    uint64_t n = 0;
    for (uint32_t y = 1; y + 1 < h; ++y)
        for (uint32_t x = 1; x + 1 < w; ++x) {
            const size_t i = (static_cast<size_t>(y) * w + x) * 3;
            sum += std::abs(img.rgb[i + 0] - img.rgb[i + 1])
                 + std::abs(img.rgb[i + 2] - img.rgb[i + 1]);
            ++n;
        }
    return static_cast<float>(sum / n);
}

// Edge fixture: same as test_develop.cpp's syntheticEdgeFixture — 16x16
// RGGB, vertical edge between columns 7/8, left 0.30 right 0.60, black=0
// white=1000.
Synthetic syntheticEdgeFixture() {
    const uint32_t w = 16, h = 16;
    Synthetic s;
    s.info.width = w; s.info.height = h; s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, int(w), int(h)};
    s.meta.black[0] = s.meta.black[1] = s.meta.black[2] = s.meta.black[3] = 0;
    s.meta.white = 1000;
    s.meta.wbGains[0] = s.meta.wbGains[1] = s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    const float left = 0.30f, right = 0.60f;
    s.cfa.assign(size_t(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const float v = (x < 8) ? left : right;
            s.cfa[size_t(y) * w + x] = uint16_t(v * s.meta.white + 0.5f);
        }
    return s;
}

} // namespace

// ---- Neutral bit-identity (the binding requirement). ----

TEST_CASE("neutral_noise_grade_is_bit_identical_to_old_pipeline") {
    // A default-constructed Grade and a Grade with both denoise fields
    // EXPLICITLY 0 must produce the exact same bytes as the old pipeline
    // (the golden hashes of test_develop.cpp lock the old bytes; here the
    // three grades must be equal to EACH OTHER and to the no-grade path).
    auto s = syntheticFlat(CfaPattern::RGGB, 0.55f, 0.30f, 0.12f, 24, 18);
    addNoise(s, 0.02f, 42); // noise must not tempt the stages to act at 0

    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade explicitZero;
    explicitZero.noiseReduction = 0.0f;
    explicitZero.chromaDenoise = 0.0f;
    auto zeroed = developCpu(s.cfa.data(), s.info, s.meta, explicitZero);

    CHECK(fnv1a(plain) == fnv1a(zeroed));
    CHECK(plain.rgb.size() == zeroed.rgb.size());
    for (size_t i = 0; i < plain.rgb.size(); ++i)
        CHECK(plain.rgb[i] == zeroed.rgb[i]);
}

TEST_CASE("flat_input_is_bit_identical_at_any_strength") {
    // On a FLAT patch every kernel tap equals the center, so both stages
    // are exact no-ops at ANY strength (taps sum to 1; the blend of equal
    // values is the value). This is what keeps the neutral look stable on
    // clean skies and flat surfaces.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.35f, 0.35f, 0.35f, 16, 16);
    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});

    Grade chroma; chroma.chromaDenoise = 100.0f;
    Grade luma; luma.noiseReduction = 100.0f;
    Grade both; both.chromaDenoise = 100.0f; both.noiseReduction = 100.0f;

    CHECK(fnv1a(developCpu(s.cfa.data(), s.info, s.meta, chroma)) == fnv1a(plain));
    CHECK(fnv1a(developCpu(s.cfa.data(), s.info, s.meta, luma)) == fnv1a(plain));
    CHECK(fnv1a(developCpu(s.cfa.data(), s.info, s.meta, both)) == fnv1a(plain));
}

// ---- Variance drops with strength (the stages actually denoise). ----

TEST_CASE("luma_denoise_drops_noise_variance_monotonically") {
    // A noisy flat gray patch: the luma stage's neighborhood average must
    // pull the per-channel variance DOWN, more at higher strength.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, 32, 32);
    addNoise(s, 0.03f, 7);

    float prevG = -1.0f;
    for (float v : {0.0f, 25.0f, 50.0f, 100.0f}) {
        Grade g; g.noiseReduction = v;
        auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
        const float var = interiorVariance(img, 1);
        if (prevG >= 0.0f)
            CHECK(var < prevG); // strictly monotone in strength
        prevG = var;
    }
    // And the strongest setting removes most of the noise: variance at 100
    // is well below half the neutral variance.
    Grade full; full.noiseReduction = 100.0f;
    auto neutral = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    CHECK(interiorVariance(developCpu(s.cfa.data(), s.info, s.meta, full), 1)
          < 0.5f * interiorVariance(neutral, 1));
}

TEST_CASE("chroma_denoise_drops_chroma_variance_monotonically") {
    // Chroma noise: each site color gets independent noise, so the
    // developed pixels carry random r-g / b-g offsets. The chroma stage
    // must pull the chroma variance DOWN, more at higher strength.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, 32, 32);
    addNoise(s, 0.03f, 11);

    float prevC = -1.0f;
    for (float v : {0.0f, 25.0f, 50.0f, 100.0f}) {
        Grade g; g.chromaDenoise = v;
        auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
        const float c = interiorChromaMean(img);
        if (prevC >= 0.0f)
            CHECK(c < prevC);
        prevC = c;
    }
    Grade full; full.chromaDenoise = 100.0f;
    auto neutral = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    CHECK(interiorChromaMean(developCpu(s.cfa.data(), s.info, s.meta, full))
          < 0.6f * interiorChromaMean(neutral));
}

// ---- Edge survival (the stages must NOT smear edges). ----

TEST_CASE("luma_denoise_preserves_edges_at_moderate_strength") {
    // The vertical edge of the edge fixture must survive a moderate (50)
    // luma denoise: the ramp delta across the boundary stays close to the
    // un-denoised delta (bilateral range weights suppress cross-edge taps).
    auto s = syntheticEdgeFixture();
    const uint32_t w = s.info.width, h = s.info.height;
    const uint32_t row = h / 2;

    auto rampDelta = [&](const ImageF& img) {
        return img.rgb[(size_t(row) * w + 8) * 3 + 1]
             - img.rgb[(size_t(row) * w + 7) * 3 + 1];
    };

    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade g; g.noiseReduction = 50.0f;
    auto den = developCpu(s.cfa.data(), s.info, s.meta, g);

    // The edge survives: at least 95% of the ramp delta remains.
    CHECK(rampDelta(den) > 0.95f * rampDelta(plain));
    // And the flat interiors stay exactly flat (already bit-identical: no
    // noise there to smooth, taps equal the center).
    for (uint32_t y = 0; y < h; ++y) {
        const size_t iL = (size_t(y) * w + 2) * 3;
        const size_t iR = (size_t(y) * w + 13) * 3;
        CHECK(den.rgb[iL + 0] == plain.rgb[iL + 0]);
        CHECK(den.rgb[iL + 1] == plain.rgb[iL + 1]);
        CHECK(den.rgb[iL + 2] == plain.rgb[iL + 2]);
        CHECK(den.rgb[iR + 0] == plain.rgb[iR + 0]);
        CHECK(den.rgb[iR + 1] == plain.rgb[iR + 1]);
        CHECK(den.rgb[iR + 2] == plain.rgb[iR + 2]);
    }
}

TEST_CASE("chroma_denoise_preserves_edges_at_full_strength") {
    // The edge fixture is gray on both sides, but bilinear demosaic leaves
    // real chroma FRINGING at the boundary (an edge R site interpolates its
    // green from both sides: r=0.60, g=0.45). Chroma denoise at 100 must:
    //  - preserve every pixel's Rec.709 luma (the stage is luma-preserving
    //    by construction — that is what keeps the edge itself intact),
    //  - keep the pure-region interiors bit-identical (constant chroma
    //    offset there, the center-relative blur delta is exactly 0),
    //  - shrink the edge-band fringing (defringe — the point of the stage).
    auto s = syntheticEdgeFixture();
    const uint32_t w = s.info.width, h = s.info.height;
    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade g; g.chromaDenoise = 100.0f;
    auto den = developCpu(s.cfa.data(), s.info, s.meta, g);

    // Luma preserved per pixel (algebraically exact; float ~1e-7).
    for (size_t i = 0; i < plain.rgb.size(); i += 3) {
        const float l0 = 0.2126f * plain.rgb[i + 0] + 0.7152f * plain.rgb[i + 1]
                       + 0.0722f * plain.rgb[i + 2];
        const float l1 = 0.2126f * den.rgb[i + 0] + 0.7152f * den.rgb[i + 1]
                       + 0.0722f * den.rgb[i + 2];
        CHECK(std::abs(l1 - l0) < 1e-5f);
    }

    // Pure-region interiors stay exact. The demosaic fringe sits at the
    // boundary columns (7/8); the chroma blur's taps reach x +/- 4, so a
    // pure pixel is guaranteed untouched only when x+4 < 7 or x-4 > 8 —
    // columns 0..2 and 13..15. (Columns 3 and 12 legitimately receive a
    // touch of defringe from the fringe 4-5 px away; the fringe's luma
    // deviation ~0.08 is inside the stage's sigma_r = 0.10 range gate, so
    // it is treated as chroma noise — which demosaic fringing is.)
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            if (x > 2 && x < 13)
                continue; // within the blur's reach of the fringe
            const size_t i = (static_cast<size_t>(y) * w + x) * 3;
            CHECK(den.rgb[i + 0] == plain.rgb[i + 0]);
            CHECK(den.rgb[i + 1] == plain.rgb[i + 1]);
            CHECK(den.rgb[i + 2] == plain.rgb[i + 2]);
        }
    }

    // Edge-band fringing shrinks: mean |r-g| over the boundary columns
    // drops at full strength (the blur pulls the demosaic fringe toward
    // the flat regions' zero offset, gated by the luma range weights).
    auto bandFringe = [&](const ImageF& img) {
        double sum = 0;
        int cnt = 0;
        for (uint32_t y = 1; y + 1 < h; ++y)
            for (uint32_t x = 5; x <= 10; ++x) {
                const size_t i = (static_cast<size_t>(y) * w + x) * 3;
                sum += std::abs(img.rgb[i + 0] - img.rgb[i + 1]);
                ++cnt;
            }
        return static_cast<float>(sum / cnt);
    };
    CHECK(bandFringe(den) < bandFringe(plain));
}

// ---- Stage independence (each slider moves only its own plane). ----

TEST_CASE("chroma_denoise_leaves_luma_untouched") {
    // The chroma stage rebuilds each pixel as y + blended offsets with the
    // luma-weighted chroma sum exactly zero, so the Rec.709 luma of every
    // pixel is preserved to float precision.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, 32, 32);
    addNoise(s, 0.03f, 23);

    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade g; g.chromaDenoise = 100.0f;
    auto den = developCpu(s.cfa.data(), s.info, s.meta, g);

    for (size_t i = 0; i < plain.rgb.size(); i += 3) {
        const float l0 = 0.2126f * plain.rgb[i + 0] + 0.7152f * plain.rgb[i + 1]
                       + 0.0722f * plain.rgb[i + 2];
        const float l1 = 0.2126f * den.rgb[i + 0] + 0.7152f * den.rgb[i + 1]
                       + 0.0722f * den.rgb[i + 2];
        CHECK(std::abs(l1 - l0) < 1e-5f);
    }
}

TEST_CASE("luma_denoise_leaves_chroma_differences_untouched") {
    // The luma stage adds the SAME delta to r, g and b (unsharpLuma
    // idiom), so per-pixel channel DIFFERENCES are preserved exactly.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, 32, 32);
    addNoise(s, 0.03f, 29);

    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade g; g.noiseReduction = 100.0f;
    auto den = developCpu(s.cfa.data(), s.info, s.meta, g);

    for (size_t i = 0; i < plain.rgb.size(); i += 3) {
        const float d0 = plain.rgb[i + 0] - plain.rgb[i + 1];
        const float d1 = den.rgb[i + 0] - den.rgb[i + 1];
        CHECK(std::abs(d0 - d1) < 1e-6f);
        const float e0 = plain.rgb[i + 2] - plain.rgb[i + 1];
        const float e1 = den.rgb[i + 2] - den.rgb[i + 1];
        CHECK(std::abs(e0 - e1) < 1e-6f);
    }
}

TEST_CASE("stages_are_independent_luma_slider_does_not_move_luma_means") {
    // Turning the CHROMA slider up must not move the mean luma (beyond
    // float noise), and turning the LUMA slider up must not move the mean
    // chroma offsets. Each stage owns exactly its own plane.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, 32, 32);
    addNoise(s, 0.03f, 31);

    auto neutral = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade c; c.chromaDenoise = 100.0f;
    Grade l; l.noiseReduction = 100.0f;
    auto chromaOn = developCpu(s.cfa.data(), s.info, s.meta, c);
    auto lumaOn = developCpu(s.cfa.data(), s.info, s.meta, l);

    // Chroma slider: luma mean unchanged (the stage is luma-preserving
    // per pixel, so the mean is too).
    CHECK(std::abs(interiorLumaMean(chromaOn) - interiorLumaMean(neutral)) < 1e-5f);

    // Luma slider: chroma mean unchanged (equal deltas preserve channel
    // differences per pixel, so the mean chroma is too).
    CHECK(std::abs(interiorChromaMean(lumaOn) - interiorChromaMean(neutral)) < 1e-6f);
}

TEST_CASE("both_stages_together_stack") {
    // With both sliders at 100 on a noisy patch, both planes are cleaner
    // than neutral and the combination is stable (no interaction blowups:
    // the stages run in sequence, chroma first).
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, 32, 32);
    addNoise(s, 0.03f, 37);

    Grade both; both.chromaDenoise = 100.0f; both.noiseReduction = 100.0f;
    auto out = developCpu(s.cfa.data(), s.info, s.meta, both);
    auto neutral = developCpu(s.cfa.data(), s.info, s.meta, Grade{});

    CHECK(interiorVariance(out, 1) < interiorVariance(neutral, 1));
    CHECK(interiorChromaMean(out) < interiorChromaMean(neutral));
    // Finite everywhere (no NaN/inf from the bilateral weights).
    for (float v : out.rgb)
        CHECK(std::isfinite(v));
}

// ---- Encoded (Mi-Log/MP4) path parity: the same stages run there. ----

TEST_CASE("encoded_path_runs_the_same_denoise_stages") {
    // A noisy NV12 frame through developEncoded: neutral is bit-identical
    // to an explicitly-zero grade (stage skipped), and both sliders clean
    // their own plane on the decoded RGB exactly like the MCRAW path.
    // Noise goes into BOTH planes here: luma noise in Y, chroma noise in
    // the interleaved UV (each 2x2 block gets one random Cb/Cr pair).
    const uint32_t w = 32, h = 32;
    YuvFrame f;
    f.width = w; f.height = h; f.semiPlanar = true;
    f.y.assign(size_t(w) * h, 0);
    f.u.assign(size_t(w) * h, 128);
    Lcg rng(97);
    for (uint32_t r = 0; r < h; ++r)
        for (uint32_t c = 0; c < w; ++c)
            f.y[size_t(r) * w + c] = uint8_t(128 + rng.symm() * 12.0f);
    for (uint32_t r = 0; r < h / 2; ++r)
        for (uint32_t c = 0; c < w / 2; ++c) {
            const size_t i = size_t(r) * (w / 2) * 2 + size_t(c) * 2;
            f.u[i] = uint8_t(128 + rng.symm() * 20.0f);
            f.u[i + 1] = uint8_t(128 + rng.symm() * 20.0f);
        }

    auto neutral = developEncoded(f, Grade{}, true);
    Grade z; z.noiseReduction = 0.0f; z.chromaDenoise = 0.0f;
    auto zeroed = developEncoded(f, z, true);
    CHECK(fnv1a(neutral) == fnv1a(zeroed));

    Grade l; l.noiseReduction = 100.0f;
    Grade c; c.chromaDenoise = 100.0f;
    auto lumaOn = developEncoded(f, l, true);
    auto chromaOn = developEncoded(f, c, true);

    // Luma denoise smooths the decoded luma noise.
    CHECK(interiorVariance(lumaOn, 1) < interiorVariance(neutral, 1));
    // Chroma denoise pulls the chroma offsets down without moving luma
    // (per-pixel luma preserved to float precision).
    CHECK(interiorChromaMean(chromaOn) < interiorChromaMean(neutral));
    for (size_t i = 0; i < neutral.rgb.size(); i += 3) {
        const float l0 = 0.2126f * neutral.rgb[i + 0] + 0.7152f * neutral.rgb[i + 1]
                       + 0.0722f * neutral.rgb[i + 2];
        const float l1 = 0.2126f * chromaOn.rgb[i + 0] + 0.7152f * chromaOn.rgb[i + 1]
                       + 0.0722f * chromaOn.rgb[i + 2];
        CHECK(std::abs(l1 - l0) < 1e-5f);
    }
}

// ---- Per-frame timing (the measurement output; device-independent
// half of the quality/cost record — on-device metrics are out of scope). ----

TEST_CASE("denoise_stage_timing_per_frame") {
    // Measures developCpu per-frame cost at each configuration on a
    // 1080p-class synthetic frame (1920x1080 RGGB). NOT an assertion
    // test — it prints the device-independent timing record the mission
    // asked for: neutral fast path vs chroma-only vs luma-only vs both.
    // Timings are machine-dependent; the printed numbers are the output.
    const uint32_t w = 1920, h = 1080;
    auto s = syntheticFlat(CfaPattern::RGGB, 0.30f, 0.30f, 0.30f, w, h);
    addNoise(s, 0.02f, 5);

    const int iters = 3;
    auto time = [&](const Grade& g) {
        // Warm-up run (allocator + cache effects), then the timed mean.
        developCpu(s.cfa.data(), s.info, s.meta, g);
        const auto t0 = std::chrono::steady_clock::now();
        for (int i = 0; i < iters; ++i)
            developCpu(s.cfa.data(), s.info, s.meta, g);
        const auto t1 = std::chrono::steady_clock::now();
        return std::chrono::duration<double, std::milli>(t1 - t0).count() / iters;
    };

    Grade chroma; chroma.chromaDenoise = 100.0f;
    Grade luma; luma.noiseReduction = 100.0f;
    Grade both; both.chromaDenoise = 100.0f; both.noiseReduction = 100.0f;

    const double tNeutral = time(Grade{});
    const double tChroma = time(chroma);
    const double tLuma = time(luma);
    const double tBoth = time(both);

    printf("---- denoise timing (1920x1080 RGGB, %d-frame mean, desktop clang -O2) ----\n", iters);
    printf("  neutral (both off):     %8.2f ms/frame\n", tNeutral);
    printf("  chroma denoise 100:     %8.2f ms/frame  (+%.2f ms over neutral)\n", tChroma, tChroma - tNeutral);
    printf("  luma denoise 100:       %8.2f ms/frame  (+%.2f ms over neutral)\n", tLuma, tLuma - tNeutral);
    printf("  both at 100:            %8.2f ms/frame  (+%.2f ms over neutral)\n", tBoth, tBoth - tNeutral);

    // The neutral fast path must not run the stages at all: its time is
    // the plain develop cost. The denoise stages add measurable cost —
    // assert the ORDER (stages on > neutral) so the printed deltas are
    // honest, with generous slack for machine noise.
    CHECK(tBoth > tNeutral);
    CHECK(tChroma > tNeutral);
    CHECK(tLuma > tNeutral);
}
