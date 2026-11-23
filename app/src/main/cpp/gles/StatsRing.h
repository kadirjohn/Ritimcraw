// StatsRing.h — the GPU preview stats readback's 2-slot fence-ring planner
// (pure, no GL).
//
// 2026-11-23 owner request (guide A1): draw 3's glMapBufferRange mapped the
// stats SSBO in the SAME frame it was drawn, forcing the CPU to wait for
// the whole GPU frame (78.5 ms median with scopes ON vs ~18 ms OFF). The
// fix reads the pack back ONE frame late in playback (ASYNC): frame N draws
// its pack into buf[N&1] + fences it, and publishes the (N-1)&1 pack only
// when that fence is ALREADY signaled (glClientWaitSync timeout 0 — never
// blocks). This header is the pure state machine for that ring, the
// ChainSlots.h precedent: GL code is Android-only, so the ring's DECISIONS
// are planned and tested here on desktop, and GlesPreviewSession.cpp only
// executes them.
//
// INVARIANT (the rule every caller must keep): every sync, off, or realloc
// frame DRAINS the ring first (the fences are deleted, the pending flags
// cleared), so no fence survives a non-async frame. Inside a run of
// async frames NO pack is ever dropped: a pack that misses its first
// publish chance (its fence not signaled yet) stays pending and is
// published at the NEXT async frame, with a bounded wait, before its slot
// is reused (the "two misses" rule below).
#pragma once

#include <cstdint>

namespace gles {

// The mode vocabulary shared with the Kotlin PreviewStatsMode enum (the
// jint that crosses the JNI seam as the render door's last parameter).
enum StatsReadbackMode : int {
    Off = 0,   // no consumer on screen: draw 3 + readback skipped entirely
    Sync = 1,  // settle/drag/first frame: today's blocking readback
    Async = 2  // playback: fence ring, publish N-1 only when already done
};

// Folds any jint the JNI seam handed us into a legal mode. The bridge's
// master kill switch (gPreviewStatsEnabled) folds separately and can only
// force Off; this clamp handles the out-of-range remainder (negative, >2)
// by choosing Sync — the safe default and today's behavior.
inline int clampStatsMode(int v) {
    if (v == Off || v == Async) return v;
    return Sync;
}

struct StatsRingSlot {
    bool pending = false;     // the slot's SSBO holds an unpublished pack
    uint64_t frame = 0;       // the async frame index that pack belongs to
};

struct StatsRingState {
    StatsRingSlot slot[2];    // slot[i] backs the packs of frames with
                              // (frame & 1) == i
    uint64_t nextAsyncFrame = 0;  // advances exactly once per committed
                                  // async draw
};

// The plan for the NEXT async frame (what runJob's async leg executes):
// frame = state.nextAsyncFrame; drawSlot = frame & 1 (the SSBO this frame
// draws into); publishSlot = the pending slot this frame publishes, in
// priority order:
//   1. the DRAW slot, when it still holds a pending pack (the frame-N-2
//      pack that missed both earlier chances). It MUST publish now: the
//      draw below overwrites its SSBO. This is `mustPublish` — the caller
//      waits for that fence with a BOUND, finite timeout.
//   2. else the (frame-1)&1 slot, when it holds a pending pack (the normal
//      N-1 publish). This is a try-only publish — the caller never blocks.
//   3. else -1 (nothing to publish).
// dropStaleFence is retired: with rule 1, a pending pack's fence is always
// consumed by its own publish, never deleted over an unread SSBO.
struct AsyncFramePlan {
    uint64_t frame = 0;
    int drawSlot = 0;
    int publishSlot = -1;
    bool mustPublish = false;  // publishSlot is the draw slot: bounded wait
    bool dropStaleFence = false;  // always false now (kept so callers and
                                  // tests written against it still compile)
};

inline AsyncFramePlan planAsyncFrame(const StatsRingState& s) {
    AsyncFramePlan p;
    p.frame = s.nextAsyncFrame;
    p.drawSlot = static_cast<int>(p.frame & 1);
    if (s.slot[p.drawSlot].pending) {
        // Rule 1: the draw slot is about to be overwritten — its pack
        // (frame N-2) publishes THIS frame or is lost.
        p.publishSlot = p.drawSlot;
        p.mustPublish = true;
    } else if (p.frame > 0) {
        const int prev = static_cast<int>((p.frame - 1) & 1);
        if (s.slot[prev].pending) p.publishSlot = prev;
    }
    return p;
}

// Marks the drawn slot pending with the plan's frame and advances
// nextAsyncFrame exactly once. PRECONDITION: the caller published (rule 1)
// or found nothing pending in the draw slot, so commitAsyncDraw never
// overwrites an unread pack.
inline void commitAsyncDraw(StatsRingState& s, const AsyncFramePlan& p) {
    s.slot[p.drawSlot].pending = true;
    s.slot[p.drawSlot].frame = p.frame;
    s.nextAsyncFrame = p.frame + 1;
}

// After a successful publish of the slot's pack: the slot is empty again,
// so the NEXT async frame's plan reads publishSlot = -1 for it.
inline void clearSlot(StatsRingState& s, int slot) {
    s.slot[slot].pending = false;
    s.slot[slot].frame = 0;
}

// Every sync, off, or realloc frame drains first (the header's invariant):
// no fence may survive a non-async frame.
inline void drain(StatsRingState& s) {
    s.slot[0] = StatsRingSlot{};
    s.slot[1] = StatsRingSlot{};
    s.nextAsyncFrame = 0;
}

}  // namespace gles
