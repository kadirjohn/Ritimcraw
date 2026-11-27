// Dynamics: compressor, gate, look-ahead limiter — C++ port (E5-C
// core, night D1). See Dynamics.h for provenance and the Rust
// semantics notes (NaN-ignoring max, saturating casts, the u64
// monotonic min-deque). Every function mirrors its Rust original line
// by line: same constants, same operation order, same precision
// points (f32 algebra throughout — the crate's dynamics work in f32).
//
// Allocation discipline (crate contract): all buffers are sized in
// the constructors; process()/reset() never allocate. The one
// exception the crate makes — VecDeque::push_back growth — is removed
// by the fixed-capacity ring (capacity reserved at setup).

#include <rawedit/Dynamics.h>

#include <algorithm>
#include <cmath>
#include <utility>

namespace rawedit {

// ---- shared helpers (dynamics.rs) ----

float dynamics_coef(float ms, float sr) {
    if (ms <= 0.0f) return 0.0f;
    return std::exp(-1.0f / (ms * 0.001f * sr));
}

float compressor_curve(float x, float threshold, float ratio, float knee) {
    const float d = x - threshold;
    if (knee > 0.0f && 2.0f * std::fabs(d) <= knee) {
        const float t = d + knee / 2.0f;
        return x + (1.0f / ratio - 1.0f) * t * t / (2.0f * knee);
    }
    if (d > 0.0f) {
        return threshold + d / ratio;
    }
    return x;
}

float expander_curve(float x, float threshold, float ratio, float range) {
    if (x >= threshold) return x;
    // f32::max ignores NaN -> fmax (raw is NaN-safe here anyway).
    const float clamped = std::fmax((x - threshold) * (ratio - 1.0f), -range);
    return x + clamped;
}

// ---- Compressor ----

const ParamSpec *Compressor::PARAMS() {
    static const char *const kDetectorChoices[] = {"Peak", "RMS"};
    static const ParamSpec specs[PARAM_COUNT] = {
        ParamSpec::make("threshold", "Threshold", -60.0f, 0.0f, -20.0f, ParamUnit::Decibels),
        ParamSpec::make("ratio", "Ratio", 1.0f, 30.0f, 4.0f, ParamUnit::Ratio),
        ParamSpec::make("knee", "Knee", 0.0f, 24.0f, 6.0f, ParamUnit::Decibels),
        ParamSpec::log("attack", "Attack", 0.01f, 300.0f, 10.0f, ParamUnit::Milliseconds),
        ParamSpec::log("release", "Release", 1.0f, 3000.0f, 100.0f, ParamUnit::Milliseconds),
        ParamSpec::make("makeup", "Make-up Gain", -40.0f, 40.0f, 0.0f, ParamUnit::Decibels),
        ParamSpec::choice("detector", "Detector", kDetectorChoices, 2, 0),
    };
    return specs;
}

Compressor::Compressor(float sample_rate, std::size_t /*channels*/)
    : pv_(PARAMS(), PARAM_COUNT), sr_(sample_rate), threshold_(0.0f),
      ratio_(1.0f), knee_(0.0f), rms_(false), att_(0.0f), rel_(0.0f),
      rms_coef_(dynamics_coef(10.0f, sample_rate)),
      makeup_(Smoothed::with_ms(1.0f, sample_rate, 20.0f)), ms_(0.0f),
      env_(0.0f), gr_meter_(0.0f) {
    apply_params(true);
}

void Compressor::apply_params(bool snap) {
    threshold_ = pv_.v("threshold");
    ratio_ = pv_.v("ratio");
    knee_ = pv_.v("knee");
    att_ = dynamics_coef(pv_.v("attack"), sr_);
    rel_ = dynamics_coef(pv_.v("release"), sr_);
    rms_ = pv_.idx("detector") == 1;
    makeup_.set(db_to_gain(pv_.v("makeup")));
    if (snap) {
        makeup_.snap();
    }
}

bool Compressor::set_param(const char *id, float value) {
    if (!pv_.set(id, value)) return false;
    apply_params(false);
    return true;
}

std::optional<float> Compressor::param(const char *id) const {
    return pv_.get(id);
}

void Compressor::reset() {
    makeup_.snap();
    ms_ = 0.0f;
    env_ = 0.0f;
    gr_meter_ = 0.0f;
}

float Compressor::curve(float x_db) const {
    return compressor_curve(x_db, threshold_, ratio_, knee_);
}

std::optional<float> Compressor::transfer_db(std::size_t band, float input_db) const {
    if (band != 0) return std::nullopt;
    return curve(input_db) + pv_.v("makeup");
}

void Compressor::process(float *const *channels, std::size_t n_ch, std::size_t n) {
    for (std::size_t i = 0; i < n; ++i) {
        float peak = 0.0f;
        float sq = 0.0f;
        for (std::size_t c = 0; c < n_ch; ++c) {
            const float x = channels[c][i];
            peak = std::fmax(peak, std::fabs(x)); // f32::max ignores NaN
            sq = std::fmax(sq, x * x);
        }
        float level_db;
        if (rms_) {
            ms_ = rms_coef_ * ms_ + (1.0f - rms_coef_) * sq;
            if (ms_ < 1e-30f) {
                ms_ = 0.0f;
            }
            level_db = 10.0f * std::log10(std::fmax(ms_, 1e-20f));
        } else {
            level_db = lin_to_db(peak);
        }
        const float target = curve(level_db) - level_db;
        const float c = target < env_ ? att_ : rel_;
        env_ = c * env_ + (1.0f - c) * target;
        if (std::fabs(env_) < 1e-9f) {
            env_ = 0.0f;
        }
        const float g = db_to_gain(env_) * makeup_.tick();
        for (std::size_t ch = 0; ch < n_ch; ++ch) {
            channels[ch][i] *= g;
        }
    }
    gr_meter_ = env_;
}

// ---- Gate ----

const ParamSpec *Gate::PARAMS() {
    static const ParamSpec specs[PARAM_COUNT] = {
        ParamSpec::make("threshold", "Threshold", -90.0f, 0.0f, -40.0f, ParamUnit::Decibels),
        ParamSpec::log("ratio", "Ratio", 1.0f, 100.0f, 10.0f, ParamUnit::Ratio),
        ParamSpec::make("range", "Range", 0.0f, 90.0f, 60.0f, ParamUnit::Decibels),
        ParamSpec::log("attack", "Attack", 0.01f, 100.0f, 1.0f, ParamUnit::Milliseconds),
        ParamSpec::make("hold", "Hold", 0.0f, 2000.0f, 50.0f, ParamUnit::Milliseconds),
        ParamSpec::log("release", "Release", 1.0f, 3000.0f, 150.0f, ParamUnit::Milliseconds),
    };
    return specs;
}

Gate::Gate(float sample_rate, std::size_t /*channels*/)
    : pv_(PARAMS(), PARAM_COUNT), sr_(sample_rate), threshold_(0.0f),
      ratio_(1.0f), range_(0.0f), att_(0.0f), rel_(0.0f), hold_(0),
      hold_left_(0), env_(0.0f) {
    apply_params(true);
}

void Gate::apply_params(bool /*snap*/) {
    threshold_ = pv_.v("threshold");
    ratio_ = pv_.v("ratio");
    range_ = pv_.v("range");
    att_ = dynamics_coef(pv_.v("attack"), sr_);
    rel_ = dynamics_coef(pv_.v("release"), sr_);
    hold_ = rust_as_u32(pv_.v("hold") * 0.001f * sr_); // Rust `as u32`
}

bool Gate::set_param(const char *id, float value) {
    if (!pv_.set(id, value)) return false;
    apply_params(false);
    return true;
}

std::optional<float> Gate::param(const char *id) const {
    return pv_.get(id);
}

void Gate::reset() {
    env_ = 0.0f;
    hold_left_ = 0;
}

float Gate::curve(float x_db) const {
    return expander_curve(x_db, threshold_, ratio_, range_);
}

std::optional<float> Gate::transfer_db(std::size_t band, float input_db) const {
    if (band != 0) return std::nullopt;
    return curve(input_db);
}

void Gate::process(float *const *channels, std::size_t n_ch, std::size_t n) {
    for (std::size_t i = 0; i < n; ++i) {
        float peak = 0.0f;
        for (std::size_t c = 0; c < n_ch; ++c) {
            peak = std::fmax(peak, std::fabs(channels[c][i]));
        }
        const float level = lin_to_db(peak);
        const float target = curve(level) - level;
        if (target >= env_) {
            // Opening (or staying open): attack.
            env_ = att_ * env_ + (1.0f - att_) * target;
            if (target >= 0.0f) {
                hold_left_ = hold_;
            }
        } else if (hold_left_ > 0) {
            hold_left_ -= 1;
        } else {
            env_ = rel_ * env_ + (1.0f - rel_) * target;
        }
        const float g = db_to_gain(env_);
        for (std::size_t ch = 0; ch < n_ch; ++ch) {
            channels[ch][i] *= g;
        }
    }
}

// ---- Limiter ----

namespace {
/// Rust f32::clamp: NaN passes through unchanged (std source: the two
/// comparisons both fail on NaN); used by the limiter's final clip.
inline float rust_clamp(float v, float lo, float hi) {
    if (v < lo) return lo;
    if (v > hi) return hi;
    return v;
}
} // namespace

const ParamSpec *Limiter::PARAMS() {
    static const ParamSpec specs[PARAM_COUNT] = {
        ParamSpec::make("input_gain", "Input Boost", 0.0f, 24.0f, 0.0f, ParamUnit::Decibels),
        ParamSpec::make("ceiling", "Ceiling", -24.0f, 0.0f, -1.0f, ParamUnit::Decibels),
        ParamSpec::log("release", "Release", 1.0f, 1000.0f, 60.0f, ParamUnit::Milliseconds),
        ParamSpec::toggle("true_peak", "True Peak", true),
    };
    return specs;
}

Limiter::Limiter(float sample_rate, std::size_t channels)
    : pv_(PARAMS(), PARAM_COUNT), sr_(sample_rate),
      in_gain_(Smoothed::with_ms(1.0f, sample_rate, 20.0f)), ceiling_(1.0f),
      rel_(0.0f), true_peak_(true), look_(1), det_delay_(0), wpos_(0),
      box_pos_(0), box_sum_(0.0), rel_state_(1.0f), t_(0), gr_meter_(0.0f) {
    const std::size_t nch = channels == 0 ? 1 : channels;
    bank_ = PolyphaseBank::make(
        std::max<std::size_t>(truePeakFactor(static_cast<double>(sample_rate)), 2));
    look_ = std::max<std::size_t>(rust_as_size(
                  std::round(LOOKAHEAD_MS * 0.001f * sample_rate)),
              1);
    det_delay_ = bank_.delay();
    const std::size_t dlen = look_ + det_delay_ + 1;
    interp_.assign(nch, Interpolator{});
    delay_.assign(nch, std::vector<float>(dlen, 0.0f));
    // Max deque occupancy is look_ + 2 (window look+1 plus the incoming
    // entry before the front pop); one spare slot keeps head == tail
    // meaning "empty" (VecDeque distinguishes them by capacity check).
    minq_.assign(look_ + 3, {0, 0.0f});
    box_ring_.assign(look_ + 1, 1.0f);
    box_pos_ = 0;
    box_sum_ = static_cast<double>(look_ + 1);
    apply_params(true);
}

void Limiter::apply_params(bool snap) {
    in_gain_.set(db_to_gain(pv_.v("input_gain")));
    ceiling_ = db_to_gain(pv_.v("ceiling"));
    rel_ = dynamics_coef(pv_.v("release"), sr_);
    true_peak_ = pv_.on("true_peak");
    if (snap) {
        in_gain_.snap();
    }
}

bool Limiter::set_param(const char *id, float value) {
    if (!pv_.set(id, value)) return false;
    apply_params(false);
    return true;
}

std::optional<float> Limiter::param(const char *id) const {
    return pv_.get(id);
}

void Limiter::reset() {
    in_gain_.snap();
    for (auto &it : interp_) it.reset();
    for (auto &d : delay_) {
        for (float &v : d) v = 0.0f;
    }
    wpos_ = 0;
    minq_head_ = 0;
    minq_tail_ = 0;
    for (float &v : box_ring_) v = 1.0f;
    box_pos_ = 0;
    box_sum_ = static_cast<double>(box_ring_.size());
    rel_state_ = 1.0f;
    t_ = 0;
    gr_meter_ = 0.0f;
}

void Limiter::process(float *const *channels, std::size_t n_ch, std::size_t n) {
    const std::size_t nch = std::min(n_ch, delay_.size());
    const std::size_t dlen = delay_[0].size();
    const std::uint64_t window = static_cast<std::uint64_t>(look_ + 1);
    const std::size_t minq_cap = minq_.size();
    for (std::size_t i = 0; i < n; ++i) {
        const float g_in = in_gain_.tick();
        const std::size_t det_pos = (wpos_ + dlen - det_delay_) % dlen;
        const std::size_t out_pos = (wpos_ + 1) % dlen; // oldest = look + det_delay samples ago
        float det = 0.0f;
        for (std::size_t ch = 0; ch < nch; ++ch) {
            const float x = channels[ch][i] * g_in;
            delay_[ch][wpos_] = x;
            const float aligned = std::fabs(delay_[ch][det_pos]);
            const float tp = true_peak_ ? interp_[ch].push_peak(bank_, x) : 0.0f;
            det = std::fmax(det, std::fmax(aligned, tp)); // f32::max ignores NaN
        }
        const float greq = det > ceiling_ ? ceiling_ / det : 1.0f;
        // Sliding minimum over the last `window` values (monotonic
        // deque on a fixed ring: cap = look + 3 slots for a max
        // occupancy of look + 2, so head == tail is unambiguously
        // "empty" and the tail slot is always writable).
        while (minq_head_ != minq_tail_ &&
               minq_[(minq_tail_ + minq_cap - 1) % minq_cap].second >= greq) {
            minq_tail_ = (minq_tail_ + minq_cap - 1) % minq_cap; // pop_back
        }
        minq_[minq_tail_] = {t_, greq}; // push_back
        minq_tail_ = (minq_tail_ + 1) % minq_cap;
        while (minq_head_ != minq_tail_ &&
               minq_[minq_head_].first + window <= t_) {
            minq_head_ = (minq_head_ + 1) % minq_cap; // pop_front
        }
        const float m = (minq_head_ != minq_tail_) ? minq_[minq_head_].second : 1.0f;
        // Release.
        if (m < rel_state_) {
            rel_state_ = m;
        } else {
            rel_state_ = rel_state_ + (m - rel_state_) * (1.0f - rel_);
        }
        // Moving average over look+1 samples.
        box_sum_ += static_cast<double>(rel_state_) - static_cast<double>(box_ring_[box_pos_]);
        box_ring_[box_pos_] = rel_state_;
        box_pos_ = (box_pos_ + 1) % box_ring_.size();
        const float g = static_cast<float>(box_sum_ / static_cast<double>(box_ring_.size()));
        for (std::size_t ch = 0; ch < nch; ++ch) {
            channels[ch][i] = rust_clamp(delay_[ch][out_pos] * g, -ceiling_, ceiling_);
        }
        wpos_ = (wpos_ + 1) % dlen;
        t_ += 1;
        // Periodically re-sum to cancel floating-point drift.
        if (t_ % 65536 == 0) {
            double s = 0.0;
            for (float v : box_ring_) s += static_cast<double>(v);
            box_sum_ = s;
        }
        gr_meter_ = lin_to_db(g);
    }
}

} // namespace rawedit
