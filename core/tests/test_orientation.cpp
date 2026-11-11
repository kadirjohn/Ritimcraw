// Axis remap tests (stabilization Phase 2, deliverable 1): GCSV
// orientation-string permutations, MCRAW sensorOrientation derivations
// (checked against telemetry-parser's own unit-test vectors), the
// unknown/empty policy, and structural properties of every known remap.
//
// Composition reference (Orientation.h): a valid string s gives
//   canon[0] = -sgn[1]*in[axis[1]]   (IMU left  -> camera right)
//   canon[1] =  sgn[0]*in[axis[0]]   (IMU up    -> camera up)
//   canon[2] =  sgn[2]*in[axis[2]]   (IMU viewer axis kept)
// so "XYZ" (identity permutation) composes to the quarter turn
// (-y, x, z) itself: the sensor axes coincide with gyroflow's IMU axes.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Gyro.h>
#include <rawedit/Orientation.h>

#include <cmath>
#include <sstream>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// A GCSV with a settable orientation header (Phase 1's parse maps it into
// ImuData::orientation untouched). An empty `orientation` omits the header
// line entirely (Phase 1 rejects a key without a value), which is exactly
// the "no orientation header" case the remap must treat as unknown.
Result<ImuData> parseGcsvWithOrientation(const char* orientation) {
    std::ostringstream text;
    text << "GYROFLOW IMU LOG\n"
         << "version,1.2\n"
         << "id,test\n";
    if (orientation[0] != '\0')
        text << "orientation," << orientation << "\n";
    text << "tscale,0.002\n"
         << "gscale,0.0000000100\n"
         << "ascale,0.0000000100\n"
         << "t,gx,gy,gz,ax,ay,az\n"
         << "0,100000000,200000000,300000000,721675301,25842485,669990349,\n"
         << "1,110000000,210000000,310000000,728949640,29934289,665443910,\n";
    std::istringstream in(text.str());
    return parseGcsv(in);
}

ImuData imuWith(const char* orientation) {
    auto r = parseGcsvWithOrientation(orientation);
    REQUIRE(r.isOk());
    return *r.value;
}

ImuData mcrawLike(int sensorOrientation, int lensFacing) {
    ImuData d;
    d.orientation = ""; // MCRAW containers have no orientation string
    d.sensorOrientation = sensorOrientation;
    d.lensFacing = lensFacing;
    return d;
}

} // namespace

TEST_CASE("gcsv XYZ composes to the quarter turn, not identity") {
    const AxisRemap r = axisRemapForImu(imuWith("XYZ"));
    CHECK(r.known);
    CHECK_FALSE(r.isIdentity());
    float x = 1, y = 2, z = 3;
    r.apply(x, y, z);
    CHECK(x == -2); // canon x = -y
    CHECK(y == 1);  // canon y = x
    CHECK(z == 3);  // canon z = z
}

TEST_CASE("gcsv the fixture device's identity-in-camera string is YxZ") {
    // For the sensor frame to EQUAL the canonical camera frame, the IMU
    // frame must be reached by the inverse quarter turn, which is the
    // string "YxZ" (odd permutation, sign product -1, det +1: valid).
    const AxisRemap r = axisRemapForImu(imuWith("YxZ"));
    REQUIRE(r.known);
    CHECK(r.isIdentity());
    float x = 1, y = 2, z = 3;
    r.apply(x, y, z);
    CHECK(x == 1); CHECK(y == 2); CHECK(z == 3);
}

TEST_CASE("gcsv common strings permute as composed out = quarterturn(orient)") {
    // "Zxy" (a GoPro-style mounting): axis (2,0,1), signs (-,+,-) -> the
    // string itself is out = (z, -x, -y) in the IMU frame; composed with
    // the quarter turn the remap is canon = (x, z, -y).
    const AxisRemap r = axisRemapForImu(imuWith("Zxy"));
    REQUIRE(r.known);
    float x = 1, y = 2, z = 3;
    r.apply(x, y, z);
    CHECK(x == 1);   // canon x = -(-in[0]) = in[0]
    CHECK(y == 3);   // canon y = in[2]
    CHECK(z == -2);  // canon z = -in[1]

    // "yXZ" (odd permutation, sign product -1, det +1): string gives
    // out = (-y, x, z); canon = (-x, -y, z).
    const AxisRemap r2 = axisRemapForImu(imuWith("yXZ"));
    REQUIRE(r2.known);
    float a = 1, b = 2, c = 3;
    r2.apply(a, b, c);
    CHECK(a == -1); CHECK(b == -2); CHECK(c == 3);

    // "xYz" (two sign flips on the identity permutation, det +1):
    // string out = (-x, y, -z); canon = (-y, -x, -z).
    const AxisRemap r3 = axisRemapForImu(imuWith("xYz"));
    REQUIRE(r3.known);
    float p = 1, q = 2, s = 3;
    r3.apply(p, q, s);
    CHECK(p == -2); CHECK(q == -1); CHECK(s == -3);
}

TEST_CASE("gcsv invalid and empty strings fall back to unknown identity") {
    // Wrong length, bad characters, repeated axes, whitespace, and the
    // missing header entirely.
    for (const char* s : {"", "XY", "XYZA", "xyx", "XxZ", "ABZ", "XyZ "}) {
        const AxisRemap r = axisRemapForImu(imuWith(s));
        CHECK_MESSAGE(!r.known, "string '" << s << "' must be unknown");
        CHECK_MESSAGE(r.isIdentity(), "string '" << s << "' must be identity");
    }
}

TEST_CASE("gcsv reflections (det -1) are rejected as unknown") {
    // A single sign flip on the identity permutation ("xYZ", "XyZ",
    // "XYz"), the all-negative identity "xyz" (product -1), and the odd
    // permutations with positive sign product ("YXZ", "XZY", "ZYX") are
    // all reflections: no physical sensor mounting produces one.
    for (const char* s : {"xYZ", "XyZ", "XYz", "xyz", "yxZ", "YXZ", "XZY", "ZYX"}) {
        const AxisRemap r = axisRemapForImu(imuWith(s));
        CHECK_MESSAGE(!r.known, "string '" << s << "' must be rejected (det -1)");
    }
}

TEST_CASE("every known gcsv remap is a proper rotation") {
    // Structural sweep over all 216 strings: whenever a remap is known,
    // its matrix must have determinant +1 and preserve norms. (24 of the
    // 48 distinct-axis strings are det +1; the rest are unknown.)
    int knownCount = 0;
    const std::string chars = "XYZxyz";
    for (char a : chars) {
        for (char b : chars) {
            for (char c : chars) {
                const std::string s{a, b, c};
                const AxisRemap r = axisRemapForImu(imuWith(s.c_str()));
                if (!r.known)
                    continue;
                ++knownCount;
                double mat[3][3] = {{0}};
                for (int col = 0; col < 3; ++col) {
                    float x = (col == 0), y = (col == 1), z = (col == 2);
                    r.apply(x, y, z);
                    mat[0][col] = x; mat[1][col] = y; mat[2][col] = z;
                }
                const double det =
                    mat[0][0] * (mat[1][1] * mat[2][2] - mat[1][2] * mat[2][1])
                  - mat[0][1] * (mat[1][0] * mat[2][2] - mat[1][2] * mat[2][0])
                  + mat[0][2] * (mat[1][0] * mat[2][1] - mat[1][1] * mat[2][0]);
                CHECK_MESSAGE(det == 1.0, "string " << s << " det " << det);
                float px = 1.5f, py = -2.25f, pz = 0.75f;
                r.apply(px, py, pz);
                const double n2 = double(px) * px + double(py) * py + double(pz) * pz;
                CHECK_MESSAGE(std::fabs(n2 - (1.5 * 1.5 + 2.25 * 2.25 + 0.75 * 0.75)) < 1e-12,
                              "string " << s << " norm changed");
            }
        }
    }
    // 3! orderings x 4 (of 8) sign combinations with product +1.
    CHECK(knownCount == 24);
}

TEST_CASE("mcraw derivation matches telemetry-parser's own vectors") {
    // tp motioncam/mod.rs tests (imu_axes_follow_the_sensor_orientation):
    CHECK(mcrawOrientationString(90, false) == "XYZ");  // usual back camera
    CHECK(mcrawOrientationString(270, false) == "xyZ"); // half turn around view axis
    CHECK(mcrawOrientationString(270, true) == "Xyz");  // usual front camera
    // Default (absent sensorOrientation) is NOT tp's "XYZ" guess here:
    // axisRemapForImu reports unknown — checked below.
}

TEST_CASE("mcraw all four back-camera mountings are known remaps") {
    for (int angle = 0; angle <= 270; angle += 90) {
        const AxisRemap r = axisRemapForImu(mcrawLike(angle, 1));
        CHECK_MESSAGE(r.known, "back camera " << angle << " deg must be known");
    }
    // 90 deg back camera (the fixture device) derives "XYZ", which
    // composes to the quarter turn — the sensor axes coincide with
    // gyroflow's IMU axes for this mounting.
    const AxisRemap r90 = axisRemapForImu(mcrawLike(90, 1));
    REQUIRE(r90.known);
    float x = 1, y = 2, z = 3;
    r90.apply(x, y, z);
    CHECK(x == -2); CHECK(y == 1); CHECK(z == 3);
}

TEST_CASE("mcraw unknown sensorOrientation is identity with known=false") {
    // Absent (-1) and non-90-multiples: identity + unknown, never guessed.
    for (int angle : {-1, 45, 30, 137, -90}) {
        const AxisRemap r = axisRemapForImu(mcrawLike(angle, 1));
        CHECK_MESSAGE(!r.known, "angle " << angle << " must be unknown");
        CHECK_MESSAGE(r.isIdentity(), "angle " << angle << " must be identity");
    }
    // Absent lensFacing (-1) behaves as the back camera (tp's default).
    CHECK(mcrawOrientationString(90, true) != mcrawOrientationString(90, false));
    const AxisRemap noFacing = axisRemapForImu(mcrawLike(270, -1));
    const AxisRemap back = axisRemapForImu(mcrawLike(270, 1));
    CHECK(noFacing.known == back.known);
    CHECK(noFacing.isIdentity() == back.isIdentity());
}

TEST_CASE("mcraw lensFacing picks the front-camera variant") {
    // Front 270 is "Xyz" (tp's own vector) — a different string than the
    // back camera's "xyZ", and a different remap.
    const AxisRemap back = axisRemapForImu(mcrawLike(270, 1));
    const AxisRemap front = axisRemapForImu(mcrawLike(270, 0));
    REQUIRE(back.known);
    REQUIRE(front.known);
    float bx = 1, by = 2, bz = 3, fx = 1, fy = 2, fz = 3;
    back.apply(bx, by, bz);
    front.apply(fx, fy, fz);
    CHECK(!(bx == fx && by == fy && bz == fz)); // they differ
    CHECK(mcrawOrientationString(270, false) == "xyZ");
    CHECK(mcrawOrientationString(270, true) == "Xyz");
    // Front 90 (unusual mounting) is still a valid derivation.
    CHECK(axisRemapForImu(mcrawLike(90, 0)).known);
}

TEST_CASE("applyOrientationString applies gcsv semantics directly") {
    // Direct string application (no quarter turn): out[i] = s*in[axis].
    float x = 1, y = 2, z = 3;
    REQUIRE(applyOrientationString("Zxy", x, y, z));
    CHECK(x == 3); CHECK(y == -1); CHECK(z == -2);
    // Invalid strings leave the values untouched and return false.
    float a = 1, b = 2, c = 3;
    CHECK_FALSE(applyOrientationString("XY", a, b, c));
    CHECK(a == 1); CHECK(b == 2); CHECK(c == 3);
    CHECK_FALSE(applyOrientationString("XxZ", a, b, c));
    CHECK(a == 1); CHECK(b == 2); CHECK(c == 3);
    // Det -1 strings are rejected here too (never applied).
    CHECK_FALSE(applyOrientationString("xYZ", a, b, c));
    CHECK(a == 1); CHECK(b == 2); CHECK(c == 3);
}

TEST_CASE("remap agreement between gcsv string and mcraw derivation for the fixture device") {
    // The fixture pair: GCSV declares "XYZ"; the MCRAW container records
    // sensorOrientation 90, back camera. Both paths must produce the same
    // known remap — the parity that lets Phase 2 treat both container
    // types uniformly.
    const AxisRemap fromGcsv = axisRemapForImu(imuWith("XYZ"));
    const AxisRemap fromMcraw = axisRemapForImu(mcrawLike(90, 1));
    REQUIRE(fromGcsv.known);
    REQUIRE(fromMcraw.known);
    CHECK(fromGcsv.index[0] == fromMcraw.index[0]);
    CHECK(fromGcsv.index[1] == fromMcraw.index[1]);
    CHECK(fromGcsv.index[2] == fromMcraw.index[2]);
    CHECK(fromGcsv.sign[0] == fromMcraw.sign[0]);
    CHECK(fromGcsv.sign[1] == fromMcraw.sign[1]);
    CHECK(fromGcsv.sign[2] == fromMcraw.sign[2]);
}
