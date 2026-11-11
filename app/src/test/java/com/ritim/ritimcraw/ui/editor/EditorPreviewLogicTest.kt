package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.engine.SourcePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EditorPreviewLogic tests: the pure decision matrix for the preview area
 * (which state shows when) and the render-ability gate, JVM-testable without
 * Compose or the native lib.
 */
class EditorPreviewLogicTest {

    // ---- stateOf matrix ----

    @Test
    fun idle_when_nothing_requested() {
        assertEquals(
            PreviewState.IDLE,
            EditorPreviewLogic.stateOf(
                renderRequested = false, renderInFlight = false,
                hasFrame = false, lastRenderFailed = false,
            ),
        )
    }

    @Test
    fun rendering_wins_over_everything() {
        // A re-render is in flight: show the spinner state even while a good
        // frame or a previous failure is on screen.
        assertEquals(
            PreviewState.RENDERING,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = true,
                hasFrame = true, lastRenderFailed = true,
            ),
        )
    }

    @Test
    fun frame_beats_failure() {
        // A failed re-render keeps the last good frame on screen (grading
        // continues on the stale frame rather than yanking the image away).
        assertEquals(
            PreviewState.FRAME,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = true, lastRenderFailed = true,
            ),
        )
    }

    @Test
    fun failed_only_when_requested_and_no_frame() {
        assertEquals(
            PreviewState.FAILED,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = false, lastRenderFailed = true,
            ),
        )
    }

    @Test
    fun no_frame_no_failure_no_request_is_idle() {
        assertEquals(
            PreviewState.IDLE,
            EditorPreviewLogic.stateOf(
                renderRequested = true, renderInFlight = false,
                hasFrame = false, lastRenderFailed = false,
            ),
        )
    }

    // ---- canRender gate ----

    @Test
    fun negative_fd_cannot_render() {
        assertFalse(EditorPreviewLogic.canRender(fd = -1, nativeAvailable = true))
    }

    @Test
    fun zero_fd_can_render() {
        // fd 0 is a valid POSIX fd; only negatives mean "no source".
        assertTrue(EditorPreviewLogic.canRender(fd = 0, nativeAvailable = true))
    }

    @Test
    fun missing_native_engine_cannot_render() {
        // Placeholder fallback: without the .so the editor stays a working
        // grade panel — the button is disabled, the placeholder shows.
        assertFalse(EditorPreviewLogic.canRender(fd = 5, nativeAvailable = false))
    }

    @Test
    fun fd_plus_engine_can_render() {
        assertTrue(EditorPreviewLogic.canRender(fd = 5, nativeAvailable = true))
    }

    // ---- RAW-stage toggle visibility (encoded clips hide the CFA rows) ----

    @Test
    fun mcraw_shows_raw_stage_toggles() {
        // Highlight Reconstruction / Keep Vignetting drive MCRAW develop
        // stages — they belong on the Color tab for RAW clips.
        assertTrue(EditorPreviewLogic.showRawStageToggles(SourcePath.MCRAW))
    }

    @Test
    fun encoded_hides_raw_stage_toggles() {
        // Decoded YUV has no CFA stage to reconstruct or vignette: the two
        // RAW rows are meaningless on a Mi-Log MP4 clip. Everything else
        // (sliders, As Shot) stays — the full Grade still passes through.
        assertFalse(EditorPreviewLogic.showRawStageToggles(SourcePath.ENCODED))
    }

    // ---- clip info resolution preference (owner request 2026-11-11 §2) ----

    @Test
    fun session_recorded_resolution_wins() {
        // The session's RECORDED frame size (the first frame's real
        // dimensions from the source) is the clip's resolution; the
        // persisted import stat is only the fallback.
        assertEquals(4096, EditorPreviewLogic.recordedWidth(4096, 3840))
    }

    @Test
    fun persisted_stat_fills_the_gap_when_session_silent() {
        // Session-less editor (no usable fd / failed open): the persisted
        // import stat still shows something instead of 0.
        assertEquals(3840, EditorPreviewLogic.recordedWidth(0, 3840))
    }

    @Test
    fun zero_import_record_is_shown_not_rejected() {
        // An old import that recorded 0x0 shows 0 — the spec forbids
        // demanding a re-import for it; whatever is known is shown.
        assertEquals(0, EditorPreviewLogic.recordedWidth(0, 0))
    }
}
