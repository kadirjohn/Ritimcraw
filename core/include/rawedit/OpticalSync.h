// rawedit::OpticalSync — GY-04: optically refined frame↔IMU sync.
//
// WHY THIS EXISTS (the owner's complaint + the audit, 2026-11-13): the
// shipped sync anchor (Sync.h estimateOffsetFromAnchors) is a receive-
// wall-clock estimate with an ASSUMED ±25 ms bound. The sensor→receive
// lag is unobservable from the files and enters the offset at full
// weight. A CONSTANT sync error rotates the extracted orientation
// toward the wrong gyro samples — the stabilization correction then
// fights the motion instead of canceling it (the owner measured:
// "titreşim neredeyse azalmıyor"). The audit's order (section D):
// "Implement optically refined sync using decoded low-resolution
// original frames and robust background motion", STARTING from the
// existing anchor — never re-anchoring, never "fixing" it by a blind
// constant.
//
// THE METHOD (audit section D, verbatim intent): search the offset
// around the anchor by minimizing the reprojection residual between
// the IMAGE motion (measured on decoded low-res frames) and the
// GYRO-PREDICTED motion (the same frameTransform homography the
// production warp uses — the residual is in the production's own
// terms, not a correlation of unrelated scalar magnitudes):
//
//   per candidate offset, per consecutive frame pair (i, j=i+1):
//     t_i = repTimeNs_i + offset        (see REPRESENTATIVE TIME below)
//     q_i = series.orientationAt(t_i)   (SLERP, clamped)
//     q_j = series.orientationAt(t_j)
//     q_rel = conj(q_j) * q_i           (relativeRotation: source frame i
//                                        -> target frame j — the SAME
//                                        convention the production warp
//                                        uses for actual->target)
//     H = frameTransform(q_rel, f, w, h)
//     Δp_pred = H(center) - center       (the rotation's image displacement
//                                        at the principal point)
//     Δp_meas = the translation aligning frame i to frame j (measured
//                                        once per pair — it does not
//                                        depend on the offset)
//     r = |Δp_meas - Δp_pred|
//   cost(offset) = Σ_pairs Huber_δ(r), δ = 1 px
//
// The RAW orientation series is the measurement basis on purpose: the
// image motion between two ORIGINAL frames IS the camera's actual
// rotation — that is the physical quantity the sync error corrupts.
// (The SMOOTHED series defines the stabilization's target, not the
// sync measurement; mixing the two would make the residual depend on
// the strength setting.)
//
// SEARCH: ±150 ms around the anchor at 1 ms (301 candidates), then the
// best candidate refined at 0.1 ms over ±2 ms (41 more). The anchor's
// ±25 ms bound is an engineering judgment — the receive lag could
// exceed it — hence the wider window. The search NEVER re-anchors: it
// starts at anchor.model.offsetNs and can only move within the range.
//
// REPRESENTATIVE TIME (the audit's exposure/readout order): a frame's
// content is the scene over its exposure, centered at
//   capture + 0.5*exposure + 0.5*readout   (telemetry-parser's rule)
// The MCRAW container carries NO exposure/readout keys (grep of
// Source.cpp/jni_bridge.cpp, 2026-11-14) — the audit forbids silently
// assuming missing data is zero, so the caller forms the DOCUMENTED
// fallback
//   repTimeNs = captureNs + 0.5*frameInterval
// and this fallback is VISIBLE in the provenance string
// (opts.repTimeSource, carried verbatim).
//
// *** MAPPING CONTRACT (binding) ***: refinedOffsetNs is defined
// AGAINST THE REPRESENTATIVE TIME:
//     t_imu = repTimeNs + refinedOffsetNs
// NOT against the raw capture stamp the anchored model uses
// (t_imu = captureNs + offsetNs). The two mappings differ by the
// half-interval lag, so a caller that swaps refinedOffsetNs into the
// capture-stamp mapping (or vice versa) injects exactly that lag as
// sync error. The estimate and the consumer MUST use the same
// repTimeNs formation. (With a constant frame interval the difference
// is a constant; the refinement absorbs it — a refined offset found
// under the fallback repTime reproduces the true gyro time when the
// production applies the SAME fallback. Pinned by the
// repTime-fallback consistency test.)
//
// IMAGE MOTION ESTIMATE (choice, documented as ordered): a 2-pass
// coarse-to-fine TRANSLATION SEARCH on the low-passed luma —
//   pass 1: ±16 integer steps on a 4x box-downsampled luma
//           (±64 thumbnail px of headroom for fast motion),
//   pass 2: ±2 px at 0.5 px steps (bilinear) on a 3x3 box-blurred
//           full luma around pass 1's best,
// metric SAD over a fixed central region (64 px border excluded: the
// production warp's edge clamp smears content inward, and a compared
// region that includes smeared borders biases the measured
// translation). Chosen over phase correlation because it is exactly
// testable against synthetic ground truth and needs no FFT
// dependency; the brief explicitly allows it.
//
// ROBUSTNESS: (1) weak-texture rejection — a pair is accepted only if
// the blurred-luma variance clears a floor AND the best shift's SAD
// improves on the median-shift SAD by opts.minMatchScore (a real
// match, not noise on a flat patch); (2) a MAD outlier gate — at the
// coarse best, per-pair residual magnitudes m_i, reject m_i >
// median + 3*1.4826*MAD (moving objects, warp smear), CAPPED at the
// top quartile of pairs (the gate may never reject more than 25% —
// it must not be able to reject the evidence that exposes a wrong
// offset). The fine pass runs on the surviving pairs only.
//
// CONFIDENCE (never fabricate): the verdict is Refined only when
//   - >= 3 pairs survived the gate,
//   - the cost curve has positive curvature at the fine minimum
//     (a real well, not a monotone wash),
//   - peakiness = 1 - Cmin/Cmedian >= opts.minPeakiness (Cmedian over
//     the coarse curve of the SURVIVING pairs — a sharp well must
//     save a meaningful fraction of the washed cost),
//   - the curvature-derived error bound IMPROVES on the anchor's
//     bound (when the anchor carried one): a "refinement" no tighter
//     than the anchor is not a refinement — keep the anchor.
// Otherwise the result says NOT refined and the caller keeps the
// anchor untouched. The reported errorBoundMs is the offset
// half-width at which the parabolic-fit cost exceeds the minimum by
// one Huber-delta^2 (δ*sqrt(2/curvature)), floored at the fine step
// and capped at the anchor bound — a sensitivity statement on the
// MEASURED curve, not a constant.
//
// WINDOWS: 3 windows (early/middle/late thirds of the frames passed
// in), each capped at opts.maxPairsPerWindow consecutive pairs
// (centered in the window). Each window is scored for informativeness
// (gyro angular-velocity variance over the window x the pair
// acceptance rate — the audit's "reject low-motion/uninformative or
// moving-object-dominated intervals"). Every window is refined
// independently; the overall offset is the median of the largest
// group of refined windows agreeing within
// opts.agreementToleranceMs when >= 2 agree, else the best-scoring
// refined window. Disagreement is reported, never averaged away.
// Per-window offsets appear in the provenance string.
//
// CALLER BUDGET: the caller decodes/develops (developThumbnail,
// stride 8 on 4K -> 480x270 keeps the whole refinement in the ~seconds
// class on the host; the fixture test quotes the measured cost). The
// refinement itself needs only enough frames to give each third
// ~opts.maxPairsPerWindow pairs — pass a bounded, time-ordered
// subset; this function splits WHAT IT IS GIVEN into thirds.
//
// Sync.cpp stays I/O-free and untouched by the image math: this unit
// consumes already-decoded ImageF frames, and the fold into a
// SyncEstimate (applyOpticalRefinement) lives here too.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <rawedit/Develop.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Sync.h>

namespace rawedit {

// One decoded, developed frame for the refinement. The caller owns the
// decode + develop (any stride); the core never touches files.
struct OpticalSyncFrame {
    int64_t captureNs = 0; // exact container capture timestamp (ns)
    // Representative capture instant (ns) — the MAPPING CONTRACT above:
    //   capture + 0.5*exposure + 0.5*readout when the container carries
    //   them, else the documented capture + 0.5*frameInterval fallback.
    // 0 = no representative-time correction (use captureNs itself).
    int64_t repTimeNs = 0;
    ImageF image; // developed low-res frame (linear working space)
};

// Intrinsics at the THUMBNAIL's dimensions (the frames above and the
// orientation prediction must share one pixel grid):
// focalLengthPxFromEquivalent(meta.focalLengthMm, imageWidth) — the
// flagged 35mm-equivalence assumption, same as the production plan.
struct OpticalSyncIntrinsics {
    double focalLengthPx = 0.0; // <= 0 -> the refinement refuses (honesty,
                                // mirroring Stabilization.h's NoImu class)
};

struct OpticalSyncOpts {
    double searchRangeMs = 150.0; // +- around the anchor (coarse grid)
    double coarseStepMs = 1.0;    // 301 candidates at the default range
    double fineStepMs = 0.1;      // fine grid step
    double fineRangeMs = 2.0;     // +- around the coarse best (41 more)
    double huberDeltaPx = 1.0;    // Huber knee (px) on the residual
    int maxPairsPerWindow = 24;   // consecutive-pair cap per window
    double minLumaVariance = 5e-5; // blurred-luma variance floor (linear^2)
    double minMatchScore = 0.05;  // (sadMedian - sadBest)/sadMedian floor
    double madSigmaScale = 3.0;   // MAD gate: median + scale*1.4826*MAD
    double minPeakiness = 0.25;   // 1 - Cmin/Cmedian confidence floor
    double agreementToleranceMs = 10.0; // cross-window agreement band
    // Carried VERBATIM into the provenance string — the caller states
    // how it formed repTimeNs (the audit's visibility requirement).
    const char* repTimeSource = "capture+0.5*interval (fallback; container "
                                "carries no exposure/readout keys)";
};

// Per-window outcome (diagnostics + provenance fragments).
struct OpticalSyncWindowResult {
    bool refined = false;
    int64_t offsetNs = 0;     // valid only when refined
    size_t pairsTotal = 0;    // consecutive pairs in the (capped) window
    size_t pairsAccepted = 0; // passed texture + match acceptance
    size_t pairsUsed = 0;    // survived the MAD gate (the estimate's pairs)
    double score = 0.0;      // window informativeness (ranking only)
    double peakiness = 0.0;  // 1 - Cmin/Cmedian (0 when not refined)
    double errorBoundMs = 0.0; // curvature band; valid only when refined
    std::string note;        // compact per-window provenance fragment
};

struct OpticalSyncResult {
    bool refined = false;
    int64_t refinedOffsetNs = 0; // valid only when refined (MAPPING
                                 // CONTRACT: t_imu = repTimeNs + this)
    double errorBoundMs = 0.0;   // valid only when refined
    std::string provenance;      // "refined:optical windows=k accepted=j
                                 // offsetMs=... repTime=..." on success;
                                 // "refine-rejected ... reason=..." on
                                 // failure (per-window reasons listed)
    std::vector<OpticalSyncWindowResult> windows; // one per third
};

// Refines the anchor optically. `anchor` supplies the STARTING offset
// (anchor.model.offsetNs) and the bound the result must improve on;
// the anchor itself is never modified and never re-estimated. Frames
// must be time-ordered (strictly increasing captureNs) and share one
// image size; the series is the RAW orientation series (VQF output).
// Invalid inputs (empty frames/series, non-positive focal, tiny or
// mismatched images, unsorted timestamps) return a not-refined result
// with the reason in the provenance — never a crash, never a guess.
OpticalSyncResult refineOffsetOptically(const SyncEstimate& anchor,
                                       const std::vector<OpticalSyncFrame>& frames,
                                       const OrientationSeries& orientationSeries,
                                       const OpticalSyncIntrinsics& intrinsics,
                                       const OpticalSyncOpts& opts = {});

// Folds a refinement into a SyncEstimate (the additive-field path).
// On success: confidence = Refined, refinedOffsetNs set, errorBoundMs
// from the measured curve, source = the provenance string. On failure:
// the anchor estimate returned UNCHANGED except the rejection note
// appended to source — the caller keeps the anchor, nothing fabricated.
SyncEstimate applyOpticalRefinement(const SyncEstimate& anchor,
                                    const OpticalSyncResult& result);

} // namespace rawedit
