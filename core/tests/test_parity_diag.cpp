// ParityDiag tests (owner request 2026-11-23, guide A5 step 1: the 4:3
// clip trips the fragment parity gate on a 0.059% population of 1-LSB
// mismatches at maxDiffLsb = 1, and the owner needs to know whether that
// population is rounding noise or a real stage difference BEFORE any
// rule decision). The diagnosis header is pure (no GL), so the bucket
// edges, the no-op guard, the conservation invariant and the exact
// logcat line shapes run on desktop the way test_chain_slots runs the
// chain planner's — the GL harness only FEEDS it.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include "../../app/src/main/cpp/gles/ParityDiag.h"

using namespace gles;

namespace {
// Every counter of a fresh ParityDiag is zero (the harness's ran=false
// early returns depend on it).
bool allZero(const ParityDiag& d) {
    if (d.mismatchTotal != 0) return false;
    if (d.byMagnitude[0] != 0 || d.byMagnitude[1] != 0) return false;
    if (d.byChannel[0] != 0 || d.byChannel[1] != 0 || d.byChannel[2] != 0)
        return false;
    for (int i = 0; i < kValueBuckets; ++i)
        if (d.byValueBucket[i] != 0) return false;
    for (int i = 0; i < kBoundaryBuckets; ++i)
        if (d.byBoundaryBucket[i] != 0) return false;
    return true;
}
}  // namespace

// ---- the tonal value axis: the CPU uint16 reference's position -------

TEST_CASE("kValueBuckets is the 11-slot axis: black + 9 ranges + white") {
    CHECK(kValueBuckets == 11);
    CHECK(kBoundaryBuckets == 4);
}

TEST_CASE("valueBucketOf pins the exact edges of every tonal range") {
    CHECK(valueBucketOf(0) == 0);         // black is exactly 0
    CHECK(valueBucketOf(1) == 1);
    CHECK(valueBucketOf(255) == 1);       // last v below 1/256
    CHECK(valueBucketOf(256) == 2);       // first v at 1/256
    CHECK(valueBucketOf(511) == 2);       // last v below 1/128
    CHECK(valueBucketOf(512) == 3);
    CHECK(valueBucketOf(1023) == 3);      // last v below 1/64
    CHECK(valueBucketOf(1024) == 4);
    CHECK(valueBucketOf(2047) == 4);      // last v below 1/32
    CHECK(valueBucketOf(2048) == 5);
    CHECK(valueBucketOf(4095) == 5);      // last v below 1/16
    CHECK(valueBucketOf(4096) == 6);
    CHECK(valueBucketOf(8191) == 6);      // last v below 1/8
    CHECK(valueBucketOf(8192) == 7);
    CHECK(valueBucketOf(16383) == 7);     // last v below 1/4
    CHECK(valueBucketOf(16384) == 8);
    CHECK(valueBucketOf(32767) == 8);     // last v below 1/2
    CHECK(valueBucketOf(32768) == 9);     // first v at 1/2
    CHECK(valueBucketOf(65534) == 9);     // NOT white: white is exactly 65535
    CHECK(valueBucketOf(65535) == 10);
}

// ---- the boundary axis: the GPU pre-quantize float's distance --------
// to the x.5 line lround rounds at. d = |frac(q) - 0.5|, so a value ON
// n+0.5 sits at d = 0 (bucket 0) and a value ON an integer at d = 0.5
// (the rest bucket). Do NOT invert this.

TEST_CASE("boundaryBucketOf: on the boundary vs far from it") {
    CHECK(boundaryBucketOf(7.5f) == 0);      // exactly on n+0.5: d = 0
    CHECK(boundaryBucketOf(0.5f) == 0);
    CHECK(boundaryBucketOf(100.5f) == 0);
    CHECK(boundaryBucketOf(65534.5f) == 0);
    CHECK(boundaryBucketOf(0.0f) == 3);      // on an integer: d = 0.5
    CHECK(boundaryBucketOf(8.0f) == 3);
    CHECK(boundaryBucketOf(65535.0f) == 3);
    CHECK(boundaryBucketOf(0.25f) == 3);     // d = 0.25, the middle of "rest"
}

TEST_CASE("boundaryBucketOf pins each threshold, both sides") {
    // d ~ 9e-5 — the tightest point INSIDE the <1e-4 bucket.
    CHECK(boundaryBucketOf(3.50009f) == 0);
    // d ~ 1.5e-4 — just across the 1e-4 edge into <1e-3.
    CHECK(boundaryBucketOf(3.50015f) == 1);
    CHECK(boundaryBucketOf(3.5005f) == 1);   // d ~ 5e-4
    // d ~ 9e-4 — the tightest point INSIDE the <1e-3 bucket.
    CHECK(boundaryBucketOf(3.5009f) == 1);
    // Across the 1e-3 edge: d ~ 5e-3 is <1e-2 but NOT <1e-3.
    CHECK(boundaryBucketOf(3.505f) == 2);
    // d ~ 9e-3 — the tightest point INSIDE the <1e-2 bucket.
    CHECK(boundaryBucketOf(3.509f) == 2);
    // Across the 1e-2 edge: d ~ 0.05 lands in the rest bucket.
    CHECK(boundaryBucketOf(3.55f) == 3);
}

// ---- recording --------------------------------------------------------

TEST_CASE("recordParityDiagSample: diff <= 0 is a no-op") {
    ParityDiag d;
    recordParityDiagSample(d, 0, 0, 7.5f, 100);    // a match
    recordParityDiagSample(d, -1, 1, 8.25f, 300);  // never expected, guarded
    CHECK(allZero(d));
}

TEST_CASE("recordParityDiagSample: one sample increments exactly one bucket per dimension") {
    ParityDiag d;
    // ref 100 -> tonal bucket 1 (<1/256); preQuant 7.5 -> ON the
    // boundary (bucket 0); 1-LSB; channel G.
    recordParityDiagSample(d, 1, 1, 7.5f, 100);
    CHECK(d.mismatchTotal == 1);
    CHECK(d.byMagnitude[0] == 1);
    CHECK(d.byMagnitude[1] == 0);
    CHECK(d.byChannel[0] == 0);
    CHECK(d.byChannel[1] == 1);
    CHECK(d.byChannel[2] == 0);
    for (int i = 0; i < kValueBuckets; ++i)
        CHECK(d.byValueBucket[i] == (i == 1 ? 1 : 0));
    for (int i = 0; i < kBoundaryBuckets; ++i)
        CHECK(d.byBoundaryBucket[i] == (i == 0 ? 1 : 0));
}

TEST_CASE("conservation: every dimension sums to mismatchTotal over a mixed sequence") {
    ParityDiag d;
    // A spread across magnitudes (1, 2, 3), all three channels, tonal
    // buckets from black to white, and boundary distances from
    // on-the-line to far — in a deliberately uneven order.
    recordParityDiagSample(d, 1, 0, 7.5f, 0);        // black, on-boundary
    recordParityDiagSample(d, 2, 1, 3.55f, 65535);   // white, far
    recordParityDiagSample(d, 1, 2, 8.5005f, 300);   // 1/256..1/128, d~5e-4
    recordParityDiagSample(d, 2, 0, 20.25f, 32768);  // 1/2..<1, d=0.25
    recordParityDiagSample(d, 1, 1, 0.5f, 4096);     // 1/16..1/8, on-boundary
    recordParityDiagSample(d, 3, 2, 100.5f, 65534);  // 1/2..<1, on-boundary
    REQUIRE(d.mismatchTotal == 6);

    long long sumMag = d.byMagnitude[0] + d.byMagnitude[1];
    long long sumCh = d.byChannel[0] + d.byChannel[1] + d.byChannel[2];
    long long sumVal = 0;
    long long sumBnd = 0;
    for (int i = 0; i < kValueBuckets; ++i) sumVal += d.byValueBucket[i];
    for (int i = 0; i < kBoundaryBuckets; ++i) sumBnd += d.byBoundaryBucket[i];
    CHECK(sumMag == d.mismatchTotal);
    CHECK(sumCh == d.mismatchTotal);
    CHECK(sumVal == d.mismatchTotal);
    CHECK(sumBnd == d.mismatchTotal);

    // The per-dimension tallies of the six samples above:
    CHECK(d.byMagnitude[0] == 3);  // diffs 1, 1, 1
    CHECK(d.byMagnitude[1] == 3);  // diffs 2, 2, 3
    CHECK(d.byChannel[0] == 2);
    CHECK(d.byChannel[1] == 2);
    CHECK(d.byChannel[2] == 2);
    CHECK(d.byValueBucket[0] == 1);   // ref 0 (black)
    CHECK(d.byValueBucket[2] == 1);   // ref 300
    CHECK(d.byValueBucket[6] == 1);   // ref 4096
    CHECK(d.byValueBucket[9] == 2);   // refs 32768, 65534
    CHECK(d.byValueBucket[10] == 1);  // ref 65535 (white)
    CHECK(d.byBoundaryBucket[0] == 3);  // 7.5, 0.5, 100.5: on the boundary
    CHECK(d.byBoundaryBucket[1] == 1);  // 8.5005f: d ~ 5e-4
    CHECK(d.byBoundaryBucket[2] == 0);
    CHECK(d.byBoundaryBucket[3] == 2);  // 3.55f (d~0.05), 20.25f (d=0.25)
}

// ---- the pinned logcat line shapes (the owner reads them from adb) --

TEST_CASE("diagHeaderLine is the owner-check logcat shape") {
    CHECK(diagHeaderLine(true, 1, 7398LL, 12582912LL) ==
          "fragment parity diag: ran=1 maxDiffLsb=1 mismatch=7398 samples=12582912");
    CHECK(diagHeaderLine(false, 0, 0LL, 0LL) ==
          "fragment parity diag: ran=0 maxDiffLsb=0 mismatch=0 samples=0");
}

TEST_CASE("formatParityDiag: the three histogram lines, literally") {
    ParityDiag d;
    recordParityDiagSample(d, 1, 0, 7.5f, 100);     // <1/256, on-boundary
    recordParityDiagSample(d, 1, 1, 8.0001f, 300);  // 1/256..1/128, far
    recordParityDiagSample(d, 2, 2, 20.25f, 65535);  // white, d=0.25
    CHECK(d.mismatchTotal == 3);
    CHECK(formatParityDiag(d) ==
        "diag value: black=0 <1/256=1 1/256=1 1/128=0 1/64=0 1/32=0 1/16=0 "
        "1/8=0 1/4=0 1/2=0 white=1\n"
        "diag boundary: <1e-4=1 <1e-3=0 <1e-2=0 rest=2\n"
        "diag mag/ch: d1=2 d2+=1 R=1 G=1 B=1");
}

TEST_CASE("formatParityDiag: the all-zero diag reads zero everywhere") {
    const ParityDiag d;
    CHECK(formatParityDiag(d) ==
        "diag value: black=0 <1/256=0 1/256=0 1/128=0 1/64=0 1/32=0 1/16=0 "
        "1/8=0 1/4=0 1/2=0 white=0\n"
        "diag boundary: <1e-4=0 <1e-3=0 <1e-2=0 rest=0\n"
        "diag mag/ch: d1=0 d2+=0 R=0 G=0 B=0");
}
