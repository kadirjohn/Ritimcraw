package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaneBufferPoolTest {
    @Test
    fun steady_state_allocates_nothing_after_warm_up() {
        val pool = PlaneBufferPool()
        val sizes = intArrayOf(1000, 500, 500)
        repeat(3) {
            val held = sizes.map { pool.acquire(it) }
            held.forEach { pool.release(it) }
        }
        // First round allocated three arrays; the other rounds reused them.
        assertEquals(3, pool.allocations)
        assertEquals(3, pool.idle)
    }

    @Test
    fun a_released_array_comes_back_for_the_same_size_only() {
        val pool = PlaneBufferPool()
        val a = pool.acquire(64)
        pool.release(a)
        assertSame(a, pool.acquire(64))
        pool.release(a)
        val other = pool.acquire(65)
        assertTrue(other !== a)
        assertEquals(2, pool.allocations)
    }

    @Test
    fun the_pool_never_holds_more_than_max_free_and_clear_empties_it() {
        val pool = PlaneBufferPool(maxFree = 2)
        val arrays = List(5) { pool.acquire(10) }
        arrays.forEach { pool.release(it) }
        assertEquals(2, pool.idle)
        pool.clear()
        assertEquals(0, pool.idle)
    }
}
