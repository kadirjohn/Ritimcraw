package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EncodedToneBridgeTest {

    private fun wheelMasterSlot(wire: FloatArray, node: Int, slot: Int): Float =
        wire[NodeChainWire.HEADER + node * NodeChainWire.STRIDE + slot + 2]

    @Test
    fun zero_sliders_keep_the_wire_and_the_fast_path() {
        assertNull(EncodedToneBridge.apply(0f, 0f, null))
        val w = NodeChainWire.encode(ClipGradeV2(), listOf(GradeNodeV2(sat = 1.2f)))
        assertSame(w, EncodedToneBridge.apply(0f, 0f, w))
    }

    @Test
    fun sliders_append_one_neutral_node_with_the_log_wheel_masters() {
        val w = EncodedToneBridge.apply(-50f, 25f, null)!!
        assertEquals(1f, w[0])
        assertEquals(NodeChainWire.HEADER + NodeChainWire.STRIDE + 6, w.size) // header + 1 node + output tail
        assertEquals(-0.5f, wheelMasterSlot(w, 0, NodeChainWire.SLOT_LOG_HI), 1e-6f)
        assertEquals(0.25f, wheelMasterSlot(w, 0, NodeChainWire.SLOT_LOG_SHADOW), 1e-6f)
        assertEquals(0f, wheelMasterSlot(w, 0, NodeChainWire.SLOT_LOG_MID), 0f)
        assertEquals(1f, w[NodeChainWire.HEADER + NodeChainWire.SLOT_ENABLED])
    }

    @Test
    fun the_node_goes_last_and_the_curve_block_and_tail_survive() {
        val curved = GradeNodeV2(curveY = NodeCurveV2(listOf(0f to 0f, 0.5f to 0.6f, 1f to 1f)))
        val plain = GradeNodeV2(sat = 1.3f)
        val base = NodeChainWire.encode(ClipGradeV2(), listOf(plain, curved))
        val out = EncodedToneBridge.apply(100f, 0f, base)!!
        assertEquals(3f, out[0])
        assertEquals(base[2], out[2]) // the curve pair total is unchanged: the new node has no curves
        val at = NodeChainWire.HEADER + 2 * NodeChainWire.STRIDE
        // The user's two node records, the new record, then the original tail (curve block + output) unchanged.
        assertArrayEquals(base.copyOfRange(NodeChainWire.HEADER, at), out.copyOfRange(NodeChainWire.HEADER, at), 0f)
        assertArrayEquals(base.copyOfRange(at, base.size), out.copyOfRange(at + NodeChainWire.STRIDE, out.size), 0f)
        assertEquals(1f, wheelMasterSlot(out, 2, NodeChainWire.SLOT_LOG_HI), 1e-6f)
    }

    @Test
    fun a_full_chain_is_left_alone() {
        val full = NodeChainWire.encode(ClipGradeV2(), List(NodeChainWire.MAX_NODES) { GradeNodeV2() })
        assertSame(full, EncodedToneBridge.apply(-30f, 10f, full))
    }

    @Test
    fun slider_percent_clamps_to_the_wheel_master_range() {
        assertEquals(1f, EncodedToneBridge.wheelMaster(250f), 0f)
        assertEquals(-1f, EncodedToneBridge.wheelMaster(-250f), 0f)
        assertEquals(0.4f, EncodedToneBridge.wheelMaster(40f), 1e-6f)
    }
}
