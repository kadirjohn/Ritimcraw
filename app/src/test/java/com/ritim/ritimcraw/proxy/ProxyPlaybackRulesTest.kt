package com.ritim.ritimcraw.proxy

import com.ritim.ritimcraw.engine.PreviewTiers.RenderClass
import com.ritim.ritimcraw.util.FeatureFlags
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

/** The owner's proxy playback rules: switch only at the next seek/play, paused = original, flag off = no proxies. */
class ProxyPlaybackRulesTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @Before fun setUp() {
        root = Files.createTempDirectory("proxyrules").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun sig() = ProxySignature(
        sourceFingerprint = "abc123", sourceSize = 100, sourceModifiedMs = 5,
        colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun fullProxy(c: ProxyCache, s: ProxySignature): ProxyEntry {
        val h = c.begin(s)
        h.videoFile.writeBytes(ByteArray(50))
        return h.commit(durationUs = 1_000_000, frameCount = 30)!!
    }

    // ---- rule 1: switch only at the next seek/play ----

    @Test fun landedBuildOnlyMarksPendingAndNeverSwapsByItself() {
        val c = ProxyCache(storage)
        val s = sig()
        val gate = ProxyPlaybackGate(c, ProxyInUse())
        gate.open(s)
        fullProxy(c, s)
        gate.onBuildLanded()
        assertTrue(gate.switchPending)
        assertFalse(gate.onProxy) // still on the original
    }

    @Test fun pendingSwitchIsTakenAtTheNextActionAndOnlyOnce() {
        val c = ProxyCache(storage)
        val s = sig()
        val inUse = ProxyInUse()
        val gate = ProxyPlaybackGate(c, inUse)
        gate.open(s)
        val entry = fullProxy(c, s)
        gate.onBuildLanded()
        val target = gate.takePendingSwitch()
        assertNotNull(target)
        assertEquals(entry.key, target!!.key)
        gate.swapped(target)
        assertTrue(gate.onProxy)
        assertFalse(gate.switchPending)
        assertNull(gate.takePendingSwitch())
        assertEquals(setOf(entry.key), inUse.protectedKeys())
    }

    @Test fun nothingPendingMeansNoSwitchEvenWithAProxyOnDisk() {
        val c = ProxyCache(storage)
        val s = sig()
        val gate = ProxyPlaybackGate(c, ProxyInUse())
        gate.open(s)
        fullProxy(c, s) // landed but nobody told the gate
        assertNull(gate.takePendingSwitch())
    }

    @Test fun halfProxyAtTheNextActionStaysOnOriginal() {
        val c = ProxyCache(storage)
        val s = sig()
        val gate = ProxyPlaybackGate(c, ProxyInUse())
        gate.open(s)
        gate.onBuildLanded()
        assertNull(gate.takePendingSwitch()) // no committed proxy: playable is null
    }

    @Test fun reopenTriggersOnlyOnPlayStartOrPausedSeek() {
        val p = ProxyPlaybackPolicy
        // nothing pending: never
        assertFalse(p.shouldReopen(false, false, 0, true, 0))
        // play pressed
        assertTrue(p.shouldReopen(true, false, 10, true, 10))
        // seek while paused
        assertTrue(p.shouldReopen(true, false, 10, false, 42))
        // clock ticking during playback: never (no mid-playback swap)
        assertFalse(p.shouldReopen(true, true, 10, true, 11))
        // paused, nothing moved
        assertFalse(p.shouldReopen(true, false, 10, false, 10))
        // pause pressed (playing -> paused, same frame): not a trigger
        assertFalse(p.shouldReopen(true, true, 10, false, 10))
    }

    // ---- rule 2: paused frame from the original ----

    @Test fun pausedSettleRendersComeFromTheOriginalWhenOnProxy() {
        val p = ProxyPlaybackPolicy
        assertEquals(ProxyPlaybackPolicy.FrameSource.ORIGINAL, p.sourceFor(RenderClass.SETTLE, true))
        assertEquals(ProxyPlaybackPolicy.FrameSource.PROXY, p.sourceFor(RenderClass.PLAYBACK, true))
        assertEquals(ProxyPlaybackPolicy.FrameSource.PROXY, p.sourceFor(RenderClass.DRAG, true))
    }

    @Test fun noProxySessionAlwaysUsesTheOriginal() {
        for (rc in RenderClass.values()) {
            assertEquals(
                ProxyPlaybackPolicy.FrameSource.ORIGINAL,
                ProxyPlaybackPolicy.sourceFor(rc, false),
            )
        }
    }

    // ---- rule 3: flag off ignores already-generated proxies ----

    @Test fun flagOffIgnoresAnAlreadyGeneratedProxy() {
        val c = ProxyCache(storage)
        val s = sig()
        fullProxy(c, s)
        val inUse = ProxyInUse()
        val off = ProxyPlaybackGate(c, inUse) { false }
        assertNull(off.resolve(s))
        assertNull(off.open(s))
        assertFalse(off.onProxy)
        assertTrue(inUse.protectedKeys().isEmpty())
        // the very same proxy plays once the flag is on
        val on = ProxyPlaybackGate(c, inUse) { true }
        assertNotNull(on.open(s))
    }

    @Test fun flagTurnedOffMidSessionNeverSwitches() {
        val c = ProxyCache(storage)
        val s = sig()
        var enabled = true
        val gate = ProxyPlaybackGate(c, ProxyInUse()) { enabled }
        gate.open(s)
        fullProxy(c, s)
        enabled = false
        gate.onBuildLanded()
        assertFalse(gate.switchPending)
        assertNull(gate.takePendingSwitch())
        assertNull(gate.maybeSwitch(playing = false, lastPresentedPtsUs = 0))
    }

    // ---- rule 4: default off, debug-only switch ----

    @Test fun defaultIsOffAndOnlyDebugPropertyTurnsItOn() {
        assertFalse(FeatureFlags.PROXY_PRODUCTION_DEFAULT)
        assertFalse(FeatureFlags.resolveProxyEnabled(false, true) { "" })
        assertFalse(FeatureFlags.resolveProxyEnabled(false, true) { null })
        assertTrue(FeatureFlags.resolveProxyEnabled(false, true) { "1" })
        // staging/release never read the property
        var read = false
        assertFalse(FeatureFlags.resolveProxyEnabled(false, false) { read = true; "1" })
        assertFalse(read)
    }
}
