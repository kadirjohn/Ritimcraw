package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.engine.RenderBackend
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
import java.nio.ByteBuffer
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

    @Test
    fun pts_ns_is_us_times_1000() {
        // The EGLExt.eglPresentationTimeANDROID stamp (GPU deliverable F,
        // §10): normalized us -> ns, pure multiplication.
        assertEquals(0L, PtsTimeline.ptsNs(0L))
        assertEquals(33_333_000L, PtsTimeline.ptsNs(33_333L))
        assertEquals(9_007_199_254_000L, PtsTimeline.ptsNs(9_007_199_254L))
    }

    @Test
    fun pts_ns_rejects_negative() {
        // The timeline's own videoPts never produces a negative, but the
        // stamp is a §10 contract — a negative reaching it fails loudly
        // instead of wrapping into a wrong presentation time.
        try {
            PtsTimeline.ptsNs(-1L)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-negative"))
        }
    }

    @Test
    fun pts_ns_rejects_nanosecond_overflow() {
        // A pts beyond Long.MAX_VALUE/1000 us cannot be represented in ns
        // — fail loudly, never wrap (§10: ptsUs->ptsNs conversion must
        // overflow-check).
        val over = Long.MAX_VALUE / 1000L + 1
        try {
            PtsTimeline.ptsNs(over)
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("overflows"))
        }
        // The boundary itself is fine (exactly representable).
        assertEquals(Long.MAX_VALUE / 1000L * 1000L, PtsTimeline.ptsNs(Long.MAX_VALUE / 1000L))
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

    // ---- ExportStageTiming: pure formatting rules (owner request 2026-11-13) ----

    @Test
    fun timing_sample_lines_land_at_the_checkpoint_frames_only() {
        val lines = ArrayList<String>()
        val t = ExportStageTiming(lines::add)
        repeat(95) {
            t.startFrame()
            t.mark(ExportStageTiming.STAGE_NATIVE, 1_000_000)
            t.endFrame()
        }
        // Exactly the 4 checkpoint frames (1, 30, 60, 90) log a sample
        // line — never one line per frame.
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("export frame 1: "))
        assertTrue(lines[1].startsWith("export frame 30: "))
        assertTrue(lines[2].startsWith("export frame 60: "))
        assertTrue(lines[3].startsWith("export frame 90: "))
        // Every sample line carries all 7 stages in order with the tag.
        for (line in lines) {
            for (s in 0 until ExportStageTiming.STAGE_COUNT) {
                assertTrue(line.contains("${ExportStageTiming.stageName(s)}="))
            }
        }
    }

    @Test
    fun timing_close_emits_average_and_summary_lines() {
        val lines = ArrayList<String>()
        val t = ExportStageTiming(lines::add)
        repeat(3) {
            t.startFrame()
            t.mark(ExportStageTiming.STAGE_NATIVE, 3_000_000L)
            t.mark(ExportStageTiming.STAGE_YUV, 1_000_000L)
            t.endFrame()
        }
        t.close(
            sourceW = 3840, sourceH = 2160,
            rotatedW = 2160, rotatedH = 3840,
            encodedW = 1920, encodedH = 1080,
            grade = GradeK(),
            gyroActive = false,
            encodeNanos = 30_000_000L,
        )
        // Frame 1 is a checkpoint, so the sample line precedes the close
        // pair: sample + averages + summary = 3 lines.
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("export frame 1: "))
        // Averages over 3 frames: native=3.0ms yuv=1.0ms, others 0.0ms.
        assertTrue(lines[1].startsWith("export stages avg over 3 frames: "))
        assertTrue(lines[1].contains("native=3.0ms"))
        assertTrue(lines[1].contains("yuv=1.0ms"))
        assertTrue(lines[1].contains("rotate=0.0ms"))
        // The summary line: three dim pairs, neutral effects, frames, total.
        val summary = lines[2]
        assertTrue(summary.startsWith("EXPORT SUMMARY: "))
        assertTrue(summary.contains("source 3840x2160 -> upright 2160x3840 -> encoded 1920x1080"))
        assertTrue(summary.contains("effects none"))
        assertTrue(summary.contains("3 frames"))
        assertTrue(summary.contains("30.0 ms"))
    }

    @Test
    fun timing_effects_tokens_follow_the_grade() {
        // Each active effect group adds its token; neutral = "none".
        assertEquals("none", ExportStageTiming.effects(GradeK(), gyroActive = false))
        assertEquals(
            "nr+temporal+gyro+tonemap+lut",
            ExportStageTiming.effects(
                GradeK(
                    noiseReduction = 40f,
                    temporalNr = true,
                    stabilization = true,
                    tonemap = 2,
                    lutId = "cube1",
                ),
                gyroActive = true,
            ),
        )
        // Stabilization ON but no gyro log attached: the pipeline's gate
        // (stabilization && gyroPath != null) is what gyroActive carries,
        // so the honest token is absent.
        assertEquals(
            "nr",
            ExportStageTiming.effects(
                GradeK(noiseReduction = 10f, stabilization = true),
                gyroActive = false,
            ),
        )
        // Chroma-only NR still counts as "nr" (the same native stage group).
        assertEquals("nr", ExportStageTiming.effects(GradeK(chromaDenoise = 25f), gyroActive = false))
        // LUT selected but lutId null = no LUT — "lut" absent.
        assertEquals("none", ExportStageTiming.effects(GradeK(lutStrength = 50f), gyroActive = false))
    }

    @Test
    fun timing_null_sink_collects_nothing_and_never_throws() {
        // Timing off: the pipeline never constructs the object, but the
        // contract of a null sink is "no crash, no lines" — the writer's
        // markWriterStages path with a null-log timing stays safe too.
        val t = ExportStageTiming(null)
        t.startFrame()
        t.mark(ExportStageTiming.STAGE_NATIVE, 5_000_000)
        t.markWriterStages(1_000_000, 2_000_000, 3_000_000)
        t.endFrame()
        t.close(4, 4, 4, 4, 4, 4, GradeK(), false, 8_000_000)
        // No assertion to fail — reaching here is the contract.
    }

    @Test
    fun pipeline_stage_timing_feeds_the_sink_per_export() {
        val session = FakeSession(frames = 2, audio = false)
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        ExportPipeline(session, writer, lines::add).run(7, GradeK(), "/tmp/out.partial")
        // 2 frames: frame 1 is a checkpoint -> one sample line, then the
        // close() pair (averages + summary). The fake writer never calls
        // attachTiming's sink (default no-op), so its stages read 0.0ms —
        // honest zeros, not fabricated numbers.
        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("export frame 1: "))
        assertTrue(lines[1].startsWith("export stages avg over 2 frames: "))
        assertTrue(lines[2].startsWith("EXPORT SUMMARY: "))
    }

    // ---- native sub-stage timing (commit (d)) ----

    @Test
    fun timing_native_sub_stages_render_the_parenthetical() {
        val lines = ArrayList<String>()
        val t = ExportStageTiming(lines::add)
        t.startFrame()
        t.mark(ExportStageTiming.STAGE_NATIVE, 472_300_000L)
        t.markNativeSubStages(doubleArrayOf(210.5, 12.3, 180.4, 45.0, 15.1, 9.0))
        t.endFrame()
        // Frame 1 is a checkpoint: the sample line splits the native pair.
        assertEquals(1, lines.size)
        val line = lines[0]
        assertTrue(line.contains("native=472.3ms("))
        assertTrue(line.contains("decode=210.5ms"))
        assertTrue(line.contains("temporal=12.3ms"))
        assertTrue(line.contains("develop=180.4ms"))
        assertTrue(line.contains("warp=45.0ms"))
        assertTrue(line.contains("oetf=15.1ms"))
        assertTrue(line.contains("copy=9.0ms"))
        // The parenthetical closes right before the next stage pair.
        assertTrue(line.contains(") rotate="))
        // The six tokens appear once each, in the fixed order. The \b
        // matters for "copy": "plane_copy=0.0ms" contains the substring
        // "copy=0.0ms", and the underscore is a word character so \bcopy
        // does not match inside it.
        assertTrue(Regex("\\bdecode=[0-9.]+ms").findAll(line).count() == 1)
        assertTrue(Regex("\\btemporal=[0-9.]+ms").findAll(line).count() == 1)
        assertTrue(Regex("\\bdevelop=[0-9.]+ms").findAll(line).count() == 1)
        assertTrue(Regex("\\bcopy=[0-9.]+ms").findAll(line).count() == 1)
    }

    @Test
    fun timing_no_sub_stages_keeps_the_plain_native_pair() {
        // Each case on its own timing object: frame 1 is the only checkpoint
        // per object, so exactly one sample line lands per case.
        fun sampleLine(subStages: DoubleArray?): String {
            val lines = ArrayList<String>()
            val t = ExportStageTiming(lines::add)
            t.startFrame()
            t.mark(ExportStageTiming.STAGE_NATIVE, 3_000_000L)
            t.markNativeSubStages(subStages)
            t.endFrame()
            assertEquals(1, lines.size)
            return lines[0]
        }

        // No markNativeSubStages call, null, or wrong length: the line is
        // exactly what it always was — no parenthetical, no "()" either.
        run {
            val lines = ArrayList<String>()
            val t = ExportStageTiming(lines::add)
            t.startFrame()
            t.mark(ExportStageTiming.STAGE_NATIVE, 3_000_000L)
            t.endFrame()
            assertEquals(1, lines.size)
            assertTrue(lines[0].contains("native=3.0ms rotate="))
            assertFalse(lines[0].contains("("))
        }
        assertTrue(sampleLine(null).contains("native=3.0ms rotate="))
        assertFalse(sampleLine(null).contains("("))
        val wrongLength = sampleLine(doubleArrayOf(1.0)) // wrong length = no data
        assertTrue(wrongLength.contains("native=3.0ms rotate="))
        assertFalse(wrongLength.contains("("))
    }

    @Test
    fun timing_sub_stages_clear_per_frame() {
        val lines = ArrayList<String>()
        val t = ExportStageTiming(lines::add)
        t.startFrame()
        t.mark(ExportStageTiming.STAGE_NATIVE, 472_300_000L)
        t.markNativeSubStages(doubleArrayOf(210.5, 12.3, 180.4, 45.0, 15.1, 9.0))
        t.endFrame()
        // Frame 2 reports nothing: startFrame must clear the slot so the
        // previous frame's split never bleeds into a plain frame's line.
        t.startFrame()
        t.mark(ExportStageTiming.STAGE_NATIVE, 3_000_000L)
        t.endFrame()
        // Only frame 1 (a checkpoint) logs; frame 2 is not. close() emits
        // the averages line, which never carries the parenthetical.
        t.close(4, 4, 4, 4, 4, 4, GradeK(), false, 500_000_000L)
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("export stages avg over 2 frames: "))
        assertFalse(lines[1].contains("decode="))
    }

    @Test
    fun pipeline_passes_sub_stages_through_when_the_session_reports() {
        // A session whose lastFrameSubStagesMs() reports the fixed-order
        // slots: the pipeline must hand them to the timing object right
        // after renderFrame, so frame 1's sample line carries the split.
        val session = object : FakeSession(frames = 2, audio = false) {
            override fun lastFrameSubStagesMs(): DoubleArray =
                doubleArrayOf(210.5, 12.3, 180.4, 45.0, 15.1, 9.0)
        }
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        ExportPipeline(session, writer, lines::add).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("native="))
        assertTrue(lines[0].contains("decode=210.5ms"))
        assertTrue(lines[0].contains("copy=9.0ms"))
        // The default session (FakeSession without the override) reports
        // null — the same export's line stays plain.
        val plainSession = FakeSession(frames = 2, audio = false)
        val plainLines = ArrayList<String>()
        ExportPipeline(plainSession, FakeWriter(), plainLines::add).run(7, GradeK(), "/tmp/out.partial")
        assertTrue(plainLines[0].contains("native="))
        assertFalse(plainLines[0].contains("decode="))
    }

    // ---- ExportPipeline + Mp4Exporter flow (fakes) ----

    /** A fake session: N frames of solid color, optional audio. Open so the
     * sub-stage pipeline test can report lastFrameSubStagesMs(). */
    private open class FakeSession(
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

        override fun renderFrame(
            handle: Long,
            frameIndex: Int,
            grade: GradeK,
            lutPath: String?,
            gyroPath: String?,
        ): ByteArray? {
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
    fun pipeline_link_gate_detached_exports_video_only() {
        // AUD-02's export gate: the clip HAS an audio track (FakeSession
        // audio=true), but the source's link state is not ATTACHED — the
        // encode must skip the audioInfo/readAudio branch and produce the
        // same video-only file a clip with no audio track produces.
        val session = FakeSession(frames = 2, audio = true)
        val writer = FakeWriter()
        ExportPipeline(
            session, writer,
            renderBackend = RenderBackend.CPU,
            audioAttached = false,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, writer.videoFrames)
        assertEquals(0, writer.audioBlocks)
        assertEquals(0, writer.startArgs!![4]) // sampleRate 0 = no audio track
    }

    @Test
    fun pipeline_link_gate_attached_keeps_audio_bytes() {
        // The default (audioAttached=true) keeps the byte-identical current
        // output: a clip with an audio track still muxes it — the same
        // counts pipeline_success_feeds_writer_in_presentation_order pins.
        val session = FakeSession(frames = 3, audio = true)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(3, writer.videoFrames)
        assertEquals(47, writer.audioBlocks)
        assertEquals(48000, writer.startArgs!![4])
        assertEquals(2, writer.startArgs!![5])
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

    // ---- RgbToI420 seam: the native YUV step's dispatch + debug dual-run ----

    @Test
    fun rgb_to_i420_seam_default_is_the_native_dispatcher() {
        // The pipeline's default converter is the native dispatch (the
        // seam's whole point: production goes native, bytes unchanged).
        // On the JVM the library is unavailable, so the dispatch falls
        // back to the Kotlin toI420 — and the writer still receives the
        // exact YuvConverter bytes (the fallback wiring's proof).
        val session = FakeSession(frames = 1, width = 4, height = 4, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        val expected = YuvConverter.toI420(
            ByteArray(4 * 4 * 3) { 128.toByte() }, 4, 4,
        )
        assertTrue(writer.lastVideoFrame!!.contentEquals(expected))
    }

    @Test
    fun rgb_to_i420_injected_converter_feeds_the_writer() {
        // A fake converter proves the pipeline calls THROUGH the seam and
        // feeds the writer what the seam returned (not a re-conversion).
        var calls = 0
        val marker = ByteArray(4 * 4 + 2 * 2 * 2) { 7.toByte() }
        val converter = RgbToI420 { _, _, _ ->
            calls++
            RgbToI420Result(marker, fromNative = false)
        }
        val session = FakeSession(frames = 2, width = 4, height = 4, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer, converter = converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, calls)
        assertTrue(writer.lastVideoFrame!!.contentEquals(marker))
    }

    @Test
    fun rgb_to_i420_dual_run_rule_fires_on_first_native_frame_only() {
        // The pure decision rule: dual-run exactly once per export, on the
        // FIRST frame that came from the native path.
        assertTrue(RgbToI420Verify.shouldDualRun(nativeFramesSeen = 0, fromNative = true))
        // Later native frames: never again.
        assertFalse(RgbToI420Verify.shouldDualRun(nativeFramesSeen = 1, fromNative = true))
        assertFalse(RgbToI420Verify.shouldDualRun(nativeFramesSeen = 30, fromNative = true))
        // Kotlin frames (native unavailable / fallback): never — there is
        // no native output to compare against.
        assertFalse(RgbToI420Verify.shouldDualRun(nativeFramesSeen = 0, fromNative = false))
        assertFalse(RgbToI420Verify.shouldDualRun(nativeFramesSeen = 5, fromNative = false))
    }

    @Test
    fun rgb_to_i420_dual_run_line_reports_match_and_mismatch() {
        // A matching pair logs the byte-count confirmation.
        val bytes = YuvConverter.toI420(ByteArray(2 * 2 * 3) { 9.toByte() }, 2, 2)
        val match = RgbToI420Verify.line(2, 2, bytes, bytes.copyOf())
        assertNotNull(match)
        assertTrue(match!!.startsWith("rgbToI420 dual-run 2x2: native == kotlin"))
        assertTrue(match.contains("6 bytes"))
        // A one-byte difference logs the mismatch with its index and the
        // two disagreeing values.
        val kt = bytes.copyOf()
        kt[3] = (kt[3] + 1).toByte()
        val mismatch = RgbToI420Verify.line(2, 2, bytes, kt)
        assertNotNull(mismatch)
        assertTrue(mismatch!!.contains("MISMATCH at byte 3"))
        assertTrue(mismatch.contains("native=${bytes[3]} kotlin=${kt[3]}"))
        // A size disagreement logs nothing (cannot happen; no crash).
        assertNull(RgbToI420Verify.line(2, 2, bytes, ByteArray(bytes.size + 1)))
    }

    @Test
    fun rgb_to_i420_pipeline_dual_run_logs_through_the_stage_sink() {
        // A converter that claims native origin drives the pipeline's
        // dual-run: the first frame logs the comparison line through the
        // stage sink, the second does not.
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = true)
        }
        val session = FakeSession(frames = 2, width = 4, height = 4, audio = false)
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        ExportPipeline(session, writer, lines::add, converter).run(7, GradeK(), "/tmp/out.partial")
        // Exactly ONE dual-run line (the timing sample/avg/summary lines
        // are the timing test's domain; here the dual-run line is the
        // first line of the export).
        assertEquals(1, lines.count { it.startsWith("rgbToI420 dual-run") })
        val dual = lines.first { it.startsWith("rgbToI420 dual-run") }
        assertTrue(dual.contains("native == kotlin"))
        // The writer received the converter's bytes: the toI420 of the
        // session's 128-gray frame (what the fake session renders).
        val expected = YuvConverter.toI420(
            ByteArray(4 * 4 * 3) { 128.toByte() }, 4, 4,
        )
        assertTrue(writer.lastVideoFrame!!.contentEquals(expected))
    }

    @Test
    fun rgb_to_i420_pipeline_dual_run_skipped_for_kotlin_frames() {
        // fromNative false (the JVM fallback, any pure fake): no dual-run
        // line, even with the sink wired.
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = false)
        }
        val session = FakeSession(frames = 2, width = 4, height = 4, audio = false)
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        ExportPipeline(session, writer, lines::add, converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(0, lines.count { it.startsWith("rgbToI420 dual-run") })
    }

    @Test
    fun rgb_to_i420_pipeline_dual_run_skipped_without_sink() {
        // A null stage sink (release builds, timing off): the dual-run
        // never runs — the guard is the sink itself, so release exports
        // pay zero.
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = true)
        }
        val session = FakeSession(frames = 1, width = 4, height = 4, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer, converter = converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, writer.videoFrames) // the export ran, conversion included
    }

    // ---- direct-to-plane path (commit (c)): writer-offered planes ----

    /** A writer that offers the encoder Image's plane buffers (heap-backed). */
    private class PlanesWriter(
        /** When true, the writer revokes the offer before the conversion. */
        private val revokeBeforeQueue: Boolean = false,
    ) : Mp4MediaWriter {
        var started = false
        var released = false
        var queuedPts = ArrayList<Long>()
        var abandoned = 0
        var packedFrames = 0
        var lastPackedFrame: ByteArray? = null
        var acquireCount = 0

        // The encoder Image's plane buffers: heap ByteBuffers with
        // MediaCodec-typical strides (y 4x4 fits; chroma rows padded).
        private val yBuf = ByteBuffer.allocate(4 * 4)
        private val uBuf = ByteBuffer.allocate(2 * 2)
        private val vBuf = ByteBuffer.allocate(2 * 2)

        override fun start(
            outPath: String,
            width: Int,
            height: Int,
            orientationDeg: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            started = true
        }

        override fun acquireVideoFramePlanes(): YuvFramePlanes? {
            acquireCount++
            if (revokeBeforeQueue) return null
            return YuvFramePlanes(
                y = yBuf,
                yStride = 4,
                u = uBuf,
                uStride = 2,
                uPixelStride = 1,
                v = vBuf,
                vStride = 2,
                vPixelStride = 1,
            )
        }

        override fun queueVideoFrame(ptsUs: Long) {
            queuedPts.add(ptsUs)
        }

        override fun abandonVideoFrame() {
            abandoned++
        }

        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
            packedFrames++
            lastPackedFrame = i420
        }

        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}

        override fun finish(): Boolean = true

        override fun release() {
            released = true
        }

        /** The bytes the planes actually hold (packed I420 readback). */
        fun packedFromPlanes(): ByteArray = YuvPlaneIo.readPlanesToPacked(4, 4, planesSnapshot())

        private fun planesSnapshot(): YuvFramePlanes =
            YuvFramePlanes(yBuf, 4, uBuf, 2, 1, vBuf, 2, 1)
    }

    @Test
    fun planes_offered_default_converter_writes_them_directly() {
        // The interface's DEFAULT convertToPlanes (packed convert +
        // strided copy) is the guaranteed fallback: it never returns
        // null, so an offered planes writer gets its buffers filled
        // byte-identically to the packed path — no writeVideoFrame call
        // at all. (A lambda inherits the default; the pipeline's own
        // dispatcher returns null on the JVM — native unavailable — and
        // takes the packed path, which the refusal test covers.)
        val converter = RgbToI420 { rgb, w, h ->
            RgbToI420Result(YuvConverter.toI420(rgb, w, h), fromNative = false)
        }
        val session = FakeSession(frames = 2, width = 4, height = 4, audio = false)
        val writer = PlanesWriter()
        ExportPipeline(session, writer, converter = converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, writer.acquireCount)
        assertEquals(2, writer.queuedPts.size)
        assertEquals(0, writer.packedFrames)
        assertEquals(0, writer.abandoned)
        val expected = YuvConverter.toI420(
            ByteArray(4 * 4 * 3) { 128.toByte() }, 4, 4,
        )
        assertTrue(writer.packedFromPlanes().contentEquals(expected))
    }

    @Test
    fun planes_refusal_falls_back_to_packed_with_abandon() {
        // convertToPlanes returning null = the conversion refused the
        // planes: the pipeline abandons the frame and feeds the writer the
        // packed bytes through writeVideoFrame — the byte-identical
        // fallback (the native twin returning false on device).
        val session = FakeSession(frames = 1, width = 4, height = 4, audio = false)
        val writer = PlanesWriter()
        val converter = object : RgbToI420 {
            override fun convert(rgb: ByteArray, width: Int, height: Int): RgbToI420Result =
                RgbToI420Result(YuvConverter.toI420(rgb, width, height), fromNative = false)

            override fun convertToPlanes(
                rgb: ByteArray,
                width: Int,
                height: Int,
                planes: YuvFramePlanes,
            ): RgbToI420PlanesResult? = null
        }
        ExportPipeline(session, writer, converter = converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, writer.acquireCount)
        assertEquals(1, writer.abandoned)
        assertEquals(0, writer.queuedPts.size)
        assertEquals(1, writer.packedFrames)
        val expected = YuvConverter.toI420(
            ByteArray(4 * 4 * 3) { 128.toByte() }, 4, 4,
        )
        assertTrue(writer.lastPackedFrame!!.contentEquals(expected))
    }

    @Test
    fun planes_null_offer_uses_the_packed_path_untouched() {
        // A writer that offers nothing (the interface default, every
        // pre-(c) fake): the packed path runs exactly as in commit (b).
        val session = FakeSession(frames = 2, width = 4, height = 4, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, writer.videoFrames)
        val expected = YuvConverter.toI420(
            ByteArray(4 * 4 * 3) { 128.toByte() }, 4, 4,
        )
        assertTrue(writer.lastVideoFrame!!.contentEquals(expected))
    }

    @Test
    fun planes_default_converter_round_trip_is_byte_identical() {
        // The pure strided-copy round trip: packed -> planes -> packed is
        // the identity, at MediaCodec-typical strides (padded chroma rows,
        // NV12 pixelStride 2 with v == u + 1).
        val rgb = ByteArray(4 * 4 * 3) { 128.toByte() }
        val packed = YuvConverter.toI420(rgb, 4, 4)
        // Planar with padded rows: yStride 8, u/vStride 4, pixelStride 1.
        val yBuf = ByteBuffer.allocate(8 * 4)
        val uBuf = ByteBuffer.allocate(4 * 2)
        val vBuf = ByteBuffer.allocate(4 * 2)
        val planar = YuvFramePlanes(yBuf, 8, uBuf, 4, 1, vBuf, 4, 1)
        YuvPlaneIo.copyPackedToPlanes(packed, 4, 4, planar)
        assertTrue(
            YuvPlaneIo.readPlanesToPacked(4, 4, planar).contentEquals(packed),
        )
        // NV12 (semi-planar): uPixelStride 2, V interleaved at u + 1.
        // The plane helpers address absolutely, so V is a SLICE at
        // offset 1 of the shared UV buffer (element 0 = first V sample) —
        // exactly how the encoder Image hands over NV12 planes.
        val nv12UV = ByteBuffer.allocate(4 * 2) // 2 chroma rows x 4 bytes
        val nv12U = nv12UV.duplicate()
        val nv12V = nv12UV.duplicate().apply { position(1) }.slice()
        val nv12 = YuvFramePlanes(yBuf, 8, nv12U, 4, 2, nv12V, 4, 2)
        YuvPlaneIo.copyPackedToPlanes(packed, 4, 4, nv12)
        assertTrue(
            YuvPlaneIo.readPlanesToPacked(4, 4, nv12).contentEquals(packed),
        )
        // The interleave really happened: UV holds u,v pairs.
        assertEquals(packed[16], nv12UV.get(0))
        assertEquals(packed[20], nv12UV.get(1))
    }

    @Test
    fun planes_dual_run_reads_encoder_memory_back() {
        // The direct path's dual-run: a converter claiming native origin
        // makes the pipeline read the planes BACK and compare against the
        // Kotlin oracle — the stride-mapping proof, logged once.
        val converter = object : RgbToI420 {
            override fun convert(rgb: ByteArray, width: Int, height: Int): RgbToI420Result =
                RgbToI420Result(YuvConverter.toI420(rgb, width, height), fromNative = true)

            override fun convertToPlanes(
                rgb: ByteArray,
                width: Int,
                height: Int,
                planes: YuvFramePlanes,
            ): RgbToI420PlanesResult {
                val packed = convert(rgb, width, height)
                YuvPlaneIo.copyPackedToPlanes(packed.i420, width, height, planes)
                return RgbToI420PlanesResult(fromNative = true)
            }
        }
        val session = FakeSession(frames = 2, width = 4, height = 4, audio = false)
        val writer = PlanesWriter()
        val lines = ArrayList<String>()
        ExportPipeline(session, writer, lines::add, converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, lines.count { it.startsWith("rgbToI420 dual-run") })
        val dual = lines.first { it.startsWith("rgbToI420 dual-run") }
        assertTrue(dual.contains("native == kotlin"))
        assertTrue(dual.contains("4x4"))
        // BOTH frames were queued from the planes, none written packed.
        assertEquals(2, writer.queuedPts.size)
        assertEquals(0, writer.packedFrames)
    }

    @Test
    fun planes_dual_run_mismatch_reports_through_the_sink() {
        // A converter that writes WRONG bytes into the planes (claims
        // native): the dual-run reads them back and the mismatch line
        // names the first differing byte — on-device debugging affordance.
        val converter = object : RgbToI420 {
            override fun convert(rgb: ByteArray, width: Int, height: Int): RgbToI420Result =
                RgbToI420Result(YuvConverter.toI420(rgb, width, height), fromNative = true)

            override fun convertToPlanes(
                rgb: ByteArray,
                width: Int,
                height: Int,
                planes: YuvFramePlanes,
            ): RgbToI420PlanesResult {
                val packed = convert(rgb, width, height)
                val corrupted = packed.i420.copyOf()
                corrupted[3] = (corrupted[3] + 1).toByte()
                YuvPlaneIo.copyPackedToPlanes(corrupted, width, height, planes)
                return RgbToI420PlanesResult(fromNative = true)
            }
        }
        val session = FakeSession(frames = 1, width = 4, height = 4, audio = false)
        val writer = PlanesWriter()
        val lines = ArrayList<String>()
        ExportPipeline(session, writer, lines::add, converter).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, lines.count { it.startsWith("rgbToI420 dual-run") })
        val dual = lines.first { it.startsWith("rgbToI420 dual-run") }
        assertTrue(dual.contains("MISMATCH at byte 3"))
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
    fun export_link_seam_detached_source_exports_video_only() {
        // AUD-02's seam: audioLinkFor turns the job's sourceId into the
        // source's link state. DETACHED -> the export still succeeds (a
        // detached source is a normal export), but the file carries no
        // audio track — the gate resolved once in export() before the
        // engine ran.
        val session = FakeSession(frames = 2, audio = true)
        val writer = FakeWriter()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
            audioLinkFor = { AudioLinkState.DETACHED },
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals(2, writer.videoFrames)
        assertEquals(0, writer.audioBlocks)
        assertEquals(0, writer.startArgs!![4]) // sampleRate 0 = no audio track
    }

    @Test
    fun export_link_seam_default_attached_keeps_audio() {
        // The default seam (audioLinkFor = { ATTACHED }) keeps the current
    // output byte-identical for a source with audio: the clip's audio
    // track is still read and muxed. An unmanaged project (no link
    // record at all) lands here too — AudioProject.link's absent-record
    // default is ATTACHED.
        val session = FakeSession(frames = 2, audio = true)
        val writer = FakeWriter()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val result = runBlocking { exporter.export(job(), 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals(2, writer.videoFrames)
        assertTrue(writer.audioBlocks > 0)
        assertEquals(48000, writer.startArgs!![4])
        assertEquals(2, writer.startArgs!![5])
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

    // ---- encoder-Surface input mode (GPU deliverable F, §10) ----

    /**
     * A writer that implements the encoder-Surface offer contract: the
     * request is recorded, the mode activates at start, and every frame is
     * taken through writeVideoFrameRgb (never the packed I420 path).
     */
    private class SurfaceWriter(
        /** When set, start() throws SurfaceInputUnavailable (the honest restart case). */
        private val unavailableAtStart: String? = null,
    ) : Mp4MediaWriter {
        var requestCount = 0
        var started = false
        var released = false
        var rgbFrames = ArrayList<Pair<Long, ByteArray>>()
        var packedFrames = 0
        var audioBlocks = 0

        override fun requestEncoderSurfaceInput() {
            requestCount++
        }

        override fun start(
            outPath: String,
            width: Int,
            height: Int,
            orientationDeg: Int,
            sampleRate: Int,
            channels: Int,
        ) {
            unavailableAtStart?.let {
                throw SurfaceInputUnavailable(it)
            }
            started = true
        }

        override fun surfaceInputActive(): Boolean = started && unavailableAtStart == null

        override fun writeVideoFrameRgb(ptsUs: Long, rgb: ByteArray, width: Int, height: Int): Boolean {
            rgbFrames.add(ptsUs to rgb.copyOf())
            return true
        }

        override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
            packedFrames++
        }

        override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {
            audioBlocks++
        }

        override fun finish(): Boolean = true

        override fun release() {
            released = true
        }
    }

    @Test
    fun surface_mode_is_requested_only_for_gpu_experimental() {
        // The explicit-parameter rule (§10: never auto): CPU and AUTO never
        // ask; GPU_EXPERIMENTAL asks exactly once, before start.
        for (backend in listOf(RenderBackend.CPU, RenderBackend.AUTO)) {
            val session = FakeSession(frames = 2, audio = false)
            val writer = SurfaceWriter()
            ExportPipeline(session, writer, renderBackend = backend).run(7, GradeK(), "/tmp/out.partial")
            assertEquals(0, writer.requestCount)
            assertEquals(2, writer.packedFrames)
            assertEquals(0, writer.rgbFrames.size)
        }
        val session = FakeSession(frames = 2, audio = false)
        val writer = SurfaceWriter()
        ExportPipeline(session, writer, renderBackend = RenderBackend.GPU_EXPERIMENTAL).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, writer.requestCount)
        assertEquals(2, writer.rgbFrames.size)
        assertEquals(0, writer.packedFrames)
    }

    @Test
    fun surface_mode_default_backend_is_cpu() {
        // The constructor default: no backend passed = the CPU reference
        // path, byte-identical to the pre-GPU pipeline (the 18 existing
        // call sites rely on this).
        val session = FakeSession(frames = 1, audio = false)
        val writer = SurfaceWriter()
        ExportPipeline(session, writer).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(0, writer.requestCount)
        assertEquals(1, writer.packedFrames)
    }

    @Test
    fun surface_mode_request_precedes_start() {
        // §10 contract: configure -> createInputSurface -> EGL window ->
        // codec.start. The pipeline's half is the request BEFORE start —
        // a writer that is asked after start must refuse (the real
        // writer throws "must be requested before start").
        var order = ArrayList<String>()
        val writer = object : Mp4MediaWriter {
            override fun requestEncoderSurfaceInput() {
                order.add("request")
            }

            override fun start(
                outPath: String,
                width: Int,
                height: Int,
                orientationDeg: Int,
                sampleRate: Int,
                channels: Int,
            ) {
                order.add("start")
            }

            override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
            override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
            override fun finish(): Boolean = true
            override fun release() {}
        }
        ExportPipeline(
            FakeSession(frames = 1, audio = false),
            writer,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(listOf("request", "start"), order)
    }

    @Test
    fun surface_mode_rgb_offer_carries_cropped_dims_and_pts() {
        // The frame offered to the Surface path is the CROPPED, UPRIGHT
        // RGB888 buffer at its true normalized pts — the same pixels the
        // I420 path would convert (§10: the GPU-developed frame draws to
        // the encoder surface; no full-frame GPU->CPU->encoder copy).
        val session = FakeSession(frames = 2, width = 8, height = 4, audio = false)
        val writer = SurfaceWriter()
        ExportPipeline(
            session,
            writer,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
        ).run(7, GradeK(), "/tmp/out.partial", manualRotationDeg = 90)
        // 90° container + 90° manual = 180 effective: no dim swap, 8x4.
        assertEquals(2, writer.rgbFrames.size)
        val (pts, rgb) = writer.rgbFrames[0]
        assertEquals(0L, pts)
        assertEquals(8 * 4 * 3, rgb.size)
        // The bytes are the session's rendered frame (128-gray here).
        assertTrue(rgb.all { it == 128.toByte() })
    }

    @Test
    fun surface_mode_refused_frame_falls_back_to_i420() {
        // The offer returning false (a writer without the mode, or the
        // real writer's CPU mode): the packed I420 path runs for that
        // frame, byte-identical — the offer is per-frame, never sticky.
        val session = FakeSession(frames = 2, audio = false)
        val writer = object : Mp4MediaWriter {
            var packed = 0
            override fun requestEncoderSurfaceInput() {}
            override fun start(
                outPath: String,
                width: Int,
                height: Int,
                orientationDeg: Int,
                sampleRate: Int,
                channels: Int,
            ) {}

            override fun writeVideoFrameRgb(ptsUs: Long, rgb: ByteArray, width: Int, height: Int): Boolean = false
            override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
                packed++
            }

            override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
            override fun finish(): Boolean = true
            override fun release() {}
        }
        ExportPipeline(
            session,
            writer,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, writer.packed)
    }

    @Test
    fun surface_unavailable_restarts_whole_export_on_cpu() {
        // §10's fallback contract: the writer could not bring the Surface
        // mode up (SurfaceInputUnavailable from start) -> the exporter
        // discards the partial, logs the honest fallback line, and re-runs
        // the WHOLE export on the CPU path with a FRESH writer — no frame
        // encoded twice, no partial continued mid-format.
        val session = FakeSession(frames = 2, audio = false)
        val failWriter = SurfaceWriter(
            unavailableAtStart = Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_CONFIGURE,
                "no EGL display is available",
                "the export restarts on the CPU path",
            ),
        )
        val cpuWriter = FakeWriter()
        val writers = ArrayDeque(listOf<Mp4MediaWriter>(failWriter, cpuWriter))
        val lines = ArrayList<String>()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writers.removeFirst() },
            engine = freshEngine(),
            stageLog = lines::add,
        )
        val out = File(tmp.root, "clip.mp4")
        val gpuJob = job().copy(renderBackend = RenderBackend.GPU_EXPERIMENTAL)
        val result = runBlocking { exporter.export(gpuJob, 7, out) }
        assertEquals(State.DONE, result.state)
        // The fallback line reached the stage sink, honestly naming both
        // ends and the reason.
        assertTrue(lines.any { it.contains("render backend fallback") })
        assertTrue(lines.any { it.contains("requested=GPU_EXPERIMENTAL") })
        assertTrue(lines.any { it.contains("effective=CPU") })
        assertTrue(lines.any { it.contains("reason=surface-input-unavailable") })
        // The CPU re-run encoded every frame through the packed path.
        assertEquals(2, cpuWriter.videoFrames)
        // The failing writer was released (its partial EGL/codec state
        // went down), and the session opened exactly twice (one whole
        // run per attempt).
        assertTrue(failWriter.released)
        assertEquals(2, session.opened.get())
        assertEquals(2, session.closed.get())
        // The file landed: the restart produced the DONE output.
        assertTrue(out.exists())
        assertFalse(File(out.parentFile, out.name + ".partial").exists())
    }

    @Test
    fun surface_unavailable_mid_encode_is_a_plain_failure_not_a_restart() {
        // The restart contract covers writer START only: a
        // SurfaceInputUnavailable thrown mid-encode would be a bug (the
        // class KDoc forbids it), but the exporter must never interpret a
        // mid-stream failure as restartable — only the start-phase throw
        // restarts. A writeVideoFrameRgb that throws a plain encode
        // failure fails the export honestly (no restart loop).
        val session = FakeSession(frames = 2, audio = false)
        var writersServed = 0
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = {
                writersServed++
                object : Mp4MediaWriter {
                    override fun requestEncoderSurfaceInput() {}
                    override fun start(
                        outPath: String,
                        width: Int,
                        height: Int,
                        orientationDeg: Int,
                        sampleRate: Int,
                        channels: Int,
                    ) {}

                    override fun writeVideoFrameRgb(ptsUs: Long, rgb: ByteArray, width: Int, height: Int): Boolean {
                        throw IllegalStateException("encoder Surface swap failed")
                    }

                    override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {}
                    override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {}
                    override fun finish(): Boolean = true
                    override fun release() {}
                }
            },
            engine = freshEngine(),
        )
        val out = File(tmp.root, "clip.mp4")
        val gpuJob = job().copy(renderBackend = RenderBackend.GPU_EXPERIMENTAL)
        val result = runBlocking { exporter.export(gpuJob, 7, out) }
        assertEquals(State.FAILED, result.state)
        assertTrue(result.detail!!.contains("encoder Surface swap failed"))
        // Exactly one writer: no restart was attempted.
        assertEquals(1, writersServed)
        assertFalse(out.exists())
    }

    /** An executor that rejects everything (shutdown engine). */
    private class RejectedExecutor : ExecutorService by Executors.newSingleThreadExecutor() {
        override fun execute(command: Runnable) {
            throw RejectedExecutionException("engine shut down")
        }
    }
}
