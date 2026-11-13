package com.ritim.ritimcraw.ui.timeline

import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineOps
import com.ritim.ritimcraw.data.timeline.TimelineOps.TrimEdge
import com.ritim.ritimcraw.data.timeline.TimelineProject
import kotlin.math.roundToInt

/**
 * The timeline UI's pure geometry + gesture rules (Stage C UI, spec
 * TL-03/04/05/07): everything pixel↔frame that must be exact, extracted
 * from the composables so it is JVM-testable headless (the mission's
 * test rule — composables are owner-verified visually, the RULES are
 * tested). No Android imports.
 *
 * SCALE CHOICE (the mission's documented decision): v1 uses ONE FIXED
 * pixels-per-frame scale — no zoom, no fit-to-width. A fixed scale makes
 * every mapping in this file a pure multiply/divide with no recompose
 * feedback loop (a fit-to-width scale would change as clips are added,
 * re-laying-out mid-gesture). The value lives in the composable as a
 * constant; this file takes it as a parameter everywhere, so the rules
 * stay independent of the choice.
 *
 * ROUNDING RULE: the model's timebase is whole FRAMES (Doubles that
 * land on the frame grid — see TimelineModel's TIMEBASE CONTRACT);
 * gestures land on whole PIXELS. [pxToFrame] and [dragDeltaFrames]
 * round through roundToInt, whose ties go toward the LATER frame
 * (+infinity), so a frame→px→frame round-trip returns the same frame
 * (locked by test) — a drag that ends where it started produces a
 * no-op edit, and the model's shrink-only trim guard sees positions it
 * can compare exactly.
 *
 * TWO GRIDS, ONE DELTA (the moved-clip rule): a clip's sourceRange and
 * timelineRange share one timebase but NOT one origin — after a move,
 * timelineRange.start != sourceRange.start. Gestures happen in
 * TIMELINE space (the screen), the model's trim positions are SOURCE
 * positions (TimelineOps.trim guards against sourceRange). The drag
 * functions here take a whole-frame DELTA and add it to the clip's own
 * current position, so the two grids never need a conversion — the
 * same delta is legal in both, exactly the model's trim contract
 * ("both edges move by the same delta").
 */
object TimelineGeometry {

    /** Frames → px. A frame maps to a continuous px offset. */
    fun frameToPx(frame: Double, pxPerFrame: Float): Float =
        (frame * pxPerFrame).toFloat()

    /**
     * Px → frames, rounded to the WHOLE frame the pixel sits in (the
     * nearest frame line, ties toward the later frame). The inverse of
     * [frameToPx] at frame lines: round-trips exactly.
     */
    fun pxToFrame(px: Float, pxPerFrame: Float): Double =
        (px / pxPerFrame).roundToInt().toDouble()

    /**
     * A drag's ACCUMULATED px → the whole-frame delta it means. Drags
     * accumulate deltas (not absolute positions), so this is the px→
     * frame rule applied to a delta: the same rounding, the same tie
     * behavior, and a zero-px drag is a 0-frame delta (a no-op).
     */
    fun dragDeltaFrames(deltaPx: Float, pxPerFrame: Float): Double =
        (deltaPx / pxPerFrame).roundToInt().toDouble()

    /**
     * A move drag's new timeline start (TL-03): the clip's current start
     * moved by the whole-frame delta, snapped to the project's edit
     * points (TL-07 — [candidates] is TimelineOps.snapCandidates of the
     * PRE-DRAG project, so the snap targets stay stable through the
     * gesture), kept >= 0 (the frame axis starts at frame 0). The same
     * function serves the mid-drag PREVIEW and the drop COMMIT — the
     * preview is exactly what the drop does, by construction.
     */
    fun moveDragStart(
        clip: TimelineClip,
        deltaFrames: Double,
        candidates: List<Double>,
        thresholdFrames: Double,
    ): Double =
        TimelineOps.snap(
            clip.timelineRange.start + deltaFrames,
            candidates,
            thresholdFrames,
        ).coerceAtLeast(0.0)

    /**
     * A trim drag's model position (TL-04): the edge's current SOURCE
     * position moved by the whole-frame delta, mapped to what the
     * model's shrink-only guard accepts. The rules, honestly:
     *   - a drag INTO the clip (a shrink) is the rounded position,
     *     clamped to the far edge's strictly-inside line (an over-drag
     *     past the far edge shrinks to the minimum 1-frame clip, the
     *     max legal shrink — the preview tracks the drag 1:1 until the
     *     boundary, no snap-back surprise);
     *   - a drag OUT of the clip (an EXTEND) has no legal meaning —
     *     the model refuses extending trims, so the honest preview is
     *     NO movement: the function returns the edge's CURRENT position,
     *     which the model also refuses (not strictly inside), so the
     *     commit is the history's recorded no-op;
     *   - null only when the clip has no legal trim at all (shorter
     *     than 2 frames — no strictly-inside frame line exists): the
     *     caller applies nothing.
     */
    fun trimDragPosition(
        clip: TimelineClip,
        edge: TrimEdge,
        deltaFrames: Double,
    ): Double? {
        val range = clip.sourceRange
        // coerceIn throws when its window is empty (min > max) — the
        // sub-2-frame clip is checked BEFORE the call.
        if (range.end - range.start < 2.0) return null
        val current = when (edge) {
            TrimEdge.START -> range.start
            TrimEdge.END -> range.end
        }
        val target = current + deltaFrames
        val extending = when (edge) {
            TrimEdge.START -> target <= range.start
            TrimEdge.END -> target >= range.end
        }
        if (extending) return current
        return target.coerceIn(range.start + 1.0, range.end - 1.0)
    }

    /**
     * The operation row's enable rules (TL-03/04/05): which actions a
     * selection + playhead state makes legal. Pure booleans, tested:
     *   - Split needs a selected clip AND a playhead strictly inside
     *     its timelineRange (the model refuses an edge/outside split —
     *     TimelineOps.split returns null — so the button says so).
     *   - Duplicate/Remove need only a selection (the trim gesture
     *     itself re-validates every drag frame through
     *     [trimDragPosition]).
     */
    fun canSplit(clip: TimelineClip?, playheadFrame: Double): Boolean {
        val c = clip ?: return false
        return playheadFrame > c.timelineRange.start && playheadFrame < c.timelineRange.end
    }

    fun canEdit(clip: TimelineClip?): Boolean = clip != null

    /**
     * The append position for a NEW clip on a track: after the last
     * clip on that track (0 for an empty lane) — the standard editing-
     * timeline append, so Add never overlaps existing content.
     */
    fun appendStart(project: TimelineProject, trackId: String): Double =
        project.clips
            .filter { it.trackId == trackId }
            .maxOfOrNull { it.timelineRange.end }
            ?: 0.0

    /**
     * The duration a NEW clip gets: the source's real frame count when
     * the stats pass has probed it, else the placeholder below. The
     * placeholder is honest about being a placeholder: the owner can
     * trim/split it like any clip, and when the device probe lands the
     * real count replaces it at the source's next add.
     */
    const val UNKNOWN_DURATION_FRAMES = 90.0

    fun addDuration(sourceFrameCount: Int): Double =
        if (sourceFrameCount > 0) sourceFrameCount.toDouble() else UNKNOWN_DURATION_FRAMES

    /**
     * The ruler's tick plan (TL-17): major ticks every [majorStepFrames]
     * with their labels, minor ticks every major/2. The ruler spans
     * 0..resolveLength(project) — the model's own length rule, never a
     * UI guess.
     */
    data class RulerTick(val frame: Double, val major: Boolean)

    fun rulerTicks(lengthFrames: Double, majorStepFrames: Int): List<RulerTick> {
        if (lengthFrames <= 0.0 || majorStepFrames <= 0) return emptyList()
        val ticks = ArrayList<RulerTick>()
        val half = majorStepFrames / 2.0
        var f = 0.0
        while (f <= lengthFrames) {
            ticks.add(RulerTick(f, major = true))
            if (f + half <= lengthFrames) {
                ticks.add(RulerTick(f + half, major = false))
            }
            f += majorStepFrames
        }
        return ticks
    }

    /**
     * The major tick step for a ruler at [pxPerFrame]: the smallest
     * step from the 1-2-5 decade ladder whose major ticks are at least
     * [minMajorPx] apart on screen (the caller passes its measured
     * label width, so labels never collide). The ladder's steps stay
     * round numbers a user can read at a glance.
     */
    private val MAJOR_LADDER = intArrayOf(
        1, 2, 5, 10, 20, 50, 100, 200, 500, 1000, 2000, 5000, 10000,
    )

    fun majorStepFor(pxPerFrame: Float, minMajorPx: Float = 64f): Int {
        for (step in MAJOR_LADDER) {
            if (frameToPx(step.toDouble(), pxPerFrame) >= minMajorPx) return step
        }
        return MAJOR_LADDER.last()
    }

    /**
     * The clips of one track, ordered left→right by timelineRange.start
     * (the model's clips list is encounter order; the TimelineProject
     * KDoc's own display rule).
     */
    fun trackClips(project: TimelineProject, trackId: String): List<TimelineClip> =
        project.clips
            .filter { it.trackId == trackId }
            .sortedBy { it.timelineRange.start }
}
