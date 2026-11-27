// test_glitter — opt-in glitter suppression (Grade.glitterSuppress, stage 8b).
//
// 1. OFF is BIT-IDENTICAL to the pipeline before the switch existed: the
//    FNV-1a hashes below were measured on `main` (the parent of the feature
//    branch) with a driver that never mentions the new field, over several
//    synthetic CFA frames (noise, clipped sites, sparkles; four grades each)
//    and, when the real clip is on disk, two real 4K frames. They are NOT
//    existing goldens and no existing golden changed.
// 2. ON: a CONDITIONAL 3x3 per-channel chroma median in Rec.709-encoded
//    values: only chroma deviating from its neighbourhood median by more than
//    kGlitterThreshold is replaced; everything else (G, flat/real-colour
//    regions, clipped neutrals, small chroma deviations) stays bit-identical.
// 3. The cheap pre-test (>= 5 of 9 values within the threshold of the centre
//    => not an outlier) is exact: brute-forced against the always-median rule.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <vector>

using namespace rawedit;

namespace {
uint64_t fnv(const ImageF& img) {
    uint64_t h = 1469598103934665603ULL;
    const unsigned char* p = reinterpret_cast<const unsigned char*>(img.rgb.data());
    for (size_t i = 0; i < img.rgb.size() * 4; ++i) { h ^= p[i]; h *= 1099511628211ULL; }
    return h;
}
struct Lcg {
    uint64_t s;
    explicit Lcg(uint64_t q) : s(q * 6364136223846793005ULL + 1442695040888963407ULL) {}
    uint32_t next() { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return uint32_t(s >> 32); }
};

struct Frame { std::vector<uint16_t> cfa; FrameInfo info; RawMeta meta; };

Frame synth(int seed) {
    const uint32_t W = 128, H = 96;
    Frame f;
    f.info.width = W; f.info.height = H; f.info.bitDepth = 16;
    f.info.cfa = CfaPattern::RGGB; f.info.activeArea = Rect{0, 0, int(W), int(H)};
    for (int i = 0; i < 4; ++i) f.meta.black[i] = 64;
    f.meta.white = 1023;
    f.meta.asShotNeutral[0] = 0.5f; f.meta.asShotNeutral[1] = 1.0f; f.meta.asShotNeutral[2] = 0.7f;
    f.meta.forwardMatrix[0] = 0.9f; f.meta.forwardMatrix[1] = 0.1f; f.meta.forwardMatrix[4] = 1.0f;
    f.meta.forwardMatrix[8] = 0.95f; f.meta.forwardMatrix[7] = 0.05f;
    Lcg r(seed);
    f.cfa.resize(size_t(W) * H);
    for (size_t i = 0; i < f.cfa.size(); ++i) {
        const uint32_t v = r.next();
        const uint32_t base = 64 + ((i % W) * 6 + (i / W) * 4) % 900;
        const uint32_t noise = v & 63;
        const uint32_t t = (v >> 8) % 100;
        f.cfa[i] = uint16_t(t < 3 ? 1023 : t < 6 ? 64 + (v >> 16) % 960 : std::min<uint32_t>(base + noise, 1023));
    }
    return f;
}

std::vector<Grade> synthGrades() {
    Grade g0;
    Grade g1; g1.chromaDenoise = 40; g1.noiseReduction = 30; g1.tonemap = 1; g1.saturation = 20;
    Grade g2; g2.highlightReconstruction = false; g2.exposureEv = 0.7f; g2.highlights = -40;
    Grade g3; g3.highlightRecovery = true; g3.chromaDenoise = 100;
    return {g0, g1, g2, g3};
}

// Frozen on `main` (see the header comment): seed 1..3 x grade 0..3.
const uint64_t kSynthOff[3][4] = {
    {0x282707e045aa6af7ULL, 0xb0a916fb9f64058bULL, 0x0f95238b45ac3c63ULL, 0x6fb225fe7f86acccULL},
    {0x0521871b1beeb75aULL, 0xfa6963e90c207263ULL, 0x553ecfbd21fb1b78ULL, 0xd750b49a2f0eae22ULL},
    {0xdb7b87a8a1242445ULL, 0x5ea04fa037105440ULL, 0xfaa3a0e254d758e2ULL, 0xfc36421df47b7742ULL},
};
// Real clip frames 1 and 2, grades {default, chroma 40 + luma 30 + sat 15}.
const uint64_t kRealOff[2][2] = {
    {0x7bdc541a6c2bac94ULL, 0x61f9cbb81998fbbcULL},
    {0x3ef51193538b2918ULL, 0x6e8602fa8edb6d0fULL},
};

ImageF flat(uint32_t w, uint32_t h, float r, float g, float b) {
    ImageF im; im.w = w; im.h = h; im.rgb.resize(size_t(w) * h * 3);
    for (size_t i = 0; i < size_t(w) * h; ++i) { im.rgb[i * 3] = r; im.rgb[i * 3 + 1] = g; im.rgb[i * 3 + 2] = b; }
    return im;
}
} // namespace

TEST_CASE("off_is_bit_identical_to_main_synthetic") {
    for (int seed = 1; seed <= 3; ++seed) {
        const Frame f = synth(seed);
        const auto grades = synthGrades();
        for (size_t k = 0; k < grades.size(); ++k) {
            Grade g = grades[k];
            REQUIRE_FALSE(g.glitterSuppress); // the default
            const ImageF a = developCpu(f.cfa.data(), f.info, f.meta, g);
            g.glitterSuppress = false;        // explicit off == default
            const ImageF b = developCpu(f.cfa.data(), f.info, f.meta, g);
            CAPTURE(seed); CAPTURE(k);
            CHECK(a.rgb.size() == b.rgb.size());
            CHECK(std::memcmp(a.rgb.data(), b.rgb.data(), a.rgb.size() * 4) == 0);
            CHECK(fnv(a) == kSynthOff[seed - 1][k]);
        }
    }
}

TEST_CASE("off_is_bit_identical_to_main_real_slice") {
    const char* path = "/tmp/claude-501/m0/yavas-f.mcraw";
    if (!std::ifstream(path).good()) { std::printf("SKIP real-slice: %s absent\n", path); return; }
    auto o = Source::open(path);
    REQUIRE(o.value != nullptr);
    Source& s = **o.value;
    int fi = 0;
    for (size_t frame : {size_t(1), size_t(2)}) {
        auto d = s.readFrame(frame);
        const FrameInfo info = s.frameInfo(frame);
        const RawMeta meta = s.rawMeta();
        Grade g0;
        Grade g1; g1.chromaDenoise = 40; g1.noiseReduction = 30; g1.saturation = 15;
        const Grade gs[2] = {g0, g1};
        for (int k = 0; k < 2; ++k) {
            Grade g = gs[k]; g.glitterSuppress = false;
            const ImageF img = developCpu(d.value->data(), info, meta, g);
            CAPTURE(frame); CAPTURE(k);
            CHECK(fnv(img) == kRealOff[fi][k]);
        }
        ++fi;
    }
}

TEST_CASE("on_removes_an_isolated_chroma_sparkle_and_keeps_green") {
    ImageF im = flat(9, 9, 0.18f, 0.18f, 0.18f);
    const size_t c = (4 * 9 + 4) * 3;
    im.rgb[c + 0] = 0.9f; // a red sparkle, G and B unchanged
    const ImageF before = im;
    suppressGlitter(im);
    // the sparkle's R is pulled back to the neighbourhood median (neutral)
    CHECK(std::fabs(im.rgb[c + 0] - 0.18f) < 1e-4f);
    // G is never written, anywhere
    for (size_t i = 0; i < 81; ++i) CHECK(im.rgb[i * 3 + 1] == before.rgb[i * 3 + 1]);
    // every other pixel is bit-untouched
    for (size_t i = 0; i < 81; ++i) {
        if (i * 3 == c) continue;
        CHECK(std::memcmp(&im.rgb[i * 3], &before.rgb[i * 3], 12) == 0);
    }
}

TEST_CASE("on_keeps_flat_real_colour_regions_and_clipped_neutrals_exact") {
    ImageF a = flat(16, 12, 0.5f, 0.1f, 0.05f); // saturated flat colour
    const ImageF a0 = a;
    suppressGlitter(a);
    CHECK(std::memcmp(a.rgb.data(), a0.rgb.data(), a.rgb.size() * 4) == 0);
    ImageF b = flat(16, 12, 2.0f, 2.0f, 2.0f); // clipped neutral above 1 must not tint
    const ImageF b0 = b;
    suppressGlitter(b);
    CHECK(std::memcmp(b.rgb.data(), b0.rgb.data(), b.rgb.size() * 4) == 0);
}

namespace {
// The GLSL selection network of GlesShaderSources (glitterMedian9), line for
// line: the shader's median must pick the same element as nth_element.
void sortPair(float& a, float& b) { const float lo = std::min(a, b); b = std::max(a, b); a = lo; }
float networkMedian9(float v[9]) {
    sortPair(v[1], v[2]); sortPair(v[4], v[5]); sortPair(v[7], v[8]);
    sortPair(v[0], v[1]); sortPair(v[3], v[4]); sortPair(v[6], v[7]);
    sortPair(v[1], v[2]); sortPair(v[4], v[5]); sortPair(v[7], v[8]);
    sortPair(v[0], v[3]); sortPair(v[5], v[8]); sortPair(v[4], v[7]);
    sortPair(v[3], v[6]); sortPair(v[1], v[4]); sortPair(v[2], v[5]);
    sortPair(v[4], v[7]); sortPair(v[4], v[2]); sortPair(v[6], v[4]);
    sortPair(v[4], v[2]);
    return v[4];
}
} // namespace

TEST_CASE("glsl_median_network_equals_nth_element") {
    Lcg r(77);
    for (int t = 0; t < 200000; ++t) {
        float a[9], b[9];
        for (int i = 0; i < 9; ++i) {
            // small alphabet on some trials so ties are exercised
            a[i] = (t % 3 == 0) ? float(r.next() % 4) : (r.next() & 0xFFFF) / 65535.0f - 0.5f;
            b[i] = a[i];
        }
        std::nth_element(b, b + 4, b + 9);
        CHECK(networkMedian9(a) == b[4]);
    }
}

TEST_CASE("on_lowers_chroma_spread_on_a_noisy_frame_and_never_touches_green") {
    const Frame f = synth(2);
    Grade g; // default grade
    const ImageF off = developCpu(f.cfa.data(), f.info, f.meta, g);
    g.glitterSuppress = true;
    const ImageF on = developCpu(f.cfa.data(), f.info, f.meta, g);
    REQUIRE(on.rgb.size() == off.rgb.size());
    double s0 = 0, s1 = 0; size_t n = off.rgb.size() / 3;
    for (size_t i = 0; i < n; ++i) {
        CHECK(on.rgb[i * 3 + 1] == off.rgb[i * 3 + 1]);
        const double d0 = off.rgb[i * 3] - off.rgb[i * 3 + 1], d1 = on.rgb[i * 3] - on.rgb[i * 3 + 1];
        s0 += d0 * d0; s1 += d1 * d1;
    }
    CHECK(s1 < s0);
}

namespace {
float tOetf(float lin) {
    lin = std::max(lin, 0.0f);
    if (lin < 0.018f) return 4.5f * lin;
    return 1.099f * std::pow(lin, 0.45f) - 0.099f;
}
float tInvOetf(float v) {
    if (v < 0.081f) return v / 4.5f;
    return std::pow((v + 0.099f) / 1.099f, 1.0f / 0.45f);
}
// Independent brute force of the whole stage: nth_element median, always run.
ImageF bruteGlitter(const ImageF& in, float thr, std::vector<char>* changedR, std::vector<char>* changedB) {
    ImageF out = in;
    const int w = int(in.w), h = int(in.h);
    std::vector<float> eg(size_t(w) * h), c1(eg.size()), c2(eg.size());
    for (size_t i = 0; i < eg.size(); ++i) {
        const float r = tOetf(in.rgb[i * 3]), g = tOetf(in.rgb[i * 3 + 1]), b = tOetf(in.rgb[i * 3 + 2]);
        eg[i] = g; c1[i] = r - g; c2[i] = b - g;
    }
    changedR->assign(eg.size(), 0); changedB->assign(eg.size(), 0);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float v1[9], v2[9]; int k = 0;
            for (int dy = -1; dy <= 1; ++dy)
                for (int dx = -1; dx <= 1; ++dx) {
                    const int xx = std::min(std::max(x + dx, 0), w - 1), yy = std::min(std::max(y + dy, 0), h - 1);
                    v1[k] = c1[size_t(yy) * w + xx]; v2[k] = c2[size_t(yy) * w + xx]; ++k;
                }
            const size_t i = size_t(y) * w + x;
            const float own1 = v1[4], own2 = v2[4];
            std::nth_element(v1, v1 + 4, v1 + 9); std::nth_element(v2, v2 + 4, v2 + 9);
            if (std::fabs(own1 - v1[4]) > thr) { out.rgb[i * 3] = tInvOetf(std::max(eg[i] + v1[4], 0.0f)); (*changedR)[i] = 1; }
            if (std::fabs(own2 - v2[4]) > thr) { out.rgb[i * 3 + 2] = tInvOetf(std::max(eg[i] + v2[4], 0.0f)); (*changedB)[i] = 1; }
        }
    return out;
}
} // namespace

TEST_CASE("fast_pretest_equals_the_always_median_rule_brute_force") {
    Lcg r(2024);
    const float thrs[] = {0.0f, 0.01f, 0.05f, kGlitterThreshold, 0.2f, 1.0f};
    for (int t = 0; t < 600000; ++t) {
        float v[9];
        const int mode = t % 4;
        for (int i = 0; i < 9; ++i) {
            if (mode == 0) v[i] = float(r.next() % 4) * 0.05f;                       // heavy ties, spacing near thr
            else if (mode == 1) v[i] = (r.next() & 0xFFFF) / 65535.0f - 0.5f;          // wide continuous
            else if (mode == 2) v[i] = 0.1f + (r.next() & 0xFFF) / 4095.0f * 0.12f;    // clustered around the thr scale
            else v[i] = (i == 4 || r.next() % 3 == 0) ? 0.3f : 0.3f + (r.next() & 0xFF) / 255.0f * 0.2f - 0.1f;
        }
        const float thr = thrs[(t / 4) % 6];
        float mf = -7.0f, mr = -9.0f;
        const bool of = glitterOutlier9(v, thr, &mf);
        const bool orf = glitterOutlier9Reference(v, thr, &mr);
        if (of != orf) { CAPTURE(t); CAPTURE(thr); FAIL("outlier decision differs"); }
        if (of) CHECK(mf == mr);
    }
}

TEST_CASE("pretest_skips_the_median_for_most_pixels_but_never_changes_the_answer") {
    // a flat neighbourhood with one big outlier at the centre: pre-test does not fire, median does
    float v[9]; for (int i = 0; i < 9; ++i) v[i] = 0.1f;
    v[4] = 0.5f;
    float m = 0.0f;
    CHECK(glitterOutlier9(v, kGlitterThreshold, &m));
    CHECK(m == 0.1f);
    // the centre inside its flat neighbourhood: not an outlier, median untouched
    v[4] = 0.1f; m = -1.0f;
    CHECK_FALSE(glitterOutlier9(v, kGlitterThreshold, &m));
    CHECK(m == -1.0f);
}

TEST_CASE("on_leaves_small_chroma_deviations_bit_identical_and_replaces_large_ones") {
    ImageF im = flat(9, 9, 0.18f, 0.18f, 0.18f);
    const size_t c = (4 * 9 + 4) * 3;
    const float eG = tOetf(0.18f);
    // R chroma off by 0.5*thr (encoded) -> must stay untouched, bit for bit
    im.rgb[c + 0] = tInvOetf(eG + 0.5f * kGlitterThreshold);
    // B chroma off by 1.5*thr -> replaced by the (neutral) median
    im.rgb[c + 2] = tInvOetf(eG + 1.5f * kGlitterThreshold);
    const ImageF before = im;
    suppressGlitter(im);
    CHECK(std::memcmp(&im.rgb[c], &before.rgb[c], 4) == 0);   // R kept exactly
    CHECK(std::fabs(im.rgb[c + 2] - 0.18f) < 1e-4f);          // B pulled to the median
    for (size_t i = 0; i < 81; ++i) {
        CHECK(im.rgb[i * 3 + 1] == before.rgb[i * 3 + 1]);    // G never written
        if (i * 3 == c) continue;
        CHECK(std::memcmp(&im.rgb[i * 3], &before.rgb[i * 3], 12) == 0);
    }
}

TEST_CASE("whole_image_matches_an_independent_brute_force") {
    Lcg r(5);
    ImageF im; im.w = 37; im.h = 29; im.rgb.resize(size_t(37) * 29 * 3);
    for (size_t i = 0; i < size_t(37) * 29; ++i) {
        const float base = 0.02f + 0.9f * float(r.next() & 1023) / 1023.0f;
        const bool spark = r.next() % 9 == 0;
        im.rgb[i * 3 + 0] = std::max(0.0f, base * (spark ? 2.5f : 1.0f + 0.05f * float(r.next() & 7)));
        im.rgb[i * 3 + 1] = base;
        im.rgb[i * 3 + 2] = std::max(0.0f, base * (spark ? 0.2f : 1.0f - 0.04f * float(r.next() & 7)));
    }
    std::vector<char> cr, cb;
    const ImageF want = bruteGlitter(im, kGlitterThreshold, &cr, &cb);
    ImageF got = im;
    suppressGlitter(got);
    size_t nch = 0;
    for (size_t i = 0; i < size_t(37) * 29; ++i) {
        CHECK(got.rgb[i * 3 + 1] == im.rgb[i * 3 + 1]);
        if (cr[i]) { ++nch; CHECK(std::fabs(got.rgb[i * 3] - want.rgb[i * 3]) < 1e-4f * std::max(1.0f, want.rgb[i * 3])); }
        else CHECK(got.rgb[i * 3] == im.rgb[i * 3]);
        if (cb[i]) { ++nch; CHECK(std::fabs(got.rgb[i * 3 + 2] - want.rgb[i * 3 + 2]) < 1e-4f * std::max(1.0f, want.rgb[i * 3 + 2])); }
        else CHECK(got.rgb[i * 3 + 2] == im.rgb[i * 3 + 2]);
    }
    CHECK(nch > 20); // the fixture really exercises the replace path
}
