// Stabilization Phase 4 implementation — see Stabilization.h for the
// contracts (direction, signs, fallbacks). Everything here is plain
// CPU math; no GPU, no UI.

#include <rawedit/Stabilization.h>

#include <algorithm>
#include <cmath>
#include <limits>

namespace rawedit {

namespace {

constexpr double kPi = 3.14159265358979323846;

// ---------------------------------------------------------------------------
// Small matrix helpers (row-major 3x3, double)
// ---------------------------------------------------------------------------
void matMul(const double a[9], const double b[9], double out[9]) {
    double r[9];
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            double s = 0.0;
            for (int k = 0; k < 3; ++k)
                s += a[i * 3 + k] * b[k * 3 + j];
            r[i * 3 + j] = s;
        }
    for (int i = 0; i < 9; ++i)
        out[i] = r[i];
}

// Inverts a 3x3 through the adjugate. Returns false when the matrix is
// (numerically) singular; out is untouched then.
bool matInv(const double m[9], double out[9]) {
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

// Homogeneous point map: out = H * (x, y, 1), perspective-divided.
bool mapPoint(const double h[9], double x, double y, double& ox, double& oy) {
    const double u = h[0] * x + h[1] * y + h[2];
    const double v = h[3] * x + h[4] * y + h[5];
    const double w = h[6] * x + h[7] * y + h[8];
    if (!std::isfinite(w) || std::fabs(w) < 1e-12)
        return false;
    ox = u / w;
    oy = v / w;
    return std::isfinite(ox) && std::isfinite(oy);
}

// ---------------------------------------------------------------------------
// slerp_toward — the smoothing primitive (Stabilization.h: advance q a
// fraction a of the way to x along the shortest arc; a = 1 -> x exactly).
// Mirrors the semantics the reference uses (semantics only — the formula
// is the standard shortest-arc partial rotation):
//   d = conj(q) * x; if d.w < 0 negate d (shortest arc);
//   half = atan2(|d.xyz|, d.w) * a;  out = q * [cos(half), axis*sin(half)]
// ---------------------------------------------------------------------------
void slerpToward(const double q[4], const double x[4], double a, double out[4]) {
    // Clamp the fraction: a <= 0 holds q, a >= 1 snaps to x.
    if (!(a > 0.0)) {
        for (int i = 0; i < 4; ++i)
            out[i] = q[i];
        return;
    }
    if (a >= 1.0) {
        for (int i = 0; i < 4; ++i)
            out[i] = x[i];
        return;
    }

    double qc[4];
    quatConj(q, qc);
    double d[4];
    quatMultiply(qc, x, d);
    if (d[0] < 0.0) {
        for (int i = 0; i < 4; ++i)
            d[i] = -d[i];
    }
    const double vn = std::sqrt(d[1] * d[1] + d[2] * d[2] + d[3] * d[3]);
    if (vn < 1e-12) {
        // q and x are the same rotation: nothing to move.
        for (int i = 0; i < 4; ++i)
            out[i] = q[i];
        return;
    }
    const double half = std::atan2(vn, d[0]) * a;
    double step[4] = {std::cos(half), d[1] / vn * std::sin(half),
                      d[2] / vn * std::sin(half), d[3] / vn * std::sin(half)};
    quatMultiply(q, step, out);
}

void normalizeQuat(double q[4]) {
    const double n = std::sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
    if (n > 0.0 && std::isfinite(n)) {
        for (int i = 0; i < 4; ++i)
            q[i] /= n;
    } else {
        q[0] = 1.0; q[1] = q[2] = q[3] = 0.0;
    }
}

// Rotation matrix of a unit quaternion (column-vector convention:
// out * v = q v q*), row-major.
void quatToMat(const double q[4], double r[9]) {
    const double w = q[0], x = q[1], y = q[2], z = q[3];
    r[0] = 1 - 2 * (y * y + z * z);
    r[1] = 2 * (x * y - w * z);
    r[2] = 2 * (x * z + w * y);
    r[3] = 2 * (x * y + w * z);
    r[4] = 1 - 2 * (x * x + z * z);
    r[5] = 2 * (y * z - w * x);
    r[6] = 2 * (x * z - w * y);
    r[7] = 2 * (y * z + w * x);
    r[8] = 1 - 2 * (x * x + y * y);
}

// Builds K (Stabilization.h: the -f at [1][1] absorbs the canonical
// y-up image vs v-down pixels flip).
void intrinsics(double f, double cx, double cy, double k[9]) {
    k[0] = f;  k[1] = 0.0; k[2] = cx;
    k[3] = 0.0; k[4] = -f; k[5] = cy;
    k[6] = 0.0; k[7] = 0.0; k[8] = 1.0;
}

// The view-axis flip D = diag(1, 1, -1) (frameTransform's derivation).
constexpr double kViewFlip[9] = {1, 0, 0, 0, 1, 0, 0, 0, -1};

} // namespace

// ---------------------------------------------------------------------------
// Statuses
// ---------------------------------------------------------------------------
const char* stabilizationStatusName(StabilizationStatus s) {
    switch (s) {
    case StabilizationStatus::Ok: return "ok";
    case StabilizationStatus::Off: return "off";
    case StabilizationStatus::NoImu: return "no-imu";
    case StabilizationStatus::Unsynced: return "unsynced";
    case StabilizationStatus::LowOverlap: return "low-overlap";
    case StabilizationStatus::IntrinsicsAssumed: return "intrinsics-assumed";
    }
    return "unknown";
}

// ---------------------------------------------------------------------------
// Smoothing (deliverable 1)
// ---------------------------------------------------------------------------
OrientationSeries smoothOrientationSeries(const OrientationSeries& series,
                                          double strength) {
    OrientationSeries out;
    const size_t n = series.size();
    if (n == 0)
        return out;
    out.timestampsNs = series.timestampsNs;
    out.quats.resize(4 * n);

    // Copy + normalize the input once (the API accepts unnormalized).
    std::vector<double> q(4 * n);
    for (size_t i = 0; i < n; ++i) {
        double t[4] = {series.quats[4 * i], series.quats[4 * i + 1],
                       series.quats[4 * i + 2], series.quats[4 * i + 3]};
        normalizeQuat(t);
        for (int k = 0; k < 4; ++k)
            q[4 * i + k] = t[k];
    }
    if (n == 1) {
        for (int k = 0; k < 4; ++k)
            out.quats[k] = q[k];
        return out;
    }

    // tau(strength) = tauMin * (tauMax/tauMin)^strength, clamped 0..1.
    // tauMin = 1 ns (not 1 us): at strength 0 alpha = 1 - exp(-dt/1ns)
    // = 1.0 to double precision for every real dt (>= 1 ns grid),
    // i.e. strength 0 is EXACTLY the raw series (doctest-pinned); 1 us
    // would leave a 1e-3..1e-4 residue on a 473 Hz grid. tauMax = 1 s
    // (the reference's low-velocity time constant).
    strength = std::fmax(0.0, std::fmin(1.0, strength));
    const double tauMin = 1.0;                       // ns
    const double tauMax = 1e9;                        // ns
    const double tau = tauMin * std::pow(tauMax / tauMin, strength);

    // Per-interval alpha from the ACTUAL stamps (grid-independent).
    std::vector<double> alpha(n, 1.0);
    for (size_t i = 1; i < n; ++i) {
        double dt = static_cast<double>(series.timestampsNs[i] - series.timestampsNs[i - 1]);
        if (!(dt > 0.0))
            dt = 1.0; // hold: equal/non-increasing stamps never NaN
        alpha[i] = 1.0 - std::exp(-dt / tau);
    }

    // Forward pass.
    std::vector<double> fwd(4 * n);
    for (int k = 0; k < 4; ++k)
        fwd[k] = q[k];
    for (size_t i = 1; i < n; ++i)
        slerpToward(&fwd[4 * (i - 1)], &q[4 * i], alpha[i], &fwd[4 * i]);

    // Reverse pass (zero group delay; same alphas, mirrored).
    for (int k = 0; k < 4; ++k)
        out.quats[4 * (n - 1) + k] = fwd[4 * (n - 1) + k];
    for (size_t ii = n - 1; ii > 0; --ii) {
        const size_t i = ii - 1;
        slerpToward(&out.quats[4 * (ii)], &fwd[4 * i], alpha[ii], &out.quats[4 * i]);
    }
    for (size_t i = 0; i < n; ++i)
        normalizeQuat(&out.quats[4 * i]);
    return out;
}

// ---------------------------------------------------------------------------
// Relative transform (deliverable 2)
// ---------------------------------------------------------------------------
void relativeRotation(const double qTarget[4], const double qActual[4],
                      double outQRel[4]) {
    double t[4] = {qTarget[0], qTarget[1], qTarget[2], qTarget[3]};
    double a[4] = {qActual[0], qActual[1], qActual[2], qActual[3]};
    normalizeQuat(t);
    normalizeQuat(a);
    double tc[4];
    quatConj(t, tc);
    quatMultiply(tc, a, outQRel);
    normalizeQuat(outQRel);
}

double relativeRotationAngleDeg(const double qRel[4]) {
    double w = std::fabs(qRel[0]);
    if (w > 1.0)
        w = 1.0;
    return 2.0 * std::acos(w) * 180.0 / kPi;
}

// ---------------------------------------------------------------------------
// Horizon lock (deliverable 1's option)
// ---------------------------------------------------------------------------
bool horizonLockRotation(const double g[3], double outQLock[4]) {
    const double xy = std::sqrt(g[0] * g[0] + g[1] * g[1]);
    outQLock[0] = 1.0;
    outQLock[1] = outQLock[2] = outQLock[3] = 0.0;
    if (!std::isfinite(xy) || xy < 0.1) {
        // No reliable roll observable: identity + false. The caller
        // flags the degradation; nothing is fabricated.
        return false;
    }
    const double roll = std::atan2(g[0], -g[1]);
    const double half = 0.5 * roll;
    outQLock[0] = std::cos(half);
    outQLock[1] = 0.0;
    outQLock[2] = 0.0;
    outQLock[3] = std::sin(half);
    return true;
}

// ---------------------------------------------------------------------------
// Lens model + frame transform (deliverable 3)
// ---------------------------------------------------------------------------
void frameTransform(const double qRel[4], double focalLengthPx,
                    double cx, double cy, double outH[9]) {
    double q[4] = {qRel[0], qRel[1], qRel[2], qRel[3]};
    normalizeQuat(q);
    double r[9];
    quatToMat(q, r);

    // H = K · D · R · D · K⁻¹ (the flip D is load-bearing — see the
    // header derivation; the naive K·R·K⁻¹ inverts pitch/yaw).
    double k[9], kInv[9], drd[9];
    intrinsics(focalLengthPx, cx, cy, k);
    matMul(kViewFlip, r, drd);      // D·R
    double tmp[9];
    matMul(drd, kViewFlip, tmp);    // D·R·D
    if (!matInv(k, kInv)) {
        // f == 0 makes K singular; the caller contract forbids it
        // (focalLengthPx <= 0 is refused upstream). Identity here
        // keeps the math total for direct frameTransform callers.
        for (int i = 0; i < 9; ++i)
            outH[i] = (i == 0 || i == 4 || i == 8) ? 1.0 : 0.0;
        return;
    }
    double tmp2[9];
    matMul(k, tmp, tmp2);          // K·D·R·D
    matMul(tmp2, kInv, outH);      // K·D·R·D·K⁻¹
}

void frameTransform(const double qRel[4], double focalLengthPx,
                    int width, int height, double outH[9]) {
    frameTransform(qRel, focalLengthPx,
                   static_cast<double>(width) / 2.0,
                   static_cast<double>(height) / 2.0, outH);
}

double focalLengthPxFromEquivalent(double focalLengthMm35, int width) {
    if (!(focalLengthMm35 > 0.0) || width <= 0)
        return 0.0; // unknown, never a guess
    return static_cast<double>(width) * focalLengthMm35 / 36.0;
}

// ---------------------------------------------------------------------------
// Static crop (deliverable 4)
// ---------------------------------------------------------------------------
double staticCropZoom(const std::vector<double>& quatsRel,
                      double focalLengthPx, double cx, double cy,
                      int width, int height) {
    if (width <= 0 || height <= 0 || !(focalLengthPx > 0.0))
        return 1.0;
    const size_t n = quatsRel.size() / 4;

    // The composite output->source map for one q_rel at zoom Z
    // (Stabilization.h): p_src = H⁻¹( c + (p_out - c)/Z ), i.e. the
    // sampled window is the output rect shrunk about the principal
    // point by 1/Z, then mapped through H⁻¹. Corners are the extremes
    // of the image (projective maps take convex hull corners to the
    // hull of the image; the window is a rectangle), and the
    // constraint "all four corners inside [0,w]x[0,h]" is monotone
    // in Z, so a bisection on Z converges to the minimal factor.
    double h[9], hInv[9];
    auto cornerViolation = [&](const double z) {
        // Largest source-space excursion over the four window corners
        // (positive = outside by that many pixels, 0 = inside).
        double worst = 0.0;
        const double halfW = width / (2.0 * z);
        const double halfH = height / (2.0 * z);
        const double corners[4][2] = {{cx - halfW, cy - halfH},
                                      {cx + halfW, cy - halfH},
                                      {cx - halfW, cy + halfH},
                                      {cx + halfW, cy + halfH}};
        for (int c = 0; c < 4; ++c) {
            double sx, sy;
            if (!mapPoint(hInv, corners[c][0], corners[c][1], sx, sy))
                return std::numeric_limits<double>::infinity();
            const double dx = std::fmax(-sx, sx - width);
            const double dy = std::fmax(-sy, sy - height);
            worst = std::fmax(worst, std::fmax(dx, dy));
        }
        return worst;
    };

    double zNeed = 1.0;
    for (size_t i = 0; i < n; ++i) {
        frameTransform(&quatsRel[4 * i], focalLengthPx, cx, cy, h);
        if (!matInv(h, hInv))
            continue; // degenerate warp; skip (never crops for it)
        if (cornerViolation(1.0) <= 0.0)
            continue; // this frame needs no crop at all
        // Bisection on [1, zHi] where zHi is the first probed bound
        // with no violation (doubling from 2; the crop for real
        // rotation warps is bounded well below 16x).
        double lo = 1.0, hi = 2.0;
        while (cornerViolation(hi) > 0.0) {
            lo = hi;
            hi *= 2.0;
            if (hi > 64.0)
                break; // absurd warp; clamp (status flags carry the rest)
        }
        for (int iter = 0; iter < 60; ++iter) {
            const double mid = 0.5 * (lo + hi);
            if (cornerViolation(mid) > 0.0)
                lo = mid;
            else
                hi = mid;
            if (hi - lo < 1e-9)
                break;
        }
        zNeed = std::fmax(zNeed, hi);
    }
    return zNeed;
}

// ---------------------------------------------------------------------------
// Clip-level API (deliverable 5)
// ---------------------------------------------------------------------------
StabilizationResult computeStabilization(const OrientationSeries& orientationSeries,
                                         const SyncModel& sync,
                                         const std::vector<int64_t>& frameTimestampsNs,
                                         const double gravityCamera[3],
                                         bool offsetKnown,
                                         bool assumedFocal,
                                         const StabilizationParams& params) {
    StabilizationResult result;
    const size_t n = frameTimestampsNs.size();
    result.frames.assign(n, StabilizationFrame{});
    result.relativeAngleDeg.assign(n, 0.0);

    // Refusal short-circuits: uniform identity + the honest status.
    // intrinsicsAssumed stays false on refusals (no frame is warped on
    // any focal there).
    auto refuseAll = [&](StabilizationStatus s) {
        for (auto& f : result.frames) {
            f.status = s;
            f.zoom = 1.0;
        }
        return result;
    };

    if (params.mode == StabilizationMode::Off)
        return refuseAll(StabilizationStatus::Off);
    if (orientationSeries.empty())
        return refuseAll(StabilizationStatus::NoImu);
    if (!offsetKnown)
        return refuseAll(StabilizationStatus::Unsynced);
    if (params.width <= 0 || params.height <= 0 || !(params.focalLengthPx > 0.0))
        return refuseAll(StabilizationStatus::NoImu); // no fabricated intrinsics
    if (n == 0)
        return result; // nothing to warp; flags stay clean

    const double cx = params.principalPoint[0] > 0.0
        ? params.principalPoint[0] : static_cast<double>(params.width) / 2.0;
    const double cy = params.principalPoint[1] > 0.0
        ? params.principalPoint[1] : static_cast<double>(params.height) / 2.0;

    // --- Step 4: per-frame actual orientations on the IMU clock ---
    std::vector<double> qActual(4 * n);
    std::vector<bool> inside(n, false);
    const int64_t gyroFirst = orientationSeries.firstTs();
    const int64_t gyroLast = orientationSeries.lastTs();
    for (size_t i = 0; i < n; ++i) {
        const int64_t imuTs = sync.toImu(frameTimestampsNs[i]);
        orientationSeries.orientationAt(imuTs, &qActual[4 * i]);
        inside[i] = imuTs >= gyroFirst && imuTs <= gyroLast;
    }

    // --- Step 5: the target series per mode ---
    // Actual series on the frame stamps (the smoothing input).
    OrientationSeries actualOnFrames;
    actualOnFrames.timestampsNs = frameTimestampsNs;
    actualOnFrames.quats = qActual;

    std::vector<double> qTarget(4 * n);
    double gWorld[3] = {0.0, 0.0, 0.0};
    bool haveGravity = gravityCamera != nullptr
        && std::isfinite(gravityCamera[0]) && std::isfinite(gravityCamera[1])
        && std::isfinite(gravityCamera[2]);

    if (params.mode == StabilizationMode::LockFirstFrame) {
        // The first INSIDE frame's actual orientation, held constant;
        // when no frame is inside, the first frame's clamped value (the
        // LowOverlap flags already report the clamping).
        size_t anchor = 0;
        for (size_t i = 0; i < n; ++i)
            if (inside[i]) {
                anchor = i;
                break;
            }
        for (size_t i = 0; i < n; ++i)
            for (int k = 0; k < 4; ++k)
                qTarget[4 * i + k] = qActual[4 * anchor + k];
    } else {
        // Smooth / HorizonLock: smoothed target from the actual series.
        const OrientationSeries smoothed =
            smoothOrientationSeries(actualOnFrames, params.strength);
        qTarget = smoothed.quats;

        if (params.mode == StabilizationMode::HorizonLock) {
            // Reconstruct the constant world gravity from the LAST gyro
            // sample's pairing (gravityCamera is the camera-frame
            // gravity at the last gyro timestamp; the series' last
            // quaternion is the same instant).
            if (haveGravity) {
                double qLast[4];
                orientationSeries.orientationAt(orientationSeries.lastTs(), qLast);
                quatRotate(qLast, gravityCamera, gWorld);
            }
            double gLen = std::sqrt(gWorld[0] * gWorld[0] + gWorld[1] * gWorld[1]
                                    + gWorld[2] * gWorld[2]);
            bool anyLockApplied = false;
            if (haveGravity && gLen > 0.1) {
                for (size_t i = 0; i < n; ++i) {
                    // Gravity in the TARGET frame (the frame being
                    // locked), then the roll-only lock.
                    double qT[4] = {qTarget[4 * i], qTarget[4 * i + 1],
                                    qTarget[4 * i + 2], qTarget[4 * i + 3]};
                    double qTc[4];
                    quatConj(qT, qTc);
                    double gTarget[3];
                    quatRotate(qTc, gWorld, gTarget);
                    double lock[4];
                    if (horizonLockRotation(gTarget, lock)) {
                        double qLocked[4];
                        quatMultiply(qT, lock, qLocked);
                        for (int k = 0; k < 4; ++k)
                            qTarget[4 * i + k] = qLocked[k];
                        anyLockApplied = true;
                    }
                }
            }
            if (!anyLockApplied)
                result.horizonLockDegraded = true; // flagged, never fabricated
        }
    }

    // --- Step 6: q_rel per frame (with the lock folded in) ---
    std::vector<double> qRel(4 * n);
    for (size_t i = 0; i < n; ++i) {
        relativeRotation(&qTarget[4 * i], &qActual[4 * i], &qRel[4 * i]);
        result.relativeAngleDeg[i] = relativeRotationAngleDeg(&qRel[4 * i]);
    }

    // --- Step 7: the static crop, clamped by the user limit ---
    result.requiredZoom = staticCropZoom(qRel, params.focalLengthPx, cx, cy,
                                          params.width, params.height);
    double zoomLimit = params.zoomLimit >= 1.0 ? params.zoomLimit : 1.0;
    result.appliedZoom = std::fmin(result.requiredZoom, zoomLimit);
    result.intrinsicsAssumed = assumedFocal;

    // --- Step 8: per-frame H + statuses ---
    for (size_t i = 0; i < n; ++i) {
        StabilizationFrame& f = result.frames[i];
        frameTransform(&qRel[4 * i], params.focalLengthPx, cx, cy, f.h);
        f.zoom = result.appliedZoom;
        f.status = inside[i] ? StabilizationStatus::Ok
                             : StabilizationStatus::LowOverlap;
        if (assumedFocal)
            f.status = StabilizationStatus::IntrinsicsAssumed;
    }
    return result;
}

} // namespace rawedit
