// GY-04 optical sync refinement tests.
//
// The audit's order (section D): validate on SYNTHETIC ground truth with
// independently generated rotations BEFORE real frames. The synthetic
// pipeline here is the real mechanism end to end:
//   - a textured base image (gradients + seeded LCG noise — the
//     weak-texture gate must see real texture),
//   - an independently generated camera-rotation timeline (small-angle
//     quaternions per frame, NOT derived from the sync path),
//   - frame i = base warped by the homography of conj(q_i)*q_0 via
//     frameTransform + warpFrame (the production warp — the residual is
//     in the production's own terms),
//   - the RAW orientation series built from the SAME quaternions on a
//     denser grid, with the frame↔IMU offset the test must RECOVER.
//
// The truth offset is what the refinement must find: |refined − truth|
// <= 2 ms on the on-anchor case and on a deliberately wrong (+80 ms)
// anchor. The flat-window, moving-blob and boundary cases pin the
// honesty rules (not-Refined, keep the anchor, no crash).
//
// The real-clip case is RAWEDIT_REALCLIP-gated (2 GB fixtures) and is
// INFORMATIONAL by design: it asserts the refinement RUNS on the real
// pipeline and the verdict is honest (Refined -> a bounded offset;
// not-Refined -> the anchor kept), never a specific offset — the real
// clips have no optical ground truth.
//
// Fixture facts quoted from test_sync.cpp (measured there): 004150
// anchored offset +367 ms, 004208 +351 ms.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/OpticalSync.h>
#include <rawedit/Sync.h>
#include <rawedit/Stabilization.h>
#include <rawedit/Warp.h>
#include <rawedit/Orientation.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Gyro.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>

#include <cmath>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <algorithm>
#include <string>
#include <vector>

#include <motioncam/Decoder.hpp>

using namespace rawedit;

namespace {

bool realclipEnabled() {
    return std::getenv("RAWEDIT_REALCLIP") != nullptr;
}

bool fixtureAvailable(const char* relpath) {
    std::FILE* f = std::fopen(relpath, "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

// ---------------------------------------------------------------------------
// Synthetic scene generator (the ground-truth side; nothing here touches
// the sync/refine path's internals).
// ---------------------------------------------------------------------------

// Deterministic LCG (seeded, reproducible; no <random> dependency so the
// binary stays tiny and the sequence is identical on every host).
struct Lcg {
    uint64_t s;
    explicit Lcg(uint64_t seed) : s(seed * 6364136223846793005ULL + 1442695040888963407ULL) {}
    uint32_t next() {
        s = s * 6364136223846793005ULL + 1442695040888963407ULL;
        return static_cast<uint32_t>(s >> 33);
    }
    double next01() { return static_cast<double>(next()) / 4294967296.0; }
};

// Textured base: smooth gradients (large-scale structure the translation
// search locks onto) + seeded noise (the texture gate's variance floor).
ImageF texturedBase(uint32_t w, uint32_t h, uint64_t seed) {
    ImageF img;
    img.w = w;
    img.h = h;
    img.rgb.assign(static_cast<size_t>(w) * h * 3, 0.0f);
    Lcg rng(seed);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            const size_t i = 3 * (static_cast<size_t>(y) * w + x);
            // Gradients: diagonal ramps + a couple of soft blobs (the
            // low-frequency structure a shift search needs).
            const double gx = static_cast<double>(x) / w;
            const double gy = static_cast<double>(y) / h;
            double v = 0.30 + 0.25 * gx + 0.20 * gy;
            const double b1 = std::exp(-((gx - 0.30) * (gx - 0.30) +
                                        (gy - 0.35) * (gy - 0.35)) / 0.010);
            const double b2 = std::exp(-((gx - 0.72) * (gx - 0.72) +
                                        (gy - 0.62) * (gy - 0.62)) / 0.006);
            v += 0.30 * b1 + 0.22 * b2;
            // Fine texture: smooth-ish noise (bilinear-interpolated
            // coarse noise so it survives the 3x3 blur).
            const double nx = rng.next01(), ny = rng.next01();
            v += 0.035 * (nx + ny - 1.0);
            const float f = static_cast<float>(v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v));
            img.rgb[i + 0] = f;
            img.rgb[i + 1] = f;
            img.rgb[i + 2] = f;
        }
    return img;
}

// Axis-angle to quaternion [w,x,y,z].
void axisAngleQuat(double ax, double ay, double az, double angleRad, double out[4]) {
    const double n = std::sqrt(ax * ax + ay * ay + az * az);
    if (n < 1e-12 || std::fabs(angleRad) < 1e-15) {
        out[0] = 1.0; out[1] = 0.0; out[2] = 0.0; out[3] = 0.0;
        return;
    }
    const double s = std::sin(angleRad / 2.0) / n;
    out[0] = std::cos(angleRad / 2.0);
    out[1] = ax * s;
    out[2] = ay * s;
    out[3] = az * s;
}

// The synthetic clip: N frames at a 30 fps interval with per-frame
// camera rotations q_i — the trajectory KNOTS, defined at the frames'
// representative instants — the base warped by conj(q_i)*q_0 (the
// production homography path), and the orientation series reproducing
// the SAME trajectory exactly on a 2x-denser grid (knots land ON grid
// points, so orientationAt returns them with zero interpolation error).
// The series timestamps sit on the IMU clock: series_t = frameClock_t +
// truthNs.
struct SyntheticClip {
    std::vector<OpticalSyncFrame> frames;
    OrientationSeries series;
    int64_t truthOffsetNs = 0;
    int64_t frameIntervalNs = 0;
    std::vector<double> frameQuats; // 4n, the per-frame camera rotations
};

// angleDegPerFrame: the hand-shake class (deg of PAN per frame). 0.30
// at ~30 fps is the fixture clips' class: ~3 deg/s of pan plus a
// ~9 Hz tremor (amplitudes 0.30/0.22/0.12 deg on X/Y/Z — peak angular
// rates ~17/12/6 deg/s). Pairs must move enough pixels to measure.
//
// IDENTIFIABILITY (why the tremor band): a constant-rate pan
// contributes ZERO offset sensitivity — at any offset the inter-frame
// rotation is the same, so the predicted displacement is flat in the
// offset and the cost curve has no well. The offset information lives
// ONLY in the trajectory's non-linearity (the second difference of the
// rotation): an offset error of dt mispredicts the inter-frame
// displacement by ~(second difference)*dt/interval. The ~9 Hz tremor
// sampled at ~30 fps carries a ~0.8 deg/frame second difference —
// ~0.1 px of predicted displacement per ms of offset error at the
// test's focal length. The pass-2 measurement grid is 0.5 px, so a
// single pair pins the offset only to ~5 ms; the Huber sum over a
// window's ~10 independent pairs averages the quantization down to
// ~0.5 ms — comfortably inside the 2 ms accuracy target with the fine
// grid at 0.1 ms. (This is the physiological hand-tremor band, not a
// test-tuned signal: real hand shake IS tremor-band, which is why the
// real clips refine at all.)
SyntheticClip makeSyntheticClip(int nFrames, int64_t truthOffsetMs,
                                double angleDegPerFrame, uint64_t seed,
                                uint32_t w = 320, uint32_t h = 180,
                                double focalPx = 240.0) {
    SyntheticClip sc;
    // ~30 fps, chosen EVEN so the half-interval series grid below is an
    // exact subdivision (no truncation drift between knot times and the
    // frames' repTime instants).
    sc.frameIntervalNs = 33333332;
    sc.truthOffsetNs = truthOffsetMs * 1000000;
    const double intervalSec =
        static_cast<double>(sc.frameIntervalNs) / 1e9;

    // The trajectory KNOTS q_i at the frames' representative instants
    // u_i = i + 0.5 (frame i's content time on the frame clock): a
    // smooth pan about Y plus the hand-tremor sinusoids — INDEPENDENT
    // of the sync path (generated here, consumed as data). The LCG
    // fixes only the tremor phases; the trajectory itself is analytic
    // in the (possibly negative — the series pad needs headroom)
    // knot index, so every knot is well-defined and reproducible.
    Lcg rng(seed);
    const double phX = 2.0 * M_PI * rng.next01();
    const double phY = 2.0 * M_PI * rng.next01();
    const double phZ = 2.0 * M_PI * rng.next01();
    const double ampX = angleDegPerFrame * 1.00;
    const double ampY = angleDegPerFrame * 0.73;
    const double ampZ = angleDegPerFrame * 0.40;
    const double fX = 8.7, fY = 9.3, fZ = 7.9; // Hz, incommensurate
    auto knotQuat = [&](int i, double out[4]) {
        const double u = static_cast<double>(i) + 0.5; // frame units
        const double t = u * intervalSec;
        const double panDeg = u * angleDegPerFrame * 0.35; // smooth pan
        const double jx = ampX * std::sin(2.0 * M_PI * fX * t + phX);
        const double jy = ampY * std::sin(2.0 * M_PI * fY * t + phY);
        const double jz = ampZ * std::sin(2.0 * M_PI * fZ * t + phZ);
        double q[4];
        axisAngleQuat(0.0, 1.0, 0.0, panDeg * M_PI / 180.0, q); // pan about Y
        double qj[4];
        axisAngleQuat(1.0, 0.0, 0.0, jx * M_PI / 180.0, qj);
        double q2[4];
        quatMultiply(q, qj, q2);
        double qjy[4];
        axisAngleQuat(0.0, 1.0, 0.0, jy * M_PI / 180.0, qjy);
        double q2y[4];
        quatMultiply(q2, qjy, q2y);
        double qk[4];
        axisAngleQuat(0.0, 0.0, 1.0, jz * M_PI / 180.0, qk);
        quatMultiply(q2y, qk, out);
    };

    // Frames: the base warped by conj(q_i)*q_0 — the frame-0-view ->
    // frame-i-view direction the production contract uses (H maps
    // SOURCE (frame 0's content) to OUTPUT (frame i's view), the same
    // convention predictedTranslation in OpticalSync.cpp works in).
    // Frame i is rendered AT its knot quat (the trajectory evaluated at
    // the representative instant), so the refinement querying
    // repTime + truthOffset finds it exactly.
    const ImageF base = texturedBase(w, h, seed);
    double q0[4];
    knotQuat(0, q0);
    sc.frames.resize(static_cast<size_t>(nFrames));
    sc.frameQuats.assign(4 * static_cast<size_t>(nFrames), 0.0);
    for (int i = 0; i < nFrames; ++i) {
        double qi[4];
        knotQuat(i, qi);
        sc.frameQuats[4 * i + 0] = qi[0];
        sc.frameQuats[4 * i + 1] = qi[1];
        sc.frameQuats[4 * i + 2] = qi[2];
        sc.frameQuats[4 * i + 3] = qi[3];
        double qic[4];
        quatConj(qi, qic);
        double qrel[4];
        quatMultiply(qic, q0, qrel); // conj(q_i)*q_0: frame-0 -> frame-i
        double H[9];
        frameTransform(qrel, focalPx, static_cast<int>(w), static_cast<int>(h), H);
        const double cx = static_cast<double>(w) / 2.0;
        const double cy = static_cast<double>(h) / 2.0;
        sc.frames[i].image = warpFrame(base, H, 1.0, cx, cy);
        sc.frames[i].captureNs = static_cast<int64_t>(i) * sc.frameIntervalNs;
        // repTime fallback: capture + 0.5*interval (the documented
        // formation; the refinement must be handed the SAME one the
        // consumer will use — here it is consistent by construction).
        sc.frames[i].repTimeNs =
            sc.frames[i].captureNs + sc.frameIntervalNs / 2;
    }

    // Orientation series: the SAME analytic trajectory evaluated on a
    // 2x-denser grid (step = interval/2), timestamps on the IMU clock
    // (frameClock + truth). The grid is KNOT-ALIGNED: the knots sit at
    // u = i + 0.5, i.e. exactly ON odd grid points, so orientationAt
    // returns each knot with f = 0 (zero interpolation error) and every
    // intra-knot query interpolates between two samples of the SAME
    // knot pair — slerp of two points on one geodesic stays on it with
    // a LINEAR parameter, so the piecewise-geodesic inter-knot curve is
    // reproduced EXACTLY everywhere. The residual at the truth offset
    // is then zero up to warp/measure quantization.
    //
    // The pad (8 frame intervals each side) covers the full +-150 ms
    // search range around any anchor error the tests use (+80 ms worst
    // case): every candidate time repTime + offset lands INSIDE the
    // series span, so no query ever clamps to a held end quaternion
    // (a clamped query flattens the predicted displacement to 0 and
    // fakes a flat window).
    const int per = 2;
    const int pad = 8; // frame intervals of headroom on each side
    const int64_t gridStep = sc.frameIntervalNs / per;
    const int64_t firstGridT = -static_cast<int64_t>(pad) * sc.frameIntervalNs;
    const int64_t lastGridT =
        (static_cast<int64_t>(nFrames) + pad) * sc.frameIntervalNs;
    const size_t nSamples =
        static_cast<size_t>((lastGridT - firstGridT) / gridStep + 1);
    sc.series.timestampsNs.resize(nSamples);
    sc.series.quats.resize(4 * nSamples);
    for (size_t k = 0; k < nSamples; ++k) {
        const int64_t tFrame = firstGridT + static_cast<int64_t>(k) * gridStep;
        sc.series.timestampsNs[k] = tFrame + sc.truthOffsetNs;
        // The trajectory at tFrame, in frame units u = tFrame/interval:
        // the knots sit at u = i + 0.5, so the bracketing knot pair is
        // (floor(u - 0.5), floor(u - 0.5) + 1) and the exact slerp
        // fraction is u - 0.5 - floor(u - 0.5) (0 at the knots, 0.5 at
        // the interval midpoints — the geodesic midpoint, also exact).
        const double u =
            static_cast<double>(tFrame) / static_cast<double>(sc.frameIntervalNs);
        const int i0 = static_cast<int>(std::floor(u - 0.5));
        const double frac = u - 0.5 - static_cast<double>(i0);
        double qa[4], qb[4], out[4];
        knotQuat(i0, qa);
        knotQuat(i0 + 1, qb);
        slerp(qa, qb, frac, out);
        sc.series.quats[4 * k + 0] = out[0];
        sc.series.quats[4 * k + 1] = out[1];
        sc.series.quats[4 * k + 2] = out[2];
        sc.series.quats[4 * k + 3] = out[3];
    }
    return sc;
}

// A convenience anchored estimate at the given offset (the refinement's
// starting point; the anchor's bound is the shipped 25 ms).
SyncEstimate anchorAt(int64_t offsetMs) {
    SyncEstimate est;
    est.model.offsetNs = offsetMs * 1000000;
    est.confidence = SyncConfidence::Anchored;
    est.source = "anchored:test";
    est.errorBoundMs = 25.0;
    return est;
}

// ---------------------------------------------------------------------------
// Real-fixture helpers (the same read-only pattern as test_sync /
// test_warp; quoted numbers live there).
// ---------------------------------------------------------------------------

long long gcsvHeaderTimestampMs(const std::string& path) {
    std::FILE* f = std::fopen(path.c_str(), "rb");
    if (!f)
        return -1;
    std::string text;
    char buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0)
        text.append(buf, n);
    std::fclose(f);
    const std::string key = "\ntimestamp,";
    const size_t at = text.find(key);
    if (at == std::string::npos)
        return -1;
    return std::atoll(text.c_str() + at + key.size());
}

struct ContainerClocks {
    std::vector<int64_t> captureNs;
    long long frame0RecvdMs = -1;
};

ContainerClocks containerClocks(const std::string& path) {
    ContainerClocks cc;
    try {
        motioncam::Decoder decoder(path);
        const auto& ts = decoder.getFrames();
        cc.captureNs.assign(ts.begin(), ts.end());
        if (!ts.empty()) {
            nlohmann::json meta;
            decoder.loadFrameMetadata(ts[0], meta);
            if (meta.contains("recvdTimestampMs")) {
                if (meta["recvdTimestampMs"].is_string())
                    cc.frame0RecvdMs =
                        std::stoll(meta["recvdTimestampMs"].get<std::string>());
                else if (meta["recvdTimestampMs"].is_number())
                    cc.frame0RecvdMs = meta["recvdTimestampMs"].get<long long>();
            }
        }
    } catch (...) {
    }
    return cc;
}

} // namespace

// ---------------------------------------------------------------------------
// 1. Synthetic ground truth: the refinement recovers a known offset.
// ---------------------------------------------------------------------------

TEST_CASE("synthetic ground truth: on-anchor refinement recovers the offset") {
    // Truth offset +367 ms (the 004150 fixture's anchored value — the
    // class of constant the audit wants measured, not assumed); the
    // anchor STARTS at the truth (the refinement must stay there, not
    // wander).
    const int64_t truthMs = 367;
    SyntheticClip sc = makeSyntheticClip(30, truthMs, 0.30, 42);
    REQUIRE(sc.frames.size() == 30);

    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = 240.0;
    const OpticalSyncResult r =
        refineOffsetOptically(anchorAt(truthMs), sc.frames, sc.series, intr);
    MESSAGE("provenance: " << r.provenance);
    REQUIRE(r.refined);
    const double errMs =
        std::fabs(static_cast<double>(r.refinedOffsetNs - sc.truthOffsetNs) / 1e6);
    MESSAGE("on-anchor: |refined - truth| = " << errMs << " ms"
            << " (bound " << r.errorBoundMs << " ms)");
    CHECK(errMs <= 2.0);
    CHECK(r.errorBoundMs > 0.0);
    CHECK(r.errorBoundMs <= 25.0); // must IMPROVE on the anchor's bound
    // The provenance names the repTime source (visibility requirement).
    CHECK(r.provenance.find("refined:optical") == 0);
    CHECK(r.provenance.find("repTime=") != std::string::npos);
}

TEST_CASE("synthetic ground truth: repTime mapping is the binding one") {
    // The refined offset is defined against repTimeNs (OpticalSync.h's
    // MAPPING CONTRACT). Feeding the refinement frames WITHOUT the
    // representative-time correction (repTimeNs = 0 -> captureNs) with
    // the SAME series must find a DIFFERENT refined offset — shifted by
    // the half-interval the fallback adds — proving the consumer cannot
    // silently swap mappings. Both runs must still be self-consistent:
    // each run's refined offset reproduces the series' truth under ITS
    // OWN time base.
    const int64_t truthMs = 200;
    SyntheticClip sc = makeSyntheticClip(30, truthMs, 0.30, 43);
    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = 240.0;

    const OpticalSyncResult withRep =
        refineOffsetOptically(anchorAt(truthMs), sc.frames, sc.series, intr);
    REQUIRE(withRep.refined);

    std::vector<OpticalSyncFrame> raw = sc.frames;
    for (OpticalSyncFrame& f : raw)
        f.repTimeNs = 0; // captureNs itself
    const OpticalSyncResult noRep =
        refineOffsetOptically(anchorAt(truthMs), raw, sc.series, intr);
    REQUIRE(noRep.refined);
    const int64_t half = sc.frameIntervalNs / 2;
    MESSAGE("withRep offset = " << withRep.refinedOffsetNs
            << " ns, noRep offset = " << noRep.refinedOffsetNs
            << " ns, half-interval = " << half << " ns");
    // The two runs use different time bases, so their refined offsets
    // differ by ~the half-interval (the repTime correction's size).
    // SIGN: the series timestamps are fixed (frame clock + truth). The
    // withRep run queries repTime + offset = capture + half + offset,
    // which reproduces the trajectory at offset = truth. The noRep run
    // queries capture + offset' — to land on the same series instants
    // it must ADD the half-interval back: offset' = truth + half. So
    // withRep - noRep = -half.
    const double diffNs = static_cast<double>(withRep.refinedOffsetNs -
                                              noRep.refinedOffsetNs);
    CHECK(std::fabs(diffNs + static_cast<double>(half)) < 2.0e6); // ~2 ms
}

// ---------------------------------------------------------------------------
// 2. Wrong anchor (+80 ms): the search must recover the truth.
// ---------------------------------------------------------------------------

TEST_CASE("synthetic ground truth: +80 ms wrong anchor recovers the truth") {
    const int64_t truthMs = 367;
    SyntheticClip sc = makeSyntheticClip(30, truthMs, 0.30, 44);
    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = 240.0;
    // The anchor is 80 ms WRONG (the receive-lag class of error the
    // audit says the ±25 ms bound may not cover).
    const OpticalSyncResult r =
        refineOffsetOptically(anchorAt(truthMs + 80), sc.frames, sc.series, intr);
    MESSAGE("provenance: " << r.provenance);
    REQUIRE(r.refined);
    const double errMs =
        std::fabs(static_cast<double>(r.refinedOffsetNs - sc.truthOffsetNs) / 1e6);
    MESSAGE("+80 ms anchor: |refined - truth| = " << errMs << " ms"
            << " (bound " << r.errorBoundMs << " ms)");
    CHECK(errMs <= 2.0);
}

// ---------------------------------------------------------------------------
// 3. Flat / uninformative window: NOT refined, anchor kept.
// ---------------------------------------------------------------------------

TEST_CASE("flat window: not refined, anchor kept") {
    // A flat gray clip: no texture, no motion. The refinement must say
    // not-Refined and the fold must keep the anchor untouched.
    const int64_t anchorMs = 367;
    SyntheticClip sc = makeSyntheticClip(30, anchorMs, 0.0, 45); // no motion
    // Overwrite with flat frames (no texture either).
    for (OpticalSyncFrame& f : sc.frames) {
        f.image.w = 320;
        f.image.h = 180;
        f.image.rgb.assign(320 * 180 * 3, 0.4f);
    }
    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = 240.0;
    const OpticalSyncResult r =
        refineOffsetOptically(anchorAt(anchorMs), sc.frames, sc.series, intr);
    MESSAGE("provenance: " << r.provenance);
    CHECK_FALSE(r.refined);
    CHECK(r.refinedOffsetNs == 0);
    // The fold keeps the anchor (and appends the rejection note).
    const SyncEstimate folded = applyOpticalRefinement(anchorAt(anchorMs), r);
    CHECK(folded.confidence == SyncConfidence::Anchored);
    CHECK(folded.model.offsetNs == anchorMs * 1000000);
    CHECK(folded.refinedOffsetNs == 0);
    CHECK(folded.source.find("refine-rejected") != std::string::npos);
}

// ---------------------------------------------------------------------------
// 4. Moving-object blob: the MAD gate must reject it; the offset still
//    recovers.
// ---------------------------------------------------------------------------

TEST_CASE("moving object: MAD gate rejects it, offset still recovers") {
    const int64_t truthMs = 367;
    SyntheticClip sc = makeSyntheticClip(30, truthMs, 0.30, 46);
    // A bright 48x48 mover sweeping across the CENTRAL region (an
    // independent mover — its image motion is NOT the camera rotation).
    // Sized to actually hijack the SAD argmin of the pairs it occupies
    // (a bright, internally-clean patch aligns better than the noisy
    // background, so the measured displacement becomes the MOVER's
    // motion — the realistic moving-object failure). A 3-frame burst
    // (frames 12..14) corrupts exactly the 2 pairs that carry the mover
    // in BOTH frames — (12,13) and (13,14); a pair with the mover in
    // only one frame gains a shift-CONSTANT error that cannot move the
    // argmin. 2 corrupted of window 1's 10 pairs is a MINORITY, right at
    // the MAD gate's top-quartile cap (10/4 = 2): the gate must reject
    // exactly this shape — a few badly-wrong pairs among mostly honest
    // ones — and the offset must still recover. A mover present in
    // EVERY frame would corrupt the majority and the refinement
    // honestly fails; that case is the audit's "moving-object-dominated
    // interval" rejection, not this one.
    for (size_t i = 12; i <= 14; ++i) {
        ImageF& img = sc.frames[i].image;
        const int bx = 60 + 5 * static_cast<int>(i);
        const int by = 66;
        for (int dy = 0; dy < 48; ++dy)
            for (int dx = 0; dx < 48; ++dx) {
                const int x = bx + dx, y = by + dy;
                if (x < 0 || y < 0 || x >= static_cast<int>(img.w) ||
                    y >= static_cast<int>(img.h))
                    continue;
                const size_t p = 3 * (static_cast<size_t>(y) * img.w + x);
                img.rgb[p + 0] = 1.0f;
                img.rgb[p + 1] = 1.0f;
                img.rgb[p + 2] = 1.0f;
            }
    }
    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = 240.0;
    const OpticalSyncResult r =
        refineOffsetOptically(anchorAt(truthMs), sc.frames, sc.series, intr);
    MESSAGE("provenance: " << r.provenance);
    REQUIRE(r.refined);
    const double errMs =
        std::fabs(static_cast<double>(r.refinedOffsetNs - sc.truthOffsetNs) / 1e6);
    MESSAGE("moving-object: |refined - truth| = " << errMs << " ms"
            << " (bound " << r.errorBoundMs << " ms)");
    CHECK(errMs <= 2.0);
}

// ---------------------------------------------------------------------------
// 5. Boundary: empty frames / series / zero-dims intrinsics -> not
//    refined, no crash.
// ---------------------------------------------------------------------------

TEST_CASE("boundary: invalid inputs are refused, never crash") {
    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = 240.0;
    SyntheticClip sc = makeSyntheticClip(10, 100, 0.30, 47);

    // Empty frames.
    {
        const OpticalSyncResult r =
            refineOffsetOptically(anchorAt(100), {}, sc.series, intr);
        CHECK_FALSE(r.refined);
        CHECK(r.provenance.find("no-frames") != std::string::npos);
    }
    // Empty series.
    {
        OrientationSeries empty;
        const OpticalSyncResult r =
            refineOffsetOptically(anchorAt(100), sc.frames, empty, intr);
        CHECK_FALSE(r.refined);
        CHECK(r.provenance.find("no-series") != std::string::npos);
    }
    // Zero focal.
    {
        OpticalSyncIntrinsics z;
        z.focalLengthPx = 0.0;
        const OpticalSyncResult r =
            refineOffsetOptically(anchorAt(100), sc.frames, sc.series, z);
        CHECK_FALSE(r.refined);
        CHECK(r.provenance.find("no-focal") != std::string::npos);
    }
    // Tiny images.
    {
        std::vector<OpticalSyncFrame> tiny = sc.frames;
        for (OpticalSyncFrame& f : tiny) {
            f.image.w = 16;
            f.image.h = 9;
            f.image.rgb.assign(16 * 9 * 3, 0.5f);
        }
        const OpticalSyncResult r =
            refineOffsetOptically(anchorAt(100), tiny, sc.series, intr);
        CHECK_FALSE(r.refined);
        CHECK(r.provenance.find("image-too-small") != std::string::npos);
    }
    // Unsorted frames.
    {
        std::vector<OpticalSyncFrame> unsorted = sc.frames;
        std::swap(unsorted[2], unsorted[3]);
        const OpticalSyncResult r =
            refineOffsetOptically(anchorAt(100), unsorted, sc.series, intr);
        CHECK_FALSE(r.refined);
        CHECK(r.provenance.find("frames-unsorted") != std::string::npos);
    }
    // Mismatched sizes.
    {
        std::vector<OpticalSyncFrame> mismatch = sc.frames;
        mismatch[3].image.w = 160;
        mismatch[3].image.h = 90;
        mismatch[3].image.rgb.assign(160 * 90 * 3, 0.5f);
        const OpticalSyncResult r =
            refineOffsetOptically(anchorAt(100), mismatch, sc.series, intr);
        CHECK_FALSE(r.refined);
        CHECK(r.provenance.find("image-size-mismatch") != std::string::npos);
    }
    // The fold on a refused result keeps the anchor byte-for-byte on
    // every field the refinement owns.
    {
        const SyncEstimate a = anchorAt(100);
        OpticalSyncResult refused;
        refused.refined = false;
        refused.provenance = "refine-rejected reason=no-frames";
        const SyncEstimate f = applyOpticalRefinement(a, refused);
        CHECK(f.confidence == a.confidence);
        CHECK(f.model.offsetNs == a.model.offsetNs);
        CHECK(f.errorBoundMs == a.errorBoundMs);
        CHECK(f.refinedOffsetNs == 0);
    }
}

// ---------------------------------------------------------------------------
// 6. Real-clip evidence (RAWEDIT_REALCLIP-gated, INFORMATIONAL): the
//    refinement RUNS on the real pipeline and the verdict is honest.
//    No specific offset is asserted — the real clips carry no optical
//    ground truth. Both fixtures run when the gate is on.
// ---------------------------------------------------------------------------

TEST_CASE("realclip evidence: refinement runs on both fixtures, verdict honest") {
    if (!realclipEnabled()) {
        MESSAGE("SKIP: RAWEDIT_REALCLIP not set (2 GB fixtures)");
        return;
    }
    const char* fixtures[][2] = {
        {"STEPS/media/test/261110_004150_VIDEO_24mm.mcraw",
         "STEPS/media/test/261110_004150_VIDEO_24mm.gcsv"},
        {"STEPS/media/test/261110_004208_VIDEO_24mm.mcraw",
         "STEPS/media/test/261110_004208_VIDEO_24mm.gcsv"},
    };
    for (auto& fx : fixtures) {
        const std::string mcraw = fx[0];
        const std::string gcsv = fx[1];
        if (!fixtureAvailable(mcraw.c_str()) || !fixtureAvailable(gcsv.c_str())) {
            MESSAGE("SKIP: fixture missing: " << mcraw);
            continue;
        }

        // The real pipeline (mirrors test_warp's fixture case):
        // clocks -> anchored sync -> VQF -> sampled frames -> refine.
        const ContainerClocks cc = containerClocks(mcraw);
        REQUIRE(!cc.captureNs.empty());
        REQUIRE(cc.frame0RecvdMs > 0);
        const long long hdrMs = gcsvHeaderTimestampMs(gcsv);
        REQUIRE(hdrMs > 0);
        const SyncEstimate anchor = estimateOffsetFromAnchors(
            cc.frame0RecvdMs, hdrMs, cc.captureNs.front());
        REQUIRE(anchor.isAnchored());
        MESSAGE(mcraw << ": anchored offset = "
                << anchor.model.offsetMs() << " ms (bound "
                << anchor.errorBoundMs << " ms)");

        auto gres = parseGcsv(gcsv);
        REQUIRE(gres.isOk());
        const ImuData data = *gres.value;
        const AxisRemap remap = axisRemapForImu(data);
        REQUIRE(remap.known);
        std::vector<ImuSample> gyro, accel;
        gyro.reserve(data.gyro.size());
        for (const ImuSample& s : data.gyro) {
            float x = s.x, y = s.y, z = s.z;
            remap.apply(x, y, z);
            gyro.push_back(ImuSample{s.timestampNs, x, y, z});
        }
        accel.reserve(data.accel.size());
        for (const ImuSample& s : data.accel) {
            float x = s.x, y = s.y, z = s.z;
            remap.apply(x, y, z);
            accel.push_back(ImuSample{s.timestampNs, x, y, z});
        }
        const VqfOrientation v = estimateOrientationVqf(gyro, accel);
        REQUIRE(!v.series.empty());

        auto src = Source::open(mcraw);
        REQUIRE(src.isOk());
        const double focalMm = (*src)->rawMeta().focalLengthMm;
        REQUIRE(focalMm > 0.0);

        // Sample a bounded, time-ordered subset: every 4th frame (~78 of
        // 310; ~26 consecutive pairs per third at the cap of 24) — the
        // caller-budget rule (OpticalSync.h). Consecutive PAIRS are
        // what the residual consumes, so the sample stride keeps pairs
        // ~133 ms apart — still well inside the search range.
        const size_t stride = 4;
        std::vector<OpticalSyncFrame> frames;
        const size_t n = cc.captureNs.size();
        // Median frame interval (for the repTime fallback).
        std::vector<int64_t> deltas;
        deltas.reserve(n - 1);
        for (size_t i = 1; i < n; ++i)
            deltas.push_back(cc.captureNs[i] - cc.captureNs[i - 1]);
        std::sort(deltas.begin(), deltas.end());
        const int64_t frameIntervalNs = deltas[deltas.size() / 2];

        const int strideEvery = 8; // 4K -> 480x270 thumbnail
        for (size_t i = 0; i < n; i += stride) {
            const FrameInfo fi = (*src)->frameInfo(i);
            auto frame = (*src)->readFrame(i);
            REQUIRE(frame.isOk());
            const ImageF developed =
                developThumbnail(frame->data(), fi, (*src)->rawMeta(), Grade{},
                                 strideEvery);
            REQUIRE(developed.w > 0);
            OpticalSyncFrame f;
            f.captureNs = cc.captureNs[i];
            f.repTimeNs = f.captureNs + frameIntervalNs / 2;
            f.image = developed;
            frames.push_back(f);
        }
        MESSAGE(mcraw << ": sampled " << frames.size() << " frames at "
                << frames[0].image.w << "x" << frames[0].image.h);

        OpticalSyncIntrinsics intr;
        intr.focalLengthPx = focalLengthPxFromEquivalent(
            focalMm, static_cast<int>(frames[0].image.w));
        REQUIRE(intr.focalLengthPx > 0.0);

        const auto t0 = std::chrono::steady_clock::now();
        const OpticalSyncResult r =
            refineOffsetOptically(anchor, frames, v.series, intr);
        const auto t1 = std::chrono::steady_clock::now();
        const double refineMs =
            std::chrono::duration<double, std::milli>(t1 - t0).count();
        MESSAGE(mcraw << ": refine cost = " << refineMs << " ms");

        // INFORMATIONAL verdict (no ground truth to assert against):
        // Refined -> a bounded offset reported and the fold upgrades the
        // estimate; not-Refined -> the anchor kept. Both are honest.
        const SyncEstimate folded = applyOpticalRefinement(anchor, r);
        if (r.refined) {
            MESSAGE(mcraw << ": REFINED offset = "
                    << static_cast<double>(r.refinedOffsetNs) / 1e6
                    << " ms (bound " << r.errorBoundMs << " ms)");
            MESSAGE(mcraw << ": provenance = " << r.provenance);
            CHECK(folded.confidence == SyncConfidence::Refined);
            CHECK(folded.refinedOffsetNs == r.refinedOffsetNs);
            CHECK(r.errorBoundMs > 0.0);
            CHECK(r.errorBoundMs <= anchor.errorBoundMs);
        } else {
            MESSAGE(mcraw << ": NOT refined (anchor kept at "
                    << anchor.model.offsetMs() << " ms)");
            MESSAGE(mcraw << ": provenance = " << r.provenance);
            CHECK(folded.confidence == SyncConfidence::Anchored);
            CHECK(folded.model.offsetNs == anchor.model.offsetNs);
        }
        // The provenance is always populated (visibility requirement).
        CHECK_FALSE(r.provenance.empty());
    }
}

// ---------------------------------------------------------------------------
// The additive SyncEstimate contract (fields the refinement owns).
// ---------------------------------------------------------------------------

TEST_CASE("sync estimate: refined fields are additive and default-preserving") {
    // Default SyncEstimate: refinedOffsetNs == 0, confidence
    // ManualRequired (unchanged defaults; no existing consumer sees a
    // behavior change).
    SyncEstimate d;
    CHECK(d.refinedOffsetNs == 0);
    CHECK(d.confidence == SyncConfidence::ManualRequired);
    // Ordering: Anchored < Refined < ManualRequired (the enum's
    // documented order; Refined sits between).
    CHECK(SyncConfidence::Anchored < SyncConfidence::Refined);
    CHECK(SyncConfidence::Refined < SyncConfidence::ManualRequired);
    // The fold's additive write path.
    SyncEstimate a = anchorAt(367);
    OpticalSyncResult ok;
    ok.refined = true;
    ok.refinedOffsetNs = 367123456;
    ok.errorBoundMs = 4.0;
    ok.provenance = "refined:optical windows=3 accepted=60 offsetMs=367.1";
    const SyncEstimate f = applyOpticalRefinement(a, ok);
    CHECK(f.confidence == SyncConfidence::Refined);
    CHECK(f.refinedOffsetNs == 367123456);
    CHECK(f.errorBoundMs == 4.0);
    CHECK(f.source == ok.provenance);
    // The ANCHOR's model is untouched (the refinement never re-anchors;
    // the consumer picks the mapping by confidence).
    CHECK(f.model.offsetNs == a.model.offsetNs);
}
