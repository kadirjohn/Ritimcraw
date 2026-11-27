package com.ritim.ritimcraw.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * G3 notification/service state machine tests, all JVM-pure through the fake
 * [ProxyForegroundController.Sink] (the ExportForegroundTest shape, the brief's
 * "bildirim/servis durum makinesi (sahte)" item): the controller's guarantees
 * (start/progress/stop order, the throttle, the never-outlives rules) and the
 * ENGINE integration (the build's own frame counts drive the foreground
 * seam; a terminal build drops the priority exactly once).
 */
class ProxyForegroundControllerTest {

    /** Records every sink call in order (the fake seam). */
    private class RecordingSink : ProxyForegroundController.Sink {
        val calls = mutableListOf<String>()
        override fun onStarted(jobId: String, title: String) {
            calls.add("start:$jobId:$title")
        }
        override fun onProgress(
            jobId: String, title: String, framesDone: Long, framesTotal: Long,
            percent: Int, indeterminate: Boolean,
        ) {
            calls.add("progress:$jobId:$framesDone/$framesTotal:$percent:$indeterminate")
        }
        override fun onStopped(jobId: String) {
            calls.add("stop:$jobId")
        }
    }

    @Test fun controllerStartsOnRunningAndStopsOnTerminal() {
        val sink = RecordingSink()
        val c = ProxyForegroundController(sink)
        c.start("j1", "night.mcraw")
        c.stop("j1")
        assertEquals(listOf("start:j1:night.mcraw", "stop:j1"), sink.calls)
    }

    @Test fun progressForANeverStartedJobIsDropped() {
        val sink = RecordingSink()
        val c = ProxyForegroundController(sink)
        c.progress("j1", 5, 30)
        assertEquals(0, sink.calls.size)
        // and for an already-stopped one
        c.start("j1", "t")
        c.stop("j1")
        c.progress("j1", 6, 30)
        assertEquals(listOf("start:j1:t", "stop:j1"), sink.calls)
    }

    @Test fun stopLandsExactlyOncePerStartedJob() {
        val sink = RecordingSink()
        val c = ProxyForegroundController(sink)
        c.start("j1", "t")
        c.stop("j1")
        c.stop("j1") // the late second stop is a no-op
        assertEquals(listOf("start:j1:t", "stop:j1"), sink.calls)
    }

    @Test fun aStartWhileAnotherJobRunsStopsTheOldOneFirst() {
        // the queue is serial but a lost terminal hook must never leave the
        // service holding a dead job's priority
        val sink = RecordingSink()
        val c = ProxyForegroundController(sink)
        c.start("j1", "a")
        c.start("j2", "b")
        assertEquals(listOf("start:j1:a", "stop:j1", "start:j2:b"), sink.calls)
    }

    @Test fun progressIsThrottledToOnePerSecondAndTheFirstReportAlwaysPasses() {
        val sink = RecordingSink()
        var now = 10_000L
        val c = ProxyForegroundController(sink) { now }
        c.start("j1", "t")
        c.progress("j1", 1, 100)              // first after start: passes
        c.progress("j1", 2, 100)              // 0 ms later: throttled
        assertEquals(1, sink.calls.count { it.startsWith("progress:") })
        now += 500
        c.progress("j1", 3, 100)              // still under 1 s
        assertEquals(1, sink.calls.count { it.startsWith("progress:") })
        now += 600                            // 1.1 s since the last pass
        c.progress("j1", 4, 100)
        assertEquals(2, sink.calls.count { it.startsWith("progress:") })
        // the throttled report still carried real counts
        assertEquals("progress:j1:1/100:1:false", sink.calls[1])
    }

    @Test fun anUnknownTotalIsIndeterminateAndAnUnknownJobDropsProgress() {
        val sink = RecordingSink()
        var now = 0L
        val c = ProxyForegroundController(sink) { now }
        c.start("j1", "t")
        c.progress("j1", 0, 0)  // total unknown (the spec's own -1 case)
        assertEquals("progress:j1:0/0:0:true", sink.calls[1])
        c.progress("other", 1, 2)
        assertEquals(2, sink.calls.size) // never landed
    }
}

class ProxyForegroundEngineTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @Before fun setUp() {
        root = Files.createTempDirectory("proxyfg").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private class RecordingForeground : ProxyForeground {
        val calls = mutableListOf<String>()
        override fun start(jobId: String, title: String) { calls.add("start:$jobId:$title") }
        override fun progress(jobId: String, framesDone: Long, framesTotal: Long) {
            calls.add("progress:$framesDone/$framesTotal")
        }
        override fun stop(jobId: String) { calls.add("stop:$jobId") }
    }

    private fun signature(fp: String = "abc123") = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    /** A small honest fake encoder: N frames, progress per frame. */
    private class SimpleFake(val frames: Long) : ProxyEncoder {
        override fun run(
            spec: ProxyBuildSpec,
            handle: ProxyWriteHandle,
            onProgress: (Long, Long) -> Unit,
            isCancelled: () -> Boolean,
            pacer: () -> Unit,
        ): ProxyEncodeResult {
            for (i in 1L..frames) {
                if (isCancelled()) return ProxyEncodeResult.Cancelled
                handle.videoFile.appendBytes(ByteArray(4))
                onProgress(i, frames)
                pacer()
            }
            return ProxyEncodeResult.Ok
        }
    }

    @Test fun aBuildDrivesTheForegroundSeamWithItsOwnCounts() {
        val fg = RecordingForeground()
        val q = ProxyStateMap()
        val c = ProxyCache(storage)
        val s = signature()
        val e = ProxyBuildEngine(
            q, c, SimpleFake(4), { ProxyBuildSpec(s, frameCount = 4, durationUs = 1_000_000) },
            pacer = {},
            onProgress = { done, total -> fg.progress(s.key().baseName, done, total) },
        )
        fg.start(s.key().baseName, "night")
        assertTrue(q.enqueue(s.key()))
        assertEquals(ProxyState.Ready, e.run(ProxyJob.Start(s.key())))
        fg.stop(s.key().baseName)
        // progress rode the SAME counts the storage page shows
        assertEquals(
            listOf("progress:1/4", "progress:2/4", "progress:3/4", "progress:4/4"),
            fg.calls.filter { it.startsWith("progress:") },
        )
        // and the notification never outlived the build
        assertEquals("stop:${s.key().baseName}", fg.calls.last())
    }

    @Test fun aFailedBuildAlsoDropsThePriority() {
        val fg = RecordingForeground()
        val q = ProxyStateMap()
        val c = ProxyCache(storage)
        val s = signature()
        val e = ProxyBuildEngine(
            q, c, SimpleFake(4), { null }, // no spec: the job fails before any frame
            pacer = {},
            onProgress = { done, total -> fg.progress(s.key().baseName, done, total) },
        )
        fg.start(s.key().baseName, "night")
        assertTrue(q.enqueue(s.key()))
        assertEquals(ProxyState.Failed, e.run(ProxyJob.Start(s.key())))
        fg.stop(s.key().baseName)
        assertTrue(q.failureReason(s.key())!!.contains("no build spec"))
        // no progress ever landed, and the stop is the last call
        assertEquals(0, fg.calls.count { it.startsWith("progress:") })
        assertEquals("stop:${s.key().baseName}", fg.calls.last())
    }
}
