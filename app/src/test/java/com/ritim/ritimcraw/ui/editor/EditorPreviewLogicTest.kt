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

    // ---- Temporal NR group visibility (MCRAW only; DN-05..DN-09) ----

    @Test
    fun mcraw_shows_temporal_nr() {
        // Temporal denoise merges neighboring CFA frames — the group
        // belongs on the Detail tab for RAW clips.
        assertTrue(EditorPreviewLogic.showTemporalNr(SourcePath.MCRAW))
    }

    @Test
    fun encoded_hides_temporal_nr() {
        // The encoded (Mi-Log MP4) path takes the same Grade but the bridge
        // ignores the temporal fields there: no CFA, no neighbor frames, so
        // the group is hidden rather than showing a dead control.
        assertFalse(EditorPreviewLogic.showTemporalNr(SourcePath.ENCODED))
    }
}
