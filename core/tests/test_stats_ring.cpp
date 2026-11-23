// StatsRing planner tests (2026-11-23 owner request, guide A1: draw 3's
// same-frame glMapBufferRange stalled the CPU for the whole GPU frame —
// 78.5 ms median with scopes ON vs ~18 ms OFF). The 2-SSBO + 2-fence
// ring's decisions are pure, so the invariants run on desktop the way
// test_chain_slots runs the chain slot planner's; GlesPreviewSession.cpp
// only executes the plans.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include "../../app/src/main/cpp/gles/StatsRing.h"

using namespace gles;

namespace {
// The async ladder the session runs: plan -> commit, repeat. Returns the
// plans so the tests can assert over the sequence.
std::vector<AsyncFramePlan> runAsyncFrames(StatsRingState& s, int n) {
    std::vector<AsyncFramePlan> plans;
    for (int i = 0; i < n; ++i) {
        const AsyncFramePlan p = planAsyncFrame(s);
        commitAsyncDraw(s, p);
        plans.push_back(p);
    }
    return plans;
}
}

TEST_CASE("slots alternate: consecutive async frames draw into 0,1,0,1") {
    StatsRingState s;
    const auto plans = runAsyncFrames(s, 4);
    REQUIRE(plans.size() == 4);
    CHECK(plans[0].drawSlot == 0);
    CHECK(plans[1].drawSlot == 1);
    CHECK(plans[2].drawSlot == 0);
    CHECK(plans[3].drawSlot == 1);
}

TEST_CASE("cold ring: the first async frame has nothing to publish") {
    StatsRingState s;
    const AsyncFramePlan p = planAsyncFrame(s);
    CHECK(p.frame == 0);
    CHECK(p.publishSlot == -1);
    CHECK_FALSE(p.dropStaleFence);
}

TEST_CASE("after frame N commits, frame N+1 publishes exactly slot N&1") {
    StatsRingState s;
    runAsyncFrames(s, 3);
    // Frames 0,1,2 committed. Frame 3's plan must offer frame 2's pack
    // (slot 2&1 = 0) — exactly the 1-frame-old slot, NEVER the slot frame
    // 3 is about to draw into (also 0? no: 3&1 = 1; the publish slot is
    // the OTHER one by construction).
    const AsyncFramePlan p = planAsyncFrame(s);
    CHECK(p.frame == 3);
    CHECK(p.publishSlot == (2 & 1));
    CHECK(p.drawSlot == (3 & 1));
    CHECK(p.publishSlot != p.drawSlot);
    // The pending pack in the publish slot belongs to frame 2.
    CHECK(s.slot[p.publishSlot].pending);
    CHECK(s.slot[p.publishSlot].frame == 2);
}

TEST_CASE("dropStaleFence: an unpublished frame N-2 pack flags its fence for deletion") {
    StatsRingState s;
    // Frames 0 and 1 commit, neither publishes (the caller never called
    // clearSlot — the fences never signaled).
    runAsyncFrames(s, 2);
    // Frame 2's plan: it draws into slot 0, which still holds frame 0's
    // never-published pack — the plan must flag it so the caller deletes
    // that fence BEFORE creating the new one.
    const AsyncFramePlan p = planAsyncFrame(s);
    CHECK(p.drawSlot == 0);
    CHECK(p.dropStaleFence);
    CHECK(s.slot[0].frame == 0);
    // After the overwriting commit, the old pending state is GONE: slot 0
    // now belongs to frame 2.
    commitAsyncDraw(s, p);
    CHECK(s.slot[0].pending);
    CHECK(s.slot[0].frame == 2);
    // And frame 3's plan no longer flags a stale fence on either slot's
    // reuse path for slot 1 (frame 1's pack is 2 frames old too — slot 1
    // is what frame 3 draws into, so IT is the stale one now).
    const AsyncFramePlan p3 = planAsyncFrame(s);
    CHECK(p3.drawSlot == 1);
    CHECK(p3.dropStaleFence);
}

TEST_CASE("clearSlot after a publish empties the slot for the planner") {
    StatsRingState s;
    runAsyncFrames(s, 2);                          // frames 0 (slot 0), 1 (slot 1) pending
    const AsyncFramePlan p = planAsyncFrame(s);    // frame 2, publishes slot 1
    REQUIRE(p.publishSlot == 1);
    clearSlot(s, p.publishSlot);                   // the publish succeeded
    // The cleared slot is gone from the planner: re-planning the SAME
    // frame (before the commit) finds nothing left to publish — the
    // publishSlot read for that slot is now -1.
    CHECK(planAsyncFrame(s).publishSlot == -1);
    // And after the commit, the frame that REUSES the cleared slot as its
    // draw slot flags no stale fence — the pack was consumed by its one
    // publish chance, not silently lost.
    commitAsyncDraw(s, p);
    const AsyncFramePlan next = planAsyncFrame(s); // frame 3
    CHECK(next.drawSlot == 1);
    CHECK_FALSE(next.dropStaleFence);
    CHECK(next.publishSlot == 0);                  // frame 2's fresh pack
}

TEST_CASE("drain clears both slots' pending flags and the frame ladder") {
    StatsRingState s;
    runAsyncFrames(s, 5);
    drain(s);
    CHECK_FALSE(s.slot[0].pending);
    CHECK_FALSE(s.slot[1].pending);
    CHECK(s.nextAsyncFrame == 0);
    const AsyncFramePlan p = planAsyncFrame(s);
    CHECK(p.frame == 0);
    CHECK(p.publishSlot == -1);
    CHECK_FALSE(p.dropStaleFence);
}

TEST_CASE("frame bookkeeping: commit records the frame the pack belongs to") {
    StatsRingState s;
    const AsyncFramePlan p0 = planAsyncFrame(s);
    commitAsyncDraw(s, p0);
    CHECK(s.slot[0].pending);
    CHECK(s.slot[0].frame == 0);
    CHECK(s.nextAsyncFrame == 1);
    const AsyncFramePlan p1 = planAsyncFrame(s);
    CHECK(p1.frame == 1);
    commitAsyncDraw(s, p1);
    CHECK(s.slot[1].frame == 1);
    CHECK(s.nextAsyncFrame == 2);
    // nextAsyncFrame advanced exactly twice for two committed draws.
    runAsyncFrames(s, 3);
    CHECK(s.nextAsyncFrame == 5);
}

TEST_CASE("clampStatsMode folds out-of-range jints to Sync") {
    CHECK(clampStatsMode(0) == Off);
    CHECK(clampStatsMode(1) == Sync);
    CHECK(clampStatsMode(2) == Async);
    CHECK(clampStatsMode(-1) == Sync);
    CHECK(clampStatsMode(3) == Sync);
    CHECK(clampStatsMode(1073741824) == Sync);
}
