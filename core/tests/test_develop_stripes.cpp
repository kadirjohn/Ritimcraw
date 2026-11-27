// test_develop_stripes — A7 (night 2026-11-26): the striped encoded-develop
// contract, desktop doctest. developEncoded develops exactly the rows its
// YuvFrame holds; the export CPU fallback's 8K path hands it stripe views
// with kEncodedStripeMargin rows of vertical context. These proofs lock:
//  - the stripe plan: even boundaries (chroma phase), exact coverage;
//  - bit-exactness: stripes == whole frame, EVERY row, with the full
//    windowed stage set (chroma denoise +-4, luma denoise +-1,
//    sharpness/detail +-2) + tonemap + a .cube LUT + the v2 node chain;
//  - the margin is load-bearing: a no-margin stripe view DIFFERS at its
//    edge rows (window taps clamp differently), so the margin cannot be
//    silently dropped;
//  - 10-bit P010 stripes; odd heights (the last stripe's tail row count);
//  - an 8K striped completion run behind RAWEDIT_BIGSTRIPE=1 (minutes —
//    the 8K equality + peak-RSS numbers live in stripe_measure's hand-run
//    record; see .planning/night-2026-11-26 and the step JSON).
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include "encoded_stripe_frame.hpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>

using namespace rawedit;

namespace {
// Byte-exact float compare: same values in the same order (the pool may
// schedule tonemap rows on any thread; the contract is per-row identical
// ops, which is order-independent per element).
bool sameImage(const ImageF& a, const ImageF& b) {
    return a.w == b.w && a.h == b.h && a.rgb.size() == b.rgb.size() &&
        std::memcmp(a.rgb.data(), b.rgb.data(), a.rgb.size() * sizeof(float)) == 0;
}
} // namespace

TEST_CASE("stripe_plan_partitions_evenly_and_completely") {
    const auto p46 = planEncodedStripes(46, 8);
    REQUIRE(p46.size() == 6);
    uint32_t prev = 0;
    for (const auto& r : p46) {
        CHECK(r.first == prev);
        CHECK(r.first % 2 == 0); // the chroma phase
        CHECK(r.second > r.first);
        CHECK(r.second - r.first <= 8);
        prev = r.second;
    }
    CHECK(prev == 46);
    CHECK(planEncodedStripes(0, 8).empty());
    // degenerate asks clamp to 2 (even floor)
    const auto tiny = planEncodedStripes(5, 0);
    REQUIRE(tiny.size() == 3);
    CHECK(tiny[0].first == 0);
    CHECK(tiny[0].second == 2);
    CHECK(tiny[1] == std::make_pair(2u, 4u));
    CHECK(tiny[2].second == 5);
    // one wide ask = one stripe
    const auto one = planEncodedStripes(46, 512);
    REQUIRE(one.size() == 1);
    CHECK(one[0] == std::make_pair(0u, 46u));
    CHECK(kEncodedStripeMargin == 10); // the stacked stage chain's reach, even — see Transfer.h
}

TEST_CASE("stripes_match_whole_frame_bit_exact") {
    // 960x540: the adversarial width of the A7 margin sweep (64-wide
    // fixtures passed on content luck at half the margin; this one exposed
    // the true stacked reach — see Transfer.h). Every stripe size from
    // smaller than the margin to the whole frame, full compare.
    const uint32_t W = 960, H = 540;
    const YuvFrame f = stripefix::makeFrame(W, H);
    const Grade g = stripefix::stageHeavyGrade();
    const ImageF whole = developEncoded(f, g, true);
    for (uint32_t rows : {2u, 4u, 6u, 8u, 16u, 64u, 128u}) {
        const ImageF striped = stripefix::developStriped(f, g, true, nullptr, nullptr, rows);
        CAPTURE(rows);
        CHECK(sameImage(whole, striped));
    }
    // and the small-fixture sweep shape (odd H, tiny stripes) at other seeds
    for (uint32_t seed : {1u, 3u, 5u}) {
        const YuvFrame f2 = stripefix::makeFrame(64, 46, seed);
        const ImageF w2 = developEncoded(f2, g, true);
        for (uint32_t rows : {2u, 6u, 16u}) {
            CAPTURE(seed); CAPTURE(rows);
            CHECK(sameImage(w2, stripefix::developStriped(f2, g, true, nullptr, nullptr, rows)));
        }
    }
}

TEST_CASE("odd_height_and_edge_rows_are_exact") {
    const uint32_t W = 48, H = 47; // odd height: the last stripe is short
    const YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/3);
    const Grade g = stripefix::stageHeavyGrade();
    const ImageF whole = developEncoded(f, g, true);
    const ImageF striped = stripefix::developStriped(f, g, true, nullptr, nullptr, 8);
    CHECK(sameImage(whole, striped));
    // spot-check the first and last rows (the whole-frame clamp edges)
    for (uint32_t x = 0; x < W; ++x)
        for (int c = 0; c < 3; ++c) {
            CHECK(striped.rgb[((size_t)0 * W + x) * 3 + c] == whole.rgb[((size_t)0 * W + x) * 3 + c]);
            CHECK(striped.rgb[((size_t)(H - 1) * W + x) * 3 + c] ==
                  whole.rgb[((size_t)(H - 1) * W + x) * 3 + c]);
        }
}

TEST_CASE("margin_is_load_bearing") {
    const uint32_t W = 64, H = 46;
    const YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/5);
    const Grade g = stripefix::stageHeavyGrade();
    const ImageF whole = developEncoded(f, g, true);
    // (a) NO margin (view = exactly the emitted rows): the windowed stages'
    // taps clamp at the view edge and the emitted rows differ — the margin
    // is real work, not decoration.
    YuvFrame bare = stripefix::sliceRows(f, 8, 16);
    ImageF imgBare = developEncoded(bare, g, true);
    size_t diffs = 0;
    for (uint32_t r = 0; r < 8; ++r)
        for (uint32_t x = 0; x < W; ++x)
            for (int c = 0; c < 3; ++c)
                if (imgBare.rgb[((size_t)r * W + x) * 3 + c] !=
                    whole.rgb[((size_t)(8 + r) * W + x) * 3 + c])
                    ++diffs;
    CHECK(diffs > 0);
    // (b) an ODD view top breaks the chroma phase: the same content sliced
    // from row 9 reads chroma one row out of phase and differs (the sweep's
    // every-odd-margin-fails finding, pinned).
    YuvFrame odd = stripefix::sliceRows(f, 9, 21);
    ImageF imgOdd = developEncoded(odd, g, true);
    size_t oddDiffs = 0;
    for (uint32_t r = 0; r < 12; ++r)
        for (uint32_t x = 0; x < W; ++x)
            for (int c = 0; c < 3; ++c)
                if (imgOdd.rgb[((size_t)r * W + x) * 3 + c] !=
                    whole.rgb[((size_t)(9 + r) * W + x) * 3 + c])
                    ++oddDiffs;
    CHECK(oddDiffs > 0);
    // (c) the positive control: the full-margin stripe of the same rows is
    // exact (the equality cases prove it frame-wide; this pins the slice).
    // Rows [8,16) sit far enough from the frame edge (46 rows) for the
    // margin top 8-10 to clamp at 0 — same as the real stripe loop's rule.
    const uint32_t top = 8u > kEncodedStripeMargin ? 8u - kEncodedStripeMargin : 0u;
    YuvFrame m = stripefix::sliceRows(f, top, 16 + kEncodedStripeMargin);
    ImageF imgM = developEncoded(m, g, true);
    size_t mDiffs = 0;
    const uint32_t off = 8u - top;
    for (uint32_t r = 0; r < 8; ++r)
        for (uint32_t x = 0; x < W; ++x)
            for (int c = 0; c < 3; ++c)
                if (imgM.rgb[((size_t)(off + r) * W + x) * 3 + c] !=
                    whole.rgb[((size_t)(8 + r) * W + x) * 3 + c])
                    ++mDiffs;
    CHECK(mDiffs == 0);
}

TEST_CASE("stripes_with_lut_and_aces_match_whole") {
    const uint32_t W = 48, H = 34;
    const YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/9);
    Grade g = stripefix::stageHeavyGrade();
    g.tonemap = 1; // ACES
    g.lutStrength = 0.6f;
    // a small non-trivial 2x2x2 cube (synthetic; test_transfer/test_color style)
    const char* cube =
        "LUT_3D_SIZE 2\n"
        "0.0 0.0 0.0\n"
        "0.1 0.2 0.3\n"
        "0.2 0.1 0.4\n"
        "0.3 0.4 0.1\n"
        "0.4 0.3 0.2\n"
        "0.5 0.6 0.7\n"
        "0.6 0.5 0.8\n"
        "0.9 0.8 0.7\n";
    const auto parsed = parseCubeText(cube);
    REQUIRE(parsed.isOk());
    const Lut lut = std::move(*parsed.value);
    const ImageF whole = developEncoded(f, g, true, &lut);
    const ImageF striped = stripefix::developStriped(f, g, true, &lut, nullptr, 6);
    CHECK(sameImage(whole, striped));
}

TEST_CASE("stripes_with_glitter_suppression_match_whole") {
    // Glitter suppression (stage 8b, +-1 row after luma denoise) stacks on
    // the chain: chroma 4 + luma 1 + glitter 1 + sharp 2 + detail 2 = 10,
    // exactly the margin. Locked here at the stage-heavy grade.
    const YuvFrame f = stripefix::makeFrame(96, 70, /*seed=*/4);
    Grade g = stripefix::stageHeavyGrade();
    g.glitterSuppress = true;
    const ImageF whole = developEncoded(f, g, true);
    for (uint32_t rows : {2u, 4u, 8u, 16u}) {
        const ImageF striped = stripefix::developStriped(f, g, true, nullptr, nullptr, rows);
        CAPTURE(rows);
        CHECK(sameImage(whole, striped));
    }
}

TEST_CASE("node_chain_composes_per_stripe") {
    const uint32_t W = 56, H = 30;
    const YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/11);
    const Grade g = stripefix::stageHeavyGrade();
    GradeNode node; // a non-neutral node: sat + contrast (per-pixel stages)
    node.sat = 1.35f;
    node.contrast = 1.15f;
    GradeNode chain[1] = {node};
    ImageF whole = developEncoded(f, g, true);
    REQUIRE(applyNodes(whole, chain, 1, NodesOutput::Rec709Linear));
    const ImageF striped = stripefix::developStriped(f, g, true, nullptr, nullptr, 6, chain, 1);
    CHECK(sameImage(whole, striped));
}

TEST_CASE("ten_bit_p010_stripes_match_whole") {
    const uint32_t W = 40, H = 30;
    const YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/7, /*bitDepth=*/10);
    const Grade g = stripefix::stageHeavyGrade();
    const ImageF whole = developEncoded(f, g, true);
    for (uint32_t rows : {2u, 6u, 30u}) {
        CAPTURE(rows);
        const ImageF striped = stripefix::developStriped(f, g, true, nullptr, nullptr, rows);
        CHECK(sameImage(whole, striped));
    }
}

TEST_CASE("planar_i420_stripes_match_whole") {
    // The striped oracle also serves planar I420 layouts (the JNI entry
    // takes semiPlanar=false): the same proof on a planar convert of the
    // same content.
    const uint32_t W = 36, H = 26;
    YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/13);
    const uint32_t cw = (W + 1) / 2, ch = (H + 1) / 2;
    YuvFrame p;
    p = f;
    p.semiPlanar = false;
    p.uvStride = (int)(cw);
    p.u.clear();
    p.v.clear();
    for (uint32_t r = 0; r < ch; ++r)
        for (uint32_t c = 0; c < cw; ++c) {
            p.u.push_back(f.u[(size_t)r * f.uvStride + (size_t)c * 2]);
            p.v.push_back(f.u[(size_t)r * f.uvStride + (size_t)c * 2 + 1]);
        }
    const Grade g = stripefix::stageHeavyGrade();
    const ImageF whole = developEncoded(p, g, true);
    const ImageF striped = stripefix::developStriped(p, g, true, nullptr, nullptr, 4);
    CHECK(sameImage(whole, striped));
}

TEST_CASE("mid_size_striped_matches_whole") {
    // 960x540: big enough for real stripe iteration (~2 rows of margin
    // work across many stripes), small enough for the always-on suite.
    const uint32_t W = 960, H = 540;
    const YuvFrame f = stripefix::makeFrame(W, H, /*seed=*/21);
    const Grade g = stripefix::stageHeavyGrade();
    const ImageF whole = developEncoded(f, g, true);
    const ImageF striped = stripefix::developStriped(f, g, true, nullptr, nullptr, 128);
    CHECK(sameImage(whole, striped));
}

TEST_CASE("eight_k_striped_completes") {
    // The 8K completion run (RAWEDIT_BIGSTRIPE=1, minutes): proves the
    // striped path completes a full 7680x4320 develop with the same
    // content variation; the whole-frame twin at 8K and the peak-RSS
    // numbers live in stripe_measure's hand-run record.
    if (std::getenv("RAWEDIT_BIGSTRIPE") == nullptr) {
        printf("SKIP eight_k_striped_completes (set RAWEDIT_BIGSTRIPE=1; the "
               "default-size equality is locked above)\n");
        return;
    }
    const uint32_t W = 7680, H = 4320;
    const YuvFrame f = stripefix::makeFrame(W, H);
    const Grade g = stripefix::stageHeavyGrade();
    const std::clock_t t0 = std::clock();
    const ImageF out = stripefix::developStriped(f, g, true, nullptr, nullptr, 82);
    const double secs = double(std::clock() - t0) / CLOCKS_PER_SEC;
    REQUIRE(out.w == W);
    REQUIRE(out.h == H);
    REQUIRE(out.rgb.size() == (size_t)W * H * 3);
    // the vertical variation survived the stages (row 0 vs the last row differ)
    size_t diffs = 0;
    for (uint32_t x = 0; x < W; x += 97)
        for (int c = 0; c < 3; ++c)
            if (out.rgb[((size_t)0 * W + x) * 3 + c] != out.rgb[((size_t)(H - 1) * W + x) * 3 + c])
                ++diffs;
    CHECK(diffs > 0);
    printf("8K striped develop: %.1f s\n", secs);
}
