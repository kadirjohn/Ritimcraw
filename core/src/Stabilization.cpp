// Stabilization Phase 4 implementation — see Stabilization.h for the
// contracts (direction, signs, fallbacks). Everything here is plain
// CPU math; no GPU, no UI.
//
// Audit fix 2026-11-13 (owner's audit, section D): the crop checker
// and the correction scaler share warpFootprintViolation below — a
// sampler-EXACT footprint measure (warpFrame's composite map, the
// [0,w-1]x[0,h-1] pixel-center domain, positive denominators) — so
// a plan that passes the checker never hands warpFrame a sample it
// would edge-clamp into smear.

#include <rawedit/Stabilization.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
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
// (Unused in this TU since the 2026-11-13 audit fix folded its body
// into warpFootprintViolation — kept as the reference twin of
// Warp.cpp's copy; doctests pin the two to the same values.)
[[maybe_unused]] bool mapPoint(const double h[9], double x, double y,
                               double& ox, double& oy) {
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

// ---------------------------------------------------------------------------
// Sampler-exact footprint (audit fix 2026-11-13)
//
// warpFrame samples p_src = M * (x, y, 1) for every OUTPUT pixel-center
// (x, y) in [0,w-1]x[0,h-1], with
//     M = Hinv * S,  S = scale-about-c by 1/Z
// (built bit-for-bit as Warp.cpp does), then clamps samples outside
// [0,w-1]x[0,h-1] to the nearest edge pixel — the smear the audit
// found. The checker must be as strict as the sampler, so this
// measures the worst violation over the four OUTPUT pixel-center rect
// corners with these exact conventions:
//   * finite HOMOGENEOUS DENOMINATOR, and POSITIVE: mapPoint alone
//     would accept a negative w, but warpFrame perspective-divides
//     with it (mirroring the sample across the plane) — for the
//     composite of a rotation homography the denominator
//     w(x,y) = m[6]x + m[7]y + m[8] is AFFINE over the rect, so
//     positivity at the four corners implies positivity over the
//     whole rect: no horizon crossing, and the image of the rect is
//     the convex hull of the corner images — the corner check is
//     then EXACT, not a conservative bound.
//   * the mapped point inside [0, w-1] x [0, h-1] INCLUSIVE: the
//     x1/y1 bilinear guards make fx == w-1 exactly safe (the tap at
//     x0+1 == w falls back to the edge pixel), so the boundary
//     itself is valid; anything past it would clamp = smear.
// Returns the largest excursion in pixels (0 = every sampled source
// coordinate valid), or +inf when a corner is unmappable or has a
// non-positive denominator.
// ---------------------------------------------------------------------------
double warpFootprintViolation(const double hInv[9], double zoom,
                              double cx, double cy, int width, int height) {
    if (width <= 0 || height <= 0 || !(zoom >= 1.0) || !std::isfinite(zoom))
        return std::numeric_limits<double>::infinity();
    // The composite M, exactly as Warp.cpp builds it (bit-for-bit).
    const double s = 1.0 / zoom;
    double m[9];
    for (int i = 0; i < 3; ++i) {
        m[i * 3 + 0] = hInv[i * 3 + 0] * s;
        m[i * 3 + 1] = hInv[i * 3 + 1] * s;
        m[i * 3 + 2] = hInv[i * 3 + 0] * (cx - cx * s)
                     + hInv[i * 3 + 1] * (cy - cy * s)
                     + hInv[i * 3 + 2];
    }
    const double maxU = static_cast<double>(width) - 1.0;
    const double maxV = static_cast<double>(height) - 1.0;
    const double corners[4][2] = {{0.0, 0.0}, {maxU, 0.0},
                                  {0.0, maxV}, {maxU, maxV}};
    double worst = 0.0;
    for (int c = 0; c < 4; ++c) {
        const double x = corners[c][0], y = corners[c][1];
        const double w = m[6] * x + m[7] * y + m[8];
        if (!std::isfinite(w) || w <= 0.0)
            return std::numeric_limits<double>::infinity();
        const double u = m[0] * x + m[1] * y + m[2];
        const double v = m[3] * x + m[4] * y + m[5];
        if (!std::isfinite(u) || !std::isfinite(v))
            return std::numeric_limits<double>::infinity();
        const double sx = u / w, sy = v / w;
        if (!std::isfinite(sx) || !std::isfinite(sy))
            return std::numeric_limits<double>::infinity();
        const double dx = std::fmax(-sx, sx - maxU);
        const double dy = std::fmax(-sy, sy - maxV);
        worst = std::fmax(worst, std::fmax(dx, dy));
    }
    return worst;
}


// ---- Adaptive smoothing helpers (stabilization round 3, 2026-11-23) ----

// Angle (rad) of the rotation taking a to b (shortest arc).
double quatAngleRad(const double a[4], const double b[4]) {
    double d = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
    d = std::fabs(d);
    if (d > 1.0) d = 1.0;
    return 2.0 * std::acos(d);
}

// alpha of a one-pole low-pass with time constant tau over an interval dt.
double alphaFor(double dtSec, double tauSec) {
    if (!(dtSec > 0.0)) return 0.0;  // a hold: equal stamps never move
    if (!(tauSec > 0.0)) return 1.0;
    return 1.0 - std::exp(-dtSec / tauSec);
}

double dtSec(const std::vector<int64_t>& ts, size_t i) {
    return i == 0 ? 0.0 : static_cast<double>(ts[i] - ts[i - 1]) * 1e-9;
}

// Forward + reverse SLERP pass with per-sample alphas (alpha[i] is the
// step INTO sample i on the forward pass and OUT of sample i on the
// reverse pass — the same interval either way, so the pair has zero
// group delay).
std::vector<double> slerpPassPair(const std::vector<double>& q,
                                  const std::vector<double>& alpha) {
    const size_t n = alpha.size();
    std::vector<double> fwd(4 * n), out(4 * n);
    for (int k = 0; k < 4; ++k) fwd[k] = q[k];
    for (size_t i = 1; i < n; ++i)
        slerpToward(&fwd[4 * (i - 1)], &q[4 * i], alpha[i], &fwd[4 * i]);
    for (int k = 0; k < 4; ++k) out[4 * (n - 1) + k] = fwd[4 * (n - 1) + k];
    for (size_t ii = n - 1; ii > 0; --ii)
        slerpToward(&out[4 * ii], &fwd[4 * (ii - 1)], alpha[ii], &out[4 * (ii - 1)]);
    for (size_t i = 0; i < n; ++i) normalizeQuat(&out[4 * i]);
    return out;
}

// Forward + reverse one-pole on a scalar series (zero lag).
void smoothScalarPair(std::vector<double>& v, const std::vector<int64_t>& ts,
                      double tauSec) {
    const size_t n = v.size();
    for (size_t i = 1; i < n; ++i)
        v[i] = v[i - 1] + alphaFor(dtSec(ts, i), tauSec) * (v[i] - v[i - 1]);
    for (size_t ii = n - 1; ii > 0; --ii)
        v[ii - 1] = v[ii] + alphaFor(dtSec(ts, ii), tauSec) * (v[ii - 1] - v[ii]);
}

// Fixed-tau zero-lag smoothing of a quaternion series.
std::vector<double> smoothFixedTau(const std::vector<double>& q,
                                   const std::vector<int64_t>& ts, double tauSec) {
    const size_t n = ts.size();
    std::vector<double> alpha(n, 1.0);
    for (size_t i = 1; i < n; ++i) alpha[i] = alphaFor(dtSec(ts, i), tauSec);
    return slerpPassPair(q, alpha);
}

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

    // tau(strength), audit fix 2026-11-12: the OLD geometric map
    // tau = 1 ns * (1e9)^strength put every usable strength UNDER one
    // frame interval (the shipped 0.7 -> ~2 ms against ~33 ms frames) —
    // near-raw: measured 0.00 deg correction on the 4K fixtures at 0.70.
    // The redesigned map is geometric over the USEFUL range instead:
    //   tau(s) = tauMax * (tauMin/tauMax)^(1-s)
    //   tauMin = 1/60 s (one frame at 60 fps — below one frame interval
    //   smoothing is meaningless), tauMax = 1 s (the reference's
    //   low-velocity time constant), so 0.3 -> ~57 ms, 0.5 -> ~129 ms,
    //   0.7 -> ~293 ms, 0.9 -> ~664 ms, 1.0 -> 1 s. strength 0 is an
    //   EXACT pass-through of the raw series: the old map reached it via
    //   tau = 1 ns (alpha = 1 - exp(-dt/1ns) = 1.0 to double precision,
    //   both passes copied through); tauMin = 16.7 ms would leave a
    //   residue, so the raw output is explicit — the doctest pin
    //   survives either way.
    strength = std::fmax(0.0, std::fmin(1.0, strength));
    if (strength <= 0.0) {
        for (size_t i = 0; i < n; ++i)
            for (int k = 0; k < 4; ++k)
                out.quats[4 * i + k] = q[4 * i + k];
        return out;
    }
    const double tauMin = 1e9 / 60.0;                // ns (one 60 fps frame)
    const double tauMax = 1e9;                        // ns
    const double tau = tauMax * std::pow(tauMin / tauMax, 1.0 - strength);

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
// Velocity-adaptive smoothing (stabilization round 3, 2026-11-23)
// ---------------------------------------------------------------------------
OrientationSeries smoothOrientationSeriesAdaptive(const OrientationSeries& series,
                                                  double strength,
                                                  double fovDiagDeg) {
    OrientationSeries out;
    const size_t n = series.size();
    if (n == 0) return out;
    out.timestampsNs = series.timestampsNs;
    std::vector<double> q(4 * n);
    for (size_t i = 0; i < n; ++i) {
        double t[4] = {series.quats[4 * i], series.quats[4 * i + 1],
                       series.quats[4 * i + 2], series.quats[4 * i + 3]};
        normalizeQuat(t);
        for (int k = 0; k < 4; ++k) q[4 * i + k] = t[k];
    }
    strength = std::fmax(0.0, std::fmin(1.0, strength));
    if (n == 1 || strength <= 0.0) {
        out.quats = q;  // exact pass-through (the strength-0 contract)
        return out;
    }
    const std::vector<int64_t>& ts = series.timestampsNs;

    // Time constants: tauLow holds a still camera (the strength's main
    // dial, 2^(2s-1): s=0.5 -> 1 s, s=1 -> 2 s), tauHigh follows fast
    // motion (0.1 s). The blend between them is driven by the angular
    // velocity normalized by a FOV-scaled limit, so a wide lens tolerates
    // faster motion before tracking it.
    const double tauLow = std::pow(2.0, 2.0 * strength - 1.0);
    const double tauHigh = 0.1;
    const double fovRatio = std::fmax(0.1, std::fmin(2.0, fovDiagDeg / 120.0));
    // 500 deg/s at full strength and a 120 deg lens, halved because the
    // second pass below re-weights the normalized velocity by up to 2x.
    const double maxV = std::fmax(1.0, 500.0 * strength * fovRatio * 0.5);

    // Angular velocity (deg/s), smoothed zero-lag at tauHigh so a single
    // noisy sample cannot flip the regime.
    // Measured on a PRE-SMOOTHED path (tau = velocityTau): the regime
    // switch must see intentional motion (pans, tilts, turns), not hand
    // tremor — at a long focal the FOV-scaled limit is low (99 mm equiv:
    // ~24 deg diagonal -> ~25 deg/s at s = 0.5) and the raw tremor's
    // instantaneous velocity alone would cross it, making the smoother
    // TRACK the shake it exists to remove (measured on the owner's 99 mm
    // clips: residual jerk 0.3-0.4 at full correction with raw velocity).
    const double velocityTau = 0.25;
    const std::vector<double> qv = smoothFixedTau(q, ts, velocityTau);
    std::vector<double> vel(n, 0.0);
    for (size_t i = 1; i < n; ++i) {
        const double dt = dtSec(ts, i);
        vel[i] = dt > 0.0 ? quatAngleRad(&qv[4 * (i - 1)], &qv[4 * i]) * 180.0 / M_PI / dt : 0.0;
    }
    smoothScalarPair(vel, ts, tauHigh);

    auto blendAlpha = [&](size_t i, double r) {
        const double dt = dtSec(ts, i);
        const double a = alphaFor(dt, tauLow) * (1.0 - r) + alphaFor(dt, tauHigh) * r;
        return std::fmax(0.0, std::fmin(1.0, a));
    };

    // Pass 1: velocity-blended alphas.
    std::vector<double> alpha(n, 1.0);
    for (size_t i = 1; i < n; ++i) alpha[i] = blendAlpha(i, vel[i] / maxV);
    std::vector<double> first = slerpPassPair(q, alpha);

    // Pass 2: where pass 1 drifted far from the real path (the frames
    // that would demand the most crop), react faster. Distance to the
    // raw path, normalized, a dead zone below half the maximum,
    // smoothed zero-lag, remapped to [0.5, 1] and multiplied into the
    // normalized velocity; the pass re-smooths the RAW series.
    std::vector<double> dist(n, 0.0);
    double maxD = 0.0;
    for (size_t i = 0; i < n; ++i) {
        dist[i] = quatAngleRad(&first[4 * i], &q[4 * i]);
        maxD = std::fmax(maxD, dist[i]);
    }
    if (!(maxD > 1e-12)) {
        out.quats = first;  // nothing drifted: pass 1 already is the answer
        return out;
    }
    for (double& d : dist) {
        d /= maxD;
        if (d < 0.5) d = 0.0;
    }
    smoothScalarPair(dist, ts, tauHigh);
    double maxD2 = 0.0;
    for (double d : dist) maxD2 = std::fmax(maxD2, d);
    for (double& d : dist) d = ((maxD2 > 1e-12 ? d / maxD2 : 0.0) + 1.0) * 0.5;
    for (size_t i = 1; i < n; ++i) alpha[i] = blendAlpha(i, vel[i] / maxV * dist[i]);
    out.quats = slerpPassPair(q, alpha);
    return out;
}

// Diagonal field of view (deg) of a pinhole with focal f (px) over w x h.
double diagonalFovDeg(double focalLengthPx, int width, int height) {
    if (!(focalLengthPx > 0.0) || width <= 0 || height <= 0) return 120.0;
    const double diag = std::sqrt(static_cast<double>(width) * width +
                                  static_cast<double>(height) * height);
    return 2.0 * std::atan(diag / (2.0 * focalLengthPx)) * 180.0 / M_PI;
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
// Static crop (deliverable 4; sampler-exact since the 2026-11-13 audit fix)
// ---------------------------------------------------------------------------
double staticCropZoom(const std::vector<double>& quatsRel,
                      double focalLengthPx, double cx, double cy,
                      int width, int height) {
    if (width <= 0 || height <= 0 || !(focalLengthPx > 0.0))
        return 1.0;
    const size_t n = quatsRel.size() / 4;

    // Per frame: the minimal Z >= 1 with the OUTPUT pixel-center rect
    // corners mapping inside [0,w-1]x[0,h-1] through warpFrame's exact
    // composite (warpFootprintViolation above — the reconciled
    // conventions; the old checker used the looser continuous
    // [0,w]x[0,h] window corners, which is how plans that the sampler
    // then edge-clamped into smear passed the check).
    //
    // Feasibility is monotone in Z: for a fixed warp the sampled
    // window contracts about c along straight segments ending at
    // hInv(c) (projective maps take segments to segments under
    // positive denominators), and a line meets the valid rect in a
    // single interval, so each corner constraint flips
    // infeasible->feasible at most once. When hInv(c) itself sits
    // outside the valid rect no Z fits at all (the Z->inf footprint
    // is that point alone) — the doubling cap honestly returns the
    // absurd bound there. Bisection on that interval converges to
    // the minimal factor.
    double h[9], hInv[9];
    double zNeed = 1.0;
    for (size_t i = 0; i < n; ++i) {
        frameTransform(&quatsRel[4 * i], focalLengthPx, cx, cy, h);
        if (!matInv(h, hInv))
            continue; // degenerate warp; skip (never crops for it)
        if (warpFootprintViolation(hInv, 1.0, cx, cy, width, height) <= 0.0)
            continue; // this frame needs no crop at all
        // Bisection on [1, zHi] where zHi is the first probed bound
        // with no violation (doubling from 2; the crop for real
        // rotation warps is bounded well below 16x).
        double lo = 1.0, hi = 2.0;
        while (warpFootprintViolation(hInv, hi, cx, cy, width, height) > 0.0) {
            lo = hi;
            hi *= 2.0;
            if (hi > 64.0)
                break; // absurd warp; clamp (status flags carry the rest)
        }
        for (int iter = 0; iter < 60; ++iter) {
            const double mid = 0.5 * (lo + hi);
            if (warpFootprintViolation(hInv, mid, cx, cy, width, height) > 0.0)
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
    // any focal there). A refusal applies no correction, so the
    // applied-scale numbers report 0 (the identity plan); the
    // per-frame status says why, and no samples are taken at all.
    auto refuseAll = [&](StabilizationStatus s) {
        for (auto& f : result.frames) {
            f.status = s;
            f.zoom = 1.0;
        }
        result.correctionScale.assign(n, 0.0);
        result.clipCorrectionScale = 0.0;
        result.minCorrectionScale = 0.0;
        result.meanCorrectionScale = 0.0;
        result.invalidSampleFrames = 0;
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

    // Default the new fields for the non-refusing paths below; the
    // sufficient-budget case leaves them at 1.0 (bit-identical to the
    // pre-fix behavior — the compatibility contract).
    result.correctionScale.assign(n, 1.0);
    result.clipCorrectionScale = 1.0;
    result.minCorrectionScale = 1.0;
    result.meanCorrectionScale = 1.0;
    result.invalidSampleFrames = 0;

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
    // The crop fallback path (round 3): a lightly smoothed target (tau
    // 0.1 s) the correction degrades TOWARD when the zoom budget is short,
    // instead of degrading toward the raw camera — high-frequency shake
    // stays filtered even where the full correction does not fit.
    std::vector<double> qTargetFast =
        smoothFixedTau(qActual, frameTimestampsNs, 0.1);
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
        const OrientationSeries smoothed = params.adaptiveSmoothing
            ? smoothOrientationSeriesAdaptive(
                  actualOnFrames, params.strength,
                  diagonalFovDeg(params.focalLengthPx, params.width, params.height))
            : smoothOrientationSeries(actualOnFrames, params.strength);
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
                    // The fallback path is leveled by its OWN gravity
                    // reading so blending between the two never un-levels.
                    double qF[4] = {qTargetFast[4 * i], qTargetFast[4 * i + 1],
                                    qTargetFast[4 * i + 2], qTargetFast[4 * i + 3]};
                    double qFc[4], gFast[3], lockF[4];
                    quatConj(qF, qFc);
                    quatRotate(qFc, gWorld, gFast);
                    if (horizonLockRotation(gFast, lockF)) {
                        double qLockedF[4];
                        quatMultiply(qF, lockF, qLockedF);
                        for (int k = 0; k < 4; ++k)
                            qTargetFast[4 * i + k] = qLockedF[k];
                    }
                }
            }
            if (!anyLockApplied)
                result.horizonLockDegraded = true; // flagged, never fabricated
        }
    }

    // --- Step 6: q_rel per frame (with the lock folded in) ---
    std::vector<double> qRel(4 * n), qRelFast(4 * n);
    for (size_t i = 0; i < n; ++i) {
        relativeRotation(&qTarget[4 * i], &qActual[4 * i], &qRel[4 * i]);
        relativeRotation(&qTargetFast[4 * i], &qActual[4 * i], &qRelFast[4 * i]);
        result.relativeAngleDeg[i] = relativeRotationAngleDeg(&qRel[4 * i]);
    }
    // The correction path a in [0, 2] (round 3): a in [1, 2] slides from
    // the fast-smoothed correction to the full one; a in [0, 1] from no
    // correction (identity) to the fast one. a = 2 is the full plan,
    // a = 0 the identity (always fits). The scale fields report a / 2.
    const double qIdentity[4] = {1.0, 0.0, 0.0, 0.0};
    auto correctionAt = [&](size_t i, double a, double outQ[4]) {
        if (a >= 2.0) {
            for (int k = 0; k < 4; ++k) outQ[k] = qRel[4 * i + k];
        } else if (a >= 1.0) {
            slerpToward(&qRelFast[4 * i], &qRel[4 * i], a - 1.0, outQ);
        } else {
            slerpToward(qIdentity, &qRelFast[4 * i], a < 0.0 ? 0.0 : a, outQ);
        }
    };

    // --- Step 7: the static crop, clamped by the user limit ---
    result.requiredZoom = staticCropZoom(qRel, params.focalLengthPx, cx, cy,
                                          params.width, params.height);
    double zoomLimit = params.zoomLimit >= 1.0 ? params.zoomLimit : 1.0;
    result.appliedZoom = std::fmin(result.requiredZoom, zoomLimit);
    result.intrinsicsAssumed = assumedFocal;

    // --- Step 7b: crop-constrained correction (audit fix 2026-11-13) ---
    // The audit's finding: when the zoom limit sits below requiredZoom
    // the old code kept the FULL correction and merely clamped the
    // zoom, so warpFrame edge-clamped out-of-source samples into smear.
    // The fix scales the CORRECTION to fit the budget — q_rel_i(a) =
    // slerp(identity, q_rel_i, a) along the geodesic (never linear
    // scaling of components/Euler/matrix entries) — smoothly over
    // time. When requiredZoom <= appliedZoom the budget suffices and
    // every scale below stays 1.0: bit-identical to the pre-fix plan.
    std::vector<double> pathParam(n, 2.0);  // a per frame, [0, 2]
    if (result.appliedZoom >= result.requiredZoom) {
        // Sufficient budget: full correction, nothing to scale.
    } else {
        // fits(i, a): frame i's correction at scale a keeps every
        // sampled source coordinate valid at appliedZoom — the same
        // warpFootprintViolation the static-crop solver used, so the
        // scaler and the checker can never disagree. The warp checked
        // is EXACTLY the warp Step 8 ships: slerpToward(identity,
        // q_rel_i, a) (a = 0 -> identity exactly, a >= 1 -> q_rel_i
        // exactly), frameTransform, matInv — the same deterministic
        // chain warpFrame will run. a = 0 is the identity warp, which
        // maps the output rect onto itself through M = S: feasible
        // at any Z >= 1 whenever the principal point lies inside the
        // rect (the default center does). The feasible set is an
        // interval containing 0 (connectedness along the slerp
        // geodesic: the footprints move continuously in a, and a
        // segment under positive denominators meets the valid rect
        // in a single interval), so a bracketed search on [0, 1]
        // finds its edge. Feasibility is NOT assumed monotone in a
        // for arbitrary projective warps — the bracket is only
        // [0, b] with b REVALIDATED before use, and any frame that
        // still fails falls back to a_clip, then to 0 (below).
        const double Z = result.appliedZoom;
        auto fits = [&](size_t i, double a) {
            if (!(a >= 0.0) || !std::isfinite(a))
                return false;
            double qScaled[4];
            correctionAt(i, a, qScaled);
            double hS[9], hInvS[9];
            frameTransform(qScaled, params.focalLengthPx, cx, cy, hS);
            if (!matInv(hS, hInvS))
                return false;
            return warpFootprintViolation(hInvS, Z, cx, cy,
                                          params.width, params.height)
                <= 0.0;
        };

        // (a) a_clip — the clip-wide constant baseline: the largest a
        // with EVERY frame fitting at appliedZoom. Bisect on [0,1]
        // from the always-feasible a = 0; 40 iterations resolve the
        // bracket to ~1e-12. This alone is a complete, shippable
        // fix (uniform reduction, no temporal artifacts); (b)/(c)
        // refine it per frame and in time.
        double aClip = 0.0;
        {
            double lo = 0.0, hi = 2.0;
            bool hiFits = true;
            for (size_t i = 0; i < n && hiFits; ++i)
                hiFits = fits(i, hi);
            if (hiFits) {
                aClip = 2.0;
            } else {
                for (int iter = 0; iter < 40; ++iter) {
                    const double mid = 0.5 * (lo + hi);
                    bool midFits = true;
                    for (size_t i = 0; i < n && midFits; ++i)
                        midFits = fits(i, mid);
                    if (midFits)
                        lo = mid;
                    else
                        hi = mid;
                    if (hi - lo < 1e-12)
                        break;
                }
                aClip = lo; // the feasible side of the bracket
            }
        }

        // (b) b_i — the per-frame safe cap: the largest a with frame
        // i fitting at appliedZoom (same bracketed search, per
        // frame). Revalidated before use (no monotonicity assumed).
        std::vector<double> bCap(n, 0.0);
        for (size_t i = 0; i < n; ++i) {
            double lo = 0.0, hi = 2.0;
            if (fits(i, hi)) {
                bCap[i] = 2.0;
                continue;
            }
            for (int iter = 0; iter < 40; ++iter) {
                const double mid = 0.5 * (lo + hi);
                if (fits(i, mid))
                    lo = mid;
                else
                    hi = mid;
                if (hi - lo < 1e-12)
                    break;
            }
            bCap[i] = lo;
        }

        // (c) a_i — time-smoothed per-frame scales. The raw b_i jump
        // frame to frame with the motion; applying them directly
        // would pump the correction strength over time (a visible
        // breathing artifact). Two steps keep the result both smooth
        // AND feasible:
        //   env_i = min{ b_j : |t_j - t_i| <= W/2 }  (sliding min
        //                    over the ACTUAL frame PTS — never an
        //                    assumed fps; VFR clips keep honest
        //                    windows)
        //   a_i  = sum_k k(t_i - t_k) * env_k        (a nonnegative
        //                    normalized kernel with support radius
        //                    <= W/2)
        // Every k contributing to a_i satisfies |t_i - t_k| <= W/2,
        // so i lies in j = i's own min window, hence env_k <= b_i and
        // the weighted average a_i <= b_i: smoothing can never push a
        // frame past its own safe cap. W = 0.5 s is chosen because it
        // matches the smoothing time constants the target series
        // itself uses at mid strengths (tau ~ 0.1-1 s): the correction
        // reduction then breathes on the same timescale as the
        // correction, not faster.
        const double windowSec = 0.5;
        std::vector<double> env(n, 2.0);
        for (size_t i = 0; i < n; ++i) {
            const double ti = static_cast<double>(frameTimestampsNs[i]) * 1e-9;
            double e = 2.0;
            for (size_t j = 0; j < n; ++j) {
                const double tj =
                    static_cast<double>(frameTimestampsNs[j]) * 1e-9;
                if (std::fabs(tj - ti) <= windowSec / 2.0)
                    e = std::fmin(e, bCap[j]);
            }
            env[i] = e;
        }
        std::vector<double> aSmooth(n, 2.0);
        for (size_t i = 0; i < n; ++i) {
            const double ti = static_cast<double>(frameTimestampsNs[i]) * 1e-9;
            double num = 0.0, den = 0.0;
            for (size_t k = 0; k < n; ++k) {
                const double tk =
                    static_cast<double>(frameTimestampsNs[k]) * 1e-9;
                const double dt = std::fabs(ti - tk);
                if (dt > windowSec / 2.0)
                    continue;
                const double weight = 1.0 - dt / (windowSec / 2.0); // triangular
                num += weight * env[k];
                den += weight;
            }
            aSmooth[i] = den > 0.0 ? num / den : env[i];
        }

        // Final selection with revalidation: the smoothed a_i, any
        // frame that fails (numerical edge of the bracket, or an
        // exotic warp where smoothing and feasibility interact)
        // falls back to a_clip, then to 0 — the honest ladder, never
        // edge-clamped pixels. a is additionally clamped from BELOW at
        // a_clip: the clip-wide baseline always fits, so a smoothing
        // rounding dip below it (an ulp lost in the weighted average
        // when every b_i is identical, e.g.) is pure loss — this also
        // gives the ordering a_clip <= min(a_i) that Stabilization.h
        // pins. invalidSampleFrames counts the frames that STILL fail
        // after all fallbacks (expected 0; if the identity itself does
        // not fit something is broken upstream and the count says so).
        result.clipCorrectionScale = aClip / 2.0;
        for (size_t i = 0; i < n; ++i) {
            double a = aSmooth[i];
            if (a > bCap[i])
                a = bCap[i]; // the smoothing guarantee, enforced
            if (a < aClip)
                a = aClip;  // the baseline guarantee, enforced
            if (!fits(i, a)) {
                a = aClip;
                if (!fits(i, a)) {
                    a = 0.0;
                    if (!fits(i, 0.0)) {
                        // The identity itself does not fit (a broken
                        // principal point, e.g.) — count honestly and
                        // ship identity anyway (the least-wrong warp;
                        // warpFrame's own no-op ladder is the final
                        // backstop downstream).
                        ++result.invalidSampleFrames;
                    }
                }
            }
            pathParam[i] = a;
            result.correctionScale[i] = a / 2.0;
        }
        double aMin = 1.0, aSum = 0.0;
        for (size_t i = 0; i < n; ++i) {
            aMin = std::fmin(aMin, result.correctionScale[i]);
            aSum += result.correctionScale[i];
        }
        result.minCorrectionScale = aMin;
        result.meanCorrectionScale = aSum / static_cast<double>(n);
    }

    // --- Step 8: per-frame H + statuses ---
    // H is rebuilt from the SCALED q_rel (slerpToward(identity,
    // q_rel_i, a_i)) — the warp the footprint check validated is the
    // warp that ships. relativeAngleDeg above stays the REQUESTED
    // full-correction angle (the honest "what was asked for" number;
    // correctionScale says what was applied).
    std::vector<double> frameZoom(n, result.appliedZoom);
    std::vector<double> qFinal(4 * n);
    for (size_t i = 0; i < n; ++i)
        correctionAt(i, pathParam[i], &qFinal[4 * i]);
    if (params.dynamicZoom && n > 1) {
        // DYNAMIC ZOOM (round 3): each frame's own minimal crop for the
        // warp it ships, then the same feasibility-preserving smoothing
        // as the correction scale but MAX-based: a sliding max over the
        // actual PTS (window W) and a triangular kernel of radius W/2,
        // so z_i >= every neighbor's need (no frame ever under-cropped)
        // and the crop breathes slowly instead of holding the clip's
        // single worst frame for its whole length. Clamped to the
        // budget; revalidated below with the exact footprint check.
        const double windowSec = 2.0;
        std::vector<double> need(n, 1.0);
        for (size_t i = 0; i < n; ++i) {
            const std::vector<double> one(qFinal.begin() + 4 * i,
                                          qFinal.begin() + 4 * i + 4);
            need[i] = std::fmin(result.appliedZoom,
                                staticCropZoom(one, params.focalLengthPx, cx, cy,
                                               params.width, params.height));
        }
        std::vector<double> envZ(n, 1.0);
        size_t lo = 0, hi = 0;
        for (size_t i = 0; i < n; ++i) {
            const int64_t ti = frameTimestampsNs[i];
            const int64_t halfW = static_cast<int64_t>(windowSec / 2.0 * 1e9);
            while (lo < n && frameTimestampsNs[lo] < ti - halfW) ++lo;
            if (hi < lo) hi = lo;
            while (hi + 1 < n && frameTimestampsNs[hi + 1] <= ti + halfW) ++hi;
            double e = 1.0;
            for (size_t j = lo; j <= hi; ++j) e = std::fmax(e, need[j]);
            envZ[i] = e;
        }
        for (size_t i = 0; i < n; ++i) {
            const double ti = static_cast<double>(frameTimestampsNs[i]) * 1e-9;
            double num = 0.0, den = 0.0;
            for (size_t k = 0; k < n; ++k) {
                const double dt = std::fabs(ti - static_cast<double>(frameTimestampsNs[k]) * 1e-9);
                if (dt > windowSec / 2.0) continue;
                const double w = 1.0 - dt / (windowSec / 2.0);
                num += w * envZ[k];
                den += w;
            }
            double z = den > 0.0 ? num / den : envZ[i];
            z = std::fmax(z, need[i]);
            frameZoom[i] = std::fmin(result.appliedZoom, std::fmax(1.0, z));
        }
        // Exact revalidation (a frame failing at its dynamic zoom falls
        // back to the static budget, which the correction already fits).
        for (size_t i = 0; i < n; ++i) {
            double hD[9], hInvD[9];
            frameTransform(&qFinal[4 * i], params.focalLengthPx, cx, cy, hD);
            if (!matInv(hD, hInvD) ||
                warpFootprintViolation(hInvD, frameZoom[i], cx, cy,
                                       params.width, params.height) > 0.0)
                frameZoom[i] = result.appliedZoom;
        }
    }
    for (size_t i = 0; i < n; ++i) {
        StabilizationFrame& f = result.frames[i];
        frameTransform(&qFinal[4 * i], params.focalLengthPx, cx, cy, f.h);
        f.zoom = frameZoom[i];
        f.status = inside[i] ? StabilizationStatus::Ok
                             : StabilizationStatus::LowOverlap;
        if (assumedFocal)
            f.status = StabilizationStatus::IntrinsicsAssumed;
    }
    return result;
}

} // namespace rawedit
