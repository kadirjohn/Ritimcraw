// E5-B audio reshape — implementation (night G1).
// See AudioReshape.h for the contract, the identity rule and the order.
// The heavy lifting is the E5-B planar-f32 cores (Resample.cpp /
// convertChannels); this TU owns only the 16-bit boundary.

#include <rawedit/AudioReshape.h>

#include <rawedit/Resample.h>

#include <cmath>

namespace rawedit {

namespace {

/// 16-bit -> float, the one §8.3 convention (AudioNormalize's norm()).
inline float norm(int16_t x) { return static_cast<float>(x) / 32768.0f; }

/// Float -> 16-bit: scale, round half away from zero, clamp — the
/// mixer's toPcm chain (§8.3, bit-equality with the existing exports).
inline int16_t toPcm16(float x) {
    const double r = std::lround(static_cast<double>(x) * 32768.0);
    const double c = std::min(32767.0, std::max(-32768.0, r));
    return static_cast<int16_t>(c);
}

/// Interleaved 16-bit -> planar f32, [channel][frame].
std::vector<std::vector<float>> deinterleave(const int16_t *pcm,
                                             std::size_t frames,
                                             int channels) {
    std::vector<std::vector<float>> planar(
        static_cast<std::size_t>(channels),
        std::vector<float>(frames));
    for (std::size_t i = 0; i < frames; ++i) {
        for (int c = 0; c < channels; ++c) {
            planar[static_cast<std::size_t>(c)][i] =
                norm(pcm[i * static_cast<std::size_t>(channels) + c]);
        }
    }
    return planar;
}

/// Planar f32 -> interleaved 16-bit.
std::vector<int16_t> interleave(const std::vector<std::vector<float>> &planar,
                                std::size_t frames, std::size_t channels) {
    std::vector<int16_t> out(frames * channels);
    for (std::size_t i = 0; i < frames; ++i) {
        for (std::size_t c = 0; c < channels; ++c) {
            out[i * channels + c] = toPcm16(planar[c][i]);
        }
    }
    return out;
}

} // namespace

ReshapeResult reshapePcm(const int16_t *pcm, std::size_t frames,
                         int sampleRate, int channels,
                         int toRate, int toChannels) {
    ReshapeResult out;
    const bool badShape = pcm == nullptr || channels <= 0 || sampleRate <= 0 ||
                          (toRate < 0) || (toChannels < 0);
    const std::size_t ch = static_cast<std::size_t>(channels > 0 ? channels : 1);
    if (badShape || frames == 0) {
        // Degenerate request: copy what is there, shape as given; the
        // caller's gate decides. Never a guess, never a throw.
        out.frames = frames;
        if (pcm != nullptr && frames > 0) {
            out.pcm.assign(pcm, pcm + frames * ch);
        }
        return out;
    }

    const int wantRate = (toRate == 0) ? sampleRate : toRate;
    const int wantChannels = (toChannels == 0) ? channels : toChannels;
    out.frames = frames;

    // The IDENTITY CONTRACT: nothing to change — copy untouched, no f32
    // round trip, no requantize. Bit-identical audio.
    if (wantRate == sampleRate && wantChannels == channels) {
        out.pcm.assign(pcm, pcm + frames * ch);
        return out;
    }

    // 1. de-interleave once, into the source's planar f32.
    std::vector<std::vector<float>> planar = deinterleave(pcm, frames, channels);

    // 2. REMIX (BS.775): the per-sample matrix over the planar buffer.
    //    convertChannels never invents channels (the pass-through rows).
    if (wantChannels != channels) {
        std::vector<const float *> in(planar.size());
        for (std::size_t c = 0; c < planar.size(); ++c) in[c] = planar[c].data();
        planar = convertChannels(in, frames, static_cast<std::size_t>(wantChannels));
    }

    // 3. RESAMPLE: pull the planar buffer through the stream resampler
    //    in one whole-buffer pass. The output frame count is the plan
    //    §4.3's own k = out_frames rule: the duration follows the rates.
    if (wantRate != sampleRate) {
        StreamResampler resampler(static_cast<uint32_t>(sampleRate),
                                  static_cast<uint32_t>(wantRate));
        const std::size_t outFrames = static_cast<std::size_t>(
            static_cast<double>(frames) * static_cast<double>(wantRate) /
            static_cast<double>(sampleRate));
        // The pull serves the source frames [start, start + n) from the
        // planar buffer — contiguous, non-overlapping, silence beyond
        // the end (the resampler's own contract for a short last span).
        std::vector<std::vector<float>> reshaped = resampler.process(
            0, outFrames,
            [&](int64_t start, std::size_t n) {
                std::vector<std::vector<float>> block(planar.size());
                for (std::size_t c = 0; c < planar.size(); ++c) {
                    block[c].resize(n, 0.0f);
                    const int64_t s = std::max<int64_t>(start, 0);
                    const int64_t e = std::min<int64_t>(start + static_cast<int64_t>(n),
                                                        static_cast<int64_t>(frames));
                    if (e > s) {
                        const int64_t skip = s - start;
                        for (int64_t i = s; i < e; ++i) {
                            block[c][static_cast<std::size_t>(skip + (i - s))] =
                                planar[c][static_cast<std::size_t>(i)];
                        }
                    }
                }
                return block;
            });
        planar = std::move(reshaped);
        out.frames = outFrames;
    }

    // 4. back to interleaved 16-bit through the one quantize chain.
    out.pcm = interleave(planar, out.frames, static_cast<std::size_t>(wantChannels));
    return out;
}

} // namespace rawedit
