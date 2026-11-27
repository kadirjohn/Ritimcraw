// E5-B audio reshape for the export path (night G1): remix + resample
// of one clip's WHOLE PCM buffer, the 16-bit whole-buffer twin of the
// normalization chain (AudioNormalize.h, night D2).
//
// docs/E5_AUDIO_PORT_PLAN.md §3.7/§3.8 + §8.3: the cores are the E5-B
// planar-f32 ports (Resample.cpp's StreamResampler, convertChannels'
// BS.775 matrix); this TU adds ONLY the 16-bit boundary the JNI seam
// needs — the de-interleave/re-interleave and the one quantize chain
// (norm x/32768, lround(x*32768) + clamp), the same chain AudioNormalize
// and the mixer's toPcm round with (the §8.3 bit-equality rule).
//
// IDENTITY CONTRACT (the export's default): [toRate] == 0 or equal to
// the source rate, AND [toChannels] == 0 or equal to the source
// channels, returns the input COPIED UNTOUCHED — no f32 round trip, no
// requantize, so a SOURCE-settings export (the default, every pre-G1
// job) keeps bit-identical audio. The caller (the Kotlin seam) does not
// even call in that case; the core guarantee is the second lock.
//
// ORDER: remix first, resample second. The two commute mathematically
// (the matrix is per-sample in time, the resampler is per-channel in
// time), but doing the remix first keeps the resampler's pull spans
// over the FINAL channel count — one conversion, never two passes.
//
// DETERMINISM: fixed formulas, no RNG, -ffp-contract=off (the Resample
// precedent; see core/CMakeLists.txt) so every target rounds the same.

#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawedit {

/// The whole-buffer reshape result: interleaved 16-bit PCM.
struct ReshapeResult {
    /// The reshaped PCM, interleaved [channels] per frame.
    std::vector<int16_t> pcm;
    /// The output frame count (pcm.size() / channels).
    std::size_t frames = 0;
};

/**
 * Remix + resample one clip's whole PCM.
 *
 * [pcm] interleaved 16-bit, [frames] frames of [channels] channels at
 * [sampleRate] Hz. [toRate] the wanted output rate; 0 = keep the source
 * rate (identity). [toChannels] the wanted output channel count; 0 =
 * keep the source channels (identity). Returns the reshaped buffer —
 * same layout, possibly different rate/channels/length. An identity
 * request copies the input untouched (the IDENTITY CONTRACT above).
 *
 * Honest, never guessing: a request whose rates or channel counts are
 * not positive, or whose PCM does not match its shape, returns the
 * input copied untouched with [frames] as given — the caller's error
 * gate (the Kotlin seam) decides what to do; this core never invents
 * content.
 */
ReshapeResult reshapePcm(const int16_t *pcm, std::size_t frames,
                         int sampleRate, int channels,
                         int toRate, int toChannels);

/// Convenience overload for a whole buffer.
inline ReshapeResult reshapePcm(const std::vector<int16_t> &pcm,
                                int sampleRate, int channels,
                                int toRate, int toChannels) {
    const std::size_t frames =
        (channels > 0) ? pcm.size() / static_cast<std::size_t>(channels) : 0;
    return reshapePcm(pcm.data(), frames, sampleRate, channels, toRate, toChannels);
}

} // namespace rawedit
