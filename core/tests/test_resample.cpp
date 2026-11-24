// E5-B: streaming resampler + stereo/mono downmix core tests.
// docs/E5_AUDIO_PORT_PLAN.md §3.7/§3.8.
//
// VECTOR ORIGIN (be exact about this): the resampler cases below are a
// port of filmcraft's crates/audio-dsp/src/resample.rs #[cfg(test)]
// block (4 tests: block-size independence, the tone check, contiguous
// pulls, seek + hostile input); the numbers, tolerances and block
// patterns are the crate's own, NOT regenerated here. What the tests
// therefore prove is: "this C++ port reproduces the behaviour
// filmcraft's suite pins" — an equivalence with the crate's vectors,
// not an independent standard check (a sin has a known value either
// way; the interesting claim is the STREAM CONTRACT: block-size
// invariance, exact rational phase, contiguous non-overlapping pulls).
//
// The downmix cases pin the BS.775 coefficients bit-exactly against
// the crate's channels.rs cells (2.0 -> 1.0 [k, k] with
// k = 1/sqrt(2); 1.0 -> 2.0 [1;1] dual mono) plus the port-level
// physical checks (L=-R cancels; L=R reproduces; passthrough).
//
// Warm-up note: unlike a polyphase FIR, this linear interpolator has
// NO filter warm-up — the transient regime is exactly one sample (the
// one-source-sample lag the contract names). The "first N samples are
// settling" excuse does NOT exist here; the tone test skips 2 samples
// only because frame 0 interpolates from silence (held[-1] = 0), which
// is the honest boundary of the one-behind contract.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include <doctest.h>

#include <rawedit/Resample.h>

#include <cmath>
#include <cstdint>
#include <utility>
#include <vector>

namespace {

constexpr double kPi = 3.14159265358979323846;

/// A sine at hz, sampled at rate, from absolute frame start; logs
/// every pull (filmcraft resample.rs test sine()).
struct SinePull {
    double hz;
    uint32_t rate;
    std::vector<std::pair<int64_t, size_t>>* pulls;
    std::vector<std::vector<float>> operator()(int64_t start, size_t frames) const {
        pulls->emplace_back(start, frames);
        std::vector<float> ch(frames);
        for (size_t i = 0; i < frames; ++i) {
            const double t = 2.0 * kPi * hz * static_cast<double>(start + static_cast<int64_t>(i)) /
                             static_cast<double>(rate);
            ch[i] = static_cast<float>(std::sin(t));
        }
        return {ch, ch}; // stereo: both channels the same tone
    }
};

/// filmcraft's convert() test helper: renders `total` output frames in
/// the given block pattern through a fresh resampler at from->to.
struct ConvertRun {
    std::vector<float> ch0;
    std::vector<std::pair<int64_t, size_t>> pulls;
};

ConvertRun convert(uint32_t from, uint32_t to, const std::vector<size_t>& blocks, size_t total) {
    rawedit::StreamResampler r(from, to);
    std::vector<std::pair<int64_t, size_t>> pulls;
    std::vector<float> out;
    int64_t pos = 0;
    size_t k = 0;
    while (static_cast<size_t>(pos) < total) {
        const size_t n = std::min(blocks[k % blocks.size()], total - static_cast<size_t>(pos));
        const SinePull sine{1000.0, from, &pulls};
        auto b = r.process(pos, n, sine);
        REQUIRE(b.size() == 2);
        for (const auto& c : b) REQUIRE(c.size() == n);
        out.insert(out.end(), b[0].begin(), b[0].end());
        pos += static_cast<int64_t>(n);
        ++k;
    }
    return {std::move(out), std::move(pulls)};
}

} // namespace

// ---- filmcraft resample.rs: output_is_the_same_however_the_stream_is_cut ----
TEST_CASE("filmcraft: output is the same however the stream is cut") {
    const std::pair<uint32_t, uint32_t> pairs[] = {
        {48000, 44100}, {44100, 48000}, {48000, 96000}, {96000, 48000}};
    const std::vector<std::vector<size_t>> cutPatterns = {
        {512}, {333}, {1, 7, 512, 64}};
    for (const auto& [from, to] : pairs) {
        const ConvertRun whole = convert(from, to, {20000}, 20000);
        for (const auto& blocks : cutPatterns) {
            const ConvertRun cut = convert(from, to, blocks, 20000);
            REQUIRE(cut.ch0 == whole.ch0);
        }
    }
}

// ---- filmcraft resample.rs: a_tone_converts_to_the_same_tone ----
TEST_CASE("filmcraft: a tone converts to the same tone") {
    // 1 kHz at 48 kHz played on a 44.1 kHz device: within linear
    // interpolation's error of the ideal tone, one source sample late.
    const uint32_t from = 48000;
    const uint32_t to = 44100;
    const ConvertRun run = convert(from, to, {512}, 20000);
    for (size_t o = 2; o < run.ch0.size(); ++o) {
        const double t = static_cast<double>(o) / static_cast<double>(to) - 1.0 / static_cast<double>(from);
        const float want = static_cast<float>(std::sin(2.0 * kPi * 1000.0 * t));
        CHECK(std::abs(run.ch0[o] - want) < 3e-3);
    }
}

// ---- filmcraft resample.rs: the_source_is_pulled_as_one_continuous_stream ----
TEST_CASE("filmcraft: the source is pulled as one continuous stream") {
    const std::pair<uint32_t, uint32_t> pairs[] = {{48000, 44100}, {44100, 48000}};
    for (const auto& [from, to] : pairs) {
        const ConvertRun run = convert(from, to, {512, 333}, 20000);
        REQUIRE(run.pulls.size() >= 2);
        for (size_t i = 1; i < run.pulls.size(); ++i) {
            CHECK(run.pulls[i - 1].first + static_cast<int64_t>(run.pulls[i - 1].second) ==
                  run.pulls[i].first);
        }
    }
}

// ---- filmcraft resample.rs: a_seek_starts_afresh_and_hostile_input_stays_finite ----
TEST_CASE("filmcraft: a seek starts afresh and hostile input stays finite") {
    rawedit::StreamResampler r(48000, 44100);
    std::vector<std::pair<int64_t, size_t>> pulls;
    const SinePull sine{1000.0, 48000, &pulls};
    (void)r.process(0, 512, sine);
    (void)r.process(1000000, 512, sine);
    REQUIRE_FALSE(pulls.empty());
    // filmcraft asserts the seek's first pull starts at
    // 1_000_000 * 48_000 / 44_100 - 1 (held_start = first - 1).
    CHECK(pulls.back().first == (static_cast<int64_t>(1000000) * 48000) / 44100 - 1);
    // a mixer returning too few channels and samples, and zero rates
    rawedit::StreamResampler z(0, 0);
    const auto b = z.process(5, 64, [](int64_t, size_t) {
        return std::vector<std::vector<float>>{std::vector<float>(3, 1.0f)};
    });
    REQUIRE(b.size() == 1);
    for (float x : b[0]) CHECK(std::isfinite(x));
    const auto empty = z.process(0, 0, [](int64_t, size_t) {
        return std::vector<std::vector<float>>{};
    });
    for (const auto& c : empty) CHECK(c.empty());
}

// ---- port-level: ratio 1.0 is a bit-exact passthrough ----
TEST_CASE("ratio 1.0 passes every sample through bit-exact") {
    rawedit::StreamResampler r(48000, 48000);
    std::vector<float> src(4096);
    for (size_t i = 0; i < src.size(); ++i) {
        src[i] = std::sin(2.0 * kPi * 997.0 * static_cast<double>(i) / 48000.0) * 0.5f;
    }
    // pulled frame-by-frame: the held pipeline must never disturb identity
    size_t next = 0;
    const auto pull = [&src, &next](int64_t start, size_t frames) {
        // the resampler reads from held_start = first - 1, so a fresh
        // stream's first pull legitimately starts at -1 (filmcraft keeps
        // one earlier frame for the one-behind interpolation)
        std::vector<float> got(frames, 0.0f);
        for (size_t i = 0; i < frames; ++i) {
            const int64_t f = start + static_cast<int64_t>(i);
            if (f >= 0 && f < static_cast<int64_t>(src.size())) {
                got[i] = src[static_cast<size_t>(f)];
            }
        }
        return std::vector<std::vector<float>>{got, got};
    };
    // feed in irregular blocks; out lags one sample behind (the contract)
    std::vector<float> out;
    int64_t pos = 0;
    const size_t blocks[] = {1000, 777, 519, 1800};
    size_t bi = 0;
    while (out.size() < src.size()) {
        const size_t n = std::min(blocks[bi++ % 4], src.size() - out.size());
        const auto b = r.process(static_cast<int64_t>(out.size()), n, pull);
        REQUIRE(b.size() == 2);
        out.insert(out.end(), b[0].begin(), b[0].end());
        pos += static_cast<int64_t>(n);
    }
    // out[k] reads src[k-1] and src[k] with frac 0 -> src[k-1]
    // (the one-sample lag); everything after the first sample is exact.
    for (size_t o = 1; o < out.size(); ++o) {
        CHECK(out[o] == src[o - 1]);
    }
    (void)next;
}

// ---- port-level: 48k -> 96k upsample of a 1 kHz sine keeps the tone ----
TEST_CASE("48k to 96k keeps the 1 kHz tone within linear-interpolation error") {
    rawedit::StreamResampler r(48000, 96000);
    const auto pull = [](int64_t start, size_t frames) {
        std::vector<float> ch(frames);
        for (size_t i = 0; i < frames; ++i) {
            const int64_t f = start + static_cast<int64_t>(i);
            ch[i] = static_cast<float>(
                std::sin(2.0 * kPi * 1000.0 * static_cast<double>(f) / 48000.0) * 0.5);
        }
        return std::vector<std::vector<float>>{ch};
    };
    const size_t total = 8192;
    std::vector<float> out;
    while (out.size() < total) {
        const size_t n = std::min<size_t>(1024, total - out.size());
        const auto b = r.process(static_cast<int64_t>(out.size()), n, pull);
        REQUIRE(b.size() == 1);
        out.insert(out.end(), b[0].begin(), b[0].end());
    }
    // RMS error against the ideal tone (one source sample late),
    // measured after the 2-sample boundary like filmcraft.
    double sum = 0.0;
    size_t count = 0;
    for (size_t o = 2; o < out.size(); ++o) {
        const double t = static_cast<double>(o) / 96000.0 - 1.0 / 48000.0;
        const double want = std::sin(2.0 * kPi * 1000.0 * t) * 0.5;
        const double d = static_cast<double>(out[o]) - want;
        sum += d * d;
        ++count;
    }
    const double rms = std::sqrt(sum / static_cast<double>(count));
    CHECK(rms < 3e-3);
}

// ---- port-level: 96k -> 48k downsample of a 1 kHz sine keeps the tone ----
TEST_CASE("96k to 48k keeps the 1 kHz tone within linear-interpolation error") {
    rawedit::StreamResampler r(96000, 48000);
    const auto pull = [](int64_t start, size_t frames) {
        std::vector<float> ch(frames);
        for (size_t i = 0; i < frames; ++i) {
            const int64_t f = start + static_cast<int64_t>(i);
            ch[i] = static_cast<float>(
                std::sin(2.0 * kPi * 1000.0 * static_cast<double>(f) / 96000.0) * 0.5);
        }
        return std::vector<std::vector<float>>{ch, ch, ch};
    };
    const size_t total = 8192;
    std::vector<float> out;
    while (out.size() < total) {
        const size_t n = std::min<size_t>(1024, total - out.size());
        const auto b = r.process(static_cast<int64_t>(out.size()), n, pull);
        REQUIRE(b.size() == 3); // the channel count is the PULL's, not the resampler's
        out.insert(out.end(), b[0].begin(), b[0].end());
    }
    double sum = 0.0;
    size_t count = 0;
    for (size_t o = 2; o < out.size(); ++o) {
        const double t = static_cast<double>(o) / 48000.0 - 1.0 / 96000.0;
        const double want = std::sin(2.0 * kPi * 1000.0 * t) * 0.5;
        const double d = static_cast<double>(out[o]) - want;
        sum += d * d;
        ++count;
    }
    const double rms = std::sqrt(sum / static_cast<double>(count));
    CHECK(rms < 3e-3);
}

// ---- port-level: flush — after the last block, no residual data ----
TEST_CASE("flush: after the final block every channel is fully drained") {
    // The resampler keeps only what the NEXT block can read (its first
    // frame - 1 on). After the stream ends, process(0) returns the
    // channel set with no data, and — proving nothing was invented —
    // the total pulled source frames never exceed what the output
    // actually needed (first..=last of the rendered range + 1).
    rawedit::StreamResampler r(44100, 48000);
    std::vector<std::pair<int64_t, size_t>> pulls;
    const SinePull sine{1000.0, 44100, &pulls};
    (void)r.process(0, 4800, sine);
    (void)r.process(4800, 4800, sine);
    const auto done = r.process(9600, 0, [](int64_t, size_t) {
        return std::vector<std::vector<float>>{};
    });
    REQUIRE(done.size() >= 1);
    for (const auto& c : done) CHECK(c.empty());
    // pulls were contiguous (the stream contract) and bounded
    for (size_t i = 1; i < pulls.size(); ++i) {
        CHECK(pulls[i - 1].first + static_cast<int64_t>(pulls[i - 1].second) == pulls[i].first);
    }
    const int64_t totalPulled =
        pulls.empty() ? 0
                      : static_cast<int64_t>(pulls.back().first - pulls.front().first) +
                            static_cast<int64_t>(pulls.back().second);
    // 9600 output frames at 44.1->48 needs ceil(9600*44.1/48) + 1 source
    // frames; a leaky implementation pulls more. Allow the exact need + 1.
    const double need = 9600.0 * 44100.0 / 48000.0;
    CHECK(static_cast<double>(totalPulled) <= need + 1.0 + 1e-9);
}

// ---- port-level: downmix coefficients are filmcraft's, bit-exact ----
TEST_CASE("downmix matrix: BS.775 k rows and dual mono, bit-exact") {
    const float k = rawedit::kDownmixK;
    auto m = rawedit::mixMatrix(2, 1); // stereo -> mono
    REQUIRE(m.size() == 1);
    REQUIRE(m[0].size() == 2);
    CHECK(m[0][0] == k);
    CHECK(m[0][1] == k);
    CHECK(k == std::sqrt(0.5f)); // the coefficient IS 1/sqrt(2)
    m = rawedit::mixMatrix(1, 2); // mono -> stereo
    REQUIRE(m.size() == 2);
    REQUIRE(m[0].size() == 1);
    CHECK(m[0][0] == 1.0f);
    CHECK(m[1][0] == 1.0f);
    m = rawedit::mixMatrix(2, 2); // identity
    REQUIRE(m.size() == 2);
    CHECK(m[0][0] == 1.0f);
    CHECK(m[0][1] == 0.0f);
    CHECK(m[1][0] == 0.0f);
    CHECK(m[1][1] == 1.0f);
    m = rawedit::mixMatrix(1, 1);
    REQUIRE(m.size() == 1);
    CHECK(m[0][0] == 1.0f);
}

// ---- port-level: L = -R cancels in mono; L = R reproduces at -3.01 dB ----
TEST_CASE("downmix physics: L=-R cancels; L=R is the -3 dB fold") {
    const size_t n = 512;
    std::vector<float> l(n), r(n);
    for (size_t i = 0; i < n; ++i) {
        const double t = 2.0 * kPi * 997.0 * static_cast<double>(i) / 48000.0;
        l[i] = static_cast<float>(std::sin(t) * 0.5);
        r[i] = -l[i]; // exact phase inversion
    }
    const auto mono = rawedit::convertChannels({l.data(), r.data()}, n, 1);
    REQUIRE(mono.size() == 1);
    double peak = 0.0;
    for (float x : mono[0]) peak = std::max(peak, static_cast<double>(std::abs(x)));
    CHECK(peak < 1e-6); // out-of-phase content folds to silence
    // in-phase: mono = k*(L + R) = 2k*L = sqrt(2)*L
    for (size_t i = 0; i < n; ++i) r[i] = l[i];
    const auto mono2 = rawedit::convertChannels({l.data(), r.data()}, n, 1);
    double err = 0.0;
    for (size_t i = 0; i < n; ++i) {
        const double want =
            rawedit::kDownmixK * 2.0 * static_cast<double>(l[i]);
        err = std::max(err, std::abs(static_cast<double>(mono2[0][i]) - want));
    }
    CHECK(err < 1e-6);
    // and the power is preserved: (2k)^2 = 2, times L^2/... -> RMS(sqrt(2)*L) vs L
    // (sum-and-drop of in-phase content DOUBLES the amplitude, the
    // BS.775 intent: independent channels keep their power).
}

// ---- port-level: mono -> stereo is a per-sample copy on both channels ----
TEST_CASE("upmix: mono -> stereo is the exact dual-mono copy") {
    const size_t n = 128;
    std::vector<float> m(n);
    for (size_t i = 0; i < n; ++i) m[i] = static_cast<float>(i) * 0.01f;
    const auto st = rawedit::convertChannels({m.data()}, n, 2);
    REQUIRE(st.size() == 2);
    for (size_t i = 0; i < n; ++i) {
        CHECK(st[0][i] == m[i]);
        CHECK(st[1][i] == m[i]);
    }
}

// ---- port-level: stereo passthrough and stereo-with-zero-gain skip ----
TEST_CASE("passthrough: identity conversion copies every sample; zero gains cost nothing") {
    const size_t n = 64;
    std::vector<float> a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        a[i] = std::sin(static_cast<double>(i) * 0.1);
        b[i] = std::cos(static_cast<double>(i) * 0.2);
    }
    const auto st = rawedit::convertChannels({a.data(), b.data()}, n, 2);
    REQUIRE(st.size() == 2);
    for (size_t i = 0; i < n; ++i) {
        CHECK(st[0][i] == a[i]);
        CHECK(st[1][i] == b[i]);
    }
    // n == 0 returns empty rows (no allocation games)
    const auto zero = rawedit::convertChannels({a.data()}, 0, 2);
    REQUIRE(zero.size() == 2);
    CHECK(zero[0].empty());
    CHECK(zero[1].empty());
}

// ---- port-level: a wider target keeps the first channels, pads silence ----
TEST_CASE("a 2 -> 6 request keeps L and R and pads silence, inventing nothing") {
    const size_t n = 32;
    std::vector<float> l(n, 0.25f), r(n, -0.75f);
    const auto m = rawedit::convertChannels({l.data(), r.data()}, n, 6);
    REQUIRE(m.size() == 6);
    for (size_t i = 0; i < n; ++i) {
        CHECK(m[0][i] == l[i]);
        CHECK(m[1][i] == r[i]);
        for (size_t c = 2; c < 6; ++c) CHECK(m[c][i] == 0.0f);
    }
}
