package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The render pipeline's pending slot (owner request 2026-11-10 §1): at most
 * one pending render, newest replaces the stored one, poll empties the slot.
 * Pure JVM tests — no coroutines, no native lib.
 */
class RenderGateTest {

    private fun request(seq: Long, frameIndex: Int = 0, maxDim: Int = 1280) =
        RenderRequest(frameIndex, GradeK(), maxDim, seq)

    @Test
    fun offer_into_empty_slot_returns_null_and_pends() {
        val gate = RenderGate()
        val r = request(1)
        assertNull(gate.offer(r))
        assertTrue(gate.hasPending)
    }

    @Test
    fun newest_request_replaces_pending_one() {
        val gate = RenderGate()
        val first = request(1, frameIndex = 10)
        val second = request(2, frameIndex = 20)
        gate.offer(first)
        val replaced = gate.offer(second)
        assertEquals(first, replaced)
        assertEquals(second, gate.poll())
    }

    @Test
    fun poll_takes_the_request_and_empties_the_slot() {
        val gate = RenderGate()
        val r = request(3)
        gate.offer(r)
        assertEquals(r, gate.poll())
        assertFalse(gate.hasPending)
        assertNull(gate.poll())
    }

    @Test
    fun rapid_offers_coalesce_to_last() {
        // A drag fires offers far faster than renders complete; every offer
        // replaces the pending one, so only the LAST survives.
        val gate = RenderGate()
        var seq = 0L
        repeat(50) { i -> gate.offer(request(seq++, frameIndex = i)) }
        val drained = gate.poll()
        assertEquals(49, drained?.frameIndex)
        assertNull(gate.poll())
    }

    @Test
    fun clear_drops_pending_without_running_it() {
        val gate = RenderGate()
        gate.offer(request(4))
        gate.clear()
        assertFalse(gate.hasPending)
        assertNull(gate.poll())
    }
}
