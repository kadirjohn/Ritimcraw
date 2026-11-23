// image_stab_gyro.hpp — gyro→image motion cross-check helpers (S2 gyrocheck).
//
// Header-only and pure on purpose: image_stab_measure.cpp's `gyrocheck`
// subcommand AND the doctest suite (test_image_stab.cpp) share the SAME
// derivation, so the tests prove the measurement code path itself.
//
// WHAT THIS DERIVES: given the camera orientation at two frame instants
// (quaternions camera->world, the OrientationSeries convention: X right,
// Y up, Z towards the viewer, camera looks along -Z), the rotation the
// camera underwent between the two frames induces a pure-rotation
// homography on the image. For small inter-frame rotation the image
// content motion in the frame pair is well approximated by a SIMILARITY
// (the v1 motion model), which we extract by projecting two camera-frame
// rays through both poses:
//
//   v0 = (0,0,-1)          the frame center ray (previous frame)
//   v1 = (0, 0.5, -1)      an off-center ray (upward in the camera frame)
//   W  = R(q_prev) v       world direction of each ray (fixed scene)
//   c  = R(q_cur)^T W      the same world direction in the current camera
//   proj(c) = (f * cx/(-cz), -f * cy/(-cz))   (y-down image, center origin)
//
// The similarity p_cur = s R(theta) p_prev + t follows from the two
// projected points (p_prev(0) is the origin by construction):
//   t      = p_cur(0)
//   theta  = angle(p_cur(1) - t) - angle(p_prev(1))
//   s      = |p_cur(1) - t| / |p_prev(1)|
//
// SIGN CONVENTIONS (same as ImageStab.h's pair motion — the motion of the
// SCENE CONTENT in the image; a camera pan moves the scene the other way):
// theta is positive counter-clockwise in the y-down image frame's
// complex-plane sense (z = u + i v). Pinned by the doctests:
//   roll +rho about camera +Z (towards the viewer) -> theta = -rho exactly
//   pan +phi about camera +Y  (left-rotate the view) -> t = (f tan phi, 0)
//   tilt +psi about camera +X (up-rotate the view)   -> t = (0, f tan psi)
//
// This file is MEASUREMENT code (core/tests); the production gyro path
// (Stabilization.cpp / Orientation*.cpp / Sync.cpp) is used READ-ONLY.
#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

#include <rawedit/Gyro.h>
#include <rawedit/ImageStab.h>
#include <rawedit/OrientationEstimate.h>

namespace rawedit {

// The derived image motion of one frame PAIR, in pixels at whatever
// resolution `fpx` is given at. tx/ty in pixels, theta in radians,
// s the scale (~1 for pure rotations).
struct GyroPairImage {
    double tx = 0.0;
    double ty = 0.0;
    double s = 1.0;
    double theta = 0.0;
};

namespace gyro_check_detail {

// Rotate v by the camera->world quaternion q (v_world = q v q*).
inline void rotateByQuat(const double q[4], const double v[3], double out[3]) {
    const double w = q[0], x = q[1], y = q[2], z = q[3];
    const double vx = v[0], vy = v[1], vz = v[2];
    out[0] = (w * w + x * x - y * y - z * z) * vx + 2 * (x * y - w * z) * vy
             + 2 * (x * z + w * y) * vz;
    out[1] = 2 * (x * y + w * z) * vx + (w * w - x * x + y * y - z * z) * vy
             + 2 * (y * z - w * x) * vz;
    out[2] = 2 * (x * z - w * y) * vx + 2 * (y * z + w * x) * vy
             + (w * w - x * x - y * y + z * z) * vz;
}

// Rotate v by conj(q) (world->camera).
inline void rotateByQuatInv(const double q[4], const double v[3], double out[3]) {
    const double qc[4] = {q[0], -q[1], -q[2], -q[3]};
    rotateByQuat(qc, v, out);
}

// Image projection of a camera-frame direction, y-down, center origin:
// u = f x / (-z), v = -f y / (-z)  (camera Y is up, image v is down).
inline void projectCam(const double c[3], double f, double out[2]) {
    const double zf = -c[2];
    out[0] = f * c[0] / zf;
    out[1] = -f * c[1] / zf;
}

}  // namespace gyro_check_detail

// The image motion a pure camera rotation qPrev -> qCur induces, extracted
// as a similarity in center-origin pixel coordinates at focal `fpx`
// (> 0, at the OUTPUT resolution being compared).
inline GyroPairImage gyroPairImageMotion(const double qPrev[4], const double qCur[4],
                                         double fpx) {
    using namespace gyro_check_detail;
    GyroPairImage m;
    if (!(fpx > 0.0)) return m;

    const double v0[3] = {0.0, 0.0, -1.0};
    const double v1[3] = {0.0, 0.5, -1.0};

    double w0[3], w1[3];
    rotateByQuat(qPrev, v0, w0);
    rotateByQuat(qPrev, v1, w1);
    double c0[3], c1[3];
    rotateByQuatInv(qCur, w0, c0);
    rotateByQuatInv(qCur, w1, c1);

    double pCur0[2], pCur1[2], pPrev1[2];
    projectCam(c0, fpx, pCur0);
    projectCam(c1, fpx, pCur1);
    projectCam(v1, fpx, pPrev1);  // previous frame: center ray is the origin

    m.tx = pCur0[0];
    m.ty = pCur0[1];
    const double dxp = pPrev1[0], dyp = pPrev1[1];
    const double ax = pCur1[0] - m.tx, ay = pCur1[1] - m.ty;
    const double nPrev = std::sqrt(dxp * dxp + dyp * dyp);
    const double nCur = std::sqrt(ax * ax + ay * ay);
    if (nPrev > 1e-12 && nCur > 1e-12) {
        m.s = nCur / nPrev;
        m.theta = std::atan2(ay, ax) - std::atan2(dyp, dxp);
    }
    return m;
}

// Predicted per-pair image motions for a whole clip, indexed like
// ImagePath::recs (element 0 unused: frame 0 has no predecessor). Element i
// is the predicted motion of the pair (i-1, i) at focal fpx. inside[i] = 1
// when BOTH bracketing frame instants map INSIDE the orientation series'
// span (clamped-end lookups are flagged 0: not a measurement there).
// offsetNs: the Sync.h bridge, imuTs[i] = ptsUs[i] * 1000 + offsetNs.
inline std::vector<GyroPairImage> predictPairsFromSeries(const OrientationSeries& series,
                                                         const std::vector<int64_t>& ptsUs,
                                                         int64_t offsetNs, double fpx,
                                                         std::vector<uint8_t>* inside) {
    std::vector<GyroPairImage> out(ptsUs.size());
    if (inside) inside->assign(ptsUs.size(), 0);
    if (ptsUs.size() < 2 || series.size() < 2) return out;
    const int64_t lo = series.firstTs(), hi = series.lastTs();
    std::vector<double> q(4 * ptsUs.size());
    for (size_t i = 0; i < ptsUs.size(); ++i)
        series.orientationAt(ptsUs[i] * 1000 + offsetNs, &q[4 * i]);
    for (size_t i = 1; i < ptsUs.size(); ++i) {
        const int64_t a = ptsUs[i - 1] * 1000 + offsetNs;
        const int64_t b = ptsUs[i] * 1000 + offsetNs;
        if (inside) (*inside)[i] = (a >= lo && b <= hi) ? 1 : 0;
        out[i] = gyroPairImageMotion(&q[4 * (i - 1)], &q[4 * i], fpx);
    }
    return out;
}

// Comparison of predicted vs measured pair motions over the usable pairs
// (inside the gyro span AND the measurement not Lost). Pixel numbers are
// at the predicted motion's resolution (fpx's frame height = workH).
struct GyroCompare {
    size_t n = 0;             // pairs used
    size_t nLost = 0;         // measured Lost pairs skipped
    size_t nOutside = 0;      // pairs outside the gyro span, skipped
    double txRms = 0;         // RMS of (pred - meas) x, px
    double tyRms = 0;         // RMS of (pred - meas) y, px
    double shiftRms = 0;      // RMS over pairs of hypot(pred - meas), px
    double thetaRmsDeg = 0;   // RMS of (pred - meas) theta, degrees
    double thetaRmsPx = 0;    // theta residual at the frame half-height, px
    double txCorr = 0;        // Pearson corr(pred, meas), x
    double tyCorr = 0;
    double thetaCorr = 0;
    double txSlope = 0;       // LSQ through origin: meas ~ slope * pred, x
    double tySlope = 0;       // (f_assumed / f_effective: a crop-factor probe)
    double p90 = 0;           // 90th percentile of |pred - meas|, px
};

// meas: per-record pair motions (toMotion(recs[i])), tx/ty in units of the
// working HEIGHT; workH scales them to px at the same height fpx uses.
inline GyroCompare compareGyroImage(const std::vector<GyroPairImage>& pred,
                                   const std::vector<PairMotion>& meas,
                                   double workH, const std::vector<uint8_t>& inside) {
    struct Pair { double dxp, dxm, dyp, dym, dtp, dtm; };
    std::vector<Pair> ps;
    GyroCompare c;
    double ssx = 0, ssy = 0, sst = 0;
    std::vector<double> shiftAbs;
    double ppTx = 0, pmTx = 0, ppTy = 0, pmTy = 0;
    for (size_t i = 1; i < pred.size() && i < meas.size(); ++i) {
        const bool inSpan = i < inside.size() ? inside[i] != 0 : true;
        if (!inSpan) { ++c.nOutside; continue; }
        if (meas[i].status == PairStatus::Lost) { ++c.nLost; continue; }
        const double mx = meas[i].tx * workH, my = meas[i].ty * workH;
        const double px = pred[i].tx - mx, py = pred[i].ty - my;
        const double pt = pred[i].theta - meas[i].theta;  // rad
        ssx += px * px;
        ssy += py * py;
        sst += pt * pt;
        shiftAbs.push_back(std::hypot(px, py));
        ps.push_back({pred[i].tx, mx, pred[i].ty, my, pred[i].theta, meas[i].theta});
        ppTx += pred[i].tx * pred[i].tx; pmTx += mx * pred[i].tx;
        ppTy += pred[i].ty * pred[i].ty; pmTy += my * pred[i].ty;
        ++c.n;
    }
    if (ps.empty()) return c;
    const double n = static_cast<double>(c.n);
    c.txRms = std::sqrt(ssx / n);
    c.tyRms = std::sqrt(ssy / n);
    c.shiftRms = std::sqrt((ssx + ssy) / n);
    c.thetaRmsDeg = std::sqrt(sst / n) * (180.0 / 3.14159265358979323846);
    c.thetaRmsPx = std::sqrt(sst / n) * (workH * 0.5);  // px at half-height
    double mpX = 0, mmX = 0, mpY = 0, mmY = 0, mpT = 0, mmT = 0;
    for (const Pair& p : ps) {
        mpX += p.dxp; mmX += p.dxm;
        mpY += p.dyp; mmY += p.dym;
        mpT += p.dtp; mmT += p.dtm;
    }
    mpX /= n; mmX /= n; mpY /= n; mmY /= n; mpT /= n; mmT /= n;
    double sXY = 0, vXX = 0, vYYm = 0, sYT = 0, vXXp = 0, vYYp = 0;
    double sTT = 0, vTTp = 0, vTTm = 0;
    for (const Pair& p : ps) {
        const double dxp = p.dxp - mpX, dxm = p.dxm - mmX;
        const double dyp = p.dyp - mpY, dym = p.dym - mmY;
        const double dtp = p.dtp - mpT, dtm = p.dtm - mmT;
        sXY += dxp * dxm; vXX += dxp * dxp; vYYm += dxm * dxm;
        sYT += dyp * dym; vXXp += dyp * dyp; vYYp += dym * dym;
        sTT += dtp * dtm; vTTp += dtp * dtp; vTTm += dtm * dtm;
    }
    c.txCorr = sXY / std::sqrt(vXX * vYYm + 1e-30);
    c.tyCorr = sYT / std::sqrt(vXXp * vYYp + 1e-30);
    c.thetaCorr = sTT / std::sqrt(vTTp * vTTm + 1e-30);
    c.txSlope = ppTx > 1e-12 ? pmTx / ppTx : 0;
    c.tySlope = ppTy > 1e-12 ? pmTy / ppTy : 0;
    std::sort(shiftAbs.begin(), shiftAbs.end());
    c.p90 = shiftAbs[(9 * shiftAbs.size()) / 10];
    return c;
}

}  // namespace rawedit
