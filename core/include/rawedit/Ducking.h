// Offline auto-ducking analysis (E5-C core, night D1).
// docs/E5_AUDIO_PORT_PLAN.md §3.5.
//
// Provenance: a line-by-line port of filmcraft's
// crates/audio-dsp/src/ducking.rs (repository ~/projects/filmcraft-
// android, workspace main c41b58467e, read 2026-11; "MIT OR
// Apache-2.0" per its Cargo.toml; clean-room per its README — SEE
// NOTICE, audio-dsp entry). Pure functions on plain buffers; times
// are seconds, levels dBFS / dB:
//   - envelope_db: channel-averaged RMS per hop (prefix sums, windows
//     clipped at the signal edges), silence reads FLOOR_DB;
//   - activity: threshold regions with gap bridging and blip
//     removal (the f32 hop is rounded to whole microseconds so
//     0.01 reads as exactly 0.01 s);
//   - sensitivity_threshold_db: Essential Sound's 0..10 -> -20 - 4s
//     dBFS map;
//   - duck_keyframes: volume keyframes that duck by reduce_db under
//     each region (ramp overlap merges groups; everything clamped to
//     the clip; cut ramps get a keyframe at the edge with the ramp's
//     value; times strictly increasing; fade >= 1 ms so a ramp never
//     collapses).
//
// Rust semantics notes kept by the port: f32::clamp is std::clamp
// (NaN passes through), div_ceil is integer (a + b - 1) / b, and the
// output types of the region/keyframe lists are std::vector of
// std::pair<double, double> — the crate's (f64, f64) tuples.
//
// Allocation note: these are OFFLINE analysis functions (the crate's
// own doc header says so); they allocate their outputs, unlike the
// realtime effects. No realtime path may call them.
//
// UNWIRED by design: nothing in the app calls this file; the F4
// keyframe wiring is a later package. Desktop tests only.

#pragma once

#include <cstddef>
#include <utility>
#include <vector>

namespace rawedit {

/// Level floor (dBFS) for silence (crate FLOOR_DB).
constexpr float kDuckingFloorDb = -120.0f;

/// RMS level (dBFS) per hop: value k is the RMS of the window of
/// window_s seconds centred on k * hop_s (clipped at the signal
/// edges), power averaged over channels. The result has
/// (len + hop - 1) / hop entries; silence reads kDuckingFloorDb.
/// channels: n_ch planar buffers of n samples.
std::vector<float> duck_envelope_db(const float *const *channels,
                                    std::size_t n_ch, std::size_t n,
                                    float sample_rate, float hop_s,
                                    float window_s);

/// Regions (start_s, end_s) where the envelope exceeds threshold_db.
/// Gaps shorter than bridge_s are merged first, then regions shorter
/// than min_on_s are dropped. A frame k covers [k*hop, (k+1)*hop).
std::vector<std::pair<double, double>> duck_activity(
    const std::vector<float> &env_db, float hop_s, float threshold_db,
    float min_on_s, float bridge_s);

/// Essential Sound ducking "Sensitivity" (0..10) -> detection
/// threshold in dBFS: -20 - 4*s (5 -> -40 dBFS; higher sensitivity
/// reacts to quieter triggers). Ports sensitivity_threshold_db.
inline float duck_sensitivity_threshold_db(float sensitivity) {
    const float s = sensitivity < 0.0f   ? 0.0f
                    : sensitivity > 10.0f ? 10.0f
                                         : sensitivity; // f32::clamp
    return -20.0f - 4.0f * s;
}

/// Volume keyframes (time_s, level_db) that duck by reduce_db under
/// each region: ramp from base_db at a - fade down to base_db -
/// |reduce_db| at a, hold until b, ramp back up to base_db at
/// b + fade. Regions whose ramps would overlap are merged (the level
/// stays ducked between them). Everything is clamped to
/// [clip_start_s, clip_end_s]; a ramp cut by a clip edge gets a
/// keyframe at the edge with the ramp's value there. Times are
/// strictly increasing; no regions -> no keyframes. fade_s is at
/// least 1 ms so a ramp never collapses into two keyframes at the
/// same time.
std::vector<std::pair<double, double>> duck_keyframes(
    const std::vector<std::pair<double, double>> &regions,
    double clip_start_s, double clip_end_s,
    double base_db, double reduce_db, double fade_s);

} // namespace rawedit
