// rawedit::Source — thin wrapper around the vendored motioncam-decoder
// (third_party/motioncam-decoder, Apache-2.0, commit b931ec0e).
//
// Wrap, never re-implement: this class owns one motioncam::Decoder instance
// and maps its exceptions onto rawedit::ErrorCode. No decoder exception ever
// escapes the rawedit API.
//
// Threading: one motioncam::Decoder instance is NOT safe for concurrent use
// (upstream README). One Source belongs to one thread. Callers that need
// background execution must confine a Source to a single dedicated thread
// (the Android layer does this with a single-thread executor).

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <rawedit/Result.h>

namespace rawedit {

// Defined in Source.cpp; Source owns one instance.
struct SourceImpl;

enum class CfaPattern {
    RGGB,
    GRBG,
    GBRG,
    BGGR
};

struct Rect {
    int x = 0;
    int y = 0;
    int w = 0;
    int h = 0;
};

struct FrameInfo {
    int64_t ptsUs = 0;       // presentation timestamp, microseconds
    uint32_t width = 0;      // CFA width in pixels
    uint32_t height = 0;     // CFA height in pixels
    uint16_t bitDepth = 16;  // MCRAW stores 16-bit samples
    CfaPattern cfa = CfaPattern::RGGB;
    Rect activeArea;         // full frame today; see Source.cpp (QA-02 pending)
    int orientationDeg = 0;  // sensor orientation, degrees CW to upright
};

struct AudioInfo {
    int sampleRate = 0;
    int channels = 0;
};

// Per-clip RAW metadata surfaced for the develop stage (Task 2) and the DNG
// writer (Task 5). blackLevel is per-CFA-channel [4] in the 2x2 order
// (r, g, g, b) matching the sensor arrangement; whiteLevel is a single value.
// colorMatrix is 3x3 row-major, DNG direction XYZ -> native camera RGB (DNG
// ColorMatrix1 semantics; do NOT multiply it onto camera RGB). forwardMatrix
// is 3x3 row-major, DNG direction native camera RGB (normalized by
// asShotNeutral) -> XYZ D50. calibration is 3x3 row-major (DNG
// CalibrationMatrix1, usually near-diagonal). asShotNeutral is [r, g, b];
// wbGains is its reciprocal (DNG semantics). lensShading holds 4 planes of
// (shadingH x shadingW) gains, row-major, plane-major (plane*shadingH*shadingW).
struct RawMeta {
    uint16_t black[4] = {0, 0, 0, 0};
    uint16_t white = 0;
    float wbGains[3] = {1.0f, 1.0f, 1.0f};
    float colorMatrix[9] = {1,0,0, 0,1,0, 0,0,1};
    float forwardMatrix[9] = {1,0,0, 0,1,0, 0,0,1};
    float calibration[9] = {1,0,0, 0,1,0, 0,0,1};
    float asShotNeutral[3] = {1.0f, 1.0f, 1.0f};
    std::vector<float> lensShading;
    uint32_t shadingW = 0;
    uint32_t shadingH = 0;
};

// Per-frame Camera2 SENSOR_NOISE_PROFILE as stored in the MCRAW frame
// metadata: 8 floats = 4 CFA slots x (S, O) in the slot order of
// RawMeta::black (r, g, g, b), normalized-signal domain:
// variance_norm(signal_norm) = S*signal_norm + O. Read through
// Source::noiseProfile(); valid=false when the clip carries no profile
// (consumers then use a documented fallback). Per frame on purpose: the
// profile can change across gain modes within one clip.
struct NoiseProfile {
    bool valid = false;
    float s[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float o[4] = {0.0f, 0.0f, 0.0f, 0.0f};
};

class Source {
public:
    // Opens a MCRAW container from a POSIX file descriptor. The descriptor is
    // dup()ed, so the caller may close its copy at any time. On any failure
    // the caller's fd is untouched.
    static Result<std::unique_ptr<Source>> open(int fd);

    // Convenience overload for paths (desktop CLI, tests).
    static Result<std::unique_ptr<Source>> open(const std::string& path);

    ~Source();

    Source(const Source&) = delete;
    Source& operator=(const Source&) = delete;

    size_t frameCount() const;

    // Metadata for frame i. i < frameCount(); an out-of-range i returns a
    // zeroed FrameInfo rather than throwing.
    FrameInfo frameInfo(size_t i) const;

    // Decodes frame i into a w*h uint16 CFA buffer (row-major, 2x2 repeating
    // pattern per FrameInfo.cfa). May take seconds on large frames.
    Result<std::vector<uint16_t>> readFrame(size_t i);

    // Audio description; sampleRate/channels are 0 when the clip has no audio.
    Result<AudioInfo> audioInfo() const;

    // All audio chunks concatenated, interleaved per channel, 16-bit PCM.
    // Empty vector when the clip has no audio.
    Result<std::vector<int16_t>> readAudio();

    // Per-clip RAW metadata (black/white levels, CFA, color matrix,
    // as-shot neutral, lens shading map). Valid after a successful open.
    const RawMeta& rawMeta() const;

    // Per-frame noise profile (Camera2 SENSOR_NOISE_PROFILE, "noiseProfile"
    // in the frame metadata): variance = S*signal + O per CFA slot. Reads
    // metadata only — no frame decode. valid=false when the clip carries
    // no profile. An out-of-range frameIndex also returns valid=false
    // (consistent with frameInfo's zeroed-info policy) — a missing profile
    // degrades to the temporal stage's fallback sigma, never an error.
    NoiseProfile noiseProfile(size_t i) const;

private:
    Source() = default;
    SourceImpl* mImpl = nullptr;
};

// Parses a motioncam sensor arrangement string ("rggb", "grbg", "gbrg",
// "bggr" — the upstream key is "sensorArrangment"; the typo is in the
// format) into a CfaPattern. Returns Unsupported for anything else.
CfaPattern parseCfaPattern(const std::string& sensorArrangment, ErrorCode& code);

} // namespace rawedit
