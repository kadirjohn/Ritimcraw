package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import com.ritim.ritimcraw.engine.GradeV2Usage
import com.ritim.ritimcraw.engine.NodeChainWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The G4 chain fold ([ExportChainFold.forHlg]): the pure re-target of the
 * snapshot's v2 chain wire to the export's own output transform. The
 * HLG export's develop must run the DRT at display = 2 (Hlg1000) — the
 * wire's display slot otherwise carries the EDITOR's preview display.
 */
class ExportChainFoldTest {

    /** One minimal OpenDRT chain wire (outputMode 1, display 0 = sRGB 2.2). */
    private fun drtWire(display: Int = 0): FloatArray {
        val grade = ClipGradeV2().copy(
            output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT),
        )
        return NodeChainWire.encode(grade, emptyList()).let { w ->
            w.also { it[it.size - NodeChainWire.OUTPUT + NodeChainWire.SLOT_OUT_DISPLAY] = display.toFloat() }
        }
    }

    @Test
    fun a_null_wire_stays_null_never_fabricates_a_chain() {
        assertNull(ExportChainFold.forHlg(null, forceHlg = true))
        assertNull(ExportChainFold.forHlg(null, forceHlg = false))
    }

    @Test
    fun force_hlg_moves_an_opendrt_wire_s_display_to_hlg_1000() {
        val wire = drtWire(display = 0) // the editor's sRGB 2.2 preview
        val folded = ExportChainFold.forHlg(wire, forceHlg = true)!!
        // The display slot IS HLG 1000 (OpenDrtDisplay's ordinal 2)...
        val slot = folded.size - NodeChainWire.OUTPUT + NodeChainWire.SLOT_OUT_DISPLAY
        assertEquals(2f, folded[slot])
        // ...and nothing else moved (copy-on-write: the ORIGINAL is intact).
        assertEquals(wire.size, folded.size)
        assertEquals(0f, wire[slot])
        var diffs = 0
        for (i in wire.indices) if (wire[i] != folded[i]) diffs++
        assertEquals(1, diffs) // exactly the display slot
    }

    @Test
    fun an_sdr_export_s_chain_is_the_same_instance_byte_identical() {
        val wire = drtWire(display = 0)
        // forceHlg false: NO copy — today's behavior for every SDR export.
        assertSame(wire, ExportChainFold.forHlg(wire, forceHlg = false))
    }

    @Test
    fun an_already_hlg_wire_needs_no_copy() {
        val wire = drtWire(display = ExportChainFold.DISPLAY_HLG_1000)
        assertSame(wire, ExportChainFold.forHlg(wire, forceHlg = true))
    }

    @Test
    fun a_legacy_output_chain_is_returned_unchanged_the_display_slot_is_ignored() {
        // outputMode 0 (a plain NODES chain): the display slot is IGNORED
        // by the wire's own layout contract — touching it would be a lie.
        val grade = ClipGradeV2() // default mode = legacy
        val wire = NodeChainWire.encode(grade, listOf(GradeNodeV2()))
        assertSame(wire, ExportChainFold.forHlg(wire, forceHlg = true))
    }

    @Test
    fun the_fold_is_pure_the_same_wire_gives_the_same_fold() {
        val a = ExportChainFold.forHlg(drtWire(1), forceHlg = true)
        val b = ExportChainFold.forHlg(drtWire(1), forceHlg = true)
        assertTrue(a!!.contentEquals(b))
    }
}
