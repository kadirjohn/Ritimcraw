package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BeforeAfter press-hold decisions (owner feature list W5 + owner request
 * 2026-11-11): the pure state transitions of the hold, JVM-testable
 * without Compose — the screen wires these into its render gate, its
 * playback state, and the preview's press gesture.
 */
class BeforeAfterTest {

    // ---- canHold gate ----

    @Test
    fun hold_allowed_when_paused_with_frame_and_engine() {
        assertTrue(
            BeforeAfter.canHold(isPlaying = false, hasFrame = true, canRender = true)
        )
    }

    @Test
    fun hold_allowed_while_playing_too() {
        // Owner request 2026-11-11: the hold works during playback as
        // well — the screen pauses the wall clock first, so the loop's
        // continuous graded offers stop and the neutral render is never
        // superseded. isPlaying no longer gates the hold.
        assertTrue(
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

    // ---- hold start transitions (owner request 2026-11-11) ----

    @Test
    fun playing_hold_pauses_and_shows_the_original() {
        // Long-press while playing: pause the wall clock (video + audio
        // key on isPlaying), then render the neutral frame at the current
        // position.
        assertEquals(
            BeforeAfter.HoldStart.PauseAndShowBefore,
            BeforeAfter.onHoldStart(
                isPlaying = true, hasFrame = true, canRender = true,
                grade = GradeK(exposureEv = 0.5f),
            ),
        )
    }

    @Test
    fun paused_hold_shows_the_original_without_pausing_again() {
        // Already paused: just the neutral render, no playback state to
        // save or restore.
        assertEquals(
            BeforeAfter.HoldStart.ShowBefore,
            BeforeAfter.onHoldStart(
                isPlaying = false, hasFrame = true, canRender = true,
                grade = GradeK(exposureEv = 0.5f),
            ),
        )
    }

    @Test
    fun neutral_grade_rejects_the_hold_even_while_playing() {
        // An already-neutral grade skips the pause too: holding would show
        // the exact frame the loop is already showing, so pausing would
        // only stall the video for no visible difference.
        assertEquals(
            BeforeAfter.HoldStart.NoHold,
            BeforeAfter.onHoldStart(
                isPlaying = true, hasFrame = true, canRender = true,
                grade = GradeK(),
            ),
        )
    }

    @Test
    fun no_frame_rejects_the_hold_start() {
        assertEquals(
            BeforeAfter.HoldStart.NoHold,
            BeforeAfter.onHoldStart(
                isPlaying = false, hasFrame = false, canRender = true,
                grade = GradeK(exposureEv = 0.5f),
            ),
        )
    }

    // ---- hold release transitions ----

    @Test
    fun release_after_a_playing_hold_resumes_playback() {
        // The hold had paused playback: restore the graded frame AND
        // resume from the held position (the existing play-from-position
        // logic).
        assertEquals(
            BeforeAfter.HoldRelease.ShowAfterAndResume,
            BeforeAfter.onHoldRelease(
                holdPausedPlayback = true, heldRenderIssued = true,
            ),
        )
    }

    @Test
    fun release_after_a_paused_hold_keeps_playback_paused() {
        // Playback was paused before the hold: the release only restores
        // the grade; the transport state is untouched.
        assertEquals(
            BeforeAfter.HoldRelease.ShowAfter,
            BeforeAfter.onHoldRelease(
                holdPausedPlayback = false, heldRenderIssued = true,
            ),
        )
    }

    @Test
    fun release_without_a_hold_does_nothing() {
        // The hold never engaged (no frame / no engine / neutral grade):
        // a release must not issue a render its press never issued, and
        // must not resume a playback it never paused.
        assertEquals(
            BeforeAfter.HoldRelease.Nothing,
            BeforeAfter.onHoldRelease(
                holdPausedPlayback = true, heldRenderIssued = false,
            ),
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
