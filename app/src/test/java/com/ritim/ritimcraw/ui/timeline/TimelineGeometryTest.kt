package com.ritim.ritimcraw.ui.timeline

import com.ritim.ritimcraw.data.timeline.FrameRange
import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineEdit
import com.ritim.ritimcraw.data.timeline.TimelineHistory
import com.ritim.ritimcraw.data.timeline.TimelineOps
import com.ritim.ritimcraw.data.timeline.TimelineOps.TrimEdge
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.data.timeline.TimelineTrack
import com.ritim.ritimcraw.data.timeline.TrackKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timeline UI's pure gesture/geometry rules (TimelineGeometry):
 * frame↔px round-trips, drag-delta rounding, snap-drag rounding,
 * trim-drag clamping against the model's shrink-only guard (including
 * the extend freeze), operation-row enable rules, the append position,
 * and the ruler tick plan. JVM-headless — the composables that call
 * these are owner-verified visually.
 */
class TimelineGeometryTest {

    private val pxPerFrame = 4f

    private fun clip(
        id: String = "c1",
        start: Double = 0.0,
        end: Double = 90.0,
        trackId: String = "v",
        timelineStart: Double = start,
    ) = TimelineClip(
        id = id,
        trackId = trackId,
        sourceId = "src-1",
        sourceRange = FrameRange(start, end),
        timelineRange = FrameRange(timelineStart, timelineStart + (end - start)),
    )

    private fun project(vararg clips: TimelineClip): TimelineProject =
        TimelineProject(
            tracks = listOf(
                TimelineTrack("v", TrackKind.VIDEO, 0),
                TimelineTrack("a", TrackKind.AUDIO, 1),
            ),
            clips = clips.toList(),
        )

    // ---- frame ↔ px mapping ----

    @Test
    fun frame_to_px_is_linear() {
        assertEquals(0f, TimelineGeometry.frameToPx(0.0, pxPerFrame), 0f)
        assertEquals(40f, TimelineGeometry.frameToPx(10.0, pxPerFrame), 0f)
        assertEquals(360f, TimelineGeometry.frameToPx(90.0, pxPerFrame), 0f)
    }

    @Test
    fun px_to_frame_rounds_to_nearest_frame_line() {
        assertEquals(10.0, TimelineGeometry.pxToFrame(40f, pxPerFrame), 0.0)
        // 42px sits between frames 10 and 11 — 42/4 = 10.5 rounds to 11.
        assertEquals(11.0, TimelineGeometry.pxToFrame(42f, pxPerFrame), 0.0)
        // 37px = 9.25 frames — rounds to 9.
        assertEquals(9.0, TimelineGeometry.pxToFrame(37f, pxPerFrame), 0.0)
    }

    @Test
    fun frame_px_round_trip_returns_the_same_frame() {
        // The load-bearing round-trip: a whole frame maps to a px that
        // maps back to the SAME frame — a drag that ends where it
        // started produces a no-op edit, and the model compares
        // positions exactly.
        for (f in listOf(0.0, 1.0, 7.0, 90.0, 1234.0)) {
            val px = TimelineGeometry.frameToPx(f, pxPerFrame)
            assertEquals(f, TimelineGeometry.pxToFrame(px, pxPerFrame), 0.0)
        }
    }

    @Test
    fun drag_delta_rounds_to_whole_frames() {
        // 10px = 2.5 frames — roundToInt's tie rule is toward
        // +infinity (the LATER frame, the documented rule), so 2.5 -> 3
        // and -2.5 -> -2: one rule, applied to deltas.
        assertEquals(3.0, TimelineGeometry.dragDeltaFrames(10f, pxPerFrame), 0.0)
        assertEquals(-2.0, TimelineGeometry.dragDeltaFrames(-10f, pxPerFrame), 0.0)
        // A zero-px drag is a 0-frame delta — the no-op gesture.
        assertEquals(0.0, TimelineGeometry.dragDeltaFrames(0f, pxPerFrame), 0.0)
        // 6px = 1.5 -> 2 (tie, later); 5px = 1.25 -> 1.
        assertEquals(2.0, TimelineGeometry.dragDeltaFrames(6f, pxPerFrame), 0.0)
        assertEquals(1.0, TimelineGeometry.dragDeltaFrames(5f, pxPerFrame), 0.0)
    }

    // ---- move drag (TL-03 + TL-07 snap) ----

    @Test
    fun move_drag_snaps_to_nearby_clip_edge() {
        // Clip edge at 90; dragging to ~89 snaps to 90 within the
        // 2-frame threshold.
        val c = clip(start = 0.0, end = 90.0)
        val p = project(c)
        val candidates = TimelineOps.snapCandidates(p)
        val start = TimelineGeometry.moveDragStart(c, 89.0 - c.timelineRange.start, candidates, 2.0)
        assertEquals(90.0, start, 0.0)
    }

    @Test
    fun move_drag_outside_threshold_keeps_the_rounded_position() {
        val c = clip(start = 0.0, end = 90.0)
        val p = project(c)
        val candidates = TimelineOps.snapCandidates(p)
        val start = TimelineGeometry.moveDragStart(c, 200.0 - c.timelineRange.start, candidates, 2.0)
        assertEquals(200.0, start, 0.0)
    }

    @Test
    fun move_drag_clamps_to_frame_zero() {
        // A drag far left of the axis start: the move clamps at 0
        // (snapping can pull NEGATIVE otherwise).
        val c = clip(start = 0.0, end = 90.0)
        val p = project(c)
        val candidates = TimelineOps.snapCandidates(p)
        val start = TimelineGeometry.moveDragStart(c, -30.0, candidates, 2.0)
        assertEquals(0.0, start, 0.0)
    }

    @Test
    fun move_drag_of_a_moved_clip_stays_timeline_relative() {
        // TWO GRIDS, ONE DELTA: a clip moved so its timeline start (50)
        // no longer equals its source start (0). The drag delta applies
        // to the TIMELINE start; the source range is untouched.
        val c = clip(start = 0.0, end = 90.0, timelineStart = 50.0)
        val p = project(c)
        val candidates = TimelineOps.snapCandidates(p)
        val start = TimelineGeometry.moveDragStart(c, 10.0, candidates, 2.0)
        assertEquals(60.0, start, 0.0)
        // The candidates include the clip's OWN edges (50, 140) — a
        // 11-frame drag lands at 61, outside the threshold of both.
        val far = TimelineGeometry.moveDragStart(c, 11.0, candidates, 2.0)
        assertEquals(61.0, far, 0.0)
    }

    // ---- trim drag (TL-04, shrink-only + extend freeze) ----

    @Test
    fun trim_drag_into_the_clip_returns_the_moved_position() {
        val c = clip(start = 0.0, end = 90.0)
        // Dragging the START 25 frames right: new source start 25.
        assertEquals(25.0, TimelineGeometry.trimDragPosition(c, TrimEdge.START, 25.0)!!, 0.0)
        // Dragging the END 15 frames left: new source end 75.
        assertEquals(75.0, TimelineGeometry.trimDragPosition(c, TrimEdge.END, -15.0)!!, 0.0)
    }

    @Test
    fun trim_drag_over_shrink_clamps_to_the_minimum_clip() {
        val c = clip(start = 0.0, end = 90.0)
        // Dragging the START 200 frames right (way past the end 90):
        // clamped to end-1 = 89 — the max legal shrink (1-frame clip),
        // never a snap-back to the far edge.
        assertEquals(89.0, TimelineGeometry.trimDragPosition(c, TrimEdge.START, 200.0)!!, 0.0)
        // Dragging the END 200 frames left: clamped to start+1 = 1.
        assertEquals(1.0, TimelineGeometry.trimDragPosition(c, TrimEdge.END, -200.0)!!, 0.0)
    }

    @Test
    fun trim_drag_out_of_the_clip_freezes_at_the_current_position() {
        // The EXTEND freeze: the model refuses extending trims, so the
        // honest preview is no movement — the function returns the
        // edge's CURRENT position (which the model also refuses), and
        // the commit is the history's recorded no-op.
        val c = clip(start = 10.0, end = 100.0)
        assertEquals(10.0, TimelineGeometry.trimDragPosition(c, TrimEdge.START, -5.0)!!, 0.0)
        assertEquals(100.0, TimelineGeometry.trimDragPosition(c, TrimEdge.END, 5.0)!!, 0.0)
        // A zero delta is also "not strictly inside" — same freeze.
        assertEquals(10.0, TimelineGeometry.trimDragPosition(c, TrimEdge.START, 0.0)!!, 0.0)
    }

    @Test
    fun trim_drag_on_a_sub_two_frame_clip_is_null() {
        // A 2-frame clip still has its one legal line per edge; a
        // 1-frame clip has none — the drag returns null and the UI
        // applies nothing.
        val one = clip(start = 10.0, end = 11.0)
        assertNull(TimelineGeometry.trimDragPosition(one, TrimEdge.START, 5.0))
        assertNull(TimelineGeometry.trimDragPosition(one, TrimEdge.END, -5.0))
        val two = clip(start = 10.0, end = 12.0)
        assertEquals(11.0, TimelineGeometry.trimDragPosition(two, TrimEdge.START, 5.0)!!, 0.0)
    }

    @Test
    fun trim_drag_positions_are_always_accepted_by_the_model() {
        // The contract between this file and the model: every non-null
        // SHRINK position is one TimelineOps.trim ACCEPTS; every freeze
        // position is one trim REFUSES (the no-op commit) — the UI
        // never offers a position the model would silently reinterpret.
        val c = clip(start = 10.0, end = 100.0)
        val p = project(c)
        for (delta in listOf(-100.0, -5.0, 0.0, 5.0, 41.0, 200.0)) {
            val start = TimelineGeometry.trimDragPosition(c, TrimEdge.START, delta)!!
            val startAccepted = TimelineOps.trim(p, c.id, TrimEdge.START, start) != null
            // START moves right = shrink = accepted; the freeze (start
            // == range.start) is refused.
            assertEquals(delta > 0.0, startAccepted)
            val end = TimelineGeometry.trimDragPosition(c, TrimEdge.END, delta)!!
            val endAccepted = TimelineOps.trim(p, c.id, TrimEdge.END, end) != null
            // END moves left = shrink = accepted; the freeze refused.
            assertEquals(delta < 0.0, endAccepted)
        }
    }

    @Test
    fun a_refused_trim_is_a_no_op_in_the_history() {
        // The honest-refusal rule end to end: a trim the model refuses
        // (position ON the edge — not strictly inside) applies as the
        // SAME project, so TimelineHistory records nothing and the
        // redo stack survives.
        val c = clip(start = 10.0, end = 100.0)
        val p = project(c)
        val history = TimelineHistory(p)
        val applied = history.apply(TimelineEdit.TrimClip(c.id, TrimEdge.START, 10.0))
        assertTrue(applied === history.project)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    // ---- the edits a drag commits (the no-op guards) ----

    @Test
    fun move_edit_is_null_when_the_drag_moved_nothing() {
        // The load-bearing guard: TimelineOps.move ALWAYS allocates a
        // new project (even for a same-position move), so the
        // history's applied === before no-op rule CANNOT catch a
        // drop-at-origin — moveEdit must. Without it a zero drag
        // would record a bogus undo step.
        val c = clip(start = 0.0, end = 90.0, timelineStart = 50.0)
        assertNull(TimelineGeometry.moveEdit(c, 50.0))
        // A real move is the MoveClip edit for the new timeline start.
        val edit = TimelineGeometry.moveEdit(c, 75.0)
        assertEquals(TimelineEdit.MoveClip(c.id, 75.0), edit)
    }

    @Test
    fun trim_edit_is_null_for_freezes_and_untrimmable_clips() {
        val c = clip(start = 10.0, end = 100.0)
        // The EXTEND freeze (position == the edge's current position):
        // the model refuses it, so committing nothing is the same end
        // state minus the pointless apply — a freeze NEVER creates an
        // undo step.
        assertNull(TimelineGeometry.trimEdit(c, TrimEdge.START, 10.0))
        assertNull(TimelineGeometry.trimEdit(c, TrimEdge.END, 100.0))
        // A sub-2-frame clip has no legal trim: null position in,
        // null edit out.
        val one = clip(start = 10.0, end = 11.0)
        assertNull(TimelineGeometry.trimEdit(one, TrimEdge.END, null))
        // A real shrink is the TrimClip edit for the new position.
        assertEquals(
            TimelineEdit.TrimClip(c.id, TrimEdge.END, 75.0),
            TimelineGeometry.trimEdit(c, TrimEdge.END, 75.0),
        )
        assertEquals(
            TimelineEdit.TrimClip(c.id, TrimEdge.START, 25.0),
            TimelineGeometry.trimEdit(c, TrimEdge.START, 25.0),
        )
    }

    // ---- operation-row enable rules ----

    @Test
    fun split_needs_a_clip_and_a_strictly_inside_playhead() {
        val c = clip(start = 10.0, end = 100.0)
        assertTrue(TimelineGeometry.canSplit(c, 50.0))
        // On the edges and outside: the model refuses (TimelineOps.split
        // returns null), so the button disables.
        assertFalse(TimelineGeometry.canSplit(c, 10.0))
        assertFalse(TimelineGeometry.canSplit(c, 100.0))
        assertFalse(TimelineGeometry.canSplit(c, 5.0))
        assertFalse(TimelineGeometry.canSplit(c, 105.0))
        assertFalse(TimelineGeometry.canSplit(null, 50.0))
    }

    @Test
    fun edit_actions_need_only_a_selection() {
        val c = clip()
        assertTrue(TimelineGeometry.canEdit(c))
        assertFalse(TimelineGeometry.canEdit(null))
    }

    // ---- append position + add duration (the insert rule) ----

    @Test
    fun append_start_is_after_the_last_clip_on_the_track() {
        val a = clip("a", start = 0.0, end = 90.0)
        val b = clip("b", start = 0.0, end = 50.0, timelineStart = 90.0)
        val p = project(a, b)
        assertEquals(140.0, TimelineGeometry.appendStart(p, "v"), 0.0)
        // An empty lane starts at 0; another track's clips never leak.
        assertEquals(0.0, TimelineGeometry.appendStart(p, "a"), 0.0)
    }

    @Test
    fun add_duration_uses_the_probed_frame_count() {
        assertEquals(300.0, TimelineGeometry.addDuration(300), 0.0)
        // Unprobed sources get the honest placeholder.
        assertEquals(
            TimelineGeometry.UNKNOWN_DURATION_FRAMES,
            TimelineGeometry.addDuration(0),
            0.0,
        )
    }

    // ---- ruler (TL-17) ----

    @Test
    fun ruler_spans_the_resolved_length_with_major_and_minor_ticks() {
        // resolveLength of a project whose last clip ends at 90 = 90;
        // at step 30: majors at 0/30/60/90, minors at 15/45/75.
        val length = TimelineOps.resolveLength(project(clip()))
        assertEquals(90.0, length, 0.0)
        val ticks = TimelineGeometry.rulerTicks(length, 30)
        val majors = ticks.filter { it.major }.map { it.frame }
        assertEquals(listOf(0.0, 30.0, 60.0, 90.0), majors)
        val minors = ticks.filter { !it.major }.map { it.frame }
        assertEquals(listOf(15.0, 45.0, 75.0), minors)
    }

    @Test
    fun empty_project_ruler_is_empty() {
        assertTrue(TimelineGeometry.rulerTicks(0.0, 30).isEmpty())
        assertTrue(TimelineGeometry.rulerTicks(-5.0, 30).isEmpty())
    }

    @Test
    fun major_step_ladder_is_monotone_and_readable() {
        // At 4px/frame the first step whose majors span >= 64px is 20
        // (16 frames * 4 = 64px); at 12px/frame it is 10 (5*12 = 60 <
        // 64, so the ladder continues to 10 -> 120px).
        assertEquals(20, TimelineGeometry.majorStepFor(4f))
        assertEquals(10, TimelineGeometry.majorStepFor(12f))
        assertEquals(1, TimelineGeometry.majorStepFor(64f))
    }

    // ---- ruler label regime (owner 2026-11-13 de-clutter) ----

    @Test
    fun dense_sub_second_steps_label_bare_frames() {
        // Sub-second steps (a major every few frames): the second is
        // not the step, so the label is the moving digits — frames.
        // 24fps: steps 1/2/5/10/20 frames are all < 1s.
        for (step in listOf(1, 2, 5, 10, 20)) {
            assertEquals(
                TimelineGeometry.LabelFields.FRAMES,
                TimelineGeometry.labelFieldsFor(step, 24, 90.0),
            )
        }
    }

    @Test
    fun second_and_minute_steps_label_mm_ss() {
        // Steps of a second up to a minute on a sub-hour timeline: the
        // salise field is noise — the label is the wall-clock MM:SS.
        // 24fps: 24 frames = 1s, 1440 = 1min (still MM_SS — sub-hour
        // timeline, minute steps do not need hours or frames).
        for (step in listOf(24, 48, 120, 240, 1440)) {
            assertEquals(
                TimelineGeometry.LabelFields.MM_SS,
                TimelineGeometry.labelFieldsFor(step, 24, 90.0),
            )
        }
    }

    @Test
    fun an_hour_long_timeline_labels_full_timecodes() {
        // FULL is reserved for rulers that actually run an hour: any
        // shorter regime wraps at the hour (Format's 2-field MM is
        // minutes-within-hour — "01:00" would mark both the 1-minute
        // and the 61-minute major).
        val hourFrames = 24.0 * 3600.0
        for (step in listOf(24, 240, 1440)) {
            assertEquals(
                TimelineGeometry.LabelFields.FULL,
                TimelineGeometry.labelFieldsFor(step, 24, hourFrames),
            )
        }
        // One frame short of the hour: still MM_SS.
        assertEquals(
            TimelineGeometry.LabelFields.MM_SS,
            TimelineGeometry.labelFieldsFor(24, 24, hourFrames - 1.0),
        )
    }

    @Test
    fun label_text_matches_the_regime() {
        // The three shapes at 24fps, tnum-stable per regime:
        // FRAMES — the bare project-grid frame number of the tick.
        assertEquals("120", TimelineGeometry.majorLabelText(0L, 24, TimelineGeometry.LabelFields.FRAMES, 120.0))
        // MM_SS — Format's 2-field adaptive timecode (MM:SS).
        assertEquals(
            "00:05",
            TimelineGeometry.majorLabelText(
                5_000_000L,
                24,
                TimelineGeometry.LabelFields.MM_SS,
            ),
        )
        // FULL — the fixed 4-field HH:MM:SS:FF.
        assertEquals(
            "00:00:05:00",
            TimelineGeometry.majorLabelText(
                5_000_000L,
                24,
                TimelineGeometry.LabelFields.FULL,
            ),
        )
    }

    @Test
    fun label_regime_degrades_to_default_fps() {
        // A metadata-less clip (fps <= 0) degrades to Format's 24 —
        // the same fallback frameToUs and the Format family apply, so
        // the regime never crashes or disagrees with the tick math.
        assertEquals(
            TimelineGeometry.LabelFields.MM_SS,
            TimelineGeometry.labelFieldsFor(24, 0, 90.0),
        )
        assertEquals(
            TimelineGeometry.LabelFields.FRAMES,
            TimelineGeometry.labelFieldsFor(10, -1, 90.0),
        )
    }

    // ---- track row ordering ----

    @Test
    fun track_clips_display_in_time_order() {
        // The model's clips list is encounter order (insert order);
        // rows display left→right by timelineRange.start.
        val a = clip("a", start = 0.0, end = 30.0, timelineStart = 50.0)
        val b = clip("b", start = 0.0, end = 30.0, timelineStart = 0.0)
        val p = project(a, b) // encounter order: a then b
        val ordered = TimelineGeometry.trackClips(p, "v")
        assertEquals(listOf("b", "a"), ordered.map { it.id })
        // Clips on a missing track never leak in.
        assertTrue(TimelineGeometry.trackClips(p, "a-track-missing").isEmpty())
    }
}
