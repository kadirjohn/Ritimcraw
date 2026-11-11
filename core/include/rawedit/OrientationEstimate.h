// rawedit::OrientationEstimate — offline orientation estimation on the
// canonical IMU model (stabilization Phase 2, deliverables 2-5).
//
// WHAT THIS ADDS OVER Phase 1 + the axis remap (Orientation.h):
//   - a simple gyro-integration baseline (deliverable 3): identity
//     initial orientation, one exponential-map step per gyro sample —
//     no accel, no bias correction, drifts but never diverges; it is the
//     reference the VQF result is validated against (deliverable 7).
//   - offline VQF estimation (deliverable 2): quaternion series on the
//     GYRO stream timestamps, gravity direction exposed for the later
//     horizon lock, gyro bias handled inside the filter.
//   - accel resampling onto the gyro grid (deliverable 4): linear
//     interpolation, the method documented at resampleAccelOntoGyro.
//   - orientationAt(timestampNs): SLERP lookup between the bracketing
//     quaternions, clamped at the ends, no extrapolation (deliverable 5).
//
// INPUT AXES: both estimators consume samples ALREADY REMAPPED into the
// canonical camera frame (Orientation.h). Callers feed
// axisRemapForImu(imu).apply(...) per sample — the estimators never look
// at ImuData::orientation themselves, so a caller with already-canonical
// data (tests, later pipeline stages) can skip the remap.
//
// QUATERNION CONVENTIONS (deliverable 6 — the binding contract for every
// later stabilization stage; restated from Orientation.h where they are
// derived from the frame):
//   - Storage: [w, x, y, z], w first, Hamilton product.
//   - Multiplication order: q = q1 * q2 applies q2 FIRST, then q1
//     (column-vector convention: v' = q1 (q2 v q2*) q1*).
//   - DIRECTION: a quaternion q rotates vectors FROM the camera (body)
//     frame TO the world frame: v_world = q v_cam q*. This is
//     camera-to-world, the direction VQF outputs natively (its 6D
//     quaternion actively rotates sensor-frame vectors into the earth
//     frame) and the direction the Phase 4 warp needs (R in
//     H = K R K^-1 is camera-to-world).
//   - Inverse: for unit quaternions, the conjugate. World-to-camera is
//     conj(q).
//   - Handedness: right-handed frames only (the remap rejects det -1).
//   - FIRST-QUATERNION REFERENCE: both estimators start at IDENTITY —
//     world = camera at the first gyro timestamp. VQF's absolute
//     inclination (gravity alignment) is layered on top: its earth frame
//     is the initial camera frame ROTATED so that earth +Z opposes
//     gravity, i.e. the accQuat part of its 6D output. The world frame
//     of the output series is therefore NOT arbitrary: it is
//     gravity-aligned (see gravity()).
//   - HOLD PHASE: quats[i] is the orientation AT timestampsNs[i] in both
//     estimators, but the sample-to-interval assignment differs:
//     integrateGyro uses BACKWARD hold (sample i's rate covers the
//     interval ENDING at t_i), VQF uses FORWARD hold internally (its
//     update advances the state by w_i*Ts over [t_i, t_{i+1}]), and
//     estimateOrientationVqf records the filter state BEFORE each
//     sample's update so the recorded series is the orientation at the
//     sample instant. The two series therefore agree exactly for a
//     constant angular rate and differ by at most one sample's rotation
//     (w*Ts) when the rate varies — negligible at 473 Hz fixtures
//     (0.06 deg at 30 deg/s).
//   - GRAVITY: VqfOrientation::gravityCamera returns the unit direction
//     gravity points TOWARDS, in the CAMERA frame at the last gyro
//     timestamp (the world frame moves with the camera, so the
//     camera-frame direction is what a horizon lock must consume). In
//     VQF's world frame gravity converges to (0, 0, -1): VQF aligns
//     earth +Z with the low-pass accelerometer direction, and an
//     accelerometer at rest measures specific force = -g (upwards), so
//     earth +Z = up and gravity points along -Z. gravityCamera is
//     (0,0,0) when there is no accel stream. The integration baseline
//     exposes no gravity (no accel): its world frame is the first
//     camera frame, unaligned.
//
// TIMESTAMPS: quaternions are stored on the GYRO stream timestamps (the
// primary stream; Phase 1's ImuData::firstTs()/lastTs() are gyro
// extents). orientationAt clamps to [first, last]; values before/after
// are the end quaternions, never extrapolated.

#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include <rawedit/Gyro.h>

namespace rawedit {

// A quaternion series on the gyro timestamps. quats[i] is the
// camera-to-world orientation AT timestampsNs[i] (valid at the sample
// instant, not integrated over an interval). Sizes always match.
struct OrientationSeries {
    std::vector<int64_t> timestampsNs;
    std::vector<double> quats; // 4 * timestampsNs.size(): [w,x,y,z] per sample

    bool empty() const { return timestampsNs.empty(); }
    size_t size() const { return timestampsNs.size(); }
    int64_t firstTs() const { return timestampsNs.empty() ? 0 : timestampsNs.front(); }
    int64_t lastTs() const { return timestampsNs.empty() ? 0 : timestampsNs.back(); }

    // Camera-to-world quaternion at t, SLERP between the bracketing
    // samples; clamped at the ends (t outside [first, last] returns the
    // nearer end quaternion — extrapolation is forbidden). Empty series
    // returns identity.
    void orientationAt(int64_t t, double out[4]) const;
};

// ---------------------------------------------------------------------------
// Gyro-only integration baseline (deliverable 3)
//
// q_{i} = q_{i-1} * exp(w_i * dt_i / 2), q_0 = identity, dt_i from the
// actual gyro timestamps (dt_1 = the first interval; the sample before
// the first is assumed still, i.e. the series starts AT the first
// timestamp with the identity). w in rad/s, already in the canonical
// camera frame. No bias correction, no accel: the result drifts with
// sensor bias but is the analytic reference for validation.
//
// The exponential map of the rotation vector w*dt:
//   theta = |w*dt|; q_step = [cos(theta/2), sin(theta/2) * w*dt/theta]
// with the theta -> 0 limit q_step = [1, 0, 0, 0].
// ---------------------------------------------------------------------------
OrientationSeries integrateGyro(const std::vector<ImuSample>& gyro);

// ---------------------------------------------------------------------------
// Offline VQF estimation (deliverable 2)
//
// Feeds the remapped gyro stream (rad/s) and accel stream (m/s^2, gravity
// included) to the vendored VQF (third_party/vqf, MIT) and records its 6D
// quaternion per gyro sample on the gyro timestamps. tauAcc follows the
// gyroflow precedent for video stabilization (imu_integration/mod.rs:
// tau_acc = 40 s — trust the gyro, correct drift slowly; VQF's own
// default 3 s is tuned for robot state estimation, not ~12 s clips).
//
// ACCEL HANDLING: VQF's constructor takes fixed sample rates. The gyro
// stream is near-uniform in practice (the fixture: 472.9 Hz, jitter well
// under a sample period); the estimator uses the MEAN gyro interval as
// gyrTs and documents the approximation. Accel is resampled onto the
// gyro grid first (resampleAccelOntoGyro below), so accTs == gyrTs and
// the equal-rate update() applies.
//
// Zero accel samples ([0,0,0], which the GCSV/MCRAW loaders can emit for
// missing readings) are skipped by VQF itself (updateAcc ignores them).
//
// Returns the 6D quaternion series (camera-to-world, gravity-aligned
// world) plus, for the horizon lock, the final gravity direction in the
// CAMERA frame (unit vector, camera-frame components; the world frame
// moves with the camera so the camera-frame direction is the useful one
// for a horizon lock that must track the camera).
struct VqfOrientation {
    OrientationSeries series;   // 6D quats on gyro timestamps
    double gravityCamera[3] = {0, 0, 0}; // unit, camera frame; (0,0,0) if no accel
    double gyroBias[3] = {0, 0, 0};      // rad/s, VQF's final bias estimate
};

VqfOrientation estimateOrientationVqf(const std::vector<ImuSample>& gyro,
                                      const std::vector<ImuSample>& accel);

// ---------------------------------------------------------------------------
// Accel resampling onto the gyro grid (deliverable 4)
//
// METHOD: linear interpolation of each accel component against timestamp,
// evaluated at every gyro timestamp. Linear (not spline) because accel
// from a phone IMU is already noisy at the sample level — the VQF
// low-pass (tauAcc 40 s) does the actual smoothing, and a smoother
// interpolant would only manufacture structure the sensor did not
// record. Edge behavior: gyro timestamps outside the accel span take the
// NEAREST accel sample (constant extrapolation at the ends only — this
// is resampling, not prediction). Accel samples at identical timestamps
// (MCRAW keeps equal timestamps) collapse to the last one before the
// interpolation runs.
//
// Returns one accel sample per gyro timestamp, .timestampNs == gyro's.
std::vector<ImuSample> resampleAccelOntoGyro(const std::vector<ImuSample>& accel,
                                             const std::vector<ImuSample>& gyro);

// ---------------------------------------------------------------------------
// Quaternion helpers (shared by tests and later phases; the conventions
// above are the contract, these are the primitives)
// ---------------------------------------------------------------------------

// Hamilton product q1 * q2 (q2 applied first).
inline void quatMultiply(const double q1[4], const double q2[4], double out[4]) {
    const double w1 = q1[0], x1 = q1[1], y1 = q1[2], z1 = q1[3];
    const double w2 = q2[0], x2 = q2[1], y2 = q2[2], z2 = q2[3];
    out[0] = w1 * w2 - x1 * x2 - y1 * y2 - z1 * z2;
    out[1] = w1 * x2 + x1 * w2 + y1 * z2 - z1 * y2;
    out[2] = w1 * y2 - x1 * z2 + y1 * w2 + z1 * x2;
    out[3] = w1 * z2 + x1 * y2 - y1 * x2 + z1 * w2;
}

// Conjugate (= inverse for unit quaternions).
inline void quatConj(const double q[4], double out[4]) {
    out[0] = q[0]; out[1] = -q[1]; out[2] = -q[2]; out[3] = -q[3];
}

// Active rotation of v by q: v' = q v q* (the camera-to-world direction).
inline void quatRotate(const double q[4], const double v[3], double out[3]) {
    const double w = q[0], x = q[1], y = q[2], z = q[3];
    const double vx = v[0], vy = v[1], vz = v[2];
    out[0] = (w * w + x * x - y * y - z * z) * vx + 2 * (x * y - w * z) * vy + 2 * (x * z + w * y) * vz;
    out[1] = 2 * (x * y + w * z) * vx + (w * w - x * x + y * y - z * z) * vy + 2 * (y * z - w * x) * vz;
    out[2] = 2 * (x * z - w * y) * vx + 2 * (y * z + w * x) * vy + (w * w - x * x - y * y + z * z) * vz;
}

// Dot product of the vector parts (cos of half the rotation angle for
// unit quaternions, up to sign).
inline double quatDot(const double a[4], const double b[4]) {
    return a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
}

// SLERP vs NLERP (deliverable 5's evaluation, shipped decision):
// SLERP is shipped. It is exact for the property tests below (same-axis
// rotations stay on the axis; the midpoint of a half-turn is the exact
// square root) and its constant angular velocity matches the physical
// model of a smoothly rotating camera between samples; NLERP deviates
// up to ~8% in the interpolated ANGLE for long arcs (the angle error is
// 0 at the ends and at the midpoint of this implementation's use, but
// the speed along the arc is non-uniform — for a 475 Hz gyro stream the
// arcs are tiny and the two agree to double precision; the exactness
// properties decided it).
void slerp(const double q0[4], const double q1[4], double t, double out[4]);

} // namespace rawedit
