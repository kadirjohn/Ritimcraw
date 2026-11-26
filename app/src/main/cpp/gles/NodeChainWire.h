// NodeChainWire.h — the node chain's RAW-KNOB wire crossing the JNI seam
// (color model v2 F1-6). The Kotlin half (NodeChainWire.kt) encodes one
// flat FloatArray per grade change; this header decodes it into
// rawedit::GradeNode values the CPU reference and the GPU pipeline
// compile through core's own rawedit::compileNode (the single source of
// truth — see the Kotlin doc for why raw knobs, not compiled packs).
//
// A hand-written mirror PAIR (the NodeParcel.h mechanism): the GRADE
// parcel's generator owns the 30 scalar slots only, so this pair is
// locked together by NodeChainWireTest's committed-header cross-check
// (the NodeParcelTest pattern).
//
// WIRE RULES (GradeParcel's own, carried over): append-only slots,
// booleans 0.0f/1.0f (strict 0/1 validated), NaN/Infinity refused, and
// the length must AGREE with the header counts (nodeCount at [0], the
// curve PAIR total at [2]) — a miscounted wire is a refusal, never a
// misaligned read.
//
// WHAT IS DELIBERATELY NOT HERE: the node LUT tables/bindings (strings
// cross separately, the stage-24 lutPath precedent — the Kotlin encoder
// refuses LUT-carrying chains for the GPU leg, so decode sees hasLut=0
// only) and the compiled uniform packs (core compiles from these knobs).
#pragma once

#include <cmath>
#include <cstdio>
#include <string>
#include <utility>
#include <vector>

#include <rawedit/ColorModel.h>

#include "NodeLutRegistry.h"

namespace gles {

constexpr int kNodeChainWireHeader = 3;
constexpr int kNodeChainWireStride = 35;
constexpr int kNodeChainWireOutput = 6;
constexpr int kNodeChainWireMaxNodes = 8;  // rawedit::kMaxNodes

// Named per-node slot offsets (add kNodeChainWireHeader + i*stride).
constexpr int kNodeChainEnabled = 0;
constexpr int kNodeChainMix = 1;
constexpr int kNodeChainLift = 2;    // x, y, master
constexpr int kNodeChainGamma = 5;   // x, y, master
constexpr int kNodeChainGain = 8;     // x, y, master
constexpr int kNodeChainOffset = 11;  // x, y, master
constexpr int kNodeChainContrast = 14;
constexpr int kNodeChainPivot = 15;
constexpr int kNodeChainSat = 16;
constexpr int kNodeChainHue = 17;   // degrees
constexpr int kNodeChainTemp = 18;
constexpr int kNodeChainTint = 19;
constexpr int kNodeChainLogShadow = 20;  // x, y, master
constexpr int kNodeChainLogMid = 23;     // x, y, master
constexpr int kNodeChainLogHi = 26;      // x, y, master
constexpr int kNodeChainLogLow = 29;
constexpr int kNodeChainLogHigh = 30;
constexpr int kNodeChainHasCurves = 31;
constexpr int kNodeChainHasLut = 32;
constexpr int kNodeChainLutMix = 33;
constexpr int kNodeChainLutHandle = 34;  // NodeLutRegistry handle (0 = none); meaningful when hasLut == 1
// The output block's slots (add header + n*stride + curve block).
constexpr int kNodeChainOutLook = 0;
constexpr int kNodeChainOutTonescale = 1;
constexpr int kNodeChainOutCwp = 2;
constexpr int kNodeChainOutCwpLimit = 3;
constexpr int kNodeChainOutGrey = 4;
constexpr int kNodeChainOutDisplay = 5;

// The decoded chain: rawedit::GradeNode per record (the knobs core
// compiles), the curve control points (nodeCurveTable's Fritsch-Carlson
// input), the output mode, and the OpenDrtSpec's user parameters.
struct NodeChainDecoded {
    int nodeCount = 0;
    int outputMode = 0;  // 0 legacy TAIL; 1 DwgLinear (TAIL's DRT variant)
    std::vector<rawedit::GradeNode> nodes;
    // The OpenDRT output spec (raw only when outputMode == 1).
    int drtLook = 0, drtTonescale = 0, drtCwp = 0;
    float drtCwpLimit = 0.25f, drtGrey = 10.0f;
    int drtDisplay = 0;  // OpenDrtDisplay ordinal (0 sRGB 2.2 / 1 Rec.1886 / 2 HLG)
    std::string error;   // non-empty = refused (decodeNodeChainWire)
};

inline bool decodeNodeChainWire(const float* w, int length, NodeChainDecoded* d) {
    d->error.clear();
    const auto refuse = [&](const std::string& msg) {
        d->error = msg;
        return false;
    };
    if (length < kNodeChainWireHeader + kNodeChainWireOutput) {
        char buf[96];
        std::snprintf(buf, sizeof(buf), "node chain wire: too short (%d, min %d)",
                      length, kNodeChainWireHeader + kNodeChainWireOutput);
        return refuse(buf);
    }
    for (int i = 0; i < length; ++i) {
        if (!std::isfinite(w[i])) {
            char buf[96];
            std::snprintf(buf, sizeof(buf),
                          "node chain wire: slot %d is not finite", i);
            return refuse(buf);
        }
    }
    const int n = static_cast<int>(w[0]);
    if (n < 0 || n > kNodeChainWireMaxNodes) {
        char buf[96];
        std::snprintf(buf, sizeof(buf),
                     "node chain wire: nodeCount %d outside 0..%d", n,
                     kNodeChainWireMaxNodes);
        return refuse(buf);
    }
    const int outputMode = static_cast<int>(w[1]);
    if (outputMode != 0 && outputMode != 1) {
        char buf[96];
        std::snprintf(buf, sizeof(buf),
                     "node chain wire: outputMode %d is not 0 or 1", outputMode);
        return refuse(buf);
    }
    // Walk the fixed records, then the curve block; every walk validates
    // its own bounds so the length agreement is proven, not assumed.
    const float* rec = w + kNodeChainWireHeader;
    int pairsSeen = 0;
    std::vector<rawedit::GradeNode> nodes(static_cast<size_t>(n));
    for (int i = 0; i < n; ++i) {
        const float* r = rec + i * kNodeChainWireStride;
        const auto checkBool = [&](int slot, const char* name) {
            if (r[slot] != 0.0f && r[slot] != 1.0f) {
                char buf[96];
                std::snprintf(buf, sizeof(buf),
                              "node chain wire: node %d %s must be 0.0 or 1.0 (got %g)",
                              i, name, static_cast<double>(r[slot]));
                refuse(buf);
                return false;
            }
            return true;
        };
        if (!checkBool(kNodeChainEnabled, "enabled")) return false;
        if (!checkBool(kNodeChainHasCurves, "hasCurves")) return false;
        if (!checkBool(kNodeChainHasLut, "hasLut")) return false;
        rawedit::GradeNode& g = nodes[static_cast<size_t>(i)];
        g.enabled = r[kNodeChainEnabled] == 1.0f;
        g.mix = r[kNodeChainMix];
        g.lift = rawedit::NodeWheel{r[kNodeChainLift], r[kNodeChainLift + 1],
                                    r[kNodeChainLift + 2]};
        g.gamma = rawedit::NodeWheel{r[kNodeChainGamma], r[kNodeChainGamma + 1],
                                     r[kNodeChainGamma + 2]};
        g.gain = rawedit::NodeWheel{r[kNodeChainGain], r[kNodeChainGain + 1],
                                    r[kNodeChainGain + 2]};
        g.offset = rawedit::NodeWheel{r[kNodeChainOffset],
                                      r[kNodeChainOffset + 1],
                                      r[kNodeChainOffset + 2]};
        g.contrast = r[kNodeChainContrast];
        g.pivot = r[kNodeChainPivot];
        g.sat = r[kNodeChainSat];
        g.hue = r[kNodeChainHue];
        g.temp = r[kNodeChainTemp];
        g.tint = r[kNodeChainTint];
        g.logShadow = rawedit::NodeWheel{r[kNodeChainLogShadow],
                                        r[kNodeChainLogShadow + 1],
                                        r[kNodeChainLogShadow + 2]};
        g.logMid = rawedit::NodeWheel{r[kNodeChainLogMid], r[kNodeChainLogMid + 1],
                                     r[kNodeChainLogMid + 2]};
        g.logHi = rawedit::NodeWheel{r[kNodeChainLogHi], r[kNodeChainLogHi + 1],
                                     r[kNodeChainLogHi + 2]};
        g.logLow = r[kNodeChainLogLow];
        g.logHigh = r[kNodeChainLogHigh];
        g.lutMix = r[kNodeChainLutMix];
        g.lut = nullptr;
        if (r[kNodeChainHasLut] == 1.0f) {
            // F1-8d: the LUT rides as a registry handle; an unknown handle is a refusal, never a silent drop.
            const int handle = static_cast<int>(r[kNodeChainLutHandle]);
            g.lut = NodeLutRegistry::get(handle);
            if (g.lut == nullptr) {
                char buf[112];
                std::snprintf(buf, sizeof(buf),
                              "node chain wire: node %d names LUT handle %d which is not registered", i, handle);
                return refuse(buf);
            }
        }
    }
    // The curve block: per hasCurves node, [channelMask, per-PRESENT
    // channel in Y,R,G,B order: count, pairs...]. The mask is the bit set
    // of the non-identity channels (Y=1, R=2, G=4, B=8) — the encoder
    // skips identity channels, and a skipped channel stays the
    // NodeCurve::isIdentity default here.
    const float* cur = rec + n * kNodeChainWireStride;
    const float* end = w + length - kNodeChainWireOutput;
    const auto readChannel = [&](rawedit::NodeCurve* c) {
        if (cur >= end) {
            refuse("node chain wire: the curve block ended mid-channel");
            return false;
        }
        const int cnt = static_cast<int>(*cur++);
        if (cnt < 2 || cnt > 64) {
            char buf[96];
            std::snprintf(buf, sizeof(buf),
                          "node chain wire: curve point count %d outside 2..64", cnt);
            refuse(buf);
            return false;
        }
        if (cur + cnt * 2 > end) {
            refuse("node chain wire: the curve block ended mid-points");
            return false;
        }
        c->points.assign(static_cast<size_t>(cnt), {0.0f, 0.0f});
        for (int k = 0; k < cnt; ++k) {
            c->points[static_cast<size_t>(k)] = {cur[k * 2], cur[k * 2 + 1]};
        }
        cur += cnt * 2;
        pairsSeen += cnt;
        return true;
    };
    for (int i = 0; i < n; ++i) {
        const float* r = rec + i * kNodeChainWireStride;
        if (r[kNodeChainHasCurves] != 1.0f) continue;
        rawedit::GradeNode& g = nodes[static_cast<size_t>(i)];
        if (cur >= end) {
            refuse("node chain wire: the curve block ended before the mask");
            return false;
        }
        const int mask = static_cast<int>(*cur++);
        if (mask < 1 || mask > 15) {
            char buf[96];
            std::snprintf(buf, sizeof(buf),
                          "node chain wire: node %d curve mask %d outside 1..15", i,
                          mask);
            return refuse(buf);
        }
        rawedit::NodeCurve* chans[4] = {&g.curveY, &g.curveR, &g.curveG, &g.curveB};
        for (int b = 0; b < 4; ++b) {
            if ((mask & (1 << b)) == 0) continue;
            if (!readChannel(chans[b])) return false;
        }
    }
    if (cur != end) {
        char buf[96];
        std::snprintf(buf, sizeof(buf),
                     "node chain wire: %d float(s) left before the output block",
                     static_cast<int>(end - cur));
        return refuse(buf);
    }
    const int pairTotal = static_cast<int>(w[2]);
    if (pairsSeen != pairTotal) {
        char buf[128];
        std::snprintf(buf, sizeof(buf),
                      "node chain wire: curve pair count %d disagrees with the header's %d",
                      pairsSeen, pairTotal);
        return refuse(buf);
    }
    const float* o = w + length - kNodeChainWireOutput;
    d->drtLook = static_cast<int>(o[kNodeChainOutLook]);
    d->drtTonescale = static_cast<int>(o[kNodeChainOutTonescale]);
    d->drtCwp = static_cast<int>(o[kNodeChainOutCwp]);
    d->drtCwpLimit = o[kNodeChainOutCwpLimit];
    d->drtGrey = o[kNodeChainOutGrey];
    d->drtDisplay = static_cast<int>(o[kNodeChainOutDisplay]);
    d->nodeCount = n;
    d->outputMode = outputMode;
    d->nodes = std::move(nodes);
    return true;
}

}  // namespace gles
