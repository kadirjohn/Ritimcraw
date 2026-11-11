// Stabilization Phase 4 tests — tier 1: the orientation primitives.
//
// CONTRACTS PINNED HERE (hand-derived; Stabilization.h documents them —
// these doctests are the executable proof):
//   - smoothOrientationSeries: strength 0 is EXACTLY the raw series
//     (tau = 1 ns makes alpha = 1 - exp(-dt/1ns) = 1.0 to double
//     precision on every real grid — both passes copy through); the
//     strength clamps to [0, 1]; empty/one-sample/dt=0 edges stay
//     total; strength 1 measurably damps a 1 Hz handheld wobble; the
//     forward+reverse pair is time-symmetric, so a symmetric sweep
//     keeps its apex at the same sample (zero group delay).
//   - relativeRotation: q_rel = conj(q_target) * q_actual maps
//     ACTUAL-camera vectors into the TARGET frame, checked functionally
//     against the Phase 2 quatRotate primitive; unnormalized inputs
//     normalize; the angle helper clamps.
//   - horizonLockRotation: ground truth R(conj(q_lock)) * g ==
//     (0, -|g.xy|, g.z) for every roll (the lock undoes exactly the
//     roll atan2(g.x, -g.y)); refusal (identity + false) whenever
//     |g.xy| < 0.1 — never a fabricated lock.
//   - frameTransform: identity q_rel -> the BIT-EXACT identity H for
//     binary-exact intrinsics; q_rel = Rz(+θ) -> the hand-derived pure
//     2D block [[c, s], [-s, c]] about the principal point with row 3
//     EXACTLY (0, 0, 1); q_rel = Rx(-θ) (pitch down) -> center pixel
//     at (cx, cy + f·tanθ) BELOW center, h[6] exactly 0, h[7] < 0;
//     q_rel = Ry(-ψ) (pan right) -> center at (cx + f·tanψ, cy),
//     h[7] exactly 0, h[6] < 0; the width/height overload is
//     bit-identical to the centered call; f = 0 -> identity fallback.
//   - focalLengthPxFromEquivalent: width·mm/36 bit-exact; non-positive
//     inputs -> 0 (unknown, never a guess).
//   - staticCropZoom: empty/identity/degenerate -> exactly 1.0; the
//     numeric bisection reproduces the roll closed form
//     Z(θ) = max((w|sinθ|+h|cosθ|)/h, (w|cosθ|+h|sinθ|)/w) at
//     5/10/20/45 deg and takes the max over frames; at 8 deg pitch
//     costs strictly more zoom than roll (perspective stretch on top
//     of the f·tanθ shift).
//
// Tier 3 (next commit) appends the clip-level API + real-fixture
// evidence.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Stabilization.h>
#include <rawedit/OrientationEstimate.h>

#include <algorithm>
#include <cmath>
#include <vector>

using namespace rawedit;

namespace {

constexpr double kPi = 3.14159265358979323846;

double deg2rad(double d) { return d * kPi / 180.0; }

// Unit quaternion of a rotation about canonical axis 0=x / 1=y / 2=z.
void quatAxisDeg(int axis, double angleDeg, double out[4]) {
    const double h = 0.5 * deg2rad(angleDeg);
    const double s = std::sin(h);
    out[0] = std::cos(h);
    out[1] = axis == 0 ? s : 0.0;
    out[2] = axis == 1 ? s : 0.0;
    out[3] = axis == 2 ? s : 0.0;
}

double quatAngleDeg(const double q[4]) {
    double w = std::fabs(q[0]);
    if (w > 1.0)
        w = 1.0;
    return 2.0 * std::acos(w) * 180.0 / kPi;
}

// Shortest-arc angle between two quaternions, degrees.
double angleBetweenDeg(const double a[4], const double b[4]) {
    double d = quatDot(a, b);
    if (d < 0.0)
        d = -d;
    if (d > 1.0)
        d = 1.0;
    return 2.0 * std::acos(d) * 180.0 / kPi;
}

// Rotations about one axis on a uniform grid, as an OrientationSeries.
OrientationSeries seriesFromAngles(const std::vector<double>& anglesDeg,
                                   int64_t t0, int64_t dtNs, int axis) {
    OrientationSeries s;
    s.timestampsNs.reserve(anglesDeg.size());
    s.quats.reserve(4 * anglesDeg.size());
    for (size_t i = 0; i < anglesDeg.size(); ++i) {
        s.timestampsNs.push_back(t0 + int64_t(i) * dtNs);
        double q[4];
        quatAxisDeg(axis, anglesDeg[i], q);
        for (int k = 0; k < 4; ++k)
            s.quats.push_back(q[k]);
    }
    return s;
}

// Applies a 3x3 row-major homography to a pixel (perspective divide).
void applyH(const double h[9], double x, double y, double& ox, double& oy) {
    const double u = h[0] * x + h[1] * y + h[2];
    const double v = h[3] * x + h[4] * y + h[5];
    const double w = h[6] * x + h[7] * y + h[8];
    REQUIRE(std::fabs(w) > 1e-12);
    ox = u / w;
    oy = v / w;
}

// The 4-component quaternion as the flat vector the crop API takes.
std::vector<double> quatVec(const double q[4]) {
    return {q[0], q[1], q[2], q[3]};
}

} // namespace

// ---------------------------------------------------------------------------
// Smoothing (deliverable 1)
// ---------------------------------------------------------------------------

TEST_CASE("smoothing: strength 0 is exactly the raw series") {
    // tau(0) = 1 ns: alpha = 1 - exp(-dt/1ns) = 1.0 EXACTLY in double
    // for every real dt (exp underflows to +0 below ~-745), so both
    // passes copy the input through — the smoothed series IS the raw
    // series up to the final re-normalization (last-bit level).
    const std::vector<double> angles = {0, 5, -3, 12, -8, 2, 0, -1, 7, -4};
    const OrientationSeries raw = seriesFromAngles(angles, 1000, 2114165, 2);
    const OrientationSeries out = smoothOrientationSeries(raw, 0.0);
    REQUIRE(out.size() == raw.size());
    CHECK(out.timestampsNs == raw.timestampsNs);
    double worst = 0.0;
    for (size_t i = 0; i < out.size(); ++i)
        worst = std::fmax(worst,
            angleBetweenDeg(&out.quats[4 * i], &raw.quats[4 * i]));
    CHECK(worst < 1e-9); // no smoothing happened at all
    for (size_t i = 0; i < out.size(); ++i)
        for (int k = 0; k < 4; ++k)
            CHECK(std::fabs(out.quats[4 * i + k] - raw.quats[4 * i + k]) < 1e-12);
}

TEST_CASE("smoothing: strength clamps and the edges stay total") {
    // Empty in -> empty out.
    CHECK(smoothOrientationSeries(OrientationSeries{}, 0.7).empty());

    // One sample -> unchanged.
    const OrientationSeries one = seriesFromAngles({37.0}, 5, 10, 1);
    const OrientationSeries oneOut = smoothOrientationSeries(one, 0.9);
    REQUIRE(oneOut.size() == 1);
    CHECK(angleBetweenDeg(&oneOut.quats[0], &one.quats[0]) < 1e-12);

    // Out-of-range strengths clamp to [0, 1] (identical results).
    const OrientationSeries raw = seriesFromAngles({0, 10, -10, 5}, 0, 1000, 0);
    const OrientationSeries neg = smoothOrientationSeries(raw, -5.0);
    const OrientationSeries zero = smoothOrientationSeries(raw, 0.0);
    const OrientationSeries big = smoothOrientationSeries(raw, 5.0);
    const OrientationSeries oneS = smoothOrientationSeries(raw, 1.0);
    for (size_t i = 0; i < raw.size(); ++i) {
        CHECK(angleBetweenDeg(&neg.quats[4 * i], &zero.quats[4 * i]) < 1e-12);
        CHECK(angleBetweenDeg(&big.quats[4 * i], &oneS.quats[4 * i]) < 1e-12);
    }

    // Equal (non-increasing) stamps: dt = 0 is treated as a hold — the
    // output stays finite, never NaN.
    OrientationSeries flat;
    double qa[4], qb[4];
    quatAxisDeg(0, 5.0, qa);
    quatAxisDeg(1, -7.0, qb);
    for (int i = 0; i < 5; ++i) {
        flat.timestampsNs.push_back(1000);
        for (int k = 0; k < 4; ++k)
            flat.quats.push_back(i % 2 ? qb[k] : qa[k]);
    }
    const OrientationSeries flatOut = smoothOrientationSeries(flat, 0.7);
    REQUIRE(flatOut.size() == 5);
    for (double v : flatOut.quats)
        CHECK(std::isfinite(v));
}

TEST_CASE("smoothing: strength 1 damps a handheld wobble") {
    // +-10 deg roll oscillation at 1 Hz on the fixtures' 473 Hz gyro
    // grid. tau(1) = 1 s, alpha ~ 0.0021 per sample: the two-pass
    // response at 1 Hz is ~2.5% amplitude — the wobble must come out
    // far flatter but not annihilated.
    const int n = 500;
    const int64_t dt = 2114165;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i) {
        const double t = double(i) * double(dt) * 1e-9;
        angles[i] = 10.0 * std::sin(2.0 * kPi * 1.0 * t);
    }
    const OrientationSeries raw = seriesFromAngles(angles, 0, dt, 2);
    const OrientationSeries out = smoothOrientationSeries(raw, 1.0);
    REQUIRE(out.size() == n);
    // z = sin(angle/2) for a pure roll: peak-to-peak tracks amplitude.
    auto p2pZ = [](const OrientationSeries& s) {
        double lo = 1e9, hi = -1e9;
        for (size_t i = 0; i < s.size(); ++i) {
            lo = std::fmin(lo, s.quats[4 * i + 3]);
            hi = std::fmax(hi, s.quats[4 * i + 3]);
        }
        return hi - lo;
    };
    const double rawP2p = p2pZ(raw);
    const double smoothP2p = p2pZ(out);
    MESSAGE("wobble p2p: raw=" << rawP2p << " smoothed=" << smoothP2p);
    CHECK(rawP2p == doctest::Approx(2.0 * std::sin(deg2rad(5.0))).epsilon(1e-4));
    CHECK(smoothP2p < rawP2p / 4.0);
    CHECK(smoothP2p > 1e-4); // signal survives, not flattened to nothing
}

TEST_CASE("smoothing: two passes leave a symmetric sweep's apex in place") {
    // Zero group delay is a STEADY-STATE property of the forward+reverse
    // pair: with the apex far from both ends (distance >> tau) the
    // interior response is the symmetric kernel h[k] ~ (1-a)a^{|k|},
    // so a symmetric input keeps its argmax on the apex sample. (Near
    // the ends the zero-state boundary transients dominate — a short
    // sweep does NOT show the property; the pin uses a long one.) The
    // corner VALUE is honestly rounded by the zero-phase smoother
    // (apex - rate*2*tau-ish, quoted via MESSAGE); a one-pass filter
    // would additionally LAG the argmax by ~tau.
    // 16.9 s at 473 Hz, tau(1.0) = 1 s: apex 8.5 tau from each end,
    // boundary transient at the apex ~ e^-8.5 ~ 2e-4.
    const int n = 8001;
    const int half = 4000;
    const int64_t dt = 2114165;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 10.0 * double(std::min(i, n - 1 - i)) / double(half);
    const OrientationSeries raw = seriesFromAngles(angles, 0, dt, 2);
    const OrientationSeries out = smoothOrientationSeries(raw, 1.0);
    REQUIRE(out.size() == n);
    size_t apexRaw = 0, apexSmooth = 0;
    double bestRaw = -1.0, bestSmooth = -1.0;
    for (size_t i = 0; i < out.size(); ++i) {
        const double aRaw = quatAngleDeg(&raw.quats[4 * i]);
        const double aSmooth = quatAngleDeg(&out.quats[4 * i]);
        if (aRaw > bestRaw) { bestRaw = aRaw; apexRaw = i; }
        if (aSmooth > bestSmooth) { bestSmooth = aSmooth; apexSmooth = i; }
    }
    CHECK(apexRaw == half);
    CHECK(apexSmooth == half);
    CHECK(bestSmooth < bestRaw);        // the corner is rounded...
    CHECK(bestSmooth > 0.5 * bestRaw);  // ...but the trend survives
    MESSAGE("apex: raw=" << bestRaw << " deg smoothed=" << bestSmooth
            << " deg (zero-phase corner rounding)");
}

// ---------------------------------------------------------------------------
// Relative transform (deliverable 2)
// ---------------------------------------------------------------------------

TEST_CASE("relativeRotation: identity, direction contract, normalization") {
    // Same orientation -> identity.
    double qA[4], qRel[4];
    quatAxisDeg(2, 23.0, qA);
    relativeRotation(qA, qA, qRel);
    CHECK(std::fabs(qRel[0] - 1.0) < 1e-12);
    CHECK(std::fabs(qRel[1]) < 1e-12);
    CHECK(std::fabs(qRel[2]) < 1e-12);
    CHECK(std::fabs(qRel[3]) < 1e-12);

    // Direction: q_rel = conj(q_target)*q_actual is the rotation that
    // re-expresses ACTUAL-frame vectors in the TARGET frame. Functional
    // check against quatRotate: camera-frame vector -> world via
    // q_actual, world -> target via conj(q_target) must equal rotating
    // it by q_rel directly.
    double qT[4], qActual[4], qRz10[4], qRx5[4];
    quatAxisDeg(2, 10.0, qRz10);
    quatAxisDeg(0, 5.0, qRx5);
    quatMultiply(qRz10, qRx5, qActual); // Rx(5) first, then Rz(10)
    quatAxisDeg(2, 10.0, qT);
    relativeRotation(qT, qActual, qRel);
    // conj(Rz10)*(Rz10*Rx5) = Rx5 exactly.
    CHECK(angleBetweenDeg(qRel, qRx5) < 1e-9);
    const double vCam[3] = {1.0, 0.5, -0.25};
    double vWorld[3], vTarget[3], vViaRel[3], qTc[4];
    quatRotate(qActual, vCam, vWorld);
    quatConj(qT, qTc);
    quatRotate(qTc, vWorld, vTarget);
    quatRotate(qRel, vCam, vViaRel);
    for (int k = 0; k < 3; ++k)
        CHECK(std::fabs(vViaRel[k] - vTarget[k]) < 1e-12);

    // Unnormalized inputs: normalized internally, same answer.
    double bigT[4] = {3.0 * qT[0], 3.0 * qT[1], 3.0 * qT[2], 3.0 * qT[3]};
    double bigA[4] = {7.0 * qActual[0], 7.0 * qActual[1],
                      7.0 * qActual[2], 7.0 * qActual[3]};
    double qRel2[4];
    relativeRotation(bigT, bigA, qRel2);
    CHECK(angleBetweenDeg(qRel2, qRel) < 1e-12);
}

TEST_CASE("relativeRotationAngleDeg: angle, and the clamp never NaNs") {
    double ident[4] = {1.0, 0.0, 0.0, 0.0};
    CHECK(relativeRotationAngleDeg(ident) == doctest::Approx(0.0).epsilon(1e-12));
    double q90[4];
    quatAxisDeg(0, 90.0, q90);
    CHECK(relativeRotationAngleDeg(q90) == doctest::Approx(90.0).epsilon(1e-12));
    // |w| beyond 1 (unnormalized input) clamps, never NaN.
    double over[4] = {1.5, 0.0, 0.0, 0.0};
    CHECK(relativeRotationAngleDeg(over) == doctest::Approx(0.0).epsilon(1e-12));
    double neg[4] = {-2.0, 0.0, 0.0, 0.0};
    CHECK(std::isfinite(relativeRotationAngleDeg(neg)));
}

// ---------------------------------------------------------------------------
// Horizon lock (deliverable 1's option)
// ---------------------------------------------------------------------------

TEST_CASE("horizonLockRotation: ground truth over rolls") {
    // g = (sin r, -cos r, c) is gravity in a camera rolled by +r; the
    // lock must undo exactly that roll: after the lock, gravity in
    // the locked frame is (0, -|g.xy|, g.z) — level, pitch untouched.
    for (double r : {10.0, 25.0, -40.0, 170.0}) {
        const double g[3] = {std::sin(deg2rad(r)), -std::cos(deg2rad(r)), 0.3};
        double qLock[4];
        REQUIRE(horizonLockRotation(g, qLock));
        // The lock is +roll about +Z, so its angle is |roll|.
        const double roll = std::atan2(g[0], -g[1]);
        CHECK(quatAngleDeg(qLock)
              == doctest::Approx(std::fabs(roll) * 180.0 / kPi).epsilon(1e-9));
        // Ground truth: R(conj(q_lock)) g == (0, -|g.xy|, g.z).
        double qLockC[4], v[3];
        quatConj(qLock, qLockC);
        quatRotate(qLockC, g, v);
        const double xy = std::sqrt(g[0] * g[0] + g[1] * g[1]);
        CHECK(std::fabs(v[0]) < 1e-12);
        CHECK(std::fabs(v[1] + xy) < 1e-12);
        CHECK(std::fabs(v[2] - g[2]) < 1e-12);
    }
}

TEST_CASE("horizonLockRotation: refuses degenerate gravity, identity out") {
    double qLock[4];
    // Zero vector (no accel stream): refuse, identity out.
    const double zero[3] = {0.0, 0.0, 0.0};
    CHECK_FALSE(horizonLockRotation(zero, qLock));
    CHECK(qLock[0] == 1.0);
    CHECK(qLock[1] == 0.0);
    CHECK(qLock[2] == 0.0);
    CHECK(qLock[3] == 0.0);
    // Gravity along the view axis (camera pointing straight down):
    // roll is unobservable — refuse.
    const double along[3] = {0.0, 0.0, -1.0};
    CHECK_FALSE(horizonLockRotation(along, qLock));
    // |g.xy| under the 0.1 threshold: refuse.
    const double tiny[3] = {0.05, -0.05, 0.99};
    CHECK_FALSE(horizonLockRotation(tiny, qLock));
    // Just above the threshold: works, ground truth holds.
    const double okSmall[3] = {0.05, -0.12, 0.0};
    REQUIRE(horizonLockRotation(okSmall, qLock));
    double qLockC[4], v[3];
    quatConj(qLock, qLockC);
    quatRotate(qLockC, okSmall, v);
    CHECK(std::fabs(v[0]) < 1e-12);
    CHECK(std::fabs(v[1] + 0.13) < 1e-12);
    // Upside down (g = (0, +1, 0)): roll = 180 deg, finite, level.
    const double up[3] = {0.0, 1.0, 0.0};
    REQUIRE(horizonLockRotation(up, qLock));
    CHECK(quatAngleDeg(qLock) == doctest::Approx(180.0).epsilon(1e-9));
    quatConj(qLock, qLockC);
    quatRotate(qLockC, up, v);
    CHECK(std::fabs(v[0]) < 1e-12);
    CHECK(std::fabs(v[1] + 1.0) < 1e-12);
}

// ---------------------------------------------------------------------------
// Frame transform / homography (deliverable 3) — tier 2
// ---------------------------------------------------------------------------

TEST_CASE("frameTransform: identity q_rel gives the bit-exact identity H") {
    // Binary-exact intrinsics (f = 1024, cx = 800, cy = 600): every
    // product in K·D·I·D·K⁻¹ is exact in double, so the identity must
    // come out BIT-EXACT (the adjugate inverse of K is exact too).
    double ident[4] = {1.0, 0.0, 0.0, 0.0};
    double h[9];
    frameTransform(ident, 1024.0, 800.0, 600.0, h);
    const double expect[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (int i = 0; i < 9; ++i)
        CHECK(h[i] == expect[i]);
}

TEST_CASE("frameTransform: pure roll is a 2D rotation about the principal point") {
    // q_rel = Rz(+5 deg): D commutes with Rz, so H is the pure 2D
    // rotation about (cx, cy) with the OPPOSITE sign in v (a CCW
    // camera roll appears CW on screen). Hand-derived:
    //   [[cos, +sin], [-sin, cos]] block, h[2] = cx(1-cos) - cy·sin,
    //   h[5] = cx·sin + cy(1-cos), row 3 EXACTLY (0, 0, 1).
    const double f = 2554.6877034505208;
    const double cx = 1920.0, cy = 1080.0;
    double qRel[4], h[9];
    quatAxisDeg(2, 5.0, qRel);
    frameTransform(qRel, f, cx, cy, h);
    const double c = std::cos(deg2rad(5.0));
    const double s = std::sin(deg2rad(5.0));
    CHECK(h[0] == doctest::Approx(c).epsilon(1e-12));
    CHECK(h[1] == doctest::Approx(s).epsilon(1e-12));
    CHECK(h[3] == doctest::Approx(-s).epsilon(1e-12));
    CHECK(h[4] == doctest::Approx(c).epsilon(1e-12));
    CHECK(h[2] == doctest::Approx(cx * (1.0 - c) - cy * s).epsilon(1e-9));
    CHECK(h[5] == doctest::Approx(cx * s + cy * (1.0 - c)).epsilon(1e-9));
    CHECK(h[6] == 0.0);
    CHECK(h[7] == 0.0);
    CHECK(h[8] == 1.0);
    // The principal point is FIXED by the roll warp.
    double px, py;
    applyH(h, cx, cy, px, py);
    CHECK(px == doctest::Approx(cx).epsilon(1e-9));
    CHECK(py == doctest::Approx(cy).epsilon(1e-9));
}

TEST_CASE("frameTransform: pitch down moves the center below, pan right moves it right") {
    // q_rel = Rx(-10 deg) (camera pitched DOWN relative to target):
    // the center pixel must land BELOW the output center (v grows
    // down) — the flip D is load-bearing; the naive K·R·K⁻¹ inverts
    // this. Exact form: (cx, cy + f·tan(10 deg)); h[6] == 0 exactly,
    // h[7] < 0.
    const double f = 2554.6877034505208;
    const double cx = 1920.0, cy = 1080.0;
    double qPitch[4], h[9];
    quatAxisDeg(0, -10.0, qPitch);
    frameTransform(qPitch, f, cx, cy, h);
    CHECK(h[6] == doctest::Approx(0.0).epsilon(1e-12));
    CHECK(h[7] < 0.0);
    double px, py;
    applyH(h, cx, cy, px, py);
    CHECK(px == doctest::Approx(cx).epsilon(1e-9));
    CHECK(py == doctest::Approx(cy + f * std::tan(deg2rad(10.0))).epsilon(1e-9));
    CHECK(py > cy); // below center, v-down

    // q_rel = Ry(-10 deg) (camera panned RIGHT): center lands RIGHT of
    // the output center: (cx + f·tan(10 deg), cy); h[7] == 0, h[6] < 0.
    double qPan[4];
    quatAxisDeg(1, -10.0, qPan);
    frameTransform(qPan, f, cx, cy, h);
    CHECK(h[7] == doctest::Approx(0.0).epsilon(1e-12));
    CHECK(h[6] < 0.0);
    applyH(h, cx, cy, px, py);
    CHECK(px == doctest::Approx(cx + f * std::tan(deg2rad(10.0))).epsilon(1e-9));
    CHECK(py == doctest::Approx(cy).epsilon(1e-9));
    CHECK(px > cx); // right of center

    // Small-angle sanity: 1 deg pitch shifts the center by ~f·tan(1 deg).
    double qSmall[4];
    quatAxisDeg(0, -1.0, qSmall);
    frameTransform(qSmall, f, cx, cy, h);
    applyH(h, cx, cy, px, py);
    CHECK(py - cy == doctest::Approx(f * std::tan(deg2rad(1.0))).epsilon(1e-9));
}

TEST_CASE("frameTransform: overload, fallback, and the equivalent-focal conversion") {
    // The width/height overload is bit-identical to the centered call.
    double qRel[4], h1[9], h2[9];
    quatAxisDeg(2, 7.0, qRel);
    frameTransform(qRel, 2554.0, 1920.0, 1080.0, h1);
    frameTransform(qRel, 2554.0, 3840, 2160, h2);
    for (int i = 0; i < 9; ++i)
        CHECK(h1[i] == h2[i]);

    // f = 0 (singular K): identity fallback — the math stays total.
    double h0[9];
    frameTransform(qRel, 0.0, 1920.0, 1080.0, h0);
    const double ident[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (int i = 0; i < 9; ++i)
        CHECK(h0[i] == ident[i]);

    // The one documented fallback: 35mm-equivalent mm -> px under the
    // STATED width-spans-36mm assumption. Bit-exact against the
    // in-expression computation (the fixture's exact container value).
    const double mm = 23.950197219848633;
    CHECK(focalLengthPxFromEquivalent(mm, 3840)
          == 3840.0 * mm / 36.0);
    CHECK(focalLengthPxFromEquivalent(mm, 3840)
          == doctest::Approx(2554.6877034505208).epsilon(1e-12));
    // Non-positive inputs: 0 (unknown), never a guess.
    CHECK(focalLengthPxFromEquivalent(0.0, 3840) == 0.0);
    CHECK(focalLengthPxFromEquivalent(-12.0, 3840) == 0.0);
    CHECK(focalLengthPxFromEquivalent(mm, 0) == 0.0);
    CHECK(focalLengthPxFromEquivalent(mm, -3840) == 0.0);
}

// ---------------------------------------------------------------------------
// Static crop (deliverable 4) — tier 2
// ---------------------------------------------------------------------------

TEST_CASE("staticCropZoom: empty, identity, and degenerate inputs") {
    const double cx = 1920.0, cy = 1080.0;
    // Empty list: nothing to crop for.
    CHECK(staticCropZoom({}, 2554.0, cx, cy, 3840, 2160) == 1.0);
    // Identity q_rel: no crop.
    double ident[4] = {1.0, 0.0, 0.0, 0.0};
    CHECK(staticCropZoom(quatVec(ident), 2554.0, cx, cy, 3840, 2160) == 1.0);
    // Degenerate dims / focal: refuse with 1.0, never a guess.
    double q[4];
    quatAxisDeg(2, 10.0, q);
    CHECK(staticCropZoom(quatVec(q), 0.0, cx, cy, 3840, 2160) == 1.0);
    CHECK(staticCropZoom(quatVec(q), 2554.0, cx, cy, 0, 2160) == 1.0);
    CHECK(staticCropZoom(quatVec(q), 2554.0, cx, cy, 3840, 0) == 1.0);
}

TEST_CASE("staticCropZoom: numeric path reproduces the roll closed form") {
    // Roll-only closed form (Stabilization.h):
    //   Z(θ) = max((w|sinθ| + h|cosθ|)/h, (w|cosθ| + h|sinθ|)/w).
    // The numeric bisection must reproduce it to 1e-9 (the bisection's
    // own stopping tolerance) at several angles.
    const double w = 3840.0, h = 2160.0;
    const double cx = w / 2.0, cy = h / 2.0;
    for (double theta : {5.0, 10.0, 20.0, 45.0}) {
        double q[4];
        quatAxisDeg(2, theta, q);
        const double s = std::fabs(std::sin(deg2rad(theta)));
        const double cc = std::fabs(std::cos(deg2rad(theta)));
        const double closed = std::fmax((w * s + h * cc) / h, (w * cc + h * s) / w);
        const double numeric = staticCropZoom(quatVec(q),
                                              2554.6877034505208, cx, cy,
                                              3840, 2160);
        CHECK(numeric == doctest::Approx(closed).epsilon(1e-9));
    }
}

TEST_CASE("staticCropZoom: pitch costs more zoom than roll at 8 deg") {
    // Independent of focal? No — pitch shifts corners by ~f·tanθ AND
    // stretches via the perspective terms, so at f = 2554 px the 8 deg
    // pitch crop is strictly LARGER than the 8 deg roll crop (the
    // roll closed form is focal-independent). Numbers quoted by the
    // run (MESSAGE); the pin is the ORDERING plus sane bounds.
    const double f = 2554.6877034505208;
    const double cx = 1920.0, cy = 1080.0;
    double qRoll[4], qPitch[4];
    quatAxisDeg(2, 8.0, qRoll);
    quatAxisDeg(0, -8.0, qPitch);
    const double zRoll = staticCropZoom(quatVec(qRoll), f, cx, cy, 3840, 2160);
    const double zPitch = staticCropZoom(quatVec(qPitch), f, cx, cy, 3840, 2160);
    MESSAGE("crop at 8 deg: roll=" << zRoll << " pitch=" << zPitch);
    // Roll: the closed form at 8 deg (independent of f).
    const double s8 = std::sin(deg2rad(8.0));
    const double c8 = std::cos(deg2rad(8.0));
    const double rollClosed = std::fmax((3840.0 * s8 + 2160.0 * c8) / 2160.0,
                                       (3840.0 * c8 + 2160.0 * s8) / 3840.0);
    CHECK(zRoll == doctest::Approx(rollClosed).epsilon(1e-9));
    CHECK(zPitch > zRoll);            // perspective on top of the shift
    CHECK(zPitch > 1.50);            // beyond the shift-only bound
    CHECK(zPitch < 1.70);            // sane for a 4K frame at 8 deg
}
