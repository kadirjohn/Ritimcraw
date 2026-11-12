package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grade history's semantics (feature N7): the per-clip undo/redo stack
 * behind the editor's undo/redo affordance — one finger drag is one undo
 * step (cause coalescing closed by settle), discrete edits never coalesce,
 * the bottom is the initial grade, pushes clear the redo stack, no-ops
 * keep it, and overflow drops the oldest entry.
 */
class GradeHistoryTest {

    private val initial = GradeK()

    /** A distinct grade per step (structural equality is the dedup guard). */
    private fun gradeAt(step: Int): GradeK = GradeK(exposureEv = step * 0.1f)

    @Test
    fun fresh_history_cannot_undo_or_redo() {
        val history = GradeHistory(initial)
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertNull(history.undo())
        assertNull(history.redo())
    }

    @Test
    fun record_then_undo_returns_the_initial_grade_and_enables_redo() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1))
        assertTrue(history.canUndo)
        assertEquals(initial, history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun undo_then_redo_returns_the_recorded_grade() {
        val history = GradeHistory(initial)
        val g = gradeAt(1)
        history.record(g)
        history.undo()
        assertEquals(g, history.redo())
        assertFalse(history.canRedo)
        assertTrue(history.canUndo)
    }

    @Test
    fun record_equal_to_the_top_is_a_no_op_that_keeps_redo() {
        val history = GradeHistory(initial)
        val g1 = gradeAt(1)
        val g2 = gradeAt(2)
        history.record(g1)
        history.record(g2)
        history.undo()
        // Re-making the exact edit that is already the top changed nothing,
        // so the redo stack must survive it.
        history.record(g1)
        assertTrue(history.canRedo)
        assertEquals(g2, history.redo())
    }

    @Test
    fun slider_frames_sharing_one_cause_coalesce_into_a_single_step() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.record(gradeAt(3), GradeHistory.SLIDER_GESTURE)
        // One finger drag = many frames = ONE undo step: the first undo
        // crosses the whole gesture back to the pre-drag state.
        assertEquals(initial, history.undo())
        assertFalse(history.canUndo)
    }

    @Test
    fun settle_separates_consecutive_drags_into_separate_steps() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.settle()
        assertEquals(gradeAt(1), history.undo())
        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun a_null_cause_never_coalesces_even_into_an_open_slider_entry() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        // A discrete edit mid-gesture (toggle, paste, reset) is its own
        // step — it must push, not replace the open drag entry.
        history.record(gradeAt(2))
        assertEquals(gradeAt(1), history.undo())
        assertEquals(initial, history.undo())
    }

    @Test
    fun a_settled_top_does_not_absorb_the_next_slider_gesture() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.settle()
        // Drag two on the same slider: the settled top forces a push, so
        // the second drag is its own undo step.
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        assertEquals(gradeAt(1), history.undo())
    }

    @Test
    fun a_push_clears_the_redo_stack() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1))
        history.record(gradeAt(2))
        history.undo()
        history.undo()
        assertTrue(history.canRedo)
        history.record(gradeAt(3))
        assertFalse(history.canRedo)
        assertNull(history.redo())
    }

    @Test
    fun undo_at_the_bottom_keeps_the_redo_stack() {
        val history = GradeHistory(initial)
        val g = gradeAt(1)
        history.record(g)
        assertEquals(initial, history.undo())
        // Bottom: null return, but the undone step is still redoable.
        assertNull(history.undo())
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
        assertEquals(g, history.redo())
    }

    @Test
    fun overflow_drops_the_oldest_entry_and_bottoms_out_there() {
        val history = GradeHistory(initial)
        val over = GradeHistory.MAX_ENTRIES + 5
        repeat(over) { step -> history.record(gradeAt(step + 1)) }
        // The stack kept the newest 50: undo bottoms out at the OLDEST
        // SURVIVING grade, not the initial one.
        var last: GradeK? = null
        repeat(GradeHistory.MAX_ENTRIES - 1) { last = history.undo() }
        assertEquals(gradeAt(over - GradeHistory.MAX_ENTRIES + 1), last)
        assertNull(history.undo())
        assertFalse(history.canUndo)
    }

    @Test
    fun undo_and_redo_entries_are_settled_so_the_next_gesture_pushes() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.undo()
        history.redo()
        // The redone entry is settled: a new drag must not weld onto it.
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        assertEquals(gradeAt(1), history.undo())
        assertEquals(initial, history.undo())
    }

    @Test
    fun settle_on_a_fresh_or_already_settled_history_is_a_no_op() {
        val history = GradeHistory(initial)
        history.settle()
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.settle()
        // The double settle closed nothing extra: the next gesture is its
        // own step, exactly one undo crosses back to the initial grade.
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        assertEquals(gradeAt(1), history.undo())
        assertEquals(initial, history.undo())
    }

    @Test
    fun a_duplicate_slider_frame_is_a_no_op_not_an_extra_step() {
        val history = GradeHistory(initial)
        val g = gradeAt(1)
        history.record(g, GradeHistory.SLIDER_GESTURE)
        history.record(g, GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.settle()
        // The duplicate frame left one entry, not two: undo crosses the
        // whole first gesture in one step.
        assertEquals(g, history.undo())
        assertEquals(initial, history.undo())
    }

    @Test
    fun a_coalesced_entry_keeps_the_gestures_last_frame_as_its_endpoint() {
        val history = GradeHistory(initial)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.record(gradeAt(3))
        // Undo past the discrete edit lands on the drag's ENDPOINT (the
        // state the user actually saw), never an intermediate frame.
        assertEquals(gradeAt(2), history.undo())
        assertEquals(initial, history.undo())
    }
}
