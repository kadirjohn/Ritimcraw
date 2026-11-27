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
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * CinemaDNG sequence export tests (night E-F, docs/
 * CINEMADNG_EXPORT_PLAN.md): the exporter's loop around a FAKE io (the
 * real calls need librawedit_jni.so + SAF — device-gated). Covers the
 * frame order, progress, the honest cancel (written frames kept, the
 * half-written frame's document deleted), the failure paths (a bad
 * folder fd, a native failure, the WAV's no-audio and write-failure
 * arms), the plan's storage lock and the unknown-free-space rule, and
 * the settings wire's round trip + unknown-version refusal.
 */
class CinemaDngExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- the fake io ----

    /**
     * The fake boundary: file names instead of fds (a per-name
     * ByteArrayOutputStream for the WAV arm), scriptable per-frame
     * failures, and a record of every open/close/delete so the partial
     * rules are asserted on what really happened.
     */
    private class FakeIo(
        val frames: Int = 4,
        val freeBytes: Long = Long.MAX_VALUE,
        val pcm: ShortArray? = ShortArray(64) { (it * 3).toShort() },
        val failAt: Int? = null,          // the OUTPUT index that fails natively
        val failOpenAt: Int? = null,      // the OUTPUT index whose fd open fails
        val throwAt: Int? = null,         // the OUTPUT index that throws
    ) : CinemaDngIo {
        val log = mutableListOf<String>()
        val files = mutableMapOf<String, ByteArray>() // name -> written bytes
        var wav: ByteArray? = null
        var deleted = mutableListOf<String>()

        override fun writeFrame(
            sourceFd: Int, frameIndex: Int,
            fpsNum: Int, fpsDen: Int,
            tcHour: Int, tcMinute: Int, tcSecond: Int, tcFrame: Int,
            frameOffset: Int, outFd: Int,
        ): Boolean {
            log.add("frame:$frameIndex:$frameOffset:$outFd")
            val i = frameOffset
            if (throwAt == i) throw IllegalStateException("decoder blew up")
            if (failAt == i) return false
            files["file$fd"] = ByteArray(16)
            return true
        }

        override fun code(): Int = DngExportErrors.CODE_TRUNCATED
        override fun stage(): Int = DngExportErrors.STAGE_FRAME_READ
        override fun detail(): String? = "frame read: truncated — short file"

        private var fd = 100
        override fun openChildWriteFd(name: String): Int {
            val i = name.substringAfter('_').substringBefore('.').toIntOrNull()
            if (i != null && failOpenAt == i) return -1
            log.add("open:$name")
            fd += 1
            return fd
        }

        override fun closeFd(fd: Int) { log.add("close:$fd") }

        override fun deleteChild(name: String): Boolean {
            deleted.add(name)
            return true
        }

        override fun freeBytes(): Long = freeBytes

        override fun audioInfo(sourceFd: Int): IntArray? =
            if (pcm != null) intArrayOf(48000, 2) else null

        override fun readAudio(sourceFd: Int): ShortArray? = pcm

        override fun openChildStream(name: String): OutputStream? {
            log.add("wav:$name")
            return object : ByteArrayOutputStream() {}
        }
    }

    // ---- naming ----

    @Test
    fun frame_names_follow_the_pattern_and_widen() {
        assertEquals("clip_000000.dng", CinemaDngNaming.frameName("clip", 6, 0))
        assertEquals("clip_000042.dng", CinemaDngNaming.frameName("clip", 6, 42))
        assertEquals("clip_999999.dng", CinemaDngNaming.frameName("clip", 6, 999999))
        // A wider number WIDENS (never truncates — the numeric ordering
        // survives, the reader-side DngSequence.frameNumberOf rule).
        assertEquals("clip_1000000.dng", CinemaDngNaming.frameName("clip", 6, 1_000_000))
        assertEquals("shot_0007.dng", CinemaDngNaming.frameName("shot", 4, 7))
        // The WAV binds by the reader's stem rule: <prefix>.wav.
        assertEquals("clip.wav", CinemaDngNaming.wavName("clip"))
    }

    @Test
    fun safe_prefix_cleans_and_falls_back() {
        // Hostile characters removed, never replaced.
        assertEquals("NightMarket", CinemaDngNaming.safePrefix("Night/Market:\\?"))
        // Blank falls back to the default — never a guess beyond it.
        assertEquals(CinemaDngNaming.DEFAULT_PREFIX, CinemaDngNaming.safePrefix("  "))
        assertEquals(CinemaDngNaming.DEFAULT_PREFIX, CinemaDngNaming.safePrefix("..."))
    }

    // ---- the WAV ----

    @Test
    fun wav_header_is_the_canonical_44_bytes() {
        val pcm = shortArrayOf(100, -100, 200, -200)
        val out = ByteArrayOutputStream()
        CinemaDngWav.write(out, pcm, 48000, 2)
        val b = out.toByteArray()
        assertEquals(44 + pcm.size * 2, b.size)
        assertEquals("RIFF", String(b, 0, 4, Charsets.US_ASCII))
        // riffSize = 36 + dataBytes
        assertEquals((36 + pcm.size * 2).toLong(), u32(b, 4))
        assertEquals("WAVE", String(b, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(b, 12, 4, Charsets.US_ASCII))
        assertEquals(16L, u32(b, 16))
        assertEquals(1L, u16(b, 20).toLong())            // PCM
        assertEquals(2L, u16(b, 22).toLong())           // channels
        assertEquals(48000L, u32(b, 24))
        assertEquals(48000L * 2 * 2, u32(b, 28)) // byte rate
        assertEquals(4L, u16(b, 32).toLong())          // block align
        assertEquals(16L, u16(b, 34).toLong())          // bits
        assertEquals("data", String(b, 36, 4, Charsets.US_ASCII))
        assertEquals((pcm.size * 2).toLong(), u32(b, 40))
        // The samples VERBATIM, little-endian.
        for (i in pcm.indices) {
            assertEquals((pcm[i].toInt() and 0xff).toLong(), (b[44 + i * 2].toLong() and 0xff))
            assertEquals(((pcm[i].toInt() shr 8) and 0xff).toLong(), (b[45 + i * 2].toLong() and 0xff))
        }
    }

    @Test
    fun wav_rejects_bad_arguments_and_huge_data() {
        val out = ByteArrayOutputStream()
        val e1 = try { CinemaDngWav.write(out, ShortArray(0), 0, 2); false } catch (e: IllegalArgumentException) { true }
        assertTrue(e1)
        val e2 = try { CinemaDngWav.write(out, ShortArray(0), 48000, 0); false } catch (e: IllegalArgumentException) { true }
        assertTrue(e2)
        // The spec's own 4 GiB ceiling: the sizes helper saturates at it
        // (an honest limit, never a wrapped size a reader would truncate).
        assertEquals(0xffffffffL, CinemaDngWav.MAX_WAV_BYTES)
        assertEquals(0xffffffffL, CinemaDngWav.sizes(0xffffffffL).first)
        assertEquals(0xffffffffL, CinemaDngWav.sizes(0x1_0000_0000L).first)
    }

    // ---- the export loop ----

    private fun engine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-cdng").apply { isDaemon = true }
        }

    private fun settings(frames: Int = 4, folder: String = "content://tree/x") = CinemaDngSettings(
        folderUri = folder,
        clipFrameCount = frames,
        frameWidth = 64,
        frameHeight = 48,
        fpsNum = 25,
        fpsDen = 1,
        audioSampleRate = 48000,
        audioChannels = 2,
    )

    private fun job(): ExportJob = ExportJob(
        id = "j1", sourceId = "clip-1", snapshot = GradeK(),
        target = Target.DNG, state = State.QUEUED,
    )

    @Test
    fun success_writes_frames_in_order_and_reports() {
        val e = engine()
        try {
            val io = FakeIo(frames = 3)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val progress = mutableListOf<Pair<Int, Int>>()
            val result = runBlocking {
                exporter.export(job(), 7, settings(3)) { d, t -> progress.add(Pair(d, t)) }
            }
            assertEquals(State.DONE, result.state)
            assertEquals("3/3 frames · clip.wav", result.detail)
            // The frame reads happen in order, one per output frame.
            assertEquals(listOf("frame:0:0:101", "frame:1:1:102", "frame:2:2:103"), io.log.filter { it.startsWith("frame:") })
            // Progress: 0/N first, then 1..N.
            assertEquals(listOf(Pair(0, 3), Pair(1, 3), Pair(2, 3), Pair(3, 3)), progress)
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun native_failure_keeps_written_frames_and_deletes_the_failed_one() {
        val e = engine()
        try {
            // Frame 2 fails natively: frames 0-1 stay, the failed frame's
            // document is deleted (it never completed).
            val io = FakeIo(frames = 4, failAt = 2)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val result = runBlocking { exporter.export(job(), 7, settings(4)) }
            assertEquals(State.FAILED, result.state)
            // The FAILED detail names the native cause and the honest count.
            assertTrue(result.detail!!.contains("CinemaDNG export failed (stage: frames)"))
            assertTrue(result.detail!!.contains("2 frames were written and kept"))
            // The half-written frame's document WAS deleted.
            assertEquals(listOf("clip_000002.dng"), io.deleted)
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun open_failure_fails_before_any_frame_write() {
        val e = engine()
        try {
            val io = FakeIo(frames = 4, failOpenAt = 1)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val result = runBlocking { exporter.export(job(), 7, settings(4)) }
            assertEquals(State.FAILED, result.state)
            assertTrue(result.detail!!.contains("folder stopped accepting files"))
            // Frame 0 was written and kept; frame 1 never opened.
            assertEquals(1, io.log.count { it.startsWith("frame:") })
            assertTrue(io.deleted.isEmpty())
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun storage_lock_fails_before_any_frame() {
        val e = engine()
        try {
            // 4 frames of 64x48 = 24 KiB, required ~26 KiB; free = 1 byte: LOCK.
            val io = FakeIo(frames = 4, freeBytes = 1L)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val result = runBlocking { exporter.export(job(), 7, settings(4)) }
            assertEquals(State.FAILED, result.state)
            assertTrue(result.detail!!.contains("stage: plan"))
            assertTrue(result.detail!!.contains("not enough free space"))
            assertEquals(0, io.log.count { it.startsWith("frame:") })
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun unknown_free_space_never_locks() {
        val e = engine()
        try {
            // freeBytes -1 (SAF could not measure): the gate is SKIPPED,
            // never a guessed lock — the export proceeds and the honest
            // mid-write failure is the backstop.
            val io = FakeIo(frames = 2, freeBytes = -1L)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val result = runBlocking { exporter.export(job(), 7, settings(2)) }
            assertEquals(State.DONE, result.state)
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun no_audio_skips_the_wav_honestly() {
        val e = engine()
        try {
            val io = FakeIo(frames = 2, pcm = null)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val result = runBlocking { exporter.export(job(), 7, settings(2)) }
            assertEquals(State.DONE, result.state)
            // No WAV name in the detail, no wav open in the log.
            assertEquals("2/2 frames", result.detail)
            assertTrue(io.log.none { it.startsWith("wav:") })
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun unusable_range_fails_at_plan() {
        val e = engine()
        try {
            val io = FakeIo(frames = 4)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val s = settings(4).copy(firstFrame = 10, lastFrame = 5)
            val result = runBlocking { exporter.export(job(), 7, s) }
            assertEquals(State.FAILED, result.state)
            assertTrue(result.detail!!.contains("unusable frame range"))
        } finally {
            e.shutdown()
        }
    }

    @Test
    fun exporter_exception_is_failed_never_thrown() {
        val e = engine()
        try {
            val io = FakeIo(frames = 4, throwAt = 0)
            val exporter = CinemaDngExporter(io = io, engine = e)
            val result = runBlocking { exporter.export(job(), 7, settings(4)) }
            assertEquals(State.FAILED, result.state)
            assertEquals("decoder blew up", result.detail)
        } finally {
            e.shutdown()
        }
    }

    // ---- the settings wire ----

    @Test
    fun settings_wire_round_trip() {
        val s = CinemaDngSettings(
            folderUri = "content://tree/primary%3Afoo",
            firstFrame = 12, lastFrame = 30,
            prefix = "sunset", frameDigits = 4,
            fpsNum = 30000, fpsDen = 1001,
            tcHour = 1, tcMinute = 2, tcSecond = 3, tcFrame = 4,
            clipFrameCount = 31, frameWidth = 3840, frameHeight = 2160,
            audioSampleRate = 48000, audioChannels = 2,
        )
        val parsed = CinemaDngSettingsWire.parse(
            com.ritim.ritimcraw.data.JsonParser(CinemaDngSettingsWire.render(s)).parseValue())
        assertTrue(parsed is CinemaDngSettingsWire.Parsed.Ok)
        assertEquals(s, (parsed as CinemaDngSettingsWire.Parsed.Ok).settings)
    }

    @Test
    fun settings_wire_refuses_unknown_version_and_missing_fields() {
        val v2 = CinemaDngSettingsWire.render(CinemaDngSettings(folderUri = "content://x"))
            .replace("\"v\":1", "\"v\":2")
        val p1 = CinemaDngSettingsWire.parse(
            com.ritim.ritimcraw.data.JsonParser(v2).parseValue())
        assertTrue(p1 is CinemaDngSettingsWire.Parsed.Unsupported)
        assertTrue((p1 as CinemaDngSettingsWire.Parsed.Unsupported).reason.contains("version"))

        val p2 = CinemaDngSettingsWire.parse("not-an-object")
        assertTrue(p2 is CinemaDngSettingsWire.Parsed.Unsupported)

        // A blank folder value is refused too (never a guessed folder).
        val noFolder = CinemaDngSettingsWire.render(CinemaDngSettings(folderUri = "x"))
            .replace("x", "")
        val p3 = CinemaDngSettingsWire.parse(
            com.ritim.ritimcraw.data.JsonParser(noFolder).parseValue())
        assertTrue(p3 is CinemaDngSettingsWire.Parsed.Unsupported)
    }

    @Test
    fun settings_valid_rejects_broken_snapshots() {
        val good = settings(4)
        assertTrue(good.valid(StringBuilder()))
        // Bad fps rational.
        assertFalse(good.copy(fpsNum = 0).valid())
        // No folder.
        assertFalse(good.copy(folderUri = "").valid())
        // Out-of-range timecode.
        assertFalse(good.copy(tcMinute = 99).valid())
        // Unknown dims.
        assertFalse(good.copy(frameWidth = 0).valid())
        // Unusable range.
        assertFalse(good.copy(lastFrame = 0, firstFrame = 3).valid())
    }

    // ---- the store round trip ----

    @Test
    fun store_round_trips_a_cinema_dng_job_and_keeps_old_rows_identical() {
        val file = tmp.newFile("project.json")
        val store = ExportStore(file)
        val s = settings(4)
        store.add(
            ExportJob(
                id = "j1", sourceId = "clip-1", snapshot = GradeK(),
                target = Target.DNG, state = State.QUEUED,
                cinemaDng = s,
            )
        )
        val loaded = store.all().single()
        assertEquals(s, loaded.cinemaDng)
        // The row's wire is under its own key, the MP4 settings untouched.
        val text = file.readText()
        assertTrue(text.contains("\"cinemaDng\":{\"v\":1"))
        assertFalse(text.contains("\"settings\""))

        // A pre-feature row (no cinemaDng): parses as null and the file
        // stays byte-identical through a re-render.
        val plain = ExportJob(
            id = "j2", sourceId = "clip-1", snapshot = GradeK(),
            target = Target.DNG, state = State.QUEUED,
        )
        store.add(plain)
        assertNull(store.all()[1].cinemaDng)
    }

    @Test
    fun store_fails_a_runnable_row_with_an_unreadable_cinema_dng_wire() {
        val file = tmp.newFile("project2.json")
        val raw = """
            {"version":1,"exports":[{"id":"j9","sourceId":"c","snapshot":{},
             "target":"DNG","state":"QUEUED",
             "cinemaDng":{"v":99,"folderUri":"content://x"}}]}
        """.trimIndent()
        file.writeText(raw)
        val jobs = ExportStore(file).all()
        // The row is kept but FAILED with the reason — never run with a
        // silently dropped sequence.
        assertEquals(State.FAILED, jobs.single().state)
        assertTrue(jobs.single().detail!!.contains("could not be read"))
        assertNull(jobs.single().cinemaDng)
    }

    // ---- helpers ----

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24)

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
}
