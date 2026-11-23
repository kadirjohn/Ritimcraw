// ParityDiag.h — the A5 parity-diagnosis accumulators (pure, no GL).
//
// 2026-11-23 owner request (guide A5 step 1): the 4:3 clip (4096x3072,
// 12582912 samples) trips the fragment develop-parity gate on a
// mismatch population of 7398 samples at maxDiffLsb = 1 — 0.059% of the
// frame, above the rule's 0.01% — and every mismatch is a single LSB.
// The A5 question: are those LSBs toByte-style ROUNDING NOISE (the GPU
// and CPU floats straddling the x.5 quantize line lround rounds at,
// driver-op rounding differences of ~1 ulp) or a REAL stage difference
// (a wrong neighbor set, a wrong clamp order)? The owner decides with
// on-device evidence; THIS header only shapes that evidence. It is
// diagnosis, NOT a rule change: GpuParity.kt's verdict constants and
// the ClipEditorScreen gate stay untouched (guide A5 step 2 is the
// owner's).
//
// One histogram, four dimensions, each answering one half of the A5
// question:
//
//   byMagnitude    diff == 1 vs diff >= 2 — noise is 1-LSB only; any
//                  2+ population is structural by definition.
//   byChannel      R/G/B — a wrong WB gain or a channel-order swap
//                  concentrates in ONE channel; noise is even.
//   byValueBucket  the CPU uint16 REFERENCE's tonal position — deep
//                  shadow (the pow/log2 stage-1 regions), midtones,
//                  highlights. A real stage difference lives where
//                  its math lives (e.g. log2 shadows, pow highlights);
//                  rounding noise is tonal-agnostic.
//   byBoundaryBucket  how far the GPU pre-quantize float sits from
//                  the x.5 line lround rounds at: d = |frac(q) - 0.5|
//                  so ON the boundary is d = 0 (bucket 0) and ON an
//                  integer is d = 0.5 (the rest bucket, FARTHEST from
//                  the boundary — do not invert this). All-noise runs
//                  pile into the first buckets; a structural
//                  difference fires wherever the formula deviates,
//                  boundary distance be damned.
//
// The header is deliberately GL-free (only <cstdint>/<cmath>/<string>):
// the ChainSlots.h rule — the desktop doctest (core/tests/
// test_parity_diag.cpp) compiles it with no NDK, and the Android-only
// GlesPipeline readback loop and jni_bridge door merely FEED it. No
// verdict, no threshold: raw counts, formatted for logcat.
#pragma once

#include <cstdint>
#include <cmath>
#include <string>

namespace gles {

// The tonal axis: black + 9 power-of-two ranges + white = 11 slots.
constexpr int kValueBuckets = 11;

// The boundary-distance axis: <1e-4, <1e-3, <1e-2, else.
constexpr int kBoundaryBuckets = 4;

// The mismatch-population histogram. All counters zero-initialize so
// every ran=false early return (and a perfect 0-mismatch run) reports
// zeros without any extra wiring in the harness.
struct ParityDiag {
    long long mismatchTotal = 0;
    long long byMagnitude[2] = {0, 0};       // [0] diff == 1, [1] diff >= 2
    long long byChannel[3] = {0, 0, 0};     // R, G, B
    long long byValueBucket[kValueBuckets] = {0};
    long long byBoundaryBucket[kBoundaryBuckets] = {0};
};

// The CPU uint16 reference's tonal bucket (u = v / 65535.0):
//   0  v == 0            black
//   1  0 < u < 1/256     the deepest shadows (the clamp floor region)
//   2  [1/256, 1/128)
//   3  [1/128, 1/64)
//   4  [1/64, 1/32)
//   5  [1/32, 1/16)
//   6  [1/16, 1/8)
//   7  [1/8, 1/4)
//   8  [1/4, 1/2)
//   9  [1/2, <1)         highlights below the clip
//   10 v == 65535         white (the clamp ceiling region)
// Each edge lands in the bucket the range's LEFT side names (v = 256 is
// bucket 2, the first u >= 1/256 sample).
inline int valueBucketOf(uint16_t v) {
    if (v == 0) return 0;
    if (v == 65535) return 10;
    // The float-free form below stays exact: every edge is a power of
    // two times 256, so the shifts cannot round. u = v/65535 with v in
    // [1, 65534] means: bucket 1 iff v < 256, else bucket 2 + the
    // index of the highest set bit of v/256 (256..511 -> 1<<8 -> 0,
    // 512..1023 -> 1<<9 -> 1, ..., 32768..65534 -> 1<<15 -> 7; plus
    // the 2 = 8 = the [1/2,1) slot). One table would also work; the
    // arithmetic documents the ranges.
    if (v < 256) return 1;
    int b = 2;
    unsigned int x = v >> 8;  // 1..254 after the guards above
    while (x > 1) {
        x >>= 1;
        ++b;
    }
    return b;  // v >= 32768 -> 9
}

// The GPU pre-quantize float's distance to the x.5 quantize boundary
// lround rounds at: q is the CLAMPED gpu value x 65535 BEFORE lround
// (the exact expression the readback feeds lround —bucketing the CPU
// reference here, or the GPU value into valueBucketOf, silently breaks
// the interpretation). d = |frac(q) - 0.5|: a q exactly ON n+0.5 has
// d = 0 (bucket 0 — the half-away-from-zero rule flips on these); a q
// exactly ON an integer has d = 0.5 (the rest bucket — the rounding
// boundary cannot produce a mismatch there).
inline int boundaryBucketOf(float preQuant) {
    float frac = std::fabs(std::fmod(preQuant, 1.0f));
    const float d = std::fabs(frac - 0.5f);
    if (d < 1e-4f) return 0;
    if (d < 1e-3f) return 1;
    if (d < 1e-2f) return 2;
    return 3;
}

// One mismatching sample (diff > 0 — the harness already computed it
// against the uint16 reference). diff <= 0 is a no-op (the equal case
// never reaches the histogram). channel is 0/1/2 = R/G/B — the
// STORE_FRAGMENT row-major rgb packing makes readback index i carry
// channel i % 3. gpuPreQuant is the clamped GPU value x 65535 BEFORE
// lround; refValue is the CPU uint16 reference. Exactly one bucket per
// dimension increments, so all four dimensions plus the total stay
// conserved over any sample sequence.
inline void recordParityDiagSample(ParityDiag& d, int diff, int channel,
                                   float gpuPreQuant, uint16_t refValue) {
    if (diff <= 0) return;
    ++d.mismatchTotal;
    ++d.byMagnitude[diff == 1 ? 0 : 1];
    if (channel < 0 || channel > 2) return;  // never expected; the contract stays countable
    ++d.byChannel[channel];
    const int vb = valueBucketOf(refValue);
    if (vb >= 0 && vb < kValueBuckets) ++d.byValueBucket[vb];
    const int bb = boundaryBucketOf(gpuPreQuant);
    if (bb >= 0 && bb < kBoundaryBuckets) ++d.byBoundaryBucket[bb];
}

// The block's first line (the diagnosis door's header; the numbers are
// the same four the plain parity array reports — the shape matches
// GpuParityRules.fragmentParityLine's with 'diag' naming the leg):
//   fragment parity diag: ran=1 maxDiffLsb=1 mismatch=7398 samples=12582912
// Pinned by the doctest; the owner greps it with the block's other
// lines (adb logcat -d -s ritim-pm).
inline std::string diagHeaderLine(bool ran, int maxDiffLsb,
                                  long long mismatchCount, long long samples) {
    using std::to_string;  // long long picks the ll overload; std:: explicit
    return std::string("fragment parity diag: ran=") + (ran ? "1" : "0") +
           " maxDiffLsb=" + to_string(maxDiffLsb) +
           " mismatch=" + to_string(mismatchCount) +
           " samples=" + to_string(samples);
}

// The three histogram lines, newline-separated, trailing newline
// trimmed (the Measure block appends its own line separator). Shapes
// pinned by the doctest — the owner-check contract:
//   diag value: black=0 <1/256=12 ... 1/2=1209 white=8
//   diag boundary: <1e-4=5200 <1e-3=1900 <1e-2=280 rest=18
//   diag mag/ch: d1=7393 d2+=5 R=2411 G=2489 B=2498
// Reading it is guide A5 step 2's input: all-noise = the first two
// boundary buckets carry the population, channels even, no single
// tonal bucket dominating; any other shape names a real stage
// difference (compare the Step 0 grade sweep before touching anything).
inline std::string formatParityDiag(const ParityDiag& d) {
    using std::to_string;
    std::string s("diag value: black=");
    s += to_string(d.byValueBucket[0]);
    s += " <1/256=" + to_string(d.byValueBucket[1]);
    s += " 1/256=" + to_string(d.byValueBucket[2]);
    s += " 1/128=" + to_string(d.byValueBucket[3]);
    s += " 1/64=" + to_string(d.byValueBucket[4]);
    s += " 1/32=" + to_string(d.byValueBucket[5]);
    s += " 1/16=" + to_string(d.byValueBucket[6]);
    s += " 1/8=" + to_string(d.byValueBucket[7]);
    s += " 1/4=" + to_string(d.byValueBucket[8]);
    s += " 1/2=" + to_string(d.byValueBucket[9]);
    s += " white=" + to_string(d.byValueBucket[10]);
    s += "\ndiag boundary: <1e-4=" + to_string(d.byBoundaryBucket[0]);
    s += " <1e-3=" + to_string(d.byBoundaryBucket[1]);
    s += " <1e-2=" + to_string(d.byBoundaryBucket[2]);
    s += " rest=" + to_string(d.byBoundaryBucket[3]);
    s += "\ndiag mag/ch: d1=" + to_string(d.byMagnitude[0]);
    s += " d2+=" + to_string(d.byMagnitude[1]);
    s += " R=" + to_string(d.byChannel[0]);
    s += " G=" + to_string(d.byChannel[1]);
    s += " B=" + to_string(d.byChannel[2]);
    return s;
}

}  // namespace gles
