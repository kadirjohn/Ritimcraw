// Streaming resampler + stereo/mono downmix core (E5-B, unwired).
// docs/E5_AUDIO_PORT_PLAN.md §3.7/§3.8.
//
// Provenance: a faithful port of the filmcraft audio-dsp crate
// (crates/audio-dsp/src/resample.rs, StreamResampler, commit "written
// from the public standards and textbook DSP" — the crate is
// dependency-free and states clean-room authorship in its README) plus
// the 2-channel rows of crates/audio-dsp/src/channels.rs (the ITU-R
// BS.775 k = 1/sqrt(2) downmix). Owner decision D1: no Rust is linked;
// the needed pieces are ported to plain C++. The arithmetic mirrors the
// Rust operation order (f32 samples, exact-rational source positions in
// 128-bit integers) so this core can be held to filmcraft's own test
// vectors; core/tests/test_resample.cpp carries those vectors and the
// extra port-level checks. THIS HEADER ONLY EXISTS ON THE DESKTOP TEST
// PATH for now — no app, JNI or Kotlin call site links it (the wiring
// is the later E5-B integration package).
//
// StreamResampler: every output sample's source position is the exact
// rational out * from / to, linearly interpolated, so the output is
// identical however the stream is cut into blocks (no phase jump at
// block boundaries). Source frames are pulled in contiguous,
// non-overlapping spans, so whatever produces them (the mixer, with
// its effect state) sees one continuous stream. The output lags the
// source by one source sample (~21 us at 48 kHz), which is what lets
// each block interpolate without reading ahead. Rates of zero are
// clamped to 1 Hz rather than dividing by zero.
//
// Downmix section: planar f32, per-sample matrix application.
// stereo -> mono is the BS.775 constant-power fold [k, k] with
// k = 1/sqrt(2) (~-3.01 dB); mono -> stereo is a unity gain copy on
// both channels (the deliberate dual-mono +3 dB). Channel counts other
// than 1 and 2 are not a layout here (nothing is invented): passes
// through the first N channels of a wider source unchanged.

#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <vector>

namespace rawedit {

/// Maximum source frames fetched in a single resampler pull
/// (filmcraft resample.rs MAX_RESAMPLE_SOURCE_SPAN).
constexpr size_t kMaxResampleSourceSpan = 384000 * 10;

/// The BS.775 stereo->mono downmix coefficient, 1/sqrt(2) ~ -3.01 dB.
constexpr float kDownmixK = 0.70710678118654752440f;

/// One conversion row m[out][in] (see the downmix section below).
using MixRow = std::vector<float>;

/// Streams a planar f32 signal from `from` Hz to `to` Hz, block by
/// block, exactly like filmcraft's StreamResampler.
///
/// The pull callback returns the source frames [start, start + frames),
/// planar (one std::vector<float> per channel, all the same length;
/// missing channels and missing samples read as silence). Calling
/// process() with a block that does not continue the previous one (a
/// seek) starts the stream afresh.
class StreamResampler {
public:
    /// Rates of zero are treated as 1 Hz rather than dividing by zero.
    StreamResampler(uint32_t from, uint32_t to);

    std::pair<uint32_t, uint32_t> rates() const { return {from_, to_}; }

    /// Renders output frames [out, out + n), planar; returns one channel
    /// per pulled source channel (the channel count the first pull
    /// produced; pulls decide it, the resampler never invents channels).
    /// n == 0 returns the (possibly empty) channel set with no data.
    std::vector<std::vector<float>> process(
        int64_t out,
        size_t n,
        const std::function<std::vector<std::vector<float>>(int64_t start, size_t frames)>& pull);

private:
    /// The source frame whose sample lands at (or just before) output
    /// frame `out`: (floor division, remainder fraction in [0, 1)).
    std::pair<int64_t, float> sourceIndex(int64_t out) const;

    uint32_t from_;
    uint32_t to_;
    /// Source frames [held_start, held_start + held[c].size()) kept
    /// from the last pull.
    int64_t heldStart_;
    std::vector<std::vector<float>> held_;
    /// The output frame the next block is expected to start at
    /// (contiguous playback); nullopt = no block yet.
    bool haveNextOut_;
    int64_t nextOut_;
};

/// ---- stereo/mono downmix section (filmcraft channels.rs 1<->2 rows) ----

/// The conversion matrix m[out][in] for a channel-count change this core
/// supports (1 <-> 2, and the pass-through identities). The rows are
/// the filmcraft matrix() cells exactly: stereo->mono is {k, k};
/// mono->stereo is {1}, {1}; identity is the unit matrix. An unchanged
/// count returns the unit matrix; a source wider than stereo is NOT
/// downmixed here (the caller takes what it wants — nothing is guessed).
std::vector<MixRow> mixMatrix(size_t fromChannels, size_t toChannels);

/// Applies m[out][in] to planar input (all channels the same length);
/// the n == 0 case returns empty rows. Zero gains are skipped exactly
/// like filmcraft (a passthrough row costs nothing).
std::vector<std::vector<float>> applyMixMatrix(
    const std::vector<MixRow>& m,
    const std::vector<const float*>& input,
    size_t n);

/// Convenience: the whole conversion in one call (identity copies).
std::vector<std::vector<float>> convertChannels(
    const std::vector<const float*>& input,
    size_t n,
    size_t toChannels);

} // namespace rawedit
