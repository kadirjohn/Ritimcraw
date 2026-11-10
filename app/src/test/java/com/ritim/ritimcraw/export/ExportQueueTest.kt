package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?): ByteArray? =
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
    fun stale_running_job_is_recovered_to_failed_on_drain() = runBlocking {
        // Owner finding 2026-11-10: a crash killed the process with jobs
        // RUNNING; the restarted runner skips non-QUEUED rows, so they
        // displayed "Exporting…" forever. The drain must first mark them
        // FAILED with the interrupted cause + recovery.
        val store = store()
        val snapshot = GradeK(exposureEv = 0.75f)
        store.add(
            job("j1", "clip1", snapshot = snapshot)
                .copy(state = State.RUNNING)
        )
        store.add(job("j2", "clip2")) // a live QUEUED job still runs
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        val jobs = store.all()
        // The stale row was recovered (not run), the queued one ran.
        assertEquals(1, ran)
        assertEquals(State.FAILED, jobs[0].state)
        assertEquals(ExportQueue.INTERRUPTED_DETAIL, jobs[0].detail)
        assertEquals(snapshot, jobs[0].snapshot) // snapshot untouched
        assertEquals(State.DONE, jobs[1].state)
        // Only clip2 reached an exporter.
        assertEquals(1, log.size)
        assertTrue(log[0].contains("clip2"))
    }

    @Test
    fun recover_interrupted_is_idempotent_and_retry_still_requeues() = runBlocking {
        val store = store()
        val snapshot = GradeK(tempK = 5000f)
        store.add(job("j1", "clip1", snapshot = snapshot).copy(state = State.RUNNING))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        assertEquals(1, queue.recoverInterrupted())
        // Second pass: nothing left RUNNING — no double failure, no wedge.
        assertEquals(0, queue.recoverInterrupted())
        assertEquals(State.FAILED, store.all().single().state)

        // Retry after recovery still re-queues the same snapshot and runs.
        store.retry("j1")
        val requeued = store.all().single()
        assertEquals(State.QUEUED, requeued.state)
        assertNull(requeued.detail)
        assertEquals(snapshot, requeued.snapshot)
        queue.runAll()
        assertEquals(State.DONE, store.all().single().state)
        assertEquals(snapshot, store.all().single().snapshot)
    }

    @Test
    fun progress_first_report_writes_and_later_reports_throttle() = runBlocking {
        // A fake clock the test advances by hand: no real sleeping, and the
        // throttle boundary is deterministic.
        var now = 0L
        val store = store()
        store.add(job("j1", "clip1", Target.MP4))
        val log = mutableListOf<String>()
        val session = object : Mp4Session {
            override fun open(fd: Int) =
                Mp4SessionInfo(1L, 3, 4, 4, 0, 0)

            override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?) =
                ByteArray(4 * 4 * 3)

            override fun framePts(handle: Long, frameIndex: Int): Long = 0
            override fun audioInfo(handle: Long): IntArray? = null
            override fun readAudio(handle: Long): ShortArray? = null
            override fun close(handle: Long) {}
            override fun code(): Int = 0
            override fun stage(): Int = 0
            override fun detail(): String? = null
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(session, { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
            progressClock = { now },
        )
        queue.runAll()

        // While the job ran, the store held RUNNING + frame counts, and the
        // FIRST report (0/3, right after configure) wrote immediately. The
        // final persisted row is terminal: DONE with counts cleared.
        val final = store.all().single()
        assertEquals(State.DONE, final.state)
        assertNull(final.framesDone)
        assertNull(final.framesTotal)
    }

    @Test
    fun progress_writer_throttles_by_wall_interval() {
        // ProgressWriter directly: the first report always writes, later
        // ones only after PROGRESS_WRITE_MIN_INTERVAL_MS of clock time.
        var now = 1000L
        val store = store()
        store.add(job("j1", "clip1"))
        val w = ProgressWriter(store, store.all().single(), { now })

        assertTrue(w.report(0, 310)) // first write always lands
        assertFalse(w.report(1, 310)) // 0 ms later: throttled
        assertFalse(w.report(2, 310)) // still throttled
        now += ExportQueue.PROGRESS_WRITE_MIN_INTERVAL_MS
        assertTrue(w.report(142, 310)) // exactly one interval: writes
        assertFalse(w.report(143, 310))
        now += ExportQueue.PROGRESS_WRITE_MIN_INTERVAL_MS - 1
        assertFalse(w.report(200, 310)) // 1 ms short: throttled
        now += 1
        assertTrue(w.report(310, 310))

        // What reached the store is the LAST written pair, state RUNNING.
        val persisted = store.all().single()
        assertEquals(State.RUNNING, persisted.state)
        assertEquals(310, persisted.framesDone)
        assertEquals(310, persisted.framesTotal)
    }

    @Test
    fun failed_job_reports_no_stale_progress_and_does_not_wedge() = runBlocking {
        val store = store()
        store.add(job("j1", "clip1")) // fails (failOn)
        store.add(job("j2", "clip2")) // succeeds
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log, failOn = "clip1-dng"), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        assertEquals(2, ran)
        val jobs = store.all()
        assertEquals(State.FAILED, jobs[0].state)
        assertNull(jobs[0].framesDone) // no stale progress on the dead row
        assertNull(jobs[0].framesTotal)
        assertEquals(State.DONE, jobs[1].state)
        assertNull(jobs[1].framesDone)
    }

    @Test
    fun mediastore_placement_maps_each_target_to_collection_folder_mime() {
        // The publish destination per target (owner finding 2026-11-10:
        // finished exports were invisible outside the app). MP4 -> the
        // video collection under Movies/ritiMCRAW; DNG -> the images
        // collection under Pictures/ritiMCRAW with the DNG mime (some
        // viewers will not render a CFA DNG — the raw mosaic is intact).
        val mp4 = MediaPublish.placement("clip1", Target.MP4)
        assertEquals(MediaPublish.Collection.VIDEO, mp4.collection)
        assertEquals("Movies/ritiMCRAW", mp4.relativePath)
        assertEquals("video/mp4", mp4.mime)
        assertEquals("clip1-mp4.mp4", mp4.name)

        val dng = MediaPublish.placement("clip1", Target.DNG)
        assertEquals(MediaPublish.Collection.IMAGES, dng.collection)
        assertEquals("Pictures/ritiMCRAW", dng.relativePath)
        assertEquals("image/x-adobe-dng", dng.mime)
        assertEquals("clip1-dng.dng", dng.name)
    }

    @Test
    fun done_job_publishes_output_and_publish_failure_never_fails_job() = runBlocking {
        // The queue asks the publisher for every DONE job, with the job's
        // own output file — and a THROWING publisher must not fail the job
        // or wedge the queue (the next job still runs).
        val store = store()
        store.add(job("j1", "clip1"))
        store.add(job("j2", "clip2"))
        val log = mutableListOf<String>()
        val published = mutableListOf<Pair<String, String>>()
        var failFirst = true
        val publisher = object : ExportPublisher {
            override suspend fun publish(job: ExportJob, output: File): Boolean {
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("resolver exploded")
                }
                published.add(job.id to output.name)
                return true
            }
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
            publisher,
        )
        val ran = queue.runAll()
        assertEquals(2, ran)
        val jobs = store.all()
        // j1: the publisher threw — the job is still DONE and the queue
        // moved on to j2, which published normally.
        assertEquals(State.DONE, jobs[0].state)
        assertEquals(State.DONE, jobs[1].state)
        assertEquals(listOf("j2" to "clip2-dng.dng"), published)
        // The published file is the job's own internal output (the source
        // of truth stays files/exports; the MediaStore copy is additive).
        assertTrue(File(tmp.root, "clip1-dng.dng").exists())
        assertTrue(File(tmp.root, "clip2-dng.dng").exists())
    }

    @Test
    fun failed_job_is_not_published() = runBlocking {
        // Only DONE jobs publish: a FAILED export (nothing was written)
        // must not put a half-file into the media store.
        val store = store()
        store.add(job("j1", "clip1")) // fails (failOn)
        val log = mutableListOf<String>()
        val publisher = object : ExportPublisher {
            override suspend fun publish(job: ExportJob, output: File): Boolean {
                throw AssertionError("a failed job must not be published")
            }
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log, failOn = "clip1-dng"), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            RecordingIo(tmp),
            publisher,
        )
        queue.runAll()
        assertEquals(State.FAILED, store.all().single().state)
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
