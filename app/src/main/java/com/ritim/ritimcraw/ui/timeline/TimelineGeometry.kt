package com.ritim.ritimcraw.ui.timeline

import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineEdit
import com.ritim.ritimcraw.data.timeline.TimelineOps
import com.ritim.ritimcraw.data.timeline.TimelineOps.TrimEdge
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.util.Format
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
     * The edit a move drag commits (TL-03) — or null when the drag
     * moved nothing. The guard is load-bearing: TimelineOps.move
     * ALWAYS allocates a new project instance (even for a same-
     * position move), so the history's applied === before no-op rule
     * cannot catch a drop-at-origin — without this check a zero
     * drag would record a bogus undo step. Null here means the
     * gesture commits nothing, honestly.
     */
    fun moveEdit(
        clip: TimelineClip,
        newTimelineStart: Double,
    ): TimelineEdit? =
        if (newTimelineStart == clip.timelineRange.start) null
        else TimelineEdit.MoveClip(clip.id, newTimelineStart)

    /**
     * The edit a trim drag commits (TL-04) — or null when there is
     * nothing to commit: null position (sub-2-frame clip) or the
     * EXTEND FREEZE (position equals the edge's current position).
     * The freeze is the model's refused trim; committing nothing is
     * the same end state as committing the refused edit (the history
     * records no either way) minus the pointless apply — and it keeps
     * the rule testable: a freeze NEVER creates an undo step.
     */
    fun trimEdit(
        clip: TimelineClip,
        edge: TrimEdge,
        position: Double?,
    ): TimelineEdit? {
        if (position == null) return null
        val current = when (edge) {
            TrimEdge.START -> clip.sourceRange.start
            TrimEdge.END -> clip.sourceRange.end
        }
        if (position == current) return null
        return TimelineEdit.TrimClip(clip.id, edge, position)
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
     * The label precision a major tick carries (owner device feedback
     * 2026-11-13: "saniye salieseler birbirine karışıyor, kötü görüntü"
     * — the full-timecode labels crowd into an unreadable mess at dense
     * scales). The 1-2-5 ladder already picks the step; the LABEL drops
     * the fields the step cannot resolve:
     *
     *  - [LabelFields.FULL] — HH:MM:SS:FF, the fixed 4-field timecode:
     *    only when the timeline itself runs an hour or longer (any
     *    shorter regime wraps at the hour — Format's 2-field MM is
     *    minutes-within-hour, so "01:00" would mark both the 1-minute
     *    and the 61-minute major — ambiguity, not clutter).
     *  - [LabelFields.MM_SS] — MM:SS: the default for sub-hour
     *    timelines at steps of a second and up — the salise (frame)
     *    field is pure noise at this density, and the wall-clock
     *    reading is what an editor scrubs by.
     *  - [LabelFields.FRAMES] — the bare FRAME NUMBER on the project
     *    grid, for sub-second steps (a high-fps clip at a dense step):
     *    the second is not the step, so the moving digits — frames —
     *    are the label. Frame numbers never wrap, so they stay
     *    unambiguous at any length; the colon-less shape is its own
     *    visual distinction from the timecode regimes.
     *
     * The regime is a function of the STEP and the ruler's SPAN, never
     * of the individual tick: one ruler never mixes label SHAPES (a
     * colon-less frame count never sits beside an MM:SS), and tnum
     * keeps the digits from reflowing within a shape (DESIGN.md
     * Typography). A FRAMES label still grows a digit at each decade
     * boundary (99 -> 100) — rare, gradual, and dwarfed by the
     * label-spacing gutter.
     */
    enum class LabelFields { FRAMES, MM_SS, FULL }

    /**
     * The label regime for a ruler: [majorStepFrames] is the ladder's
     * step at [fps] (typically from [majorStepFor]),
     * [timelineLengthFrames] the ruler's span. Pure — the composable
     * renders exactly this shape; see [LabelFields] for the rationale.
     */
    fun labelFieldsFor(
        majorStepFrames: Int,
        fps: Int,
        timelineLengthFrames: Double = 0.0,
    ): LabelFields {
        val safeFps = if (fps <= 0) Format.DEFAULT_FPS else fps
        val stepSeconds = majorStepFrames.toDouble() / safeFps
        val timelineHours = timelineLengthFrames / safeFps / 3600.0
        return when {
            timelineHours >= 1.0 -> LabelFields.FULL
            stepSeconds < 1.0 -> LabelFields.FRAMES
            else -> LabelFields.MM_SS
        }
    }

    /**
     * The text of one major tick's label at [ptsUs] in the regime
     * [fields] (typically from [labelFieldsFor]) at [fps]:
     * MM_SS renders Format's 2-field adaptive timecode (MM:SS), FULL
     * the fixed 4-field HH:MM:SS:FF, FRAMES the bare frame number
     * (the frame ON the tick — the project-grid integer the ruler
     * ticks are actually drawn on, not a re-derived one, so a frame
     * line and its label can never disagree). Locale.ROOT and tnum
     * come from the house Format / the caller's style.
     */
    fun majorLabelText(
        ptsUs: Long,
        fps: Int,
        fields: LabelFields,
        tickFrame: Double = 0.0,
    ): String = when (fields) {
        LabelFields.MM_SS -> Format.adaptiveTimecode(ptsUs, fps, 2)
        LabelFields.FULL -> Format.formatTimecode(ptsUs, fps)
        LabelFields.FRAMES -> tickFrame.toLong().toString()
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
