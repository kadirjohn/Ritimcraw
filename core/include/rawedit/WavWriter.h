// rawedit::WavWriter — PCM WAV (RIFF/WAVE) writer for the CinemaDNG
// sequence export's companion audio (docs/CINEMADNG_EXPORT_PLAN.md R1-4,
// night E-D3 2026-11-27).
//
// WHY A WAV: the CinemaDNG contract carries the clip's audio as a
// 16-bit PCM WAV beside the DNG frames (the reader side's WavBinding,
// engine/DngSequence.kt, expects one). The MP4 export encodes AAC; a
// sequence export must NOT re-encode — "kaynak örnekleme hızı/kanal
// (yeniden örnekleme YOK)", the plan's rule.
//
// THE CONTRACT:
//   - RIFF/WAVE, format-code 1 (PCM), 16-bit samples, source sample
//     rate and channel count VERBATIM (no resampling, no downmix);
//   - the clip's PCM sliced to the exported RANGE: start/end in
//     samples (the caller derives them from the frame range; an
//     open-ended range takes the whole clip);
//   - sizes are 32-bit RIFF fields but computed in 64-bit, so a >4 GiB
//     file reports honest saturation (0xffffffff) rather than a silent
//     wrap — the WAV spec's own limit, reported never guessed;
//   - a partially written file on failure is REMOVED (the single-file
//     rule; the sequence's frames follow the DngSequenceWriter partial
//     rule instead, which is why the WAV write is one atomic call).
//
// The writer allocates nothing per sample and does one pass: the
// header is 44 bytes written first (sizes patched after the data when
// the whole range is in memory; for the streaming arm the header is
// written with the FINAL sizes computed up front — the caller knows
// the sample count — so nothing is ever back-patched mid-file).

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <rawedit/Result.h>

namespace rawedit {

// The audio range the WAV carries: sample indices into the clip's
// interleaved PCM. endSample is EXCLUSIVE (the half-open convention
// the Kotlin layer's ranges use); endSample = 0 means "to the end".
struct WavRange {
    size_t startSample = 0;   // first carried sample
    size_t endSample = 0;     // exclusive; 0 = the clip's whole tail
};

// Writes [range] of [pcm] (interleaved, 16-bit, [channels] channels,
// the clip's own [sampleRate] — verbatim, no resampling) as a PCM WAV
// at [path]. Returns IoError when the file cannot be written (the
// partial file is removed); Unsupported for an unusable argument set
// (sampleRate <= 0, channels <= 0, pcm shorter than the range).
//
// The samples are written VERBATIM — they are the source's own
// 16-bit PCM, and any clipping that exists is the source's (the
// container already delivers int16). "PCM clipped to range" means the
// RANGE slice, not a sample-value clamp: the bytes are copied, never
// re-mathed.
Result<void> writeWav(const std::string& path,
                     const std::vector<int16_t>& pcm,
                     int sampleRate, int channels,
                     const WavRange& range = {});

// The header sizes of a WAV carrying [dataBytes] of PCM: (riffSize,
// dataSize), both uint32. riffSize saturates at 0xffffffff (the
// spec's own 4 GiB ceiling — an honest limit, never a silent wrap).
// Pure; tested field-by-field against the byte layout.
struct WavSizes {
    uint32_t riffSize = 0;
    uint32_t dataSize = 0;
    bool saturated = false; // true when riffSize hit the spec ceiling
};
WavSizes wavSizes(size_t dataBytes);

// The number of samples (per channel) a range slice of [pcm] holds,
// and the byte count the WAV data chunk carries. Both pure helpers
// the Kotlin plan mirrors; tested for the huge-frame path.
size_t wavRangeSampleCount(const std::vector<int16_t>& pcm,
                           const WavRange& range);
size_t wavRangeDataBytes(const std::vector<int16_t>& pcm,
                         const WavRange& range);

} // namespace rawedit
