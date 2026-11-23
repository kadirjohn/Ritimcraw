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
    for (int warp = 0; warp < 2; ++warp) {
        CAPTURE(nr); CAPTURE(sharp); CAPTURE(detail); CAPTURE(warp);
        const ChainPlan p = planChain(kNrLive[nr], sharp, detail, warp);
        REQUIRE(p.count == 1 + 2 * sharp + 2 * detail + warp);
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
        if (!sharp && !detail && !warp) CHECK(p.finalSlot == p.passes[0].out);  // POST's output
        // The warp reads the chain and renders where it records.
        if (warp) {
            REQUIRE(p.warp >= 0);
            CHECK(p.passes[p.warp].kind == kPassWarp);
            CHECK(p.warp == p.count - 1);
            CHECK(p.passes[p.warp].out == chainNextSlot(p.passes[p.warp].in));
            CHECK(p.finalSlot == p.passes[p.warp].out);
        } else {
            CHECK(p.warp == -1);
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
