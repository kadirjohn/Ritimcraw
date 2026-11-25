package com.ritim.ritimcraw.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProxyCacheTest {
    private lateinit var root: File
    private lateinit var storage: DirProxyStorage
    private var now = 1_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("proxycache").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun cache(cap: Long = 1000) = ProxyCache(storage, cap) { now += 10_000; now }

    private fun sig(fp: String = "abc123", settings: ProxySettings = ProxySettings()) = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        settings = settings, colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun put(c: ProxyCache, key: ProxyKey, bytes: Int): ProxyEntry? {
        val h = c.begin(key)
        h.videoFile.writeBytes(ByteArray(bytes) { 1 })
        return h.commit(durationUs = 1_000_000, frameCount = 30)
    }

    @Test fun signatureIsStableAndFilenameSafe() {
        val a = sig(); val b = sig()
        assertEquals(a.canonical(), b.canonical())
        assertEquals(a.key(), b.key())
        assertEquals(16, a.hash().length)
        assertTrue(Regex("[0-9a-f]{16}").matches(a.hash()))
        assertEquals("abc123-${a.hash()}", a.key().baseName)
    }

    @Test fun signatureChangesWithEveryField() {
        val base = sig()
        val variants = listOf(
            base.copy(sourceFingerprint = "abc124"), base.copy(sourceSize = 101), base.copy(sourceModifiedMs = 6),
            base.copy(settings = ProxySettings(shortSide = 720)), base.copy(settings = ProxySettings(bitrateClass = "hi")),
            base.copy(settings = ProxySettings(keyFrameIntervalSec = 2)), base.copy(colorSpecKey = "bt709-limited"),
            base.copy(kind = ProxySourceKind.MCRAW), base.copy(mcrawTransferVersion = 1),
            base.copy(encoderVersion = base.encoderVersion + 1), base.copy(headroomAffine = true),
        )
        val hashes = (variants + base).map { it.hash() }
        assertEquals(hashes.size, hashes.toSet().size)
    }

    @Test fun commitPublishesAndLookupResolves() {
        val c = cache(); val k = sig().key()
        val e = put(c, k, 100)
        assertNotNull(e)
        val hit = c.lookup(k)
        assertNotNull(hit)
        assertEquals(100L, hit!!.bytes)
        assertEquals(30L, hit.frameCount)
        assertTrue(hit.file.isFile)
        // no tmp left behind, only the published pair
        assertEquals(listOf("${k.baseName}.manifest", "${k.baseName}.mp4"), storage.names())
    }

    @Test fun abortLeavesNothingAndNeverResolves() {
        val c = cache(); val k = sig().key()
        val h = c.begin(k)
        h.videoFile.writeBytes(ByteArray(50))
        assertNull(c.lookup(k)) // a .tmp never resolves
        h.abort()
        assertTrue(storage.names().isEmpty())
        assertNull(c.lookup(k))
    }

    @Test fun crashLeftoversNeverResolveAndSweepRemovesThem() {
        val k = sig().key()
        // simulated crash 1: tmp video only. crash 2: video renamed but manifest not published.
        storage.write("${k.baseName}.mp4.tmp", ByteArray(40))
        val k2 = sig("def456").key()
        storage.write("${k2.baseName}.mp4", ByteArray(40))
        storage.write("${k2.baseName}.manifest.tmp", "fingerprint=def456\n".toByteArray())
        val c = cache() // fresh process: no open handles
        assertNull(c.lookup(k))
        assertNull(c.lookup(k2))
        assertEquals(3, c.sweep())
        assertTrue(storage.names().isEmpty())
    }

    @Test fun sweepSparesTmpOfOpenHandleAndLookupRejectsSizeMismatch() {
        val c = cache(); val k = sig().key()
        val h = c.begin(k)
        h.videoFile.writeBytes(ByteArray(10))
        assertEquals(0, c.sweep())
        assertTrue(h.videoFile.isFile)
        h.commit()
        // truncate the published video: manifest size check must reject it
        File(root, "${k.baseName}.mp4").writeBytes(ByteArray(3))
        assertNull(c.lookup(k))
    }

    @Test fun recommitReplacesAtomicallyAndEmptyWriteIsRejected() {
        val c = cache(); val k = sig().key()
        put(c, k, 100)
        put(c, k, 60)
        assertEquals(60L, c.lookup(k)!!.bytes)
        val h = c.begin(k); // nothing written
        assertNull(h.commit())
        assertEquals(60L, c.lookup(k)?.bytes) // old entry untouched by an empty commit
    }

    @Test fun lruEvictsOldestToCapAndSparesJustCommitted() {
        val c = cache(cap = 250)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kc = sig("c1").key()
        put(c, ka, 100); put(c, kb, 100)
        assertEquals(200L, c.totalBytes())
        put(c, kc, 100) // 300 > 250 -> evict least recently used = a
        assertNull(c.peek(ka))
        assertNotNull(c.peek(kb)); assertNotNull(c.peek(kc))
        assertTrue(c.totalBytes() <= 250)
    }

    @Test fun lookupTouchesLastAccess() {
        val c = cache(cap = 250)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kc = sig("c1").key()
        put(c, ka, 100); put(c, kb, 100)
        val before = c.peek(ka)!!.lastAccessMs
        assertNotNull(c.lookup(ka)) // a is now newer than b
        assertTrue(c.peek(ka)!!.lastAccessMs > before)
        put(c, kc, 100)
        assertNotNull(c.peek(ka)); assertNull(c.peek(kb)) // b evicted instead
    }

    @Test fun oversizeEntryIsNotPublished() {
        val c = cache(cap = 100)
        assertNull(put(c, sig().key(), 101))
        assertTrue(storage.names().isEmpty())
    }

    @Test fun sizesPerClipTotalAndClear() {
        val c = cache()
        val s1 = sig("a1"); val s2 = sig("a1", ProxySettings(shortSide = 720)); val s3 = sig("b1")
        put(c, s1.key(), 100); put(c, s2.key(), 50); put(c, s3.key(), 70)
        assertEquals(150L, c.bytesForClip("a1"))
        assertEquals(70L, c.bytesForClip("b1"))
        assertEquals(220L, c.totalBytes())
        assertTrue(c.clear(s2.key()))
        assertEquals(100L, c.bytesForClip("a1"))
        // a leftover tmp plus entries: clearAll removes everything
        storage.write("zzz-1.mp4.tmp", ByteArray(5))
        c.clearAll()
        assertTrue(storage.names().isEmpty())
        assertEquals(0L, c.totalBytes())
    }

    @Test fun policyAutoGenerationDefaultsAndMcrawOptIn() {
        val def = ProxyPolicy()
        assertEquals(20L shl 30, def.capBytes)
        fun enc(w: Int, h: Int, fps: Double) = ProxyClipInfo(ProxySourceKind.ENCODED, w, h, fps)
        assertTrue(def.shouldAutoGenerate(enc(7680, 4320, 30.0)))   // 8K
        assertTrue(def.shouldAutoGenerate(enc(3840, 2160, 60.0)))   // 4K60
        assertFalse(def.shouldAutoGenerate(enc(3840, 2160, 30.0)))  // 4K30
        assertFalse(def.shouldAutoGenerate(enc(1920, 1080, 120.0))) // small stays off
        val mcraw = ProxyClipInfo(ProxySourceKind.MCRAW, 4000, 3000, 24.0)
        assertFalse(def.shouldAutoGenerate(mcraw))
        assertTrue(def.copy(autoMcraw = true).shouldAutoGenerate(mcraw))
        assertFalse(def.copy(autoEncoded = false).shouldAutoGenerate(enc(7680, 4320, 30.0)))
    }

    @Test fun stateMapQueuePauseRequeueAndFailures() {
        val m = ProxyStateMap()
        val a = sig("a1").key(); val b = sig("b1").key()
        assertEquals(ProxyState.None, m.state(a))
        assertTrue(m.enqueue(a)); assertTrue(m.enqueue(b, front = true))
        assertFalse(m.enqueue(a))
        m.pause(); assertNull(m.next()); m.resume()
        assertEquals(b, m.next()); assertEquals(ProxyState.Building, m.state(b))
        m.requeue(b) // export started: back to the head
        assertEquals(listOf(b, a), m.queued())
        assertEquals(b, m.next()); m.failed(b, "no hevc"); m.ready(a.also { m.next() })
        assertEquals(ProxyState.Failed, m.state(b)); assertEquals(1, m.failureCount(b))
        assertEquals(ProxyState.Ready, m.state(a))
        assertNotEquals(ProxyState.Queued, m.state(a))
    }
}
