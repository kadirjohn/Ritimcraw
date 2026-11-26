package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeLutOpsTest {
    private fun lutNode(id: String = "a", mix: Float = 1f, enabled: Boolean = true) =
        GradeNodeV2(lutId = id, lutMix = mix, enabled = enabled)

    @Test fun select_sets_the_cube_and_restores_a_zero_mix() {
        val n = NodeLutOps.select(GradeNodeV2(lutMix = 0f), "cube1")
        assertEquals("cube1", n.lutId)
        assertEquals(1f, n.lutMix, 0f)
        // A set mix survives choosing another cube.
        val m = NodeLutOps.select(GradeNodeV2(lutId = "x", lutMix = 0.4f), "cube2")
        assertEquals(0.4f, m.lutMix, 0f)
    }

    @Test fun none_clears_the_cube_and_resets_the_mix() {
        val n = NodeLutOps.select(lutNode(mix = 0.3f), null)
        assertNull(n.lutId)
        assertEquals(1f, n.lutMix, 0f)
        assertEquals(GradeNodeV2().lutId, NodeLutOps.reset(lutNode()).lutId)
    }

    @Test fun mix_is_clamped_to_0_1() {
        assertEquals(0f, NodeLutOps.withMix(lutNode(), -3f).lutMix, 0f)
        assertEquals(1f, NodeLutOps.withMix(lutNode(), 7f).lutMix, 0f)
        assertEquals(0.25f, NodeLutOps.withMix(lutNode(), 0.25f).lutMix, 0f)
    }

    @Test fun a_lut_at_zero_mix_is_not_live_and_does_not_count() {
        assertFalse(NodeLutOps.hasLiveLut(lutNode(mix = 0f)))
        assertEquals(0, NodeLutOps.liveLutCount(listOf(lutNode(mix = 0f), GradeNodeV2())))
    }

    @Test fun the_cap_is_four_enabled_nodes_with_a_live_lut() {
        val four = List(4) { lutNode("c$it") } + GradeNodeV2()           // 5 nodes, the 5th has no LUT
        assertEquals(4, NodeLutOps.liveLutCount(four))
        assertFalse("a fifth LUT is refused", NodeLutOps.canAssign(four, 4))
        assertTrue("a node that already has one can always change its choice", NodeLutOps.canAssign(four, 2))
        assertTrue("below the cap a plain node may take one", NodeLutOps.canAssign(four.take(3) + GradeNodeV2(), 3))
    }

    @Test fun a_disabled_node_never_counts_toward_the_cap() {
        val nodes = List(4) { lutNode("c$it") } + GradeNodeV2(enabled = false) + lutNode("d", enabled = false)
        assertEquals(4, NodeLutOps.liveLutCount(nodes))
        assertTrue("a disabled node may take a LUT (it is not counted until it is enabled)", NodeLutOps.canAssign(nodes, 4))
        assertFalse(NodeLutOps.canAssign(nodes, 99))
    }
}
