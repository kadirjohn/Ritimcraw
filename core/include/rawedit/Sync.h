// rawedit::Sync — frame↔IMU clock bridge (stabilization Phase 3).
//
// THE PROBLEM (measured on the real fixtures, STEPS/media/test/261110_004150
// and 261110_004208, .mcraw + sidecar .gcsv pairs from MotionCam Pro 5.0.9
// on a Xiaomi 15 Ultra):
//
//   - MCRAW frame timestamps are NANOSECONDS on the phone's monotonic
//     clock (SystemClock.elapsedRealtimeNanos semantics; the container's
//     timestampSource=1 "REALTIME" says they are comparable to other
//     subsystems' timestamps on the SAME boot). Fixture A: first frame
//     258158030289578 ns, last 258168310276870 (310 frames, span
//     10.279987292 s). The per-frame pts the app surfaces
//     (FrameInfo.ptsUs, PtsTimeline) is exactly captureNs/1000 — the SAME
//     clock, integer-divided to microseconds; there is no separate pts
//     timeline (Source.cpp: "Frame timestamps are nanoseconds ...
//     FrameInfo carries microseconds").
//   - The sidecar GCSV's t column is a 0-based tick index on the GCSV's
//     OWN clock: tsNs = round(t * tscale * 1e9), 0 = log start. The GCSV
//     header "timestamp" key is EPOCH MS wall time of the log start
//     (004150: 1794260510503 = 2026-11-10 00:41:50.503 local, matching
//     the file name; 004208: 1794260528447 = 00:42:08.447).
//   - The two clocks are INCOMPARABLE without a bridge: monotonic ns vs
//     tick index, different origins, no shared epoch. And the streams are
//     not co-extensive: the gyro log (11.627 s / 11.515 s) is ~1.2-1.3 s
//     LONGER than the video (10.280 s) — MotionCam starts the IMU log
//     before the first frame and stops it after the last.
//   - The FIRST gyro sample therefore does NOT coincide with the first
//     frame (owner rule: "GCSV'deki ilk gyro sample'ın ilk video frame'iyle
//     tam olarak eşleştiğini varsayma"), and a zero-offset assumption is
//     wrong by construction: at offset 0 the frames start ~1.1 s "after"
//     the log's t=0, which is an artifact of the log's head start, not a
//     measurement.
//
// THE WALL-CLOCK ANCHOR (what makes an anchored estimate possible):
//   each MCRAW frame's per-frame JSON carries "recvdTimestampMs" — epoch
//   milliseconds, the app-side receive time of that frame (004150 frame 0:
//   1794260510870; 004208 frame 0: 1794260528798). telemetry-parser reads
//   exactly this key (src/motioncam/mod.rs:177) and exposes it as the
//   CaptureTimestamp tag (:261) — it is the established wall anchor for
//   MotionCam files, not an ritiMCRAW invention. The GCSV header supplies
//   the wall time of the other side (log start). Two wall timestamps over
//   one shared epoch = an offset estimate:
//
//     anchorOffsetNs = (frame0RecvdMs - gcsvLogStartMs) * 1e6 - leadNs
//
//   where leadNs = logStart→firstGyroSample... is ZERO by definition (the
//   first gyro sample IS the log start, t=0); see estimateOffsetFromAnchors
//   for the exact statement. The estimate maps the frame clock onto the
//   gyro clock THROUGH the wall epoch: the receive lag (sensor capture →
//   app receive, unobservable per-frame) shifts the anchor by its lag
//   value, and the wall clocks themselves are ms-quantized.
//
//   MEASURED on the two fixtures: naive anchor offset (no receive-lag
//   correction) = +367 ms (004150) and +351 ms (004208). Cross-clip check:
//   frame-0 wall-minus-monotonic agrees to 3.76 ms between the two clips
//   recorded 18 s apart on the same boot — the first-frame receive lag is
//   stable, so the anchor method is repeatable. Error budget: header ms
//   quantization (±0.5 ms) + recvd ms quantization (±0.5 ms) + first-frame
//   receive lag (unknown constant; within-clip receive jitter measured at
//   −13.2..+3.5 ms and −7.2..+7.7 ms, so the constant lag plausibly sits
//   inside a ±20 ms band) → documented bound ±25 ms unless a tighter one
//   is measured. ±25 ms at 30 fps is ±0.75 frame — fine for a coarse
//   default, refined by the manual offset (Phase 4 UI) or later optical
//   sync. When either anchor is missing the verdict is MANUAL-REQUIRED and
//   NO offset is fabricated.
//
// WHAT IS NOT HERE: optical sync (needs decoded frames; Phase 5+) and any
// JNI/UI exposure (Phase 4). Embedded-MCRAW IMU needs no bridge at all —
// it shares the frame clock by construction (Gyro.h) — so this model is
// for the SIDE-CAR GCSV case.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace rawedit {

// ---------------------------------------------------------------------------
// The clock bridge (deliverable 1). Dead simple on purpose:
//
//     imuTimestampNs = frameCaptureNs + offsetNs
//
// SIGN CONVENTION (binding): offsetNs is ADDED to the frame clock to reach
// the IMU clock. Positive = the IMU clock reads later than the frame clock
// at the same physical instant (e.g. the IMU log's origin sits "before"
// the first frame, so the same instant carries a LARGER imu ts than frame
// ts — the anchored fixtures measure exactly this, +0.35-0.37 s).
//
// The offset folds in everything: clock-origin difference, rate drift over
// a clip (both clocks are quartz-stable over ~12 s; drift is sub-ms and
// NOT modeled — a single constant is the whole bridge), and any user trim.
// There is deliberately no rate/skew term.
// ---------------------------------------------------------------------------
struct SyncModel {
    int64_t offsetNs = 0;

    // frame capture timestamp (ns) -> IMU-clock timestamp (ns)
    int64_t toImu(int64_t frameCaptureNs) const { return frameCaptureNs + offsetNs; }
    // inverse: IMU-clock timestamp (ns) -> frame capture timestamp (ns)
    int64_t toFrame(int64_t imuTimestampNs) const { return imuTimestampNs - offsetNs; }

    // Round-trip helpers for callers holding microseconds (the app's
    // FrameInfo.ptsUs) or milliseconds (the UI's manual offset field).
    static constexpr int64_t nsPerUs = 1000;
    static constexpr int64_t nsPerMs = 1000000;
    static SyncModel fromOffsetMs(double offsetMs);
    double offsetMs() const;
};

// ---------------------------------------------------------------------------
// Confidence of an offset estimate (deliverable 3: the estimate function
// returns candidates with confidence; UNKNOWN -> manual required).
// ---------------------------------------------------------------------------
enum class SyncConfidence {
    Anchored,      // wall-clock anchors on both sides; ±tens-of-ms class
    ManualRequired // no anchor (or anchors unusable); user must set offset
};

// One offset candidate. source names how it was obtained (for the Phase 4
// UI's status line): "anchored:recvdMs-vs-gcsvHeader", "manual", "zero".
struct SyncEstimate {
    SyncModel model;
    SyncConfidence confidence = SyncConfidence::ManualRequired;
    std::string source;   // provenance string above
    double errorBoundMs = 0.0; // symmetric bound, 0 when unknown (manual)

    bool isAnchored() const { return confidence == SyncConfidence::Anchored; }
};

// ---------------------------------------------------------------------------
// Method A — anchored estimate from the two wall clocks (deliverable 2).
//
// Inputs (all read from the files, never guessed):
//   frame0WallMs   first frame's recvdTimestampMs (epoch ms; the app-side
//                  receive time of frame 0). From frame 0's per-frame JSON.
//   gyroStartWallMs the GCSV header "timestamp" key (epoch ms; log start,
//                  which is the first gyro sample's wall time — t=0).
//   frame0CaptureNs frame 0's container timestamp (ns, monotonic clock).
//
// Construction: express the first gyro sample's instant in the FRAME
// clock. Its wall time is gyroStartWallMs; frame 0's wall time is
// frame0WallMs (up to the receive lag, see the header); so the first gyro
// sample happened (gyroStartWallMs - frame0WallMs) ms of wall time BEFORE
// frame 0 was received, i.e. its frame-clock timestamp is approximately
//
//     gyroTsOnFrameClock ≈ frame0CaptureNs + (gyroStartWallMs-frame0WallMs)*1e6
//
// and offsetNs ≡ imuTs - frameTs at the same instant = 0 - that value:
//
//     offsetNs = (frame0WallMs - gyroStartWallMs) * 1e6 - frame0CaptureNs
//
// (expand: toImu(frame0CaptureNs) must land at the gyro clock's 0 for the
// log-start instant). The formula is one line; everything interesting is
// the error budget, which errorBoundMs carries.
//
// Returns ManualRequired (not a fabricated number) when either anchor is
// absent (<= 0 or NaN) — the caller then needs the manual path.
SyncEstimate estimateOffsetFromAnchors(int64_t frame0WallMs,
                                       int64_t gyroStartWallMs,
                                       int64_t frame0CaptureNs);

// ---------------------------------------------------------------------------
// Method C — manual offset (deliverable 3). The user-facing fallback (and
// the trim control over an anchored estimate in Phase 4's UI). The
// estimate is a passthrough with ManualRequired confidence unless a trim
// over an anchored estimate is requested; trims keep the Anchored verdict
// but widen the bound by |trimMs| (the user's own uncertainty).
SyncEstimate manualOffsetMs(double offsetMs);
SyncEstimate trimOffsetMs(const SyncEstimate& base, double trimMs);

// ---------------------------------------------------------------------------
// Frame mapping (deliverable 4). One frame -> one IMU timestamp, with the
// overlap bookkeeping the stabilization stage needs.
//
// VFR and dropped frames need nothing special here: each frame maps
// independently through the same constant offset (no fixed-rate
// assumption exists to violate); a dropped frame is simply absent from
// frameCaptureNs. The IMU side interpolates (OrientationSeries::
// orientationAt SLERPs between bracketing gyro samples), so what matters
// is only which frames fall INSIDE the gyro span. Frames outside it clamp
// to the end quaternions (OrientationEstimate.h) — those frames are
// flagged here so Phase 4 can decide (crop-to-overlap, or hold the end
// orientation) instead of silently warping with a held quaternion.
// ---------------------------------------------------------------------------
struct FrameSync {
    std::vector<int64_t> imuTimestampNs; // per frame: toImu(frameCaptureNs)
    // Frames with imuTimestampNs outside [gyroFirstTs, gyroLastTs] clamp
    // in orientationAt; counts and the contiguous INSIDE window:
    size_t frameCount = 0;
    size_t beforeGyroStart = 0; // leading frames whose imuTs < gyroFirstTs
    size_t afterGyroEnd = 0;    // trailing frames whose imuTs > gyroLastTs
    // Inclusive indices of the first/last frame inside the gyro span;
    // npos when no frame is inside (gyro span misses the video entirely).
    static constexpr size_t npos = static_cast<size_t>(-1);
    size_t firstInside = npos;
    size_t lastInside = npos;

    bool empty() const { return frameCount == 0; }
    // Fraction of frames whose imuTs lies inside the gyro span [0,1].
    double insideFraction() const;
};

// Maps all frames through `model` against the gyro span [gyroFirstTs,
// gyroLastTs] (ImuData::firstTs()/lastTs(); both are gyro-stream extents).
// The gyro span must be a real span (gyroLastTs >= gyroFirstTs, at least
// one sample implied); an empty/inverted span yields an all-outside result
// (firstInside stays npos) rather than an error — the caller reports it.
//
// Requires frameCaptureNs sorted ascending (containers emit them in time
// order; Source returns them that way). BEFORE/AFTER counting assumes the
// sort: before-frames are a prefix, after-frames a suffix. Unsorted input
// is a caller bug; the counts would still be internally consistent with
// the flags' definitions (imuTs comparisons), only the prefix/suffix
// phrasing would be wrong.
FrameSync framesToImu(const std::vector<int64_t>& frameCaptureNs,
                      const SyncModel& model,
                      int64_t gyroFirstTs, int64_t gyroLastTs);

} // namespace rawedit
