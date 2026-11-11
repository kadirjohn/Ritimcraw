// rawedit::Gyro — canonical IMU model + GCSV / embedded-MCRAW loaders
// (stabilization Phase 1: parse + normalize only; no orientation math, no
// axis remap, no sync — those are Phase 2/3).
//
// The real fixtures (STEPS/media/test/261110_004150_VIDEO_24mm.gcsv and
// 261110_004208_VIDEO_24mm.gcsv, written by MotionCam in the GyroFlow IMU
// LOG convention) look like this:
//
//   GYROFLOW IMU LOG
//   version,1.2
//   id,Xiaomi.25010PN30G
//   orientation,XYZ
//   fwversion,5.0.9-pro
//   timestamp,1794260510503
//   vendor,MotionCam
//   tscale,0.0021144340
//   gscale,0.0000000100
//   ascale,0.0000000100
//   t,gx,gy,gz,ax,ay,az
//   0,1138872,5122492,1610019,721675301,25842485,669990349,
//   1,1237001,5006425,1511890,728949640,29934289,665443910,
//   ...
//
// A signature line, key,value header lines, a column header
// (t,gx,gy,gz,ax,ay,az), then one row per sample: a 0-based tick index t,
// integer sensor readings and a trailing comma (8 comma-separated fields,
// the last empty).
//
// UNIT CHAIN (the critical part — cross-checked against the official GCSV
// spec, telemetry-parser gcsv.rs @9350bc2, and the fixtures themselves; the
// executable form is in test_gyro.cpp "scale chain" tests):
//
//   timestampNs = round(t * tscale * 1e9)   tscale: seconds per tick
//                                            (0.0021144340 s ~ 472.9 Hz)
//   gyro  xyz   = gx/gy/gz * gscale          gscale: rad/s per count
//   accel xyz   = ax/ay/az * ascale          ascale: m/s^2 per count
//
// Gyro: the official spec (docs.gyroflow.xyz) says gscale gives rad/s.
// telemetry-parser computes an intermediate gyro_scale = 1/gscale * PI/180
// tagged "deg/s", then its util.rs into_scaled multiplies by 180/PI when
// converting "deg/s" — the factors cancel, so the net is raw * gscale in
// rad/s. This parser applies gscale exactly once.
//
// Accel: the official spec says ascale gives "g" units, but MotionCam files
// are NOT in g. Proof from the fixtures: raw*ascale magnitudes cluster at
// gravity (mean |a| = 9.855 and 9.701 m/s^2 in the two files) — as g that
// would be a sustained ~9.86 g handheld, physically impossible.
// Corroboration: telemetry-parser tags MotionCam accelerometer data
// "m/s^2", and the vendored decoder's Decoder.hpp documents
// loadAccelerometerData values as "m/s^2 including gravity". Canonical
// accel is therefore m/s^2, gravity included.
//
// CANONICAL MODEL — two streams, not one merged sample per timestamp:
// gyro and accelerometer are independent streams in the MCRAW container
// (separate blocks, separate rates, separate timestamps), and Phase 2's
// orientation filter (VQF) consumes gyro and accel as separate arrays.
// A single merged sample type would fabricate alignment that the embedded
// format does not guarantee. For GCSV logs the two streams are index-locked
// (row i produced both; aligned() is true); for MCRAW they are not.
//
// Errors never throw: every failure is a Result<ImuData> with an ErrorCode
// and a detail string naming the offending line or sample.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <rawedit/Result.h>

namespace rawedit {

// One timestamped triple. Units depend on the stream it lives in:
// ImuData::gyro is rad/s, ImuData::accel is m/s^2 (gravity included).
// Axes are the source's declared orientation (ImuData::orientation);
// no remapping is applied here.
struct ImuSample {
    int64_t timestampNs = 0;
    float x = 0.0f;
    float y = 0.0f;
    float z = 0.0f;
};

// A parsed IMU log. gyro is always the primary stream; accel is present
// when the source carried accelerometer data (hasAccel()).
struct ImuData {
    std::vector<ImuSample> gyro;  // rad/s
    std::vector<ImuSample> accel; // m/s^2 incl. gravity; empty when absent

    // Raw "orientation" key from the GCSV header (e.g. "XYZ"). Empty for
    // MCRAW, whose container has no such key — the remap there is derived
    // from sensorOrientation in Phase 2, never guessed here.
    std::string orientation;

    // Raw MCRAW container metadata keys, -1 when absent (GCSV always).
    //   timestampSource: Android SENSOR_INFO_TIMESTAMP_SOURCE semantics
    //     (telemetry-parser motioncam.rs @9350bc2): 0 = UNKNOWN, "frame
    //     clock can not be compared to timestamps from other subsystems";
    //     1 = REALTIME (SystemClock.elapsedRealtimeNanos), comparable.
    //     The real fixtures record 1.
    //   sensorOrientation: degrees CW to upright (fixtures record 90).
    //   lensFacing: Android LENS_FACING semantics (telemetry-parser
    //     motioncam.rs @9350bc2 reads it alongside sensorOrientation):
    //     0 = front, 1 = back; -1 = absent (treated as back, the common
    //     case and tp's own default). The Phase 2 axis remap needs it to
    //     pick the front-camera variant of the sensor-orientation
    //     rotation.
    int timestampSource = -1;
    int sensorOrientation = -1;
    int lensFacing = -1;
    // GCSV header "timestamp" (stabilization Phase 3/5): the epoch-ms
    // wall time of the log start — the gyro-side anchor the Sync.h
    // estimate pairs with the container's frame-0 recvdTimestampMs
    // (Source.h's FrameInfo.recvdMs). -1 = the key is absent or not a
    // positive integer (absent is the honest state: sync degrades to
    // ManualRequired, never fabricated). Optional by design — the
    // key is not in the GCSV spec's required set, so a file without it
    // still parses.
    int64_t logStartWallMs = -1;

    bool hasAccel() const { return !accel.empty(); }
    // True when gyro and accel are index-locked with pairwise equal
    // timestamps (always the case for GCSV logs with accel columns).
    bool aligned() const;

    // Gyro-stream extent (the primary stream); 0 when there are no samples.
    int64_t firstTs() const;
    int64_t lastTs() const;
    int64_t spanNs() const;   // lastTs - firstTs (inclusive)
    int64_t periodNs() const; // span / (n-1); 0 when fewer than 2 samples
    size_t sampleCount() const { return gyro.size(); }
};

// ---------------------------------------------------------------------------
// GCSV (GyroFlow IMU LOG)
//
// Header policy (conservative, tested):
//   - signature must be "GYROFLOW IMU LOG" (telemetry-parser also accepts
//     "CAMERA IMU LOG"; not accepted here — no fixture uses it)
//   - tscale and gscale are required and must be > 0
//   - ascale is required iff any data row carries accelerometer columns;
//     a gyro-only log (t,gx,gy,gz,) needs no ascale
//   - "timestamp" (epoch ms, optional) is carried into
//     ImuData.logStartWallMs — the sync anchor; a non-positive or
//     non-integer value keeps the -1 sentinel (never fabricated)
//   - unknown header keys are ignored; a key without a value is malformed
//   - the column header must be exactly "t,gx,gy,gz,ax,ay,az"
//
// Row policy:
//   - blank lines are skipped anywhere after the signature
//   - the first data row fixes the shape: either 4 fields (t,gx,gy,gz,
//     gyro-only) or 7/8 fields (accel columns; the 8th is the empty field
//     of the trailing comma). Every later row must repeat that shape —
//     a file that switches mid-stream is rejected
//   - all fields are strict integers (sign allowed); t must strictly
//     increase: a repeated or backwards tick is rejected as malformed
//     (the fixtures are strictly increasing tick runs)
// ---------------------------------------------------------------------------

// Parses a GCSV file. IoError when the path cannot be opened; parse failures
// surface from the stream overload below.
Result<ImuData> parseGcsv(const std::string& path);

// Stream overload (tests, or callers that already hold the bytes).
//   Truncated  — stream ended before the signature or the column header,
//                or held no data rows at all
//   BadHeader  — wrong signature line, malformed key,value header, missing
//                tscale/gscale/ascale, or a malformed data row (detail
//                names the line number)
Result<ImuData> parseGcsv(std::istream& in);

// ---------------------------------------------------------------------------
// Embedded MCRAW IMU
//
// The vendored motioncam-decoder (third_party/motioncam-decoder,
// Apache-2.0, commit b931ec0e — byte-identical to upstream
// mirsadm/motioncam-decoder at that commit) exposes the embedded IMU as two
// independent streams:
//   hasGyroData() / loadGyroData()            rad/s, MotionSample.timestampNs
//   hasAccelerometerData() / loadAccelerometerData()
//                                             m/s^2 incl. gravity
// Both share the container's nanosecond timeline with getFrames() frame
// timestamps (Decoder.hpp). Whether that timeline is comparable to other
// clocks is decided by the container's timestampSource key (recorded in
// ImuData::timestampSource; see above).
//
// This loader opens its own read-only Decoder on the file — no frames are
// decoded. A separate FILE* is safe: the decoder holds no cross-file state.
//
//   IoError     — the path cannot be opened
//   BadHeader   — not an MCRAW (bad id/version), or a stream whose
//                 timestamps jump backwards (corrupt index)
//   Truncated   — container structure error mid-file (mapped like
//                 Source.cpp classifyError)
//   Unsupported — the container has neither gyro nor accelerometer data
//                 (the real fixtures are exactly this case)
//
// Ordering policy: samples are accepted in non-decreasing timestamp order
// (equal timestamps kept — resampling is Phase 2); a backwards jump is
// rejected as corruption. The real fixtures carry no embedded IMU, so the
// populated path is verified against a synthetic container built the same
// way the vendored decoder's own DecoderGyroTest.cpp builds one.
// ---------------------------------------------------------------------------

Result<ImuData> parseImuFromMcraw(const std::string& path);

} // namespace rawedit
