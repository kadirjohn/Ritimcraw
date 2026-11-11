// Orientation estimation tests (stabilization Phase 2, deliverables 2-5
// + 7): gyro integration vs analytic, VQF on synthetic motion and the
// real fixtures, accel resampling, SLERP properties, and orientationAt
// clamping. Inputs are already in the canonical camera frame (the
// estimators never see ImuData::orientation), matching how the later
// pipeline will call them: remap first, estimate second.
//
// Fixture expectations are QUOTED NUMBERS, not just tolerances: the
// mission requires the VQF gravity estimate to be validated against the
// accel mean direction (independent of the filter) and the numbers to
// be reported. The scratch-computed references (independent python of
// the same fixtures) are in the comments.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Gyro.h>
#include <rawedit/Orientation.h>
#include <rawedit/OrientationEstimate.h>

#include <cmath>
#include <cstdio>
#include <sstream>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

constexpr double kPi = 3.14159265358979323846;

ImuSample samp(int64_t t, double x, double y, double z) {
    return ImuSample{t, float(x), float(y), float(z)};
}

// Synthetic gyro: constant angular velocity about one axis, uniform grid.
std::vector<ImuSample> spin(int64_t t0, int64_t dtNs, int n, double wx, double wy, double wz) {
    std::vector<ImuSample> g;
    g.reserve(n);
    for (int i = 0; i < n; ++i)
        g.push_back(samp(t0 + int64_t(i) * dtNs, wx, wy, wz));
    return g;
}

double quatAngleDeg(const double q[4]) {
    double d = std::fmax(-1.0, std::fmin(1.0, q[0]));
    return 2.0 * std::acos(d) * 180.0 / kPi;
}

// Rotation angle between two quaternions (shortest arc).
double angleBetweenDeg(const double a[4], const double b[4]) {
    double d = quatDot(a, b);
    if (d < 0)
        d = -d;
    return 2.0 * std::acos(std::fmin(1.0, d)) * 180.0 / kPi;
}

double vecAngleBetweenDeg(const double a[3], const double b[3]) {
    double na = std::sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    double nb = std::sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
    double d = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (na * nb);
    return std::acos(std::fmax(-1.0, std::fmin(1.0, d))) * 180.0 / kPi;
}

// True when the real fixture exists (gitignored STEPS/ tree).
bool fixtureAvailable(const char* relpath) {
    std::FILE* f = std::fopen(relpath, "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

// Fixture loader: parse + remap into the canonical frame. Both fixtures
// declare orientation "XYZ", which composes to the quarter turn
// canon = (-y, x, z) (test_orientation.cpp verifies the remap itself).
struct FixtureImu {
    ImuData data;
    std::vector<ImuSample> gyro; // canonical
    std::vector<ImuSample> accel;
};

FixtureImu loadFixture(const char* relpath) {
    FixtureImu f;
    auto res = parseGcsv(relpath);
    REQUIRE(res.isOk());
    f.data = *res.value;
    const AxisRemap remap = axisRemapForImu(f.data);
    REQUIRE(remap.known);
    f.gyro.reserve(f.data.gyro.size());
    for (const ImuSample& s : f.data.gyro) {
        float x = s.x, y = s.y, z = s.z;
        remap.apply(x, y, z);
        f.gyro.push_back(samp(s.timestampNs, x, y, z));
    }
    f.accel.reserve(f.data.accel.size());
    for (const ImuSample& s : f.data.accel) {
        float x = s.x, y = s.y, z = s.z;
        remap.apply(x, y, z);
        f.accel.push_back(samp(s.timestampNs, x, y, z));
    }
    return f;
}

} // namespace

// ---------------------------------------------------------------------------
// integrateGyro (deliverable 3)
// ---------------------------------------------------------------------------
TEST_CASE("integration constant angular velocity matches analytic per axis") {
    // 100 deg/s about each canonical axis in turn, on a uniform 200 Hz grid.
    // After n intervals of dt at constant w, the analytic orientation is the
    // rotation of w*n*dt about the axis — the integrator's per-sample
    // exponential steps compose exactly for a single axis (all steps share
    // the axis, so the exponentials commute and add).
    // ImuSample stores float, so the integrator sees float(rate); the
    // analytic expectation must be built from the float-rounded rate or
    // the ~6e-8 relative rounding (174 rad total -> ~1e-5 rad error)
    // dwarfs the 1e-9 tolerance below.
    const double rate = double(float(100.0 * kPi / 180.0)); // rad/s, as stored
    const int64_t dt = int64_t(200e6);       // 200 Hz
    const int n = 500;
    const double total = rate * double(n - 1) * double(dt) * 1e-9;
    const double cases[3][3] = {{rate, 0, 0}, {0, rate, 0}, {0, 0, rate}};
    for (int axis = 0; axis < 3; ++axis) {
        const auto g = spin(1'000'000'000, dt, n, cases[axis][0], cases[axis][1], cases[axis][2]);
        const OrientationSeries s = integrateGyro(g);
        REQUIRE(s.size() == size_t(n));
        // Final quaternion: rotation of `total` rad about the axis.
        const double half = total / 2.0;
        const double expect[4] = {std::cos(half),
                                  std::sin(half) * (axis == 0),
                                  std::sin(half) * (axis == 1),
                                  std::sin(half) * (axis == 2)};
        const double* got = &s.quats[(n - 1) * 4];
        for (int i = 0; i < 4; ++i)
            CHECK(std::fabs(got[i] - expect[i]) < 1e-9);
        // And the axis stays the axis: rotating the axis vector by the
        // final quaternion returns it unchanged.
        double v[3] = {double(axis == 0), double(axis == 1), double(axis == 2)};
        double out[3];
        quatRotate(got, v, out);
        for (int i = 0; i < 3; ++i)
            CHECK(std::fabs(out[i] - v[i]) < 1e-9);
    }
}

TEST_CASE("integration zero motion stays at identity") {
    const auto g = spin(0, int64_t(2e6), 100, 0.0, 0.0, 0.0);
    const OrientationSeries s = integrateGyro(g);
    REQUIRE(s.size() == 100);
    for (size_t i = 0; i < s.size(); ++i)
        for (int c = 0; c < 4; ++c)
            CHECK(s.quats[i * 4 + c] == (c == 0 ? 1.0 : 0.0));
}

TEST_CASE("integration empty input gives an empty series") {
    const OrientationSeries s = integrateGyro({});
    CHECK(s.empty());
    double q[4];
    s.orientationAt(123, q);
    CHECK(q[0] == 1.0); // empty -> identity at any timestamp
}

TEST_CASE("integration single sample is the identity at that timestamp") {
    const OrientationSeries s = integrateGyro({samp(42, 0.1, 0.2, 0.3)});
    REQUIRE(s.size() == 1);
    CHECK(s.firstTs() == 42);
    CHECK(s.quats[0] == 1.0);
}

// ---------------------------------------------------------------------------
// resampleAccelOntoGyro (deliverable 4)
// ---------------------------------------------------------------------------
TEST_CASE("resample interpolates linearly at gyro timestamps") {
    // Accel at 0, 10, 20 ns with values 0, 10, 20 per component; gyro at
    // 5 and 15: exact midpoints 5 and 15.
    const auto accel = std::vector<ImuSample>{samp(0, 0, 0, 0), samp(10, 10, 10, 10), samp(20, 20, 20, 20)};
    const auto gyro = std::vector<ImuSample>{samp(5, 0, 0, 0), samp(15, 0, 0, 0)};
    const auto out = resampleAccelOntoGyro(accel, gyro);
    REQUIRE(out.size() == 2);
    CHECK(out[0].timestampNs == 5);
    CHECK(out[0].x == 5.0f);
    CHECK(out[1].x == 15.0f);
    // All three components interpolate identically.
    CHECK(out[0].y == 5.0f);
    CHECK(out[0].z == 5.0f);
}

TEST_CASE("resample exact hits and constant extension at the ends") {
    const auto accel = std::vector<ImuSample>{samp(10, 1, 2, 3), samp(20, 4, 5, 6)};
    // Before the span: nearest sample (constant extension, not prediction).
    auto out = resampleAccelOntoGyro(accel, {samp(0, 0, 0, 0)});
    REQUIRE(out.size() == 1);
    CHECK(out[0].x == 1.0f);
    CHECK(out[0].timestampNs == 0);
    // After the span.
    out = resampleAccelOntoGyro(accel, {samp(30, 0, 0, 0)});
    REQUIRE(out.size() == 1);
    CHECK(out[0].x == 4.0f);
    // Exact hit.
    out = resampleAccelOntoGyro(accel, {samp(15, 0, 0, 0)});
    REQUIRE(out.size() == 1);
    CHECK(out[0].x == 2.5f); // midpoint of the 10..20 interval
    // Exact hit on a sample timestamp.
    out = resampleAccelOntoGyro(accel, {samp(20, 0, 0, 0)});
    REQUIRE(out.size() == 1);
    CHECK(out[0].x == 4.0f);
}

TEST_CASE("resample equal accel timestamps collapse to the last") {
    const auto accel = std::vector<ImuSample>{samp(10, 1, 1, 1), samp(10, 2, 2, 2), samp(30, 3, 3, 3)};
    auto out = resampleAccelOntoGyro(accel, {samp(10, 0, 0, 0)});
    REQUIRE(out.size() == 1);
    CHECK(out[0].x == 2.0f); // the LAST of the duplicate pair
    // Interpolation past the collapsed pair uses the survivor.
    out = resampleAccelOntoGyro(accel, {samp(20, 0, 0, 0)});
    REQUIRE(out.size() == 1);
    CHECK(out[0].x == 2.5f);
}

TEST_CASE("resample empty accel emits zero samples on the gyro stamps") {
    const auto gyro = std::vector<ImuSample>{samp(0, 0, 0, 0), samp(10, 0, 0, 0)};
    const auto out = resampleAccelOntoGyro({}, gyro);
    REQUIRE(out.size() == 2);
    for (const ImuSample& s : out) {
        CHECK(s.x == 0.0f);
        CHECK(s.y == 0.0f);
        CHECK(s.z == 0.0f);
    }
    CHECK(out[1].timestampNs == 10);
    // Empty gyro: no output at all.
    CHECK(resampleAccelOntoGyro({samp(0, 1, 1, 1)}, {}).empty());
}

// ---------------------------------------------------------------------------
// SLERP + orientationAt (deliverable 5)
// ---------------------------------------------------------------------------
TEST_CASE("slerp midpoint and endpoints are exact") {
    // Quarter turn about z: q0 = identity, q1 = 90 deg about z.
    const double q0[4] = {1, 0, 0, 0};
    const double q1[4] = {std::cos(kPi / 4), 0, 0, std::sin(kPi / 4)};
    double m[4];
    slerp(q0, q1, 0.5, m);
    // Midpoint of a quarter turn: 45 deg about z, the exact square root.
    const double expect[4] = {std::cos(kPi / 8), 0, 0, std::sin(kPi / 8)};
    for (int i = 0; i < 4; ++i)
        CHECK(std::fabs(m[i] - expect[i]) < 1e-12);
    // Endpoints.
    double a[4], b[4];
    slerp(q0, q1, 0.0, a);
    slerp(q0, q1, 1.0, b);
    for (int i = 0; i < 4; ++i) {
        CHECK(std::fabs(a[i] - q0[i]) < 1e-12);
        CHECK(std::fabs(b[i] - q1[i]) < 1e-12);
    }
}

TEST_CASE("slerp same-axis rotations stay on the axis and add angles") {
    // 30 deg and 70 deg about x: the interpolated quaternion must stay a
    // pure x rotation and its angle must move linearly from 30 to 70.
    const double q0[4] = {std::cos(kPi / 12), std::sin(kPi / 12), 0, 0};
    const double q1[4] = {std::cos(kPi * 7 / 36), std::sin(kPi * 7 / 36), 0, 0};
    for (double t : {0.0, 0.125, 0.25, 0.5, 0.75, 0.875, 1.0}) {
        double q[4];
        slerp(q0, q1, t, q);
        CHECK(std::fabs(q[2]) < 1e-12);
        CHECK(std::fabs(q[3]) < 1e-12);
        const double angle = 2.0 * std::acos(std::fmin(1.0, q[0])) * 180.0 / kPi;
        CHECK(std::fabs(angle - (30.0 + 40.0 * t)) < 1e-9);
    }
}

TEST_CASE("slerp takes the shortest path across the sign seam") {
    // q1 stored with the opposite sign: same rotation, and the midpoint
    // must be the same as with the positive-sign q1 — not a detour
    // around the sphere.
    const double q0[4] = {1, 0, 0, 0};
    const double q1[4] = {std::cos(kPi / 4), 0, 0, std::sin(kPi / 4)};
    const double q1neg[4] = {-std::cos(kPi / 4), 0, 0, -std::sin(kPi / 4)};
    double m1[4], m2[4];
    slerp(q0, q1, 0.5, m1);
    slerp(q0, q1neg, 0.5, m2);
    // Same rotation (dot magnitude 1), possibly opposite sign.
    const double d = std::fabs(quatDot(m1, m2));
    CHECK(d > 1.0 - 1e-12);
}

TEST_CASE("orientationAt clamps at the ends and interpolates between") {
    // Two samples: identity at t=0, quarter turn about z at t=100.
    OrientationSeries s;
    s.timestampsNs = {0, 100};
    s.quats = {1, 0, 0, 0, std::cos(kPi / 4), 0, 0, std::sin(kPi / 4)};
    double q[4];

    s.orientationAt(-5, q); // before: clamp to first
    CHECK(q[0] == 1.0);
    s.orientationAt(0, q);
    CHECK(q[0] == 1.0);
    s.orientationAt(105, q); // after: clamp to last
    CHECK(std::fabs(q[0] - std::cos(kPi / 4)) < 1e-12);
    s.orientationAt(100, q);
    CHECK(std::fabs(q[0] - std::cos(kPi / 4)) < 1e-12);

    // Midpoint: exact square root of the quarter turn.
    s.orientationAt(50, q);
    CHECK(std::fabs(q[0] - std::cos(kPi / 8)) < 1e-12);
    CHECK(std::fabs(q[3] - std::sin(kPi / 8)) < 1e-12);
    // Quarter point: 22.5 deg.
    s.orientationAt(25, q);
    CHECK(std::fabs(q[0] - std::cos(kPi / 16)) < 1e-12);
    CHECK(std::fabs(q[3] - std::sin(kPi / 16)) < 1e-12);
}

TEST_CASE("orientationAt single-sample series returns it everywhere") {
    OrientationSeries s;
    s.timestampsNs = {500};
    s.quats = {0, 1, 0, 0}; // 180 deg about x
    double q[4];
    s.orientationAt(0, q);
    CHECK(q[1] == 1.0);
    s.orientationAt(500, q);
    CHECK(q[1] == 1.0);
    s.orientationAt(9999, q);
    CHECK(q[1] == 1.0);
}

// ---------------------------------------------------------------------------
// estimateOrientationVqf (deliverables 2 + 7)
// ---------------------------------------------------------------------------
TEST_CASE("vqf zero motion with gravity stays identity and converges bias") {
    // Still camera, gravity along -z (up along +z), constant gyro bias of
    // 1 deg/s about y. VQF must (a) keep the orientation at identity —
    // the bias is absorbed, not integrated — and (b) converge its bias
    // estimate to the injected bias.
    const double biasY = 1.0 * kPi / 180.0;
    const int64_t dt = int64_t(10e6); // 100 Hz
    const int n = 600;                // 6 s: past restMinT (1.5 s)
    std::vector<ImuSample> gyro, accel;
    gyro.reserve(n);
    accel.reserve(n);
    for (int i = 0; i < n; ++i) {
        gyro.push_back(samp(int64_t(i) * dt, 0.0, biasY, 0.0));
        accel.push_back(samp(int64_t(i) * dt, 0.0, 0.0, 9.81));
    }
    const VqfOrientation v = estimateOrientationVqf(gyro, accel);
    REQUIRE(v.series.size() == size_t(n));

    // (a) The final orientation stays identity (bias absorbed). NOT
    // exactly: the bias is a YAW drift (horizontal axis, gravity along z),
    // which accel cannot observe, so the residual from the bias-KF
    // convergence transient (~0.5 s before the estimate settles) is
    // permanent. Observed: 0.0021 quaternion yaw ~ 0.24 deg, vs 6 deg if
    // the bias were integrated uncorrected — the tolerance proves
    // absorption with ~2x headroom on the observed transient.
    const double* qEnd = &v.series.quats[(n - 1) * 4];
    CHECK(std::fabs(qEnd[0] - 1.0) < 5e-3);
    CHECK(std::fabs(qEnd[1]) < 5e-3);
    CHECK(std::fabs(qEnd[2]) < 5e-3);
    CHECK(std::fabs(qEnd[3]) < 5e-3);

    // (b) The bias estimate converged to the injected bias.
    CHECK(std::fabs(v.gyroBias[0]) < 1e-3);
    CHECK(std::fabs(v.gyroBias[1] - biasY) < 1e-3);
    CHECK(std::fabs(v.gyroBias[2]) < 1e-3);

    // (c) Gravity in the camera frame: the camera did not move, so it
    // stays the initial gravity direction, (0, 0, -1). The x component
    // carries the same yaw transient as (a): observed 0.0041 ~ 0.24 deg.
    CHECK(std::fabs(v.gravityCamera[0]) < 1e-2);
    CHECK(std::fabs(v.gravityCamera[1]) < 1e-3);
    CHECK(std::fabs(v.gravityCamera[2] + 1.0) < 1e-2);
}

TEST_CASE("vqf clean spin tracks the integrated angle (integration vs vqf)") {
    // Constant 50 deg/s about z — the GRAVITY axis — no bias, accel (0,0,9.81)
    // consistent with the spin. Spinning about the gravity axis is the clean
    // case: yaw is accel-unobservable (gravity stays along -z in the camera
    // frame), so VQF's accQuat stays identity and its series must equal the
    // integration baseline exactly, up to the one-sample hold-phase effect.
    // Spinning about a HORIZONTAL axis instead would rotate the inertial
    // gravity direction continuously, which the tauAcc=40 s low-pass
    // (longer than the clip) tracks only in the average — a tumble is not
    // a clean synthetic comparison.
    const double w = 50.0 * kPi / 180.0;
    const int64_t dt = int64_t(10e6);
    const int n = 400; // 4 s -> 200 deg total
    std::vector<ImuSample> gyro, accel;
    for (int i = 0; i < n; ++i) {
        gyro.push_back(samp(int64_t(i) * dt, 0.0, 0.0, w));
        // Gravity along -z camera (up along +z): unchanged by a z spin.
        accel.push_back(samp(int64_t(i) * dt, 0.0, 0.0, 9.81));
    }
    const VqfOrientation v = estimateOrientationVqf(gyro, accel);
    const OrientationSeries base = integrateGyro(gyro);
    REQUIRE(v.series.size() == base.size());

    // Gravity stays along the spin axis in the camera frame, so VQF's
    // accQuat is the identity and its series must equal the integration
    // baseline SAMPLE BY SAMPLE (both are exp(w*t_i) about z; the hold
    // phases agree exactly at a constant rate). This is the strongest
    // form of the integration-vs-VQF comparison. Bound is numerical
    // noise, not semantics: VQF does not renormalize gyrQuat every step
    // (the integrator does), and the dot-product angle metric picks up
    // the norm drift — observed 1.7e-6 deg over the series; 1e-4 deg
    // still pins equality to 5e-7 of the 200 deg arc.
    for (size_t i = 0; i < base.size(); ++i) {
        const double* qv = &v.series.quats[i * 4];
        const double* qb = &base.quats[i * 4];
        CHECK(angleBetweenDeg(qv, qb) < 1e-4);
    }

    // Total angle: both estimators see the same total. n=400 samples at
    // 100 Hz span 399 intervals = 3.99 s -> 50 deg/s * 3.99 s = 199.5 deg
    // (the old 200 deg expectation was wrong by the missing last
    // interval; 0.5 deg of tolerance had been hiding it).
    const double totalDeg = 50.0 * double(n - 1) * 0.01;
    const double* qvN = &v.series.quats[(n - 1) * 4];
    const double* qbN = &base.quats[(n - 1) * 4];
    CHECK(std::fabs(quatAngleDeg(qbN) - totalDeg) < 1e-3);
    CHECK(std::fabs(quatAngleDeg(qvN) - totalDeg) < 1e-3);

    // Gravity estimate: unchanged by the spin, (0, 0, -1) in the camera
    // frame the whole time.
    CHECK(std::fabs(v.gravityCamera[0]) < 1e-9);
    CHECK(std::fabs(v.gravityCamera[1]) < 1e-9);
    CHECK(std::fabs(v.gravityCamera[2] + 1.0) < 1e-9);
}

TEST_CASE("vqf empty inputs give empty series and zero outputs") {
    const VqfOrientation v = estimateOrientationVqf({}, {});
    CHECK(v.series.empty());
    CHECK(v.gravityCamera[0] == 0.0);
    CHECK(v.gravityCamera[1] == 0.0);
    CHECK(v.gravityCamera[2] == 0.0);
    CHECK(v.gyroBias[0] == 0.0);
}

TEST_CASE("vqf no accel: gravity is zero, series is gyro-only 6d") {
    // Without accel, VQF's accQuat stays identity and its updateAcc is
    // never called: the output equals the integration baseline.
    const double w = 30.0 * kPi / 180.0;
    const int64_t dt = int64_t(10e6);
    const int n = 200;
    std::vector<ImuSample> gyro;
    for (int i = 0; i < n; ++i)
        gyro.push_back(samp(int64_t(i) * dt, w, 0.0, 0.0));
    const VqfOrientation v = estimateOrientationVqf(gyro, {});
    const OrientationSeries base = integrateGyro(gyro);
    REQUIRE(v.series.size() == base.size());
    for (size_t i = 0; i < n; ++i) {
        const double* qv = &v.series.quats[i * 4];
        const double* qb = &base.quats[i * 4];
        // Same rotation up to numerical noise (VQF does not renormalize
        // gyrQuat every step; observed 1.7e-6 deg over the series).
        CHECK(angleBetweenDeg(qv, qb) < 1e-4);
    }
    // No accel: no gravity estimate.
    CHECK(v.gravityCamera[0] == 0.0);
    CHECK(v.gravityCamera[2] == 0.0);
}

// ---------------------------------------------------------------------------
// Real fixtures (deliverable 7): VQF on both GCSV files, quoted numbers.
// Fixtures are gitignored; guarded so machines without STEPS/ pass.
// ---------------------------------------------------------------------------
TEST_CASE("real_fixture_vqf_261110_004150"
          * doctest::description("real gyro log; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv")) {
        MESSAGE("SKIP: STEPS/media/test/261110_004150_VIDEO_24mm.gcsv not present");
        return;
    }
    const FixtureImu f = loadFixture("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv");
    REQUIRE(f.data.sampleCount() == 5500);
    REQUIRE(f.data.aligned());

    const VqfOrientation v = estimateOrientationVqf(f.gyro, f.accel);
    REQUIRE(v.series.size() == 5500);

    // Total rotation: integrateGyro's |w| integral is 12.69 deg (python
    // reference); VQF's series must be in the same ballpark (same
    // physical motion, plus the gravity-alignment rotation).
    const double* qEnd = &v.series.quats[4 * 5499];
    const double totalVqf = quatAngleDeg(qEnd);
    MESSAGE("fixture 004150: VQF final quat (" << qEnd[0] << ", " << qEnd[1] << ", "
            << qEnd[2] << ", " << qEnd[3] << "), total rotation " << totalVqf << " deg");

    // VQF vs the integration baseline. Both series start at identity, so
    // the relative rotation at t0 is the identity BY CONSTRUCTION — the
    // drift metric compares INTERIOR points (1/3 and 2/3 of the clip):
    // the relative rotation is the constant gravity alignment plus VQF's
    // accel correction of integration drift; its CHANGE over the clip is
    // the drift the accel actually removed.
    const OrientationSeries base = integrateGyro(f.gyro);
    REQUIRE(base.size() == v.series.size());
    const size_t a = v.series.size() / 3, b = 2 * v.series.size() / 3;
    double cA[4], relA[4], cB[4], relB[4];
    quatConj(&v.series.quats[a * 4], cA);
    quatMultiply(cA, &base.quats[a * 4], relA);
    quatConj(&v.series.quats[b * 4], cB);
    quatMultiply(cB, &base.quats[b * 4], relB);
    const double drift = angleBetweenDeg(relA, relB);
    MESSAGE("fixture 004150: VQF-vs-integration relative rotation drift " << drift << " deg");
    CHECK(drift < 3.0);

    // Gravity vs the accel mean direction (the independent check the
    // mission demands). Clip mean of the canonical accel (python
    // reference): (-0.0388, 0.7409, 0.6705) pointing UP; gravity points
    // the other way: (0.0388, -0.7409, -0.6705). VQF's gravityCamera
    // must agree with that within a few degrees.
    const double refGravity[3] = {0.0388, -0.7409, -0.6705};
    const double agree = vecAngleBetweenDeg(v.gravityCamera, refGravity);
    MESSAGE("fixture 004150: VQF gravityCamera (" << v.gravityCamera[0] << ", "
            << v.gravityCamera[1] << ", " << v.gravityCamera[2] << "), accel-mean "
            << "agreement " << agree << " deg");
    CHECK(agree < 5.0);

    // orientationAt integrity: bracketing lookup at a midpoint matches
    // the SLERP of the two neighbors (spot check).
    double qMid[4], qSlerp[4];
    const int64_t t = v.series.timestampsNs[100] + (v.series.timestampsNs[101] - v.series.timestampsNs[100]) / 2;
    v.series.orientationAt(t, qMid);
    slerp(&v.series.quats[100 * 4], &v.series.quats[101 * 4], 0.5, qSlerp);
    CHECK(angleBetweenDeg(qMid, qSlerp) < 1e-9);
}

TEST_CASE("real_fixture_vqf_261110_004208"
          * doctest::description("real gyro log; needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004208_VIDEO_24mm.gcsv")) {
        MESSAGE("SKIP: STEPS/media/test/261110_004208_VIDEO_24mm.gcsv not present");
        return;
    }
    const FixtureImu f = loadFixture("STEPS/media/test/261110_004208_VIDEO_24mm.gcsv");
    REQUIRE(f.data.sampleCount() == 5447);
    REQUIRE(f.data.aligned());

    const VqfOrientation v = estimateOrientationVqf(f.gyro, f.accel);
    REQUIRE(v.series.size() == 5447);

    const double* qEnd = &v.series.quats[4 * 5446];
    MESSAGE("fixture 004208: VQF final quat (" << qEnd[0] << ", " << qEnd[1] << ", "
            << qEnd[2] << ", " << qEnd[3] << "), total rotation "
            << quatAngleDeg(qEnd) << " deg");

    // Interior-point drift metric, same as the 004150 case above.
    const OrientationSeries base = integrateGyro(f.gyro);
    REQUIRE(base.size() == v.series.size());
    const size_t a = v.series.size() / 3, b = 2 * v.series.size() / 3;
    double cA[4], relA[4], cB[4], relB[4];
    quatConj(&v.series.quats[a * 4], cA);
    quatMultiply(cA, &base.quats[a * 4], relA);
    quatConj(&v.series.quats[b * 4], cB);
    quatMultiply(cB, &base.quats[b * 4], relB);
    const double drift = angleBetweenDeg(relA, relB);
    MESSAGE("fixture 004208: VQF-vs-integration relative rotation drift " << drift << " deg");
    CHECK(drift < 3.0);

    // Clip mean of the canonical accel (python reference): (-0.8397,
    // -0.0334, 0.5420) up; gravity: (0.8397, 0.0334, -0.5420).
    const double refGravity[3] = {0.8397, 0.0334, -0.5420};
    const double agree = vecAngleBetweenDeg(v.gravityCamera, refGravity);
    MESSAGE("fixture 004208: VQF gravityCamera (" << v.gravityCamera[0] << ", "
            << v.gravityCamera[1] << ", " << v.gravityCamera[2] << "), accel-mean "
            << "agreement " << agree << " deg");
    CHECK(agree < 5.0);
}
