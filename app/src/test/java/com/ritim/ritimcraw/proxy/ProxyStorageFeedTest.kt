package com.ritim.ritimcraw.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * G3 storage-page state tests, all JVM-pure (the brief's "depolama sayfası
 * durum fonksiyonları (saf, JVM testli)"): the feed assembles the P1 usage
 * numbers, the per-clip bytes, the queue's own states and the honest failure
 * reasons — the UI never recomputes any of it.
 */
class ProxyStorageFeedTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @Before fun setUp() {
        root = Files.createTempDirectory("proxystore").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun sig(fp: String) = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun put(c: ProxyCache, s: ProxySignature, bytes: Int): ProxyEntry? {
        val h = c.begin(s)
        h.videoFile.writeBytes(ByteArray(bytes) { 1 })
        return h.commit(durationUs = 1_000_000, frameCount = 30)
    }

    private fun state(
        c: ProxyCache = ProxyCache(storage),
        q: ProxyStateMap = ProxyStateMap(),
        cap: Long = 1000,
        inUse: ProxyProtectSource = ProxyProtectSource.NONE,
    ) = ProxyStorageFeed.state(c, q, cap, inUse, q.buildingSource())

    @Test fun anEmptyCacheShowsZeroBytesAndNoClips() {
        val s = state()
        assertEquals(0L, s.usage.totalBytes)
        assertTrue(s.perClip.isEmpty())
        assertTrue(s.queued.isEmpty())
        assertTrue(s.building.isEmpty())
        assertTrue(s.failed.isEmpty())
        assertFalse(s.usage.isOverCap)
    }

    @Test fun perClipBytesSumEverySignatureOfOneFingerprint() {
        // one clip with TWO signature variants (a settings change left the old
        // proxy behind): the row shows the SUM, not the last one
        val c = ProxyCache(storage)
        put(c, sig("a1"), 100)
        put(c, sig("a1").copy(sourceSize = 200), 50)
        put(c, sig("b1"), 300)
        val s = state(c = c)
        assertEquals(2, s.perClip.size)
        assertEquals("b1", s.perClip[0].fingerprint)  // largest first: 300 > 150
        assertEquals(300L, s.perClip[0].bytes)
        assertEquals("a1", s.perClip[1].fingerprint)
        assertEquals(150L, s.perClip[1].bytes)
        assertEquals(450L, s.usage.totalBytes)
    }

    @Test fun overCapCarriesTheHonestOverageAndTheProtectedShare() {
        val c = ProxyCache(storage, 1000)
        val inUse = ProxyInUse()
        val ka = sig("a1")
        put(c, ka, 400)
        put(c, sig("b1"), 400)
        inUse.acquire(ka.key())
        val s = state(c = c, cap = 500, inUse = inUse)
        assertTrue(s.usage.isOverCap)
        assertEquals(300L, s.usage.overCapBytes)
        assertEquals(400L, s.usage.protectedBytes)  // the playing clip's bytes
    }

    @Test fun queueStatesSurfaceAsHonestSetLines() {
        val c = ProxyCache(storage)
        val q = ProxyStateMap()
        val ka = sig("a1"); val kb = sig("b1"); val kc = sig("c1")
        q.enqueue(ka.key())          // queued (back lane)
        q.enqueue(kb.key(), front = true)
        q.next()                     // kb pops -> Building
        q.enqueue(kc.key())
        q.next()                     // ka pops -> Building too (one at a time in
                                      // the real runner; here both read as Building)
        q.failed(kc.key(), "no video/hevc encoder on this device")
        val s = state(c = c, q = q)
        assertEquals(setOf("c1"), s.failed.keys)
        assertEquals("no video/hevc encoder on this device", s.failed["c1"])
        assertTrue("a1" in s.building || "a1" in s.queued)  // the queue's own truth
        assertTrue("b1" in s.building)
    }

    @Test fun theFeedReadsTheCacheWithoutTouchingLastAccess() {
        // LRU honesty: building the page's state must not refresh any clip's
        // last-access (the E2 peek rule) — a later eviction still sees the
        // real access order
        val c = ProxyCache(storage, 10_000)
        var now = 1_000_000L
        val clockCache = ProxyCache(storage, 10_000) { now += 10_000; now }
        val ka = sig("a1")
        put(clockCache, ka, 100)
        val before = clockCache.playable(ka.key())!!.lastAccessMs
        now += 100_000L
        val s = state(c = clockCache)
        assertEquals(before, clockCache.peekPlayable(ka.key())!!.lastAccessMs)
        assertEquals(100L, s.usage.totalBytes)
    }
}
