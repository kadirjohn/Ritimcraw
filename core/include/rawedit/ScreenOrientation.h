// rawedit::ScreenOrientation — MotionCam clip orientation (gravity first, screen-code fallback).
//
// Two sources, in priority order (owner decision 2026-11-27):
//   1. Sidecar .gcsv accelerometer gravity (first second of IMU samples): tells how the phone was
//      PHYSICALLY held even when Android's auto-rotate was locked (where MotionCam writes code 0 for
//      both real portrait and locked-rotation landscape clips).
//      Measured on Xiaomi 25010PN30G (sensorOrientation 90, orientation "XYZ"):
//        gravity +x -> 0   (SOLA / landscape, including locked-rotation old code-0 landscape clips)
//        gravity +y -> 90  (DIKEY / portrait)
//        gravity -x -> 180 (SAGA / reverse landscape)
//        gravity -y -> 270 (reverse portrait, derived)
//      Flat phone (in-plane gravity < 0.5 * |g|, i.e. tilted > 60 deg from the screen plane), front
//      camera (lensFacing == 0), empty accelerometer stream, or non-quarter sensorOrientation are not
//      decided (recognised = false) so the caller falls back to source 2.
//
//   2. MotionCam's per-frame "orientation" screen-orientation code (fallback when no usable .gcsv
//      gravity decision is available):
//        0 = portrait, 1 = reverse portrait, 2 = landscape, 3 = reverse landscape.
//      With sensorOrientation 90: 0 -> 90, 1 -> 270 (derived), 2 -> 0, 3 -> 180.
//      Formula (Android back-camera): (sensorOrientation - screenRotationDeg[code]) mod 360.
//      An unknown code (outside 0..3) or non-quarter sensorOrientation is reported as NOT recognised
//      so callers log it under ritim-orient rather than silently applying 0.

#pragma once

#include <cmath>

#include <rawedit/Gyro.h>
#include <rawedit/Orientation.h>

namespace rawedit {

struct ResolvedOrientation {
    int rotationDeg = 0;      // degrees CW the RAW frame needs to stand upright: 0, 90, 180 or 270
    bool recognised = false;  // false: input cannot decide an upright quarter turn
};

inline int normalizeDeg360(int deg) { return ((deg % 360) + 360) % 360; }

// Fallback (source 2): MotionCam per-frame screen-orientation code -> degrees CW to upright.
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

// Primary (source 1): phone-frame accelerometer gravity vector (m/s^2) -> degrees CW to upright.
// Not recognised for a flat phone (in-plane share < 0.5), a front camera (lensFacing == 0), a zero
// vector, or a non-quarter sensor orientation.
inline ResolvedOrientation gravityRotation(double ax, double ay, double az,
                                           int sensorOrientationDeg, int lensFacing) {
    ResolvedOrientation r;
    if (lensFacing == 0) return r;
    const int sensor = normalizeDeg360(sensorOrientationDeg);
    if (sensor % 90 != 0) return r;
    const double inPlane = std::hypot(ax, ay);
    const double mag = std::sqrt(ax * ax + ay * ay + az * az);
    if (!(mag > 1e-6) || inPlane < 0.5 * mag) return r;
    const double thetaDeg = std::atan2(ay, ax) * 180.0 / 3.14159265358979323846;
    const int quarter = static_cast<int>(std::lround(thetaDeg / 90.0)) * 90;
    r.rotationDeg = normalizeDeg360(quarter + sensor - 90);
    r.recognised = true;
    return r;
}

// Mean accelerometer vector over the first second of an IMU log -> gravity rotation.
// When the GCSV carries a valid 3-char axis orientation string (e.g. "XYZ" for sensorOrientation 90),
// remaps into gyroflow's raw-sensor IMU frame (+x sensor top, +y sensor left) where atan2(ay, ax)
// directly yields the upright rotation regardless of sensor mounting; otherwise uses phone-axis
// gravityRotation with sensorOrientationDeg.
inline ResolvedOrientation gravityRotationFromImu(const ImuData& imu, int sensorOrientationDeg) {
    if (imu.accel.empty()) return ResolvedOrientation{};
    if (imu.lensFacing == 0) return ResolvedOrientation{};
    const int64_t t0 = imu.accel.front().timestampNs;
    double sx = 0.0, sy = 0.0, sz = 0.0;
    size_t n = 0;
    for (const ImuSample& a : imu.accel) {
        if (a.timestampNs - t0 > 1000000000LL && n >= 10) break;
        sx += a.x;
        sy += a.y;
        sz += a.z;
        ++n;
    }
    if (n == 0) return ResolvedOrientation{};
    const double mx = sx / static_cast<double>(n);
    const double my = sy / static_cast<double>(n);
    const double mz = sz / static_cast<double>(n);
    if (!imu.orientation.empty()) {
        float rx = static_cast<float>(mx);
        float ry = static_cast<float>(my);
        float rz = static_cast<float>(mz);
        if (applyOrientationString(imu.orientation, rx, ry, rz)) {
            return gravityRotation(rx, ry, rz, 90, imu.lensFacing);
        }
    }
    return gravityRotation(mx, my, mz, sensorOrientationDeg, imu.lensFacing);
}

// Manual-rotation delta (0/90/180/270) that turns the clip's metadata fallback rotation into what
// gravity says, or -1 when gravity did not decide (caller keeps the metadata fallback).
inline int orientationDeltaFromGravity(int metadataRotationDeg, const ResolvedOrientation& gravity) {
    if (!gravity.recognised) return -1;
    return normalizeDeg360(gravity.rotationDeg - metadataRotationDeg);
}

}  // namespace rawedit
