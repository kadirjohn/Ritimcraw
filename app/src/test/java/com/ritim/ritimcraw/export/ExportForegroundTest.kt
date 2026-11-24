package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Export foreground tests (owner finding 2026-11-14: a backgrounded
 * export was frozen mid-render by Android's OomAdjuster). Two layers:
 *
 *  - The CONTROLLER (pure state machine, the NativeSource
 *    unavailable-guard shape: every decision JVM-tested, every framework
 *    call behind the [ExportForegroundController.Sink] fake): RUNNING ->
 *    foreground, terminal -> stop, never both directions out of order,
 *    the >=1s progress throttle.
 *
 *  - The QUEUE integration: the real [ExportQueue] transitions drive a
 *    fake [ExportForeground] — start rides the pick-to-RUNNING flip,
 *    progress rides the queue-owned callback with the SAME frame counts
 *    the store holds, stop rides the terminal write (DONE, FAILED and
 *    the cooperative CANCELLED), and a job cancelled before the start
 *    NEVER gains the priority.
 */
class ExportForegroundTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- The pure controller ------------------------------------------

    /** Records every sink call in order (the fake seam). */
    private class RecordingSink : ExportForegroundController.Sink {
        val calls = mutableListOf<String>()

        override fun onStarted(jobId: String, title: String) {
            calls.add("start:$jobId:$title")
        }

        override fun onProgress(
            jobId: String,
            title: String,
            framesDone: Int,
            framesTotal: Int,
            percent: Int,
            indeterminate: Boolean,
        ) {
            calls.add("progress:$jobId:$framesDone/$framesTotal:$percent:$indeterminate")
        }

        override fun onStopped(jobId: String) {
            calls.add("stop:$jobId")
        }
    }

    @Test
    fun controller_starts_on_running_and_stops_on_terminal() {
        // The happy path, in order: start carries the title, stop lands
        // exactly once.
        val sink = RecordingSink()
        val controller = ExportForegroundController(sink)
        controller.start("j1", "Sunset.mp4")
        controller.stop("j1")
        assertEquals(listOf("start:j1:Sunset.mp4", "stop:j1"), sink.calls)
    }

    @Test
    fun controller_progress_for_a_never_started_job_is_dropped() {
        // A notification can never appear for a job that is not RUNNING.
        val sink = RecordingSink()
        val controller = ExportForegroundController(sink)
        controller.progress("j1", 3, 10)
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun controller_progress_after_stop_is_dropped_and_stop_is_idempotent() {
        // The terminal write's stop is the ONLY stop; anything late is a
        // no-op — the notification is removed last, never resurrected.
        val sink = RecordingSink()
        val controller = ExportForegroundController(sink)
        controller.start("j1", "Sunset.mp4")
        controller.stop("j1")
        controller.progress("j1", 9, 10)
        controller.stop("j1")
        assertEquals(listOf("start:j1:Sunset.mp4", "stop:j1"), sink.calls)
    }

    @Test
    fun controller_throttles_progress_to_one_update_per_second() {
        // The >=1s notification window (the UI's 500ms store-write
        // throttle is finer than a notification needs): the first report
        // passes, a report inside the window is dropped, one after it
        // passes again. The clock is injected — the ProgressWriter
        // precedent.
        val sink = RecordingSink()
        var now = 0L
        val controller = ExportForegroundController(sink, clock = { now })
        controller.start("j1", "Sunset.mp4")
        controller.progress("j1", 1, 10) // first report always passes
        now = 500L
        controller.progress("j1", 5, 10) // inside the window: dropped
        now = 999L // one millisecond short of the window: still dropped
        controller.progress("j1", 6, 10)
        now = 1000L // exactly the full window since the last update: passes
        controller.progress("j1", 7, 10)
        assertEquals(
            listOf(
                "start:j1:Sunset.mp4",
                "progress:j1:1/10:10:false",
                "progress:j1:7/10:70:false",
            ),
            sink.calls,
        )
    }

    @Test
    fun controller_recomputes_the_throttle_window_after_a_new_start() {
        // A retry of the same job (or the next job in the queue) starts
        // with a fresh window — the first report always shows immediately.
        val sink = RecordingSink()
        var now = 0L
        val controller = ExportForegroundController(sink, clock = { now })
        controller.start("j1", "A.mp4")
        controller.progress("j1", 1, 10)
        now = 100L
        controller.stop("j1")
        controller.start("j1", "A.mp4")
        controller.progress("j1", 1, 10) // fresh window: passes despite 100ms
        assertEquals(
            listOf(
                "start:j1:A.mp4",
                "progress:j1:1/10:10:false",
                "stop:j1",
                "start:j1:A.mp4",
                "progress:j1:1/10:10:false",
            ),
            sink.calls,
        )
    }

    @Test
    fun controller_a_start_while_another_job_holds_priority_stops_the_old_one() {
        // Defensive path (the serial queue stops a job before starting the
        // next): the service still never outlives its job.
        val sink = RecordingSink()
        val controller = ExportForegroundController(sink)
        controller.start("j1", "A.mp4")
        controller.start("j2", "B.mp4")
        assertEquals(
            listOf("start:j1:A.mp4", "stop:j1", "start:j2:B.mp4"),
            sink.calls,
        )
    }

    @Test
    fun controller_zero_total_shows_an_indeterminate_bar() {
        // An exporter that reports (0, 0) must not divide by zero: the
        // bar goes indeterminate instead.
        val sink = RecordingSink()
        val controller = ExportForegroundController(sink)
        controller.start("j1", "A.mp4")
        controller.progress("j1", 0, 0)
        assertEquals("progress:j1:0/0:0:true", sink.calls[1])
    }

    // ---- The queue integration ----------------------------------------

    /** The fake foreground seam: records start/progress/stop per job. */
    private class RecordingForeground : ExportForeground {
        val events = mutableListOf<String>()

        override fun start(jobId: String, title: String) {
            events.add("start:$jobId:$title")
        }

        override fun progress(jobId: String, framesDone: Int, framesTotal: Int) {
            events.add("progress:$jobId:$framesDone/$framesTotal")
        }

        override fun stop(jobId: String) {
            events.add("stop:$jobId")
        }
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-foreground").apply { isDaemon = true }
        }

    private fun store(): ExportStore = ExportStore(File(tmp.root, "project.json"))

    private fun job(
        id: String,
        sourceId: String = "clip1",
        target: Target = Target.DNG,
    ): ExportJob = ExportJob(id, sourceId, GradeK(), target, State.QUEUED)

    /** The DNG fake from ExportQueueTest's shape: writes a real file so the
     * staging/rename path runs; reports (0,1) then (1,1). */
    private class FakeDngRender(
        val log: MutableList<String>,
        val failOn: String? = null,
    ) : DngRender {
        override fun render(
            fd: Int,
            frameIndex: Int,
            manualRotationDeg: Int,
            cropX: Float,
            cropY: Float,
            cropW: Float,
            cropH: Float,
            outPath: String,
        ): Boolean {
            log.add("dng:${File(outPath).name}")
            if (failOn != null && File(outPath).name.startsWith(failOn)) return false
            File(outPath).writeBytes(ByteArray(16))
            return true
        }

        override fun renderLinear(
            fd: Int,
            frameIndex: Int,
            manualRotationDeg: Int,
            cropX: Float,
            cropY: Float,
            cropW: Float,
            cropH: Float,
            outPath: String,
        ): Boolean {
            log.add("linear:${File(outPath).name}")
            if (failOn != null && File(outPath).name.startsWith(failOn)) return false
            File(outPath).writeBytes(ByteArray(16))
            return true
        }

        override fun code(): Int = DngExportErrors.CODE_IO
        override fun stage(): Int = DngExportErrors.STAGE_DNG_WRITE
        override fun detail(): String? = "fake io failure"
    }

    /** The fake MP4 session: 3 frames, so progress reports flow. */
    private class FakeMp4Session : Mp4Session {
        override fun open(fd: Int): Mp4SessionInfo? =
            Mp4SessionInfo(handle = 1L, frameCount = 3, width = 4, height = 4, cfa = 0, orientationDeg = 0)

        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?, gyroPath: String?, chainWire: FloatArray?): ByteArray? =
            ByteArray(4 * 4 * 3)

        override fun framePts(handle: Long, frameIndex: Int): Long = frameIndex * 33_336L
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray? = null
        override fun close(handle: Long) {}
        override fun code(): Int = 0
        override fun stage(): Int = 0
        override fun detail(): String? = null
    }

    private class FakeWriter : Mp4MediaWriter {
        override fun start(outPath: String, width: Int, height: Int, orientationDeg: Int, sampleRate: Int, channels: Int) {
            File(outPath).writeBytes(ByteArray(16))
        }

        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
        override fun finish(): Boolean = true
        override fun release() {}
    }

    private class RecordingIo(private val tmp: TemporaryFolder) : ExportIo {
        val opened = mutableListOf<Int>()
        private var next = 7

        override fun sourceFd(job: ExportJob): Int {
            val fd = next++
            opened.add(fd)
            return fd
        }

        override fun closeFd(fd: Int) {}
        override fun outputFile(job: ExportJob, target: Target): File = File(tmp.root, job.fileName)
    }

    private class FakePngWriter : PngWriter {
        override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean {
            File(outPath).writeBytes(ByteArray(16))
            return true
        }
    }

    /**
     * Builds the queue with the recording foreground seam, mirroring the
     * ExportQueueTest constructions (all positional args + the new named
     * foreground).
     */
    private fun queue(
        store: ExportStore,
        foreground: ExportForeground,
        dng: DngExporter,
        mp4: Mp4Exporter,
        photo: PhotoExporter,
    ): ExportQueue = ExportQueue(
        store, dng, mp4, photo, RecordingIo(tmp),
        foreground = foreground,
    )

    @Test
    fun queue_start_rides_the_running_flip_and_stop_rides_the_terminal_write() = runBlocking {
        // The full contract on a DONE export: start AFTER the RUNNING write
        // (with the job's display-derived file name as the title), progress
        // with the SAME counts the store holds, stop after DONE.
        val store = store()
        store.add(job("j1").copy(outputName = "Sunset Trip.dng"))
        val foreground = RecordingForeground()
        val log = mutableListOf<String>()
        val queue = queue(
            store, foreground,
            DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(), FakePngWriter(), freshEngine()),
        )
        queue.runAll()
        // DNG reports (0,1) then (1,1): both land before the terminal stop.
        assertEquals(
            listOf(
                "start:j1:Sunset Trip.dng",
                "progress:j1:0/1",
                "progress:j1:1/1",
                "stop:j1",
            ),
            foreground.events,
        )
        assertEquals(State.DONE, store.all().single().state)
    }

    @Test
    fun queue_stop_rides_the_failed_terminal_too() = runBlocking {
        // A FAILED export releases the priority the same way — the
        // service never outlives the job whatever the outcome.
        val store = store()
        store.add(job("j1"))
        val foreground = RecordingForeground()
        val log = mutableListOf<String>()
        val queue = queue(
            store, foreground,
            DngExporter(FakeDngRender(log, failOn = "clip1"), freshEngine()),
            Mp4Exporter(FakeMp4Session(), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(), FakePngWriter(), freshEngine()),
        )
        queue.runAll()
        assertEquals(State.FAILED, store.all().single().state)
        assertEquals(
            listOf("start:j1:clip1-dng.dng", "progress:j1:0/1", "stop:j1"),
            foreground.events,
        )
    }

    @Test
    fun queue_stop_rides_the_cancelled_terminal_at_the_frame_boundary() = runBlocking {
        // The cooperative stop: cancel() itself never touches the seam
        // (its CANCELLED flip is optimistic UI state); the drain confirms
        // CANCELLED at the next frame boundary and the terminal write +
        // stop land there. The service never outlives a cancelled job.
        val store = store()
        store.add(job("j1", target = Target.MP4))
        val foreground = RecordingForeground()
        val log = mutableListOf<String>()
        val inFirst = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = object : Mp4Session {
            override fun open(fd: Int): Mp4SessionInfo? =
                Mp4SessionInfo(handle = 1L, frameCount = 3, width = 4, height = 4, cfa = 0, orientationDeg = 0)

            override fun renderFrame(handle: Long, frameIndex: Int, call: GradeK, lutPath: String?, gyroPath: String?, chainWire: FloatArray?): ByteArray? {
                log.add("render:$frameIndex")
                if (frameIndex == 0) {
                    inFirst.countDown()
                    release.await()
                }
                return ByteArray(4 * 4 * 3)
            }

            override fun framePts(handle: Long, frameIndex: Int): Long = frameIndex * 33_336L
            override fun audioInfo(handle: Long): IntArray? = null
            override fun readAudio(handle: Long): ShortArray? = null
            override fun close(handle: Long) {}
            override fun code(): Int = 0
            override fun stage(): Int = 0
            override fun detail(): String? = null
        }
        val queue = queue(
            store, foreground,
            DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(session, { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(), FakePngWriter(), freshEngine()),
        )
        val drain = async(Dispatchers.Default) { queue.runAll() }
        assertTrue(inFirst.await(5, TimeUnit.SECONDS))
        assertTrue(queue.cancel("j1"))
        release.countDown()
        assertEquals(1, drain.await())
        assertEquals(State.CANCELLED, store.all().single().state)
        // Start rode the RUNNING flip; the (0,3) report landed before the
        // cancel; the stop rode the terminal CANCELLED write.
        assertEquals(
            listOf("start:j1:clip1-mp4.mp4", "progress:j1:0/3", "stop:j1"),
            foreground.events,
        )
    }

    @Test
    fun queue_a_job_cancelled_before_the_start_never_gains_the_priority() = runBlocking {
        // The pick-to-RUNNING early return (a row cancelled/cleared before
        // the start) never reaches the start hook — a never-started job
        // never holds the process priority.
        val store = store()
        val j1 = job("j1")
        store.add(j1)
        val foreground = RecordingForeground()
        val log = mutableListOf<String>()
        val queue = queue(
            store, foreground,
            DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(), FakePngWriter(), freshEngine()),
        )
        assertTrue(queue.cancel("j1"))
        queue.runOne(j1)
        assertTrue(foreground.events.isEmpty())
        assertEquals(State.CANCELLED, store.all().single().state)
    }

    @Test
    fun queue_two_jobs_in_sequence_start_and_stop_each_in_turn() = runBlocking {
        // The serial queue: job 1's stop lands before job 2's start — the
        // controller's defensive old-job stop never fires for real.
        val store = store()
        store.add(job("j1"))
        store.add(job("j2", sourceId = "clip2"))
        val foreground = RecordingForeground()
        val log = mutableListOf<String>()
        val queue = queue(
            store, foreground,
            DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(), FakePngWriter(), freshEngine()),
        )
        queue.runAll()
        assertEquals(
            listOf(
                "start:j1:clip1-dng.dng",
                "progress:j1:0/1",
                "progress:j1:1/1",
                "stop:j1",
                "start:j2:clip2-dng.dng",
                "progress:j2:0/1",
                "progress:j2:1/1",
                "stop:j2",
            ),
            foreground.events,
        )
        assertEquals(State.DONE, store.all()[0].state)
        assertEquals(State.DONE, store.all()[1].state)
    }
}
