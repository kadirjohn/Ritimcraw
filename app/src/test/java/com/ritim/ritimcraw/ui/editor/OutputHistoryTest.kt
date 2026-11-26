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
}
