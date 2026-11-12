// warpFrame tests (stabilization Phase 5: the CPU warp).
//
// Synthetic doctests pin the exact composite map p_src = H^-1(c +
// (p_out - c)/Z) with hand-computable plans: identity must be
// byte-identical (the off look is free), a pure translation must shift
// pixels exactly, a zoom crop must sample the documented window, a roll
// from frameTransform must land on the pinned 2D block, and the edge
// clamp / degenerate-H / zoom-clamp fallbacks must stay honest.
//
// The real-fixture case (deliverable: quote the status + measured
// per-frame warp costs) reads the gitignored STEPS/media/test pair
// when present and skips otherwise, like test_stabilization. It runs
// the REAL pipeline Phase 5's renderer drives — container clocks ->
// anchored sync -> VQF orientation -> container focal -> the clip API
// -> computeStabilization at the warped buffer's dims -> warpFrame —
// at EVERY tier the preview drives (developThumbnail strideEvery=8 ->
// the 480x270 drag tier, 6 -> the 640x360 playback tier, 3 -> the
// 1280x720 paused tier, 1 -> the 3840x2160 export/source tier) and
// quotes the measured per-frame cost of each warp.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Warp.h>
#include <rawedit/Stabilization.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Orientation.h>
#include <rawedit/Sync.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include <motioncam/Decoder.hpp>

using namespace rawedit;

namespace {

constexpr double kPi = 3.14159265358979323846;

double deg2rad(double d) { return d * kPi / 180.0; }

// A small synthetic ImageF: pixel (x, y) carries r = x, g = y, b = 0
// so any sample's bilinear landing point is readable off its values
// (bilinear interpolation reproduces the linear ramps exactly).
ImageF rampImage(uint32_t w, uint32_t h) {
    ImageF img;
    img.w = w;
    img.h = h;
    img.rgb.resize(static_cast<size_t>(w) * h * 3u);
    for (uint32_t y = 0; y < h; ++y)
        for (uint32_t x = 0; x < w; ++x) {
            float* p = &img.rgb[(static_cast<size_t>(y) * w + x) * 3u];
            p[0] = static_cast<float>(x);
            p[1] = static_cast<float>(y);
            p[2] = 0.0f;
        }
    return img;
}

float at(const ImageF& img, uint32_t x, uint32_t y, int c) {
    return img.rgb[(static_cast<size_t>(y) * img.w + x) * 3u + c];
}

// True when two ImageF are byte-identical (dims + every float bit).
bool identical(const ImageF& a, const ImageF& b) {
    return a.w == b.w && a.h == b.h && a.rgb == b.rgb;
}

const double kIdentity[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};

bool stFixtureAvailable(const char* relpath) {
    std::FILE* f = std::fopen(relpath, "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

} // namespace

// ---------------------------------------------------------------------------
// Synthetic doctests — the exact composite map
// ---------------------------------------------------------------------------

TEST_CASE("identity_h_and_zoom_one_is_byte_identical") {
    const ImageF src = rampImage(9, 7);
    const ImageF out = warpFrame(src, kIdentity, 1.0, 4.0, 3.0);
    // The off look is free: no resample, every float bit unchanged.
    CHECK(identical(src, out));
}

TEST_CASE("empty_src_returns_empty") {
    ImageF empty;
    const ImageF out = warpFrame(empty, kIdentity, 1.0, 0.0, 0.0);
    CHECK(out.w == 0);
    CHECK(out.h == 0);
    CHECK(out.rgb.empty());
}

TEST_CASE("pure_translation_shifts_pixels_exactly") {
    // H = [[1,0,tx],[0,1,ty],[0,0,1]] maps source (x,y) to output
    // (x+tx, y+ty), so output(x,y) = src(x-tx, y-ty). With integer
    // tx/ty every landing point is exact (no interpolation).
    const ImageF src = rampImage(8, 8);
    const double h[9] = {1, 0, 2, 0, 1, 1, 0, 0, 1}; // tx=2, ty=1
    const ImageF out = warpFrame(src, h, 1.0, 3.5, 3.5);
    REQUIRE(out.w == 8);
    REQUIRE(out.h == 8);
    // Interior: out(x,y) = src(x-2, y-1), both channels exact.
    for (uint32_t y = 1; y < 8; ++y)
        for (uint32_t x = 2; x < 8; ++x) {
            CHECK(at(out, x, y, 0) == at(src, x - 2, y - 1, 0));
            CHECK(at(out, x, y, 1) == at(src, x - 2, y - 1, 1));
        }
    // Left edge (x < 2): the sample lands before the source's left
    // edge and clamps to column 0 — r stays 0 there while the interior
    // carries x-2.
    for (uint32_t y = 1; y < 8; ++y) {
        CHECK(at(out, 0, y, 0) == 0.0f);
        CHECK(at(out, 1, y, 0) == 0.0f);
        CHECK(at(out, 2, y, 0) == 0.0f); // src(0, y-1): also column 0
    }
    // Top edge (y = 0): the sample clamps to row 0 — g stays 0 while
    // the interior carries y-1.
    for (uint32_t x = 2; x < 8; ++x) {
        CHECK(at(out, x, 0, 1) == 0.0f);
        CHECK(at(out, x, 1, 1) == 0.0f); // src(x-2, 0): also row 0
    }
}

TEST_CASE("zoom_crop_samples_the_documented_window") {
    // Identity H + zoom 2 on a 10x8 ramp with c = (4.5, 3.5): output
    // pixel (x,y) samples c + (p - c)/2, the window centered at c with
    // half-extents (w/2Z, h/2Z) = (2.5, 2). Corners are hand-computable:
    // out(0,0) samples (2.25, 1.75); out(9,7) samples (6.75, 5.25);
    // out(4,3) samples (4.25, 3.25). The ramps are linear, so bilinear
    // reproduces the landing point exactly in the channel values.
    const ImageF src = rampImage(10, 8);
    const ImageF out = warpFrame(src, kIdentity, 2.0, 4.5, 3.5);
    REQUIRE(out.w == 10);
    REQUIRE(out.h == 8);
    CHECK(at(out, 0, 0, 0) == doctest::Approx(2.25f).epsilon(1e-5));
    CHECK(at(out, 0, 0, 1) == doctest::Approx(1.75f).epsilon(1e-5));
    CHECK(at(out, 4, 3, 0) == doctest::Approx(4.25f).epsilon(1e-5));
    CHECK(at(out, 4, 3, 1) == doctest::Approx(3.25f).epsilon(1e-5));
    CHECK(at(out, 9, 7, 0) == doctest::Approx(6.75f).epsilon(1e-5));
    CHECK(at(out, 9, 7, 1) == doctest::Approx(5.25f).epsilon(1e-5));
    // Every sample stays inside the source at zoom 2 >= required 1:
    // the whole window is in range, no edge-clamp values appear.
    for (uint32_t y = 0; y < 8; ++y)
        for (uint32_t x = 0; x < 10; ++x) {
            CHECK(at(out, x, y, 0) == doctest::Approx(2.25 + 0.5 * x).epsilon(1e-4));
            CHECK(at(out, x, y, 1) == doctest::Approx(1.75 + 0.5 * y).epsilon(1e-4));
        }
}

TEST_CASE("roll_from_frameTransform_lands_on_the_pinned_block") {
    // frameTransform builds H = K·D·R·D·K^-1 (SOURCE -> OUTPUT). For a
    // pure roll about the camera view axis D drops out and H is the
    // pinned 2D block [[cos,sin],[-sin,cos]] about the principal point
    // (Stabilization.h's roll contract). warpFrame samples out(p) =
    // src(H^-1(p)) at zoom 1, and H^-1's block is the transpose
    // [[cos,-sin],[sin,cos]]: output pixel p samples
    // c + (cos·dx - sin·dy, sin·dx + cos·dy) with (dx, dy) = p - c.
    const double focal = 100.0;
    const double cx = 50.0, cy = 50.0;
    const double th = deg2rad(5.0);
    double q[4];
    q[0] = std::cos(0.5 * th);
    q[1] = 0.0;
    q[2] = 0.0;
    q[3] = std::sin(0.5 * th);
    double H[9];
    frameTransform(q, focal, cx, cy, H);

    const ImageF src = rampImage(101, 101);
    const ImageF out = warpFrame(src, H, 1.0, cx, cy);
    REQUIRE(out.w == 101);
    REQUIRE(out.h == 101);
    const double cth = std::cos(th), sth = std::sin(th);
    const uint32_t xs[3] = {50, 70, 30};
    const uint32_t ys[3] = {50, 48, 61};
    for (int i = 0; i < 3; ++i) {
        const double dx = static_cast<double>(xs[i]) - cx;
        const double dy = static_cast<double>(ys[i]) - cy;
        const double sxp = cx + cth * dx - sth * dy;
        const double syp = cy + sth * dx + cth * dy;
        CHECK(at(out, xs[i], ys[i], 0) == doctest::Approx(sxp).epsilon(1e-4));
        CHECK(at(out, xs[i], ys[i], 1) == doctest::Approx(syp).epsilon(1e-4));
    }
    // The pinned SIGN, readable off the values: source content right
    // of c at the principal row — src(70, 50), r = 70 — reappears in
    // the output ABOVE c (v-down: smaller y), at output
    // (50 + 20cos, 50 - 20sin) = (69.92, 48.26). The nearest output
    // pixel (70, 48) therefore samples src x = 70.098 (r ≈ 70.1, the
    // right-of-c content) — content that sat at output y = 50 now
    // reads at output y = 48.
    CHECK(at(out, 70, 48, 0) == doctest::Approx(70.098).epsilon(1e-3));
}

TEST_CASE("edge_clamp_keeps_the_frame_finite") {
    // A translation far off the source pushes every sample outside:
    // every output pixel is the clamped corner pixel (finite, inside
    // the src's value range) — the documented behavior when the zoom
    // limit sits below the plan's required zoom.
    const ImageF src = rampImage(6, 6);
    const double h[9] = {1, 0, 50, 0, 1, 50, 0, 0, 1};
    const ImageF out = warpFrame(src, h, 1.0, 2.5, 2.5);
    REQUIRE(out.w == 6);
    REQUIRE(out.h == 6);
    for (size_t i = 0; i < out.rgb.size(); ++i) {
        CHECK(std::isfinite(out.rgb[i]));
        CHECK(out.rgb[i] >= 0.0f);
        CHECK(out.rgb[i] <= 5.0f);
    }
}

TEST_CASE("degenerate_h_returns_src_unchanged") {
    // Singular to the matInv threshold (det = 0): an honest no-op,
    // never garbage — the same skip staticCropZoom makes.
    const ImageF src = rampImage(7, 5);
    const double h[9] = {1, 2, 0, 2, 4, 0, 0, 0, 1}; // rank 2
    const ImageF out = warpFrame(src, h, 1.0, 3.0, 2.0);
    CHECK(identical(src, out));
}

TEST_CASE("zoom_below_one_clamps_to_one") {
    // A warp may not magnify: zoom 0.5 must behave exactly like 1.0.
    const ImageF src = rampImage(8, 8);
    const double txh[9] = {1, 0, 1, 0, 1, 0, 0, 0, 1};
    const ImageF a = warpFrame(src, txh, 0.5, 4.0, 4.0);
    const ImageF b = warpFrame(src, txh, 1.0, 4.0, 4.0);
    CHECK(identical(a, b));
}

// ---------------------------------------------------------------------------
// Real-fixture evidence — the real pipeline at both tiers
// (mirrors test_stabilization's fixture helpers; the fixtures are
// gitignored, so machines without the STEPS/ tree skip this case)
// ---------------------------------------------------------------------------

namespace {

// The GCSV header "timestamp" (epoch ms) — the gyro log's wall start.
long long stGcsvHeaderTimestampMs(const std::string& path) {
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

// Exact per-frame capture ns + frame-0 recvdTimestampMs from the
// container via the vendored decoder (the same read-only pattern as
// Gyro.cpp / test_stabilization.cpp).
struct StContainerClocks {
    std::vector<int64_t> captureNs;
    long long frame0RecvdMs = -1;
};

StContainerClocks stContainerClocks(const std::string& path) {
    StContainerClocks cc;
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

TEST_CASE("fixture evidence: 261110_004150 warp through the real pipeline") {
    const char* gcsv = "STEPS/media/test/261110_004150_VIDEO_24mm.gcsv";
    const char* mcraw = "STEPS/media/test/261110_004150_VIDEO_24mm.mcraw";
    if (!stFixtureAvailable(gcsv) || !stFixtureAvailable(mcraw)) {
        MESSAGE("SKIP: fixtures not present (gitignored)");
        return;
    }

    // ---- the real pipeline: clocks -> anchored sync -> VQF -> plan ----
    const StContainerClocks cc = stContainerClocks(mcraw);
    REQUIRE(!cc.captureNs.empty());
    REQUIRE(cc.frame0RecvdMs > 0);
    const long long hdrMs = stGcsvHeaderTimestampMs(gcsv);
    REQUIRE(hdrMs > 0);
    const SyncEstimate est = estimateOffsetFromAnchors(
        cc.frame0RecvdMs, hdrMs, cc.captureNs.front());
    REQUIRE(est.isAnchored());
    const SyncModel sync = est.model;

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
    const double gravity[3] = {v.gravityCamera[0], v.gravityCamera[1],
                               v.gravityCamera[2]};

    auto src = Source::open(mcraw);
    REQUIRE(src.isOk());
    const double focalMm = (*src)->rawMeta().focalLengthMm;
    REQUIRE(focalMm > 0.0);
    const FrameInfo fi = (*src)->frameInfo(0);
    REQUIRE(fi.width > 0);
    REQUIRE(fi.height > 0);
    auto frame = (*src)->readFrame(0);
    REQUIRE(frame.isOk());

    // ---- the plan at each tier's dims, then the warp, timed ----
    auto runTier = [&](int strideEvery, const char* name) {
        const ImageF developed = developThumbnail(
            frame->data(), fi, (*src)->rawMeta(), Grade{}, strideEvery);
        REQUIRE(developed.w > 0);
        REQUIRE(developed.h > 0);
        StabilizationParams par;
        par.mode = StabilizationMode::Smooth;
        par.strength = 0.7; // the documented default: tau ≈ 2 ms
        par.zoomLimit = 1.1;
        // The plan is computed at the warped buffer's dims (the binding
        // placement decision): focal px from the 35mm-equivalent
        // fallback at THIS buffer's width.
        par.focalLengthPx = focalLengthPxFromEquivalent(
            focalMm, static_cast<int>(developed.w));
        REQUIRE(par.focalLengthPx > 0.0);
        par.width = static_cast<int>(developed.w);
        par.height = static_cast<int>(developed.h);
        // The plan (computeStabilization) is recomputed PER RENDER on the
        // bridge (jni_bridge.cpp stabilizationFrameFor), so its cost rides
        // every tier's budget too — time it alongside the warp for the
        // honest per-render total.
        const auto p0 = std::chrono::steady_clock::now();
        const StabilizationResult r = computeStabilization(
            v.series, sync, cc.captureNs, gravity, true, true, par);
        const auto p1 = std::chrono::steady_clock::now();
        const double planMs =
            std::chrono::duration<double, std::milli>(p1 - p0).count();
        MESSAGE(std::string(name) << ": computeStabilization plan = "
                << planMs << " ms");
        REQUIRE(!r.frames.empty());
        const StabilizationFrame& f0 = r.frames[0];
        // std::string wraps: doctest stringifies a bare const char* as a
        // pointer (DOCTEST_CONFIG_TREAT_CHAR_STAR_AS_STRING is off), so
        // the quoted evidence line carries the readable name.
        MESSAGE(std::string(name) << ": status="
                << std::string(stabilizationStatusName(f0.status))
                << " zoom=" << f0.zoom << " buffer=" << developed.w << "x"
                << developed.h << " focalPx=" << par.focalLengthPx);
        // The fixture's focal px IS the flagged 35mm-equivalent
        // fallback — the honest per-frame status for it.
        CHECK(f0.status == StabilizationStatus::IntrinsicsAssumed);
        // Timing: warm-up (caches, branch predictor), then one timed
        // warp of the same frame — a single honest per-frame cost.
        const double pcx = static_cast<double>(developed.w) / 2.0;
        const double pcy = static_cast<double>(developed.h) / 2.0;
        warpFrame(developed, f0.h, f0.zoom, pcx, pcy);
        const auto t0 = std::chrono::steady_clock::now();
        const ImageF warped = warpFrame(developed, f0.h, f0.zoom, pcx, pcy);
        const auto t1 = std::chrono::steady_clock::now();
        const double ms =
            std::chrono::duration<double, std::milli>(t1 - t0).count();
        MESSAGE(std::string(name) << ": warpFrame " << developed.w << "x"
                << developed.h << " = " << ms << " ms/frame");
        REQUIRE(warped.w == developed.w);
        REQUIRE(warped.h == developed.h);
        CHECK(ms >= 0.0);
        CHECK(ms < 500.0); // sanity bound, far above the expected cost
    };

    runTier(8, "004150 drag-480");
    runTier(6, "004150 playback-720");
    runTier(3, "004150 preview-1280");
    runTier(1, "004150 export-fullres");
}
