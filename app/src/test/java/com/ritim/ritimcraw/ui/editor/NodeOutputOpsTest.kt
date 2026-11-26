package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.grade2.OpenDrtParamsV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1-8d: the Output card's pure logic — the mode switch (legacy keeps its block verbatim, opendrt keeps
 * its params), the OpenDRT parameter clamps (core OpenDrt.h's own bounds: look 0..6, tonescale 0..13,
 * cwp 0..6, cwpLimit 0..1, grey 3..25 nits, display 0..2), and the pack resolution's seam shape (the
 * page's edit clamps BEFORE the record, so the wire and the resolver see in-range values only).
 */
class NodeOutputOpsTest {

    // ---- the mode segment ----

    @Test
    fun set_mode_carries_each_branch_verbatim_and_switches_back() {
        // legacy record with a non-neutral legacy block + a tuned drt block
        val legacy = OutputTransformV2(
            mode = OutputTransformV2.MODE_LEGACY,
            legacy = com.ritim.ritimcraw.data.grade2.LegacyOutputV2(
                tonemap = 2, lutId = "cube", lutStrength = 40f,
            ),
            opendrt = OpenDrtParamsV2(look = 3, grey = 14f, display = 1),
        )
        val asDrt = NodeOutputOps.setMode(legacy, OutputTransformV2.MODE_OPENDRT)
        assertEquals(OutputTransformV2.MODE_OPENDRT, asDrt.mode)
        assertEquals(legacy.legacy, asDrt.legacy) // the legacy block rides verbatim (a switch back keeps it)
        assertEquals(legacy.opendrt, asDrt.opendrt) // the drt params ride verbatim (no reset on the switch)
        val back = NodeOutputOps.setMode(asDrt, OutputTransformV2.MODE_LEGACY)
        assertEquals(OutputTransformV2.MODE_LEGACY, back.mode)
        assertEquals(legacy.legacy, back.legacy)
        assertEquals(legacy.opendrt, back.opendrt)
        assertEquals(legacy, back) // a there-and-back is the identity
    }

    @Test
    fun an_unknown_mode_string_falls_back_to_legacy() {
        val start = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT)
        val clamped = NodeOutputOps.setMode(start, "hdr-plus-ultra")
        assertEquals(OutputTransformV2.MODE_LEGACY, clamped.mode)
        assertEquals(start.opendrt, clamped.opendrt)
    }

    // ---- parameter clamps (core OpenDrt.h's own ranges) ----

    @Test
    fun opendrt_setters_clamp_to_core_ranges() {
        val o = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT)
        // look 0..6 (Standard..Umbra)
        assertEquals(6, NodeOutputOps.setLook(o, 9).opendrt.look)
        assertEquals(0, NodeOutputOps.setLook(o, -3).opendrt.look)
        assertEquals(4, NodeOutputOps.setLook(o, 4).opendrt.look)
        // tonescale 0..13 (from-look + Klipp's list)
        assertEquals(13, NodeOutputOps.setTonescale(o, 99).opendrt.tonescale)
        assertEquals(0, NodeOutputOps.setTonescale(o, -1).opendrt.tonescale)
        assertEquals(7, NodeOutputOps.setTonescale(o, 7).opendrt.tonescale)
        // cwp 0..6 (look's + D93..D50)
        assertEquals(6, NodeOutputOps.setCwp(o, 42).opendrt.cwp)
        assertEquals(0, NodeOutputOps.setCwp(o, -2).opendrt.cwp)
        assertEquals(3, NodeOutputOps.setCwp(o, 3).opendrt.cwp)
        // grey 3..25 nits, 0.5 steps
        assertEquals(25f, NodeOutputOps.setGrey(o, 80f).opendrt.grey, 0f)
        assertEquals(3f, NodeOutputOps.setGrey(o, 0f).opendrt.grey, 0f)
        assertEquals(12.5f, NodeOutputOps.setGrey(o, 12.34f).opendrt.grey, 1e-6f) // stepper rounds to half nits
        // display 0..2 (sRGB 2.2 / Rec.1886 / HLG 1000)
        assertEquals(2, NodeOutputOps.setDisplay(o, 7).opendrt.display)
        assertEquals(0, NodeOutputOps.setDisplay(o, -1).opendrt.display)
        assertEquals(1, NodeOutputOps.setDisplay(o, 1).opendrt.display)
    }

    @Test
    fun cwp_limit_is_0_1_and_snaps_like_the_wheels_grid() {
        val o = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT)
        assertEquals(1f, NodeOutputOps.setCwpLimit(o, 1.7f).opendrt.cwpLimit, 1e-6f)
        assertEquals(0f, NodeOutputOps.setCwpLimit(o, -0.4f).opendrt.cwpLimit, 1e-6f)
        // 0.25 grid: 0.33 -> 0.25 (rounds down at the half), 0.4 -> 0.5
        assertEquals(0.25f, NodeOutputOps.setCwpLimit(o, 0.33f).opendrt.cwpLimit, 1e-6f)
        assertEquals(0.5f, NodeOutputOps.setCwpLimit(o, 0.4f).opendrt.cwpLimit, 1e-6f)
        val o2 = OutputTransformV2(
            mode = OutputTransformV2.MODE_OPENDRT,
            opendrt = OpenDrtParamsV2(cwp = 5, cwpLimit = 0.75f),
        )
        assertEquals(0.75f, o2.opendrt.cwpLimit, 0f) // the grid's own values stay exact
    }

    @Test
    fun edits_touch_only_their_own_field() {
        val start = OutputTransformV2(
            mode = OutputTransformV2.MODE_OPENDRT,
            legacy = com.ritim.ritimcraw.data.grade2.LegacyOutputV2(tonemap = 1, lutStrength = 70f),
            opendrt = OpenDrtParamsV2(look = 2, tonescale = 3, cwp = 1, cwpLimit = 0.5f, grey = 12f, display = 2),
        )
        val look = NodeOutputOps.setLook(start, 5)
        assertEquals(5, look.opendrt.look)
        assertEquals(start.legacy, look.legacy)
        assertEquals(start.opendrt.copy(look = 5), look.opendrt)
        val grey = NodeOutputOps.setGrey(start, 18f)
        assertEquals(start.legacy, grey.legacy)
        assertEquals(start.opendrt.copy(grey = 18f), grey.opendrt)
        val disp = NodeOutputOps.setDisplay(start, 0)
        assertEquals(start.legacy, disp.legacy)
        assertEquals(start.opendrt.copy(display = 0), disp.opendrt)
        val cwp = NodeOutputOps.setCwp(start, 6)
        assertEquals(start.legacy, cwp.legacy)
        assertEquals(start.opendrt.copy(cwp = 6), cwp.opendrt)
        val lim = NodeOutputOps.setCwpLimit(start, 0.25f)
        assertEquals(start.legacy, lim.legacy)
        assertEquals(start.opendrt.copy(cwpLimit = 0.25f), lim.opendrt)
        val ts = NodeOutputOps.setTonescale(start, 10)
        assertEquals(start.legacy, ts.legacy)
        assertEquals(start.opendrt.copy(tonescale = 10), ts.opendrt)
    }

    // ---- the editor implies-usage verdict (the K2 lock: legacy output + no nodes = bit-identical v1) ----

    @Test
    fun opendrt_output_alone_implies_v2_usage_even_with_an_empty_chain() {
        // GradeV2Usage: mode opendrt is NODES usage on its own — the UI must never hide the card on a
        // chainless clip (the design's default K3 will be exactly that shape).
        val g = com.ritim.ritimcraw.data.grade2.ClipGradeV2(
            output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT),
        )
        assertEquals(
            com.ritim.ritimcraw.engine.GradeV2Usage.Usage.NODES,
            com.ritim.ritimcraw.engine.GradeV2Usage.usage(g),
        )
        // and the wire carries the empty-chain DRT shape honestly
        val wire = com.ritim.ritimcraw.engine.NodeChainWire.encode(g, emptyList())
        assertEquals(1f, wire[1], 0f) // outputMode 1
        assertEquals(0f, wire[0], 0f) // nodeCount 0
        assertEquals(g.output.opendrt.look.toFloat(), wire[3], 0f)
    }

    @Test
    fun a_legacy_record_with_a_set_drt_block_stills_render_legacy() {
        // The record carries BOTH blocks verbatim; only mode routes. A legacy record whose drt params were
        // tuned earlier is not "half switched" — the wire's outputMode decides and the params ride unread.
        val g = com.ritim.ritimcraw.data.grade2.ClipGradeV2(
            output = OutputTransformV2(
                mode = OutputTransformV2.MODE_LEGACY,
                opendrt = OpenDrtParamsV2(look = 4, grey = 20f),
            ),
        )
        assertTrue(g.nodes.isEmpty())
        val usage = com.ritim.ritimcraw.engine.GradeV2Usage.usage(g)
        assertEquals(com.ritim.ritimcraw.engine.GradeV2Usage.Usage.LEGACY, usage)
        assertFalse(g.output.mode == OutputTransformV2.MODE_OPENDRT)
    }

    // ---- direct vs display readouts (numbers the page labels itself with) ----

    @Test
    fun grey_readout_is_locale_independent() {
        assertEquals("10.0 nit", NodeOutputOps.greyReadout(10f))
        assertEquals("18.5 nit", NodeOutputOps.greyReadout(18.5f))
        assertEquals("3.0 nit", NodeOutputOps.greyReadout(3f))
    }
}
