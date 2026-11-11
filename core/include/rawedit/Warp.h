// rawedit::Warp — the stabilization CPU warp (gyro Phase 5).
//
// computeStabilization (Stabilization.h) turns the clip's orientation
// series into a per-frame homography H (SOURCE pixel -> OUTPUT pixel,
// row-major 3x3, v-down image coordinates) plus a crop zoom. warpFrame
// applies that pair to a developed frame. It is the ONE warp in the
// codebase: the paused preview (1280px tier) and the MP4 export (full
// resolution) both call it, so the two can never drift apart — the JNI
// bridge (Phase 5 wiring) is a thin seam over this function, never a
// second implementation.
//
// PLACEMENT (binding decision; mirrors the render order in Develop.h):
// the warp applies BEFORE the upright rotation, on the developed
// STORED-ORIENTATION buffer, in both preview and export. Orientation.h
// defines the canonical camera frame "in the recorded raw image", and
// H's intrinsics (K with -f absorbing the y-up frame, the view-axis
// flip D) tie it to the RECORDED image's pixel grid — an already
// upright-rotated buffer is a different grid and must not be warped
// with this H. Callers therefore pass the buffer being warped: the
// plan (computeStabilization) is computed at the SAME dims (its
// params.width/height are the warped buffer's dims, thumbnail dims for
// the preview and full dims for the export), and (cx, cy) is the same
// principal point the plan was computed with — its convention:
// params.principalPoint when set, else the buffer center.
//
// THE MAP (the exact inverse-sampling composite):
//   p_src = H^-1 * ( c + (p_out - c) / Z )
// with c = (cx, cy) and Z = max(zoom, 1). The output rect [0,w] x [0,h]
// samples the source over the window centered at c with half-extents
// (w/2Z, h/2Z), mapped through H^-1 — the same window staticCropZoom
// corner-checks when it derives the required zoom, so a zoom at or
// above the plan's zoom keeps every sample inside the source.
//
// Sampling is bilinear. Samples outside [0, w-1] x [0, h-1] clamp to
// the nearest edge pixel — the honest behavior when the user's zoom
// limit sits below the plan's required zoom (the frame keeps its size
// and stays finite; the frame status already flags the clip). An
// output pixel whose source lands behind the projective plane (no real
// stabilization plan produces one; H stays near identity) clamps to
// the top-left corner — never a garbage read.
//
// Cost shape: one 3x3 composite per frame, then per output pixel six
// multiplies and one bilinear tap — O(w*h), the order of one develop
// stage. The fixture test in core/tests/test_warp.cpp quotes measured
// per-frame costs at both tiers; nothing here is GPU-bound (deferred,
// see the Phase 5 notes).
//
// Fast paths (all bit-exact and tested):
//   - empty src (w == 0) returns an empty ImageF;
//   - H exactly the identity AND zoom == 1 returns src byte-identical
//     (the stabilization-off look is free, like every neutral stage in
//     this codebase);
//   - a degenerate H (singular to 1e-12, the same threshold
//     staticCropZoom skips on) returns src unchanged — an honest no-op,
//     never a garbage frame;
//   - zoom < 1 clamps to 1 (a warp may not magnify the frame).

#pragma once

#include <rawedit/Develop.h>

namespace rawedit {

// Warps one developed frame by the stabilization plan's per-frame
// homography and zoom. h is the row-major 3x3 from StabilizationFrame.h
// (SOURCE pixel -> OUTPUT pixel); zoom is StabilizationFrame.zoom;
// (cx, cy) is the principal point the plan was computed with. The
// result has src's dimensions. See the header doc for the composite
// map, the edge clamp and the fast paths.
ImageF warpFrame(const ImageF& src, const double h[9], double zoom,
                 double cx, double cy);

} // namespace rawedit
