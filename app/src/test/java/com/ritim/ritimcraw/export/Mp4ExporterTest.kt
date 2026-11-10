package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.NativeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger

/**
 * MP4 export tests. The pure pieces (YuvConverter, PtsTimeline,
 * Mp4ExportErrors, ExportPipeline) and the export flow around FAKE
 * session/writer are JVM-testable without librawedit_jni.so and without
 * MediaCodec (framework stubs throw in JVM tests) — the real encode is
 * device-gated.
 */
class Mp4ExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- YuvConverter: known values ----

    @Test
    fun yuv_black_is_limited_range_black() {
        // 2x2 all-black: Y=16 exactly (limited range), chroma neutral 128.
        val rgb = ByteArray(2 * 2 * 3)
        val yuv = YuvConverter.toI420(rgb, 2, 2)
        assertEquals(2 * 2 + 2, yuv.size) // Y 4 + U 1 + V 1
        assertEquals(16, yuv[0].toInt() and 0xFF)
        assertEquals(16, yuv[1].toInt() and 0xFF)
        assertEquals(16, yuv[2].toInt() and 0xFF)
        assertEquals(16, yuv[3].toInt() and 0xFF)
        assertEquals(128, yuv[4].toInt() and 0xFF) // U
        assertEquals(128, yuv[5].toInt() and 0xFF) // V
    }

    @Test
    fun yuv_white_is_limited_range_white() {
        val rgb = ByteArray(2 * 2 * 3) { 255.toByte() }
        val yuv = YuvConverter.toI420(rgb, 2, 2)
        assertEquals(235, yuv[0].toInt() and 0xFF)
        assertEquals(128, yuv[4].toInt() and 0xFF)
        assertEquals(128, yuv[5].toInt() and 0xFF)
    }

    @Test
    fun yuv_pure_red_bt601_matches_known_values() {
        // BT.601 limited range: pure red -> Y=81, U=90, V=240.
        val rgb = ByteArray(2 * 2 * 3) { if (it % 3 == 0) 255.toByte() else 0 }
        val yuv = YuvConverter.toI420(rgb, 2, 2, YuvMatrix.BT601)
        assertEquals(81, yuv[0].toInt() and 0xFF)
        assertEquals(90, yuv[4].toInt() and 0xFF)
        assertEquals(240, yuv[5].toInt() and 0xFF)
    }

    @Test
    fun yuv_pure_blue_bt601_matches_known_values() {
        // BT.601 limited range: pure blue -> Y=41, U=240, V=110.
        val rgb = ByteArray(2 * 2 * 3) { if (it % 3 == 2) 255.toByte() else 0 }
        val yuv = YuvConverter.toI420(rgb, 2, 2, YuvMatrix.BT601)
        assertEquals(41, yuv[0].toInt() and 0xFF)
        assertEquals(240, yuv[4].toInt() and 0xFF)
        assertEquals(110, yuv[5].toInt() and 0xFF)
    }

    @Test
    fun yuv_bt709_differs_from_bt601_on_chroma() {
        val rgb = ByteArray(2 * 2 * 3) { if (it % 3 == 0) 255.toByte() else 0 } // red
        val y601 = YuvConverter.toI420(rgb, 2, 2, YuvMatrix.BT601)
        val y709 = YuvConverter.toI420(rgb, 2, 2, YuvMatrix.BT709)
        // Same luma-ish for red? No: 601 Y=81, 709 Y=63 for pure red
        // (16 + 219*0.2126 = 62.56 -> 63).
        assertEquals(81, y601[0].toInt() and 0xFF)
        assertEquals(63, y709[0].toInt() and 0xFF)
    }

    @Test
    fun yuv_nv12_interleaves_uv() {
        val rgb = ByteArray(2 * 2 * 3) { if (it % 3 == 0) 255.toByte() else 0 }
        val nv12 = YuvConverter.toNv12(rgb, 2, 2, YuvMatrix.BT601)
        assertEquals(6, nv12.size) // Y 4 + UV 2
        assertEquals(81, nv12[0].toInt() and 0xFF)
        assertEquals(90, nv12[4].toInt() and 0xFF) // U first
        assertEquals(240, nv12[5].toInt() and 0xFF) // then V
    }

    @Test
    fun yuv_i420_and_nv12_share_y_and_chroma() {
        val rgb = ByteArray(4 * 4 * 3) { ((it * 37) % 256).toByte() }
        val i420 = YuvConverter.toI420(rgb, 4, 4)
        val nv12 = YuvConverter.toNv12(rgb, 4, 4)
        // Y planes identical.
        for (i in 0 until 16) {
            assertEquals(i420[i], nv12[i])
        }
        // Chroma identical, layout differs.
        val cw = 2
        val ch = 2
        for (cy in 0 until ch) {
            for (cx in 0 until cw) {
                val i420U = 16 + cy * cw + cx
                val i420V = 16 + cw * ch + cy * cw + cx
                val nvU = 16 + (cy * cw + cx) * 2
                val nvV = nvU + 1
                assertEquals(i420[i420U], nv12[nvU])
                assertEquals(i420[i420V], nv12[nvV])
            }
        }
    }

    @Test
    fun yuv_odd_dimensions_edge_clamp() {
        // 3x3: chroma grid is 2x2; the right/bottom edges are clamped.
        val rgb = ByteArray(3 * 3 * 3) { 128.toByte() }
        val yuv = YuvConverter.toI420(rgb, 3, 3)
        assertEquals(9 + 2 * 2 * 2, yuv.size) // Y 9 + U 4 + V 4
        // All gray -> every Y the same, chroma neutral.
        assertEquals(yuv[0], yuv[8])
        assertEquals(128, yuv[9].toInt() and 0xFF)
    }

    @Test
    fun yuv_too_small_buffer_throws() {
        try {
            YuvConverter.toI420(ByteArray(2), 2, 2)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("too small"))
        }
    }

    // ---- PtsTimeline ----

    @Test
    fun pts_first_frame_normalized_to_zero() {
        val pts = PtsTimeline.videoPts(listOf(258175962047L, 258175968000L, 258175974000L))
        assertEquals(0L, pts[0])
        assertEquals(5953L, pts[1])
        assertEquals(11953L, pts[2])
    }

    @Test
    fun pts_monotonic_clamp_on_bad_source() {
        // A source that violates the strictly-increasing contract: the
        // timeline clamps so the muxer never sees a non-monotonic pts.
        val pts = PtsTimeline.videoPts(listOf(100L, 300L, 200L, 400L))
        assertEquals(listOf(0L, 200L, 201L, 300L), pts)
    }

    @Test
    fun pts_empty_is_empty() {
        assertTrue(PtsTimeline.videoPts(emptyList()).isEmpty())
    }

    @Test
    fun pts_audio_block_pts_from_sample_index() {
        // 48 kHz, 1024-sample AAC blocks: block k at k*1024/48000 s.
        assertEquals(0L, PtsTimeline.audioBlockPts(0, 1024, 48000))
        assertEquals(21_333L, PtsTimeline.audioBlockPts(1, 1024, 48000))
        assertEquals(2_133_333L, PtsTimeline.audioBlockPts(100, 1024, 48000))
    }

    @Test
    fun interleave_merges_by_presentation_order() {
        val video = listOf(0L, 33_333L, 66_667L)
        val audio = listOf(0L, 21_333L, 42_666L, 64_000L)
        val order = PtsTimeline.interleave(video, audio)
        assertEquals(7, order.size)
        // First event is the opening video frame at 0 (stable sort, video
        // first), then the audio block that shares pts 0.
        assertEquals(PtsTimeline.Sample.Track.VIDEO, order[0].track)
        assertEquals(0L, order[0].ptsUs)
        assertEquals(PtsTimeline.Sample.Track.AUDIO, order[1].track)
        assertEquals(0L, order[1].ptsUs)
        assertEquals(PtsTimeline.Sample.Track.AUDIO, order[2].track)
        assertEquals(21_333L, order[2].ptsUs)
        assertEquals(PtsTimeline.Sample.Track.VIDEO, order[3].track)
        assertEquals(33_333L, order[3].ptsUs)
        assertEquals(PtsTimeline.Sample.Track.AUDIO, order[4].track)
        assertEquals(42_666L, order[4].ptsUs)
        assertEquals(PtsTimeline.Sample.Track.AUDIO, order[5].track)
        assertEquals(64_000L, order[5].ptsUs)
        assertEquals(PtsTimeline.Sample.Track.VIDEO, order[6].track)
        assertEquals(66_667L, order[6].ptsUs)
        // Non-decreasing pts across the whole merge.
        for (i in 1 until order.size) {
            assertTrue(order[i].ptsUs >= order[i - 1].ptsUs)
        }
    }

    // ---- Mp4ExportErrors mapping ----

    @Test
    fun mp4_message_names_stage_cause_recovery() {
        val m = Mp4ExportErrors.message(
            Mp4ExportErrors.CODE_TRUNCATED, Mp4ExportErrors.STAGE_FRAME_READ
        )
        assertTrue(m.startsWith("MP4 export failed (stage: frame read): truncated clip"))
        assertTrue(m.contains("recovery: re-import the clip"))
    }

    @Test
    fun mp4_kotlin_message_names_codec_and_configure() {
        val m = Mp4ExportErrors.kotlinMessage(
            Mp4ExportErrors.STAGE_CONFIGURE,
            "video/avc rejected the 3840x2160 format",
            "retry the export (the clip may exceed this device's encoder limits)",
        )
        assertTrue(m.startsWith("MP4 export failed (stage: configure): video/avc"))
        assertTrue(m.contains("3840x2160"))
        assertTrue(m.contains("recovery: retry"))
    }

    @Test
    fun mp4_every_code_has_cause_and_recovery() {
        for (code in 0..4) {
            val (cause, recovery) = Mp4ExportErrors.causeAndRecovery(code)
            assertTrue(cause.isNotBlank())
            assertTrue(recovery.isNotBlank())
        }
        val (cause, recovery) = Mp4ExportErrors.causeAndRecovery(42)
        assertEquals("unknown error", cause)
        assertEquals("retry the export", recovery)
    }

    // ---- ExportPipeline + Mp4Exporter flow (fakes) ----

    /** A fake session: N frames of solid color, optional audio. */
    private class FakeSession(
        private val frames: Int = 2,
        private val width: Int = 4,
        private val height: Int = 4,
        private val ptsStepUs: Long = 33_333L,
        private val audio: Boolean = true,
        private val failAtFrame: Int = -1,
    ) : Mp4Session {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        val rendered = ArrayList<Int>()
        var lastCode = 0
        var lastStage = 0
        var lastDetail: String? = null

        override fun open(fd: Int): Mp4SessionInfo? {
            opened.incrementAndGet()
            return Mp4SessionInfo(1L, frames, width, height, 0, 90)
        }

        override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?): ByteArray? {
            if (frameIndex == failAtFrame) {
                lastCode = Mp4ExportErrors.CODE_IO
                lastStage = Mp4ExportErrors.STAGE_DEVELOP
                lastDetail = "develop: ioError — frame $frameIndex"
                return null
            }
            rendered.add(frameIndex)
            return ByteArray(width * height * 3) { 128.toByte() }
        }

        override fun framePts(handle: Long, frameIndex: Int): Long =
            frameIndex * ptsStepUs

        override fun audioInfo(handle: Long): IntArray? =
            if (audio) intArrayOf(48000, 2) else null

        override fun readAudio(handle: Long): ShortArray =
            ShortArray(48000 * 2) // 1 s stereo

        override fun close(handle: Long) {
            closed.incrementAndGet()
        }

        override fun code(): Int = lastCode
        override fun stage(): Int = lastStage
        override fun detail(): String? = lastDetail
    }

    /** A fake writer that records what the pipeline feeds it and writes the staged file. */
    private class FakeWriter(
        private val throwOnStart: Boolean = false,
        private val finishOk: Boolean = true,
    ) : Mp4MediaWriter {
        var started = false
        var released = false
        var videoFrames = 0
        var audioBlocks = 0
        var sawPts = ArrayList<Long>()
        var startArgs: Array<Any>? = null
        var outPath: String? = null

        override fun start(
            outPath: String,
            width: Int,
            height: Int,
            orientationDeg: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            if (throwOnStart) {
                throw IllegalStateException(
                    Mp4ExportErrors.kotlinMessage(
                        Mp4ExportErrors.STAGE_CONFIGURE,
                        "video/avc rejected the ${width}x$height format",
                        "retry the export (the clip may exceed this device's encoder limits)",
                    )
                )
            }
            started = true
            this.outPath = outPath
            startArgs = arrayOf(outPath, width, height, orientationDeg, sampleRate, channels)
        }

        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
            videoFrames++
            sawPts.add(ptsUs)
        }

        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {
            audioBlocks++
            sawPts.add(ptsUs)
        }

        override fun finish(): Boolean {
            sawPts.sort()
            if (finishOk) {
                // Stand in for the muxer producing the file.
                File(outPath!!).writeBytes(ByteArray(64))
            }
            return finishOk
        }

        override fun release() {
            released = true
        }
    }

    private fun freshEngine(): ExecutorService =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-export").apply { isDaemon = true }
        }

    private fun job(target: Target = Target.MP4): ExportJob =
        ExportJob("j1", "src", GradeK(), target, State.QUEUED)

    @Test
    fun pipeline_success_feeds_writer_in_presentation_order() {
        val session = FakeSession(frames = 3, audio = true)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        assertTrue(writer.started)
        assertTrue(writer.released)
        assertEquals(3, writer.videoFrames)
        // 48000*2 samples / (1024*2 channels) = 46 full blocks + 1 short.
        assertEquals(47, writer.audioBlocks)
        // Everything the writer saw is in non-decreasing pts order.
        assertEquals(writer.sawPts, writer.sawPts.sorted())
        // Session opened exactly once and closed.
        assertEquals(1, session.opened.get())
        assertEquals(1, session.closed.get())
        // Grade passed through to every render.
        assertEquals(listOf(0, 1, 2), session.rendered)
        // Orientation + audio description reached start().
        val args = writer.startArgs!!
        assertEquals(90, args[3])
        assertEquals(48000, args[4])
        assertEquals(2, args[5])
    }

    @Test
    fun pipeline_video_only_when_clip_has_no_audio() {
        val session = FakeSession(frames = 2, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, writer.videoFrames)
        assertEquals(0, writer.audioBlocks)
        assertEquals(0, writer.startArgs!![4]) // sampleRate 0 = no audio track
    }

    @Test
    fun pipeline_native_frame_failure_maps_code_stage_detail() {
        val session = FakeSession(frames = 3, failAtFrame = 1)
        val writer = FakeWriter()
        try {
            ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
            org.junit.Assert.fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.startsWith("MP4 export failed (stage: develop)"))
            assertTrue(e.message!!.contains("frame 1"))
        }
        assertTrue(writer.released) // writer released even on failure
        assertEquals(1, session.closed.get()) // session closed even on failure
    }

    @Test
    fun rejected_encoder_leaves_no_file() {
        val session = FakeSession(frames = 2)
        val writer = FakeWriter(throwOnStart = true)
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertNotNull(result.detail)
        // The detail names the codec and the stage.
        assertTrue(result.detail!!.contains("video/avc"))
        assertTrue(result.detail!!.contains("configure"))
        // Neither the final file nor the partial exists.
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
        // Writer released, session closed despite the throw.
        assertTrue(writer.released)
        assertEquals(1, session.closed.get())
    }

    @Test
    fun export_success_renames_partial_and_marks_done() {
        val session = FakeSession(frames = 2)
        val writer = FakeWriter()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals("clip.mp4", result.detail)
        assertTrue(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
        // The writer wrote to the staging path, not the final name.
        assertEquals(
            File(out.parentFile, out.name + ".partial").absolutePath,
            writer.startArgs!![0],
        )
    }

    @Test
    fun export_mux_failure_cleans_up_and_fails() {
        val session = FakeSession(frames = 2)
        val writer = FakeWriter(finishOk = false)
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("muxer"))
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
    }

    @Test
    fun export_finalize_failure_discards_partial_and_leaves_no_file() {
        val session = FakeSession(frames = 2)
        val writer = FakeWriter()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
        )
        // out exists as a non-empty DIRECTORY: delete+rename both fail.
        val out = File(tmp.root, "clip.mp4")
        out.mkdirs()
        File(out, "keep.txt").writeText("x")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("finalize"))
        // The partial was discarded; the directory (pre-existing) stays.
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
    }

    @Test
    fun unavailable_engine_fails_cleanly_with_recovery() {
        val exporter = Mp4Exporter(
            session = FakeSession(),
            writerFactory = { FakeWriter() },
            engine = RejectedExecutor(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("export engine unavailable"))
        assertFalse(out.exists())
    }

    @Test
    fun real_boundary_without_native_fails_with_unavailable_message() {
        // NativeSource.nativeAvailable is false in JVM tests: the real
        // Mp4Session boundary must fail cleanly (never UnsatisfiedLinkError).
        val exporter = Mp4Exporter(
            session = NativeMp4Session,
            writerFactory = { FakeWriter() },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("native engine is not available"))
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
    }

    @Test
    fun fake_codec_exception_surfaces_in_detail() {
        // A codec that throws mid-encode (not at configure): the failure
        // names the encode stage and the file is cleaned up.
        val session = FakeSession(frames = 3)
        val writer = object : Mp4MediaWriter {
            override fun start(
                outPath: String,
                width: Int,
                height: Int,
                orientationDeg: Int,
                sampleRate: Int,
                channels: Int,
            ) {}

            override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
                throw IllegalStateException("codec died mid-encode")
            }

            override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}

            override fun finish(): Boolean = true

            override fun release() {}
        }
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.FAILED, result.state)
        assertEquals("codec died mid-encode", result.detail)
        assertFalse(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
    }

    /** An executor that rejects everything (shutdown engine). */
    private class RejectedExecutor : ExecutorService by Executors.newSingleThreadExecutor() {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("engine shut down")
        }
    }
}
