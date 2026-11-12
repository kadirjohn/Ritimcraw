// JNI bridge: rawedit (core/) <-> Kotlin engine/NativeSource.kt.
//
// Handle-based: nativeOpenSource dups the caller's fd, constructs a
// rawedit::Source and returns an opaque handle; every other call takes that
// handle. The Kotlin side keeps one handle per clip and confines all calls
// to a single engine thread (one motioncam decoder instance is not safe for
// concurrent use).
//
// Errors: no rawedit exception ever crosses the boundary. Calls return
// "empty" values (0 / nullptr) and the caller reads nativeLastError() for a
// stage + cause + recovery message (spec SYS-02).

#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <new>
#include <map>
#include <string>
#include <utility>
#include <vector>

#include <android/log.h>

#include <rawedit/Develop.h>
#include <rawedit/DngWriter.h>
#include <rawedit/Gyro.h>
#include <rawedit/Lut.h>
#include <rawedit/Orientation.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Result.h>
#include <rawedit/RawStats.h>
#include <rawedit/Source.h>
#include <rawedit/Stabilization.h>
#include <rawedit/Sync.h>
#include <rawedit/Temporal.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>
#include <rawedit/Warp.h>

#include "gles/GlesPipeline.h"

namespace {

// Last error detail for the most recent failed call on this thread.
thread_local std::string gLastError;

// Numeric rawedit::ErrorCode of the most recent failed call on this thread
// (0 ok, 1 truncated, 2 badHeader, 3 ioError, 4 unsupported), plus the
// pipeline stage token, so the Kotlin PreviewErrors mapper can name the
// stage + cause + recovery per ErrorCode without re-parsing detail strings.
// All three are thread-local: read them from the same engine thread that
// failed. Updated by the preview setError only on failure paths.
thread_local int gLastErrorCode = 0;

// Stage tokens (shared contract with PreviewRenderer.kt / DngExporter.kt).
constexpr int kStageOpen = 0;
constexpr int kStageFrameRead = 1;
constexpr int kStageDevelop = 2;
constexpr int kStageDngWrite = 3;

thread_local int gLastErrorStage = kStageOpen;

// Debug measurement logging (audit fix 2026-11-12, stabilization round 2):
// one logcat line per settle-class render describing the stabilization
// actually applied — mode, strength, per-frame max angle, requiredZoom vs
// appliedZoom, IMU overlap, or the skip reason when nothing was applied.
// Tag "ritim-stab" (the "ritim-pm" / "ritim-scope" precedent):
//
//   adb logcat -s ritim-stab        (live)
//   adb logcat -d -s ritim-stab     (dump after a run)
//
// gStabDebugLog defaults OFF; NativeSource's companion init sets it from
// BuildConfig.DEBUG at library load, so release builds compile the checks
// away to a single relaxed load and never write a line. The per-render cost
// when on is one snprintf + one __android_log_print — the plan is already
// computed at that point, nothing is re-run for the log.
std::atomic<bool> gStabDebugLog{false};

void setError(const std::string& message) {
    gLastError = message;
}

void setError(int code, int stage, const std::string& message) {
    gLastErrorCode = code;
    gLastErrorStage = stage;
    gLastError = message;
}

// Stage-22 LUT cache (COL-07) -------------------------------------------------
//
// The preview engine thread and the export engine thread each call the
// develop entry points per frame; both hand the selected .cube's path as a
// jstring. Parsing a cube is expensive (up to 128^3 * 3 floats), so each
// thread keeps ONE parsed table: the path of the last parse and the table
// itself. Same path = reuse; different path = reparse; null/empty path =
// no LUT. A parse failure clears the cache and reports through the
// thread-local error state like every other pipeline failure.
thread_local std::string gLutPath;
thread_local std::unique_ptr<rawedit::Lut> gLut;

// Resolves the Kotlin lutPath argument into a table for this render.
// Returns false ONLY on a parse failure (error state set, cache cleared —
// the render fails loudly rather than silently dropping stage 24). Returns
// true with *out == nullptr when no LUT is selected (null or empty path —
// the old no-LUT look) or the path's file is missing (IoError is treated
// as no-LUT: LutStore.pathFor already checked existence, but a file
// removed between the Kotlin check and the open renders as no LUT rather
// than failing the whole clip — the grade still loads and the user can
// re-import the cube).
bool resolveLut(JNIEnv* env, jstring lutPath, const rawedit::Lut** out) {
    *out = nullptr;
    if (lutPath == nullptr) return true;
    const char* chars = env->GetStringUTFChars(lutPath, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "lut: out of memory reading the path (recovery: free memory and retry)");
        return false;
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(lutPath, chars);
    if (path.empty()) return true;

    if (gLut != nullptr && path == gLutPath) {
        *out = gLut.get();
        return true;
    }

    auto parsed = rawedit::parseCubeFile(path);
    if (!parsed.isOk()) {
        // Clear the stale cache so the next call with a good path reparses.
        gLut.reset();
        gLutPath.clear();
        setError(static_cast<int>(parsed.code), kStageDevelop,
                 std::string("lut: ") + rawedit::errorName(parsed.code) +
                     " — " + parsed.detail +
                     " (recovery: pick a valid .cube file)");
        return false;
    }
    gLut = std::make_unique<rawedit::Lut>(std::move(*parsed.value));
    gLutPath = path;
    *out = gLut.get();
    return true;
}

// Gyro stabilization cache (gyro Phase 5) ------------------------------------------------
//
// The gLut pattern mirrored for the sidecar .gcsv: parsing the log + running
// the VQF orientation filter is expensive, so each engine thread keeps ONE
// resolved artifact set keyed by path — same path = reuse, different path =
// reparse, null/empty path = no stabilization. The artifacts hold only the
// path-invariant work (parsed ImuData, axis-remapped sample streams, VQF
// orientation series); the clip-wide stabilization plan is recomputed per
// render because it depends on the request's mode/strength/focal/trim and
// the Source's frame timestamps — a plan cache keyed on that tuple would
// churn on every slider step anyway.
struct GyroArtifacts {
    rawedit::ImuData imu;                    // parsed log (logStartWallMs anchor)
    std::vector<rawedit::ImuSample> gyro;   // canonical camera frame
    std::vector<rawedit::ImuSample> accel;  // canonical camera frame
    rawedit::VqfOrientation orientation;     // VQF series + camera gravity
};

thread_local std::string gGyroPath;
thread_local std::unique_ptr<GyroArtifacts> gGyro;

// Resolves the Kotlin gyroPath argument into the cached artifacts for this
// render. Returns false ONLY on a parse failure (error state set, cache
// cleared — a corrupt .gcsv fails the render loudly like a corrupt .cube).
// Returns true with *out == nullptr when no log is attached (null/empty
// path — no warp) or the file went missing since the attach probe (IoError
// degrades to the unstabilized frame rather than failing the whole clip;
// the honest no-op, never a fabricated warp).
bool resolveGyro(JNIEnv* env, jstring gyroPath, const GyroArtifacts** out) {
    *out = nullptr;
    if (gyroPath == nullptr) return true;
    const char* chars = env->GetStringUTFChars(gyroPath, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "gyro: out of memory reading the path (recovery: free memory and retry)");
        return false;
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(gyroPath, chars);
    if (path.empty()) return true;

    if (gGyro != nullptr && path == gGyroPath) {
        *out = gGyro.get();
        return true;
    }

    auto parsed = rawedit::parseGcsv(path);
    if (!parsed.isOk()) {
        gGyro.reset();
        gGyroPath.clear();
        if (parsed.code == rawedit::ErrorCode::IoError) return true; // no log
        setError(static_cast<int>(parsed.code), kStageDevelop,
                 std::string("gyro: ") + rawedit::errorName(parsed.code) +
                     " — " + parsed.detail +
                     " (recovery: re-attach the clip's sidecar .gcsv)");
        return false;
    }

    // Canonical composition (core/tests/test_orientation_estimate.cpp
    // loadFixture): parse -> axisRemapForImu -> apply per gyro AND accel
    // sample -> estimateOrientationVqf. The identity+unknown fallback remap
    // is still applied when the metadata is absent — the documented
    // fallback, never a silent guess.
    auto artifacts = std::make_unique<GyroArtifacts>();
    artifacts->imu = std::move(*parsed.value);
    const rawedit::AxisRemap remap = rawedit::axisRemapForImu(artifacts->imu);
    artifacts->gyro.reserve(artifacts->imu.gyro.size());
    for (const rawedit::ImuSample& s : artifacts->imu.gyro) {
        float x = s.x, y = s.y, z = s.z;
        remap.apply(x, y, z);
        rawedit::ImuSample c;
        c.timestampNs = s.timestampNs;
        c.x = x;
        c.y = y;
        c.z = z;
        artifacts->gyro.push_back(c);
    }
    artifacts->accel.reserve(artifacts->imu.accel.size());
    for (const rawedit::ImuSample& s : artifacts->imu.accel) {
        float x = s.x, y = s.y, z = s.z;
        remap.apply(x, y, z);
        rawedit::ImuSample c;
        c.timestampNs = s.timestampNs;
        c.x = x;
        c.y = y;
        c.z = z;
        artifacts->accel.push_back(c);
    }
    artifacts->orientation = rawedit::estimateOrientationVqf(
        artifacts->gyro, artifacts->accel);
    gGyro = std::move(artifacts);
    gGyroPath = path;
    *out = gGyro.get();
    return true;
}

// The stabilization request as the six flat JNI params resolve it (gyro
// Phase 5). Built only when a gyro log resolved: the Kotlin layer already
// gated off / no-attachment down to a null gyroPath (the tier gate passes
// at every tier since the 2026-11-12 audit fix — the warp applies at drag,
// playback, paused and the full-res follow-up alike), so the C++ side
// never sees a request it should not run. Mode ints are GradeK's
// 0 = Smooth, 1 = LockFirstFrame, 2 = HorizonLock (the StabilizationMode
// enum order minus Off); strength is already 0..1 (GradeK percent / 100 at
// the Kotlin boundary, the lutStrength precedent); focalMm 0 = use the
// container value; trimMs is the manual sync trim over the anchored
// estimate.
struct StabRequest {
    rawedit::StabilizationMode mode = rawedit::StabilizationMode::Smooth;
    double strength = 0.5;
    double zoomLimit = 1.3;
    double focalMmOverride = 0.0;
    double trimMs = 0.0;
};

StabRequest stabRequestOf(jint mode, jfloat strength, jfloat zoomLimit,
                          jfloat focalMm, jfloat trimMs) {
    StabRequest r;
    r.mode = mode == 1 ? rawedit::StabilizationMode::LockFirstFrame
            : mode == 2 ? rawedit::StabilizationMode::HorizonLock
            : rawedit::StabilizationMode::Smooth;
    r.strength = strength;
    r.zoomLimit = zoomLimit;
    r.focalMmOverride = static_cast<double>(focalMm);
    r.trimMs = static_cast<double>(trimMs);
    return r;
}

// Warp decision from the plan's per-frame status: Ok / IntrinsicsAssumed /
// LowOverlap warp (LowOverlap still warps from the clamped orientation —
// the flag tells the UI which frames are held); Off / NoImu / Unsynced
// carry identity H + zoom 1 and MUST NOT pay the warp cost (Stabilization.h
// OFF-mode contract; Unsynced is the honest refusal to guess the clock
// bridge).
inline bool stabWarps(const rawedit::StabilizationFrame& f) {
    return f.status == rawedit::StabilizationStatus::Ok ||
           f.status == rawedit::StabilizationStatus::IntrinsicsAssumed ||
           f.status == rawedit::StabilizationStatus::LowOverlap;
}

// What one render's measurement line reports (audit fix 2026-11-12):
// the request as logged, the plan's clip-wide numbers, the overlap, and
// the per-frame slice at the rendered index. Filled by
// stabilizationFrameFor next to the frame it returns; logged by
// applyStabilization when gStabDebugLog is on.
struct StabMeasure {
    rawedit::StabilizationStatus status =
        rawedit::StabilizationStatus::Unsynced; // the frame's own status
    double requiredZoom = 1.0;
    double appliedZoom = 1.0;
    double frameAngleDeg = 0.0;  // |q_rel| at the rendered frame
    double maxAngleDeg = 0.0;    // worst |q_rel| over the clip plan
    size_t framesInside = 0;     // frames whose imuTs is inside the gyro span
    size_t framesTotal = 0;
    bool anchored = false;       // the sync estimate carried real anchors
};

// The clip-wide stabilization plan evaluated at [frameIndex], for the
// buffer being warped ([bufW] x [bufH] — thumbnail dims on the preview
// path, full dims on the export path; the homography scales with the
// buffer because the pixel focal is derived from its width). Self-contained
// per render: the sync estimate comes from THIS Source's frame-0 anchors
// (the probe's exact idiom), so the export path — a fresh session over the
// same file — resolves the same offset as the preview. The trim composes
// per Sync.h: an anchored estimate keeps its verdict (trimOffsetMs over
// it); an unanchored one with a non-zero trim becomes the manual offset; an
// unanchored one with no trim stays honestly Unsynced — offsetKnown false,
// computeStabilization returns Unsynced identity frames and the caller
// skips the warp. Never a fabricated zero offset.
// [measure], when non-null, receives the debug line's numbers (Fix 4 of the
// 2026-11-12 audit): status/zooms/overlap as computed for THIS render —
// no extra work, the plan is already in hand.
void stabilizationFrameFor(const StabRequest& req,
                          rawedit::Source& src,
                          int frameIndex,
                          int bufW, int bufH,
                          const GyroArtifacts* gyro,
                          rawedit::StabilizationFrame* out,
                          StabMeasure* measure = nullptr) {
    *out = rawedit::StabilizationFrame(); // identity + Off until proven
    if (measure != nullptr) measure->anchored = false;
    if (gyro == nullptr || bufW <= 0 || bufH <= 0) return;

    // Sync: frame-0 anchors from the Source itself.
    const rawedit::FrameInfo first = src.frameInfo(0);
    const rawedit::SyncEstimate est = rawedit::estimateOffsetFromAnchors(
        first.recvdMs, gyro->imu.logStartWallMs, first.ptsUs * 1000);
    rawedit::SyncModel model = est.model;
    bool offsetKnown = false;
    if (est.isAnchored()) {
        offsetKnown = true;
        if (req.trimMs != 0.0) {
            model = rawedit::trimOffsetMs(est, req.trimMs).model;
        }
    } else if (req.trimMs != 0.0) {
        offsetKnown = true;
        model = rawedit::manualOffsetMs(req.trimMs).model;
    }
    // else: unanchored + no trim -> offsetKnown stays false (Unsynced).
    if (measure != nullptr) measure->anchored = est.isAnchored();

    // Focal: the grade's override wins; 0 = the container's 35mm-equiv
    // value. focalLengthPxFromEquivalent is the one documented, FLAGGED
    // fallback (f_px = width * mm / 36) — assumedFocal below wires the
    // IntrinsicsAssumed status so the assumption is visible, never silent.
    const double focalMm = req.focalMmOverride > 0.0
        ? req.focalMmOverride
        : src.rawMeta().focalLengthMm;
    const double focalPx = rawedit::focalLengthPxFromEquivalent(focalMm, bufW);

    rawedit::StabilizationParams params;
    params.mode = req.mode;
    params.strength = req.strength;
    params.zoomLimit = req.zoomLimit;
    params.focalLengthPx = focalPx;
    params.width = bufW;
    params.height = bufH;
    // principalPoint keeps the {0,0} sentinel -> the buffer center; the
    // warp's cx/cy must match that resolution exactly (width/2, height/2).

    // Frame timestamps: the develop's frame clock (ptsUs) for the whole
    // clip — frameInfo is metadata-only, so this walk adds no decode.
    const size_t frames = src.frameCount();
    std::vector<int64_t> frameTs(frames);
    for (size_t i = 0; i < frames; ++i) {
        frameTs[i] = src.frameInfo(i).ptsUs * 1000;
    }

    const rawedit::StabilizationResult result = rawedit::computeStabilization(
        gyro->orientation.series, model, frameTs,
        gyro->orientation.gravityCamera, offsetKnown, /*assumedFocal=*/true,
        params);
    if (frameIndex >= 0 &&
        static_cast<size_t>(frameIndex) < result.frames.size()) {
        *out = result.frames[static_cast<size_t>(frameIndex)];
    }
    if (measure != nullptr) {
        measure->status = out->status;
        measure->requiredZoom = result.requiredZoom;
        measure->appliedZoom = result.appliedZoom;
        measure->framesTotal = result.frames.size();
        if (static_cast<size_t>(frameIndex) < result.relativeAngleDeg.size()) {
            measure->frameAngleDeg = result.relativeAngleDeg[
                static_cast<size_t>(std::max(0, frameIndex))];
        }
        for (double a : result.relativeAngleDeg)
            measure->maxAngleDeg = std::fmax(measure->maxAngleDeg, a);
        // IMU overlap: the same framesToImu the plan's LowOverlap flags
        // come from — frames mapped inside the gyro span (samples in the
        // window). Cheap: one pass over the already-mapped timestamps.
        const rawedit::FrameSync overlap = rawedit::framesToImu(
            frameTs, model, gyro->orientation.series.firstTs(),
            gyro->orientation.series.lastTs());
        measure->framesInside = overlap.frameCount -
                                overlap.beforeGyroStart - overlap.afterGyroEnd;
    }
}

// The warp input for one render (gyro Phase 5). gyro == nullptr is the
// no-warp default: the Kotlin layer already gated off / no-attachment down
// to a null gyroPath (the tier gate passes at every tier since the
// 2026-11-12 audit fix), and a log that went missing since the attach
// probe degrades the same way — so a default StabInput warps nothing and
// costs nothing.
struct StabInput {
    StabRequest request;
    rawedit::Source* source = nullptr;
    int frameIndex = -1;
    const GyroArtifacts* gyro = nullptr;
};

// resolveStabilization's result: ok=false is a .gcsv parse failure (error
// state set — the render must fail like a LUT parse failure); input.gyro ==
// nullptr means this render simply does not warp.
struct StabResolution {
    bool ok = true;
    StabInput input;
};

// Resolves the six flat JNI stabilization params into the warp input for
// this render. The Kotlin boundary already did the gating (stabilization
// off or no attachment -> null gyroPath; every tier passes the tier gate
// since the 2026-11-12 audit fix), so the ONLY decisions left here are
// the cache lookup and the honest failure. strength arrives already in
// rawedit's 0..1 scale (GradeK percent / 100 at the Kotlin boundary, the
// lutStrength precedent).
StabResolution resolveStabilization(JNIEnv* env, jstring gyroPath,
                                    jint stabMode, jfloat stabStrength,
                                    jfloat stabZoomLimit, jfloat stabFocalMm,
                                    jfloat stabTrimMs,
                                    rawedit::Source* src, int frameIndex) {
    StabResolution r;
    const GyroArtifacts* gyro = nullptr;
    if (!resolveGyro(env, gyroPath, &gyro)) {
        r.ok = false;
        return r;
    }
    if (gyro == nullptr) return r; // off / no log: the no-warp default
    r.input.request = stabRequestOf(stabMode, stabStrength, stabZoomLimit,
                                    stabFocalMm, stabTrimMs);
    r.input.source = src;
    r.input.frameIndex = frameIndex;
    r.input.gyro = gyro;
    return r;
}

// The shared warp step (gyro Phase 5): evaluates the clip plan at the
// buffer's OWN dims and warps [img] in place when the plan says this frame
// warps. Placement (binding decision): BEFORE the upright rotation, on the
// developed STORED-ORIENTATION buffer, identically on the preview and
// export paths — the homography is computed in the stored frame, and the
// export path rotates in Kotlin AFTER the native return
// (RotationState.rotateRgb888), so the warp composes with the rotation
// either way. No-op on the default input or a refusing status (Off / NoImu
// / Unsynced carry identity H and MUST NOT pay the warp cost).
//
// [maxDim] is the render's preview tier (0 = full res, the export path and
// the full-res follow-up). It gates ONLY the debug measurement line (audit
// fix 2026-11-12): the warp itself applies at EVERY tier (the Fix 1 rule),
// but the log fires on settle-class renders only — the paused/settle tier
// (>= 1280) and full res — so a slider drag or playback does not spam one
// line per frame. One line per settle render, tag "ritim-stab".
void applyStabilization(const StabInput& in, rawedit::ImageF& img,
                        int maxDim = 0) {
    if (in.gyro == nullptr || in.source == nullptr) return;
    if (img.w == 0 || img.h == 0) return;
    const bool wantLog = gStabDebugLog.load(std::memory_order_relaxed) &&
                         maxDim != 480 && maxDim != 720;
    rawedit::StabilizationFrame frame;
    StabMeasure measure;
    stabilizationFrameFor(in.request, *in.source, in.frameIndex,
                          static_cast<int>(img.w), static_cast<int>(img.h),
                          in.gyro, &frame, wantLog ? &measure : nullptr);
    if (wantLog) {
        char line[256];
        // Skip reasons read honestly: off/no-imu/unsynced say WHY nothing
        // was applied; the warp statuses carry the numbers that were.
        if (in.request.mode == rawedit::StabilizationMode::Off) {
            std::snprintf(line, sizeof line,
                "skip: mode off (frame %d, %dx%d)", in.frameIndex,
                static_cast<int>(img.w), static_cast<int>(img.h));
        } else if (!stabWarps(frame)) {
            std::snprintf(line, sizeof line,
                "skip: %s (frame %d, %dx%d)",
                rawedit::stabilizationStatusName(frame.status), in.frameIndex,
                static_cast<int>(img.w), static_cast<int>(img.h));
        } else {
            std::snprintf(line, sizeof line,
                "applied: mode=%d strength=%.2f frame=%d angle=%.3fdeg "
                "maxAngle=%.3fdeg requiredZoom=%.4f appliedZoom=%.4f "
                "imuOverlap=%zu/%zu status=%s %s",
                static_cast<int>(in.request.mode), in.request.strength,
                in.frameIndex, measure.frameAngleDeg, measure.maxAngleDeg,
                measure.requiredZoom, measure.appliedZoom,
                measure.framesInside, measure.framesTotal,
                rawedit::stabilizationStatusName(frame.status),
                measure.anchored ? "anchored" : "manual-trim");
        }
        __android_log_print(ANDROID_LOG_INFO, "ritim-stab", "%s", line);
    }
    if (!stabWarps(frame)) return;
    // cx/cy resolve the principalPoint sentinel exactly the way the core
    // plan resolves it ({0,0} sentinel -> the buffer center): the warp and
    // the plan must agree or the crop drifts off the intended center.
    const double cx = static_cast<double>(img.w) / 2.0;
    const double cy = static_cast<double>(img.h) / 2.0;
    img = rawedit::warpFrame(img, frame.h, frame.zoom, cx, cy);
}

// Handle registry: a small fixed table keeps the bridge simple and avoids
// passing raw pointers over JNI. Each session confines ITS calls to one
// engine thread (one motioncam decoder is not concurrency-safe), but the
// preview engine thread and the export engine thread share THIS table —
// insert/close from both must not race on slot bookkeeping (the owner's
// "too many open clips" class of failure), so the table itself is guarded.
constexpr int kMaxSources = 8;

// One immutable decoded CFA. Every decoded frame is wrapped in a shared
// const buffer the moment readFrame returns it, and the interactive cache,
// the temporal ring and the merge input all hold shared_ptrs to the SAME
// allocation: a frame is decoded at most once per eviction cycle and never
// copied between caches. Const-by-construction also enforces the Temporal.h
// "pristine inputs" contract — nothing can write a merged frame back into a
// buffer the ring still shares.
using CfaBuffer = std::shared_ptr<const std::vector<uint16_t>>;

// One decoded CFA in the temporal neighbor ring, keyed by frame index.
// PRISTINE by contract: the ring stores only what readFrame returned —
// temporal OUTPUT is never fed back as temporal INPUT (Temporal.h "what the
// caller owns"), so a merged frame can never become another merge's input.
struct RingFrame {
    int index = -1;
    CfaBuffer cfa;
};

// Temporal neighbor ring capacity (DN-09): center + prev + next is 3; the
// 4th slot lets a one-frame scrub back-and-forth stay cached. A 4K CFA is
// ~16.6 MB (uint16), so the ring bound is ~66 MB per open clip at 4K — the
// documented worst case (the ring is cleared on close/reopen and emptied
// when temporal NR turns off).
constexpr size_t kTemporalRingCapacity = 4;

struct Entry {
    std::unique_ptr<rawedit::Source> source;
    bool inUse = false;
    // Interactive slider cache (owner request 2026-11-10 section 1): the
    // last decoded CFA for THIS handle, so a grade-only re-render skips the
    // decode. Frame-keyed; a new handle (reopen) starts with an empty cache.
    // All access is confined to the owner session's engine thread except
    // slot bookkeeping, which the registry mutex already guards.
    CfaBuffer cachedCfa;
    rawedit::FrameInfo cachedInfo{};
    int cachedFrameIndex = -1;
    // Temporal NR state (spec DN-05..DN-09), same threading contract as the
    // interactive cache above (owner session's engine thread only):
    //   ring           — LRU of pristine decoded CFAs, see RingFrame;
    //   mergedCfa      — the last temporal merge OUTPUT, non-null while
    //                    mergedFrameIndex/mergedStrength match the request
    //                    (the merge is a pure function of the pristine
    //                    window + strength, so a grade-only re-render — any
    //                    non-temporal slider — reuses it instead of
    //                    re-merging).
    std::vector<RingFrame> ring;
    // Per-pair MAFD scene scores (source-comparison item #8, the trend
    // gate's memory): pair (a, a+1) is keyed by its END index a+1. The
    // score is a pure function of the PRISTINE decoded pair, so entries
    // never go stale for a given clip; the map is cleared whenever the
    // temporal caches are (off toggle, close) so a clip with temporal NR
    // off never holds temporal memory between renders. Bounded by the
    // prune in temporalMergedCfa (keys stay within +-2 of the rendered
    // frame). Same threading contract as the ring: owner engine thread.
    std::map<int, float> mafdByPairEnd;
    int mergedFrameIndex = -1;
    float mergedStrength = -1.0f;
    CfaBuffer mergedCfa;
};

Entry gEntries[kMaxSources];
std::mutex gRegistryMutex;

// Drops every per-session cache of one Entry (interactive + temporal). Call
// sites hold the registry mutex. The shared_ptr resets drop the Entry's
// references so the buffers free with the last owner — a closed 4K clip's
// ~66 MB ring must not stay resident.
void clearEntryCaches(Entry& e) {
    e.cachedCfa = nullptr;
    e.cachedInfo = rawedit::FrameInfo{};
    e.cachedFrameIndex = -1;
    e.ring.clear();
    e.ring.shrink_to_fit();
    e.mafdByPairEnd.clear();
    e.mergedFrameIndex = -1;
    e.mergedStrength = -1.0f;
    e.mergedCfa = nullptr;
}

int64_t insertSource(std::unique_ptr<rawedit::Source> src) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    for (int i = 0; i < kMaxSources; ++i) {
        if (!gEntries[i].inUse) {
            gEntries[i].source = std::move(src);
            gEntries[i].inUse = true;
            // A reopened slot starts with empty caches.
            clearEntryCaches(gEntries[i]);
            // Handle packs slot index; slots are tiny so this is exact.
            return static_cast<int64_t>(i) + 1;
        }
    }
    setError("openSource: too many open clips (recovery: close a clip and retry)");
    return 0;
}

rawedit::Source* sourceFor(int64_t handle) {
    if (handle <= 0 || handle > kMaxSources) {
        setError("invalid clip handle (recovery: reopen the clip)");
        return nullptr;
    }
    // Only the bookkeeping read is guarded: the returned Source* stays valid
    // because a handle is closed solely from the session's own engine thread
    // (drained after that session's renders), never from another thread.
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    Entry& e = gEntries[handle - 1];
    if (!e.inUse || !e.source) {
        setError("clip is closed (recovery: reopen the clip)");
        return nullptr;
    }
    return e.source.get();
}

jstring toJavaString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

// Preview pipeline helpers -----------------------------------------------------
//
// Decode -> developThumbnail (CFA decimation + the unchanged developCpu
// reference pipeline, core/Thumbnail.h) -> linearToRec709 -> clamp [0,1]
// -> 8-bit ARGB. Stage tokens + ErrorCode travel through thread-locals so
// the Kotlin layer can map stage + cause + recovery (spec SYS-02).

// Smallest strideEvery N (>= 1) whose thumbnail fits inside maxDim on the
// long edge, per the thumbnail math (outW = 2*ceil(width/2/N)). N = 1 is the
// identity full-res develop.
int strideFor(const rawedit::FrameInfo& info, int maxDim) {
    const long long w = info.width;
    const long long h = info.height;
    const long long longEdge = std::max(w, h);
    if (maxDim <= 0 || longEdge <= maxDim) return 1;
    // N cells of the source map to 1 cell of the thumbnail, so the long edge
    // shrinks by ~N; start from the exact ratio and walk up until it fits.
    int n = static_cast<int>((longEdge + maxDim - 1) / maxDim);
    if (n < 1) n = 1;
    while (n < 64) {
        const uint32_t outW = rawedit::thumbnailWidth(info.width, n);
        const uint32_t outH = rawedit::thumbnailHeight(info.height, n);
        if (outW == 0 || outH == 0) break;
        if (static_cast<long long>(std::max(outW, outH)) <= maxDim) return n;
        ++n;
    }
    return n;
}

// GradeK -> rawedit::Grade: identity field mapping, floats as-is. The grade
// floats travel as flat jfloat parameters (same style as the original four):
// the calls are per-frame and the JVM passes floats in registers, so a
// jfloatArray would only add a GetFloatArrayElements copy per render with no
// safety gain. The three RAW booleans (asShot, highlightReconstruction,
// keepVignetting) follow the same flat-register style as jbooleans; the
// detail/tonemap package (COL-15/COL-16) appends two floats and the tonemap
// selector int in the same style; temporal NR (DN-05..DN-09) appends its
// toggle + strength the same way. Parameter order = GradeK field order
// (Develop.h Grade order): exposureEv, highlights, tempK, tint, saturation,
// blackPoint, whitePoint, shadows, midtones, contrast, asShot,
// highlightReconstruction, keepVignetting, sharpness, detail, tonemap. The
// LUT package (COL-07) appends the strength blend float in the same style;
// the Kotlin layer maps GradeK's 0..100 percent to rawedit's 0..1 blend
// before the call, so the jfloat here is already native-scale. The noise
// package (DN-01..08) appends its two floats, and the temporal package
// (DN-05..09) its bool+float, last, in rawedit's native scale. The color
// package (COL-13) appends its nine floats after temporalStrength in Grade
// order: six wheel offsets (unit-disc [-1,1], (0,0) neutral) and three
// split-tone scalars (two hue degrees with -1 = off, balance -100..100).
// developCpu
// itself ignores the temporal fields (the bridge applies the stage to the
// CFA before developCpu); they ride Grade so the whole grade pipeline
// carries them like any other control.
rawedit::Grade gradeFromKotlin(float exposureEv, float highlights, float tempK, float tint,
                               float saturation, float blackPoint, float whitePoint,
                               float shadows, float midtones, float contrast,
                               bool asShot, bool highlightReconstruction, bool keepVignetting,
                               float sharpness, float detail, int tonemap,
                               float lutStrength,
                               float noiseReduction, float chromaDenoise,
                               bool temporalNr, float temporalStrength,
                               float wheelShadowX, float wheelShadowY,
                               float wheelMidX, float wheelMidY,
                               float wheelHighX, float wheelHighY,
                               float splitShadowHue, float splitHighlightHue,
                               float splitBalance) {
    rawedit::Grade g;
    g.exposureEv = exposureEv;
    g.highlights = highlights;
    g.tempK = tempK;
    g.tint = tint;
    g.saturation = saturation;
    g.blackPoint = blackPoint;
    g.whitePoint = whitePoint;
    g.shadows = shadows;
    g.midtones = midtones;
    g.contrast = contrast;
    g.asShot = asShot;
    g.highlightReconstruction = highlightReconstruction;
    g.keepVignetting = keepVignetting;
    g.sharpness = sharpness;
    g.detail = detail;
    g.tonemap = tonemap;
    g.lutStrength = lutStrength;
    g.noiseReduction = noiseReduction;
    g.chromaDenoise = chromaDenoise;
    g.temporalNr = temporalNr;
    g.temporalStrength = temporalStrength;
    g.wheelShadowX = wheelShadowX;
    g.wheelShadowY = wheelShadowY;
    g.wheelMidX = wheelMidX;
    g.wheelMidY = wheelMidY;
    g.wheelHighX = wheelHighX;
    g.wheelHighY = wheelHighY;
    g.splitShadowHue = splitShadowHue;
    g.splitHighlightHue = splitHighlightHue;
    g.splitBalance = splitBalance;
    return g;
}

// linearToRec709 output is unclamped; clamp then quantize to 0..255.
inline uint8_t toByte(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    // Round-to-nearest with ties resolved up, matching the DNG path's
    // integer quantization style.
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}

// Develop a decoded CFA into the preview's ARGB_8888 jint array
// ([width, height, pixels...], rotated upright per FrameInfo.orientationDeg).
// Shared by the plain preview render and the cached interactive render so the
// two paths stay pixel-identical. lut is the already-resolved stage-22 table
// (nullptr = no LUT). stab is the resolved gyro stabilization input (gyro ==
// nullptr = no warp — the default-constructed StabInput); the warp runs on
// the developed STORED-ORIENTATION thumbnail, BEFORE the upright rotation
// below (binding placement decision: the homography is computed in the
// stored frame). Returns nullptr (error state set) on a
// develop failure; a NewIntArray failure propagates as nullptr with a
// pending JNI exception, which the Kotlin wrapper already maps to a render
// failure.
jintArray developToArgb(JNIEnv* env,
                         const std::vector<uint16_t>& cfa,
                         const rawedit::FrameInfo& info,
                         const rawedit::RawMeta& meta,
                         const rawedit::Grade& grade,
                         const rawedit::Lut* lut,
                         const StabInput& stab,
                         int maxDim) {
    const int strideEvery = strideFor(info, maxDim);
    rawedit::ImageF img = rawedit::developThumbnail(
        cfa.data(), info, meta, grade, strideEvery, lut);
    if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the frame is too small to develop "
                 "(recovery: re-import the clip)");
        return nullptr;
    }

    // Gyro warp on the stored-orientation thumbnail (no-op without a
    // resolved log; identity/Unsynced frames cost nothing). maxDim rides
    // along only for the debug log's settle-tier gate (audit fix
    // 2026-11-12): the warp itself applies at every tier.
    applyStabilization(stab, img, maxDim);

    const jsize w = static_cast<jsize>(img.w);
    const jsize h = static_cast<jsize>(img.h);
    const int orient = ((info.orientationDeg % 360) + 360) % 360;
    jsize outW = w;
    jsize outH = h;
    if (orient == 90 || orient == 270) {
        outW = h;
        outH = w;
    }
    const jsize n = outW * outH;
    // [width, height, then n ARGB pixels].
    std::vector<jint> out(static_cast<size_t>(n) + 2);
    out[0] = outW;
    out[1] = outH;
    jint* px = out.data() + 2;
    for (jsize y = 0; y < outH; ++y) {
        for (jsize x = 0; x < outW; ++x) {
            // Source pixel under destination (x, y) after the rotation.
            jsize si;
            switch (orient) {
                case 90:  si = static_cast<jsize>(h - 1 - x) * w + y; break;
                case 180: si = static_cast<jsize>(h - 1 - y) * w + (w - 1 - x); break;
                case 270: si = x * w + (w - 1 - y); break;
                default:  si = y * w + x; break;
            }
            const float r = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 0]);
            const float g = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 1]);
            const float b = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 2]);
            px[static_cast<size_t>(y) * outW + x] =
                0xFF000000 |
                (static_cast<jint>(toByte(r)) << 16) |
                (static_cast<jint>(toByte(g)) << 8) |
                static_cast<jint>(toByte(b));
        }
    }
    jintArray arr = env->NewIntArray(n + 2);
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, n + 2, out.data());
    return arr;
}

// Temporal NR window assembly (spec DN-05..DN-09) ------------------------------
//
// Everything the JNI entry points need from Temporal.h, gathered behind one
// helper so the preview, cached-preview and export paths run the SAME logic:
//   - an LRU ring of pristine decoded CFAs (kTemporalRingCapacity entries,
//     newest first) so sequential playback/scrub amortizes to ~1 decode per
//     frame (DN-09);
//   - PTS-window eligibility via temporalNeighborEligible against the LOCAL
//     frame duration (median of the center frame's immediate neighbors'
//     PTS deltas; falls back to 1/fps-style spacing from pts[0] when the
//     clip has fewer than three frames);
//   - dimension + CFA-pattern agreement with the center frame (a neighbor
//     from a format change inside the clip is rejected, never blended);
//   - per-frame noise profiles via Source::noiseProfile (the temporal stage
//     itself falls back to its documented fallback sigma when invalid);
//   - a (frameIndex, strength)-keyed merge cache: the merge is a pure
//     function of the pristine window + strength, so a grade-only re-render
//     (any non-temporal slider moved) reuses the previous merge output
//     instead of re-running motion estimation.
//
// Returns the CFA to develop: the merged frame when temporal NR produced
// one, or the PRISTINE center otherwise. NEVER mutates the ring (the ring
// is inputs-only). A nullptr source ring entry (decode failure) is treated
// as "no such neighbor" — the merge then proceeds with the other side, the
// conservative behavior.

// Returns frame i's pristine CFA from the ring, decoding it once on a miss.
// The buffer is shared-const (CfaBuffer), so the returned pointer stays valid
// no matter what later loads do to the ring (eviction frees the RingFrame,
// not a buffer someone else still holds). MRU order is maintained by moving
// a hit to the back. Returns nullptr on a decode failure (error already set).
CfaBuffer loadRingFrame(rawedit::Source& s, Entry& e, int frameIndex) {
    for (size_t i = 0; i < e.ring.size(); ++i) {
        if (e.ring[i].index != frameIndex) continue;
        // Move the hit to the back (MRU) — cheap: shared_ptr swap.
        if (i + 1 != e.ring.size()) {
            RingFrame hit = std::move(e.ring[i]);
            e.ring.erase(e.ring.begin() + static_cast<long>(i));
            e.ring.push_back(std::move(hit));
        }
        return e.ring.back().cfa;
    }
    auto frame = s.readFrame(static_cast<size_t>(frameIndex));
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("frame read: ") + rawedit::errorName(frame.code) +
                     " — " + frame.detail);
        return nullptr;
    }
    RingFrame nf;
    nf.index = frameIndex;
    nf.cfa = CfaBuffer(new std::vector<uint16_t>(std::move(*frame.value)));
    e.ring.push_back(std::move(nf));
    while (e.ring.size() > kTemporalRingCapacity) e.ring.erase(e.ring.begin());
    return e.ring.back().cfa;
}

// Ensures the ALREADY-DECODED center buffer is in the ring WITHOUT a decode:
// a hit just refreshes MRU order; a miss pushes the shared buffer (no copy).
// Used by temporalMergedCfa so a cache-hit center (the interactive cache
// survived, but the ring was cleared by an off->on toggle or evicted during
// a scrub) rejoins its neighbors at zero decode cost.
CfaBuffer ensureRingFrame(Entry& e, int frameIndex, CfaBuffer cfa) {
    for (size_t i = 0; i < e.ring.size(); ++i) {
        if (e.ring[i].index != frameIndex) continue;
        if (i + 1 != e.ring.size()) {
            RingFrame hit = std::move(e.ring[i]);
            e.ring.erase(e.ring.begin() + static_cast<long>(i));
            e.ring.push_back(std::move(hit));
        }
        return e.ring.back().cfa;
    }
    RingFrame nf;
    nf.index = frameIndex;
    nf.cfa = std::move(cfa);
    e.ring.push_back(std::move(nf));
    while (e.ring.size() > kTemporalRingCapacity) e.ring.erase(e.ring.begin());
    return e.ring.back().cfa;
}

// True when neighbor info can be blended with center info: same CFA
// geometry (dimensions + pattern). Orientation change inside one clip would
// also land here; FrameInfo has no per-frame orientation change in this
// container, so geometry is the full check.
bool temporalNeighborCompatible(const rawedit::FrameInfo& center,
                                const rawedit::FrameInfo& neighbor) {
    return center.width == neighbor.width && center.height == neighbor.height &&
           center.cfa == neighbor.cfa;
}

// Local median frame duration around the center frame (microseconds):
// median of {pts[i] - pts[i-1], pts[i+1] - pts[i]} — the local spacing, not
// a whole-clip average, so a VFR clip with a slow intro still uses the
// spacing around THIS frame. 0 when unknown (fewer than two frames); the
// caller then skips the distance decay (dPts fields stay 0 = no decay).
int64_t localFrameDurationUs(rawedit::Source& s, int frameIndex) {
    const size_t n = s.frameCount();
    if (n < 2) return 0;
    const int i = frameIndex;
    int64_t deltas[2] = {0, 0};
    int k = 0;
    if (i - 1 >= 0) deltas[k++] = s.frameInfo(static_cast<size_t>(i)).ptsUs -
                                  s.frameInfo(static_cast<size_t>(i - 1)).ptsUs;
    if (i + 1 < static_cast<int>(n))
        deltas[k++] = s.frameInfo(static_cast<size_t>(i + 1)).ptsUs -
                      s.frameInfo(static_cast<size_t>(i)).ptsUs;
    if (k == 2) return (deltas[0] + deltas[1]) / 2; // median of 2 = mean
    return deltas[0];
}

// The assembled window. Prev/next are shared-const buffers: eviction frees
// the RingFrame, never a buffer this window still holds. Null when that
// side is missing or ineligible. Noise profiles travel by value (8 floats
// each); the temporal stage ignores them when .valid is false (fallback
// sigma).
struct TemporalWindow {
    CfaBuffer prev;
    CfaBuffer next;
    rawedit::NoiseProfile prevNoise{};
    rawedit::NoiseProfile nextNoise{};
    rawedit::NoiseProfile centerNoise{};
    int64_t prevDPtsUs = 0;
    int64_t nextDPtsUs = 0;
    int64_t frameDurationUs = 0;
};

// Loads the eligible temporal neighbors of frameIndex into the ring and
// assembles the window. Returns false ONLY on a hard decode failure of a
// neighbor (error set); missing/ineligible neighbors just leave their side
// null. The center CFA itself is NOT loaded here — the caller routes it
// through loadRingFrame too (the ring is the complete temporal window
// store; sequential playback then decodes only the new frontier frame,
// because frame i+1's prefetch is frame i's neighbor).
bool assembleTemporalWindow(rawedit::Source& s, Entry& e, int frameIndex,
                            const rawedit::FrameInfo& centerInfo,
                            TemporalWindow& w) {
    const int n = static_cast<int>(s.frameCount());
    w.frameDurationUs = localFrameDurationUs(s, frameIndex);
    w.centerNoise = s.noiseProfile(static_cast<size_t>(frameIndex));
    for (int side = 0; side < 2; ++side) {
        const int ni = side == 0 ? frameIndex - 1 : frameIndex + 1;
        if (ni < 0 || ni >= n) continue;
        const rawedit::FrameInfo niInfo = s.frameInfo(static_cast<size_t>(ni));
        const int64_t dPts = niInfo.ptsUs - centerInfo.ptsUs;
        if (!rawedit::temporalNeighborEligible(dPts, w.frameDurationUs)) continue;
        if (!temporalNeighborCompatible(centerInfo, niInfo)) continue;
        const CfaBuffer cfa = loadRingFrame(s, e, ni);
        if (cfa == nullptr) return false; // decode failed: hard error
        if (side == 0) {
            w.prev = cfa;
            w.prevNoise = s.noiseProfile(static_cast<size_t>(ni));
            w.prevDPtsUs = dPts;
        } else {
            w.next = cfa;
            w.nextNoise = s.noiseProfile(static_cast<size_t>(ni));
            w.nextDPtsUs = dPts;
        }
    }
    return true;
}

// Trend-gate cache accessors (source-comparison item #8): the bridge owns
// the per-pair MAFD memory so the merge stays stateless. Pair (a, a+1) is
// keyed by END index a+1; a miss (scrub start, first pair, cleared cache)
// returns negative = "unknown", which leaves only the absolute MAFD gate
// active — the designed conservative degradation.
float cachedPairMafd(const Entry& e, int pairEndIndex) {
    const auto it = e.mafdByPairEnd.find(pairEndIndex);
    return it == e.mafdByPairEnd.end() ? -1.0f : it->second;
}

// Records this render's pair scores and prunes the map to pairs adjacent
// to the rendered frame (the trend gate never reads beyond frameIndex-1 ..
// frameIndex+1, so keys outside [frameIndex - 2, frameIndex + 2] are dead).
// rep.*.present is exactly "a buffer was passed"; the merge computes the
// score (and reports it) even when a gate then rejects the pair.
void storePairMafds(Entry& e, int frameIndex, const rawedit::TemporalReport& rep) {
    if (rep.prev.present)
        e.mafdByPairEnd[frameIndex] = rep.prev.mafd;      // pair (i-1, i)
    if (rep.next.present)
        e.mafdByPairEnd[frameIndex + 1] = rep.next.mafd;  // pair (i, i+1)
    e.mafdByPairEnd.erase(e.mafdByPairEnd.begin(),
                          e.mafdByPairEnd.lower_bound(frameIndex - 2));
    e.mafdByPairEnd.erase(e.mafdByPairEnd.upper_bound(frameIndex + 2),
                          e.mafdByPairEnd.end());
}

// Produces the CFA for developCpu with temporal NR applied when enabled:
//   strength <= 0 or temporalNr off  -> the pristine center, untouched, and
//   the temporal caches are emptied (an off->on toggle starts clean);
//   cache hit (frame + strength)     -> the previous merge output;
//   otherwise                        -> temporalMergeCfa over the window,
//   output cached in mergedCfa.
// centerCfa is the ALREADY-DECODED center (the interactive cache or a fresh
// readFrame — the caller routes it through loadRingFrame too, so the ring
// holds every frame of the window). On a neighbor decode failure returns
// nullptr (error already set) so the caller fails the render; the
// conservative alternative (merge with the other side only) would hide a
// broken clip behind a slow render.
CfaBuffer temporalMergedCfa(
        rawedit::Source& s, Entry& e, int frameIndex,
        CfaBuffer centerCfa,
        const rawedit::FrameInfo& centerInfo,
        bool temporalNr, float strength) {
    if (!temporalNr || strength <= 0.0f) {
        // Zero-cost when off: no neighbor decodes, no merge. Drop any
        // temporal caches — including this render's center ring entry, so
        // a clip with temporal NR off never holds temporal memory between
        // renders (the interactive cachedCfa is untouched).
        e.mergedFrameIndex = -1;
        e.mergedStrength = -1.0f;
        e.mergedCfa = nullptr;
        e.ring.clear();
        e.ring.shrink_to_fit();
        e.mafdByPairEnd.clear();
        return centerCfa;
    }
    if (e.mergedFrameIndex == frameIndex && e.mergedStrength == strength &&
        e.mergedCfa != nullptr) {
        return e.mergedCfa; // grade-only re-render: same merge, reuse
    }
    // The center joins the ring FIRST (no decode: the buffer is shared), so
    // the window assembly below and any same-frame retry read it from there.
    centerCfa = ensureRingFrame(e, frameIndex, std::move(centerCfa));
    TemporalWindow w;
    if (!assembleTemporalWindow(s, e, frameIndex, centerInfo, w)) {
        return nullptr;
    }
    if (w.prev == nullptr && w.next == nullptr) {
        // No eligible neighbor: both-rejected/edge frame. Output is the
        // center bit for bit (the shared pristine buffer itself — no copy);
        // cached so a grade-only re-render reuses it.
        e.mergedFrameIndex = frameIndex;
        e.mergedStrength = strength;
        e.mergedCfa = centerCfa;
        return e.mergedCfa;
    }
    rawedit::TemporalSettings ts;
    ts.strength = strength;
    ts.centerNoise = &w.centerNoise;
    ts.prevNoise = &w.prevNoise;
    ts.nextNoise = &w.nextNoise;
    ts.prevDPtsUs = w.prevDPtsUs;
    ts.nextDPtsUs = w.nextDPtsUs;
    ts.frameDurationUs = w.frameDurationUs;
    // Trend gate (item #8): the PREVIOUS pair-in-time's score for each
    // side. Prev side's pair is (i-2, i-1) -> key i-1; next side's pair is
    // (i-1, i) -> key i. A miss passes negative (absolute gate only).
    ts.prevPairMafd = cachedPairMafd(e, frameIndex - 1);
    ts.nextPairMafd = cachedPairMafd(e, frameIndex);
    auto merged = std::make_shared<std::vector<uint16_t>>(centerCfa->size(), 0);
    rawedit::TemporalReport rep;
    rawedit::temporalMergeCfa(centerCfa->data(), centerInfo,
                              w.prev ? w.prev->data() : nullptr,
                              w.next ? w.next->data() : nullptr,
                              s.rawMeta(), ts, merged->data(), &rep);
    storePairMafds(e, frameIndex, rep);
    e.mergedFrameIndex = frameIndex;
    e.mergedStrength = strength;
    e.mergedCfa = CfaBuffer(std::move(merged));
    return e.mergedCfa;
}

} // namespace

extern "C" {

// rawedit::Source* lifecycle -------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeOpenSource(JNIEnv*, jclass, jint fd) {
    auto result = rawedit::Source::open(static_cast<int>(fd));
    if (!result.isOk()) {
        setError(std::string("openSource: ") + rawedit::errorName(result.code) +
                 " — " + result.detail);
        return 0;
    }
    return insertSource(std::move(*result.value));
}

JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeCloseSource(JNIEnv*, jclass, jlong handle) {
    if (handle > 0 && handle <= kMaxSources) {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        Entry& e = gEntries[handle - 1];
        e.source.reset();
        e.inUse = false;
        // Drop the caches with the Source they belong to (a reopened handle
        // starts fresh) and return their memory.
        clearEntryCaches(e);
    }
}

JNIEXPORT jint JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeFrameCount(JNIEnv*, jclass, jlong handle) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return -1;
    return static_cast<jint>(s->frameCount());
}

// FrameInfo -> FrameInfoK -----------------------------------------------------

JNIEXPORT jobject JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeFrameInfo(JNIEnv* env, jclass, jlong handle, jint index) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(index));

        jclass rectCls = env->FindClass("android/graphics/Rect");
        if (rectCls == nullptr) return nullptr;
        jmethodID rectCtor = env->GetMethodID(rectCls, "<init>", "(IIII)V");
        if (rectCtor == nullptr) return nullptr;
        jobject active = env->NewObject(
            rectCls, rectCtor,
            static_cast<jint>(info.activeArea.x),
            static_cast<jint>(info.activeArea.y),
            static_cast<jint>(info.activeArea.w),
            static_cast<jint>(info.activeArea.h));

        jclass cls = env->FindClass("com/ritim/ritimcraw/engine/FrameInfoK");
        if (cls == nullptr) return nullptr;
        jmethodID ctor = env->GetMethodID(
            cls, "<init>", "(JIIIILandroid/graphics/Rect;I)V");
        if (ctor == nullptr) return nullptr;
        return env->NewObject(
            cls, ctor,
            static_cast<jlong>(info.ptsUs),
            static_cast<jint>(info.width),
            static_cast<jint>(info.height),
            static_cast<jint>(info.bitDepth),
            static_cast<jint>(static_cast<int>(info.cfa)),
            active,
            static_cast<jint>(info.orientationDeg));
    } catch (const std::exception& e) {
        setError(std::string("frameInfo: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// Frames / audio ---------------------------------------------------------------

JNIEXPORT jshortArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeReadFrame(JNIEnv* env, jclass, jlong handle, jint index) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        auto result = s->readFrame(static_cast<size_t>(index));
        if (!result.isOk()) {
            setError(std::string("readFrame: ") + rawedit::errorName(result.code) +
                     " — " + result.detail);
            return nullptr;
        }
        const std::vector<uint16_t>& frame = *result.value;
        const jsize n = static_cast<jsize>(frame.size());
        jshortArray out = env->NewShortArray(n);
        if (out == nullptr) return nullptr;
        // uint16 samples reinterpreted as int16: bit pattern preserved.
        env->SetShortArrayRegion(
            out, 0, n, reinterpret_cast<const jshort*>(frame.data()));
        return out;
    } catch (const std::bad_alloc&) {
        setError("readFrame: out of memory decoding the frame (recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(std::string("readFrame: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jshortArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeReadAudio(JNIEnv* env, jclass, jlong handle) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        auto result = s->readAudio();
        if (!result.isOk()) {
            setError(std::string("readAudio: ") + rawedit::errorName(result.code) +
                     " — " + result.detail);
            return nullptr;
        }
        const std::vector<int16_t>& audio = *result.value;
        const jsize n = static_cast<jsize>(audio.size());
        jshortArray out = env->NewShortArray(n);
        if (out == nullptr) return nullptr;
        env->SetShortArrayRegion(out, 0, n, audio.data());
        return out;
    } catch (const std::exception& e) {
        setError(std::string("readAudio: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeLastError(JNIEnv* env, jclass) {
    if (gLastError.empty()) return nullptr;
    return toJavaString(env, gLastError);
}

// Debug measurement logging toggle (audit fix 2026-11-12): NativeSource's
// companion init calls this once at library load with BuildConfig.DEBUG, so
// the "ritim-stab" per-settle-render line exists only in debug builds —
// release keeps the flag's default false and stays silent.
JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeSetStabDebugLog(
        JNIEnv*, jclass, jboolean enabled) {
    gStabDebugLog.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

// Preview pipeline -------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewOpen(
        JNIEnv* env, jclass, jint fd) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    auto result = rawedit::Source::open(static_cast<int>(fd));
    if (!result.isOk()) {
        setError(static_cast<int>(result.code), kStageOpen,
                 std::string("open: ") + rawedit::errorName(result.code) +
                     " — " + result.detail);
        return nullptr;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*result.value);
    const size_t frames = src->frameCount();
    if (frames == 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "open: unsupported — the clip holds no video frames "
                 "(recovery: re-import the clip)");
        return nullptr;
    }
    const rawedit::FrameInfo first = src->frameInfo(0);
    // Read before insertSource takes ownership (owner request 2026-11-11
    // section 2/3): the container RAW white level feeds the clip info sheet
    // ("RAW white level: 1023"). It is the fallback calibration — the
    // per-frame dynamic levels ride FrameInfo into the render/DNG calls.
    const uint16_t containerWhite = src->rawMeta().white;
    const int64_t handle = insertSource(std::move(src));
    if (handle == 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 gLastError);
        return nullptr;
    }
    // [handle, frameCount, firstWidth, firstHeight, firstCfa,
    //  firstOrientation, containerWhiteLevel]
    jint out[7] = {
        static_cast<jint>(handle),
        static_cast<jint>(frames),
        static_cast<jint>(first.width),
        static_cast<jint>(first.height),
        static_cast<jint>(static_cast<int>(first.cfa)),
        static_cast<jint>(first.orientationDeg),
        static_cast<jint>(containerWhite),
    };
    jintArray arr = env->NewIntArray(7);
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 7, out);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewRender(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength,
        jfloat noiseReduction, jfloat chromaDenoise,
        jboolean temporalNr, jfloat temporalStrength,
        jfloat wheelShadowX, jfloat wheelShadowY,
        jfloat wheelMidX, jfloat wheelMidY,
        jfloat wheelHighX, jfloat wheelHighY,
        jfloat splitShadowHue, jfloat splitHighlightHue,
        jfloat splitBalance,
        jstring gyroPath, jint stabMode, jfloat stabStrength,
        jfloat stabZoomLimit, jfloat stabFocalMm, jfloat stabTrimMs,
        jint maxDim) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // render with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 0b. Resolve the gyro stabilization input (gyro Phase 5): a .gcsv
        //     parse failure fails the render; a null path (off / tier skip /
        //     no attachment — the Kotlin gate) warps nothing.
        const StabResolution stab =
            resolveStabilization(env, gyroPath, stabMode, stabStrength,
                                 stabZoomLimit, stabFocalMm, stabTrimMs,
                                 s, frameIndex);
        if (!stab.ok) return nullptr;

        // 1. Frame read — through the ring, so with temporal NR on the
        //    center joins its neighbors in one LRU (the merge reads them
        //    all back from it; with NR off the ring is cleared by
        //    temporalMergedCfa and this was a plain decode).
        Entry& e = gEntries[handle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Temporal NR on the CFA (pristine buffer back when off).
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            temporalNr == JNI_TRUE, temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        // 3-4. Develop with CFA decimation + warp + ARGB (shared preview
        //      tail; the warp is a no-op without a resolved log).
        return developToArgb(env, *cfa, info, meta,
                             gradeFromKotlin(exposureEv, highlights, tempK, tint,
                                             saturation, blackPoint, whitePoint,
                                             shadows, midtones, contrast,
                                             asShot, highlightReconstruction,
                                             keepVignetting, sharpness, detail,
                                             tonemap, lutStrength,
                                             noiseReduction, chromaDenoise,
                                             temporalNr == JNI_TRUE,
                                             temporalStrength,
                                             wheelShadowX, wheelShadowY,
                                             wheelMidX, wheelMidY,
                                             wheelHighX, wheelHighY,
                                             splitShadowHue, splitHighlightHue,
                                             splitBalance),
                             lut, stab.input, maxDim);
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// Interactive slider render (owner request 2026-11-10 section 1): same output
// contract as nativePreviewRender, but the handle's Entry caches the last
// decoded CFA. When frameIndex matches the cache the DECODE is skipped and
// only the develop+ARGB tail runs — a grade-only re-render during a slider
// drag. A frameIndex change (or a reopened handle, whose cache starts empty)
// decodes and replaces the cache. Same thread as the session's other calls
// (the single preview engine thread), so the cache needs no extra locking
// beyond the registry's slot bookkeeping.
JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewSessionRenderCached(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength,
        jfloat noiseReduction, jfloat chromaDenoise,
        jboolean temporalNr, jfloat temporalStrength,
        jfloat wheelShadowX, jfloat wheelShadowY,
        jfloat wheelMidX, jfloat wheelMidY,
        jfloat wheelHighX, jfloat wheelHighY,
        jfloat splitShadowHue, jfloat splitHighlightHue,
        jfloat splitBalance,
        jstring gyroPath, jint stabMode, jfloat stabStrength,
        jfloat stabZoomLimit, jfloat stabFocalMm, jfloat stabTrimMs,
        jint maxDim) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // render with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    if (handle <= 0 || handle > kMaxSources) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "invalid clip handle (recovery: reopen the clip)");
        return nullptr;
    }
    Entry& e = gEntries[handle - 1];
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 0b. Resolve the gyro stabilization input (gyro Phase 5): a .gcsv
        //     parse failure fails the render; a null path (off / tier skip /
        //     no attachment — the Kotlin gate) warps nothing.
        const StabResolution stab =
            resolveStabilization(env, gyroPath, stabMode, stabStrength,
                                 stabZoomLimit, stabFocalMm, stabTrimMs,
                                 s, frameIndex);
        if (!stab.ok) return nullptr;

        // 1. Decode only when the requested frame is not the cached one —
        //    through the ring, so a neighbor prefetched during the previous
        //    frame's merge becomes this frame's center with no decode (with
        //    temporal NR off the ring is cleared right after; the shared
        //    buffer survives in the interactive cache).
        if (frameIndex != e.cachedFrameIndex) {
            const CfaBuffer buf = loadRingFrame(*s, e, frameIndex);
            if (buf == nullptr) return nullptr; // decode failed (error set)
            e.cachedCfa = buf;
            e.cachedInfo = s->frameInfo(static_cast<size_t>(frameIndex));
            e.cachedFrameIndex = frameIndex;
        }
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Temporal NR on the CFA. The interactive cache stays PRISTINE
        //    (temporal output never re-enters as input); the merge reads
        //    the center from the cache's shared buffer via the ring.
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, e.cachedCfa, e.cachedInfo,
            temporalNr == JNI_TRUE, temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        // 3-4. Develop the (possibly merged) CFA + warp + ARGB (shared
        //      preview tail; the warp is a no-op without a resolved log).
        return developToArgb(env, *cfa, e.cachedInfo, meta,
                             gradeFromKotlin(exposureEv, highlights, tempK, tint,
                                             saturation, blackPoint, whitePoint,
                                             shadows, midtones, contrast,
                                             asShot, highlightReconstruction,
                                             keepVignetting, sharpness, detail,
                                             tonemap, lutStrength,
                                             noiseReduction, chromaDenoise,
                                             temporalNr == JNI_TRUE,
                                             temporalStrength,
                                             wheelShadowX, wheelShadowY,
                                             wheelMidX, wheelMidY,
                                             wheelHighX, wheelHighY,
                                             splitShadowHue, splitHighlightHue,
                                             splitBalance),
                             lut, stab.input, maxDim);
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// RAW histogram stats (feature N6) ------------------------------------------------
//
// PRE-DEVELOP CFA statistics for the RAW histogram mode: one compact
// jintArray describing the sensor domain of one frame — what the sensor
// captured, BEFORE develop. The stats therefore do NOT move with
// exposure/WB/LUT/grade sliders (they act after the sensor data); the
// array changes only with the frame. The processed histogram
// (FrameHistogram on the rendered ARGB) keeps tracking the displayed
// result; the two clip domains are NEVER compared (owner request
// rgb-histogram-request.md line 21).
//
// jintArray LAYOUT (fixed, 787 ints):
//   [0]      = layout version (1 today; the Kotlin mapper rejects
//              anything else)
//   [1]      = frameIndex the stats describe (the stale guard: the
//              caller drops a result whose index is not the frame it
//              asked for)
//   [2]      = width,  [3] = height (the CFA dims, for reference)
//   [4..771] = bins: 3 channels x 256 bins, channel-major —
//              [4 + ch*256 + b] = R/G/B histogram count (ch 0=R, 1=G
//              with both green sites combined, 2=B; normalized to [0,1]
//              against that site's [black, white] range, exactly the
//              develop stage's stage-1 mapping). 4 + 3*256 = 772, so
//              the trailer starts at 772 — count it before packing.
//   [772..774] = RAW white-clip counts per channel (samples >= white)
//   [775..777] = RAW black-clip counts per channel (samples <= black)
//   [778..780] = samples per channel (the G count covers both green
//               sites; the clip thresholds' denominator)
//   [781..784] = the black levels actually used, 2x2 cell-slot order
//                (r, g, g, b) — the frame's dynamic levels when it
//                carried them, the container fallback otherwise
//   [785]     = the white level actually used
//   [786]     = reserved (0)
//
// CACHE REUSE: the decoded pristine CFA is never decoded twice — the
// entry's interactive cache (cachedCfa/cachedInfo/cachedFrameIndex)
// serves the frame when it matches, else the frame decodes through the
// ring exactly like the render path (loadRingFrame) and replaces the
// cache. The 16 MB CFA never crosses JNI; only this compact array does.
//
// THREADING: same single preview engine thread as
// nativePreviewSessionRenderCached — the Kotlin layer routes the call
// through the session's engine executor (EditorPreviewSession.rawHistogram),
// so the cache needs no locking beyond the registry's slot bookkeeping.
//
// Errors: the existing setError + null-return pattern (the Kotlin layer
// reads nativePreviewErrorCode/Stage/LastError). An invalid handle, a
// missing frame or a decode failure returns nullptr; the UI shows the
// RAW mode empty, never fabricated data.

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeRawHistogram(
        JNIEnv* env, jclass, jlong handle, jint frameIndex) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    if (handle <= 0 || handle > kMaxSources) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "invalid clip handle (recovery: reopen the clip)");
        return nullptr;
    }
    Entry& e = gEntries[handle - 1];
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    if (frameIndex < 0 ||
        static_cast<size_t>(frameIndex) >= s->frameCount()) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageFrameRead,
                 std::string("rawHistogram: frame index ") +
                     std::to_string(frameIndex) + " out of range (frameCount=" +
                     std::to_string(s->frameCount()) +
                     ", recovery: use the current frame index)");
        return nullptr;
    }
    try {
        // 1. The pristine CFA through the interactive cache — the same
        //    reuse the render path performs. A cache miss decodes and
        //    stores via the ring (never a second decode for the same
        //    frame). The cache holds PRISTINE frames (temporal merge
        //    output never re-enters as input), which is exactly the
        //    pre-develop sensor domain this feature reads.
        if (frameIndex != e.cachedFrameIndex) {
            const CfaBuffer buf = loadRingFrame(*s, e, frameIndex);
            if (buf == nullptr) return nullptr; // decode failed (error set)
            e.cachedCfa = buf;
            e.cachedInfo = s->frameInfo(static_cast<size_t>(frameIndex));
            e.cachedFrameIndex = frameIndex;
        }
        const rawedit::FrameInfo& info = e.cachedInfo;
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. The pure stats pass (core/src/RawStats.cpp; the level
        //    resolution mirrors Develop stage 1, -1 sentinels included).
        //    The container fallback levels widen uint16 -> int32 here
        //    (the core signature takes int32 so the -1 sentinel is in
        //    range).
        const int32_t blackFallback[4] = {
            static_cast<int32_t>(meta.black[0]),
            static_cast<int32_t>(meta.black[1]),
            static_cast<int32_t>(meta.black[2]),
            static_cast<int32_t>(meta.black[3]),
        };
        const rawedit::RawHistogramStats stats = rawedit::rawHistogramStats(
            e.cachedCfa->data(), info.width, info.height, info.cfa,
            info.dynamicBlack, blackFallback, info.dynamicWhite,
            static_cast<int32_t>(meta.white));

        // 3. Pack the compact array (layout above). The trailer offsets
        //    are derived, not restated: 4 header + 3*256 bins = 772, and
        //    each trailer block starts where the previous one ends.
        constexpr int kOut = 787;
        constexpr int kTrailer = 772; // 4 + 3 * 256
        std::vector<jint> out(kOut, 0);
        out[0] = 1; // layout version
        out[1] = frameIndex;
        out[2] = static_cast<jint>(info.width);
        out[3] = static_cast<jint>(info.height);
        for (int ch = 0; ch < 3; ++ch)
            for (int b = 0; b < rawedit::RawHistogramStats::kBins; ++b)
                out[4 + ch * 256 + b] = static_cast<jint>(stats.bins[ch][b]);
        for (int ch = 0; ch < 3; ++ch) out[kTrailer + ch] = stats.whiteClips[ch];
        for (int ch = 0; ch < 3; ++ch) out[kTrailer + 3 + ch] = stats.blackClips[ch];
        for (int ch = 0; ch < 3; ++ch) out[kTrailer + 6 + ch] = stats.samples[ch];
        for (int slot = 0; slot < 4; ++slot) out[kTrailer + 9 + slot] = stats.blackUsed[slot];
        out[kTrailer + 13] = stats.whiteUsed;
        out[kTrailer + 14] = 0; // reserved
        jintArray arr = env->NewIntArray(kOut);
        if (arr == nullptr) return nullptr;
        env->SetIntArrayRegion(arr, 0, kOut, out.data());
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "rawHistogram: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 std::string("rawHistogram: ") + e.what() +
                     " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewErrorCode(JNIEnv*, jclass) {
    return gLastErrorCode;
}

JNIEXPORT jint JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewErrorStage(JNIEnv*, jclass) {
    return gLastErrorStage;
}

// LUT probe (COL-07) -----------------------------------------------------------
//
// Stateless validation for the import flow: parses the .cube at path and
// returns its TITLE (a non-null jstring, possibly empty when the file has
// no TITLE line), or nullptr on a parse failure with the error state set
// — the Kotlin layer reads nativeLastError() for the cause. No handle, no
// cache: the render-path cache (gLut/gLutPath) is deliberately untouched,
// so probing never poisons a render and is safe to call from any thread
// (the screen probes on the import click; the render threads keep their
// own parsed tables).

JNIEXPORT jstring JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeLutProbe(
        JNIEnv* env, jclass, jstring path) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "lut: out of memory reading the path (recovery: free memory and retry)");
        return nullptr;
    }
    const std::string file(chars);
    env->ReleaseStringUTFChars(path, chars);
    if (file.empty()) {
        setError(static_cast<int>(rawedit::ErrorCode::BadHeader), kStageDevelop,
                 "lut: bad header — the path is empty "
                 "(recovery: pick a valid .cube file)");
        return nullptr;
    }
    auto parsed = rawedit::parseCubeFile(file);
    if (!parsed.isOk()) {
        setError(static_cast<int>(parsed.code), kStageDevelop,
                 std::string("lut: ") + rawedit::errorName(parsed.code) +
                     " — " + parsed.detail +
                     " (recovery: pick a valid .cube file)");
        return nullptr;
    }
    return toJavaString(env, parsed.value->title);
}

// Gyro sync probe (GYRO PHASE 5) ------------------------------------------------
//
// Stateless validation + status estimate for the .gcsv attach flow: opens a
// THROWAWAY Source from the clip's fd (Source::open dup's the fd, so the
// caller's handle is untouched), reads frame 0's sync anchors + the
// container's 35mm-equivalent focal length, parses the .gcsv at gyroPath,
// and runs estimateOffsetFromAnchors. Returns a double array:
//
//   [0] anchored flag (1.0 = Anchored, 0.0 = ManualRequired)
//   [1] offsetMs    (the estimate; 0 when manual-required)
//   [2] errorBoundMs (symmetric bound; 0 when unknown)
//   [3] focalLengthMm (container 35mm-equiv; 0 = absent)
//   [4] sampleCount (gyro samples; 0 when the parse yielded none)
//
// or nullptr on failure with the error state set (the Kotlin layer reads
// nativeLastError()). No handle, no cache, no render-path state touched —
// the screen probes on the attach click, so it is safe from any thread and
// never poisons a render (the nativeLutProbe contract).

JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeGyroSyncProbe(
        JNIEnv* env, jclass, jint fd, jstring gyroPath) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    const char* chars = env->GetStringUTFChars(gyroPath, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "gyro: out of memory reading the path (recovery: free memory and retry)");
        return nullptr;
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(gyroPath, chars);
    if (path.empty()) {
        setError(static_cast<int>(rawedit::ErrorCode::BadHeader), kStageOpen,
                 "gyro: bad header — the path is empty "
                 "(recovery: pick the clip's sidecar .gcsv)");
        return nullptr;
    }

    // 1. Throwaway Source from the dup'd fd: frame 0's anchors + focal.
    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("gyro: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail +
                     " (recovery: reopen the clip and retry)");
        return nullptr;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);
    const rawedit::FrameInfo first = src->frameInfo(0);
    const int64_t frame0WallMs = first.recvdMs;
    const int64_t frame0CaptureNs = first.ptsUs * 1000;
    const double focalMm = src->rawMeta().focalLengthMm;

    // 2. Parse the sidecar log.
    auto parsed = rawedit::parseGcsv(path);
    if (!parsed.isOk()) {
        setError(static_cast<int>(parsed.code), kStageOpen,
                 std::string("gyro: ") + rawedit::errorName(parsed.code) +
                     " — " + parsed.detail +
                     " (recovery: pick the clip's sidecar .gcsv)");
        return nullptr;
    }
    const rawedit::ImuData& imu = *parsed.value;
    const int64_t gyroStartWallMs = imu.logStartWallMs;
    const double samples = static_cast<double>(imu.sampleCount());

    // 3. Anchored estimate (ManualRequired — never fabricated — when either
    //    anchor is absent; the UI shows the manual path then).
    const rawedit::SyncEstimate est = rawedit::estimateOffsetFromAnchors(
        frame0WallMs, gyroStartWallMs, frame0CaptureNs);
    const double out[5] = {
        est.isAnchored() ? 1.0 : 0.0,
        est.model.offsetMs(),
        est.errorBoundMs,
        focalMm,
        samples,
    };
    jdoubleArray result = env->NewDoubleArray(5);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 5, out);
    return result;
}

// GLES capability probe + single-frame demosaic parity (owner request
// 2026-11-12, deliverable C) ---------------------------------------------------
//
// Both are STATELESS probes (the nativeGyroSyncProbe contract): no
// handle, no render-cache state, no session, safe from any thread; any
// fd is dup'd by Source::open so the caller's copy is untouched. The
// device entry point is the debug Measure harness (PreviewMetrics leg 4);
// nothing here changes a render decision on its own — the numbers feed
// BackendGates (engine/RenderBackend.kt) in a later deliverable.
//
// nativeGlesProbe: creates a throwaway GLES context (grafika EglCore
// topology, gles/GlesPipeline.cpp), reads what the device reports,
// releases the context, and returns
//
//   [0] contextOk      1 when the EGL context + pbuffer came up
//   [1] glesMajor      parsed from glGetString(GL_VERSION) (0 = unknown)
//   [2] glesMinor
//   [3] computeOk      ES 3.1+ context with a nonzero dispatch limit
//   [4] floatFboOk     GL_EXT_color_buffer_float advertised
//   [5] maxTextureSize
//
// A device with NO context is an honest report, not an error: the array
// returns with contextOk=0 and the failing EGL step in the error detail
// (read nativeLastError from the same thread). nullptr is reserved for
// JNI-level failure (error state set).

JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeGlesProbe(
        JNIEnv* env, jclass) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    std::string detail;
    const gles::Capabilities caps = gles::probeCapabilities(&detail);
    if (!caps.contextOk) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "gles probe: no context at " + detail +
                     " (recovery: the CPU render path stays the default)");
    }
    const double out[6] = {
        caps.contextOk ? 1.0 : 0.0,
        static_cast<double>(caps.glesMajor),
        static_cast<double>(caps.glesMinor),
        caps.computeOk ? 1.0 : 0.0,
        caps.floatFboOk ? 1.0 : 0.0,
        static_cast<double>(caps.maxTextureSize),
    };
    jdoubleArray result = env->NewDoubleArray(6);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 6, out);
    return result;
}

// nativeGlesParity: opens the clip behind [fd], decodes frame 0, and runs
// the CALLER-PROVIDED GLSL ES 3.10 compute source [computeSource] — the
// Kotlin single source of truth (GlesShaderSources.DEMOSAIC_COMPUTE; the
// device compiles the exact text the host validator checked) — on a
// throwaway GLES 3.1 context, against rawedit::developLinear: the DNG
// path's own reference, on the same frame. Returns
//
//   [0] ran           1 when the GPU pass produced real numbers
//   [1] maxDiffLsb    largest |gpu - cpu| in 16-bit LSB units
//   [2] mismatchCount samples whose 16-bit outputs differ
//   [3] samples       w * h * 3
//
// ran=0: a GPU stage failed (EGL, compile, dispatch, map) — the error
// detail carries the step or the compile log (nativeLastError). nullptr:
// clip-level failure (open/decode; error state set). The PASS VERDICT is
// Kotlin's (GpuParityRules) — this reports the raw numbers only, never a
// claim. Runtime behavior is device-test-pending: no phone has run it.

JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeGlesParity(
        JNIEnv* env, jclass, jint fd, jstring computeSource) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    const char* chars = env->GetStringUTFChars(computeSource, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "gles parity: out of memory reading the shader source "
                 "(recovery: free memory and retry)");
        return nullptr;
    }
    const std::string source(chars);
    env->ReleaseStringUTFChars(computeSource, chars);

    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("gles parity: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail +
                     " (recovery: reopen the clip and retry)");
        return nullptr;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);
    const rawedit::FrameInfo info = src->frameInfo(0);
    auto frame = src->readFrame(0);
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("gles parity: frame 0 decode — ") +
                     rawedit::errorName(frame.code) + " — " + frame.detail +
                     " (recovery: reopen the clip and retry)");
        return nullptr;
    }
    const gles::ParityReport rep =
        gles::demosaicParity(source, *frame.value, info, src->rawMeta());
    if (!rep.ran) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "gles parity: " + rep.detail +
                     " (recovery: the CPU render path stays the default)");
    }
    const double out[4] = {
        rep.ran ? 1.0 : 0.0,
        static_cast<double>(rep.maxDiffLsb),
        static_cast<double>(rep.mismatchCount),
        static_cast<double>(rep.samples),
    };
    jdoubleArray result = env->NewDoubleArray(4);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 4, out);
    return result;
}

// MP4 export pipeline ----------------------------------------------------------
//
// The exporter opens a session with nativePreviewOpen (same handle registry,
// same [handle, frameCount, w, h, cfa, orient] contract) and then calls
// nativeExportFrame per frame: full-res developCpu (NO thumbnail
// downscale) -> linearToRec709 -> clamp [0,1] -> quantize 8-bit RGB888.
// The clamp is inherent to 8-bit quantization (linearToRec709 itself is
// unclamped); values above 1.0 clip at 255, below 0 at 0. A 4K frame is
// ~25 MB of RGB — acceptable for the CPU reference path (a GPU path lands
// later). PTS comes from nativeFramePtsUs; audio description from
// nativeAudioInfo, samples from nativeReadAudio (all existing entry points
// except the two new ones below).

JNIEXPORT jbyteArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeExportFrame(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength,
        jfloat noiseReduction, jfloat chromaDenoise,
        jboolean temporalNr, jfloat temporalStrength,
        jfloat wheelShadowX, jfloat wheelShadowY,
        jfloat wheelMidX, jfloat wheelMidY,
        jfloat wheelHighX, jfloat wheelHighY,
        jfloat splitShadowHue, jfloat splitHighlightHue,
        jfloat splitBalance,
        jstring gyroPath, jint stabMode, jfloat stabStrength,
        jfloat stabZoomLimit, jfloat stabFocalMm, jfloat stabTrimMs) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // export frame with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 0b. Resolve the gyro stabilization input (gyro Phase 5): the
        //     Kotlin exporter passes the clip's gyro path when stabilization
        //     is on (export is NEVER tier-gated — the temporal NR
        //     precedent: export always applies at full quality); a .gcsv
        //     parse failure fails the frame; a null path warps nothing.
        const StabResolution stab =
            resolveStabilization(env, gyroPath, stabMode, stabStrength,
                                 stabZoomLimit, stabFocalMm, stabTrimMs,
                                 s, frameIndex);
        if (!stab.ok) return nullptr;

        // 1. Frame read — through the ring, like the preview paths (sequential
        //    export amortizes to ~1 decode per frame with temporal NR on).
        Entry& e = gEntries[handle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Temporal NR on the CFA (pristine buffer back when off).
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            temporalNr == JNI_TRUE, temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        // 3. Full-res develop with the job's grade snapshot (core/Develop.h).
        gLastErrorStage = kStageDevelop;
        rawedit::ImageF img = rawedit::developCpu(
            cfa->data(), info, meta,
            gradeFromKotlin(exposureEv, highlights, tempK, tint,
                            saturation, blackPoint, whitePoint,
                            shadows, midtones, contrast,
                            asShot, highlightReconstruction, keepVignetting,
                            sharpness, detail, tonemap, lutStrength,
                            noiseReduction, chromaDenoise,
                            temporalNr == JNI_TRUE, temporalStrength,
                            wheelShadowX, wheelShadowY,
                            wheelMidX, wheelMidY,
                            wheelHighX, wheelHighY,
                            splitShadowHue, splitHighlightHue,
                            splitBalance),
            lut);
        if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop: unsupported — the frame is too small to develop "
                     "(recovery: re-import the clip)");
            return nullptr;
        }

        // 3b. Full-res gyro warp on the STORED-ORIENTATION buffer (gyro
        //     Phase 5): measured 44.9832 ms/frame at 3840x2160 (commit b's
        //     warpFrame doctest) on top of the existing export frame time —
        //     acceptable for the offline CPU reference path (a GPU warp is
        //     the N4 follow-up). The upright rotation happens in Kotlin
        //     AFTER this return (RotationState.rotateRgb888), so warping
        //     the non-rotated buffer composes with it exactly like the
        //     preview path's pre-rotation placement. maxDim 0 = full res:
        //     the debug log line fires per export frame (settle-class).
        applyStabilization(stab.input, img, /*maxDim=*/0);

        // 3. linearToRec709 -> clamp -> 8-bit RGB888, row-major, 3 bytes
        //    per pixel, NOT rotated here: the Kotlin exporter rotates the
        //    pixels upright (RotationState.rotateRgb888) before crop/YUV,
        //    so the muxer's orientation hint stays 0 — the pixels are
        //    already upright.
        const jsize w = static_cast<jsize>(img.w);
        const jsize h = static_cast<jsize>(img.h);
        const jsize n = w * h * 3;
        std::vector<jbyte> out(static_cast<size_t>(n));
        for (jsize i = 0; i < w * h; ++i) {
            out[static_cast<size_t>(i) * 3 + 0] = static_cast<jbyte>(
                toByte(rawedit::linearToRec709(img.rgb[static_cast<size_t>(i) * 3 + 0])));
            out[static_cast<size_t>(i) * 3 + 1] = static_cast<jbyte>(
                toByte(rawedit::linearToRec709(img.rgb[static_cast<size_t>(i) * 3 + 1])));
            out[static_cast<size_t>(i) * 3 + 2] = static_cast<jbyte>(
                toByte(rawedit::linearToRec709(img.rgb[static_cast<size_t>(i) * 3 + 2])));
        }
        jbyteArray arr = env->NewByteArray(n);
        if (arr == nullptr) return nullptr;
        env->SetByteArrayRegion(arr, 0, n, out.data());
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeFramePtsUs(
        JNIEnv*, jclass, jlong handle, jint index) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return -1;
    // frameInfo returns a zeroed FrameInfo for out-of-range indices (no
    // throw), so this is always safe; the exporter only walks 0..frameCount.
    return static_cast<jlong>(s->frameInfo(static_cast<size_t>(index)).ptsUs);
}

// Stabilization zoom probe (audit fix 2026-11-12, Fix 5) -----------------------
//
// The zoom-limit honesty readout behind the Stabilization group's status
// line: evaluates the CLIP-WIDE plan for the six flat request params —
// the identical stabilizationFrameFor the renders run — and returns the
// plan's numbers WITHOUT warping anything:
//
//   [0] requiredZoom  (unclamped static-crop need, >= 1.0)
//   [1] appliedZoom   (after the user's zoomLimit clamp)
//   [2] statusInt     (frame 0's StabilizationStatus: Ok=0, Off=1, NoImu=2,
//                      Unsynced=3, LowOverlap=4, IntrinsicsAssumed=5 — the
//                      core Stabilization.h enum order; the Kotlin mirror
//                      documents the same list)
//
// or nullptr when there is no verdict to show: a bad handle (the
// nativeFramePtsUs silent-null precedent), no gyro log attached (null/empty
// path — resolveGyro's honest no-op), or a .gcsv parse failure (error state
// set, like a failing render — the next native call resets it per the
// entry-point convention). The UI shows NO readout on null — never a
// fabricated zoom.
//
// WHY THE PROBE IS CHEAP AND TIER-FREE: the plan evaluation is the same
// one the renders pay (~0.2 ms on the 4K fixture, PreviewTiers' measured
// table) and the zooms are BUFFER-INDEPENDENT — focalLengthPxFromEquivalent
// scales the pixel focal linearly with the buffer width and staticCropZoom
// is a dimensionless corner-coverage ratio, so evaluating at the clip's
// RECORDED CFA dims (frameInfo(0).width/height) yields the same
// required/applied zooms any preview tier or the full-res export would.
// One probe per settle covers every tier.
//
// THREADING (binding): like every session native call this MUST run on the
// session's engine thread — resolveGyro's gyro cache and the error state
// are thread_local. The Kotlin seam (PreviewSession.stabZoomProbe) confines
// it via engine.execute exactly like framePtsUs; the probe never runs from
// any other thread.

JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeStabZoomProbe(
        JNIEnv* env, jclass, jlong handle, jstring gyroPath,
        jint stabMode, jfloat stabStrength, jfloat stabZoomLimit,
        jfloat stabFocalMm, jfloat stabTrimMs) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr; // dead session: no verdict, silently

    // The gyro resolves exactly like a render's: the thread-local cache
    // (warm after the first render), false only on a parse failure.
    const GyroArtifacts* gyro = nullptr;
    if (!resolveGyro(env, gyroPath, &gyro)) return nullptr; // error set
    if (gyro == nullptr) return nullptr; // no log attached: no verdict

    // The clip's recorded CFA dims as the evaluation buffer: any dims give
    // the same zooms (the scale invariant above), so the source's own
    // dimensions are the honest choice. frameInfo is metadata-only — no
    // decode, no cache touch.
    const rawedit::FrameInfo info0 = s->frameInfo(0);
    const int bufW = info0.width;
    const int bufH = info0.height;

    // The full plan evaluation the renders run, frame 0, measure filled:
    // requiredZoom/appliedZoom are clip-wide (the static crop is one
    // number over the whole q_rel list), status is frame 0's own verdict.
    const StabRequest req = stabRequestOf(stabMode, stabStrength, stabZoomLimit,
                                          stabFocalMm, stabTrimMs);
    rawedit::StabilizationFrame frame;
    StabMeasure measure;
    stabilizationFrameFor(req, *s, /*frameIndex=*/0, bufW, bufH, gyro,
                          &frame, &measure);

    const double out[3] = {
        measure.requiredZoom,
        measure.appliedZoom,
        static_cast<double>(static_cast<int>(measure.status)),
    };
    jdoubleArray result = env->NewDoubleArray(3);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 3, out);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeAudioInfo(
        JNIEnv* env, jclass, jlong handle) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        auto info = s->audioInfo();
        if (!info.isOk()) {
            setError(static_cast<int>(info.code), kStageFrameRead,
                     std::string("audio info: ") + rawedit::errorName(info.code) +
                         " — " + info.detail);
            return nullptr;
        }
        jint out[2] = {static_cast<jint>(info.value->sampleRate),
                       static_cast<jint>(info.value->channels)};
        jintArray arr = env->NewIntArray(2);
        if (arr == nullptr) return nullptr;
        env->SetIntArrayRegion(arr, 0, 2, out);
        return arr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 std::string("audio info: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// DNG export -------------------------------------------------------------------
//
// One-shot: open the clip from fd, decode frame [frameIndex], write the raw
// CFA mosaic as a DNG 1.4 file at outPath (the Kotlin layer passes its
// out.partial staging path in app cache; writeCfaDng already removes a
// partially written file on failure). No grade fields: a CFA DNG is the raw
// mosaic — developCpu is not part of this path. The handle registry is not
// used (the Source lives only for this call), so the function is safe to
// call from any single engine thread.

// RAW-09 crop cut: the Kotlin layer passes the crop rect as fractions of
// the EFFECTIVE-upright frame (the space the user sees and crops in). The
// CFA pixels live in SOURCE space, and effective = source rotated CW by
// effectiveDeg (the same convention the DNG Orientation tag uses), so the
// fractions map through the rotation exactly like the JVM-tested
// CropState.mapRect: a rect in effective space becomes
//   90:  (y, 1-x-w, h, w)   180: (1-x-w, 1-y-h, w, h)
//   270: (1-y-h, x, h, w)   else: unchanged
// The mapped rect is then snapped to the 2x2 Bayer grid (even offsets and
// sizes keep the CFA pattern phase intact — the same contract as
// CropState.pixelRect(align = 2)) and the decoded CFA is cut to the window:
// a plain copy, never a resample. Returns true when the cut was applied;
// full-frame fractions leave the buffer untouched.
static bool dngCropCut(std::vector<uint16_t>& cfa, rawedit::FrameInfo& info,
                       int effectiveDeg,
                       float cropX, float cropY, float cropW, float cropH) {
    const bool full = cropX <= 0.0f && cropY <= 0.0f &&
                      cropW >= 1.0f && cropH >= 1.0f;
    if (full) return false;

    const int width = static_cast<int>(info.width);
    const int height = static_cast<int>(info.height);
    if (width <= 0 || height <= 0) return false;

    // Effective-space fractions -> source-space fractions (CropState.mapRect).
    int deg = ((effectiveDeg % 360) + 360) % 360;
    float fx = cropX, fy = cropY, fw = cropW, fh = cropH;
    switch (deg) {
        case 90:  fx = cropY; fy = 1.0f - cropX - cropW; fw = cropH; fh = cropW; break;
        case 180: fx = 1.0f - cropX - cropW; fy = 1.0f - cropY - cropH; fw = cropW; fh = cropH; break;
        case 270: fx = 1.0f - cropY - cropH; fy = cropX; fw = cropH; fh = cropW; break;
        default: break;
    }

    // Source-space pixel window on the 2x2 Bayer grid (mirrors
    // CropState.pixelRect with align = 2: round, align, clamp inside).
    int x = static_cast<int>(std::lround(fx * width));
    int y = static_cast<int>(std::lround(fy * height));
    int w = static_cast<int>(std::lround(fw * width));
    int h = static_cast<int>(std::lround(fh * height));
    x -= x % 2; y -= y % 2;
    w = std::max(w - w % 2, 2);
    h = std::max(h - h % 2, 2);
    if (x + w > width) x = std::max(width - w, 0);
    if (y + h > height) y = std::max(height - h, 0);
    w = std::min(w, width - x);
    h = std::min(h, height - y);
    if (w <= 0 || h <= 0) return false;

    // Plain window copy: the selected region at native resolution, never
    // stretched and never resampled (a raw mosaic must not be interpolated).
    std::vector<uint16_t> cut(static_cast<size_t>(w * h));
    for (int row = 0; row < h; ++row) {
        const uint16_t* srcRow = cfa.data() + static_cast<size_t>(y + row) * width + x;
        std::copy(srcRow, srcRow + w, cut.data() + static_cast<size_t>(row) * w);
    }
    cfa = std::move(cut);

    // The cut frame is a whole "sensor" of its own: the DNG's dimensions and
    // ActiveArea describe the cropped buffer from (0,0) — a reader must not
    // see the original frame geometry. The orientation tag keeps the
    // effective rotation (the reader still turns the pixels upright).
    info.width = static_cast<uint32_t>(w);
    info.height = static_cast<uint32_t>(h);
    info.activeArea = rawedit::Rect{0, 0, w, h};
    return true;
}

JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDngExport(
        JNIEnv* env, jclass, jint fd, jint frameIndex,
        jint manualRotationDeg,
        jfloat cropX, jfloat cropY, jfloat cropW, jfloat cropH,
        jstring outPath) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    const char* pathChars = env->GetStringUTFChars(outPath, nullptr);
    if (pathChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDngWrite,
                 "dng export: out of memory reading the output path "
                 "(recovery: free memory and retry)");
        return JNI_FALSE;
    }
    const std::string path(pathChars);
    env->ReleaseStringUTFChars(outPath, pathChars);

    // 1. Open the clip from the SAF fd (the bridge dups it; the caller's fd
    //    is untouched).
    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("open: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail);
        return JNI_FALSE;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);

    // 2. Decode the frame (the expensive step — seconds on a 4K frame).
    gLastErrorStage = kStageFrameRead;
    auto frame = src->readFrame(static_cast<size_t>(frameIndex));
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("frame read: ") + rawedit::errorName(frame.code) +
                     " — " + frame.detail);
        return JNI_FALSE;
    }
    // Section 5 (owner request 2026-11-11): the frame's orientationDeg is
    // the CONTAINER orientation (the per-frame override when the source
    // declares one — see Source.cpp frameInfo). The Kotlin layer snapshots
    // the user's manual rotation delta (job.manualRotationDeg) and passes
    // it here; the DNG's Orientation tag must carry the EFFECTIVE rotation
    // (container + manual) while the CFA itself stays unrotated — a DNG is
    // the raw mosaic, rotation belongs to the reader. The tag mapping lives
    // in DngWriter.cpp tiffOrientation (90->6, 180->3, 270->8, else 1) and
    // is mirrored in the JVM-tested data/RotationState.dngOrientationTag.
    rawedit::FrameInfo info = src->frameInfo(static_cast<size_t>(frameIndex));
    const int effectiveDeg =
        (((info.orientationDeg + manualRotationDeg) % 360) + 360) % 360;
    info.orientationDeg = effectiveDeg;
    const rawedit::RawMeta& meta = src->rawMeta();

    // 2b. RAW-09: cut the selected region from the decoded CFA before the
    //     write. The fractions arrive in effective-upright space; the cut
    //     maps them into source space, aligns to the 2x2 Bayer grid and
    //     copies the window — native resolution, never stretched. The
    //     straighten dial is deliberately NOT applied here: the raw mosaic
    //     must not be resampled (documented RAW-09 skip).
    dngCropCut(*frame.value, info, effectiveDeg,
               cropX, cropY, cropW, cropH);

    // 3. Write the CFA DNG to the staging path (writeCfaDng removes a
    //    partial file on failure, so the staging file exists only on
    //    success — the Kotlin layer renames it into place).
    gLastErrorStage = kStageDngWrite;
    rawedit::Result<void> written = rawedit::writeCfaDng(
        path, frame.value->data(), info, meta);
    if (!written.isOk()) {
        setError(static_cast<int>(written.code), kStageDngWrite,
                 std::string("dng write: ") + rawedit::errorName(written.code) +
                     " — " + written.detail);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Linear DNG export ---------------------------------------------------------------
//
// RAW-13 (owner spec section 3): the demosaiced, LINEAR scene-referred
// deliverable, a separate file contract from the CFA DNG above. Same
// one-shot shape as nativeDngExport (open from fd, decode frame, write to
// the staging path; no handle registry, safe from any single engine
// thread), with the minimal develop between read and write:
//   developLinear (Develop.cpp) = stage 1 black/white normalization
//   (frame-preferred levels) + stage 4 bilinear demosaic + stage 5 as-shot
//   white balance, nothing else — the linear output and the display tone
//   map are separate paths ("Görüntüleme ton eşlemesi ile lineer veri
//   çıkışı ayrı yollar"). The exact stage list is recorded in the file's
//   ImageDescription tag (270) by writeLinearDng.
//
// The metadata decisions (writeLinearDng): BlackLevel 0 / WhiteLevel 65535
// (stage 1 already mapped the sensor range to 0..1), AsShotNeutral
// (1,1,1) (the WB multiply is baked into the pixels — a reader must not
// double-apply), ColorMatrix1 kept as-is (the pixels stay in the
// camera-native primaries). Rotation: same contract as the CFA export —
// the Orientation tag carries container + manual while the pixels stay
// unrotated (rotation belongs to the reader).

JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeLinearDngExport(
        JNIEnv* env, jclass, jint fd, jint frameIndex,
        jint manualRotationDeg,
        jfloat cropX, jfloat cropY, jfloat cropW, jfloat cropH,
        jstring outPath) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    const char* pathChars = env->GetStringUTFChars(outPath, nullptr);
    if (pathChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDngWrite,
                 "linear dng export: out of memory reading the output path "
                 "(recovery: free memory and retry)");
        return JNI_FALSE;
    }
    const std::string path(pathChars);
    env->ReleaseStringUTFChars(outPath, pathChars);

    // 1. Open the clip from the SAF fd (the bridge dups it; the caller's fd
    //    is untouched).
    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("open: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail);
        return JNI_FALSE;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);

    // 2. Decode the frame (the expensive step — seconds on a 4K frame).
    gLastErrorStage = kStageFrameRead;
    auto frame = src->readFrame(static_cast<size_t>(frameIndex));
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("frame read: ") + rawedit::errorName(frame.code) +
                     " — " + frame.detail);
        return JNI_FALSE;
    }
    rawedit::FrameInfo info = src->frameInfo(static_cast<size_t>(frameIndex));
    // Same effective-rotation contract as the CFA export (owner request
    // 2026-11-11 section 5): the tag carries container + manual, the pixels
    // stay unrotated.
    const int effectiveDeg =
        (((info.orientationDeg + manualRotationDeg) % 360) + 360) % 360;
    info.orientationDeg = effectiveDeg;
    const rawedit::RawMeta& meta = src->rawMeta();

    // 2b. RAW-09: the crop cut runs BEFORE developLinear, so the demosaic
    //     works on the selected region only (a cut after the demosaic would
    //     leave the RGB buffer at full size). Same Bayer-aligned plain
    //     window copy as the CFA arm; straighten is a documented skip.
    dngCropCut(*frame.value, info, effectiveDeg,
               cropX, cropY, cropW, cropH);

    // 3. The minimal linear develop (stage 1 + demosaic + WB; the stage list
    //    is recorded in the file's ImageDescription). A camera failure here
    //    is a develop-stage failure, not a write failure.
    gLastErrorStage = kStageDevelop;
    const std::vector<uint16_t> rgb =
        rawedit::developLinear(frame.value->data(), info, meta);
    if (rgb.empty()) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported),
                 kStageDevelop,
                 "linear develop: empty frame (recovery: reopen the clip)");
        return JNI_FALSE;
    }

    // 4. Write the Linear DNG to the staging path (writeLinearDng removes a
    //    partial file on failure, so the staging file exists only on
    //    success — the Kotlin layer renames it into place).
    gLastErrorStage = kStageDngWrite;
    rawedit::Result<void> written = rawedit::writeLinearDng(
        path, rgb.data(), info, meta);
    if (!written.isOk()) {
        setError(static_cast<int>(written.code), kStageDngWrite,
                 std::string("linear dng write: ") +
                     rawedit::errorName(written.code) + " — " + written.detail);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Encoded (Mi-Log MP4) develop ---------------------------------------------------
//
// The encoded decode path (sprint plan Task 6): the Kotlin EncodedSource
// state machine decodes a frame via MediaCodec (MediaExtractor/MediaCodec
// adapters on the Kotlin side), packs it as NV12 (or I420), and hands it
// here. This entry develops it with the same preview tail as the MCRAW path —
// developEncoded (limited-range YUV -> RGB -> Mi-Log decode -> WB/temp/tint/
// exposure -> sharpness/detail/tonemap, the shared COL-15/COL-16 stages,
// then the optional stage-22 LUT, COL-07) ->
// linearToRec709 -> clamp -> 8-bit ARGB, rotated upright by the container's
// rotation hint — the same [width, height, then w*h ARGB_8888 ints] contract
// as nativePreviewRender, so the preview UI is identical for both source
// kinds. No handle registry: the frame is stateless input.
//
// The frame bytes arrive as three flat arrays (mirroring rawedit::YuvFrame):
//   semiPlanar true  = NV12: yPlane + interleaved UV in uPlane (vPlane unused)
//   semiPlanar false = I420: y, u, v planes
// stride/uvStride are bytes per luma/chroma row (0 = width / width/2). The
// develop uses tempK/tint/exposureEv of the grade plus the detail/tonemap
// stages (developEncoded's contract); the other grade fields travel anyway
// (flat-register style, one signature for every develop entry point).

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeEncodedDevelop(
        JNIEnv* env, jclass,
        jbyteArray yPlane, jbyteArray uPlane, jbyteArray vPlane,
        jint width, jint height, jint stride, jint uvStride,
        jboolean semiPlanar, jboolean isBt709, jint orientationDeg,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength,
        jfloat noiseReduction, jfloat chromaDenoise,
        jboolean temporalNr, jfloat temporalStrength,
        jfloat wheelShadowX, jfloat wheelShadowY,
        jfloat wheelMidX, jfloat wheelMidY,
        jfloat wheelHighX, jfloat wheelHighY,
        jfloat splitShadowHue, jfloat splitHighlightHue,
        jfloat splitBalance) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    // Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // develop with the lut error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    // Temporal NR (DN-05..DN-09) is a CFA-frame stage; the encoded path has
    // no CFA and no neighbor frames available at this seam, so the two
    // fields travel for signature parity (one signature across every
    // develop entry point) and are ignored here. developEncoded never reads
    // them; the encoded UI keeps the control MCRAW-gated. The color wheel /
    // split-tone fields (COL-13) travel the same way: tone-band stages are
    // MCRAW-only (the encoded path grades via its WB/temp/tint/exposure
    // loop), so they ride the signature for parity and are ignored here.

    if (width <= 0 || height <= 0 || yPlane == nullptr || uPlane == nullptr ||
        (semiPlanar == JNI_FALSE && vPlane == nullptr)) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the decoded frame is empty or malformed "
                 "(recovery: re-import the clip)");
        return nullptr;
    }

    // Copy the plane bytes (GetByteArrayElements may pin/copy; the release
    // mode below is abort for read-only use, so no writeback is expected).
    const jsize yLen = env->GetArrayLength(yPlane);
    const jsize uLen = env->GetArrayLength(uPlane);
    const jsize vLen = vPlane != nullptr ? env->GetArrayLength(vPlane) : 0;
    const int s = stride > 0 ? stride : width;
    const int us = uvStride > 0 ? uvStride : width / 2;

    rawedit::YuvFrame frame;
    frame.width = static_cast<uint32_t>(width);
    frame.height = static_cast<uint32_t>(height);
    frame.semiPlanar = semiPlanar != JNI_FALSE;
    frame.stride = s;
    frame.uvStride = us;

    // Size check before any copy: the develop indexes y up to
    // (height-1)*stride + width-1 and the chroma grid up to
    // (ch-1)*uvStride + (cw-1)*2 (+1 for the interleaved V byte).
    const long long cw = (static_cast<long long>(width) + 1) / 2;
    const long long ch = (static_cast<long long>(height) + 1) / 2;
    const long long yNeed = static_cast<long long>(height - 1) * s + width;
    const long long cNeed = frame.semiPlanar
        ? static_cast<long long>(ch - 1) * us + (cw - 1) * 2 + 2
        : static_cast<long long>(ch - 1) * us + cw;
    if (yLen < yNeed || uLen < cNeed ||
        (!frame.semiPlanar && (vLen < cNeed))) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the decoded frame's planes are smaller "
                 "than its dimensions (recovery: re-import the clip)");
        return nullptr;
    }

    {
        jbyte* yBytes = env->GetByteArrayElements(yPlane, nullptr);
        if (yBytes == nullptr) return nullptr;
        frame.y.assign(reinterpret_cast<const uint8_t*>(yBytes),
                       reinterpret_cast<const uint8_t*>(yBytes) + yLen);
        env->ReleaseByteArrayElements(yPlane, yBytes, JNI_ABORT);

        jbyte* uBytes = env->GetByteArrayElements(uPlane, nullptr);
        if (uBytes == nullptr) return nullptr;
        frame.u.assign(reinterpret_cast<const uint8_t*>(uBytes),
                       reinterpret_cast<const uint8_t*>(uBytes) + uLen);
        env->ReleaseByteArrayElements(uPlane, uBytes, JNI_ABORT);

        if (vPlane != nullptr) {
            jbyte* vBytes = env->GetByteArrayElements(vPlane, nullptr);
            if (vBytes == nullptr) return nullptr;
            frame.v.assign(reinterpret_cast<const uint8_t*>(vBytes),
                           reinterpret_cast<const uint8_t*>(vBytes) + vLen);
            env->ReleaseByteArrayElements(vPlane, vBytes, JNI_ABORT);
        }
    }

    try {
        // 1. developEncoded: limited-range YUV -> RGB -> Mi-Log decode ->
        //    WB/temp/tint/exposure -> sharpness/detail/tonemap
        //    (core/Transfer.h).
        rawedit::ImageF img = rawedit::developEncoded(
            frame,
            gradeFromKotlin(exposureEv, highlights, tempK, tint,
                            saturation, blackPoint, whitePoint,
                            shadows, midtones, contrast,
                            asShot, highlightReconstruction, keepVignetting,
                            sharpness, detail, tonemap, lutStrength,
                            noiseReduction, chromaDenoise,
                            temporalNr == JNI_TRUE, temporalStrength,
                            wheelShadowX, wheelShadowY,
                            wheelMidX, wheelMidY,
                            wheelHighX, wheelHighY,
                            splitShadowHue, splitHighlightHue,
                            splitBalance),
            isBt709 != JNI_FALSE, lut);
        if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop: unsupported — the frame is too small to develop "
                     "(recovery: re-import the clip)");
            return nullptr;
        }

        // 2. linearToRec709 -> clamp -> 8-bit ARGB, rotated upright (the
        //    same rotation math as developToArgb, so an encoded clip and an
        //    MCRAW clip of the same scene show identical orientation).
        const jsize w = static_cast<jsize>(img.w);
        const jsize h = static_cast<jsize>(img.h);
        const int orient = ((orientationDeg % 360) + 360) % 360;
        jsize outW = w;
        jsize outH = h;
        if (orient == 90 || orient == 270) {
            outW = h;
            outH = w;
        }
        const jsize n = outW * outH;
        std::vector<jint> out(static_cast<size_t>(n) + 2);
        out[0] = outW;
        out[1] = outH;
        jint* px = out.data() + 2;
        for (jsize y = 0; y < outH; ++y) {
            for (jsize x = 0; x < outW; ++x) {
                jsize si;
                switch (orient) {
                    case 90:  si = static_cast<jsize>(h - 1 - x) * w + y; break;
                    case 180: si = static_cast<jsize>(h - 1 - y) * w + (w - 1 - x); break;
                    case 270: si = x * w + (w - 1 - y); break;
                    default:  si = y * w + x; break;
                }
                const float r = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 0]);
                const float g = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 1]);
                const float b = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 2]);
                px[static_cast<size_t>(y) * outW + x] =
                    0xFF000000 |
                    (static_cast<jint>(toByte(r)) << 16) |
                    (static_cast<jint>(toByte(g)) << 8) |
                    static_cast<jint>(toByte(b));
            }
        }
        jintArray arr = env->NewIntArray(n + 2);
        if (arr == nullptr) return nullptr;
        env->SetIntArrayRegion(arr, 0, n + 2, out.data());
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory developing the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

} // extern "C"
