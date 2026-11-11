package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK

/**
 * Pure before/after press-hold decisions (owner feature list W5),
 * JVM-testable without Compose: press-and-hold on the preview swaps the
 * rendered grade to the ORIGINAL (neutral) grade for the CURRENT frame;
 * release re-renders with the actual grade. Both renders go through the
 * screen's existing render gate (newest-wins, monotonic publication), so
 * the feature cannot corrupt the drag-tier/settle pipeline — a release
 * always re-issues the graded render, and any newer request (a seek, a
 * slider settle) simply supersedes the hold's by sequence.
 *
 * During playback too (owner request 2026-11-11): a hold while PLAYING
 * first pauses the wall clock ([HoldStart.PauseAndShowBefore] — the
 * screen flips isPlaying to false, which the playback loop and the audio
 * transport both key on, so video and sound stop cleanly at the current
 * position; the loop is NOT fought frame-by-frame), then renders the
 * neutral frame at the paused tier through the cached path. Release
 * re-renders the graded frame and resumes playback from that position
 * ([HoldRelease.ShowAfterAndResume] — the screen's existing
 * play-from-position logic). A finger DRAG during the hold cancels it
 * through the same end path as release: a moving finger is a seek
 * gesture, not a compare. If playback starts some other way mid-hold
 * (the transport's play button), playback wins by sequence and the
 * release render merely restores the grade.
 *
 * The hold render runs at the paused tier through the session's CACHED
 * path (the frame on screen was just rendered at the same index, so the
 * decode is reused — a grade-only swap costs no new decode).
 */
object BeforeAfter {

    /**
     * What a hold START does. Pure: the screen branches on this and owns
     * the actual state writes.
     */
    sealed interface HoldStart {

        /** Pause the wall clock (playback was running) and render the neutral frame. */
        data object PauseAndShowBefore : HoldStart

        /** Render the neutral frame; playback was already paused. */
        data object ShowBefore : HoldStart

        /** No hold: nothing to compare against, nothing to render, or the grade is already neutral. */
        data object NoHold : HoldStart
    }

    /**
     * What a hold RELEASE (or drag-cancel) does. Pure: the screen branches
     * on this and owns the actual state writes.
     */
    sealed interface HoldRelease {

        /** Re-render the graded frame, then resume playback (the hold had paused it). */
        data object ShowAfterAndResume : HoldRelease

        /** Re-render the graded frame; playback stays paused (it was paused before the hold). */
        data object ShowAfter : HoldRelease

        /** The hold never engaged; nothing to restore. */
        data object Nothing : HoldRelease
    }

    /**
     * True when a press-hold may start: a frame on screen to compare
     * against and the render path runnable. Works PAUSED and PLAYING
     * (owner request 2026-11-11): during playback the hold pauses the
     * wall clock first, so the loop's continuous graded offers stop and
     * the neutral render is never superseded. [isPlaying] stays in the
     * signature (the caller always has it) but no longer gates — the
     * playing case is [HoldStart.PauseAndShowBefore], not a rejection.
     */
    fun canHold(isPlaying: Boolean, hasFrame: Boolean, canRender: Boolean): Boolean =
        hasFrame && canRender

    /**
     * The transition for a hold START given the current state: rejected
     * (no frame / no engine / already-neutral grade), engage-while-playing
     * (pause the wall clock, then show the original), or engage-while-paused
     * (just show the original).
     */
    fun onHoldStart(
        isPlaying: Boolean,
        hasFrame: Boolean,
        canRender: Boolean,
        grade: GradeK,
    ): HoldStart = when {
        !canHold(isPlaying, hasFrame, canRender) || !holdChangesPicture(grade) ->
            HoldStart.NoHold
        isPlaying -> HoldStart.PauseAndShowBefore
        else -> HoldStart.ShowBefore
    }

    /**
     * The transition for a hold RELEASE (or drag-cancel) given the hold's
     * own bookkeeping: [heldRenderIssued] is whether the press actually
     * issued the neutral render (an already-neutral grade skipped it), and
     * [holdPausedPlayback] is whether the press paused playback (it was
     * playing when the hold started). A hold that never engaged releases
     * to [Nothing]; one that paused playback restores the grade AND
     * resumes from the held position.
     */
    fun onHoldRelease(
        holdPausedPlayback: Boolean,
        heldRenderIssued: Boolean,
    ): HoldRelease = when {
        !heldRenderIssued -> HoldRelease.Nothing
        holdPausedPlayback -> HoldRelease.ShowAfterAndResume
        else -> HoldRelease.ShowAfter
    }

    /**
     * The grade rendered while held: the ORIGINAL/neutral grade. A
     * default-constructed [GradeK] is the no-op against the ungraded
     * pipeline (as-shot WB, zero tone controls, no LUT, no denoise), which
     * IS the clip as the camera recorded it.
     */
    fun holdGrade(): GradeK = GradeK()

    /**
     * True when holding would change the picture at all: with an already
     * neutral grade the hold would render the identical frame, so the
     * screen skips the hold render (and, symmetrically, the release
     * render — its held flag only ever sets when a hold render was
     * issued) and the pipeline stays idle. During playback a neutral
     * grade also skips the pause: holding would show the exact frame the
     * loop is already showing, so pausing would only stall the video for
     * no visible difference.
     */
    fun holdChangesPicture(current: GradeK): Boolean = current != GradeK()
}
