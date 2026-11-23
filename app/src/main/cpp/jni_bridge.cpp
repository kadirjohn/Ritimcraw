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
#include <chrono>
#include <cmath>
#include <cstdio>
#include <memory>
#include <mutex>
#include <new>
#include <map>
#include <optional>
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
#include <rawedit/OpticalSync.h>
#include <rawedit/Result.h>
#include <rawedit/RawStats.h>
#include <rawedit/Source.h>
#include <rawedit/Stabilization.h>
#include <thread>
#include <unistd.h>
#include <rawedit/Sync.h>
#include <rawedit/Temporal.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>
#include <rawedit/Warp.h>
#include <rawedit/Yuv.h>

#include "gles/GlesDevelopSession.h"
#include "gles/GradeParcel.h"
#include "gles/GlesPipeline.h"
#include "gles/GlesPreviewSession.h"
// Pure planner (A1 async stats, 2026-11-23): the statsMode clamp the
// render door's kill-switch fold uses — header-only, no GL.
#include "gles/StatsRing.h"

#include <android/native_window.h>
#include <android/native_window_jni.h>

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
// appliedZoom, the correction-scale numbers (audit fix 2026-11-13: the
// fraction of the correction actually applied when the zoom limit cannot
// cover the full need — scale/minScale/meanScale per frame/clip, plus the
// invalid-sample counter), IMU overlap, or the skip reason when nothing
// was applied.
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
//
// Second tag, "ritim-stab-timing" (2026-11-13, the plan-cache round): one
// line per RENDERED frame at EVERY tier —
//
//   ritim-stab-timing: frame=N planMs=..(cache-hit|computed) warpMs=.. totalMs=..
//
// planMs is the stabilizationFrameFor span (plan lookup or compute), warpMs
// the warpFrame span, totalMs the two combined. Unlike "ritim-stab" (which
// fires on settle-class renders only), this fires per render including
// playback — it is the line the owner's ON/OFF playback comparison reads:
// after the first frame of a key, planMs should collapse to a cache-hit
// rounding to ~0 while warpMs stays the real per-frame warp cost. Same
// gate (gStabDebugLog), same adb filter shape:
//
//   adb logcat -s ritim-stab-timing    (live)
//   adb logcat -d -s ritim-stab-timing (dump after a run)
std::atomic<bool> gStabDebugLog{false};

// Optical sync refinement toggle (M5 commit b, GY-04 exposure): a RUNTIME
// user toggle, unlike the two BuildConfig gates above — the UI's stab sheet
// owns it (nativeSetSyncRefine), the companion init never touches it, and
// the default stays FALSE until the owner's device A/B proves the refined
// offset reduces the vibration. When on, a stab plan whose sync estimate is
// Anchored runs rawedit::refineOffsetOptically ONCE per (handle, request
// key) inside the plan cache's miss branch — the refine is a ~seconds-class
// cost on the host and must never run per rendered frame. The toggle rides
// the StabRequest (stabRequestOf reads it here) so all three render paths
// and the export pick it up without widening the six flat JNI params.
// Stabilization round 3 (2026-11-23): ON by default. The anchored offset
// alone was measured 83-135 ms off on the owner's device (see
// kReceiveLagPriorMs), and a sync error that size turns the warp into a
// shake amplifier; the optical refine measured the true offset on every
// handheld clip tried, with 1-13 ms bounds.
std::atomic<bool> gSyncRefine{true};

// The measured frame-delivery lag prior for the anchored estimate
// (Sync.h estimateOffsetFromAnchors receiveLagMs): MotionCam Pro 5.0.9 on
// the Xiaomi 25010PN30G, optical sync vs anchor on six clips: 83, 119,
// 125, 129, 135 ms (+ a 64 ms fixture) late — mean ~112 ms. The prior
// recenters the anchor; the bound covers the observed spread. Device/app
// specific — the optical refine (default on) replaces it whenever it
// succeeds, and its search window is widened to cover a prior miss.
constexpr double kReceiveLagPriorMs = 110.0;
constexpr double kReceiveLagBoundMs = 50.0;
constexpr double kOpticalSearchRangeMs = 300.0;

// Preview stats-readback toggle (trace-dims fix 2026-11-14): the A/B
// instrument for the GPU preview's synchronous glMapBufferRange cost.
// DEFAULT TRUE = today's behavior (the stats pack publishes every frame);
// false makes the preview session skip draw 3 + the readback entirely and
// publish the honest 1x1 empty fallback — the zebra/scopes consumers
// degrade for the measurement run, and the ritim-trace line reads
// stats=off. Like gSyncRefine this is a RUNTIME toggle (jni wrapper, not
// the companion init): the owner's device A/B flips it between two
// playback runs of the 12MP clip; release builds never flip it, so the
// pack always publishes there. The flag rides the PreviewFrameRequest
// (copied below in nativePreviewSessionRenderFrameParcel) so the session
// reads it per render without a second native call.
std::atomic<bool> gPreviewStatsEnabled{true};

// Export sub-stage timing (commit (d), owner request 2026-11-13): the
// export frame's native wall time splits into its six internals so the
// owner's "native=472ms" sample line becomes
// "native=472ms(decode=.. temporal=.. develop=.. warp=.. oetf=.. copy=..)".
// Collection is gated by the SAME BuildConfig.DEBUG flag as gStabDebugLog
// (set at library load) — a release build reads one relaxed atomic per
// branch and collects nothing. The slots are thread-local: the export
// engine owns one thread, and the getter is read from that thread right
// after the call (the error accessors' thread contract).
struct ExportFrameTiming {
    double decodeMs = 0;
    double temporalMs = 0;
    double developMs = 0;
    double warpMs = 0;
    double oetfPackMs = 0;
    double jniCopyMs = 0;
};
std::atomic<bool> gExportTiming{false};
thread_local ExportFrameTiming gExportFrameTiming;

// Milliseconds between two steady_clock points — the only arithmetic the
// sub-stage boundaries do (kept out of nativeExportFrameParcel's body).
inline double elapsedMs(const std::chrono::steady_clock::time_point& a,
                        const std::chrono::steady_clock::time_point& b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

// Microseconds between two steady_clock points — the preview spans are
// ms-scale or below, so the trace-dims fix (2026-11-14) carries them at
// microsecond resolution through the jint array tail (the GlesPreviewSession
// twin of the same helper).
inline double elapsedUs(const std::chrono::steady_clock::time_point& a,
                        const std::chrono::steady_clock::time_point& b) {
    return std::chrono::duration<double, std::micro>(b - a).count();
}

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

// Managed LUT binding cache (owner request 2026-11-13) ----------------------
//
// The gLut pattern mirrored for the compiled interpretation: compiling a
// binding (gamut-conversion matrices + transfer curves) is cheap next to
// parsing a cube, but it is per-frame work when done inline, so each engine
// thread keeps ONE compiled binding keyed by the SAME key the parsed table
// uses (the path) plus the interpretation's ordinal tuple — the Kotlin
// LutInterpK.signature() mirrored verbatim. Same key = reuse; any change =
// recompile; all-five -1 = no binding (the legacy stage-24 pair, the old
// look byte-identical).
//
// The binding is compiled FROM the ordinals the Kotlin side passes straight
// through (the rawedit enum ordinals — LutInterpK's values are pinned to the
// C++ order by LutInterpTest), so the bridge holds no lookup table and a
// future C++ enum extension can never wedge an older UI into a wrong role:
// an ordinal the core does not know falls back to the legacy interpretation
// (compileLutBinding clamps), never a silent mis-render.
struct BindingKey {
    std::string path;
    int role = -1;
    int inGamut = -1;
    int inTransfer = -1;
    int outGamut = -1;
    int outTransfer = -1;

    bool matches(const std::string& p, int r, int ig, int it, int og, int ot) const {
        return role == r && inGamut == ig && inTransfer == it &&
               outGamut == og && outTransfer == ot && path == p;
    }
};

thread_local BindingKey gBindingKey;
thread_local rawedit::CompiledLutBinding gBinding;

// Resolves the Kotlin lutRole..lutOutTransfer arguments into a compiled
// binding for this render. Returns true with *out == nullptr when there is
// no managed binding (all five -1, or lutRole alone -1 — a malformed call)
// or when no LUT is resolved (the caller passes the resolveLut result: no
// table means the grade develops through the legacy pair, which a null
// binding reproduces byte-identically). Always true: a binding resolution
// cannot fail — an out-of-range ordinal is clamped by compileLutBinding to
// the legacy interpretation, and the table itself was already resolved.
void resolveBinding(
    const rawedit::Lut* lut,
    int role, int inGamut, int inTransfer, int outGamut, int outTransfer,
    const rawedit::CompiledLutBinding** out) {
    *out = nullptr;
    if (lut == nullptr || role < 0) return;
    // The default key (role = -1) can never match a live call (role >= 0
    // here), so the first call always compiles.
    if (gBindingKey.matches(gLutPath, role, inGamut, inTransfer, outGamut, outTransfer)) {
        *out = &gBinding;
        return;
    }
    rawedit::LutInterpretation interp;
    interp.role = static_cast<rawedit::LutRole>(role);
    interp.input.gamut = static_cast<rawedit::GamutId>(inGamut);
    interp.input.transfer = static_cast<rawedit::TransferId>(inTransfer);
    interp.output.gamut = static_cast<rawedit::GamutId>(outGamut);
    interp.output.transfer = static_cast<rawedit::TransferId>(outTransfer);
    gBinding = rawedit::compileLutBinding(interp);
    gBindingKey = BindingKey{gLutPath, role, inGamut, inTransfer, outGamut, outTransfer};
    *out = &gBinding;
}

// Gyro stabilization cache (gyro Phase 5) ------------------------------------------------
//
// The gLut pattern mirrored for the sidecar .gcsv: parsing the log + running
// the VQF orientation filter is expensive, so each engine thread keeps ONE
// resolved artifact set keyed by path — same path = reuse, different path =
// reparse, null/empty path = no stabilization. The artifacts hold only the
// path-invariant work (parsed ImuData, axis-remapped sample streams, VQF
// orientation series); the clip-wide stabilization plan is NOT here — it
// depends on the request's mode/strength/focal/trim, the warp buffer's dims
// and the Source's frame timestamps, so it caches per HANDLE in the
// session's Entry (StabPlanCache below), keyed on exactly that tuple. The
// old per-render recompute was the choppy-playback bug: a slider step
// recomputes once per step now, and playback between steps is all cache
// hits — the churn argument that kept the plan out of a cache held only
// for drags, never for playback. [path] is the parse identity that plan
// key borrows: the artifacts cached under a path are the ones every plan
// built from that same path used.
struct GyroArtifacts {
    std::string path;                        // parse identity (the plan cache key)
    rawedit::ImuData imu;                    // parsed log (logStartWallMs anchor)
    std::vector<rawedit::ImuSample> gyro;   // canonical camera frame
    std::vector<rawedit::ImuSample> accel;  // canonical camera frame
    rawedit::VqfOrientation orientation;     // VQF series + camera gravity
    // Round 3: the bias-corrected PURE gyro integration (VQF's final bias
    // estimate subtracted, no accelerometer tilt corrections). Smooth and
    // LockFirstFrame only need relative rotation, and the VQF series
    // carries accel-driven tilt corrections that are not camera motion —
    // measured: a 5-23 deg start-up convergence swing on most real clips
    // (required crop up to 128x) and an accel-correction wobble during
    // walking. Horizon lock keeps the VQF series (it needs gravity).
    rawedit::OrientationSeries gyroOnly;
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
    {
        std::vector<rawedit::ImuSample> unbiased = artifacts->gyro;
        for (rawedit::ImuSample& s : unbiased) {
            s.x -= static_cast<float>(artifacts->orientation.gyroBias[0]);
            s.y -= static_cast<float>(artifacts->orientation.gyroBias[1]);
            s.z -= static_cast<float>(artifacts->orientation.gyroBias[2]);
        }
        artifacts->gyroOnly = rawedit::integrateGyro(unbiased);
    }
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
// estimate. syncRefine is the M5 commit-b optical-refinement flag: it rides
// the REQUEST (not the six flat JNI params) so the cache key, the refine
// gate and the export path all see it without any signature widening.
struct StabRequest {
    rawedit::StabilizationMode mode = rawedit::StabilizationMode::Smooth;
    double strength = 0.5;
    double zoomLimit = 1.3;
    double focalMmOverride = 0.0;
    double trimMs = 0.0;
    bool syncRefine = false;
    // Round 3: preview paths run the optical refine on a background thread
    // and render with the (lag-prior) anchor until it lands; export paths
    // leave this false and wait for it (every exported frame must ride the
    // same offset). Not a plan-cache key: both converge to the same plan.
    bool asyncRefine = false;
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
    r.syncRefine = gSyncRefine.load(std::memory_order_relaxed);
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
// the per-frame slice at the rendered index. The correction-scale fields
// (audit fix 2026-11-13) carry the crop-budget honesty readout: the
// fraction of the correction actually applied — per rendered frame
// (frameScale), the clip-wide constant baseline (clipScale) and the
// effective-reduction min/mean over the plan (minScale/meanScale), plus
// the invalidSampleFrames counter (frames whose final warp still samples
// outside the source — expected 0; any other value is a bug, never a
// silently clamped edge). Filled by stabilizationFrameFor next to the
// frame it returns; logged by applyStabilization when gStabDebugLog is on.
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
    double frameScale = 1.0;     // correctionScale at the rendered frame
    double clipScale = 1.0;      // clip-wide constant baseline a
    double minScale = 1.0;       // effective reduction, worst frame
    double meanScale = 1.0;     // effective reduction, clip mean
    size_t invalidSamples = 0;   // final-warp out-of-source frames (exp. 0)
    // Sync verdict (M5 commit b): which offset the plan actually rode, and
    // its numbers for the timing line + the zoom probe. 0 = manual trim /
    // no anchor (the pre-refine spelling), 1 = the anchored estimate, 2 =
    // the optically refined offset (only when syncRefine ran and the core
    // accepted it — a rejected refine keeps 1 and the anchor's offset).
    int syncKind = 0;
    double syncOffsetMs = 0.0;  // the offset the plan rode, ms
    double syncBoundMs = 0.0;   // the estimate's error bound, ms
};

// The syncKind spelling for the timing line + the probe contract (M5
// commit b). One source for the token so the log line, the applied line's
// trailing verdict and the Kotlin mirror can never drift apart.
inline const char* stabSyncKindName(int kind) {
    return kind == 2 ? "refined" : kind == 1 ? "anchored" : "manual";
}

// 3x3 inverse through the adjugate — a DOCUMENTED COPY of rawedit::matInv
// (core/src/Warp.cpp; the same copy note Stabilization.cpp's own helper
// carries: the core helper is anonymous-namespace, so it is transcribed
// here verbatim rather than exposed through an internal header for one
// caller). Returns false when the matrix is numerically singular; out is
// untouched then. Used by the GPU warp resolver (nativeStabWarpResolve)
// to compose M = Hinv * S without calling into warpFrame itself.
bool stabMatInv(const double m[9], double out[9]) {
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

// Forward declaration: the refine helper below shares the preview's
// thumbnail-stride rule, which is defined with the develop pipeline below.
int strideFor(const rawedit::FrameInfo& info, int maxDim);

// The optical sync refinement (M5 commit b, GY-04): decodes a bounded,
// evenly-spaced frame subset, develops each at the ~480px thumbnail tier
// with a NEUTRAL grade (the sync measurement must not depend on the user's
// grade — a default Grade runs exactly the linear stages 1+2+3+4+5), and
// hands the frames + the RAW orientation series to the core refinement.
// The core splits WHAT IT IS GIVEN into thirds, so the cap is the full
// window budget: 3 windows x maxPairsPerWindow(24) pairs = 75 frames.
// NEVER a render failure: any decode/develop refusal returns the anchor
// estimate untouched (the honest skip — the plan rides the anchored
// offset, exactly the pre-M5 behavior), and the core's own rejections
// (weak texture, no curvature improvement, windows disagreeing, unsorted
// stamps, non-positive focal) come back not-refined through
// applyOpticalRefinement, which also keeps the anchor. The refine cost is
// the reason it runs ONCE per plan-cache key, never per frame.
// A frame's representative time (round 3): the content is centered at
// capture + 0.5*exposure (+ 0.5*readout — not in the container; a constant
// the optical sync absorbs). Frames without an exposure key fall back to
// the documented half-interval. The optical refine and the plan MUST use
// this same formation (OpticalSync.h's mapping contract).
int64_t repTimeNs(const rawedit::FrameInfo& info, int64_t frameIntervalNs) {
    const int64_t capture = info.ptsUs * 1000;
    return capture + (info.exposureNs > 0 ? info.exposureNs / 2 : frameIntervalNs / 2);
}

rawedit::SyncEstimate refineSyncEstimate(rawedit::Source& src,
                                         const rawedit::OrientationSeries& series,
                                         const rawedit::SyncEstimate& anchor,
                                         int64_t frameIntervalNs,
                                         double focalMm) {
    constexpr size_t kRefineMaxFrames = 75;
    const size_t frames = src.frameCount();
    if (frames < 2) return anchor;

    const size_t count = std::min(frames, kRefineMaxFrames);
    const rawedit::FrameInfo info0 = src.frameInfo(0);
    const int strideEvery = strideFor(info0, 480);
    const rawedit::RawMeta meta = src.rawMeta();
    const rawedit::Grade grade{}; // neutral: stages 1+2+3+4+5 only

    std::vector<rawedit::OpticalSyncFrame> syncFrames;
    syncFrames.reserve(count);
    for (size_t k = 0; k < count; ++k) {
        // Evenly spaced over the clip: strictly increasing indices (the
        // core requires time-ordered frames), whole-clip coverage for the
        // thirds the core will split.
        const size_t idx = (k * (frames - 1)) / (count - 1);
        auto cfa = src.readFrame(idx);
        if (!cfa.isOk()) return anchor; // honest skip, never a failure
        const rawedit::FrameInfo info = src.frameInfo(idx);
        rawedit::ImageF img = rawedit::developThumbnail(
            cfa.value->data(), info, meta, grade, strideEvery,
            /*lut=*/nullptr, /*binding=*/nullptr);
        if (img.w == 0 || img.h == 0) return anchor;
        rawedit::OpticalSyncFrame f;
        f.captureNs = info.ptsUs * 1000;
        // The documented representative-time fallback (the container
        // carries no exposure/readout keys): capture + half the uniform
        // interval — the SAME formation the plan's mapping-contract
        // composition below uses, as OpticalSync.h requires.
        f.repTimeNs = repTimeNs(info, frameIntervalNs);
        f.image = std::move(img);
        syncFrames.push_back(std::move(f));
    }

    rawedit::OpticalSyncIntrinsics intrinsics;
    intrinsics.focalLengthPx = rawedit::focalLengthPxFromEquivalent(
        focalMm, static_cast<int>(syncFrames[0].image.w));
    rawedit::OpticalSyncOpts opts;
    opts.searchRangeMs = kOpticalSearchRangeMs;
    opts.repTimeSource = "capture+0.5*exposure (frame exposureTime; "
                         "capture+0.5*interval when absent)";
    const rawedit::OpticalSyncResult result = rawedit::refineOffsetOptically(
        anchor, syncFrames, series, intrinsics, opts);
    return rawedit::applyOpticalRefinement(anchor, result);
}

// The clip-wide stabilization plan cache (the choppy-playback fix,
// 2026-11-13). The plan — sync estimate + the whole-clip frameTs walk +
// computeStabilization — is a pure function of (request, buffer dims,
// gyro artifacts, the Source's frame series), NONE of which changes frame
// to frame during playback. The old code recomputed all of it per
// DISPLAYED frame: a 576-frame clip paid 576 metadata JSON parses (the
// frameTs walk) + a full clip plan per rendered frame — the measured
// choppy-with-stab smoking gun (stabilization OFF never enters this
// path, hence OFF = smooth).
//
// KEY (what invalidates): mode, strength, zoomLimit, focalMmOverride,
// trimMs (the StabRequest — a slider step changes exactly one of these
// and recomputes ONCE per step, not per frame), bufW/bufH (the pixel
// focal derives from the buffer width, so a tier change is a different
// plan), the gyro artifacts' PATH (the parse identity: a re-attached
// log is a different series even at the same settings) and frameCount
// (the frame series identity — a different clip is a different Source,
// and the cache lives inside the clip's own Entry, so a reopen starts
// empty by construction). Sync inputs need no key slots of their own:
// the frame-0 anchors come from THIS Source and the wall-clock anchor
// from the SAME gyro artifacts the path key already pins — EXCEPT the
// M5 syncRefine flag, which IS a key slot: the toggle changes the offset
// the plan rode (anchored vs optically refined), so flipping it must
// invalidate the plan and re-run the once-per-key refine.
//
// VALUE: the StabilizationResult (per-frame homographies + zooms + the
// angle list + the correction-scale fields of the 2026-11-13 audit fix —
// correctionScale per frame, clipCorrectionScale, min/mean
// correctionScale, invalidSampleFrames; all of them plan OUTPUT, so they
// ride the result wholesale and the cache key needs no new slots: the
// scale is a pure function of the key's own inputs) plus the plan-level
// measure numbers that are not per-frame slices (anchored / maxAngleDeg /
// framesInside / the sync verdict trio), so a cache hit serves the
// per-frame tail AND the debug measure line identically to a fresh
// compute. The frameTs vector and the sync model are NOT stored: they are
// compute inputs only (the measure path's framesToImu overlap is folded
// into framesInside at store time). ~61 KB for a 576-frame clip —
// trivial next to the Entry's CFA caches.
//
// THREADING: the cache lives in the session's Entry, whose access
// contract is the owner session's engine thread only (identical to
// cachedCfa/ring/mafdByPairEnd above/below); the registry mutex guards
// slot bookkeeping, not per-render reads. The export path opens its OWN
// handle (a fresh Entry) so preview and export never share a cache.
struct StabPlanSlot {
    // Key.
    rawedit::StabilizationMode mode = rawedit::StabilizationMode::Smooth;
    double strength = 0.5;
    double zoomLimit = 1.3;
    double focalMmOverride = 0.0;
    double trimMs = 0.0;
    bool syncRefine = false;
    int bufW = 0;
    int bufH = 0;
    std::string gyroPath;
    size_t frameCount = 0;
    // Value.
    bool valid = false;
    rawedit::StabilizationResult result{};
    bool anchored = false;
    double maxAngleDeg = 0.0;
    size_t framesInside = 0;
    int syncKind = 0;          // 0 manual, 1 anchored, 2 refined (StabMeasure)
    double syncOffsetMs = 0.0;
    double syncBoundMs = 0.0;
};

// A background optical refine (round 3). Owns everything it touches; the
// Entry may close while it runs (the result is then simply dropped).
struct RefineJob {
    std::atomic<bool> done{false};
    rawedit::SyncEstimate result{};  // written before done (release)
    // Key it was launched for (a toggle/focal/log change ignores it).
    std::string gyroPath;
    size_t frameCount = 0;
    double focalMm = 0.0;
};

// Round 3 stutter fix (2026-11-23): the preview flips buffer dims on every
// play/pause/drag (1280 settle, 640 drag, 1280 playback, full-res
// follow-up), and the single slot keyed on bufW/bufH rebuilt the WHOLE plan
// on each flip — measured 2.3-2.6 s per rebuild on device once the optical
// refine became the default, for the SAME offset every time. Now:
//  - a small round-robin set of plan slots (one per recent dims/settings),
//  - the sync estimate cached separately (it depends on the clip, the log,
//    the refine flag and the focal — never on the buffer dims), with the
//    optical refine on a background thread for preview paths,
//  - the frames' representative times cached (one metadata JSON parse per
//    frame per clip, not per plan).
struct StabPlanCache {
    static constexpr int kSlots = 4;
    StabPlanSlot slots[kSlots];
    int next = 0;

    bool syncValid = false;
    bool syncProvisional = false;  // anchor in use, a refine is pending
    std::string syncGyroPath;
    size_t syncFrameCount = 0;
    bool syncRefine = false;
    double syncFocalMm = 0.0;
    rawedit::SyncEstimate syncEst{};
    int64_t syncIntervalNs = 0;
    std::shared_ptr<RefineJob> refineJob;

    std::vector<int64_t> repTimes;

    void invalidatePlans() {
        for (StabPlanSlot& c : slots) c.valid = false;
    }
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
// [planCache], when non-null, is the handle's Entry cache (see
// StabPlanCache): a key match serves the plan without the frameTs walk or
// computeStabilization; a mismatch recomputes ONCE and stores. Null keeps
// the old always-compute behavior for callers without an Entry.
// [measure], when non-null, receives the debug line's numbers (Fix 4 of the
// 2026-11-12 audit): status/zooms/overlap as computed for THIS render —
// no extra work, the plan is already in hand.
// [fromCache], when non-null, reports whether the plan came from the cache
// (the debug timing line's cache-hit|computed split).
void stabilizationFrameFor(const StabRequest& req,
                          rawedit::Source& src,
                          int frameIndex,
                          int bufW, int bufH,
                          const GyroArtifacts* gyro,
                          rawedit::StabilizationFrame* out,
                          StabMeasure* measure = nullptr,
                          StabPlanCache* planCache = nullptr,
                          bool* fromCache = nullptr) {
    *out = rawedit::StabilizationFrame(); // identity + Off until proven
    if (measure != nullptr) {
        measure->anchored = false;
        measure->syncKind = 0;
        measure->syncOffsetMs = 0.0;
        measure->syncBoundMs = 0.0;
    }
    if (fromCache != nullptr) *fromCache = false;
    if (gyro == nullptr || bufW <= 0 || bufH <= 0) return;

    // Cache lookup: the key is the full request + buffer + gyro identity
    // (StabPlanCache above). A hit skips the sync estimate, the frameTs
    // walk AND computeStabilization — everything up to the per-frame
    // tail, which is the only part that varies frame to frame.
    // A finished background refine lands here (one atomic load per
    // frame): it replaces the provisional anchor and drops the plans built
    // on it, so the next lookup rebuilds — cheaply, sync and frame times
    // are cached — on the refined offset.
    if (planCache != nullptr && planCache->refineJob &&
        planCache->refineJob->done.load(std::memory_order_acquire)) {
        const std::shared_ptr<RefineJob> job = std::move(planCache->refineJob);
        planCache->refineJob.reset();
        if (planCache->syncValid && planCache->syncProvisional &&
            job->gyroPath == planCache->syncGyroPath &&
            job->frameCount == planCache->syncFrameCount &&
            job->focalMm == planCache->syncFocalMm && planCache->syncRefine) {
            planCache->syncEst = job->result;
            planCache->syncProvisional = false;
            planCache->invalidatePlans();
        }
    }

    StabPlanSlot* slot = nullptr;
    if (planCache != nullptr) {
        for (StabPlanSlot& c : planCache->slots) {
            if (c.valid &&
                c.mode == req.mode &&
                c.strength == req.strength &&
                c.zoomLimit == req.zoomLimit &&
                c.focalMmOverride == req.focalMmOverride &&
                c.trimMs == req.trimMs &&
                c.syncRefine == req.syncRefine &&
                c.bufW == bufW && c.bufH == bufH &&
                c.gyroPath == gyro->path &&
                c.frameCount == src.frameCount()) {
                slot = &c;
                break;
            }
        }
    }
    // A blocking (export) caller never rides a provisional anchor.
    if (slot != nullptr && !req.asyncRefine && planCache->syncProvisional) {
        slot = nullptr;
    }
    const bool hit = slot != nullptr;
    if (hit && fromCache != nullptr) *fromCache = true;

    // The plan in hand, whichever way it was obtained. A local keeps the
    // no-cache (legacy) path alive; with a cache the pointer pins the
    // Entry's copy so a store below is move-free.
    rawedit::StabilizationResult local;
    const rawedit::StabilizationResult* plan = nullptr;
    bool anchored = false;
    double maxAngleDeg = 0.0;
    size_t framesInside = 0;
    int syncKind = 0;
    double syncOffsetMs = 0.0;
    double syncBoundMs = 0.0;

    if (hit) {
        plan = &slot->result;
        anchored = slot->anchored;
        maxAngleDeg = slot->maxAngleDeg;
        framesInside = slot->framesInside;
        syncKind = slot->syncKind;
        syncOffsetMs = slot->syncOffsetMs;
        syncBoundMs = slot->syncBoundMs;
    } else {
        // Focal: the grade's override wins; 0 = the container's 35mm-equiv
        // value. Resolved FIRST so the optical refine below uses the same
        // focal the plan will (the refine's intrinsics and the plan's
        // pixel focal must be the same 35mm-equivalence assumption).
        // focalLengthPxFromEquivalent is the one documented, FLAGGED
        // fallback (f_px = width * mm / 36) — assumedFocal below wires
        // the IntrinsicsAssumed status so the assumption is visible,
        // never silent.
        const double focalMm = req.focalMmOverride > 0.0
            ? req.focalMmOverride
            : src.rawMeta().focalLengthMm;

        // Sync — cached per clip/log/refine-flag/focal (never per buffer
        // dims). The anchor is instant; the optical refine is seconds-class:
        // preview callers launch it on a background thread and ride the
        // lag-prior anchor meanwhile (the plan is rebuilt when it lands),
        // export callers wait for it.
        rawedit::SyncEstimate est;
        int64_t frameIntervalNs = 0;
        const bool syncHit = planCache != nullptr && planCache->syncValid &&
            planCache->syncGyroPath == gyro->path &&
            planCache->syncFrameCount == src.frameCount() &&
            planCache->syncRefine == req.syncRefine &&
            planCache->syncFocalMm == focalMm &&
            (req.asyncRefine || !planCache->syncProvisional);
        if (syncHit) {
            est = planCache->syncEst;
            frameIntervalNs = planCache->syncIntervalNs;
        } else {
            const rawedit::FrameInfo first = src.frameInfo(0);
            est = rawedit::estimateOffsetFromAnchors(
                first.recvdMs, gyro->imu.logStartWallMs, first.ptsUs * 1000,
                kReceiveLagPriorMs, kReceiveLagBoundMs);
            const size_t frames = src.frameCount();
            if (frames >= 2) {
                const int64_t firstTs = first.ptsUs * 1000;
                const int64_t lastTs = src.frameInfo(frames - 1).ptsUs * 1000;
                if (lastTs > firstTs) {
                    frameIntervalNs =
                        (lastTs - firstTs) / static_cast<int64_t>(frames - 1);
                }
            }
            bool provisional = false;
            if (req.syncRefine && est.isAnchored() && frameIntervalNs > 0) {
                // The refine measures against the bias-corrected gyro-only
                // series (round 3): with VQF's accel corrections in the
                // series it was rejected on two of four real handheld clips
                // that gyro-only synced.
                const int bgFd = req.asyncRefine && planCache != nullptr
                    ? src.reopenIndependentFd() : -1;
                if (bgFd >= 0) {
                    auto job = std::make_shared<RefineJob>();
                    job->gyroPath = gyro->path;
                    job->frameCount = frames;
                    job->focalMm = focalMm;
                    const rawedit::SyncEstimate anchorCopy = est;
                    const rawedit::OrientationSeries seriesCopy = gyro->gyroOnly;
                    const int64_t interval = frameIntervalNs;
                    const double fmm = focalMm;
                    std::thread([job, bgFd, anchorCopy, seriesCopy, interval, fmm]() {
                        rawedit::SyncEstimate out = anchorCopy;
                        auto opened = rawedit::Source::open(bgFd);
                        ::close(bgFd);
                        if (opened.isOk()) {
                            out = refineSyncEstimate(**opened.value, seriesCopy,
                                                     anchorCopy, interval, fmm);
                        }
                        job->result = out;
                        job->done.store(true, std::memory_order_release);
                    }).detach();
                    planCache->refineJob = job;
                    provisional = true;
                } else {
                    est = refineSyncEstimate(src, gyro->gyroOnly,
                                             est, frameIntervalNs, focalMm);
                }
            }
            if (planCache != nullptr) {
                planCache->syncValid = true;
                planCache->syncProvisional = provisional;
                planCache->syncGyroPath = gyro->path;
                planCache->syncFrameCount = src.frameCount();
                planCache->syncRefine = req.syncRefine;
                planCache->syncFocalMm = focalMm;
                planCache->syncEst = est;
                planCache->syncIntervalNs = frameIntervalNs;
                planCache->invalidatePlans();  // new sync -> old plans stale
            }
        }

        rawedit::SyncModel model = est.model;
        bool offsetKnown = false;
        if (est.confidence == rawedit::SyncConfidence::Refined) {
            // MAPPING CONTRACT (OpticalSync.h, binding): refinedOffsetNs
            // maps t_imu = repTimeNs + refinedOffsetNs. Since round 3 the
            // plan's frameTs ARE those representative times (repTimeNs()
            // above, shared with the refine), so no half-interval shift
            // is composed in. The trim composes MANUALLY over it (the core
            // trim helper would trim the anchor's model, which a refined
            // estimate never touched).
            // Round 3: frameTs below ARE the representative times the
            // refine measured against, so the refined offset applies as is.
            offsetKnown = true;
            model.offsetNs = est.refinedOffsetNs +
                             static_cast<int64_t>(
                                 req.trimMs * 1000000.0);
        } else if (est.isAnchored()) {
            offsetKnown = true;
            if (req.trimMs != 0.0) {
                model = rawedit::trimOffsetMs(est, req.trimMs).model;
            }
        } else if (req.trimMs != 0.0) {
            offsetKnown = true;
            model = rawedit::manualOffsetMs(req.trimMs).model;
        }
        // else: unanchored + no trim -> offsetKnown stays false (Unsynced).
        anchored = est.isAnchored() ||
                   est.confidence == rawedit::SyncConfidence::Refined;
        syncBoundMs = est.errorBoundMs;
        syncOffsetMs = static_cast<double>(model.offsetNs) / 1000000.0;
        syncKind = est.confidence == rawedit::SyncConfidence::Refined ? 2
                 : est.isAnchored() ? 1 : 0;

        // Focal: resolved above the sync block so the refine shares it.
        const double focalPx = rawedit::focalLengthPxFromEquivalent(
            focalMm, bufW);

        rawedit::StabilizationParams params;
        params.mode = req.mode;
        params.strength = req.strength;
        params.zoomLimit = req.zoomLimit;
        params.focalLengthPx = focalPx;
        params.width = bufW;
        params.height = bufH;
        // principalPoint keeps the {0,0} sentinel -> the buffer center;
        // the warp's cx/cy must match that resolution exactly
        // (width/2, height/2).

        // Frame timestamps: the develop's frame clock (ptsUs) for the
        // whole clip — frameInfo is metadata-only, so this walk adds no
        // decode, but each call parses the frame's metadata JSON, which
        // is exactly why the plan is cached now (once per key change,
        // not once per displayed frame).
        const size_t frames = src.frameCount();
        // Representative times (capture + 0.5*exposure — round 3): the
        // instant each frame's content was seen, the same formation the
        // optical refine used.
        std::vector<int64_t> frameTs;
        if (planCache != nullptr && planCache->repTimes.size() == frames) {
            frameTs = planCache->repTimes;
        } else {
            frameTs.resize(frames);
            for (size_t i = 0; i < frames; ++i) {
                frameTs[i] = repTimeNs(src.frameInfo(i), frameIntervalNs);
            }
            if (planCache != nullptr) planCache->repTimes = frameTs;
        }

        // Smooth / LockFirstFrame ride the gyro-only series; HorizonLock
        // needs the VQF series (its gravityCamera pairs with VQF's last
        // quaternion).
        const rawedit::OrientationSeries& stabSeries =
            req.mode == rawedit::StabilizationMode::HorizonLock || gyro->gyroOnly.empty()
                ? gyro->orientation.series
                : gyro->gyroOnly;
        rawedit::StabilizationResult computed = rawedit::computeStabilization(
            stabSeries, model, frameTs,
            gyro->orientation.gravityCamera, offsetKnown,
            /*assumedFocal=*/true, params);
        for (double a : computed.relativeAngleDeg)
            maxAngleDeg = std::fmax(maxAngleDeg, a);
        // IMU overlap: the same framesToImu the plan's LowOverlap flags
        // come from — frames mapped inside the gyro span (samples in the
        // window). Cheap: one pass over the already-mapped timestamps,
        // and folded into the cache so a hit never re-walks it.
        const rawedit::FrameSync overlap = rawedit::framesToImu(
            frameTs, model, gyro->orientation.series.firstTs(),
            gyro->orientation.series.lastTs());
        framesInside = overlap.frameCount - overlap.beforeGyroStart -
                       overlap.afterGyroEnd;

        if (planCache != nullptr) {
            StabPlanSlot& dst = planCache->slots[planCache->next];
            planCache->next = (planCache->next + 1) % StabPlanCache::kSlots;
            dst.mode = req.mode;
            dst.strength = req.strength;
            dst.zoomLimit = req.zoomLimit;
            dst.focalMmOverride = req.focalMmOverride;
            dst.trimMs = req.trimMs;
            dst.syncRefine = req.syncRefine;
            dst.bufW = bufW;
            dst.bufH = bufH;
            dst.gyroPath = gyro->path;
            dst.frameCount = frames;
            dst.valid = true;
            dst.anchored = anchored;
            dst.maxAngleDeg = maxAngleDeg;
            dst.framesInside = framesInside;
            dst.syncKind = syncKind;
            dst.syncOffsetMs = syncOffsetMs;
            dst.syncBoundMs = syncBoundMs;
            dst.result = std::move(computed);
            plan = &dst.result;
        } else {
            local = std::move(computed);
            plan = &local;
        }
    }

    // Per-frame tail: identical for a cache hit and a fresh compute —
    // the plan is in hand either way.
    if (frameIndex >= 0 &&
        static_cast<size_t>(frameIndex) < plan->frames.size()) {
        *out = plan->frames[static_cast<size_t>(frameIndex)];
    }
    if (measure != nullptr) {
        measure->status = out->status;
        measure->requiredZoom = plan->requiredZoom;
        measure->appliedZoom = plan->appliedZoom;
        measure->framesTotal = plan->frames.size();
        if (static_cast<size_t>(frameIndex) <
            plan->relativeAngleDeg.size()) {
            measure->frameAngleDeg = plan->relativeAngleDeg[
                static_cast<size_t>(std::max(0, frameIndex))];
        }
        measure->maxAngleDeg = maxAngleDeg;
        measure->framesInside = framesInside;
        measure->anchored = anchored;
        measure->syncKind = syncKind;
        measure->syncOffsetMs = syncOffsetMs;
        measure->syncBoundMs = syncBoundMs;
        // Correction-scale honesty (audit fix 2026-11-13): the numbers ride
        // the plan wholesale (cache value or fresh compute — the fields are
        // StabilizationResult members, so a cache hit serves them without
        // any extra walk). The refusing plans zero them in the core, so a
        // skip line never carries a fabricated scale; a plan inside budget
        // carries all-1.0 scales and the reduction is visible exactly when
        // the budget demanded it.
        measure->clipScale = plan->clipCorrectionScale;
        measure->minScale = plan->minCorrectionScale;
        measure->meanScale = plan->meanCorrectionScale;
        measure->invalidSamples = plan->invalidSampleFrames;
        if (static_cast<size_t>(frameIndex) <
            plan->correctionScale.size()) {
            measure->frameScale = plan->correctionScale[
                static_cast<size_t>(std::max(0, frameIndex))];
        }
    }
}

// The warp input for one render (gyro Phase 5). gyro == nullptr is the
// no-warp default: the Kotlin layer already gated off / no-attachment down
// to a null gyroPath (the tier gate passes at every tier since the
// 2026-11-12 audit fix), and a log that went missing since the attach
// probe degrades the same way — so a default StabInput warps nothing and
// costs nothing. planCache is the handle's Entry plan cache (nullptr for
// callers without an Entry — the legacy always-compute behavior): it is
// resolved here, once per render, so applyStabilization's call into
// stabilizationFrameFor carries it.
struct StabInput {
    StabRequest request;
    rawedit::Source* source = nullptr;
    int frameIndex = -1;
    const GyroArtifacts* gyro = nullptr;
    StabPlanCache* planCache = nullptr;
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
// lutStrength precedent). [planCache] is the handle's Entry cache,
// forwarded into the input (nullptr = the legacy uncached path).
StabResolution resolveStabilization(JNIEnv* env, jstring gyroPath,
                                    jint stabMode, jfloat stabStrength,
                                    jfloat stabZoomLimit, jfloat stabFocalMm,
                                    jfloat stabTrimMs,
                                    rawedit::Source* src, int frameIndex,
                                    StabPlanCache* planCache = nullptr,
                                    bool asyncRefine = false) {
    StabResolution r;
    const GyroArtifacts* gyro = nullptr;
    if (!resolveGyro(env, gyroPath, &gyro)) {
        r.ok = false;
        return r;
    }
    if (gyro == nullptr) return r; // off / no log: the no-warp default
    r.input.request = stabRequestOf(stabMode, stabStrength, stabZoomLimit,
                                    stabFocalMm, stabTrimMs);
    r.input.request.asyncRefine = asyncRefine;
    r.input.source = src;
    r.input.frameIndex = frameIndex;
    r.input.gyro = gyro;
    r.input.planCache = planCache;
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
// the full-res follow-up). It gates ONLY the "ritim-stab" line (audit fix
// 2026-11-12): the warp itself applies at EVERY tier (the Fix 1 rule), but
// that log fires on settle-class renders only — the paused/settle tier
// (>= 1280) and full res — so a slider drag or playback does not spam one
// line per frame. One line per settle render, tag "ritim-stab". The
// "ritim-stab-timing" line (2026-11-13) is NOT tier-gated: it fires per
// rendered frame at every tier, because the owner's ON/OFF playback
// comparison happens during playback, not at the settle.
void applyStabilization(const StabInput& in, rawedit::ImageF& img,
                        int maxDim = 0) {
    if (in.gyro == nullptr || in.source == nullptr) return;
    if (img.w == 0 || img.h == 0) return;
    const bool debug = gStabDebugLog.load(std::memory_order_relaxed);
    const bool wantLog = debug && maxDim != 480 && maxDim != 720;
    rawedit::StabilizationFrame frame;
    StabMeasure measure;
    bool planFromCache = false;
    const auto planStart = std::chrono::steady_clock::now();
    stabilizationFrameFor(in.request, *in.source, in.frameIndex,
                          static_cast<int>(img.w), static_cast<int>(img.h),
                          in.gyro, &frame, debug ? &measure : nullptr,
                          in.planCache, debug ? &planFromCache : nullptr);
    const auto planEnd = std::chrono::steady_clock::now();
    if (wantLog) {
        // 320: the applied line's worst case (long status name, 6-digit
        // frame/overlap counts) is ~244 chars; the margin keeps the
        // correction-scale fields from ever truncating mid-number.
        char line[320];
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
            // scale/minScale/meanScale: the correction actually applied as
            // a fraction of the full correction (audit fix 2026-11-13).
            // 1.000 everywhere = the budget covered the full correction;
            // below 1.0 = the correction was honestly reduced to fit the
            // zoom limit (the alternative was clamped edge pixels). The
            // owner's device re-test reads exactly these: at high strength
            // with the limit raised, scale=1.000 and no edge tearing; at a
            // tight limit, scale<1 and the correction visibly reduced.
            std::snprintf(line, sizeof line,
                "applied: mode=%d strength=%.2f frame=%d angle=%.3fdeg "
                "maxAngle=%.3fdeg requiredZoom=%.4f appliedZoom=%.4f "
                "scale=%.4f minScale=%.4f meanScale=%.4f invalid=%zu "
                "imuOverlap=%zu/%zu status=%s %s",
                static_cast<int>(in.request.mode), in.request.strength,
                in.frameIndex, measure.frameAngleDeg, measure.maxAngleDeg,
                measure.requiredZoom, measure.appliedZoom,
                measure.frameScale, measure.minScale, measure.meanScale,
                measure.invalidSamples,
                measure.framesInside, measure.framesTotal,
                rawedit::stabilizationStatusName(frame.status),
                measure.syncKind == 2
                    ? "refined"
                    : measure.anchored ? "anchored" : "manual-trim");
        }
        __android_log_print(ANDROID_LOG_INFO, "ritim-stab", "%s", line);
    }
    if (!stabWarps(frame)) {
        if (debug) {
            // A refusing frame (Off / NoImu / Unsynced) still pays the plan
            // span; the warp is what it skipped. Same line shape as the
            // applied case — a skip simply reads warpMs=0.000 — so the
            // owner's parser sees one uniform format per rendered frame.
            // The sync token (M5 commit b) is APPEND-ONLY: a pre-M5 log
            // parser reading the first five fields keeps working, and the
            // token names which offset the plan rode.
            const double planMs = std::chrono::duration<double, std::milli>(
                planEnd - planStart).count();
            __android_log_print(ANDROID_LOG_INFO, "ritim-stab-timing",
                "frame=%d planMs=%.3f(%s) warpMs=0.000 totalMs=%.3f "
                "sync=%s offsetMs=%.3f backend=cpu",
                in.frameIndex, planMs,
                planFromCache ? "cache-hit" : "computed", planMs,
                stabSyncKindName(measure.syncKind), measure.syncOffsetMs);
        }
        return;
    }
    // cx/cy resolve the principalPoint sentinel exactly the way the core
    // plan resolves it ({0,0} sentinel -> the buffer center): the warp and
    // the plan must agree or the crop drifts off the intended center.
    const double cx = static_cast<double>(img.w) / 2.0;
    const double cy = static_cast<double>(img.h) / 2.0;
    const auto warpStart = std::chrono::steady_clock::now();
    img = rawedit::warpFrame(img, frame.h, frame.zoom, cx, cy);
    const auto warpEnd = std::chrono::steady_clock::now();
    if (debug) {
        const double planMs = std::chrono::duration<double, std::milli>(
            planEnd - planStart).count();
        const double warpMs = std::chrono::duration<double, std::milli>(
            warpEnd - warpStart).count();
        // The sync token (M5 commit b) is APPEND-ONLY: the first five
        // fields keep their pre-M5 shape and the appended trio names which
        // offset the plan rode — the owner's ON/OFF A/B reads
        // sync=refined vs sync=anchored directly off this line. The
        // backend marker (the GPU warp port's commit b) is appended the
        // same way: backend=cpu here, backend=gpu on the resolver's line,
        // so the A/B parser tells the two apart without a format change.
        __android_log_print(ANDROID_LOG_INFO, "ritim-stab-timing",
            "frame=%d planMs=%.3f(%s) warpMs=%.3f totalMs=%.3f "
            "sync=%s offsetMs=%.3f backend=cpu",
            in.frameIndex, planMs,
            planFromCache ? "cache-hit" : "computed", warpMs,
            planMs + warpMs,
            stabSyncKindName(measure.syncKind), measure.syncOffsetMs);
    }
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
    // Stabilization plan cache (the choppy-playback fix, 2026-11-13):
    // the clip-wide plan — sync estimate + frameTs walk +
    // computeStabilization — is a pure function of the request + buffer
    // dims + gyro artifacts + this Source's frame series, so it computes
    // ONCE per key change instead of once per displayed frame (the old
    // per-render recompute was the choppy-with-stab bug; a 576-frame clip
    // paid 576 metadata JSON parses per frame). Same threading contract
    // as the caches above: owner session's engine thread only. See
    // StabPlanCache for the key/invalidation rules.
    StabPlanCache stabPlan;
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
    e.stabPlan = StabPlanCache{}; // a reopened handle starts planless
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
                         const rawedit::CompiledLutBinding* binding,
                         const StabInput& stab,
                         int maxDim) {
    const int strideEvery = strideFor(info, maxDim);
    rawedit::ImageF img = rawedit::developThumbnail(
        cfa.data(), info, meta, grade, strideEvery, lut, binding);
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

// GPU develop session registry (M3 commit a) ---------------------------------
//
// The production seam's whole native side. The Kotlin dispatcher (commit b)
// opens ONE session per export job when the grade is fragment-covered and
// renderBackend=GPU_EXPERIMENTAL, renders every frame through it, then
// closes it. The registry mirrors the clip handle registry's shape: a fixed
// slot array + a mutex for slot bookkeeping ONLY. The session pointer itself
// is confined to the export engine thread (the same thread that calls
// nativeDevelopSessionOpen / RenderFrame / Close — Source's own threading
// rule: one Source belongs to one thread, and the session is opened and
// served from that same thread), so the slot read after open is unguarded
// exactly like sourceFor's returned pointer.
//
// A session is NOT tied to a clip handle on purpose: the session owns only
// GL state (programs/textures/FBOs keyed by resolution + the shading map);
// the clip's bytes flow through it per frame. One session could serve two
// clips back-to-back; the registry keeps slots anyway so a leaked session
// (an export job that never closed) is visible in the slot array, not
// silent. Slots are never reused while occupied — the fixed size bounds the
// leak to kMaxDevelopSessions.
constexpr int kMaxDevelopSessions = 4;
std::unique_ptr<gles::DevelopSession> gDevelopSessions[kMaxDevelopSessions];

// GPU preview session registry (M4 commit a) ----------------------------------
//
// Same registry shape as the develop sessions above (fixed slot array +
// mutex for slot bookkeeping ONLY), for the preview counterpart of the
// M3 seam: the editor opens ONE session per clip preview while
// renderBackend=GPU_EXPERIMENTAL (the Kotlin gate, commit c, decides),
// attaches the SurfaceView's window to it, renders the preview-tier
// frames through it, and closes it on teardown. The session pointer is
// confined to the engine thread that opened it — the same
// one-thread-serves-the-session rule the clip registry rides (the GL
// work marshals onto the session's own GL thread inside renderFrame;
// setSurface likewise). A session is NOT tied to a clip handle on
// purpose (the session owns only GL state; the clip's bytes flow
// through it per frame). Slots are never reused while occupied — the
// fixed size bounds the leak surface to kMaxPreviewSessions.
constexpr int kMaxPreviewSessions = 4;
std::unique_ptr<gles::PreviewSession> gPreviewSessions[kMaxPreviewSessions];

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
// the "ritim-stab" per-settle-render line and the "ritim-stab-timing"
// per-render timing line exist only in debug builds — release keeps the
// flag's default false and stays silent.
JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeSetStabDebugLog(
        JNIEnv*, jclass, jboolean enabled) {
    gStabDebugLog.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

// Export sub-stage timing toggle (commit (d)): same companion-init pattern
// as nativeSetStabDebugLog — the gate flips on only in debug builds, and a
// release build reads it once per boundary as a relaxed load.
JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeSetExportTiming(
        JNIEnv*, jclass, jboolean enabled) {
    gExportTiming.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

// Optical sync refinement toggle (M5 commit b): UNLIKE the two gates above
// this is a RUNTIME user toggle — the stab sheet's sync-refine switch owns
// it (jniSetSyncRefine), not the companion init. Default FALSE: the owner
// A/Bs the refined offset on device before it becomes a default. When on,
// stabRequestOf copies it onto every stab request and the plan cache's
// miss branch runs the once-per-key optical refinement.
JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeSetSyncRefine(
        JNIEnv*, jclass, jboolean enabled) {
    gSyncRefine.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

// Preview stats-readback toggle (trace-dims fix 2026-11-14): the same
// store-relaxed pattern as the three toggles above, but a RUNTIME one —
// the Kotlin jniSetPreviewStatsEnabled wrapper owns it (the
// jniSetSyncRefine precedent), not the companion init. Default TRUE (the
// atomic's initializer) = today's behavior; the owner's device A/B flips
// it between two playback runs.
JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeSetPreviewStatsEnabled(
        JNIEnv*, jclass, jboolean enabled) {
    gPreviewStatsEnabled.store(enabled == JNI_TRUE, std::memory_order_relaxed);
}

// Window probe gate (white-window round 2, 2026-11-15): the DEBUG-ONLY
// one-shot readback of the GPU preview's window pass — the pragmatic
// one-pixel exception to that path's no-readback rule. The gate's atomic
// lives in GlesPreviewSession.cpp (next to its consumer, the window
// pass); this setter is the gStabDebugLog precedent — NativeSource's
// companion init calls it once with BuildConfig.DEBUG at library load,
// so release builds keep the native default (off) and the window pass
// reads one relaxed load per draw, never a pixel. The probe's two
// logcat lines ("window probe: ..." under the ritim-trace tag) are the
// owner's replay instrument for the aspect-dependent white window (the
// WIDE clips paint white, the 4:3 healthy; zoom-proof; stats-healthy).
JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeSetPreviewWindowProbe(
        JNIEnv*, jclass, jboolean enabled) {
    gles::setWindowProbeEnabled(enabled == JNI_TRUE);
}

// The system monitor's GPU reading: the app's cumulative preview GPU time
// (ns) from the preview session's TIME_ELAPSED queries, or -1 when no
// timer-capable preview session is live. Any thread.
JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewGpuNanos(JNIEnv*, jclass) {
    return static_cast<jlong>(gles::previewGpuNanosTotal());
}

// The six sub-stage slots of the frame nativeExportFrameParcel just
// produced, in the fixed order the Kotlin side renders:
//   [decode, temporal, develop, warp, oetf_pack, jni_copy] (milliseconds).
// Thread-local like the error accessors: call on the export thread right
// after a successful nativeExportFrameParcel — that is the only writer. Null when
// the gate is off (nothing was collected); the Kotlin side treats that as
// "no sub-stage data" and keeps the plain "native=..ms" line.
JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeExportFrameTimingMs(
        JNIEnv* env, jclass) {
    if (!gExportTiming.load(std::memory_order_relaxed)) return nullptr;
    const ExportFrameTiming& t = gExportFrameTiming;
    const double slots[6] = {t.decodeMs, t.temporalMs, t.developMs,
                             t.warpMs, t.oetfPackMs, t.jniCopyMs};
    jdoubleArray out = env->NewDoubleArray(6);
    if (out == nullptr) return nullptr;
    env->SetDoubleArrayRegion(out, 0, 6, slots);
    return out;
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
        jint maxDim,
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // render with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    // 0a. Resolve the managed binding (2026-11-13): all five -1 = the
    //     legacy stage-24 pair, byte-identical.
    const rawedit::CompiledLutBinding* binding = nullptr;
    resolveBinding(lut, lutRole, lutInGamut, lutInTransfer,
                   lutOutGamut, lutOutTransfer, &binding);
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 0b. Resolve the gyro stabilization input (gyro Phase 5): a .gcsv
        //     parse failure fails the render; a null path (off / tier skip /
        //     no attachment — the Kotlin gate) warps nothing. The handle's
        //     Entry carries the plan cache (the 2026-11-13 perf fix): the
        //     clip-wide plan computes once per key change, not per frame.
        Entry& e = gEntries[handle - 1];
        const StabResolution stab =
            resolveStabilization(env, gyroPath, stabMode, stabStrength,
                                 stabZoomLimit, stabFocalMm, stabTrimMs,
                                 s, frameIndex, &e.stabPlan,
                                 /*asyncRefine=*/true);
        if (!stab.ok) return nullptr;

        // 1. Frame read — through the ring, so with temporal NR on the
        //    center joins its neighbors in one LRU (the merge reads them
        //    all back from it; with NR off the ring is cleared by
        //    temporalMergedCfa and this was a plain decode).
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
                             lut, binding, stab.input, maxDim);
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
        jint maxDim,
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // render with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    // 0a. Resolve the managed binding (2026-11-13): all five -1 = the
    //     legacy stage-24 pair, byte-identical.
    const rawedit::CompiledLutBinding* binding = nullptr;
    resolveBinding(lut, lutRole, lutInGamut, lutInTransfer,
                   lutOutGamut, lutOutTransfer, &binding);
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
        //     no attachment — the Kotlin gate) warps nothing. The handle's
        //     Entry carries the plan cache (the 2026-11-13 perf fix).
        const StabResolution stab =
            resolveStabilization(env, gyroPath, stabMode, stabStrength,
                                 stabZoomLimit, stabFocalMm, stabTrimMs,
                                 s, frameIndex, &e.stabPlan,
                                 /*asyncRefine=*/true);
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
                             lut, binding, stab.input, maxDim);
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
        frame0WallMs, gyroStartWallMs, frame0CaptureNs,
        kReceiveLagPriorMs, kReceiveLagBoundMs);
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

// Container identity probe (IN-13 metadata extraction) --------------------------
//
// STATELESS (the nativeGyroSyncProbe contract): no handle, no cache, no
// render-path state; the fd is dup'd inside Source::open so the caller's
// copy is untouched; safe from any thread (the import flow probes right
// after a copy, off the render path). Opens a throwaway Source and reads
// the container's camera/lens identity keys — the fields no develop or
// export consumer touches, so this probe is their ONLY exposure (the
// mission's "add a native function only if a field is genuinely
// unreachable" rule: every other IN-13 fact reuses an existing seam).
//
// Returns a ContainerIdentityK with the container's own spellings:
//   uniqueCameraModel, deviceModel (deviceSpecificProfile.deviceModel),
//   manufacturer + buildModel (extraData.postProcessSettings.metadata
//   "build.manufacturer"/"build.model"), apertureMm (apertures[0]),
//   focalLengthMm (RawMeta's 35mm-equivalent; 0 = absent) — absent keys
//   arrive as ""/0, and the Kotlin layer maps that to UNKNOWN, never a
//   guess. nullptr = the open itself failed (error state set).

JNIEXPORT jobject JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeContainerIdentity(
        JNIEnv* env, jclass, jint fd) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("identity: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail +
                     " (recovery: reopen the clip and retry)");
        return nullptr;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);
    const rawedit::ContainerIdentity& id = src->containerIdentity();

    jclass cls = env->FindClass("com/ritim/ritimcraw/engine/ContainerIdentityK");
    if (cls == nullptr) return nullptr;
    jmethodID ctor = env->GetMethodID(
        cls, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;"
        "Ljava/lang/String;DD)V");
    if (ctor == nullptr) return nullptr;
    auto str = [&](const std::string& s) -> jstring {
        return env->NewStringUTF(s.c_str());
    };
    const jstring ucm = str(id.uniqueCameraModel);
    const jstring dm = str(id.deviceModel);
    const jstring man = str(id.manufacturer);
    const jstring bm = str(id.buildModel);
    if (ucm == nullptr || dm == nullptr || man == nullptr || bm == nullptr)
        return nullptr;
    return env->NewObject(
        cls, ctor,
        ucm, dm, man, bm,
        static_cast<jdouble>(id.aperture),
        static_cast<jdouble>(src->rawMeta().focalLengthMm));
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

// nativeGlesFragmentParity (deliverable D): the FRAGMENT develop parity —
// the first REAL GPU render path. Opens the clip behind [fd] (dup'd),
// decodes frame 0, and runs the CALLER-PROVIDED four-shader fragment chain
// (the Kotlin single source of truth: FULLSCREEN_VERTEX,
// FULLSCREEN_VERTEX_310, DEVELOP_FRAGMENT, STORE_FRAGMENT — the device
// compiles the exact text the host validator checked):
//
//   GL_R16UI CFA texture -> fused develop fragment pass (stages 1+2+3+4+5
//   behind uniform gates: uHasProfile=0, uExposure=1.0, uOetf=0) into an
//   RGBA32F attachment (EXT_color_buffer_float, gated in-context) -> store
//   fragment pass -> SSBO -> glMapBufferRange (never glReadPixels) ->
//   clamp + uint16 quantize exactly like the developLinear tail.
//
// [caseIndex] selects the parity case (gles/GlesPipeline.h documents all
// three):
//   0 — the developLinear shape (uHasShading=0, uHr=0): the reference is
//       rawedit::developLinear itself, same frame, byte-honest regardless
//       of the clip's real metadata (the Measure leg keeps this case).
//   1 — the DEFAULT grade's real stage list (uHasShading = the clip's
//       map-present gate with the real 4-plane map uploaded, uHr=1 with
//       uHrGain = meta.wbGains with the >0 guard): the reference is
//       computed C++-side from the exported rawedit primitives.
//   2 — the TONEMAP sweep (stage 23): uTonemap runs modes 1 (ACES) through
//       5 (AgX Base), one develop+store+readback pass per mode; each
//       mode's reference is rawedit::developCpu itself with a default
//       Grade (tonemap=mode only) and an identity-matrices meta copy, so
//       the reference is the real exported stage list (1+2+3+4+5+23).
//       The report carries the WORST case across the five modes and
//       samples = 5 * w * h * 3.
// Returns
//
//   [0] ran           1 when the GPU pass produced real numbers
//   [1] maxDiffLsb    largest |gpu - cpu| in 16-bit LSB units
//   [2] mismatchCount samples whose 16-bit outputs differ
//   [3] samples       w * h * 3
//
// ran=0: a GPU stage failed (EGL, extension gate, compile, link, FBO
// completeness, draw, map) — the error detail names it (nativeLastError).
// nullptr: clip-level failure (open/decode; error state set). The PASS
// VERDICT is Kotlin's (GpuParityRules) — raw numbers only, never a
// claim. The fragment path needs NO compute (contextOk + floatFboOk
// gates it, not computeOk). Device-test-pending: no phone has run it.
//
// A5 step 1 (owner request 2026-11-23, guide A5): the TWO doors below
// share one body (fragmentParityBody — the exportFrameBody precedent).
// The 4-double plain door is byte-identical to its pre-extraction shape;
// the NEW string door nativeGlesFragmentParityDiag returns the same run's
// diagnosis (the ParityDiag histograms gles/ParityDiag.h shapes) as text.
// The 4-value array contract does NOT grow a field: GlesParityResult's
// require(size == 4) is a deliberately loud contract and the diagnosis
// rides its own door instead (guide A5: instrumentation only, no verdict
// or gate change on this path).

// The shared fragment-parity body: the four shader-string reads, the
// clip open, frame 0's info+decode, the developFragmentParity call and
// the !rep.ran setError path. Returns nothing (error state set) on any
// clip-level/JNI failure — same error stages, same message strings, same
// doubles for the plain door as before the extraction (T-qt-02: the
// extraction is mechanical only; any error-text drift is contract drift
// and fails the GpuParityTest-pinned seam).
static std::optional<gles::ParityReport> fragmentParityBody(
        JNIEnv* env, jint fd, jstring vertexSource, jstring vertex310Source,
        jstring fragmentSource, jstring storeFragmentSource, jint caseIndex) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;

    // Read the four shader sources (each GetStringUTFChars pair follows the
    // nativeGlesParity pattern; a null return is the JNI OOM path).
    const char* v300Chars = env->GetStringUTFChars(vertexSource, nullptr);
    if (v300Chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "gles fragment parity: out of memory reading the vertex source "
                 "(recovery: free memory and retry)");
        return std::nullopt;
    }
    const std::string v300(v300Chars);
    env->ReleaseStringUTFChars(vertexSource, v300Chars);

    const char* v310Chars = env->GetStringUTFChars(vertex310Source, nullptr);
    if (v310Chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "gles fragment parity: out of memory reading the 310 vertex source "
                 "(recovery: free memory and retry)");
        return std::nullopt;
    }
    const std::string v310(v310Chars);
    env->ReleaseStringUTFChars(vertex310Source, v310Chars);

    const char* fragChars = env->GetStringUTFChars(fragmentSource, nullptr);
    if (fragChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "gles fragment parity: out of memory reading the develop source "
                 "(recovery: free memory and retry)");
        return std::nullopt;
    }
    const std::string frag(fragChars);
    env->ReleaseStringUTFChars(fragmentSource, fragChars);

    const char* storeChars = env->GetStringUTFChars(storeFragmentSource, nullptr);
    if (storeChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "gles fragment parity: out of memory reading the store source "
                 "(recovery: free memory and retry)");
        return std::nullopt;
    }
    const std::string store(storeChars);
    env->ReleaseStringUTFChars(storeFragmentSource, storeChars);

    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("gles fragment parity: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail +
                     " (recovery: reopen the clip and retry)");
        return std::nullopt;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);
    const rawedit::FrameInfo info = src->frameInfo(0);
    auto frame = src->readFrame(0);
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("gles fragment parity: frame 0 decode — ") +
                     rawedit::errorName(frame.code) + " — " + frame.detail +
                     " (recovery: reopen the clip and retry)");
        return std::nullopt;
    }
    const gles::ParityReport rep =
        gles::developFragmentParity(v300, v310, frag, store, caseIndex,
                                     *frame.value, info, src->rawMeta());
    if (!rep.ran) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "gles fragment parity: " + rep.detail +
                     " (recovery: the CPU render path stays the default)");
    }
    return rep;
}

// The plain door (deliverable D): a thin formatter over the shared body —
// the returned doubles and every error stage/string are byte-identical
// to the pre-extraction shape (see the shared-body comment above).
JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeGlesFragmentParity(
        JNIEnv* env, jclass, jint fd, jstring vertexSource, jstring vertex310Source,
        jstring fragmentSource, jstring storeFragmentSource, jint caseIndex) {
    const std::optional<gles::ParityReport> rep =
        fragmentParityBody(env, fd, vertexSource, vertex310Source,
                           fragmentSource, storeFragmentSource, caseIndex);
    if (!rep.has_value()) return nullptr;
    const double out[4] = {
        rep->ran ? 1.0 : 0.0,
        static_cast<double>(rep->maxDiffLsb),
        static_cast<double>(rep->mismatchCount),
        static_cast<double>(rep->samples),
    };
    jdoubleArray result = env->NewDoubleArray(4);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 4, out);
    return result;
}

// The diagnosis door (A5 step 1, owner request 2026-11-23, guide A5):
// the SAME shared body as the plain door (its own one-shot EGL context —
// a second full-frame parity pass, exactly what makes it a separate door
// rather than a widened array), formatted as TEXT for the debug Measure
// block: the diagHeaderLine (the plain door's four numbers as one line)
// plus, when the run produced real numbers, the ParityDiag histograms
// (value / boundary / mag+ch). A !ran result returns the header line
// only — the setError detail already named the failing step through
// nativeLastError, the Kotlin leg logs it beside the block. nullptr on
// the body's clip-level/JNI failures (error state set, same as the plain
// door). No new logging, no verdict: the owner reads the histograms and
// decides (guide A5 step 2). Device-test-pending: no phone has run it.
JNIEXPORT jstring JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeGlesFragmentParityDiag(
        JNIEnv* env, jclass, jint fd, jstring vertexSource, jstring vertex310Source,
        jstring fragmentSource, jstring storeFragmentSource, jint caseIndex) {
    const std::optional<gles::ParityReport> rep =
        fragmentParityBody(env, fd, vertexSource, vertex310Source,
                           fragmentSource, storeFragmentSource, caseIndex);
    if (!rep.has_value()) return nullptr;
    std::string text = gles::diagHeaderLine(
        rep->ran, rep->maxDiffLsb, rep->mismatchCount, rep->samples);
    if (rep->ran) {
        text += "\n";
        text += gles::formatParityDiag(rep->diag);
    }
    return env->NewStringUTF(text.c_str());  // null on the JNI OOM path (the existing convention)
}

// MP4 export pipeline ----------------------------------------------------------
//
// The exporter opens a session with nativePreviewOpen (same handle registry,
// same [handle, frameCount, w, h, cfa, orient] contract) and then calls
// nativeExportFrameParcel per frame: full-res developCpu (NO thumbnail
// downscale) -> linearToRec709 -> clamp [0,1] -> quantize 8-bit RGB888.
// The clamp is inherent to 8-bit quantization (linearToRec709 itself is
// unclamped); values above 1.0 clip at 255, below 0 at 0. A 4K frame is
// ~25 MB of RGB — acceptable for the CPU reference path (a GPU path lands
// later). PTS comes from nativeFramePtsUs; audio description from
// nativeAudioInfo, samples from nativeReadAudio (all existing entry points
// except the two new ones below).
//
// The grade parcel crossing (commit a + b): the per-frame SCALAR grade set
// crosses as ONE flat FloatArray. The ONE front door is
// nativeExportFrameParcel (decodes the parcel ONCE into
// gles::DevelopGrade, folds it onto rawedit::Grade via the GENERATED
// applyGradeToRawedit, sets lutStrength from its own flat param — the LUT
// binding group is deliberately not parcel fields). The temporal pair
// rides the parcel like every other scalar and dies at temporalMergedCfa
// below, exactly as the flat path's jboolean/jfloat pair always did. The
// flat nativeExportFrame door died with commit (b) — its Kotlin decl is
// gone, so no caller can reach this body with per-field params.

// The shared export body: everything except the grade's arrival shape.
// grade is the job's grade snapshot (lutStrength already folded in);
// the LUT binding and stabilization params are per-job/plan data that
// stay flat. Returns nullptr (error state set) on any failure.
jbyteArray exportFrameBody(JNIEnv* env, jlong handle, jint frameIndex,
                           const rawedit::Grade& grade,
                           jstring lutPath,
                           jstring gyroPath, jint stabMode, jfloat stabStrength,
                           jfloat stabZoomLimit, jfloat stabFocalMm, jfloat stabTrimMs,
                           jint lutRole, jint lutInGamut, jint lutInTransfer,
                           jint lutOutGamut, jint lutOutTransfer) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // export frame with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    // 0a. Resolve the managed binding (2026-11-13): all five -1 = the
    //     legacy stage-24 pair, byte-identical.
    const rawedit::CompiledLutBinding* binding = nullptr;
    resolveBinding(lut, lutRole, lutInGamut, lutInTransfer,
                   lutOutGamut, lutOutTransfer, &binding);
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
        //     The export handle's OWN Entry carries its plan cache (the
        //     2026-11-13 perf fix): sequential export frames hit it after
        //     the first frame.
        Entry& e = gEntries[handle - 1];
        const StabResolution stab =
            resolveStabilization(env, gyroPath, stabMode, stabStrength,
                                 stabZoomLimit, stabFocalMm, stabTrimMs,
                                 s, frameIndex, &e.stabPlan);
        if (!stab.ok) return nullptr;

        // Sub-stage timing (commit (d)): one steady_clock read at each of
        // the six boundaries, collected only when the debug gate is on.
        // Release builds pay one relaxed atomic load per boundary and
        // nothing else (the gStabDebugLog pattern). The slots are read by
        // nativeExportFrameTimingMs on this same thread right after a
        // SUCCESSFUL call; a failure return leaves the previous frame's
        // numbers, which the Kotlin side never reads on a failed frame.
        const bool timing = gExportTiming.load(std::memory_order_relaxed);
        using SteadyClock = std::chrono::steady_clock;
        SteadyClock::time_point tDecode0{}, tTemporal0{}, tDevelop0{},
            tWarp0{}, tOetf0{}, tCopy0{};
        if (timing) tDecode0 = SteadyClock::now();

        // 1. Frame read — through the ring, like the preview paths (sequential
        //    export amortizes to ~1 decode per frame with temporal NR on).
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();
        if (timing) {
            tTemporal0 = SteadyClock::now();
            gExportFrameTiming.decodeMs = elapsedMs(tDecode0, tTemporal0);
        }

        // 2. Temporal NR on the CFA (pristine buffer back when off).
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            grade.temporalNr, grade.temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed
        if (timing) {
            tDevelop0 = SteadyClock::now();
            gExportFrameTiming.temporalMs = elapsedMs(tTemporal0, tDevelop0);
        }

        // 3. Full-res develop with the job's grade snapshot (core/Develop.h).
        gLastErrorStage = kStageDevelop;
        rawedit::ImageF img = rawedit::developCpu(cfa->data(), info, meta, grade,
                                                  lut, binding);
        if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop: unsupported — the frame is too small to develop "
                     "(recovery: re-import the clip)");
            return nullptr;
        }
        if (timing) {
            tWarp0 = SteadyClock::now();
            gExportFrameTiming.developMs = elapsedMs(tDevelop0, tWarp0);
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
        if (timing) {
            tOetf0 = SteadyClock::now();
            gExportFrameTiming.warpMs = elapsedMs(tWarp0, tOetf0);
        }

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
        if (timing) {
            tCopy0 = SteadyClock::now();
            gExportFrameTiming.oetfPackMs = elapsedMs(tOetf0, tCopy0);
        }
        jbyteArray arr = env->NewByteArray(n);
        if (arr == nullptr) return nullptr;
        env->SetByteArrayRegion(arr, 0, n, out.data());
        if (timing) {
            gExportFrameTiming.jniCopyMs = elapsedMs(tCopy0, SteadyClock::now());
        }
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

// Decodes the grade parcel (one flat FloatArray, SLOT_COUNT floats) into
// [out]. Returns false with the honest message in [error] on any refusal
// (unknown length, non-finite value, boolean outside strict 0/1) — the
// caller rides setError; no exception ever crosses the boundary.
bool readGradeParcel(JNIEnv* env, jfloatArray parcel, gles::DevelopGrade* out,
                     std::string* error) {
    const jsize len = parcel == nullptr ? 0 : env->GetArrayLength(parcel);
    float buf[gles::kGradeParcelSlotCount];
    if (len == gles::kGradeParcelSlotCount) {
        env->GetFloatArrayRegion(parcel, 0, len, buf);
    }
    return gles::decodeGradeParcel(buf, len, out, error);
}

// The CPU export's frame develop (GRADE PARCEL, commit a + b): the 29
// scalar grade fields as ONE flat FloatArray. 16 parameters: handle,
// frameIndex, gradeParcel + lutPath, lutStrength + the six stabilization
// plan values + the five LUT-binding ordinals. The decode is ONCE per
// call; a refusal fails the frame honestly (setError + nullptr), never a
// silent misread. Commit (b): the CPU export rides THIS door — the flat
// nativeExportFrame died with its Kotlin decl.
JNIEXPORT jbyteArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeExportFrameParcel(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloatArray gradeParcel,
        jstring lutPath, jfloat lutStrength,
        jstring gyroPath, jint stabMode, jfloat stabStrength,
        jfloat stabZoomLimit, jfloat stabFocalMm, jfloat stabTrimMs,
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer) {
    gles::DevelopGrade decoded;
    std::string parcelError;
    if (!readGradeParcel(env, gradeParcel, &decoded, &parcelError)) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageFrameRead,
                 parcelError);
        return nullptr;
    }
    rawedit::Grade grade;
    gles::applyGradeToRawedit(decoded, &grade);
    grade.lutStrength = lutStrength;
    return exportFrameBody(env, handle, frameIndex, grade,
                           lutPath, gyroPath, stabMode, stabStrength,
                           stabZoomLimit, stabFocalMm, stabTrimMs,
                           lutRole, lutInGamut, lutInTransfer,
                           lutOutGamut, lutOutTransfer);
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

// GPU develop session entry points (M3 commit a) ------------------------------
//
// The production counterpart of nativeGlesFragmentParity: where that
// function built the whole pipeline one-shot per call, these hold it. The
// Kotlin exporter (commit b) drives one session per export job:
//
//   nativeDevelopSessionOpen(vertex, vertex310, pre, post, tail,
//                           sharpnessH, sharpnessV, detailH, detailV,
//                           warp, nrChromaH, nrChromaV, nrLuma, store,
//                           blit) -> handle
//   nativeDevelopSessionRenderFrameParcel(handle, clipHandle, frameIndex,
//                                         gradeParcel,
//                                         lutPath, lutStrength, lutRole,
//                                         lutInGamut, lutInTransfer,
//                                         lutOutGamut, lutOutTransfer,
//                                         warpActive, warpM) -> RGB888 bytes
//   nativeDevelopSessionSetEncoderSurface(handle, surface) -> ok
//   nativeDevelopSessionRenderFrameToEncoderParcel(handle, clipHandle,
//                                                  frameIndex, ptsUs,
//                                                  gradeParcel,
//                                                  lutPath, lutStrength,
//                                                  lutRole, lutInGamut,
//                                                  lutInTransfer,
//                                                  lutOutGamut,
//                                                  lutOutTransfer,
//                                                  warpActive, warpM,
//                                                  rotationDeg) -> ok
//   nativeDevelopSessionClose(handle)
//
// GRADE PARCEL (commit a + b): the 29 fragment-covered scalar grade
// controls cross as ONE flat gradeParcel FloatArray — GENERATED from
// tools/gen-grade-parcel.py's schema on both sides of the seam (the
// flat 41/43-parameter doors died with commit (b); the parcel is the
// ONLY render shape). The parcel is decoded ONCE at the top of each
// door (a refusal fails the render honestly with setError + null/false,
// never a silent misread).
//
// Open compiles the caller-provided Kotlin shader texts (the single source
// of truth, host-validated — the same contract the parity harness rides).
// RenderFrame reuses the export handle's OWN Source on the CALLING thread
// for the decode (loadRingFrame — the ring amortizes sequential frames),
// then hands the decoded CFA to the session's GL thread: only the GL work
// marshals. Close is idempotent; the destructor also closes.
//
// The encoder-surface seams (M5) hand the MediaCodec input Surface to the
// session: SetEncoderSurface wraps it in an EGL window surface ONCE per
// export (the session's own config, opened recordable), RenderFrameToEncoder
// develops on the GPU and blits straight to it — no readback on that path.
// The surface ownership follows nativePreviewSessionSetSurface's shape
// (ANativeWindow_fromSurface at entry, release on the GL thread on every
// path); a refusal returns false with the reason in the last-error slot —
// the Kotlin dispatcher falls back with ONE honest line, never a crash.
//
// The parcel covers the fragment-covered grade controls — the five base
// ones (exposure, highlights, highlight reconstruction, vignetting,
// tonemap), the two spatial-NR stages' raw values since the NR port
// (chroma denoise, noise reduction — stages 7/8), the two unsharp stages'
// raw values since the sharpness/detail port (sharpness, detail —
// stages 21/22, the four-pass unsharp chain), plus the ten pointwise
// stages' raw values (black point, white point, shadows, midtones,
// contrast, temperature, tint, the color wheel, split tone, saturation)
// with asShot as the 16/17 gate's input. The temporal pair (temporalNr,
// temporalStrength — the same raw values nativeExportFrameParcel
// receives) rides the SAME rule the CPU path rides: the pair dies at
// this bridge (the session never sees it), consumed by
// temporalMergedCfa to produce the CFA the request uploads — routing,
// not porting; the merge math stays core/Temporal.cpp's, frozen. Every
// parcel slot names a stage the shader covers OR a pre-stage the
// bridge's own merge covers.

// The one open path behind both JNI doors (E1d): [tenBit] asks for the
// RGBA1010102 + window + recordable EGL config (found by query). The
// RGBA8888 door passes false and is byte-identical to before.
static jlong developSessionOpenImpl(
        JNIEnv* env, jstring vertexSource, jstring vertex310Source,
        jstring preFragmentSource, jstring postFragmentSource,
        jstring tailFragmentSource, jstring sharpnessHSource,
        jstring sharpnessVSource, jstring detailHSource, jstring detailVSource,
        jstring warpFragmentSource, jstring nrChromaHSource,
        jstring nrChromaVSource,
        jstring nrLumaSource, jstring storeFragmentSource,
        jstring blitFragmentSource, bool tenBit) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;

    // The fifteen sources, each read with the nativeGlesFragmentParity pattern
    // (GetStringUTFChars -> std::string copy -> Release; null = JNI OOM).
    const char* v300Chars = env->GetStringUTFChars(vertexSource, nullptr);
    if (v300Chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the vertex source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string v300(v300Chars);
    env->ReleaseStringUTFChars(vertexSource, v300Chars);

    const char* v310Chars = env->GetStringUTFChars(vertex310Source, nullptr);
    if (v310Chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the 310 vertex source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string v310(v310Chars);
    env->ReleaseStringUTFChars(vertex310Source, v310Chars);

    const char* preChars = env->GetStringUTFChars(preFragmentSource, nullptr);
    if (preChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the pre source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string pre(preChars);
    env->ReleaseStringUTFChars(preFragmentSource, preChars);

    const char* postChars = env->GetStringUTFChars(postFragmentSource, nullptr);
    if (postChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the post source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string post(postChars);
    env->ReleaseStringUTFChars(postFragmentSource, postChars);

    const char* tailChars = env->GetStringUTFChars(tailFragmentSource, nullptr);
    if (tailChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the tail source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string tail(tailChars);
    env->ReleaseStringUTFChars(tailFragmentSource, tailChars);

    const char* sharpnessHChars = env->GetStringUTFChars(sharpnessHSource, nullptr);
    if (sharpnessHChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the sharpness-h source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string sharpnessH(sharpnessHChars);
    env->ReleaseStringUTFChars(sharpnessHSource, sharpnessHChars);

    const char* sharpnessVChars = env->GetStringUTFChars(sharpnessVSource, nullptr);
    if (sharpnessVChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the sharpness-v source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string sharpnessV(sharpnessVChars);
    env->ReleaseStringUTFChars(sharpnessVSource, sharpnessVChars);

    const char* detailHChars = env->GetStringUTFChars(detailHSource, nullptr);
    if (detailHChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the detail-h source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string detailH(detailHChars);
    env->ReleaseStringUTFChars(detailHSource, detailHChars);

    const char* detailVChars = env->GetStringUTFChars(detailVSource, nullptr);
    if (detailVChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the detail-v source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string detailV(detailVChars);
    env->ReleaseStringUTFChars(detailVSource, detailVChars);

    const char* warpChars = env->GetStringUTFChars(warpFragmentSource, nullptr);
    if (warpChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the warp source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string warp(warpChars);
    env->ReleaseStringUTFChars(warpFragmentSource, warpChars);

    const char* nrChromaHChars = env->GetStringUTFChars(nrChromaHSource, nullptr);
    if (nrChromaHChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the nr-chroma-h source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string nrChromaH(nrChromaHChars);
    env->ReleaseStringUTFChars(nrChromaHSource, nrChromaHChars);

    const char* nrChromaVChars = env->GetStringUTFChars(nrChromaVSource, nullptr);
    if (nrChromaVChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the nr-chroma-v source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string nrChromaV(nrChromaVChars);
    env->ReleaseStringUTFChars(nrChromaVSource, nrChromaVChars);

    const char* nrLumaChars = env->GetStringUTFChars(nrLumaSource, nullptr);
    if (nrLumaChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the nr-luma source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string nrLuma(nrLumaChars);
    env->ReleaseStringUTFChars(nrLumaSource, nrLumaChars);

    const char* storeChars = env->GetStringUTFChars(storeFragmentSource, nullptr);
    if (storeChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the store source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string store(storeChars);
    env->ReleaseStringUTFChars(storeFragmentSource, storeChars);

    const char* blitChars = env->GetStringUTFChars(blitFragmentSource, nullptr);
    if (blitChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: out of memory reading the blit source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string blit(blitChars);
    env->ReleaseStringUTFChars(blitFragmentSource, blitChars);

    // Find a free slot (the registry's fixed array bounds the leak surface).
    int slot = -1;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        for (int i = 0; i < kMaxDevelopSessions; ++i) {
            if (gDevelopSessions[i] == nullptr) {
                slot = i;
                break;
            }
        }
    }
    if (slot < 0) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: too many open sessions "
                 "(recovery: close a session and retry)");
        return 0;
    }

    std::string detail;
    std::unique_ptr<gles::DevelopSession> session =
        gles::DevelopSession::open(v300, v310, pre, post, tail, sharpnessH,
                                   sharpnessV, detailH, detailV, warp,
                                   nrChromaH, nrChromaV, nrLuma, store, blit,
                                   &detail, tenBit);
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "develop session: " + detail +
                     " (recovery: the CPU develop path stays the default)");
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gDevelopSessions[slot] = std::move(session);
    }
    return static_cast<jlong>(slot) + 1;
}

JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopSessionOpen(
        JNIEnv* env, jclass, jstring vertexSource, jstring vertex310Source,
        jstring preFragmentSource, jstring postFragmentSource,
        jstring tailFragmentSource, jstring sharpnessHSource,
        jstring sharpnessVSource, jstring detailHSource, jstring detailVSource,
        jstring warpFragmentSource, jstring nrChromaHSource,
        jstring nrChromaVSource,
        jstring nrLumaSource, jstring storeFragmentSource,
        jstring blitFragmentSource) {
    return developSessionOpenImpl(
        env, vertexSource, vertex310Source, preFragmentSource,
        postFragmentSource, tailFragmentSource, sharpnessHSource,
        sharpnessVSource, detailHSource, detailVSource, warpFragmentSource,
        nrChromaHSource, nrChromaVSource, nrLumaSource, storeFragmentSource,
        blitFragmentSource, /*tenBit=*/false);
}

// E1d: the same session on an RGBA1010102 config (10-bit encoder surface).
JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopSessionOpenTenBit(
        JNIEnv* env, jclass, jstring vertexSource, jstring vertex310Source,
        jstring preFragmentSource, jstring postFragmentSource,
        jstring tailFragmentSource, jstring sharpnessHSource,
        jstring sharpnessVSource, jstring detailHSource, jstring detailVSource,
        jstring warpFragmentSource, jstring nrChromaHSource,
        jstring nrChromaVSource,
        jstring nrLumaSource, jstring storeFragmentSource,
        jstring blitFragmentSource) {
    return developSessionOpenImpl(
        env, vertexSource, vertex310Source, preFragmentSource,
        postFragmentSource, tailFragmentSource, sharpnessHSource,
        sharpnessVSource, detailHSource, detailVSource, warpFragmentSource,
        nrChromaHSource, nrChromaVSource, nrLumaSource, storeFragmentSource,
        blitFragmentSource, /*tenBit=*/true);
}

// E1d: can this device make an RGBA1010102 + pbuffer + window + recordable
// ES3 config? A throwaway EGL open on the calling thread; the failing step
// lands in the error state's detail (previewLastError) when false.
JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopTenBitProbe(
        JNIEnv*, jclass) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    std::string detail;
    const bool ok = gles::DevelopSession::probeTenBitConfig(&detail);
    if (!ok) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "ten-bit probe: " + detail +
                     " (recovery: 10-bit export stays unavailable on this device)");
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

// The develop session's render door (GRADE PARCEL, commit a + b): the 29
// scalar grade fields as ONE flat FloatArray. 13 parameters:
// sessionHandle, clipHandle, frameIndex, gradeParcel, lutPath,
// lutStrength + the five LUT-binding ordinals + warpActive, warpM. The
// parcel is decoded ONCE at the top (a refusal fails the render honestly
// with setError + nullptr, never a silent misread), the temporal pair
// feeds temporalMergedCfa from the decoded struct, and the request
// carries ONLY the non-grade fields — the grade rides the struct-taking
// session overload (the GENERATED applyGradeToRequest fold, no
// hand-mirrored field list at this seam). Commit (b): the flat
// nativeDevelopSessionRenderFrame died with its Kotlin decl — this is
// the ONLY render door.
JNIEXPORT jbyteArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopSessionRenderFrameParcel(
        JNIEnv* env, jclass, jlong sessionHandle, jlong clipHandle,
        jint frameIndex, jfloatArray gradeParcel,
        jstring lutPath, jfloat lutStrength,
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer,
        jboolean warpActive, jfloatArray warpM) {
    gles::DevelopGrade grade;
    std::string parcelError;
    if (!readGradeParcel(env, gradeParcel, &grade, &parcelError)) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageFrameRead,
                 parcelError);
        return nullptr;
    }
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    if (sessionHandle <= 0 || sessionHandle > kMaxDevelopSessions) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "develop session: invalid session handle "
                 "(recovery: reopen the export)");
        return nullptr;
    }
    gles::DevelopSession* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        session = gDevelopSessions[sessionHandle - 1].get();
    }
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "develop session: session is closed "
                 "(recovery: reopen the export)");
        return nullptr;
    }
    rawedit::Source* s = sourceFor(clipHandle);
    if (!s) return nullptr;
    try {
        // Same shape nativeExportFrameParcel rides: decode + frame info on
        // the CALLING thread, the ring amortizing sequential export frames.
        Entry& e = gEntries[clipHandle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // Temporal NR — the temporal pair arrives IN the parcel here (the
        // decoded struct), consumed by the merge before the request
        // exists, exactly like the flat jboolean/jfloat pair once did.
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            grade.temporalNr, grade.temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        gles::DevelopFrameRequest req;
        req.cfa = cfa.get();
        req.info = info;
        req.meta = &meta;
        // Stage 24 LUT: the resolved .cube path + the [0,1] strength + the
        // five interpretation ordinals (-1 = the legacy shape). A null
        // jstring = no LUT.
        req.lutStrength = lutStrength;
        if (lutPath != nullptr) {
            const char* lutChars = env->GetStringUTFChars(lutPath, nullptr);
            if (lutChars != nullptr) {
                req.lutPath.assign(lutChars);
                env->ReleaseStringUTFChars(lutPath, lutChars);
            }
        }
        req.lutRole = lutRole;
        req.lutInGamut = lutInGamut;
        req.lutInTransfer = lutInTransfer;
        req.lutOutGamut = lutOutGamut;
        req.lutOutTransfer = lutOutTransfer;
        // Gyro warp: the caller-resolved (warpActive, warpM) pair; warpM
        // may be null when inactive.
        req.warpActive = warpActive == JNI_TRUE;
        if (warpM != nullptr) {
            env->GetFloatArrayRegion(warpM, 0, 9, req.warpM);
        }
        const gles::DevelopFrameResult res = session->renderFrame(req, grade);
        if (!res.ok) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop session render: " + res.detail +
                         " (recovery: the export falls back to the CPU develop)");
            return nullptr;
        }
        const jsize n = static_cast<jsize>(res.rgb888.size());
        if (n <= 0 || static_cast<size_t>(n) != res.rgb888.size()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop session render: empty output "
                     "(recovery: reopen the export)");
            return nullptr;
        }
        jbyteArray arr = env->NewByteArray(n);
        if (arr == nullptr) return nullptr;
        env->SetByteArrayRegion(arr, 0, n,
            reinterpret_cast<const jbyte*>(res.rgb888.data()));
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop session render: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop session render: ") + e.what() +
                     " (recovery: reopen the export)");
        return nullptr;
    }
}

// ENCODER SURFACE (M5). The MediaCodec input Surface reaches the session
// through the same ownership shape as nativePreviewSessionSetSurface: the
// ANativeWindow_fromSurface reference transfers to the session at call
// entry (the session releases it on the GL thread on every path, so this
// layer NEVER releases it after a successful fromSurface); a null surface
// detaches. A refusal is honest — false with the step in the last-error
// slot — and the Kotlin dispatcher turns it into ONE fallback line.

JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopSessionSetEncoderSurface(
        JNIEnv* env, jclass, jlong sessionHandle, jobject surface) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    if (sessionHandle <= 0 || sessionHandle > kMaxDevelopSessions) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: invalid session handle "
                 "(recovery: reopen the export)");
        return JNI_FALSE;
    }
    gles::DevelopSession* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        session = gDevelopSessions[sessionHandle - 1].get();
    }
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "develop session: session is closed "
                 "(recovery: reopen the export)");
        return JNI_FALSE;
    }
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
        if (window == nullptr) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                     "develop session: could not acquire the encoder surface "
                     "(recovery: the export falls back to the CPU develop)");
            return JNI_FALSE;
        }
    }
    std::string detail;
    if (!session->setEncoderSurface(window, &detail)) {
        // Ownership already transferred at entry (even on refusal — the
        // session released the window); the reason lands in the error slot.
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "develop session encoder surface: " + detail +
                     " (recovery: the export falls back to the CPU develop)");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// ENCODER RENDER (M5). The same decode-on-the-calling-thread shape as
// nativeDevelopSessionRenderFrameParcel (Source's one-thread rule; the ring
// amortizes sequential export frames), but the GL thread develops AND
// blits straight to the encoder window surface — no readback bytes ever
// cross the seam, so the return is a boolean, not a byte array. [ptsUs]
// is the frame's normalized encoder presentation timestamp (the same
// value the writer path's PtsTimeline would stamp); the session
// multiplies it to nanoseconds media time. [rotationDeg] is the frame's
// effective orientation (container + manual, normalized): the blit
// applies it through the uRotation fold — the session fast-refuses
// anything outside 0/90/180/270. ok=false means the export falls back
// per the dispatcher's one-line rule.

// The encoder-surface render door (GRADE PARCEL, commit a + b): 15
// parameters — the 29-field grade block collapsed into gradeParcel,
// ptsUs/rotationDeg kept (job mechanics, not grade scalars). The parcel
// is decoded ONCE at the top (a refusal fails the render honestly with
// setError + JNI_FALSE, never a silent misread); the grade rides the
// struct-taking session overload (the GENERATED fold). Commit (b): the
// flat nativeDevelopSessionRenderFrameToEncoder died with its Kotlin
// decl — this is the ONLY encoder door.
JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopSessionRenderFrameToEncoderParcel(
        JNIEnv* env, jclass, jlong sessionHandle, jlong clipHandle,
        jint frameIndex, jlong ptsUs, jfloatArray gradeParcel,
        jstring lutPath, jfloat lutStrength,
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer,
        jboolean warpActive, jfloatArray warpM, jint rotationDeg) {
    gles::DevelopGrade grade;
    std::string parcelError;
    if (!readGradeParcel(env, gradeParcel, &grade, &parcelError)) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageFrameRead,
                 parcelError);
        return JNI_FALSE;
    }
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    if (sessionHandle <= 0 || sessionHandle > kMaxDevelopSessions) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "develop session: invalid session handle "
                 "(recovery: reopen the export)");
        return JNI_FALSE;
    }
    gles::DevelopSession* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        session = gDevelopSessions[sessionHandle - 1].get();
    }
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "develop session: session is closed "
                 "(recovery: reopen the export)");
        return JNI_FALSE;
    }
    rawedit::Source* s = sourceFor(clipHandle);
    if (!s) return JNI_FALSE;
    try {
        Entry& e = gEntries[clipHandle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return JNI_FALSE; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // Temporal NR — the temporal pair arrives IN the parcel here,
        // consumed by the merge before the request exists.
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            grade.temporalNr, grade.temporalStrength);
        if (cfa == nullptr) return JNI_FALSE; // neighbor decode failed

        gles::DevelopFrameRequest req;
        req.cfa = cfa.get();
        req.info = info;
        req.meta = &meta;
        // Stage 24 LUT + gyro warp (the caller-resolved pair; warpM may be
        // null when inactive).
        req.lutStrength = lutStrength;
        if (lutPath != nullptr) {
            const char* lutChars = env->GetStringUTFChars(lutPath, nullptr);
            if (lutChars != nullptr) {
                req.lutPath.assign(lutChars);
                env->ReleaseStringUTFChars(lutPath, lutChars);
            }
        }
        req.lutRole = lutRole;
        req.lutInGamut = lutInGamut;
        req.lutInTransfer = lutInTransfer;
        req.lutOutGamut = lutOutGamut;
        req.lutOutTransfer = lutOutTransfer;
        req.warpActive = warpActive == JNI_TRUE;
        if (warpM != nullptr) {
            env->GetFloatArrayRegion(warpM, 0, 9, req.warpM);
        }
        const gles::DevelopFrameResult res =
            session->renderFrameToEncoder(req, grade,
                                          static_cast<int64_t>(ptsUs),
                                          static_cast<int>(rotationDeg));
        if (!res.ok) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop session encoder render: " + res.detail +
                         " (recovery: the export falls back to the CPU develop)");
            return JNI_FALSE;
        }
        return JNI_TRUE;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop session encoder render: out of memory decoding the "
                 "frame (recovery: free memory and retry)");
        return JNI_FALSE;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop session encoder render: ") + e.what() +
                     " (recovery: reopen the export)");
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDevelopSessionClose(
        JNIEnv*, jclass, jlong sessionHandle) {
    if (sessionHandle <= 0 || sessionHandle > kMaxDevelopSessions) return;
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gDevelopSessions[sessionHandle - 1].reset();
}

// GPU preview session (M4 commit a) -------------------------------------------
//
// The preview counterpart of the M3 develop-session seam, wired to the
// SurfaceView the editor mounts (commit b). One session per clip preview:
//
//   nativePreviewSessionOpen(vertex, vertex310, pre, post, tail, sharpnessH,
//                            sharpnessV, detailH, detailV, nrChromaH,
//                            nrChromaV, nrLuma, store, window) -> handle
//   nativePreviewSessionSetSurface(handle, surface|null) -> boolean
//   nativePreviewSessionRenderFrameParcel(handle, clipHandle, frameIndex,
//       maxDim, gradeParcel, lutPath, lutStrength, lutRole, lutInGamut,
//       lutInTransfer, lutOutGamut, lutOutTransfer, warpActive, warpM,
//       statsMode, cropActive, cropParams, zebraOn, zebraThreshold,
//       zebraPeriod, zebraAlpha)
//       -> [w, h, pixels...] int array (the small published stats pack)
//   nativePreviewSessionClose(handle)
//
// Open compiles the same single-source-of-truth Kotlin shader texts
// (host-validated) plus the window pass's own WINDOW_FRAGMENT. SetSurface
// carries the SurfaceHolder's lifecycle: a non-null surface attaches the
// window (the ANativeWindow_fromSurface reference transfers to the session
// at call entry — the session releases it on every path, so the JNI layer
// NEVER releases it after a successful fromSurface), null detaches.
// RenderFrame decodes + decimates on the CALLING thread (Source's
// one-thread rule; the decimation is Thumbnail.h's cell-aligned math,
// copied per the documented-copy rule — core/ is READ-ONLY; the reduced
// mosaic keeps the 2x2 Bayer pattern, so uCell/uBlack/uRange and the
// shading plane order stay valid at the preview tier), then hands the
// request to the session's GL thread: develop -> window present
// (best-effort) -> the small stats pack readback. NO full-frame readback
// exists on this path — the audit's explicit rule; glReadPixels is used
// NOWHERE.
//
// The grade controls cross as the ONE flat grade parcel (GRADE PARCEL,
// commit a + b: the 29 fragment-covered scalar knobs as one FloatArray,
// GENERATED from tools/gen-grade-parcel.py's schema on both sides of the
// seam — the same parcel the export and develop-session doors ride, so
// a new grade field is a schema row, not a signature widening). The
// temporal pair rides the parcel like every other scalar and dies at
// this bridge, consumed by temporalMergedCfa BEFORE the CFA reduction,
// so the reduced mosaic samples the MERGED CFA (the merge math stays
// core/Temporal.cpp's, frozen — routing, not porting).

JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewSessionOpen(
        JNIEnv* env, jclass, jstring vertexSource, jstring vertex310Source,
        jstring preFragmentSource, jstring postFragmentSource,
        jstring tailFragmentSource, jstring sharpnessHSource,
        jstring sharpnessVSource, jstring detailHSource, jstring detailVSource,
        jstring warpFragmentSource, jstring nrChromaHSource,
        jstring nrChromaVSource,
        jstring nrLumaSource, jstring storeFragmentSource,
        jstring windowFragmentSource) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;

    // The fifteen sources, each read with the nativeGlesFragmentParity pattern
    // (GetStringUTFChars -> std::string copy -> Release; null = JNI OOM).
    const char* v300Chars = env->GetStringUTFChars(vertexSource, nullptr);
    if (v300Chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the vertex source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string v300(v300Chars);
    env->ReleaseStringUTFChars(vertexSource, v300Chars);

    const char* v310Chars = env->GetStringUTFChars(vertex310Source, nullptr);
    if (v310Chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the 310 vertex source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string v310(v310Chars);
    env->ReleaseStringUTFChars(vertex310Source, v310Chars);

    const char* preChars = env->GetStringUTFChars(preFragmentSource, nullptr);
    if (preChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the pre source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string pre(preChars);
    env->ReleaseStringUTFChars(preFragmentSource, preChars);

    const char* postChars = env->GetStringUTFChars(postFragmentSource, nullptr);
    if (postChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the post source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string post(postChars);
    env->ReleaseStringUTFChars(postFragmentSource, postChars);

    const char* tailChars = env->GetStringUTFChars(tailFragmentSource, nullptr);
    if (tailChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the tail source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string tail(tailChars);
    env->ReleaseStringUTFChars(tailFragmentSource, tailChars);

    const char* sharpnessHChars = env->GetStringUTFChars(sharpnessHSource, nullptr);
    if (sharpnessHChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the sharpness-h source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string sharpnessH(sharpnessHChars);
    env->ReleaseStringUTFChars(sharpnessHSource, sharpnessHChars);

    const char* sharpnessVChars = env->GetStringUTFChars(sharpnessVSource, nullptr);
    if (sharpnessVChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the sharpness-v source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string sharpnessV(sharpnessVChars);
    env->ReleaseStringUTFChars(sharpnessVSource, sharpnessVChars);

    const char* detailHChars = env->GetStringUTFChars(detailHSource, nullptr);
    if (detailHChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the detail-h source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string detailH(detailHChars);
    env->ReleaseStringUTFChars(detailHSource, detailHChars);

    const char* detailVChars = env->GetStringUTFChars(detailVSource, nullptr);
    if (detailVChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the detail-v source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string detailV(detailVChars);
    env->ReleaseStringUTFChars(detailVSource, detailVChars);

    const char* warpChars = env->GetStringUTFChars(warpFragmentSource, nullptr);
    if (warpChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the warp source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string warp(warpChars);
    env->ReleaseStringUTFChars(warpFragmentSource, warpChars);

    const char* nrChromaHChars = env->GetStringUTFChars(nrChromaHSource, nullptr);
    if (nrChromaHChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the nr-chroma-h source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string nrChromaH(nrChromaHChars);
    env->ReleaseStringUTFChars(nrChromaHSource, nrChromaHChars);

    const char* nrChromaVChars = env->GetStringUTFChars(nrChromaVSource, nullptr);
    if (nrChromaVChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the nr-chroma-v source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string nrChromaV(nrChromaVChars);
    env->ReleaseStringUTFChars(nrChromaVSource, nrChromaVChars);

    const char* nrLumaChars = env->GetStringUTFChars(nrLumaSource, nullptr);
    if (nrLumaChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the nr-luma source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string nrLuma(nrLumaChars);
    env->ReleaseStringUTFChars(nrLumaSource, nrLumaChars);

    const char* storeChars = env->GetStringUTFChars(storeFragmentSource, nullptr);
    if (storeChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the store source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string store(storeChars);
    env->ReleaseStringUTFChars(storeFragmentSource, storeChars);

    const char* windowChars = env->GetStringUTFChars(windowFragmentSource, nullptr);
    if (windowChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: out of memory reading the window source "
                 "(recovery: free memory and retry)");
        return 0;
    }
    const std::string window(windowChars);
    env->ReleaseStringUTFChars(windowFragmentSource, windowChars);

    // Find a free slot (the registry's fixed array bounds the leak surface).
    int slot = -1;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        for (int i = 0; i < kMaxPreviewSessions; ++i) {
            if (gPreviewSessions[i] == nullptr) {
                slot = i;
                break;
            }
        }
    }
    if (slot < 0) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: too many open sessions "
                 "(recovery: close a session and retry)");
        return 0;
    }

    std::string detail;
    std::unique_ptr<gles::PreviewSession> session =
        gles::PreviewSession::open(v300, v310, pre, post, tail, sharpnessH,
                                   sharpnessV, detailH, detailV, warp,
                                   nrChromaH, nrChromaV, nrLuma, store, window,
                                   &detail);
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "preview session: " + detail +
                     " (recovery: the CPU preview path stays the default)");
        return 0;
    }
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        gPreviewSessions[slot] = std::move(session);
    }
    return static_cast<jlong>(slot) + 1;
}

JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewSessionSetSurface(
        JNIEnv* env, jclass, jlong sessionHandle, jobject surface) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    if (sessionHandle <= 0 || sessionHandle > kMaxPreviewSessions) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: invalid session handle "
                 "(recovery: reopen the preview)");
        return JNI_FALSE;
    }
    gles::PreviewSession* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        session = gPreviewSessions[sessionHandle - 1].get();
    }
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageOpen,
                 "preview session: session is closed "
                 "(recovery: reopen the preview)");
        return JNI_FALSE;
    }
    // Ownership transfer at call entry: a non-null surface acquires the
    // ANativeWindow reference HERE and hands the raw pointer to the session
    // (which releases it on every path — refusal, failure, replace,
    // teardown). The JNI layer never releases it after a successful
    // fromSurface. A null surface is the destroy signal (no reference).
    ANativeWindow* window = nullptr;
    if (surface != nullptr) {
        window = ANativeWindow_fromSurface(env, surface);
        if (window == nullptr) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                     "preview session: ANativeWindow_fromSurface failed "
                     "(recovery: the preview retries on the next surface "
                     "callback, or falls back to CPU)");
            return JNI_FALSE;
        }
    }
    std::string detail;
    const bool ok = session->setSurface(window, &detail);
    if (!ok) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "preview session surface: " + detail +
                     " (recovery: the preview retries on the next surface "
                     "callback, or falls back to CPU)");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// The preview session's render door (GRADE PARCEL, commit a + b): 17
// parameters — the 29-field grade block collapsed into gradeParcel
// (maxDim stays: job mechanics, not a grade scalar). The parcel is
// decoded ONCE at the top (a refusal fails the render honestly with
// setError + null, never a silent misread); the grade rides the
// struct-taking session overload (the GENERATED fold). Commit (b): the
// flat nativePreviewSessionRenderFrame died with its Kotlin decl — this
// is the ONLY preview render door. A1 async stats (2026-11-23) added
// jint statsMode: the Kotlin-computed per-request readback mode (0=off
// 1=sync 2=async, the StatsRing.h StatsReadbackMode vocabulary), folded
// below with the master kill switch. A2 GPU crop/straighten (2026-11-23,
// guide A2) APPENDED the crop pair AFTER statsMode — append-only, never
// interleaved (a mismatched order is an immediate crash; the
// signature-lock test is the guard): jboolean cropActive + jfloatArray
// cropParams, the GpuCropMap.requestCrop parcel ([rectX, rectY, rectW,
// rectH, straightenDeg], the rect ALREADY mapRect'ed container-upright).
// A3 GPU zebra (2026-11-23, guide A3) APPENDED the zebra quad AFTER the
// crop pair — the same append-only rule: jboolean zebraOn + jint
// zebraThreshold + jint zebraPeriod + jfloat zebraAlpha, the
// single-sourced KOTLIN values (ZebraOverlay.STRIPE_PERIOD /
// ZebraOverlay.STRIPE_ALPHA / FrameHistogram.WHITE_CLIP_VALUE — 253/12/
// 200 live nowhere in this bridge). The session's window draw paints the
// stripes; the stats draw pins uZebraOn=0 (CLEAN scopes).
// NO new tail fields — the 13-int measurement tail is unchanged.
JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewSessionRenderFrameParcel(
        JNIEnv* env, jclass, jlong sessionHandle, jlong clipHandle,
        jint frameIndex, jint maxDim, jfloatArray gradeParcel,
        jstring lutPath, jfloat lutStrength,
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer,
        jboolean warpActive, jfloatArray warpM, jint statsMode,
        jboolean cropActive, jfloatArray cropParams,
        jboolean zebraOn, jint zebraThreshold, jint zebraPeriod,
        jfloat zebraAlpha) {
    gles::DevelopGrade grade;
    std::string parcelError;
    if (!readGradeParcel(env, gradeParcel, &grade, &parcelError)) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageFrameRead,
                 parcelError);
        return nullptr;
    }
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    if (sessionHandle <= 0 || sessionHandle > kMaxPreviewSessions) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "preview session: invalid session handle "
                 "(recovery: reopen the preview)");
        return nullptr;
    }
    gles::PreviewSession* session = nullptr;
    {
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        session = gPreviewSessions[sessionHandle - 1].get();
    }
    if (session == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "preview session: session is closed "
                 "(recovery: reopen the preview)");
        return nullptr;
    }
    rawedit::Source* s = sourceFor(clipHandle);
    if (!s) return nullptr;
    try {
        // Decode + frame info on the CALLING thread, the per-stage timing
        // spans ALWAYS collected.
        using SteadyClock = std::chrono::steady_clock;
        const SteadyClock::time_point tDecode0 = SteadyClock::now();
        Entry& e = gEntries[clipHandle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();
        // Temporal NR — the temporal pair arrives IN the parcel here (the
        // decoded struct), consumed by the merge BEFORE the reduction
        // below, so the reduced mosaic samples the MERGED CFA (the honest
        // placement, decUs covers the merge).
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            grade.temporalNr, grade.temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed
        const SteadyClock::time_point tDecode1 = SteadyClock::now();
        const double decodeUs = elapsedUs(tDecode0, tDecode1);

        // The preview CFA reduction — Thumbnail.h's cell-aligned math,
        // copied per the documented-copy rule (core/ is READ-ONLY).
        const SteadyClock::time_point tCfa0 = SteadyClock::now();
        const int strideN = strideFor(info, maxDim);
        uint32_t reducedW = 0;
        uint32_t reducedH = 0;
        const std::vector<uint16_t>* reducedCfa = nullptr;
        if (strideN <= 1) {
            reducedCfa = cfa.get();
            reducedW = info.width;
            reducedH = info.height;
        } else {
            const uint32_t cellCols = info.width / 2;
            const uint32_t cellRows = info.height / 2;
            const uint32_t sampledCols = (cellCols + strideN - 1) / strideN;
            const uint32_t sampledRows = (cellRows + strideN - 1) / strideN;
            reducedW = 2 * sampledCols;
            reducedH = 2 * sampledRows;
            thread_local std::vector<uint16_t> scratch;
            scratch.assign(static_cast<size_t>(reducedW) * reducedH, 0);
            const uint32_t srcW = info.width;
            const uint32_t srcH = info.height;
            for (uint32_t ocy = 0; ocy < sampledRows; ++ocy) {
                for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                    // Source cell (ocx*N, ocy*N), verbatim 2x2 copy.
                    const uint32_t scx = ocx * static_cast<uint32_t>(strideN);
                    const uint32_t scy = ocy * static_cast<uint32_t>(strideN);
                    for (uint32_t dy = 0; dy < 2; ++dy) {
                        for (uint32_t dx = 0; dx < 2; ++dx) {
                            const uint32_t sx = 2 * scx + dx;
                            const uint32_t sy = 2 * scy + dy;
                            const uint16_t sample =
                                (sx < srcW && sy < srcH)
                                    ? (*cfa)[static_cast<size_t>(sy) * srcW + sx]
                                    : 0;
                            scratch[static_cast<size_t>(2 * ocy + dy) * reducedW +
                                    (2 * ocx + dx)] = sample;
                        }
                    }
                }
            }
            reducedCfa = &scratch;
        }
        rawedit::FrameInfo reducedInfo = info;
        reducedInfo.width = reducedW;
        reducedInfo.height = reducedH;
        const SteadyClock::time_point tCfa1 = SteadyClock::now();
        const double cfaUs = elapsedUs(tCfa0, tCfa1);

        gles::PreviewFrameRequest req;
        req.cfa = reducedCfa;
        req.info = reducedInfo;
        req.meta = &meta;
        // Stage 24 LUT + gyro warp: the resolved .cube path (per request,
        // the lutPathFor resolution), the [0,1] strength and the five
        // interpretation ordinals (-1 = the legacy shape); warpActive
        // false = the session skips draw 10 entirely (warpM may be null
        // when inactive).
        req.lutStrength = lutStrength;
        if (lutPath != nullptr) {
            const char* lutChars = env->GetStringUTFChars(lutPath, nullptr);
            if (lutChars != nullptr) {
                req.lutPath.assign(lutChars);
                env->ReleaseStringUTFChars(lutPath, lutChars);
            }
        }
        req.lutRole = lutRole;
        req.lutInGamut = lutInGamut;
        req.lutInTransfer = lutInTransfer;
        req.lutOutGamut = lutOutGamut;
        req.lutOutTransfer = lutOutTransfer;
        req.warpActive = warpActive == JNI_TRUE;
        if (warpM != nullptr) {
            env->GetFloatArrayRegion(warpM, 0, 9, req.warpM);
        }
        // The stats-readback MODE (A1 async stats, 2026-11-23): the
        // MASTER KILL SWITCH folds here, natively — the toggle can only
        // ever force OFF, never upgrade. The Kotlin side computes the
        // mode per request from scope demand + render class
        // (statsModeFor); clampStatsMode folds any out-of-range jint
        // (negative, >2) to Sync — the safe default and today's behavior.
        req.statsMode = gPreviewStatsEnabled.load(std::memory_order_relaxed)
            ? gles::clampStatsMode(statsMode)
            : gles::StatsReadbackMode::Off;
        // The A2 crop pair (2026-11-23, guide A2): the mapRect'ed parcel
        // copies onto the request beside the statsMode fold — the
        // session's planCrop reads it per render (the dims-dependent
        // geometry is native-only). A null array wears the request's
        // neutral defaults (cropActive false — the identity path), never
        // a misread: the Kotlin seam's non-null rule means null is a
        // caller contract break, and the honest answer is uncropped.
        req.cropActive = cropActive == JNI_TRUE;
        if (cropParams != nullptr) {
            env->GetFloatArrayRegion(cropParams, 0, 5, req.cropParams);
        }
        // The A3 zebra quad (2026-11-23, guide A3): copied onto the
        // request beside the crop pair — the values are Kotlin's own
        // single-sourced constants (ZebraOverlay/FrameHistogram), so
        // this bridge never edits or clamps them; the session's window
        // draw consumes the quad, the stats draw's uZebraOn=0 pin is
        // session-side. zebraOn false renders exactly the pre-A3 frame.
        req.zebraOn = zebraOn == JNI_TRUE;
        req.zebraThreshold = zebraThreshold;
        req.zebraPeriod = zebraPeriod;
        req.zebraAlpha = zebraAlpha;
        const gles::PreviewFrameResult res = session->renderFrame(req, grade);
        // The editor suppresses its CPU Image on success. A stats pack
        // (or the stats-off black 1x1 marker) cannot substitute for a
        // presented window frame. Route a failed present to CPU too.
        if (!res.ok || !res.presented) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "preview session render: " + res.detail +
                         " (recovery: the preview falls back to the CPU develop)");
            return nullptr;
        }
        // The published stats pack: [w, h, pixels...] (the SAME shape the
        // CPU preview publishes, so the Compose overlays consume either
        // path unchanged) + the 13-int measurement tail.
        const jsize n = static_cast<jsize>(res.argb8888.size());
        if (res.width == 0 || res.height == 0 ||
            n <= 0 || static_cast<size_t>(n) != res.argb8888.size()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "preview session render: empty output "
                     "(recovery: reopen the preview)");
            return nullptr;
        }
        // Clamp each span into jint range: a microsecond double from a
        // stalled frame can exceed 2^31, and a negative cast into jint is
        // UB — saturate instead (the trace prints the saturated value,
        // which is itself an honest "this span blew up" signal).
        auto spanToJint = [](double us) -> jint {
            if (!(us > 0.0)) return 0; // covers <= 0 and NaN
            constexpr double kMaxJint = 2147483647.0;
            if (us > kMaxJint) return static_cast<jint>(kMaxJint);
            return static_cast<jint>(us);
        };
        jint tail[13] = {
            static_cast<jint>(info.width), static_cast<jint>(info.height),
            static_cast<jint>(res.developW), static_cast<jint>(res.developH),
            static_cast<jint>(res.surfaceW), static_cast<jint>(res.surfaceH),
            req.statsMode,
            spanToJint(decodeUs), spanToJint(cfaUs),
            spanToJint(res.uploadUs), spanToJint(res.developUs),
            spanToJint(res.presentUs), spanToJint(res.statsUs),
        };
        constexpr jsize kTailLen = 13;
        jintArray arr = env->NewIntArray(n + 2 + kTailLen);
        if (arr == nullptr) return nullptr;
        jint head[2] = {static_cast<jint>(res.width), static_cast<jint>(res.height)};
        env->SetIntArrayRegion(arr, 0, 2, head);
        env->SetIntArrayRegion(arr, 2, n,
            reinterpret_cast<const jint*>(res.argb8888.data()));
        env->SetIntArrayRegion(arr, 2 + n, kTailLen, tail);
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "preview session render: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("preview session render: ") + e.what() +
                     " (recovery: reopen the preview)");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewSessionClose(
        JNIEnv*, jclass, jlong sessionHandle) {
    if (sessionHandle <= 0 || sessionHandle > kMaxPreviewSessions) return;
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    gPreviewSessions[sessionHandle - 1].reset();
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
//   [3] syncKind      (the sync verdict the plan rode, M5 commit b:
//                      0 = manual trim / no anchor, 1 = the anchored
//                      estimate, 2 = the optically refined offset — the
//                      stabSyncKindName mirror; the pre-M5 array ended
//                      here at [2], so existing `size >= 3` readers keep
//                      working unchanged)
//   [4] syncOffsetMs  (the offset the plan rode, ms — the timing line's
//                      offsetMs, the same number)
//   [5] syncBoundMs   (the estimate's error bound, ms — the "±N" the
//                      status line renders)
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
// One probe per settle covers every tier. THE M5 EXCEPTION: with the
// sync-refine toggle on, a probe MISS pays the once-per-key optical
// refinement (~seconds class host) — deliberately, because the probe is
// the settle-time plan builder; the renders after it serve the warmed
// cache, and the probe's [3..5] fields are exactly how the UI reads the
// refined verdict without re-running anything.
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
    // The handle's Entry plan cache rides along (the 2026-11-13 perf
    // fix): a probe at the same request + recorded dims as a previous
    // probe or render SERVES from it, and a first probe WARMS it for the
    // renders that follow — the probe is the settle-time plan builder.
    StabRequest req = stabRequestOf(stabMode, stabStrength, stabZoomLimit,
                                    stabFocalMm, stabTrimMs);
    req.asyncRefine = true;  // UI readout: never block
    rawedit::StabilizationFrame frame;
    StabMeasure measure;
    stabilizationFrameFor(req, *s, /*frameIndex=*/0, bufW, bufH, gyro,
                          &frame, &measure, &gEntries[handle - 1].stabPlan);

    const double out[6] = {
        measure.requiredZoom,
        measure.appliedZoom,
        static_cast<double>(static_cast<int>(measure.status)),
        static_cast<double>(measure.syncKind),
        measure.syncOffsetMs,
        measure.syncBoundMs,
    };
    jdoubleArray result = env->NewDoubleArray(6);
    if (result == nullptr) return nullptr;
    env->SetDoubleArrayRegion(result, 0, 6, out);
    return result;
}

// Gyro warp resolver (the GPU port's commit b) ----------------------------------
//
// The dispatch-side half of the gyro warp port: the CPU path resolves the
// plan and warps inside applyStabilization, but the GPU sessions consume the
// warp as a fragment pass whose only inputs are (warpActive, warpM[9]). This
// entry resolves the frame's stabilization plan — the IDENTICAL
// stabilizationFrameFor evaluation the CPU renders run, riding the handle's
// Entry plan cache — and returns the fragment pass's two inputs pre-folded:
//
//   [0]  active  (1.0 = draw the warp pass with M; 0.0 = skip it — the
//                 session's identity no-draw rule)
//   [1..9]  M    (row-major composite M = Hinv * S, narrowed to floats —
//                 the upload layout the session's transpose=GL_TRUE tap
//                 reproduces the CPU row-major composition with)
//
// THE FOLDS (every CPU no-op in warpFrame's fast paths, resolved here so
// the session never draws a resample that the CPU would have skipped):
// stabWarps false (Off / NoImu / Unsynced — identity H + zoom 1), a
// non-finite zoom, the byte-identical identity case (H exactly identity AND
// zoom == 1 — the CPU returns src untouched), and a singular H (matInv
// fails) all fold into active=0.0 with an identity M, exactly the honest
// no-ops applyStabilization takes. A zoom below 1 clamps to 1 (a warp may
// not magnify the frame — the CPU's own clamp).
//
// [maxDim] is the render's buffer tier (0 = full res, the export path and
// the develop readback; the preview path passes the tier's own maxDim). The
// resolver computes the dims the session will ACTUALLY warp — strideFor on
// the frame's recorded info, then the preview seam's own reduction math —
// because the pixel focal (and therefore H) derives from the buffer width:
// evaluating the plan at any other dims would hand the pass a homography
// for a different buffer. frameInfo is metadata-only — no decode, no cache
// touch.
//
// Returns nullptr when there is no verdict: a dead handle (silently — the
// caller's session render will fail on its own handle check), a null gyro
// path (stabilization off / no attachment — resolveGyro's honest no-log
// degrade) or a .gcsv parse failure (error state set, like a failing
// render). The Kotlin dispatch folds null into warpActive=false with the
// identity M — never a fabricated warp.
//
// THREADING (binding): like every session native call this MUST run on the
// session's engine thread — resolveGyro's gyro cache and the error state
// are thread_local, and the Entry plan cache it warms rides the same
// one-thread contract. The Kotlin seam confines it exactly like the render
// calls themselves.

JNIEXPORT jfloatArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeStabWarpResolve(
        JNIEnv* env, jclass, jlong handle, jstring gyroPath,
        jint stabMode, jfloat stabStrength, jfloat stabZoomLimit,
        jfloat stabFocalMm, jfloat stabTrimMs,
        jint frameIndex, jint maxDim, jboolean asyncRefine) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr; // dead session: no verdict, silently

    // The gyro resolves exactly like a render's (the probe's idiom): the
    // thread-local cache, false only on a parse failure (error set — null
    // return, the caller folds inactive), null artifacts = no log
    // attached = inactive.
    const GyroArtifacts* gyro = nullptr;
    if (!resolveGyro(env, gyroPath, &gyro)) return nullptr; // error set
    if (gyro == nullptr) return nullptr; // no log: inactive, no verdict

    // The dims the session will warp: the preview seam's own reduction
    // math over the frame's recorded info, so H is computed at the buffer
    // the fragment pass samples (the pixel focal scales with width).
    const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
    const int strideN = strideFor(info, maxDim);
    int bufW = static_cast<int>(info.width);
    int bufH = static_cast<int>(info.height);
    if (strideN > 1) {
        const int cellCols = static_cast<int>(info.width) / 2;
        const int cellRows = static_cast<int>(info.height) / 2;
        bufW = 2 * ((cellCols + strideN - 1) / strideN);
        bufH = 2 * ((cellRows + strideN - 1) / strideN);
    }
    if (bufW <= 0 || bufH <= 0) return nullptr; // no recorded dims: inactive

    // The full plan evaluation the CPU renders run, at the session's own
    // dims, riding the handle's Entry plan cache (once per key change, the
    // 2026-11-13 perf fix — the resolver runs per frame, so the cache is
    // what keeps it cheap).
    StabRequest req = stabRequestOf(stabMode, stabStrength, stabZoomLimit,
                                    stabFocalMm, stabTrimMs);
    req.asyncRefine = asyncRefine == JNI_TRUE;
    rawedit::StabilizationFrame frame;
    StabMeasure measure;
    bool planFromCache = false;
    const bool debug = gStabDebugLog.load(std::memory_order_relaxed);
    const auto planStart = std::chrono::steady_clock::now();
    stabilizationFrameFor(req, *s, frameIndex, bufW, bufH, gyro, &frame,
                         debug ? &measure : nullptr,
                         &gEntries[handle - 1].stabPlan,
                         debug ? &planFromCache : nullptr);
    const auto planEnd = std::chrono::steady_clock::now();

    // The CPU fold, decided here so the session never draws what the CPU
    // would have skipped: refusing status, non-finite zoom, the exact
    // identity (H == I AND zoom == 1 — warpFrame's byte-identical fast
    // path), and a singular H all mean no draw.
    bool active = stabWarps(frame);
    double zoom = frame.zoom;
    bool identity = true;
    for (int i = 0; i < 9; ++i) {
        const double want = i == 0 || i == 4 || i == 8 ? 1.0 : 0.0;
        if (frame.h[i] != want) {
            identity = false;
            break;
        }
    }
    if (identity && zoom == 1.0) active = false;
    if (!std::isfinite(zoom)) active = false;
    if (zoom < 1.0) zoom = 1.0; // a warp may not magnify (the CPU clamp)

    double m[9] = {1.0, 0.0, 0.0,
                   0.0, 1.0, 0.0,
                   0.0, 0.0, 1.0};
    if (active) {
        // M = Hinv * S composed once per frame in double — a documented
        // copy of rawedit::warpFrame's own composition (core/ is READ-ONLY;
        // the helper is anonymous-namespace there, so the seam transcribes
        // it rather than exposing an internal header for one caller). The
        // adjugate inverse carries the same copy note.
        double hInv[9];
        if (!stabMatInv(frame.h, hInv)) {
            active = false; // degenerate plan: the CPU's honest no-op
        } else {
            const double cx = static_cast<double>(bufW) / 2.0;
            const double cy = static_cast<double>(bufH) / 2.0;
            const double sc = 1.0 / zoom;
            for (int i = 0; i < 3; ++i) {
                m[i * 3 + 0] = hInv[i * 3 + 0] * sc;
                m[i * 3 + 1] = hInv[i * 3 + 1] * sc;
                m[i * 3 + 2] = hInv[i * 3 + 0] * (cx - cx * sc)
                             + hInv[i * 3 + 1] * (cy - cy * sc)
                             + hInv[i * 3 + 2];
            }
        }
    }

    // The honest backend marker (the same ritim-stab-timing line the CPU
    // path logs, backend=gpu): the resolver pays the plan span but ZERO
    // warp span — the resample itself runs as the session's draw 10, whose
    // cost lives in the session's developUs span, not here. warpMs=0.000
    // is the honest spelling of that split.
    if (debug) {
        const double planMs = std::chrono::duration<double, std::milli>(
            planEnd - planStart).count();
        __android_log_print(ANDROID_LOG_INFO, "ritim-stab-timing",
            "frame=%d planMs=%.3f(%s) warpMs=0.000 totalMs=%.3f "
            "sync=%s offsetMs=%.3f backend=gpu",
            frameIndex, planMs,
            planFromCache ? "cache-hit" : "computed", planMs,
            stabSyncKindName(measure.syncKind), measure.syncOffsetMs);
    }

    const float out[10] = {
        active ? 1.0f : 0.0f,
        static_cast<float>(m[0]), static_cast<float>(m[1]),
        static_cast<float>(m[2]), static_cast<float>(m[3]),
        static_cast<float>(m[4]), static_cast<float>(m[5]),
        static_cast<float>(m[6]), static_cast<float>(m[7]),
        static_cast<float>(m[8]),
    };
    jfloatArray result = env->NewFloatArray(10);
    if (result == nullptr) return nullptr;
    env->SetFloatArrayRegion(result, 0, 10, out);
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

// RGB888 -> I420 (MP4 export YUV step) -----------------------------------------
//
// Stateless entry (the nativeEncodedDevelop precedent — no handle registry):
// converts one packed row-major RGB888 frame into the tightly packed I420
// layout YuvConverter.toI420 produces (Y plane, then U, then V), via the
// byte-exact native twin in core/src/Yuv.cpp (the contract lives in
// core/include/rawedit/Yuv.h). This library links rawedit, and Yuv.cpp's
// per-source-file -ffp-contract=off (core/CMakeLists.txt) is inherited
// unchanged — the FMA hazard the header documents cannot reappear here.
// matrix is the Kotlin YuvMatrix ordinal: 0 = BT.601, else BT.709.
//
// The pipeline calls this per frame AFTER the upright rotation and crop
// (the Kotlin side owns those), so the frame is stateless input. Returns
// null only on a malformed request (empty frame / short rgb buffer) with
// the error state set; the Kotlin dispatcher then falls back to
// YuvConverter.toI420, which produces the same bytes — or the same
// require() failure — the export always had, so a native refusal never
// changes the encoded output or the failure mode. No C++ allocations on
// this path (the converter writes straight into the output array), so
// there is no bad_alloc case; JNI OOM surfaces as a null return with the
// pending Java exception.

JNIEXPORT jbyteArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeRgbToI420(
        JNIEnv* env, jclass, jbyteArray rgb, jint width, jint height,
        jint matrix) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    if (rgb == nullptr || width <= 0 || height <= 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "rgbToI420: unsupported — the frame is empty or malformed "
                 "(recovery: re-export the clip)");
        return nullptr;
    }
    if (static_cast<long long>(env->GetArrayLength(rgb)) <
        static_cast<long long>(width) * height * 3) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "rgbToI420: unsupported — the rgb buffer is smaller than the "
                 "frame (recovery: re-export the clip)");
        return nullptr;
    }
    const rawedit::RgbToYuvMatrix& m =
        matrix == 0 ? rawedit::kYuvMatrixBt601 : rawedit::kYuvMatrixBt709;
    const jsize outLen = static_cast<jsize>(rawedit::i420PackedSize(width, height));
    jbyte* in = env->GetByteArrayElements(rgb, nullptr);
    if (in == nullptr) return nullptr; // OOM pending
    jbyteArray out = env->NewByteArray(outLen);
    if (out == nullptr) {
        env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
        return nullptr; // OOM pending
    }
    jbyte* dst = env->GetByteArrayElements(out, nullptr);
    if (dst == nullptr) {
        env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
        return nullptr; // OOM pending
    }
    rawedit::rgb888ToI420Packed(
        reinterpret_cast<const uint8_t*>(in), width, height, m,
        reinterpret_cast<uint8_t*>(dst));
    env->ReleaseByteArrayElements(out, dst, 0); // write back / unpin
    env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
    return out;
}

// RGB888 -> I420, direct into the encoder Image's planes (commit (c)) ------
//
// The same byte-exact twin as nativeRgbToI420 above, writing STRAIGHT into
// the MediaCodec input Image's plane ByteBuffers (GetDirectBufferAddress —
// zero copies, zero intermediates: the per-frame packed I420 allocation and
// the Kotlin plane copy both disappear). Kotlin hands over the buffers the
// writer dequeued and the strides it read from Image.Plane; the C++ side
// fills a rawedit::YuvPlanes and calls rgb888ToI420, whose scalar/NEON
// kernels honor any rowStride/pixelStride pair (planar, NV12, padded).
//
// Returns JNI_TRUE when the planes hold the frame, JNI_FALSE when Kotlin
// must take the packed fallback (which produces the same bytes through
// YuvConverter.toI420 + the writer's copy): null/non-direct buffers,
// library-level refusals (empty frame / short rgb), or a stride geometry
// the converter cannot address (row/pixel strides < the frame's row bytes).
// A JNI_FALSE is NOT an error state: the fallback is the designed path for
// every refusal, so the error accessors stay untouched.

JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeRgbToI420Planes(
        JNIEnv* env, jclass, jbyteArray rgb, jint width, jint height,
        jint matrix, jobject yBuf, jint yStride,
        jobject uBuf, jint uStride, jint uPixelStride,
        jobject vBuf, jint vStride, jint vPixelStride) {
    if (rgb == nullptr || width <= 0 || height <= 0) return JNI_FALSE;
    if (static_cast<long long>(env->GetArrayLength(rgb)) <
        static_cast<long long>(width) * height * 3) {
        return JNI_FALSE;
    }
    uint8_t* y = static_cast<uint8_t*>(env->GetDirectBufferAddress(yBuf));
    uint8_t* u = static_cast<uint8_t*>(env->GetDirectBufferAddress(uBuf));
    uint8_t* v = static_cast<uint8_t*>(env->GetDirectBufferAddress(vBuf));
    if (y == nullptr || u == nullptr || v == nullptr) return JNI_FALSE;
    const int cw = (width + 1) / 2;
    const int ch = (height + 1) / 2;
    // Stride sanity: each row must fit inside its plane buffer, and the
    // last sample of the last row must be addressable. The writer hands
    // over the Image's strides unvalidated (they come from MediaCodec and
    // are trusted there), so this check is the single gate — a refusal is
    // the designed fallback, never an error.
    if (yStride < width || uStride < cw * uPixelStride ||
        vStride < cw * vPixelStride) {
        return JNI_FALSE;
    }
    jbyte* in = env->GetByteArrayElements(rgb, nullptr);
    if (in == nullptr) return JNI_FALSE; // OOM: take the packed fallback
    rawedit::YuvPlanes planes;
    planes.y = y;
    planes.yStride = yStride;
    planes.u = u;
    planes.uStride = uStride;
    planes.uPixelStride = uPixelStride;
    planes.v = v;
    planes.vStride = vStride;
    planes.vPixelStride = vPixelStride;
    const rawedit::RgbToYuvMatrix& m =
        matrix == 0 ? rawedit::kYuvMatrixBt601 : rawedit::kYuvMatrixBt709;
    rawedit::rgb888ToI420(
        reinterpret_cast<const uint8_t*>(in), width, height, m, planes);
    env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
    return JNI_TRUE;
}

// RGB888 rotate + aspect-fill, fused (the timeline export's frame loop) --------
//
// The byte-exact native twin of the Kotlin pair the timeline exporter's frame
// loop runs per frame: RotationState.rotateRgb888 (sensor-oriented ->
// upright, deg 0/90/180/270 CW) followed by RgbScale.aspectFill (aspect-fill
// center-crop bilinear to the plan's output frame). Those two Kotlin passes
// are the remaining per-frame allocations + walks after the YUV seam moved
// native; this entry removes both by FUSING them into one bilinear pass
// with no intermediate upright buffer:
//
//  1. Compute the aspect-fill sample position in UPRIGHT space exactly the
//     way RgbScale.bilinear does (cover scale, symmetric crop window,
//     center-of-pixel inverse mapping, clamp-to-edge via y0/y1, x0/x1 and
//     the fractional fx/fy).
//  2. Inverse-map the four integer tap coordinates through the rotation
//     into SOURCE space (the rotation is an exact lattice permutation, so
//     an integer upright tap lands on an integer source pixel — the four
//     taps and the blend are the only per-pixel work, no rotated copy).
//  3. Blend one channel per tap pair with DOUBLE math in the same order
//     Kotlin blends (top = v00 + (v01-v00)*fx; bottom likewise; v = top +
//     (bottom-top)*fy; truncate toward zero, clamp 0..255).
//
// BYTE-EXACTNESS CONTRACT vs the Kotlin pair, every rule load-bearing:
//  - normalize(deg) = ((deg % 360) + 360) % 360; 0 and any value outside
//    {0,90,180,270} are IDENTITY (RotationState's else branch returns the
//    input by reference).
//  - Upright dims swap for quarter turns (90/270), not for 0/180.
//  - scale = max(outW/uw, outH/uh) in double; scaledW = outW/scale;
//    scaledH = outH/scale; cropX = (uw - scaledW)/2; cropY likewise —
//    RgbScale.aspectFill's exact expressions.
//  - sy = cropY + (y+0.5)*scaledH/outH - 0.5 in double; y0 = (int)sy
//    clamped to [0, uh-1]; y1 = y0+1 clamped; fy = sy - y0 clamped to
//    [0,1] — same shape for sx/x0/x1/fx. (int) truncates toward zero
//    exactly like Kotlin toInt() on these never-NaN values.
//  - The 2x2 taps are computed in UPRIGHT space FIRST, then inverse-mapped;
//    the rotation maps the upright lattice onto the source lattice
//    bijectively, so a clamped upright tap always lands on a valid source
//    pixel — no second clamp is needed and none may be added (a clamp here
//    would diverge from the Kotlin pair on degenerate sizes).
//  - The blend's multiply-add chain must round EXACTLY where the Kotlin
//    double ops round: this TU is compiled with -ffp-contract=off (the
//    core/src/Yuv.cpp precedent, app/src/main/cpp/CMakeLists.txt), so the
//    compiler cannot contract v00 + (v01-v00)*fx into a single-rounding FMA
//    on arm64.
//  - Reads are unsigned (uint8_t); the final value truncates toward zero
//    and clamps to [0,255] before the byte store.
//
// mode is the reconcile policy: 0 = aspect-fill center-crop (the only
// policy v1 ships; any other value is a refusal, never a guess). The
// degenerate pass — identity rotation AND upright dims equal to the
// output frame — returns the INPUT array object (the Kotlin pair's
// by-reference identity, matched so the caller's aliasing stays identical).
//
// Returns null only on a malformed request (null rgb, non-positive dims,
// short rgb buffer, unknown mode) with the error state set; the Kotlin
// dispatcher then falls back to the unchanged Kotlin pair, which produces
// the same bytes — or the same require() failure the export always had.
// No C++ allocations on the hot path beyond the output array itself; JNI
// OOM surfaces as a null return with the pending Java exception.

// Upright (ux,uy) -> source (sx,sy) for each rotation; the inverse of the
// forward index math in RotationState.rotateRgb888. Rotation is a lattice
// bijection, so integer in stays integer out and bounds-clamped upright
// taps map to valid source pixels.
static inline void rgbRotateScaleInverseMap(
        int deg, int inW, int inH, int ux, int uy, int* sx, int* sy) {
    switch (deg) {
        case 90:  *sx = uy;         *sy = inH - 1 - ux; break;
        case 180: *sx = inW - 1 - ux; *sy = inH - 1 - uy; break;
        case 270: *sx = inW - 1 - uy; *sy = ux;         break;
        default:  *sx = ux;        *sy = uy;           break;
    }
}

// One channel's bilinear blend, the exact RgbScale.blend order and rounding.
static inline uint8_t rgbRotateScaleBlend(
        const uint8_t* rgb, int inW,
        int s00x, int s00y, int s01x, int s01y,
        int s10x, int s10y, int s11x, int s11y,
        int channel, double fx, double fy) {
    const int p00 = (s00y * inW + s00x) * 3 + channel;
    const int p01 = (s01y * inW + s01x) * 3 + channel;
    const int p10 = (s10y * inW + s10x) * 3 + channel;
    const int p11 = (s11y * inW + s11x) * 3 + channel;
    const double v00 = rgb[p00];
    const double v01 = rgb[p01];
    const double v10 = rgb[p10];
    const double v11 = rgb[p11];
    const double top = v00 + (v01 - v00) * fx;
    const double bottom = v10 + (v11 - v10) * fx;
    const double v = top + (bottom - top) * fy;
    int iv = static_cast<int>(v); // truncation toward zero, like toInt()
    if (iv < 0) iv = 0;
    if (iv > 255) iv = 255;
    return static_cast<uint8_t>(iv);
}

JNIEXPORT jbyteArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeRgbRotateScale(
        JNIEnv* env, jclass, jbyteArray rgb, jint inW, jint inH,
        jint orientationDeg, jint outW, jint outH, jint mode) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    if (rgb == nullptr || inW <= 0 || inH <= 0 || outW <= 0 || outH <= 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "rgbRotateScale: unsupported — the frame is empty or malformed "
                 "(recovery: re-export the clip)");
        return nullptr;
    }
    if (mode != 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "rgbRotateScale: unsupported — unknown scale mode "
                 "(recovery: re-export the clip)");
        return nullptr;
    }
    if (static_cast<long long>(env->GetArrayLength(rgb)) <
        static_cast<long long>(inW) * inH * 3) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "rgbRotateScale: unsupported — the rgb buffer is smaller than "
                 "the frame (recovery: re-export the clip)");
        return nullptr;
    }
    // RotationState.normalize, then identity for anything but quarter/half
    // turns (the Kotlin else branch is identity too).
    int deg = orientationDeg % 360;
    if (deg < 0) deg += 360;
    const bool quarter = (deg == 90 || deg == 270);
    const bool half = (deg == 180);
    // Upright dims: swapped for quarter turns (RotationState.rotatedDims).
    const int uw = quarter ? inH : inW;
    const int uh = quarter ? inW : inH;
    // Degenerate pass: identity rotation AND exact size — the Kotlin pair
    // returns the input by reference at BOTH steps (rotateRgb888's 0/else
    // branch, then aspectFill's identity fast path), so the native twin
    // hands the SAME array object back (no copy, no allocation).
    if (!quarter && !half && uw == outW && uh == outH) {
        return rgb;
    }
    // Non-degenerate: real rotation or a real scale (or both).
    const jsize outLen = static_cast<jsize>(
        static_cast<long long>(outW) * outH * 3);
    jbyte* in = env->GetByteArrayElements(rgb, nullptr);
    if (in == nullptr) return nullptr; // OOM pending
    jbyteArray out = env->NewByteArray(outLen);
    if (out == nullptr) {
        env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
        return nullptr; // OOM pending
    }
    jbyte* dst = env->GetByteArrayElements(out, nullptr);
    if (dst == nullptr) {
        env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
        return nullptr; // OOM pending
    }
    const uint8_t* src = reinterpret_cast<const uint8_t*>(in);
    uint8_t* o = reinterpret_cast<uint8_t*>(dst);
    // RgbScale.aspectFill's cover scale and symmetric crop window, in
    // UPRIGHT dims, all double math.
    const double scale = std::max(
        static_cast<double>(outW) / uw, static_cast<double>(outH) / uh);
    const double scaledW = outW / scale;
    const double scaledH = outH / scale;
    const double cropX = (uw - scaledW) / 2.0;
    const double cropY = (uh - scaledH) / 2.0;
    int oi = 0;
    for (int y = 0; y < outH; y++) {
        const double sy = cropY + (y + 0.5) * scaledH / outH - 0.5;
        int uy0 = static_cast<int>(sy);
        if (uy0 < 0) uy0 = 0;
        if (uy0 > uh - 1) uy0 = uh - 1;
        int uy1 = uy0 + 1;
        if (uy1 < 0) uy1 = 0;
        if (uy1 > uh - 1) uy1 = uh - 1;
        double fy = sy - uy0;
        if (fy < 0.0) fy = 0.0;
        if (fy > 1.0) fy = 1.0;
        for (int x = 0; x < outW; x++) {
            const double sx = cropX + (x + 0.5) * scaledW / outW - 0.5;
            int ux0 = static_cast<int>(sx);
            if (ux0 < 0) ux0 = 0;
            if (ux0 > uw - 1) ux0 = uw - 1;
            int ux1 = ux0 + 1;
            if (ux1 < 0) ux1 = 0;
            if (ux1 > uw - 1) ux1 = uw - 1;
            double fx = sx - ux0;
            if (fx < 0.0) fx = 0.0;
            if (fx > 1.0) fx = 1.0;
            // Inverse-map the four upright taps into source coords.
            int s00x, s00y, s01x, s01y, s10x, s10y, s11x, s11y;
            rgbRotateScaleInverseMap(deg, inW, inH, ux0, uy0, &s00x, &s00y);
            rgbRotateScaleInverseMap(deg, inW, inH, ux1, uy0, &s01x, &s01y);
            rgbRotateScaleInverseMap(deg, inW, inH, ux0, uy1, &s10x, &s10y);
            rgbRotateScaleInverseMap(deg, inW, inH, ux1, uy1, &s11x, &s11y);
            o[oi++] = rgbRotateScaleBlend(
                src, inW, s00x, s00y, s01x, s01y, s10x, s10y, s11x, s11y, 0, fx, fy);
            o[oi++] = rgbRotateScaleBlend(
                src, inW, s00x, s00y, s01x, s01y, s10x, s10y, s11x, s11y, 1, fx, fy);
            o[oi++] = rgbRotateScaleBlend(
                src, inW, s00x, s00y, s01x, s01y, s10x, s10y, s11x, s11y, 2, fx, fy);
        }
    }
    env->ReleaseByteArrayElements(out, dst, 0); // write back / unpin
    env->ReleaseByteArrayElements(rgb, in, JNI_ABORT);
    return out;
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
        jint bitDepth, jboolean fullRange, jint matrix, jint transfer, jint primaries,
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
        jint lutRole, jint lutInGamut, jint lutInTransfer,
        jint lutOutGamut, jint lutOutTransfer) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    // Resolve the stage-24 LUT first (COL-07): a parse failure fails the
    // develop with the lut error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    // Resolve the managed binding (2026-11-13): all five -1 = the legacy
    // stage-24 pair, byte-identical.
    const rawedit::CompiledLutBinding* binding = nullptr;
    resolveBinding(lut, lutRole, lutInGamut, lutInTransfer,
                   lutOutGamut, lutOutTransfer, &binding);
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
    // E0: 10-bit frames carry 16-bit little-endian MSB-aligned words, so
    // every sample is two bytes and strides stay in BYTES.
    if (bitDepth != 8 && bitDepth != 10) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the decoded frame's bit depth is not 8 or 10 "
                 "(recovery: re-import the clip)");
        return nullptr;
    }
    const int bps = bitDepth > 8 ? 2 : 1;
    const int s = stride > 0 ? stride : width * bps;
    const int us = uvStride > 0 ? uvStride : ((width + 1) / 2) * (semiPlanar != JNI_FALSE ? 2 : 1) * bps;

    rawedit::YuvFrame frame;
    frame.width = static_cast<uint32_t>(width);
    frame.height = static_cast<uint32_t>(height);
    frame.semiPlanar = semiPlanar != JNI_FALSE;
    frame.stride = s;
    frame.uvStride = us;
    frame.bitDepth = bitDepth;
    frame.fullRange = fullRange != JNI_FALSE;
    frame.matrix = matrix == 0 ? rawedit::YuvMatrix::BT601
                 : matrix == 2 ? rawedit::YuvMatrix::BT2020
                 : rawedit::YuvMatrix::BT709;
    frame.transfer = transfer == 1 ? rawedit::EncodedTransfer::Rec709
                   : transfer == 2 ? rawedit::EncodedTransfer::Srgb
                   : transfer == 3 ? rawedit::EncodedTransfer::Linear
                   : rawedit::EncodedTransfer::MiLog;
    frame.primaries = primaries == 1 ? rawedit::EncodedPrimaries::BT2020
                                     : rawedit::EncodedPrimaries::BT709;

    // Size check before any copy: the develop indexes y up to
    // (height-1)*stride + width-1 and the chroma grid up to
    // (ch-1)*uvStride + (cw-1)*2 (+1 for the interleaved V byte).
    const long long cw = (static_cast<long long>(width) + 1) / 2;
    const long long ch = (static_cast<long long>(height) + 1) / 2;
    const long long yNeed = static_cast<long long>(height - 1) * s + static_cast<long long>(width) * bps;
    const long long cNeed = frame.semiPlanar
        ? static_cast<long long>(ch - 1) * us + ((cw - 1) * 2 + 2) * bps
        : static_cast<long long>(ch - 1) * us + cw * bps;
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
            isBt709 != JNI_FALSE, lut, binding);
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
