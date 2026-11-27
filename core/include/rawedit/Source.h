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
    int orientationDeg = 0;  // degrees CW the RAW frame needs to stand upright (0/90/180/270), see ScreenOrientation.h
    // Per-frame RAW levels (owner request 2026-11-11 section 3): Android
    // Camera2 defines dynamic black/white levels as per-frame RAW
    // calibration. -1 = "this frame did not carry a level" — the develop
    // stage and the DNG writer fall back to the container RawMeta values.
    // Stored as int32 because the JSON keys hold plain integers and the
    // sentinel is out of every uint16 range.
    int32_t dynamicBlack[4] = {-1, -1, -1, -1};
    int32_t dynamicWhite = -1;
    // Frame-0-anchored wall clock (stabilization Phase 3/5): the
    // container's per-frame "recvdTimestampMs" (epoch ms, string or
    // number in the JSON) — the frame-received wall time the anchored
    // sync estimate pairs with the GCSV header timestamp (Sync.h).
    // -1 = this frame carries no timestamp (the pre-Phase-3 behavior:
    // sync degrades to ManualRequired, never fabricated). Frame 0's
    // value is the anchor; later frames keep theirs for diagnostics.
    int64_t recvdMs = -1;
    // Per-frame exposure time in ns (the frame JSON's "exposureTime";
    // stabilization round 3). The frame's content is centered at
    // capture + 0.5*exposure (+ 0.5*readout, which the container does not
    // carry — a constant the optical sync absorbs). -1 = absent.
    int64_t exposureNs = -1;
};

struct AudioInfo {
    int sampleRate = 0;
    int channels = 0;
};

// Per-clip RAW metadata surfaced for the develop stage (Task 2), the DNG
// writer (Task 5) and the stabilization warp (Phase 4). blackLevel is
// per-CFA-channel [4] in the 2x2 order
// (r, g, g, b) matching the sensor arrangement; whiteLevel is a single value.
// colorMatrix is 3x3 row-major, DNG direction XYZ -> native camera RGB (DNG
// ColorMatrix1 semantics; do NOT multiply it onto camera RGB). forwardMatrix
// is 3x3 row-major, DNG direction native camera RGB (normalized by
// asShotNeutral) -> XYZ D50. calibration is 3x3 row-major (DNG
// CalibrationMatrix1, usually near-diagonal). asShotNeutral is [r, g, b];
// wbGains is its reciprocal (DNG semantics). lensShading holds 4 planes of
// (shadingH x shadingW) gains, row-major, plane-major (plane*shadingH*shadingW).
// focalLengthMm is the container's "focalLengths"[0] — a 35mm-EQUIVALENT
// focal in mm, NOT a pixel focal (the container carries no sensor pitch or
// crop factor anywhere, so a pixel focal cannot be derived from the file —
// Stabilization.h documents the one flagged conversion). 0 = the key is
// absent; consumers must treat 0 as "unknown", never as a real value.
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
    double focalLengthMm = 0.0; // 35mm-equivalent mm; 0 = absent
};

// Per-clip camera identity (IN-13 metadata extraction): the container's
// camera/lens facts that no other reader consumes. Read through
// Source::containerIdentity(). Empty string / 0 mean the key is absent —
// unknown, never a guess (the RawMeta convention). The key spellings are
// the container's own (verified against real recordings):
//  - uniqueCameraModel: top-level "uniqueCameraModel" (EMPTY on the
//    owner's Xiaomi — the fallback chain lives in the Kotlin layer).
//  - deviceModel: "deviceSpecificProfile"."deviceModel".
//  - manufacturer / buildModel: "extraData"."postProcessSettings".
//    "metadata" keys "build.manufacturer" / "build.model" (literal dotted
//    key names in the JSON).
//  - aperture: top-level "apertures"[0], the lens f-number (0 = absent).
// focalLengthMm is NOT duplicated here — it already lives in RawMeta
// (35mm-equivalent; 0 = absent) and the JNI probe reads both accessors
// off the same open Source.
struct ContainerIdentity {
    std::string uniqueCameraModel;
    std::string deviceModel;
    std::string manufacturer;
    std::string buildModel;
    double aperture = 0.0; // lens f-number; 0 = absent
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

    // A NEW descriptor on the same file with its OWN open-file description
    // (independent offset), for a second Source used on another thread —
    // the decoder reads through a FILE* whose offset a dup() would share.
    // Re-opens /proc/self/fd/N (Linux/Android) or the F_GETPATH path
    // (macOS). The caller owns (and closes) it; -1 when not possible (a
    // pipe/socket descriptor, e.g.) — callers then fall back to a blocking
    // path on this Source. Round 3 (async optical sync).
    int reopenIndependentFd() const;

    Source(const Source&) = delete;
    Source& operator=(const Source&) = delete;

    size_t frameCount() const;

    // Metadata for frame i. i < frameCount(); an out-of-range i returns a
    // zeroed FrameInfo rather than throwing.
    FrameInfo frameInfo(size_t i) const;

    // Non-empty when a frame's MotionCam "orientation" code was not one of the known screen-orientation codes
    // (ScreenOrientation.h): the first such frame's code, for the caller to log. Empty otherwise. Set by
    // frameInfo(); the rotation used for such a frame is the container's sensorOrientation (the same fallback
    // as a frame without the key), never a silent "no rotation".
    std::string orientationWarning() const;

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

    // Per-clip camera identity (IN-13): the container's camera/lens keys
    // that no develop/export consumer touches. Absent keys leave the
    // field empty/0 — unknown, never a guess. Reads metadata only; valid
    // after a successful open.
    const ContainerIdentity& containerIdentity() const;

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
