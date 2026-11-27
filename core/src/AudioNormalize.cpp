// E5 loudness normalization chain — implementation (D2, plan §4.1).
// See AudioNormalize.h for the contract, the honesty rules and the
// limiter chain. The measurement is the E5-A port
// (core/src/Loudness.cpp) reused through loudness_measure(); this TU
// only owns the gain/clip/verify loop and the 16-bit requantization.

#include <rawedit/AudioNormalize.h>

#include <rawedit/Dynamics.h>
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

    const double sr = static_cast<double>(sample_rate);

    // Pre-limiter gain -> look-ahead Limiter at the true-peak ceiling ->
    // latency-compensated (same length, same alignment) -> requantize.
    // Always from the ORIGINAL samples (never a compounding gain).
    const auto applyChain = [&](double gainDb) {
        const float g = static_cast<float>(std::pow(10.0, gainDb / 20.0));
        Limiter lim(static_cast<float>(sample_rate), static_cast<std::size_t>(channels));
        lim.set_param("ceiling", static_cast<float>(std::min(0.0, std::max(-24.0, tp_dbtp))));
        const std::size_t lat = lim.latency();
        const std::size_t total = frames + lat;
        std::vector<std::vector<float>> buf(ch, std::vector<float>(total, 0.0f));
        std::vector<float *> ptrs(ch);
        for (std::size_t c = 0; c < ch; ++c) {
            for (std::size_t i = 0; i < frames; ++i) {
                buf[c][i] = static_cast<float>(planar[c * frames + i]) * g;
            }
            ptrs[c] = buf[c].data();
        }
        lim.process(ptrs.data(), ch, total);
        for (std::size_t i = 0; i < frames; ++i) {
            for (std::size_t c = 0; c < ch; ++c) {
                out.pcm[i * ch + c] = toPcm16(static_cast<double>(buf[c][i + lat]));
            }
        }
    };

    const auto measureOut = [&]() {
        return measure(deinterleave(out.pcm.data(), frames, channels), frames, channels, sr);
    };

    const double wanted = target_lufs - s.integrated_lufs;
    double gain = std::min(wanted, kMaxNormalizeGainDb);
    applyChain(gain);
    LoudnessSummary v = measureOut();
    int iterations = 1;

    const double err1 = std::isfinite(v.integrated_lufs)
        ? std::fabs(v.integrated_lufs - target_lufs) : HUGE_VAL;
    if (err1 > 0.1 && std::isfinite(v.integrated_lufs)) {
        // The one correction pass: move the pre-limiter gain by the
        // residual, still capped; keep it only when it lands closer.
        const double gain2 = std::min(gain + (target_lufs - v.integrated_lufs),
                                      kMaxNormalizeGainDb);
        if (gain2 != gain) {
            const std::vector<int16_t> first = out.pcm;
            applyChain(gain2);
            LoudnessSummary v2 = measureOut();
            iterations = 2;
            if (std::isfinite(v2.integrated_lufs) &&
                std::fabs(v2.integrated_lufs - target_lufs) < err1) {
                gain = gain2;
                v = v2;
            } else {
                out.pcm = first;
            }
        }
    }

    out.report.applied_gain_db = gain;
    out.report.iterations = iterations;
    out.report.final_lufs = v.integrated_lufs;
    out.report.final_dbtp = v.true_peak_dbtp;
    out.report.ceiling_exceeded = v.true_peak_dbtp > tp_dbtp + kCeilingSlackDb;
    const bool reached = std::isfinite(v.integrated_lufs) &&
        std::fabs(v.integrated_lufs - target_lufs) <= kReachedLu;
    if (!reached) {
        out.report.shortfall = (gain >= kMaxNormalizeGainDb - 1e-9)
            ? NormalizeShortfall::GainCapped : NormalizeShortfall::Ceiling;
    }

    out.report.skipped = NormalizeSkipped::None;
    return out;
}

} // namespace rawedit
