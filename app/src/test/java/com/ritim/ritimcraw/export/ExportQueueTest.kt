package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Queue runner tests with FAKE exporters (the real ones need the native
 * engine + MediaCodec — device-gated). Covers the queue contract: serial
 * one-at-a-time processing, QUEUED -> RUNNING -> DONE/FAILED transitions,
 * retry re-queue with the same snapshot, failure does not wedge the queue,
 * and the fd hand-off (acquired once per job, closed after it).
 */
class ExportQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * The fake DNG boundary: writes a real file so the staging/rename path
     * runs for real; scriptable failure via [failOn] (matched against the
     * output path, so the fake needs no knowledge of job ids).
     */
    private class FakeDngRender(
        val log: MutableList<String>,
        val failOn: String? = null,
    ) : DngRender {
        override fun render(fd: Int, frameIndex: Int, outPath: String): Boolean {
            log.add("dng:$fd:${File(outPath).name}")
            if (failOn != null && File(outPath).name.startsWith(failOn)) return false
            File(outPath).writeBytes(ByteArray(16))
            return true
        }

        override fun code(): Int = DngExportErrors.CODE_IO
        override fun stage(): Int = DngExportErrors.STAGE_DNG_WRITE
        override fun detail(): String? = "fake io failure"
    }

    /** The fake MP4 boundary: open always fails with a scripted error. */
    private class FakeMp4Session(
        val log: MutableList<String>,
        val openFail: Boolean = true,
    ) : Mp4Session {
        override fun open(fd: Int): Mp4SessionInfo? {
            log.add("mp4:$fd")
            if (openFail) return null
            return Mp4SessionInfo(handle = 1L, frameCount = 1, width = 4, height = 4, cfa = 0, orientationDeg = 0)
        }

        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK): ByteArray? =
            ByteArray(4 * 4 * 3)

        override fun framePts(handle: Long, frameIndex: Int): Long = 0
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray? = null
        override fun close(handle: Long) {}
        override fun code(): Int = Mp4ExportErrors.CODE_IO
        override fun stage(): Int = Mp4ExportErrors.STAGE_OPEN
        override fun detail(): String? = "fake open failure"
    }

    /** Fake writer: finish() writes a real file so rename runs for real. */
    private class FakeWriter : Mp4MediaWriter {
        override fun start(outPath: String, width: Int, height: Int, orientationDeg: Int, sampleRate: Int, channels: Int) {
            File(outPath).writeBytes(ByteArray(16))
        }

        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
        override fun finish(): Boolean = true
        override fun release() {}
    }

    /** Records fd hand-off: which fds were handed out and which got closed. */
    private class RecordingIo(private val tmp: TemporaryFolder) : ExportIo {
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        private var next = 7

        override fun sourceFd(job: ExportJob): Int {
            val fd = next++
            opened.add(fd)
            return fd
        }

        override fun closeFd(fd: Int) {
            closed.add(fd)
        }

        override fun outputFile(job: ExportJob, target: Target): File =
            File(tmp.root, ExportQueue.outputName(job.sourceId, target))
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-queue").apply { isDaemon = true }
        }

    private fun store(): ExportStore = ExportStore(File(tmp.root, "project.json"))

    private fun job(
        id: String,
        sourceId: String = "clip1",
        target: Target = Target.DNG,
        snapshot: GradeK = GradeK(),
    ): ExportJob = ExportJob(id, sourceId, snapshot, target, State.QUEUED)

    @Test
    fun output_name_is_stable_and_lowercase() {
        assertEquals("clip1-dng.dng", ExportQueue.outputName("clip1", Target.DNG))
        assertEquals("clip1-mp4.mp4", ExportQueue.outputName("clip1", Target.MP4))
    }

    @Test
    fun queue_runs_one_job_to_done() = runBlocking {
        val store = store()
        store.add(job("j1"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        assertEquals(1, ran)
        val jobs = store.all()
        assertEquals(1, jobs.size)
        assertEquals(State.DONE, jobs[0].state)
        assertEquals("clip1-dng.dng", jobs[0].detail)
        // The exporter saw the staged output name and the handed-over fd.
        assertEquals("dng:7:clip1-dng.dng.partial", log[0])
        // The output file really exists (finalize ran).
        assertTrue(File(tmp.root, "clip1-dng.dng").exists())
    }

    @Test
    fun queue_runs_jobs_serially_in_queue_order() = runBlocking {
        val store = store()
        store.add(job("j1", "clip1"))
        store.add(job("j2", "clip2"))
        store.add(job("j3", "clip3"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        assertEquals(3, ran)
        // Queue order preserved: j1 (clip1), j2 (clip2), j3 (clip3).
        assertEquals(
            listOf("clip1", "clip2", "clip3"),
            store.all().map { it.sourceId },
        )
        assertEquals(3, log.size)
        assertTrue(log[0].contains("clip1"))
        assertTrue(log[1].contains("clip2"))
        assertTrue(log[2].contains("clip3"))
        assertTrue(store.all().all { it.state == State.DONE })
    }

    @Test
    fun queue_failure_marks_failed_and_continues() = runBlocking {
        val store = store()
        store.add(job("j1", "clip1"))
        store.add(job("j2", "clip2"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log, failOn = "clip1-dng"), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        assertEquals(2, ran) // the failed job ran too; the queue continued
        val jobs = store.all()
        assertEquals(State.FAILED, jobs[0].state)
        // The failure detail names stage + cause + recovery (the mapped
        // DngExportErrors message, not a bare "failed").
        assertTrue(jobs[0].detail!!.contains("stage: dng write"))
        assertTrue(jobs[0].detail!!.contains("recovery:"))
        assertEquals(State.DONE, jobs[1].state)
        // The failed job left no file behind (partial discarded).
        assertTrue(!File(tmp.root, "clip1-dng.dng").exists())
        assertTrue(!File(tmp.root, "clip1-dng.dng.partial").exists())
    }

    @Test
    fun queue_dispatches_mp4_to_the_mp4_exporter() = runBlocking {
        val store = store()
        store.add(job("j1", "clip1", Target.MP4))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        // The MP4 path went to the MP4 exporter (the fake session's open
        // fails, so the job is FAILED — via the MP4 exporter's message).
        assertEquals(1, log.size)
        assertTrue(log[0].startsWith("mp4:"))
        val jobs = store.all()
        assertEquals(State.FAILED, jobs[0].state)
        assertTrue(jobs[0].detail!!.startsWith("MP4 export failed"))
    }

    @Test
    fun mp4_success_reaches_done() = runBlocking {
        val store = store()
        store.add(job("j1", "clip1", Target.MP4))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log, openFail = false), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        val jobs = store.all()
        assertEquals(State.DONE, jobs[0].state)
        assertEquals("clip1-mp4.mp4", jobs[0].detail)
        assertTrue(File(tmp.root, "clip1-mp4.mp4").exists())
    }

    @Test
    fun retry_requeues_with_the_same_snapshot_and_reruns() = runBlocking {
        val store = store()
        val snapshot = GradeK(exposureEv = 1.5f, tempK = 6500f)
        store.add(job("j1", "clip1", snapshot = snapshot))
        val log = mutableListOf<String>()
        val failOnce = FakeDngRender(log, failOn = "clip1-dng")
        val queue = ExportQueue(
            store, DngExporter(failOnce, freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        assertEquals(State.FAILED, store.all()[0].state)

        // Retry: re-queue with the same snapshot (the store handles it),
        // then the queue runs it again — this time the exporter succeeds.
        val okRender = FakeDngRender(log)
        val queue2 = ExportQueue(
            store, DngExporter(okRender, freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        store.retry("j1")
        val requeued = store.all()[0]
        assertEquals(State.QUEUED, requeued.state)
        assertNull(requeued.detail) // the old failure detail is cleared
        assertEquals(snapshot, requeued.snapshot) // same grade snapshot
        queue2.runAll()
        val done = store.all()[0]
        assertEquals(State.DONE, done.state)
        assertEquals(snapshot, done.snapshot)
    }

    @Test
    fun queue_skips_non_queued_jobs() = runBlocking {
        val store = store()
        // A DONE and a FAILED job from an earlier session: neither runs again.
        store.add(job("j1", "clip1").copy(state = State.DONE, detail = "clip1-dng.dng"))
        store.add(job("j2", "clip2").copy(state = State.FAILED, detail = "boom"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        assertEquals(0, ran)
        assertTrue(log.isEmpty())
        // States untouched.
        assertEquals(State.DONE, store.all()[0].state)
        assertEquals(State.FAILED, store.all()[1].state)
    }

    @Test
    fun every_source_fd_is_closed_after_its_job() = runBlocking {
        val store = store()
        store.add(job("j1", "clip1"))
        store.add(job("j2", "clip2", Target.MP4))
        val log = mutableListOf<String>()
        val io = RecordingIo(tmp)
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            io,
        )
        queue.runAll()
        // One fd per job (DNG success + MP4 failure both hand one back), and
        // every handed-out fd was closed — the runner never leaks one, even
        // on the failure path.
        assertEquals(io.opened, io.closed)
        assertEquals(2, io.opened.size)
    }
}
