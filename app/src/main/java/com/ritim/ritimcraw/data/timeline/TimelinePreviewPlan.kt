package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.MediaSource
import kotlin.math.roundToInt

/**
 * The timeline PREVIEW PLAN: the pure, JVM-testable position math the
 * timeline screen's preview walks. The EXPORT plan already decides the
 * frame recipe — which source frame each output frame shows, where the
 * black runs are ([TimelineExportPlan]) — so this plan REUSES that walk
 * verbatim and adds what playback needs on top: playhead frame ->
 * (clip, source frame) and back, on the project's own grid. Nothing
 * here opens a file, touches a codec, or imports Android; the screen
 * (ui/timeline) renders what this plan answers.
 *
 * WITHOUT SKIPS (the preview's honest rule): the export plan records
 * WHY a clip renders black (its skips list, for the export's stage
 * log); a preview has no stage log — a source that is missing or
 * UNKNOWN renders its span BLACK, the same frames the export renders
 * black. The preview never guesses a substitute and never hides the
 * span: WHAT YOU PREVIEW IS WHAT YOU EXPORT, frame for frame (locked
 * by test).
 *
 * TIMEBASE (TimelineModel's contract): the playhead is a frame number
 * on the PROJECT's grid; output frames are the same grid's integers.
 * [outputFrameOf] rounds a playhead to the output frame it sits on —
 * roundToInt, ties toward the LATER frame, the same rule the export
 * walk's output positions and TimelineGeometry.pxToFrame use, so the
 * ruler, the playhead, and the preview can never disagree about which
 * frame is current — and clamps to the movie's bounds: a playhead at
 * the very end HOLDS the last frame (the editor's end-of-clip rule),
 * and a playhead on an empty timeline answers black, never an index
 * past the end.
 *
 * PLAYBACK GRID: the movie's timebase is the project fps — output
 * frame i lands at i * 1_000_000 us / fps (the exporter's own pts
 * rule; the preview plays the same grid the muxer writes). [ptsUs]
 * hands that uniform grid to the playback loop as the LongArray the
 * editor's PtsTimeline pacing already consumes.
 *
 * V1 LIMITS (inherited from the reused walk, documented there): the
 * mapping is FRAME-NEAREST, no resampling; only the VIDEO track
 * renders; overlaps are not supported (the earlier clip wins).
 */
class TimelinePreviewPlan(
    /** The output frame grid's fps (TimelineOutput.fps, the timebase). */
    val fps: Int,
    /** Total output frames: ceil(resolveLength) — the preview's exact span. */
    val totalFrames: Int,
    /**
     * The frame recipe in OUTPUT ORDER — the EXPORT plan's own entries,
     * reused verbatim: contiguous Clip and Black runs covering
     * [0, totalFrames) exactly once (the export plan's locked contract;
     * this plan's lookups rely on it and never re-walk).
     */
    val entries: List<TimelineExportPlan.Entry>,
) {

    /**
     * What the preview shows at one output position: a clip's decoded
     * source frame, or black.
     */
    sealed interface Frame {

        /**
         * The clip whose source frame shows here: [sourceFrame] is the
         * already-rounded, already-clamped frame index the renderer
         * decodes (the export mapping's answer — the preview renders,
         * never recomputes). [clipId] joins back to the project (the
         * clip's own ranges, its linked audio); [sourceId] picks the
         * session to open.
         */
        data class Clip(
            val clipId: String,
            val sourceId: String,
            val sourceFrame: Int,
        ) : Frame

        /**
         * Black: a gap between clips, a gated-out clip's span (missing
         * source / UNKNOWN fps / UNKNOWN frame count — the file KDoc's
         * without-skips rule), or the tail past the last video frame.
         * A preview shows black the same way the export renders it
         * black: honestly, never by guessing a substitute.
         */
        object Black : Frame
    }

    /** The plan's answer at one position: the output frame and its content. */
    data class FrameAt(
        /** The output frame the playhead rounded to (the grid integer). */
        val outputFrame: Int,
        val frame: Frame,
    )

    /**
     * The output frame a playhead position sits on: round(), ties toward
     * the LATER frame (the file KDoc's timebase rule), clamped to the
     * movie's bounds — never past the last frame, never negative.
     */
    fun outputFrameOf(playheadFrame: Double): Int =
        playheadFrame.roundToInt().coerceIn(0, maxOf(0, totalFrames - 1))

    /**
     * The playhead position of an output frame — the grid identity
     * (output frames ARE project-grid frames). The documented inverse
     * of [outputFrameOf]: the two round-trip exactly on whole frames
     * (locked by test), so a playhead set from a frame and a frame
     * read from a playhead can never drift.
     */
    fun playheadOf(outputFrame: Int): Double = outputFrame.toDouble()

    /**
     * What the preview shows at output frame [outputFrame] (clamped to
     * the movie's bounds): the clip entry covering it answers the
     * source frame to decode; a black run (or an empty plan) answers
     * black. Binary search over the entries' contiguous starts — the
     * lookup is O(log n), cheap enough to call per playback tick.
     */
    fun atOutputFrame(outputFrame: Int): FrameAt {
        if (entries.isEmpty() || totalFrames <= 0) {
            return FrameAt(0, Frame.Black)
        }
        val f = outputFrame.coerceIn(0, totalFrames - 1)
        // The last entry whose outputStart <= f: entries are sorted and
        // contiguous from 0 (the export plan's contract), so the found
        // entry is the one covering f.
        var lo = 0
        var hi = entries.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (entries[mid].outputStart <= f) lo = mid else hi = mid - 1
        }
        val entry = entries[lo]
        val frame = when (entry) {
            is TimelineExportPlan.Entry.Clip -> Frame.Clip(
                clipId = entry.clipId,
                sourceId = entry.sourceId,
                sourceFrame = entry.sourceFrames[f - entry.outputStart],
            )
            is TimelineExportPlan.Entry.Black -> Frame.Black
        }
        return FrameAt(f, frame)
    }

    /**
     * What the preview shows at playhead position [playheadFrame]:
     * [outputFrameOf] then [atOutputFrame]. The playhead is a Double
     * only because the model's grid is Double; in practice it lands on
     * whole frames (scrub rounds, playback advances whole frames), and
     * the round here is exact for those.
     */
    fun atPlayhead(playheadFrame: Double): FrameAt =
        atOutputFrame(outputFrameOf(playheadFrame))

    /**
     * The uniform playback grid: output frame i at i * 1_000_000 us /
     * fps — the exporter's pts rule, handed to the playback loop in
     * the LongArray shape the editor's PtsTimeline pacing consumes
     * (frameAt / elapsedAt / delayUntilNextUs all take one). Monotone
     * by construction; an empty plan answers an empty array.
     */
    fun ptsUs(): LongArray =
        LongArray(totalFrames) { i -> i.toLong() * 1_000_000L / fps }

    companion object {

        /**
         * Builds the preview plan for [project]'s VIDEO track — the
         * same walk, the same honest gates, the same total as
         * [TimelineExportPlan.build], reused verbatim so preview and
         * export can never disagree (locked by test); see that plan's
         * KDoc for the mapping and gate rules, this file's for the
         * position math. The same build contract holds too: the project
         * fps must be a usable timebase and the output a usable size
         * (the export plan's requires).
         *
         * @param sources the Library's sources by id — presence here is
         *   the SOURCE_MISSING gate.
         * @param fpsMeasured the ONLY honest source rate (null = UNKNOWN
         *   gates the clip's span to black, never a guess).
         * @param frameCount the source's REAL frame count, or null when
         *   unknown (the mapping clamps to it).
         */
        fun build(
            project: TimelineProject,
            sources: Map<String, MediaSource>,
            fpsMeasured: (String) -> Double?,
            frameCount: (String) -> Int?,
        ): TimelinePreviewPlan {
            val export = TimelineExportPlan.build(project, sources, fpsMeasured, frameCount)
            return TimelinePreviewPlan(
                fps = export.fps,
                totalFrames = export.totalFrames,
                entries = export.entries,
            )
        }
    }
}
