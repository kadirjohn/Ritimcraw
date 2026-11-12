// rawedit::Temporal — temporal noise reduction on RAW CFA frames
// (spec DN-05..DN-09, "Ilk algoritma": center frame + up to two neighbors
// from a small time window, high-confidence neighbors only).
//
// WHERE IT SITS: before developCpu, on RAW CFA samples. The stage-1..6
// prefix of the develop pipeline (per-slot black/white normalization, lens
// shading gain, highlight reconstruction, bilinear demosaic, WB, camera ->
// Rec.709) is affine or linear in the samples, so a convex blend of CFA
// samples commutes with it: blending raw then developing is mathematically
// identical to developing then blending, and the merged frame flows through
// the unchanged linear pipeline (the spec's "processed frame stays in the
// linear path"). Working in the CFA domain also means each sensor slot
// blends only with itself (the warp offset is always even), so chroma can
// never bleed across slots — the raw-domain equivalent of evaluating luma
// and chroma differences separately. No spatial NR stage exists in this
// pipeline yet; when one lands it runs after developCpu, i.e. after this
// stage, which is the required order (temporal before spatial).
//
// CONSERVATIVE BY CONSTRUCTION (owner's north star: prefer noise over
// ghosts): the center sample always keeps weight 1.0; a neighbor sample
// joins only when every gate below passes, and any failed gate zeroes that
// neighbor's weight at that pixel. When both neighbors are rejected the
// output is the center CFA bit for bit (the merge skips the write).
//
// Gates, two tiers (denoise research 2026-11-11 + the source-comparison
// study of FFmpeg/OpenCV/ginzburg, same day):
//   GLOBAL, per neighbor, before any matching:
//     - PTS eligibility (temporalNeighborEligible): the neighbor must be an
//       adjacent-in-time frame of the same clip — 0 < |dPTS| <= 1.5x the
//       local frame duration. Duplicates (dPTS = 0) and large gaps (dropped
//       frames, a cut inside the container) are rejected; reverse order is
//       fine (|dPTS|).
//     - Mean luma shift of the 1/8 proxies > 4% of range -> reject (sudden
//       exposure jump; not a scene cut, but still unsafe to merge).
//     - MAFD scene score (source-comparison item #8, FFmpeg scdet
//       get_scene_score): mean |center - neighbor| x100 on the normalized
//       proxies. Reject when it exceeds 10 (an absolute cut), OR when it
//       JUMPS from the previous pair-in-time's score by more than 10
//       (the trend form; the caller supplies the previous score via
//       TemporalSettings — negative = unknown, delta inactive). Cuts
//       with near-equal histograms and means are what this catches; it
//       also exits BEFORE the ~11 ms match cost.
//     - Total-variation distance of 32-bin luma histograms > 0.30 -> reject
//       (scene cut).
//     - Fraction of blocks with an acceptable match < 25% -> reject
//       (motion storm / cut / occlusion everywhere).
//   LOCAL, per 16x16-px block of the 1/8 luma proxy (128x128 CFA px),
//   decided STATIC-FIRST:
//     - Zero-motion residual first: when the block already agrees to
//       within the noise model (<= 3x sigma of the difference, full
//       confidence at <= 1.5x), it merges AT ZERO MOTION. Matching cannot
//       improve on zero motion for static/untextured content (flat wall,
//       sky, locked shot) — its vectors there are noise-driven — so this
//       path both fixes the flat-content case and subsumes the old F/B
//       "rescue". Sigma comes from the per-frame noiseProfile metadata
//       (Camera2 SENSOR_NOISE_PROFILE: variance = S*signal + O, per CFA
//       slot, normalized signal domain) — measured on the 2026-11-11
//       fixture to match the empirical frame-diff sigma within ~15% — or a
//       conservative low-end-of-band fallback sigma (0.6% of range) when
//       it does not.
//     - MEASURED NOISE FLOOR (ginzburg _noise_floor, adapted to MAD): the
//       25th percentile of the winner MAD field of this frame pair. Every
//       block's used residual must also stay <= 3x this floor, so a
//       metadata profile claiming far more noise than the frames contain
//       can no longer admit changed content through the sigma taper — the
//       floor is measured from what matching actually achieves on THIS
//       pair, self-calibrating per render.
//     - Motion path (zero motion does not fit): forward/backward
//       consistency (UnFlow criterion, 1.0 proxy px per axis) + the floor
//       gate + COMBINED confidence: the sigma taper on the WARPED residual
//       (full at <= 1.5 sigma, zero at 3 sigma — the absolute noise bound)
//       multiplied by the GAIN RATIO (ginzburg _warp_confidence):
//       (mad0 - mad_w) / max(mad0, floor), clamped to [0,1] — how much of
//       the zero-motion error the warp actually removed, dimensionless.
//       The gain ratio alone is NOT a safety gate: on decorrelated content
//       the min over 81 offsets of a noisy cost field always sits below
//       the zero-motion cost, so a "gain" there is selection bias, not
//       alignment (proven by the beyond-radius shift test). The sigma
//       taper bounds the warped residual itself; the gain ratio then
//       scales how much of the improvement is trusted. Confidence
//       < 0.1 (ginzburg threshold) rejects.
//     - Per-pixel guard: |N_warped - C| <= 6 sigma_pixel zeroes the
//       neighbor weight at that pixel only (occlusion boundaries inside an
//       otherwise passing block).
//
// MERGE (research formula): O = (C + wP*P + wn*N) / (1 + wP + wN) per CFA
// sample, computed in float and rounded to nearest. Each neighbor's weight
// is capped at 0.40 scaled by strength/100 (the researched 0.35-0.5 band;
// 0.40 measured against the variance-reduction test) and DECAYED BY
// TEMPORAL DISTANCE (atadenoise get_weights): cap * min(1,
// frameDuration/|dPTS|) — a neighbor 1.5 durations out carries 2/3. With
// both neighbors at full weight the residual variance is ~0.41x
// center-only (~1.6 dB); with one, ~0.59x. Block weights are spread with
// OVERLAP FEATHERING (FFmpeg obmc_tab / ginzburg cosine): linear ramps
// over the outer quarter of each block edge, 50/50 at a boundary — the
// conservative variant renormalizes over ACCEPTED blocks only, so a
// rejected block's side of a boundary keeps the center pixel instead of
// being dragged by a neighbor's vector. No Wiener/NLM in this version.
//
// MOTION ESTIMATION: 1/8-scale luma proxies (a proxy pixel is the mean
// normalized signal of the 4 stride-2 samples of its 8x8 CFA block — one
// sample per 2x2 cell slot, so the average is CFA-pattern independent).
// Integer block matching in
// two passes, search radius +-4 proxy px (= +-32 CFA px, handheld
// vibration between adjacent frames, not general optical flow), SAD on a
// 2x2-subsampled lattice during the search, full sampling for winners.
// Winner MADs are MEAN-NORMALIZED GLOBALLY (source-comparison item #9):
// the pair's proxy mean difference — what the mean-luma gate already
// computes — is subtracted before the absolute value, so auto-exposure
// micro-flicker no longer inflates the floor or the static-path residual.
// Deliberately global, not per-block: a per-block mean subtraction erases
// the residual of a local content change (measured: MAD 0.300 -> 0.0035,
// i.e. a ghost); uniform steps stay bounded by the 4% mean-luma gate, and
// the search cost stays plain (measured argmin shift under flicker is
// noise level):
//   pass 1: plain argmin (matchProxyBlocks, exposed for tests);
//   pass 2 (internal): re-decide with the measured floor known —
//     - ZERO-BIAS TIE-BREAK (ginzburg _argmin_zero_biased): candidates
//       within 0.5x floor of the best cost are ties; ties go to the
//       SHORTEST vector, so flat/noisy regions cannot invent motion;
//     - MV REGULARIZATION (FFmpeg minterpolate get_sbad): cost = SAD +
//       0.5x floor per proxy px of |mv - pred|, pred = median of the
//       already-decided left/top/top-right vectors (EPZS-style spatial
//       predictor — no cross-frame state, so the merge stays stateless
//       and scrub-safe).
// No subpixel refinement in this version; the warp uses the integer proxy
// vector scaled by 8 (always even -> slot preserving).
//
// WHAT THE CALLER OWNS (the JNI bridge):
//   - loading neighbor CFAs (Source::readFrame) ONLY when temporal NR is
//     on (zero cost when off — the bridge never calls into this file);
//   - a small ring cache of decoded neighbor CFAs + their proxies keyed by
//     frame index, so sequential playback amortizes to ~1 decode/frame
//     (DN-09: a scrub miss reloads; the cache never blends a previous
//     scene because eligibility is re-evaluated per render);
//   - passing only PTS-eligible neighbors (the ring plus
//     temporalNeighborEligible);
//   - never feeding a temporal OUTPUT back in as an INPUT: this merge
//     writes only to `out`; the center and neighbor buffers stay pristine
//     and are what the caller caches.

#pragma once

#include <cstdint>
#include <vector>

#include <rawedit/Source.h>

namespace rawedit {

// Fallback sigma (normalized signal domain) when the clip has no noise
// profile: 0.6% of range — the LOW end of the measured band (sensor noise
// at ISO ~2800 measured 0.6-1.4% of range on the 2026-11-11 fixture).
// Deliberately conservative, not middle-of-band: a fallback sigma that is
// too generous lets the static path's 3-sigma gate read decorrelated
// texture as "within the noise model" (the beyond-radius shift test caught
// exactly that — garbage MADs at 2.7x a 1% fallback passed as static); a
// fallback that is too tight only keeps noise, which is the preferred
// failure (noise over ghosts). A clip without a profile loses merge
// strength on genuinely static content at high ISO — the cost of the
// conservative choice, accepted.
inline float temporalFallbackSigma() { return 0.006f; }

// PTS-window eligibility (research item: reject duplicate/large-gap
// neighbors; reverse order allowed). dPts = neighbor.ptsUs - center.ptsUs;
// frameDurationUs = local median frame duration of the clip. A duplicate
// (0), a same-timestamp gap or anything beyond 1.5x the duration is not an
// adjacent frame.
bool temporalNeighborEligible(int64_t dPts, int64_t frameDurationUs);

// 1/8-scale luma proxy: w = width/8, h = height/8, each proxy pixel the
// mean normalized signal of the 4 stride-2 samples of its 8x8 CFA block
// (one sample per 2x2 cell slot). Pattern independent (equal sample count
// per slot). Empty when the frame is too small (w or h < 32).
struct LumaProxy {
    uint32_t w = 0;
    uint32_t h = 0;
    std::vector<float> v; // w*h, [0,1]-ish normalized luma
};

LumaProxy buildLumaProxy(const uint16_t* cfa, const FrameInfo& info,
                         const RawMeta& meta);

// One integer motion vector in PROXY pixels, plus the winner's mean
// absolute difference (full-sampling SAD / block area). Used by tests.
struct MotionVector {
    int dx = 0;
    int dy = 0;
    float mad = 0.0f;
};

// Block matching on the proxy grid: 16x16-px blocks, non-overlapping
// (stride 16), search radius +-4, SAD on a 2x2-subsampled lattice during
// the search and full sampling for the winner. Fills out[outW*outH]
// (outW = proxyW/16, outH = proxyH/16) row-major. meanOffset (default 0)
// is subtracted from every sample difference before the absolute value in
// the winner MAD (source-comparison item #9, mean-normalized SAD): pass
// mean(center) - mean(neighbor) of the proxies to remove an auto-exposure
// level step from the residuals. The SEARCH cost stays plain SAD —
// measured argmin shift under flicker is noise level. Exposed for tests.
void matchProxyBlocks(const LumaProxy& center, const LumaProxy& neighbor,
                      std::vector<MotionVector>& out, uint32_t& outW,
                      uint32_t& outH, float meanOffset = 0.0f);

// Settings + inputs for one merge. strength is the user slider 0..100; the
// effective per-neighbor weight cap is 0.40 * strength/100, decayed by
// temporal distance (cap * min(1, frameDurationUs/|dPts|)). Noise profiles
// are per frame (research: do not assume one profile across gain modes);
// any pointer may be null, which selects the fallback sigma. The dPts
// fields are 0 = "adjacent / unspecified" (no decay); the bridge passes
// the real PTS deltas after temporalNeighborEligible has filtered them.
// The pair-MAFD fields (source-comparison item #8, FFmpeg scdet
// convention) carry the PREVIOUS pair-in-time's scene score for the
// trend gate: negative = unknown (scrub start, first pair) and leaves
// only the absolute MAFD gate active. The bridge owns the per-pair
// cache keyed by frame index (the merge itself stays stateless).
struct TemporalSettings {
    float strength = 0.0f; // 0..100; <= 0 makes the merge a passthrough
    const NoiseProfile* centerNoise = nullptr;
    const NoiseProfile* prevNoise = nullptr;
    const NoiseProfile* nextNoise = nullptr;
    int64_t prevDPtsUs = 0;
    int64_t nextDPtsUs = 0;
    int64_t frameDurationUs = 0;
    float prevPairMafd = -1.0f; // < 0 = unknown: absolute MAFD gate only
    float nextPairMafd = -1.0f; // < 0 = unknown: absolute MAFD gate only
};

// Per-neighbor outcome, for tests and the perf report.
struct NeighborStats {
    bool present = false;    // a buffer was passed
    bool eligible = false;   // passed the global gates
    float acceptRate = 0.0f; // fraction of blocks that passed local gates
    float meanWeight = 0.0f; // mean per-pixel weight actually applied
    float noiseFloor = 0.0f; // measured 25th-pct winner MAD (diagnostic)
    float mafd = 0.0f;       // this pair's scene score x100 (diagnostic)
};

// Measured per-stage costs (research item: report proxy / matching / merge
// separately) plus the two neighbor stats.
struct TemporalReport {
    NeighborStats prev, next;
    double proxyMs = 0.0;
    double matchMs = 0.0;
    double mergeMs = 0.0;
};

// Merges the temporal window into `out`. center/prev/next are full-res CFA
// buffers (frameCount x w*h uint16, row-major); prev/next may be null
// (first/last frame, or an ineligible neighbor the caller already dropped —
// DN-09 window clamp). out must NOT alias any input: the caller keeps the
// pristine inputs for its ring cache (temporal output is never an input).
// When strength <= 0, or every neighbor is rejected, out is a byte-exact
// copy of center — the disabled path is bit-identical by construction.
// report may be null. All three frames must share dimensions and CFA
// pattern (same clip); a mismatched neighbor is rejected, not blended.
void temporalMergeCfa(const uint16_t* center, const FrameInfo& info,
                      const uint16_t* prev, const uint16_t* next,
                      const RawMeta& meta, const TemporalSettings& settings,
                      uint16_t* out, TemporalReport* report);

} // namespace rawedit
