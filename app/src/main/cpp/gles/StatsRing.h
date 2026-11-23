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
// INVARIANT (the rule every caller must keep): a pending pack may survive
// ONLY across CONSECUTIVE async frames — every sync, off, or realloc frame
// DRAINS the ring first (the fences are deleted, the pending flags
// cleared), and a pack gets exactly ONE publish chance (at the next async
// frame) before its slot is reused by the frame after that.
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
// draws into); publishSlot = the (frame-1)&1 slot ONLY when frame > 0 and
// that slot holds a pending pack (the frame-N-1 pack this frame may
// publish if its fence already signaled), else -1 (nothing to publish);
// dropStaleFence = the DRAW slot is still pending — the never-published
// frame-N-2 pack whose fence must be deleted BEFORE the new one exists.
struct AsyncFramePlan {
    uint64_t frame = 0;
    int drawSlot = 0;
    int publishSlot = -1;
    bool dropStaleFence = false;
};

inline AsyncFramePlan planAsyncFrame(const StatsRingState& s) {
    AsyncFramePlan p;
    p.frame = s.nextAsyncFrame;
    p.drawSlot = static_cast<int>(p.frame & 1);
    if (p.frame > 0) {
        const int prev = static_cast<int>((p.frame - 1) & 1);
        if (s.slot[prev].pending) p.publishSlot = prev;
    }
    p.dropStaleFence = s.slot[p.drawSlot].pending;
    return p;
}

// Marks the drawn slot pending with the plan's frame (overwriting any
// stale entry — the frame-N-2 pack's one publish chance is gone) and
// advances nextAsyncFrame exactly once.
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
