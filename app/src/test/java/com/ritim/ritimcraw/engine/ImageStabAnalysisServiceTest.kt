package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.ClipDeletion
import com.ritim.ritimcraw.data.FakeResolver
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.SourceRepository
import com.ritim.ritimcraw.data.ThumbnailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** The analysis cache, the run policy, the service state machine and the delete hook (S4). */
class ImageStabAnalysisServiceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    private fun cache() = ImageStabCache(File(tmp.root, "cache/imagestab"))

    // ---- cache ----

    @Test
    fun cacheKeyIsFingerprintPlusAnalysisVersionOnly() {
        val c = cache()
        assertEquals("abc-v${ImageStabCache.ANALYSIS_VERSION}.ristab", c.fileFor("abc").name)
        assertFalse(c.isComplete("abc"))
        File(c.targetPath("abc")).writeText("x")
        assertTrue(c.isComplete("abc"))
        // a different version is not this analysis
        File(c.fileFor("abc").parentFile, "def-v999.ristab").writeText("x")
        assertFalse(c.isComplete("def"))
    }

    @Test
    fun cacheDeleteRemovesEveryVersionAndCheckpointOfOneFingerprint() {
        val c = cache()
        val dir = File(c.targetPath("abc")).parentFile
        File(dir, "abc-v1.ristab").writeText("x")
        File(dir, "abc-v1.ristab.part").writeText("x")
        File(dir, "abc-v0.ristab").writeText("x")
        File(dir, "abcd-v1.ristab").writeText("x") // another fingerprint sharing a prefix
        File(dir, "zzz-v1.ristab").writeText("x")
        assertEquals(3, c.delete("abc"))
        assertEquals(listOf("abcd-v1.ristab", "zzz-v1.ristab"), dir.list()!!.sorted())
        assertEquals(0, c.delete("")) // never a wildcard
        assertEquals(0, c.delete("nope"))
    }

    // ---- policy ----

    @Test
    fun policyTable() {
        val d = ImageStabRunPolicy::decide
        assertNull(d(false, false, null, false))
        assertNull(d(false, false, 0.5f, false))
        assertEquals(ImageStabPause.EXPORT, d(true, true, 0.99f, false)) // export wins
        assertEquals(ImageStabPause.PLAYBACK, d(false, true, 0.99f, false))
        assertEquals(ImageStabPause.THERMAL, d(false, false, 0.85f, false))
        assertNull(d(false, false, Float.NaN, false))
        // hysteresis: once thermally paused it stays paused until clearly cool
        assertEquals(ImageStabPause.THERMAL, d(false, false, 0.75f, true))
        assertNull(d(false, false, 0.75f, false))
        assertNull(d(false, false, 0.69f, true))
    }

    // ---- service ----

    private class FakeSignals : ImageStabSignals {
        @Volatile var exporting = false
        @Volatile var playing = false
        @Volatile var headroom: Float? = null
        override fun exporting() = exporting
        override fun playing() = playing
        override fun thermalHeadroom() = headroom
    }

    /**
     * Writes the final file after [framesNeeded] "frames" of work in total
     * across calls, honoring cancel with a kept checkpoint counter (the
     * native resume contract: progress survives a cancelled run).
     */
    private class FakeBackend(val framesNeeded: Int = 40, val frameMs: Long = 5) : ImageStabBackend {
        val cancelFlags = HashMap<Int, AtomicBoolean>()
        val doneFrames = AtomicInteger(0) // the "checkpoint"
        val analyzeCalls = AtomicInteger(0)
        val running = AtomicBoolean(false)
        var nextJob = 0
        var failWith: Int? = null
        override fun jobNew(): Int = synchronized(this) { nextJob++.also { cancelFlags[it] = AtomicBoolean(false) } }
        override fun jobFree(job: Int) {}
        override fun jobCancel(job: Int) { cancelFlags[job]?.set(true) }
        override fun jobProgress(job: Int): LongArray = longArrayOf(doneFrames.get().toLong(), framesNeeded.toLong())
        override fun analyze(fd: Int, finalFile: String, job: Int, threads: Int): Int {
            analyzeCalls.incrementAndGet()
            running.set(true)
            try {
                failWith?.let { return it }
                while (doneFrames.get() < framesNeeded) {
                    if (cancelFlags[job]!!.get()) return 1
                    Thread.sleep(frameMs)
                    doneFrames.incrementAndGet()
                }
                File(finalFile).also { it.parentFile.mkdirs() }.writeText("done")
                return 0
            } finally {
                running.set(false)
            }
        }
    }

    private val fds = ImageStabFdSource(open = { 7 }, close = {})

    private fun service(b: ImageStabBackend, s: ImageStabSignals, c: ImageStabCache = cache()) =
        ImageStabAnalysisService(c, b, s, scope, ioDispatcher = Dispatchers.IO, pollMs = 10L)

    @Test
    fun runsToDoneAndWritesTheKeyedFile() = runBlocking {
        val c = cache()
        val svc = service(FakeBackend(), FakeSignals(), c)
        assertEquals(ImageStabStatus.Idle(false), svc.status("fp"))
        svc.request("fp", fds)
        withTimeout(10_000) { svc.awaitTerminal("fp") }
        assertEquals(ImageStabStatus.Done, svc.status("fp"))
        assertTrue(c.isComplete("fp"))
        // a second request is a no-op Done (no re-analysis)
        assertNull(svc.request("fp", fds))
    }

    @Test
    fun standsDownDuringExportThenResumesFromItsCheckpoint() = runBlocking {
        val b = FakeBackend(framesNeeded = 200, frameMs = 5)
        val sig = FakeSignals()
        val svc = service(b, sig)
        svc.request("fp", fds)
        // let it run a little, then an export starts
        withTimeout(10_000) { while (b.doneFrames.get() < 10) delay(5) }
        sig.exporting = true
        withTimeout(10_000) { while (svc.status("fp") !is ImageStabStatus.Paused) delay(5) }
        assertEquals(ImageStabPause.EXPORT, (svc.status("fp") as ImageStabStatus.Paused).reason)
        val frozenAt = b.doneFrames.get()
        delay(150)
        assertEquals("no progress while an export runs", frozenAt, b.doneFrames.get())
        assertFalse(b.running.get())
        // export ends: the SECOND analyze call resumes and finishes
        sig.exporting = false
        withTimeout(15_000) { svc.awaitTerminal("fp") }
        assertEquals(ImageStabStatus.Done, svc.status("fp"))
        assertTrue("resumed in a new native call", b.analyzeCalls.get() >= 2)
        assertEquals(200, b.doneFrames.get()) // no frame was redone
    }

    @Test
    fun standsDownWhileHotAndWhilePlaying() = runBlocking {
        val b = FakeBackend(framesNeeded = 400, frameMs = 5)
        val sig = FakeSignals()
        sig.playing = true
        val svc = service(b, sig)
        svc.request("fp", fds)
        withTimeout(5_000) { while (svc.status("fp") !is ImageStabStatus.Paused) delay(5) }
        assertEquals(ImageStabPause.PLAYBACK, (svc.status("fp") as ImageStabStatus.Paused).reason)
        assertEquals(0, b.analyzeCalls.get())
        sig.playing = false
        sig.headroom = 0.95f
        withTimeout(5_000) {
            while ((svc.status("fp") as? ImageStabStatus.Paused)?.reason != ImageStabPause.THERMAL) delay(5)
        }
        assertEquals(0, b.analyzeCalls.get())
        sig.headroom = 0.2f
        withTimeout(10_000) { while (b.doneFrames.get() < 400) delay(10) }
        withTimeout(10_000) { svc.awaitTerminal("fp") }
        assertEquals(ImageStabStatus.Done, svc.status("fp"))
    }

    @Test
    fun userCancelKeepsTheCheckpointAndDoesNotResumeByItself() = runBlocking {
        val b = FakeBackend(framesNeeded = 2000, frameMs = 5)
        val svc = service(b, FakeSignals())
        svc.request("fp", fds)
        withTimeout(10_000) { while (b.doneFrames.get() < 5) delay(5) }
        svc.cancel("fp")
        withTimeout(10_000) { svc.awaitTerminal("fp") }
        assertTrue(svc.status("fp") is ImageStabStatus.Idle)
        val kept = b.doneFrames.get()
        assertTrue(kept in 5 until 2000)
        delay(100)
        assertEquals("a cancelled run stays stopped", kept, b.doneFrames.get())
    }

    @Test
    fun ensureIsUrgentAndIgnoresThePausePolicy() = runBlocking {
        val sig = FakeSignals()
        sig.exporting = true // the export itself is what waits for the analysis
        val b = FakeBackend(framesNeeded = 20, frameMs = 2)
        val svc = service(b, sig)
        assertTrue(withTimeout(10_000) { svc.ensure("fp", fds) })
        assertEquals(1, b.analyzeCalls.get())
    }

    @Test
    fun failureIsReportedNotSwallowed() = runBlocking {
        val b = FakeBackend()
        b.failWith = 2
        val svc = service(b, FakeSignals())
        assertFalse(withTimeout(10_000) { svc.ensure("fp", fds) })
        assertEquals(ImageStabStatus.Failed(2), svc.status("fp"))
        val noFd = ImageStabFdSource(open = { -1 }, close = {})
        assertFalse(withTimeout(10_000) { svc.ensure("fp2", noFd) })
        assertEquals(ImageStabStatus.Failed(ImageStabAnalysisService.CODE_OPEN), svc.status("fp2"))
    }

    // ---- delete hook ----

    @Test
    fun deletingAClipDeletesItsAnalysisAndOnlyItsAnalysis() {
        val project = File(tmp.root, "project.json")
        val sources = SourceRepository(FakeResolver("clip-a".toByteArray()), project)
        val grades = GradeStore(project)
        val thumbs = ThumbnailStore(File(tmp.root, "thumbs"))
        val a = sources.import("content://clip/a")
        val c = cache()
        File(c.targetPath(a.fingerprint)).writeText("x")
        File(c.targetPath(a.fingerprint) + ".part").writeText("x")
        File(c.targetPath("someone-else")).writeText("x")
        grades.put(a.id, GradeK(stabilization = true, stabilizationEngine = 1))

        val svc = service(FakeBackend(), FakeSignals(), c)
        ClipDeletion.delete(listOf(a.id), sources, grades, thumbs, imageStab = svc)

        assertFalse(c.isComplete(a.fingerprint))
        assertFalse(c.hasPartial(a.fingerprint))
        assertTrue(c.isComplete("someone-else"))
        assertTrue(sources.all().isEmpty())
        // ids that are not records are no-ops
        ClipDeletion.delete(listOf("nope"), sources, grades, thumbs, imageStab = svc)
        assertTrue(c.isComplete("someone-else"))
    }

    @Test
    fun deletingWhileAnalyzingStopsTheRunAndLeavesNoFiles() = runBlocking {
        val c = cache()
        val b = FakeBackend(framesNeeded = 4000, frameMs = 5)
        val svc = service(b, FakeSignals(), c)
        svc.request("fp", fds)
        withTimeout(10_000) { while (b.doneFrames.get() < 5) delay(5) }
        File(c.targetPath("fp") + ".part").writeText("checkpoint") // what native keeps
        svc.forget("fp")
        withTimeout(10_000) { while (b.running.get()) delay(5) }
        delay(100)
        assertFalse(c.hasPartial("fp"))
        assertFalse(c.isComplete("fp"))
    }
}
