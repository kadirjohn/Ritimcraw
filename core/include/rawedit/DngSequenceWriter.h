// rawedit::DngSequenceWriter — the WRITER half of a CinemaDNG sequence
// export (docs/CINEMADNG_EXPORT_PLAN.md R1-2, night E-D3 2026-11-27).
//
// Named DngSequenceWriter on purpose: engine/DngSequence.kt is the
// READER-side pure model (folder naming, gaps, fps grid, WAV binding)
// and the two must never be confused. This header owns no reading, no
// discovery, no folder scanning — it writes frames that the CALLER
// supplies, in the order the caller supplies them.
//
// THE CONTRACT (the ExportEngine pattern, Kotlin side):
//   - frame list: the caller registers the frames (a frame provider
//     callback per index, so a large clip never needs all CFA buffers
//     resident at once — the provider decodes on demand, exactly like
//     the export pipeline's per-frame decode);
//   - progress: onProgress(written, total) after every frame write;
//   - cancel: shouldCancel() polled BEFORE every frame; a cancel stops
//     the write with state=Cancelled and the frames already written
//     STAY ON DISK (a partial result is a result — "yazılan kareler
//     korunur, sessizce silme YOK", the plan's storage rule);
//   - failure: a frame that fails to write stops the sequence with
//     state=Failed and the written frames also stay (the same rule —
//     the caller decides what to show, never a silent delete).
//
// FILE NAMING (R1-1 is NOT resolved — no MotionCam sample exists yet):
// the pattern is a PARAMETER, `<prefix>_<frame:06>.dng` only as the
// DEFAULT. The caller can pass any width (1..10) and any prefix; the
// writer derives each frame's file name and reports the name list in
// the result. "örnek dosya gelince doğrulanacak" — the defaults are
// provisional until a real MotionCam CinemaDNG export is compared.
//
// TIMECODE (R1-3): the START timecode is a parameter (default
// 00:00:00:00 — the owner's "başlangıç timecode kaynağı karar bekliyor"
// question stays OPEN; this layer never guesses a wall-clock origin).
// Each frame i writes the SMPTE code derived from start + i under the
// sequence fps, plus the FrameRate tag with the EXACT fps rational the
// caller named (30000/1001 for 29.97 — never a 1e6 approximation).
//
// THREADING: writeFrames itself is single-threaded by contract (the
// frame provider is called from the writer's thread, one at a time —
// the Android layer confines it to the shared ExportEngine thread,
// where the motioncam decoder is not concurrency-safe). "Thread-safe"
// here means the DngSequenceWriter object carries no hidden global
// state: two writers in two threads write two independent sequences
// (each writeCfaDng call is pure file IO over its own buffers). The
// cancel flag is std::atomic so a cancel request from the UI thread is
// seen without any lock (the ExportQueue's cooperative-cancel shape).

#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#include <rawedit/DngWriter.h>
#include <rawedit/Result.h>
#include <rawedit/Source.h>

namespace rawedit {

// How the sequence ended (the ExportJob State shape: the caller maps
// these onto its own job states).
enum class SequenceState {
    Complete,   // every registered frame was written
    Cancelled,  // shouldCancel said stop: written frames stay on disk
    Failed,     // a frame write failed: written frames stay on disk
};

// The result of one sequence write (the partial-result carrier).
struct SequenceResult {
    SequenceState state = SequenceState::Complete;
    size_t framesWritten = 0;   // frames actually on disk
    size_t framesTotal = 0;     // frames the plan asked for
    std::vector<std::string> writtenPaths; // ascending frame order
    std::string failDetail;    // non-empty on Failed
    int failCode = 0;          // rawedit::ErrorCode as int on Failed
    std::string failStage;     // "frame read" / "dng write" on Failed

    bool isOk() const { return state == SequenceState::Complete; }
};

// The frame the sequence writes: the caller owns the decode. Returned
// by value so the writer never aliases a provider-owned buffer past
// the callback (the decode of frame i+1 may reuse the provider's own
// staging memory).
struct SequenceFrameData {
    std::vector<uint16_t> cfa; // the raw mosaic samples, w*h
    FrameInfo frame;           // the frame's info (dimensions, CFA...)
};

// A start timecode for the sequence (frame 0's SMPTE code). The fields
// are stored UNPACKED (not the 8 tag bytes) so an out-of-range value is
// a validation error at plan time, never a corrupt tag.
struct TimecodeStart {
    int hour = 0;
    int minute = 0;
    int second = 0;
    int frame = 0;
};

// The per-frame source the writer calls. Returns false + sets the
// code/stage/detail outs when the frame cannot be decoded (the write
// stops Failed, written frames preserved).
using SequenceFrameProvider = std::function<
    bool(size_t frameIndex, SequenceFrameData& out,
         int& code, std::string& stage, std::string& detail)>;

// The cancel probe: called before every frame; true = stop (Cancelled).
using SequenceCancelProbe = std::function<bool()>;

// The progress sink: (framesWritten, framesTotal).
using SequenceProgress = std::function<void(size_t, size_t)>;

// The naming pattern, PARAMETERIZED (R1-1 unresolved): prefix +
// underscore + zero-padded decimal + ".dng". The default is
// prefix "clip", width 6 -> clip_000000.dng. Width is clamped to
// [1, 10] by the plan helper (a 10-digit frame number cannot overflow
// a 32-bit frame index; the clamp is honest, tested at the 999999
// boundary).
struct SequenceNaming {
    std::string prefix = "clip";
    int frameDigits = 6;
};

// The plan of one sequence write: naming, fps (as the EXACT rational
// the FrameRate tag writes), the start timecode, the frame range.
// fpsNum/fpsDen must be > 0; the double the timecode arithmetic uses
// is derived (fpsNum / (double)fpsDen) so the tag and the arithmetic
// can never disagree.
struct DngSequencePlan {
    SequenceNaming naming;
    uint32_t fpsNum = 30;     // FrameRate numerator (30000 for 29.97)
    uint32_t fpsDen = 1;      // FrameRate denominator (1001 for 29.97)
    TimecodeStart startTimecode; // default 00:00:00:00 (owner question open)
    size_t firstFrame = 0;    // the clip-relative first frame index
    size_t frameCount = 0;    // how many frames (may be < clip frames)
    std::string outputDir;    // absolute; must exist (no mkdir here)

    // The exact fps the timecode arithmetic runs at (never re-derived
    // ad hoc: one source of truth with the FrameRate tag).
    double fps() const { return static_cast<double>(fpsNum) / fpsDen; }

    // The file name of output frame i (0-based in the WRITE, not the
    // clip: frame 0 is the first written frame). Pure; tested at the
    // 999999 boundary and the huge-frame path.
    std::string fileName(size_t i) const;

    // The SMPTE 8-byte code of output frame i, derived from the start
    // timecode under fps (the DngWriter::smpteTimeCodes layout).
    void timeCodesOf(size_t i, uint8_t out[8]) const;

    // True when the plan is internally usable (the caller checks and
    // reports its own policy on top). Empty dir or frameCount 0 is a
    // caller policy decision; this only rejects impossible fields.
    bool valid(std::string& why) const;
};

// Writes the sequence. The frames come from [provider] in ascending
// output order (output frame i = the plan's firstFrame + i on the
// caller's own numbering — the provider receives the OUTPUT index).
// Every write failure leaves the frames written so far on disk and
// returns state=Failed with the written path list filled.
SequenceResult writeDngSequence(
    const DngSequencePlan& plan,
    const RawMeta& meta,
    const DngExtraMeta& extra,
    const SequenceFrameProvider& provider,
    const SequenceCancelProbe& shouldCancel = {},
    const SequenceProgress& onProgress = {});

} // namespace rawedit
