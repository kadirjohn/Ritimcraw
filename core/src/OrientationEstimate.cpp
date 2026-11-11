// rawedit::OrientationEstimate — implementation. Conventions and method
// documentation live in OrientationEstimate.h; this file holds only the
// executable semantics and their inline citations.

#include <rawedit/OrientationEstimate.h>

#include <algorithm>
#include <cmath>
#include <limits>

#include "vqf.hpp"

namespace rawedit {
namespace {

// Quaternion helpers on double[4] locals (header inline versions are the
// public API; these keep the file's math self-contained and readable).
void quatNormalize(double q[4]) {
    const double n = std::sqrt(quatDot(q, q));
    if (n > 0.0) {
        q[0] /= n; q[1] /= n; q[2] /= n; q[3] /= n;
    }
}

// Exponential map of a rotation vector rv = w*dt (rad): the unit
// quaternion of that rotation. theta -> 0 limit is the identity.
void quatFromRotationVector(const double rv[3], double out[4]) {
    const double theta2 = rv[0] * rv[0] + rv[1] * rv[1] + rv[2] * rv[2];
    if (theta2 <= std::numeric_limits<double>::min()) {
        out[0] = 1.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0;
        return;
    }
    const double theta = std::sqrt(theta2);
    const double s = std::sin(theta / 2.0) / theta;
    out[0] = std::cos(theta / 2.0);
    out[1] = s * rv[0];
    out[2] = s * rv[1];
    out[3] = s * rv[2];
}

} // namespace

// ---------------------------------------------------------------------------
// SLERP (deliverable 5). Shipped over NLERP — the evaluation is in the
// header. Shortest-path: q1's sign is flipped when the dot with q0 is
// negative, so the interpolation never crosses the 2pi seam.
// ---------------------------------------------------------------------------
void slerp(const double q0[4], const double q1_[4], double t, double out[4]) {
    double q1[4] = {q1_[0], q1_[1], q1_[2], q1_[3]};
    double d = quatDot(q0, q1);
    if (d < 0.0) {
        // Shortest path: same rotation, opposite sign.
        q1[0] = -q1[0]; q1[1] = -q1[1]; q1[2] = -q1[2]; q1[3] = -q1[3];
        d = -d;
    }
    if (d > 1.0)
        d = 1.0; // clamp numerical noise

    const double theta = std::acos(d);
    if (theta < 1e-9) {
        // Nearly identical: lerp (also the t=0/t=1 exact ends).
        for (int i = 0; i < 4; ++i)
            out[i] = q0[i] + t * (q1[i] - q0[i]);
        quatNormalize(out);
        return;
    }
    const double sinTheta = std::sin(theta);
    const double c0 = std::sin((1.0 - t) * theta) / sinTheta;
    const double c1 = std::sin(t * theta) / sinTheta;
    for (int i = 0; i < 4; ++i)
        out[i] = c0 * q0[i] + c1 * q1[i];
    quatNormalize(out);
}

// ---------------------------------------------------------------------------
// Gyro-only integration baseline (deliverable 3)
// ---------------------------------------------------------------------------
OrientationSeries integrateGyro(const std::vector<ImuSample>& gyro) {
    OrientationSeries out;
    if (gyro.empty())
        return out;
    out.timestampsNs.reserve(gyro.size());
    out.quats.reserve(gyro.size() * 4);

    // q at the first timestamp is the identity: the world frame is defined
    // as the camera frame at that instant.
    double q[4] = {1.0, 0.0, 0.0, 0.0};
    out.timestampsNs.push_back(gyro[0].timestampNs);
    out.quats.insert(out.quats.end(), q, q + 4);

    for (size_t i = 1; i < gyro.size(); ++i) {
        const double dt = double(gyro[i].timestampNs - gyro[i - 1].timestampNs) * 1e-9;
        const double rv[3] = {double(gyro[i].x) * dt,
                              double(gyro[i].y) * dt,
                              double(gyro[i].z) * dt};
        double step[4];
        quatFromRotationVector(rv, step);
        double next[4];
        quatMultiply(q, step, next); // q * step: step applied first
        quatNormalize(next);
        q[0] = next[0]; q[1] = next[1]; q[2] = next[2]; q[3] = next[3];
        out.timestampsNs.push_back(gyro[i].timestampNs);
        out.quats.insert(out.quats.end(), q, q + 4);
    }
    return out;
}

// ---------------------------------------------------------------------------
// Accel resampling (deliverable 4): linear interpolation per component
// against timestamp, evaluated at every gyro timestamp. Method and edge
// policy documented in the header.
// ---------------------------------------------------------------------------
std::vector<ImuSample> resampleAccelOntoGyro(const std::vector<ImuSample>& accel,
                                             const std::vector<ImuSample>& gyro) {
    if (gyro.empty())
        return {};
    std::vector<ImuSample> out;
    out.reserve(gyro.size());
    if (accel.empty()) {
        // No accel at all: emit zeros (VQF's updateAcc ignores them, and
        // the zero vector is the "missing" marker in both loaders).
        for (const ImuSample& g : gyro)
            out.push_back(ImuSample{g.timestampNs, 0.0f, 0.0f, 0.0f});
        return out;
    }

    // Collapse equal timestamps (keep the LAST of each run — MCRAW keeps
    // duplicate timestamps and the newest reading wins).
    std::vector<const ImuSample*> src;
    src.reserve(accel.size());
    for (const ImuSample& a : accel) {
        if (!src.empty() && src.back()->timestampNs == a.timestampNs)
            src.back() = &a;
        else
            src.push_back(&a);
    }

    // Two-pointer walk: for each gyro timestamp, the bracketing accel
    // samples. Outside the accel span: nearest sample (constant
    // extension at the ends only).
    size_t j = 0;
    for (const ImuSample& g : gyro) {
        // Advance j so src[j] is the last sample <= g.t (clamped).
        while (j + 1 < src.size() && src[j + 1]->timestampNs <= g.timestampNs)
            ++j;
        if (j + 1 >= src.size() || src[j]->timestampNs == g.timestampNs) {
            // At/after the end, or an exact hit.
            const ImuSample& a = *src[std::min(j, src.size() - 1)];
            out.push_back(ImuSample{g.timestampNs, a.x, a.y, a.z});
            continue;
        }
        const ImuSample& a0 = *src[j];
        const ImuSample& a1 = *src[j + 1];
        if (g.timestampNs < src.front()->timestampNs) {
            // Before the start: nearest (src.front()).
            out.push_back(ImuSample{g.timestampNs, src.front()->x, src.front()->y, src.front()->z});
            continue;
        }
        const double span = double(a1.timestampNs - a0.timestampNs);
        const double t = span > 0.0 ? double(g.timestampNs - a0.timestampNs) / span : 0.0;
        out.push_back(ImuSample{
            g.timestampNs,
            float(a0.x + t * (a1.x - a0.x)),
            float(a0.y + t * (a1.y - a0.y)),
            float(a0.z + t * (a1.z - a0.z)),
        });
    }
    return out;
}

// ---------------------------------------------------------------------------
// Offline VQF (deliverable 2)
// ---------------------------------------------------------------------------
VqfOrientation estimateOrientationVqf(const std::vector<ImuSample>& gyro,
                                     const std::vector<ImuSample>& accel) {
    VqfOrientation out;
    if (gyro.empty())
        return out;

    // Mean gyro interval as the fixed sample time (near-uniform streams;
    // approximation documented in the header).
    const double span = double(gyro.back().timestampNs - gyro.front().timestampNs);
    const double gyrTs = gyro.size() > 1 ? span * 1e-9 / double(gyro.size() - 1) : 0.01;

    // gyroflow precedent (imu_integration/mod.rs, GPL — semantics only):
    // tau_acc = 40 s for video stabilization.
    VQFParams params;
    params.tauAcc = 40.0;
    VQF vqf(params, gyrTs, gyrTs); // accel resampled onto the gyro grid

    // Accel onto the gyro grid (deliverable 4); zero samples are ignored
    // by VQF's updateAcc.
    const std::vector<ImuSample> acc = resampleAccelOntoGyro(accel, gyro);

    out.series.timestampsNs.reserve(gyro.size());
    out.series.quats.reserve(gyro.size() * 4);
    for (size_t i = 0; i < gyro.size(); ++i) {
        // Record the state BEFORE consuming sample i, then advance with
        // sample i. VQF's updateGyr advances the state BY w_i*Ts (forward
        // hold: sample i's rate covers [t_i, t_{i+1}]), while integrateGyro
        // uses backward hold (sample i covers [t_{i-1}, t_i]); the two
        // agree exactly for constant rates and differ by at most one
        // sample's rotation when the rate varies. Recording before the
        // update is what makes the FIRST quaternion the identity (the
        // documented world-frame reference) and every recorded quaternion
        // the orientation AT its timestamp. Recording post-update instead
        // would lead the truth by one sample (w*Ts, e.g. 0.3 deg at
        // 30 deg/s and 100 Hz) and start the series at a non-identity
        // quaternion.
        double q[4];
        vqf.getQuat6D(q);
        out.series.timestampsNs.push_back(gyro[i].timestampNs);
        out.series.quats.insert(out.series.quats.end(), q, q + 4);
        const double g[3] = {double(gyro[i].x), double(gyro[i].y), double(gyro[i].z)};
        vqf.updateGyr(g);
        if (i < acc.size()) {
            const double a[3] = {double(acc[i].x), double(acc[i].y), double(acc[i].z)};
            vqf.updateAcc(a);
        }
    }

    // Final gravity direction in the CAMERA frame, from the LAST RECORDED
    // quaternion (the state at the last gyro timestamp) — not the
    // post-loop VQF state, which sits one sample past the end after the
    // last update. Gravity in the WORLD frame is (0, 0, -1) by
    // construction (VQF aligns earth +Z with the low-pass accel direction
    // = up); the camera-frame direction is conj(q) (0,0,-1) conj(q)* — the
    // direction a horizon lock needs, expressed where the camera is.
    const double* qLast = &out.series.quats[out.series.quats.size() - 4];
    const double gravityWorld[3] = {0.0, 0.0, -1.0};
    double qc[4];
    quatConj(qLast, qc);
    double gCam[3];
    quatRotate(qc, gravityWorld, gCam);
    out.gravityCamera[0] = gCam[0];
    out.gravityCamera[1] = gCam[1];
    out.gravityCamera[2] = gCam[2];
    if (accel.empty()) {
        out.gravityCamera[0] = out.gravityCamera[1] = out.gravityCamera[2] = 0.0;
    }

    double bias[3] = {0.0, 0.0, 0.0};
    vqf.getBiasEstimate(bias);
    out.gyroBias[0] = bias[0];
    out.gyroBias[1] = bias[1];
    out.gyroBias[2] = bias[2];
    return out;
}

// ---------------------------------------------------------------------------
// orientationAt (deliverable 5): SLERP between the bracketing samples,
// clamped at the ends. Binary search on the timestamp vector.
// ---------------------------------------------------------------------------
void OrientationSeries::orientationAt(int64_t t, double out[4]) const {
    if (timestampsNs.empty()) {
        out[0] = 1.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0;
        return;
    }
    if (t <= timestampsNs.front()) {
        std::copy(quats.begin(), quats.begin() + 4, out);
        return;
    }
    if (t >= timestampsNs.back()) {
        std::copy(quats.end() - 4, quats.end(), out);
        return;
    }
    // Bracketing pair: last sample <= t and its successor.
    const auto it = std::upper_bound(timestampsNs.begin(), timestampsNs.end(), t);
    const size_t hi = size_t(it - timestampsNs.begin());
    const size_t lo = hi - 1;
    const int64_t span = timestampsNs[hi] - timestampsNs[lo];
    const double f = span > 0 ? double(t - timestampsNs[lo]) / double(span) : 0.0;
    slerp(&quats[lo * 4], &quats[hi * 4], f, out);
}

} // namespace rawedit
