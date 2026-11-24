// NodeParcel.h — the NODES pass's uniform pack crossing the JNI seam
// (color model v2 F1-5). NOT generated: tools/gen-grade-parcel.py's schema
// owns the 29-slot GRADE parcel only; the node chain is a different packet
// (a variable-length chain + the output transform), so this header and its
// Kotlin mirror (NodeParcel.kt) are a hand-written PAIR locked together by
// NodeParcelTest's offset cross-check (the same stale-mirror catch the
// generated pair has).
//
// WIRE RULES (GradeParcel's own rules, carried over):
//   - APPEND ONLY. Slot offsets are a wire contract; never reorder, never
//     reuse an offset. [kNodeParcelHeader] + per-node strides + the output
//     tail; a decode of any other length refuses.
//   - Booleans cross as 0.0f/1.0f (strict 0/1 validated; 0.37 is a refusal).
//   - Ints cross as int-as-float.
//   - NaN and Infinity are refused in any slot.
//
// WHAT IS DELIBERATELY NOT HERE (the parcel's exclusion list):
//   - the curve TABLES: 3 x 512 floats per node = up to 12,288 floats — never
//     uniforms and never this parcel. They ride a 1D R32F texture ATLAS
//     (the inventory §2.4), filled by the pipeline from the compiled packs
//     (rawedit::compileNode -> curves), revision-keyed like the LUT cache.
//     The parcel carries only the per-node hasCurves FLAG; the pipeline
//     keeps the atlas rows beside the packs it uploads.
//   - the node LUT tables + their binding: per-JOB data like the stage-24
//     LUT binding group (lutPath precedes this parcel at the same seam).
//     The parcel carries hasLut + lutIndex (0..3, a fixed table-slot
//     order) + lutMix so the shader picks the sampler; the table and the
//     12-float binding set are the pipeline's own revision-cached upload,
//     keyed by the lutIndex order this parcel fixes.
//   - nodes' raw GradeNode knobs (wheel x/y, log zone limits...): the
//     packs cross PRE-COMPILED (the inventory §2.1: everything
//     derleme-zamanı — the wheel cosines, the primaries solve, the hue
//     matrix — computed ONCE Kotlin-side with the same formulas the CPU
//     reference uses, mirrored in NodeParcel.kt and pinned by the JVM
//     parity tests against ColorModel's own constants).
#pragma once

#include <cmath>
#include <cstdio>
#include <string>

namespace gles {

// The chain's N1 caps (ColorModel.h's kMaxNodes/kMaxNodeLuts, mirrored so
// the Kotlin side can validate without core; parity pinned by NodeParcelTest
// against the core constants through the C++ header cross-check).
constexpr int kNodeParcelMaxNodes = 8;
constexpr int kNodeParcelMaxNodeLuts = 4;

// ---- layout ---------------------------------------------------------------
//
// [0]      nodeCount (0 = the pass still runs when outputMode = 1 — the
//          709->DWG conversion only; the v1 legacy chain sends count 0 +
//          outputMode 0 and the pipeline adds NO pass at all, plan §3.2)
// [1]      outputMode (0 = legacy: DI decode -> 709, TAIL's v1 variant;
//          1 = DwgLinear: hand DWG linear to TAIL's OpenDRT variant)
// per ACTIVE node i (0 .. nodeCount-1), the node's compiled pack:
//   +0   enabled (0/1; 0 = the shader skips the iteration)
//   +1   mix (clamped 0..1)
//   +2   lift.x  +3 lift.y   +4 lift.master
//   +5   gammaExp.x +6 gammaExp.y +7 gammaExp.master
//   +8   gain.x  +9 gain.y  +10 gain.master
//   +11  offset.x +12 offset.y +13 offset.master
//   +14  ySide0  +15 ySide1  +16 ySide2
//   +17  contrast +18 pivot
//   +19  wb.r +20 wb.g +21 wb.b
//   +22  hueMat m00 +23 m01 +24 m02
//   +25  m10 +26 m11 +27 m12
//   +28  m20 +29 m21 +30 m22        (row-major, uploaded transposed GL_TRUE)
//   +31  sat
//   +32  hasCurves (0/1)
//   +33  hasLut (0/1) +34 lutIndex (0..3) +35 lutMix
// the output transform block, ALWAYS present after the last node record
// (at kNodeParcelHeader + nodeCount * kNodeParcelStride) — the DRT presets
// apply with an empty chain too:
//   +0 look (0..6) +1 tonescale (0..13) +2 cwp (0..6) +3 cwpLimit
//   +4 grey +5 eotf (0 = sRGB 2.2, 1 = Rec.1886 2.4, 2 = HLG 1000 — the
//   OpenDrtDisplay ordinals; the legacy mode ignores the whole block)
constexpr int kNodeParcelStride = 36;
constexpr int kNodeParcelHeader = 2;
constexpr int kNodeParcelOutputHeader = 6;
constexpr int kNodeParcelMaxSlots =
    kNodeParcelHeader + kNodeParcelMaxNodes * kNodeParcelStride + kNodeParcelOutputHeader;

// Named slot offsets for one node record (add kNodeParcelHeader + i*stride).
constexpr int kNodeParcelEnabled = 0;
constexpr int kNodeParcelMix = 1;
constexpr int kNodeParcelLift = 2;        // x, y, master
constexpr int kNodeParcelGammaExp = 5;    // x, y, master
constexpr int kNodeParcelGain = 8;        // x, y, master
constexpr int kNodeParcelOffset = 11;     // x, y, master
constexpr int kNodeParcelYSide = 14;      // a, b, e
constexpr int kNodeParcelContrast = 17;
constexpr int kNodeParcelPivot = 18;
constexpr int kNodeParcelWb = 19;        // r, g, b
constexpr int kNodeParcelHueMat = 22;    // 9, row-major
constexpr int kNodeParcelSat = 31;
constexpr int kNodeParcelHasCurves = 32;
constexpr int kNodeParcelHasLut = 33;
constexpr int kNodeParcelLutIndex = 34;
constexpr int kNodeParcelLutMix = 35;
// The output transform block's slots (add kNodeParcelHeader + n*stride).
constexpr int kNodeParcelOutLook = 0;
constexpr int kNodeParcelOutTonescale = 1;
constexpr int kNodeParcelOutCwp = 2;
constexpr int kNodeParcelOutCwpLimit = 3;
constexpr int kNodeParcelOutGrey = 4;
constexpr int kNodeParcelOutEotf = 5;

// The decoded chain: the flat values plus the two ints the fold reads.
// Curve tables and LUT tables are NOT here (see the header doc).
struct NodeParcelDecoded {
    int nodeCount = 0;      // decoded slot [0]
    int outputMode = 0;     // decoded slot [1]
    int count = 0;          // the ACCEPTED length (header + n*stride + output)
    float values[kNodeParcelMaxSlots] = {};
    std::string error;      // non-empty = refused (decodeNodeParcel)
};

// Decodes + validates. Returns false with the honest message in [d->error]
// on any refusal: a length that is not header + n*stride + output with
// n in 0..8 (the length and slot [0] must AGREE — a miscounted parcel is
// refused, never read misaligned), a non-finite slot, a boolean outside
// strict 0/1, an out-of-range lutIndex, or more LUTs than the cap.
inline bool decodeNodeParcel(const float* parcel, int length, NodeParcelDecoded* d) {
    d->error.clear();
    const auto refuse = [&](const std::string& msg) {
        d->error = msg;
        return false;
    };
    if (length < kNodeParcelHeader + kNodeParcelOutputHeader) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "node parcel: too short (%d, min %d)",
                      length, kNodeParcelHeader + kNodeParcelOutputHeader);
        return refuse(buf);
    }
    const int header = static_cast<int>(parcel[0]);
    if (header < 0 || header > kNodeParcelMaxNodes ||
        length != kNodeParcelHeader + header * kNodeParcelStride +
                      kNodeParcelOutputHeader) {
        char buf[128];
        std::snprintf(buf, sizeof(buf),
                      "node parcel: length %d does not match nodeCount %d "
                      "(recovery: re-encode the chain with NodeParcel.encode)",
                      length, header);
        return refuse(buf);
    }
    for (int i = 0; i < length; ++i) {
        if (!std::isfinite(parcel[i])) {
            char buf[96];
            std::snprintf(buf, sizeof(buf),
                          "node parcel: slot %d is not finite (recovery: the "
                          "parcel must carry finite floats)", i);
            return refuse(buf);
        }
    }
    int luts = 0;
    for (int n = 0; n < header; ++n) {
        const float* rec = parcel + kNodeParcelHeader + n * kNodeParcelStride;
        const auto checkBool = [&](int slot, const char* name) {
            if (rec[slot] != 0.0f && rec[slot] != 1.0f) {
                char buf[96];
                std::snprintf(buf, sizeof(buf),
                              "node parcel: node %d %s must be 0.0 or 1.0 "
                              "(got %g)", n, name,
                              static_cast<double>(rec[slot]));
                refuse(buf);
                return false;
            }
            return true;
        };
        if (!checkBool(kNodeParcelEnabled, "enabled")) return false;
        if (!checkBool(kNodeParcelHasCurves, "hasCurves")) return false;
        if (!checkBool(kNodeParcelHasLut, "hasLut")) return false;
        if (rec[kNodeParcelHasLut] == 1.0f) {
            if (rec[kNodeParcelLutIndex] < 0.0f ||
                rec[kNodeParcelLutIndex] >
                    static_cast<float>(kNodeParcelMaxNodeLuts - 1)) {
                char buf[96];
                std::snprintf(buf, sizeof(buf),
                              "node parcel: node %d lutIndex %g outside 0..%d",
                              n, static_cast<double>(rec[kNodeParcelLutIndex]),
                              kNodeParcelMaxNodeLuts - 1);
                return refuse(buf);
            }
            ++luts;
        }
    }
    if (luts > kNodeParcelMaxNodeLuts) {
        char buf[96];
        std::snprintf(buf, sizeof(buf),
                      "node parcel: %d node LUTs exceed the N1 cap %d", luts,
                      kNodeParcelMaxNodeLuts);
        return refuse(buf);
    }
    d->nodeCount = header;
    d->outputMode = static_cast<int>(parcel[1]);
    d->count = length;
    for (int i = 0; i < length; ++i) d->values[i] = parcel[i];
    return true;
}

}  // namespace gles
