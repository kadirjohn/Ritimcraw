// test_screen_orientation — MotionCam clip orientation: .gcsv gravity first, screen-code fallback
// (core/include/rawedit/ScreenOrientation.h), and its use by Source::frameInfo.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Gyro.h>
#include <rawedit/ScreenOrientation.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <string>

using namespace rawedit;

TEST_CASE("sensor orientation 90: the four screen codes (fallback)") {
    CHECK(resolveMotionCamOrientation(0, 90).rotationDeg == 90);   // portrait (observed: DIKEY clip)
    CHECK(resolveMotionCamOrientation(1, 90).rotationDeg == 270);  // reverse portrait (derived, not observed)
    CHECK(resolveMotionCamOrientation(2, 90).rotationDeg == 0);    // landscape (observed: SOLA clip, yavas_pan)
    CHECK(resolveMotionCamOrientation(3, 90).rotationDeg == 180);  // reverse landscape (observed: SAGA clip)
    for (int code = 0; code < 4; ++code) CHECK(resolveMotionCamOrientation(code, 90).recognised);
}

TEST_CASE("the same formula for the other quarter-turn sensor orientations") {
    // rotation = (sensor - screenRotation) mod 360 with screenRotation = {0, 180, 90, 270}[code]
    CHECK(resolveMotionCamOrientation(0, 0).rotationDeg == 0);
    CHECK(resolveMotionCamOrientation(2, 0).rotationDeg == 270);
    CHECK(resolveMotionCamOrientation(3, 0).rotationDeg == 90);
    CHECK(resolveMotionCamOrientation(0, 270).rotationDeg == 270);
    CHECK(resolveMotionCamOrientation(2, 270).rotationDeg == 180);
    CHECK(resolveMotionCamOrientation(0, 180).rotationDeg == 180);
    // a negative or >= 360 sensor orientation is normalised first
    CHECK(resolveMotionCamOrientation(0, -270).rotationDeg == 90);
    CHECK(resolveMotionCamOrientation(0, 450).rotationDeg == 90);
}

TEST_CASE("unknown codes and non-quarter sensor orientations are not recognised") {
    CHECK_FALSE(resolveMotionCamOrientation(-1, 90).recognised);
    CHECK_FALSE(resolveMotionCamOrientation(4, 90).recognised);
    CHECK_FALSE(resolveMotionCamOrientation(90, 90).recognised);  // a degrees value in the code slot
    CHECK_FALSE(resolveMotionCamOrientation(0, 45).recognised);
    CHECK_FALSE(resolveMotionCamOrientation(2, 91).recognised);
    // an unrecognised result still carries a defined rotation (0) the caller must not use silently
    CHECK(resolveMotionCamOrientation(7, 90).rotationDeg == 0);
}

TEST_CASE("gravity: measured vectors of real clips") {
    auto at = [](double ax, double ay, double az) { return gravityRotation(ax, ay, az, 90, 1); };
    CHECK(at(-0.46, 9.85, 0.87).rotationDeg == 90);     // DIKEY (261127_151942)
    CHECK(at(-0.26, 8.16, 5.19).rotationDeg == 90);     // 261110_004208 (portrait)
    CHECK(at(10.00, -0.03, 0.25).rotationDeg == 0);     // SOLA (261127_152054)
    CHECK(at(9.95, 0.20, 0.18).rotationDeg == 0);       // 261118_201651 (locked-rotation landscape, code 0)
    CHECK(at(9.86, -0.09, 0.59).rotationDeg == 0);      // 261127_102019 (locked-rotation landscape, code 0)
    CHECK(at(7.22, 0.33, 6.70).rotationDeg == 0);       // 261110_004150 (locked-rotation landscape, code 0)
    CHECK(at(-9.93, -0.48, 0.35).rotationDeg == 180);   // SAGA (261127_152022)
    CHECK(at(0.0, -9.8, 0.0).rotationDeg == 270);       // reverse portrait (derived)
    CHECK(at(6.37, -1.36, 7.50).rotationDeg == 0);      // yurumek (tilted back, still landscape)
    CHECK(at(-0.46, 9.85, 0.87).recognised);
}

TEST_CASE("gravity: flat phone, front camera, zero and non-quarter sensor fall back (-1)") {
    CHECK_FALSE(gravityRotation(0.5, 0.4, 9.8, 90, 1).recognised);
    CHECK_FALSE(gravityRotation(0.0, 0.0, 0.0, 90, 1).recognised);
    CHECK_FALSE(gravityRotation(9.8, 0.0, 0.0, 90, 0).recognised);
    CHECK_FALSE(gravityRotation(9.8, 0.0, 0.0, 45, 1).recognised);
    CHECK(orientationDeltaFromGravity(90, gravityRotation(0.5, 0.4, 9.8, 90, 1)) == -1);
    // another sensor orientation shifts the answer by (quarter + sensor - 90)
    CHECK(gravityRotation(9.8, 0.0, 0.0, 270, 1).rotationDeg == 180);
    CHECK(gravityRotation(0.0, 9.8, 0.0, 0, 1).rotationDeg == 0);
    CHECK(gravityRotation(9.8, 0.0, 0.0, 0, 1).rotationDeg == 270);
}

TEST_CASE("gravity from IMU log: first-second window, axis remap, and delta over metadata fallback") {
    ImuData none;
    CHECK_FALSE(gravityRotationFromImu(none, 90).recognised);

    ImuData d;
    d.orientation = "XYZ";
    for (int i = 0; i < 100; ++i)
        d.accel.push_back(ImuSample{int64_t(i) * 10000000LL, 0.0f, 9.8f, 0.2f});  // portrait for 1 s
    for (int i = 0; i < 500; ++i)
        d.accel.push_back(ImuSample{1000000000LL + int64_t(i) * 10000000LL, 9.8f, 0.0f, 0.2f});  // then landscape
    const ResolvedOrientation gPortrait = gravityRotationFromImu(d, 90);
    CHECK(gPortrait.recognised);
    CHECK(gPortrait.rotationDeg == 90);
    CHECK(orientationDeltaFromGravity(90, gPortrait) == 0);

    // Non-XYZ GCSV header (e.g. sensorOrientation 270 -> "xyZ") remaps into raw-sensor IMU frame
    ImuData rot270;
    rot270.orientation = "xyZ";
    for (int i = 0; i < 20; ++i)
        rot270.accel.push_back(ImuSample{int64_t(i) * 10000000LL, 9.8f, 0.0f, 0.0f});
    CHECK(gravityRotationFromImu(rot270, 270).rotationDeg == 180);

    // Locked-rotation landscape clip (metadata fallback = 90, gravity = 0 -> delta = 270, effective = 0)
    const ResolvedOrientation gLand = gravityRotation(9.8, 0.0, 0.0, 90, 1);
    CHECK(orientationDeltaFromGravity(90, gLand) == 270);
    CHECK(normalizeDeg360(90 + orientationDeltaFromGravity(90, gLand)) == 0);
}

namespace {
bool exists(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fclose(f);
    return true;
}
}  // namespace

TEST_CASE("real clips and gcsv fixtures: gravity overrides locked-rotation code 0, agrees with portrait and code 2") {
    const std::string dir = "STEPS/media/test/";
    const std::string yavas = dir + "yavas_pan.mcraw";                         // code 2, landscape
    const std::string oldLand = dir + "261110_004150_VIDEO_24mm.mcraw";        // code 0, actually landscape
    const std::string portrait = dir + "261110_004208_VIDEO_24mm.mcraw";       // code 0, actually portrait
    if (!exists(yavas) || !exists(oldLand) || !exists(portrait)) {
        MESSAGE("fixtures not on this machine; skipped");
        return;
    }
    {
        auto opened = Source::open(yavas);
        REQUIRE(opened.isOk());
        Source& src = **opened.value;
        CHECK(src.frameInfo(0).orientationDeg == 0);
        CHECK(src.sensorOrientationDeg() == 90);
        CHECK(src.orientationWarning().empty());
        auto imu = parseGcsv(dir + "yavas_pan.gcsv");
        REQUIRE(imu.isOk());
        const ResolvedOrientation g = gravityRotationFromImu(*imu.value, src.sensorOrientationDeg());
        CHECK(g.recognised);
        CHECK(g.rotationDeg == 0);
        CHECK(orientationDeltaFromGravity(src.frameInfo(0).orientationDeg, g) == 0);
    }
    {
        // Locked-rotation landscape clip: screen-code fallback is 90, .gcsv gravity corrects it to 0 (delta 270).
        auto opened = Source::open(oldLand);
        REQUIRE(opened.isOk());
        Source& src = **opened.value;
        CHECK(src.frameInfo(0).orientationDeg == 90);
        CHECK(src.orientationWarning().empty());
        auto imu = parseGcsv(dir + "261110_004150_VIDEO_24mm.gcsv");
        REQUIRE(imu.isOk());
        const ResolvedOrientation g = gravityRotationFromImu(*imu.value, src.sensorOrientationDeg());
        CHECK(g.recognised);
        CHECK(g.rotationDeg == 0);
        CHECK(orientationDeltaFromGravity(src.frameInfo(0).orientationDeg, g) == 270);
    }
    {
        // Real portrait clip: screen-code fallback is 90, .gcsv gravity confirms 90 (delta 0).
        auto opened = Source::open(portrait);
        REQUIRE(opened.isOk());
        Source& src = **opened.value;
        CHECK(src.frameInfo(0).orientationDeg == 90);
        CHECK(src.orientationWarning().empty());
        auto imu = parseGcsv(dir + "261110_004208_VIDEO_24mm.gcsv");
        REQUIRE(imu.isOk());
        const ResolvedOrientation g = gravityRotationFromImu(*imu.value, src.sensorOrientationDeg());
        CHECK(g.recognised);
        CHECK(g.rotationDeg == 90);
        CHECK(orientationDeltaFromGravity(src.frameInfo(0).orientationDeg, g) == 0);
    }
}
