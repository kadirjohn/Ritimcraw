// rawedit::Orientation — axis remap of the canonical IMU model into the
// canonical CAMERA frame (stabilization Phase 2, deliverable 1).
//
// WHERE THIS SITS: Phase 1 (Gyro.h) parses GCSV / embedded-MCRAW into
// ImuData with the SOURCE's declared axes. This header defines the single
// canonical camera frame every later stabilization stage consumes, and the
// per-sample remap that brings stored samples into it. It deliberately does
// NOT touch the stored samples: the remap is a value object computed from
// ImuData's metadata, applied by the caller (the Phase 2 orientation
// estimator feeds remapped vectors straight into VQF without copying the
// streams).
//
// THE CANONICAL CAMERA FRAME (the choice, and why):
//   X right, Y up, Z towards the viewer; right-handed; the camera looks
//   along -Z. This is the OpenGL/WebGL convention the render pipeline will
//   use for the warp (pinhole H = K * R * K^-1 with R a camera-to-world
//   rotation), and it matches gyroflow's integration frame exactly
//   (gyroflow integrates in x-right/y-up/z-towards-viewer; the owner's
//   brief mandates "use our render pipeline's coordinate system" — there
//   is no existing 3D convention in the render pipeline today, so the
//   OpenGL frame is defined here as the pipeline's convention).
//
// THE TWO SOURCES OF THE REMAP:
//
// 1. GCSV "orientation" header (3 chars, e.g. "XYZ"). Semantics
//    (telemetry-parser tags_impl.rs orient() @9350bc2, gyroflow
//    imu_transforms.rs orient(), GPL/Apache references — semantics only,
//    no code copied): each character names the SOURCE axis that feeds the
//    corresponding OUTPUT axis of gyroflow's IMU frame; case is the sign
//    (lowercase = negated). Character i: out[i] = s * in[axis(char_i)].
//    The IMU frame the string maps into is x up, y left, z towards the
//    viewer (telemetry-parser motioncam.rs: "Axes of the motion samples
//    for gyroflow's IMU frame: x up, y left, z towards the viewer, in the
//    recorded raw image (a quarter turn from its camera frame, x right,
//    y up, which the integrators turn it into as `(-y, x, z)`").
//    The string therefore describes the sensor's axes in gyroflow's IMU
//    frame; composing it with the fixed quarter turn (-y, x, z) yields
//    the remap into the canonical camera frame. "XYZ" composes to the
//    identity there (the fixture devices mount the sensor that way).
//
// 2. MCRAW container: no orientation string exists, so it is DERIVED from
//    sensorOrientation degrees (+ lensFacing, telemetry-parser
//    motioncam.rs @9350bc2 semantics, cited inline in Orientation.cpp):
//    sensor_orientation_rotation(angle, front) -> a 3x3 rotation from the
//    phone's sensor axes to the camera's raw-image axes (x right, y down,
//    z forward), then the same row closure and axes_string() that
//    telemetry-parser uses produce the 3-char string, then the same fixed
//    quarter turn into the canonical camera frame. The derivation is
//    skipped (unknown) unless sensorOrientation is a multiple of 90° —
//    axes_string() only represents signed axis permutations, and anything
//    else is not a pure permutation (telemetry-parser keeps the matrix;
//    Phase 2 does not need it — the fixtures record exactly 90).
//
// UNKNOWN / EMPTY — never guessed silently:
//   - GCSV: an orientation header that is absent, empty, or not a valid
//     3-char axis string, or a string that is not a proper rotation
//     (repeated axis, or determinant -1 — a reflection, which no physical
//     mounting produces) yields the identity remap with known=false. The
//     caller must check `known` before trusting the data.
//   - MCRAW: sensorOrientation absent (or not a multiple of 90°) yields
//     the identity remap with known=false — same rule.
//   The mission mandates this explicit flag; tp's "xzY" GCSV default and
//   "XYZ" MCRAW default are NOT adopted for the unknown case.
//
// QUATERNION CONVENTIONS (deliverable 6, documented here because the remap
// defines the frame the quaternions live in; the estimation API in
// OrientationEstimate.h restates what applies there):
//   - Multiplication: Hamilton product, w-first storage [w, x, y, z];
//     compose(q1, q2) = q1 * q2 applies q2 FIRST, then q1 (column-vector
//     convention: v' = q1 * (q2 * v * q2^-1) * q1^-1).
//   - Rotation direction: a quaternion q rotates vectors FROM the camera
//     (body) frame TO the world frame: v_world = q * v_cam * q^-1
//     (camera-to-world, the direction VQF outputs and the warp needs).
//   - Inverse/conjugate: for unit quaternions the conjugate is the
//     inverse; world-to-camera is conj(q).
//   - Handedness: right-handed frames only; the remap rejects reflections
//     (det -1 strings) so every downstream quaternion is a proper
//     rotation.
//   - First-quaternion reference: estimation starts at IDENTITY (q0 = 1),
//     i.e. world = camera at the first gyro timestamp; VQF's absolute
//     inclination (gravity alignment) is then a rotation ON TOP of that
//     initial identity — see OrientationEstimate.h for how the gravity-
//     aligned earth frame is defined there.
//   - Gravity alignment: the earth frame's +Z points OPPOSITE gravity
//     (up); VQF aligns it with the low-pass-filtered accelerometer
//     direction, so a horizon lock later reads gravity as -Z of earth.

#pragma once

#include <cstdint>
#include <string>

#include <rawedit/Gyro.h>

namespace rawedit {

// A signed axis permutation: canonical[i] = sign[i] * source[axis[i]].
// Exactly the composition "orientation string -> gyroflow IMU frame ->
// fixed quarter turn -> canonical camera frame", precomputed per axis.
struct AxisRemap {
    bool known = false; // false = identity fallback (unknown metadata)
    int index[3] = {0, 1, 2};
    int sign[3] = {1, 1, 1};

    // Applies the remap to one source-frame triple.
    void apply(float& x, float& y, float& z) const {
        const float in[3] = {x, y, z};
        x = sign[0] * in[index[0]];
        y = sign[1] * in[index[1]];
        z = sign[2] * in[index[2]];
    }

    // True when the remap is the identity permutation (known or not).
    bool isIdentity() const {
        return index[0] == 0 && index[1] == 1 && index[2] == 2
            && sign[0] == 1 && sign[1] == 1 && sign[2] == 1;
    }
};

// The remap for a parsed ImuData, from whichever metadata it carries:
// GCSV orientation string, or MCRAW sensorOrientation (+ lensFacing).
// Identity + known=false when the metadata is absent or unusable — never a
// silent guess. Deterministic, allocation-free, cheap to recompute.
AxisRemap axisRemapForImu(const ImuData& imu);

// The 3-char axis string for an MCRAW-style container. Empty when the
// derivation is not possible (sensorOrientation absent or not a multiple
// of 90°). Exposed for tests and for parity checks against GCSV files
// recorded alongside the same camera (the fixture pair agrees: "XYZ").
//   angleDeg: sensorOrientation (degrees CW to upright)
//   front:    lensFacing == 0 (front camera); false = back camera
std::string mcrawOrientationString(int angleDeg, bool front);

// Applies an orientation string (GCSV semantics, see above) in place.
// Returns false without touching xyz when the string is invalid (wrong
// length, bad character, repeated axis, or det -1 reflection).
bool applyOrientationString(const std::string& s, float& x, float& y, float& z);

} // namespace rawedit
