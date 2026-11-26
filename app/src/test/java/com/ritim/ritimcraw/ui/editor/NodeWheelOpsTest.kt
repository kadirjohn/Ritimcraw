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

    // ---- edge values: extremes, wrap-around, clamps, zero/identity (A8) ----

    @Test
    fun set_puck_at_the_exact_rim_clamps_and_keeps_a_grid_position() {
        // r == 1 is the boundary: NO rescale runs (r > 1 is strict), the
        // snap must keep the puck EXACTLY on the rim grid, never nudge it
        // inward — the engine clamps at magnitude 1 and a rim value must
        // survive as maximum saturation.
        for ((x, y) in listOf(1f to 0f, 0f to 1f, -1f to 0f, 0f to -1f)) {
            val w = NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.GAIN, x, y), NodeWheelId.GAIN)
            assertEquals(x, w.x, 1e-6f)
            assertEquals(y, w.y, 1e-6f)
            assertEquals(1f, Math.hypot(w.x.toDouble(), w.y.toDouble()).toFloat(), 1e-6f)
        }
    }

    @Test
    fun set_puck_just_outside_the_rim_rescales_then_snaps_inward() {
        // r slightly over 1: the rescale normalizes onto the unit circle
        // (magnitude stops at 1), and the grid snap then moves the puck to
        // the NEAREST grid point — the diagonal lands at (0.70, 0.70),
        // magnitude 0.99. The clamp owns "never past the rim"; it does NOT
        // promise rim-exact storage off the cardinal axes.
        val w = NodeWheelOps.get(
            NodeWheelOps.setPuck(neutral, NodeWheelId.LIFT, 0.708f, 0.708f), // r ~ 1.00113
            NodeWheelId.LIFT,
        )
        assertEquals(0.70f, w.x, 1e-6f)
        assertEquals(0.70f, w.y, 1e-6f)
        assertTrue(Math.hypot(w.x.toDouble(), w.y.toDouble()) <= 1.0 + NodeWheelOps.PUCK_STEP)
        // A cardinal just-outside input stays rim-exact: the rescale gives
        // (1, 0) and the snap keeps it there.
        val c = NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.LIFT, 1.0000001f, 0f), NodeWheelId.LIFT)
        assertEquals(1f, c.x, 1e-6f)
        assertEquals(0f, c.y, 1e-6f)
    }

    @Test
    fun large_finite_coordinates_collapse_to_the_rim_grid() {
        // A flung pointer or a unit bug far outside the disc: FINITE float
        // extremes (hypot < Float.MAX) land on the rim as finite grid
        // values — never NaN, never a magnitude past 1.
        val w = NodeWheelOps.get(
            NodeWheelOps.setPuck(neutral, NodeWheelId.OFFSET, 1e18f, 1e18f),
            NodeWheelId.OFFSET,
        )
        assertFalse(w.x.isNaN() || w.x.isInfinite())
        assertFalse(w.y.isNaN() || w.y.isInfinite())
        assertEquals(0.70f, w.x, 1e-6f)
        assertEquals(0.70f, w.y, 1e-6f)
        assertTrue(Math.hypot(w.x.toDouble(), w.y.toDouble()) <= 1.0 + NodeWheelOps.PUCK_STEP)
    }

    @Test
    fun sub_grid_input_snaps_to_a_grid_multiple_including_zero() {
        // The stored value is always an exact multiple of PUCK_STEP (the
        // pad's drag events arrive at pixel granularity): membership both
        // sides of zero, and jitter INSIDE half a grid step still centers.
        fun onGrid(v: Float): Boolean = (v / NodeWheelOps.PUCK_STEP).let { Math.abs(it - it.toDouble().let { u -> Math.round(u).toDouble() }) < 1e-4 }
        for ((x, y) in listOf(0.026f to 0.026f, -0.026f to 0.026f, 0.001f to -0.001f, 0.123f to -0.128f)) {
            val w = NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.LOG_SHADOW, x, y), NodeWheelId.LOG_SHADOW)
            assertTrue("x=$x -> ${w.x}", onGrid(w.x))
            assertTrue("y=$y -> ${w.y}", onGrid(w.y))
        }
        // Inside half a step of zero: the identity position, positive zero.
        val z = NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.LOG_SHADOW, 0.001f, -0.001f), NodeWheelId.LOG_SHADOW)
        assertEquals(0f, z.x, 0f)
        assertEquals(0f, z.y, 0f)
        assertEquals(0, java.lang.Float.floatToRawIntBits(z.x)) // not -0.0
    }

    @Test
    fun the_zero_puck_is_identity_and_neutral_for_every_wheel() {
        for (id in NodeWheelId.entries) {
            assertTrue(NodeWheelOps.isNeutral(NodeWheelOps.setPuck(neutral, id, 0.004f, -0.004f), id))
            assertEquals(NodeWheelV2(), NodeWheelOps.get(NodeWheelOps.setPuck(neutral, id, 0f, 0f), id))
        }
    }

    @Test
    fun master_at_the_clamp_bounds_is_endpoint_valued_and_snaps_inside() {
        // Exactly +/-1 stays exactly on the endpoint (no float drift past
        // the bound); just-inside values snap onto the 0.01 grid.
        for (id in NodeWheelId.entries) {
            assertEquals(1f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, id, 1f), id).master, 0f)
            assertEquals(-1f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, id, -1f), id).master, 0f)
            assertEquals(0.42f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, id, 0.424f), id).master, 1e-6f)
            assertEquals(-0.42f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, id, -0.424f), id).master, 1e-6f)
        }
        // A master far past the clamp lands ON the bound, not past it.
        assertEquals(1f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, NodeWheelId.GAMMA, 89f), NodeWheelId.GAMMA).master, 0f)
        assertEquals(-1f, NodeWheelOps.get(NodeWheelOps.setMaster(neutral, NodeWheelId.GAMMA, -89f), NodeWheelId.GAMMA).master, 0f)
    }

    @Test
    fun disc_colour_clamps_magnitude_beyond_the_rim() {
        // padDiscColor runs on PAD coordinates; a drag event can arrive
        // outside the disc before the clamp. m >= 1 saturates at the rim: a
        // 10x input shows the SAME rim colour as the 1x one.
        val rim = NodeWheelOps.discColor(0.6f, 0.8f) // r = 1
        val far = NodeWheelOps.discColor(6f, 8f)    // r = 10 -> m clamps to 1
        assertEquals(rim.red, far.red, 0.01f)
        assertEquals(rim.green, far.green, 0.01f)
        assertEquals(rim.blue, far.blue, 0.01f)
    }

    @Test
    fun disc_colour_is_continuous_across_the_zero_magnitude_threshold() {
        // The 1e-6 neutral-grey special case must agree with the limit of
        // the general branch — no visible seam at the disc centre.
        val mid = NodeWheelOps.discColor(1e-7f, 0f)
        assertEquals(0.5f, mid.red, 0.01f)
        assertEquals(0.5f, mid.green, 0.01f)
        assertEquals(0.5f, mid.blue, 0.01f)
        val justOver = NodeWheelOps.discColor(1e-5f, 0f)
        assertEquals(0.5f, justOver.red, 0.01f)
        assertEquals(0.5f, justOver.green, 0.01f)
        assertEquals(0.5f, justOver.blue, 0.01f)
    }

    @Test
    fun wraparound_composite_angles_are_the_colour_between_the_primaries() {
        // The wheel wraps continuously: 60deg between red and green is the
        // yellow band (both channels up), 180deg is the negative-red
        // (cyan) point, 300deg the magenta band.
        fun at(deg: Float) = NodeWheelOps.discColor(
            Math.cos(Math.toRadians(deg.toDouble())).toFloat(),
            Math.sin(Math.toRadians(deg.toDouble())).toFloat(),
        )
        val yellow = at(60f)
        assertTrue("yellow r=${yellow.red}", yellow.red > 0.5f && yellow.green > 0.5f && yellow.blue < 0.5f)
        val cyan = at(180f)
        assertTrue("cyan", cyan.red < 0.5f && cyan.blue > 0.5f && cyan.green > 0.5f)
        val magenta = at(300f)
        assertTrue("magenta", m_true(magenta))
        // A wrap both sides of 0 reads the SAME hue: -1deg == 359deg.
        assertEquals(at(1f).red, at(-1f).red, 0.02f)
        assertEquals(at(1f).green, at(-1f).green, 0.02f)
    }

    private fun m_true(c: androidx.compose.ui.graphics.Color) =
        c.red > 0.5f && c.blue > 0.5f && c.green < 0.5f

    @Test
    fun the_disc_edge_is_endpoint_saturated_under_the_format_map() {
        // The 0.75 gain maps the 2/3-magnitude rim without hitting the
        // coerceIn ceiling at the primaries (k * 2/3 = 2/3 -> 0.5 + 0.5 =
        // 1.0): the rim red channel saturates exactly, and the OPPOSITE
        // rim (anti-primary) stays ABOVE the floor — never negative.
        val redRim = NodeWheelOps.discColor(1f, 0f)
        assertEquals(1f, redRim.red, 0.01f)
        val antiRed = NodeWheelOps.discColor(-1f, 0f)
        assertEquals(0f, antiRed.red, 0.01f)
        assertTrue(antiRed.green > 0.45f && antiRed.blue > 0.45f)
    }

    @Test
    fun readout_formats_extremes_and_zeros_without_locale_help() {
        assertEquals("x +1.00  y +1.00", NodeWheelOps.puckReadout(NodeWheelV2(1f, 1f, 0.9f)))
        assertEquals("x -1.00  y -1.00", NodeWheelOps.puckReadout(NodeWheelV2(-1f, -1f, 0.9f)))
        // The STORED path never holds -0.0 (setPuck normalizes), so the
        // readout of every stored identity is +0.00 both axes.
        val stored = NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.LOG_HI, 0.001f, -0.001f), NodeWheelId.LOG_HI)
        assertEquals("x +0.00  y +0.00", NodeWheelOps.puckReadout(stored))
        assertEquals("x +0.05  y -0.05", NodeWheelOps.puckReadout(NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.LIFT, 0.051f, -0.049f), NodeWheelId.LIFT)))
        // A wheel built by hand outside the setter CAN carry a true -0.0:
        // the format keeps the STORE's sign rule (String.format %+.2f) —
        // pinned as-is so a locale fix never silently changes the sign.
        assertEquals("x +0.00  y -0.00", NodeWheelOps.puckReadout(NodeWheelV2(0.0f, -0.0f, 0f)))
    }

    @Test
    fun set_puck_keeps_the_master_untouched_at_the_uneven_grid_extremes() {
        // A dragged puck at diagonal extremes must preserve third-channel
        // state: the master survives every clamp path (rim rescale, snap,
        // coerce).
        val start = NodeWheelOps.set(neutral, NodeWheelId.OFFSET, NodeWheelV2(0.3f, -0.4f, 0.77f))
        val dragged = NodeWheelOps.setPuck(start, NodeWheelId.OFFSET, 40f, -40f).offset
        assertEquals(0.77f, dragged.master, 0f)
        val rim = NodeWheelOps.setPuck(start, NodeWheelId.OFFSET, 1f, 0f).offset
        assertEquals(0.77f, rim.master, 0f)
    }
}
