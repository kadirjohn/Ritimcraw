// Streaming resampler + stereo/mono downmix core (E5-B, unwired). See
// Resample.h for the provenance; this file follows filmcraft's
// crates/audio-dsp/src/resample.rs operation order line by line (the
// i128 rational source index becomes __int128, the Vec<Vec<f32>>
// planar buffers become std::vector<std::vector<float>>) and the
// channels.rs 1<->2 matrix cells. Nothing here links the app: the
// desktop tests are the only consumer until the E5-B wiring package.

#include <rawedit/Resample.h>

#include <algorithm>
#include <cstdint>
#include <limits>

namespace rawedit {

namespace {

/// Rust's i128::div_euclid (floor division, also for negatives).
int64_t divFloor(__int128 p, __int128 q) {
    __int128 d = p / q;
    __int128 r = p % q;
    if (r != 0 && ((r < 0) != (q < 0))) --d;
    return static_cast<int64_t>(d);
}

/// Rust's saturating i64 addition (out + n must never overflow).
int64_t satAdd(int64_t a, int64_t b) {
    if (b > 0 && a > std::numeric_limits<int64_t>::max() - b) {
        return std::numeric_limits<int64_t>::max();
    }
    if (b < 0 && a < std::numeric_limits<int64_t>::min() - b) {
        return std::numeric_limits<int64_t>::min();
    }
    return a + b;
}

} // namespace

StreamResampler::StreamResampler(uint32_t from, uint32_t to)
    : from_(from == 0 ? 1u : from),
      to_(to == 0 ? 1u : to),
      heldStart_(0),
      held_(),
      haveNextOut_(false),
      nextOut_(0) {}

std::pair<int64_t, float> StreamResampler::sourceIndex(int64_t out) const {
    // p = out * from in 128 bits: |out| up to ~9.2e18 keeps p well
    // inside 128 bits for any sane rate (2^63 * 2^32 = 2^95).
    const __int128 p = static_cast<__int128>(out) * static_cast<__int128>(from_);
    const __int128 to = static_cast<__int128>(to_);
    const int64_t s = divFloor(p, to);
    __int128 r = p % to;
    if (r < 0) r += to; // Rust rem_euclid: the fraction lives in [0, 1).
    const double frac = static_cast<double>(r) / static_cast<double>(to);
    return {s, static_cast<float>(frac)};
}

std::vector<std::vector<float>> StreamResampler::process(
    int64_t out,
    size_t n,
    const std::function<std::vector<std::vector<float>>(int64_t start, size_t frames)>& pull) {
    if (n == 0) {
        // filmcraft: `vec![Vec::new(); held.len().max(1)]` — at least one
        // (empty) channel, so a caller can index channel 0 safely.
        std::vector<std::vector<float>> empty(std::max<size_t>(held_.size(), 1));
        return empty;
    }
    const int64_t first = sourceIndex(out).first;
    const int64_t last = sourceIndex(satAdd(out, static_cast<int64_t>(n) - 1)).first;
    // interpolation reads source frames first-1 ..= last
    if (!haveNextOut_ || nextOut_ != out || held_.empty()) {
        heldStart_ = first - 1;
        held_.clear();
    }
    const int64_t heldLen = held_.empty() ? 0 : static_cast<int64_t>(held_[0].size());
    const int64_t heldEnd = heldStart_ + heldLen;
    if (last + 1 > heldEnd) {
        const int64_t missing = last + 1 - heldEnd;
        const int64_t clamped =
            (missing > static_cast<int64_t>(kMaxResampleSourceSpan))
                ? static_cast<int64_t>(kMaxResampleSourceSpan)
                : (missing < 0 ? 0 : missing);
        const size_t want = static_cast<size_t>(clamped);
        const std::vector<std::vector<float>> got = pull(heldEnd, want);
        if (held_.empty()) {
            held_.assign(std::max<size_t>(got.size(), 1), {});
        }
        for (size_t c = 0; c < held_.size(); ++c) {
            const std::vector<float>& src = (c < got.size()) ? got[c] : std::vector<float>();
            held_[c].reserve(held_[c].size() + want);
            for (size_t i = 0; i < want; ++i) {
                held_[c].push_back((i < src.size()) ? src[i] : 0.0f);
            }
        }
    }
    std::vector<std::vector<float>> dst(held_.size(), std::vector<float>(n, 0.0f));
    for (size_t i = 0; i < n; ++i) {
        const int64_t frame = satAdd(out, static_cast<int64_t>(i));
        const std::pair<int64_t, float> sf = sourceIndex(frame);
        const int64_t s = sf.first;
        const float frac = sf.second;
        // held starts at first-1, so the earlier frame is always held
        const int64_t k64 = s - heldStart_;
        const size_t k = (k64 > 0) ? static_cast<size_t>(k64) : 0;
        for (size_t d = 0; d < dst.size(); ++d) {
            const std::vector<float>& h = held_[d];
            const float a = (k >= 1 && k - 1 < h.size()) ? h[k - 1] : 0.0f;
            const float b = (k < h.size()) ? h[k] : 0.0f;
            dst[d][i] = a + (b - a) * frac;
        }
    }
    // keep what the next block can still read: from its first frame - 1 on
    const int64_t nextFirst = sourceIndex(satAdd(out, static_cast<int64_t>(n))).first;
    const int64_t heldLen2 = held_.empty() ? 0 : static_cast<int64_t>(held_[0].size());
    int64_t drop = nextFirst - 1 - heldStart_;
    if (drop < 0) drop = 0;
    if (drop > heldLen2) drop = heldLen2;
    for (std::vector<float>& h : held_) {
        h.erase(h.begin(), h.begin() + static_cast<size_t>(drop));
    }
    heldStart_ += drop;
    nextOut_ = satAdd(out, static_cast<int64_t>(n));
    haveNextOut_ = true;
    return dst;
}

std::vector<MixRow> mixMatrix(size_t fromChannels, size_t toChannels) {
    const float k = kDownmixK;
    std::vector<MixRow> m(toChannels, MixRow(fromChannels, 0.0f));
    if (fromChannels == toChannels) {
        for (size_t i = 0; i < toChannels; ++i) m[i][i] = 1.0f;
        return m;
    }
    if (fromChannels == 2 && toChannels == 1) {
        m[0][0] = k; // BS.775: M = k*L + k*R
        m[0][1] = k;
        return m;
    }
    if (fromChannels == 1 && toChannels == 2) {
        m[0][0] = 1.0f; // dual mono, the deliberate +3 dB
        m[1][0] = 1.0f;
        return m;
    }
    // Unsupported change (e.g. 2 -> 6): a wider target keeps the first
    // channels it can and pads silence — NOTHING is guessed (a guessed
    // 5.1 upmix would invent content).
    const size_t take = std::min(fromChannels, toChannels);
    for (size_t i = 0; i < take; ++i) m[i][i] = 1.0f;
    return m;
}

std::vector<std::vector<float>> applyMixMatrix(
    const std::vector<MixRow>& m,
    const std::vector<const float*>& input,
    size_t n) {
    std::vector<std::vector<float>> out(m.size());
    for (size_t o = 0; o < m.size(); ++o) {
        out[o].assign(n, 0.0f);
        for (size_t i = 0; i < m[o].size() && i < input.size(); ++i) {
            const float g = m[o][i];
            if (g == 0.0f) continue; // filmcraft skips zero gains
            const float* x = input[i];
            for (size_t s = 0; s < n; ++s) out[o][s] += g * x[s];
        }
    }
    return out;
}

std::vector<std::vector<float>> convertChannels(
    const std::vector<const float*>& input,
    size_t n,
    size_t toChannels) {
    return applyMixMatrix(mixMatrix(input.size(), toChannels), input, n);
}

} // namespace rawedit
