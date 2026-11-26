package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** NodeWheelOps overflow clamp: every wheel value that enters the editor model or readout stays in range and finite. */
class NodeWheelClampTest {
    private val neutral = GradeNodeV2()
    private val bad = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

    private fun finite(v: Float) = !v.isNaN() && !v.isInfinite()
    private fun inRange(w: NodeWheelV2) =
        finite(w.x) && finite(w.y) && finite(w.master) &&
            Math.hypot(w.x.toDouble(), w.y.toDouble()) <= 1.0 + 1e-6 && w.master in -1f..1f

    @Test
    fun valid_values_are_untouched_bit_for_bit() {
        val vals = listOf(0f, -0f, 0.05f, -0.35f, 0.7f, 1f, -1f, 0.123456f)
        for (x in vals) for (y in vals) for (m in vals) {
            if (Math.hypot(x.toDouble(), y.toDouble()) > 1.0) continue
            val w = NodeWheelV2(x, y, m)
            assertSame(w, NodeWheelOps.sanitize(w))
            val got = NodeWheelOps.get(NodeWheelOps.set(neutral, NodeWheelId.GAIN, w), NodeWheelId.GAIN)
            assertEquals(x.toRawBits(), got.x.toRawBits())
            assertEquals(y.toRawBits(), got.y.toRawBits())
            assertEquals(m.toRawBits(), got.master.toRawBits())
        }
    }

    @Test
    fun persisted_nan_and_infinity_become_zero_on_read() {
        for (b in bad) for (id in NodeWheelId.entries) {
            val w = NodeWheelOps.get(NodeWheelOps.set(neutral, id, NodeWheelV2(b, 0.2f, b)), id)
            assertEquals(0f, w.x, 0f)
            assertEquals(0.2f, w.y, 0f)
            assertEquals(0f, w.master, 0f)
        }
        // the raw stored node (as parsed from an odd project), read through get
        for (b in bad) {
            val rawNode = neutral.copy(lift = NodeWheelV2(b, b, b))
            assertEquals(NodeWheelV2(), NodeWheelOps.get(rawNode, NodeWheelId.LIFT))
        }
    }

    @Test
    fun persisted_puck_past_the_rim_is_pulled_inside_and_master_clamped() {
        val node = neutral.copy(gamma = NodeWheelV2(3f, 4f, 7f), gain = NodeWheelV2(-2f, 0f, -9f))
        val g = NodeWheelOps.get(node, NodeWheelId.GAMMA)
        assertEquals(0.6f, g.x, 1e-6f)
        assertEquals(0.8f, g.y, 1e-6f)
        assertEquals(1f, g.master, 0f)
        val n = NodeWheelOps.get(node, NodeWheelId.GAIN)
        assertEquals(-1f, n.x, 1e-6f)
        assertEquals(-1f, n.master, 0f)
        assertTrue(inRange(g) && inRange(n))
    }

    @Test
    fun huge_finite_persisted_values_stay_in_range() {
        val w = NodeWheelOps.sanitize(NodeWheelV2(Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE))
        assertTrue(inRange(w))
        assertEquals(1f, w.master, 0f)
    }

    @Test
    fun setters_never_throw_or_store_nan_and_infinity() {
        for (b in bad) for (c in listOf(0f, 0.5f, b)) {
            val p = NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.LIFT, b, c), NodeWheelId.LIFT)
            assertTrue(inRange(p))
            val m = NodeWheelOps.get(NodeWheelOps.setMaster(neutral, NodeWheelId.LIFT, b), NodeWheelId.LIFT)
            assertEquals(0f, m.master, 0f)
        }
    }

    @Test
    fun snap_of_extremes_stays_in_range() {
        for (v in listOf(1e30f, -1e30f, Float.MAX_VALUE, 1e-30f)) {
            assertTrue(inRange(NodeWheelOps.get(NodeWheelOps.setPuck(neutral, NodeWheelId.OFFSET, v, v), NodeWheelId.OFFSET)))
            assertTrue(inRange(NodeWheelOps.get(NodeWheelOps.setMaster(neutral, NodeWheelId.OFFSET, v), NodeWheelId.OFFSET)))
        }
    }

    @Test
    fun readouts_are_finite_and_in_range_for_bad_wheels() {
        assertEquals("x +0.00  y +0.00", NodeWheelOps.puckReadout(NodeWheelV2(Float.NaN, Float.POSITIVE_INFINITY, 0f)))
        assertEquals("x +0.60  y +0.80", NodeWheelOps.puckReadout(NodeWheelV2(3f, 4f, 0f)))
        assertEquals("+0", NodeWheelOps.masterReadout(Float.NaN))
        assertEquals("+100", NodeWheelOps.masterReadout(55f))
        assertEquals("-100", NodeWheelOps.masterReadout(-5f))
        assertEquals("+25", NodeWheelOps.masterReadout(0.25f))
        assertEquals("-50", NodeWheelOps.masterReadout(-0.5f))
    }

    @Test
    fun disc_colour_of_nan_and_infinity_is_a_valid_colour() {
        for (b in bad) {
            val c = NodeWheelOps.discColor(b, b)
            assertFalse(c.red.isNaN() || c.green.isNaN() || c.blue.isNaN())
            assertEquals(0.5f, c.red, 0.01f)
            val p = NodeWheelOps.padDiscColor(b, 0.3f)
            assertFalse(p.red.isNaN() || p.green.isNaN() || p.blue.isNaN())
        }
    }

    @Test
    fun reset_and_neutral_checks_work_on_a_corrupt_wheel() {
        val node = neutral.copy(lift = NodeWheelV2(Float.NaN, Float.NaN, Float.NaN))
        assertTrue(NodeWheelOps.isNeutral(node, NodeWheelId.LIFT))
        assertEquals(NodeWheelV2(), NodeWheelOps.get(NodeWheelOps.resetWheel(node, NodeWheelId.LIFT), NodeWheelId.LIFT))
    }
}
