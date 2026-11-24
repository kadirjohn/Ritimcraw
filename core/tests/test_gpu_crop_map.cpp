// GpuCropMap plan tests (2026-11-23 owner request, guide A2: an active
// crop/straighten must ride the GPU window pass instead of forcing the
// whole preview to the CPU). The frame plan (cropped output dims, the
// auto-zoom, the cos/sin pair) is pure, so the invariants run on desktop
// the way test_stats_ring runs the ring planner's; GlesPreviewSession.cpp
// only uploads the planned values.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <cmath>

#include "../../app/src/main/cpp/gles/GpuCropMap.h"

using namespace gles;

namespace {
// The JVM-side neutral parcel (GpuCropMap.NEUTRAL_CROP_PARAMS): rect
// (0,0,1,1) + 0 deg.
const float kNeutral[5] = {0.0f, 0.0f, 1.0f, 1.0f, 0.0f};
// An active rect + 7 deg (the plan's own anchor case shape).
const float kCentered7[5] = {0.25f, 0.25f, 0.5f, 0.5f, 7.0f};
}  // namespace

TEST_CASE("cropRoundDim rounds half away, matching Kotlin roundToInt") {
    CHECK(cropRoundDim(384.0f) == 384);
    CHECK(cropRoundDim(384.4f) == 384);
    CHECK(cropRoundDim(384.5f) == 385);  // half AWAY, not half-even
    CHECK(cropRoundDim(384.6f) == 385);
    CHECK(cropRoundDim(0.4f) == 0);
    CHECK(cropRoundDim(0.5f) == 1);
}

TEST_CASE("planCrop: outW/outH mirror outputDims - 0.3 x 0.5 of 1280x720 -> 384x360") {
    const float p[5] = {0.1f, 0.2f, 0.3f, 0.5f, 0.0f};
    const CropFramePlan plan = planCrop(1280, 720, true, p);
    CHECK(plan.active);
    CHECK(plan.outW == 384);  // 0.3 * 1280 = 384
    CHECK(plan.outH == 360);  // 0.5 * 720 = 360
    CHECK(plan.zoom == 1.0f);  // deg 0
    CHECK(plan.cos == 1.0f);
    CHECK(plan.sin == 0.0f);
}

TEST_CASE("planCrop: degenerate rects clamp each output dim to min 1") {
    // A tiny rect on a huge frame still plans 1x1 at minimum; a 5%-floor
    // rect on a small upright frame rounds to >= 1 per axis.
    const float tiny[5] = {0.5f, 0.5f, 0.0001f, 0.0001f, 0.0f};
    const CropFramePlan plan = planCrop(1280, 720, true, tiny);
    CHECK(plan.active);
    CHECK(plan.outW == 1);
    CHECK(plan.outH == 1);
}

TEST_CASE("planCrop: inactive request plans the identity (full dims)") {
    const CropFramePlan plan = planCrop(1280, 720, false, kCentered7);
    CHECK_FALSE(plan.active);
    CHECK(plan.outW == 1280);
    CHECK(plan.outH == 720);
    CHECK(plan.zoom == 1.0f);
    CHECK(plan.cos == 1.0f);
    CHECK(plan.sin == 0.0f);
    // The rect fields normalize to the full rect — the shader gate never
    // reads them, and a healthy probe line would still carry the plan's
    // own aspect values (uCropOn = 0).
    CHECK(plan.rectX == 0.0f);
    CHECK(plan.rectY == 0.0f);
    CHECK(plan.rectW == 1.0f);
    CHECK(plan.rectH == 1.0f);
}

TEST_CASE("planCrop: the neutral set (0 deg, full rect) plans the identity even when cropActive is set") {
    // A stale cropActive=true with a NEUTRAL grade must not pay the crop
    // path — the exact PublishCrop.plan inline test, folded natively.
    const CropFramePlan plan = planCrop(1280, 720, true, kNeutral);
    CHECK_FALSE(plan.active);
    CHECK(plan.outW == 1280);
    CHECK(plan.outH == 720);
    CHECK(plan.zoom == 1.0f);
}

TEST_CASE("planCrop: deg clamps to the STRAIGHTEN_MAX_DEG ceiling") {
    const float over[5] = {0.25f, 0.25f, 0.5f, 0.5f, 25.0f};
    const float atMax[5] = {0.25f, 0.25f, 0.5f, 0.5f, 15.0f};
    const CropFramePlan clamped = planCrop(1280, 720, true, over);
    const CropFramePlan capped = planCrop(1280, 720, true, atMax);
    CHECK(clamped.active);
    // 25 deg clamps to 15: the zoom and the trig equal the at-max plan.
    CHECK(clamped.zoom == doctest::Approx(capped.zoom));
    CHECK(clamped.cos == doctest::Approx(capped.cos));
    CHECK(clamped.sin == doctest::Approx(capped.sin));
    // The negative side mirrors.
    const float under[5] = {0.25f, 0.25f, 0.5f, 0.5f, -25.0f};
    const float atMin[5] = {0.25f, 0.25f, 0.5f, 0.5f, -15.0f};
    const CropFramePlan clampedNeg = planCrop(1280, 720, true, under);
    const CropFramePlan cappedNeg = planCrop(1280, 720, true, atMin);
    CHECK(clampedNeg.zoom == doctest::Approx(cappedNeg.zoom));
    CHECK(clampedNeg.cos == doctest::Approx(cappedNeg.cos));
    CHECK(clampedNeg.sin == doctest::Approx(cappedNeg.sin));
}

TEST_CASE("zoom formula: deg 0 or a non-positive rect needs zoom 1") {
    CHECK(cropStraightenZoom(1280, 720, 0.25f, 0.25f, 0.5f, 0.5f, 0.0f) == 1.0f);
    CHECK(cropStraightenZoom(1280, 720, 0.5f, 0.5f, 0.0f, 0.0f, 7.0f) == 1.0f);
    CHECK(cropStraightenZoom(0, 0, 0.25f, 0.25f, 0.5f, 0.5f, 7.0f) == 1.0f);
}

TEST_CASE("zoom formula: the full-rect documented value - 1920x1080 at 15 deg -> ~1.4260") {
    // CropState.straightenZoom's OWN KDoc number (verified JVM-side with
    // hand-derived math: the inscribed-rect scale-up max(cos + (W/H) sin,
    // cos + (H/W) sin)): the C++ twin must produce the same value, so
    // the native planCrop and the Kotlin object provably implement the
    // same formula Kotlin-side (the plan's pin).
    const float z = cropStraightenZoom(1920, 1080, 0.0f, 0.0f, 1.0f, 1.0f, 15.0f);
    CHECK(z == doctest::Approx(1.4260f).epsilon(0.001));
}

TEST_CASE("zoom formula: a square at 45 deg is sqrt(2) when unclamped by the range") {
    // 45 deg exceeds STRAIGHTEN_MAX_DEG, so the CLAMPED angle is 15 and
    // the zoom is the 15-deg value — the clamp runs FIRST in the
    // formula (deg is clamped before the trig), the CropState order.
    const float z45 = cropStraightenZoom(100, 100, 0.0f, 0.0f, 1.0f, 1.0f, 45.0f);
    const float z15 = cropStraightenZoom(100, 100, 0.0f, 0.0f, 1.0f, 1.0f, 15.0f);
    CHECK(z45 == doctest::Approx(z15));
    // The 15-deg square zoom: cos15 + sin15 (W/H == H/W == 1) ~ 1.2247.
    CHECK(z15 == doctest::Approx(1.2247f).epsilon(0.001));
}

TEST_CASE("zoom formula: the [1, 8] ceiling on an edge-hugging rect") {
    // A rect hugging the BOTTOM edge needs an unbounded zoom for any
    // nonzero angle (its rotated corner's ty / (h - cy) bound blows up
    // as h - cy -> 0); the STRAIGHTEN_ZOOM_MAX ceiling (8) clamps it —
    // CropState's own documented behavior (the sampler's clamp-to-edge
    // fills the sliver). Rect (0.4, 0.99, 0.2, 0.01) on 1280x720 at 15
    // deg: ay*s = 128*0.2588 ~ 33 over h-cy = 7.2 -> raw bound ~ 10.2.
    const float hug[5] = {0.4f, 0.99f, 0.2f, 0.01f, 15.0f};
    const CropFramePlan plan = planCrop(1280, 720, true, hug);
    CHECK(plan.active);
    CHECK(plan.zoom >= 1.0f);
    CHECK(plan.zoom <= 8.0f);
    // The zoom ceiling fires: the raw linear bound (~10.2) exceeds 8.
    CHECK(plan.zoom == 8.0f);
}

TEST_CASE("zoom formula: a small centered rect needs no zoom") {
    // CropStateTest's own case, natively: the ax/ay bound keeps any
    // corner sample inside the frame for a small rect.
    const float z = cropStraightenZoom(1280, 720, 0.475f, 0.475f, 0.05f, 0.05f, 7.0f);
    CHECK(z == 1.0f);
}

TEST_CASE("cos/sin signs: positive deg is cos + / sin +, negative deg cos + / sin -") {
    const float pos[5] = {0.25f, 0.25f, 0.5f, 0.5f, 7.0f};
    const float neg[5] = {0.25f, 0.25f, 0.5f, 0.5f, -7.0f};
    const CropFramePlan p = planCrop(1280, 720, true, pos);
    const CropFramePlan n = planCrop(1280, 720, true, neg);
    CHECK(p.cos > 0.99f);
    CHECK(p.sin > 0.0f);   // +7 deg samples from the left-below: sin +
    CHECK(n.cos > 0.99f);
    CHECK(n.sin < 0.0f);   // -7 deg mirrors: sin -
    // Equal magnitudes (the double-theta trig is odd in deg).
    CHECK(p.sin == doctest::Approx(-n.sin));
    CHECK(p.cos == doctest::Approx(n.cos));
    // The exact 7-deg values (double theta -> float, the Kotlin
    // precision): cos(7deg) = 0.992546152f, sin(7deg) = 0.121869343f.
    CHECK(p.cos == doctest::Approx(0.992546152f).epsilon(1e-6));
    CHECK(p.sin == doctest::Approx(0.121869343f).epsilon(1e-6));;
}

TEST_CASE("planCrop: the 90/270 upright swap feeds the same rect fractions") {
    // The rect is ALREADY in upright fractions (Kotlin mapRect'ed it;
    // the session passes the UPRIGHT dims), so a quarter-turned clip
    // plans exactly the same crop: upright 720x1280 with rect (0.25,
    // 0.25, 0.5, 0.5) cuts the same geometric region as upright
    // 1280x720 with the swapped dims would under the fold — the SHIFT is
    // NONE, the mapping lives JVM-side (single-sourced), the header only
    // consumes the upright geometry.
    const CropFramePlan landscape =
        planCrop(1280, 720, true, kCentered7);
    const CropFramePlan portrait =
        planCrop(720, 1280, true, kCentered7);
    CHECK(landscape.outW == 640);   // 0.5 * 1280
    CHECK(landscape.outH == 360);   // 0.5 * 720
    CHECK(portrait.outW == 360);    // 0.5 * 720
    CHECK(portrait.outH == 640);    // 0.5 * 1280
}

TEST_CASE("planCrop: degenerate zero upright dims plan inactive/zero-out") {
    const CropFramePlan plan = planCrop(0, 0, true, kCentered7);
    CHECK_FALSE(plan.active);
    CHECK(plan.outW == 0);
    CHECK(plan.outH == 0);
}

TEST_CASE("cropNeutral: the neutral set is deg 0 AND the full rect") {
    CHECK(cropNeutral(kNeutral));
    CHECK_FALSE(cropNeutral(kCentered7));
    // Straighten alone (full rect) is NOT neutral.
    const float straightenOnly[5] = {0.0f, 0.0f, 1.0f, 1.0f, 0.5f};
    CHECK_FALSE(cropNeutral(straightenOnly));
    // Rect alone is NOT neutral.
    const float rectOnly[5] = {0.0f, 0.0f, 0.5f, 1.0f, 0.0f};
    CHECK_FALSE(cropNeutral(rectOnly));
}

// ---- 0 degrees: the rect snaps to the CPU twin's whole-pixel window (2026-11-25) ----

TEST_CASE("planCrop 0deg: a fractional rect snaps to CropState.pixelRect's whole-pixel window") {
    // 256x144 preview, the device sweep's mismatching rect 0.4,0.35,0.45,0.4:
    // x = 102.4 -> 102, y = 50.4 -> 50, w = 115.2 -> 115, h = 57.6 -> 58.
    const float p[5] = {0.4f, 0.35f, 0.45f, 0.4f, 0.0f};
    const CropFramePlan plan = planCrop(256, 144, true, p);
    CHECK(plan.active);
    CHECK(plan.outW == 115);
    CHECK(plan.outH == 58);
    CHECK(plan.rectX * 256.0f == doctest::Approx(102.0f).epsilon(1e-5));
    CHECK(plan.rectY * 144.0f == doctest::Approx(50.0f).epsilon(1e-5));
    CHECK(plan.rectW * 256.0f == doctest::Approx(115.0f).epsilon(1e-5));
    CHECK(plan.rectH * 144.0f == doctest::Approx(58.0f).epsilon(1e-5));
}

TEST_CASE("planCrop 0deg: a whole-pixel rect is untouched") {
    const float p[5] = {104.0f / 256.0f, 45.0f / 144.0f, 120.0f / 256.0f, 64.0f / 144.0f, 0.0f};
    const CropFramePlan plan = planCrop(256, 144, true, p);
    CHECK(plan.rectX * 256.0f == doctest::Approx(104.0f).epsilon(1e-5));
    CHECK(plan.rectY * 144.0f == doctest::Approx(45.0f).epsilon(1e-5));
    CHECK(plan.outW == 120);
    CHECK(plan.outH == 64);
}

TEST_CASE("planCrop 0deg: the window is shifted to fit like pixelRect (x0 = min(x, W - outW))") {
    // x = 0.9 * 256 = 230.4 -> 230, w = 0.3 * 256 = 76.8 -> 77: 230 + 77 > 256, so x -> 179.
    const float p[5] = {0.9f, 0.1f, 0.3f, 0.5f, 0.0f};
    const CropFramePlan plan = planCrop(256, 144, true, p);
    CHECK(plan.outW == 77);
    CHECK(plan.rectX * 256.0f == doctest::Approx(179.0f).epsilon(1e-5));
}

TEST_CASE("planCrop: a rotated crop keeps the continuous rect (only 0 deg snaps)") {
    const float p[5] = {0.4f, 0.35f, 0.45f, 0.4f, 7.0f};
    const CropFramePlan plan = planCrop(256, 144, true, p);
    CHECK(plan.rectX == 0.4f);
    CHECK(plan.rectY == 0.35f);
    CHECK(plan.rectW == 0.45f);
    CHECK(plan.rectH == 0.4f);
}
