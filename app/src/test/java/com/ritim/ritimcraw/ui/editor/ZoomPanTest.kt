package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * ZoomPan tests (owner request 2026-11-11 section 6): every decision the
 * preview's pinch/pan handler and display transform rely on — clamps,
 * pan bounds, zoom-around-focus, the reset, and the gesture interplay
 * matrix — is pure and JVM-tested here.
 */
class ZoomPanTest {

    // ---- zoom clamp ----

    @Test
    fun `zoom clamps to the 1x floor and the 5x ceiling`() {
        // The mission's range, clamped not guarded: a pinch past the
        // limit settles AT the ceiling.
        assertEquals(1f, ZoomPan.clampZoom(0.5f), 0f)
        assertEquals(1f, ZoomPan.clampZoom(1f), 0f)
        assertEquals(2.5f, ZoomPan.clampZoom(2.5f), 0f)
        assertEquals(5f, ZoomPan.clampZoom(5f), 0f)
        assertEquals(5f, ZoomPan.clampZoom(12f), 0f)
        // The ceiling is a named constant so the UI can cite it.
        assertEquals(5f, ZoomPan.MAX_ZOOM, 0f)
    }

    @Test
    fun `isZoomed gates panning to above 1x`() {
        // §6: "at 1x no panning" — exactly 1x is NOT zoomed; anything
        // above pans.
        assertFalse(ZoomPan.isZoomed(1f))
        assertTrue(ZoomPan.isZoomed(1.0001f))
        assertTrue(ZoomPan.isZoomed(5f))
    }

    // ---- pan bounds ----

    @Test
    fun `pan bound is half the overflow per axis`() {
        // At 2x the content is twice the box: the center may drift half
        // the box before an edge shows background.
        assertEquals(0f, ZoomPan.panBound(1f), 0f)
        assertEquals(0.5f, ZoomPan.panBound(2f), 0f)
        assertEquals(1f, ZoomPan.panBound(3f), 0f)
        assertEquals(2f, ZoomPan.panBound(5f), 0f)
        // Below 1x (a transient pinch value) never yields a negative bound.
        assertEquals(0f, ZoomPan.panBound(0.5f), 0f)
    }

    @Test
    fun `pan clamps so the content never leaves the box`() {
        val bound = ZoomPan.panBound(2f) // 0.5
        val clamped = ZoomPan.clampPan(ZoomPan.State(zoom = 2f, panX = 0.9f, panY = -1.2f))
        assertEquals(bound, clamped.panX, 0f)
        assertEquals(-bound, clamped.panY, 0f)
        // In-bounds values pass through untouched.
        val inside = ZoomPan.clampPan(ZoomPan.State(zoom = 2f, panX = 0.25f, panY = -0.3f))
        assertEquals(0.25f, inside.panX, 0f)
        assertEquals(-0.3f, inside.panY, 0f)
    }

    @Test
    fun `panning at 1x is a no-op by clamp`() {
        // The §6 gate as a pure rule: a drag at 1x (however it arrives)
        // snaps back to centered.
        val after = ZoomPan.panBy(ZoomPan.State(), 0.3f, -0.2f)
        assertEquals(1f, after.zoom, 0f)
        assertEquals(0f, after.panX, 0f)
        assertEquals(0f, after.panY, 0f)
    }

    @Test
    fun `panBy accumulates and clamps`() {
        var s = ZoomPan.State(zoom = 2f)
        s = ZoomPan.panBy(s, 0.3f, 0.3f)
        assertEquals(0.3f, s.panX, 0f)
        s = ZoomPan.panBy(s, 0.3f, 0f)
        // 0.6 exceeds the 0.5 bound: clamped to 0.5.
        assertEquals(0.5f, s.panX, 0f)
    }

    // ---- zoom around the focus point ----

    @Test
    fun `stationary centroid zooms in place`() {
        // Fingers spread around the CENTER without moving: the center
        // content point stays centered (pan stays 0).
        val after = ZoomPan.pinch(
            ZoomPan.State(),
            anchorFocusX = 0.5f, anchorFocusY = 0.5f,
            focusX = 0.5f, focusY = 0.5f,
            zoom = 2f,
        )
        assertEquals(2f, after.zoom, 0f)
        assertEquals(0f, after.panX, 1e-6f)
        assertEquals(0f, after.panY, 1e-6f)
    }

    @Test
    fun `zooming pins the content under the focus point`() {
        // 2x zoom with the fingers anchored on the box's top-left
        // quadrant center (0.25, 0.25): the content point that sat there
        // must still sit there after the pinch.
        val before = ZoomPan.State()
        val anchorX = 0.25f
        val anchorY = 0.25f
        val after = ZoomPan.pinch(before, anchorX, anchorY, anchorX, anchorY, 2f)
        assertEquals(2f, after.zoom, 0f)
        // Inverse map the focus through the transform: b = 0.5 + pan +
        // z*(c - 0.5) must give back the anchor's content point for BOTH
        // the before and after states — the invariant the derivation
        // targets.
        val contentBeforeX = (anchorX - 0.5f - before.panX) / before.zoom + 0.5f
        val contentAfterX = (anchorX - 0.5f - after.panX) / after.zoom + 0.5f
        assertEquals(contentBeforeX, contentAfterX, 1e-6f)
        val contentBeforeY = (anchorY - 0.5f - before.panY) / before.zoom + 0.5f
        val contentAfterY = (anchorY - 0.5f - after.panY) / after.zoom + 0.5f
        assertEquals(contentBeforeY, contentAfterY, 1e-6f)
    }

    @Test
    fun `a moving centroid drags the content with the fingers`() {
        // Fingers spread AND move right by 0.1 box widths: the content
        // moves with them (the pan absorbs the centroid delta).
        val after = ZoomPan.pinch(
            ZoomPan.State(),
            anchorFocusX = 0.4f, anchorFocusY = 0.5f,
            focusX = 0.5f, focusY = 0.5f,
            zoom = 2f,
        )
        assertEquals(2f, after.zoom, 0f)
        // The content under the ORIGINAL centroid is now under the NEW
        // centroid: the invariant check, not a canned pan value.
        val before = ZoomPan.State()
        val contentUnderAnchor = (0.4f - 0.5f - before.panX) / before.zoom + 0.5f
        val contentUnderNewFocus = (0.5f - 0.5f - after.panX) / after.zoom + 0.5f
        assertEquals(contentUnderAnchor, contentUnderNewFocus, 1e-6f)
        // And the pan moved right (positive fraction), with the fingers.
        assertTrue(after.panX > 0f)
    }

    @Test
    fun `pinch against the ceiling still pins the focus point`() {
        // A pinch that ASKS for 12x lands at the 5x ceiling; the invariant
        // must hold for the APPLIED zoom, so the focus content stays put.
        val anchorX = 0.3f
        val before = ZoomPan.State(zoom = 4f, panX = 0.1f, panY = 0f)
        val after = ZoomPan.pinch(before, anchorX, 0.5f, anchorX, 0.5f, 12f)
        assertEquals(5f, after.zoom, 0f)
        val contentBefore = (anchorX - 0.5f - before.panX) / before.zoom + 0.5f
        val contentAfter = (anchorX - 0.5f - after.panX) / after.zoom + 0.5f
        assertEquals(contentBefore, contentAfter, 1e-6f)
    }

    @Test
    fun `a focus inside the box never needs a clamped pan`() {
        // Zoom-around-focus from the identity needs pan = (f - 0.5)(1 - z),
        // whose magnitude is at most 0.5(z - 1) = panBound(z) while the
        // focus stays inside the box — so the invariant is EXACT (the
        // clamp never binds), even for a focus hugging the edge at 5x.
        val after = ZoomPan.pinch(
            ZoomPan.State(),
            anchorFocusX = 0.95f, anchorFocusY = 0.5f,
            focusX = 0.95f, focusY = 0.5f,
            zoom = 5f,
        )
        assertEquals(5f, after.zoom, 0f)
        assertEquals(-1.8f, after.panX, 1e-6f) // (0.95 - 0.5) * (1 - 5)
        // And in bounds: the content still covers the box.
        assertTrue(abs(after.panX) <= ZoomPan.panBound(after.zoom))
    }

    @Test
    fun `a dragging centroid clamps when the content hits the box edge`() {
        // The clamp's real job: a two-finger drag walks the centroid
        // right; the pan follows the invariant until the content reaches
        // the box edge, then holds at the bound.
        var s = ZoomPan.State(zoom = 2f)
        // Three event-to-event pinches: centroid 0.5 -> 0.7 -> 0.9 -> 1.1.
        s = ZoomPan.pinch(s, 0.5f, 0.5f, 0.7f, 0.5f, 2f)
        assertEquals(0.2f, s.panX, 1e-6f)
        s = ZoomPan.pinch(s, 0.7f, 0.5f, 0.9f, 0.5f, 2f)
        assertEquals(0.4f, s.panX, 1e-6f)
        // The invariant wants 0.6; the 2x bound is 0.5 — clamped, the
        // content stops at the edge and the gesture keeps tracking.
        s = ZoomPan.pinch(s, 0.9f, 0.5f, 1.1f, 0.5f, 2f)
        assertEquals(ZoomPan.panBound(2f), s.panX, 1e-6f)
    }

    @Test
    fun `pinch back to 1x recenters`() {
        // Zooming back out to exactly 1x: the bound is 0, so the pan
        // clamps back to centered — the box fills again.
        val zoomed = ZoomPan.State(zoom = 3f, panX = 0.4f, panY = -0.4f)
        val after = ZoomPan.pinch(zoomed, 0.5f, 0.5f, 0.5f, 0.5f, 1f)
        assertEquals(1f, after.zoom, 0f)
        assertEquals(0f, after.panX, 0f)
        assertEquals(0f, after.panY, 0f)
    }

    // ---- reset on clip change ----

    @Test
    fun `the default state is the reset target`() {
        // PreviewSurface keys its zoom/pan state on the clip: a fresh
        // clip starts from the identity, and the default IS that target.
        assertEquals(ZoomPan.State(), ZoomPan.State(zoom = 1f, panX = 0f, panY = 0f))
        // A zoomed state is not the default (the reset is a real change).
        assertFalse(ZoomPan.State(zoom = 2f) == ZoomPan.State())
    }

    // ---- gesture interplay matrix (§6) ----

    @Test
    fun `interaction matrix - pinch never yields a hold, and pan only while zoomed`() {
        // The matrix the handler implements (the pure halves):
        //  - at 1x: isZoomed false -> a single finger NEVER pans -> the
        //    hold handler owns the stream (stationary press holds; the
        //    hold's own slop check cancels on movement).
        assertFalse(ZoomPan.isZoomed(1f))
        //  - zoomed: isZoomed true -> a single finger pans after slop and
        //    consumes, so a pan never reaches the hold.
        assertTrue(ZoomPan.isZoomed(2f))
        //  - a pinch clamps/updates zoom through the same decisions;
        //    from 1x it always leaves isZoomed true (a pinch that grows
        //    the spread zooms in) or returns exactly to the 1x floor.
        val pinched = ZoomPan.pinch(ZoomPan.State(), 0.5f, 0.5f, 0.5f, 0.5f, 2f)
        assertTrue(ZoomPan.isZoomed(pinched.zoom))
        val unpinched = ZoomPan.pinch(pinched, 0.5f, 0.2f, 0.5f, 0.2f, 1f)
        assertFalse(ZoomPan.isZoomed(unpinched.zoom))
    }
}
