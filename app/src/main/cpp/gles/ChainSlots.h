// ChainSlots.h — the develop chain's ping-pong slot planner (pure, no GL).
//
// 2026-11-23 owner report: with stabilization ON the GPU preview ignored
// every grade slider. Root cause: the warp pass bound the framebuffer of
// the next slot but recorded the texture AFTER that one as its output, so
// the TAIL read the un-graded PRE image. The slot formulas were copy-pasted
// per pass; this header is now the single place they live, and draw() takes
// every in/out/guide slot from it (a pass's FBO and its output texture are
// both looked up from the SAME planned output slot, so they cannot drift).
//
// Slots: 0 = texA, 1 = texB, 2 = texC (RGBA32F ping-pong textures).
#pragma once

namespace gles {

enum ChainSlot : int { kSlotA = 0, kSlotB = 1, kSlotC = 2 };

inline int chainNextSlot(int s) { return s == kSlotA ? kSlotB : (s == kSlotB ? kSlotC : kSlotA); }

// The chain's live slot after the NR section: texA with no NR pass, texC
// after the chroma pair, texB after a luma pass (alone or after chroma).
inline int chainSlotAfterNr(bool runChroma, bool runLuma) {
    if (runLuma) return kSlotB;
    return runChroma ? kSlotC : kSlotA;
}

enum ChainPassKind : int {
    kPassPost = 0, kPassSharpH, kPassSharpV, kPassDetailH, kPassDetailV, kPassWarp
};

struct ChainPass {
    ChainPassKind kind = kPassPost;
    int in = kSlotA;
    int out = kSlotA;
    int guide = -1;  // -1: none (only the unsharp V passes carry one)
};

struct ChainPlan {
    ChainPass passes[6];
    int count = 0;
    int finalSlot = kSlotA;  // what TAIL reads
    // Index into passes[] for each optional pass, -1 when it does not run.
    int sharpH = -1, sharpV = -1, detailH = -1, detailV = -1, warp = -1;
};

inline ChainPlan planChain(int nrLive, bool runSharp, bool runDetail, bool runWarp) {
    ChainPlan p;
    auto add = [&](ChainPassKind k, int in, int out, int guide) {
        ChainPass& c = p.passes[p.count];
        c.kind = k; c.in = in; c.out = out; c.guide = guide;
        return p.count++;
    };
    // POST: the pointwise stages write texB when the live slot is A, else A.
    int cur = add(kPassPost, nrLive, nrLive == kSlotA ? kSlotB : kSlotA, -1);
    cur = p.passes[cur].out;
    // Each unsharp stage: H (input -> next), V (H output + the stage's input
    // as guide -> the slot after that).
    auto unsharp = [&](ChainPassKind h, ChainPassKind v, int& hi, int& vi) {
        const int in = cur, hOut = chainNextSlot(in), vOut = chainNextSlot(hOut);
        hi = add(h, in, hOut, -1);
        vi = add(v, hOut, vOut, in);
        cur = vOut;
    };
    if (runSharp) unsharp(kPassSharpH, kPassSharpV, p.sharpH, p.sharpV);
    if (runDetail) unsharp(kPassDetailH, kPassDetailV, p.detailH, p.detailV);
    if (runWarp) {
        p.warp = add(kPassWarp, cur, chainNextSlot(cur), -1);
        cur = p.passes[p.warp].out;
    }
    p.finalSlot = cur;
    return p;
}

}  // namespace gles
