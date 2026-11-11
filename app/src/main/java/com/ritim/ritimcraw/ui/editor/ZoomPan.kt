package com.ritim.ritimcraw.ui.editor

/**
 * Pure pinch-zoom + pan decisions for the preview surface (owner request
 * 2026-11-11 section 6). No Compose types: every rule the gesture handler
 * and the display transform rely on is a JVM-tested decision here.
 *
 * UNITS — [State.panX] and [State.panY] are fractions of the preview BOX
 * (panX 0.25 = a quarter of the box width right, panY 0.25 = a quarter of
 * the box height down), and the zoom focus points are box fractions too
 * ((0,0) top-left, (1,1) bottom-right). Fraction units make the math
 * aspect-free: a portrait clip in its portrait-shaped box and a landscape
 * clip in its landscape-shaped box run the IDENTICAL rules, so the same
 * tests cover both.
 *
 * PAN BOUNDS — in the steady state the content exactly fills the box
 * (ContentScale.Fit on a matching ratio; the quarter-turn cover scale from
 * section 5 restores the fill after a manual rotation). At zoom z the
 * content is z times the box, centered, so the center may drift at most
 * (z - 1) / 2 of the box per axis before an edge shows background:
 * [panBound]. At 1x the bound is 0 — no panning at all (the mission's
 * "at 1x no panning" is this clamp, not a gesture special case).
 *
 * TRANSFORM ORDER (the display side composes, outermost last):
 *   translate(pan) ∘ rotate(manual delta) ∘ scale(cover * zoom)
 * The rotation (section 5) and the zoom scale about the content center;
 * the pan translation is applied LAST, in the box's screen space — so a
 * drag moves the already-rotated, already-zoomed content in the direction
 * of the finger. "Zoom/pan in rotated space" = the zoom scales the rotated
 * content and the pan translates it; both operate on the post-rotation
 * content, never pre-rotating a pan vector.
 *
 * GESTURE INTERPLAY (the section 6 matrix; the handler in PreviewSurface
 * follows these decisions, the hold handler in ClipEditorScreen is
 * untouched):
 *  - pinch (2+ pointers): always consumes — the before/after hold can
 *    never engage from a pinch, before it or after (a leftover finger
 *    keeps consuming until every pointer lifts);
 *  - single finger while zoomed: pans ONLY after the touch slop is
 *    crossed (and consumes from then on) — a stationary press while
 *    zoomed still reaches the hold handler, and a pan never fires the
 *    hold;
 *  - single finger at 1x: fully passive — no consume, so the hold owns
 *    the stream exactly as before (stationary press holds; movement
 *    cancels via the hold's own slop check).
 *
 * MAX_ZOOM — 5x: preview frames render at the tier's max dimension
 * (~1080-1280 px), so 5x inspects individual source pixels at practical
 * sharpness; beyond that the frame is mostly interpolation and the pan
 * bounds grow past what a finger can usefully traverse. Clamped, not
 * guarded: a pinch past the limit settles AT 5x.
 */
object ZoomPan {

    /** The un-zoomed floor: at 1x the preview fills the box and cannot pan. */
    const val MIN_ZOOM = 1f

    /**
     * The zoom ceiling. 5x keeps zoomed detail real (tier-sized preview
     * frames still carry source-pixel information) — see the object KDoc.
     */
    const val MAX_ZOOM = 5f

    /**
     * The zoom/pan transform. Pan is in box fractions (see the object
     * KDoc); the default is the identity (1x, centered) — also the exact
     * reset target when the clip changes (PreviewSurface keys its
     * rememberSaveable on the clip, so a fresh clip starts from this).
     */
    data class State(
        val zoom: Float = MIN_ZOOM,
        val panX: Float = 0f,
        val panY: Float = 0f,
    )

    /** Zoom clamped to [MIN_ZOOM]..[MAX_ZOOM]. */
    fun clampZoom(zoom: Float): Float = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)

    /**
     * True when a single-finger drag should pan (the §6 gate): at exactly
     * 1x there is nothing to pan — the content fills the box.
     */
    fun isZoomed(zoom: Float): Boolean = zoom > MIN_ZOOM

    /**
     * The pan bound per axis, in box fractions: half the content's overflow
     * ((zoom - 1) / 2). Values below 1x clamp to 0 — never a negative
     * bound.
     */
    fun panBound(zoom: Float): Float = ((zoom - 1f) / 2f).coerceAtLeast(0f)

    /**
     * Pan clamped so the content always stays inside the preview box: each
     * axis within ±[panBound]. At 1x any pan snaps back to 0 (the content
     * cannot drift off a box it exactly fills).
     */
    fun clampPan(state: State): State {
        val bound = panBound(state.zoom)
        return state.copy(
            panX = state.panX.coerceIn(-bound, bound),
            panY = state.panY.coerceIn(-bound, bound),
        )
    }

    /**
     * A single-finger drag: adds the drag deltas (box fractions — divide
     * the pixel delta by the box size at the call site) and clamps, so the
     * content never leaves the box. At 1x this is a no-op by clamp.
     */
    fun panBy(state: State, dxFraction: Float, dyFraction: Float): State =
        clampPan(state.copy(panX = state.panX + dxFraction, panY = state.panY + dyFraction))

    /**
     * The pinch decision: zoom to [zoom] around the focus point, keeping
     * the content point that sat under the ANCHOR centroid under the
     * CURRENT centroid (a two-finger drag moves the content with the
     * fingers; a stationary centroid zooms in place).
     *
     * Derivation: a box point b shows content point c as
     * b = 0.5 + pan + z * (c - 0.5). Solving for the pan that keeps the
     * anchor's content point under the current focus:
     *   pan' = (focus - 0.5) - (z' / z) * (anchorFocus - 0.5 - pan)
     * with z' the CLAMPED zoom — the invariant must hold for the zoom
     * actually applied, so a pinch against the 5x ceiling still pins the
     * focus point.
     *
     * Both focus points are box fractions. The returned pan is clamped,
     * so a focus near an edge cannot pull the content out of the box.
     */
    fun pinch(
        state: State,
        anchorFocusX: Float,
        anchorFocusY: Float,
        focusX: Float,
        focusY: Float,
        zoom: Float,
    ): State {
        val z = clampZoom(zoom)
        val panX = (focusX - 0.5f) - (z / state.zoom) * (anchorFocusX - 0.5f - state.panX)
        val panY = (focusY - 0.5f) - (z / state.zoom) * (anchorFocusY - 0.5f - state.panY)
        return clampPan(State(zoom = z, panX = panX, panY = panY))
    }
}
