// Audio effect parameter skeleton + per-sample smoothing (E5-C core,
// night D1). docs/E5_AUDIO_PORT_PLAN.md §3.6.
//
// Provenance: a line-by-line port of the filmcraft audio-dsp crate's
// shared layer (crates/audio-dsp/src/lib.rs selections + smooth.rs in
// full, repository ~/projects/filmcraft-android, workspace main
// c41b58467e, read 2026-11; "MIT OR Apache-2.0" per its Cargo.toml;
// the crate states clean-room authorship — SEE NOTICE, audio-dsp
// entry). Carried over: the Unit enum, ParamSpec (id/label/min/max/
// default/unit/log-hint/choices + sanitize — NaN maps to the default,
// choices/toggles round), the ParamValues store shared by the effect
// implementations, db_to_gain / gain_to_db, and smooth.rs's Smoothed
// in full (linear ramp, f64 state, f32 surface — ramps land exactly
// on the target, retarget mid-ramp is continuous, ramp 0 is
// immediate). NOT carried: the Rust REGISTRY / effects() factory (the
// plan's §3.6 note — three effects are constructed by hand in
// Dynamics.h; a dynamic registry is a later package), the
// param_plumbing! macro (its per-method behavior is written out in
// each C++ class), and the crate's flush/flush32 denormal guards
// (rawedit/Loudness.h already ports flush as flushF64 from the E5-A
// half — the crate's dynamics module hand-rolls its own guards
// instead of using flush, so no f32 variant is needed; sharing the
// existing one avoids a second denormal helper).
//
// Rust semantics notes kept by the port: f32::max IGNORES NaN, so the
// effects use std::fmax (same IEEE maxNum behavior); `as u32`/`as
// usize` float casts saturate (NaN/negative -> 0, overflow -> MAX),
// mirrored by rust_as_u32/rust_as_size below.
//
// UNWIRED by design (E5-C core): nothing in the app calls this file;
// the JNI/Kotlin wiring is the later F4/E5 integration package.

#pragma once

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <optional>
#include <string_view>
#include <vector>

namespace rawedit {

// ---- Unit / ParamSpec / ParamValues (lib.rs) ----

/// Unit of a parameter value (for display and control construction).
enum class ParamUnit {
    None,        ///< Unitless scalar.
    Decibels,
    Hertz,
    Milliseconds,
    Seconds,
    Percent,
    Q,           ///< Filter quality factor.
    Ratio,       ///< Compression ratio (x:1).
    Semitones,
    Bpm,
    Pan,         ///< -100 (left) .. +100 (right).
    Toggle,      ///< Boolean: 0 = off, 1 = on.
    Choice,      ///< Index into ParamSpec::choices.
};

/// Static description of one effect parameter (crate ParamSpec).
struct ParamSpec {
    const char *id;             ///< Stable id used by set_param/automation.
    const char *name;           ///< Human-readable label.
    float min;
    float max;
    float default_value;
    ParamUnit unit;
    bool log_scale;             ///< Hint: control should be logarithmic.
    const char *const *choices; ///< Labels when unit == Choice; null otherwise.
    std::size_t choice_count;

    /// A continuous (linear) parameter.
    static ParamSpec make(const char *id, const char *name,
                          float min, float max, float def, ParamUnit unit) {
        return ParamSpec{id, name, min, max, def, unit, false, nullptr, 0};
    }
    /// A continuous parameter best shown on a logarithmic control.
    static ParamSpec log(const char *id, const char *name,
                         float min, float max, float def, ParamUnit unit) {
        return ParamSpec{id, name, min, max, def, unit, true, nullptr, 0};
    }
    /// An on/off toggle.
    static ParamSpec toggle(const char *id, const char *name, bool def) {
        return ParamSpec{id, name, 0.0f, 1.0f, def ? 1.0f : 0.0f,
                         ParamUnit::Toggle, false, nullptr, 0};
    }
    /// A discrete choice; the value is the index into `choices`.
    static ParamSpec choice(const char *id, const char *name,
                            const char *const *choices, std::size_t count,
                            std::size_t def) {
        return ParamSpec{id, name, 0.0f, static_cast<float>(count - 1),
                         static_cast<float>(def), ParamUnit::Choice, false,
                         choices, count};
    }

    /// Clamp (and for choices/toggles, round) a value into this
    /// parameter's domain. NaN maps to the default.
    float sanitize(float v) const {
        if (std::isnan(v)) {
            return default_value;
        }
        v = std::clamp(v, min, max);
        switch (unit) {
        case ParamUnit::Choice:
        case ParamUnit::Toggle:
            return std::round(v);
        default:
            return v;
        }
    }
};

/// Decibels -> linear amplitude (crate db_to_gain).
inline float db_to_gain(float db) { return std::pow(10.0f, db / 20.0f); }

/// Linear amplitude -> decibels (crate gain_to_db; |g|, -inf for 0).
inline float gain_to_db(float g) { return 20.0f * std::log10(std::fabs(g)); }

/// Rust `f32 as u32`: truncates toward zero, saturates at u32::MAX,
/// NaN and negatives become 0.
inline std::uint32_t rust_as_u32(float x) {
    if (std::isnan(x) || x < 0.0f) return 0;
    if (x >= 4294967296.0f) return 0xFFFFFFFFu;
    return static_cast<std::uint32_t>(x);
}

/// Rust `f32 as usize` (64-bit): truncates toward zero, saturates at
/// SIZE_MAX, NaN and negatives become 0.
inline std::size_t rust_as_size(float x) {
    if (std::isnan(x) || x < 0.0f) return 0;
    if (x >= 18446744073709551616.0f) return ~static_cast<std::size_t>(0);
    return static_cast<std::size_t>(x);
}

/// Parameter value storage shared by the effect implementations
/// (crate ParamValues; crate-private there, public here so the effect
/// classes can hold one). Allocations happen only at construction.
class ParamValues {
public:
    explicit ParamValues(const ParamSpec *specs, std::size_t count)
        : specs_(specs), count_(count), values_(count) {
        for (std::size_t i = 0; i < count_; ++i) {
            values_[i] = specs[i].default_value;
        }
    }

    std::size_t count() const { return count_; }
    const ParamSpec &spec(std::size_t i) const { return specs_[i]; }

    /// Index of `id`, or count() when unknown.
    std::size_t index_of(const char *id) const {
        for (std::size_t i = 0; i < count_; ++i) {
            if (std::string_view(specs_[i].id) == id) return i;
        }
        return count_;
    }

    /// Returns true if the id exists (value sanitized into range).
    bool set(const char *id, float v) {
        const std::size_t i = index_of(id);
        if (i >= count_) return false;
        values_[i] = specs_[i].sanitize(v);
        return true;
    }

    /// Current value of a parameter; nullopt when the id is unknown.
    std::optional<float> get(const char *id) const {
        const std::size_t i = index_of(id);
        if (i >= count_) return std::nullopt;
        return values_[i];
    }

    /// Value of a parameter that is known to exist (0 otherwise — a
    /// programming error the crate catches in debug builds).
    float v(const char *id) const { return get(id).value_or(0.0f); }
    bool on(const char *id) const { return v(id) >= 0.5f; }
    std::size_t idx(const char *id) const {
        const float x = v(id);
        return static_cast<std::size_t>(x > 0.0f ? x : 0.0f);
    }

private:
    const ParamSpec *specs_;
    std::size_t count_;
    std::vector<float> values_;
};

// ---- Smoothed (smooth.rs, full file) ----

/// A value that ramps linearly to its target over a fixed number of
/// samples (f64 state, f32 surface — the crate's split, kept).
class Smoothed {
public:
    /// ramp_samples = length of a full transition (0 = no smoothing).
    Smoothed(float value, std::uint32_t ramp_samples)
        : current_(value), target_(value), step_(0.0), remaining_(0),
          ramp_(ramp_samples) {}
    /// Convenience: ramp length from a time in milliseconds.
    static Smoothed with_ms(float value, float sample_rate, float ms) {
        float r = std::round(sample_rate * ms / 1000.0f);
        r = std::fmax(r, 0.0f); // Rust f32::max ignores NaN
        if (r >= 4294967296.0f) r = 4294967295.0f; // `as u32` saturates
        return Smoothed(value, static_cast<std::uint32_t>(r));
    }

    /// Start ramping towards target (no-op when already the target).
    void set(float target) {
        const double t = target;
        if (t == target_) return;
        target_ = t;
        if (ramp_ == 0) {
            current_ = t;
            remaining_ = 0;
        } else {
            remaining_ = ramp_;
            step_ = (t - current_) / static_cast<double>(ramp_);
        }
    }
    /// Jump straight to the target.
    void snap() {
        current_ = target_;
        remaining_ = 0;
    }
    /// Set and jump.
    void set_immediate(float v) {
        target_ = v;
        snap();
    }
    /// Advance one sample and return the new value.
    float tick() {
        if (remaining_ > 0) {
            remaining_ -= 1;
            if (remaining_ == 0) {
                current_ = target_;
            } else {
                current_ += step_;
            }
        }
        return static_cast<float>(current_);
    }
    /// Current value without advancing.
    float value() const { return static_cast<float>(current_); }
    float target() const { return static_cast<float>(target_); }
    bool is_smoothing() const { return remaining_ > 0; }

private:
    double current_;
    double target_;
    double step_;
    std::uint32_t remaining_;
    std::uint32_t ramp_;
};

/// A real-time audio effect operating in place on planar channels
/// (lib.rs AudioEffect trait). Implementations are constructed
/// (allocating) with a sample rate and channel count; after that
/// process(), set_param() and reset() never allocate. All channel
/// slices of one block have the same length (the crate's block_len
/// takes the shortest; here the caller passes the common length n).
/// Channel-generic effects (compressor, gate) process every channel
/// they are given; the limiter processes the channels it was built
/// for and passes the rest through untouched.
class AudioEffect {
public:
    virtual ~AudioEffect() = default;

    /// Registry id (e.g. "compressor").
    virtual const char *id() const = 0;
    /// Parameter descriptions.
    virtual std::size_t param_count() const = 0;
    virtual const ParamSpec *params() const = 0;
    /// Set a parameter by id (clamped into range); false for unknown
    /// ids. Takes effect smoothly from the next processed sample.
    virtual bool set_param(const char *id, float value) = 0;
    /// Current (target) value; nullopt for unknown ids.
    virtual std::optional<float> param(const char *id) const = 0;
    /// Clear all internal state (delay lines, envelopes, filter
    /// memories); smoothed parameters jump to their targets.
    virtual void reset() = 0;
    /// Process one block of n samples per channel in place.
    /// Zero-length blocks are allowed.
    virtual void process(float *const *channels, std::size_t n_ch,
                         std::size_t n) = 0;
    /// Processing latency in samples (look-ahead / STFT delay) for
    /// delay compensation.
    virtual std::size_t latency() const { return 0; }
    /// Analytic magnitude response (dB) at freq Hz for the current
    /// target settings (filter/EQ effects); nullopt when not applicable.
    virtual std::optional<double> response_db(float /*freq*/) const {
        return std::nullopt;
    }
    /// Static input -> output level curve (dB) for `band` (0 for
    /// single-band processors) at the current settings; nullopt when
    /// not applicable.
    virtual std::optional<float> transfer_db(std::size_t /*band*/,
                                             float /*input_db*/) const {
        return std::nullopt;
    }
};

} // namespace rawedit
