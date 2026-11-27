// E5 loudness normalization chain — implementation (D2, plan §4.1).
// See AudioNormalize.h for the contract, the honesty rules and the
// limiter-deferral note. The measurement is the E5-A port
// (core/src/Loudness.cpp) reused through loudness_measure(); this TU
// only owns the gain/clip/verify loop and the 16-bit requantization.

#include <rawedit/AudioNormalize.h>

#include <rawedit/Loudness.h>

#include <algorithm>
#include <cmath>

namespace rawedit {

namespace {

/// 16-bit -> float the plan's one convention (§8.3, the AudioMixer norm).
inline double norm(int16_t x) { return static_cast<double>(x) / 32768.0; }

/// Float -> 16-bit: scale, round half away from zero, clamp. The
/// requantization the second pass of §8.3 describes (±0.5 LSB, ~0.001 LU
/// — inside every tolerance this chain asserts).
inline int16_t toPcm16(double x) {
    const double r = static_cast<double>(std::lround(x * 32768.0));
    const double c = std::min(32767.0, std::max(-32768.0, r));
    return static_cast<int16_t>(c);
}

/// The minimum programme length the gating blocks can measure honestly:
/// one 400 ms momentary window.
inline bool measurableLength(std::size_t frames, double sample_rate) {
    return sample_rate > 0.0 && static_cast<double>(frames) / sample_rate >= 0.4;
}

/// Planar f64 measurement of one de-interleaved buffer
/// ([channel][frame], channels * frames doubles), fed to the meter as
/// f32 (the crate's surface).
LoudnessSummary measure(const std::vector<double> &planar,
                        std::size_t frames, int channels, double sample_rate) {
    std::vector<float> f32(planar.size());
    for (std::size_t i = 0; i < planar.size(); ++i) {
        f32[i] = static_cast<float>(planar[i]);
    }
    std::vector<const float *> chans(static_cast<std::size_t>(channels));
    for (int c = 0; c < channels; ++c) {
        chans[static_cast<std::size_t>(c)] =
            f32.data() + static_cast<std::size_t>(c) * frames;
    }
    return loudness_measure(chans.data(), static_cast<std::size_t>(channels),
                            frames, sample_rate);
}

/// Interleaved 16-bit -> de-interleaved planar f64 ([channel][frame]).
std::vector<double> deinterleave(const int16_t *pcm, std::size_t frames,
                                 int channels) {
    std::vector<double> planar(frames * static_cast<std::size_t>(channels));
    for (std::size_t i = 0; i < frames; ++i) {
        for (int c = 0; c < channels; ++c) {
            planar[static_cast<std::size_t>(c) * frames + i] =
                norm(pcm[i * static_cast<std::size_t>(channels) + c]);
        }
    }
    return planar;
}

} // namespace

double normalizeGainDbPeakLimited(double measured_lufs, double target_lufs,
                                  double true_peak_dbtp, double max_true_peak_dbtp) {
    return LoudnessMeter::normalize_gain_db_peak_limited(
        measured_lufs, target_lufs, true_peak_dbtp, max_true_peak_dbtp);
}

NormalizeResult normalizePcm(const int16_t *pcm, std::size_t frames,
                             int sample_rate, int channels,
                             double target_lufs, double tp_dbtp) {
    NormalizeResult out;
    const std::size_t ch = static_cast<std::size_t>(channels > 0 ? channels : 1);
    const std::size_t n = frames * ch;
    out.pcm.assign(pcm, pcm + n);
    out.report.measured_lufs = -HUGE_VAL;
    out.report.measured_dbtp = -HUGE_VAL;
    out.report.final_lufs = -HUGE_VAL;
    out.report.final_dbtp = -HUGE_VAL;
    if (frames == 0 || channels <= 0 || sample_rate <= 0) {
        // Degenerate request: nothing to measure, nothing to change.
        out.report.skipped = NormalizeSkipped::TooShort;
        return out;
    }

    // The honest gates, in the plan's order: too short -> unmeasurable.
    if (!measurableLength(frames, static_cast<double>(sample_rate))) {
        out.report.skipped = NormalizeSkipped::TooShort;
        return out;
    }

    // The working domain: the de-interleaved planar copy of the input.
    const std::vector<double> planar = deinterleave(pcm, frames, channels);

    // PASS 1 — measure.
    LoudnessSummary s = measure(planar, frames, channels, static_cast<double>(sample_rate));
    out.report.measured_lufs = s.integrated_lufs;
    out.report.measured_dbtp = s.true_peak_dbtp;
    out.report.final_lufs = s.integrated_lufs;
    out.report.final_dbtp = s.true_peak_dbtp;
    if (!std::isfinite(s.integrated_lufs)) {
        // Silence or gated-invalid: never guess a gain.
        out.report.skipped = NormalizeSkipped::Silent;
        return out;
    }

    // The ceiling in linear terms (the hard-clip target, see the header's
    // LIMITER NOTE: D1's look-ahead limiter replaces this clip at merge).
    const double ceiling = std::pow(10.0, tp_dbtp / 20.0);

    // Gain application: from the ORIGINAL planar samples, one clamped
    // requantize per interleaved output sample.
    const auto applyGain = [&](double gainDb) {
        const double g = std::pow(10.0, gainDb / 20.0);
        for (std::size_t i = 0; i < frames; ++i) {
            for (int c = 0; c < channels; ++c) {
                const double x = planar[static_cast<std::size_t>(c) * frames + i];
                const double y = std::min(ceiling, std::max(-ceiling, x * g));
                out.pcm[i * ch + static_cast<std::size_t>(c)] = toPcm16(y);
            }
        }
    };

    double gain = normalizeGainDbPeakLimited(
        s.integrated_lufs, target_lufs, s.true_peak_dbtp, tp_dbtp);
    applyGain(gain);
    out.report.applied_gain_db = gain;
    out.report.iterations = 1;

    // PASS 3 — verify on what will actually be encoded (the requantized
    // samples, as the writer will see them).
    LoudnessSummary v = measure(deinterleave(out.pcm.data(), frames, channels),
                                 frames, channels, static_cast<double>(sample_rate));
    out.report.final_lufs = v.integrated_lufs;
    out.report.final_dbtp = v.true_peak_dbtp;

    const bool converged =
        std::isfinite(v.integrated_lufs) &&
        std::fabs(v.integrated_lufs - target_lufs) <= 0.1;

    if (!converged) {
        // ONE correction pass (the second and last): fix the residual on
        // the ORIGINAL samples (never a compounding gain), still
        // peak-limited by the ceiling rule.
        const double correction = normalizeGainDbPeakLimited(
            v.integrated_lufs, target_lufs, v.true_peak_dbtp, tp_dbtp);
        const double total = gain + correction;
        applyGain(total);
        out.report.applied_gain_db = total;
        out.report.iterations = 2;

        LoudnessSummary v2 = measure(deinterleave(out.pcm.data(), frames, channels),
                                      frames, channels, static_cast<double>(sample_rate));
        out.report.final_lufs = v2.integrated_lufs;
        out.report.final_dbtp = v2.true_peak_dbtp;
    }

    out.report.skipped = NormalizeSkipped::None;
    return out;
}

} // namespace rawedit
