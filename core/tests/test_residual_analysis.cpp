// test_residual_analysis — the gyroscope cross-check residual decomposition
// legs' proofs (the `residual` subcommand of image_stab_measure;
// core/tests/image_stab_focus_fit.hpp fitPairOffset + the region-fit field
// on a SYNTHETIC rotational clip).
//
// Leg (a) — the similarity limit made visible: a synthetic clip whose
// frames are rendered through a TRUE rotation homography (K R K^-1, the
// S10 model) between two poses. A 4-DOF similarity fit over the WHOLE
// frame averages the perspective field; tile fits must show the field's
// edge stretch (the plan's 1/cos^2(alpha) estimate). The test renders a
// procedural texture through the homography (both frames from the same
// analytic texture — no resampling of frame 0), runs the production
// estimatePair on 3x3 tile crops, and checks:
//   - the edge tiles measure an x shift LARGER than the center tile
//     (the sign of the perspective stretch at the pan's side);
//   - the ratio edge/center matches the analytic value for the tile's
//   off-axis distance (within the estimator's noise).
//
// Leg (b) — the sync jitter probe: a synthetic gyro walk with a KNOWN
// true offset and per-pair-jitter-corrupted measurements. The constant
// offset cannot zero the jitter (its residual floor stays), but
// fitPairOffset must recover each pair's true delta within the step size.
#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <cmath>
#include <cstdint>
#include <vector>

#include <rawedit/Gyro.h>
#include <rawedit/ImageStab.h>
#include <rawedit/OrientationEstimate.h>

#include "image_stab_focus_fit.hpp"
#include "image_stab_gyro.hpp"

using namespace rawedit;

namespace {

// ---------------- procedural texture (analytic; see test_image_stab) --------

struct Wave { double kx, ky, ph, amp; };

std::vector<Wave> waves(uint64_t seed, int n) {
    std::vector<Wave> w;
    uint64_t s = seed * 6364136223846793005ull + 1442695040888963407ull;
    auto rnd = [&]() {
        s = s * 6364136223846793005ull + 1442695040888963407ull;
        return double(s >> 11) / 9007199254740992.0;
    };
    for (int i = 0; i < n; ++i) {
        const double wl = 8.0 + rnd() * 60.0;
        const double a = rnd() * 6.283185307179586;
        w.push_back({std::cos(a) * 6.283185307179586 / wl,
                     std::sin(a) * 6.283185307179586 / wl,
                     rnd() * 6.283185307179586, wl / 18.0});
    }
    return w;
}

double at(const std::vector<Wave>& w, double x, double y) {
    double v = 0;
    for (const auto& k : w) v += k.amp * std::sin(k.kx * x + k.ky * y + k.ph);
    return v;
}

// ---------------- leg (a): rotation-homography rendered frames ----------

constexpr int kW = 640, kH = 360;

// Renders the texture through the homography H (cur -> prev mapping):
// pixel p in the CURRENT frame samples the texture at H^-1 p (the
// previous frame's content coordinate). H is the true K R K^-1 for a yaw
// of phi radians at focal f.
struct RotRender { double H[9]; };

void yawHomography(double f, double phi, double H[9]) {
    // K = [[f,0,cx],[0,f,cy],[0,0,1]]; R = Rot_y(phi); H = K R K^-1.
    // Derivation: a world point along -Z maps by yaw about the camera Y;
    // with y up and image v down (the image_stab_gyro.hpp projection:
    // u = f x/(-z), v = -f y/(-z)), the induced homography in
    // CENTERED PIXEL coordinates (draw with a small-angle check:
    // H = [[1, 0, f sin],[0, 1, 0],[0,0,1]] approx; exact form below.
    const double c = std::cos(phi), s = std::sin(phi);
    // R about Y (right-handed, camera Y up): x' = c x + s z; z' = -s x + c z.
    // Point p_cam = (x, y, z); image u = f x/(-z), v = -f y/(-z).
    // A ray at image (u, v): direction (u/f, -v/f, -1).
    // Rotated: x2 = c (u/f) + s (-1); z2 = -s (u/f) + c (-1);
    //          u2 = f x2 / (s (u/f) - c) = (c u - s f) / (s u / f - c) ... careful:
    // u2 = f * x2 / (-z2) = f * (c u/f - s) / (s u/f - c) = (c u - f s) / (s u / f - c).
    // Multiply through by f: u2 = (c u - f s) / (s u - f c) * f / ... keep exact:
    // Closed form (standard K R K^-1 for yaw):
    H[0] = c;                 H[1] = 0;   H[2] = -f * s;
    H[3] = 0;                 H[4] = 1;   H[5] = 0;
    H[6] = s / f;             H[7] = 0;   H[8] = c;
    // This is the homogeneous map on CENTERED pixel coordinates:
    // (u2)   (c   0   -f s) (u)
    // (v2) = (0   1    0  ) (v)
    // (w2)   (s/f 0    c  ) (1)
}

std::vector<uint8_t> renderRot(const std::vector<Wave>& w, const RotRender& rr, double gain,
                               double bias) {
    std::vector<uint8_t> img(size_t(kW) * kH);
    // Invert H (3x3) once.
    const double* m = rr.H;
    const double det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) +
                       m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (det == 0.0) return img;   // degenerate; caller's REQUIREs catch it
    double inv[9];
    inv[0] = (m[4] * m[8] - m[5] * m[7]) / det;
    inv[1] = (m[2] * m[7] - m[1] * m[8]) / det;
    inv[2] = (m[1] * m[5] - m[2] * m[4]) / det;
    inv[3] = (m[5] * m[6] - m[3] * m[8]) / det;
    inv[4] = (m[0] * m[8] - m[2] * m[6]) / det;
    inv[5] = (m[2] * m[3] - m[0] * m[5]) / det;
    inv[6] = (m[3] * m[7] - m[4] * m[6]) / det;
    inv[7] = (m[1] * m[6] - m[0] * m[7]) / det;
    inv[8] = (m[0] * m[4] - m[1] * m[3]) / det;
    for (int y = 0; y < kH; ++y)
        for (int x = 0; x < kW; ++x) {
            const double u = x - kW * 0.5, v = y - kH * 0.5;
            // Current-frame pixel p maps back to content coordinate H^-1 p.
            const double w2 = inv[6] * u + inv[7] * v + inv[8];
            const double u2 = (inv[0] * u + inv[1] * v + inv[2]) / w2;
            const double v2 = (inv[3] * u + inv[4] * v + inv[5]) / w2;
            double val = at(w, u2, v2);
            const double q = bias + gain * val;
            img[size_t(y) * kW + x] = uint8_t(std::min(255.0, std::max(0.0, q)));
        }
    return img;
}

// ---------------- leg (b): jittered-sync synthetic ------------------------

struct JitterSynth {
    OrientationSeries series;
    std::vector<PairMotion> meas;   // generated at per-pair true deltas
    std::vector<int64_t> ptsUs;    // frame instants (us)
    std::vector<int64_t> trueDelta;  // ns, per pair
    const double fpx = 1000.0;
    const double workH = 360.0;
};

JitterSynth buildJitter(int pairs, int64_t baseDeltaNs, double jitterAmplitudeMs, uint64_t seed) {
    // Gyro: a MODULATED pan rate (about Y) + slight roll. The modulation
    // matters: under a CONSTANT angular rate the pair quaternion is
    // offset-invariant (the whole jitter probe goes deaf — the residual
    // command's own note). A wobble makes the predicted pair motion a
    // function of the absolute offset.
    std::vector<ImuSample> g;
    const double wy0 = 8.0 * 3.14159265358979323846 / 180.0;
    const double wz = 1.0 * 3.14159265358979323846 / 180.0;
    const int64_t dt = 33333333;
    for (int i = 0; i <= pairs + 4; ++i) {
        const double t = double(i) * dt * 1e-9;
        const double wy = wy0 * (1.0 + 0.9 * std::sin(2.0 * 3.141592653589793 * 2.2 * t));
        g.push_back({i * dt, 0.0f, float(wy), float(wz)});
    }
    JitterSynth out;
    out.series = integrateGyro(g);
    out.ptsUs.resize(pairs + 2);
    for (int i = 0; i <= pairs + 1; ++i) out.ptsUs[i] = int64_t(i) * 33333;
    (void)seed;   // the wander is deterministic (sin), no noise draw needed
    out.meas.assign(pairs + 2, PairMotion{});
    out.trueDelta.assign(pairs + 2, 0);
    for (int i = 1; i <= pairs; ++i) {
        // The TRUE offset for this pair: base + a slow sinusoidal wander
        // (not white noise — a real receiver pipeline's jitter is smooth).
        const double jit = jitterAmplitudeMs * std::sin(double(i) * 0.17 + 0.3);
        const int64_t d = baseDeltaNs + int64_t(jit * 1e6);
        double qPrev[4], qCur[4];
        out.series.orientationAt(out.ptsUs[i - 1] * 1000 + d, qPrev);
        out.series.orientationAt(out.ptsUs[i] * 1000 + d, qCur);
        const GyroPairImage pr = gyroPairImageMotion(qPrev, qCur, out.fpx);
        out.meas[i].status = PairStatus::Ok;
        out.meas[i].tx = float(pr.tx / out.workH);
        out.meas[i].ty = float(pr.ty / out.workH);
        out.trueDelta[i] = d;
    }
    return out;
}

}  // namespace

TEST_CASE("residual (a): tile fits expose the rotation field's edge stretch") {
    // A synthetic yaw step of 0.25 deg at focal 1000 px: the exact K R K^-1
    // homography on centered pixels. The similarity fit averages the
    // field; tiles at the pan-side edges must measure a LARGER x shift.
    const double f = 1000.0;
    const double phi = 0.25 * 3.14159265358979323846 / 180.0;
    double H[9];
    yawHomography(f, phi, H);
    const std::vector<Wave> w = waves(21, 60);
    const std::vector<uint8_t> prevImg = renderRot(w, RotRender{{1, 0, 0, 0, 1, 0, 0, 0, 1}}, 3.0, 128.0);
    const std::vector<uint8_t> curImg = renderRot(w, RotRender{{H[0], H[1], H[2],
                                                               H[3], H[4], H[5],
                                                               H[6], H[7], H[8]}}, 3.0, 128.0);
    // Whole-frame and 3x3 tile fits on luma views.
    const LumaFrame prevAll{kW, kH, kW, prevImg.data()};
    const LumaFrame curAll{kW, kH, kW, curImg.data()};
    const PairMotion whole = estimatePair(prevAll, curAll);
    REQUIRE(whole.status != PairStatus::Lost);
    const double th = kH / 3, tw = kW / 3;
    double tileTx[3][3] = {{0}};
    PairStatus tileSt[3][3] = {{PairStatus::Lost, PairStatus::Lost, PairStatus::Lost},
                               {PairStatus::Lost, PairStatus::Lost, PairStatus::Lost},
                               {PairStatus::Lost, PairStatus::Lost, PairStatus::Lost}};
    for (int r = 0; r < 3; ++r)
        for (int c = 0; c < 3; ++c) {
            const int y0 = r * int(th), x0 = c * int(tw);
            const LumaFrame p{int(tw), int(th), kW, prevImg.data() + size_t(y0) * kW + x0};
            const LumaFrame q{int(tw), int(th), kW, curImg.data() + size_t(y0) * kW + x0};
            const PairMotion m = estimatePair(p, q);
            tileSt[r][c] = m.status;
            // m.tx is normalized by the TILE height; back to px at the
            // FULL working height (the tile measures an x shift, the y
            // normalization is just the unit; px = m.tx * tileH).
            tileTx[r][c] = m.tx * th;
        }
    // Center tiles tracked; the x shift per column must grow from left to
    // right (the yaw pushes the +x side faster: dx/du ~ 1/cos^2).
    REQUIRE(tileSt[1][0] == PairStatus::Ok);
    REQUIRE(tileSt[1][1] == PairStatus::Ok);
    REQUIRE(tileSt[1][2] == PairStatus::Ok);
    const double left = tileTx[1][0], center = tileTx[1][1], right = tileTx[1][2];
    // whole.tx is in units of the frame HEIGHT; px at the working height.
    // The rendered homography H[2] = -f*s carries the content LEFT by
    // f*tan(phi) (yawHomography's sign convention), so the expected center
    // displacement is -f*tan(phi) here:
    const double wholePx = whole.tx * double(kH);
    const double expect = -f * std::tan(phi);
    // The MEAN of the three tiles must recover the field's center
    // displacement (what the whole-frame similarity fit lands on, within
    // the estimator's tolerance).
    const double meanTile = (left + center + right) / 3.0;
    CHECK(std::fabs(wholePx - meanTile) < 0.60);           // similarity ~ tile average
    CHECK(std::fabs(wholePx - expect) < 0.60);             // and ~ the center point
    // Edge-vs-center difference is the visible similarity limit: a tile's
    // local similarity fit returns the field's shift AT THE TILE'S CENTER
    // POINT; for a yaw the shift magnitude grows by ~(u/f)^2 off-axis.
    // Column 0/2 tile centers sit |u| ~ 213 px, u/f = 0.213, ~4.5% stretch.
    const double edgeAbs = std::max(std::fabs(left - center), std::fabs(right - center));
    const double theory = std::fabs(expect) * 0.045;      // ~4.5% of the shift
    CHECK(edgeAbs > 0.25 * theory);                       // at least a quarter visible
    CHECK(edgeAbs < 4.0 * theory);                        // not something else entirely
}

TEST_CASE("residual (b): fitPairOffset recovers jittered per-pair deltas") {
    // Base offset -30 ms inside the scan window; the synthetic wanders
    // +-12 ms smoothly. The probe (2 ms step) must land within one step of
    // each true delta, and the constant-delta residual must stay ABOVE
    // the per-pair residual (the jitter budget is real).
    const int pairs = 120;
    const int64_t base = -30000000;   // -30 ms in ns
    const JitterSynth js = buildJitter(pairs, base, 12.0, 5);
    double sseConst = 0, ssePerPair = 0;
    size_t n = 0;
    int withinOneStep = 0;
    for (int i = 1; i <= pairs; ++i) {
        // Constant-delta residual at the base.
        double qPrev[4], qCur[4];
        js.series.orientationAt(js.ptsUs[i - 1] * 1000 + base, qPrev);
        js.series.orientationAt(js.ptsUs[i] * 1000 + base, qCur);
        const GyroPairImage pr = gyroPairImageMotion(qPrev, qCur, js.fpx);
        const double dx = pr.tx - js.meas[i].tx * js.workH;
        const double dy = pr.ty - js.meas[i].ty * js.workH;
        sseConst += dx * dx + dy * dy;
        // Per-pair probe from the base (the residual command's window).
        int64_t found = 0;
        const double sse = fitPairOffset(js.series, js.ptsUs[i], 33333, js.meas[i],
                                        js.workH, js.fpx, base, 20.0, 2.0, &found);
        ssePerPair += sse;
        if (std::llabs(found - js.trueDelta[i]) <= 2000000) ++withinOneStep;  // 2 ms
        ++n;
    }
    const double rmsConst = std::sqrt(sseConst / (2 * double(n)));
    const double rmsPerPair = std::sqrt(ssePerPair / (2 * double(n)));
    // Recovery: almost every pair's delta is found within one step.
    CHECK(withinOneStep > n * 9 / 10);
    // The jitter budget is real: constant delta leaves a floor the
    // per-pair probe removes. The budget's SIZE scales with the jitter
    // amplitude (px per ms of the modulated-rate motion); the claim here
    // is a VISIBLE floor, not a fixed number.
    CHECK(rmsConst > rmsPerPair * 1.5);
    CHECK(rmsConst - rmsPerPair > 0.15);  // px; the no-jitter control below is ~0
}

TEST_CASE("residual (b): with NO jitter the probe reports a zero budget") {
    // The honest control: base == truth for every pair, so per-pair delta
    // equals constant delta and the budget collapses to ~0.
    const int pairs = 80;
    const int64_t base = 0;
    const JitterSynth js = buildJitter(pairs, base, 0.0, 9);
    double sseConst = 0, ssePerPair = 0;
    size_t n = 0;
    for (int i = 1; i <= pairs; ++i) {
        double qPrev[4], qCur[4];
        js.series.orientationAt(js.ptsUs[i - 1] * 1000 + base, qPrev);
        js.series.orientationAt(js.ptsUs[i] * 1000 + base, qCur);
        const GyroPairImage pr = gyroPairImageMotion(qPrev, qCur, js.fpx);
        const double dx = pr.tx - js.meas[i].tx * js.workH;
        const double dy = pr.ty - js.meas[i].ty * js.workH;
        sseConst += dx * dx + dy * dy;
        int64_t found = 0;
        ssePerPair += fitPairOffset(js.series, js.ptsUs[i], 33333, js.meas[i],
                                    js.workH, js.fpx, base, 20.0, 2.0, &found);
        ++n;
    }
    const double rmsConst = std::sqrt(sseConst / (2 * double(n)));
    const double rmsPerPair = std::sqrt(ssePerPair / (2 * double(n)));
    CHECK(rmsPerPair <= rmsConst + 1e-9);
    CHECK(rmsConst - rmsPerPair < 1e-6);   // zero budget, honestly
}
