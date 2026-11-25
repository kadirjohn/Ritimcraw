package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F1-8b: the Wheels page's pure logic and its coverage on the node undo history. */
class NodeWheelOpsTest {
    private val neutral = GradeNodeV2()

    @Test
    fun every_wheel_id_reads_and_writes_its_own_field_only() {
        for (id in NodeWheelId.entries) {
            val w = NodeWheelV2(0.5f, -0.25f, 0.3f)
            val n = NodeWheelOps.set(neutral, id, w)
            assertEquals(w, NodeWheelOps.get(n, id))
            for (other in NodeWheelId.entries.filter { it != id }) {
                assertTrue("$id leaked into $other", NodeWheelOps.isNeutral(n, other))
            }
            assertEquals(neutral, NodeWheelOps.resetWheel(n, id))
        }
    }

    @Test
    fun groups_are_four_primary_and_three_log() {
        assertEquals(listOf(NodeWheelId.LIFT, NodeWheelId.GAMMA, NodeWheelId.GAIN, NodeWheelId.OFFSET), NodeWheelId.PRIMARY)
        assertEquals(listOf(NodeWheelId.LOG_SHADOW, NodeWheelId.LOG_MID, NodeWheelId.LOG_HI), NodeWheelId.LOG)
        assertEquals(NodeWheelId.GAIN, NodeWheelOps.selectionForGroup(NodeWheelId.GAIN, true))
        assertEquals(NodeWheelId.LOG_SHADOW, NodeWheelOps.selectionForGroup(NodeWheelId.GAIN, false))
        assertEquals(NodeWheelId.LIFT, NodeWheelOps.selectionForGroup(NodeWheelId.LOG_MID, true))
    }

    @Test
    fun puck_is_clamped_to_the_unit_disc_and_snapped_to_the_grid() {
        val n = NodeWheelOps.setPuck(neutral, NodeWheelId.LIFT, 3f, 4f) // hypot 5 -> (0.6, 0.8)
        assertEquals(0.6f, n.lift.x, 1e-6f)
        assertEquals(0.8f, n.lift.y, 1e-6f)
        val s = NodeWheelOps.setPuck(neutral, NodeWheelId.GAIN, 0.2234f, -0.0612f)
        assertEquals(0.20f, s.gain.x, 1e-6f)
        assertEquals(-0.05f, s.gain.y, 1e-6f)
        // diagonal rim: both snapped values stay inside the box and the disc is not exceeded by more than a grid step
        val d = NodeWheelOps.setPuck(neutral, NodeWheelId.OFFSET, 9f, 9f).offset
        assertTrue(Math.hypot(d.x.toDouble(), d.y.toDouble()) <= 1.0 + NodeWheelOps.PUCK_STEP)
    }

    @Test
    fun puck_keeps_the_master_and_a_centre_drop_is_positive_zero() {
        val start = NodeWheelOps.set(neutral, NodeWheelId.GAMMA, NodeWheelV2(0.5f, 0.5f, 0.4f))
        val n = NodeWheelOps.setPuck(start, NodeWheelId.GAMMA, 0.01f, -0.01f)
        assertEquals(0.4f, n.gamma.master, 0f)
        // -0.0 would break data-class equality with the neutral wheel (Float.compare)
        assertEquals(NodeWheelV2(0f, 0f, 0.4f), n.gamma)
        assertEquals(0f, NodeWheelOps.padToWheelY(0f), 0f)
        assertEquals(0, java.lang.Float.floatToRawIntBits(NodeWheelOps.padToWheelY(0f))) // +0.0, not -0.0
    }

    @Test
    fun master_is_clamped_and_snapped() {
        assertEquals(1f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, NodeWheelId.LOG_HI, 5f), NodeWheelId.LOG_HI).master, 0f)
        assertEquals(-1f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, NodeWheelId.LOG_HI, -5f), NodeWheelId.LOG_HI).master, 0f)
        assertEquals(0.23f, NodeWheelOps.setMaster(neutral, NodeWheelId.LIFT, 0.2349f).lift.master, 1e-6f)
        assertEquals(0f, NodeWheelOps.setMaster(neutral, NodeWheelId.LIFT, -0.001f).lift.master, 0f)
        assertEquals(NodeWheelV2(), NodeWheelOps.setMaster(neutral, NodeWheelId.LIFT, -0.001f).lift)
    }

    @Test
    fun resets_are_single_scoped() {
        val n = NodeWheelOps.set(neutral, NodeWheelId.GAIN, NodeWheelV2(0.4f, -0.3f, 0.5f))
        assertEquals(NodeWheelV2(0f, 0f, 0.5f), NodeWheelOps.resetPuck(n, NodeWheelId.GAIN).gain)
        assertEquals(NodeWheelV2(0.4f, -0.3f, 0f), NodeWheelOps.resetMaster(n, NodeWheelId.GAIN).gain)
        assertEquals(NodeWheelV2(), NodeWheelOps.resetWheel(n, NodeWheelId.GAIN).gain)
        // a wheel reset never touches the node's other controls or enable flag
        val other = n.copy(sat = 1.3f, enabled = false)
        val r = NodeWheelOps.resetWheel(other, NodeWheelId.GAIN)
        assertEquals(1.3f, r.sat, 0f)
        assertFalse(r.enabled)
    }

    @Test
    fun pad_frame_is_flipped_in_y_both_ways() {
        assertEquals(-0.5f, NodeWheelOps.padToWheelY(0.5f), 0f)
        assertEquals(0.5f, NodeWheelOps.wheelToPadY(-0.5f), 0f)
        val round = NodeWheelOps.wheelToPadY(NodeWheelOps.padToWheelY(0.35f))
        assertEquals(0.35f, round, 0f)
    }

    @Test
    fun disc_colour_follows_the_engine_wheel_rgb() {
        val c = NodeWheelOps.discColor(0f, 0f)
        assertEquals(0.5f, c.red, 0.01f); assertEquals(0.5f, c.green, 0.01f); assertEquals(0.5f, c.blue, 0.01f) // sRGB colours store 8 bits
        val right = NodeWheelOps.discColor(1f, 0f) // angle 0: red up, green and blue down
        assertTrue(right.red > 0.9f && right.green < 0.5f && right.blue < 0.5f)
        val g = NodeWheelOps.discColor(Math.cos(2 * Math.PI / 3).toFloat(), Math.sin(2 * Math.PI / 3).toFloat())
        assertTrue(g.green > 0.9f && g.red < 0.5f && g.blue < 0.5f) // 120 degrees = green
        val b = NodeWheelOps.discColor(Math.cos(4 * Math.PI / 3).toFloat(), Math.sin(4 * Math.PI / 3).toFloat())
        assertTrue(b.blue > 0.9f && b.red < 0.5f && b.green < 0.5f) // 240 degrees = blue
        // pad frame: the engine's 120 degrees (y up) is the pad's upper left
        val padUpLeft = NodeWheelOps.padDiscColor(-0.5f, -0.866f)
        assertTrue(padUpLeft.green > padUpLeft.red && padUpLeft.green > padUpLeft.blue)
    }

    @Test
    fun readout_is_locale_independent() {
        assertEquals("x +0.25  y -0.10", NodeWheelOps.puckReadout(NodeWheelV2(0.25f, -0.1f, 0f)))
    }

    // ---- undo coverage on the node history (GradeHistory.recordNodes, the path NodesBody edits take) ----

    private val grade = GradeK()

    @Test
    fun a_pad_drag_is_one_undo_step_that_restores_the_pre_drag_wheel() {
        val history = GradeHistory(grade, listOf(neutral))
        var n = neutral
        for (x in listOf(0.1f, 0.2f, 0.35f, 0.5f)) {
            n = NodeWheelOps.setPuck(n, NodeWheelId.LIFT, x, 0.1f)
            history.recordNodes(listOf(n), GradeHistory.NODE_GESTURE)
        }
        history.settle()
        val back = history.undoSnapshot()!!
        assertEquals(listOf(neutral), back.nodes)
        assertNull(history.undoSnapshot())
        assertEquals(0.5f, history.redoSnapshot()!!.nodes[0].lift.x, 1e-6f)
    }

    @Test
    fun a_master_drag_then_a_reset_are_two_steps_and_undo_walks_back_through_both() {
        val history = GradeHistory(grade, listOf(neutral))
        val dragged = NodeWheelOps.setMaster(neutral, NodeWheelId.GAMMA, 0.4f)
        history.recordNodes(listOf(NodeWheelOps.setMaster(neutral, NodeWheelId.GAMMA, 0.2f)), GradeHistory.NODE_GESTURE)
        history.recordNodes(listOf(dragged), GradeHistory.NODE_GESTURE)
        history.settle()
        val reset = NodeWheelOps.resetWheel(dragged, NodeWheelId.GAMMA)
        history.recordNodes(listOf(reset), GradeHistory.NODE_GESTURE)
        history.settle()
        assertEquals(listOf(dragged), history.undoSnapshot()!!.nodes) // undo the reset
        assertEquals(listOf(neutral), history.undoSnapshot()!!.nodes) // undo the drag
        assertEquals(listOf(dragged), history.redoSnapshot()!!.nodes)
    }

    @Test
    fun wheel_edits_on_two_nodes_keep_the_other_node_untouched_through_undo() {
        val a = GradeNodeV2(sat = 1.2f)
        val history = GradeHistory(grade, listOf(a, neutral))
        val edited = listOf(a, NodeWheelOps.setPuck(neutral, NodeWheelId.LOG_MID, 0.3f, 0.4f))
        history.recordNodes(edited, GradeHistory.NODE_GESTURE)
        history.settle()
        val back = history.undoSnapshot()!!
        assertEquals(listOf(a, neutral), back.nodes)
    }
}
