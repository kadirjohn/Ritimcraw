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
    CHECK_FALSE(p.mustPublish);
}

TEST_CASE("healthy ladder: frame N+1 publishes exactly slot N&1") {
    StatsRingState s;
    // The steady state: every publish succeeds, so each frame holds the
    // only pending pack and the next frame offers it try-only (rule 2).
    commitAsyncDraw(s, planAsyncFrame(s));            // frame 0 -> slot 0
    const AsyncFramePlan p1 = planAsyncFrame(s);      // frame 1
    CHECK(p1.publishSlot == 0);                       // frame 0's pack
    CHECK(p1.drawSlot == 1);
    CHECK_FALSE(p1.mustPublish);
    clearSlot(s, p1.publishSlot);                    // publish succeeded
    commitAsyncDraw(s, p1);                           // frame 1 -> slot 1
    const AsyncFramePlan p2 = planAsyncFrame(s);      // frame 2
    CHECK(p2.publishSlot == 1);                       // frame 1's pack
    CHECK(p2.drawSlot == 0);
    CHECK_FALSE(p2.mustPublish);
    clearSlot(s, p2.publishSlot);
    commitAsyncDraw(s, p2);                           // frame 2 -> slot 0
    const AsyncFramePlan p3 = planAsyncFrame(s);      // frame 3
    CHECK(p3.publishSlot == 0);                       // frame 2's pack
    CHECK(p3.drawSlot == 1);
    CHECK_FALSE(p3.mustPublish);
    // The pending pack in the publish slot belongs to frame 2.
    CHECK(s.slot[p3.publishSlot].pending);
    CHECK(s.slot[p3.publishSlot].frame == 2);
}

TEST_CASE("starved ladder: misses promote the oldest at-risk pack (rule 1)") {
    StatsRingState s;
    // No publish ever succeeds: frames 0,1,2 commit with both slots left
    // pending. Frame 3 draws into slot 1, which still holds frame 1's
    // unpublished pack — rule 1 promotes THAT pack (the store pass would
    // overwrite it), while frame 2's pack in slot 0 keeps its wait in
    // line for the frame after.
    runAsyncFrames(s, 3);
    const AsyncFramePlan p = planAsyncFrame(s);
    CHECK(p.frame == 3);
    CHECK(p.drawSlot == 1);
    CHECK(p.publishSlot == 1);
    CHECK(p.mustPublish);
    CHECK(s.slot[p.publishSlot].frame == 1);
    // The bounded publish succeeded; slot 1 clears before the commit.
    clearSlot(s, p.publishSlot);
    commitAsyncDraw(s, p);
    // Frame 4: draw slot 0 — frame 2's never-published pack, now two
    // retries old, gets its bounded turn.
    const AsyncFramePlan p4 = planAsyncFrame(s);
    CHECK(p4.drawSlot == 0);
    CHECK(p4.publishSlot == 0);
    CHECK(p4.mustPublish);
    CHECK(s.slot[0].frame == 2);
}

TEST_CASE("mustPublish: an unpublished frame N-2 pack takes priority over N-1") {
    StatsRingState s;
    // Frames 0 and 1 commit, neither publishes (the fences never signaled).
    runAsyncFrames(s, 2);
    // Frame 2's plan: it draws into slot 0, which still holds frame 0's
    // never-published pack. Rule 1: publishSlot is the DRAW slot itself
    // with mustPublish set — the pack must be consumed with a bounded
    // wait BEFORE the store pass overwrites its SSBO. Nothing is dropped.
    const AsyncFramePlan p = planAsyncFrame(s);
    CHECK(p.drawSlot == 0);
    CHECK(p.publishSlot == 0);
    CHECK(p.mustPublish);
    CHECK_FALSE(p.dropStaleFence);
    CHECK(s.slot[0].frame == 0);
    clearSlot(s, p.publishSlot);  // the bounded publish succeeded
    // After the commit, slot 0 holds frame 2's pack.
    commitAsyncDraw(s, p);
    CHECK(s.slot[0].pending);
    CHECK(s.slot[0].frame == 2);
    // Frame 3 normally publishes frame 2's pack (slot 0) as a try-only
    // N-1 publish — but its DRAW slot is 1, slot 1 still holds frame 1's
    // never-published pack, so rule 1 fires there instead.
    const AsyncFramePlan p3 = planAsyncFrame(s);
    CHECK(p3.drawSlot == 1);
    CHECK(p3.publishSlot == 1);
    CHECK(p3.mustPublish);
}

TEST_CASE("mustPublish ring never drops a pack: the N-1 miss becomes the next frame's priority") {
    StatsRingState s;
    // Frame 0 commits; frame 1's try-only publish of frame 0 misses.
    const AsyncFramePlan p0 = planAsyncFrame(s);
    commitAsyncDraw(s, p0);
    const AsyncFramePlan p1 = planAsyncFrame(s);   // frame 1: try-only publish of slot 0
    REQUIRE(p1.publishSlot == 0);
    REQUIRE_FALSE(p1.mustPublish);
    commitAsyncDraw(s, p1);                         // the fence was not signaled: no clearSlot
    // Frame 2: BOTH slots pending. Rule 1 must pick the draw slot (slot 0,
    // frame 0's pack) — the only pack the store pass would destroy.
    const AsyncFramePlan p2 = planAsyncFrame(s);
    REQUIRE(p2.drawSlot == 0);
    REQUIRE(p2.publishSlot == 0);
    REQUIRE(p2.mustPublish);
    clearSlot(s, p2.publishSlot);
    commitAsyncDraw(s, p2);
    // Frame 3: slot 1 still holds frame 1's pack (2 frames old), frame 2's
    // pack sits in slot 0. Rule 1 protects slot 1 first.
    const AsyncFramePlan p3 = planAsyncFrame(s);
    REQUIRE(p3.drawSlot == 1);
    REQUIRE(p3.publishSlot == 1);
    REQUIRE(p3.mustPublish);
}

TEST_CASE("clearSlot after a publish empties the slot for the planner") {
    StatsRingState s;
    runAsyncFrames(s, 2);                          // frames 0 (slot 0), 1 (slot 1) pending
    const AsyncFramePlan p = planAsyncFrame(s);    // frame 2: rule 1 owns slot 0
    REQUIRE(p.publishSlot == 0);
    REQUIRE(p.mustPublish);
    clearSlot(s, p.publishSlot);                   // the bounded publish succeeded
    REQUIRE_FALSE(planAsyncFrame(s).mustPublish);  // slot 1: try-only N-1
    commitAsyncDraw(s, p);                          // frame 2 draws slot 0 (now clean)
    // Frame 3: slot 1 still holds frame 1's pack, and frame 3's DRAW slot
    // is 1 — the pack at risk of overwriting. Slot 0 holds frame 2's
    // fresh pack. Rule 1 promotes slot 1 (bounded), not slot 0.
    const AsyncFramePlan p3 = planAsyncFrame(s);
    CHECK(p3.drawSlot == 1);
    CHECK(p3.publishSlot == 1);
    CHECK(p3.mustPublish);
    clearSlot(s, p3.publishSlot);                  // the bounded publish succeeded
    commitAsyncDraw(s, p3);                        // frame 3 draws slot 1 (clean again)
    // Frame 4: slot 0 still holds frame 2's pack — it was never at risk
    // during frames 2-3 (its slot was only ever the PUBLISH slot), but
    // frame 4 draws INTO slot 0, so now rule 1 promotes it (bounded)
    // before the store pass overwrites it. Frame 3's fresh pack in slot 1
    // waits its turn — the ring never drops a pack.
    const AsyncFramePlan next = planAsyncFrame(s); // frame 4
    CHECK(next.drawSlot == 0);
    CHECK(next.publishSlot == 0);
    CHECK(next.mustPublish);
    CHECK(s.slot[0].frame == 2);
    // Only after BOTH packs have had their bounded turn does the healthy
    // try-only ladder resume — no pack is ever silently lost.
    clearSlot(s, next.publishSlot);
    commitAsyncDraw(s, next);                      // frame 4 draws slot 0 (clean)
    // Frame 5: slot 1 still holds frame 3's unpublished pack (frames 3-4
    // only ever consumed slot 0's packs), and frame 5 draws INTO slot 1 —
    // rule 1 again. In a starved ring every other frame carries a
    // mustPublish; the invariant that matters is NEVER-DROP: each frame
    // consumes exactly the pack its own slot endangers, in FIFO order.
    const AsyncFramePlan p5 = planAsyncFrame(s);    // frame 5
    CHECK(p5.drawSlot == 1);
    CHECK(p5.publishSlot == 1);
    CHECK(p5.mustPublish);
    CHECK(s.slot[1].frame == 3);
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
    CHECK_FALSE(p.mustPublish);
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
