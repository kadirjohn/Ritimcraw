package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.NativeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Photo export tests (RAW-11 Frame Picker). The pure pieces (PhotoPixels,
 * PhotoDevelop, PhotoExportErrors) and the export flow around FAKE
 * session/writer are JVM-testable without librawedit_jni.so and without
 * android.graphics.Bitmap (framework stubs throw in JVM tests) — the real
 * PNG encode is device-gated, exactly like the MP4/DNG paths.
 */
class PhotoExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- PhotoPixels.toArgb: packing + clamping ----

    @Test
    fun argb_packs_opaque_channels() {
        // 0xFF alpha always: a PNG pixel is opaque; the channel order is
        // 0xAARRGGBB as Bitmap.createBitmap expects.
        assertEquals(0xFF102030.toInt(), PhotoPixels.toArgb(0x10, 0x20, 0x30))
        assertEquals(0xFF000000.toInt(), PhotoPixels.toArgb(0, 0, 0))
        assertEquals(0xFFFFFFFF.toInt(), PhotoPixels.toArgb(255, 255, 255))
        assertEquals(0xFFFF0000.toInt(), PhotoPixels.toArgb(255, 0, 0))
        assertEquals(0xFF00FF00.toInt(), PhotoPixels.toArgb(0, 255, 0))
        assertEquals(0xFF0000FF.toInt(), PhotoPixels.toArgb(0, 0, 255))
    }

    @Test
    fun argb_clamps_out_of_range_channels() {
        // The develop hands back 8-bit bytes, but the packing is defensive:
        // a channel outside 0..255 clamps instead of bleeding into its
        // neighbor's bits.
        assertEquals(0xFFFF0000.toInt(), PhotoPixels.toArgb(256, 0, 0))
        assertEquals(0xFF00FF00.toInt(), PhotoPixels.toArgb(0, 999, 0))
        assertEquals(0xFF0000FF.toInt(), PhotoPixels.toArgb(0, 0, 1000))
        assertEquals(0xFF000000.toInt(), PhotoPixels.toArgb(-1, -1, -1))
    }

    // ---- PhotoPixels.toArgbInts: the RGB888 walk ----

    @Test
    fun argb_ints_walk_the_row_major_rgb888_buffer() {
        // A 2x1 frame: two pixels, 6 bytes, row-major — pixel 0 then
        // pixel 1, each RGB. The output packs them in the same order.
        val rgb = byteArrayOf(1, 2, 3, 4, 5, 6)
        val argb = PhotoPixels.toArgbInts(rgb, 2, 1)
        assertEquals(2, argb.size)
        assertEquals(0xFF010203.toInt(), argb[0])
        assertEquals(0xFF040506.toInt(), argb[1])
    }

    @Test
    fun argb_ints_read_unsigned_bytes() {
        // Bytes above 127 are negative in Kotlin: the walk must mask to
        // 0..255, not sign-extend (a sign-extended 0x80 would bleed into
        // the alpha bits and break the whole pixel).
        val rgb = byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0x7F)
        val argb = PhotoPixels.toArgbInts(rgb, 1, 1)
        assertEquals(0xFF80FF7F.toInt(), argb[0])
    }

    @Test
    fun argb_ints_rejects_a_buffer_that_does_not_match_the_dimensions() {
        // 2x2 needs 12 bytes: a short buffer (or a dimension lie) fails
        // fast instead of reading past the end.
        try {
            PhotoPixels.toArgbInts(ByteArray(11), 2, 2)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expected 12"))
        }
        try {
            PhotoPixels.toArgbInts(ByteArray(12), 0, 2)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("positive dimensions"))
        }
    }

    // ---- PhotoPixels.rotate: orientation is baked into the pixels ----

    @Test
    fun rotate_zero_returns_the_same_buffer_untouched() {
        // 0 degrees (and any undeclared value): the identity — the SAME
        // buffer, not a copy, so a straight clip never pays for a rotation.
        val argb = intArrayOf(1, 2, 3, 4)
        val rotated = PhotoPixels.rotate(argb, 2, 2, 0)
        assertSame(argb, rotated.argb)
        assertEquals(2, rotated.width)
        assertEquals(2, rotated.height)
    }

    @Test
    fun rotate_180_reverses_both_axes() {
        // 2x2: [1 2 / 3 4] -> [4 3 / 2 1].
        val argb = intArrayOf(1, 2, 3, 4)
        val rotated = PhotoPixels.rotate(argb, 2, 2, 180)
        assertEquals(listOf(4, 3, 2, 1), rotated.argb.toList())
        assertEquals(2, rotated.width)
        assertEquals(2, rotated.height)
    }

    @Test
    fun rotate_90_swaps_the_axes_and_turns_clockwise() {
        // A 3-wide x 2-high frame: the 90-degree turn makes the source's
        // LAST column the output's LAST row (clockwise — the same
        // direction the MP4 arm's muxer hint turns at display time):
        //   source (3x2):  1 2 3
        //                 4 5 6
        //   90 degrees -> (2x3):  4 1
        //                         5 2
        //                         6 3
        // The output's dims swap: width 2, height 3.
        val argb = intArrayOf(1, 2, 3, 4, 5, 6)
        val rotated = PhotoPixels.rotate(argb, 3, 2, 90)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertEquals(listOf(4, 1, 5, 2, 6, 3), rotated.argb.toList())
    }

    @Test
    fun rotate_270_swaps_the_axes_and_turns_counter_clockwise() {
        // The same 3x2 frame at 270 (the opposite turn): the source's
        // first column becomes the output's first row.
        //   source (3x2):  1 2 3
        //                 4 5 6
        //   270 degrees -> (2x3):  3 6
        //                           2 5
        //                           1 4
        val argb = intArrayOf(1, 2, 3, 4, 5, 6)
        val rotated = PhotoPixels.rotate(argb, 3, 2, 270)
        assertEquals(2, rotated.width)
        assertEquals(3, rotated.height)
        assertEquals(listOf(3, 6, 2, 5, 1, 4), rotated.argb.toList())
    }

    @Test
    fun rotate_round_trips_through_opposite_angles() {
        // 90 then 270 (or 180 twice) restores the original pixels and dims
        // — the two turns compose to the identity, so a rotated-back photo
        // is the photo, not a near-miss.
        val argb = intArrayOf(1, 2, 3, 4, 5, 6)
        val once = PhotoPixels.rotate(argb, 3, 2, 90)
        val back = PhotoPixels.rotate(once.argb, once.width, once.height, 270)
        assertEquals(3, back.width)
        assertEquals(2, back.height)
        assertEquals(argb.toList(), back.argb.toList())
        val half = PhotoPixels.rotate(argb, 3, 2, 180)
        val whole = PhotoPixels.rotate(half.argb, half.width, half.height, 180)
        assertEquals(argb.toList(), whole.argb.toList())
    }

    @Test
    fun rotate_normalizes_negative_and_over_full_angles() {
        // -90 and 270 name the same turn; 450 and 90 do too — the picker's
        // sources report 0/90/180/270, but the normalization makes the
        // math honest for any int.
        val argb = intArrayOf(1, 2, 3, 4, 5, 6)
        val a = PhotoPixels.rotate(argb, 3, 2, 270)
        val b = PhotoPixels.rotate(argb, 3, 2, -90)
        assertEquals(a.argb.toList(), b.argb.toList())
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val c = PhotoPixels.rotate(argb, 3, 2, 90)
        val d = PhotoPixels.rotate(argb, 3, 2, 450)
        assertEquals(c.argb.toList(), d.argb.toList())
        assertEquals(c.width, d.width)
        assertEquals(c.height, d.height)
    }

    @Test
    fun rotate_rejects_a_buffer_that_does_not_match_the_dimensions() {
        try {
            PhotoPixels.rotate(intArrayOf(1, 2, 3), 2, 2, 90)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expected 4"))
        }
    }

    // ---- PhotoDevelop: the picker's two grade modes ----

    @Test
    fun clip_grade_mode_carries_the_clip_snapshot_as_is() {
        // "Photo: use clip grade": the job carries the clip's CURRENT grade
        // (the photo matches the preview), untouched.
        val clip = GradeK(exposureEv = 1.25f, tempK = 4300f, contrast = 70f)
        assertEquals(clip, PhotoDevelop.snapshotFor(clip, separate = false))
    }

    @Test
    fun separate_develop_mode_carries_a_fresh_neutral_grade() {
        // "Photo: separate develop": a FRESH neutral grade — the clip's own
        // grade is untouched (the develop starts from zero, like a new
        // photo), and the neutral grade is the GradeK default.
        val clip = GradeK(exposureEv = 1.25f, tempK = 4300f, contrast = 70f)
        val snapshot = PhotoDevelop.snapshotFor(clip, separate = true)
        assertEquals(GradeK(), snapshot)
        // The neutral defaults: exposure 0, contrast 0, temp back at
        // daylight (5600K) — the grade a brand-new photo starts with.
        assertEquals(0f, snapshot.exposureEv, 0f)
        assertEquals(5600f, snapshot.tempK, 0f)
        assertEquals(0f, snapshot.contrast, 0f)
    }

    // ---- PhotoExportErrors wording (DESIGN.md: stage + cause + recovery) ----

    @Test
    fun photo_kotlin_message_names_stage_cause_recovery() {
        val m = PhotoExportErrors.kotlinMessage(
            PhotoExportErrors.STAGE_ENCODE,
            "the PNG could not be written",
            "check free space and retry",
        )
        assertTrue(m.startsWith("Photo export failed (stage: png encode): the PNG could not be written"))
        assertTrue(m.contains("recovery: check free space and retry"))
    }

    @Test
    fun photo_kotlin_message_appends_the_detail_when_present() {
        // A detail (the failing path) is appended in brackets; a blank one
        // is omitted — the wording stays clean for the common case.
        val withDetail = PhotoExportErrors.kotlinMessage(
            PhotoExportErrors.STAGE_FINALIZE,
            "the finished photo could not be moved",
            "check free space and retry",
            "clip1-photo.png",
        )
        assertTrue(withDetail.endsWith("[clip1-photo.png]"))
        val withoutDetail = PhotoExportErrors.kotlinMessage(
            PhotoExportErrors.STAGE_FINALIZE,
            "the finished photo could not be moved",
            "check free space and retry",
            null,
        )
        assertTrue(!withoutDetail.contains("["))
    }

    // ---- PhotoExporter flow (fakes) ----

    /** A fake session: one frame of solid color, scriptable orientation. */
    private class FakeSession(
        private val width: Int = 4,
        private val height: Int = 4,
        private val orientationDeg: Int = 0,
    ) : Mp4Session {
        var closed = 0
        var renderedFrame = -1
        var sawGrade: GradeK? = null
        var sawLutPath: String? = "unset"

        override fun open(fd: Int) =
            Mp4SessionInfo(1L, 1, width, height, 0, orientationDeg)

        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?): ByteArray? {
            renderedFrame = frameIndex
            sawGrade = grade
            sawLutPath = lutPath
            return ByteArray(width * height * 3) { 128.toByte() }
        }

        override fun framePts(handle: Long, frameIndex: Int): Long = 0
        override fun audioInfo(handle: Long): IntArray? = null
        override fun readAudio(handle: Long): ShortArray? = null
        override fun close(handle: Long) {
            closed++
        }

        override fun code(): Int = 0
        override fun stage(): Int = 0
        override fun detail(): String? = null
    }

    /** A fake writer that records what it was handed and writes the staged file. */
    private class FakeWriter : PngWriter {
        var sawArgb = IntArray(0)
        var sawWidth = 0
        var sawHeight = 0
        var sawPath: String? = null

        override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean {
            sawArgb = argb
            sawWidth = width
            sawHeight = height
            sawPath = outPath
            File(outPath).writeBytes(ByteArray(16))
            return true
        }
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-photo").apply { isDaemon = true }
        }

    private fun photoJob(frameIndex: Int = 0, snapshot: GradeK = GradeK()): ExportJob =
        ExportJob("j1", "src", snapshot, Target.PHOTO, State.QUEUED, frameIndex = frameIndex)

    @Test
    fun photo_export_success_marks_done_and_finalizes_the_partial() = runBlocking {
        val session = FakeSession()
        val writer = FakeWriter()
        val exporter = PhotoExporter(session, writer, freshEngine())
        val out = File(tmp.root, "clip-photo.png")
        val result = exporter.export(photoJob(frameIndex = 5), 7, out)
        assertEquals(State.DONE, result.state)
        assertEquals("clip-photo.png", result.detail)
        // The picked frame reached the native develop.
        assertEquals(5, session.renderedFrame)
        // The writer wrote the STAGED partial path, and finalize renamed it.
        assertEquals(File(tmp.root, "clip-photo.png.partial").absolutePath, writer.sawPath)
        assertTrue(out.exists())
        assertFalse(File(tmp.root, "clip-photo.png.partial").exists())
        // The session was opened and closed exactly once.
        assertEquals(1, session.closed)
    }

    @Test
    fun photo_export_converts_and_rotates_to_the_writer() = runBlocking {
        // The pure seam end to end: the session's RGB888 becomes packed
        // ARGB ints, rotated by the clip's orientation, with the
        // post-rotation dims handed to the writer.
        val session = FakeSession(width = 3, height = 2, orientationDeg = 90)
        val writer = FakeWriter()
        val exporter = PhotoExporter(session, writer, freshEngine())
        val out = File(tmp.root, "clip-photo.png")
        val result = exporter.export(photoJob(), 7, out)
        assertEquals(State.DONE, result.state)
        // 3x2 = 6 pixels; solid 128 gray -> 0xFF808080 each.
        assertEquals(6, writer.sawArgb.size)
        assertEquals(0xFF808080.toInt(), writer.sawArgb[0])
        // The 90-degree turn swapped the dims for the writer.
        assertEquals(2, writer.sawWidth)
        assertEquals(3, writer.sawHeight)
    }

    @Test
    fun photo_export_resolves_the_lut_once_and_passes_it_to_the_develop() = runBlocking {
        // COL-07 parity with the MP4 arm: the snapshot's lutId resolves to
        // the copied cube's path ONCE per job, and the develop applies it.
        val session = FakeSession()
        val writer = FakeWriter()
        val exporter = PhotoExporter(
            session, writer, freshEngine(),
            lutPathFor = { id -> if (id == "abc123") "/data/luts/abc123.cube" else null },
        )
        val out = File(tmp.root, "clip-photo.png")
        val job = photoJob(snapshot = GradeK(lutId = "abc123", lutStrength = 62f))
        val result = exporter.export(job, 7, out)
        assertEquals(State.DONE, result.state)
        assertEquals("/data/luts/abc123.cube", session.sawLutPath)
        // The grade snapshot reached the develop with its LUT selection.
        assertEquals("abc123", session.sawGrade!!.lutId)
        assertEquals(62f, session.sawGrade!!.lutStrength, 0f)
    }

    @Test
    fun photo_native_open_failure_fails_the_job_and_closes_nothing() = runBlocking {
        // The session's open returns null: the job is FAILED with the
        // mapped native message (the shared open seam), and no partial is
        // left behind.
        val session = object : Mp4Session {
            override fun open(fd: Int): Mp4SessionInfo? = null
            override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?): ByteArray? = null
            override fun framePts(handle: Long, frameIndex: Int): Long = 0
            override fun audioInfo(handle: Long): IntArray? = null
            override fun readAudio(handle: Long): ShortArray? = null
            override fun close(handle: Long) {}
            override fun code(): Int = Mp4ExportErrors.CODE_IO
            override fun stage(): Int = Mp4ExportErrors.STAGE_OPEN
            override fun detail(): String? = "fake open failure"
        }
        val exporter = PhotoExporter(session, FakeWriter(), freshEngine())
        val out = File(tmp.root, "clip-photo.png")
        val result = runBlocking { exporter.export(photoJob(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.startsWith("MP4 export failed (stage: open)"))
        assertFalse(out.exists())
        assertFalse(File(tmp.root, "clip-photo.png.partial").exists())
    }

    @Test
    fun photo_writer_failure_fails_with_the_encode_stage() = runBlocking {
        // The writer returns false: the Kotlin-stage png-encode message,
        // the partial discarded, no file at all.
        val writer = object : PngWriter {
            override fun write(argb: IntArray, width: Int, height: Int, outPath: String): Boolean = false
        }
        val exporter = PhotoExporter(FakeSession(), writer, freshEngine())
        val out = File(tmp.root, "clip-photo.png")
        val result = runBlocking { exporter.export(photoJob(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.startsWith("Photo export failed (stage: png encode)"))
        assertTrue(result.detail!!.contains("recovery: check free space and retry"))
        assertFalse(out.exists())
        assertFalse(File(tmp.root, "clip-photo.png.partial").exists())
        // The session was still closed (the failure path does not leak it).
        // (FakeSession's closed counter is not reachable through the base
        // type here; the close happened in the finally — see the success
        // test.)
    }

    @Test
    fun photo_finalize_failure_discards_the_partial_and_leaves_no_file() = runBlocking {
        // out exists as a non-empty DIRECTORY: delete+rename both fail, so
        // finalize returns false and the partial is discarded.
        val exporter = PhotoExporter(FakeSession(), FakeWriter(), freshEngine())
        val out = File(tmp.root, "clip-photo.png")
        out.mkdirs()
        File(out, "keep.txt").writeText("x")
        val result = runBlocking { exporter.export(photoJob(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("stage: finalize"))
        // The partial was discarded; the directory (pre-existing) stays.
        assertFalse(File(tmp.root, "clip-photo.png.partial").exists())
    }

    @Test
    fun photo_unavailable_engine_fails_cleanly_with_recovery() = runBlocking {
        // A rejected engine (shut down): the job fails with the
        // engine-unavailable message, never an exception to the caller.
        val exporter = PhotoExporter(
            FakeSession(), FakeWriter(),
            RejectedExecutor(),
        )
        val out = File(tmp.root, "clip-photo.png")
        val result = runBlocking { exporter.export(photoJob(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("export engine unavailable"))
        assertTrue(result.detail!!.contains("recovery: retry the export"))
        assertFalse(out.exists())
    }

    @Test
    fun photo_real_boundary_without_native_fails_with_unavailable_message() = runBlocking {
        // NativeSource.nativeAvailable is false in JVM tests: the real
        // session boundary must fail cleanly (never UnsatisfiedLinkError).
        val exporter = PhotoExporter(NativeMp4Session, FakeWriter(), freshEngine())
        val out = File(tmp.root, "clip-photo.png")
        val result = runBlocking { exporter.export(photoJob(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains(NativeSource.nativeUnavailableMessage))
        assertFalse(out.exists())
        assertFalse(File(tmp.root, "clip-photo.png.partial").exists())
    }

    /** An executor that rejects everything (shutdown engine). */
    private class RejectedExecutor : ExecutorService by Executors.newSingleThreadExecutor() {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("engine shut down")
        }
    }
}
