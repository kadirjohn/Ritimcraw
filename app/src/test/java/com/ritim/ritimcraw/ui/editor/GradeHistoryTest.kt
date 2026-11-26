package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.LutEncodingK
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer
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
        // The stack kept the newest MAX_ENTRIES states: undo bottoms out
        // at the OLDEST SURVIVING grade, not the initial one.
        var last: GradeK? = null
        repeat(GradeHistory.MAX_ENTRIES - 1) { last = history.undo() }
        assertEquals(gradeAt(over - GradeHistory.MAX_ENTRIES + 1), last)
        assertNull(history.undo())
        assertFalse(history.canUndo)
    }

    @Test
    fun the_capacity_serves_exactly_100_undos_and_no_more() {
        // The owner's 100-undo budget, pinned at the boundary: 101 states
        // (the initial bottom + 100 recorded steps) serve EXACTLY 100
        // undos — the 100th lands on the initial grade itself, and the
        // 101st returns null: the initial state is a state to BE, not a
        // step to take past. Each undo returns the grade to APPLY (one
        // step back from the current one).
        val history = GradeHistory(initial)
        repeat(100) { step -> history.record(gradeAt(step + 1)) }
        assertTrue(history.canUndo)
        // Undos 1..99 land on g99 down to g1.
        repeat(99) { undone ->
            assertEquals(gradeAt(99 - undone), history.undo())
        }
        // The 100th and final served undo lands on the initial state.
        assertEquals(initial, history.undo())
        // Nothing deeper to serve.
        assertNull(history.undo())
        assertFalse(history.canUndo)
    }

    @Test
    fun the_boundary_step_itself_makes_the_oldest_state_fall_off() {
        // Drop-oldest boundary, pinned exactly AT the capacity: the record
        // that FILLS the stack to MAX_ENTRIES keeps the initial state;
        // the very NEXT record pushes it off, and undo bottoms out one
        // step shallower — the honest edge the overflow must show.
        val history = GradeHistory(initial)
        repeat(GradeHistory.MAX_ENTRIES - 1) { step ->
            history.record(gradeAt(step + 1))
        }
        // Stack holds exactly MAX_ENTRIES states, initial still the bottom.
        var last: GradeK? = null
        repeat(GradeHistory.MAX_ENTRIES - 1) { last = history.undo() }
        assertEquals(initial, last)
        assertNull(history.undo())
        // Refill to full, then ONE more record: the initial state falls off.
        repeat(GradeHistory.MAX_ENTRIES - 1) { step ->
            history.record(gradeAt(step + 1))
        }
        history.record(gradeAt(GradeHistory.MAX_ENTRIES))
        repeat(GradeHistory.MAX_ENTRIES - 1) { last = history.undo() }
        assertEquals(gradeAt(1), last)
        assertNull(history.undo())
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

    @Test
    fun undo_and_redo_carry_the_managed_lut_interpretation() {
        // Owner request 2026-11-13: the interpretation rides INSIDE the
        // GradeK the history holds by value — an undo/redo restores the
        // exact interpretation the user set at that step, independent of
        // the library's records.
        val interp = LutInterpK(
            input = LutEncodingK(LutGamut.Rec2020, LutTransfer.Linear),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneLook,
        )
        val withInterp = GradeK(exposureEv = 0.5f, lutId = "abc123def4567890", lutInterp = interp)

        val history = GradeHistory(initial)
        history.record(withInterp)

        val undone = history.undo()
        assertEquals(initial, undone)
        val redone = history.redo()
        assertEquals(interp, redone!!.lutInterp)
        assertEquals("abc123def4567890", redone.lutId)
    }

    // ---- node chain on the history (F1-8a follow-up) ----

    private fun node(sat: Float = 1f, enabled: Boolean = true) =
        com.ritim.ritimcraw.data.grade2.GradeNodeV2(sat = sat, enabled = enabled)

    @Test
    fun a_node_add_is_a_step_and_undo_restores_the_empty_chain() {
        val history = GradeHistory(initial, emptyList())
        history.recordNodes(listOf(node()))
        assertTrue(history.canUndo)
        val back = history.undoSnapshot()!!
        assertEquals(initial, back.grade)
        assertEquals(emptyList<Any>(), back.nodes)
        val fwd = history.redoSnapshot()!!
        assertEquals(listOf(node()), fwd.nodes)
    }

    @Test
    fun a_node_slider_drag_is_one_step_until_settled() {
        val history = GradeHistory(initial, listOf(node()))
        val cause = GradeHistory.NODE_GESTURE
        history.recordNodes(listOf(node(1.1f)), cause)
        history.recordNodes(listOf(node(1.2f)), cause)
        history.recordNodes(listOf(node(1.3f)), cause)
        history.settle()
        history.recordNodes(listOf(node(1.5f)), cause)
        assertEquals(listOf(node(1.3f)), history.undoSnapshot()!!.nodes)
        assertEquals(listOf(node(1f)), history.undoSnapshot()!!.nodes)
        assertNull(history.undoSnapshot())
    }

    @Test
    fun grade_and_node_edits_interleave_and_each_snapshot_carries_both() {
        val history = GradeHistory(initial, emptyList())
        history.recordNodes(listOf(node(1.4f)))
        history.record(gradeAt(3))
        val s1 = history.undoSnapshot()!!
        assertEquals(initial, s1.grade)          // the grade edit is undone ...
        assertEquals(listOf(node(1.4f)), s1.nodes) // ... the node edit is still in place
        val s0 = history.undoSnapshot()!!
        assertEquals(emptyList<Any>(), s0.nodes)
        assertEquals(gradeAt(3), history.redoSnapshot().let { history.redoSnapshot()!!.grade })
    }

    @Test
    fun an_unchanged_node_list_is_a_no_op_and_keeps_redo() {
        val history = GradeHistory(initial, listOf(node()))
        history.recordNodes(listOf(node(1.2f)))
        history.undoSnapshot()
        history.recordNodes(listOf(node())) // equal to the top
        assertTrue(history.canRedo)
    }

    // ---- open entry + single touch + settle + resetScope (the double-tap ORDER) ----

    @Test
    fun mark_tap_settle_tap_scope_collapses_the_whole_double_tap_into_one_step() {
        // The device's exact call order: tap1's down marks (and settles any
        // open entry first), tap1's jump records OPEN and its drag stop
        // settles, tap2's down does NOT re-mark, tap2's jump records OPEN
        // again (no inter-tap settle needed — the collapse filters on the
        // cause, not the open flag), and the up runs the reset inside
        // resetScope. ONE undo lands exactly on the pre-gesture grade.
        val history = GradeHistory(initial)
        val preGesture = gradeAt(1)
        history.record(preGesture, GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.markGestureBase()
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.record(gradeAt(3), GradeHistory.SLIDER_GESTURE) // tap2's jump, left open
        val reset = gradeAt(4)
        history.resetScope { history.record(reset) }
        assertEquals(preGesture, history.undo())
        assertTrue(history.canUndo)
        assertEquals(reset, history.redo())
        assertEquals(preGesture, history.undo())
        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun the_second_touchs_mark_wins_and_a_stale_mark_never_swallows() {
        // A single touch that never becomes a double-tap leaves its mark
        // unconsumed; the NEXT touch's mark REPLACES it. A later reset
        // collapses to the LATEST base — the stale first mark must not
        // swallow the state that landed in between.
        val history = GradeHistory(initial)
        history.markGestureBase() // touch 1 (a lone tap)
        history.record(gradeAt(1), GradeHistory.SLIDER_GESTURE)
        history.settle()
        assertTrue(history.canUndo) // the single touch made its own durable step
        history.markGestureBase() // touch 2 replaces the mark
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.resetScope { history.record(gradeAt(3)) }
        // The collapse kept the LATEST base (gradeAt(1)); a stale-mark win
        // would return the initial grade here instead.
        assertEquals(gradeAt(1), history.undo())
        assertEquals(initial, history.undo())
        assertNull(history.undo())
        // Redo replays the crossed states forward: the touch, then the ONE
        // reset step — never the tap2 jump in between (the collapse dropped
        // it, so the redo stack holds exactly two entries, not three).
        assertEquals(gradeAt(1), history.redo())
        assertEquals(gradeAt(3), history.redo())
    }

    @Test
    fun a_discrete_edit_between_the_taps_blocks_the_collapse_and_keeps_every_step() {
        // Growth since the mark that contains a non-slider entry fails the
        // cause check: the collapse declines and the reset records plainly.
        // Undo must then walk back through EVERY step — a toggle between
        // the two taps is never silently swallowed.
        val history = GradeHistory(initial)
        val marked = gradeAt(1)
        history.record(marked, GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.markGestureBase()
        val toggled = gradeAt(2)
        history.record(toggled) // discrete (null cause) between the taps
        history.record(gradeAt(3), GradeHistory.SLIDER_GESTURE) // tap2's jump
        history.settle()
        history.resetScope { history.record(gradeAt(4)) }
        assertEquals(gradeAt(3), history.undo())
        assertEquals(toggled, history.undo())
        assertEquals(marked, history.undo())
        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun a_no_growth_tap_window_still_lands_the_reset_on_the_marked_grade() {
        // The second tap lands where the thumb already is: the jump frame
        // equals the top and records NOTHING, so the window shows no
        // growth and the collapse declines — but the reset's undo still
        // lands on the marked grade, the exact outcome a collapse gives.
        val history = GradeHistory(initial)
        val base = gradeAt(1)
        history.record(base)
        history.markGestureBase()
        history.record(base, GradeHistory.SLIDER_GESTURE) // duplicate frame: no-op
        val reset = gradeAt(2)
        history.resetScope { history.record(reset) }
        assertEquals(base, history.undo()) // one step back: the marked grade
        assertEquals(reset, history.redo())
    }

    @Test
    fun a_reset_scope_without_a_mark_records_plaintly() {
        // resetScope entered without markGestureBase (null base): the
        // collapse is a no-op and the reset is one PLAIN step. The scope
        // must leave no half-state behind — a later mark + drag + reset
        // still collapses normally.
        val history = GradeHistory(initial)
        val g1 = gradeAt(1)
        history.resetScope { history.record(g1) }
        assertTrue(history.canUndo)
        assertEquals(initial, history.undo())
        assertEquals(g1, history.redo())
        history.settle()
        history.markGestureBase()
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.resetScope { history.record(gradeAt(3)) }
        assertEquals(g1, history.undo()) // the collapse dropped the drag
        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun a_thrown_reset_scope_still_records_and_cleans_up_after_itself() {
        // An exception inside the scope still runs the finally: the record
        // landed (with its collapse) and BOTH the in-reset flag and the
        // mark are cleared — a later resetScope cannot collapse against a
        // dead base.
        val history = GradeHistory(initial)
        val dragged = gradeAt(1)
        history.record(dragged, GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.markGestureBase()
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE) // the double-tap's nudge
        history.settle()
        val reset = gradeAt(3)
        val threw = try {
            history.resetScope {
                history.record(reset)
                throw IllegalStateException("screen torn down mid-reset")
            }
            false
        } catch (e: IllegalStateException) {
            true
        }
        assertTrue(threw)
        assertEquals(dragged, history.undo()) // the nudge collapsed, the reset recorded
        assertEquals(reset, history.redo())
        history.settle()
        val nudge = gradeAt(4)
        history.record(nudge, GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.resetScope { history.record(reset) }
        // The base was cleared by the aborted scope: this reset is a PLAIN
        // step, so undo walks the nudge back individually — not swallowed.
        assertEquals(nudge, history.undo())
        assertEquals(reset, history.undo())
        assertEquals(dragged, history.undo())
        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun a_no_change_reset_keeps_redo_and_a_real_change_clears_it() {
        val history = GradeHistory(initial)
        val g1 = gradeAt(1)
        history.record(g1)
        assertEquals(initial, history.undo())
        history.markGestureBase()
        // The double-tap lands where the reset leaves the grade untouched:
        // every record inside the window is a no-op, so the redo stack
        // survives the whole gesture.
        history.record(initial, GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.resetScope { history.record(initial) }
        assertTrue(history.canRedo)
        assertEquals(g1, history.redo())
        // A real change through the same shape: the push clears the redo
        // stack, collapse or not (the classic undo rule).
        assertEquals(initial, history.undo())
        history.markGestureBase()
        history.record(gradeAt(2), GradeHistory.SLIDER_GESTURE)
        history.settle()
        history.resetScope { history.record(gradeAt(3)) }
        assertFalse(history.canRedo)
        assertNull(history.redo())
        assertEquals(initial, history.undo())
        assertNull(history.undo())
    }

    @Test
    fun at_the_capacity_boundary_the_mark_cannot_collapse_and_the_reset_records_plaintly() {
        // Overflow vs the collapse contract: on a FULL stack the marked
        // entry cannot survive a push (takeLast drops the oldest, size
        // stays MAX_ENTRIES), so "the stack still matches the mark" is
        // honestly false -- no growth is possible and no index matches.
        // The reset records plainly and its undo lands on the tap jump,
        // one step shallower than a mid-history double-tap would land.
        val history = GradeHistory(initial)
        repeat(GradeHistory.MAX_ENTRIES - 1) { step -> history.record(gradeAt(step + 1)) }
        history.markGestureBase() // base.size == MAX_ENTRIES
        val jump = gradeAt(GradeHistory.MAX_ENTRIES)
        history.record(jump, GradeHistory.SLIDER_GESTURE) // the stack is full: the oldest falls
        history.settle()
        val reset = gradeAt(GradeHistory.MAX_ENTRIES + 1)
        history.resetScope { history.record(reset) }
        assertEquals(jump, history.undo()) // no collapse: the reset's undo is the jump
        assertEquals(gradeAt(GradeHistory.MAX_ENTRIES - 1), history.undo())
        assertTrue(history.canUndo) // the surviving states still serve undos
    }
}
