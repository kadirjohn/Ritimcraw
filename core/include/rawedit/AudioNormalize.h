// E5 loudness normalization chain (D2, docs/E5_AUDIO_PORT_PLAN.md §4.1).
//
// Chain over the WHOLE clip PCM: measure (the E5-A BS.1770-4 meter,
// core/src/Loudness.cpp — REUSED, never duplicated) -> gain (capped at
// +12 dB) -> look-ahead Limiter at the true-peak ceiling (D1's
// rawedit::Limiter, latency compensated: same length and alignment) ->
// requantize -> MEASURE the result, with at most 2 gain iterations. The
// limiter lets the gain reach the target on clips without true-peak
// headroom instead of clamping it. Pure logic: 16-bit interleaved in,
// 16-bit interleaved out, same layout and length; the pts/block math of
// the exporter never sees this (plan §4.3).
//
// HONESTY CONTRACT ("reported, never guessed"):
//  - shorter than 400 ms, or a gated-invalid measurement (integrated is
//    -inf: silence or everything below the -70 LUFS absolute gate): the
//    PCM is returned UNTOUCHED and the report says why. No gain is ever
//    invented for a signal the standard cannot measure.
//  - the total gain never exceeds +12 dB (a very quiet clip must not get
//    its noise floor lifted by 27 dB). A target that needs more is
//    reported as NormalizeShortfall::GainCapped.
//  - a target the limiter could not deliver inside the ceiling is
//    reported as NormalizeShortfall::Ceiling. Final I/TP are MEASURED on
//    the requantized output; a true peak still above the ceiling is
//    flagged in ceiling_exceeded, never hidden.
//
// NUMBERS: the K-weighted measurement is the E5-A port's own algebra
// (f64 state, f32 audio); this TU applies the gain in f64 and requantizes
// with lround + clamp. Deterministic: fixed formulas, no RNG, and the
// desktop build compiles this TU with -ffp-contract=off (the
// Loudness.cpp precedent) so every target rounds the same.

#pragma once

#include <cstdint>
#include <vector>

namespace rawedit {

/// Why normalizePcm returned the input unchanged.
enum class NormalizeSkipped {
    /// Gain was applied (or verified 0 dB) — not a skip.
    None = 0,
    /// Programme shorter than 400 ms: the BS.1770 gating blocks cannot
    /// measure it honestly.
    TooShort = 1,
    /// Integrated loudness is -inf (silence, or nothing passed the gates).
    Silent = 2,
};

/// Why the final loudness is not at the target.
enum class NormalizeShortfall {
    None = 0,
    /// The target needs more than +12 dB; +12 dB was applied.
    GainCapped = 1,
    /// The limiter could not deliver the target inside the ceiling.
    Ceiling = 2,
};

/// Maximum total gain the chain applies, dB.
constexpr double kMaxNormalizeGainDb = 12.0;
/// Final I within this many LU of the target counts as reached.
constexpr double kReachedLu = 0.2;
/// Requantization slack on the true-peak ceiling check, dB.
constexpr double kCeilingSlackDb = 0.05;

/// What the chain measured and did. The values are as measured, never
/// derived.
struct NormalizeReport {
    /// Integrated loudness of the INPUT, LUFS; -inf when unmeasurable.
    double measured_lufs = 0.0;
    /// True peak of the INPUT, dBTP; -inf when silent.
    double measured_dbtp = 0.0;
    /// Integrated loudness AFTER the applied gain (== measured when
    /// skipped), LUFS.
    double final_lufs = 0.0;
    /// True peak AFTER the applied gain (== measured when skipped), dBTP.
    double final_dbtp = 0.0;
    /// Gain actually applied, dB (0 when skipped).
    double applied_gain_db = 0.0;
    /// Gain application passes run (1 or 2; 0 when skipped).
    int iterations = 0;
    NormalizeSkipped skipped = NormalizeSkipped::None;
    /// Why the target was not reached (None when it was, or skipped).
    NormalizeShortfall shortfall = NormalizeShortfall::None;
    /// True when the measured final true peak is above the ceiling
    /// (beyond a 0.05 dB requantization slack).
    bool ceiling_exceeded = false;
};

/// The whole-chain result: PCM plus the honest report.
struct NormalizeResult {
    /// Interleaved 16-bit PCM, same layout and length as the input.
    std::vector<int16_t> pcm;
    NormalizeReport report;
};

/// Gain (dB) that moves a measurement at [measured_lufs] to [target_lufs],
/// clamped so the resulting true peak stays under [max_true_peak_dbtp]
/// (loudness is sacrificed, not peaks). 0 for unmeasurable input. This is
/// the plan §4.1 helper — the same semantics as the meter's own
/// LoudnessMeter::normalize_gain_db_peak_limited, spelled here so the
/// chain and its callers share one visible definition.
double normalizeGainDbPeakLimited(double measured_lufs, double target_lufs,
                                  double true_peak_dbtp, double max_true_peak_dbtp);

/// Two-pass loudness normalization of one clip's PCM.
///
/// [pcm] interleaved 16-bit, [frames] frames of [channels] channels at
/// [sample_rate] Hz. [target_lufs] the wanted integrated loudness;
/// [tp_dbtp] the true-peak ceiling in dBTP (the Limiter's ceiling). Returns PCM of the same shape plus the
/// honest report. Deterministic: the same input yields bit-identical
/// output on every call and every target.
NormalizeResult normalizePcm(const int16_t *pcm, std::size_t frames,
                             int sample_rate, int channels,
                             double target_lufs, double tp_dbtp);

/// Convenience overload for a whole buffer.
inline NormalizeResult normalizePcm(const std::vector<int16_t> &pcm,
                                   int sample_rate, int channels,
                                   double target_lufs, double tp_dbtp) {
    const std::size_t frames = (channels > 0) ? pcm.size() / static_cast<std::size_t>(channels) : 0;
    return normalizePcm(pcm.data(), frames, sample_rate, channels, target_lufs, tp_dbtp);
}

} // namespace rawedit
