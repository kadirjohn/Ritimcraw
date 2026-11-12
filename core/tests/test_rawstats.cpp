// RAW histogram stats tests (feature N6). All fixtures are synthetic CFA
// buffers — no real clip needed. The site-color mapping and the
// black=64/white=1023-style fixtures follow test_develop.cpp's
// conventions, and the level-resolution cases mirror Develop stage 1's
// fallback rules (frame dynamic levels win; -1 sentinel = fall back to
// the container RawMeta values).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/RawStats.h>
#include <rawedit/Source.h>

#include <vector>

using namespace rawedit;

namespace {

// Site color at (x, y) for an arrangement — the same mapping
// test_develop.cpp's synthetic builder uses (0 = R, 1 = G, 2 = B).
int siteAt(CfaPattern pattern, uint32_t x, uint32_t y) {
    switch (pattern) {
        case CfaPattern::RGGB: return (y % 2 == 0) ? (x % 2 == 0 ? 0 : 1) : (x % 2 == 0 ? 1 : 2);
        case CfaPattern::GRBG: return (y % 2 == 0) ? (x % 2 == 0 ? 1 : 0) : (x % 2 == 0 ? 2 : 1);
        case CfaPattern::GBRG: return (y % 2 == 0) ? (x % 2 == 0 ? 1 : 2) : (x % 2 == 0 ? 0 : 1);
        case CfaPattern::BGGR: return (y % 2 == 0) ? (x % 2 == 0 ? 2 : 1) : (x % 2 == 0 ? 1 : 0);
    }
    return 0;
}

// Builds a w*h CFA with a distinct raw value per site color
// (vr, vg, vb) under [pattern]. The caller picks values that land on
// exact bin boundaries for the levels under test.
std::vector<uint16_t> synthetic(CfaPattern pattern, uint16_t vr, uint16_t vg,
                                 uint16_t vb, uint32_t w = 8, uint32_t h = 8) {
    std::vector<uint16_t> cfa(static_cast<size_t>(w) * h, 0);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const int site = siteAt(pattern, x, y);
            cfa[static_cast<size_t>(y) * w + x] =
                site == 0 ? vr : (site == 1 ? vg : vb);
        }
    return cfa;
}

// Frame levels all -1 (the sentinel) — the caller then passes container
// fallbacks, exactly like the JNI does with FrameInfo + RawMeta.
const int32_t kNoBlack[4] = {-1, -1, -1, -1};
const int32_t kNoWhite = -1;

// Uniform mid-gray under black=64/white=1024: every channel value 544
// normalizes to exactly 0.5 -> bin 128.
const int32_t kBlack64[4] = {64, 64, 64, 64};

uint32_t binSum(const RawHistogramStats& s, int ch) {
    uint32_t sum = 0;
    for (int b = 0; b < RawHistogramStats::kBins; ++b) sum += s.bins[ch][b];
    return sum;
}

} // namespace

TEST_CASE("uniform_frame_lands_in_a_single_bin_per_channel") {
    // 544 = 64 + 0.5 * (1024 - 64): normalized exactly 0.5 -> bin 128.
    const auto cfa = synthetic(CfaPattern::RGGB, 544, 544, 544);
    const RawHistogramStats s = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    for (int ch = 0; ch < 3; ++ch) {
        CHECK(binSum(s, ch) == s.samples[ch]);
        for (int b = 0; b < RawHistogramStats::kBins; ++b) {
            if (b == 128) continue;
            CHECK(s.bins[ch][b] == 0);
        }
        CHECK(s.bins[ch][128] == s.samples[ch]);
    }
    // Site counts in an 8x8: R 16, G 32 (both green sites), B 16.
    CHECK(s.samples[0] == 16);
    CHECK(s.samples[1] == 32);
    CHECK(s.samples[2] == 16);
    // Nothing clips at mid-gray.
    CHECK(s.whiteClips[0] == 0);
    CHECK(s.whiteClips[1] == 0);
    CHECK(s.whiteClips[2] == 0);
    CHECK(s.blackClips[0] == 0);
    CHECK(s.blackClips[1] == 0);
    CHECK(s.blackClips[2] == 0);
    CHECK(s.blackUsed[0] == 64);
    CHECK(s.whiteUsed == 1024);
}

TEST_CASE("saturated_frame_counts_white_clips") {
    // Every site at white: bin 255, full white-clip counts.
    const auto cfa = synthetic(CfaPattern::RGGB, 1024, 1024, 1024);
    const RawHistogramStats s = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    for (int ch = 0; ch < 3; ++ch) {
        CHECK(s.bins[ch][255] == s.samples[ch]);
        CHECK(s.whiteClips[ch] == s.samples[ch]);
        CHECK(s.blackClips[ch] == 0);
    }
    CHECK(s.whiteClips[0] == 16);
    CHECK(s.whiteClips[1] == 32);
    CHECK(s.whiteClips[2] == 16);
}

TEST_CASE("black_level_frame_counts_shadow_clips") {
    // Every site at black: bin 0, full shadow-clip counts.
    const auto cfa = synthetic(CfaPattern::RGGB, 64, 64, 64);
    const RawHistogramStats s = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    for (int ch = 0; ch < 3; ++ch) {
        CHECK(s.bins[ch][0] == s.samples[ch]);
        CHECK(s.blackClips[ch] == s.samples[ch]);
        CHECK(s.whiteClips[ch] == 0);
    }
}

TEST_CASE("sentinel_levels_fall_back_to_the_container_values") {
    // Frame carried no levels (-1 sentinels): the fallback must be used
    // and reported. Same uniform fixture as the first case, so the
    // result must be IDENTICAL to passing the container values directly.
    const auto cfa = synthetic(CfaPattern::RGGB, 544, 544, 544);
    const RawHistogramStats viaFallback = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, kNoBlack, kBlack64, kNoWhite, 1023);
    const RawHistogramStats direct = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1023, 1023);
    for (int ch = 0; ch < 3; ++ch)
        for (int b = 0; b < RawHistogramStats::kBins; ++b)
            REQUIRE(viaFallback.bins[ch][b] == direct.bins[ch][b]);
    for (int ch = 0; ch < 3; ++ch) {
        CHECK(viaFallback.whiteClips[ch] == direct.whiteClips[ch]);
        CHECK(viaFallback.blackClips[ch] == direct.blackClips[ch]);
    }
    // The levels ACTUALLY USED are the fallbacks (the -1s resolved away).
    for (int slot = 0; slot < 4; ++slot) CHECK(viaFallback.blackUsed[slot] == 64);
    CHECK(viaFallback.whiteUsed == 1023);
}

TEST_CASE("frame_levels_win_over_the_container_fallback") {
    // The frame carried dynamic levels (black 64, white 512): they win,
    // and blackUsed/whiteUsed report them. Uniform 288 = 64 + 0.5*448
    // normalizes to exactly 0.5 -> bin 128 under the FRAME levels; under
    // the container fallback (64/1023) it normalizes to ~0.234 -> bin 59,
    // so the bin proves which levels were actually used.
    const int32_t frameBlack[4] = {64, 64, 64, 64};
    const auto cfa = synthetic(CfaPattern::RGGB, 288, 288, 288);
    const RawHistogramStats s = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, frameBlack, kBlack64, 512, 1023);
    for (int ch = 0; ch < 3; ++ch) {
        CHECK(s.bins[ch][128] == s.samples[ch]);
        for (int b = 0; b < RawHistogramStats::kBins; ++b)
            if (b != 128) CHECK(s.bins[ch][b] == 0);
    }
    for (int slot = 0; slot < 4; ++slot) CHECK(s.blackUsed[slot] == 64);
    CHECK(s.whiteUsed == 512);
}

TEST_CASE("split_green_levels_feed_one_g_histogram") {
    // Slot order is (r, g1, g2, b); the two green slots may carry
    // different black levels (the develop stage keeps each site's own).
    // Uniform 544: g1 (black 64) -> v = 0.5 -> bin 128 (16 sites);
    // g2 (black 160) -> v = (544-160)/864 = 0.4444 -> bin 113 (16 sites).
    // One G histogram, both peaks.
    const int32_t black[4] = {64, 64, 160, 64};
    const auto cfa = synthetic(CfaPattern::RGGB, 544, 544, 544);
    const RawHistogramStats s = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, black, kBlack64, 1024, 1023);
    CHECK(s.bins[1][128] == 16);
    CHECK(s.bins[1][113] == 16);
    CHECK(s.samples[1] == 32);
    CHECK(s.blackUsed[1] == 64);
    CHECK(s.blackUsed[2] == 160);
}

TEST_CASE("every_cfa_pattern_maps_sites_to_the_right_channel") {
    // Distinct per-channel values on exact bin boundaries: R = 0.25 ->
    // bin 64, G = 0.5 -> bin 128, B = 0.75 -> bin 192 (black 64, white
    // 1024). A wrong layout table would swap the channels under any
    // pattern; all four arrangements are checked.
    for (auto p : {CfaPattern::RGGB, CfaPattern::GRBG, CfaPattern::GBRG, CfaPattern::BGGR}) {
        const auto cfa = synthetic(p, 304, 544, 784);
        const RawHistogramStats s = rawHistogramStats(
            cfa.data(), 8, 8, p, kBlack64, kBlack64, 1024, 1023);
        CAPTURE(static_cast<int>(p));
        CHECK(s.bins[0][64] == 16);   // R sites: 16, all in bin 64
        CHECK(s.bins[1][128] == 32);  // G sites: 32 (both greens), bin 128
        CHECK(s.bins[2][192] == 16);  // B sites: 16, all in bin 192
        CHECK(binSum(s, 0) == 16);
        CHECK(binSum(s, 1) == 32);
        CHECK(binSum(s, 2) == 16);
    }
}

TEST_CASE("partial_clipping_counts_only_the_clipped_sites") {
    // 8x8 RGGB: the left half's R sites at white (8 sites), the top
    // half's B sites at black (8 sites), everything else mid-gray.
    std::vector<uint16_t> cfa = synthetic(CfaPattern::RGGB, 544, 544, 544);
    for (uint32_t y = 0; y < 8; ++y)
        for (uint32_t x = 0; x < 8; ++x) {
            const int site = siteAt(CfaPattern::RGGB, x, y);
            if (site == 0 && x < 4) cfa[static_cast<size_t>(y) * 8 + x] = 1024;
            if (site == 2 && y < 4) cfa[static_cast<size_t>(y) * 8 + x] = 64;
        }
    const RawHistogramStats s = rawHistogramStats(
        cfa.data(), 8, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    CHECK(s.whiteClips[0] == 8);  // R: left half at white
    CHECK(s.blackClips[2] == 8);  // B: top half at black
    CHECK(s.whiteClips[1] == 0);
    CHECK(s.blackClips[0] == 0);
    CHECK(s.blackClips[1] == 0);
    CHECK(s.whiteClips[2] == 0);
    // The clipped samples pile into the end bins; the rest stay mid.
    CHECK(s.bins[0][255] == 8);
    CHECK(s.bins[0][128] == 8);
    CHECK(s.bins[2][0] == 8);
    CHECK(s.bins[2][128] == 8);
    CHECK(s.bins[1][128] == 32);
}

TEST_CASE("degenerate_inputs_return_an_empty_result") {
    const RawHistogramStats s = rawHistogramStats(
        nullptr, 8, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    CHECK(s.samples[0] == 0);
    CHECK(s.samples[1] == 0);
    CHECK(s.samples[2] == 0);
    const auto cfa = synthetic(CfaPattern::RGGB, 544, 544, 544);
    const RawHistogramStats s0 = rawHistogramStats(
        cfa.data(), 0, 8, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    CHECK(s0.samples[0] == 0);
    const RawHistogramStats s1 = rawHistogramStats(
        cfa.data(), 8, 0, CfaPattern::RGGB, kBlack64, kBlack64, 1024, 1023);
    CHECK(s1.samples[0] == 0);
}
