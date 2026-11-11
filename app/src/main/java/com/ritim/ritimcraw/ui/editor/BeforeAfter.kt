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
 * Paused-only guard (the documented choice): while PLAYING, the playback
 * loop continuously offers graded renders through the same gate, so a
 * neutral hold render would be superseded within one frame and the two
 * features would fight for the pipeline. The hold is therefore ignored
 * during playback; if playback starts mid-hold, playback wins
 * (newest-wins) and the release render restores the grade — never a
 * corrupt state, never a backlog.
 *
 * The hold render runs at the paused tier through the session's CACHED
 * path (the frame on screen was just rendered at the same index, so the
 * decode is reused — a grade-only swap costs no new decode).
 */
object BeforeAfter {

    /**
     * True when a press-hold may start: paused, a frame on screen to
     * compare against, and the render path runnable. Playing clips are
     * excluded per the object KDoc; without a frame or without the engine
     * there is nothing to compare and nothing to render.
     */
    fun canHold(isPlaying: Boolean, hasFrame: Boolean, canRender: Boolean): Boolean =
        !isPlaying && hasFrame && canRender

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
     * issued) and the pipeline stays idle.
     */
    fun holdChangesPicture(current: GradeK): Boolean = current != GradeK()
}
