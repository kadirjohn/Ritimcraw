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
// WHAT IS NOT HERE: optical sync (needs decoded frames; Phase 5+), the
// anchored wall-clock estimator and frame mapping (the rest of Phase 3,
// landing in this header next), and any JNI/UI exposure (Phase 4).
// Embedded-MCRAW IMU needs no bridge at all — it shares the frame clock
// by construction (Gyro.h) — so this model is for the SIDE-CAR GCSV case.

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
// Method C — manual offset (deliverable 3). The user-facing fallback; the
// anchored estimator and the trim control land in the next commit of this
// file. The estimate is a passthrough with ManualRequired confidence.
// ---------------------------------------------------------------------------
SyncEstimate manualOffsetMs(double offsetMs);

} // namespace rawedit
