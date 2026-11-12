package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.async
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
        override fun render(fd: Int, frameIndex: Int, manualRotationDeg: Int, outPath: String): Boolean {
            log.add("dng:$fd:${File(outPath).name}")
            if (failOn != null && File(outPath).name.startsWith(failOn)) return false
            File(outPath).writeBytes(ByteArray(16))
            return true
        }

        override fun renderLinear(fd: Int, frameIndex: Int, manualRotationDeg: Int, outPath: String): Boolean {
            log.add("linear:$fd:${File(outPath).name}")
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

        override fun renderFrame(
            handle: Long,
            frameIndex: Int,
            grade: GradeK,
            lutPath: String?,
            gyroPath: String?,
        ): ByteArray? = ByteArray(4 * 4 * 3)

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

    /**
     * The fake PNG boundary (RAW-11 Frame Picker): records the handed-over
     * dimensions and writes a real file so the staged rename runs for real.
     * Scriptable failure via [failOn] (matched against the output path's
     * name), mirroring [FakeDngRender].
     */
    private class FakePngWriter(
        val log: MutableList<String>,
        val failOn: String? = null,
    ) : PngWriter {
        val sizes = mutableListOf<Triple<Int, Int, Int>>() // argb count, width, height

        override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean {
            sizes.add(Triple(argb.size, width, height))
            log.add("png:${File(outPath).name}")
            if (failOn != null && File(outPath).name.startsWith(failOn)) return false
            File(outPath).writeBytes(ByteArray(16))
            return true
        }
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
            // Faithful to the real ExportIo (AppNav): the job's own file
            // name — the display-derived one when captured (§2b), the legacy
            // <sourceId>-<target> name otherwise.
            File(tmp.root, job.fileName)
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
        // RAW-11 Frame Picker: a developed frame lands as a PNG.
        assertEquals("clip1-photo.png", ExportQueue.outputName("clip1", Target.PHOTO))
        // RAW-13: the linear target's name disambiguates the legacy ".dng"
        // — a Linear DNG is a separate file contract from the CFA DNG.
        assertEquals("clip1-linear.dng", ExportQueue.outputName("clip1", Target.LINEAR))
    }

    // ---- DISPLAY-DERIVED OUTPUT NAMES (owner request 2026-11-11 §2b) ----

    @Test
    fun display_output_name_swaps_the_extension_per_target() {
        // "Sunset Trip.raw" -> base "Sunset Trip", then the target's own
        // extension — the clip's extension is never the output's.
        assertEquals("Sunset Trip.dng", ExportQueue.displayOutputName("Sunset Trip.raw", Target.DNG))
        assertEquals("Sunset Trip.mp4", ExportQueue.displayOutputName("Sunset Trip.raw", Target.MP4))
        // A name with no extension is used as-is.
        assertEquals("Sunset Trip.dng", ExportQueue.displayOutputName("Sunset Trip", Target.DNG))
        // RAW-11: a photo job swaps in the PNG extension.
        assertEquals("Sunset Trip.png", ExportQueue.displayOutputName("Sunset Trip.raw", Target.PHOTO))
        // RAW-13: a Linear DNG IS a .dng file — the display name keeps the
        // extension; the legacy name is what disambiguates.
        assertEquals("Sunset Trip.dng", ExportQueue.displayOutputName("Sunset Trip.raw", Target.LINEAR))
    }

    @Test
    fun display_output_name_strips_hostile_characters_and_control_chars() {
        // Path separators, reserved Windows chars, and control characters
        // are REMOVED (never replaced with a placeholder).
        assertEquals(
            "NightMarketraw.dng",
            ExportQueue.displayOutputName("Night/Market:\\raw?.dng", Target.DNG),
        )
        assertEquals(
            "clip.dng",
            ExportQueue.displayOutputName("clip\u0007\u001b.dng", Target.DNG),
        )
        // Leading/trailing spaces and dots (Windows dot-trimming hazards)
        // are stripped.
        assertEquals(
            "clip.dng",
            ExportQueue.displayOutputName("  ..clip..  .dng", Target.DNG),
        )
    }

    @Test
    fun display_output_name_returns_null_when_nothing_is_left() {
        // All-hostile input: the caller keeps the legacy name instead.
        assertNull(ExportQueue.displayOutputName("///???.raw", Target.DNG))
        assertNull(ExportQueue.displayOutputName("   ", Target.MP4))
        assertNull(ExportQueue.displayOutputName("", Target.DNG))
    }

    @Test
    fun display_output_name_caps_the_base_at_200_utf8_bytes() {
        // 201 two-byte characters: 200 bytes of base + the target's own
        // extension. The cap is on the BASE, not the whole name.
        val long = "ğ".repeat(201)
        val capped = ExportQueue.displayOutputName(long, Target.DNG)
        assertEquals(200, capped!!.dropLast(4).toByteArray(Charsets.UTF_8).size)
        assertEquals("ğ".repeat(100), capped.dropLast(4))
        // A 4-byte character never gets cut in half: the cap backs off to
        // the previous whole character (no lone surrogate is written).
        val emoji = "😀".repeat(51) // 204 bytes
        val cappedEmoji = ExportQueue.displayOutputName(emoji, Target.MP4)
        val emojiBase = cappedEmoji!!.removeSuffix(".mp4")
        assertEquals(200, emojiBase.toByteArray(Charsets.UTF_8).size)
        // 50 whole emoji (200 bytes): the 51st would overflow, and the
        // surrogate pair is never split.
        assertEquals(50, emojiBase.codePointCount(0, emojiBase.length))
        assertTrue(emojiBase == "😀".repeat(50))
    }

    @Test
    fun job_file_name_is_the_display_name_with_legacy_fallback() {
        // A job that captured a display-derived name at enqueue uses it for
        // EVERY path that touches its file; a pre-§2b row (null name) falls
        // back to the legacy <sourceId>-<target> name.
        val named = job("j1", "clip1").copy(outputName = "Sunset Trip.dng")
        assertEquals("Sunset Trip.dng", named.fileName)
        assertEquals("clip1-dng.dng", job("j1", "clip1").fileName)
    }

    @Test
    fun queue_writes_the_display_derived_file_name() = runBlocking {
        // End to end: a job with a captured outputName produces THAT file —
        // the staged .partial, the final rename, the DONE detail, and the
        // file on disk all carry the display-derived name.
        val store = store()
        store.add(job("j1", "clip1").copy(outputName = "Sunset Trip.dng"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        val done = store.all().single()
        assertEquals(State.DONE, done.state)
        assertEquals("Sunset Trip.dng", done.detail)
        assertEquals("Sunset Trip.dng", done.fileName)
        // The staged partial and the final file both used the name.
        assertEquals("dng:7:Sunset Trip.dng.partial", log[0])
        assertTrue(File(tmp.root, "Sunset Trip.dng").exists())
        assertTrue(!File(tmp.root, "clip1-dng.dng").exists())
    }

    @Test
    fun retry_keeps_the_display_derived_name() = runBlocking {
        // Retry re-runs the SAME job: it must overwrite its own output, so
        // the captured name survives the requeue (requeued() does not clear
        // it) and the second run writes the same file.
        val store = store()
        store.add(job("j1", "clip1").copy(outputName = "Sunset Trip.dng"))
        val log = mutableListOf<String>()
        val failOnce = FakeDngRender(log, failOn = "Sunset Trip")
        val queue = ExportQueue(
            store, DngExporter(failOnce, freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        assertEquals(State.FAILED, store.all().single().state)

        val okRender = FakeDngRender(log)
        val queue2 = ExportQueue(
            store, DngExporter(okRender, freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        store.retry("j1")
        val requeued = store.all().single()
        assertEquals("Sunset Trip.dng", requeued.outputName) // kept
        assertEquals("Sunset Trip.dng", requeued.fileName)
        queue2.runAll()
        val done = store.all().single()
        assertEquals(State.DONE, done.state)
        assertEquals("Sunset Trip.dng", done.detail)
        assertTrue(File(tmp.root, "Sunset Trip.dng").exists())
    }

    @Test
    fun mediastore_placement_uses_the_job_name_with_legacy_fallback() {
        // The MediaStore DISPLAY_NAME is the job's own output name (§2b);
        // a same-name collision is uniquified by the platform, and DONE rows
        // open via the job's publishedUri — so a suffixed rename never
        // misroutes an open.
        val named = MediaPublish.placement("clip1", Target.DNG, "Sunset Trip.dng")
        assertEquals("Sunset Trip.dng", named.name)
        assertEquals(MediaPublish.Collection.IMAGES, named.collection)
        assertEquals("Pictures/ritiMCRAW", named.relativePath)
        assertEquals("image/x-adobe-dng", named.mime)

        // Null name (pre-§2b row / unresolvable display): the legacy name.
        val legacy = MediaPublish.placement("clip1", Target.DNG, null)
        assertEquals("clip1-dng.dng", legacy.name)
        assertEquals("Pictures/ritiMCRAW", legacy.relativePath)
    }

    @Test
    fun queue_runs_one_job_to_done() = runBlocking {
        val store = store()
        store.add(job("j1"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        val jobs = store.all()
        assertEquals(State.DONE, jobs[0].state)
        assertEquals("clip1-mp4.mp4", jobs[0].detail)
        assertTrue(File(tmp.root, "clip1-mp4.mp4").exists())
    }

    // ---- LINEAR arm (RAW-13 Linear DNG) ----

    @Test
    fun queue_dispatches_linear_to_the_dng_exporter_render_linear() = runBlocking {
        // A LINEAR job rides the DNG arm (the shared exporter, staging, and
        // error mapping) but must reach renderLinear — never the CFA render —
        // and land as <sourceId>-linear.dng.
        val store = store()
        store.add(job("j1", "clip1", Target.LINEAR).copy(frameIndex = 7))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        assertEquals(1, log.size)
        assertEquals("linear:7:clip1-linear.dng.partial", log[0])
        val jobs = store.all()
        assertEquals(State.DONE, jobs[0].state)
        assertEquals("clip1-linear.dng", jobs[0].detail)
        assertTrue(File(tmp.root, "clip1-linear.dng").exists())
        assertTrue(!File(tmp.root, "clip1-linear.dng.partial").exists())
    }

    // ---- PHOTO arm (RAW-11 Frame Picker) ----

    @Test
    fun queue_dispatches_photo_to_the_photo_exporter() = runBlocking {
        // Same shape as queue_dispatches_mp4_to_the_mp4_exporter: a PHOTO
        // job must reach the PHOTO arm — the fake session's open fails, so
        // the job is FAILED via the photo path's mapped native message.
        val store = store()
        store.add(job("j1", "clip1", Target.PHOTO))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        // The photo path went to the photo exporter: the session was
        // opened (through the photo arm), and the DNG/MP4 exporters were
        // never touched.
        assertEquals(1, log.size)
        assertTrue(log[0].startsWith("mp4:"))
        val jobs = store.all()
        assertEquals(State.FAILED, jobs[0].state)
        // The photo path reuses the native message mapper (the same open
        // seam the MP4 path uses), so the detail names the shared native
        // stage — the recovery wording is what tells the user what to do.
        assertTrue(jobs[0].detail!!.contains("stage: open"))
        assertTrue(jobs[0].detail!!.contains("recovery:"))
    }

    @Test
    fun photo_success_reaches_done() = runBlocking {
        // The full photo pipeline: the fake session opens (openFail = false
        // — the default fake fails open), the developed frame reaches the
        // PNG writer as packed pixels, and the staged partial is renamed
        // into the final PNG with the DONE detail naming it.
        val store = store()
        store.add(job("j1", "clip1", Target.PHOTO).copy(frameIndex = 2))
        val log = mutableListOf<String>()
        val png = FakePngWriter(log)
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log, openFail = false), png, freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        val jobs = store.all()
        assertEquals(State.DONE, jobs[0].state)
        assertEquals("clip1-photo.png", jobs[0].detail)
        // The file really exists and no partial is left behind.
        assertTrue(File(tmp.root, "clip1-photo.png").exists())
        assertTrue(!File(tmp.root, "clip1-photo.png.partial").exists())
        // The writer saw the 4x4 frame (48 RGB bytes -> 16 packed pixels)
        // written to the STAGED path, not the final name.
        assertEquals(listOf(Triple(16, 4, 4)), png.sizes)
        assertEquals("png:clip1-photo.png.partial", log.last())
    }

    @Test
    fun photo_png_write_failure_fails_the_job_and_cleans_up() = runBlocking {
        // The PNG writer failing to write the staged file is a Kotlin-stage
        // failure: the job is FAILED with the photo message (stage: png
        // encode, cause, recovery), the partial is discarded, and no file
        // is left behind.
        val store = store()
        store.add(job("j1", "clip1", Target.PHOTO))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log, openFail = false), FakePngWriter(log, failOn = "clip1-photo"), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        val jobs = store.all()
        assertEquals(State.FAILED, jobs[0].state)
        assertTrue(jobs[0].detail!!.contains("Photo export failed (stage: png encode)"))
        assertTrue(jobs[0].detail!!.contains("recovery:"))
        // Neither the final file nor the partial exists.
        assertTrue(!File(tmp.root, "clip1-photo.png").exists())
        assertTrue(!File(tmp.root, "clip1-photo.png.partial").exists())
    }

    @Test
    fun photo_job_carries_the_picked_frame_to_the_native_seam() = runBlocking {
        // The picker's whole contract: the job's frameIndex — the transport
        // position at queue time — is the frame the native develop renders.
        // The fake session records every renderFrame's frameIndex; the
        // job's index must be among them, exactly as handed over.
        val store = store()
        store.add(job("j1", "clip1", Target.PHOTO).copy(frameIndex = 7))
        val log = mutableListOf<String>()
        val session = object : Mp4Session {
            val renderedFrames = mutableListOf<Int>()

            override fun open(fd: Int) =
                Mp4SessionInfo(1L, 10, 4, 4, 0, 0)

            override fun renderFrame(
                handle: Long,
                frameIndex: Int,
                grade: GradeK,
                lutPath: String?,
                gyroPath: String?,
            ): ByteArray? {
                renderedFrames.add(frameIndex)
                return ByteArray(4 * 4 * 3)
            }

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
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(session, FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        queue.runAll()
        assertEquals(State.DONE, store.all().single().state)
        assertEquals(listOf(7), session.renderedFrames)
    }

    @Test
    fun photo_job_runs_serially_with_the_other_targets() = runBlocking {
        // Queue integration: a DNG and a PHOTO job in one drain, one at a
        // time, in queue order — both reach their own exporter and both
        // land DONE.
        val store = store()
        store.add(job("j1", "clip1")) // DNG
        store.add(job("j2", "clip1", Target.PHOTO))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log, openFail = false), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        val ran = queue.runAll()
        assertEquals(2, ran)
        // Queue order: the DNG render ran first, then the photo's session
        // open + PNG write.
        assertEquals("dng:7:clip1-dng.dng.partial", log[0])
        assertEquals("mp4:8", log[1])
        assertEquals("png:clip1-photo.png.partial", log[2])
        val jobs = store.all()
        assertTrue(jobs.all { it.state == State.DONE })
        assertTrue(File(tmp.root, "clip1-dng.dng").exists())
        assertTrue(File(tmp.root, "clip1-photo.png").exists())
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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

            override fun renderFrame(
                handle: Long,
                frameIndex: Int,
                grade: GradeK,
                lutPath: String?,
                gyroPath: String?,
            ) = ByteArray(4 * 4 * 3)

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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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

        // RAW-11: a developed frame is a finished photo — the images
        // collection as a PNG every gallery renders (the raw-mosaic DNG is
        // not).
        val photo = MediaPublish.placement("clip1", Target.PHOTO)
        assertEquals(MediaPublish.Collection.IMAGES, photo.collection)
        assertEquals("Pictures/ritiMCRAW", photo.relativePath)
        assertEquals("image/png", photo.mime)
        assertEquals("clip1-photo.png", photo.name)

        // RAW-13: a Linear DNG is a demosaiced image file — it publishes to
        // the images collection like the CFA DNG (same folder, same DNG mime;
        // the name disambiguates the two contracts).
        val linear = MediaPublish.placement("clip1", Target.LINEAR)
        assertEquals(MediaPublish.Collection.IMAGES, linear.collection)
        assertEquals("Pictures/ritiMCRAW", linear.relativePath)
        assertEquals("image/x-adobe-dng", linear.mime)
        assertEquals("clip1-linear.dng", linear.name)
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
            override suspend fun publish(job: ExportJob, output: File): String? {
                if (failFirst) {
                    failFirst = false
                    throw IllegalStateException("resolver exploded")
                }
                published.add(job.id to output.name)
                return "content://media/external/${job.id}"
            }
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            override suspend fun publish(job: ExportJob, output: File): String? {
                throw AssertionError("a failed job must not be published")
            }
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log, failOn = "clip1-dng"), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
            publisher,
        )
        queue.runAll()
        assertEquals(State.FAILED, store.all().single().state)
    }

    @Test
    fun done_job_stores_the_published_uri_on_its_row() = runBlocking {
        // DONE rows open on tap (owner request 2026-11-11 §1): the publish
        // returns the media-store row's URI and the queue stamps it on the
        // job's own terminal row — so the row opens ITS OWN file, never a
        // newer export of the same clip.
        val store = store()
        store.add(job("j1", "clip1"))
        store.add(job("j2", "clip2"))
        val log = mutableListOf<String>()
        val publisher = object : ExportPublisher {
            override suspend fun publish(job: ExportJob, output: File): String? =
                "content://media/external/${job.sourceId}/${output.name}"
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
            publisher,
        )
        queue.runAll()
        val jobs = store.all()
        assertEquals(State.DONE, jobs[0].state)
        assertEquals(State.DONE, jobs[1].state)
        assertEquals("content://media/external/clip1/clip1-dng.dng", jobs[0].publishedUri)
        assertEquals("content://media/external/clip2/clip2-dng.dng", jobs[1].publishedUri)
        // The URI survives a store reload (it lives in project.json).
        assertEquals(
            "content://media/external/clip1/clip1-dng.dng",
            ExportStore(File(tmp.root, "project.json")).all()[0].publishedUri,
        )
    }

    @Test
    fun null_publish_leaves_the_done_row_without_a_uri() = runBlocking {
        // A publish that returns null (skipped or failed) never fails the
        // job; the DONE row simply has no URI and the open path falls back
        // to the job's own internal output file.
        val store = store()
        store.add(job("j1", "clip1"))
        val log = mutableListOf<String>()
        val publisher = object : ExportPublisher {
            override suspend fun publish(job: ExportJob, output: File): String? = null
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
            publisher,
        )
        queue.runAll()
        val job = store.all().single()
        assertEquals(State.DONE, job.state)
        assertNull(job.publishedUri)
        // The output file exists: the fallback path has something to open.
        assertTrue(File(tmp.root, "clip1-dng.dng").exists())
    }

    @Test
    fun cleared_queue_is_not_resurrected_by_a_late_publish() = runBlocking {
        // The URI stamp re-reads the row BY ID and only stamps a row that is
        // still DONE: a queue cleared while the publish ran must stay empty
        // (update() would otherwise re-add the row), and a row that moved
        // on (retry) keeps its fresh state.
        val store = store()
        store.add(job("j1", "clip1"))
        val log = mutableListOf<String>()
        val publisher = object : ExportPublisher {
            override suspend fun publish(job: ExportJob, output: File): String? {
                // The queue is cleared WHILE the publish runs (the terminal
                // row has already been written; only the URI stamp is left).
                store.clear()
                return "content://media/external/late"
            }
        }
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
            publisher,
        )
        queue.runAll()
        assertTrue(store.all().isEmpty())
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            io,
        )
        queue.runAll()
        // One fd per job (DNG success + MP4 failure both hand one back), and
        // every handed-out fd was closed — the runner never leaks one, even
        // on the failure path.
        assertEquals(io.opened, io.closed)
        assertEquals(2, io.opened.size)
    }

    // ---- LIVE STATUS (owner request 2026-11-11 §1) ----

    @Test
    fun progress_writes_replace_the_whole_row() {
        // Every throttled progress write lands the full RUNNING row — the
        // writer is built from the RUNNING row (not the QUEUED input) and
        // carries the counts, so the UI reads a consistent row per write.
        var now = 1_000L
        val store = store()
        store.add(job("j1", "clip1"))
        // runOne's RUNNING write, simulated.
        val running = store.all().single().copy(state = State.RUNNING)
        store.update(running)
        val w = ProgressWriter(store, running, { now })
        assertTrue(w.report(0, 310))
        assertEquals(State.RUNNING, store.all().single().state)
        now += ExportQueue.PROGRESS_WRITE_MIN_INTERVAL_MS
        assertTrue(w.report(150, 310))
        val persisted = store.all().single()
        assertEquals(150, persisted.framesDone)
        assertEquals(310, persisted.framesTotal)
    }

    @Test
    fun revision_flow_bumps_on_every_queue_store_write() = runBlocking {
        // The revision flow is the live-status signal: every store write the
        // queue makes bumps it, so the UI collecting it re-reads the store
        // while the job runs (the flow carries the write count, not data).
        // The injected clock is CONSTANT, so the throttle is deterministic:
        // RUNNING write + first progress write land, the DNG exporter's
        // second report (1/1) is throttled out, then the terminal write.
        val store = store()
        store.add(job("j1", "clip1"))
        val log = mutableListOf<String>()
        val queue = ExportQueue(
            store, DngExporter(FakeDngRender(log), freshEngine()),
            Mp4Exporter(FakeMp4Session(log), { FakeWriter() }, freshEngine()),
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
            progressClock = { 1_000L },
        )
        val before = queue.revisionFlow().value
        queue.runAll()
        val after = queue.revisionFlow().value
        // RUNNING write + first progress write + terminal write = 3 bumps.
        assertEquals(before + 3, after)
    }

    @Test
    fun kick_during_active_drain_is_a_noop_and_never_marks_live_job_failed() = runBlocking {
        // KICK SAFETY (owner request 2026-11-11 §1): a second runAll while a
        // drain is active must NOT double-start the running job and must NOT
        // recoverInterrupted (which would mark the LIVE RUNNING job FAILED).
        // The active drain picks up the new job itself.
        val store = store()
        store.add(job("j1", "clip1", Target.MP4))
        val log = mutableListOf<String>()
        // A session whose open BLOCKS until the test releases it: the first
        // drain is held mid-job so the second call overlaps it.
        val opened = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val session = object : Mp4Session {
            override fun open(fd: Int): Mp4SessionInfo? {
                opened.countDown()
                release.await()
                return Mp4SessionInfo(1L, 1, 4, 4, 0, 0)
            }

            override fun renderFrame(
                handle: Long,
                frameIndex: Int,
                grade: GradeK,
                lutPath: String?,
                gyroPath: String?,
            ): ByteArray? = ByteArray(4 * 4 * 3)

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
            PhotoExporter(FakeMp4Session(log), FakePngWriter(log), freshEngine()),
            RecordingIo(tmp),
        )
        // Start the first drain on a real dispatcher thread (NOT the
        // runBlocking event loop — the latch waits below are JVM blocking
        // calls that would starve a loop-queued coroutine); hold it mid-job.
        val drain = async(kotlinx.coroutines.Dispatchers.Default) { queue.runAll() }
        assertTrue(opened.await(5, java.util.concurrent.TimeUnit.SECONDS))
        // The kick: a second runAll while the drain is active. It must
        // return 0 WITHOUT touching the live RUNNING row.
        assertEquals(0, queue.runAll())
        // The live row is still RUNNING (not recovered to FAILED).
        assertEquals(State.RUNNING, store.all().single().state)
        release.countDown()
        assertEquals(1, drain.await())
        // The job ran EXACTLY once (no double-start) and finished DONE —
        // never FAILED by a kick's recoverInterrupted.
        assertEquals(State.DONE, store.all().single().state)
    }
}
