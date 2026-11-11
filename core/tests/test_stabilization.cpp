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
//
// Later tiers (separate commits) pin the frame homography + static crop
// and the clip-level API + real-fixture evidence.

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
