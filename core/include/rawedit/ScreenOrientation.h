// rawedit::ScreenOrientation — MotionCam's per-frame "orientation" code -> degrees CW to upright.
//
// The frame metadata's "orientation" is NOT degrees. It is MotionCam's screen-orientation code:
//   0 = portrait, 1 = reverse portrait, 2 = landscape, 3 = reverse landscape.
// Verified on a 25010PN30G with a sensorOrientation of 90 (three clips shot on purpose, un-rotated frames
// inspected by eye, 2026-11-27):
//   code 0 (portrait clip)         -> the raw frame needs 90 CW to stand upright
//   code 2 ("sola dogru" landscape)  -> upright as recorded (0)        [also yavas_pan, elde_sabit, yurumek]
//   code 3 ("saga dogru" landscape)  -> upside down as recorded (180)
// Code 1 (reverse portrait) is DERIVED, not observed: no such clip exists yet.
//
// The mapping: the code names the screen rotation d the phone was held at (portrait 0, landscape 90,
// reverse portrait 180, reverse landscape 270); the sensor sits sensorOrientation degrees off the screen's
// natural top, so the raw frame needs (sensorOrientation - d) mod 360 CW (Android's back-camera formula).
// Front cameras mirror that sign; no front-camera MCRAW has been seen, so none is modelled.
//
// An unknown code (outside 0..3) or a sensorOrientation that is not a multiple of 90 is reported as NOT
// recognised: the caller must say so (a log line), never treat it as "no rotation" silently.

#pragma once

namespace rawedit {

struct ResolvedOrientation {
    int rotationDeg = 0;      // degrees CW the RAW frame needs to stand upright: 0, 90, 180 or 270
    bool recognised = false;  // false: code or sensor orientation outside what is known
};

inline int normalizeDeg360(int deg) { return ((deg % 360) + 360) % 360; }

inline ResolvedOrientation resolveMotionCamOrientation(int screenCode, int sensorOrientationDeg) {
    ResolvedOrientation r;
    if (screenCode < 0 || screenCode > 3) return r;
    const int sensor = normalizeDeg360(sensorOrientationDeg);
    if (sensor % 90 != 0) return r;
    static const int kScreenRotationDeg[4] = {0, 180, 90, 270};
    r.rotationDeg = normalizeDeg360(sensor - kScreenRotationDeg[screenCode]);
    r.recognised = true;
    return r;
}

}  // namespace rawedit
