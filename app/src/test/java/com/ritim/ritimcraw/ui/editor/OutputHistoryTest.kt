package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.OpenDrtParamsV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1-8f: Output card edits ride the grade history (the Snapshot carries the output group). */
class OutputHistoryTest {
    private val g0 = GradeK()
    private val o0 = OutputTransformV2()
    private val node = listOf(GradeNodeV2(sat = 1.2f))

    private fun drt(look: Int) =
        NodeOutputOps.setLook(NodeOutputOps.setMode(o0, OutputTransformV2.MODE_OPENDRT), look)

    @Test
    fun a_slider_drag_is_one_step_and_undo_restores_the_pre_drag_value_and_redo_reapplies() {
        val start = drt(1)
        val h = GradeHistory(g0, node, start)
        for (l in 2..5) h.recordOutput(NodeOutputOps.setLook(start, l), GradeHistory.OUTPUT_GESTURE)
        h.settle()
        assertEquals(start, h.undoSnapshot()!!.output)
        assertFalse(h.canUndo)
        assertNull(h.undoSnapshot())
        assertEquals(5, h.redoSnapshot()!!.output.opendrt.look)
    }

    @Test
    fun two_drags_are_two_steps() {
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(NodeOutputOps.setLook(o0, 2), GradeHistory.OUTPUT_GESTURE)
        h.settle()
        h.recordOutput(NodeOutputOps.setLook(o0, 4), GradeHistory.OUTPUT_GESTURE)
        h.settle()
        assertEquals(2, h.undoSnapshot()!!.output.opendrt.look)
        assertEquals(0, h.undoSnapshot()!!.output.opendrt.look)
    }

    @Test
    fun a_mode_switch_is_one_step_and_undo_restores_old_mode_and_old_opendrt_params() {
        val params = OpenDrtParamsV2(look = 3, tonescale = 5, cwp = 2, cwpLimit = 0.5f, grey = 12f, display = 1)
        val legacyWithParams = OutputTransformV2(opendrt = params) // mode legacy, params kept verbatim
        val h = GradeHistory(g0, node, legacyWithParams)
        val switched = NodeOutputOps.setMode(legacyWithParams, OutputTransformV2.MODE_OPENDRT)
        h.recordOutput(switched, null)
        val tweaked = NodeOutputOps.setLook(switched, 6)
        h.recordOutput(tweaked, GradeHistory.OUTPUT_GESTURE)
        h.settle()
        assertEquals(switched, h.undoSnapshot()!!.output) // undo the tweak: still opendrt, old look
        val back = h.undoSnapshot()!!.output // undo the switch
        assertEquals(OutputTransformV2.MODE_LEGACY, back.mode)
        assertEquals(params, back.opendrt)
        assertFalse(h.canUndo)
        assertEquals(switched, h.redoSnapshot()!!.output)
        assertEquals(tweaked, h.redoSnapshot()!!.output)
    }

    @Test
    fun discrete_choices_never_coalesce() {
        val opendrt = NodeOutputOps.setMode(o0, OutputTransformV2.MODE_OPENDRT)
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(opendrt)
        h.recordOutput(NodeOutputOps.setDisplay(opendrt, 2))
        assertEquals(0, h.undoSnapshot()!!.output.opendrt.display)
        assertEquals(OutputTransformV2.MODE_LEGACY, h.undoSnapshot()!!.output.mode)
        assertFalse(h.canUndo)
    }

    @Test
    fun an_identical_output_is_a_no_op_that_keeps_redo() {
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(drt(2))
        h.undoSnapshot()
        assertTrue(h.canRedo)
        h.recordOutput(o0)
        assertTrue(h.canRedo)
    }

    @Test
    fun interleaving_with_grade_and_node_edits_keeps_each_group_through_undo() {
        val a = drt(2)
        val g1 = GradeK(exposureEv = 0.5f)
        val n1 = listOf(GradeNodeV2(sat = 1.5f))
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(a) // 1
        h.record(g1) // 2: a grade edit keeps the output
        h.recordNodes(n1) // 3: a node edit keeps grade + output
        val s2 = h.undoSnapshot()!!
        assertEquals(g1, s2.grade); assertEquals(node, s2.nodes); assertEquals(a, s2.output)
        val s1 = h.undoSnapshot()!!
        assertEquals(g0, s1.grade); assertEquals(node, s1.nodes); assertEquals(a, s1.output)
        val s0 = h.undoSnapshot()!!
        assertEquals(o0, s0.output)
        assertEquals(a, h.redoSnapshot()!!.output)
        h.redoSnapshot()
        val r3 = h.redoSnapshot()!!
        assertEquals(n1, r3.nodes); assertEquals(g1, r3.grade); assertEquals(a, r3.output)
    }

    @Test
    fun a_new_output_edit_after_undo_clears_redo() {
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(drt(2))
        h.undoSnapshot()
        h.recordOutput(drt(3))
        assertFalse(h.canRedo)
    }

    @Test
    fun clips_without_output_edits_keep_the_default_output_in_every_snapshot() {
        val h = GradeHistory(g0)
        h.record(GradeK(exposureEv = 1f))
        assertEquals(o0, h.undoSnapshot()!!.output)
        assertEquals(o0, h.redoSnapshot()!!.output)
        assertEquals(GradeK(exposureEv = 1f), h.undo().let { h.redo() })
    }

    @Test
    fun value_edit_classification_follows_the_slider_vs_discrete_split() {
        val d = drt(1)
        assertTrue(NodeOutputOps.isValueEdit(d, NodeOutputOps.setLook(d, 4)))
        assertTrue(NodeOutputOps.isValueEdit(d, NodeOutputOps.setGrey(d, 20f)))
        assertFalse(NodeOutputOps.isValueEdit(d, NodeOutputOps.setDisplay(d, 2)))
        assertFalse(NodeOutputOps.isValueEdit(d, NodeOutputOps.setMode(d, OutputTransformV2.MODE_LEGACY)))
    }

    // ---- C3 gap fill (night 2026-11-26): mixed sequences, bounds, rapid edits ----

    @Test
    fun a_mode_switch_between_slider_frames_is_its_own_step_and_redo_ends_in_null() {
        val start = drt(1)
        val h = GradeHistory(g0, node, start)
        // frames of one drag, never settled (the screen closes the step after a 600 ms pause)
        h.recordOutput(NodeOutputOps.setLook(start, 2), GradeHistory.OUTPUT_GESTURE)
        h.recordOutput(NodeOutputOps.setLook(start, 3), GradeHistory.OUTPUT_GESTURE)
        // a mode switch MID-drag: discrete (isValueEdit is false on the real screen) -> its own closed step
        val switched = NodeOutputOps.setMode(NodeOutputOps.setLook(start, 3), OutputTransformV2.MODE_LEGACY)
        h.recordOutput(switched)
        // the drag resumes on the switched card (a value edit again)
        val resumed = NodeOutputOps.setGrey(switched, 12f)
        h.recordOutput(resumed, GradeHistory.OUTPUT_GESTURE)
        h.settle()
        // undo applies the PREVIOUS state each step: the resumed drag, the switch (pre-switch drag
        // endpoint: opendrt, look 3), the drag, the initial. Redo walks them forward and ENDS in null.
        assertEquals(switched, h.undoSnapshot()!!.output)
        assertEquals(NodeOutputOps.setLook(start, 3), h.undoSnapshot()!!.output)
        assertEquals(start, h.undoSnapshot()!!.output)
        assertNull(h.undoSnapshot())
        assertEquals(NodeOutputOps.setLook(start, 3), h.redoSnapshot()!!.output)
        assertEquals(switched, h.redoSnapshot()!!.output)
        assertEquals(resumed, h.redoSnapshot()!!.output)
        assertNull(h.redoSnapshot())
        assertFalse(h.canRedo)
    }

    @Test
    fun undo_at_the_bottom_keeps_redo_for_the_output_group() {
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(drt(2))
        assertEquals(o0, h.undoSnapshot()!!.output)
        assertFalse(h.canUndo)
        assertTrue(h.canRedo) // the bottom's null did not touch the redo stack
        assertNull(h.undoSnapshot())
        assertTrue(h.canRedo)
        assertEquals(drt(2), h.redoSnapshot()!!.output)
        assertFalse(h.canRedo) // redo at the end: null and disabled
        assertNull(h.redoSnapshot())
    }

    @Test
    fun edits_without_a_settle_coalesce_across_drag_boundaries() {
        // The screen closes an output step after a 600 ms PAUSE (ClipEditorScreen's
        // outputSettleJob), not on DragStopped: two quick drags inside the pause are
        // ONE undo step. GradeHistory's contract is cause + settle, and this pins it:
        // frames with no intervening settle merge; a discrete edit between them splits.
        val a = drt(2)
        val b = drt(3)
        val c = drt(4)
        val h = GradeHistory(g0, node, o0)
        h.recordOutput(a, GradeHistory.OUTPUT_GESTURE)
        h.recordOutput(b, GradeHistory.OUTPUT_GESTURE)
        h.recordOutput(c, GradeHistory.OUTPUT_GESTURE) // a "second drag" 100 ms later: still open -> replaced
        assertEquals(o0, h.undoSnapshot()!!.output) // ONE step crosses both drags
        assertFalse(h.canUndo)
        assertEquals(c, h.redoSnapshot()!!.output)
        // the same shape with a discrete edit in the middle: three separate states to walk back
        val h2 = GradeHistory(g0, node, o0)
        h2.recordOutput(a, GradeHistory.OUTPUT_GESTURE)
        val switched = NodeOutputOps.setMode(a, OutputTransformV2.MODE_LEGACY)
        h2.recordOutput(switched) // discrete mid-flight: pushes, never coalesces
        h2.recordOutput(b, GradeHistory.OUTPUT_GESTURE)
        assertEquals(switched, h2.undoSnapshot()!!.output) // undo the second burst
        assertEquals(a, h2.undoSnapshot()!!.output) // undo the switch
        assertEquals(o0, h2.undoSnapshot()!!.output) // undo the first burst
        assertNull(h2.undoSnapshot())
    }

    @Test
    fun open_gestures_of_all_three_groups_interleave_and_each_undo_restores_the_triple() {
        // The three-group transition: an output drag in flight, a NEW NODE landing
        // mid-drag, grade slider frames (a different cause), then the output drag
        // continuing. Each undo restores the exact (grade, nodes, output) triple.
        val nodes0 = listOf(GradeNodeV2(sat = 1.2f))
        val nodes1 = nodes0 + GradeNodeV2(sat = 1.5f)
        val h = GradeHistory(g0, nodes0, o0)
        h.recordOutput(drt(2), GradeHistory.OUTPUT_GESTURE)
        h.recordOutput(drt(3), GradeHistory.OUTPUT_GESTURE)
        h.recordNodes(nodes1) // the node add carries the DRAGGED output (drt(3)) with it
        h.record(GradeK(exposureEv = 0.4f), GradeHistory.SLIDER_GESTURE)
        h.record(GradeK(exposureEv = 0.5f), GradeHistory.SLIDER_GESTURE)
        h.recordOutput(drt(4), GradeHistory.OUTPUT_GESTURE) // the top's cause differs: pushes
        h.settle()
        var s = h.undoSnapshot()!! // undo the resumed output frame: the grade entry's endpoint
        assertEquals(GradeK(exposureEv = 0.5f), s.grade); assertEquals(nodes1, s.nodes); assertEquals(drt(3), s.output)
        s = h.undoSnapshot()!! // undo the coalesced grade drag: the node-add state
        assertEquals(g0, s.grade); assertEquals(nodes1, s.nodes); assertEquals(drt(3), s.output)
        s = h.undoSnapshot()!! // undo the node add: the output drag's endpoint rides
        assertEquals(g0, s.grade); assertEquals(nodes0, s.nodes); assertEquals(drt(3), s.output)
        s = h.undoSnapshot()!! // undo the output drag: the initial triple
        assertEquals(g0, s.grade); assertEquals(nodes0, s.nodes); assertEquals(o0, s.output)
        assertNull(h.undoSnapshot())
    }

    @Test
    fun overflow_drops_the_oldest_state_and_keeps_the_newest_output() {
        val h = GradeHistory(g0, node, o0)
        val over = GradeHistory.MAX_ENTRIES + 4
        repeat(over) { i ->
            h.recordOutput(OutputTransformV2(opendrt = OpenDrtParamsV2(grey = (i + 1).toFloat())))
        }
        assertEquals(104f, h.undoSnapshot()!!.output.opendrt.grey, 0f) // the newest-but-one state
        var last: OutputTransformV2? = null
        repeat(GradeHistory.MAX_ENTRIES - 2) { last = h.undoSnapshot()!!.output }
        assertEquals(OpenDrtParamsV2(grey = 5f), last!!.opendrt) // the OLDEST SURVIVING output (r5)
        assertNull(h.undoSnapshot()) // the initial state fell off the bottom
        assertFalse(h.canUndo)
    }
}
