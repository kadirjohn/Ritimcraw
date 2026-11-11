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

    // ---- imageInputSize: the queueInputBuffer size (owner request 2026-11-11 §1) ----

    @Test
    fun size_on_tight_planar_strides_is_width_height_three_halves() {
        // CTS EncodeDecodeTest's algebra exactly: stride==width, planar
        // chroma (pixelStride 1) -> the test's own frameData.length.
        assertEquals(
            3840 * 2160 * 3 / 2,
            imageInputSize(
                width = 3840, height = 2160,
                yRowStride = 3840,
                uRowStride = 1920, uPixelStride = 1,
                vRowStride = 1920, vPixelStride = 1,
            ),
        )
    }

    @Test
    fun size_on_tight_semiplanar_is_also_width_height_three_halves() {
        // NV12 (pixelStride 2, one interleaved plane): the SAME total. This
        // is why CTS can use one formula for every device — and why SUMMING
        // per-plane extents would be wrong (it would double-count the
        // shared chroma plane and report w*h*2).
        assertEquals(
            3840 * 2160 * 3 / 2,
            imageInputSize(
                width = 3840, height = 2160,
                yRowStride = 3840,
                uRowStride = 3840, uPixelStride = 2,
                vRowStride = 3840, vPixelStride = 2,
            ),
        )
    }

    @Test
    fun size_grows_with_padded_luma_stride() {
        // Alignment-padded Y (rowStride > width): the luma block — and so
        // the chroma base — grows by the padding under each row.
        assertEquals(
            3840 * 2160 * 3 / 2 + 2160 * 64,
            imageInputSize(
                width = 3840, height = 2160,
                yRowStride = 3904,
                uRowStride = 1920, uPixelStride = 1,
                vRowStride = 1920, vPixelStride = 1,
            ),
        )
    }

    @Test
    fun size_grows_with_padded_chroma_stride_planar() {
        // Padded U/V rows (rowStride 1984 > cw 1920): V — which starts
        // after U's whole block in a planar layout — ends at
        //   chromaBase + ch*uRow + [1 + (ch-1)*vRow + (cw-1)]
        //   = 2160*3840 + 1080*1984 + 1 + 1079*1984 + 1919 = 12579776.
        assertEquals(
            2160 * 3840 + 1080 * 1984 + 1 + 1079 * 1984 + 1919,
            imageInputSize(
                width = 3840, height = 2160,
                yRowStride = 3840,
                uRowStride = 1984, uPixelStride = 1,
                vRowStride = 1984, vPixelStride = 1,
            ),
        )
    }

    @Test
    fun size_grows_with_padded_semiplanar_stride() {
        // NV12 with padding (rowStride 3904 covers the interleaved U+V
        // pairs): V starts one byte into the shared plane, so it ends at
        //   chromaBase + 1 + [1 + (ch-1)*vRow + (cw-1)*2]
        //   = 2160*3840 + 2 + 1079*3904 + 1919*2 = 12510656 — SMALLER than
        // the planar total above (no second chroma block), exactly the
        // overlap the max-end computation handles.
        assertEquals(
            2160 * 3840 + 2 + 1079 * 3904 + 1919 * 2,
            imageInputSize(
                width = 3840, height = 2160,
                yRowStride = 3840,
                uRowStride = 3904, uPixelStride = 2,
                vRowStride = 3904, vPixelStride = 2,
            ),
        )
    }

    @Test
    fun size_on_odd_dimensions_rounds_chroma_up() {
        // 3x3: cw=ch=2 (the same (w+1)/2 the copy fills). Tight planar:
        // yEnd = 2*3+3 = 9, chromaBase = 9, uEnd = 9 + 1 + 2 + 1 = 13,
        // vStart = 9 + 2*2 = 13, vEnd = 13 + 1 + 2 + 1 = 17 -> the last
        // filled byte sits at offset 16 (0-based): 17 bytes total.
        assertEquals(
            17,
            imageInputSize(
                width = 3, height = 3,
                yRowStride = 3,
                uRowStride = 2, uPixelStride = 1,
                vRowStride = 2, vPixelStride = 1,
            ),
        )
    }

    @Test
    fun size_never_exceeds_the_planes_the_codec_handed_out() {
        // The invariant the max-end computation exists for: on a padded
        // NV12 layout the SUM of plane extents would exceed the buffer
        // (double-counted chroma); the max end offset stays within it.
        val w = 3840
        val h = 2160
        val cw = 1920
        val ch = 1080
        val yRow = 3904
        val uRow = 3904
        val sum = (h * yRow) + (1 + (ch - 1) * uRow + (cw - 1) * 2) * 2
        val size = imageInputSize(
            width = w, height = h,
            yRowStride = yRow,
            uRowStride = uRow, uPixelStride = 2,
            vRowStride = uRow, vPixelStride = 2,
        )
        assertTrue(size < sum) // the sum double-counts the shared plane
        assertTrue(size <= h * yRow + ch * uRow + 1) // within the buffer
    }

    @Test
    fun size_rejects_non_positive_dimensions() {
        try {
            imageInputSize(0, 2160, 3840, 1920, 1, 1920, 1)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("positive dimensions"))
        }
    }

    @Test
    fun size_rejects_row_stride_too_small_for_the_row() {
        // A stride that cannot hold the row would make the copy overflow
        // the plane long before the size mattered — fail fast instead.
        try {
            imageInputSize(3840, 2160, 3840, 1024, 1, 1920, 1)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("u row stride"))
        }
        try {
            imageInputSize(3840, 2160, 1024, 1920, 1, 1920, 1)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("luma row stride"))
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
        var lastVideoFrame: ByteArray? = null
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
            lastVideoFrame = i420
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
        // Orientation + audio description reached start(). The muxer hint
        // is 0 (§5): the pixels are pre-rotated upright by the pipeline, so
        // a hint would double-rotate in players. The writer's dims were
        // swapped for this 90° clip (square 4x4 here, so same numbers).
        val args = writer.startArgs!!
        assertEquals(0, args[3])
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
    fun pipeline_rotates_pixels_upright_and_swaps_writer_dims() {
        // §5 (owner request 2026-11-11): an 8x4 clip with container
        // orientation 90 exports 4x8 — the pixels are pre-rotated upright
        // (the encoder wants upright video) and the muxer hint is 0, so no
        // player double-rotates.
        val session = FakeSession(frames = 1, width = 8, height = 4, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        val args = writer.startArgs!!
        assertEquals(4, args[1]) // width swapped
        assertEquals(8, args[2]) // height swapped
        assertEquals(0, args[3]) // no muxer hint: pixels already upright
        // The encoded frame carries the swapped (upright) dimensions.
        val frame = writer.lastVideoFrame!!
        assertEquals(4 * 8 * 3 / 2, frame.size) // I420: w*h*1.5
    }

    @Test
    fun pipeline_manual_rotation_composes_into_effective_degrees() {
        // A quarter-turn manual delta on the same 90°-container clip makes
        // the effective orientation 180: pixels turn by a half turn and the
        // writer keeps the container dims (no swap for 180).
        val session = FakeSession(frames = 1, width = 8, height = 4, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial", manualRotationDeg = 90)
        val args = writer.startArgs!!
        assertEquals(8, args[1]) // 180 effective: no dim swap
        assertEquals(4, args[2])
        assertEquals(0, args[3]) // hint stays 0 in every case
        assertEquals(8 * 4 * 3 / 2, writer.lastVideoFrame!!.size)
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
