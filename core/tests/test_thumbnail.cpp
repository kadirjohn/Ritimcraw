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

// ---- P5 (2026-11-25): the reduction is a same-colour N x N BINNING --------

namespace {

// Brute-force reference of the documented math (the header's block mean,
// round-half-up, cells that exist only).
uint16_t refBin(const std::vector<uint16_t>& cfa, uint32_t w, uint32_t h, uint32_t n,
                uint32_t ocx, uint32_t ocy, uint32_t dx, uint32_t dy) {
    const uint32_t cellCols = w / 2, cellRows = h / 2;
    uint64_t sum = 0, cnt = 0;
    for (uint32_t j = 0; j < n; ++j) {
        for (uint32_t i = 0; i < n; ++i) {
            const uint32_t cx = ocx * n + i, cy = ocy * n + j;
            if (cx >= cellCols || cy >= cellRows) continue;
            sum += cfa[static_cast<size_t>(2 * cy + dy) * w + (2 * cx + dx)];
            ++cnt;
        }
    }
    return static_cast<uint16_t>((sum + cnt / 2) / cnt);
}

std::vector<uint16_t> noisyCfa(uint32_t w, uint32_t h, int amp, uint32_t seed) {
    std::vector<uint16_t> v(static_cast<size_t>(w) * h);
    uint32_t x = seed;
    for (auto& s : v) {
        x ^= x << 13; x ^= x >> 17; x ^= x << 5;
        s = static_cast<uint16_t>(500 + static_cast<int>(x % (2 * amp + 1)) - amp);
    }
    return v;
}

} // namespace

TEST_CASE("binned_reduction_matches_the_documented_block_mean_site_by_site") {
    for (uint32_t n : {2u, 3u, 5u}) {
        const uint32_t w = 66, h = 50; // cells 33 x 25: partial blocks at the right/bottom edges
        auto src = noisyCfa(w, h, 200, 12345u + n);
        std::vector<uint16_t> out;
        REQUIRE(reduceCfaBinned(src.data(), w, h, static_cast<int>(n), out));
        const uint32_t outW = thumbnailWidth(w, static_cast<int>(n));
        const uint32_t outH = thumbnailHeight(h, static_cast<int>(n));
        REQUIRE(out.size() == static_cast<size_t>(outW) * outH);
        for (uint32_t ocy = 0; ocy < outH / 2; ++ocy)
            for (uint32_t ocx = 0; ocx < outW / 2; ++ocx)
                for (uint32_t dy = 0; dy < 2; ++dy)
                    for (uint32_t dx = 0; dx < 2; ++dx)
                        CHECK(out[static_cast<size_t>(2 * ocy + dy) * outW + 2 * ocx + dx] ==
                              refBin(src, w, h, n, ocx, ocy, dx, dy));
    }
}

TEST_CASE("binning_keeps_the_2x2_pattern_and_never_mixes_colours") {
    // Four distinct per-site levels (RGGB-like): every output cell must keep them exactly.
    const uint32_t w = 90, h = 60;
    std::vector<uint16_t> src(static_cast<size_t>(w) * h);
    const uint16_t lv[2][2] = {{100, 400}, {700, 1000}};
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) src[static_cast<size_t>(y) * w + x] = lv[y & 1][x & 1];
    for (int n : {2, 3, 4, 7}) {
        std::vector<uint16_t> out;
        REQUIRE(reduceCfaBinned(src.data(), w, h, n, out));
        const uint32_t outW = thumbnailWidth(w, n);
        for (size_t i = 0; i < out.size(); ++i) {
            const uint32_t x = static_cast<uint32_t>(i % outW), y = static_cast<uint32_t>(i / outW);
            CHECK(out[i] == lv[y & 1][x & 1]);
        }
    }
}

TEST_CASE("binning_averages_what_decimation_used_to_alias") {
    // Alternate cells 0 / 1000 along x (a period-2-cell stripe). The old
    // one-cell-per-block copy at N=2 returned 0 or 1000 depending on phase;
    // the binned mean is the honest 500 everywhere.
    const uint32_t w = 64, h = 32;
    std::vector<uint16_t> src(static_cast<size_t>(w) * h);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) src[static_cast<size_t>(y) * w + x] = ((x / 2) & 1) ? 1000 : 0;
    std::vector<uint16_t> out;
    REQUIRE(reduceCfaBinned(src.data(), w, h, 2, out));
    for (uint16_t v : out) CHECK(v == 500);
}

TEST_CASE("binning_cuts_sensor_noise_by_about_n") {
    const uint32_t w = 384, h = 216;
    auto src = noisyCfa(w, h, 60, 987654321u);
    auto stddev = [](const std::vector<uint16_t>& v) {
        double m = 0; for (auto s : v) m += s; m /= v.size();
        double q = 0; for (auto s : v) q += (s - m) * (s - m);
        return std::sqrt(q / v.size());
    };
    std::vector<uint16_t> out;
    REQUIRE(reduceCfaBinned(src.data(), w, h, 3, out));
    // N=3 averages 9 independent samples: sigma / 3 (the old decimation kept sigma).
    CHECK(stddev(out) < stddev(src) * 0.45);
    CHECK(stddev(out) > stddev(src) * 0.25);
}

TEST_CASE("export_stride_one_is_the_source_bit_for_bit") {
    // The export guarantee: N = 1 is an identity, so nothing about the
    // export path changed with the preview fix. Even and odd frame sizes.
    for (auto dims : {std::pair<uint32_t, uint32_t>{64, 48}, {65, 49}}) {
        auto src = noisyCfa(dims.first, dims.second, 300, 42u);
        std::vector<uint16_t> out;
        REQUIRE(reduceCfaBinned(src.data(), dims.first, dims.second, 1, out));
        const uint32_t outW = thumbnailWidth(dims.first, 1), outH = thumbnailHeight(dims.second, 1);
        REQUIRE(out.size() == static_cast<size_t>(outW) * outH);
        for (uint32_t y = 0; y < outH; ++y)
            for (uint32_t x = 0; x < outW; ++x)
                CHECK(out[static_cast<size_t>(y) * outW + x] == src[static_cast<size_t>(y) * dims.first + x]);
    }
}

TEST_CASE("binning_rejects_invalid_input_without_throwing") {
    std::vector<uint16_t> out{1, 2, 3};
    CHECK_FALSE(reduceCfaBinned(nullptr, 64, 64, 2, out));
    CHECK(out.empty());
    std::vector<uint16_t> src(64 * 64, 100);
    CHECK_FALSE(reduceCfaBinned(src.data(), 1, 64, 2, out));
    CHECK_FALSE(reduceCfaBinned(src.data(), 64, 64, 0, out));
}

TEST_CASE("reconstructed_binning_matches_plain_binning_when_unclipped") {
    auto s = syntheticFlatRggb(64, 64, 0.35f);
    std::vector<uint16_t> plain, recon;
    REQUIRE(reduceCfaBinned(s.cfa.data(), s.info.width, s.info.height, 4, plain));
    REQUIRE(reduceCfaBinnedReconstructed(s.cfa.data(), s.info, s.meta, 4, true, RawLevelsOverride(), recon));
    CHECK(plain == recon);
}

TEST_CASE("binned_preview_of_partially_clipped_cells_stays_neutral_not_magenta") {
    // Regression for tier-dependent pembe leke (magenta highlights):
    // When a bin averages clipped bright cells (e.g. a thin lamp/neon stroke)
    // with dark neighbor cells, plain raw binning dilutes the raw code below
    // white so neither stage 3 nor highlightRecovery flags the clip, turning
    // the bin magenta after WB and Highlights -100. Pre-binning reconstruction
    // must keep both neutral and recovery preview tiers neutral.
    const uint32_t w = 64, h = 64;
    Synthetic s;
    s.info.width = w; s.info.height = h; s.info.bitDepth = 16;
    s.info.cfa = CfaPattern::RGGB;
    s.info.activeArea = Rect{0, 0, int(w), int(h)};
    for (int i = 0; i < 4; ++i) s.meta.black[i] = 64;
    s.meta.white = 1023;
    s.meta.asShotNeutral[0] = 1.0f / 2.56f;
    s.meta.asShotNeutral[1] = 1.0f;
    s.meta.asShotNeutral[2] = 1.0f / 1.98f;
    s.meta.wbGains[0] = 2.56f; s.meta.wbGains[1] = 1.0f; s.meta.wbGains[2] = 1.98f;
    s.meta.colorMatrix[0] = 1; s.meta.colorMatrix[4] = 1; s.meta.colorMatrix[8] = 1;

    // Half of the cells in every 4x4 bin are fully clipped white (1023) and
    // half are dark neutral (0.05 post-WB), so plain binning averages to ~543
    // (well below 1023) and becomes strongly magenta after WB.
    s.cfa.assign(size_t(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const bool clippedCell = ((x / 2) & 1) == 0;
            if (clippedCell) {
                s.cfa[size_t(y) * w + x] = 1023;
            } else {
                const int r = y & 1, c = x & 1;
                const float gWb = (r == 0 && c == 0) ? 2.56f : ((r == 1 && c == 1) ? 1.98f : 1.0f);
                const float raw = 64.0f + (0.05f / gWb) * 959.0f;
                s.cfa[size_t(y) * w + x] = static_cast<uint16_t>(std::lround(raw));
            }
        }
    }

    for (bool rec : {false, true}) {
        Grade g;
        g.highlights = -100.0f;
        g.highlightReconstruction = true;
        g.highlightRecovery = rec;
        const ImageF thumb = developThumbnail(s.cfa.data(), s.info, s.meta, g, 4);
        REQUIRE(thumb.w > 0);
        const float r = centerMean(thumb, 0), gr = centerMean(thumb, 1), b = centerMean(thumb, 2);
        CAPTURE(rec);
        CHECK(std::fabs(r - gr) < 0.02f);
        CHECK(std::fabs(b - gr) < 0.02f);
    }

    // With both reconstruction and recovery explicitly off, the raw clip stays un-capped.
    Grade off;
    off.highlights = -100.0f;
    off.highlightReconstruction = false;
    off.highlightRecovery = false;
    const ImageF thumbOff = developThumbnail(s.cfa.data(), s.info, s.meta, off, 4);
    CHECK(centerMean(thumbOff, 0) > centerMean(thumbOff, 1) + 0.25f);
    CHECK(centerMean(thumbOff, 2) > centerMean(thumbOff, 1) + 0.15f);
}
