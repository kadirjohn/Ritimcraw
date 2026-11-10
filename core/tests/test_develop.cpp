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

// FNV-1a over the raw bytes of the whole rgb buffer. Locks the FULL output,
// not just the sampled pixels. Same machine runs the test, so byte order is
// irrelevant.
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

// A Grade with every new tone/color/detail field EXPLICITLY neutral and every
// RAW toggle EXPLICITLY on (the old shapes would leave them at these defaults
// anyway; writing them out proves the defaults are the old behavior).
Grade neutralToneGrade() {
    Grade g;
    g.saturation = 0.0f;
    g.blackPoint = 0.0f;
    g.whitePoint = 0.0f;
    g.shadows = 0.0f;
    g.midtones = 0.0f;
    g.contrast = 0.0f;
    g.asShot = true;
    g.highlightReconstruction = true;
    g.keepVignetting = true;
    g.sharpness = 0.0f;
    g.detail = 0.0f;
    g.tonemap = 0;
    return g;
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

TEST_CASE("highlights_slider_changes_bright_region_only") {
    // A bright (near-white, unclipped) patch must darken with highlights
    // recovery; a mid-gray patch must stay put.
    Synthetic bright = syntheticFlat(CfaPattern::RGGB, 0.95f, 0.95f, 0.95f);
    Synthetic mid = syntheticFlat(CfaPattern::RGGB, 0.3f, 0.3f, 0.3f);
    Grade g; g.highlights = -100.0f;
    auto b0 = developCpu(bright.cfa.data(), bright.info, bright.meta, Grade{});
    auto b1 = developCpu(bright.cfa.data(), bright.info, bright.meta, g);
    auto m0 = developCpu(mid.cfa.data(), mid.info, mid.meta, Grade{});
    auto m1 = developCpu(mid.cfa.data(), mid.info, mid.meta, g);
    CHECK(centerMean(b1, 1) < centerMean(b0, 1) - 0.02f);   // bright got pulled
    CHECK(std::abs(centerMean(m1, 1) - centerMean(m0, 1)) < 0.001f); // mid untouched
}

TEST_CASE("highlights_recovery_never_turns_bright_sky_black") {
    // Owner finding 2026-11-10: Exposure +2.1 EV then Highlights -55 turned
    // a bright sky BLACK. Root cause: the old quadratic factor crossed zero
    // and went negative for large t, and the Rec.709 clamp turned negative
    // output into black. The monotone shoulder keeps every over-knee pixel
    // above the knee and preserves brightness order.
    Synthetic s = syntheticFlat(CfaPattern::RGGB, 0.95f, 0.95f, 0.95f);
    Grade g; g.exposureEv = 2.1f; g.highlights = -55.0f;
    auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
    const float m = centerMean(img, 1);
    // The sky is still bright, never black: recovery pulls it toward the
    // knee region, not to zero.
    CHECK(m > 0.5f);
    // And at the extreme (-100) it still must not go black.
    Grade extreme; extreme.exposureEv = 2.1f; extreme.highlights = -100.0f;
    auto img2 = developCpu(s.cfa.data(), s.info, s.meta, extreme);
    const float m2 = centerMean(img2, 1);
    CHECK(m2 > 0.4f);
}

TEST_CASE("highlights_recovery_preserves_brightness_order") {
    // Two over-knee patches, one brighter than the other: after ANY recovery
    // strength the brighter one must stay at least as bright (the shoulder
    // is monotone in input). The old quadratic reversed order past the
    // zero-crossing.
    Synthetic dim = syntheticFlat(CfaPattern::RGGB, 0.80f, 0.80f, 0.80f);
    Synthetic bright = syntheticFlat(CfaPattern::RGGB, 0.98f, 0.98f, 0.98f);
    Grade g; g.highlights = -100.0f;
    auto d = developCpu(dim.cfa.data(), dim.info, dim.meta, g);
    auto b = developCpu(bright.cfa.data(), bright.info, bright.meta, g);
    // Both patches are above the knee after recovery; order preserved.
    CHECK(centerMean(b, 1) >= centerMean(d, 1) - 0.001f);
    CHECK(centerMean(b, 1) > 0.75f);   // still bright, not crushed
    CHECK(centerMean(d, 1) > 0.75f);
}

TEST_CASE("temperature_slider_moves_red_and_blue_opposite") {
    auto s = syntheticFlat(CfaPattern::RGGB, 0.4f, 0.4f, 0.4f);
    // Manual WB: the sliders only act when asShot is false (the as-shot
    // grade skips stages 14-15 entirely).
    Grade warm; warm.asShot = false; warm.tempK = 32000.0f;   // >> 5600: warm
    Grade cool; cool.asShot = false; cool.tempK = 3000.0f;   // << 5600: cool
    auto base = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto w = developCpu(s.cfa.data(), s.info, s.meta, warm);
    auto c = developCpu(s.cfa.data(), s.info, s.meta, cool);
    const float br = centerMean(base, 0), bb = centerMean(base, 2);
    CHECK(centerMean(w, 0) > br + 0.05f);   // red up when warm
    CHECK(centerMean(w, 2) < bb - 0.05f);   // blue down when warm
    CHECK(centerMean(c, 0) < br - 0.05f);   // red down when cool
    CHECK(centerMean(c, 2) > bb + 0.05f);   // blue up when cool
}

TEST_CASE("tint_slider_moves_green_only") {
    auto s = syntheticFlat(CfaPattern::RGGB, 0.4f, 0.4f, 0.4f);
    // Manual WB: the tint slider only acts when asShot is false.
    Grade magenta; magenta.asShot = false; magenta.tint = -100.0f;
    Grade green; green.asShot = false; green.tint = 100.0f;
    auto base = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto mg = developCpu(s.cfa.data(), s.info, s.meta, magenta);
    auto gr = developCpu(s.cfa.data(), s.info, s.meta, green);
    const float bg = centerMean(base, 1);
    CHECK(centerMean(mg, 1) < bg - 0.05f);  // magenta: green down
    CHECK(centerMean(gr, 1) > bg + 0.05f);  // green up
    // R and B stay put.
    CHECK(std::abs(centerMean(mg, 0) - centerMean(base, 0)) < 0.001f);
    CHECK(std::abs(centerMean(gr, 2) - centerMean(base, 2)) < 0.001f);
}

// ---- Tone/color controls (owner request 2026-11-10 section 2) ----

// Pixel value of channel c at the buffer center.
float centerPx(const ImageF& img, int c) {
    return img.rgb[(static_cast<size_t>(img.h / 2) * img.w + img.w / 2) * 3 + c];
}

TEST_CASE("neutral_tone_grade_is_bit_identical_to_old_pipeline") {
    // Hard requirement: a grade with all new fields neutral must produce
    // BIT-IDENTICAL output to the pre-tone-control pipeline. Locks three
    // things together: (a) FNV-1a hashes + hexfloat centers captured from
    // the pipeline BEFORE the tone stages existed (tree at 84b9df2), (b) a
    // Grade with the new fields explicitly neutral, (c) a default-
    // constructed Grade. All must agree exactly, byte for byte.
    {
        // Fixture A: default grade on a 0.18 flat patch.
        auto s = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
        auto img = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
        auto imgExplicit = developCpu(s.cfa.data(), s.info, s.meta, neutralToneGrade());
        CHECK(fnv1a(img) == fnv1a(imgExplicit));
        CHECK(fnv1a(img) == 0x88593750a9564183ULL);
        CHECK(centerPx(img, 0) == 0x1.6f50e2p-3f);
        CHECK(centerMean(img, 1) == 0x1.6f50e2p-3f);
    }
    {
        // Fixture B: the four ORIGINAL controls all active on a colored
        // patch; the new fields stay at their neutral defaults. The manual
        // temp/tint values require asShot = false — under the new default
        // (asShot = true) stages 14-15 would be skipped; setting it false
        // runs them, which is what the captured hashes record. This locks
        // the manual-WB path to the old output byte for byte.
        auto s = syntheticFlat(CfaPattern::RGGB, 0.55f, 0.30f, 0.12f);
        Grade g;
        g.exposureEv = 0.7f;
        g.highlights = -35.0f;
        g.tempK = 7200.0f;
        g.tint = 12.0f;
        g.asShot = false;
        auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
        CHECK(fnv1a(img) == 0x38150540c239af83ULL);
        CHECK(centerPx(img, 0) == 0x1.e8f262p-1f);
        CHECK(centerPx(img, 1) == 0x1.f835dcp-2f);
        CHECK(centerPx(img, 2) == 0x1.65f01cp-3f);
    }
    {
        // Fixture C: exposure + heavy highlights recovery on a bright patch.
        auto s = syntheticFlat(CfaPattern::RGGB, 0.95f, 0.95f, 0.95f);
        Grade g;
        g.exposureEv = 2.1f;
        g.highlights = -55.0f;
        auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
        CHECK(fnv1a(img) == 0x1cea742e0c17bf03ULL);
        CHECK(centerPx(img, 0) == 0x1.2fbecap+1f);
    }
}

TEST_CASE("shadows_slider_lifts_only_dark_region") {
    // +100 shadows must lift a dark patch and leave mid/bright patches
    // untouched (the weight is 0 at and above midgray 0.18).
    Synthetic dark = syntheticFlat(CfaPattern::RGGB, 0.05f, 0.05f, 0.05f);
    Synthetic mid = syntheticFlat(CfaPattern::RGGB, 0.40f, 0.40f, 0.40f);
    Synthetic bright = syntheticFlat(CfaPattern::RGGB, 0.85f, 0.85f, 0.85f);
    Grade g; g.shadows = 100.0f;
    auto d0 = developCpu(dark.cfa.data(), dark.info, dark.meta, Grade{});
    auto d1 = developCpu(dark.cfa.data(), dark.info, dark.meta, g);
    auto m0 = developCpu(mid.cfa.data(), mid.info, mid.meta, Grade{});
    auto m1 = developCpu(mid.cfa.data(), mid.info, mid.meta, g);
    auto b0 = developCpu(bright.cfa.data(), bright.info, bright.meta, Grade{});
    auto b1 = developCpu(bright.cfa.data(), bright.info, bright.meta, g);
    CHECK(centerMean(d1, 1) > centerMean(d0, 1) + 0.02f);         // dark lifted
    CHECK(std::abs(centerMean(m1, 1) - centerMean(m0, 1)) < 0.001f); // mid untouched
    CHECK(std::abs(centerMean(b1, 1) - centerMean(b0, 1)) < 0.001f); // bright untouched
}

TEST_CASE("midtones_slider_moves_only_mids") {
    // +100 midtones must brighten a midgray patch (the tent weight peaks at
    // 0.18) and leave dark and bright patches untouched.
    Synthetic dark = syntheticFlat(CfaPattern::RGGB, 0.02f, 0.02f, 0.02f);
    Synthetic mid = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
    Synthetic bright = syntheticFlat(CfaPattern::RGGB, 0.85f, 0.85f, 0.85f);
    Grade g; g.midtones = 100.0f;
    auto d0 = developCpu(dark.cfa.data(), dark.info, dark.meta, Grade{});
    auto d1 = developCpu(dark.cfa.data(), dark.info, dark.meta, g);
    auto m0 = developCpu(mid.cfa.data(), mid.info, mid.meta, Grade{});
    auto m1 = developCpu(mid.cfa.data(), mid.info, mid.meta, g);
    auto b0 = developCpu(bright.cfa.data(), bright.info, bright.meta, Grade{});
    auto b1 = developCpu(bright.cfa.data(), bright.info, bright.meta, g);
    CHECK(centerMean(m1, 1) > centerMean(m0, 1) + 0.02f);          // mid brightened
    CHECK(std::abs(centerMean(d1, 1) - centerMean(d0, 1)) < 0.001f); // dark untouched
    CHECK(std::abs(centerMean(b1, 1) - centerMean(b0, 1)) < 0.001f); // bright untouched
}

TEST_CASE("contrast_pivots_around_midgray_both_directions") {
    // A midgray patch sits exactly at the pivot (0.18): both +100 and -100
    // leave it put. A darker patch sinks with +100 (slope 2 doubles its
    // distance below the pivot); a brighter patch rises with +100 and sinks
    // toward the pivot with -100 (slope 0 collapses it onto the pivot).
    Synthetic mid = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
    Synthetic darker = syntheticFlat(CfaPattern::RGGB, 0.09f, 0.09f, 0.09f);
    Synthetic brighter = syntheticFlat(CfaPattern::RGGB, 0.36f, 0.36f, 0.36f);
    Grade up; up.contrast = 100.0f;
    Grade down; down.contrast = -100.0f;
    auto base = developCpu(mid.cfa.data(), mid.info, mid.meta, Grade{});
    auto cUp = developCpu(mid.cfa.data(), mid.info, mid.meta, up);
    auto cDown = developCpu(mid.cfa.data(), mid.info, mid.meta, down);
    CHECK(std::abs(centerMean(cUp, 1) - centerMean(base, 1)) < 0.001f);
    CHECK(std::abs(centerMean(cDown, 1) - centerMean(base, 1)) < 0.001f);

    auto k0 = developCpu(darker.cfa.data(), darker.info, darker.meta, Grade{});
    auto kUp = developCpu(darker.cfa.data(), darker.info, darker.meta, up);
    auto kDown = developCpu(darker.cfa.data(), darker.info, darker.meta, down);
    CHECK(centerMean(kUp, 1) < centerMean(k0, 1) - 0.005f);  // sinks (further below pivot)
    CHECK(std::abs(centerMean(kDown, 1) - 0.18f) < 0.001f);  // collapses onto the pivot

    auto j0 = developCpu(brighter.cfa.data(), brighter.info, brighter.meta, Grade{});
    auto jUp = developCpu(brighter.cfa.data(), brighter.info, brighter.meta, up);
    auto jDown = developCpu(brighter.cfa.data(), brighter.info, brighter.meta, down);
    CHECK(centerMean(jUp, 1) > centerMean(j0, 1) + 0.005f);  // rises (further above pivot)
    CHECK(std::abs(centerMean(jDown, 1) - 0.18f) < 0.001f);  // collapses onto the pivot
}

TEST_CASE("black_point_crushes_the_toe") {
    // Positive black point darkens the toe; white stays pinned at 1
    // (x' = lift + (1 - lift) * x maps 1 to 1). Negative lifts the floor:
    // at -100 the input floor 0 maps to 0.25 (faded blacks).
    Synthetic nearBlack = syntheticFlat(CfaPattern::RGGB, 0.04f, 0.04f, 0.04f);
    Synthetic white = syntheticFlat(CfaPattern::RGGB, 1.0f, 1.0f, 1.0f);
    Synthetic black = syntheticFlat(CfaPattern::RGGB, 0.0f, 0.0f, 0.0f);
    Grade crush; crush.blackPoint = 100.0f;
    Grade fade; fade.blackPoint = -100.0f;
    auto n0 = developCpu(nearBlack.cfa.data(), nearBlack.info, nearBlack.meta, Grade{});
    auto n1 = developCpu(nearBlack.cfa.data(), nearBlack.info, nearBlack.meta, crush);
    auto w0 = developCpu(white.cfa.data(), white.info, white.meta, Grade{});
    auto w1 = developCpu(white.cfa.data(), white.info, white.meta, crush);
    auto bf = developCpu(black.cfa.data(), black.info, black.meta, fade);
    CHECK(centerMean(n1, 1) < centerMean(n0, 1) - 0.02f);          // toe darkened
    CHECK(std::abs(centerMean(w1, 1) - centerMean(w0, 1)) < 0.001f); // white pinned
    CHECK(std::abs(centerMean(bf, 1) - 0.25f) < 0.01f);            // floor lifted
}

TEST_CASE("white_point_remaps_the_shoulder") {
    // Positive white point raises the shoulder (scale 4/3 at +100),
    // negative pulls it down (scale 3/4 at -100); black stays pinned at 0.
    Synthetic bright = syntheticFlat(CfaPattern::RGGB, 0.85f, 0.85f, 0.85f);
    Synthetic black = syntheticFlat(CfaPattern::RGGB, 0.0f, 0.0f, 0.0f);
    Grade up; up.whitePoint = 100.0f;
    Grade down; down.whitePoint = -100.0f;
    auto b0 = developCpu(bright.cfa.data(), bright.info, bright.meta, Grade{});
    auto bUp = developCpu(bright.cfa.data(), bright.info, bright.meta, up);
    auto bDown = developCpu(bright.cfa.data(), bright.info, bright.meta, down);
    auto k0 = developCpu(black.cfa.data(), black.info, black.meta, Grade{});
    auto kUp = developCpu(black.cfa.data(), black.info, black.meta, up);
    CHECK(centerMean(bUp, 1) > centerMean(b0, 1) + 0.02f);         // shoulder raised
    CHECK(centerMean(bDown, 1) < centerMean(b0, 1) - 0.02f);       // shoulder pulled
    CHECK(std::abs(centerMean(kUp, 1) - centerMean(k0, 1)) < 0.001f); // black pinned
}

TEST_CASE("saturation_scales_chroma_neutrals_untouched") {
    // +100 saturation grows the channel spread of a colored patch; a
    // neutral patch is bit-identical (its chroma is 0); -100 shrinks the
    // spread but never to exactly 0 (hue preserved, no full desaturation).
    Synthetic color = syntheticFlat(CfaPattern::RGGB, 0.55f, 0.30f, 0.12f);
    Synthetic neutral = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
    Grade sat; sat.saturation = 100.0f;
    Grade desat; desat.saturation = -100.0f;
    auto c0 = developCpu(color.cfa.data(), color.info, color.meta, Grade{});
    auto c1 = developCpu(color.cfa.data(), color.info, color.meta, sat);
    auto c2 = developCpu(color.cfa.data(), color.info, color.meta, desat);
    auto n0 = developCpu(neutral.cfa.data(), neutral.info, neutral.meta, Grade{});
    auto n1 = developCpu(neutral.cfa.data(), neutral.info, neutral.meta, sat);
    const float spread0 = centerMean(c0, 0) - centerMean(c0, 2);
    const float spread1 = centerMean(c1, 0) - centerMean(c1, 2);
    const float spread2 = centerMean(c2, 0) - centerMean(c2, 2);
    CHECK(spread1 > spread0 + 0.02f);  // chroma grew
    CHECK(spread2 < spread0 - 0.02f);  // chroma shrank
    CHECK(spread2 > 0.0f);             // but never fully desaturated
    CHECK(fnv1a(n0) == fnv1a(n1));     // neutral patch bit-identical
}

// ---- RAW metadata toggles (owner request 2026-11-10, spec RAW-07,
// COL-09, RAW-04, RAW-06) ----

TEST_CASE("as_shot_skips_temperature_and_tint_stages") {
    // asShot = true must skip stages 14-15 entirely, whatever tempK/tint
    // hold: the output is bit-identical to a neutral-WB grade. asShot =
    // false runs them (the manual path, locked by golden fixture B).
    auto s = syntheticFlat(CfaPattern::RGGB, 0.4f, 0.4f, 0.4f);
    Grade asShot;
    asShot.asShot = true;
    asShot.tempK = 32000.0f;   // would be strongly warm if it ran
    asShot.tint = 80.0f;       // would be strongly green if it ran
    auto neutral = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto skipped = developCpu(s.cfa.data(), s.info, s.meta, asShot);
    CHECK(fnv1a(skipped) == fnv1a(neutral));   // bit-identical to as-shot WB

    Grade manual;
    manual.asShot = false;
    manual.tempK = 32000.0f;
    manual.tint = 80.0f;
    auto applied = developCpu(s.cfa.data(), s.info, s.meta, manual);
    CHECK(fnv1a(applied) != fnv1a(neutral));  // manual WB really applies
    CHECK(centerMean(applied, 0) > centerMean(neutral, 0) + 0.05f);  // warm
    CHECK(centerMean(applied, 1) > centerMean(neutral, 1) + 0.05f);  // green
}

TEST_CASE("as_shot_with_neutral_manual_values_is_bit_identical") {
    // The load path for OLD persisted grades: a manual grade whose temp/tint
    // happen to be neutral, with asShot explicitly false, must still be
    // bit-identical to the as-shot default — the skipped stages were no-ops
    // anyway. This is what makes "missing field -> default true" safe.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.3f, 0.3f, 0.3f);
    Grade manualNeutral;
    manualNeutral.asShot = false;   // tempK 5600 / tint 0: both stages no-op
    auto a = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto b = developCpu(s.cfa.data(), s.info, s.meta, manualNeutral);
    CHECK(fnv1a(a) == fnv1a(b));
}

TEST_CASE("reconstruction_off_keeps_the_magenta_clip") {
    // Inverse of clipped_highlights_stay_neutral: with
    // highlightReconstruction = false the clipped red sites keep their raw
    // clipped level and the patch reads magenta (red above green) — the
    // sensor-channel artifact the owner wants to be able to keep.
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

    Grade off;
    off.highlightReconstruction = false;
    auto img = developCpu(s.cfa.data(), s.info, s.meta, off);
    auto r = centerMean(img, 0), g = centerMean(img, 1), b = centerMean(img, 2);
    // Magenta-ish: red clearly above green (1.0 raw level vs 0.6).
    CHECK(r > g + 0.05f);
    // And the default still reconstructs (the paired guarantee).
    auto on = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    CHECK(centerMean(on, 0) <= centerMean(on, 1) + 0.01f);
}

TEST_CASE("vignetting_off_darkens_the_corners") {
    // Inverse of lens_shading_flattens_vignetted_flat_field: with
    // keepVignetting = false the shading gain is skipped and the corner
    // keeps the recorded vignette (dark), while the center is unchanged.
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
        const float rr = dx * dx + dy * dy;
        return 1.0f - 0.4f * rr / 0.5f;     // 1.0 center, 0.6 corner
    };

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

    const float flat = 0.5f;
    s.cfa.assign(size_t(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const float u = float(x) / float(w - 1);
            const float v = float(y) / float(h - 1);
            const float val = flat * vignette(u, v) * s.meta.white;
            s.cfa[size_t(y) * w + x] = uint16_t(val + 0.5f);
        }

    Grade off;
    off.keepVignetting = false;
    auto raw = developCpu(s.cfa.data(), s.info, s.meta, off);
    auto corrected = developCpu(s.cfa.data(), s.info, s.meta, Grade{});

    const float rawCenter = raw.rgb[(size_t(h / 2) * w + w / 2) * 3 + 1];
    const float rawCorner = raw.rgb[(size_t(1) * w + 1) * 3 + 1];
    const float fixedCorner = corrected.rgb[(size_t(1) * w + 1) * 3 + 1];
    // The toggle only removes the gain, so with a flat 0.5 center it must
    // sit near 0.5 (the recorded level), and the corner keeps the recorded
    // vignette: visibly darker than the corrected corner. The bilinear
    // demosaic mixes neighboring sites, so the corner pixel reads ~0.70
    // of center rather than the raw 0.60; the corrected corner reads ~1.0.
    CHECK(std::abs(rawCenter - 0.5f) < 0.01f);
    CHECK(rawCorner < rawCenter * 0.75f);
    CHECK(fixedCorner > rawCenter * 0.95f);
    CHECK(rawCorner < fixedCorner - 0.05f);
}

// ---- Detail controls + tonemapping (owner request 2026-11-10 section 3) ----

// A hand-built CFA whose developed image has a sharp vertical EDGE at the
// center column and flat regions on both sides: the left half is 0.30 gray,
// the right half 0.60. After bilinear demosaic the boundary between the
// halves is a one-pixel ramp — exactly the local contrast a sharpening
// stage should amplify, while the flat interiors have none.
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
            // The edge sits between columns 7 and 8 (x < 8 left, else right).
            const float v = (x < 8) ? left : right;
            s.cfa[size_t(y) * w + x] = uint16_t(v * s.meta.white + 0.5f);
        }
    return s;
}

TEST_CASE("sharpness_increases_local_contrast_at_the_edge") {
    // COL-15: +100 sharpness must increase the local contrast across the
    // edge (bright side of the boundary brightens, dark side darkens) and
    // leave the FLAT interiors untouched (a flat region's blur equals its
    // value, so the high-pass delta is exactly 0).
    auto s = syntheticEdgeFixture();
    const uint32_t w = s.info.width, h = s.info.height;

    Grade g; g.sharpness = 100.0f;
    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto sharp = developCpu(s.cfa.data(), s.info, s.meta, g);

    // Local contrast across the edge: max minus min of the center row.
    auto rowExtrema = [&](const ImageF& img, uint32_t y, float& lo, float& hi) {
        lo = hi = img.rgb[(size_t(y) * w) * 3 + 1];
        for (uint32_t x = 0; x < w; ++x) {
            const float v = img.rgb[(size_t(y) * w + x) * 3 + 1];
            lo = std::min(lo, v);
            hi = std::max(hi, v);
        }
    };
    float lo0, hi0, lo1, hi1;
    rowExtrema(plain, h / 2, lo0, hi0);
    rowExtrema(sharp, h / 2, lo1, hi1);
    CHECK(hi1 - lo1 > (hi0 - lo0) + 0.01f);   // edge contrast grew
    // The overshoot directions: bright side brighter, dark side darker.
    CHECK(hi1 > hi0 + 0.005f);
    CHECK(lo1 < lo0 - 0.005f);

    // Flat interiors untouched: columns 0-4 (left) and 11-15 (right) are far
    // from the edge; their pixels must be exactly equal.
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x <= 4; ++x) {
            const size_t i = (size_t(y) * w + x) * 3;
            CHECK(sharp.rgb[i + 0] == plain.rgb[i + 0]);
            CHECK(sharp.rgb[i + 1] == plain.rgb[i + 1]);
            CHECK(sharp.rgb[i + 2] == plain.rgb[i + 2]);
        }
        for (uint32_t x = 11; x < w; ++x) {
            const size_t i = (size_t(y) * w + x) * 3;
            CHECK(sharp.rgb[i + 0] == plain.rgb[i + 0]);
            CHECK(sharp.rgb[i + 1] == plain.rgb[i + 1]);
            CHECK(sharp.rgb[i + 2] == plain.rgb[i + 2]);
        }
    }

    // Negative sharpness softens: the edge ramp flattens toward its local
    // mean. The row-extrema range cannot shrink (the flat interiors pin
    // it), so the right metric is the local delta ACROSS the ramp: the
    // bright-side ramp pixel minus the dark-side ramp pixel.
    Grade soft; soft.sharpness = -100.0f;
    auto softImg = developCpu(s.cfa.data(), s.info, s.meta, soft);
    const uint32_t row = h / 2;
    auto rampDelta = [&](const ImageF& img) {
        // The demosaic ramp: column 7 blends left/right (dark side of the
        // boundary), column 8 blends right/left (bright side).
        return img.rgb[(size_t(row) * w + 8) * 3 + 1]
             - img.rgb[(size_t(row) * w + 7) * 3 + 1];
    };
    CHECK(rampDelta(softImg) < rampDelta(plain) - 0.005f);
}

TEST_CASE("sharpness_shifts_luma_only_no_chroma_shift") {
    // The unsharp mask adds the SAME delta to r, g and b, so channel
    // DIFFERENCES are preserved exactly (no chroma halos, hue preserved).
    auto s = syntheticEdgeFixture();

    Grade g; g.sharpness = 80.0f;
    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto sharp = developCpu(s.cfa.data(), s.info, s.meta, g);
    for (size_t i = 0; i < plain.rgb.size(); i += 3) {
        const float d0 = plain.rgb[i + 0] - plain.rgb[i + 1];
        const float d1 = sharp.rgb[i + 0] - sharp.rgb[i + 1];
        CHECK(std::abs(d0 - d1) < 1e-6f);
        const float e0 = plain.rgb[i + 2] - plain.rgb[i + 1];
        const float e1 = sharp.rgb[i + 2] - sharp.rgb[i + 1];
        CHECK(std::abs(e0 - e1) < 1e-6f);
    }
}

TEST_CASE("detail_moves_fine_texture_at_a_smaller_scale") {
    // COL-15: Detail is a SECOND unsharp pass at a smaller radius (0.75 px
    // vs sharpness's 1.5 px) — its own control, not a duplicate. The scale
    // separation shows up in the HALO EXTENT around an edge, not in the
    // total delta: for Gaussian high-pass kernels a wider radius always
    // responds at least as strongly at every frequency, so "detail moves
    // the texture more" is not the right signature. What separates the
    // scales is how FAR from the edge the effect reaches. The kernels'
    // second-neighbor taps expose it: at radius 1.5 the c1 tap weight is
    // exp(-2/1.5^2) = 0.411, at radius 0.75 it is exp(-2/0.75^2) = 0.029 —
    // so sharpness bleeds visibly into the 2-px neighborhood while detail
    // is confined to the immediate 1-px ramp.
    auto s = syntheticEdgeFixture();
    const uint32_t w = s.info.width, h = s.info.height;

    auto plain = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    Grade gd; gd.detail = 100.0f;
    auto detailed = developCpu(s.cfa.data(), s.info, s.meta, gd);
    Grade gs; gs.sharpness = 100.0f;
    auto sharpened = developCpu(s.cfa.data(), s.info, s.meta, gs);

    // Per-pixel absolute delta from the neutral render, green channel of
    // the center row.
    const uint32_t row = h / 2;
    auto rowDelta = [&](const ImageF& b) {
        double sum = 0;
        for (uint32_t x = 0; x < w; ++x) {
            const size_t i = (size_t(row) * w + x) * 3 + 1;
            sum += std::abs(double(plain.rgb[i]) - double(b.rgb[i]));
        }
        return sum;
    };
    const double dDetail = rowDelta(detailed);
    const double dSharp = rowDelta(sharpened);
    CHECK(dDetail > 0.0);                       // detail really applies
    CHECK(dSharp > 0.0);                       // sharpness really applies

    // The scale separation: the halo's REACH. The demosaic ramp is ~2 px
    // wide (green at column 8, red/blue ramping at column 7), so the
    // immediate neighborhood responds to both kernels. One step further
    // out — columns 5 and 10 — only the wide kernel's second-neighbor tap
    // (weight 0.411 at radius 1.5 vs 0.029 at radius 0.75) still reaches:
    // sharpness's halo there is ~8x detail's.
    const size_t i5 = (size_t(row) * w + 5) * 3 + 1;
    const size_t i10 = (size_t(row) * w + 10) * 3 + 1;
    const double haloSharp = std::abs(double(plain.rgb[i5]) - double(sharpened.rgb[i5]))
                           + std::abs(double(plain.rgb[i10]) - double(sharpened.rgb[i10]));
    const double haloDetail = std::abs(double(plain.rgb[i5]) - double(detailed.rgb[i5]))
                            + std::abs(double(plain.rgb[i10]) - double(detailed.rgb[i10]));
    CHECK(haloSharp > 0.003);                   // the wide kernel reaches out
    CHECK(haloDetail < haloSharp * 0.25);       // the fine kernel does not

    // And the two controls are INDEPENDENT: detail alone (sharpness 0)
    // moves the edge neighborhood, proving it is its own stage.
    CHECK(dDetail > 0.01);
}

TEST_CASE("tonemap_off_is_bit_identical_passthrough") {
    // tonemap = 0 (the default) must be an exact passthrough: same FNV-1a
    // hash as the pre-tonemapping pipeline on a graded fixture, and a
    // default Grade must equal an explicitly neutral one.
    auto s = syntheticFlat(CfaPattern::RGGB, 0.55f, 0.30f, 0.12f);
    Grade g;
    g.exposureEv = 0.7f;
    g.highlights = -35.0f;
    g.asShot = false;
    g.tempK = 7200.0f;
    g.tint = 12.0f;
    g.tonemap = 0;
    auto a = developCpu(s.cfa.data(), s.info, s.meta, g);
    auto b = developCpu(s.cfa.data(), s.info, s.meta, neutralToneGrade());
    // Same graded fixture as golden test B but tonemap explicitly 0: the
    // hash must equal the captured pre-tonemapping value.
    CHECK(fnv1a(a) == 0x38150540c239af83ULL);
    // Default Grade vs explicitly-neutral Grade: bit identical.
    auto c = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto d = developCpu(s.cfa.data(), s.info, s.meta, neutralToneGrade());
    CHECK(fnv1a(c) == fnv1a(d));
}

TEST_CASE("aces_tonemap_maps_known_inputs_to_known_outputs") {
    // ACES (tonemap 1): the Narkowicz 2015 fit. Fixture values computed in
    // float32 from y = x(2.51x + 0.03) / (x(2.43x + 0.59) + 0.14), clamped
    // to [0,1]:
    //   0.18 -> 0.26689893   (midgray anchor)
    //   1.00 -> 0.80379748   (1.0 anchor)
    //   4.00 -> 0.97341710   (4.0 anchor, deep highlight compression)
    // A flat patch developed with tonemap = 1 lands on the anchor (the
    // develop pipeline is the identity for a linear flat patch: normalize
    // -> identity matrix -> no grade).
    auto s = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
    Grade g; g.tonemap = 1;
    auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
    // The fixture quantizes: 0.18 * 959 + 0.5 rounds to code 172, which
    // develops to 172/959 = 0.17935349 (not exactly 0.18), so the anchor is
    // the fit evaluated at the ACTUAL developed value.
    CHECK(std::abs(centerMean(img, 1) - 0.26581848f) < 1e-4f);

    Synthetic s1 = syntheticFlat(CfaPattern::RGGB, 1.0f, 1.0f, 1.0f);
    Grade g1; g1.tonemap = 1;
    auto img1 = developCpu(s1.cfa.data(), s1.info, s1.meta, g1);
    CHECK(std::abs(centerMean(img1, 1) - 0.80379747f) < 1e-4f);

    Synthetic s4 = syntheticFlat(CfaPattern::RGGB, 0.25f, 0.25f, 0.25f);
    Grade g4; g4.tonemap = 1; g4.exposureEv = 4.0f;  // 0.25 * 16 = 4.0
    auto img4 = developCpu(s4.cfa.data(), s4.info, s4.meta, g4);
    // Quantized fixture: 239/959 * 16 = 3.98748697 (not exactly 4.0).
    CHECK(std::abs(centerMean(img4, 1) - 0.97323133f) < 1e-4f);
}

TEST_CASE("agx_tonemap_maps_known_inputs_to_known_outputs") {
    // AgX (tonemap 2): 2022 Filmic AgX essential fit (Sobotka), minimal
    // implementation constants, Punchy look. Fixture values computed in
    // float64 through the full minimal pipeline (inset matrix, log2 encode
    // clamped to [-12.47393, 4.026069] EV around 0.18, 6th-order contrast
    // polynomial clamped to [0,1], Punchy power 1.35 / sat 1.4, outset
    // matrix, gamma 2.2), evaluated at the ACTUAL developed values (the
    // fixtures quantize: 0.18 -> 172/959, 0.25*16 -> 239/959*16):
    //   0.18 -> 0.45270696   (midgray anchor)
    //   1.00 -> 0.85659844   (1.0 anchor)
    //   4.00 -> 0.99535261   (4.0 anchor; AgX compresses highlights harder
    //                          than ACES at the top of the range)
    auto s = syntheticFlat(CfaPattern::RGGB, 0.18f, 0.18f, 0.18f);
    Grade g; g.tonemap = 2;
    auto img = developCpu(s.cfa.data(), s.info, s.meta, g);
    CHECK(std::abs(centerMean(img, 1) - 0.45270696f) < 1e-4f);

    Synthetic s1 = syntheticFlat(CfaPattern::RGGB, 1.0f, 1.0f, 1.0f);
    Grade g1; g1.tonemap = 2;
    auto img1 = developCpu(s1.cfa.data(), s1.info, s1.meta, g1);
    CHECK(std::abs(centerMean(img1, 1) - 0.85659844f) < 1e-4f);

    Synthetic s4 = syntheticFlat(CfaPattern::RGGB, 0.25f, 0.25f, 0.25f);
    Grade g4; g4.tonemap = 2; g4.exposureEv = 4.0f;  // 0.25 * 16 = 4.0
    auto img4 = developCpu(s4.cfa.data(), s4.info, s4.meta, g4);
    CHECK(std::abs(centerMean(img4, 1) - 0.99535261f) < 1e-4f);
}

TEST_CASE("tonemap_curves_are_monotone_over_scene_range") {
    // Both fits must be monotone over the useful scene range: a brighter
    // input never maps darker. Sampled through the develop path with
    // exposure pushes on a flat patch (0.05 * 2^k, k = 0..8 -> 0.05..12.8).
    Synthetic s = syntheticFlat(CfaPattern::RGGB, 0.05f, 0.05f, 0.05f);
    float prevA = -1.0f, prevX = -1.0f;
    for (int k = 0; k <= 8; ++k) {
        const float ev = float(k);
        Grade ga; ga.tonemap = 1; ga.exposureEv = ev;
        Grade gx; gx.tonemap = 2; gx.exposureEv = ev;
        auto a = developCpu(s.cfa.data(), s.info, s.meta, ga);
        auto x = developCpu(s.cfa.data(), s.info, s.meta, gx);
        const float va = centerMean(a, 1);
        const float vx = centerMean(x, 1);
        if (k > 0) {
            CHECK(va >= prevA - 1e-6f);      // ACES monotone
            CHECK(vx >= prevX - 1e-6f);      // AgX monotone
        }
        prevA = va;
        prevX = vx;
    }
    // Both compress: the top of the range (0.05 * 2^8 = 12.8 linear) maps
    // at or below 1.0 — ACES saturates to exactly 1.0 there (the fit clamps
    // to the display box; y(12.8) > 1), AgX stays below. Both must stay
    // within the box.
    CHECK(prevA <= 1.0f);
    CHECK(prevX < 1.0f);
    // And both brighten midtones relative to the off state (the point of a
    // display curve): 0.05*2^1 = 0.1 linear maps above 0.1.
    Grade g1; g1.tonemap = 1; g1.exposureEv = 1.0f;
    Grade g2; g2.tonemap = 2; g2.exposureEv = 1.0f;
    auto a1 = developCpu(s.cfa.data(), s.info, s.meta, g1);
    auto x1 = developCpu(s.cfa.data(), s.info, s.meta, g2);
    CHECK(centerMean(a1, 1) > 0.1f);
    CHECK(centerMean(x1, 1) > 0.1f);
}
