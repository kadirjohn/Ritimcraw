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
 * G3 production-layer tests, all JVM-pure with a FAKE encoder (the brief's
 * test list): the auto/optional queue decision table, the build engine's
 * queue-policy integration (start -> commit / interrupt -> abort+requeue /
 * user cancel -> out), the atomic-rename contract from the WRITE side (a
 * partial build never becomes playable, the commit validates the frame
 * count) and the encode spec's pure numbers.
 */
class ProxyQueueDecisionTest {

    private fun clip(
        kind: ProxySourceKind = ProxySourceKind.ENCODED,
        w: Int = 1920, h: Int = 1080, fps: Double = 30.0,
    ) = ProxyClipInfo(kind, w, h, fps)

    @Test fun encoded8kIsAutomatic() {
        // the plan's own headline case: 8K encoded clips get a proxy
        assertNotNull(ProxyQueueDecision.decide(clip(w = 7680, h = 4320)))
        assertNotNull(ProxyQueueDecision.decide(clip(w = 4320, h = 7680)))
    }

    @Test fun encoded4k60IsAutomaticAnd4k30IsNot() {
        // "4K60": above 50 fps counts (the plan's "60p" convention)
        assertNotNull(ProxyQueueDecision.decide(clip(w = 3840, h = 2160, fps = 59.94)))
        assertNotNull(ProxyQueueDecision.decide(clip(w = 3840, h = 2160, fps = 50.1)))
        assertNull(ProxyQueueDecision.decide(clip(w = 3840, h = 2160, fps = 30.0)))
        assertNull(ProxyQueueDecision.decide(clip(w = 3840, h = 2160, fps = 50.0)))
    }

    @Test fun smallEncodedClipsAreNotAutomatic() {
        // 1080p30 and 720p60: below every threshold, no automatic proxy
        assertNull(ProxyQueueDecision.decide(clip(w = 1920, h = 1080, fps = 30.0)))
        assertNull(ProxyQueueDecision.decide(clip(w = 1280, h = 720, fps = 60.0)))
    }

    @Test fun mcrawIsOptInOnly() {
        // the owner decision: MCRAW proxy is opt-in (autoMcraw defaults false)
        assertNull(ProxyQueueDecision.decide(clip(kind = ProxySourceKind.MCRAW, w = 3840, h = 2160, fps = 60.0)))
        val optedIn = ProxyPolicy(autoMcraw = true)
        assertNotNull(
            ProxyQueueDecision.decide(
                clip(kind = ProxySourceKind.MCRAW, w = 3840, h = 2160, fps = 60.0),
                policy = optedIn,
            ),
        )
        // an opted-in MCRAW below every threshold still queues (the opt-in IS the threshold)
        assertNotNull(
            ProxyQueueDecision.decide(
                clip(kind = ProxySourceKind.MCRAW, w = 1920, h = 1080, fps = 30.0),
                policy = optedIn,
            ),
        )
    }

    @Test fun encodedAutoCanBeDisabledEntirely() {
        val off = ProxyPolicy(autoEncoded = false)
        assertNull(ProxyQueueDecision.decide(clip(w = 7680, h = 4320), policy = off))
    }

    @Test fun incompleteFactsNeverQueueAProxy() {
        // honest refusal: a clip whose size or fps is unknown cannot be signed
        // (a proxy nobody can validate against its source)
        assertNull(ProxyQueueDecision.decide(clip(w = 0, h = 2160, fps = 60.0)))
        assertNull(ProxyQueueDecision.decide(clip(w = 7680, h = 0, fps = 60.0)))
        assertNull(ProxyQueueDecision.decide(clip(w = 7680, h = 4320, fps = 0.0)))
    }
}

class ProxyBuildSpecNumbersTest {

    @Test fun shortSideScalesDownNeverUp() {
        // 16:9 4K -> 1080p; vertical 8K -> short side 1080
        assertEquals(1920 to 1080, ProxyDownscale.outputSize(3840, 2160, 1080))
        assertEquals(1080 to 1920, ProxyDownscale.outputSize(2160, 3840, 1080))
        // 4:3-ish 4096x3072 -> short side 1080 = 1440x1080 (the plan's own example)
        assertEquals(1440 to 1080, ProxyDownscale.outputSize(4096, 3072, 1080))
        // a source at or below the target is NEVER upscaled
        assertEquals(1920 to 1080, ProxyDownscale.outputSize(1920, 1080, 1080))
        assertEquals(1280 to 720, ProxyDownscale.outputSize(1280, 720, 1080))
    }

    @Test fun outputSizesAreEven() {
        // the encoder contract: even dimensions at every odd source
        val (w, h) = ProxyDownscale.outputSize(3841, 2161, 1080)
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
    }

    @Test fun downscaleFlagFollowsTheShortSide() {
        assertTrue(ProxyDownscale.isDownscale(3840, 2160, 1080))
        assertFalse(ProxyDownscale.isDownscale(1920, 1080, 1080))
        assertFalse(ProxyDownscale.isDownscale(1280, 720, 1080))
    }

    @Test fun bitrateStartsAtThePlanS1080pReferenceAndScalesWithFps() {
        // the plan's stated starting point: ~20 Mbit/s at 1080p (the proxy's own size)
        assertEquals(20_000_000, ProxyBitrate.bps(30.0))
        // a 1080p60 proxy carries twice the frames: twice the bitrate
        assertEquals(40_000_000, ProxyBitrate.bps(60.0))
        assertEquals(39_980_000, ProxyBitrate.bps(59.97))
        // an unknown fps falls back to the reference honestly, never to 0
        assertEquals(20_000_000, ProxyBitrate.bps(0.0))
    }

    @Test fun bitrateIsClamped() {
        // degenerate rates stay inside 1..100 Mbit/s — never a 0 or a runaway ask
        assertTrue(ProxyBitrate.bps(0.001) >= 1_000_000)
        assertTrue(ProxyBitrate.bps(240.0) <= 100_000_000)
    }
}

/** A controllable fake encoder: writes N "frames" to the tmp, reports progress, honours cancel/pacer. */
private open class FakeEncoder : ProxyEncoder {
    var framesToWrite: Long = 30
    /** When set, [run] returns this instead of Ok at the end. */
    var endWith: ProxyEncodeResult? = null
    /** When true, [run] checks [isCancelled] after every frame (the real contract). */
    var honourCancel = true
    var pacedFrames = 0
    val progressCalls = ArrayList<Pair<Long, Long>>()

    override fun run(
        spec: ProxyBuildSpec,
        handle: ProxyWriteHandle,
        onProgress: (Long, Long) -> Unit,
        isCancelled: () -> Boolean,
        pacer: () -> Unit,
    ): ProxyEncodeResult {
        for (i in 1L..framesToWrite) {
            if (honourCancel && isCancelled()) return ProxyEncodeResult.Cancelled
            handle.videoFile.appendBytes(ByteArray(10))
            onProgress(i, framesToWrite)
            pacer()
            pacedFrames++
        }
        return endWith ?: ProxyEncodeResult.Ok
    }
}

class ProxyBuildEngineTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @Before fun setUp() {
        root = Files.createTempDirectory("proxyengine").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun signature(fp: String = "abc123") = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun spec(sig: ProxySignature, frames: Long = 30) =
        ProxyBuildSpec(sig, frameCount = frames, durationUs = 1_000_000)

    /** The engine wired over a real cache and the fake encoder. */
    private class Wired(
        val queue: ProxyStateMap,
        val cache: ProxyCache,
        val encoder: FakeEncoder,
        val engine: ProxyBuildEngine,
        val sig: ProxySignature,
    )

    private fun wire(frames: Long = 30, cap: Long = 10_000): Wired {
        val q = ProxyStateMap()
        val c = ProxyCache(storage, cap)
        val enc = FakeEncoder().apply { framesToWrite = frames }
        val s = signature()
        val e = ProxyBuildEngine(
            queue = q,
            cache = c,
            encoder = enc,
            specFor = { if (it == s.key()) spec(s, frames) else null },
        )
        return Wired(q, c, enc, e, s)
    }

    /** Runs one popped job through the engine (the runner popped it in the test). */
    private fun ProxyBuildEngine.drive(q: ProxyStateMap): ProxyState {
        val next = q.queued().firstOrNull() ?: return ProxyState.None
        val popped = q.next()
        assertTrue(popped == next)
        return run(ProxyJob.Start(popped!!))
    }

    @Test fun aCompleteBuildCommitsAndIsPlayable() {
        val w = wire()
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Ready, w.engine.drive(w.queue))
        // the P1 gate: the committed proxy resolves and carries the honest counts
        val entry = w.cache.playable(w.sig.key())
        assertNotNull(entry)
        assertEquals(300L, entry!!.bytes)          // 30 frames x 10 bytes
        assertEquals(30L, entry.frameCount)
        assertEquals(1_000_000L, entry.durationUs)
        assertEquals(ProxyState.Ready, w.queue.state(w.sig.key()))
    }

    @Test fun aShortBuildNeverCommitsNothingIsPlayable() {
        // the encoder stalls at 20 of 30 frames but reports Ok: the engine's
        // frame-count gate refuses the commit — a half proxy NEVER becomes playable
        val w = wire()
        val enc = object : FakeEncoder() {
            override fun run(
                spec: ProxyBuildSpec,
                handle: ProxyWriteHandle,
                onProgress: (Long, Long) -> Unit,
                isCancelled: () -> Boolean,
                pacer: () -> Unit,
            ): ProxyEncodeResult {
                for (i in 1L..20L) {
                    handle.videoFile.appendBytes(ByteArray(10))
                    onProgress(i, spec.frameCount)
                }
                return ProxyEncodeResult.Ok // the lie the gate must catch
            }
        }
        val e = ProxyBuildEngine(w.queue, w.cache, enc, { spec(w.sig) })
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Failed, e.drive(w.queue))
        assertNull(w.cache.playable(w.sig.key()))          // nothing published
        assertEquals(1, w.queue.failureCount(w.sig.key()))
        assertTrue(w.queue.failureReason(w.sig.key())!!.contains("20 of 30"))
        // the tmp is gone: the write side of the never-half rule
        assertEquals(0, storage.names().count { it.endsWith(".tmp") })
    }

    @Test fun aFailedBuildRecordsTheHonestReasonAndDropsTheTmp() {
        val w = wire()
        w.encoder.endWith = ProxyEncodeResult.Failed("no video/hevc encoder on this device")
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Failed, w.engine.drive(w.queue))
        assertNull(w.cache.playable(w.sig.key()))
        assertEquals("no video/hevc encoder on this device", w.queue.failureReason(w.sig.key()))
        assertEquals(0, storage.names().count { it.endsWith(".tmp") })
    }

    /**
     * The monitor-thread shape, as a test double: the encoder reports one
     * frame, then asks ITS OWN engine to cancel (exactly what the wired
     * monitor coroutine does from another thread mid-build) and honours the
     * flag. One class, parameterised by the reason, so both cancel routes
     * share the exact same protocol.
     */
    private class CancelAfterFirstFrame(private val reason: ProxyBuildEngine.CancelReason) : ProxyEncoder {
        lateinit var engine: ProxyBuildEngine

        override fun run(
            spec: ProxyBuildSpec,
            handle: ProxyWriteHandle,
            onProgress: (Long, Long) -> Unit,
            isCancelled: () -> Boolean,
            pacer: () -> Unit,
        ): ProxyEncodeResult {
            handle.videoFile.appendBytes(ByteArray(10))
            onProgress(1, spec.frameCount)
            engine.requestCancel(reason)
            return if (isCancelled()) ProxyEncodeResult.Cancelled else ProxyEncodeResult.Ok
        }
    }

    @Test fun aUserCancelTakesTheJobOutOfTheQueueAndDropsTheTmp() {
        val w = wire(frames = 100)
        // the "monitor thread": cancel after the first progress report
        val enc = CancelAfterFirstFrame(ProxyBuildEngine.CancelReason.USER)
        val cancelAfterFirstProgress = ProxyBuildEngine(
            queue = w.queue,
            cache = w.cache,
            encoder = enc,
            specFor = { spec(w.sig, 100) },
        )
        enc.engine = cancelAfterFirstProgress
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Cancelled, cancelAfterFirstProgress.drive(w.queue))
        assertEquals(ProxyState.Cancelled, w.queue.state(w.sig.key()))
        assertEquals(0, w.queue.failureCount(w.sig.key()))  // a cancel is not a failure
        assertNull(w.cache.playable(w.sig.key()))
        assertEquals(0, storage.names().size)                 // every tmp dropped
        // a cancelled job can be asked for again (settings changed)
        assertTrue(w.queue.enqueue(w.sig.key()))
    }

    @Test fun aPriorityInterruptRequeuesTheJobAtTheVeryFront() {
        val w = wire(frames = 100)
        val enc = CancelAfterFirstFrame(ProxyBuildEngine.CancelReason.PRIORITY)
        val e = ProxyBuildEngine(w.queue, w.cache, enc, { spec(w.sig, 100) })
        enc.engine = e
        assertTrue(w.queue.enqueue(w.sig.key()))
        val other = signature("other1")
        assertTrue(w.queue.enqueue(other.key()))
        assertEquals(ProxyState.Queued, e.drive(w.queue))
        // the interrupted job is FRONT again — it runs before `other`
        assertEquals(listOf(w.sig.key(), other.key()), w.queue.queued())
        assertNull(w.cache.playable(w.sig.key()))
        assertEquals(0, storage.names().size)
    }

    @Test fun aMissingSpecFailsTheJobHonestlyWithoutTouchingTheCache() {
        val w = wire()
        val e = ProxyBuildEngine(w.queue, w.cache, w.encoder, { null })
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Failed, e.drive(w.queue))
        assertTrue(w.queue.failureReason(w.sig.key())!!.contains("no build spec"))
    }

    @Test fun theCommitRefusedOverTheCapIsAnHonestFailure() {
        // the cache refuses a commit bigger than the cap: the engine records
        // the refusal, the job fails, nothing is playable
        val w = wire(frames = 30, cap = 100) // 30 frames x 10 bytes = 300 > 100
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Failed, w.engine.drive(w.queue))
        assertNull(w.cache.playable(w.sig.key()))
        assertTrue(w.queue.failureReason(w.sig.key())!!.contains("commit was refused"))
    }

    @Test fun progressReachesTheListenerWithTheBuildsOwnCounts() {
        val w = wire(frames = 5)
        val seen = ArrayList<Pair<Long, Long>>()
        val e = ProxyBuildEngine(
            w.queue, w.cache, w.encoder, { spec(w.sig, 5) },
            onProgress = { d, t -> seen.add(d to t) },
        )
        assertTrue(w.queue.enqueue(w.sig.key()))
        e.drive(w.queue)
        assertEquals(listOf(1L to 5L, 2L to 5L, 3L to 5L, 4L to 5L, 5L to 5L), seen)
    }

    @Test fun theSlowPacerRunsBetweenFramesWithoutChangingTheOutput() {
        val w = wire(frames = 4)
        var paces = 0
        val e = ProxyBuildEngine(
            w.queue, w.cache, w.encoder, { spec(w.sig, 4) },
            pacer = { paces++ },
        )
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Ready, e.drive(w.queue))
        assertEquals(4, paces) // one pace per frame — YIELD slows the pace, not the bytes
        assertEquals(40L, w.cache.playable(w.sig.key())!!.bytes)
    }

    @Test fun aTwiceFailedClipIsNotDrivenAgainThisSession() {
        // the E2 session barrier seen through the engine: two failures, the
        // third enqueue is refused before any build starts
        val w = wire()
        w.encoder.endWith = ProxyEncodeResult.Failed("encoder init")
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Failed, w.engine.drive(w.queue))
        assertTrue(w.queue.enqueue(w.sig.key()))
        assertEquals(ProxyState.Failed, w.engine.drive(w.queue))
        assertFalse(w.queue.enqueue(w.sig.key()))
        assertEquals(2, w.queue.failureCount(w.sig.key()))
    }
}
