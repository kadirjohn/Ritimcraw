// test_scale_tool — the S11a scale-policy counterfactual tool's honesty
// proofs (docs/S11_ROBUSTNESS_NOTES.md; core/tests/image_stab_scale_policy.hpp
// is the shared pure layer between this suite and image_stab_measure's
// `scale-test` subcommand).
//
// What must hold for the counterfactual tables to be trusted:
//   (a) SANDWICH: a plan built on applyScalePolicy(recs, Free) records is
//       bit-identical to a plan built on recs themselves — the tool's
//       read+rewrite path adds nothing.
//   (b) LOCK SURGICALITY: the lock rewrite changes ONLY logS on MEASURED
//       records (tx/ty/theta and all quality fields bit-identical), and
//       touches Lost records not at all.
//   (c) CLAMP BAND: |logS| <= band with correct signs at both edges, and
//       values inside the band untouched.
//   (d) RECORD ROUND TRIP: toMotion/toRecord is lossless on records the
//       analysis writes (the rewrite rides on quantized fields; any loss
//       would fake a policy effect).
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <vector>

#include <rawedit/ImageStab.h>

#include "image_stab_scale_policy.hpp"

using namespace rawedit;

namespace {

// Records with the S11 walk-clip shape: a systematic +logS bias on measured
// pairs (the drift driver), a couple of Lost pairs, and a lost RUN longer
// than the 2-frame hold (the plan's worst case).
std::vector<PairRecord> biasedRecords(size_t n, double bias) {
    std::vector<PairRecord> recs;
    recs.reserve(n);
    {
        PairMotion id;
        id.status = PairStatus::Ok;
        id.inlierRatio = 0.9f;
        id.coverage = 0.9f;
        recs.push_back(toRecord(0, id));
    }
    for (size_t i = 1; i < n; ++i) {
        PairMotion m;
        m.tx = 0.002 * std::sin(double(i) * 0.11);
        m.ty = 0.001 * std::cos(double(i) * 0.07);
        m.logS = i % 7 == 3 ? 3.0 * bias : bias;   // occasional bigger jump
        m.theta = 0.0004 * std::sin(double(i) * 0.05);
        m.inlierRatio = 0.8f;
        m.coverage = 0.7f;
        m.nTracked = 120;
        m.nInliers = 100;
        m.residualRms = 0.35f;
        if (i == 40 || i == 41 || i == 60 || (i >= 100 && i < 116)) {
            m = PairMotion{};  // Lost pairs (a 16-long run included)
            m.status = PairStatus::Lost;
        } else if (i % 23 == 5) {
            m.status = PairStatus::Weak;   // some Weak pairs
        } else {
            m.status = PairStatus::Ok;
        }
        recs.push_back(toRecord(static_cast<int64_t>(i) * 33333, m));
    }
    return recs;
}

bool samePlan(const ImageStabPlan& a, const ImageStabPlan& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        const ImageStabFrame& x = a.frame(i);
        const ImageStabFrame& y = b.frame(i);
        if (std::memcmp(&x, &y, sizeof(ImageStabFrame)) != 0) return false;
    }
    const ImageStabPlanStats& sa = a.stats();
    const ImageStabPlanStats& sb = b.stats();
    return std::memcmp(&sa, &sb, sizeof(ImageStabPlanStats)) == 0;
}

}  // namespace

TEST_CASE("scale tool: record round trip is lossless (quantization honesty)") {
    const auto recs = biasedRecords(60, 0.0122);
    for (const PairRecord& r : recs) {
        const PairRecord back = toRecord(r.ptsUs, toMotion(r));
        CHECK(back.ptsUs == r.ptsUs);
        CHECK(back.tx == r.tx);
        CHECK(back.ty == r.ty);
        CHECK(back.logS == r.logS);
        CHECK(back.theta == r.theta);
        CHECK(back.inlierRatio8 == r.inlierRatio8);
        CHECK(back.coverage8 == r.coverage8);
        CHECK(back.nInliers == r.nInliers);
        CHECK(back.residualMilliPx == r.residualMilliPx);
        CHECK(back.status == r.status);
    }
}

TEST_CASE("scale tool: free policy is a bit-identical copy and a bit-identical plan (sandwich)") {
    const auto recs = biasedRecords(80, 0.0122);
    const auto freed = applyScalePolicy(recs, ScalePolicy::Free);
    REQUIRE(freed.size() == recs.size());
    for (size_t i = 0; i < recs.size(); ++i) {
        CHECK(std::memcmp(&freed[i], &recs[i], sizeof(PairRecord)) == 0);
    }
    for (int mode = 0; mode < 2; ++mode) {
        ImageStabParams p;
        p.mode = mode;
        p.strength = 0.5;
        const ImageStabPlan a = ImageStabPlan::build(recs, p, 0, recs.size() - 1, 16.0 / 9.0);
        const ImageStabPlan b = ImageStabPlan::build(freed, p, 0, recs.size() - 1, 16.0 / 9.0);
        CHECK(samePlan(a, b));
    }
}

TEST_CASE("scale tool: lock rewrites only logS on measured pairs, never touches lost") {
    const auto recs = biasedRecords(920, 0.0122);   // walk-clip scale: sum ~ 11 -> exp ~ 74k
    const auto locked = applyScalePolicy(recs, ScalePolicy::Lock);
    REQUIRE(locked.size() == recs.size());
    for (size_t i = 0; i < recs.size(); ++i) {
        const PairRecord& src = recs[i];
        const PairRecord& dst = locked[i];
        if (src.status == static_cast<uint8_t>(PairStatus::Lost)) {
            // Lost: every byte identical.
            CHECK(std::memcmp(&src, &dst, sizeof(PairRecord)) == 0);
        } else {
            CHECK(dst.logS == 0.0f);
            CHECK(dst.ptsUs == src.ptsUs);
            CHECK(dst.tx == src.tx);
            CHECK(dst.ty == src.ty);
            CHECK(dst.theta == src.theta);
            CHECK(dst.inlierRatio8 == src.inlierRatio8);
            CHECK(dst.coverage8 == src.coverage8);
            CHECK(dst.nInliers == src.nInliers);
            CHECK(dst.residualMilliPx == src.residualMilliPx);
            CHECK(dst.status == src.status);
        }
    }
    // The drift driver is actually removed: the accumulated factor drops
    // to exactly 1 (the S11 notes' lock line) from the walk-like magnitude.
    CHECK(accumulatedScale(locked) == doctest::Approx(1.0).epsilon(1e-12));
    CHECK(accumulatedScale(recs) > 1000.0);
}

TEST_CASE("scale tool: clamp band enforces |logS| <= band with signs, keeps small values") {
    // Build records whose |logS| crosses the band on both sides.
    auto recs = biasedRecords(50, 0.0);   // zero bias...
    PairMotion m;
    m.status = PairStatus::Ok;
    m.inlierRatio = 0.9f;
    m.coverage = 0.9f;
    m.logS = 0.050;   // far above
    recs[1] = toRecord(recs[1].ptsUs, m);
    m.logS = -0.030;  // below, negative side
    recs[2] = toRecord(recs[2].ptsUs, m);
    m.logS = 0.0015;  // inside the band, must be untouched
    recs[3] = toRecord(recs[3].ptsUs, m);
    const auto clamped = applyScalePolicy(recs, ScalePolicy::Clamp, 0.002);
    CHECK(clamped[1].logS == doctest::Approx(0.002f).epsilon(1e-6));
    CHECK(clamped[2].logS == doctest::Approx(-0.002f).epsilon(1e-6));
    CHECK(clamped[3].logS == recs[3].logS);
    // Non-measured (Lost) records are out of policy by definition.
    // All quality/translation/rotation fields are preserved.
    for (size_t i = 0; i < recs.size(); ++i) {
        CHECK(clamped[i].tx == recs[i].tx);
        CHECK(clamped[i].ty == recs[i].ty);
        CHECK(clamped[i].theta == recs[i].theta);
        CHECK(clamped[i].status == recs[i].status);
        CHECK(clamped[i].inlierRatio8 == recs[i].inlierRatio8);
    }
}

TEST_CASE("scale tool: lock counterfactual actually frees the plan budget on biased records") {
    // The point of the tool: on drift-shaped records the free policy cannot
    // apply the correction (budget dead) while lock can. This is the
    // miniature of the S11 notes' walk-clip numbers (922 pairs, exp ~ 74k).
    const auto recs = biasedRecords(920, 0.0122);
    ImageStabParams p;
    p.strength = 0.5;
    p.zoomLimit = 1.15;
    const ImageStabPlan free = ImageStabPlan::build(recs, p, 0, recs.size() - 1, 16.0 / 9.0);
    const ImageStabPlan lock = ImageStabPlan::build(applyScalePolicy(recs, ScalePolicy::Lock), p,
                                                     0, recs.size() - 1, 16.0 / 9.0);
    CHECK(accumulatedScale(recs) > 1000.0);
    CHECK(free.stats().meanA <= 0.5);        // budget mostly dead under drift
    CHECK(lock.stats().meanA > free.stats().meanA);  // lock frees real budget
    CHECK(lock.stats().limitedFraction <= free.stats().limitedFraction);
    CHECK(lock.stats().invalidSampleFrames == 0);
    CHECK(free.stats().invalidSampleFrames == 0);     // the guarantee holds either way
}
