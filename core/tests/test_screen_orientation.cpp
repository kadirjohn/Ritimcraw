// test_screen_orientation — MotionCam's per-frame "orientation" code -> degrees CW to upright
// (core/include/rawedit/ScreenOrientation.h), and its use by Source::frameInfo.
//
//   - the table for sensorOrientation 90 (the only value seen): 0->90, 1->270, 2->0, 3->180; codes 0, 2 and 3
//     were verified on real clips (2026-11-27), 1 is derived;
//   - the same formula for the other quarter-turn sensor orientations;
//   - unknown codes and non-quarter sensor orientations are NOT recognised (never a silent "no rotation");
//   - real clips (when the fixtures exist on this machine): a code-2 clip resolves to 0, a code-0 clip to 90,
//     and Source reports no warning for them.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/ScreenOrientation.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <string>

using namespace rawedit;

TEST_CASE("sensor orientation 90: the four screen codes") {
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

namespace {
bool exists(const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fclose(f);
    return true;
}
}  // namespace

TEST_CASE("real clips: code 2 stands upright as recorded, code 0 needs 90 CW") {
    const std::string dir = "STEPS/media/test/";
    const std::string landscape = dir + "yavas_pan.mcraw";            // every frame: orientation 2
    const std::string portraitCode = dir + "261110_004150_VIDEO_24mm.mcraw";  // every frame: orientation 0
    if (!exists(landscape) || !exists(portraitCode)) {
        MESSAGE("fixtures not on this machine; skipped");
        return;
    }
    {
        auto opened = Source::open(landscape);
        REQUIRE(opened.isOk());
        Source& src = **opened.value;
        CHECK(src.frameInfo(0).orientationDeg == 0);
        CHECK(src.frameInfo(src.frameCount() / 2).orientationDeg == 0);
        CHECK(src.orientationWarning().empty());
    }
    {
        auto opened = Source::open(portraitCode);
        REQUIRE(opened.isOk());
        Source& src = **opened.value;
        CHECK(src.frameInfo(0).orientationDeg == 90);
        CHECK(src.orientationWarning().empty());
    }
}
