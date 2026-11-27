// Dynamics: compressor, expander/gate, look-ahead brick-wall limiter
// (E5-C core, night D1). docs/E5_AUDIO_PORT_PLAN.md §3.4.
//
// Provenance: a line-by-line port of filmcraft's
// crates/audio-dsp/src/effects/dynamics.rs (repository
// ~/projects/filmcraft-android, workspace main c41b58467e, read
// 2026-11; "MIT OR Apache-2.0" per its Cargo.toml; clean-room per its
// README — SEE NOTICE, audio-dsp entry). Detection is stereo-linked
// (the loudest channel drives one common gain) so the image does not
// shift; gain computers work in dB; attack/release are one-pole
// smoothers on the gain. The true-peak detector shares the E5-A port's
// oversampling core (rawedit/Loudness.h PolyphaseBank + Interpolator)
// — the crate's own oversample.rs module, already ported there; no
// second oversampler exists here.
//
// Rust semantics notes kept by the port (numbers matter):
//   - f32::max IGNORES NaN, so `peak` chains and the detector `det`
//     are std::fmax (IEEE maxNum); the crate relies on this for the
//     NaN-input safety of the detector paths.
//   - The limiter's monotonic min-deque holds (u64 sample index, f32
//     gain); the VecDeque becomes a fixed ring allocated at setup
//     (capacity look+2), with head/tail indices — process() never
//     allocates.
//   - `t.is_multiple_of(65536)` -> (t % 65536) == 0.
//   - f32 `clamp` on the ceiling clip: NaN maps to the upper bound
//     (std::min behavior mirrored explicitly).
//
// The Limiter is the one the E5-A export chain will sit behind
// (docs/E5_AUDIO_PORT_PLAN.md §4.1: normalize -> limiter); it lives
// here because Loudness.h (E5-A first half) shipped without it. Still
// UNWIRED: nothing in the app calls this file; desktop tests only.

#pragma once

#include <rawedit/AudioFx.h>
#include <rawedit/Loudness.h>

#include <cstddef>
#include <cstdint>
#include <vector>

namespace rawedit {

/// One-pole coefficient for a time constant in ms (dynamics.rs coef).
float dynamics_coef(float ms, float sr);

/// 20*log10(max(|x|, 1e-10)) — dynamics.rs lin_to_db.
inline float lin_to_db(float x) { return 20.0f * std::log10(std::fmax(x, 1e-10f)); }

/// Static compressor curve with a quadratic soft knee (output level in
/// dB for input level x dB). Ports dynamics.rs compressor_curve.
float compressor_curve(float x, float threshold, float ratio, float knee);

/// Static downward-expander curve (output level in dB), attenuation
/// limited to `range` dB. Ports dynamics.rs expander_curve.
float expander_curve(float x, float threshold, float ratio, float range);

/// Feed-forward compressor: peak or RMS detector, soft knee,
/// attack/release one-pole on the gain (dB), smoothed make-up gain.
/// The RMS time constant is the crate's hard-coded 10 ms.
class Compressor : public AudioEffect {
public:
    static const ParamSpec *PARAMS();
    static constexpr std::size_t PARAM_COUNT = 7;

    /// sample_rate Hz; channels is accepted for API parity (the
    /// compressor is channel-generic and processes what it is given).
    Compressor(float sample_rate, std::size_t channels);
    ~Compressor() override = default;

    // ---- AudioEffect ----
    const char *id() const override { return "compressor"; }
    std::size_t param_count() const override { return PARAM_COUNT; }
    const ParamSpec *params() const override { return PARAMS(); }
    bool set_param(const char *id, float value) override;
    std::optional<float> param(const char *id) const override;
    void reset() override;
    void process(float *const *channels, std::size_t n_ch,
                 std::size_t n) override;
    std::optional<float> transfer_db(std::size_t band,
                                     float input_db) const override;

    /// Static curve at the current settings.
    float curve(float x_db) const;
    /// Current gain reduction in dB (<= 0), for metering.
    float gain_reduction_db() const { return gr_meter_; }

private:
    void apply_params(bool snap);

    ParamValues pv_;
    float sr_;
    float threshold_;
    float ratio_;
    float knee_;
    bool rms_;
    float att_;
    float rel_;
    float rms_coef_;
    Smoothed makeup_;
    float ms_;      ///< Detector mean square (RMS mode).
    float env_;     ///< Smoothed gain reduction (dB, <= 0).
    float gr_meter_;
};

/// Downward expander / noise gate with hold (ratio >= 10 behaves as a
/// gate). Ports dynamics.rs Gate.
class Gate : public AudioEffect {
public:
    static const ParamSpec *PARAMS();
    static constexpr std::size_t PARAM_COUNT = 6;

    Gate(float sample_rate, std::size_t channels);
    ~Gate() override = default;

    const char *id() const override { return "gate"; }
    std::size_t param_count() const override { return PARAM_COUNT; }
    const ParamSpec *params() const override { return PARAMS(); }
    bool set_param(const char *id, float value) override;
    std::optional<float> param(const char *id) const override;
    void reset() override;
    void process(float *const *channels, std::size_t n_ch,
                 std::size_t n) override;
    std::optional<float> transfer_db(std::size_t band,
                                     float input_db) const override;

    float curve(float x_db) const;

private:
    void apply_params(bool snap);

    ParamValues pv_;
    float sr_;
    float threshold_;
    float ratio_;
    float range_;
    float att_;
    float rel_;
    std::uint32_t hold_;
    std::uint32_t hold_left_;
    float env_;
};

/// Look-ahead brick-wall limiter. The required gain for each
/// (optionally true-peak) detected sample is held for the look-ahead
/// window with a sliding minimum, released with a one-pole, then
/// smoothed with a moving average exactly as long as the look-ahead,
/// so the gain has reached its target when the peak leaves the delay
/// line. A final clip at the ceiling guarantees the sample peak never
/// exceeds it. Ports dynamics.rs Limiter.
class Limiter : public AudioEffect {
public:
    static const ParamSpec *PARAMS();
    static constexpr std::size_t PARAM_COUNT = 4;
    /// Fixed look-ahead time (ms).
    static constexpr float LOOKAHEAD_MS = 5.0f;

    /// sample_rate Hz, channels >= 1 (clamped).
    Limiter(float sample_rate, std::size_t channels);
    ~Limiter() override = default;

    const char *id() const override { return "limiter"; }
    std::size_t param_count() const override { return PARAM_COUNT; }
    const ParamSpec *params() const override { return PARAMS(); }
    bool set_param(const char *id, float value) override;
    std::optional<float> param(const char *id) const override;
    void reset() override;
    void process(float *const *channels, std::size_t n_ch,
                 std::size_t n) override;
    std::size_t latency() const override { return look_ + det_delay_; }

    /// Current gain reduction in dB (<= 0).
    float gain_reduction_db() const { return gr_meter_; }

private:
    void apply_params(bool snap);

    ParamValues pv_;
    float sr_;
    Smoothed in_gain_;
    float ceiling_;
    float rel_;
    bool true_peak_;
    std::size_t look_;       ///< Look-ahead (samples).
    std::size_t det_delay_;  ///< Detector alignment delay (samples).
    PolyphaseBank bank_;
    std::vector<Interpolator> interp_;
    /// Per-channel delay line (ring), length look_ + det_delay_ + 1.
    std::vector<std::vector<float>> delay_;
    std::size_t wpos_;
    /// Monotonic deque for the sliding minimum: (sample index, gain).
    /// Fixed-capacity ring (look_ + 2), head_/tail_ indices;
    /// empty when head_ == tail_; size = tail_ - head_.
    std::vector<std::pair<std::uint64_t, float>> minq_;
    std::size_t minq_head_ = 0;
    std::size_t minq_tail_ = 0;
    /// Box filter ring and running sum.
    std::vector<float> box_ring_;
    std::size_t box_pos_;
    double box_sum_;
    float rel_state_;
    std::uint64_t t_;
    float gr_meter_;
};

} // namespace rawedit
