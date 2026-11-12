// rawedit::Stabilization — smoothed target orientation, relative
// transform, pinhole warp homography and static crop (stabilization
// Phase 4; the CPU math only — no GPU warp, no UI).
//
// WHAT THIS PHASE OWNS:
//   1. smoothOrientationSeries — the "Smooth" target: a time-weighted
//      low-pass on the camera-to-world quaternion series, dialable
//      with a 0..1 strength (deliverable 1, modes Smooth/HorizonLock).
//   2. relativeRotation — per frame q_rel = conj(q_target) * q_actual
//      (deliverable 2) with the direction contract below. Lock First
//      Frame is the degenerate case q_target = the first frame's
//      actual orientation. horizonLockRotation supplies the Horizon
//      Lock mode's leveling rotation from Phase 2's gravityCamera.
//   3. frameTransform — the rotation-only pinhole homography
//      H = K·R̃(q_rel)·K⁻¹, R̃ = D·R(q_rel)·D the view-axis-flip
//      conjugate (deliverable 3; the derivation and the flip are
//      documented at frameTransform — the naive K·R·K⁻¹ without the
//      flip inverts pitch/yaw).
//   4. staticCropZoom — the minimal uniform zoom that keeps every
//      warped frame inside the source (deliverable 4).
//   5. computeStabilization — the clip-level API: Phase 2 orientation
//      + Phase 3 sync + mode/strength/horizon/zoom params -> per-frame
//      {H, zoom, status} (deliverable 5).
//
// REFERENCE STUDY, NO CODE COPIED: gyroflow's DefaultAlgo
// (src/core/smoothing/default_algo.rs @69c46052, GPL-3.0 — semantics
// reference only, nothing copied) smooths with a velocity-adaptive
// exponential time constant: tau = 1.0 s at zero angular velocity
// shrinking to 0.1 s at 500 deg/s (its default smoothness 0.5 halves
// the ceiling), applied as a FORWARD + REVERSE pair of slerp_toward
// passes (the two-pass trick zeroes the group delay a single
// exponential pass leaves), then a second adaptive pass over the
// residual. Its horizon lock (smoothing/horizon.rs) rebuilds each
// quaternion from yaw/pitch with the roll term replaced by a user
// offset — i.e. roll-only leveling. What ships here is the same
// family, reduced to what a first stabilization needs:
//   - one-parameter exponential smoothing on the quaternion SLERP
//     path with a TIME CONSTANT, not a per-sample alpha — the fixture
//     gyro grid is 473 Hz but the API must hold for any grid, so
//     alpha is computed per interval: alpha = 1 - exp(-dt/tau);
//   - forward + reverse pass instead of the reference's second
//     adaptive pass (two passes already zero the lag; the residual
//     pass buys margin only at slider extremes);
//   - velocity adaptation deferred (the strength slider maps directly
//     to tau; see smoothOrientationSeries). The reference gates tau
//     on smoothed angular velocity so fast pans track while jitter
//     smooths — a follow-up can add the same gate without changing
//     this API if test footage demands it;
//   - horizon lock as an explicit roll-only rotation about the view
//     axis (see horizonLockRotation), matching the reference's
//     roll-only semantics without rebuilding the quaternion.
//
// QUATERNION CONVENTIONS (inherited, binding — OrientationEstimate.h):
//   [w,x,y,z] storage, Hamilton product, q1*q2 applies q2 FIRST;
//   q rotates camera→world (v_world = q v_cam q*); conj(q) is the
//   inverse for unit quaternions. The world frame of a VQF series is
//   gravity-aligned (earth +Z opposes gravity); the integration
//   baseline's world is the first camera frame. Smoothing and the
//   relative transform are world-frame-agnostic (they only compose
//   rotations); HORIZON LOCK is not — it consumes the gravity
//   direction, and only makes sense on a gravity-aware series.
//
// RELATIVE TRANSFORM — THE DIRECTION CONTRACT (deliverable 2's
// documentation duty; Phase 5's GPU warp applies it as written):
//
//   q_rel = conj(q_target) * q_actual
//
//   q_actual is the camera's real orientation at the frame; q_target
//   is the stabilized ("virtual camera") orientation the mode chose.
//   q_rel maps vectors from the ACTUAL camera frame into the TARGET
//   frame: v_target = q_rel v_actual q_rel*. Concretely: the frame was
//   exposed with the camera at q_actual; compositing the stabilized
//   image means re-expressing each recorded pixel ray in the target
//   camera's frame. H = K·D·R(q_rel)·D·K⁻¹ below is that re-expression in
//   pixel coordinates (SOURCE pixel -> OUTPUT pixel). With
//   q_target == q_actual the warp is the identity. If the actual
//   camera is rolled +5 deg CCW (viewed from behind the camera)
//   relative to the target, q_rel is a +5 deg rotation about the view
//   axis IN THE CAMERA FRAME — canonical Z (the camera looks along
//   -Z) — and H comes out a pure 2D rotation about the principal
//   point (the doctest pins the sign).
//
// HORIZON LOCK: when enabled (mode HorizonLock or the flag on Smooth),
// the TARGET orientation is post-rotated so gravity projects straight
// down in the image: the horizon sits level regardless of camera
// roll. "Horizon lock" means ROLL-ONLY leveling in every stabilizer
// that ships it (gyroflow's horizon.rs replaces the roll term only);
// leveling pitch would need a perspective shear, not a rotation
// composition, and is deliberately not attempted. The lock rotation
// is computed from Phase 2's gravityCamera g (unit gravity direction
// in the CAMERA frame at the last gyro sample, camera-to-world
// series; (0,0,0) when there is no accel stream):
//
//   roll_angle = atan2(g.x, -g.y)
//   q_lock     = rotation about camera +Z by +roll_angle
//   q_target'  = q_target * q_lock          (q_lock applied FIRST)
//
//   Derivation: g is the gravity direction expressed in the frame
//   being LOCKED — the TARGET camera frame (computeStabilization
//   evaluates it per frame; see below). With the camera held level,
//   gravity in its own frame is (0,-1,0) (down = -Y); a camera rolled
//   by +r about the view axis (top tipping right, the photographer's
//   sense — a body-frame post-rotation by -r about +Z) sees gravity
//   at (sin r, -cos r, g.z). So r = atan2(g.x, -g.y) is the roll, and
//   the leveling body rotation is the one that undoes it: +r about +Z.
//   Ground truth (doctest-pinned): after q_target' = q_target *
//   q_lock, gravity in the locked frame is the q_lock^-1 rotation of
//   g — exactly (0, -|g.xy|, g.z): level. The residual g.z (gravity
//   out the view axis = pitch) is untouched — leveling pitch is not a
//   rotation composition.
//
//   Per-frame evaluation in computeStabilization: the input
//   gravityCamera is the CAMERA-frame gravity at the series' LAST
//   gyro sample (it pairs with the last quaternion of that same
//   series). World gravity is constant in the world frame, so it is
//   reconstructed once: g_world = R(q_last) * gravityCamera, then
//   evaluated per frame in the TARGET frame: g_target = R(conj
//   (q_target)) * g_world. The lock folds into the relative rotation
//   without touching the warp contract:
//       q_rel' = conj(q_lock) * q_rel    (== conj(q_target') * q_actual)
//
//   Degenerate case: |g.xy| < 0.1 -> identity + false. This catches
//   both the zero vector (no accel — gravityCamera is (0,0,0), which
//   must degrade to plain smoothing, never fabricate) and gravity
//   along the view axis (camera pointing near straight up/down —
//   roll is unobservable there and a lock would be noise). g
//   straight up (0,+1,0, camera upside down) still yields roll_angle
//   = atan2(0, -1) = 180 deg — the formula stays finite; q and -q
//   are the same rotation, so no special case is needed.
//
// LENS PROJECTION + THE FOCAL LENGTH INPUT (deliverable 3 + the
// owner's no-fabrication rule). What the container actually exposes
// (dumped from the fixtures, quoted): the MCRAW container JSON
// carries "focalLengths": [23.950197219848633] and "apertures":
// [1.6299999952316284] — a 35mm-EQUIVALENT focal in mm (the Xiaomi
// 15 Ultra main camera is 23 mm equiv f/1.63; the fixture file names
// say VIDEO_24mm; the vendored decoder never reads the key). The
// container carries NO sensor pixel pitch, no optical format, no
// crop factor — and FrameInfo/RawMeta surface neither. A focal in
// PIXELS therefore cannot be derived from the container alone.
// Per the owner's rule ("Lens kalibrasyonu mevcut değilse uydurma
// katsayılar kullanma"), the warp takes the pixel focal as an
// EXPLICIT INPUT (StabilizationParams::focalLengthPx), and the API
// offers exactly one documented, FLAGGED fallback:
// focalLengthPxFromEquivalent() converts the container's mm value
// under a STATED 35mm-equivalence assumption (f_px = width · f_mm /
// 36, i.e. the source width spans the full-frame 36 mm). Callers
// using it (or any fallback) get status IntrinsicsAssumed per frame
// — the assumption is visible, not silent. A UI focal/zoom-
// compensation control feeding focalLengthPx is REQUIRED for the
// Xiaomi fixtures (deferred to the UI phase).
//
// OFF MODE (owner: "stabilizasyon kapatıldığında gereksiz GPU warp
// uygulanmamalı" — OFF must cost nothing): computeStabilization
// returns identity H, zoom 1.0 and status Off for every frame without
// touching the orientation series; the renderer checks the status and
// skips the warp entirely.

#pragma once

#include <cstdint>
#include <vector>

#include <rawedit/OrientationEstimate.h>
#include <rawedit/Sync.h>

namespace rawedit {

// ---------------------------------------------------------------------------
// Modes (owner spec §7: Smooth default; Lock First Frame; Horizon Lock;
// OFF)
// ---------------------------------------------------------------------------
enum class StabilizationMode {
    Off,            // no warp: identity outputs, status Off, zero cost
    Smooth,         // smoothed target, user strength 0..1
    LockFirstFrame, // target = the first INSIDE frame's actual orientation
    HorizonLock     // Smooth + gravity-leveled roll (needs accel)
};

// Per-frame status (deliverable 5: never silently produce garbage).
enum class StabilizationStatus {
    Ok,                // warp from real orientation + trusted sync
    Off,               // mode Off: identity, renderer skips the warp
    NoImu,             // no orientation series (empty input)
    Unsynced,          // caller flagged the offset unknown
    LowOverlap,        // frame outside the gyro span (clamped lookup)
    IntrinsicsAssumed  // warp used a fallback/assumed focal length
};

const char* stabilizationStatusName(StabilizationStatus s);

// ---------------------------------------------------------------------------
// Smoothing (deliverable 1)
//
// Time-weighted exponential smoothing of the quaternion series on the
// SLERP path, forward + reverse pass (zero group delay):
//
//   tau(s)         = tauMax * pow(tauMin / tauMax, 1 - s)      (s > 0)
//   alpha_i        = 1 - exp(-dt_i / tau),  dt_i = t_i - t_{i-1}
//   forward pass:  q~_i = slerp_toward(q~_{i-1}, q_i, alpha_i)
//   reverse pass:  q'_i = slerp_toward(q'_{i+1}, q~_i, alpha_{i+1})
//
// STRENGTH SEMANTICS (the user's dial, 0..1, clamped; audit fix
// 2026-11-12 — the old map tau = 1 ns·(1e9)^s put every usable strength
// under one frame interval: the shipped 0.7 was tau ≈ 2 ms against ~33 ms
// frames, measured 0.00 deg correction on the 4K fixtures):
//   0.0 -> EXACT pass-through of the raw series (zero smoothing; the
//           strength-0 doctest pins the output series). The old map
//           reached this via tau = 1 ns (alpha = 1 at every real dt);
//           the redesigned tauMin is one frame at 60 fps, which would
//           leave a residue at 0, so the raw output is explicit.
//   0.3 -> tau ≈ 57 ms, 0.5 -> ≈ 129 ms, 0.7 -> ≈ 293 ms,
//   0.9 -> ≈ 664 ms — geometric over the USEFUL range: tauMin = 1/60 s
//           (below one frame interval smoothing is meaningless) up to
//   1.0 -> tau = tauMax (1 s): maximally smooth for this phase (the
//           reference's low-velocity time constant; a ~12 s handheld
//           clip's target all but stops moving).
// The exponential alpha (not a naive lerp alpha) keeps the response
// independent of the sample grid: the same tau on a 473 Hz or 200 Hz
// grid smooths the same physical motion the same amount.
//
// slerp_toward(q, x, a) advances q a fraction a of the way to x along
// the shortest arc (a = 1 -> x exactly; a = 0 -> q exactly — defined
// for all a, stable at antipodal inputs via the w-sign rule).
//
// Edge handling: empty in -> empty out; one sample -> unchanged;
// dt <= 0 (equal/non-increasing stamps) treated as dt = 1 ns (a hold;
// never a NaN).
OrientationSeries smoothOrientationSeries(const OrientationSeries& series,
                                          double strength);

// Relative rotation q_rel = conj(q_target) * q_actual (the direction
// contract above). Inputs need not be normalized; output is.
void relativeRotation(const double qTarget[4], const double qActual[4],
                      double outQRel[4]);

// Rotation angle of q_rel in degrees: 2·acos(clamp(|w|,0,1))·180/pi.
double relativeRotationAngleDeg(const double qRel[4]);

// ---------------------------------------------------------------------------
// Horizon lock (deliverable 1's option; modes HorizonLock / Smooth+flag)
//
// Roll-only leveling rotation from the gravity direction g expressed
// IN THE FRAME BEING LOCKED (computeStabilization evaluates Phase 2's
// gravityCamera in the per-frame target frame; see the header
// derivation): roll = atan2(g.x, -g.y), q_lock = +roll about +Z, so
// that q_target * q_lock is level (ground truth: R(conj(q_lock)) g ==
// (0, -|g.xy|, g.z), doctest-pinned). Returns false with out =
// identity when |g.xy| < 0.1 (gravity along the view axis: roll is
// unobservable — unreliable input must not fabricate a rotation);
// the caller flags the degradation.
bool horizonLockRotation(const double g[3], double outQLock[4]);

// ---------------------------------------------------------------------------
// Lens model + frame transform (deliverable 3)
//
// Pinhole intrinsics in pixels, canonical camera frame X right / Y UP /
// Z towards the viewer (Orientation.h), image v growing DOWN — K
// absorbs the flip:
//
//        [ f  0  cx ]
//   K =  [ 0 -f  cy ]
//        [ 0   0  1 ]
//
// DERIVATION (the flip is load-bearing). A source pixel p = (u,v,1)
// corresponds to the canonical ray d = K⁻¹ p = (x, y, 1) — note z = +1,
// while the camera looks along −Z. Rotating the camera by q_rel moves
// the ray to d' = R(q_rel) d, and the output pixel is p' = K d'/d'_z.
// Substituting: H = K · R(q_rel) · K⁻¹ has (R K⁻¹ p)_z = R₃·(x,y,1),
// the WRONG view axis (+Z instead of −Z) — it inverts pitch and yaw
// (doctest-pinned: a camera pitched down maps its center pixel BELOW
// the output center only with the correct form). The pixel ray with
// z = +1 is the look direction NEGATED, so the rotation must be
// conjugated by the view flip D = diag(1, 1, −1):
//
//     H = K · D · R(q_rel) · D · K⁻¹
//
// (D R D rotates the ray (x, y, 1): flip into the look frame, rotate,
// flip back — geometrically "rotate the view direction, not the +Z
// axis"). This is the standard construction for rotation homographies
// of a forward-looking pinhole (D conjugation, not a composition with
// D). H is still a true homography (det-preserving, D² = I).
//
// frameTransform computes exactly that H (row-major 3x3, SOURCE pixel
// -> OUTPUT pixel, both in v-down pixel coordinates — the matrix a GPU
// sampler consumes directly). Identity q_rel returns the identity H
// exactly. Rotation about camera Z (roll) is unaffected by D and comes
// out a pure 2D rotation about the principal point with the OPPOSITE
// sign in v (a CCW camera roll appears CW on screen — pinned in the
// doctests); rotation about camera X/Y (pitch/yaw) produces the
// perspective terms in the third column and row 3, with the CORRECT
// pitch/yaw signs (camera pitched down ⇒ its center pixel lands below
// the output center — the ground stays below the horizon).
void frameTransform(const double qRel[4], double focalLengthPx,
                    double cx, double cy, double outH[9]);

// Principal point at the image center, focal in px: the common call.
void frameTransform(const double qRel[4], double focalLengthPx,
                    int width, int height, double outH[9]);

// Converts a 35mm-EQUIVALENT focal (mm — what the MCRAW container's
// "focalLengths" array holds; RawMeta::focalLengthMm surfaces it)
// into a pixel focal under the STATED assumption that the source width
// spans the full-frame 36 mm: f_px = width * f_mm / 36. This is the
// API's ONLY fallback and it is an ASSUMPTION (no sensor pitch exists
// in the container): every warp computed from it must carry status
// IntrinsicsAssumed. Returns 0 for non-positive inputs (the caller
// then has no focal at all -> NoImu-style refusal, not a guess).
double focalLengthPxFromEquivalent(double focalLengthMm35, int width);

// ---------------------------------------------------------------------------
// Static crop (deliverable 4)
//
// The minimal uniform zoom factor Z >= 1 (about the principal point)
// such that, for EVERY q_rel in the list, all four corners of the
// output rectangle map INSIDE the source rectangle [0,w]x[0,h] through
// the composite output->source map
//     p_src = H⁻¹( c + (p_out - c)/Z )
// (the displayed output rect [0,w]x[0,h] samples the warped image over
// the window centered at c with half-extents (w/2Z, h/2Z); the corners
// of that window are the four p_out corners). A projective map takes
// the window's corners to the extreme points of its image, so checking
// the four corners is exact, and the constraint is monotone in Z (a
// larger Z shrinks the window, hence its image), which the numeric
// solver relies on. Zoom semantics: Z = 1 no crop; Z = 1.1 = the
// output is the source shrunk 10% about the principal point.
//
// Roll-only closed form (doctest-pinned): for a rotation by theta of a
// w x h rectangle about its center, the minimal shrink factor is
//   Z(theta) = (w·|sin| + h·|cos|) / h  vs  (w·|cos| + h·|sin|) / w,
// max of the two expressions (corner coverage), where sin/cos are of
// theta; the numeric path must reproduce it. The general case runs the
// corner map per quaternion and takes the max over frames — the value
// the clip needs so no frame ever samples outside the source.
double staticCropZoom(const std::vector<double>& quatsRel, // 4 * n
                      double focalLengthPx, double cx, double cy,
                      int width, int height);

// ---------------------------------------------------------------------------
// Clip-level API (deliverable 5)
// ---------------------------------------------------------------------------
struct StabilizationParams {
    StabilizationMode mode = StabilizationMode::Smooth;
    double strength = 0.5;   // 0..1; Smooth/HorizonLock only, clamped
    double zoomLimit = 1.3;  // user clamp on the static crop, >= 1
    double focalLengthPx = 0.0; // <= 0 -> the API refuses (NoImu-class),
                             // never a silent guess — supply a real
                             // value or call focalLengthPxFromEquivalent
                             // and accept the Assumed flag
    double principalPoint[2] = {0.0, 0.0}; // <= 0 sentinel -> center
    int width = 0;           // source pixel dims; required > 0
    int height = 0;
};

struct StabilizationFrame {
    double h[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1}; // K·D·R·D·K⁻¹ row-major
    double zoom = 1.0;                         // clip-wide static crop
    StabilizationStatus status = StabilizationStatus::Ok;
};

struct StabilizationResult {
    std::vector<StabilizationFrame> frames; // per frameTimestampsNs
    double requiredZoom = 1.0; // unclamped static-crop need
    double appliedZoom = 1.0;  // after the user zoomLimit clamp
    bool intrinsicsAssumed = false; // any frame on the fallback focal
    bool horizonLockDegraded = false; // lock asked, gravity unavailable
    std::vector<double> relativeAngleDeg; // per-frame |q_rel| angle
};

// The clip-level pipeline:
//   1. mode Off            -> all frames {I, 1.0, Off}; series untouched.
//   2. empty series        -> all frames {I, 1.0, NoImu}.
//   3. !offsetKnown        -> all frames {I, 1.0, Unsynced} (the caller
//      refuses to guess the clock bridge; Sync.h's estimate types are
//      the source of that verdict).
//   4. per frame: imuTs = sync.toImu(frameTs); orientationAt(imuTs)
//      (SLERP, clamped at the gyro span ends — OrientationEstimate.h).
//      imuTs outside [series.firstTs(), series.lastTs()] -> status
//      LowOverlap (the warp is still computed from the clamped
//      orientation; the flag tells the UI which frames are held).
//   5. target per mode:
//        Smooth / HorizonLock: smoothOrientationSeries(actual, strength)
//                              (HorizonLock additionally applies the
//                              leveling rotation, or degrades to plain
//                              smoothing when gravity is unusable —
//                              horizonLockDegraded flags it)
//        LockFirstFrame:      the first INSIDE frame's actual
//                              orientation, held constant; when NO
//                              frame is inside the gyro span the first
//                              frame's clamped orientation is used
//                              (the series' first quaternion — the
//                              only orientation in hand; the
//                              LowOverlap flags already say the
//                              lookup was clamped).
//   6. q_rel = conj(q_target) * q_actual per frame.
//   7. staticCropZoom over the q_rel list -> requiredZoom, clamped by
//      params.zoomLimit (applied >= 1) -> appliedZoom.
//   8. H = K·D·R(q_rel)·D·K⁻¹ per frame (the flip D at frameTransform).
// Statuses per frame (precedence, highest first): NoImu > Unsynced >
// IntrinsicsAssumed > LowOverlap > Ok (Off never coexists with these
// — mode Off short-circuits the whole pipeline into a uniform
// {I, 1.0, Off}). IntrinsicsAssumed is applied whenever the caller
// marked the focal assumed (see assumedFocal below).
// computeStabilization itself never invents a focal: focalLengthPx
// <= 0 produces NoImu-class refusal {I, 1.0, NoImu} for every frame.
//
// assumedFocal: the caller passes true when the focal came from
// focalLengthPxFromEquivalent (or any assumption) — the honest wiring
// of the IntrinsicsAssumed flag; the math is identical either way.
//
// gravityCamera: Phase 2's camera-frame gravity (VqfOrientation::
// gravityCamera), used only for the horizon lock; nullptr = no
// gravity -> lock degrades (flagged), plain smoothing applies.
StabilizationResult computeStabilization(const OrientationSeries& orientationSeries,
                                         const SyncModel& sync,
                                         const std::vector<int64_t>& frameTimestampsNs,
                                         const double gravityCamera[3],
                                         bool offsetKnown,
                                         bool assumedFocal,
                                         const StabilizationParams& params);

} // namespace rawedit
