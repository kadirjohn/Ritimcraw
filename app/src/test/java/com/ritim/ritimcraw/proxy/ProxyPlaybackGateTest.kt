package com.ritim.ritimcraw.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * G3 playback-gate wiring tests, all JVM-pure over the REAL P1 cache (the
 * brief's item 2, "oynatma entegrasyonu: yalnız tamamlanmış+doğrulanmış proxy,
 * anahtar kare sınırında geçiş"): the session-open resolution, the in-use
 * protection's acquire/release, the never-half truth (a tmp or a torn
 * manifest opens the ORIGINAL) and the E2 boundary rule applied by the gate.
 */
class ProxyPlaybackGateTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @Before fun setUp() {
        root = Files.createTempDirectory("proxygate").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun sig(fp: String = "abc123") = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun fullProxy(c: ProxyCache, s: ProxySignature): ProxyEntry {
        val h = c.begin(s)
        h.videoFile.writeBytes(ByteArray(50))
        return h.commit(durationUs = 1_000_000, frameCount = 30)!!
    }

    @Test fun aSessionWithNoProxyOpensTheOriginal() {
        val c = ProxyCache(storage)
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        assertNull(gate.open(sig()))
        assertFalse(gate.onProxy)
        assertTrue(inUse.protectedKeys().isEmpty()) // nothing acquired
    }

    @Test fun aSessionWithAPlayableProxyOpensItAndHoldsTheProtection() {
        val c = ProxyCache(storage)
        val s = sig()
        val entry = fullProxy(c, s)
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        val opened = gate.open(s)
        assertNotNull(opened)
        assertEquals(entry.key, opened!!.key)
        assertTrue(gate.onProxy)
        assertEquals(setOf(entry.key), inUse.protectedKeys())
        // the LRU sees the open as an access (the cache's own playable contract)
        gate.close()
        assertTrue(inUse.protectedKeys().isEmpty())
        assertFalse(gate.onProxy)
    }

    @Test fun aHalfProxyNeverOpensATmpOrALeftover() {
        val c = ProxyCache(storage)
        val s = sig()
        // a build in progress: only the tmp exists
        val h = c.begin(s)
        h.videoFile.writeBytes(ByteArray(30))
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        assertNull(gate.open(s))                    // the session opens the original
        h.abort()
        // a crash leftover: a video without a manifest
        storage.write("${s.key().baseName}.mp4", ByteArray(30))
        assertNull(gate.open(s))
        assertFalse(gate.onProxy)
        assertTrue(inUse.protectedKeys().isEmpty())
    }

    @Test fun aMidPlayBuildLandsAndTheSwitchWaitsForTheBoundary() {
        val c = ProxyCache(storage)
        val s = sig()
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        // the session opened the ORIGINAL (no proxy yet)
        gate.open(s)
        assertFalse(gate.onProxy)
        // the build finishes while the session is paused: switch NOW
        val entry = fullProxy(c, s)
        val target = gate.maybeSwitch(playing = false, lastPresentedPtsUs = 0)
        assertNotNull(target)
        assertEquals(entry.key, target!!.key)
        gate.swapped(target)
        assertTrue(gate.onProxy)
        assertEquals(setOf(entry.key), inUse.protectedKeys())
    }

    @Test fun aPlayingSessionStaysOnTheOriginalUntilTheGopBoundary() {
        val c = ProxyCache(storage)
        val s = sig()
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        gate.open(s)
        fullProxy(c, s)
        // mid-GOP: stay (Klipp's lesson — no live source swap mid-play)
        assertNull(gate.maybeSwitch(playing = true, lastPresentedPtsUs = 33_367))
        assertNull(gate.maybeSwitch(playing = true, lastPresentedPtsUs = 999_999))
        // exactly on the 1 s GOP boundary: the switch target is the proxy
        val atBoundary = gate.maybeSwitch(playing = true, lastPresentedPtsUs = 1_000_000)
        assertNotNull(atBoundary)
        // the caller swapped: the protection moved to the proxy
        gate.swapped(atBoundary!!)
        assertTrue(gate.onProxy)
        // an already-swapped session is never offered the same target again
        assertNull(gate.maybeSwitch(playing = true, lastPresentedPtsUs = 2_000_000))
    }

    @Test fun aStaleSignatureVariantNeverSwitchesTheSession() {
        // the cache holds a proxy for ANOTHER signature (the settings changed):
        // the session's own signature resolves nothing — no swap, no confusion
        val c = ProxyCache(storage)
        val sessionSig = sig()
        val otherSig = sig().copy(sourceSize = 999)
        fullProxy(c, otherSig)
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        assertNull(gate.open(sessionSig))
        assertNull(gate.maybeSwitch(playing = false, lastPresentedPtsUs = 0))
        assertFalse(gate.onProxy)
    }

    @Test fun aClosedGateNeverSwitchesAnything() {
        val c = ProxyCache(storage)
        val s = sig()
        fullProxy(c, s)
        val gate = ProxyPlaybackGate(c, ProxyInUse())
        gate.open(s)
        gate.close()
        // after close the session is gone: no decision, no target
        assertNull(gate.maybeSwitch(playing = false, lastPresentedPtsUs = 0))
    }
}
