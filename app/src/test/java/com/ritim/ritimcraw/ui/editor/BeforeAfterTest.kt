package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BeforeAfter press-hold decisions (owner feature list W5): the pure
 * state transitions of the hold, JVM-testable without Compose — the
 * screen wires these into its render gate and the preview's press
 * gesture.
 */
class BeforeAfterTest {

    // ---- paused-only guard ----

    @Test
    fun hold_allowed_when_paused_with_frame_and_engine() {
        assertTrue(
            BeforeAfter.canHold(isPlaying = false, hasFrame = true, canRender = true)
        )
    }

    @Test
    fun playing_rejects_the_hold() {
        // Playback continuously offers graded renders through the same
        // gate; a neutral hold render would be superseded within one
        // frame. The hold is ignored during playback (documented choice).
        assertFalse(
            BeforeAfter.canHold(isPlaying = true, hasFrame = true, canRender = true)
        )
    }

    @Test
    fun no_frame_rejects_the_hold() {
        // Nothing on screen to compare against: the hold is meaningless.
        assertFalse(
            BeforeAfter.canHold(isPlaying = false, hasFrame = false, canRender = true)
        )
    }

    @Test
    fun no_engine_rejects_the_hold() {
        // The render path cannot run at all (placeholder preview).
        assertFalse(
            BeforeAfter.canHold(isPlaying = false, hasFrame = true, canRender = false)
        )
    }

    // ---- grade swap ----

    @Test
    fun hold_grade_is_the_neutral_original() {
        // A default GradeK is the no-op against the ungraded pipeline:
        // as-shot WB, zero tone, no LUT, no denoise — the clip as the
        // camera recorded it.
        assertEquals(GradeK(), BeforeAfter.holdGrade())
    }

    @Test
    fun graded_clip_changes_picture_when_held() {
        // Any non-neutral grade means the hold shows a different frame.
        assertTrue(BeforeAfter.holdChangesPicture(GradeK(exposureEv = 0.5f)))
    }

    @Test
    fun neutral_grade_holds_no_op() {
        // Already-neutral grade: the hold render would be identical, so
        // the screen skips it and the pipeline stays idle.
        assertFalse(BeforeAfter.holdChangesPicture(GradeK()))
    }

    @Test
    fun lut_only_grade_still_counts_as_graded() {
        // An invisible-looking grade that is NOT neutral (a selected LUT
        // with default strength) still swaps the picture on hold.
        assertTrue(BeforeAfter.holdChangesPicture(GradeK(lutId = "cube-a")))
    }
}
