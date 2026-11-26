// ChainSlots planner tests (2026-11-23 owner report: the warp pass wrote
// the slot AFTER the one its FBO rendered into, so TAIL read the un-graded
// PRE image with stabilization on). The planner header is pure (no GL), so
// the invariants run on desktop for every NR x sharp x detail x warp combo.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include "../../app/src/main/cpp/gles/ChainSlots.h"

using namespace gles;

namespace {
const int kNrLive[4] = {kSlotA, kSlotC, kSlotB, kSlotB};  // none/chroma/luma/both
}

TEST_CASE("NR live slot matches draw()'s NR section") {
    CHECK(chainSlotAfterNr(false, false) == kSlotA);
    CHECK(chainSlotAfterNr(true, false) == kSlotC);
    CHECK(chainSlotAfterNr(false, true) == kSlotB);
    CHECK(chainSlotAfterNr(true, true) == kSlotB);
}

TEST_CASE("every combination satisfies the chain invariants") {
    for (int nr = 0; nr < 4; ++nr)
    for (int sharp = 0; sharp < 2; ++sharp)
    for (int detail = 0; detail < 2; ++detail)
    for (int warp = 0; warp < 2; ++warp)
    for (int nodes = 0; nodes < 2; ++nodes) {
        CAPTURE(nr); CAPTURE(sharp); CAPTURE(detail); CAPTURE(warp);
        CAPTURE(nodes);
        const ChainPlan p = planChain(kNrLive[nr], sharp, detail, warp,
                                      nodes != 0);
        REQUIRE(p.count == 1 + 2 * sharp + 2 * detail + warp + nodes);
        CHECK(p.passes[0].kind == kPassPost);
        CHECK(p.passes[0].in == kNrLive[nr]);
        int live = kNrLive[nr];
        for (int i = 0; i < p.count; ++i) {
            const ChainPass& c = p.passes[i];
            CAPTURE(i);
            CHECK(c.in == live);  // the chain is contiguous
            CHECK(c.out != c.in);
            CHECK(c.out >= 0);
            CHECK(c.out <= 2);
            if (c.guide >= 0) {
                CHECK(c.out != c.guide);
                CHECK(c.in != c.guide);
                // The guide is the stage's input (its H pass's input) and
                // the H pass did not overwrite it before this V pass ran.
                CHECK(p.passes[i - 1].in == c.guide);
                CHECK(p.passes[i - 1].out != c.guide);
            }
            if (c.kind == kPassSharpV || c.kind == kPassDetailV) CHECK(c.guide >= 0);
            else CHECK(c.guide == -1);
            live = c.out;
        }
        // The last executed pass writes the final slot; TAIL reads it.
        CHECK(p.passes[p.count - 1].out == p.finalSlot);
        CHECK(live == p.finalSlot);
        if (!sharp && !detail && !warp && !nodes)
            CHECK(p.finalSlot == p.passes[0].out);  // POST's output
        // The warp reads the chain and renders where it records. With the
        // NODES pass in the chain (F1-5) the warp stops being the LAST
        // pass — it keeps its position (nodes runs after it) and the old
        // "warp == count - 1" claim becomes warp == count - 1 - nodes.
        if (warp) {
            REQUIRE(p.warp >= 0);
            CHECK(p.passes[p.warp].kind == kPassWarp);
            CHECK(p.warp == p.count - 1 - nodes);
            CHECK(p.passes[p.warp].out == chainNextSlot(p.passes[p.warp].in));
            if (!nodes)
                CHECK(p.finalSlot == p.passes[p.warp].out);
            else
                // nodes reads the warp's output: the warp feeds the chain.
                CHECK(p.passes[p.warp].out == p.passes[p.nodes].in);
        } else {
            CHECK(p.warp == -1);
        }
        // The NODES pass (F1-5): runs last when present, reads the previous
        // pass's output, writes the next slot, never itself, no guide.
        if (nodes) {
            REQUIRE(p.nodes >= 0);
            CHECK(p.nodes == p.count - 1);
            CHECK(p.passes[p.nodes].kind == kPassNodes);
            CHECK(p.passes[p.nodes].in == p.passes[p.nodes - 1].out);
            CHECK(p.passes[p.nodes].out == chainNextSlot(p.passes[p.nodes].in));
            CHECK(p.passes[p.nodes].guide == -1);
            CHECK(p.finalSlot == p.passes[p.nodes].out);
        } else {
            CHECK(p.nodes == -1);
        }
        CHECK((p.sharpH >= 0) == (sharp != 0));
        CHECK((p.sharpV >= 0) == (sharp != 0));
        CHECK((p.detailH >= 0) == (detail != 0));
        CHECK((p.detailV >= 0) == (detail != 0));
    }
}

// The slot choices the pre-refactor draw() produced (read from the code
// before the refactor, hard-coded here): POST out = B if live is A else A;
// each unsharp H -> next(in), V -> next(next(in)) with guide = in.
TEST_CASE("POST/sharp/detail slots equal the pre-refactor table") {
    struct Row {
        int nrLive, postOut, sIn, sH, sV;      // POST + sharpness
        int dIn, dH, dV;                       // detail after sharpness
        int dInAlone, dHAlone, dVAlone;        // detail without sharpness
    };
    const Row rows[] = {
        // nrLive A: post B; sharp B->C->A; then detail A->B->C; alone B->C->A
        {kSlotA, kSlotB, kSlotB, kSlotC, kSlotA, kSlotA, kSlotB, kSlotC, kSlotB, kSlotC, kSlotA},
        // nrLive C: post A; sharp A->B->C; then detail C->A->B; alone A->B->C
        {kSlotC, kSlotA, kSlotA, kSlotB, kSlotC, kSlotC, kSlotA, kSlotB, kSlotA, kSlotB, kSlotC},
        // nrLive B: post A; identical to C
        {kSlotB, kSlotA, kSlotA, kSlotB, kSlotC, kSlotC, kSlotA, kSlotB, kSlotA, kSlotB, kSlotC},
    };
    for (const Row& r : rows) {
        CAPTURE(r.nrLive);
        const ChainPlan both = planChain(r.nrLive, true, true, false);
        CHECK(both.passes[0].out == r.postOut);
        CHECK(both.passes[both.sharpH].in == r.sIn);
        CHECK(both.passes[both.sharpH].out == r.sH);
        CHECK(both.passes[both.sharpV].out == r.sV);
        CHECK(both.passes[both.sharpV].guide == r.sIn);
        CHECK(both.passes[both.detailH].in == r.dIn);
        CHECK(both.passes[both.detailH].out == r.dH);
        CHECK(both.passes[both.detailV].out == r.dV);
        CHECK(both.passes[both.detailV].guide == r.dIn);
        const ChainPlan det = planChain(r.nrLive, false, true, false);
        CHECK(det.passes[det.detailH].in == r.dInAlone);
        CHECK(det.passes[det.detailH].out == r.dHAlone);
        CHECK(det.passes[det.detailV].out == r.dVAlone);
        CHECK(det.passes[det.detailV].guide == r.dInAlone);
    }
}

TEST_CASE("regression: NR and unsharp off, warp renders into POST's next slot and TAIL reads it") {
    const ChainPlan p = planChain(kSlotA, false, false, true);
    CHECK(p.passes[0].out == kSlotB);        // POST -> texB
    CHECK(p.passes[p.warp].in == kSlotB);
    CHECK(p.passes[p.warp].out == kSlotC);   // warp renders into C ...
    CHECK(p.finalSlot == kSlotC);            // ... and TAIL reads C, never A (PRE)
}

// The plan doc's hand-derived full-chain example (COLOR_MODEL_V2_PLAN §5.2:
// "NR yok, sharp+detail+warp+nodes açıkken: POST A->B; sharpH B->C, sharpV
// C->A (guide B); detailH A->B, detailV B->C (guide A); warp C->A; nodes
// A->B; TAIL B'yi okur"), verified against the planner output — the table
// the warp-slot lesson made mandatory, now with the NODES row appended.
TEST_CASE("F1-5: the plan doc's full-chain slot table (sharp+detail+warp+nodes)") {
    const ChainPlan p = planChain(kSlotA, true, true, true, true);
    REQUIRE(p.count == 7);
    CHECK(p.passes[0].out == kSlotB);                        // POST A->B
    CHECK(p.passes[p.sharpH].in == kSlotB);                 // sharpH B->C
    CHECK(p.passes[p.sharpH].out == kSlotC);
    CHECK(p.passes[p.sharpV].out == kSlotA);                 // sharpV C->A
    CHECK(p.passes[p.sharpV].guide == kSlotB);
    CHECK(p.passes[p.detailH].in == kSlotA);                 // detailH A->B
    CHECK(p.passes[p.detailH].out == kSlotB);
    CHECK(p.passes[p.detailV].out == kSlotC);                // detailV B->C
    CHECK(p.passes[p.detailV].guide == kSlotA);
    CHECK(p.passes[p.warp].in == kSlotC);                    // warp C->A
    CHECK(p.passes[p.warp].out == kSlotA);
    CHECK(p.passes[p.nodes].in == kSlotA);                    // nodes A->B
    CHECK(p.passes[p.nodes].out == kSlotB);
    CHECK(p.finalSlot == kSlotB);                            // TAIL reads B
}

// The regression lock: runNodes=false must reproduce the pre-F1-5 planner's
// table EXACTLY — the same slots for POST/sharp/detail/warp and the same
// finalSlot, on every legacy combination. The default argument keeps the
// old call sites (draw()'s pre-F1-5 shape) bit-identical.
TEST_CASE("F1-5 regression: runNodes=false equals the pre-F1-5 planner everywhere") {
    for (int nr = 0; nr < 4; ++nr)
    for (int sharp = 0; sharp < 2; ++sharp)
    for (int detail = 0; detail < 2; ++detail)
    for (int warp = 0; warp < 2; ++warp) {
        CAPTURE(nr); CAPTURE(sharp); CAPTURE(detail); CAPTURE(warp);
        const ChainPlan p = planChain(kNrLive[nr], sharp, detail, warp, false);
        // The explicitly-threaded form and the default-argument form agree.
        const ChainPlan def = planChain(kNrLive[nr], sharp, detail, warp);
        CHECK(def.count == p.count);
        CHECK(def.finalSlot == p.finalSlot);
        CHECK(def.nodes == -1);
        for (int i = 0; i < p.count; ++i) {
            CHECK(def.passes[i].kind == p.passes[i].kind);
            CHECK(def.passes[i].in == p.passes[i].in);
            CHECK(def.passes[i].out == p.passes[i].out);
            CHECK(def.passes[i].guide == p.passes[i].guide);
        }
        // And no nodes pass exists anywhere in the legacy plan.
        for (int i = 0; i < p.count; ++i)
            CHECK(p.passes[i].kind != kPassNodes);
    }
}

// Glitter suppression parks its encoded chroma in the slot POST neither reads
// nor writes (the encode pass runs right before POST, so nothing else is live).
TEST_CASE("the glitter encode slot is the third slot, never POST's input or output") {
    for (int nr = 0; nr < 4; ++nr) {
        CAPTURE(nr);
        const ChainPlan p = planChain(kNrLive[nr], false, false, false);
        const int in = p.passes[0].in, out = p.passes[0].out;
        const int freeSlot = chainFreeSlot(in, out);
        CHECK(freeSlot >= 0);
        CHECK(freeSlot <= 2);
        CHECK(freeSlot != in);
        CHECK(freeSlot != out);
    }
}
