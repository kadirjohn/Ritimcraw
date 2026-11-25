package com.ritim.ritimcraw.data.grade2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeListOpsTest {
    private fun node(sat: Float, enabled: Boolean = true) = GradeNodeV2(sat = sat, enabled = enabled)

    @Test
    fun add_appends_an_identity_node_until_the_limit() {
        var l = emptyList<GradeNodeV2>()
        repeat(NodeListOps.MAX_NODES) {
            assertNull(NodeListOps.addBlockedReason(l))
            l = NodeListOps.add(l)
        }
        assertEquals(8, l.size)
        assertTrue(l.all { it == GradeNodeV2() })
        assertEquals("limit", NodeListOps.addBlockedReason(l))
        assertSame("a blocked add returns the same instance", l, NodeListOps.add(l))
    }

    @Test
    fun duplicate_inserts_a_copy_after_the_source_and_respects_the_limit() {
        val l = listOf(node(1.1f), node(1.2f), node(1.3f))
        val d = NodeListOps.duplicate(l, 1)
        assertEquals(listOf(1.1f, 1.2f, 1.2f, 1.3f), d.map { it.sat })
        val full = List(8) { node(it.toFloat()) }
        assertSame(full, NodeListOps.duplicate(full, 0))
        assertSame(l, NodeListOps.duplicate(l, 3))
    }

    @Test
    fun delete_removes_and_the_selection_moves_to_the_previous_node() {
        val l = listOf(node(1f), node(2f), node(3f))
        assertEquals(listOf(1f, 3f), NodeListOps.delete(l, 1).map { it.sat })
        assertEquals(0, NodeListOps.selectionAfterDelete(1, 2))
        assertEquals(0, NodeListOps.selectionAfterDelete(0, 2))
        assertEquals(1, NodeListOps.selectionAfterDelete(2, 2))
        assertEquals(-1, NodeListOps.selectionAfterDelete(0, 0))
        assertSame(l, NodeListOps.delete(l, 5))
        assertSame(l, NodeListOps.delete(l, -1))
    }

    @Test
    fun toggle_flips_only_the_flag_and_keeps_the_parameters() {
        val l = listOf(node(1.4f))
        val off = NodeListOps.toggleEnabled(l, 0)
        assertFalse(off[0].enabled)
        assertEquals(1.4f, off[0].sat)
        assertTrue(NodeListOps.toggleEnabled(off, 0)[0].enabled)
        assertNotSame(l, off)
    }

    @Test
    fun reset_returns_to_identity_but_keeps_a_disabled_node_disabled() {
        val l = listOf(node(1.4f, enabled = false), node(1.2f))
        val r = NodeListOps.reset(l, 0)
        assertEquals(GradeNodeV2(enabled = false), r[0])
        assertEquals(1.2f, r[1].sat)
        assertEquals(GradeNodeV2(), NodeListOps.reset(l, 1)[1])
    }

    @Test
    fun move_reorders_and_rejects_bad_indices() {
        val l = listOf(node(1f), node(2f), node(3f))
        assertEquals(listOf(2f, 3f, 1f), NodeListOps.move(l, 0, 2).map { it.sat })
        assertEquals(listOf(3f, 1f, 2f), NodeListOps.move(l, 2, 0).map { it.sat })
        assertSame(l, NodeListOps.move(l, 1, 1))
        assertSame(l, NodeListOps.move(l, 0, 3))
    }

    @Test
    fun names_are_trimmed_cut_at_40_characters_and_blank_means_default() {
        assertNull(NodeListOps.normalizeName(null))
        assertNull(NodeListOps.normalizeName("   "))
        assertEquals("Sky", NodeListOps.normalizeName("  Sky "))
        val long = "a".repeat(55)
        assertEquals(40, NodeListOps.normalizeName(long)!!.length)
        // 40 CHARACTERS, not 40 UTF-16 units: an emoji pair counts once.
        val emoji = "\uD83D\uDE00".repeat(45)
        assertEquals(40, NodeListOps.normalizeName(emoji)!!.codePointCount(0, NodeListOps.normalizeName(emoji)!!.length))
        val l = listOf(node(1f))
        assertEquals("Sky", NodeListOps.rename(l, 0, " Sky ")[0].name)
        assertSame(l, NodeListOps.rename(l, 0, null))
        assertSame(l, NodeListOps.rename(l, 4, "x"))
    }

    @Test
    fun a_name_survives_the_v2_json_round_trip_and_an_absent_name_writes_no_key() {
        val g = ClipGradeV2(nodes = listOf(GradeNodeV2(name = "Sky \"A\""), GradeNodeV2()))
        val json = GradeV2Fiber.render(g)
        @Suppress("UNCHECKED_CAST")
        val back = ClipGradeV2.parse(com.ritim.ritimcraw.data.JsonParser(json).parseValue() as Map<String, Any?>)
        assertEquals("Sky \"A\"", back.nodes[0].name)
        assertNull(back.nodes[1].name)
        // The second node has no name: exactly one "name" key in the whole record.
        assertEquals(1, Regex("\"name\":").findAll(json).count())
        // An unnamed grade renders byte-identically to a grade built before the field existed.
        val plain = ClipGradeV2(nodes = listOf(GradeNodeV2()))
        assertEquals(false, GradeV2Fiber.render(plain).contains("\"name\""))
    }
}
