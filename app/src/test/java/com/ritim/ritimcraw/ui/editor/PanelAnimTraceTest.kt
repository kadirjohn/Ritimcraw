package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PanelAnimTrace tests, all JVM (B13 panel-jank diagnosis, 2026-11-26):
 * the pure counter behind the debug ritim-trace "panel anim" line — how
 * many preview render requests and SurfaceView resizes fired inside one
 * panel animation window. The pinned expectation the diagnosis reads:
 * WITH the surface-size freeze a downward settle must show
 * resizes=0; a nonzero count indicts the freeze and names the
 * buffer-realloc jank suspect. The counts-outside-window rule is what
 * keeps the counts honest (steady-state renders never inflate a burst).
 */
class PanelAnimTraceTest {

    // ---- the window: start resets, end sums ----

    @Test
    fun anim_window_counts_renders_and_resizes_and_durations() {
        val t = PanelAnimTrace()
        t.onAnimStart(PanelState.EXPANDED, nowMs = 1_000)
        t.onRenderRequest()
        t.onRenderRequest()
        t.onRenderRequest()
        t.onSurfaceResize()
        val line = t.onAnimEnd(PanelState.COLLAPSED, nowMs = 1_231)
        assertEquals(
            "panel anim from=EXPANDED to=COLLAPSED dur_ms=231 renders=3 resizes=1",
            line,
        )
    }

    @Test
    fun a_clean_downward_settle_is_three_renders_and_zero_resizes() {
        // The freeze's promise, pinned: the buffers never reallocate
        // during the animation, so only the render requests themselves
        // count against the window.
        val t = PanelAnimTrace()
        t.onAnimStart(PanelState.NORMAL, nowMs = 0)
        repeat(3) { t.onRenderRequest() }
        assertEquals(
            "panel anim from=NORMAL to=COLLAPSED dur_ms=200 renders=3 resizes=0",
            t.onAnimEnd(PanelState.COLLAPSED, nowMs = 200),
        )
    }

    // ---- counting stops at the window edges ----

    @Test
    fun renders_and_resizes_outside_the_window_are_not_counted() {
        val t = PanelAnimTrace()
        // Before any window opens: none of these may leak into a burst.
        t.onRenderRequest()
        t.onSurfaceResize()
        // The observer's initial "not animating" fire must produce no line.
        assertNull(t.onAnimEnd(PanelState.EXPANDED, nowMs = 100))
        // An open window counts what happens inside it ... and the request
        // AFTER the burst is not the burst's.
        t.onAnimStart(PanelState.COLLAPSED, nowMs = 200)
        assertEquals(
            "panel anim from=COLLAPSED to=NORMAL dur_ms=300 renders=0 resizes=0",
            t.onAnimEnd(PanelState.NORMAL, nowMs = 500),
        )
        // After the window closed, requests are again not this burst's:
        // a second end has no window to close.
        t.onRenderRequest()
        t.onSurfaceResize()
        assertNull(t.onAnimEnd(PanelState.NORMAL, nowMs = 5_000))
    }

    @Test
    fun a_second_start_resets_the_counts_of_the_previous_burst() {
        val t = PanelAnimTrace()
        t.onAnimStart(PanelState.NORMAL, nowMs = 0)
        t.onRenderRequest()
        t.onRenderRequest()
        t.onRenderRequest()
        t.onRenderRequest()
        t.onSurfaceResize()
        t.onAnimEnd(PanelState.EXPANDED, nowMs = 50)
        // A new gesture starts a fresh burst: the old counts must not
        // accumulate into it.
        t.onAnimStart(PanelState.EXPANDED, nowMs = 1_000)
        t.onRenderRequest()
        assertEquals(
            "panel anim from=EXPANDED to=NORMAL dur_ms=250 renders=1 resizes=0",
            t.onAnimEnd(PanelState.NORMAL, nowMs = 1_250),
        )
    }

    // ---- degenerate edges ----

    @Test
    fun a_clock_that_went_backwards_clamps_duration_to_zero() {
        val t = PanelAnimTrace()
        t.onAnimStart(PanelState.NORMAL, nowMs = 2_000)
        assertEquals(
            "panel anim from=NORMAL to=NORMAL dur_ms=0 renders=0 resizes=0",
            t.onAnimEnd(PanelState.NORMAL, nowMs = 1_999),
        )
        // And the window is closed afterwards: not stuck open.
        assertNull(t.onAnimEnd(PanelState.NORMAL, nowMs = 3_000))
    }

    @Test
    fun render_requests_without_an_open_window_are_free() {
        // The hook sites call this on EVERY render; with no animation in
        // flight the call must be a pure no-op (and in release the site
        // never even reaches it — RenderTrace.enabled gates the call).
        val t = PanelAnimTrace()
        repeat(100) { t.onRenderRequest() }
        repeat(100) { t.onSurfaceResize() }
        t.onAnimStart(PanelState.COLLAPSED, nowMs = 0)
        var line = t.onAnimEnd(PanelState.EXPANDED, nowMs = 10)
        // The 200 stray calls left nothing in the line's counts.
        assertTrue(line!!.endsWith("renders=0 resizes=0"))
        // Double end: the second call has no window to close.
        assertNull(t.onAnimEnd(PanelState.EXPANDED, nowMs = 20))
        line = null
    }
}
