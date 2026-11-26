package com.ritim.ritimcraw.engine

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ScrubFrameCacheTest {
    private fun k(i: Int, src: String = "a", tier: Int = 320) = ScrubFrameCache.Key(src, i, tier)

    private object FakeCodec : ScrubCodec {
        override fun encode(argb: IntArray, width: Int, height: Int, quality: Int) = ByteArray(10) { argb[0].toByte() }
        override fun decode(bytes: ByteArray) = ScrubPixels(intArrayOf(bytes[0].toInt()), 1, 1)
    }

    private class FakeSource(override val frameCount: Int, val gate: CompletableDeferred<Unit>? = null) : ScrubFrameSource {
        val requested = CopyOnWriteArrayList<Int>()
        override suspend fun frameArgb(index: Int, maxDim: Int): ScrubPixels? {
            requested += index
            gate?.takeIf { index == 0 }?.await()
            return ScrubPixels(intArrayOf(index), 1, 1)
        }
    }

    @Test fun lruEvictsByBytes() {
        val c = ScrubFrameCache(maxBytes = 30)
        c.put(k(1), ByteArray(10)); c.put(k(2), ByteArray(10)); c.put(k(3), ByteArray(10))
        c.get(k(1)) // 2 is now eldest
        c.put(k(4), ByteArray(10))
        assertNull(c.get(k(2)))
        assertNotNull(c.get(k(1)))
        assertEquals(30L, c.sizeBytes)
        c.put(k(9), ByteArray(31)) // oversized ignored
        assertNull(c.get(k(9)))
        assertEquals(30L, c.sizeBytes)
    }

    @Test fun nearestRespectsToleranceAndTier() {
        val c = ScrubFrameCache(1000)
        c.put(k(10), ByteArray(1)); c.put(k(20), ByteArray(1)); c.put(k(15, tier = 160), ByteArray(1))
        assertEquals(10, c.nearest("a", 12, 320, 3)?.first)
        assertEquals(20, c.nearest("a", 18, 320, 3)?.first)
        assertNull(c.nearest("a", 15, 320, 3))
        assertNull(c.nearest("b", 10, 320, 3))
    }

    @Test fun clearBySourceAndAll() {
        val c = ScrubFrameCache(1000)
        c.put(k(1, "a"), ByteArray(5)); c.put(k(1, "b"), ByteArray(7))
        c.clear("a")
        assertNull(c.get(k(1, "a"))); assertNotNull(c.get(k(1, "b")))
        assertEquals(7L, c.sizeBytes)
        c.clearAll()
        assertEquals(0, c.count); assertEquals(0L, c.sizeBytes)
    }

    @Test fun fillerWalksOutwardAtStrideWithinBudget() {
        val ex = Executors.newSingleThreadExecutor()
        try {
            val cache = ScrubFrameCache(10_000)
            val f = ScrubFrameFiller(cache, FakeCodec, ex.asCoroutineDispatcher())
            val src = FakeSource(100)
            f.retarget("a", src, center = 50, stride = 10, maxFrames = 5)
            runBlocking { f.join() }
            assertEquals(listOf(50, 60, 40, 70, 30), src.requested.toList())
            assertEquals(5, cache.count)
            assertEquals(10, ScrubFrameFiller.strideFor(1000, 100))
            f.close()
        } finally { ex.shutdownNow() }
    }

    @Test fun fillerRetargetCancelsPreviousWalk() {
        val ex = Executors.newSingleThreadExecutor()
        try {
            val cache = ScrubFrameCache(10_000)
            val f = ScrubFrameFiller(cache, FakeCodec, ex.asCoroutineDispatcher())
            val gate = CompletableDeferred<Unit>()
            val slow = FakeSource(100, gate)
            f.retarget("a", slow, 0, 1, 10) // suspends on frame 0
            val fast = FakeSource(100)
            f.retarget("b", fast, 80, 1, 3)
            runBlocking { f.join() }
            gate.complete(Unit)
            assertEquals(listOf(80, 81, 79), fast.requested.toList())
            assertNull(cache.nearest("a", 0, 320, 1000)) // cancelled walk stored nothing
            assertEquals(3, cache.count)
            f.cancel(); f.close()
        } finally { ex.shutdownNow() }
    }

    @Test fun standDownCancelsTheWalkButKeepsTheCache() {
        // ScrubFrames.standDown (F2-C3 wiring, playback start): the walk
        // stops — Klipp's ScrubFrames.paused semantics — and the frames
        // already cached STAY (they serve when scrubbing resumes; the
        // facade's onScrub retargets a fresh walk around the new center).
        val ex = Executors.newSingleThreadExecutor()
        try {
            val cache = ScrubFrameCache(10_000)
            val sf = ScrubFrames(cache, FakeCodec, ex.asCoroutineDispatcher())
            val src = FakeSource(100)
            sf.attach("a", src)
            sf.onScrub(50) // retargets the walk at 50
            runBlocking {
                // Wait for the walk to finish storing (bounded by budget's
                // 120 maxFrames at stride 1 on 100 frames = all 100; the
                // poll loop below observes steady state instead).
                var waited = 0
                while (src.requested.size < 3 && waited < 200) {
                    kotlinx.coroutines.delay(10); waited++
                }
            }
            sf.standDown()
            // The cache is NOT cleared by the stand-down (only release
            // clears): whatever the walk stored stays queryable.
            val stored = cache.count
            assertTrue("the walk stored nothing before the stand-down", stored > 0)
            sf.onScrub(50)
            runBlocking {
                var waited = 0
                while (waited < 50) { kotlinx.coroutines.delay(10); waited++ }
            }
            // With play "resumed" the walk re-targets on the next scrub —
            // the stand-down is a CANCEL of the walk, not a latch on the
            // facade (Klipp re-arms its builder the same way).
            assertTrue(cache.count >= stored)
            sf.close()
        } finally { ex.shutdownNow() }
    }

    @Test fun threadSafetySmoke() {
        val c = ScrubFrameCache(500)
        val ts = (0 until 8).map { t ->
            Thread {
                repeat(2000) { i ->
                    c.put(k(i % 50, "s$t"), ByteArray(10))
                    c.nearest("s$t", i % 50, 320, 2); c.get(k(i % 50, "s${(t + 1) % 8}"))
                    if (i % 500 == 0) c.clear("s$t")
                }
            }.also { it.start() }
        }
        ts.forEach { it.join() }
        assertTrue(c.sizeBytes <= 500)
        assertEquals(c.sizeBytes, c.count * 10L)
    }
}
