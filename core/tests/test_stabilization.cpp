// Stabilization Phase 4 tests — tier 1: the orientation primitives.
//
// CONTRACTS PINNED HERE (hand-derived; Stabilization.h documents them —
// these doctests are the executable proof):
//   - smoothOrientationSeries: strength 0 is EXACTLY the raw series
//     (explicit pass-through since the 2026-11-12 audit fix; the
//     pre-fix map reached it via tau = 1 ns, alpha = 1 at every real
//     dt); the strength clamps to [0, 1]; empty/one-sample/dt=0 edges
//     stay total; the redesigned tau map (tauMax·(tauMin/tauMax)^(1-s),
//     tauMin = 1/60 s, tauMax = 1 s) makes the mid strengths smooth for
//     real — 0.5 is tau ≈ 129 ms, a visibly corrected target on the
//     frame grid; strength 1 measurably damps a 1 Hz handheld wobble;
//     the forward+reverse pair is time-symmetric, so a symmetric sweep
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
//   - staticCropZoom (SAMPLER-EXACT since the 2026-11-13 audit fix):
//     empty/identity/degenerate -> exactly 1.0; the checked domain is
//     warpFrame's own — the OUTPUT pixel-center rect corners mapped
//     through the composite with POSITIVE homogeneous denominators,
//     valid inside [0,w-1]x[0,h-1] INCLUSIVE (the old checker used
//     the looser continuous [0,w]x[0,h] window corners, which is how
//     plans the sampler then edge-clamped into smear passed it); the
//     numeric bisection reproduces the roll closed form at the
//     PIXEL-RECT center c = ((w-1)/2, (h-1)/2):
//       Z(θ) = max(((w-1)|sinθ|+(h-1)|cosθ|)/(h-1),
//                  ((w-1)|cosθ|+(h-1)|sinθ|)/(w-1))
//     at 5/10/20/45 deg and takes the max over frames; at 8 deg pitch
//     costs strictly more zoom than roll (perspective stretch on top
//     of the f·tanθ shift); the boundary conventions are pinned
//     directly (identity fits at Z = 1 exactly — the inclusive
//     pixel-center domain; an ε roll does not; a denominator sign
//     change inside the rect never fits at that zoom — the sampler
//     would mirror-sample past the vanishing line).
//   - computeStabilization (clip API): mode Off = identity + zoom 1 +
//     status Off for every frame, zero cost; the refusal ladder
//     (empty series -> NoImu, unknown offset -> Unsynced, missing
//     intrinsics -> NoImu, no frames -> empty) reports correctionScale
//     0 (no correction applied); LowOverlap flags frames outside the
//     gyro span (finite H still); Smooth at the default strength on
//     the 30 fps frame grid is a near no-op; Smooth at the default
//     strength on the 30 fps frame grid is a real (visible)
//     correction since the 2026-11-12 tau redesign; LockFirstFrame
//     pins frame 0; HorizonLock levels a rolled camera and degrades
//     to plain Smooth (flagged) without gravity; the zoom-limit clamp
//     keeps requiredZoom honest.
//   - Crop-constrained correction (audit fix 2026-11-13, Step 7b):
//     when requiredZoom <= appliedZoom the plan is BIT-IDENTICAL to
//     the pre-fix behavior (correctionScale exactly 1 everywhere —
//     the compatibility regression pin); when the budget is short
//     the CORRECTION scales on the slerp geodesic (never the zoom
//     past the limit, never edge-clamped pixels): zero budget ->
//     identity warp (scales exactly 0); a short budget -> scales in
//     [0,1], non-increasing on a growing pan, clip-wide a_clip <=
//     min <= mean <= 1, and EVERY frame's shipped H re-verified to
//     fit at appliedZoom under the sampler-exact footprint check
//     (the anti-smear executable proof — on synthetic yaw/pitch/
//     roll and on both real fixtures); invalidSampleFrames == 0.
//   - Real-fixture evidence (both fixtures, from the FILES): anchored
//     sync -> VQF orientation -> focal from the container ->
//     computeStabilization at several modes/strengths; the numbers
//     are QUOTED via MESSAGE and pinned within honest bounds.
//
// Fixture evidence also cross-checks the smoothed-vs-raw residual:
// smoothOrientationSeries on the actual-on-frames series must equal
// the relativeAngleDeg the clip API reports (the API IS that
// pipeline, so the two paths agree to 1e-9).

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Stabilization.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Orientation.h>
#include <rawedit/Sync.h>
#include <rawedit/Source.h>

#include <motioncam/Decoder.hpp>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <limits>
#include <string>
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

// Test-side adjugate inverse — the same body Warp.cpp and
// Stabilization.cpp use (their copies are anonymous-namespace; the
// doctests pin all three to the same values).
bool stMatInv(const double m[9], double out[9]) {
    const double c00 = m[4] * m[8] - m[5] * m[7];
    const double c01 = m[5] * m[6] - m[3] * m[8];
    const double c02 = m[3] * m[7] - m[4] * m[6];
    const double det = m[0] * c00 + m[1] * c01 + m[2] * c02;
    if (!std::isfinite(det) || std::fabs(det) < 1e-12)
        return false;
    const double inv = 1.0 / det;
    out[0] = c00 * inv;
    out[1] = (m[2] * m[7] - m[1] * m[8]) * inv;
    out[2] = (m[1] * m[5] - m[2] * m[4]) * inv;
    out[3] = c01 * inv;
    out[4] = (m[0] * m[8] - m[2] * m[6]) * inv;
    out[5] = (m[2] * m[3] - m[0] * m[5]) * inv;
    out[6] = c02 * inv;
    out[7] = (m[1] * m[6] - m[0] * m[7]) * inv;
    out[8] = (m[0] * m[4] - m[1] * m[3]) * inv;
    return true;
}

// Test-side twin of warpFrame's sampler footprint — the anti-smear
// proof (audit fix 2026-11-13). warpFootprintViolation is internal
// to Stabilization.cpp, so this REBUILDS the exact chain warpFrame
// runs on a SHIPPED plan frame: matInv, the composite M = Hinv * S
// bit-for-bit as Warp.cpp builds it, the four OUTPUT pixel-center
// rect corners, FINITE POSITIVE homogeneous denominators (a negative
// w would mirror the sample across the vanishing line), and
// [0,w-1]x[0,h-1] INCLUSIVE validity (the x1/y1 bilinear guards make
// the boundary itself safe; anything past it clamps = smear).
// Returns the worst corner excursion in pixels (<= 0: nothing the
// sampler touches would clamp), or +inf when a corner is unmappable
// or behind the vanishing plane.
double samplerFootprintViolation(const double h[9], double zoom,
                                 double cx, double cy, int width, int height) {
    if (width <= 0 || height <= 0 || !(zoom >= 1.0) || !std::isfinite(zoom))
        return std::numeric_limits<double>::infinity();
    double hInv[9];
    if (!stMatInv(h, hInv))
        return std::numeric_limits<double>::infinity();
    const double s = 1.0 / zoom;
    double m[9];
    for (int i = 0; i < 3; ++i) {
        m[i * 3 + 0] = hInv[i * 3 + 0] * s;
        m[i * 3 + 1] = hInv[i * 3 + 1] * s;
        m[i * 3 + 2] = hInv[i * 3 + 0] * (cx - cx * s)
                     + hInv[i * 3 + 1] * (cy - cy * s)
                     + hInv[i * 3 + 2];
    }
    const double maxU = static_cast<double>(width) - 1.0;
    const double maxV = static_cast<double>(height) - 1.0;
    const double corners[4][2] = {{0.0, 0.0}, {maxU, 0.0},
                                  {0.0, maxV}, {maxU, maxV}};
    double worst = 0.0;
    for (int c = 0; c < 4; ++c) {
        const double x = corners[c][0], y = corners[c][1];
        const double wd = m[6] * x + m[7] * y + m[8];
        if (!std::isfinite(wd) || wd <= 0.0)
            return std::numeric_limits<double>::infinity();
        const double u = m[0] * x + m[1] * y + m[2];
        const double v = m[3] * x + m[4] * y + m[5];
        if (!std::isfinite(u) || !std::isfinite(v))
            return std::numeric_limits<double>::infinity();
        const double sx = u / wd, sy = v / wd;
        if (!std::isfinite(sx) || !std::isfinite(sy))
            return std::numeric_limits<double>::infinity();
        worst = std::fmax(worst, std::fmax(std::fmax(-sx, sx - maxU),
                                           std::fmax(-sy, sy - maxV)));
    }
    return worst;
}

// The 4-component quaternion as the flat vector the crop API takes.
std::vector<double> quatVec(const double q[4]) {
    return {q[0], q[1], q[2], q[3]};
}

// Largest REQUESTED correction angle in a plan, degrees (used by the
// acceptance tests and the fixture evidence tests).
double stMaxAngle(const StabilizationResult& r) {
    double m = 0.0;
    for (double a : r.relativeAngleDeg)
        m = std::fmax(m, a);
    return m;
}

} // namespace

// ---------------------------------------------------------------------------
// Smoothing (deliverable 1)
// ---------------------------------------------------------------------------

TEST_CASE("smoothing: strength 0 is exactly the raw series") {
    // Audit fix 2026-11-12: strength 0 is an EXPLICIT pass-through of
    // the normalized copy — the same doctest pin as before the fix
    // (the old map reached it via tau = 1 ns, alpha = 1 - exp(-dt/1ns)
    // = 1.0 EXACTLY in double for every real dt; the redesigned
    // tauMin = 1/60 s would leave a residue at 0, so the raw output is
    // now explicit rather than a floating-point accident).
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
    // Roll-only closed form (Stabilization.h), clean at the PIXEL-RECT
    // center c = ((w-1)/2, (h-1)/2):
    //   Z(θ) = max(((w-1)|sinθ| + (h-1)|cosθ|)/(h-1),
    //               ((w-1)|cosθ| + (h-1)|sinθ|)/(w-1)).
    // Derivation: H for a roll is the 2D rotation about c (pinned at
    // frameTransform), so H⁻¹∘S maps the output corner (x,y) to
    // c + R(-θ)·((x,y) - c)/Z; the four |·| terms are the corner
    // extents. The numeric bisection must reproduce it to 1e-9 (the
    // bisection's own stopping tolerance) at several angles.
    const double w = 3840.0, h = 2160.0;
    const double cx = (w - 1.0) / 2.0, cy = (h - 1.0) / 2.0;
    for (double theta : {5.0, 10.0, 20.0, 45.0}) {
        double q[4];
        quatAxisDeg(2, theta, q);
        const double s = std::fabs(std::sin(deg2rad(theta)));
        const double cc = std::fabs(std::cos(deg2rad(theta)));
        const double closed = std::fmax(((w - 1.0) * s + (h - 1.0) * cc) / (h - 1.0),
                                        ((w - 1.0) * cc + (h - 1.0) * s) / (w - 1.0));
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
    const double cx = 1920.0, cy = 1080.0; // the DEFAULT principal point
    double qRoll[4], qPitch[4];
    quatAxisDeg(2, 8.0, qRoll);
    quatAxisDeg(0, -8.0, qPitch);
    const double zRoll = staticCropZoom(quatVec(qRoll), f, cx, cy, 3840, 2160);
    const double zPitch = staticCropZoom(quatVec(qPitch), f, cx, cy, 3840, 2160);
    MESSAGE("crop at 8 deg: roll=" << zRoll << " pitch=" << zPitch);
    // Roll at the DEFAULT center (w/2, h/2 — half a pixel off the
    // pixel-rect center): the four corner constraints are asymmetric;
    // the derivation (H roll block [[c,s],[-s,c]] about c, composite
    // M = H⁻¹∘S) gives the four binding constraints below, and the
    // other four corner/constraint pairs are dominated (Stabilization.h
    // documents the same list). At typical aspect ratios the height
    // pair (denominators h/2-1, h/2) binds.
    const double s8 = std::sin(deg2rad(8.0));
    const double c8 = std::cos(deg2rad(8.0));
    const double hw = 3840.0 / 2.0, hh = 2160.0 / 2.0;
    const double rollClosed = std::fmax(
        std::fmax(((hw - 1.0) * c8 + hh * s8) / (hw - 1.0),
                  (hw * c8 + (hh - 1.0) * s8) / hw),
        std::fmax(((hw - 1.0) * s8 + (hh - 1.0) * c8) / (hh - 1.0),
                  (hw * s8 + hh * c8) / hh));
    CHECK(zRoll == doctest::Approx(rollClosed).epsilon(1e-9));
    CHECK(zPitch > zRoll);            // perspective on top of the shift
    CHECK(zPitch > 1.50);            // beyond the shift-only bound
    CHECK(zPitch < 1.70);            // sane for a 4K frame at 8 deg
}

TEST_CASE("sampler conventions: identity fits at Z=1, an epsilon roll does not") {
    // The reconciled boundary pins (audit fix 2026-11-13). The old
    // checker used the CONTINUOUS [0,w]x[0,h] window while warpFrame
    // clamps to [0,w-1]x[0,h-1]; the reconciled check is the
    // PIXEL-CENTER rect. Three boundary facts:
    //   1. The identity warp fits at Z = 1 EXACTLY: M = S maps the
    //      output rect onto itself, and the INCLUSIVE domain makes the
    //      boundary itself valid (the x1/y1 bilinear guards tap the
    //      edge pixel at fx == w-1 exactly).
    //   2. An epsilon roll does NOT fit at Z = 1: the rotated corners
    //      fall outside the pixel-center rect by O(theta) — the
    //      continuous-window checker would have accepted it, the
    //      sampler would have clamped (smear).
    //   3. A denominator sign change inside the rect never fits AT THAT
    //      ZOOM: warpFrame perspective-divides with the negative w
    //      (mirroring the sample across the vanishing line), so the
    //      check must refuse it even though the divided coordinates
    //      might land inside — a check on divided coordinates alone
    //      (mapPoint) would pass it.
    double ident[4] = {1.0, 0.0, 0.0, 0.0};
    double h[9], hInv[9];
    frameTransform(ident, 2554.0, 1920.0, 1080.0, h);
    REQUIRE(stMatInv(h, hInv));
    // (1) identity fits at Z = 1 exactly.
    CHECK(samplerFootprintViolation(h, 1.0, 1920.0, 1080.0, 3840, 2160) <= 0.0);

    // (2) an epsilon roll does not fit at Z = 1.
    double qEps[4], hEps[9], hInvEps[9];
    quatAxisDeg(2, 0.01, qEps);
    frameTransform(qEps, 2554.0, 1920.0, 1080.0, hEps);
    REQUIRE(stMatInv(hEps, hInvEps));
    CHECK(samplerFootprintViolation(hEps, 1.0, 1920.0, 1080.0,
                                     3840, 2160) > 0.0);
    // ...but a small zoom (the roll closed form) makes it fit: the
    // excursion is O(theta), the remedy is O(theta) too.
    const double zEps = staticCropZoom(quatVec(qEps), 2554.0, 1920.0, 1080.0,
                                       3840, 2160);
    CHECK(zEps > 1.0);
    CHECK(zEps < 1.001);
    CHECK(samplerFootprintViolation(hEps, zEps, 1920.0, 1080.0,
                                    3840, 2160) <= 0.0);

    // (3) a denominator sign change inside the rect never fits at that
    // zoom. A PURE ROLL cannot do it (its H has third row (0,0,1), so
    // w = 1 everywhere — a 180-deg roll is refused on COORDINATES, not
    // the denominator); the trap is the genuinely projective warp: a
    // pitch close to 90 deg puts the vanishing line INSIDE the rect,
    // so w(x,y) = m[6]x + m[7]y + m[8] is positive at some corners and
    // negative at others. warpFrame perspective-divides with the
    // negative w (mirroring the sample across the vanishing line), so
    // the check must refuse it even though the divided coordinates of
    // the positive-w corners might land inside — a check on divided
    // coordinates alone (mapPoint) would pass those corners.
    double qPitch[4], hPitch[9], hInvPitch[9];
    quatAxisDeg(0, -89.0, qPitch);
    frameTransform(qPitch, 2554.0, 1920.0, 1080.0, hPitch);
    REQUIRE(stMatInv(hPitch, hInvPitch));
    CHECK(std::isinf(samplerFootprintViolation(hPitch, 1.0, 1920.0, 1080.0,
                                               3840, 2160)));
    // ...and it never fits at ANY zoom: the violation is +inf for
    // every Z >= 1 (the vanishing line stays inside the rect; zooming
    // only scales the pre-division numerators).
    for (double z = 1.0; z < 4.0; z += 0.5)
        CHECK(std::isinf(samplerFootprintViolation(hPitch, z, 1920.0, 1080.0,
                                                   3840, 2160)));
    // The center still maps fine (the trap: applyH alone would pass).
    double px, py;
    applyH(hPitch, 1920.0, 1080.0, px, py);
    CHECK(px == doctest::Approx(1920.0).epsilon(1e-6));
    CHECK(py > 1080.0);            // pitched down: center lands below
    CHECK(std::isfinite(py));
}

TEST_CASE("computeStabilization: acceptance — identity and fixed camera") {
    // The acceptance suite (audit fix 2026-11-13). Identity: a static
    // camera needs NO correction — q_rel is the identity, the crop is
    // 1.0, every scale stays 1.0 (nothing to reduce: the compatibility
    // contract), and the plan is the identity warp.
    const int n = 20;
    const OrientationSeries raw = seriesFromAngles(std::vector<double>(n, 0.0),
                                                   0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                      raw.timestampsNs,
                                                      nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    CHECK(r.requiredZoom == 1.0);
    CHECK(r.appliedZoom == 1.0);
    for (int i = 0; i < n; ++i) {
        CHECK(r.relativeAngleDeg[i] == doctest::Approx(0.0).epsilon(1e-12));
        CHECK(r.correctionScale[i] == 1.0); // nothing asked, nothing reduced
        for (int k = 0; k < 9; ++k) {
            const double want = k == 0 || k == 4 || k == 8 ? 1.0 : 0.0;
            CHECK(r.frames[i].h[k] == want);
        }
        CHECK(r.frames[i].zoom == 1.0);
        CHECK(r.frames[i].status == StabilizationStatus::Ok);
    }
    CHECK(r.clipCorrectionScale == 1.0);
    CHECK(r.minCorrectionScale == 1.0);
    CHECK(r.meanCorrectionScale == 1.0);
    CHECK(r.invalidSampleFrames == 0);
}

TEST_CASE("computeStabilization: acceptance — known yaw/pitch/roll fits the budget") {
    // A known composite rotation (10 deg yaw + 5 deg pitch + 3 deg
    // roll). LockFirstFrame anchors the target at frame 0 (identity),
    // so every later frame's q_rel IS the composite — the known-input
    // acceptance fixture. At a GENEROUS limit (2.0) the budget
    // suffices — every scale stays exactly 1.0 (the compatibility
    // contract), the warp is the full correction, and the sampler
    // footprint is clean at appliedZoom. (The default 1.3 with a
    // borderline clip is pinned in the zoom-limit test.)
    const int n = 10;
    double qYaw[4], qPitch[4], qRoll[4], qTmp[4], qFull[4];
    quatAxisDeg(1, 10.0, qYaw);
    quatAxisDeg(0, 5.0, qPitch);
    quatAxisDeg(2, 3.0, qRoll);
    quatMultiply(qYaw, qPitch, qTmp);
    quatMultiply(qTmp, qRoll, qFull);
    const double qIdent[4] = {1.0, 0.0, 0.0, 0.0};
    OrientationSeries raw;
    raw.timestampsNs.reserve(n);
    raw.quats.reserve(4 * n);
    for (int i = 0; i < n; ++i) {
        raw.timestampsNs.push_back(int64_t(i) * 33333333);
        for (int k = 0; k < 4; ++k)
            raw.quats.push_back(i == 0 ? qIdent[k] : qFull[k]);
    }
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    p.zoomLimit = 2.0;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                      raw.timestampsNs,
                                                      nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    MESSAGE("yaw/pitch/roll: requiredZoom=" << r.requiredZoom
            << " appliedZoom=" << r.appliedZoom);
    CHECK(r.requiredZoom > 1.0);      // the composite really needs a crop
    CHECK(r.requiredZoom < 2.0);      // ...within the generous budget
    CHECK(r.appliedZoom == r.requiredZoom);
    for (int i = 0; i < n; ++i) {
        if (i == 0)
            continue; // the anchor frame: q_rel is the identity
        CHECK(r.relativeAngleDeg[i] == doctest::Approx(
                  quatAngleDeg(qFull)).epsilon(1e-9));
        CHECK(r.correctionScale[i] == 1.0);
        CHECK(r.frames[i].status == StabilizationStatus::Ok);
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    }
    CHECK(r.clipCorrectionScale == 1.0);
    CHECK(r.minCorrectionScale == 1.0);
    CHECK(r.meanCorrectionScale == 1.0);
    CHECK(r.invalidSampleFrames == 0);
}

TEST_CASE("computeStabilization: acceptance — pan+jitter under a short budget") {
    // A realistic handheld clip: a 0.5 deg/frame pan with 2 deg jitter
    // on top (LockFirstFrame so the correction is the accumulated
    // pan+jitter), under a deliberately SHORT limit (1.05). The audit
    // fix's headline behavior: the correction visibly scales down
    // (min/mean strictly inside (0,1)), stays smooth over time (the
    // smoothing contract: no frame's scale exceeds its neighbors' by
    // a visible jump), and every shipped H re-checks clean at
    // appliedZoom — warpFrame never edge-clamps this plan.
    const int n = 60;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 0.5 * double(i)
                  + 2.0 * std::sin(2.0 * kPi * 3.0 * double(i) / 30.0);
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    p.zoomLimit = 1.05;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                      raw.timestampsNs,
                                                      nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    MESSAGE("pan+jitter: requiredZoom=" << r.requiredZoom
            << " appliedZoom=" << r.appliedZoom
            << " minScale=" << r.minCorrectionScale
            << " meanScale=" << r.meanCorrectionScale);
    CHECK(r.requiredZoom > r.appliedZoom);   // the budget really is short
    CHECK(r.appliedZoom == 1.05);
    REQUIRE(r.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i) {
        CHECK(r.correctionScale[i] > 0.0);
        CHECK(r.correctionScale[i] < 1.0);
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    }
    CHECK(r.minCorrectionScale > 0.0);
    CHECK(r.minCorrectionScale < 1.0);
    CHECK(r.meanCorrectionScale > 0.0);
    CHECK(r.meanCorrectionScale < 1.0);
    CHECK(r.invalidSampleFrames == 0);
    // Smooth over time: the scales vary gently (the sliding-min
    // envelope + triangular kernel), never a frame-to-frame cliff —
    // the audit's "smoothly over time" requirement, executable.
    double jump = 0.0;
    for (int i = 1; i < n; ++i)
        jump = std::fmax(jump, std::fabs(r.correctionScale[i]
                                         - r.correctionScale[i - 1]));
    MESSAGE("pan+jitter: max frame-to-frame scale jump=" << jump);
    CHECK(jump < 0.05);
}

TEST_CASE("computeStabilization: acceptance — very low crop budget and high strength") {
    // The two extreme operands of the audit finding. (a) A very low
    // budget (limit 1.001 on a clip needing ~1.24): the correction
    // collapses toward identity but stays > 0 and smooth, and the
    // sampler footprint is still clean — the plan degrades HONESTLY
    // (visible reduction), never to edge-clamped smear.
    // (b) High strength (0.99) on a jittery clip: the smoothing asks
    // for a big correction; with the default limit the budget may or
    // may not suffice, but either way the invariants hold (scales in
    // [0,1], footprint clean, invalidSampleFrames == 0).
    const int n = 60;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 8.0 * double(i) / double(n - 1);
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;

    // (a) very low budget.
    p.zoomLimit = 1.001;
    StabilizationResult r = computeStabilization(raw, sync,
                                                raw.timestampsNs,
                                                nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    MESSAGE("low-budget: requiredZoom=" << r.requiredZoom
            << " appliedZoom=" << r.appliedZoom
            << " minScale=" << r.minCorrectionScale
            << " meanScale=" << r.meanCorrectionScale);
    CHECK(r.requiredZoom > r.appliedZoom);
    CHECK(r.appliedZoom == doctest::Approx(1.001).epsilon(1e-12));
    REQUIRE(r.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i) {
        CHECK(r.correctionScale[i] >= 0.0);
        CHECK(r.correctionScale[i] < 1.0);
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    }
    CHECK(r.minCorrectionScale < 0.2);  // the budget is nearly zero...
    CHECK(r.invalidSampleFrames == 0);
    double jump = 0.0;
    for (int i = 1; i < n; ++i)
        jump = std::fmax(jump, std::fabs(r.correctionScale[i]
                                         - r.correctionScale[i - 1]));
    CHECK(jump < 0.05);                // ...and still smooth over time

    // (a') a TRUE zero budget (limit 1.0): no correction at all fits,
    // so every scale is exactly 0 and the plan is the identity warp —
    // the honest floor, never edge-clamped pixels.
    p.zoomLimit = 1.0;
    r = computeStabilization(raw, sync, raw.timestampsNs,
                            nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    CHECK(r.appliedZoom == 1.0);
    REQUIRE(r.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i) {
        CHECK(r.correctionScale[i] == 0.0);
        for (int k = 0; k < 9; ++k) {
            const double want = k == 0 || k == 4 || k == 8 ? 1.0 : 0.0;
            CHECK(r.frames[i].h[k] == want); // identity warp exactly
        }
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    }
    CHECK(r.clipCorrectionScale == 0.0);
    CHECK(r.minCorrectionScale == 0.0);
    CHECK(r.meanCorrectionScale == 0.0);
    CHECK(r.invalidSampleFrames == 0);
    MESSAGE("zero-budget: all scales exactly 0, identity warp");

    // (b) high strength: the correction asked for is large; the plan
    // stays honest whatever the budget decides.
    p.zoomLimit = 1.3;
    p.mode = StabilizationMode::Smooth;
    p.strength = 0.99;
    r = computeStabilization(raw, sync, raw.timestampsNs,
                            nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    MESSAGE("high-strength: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom
            << " appliedZoom=" << r.appliedZoom
            << " minScale=" << r.minCorrectionScale);
    REQUIRE(r.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i) {
        CHECK(r.correctionScale[i] >= 0.0);
        CHECK(r.correctionScale[i] <= 1.0);
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    }
    CHECK(r.minCorrectionScale >= 0.0);
    CHECK(r.minCorrectionScale <= 1.0);
    CHECK(r.invalidSampleFrames == 0);
    CHECK(stMaxAngle(r) > 0.05);  // the high strength really corrects
}

TEST_CASE("computeStabilization: acceptance — orientation variants and partial gyro coverage") {
    // Orientation variants: the same correction asked for under YAW
    // and under PITCH must both fit honestly at a generous limit, and
    // at a short limit both degrade smoothly (the slerp scale is
    // axis-agnostic — it scales the geodesic, not the axis).
    const int n = 30;
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    for (int axis : {0, 1, 2}) {
        std::vector<double> angles(n);
        for (int i = 0; i < n; ++i)
            angles[i] = 6.0 * double(i) / double(n - 1);
        const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, axis);
        // Generous budget: full correction, all scales 1.0. (The 6-deg
        // PITCH needs ~1.39 crop — perspective on top of the shift —
        // so the limit must sit above that: 2.0.)
        StabilizationParams generous = p;
        generous.zoomLimit = 2.0;
        StabilizationResult r = computeStabilization(raw, sync,
                                                    raw.timestampsNs,
                                                    nullptr, true, false, generous);
        REQUIRE(r.frames.size() == size_t(n));
        MESSAGE("axis-" << axis << " generous: requiredZoom=" << r.requiredZoom
                << " minScale=" << r.minCorrectionScale);
        CHECK(r.requiredZoom > 1.0);
        CHECK(r.requiredZoom <= 2.0);
        CHECK(r.appliedZoom == r.requiredZoom);
        for (int i = 0; i < n; ++i)
            CHECK(r.correctionScale[i] == 1.0);
        // Short budget: smooth honest reduction, footprint clean.
        StabilizationParams tight = p;
        tight.zoomLimit = 1.05;
        r = computeStabilization(raw, sync, raw.timestampsNs,
                                 nullptr, true, false, tight);
        REQUIRE(r.correctionScale.size() == size_t(n));
        MESSAGE("axis-" << axis << " tight: requiredZoom=" << r.requiredZoom
                << " minScale=" << r.minCorrectionScale
                << " meanScale=" << r.meanCorrectionScale);
        CHECK(r.requiredZoom > r.appliedZoom);
        for (int i = 0; i < n; ++i) {
            CHECK(r.correctionScale[i] > 0.0);
            CHECK(r.correctionScale[i] < 1.0);
            CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                            1920.0, 1080.0, 3840, 2160) <= 0.0);
        }
        CHECK(r.invalidSampleFrames == 0);
    }

    // Partial gyro coverage: frames beyond the gyro span still get a
    // plan (the clamped orientation), the covered frames carry the
    // full contract, and the reduction is smooth across the coverage
    // boundary — the envelope's job. Gyro span [1000, 21000] (21
    // samples, 0 -> 8 deg ramp); frames at ts 0..29000: frame 0 (ts 0)
    // and frames 22..29 (ts > 21000) are outside (LowOverlap, clamped
    // orientations), frames 1..21 inside. The clip needs the 8-deg
    // roll crop (~1.238); at limit 1.05 the budget is short and the
    // correction scales — everywhere, including the clamped frames.
    std::vector<double> ramp(21);
    for (int i = 0; i < 21; ++i)
        ramp[i] = 8.0 * double(i) / 20.0;
    const OrientationSeries part = seriesFromAngles(ramp, 1000, 1000, 2);
    std::vector<int64_t> frames;
    for (int i = 0; i < 30; ++i)
        frames.push_back(int64_t(i) * 1000);
    StabilizationParams partial = p;
    partial.zoomLimit = 1.05;
    StabilizationResult r = computeStabilization(part, sync, frames,
                                                 nullptr, true, false, partial);
    REQUIRE(r.frames.size() == 30);
    CHECK(r.frames[0].status == StabilizationStatus::LowOverlap);
    CHECK(r.frames[10].status == StabilizationStatus::Ok);
    CHECK(r.frames[21].status == StabilizationStatus::Ok);
    CHECK(r.frames[22].status == StabilizationStatus::LowOverlap);
    CHECK(r.frames[29].status == StabilizationStatus::LowOverlap);
    MESSAGE("partial: requiredZoom=" << r.requiredZoom
            << " minScale=" << r.minCorrectionScale
            << " meanScale=" << r.meanCorrectionScale);
    CHECK(r.requiredZoom > r.appliedZoom);
    REQUIRE(r.correctionScale.size() == 30);
    for (int i = 0; i < 30; ++i) {
        CHECK(r.correctionScale[i] > 0.0);
        CHECK(r.correctionScale[i] < 1.0);
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    }
    CHECK(r.invalidSampleFrames == 0);
    // Smooth across the coverage boundary (frames 21 -> 22): no cliff.
    CHECK(std::fabs(r.correctionScale[22] - r.correctionScale[21]) < 0.05);
}

// ---------------------------------------------------------------------------
// Clip-level API (deliverable 5) — tier 3
// ---------------------------------------------------------------------------

TEST_CASE("computeStabilization: mode Off is identity, zoom 1, zero cost") {
    // The series is non-empty and everything else would compute — Off
    // must short-circuit BEFORE touching it (identity H, zoom 1.0,
    // status Off per frame, no relative angles).
    const OrientationSeries raw = seriesFromAngles({0, 5, 10, 15, 20}, 0, 33333333, 2);
    std::vector<int64_t> frames = {0, 33333333, 66666666, 99999999};
    SyncModel sync; // zero offset
    StabilizationParams p;
    p.mode = StabilizationMode::Off;
    const StabilizationResult r = computeStabilization(raw, sync, frames,
                                                       nullptr, true, false, p);
    REQUIRE(r.frames.size() == 4);
    const double ident[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    for (const auto& f : r.frames) {
        for (int i = 0; i < 9; ++i)
            CHECK(f.h[i] == ident[i]);
        CHECK(f.zoom == 1.0);
        CHECK(f.status == StabilizationStatus::Off);
    }
    CHECK(r.requiredZoom == 1.0);
    CHECK(r.appliedZoom == 1.0);
    for (double a : r.relativeAngleDeg)
        CHECK(a == 0.0);
    // Off refuses before any correction exists: the applied-scale
    // numbers report the identity plan (all 0).
    REQUIRE(r.correctionScale.size() == 4);
    for (double a : r.correctionScale)
        CHECK(a == 0.0);
    CHECK(r.clipCorrectionScale == 0.0);
    CHECK(r.minCorrectionScale == 0.0);
    CHECK(r.meanCorrectionScale == 0.0);
    CHECK(r.invalidSampleFrames == 0);
}

TEST_CASE("computeStabilization: refusal ladder and status names") {
    const OrientationSeries raw = seriesFromAngles({0, 5, 10}, 0, 1000, 2);
    std::vector<int64_t> frames = {0, 1000, 2000};
    SyncModel sync;
    StabilizationParams p; // Smooth, defaults

    // Empty series -> NoImu (uniform identity, zoom 1).
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    StabilizationResult r = computeStabilization({}, sync, frames,
                                                 nullptr, true, false, p);
    REQUIRE(r.frames.size() == 3);
    for (const auto& f : r.frames) {
        CHECK(f.status == StabilizationStatus::NoImu);
        CHECK(f.zoom == 1.0);
        CHECK(f.h[0] == 1.0);
    }
    // A refusal applies NO correction: the applied-scale numbers report
    // the identity plan (all 0; Stabilization.h's Step 7b contract).
    REQUIRE(r.correctionScale.size() == 3);
    for (double a : r.correctionScale)
        CHECK(a == 0.0);
    CHECK(r.clipCorrectionScale == 0.0);
    CHECK(r.minCorrectionScale == 0.0);
    CHECK(r.meanCorrectionScale == 0.0);
    CHECK(r.invalidSampleFrames == 0);

    // Unknown offset -> Unsynced (refuses to guess the clock bridge).
    r = computeStabilization(raw, sync, frames, nullptr, false, false, p);
    REQUIRE(r.frames.size() == 3);
    for (const auto& f : r.frames)
        CHECK(f.status == StabilizationStatus::Unsynced);
    REQUIRE(r.correctionScale.size() == 3);
    for (double a : r.correctionScale)
        CHECK(a == 0.0);
    CHECK(r.clipCorrectionScale == 0.0);
    CHECK(r.minCorrectionScale == 0.0);
    CHECK(r.meanCorrectionScale == 0.0);
    CHECK(r.invalidSampleFrames == 0);

    // Missing intrinsics (focal 0 / width 0) -> NoImu-class refusal.
    StabilizationParams noFocal = p;
    noFocal.focalLengthPx = 0.0;
    r = computeStabilization(raw, sync, frames, nullptr, true, false, noFocal);
    for (const auto& f : r.frames)
        CHECK(f.status == StabilizationStatus::NoImu);
    REQUIRE(r.correctionScale.size() == 3);
    for (double a : r.correctionScale)
        CHECK(a == 0.0);
    StabilizationParams noDims = p;
    noDims.width = 0;
    r = computeStabilization(raw, sync, frames, nullptr, true, false, noDims);
    for (const auto& f : r.frames)
        CHECK(f.status == StabilizationStatus::NoImu);
    REQUIRE(r.correctionScale.size() == 3);
    for (double a : r.correctionScale)
        CHECK(a == 0.0);

    // No frames -> empty result, flags clean. The struct defaults carry
    // the new fields: empty correctionScale, 1.0 scalars (nothing was
    // reduced; there was nothing to reduce).
    r = computeStabilization(raw, sync, {}, nullptr, true, false, p);
    CHECK(r.frames.empty());
    CHECK(r.relativeAngleDeg.empty());
    CHECK(r.correctionScale.empty());
    CHECK(r.clipCorrectionScale == 1.0);
    CHECK(r.minCorrectionScale == 1.0);
    CHECK(r.meanCorrectionScale == 1.0);
    CHECK(r.invalidSampleFrames == 0);
    CHECK(r.requiredZoom == 1.0);
    CHECK_FALSE(r.intrinsicsAssumed);

    // The names (the UI-facing strings).
    CHECK(std::string(stabilizationStatusName(StabilizationStatus::Ok)) == "ok");
    CHECK(std::string(stabilizationStatusName(StabilizationStatus::Off)) == "off");
    CHECK(std::string(stabilizationStatusName(StabilizationStatus::NoImu)) == "no-imu");
    CHECK(std::string(stabilizationStatusName(StabilizationStatus::Unsynced)) == "unsynced");
    CHECK(std::string(stabilizationStatusName(StabilizationStatus::LowOverlap)) == "low-overlap");
    CHECK(std::string(stabilizationStatusName(StabilizationStatus::IntrinsicsAssumed))
          == "intrinsics-assumed");
}

TEST_CASE("computeStabilization: LowOverlap flags frames outside the gyro span") {
    // Gyro span [1000, 5000]; frames at 0 and 6000 (frame clock == IMU
    // clock, zero offset) fall outside -> LowOverlap, but the warp is
    // still computed from the CLAMPED orientation (finite H).
    // LockFirstFrame makes the clamp direction OBSERVABLE: the anchor
    // is the first INSIDE frame (ts 2000, 10 deg), so
    //   frame 0 (clamped to the FIRST orientation, 0 deg)  -> 10 deg
    //   frame 3 (clamped to the LAST orientation, 40 deg)   -> 30 deg
    // (clamping frame 0 to the last instead would give 30 there).
    const OrientationSeries raw = seriesFromAngles({0, 10, 20, 30, 40},
                                                    1000, 1000, 2);
    std::vector<int64_t> frames = {0, 2000, 4000, 6000};
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult r = computeStabilization(raw, sync, frames,
                                                       nullptr, true, false, p);
    REQUIRE(r.frames.size() == 4);
    CHECK(r.frames[0].status == StabilizationStatus::LowOverlap);
    CHECK(r.frames[1].status == StabilizationStatus::Ok);
    CHECK(r.frames[2].status == StabilizationStatus::Ok);
    CHECK(r.frames[3].status == StabilizationStatus::LowOverlap);
    for (const auto& f : r.frames)
        for (int i = 0; i < 9; ++i)
            CHECK(std::isfinite(f.h[i]));
    CHECK(r.relativeAngleDeg[0] == doctest::Approx(10.0).epsilon(1e-9));
    CHECK(r.relativeAngleDeg[1] == doctest::Approx(0.0).epsilon(1e-12));
    CHECK(r.relativeAngleDeg[2] == doctest::Approx(20.0).epsilon(1e-9));
    CHECK(r.relativeAngleDeg[3] == doctest::Approx(30.0).epsilon(1e-9));
}

TEST_CASE("computeStabilization: Smooth strength 0.5 corrects for real on the frame grid") {
    // Audit fix 2026-11-12: the redesigned tau map puts 0.5 at tau ≈
    // 129 ms; the frame grid is 33.3 ms, so alpha ≈ 0.23 per interval —
    // the two-pass response measurably separates the smoothed target
    // from the actual on a 0.5 Hz wobble. The OLD map put 0.5 at
    // tau = 31.6 µs (a near no-op, exp underflow) — the pin this test
    // replaces. The new pin: the midpoint is a REAL correction, and
    // the crop it needs stays modest (the honest default).
    const int n = 90; // 3 s at 30 fps
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 10.0 * std::sin(2.0 * kPi * 0.5 * double(i) / 30.0);
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p; // Smooth, strength default 0.5
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                       raw.timestampsNs,
                                                       nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    double maxAngle = 0.0;
    for (double a : r.relativeAngleDeg)
        maxAngle = std::fmax(maxAngle, a);
    MESSAGE("smooth-0.5 on frame grid: maxAngle=" << maxAngle
            << " requiredZoom=" << r.requiredZoom);
    CHECK(maxAngle > 0.05);  // the target really moved relative to actual
    CHECK(maxAngle < 10.0);  // ...but never diverges absurdly
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.5);
    CHECK(r.appliedZoom == r.requiredZoom);
    for (const auto& f : r.frames)
        CHECK(f.status == StabilizationStatus::Ok);
}

TEST_CASE("computeStabilization: Smooth strength 0.95 stabilizes for real") {
    // tau(0.95) ≈ 0.815 s (the redesigned map; the pre-2026-11-12 map
    // put it at 0.355 s) over a 3 s 0.5 Hz sweep: the target lags the
    // actual motion, so q_rel is non-trivial and the crop engages.
    const int n = 90;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 10.0 * std::sin(2.0 * kPi * 0.5 * double(i) / 30.0);
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.strength = 0.95;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                       raw.timestampsNs,
                                                       nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    double maxAngle = 0.0;
    for (double a : r.relativeAngleDeg)
        maxAngle = std::fmax(maxAngle, a);
    MESSAGE("smooth-0.95 on frame grid: maxAngle=" << maxAngle
            << " requiredZoom=" << r.requiredZoom);
    CHECK(maxAngle > 0.05);   // the target really moved relative to actual
    CHECK(maxAngle < 10.0);   // ...but never diverges absurdly
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.5);
    CHECK(r.appliedZoom == r.requiredZoom);
    // The zoom is clip-wide uniform.
    for (const auto& f : r.frames)
        CHECK(f.zoom == r.appliedZoom);
}

TEST_CASE("computeStabilization: LockFirstFrame pins the first frame") {
    // A clip that pans 1 deg/frame: locking the first frame makes
    // every later frame's q_rel the accumulated pan (0, 1, 2, ...).
    const int n = 20;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = double(i);
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                       raw.timestampsNs,
                                                       nullptr, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    CHECK(r.relativeAngleDeg[0] == doctest::Approx(0.0).epsilon(1e-12));
    // Frame i's q_rel is R(-i deg) about Z: angle grows linearly.
    for (int i = 1; i < n; ++i)
        CHECK(r.relativeAngleDeg[i] == doctest::Approx(double(i)).epsilon(1e-9));
    double maxAngle = 0.0;
    for (double a : r.relativeAngleDeg)
        maxAngle = std::fmax(maxAngle, a);
    MESSAGE("lock-first: maxAngle=" << maxAngle
            << " requiredZoom=" << r.requiredZoom);
    CHECK(maxAngle > 5.0);
    CHECK(r.requiredZoom > 1.01);
    CHECK(r.requiredZoom < 1.8);
}

TEST_CASE("computeStabilization: HorizonLock levels a rolled camera") {
    // Constant +20 deg roll on a level camera, gravity in the camera
    // frame = R(conj(q))·(0,-1,0) = (-sin20, -cos20, 0). The lock must
    // rotate the target so the horizon is level: every frame's q_rel
    // is the pure -20 deg roll (the lock applied to a static target),
    // the H is the pure roll block, and the crop is the roll closed
    // form at 20 deg (~1.5477 at 3840x2160... actually the closed form
    // below), all frames Ok, horizonLockDegraded false.
    const int n = 30;
    const double rollDeg = 20.0;
    double qRoll[4];
    quatAxisDeg(2, rollDeg, qRoll);
    OrientationSeries raw;
    raw.timestampsNs.reserve(n);
    raw.quats.reserve(4 * n);
    for (int i = 0; i < n; ++i) {
        raw.timestampsNs.push_back(int64_t(i) * 33333333);
        for (int k = 0; k < 4; ++k)
            raw.quats.push_back(qRoll[k]);
    }
    // Gravity in the rolled camera frame.
    double qRollC[4];
    quatConj(qRoll, qRollC);
    const double gWorld[3] = {0.0, -1.0, 0.0};
    double gCam[3];
    quatRotate(qRollC, gWorld, gCam);
    // Independent check of the scenario: |g.xy| = 1, roll = +20 deg.
    REQUIRE(std::sqrt(gCam[0] * gCam[0] + gCam[1] * gCam[1])
            == doctest::Approx(1.0).epsilon(1e-12));

    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::HorizonLock;
    p.strength = 0.0; // static target: the lock is the only motion
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult r = computeStabilization(raw, sync,
                                                       raw.timestampsNs,
                                                       gCam, true, false, p);
    REQUIRE(r.frames.size() == size_t(n));
    CHECK_FALSE(r.horizonLockDegraded);
    for (int i = 0; i < n; ++i) {
        // q_rel = conj(q_target')·q_actual with q_target' leveled:
        // the lock undoes the roll, so q_rel is the -20 deg roll —
        // angle exactly 20 deg.
        CHECK(r.relativeAngleDeg[i] == doctest::Approx(rollDeg).epsilon(1e-9));
        // H is the pure roll block (row 3 exactly (0,0,1)).
        CHECK(r.frames[i].h[6] == doctest::Approx(0.0).epsilon(1e-12));
        CHECK(r.frames[i].h[7] == doctest::Approx(0.0).epsilon(1e-12));
        CHECK(r.frames[i].h[8] == doctest::Approx(1.0).epsilon(1e-12));
        CHECK(r.frames[i].status == StabilizationStatus::Ok);
    }
    // The crop is the roll closed form at 20 deg — the four corner
    // constraints at the DEFAULT principal point (w/2, h/2; the same
    // derivation as "pitch costs more zoom than roll": H roll block
    // [[c,s],[-s,c]] about c, composite M = Hinv * S, the four binding
    // |corner extent| terms; the height pair binds at 16:9).
    const double s20 = std::sin(deg2rad(20.0));
    const double c20 = std::cos(deg2rad(20.0));
    const double hw = 3840.0 / 2.0, hh = 2160.0 / 2.0;
    const double rollClosed = std::fmax(
        std::fmax(((hw - 1.0) * c20 + hh * s20) / (hw - 1.0),
                  (hw * c20 + (hh - 1.0) * s20) / hw),
        std::fmax(((hw - 1.0) * s20 + (hh - 1.0) * c20) / (hh - 1.0),
                  (hw * s20 + hh * c20) / hh));
    CHECK(r.requiredZoom == doctest::Approx(rollClosed).epsilon(1e-9));
    MESSAGE("horizon-lock: requiredZoom=" << r.requiredZoom
            << " (roll closed form " << rollClosed << ")");

    // Budget-short: required (~1.55) exceeds the default limit (1.3),
    // so the correction scales down — the audit fix's core contract.
    // Every frame's scale lands strictly inside (0,1) (a real, visible
    // reduction, never the full correction and never identity), the
    // clip-wide a_clip and the min/mean reductions order as pinned in
    // Stabilization.h, and the anti-smear re-verification holds: every
    // SHIPPED H re-checks clean at appliedZoom under the sampler-exact
    // footprint (invalidSampleFrames == 0 — the executable proof that
    // warpFrame never edge-clamps this plan).
    CHECK(r.requiredZoom > r.appliedZoom);
    CHECK(r.appliedZoom == 1.3);
    REQUIRE(r.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i) {
        CHECK(r.correctionScale[i] > 0.0);
        CHECK(r.correctionScale[i] < 1.0);
    }
    CHECK(r.clipCorrectionScale > 0.0);
    CHECK(r.clipCorrectionScale < 1.0);
    CHECK(r.minCorrectionScale > 0.0);
    CHECK(r.minCorrectionScale < 1.0);
    CHECK(r.meanCorrectionScale > 0.0);
    CHECK(r.meanCorrectionScale < 1.0);
    CHECK(r.clipCorrectionScale <= r.minCorrectionScale);
    CHECK(r.minCorrectionScale <= r.meanCorrectionScale);
    CHECK(r.meanCorrectionScale <= 1.0);
    CHECK(r.invalidSampleFrames == 0);
    for (int i = 0; i < n; ++i)
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        1920.0, 1080.0, 3840, 2160) <= 0.0);
    MESSAGE("horizon-lock: clipScale=" << r.clipCorrectionScale
            << " minScale=" << r.minCorrectionScale
            << " meanScale=" << r.meanCorrectionScale);
}

TEST_CASE("computeStabilization: HorizonLock degrades without gravity") {
    // No gravity pointer / zero gravity -> horizonLockDegraded true,
    // and the result equals plain Smooth at the same strength.
    const int n = 30;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 8.0 * std::sin(2.0 * kPi * 0.4 * double(i) / 30.0);
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::HorizonLock;
    p.strength = 0.95;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    const StabilizationResult degraded = computeStabilization(raw, sync,
                                                              raw.timestampsNs,
                                                              nullptr, true, false, p);
    CHECK(degraded.horizonLockDegraded);
    const double zeroG[3] = {0.0, 0.0, 0.0};
    const StabilizationResult zero = computeStabilization(raw, sync,
                                                          raw.timestampsNs,
                                                          zeroG, true, false, p);
    CHECK(zero.horizonLockDegraded);
    // Gravity along the view axis: roll unobservable -> degraded.
    const double viewAxis[3] = {0.0, 0.0, -1.0};
    const StabilizationResult view = computeStabilization(raw, sync,
                                                          raw.timestampsNs,
                                                          viewAxis, true, false, p);
    CHECK(view.horizonLockDegraded);
    // Degraded == plain Smooth (the flagged fallback is EXACTLY plain
    // smoothing — the lock adds nothing when it cannot fire).
    StabilizationParams plain = p;
    plain.mode = StabilizationMode::Smooth;
    const StabilizationResult smooth = computeStabilization(raw, sync,
                                                            raw.timestampsNs,
                                                            nullptr, true, false, plain);
    REQUIRE(smooth.frames.size() == degraded.frames.size());
    for (size_t i = 0; i < smooth.frames.size(); ++i) {
        for (int k = 0; k < 9; ++k)
            CHECK(std::fabs(degraded.frames[i].h[k] - smooth.frames[i].h[k]) < 1e-15);
        CHECK(std::fabs(degraded.relativeAngleDeg[i] - smooth.relativeAngleDeg[i]) < 1e-15);
    }
}

TEST_CASE("computeStabilization: zoom limit clamps applied, required stays honest") {
    // A clip panning to exactly 8 deg (LockFirstFrame) needs the roll
    // closed form at 8 deg (~1.238); with the user limit at 1.1:
    // appliedZoom == 1.1, requiredZoom stays honest (~1.238) — the UI
    // can show "stabilization cropped X%, limit reached".
    const int n = 10;
    std::vector<double> angles(n);
    for (int i = 0; i < n; ++i)
        angles[i] = 8.0 * double(i) / double(n - 1); // 0 -> 8 deg
    const OrientationSeries raw = seriesFromAngles(angles, 0, 33333333, 2);
    SyncModel sync;
    StabilizationParams p;
    p.mode = StabilizationMode::LockFirstFrame;
    p.focalLengthPx = 2554.0;
    p.width = 3840;
    p.height = 2160;
    p.zoomLimit = 1.1;
    StabilizationResult r = computeStabilization(raw, sync, raw.timestampsNs,
                                                 nullptr, true, false, p);
    const double rollClosed8 = [&] {
        // The four-constraint default-center form (same derivation as
        // the "pitch costs more zoom" pin; the height pair binds).
        const double s8 = std::sin(deg2rad(8.0));
        const double c8 = std::cos(deg2rad(8.0));
        const double hw = 3840.0 / 2.0, hh = 2160.0 / 2.0;
        return std::fmax(
            std::fmax(((hw - 1.0) * c8 + hh * s8) / (hw - 1.0),
                      (hw * c8 + (hh - 1.0) * s8) / hw),
            std::fmax(((hw - 1.0) * s8 + (hh - 1.0) * c8) / (hh - 1.0),
                      (hw * s8 + hh * c8) / hh));
    }();
    MESSAGE("zoom-limit: required=" << r.requiredZoom
            << " applied=" << r.appliedZoom);
    CHECK(r.requiredZoom == doctest::Approx(rollClosed8).epsilon(1e-9));
    CHECK(r.appliedZoom == 1.1);
    // required stays honest (unclamped).
    CHECK(r.requiredZoom > r.appliedZoom);
    for (const auto& f : r.frames)
        CHECK(f.zoom == 1.1);

    // Budget-short (required ~1.238 > limit 1.1): the correction SCALES
    // DOWN to fit the budget instead of edge-clamping out-of-source
    // samples into smear — the audit fix's core contract. The pan grows
    // to 8 deg, so the late frames need the most reduction (their
    // scale is the smallest); frame 0's q_rel is the identity (0 deg),
    // so its scale stays 1.0 (nothing to reduce). Scales live in
    // (0,1], min/mean in (0,1), every shipped H re-checks clean at
    // appliedZoom under the sampler-exact footprint, and the same clip
    // at a GENEROUS limit needs strictly less reduction (monotone in
    // the budget: scales non-decreasing as the limit grows, with the
    // sufficient limit 1.3 leaving everything exactly 1.0 — the
    // compatibility pin).
    REQUIRE(r.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i) {
        CHECK(r.correctionScale[i] > 0.0);
        CHECK(r.correctionScale[i] <= 1.0);
    }
    // The reduction follows the pan: bCap is non-increasing along the
    // 0 -> 8 deg ramp, and the sliding-min envelope + symmetric
    // triangular kernel preserve that ordering (a moving average of a
    // monotone sequence under a symmetric non-negative kernel is
    // monotone the same way) — so the scales are non-increasing and
    // the LAST frame (the full 8 deg pan) carries the minimum. Frame
    // 0's own q_rel is the identity, but its scale is still < 1: the
    // time window (0.5 s > the clip's whole 0.3 s span) pulls every
    // frame toward the clip's worst cap — smoothing is the point.
    for (int i = 1; i < n; ++i)
        CHECK(r.correctionScale[i] <= r.correctionScale[i - 1] + 1e-12);
    CHECK(r.minCorrectionScale > 0.0);
    CHECK(r.minCorrectionScale < 1.0);
    CHECK(r.meanCorrectionScale > 0.0);
    CHECK(r.meanCorrectionScale < 1.0);
    CHECK(r.invalidSampleFrames == 0);
    for (const auto& f : r.frames)
        CHECK(samplerFootprintViolation(f.h, f.zoom, 1920.0, 1080.0,
                                        3840, 2160) <= 0.0);
    MESSAGE("zoom-limit: minScale=" << r.minCorrectionScale
            << " meanScale=" << r.meanCorrectionScale
            << " clipScale=" << r.clipCorrectionScale);
    // The reduction follows the pan: the last frame needs the most.
    CHECK(r.correctionScale[n - 1] == doctest::Approx(r.minCorrectionScale).epsilon(1e-12));
    // Monotone in the budget: raising the limit never reduces a scale.
    StabilizationParams looser = p;
    looser.zoomLimit = 1.2;
    const StabilizationResult rLoose =
        computeStabilization(raw, sync, raw.timestampsNs,
                             nullptr, true, false, looser);
    REQUIRE(rLoose.correctionScale.size() == size_t(n));
    for (int i = 0; i < n; ++i)
        CHECK(rLoose.correctionScale[i] >= r.correctionScale[i] - 1e-12);
    CHECK(rLoose.minCorrectionScale >= r.minCorrectionScale - 1e-12);
    // A SUFFICIENT budget (limit >= required ~1.238) leaves every
    // scale exactly 1.0 — the bit-identical compatibility contract.
    StabilizationParams ample = p;
    ample.zoomLimit = 1.3;
    const StabilizationResult rAmple =
        computeStabilization(raw, sync, raw.timestampsNs,
                             nullptr, true, false, ample);
    REQUIRE(rAmple.correctionScale.size() == size_t(n));
    CHECK(rAmple.appliedZoom == rAmple.requiredZoom);
    for (int i = 0; i < n; ++i)
        CHECK(rAmple.correctionScale[i] == 1.0);
    CHECK(rAmple.clipCorrectionScale == 1.0);
    CHECK(rAmple.minCorrectionScale == 1.0);
    CHECK(rAmple.meanCorrectionScale == 1.0);
    CHECK(rAmple.invalidSampleFrames == 0);
    // A limit below 1 is treated as 1 (never zoom OUT).
    StabilizationParams weird = p;
    weird.zoomLimit = 0.5;
    r = computeStabilization(raw, sync, raw.timestampsNs,
                             nullptr, true, false, weird);
    CHECK(r.appliedZoom == std::fmin(r.requiredZoom, 1.0));
    CHECK(r.appliedZoom == 1.0);
    // Assumed focal flags every frame IntrinsicsAssumed.
    StabilizationParams assumed = p;
    r = computeStabilization(raw, sync, raw.timestampsNs,
                             nullptr, true, true, assumed);
    CHECK(r.intrinsicsAssumed);
    for (const auto& f : r.frames)
        CHECK(f.status == StabilizationStatus::IntrinsicsAssumed);
}

// ---------------------------------------------------------------------------
// Real-fixture evidence (deliverable 6) — tier 3
//
// Reads the gitignored STEPS/media/test pairs when present (skips
// otherwise, like test_sync/test_orientation_estimate). The pipeline
// is the real one Phase 5's renderer will drive: container clocks ->
// anchored sync -> VQF orientation -> container focal -> the clip API.
// The NUMBERS are the deliverable: quoted via MESSAGE, pinned within
// honest bounds (never fabricated — the pins are re-derived from the
// first real run).
// ---------------------------------------------------------------------------

namespace {

// True when the real fixture exists (gitignored STEPS/ tree).
bool stFixtureAvailable(const char* relpath) {
    std::FILE* f = std::fopen(relpath, "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

// The GCSV header "timestamp" (epoch ms) — the gyro log's wall start.
long long stGcsvHeaderTimestampMs(const std::string& path) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f)
        return -1;
    std::string text;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0)
        text.append(buf, n);
    std::fclose(f);
    const std::string key = "\ntimestamp,";
    const size_t at = text.find(key);
    if (at == std::string::npos)
        return -1;
    return std::atoll(text.c_str() + at + key.size());
}

// Exact per-frame capture ns + frame-0 recvdTimestampMs from the
// container via the vendored decoder (the same read-only pattern as
// Gyro.cpp / test_sync.cpp).
struct StContainerClocks {
    std::vector<int64_t> captureNs;
    long long frame0RecvdMs = -1;
};

StContainerClocks stContainerClocks(const std::string& path) {
    StContainerClocks cc;
    try {
        motioncam::Decoder decoder(path);
        const auto& ts = decoder.getFrames();
        cc.captureNs.assign(ts.begin(), ts.end());
        if (!ts.empty()) {
            nlohmann::json meta;
            decoder.loadFrameMetadata(ts[0], meta);
            if (meta.contains("recvdTimestampMs")) {
                if (meta["recvdTimestampMs"].is_string())
                    cc.frame0RecvdMs =
                        std::stoll(meta["recvdTimestampMs"].get<std::string>());
                else if (meta["recvdTimestampMs"].is_number())
                    cc.frame0RecvdMs = meta["recvdTimestampMs"].get<long long>();
            }
        }
    } catch (...) {
    }
    return cc;
}

// Fixture IMU: parse + remap into the canonical frame (both fixtures
// declare "XYZ", composing to the quarter turn — test_orientation).
struct StFixtureImu {
    std::vector<ImuSample> gyro; // canonical
    std::vector<ImuSample> accel;
};

StFixtureImu stLoadFixture(const char* relpath) {
    StFixtureImu f;
    auto res = parseGcsv(relpath);
    REQUIRE(res.isOk());
    const ImuData data = *res.value;
    const AxisRemap remap = axisRemapForImu(data);
    REQUIRE(remap.known);
    f.gyro.reserve(data.gyro.size());
    for (const ImuSample& s : data.gyro) {
        float x = s.x, y = s.y, z = s.z;
        remap.apply(x, y, z);
        f.gyro.push_back(ImuSample{s.timestampNs, x, y, z});
    }
    f.accel.reserve(data.accel.size());
    for (const ImuSample& s : data.accel) {
        float x = s.x, y = s.y, z = s.z;
        remap.apply(x, y, z);
        f.accel.push_back(ImuSample{s.timestampNs, x, y, z});
    }
    return f;
}

// The whole real pipeline for one fixture, shared by the evidence
// tests: clocks -> anchored sync -> VQF -> focal -> computeStabilization.
struct StFixturePipeline {
    SyncModel sync;
    bool anchored = false;
    OrientationSeries orientation;
    double gravityCamera[3] = {0, 0, 0};
    std::vector<int64_t> frameTs;
    double focalMm = 0.0;
    double focalPx = 0.0;
    int width = 0, height = 0;
};

StFixturePipeline stRunPipeline(const char* gcsvPath, const char* mcrawPath) {
    StFixturePipeline p;
    const StContainerClocks cc = stContainerClocks(mcrawPath);
    REQUIRE(!cc.captureNs.empty());
    REQUIRE(cc.frame0RecvdMs > 0);
    p.frameTs = cc.captureNs;
    const long long hdrMs = stGcsvHeaderTimestampMs(gcsvPath);
    REQUIRE(hdrMs > 0);
    const SyncEstimate est = estimateOffsetFromAnchors(
        cc.frame0RecvdMs, hdrMs, cc.captureNs.front());
    REQUIRE(est.isAnchored());
    p.sync = est.model;
    p.anchored = true;
    const StFixtureImu f = stLoadFixture(gcsvPath);
    const VqfOrientation v = estimateOrientationVqf(f.gyro, f.accel);
    REQUIRE(!v.series.empty());
    p.orientation = v.series;
    for (int k = 0; k < 3; ++k)
        p.gravityCamera[k] = v.gravityCamera[k];
    // The container's 35mm-equivalent focal via Source (the honest
    // path the app will use), then the flagged px fallback.
    auto src = Source::open(mcrawPath);
    REQUIRE(src.isOk());
    p.focalMm = (*src)->rawMeta().focalLengthMm;
    REQUIRE(p.focalMm > 0.0);
    const FrameInfo fi = (*src)->frameInfo(0);
    p.width = static_cast<int>(fi.width);
    p.height = static_cast<int>(fi.height);
    REQUIRE(p.width > 0);
    REQUIRE(p.height > 0);
    p.focalPx = focalLengthPxFromEquivalent(p.focalMm, p.width);
    REQUIRE(p.focalPx > 0.0);
    return p;
}

// The anti-smear proof shared by the fixture tests (audit fix
// 2026-11-13): every SHIPPED frame's H, at the zoom the plan ships,
// keeps every sampled source coordinate inside [0,w-1]x[0,h-1] with a
// finite positive homogeneous denominator — warpFrame never
// edge-clamps a single tap of this plan. Also pins the scale honesty:
// in [0,1] per frame, min/mean/clip consistent with the fields, and
// the invalidSampleFrames counter exactly 0.
void stCheckPlanHonesty(const StabilizationResult& r, int width, int height) {
    REQUIRE(r.frames.size() == r.correctionScale.size());
    const double cx = static_cast<double>(width) / 2.0;
    const double cy = static_cast<double>(height) / 2.0;
    double aMin = 1.0, aSum = 0.0;
    for (size_t i = 0; i < r.frames.size(); ++i) {
        CHECK(r.correctionScale[i] >= 0.0);
        CHECK(r.correctionScale[i] <= 1.0);
        CHECK(r.frames[i].zoom == r.appliedZoom);
        CHECK(samplerFootprintViolation(r.frames[i].h, r.frames[i].zoom,
                                        cx, cy, width, height) <= 0.0);
        aMin = std::fmin(aMin, r.correctionScale[i]);
        aSum += r.correctionScale[i];
    }
    CHECK(r.invalidSampleFrames == 0);
    CHECK(r.minCorrectionScale == doctest::Approx(aMin).epsilon(1e-12));
    if (!r.frames.empty())
        CHECK(r.meanCorrectionScale
              == doctest::Approx(aSum / double(r.frames.size())).epsilon(1e-12));
}

} // namespace

TEST_CASE("fixture evidence: 261110_004150 stabilization numbers") {
    const char* gcsv = "STEPS/media/test/261110_004150_VIDEO_24mm.gcsv";
    const char* mcraw = "STEPS/media/test/261110_004150_VIDEO_24mm.mcraw";
    if (!stFixtureAvailable(gcsv) || !stFixtureAvailable(mcraw)) {
        MESSAGE("SKIP: fixtures not present (gitignored)");
        return;
    }
    const StFixturePipeline p = stRunPipeline(gcsv, mcraw);
    MESSAGE("004150: frames=" << p.frameTs.size()
            << " focalMm=" << p.focalMm << " focalPx=" << p.focalPx
            << " (" << p.width << "x" << p.height << ")");
    REQUIRE(p.frameTs.size() == 310);
    REQUIRE(p.focalMm == doctest::Approx(23.950197219848633).epsilon(1e-12));
    REQUIRE(p.width == 3840);
    REQUIRE(p.height == 2160);

    StabilizationParams par;
    par.focalLengthPx = p.focalPx;
    par.width = p.width;
    par.height = p.height;

    // The audit fix 2026-11-12 strength sweep: the same real pipeline at
    // 0.3/0.5/0.7/0.9/1.0. The numbers are the deliverable — quoted per
    // strength, pinned within honest bounds. The old map measured 0.00
    // deg at 0.70 here; the redesigned map must make EVERY mid strength
    // a visible correction, and 1.0 stays near the strongest.
    par.mode = StabilizationMode::Smooth;
    static const double kSweep[] = {0.3, 0.5, 0.7, 0.9, 1.0};
    double prevAngle = -1.0;
    for (double s : kSweep) {
        par.strength = s;
        StabilizationResult rs = computeStabilization(p.orientation, p.sync,
                                                      p.frameTs, p.gravityCamera,
                                                      true, true, par);
        MESSAGE("004150 smooth-" << s << ": maxAngle=" << stMaxAngle(rs)
                << " requiredZoom=" << rs.requiredZoom
                << " appliedZoom=" << rs.appliedZoom);
        CHECK(stMaxAngle(rs) > 0.05);  // visible correction at every strength
        CHECK(stMaxAngle(rs) < 10.0);  // ...but never diverges absurdly
        CHECK(rs.requiredZoom > 1.0);
        CHECK(rs.requiredZoom < 1.5);
        CHECK(rs.appliedZoom == std::fmin(rs.requiredZoom, par.zoomLimit));
        // Monotone: stronger smooths at least as much (the sweep's
        // ordering contract; 1.0 is near the strongest).
        if (prevAngle >= 0.0)
            CHECK(stMaxAngle(rs) >= prevAngle - 1e-9);
        prevAngle = stMaxAngle(rs);
    }
    CHECK(prevAngle > 0.05); // the strongest strength corrected for real

    // Smooth 0.5 (the new default): the visible midpoint — pinned tight.
    par.strength = 0.5;
    StabilizationResult r = computeStabilization(p.orientation, p.sync,
                                                 p.frameTs, p.gravityCamera,
                                                 true, true, par);
    MESSAGE("004150 smooth-0.5: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom);
    CHECK(stMaxAngle(r) > 0.05);
    CHECK(stMaxAngle(r) < 10.0);
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.5);
    CHECK(r.intrinsicsAssumed);
    for (const auto& fr : r.frames)
        CHECK(fr.status == StabilizationStatus::IntrinsicsAssumed);

    // Smooth 0.95: real smoothing — the numbers the phase must quote.
    par.strength = 0.95;
    r = computeStabilization(p.orientation, p.sync, p.frameTs, p.gravityCamera,
                             true, true, par);
    MESSAGE("004150 smooth-0.95: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom
            << " appliedZoom=" << r.appliedZoom);
    CHECK(stMaxAngle(r) > 0.05);
    CHECK(stMaxAngle(r) < 10.0);
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.5);
    CHECK(r.appliedZoom == std::fmin(r.requiredZoom, par.zoomLimit));
    // The anti-smear proof on the REAL fixture (audit fix 2026-11-13):
    // whatever the budget decides, every SHIPPED H re-checks clean at
    // its applied zoom under the sampler-exact footprint — warpFrame
    // never edge-clamps this plan — and the scales report honestly
    // (all 1.0 when the budget suffices; in (0,1] when it is short).
    stCheckPlanHonesty(r, p.width, p.height);
    MESSAGE("004150 smooth-0.95 scales: clip=" << r.clipCorrectionScale
            << " min=" << r.minCorrectionScale
            << " mean=" << r.meanCorrectionScale);

    // LockFirstFrame: what it measures is the start-to-end DISPLACEMENT
    // from the first frame's pose, NOT the clip's path length. The VQF
    // path over the whole log is ~47.8 deg (Phase 2's number), but this
    // clip is handheld wobble around a roughly fixed pose: the frames
    // stay within ~1 deg of the first frame. Quoted: maxAngle=1.0167.
    par.mode = StabilizationMode::LockFirstFrame;
    r = computeStabilization(p.orientation, p.sync, p.frameTs, p.gravityCamera,
                             true, true, par);
    MESSAGE("004150 lock-first: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom);
    CHECK(stMaxAngle(r) > 0.5);
    CHECK(stMaxAngle(r) < 2.0);
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.2);

    // The smoothed-vs-raw residual cross-check: the API's angles ARE
    // smoothOrientationSeries(actual-on-frames) vs actual — to 1e-9.
    par.mode = StabilizationMode::Smooth;
    par.strength = 0.95;
    r = computeStabilization(p.orientation, p.sync, p.frameTs, p.gravityCamera,
                             true, true, par);
    OrientationSeries actualOnFrames;
    actualOnFrames.timestampsNs = p.frameTs;
    actualOnFrames.quats.resize(4 * p.frameTs.size());
    for (size_t i = 0; i < p.frameTs.size(); ++i)
        p.orientation.orientationAt(p.sync.toImu(p.frameTs[i]),
                                     &actualOnFrames.quats[4 * i]);
    const OrientationSeries smoothed =
        smoothOrientationSeries(actualOnFrames, 0.95);
    double worst = 0.0;
    for (size_t i = 0; i < p.frameTs.size(); ++i) {
        double qRel[4];
        relativeRotation(&smoothed.quats[4 * i], &actualOnFrames.quats[4 * i], qRel);
        worst = std::fmax(worst, std::fabs(relativeRotationAngleDeg(qRel)
                                           - r.relativeAngleDeg[i]));
    }
    MESSAGE("004150 residual cross-check: worst=" << worst << " deg");
    CHECK(worst < 1e-9);
}

TEST_CASE("fixture evidence: 261110_004208 stabilization numbers") {
    const char* gcsv = "STEPS/media/test/261110_004208_VIDEO_24mm.gcsv";
    const char* mcraw = "STEPS/media/test/261110_004208_VIDEO_24mm.mcraw";
    if (!stFixtureAvailable(gcsv) || !stFixtureAvailable(mcraw)) {
        MESSAGE("SKIP: fixtures not present (gitignored)");
        return;
    }
    const StFixturePipeline p = stRunPipeline(gcsv, mcraw);
    MESSAGE("004208: frames=" << p.frameTs.size()
            << " focalMm=" << p.focalMm << " focalPx=" << p.focalPx
            << " (" << p.width << "x" << p.height << ")");
    REQUIRE(p.frameTs.size() == 310);
    REQUIRE(p.focalMm == doctest::Approx(23.950197219848633).epsilon(1e-12));
    REQUIRE(p.width == 3840);
    REQUIRE(p.height == 2160);

    StabilizationParams par;
    par.focalLengthPx = p.focalPx;
    par.width = p.width;
    par.height = p.height;

    // The audit fix 2026-11-12 strength sweep (same contract as 004150:
    // every mid strength a visible correction, monotone up to 1.0).
    par.mode = StabilizationMode::Smooth;
    static const double kSweep[] = {0.3, 0.5, 0.7, 0.9, 1.0};
    double prevAngle = -1.0;
    for (double s : kSweep) {
        par.strength = s;
        StabilizationResult rs = computeStabilization(p.orientation, p.sync,
                                                      p.frameTs, p.gravityCamera,
                                                      true, true, par);
        MESSAGE("004208 smooth-" << s << ": maxAngle=" << stMaxAngle(rs)
                << " requiredZoom=" << rs.requiredZoom
                << " appliedZoom=" << rs.appliedZoom);
        CHECK(stMaxAngle(rs) > 0.05);
        CHECK(stMaxAngle(rs) < 10.0);
        CHECK(rs.requiredZoom > 1.0);
        CHECK(rs.requiredZoom < 1.5);
        CHECK(rs.appliedZoom == std::fmin(rs.requiredZoom, par.zoomLimit));
        if (prevAngle >= 0.0)
            CHECK(stMaxAngle(rs) >= prevAngle - 1e-9);
        prevAngle = stMaxAngle(rs);
    }

    par.strength = 0.5;
    StabilizationResult r = computeStabilization(p.orientation, p.sync,
                                                 p.frameTs, p.gravityCamera,
                                                 true, true, par);
    MESSAGE("004208 smooth-0.5: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom);
    CHECK(stMaxAngle(r) > 0.05);
    CHECK(stMaxAngle(r) < 10.0);
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.5);

    par.strength = 0.95;
    r = computeStabilization(p.orientation, p.sync, p.frameTs, p.gravityCamera,
                             true, true, par);
    MESSAGE("004208 smooth-0.95: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom
            << " appliedZoom=" << r.appliedZoom);
    CHECK(stMaxAngle(r) > 0.05);
    CHECK(stMaxAngle(r) < 10.0);
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.5);
    // The anti-smear proof on the REAL fixture (same contract as
    // 004150 above): every shipped H clean at its zoom, scales honest.
    stCheckPlanHonesty(r, p.width, p.height);
    MESSAGE("004208 smooth-0.95 scales: clip=" << r.clipCorrectionScale
            << " min=" << r.minCorrectionScale
            << " mean=" << r.meanCorrectionScale);

    // Same displacement-vs-path story as 004150: VQF path ~56.0 deg,
    // start-to-end displacement ~1.5 deg (handheld wobble around a
    // fixed pose). Quoted: maxAngle=1.47546.
    par.mode = StabilizationMode::LockFirstFrame;
    r = computeStabilization(p.orientation, p.sync, p.frameTs, p.gravityCamera,
                             true, true, par);
    MESSAGE("004208 lock-first: maxAngle=" << stMaxAngle(r)
            << " requiredZoom=" << r.requiredZoom);
    CHECK(stMaxAngle(r) > 1.0);
    CHECK(stMaxAngle(r) < 2.0);
    CHECK(r.requiredZoom > 1.0);
    CHECK(r.requiredZoom < 1.2);
}
