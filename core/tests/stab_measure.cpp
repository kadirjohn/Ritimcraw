// stab_measure — stabilization quality on a real gyro log (desktop tool).
//
// Usage: stab_measure <file.gcsv> [strength=0.5] [zoomLimit=1.1] [fps=30]
//
// Loads the log the way the app does (parseGcsv -> axisRemapForImu ->
// estimateOrientationVqf), lays a synthetic frame clock over the gyro span
// (sync offset 0, known — isolates the smoothing/crop math from sync), runs
// computeStabilization, recovers each frame's applied rotation from H
// (R = D K^-1 H K D), and reports:
//
//   jerk  — RMS of the frame-to-frame change of angular velocity
//           (deg/frame^2): high-frequency shake. raw = the camera,
//           out = the stabilized virtual camera. ratio = out/raw (lower is
//           better; 1.0 = no stabilization).
//   resid — RMS angle between the virtual camera and a 1 s centered
//           moving-average of the camera path (deg): wobble left over.
//   corr  — mean / min applied correction scale (1.0 = full correction).
//   zoom  — required (unclamped) and applied static crop.
#include <rawedit/Gyro.h>
#include <rawedit/Orientation.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Stabilization.h>
#include <rawedit/Sync.h>

#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

struct M3 { double m[9]; };

M3 quatToMat(const double q[4]) {
    const double w = q[0], x = q[1], y = q[2], z = q[3];
    return {{1 - 2 * (y * y + z * z), 2 * (x * y - w * z), 2 * (x * z + w * y),
             2 * (x * y + w * z), 1 - 2 * (x * x + z * z), 2 * (y * z - w * x),
             2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y)}};
}
M3 mul(const M3& a, const M3& b) {
    M3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            double s = 0;
            for (int k = 0; k < 3; ++k) s += a.m[3 * i + k] * b.m[3 * k + j];
            r.m[3 * i + j] = s;
        }
    return r;
}
M3 tr(const M3& a) {
    M3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) r.m[3 * i + j] = a.m[3 * j + i];
    return r;
}
// log map: rotation vector (rad) of R
void logR(const M3& R, double v[3]) {
    const double c = std::fmax(-1.0, std::fmin(1.0, (R.m[0] + R.m[4] + R.m[8] - 1) / 2));
    const double th = std::acos(c);
    const double s = std::sin(th);
    const double k = th < 1e-9 ? 0.5 : th / (2 * s);
    v[0] = k * (R.m[7] - R.m[5]);
    v[1] = k * (R.m[2] - R.m[6]);
    v[2] = k * (R.m[3] - R.m[1]);
}
double angleDeg(const M3& R) {
    double v[3];
    logR(R, v);
    return std::sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) * 180.0 / M_PI;
}

// R from H: H = K D R D K^-1  ->  R ∝ D K^-1 H K D, re-orthonormalized.
M3 rotFromH(const double h[9], double f, double cx, double cy) {
    const M3 K{{f, 0, cx, 0, -f, cy, 0, 0, 1}};
    const M3 Ki{{1 / f, 0, -cx / f, 0, -1 / f, cy / f, 0, 0, 1}};
    const M3 D{{1, 0, 0, 0, 1, 0, 0, 0, -1}};
    const M3 H{{h[0], h[1], h[2], h[3], h[4], h[5], h[6], h[7], h[8]}};
    M3 R = mul(mul(mul(mul(D, Ki), H), K), D);
    // scale so det = 1, then Gram-Schmidt rows
    const double det = R.m[0] * (R.m[4] * R.m[8] - R.m[5] * R.m[7]) -
                       R.m[1] * (R.m[3] * R.m[8] - R.m[5] * R.m[6]) +
                       R.m[2] * (R.m[3] * R.m[7] - R.m[4] * R.m[6]);
    const double s = std::cbrt(det);
    for (double& e : R.m) e /= s;
    return R;
}

// Frames skipped at each end: the orientation filter's start-up
// convergence (several degrees in the first ~0.5 s) is not camera shake.
size_t gSkip = 15;

double jerkRms(const std::vector<M3>& path) {
    std::vector<std::array<double, 3>> w;
    for (size_t i = gSkip + 1; i + gSkip < path.size(); ++i) {
        double v[3];
        logR(mul(tr(path[i - 1]), path[i]), v);
        w.push_back({v[0], v[1], v[2]});
    }
    double acc = 0;
    size_t n = 0;
    for (size_t i = 1; i < w.size(); ++i) {
        double d2 = 0;
        for (int k = 0; k < 3; ++k) d2 += (w[i][k] - w[i - 1][k]) * (w[i][k] - w[i - 1][k]);
        acc += d2;
        ++n;
    }
    return n ? std::sqrt(acc / n) * 180.0 / M_PI : 0;
}

} // namespace

#include <array>

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: %s file.gcsv [strength] [zoomLimit] [fps] [mode]\n", argv[0]);
        return 2;
    }
    const double strength = argc > 2 ? std::atof(argv[2]) : 0.5;
    const double zoomLimit = argc > 3 ? std::atof(argv[3]) : 1.1;
    const double fps = argc > 4 ? std::atof(argv[4]) : 30.0;
    const int W = 3840, H = 2160;

    auto parsed = parseGcsv(std::string(argv[1]));
    if (!parsed.isOk()) {
        std::fprintf(stderr, "parse failed: %s\n", parsed.detail.c_str());
        return 1;
    }
    ImuData imu = std::move(*parsed.value);
    const AxisRemap remap = axisRemapForImu(imu);
    std::vector<ImuSample> gyro, accel;
    for (auto s : imu.gyro) { remap.apply(s.x, s.y, s.z); gyro.push_back(s); }
    for (auto s : imu.accel) { remap.apply(s.x, s.y, s.z); accel.push_back(s); }
    VqfOrientation ori = estimateOrientationVqf(gyro, accel);
    if (std::getenv("STAB_GYRO_ONLY")) {
        // Bias-corrected pure gyro integration (no accel tilt corrections).
        std::vector<ImuSample> g2 = gyro;
        for (auto& s : g2) {
            s.x -= static_cast<float>(ori.gyroBias[0]);
            s.y -= static_cast<float>(ori.gyroBias[1]);
            s.z -= static_cast<float>(ori.gyroBias[2]);
        }
        ori.series = integrateGyro(g2);
    }

    std::vector<int64_t> frameTs;
    const int64_t step = static_cast<int64_t>(1e9 / fps);
    for (int64_t t = ori.series.firstTs(); t <= ori.series.lastTs(); t += step) frameTs.push_back(t);

    StabilizationParams p;
    p.mode = StabilizationMode::Smooth;
    p.strength = strength;
    p.zoomLimit = zoomLimit;
    // 35mm-equivalent focal from the MotionCam file name (..._24mm.gcsv),
    // or STAB_FOCAL_MM; 23.95 (the main camera) otherwise.
    double focalMm = 23.95;
    {
        const std::string path(argv[1]);
        const size_t mm = path.rfind("mm");
        if (mm != std::string::npos) {
            size_t b = mm;
            while (b > 0 && std::isdigit(static_cast<unsigned char>(path[b - 1]))) --b;
            if (b < mm) focalMm = std::atof(path.substr(b, mm - b).c_str());
        }
        if (const char* e = std::getenv("STAB_FOCAL_MM")) focalMm = std::atof(e);
    }
    p.focalLengthPx = focalLengthPxFromEquivalent(focalMm, W);
    p.width = W;
    p.height = H;
    if (std::getenv("STAB_LEGACY")) { p.adaptiveSmoothing = false; p.dynamicZoom = false; }
    // STAB_OFFSET_MS: the plan uses a WRONG sync offset (the output is
    // still judged against the true clock) — sync-error sensitivity.
    const double offErr = std::getenv("STAB_OFFSET_MS") ? std::atof(std::getenv("STAB_OFFSET_MS")) : 0.0;
    const SyncModel model = manualOffsetMs(offErr).model;
    StabilizationResult r = computeStabilization(ori.series, model, frameTs, ori.gravityCamera,
                                                 true, true, p);

    const double cx = W / 2.0, cy = H / 2.0;
    std::vector<M3> raw, out;
    for (size_t i = 0; i < frameTs.size(); ++i) {
        double q[4];
        ori.series.orientationAt(frameTs[i], q);
        const M3 Ra = quatToMat(q);
        const M3 Rrel = rotFromH(r.frames[i].h, p.focalLengthPx, cx, cy);
        raw.push_back(Ra);
        // q_rel = conj(q_t) q_a  ->  R_t = R_a R_rel^T
        out.push_back(mul(Ra, tr(Rrel)));
    }
    // residual vs a 1 s centered moving average (mean of rotation vectors
    // relative to the frame — fine for small angles)
    const int half = static_cast<int>(fps / 2);
    double resid = 0;
    size_t residN = 0;
    for (size_t i = gSkip; i + gSkip < out.size(); ++i) {
        ++residN;
        double m[3] = {0, 0, 0};
        int c = 0;
        for (int j = -half; j <= half; ++j) {
            const long k = static_cast<long>(i) + j;
            if (k < 0 || k >= static_cast<long>(raw.size())) continue;
            double v[3];
            logR(mul(tr(out[i]), raw[k]), v);
            for (int a = 0; a < 3; ++a) m[a] += v[a];
            ++c;
        }
        double a2 = 0;
        for (double e : m) a2 += (e / c) * (e / c);
        resid += a2;
    }
    resid = std::sqrt(resid / residN) * 180.0 / M_PI;

    double meanCorr = 0, minCorr = 1;
    for (double a : r.correctionScale) { meanCorr += a; minCorr = std::fmin(minCorr, a); }
    if (!r.correctionScale.empty()) meanCorr /= r.correctionScale.size();
    double maxZoomFrame = 0, meanZoomFrame = 0;
    for (auto& f : r.frames) { maxZoomFrame = std::fmax(maxZoomFrame, f.zoom); meanZoomFrame += f.zoom; }
    meanZoomFrame /= r.frames.size();

    if (std::getenv("STAB_DUMP")) {
        for (size_t i = 0; i < r.frames.size(); ++i)
            std::printf("f%zu a=%.3f z=%.3f ang=%.2f\n", i, r.correctionScale[i], r.frames[i].zoom,
                        r.relativeAngleDeg[i]);
    }
    const double jr = jerkRms(raw), jo = jerkRms(out);
    std::printf("s=%.2f zl=%.2f frames=%zu  jerk raw=%.4f out=%.4f ratio=%.3f  resid=%.3fdeg  "
                "corr mean=%.3f min=%.3f  zoom req=%.3f applied=%.3f frame(mean=%.3f max=%.3f)\n",
                strength, zoomLimit, frameTs.size(), jr, jo, jo / jr, resid, meanCorr, minCorr,
                r.requiredZoom, r.appliedZoom, meanZoomFrame, maxZoomFrame);
    (void)angleDeg;
    return 0;
}
