// rawedit::Gyro — GCSV gyro log parser (spec: stabilization input).
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
// That is: a signature line, key,value header lines, a column header
// (t,gx,gy,gz,ax,ay,az), then one row per sample. Each row is a 0-based tick
// index t followed by seven integer sensor readings and a trailing comma
// (8 comma-separated fields, the last empty). t is strictly increasing and
// contiguous in both fixtures (0..5499 and 0..5443).
//
// Units (GyroFlow convention, verified against the fixtures' sample rates):
//   timestampNs = round(t * tscale * 1e9)   tscale is seconds per tick
//                                              (0.0021144340 s ~ 472.9 Hz)
//   x/y/z        = gx/gy/gz * gscale        gscale is rad/s per count
//                                              (1e-8 in both fixtures)
// The ax/ay/az accelerometer columns are validated as part of the row shape
// only when present and are NOT exposed — the stabilization pass consumes
// angular velocity. Samples come back in file order with strictly increasing
// timestamps (a backwards or repeated tick is rejected as malformed).
//
// Errors never throw: every failure is a Result<GyroData> with an ErrorCode
// and a detail string naming the offending line.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <rawedit/Result.h>

namespace rawedit {

// One angular-velocity sample in the file's declared axis orientation
// (GyroData.orientation; no axis remapping is applied here).
struct GyroSample {
    int64_t timestampNs = 0; // t * tscale, nanoseconds since log start
    float x = 0.0f;          // rad/s
    float y = 0.0f;          // rad/s
    float z = 0.0f;          // rad/s
};

struct GyroData {
    std::vector<GyroSample> samples;
    std::string orientation; // header "orientation" value, e.g. "XYZ"

    size_t sampleCount() const { return samples.size(); }
    // First/last sample timestamps; 0 when there are no samples.
    int64_t firstTs() const { return samples.empty() ? 0 : samples.front().timestampNs; }
    int64_t lastTs() const { return samples.empty() ? 0 : samples.back().timestampNs; }
    // Inclusive time span in nanoseconds; 0 when there are no samples.
    int64_t spanNs() const { return lastTs() - firstTs(); }
};

// Parses a GCSV file. IoError when the path cannot be opened; parse failures
// surface from the stream overload below.
Result<GyroData> parseGcsv(const std::string& path);

// Stream overload (tests, or callers that already hold the bytes).
//   Truncated  — stream ended before the signature or the column header,
//                or held no data rows at all
//   BadHeader  — wrong signature line, malformed key,value header, missing
//                tscale/gscale, or a malformed data row (detail names the
//                line number)
Result<GyroData> parseGcsv(std::istream& in);

} // namespace rawedit
