// Thumbnail decimation tests. Synthetic flat CFA buffers (same builder shape
// as test_develop.cpp) — no real clip needed.
//
// The vignette map complicates the borders (the thumbnail samples shading
// gains at the reduced image's normalized coordinates), so the flat-field
// comparison with developCpu is made on the center region only.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>

#include <cmath>
#include <cstdint>
#include <vector>

using namespace rawedit;

namespace {

// Flat 512x512 RGGB CFA at a uniform linear level, black 64 / white 1023
// (the Xiaomi 15 Ultra container values). Identical to test_develop.cpp's
// syntheticFlat for the RGGB case, repeated here so the two test binaries
// stay independent.
struct Synthetic {
    std::vector<uint16_t> cfa;
    FrameInfo info;
    RawMeta meta;
};

Synthetic syntheticFlatRggb(uint32_t w, uint32_t h, float level) {
    Synthetic s;
    s.info.width = w;
    s.info.height = h;
    s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, static_cast<int>(w), static_cast<int>(h)};

    s.meta.black[0] = 64; s.meta.black[1] = 64;
    s.meta.black[2] = 64; s.meta.black[3] = 64;
    s.meta.white = 1023;
    s.meta.wbGains[0] = 1.0f; s.meta.wbGains[1] = 1.0f; s.meta.wbGains[2] = 1.0f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    const uint16_t v = static_cast<uint16_t>(64 + level * (1023 - 64));
    s.cfa.assign(static_cast<size_t>(w) * h, v);
    return s;
}

// Mean of channel c over the center quarter (avoids border and edge-clamping
// effects; the thumbnail's center region is what the mission asks to compare).
float centerMean(const ImageF& img, int c) {
    const uint32_t x0 = img.w / 4, x1 = img.w * 3 / 4;
    const uint32_t y0 = img.h / 4, y1 = img.h * 3 / 4;
    double sum = 0;
    size_t n = 0;
    for (uint32_t y = y0; y < y1; ++y)
        for (uint32_t x = x0; x < x1; ++x) {
            sum += img.rgb[(static_cast<size_t>(y) * img.w + x) * 3 + c];
            ++n;
        }
    return n ? static_cast<float>(sum / n) : 0.0f;
}

} // namespace

TEST_CASE("thumbnail_dims_follow_documented_math") {
    // outW = 2*ceil(width/2/N), outH = 2*ceil(height/2/N).
    CHECK(thumbnailWidth(512, 8) == 64);
    CHECK(thumbnailHeight(512, 8) == 64);
    CHECK(thumbnailWidth(3840, 8) == 480);
    CHECK(thumbnailHeight(2160, 8) == 270);
    CHECK(thumbnailWidth(3840, 1) == 3840);
    CHECK(thumbnailHeight(2160, 1) == 2160);
    // ceil: 5 cells (512/2=256 -> 32 cells exactly; 513/2=256 cells, N=8 -> 32;
    // 515/2=257 cells, N=8 -> 33 -> 66).
    CHECK(thumbnailWidth(513, 8) == 64);
    CHECK(thumbnailWidth(515, 8) == 66);
    // Invalid inputs -> 0.
    CHECK(thumbnailWidth(0, 8) == 0);
    CHECK(thumbnailWidth(512, 0) == 0);
    CHECK(thumbnailWidth(512, -1) == 0);
    CHECK(thumbnailWidth(1, 8) == 0);
}

TEST_CASE("flat_512_rggb_stride8_center_matches_develop_cpu") {
    const uint32_t w = 512, h = 512;
    auto s = syntheticFlatRggb(w, h, 0.18f);

    auto full = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto thumb = developThumbnail(s.cfa.data(), s.info, s.meta, Grade{}, 8);

    // Documented dims: 2*ceil(256/8) = 64 per side.
    REQUIRE(thumb.w == 64);
    REQUIRE(thumb.h == 64);
    REQUIRE(thumb.rgb.size() == static_cast<size_t>(64) * 64 * 3);

    // Center-region means must match the full develop within 1% of the level.
    // The flat field is uniform, so decimation cannot change the value; only
    // the vignette (empty here) and border sampling differ.
    for (int c = 0; c < 3; ++c) {
        const float mf = centerMean(full, c);
        const float mt = centerMean(thumb, c);
        CAPTURE(c);
        CAPTURE(mf);
        CAPTURE(mt);
        CHECK(std::fabs(mt - mf) < 0.002f);
        CHECK(mf > 0.1f); // sanity: actually developed, not zeros
    }
}

TEST_CASE("flat_512_rggb_stride8_with_vignette_center_matches") {
    // Same comparison with a real lens shading map: the map is smooth, so
    // the center quarter of the decimated image still matches the full
    // develop within a loose tolerance (the vignette's curvature is tiny
    // over the center quarter).
    const uint32_t w = 512, h = 512;
    auto s = syntheticFlatRggb(w, h, 0.5f);

    // 17x13 shading map (the real clip's dimensions), all four planes the
    // same mild radial gain.
    const uint32_t sw = 17, sh = 13;
    s.meta.shadingW = sw;
    s.meta.shadingH = sh;
    s.meta.lensShading.assign(4 * sw * sh, 0.0f);
    for (uint32_t y = 0; y < sh; ++y)
        for (uint32_t x = 0; x < sw; ++x) {
            const float u = float(x) / float(sw - 1), v = float(y) / float(sh - 1);
            const float dx = u - 0.5f, dy = v - 0.5f;
            const float g = 1.0f + 0.2f * (dx * dx + dy * dy);
            for (int plane = 0; plane < 4; ++plane)
                s.meta.lensShading[plane * sw * sh + y * sw + x] = g;
        }

    auto full = developCpu(s.cfa.data(), s.info, s.meta, Grade{});
    auto thumb = developThumbnail(s.cfa.data(), s.info, s.meta, Grade{}, 8);

    REQUIRE(thumb.w == 64);
    REQUIRE(thumb.h == 64);
    for (int c = 0; c < 3; ++c) {
        const float mf = centerMean(full, c);
        const float mt = centerMean(thumb, c);
        CAPTURE(c);
        CAPTURE(mf);
        CAPTURE(mt);
        CHECK(std::fabs(mt - mf) < 0.01f);
    }
}

TEST_CASE("stride1_equals_develop_cpu_within_1e-5") {
    const uint32_t w = 512, h = 512;
    auto s = syntheticFlatRggb(w, h, 0.18f);
    // Non-trivial grade so the comparison exercises the whole pipeline:
    // WB gains + exposure (color matrix stays identity; a non-identity
    // matrix is fine too since both paths share developCpu).
    Grade g;
    g.exposureEv = 0.5f;
    s.meta.wbGains[0] = 2.0f;
    s.meta.wbGains[1] = 1.0f;
    s.meta.wbGains[2] = 1.5f;

    auto full = developCpu(s.cfa.data(), s.info, s.meta, g);
    auto thumb = developThumbnail(s.cfa.data(), s.info, s.meta, g, 1);

    // strideEvery=1 decimates nothing: dims and every sample must match.
    REQUIRE(thumb.w == full.w);
    REQUIRE(thumb.h == full.h);
    REQUIRE(thumb.rgb.size() == full.rgb.size());
    double maxDiff = 0.0;
    for (size_t i = 0; i < full.rgb.size(); ++i) {
        const double d = std::fabs(static_cast<double>(thumb.rgb[i])
                                   - static_cast<double>(full.rgb[i]));
        if (d > maxDiff)
            maxDiff = d;
    }
    CHECK(maxDiff <= 1e-5);
}

TEST_CASE("invalid_inputs_return_empty_image") {
    auto s = syntheticFlatRggb(8, 8, 0.5f);
    CHECK(developThumbnail(nullptr, s.info, s.meta, Grade{}, 8).w == 0);
    CHECK(developThumbnail(s.cfa.data(), s.info, s.meta, Grade{}, 0).w == 0);
    CHECK(developThumbnail(s.cfa.data(), s.info, s.meta, Grade{}, -2).w == 0);
    FrameInfo tiny = s.info;
    tiny.width = 1;
    tiny.height = 1;
    CHECK(developThumbnail(s.cfa.data(), tiny, s.meta, Grade{}, 8).w == 0);
}
