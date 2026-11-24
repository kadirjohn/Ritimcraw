// F1-6: the node chain WIRE's decode proofs — the C++ half of the
// hand-written mirror pair (gles/NodeChainWire.h + engine/NodeChainWire.kt).
//
// The Kotlin half of the pair (NodeChainWire.encode) is production code the
// JVM suite exercises through the seam tests; THIS file pins the C++
// decode against inputs built the way the Kotlin encoder shapes them
// (header + records + curve block + output tail) and — the load-bearing
// part — against the CPU REFERENCE's own structures: a decoded node must
// round-trip through rawedit::compileNode the same way a hand-built
// GradeNode does, so the wire cannot change what the chain computes.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <cmath>
#include <vector>

#include "../../app/src/main/cpp/gles/NodeChainWire.h"

#include <rawedit/ColorModel.h>

using gles::NodeChainDecoded;

namespace {

// One node's expected-vs-decoded compare, field for field (the wire's
// whole job is fidelity to the knobs; anything softer would hide a
// misaligned slot).
void checkNode(const rawedit::GradeNode& d, const rawedit::GradeNode& want,
               const char* label) {
    CHECK_MESSAGE(d.enabled == want.enabled, label << ": enabled");
    CHECK_MESSAGE(d.mix == want.mix, label << ": mix");
    CHECK_MESSAGE(d.lift.x == want.lift.x, label << ": lift.x");
    CHECK_MESSAGE(d.lift.y == want.lift.y, label << ": lift.y");
    CHECK_MESSAGE(d.lift.master == want.lift.master, label << ": lift.master");
    CHECK_MESSAGE(d.gamma.x == want.gamma.x, label << ": gamma.x");
    CHECK_MESSAGE(d.gamma.y == want.gamma.y, label << ": gamma.y");
    CHECK_MESSAGE(d.gamma.master == want.gamma.master, label << ": gamma.master");
    CHECK_MESSAGE(d.gain.x == want.gain.x, label << ": gain.x");
    CHECK_MESSAGE(d.gain.y == want.gain.y, label << ": gain.y");
    CHECK_MESSAGE(d.gain.master == want.gain.master, label << ": gain.master");
    CHECK_MESSAGE(d.offset.x == want.offset.x, label << ": offset.x");
    CHECK_MESSAGE(d.offset.y == want.offset.y, label << ": offset.y");
    CHECK_MESSAGE(d.offset.master == want.offset.master, label << ": offset.master");
    CHECK_MESSAGE(d.contrast == want.contrast, label << ": contrast");
    CHECK_MESSAGE(d.pivot == want.pivot, label << ": pivot");
    CHECK_MESSAGE(d.sat == want.sat, label << ": sat");
    CHECK_MESSAGE(d.hue == want.hue, label << ": hue");
    CHECK_MESSAGE(d.temp == want.temp, label << ": temp");
    CHECK_MESSAGE(d.tint == want.tint, label << ": tint");
    CHECK_MESSAGE(d.logShadow.x == want.logShadow.x, label << ": logShadow.x");
    CHECK_MESSAGE(d.logMid.y == want.logMid.y, label << ": logMid.y");
    CHECK_MESSAGE(d.logHi.master == want.logHi.master, label << ": logHi.master");
    CHECK_MESSAGE(d.logLow == want.logLow, label << ": logLow");
    CHECK_MESSAGE(d.logHigh == want.logHigh, label << ": logHigh");
    CHECK_MESSAGE(d.lutMix == want.lutMix, label << ": lutMix");
    CHECK_MESSAGE(d.curveY.points == want.curveY.points, label << ": curveY");
    CHECK_MESSAGE(d.curveR.points == want.curveR.points, label << ": curveR");
    CHECK_MESSAGE(d.curveG.points == want.curveG.points, label << ": curveG");
    CHECK_MESSAGE(d.curveB.points == want.curveB.points, label << ": curveB");
}

}  // namespace

TEST_CASE("decode: a two-node chain with curves crosses verbatim") {
    // Built the way NodeChainWire.kt shapes it: header (3) + 2 records
    // (34 each) + the curve block + the output tail (6). Node 0 carries a
    // curve on Y only (mask 1, the other channels identity-skip); node 1
    // is a log-wheel node with a Y curve of a different shape (mask 1).
    std::vector<float> w;
    const int curve0Points = 3;
    const int curve1Points = 4;
    // Per hasCurves node: 1 mask slot + per present channel 1 count + 2*n.
    const size_t curveSlots = (1 + 1 + curve0Points * 2) + (1 + 1 + curve1Points * 2);
    w.resize(gles::kNodeChainWireHeader + 2 * gles::kNodeChainWireStride +
              curveSlots + gles::kNodeChainWireOutput, 0.0f);
    w[0] = 2.0f;
    w[1] = 1.0f;  // DwgLinear (the OpenDRT output)
    w[2] = static_cast<float>(curve0Points + curve1Points);
    float* r0 = w.data() + gles::kNodeChainWireHeader;
    r0[gles::kNodeChainEnabled] = 1.0f;
    // The wire carries every knob's VALUE (NodeChainWire.kt's encoder
    // writes the GradeNodeV2 fields verbatim; their Kotlin defaults are
    // core's identity values), so the hand-built ropes below spell the
    // identity slots out explicitly — a zeroed slot would mean mix=0 (a
    // disabled node), not "unspecified".
    r0[gles::kNodeChainMix] = 0.75f;
    r0[gles::kNodeChainContrast] = 1.3f;
    r0[gles::kNodeChainPivot] = rawedit::kDiMidGray;
    r0[gles::kNodeChainSat] = 1.1f;
    r0[gles::kNodeChainLift] = 0.2f; r0[gles::kNodeChainLift + 1] = -0.1f;
    r0[gles::kNodeChainLift + 2] = 0.05f;
    r0[gles::kNodeChainHue] = 15.0f;
    r0[gles::kNodeChainLogLow] = 0.142f;
    r0[gles::kNodeChainLogHigh] = 0.552f;
    r0[gles::kNodeChainLutMix] = 1.0f;
    r0[gles::kNodeChainHasCurves] = 1.0f;
    float* r1 = r0 + gles::kNodeChainWireStride;
    r1[gles::kNodeChainEnabled] = 1.0f;
    r1[gles::kNodeChainMix] = 1.0f;
    r1[gles::kNodeChainContrast] = 1.0f;
    r1[gles::kNodeChainPivot] = rawedit::kDiMidGray;
    r1[gles::kNodeChainSat] = 1.0f;
    r1[gles::kNodeChainGain] = -0.3f; r1[gles::kNodeChainGain + 1] = 0.25f;
    r1[gles::kNodeChainGain + 2] = 0.1f;
    r1[gles::kNodeChainLogShadow] = 0.12f; r1[gles::kNodeChainLogShadow + 1] = -0.05f;
    r1[gles::kNodeChainLogMid + 1] = 0.08f;
    r1[gles::kNodeChainLogHi + 2] = -0.2f;
    r1[gles::kNodeChainLogLow] = 0.142f;
    r1[gles::kNodeChainLogHigh] = 0.552f;
    r1[gles::kNodeChainLutMix] = 1.0f;
    r1[gles::kNodeChainHasCurves] = 1.0f;
    // The curve block: node 0's mask (Y only = 1) + Y (3 pairs), node
    // 1's mask (Y only = 1) + Y (4 pairs).
    float* c = r1 + gles::kNodeChainWireStride;
    *c++ = 1.0f;  // node 0's channel mask: Y = bit 0
    *c++ = static_cast<float>(curve0Points);
    *c++ = 0.0f; *c++ = 0.05f;
    *c++ = 0.5f; *c++ = 0.6f;
    *c++ = 1.0f; *c++ = 1.0f;
    *c++ = 1.0f;  // node 1's channel mask: Y = bit 0
    *c++ = static_cast<float>(curve1Points);
    *c++ = 0.0f; *c++ = 0.0f;
    *c++ = 0.3f; *c++ = 0.32f;
    *c++ = 0.6f; *c++ = 0.55f;
    *c++ = 1.0f; *c++ = 0.9f;
    // The output tail (slots land at w + size - 6).
    float* o = w.data() + w.size() - gles::kNodeChainWireOutput;
    o[gles::kNodeChainOutLook] = 2.0f;
    o[gles::kNodeChainOutTonescale] = 5.0f;
    o[gles::kNodeChainOutCwp] = 4.0f;
    o[gles::kNodeChainOutCwpLimit] = 0.6f;
    o[gles::kNodeChainOutGrey] = 12.0f;
    o[gles::kNodeChainOutDisplay] = 2.0f;

    gles::NodeChainDecoded d;
    REQUIRE(gles::decodeNodeChainWire(w.data(), static_cast<int>(w.size()), &d));
    REQUIRE(d.nodeCount == 2);
    REQUIRE(d.outputMode == 1);

    rawedit::GradeNode n0;
    n0.enabled = true;
    n0.mix = 0.75f;
    n0.lift = rawedit::NodeWheel{0.2f, -0.1f, 0.05f};
    n0.contrast = 1.3f;
    n0.pivot = rawedit::kDiMidGray;
    n0.sat = 1.1f;
    n0.hue = 15.0f;
    n0.logLow = 0.142f;
    n0.logHigh = 0.552f;
    n0.curveY.points = {{0.0f, 0.05f}, {0.5f, 0.6f}, {1.0f, 1.0f}};
    checkNode(d.nodes[0], n0, "node0");

    rawedit::GradeNode n1;
    n1.enabled = true;
    n1.mix = 1.0f;
    n1.contrast = 1.0f;
    n1.pivot = rawedit::kDiMidGray;
    n1.sat = 1.0f;
    n1.gain = rawedit::NodeWheel{-0.3f, 0.25f, 0.1f};
    n1.logShadow = rawedit::NodeWheel{0.12f, -0.05f, 0.0f};
    n1.logMid = rawedit::NodeWheel{0.0f, 0.08f, 0.0f};
    n1.logHi = rawedit::NodeWheel{0.0f, 0.0f, -0.2f};
    n1.logLow = 0.142f;
    n1.logHigh = 0.552f;
    n1.curveY.points = {{0.0f, 0.0f}, {0.3f, 0.32f}, {0.6f, 0.55f}, {1.0f, 0.9f}};
    checkNode(d.nodes[1], n1, "node1");

    CHECK(d.drtLook == 2);
    CHECK(d.drtTonescale == 5);
    CHECK(d.drtCwp == 4);
    CHECK(d.drtCwpLimit == 0.6f);
    CHECK(d.drtGrey == 12.0f);
    CHECK(d.drtDisplay == 2);
}

TEST_CASE("decode: an empty wire with the legacy output is the clear shape") {
    std::vector<float> w(gles::kNodeChainWireHeader + gles::kNodeChainWireOutput, 0.0f);
    w[2] = 0.0f;
    gles::NodeChainDecoded d;
    REQUIRE(gles::decodeNodeChainWire(w.data(), static_cast<int>(w.size()), &d));
    CHECK(d.nodeCount == 0);
    CHECK(d.outputMode == 0);
    CHECK(d.nodes.empty());
    CHECK(d.error.empty());
}

TEST_CASE("decode: the honest refusals") {
    gles::NodeChainDecoded d;
    // Too short.
    std::vector<float> tiny(4, 0.0f);
    CHECK_FALSE(gles::decodeNodeChainWire(tiny.data(), 4, &d));
    CHECK(d.error.find("too short") != std::string::npos);
    // nodeCount past the N1 cap.
    std::vector<float> over(gles::kNodeChainWireHeader + gles::kNodeChainWireOutput + 9 * gles::kNodeChainWireStride, 0.0f);
    over[0] = 9.0f;
    over[2] = 0.0f;
    CHECK_FALSE(gles::decodeNodeChainWire(over.data(), static_cast<int>(over.size()), &d));
    CHECK(d.error.find("outside 0..8") != std::string::npos);
    // A boolean outside 0/1.
    std::vector<float> one(gles::kNodeChainWireHeader + gles::kNodeChainWireStride +
                               gles::kNodeChainWireOutput, 0.0f);
    one[0] = 1.0f;
    one[2] = 0.0f;
    one[gles::kNodeChainWireHeader + gles::kNodeChainEnabled] = 0.37f;
    CHECK_FALSE(gles::decodeNodeChainWire(one.data(), static_cast<int>(one.size()), &d));
    CHECK(d.error.find("must be 0.0 or 1.0") != std::string::npos);
    // A header that disagrees with the curve block.
    std::vector<float> curves(gles::kNodeChainWireHeader + gles::kNodeChainWireStride +
                                  (1 + 1 + 3 * 2) + gles::kNodeChainWireOutput, 0.0f);
    curves[0] = 1.0f;
    curves[1] = 0.0f;
    curves[2] = 5.0f;  // claims 5 pairs, the wire carries 3
    float* cr = curves.data() + gles::kNodeChainWireHeader;
    cr[gles::kNodeChainEnabled] = 1.0f;
    cr[gles::kNodeChainHasCurves] = 1.0f;
    float* cp = cr + gles::kNodeChainWireStride;
    cp[0] = 1.0f;  // mask: Y
    cp[1] = 3.0f;  // then 3 pairs
    CHECK_FALSE(gles::decodeNodeChainWire(curves.data(), static_cast<int>(curves.size()), &d));
    CHECK(d.error.find("disagrees with the header's") != std::string::npos);
    // A non-finite slot.
    std::vector<float> nan = curves;
    nan[2] = 3.0f;
    nan[gles::kNodeChainWireHeader + gles::kNodeChainContrast] = std::nanf("");
    CHECK_FALSE(gles::decodeNodeChainWire(nan.data(), static_cast<int>(nan.size()), &d));
    CHECK(d.error.find("not finite") != std::string::npos);
}

TEST_CASE("decode -> compileNode: the wire cannot change what the chain computes") {
    // THE F1-6 fidelity proofs: a decoded node compiles to the SAME pack
    // as the hand-built GradeNode (so the wire cannot change the GPU's
    // uniforms), and applyNodeDi applies the same DI math (so it cannot
    // change the CPU's pixels either).
    std::vector<float> w;
    const int points = 4;
    const size_t curveSlots = 1 + 1 + points * 2;  // mask + count + pairs
    w.resize(gles::kNodeChainWireHeader + gles::kNodeChainWireStride + curveSlots +
             gles::kNodeChainWireOutput, 0.0f);
    w[0] = 1.0f;
    w[1] = 0.0f;
    w[2] = static_cast<float>(points);
    float* r = w.data() + gles::kNodeChainWireHeader;
    r[gles::kNodeChainEnabled] = 1.0f;
    r[gles::kNodeChainMix] = 1.0f;
    r[gles::kNodeChainContrast] = 1.4f;
    r[gles::kNodeChainPivot] = rawedit::kDiMidGray;
    r[gles::kNodeChainSat] = 0.85f;
    r[gles::kNodeChainLift] = 0.3f; r[gles::kNodeChainLift + 1] = -0.4f;
    r[gles::kNodeChainGamma + 2] = 0.2f;
    r[gles::kNodeChainGain] = -0.25f; r[gles::kNodeChainGain + 2] = 0.15f;
    r[gles::kNodeChainOffset + 1] = 0.4f;
    r[gles::kNodeChainHue] = -30.0f;
    r[gles::kNodeChainTemp] = 0.3f;
    r[gles::kNodeChainTint] = -0.2f;
    r[gles::kNodeChainLogShadow + 2] = 0.1f;
    r[gles::kNodeChainLogLow] = 0.142f;
    r[gles::kNodeChainLogHigh] = 0.552f;
    r[gles::kNodeChainLutMix] = 1.0f;
    r[gles::kNodeChainHasCurves] = 1.0f;
    float* c = r + gles::kNodeChainWireStride;
    *c++ = 1.0f;  // mask: Y
    *c++ = static_cast<float>(points);
    *c++ = 0.0f; *c++ = 0.02f;
    *c++ = 0.4f; *c++ = 0.38f;
    *c++ = 0.7f; *c++ = 0.8f;
    *c++ = 1.0f; *c++ = 1.0f;

    gles::NodeChainDecoded d;
    REQUIRE(gles::decodeNodeChainWire(w.data(), static_cast<int>(w.size()), &d));
    REQUIRE(d.nodeCount == 1);

    rawedit::GradeNode hand;
    hand.enabled = true;
    hand.lift = rawedit::NodeWheel{0.3f, -0.4f, 0.0f};
    hand.gamma = rawedit::NodeWheel{0.0f, 0.0f, 0.2f};
    hand.gain = rawedit::NodeWheel{-0.25f, 0.0f, 0.15f};
    hand.offset = rawedit::NodeWheel{0.0f, 0.4f, 0.0f};
    hand.contrast = 1.4f;
    hand.sat = 0.85f;
    hand.hue = -30.0f;
    hand.temp = 0.3f;
    hand.tint = -0.2f;
    hand.logShadow = rawedit::NodeWheel{0.0f, 0.0f, 0.1f};
    hand.curveY.points = {{0.0f, 0.02f}, {0.4f, 0.38f}, {0.7f, 0.8f}, {1.0f, 1.0f}};

    const rawedit::CompiledNode wirePack = rawedit::compileNode(d.nodes[0]);
    const rawedit::CompiledNode handPack = rawedit::compileNode(hand);
    for (int i = 0; i < 3; ++i) {
        CHECK(wirePack.lift[i] == handPack.lift[i]);
        CHECK(wirePack.gain[i] == handPack.gain[i]);
        CHECK(wirePack.offset[i] == handPack.offset[i]);
        CHECK(wirePack.gammaExp[i] == handPack.gammaExp[i]);
        CHECK(wirePack.wb[i] == handPack.wb[i]);
    }
    CHECK(wirePack.contrast == handPack.contrast);
    CHECK(wirePack.pivot == handPack.pivot);
    CHECK(wirePack.sat == handPack.sat);
    for (int i = 0; i < 9; ++i) {
        CHECK(wirePack.hueMat[i] == handPack.hueMat[i]);
    }
    REQUIRE(wirePack.curves.size() == handPack.curves.size());
    for (size_t i = 0; i < wirePack.curves.size(); ++i) {
        CHECK(wirePack.curves[i] == handPack.curves[i]);
    }

    // And one DI pixel: the decoded node applies the same math.
    float diWire[3] = {0.336f, 0.42f, 0.21f};
    float diHand[3] = {0.336f, 0.42f, 0.21f};
    rawedit::applyNodeDi(d.nodes[0], diWire);
    rawedit::applyNodeDi(hand, diHand);
    CHECK(diWire[0] == diHand[0]);
    CHECK(diWire[1] == diHand[1]);
    CHECK(diWire[2] == diHand[2]);
}

TEST_CASE("the wire's constants agree with the Kotlin mirror's caps") {
    // The stale-mirror catch is the JVM NodeChainWireTest reading this
    // header; this side pins the same invariants the decode relies on.
    CHECK(gles::kNodeChainWireMaxNodes == static_cast<int>(rawedit::kMaxNodes));
    CHECK(gles::kNodeChainWireHeader == 3);
    CHECK(gles::kNodeChainWireStride == 34);
    CHECK(gles::kNodeChainWireOutput == 6);
}
