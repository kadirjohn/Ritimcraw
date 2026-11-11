// Frame↔IMU synchronization tests (stabilization Phase 3).
//
// Stage A (this commit): the SyncModel clock bridge — offset arithmetic,
// sign convention, ns/ms conversions — and the manual-offset passthrough.
// The anchored estimator, frame mapping and real-fixture tests land with
// the rest of Phase 3 in this same file.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Sync.h>

#include <vector>

using namespace rawedit;

// ---------------------------------------------------------------------------
// SyncModel: arithmetic, sign, conversions
// ---------------------------------------------------------------------------

TEST_CASE("model applies offset with the documented sign") {
    // offsetNs is ADDED to the frame clock to reach the IMU clock.
    SyncModel m;
    m.offsetNs = 123456789;
    CHECK(m.toImu(1000) == 1000 + 123456789);
    CHECK(m.toFrame(1000 + 123456789) == 1000);
    // Zero offset is the identity (and is NOT a claim about real files).
    SyncModel zero;
    CHECK(zero.toImu(258158030289578LL) == 258158030289578LL);
}

TEST_CASE("model ms/ns conversions round-trip") {
    // fromOffsetMs -> offsetMs round-trips at ms resolution.
    for (double ms : {0.0, 1.0, -1.0, 0.5, -0.5, 367.0, -1250.25, 12345.678}) {
        const SyncModel m = SyncModel::fromOffsetMs(ms);
        CHECK(m.offsetMs() == doctest::Approx(ms).epsilon(1e-9));
    }
    // Sub-ns rounding: 4e-7 ms (0.4 ns) rounds to 0 ns.
    CHECK(SyncModel::fromOffsetMs(4e-7).offsetNs == 0);
    // 0.5 ms rounds away from zero (std::round semantics, documented).
    CHECK(SyncModel::fromOffsetMs(0.5).offsetNs == 500000);
    CHECK(SyncModel::fromOffsetMs(-0.5).offsetNs == -500000);
    // toImu/toFrame are exact inverses for any offset.
    const SyncModel m = SyncModel::fromOffsetMs(-371.123);
    const int64_t t = 258158030289578LL;
    CHECK(m.toFrame(m.toImu(t)) == t);
}

TEST_CASE("toImu/toFrame shift a whole timeline uniformly") {
    // A constant offset shifts every frame identically (the bridge is one
    // constant; no rate term — Sync.h).
    const SyncModel m = SyncModel::fromOffsetMs(351.0);
    const std::vector<int64_t> frames = {1000000000LL, 1033558120LL, 1069116240LL};
    for (int64_t f : frames) {
        CHECK(m.toImu(f) - f == m.offsetNs);
        CHECK(m.toImu(f) - m.toImu(frames[0]) == f - frames[0]);
    }
}

// ---------------------------------------------------------------------------
// Manual offset
// ---------------------------------------------------------------------------

TEST_CASE("manual offset carries ManualRequired and no bound") {
    const SyncEstimate est = manualOffsetMs(-130.5);
    CHECK_FALSE(est.isAnchored());
    CHECK(est.confidence == SyncConfidence::ManualRequired);
    CHECK(est.source == "manual");
    CHECK(est.model.offsetNs == -130500000LL);
    CHECK(est.errorBoundMs == 0.0); // 0 = unknown, not exact (Sync.h)
}
