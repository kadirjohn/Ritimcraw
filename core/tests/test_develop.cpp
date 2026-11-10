// CPU develop reference tests (Task 2). All fixtures are synthetic CFA
// buffers built here — no real clip needed.
//
// Pattern semantics: every 2x2 cell of the CFA holds (r, g, g, b) in
// cell-slot order regardless of arrangement; the arrangement decides where
// those values sit spatially.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <cmath>
#include <cstdio>
#include <vector>

using namespace rawedit;

namespace {

// Builds a w*h uint16 CFA encoding a flat linear patch of the given value
// per channel (r, g, b). Values are pre-level raw counts: v is mapped into
// [black, white] for each channel so that after normalization the channel
// reads `v`. black=64, white=1023 (the Xiaomi 15 Ultra container values).
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

    const float lin[3] = {lr, lg, lb};
    const uint16_t vals[3] = {
        static_cast<uint16_t>(64 + lr * (1023 - 64)),
        static_cast<uint16_t>(64 + lg * (1023 - 64)),
        static_cast<uint16_t>(64 + lb * (1023 - 64)),
    };

    s.cfa.assign(static_cast<size_t>(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            // Site color at (x, y) for this arrangement.
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

// Mean of channel c over the center 2x2 region (avoids edge clamping effects).
float centerMean(const ImageF& img, int c) {
    const uint32_t x0 = img.w / 2 - 1, x1 = img.w / 2;
    const uint32_t y0 = img.h / 2 - 1, y1 = img.h / 2;
    float sum = 0;
    int n = 0;
    for (uint32_t y = y0; y <= y1; ++y)
        for (uint32_t x = x0; x <= x1; ++x) {
            sum += img.rgb[(static_cast<size_t>(y) * img.w + x) * 3 + c];
            ++n;
        }
    return sum / n;
}

} // namespace

TEST_CASE("neutral_patch_has_no_cast_for_every_cfa") {
    for (auto p : {CfaPattern::RGGB, CfaPattern::GRBG, CfaPattern::GBRG, CfaPattern::BGGR}) {
        auto s = syntheticFlat(p, 0.18f, 0.18f, 0.18f);
        auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
        CAPTURE(static_cast<int>(p));
        auto r = centerMean(img, 0), g = centerMean(img, 1), b = centerMean(img, 2);
        CHECK(std::abs(r - g) < 0.005f);
        CHECK(std::abs(b - g) < 0.005f);
    }
}

TEST_CASE("black_and_white_levels_map_to_0_and_1") {
    auto s = syntheticFlat(CfaPattern::RGGB, 0.0f, 0.0f, 0.0f);
    auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto r = centerMean(img, 0);
    CHECK(std::abs(r - 0.0f) < 1e-4f);

    auto sw = syntheticFlat(CfaPattern::RGGB, 1.0f, 1.0f, 1.0f);
    auto img2 = developCpu(sw.cfa.data(), sw.info, sw.meta, Grade{});
    auto r2 = centerMean(img2, 0);
    // Stage 3 (highlight reconstruction) clips to the lowest clipped channel,
    // which for a uniform fully-clipped patch is 1.0 * min gain = 1.0.
    CHECK(std::abs(r2 - 1.0f) < 1e-3f);
}

TEST_CASE("lens_shading_flattens_vignetted_flat_field") {
    // Build a flat field whose CFA values carry a radial vignette, and a
    // matching shading map (gain = 1 / vignette) that must flatten it.
    const uint32_t w = 16, h = 16;
    Synthetic s;
    s.info.width = w; s.info.height = h; s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, int(w), int(h)};
    s.meta.black[0] = s.meta.black[1] = s.meta.black[2] = s.meta.black[3] = 0;
    s.meta.white = 1000;
    s.meta.wbGains[0] = s.meta.wbGains[1] = s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    auto vignette = [&](float u, float v) {
        const float dx = u - 0.5f, dy = v - 0.5f;
        const float rr = dx * dx + dy * dy; // 0 center, 0.5 corner
        return 1.0f - 0.4f * rr / 0.5f;     // 1.0 center, 0.6 corner
    };

    // 17x13 shading map (the real clip's lensShadingMap dimensions), all four
    // planes identical: gain = 1 / vignette. A coarse 3x3 map cannot
    // bilinearly represent a quadratic vignette within 1%; 17x13 leaves the
    // corner/center ratio within ~0.05%.
    const uint32_t sw = 17, sh = 13;
    s.meta.shadingW = sw; s.meta.shadingH = sh;
    s.meta.lensShading.assign(4 * sw * sh, 0.0f);
    for (uint32_t y = 0; y < sh; ++y)
        for (uint32_t x = 0; x < sw; ++x) {
            const float u = sw > 1 ? float(x) / float(sw - 1) : 0.5f;
            const float v = sh > 1 ? float(y) / float(sh - 1) : 0.5f;
            const float g = 1.0f / vignette(u, v);
            for (int plane = 0; plane < 4; ++plane)
                s.meta.lensShading[plane * sw * sh + y * sw + x] = g;
        }

    // CFA: flat 0.5 field multiplied by the vignette.
    const float flat = 0.5f;
    s.cfa.assign(size_t(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const float u = float(x) / float(w - 1);
            const float v = float(y) / float(h - 1);
            const float val = flat * vignette(u, v) * s.meta.white;
            s.cfa[size_t(y) * w + x] = uint16_t(val + 0.5f);
        }

    auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});

    // Green channel, corner vs center: within 1% after the gain map.
    const float center = img.rgb[(size_t(h / 2) * w + w / 2) * 3 + 1];
    const float corner = img.rgb[(size_t(1) * w + 1) * 3 + 1];
    CHECK(center > 0.4f);
    CHECK(corner > 0.4f);
    CHECK(std::abs(corner / center - 1.0f) < 0.01f);
}

TEST_CASE("clipped_highlights_stay_neutral") {
    // Red sites clipped at white, green/blue at 0.6: without reconstruction
    // the pixel would go magenta. With reconstruction all clipped channels
    // clip to the lowest clipped channel's level, so r <= g + tolerance and
    // b <= g + tolerance.
    const uint32_t w = 8, h = 8;
    Synthetic s;
    s.info.width = w; s.info.height = h; s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, int(w), int(h)};
    s.meta.black[0] = s.meta.black[1] = s.meta.black[2] = s.meta.black[3] = 0;
    s.meta.white = 1000;
    s.meta.wbGains[0] = 1.0f; s.meta.wbGains[1] = 1.0f; s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    s.cfa.assign(size_t(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const bool rSite = (y % 2 == 0) && (x % 2 == 0);
            s.cfa[size_t(y) * w + x] = rSite ? 1000 : 600;
        }

    auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto r = centerMean(img, 0), g = centerMean(img, 1), b = centerMean(img, 2);
    // No magenta: red and blue must not exceed green by more than 0.01.
    CHECK(r <= g + 0.01f);
    CHECK(b <= g + 0.01f);
    // And the clipped pixel stays bright/neutral-ish, not dark.
    CHECK(g > 0.5f);
}

TEST_CASE("exposure_plus_one_ev_doubles_linear") {
    auto s = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
    auto base = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade ev1;
    ev1.exposureEv = 1.0f;
    auto up = developCpu(s.cfa.data(), s.info, s.meta, ev1);

    const float m0 = centerMean(base, 1);
    const float m1 = centerMean(up, 1);
    REQUIRE(m0 > 0.0f);
    CHECK(std::abs(m1 / m0 - 2.0f) < 0.01f);
}

TEST_CASE("wb_gains_and_color_matrix_apply_in_order") {
    // A flat patch with as-shot-neutral WB must come out neutral: WB is the
    // division by asShotNeutral (DNG semantics; wbGains is its derived
    // reciprocal), so feeding channels scaled by the neutral vector produces
    // equal output. Real clip values.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.3427734375f, 1.0f, 0.552734375f);
    s.meta.asShotNeutral[0] = 0.3427734375f;
    s.meta.asShotNeutral[1] = 1.0f;
    s.meta.asShotNeutral[2] = 0.552734375f;
    // wbGains stays in sync (reciprocal) for consumers that read it.
    s.meta.wbGains[0] = 1.0f / 0.3427734375f;
    s.meta.wbGains[1] = 1.0f;
    s.meta.wbGains[2] = 1.0f / 0.552734375f;

    auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto r = centerMean(img, 0), g = centerMean(img, 1), b = centerMean(img, 2);
    CHECK(std::abs(r - g) < 0.005f);
    CHECK(std::abs(b - g) < 0.005f);
}

TEST_CASE("dng_forward_matrix_maps_neutral_to_rec709") {
    // DNG 1.6 §6 ForwardMatrix path with the REAL 24mm clip metadata:
    // FM * CAL * [1,1,1] must land on the D50 white point (0.9642, 1.0,
    // 0.8249), and after the Bradford D50->D65 + XYZ->Rec.709 stage a
    // neutral camera patch must come out neutral in Rec.709.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.336914f, 1.0f, 0.568359f);
    s.meta.asShotNeutral[0] = 0.336914f;
    s.meta.asShotNeutral[1] = 1.0f;
    s.meta.asShotNeutral[2] = 0.568359f;
    s.meta.wbGains[0] = 1.0f / 0.336914f;
    s.meta.wbGains[1] = 1.0f;
    s.meta.wbGains[2] = 1.0f / 0.568359f;
    // Real container values (261110_004208 clip).
    const float fm[9] = {0.6328125f, 0.109375f, 0.21875f,
                         0.21875f, 0.7578125f, 0.0234375f,
                         -0.0390625f, -0.453125f, 1.3203125f};
    const float cal[9] = {1.03125f, 0, 0,  0, 1, 0,  0, 0, 1};
    for (int i = 0; i < 9; ++i) {
        s.meta.forwardMatrix[i] = fm[i];
        s.meta.calibration[i] = cal[i];
    }

    auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto r = centerMean(img, 0), g = centerMean(img, 1), b = centerMean(img, 2);
    // Neutral in, near-neutral out. The container's ForwardMatrix is
    // quantized to 1/128 steps, so FM * CAL * [1,1,1] lands ~1.7% off the
    // exact D50 white point on x, which the XYZ->Rec.709 matrix amplifies
    // to ~4% in R. A real DNG reader shows the same slight warm shift with
    // this metadata; 0.05 documents it instead of hiding it.
    CHECK(std::abs(r - g) < 0.05f);
    CHECK(std::abs(b - g) < 0.05f);
    // And it is far closer to neutral than the old inverted-matrix bug
    // (which put ~127% into G: neutral input came out (0.03, 1.27, 0.54)).
    CHECK(g > 0.9f);
    CHECK(r > 0.9f);
    CHECK(b > 0.9f);
}
