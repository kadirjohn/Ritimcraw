// E5 loudness normalization chain — tests (D2, plan §4.1 / task D2).
//
// Synthetic signals only (sine, pink-noise-like, clipped), fixed
// formulas / fixed-seed PRNG, so everything is deterministic. The
// acceptance is the plan's: target convergence |I' - target| <= 0.1 LU
// and TP' <= ceiling (+ the hard-clip band) when the ceiling allows,
// <= 2 iterations, and the honest skips (short, silent) leaving the PCM
// bit-identical. -ffp-contract=off matches the AudioNormalize.cpp build
// (tools/build-desktop.sh, the Loudness.cpp precedent).

#include <rawedit/AudioNormalize.h>
#include <rawedit/Loudness.h>

#include <cmath>
#include <cstdint>
#include <vector>

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

namespace {

using rawedit::NormalizeResult;
using rawedit::NormalizeSkipped;
using rawedit::normalizeGainDbPeakLimited;
using rawedit::normalizePcm;

constexpr double kPi = 3.14159265358979323846;
constexpr double kSr = 48000.0;

/// Deterministic PRNG (the test_loudness Rng port) for pink-like noise.
struct Rng {
    std::uint64_t s;
    explicit Rng(std::uint64_t seed) : s(seed * 0x9E3779B97F4A7C15ULL | 1ULL) {}
    std::uint64_t next_u64() {
        std::uint64_t x = s;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        s = x;
        return x * 0x2545F4914F6CDD1DULL;
    }
    double uniform() { // [-1, 1)
        return static_cast<double>(next_u64() >> 40) / (1ULL << 23) * 2.0 - 1.0;
    }
};

/// Interleaved 16-bit stereo sine of [seconds] at [dbfs] per channel.
std::vector<int16_t> stereoSine(double seconds, double freq, double dbfs, double sr) {
    const std::size_t frames = static_cast<std::size_t>(std::llround(seconds * sr));
    const double amp = std::pow(10.0, dbfs / 20.0);
    std::vector<int16_t> pcm(frames * 2);
    for (std::size_t i = 0; i < frames; ++i) {
        const double x = amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / sr);
        const double r = static_cast<double>(std::lround(x * 32768.0));
        const int16_t s = static_cast<int16_t>(std::min(32767.0, std::max(-32768.0, r)));
        pcm[2 * i] = s;
        pcm[2 * i + 1] = s;
    }
    return pcm;
}

/// Interleaved 16-bit mono sine.
std::vector<int16_t> monoSine(double seconds, double freq, double dbfs, double sr) {
    const std::size_t frames = static_cast<std::size_t>(std::llround(seconds * sr));
    const double amp = std::pow(10.0, dbfs / 20.0);
    std::vector<int16_t> pcm(frames);
    for (std::size_t i = 0; i < frames; ++i) {
        const double x = amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / sr);
        const double r = static_cast<double>(std::lround(x * 32768.0));
        pcm[i] = static_cast<int16_t>(std::min(32767.0, std::max(-32768.0, r)));
    }
    return pcm;
}

/// Paul Kellet pink-ish noise (deterministic seed) mixed with a 997 Hz
/// tone, interleaved stereo. The pink alone is almost all low-frequency
/// power, which the K-weighting's RLB high-pass cuts hard (its gated
/// integrated sits ~20 LU below its RMS — the standard's own weighting,
/// not a fixture bug), so the programme carries a tone at −24 dBFS to
/// be a measurable, normal programme around −21..−25 LUFS with the
/// noise still in the mix.
std::vector<int16_t> pinkLike(double seconds, double sr, std::uint64_t seed) {
    const std::size_t frames = static_cast<std::size_t>(std::llround(seconds * sr));
    Rng rng(seed);
    std::vector<int16_t> pcm(frames * 2);
    double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
    const double toneAmp = std::pow(10.0, -24.0 / 20.0);
    for (std::size_t i = 0; i < frames; ++i) {
        const double white = rng.uniform() * 0.008;
        b0 = 0.99886 * b0 + white * 0.0555179;
        b1 = 0.99332 * b1 + white * 0.0750759;
        b2 = 0.96900 * b2 + white * 0.1538520;
        b3 = 0.86650 * b3 + white * 0.3104856;
        b4 = 0.55000 * b4 + white * 0.5329522;
        b5 = 0.86650 * b5 + white * 0.0168980;
        const double pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362) * 0.11;
        b6 = white * 0.115926;
        const double tone = toneAmp * std::sin(2.0 * kPi * 997.0 * static_cast<double>(i) / sr);
        const double r = static_cast<double>(std::lround((pink + tone) * 32768.0));
        const int16_t s = static_cast<int16_t>(std::min(32767.0, std::max(-32768.0, r)));
        pcm[2 * i] = s;
        pcm[2 * i + 1] = s;
    }
    return pcm;
}

/// A "loud but sparse" signal: −18 dBFS sine with occasional near-full
/// scale spikes — the TP-exceeding-target case (loudness sacrificed).
std::vector<int16_t> peakHeavy(double seconds, double sr) {
    std::vector<int16_t> pcm = stereoSine(seconds, 997.0, -18.0, sr);
    const std::size_t frames = pcm.size() / 2;
    const double spike = 0.999;
    // One spike every 0.5 s, a few samples wide (peak, not energy).
    for (std::size_t k = 0; k * static_cast<std::size_t>(sr / 2) < frames; ++k) {
        const std::size_t base = k * static_cast<std::size_t>(sr / 2);
        for (std::size_t j = 0; j < 4 && base + j < frames; ++j) {
            const int16_t v = static_cast<int16_t>(std::lround(spike * 32767.0 * (1.0 - j * 0.2)));
            pcm[2 * (base + j)] = v;
            pcm[2 * (base + j) + 1] = v;
        }
    }
    return pcm;
}

bool bitIdentical(const std::vector<int16_t> &a, const std::vector<int16_t> &b) {
    return a.size() == b.size() && std::equal(a.begin(), a.end(), b.begin());
}

} // namespace

// ---- the §4.1 gain helper ----

TEST_CASE("normalize: gain helper is the plan's peak-limited gain") {
    using rawedit::LoudnessMeter;
    // Unclamped: exactly the difference.
    CHECK(normalizeGainDbPeakLimited(-30.0, -23.0, -20.0, -1.0) ==
          doctest::Approx(7.0));
    // Clamped: +16 dB wanted, but TP -12 with a -1 ceiling allows +11.
    CHECK(normalizeGainDbPeakLimited(-30.0, -14.0, -12.0, -1.0) ==
          doctest::Approx(11.0));
    // Negative gain never clamps (peaks only fall).
    CHECK(normalizeGainDbPeakLimited(-5.0, -23.0, -0.1, -1.0) ==
          doctest::Approx(-18.0));
    // Unmeasurable input: 0, never a guess.
    CHECK(normalizeGainDbPeakLimited(-HUGE_VAL, -14.0, -12.0, -1.0) == 0.0);
    // Consistent with the meter's own helper (one shared definition).
    CHECK(normalizeGainDbPeakLimited(-30.0, -14.0, -12.0, -1.0) ==
          LoudnessMeter::normalize_gain_db_peak_limited(-30.0, -14.0, -12.0, -1.0));
}

// ---- honest skips ----

TEST_CASE("normalize: shorter than 400 ms is never normalized") {
    auto pcm = stereoSine(0.3, 997.0, -20.0, kSr); // 300 ms
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::TooShort);
    CHECK(r.report.iterations == 0);
    CHECK(r.report.applied_gain_db == 0.0);
    CHECK(bitIdentical(pcm, r.pcm));
    // 400 ms exactly IS measurable (the momentary window fits).
    auto ok = stereoSine(0.4, 997.0, -20.0, kSr);
    NormalizeResult r2 = normalizePcm(ok, 48000, 2, -14.0, -1.0);
    CHECK(r2.report.skipped != NormalizeSkipped::TooShort);
}

TEST_CASE("normalize: silence is never normalized") {
    std::vector<int16_t> pcm(48000 * 2, 0); // 1 s of digital silence
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::Silent);
    CHECK(r.report.iterations == 0);
    CHECK(r.report.applied_gain_db == 0.0);
    CHECK(bitIdentical(pcm, r.pcm));
}

TEST_CASE("normalize: below-gate signal is not guessed either") {
    // 1 s at -80 dBFS: below the -70 LUFS absolute gate — integrated is
    // -inf even though the signal is not digital silence.
    auto pcm = stereoSine(1.0, 997.0, -80.0, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::Silent);
    CHECK(bitIdentical(pcm, r.pcm));
}

// ---- target convergence ----

TEST_CASE("normalize: stereo sine converges to the social target") {
    for (double start : {-23.0, -33.0, -12.0}) {
        auto pcm = stereoSine(20.0, 997.0, start, kSr);
        NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
        INFO("start dBFS " << start);
        CHECK(r.report.skipped == NormalizeSkipped::None);
        CHECK(r.report.iterations <= 2);
        CHECK(std::isfinite(r.report.final_lufs));
        if (r.report.measured_lufs + 12.0 < -14.0 - 0.1) {
            // +12 dB gain cap: the target is out of reach and says so.
            CHECK(r.report.applied_gain_db == doctest::Approx(12.0));
            CHECK(r.report.shortfall == rawedit::NormalizeShortfall::GainCapped);
        } else {
            CHECK(std::fabs(r.report.final_lufs - (-14.0)) <= 0.1);
        }
        // A sine's TP sits at its sample level: starts at -12 must NOT
        // need the ceiling (−12 + 2 = −10 ≤ −1), starts at -23 needs
        // +9 dB → −14 dBTP, still inside.
        CHECK(r.report.final_dbtp <= -1.0 + 0.5);
        // The applied gain is the honest difference (no ceiling active).
        CHECK(r.report.applied_gain_db ==
              doctest::Approx(r.report.final_lufs - r.report.measured_lufs).epsilon(0.02));
    }
}

TEST_CASE("normalize: pink-like noise converges, mono and stereo") {
    for (int ch : {1, 2}) {
        auto pcm = pinkLike(10.0, kSr, 0xC0FFEE + static_cast<std::uint64_t>(ch));
        if (ch == 1) {
            // Downmix the stereo generator by taking the L channel.
            std::vector<int16_t> mono(pcm.size() / 2);
            for (std::size_t i = 0; i < mono.size(); ++i) mono[i] = pcm[2 * i];
            pcm = mono;
        }
        NormalizeResult r = normalizePcm(pcm, 48000, ch, -14.0, -1.0);
        INFO("channels " << ch);
        CHECK(r.report.skipped == NormalizeSkipped::None);
        CHECK(r.report.iterations <= 2);
        if (r.report.measured_lufs + 12.0 < -14.0 - 0.1) {
            // +12 dB gain cap: the target is out of reach and says so.
            CHECK(r.report.applied_gain_db == doctest::Approx(12.0));
            CHECK(r.report.shortfall == rawedit::NormalizeShortfall::GainCapped);
        } else {
            CHECK(std::fabs(r.report.final_lufs - (-14.0)) <= 0.1);
        }
        // Pink noise at the −14 target needs the ceiling at −1: the
        // hard clip may trade a little loudness, but the peak holds.
        CHECK(r.report.final_dbtp <= -1.0 + 0.5);
    }
}

TEST_CASE("normalize: mono sine converges (single-channel weighting)") {
    auto pcm = monoSine(20.0, 997.0, -23.0, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 1, -16.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::None);
    CHECK(r.report.iterations <= 2);
    CHECK(std::fabs(r.report.final_lufs - (-16.0)) <= 0.1);
}

TEST_CASE("normalize: at-target input applies ~0 dB and stays put") {
    // -23 dBFS stereo sine measures ≈ −23 LUFS: targeting −23 is a no-op.
    auto pcm = stereoSine(20.0, 997.0, -23.0, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -23.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::None);
    CHECK(std::fabs(r.report.final_lufs - (-23.0)) <= 0.1);
    CHECK(std::fabs(r.report.applied_gain_db) <= 0.05);
}

// ---- peak-limited case (loudness sacrificed, peak preserved) ----

TEST_CASE("normalize: target-exceeding TP clamps the gain") {
    // Loud sparse signal: integrated ≈ −18-ish LUFS, TP ≈ 0 dBTP. Target
    // −14 wants +4 dB, but a −1 dBTP ceiling on a ≈0 dBTP input clamps
    // the gain to ≈ −1 dB. The peak is preserved, loudness sacrificed —
    // and the chain does NOT keep iterating toward an impossible target.
    auto pcm = peakHeavy(10.0, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::None);
    CHECK(r.report.iterations <= 2);
    // The limiter handles the spikes, so the gain may now exceed the
    // old peak clamp; it stays far below the +12 dB cap.
    CHECK(r.report.applied_gain_db < 6.0);
    // The honest result: TP at/under the ceiling, I short of the target.
    CHECK(r.report.final_dbtp <= -1.0 + 0.6);
    CHECK(r.report.final_lufs < -14.0);
    // No sample ever exceeds the ceiling (the hard-clip guarantee; the
    // true-peak interpolator may see a hair above between samples —
    // that is the limiter-deferral note's territory).
    const double ceilLin = std::pow(10.0, -1.0 / 20.0) * 32768.0 + 1.0;
    for (int16_t s : r.pcm) {
        CHECK(static_cast<double>(s) <= ceilLin);
        CHECK(static_cast<double>(s) >= -ceilLin);
    }
}

TEST_CASE("normalize: clipped input keeps the peak under the ceiling") {
    // Full-scale sine: already clipped-looking, TP ≈ 0 dBTP. Target −14
    // with a −1 ceiling must LOWER it (negative gain, no clamp needed)
    // and land on the target.
    auto pcm = stereoSine(20.0, 997.0, -0.5, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    CHECK(r.report.skipped == NormalizeSkipped::None);
    CHECK(r.report.iterations <= 2);
    CHECK(std::fabs(r.report.final_lufs - (-14.0)) <= 0.1);
    CHECK(r.report.final_dbtp <= -1.0 + 0.6);
}

// ---- determinism ----

TEST_CASE("normalize: the same input is bit-identical every run") {
    auto pcm = pinkLike(6.0, kSr, 42);
    NormalizeResult a = normalizePcm(pcm, 48000, 2, -16.0, -1.0);
    NormalizeResult b = normalizePcm(pcm, 48000, 2, -16.0, -1.0);
    CHECK(bitIdentical(a.pcm, b.pcm));
    CHECK(a.report.applied_gain_db == b.report.applied_gain_db);
    CHECK(a.report.iterations == b.report.iterations);
    CHECK(a.report.final_lufs == b.report.final_lufs);
    CHECK(a.report.final_dbtp == b.report.final_dbtp);
}

TEST_CASE("normalize: output keeps the input's shape and length") {
    auto pcm = stereoSine(1.0, 997.0, -20.0, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    CHECK(r.pcm.size() == pcm.size());
    // Degenerate frames: nothing to do, empty out.
    NormalizeResult empty = normalizePcm(nullptr, 0, 48000, 2, -14.0, -1.0);
    CHECK(empty.pcm.empty());
    CHECK(empty.report.skipped == NormalizeSkipped::TooShort);
}

TEST_CASE("normalize: iterations never exceed the plan's bound") {
    // A sweep of targets on one fixture: every chain stops within 2.
    auto pcm = pinkLike(5.0, kSr, 7);
    for (double target : {-23.0, -20.0, -16.0, -14.0, -12.0}) {
        NormalizeResult r = normalizePcm(pcm, 48000, 2, target, -1.0);
        INFO("target " << target);
        CHECK(r.report.iterations <= 2);
        CHECK(r.report.iterations >= 1);
    }
}

// ---- limiter chain (feat-loudness-limiter) ----

namespace {
std::vector<int16_t> quietMix(double seconds, double sr, int ch) {
    // Sine + deterministic noise, low level (~-41 LUFS, low peak).
    const std::size_t frames = static_cast<std::size_t>(std::llround(seconds * sr));
    Rng rng(99);
    std::vector<int16_t> pcm(frames * static_cast<std::size_t>(ch));
    const double a = std::pow(10.0, -43.0 / 20.0);
    for (std::size_t i = 0; i < frames; ++i) {
        const double x = a * std::sin(2.0 * kPi * 997.0 * static_cast<double>(i) / sr) +
                         a * 0.2 * rng.uniform();
        const int16_t v = static_cast<int16_t>(std::lround(x * 32768.0));
        for (int c = 0; c < ch; ++c) pcm[i * static_cast<std::size_t>(ch) + c] = v;
    }
    return pcm;
}
} // namespace

TEST_CASE("normalize: quiet clip is capped at +12 dB and says so") {
    for (int ch : {1, 2}) {
        auto pcm = quietMix(10.0, kSr, ch);
        NormalizeResult r = normalizePcm(pcm, 48000, ch, -14.0, -1.0);
        INFO("channels " << ch << " I " << r.report.measured_lufs << " -> "
             << r.report.final_lufs << " TP " << r.report.final_dbtp);
        CHECK(r.report.skipped == NormalizeSkipped::None);
        CHECK(r.report.measured_lufs < -30.0);
        CHECK(r.report.applied_gain_db <= 12.0 + 1e-9);
        CHECK(r.report.applied_gain_db == doctest::Approx(12.0));
        CHECK(r.report.shortfall == rawedit::NormalizeShortfall::GainCapped);
        CHECK(r.report.final_lufs ==
              doctest::Approx(r.report.measured_lufs + 12.0).epsilon(0.01));
        CHECK(r.report.final_dbtp <= -1.0 + 0.05);
        CHECK(!r.report.ceiling_exceeded);
        CHECK(r.pcm.size() == pcm.size());
    }
}

TEST_CASE("normalize: little headroom reaches the target through the limiter") {
    // Sine at -18 dBFS with brief peaks at -1.8 dBFS: +4 dB wanted, a
    // hard clamp would give well under that.
    auto pcm = stereoSine(20.0, 997.0, -18.0, kSr);
    const std::size_t frames = pcm.size() / 2;
    const double pk = std::pow(10.0, -1.8 / 20.0) * 32767.0;
    for (std::size_t k = 0; (k + 1) * 24000 < frames; ++k) {
        const std::size_t b = k * 24000;
        for (std::size_t j = 0; j < 6; ++j) {
            const int16_t v = static_cast<int16_t>(std::lround(pk * (1.0 - 0.1 * static_cast<double>(j))));
            pcm[2 * (b + j)] = v;
            pcm[2 * (b + j) + 1] = v;
        }
    }
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -14.0, -1.0);
    INFO("I " << r.report.measured_lufs << " TP " << r.report.measured_dbtp << " -> "
         << r.report.final_lufs << " / " << r.report.final_dbtp << " gain "
         << r.report.applied_gain_db << " it " << r.report.iterations);
    CHECK(r.report.skipped == NormalizeSkipped::None);
    CHECK(r.report.iterations <= 2);
    CHECK(r.report.applied_gain_db > 3.0);
    CHECK(std::fabs(r.report.final_lufs - (-14.0)) <= 0.2);
    CHECK(r.report.final_dbtp <= -1.0 + 0.05);
    CHECK(r.report.shortfall == rawedit::NormalizeShortfall::None);
    CHECK(r.pcm.size() == pcm.size());
}

TEST_CASE("normalize: limiter chain is deterministic and length-exact") {
    auto pcm = quietMix(3.0, kSr, 2);
    NormalizeResult a = normalizePcm(pcm, 48000, 2, -20.0, -1.0);
    NormalizeResult b = normalizePcm(pcm, 48000, 2, -20.0, -1.0);
    CHECK(a.pcm.size() == pcm.size());
    CHECK(bitIdentical(a.pcm, b.pcm));
}

TEST_CASE("normalize: limiter output stays aligned with the input") {
    // A non-limiting chain must not shift the signal: lag 0 correlates best.
    auto pcm = stereoSine(2.0, 440.0, -23.0, kSr);
    NormalizeResult r = normalizePcm(pcm, 48000, 2, -20.7, -1.0);
    const std::size_t frames = pcm.size() / 2;
    auto corr = [&](int lag) {
        double s = 0;
        for (std::size_t i = 4800; i + 4800 < frames; ++i) {
            const std::size_t j = static_cast<std::size_t>(static_cast<long>(i) + lag);
            s += static_cast<double>(pcm[2 * i]) * static_cast<double>(r.pcm[2 * j]);
        }
        return s;
    };
    CHECK(corr(0) >= corr(-1));
    CHECK(corr(0) >= corr(1));
}
