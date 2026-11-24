// BS.1770-4 / EBU R128 loudness meter + true-peak port — tests (E5-A first
// half, filmcraft audio-dsp vectors). See core/src/Loudness.cpp provenance.
//
// Every TEST_CASE below is a carry-over of a filmcraft audio-dsp crate
// test (crates/audio-dsp/src/loudness.rs #[cfg(test)] mod: 14 tests;
// biquad.rs: 6; oversample.rs: 1) with the SAME tolerances the crate
// uses. The signal generators (sine segments, stereo measurement helper)
// port the crate's testutil + loudness test helpers; the tone_amplitude
// Goertzel-style probe used by the biquad tests is ported as well. The
// ffmpeg oracle (tests/loudness_oracle.rs) is NOT ported: it needs ffmpeg
// in the environment; the scan plan (docs/E5_AUDIO_PORT_PLAN.md E5-A §3.1)
// decided its signal generators travel instead, with fixed expectations —
// the silence / -60 dBFS-ish / gating sections below cover the standard's
// own reference stances (Tech 3341 cases 1-5, Tech 3342 LRA), whose target
// numbers come from the standards themselves, quoted per case in comments.
//
// Determinism: signals are generated from fixed formulas (no RNG), the
// PRNG port (testutil Rng) uses a fixed seed where used; -ffp-contract=off
// matches the Loudness.cpp build (see tools/build-desktop.sh).

#include <rawedit/Loudness.h>

#include <cmath>
#include <cstdint>
#include <vector>

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

namespace {

using rawedit::Biquad;
using rawedit::BiquadCoeffs;
using rawedit::BiquadType;
using rawedit::Interpolator;
using rawedit::LoudnessMeter;
using rawedit::LoudnessSummary;
using rawedit::PolyphaseBank;
using rawedit::energy_to_lufs;
using rawedit::k_prefilter;
using rawedit::k_rlb;
using rawedit::kTpTapsPerPhase;
using rawedit::truePeakFactor;

constexpr double kPi = 3.14159265358979323846;
constexpr double kSr = 48000.0;

double dbfs(double db) { return std::pow(10.0, db / 20.0); }
double db(double x) { return 20.0 * std::log10(x); }

// ---- testutil ports (crate lib.rs #[cfg(test)] mod testutil) ----

/// Tiny deterministic PRNG (xorshift64*), fixed seed (crate Rng).
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
    float uniform() { // [-1, 1)
        return static_cast<float>(static_cast<double>(next_u64() >> 40) / (1ULL << 24) * 2.0 - 1.0);
    }
};

std::vector<float> sine(double freq, double amp, double sr, std::size_t n, double phase) {
    std::vector<float> v(n);
    for (std::size_t i = 0; i < n; ++i) {
        v[i] = static_cast<float>(
            amp * std::sin(2.0 * kPi * freq * static_cast<double>(i) / sr + phase));
    }
    return v;
}

double rms(const std::vector<float> &x) {
    if (x.empty()) return 0.0;
    double s = 0.0;
    for (float v : x) s += static_cast<double>(v) * static_cast<double>(v);
    return std::sqrt(s / static_cast<double>(x.size()));
}

/// Amplitude of the freq component (single-bin DFT correlation).
double tone_amplitude(const std::vector<float> &x, double freq, double sr) {
    double re = 0.0, im = 0.0;
    for (std::size_t i = 0; i < x.size(); ++i) {
        const double w = 2.0 * kPi * freq * static_cast<double>(i) / sr;
        re += static_cast<double>(x[i]) * std::cos(w);
        im += static_cast<double>(x[i]) * std::sin(w);
    }
    return 2.0 * std::sqrt(re * re + im * im) / static_cast<double>(x.size());
}

double lufs_to_energy(double l) { return std::pow(10.0, (l + 0.691) / 10.0); }

// ---- loudness.rs test helpers ----

/// Stereo programme made of (seconds, dBFS) sine segments (crate segments()).
std::vector<float> segments(double freq, double sr, const std::vector<std::pair<double, double>> &segs) {
    std::vector<float> out;
    std::size_t phase_idx = 0;
    for (const auto &seg : segs) {
        const std::size_t n = static_cast<std::size_t>(std::llround(seg.first * sr));
        const double a = dbfs(seg.second);
        for (std::size_t i = 0; i < n; ++i) {
            out.push_back(static_cast<float>(
                a * std::sin(2.0 * kPi * freq * static_cast<double>(phase_idx) / sr)));
            phase_idx += 1;
        }
    }
    return out;
}

LoudnessMeter stereo_measure(const std::vector<float> &x, double sr) {
    // Feed in odd-sized blocks to exercise the sub-block splitting (the
    // crate's chunks(1237)); both channels carry the same mono buffer
    // (crate: m.process(&[chunk, chunk])).
    LoudnessMeter m(sr, 2);
    for (std::size_t pos = 0; pos < x.size(); pos += 1237) {
        const std::size_t len = std::min(x.size() - pos, static_cast<std::size_t>(1237));
        const float *data = x.data() + pos;
        const float *ch[2] = {data, data};
        m.process(ch, 2, len);
    }
    return m;
}

} // namespace

// ============================ oversample.rs =============================

TEST_CASE("oversample: phases have unity DC and interpolate the fs/4 sine") {
    // oversample.rs: phases_have_unity_dc_and_interpolate_sines
    PolyphaseBank bank = PolyphaseBank::make(4);
    for (std::size_t p = 0; p < 4; ++p) {
        float s = 0.0f;
        for (std::size_t j = 0; j < kTpTapsPerPhase; ++j) s += bank.coeffs[p * kTpTapsPerPhase + j];
        CHECK(std::fabs(s - 1.0f) < 1e-5f);
    }
    // Sine at fs/4 with 45 deg phase: samples at +-0.707, true peak 1.0.
    Interpolator ip;
    float peak = 0.0f;
    for (int n = 0; n < 400; ++n) {
        const float x = static_cast<float>(std::sin(kPi / 2.0 * n + kPi / 4.0));
        const float p = ip.push_peak(bank, x);
        if (n > 40) peak = std::max(peak, p);
    }
    CHECK(std::fabs(peak - 1.0f) < 0.02f);
}

// ============================== biquad.rs ===============================

namespace {
double measured_db(const BiquadCoeffs &c, double freq) {
    const std::size_t n = static_cast<std::size_t>(kSr);
    std::vector<float> x = sine(freq, 0.5, kSr, n, 0.0);
    Biquad bq;
    bq.process(c, x.data(), x.size());
    // Skip the transient; measure over the tail.
    const std::vector<float> tail(x.begin() + n / 2, x.end());
    return db(tone_amplitude(tail, freq, kSr) / 0.5);
}
} // namespace

TEST_CASE("biquad: peaking hits its gain at the centre") {
    const BiquadCoeffs c = BiquadCoeffs::design(BiquadType::Peaking, kSr, 1000.0, 1.0, 6.0);
    CHECK(std::fabs(c.magnitude_db(1000.0, kSr) - 6.0) < 1e-9);
    CHECK(std::fabs(c.magnitude_db(20.0, kSr)) < 0.05);
    CHECK(std::fabs(measured_db(c, 1000.0) - 6.0) < 0.05);
}

TEST_CASE("biquad: low/high pass are -3.0103 dB at the cutoff") {
    const double q = 0.7071067811865476; // FRAC_1_SQRT_2
    const BiquadCoeffs lp = BiquadCoeffs::design(BiquadType::LowPass, kSr, 2000.0, q, 0.0);
    const BiquadCoeffs hp = BiquadCoeffs::design(BiquadType::HighPass, kSr, 2000.0, q, 0.0);
    CHECK(std::fabs(lp.magnitude_db(2000.0, kSr) + 3.0103) < 0.01);
    CHECK(std::fabs(hp.magnitude_db(2000.0, kSr) + 3.0103) < 0.01);
    CHECK(std::fabs(lp.magnitude_db(100.0, kSr)) < 0.01);
    CHECK(std::fabs(hp.magnitude_db(15000.0, kSr)) < 0.1);
    // 12 dB/oct slope well past cutoff.
    CHECK(lp.magnitude_db(8000.0, kSr) < -22.0);
    CHECK(std::fabs(measured_db(lp, 2000.0) + 3.0103) < 0.05);
    CHECK(std::fabs(measured_db(hp, 500.0) - hp.magnitude_db(500.0, kSr)) < 0.05);
}

TEST_CASE("biquad: notch is deep at its centre") {
    const BiquadCoeffs c = BiquadCoeffs::design(BiquadType::Notch, kSr, 60.0, 10.0, 0.0);
    CHECK(c.magnitude(60.0, kSr) < 1e-6);
    CHECK(std::fabs(c.magnitude_db(1000.0, kSr)) < 0.01);
    CHECK(measured_db(c, 60.0) < -40.0);
}

TEST_CASE("biquad: shelves reach their gain") {
    const double q = 0.7071067811865476;
    const BiquadCoeffs ls = BiquadCoeffs::design(BiquadType::LowShelf, kSr, 200.0, q, 9.0);
    const BiquadCoeffs hs = BiquadCoeffs::design(BiquadType::HighShelf, kSr, 5000.0, q, -9.0);
    CHECK(std::fabs(ls.magnitude_db(10.0, kSr) - 9.0) < 0.05);
    CHECK(std::fabs(ls.magnitude_db(200.0, kSr) - 4.5) < 0.01);
    CHECK(std::fabs(ls.magnitude_db(10000.0, kSr)) < 0.05);
    CHECK(std::fabs(hs.magnitude_db(23000.0, kSr) + 9.0) < 0.1);
    CHECK(std::fabs(hs.magnitude_db(5000.0, kSr) + 4.5) < 0.01);
    CHECK(std::fabs(measured_db(ls, 40.0) - ls.magnitude_db(40.0, kSr)) < 0.05);
    CHECK(std::fabs(measured_db(hs, 12000.0) - hs.magnitude_db(12000.0, kSr)) < 0.05);
}

TEST_CASE("biquad: bandpass and allpass identities") {
    const BiquadCoeffs bp = BiquadCoeffs::design(BiquadType::BandPass, kSr, 1000.0, 2.0, 0.0);
    CHECK(std::fabs(bp.magnitude_db(1000.0, kSr)) < 1e-9);
    const BiquadCoeffs ap = BiquadCoeffs::design(BiquadType::AllPass, kSr, 1000.0, 2.0, 0.0);
    for (double f : {50.0, 1000.0, 9000.0}) {
        CHECK(std::fabs(ap.magnitude_db(f, kSr)) < 1e-9);
    }
}

TEST_CASE("biquad: state flushes to zero, no denormals") {
    // crate state_flushes_to_zero_no_denormals: after one impulse the
    // recursive state decays to an exact 0 within 2M silent ticks and
    // never goes subnormal en route (the flush() guard's proof).
    const BiquadCoeffs c = BiquadCoeffs::design(BiquadType::LowPass, kSr, 100.0, 0.7, 0.0);
    Biquad bq;
    (void)bq.tick(c, 1.0);
    double last = 1.0;
    for (int i = 0; i < 2'000'000; ++i) {
        last = bq.tick(c, 0.0);
        if (last != 0.0) {
            CHECK(std::fpclassify(static_cast<float>(last)) != FP_SUBNORMAL);
        }
    }
    CHECK(last == 0.0);
}

// ============================ loudness.rs ===============================

TEST_CASE("loudness: K-weighting matches the BS.1770 table at 48 kHz") {
    // loudness.rs: k_weighting_matches_bs1770_table_at_48k — the numbers
    // are the coefficient table printed in ITU-R BS.1770-4 (Table 1/2).
    const BiquadCoeffs p = k_prefilter(48000.0);
    CHECK(std::fabs(p.b0 - 1.53512485958697) < 1e-8);
    CHECK(std::fabs(p.b1 + 2.69169618940638) < 1e-8);
    CHECK(std::fabs(p.b2 - 1.19839281085285) < 1e-8);
    CHECK(std::fabs(p.a1 + 1.69065929318241) < 1e-8);
    CHECK(std::fabs(p.a2 - 0.73248077421585) < 1e-8);
    const BiquadCoeffs r = k_rlb(48000.0);
    CHECK(std::fabs(r.a1 + 1.99004745483398) < 1e-8);
    CHECK(std::fabs(r.a2 - 0.99007225036621) < 1e-8);
}

TEST_CASE("loudness: K-weighting shape is sample-rate independent") {
    for (double sr : {32000.0, 44100.0, 48000.0, 96000.0, 192000.0}) {
        const auto db_at = [&](double f) {
            return 10.0 * std::log10(LoudnessMeter::k_weighting_power(f, sr));
        };
        // ~+0.69 dB at 1 kHz, ~+4 dB high shelf, strong low cut.
        CHECK(std::fabs(db_at(1000.0) - 0.691) < 0.03);
        CHECK(std::fabs(db_at(10000.0) - 4.0) < 0.3);
        CHECK(db_at(20.0) < -10.0);
    }
}

TEST_CASE("loudness: silence and below-gate measure as -inf") {
    // Standard reference stance: a digital silence programme has no
    // gated blocks, so I = -inf and LRA = 0 (BS.1770-4 gating definition).
    LoudnessMeter m(kSr, 2);
    std::vector<float> z(48000 * 2, 0.0f);
    m = stereo_measure(z, kSr); // crate: process(&[&z, &z])
    CHECK(m.integrated() == -HUGE_VAL);
    CHECK(m.loudness_range() == 0.0);
    // A -80 dBFS stereo sine is below the -70 LUFS absolute gate.
    const std::vector<float> x = segments(1000.0, kSr, {{5.0, -80.0}});
    LoudnessMeter m2 = stereo_measure(x, kSr);
    CHECK(m2.integrated() == -HUGE_VAL);
}

TEST_CASE("loudness: EBU Tech 3341 cases 1 and 2 (stereo sine)") {
    // Case 1/2 of the EBU Tech 3341 test cases (reference values quoted
    // by the standard): a stereo sine at the given level has I = M = S =
    // the level (997 Hz at 44.1 kHz, 1 kHz elsewhere), +-0.1 LU.
    struct RateFreq { double sr; double freq; };
    const RateFreq rates[3] = {{48000.0, 1000.0}, {44100.0, 997.0}, {96000.0, 1000.0}};
    for (const RateFreq &rf : rates) {
        for (double level : {-23.0, -33.0}) {
            const std::vector<float> x = segments(rf.freq, rf.sr, {{20.0, level}});
            LoudnessMeter m = stereo_measure(x, rf.sr);
            CHECK(std::fabs(m.integrated() - level) < 0.1);
            CHECK(std::fabs(m.momentary() - level) < 0.1);
            CHECK(std::fabs(m.short_term() - level) < 0.1);
            CHECK(std::fabs(m.max_momentary() - level) < 0.1);
            CHECK(m.loudness_range() < 0.1);
        }
    }
}

TEST_CASE("loudness: interleaved == planar (1e-9)") {
    const std::vector<float> x = segments(1000.0, kSr, {{5.0, -20.0}});
    std::vector<float> il;
    il.reserve(x.size() * 2);
    for (float v : x) {
        il.push_back(v);
        il.push_back(v);
    }
    LoudnessMeter m(kSr, 2);
    m.process_interleaved(il.data(), il.size() / 2);
    LoudnessMeter p = stereo_measure(x, kSr);
    CHECK(std::fabs(m.integrated() - p.integrated()) < 1e-9);
}

TEST_CASE("loudness: EBU Tech 3341 cases 3-4 (gating scenarios) = -23.0 LUFS") {
    // Case 3: -36/-23/-36 dBFS for 10/60/10 s -> exactly -23.0 LUFS once
    // the quieter flanks fall below the relative gate (Tech 3341).
    {
        const std::vector<float> x = segments(1000.0, kSr,
            {{10.0, -36.0}, {60.0, -23.0}, {10.0, -36.0}});
        LoudnessMeter m = stereo_measure(x, kSr);
        CHECK(std::fabs(m.integrated() + 23.0) < 0.1);
    }
    // Case 4: -72/-36/-23/-36/-72 for 10/10/60/10/10 s -> -23.0 LUFS.
    {
        const std::vector<float> x = segments(1000.0, kSr,
            {{10.0, -72.0}, {10.0, -36.0}, {60.0, -23.0}, {10.0, -36.0}, {10.0, -72.0}});
        LoudnessMeter m = stereo_measure(x, kSr);
        CHECK(std::fabs(m.integrated() + 23.0) < 0.1);
    }
}

TEST_CASE("loudness: EBU Tech 3341 case 5 (relative gate) = -23.0 LUFS") {
    const std::vector<float> x = segments(1000.0, kSr,
        {{20.0, -26.0}, {20.1, -20.0}, {20.0, -26.0}});
    LoudnessMeter m = stereo_measure(x, kSr);
    CHECK(std::fabs(m.integrated() + 23.0) < 0.1);
}

TEST_CASE("loudness: EBU Tech 3342 loudness range (4 scenarios, +-1 LU)") {
    struct Case { std::vector<std::pair<double, double>> segs; double expect; };
    const std::vector<Case> cases = {
        {{{20.0, -20.0}, {20.0, -30.0}}, 10.0},
        {{{20.0, -20.0}, {20.0, -15.0}}, 5.0},
        {{{20.0, -40.0}, {20.0, -20.0}}, 20.0},
        {{{20.0, -50.0}, {20.0, -35.0}, {20.0, -20.0}, {20.0, -35.0}, {20.0, -50.0}}, 15.0},
    };
    for (const Case &c : cases) {
        const std::vector<float> x = segments(1000.0, kSr, c.segs);
        LoudnessMeter m = stereo_measure(x, kSr);
        CHECK(std::fabs(m.loudness_range() - c.expect) < 1.0);
    }
}

TEST_CASE("loudness: log sweep matches the analytic K-weighting expectation") {
    const double secs = 20.0;
    const std::size_t n = static_cast<std::size_t>(kSr * secs);
    const double f1 = 20.0, f2 = 20000.0;
    const double k = std::log(f2 / f1);
    const double amp = dbfs(-20.0);
    std::vector<float> x(n);
    double phase = 0.0;
    double expected_e = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        const double f = f1 * std::exp(k * static_cast<double>(i) / static_cast<double>(n));
        x[i] = static_cast<float>(amp * std::sin(phase));
        phase += 2.0 * kPi * f / kSr;
        // Stereo: 2 channels x A^2/2 x |H(f)|^2.
        expected_e += amp * amp * LoudnessMeter::k_weighting_power(f, kSr);
    }
    expected_e /= static_cast<double>(n);
    LoudnessMeter m = stereo_measure(x, kSr);
    const double expect = energy_to_lufs(expected_e);
    // Gating removes the quiet low-frequency start, so integrated >=
    // ungated expectation; the difference must be small for a sweep that
    // is mostly well above the gates.
    const double i = m.integrated();
    CHECK(i >= expect - 0.1);
    CHECK(i - expect < 0.6);
}

TEST_CASE("loudness: surround weights — LFE excluded, surrounds +1.5 dB") {
    // The default 5.1 weight vector: L R C LFE Ls Rs = 1,1,1,0,1.41,1.41.
    const std::vector<float> x = segments(1000.0, kSr, {{10.0, -30.0}});
    std::vector<float> z(x.size(), 0.0f);
    const float *xp = x.data();
    const float *zp = z.data();
    const float *ch_front[6] = {xp, zp, zp, zp, zp, zp};
    const float *ch_surr[6] = {zp, zp, zp, zp, xp, zp};
    const float *ch_lfe[6] = {zp, zp, zp, xp, zp, zp};
    LoudnessMeter front(kSr, 6); front.process(ch_front, 6, x.size());
    LoudnessMeter surr(kSr, 6); surr.process(ch_surr, 6, x.size());
    LoudnessMeter lfe(kSr, 6); lfe.process(ch_lfe, 6, x.size());
    CHECK(std::fabs((surr.integrated() - front.integrated()) - 10.0 * std::log10(1.41)) < 0.01);
    CHECK(lfe.integrated() == -HUGE_VAL);
}

TEST_CASE("loudness: reset clears everything") {
    // crate reset_clears_everything: after reset both meters read empty,
    // and a refeed measures the new programme at its own level.
    std::vector<float> x = segments(1000.0, kSr, {{5.0, -20.0}});
    LoudnessMeter m = stereo_measure(x, kSr);
    m.reset();
    CHECK(m.integrated() == -HUGE_VAL);
    CHECK(m.true_peak() == 0.0);
    x = segments(1000.0, kSr, {{5.0, -30.0}});
    m = stereo_measure(x, kSr); // stereo refeed of the reset meter
    CHECK(std::fabs(m.integrated() + 30.0) < 0.1);
}

TEST_CASE("loudness: normalize helpers") {
    CHECK(LoudnessMeter::normalize_gain_db(-30.0, -23.0) == 7.0);
    CHECK(LoudnessMeter::normalize_gain_db(-HUGE_VAL, -23.0) == 0.0);
    CHECK(LoudnessMeter::normalize_gain_db_peak_limited(-30.0, -23.0, -3.0, -1.0) == 2.0);
    CHECK(LoudnessMeter::normalize_gain_db_peak_limited(-30.0, -23.0, -10.0, -1.0) == 7.0);
    // Applying the gain lands on target.
    const std::vector<float> x = segments(1000.0, kSr, {{10.0, -31.3}});
    LoudnessMeter m = stereo_measure(x, kSr);
    const float g = static_cast<float>(
        std::pow(10.0, LoudnessMeter::normalize_gain_db(m.integrated(), -16.0) / 20.0));
    std::vector<float> y(x.size());
    for (std::size_t i = 0; i < x.size(); ++i) y[i] = x[i] * g;
    LoudnessMeter m2 = stereo_measure(y, kSr);
    CHECK(std::fabs(m2.integrated() + 16.0) < 0.02);
    CHECK(std::fabs(lufs_to_energy(energy_to_lufs(0.3)) - 0.3) < 1e-12);
}

TEST_CASE("loudness: tiny blocks equal big blocks (bit-identical) + mono") {
    // crate tiny_blocks_equal_big_blocks: feeding the same programme in
    // single samples measures BIT-IDENTICALLY to one big block (the ring
    // and filters are order-independent per sample), and mono (1 channel)
    // measures at its own analytic K-weighted value — no dual-mono +3 dB.
    const std::vector<float> x = segments(440.0, kSr, {{4.0, -18.0}});
    LoudnessMeter a(kSr, 1);
    const float *xp = x.data();
    const float *chx[1] = {xp};
    a.process(chx, 1, x.size());
    LoudnessMeter b(kSr, 1);
    b.process(chx, 1, 0); // empty block: no-op (crate: process(&[&[]]))
    for (std::size_t pos = 0; pos < x.size(); ++pos) {
        const float *one[1] = {xp + pos};
        b.process(one, 1, 1);
    }
    CHECK(a.integrated() == b.integrated());
    CHECK(a.true_peak() == b.true_peak());
    // Mono is summed without the dual-mono +3 dB (BS.1770): the mono
    // programme measures at its own analytic K-weighted RMS.
    const double expect = -0.691 + 20.0 * std::log10(rms(x)) +
        10.0 * std::log10(LoudnessMeter::k_weighting_power(440.0, kSr));
    CHECK(std::fabs(a.integrated() - expect) < 0.1);
}

TEST_CASE("loudness: true peak of intersample-peak signals (EBU TP band)") {
    for (double sr : {48000.0, 44100.0}) {
        // fs/4 sine with 45 deg phase: every sample is +-0.5*sqrt(2)*A,
        // sample peak -3.01 dBFS, true peak 0 dBFS.
        const std::size_t n = static_cast<std::size_t>(sr);
        std::vector<float> x(n);
        for (std::size_t i = 0; i < n; ++i) {
            x[i] = static_cast<float>(std::sin(kPi / 2.0 * static_cast<double>(i) + kPi / 4.0));
        }
        LoudnessMeter m = stereo_measure(x, sr);
        CHECK(std::fabs(20.0 * std::log10(m.sample_peak()) + 3.01) < 0.01);
        // EBU Tech 3341 true-peak tolerance: +0.2 / -0.4 dB.
        const double tp = m.true_peak_dbtp();
        CHECK(tp >= -0.4);
        CHECK(tp <= 0.2);
    }
    // An off-grid tone: TP ~= amplitude.
    {
        std::vector<float> x = sine(997.0, dbfs(-6.0), 48000.0, 48000, 0.3);
        LoudnessMeter m = stereo_measure(x, 48000.0);
        CHECK(std::fabs(m.true_peak_dbtp() + 6.0) < 0.2);
    }
    // Tech 3341 case 15-ish: 0 dBFS 11.025 kHz tone at 44.1 kHz sampled
    // off-peak; the oversampled estimate must catch the inter-sample
    // overshoot within the EBU band.
    {
        std::vector<float> x = sine(11025.0, 1.0, 44100.0, 44100, 0.0);
        LoudnessMeter m = stereo_measure(x, 44100.0);
        CHECK(m.true_peak_dbtp() >= -0.4);
        CHECK(m.true_peak_dbtp() <= 0.2);
    }
}

TEST_CASE("loudness: determinism — the same input measures bit-identically") {
    const std::vector<float> x = segments(1000.0, kSr, {{8.0, -20.0}, {4.0, -32.0}});
    LoudnessMeter a = stereo_measure(x, kSr);
    LoudnessMeter b = stereo_measure(x, kSr);
    CHECK(a.integrated() == b.integrated());
    CHECK(a.momentary() == b.momentary());
    CHECK(a.short_term() == b.short_term());
    CHECK(a.max_momentary() == b.max_momentary());
    CHECK(a.max_short_term() == b.max_short_term());
    CHECK(a.loudness_range() == b.loudness_range());
    CHECK(a.true_peak() == b.true_peak());
    CHECK(a.sample_peak() == b.sample_peak());
    // And a fixed-seed noise programme (the only RNG consumer here):
    Rng rng(12345);
    std::vector<float> nz(48000 * 5);
    for (auto &v : nz) v = rng.uniform();
    LoudnessMeter c1 = stereo_measure(nz, kSr);
    LoudnessMeter c2 = stereo_measure(nz, kSr);
    CHECK(c1.integrated() == c2.integrated());
    CHECK(c1.true_peak() == c2.true_peak());
}

TEST_CASE("loudness: truePeakFactor ladder (BS.1770-4 Annex 2)") {
    CHECK(truePeakFactor(44100.0) == 4);
    CHECK(truePeakFactor(48000.0) == 4);
    CHECK(truePeakFactor(88200.0) == 2);
    CHECK(truePeakFactor(96000.0) == 2);
    CHECK(truePeakFactor(176400.0) == 1);
    CHECK(truePeakFactor(192000.0) == 1);
}

TEST_CASE("loudness: a fixed -60 dBFS sine measures at its K-weighted level") {
    // Standing reference stance from the task brief: a constant stereo
    // sine has I = M = S = the sine's dBFS level (for a full-scale-pair
    // amplitude in dBFS the K-weighting gain at ~1 kHz (+0.69 dB), the
    // two-channel power sum (+3 dB) and the -0.691 + 10log10 offset
    // algebra cancel; the crate's tech3341_case1_and_2 test asserts the
    // SAME identity at -23/-33 dBFS and it passes above), so the -60
    // dBFS programme must return -60.0 LUFS within the EBU measurement
    // tolerance. Deterministic and a spec-sheet-quotable number.
    const std::vector<float> x = segments(1000.0, kSr, {{20.0, -60.0}});
    LoudnessMeter m = stereo_measure(x, kSr);
    CHECK(std::fabs(m.integrated() + 60.0) < 0.1);
    CHECK(std::fabs(m.momentary() + 60.0) < 0.1);
    CHECK(std::fabs(m.short_term() + 60.0) < 0.1);
    // 20 dB quieter lands inside the absolute gate.
    const std::vector<float> q = segments(1000.0, kSr, {{20.0, -80.0}});
    LoudnessMeter m2 = stereo_measure(q, kSr);
    CHECK(m2.integrated() == -HUGE_VAL);
}
