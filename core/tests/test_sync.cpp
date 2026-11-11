// Frame↔IMU synchronization tests (stabilization Phase 3).
//
// Synthetic clock tests: offset round-trip, sign convention, ns/ms/Us
// conversion, anchor arithmetic, VFR mapping, clamp flagging.
//
// Real-fixture tests read the STEPS/media/test pairs when present and
// skip otherwise (fixtures are gitignored). They compute the anchored
// offset and the overlap window from the FILES:
//   - the .mcraw via a raw motioncam::Decoder (exact container ns +
//     frame-0 recvdTimestampMs — Source.h does not surface either the
//     sub-us remainder of the ns timestamps or recvdTimestampMs; the
//     test opens its own read-only decoder the same way Gyro.cpp does)
//   - the .gcsv via parseGcsv (gyro span) plus its header epoch-ms
//
// The real-fixture tests therefore QUOTE the anchor values and the
// overlap numbers, and state the per-fixture verdict (both fixtures
// land ANCHORED; the MANUAL-REQUIRED path is covered synthetically in
// "anchored estimate refuses missing anchors").

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Sync.h>
#include <rawedit/Gyro.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <string>
#include <vector>

#include <motioncam/Decoder.hpp>

using namespace rawedit;

namespace {

bool fixtureAvailable(const char* relpath) {
    std::FILE* f = std::fopen(relpath, "rb");
    if (!f)
        return false;
    std::fclose(f);
    return true;
}

// Reads the GCSV header "timestamp" (epoch ms) without leaning on ImuData
// (which does not carry it — Phase 3 keeps the key out of the canonical
// model; this helper exists so tests can quote the real value).
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

// Exact per-frame capture timestamps (ns) + first-frame recvdTimestampMs
// (epoch ms) straight from the container via the vendored decoder (the
// same read-only pattern as Gyro.cpp). Source's FrameInfo.ptsUs is
// captureNs/1000 with INTEGER division, so ptsUs*1000 loses up to 999 ns
// — sync needs the exact ns values (the sub-us remainder is far below
// the sync error bound, but exactness is free here).
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
// SyncModel: arithmetic, sign, conversions
// ---------------------------------------------------------------------------

TEST_CASE("model applies offset with the documented sign") {
    // offsetNs is ADDED to the frame clock to reach the IMU clock.
    SyncModel m;
    m.offsetNs = 123456789;
    CHECK(m.toImu(1000) == 1000 + 123456789);
    CHECK(m.toFrame(1000 + 123456789) == 1000);
    // Zero offset is the identity (and is NOT a claim about real files).
    SyncModel zero;
    CHECK(zero.toImu(258158030289578LL) == 258158030289578LL);
}

TEST_CASE("model ms/ns conversions round-trip") {
    // fromOffsetMs -> offsetMs round-trips at ms resolution.
    for (double ms : {0.0, 1.0, -1.0, 0.5, -0.5, 367.0, -1250.25, 12345.678}) {
        const SyncModel m = SyncModel::fromOffsetMs(ms);
        CHECK(m.offsetMs() == doctest::Approx(ms).epsilon(1e-9));
    }
    // Sub-ns rounding: 4e-7 ms (0.4 ns) rounds to 0 ns.
    CHECK(SyncModel::fromOffsetMs(4e-7).offsetNs == 0);
    // 0.5 ms rounds away from zero (std::round semantics, documented).
    CHECK(SyncModel::fromOffsetMs(0.5).offsetNs == 500000);
    CHECK(SyncModel::fromOffsetMs(-0.5).offsetNs == -500000);
    // toImu/toFrame are exact inverses for any offset.
    const SyncModel m = SyncModel::fromOffsetMs(-371.123);
    const int64_t t = 258158030289578LL;
    CHECK(m.toFrame(m.toImu(t)) == t);
}

TEST_CASE("toImu/toFrame shift a whole timeline uniformly") {
    // A constant offset shifts every frame identically (the bridge is one
    // constant; no rate term — Sync.h).
    const SyncModel m = SyncModel::fromOffsetMs(351.0);
    const std::vector<int64_t> frames = {1000000000LL, 1033558120LL, 1069116240LL};
    for (int64_t f : frames) {
        CHECK(m.toImu(f) - f == m.offsetNs);
        CHECK(m.toImu(f) - m.toImu(frames[0]) == f - frames[0]);
    }
}

// ---------------------------------------------------------------------------
// Anchored estimate: formula, guards, confidence
// ---------------------------------------------------------------------------

TEST_CASE("anchored estimate matches the hand-computed fixture numbers") {
    // Fixture 261110_004150: recvd0 = 1794260510870 ms, gcsv header =
    // 1794260510503 ms, frame0 capture = 258158030289578 ns.
    const SyncEstimate est = estimateOffsetFromAnchors(
        1794260510870LL, 1794260510503LL, 258158030289578LL);
    REQUIRE(est.isAnchored());
    CHECK(est.source == "anchored:recvdMs-vs-gcsvHeader");
    // offsetNs = (recvd0 - hdr)*1e6 - frame0Ns
    //          = 367e6 - 258158030289578 = -258157663289578
    CHECK(est.model.offsetNs == 367LL * 1000000LL - 258158030289578LL);
    CHECK(est.model.offsetMs() == doctest::Approx(-258157663.289578));
    CHECK(est.errorBoundMs == doctest::Approx(25.0));

    // The estimate's core claim: the FIRST GYRO SAMPLE (imu ts 0) maps
    // onto the frame clock at frame0 + (hdr-recvd0) — the log started
    // 367 ms of wall time before frame 0 was received.
    CHECK(est.model.toFrame(0) == 258158030289578LL - 367LL * 1000000LL);
}

TEST_CASE("anchored estimate refuses missing anchors") {
    // Absent anchors never fabricate an offset (owner rule).
    const int64_t capNs = 258158030289578LL;
    for (auto [recvd, hdr] : {std::pair<int64_t, int64_t>{0, 1794260510503LL},
                              {1794260510870LL, 0},
                              {-1, -1}}) {
        const SyncEstimate est = estimateOffsetFromAnchors(recvd, hdr, capNs);
        CHECK_FALSE(est.isAnchored());
        CHECK(est.confidence == SyncConfidence::ManualRequired);
        CHECK(est.model.offsetNs == 0); // untouched: no fabricated number
        CHECK(est.errorBoundMs == 0.0);
    }
}

TEST_CASE("anchored estimate round-trips a synthetic clock pair") {
    // Build two clocks with a KNOWN offset and check the estimator
    // recovers it exactly (no quantization: feed it ms-exact anchors).
    // Frame clock: f0 = 1e14 ns. Gyro clock starts 250 ms of wall time
    // before frame 0's receive anchor. recvd lag = 0 by construction.
    const int64_t f0 = 100000000000000LL;          // frame 0 capture ns
    const int64_t gyroStartWall = 1794260510000LL;  // log start epoch ms
    const int64_t f0Wall = gyroStartWall + 250;     // frame 0 recvd epoch ms
    const SyncEstimate est = estimateOffsetFromAnchors(f0Wall, gyroStartWall, f0);
    REQUIRE(est.isAnchored());
    // imuTs = frameTs + offset; gyro t=0 sits at frameTs = f0 - 250 ms.
    CHECK(est.model.toImu(f0 - 250LL * 1000000LL) == 0);
    CHECK(est.model.toImu(f0) == 250LL * 1000000LL);
}

// ---------------------------------------------------------------------------
// Manual offset & trim
// ---------------------------------------------------------------------------

TEST_CASE("manual offset carries ManualRequired and no bound") {
    const SyncEstimate est = manualOffsetMs(-130.5);
    CHECK_FALSE(est.isAnchored());
    CHECK(est.confidence == SyncConfidence::ManualRequired);
    CHECK(est.source == "manual");
    CHECK(est.model.offsetNs == -130500000LL);
    CHECK(est.errorBoundMs == 0.0); // 0 = unknown, not exact (Sync.h)
}

TEST_CASE("trim adjusts an anchored estimate and widens its bound") {
    const SyncEstimate base = estimateOffsetFromAnchors(
        1794260510870LL, 1794260510503LL, 258158030289578LL);
    REQUIRE(base.isAnchored());
    const SyncEstimate trimmed = trimOffsetMs(base, -10.0);
    CHECK(trimmed.isAnchored()); // provenance retained, still anchored
    CHECK(trimmed.model.offsetNs == base.model.offsetNs - 10000000LL);
    CHECK(trimmed.errorBoundMs == doctest::Approx(35.0));
    CHECK(trimmed.source == "anchored:recvdMs-vs-gcsvHeader+trim");
    // Trim of a manual estimate stays manual with unknown bound.
    const SyncEstimate manualTrimmed = trimOffsetMs(manualOffsetMs(5.0), 5.0);
    CHECK(manualTrimmed.confidence == SyncConfidence::ManualRequired);
    CHECK(manualTrimmed.errorBoundMs == 0.0);
    CHECK(manualTrimmed.model.offsetNs == 10000000LL);
}

// ---------------------------------------------------------------------------
// framesToImu: VFR, drops, clamp window
// ---------------------------------------------------------------------------

TEST_CASE("framesToImu maps a uniform frame list inside the span") {
    // 30 fps over 1 s, gyro span covers all of it.
    std::vector<int64_t> frames;
    for (int i = 0; i < 31; i++)
        frames.push_back(1000000000LL + i * 33333333LL);
    const SyncModel m; // zero offset
    const FrameSync fs = framesToImu(frames, m, 900000000LL, 2000000000LL);
    REQUIRE(fs.frameCount == 31);
    CHECK(fs.beforeGyroStart == 0);
    CHECK(fs.afterGyroEnd == 0);
    CHECK(fs.firstInside == 0);
    CHECK(fs.lastInside == 30);
    CHECK(fs.insideFraction() == doctest::Approx(1.0));
    for (size_t i = 0; i < frames.size(); i++)
        CHECK(fs.imuTimestampNs[i] == frames[i]);
}

TEST_CASE("framesToImu honors VFR and dropped frames") {
    // VFR: uneven intervals. Dropped frame: a gap in the middle (frame at
    // 2.5*interval absent). Each frame maps independently — no
    // fixed-rate assumption, gaps just map.
    const std::vector<int64_t> frames = {
        1000000000LL,        // 0
        1040000000LL,        // 1 (+40 ms)
        1060000000LL,        // 2 (+20 ms)
        // 3 dropped: ~+60 ms would be 1120000000
        1140000000LL,        // 3' (+80 ms after the drop)
        1150000000LL,        // 4 (+10 ms)
    };
    const SyncModel m = SyncModel::fromOffsetMs(100.0);
    const FrameSync fs = framesToImu(frames, m, 1150000000LL, 1230000000LL);
    REQUIRE(fs.frameCount == 5);
    for (size_t i = 0; i < frames.size(); i++)
        CHECK(fs.imuTimestampNs[i] == frames[i] + 100000000LL);
    // frame 0: 1.1e9 < 1.15e9 -> before; frame 1: 1.14e9 <1.15e9 -> before;
    // frames 2..4 (1.16, 1.24→ no: 1.25e9) ... compute: +100 ms gives
    // 1.10, 1.14, 1.16, 1.24, 1.25 vs span [1.15, 1.23]:
    //   before: 1.10, 1.14  (2 frames)
    //   inside: 1.16, 1.24? no — 1.24 > 1.23 -> after
    // so inside = {1.16} only, after = {1.24, 1.25} (2 frames).
    CHECK(fs.beforeGyroStart == 2);
    CHECK(fs.afterGyroEnd == 2);
    CHECK(fs.firstInside == 2);
    CHECK(fs.lastInside == 2);
    CHECK(fs.insideFraction() == doctest::Approx(0.2));
}

TEST_CASE("framesToImu flags clamp frames before and after the span") {
    // Gyro span [2e9, 3e9]; frames at 1.0, 1.9, 2.0, 2.5, 3.0, 3.1, 4.0.
    const std::vector<int64_t> frames = {1000000000LL, 1900000000LL, 2000000000LL,
                                         2500000000LL, 3000000000LL, 3100000000LL,
                                         4000000000LL};
    const FrameSync fs = framesToImu(frames, SyncModel{}, 2000000000LL, 3000000000LL);
    CHECK(fs.beforeGyroStart == 2); // 1.0, 1.9 (strictly before)
    CHECK(fs.afterGyroEnd == 2);    // 3.1, 4.0 (strictly after)
    CHECK(fs.firstInside == 2);     // boundary counts as inside (clamp-free)
    CHECK(fs.lastInside == 4);
    CHECK(fs.insideFraction() == doctest::Approx(3.0 / 7.0));
    // The clamp contract: flagged frames are the ones orientationAt will
    // clamp to an end quaternion — before-frames to the first, after-
    // frames to the last (OrientationEstimate.h).
}

TEST_CASE("framesToImu degenerate spans keep every frame outside") {
    const std::vector<int64_t> frames = {1000, 2000, 3000};
    // Empty log: ImuData's no-sample extents are first==last==0 (Gyro.h).
    // The span [0,0] is "usable" but empty; raw comparisons put every
    // nonzero frame AFTER the span (before/after only split meaningfully
    // for a real span — orientationAt clamps everything to identity
    // either way).
    const FrameSync empty = framesToImu(frames, SyncModel{}, 0, 0);
    CHECK(empty.firstInside == FrameSync::npos);
    CHECK(empty.lastInside == FrameSync::npos);
    CHECK(empty.insideFraction() == doctest::Approx(0.0));
    CHECK(empty.beforeGyroStart == 0);
    CHECK(empty.afterGyroEnd == 3);
    CHECK(empty.frameCount == 3);
    // Timestamps still mapped (well-defined arithmetic).
    const SyncModel m = SyncModel::fromOffsetMs(1.0);
    const FrameSync mapped = framesToImu(frames, m, 0, 0);
    CHECK(mapped.imuTimestampNs[1] == 2000 + 1000000LL);
    // Inverted span (corrupt index): nothing inside either.
    const FrameSync inverted = framesToImu(frames, SyncModel{}, 3000, 1000);
    CHECK(inverted.firstInside == FrameSync::npos);
    CHECK(inverted.insideFraction() == doctest::Approx(0.0));
}

TEST_CASE("framesToImu handles empty frame list") {
    const FrameSync fs = framesToImu({}, SyncModel{}, 0, 1000);
    CHECK(fs.empty());
    CHECK(fs.frameCount == 0);
    CHECK(fs.insideFraction() == doctest::Approx(0.0));
    CHECK(fs.firstInside == FrameSync::npos);
}

TEST_CASE("framesToImu full video inside a longer gyro log") {
    // The fixture geometry: ~310 frames spanning 10.28 s, gyro log
    // 11.6 s started ~0.3 s before frame 0 — with the anchored offset ALL
    // frames land inside the gyro span.
    std::vector<int64_t> frames;
    for (int i = 0; i < 310; i++)
        frames.push_back(258158030289578LL + (int64_t)(i * 33268567.3));
    const SyncEstimate est = estimateOffsetFromAnchors(
        1794260510870LL, 1794260510503LL, 258158030289578LL);
    const int64_t gyroFirst = 0;
    const int64_t gyroLast = 11627272566LL; // fixture A span, quoted
    const FrameSync fs = framesToImu(frames, est.model, gyroFirst, gyroLast);
    CHECK(fs.beforeGyroStart == 0);
    CHECK(fs.afterGyroEnd == 0);
    CHECK(fs.firstInside == 0);
    CHECK(fs.lastInside == 309);
    CHECK(fs.insideFraction() == doctest::Approx(1.0));
}

// ---------------------------------------------------------------------------
// Real fixtures: anchor verdict + overlap window (quoted numbers)
// ---------------------------------------------------------------------------

TEST_CASE("real fixture 261110_004150: anchored sync and overlap"
          * doctest::description("needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv") ||
        !fixtureAvailable("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw")) {
        MESSAGE("SKIP: 261110_004150 fixtures not present");
        return;
    }
    auto src = Source::open("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw");
    REQUIRE(src.isOk());
    const ContainerClocks cc = containerClocks(
        "STEPS/media/test/261110_004150_VIDEO_24mm.mcraw");
    REQUIRE(cc.captureNs.size() == (*src)->frameCount());
    const size_t n = cc.captureNs.size();
    REQUIRE(n == 310);
    const std::vector<int64_t>& frames = cc.captureNs;

    auto imu = parseGcsv("STEPS/media/test/261110_004150_VIDEO_24mm.gcsv");
    REQUIRE(imu.isOk());
    const long long hdrMs = gcsvHeaderTimestampMs(
        "STEPS/media/test/261110_004150_VIDEO_24mm.gcsv");
    REQUIRE(hdrMs == 1794260510503LL); // quoted: log start epoch ms
    const long long recvdMs = cc.frame0RecvdMs;
    REQUIRE(recvdMs == 1794260510870LL); // quoted: frame-0 receive epoch ms

    // Documented container facts (quoted in Sync.h): 310 frames, exact ns
    // span 10279987292 (fixture A). Gyro: 5500 samples at tscale
    // 0.0021144340 -> span 11627272566 ns (round(5499*tscale*1e9)).
    CHECK(frames.front() == 258158030289578LL);
    CHECK(frames.back() - frames.front() == 10279987292LL);
    CHECK(imu.value->sampleCount() == 5500);
    CHECK(imu.value->spanNs() == 11627272566LL);

    // VERDICT: ANCHORED. offset = (recvd0-hdr)*1e6 - frame0Ns
    //   = 367e6 - 258158030289578 = -258157663289578 ns.
    const SyncEstimate est = estimateOffsetFromAnchors(
        recvdMs, hdrMs, frames.front());
    REQUIRE(est.isAnchored());
    CHECK(est.model.offsetNs == -258157663289578LL);
    CHECK(est.model.offsetMs() == doctest::Approx(-258157663.289578).epsilon(1e-9));
    CHECK(est.errorBoundMs == doctest::Approx(25.0));
    MESSAGE("fixture 261110_004150: ANCHORED, offset "
            << est.model.offsetMs() << " ms (bound ±"
            << est.errorBoundMs << " ms)");

    const FrameSync fs = framesToImu(frames, est.model,
                                     imu.value->firstTs(), imu.value->lastTs());
    MESSAGE("overlap: firstInside=" << fs.firstInside << " lastInside="
            << fs.lastInside << " inside=" << fs.insideFraction());
    // The gyro log covers the whole video: every frame inside.
    CHECK(fs.firstInside == 0);
    CHECK(fs.lastInside == n - 1);
    CHECK(fs.beforeGyroStart == 0);
    CHECK(fs.afterGyroEnd == 0);
    CHECK(fs.insideFraction() == doctest::Approx(1.0));
}

TEST_CASE("real fixture 261110_004208: anchored sync and overlap"
          * doctest::description("needs STEPS/media/test fixture")) {
    if (!fixtureAvailable("STEPS/media/test/261110_004208_VIDEO_24mm.gcsv") ||
        !fixtureAvailable("STEPS/media/test/261110_004208_VIDEO_24mm.mcraw")) {
        MESSAGE("SKIP: 261110_004208 fixtures not present");
        return;
    }
    const ContainerClocks cc = containerClocks(
        "STEPS/media/test/261110_004208_VIDEO_24mm.mcraw");
    const size_t n = cc.captureNs.size();
    REQUIRE(n == 310);
    const std::vector<int64_t>& frames = cc.captureNs;

    auto imu = parseGcsv("STEPS/media/test/261110_004208_VIDEO_24mm.gcsv");
    REQUIRE(imu.isOk());
    const long long hdrMs = gcsvHeaderTimestampMs(
        "STEPS/media/test/261110_004208_VIDEO_24mm.gcsv");
    REQUIRE(hdrMs == 1794260528447LL); // quoted: log start epoch ms
    const long long recvdMs = cc.frame0RecvdMs;
    REQUIRE(recvdMs == 1794260528798LL); // quoted: frame-0 receive epoch ms

    // Fixture B: tscale 0.0021144760 (differs from A), 5447 samples ->
    // span 11515436296 ns (round(5446*tscale*1e9)).
    CHECK(frames.front() == 258175962047287LL);
    CHECK(frames.back() - frames.front() == 10279987291LL);
    CHECK(imu.value->sampleCount() == 5447);
    CHECK(imu.value->spanNs() == 11515436296LL);

    // VERDICT: ANCHORED. offset = (recvd0-hdr)*1e6 - frame0Ns
    //   = 351e6 - 258175962047287 = -258175611047287 ns.
    const SyncEstimate est = estimateOffsetFromAnchors(
        recvdMs, hdrMs, frames.front());
    REQUIRE(est.isAnchored());
    CHECK(est.model.offsetNs == -258175611047287LL);
    CHECK(est.model.offsetMs() == doctest::Approx(-258175611.047287).epsilon(1e-9));
    CHECK(est.errorBoundMs == doctest::Approx(25.0));
    MESSAGE("fixture 261110_004208: ANCHORED, offset "
            << est.model.offsetMs() << " ms (bound ±"
            << est.errorBoundMs << " ms)");

    const FrameSync fs = framesToImu(frames, est.model,
                                     imu.value->firstTs(), imu.value->lastTs());
    MESSAGE("overlap: firstInside=" << fs.firstInside << " lastInside="
            << fs.lastInside << " inside=" << fs.insideFraction());
    CHECK(fs.firstInside == 0);
    CHECK(fs.lastInside == n - 1);
    CHECK(fs.beforeGyroStart == 0);
    CHECK(fs.afterGyroEnd == 0);
    CHECK(fs.insideFraction() == doctest::Approx(1.0));
}

TEST_CASE("real fixture ptsUs relationship: pts == captureNs/1000 (floor)"
          * doctest::description("needs STEPS/media/test fixture")) {
    // The Phase 3 brief asks to verify pts==captureNs/1000 on the fixtures.
    // MEASURED: the container ns are NOT us-aligned (310/310 frames have
    // ns%1000 != 0; e.g. frame 0 = 258158030289578 ns -> 578 ns remainder),
    // so Source.cpp's integer division is a FLOOR and ptsUs*1000 loses up
    // to 999 ns per frame. That loss is ~4e4x below the ±25 ms sync bound,
    // but sync reads the exact ns via the decoder (containerClocks above)
    // because exactness is free. This test pins the floor semantics.
    if (!fixtureAvailable("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw")) {
        MESSAGE("SKIP: 261110_004150 fixtures not present");
        return;
    }
    try {
        motioncam::Decoder decoder("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw");
        const auto& ts = decoder.getFrames();
        auto src = Source::open("STEPS/media/test/261110_004150_VIDEO_24mm.mcraw");
        REQUIRE(src.isOk());
        REQUIRE(ts.size() == (*src)->frameCount());
        size_t nonAligned = 0;
        for (size_t i = 0; i < ts.size(); i++) {
            const FrameInfo fi = (*src)->frameInfo(i);
            // floor division: ptsUs == captureNs/1000 exactly (Source.cpp),
            // and the reconstruction error is the ns remainder < 1000.
            CHECK(fi.ptsUs == ts[i] / 1000);
            CHECK(ts[i] - fi.ptsUs * 1000 >= 0);
            CHECK(ts[i] - fi.ptsUs * 1000 < 1000);
            if (ts[i] % 1000 != 0)
                ++nonAligned;
        }
        CHECK(nonAligned == 310); // quoted: every frame loses sub-us time
        // And monotonic strictly increasing (time-ordered container).
        for (size_t i = 1; i < ts.size(); i++)
            CHECK(ts[i] > ts[i - 1]);
    } catch (const std::exception& e) {
        FAIL("decoder threw: " << e.what());
    }
}
