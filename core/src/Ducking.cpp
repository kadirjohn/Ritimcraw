// Offline auto-ducking analysis — C++ port (E5-C core, night D1).
// See Ducking.h for provenance; every function mirrors its Rust
// original line by line (f64 accumulators, f32 levels, the crate's
// epsilon constants).

#include <rawedit/AudioFx.h>
#include <rawedit/Ducking.h>

#include <algorithm>
#include <cmath>

namespace rawedit {

std::vector<float> duck_envelope_db(const float *const *channels,
                                    std::size_t n_ch, std::size_t n,
                                    float sample_rate, float hop_s,
                                    float window_s) {
    if (n_ch == 0 || n == 0) {
        return {};
    }
    // The crate rounds in f32 before the usize cast: hop_s * sample_rate
    // and window_s * sample_rate / 2.0 are f32 products (left-to-right),
    // then f32::round, then `as usize` (saturating, NaN -> 0).
    const std::size_t hop = std::max<std::size_t>(
        rust_as_size(std::round(hop_s * sample_rate)), 1);
    const std::size_t half = std::max<std::size_t>(
        rust_as_size(std::round(window_s * sample_rate / 2.0f)), 1);
    // Prefix sums of the channel-averaged power.
    const double nch = static_cast<double>(n_ch);
    std::vector<double> pre(n + 1);
    pre[0] = 0.0;
    double acc = 0.0;
    for (std::size_t i = 0; i < n; ++i) {
        double p = 0.0;
        for (std::size_t c = 0; c < n_ch; ++c) {
            const double v = static_cast<double>(channels[c][i]);
            p += v * v;
        }
        p /= nch;
        acc += p;
        pre[i + 1] = acc;
    }
    const std::size_t count = (n + hop - 1) / hop; // Rust div_ceil
    std::vector<float> out(count);
    for (std::size_t k = 0; k < count; ++k) {
        const std::size_t c = k * hop;
        const std::size_t a = c > half ? c - half : 0; // saturating_sub
        const std::size_t b = std::min(c + half, n);
        const std::size_t w = b - a == 0 ? 1 : b - a; // (b - a).max(1)
        // (pre[b] - pre[a]).max(0.0) / (b - a).max(1) as f64.
        double diff = pre[b] - pre[a];
        if (diff < 0.0) diff = 0.0;
        const double ms = diff / static_cast<double>(w);
        if (ms <= 1e-12) {
            out[k] = kDuckingFloorDb;
        } else {
            // (10.0 * ms.log10()) as f32, then .max(FLOOR_DB).
            out[k] = std::fmax(static_cast<float>(10.0 * std::log10(ms)),
                               kDuckingFloorDb);
        }
    }
    return out;
}

std::vector<std::pair<double, double>> duck_activity(
    const std::vector<float> &env_db, float hop_s, float threshold_db,
    float min_on_s, float bridge_s) {
    // The f32 hop rounded to whole microseconds, so 0.01 means
    // exactly 0.01 s (f64 math on the widened f32, round half away
    // from zero like Rust's f64::round).
    const double hop =
        std::round(static_cast<double>(hop_s) * 1e6) / 1e6;
    std::vector<std::pair<double, double>> raw;
    bool open = false;
    std::size_t start = 0;
    for (std::size_t k = 0; k < env_db.size(); ++k) {
        const bool above = env_db[k] > threshold_db;
        if (above && !open) {
            start = k;
            open = true;
        } else if (!above && open) {
            raw.emplace_back(static_cast<double>(start) * hop,
                             static_cast<double>(k) * hop);
            open = false;
        }
    }
    if (open) {
        raw.emplace_back(static_cast<double>(start) * hop,
                         static_cast<double>(env_db.size()) * hop);
    }
    std::vector<std::pair<double, double>> merged;
    for (const auto &r : raw) {
        if (!merged.empty() &&
            r.first - merged.back().second <
                static_cast<double>(bridge_s) - 1e-9) {
            merged.back().second = r.second;
        } else {
            merged.push_back(r);
        }
    }
    std::vector<std::pair<double, double>> out;
    for (const auto &r : merged) {
        if (r.second - r.first >= static_cast<double>(min_on_s) - 1e-9) {
            out.push_back(r);
        }
    }
    return out;
}

std::vector<std::pair<double, double>> duck_keyframes(
    const std::vector<std::pair<double, double>> &regions,
    double clip_start_s, double clip_end_s,
    double base_db, double reduce_db, double fade_s) {
    const double fade = std::fmax(fade_s, 1e-3);
    const double ducked = base_db - std::fabs(reduce_db);
    std::vector<std::pair<double, double>> sorted;
    for (const auto &r : regions) {
        // Rust total_cmp on (f64, f64): ordered by first then second;
        // our inputs are finite times, so plain < suffices.
        if (r.second > r.first && r.second > clip_start_s &&
            r.first < clip_end_s) {
            sorted.push_back(r);
        }
    }
    // Rust's sort_by is STABLE; std::stable_sort mirrors it (equal
    // start times keep their input order).
    std::stable_sort(sorted.begin(), sorted.end(),
                     [](const std::pair<double, double> &x,
                        const std::pair<double, double> &y) {
                         return x.first < y.first;
                     });
    // Merge regions whose ramps touch or overlap.
    std::vector<std::pair<double, double>> groups;
    for (const auto &r : sorted) {
        if (!groups.empty() &&
            r.first - fade <= groups.back().second + fade) {
            groups.back().second = std::fmax(groups.back().second, r.second);
        } else {
            groups.push_back(r);
        }
    }
    std::vector<std::pair<double, double>> out;
    auto push = [&](double t, double v) {
        if (t < clip_start_s || t > clip_end_s) {
            return;
        }
        if (!out.empty() && t <= out.back().first + 1e-9) {
            out.back().second = v;
        } else {
            out.emplace_back(t, v);
        }
    };
    // Value of the down-ramp ending at `a` / up-ramp starting at `b`,
    // at time t.
    auto down = [&](double a, double t) {
        const double frac = (t - (a - fade)) / fade;
        return base_db + (ducked - base_db) * (frac < 0.0   ? 0.0
                                              : frac > 1.0 ? 1.0
                                                           : frac);
    };
    auto up = [&](double b, double t) {
        const double frac = (t - b) / fade;
        return ducked + (base_db - ducked) * (frac < 0.0   ? 0.0
                                             : frac > 1.0 ? 1.0
                                                          : frac);
    };
    for (const auto &ab : groups) {
        const double a = ab.first, b = ab.second;
        const double ramp_start = a - fade;
        if (ramp_start >= clip_start_s) {
            push(ramp_start, base_db);
        } else {
            push(clip_start_s, down(a, clip_start_s));
        }
        push(std::fmax(a, clip_start_s), ducked);
        if (b < clip_end_s) {
            push(b, ducked);
            if (b + fade <= clip_end_s) {
                push(b + fade, base_db);
            } else {
                push(clip_end_s, up(b, clip_end_s));
            }
        } else {
            push(clip_end_s, ducked);
        }
    }
    return out;
}

} // namespace rawedit
