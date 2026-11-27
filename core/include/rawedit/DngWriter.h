// rawedit::DngWriter — minimal own CFA (Bayer) DNG writer.
//
// Writes an uncompressed, single-strip, little-endian TIFF/DNG 1.4 file from
// a 16-bit CFA buffer plus the FrameInfo/RawMeta the Source layer already
// provides. No libtiff: the tag set is small and fixed, and owning the
// writer keeps the Android .so free of extra dependencies.
//
// Scope (Sprint 1, Task 5): one IFD, one strip, no maker notes, no lens
// shading baked into the pixels (the map is exported as-is; flattening it
// into the CFA is deferred with the develop pipeline's shading pass).

#pragma once

#include <cstdint>
#include <string>

#include <rawedit/Result.h>
#include <rawedit/Source.h>
namespace rawedit {

// Optional provenance tags for both DNG arms (şartname RAW-12: "CFA fazı,
// lens shading, noise profile, orientation, aperture, focal length ve
// capture time gibi mevcut/uyumlu alanları koru. Metadata değerleri gerçek
// kaynağa dayanır"; QA-17 axes "lens metadata, capture time"). Closes the
// writer half of docs/LINEAR_DNG_GAPS.md G1 (lens), G2 (capture time),
// G3 (camera identity) and G7 (ForwardMatrix1), 2026-11-23.
//
// THE HONESTY RULE: every field defaults to "absent" and an absent field
// writes NO tag — never a placeholder. A default-constructed DngExtraMeta
// therefore produces a file byte-identical to the pre-2026-11-23 writer
// (pinned by test_dng "dng_extra_meta_default_is_byte_identical"). The
// caller fills only what the SOURCE really carries:
//  - make / model / uniqueCameraModel: ContainerIdentity (manufacturer,
//    buildModel/deviceModel, uniqueCameraModel) — empty = omit.
//  - captureTimeUtc: "YYYY:MM:DD HH:MM:SS" (see dngDateTimeUtc) from the
//    frame's FrameInfo.recvdMs epoch — empty = omit. Written as DateTime
//    (306) + DateTimeOriginal (36867) + OffsetTimeOriginal (36881)
//    "+00:00", because the string is UTC and a reader must not read it as
//    local time.
//  - fNumber: ContainerIdentity.aperture (FNumber 33437) — 0 = omit.
//  - focalLength35mm: RawMeta.focalLengthMm, which is the container's
//    35mm-EQUIVALENT focal (Source.h) — so it is written as
//    FocalLengthIn35mmFilm (41989, SHORT, rounded), NEVER as the physical
//    FocalLength (37386); 0 = omit.
//  - focalLengthMm: the PHYSICAL focal (37386 FocalLength) — only when a
//    real source supplies it (the MCRAW container does not today); 0 = omit.
//  - lensModel: EXIF LensModel (42036) — empty = omit.
//  - forwardMatrix1: RawMeta.forwardMatrix (50964 ForwardMatrix1,
//    SRATIONAL x9) when hasForwardMatrix1 — false = omit.
// The EXIF-domain tags live in IFD0 (single-IFD writer): the DNG SDK and
// LibRaw both read FNumber/FocalLength/DateTimeOriginal from the main IFD.
//
// TIMECODE / FRAME RATE (CinemaDNG sequence support, docs/
// CINEMADNG_EXPORT_PLAN.md R1-3, night E-D3 2026-11-27): a sequence
// export writes the CinemaDNG identity tags on every frame so a reader
// (Resolve, MotionCam) can group the folder into one clip and place it
// on a timeline. Both default to ABSENT and an absent option writes NO
// tag — the same honesty rule as DngExtraMeta — so a default
// DngSequenceMeta produces a file byte-identical to the writer without
// it (pinned by test_dng "dng_sequence_meta_default_is_byte_identical").
//   - TimeCodes (51043): 8 raw bytes, the DNG 1.4 SMPTE timecode layout
//     (see smpteTimeCodes): hour/min/sec/frame plus the two flag bytes.
//     The caller passes the START code of frame 0; per-frame codes are
//     derived by DngSequence, which owns the fps-based arithmetic.
//   - FrameRate (51044): one RATIONAL. Written as the exact rational the
//     caller names (e.g. 24000/1001 for 23.976) — never a 1e6-denominator
//     approximation, so 29.97 reads back as 30000/1001, the value Resolve
//     and the DNG spec's drop-frame convention expect.
struct DngSequenceMeta {
    bool hasTimeCodes = false;    // false = omit tag 51043
    uint8_t timeCodes[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    bool hasFrameRate = false;     // false = omit tag 51044
    uint32_t frameRateNum = 0;    // rational numerator (e.g. 30000)
    uint32_t frameRateDen = 0;    // rational denominator (e.g. 1001)
};

// The DNG 1.4 TimeCodes (51043) byte layout, SMPTE 12M: 8 bytes =
//   [0] hours (bits 0-5) + bgf8 flag (bit 7, 1 when 24 fps)
//   [1] minutes + colorFrame flag (bit 7)
//   [2] seconds + field flag (bit 7)
//   [3] frame + fhf flag (bit 7, 1 for interlaced field 1)
//   [4] dropped-frame flag (bit 0, 1 when fps uses drop-frame: 29.97)
//   [5] user-bits binary group flags
//   [6] user bits
//   [7] user bits
// The fps flag bits follow the convention the tinydng reference writer
// and the DNG SDK both write for a base rate of 24/25/30 fps
// (TIFFTAG_TIMECODE is a BYTE[8] in both). Writes the code for frame
// number frameOffset at fps into out (the caller derives a per-frame
// code from the sequence's start code, which this helper decodes:
// hours/min/sec/frame carry over and the FRAME field advances by
// frameOffset under the fps's frame-per-second rollover).
//   - fps 24 (exact or 23.976): bgf8 = 1, df = 0
//   - fps 25: bgf8 = 0, df = 0
//   - fps 30 (exact or 29.97): bgf8 = 0, df = 1 only for 29.97-style
//     drop-frame rates (df=0 for exact 30)
//   - anything else: flags 0 (reported, never guessed — the caller
//     passes a rate this helper classifies honestly; the classification
//     itself is tested).
void smpteTimeCodes(int hour, int minute, int second, int frame,
                    int frameOffset, double fps, uint8_t out[8]);

// Decodes the fps classification of smpteTimeCodes back out of 8 flag
// bytes: (bgf8, dropFrame). Pure; testable without a file.
void smpteFpsFlags(double fps, uint8_t flagsOut[2]);

// Writes the raw 8-byte SMPTE code as the TimeCodes tag verbatim (the
// caller owns the arithmetic; this overload writes exactly what it is
// given — the byte layout contract, no classification).
void smpteTimeCodesRaw(const uint8_t in[8], int frameOffset, double fps,
                       uint8_t out[8]);

// NOISE (G4, the deliberate omission): neither arm writes NoiseProfile
// (51041) or a noise opcode. The source's NoiseProfile is per CFA slot in
// the normalized-signal domain; for the CFA file it would need a
// per-plane DNG remap, and for the Linear file (demosaiced, WB-scaled RGB)
// it is simply WRONG — the "Kare çıktıları" rule asks that noise semantics
// match the output pixels, and no tag is the only statement that is never
// false. Revisit when an RGB-domain derivation is written and tested.
struct DngExtraMeta {
    std::string make;
    std::string model;
    std::string uniqueCameraModel;
    std::string captureTimeUtc;   // "YYYY:MM:DD HH:MM:SS", UTC; empty = omit
    double fNumber = 0.0;         // 0 = omit
    double focalLength35mm = 0.0; // 35mm-equivalent mm; 0 = omit
    double focalLengthMm = 0.0;   // physical mm; 0 = omit
    std::string lensModel;        // empty = omit
    bool hasForwardMatrix1 = false;
    float forwardMatrix1[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
};

// "YYYY:MM:DD HH:MM:SS" (the TIFF/EXIF DateTime layout, 19 chars) for an
// epoch-milliseconds instant in UTC. Returns "" for a negative instant
// (FrameInfo.recvdMs uses -1 for "absent") — absent stays absent.
std::string dngDateTimeUtc(int64_t epochMs);

// Writes cfa (width*height uint16 samples, row-major, 2x2 pattern per
// FrameInfo.cfa) as a DNG at path. On failure returns IoError/Unsupported
// with detail; a partially written file is removed.
Result<void> writeCfaDng(const std::string& path, const uint16_t* cfa,
                         const FrameInfo& frame, const RawMeta& meta);

// The same CFA file plus the optional provenance tags in extra (see
// DngExtraMeta). A default extra writes exactly the overload above.
Result<void> writeCfaDng(const std::string& path, const uint16_t* cfa,
                         const FrameInfo& frame, const RawMeta& meta,
                         const DngExtraMeta& extra);

// Linear DNG (owner spec RAW-13): the demosaiced, scene-referred linear
// frame as a DNG 1.4 file — PhotometricInterpretation 34892 (LinearRaw),
// SamplesPerPixel 3, BitsPerSample 16 per sample, the pixels in the
// camera-native RGB primaries (ColorMatrix1 kept; only the stage-1
// normalization and the as-shot white balance are baked in). The exact
// processing stages applied to the pixels are recorded in the
// ImageDescription tag (270) so a reader can tell what this file is.
// rgb must hold width*height*3 uint16 samples (row-major, r-g-b order);
// the writer never re-develops — it serializes what it is given.
// On failure returns IoError/Unsupported with detail; a partially written
// file is removed.
Result<void> writeLinearDng(const std::string& path, const uint16_t* rgb,
                            const FrameInfo& frame, const RawMeta& meta);

// The same Linear file plus the optional provenance tags in extra. A
// ForwardMatrix1 is consistent with the linear pixels: it maps
// white-balanced camera RGB to XYZ(D50), and the linear pixels ARE
// white-balanced camera RGB (AsShotNeutral = 1).
Result<void> writeLinearDng(const std::string& path, const uint16_t* rgb,
                            const FrameInfo& frame, const RawMeta& meta,
                            const DngExtraMeta& extra);

// The same CFA file plus the CinemaDNG sequence tags in seq (see
// DngSequenceMeta, R1-3): TimeCodes 51043 (8 bytes, verbatim — the
// caller derived the per-frame code) and FrameRate 51044 (the exact
// rational given). A default seq writes NO tag and produces a file
// byte-identical to the no-seq overload (the DngExtraMeta honesty
// rule). The sequence tags sort after every base and extra tag
// (51043/51044 > 50964), so the IFD stays ascending.
Result<void> writeCfaDng(const std::string& path, const uint16_t* cfa,
                         const FrameInfo& frame, const RawMeta& meta,
                         const DngExtraMeta& extra,
                         const DngSequenceMeta& seq);

// The same Linear file plus both optional tag sets (extra + seq). A
// default seq writes no sequence tag: byte-identical to the extra-only
// overload.
Result<void> writeLinearDng(const std::string& path, const uint16_t* rgb,
                            const FrameInfo& frame, const RawMeta& meta,
                            const DngExtraMeta& extra,
                            const DngSequenceMeta& seq);

// The fd arm of the CFA writer (CinemaDNG sequence export, R1-2, night
// E-F 2026-11-27): the SAME bytes writeCfaDng would put at [path],
// written to the RAW POSIX fd [outFd] the caller owns (Android SAF:
// ContentResolver.openFileDescriptor(uri, "w") — no path exists the
// path arm could open). The caller opens AND closes the fd; a failure
// leaves whatever was written, and the CALLER discards the document
// (the SAF delete) — the sequence's partial-result rule is the
// caller's, exactly like DngSequenceWriter's. A short write is an
// honest IoError with errno, never a silent partial frame.
Result<void> writeCfaDngToFd(int outFd, const uint16_t* cfa,
                             const FrameInfo& frame, const RawMeta& meta,
                             const DngExtraMeta& extra,
                             const DngSequenceMeta& seq);

} // namespace rawedit
