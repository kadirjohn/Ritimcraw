// image_stab_focus_fit.hpp — effective-focal calibration for the gyro
// cross-check (S10 input; the gyrocheck slope finding 1.216-1.222).
//
// THE LINEARITY THE FIT RIDES ON (and why this is cheap): the translation
// part of a pure-rotation homography is LINEAR in the focal length. In
// image_stab_gyro.hpp's derivation, the center ray's projected offset is
//     t = f * (cx / -cz, -cy / -cz)
// and the off-center point enters only through the angle theta and the
// scale (which the pair model ignores for rotations). So one prediction
// pass at ANY focal gives every other focal by multiplication:
//     pred_k(i).tx = k * pred_1(i).tx,   pred_k(i).ty = k * pred_1(i).ty,
//     theta unchanged.
// The best scale factor against measured pairs at fixed sync offset is
// then the closed-form least-squares solution (no grid search needed):
//     k* = sum(pred . meas) / sum(pred . pred)      (2D dot products)
// and the residual sum SSE(k) = A k^2 - 2 B k + C with
//     A = sum(|pred|^2), B = sum(pred . meas), C = sum(|meas|^2),
// a perfect parabola whose curvature gives an honest confidence interval:
// the k window where SSE stays within (1+tol^2) of the minimum.
//
// This header is MEASUREMENT code (core/tests): the production gyro path
// is used read-only (parseGcsv/axisRemap/integrateGyro/VQF via the
// callers), and NO focal constant is written anywhere — the tool reports.
#pragma once

#include <cmath>
#include <cstdint>
#include <vector>

#include <rawedit/ImageStab.h>
#include "image_stab_gyro.hpp"

namespace rawedit {

// Focal-scaled predictions: element i is pred_1(i) * k for translation,
// theta/s untouched (the linearity above). Pairs are indexed like records
// (element 0 unused, mirroring predictPairsFromSeries).
inline std::vector<GyroPairImage> scalePredictions(const std::vector<GyroPairImage>& pred1,
                                                   double k) {
    std::vector<GyroPairImage> out(pred1.size());
    for (size_t i = 0; i < pred1.size(); ++i) {
        out[i].tx = k * pred1[i].tx;
        out[i].ty = k * pred1[i].ty;
        out[i].s = pred1[i].s;       // scale ratio: rides on the same f ratio
        out[i].theta = pred1[i].theta;
    }
    return out;
}

// The parabola of one sync-offset candidate: SSE(k) = A k^2 - 2 B k + C
// over the USABLE pairs (inside the gyro span and the measurement not
// Lost), with all vectors in the same px height the caller uses for both
// sides (typically the working height, e.g. 360).
//
// Per-channel terms are kept separately: the 2D (x+y) fit assumes an
// ISOTROPIC focal scale, which the real clips violate (the night's
// gyrocheck slopes: x ~1.22, y < x; EIS/digital-crop asymmetry is the
// suspect). kX answers "what does the PAN channel say the focal is";
// k2D averages both; the difference is itself a diagnostic.
struct FocusFitQuad {
    size_t n = 0;        // usable pairs
    double A = 0;        // sum pred.pred (x + y)
    double B = 0;        // sum pred.meas (x + y)
    double C = 0;        // sum meas.meas (x + y)
    double Ax = 0, Bx = 0, Cx = 0;  // x-channel-only parabola terms
    double Ay = 0, By = 0, Cy = 0;  // y-channel-only parabola terms
    // Best k (closed form) and the RMS at it.
    double kBest() const { return A > 1e-12 ? B / A : 1.0; }
    double kBestX() const { return Ax > 1e-12 ? Bx / Ax : 1.0; }
    double kBestY() const { return Ay > 1e-12 ? By / Ay : 1.0; }
    double rmsAtK(double k) const {
        if (!n) return 0.0;
        const double sse = A * k * k - 2 * B * k + C;
        return std::sqrt(std::max(0.0, sse) / (2.0 * double(n)));  // 2D RMS
    }
    double rmsAtKX(double k) const {
        if (!n) return 0.0;
        const double sse = Ax * k * k - 2 * Bx * k + Cx;
        return std::sqrt(std::max(0.0, sse) / double(n));  // x-only RMS
    }
};

// Accumulates the quad over usable pairs. meas: per-record pair motions
// (tx/ty in units of the working HEIGHT; scaled by workH here). inside:
// the span flags from predictPairsFromSeries.
inline FocusFitQuad focusQuad(const std::vector<GyroPairImage>& pred1,
                              const std::vector<PairMotion>& meas,
                              double workH, const std::vector<uint8_t>& inside) {
    FocusFitQuad q;
    for (size_t i = 1; i < pred1.size() && i < meas.size(); ++i) {
        const bool inSpan = i < inside.size() ? inside[i] != 0 : true;
        if (!inSpan) continue;
        if (meas[i].status == PairStatus::Lost) continue;
        const double px = pred1[i].tx, py = pred1[i].ty;
        const double mx = meas[i].tx * workH, my = meas[i].ty * workH;
        q.A += px * px + py * py;
        q.B += px * mx + py * my;
        q.C += mx * mx + my * my;
        q.Ax += px * px; q.Bx += px * mx; q.Cx += mx * mx;
        q.Ay += py * py; q.By += py * my; q.Cy += my * my;
        ++q.n;
    }
    return q;
}

// The k interval where the RMS stays within (1 + tolFrac) of the minimum
// (tolFrac = 0.02 -> the RMS may grow 2%). Honest width: grows with noise,
// narrows with signal. Returns [kLo, kHi]; degenerate quad -> [k, k].
inline void focusConfidence(const FocusFitQuad& q, double tolFrac,
                            double& kLo, double& kHi) {
    const double k = q.kBest();
    if (q.n < 2 || q.A <= 1e-12) { kLo = kHi = k; return; }
    // SSE(k+d) - SSE_min = A d^2. Threshold: SSE_min * ((1+t)^2 - 1).
    const double sseMin = std::max(0.0, q.A * k * k - 2 * q.B * k + q.C);
    const double thr = sseMin * ((1.0 + tolFrac) * (1.0 + tolFrac) - 1.0);
    const double d = std::sqrt(std::max(0.0, thr / q.A));
    kLo = k - d;
    kHi = k + d;
}

// ---------------------------------------------------------------------------
// Per-pair sync jitter probe (the residual decomposition's (b) leg).
//
// For ONE pair: the frame instant of its second frame (ptsUs of frame i),
// the pair's previous-frame instant derived as ptsUs - frameIntervalUs
// (the caller passes the constant interval; irregular PTS only shifts
// both brackets equally, the probe is about the OFFSET), the measured
// motion, and the orientation series. Sweeps delta in
// [baseDelta - windowMs, baseDelta + windowMs] by stepMs and returns the
// best delta AND its residual SSE. Pure; shared with test_residual_analysis.
// ---------------------------------------------------------------------------
inline double fitPairOffset(const OrientationSeries& series, int64_t ptsUs,
                            int64_t frameIntervalUs, const PairMotion& meas,
                            double workH, double fpx, int64_t baseDelta,
                            double windowMs, double stepMs, int64_t* bestDeltaOut) {
    int64_t best = baseDelta;
    double bestSs = 1e300;
    for (double dMs = -windowMs; dMs <= windowMs + 1e-9; dMs += stepMs) {
        const int64_t offs = baseDelta + int64_t(std::llround(dMs * 1e6));
        double qPrev[4], qCur[4];
        series.orientationAt(ptsUs * 1000 - frameIntervalUs * 1000 + offs, qPrev);
        series.orientationAt(ptsUs * 1000 + offs, qCur);
        const GyroPairImage pr = gyroPairImageMotion(qPrev, qCur, fpx);
        const double dx = pr.tx - meas.tx * workH;
        const double dy = pr.ty - meas.ty * workH;
        const double ss = dx * dx + dy * dy;
        if (ss < bestSs) { bestSs = ss; best = offs; }
    }
    if (bestDeltaOut) *bestDeltaOut = best;
    return bestSs;
}

}  // namespace rawedit
