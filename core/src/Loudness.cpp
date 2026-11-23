// BS.1770-4 / EBU R128 loudness metering — C++ port (E5-A first half).
// See Loudness.h for provenance, scope and the precision contract
// (filmcraft audio-dsp crate, loudness.rs + biquad.rs + oversample.rs).
//
// Every function here mirrors its Rust original line by line: same
// constants, same operation order, same precision points (f64 algebra,
// f32 audio surfaces). Where the original used iterator adapters the
// port spells the loops out; behavior is identical.

#include <rawedit/Loudness.h>

#include <algorithm>
#include <cmath>

namespace rawedit {

namespace {
constexpr double kPi = 3.14159265358979323846;

/// Modified Bessel I0 by power series (the crate's 50-term loop with the
/// term < sum * 1e-17 early exit — identical truncation, identical sums).
double bessel_i0(double x) {
    double sum = 1.0;
    double term = 1.0;
    const double q = x * x / 4.0;
    for (int k = 1; k < 50; ++k) {
        term *= q / static_cast<double>(k * k);
        sum += term;
        if (term < sum * 1e-17) {
            break;
        }
    }
    return sum;
}
} // namespace

// ---- BiquadCoeffs ----

const BiquadCoeffs BiquadCoeffs::IDENTITY = BiquadCoeffs{1.0, 0.0, 0.0, 0.0, 0.0};

BiquadCoeffs BiquadCoeffs::norm(double b0, double b1, double b2, double a0, double a1, double a2) {
    return BiquadCoeffs{b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0};
}

double BiquadCoeffs::magnitude(double freq, double sample_rate) const {
    const double w = 2.0 * kPi * freq / sample_rate;
    const double s1 = std::sin(w), c1 = std::cos(w);
    const double s2 = std::sin(2.0 * w), c2 = std::cos(2.0 * w);
    // H = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2), z^-1 = e^{-jw}.
    const double nr = b0 + b1 * c1 + b2 * c2;
    const double ni = -(b1 * s1 + b2 * s2);
    const double dr = 1.0 + a1 * c1 + a2 * c2;
    const double di = -(a1 * s1 + a2 * s2);
    return std::sqrt((nr * nr + ni * ni) / (dr * dr + di * di));
}

double BiquadCoeffs::magnitude_db(double freq, double sample_rate) const {
    return 20.0 * std::log10(magnitude(freq, sample_rate));
}

BiquadCoeffs BiquadCoeffs::design(BiquadType kind, double sample_rate, double freq, double q, double gain_db) {
    // freq.clamp(1.0, sample_rate * 0.49); q.max(1e-3)
    const double f = std::min(std::max(freq, 1.0), sample_rate * 0.49);
    q = std::max(q, 1e-3);
    const double w0 = 2.0 * kPi * f / sample_rate;
    const double sn = std::sin(w0), cs = std::cos(w0);
    const double alpha = sn / (2.0 * q);
    const double a = std::pow(10.0, gain_db / 40.0);
    switch (kind) {
        case BiquadType::LowPass:
            return norm((1.0 - cs) / 2.0, 1.0 - cs, (1.0 - cs) / 2.0, 1.0 + alpha, -2.0 * cs, 1.0 - alpha);
        case BiquadType::HighPass:
            return norm((1.0 + cs) / 2.0, -(1.0 + cs), (1.0 + cs) / 2.0, 1.0 + alpha, -2.0 * cs, 1.0 - alpha);
        case BiquadType::BandPass:
            return norm(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * cs, 1.0 - alpha);
        case BiquadType::Notch:
            return norm(1.0, -2.0 * cs, 1.0, 1.0 + alpha, -2.0 * cs, 1.0 - alpha);
        case BiquadType::AllPass:
            return norm(1.0 - alpha, -2.0 * cs, 1.0 + alpha, 1.0 + alpha, -2.0 * cs, 1.0 - alpha);
        case BiquadType::Cut: {
            const double g = std::pow(10.0, gain_db / 20.0);
            return norm(1.0 + g * alpha, -2.0 * cs, 1.0 - g * alpha, 1.0 + alpha, -2.0 * cs, 1.0 - alpha);
        }
        case BiquadType::Peaking:
            return norm(1.0 + alpha * a, -2.0 * cs, 1.0 - alpha * a,
                        1.0 + alpha / a, -2.0 * cs, 1.0 - alpha / a);
        case BiquadType::LowShelf: {
            const double k = 2.0 * std::sqrt(a) * alpha;
            return norm(
                a * ((a + 1.0) - (a - 1.0) * cs + k),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cs),
                a * ((a + 1.0) - (a - 1.0) * cs - k),
                (a + 1.0) + (a - 1.0) * cs + k,
                -2.0 * ((a - 1.0) + (a + 1.0) * cs),
                (a + 1.0) + (a - 1.0) * cs - k);
        }
        case BiquadType::HighShelf: {
            const double k = 2.0 * std::sqrt(a) * alpha;
            return norm(
                a * ((a + 1.0) + (a - 1.0) * cs + k),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cs),
                a * ((a + 1.0) + (a - 1.0) * cs - k),
                (a + 1.0) - (a - 1.0) * cs + k,
                2.0 * ((a - 1.0) - (a + 1.0) * cs),
                (a + 1.0) - (a - 1.0) * cs - k);
        }
    }
    return BiquadCoeffs::IDENTITY; // unreachable
}

void Biquad::process(const BiquadCoeffs &c, float *buf, std::size_t n) {
    for (std::size_t i = 0; i < n; ++i) {
        buf[i] = static_cast<float>(tick(c, static_cast<double>(buf[i])));
    }
}

// ---- oversample ----

std::size_t truePeakFactor(double sample_rate) {
    if (sample_rate < 88200.0) return 4;
    if (sample_rate < 176400.0) return 2;
    return 1;
}

void Interpolator::reset() {
    for (std::size_t i = 0; i < 2 * kTpTapsPerPhase; ++i) hist[i] = 0.0f;
    pos = 0;
}

float Interpolator::push_peak(const PolyphaseBank &bank, float x) {
    if (bank.factor == 1) {
        return std::fabs(x);
    }
    // Newest sample at window[0]: store so that window[j] = x[n - j].
    pos = (pos == 0) ? kTpTapsPerPhase - 1 : pos - 1;
    hist[pos] = x;
    hist[pos + kTpTapsPerPhase] = x;
    const float *win = &hist[pos];
    float peak = 0.0f;
    for (std::size_t p = 0; p < bank.factor; ++p) {
        const float *c = &bank.coeffs[p * kTpTapsPerPhase];
        float y = 0.0f;
        for (std::size_t j = 0; j < kTpTapsPerPhase; ++j) {
            y += c[j] * win[j];
        }
        peak = std::max(peak, std::fabs(y));
    }
    return peak;
}

PolyphaseBank PolyphaseBank::make(std::size_t factor) {
    factor = std::max<std::size_t>(factor, 1U);
    PolyphaseBank bank;
    bank.factor = factor;
    const std::size_t len = factor * kTpTapsPerPhase;
    const double centre = static_cast<double>(len - 1) / 2.0;
    const double beta = 8.0;
    const double i0b = bessel_i0(beta);
    // Prototype: Kaiser-windowed sinc, cut-off at the original Nyquist.
    double proto[4 * kTpTapsPerPhase] = {};
    for (std::size_t m = 0; m < len; ++m) {
        const double t = (static_cast<double>(m) - centre) / static_cast<double>(factor);
        const double sinc = (std::fabs(t) < 1e-12) ? 1.0 : std::sin(kPi * t) / (kPi * t);
        const double r = (static_cast<double>(m) - centre) / (centre + 1.0);
        const double w = bessel_i0(beta * std::sqrt(std::max(1.0 - r * r, 0.0))) / i0b;
        proto[m] = sinc * w;
    }
    // Per-phase normalisation to unity DC gain.
    for (std::size_t p = 0; p < factor; ++p) {
        double sum = 0.0;
        for (std::size_t j = 0; j < kTpTapsPerPhase; ++j) {
            sum += proto[p + factor * j];
        }
        for (std::size_t j = 0; j < kTpTapsPerPhase; ++j) {
            bank.coeffs[p * kTpTapsPerPhase + j] =
                static_cast<float>(proto[p + factor * j] / sum);
        }
    }
    return bank;
}

// ---- loudness ----

double energy_to_lufs(double e) {
    return (e <= 0.0) ? -HUGE_VAL : -0.691 + 10.0 * std::log10(e);
}

BiquadCoeffs k_prefilter(double sample_rate) {
    // Analog prototype parameters of the BS.1770 shelving stage.
    const double f0 = 1681.974450955533;
    const double gain_db = 3.999843853973347;
    const double q = 0.7071752369554196;
    const double k = std::tan(kPi * f0 / sample_rate);
    const double vh = std::pow(10.0, gain_db / 20.0);
    const double vb = std::pow(vh, 0.4996667741545416);
    const double a0 = 1.0 + k / q + k * k;
    BiquadCoeffs c;
    c.b0 = (vh + vb * k / q + k * k) / a0;
    c.b1 = 2.0 * (k * k - vh) / a0;
    c.b2 = (vh - vb * k / q + k * k) / a0;
    c.a1 = 2.0 * (k * k - 1.0) / a0;
    c.a2 = (1.0 - k / q + k * k) / a0;
    return c;
}

BiquadCoeffs k_rlb(double sample_rate) {
    const double f0 = 38.13547087602444;
    const double q = 0.5003270373238773;
    const double k = std::tan(kPi * f0 / sample_rate);
    const double a0 = 1.0 + k / q + k * k;
    BiquadCoeffs c;
    c.b0 = 1.0;
    c.b1 = -2.0;
    c.b2 = 1.0;
    c.a1 = 2.0 * (k * k - 1.0) / a0;
    c.a2 = (1.0 - k / q + k * k) / a0;
    return c;
}

void default_channel_weights(std::size_t channels, double *out) {
    // L R C LFE Ls Rs [Lb Rb]; LFE excluded, surrounds +1.41.
    switch (channels) {
        case 5:
            out[0] = 1.0; out[1] = 1.0; out[2] = 1.0; out[3] = 1.41; out[4] = 1.41;
            return;
        case 6:
            out[0] = 1.0; out[1] = 1.0; out[2] = 1.0; out[3] = 0.0; out[4] = 1.41; out[5] = 1.41;
            return;
        case 8:
            out[0] = 1.0; out[1] = 1.0; out[2] = 1.0; out[3] = 0.0;
            out[4] = 1.41; out[5] = 1.41; out[6] = 1.41; out[7] = 1.41;
            return;
        default:
            for (std::size_t i = 0; i < channels; ++i) out[i] = 1.0;
    }
}

// Histogram
void LoudnessMeter::Histogram::clear() {
    for (std::size_t i = 0; i < kHistBins; ++i) {
        count[i] = 0;
        energy[i] = 0.0;
    }
}

std::size_t LoudnessMeter::Histogram::bin_of(double l) {
    const double x = std::floor((l - kHistMin) / kHistStep);
    return std::min<std::size_t>(
        static_cast<std::size_t>(std::max(x, 0.0)), kHistBins - 1);
}

double LoudnessMeter::Histogram::bin_centre(std::size_t i) {
    return kHistMin + (static_cast<double>(i) + 0.5) * kHistStep;
}

void LoudnessMeter::Histogram::add(double e) {
    const double l = energy_to_lufs(e);
    if (l >= kHistMin) { // absolute gate
        const std::size_t b = bin_of(l);
        count[b] += 1;
        energy[b] += e;
    }
}

bool LoudnessMeter::Histogram::mean_energy_from(std::size_t from, double *e, std::uint64_t *n) const {
    double sum = 0.0;
    std::uint64_t c = 0;
    for (std::size_t i = from; i < kHistBins; ++i) {
        sum += energy[i];
        c += count[i];
    }
    if (c == 0) return false;
    *e = sum / static_cast<double>(c);
    *n = c;
    return true;
}

bool LoudnessMeter::Histogram::relative_gate_bin(double rel, std::size_t *bin) const {
    double e;
    std::uint64_t n;
    if (!mean_energy_from(0, &e, &n)) return false;
    const double thr = energy_to_lufs(e) + rel;
    // Rust: `if thr < HIST_MIN { 0 } else { Self::bin_of(thr) }`
    *bin = (thr < kHistMin) ? 0 : bin_of(thr);
    return true;
}

// LoudnessMeter
LoudnessMeter::LoudnessMeter(double sample_rate, std::size_t channels)
    : sample_rate_(sample_rate),
      channels_(std::max<std::size_t>(channels, 1)) {
    sub_len_ = std::max<std::size_t>(
        static_cast<std::size_t>(std::llround(sample_rate_ * 0.1)), 1);
    default_channel_weights(channels_, weights_);
    pre_ = k_prefilter(sample_rate_);
    rlb_ = k_rlb(sample_rate_);
    for (std::size_t ch = 0; ch < channels_; ++ch) {
        filt_[ch][0] = Biquad{};
        filt_[ch][1] = Biquad{};
    }
    bank_ = PolyphaseBank::make(truePeakFactor(sample_rate_));
    reset();
}

void LoudnessMeter::set_channel_weights(const double *weights, std::size_t n) {
    for (std::size_t i = 0; i < channels_; ++i) {
        weights_[i] = (i < n) ? weights[i] : 1.0;
    }
}

void LoudnessMeter::reset() {
    integrated_hist_.clear();
    lra_hist_.clear();
    for (std::size_t ch = 0; ch < channels_; ++ch) {
        filt_[ch][0].reset();
        filt_[ch][1].reset();
        acc_[ch] = 0.0;
        sample_peak_[ch] = 0.0f;
        true_peak_[ch] = 0.0f;
        interp_[ch].reset();
    }
    sub_pos_ = 0;
    for (std::size_t i = 0; i < kStSubblocks; ++i) {
        ring_e_[i] = 0.0;
        ring_n_[i] = 0;
    }
    ring_pos_ = 0;
    subblocks_seen_ = 0;
    momentary_ = -HUGE_VAL;
    short_term_ = -HUGE_VAL;
    max_momentary_ = -HUGE_VAL;
    max_short_term_ = -HUGE_VAL;
}

inline void LoudnessMeter::feed(std::size_t ch, float x) {
    Biquad &pre = filt_[ch][0];
    Biquad &rlb = filt_[ch][1];
    const double y = rlb.tick(rlb_, pre.tick(pre_, static_cast<double>(x)));
    acc_[ch] += y * y;
    const float a = std::fabs(x);
    if (a > sample_peak_[ch]) {
        sample_peak_[ch] = a;
    }
    const float tp = std::max(interp_[ch].push_peak(bank_, x), a);
    if (tp > true_peak_[ch]) {
        true_peak_[ch] = tp;
    }
}

void LoudnessMeter::process(const float *const *channels, std::size_t n_ch_in, std::size_t n) {
    const std::size_t in = std::min(n_ch_in, channels_);
    std::size_t pos = 0;
    while (pos < n) {
        const std::size_t take = std::min(n - pos, sub_len_ - sub_pos_);
        for (std::size_t ch = 0; ch < in; ++ch) {
            const float *data = channels[ch];
            for (std::size_t i = pos; i < pos + take; ++i) {
                feed(ch, data[i]);
            }
        }
        // Missing channels count as silence (the slice contract's
        // "channels.len()..self.channels" arm).
        for (std::size_t ch = in; ch < channels_; ++ch) {
            for (std::size_t i = 0; i < take; ++i) {
                feed(ch, 0.0f);
            }
        }
        pos += take;
        sub_pos_ += take;
        if (sub_pos_ == sub_len_) {
            finish_subblock();
        }
    }
}

void LoudnessMeter::process_interleaved(const float *data, std::size_t frames) {
    const std::size_t nch = channels_;
    std::size_t f = 0;
    while (f < frames) {
        const std::size_t take = std::min(frames - f, sub_len_ - sub_pos_);
        for (std::size_t i = f; i < f + take; ++i) {
            for (std::size_t ch = 0; ch < nch; ++ch) {
                feed(ch, data[i * nch + ch]);
            }
        }
        f += take;
        sub_pos_ += take;
        if (sub_pos_ == sub_len_) {
            finish_subblock();
        }
    }
}

void LoudnessMeter::finish_subblock() {
    double e = 0.0;
    for (std::size_t ch = 0; ch < channels_; ++ch) {
        e += weights_[ch] * acc_[ch];
        acc_[ch] = 0.0;
    }
    ring_e_[ring_pos_] = e;
    ring_n_[ring_pos_] = sub_pos_;
    ring_pos_ = (ring_pos_ + 1) % kStSubblocks;
    sub_pos_ = 0;
    subblocks_seen_ += 1;

    // Window mean: sum of the k most recent sub-block energies / samples.
    const auto window = [&](std::size_t k) {
        double s = 0.0;
        for (std::size_t i = 0; i < k; ++i) {
            s += ring_e_[(ring_pos_ + kStSubblocks - 1 - i) % kStSubblocks];
        }
        return s / static_cast<double>(k * sub_len_);
    };
    const double em = window(kMSubblocks);
    const double es = window(kStSubblocks);
    momentary_ = energy_to_lufs(em);
    short_term_ = energy_to_lufs(es);
    if (subblocks_seen_ >= kMSubblocks) {
        integrated_hist_.add(em);
        max_momentary_ = std::max(max_momentary_, momentary_);
    }
    if (subblocks_seen_ >= kStSubblocks) {
        lra_hist_.add(es);
        max_short_term_ = std::max(max_short_term_, short_term_);
    }
}

double LoudnessMeter::integrated() const {
    std::size_t bin;
    double e;
    std::uint64_t n;
    if (!integrated_hist_.relative_gate_bin(-10.0, &bin)) return -HUGE_VAL;
    if (!integrated_hist_.mean_energy_from(bin, &e, &n)) return -HUGE_VAL;
    return energy_to_lufs(e);
}

double LoudnessMeter::relative_threshold() const {
    double e;
    std::uint64_t n;
    if (!integrated_hist_.mean_energy_from(0, &e, &n)) return -HUGE_VAL;
    return energy_to_lufs(e) - 10.0;
}

double LoudnessMeter::loudness_range() const {
    const Histogram &h = lra_hist_;
    std::size_t start;
    if (!h.relative_gate_bin(-20.0, &start)) return 0.0;
    std::uint64_t n = 0;
    for (std::size_t i = start; i < kHistBins; ++i) n += h.count[i];
    if (n == 0) return 0.0;
    // Nearest-rank percentiles on the sorted gated values (histogram order).
    const auto percentile = [&](double p) -> double {
        const std::uint64_t rank = std::min<std::uint64_t>(
            static_cast<std::uint64_t>(std::llround(p * static_cast<double>(n - 1))), n - 1);
        std::uint64_t c = 0;
        for (std::size_t i = start; i < kHistBins; ++i) {
            c += h.count[i];
            if (c > rank) {
                return Histogram::bin_centre(i);
            }
        }
        return Histogram::bin_centre(kHistBins - 1);
    };
    return std::max(percentile(0.95) - percentile(0.10), 0.0);
}

double LoudnessMeter::sample_peak() const {
    float m = 0.0f;
    for (std::size_t ch = 0; ch < channels_; ++ch) m = std::max(m, sample_peak_[ch]);
    return static_cast<double>(m);
}

double LoudnessMeter::sample_peak_channel(std::size_t ch) const {
    return (ch < channels_) ? static_cast<double>(sample_peak_[ch]) : 0.0;
}

double LoudnessMeter::true_peak() const {
    float m = 0.0f;
    for (std::size_t ch = 0; ch < channels_; ++ch) m = std::max(m, true_peak_[ch]);
    return static_cast<double>(m);
}

double LoudnessMeter::true_peak_channel(std::size_t ch) const {
    return (ch < channels_) ? static_cast<double>(true_peak_[ch]) : 0.0;
}

double LoudnessMeter::true_peak_dbtp() const {
    return 20.0 * std::log10(true_peak());
}

LoudnessSummary LoudnessMeter::summary() const {
    LoudnessSummary s;
    s.integrated_lufs = integrated();
    s.loudness_range_lu = loudness_range();
    s.max_momentary_lufs = max_momentary_;
    s.max_short_term_lufs = max_short_term_;
    s.sample_peak_dbfs = 20.0 * std::log10(sample_peak());
    s.true_peak_dbtp = true_peak_dbtp();
    return s;
}

double LoudnessMeter::normalize_gain_db(double measured_lufs, double target_lufs) {
    return (std::isfinite(measured_lufs) && std::isfinite(target_lufs))
        ? target_lufs - measured_lufs
        : 0.0;
}

double LoudnessMeter::normalize_gain_db_peak_limited(
    double measured_lufs, double target_lufs,
    double true_peak_dbtp, double max_true_peak_dbtp) {
    const double g = normalize_gain_db(measured_lufs, target_lufs);
    return std::isfinite(true_peak_dbtp) ? std::min(g, max_true_peak_dbtp - true_peak_dbtp) : g;
}

double LoudnessMeter::k_weighting_power(double freq, double sample_rate) {
    const double a = k_prefilter(sample_rate).magnitude(freq, sample_rate);
    const double b = k_rlb(sample_rate).magnitude(freq, sample_rate);
    const double ab = a * b;
    return ab * ab;
}

LoudnessSummary loudness_measure(const float *const *channels, std::size_t n_ch,
                                  std::size_t n, double sample_rate) {
    LoudnessMeter m(sample_rate, n_ch);
    m.process(channels, n_ch, n);
    return m.summary();
}

} // namespace rawedit
