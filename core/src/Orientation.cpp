// rawedit::Orientation — implementation. The frame conventions, the
// semantics of the GCSV orientation string, and the unknown/empty policy
// are documented in Orientation.h; this file cites the exact upstream
// semantics it ports (telemetry-parser motioncam.rs / tags_impl.rs /
// gyroflow imu_transforms.rs — GPL/Apache REFERENCES, semantics only, no
// code copied).

#include <rawedit/Orientation.h>

#include <cmath>
#include <cstdlib>

namespace rawedit {
namespace {

// Parses a 3-char orientation string into axis/sign triples:
//   out[i] = sign[i] * in[axis[i]]
// Valid iff the string has exactly 3 chars from {X,x,Y,y,Z,z}, names three
// DISTINCT axes (a permutation, not a projection), and is a PROPER
// rotation (determinant +1): parity(permutation) * product(signs) = +1.
// A determinant -1 string is a reflection — no physical sensor mounting
// produces one, and a reflection would corrupt every downstream
// quaternion, so it is rejected (unknown), not applied.
bool parseOrientationString(const std::string& s, int axis[3], int sgn[3]) {
    if (s.size() != 3)
        return false;
    for (int i = 0; i < 3; ++i) {
        const char c = s[static_cast<size_t>(i)];
        switch (c) {
            case 'X': axis[i] = 0; sgn[i] = 1; break;
            case 'x': axis[i] = 0; sgn[i] = -1; break;
            case 'Y': axis[i] = 1; sgn[i] = 1; break;
            case 'y': axis[i] = 1; sgn[i] = -1; break;
            case 'Z': axis[i] = 2; sgn[i] = 1; break;
            case 'z': axis[i] = 2; sgn[i] = -1; break;
            default: return false;
        }
    }
    if (axis[0] == axis[1] || axis[0] == axis[2] || axis[1] == axis[2])
        return false; // not a permutation
    // Permutation parity by inversion count.
    int inversions = 0;
    for (int i = 0; i < 3; ++i)
        for (int j = i + 1; j < 3; ++j)
            if (axis[i] > axis[j])
                ++inversions;
    const int signProduct = sgn[0] * sgn[1] * sgn[2];
    const int det = (inversions % 2 == 0 ? 1 : -1) * signProduct;
    return det == 1;
}

// The fixed quarter turn from gyroflow's IMU frame (x up, y left, z towards
// the viewer) into the canonical camera frame (x right, y up, z towards
// the viewer): canon = (-y_imu, x_imu, z_imu). Composed with a parsed
// string (sensor -> IMU frame) this yields the full sensor -> camera remap:
//   canon[0] = -sign[1] * in[axis[1]]
//   canon[1] =  sign[0] * in[axis[0]]
//   canon[2] =  sign[2] * in[axis[2]]
AxisRemap composeWithQuarterTurn(const int axis[3], const int sgn[3]) {
    AxisRemap r;
    r.known = true;
    r.index[0] = axis[1]; r.sign[0] = -sgn[1];
    r.index[1] = axis[0]; r.sign[1] = sgn[0];
    r.index[2] = axis[2]; r.sign[2] = sgn[2];
    return r;
}

} // namespace

bool applyOrientationString(const std::string& s, float& x, float& y, float& z) {
    int axis[3], sgn[3];
    if (!parseOrientationString(s, axis, sgn))
        return false;
    const float in[3] = {x, y, z};
    x = sgn[0] * in[axis[0]];
    y = sgn[1] * in[axis[1]];
    z = sgn[2] * in[axis[2]];
    return true;
}

// ---------------------------------------------------------------------------
// MCRAW derivation — telemetry-parser src/motioncam/mod.rs @9350bc2
// semantics, ported with the structure kept recognizable:
//
//   sensor_orientation_rotation(angle, front):
//     Android SENSOR_ORIENTATION doc: "If the top side of the camera sensor
//     is aligned with the right edge of the screen in natural
//     orientation, the value should be 90. If the top side of a
//     front-facing camera sensor is aligned with the right of the screen,
//     the value should be 270." The rotation from the phone's sensor axes
//     to the camera's raw-image axes (x right, y down, z forward):
//       back(angle): s = round(sin(angle)), c = round(cos(angle)),
//         [[ c, -s, 0], [-s, -c, 0], [0, 0, -1]]
//       front: back(360 - angle) with rows 0 and 2 negated ("a back camera
//         with the sensor's top towards the same edge, turned around its
//         y axis to face the user").
//
//     The rows are then closed into gyroflow's IMU frame by negating every
//     row and swapping rows 0 and 1 (motioncam.rs imu_orientation's `axes`
//     closure: axes_string(&[m[1].map(-x), m[0].map(-x), m[2].map(-x)])) —
//     the IMU frame is "a quarter turn from its camera frame, x right,
//     y up, which the integrators turn it into as (-y, x, z)".
//
//   axes_string(m): per output row, the dominant input axis (largest
//     |value|, last one on ties — Rust max_by keeps the last maximum),
//     rejected when already used or |value| < 0.5; case = sign of the
//     value.
//
// Deviation from telemetry-parser, deliberate and documented in
// Orientation.h: the derivation is only attempted for multiples of 90°.
// tp rounds sin/cos and derives a (wrong) axis string for other angles
// (e.g. 30° derives the 0° string); a sensorOrientation that is not a
// multiple of 90° is not representable as a signed axis permutation at
// all, so it yields "" (unknown) here instead. tp's lensPoseRotation
// fallback (used only when sensorOrientation is absent, and "often a
// placeholder" per tp's own comment) is not ported: the fixtures record
// sensorOrientation, and an absent one is reported as unknown rather
// than guessed.
// ---------------------------------------------------------------------------
namespace {

using Mat3 = double[3][3];

void sensorOrientationRotation(int angleDeg, bool front, Mat3& out) {
    const double rad = angleDeg * 3.14159265358979323846 / 180.0;
    const double s = std::round(std::sin(rad));
    const double c = std::round(std::cos(rad));
    // back(angle)
    Mat3 m = {{c, -s, 0.0}, {-s, -c, 0.0}, {0.0, 0.0, -1.0}};
    if (front) {
        // back(360 - angle) with rows 0 and 2 negated
        const double rad2 = (360.0 - angleDeg) * 3.14159265358979323846 / 180.0;
        const double s2 = std::round(std::sin(rad2));
        const double c2 = std::round(std::cos(rad2));
        m[0][0] = -c2; m[0][1] = s2;  m[0][2] = 0.0;
        m[1][0] = -s2; m[1][1] = -c2; m[1][2] = 0.0;
        m[2][0] = 0.0; m[2][1] = 0.0; m[2][2] = 1.0;
    }
    // The imu_orientation row closure: negate every row, swap rows 0 and 1.
    for (int i = 0; i < 3; ++i) {
        out[0][i] = -m[1][i];
        out[1][i] = -m[0][i];
        out[2][i] = -m[2][i];
    }
}

} // namespace

std::string mcrawOrientationString(int angleDeg, bool front) {
    if (angleDeg < 0 || angleDeg % 90 != 0)
        return ""; // not representable as a signed axis permutation
    Mat3 m;
    sensorOrientationRotation(angleDeg, front, m);

    bool used[3] = {false, false, false};
    std::string out;
    out.reserve(3);
    for (int row = 0; row < 3; ++row) {
        int axis = -1;
        double value = 0.0;
        for (int col = 0; col < 3; ++col) {
            // >= keeps the LAST of equal |values|, matching Rust's max_by.
            if (axis == -1 || std::fabs(m[row][col]) >= std::fabs(value)) {
                axis = col;
                value = m[row][col];
            }
        }
        if (used[axis] || std::fabs(value) < 0.5)
            return "";
        used[axis] = true;
        const char c = "XYZ"[axis];
        out.push_back(value < 0.0 ? static_cast<char>(c + 32) : c);
    }
    return out;
}

AxisRemap axisRemapForImu(const ImuData& imu) {
    // GCSV path: the file's own orientation header. Valid string -> known
    // remap; absent/empty/invalid -> identity + unknown (never guessed).
    int axis[3], sgn[3];
    if (!imu.orientation.empty()) {
        if (parseOrientationString(imu.orientation, axis, sgn))
            return composeWithQuarterTurn(axis, sgn);
        return AxisRemap{}; // known = false
    }

    // MCRAW path: derive the string from sensorOrientation (+ lensFacing).
    // sensorOrientation < 0 = absent (GCSV files never set it).
    if (imu.sensorOrientation >= 0) {
        const bool front = (imu.lensFacing == 0); // Android LENS_FACING_FRONT
        const std::string s = mcrawOrientationString(imu.sensorOrientation, front);
        if (!s.empty() && parseOrientationString(s, axis, sgn))
            return composeWithQuarterTurn(axis, sgn);
    }

    return AxisRemap{}; // known = false
}

} // namespace rawedit
