// BS.1770-4 / EBU R128 loudness metering — C++ port (E5-A first half).
//
// Provenance: a line-by-line port of the filmcraft audio-dsp crate
// (crates/audio-dsp/src/loudness.rs, biquad.rs, oversample.rs — repository
// ~/projects/filmcraft-android, workspace main c41b58467e, 2026-11-24;
// "MIT OR Apache-2.0" per its Cargo.toml). The crate states it is
// clean-room ("written from the public standards"); this port carries
// that status over — no standard text is reproduced here, only the
// algorithm as the crate implements it. SEE NOTICE (audio-dsp entry).
//
// What is ported (docs/E5_AUDIO_PORT_PLAN.md §3.1-§3.3, selection for the
// first half of E5-A):
//   - biquad.rs: the transposed-direct-form-II core + the RBJ "Audio EQ
//     Cookbook" coefficient set (the K-weighting stages are derived
//     through the same Coeffs; the filter-type catalogue travels with the
//     biquad because the K-weighting derivation is its main consumer —
//     E5-C's EQ can extend here later without a second biquad code path).
//   - loudness.rs: energy_to_lufs, K-weighting coefficient derivation
//     (stage-1 high shelf + stage-2 RLB high pass, bilinear transform off
//     the analog prototypes so ANY sample rate works; at 48 kHz they
//     match the recommendation's coefficient table), default channel
//     weights, the fixed-memory 0.01 LU histogram (count + exact energy
//     per bin), the streaming LoudnessMeter (400 ms momentary / 3 s
//     short-term / 100 ms hop = 75% overlap, -70 LUFS absolute gate,
//     -10 LU relative gate for integrated, -70/-20 + 10th-95th percentile
//     for LRA per EBU Tech 3342), sample peak, true peak, summary, and
//     the normalize-gain helpers.
//   - oversample.rs: the polyphase FIR interpolator behind the true-peak
//     estimate (Kaiser-windowed sinc, 16 taps/phase, beta 8, per-phase
//     DC-normalised; factor chosen so the effective rate is >= 176.4 kHz
//     — BS.1770-4 Annex 2's "4x at 44.1/48 kHz" rule).
//
// NUMBERS: the Rust crate computes filters/coefficients in f64 and takes
// f32 sample input; the port keeps exactly that split (f64 state and
// algebra, f32 in/out on the audio paths) — same precision points, same
// operation order. flush() (the crate's denormal guard: |x| < 1e-30 -> 0)
// is ported as-is: Android does not guarantee a process-wide FTZ, and the
// crate's own zero-state test relies on it.
//
// API SHAPE: push-based (process a block of planar or interleaved f32),
// so a future wiring can stream AudioMixer blocks straight in without a
// second pass. UNWIRED by design (E5-A first half): nothing in the app
// calls this file yet; JNI and the export normalize loop land with the
// second half of E5-A. Do not instantiate outside desktop tests in the
// meantine.

#pragma once

#include <cmath>
#include <cstddef>
#include <cstdint>

namespace rawedit {

// ---- shared bits (lib.rs of the crate) ----

/// Flush tiny values to zero (denormal protection for recursive state).
inline double flushF64(double x) { return (x < 0.0 ? -x : x) < 1e-30 ? 0.0 : x; }

// ---- biquad (biquad.rs) ----

/// Filter shape (RBJ cookbook designs; ports crate FilterType).
enum class BiquadType {
    Peaking,
    LowShelf,
    HighShelf,
    LowPass,
    HighPass,
    Notch,
    BandPass,
    AllPass,
    /// Constant-bandwidth cut: 1 + (g - 1)*BP(s) — the -3 dB width of the
    /// band-pass stays f/Q however deep the cut (gain_db <= 0), unlike
    /// the RBJ peaking filter whose cut widens with depth.
    Cut,
};

/// Normalised biquad coefficients (a0 == 1), f64.
struct BiquadCoeffs {
    double b0 = 1.0;
    double b1 = 0.0;
    double b2 = 0.0;
    double a1 = 0.0;
    double a2 = 0.0;

    static const BiquadCoeffs IDENTITY;

    /// Normalise by a0 (crate's private norm()).
    static BiquadCoeffs norm(double b0, double b1, double b2, double a0, double a1, double a2);
    /// RBJ cookbook design. freq clamped below Nyquist, q > 0, gain for
    /// peaking/shelf types (shelves read q as the shelf "Q", S=1 == Q=1/sqrt(2)).
    static BiquadCoeffs design(BiquadType kind, double sample_rate, double freq, double q, double gain_db);

    /// |H(e^{jw})| at freq Hz.
    double magnitude(double freq, double sample_rate) const;
    /// magnitude in dB.
    double magnitude_db(double freq, double sample_rate) const;
};

/// One biquad section's state, transposed direct form II, f64, denormal-flushed.
struct Biquad {
    double s1 = 0.0;
    double s2 = 0.0;

    inline double tick(const BiquadCoeffs &c, double x) {
        const double y = c.b0 * x + s1;
        s1 = flushF64(c.b1 * x - c.a1 * y + s2);
        s2 = flushF64(c.b2 * x - c.a2 * y);
        return y;
    }
    void reset() { s1 = 0.0; s2 = 0.0; }
    /// Exact zero state (bit-equal, sign of zero included).
    bool is_clear() const { return s1 == 0.0 && s2 == 0.0 && !std::signbit(s1) && !std::signbit(s2); }
    /// Filter a buffer in place (f32 in/out, f64 internal — the crate contract).
    void process(const BiquadCoeffs &c, float *buf, std::size_t n);
};

// ---- oversample (oversample.rs) ----

/// Taps per polyphase branch.
constexpr std::size_t kTpTapsPerPhase = 16;

/// Oversampling factor giving >= 176.4 kHz effective (BS.1770-4 Annex 2).
std::size_t truePeakFactor(double sample_rate);

/// Polyphase coefficient bank shared by all channels (Kaiser-windowed
/// sinc prototype, beta 8, per-phase unity DC gain), f32 coefficients.
struct PolyphaseBank {
    std::size_t factor = 1;
    float coeffs[kTpTapsPerPhase * 4] = {}; // factor <= 4 (truePeakFactor)

    static PolyphaseBank make(std::size_t factor);
    /// Approximate delay (input samples) of the interpolated window.
    std::size_t delay() const { return kTpTapsPerPhase / 2; }
};

/// Per-channel interpolator history (double-written ring).
struct Interpolator {
    float hist[2 * kTpTapsPerPhase] = {};
    std::size_t pos = 0;

    void reset();
    /// Push one input sample; returns the max |y| of the `factor`
    /// interpolated outputs it produces.
    float push_peak(const PolyphaseBank &bank, float x);
};

// ---- loudness (loudness.rs) ----

/// Loudness of a (channel-weighted) mean-square energy, in LUFS. -inf for e <= 0.
double energy_to_lufs(double e);

/// Stage 1 of K-weighting: the high-shelf ("head") pre-filter.
BiquadCoeffs k_prefilter(double sample_rate);
/// Stage 2 of K-weighting: the RLB high-pass.
BiquadCoeffs k_rlb(double sample_rate);

/// Default BS.1770 channel weights for a channel count (LFE excluded,
/// surrounds +1.41 ~ +1.5 dB; L R C LFE Ls Rs [Lb Rb] order for 5.1/7.1).
void default_channel_weights(std::size_t channels, double *out);

/// Programme loudness measurements (EBU R128 set). Ports LoudnessSummary.
struct LoudnessSummary {
    double integrated_lufs = 0.0;   // -inf when nothing passed the gates
    double loudness_range_lu = 0.0; // LRA (EBU Tech 3342), 0 when too little signal
    double max_momentary_lufs = 0.0;
    double max_short_term_lufs = 0.0;
    double sample_peak_dbfs = 0.0;
    double true_peak_dbtp = 0.0;
};

/// Streaming BS.1770-4 / EBU R128 loudness meter for planar or
/// interleaved f32 audio. Fixed-memory: the histogram is a fixed
/// 0.01-LU bin array (count + exact energy per bin), so arbitrarily long
/// programmes measure without growing allocations.
class LoudnessMeter {
public:
    /// channels at sample_rate Hz, default BS.1770 weights.
    LoudnessMeter(double sample_rate, std::size_t channels);

    double sample_rate() const { return sample_rate_; }
    std::size_t channels() const { return channels_; }

    /// Override per-channel weights (missing entries 1.0, extra ignored).
    void set_channel_weights(const double *weights, std::size_t n);
    /// Clear all measurements and filter state.
    void reset();

    /// Feed a block of planar audio: [n_ch_in][n] channels (extra channels
    /// are ignored, missing channels count as silence — the crate
    /// contract; the sub-block drain order below follows it exactly).
    void process(const float *const *channels, std::size_t n_ch_in, std::size_t n);
    /// Feed interleaved audio (channels() samples per frame).
    void process_interleaved(const float *data, std::size_t frames);

    double momentary() const { return momentary_; }     // last 400 ms, LUFS
    double short_term() const { return short_term_; }  // last 3 s, LUFS
    double max_momentary() const { return max_momentary_; }
    double max_short_term() const { return max_short_term_; }

    /// Gated integrated loudness (BS.1770-4), LUFS; -inf when nothing passed.
    double integrated() const;
    /// The relative gate threshold used above, LUFS; -inf when no blocks.
    double relative_threshold() const;
    /// Loudness range (EBU Tech 3342), LU; 0 when too little signal.
    double loudness_range() const;

    double sample_peak() const;                 // max |sample|, linear
    double sample_peak_channel(std::size_t ch) const;
    double true_peak() const;                   // max interpolated |y|, linear
    double true_peak_channel(std::size_t ch) const;
    double true_peak_dbtp() const;

    LoudnessSummary summary() const;

    // ---- helpers (loudness.rs free functions) ----
    /// Gain (dB) moving measured to target; 0 for unmeasurable input.
    static double normalize_gain_db(double measured_lufs, double target_lufs);
    /// Like normalize_gain_db but limited so the resulting true peak stays
    /// under max_true_peak_dbtp (loudness is sacrificed, not peaks).
    static double normalize_gain_db_peak_limited(
        double measured_lufs, double target_lufs,
        double true_peak_dbtp, double max_true_peak_dbtp);
    /// |H(f)|^2 through both K stages (tests / analytical tooling).
    static double k_weighting_power(double freq, double sample_rate);

private:
    void feed(std::size_t ch, float x);
    void finish_subblock();

    static constexpr std::size_t kHistBins = 9000; // (-70..+20) / 0.01
    static constexpr std::size_t kStSubblocks = 30; // 3 s / 100 ms
    static constexpr std::size_t kMSubblocks = 4;   // 400 ms / 100 ms
    static constexpr double kHistMin = -70.0;
    static constexpr double kHistStep = 0.01;
    static constexpr std::size_t kMaxChannels = 8;

    /// Histogram of block loudness: count + exact energy per bin.
    struct Histogram {
        std::uint64_t count[kHistBins] = {};
        double energy[kHistBins] = {};

        void clear();
        static std::size_t bin_of(double lufs);
        static double bin_centre(std::size_t i);
        void add(double energy);
        /// (mean energy, count) of bins from `from` up; false if empty.
        bool mean_energy_from(std::size_t from, double *e, std::uint64_t *n) const;
        /// Relative-gate start bin `rel` LU below the absolute-gated mean.
        bool relative_gate_bin(double rel, std::size_t *bin) const;
    };

    double sample_rate_;
    std::size_t channels_;
    double weights_[kMaxChannels] = {};
    BiquadCoeffs pre_;
    BiquadCoeffs rlb_;
    Biquad filt_[kMaxChannels][2] = {};
    double acc_[kMaxChannels] = {};       // per-channel sum of squares in the sub-block
    std::size_t sub_len_ = 1;             // samples per 100 ms sub-block
    std::size_t sub_pos_ = 0;
    /// Ring of (weighted energy, sample count) for the last 30 sub-blocks.
    double ring_e_[kStSubblocks] = {};
    std::size_t ring_n_[kStSubblocks] = {};
    std::size_t ring_pos_ = 0;
    std::uint64_t subblocks_seen_ = 0;
    double momentary_ = -HUGE_VAL; // -inf
    double short_term_ = -HUGE_VAL;
    double max_momentary_ = -HUGE_VAL;
    double max_short_term_ = -HUGE_VAL;
    Histogram integrated_hist_;
    Histogram lra_hist_;
    float sample_peak_[kMaxChannels] = {};
    float true_peak_[kMaxChannels] = {};
    PolyphaseBank bank_;
    Interpolator interp_[kMaxChannels];
};

/// Measure a whole planar programme in one call (crate free fn measure()):
/// n_ch channels of n samples each.
LoudnessSummary loudness_measure(const float *const *channels, std::size_t n_ch,
                                  std::size_t n, double sample_rate);

} // namespace rawedit
