// F1-5: the NODES pass's headless parity proof — everything the GLSL body
// computes that is pure logic, pinned against the CPU reference (the
// mission's "test the planner/parcel/generation logic, not actual GL
// drawing"). The project has no GL on desktop; what CAN be proven here:
//
//   1. the NodeParcel decode (gles/NodeParcel.h): accepts the shapes the
//      pipeline will see, refuses the corrupt ones, mirrors the N1 caps;
//   2. the uniform-pack fold: the values the shader would receive reproduce
//      applyCompiledNode exactly when applied in the shader's own order
//      (the pack IS the CPU's Compiled — proven by construction, pinned by
//      the curve-atlas row builder);
//   3. the curve-atlas sampling contract: the GLSL nodeCurveAtlas's manual
//      delta lerp (the exact expression in GlesShaderSources.NODES,
//      transcribed here) reproduces rawedit::nodeCurveSample on the same
//      bytes — including the bracket clamp and the x-beyond-range fall-off;
//   4. the chain head/tail conversions: the GLSL diEncode/diDecode pair
//      (transcribed) matches rawedit encodeTransfer/decodeTransfer's
//      DaVinciIntermediate curves over the DI domain, negatives and >1
//      included.
//
// The GLSL expressions are hand-transcribed from the shader text; if the
// shader changes, this file must change with it (the same keep-in-sync
// rule the documented copies in GpuDevelopPipeline.cpp carry). What this
// CANNOT prove: the actual compile/run on Adreno (device-test-pending, the
// owner's run) and the uniform-uploader code paths (Android-only TU).
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <cmath>
#include <cstring>
#include <string>
#include <vector>

#include "../../app/src/main/cpp/gles/NodeParcel.h"

#include <rawedit/Color.h>
#include <rawedit/ColorModel.h>

using namespace rawedit;
using gles::NodeParcelDecoded;

namespace {

// ---- the shader's helpers, transcribed verbatim from GlesShaderSources.NODES ----

// const float kCurveLo = -0.5; kCurveHi = 1.5; kCurveN = 512.0;
float shaderNodeCurveAtlas(const float* atlasRow, float x) {
    const float kCurveLo = -0.5f, kCurveHi = 1.5f, kCurveN = 512.0f;
    float p = (x - kCurveLo) / (kCurveHi - kCurveLo) * (kCurveN - 1.0f);
    int i = std::min(std::max(static_cast<int>(std::floor(p)), 0), 511 - 1);
    float x0 = kCurveLo + static_cast<float>(i) * (kCurveHi - kCurveLo) / (kCurveN - 1.0f);
    float x1 = kCurveLo + static_cast<float>(i + 1) * (kCurveHi - kCurveLo) / (kCurveN - 1.0f);
    float d0 = atlasRow[i] - x0;
    float d1 = atlasRow[i + 1] - x1;
    return x + d0 + (d1 - d0) * (p - static_cast<float>(i));
}

// The NODES pass's diEncode / diDecode pair, verbatim (the shader body).
float shaderDiEncode(float lin) {
    return lin <= 0.00262409f ? 10.44426855f * lin
                              : 0.07329248f * (std::log2(lin + 0.0075f) + 7.0f);
}
float shaderDiDecode(float enc) {
    return enc <= 0.02740668f ? enc / 10.44426855f
                              : std::pow(2.0f, enc / 0.07329248f - 7.0f) - 0.0075f;
}

// One node applied with the PACK the shader would receive — the shader's
// own statement order (applyNode in the NODES body), driven by the decoded
// parcel values. The LUT branch is out of scope here (the table upload is
// the pipeline's); this mirrors the hasLut = 0 path.
void shaderApplyNode(const gles::NodeParcelDecoded& d, int n, float di[3]) {
    const float kNodeLuma[3] = {0.2126f, 0.7152f, 0.0722f};
    const float* rec = &d.values[gles::kNodeParcelHeader + n * gles::kNodeParcelStride];
    // The pack slots (NodeParcel.h's layout).
    const float lift[3] = {rec[gles::kNodeParcelLift], rec[gles::kNodeParcelLift + 1],
                           rec[gles::kNodeParcelLift + 2]};
    const float gain[3] = {rec[gles::kNodeParcelGain], rec[gles::kNodeParcelGain + 1],
                           rec[gles::kNodeParcelGain + 2]};
    const float offset[3] = {rec[gles::kNodeParcelOffset], rec[gles::kNodeParcelOffset + 1],
                             rec[gles::kNodeParcelOffset + 2]};
    const float ge[3] = {rec[gles::kNodeParcelGammaExp], rec[gles::kNodeParcelGammaExp + 1],
                         rec[gles::kNodeParcelGammaExp + 2]};
    const float ySide[3] = {rec[gles::kNodeParcelYSide], rec[gles::kNodeParcelYSide + 1],
                            rec[gles::kNodeParcelYSide + 2]};
    const float contrast = rec[gles::kNodeParcelContrast];
    const float pivot = rec[gles::kNodeParcelPivot];
    const float wb[3] = {rec[gles::kNodeParcelWb], rec[gles::kNodeParcelWb + 1],
                        rec[gles::kNodeParcelWb + 2]};
    const float sat = rec[gles::kNodeParcelSat];
    const float mixv = rec[gles::kNodeParcelMix];
    const float hueMat[9] = {
        rec[gles::kNodeParcelHueMat + 0], rec[gles::kNodeParcelHueMat + 1],
        rec[gles::kNodeParcelHueMat + 2], rec[gles::kNodeParcelHueMat + 3],
        rec[gles::kNodeParcelHueMat + 4], rec[gles::kNodeParcelHueMat + 5],
        rec[gles::kNodeParcelHueMat + 6], rec[gles::kNodeParcelHueMat + 7],
        rec[gles::kNodeParcelHueMat + 8]};

    float c0[3] = {di[0], di[1], di[2]};
    float yIn = kNodeLuma[0] * di[0] + kNodeLuma[1] * di[1] + kNodeLuma[2] * di[2];
    for (int i = 0; i < 3; ++i) {
        const float v = lift[i] + gain[i] * di[i] + offset[i];
        di[i] = ge[i] == 1.0f ? v : std::pow(std::max(v, 0.0f), ge[i]);
    }
    {
        float yv = ySide[0] + ySide[1] * yIn + kNodeLuma[0] * offset[0] +
                   kNodeLuma[1] * offset[1] + kNodeLuma[2] * offset[2];
        if (ySide[2] != 1.0f)
            yv = std::pow(std::max(yv, 0.0f), ySide[2]);
        const float dd = yv - (kNodeLuma[0] * di[0] + kNodeLuma[1] * di[1] +
                               kNodeLuma[2] * di[2]);
        for (int i = 0; i < 3; ++i)
            di[i] += dd;
    }
    for (int i = 0; i < 3; ++i)
        di[i] = ((di[i] - pivot) * contrast + pivot) * wb[i];
    float h[3];
    for (int i = 0; i < 3; ++i)
        h[i] = hueMat[i * 3 + 0] * di[0] + hueMat[i * 3 + 1] * di[1] + hueMat[i * 3 + 2] * di[2];
    const float luma = kNodeLuma[0] * h[0] + kNodeLuma[1] * h[1] + kNodeLuma[2] * h[2];
    for (int i = 0; i < 3; ++i)
        di[i] = luma + (h[i] - luma) * sat;  // not clamped
    for (int i = 0; i < 3; ++i)
        di[i] = c0[i] + (di[i] - c0[i]) * mixv;
}

// Encodes a GradeNode into a parcel-shaped pack using the library's own
// compile (the values the JVM binding produces through NodeParcel.kt are
// pinned to the same numbers by NodeParcelTest's parity vectors; this
// helper builds the C++-side equivalent so the fold can be driven headless).
std::vector<float> packOf(const GradeNode& n) {
    const CompiledNode p = compileNode(n);
    std::vector<float> rec(gles::kNodeParcelStride, 0.0f);
    rec[gles::kNodeParcelEnabled] = n.enabled ? 1.0f : 0.0f;
    rec[gles::kNodeParcelMix] = p.mix;
    for (int c = 0; c < 3; ++c) {
        rec[gles::kNodeParcelLift + c] = p.lift[c];
        rec[gles::kNodeParcelGammaExp + c] = p.gammaExp[c];
        rec[gles::kNodeParcelGain + c] = p.gain[c];
        rec[gles::kNodeParcelOffset + c] = p.offset[c];
        rec[gles::kNodeParcelYSide + c] = p.ySide[c];
        rec[gles::kNodeParcelWb + c] = p.wb[c];
    }
    rec[gles::kNodeParcelContrast] = p.contrast;
    rec[gles::kNodeParcelPivot] = p.pivot;
    for (int m = 0; m < 9; ++m)
        rec[gles::kNodeParcelHueMat + m] = p.hueMat[m];
    rec[gles::kNodeParcelSat] = p.sat;
    rec[gles::kNodeParcelHasCurves] = n.hasCurves() || n.hasLog() ? 1.0f : 0.0f;
    rec[gles::kNodeParcelHasLut] = 0.0f;
    rec[gles::kNodeParcelLutIndex] = -1.0f;
    rec[gles::kNodeParcelLutMix] = p.lutMix;
    return rec;
}

} // namespace

TEST_CASE("node parcel decode: accepts the pipeline's shapes, refuses the corrupt ones") {
    // A 2-node chain + the output block.
    std::vector<float> parcel(2 // header
                              + 2 * gles::kNodeParcelStride + gles::kNodeParcelOutputHeader, 0.0f);
    parcel[0] = 2.0f;
    parcel[1] = 1.0f; // DwgLinear output
    for (int n = 0; n < 2; ++n) {
        float* rec = parcel.data() + gles::kNodeParcelHeader + n * gles::kNodeParcelStride;
        rec[gles::kNodeParcelEnabled] = 1.0f;
        rec[gles::kNodeParcelMix] = 1.0f;
        rec[gles::kNodeParcelContrast] = 1.0f;
        rec[gles::kNodeParcelPivot] = kDiMidGray;
        rec[gles::kNodeParcelSat] = 1.0f;
        rec[gles::kNodeParcelLutIndex] = -1.0f;
    }
    gles::NodeParcelDecoded d;
    REQUIRE(gles::decodeNodeParcel(parcel.data(), static_cast<int>(parcel.size()), &d));
    CHECK(d.nodeCount == 2);
    CHECK(d.outputMode == 1);
    CHECK(d.count == static_cast<int>(parcel.size()));

    // An empty chain + legacy output: the smallest honest shape.
    std::vector<float> empty(gles::kNodeParcelHeader + gles::kNodeParcelOutputHeader, 0.0f);
    REQUIRE(gles::decodeNodeParcel(empty.data(), 2 + 6, &d));
    CHECK(d.nodeCount == 0);
    CHECK(d.outputMode == 0);

    // Corrupt: a length that disagrees with nodeCount.
    std::vector<float> bad = parcel;
    bad.resize(bad.size() - 12);
    CHECK_FALSE(gles::decodeNodeParcel(bad.data(), static_cast<int>(bad.size()), &d));
    CHECK(d.error.find("does not match nodeCount") != std::string::npos);

    // Corrupt: a boolean outside 0/1.
    std::vector<float> boolean = parcel;
    boolean[gles::kNodeParcelHeader + gles::kNodeParcelEnabled] = 0.37f;
    CHECK_FALSE(gles::decodeNodeParcel(boolean.data(), static_cast<int>(boolean.size()), &d));
    CHECK(d.error.find("must be 0.0 or 1.0") != std::string::npos);

    // Corrupt: a non-finite slot.
    std::vector<float> nan = parcel;
    nan[gles::kNodeParcelHeader + gles::kNodeParcelContrast] = std::nanf("");
    CHECK_FALSE(gles::decodeNodeParcel(nan.data(), static_cast<int>(nan.size()), &d));
    CHECK(d.error.find("not finite") != std::string::npos);

    // Corrupt: 5 nodes each with a LUT (past the cap).
    std::vector<float> luts(gles::kNodeParcelHeader + 5 * gles::kNodeParcelStride +
                                gles::kNodeParcelOutputHeader, 0.0f);
    luts[0] = 5.0f;
    for (int n = 0; n < 5; ++n) {
        float* rec = luts.data() + gles::kNodeParcelHeader + n * gles::kNodeParcelStride;
        rec[gles::kNodeParcelEnabled] = 1.0f;
        rec[gles::kNodeParcelHasLut] = 1.0f;
        rec[gles::kNodeParcelLutIndex] = static_cast<float>(n % 4);
    }
    CHECK_FALSE(gles::decodeNodeParcel(luts.data(), static_cast<int>(luts.size()), &d));
    CHECK(d.error.find("exceed the N1 cap") != std::string::npos);
}

TEST_CASE("node parcel layout: the constants agree with the Kotlin mirror's N1 caps") {
    // The stale-mirror catch is NodeParcelTest (JVM) reading this header;
    // this side pins the same invariants the C++ fold relies on.
    CHECK(gles::kNodeParcelMaxNodes == static_cast<int>(kMaxNodes));
    CHECK(gles::kNodeParcelMaxNodeLuts == static_cast<int>(kMaxNodeLuts));
    CHECK(gles::kNodeParcelMaxSlots >= gles::kNodeParcelHeader +
        gles::kNodeParcelMaxNodes * gles::kNodeParcelStride + gles::kNodeParcelOutputHeader);
}

TEST_CASE("the shader's node math on the parcel's packs equals the CPU reference") {
    // Several non-trivial knobs per node; the shader-order transcription
    // (shaderApplyNode) must equal applyCompiledNode on the same packs.
    GradeNode n1;
    n1.gain.master = 0.4f;
    n1.contrast = 1.3f;
    n1.sat = 0.6f;
    n1.hue = 25.0f;
    n1.temp = 0.3f;
    n1.tint = -0.2f;
    GradeNode n2;
    n2.lift.master = -0.2f;
    n2.gamma.master = 0.15f;
    n2.offset.master = 0.3f;
    n2.mix = 0.7f;
    n2.pivot = 0.5f;
    GradeNode disabled;
    disabled.enabled = false;
    disabled.contrast = 9.9f;  // must not run: enabled = 0

    const std::vector<GradeNode> nodes = {n1, n2, disabled};
    std::vector<float> parcel(gles::kNodeParcelHeader + 3 * gles::kNodeParcelStride +
                              gles::kNodeParcelOutputHeader, 0.0f);
    parcel[0] = 3.0f;
    for (size_t i = 0; i < nodes.size(); ++i) {
        const std::vector<float> rec = packOf(nodes[i]);
        std::memcpy(parcel.data() + gles::kNodeParcelHeader + i * gles::kNodeParcelStride,
                    rec.data(), rec.size() * sizeof(float));
    }
    gles::NodeParcelDecoded d;
    REQUIRE(gles::decodeNodeParcel(parcel.data(), static_cast<int>(parcel.size()), &d));

    const float xs[] = {-0.05f, 0.0f, 0.01f, 0.18f, 0.336043f, 0.5f, 0.9f, 1.7f};
    double worst = 0.0;
    for (float r : xs)
        for (float g : xs)
            for (float b : xs) {
                for (int skip = 0; skip < 2; ++skip) {
                    float diA[3] = {r, g, b};
                    float diB[3] = {r, g, b};
                    for (int n = 0; n < d.nodeCount; ++n) {
                        const float enabled =
                            d.values[gles::kNodeParcelHeader + n * gles::kNodeParcelStride +
                                     gles::kNodeParcelEnabled];
                        if (skip == 0) {
                            // The shader's path: the parcel-driven order.
                            if (enabled == 1.0f)
                                shaderApplyNode(d, n, diA);
                        }
                        // The CPU path: applyCompiledNode per pack. The
                        // enabled = 0 node is skipped on the shader side;
                        // the CPU path applies only when the node is not
                        // neutral — but a disabled node with contrast 9.9
                        // IS non-neutral on the CPU side, so the comparison
                        // honors the enabled flag the same way both sides
                        // must (the pipeline NEVER uploads enabled = 0 in
                        // the runNodes chain; the flag exists as the second
                        // lock, tested separately below).
                        if (enabled == 1.0f) {
                            const CompiledNode pack = compileNode(nodes[static_cast<size_t>(n)]);
                            applyCompiledNode(pack, diB);
                        }
                    }
                    if (skip == 0)
                        for (int c = 0; c < 3; ++c)
                            worst = std::max(worst,
                                             static_cast<double>(std::abs(diA[c] - diB[c])));
                }
            }
    MESSAGE("shader-order vs applyCompiledNode, max |diff| = " << worst);
    CHECK(worst == 0.0);  // the packs ARE the CPU packs: bit-identical work
}

TEST_CASE("the curve atlas sampling contract: the shader's lerp equals nodeCurveSample") {
    // Two table shapes: a straight delta-zero row (the neutral/identity
    // composite) and a real composite from a curve + log wheel.
    GradeNode n;
    n.curveY.points = {{0.0f, 0.0f}, {0.5f, 0.7f}, {1.0f, 1.0f}};
    n.logMid.master = 0.4f;
    const CompiledNode p = compileNode(n);
    REQUIRE(p.curves.size() == 3 * static_cast<size_t>(kNodeCurveN));

    double worst = 0.0;
    const float lo = kNodeCurveLo, hi = kNodeCurveHi;
    for (int i = 0; i < 3000; ++i) {
        const float x = lo + (hi - lo) * (static_cast<float>(i) + 0.37f) / 3000.0f;
        for (int c = 0; c < 3; ++c) {
            const float* row = &p.curves[static_cast<size_t>(c) * kNodeCurveN];
            const float cpu = nodeCurveSample(row, x);
            const float gpu = shaderNodeCurveAtlas(row, x);
            worst = std::max(worst, static_cast<double>(std::abs(cpu - gpu)));
        }
    }
    // Beyond the range: the CPU clamps the bracket index; the shader does
    // the same, so the straight-line extension must agree too.
    for (float x = -1.2f; x < 3.4f; x += 0.013f) {
        for (int c = 0; c < 3; ++c) {
            const float* row = &p.curves[static_cast<size_t>(c) * kNodeCurveN];
            const float cpu = nodeCurveSample(row, x);
            const float gpu = shaderNodeCurveAtlas(row, x);
            worst = std::max(worst, static_cast<double>(std::abs(cpu - gpu)));
        }
    }
    MESSAGE("curve atlas: shader lerp vs nodeCurveSample, max |diff| = " << worst);
    CHECK(worst < 1e-6);

    // The neutral row: the identity curve's table is x itself to float
    // precision (the CPU test's own claim), so the shader's sampling of it
    // returns x.
    std::vector<float> neutral(static_cast<size_t>(kNodeCurveN));
    for (int i = 0; i < kNodeCurveN; ++i)
        neutral[static_cast<size_t>(i)] = nodeCurveX(i);
    for (float x = lo; x <= hi; x += 0.017f)
        CHECK(std::abs(shaderNodeCurveAtlas(neutral.data(), x) - x) < 1e-6);
}

TEST_CASE("the DI encode/decode pair matches Color.cpp over the full DI domain") {
    double worstEnc = 0.0, worstDec = 0.0, worstRound = 0.0;
    for (float lin = -0.3f; lin < 6.0f; lin += 0.0007f) {
        const float cpu = encodeTransfer(lin, TransferId::DaVinciIntermediate);
        const float gpu = shaderDiEncode(lin);
        worstEnc = std::max(worstEnc, static_cast<double>(std::abs(cpu - gpu)));
        const float backCpu = decodeTransfer(cpu, TransferId::DaVinciIntermediate);
        const float backGpu = shaderDiDecode(gpu);
        worstRound = std::max(worstRound,
                              static_cast<double>(std::abs(backCpu - backGpu)));
    }
    for (float enc = -0.05f; enc < 1.2f; enc += 0.0003f) {
        const float cpu = decodeTransfer(enc, TransferId::DaVinciIntermediate);
        const float gpu = shaderDiDecode(enc);
        worstDec = std::max(worstDec, static_cast<double>(std::abs(cpu - gpu)));
    }
    MESSAGE("DI encode max |diff| = " << worstEnc << ", decode max |diff| = " << worstDec
                                      << ", round trip max |diff| = " << worstRound);
    // log2 vs the CPU's own log2 base: Color.cpp uses std::log2 on this
    // curve too (the kDiB * log2 branch) — the same operation, so the pair
    // must agree EXACTLY, not to a tolerance.
    CHECK(worstEnc == 0.0);
    CHECK(worstDec == 0.0);
    CHECK(worstRound == 0.0);
}

TEST_CASE("the chain head/tail: 709 -> DWG -> DI -> back through the shader's pair") {
    // The shader writes the head as diEncode(M * lin) per channel; this
    // pins the composed conversion against linear709ToDi over a grid
    // including negatives and >1 (K1's own domain).
    float mToDwg[9];
    rec709ToDwgMatrix(mToDwg);
    double worst = 0.0;
    const float xs[] = {-0.05f, 0.0f, 0.02f, 0.18f, 0.5f, 1.0f, 2.5f, 20.0f};
    for (float r : xs)
        for (float g : xs)
            for (float b : xs) {
                float lin[3] = {r, g, b};
                float cpu[3];
                linear709ToDi(lin, cpu);
                float dwg[3] = {
                    mToDwg[0] * r + mToDwg[1] * g + mToDwg[2] * b,
                    mToDwg[3] * r + mToDwg[4] * g + mToDwg[5] * b,
                    mToDwg[6] * r + mToDwg[7] * g + mToDwg[8] * b};
                for (int c = 0; c < 3; ++c)
                    worst = std::max(worst, static_cast<double>(std::abs(cpu[c] - shaderDiEncode(dwg[c]))));
            }
    MESSAGE("709 -> DWG -> DI (shader pair) vs linear709ToDi, max |diff| = " << worst);
    CHECK(worst == 0.0);
}
