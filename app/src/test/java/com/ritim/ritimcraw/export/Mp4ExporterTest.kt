package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.LutEncodingK
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.engine.GradeParcel
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
            "nr+unsharp+temporal+gyro+tonemap+lut",
            ExportStageTiming.effects(
                GradeK(
                    noiseReduction = 40f,
                    sharpness = 30f,
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
        // Detail-only unsharp still counts as "unsharp" (either percent
        // nonzero rides the same four-pass chain, matching the session's
        // own raw != 0 gate); NEGATIVE sharpness too — softening is the
        // same per-frame cost as sharpening.
        assertEquals("unsharp", ExportStageTiming.effects(GradeK(detail = 15f), gyroActive = false))
        assertEquals("unsharp", ExportStageTiming.effects(GradeK(sharpness = -20f), gyroActive = false))
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
        // Frame 1's checkpoint sample line lands first, then the close()
        // pair (averages + summary), then the render-backend pair from the
        // finally (the develop axis is only knowable AFTER the loop —
        // commit (c)). The fake writer never calls attachTiming's sink
        // (default no-op), so its stages read 0.0ms — honest zeros, not
        // fabricated numbers.
        assertEquals(5, lines.size)
        assertTrue(lines[0].startsWith("export frame 1: "))
        assertTrue(lines[1].startsWith("export stages avg over 2 frames: "))
        assertTrue(lines[2].startsWith("EXPORT SUMMARY: "))
        assertTrue(lines[3].startsWith("render backend: "))
        assertTrue(lines[4] == "encoder input: bytes")
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
        assertEquals(5, lines.size)
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
        // The gyroPath slot every CPU render carried (S4: the lock that an
        // image-engine grade never leaks a gyro path and a gyro grade's
        // path still arrives untouched).
        val renderGyroPaths = ArrayList<String?>()
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
                    chainWire: FloatArray?,
        ): ByteArray? {
            if (frameIndex == failAtFrame) {
                lastCode = Mp4ExportErrors.CODE_IO
                lastStage = Mp4ExportErrors.STAGE_DEVELOP
                lastDetail = "develop: ioError — frame $frameIndex"
                return null
            }
            rendered.add(frameIndex)
            renderGyroPaths.add(gyroPath)
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
    fun pipeline_resolution_bound_starts_writer_at_the_resolved_size() {
        // Export resolution (owner request 2026-11-13): a job that carries
        // a bound resolves it through the SAME fits-within rules the
        // sheet's chips showed (ExportResolution.outputSize against the
        // crop/rotation-effective size) — the writer's encoder dims ARE
        // the resolved size, so the encode can never disagree with the
        // choice. FakeSession is 4x4 with orientationDeg 90 (square, so
        // the upright dims stay 4x4): a 2x2 bound resolves to 2x2.
        val session = FakeSession(frames = 2, audio = false)
        val writer = FakeWriter()
        ExportPipeline(session, writer, resolutionW = 2, resolutionH = 2)
            .run(7, GradeK(), "/tmp/out.partial")
        // The writer started at the RESOLVED dims, not the source's.
        assertEquals(2, writer.startArgs!![1])
        assertEquals(2, writer.startArgs!![2])
        // And every frame the writer received was converted at those
        // dims: I420 is w*h*3/2 bytes (2*2*3/2 = 6), not the source's
        // 4*4*3/2 = 24 — the aspectFit step ran between crop and the
        // conversion.
        assertEquals(2 * 2 * 3 / 2, writer.lastVideoFrame!!.size)
        assertEquals(2, writer.videoFrames)
    }

    @Test
    fun pipeline_null_bound_is_byte_identical_to_the_reference_path() {
        // Null pair = Original = today's behavior: the writer starts at
        // the crop dims (the source's effective size), the scale step is
        // skipped entirely (not even a method dispatch), and the frames
        // are byte-identical to a pipeline constructed with no bound at
        // all. The chips' default IS the pre-feature export.
        val session = FakeSession(frames = 2, audio = false)
        val nullBound = FakeWriter() // constructed with the default null pair
        ExportPipeline(session, nullBound).run(7, GradeK(), "/tmp/out.partial")
        val reference = FakeWriter() // constructed with no bound at all
        ExportPipeline(session, reference).run(7, GradeK(), "/tmp/out.partial")

        assertEquals(nullBound.startArgs!![1], reference.startArgs!![1])
        assertEquals(nullBound.startArgs!![2], reference.startArgs!![2])
        assertEquals(4, reference.startArgs!![1]) // the crop dims, not a bound
        assertEquals(4, reference.startArgs!![2])
        assertTrue(
            nullBound.lastVideoFrame!!.contentEquals(reference.lastVideoFrame!!)
        )
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
    fun the_image_pre_stage_seam_is_never_called_while_the_flag_is_off() {
        // S4: imageAnalysisFor is the export's analysis pre-stage — but
        // with IMAGE_STABILIZATION_ENABLED false (the shipped state) an
        // image-engine snapshot NEVER reaches it: the export runs
        // unstabilized, exactly as before, and the app's seam (which
        // would suspend on the analysis service) is never invoked.
        var seamCalls = 0
        val session = FakeSession(frames = 2, audio = false)
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { FakeWriter() },
            engine = freshEngine(),
            imageAnalysisFor = { seamCalls++; "/never/built.ristab" },
        )
        val imageJob = job().copy(
            snapshot = GradeK(stabilization = true, stabilizationEngine = 1),
        )
        val out = File(tmp.root, "dark.mp4")
        val result = runBlocking { exporter.export(imageJob, 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals(0, seamCalls)
        assertEquals(listOf<String?>(null, null), session.renderGyroPaths)
    }

    @Test
    fun gyro_and_off_snapshots_never_ask_the_image_pre_stage_seam() {
        // Even with the flag flipped on in the future engine build, a
        // gyro grade (engine 0 / absent) and a stabilization-off grade
        // never ask for an analysis: the seam is image-engine-only.
        var seamCalls = 0
        val exporter = Mp4Exporter(
            session = FakeSession(frames = 1, audio = false),
            writerFactory = { FakeWriter() },
            engine = freshEngine(),
            imageAnalysisFor = { seamCalls++; "/never/built.ristab" },
        )
        // The snapshot gate reads the grade BEFORE any flag choice, so
        // these two jobs skip the seam at any flag state.
        val gyroJob = job().copy(snapshot = GradeK(stabilization = true))
        val offJob = job()
        runBlocking { exporter.export(gyroJob, 7, File(tmp.root, "g.mp4")) }
        runBlocking { exporter.export(offJob, 7, File(tmp.root, "n.mp4")) }
        assertEquals(0, seamCalls)
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
    fun export_audio_snapshot_wins_over_live_link() {
        // QA-15 / EX-11: the job's ENQUEUE-time audio choice wins over the
        // live link. Queued ATTACHED, the user detached afterwards (the
        // live seam now says DETACHED) -> the export still carries audio.
        val session = FakeSession(frames = 2, audio = true)
        val writer = FakeWriter()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
            audioLinkFor = { AudioLinkState.DETACHED },
        )
        val out = File(tmp.root, "clip.mp4")
        val queued = job().copy(audioLink = AudioLinkState.ATTACHED)
        val result = runBlocking { exporter.export(queued, 7, out) }
        assertEquals(State.DONE, result.state)
        assertTrue(writer.audioBlocks > 0)
        assertEquals(48000, writer.startArgs!![4])
    }

    @Test
    fun export_audio_snapshot_detached_stays_video_only_after_reattach() {
        // The mirror case: queued DETACHED, re-attached before the retry
        // ran -> the retry honors the queued choice (video-only).
        val session = FakeSession(frames = 2, audio = true)
        val writer = FakeWriter()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writer },
            engine = freshEngine(),
            audioLinkFor = { AudioLinkState.ATTACHED },
        )
        val out = File(tmp.root, "clip.mp4")
        val retried = job().copy(audioLink = AudioLinkState.DETACHED).requeued()
        assertEquals(AudioLinkState.DETACHED, retried.audioLink)
        val result = runBlocking { exporter.export(retried, 7, out) }
        assertEquals(State.DONE, result.state)
        assertEquals(0, writer.audioBlocks)
        assertEquals(0, writer.startArgs!![4])
    }

    @Test
    fun effective_audio_link_falls_back_to_live_for_legacy_rows() {
        // A row queued before the snapshot field (audioLink == null)
        // keeps the legacy live lookup exactly.
        val exporter = Mp4Exporter(audioLinkFor = { AudioLinkState.REMOVED })
        assertEquals(AudioLinkState.REMOVED, exporter.effectiveAudioLink(job()))
        assertEquals(
            AudioLinkState.ATTACHED,
            exporter.effectiveAudioLink(job().copy(audioLink = AudioLinkState.ATTACHED)),
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

    // ---- encoder-Surface input mode (GPU deliverable F, §10) ----

    /**
     * A writer that implements the encoder-Surface offer contract: the
     * request is recorded, the mode activates at start, and every frame is
     * taken through writeVideoFrameRgb (never the packed I420 path).
     */
    private class SurfaceWriter(
        /** When set, start() throws SurfaceInputUnavailable (the honest restart case). */
        private val unavailableAtStart: String? = null,
        /** The bring-up step the throw names (null = the unknown-step legacy case). */
        private val unavailableStep: SurfaceInputStep? = null,
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
                throw SurfaceInputUnavailable(it, unavailableStep)
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

    /**
     * A writer that implements the M5 session-owns-EGL contract: the
     * request is recorded BEFORE start, start() hands the codec input
     * Surface out UNWRAPPED (the GPU develop session becomes the
     * BufferQueue producer), the pipeline drains the codec per frame,
     * and adoptEncoderSurface() stands in for the writer wrapping the
     * SAME surface in its own EGL (the fallback: frames then flow
     * through writeVideoFrameRgb, the shipped CPU-RGB Surface mode).
     */
    private class SessionSurfaceWriter(
        /** When set, adoptEncoderSurface() throws SurfaceInputUnavailable. */
        private val adoptUnavailable: String? = null,
        /** The bring-up step the adopt throw names (null = the unknown-step legacy case). */
        private val adoptStep: SurfaceInputStep? = null,
    ) : Mp4MediaWriter {
        val sessionRequests = ArrayList<String>()
        var started = false
        var released = false
        var ownSurfaceMode = false
        val adoptCalls = ArrayList<Unit>()
        var drainCalls = 0
        var rgbFrames = 0
        var packedFrames = 0
        var audioBlocks = 0

        /** The stand-in codec input Surface, handed out unwrapped. */
        val inputSurface = Any()

        override fun requestEncoderSurfaceInput() {
            ownSurfaceMode = true
            sessionRequests.add("requestEncoderSurfaceInput")
        }

        override fun requestSessionEncoderSurface() {
            sessionRequests.add("requestSessionEncoderSurface")
        }

        override fun encoderInputSurface(): Any? = inputSurface

        override fun adoptEncoderSurface() {
            adoptUnavailable?.let { throw SurfaceInputUnavailable(it, adoptStep) }
            adoptCalls.add(Unit)
        }

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

        override fun surfaceInputActive(): Boolean =
            started && (ownSurfaceMode || adoptCalls.isNotEmpty())

        override fun drainEncoderInput() {
            drainCalls++
        }

        override fun writeVideoFrameRgb(ptsUs: Long, rgb: ByteArray, width: Int, height: Int): Boolean {
            rgbFrames++
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
    fun surface_unavailable_fallback_line_names_the_failing_step() {
        // Owner bug report 2026-11-13: the fallback line said only
        // reason=surface-input-unavailable and the diagnosis could not
        // tell WHICH bring-up step failed — the Surface creation, the EGL
        // wrap, or the recordable query. The writer now names its step
        // (SurfaceInputStep) and the SAME one-line reason gains the
        // step's token: reason=surface-input-unavailable:<step>.
        val session = FakeSession(frames = 2, audio = false)
        val failWriter = SurfaceWriter(
            unavailableAtStart = Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_CONFIGURE,
                "no EGL window surface could be created on the input Surface",
                "the export restarts on the CPU path",
            ),
            unavailableStep = SurfaceInputStep.EGL_WRAP,
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
        // The FULL fallback line, exactly — one line, both ends, the
        // reason carrying the failing step's token.
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertEquals(
            "render backend fallback: requested=GPU_EXPERIMENTAL effective=CPU " +
                "reason=surface-input-unavailable:egl-wrap",
            fallback,
        )
        // The line-format contract: ONE line, no newline inside it.
        assertFalse(fallback.contains('\n'))
    }

    @Test
    fun surface_unavailable_unknown_step_keeps_the_exact_legacy_reason() {
        // The back-compat contract: a step-less SurfaceInputUnavailable
        // (a legacy writer, or the writer's mode-confusion guard) keeps
        // the EXACT legacy reason string — the owner's existing greps and
        // every pre-step test stay byte-identical. No suffix, no second
        // line.
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
        // The FULL line, exactly the legacy shape — reason= ends the line.
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertEquals(
            "render backend fallback: requested=GPU_EXPERIMENTAL effective=CPU " +
                "reason=surface-input-unavailable",
            fallback,
        )
        assertFalse(fallback.contains('\n'))
    }

    @Test
    fun every_surface_input_step_round_trips_into_the_fallback_reason() {
        // The closed vocabulary is the contract: EVERY SurfaceInputStep
        // token reaches the fallback line through the REAL emit site as
        // reason=surface-input-unavailable:<token> — and every variant
        // still contains the legacy reason string, so the owner's
        // existing greps keep hitting every step.
        for (step in SurfaceInputStep.entries) {
            val session = FakeSession(frames = 2, audio = false)
            val failWriter = SurfaceWriter(
                unavailableAtStart = "configure: bring-up step failed",
                unavailableStep = step,
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
            val out = File(tmp.root, "clip-${step.token}.mp4")
            val gpuJob = job().copy(renderBackend = RenderBackend.GPU_EXPERIMENTAL)
            val result = runBlocking { exporter.export(gpuJob, 7, out) }
            assertEquals("step ${step.token} must restart on CPU", State.DONE, result.state)
            val fallback = lines.first { it.startsWith("render backend fallback: ") }
            assertEquals(
                "step ${step.token} must name itself in the reason",
                "render backend fallback: requested=GPU_EXPERIMENTAL effective=CPU " +
                    "reason=surface-input-unavailable:${step.token}",
                fallback,
            )
            // The owner's legacy grep still hits every step variant.
            assertTrue(
                "step ${step.token} must keep the legacy reason substring",
                fallback.contains("reason=surface-input-unavailable"),
            )
            assertFalse(fallback.contains('\n'))
        }
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

    // ---- render-backend visibility line (owner request 2026-11-13, (c)) ----

    @Test
    fun render_backend_line_reports_every_export_honestly() {
        // CPU requested: the same line, effective=CPU, and the grade's
        // active uncovered stages as tokens — the line is per-export
        // visibility, not a fallback-only report. The encoder-input line is
        // unconditional now (commit (c)): a CPU export says bytes.
        val cpuLines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            FakeWriter(),
            cpuLines::add,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, cpuLines.count { it.startsWith("render backend: ") })
        val cpuLine = cpuLines.first { it.startsWith("render backend: ") }
        assertTrue("requested missing: $cpuLine", cpuLine.contains("requested=CPU"))
        assertTrue("effective missing: $cpuLine", cpuLine.contains("effective=CPU"))
        assertTrue("unsupported must be none: $cpuLine", cpuLine.contains("unsupported=none"))
        assertTrue("cpu must say bytes: $cpuLines", cpuLines.any { it == "encoder input: bytes" })

        // GPU_EXPERIMENTAL + a green GPU develop seam + a writer that took
        // the Surface mode: the line says effective=GLES — the DEVELOP axis
        // (the session rendered the frames; the JVM default seam would have
        // caught IllegalStateException and stayed CPU, so the green fake is
        // what makes the GLES shape reachable here) — and both encoder-input
        // and develop axes are visible on their own lines.
        val gpuLines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            SurfaceWriter(),
            gpuLines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = FakeGpuDevelop(),
        ).run(7, GradeK(), "/tmp/out.partial")
        val gpuLine = gpuLines.first { it.startsWith("render backend: ") }
        assertTrue("requested missing: $gpuLine", gpuLine.contains("requested=GPU_EXPERIMENTAL"))
        assertTrue("effective missing: $gpuLine", gpuLine.contains("effective=GLES"))
        assertTrue("unsupported must be none: $gpuLine", gpuLine.contains("unsupported=none"))
        assertTrue(gpuLines.any { it == "encoder input: surface" })
    }

    @Test
    fun render_backend_line_names_the_unsupported_tokens_exactly() {
        // The tokens are the exact BackendRules fragment vocabulary: a grade
        // with an active uncovered stage names its token; a neutral grade
        // says "none". Chroma denoise is FRAGMENT-COVERED since the
        // spatial-NR port (stages 7+8), sharpness/detail since the
        // sharpness/detail port (stages 21+22), and the gyro warp since the
        // gyro-warp port's seam leg (the pass is pure math — no
        // texture-format leg — so a stabilized grade with a log attached
        // rides GLES like any other covered grade and NEVER names a warp
        // token): a LUT grade with the texture path unavailable is the
        // uncovered-stage stand-in now (the ONLY stage token left
        // anywhere).
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            FakeWriter(),
            lines::add,
        ).run(
            7,
            GradeK(lutId = "milog", lutStrength = 85f, stabilization = true),
            "/tmp/out.partial",
            gyroPath = "/tmp/gyro.log",
        )
        val line = lines.first { it.startsWith("render backend: ") }
        assertTrue("lut missing: $line", line.contains("lut"))
        assertTrue(
            "gyro-warp must be absent (fragment-covered): $line",
            !line.contains("gyro-warp"),
        )
        assertTrue(
            "chroma-denoise must be absent (fragment-covered): $line",
            !line.contains("chroma-denoise"),
        )
        assertTrue(
            "sharpness must be absent (fragment-covered): $line",
            !line.contains("sharpness"),
        )
        // Stabilization off: the attached log alone does not warp — the
        // line stays honest either way, with no warp token in either
        // case.
        val idleLines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            FakeWriter(),
            idleLines::add,
        ).run(7, GradeK(), "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        val idleLine = idleLines.first { it.startsWith("render backend: ") }
        assertTrue(
            "gyro-warp must be absent: $idleLine",
            !idleLine.contains("gyro-warp"),
        )
        assertTrue("unsupported must be none: $idleLine", idleLine.contains("unsupported=none"))
    }

    @Test
    fun render_backend_line_gpu_request_without_surface_mode_is_cpu() {
        // GPU_EXPERIMENTAL + the JVM's default seam (no .so: the probe
        // throws, the honest all-false gates keep the develop on CPU): the
        // line reports effective=CPU on the DEVELOP axis — the GPU develop
        // never engaged, never silently claimed — and the encoder-input
        // line says bytes (the writer never took the Surface mode).
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            FakeWriter(),
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
        ).run(7, GradeK(), "/tmp/out.partial")
        val line = lines.first { it.startsWith("render backend: ") }
        assertTrue("requested missing: $line", line.contains("requested=GPU_EXPERIMENTAL"))
        assertTrue("effective must be CPU: $line", line.contains("effective=CPU"))
        assertTrue(lines.any { it == "encoder input: bytes" })
    }

    @Test
    fun render_backend_line_develop_axis_is_independent_of_encoder_input() {
        // Commit (c)'s core fix: the develop axis and the encoder-input mode
        // are SEPARATE lines because they are separate truths. A GPU-developed
        // export whose writer never took the Surface mode (GPU_EXPERIMENTAL,
        // a green seam, a plain writer whose per-frame offer refuses) reports
        // effective=GLES on the develop axis while the encoder-input line
        // honestly says bytes — the pre-(c) line collapsed both axes into a
        // misleading effective=CPU.
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            FakeWriter(),
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = FakeGpuDevelop(),
        ).run(7, GradeK(), "/tmp/out.partial")
        val line = lines.first { it.startsWith("render backend: ") }
        assertTrue("requested missing: $line", line.contains("requested=GPU_EXPERIMENTAL"))
        assertTrue("develop axis must be GLES: $line", line.contains("effective=GLES"))
        assertTrue("encoder input must say bytes: $lines", lines.any { it == "encoder input: bytes" })
        assertFalse(lines.any { it == "encoder input: surface" })
        // No develop fallback: the GPU develop ran end to end.
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
    }

    // ---- GPU develop dispatch (M3 commit b) ----

    /**
     * A fake GPU develop seam: configurable probe/parity arrays (the raw
     * shapes the JNI returns), a configurable session handle, and a
     * recorder for every entry point. The DISPATCHER owns every decision
     * (fromArray, verdict, gates) — this fake only reports, exactly like
     * the native seam it stands in for.
     */
    private class FakeGpuDevelop(
        private val probeArray: DoubleArray? =
            doubleArrayOf(1.0, 3.0, 2.0, 1.0, 1.0, 4096.0),
        private val parityArray: DoubleArray? =
            doubleArrayOf(1.0, 1.0, 398.0, 24_800_000.0),
        private val openHandle: Long = 1L,
        private val frameBytes: ByteArray = ByteArray(4 * 4 * 3) { 77.toByte() },
        private val failRenderFrom: Int = -1,
        private val detail: String? = null,
        // M5: the session encoder path's knobs. refuseEncoderSurface makes
        // setEncoderSurface return false (the session-refused adopt case);
        // failEncoderRenderFrom makes renderFrameToEncoderParcel return
        // false from that frame on (the mid-stream refusal case) — and,
        // mirroring the native latch (an Encoder-job failure latches the
        // session unhealthy, so the READBACK dies with it), the readback
        // renderFrameParcel refuses from that frame on too. Defaults keep
        // every pre-M5 test's behavior untouched (the interface defaults
        // served).
        private val refuseEncoderSurface: Boolean = false,
        private val failEncoderRenderFrom: Int = -1,
        private val encoderDetail: String? = null,
        // The gyro warp port's commit b: the resolver's configurable
        // verdict (the raw shape the JNI returns — [0]=active, [1..9]=M,
        // null = no verdict). The DISPATCHER owns the fold (null →
        // inactive + identity M); this fake only reports.
        private val warpArray: FloatArray? = null,
        private val failWarpResolveWithIllegalState: Boolean = false,
    ) : GpuDevelop {
        var probeCalls = 0
        val parityFds = ArrayList<Int>()
        var openCalls = 0
        val rendered = ArrayList<Int>()
        val closedHandles = ArrayList<Long>()
        var lastSessionHandle = 0L
        var lastClipHandle = 0L
        var lastExposureEv = Float.NaN
        var lastHighlights = Float.NaN
        var lastHighlightReconstruction = false
        var lastKeepVignetting = false
        var lastTonemap = -1

        // The two spatial-NR stages' raw values the last READBACK render
        // carried — the stages-7/8 verbatim pass-through lock's snapshot
        // (same shape as the pointwise recorder below).
        var lastNr: NrControls? = null

        // The temporal pair the last READBACK render carried — the hybrid's
        // routing lock's snapshot: the dispatcher threads the grade values
        // VERBATIM, and the bridge's own temporalMergedCfa consumes them
        // to produce the CFA the render uploads (the pair dies at the
        // bridge — the session request never sees it).
        var lastTemporal: TemporalControls? = null

        // The two unsharp stages' raw percents the last READBACK render
        // carried — the stages-21/22 verbatim pass-through lock's snapshot
        // (no clamp: negative sharpness is valid softening).
        var lastSharpnessDetail: SharpnessDetailControls? = null

        // The ten pointwise stages' raw values the last READBACK render
        // carried, in the seam's parameter order — the verbatim
        // pass-through lock's snapshot (a swapped or dropped parameter in
        // the dispatcher fails the data-class equality with the field
        // named).
        var lastPointwise: PointwiseControls? = null

        // M5 recorders: every setEncoderSurface call (handle + the surface
        // object handed in, or the null detach) and every encoder render.
        // lastEncoderRotationDeg records the rotation the dispatch carried
        // (the surface-gpu vertical deliverable: 0 for upright, the
        // effective 90/180/270 for a rotated eligible job). The encoder
        // blit reuses the SAME develop program, so the pointwise recorder
        // rides it too.
        val encoderSurfaces = ArrayList<Pair<Long, Any?>>()
        val encoderRendered = ArrayList<Int>()
        var lastEncoderPtsUs = -1L
        var lastEncoderSessionHandle = 0L
        var lastEncoderRotationDeg = Int.MIN_VALUE
        var lastEncoderPointwise: PointwiseControls? = null

        // The encoder blit reuses the SAME develop program family (the NR
        // multi-pass chain included), so the stages-7/8 recorder rides it
        // too — the encoder pass-through lock's snapshot.
        var lastEncoderNr: NrControls? = null

        // Same for the temporal pair: the encoder blit's CFA comes from the
        // SAME bridge merge, so the encoder pass-through lock rides it too
        // (the grade values verbatim since the hybrid).
        var lastEncoderTemporal: TemporalControls? = null

        // Same for the unsharp pair: the encoder blit runs the same
        // four-pass chain, so the stages-21/22 recorder rides it too.
        var lastEncoderSharpnessDetail: SharpnessDetailControls? = null

        // The seven LUT controls (stage 24) the last READBACK render
        // carried, in the seam's parameter order — the verbatim
        // pass-through lock's snapshot (the resolved path, the [0,1]
        // strength from the grade's percent, and the five interpretation
        // ordinals; a swapped or dropped parameter in the dispatcher fails
        // the data-class equality with the field named).
        var lastLut: LutControls? = null

        // Same seven controls on the encoder blit — the encoder
        // pass-through lock's snapshot.
        var lastEncoderLut: LutControls? = null

        // The warp pair the last READBACK render carried — the pass-
        // through lock's snapshot (the active flag and the nine-float
        // map; the INACTIVE case rides the same recorder with
        // warpActive=false + the identity M, asserting the dispatcher's
        // null fold and the no-warp path's verbatim threading).
        var lastWarp: WarpControls? = null

        // Same pair on the encoder blit — the encoder pass-through
        // lock's snapshot.
        var lastEncoderWarp: WarpControls? = null

        // Every resolveWarp call's frame index — the per-frame
        // resolution lock (the dispatch resolves the plan EACH frame,
        // never once per job).
        val warpResolved = ArrayList<Int>()

        // Every image-engine resolve (S4); must stay empty while the
        // image flag is off.
        val imageResolved = ArrayList<Int>()

        override fun probe(): DoubleArray? {
            probeCalls++
            return probeArray
        }

        override fun fragmentParity(fd: Int): DoubleArray? {
            parityFds.add(fd)
            return parityArray
        }

        override fun openSession(): Long {
            openCalls++
            return openHandle
        }

        /**
         * GRADE PARCEL (commit a + b): the readback render door — the ONLY
         * readback entry since the flat 41-parameter door died with commit
         * (b). Decodes the parcel via GradeParcel.decode (the REAL decoder,
         * so the fake asserts the SAME wire format the bridge reads — the
         * decode-refusal contract is exercised by GradeParcelTest) and
         * records the same snapshots the flat recorder once did, field
         * for field.
         */
        override fun renderFrameParcel(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            gradeParcel: FloatArray,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
            warpActive: Boolean,
            warpM: FloatArray,
        ): ByteArray? {
            val g = GradeParcel.decode(gradeParcel)
            if (failRenderFrom >= 0 && frameIndex >= failRenderFrom) return null
            // The native latch mirror: an encoder-render failure latched
            // the session unhealthy, so the readback refuses too.
            if (failEncoderRenderFrom >= 0 && frameIndex >= failEncoderRenderFrom) return null
            rendered.add(frameIndex)
            lastSessionHandle = sessionHandle
            lastClipHandle = clipHandle
            lastExposureEv = g.exposureEv
            lastHighlights = g.highlights
            lastHighlightReconstruction = g.highlightReconstruction
            lastKeepVignetting = g.keepVignetting
            lastTonemap = g.tonemap
            lastNr = NrControls(g.chromaDenoise, g.noiseReduction)
            lastTemporal = TemporalControls(g.temporalNr, g.temporalStrength)
            lastSharpnessDetail = SharpnessDetailControls(g.sharpness, g.detail)
            lastPointwise = PointwiseControls(
                g.blackPoint, g.whitePoint, g.shadows, g.midtones, g.contrast,
                g.tempK, g.tint, g.saturation, g.asShot,
                g.wheelShadowX, g.wheelShadowY, g.wheelMidX, g.wheelMidY,
                g.wheelHighX, g.wheelHighY,
                g.splitShadowHue, g.splitHighlightHue, g.splitBalance,
            )
            lastLut = LutControls(
                lutPath, lutStrength, lutRole,
                lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
            )
            lastWarp = WarpControls(warpActive, warpM)
            return frameBytes
        }

        override fun setEncoderSurface(sessionHandle: Long, surface: Any?): Boolean {
            encoderSurfaces.add(sessionHandle to surface)
            if (refuseEncoderSurface) return false
            return true
        }

        /**
         * GRADE PARCEL (commit a + b): the encoder render door — the ONLY
         * encoder entry since the flat 43-parameter door died with commit
         * (b). Same decode-and-record shape as the readback door plus the
         * two plumbing values the encoder blit adds (ptsUs, rotationDeg).
         */
        override fun renderFrameToEncoderParcel(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            ptsUs: Long,
            gradeParcel: FloatArray,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
            warpActive: Boolean,
            warpM: FloatArray,
            rotationDeg: Int,
        ): Boolean {
            val g = GradeParcel.decode(gradeParcel)
            if (failEncoderRenderFrom >= 0 && frameIndex >= failEncoderRenderFrom) {
                return false
            }
            encoderRendered.add(frameIndex)
            lastEncoderPtsUs = ptsUs
            lastEncoderSessionHandle = sessionHandle
            lastEncoderRotationDeg = rotationDeg
            lastEncoderNr = NrControls(g.chromaDenoise, g.noiseReduction)
            lastEncoderTemporal = TemporalControls(g.temporalNr, g.temporalStrength)
            lastEncoderSharpnessDetail = SharpnessDetailControls(g.sharpness, g.detail)
            lastEncoderPointwise = PointwiseControls(
                g.blackPoint, g.whitePoint, g.shadows, g.midtones, g.contrast,
                g.tempK, g.tint, g.saturation, g.asShot,
                g.wheelShadowX, g.wheelShadowY, g.wheelMidX, g.wheelMidY,
                g.wheelHighX, g.wheelHighY,
                g.splitShadowHue, g.splitHighlightHue, g.splitBalance,
            )
            lastEncoderLut = LutControls(
                lutPath, lutStrength, lutRole,
                lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
            )
            lastEncoderWarp = WarpControls(warpActive, warpM)
            return true
        }

        override fun resolveWarp(
            clipHandle: Long,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
            frameIndex: Int,
            maxDim: Int,
        ): FloatArray? {
            // The unavailable-guard fold's stand-in: the dispatcher catches
            // IllegalStateException (the no-.so mid-export case) and folds
            // it to the inactive verdict, never letting the seam throw.
            if (failWarpResolveWithIllegalState) {
                throw IllegalStateException(NativeSource.nativeUnavailableMessage)
            }
            warpResolved.add(frameIndex)
            return warpArray
        }

        override fun resolveImageWarp(
            clipHandle: Long,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            rangeStart: Int,
            rangeEnd: Int,
            frameIndex: Int,
            maxDim: Int,
        ): FloatArray? {
            imageResolved.add(frameIndex)
            return null
        }

        override fun closeSession(sessionHandle: Long) {
            closedHandles.add(sessionHandle)
        }

        override fun lastDetail(): String? = detail ?: encoderDetail
    }

    /**
     * The two spatial-NR stages' raw values one render carried, in the
     * seam's parameter order — shared by the readback and the encoder
     * recorders so a swapped or dropped parameter fails the equality with
     * the field named (stages 7/8 ride the multi-pass fragment chain).
     */
    private data class NrControls(
        val chromaDenoise: Float,
        val noiseReduction: Float,
    )

    /**
     * The temporal pair one render carried, in the seam's parameter order —
     * shared by the readback and the encoder recorders so a swapped or
     * dropped parameter fails the equality with the field named. The pair
     * never reaches the session request: it dies at the bridge, consumed
     * by the bridge's own temporalMergedCfa to produce the CFA the request
     * uploads (the hybrid's routing, not a shader stage).
     */
    private data class TemporalControls(
        val temporalNr: Boolean,
        val temporalStrength: Float,
    )

    /**
     * The two unsharp stages' raw percents one render carried, in the
     * seam's parameter order — shared by the readback and the encoder
     * recorders so a swapped or dropped parameter fails the equality with
     * the field named (stages 21/22 ride the four-pass unsharp chain;
     * threaded VERBATIM with no clamp, because negative values are valid
     * softening).
     */
    private data class SharpnessDetailControls(
        val sharpness: Float,
        val detail: Float,
    )

    /**
     * The seven LUT controls (stage 24) one render carried, in the seam's
     * parameter order — shared by the readback and the encoder recorders
     * so a swapped or dropped parameter fails the equality with the field
     * named (the resolved .cube path, the [0,1] strength, and the five
     * interpretation ordinals, -1 = legacy).
     */
    private data class LutControls(
        val lutPath: String?,
        val lutStrength: Float,
        val lutRole: Int,
        val lutInGamut: Int,
        val lutInTransfer: Int,
        val lutOutGamut: Int,
        val lutOutTransfer: Int,
    )

    /**
     * The warp pair one render carried, in the seam's parameter order —
     * shared by the readback and the encoder recorders so a swapped or
     * dropped parameter fails the equality with the field named (the
     * active flag and the nine-float composite inverse-sampling map;
     * warpActive=false means the session SKIPS draw 10 entirely, the
     * identity no-draw rule).
     */
    private data class WarpControls(
        val warpActive: Boolean,
        val warpM: FloatArray,
    ) {
        override fun equals(other: Any?): Boolean = other is WarpControls &&
            warpActive == other.warpActive && warpM.contentEquals(other.warpM)
        override fun hashCode(): Int = 31 * warpActive.hashCode() + warpM.contentHashCode()
    }

    /**
     * The ten pointwise stages' raw values one render carried, in the
     * seam's parameter order — shared by the readback and the encoder
     * recorders so a swapped or dropped parameter fails the equality with
     * the field named.
     */
    private data class PointwiseControls(
        val blackPoint: Float,
        val whitePoint: Float,
        val shadows: Float,
        val midtones: Float,
        val contrast: Float,
        val tempK: Float,
        val tint: Float,
        val saturation: Float,
        val asShot: Boolean,
        val wheelShadowX: Float,
        val wheelShadowY: Float,
        val wheelMidX: Float,
        val wheelMidY: Float,
        val wheelHighX: Float,
        val wheelHighY: Float,
        val splitShadowHue: Float,
        val splitHighlightHue: Float,
        val splitBalance: Float,
    )

    @Test
    fun covered_gpu_job_develops_every_frame_on_the_gpu_session() {
        // The dispatch's happy path: GPU_EXPERIMENTAL + a covered grade +
        // green probe/parity -> the GPU session renders EVERY frame, the
        // CPU session's renderFrame is never called, and the session is
        // opened and closed exactly once. The render call carries the
        // fragment-covered grade controls (the NativeSource signature
        // lock's Kotlin mirror) — highlights rides through at the stage-15
        // value, since the pointwise port the ten stages' raw values ride
        // too, and since the spatial-NR port the two stages-7/8 raw values
        // ride as well, and since the sharpness/detail port the two
        // stages-21/22 raw values ride too, so a multi-control grade
        // stays on GPU end to end.
        val session = FakeSession(frames = 2, audio = false)
        val writer = FakeWriter()
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            GradeK(
                exposureEv = 1.5f,
                highlights = -55f,
                chromaDenoise = 25f,
                noiseReduction = 40f,
                sharpness = 35f,
                detail = -15f,
                highlightReconstruction = false,
                keepVignetting = false,
                tonemap = 2,
                blackPoint = 5f,
                whitePoint = -5f,
                shadows = 55f,
                midtones = -55f,
                contrast = 100f,
                tempK = 6500f,
                tint = 30f,
                saturation = -40f,
                asShot = false,
                wheelShadowX = 0.25f,
                wheelShadowY = -0.25f,
                wheelMidX = 0.5f,
                wheelMidY = -0.5f,
                wheelHighX = 0.75f,
                wheelHighY = -0.75f,
                splitShadowHue = 30f,
                splitHighlightHue = 210f,
                splitBalance = -25f,
            ),
            "/tmp/out.partial",
        )
        assertEquals(1, gpu.probeCalls)
        assertEquals(listOf(7), gpu.parityFds) // the export's own fd, once
        assertEquals(1, gpu.openCalls)
        assertEquals(listOf(0, 1), gpu.rendered)
        assertTrue("the CPU develop must never run", session.rendered.isEmpty())
        assertEquals(1L, gpu.lastSessionHandle)
        assertEquals(1L, gpu.lastClipHandle) // FakeSession's handle
        assertEquals(1.5f, gpu.lastExposureEv, 0f)
        assertEquals(-55f, gpu.lastHighlights, 0f)
        assertFalse(gpu.lastHighlightReconstruction)
        assertFalse(gpu.lastKeepVignetting)
        assertEquals(2, gpu.lastTonemap)
        // The two spatial-NR stages ride the dispatch verbatim too — the
        // stages-7/8 raw values in the seam's parameter order.
        assertEquals(NrControls(chromaDenoise = 25f, noiseReduction = 40f), gpu.lastNr)
        // The temporal pair rides the dispatch verbatim since the hybrid —
        // this grade is temporal-OFF (the default), so the pair reads
        // false/0 here; the temporal-active grade's own pass-through lock
        // lives in the temporal_grade_rides_the_gpu_path test above.
        assertEquals(TemporalControls(temporalNr = false, temporalStrength = 0f), gpu.lastTemporal)
        // The two unsharp stages ride the dispatch verbatim too — the
        // stages-21/22 raw percents in the seam's parameter order, the
        // NEGATIVE detail carrying through unclamped (softening is valid).
        assertEquals(SharpnessDetailControls(sharpness = 35f, detail = -15f), gpu.lastSharpnessDetail)
        // The ten pointwise stages ride the dispatch verbatim, each raw
        // grade value in the established order — a swapped pair or a
        // dropped stage fails this equality with the field named.
        assertEquals(
            PointwiseControls(
                blackPoint = 5f, whitePoint = -5f,
                shadows = 55f, midtones = -55f, contrast = 100f,
                tempK = 6500f, tint = 30f, saturation = -40f,
                asShot = false,
                wheelShadowX = 0.25f, wheelShadowY = -0.25f,
                wheelMidX = 0.5f, wheelMidY = -0.5f,
                wheelHighX = 0.75f, wheelHighY = -0.75f,
                splitShadowHue = 30f, splitHighlightHue = 210f,
                splitBalance = -25f,
            ),
            gpu.lastPointwise,
        )
        // No LUT in this grade: the seven controls ride as the neutral
        // legacy shape (null path, -1 ordinals) — the strength carries
        // the grade's default 100 percent mapped to [0,1] (1.0, the
        // faithful pass-through; the native leg gates on the null path,
        // so a null-path render never samples the table) — the LUT
        // grade's own pass-through lives in the stage-24 test below.
        assertEquals(
            LutControls(
                lutPath = null, lutStrength = 1.0f,
                lutRole = -1, lutInGamut = -1, lutInTransfer = -1,
                lutOutGamut = -1, lutOutTransfer = -1,
            ),
            gpu.lastLut,
        )
        assertEquals(listOf(1L), gpu.closedHandles) // closed exactly once
        assertEquals(2, writer.videoFrames) // the packed tail consumed both
        // No develop fallback: the dispatch chose GLES and it held — the
        // multi-control grade is covered now, so it stays GPU.
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
    }

    @Test
    fun lut_grade_rides_the_gpu_when_the_texture_path_is_healthy() {
        // The stage-24 recipe: a LUT-active grade (lutId set, a percent
        // strength, a full managed interpretation) on GPU_EXPERIMENTAL
        // stays on the GPU when the probe says the 3D-texture path is
        // healthy — the runtime leg's fact comes from the probe itself
        // (contextOk && glesMajor >= 3, the FakeGpuDevelop default), so
        // the pre-coverage fetch is paid ONCE and the gate reuses it. The
        // seven LUT controls ride the dispatch verbatim: the resolved
        // .cube path, the percent strength mapped to [0,1] (85 -> 0.85),
        // and the five interpretation ordinals in the LutInterpK order
        // (role, input gamut, input transfer, output gamut, output
        // transfer) — a swapped pair or a dropped ordinal fails the
        // LutControls equality with the field named.
        val session = FakeSession(frames = 2, audio = false)
        val writer = FakeWriter()
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            GradeK(
                lutId = "milog",
                lutStrength = 85f,
                lutInterp = LutInterpK(
                    input = LutEncodingK(
                        gamut = LutGamut.VGamut,
                        transfer = LutTransfer.VLog,
                    ),
                    output = LutEncodingK(
                        gamut = LutGamut.Rec709,
                        transfer = LutTransfer.Rec709OETF,
                    ),
                    role = LutRole.SceneToDisplay,
                ),
            ),
            "/tmp/out.partial",
            lutPath = "/data/luts/milog.cube",
        )
        // The probe is paid exactly once: the pre-coverage fetch for the
        // LUT fact IS the gate's probe (reused, not re-asked).
        assertEquals(1, gpu.probeCalls)
        assertEquals(listOf(7), gpu.parityFds)
        assertEquals(1, gpu.openCalls)
        assertEquals(listOf(0, 1), gpu.rendered)
        assertTrue("the CPU develop must never run", session.rendered.isEmpty())
        // The seven controls, verbatim: the path the job resolved, the
        // percent mapped to [0,1], and the five ordinals from the managed
        // interpretation's enums (VGamut=8, VLog=10, Rec709=0,
        // Rec709OETF=2, SceneToDisplay=3).
        assertEquals(
            LutControls(
                lutPath = "/data/luts/milog.cube",
                lutStrength = 0.85f,
                lutRole = 3,
                lutInGamut = 8,
                lutInTransfer = 10,
                lutOutGamut = 0,
                lutOutTransfer = 2,
            ),
            gpu.lastLut,
        )
        // No fallback: the runtime leg's fact said healthy, so the lut
        // token never appears and the grade stays GPU end to end.
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
    }

    @Test
    fun lut_grade_falls_back_with_the_lut_token_when_the_texture_path_is_unhealthy() {
        // The runtime leg's honest other side: the same LUT-active grade
        // on a GLES-2 context (glesMajor < 3, no 3D-texture path) takes
        // the CPU path WHOLESALE with the lut token in the fallback line
        // — never a silent drop of the look. The probe is still paid
        // exactly once (the pre-coverage fetch), and the parity run is
        // skipped: coverage already said no.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(probeArray = doubleArrayOf(1.0, 2.0, 0.0, 1.0, 1.0, 4096.0))
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            GradeK(lutId = "milog", lutStrength = 85f),
            "/tmp/out.partial",
            lutPath = "/data/luts/milog.cube",
        )
        assertEquals(1, gpu.probeCalls)
        assertTrue("parity must be skipped", gpu.parityFds.isEmpty())
        assertEquals(0, gpu.openCalls)
        assertEquals(listOf(0, 1), session.rendered)
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("lut token missing: $fallback", fallback.contains("lut"))
    }

    @Test
    fun gpu_develop_bytes_feed_the_same_rotate_crop_surface_tail() {
        // The byte-shape contract: the GPU session's RGB888 output flows
        // through the SAME tail — here the encoder-Surface offer carries
        // the GPU's bytes (marker 77), not the CPU session's (128).
        val session = FakeSession(frames = 2, width = 8, height = 4, audio = false)
        val writer = SurfaceWriter()
        val gpu = FakeGpuDevelop(frameBytes = ByteArray(8 * 4 * 3) { 77.toByte() })
        ExportPipeline(
            session, writer,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(2, writer.rgbFrames.size)
        assertEquals(8 * 4 * 3, writer.rgbFrames[0].second.size)
        assertTrue(
            "the offered bytes must be the GPU session's",
            writer.rgbFrames[0].second.all { it == 77.toByte() },
        )
        assertTrue(session.rendered.isEmpty())
    }

    @Test
    fun temporal_grade_rides_the_gpu_path_since_the_hybrid() {
        // The temporal hybrid's dispatch lock: a temporal-NR grade rides
        // GLES like any other covered grade — the bridge's own
        // temporalMergedCfa runs the merge before the session ever sees
        // the CFA (routing, not porting; the merge math stays the frozen
        // CPU's), so the coverage vocabulary contributes NO temporal
        // token anymore. The probe is paid ONCE (the grade is eligible —
        // the never-probe shape died with the uncovered pre-stage), the
        // renders run on the GPU session, the readback threads the grade
        // pair verbatim (the pass-through lock), no fallback line ever
        // names a temporal, and the visibility line reads effective=GLES
        // unsupported=none — the hybrid's honest headline.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(temporalNr = true, temporalStrength = 5f), "/tmp/out.partial")
        assertEquals(1, gpu.probeCalls)
        assertEquals(1, gpu.openCalls)
        assertEquals(listOf(0, 1), gpu.rendered)
        assertTrue(session.rendered.isEmpty())
        // The pass-through lock: the grade's temporal pair threads
        // VERBATIM into the readback render (the dispatch never rewrites
        // it — the bridge consumes the pair to produce the merged CFA).
        assertEquals(TemporalControls(temporalNr = true, temporalStrength = 5f), gpu.lastTemporal)
        // The bytes writer never runs the encoder blit (FakeWriter's
        // encoderInputSurface is the null default), so the encoder leg's
        // pair stays unset here — the session_encoder_temporal test
        // below owns that leg.
        assertNull(gpu.lastEncoderTemporal)
        // No fallback line: the grade is covered, the job rode GLES.
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("effective must be GLES: $backend", backend.contains("effective=GLES"))
        assertTrue("unsupported must be none: $backend", backend.contains("unsupported=none"))
        assertFalse(
            "temporal token must be gone: $backend",
            backend.contains("temporal-nr"),
        )
    }

    @Test
    fun gyro_warp_rides_the_gpu_with_a_verdict_per_frame() {
        // The gyro-warp port's seam leg: the warp input now threads BOTH
        // render seams, so the EXPORT DISPATCH lock is GONE — a stabilized
        // grade with a log attached rides GLES like any other covered
        // grade. The dispatch resolves the plan PER FRAME (warpResolved
        // records one entry per frame) and threads the verdict verbatim
        // into the render: this run's resolver returns null (no verdict —
        // the honest no-log fold), so the render carries warpActive=false
        // + the identity M, and the session SKIPS draw 10 entirely (the
        // identity no-draw rule). No gyro-warp token anywhere.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(stabilization = true), "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        assertEquals(1, gpu.probeCalls)
        assertEquals(1, gpu.openCalls)
        assertEquals(listOf(0, 1), gpu.rendered)
        // The per-frame resolution lock: one resolve per frame, at the
        // frame loop's own indices, maxDim=0 (the export's full res —
        // the applyStabilization convention).
        assertEquals(listOf(0, 1), gpu.warpResolved)
        // The INACTIVE pass-through lock: null verdict → the dispatcher's
        // fold (warpActive=false + identity M), threaded verbatim.
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(WarpControls(false, identity), gpu.lastWarp)
        // The bytes writer never runs the encoder blit (FakeWriter's
        // encoderInputSurface is the null default), so the encoder leg's
        // pair stays unset here — the session_encoder_warp test below
        // owns that leg.
        assertNull(gpu.lastEncoderWarp)
        // No fallback line: the grade is covered, the job rode GLES.
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("effective must be GLES: $backend", backend.contains("effective=GLES"))
        assertFalse(
            "gyro-warp token must be gone: $backend",
            backend.contains("gyro-warp"),
        )
    }

    @Test
    fun gyro_warp_active_verdict_threads_verbatim_into_the_readback_render() {
        // The ACTIVE case's pass-through lock, readback leg: the resolver's
        // verdict (active + a non-identity M) threads VERBATIM into the
        // readback renderFrameParcel — the dispatcher never rewrites, clamps, or
        // fabricates the map the resolver produced. The encoder blit's leg
        // is the session_encoder_warp test below.
        val m = floatArrayOf(
            0.5f, 0.25f, -1.5f,
            2.5f, 0.75f, 3.25f,
            -0.5f, 1.5f, 0.9f,
        )
        val gpu = FakeGpuDevelop(
            warpArray = floatArrayOf(1.0f) + m,
        )
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false), FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(stabilization = true), "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        assertEquals(listOf(0, 1), gpu.warpResolved)
        assertEquals(WarpControls(true, m), gpu.lastWarp)
        // The bytes writer never runs the encoder blit — the
        // session_encoder_warp test below owns that leg.
        assertNull(gpu.lastEncoderWarp)
    }

    @Test
    fun image_engine_grade_with_the_flag_off_exports_unstabilized_on_both_paths() {
        // S4 A/B lock: IMAGE_STABILIZATION_ENABLED is false, so a grade that
        // selects the image engine must behave exactly like an unstabilized
        // one — no gyro resolve (even with a log attached: an image grade
        // never falls back to gyro), no image resolve, the identity verdict
        // on the GPU path, and a null stabilization slot on the CPU path.
        val image = GradeK(stabilization = true, stabilizationEngine = 1)
        val gpu = FakeGpuDevelop()
        ExportPipeline(
            FakeSession(frames = 2, audio = false), FakeWriter(), null,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, image, "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        assertTrue(gpu.warpResolved.isEmpty())
        assertTrue(gpu.imageResolved.isEmpty())
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(WarpControls(false, identity), gpu.lastWarp)

        val cpu = FakeSession(frames = 2, audio = false)
        ExportPipeline(cpu, FakeWriter(), null)
            .run(7, image, "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        assertEquals(listOf<String?>(null, null), cpu.renderGyroPaths)
        // while dark the same grade exports fine (no hidden failure)
        assertEquals(listOf(0, 1), cpu.rendered)
    }

    @Test
    fun gyro_grade_keeps_threading_its_path_to_the_cpu_renders() {
        // The pre-S4 behavior, locked: the exporter handed the attached
        // path straight to every CPU render (the bridge folds it).
        val cpu = FakeSession(frames = 2, audio = false)
        ExportPipeline(cpu, FakeWriter(), null)
            .run(7, GradeK(stabilization = true), "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        assertEquals(listOf<String?>("/tmp/gyro.log", "/tmp/gyro.log"), cpu.renderGyroPaths)
    }

    @Test
    fun warp_resolver_unavailable_folds_to_inactive_never_throws() {
        // The no-.so mid-export fold: resolveWarp throwing the unavailable
        // IllegalStateException (the JVM seam's own guard) is CAUGHT by the
        // dispatch and folded to the inactive verdict — the export never
        // dies on the resolver, and the renders still carry the honest
        // warpActive=false + identity M pair.
        val gpu = FakeGpuDevelop(failWarpResolveWithIllegalState = true)
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false), FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(stabilization = true), "/tmp/out.partial", gyroPath = "/tmp/gyro.log")
        assertTrue(gpu.warpResolved.isEmpty())
        assertEquals(listOf(0, 1), gpu.rendered)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        assertEquals(WarpControls(false, identity), gpu.lastWarp)
        // The bytes writer never runs the encoder blit — the
        // session_encoder_warp test below owns that leg.
        assertNull(gpu.lastEncoderWarp)
    }

    @Test
    fun session_encoder_warp_threads_the_verdict_into_the_blit() {
        // The warp pair's ENCODER leg: the session-owns-EGL path (the M5
        // fast path — the session develops AND blits straight to the codec
        // input surface, the readback renderFrameParcel NEVER runs) threads the
        // SAME per-frame verdict into renderFrameToEncoderParcel, verbatim,
        // before rotationDeg (the LUT precedent keeps rotationDeg the
        // tail parameter). The bytes-writer tests above own the readback
        // leg; this one owns the blit.
        val m = floatArrayOf(
            0.5f, 0.25f, -1.5f,
            2.5f, 0.75f, 3.25f,
            -0.5f, 1.5f, 0.9f,
        )
        val gpu = FakeGpuDevelop(
            warpArray = floatArrayOf(1.0f) + m,
        )
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        // The runSessionEncoderExport geometry recipe: the container's 90
        // composed with the manual 270 is an effective 0 on the 4x4
        // square, so the blit is 1:1 pixels and the job is
        // encoderDrawEligible — plus the stabilized grade and the gyro
        // log the warp port threads.
        ExportPipeline(
            FakeSession(frames = 2, audio = false), writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            GradeK(stabilization = true),
            "/tmp/out.partial",
            manualRotationDeg = 270,
            gyroPath = "/tmp/gyro.log",
        )
        // One resolve per frame, before each blit.
        assertEquals(listOf(0, 1), gpu.warpResolved)
        // The blit ran per frame; the readback never did.
        assertEquals(listOf(0, 1), gpu.encoderRendered)
        assertTrue("the readback renderFrameParcel must never run", gpu.rendered.isEmpty())
        // The ACTIVE verdict threads verbatim into the blit's pair.
        assertEquals(WarpControls(true, m), gpu.lastEncoderWarp)
        assertNull(gpu.lastWarp)
        assertEquals(2, writer.drainCalls)
        assertEquals(0, lines.count { it.startsWith("encoder surface fallback: ") })
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
    }

    @Test
    fun session_encoder_temporal_threads_the_grade_pair_into_the_blit() {
        // The temporal pair's ENCODER leg: the session-owns-EGL path (the
        // M5 fast path — the session develops AND blits straight to the
        // codec input surface, the readback renderFrameParcel NEVER runs)
        // threads the grade's temporal pair into renderFrameToEncoderParcel
        // VERBATIM since the hybrid — the bridge's own temporalMergedCfa
        // consumes the pair to produce the CFA the blit uploads (the
        // SAME merge the CPU path rides, the same per-Entry caches), so
        // a temporal-active grade rides the fast path like any other
        // covered grade. The bytes-writer test above owns the readback
        // leg; this one owns the blit.
        val gpu = FakeGpuDevelop()
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        // The runSessionEncoderExport geometry recipe (the effective 0°
        // that makes the job encoderDrawEligible) plus the temporal-active
        // grade the hybrid threads.
        ExportPipeline(
            FakeSession(frames = 2, audio = false), writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            GradeK(temporalNr = true, temporalStrength = 5f),
            "/tmp/out.partial",
            manualRotationDeg = 270,
        )
        // The blit ran per frame; the readback never did.
        assertEquals(listOf(0, 1), gpu.encoderRendered)
        assertTrue("the readback renderFrameParcel must never run", gpu.rendered.isEmpty())
        // The grade's temporal pair threads VERBATIM into the blit —
        // the dispatch never rewrites it (the bridge consumes the pair
        // to produce the merged CFA the blit uploads).
        assertEquals(TemporalControls(temporalNr = true, temporalStrength = 5f), gpu.lastEncoderTemporal)
        assertNull(gpu.lastTemporal)
        // No fallback line: the temporal grade is covered, the job rode
        // GLES end to end through the blit.
        assertEquals(0, lines.count { it.startsWith("encoder surface fallback: ") })
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("effective must be GLES: $backend", backend.contains("effective=GLES"))
        assertTrue("unsupported must be none: $backend", backend.contains("unsupported=none"))
    }

    @Test
    fun auto_stays_cpu_with_the_policy_line() {
        // AUTO never touches the seam (an honest policy, not a gate
        // measurement): the CPU develop runs and the line names it.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.AUTO,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(0, gpu.probeCalls)
        assertEquals(0, gpu.openCalls)
        assertEquals(listOf(0, 1), session.rendered)
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("requested missing: $fallback", fallback.contains("requested=AUTO"))
        assertTrue("policy missing: $fallback", fallback.contains("reason=auto-stays-cpu"))
    }

    @Test
    fun cpu_job_never_touches_the_gpu_seam() {
        // The reference path is byte-identical: a CPU job makes NO seam
        // call at all and logs NO develop fallback (nothing fell back).
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.CPU,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(0, gpu.probeCalls)
        assertEquals(0, gpu.openCalls)
        assertTrue(gpu.rendered.isEmpty())
        assertTrue(gpu.closedHandles.isEmpty())
        assertEquals(listOf(0, 1), session.rendered)
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
    }

    @Test
    fun parity_fail_stays_cpu_with_the_gate_token() {
        // A parity run that FAILS (maxDiffLsb beyond the 1-LSB tolerance)
        // keeps the export on the CPU develop with the gate token — the
        // session is never opened.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(parityArray = doubleArrayOf(1.0, 2.0, 398.0, 24_800_000.0))
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, gpu.probeCalls)
        assertEquals(listOf(7), gpu.parityFds)
        assertEquals(0, gpu.openCalls)
        assertEquals(listOf(0, 1), session.rendered)
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("gate token missing: $fallback", fallback.contains("parity-not-verified"))
        // The parity-fix observability: the verdict's own detail rides
        // BESIDE the fallback line as a companion line — the thresholds'
        // numbers for a run that failed them (the token vocabulary stays
        // byte-identical).
        val detail = lines.first { it.startsWith("parity gate: ") }
        assertEquals(
            "parity gate: FAIL (maxDiffLsb 2 > 1; " +
                "ran=1 maxDiffLsb=2 mismatch=398 samples=24800000)",
            detail,
        )
    }

    @Test
    fun parity_not_run_logs_the_gl_step_that_refused() {
        // The parity-fix observability, the NOT_RUN leg: a run that refused
        // (an EGL/compile/link/fbo step failed) reports the harness's own
        // error detail (nativeLastError) — exactly what classifies the
        // parity-not-verified latch on the owner's device.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(
            parityArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
            detail = "link: develop",
        )
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("gate token missing: $fallback", fallback.contains("parity-not-verified"))
        assertEquals(
            "parity gate: NOT_RUN (link: develop)",
            lines.first { it.startsWith("parity gate: ") },
        )
    }

    @Test
    fun parity_jni_failure_logs_the_seam_error() {
        // The parity-fix observability, the null-array leg: a seam that
        // returned no array (the clip-level failure: open/decode) reports
        // the "jni failure" vocabulary with the seam's own detail.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(
            parityArray = null,
            detail = "gles fragment parity: IoError — frame 0 decode",
        )
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(
            "parity gate: jni failure (gles fragment parity: IoError — frame 0 decode)",
            lines.first { it.startsWith("parity gate: ") },
        )
    }

    @Test
    fun parity_pass_logs_no_companion_line() {
        // A green parity verdict logs NO companion line — the export rides
        // GLES and the fallback line is absent, so nothing rides beside
        // nothing.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(0, lines.count { it.startsWith("parity gate: ") })
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
    }

    @Test
    fun red_probe_skips_the_parity_run() {
        // A probe whose context gate is red never pays the parity run (it
        // could only fail predictably); the line names the missing gates.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(probeArray = doubleArrayOf(0.0, 3.0, 2.0, 1.0, 1.0, 4096.0))
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, gpu.probeCalls)
        assertTrue("parity must be skipped", gpu.parityFds.isEmpty())
        assertEquals(0, gpu.openCalls)
        assertEquals(listOf(0, 1), session.rendered)
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("context token missing: $fallback", fallback.contains("no-egl-context"))
    }

    @Test
    fun open_failure_continues_on_cpu_with_the_session_detail() {
        // Open returning 0 is NOT an export failure: the CPU develop
        // continues, ONE fallback line carries the session's own detail,
        // and close is never called (no session was opened).
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(
            openHandle = 0L,
            detail = "develop session: egl context (recovery: the CPU develop path stays the default)",
        )
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(1, gpu.openCalls)
        assertTrue(gpu.closedHandles.isEmpty())
        assertEquals(listOf(0, 1), session.rendered)
        assertEquals(2, writer.videoFrames)
        assertEquals(1, lines.count { it.startsWith("render backend fallback: ") })
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("detail missing: $fallback", fallback.contains("develop session:"))
    }

    @Test
    fun mid_render_failure_latches_cpu_for_the_rest() {
        // A mid-render GPU failure latches the CPU develop for the REST of
        // the export with ONE fallback line — frame 0 is GPU, frame 1 is
        // CPU, and the session closes exactly once.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(
            failRenderFrom = 1,
            detail = "develop session render: draw failed (recovery: the export falls back to the CPU develop)",
        )
        val writer = FakeWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(listOf(0), gpu.rendered)
        assertEquals(listOf(1), session.rendered)
        assertEquals(2, writer.videoFrames)
        assertEquals(1, lines.count { it.startsWith("render backend fallback: ") })
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("detail missing: $fallback", fallback.contains("develop session render:"))
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun wrong_sized_gpu_output_latches_cpu() {
        // A wrong-shaped buffer is a GPU failure, never a corrupt frame:
        // the size guard latches the CPU develop even though the render
        // "succeeded".
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(frameBytes = ByteArray(3))
        val lines = ArrayList<String>()
        ExportPipeline(
            session, FakeWriter(), lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial")
        assertEquals(listOf(0), gpu.rendered) // the fake did return bytes
        assertEquals(listOf(0, 1), session.rendered) // both frames on CPU
        assertEquals(1, lines.count { it.startsWith("render backend fallback: ") })
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun gpu_session_closes_when_the_encode_fails() {
        // The finally-path lock: a mid-encode failure (here the CPU
        // develop's own frame failure after the GPU render refused) still
        // closes the GPU session and releases the writer — the session
        // never outlives the export, on ANY path.
        val session = FakeSession(frames = 2, audio = false, failAtFrame = 0)
        val gpu = FakeGpuDevelop(failRenderFrom = 0)
        val writer = FakeWriter()
        var thrown: IllegalStateException? = null
        try {
            ExportPipeline(
                session, writer,
                renderBackend = RenderBackend.GPU_EXPERIMENTAL,
                gpuDevelop = gpu,
            ).run(7, GradeK(), "/tmp/out.partial")
        } catch (expected: IllegalStateException) {
            thrown = expected
        }
        assertNotNull(thrown)
        assertEquals(1, gpu.openCalls)
        assertEquals(listOf(1L), gpu.closedHandles)
        assertTrue(writer.released)
    }

    // ---- GPU develop draws to the encoder surface (M5 commit a) -----------

    /**
     * The geometry recipe that makes a FakeSession job encoderDrawEligible:
     * the container says 90°, a 270° manual rotation composes to an
     * effective 0° (upright pixels, no dim swap on the 4x4 square), the
     * default grade's crop is full, straighten is 0 and no resolution
     * bound rides the job — so the develop output, rotated 0°, IS the
     * encode input and the session blit is 1:1 pixels.
     */
    private fun runSessionEncoderExport(
        gpu: FakeGpuDevelop,
        writer: Mp4MediaWriter,
        lines: MutableList<String>,
    ) {
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            writer,
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            // The grade carries the two spatial-NR stages' raw values and
            // the two unsharp stages' raw percents too — the blit reuses
            // the SAME develop program family (the four-pass unsharp chain
            // included), so the encoder pass-through lock must see them
            // verbatim.
            GradeK(chromaDenoise = 25f, noiseReduction = 40f, sharpness = 45f, detail = 20f),
            "/tmp/out.partial",
            manualRotationDeg = 270,
        )
    }

    @Test
    fun session_encoder_path_blits_every_frame_and_skips_the_readback() {
        // The M5 happy path: an encoderDrawEligible job requests the
        // session-owns-EGL mode ONCE (never the writer's own Surface
        // request), the session attaches the writer's unwrapped input
        // surface, EVERY frame blits through renderFrameToEncoderParcel with
        // the frame's normalized pts, the writer drains per frame — and
        // the readback renderFrameParcel NEVER runs (no RGB bytes cross the
        // seam). The finally's visibility line says surface-gpu.
        val gpu = FakeGpuDevelop()
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        runSessionEncoderExport(gpu, writer, lines)
        // The request went to the SESSION mode, before start, once.
        assertEquals(listOf("requestSessionEncoderSurface"), writer.sessionRequests)
        // The attach: exactly one setEncoderSurface with the writer's
        // unwrapped input surface object.
        assertEquals(1, gpu.encoderSurfaces.size)
        assertEquals(1L, gpu.encoderSurfaces[0].first)
        assertTrue("the writer's input surface must reach the session", gpu.encoderSurfaces[0].second === writer.inputSurface)
        // Every frame blitted; the readback never ran.
        assertEquals(listOf(0, 1), gpu.encoderRendered)
        assertTrue("the readback renderFrameParcel must never run", gpu.rendered.isEmpty())
        // The pts the session stamps is the frame's normalized media time —
        // frame 1's 33_333 (the FakeSession's ptsStepUs), the LAST blit.
        assertEquals(33_333L, gpu.lastEncoderPtsUs)
        // The rotation the dispatch carried: the job's EFFECTIVE 0° (the
        // container's 90 composed with the manual 270) — the fold's 0 arm,
        // the byte-identical upright copy.
        assertEquals(0, gpu.lastEncoderRotationDeg)
        // The two spatial-NR stages ride the blit verbatim too — the
        // stages-7/8 raw values in the seam's parameter order.
        assertEquals(NrControls(chromaDenoise = 25f, noiseReduction = 40f), gpu.lastEncoderNr)
        // The temporal pair rides the blit verbatim since the hybrid —
        // this grade is temporal-OFF (the default), so the pair reads
        // false/0 here; the temporal-active grade's own blit pass-through
        // lock lives in the session_encoder_temporal test.
        assertEquals(TemporalControls(temporalNr = false, temporalStrength = 0f), gpu.lastEncoderTemporal)
        // The two unsharp stages ride the blit verbatim too — the
        // stages-21/22 raw percents in the seam's parameter order.
        assertEquals(SharpnessDetailControls(sharpness = 45f, detail = 20f), gpu.lastEncoderSharpnessDetail)
        // No LUT in this grade: the blit's seven LUT controls ride as the
        // neutral legacy shape (null path, -1 ordinals; the strength is
        // the default 100 percent mapped to [0,1] — the faithful
        // pass-through, inert while the path is null).
        assertEquals(
            LutControls(
                lutPath = null, lutStrength = 1.0f,
                lutRole = -1, lutInGamut = -1, lutInTransfer = -1,
                lutOutGamut = -1, lutOutTransfer = -1,
            ),
            gpu.lastEncoderLut,
        )
        // The writer drained once per blit.
        assertEquals(2, writer.drainCalls)
        // No frames on the writer's own paths.
        assertEquals(0, writer.rgbFrames)
        assertEquals(0, writer.packedFrames)
        // ONE fallback line total: none — the fast path held.
        assertEquals(0, lines.count { it.startsWith("encoder surface fallback: ") })
        assertEquals(0, lines.count { it.startsWith("render backend fallback: ") })
        // The visibility line.
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must say surface-gpu: $backend", backend.contains("encoder-input=surface-gpu"))
        // The writer's own axis line stays honest: it never took frames.
        assertTrue(lines.any { it == "encoder input: bytes" })
        // The session closed exactly once.
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun session_refusal_adopts_the_surface_into_the_writer() {
        // The session refused the attach (setEncoderSurface false): the
        // writer ADOPTS the input surface into its own EGL, frames flow
        // through the EXISTING writeVideoFrameRgb offer (the CPU-RGB
        // Surface mode, byte-for-byte the shipped path), and ONE honest
        // fallback line names the session's reason. The export lives.
        val gpu = FakeGpuDevelop(
            refuseEncoderSurface = true,
            encoderDetail = "develop session encoder surface: window attach (recovery: the writer's own surface mode)",
        )
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        runSessionEncoderExport(gpu, writer, lines)
        assertEquals(1, writer.adoptCalls.size)
        // The develop itself stayed on the GPU session (the readback path).
        assertEquals(listOf(0, 1), gpu.rendered)
        assertEquals(2, writer.rgbFrames)
        assertEquals(0, writer.packedFrames)
        assertEquals(0, writer.drainCalls)
        assertEquals(1, lines.count { it.startsWith("encoder surface fallback: ") })
        val fallback = lines.first { it.startsWith("encoder surface fallback: ") }
        assertTrue("requested missing: $fallback", fallback.contains("requested=surface-gpu"))
        assertTrue("effective must be surface: $fallback", fallback.contains("effective=surface"))
        assertTrue("session detail missing: $fallback", fallback.contains("develop session encoder surface:"))
        assertTrue("adopt must be named: $fallback", fallback.contains("writer adopted the input surface"))
        // The visibility line: the writer's own EGL took the frames.
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface: $backend", backend.contains("encoder-input=surface"))
        assertFalse(backend.contains("encoder-input=surface-gpu"))
        assertTrue(lines.any { it == "encoder input: surface" })
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun mid_stream_encoder_render_refusal_detaches_then_adopts() {
        // The mid-stream refusal cascade: frame 0 blits, frame 1's
        // renderFrameToEncoderParcel refuses (a mid-render GL failure latches
        // the session unhealthy natively — the GPU readback dies with
        // it). The pipeline DETACHES the session's surface first (a
        // Surface job always processes, even latched — the
        // single-producer rule), then the writer ADOPTS, and the CPU
        // develop takes the rest — ONE fallback line on the encoder
        // axis, ONE on the develop axis, both honest.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(
            failEncoderRenderFrom = 1,
            encoderDetail = "develop session encoder render: blit failed (recovery: the export falls back to the CPU develop)",
        )
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            session, writer, lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial", manualRotationDeg = 270)
        // Frame 0 blitted; frame 1 refused.
        assertEquals(listOf(0), gpu.encoderRendered)
        // The detach: the LAST setEncoderSurface call is the null detach.
        assertEquals(2, gpu.encoderSurfaces.size)
        assertEquals(1L, gpu.encoderSurfaces[1].first)
        assertNull("the detach must pass null", gpu.encoderSurfaces[1].second)
        // The writer adopted once.
        assertEquals(1, writer.adoptCalls.size)
        // Frame 0 drained; the latched readback refused frame 1, so the
        // CPU develop took it and the writer's own offer consumed it.
        assertEquals(1, writer.drainCalls)
        assertTrue("the latched readback must refuse", gpu.rendered.isEmpty())
        assertEquals(listOf(1), session.rendered)
        assertEquals(1, writer.rgbFrames)
        assertEquals(0, writer.packedFrames)
        // The encoder-axis fallback line: exactly one, naming the render
        // failure and the adopt.
        assertEquals(1, lines.count { it.startsWith("encoder surface fallback: ") })
        val fallback = lines.first { it.startsWith("encoder surface fallback: ") }
        assertTrue("requested missing: $fallback", fallback.contains("requested=surface-gpu"))
        assertTrue("effective must be surface: $fallback", fallback.contains("effective=surface"))
        assertTrue("render detail missing: $fallback", fallback.contains("develop session encoder render:"))
        assertTrue(
            "the post-failure adopt must be named: $fallback",
            fallback.contains("writer adopted the input surface after the encoder render failure"),
        )
        // The develop-axis fallback line: the CPU develop took over. The
        // fake's lastDetail() is the encoderDetail (the native latch names
        // the encoder render failure — the same detail the encoder-axis
        // line rode), NOT the generic readback string.
        assertEquals(1, lines.count { it.startsWith("render backend fallback: ") })
        val developFallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue("develop detail missing: $developFallback", developFallback.contains("develop session encoder render:"))
        // The visibility line: the writer's own EGL took the frames.
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface: $backend", backend.contains("encoder-input=surface"))
        assertFalse(backend.contains("encoder-input=surface-gpu"))
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun session_never_opened_adopts_for_the_gap_case() {
        // The gap case: the geometry gate passed and the request went to
        // the writer, but the GPU develop session never opened
        // (openSession returned 0 — the honest CPU-continue line already
        // named it). The writer holds a Surface-configured codec the byte
        // path CANNOT feed, so the pipeline ADOPTS the surface into the
        // writer's own EGL — the frames flow through writeVideoFrameRgb
        // (the CPU-RGB Surface mode), ONE line names the gap.
        val gpu = FakeGpuDevelop(
            openHandle = 0L,
            detail = "develop session: egl context (recovery: the CPU develop path stays the default)",
        )
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        runSessionEncoderExport(gpu, writer, lines)
        assertEquals(1, writer.adoptCalls.size)
        assertEquals(0, gpu.encoderSurfaces.size) // no session, no attach
        // Both frames on the CPU develop, through the writer's own offer.
        assertEquals(2, writer.rgbFrames)
        assertEquals(0, writer.packedFrames)
        assertEquals(1, lines.count { it.startsWith("encoder surface fallback: ") })
        val fallback = lines.first { it.startsWith("encoder surface fallback: ") }
        assertTrue("requested missing: $fallback", fallback.contains("requested=surface-gpu"))
        assertTrue("effective must be surface: $fallback", fallback.contains("effective=surface"))
        assertTrue(
            "the gap reason must be named: $fallback",
            fallback.contains("reason=develop-session-unavailable"),
        )
        // The develop-axis line named the session open failure.
        assertEquals(1, lines.count { it.startsWith("render backend fallback: ") })
        val developFallback = lines.first { it.startsWith("render backend fallback: ") }
        assertTrue(developFallback.contains("develop session:"))
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface: $backend", backend.contains("encoder-input=surface"))
        assertTrue(lines.any { it == "encoder input: surface" })
        assertTrue(gpu.closedHandles.isEmpty()) // nothing was opened
    }

    @Test
    fun writer_without_the_input_surface_stays_bytes() {
        // The writer took the session request but could not produce the
        // codec input surface (its start already logged/throws per its
        // contract): the pipeline falls back to the writer's own modes
        // with ONE line — here a plain writer whose per-frame offer
        // refuses, so the packed I420 path runs, byte-identical.
        val gpu = FakeGpuDevelop()
        val writer = object : Mp4MediaWriter {
            var packed = 0
            override fun requestSessionEncoderSurface() {}
            override fun encoderInputSurface(): Any? = null
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
        val lines = ArrayList<String>()
        runSessionEncoderExport(gpu, writer, lines)
        assertEquals(0, gpu.encoderSurfaces.size) // no surface to attach
        assertEquals(2, writer.packed)
        assertEquals(listOf(0, 1), gpu.rendered) // the readback developed
        assertEquals(1, lines.count { it.startsWith("encoder surface fallback: ") })
        val fallback = lines.first { it.startsWith("encoder surface fallback: ") }
        assertTrue("requested missing: $fallback", fallback.contains("requested=surface-gpu"))
        assertTrue("effective must be bytes: $fallback", fallback.contains("effective=bytes"))
        assertTrue(
            "the reason must be named: $fallback",
            fallback.contains("reason=writer-had-no-input-surface"),
        )
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be bytes: $backend", backend.contains("encoder-input=bytes"))
        assertTrue(lines.any { it == "encoder input: bytes" })
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun quarter_turn_swapped_dims_job_blits_through_the_session_with_rotation() {
        // The surface-gpu rotation deliverable's ELIGIBLE case: a VERTICAL
        // clip (8x4 source, container 90° -> effective 90°, outDims 4x8)
        // with no crop/straighten/scale has encodeDims == the ROTATED
        // source dims, so the job IS encoderDrawEligible — the request
        // goes to the session mode, every frame blits through
        // renderFrameToEncoderParcel, and the render call CARRIES the effective
        // 90 (the uRotation fold's quarter-turn arm; the session swaps
        // the destination viewport to the source's swapped dims). The
        // UV math itself is device-proven (the owner's vertical clip);
        // this locks the PLUMBING: the gate admits a swapped-dims quarter
        // turn and the dispatch passes the rotation.
        val gpu = FakeGpuDevelop()
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, width = 8, height = 4, audio = false),
            writer,
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial") // manualRotationDeg 0: 90° effective
        // The request went to the SESSION mode (the rotated gate passed).
        assertEquals(listOf("requestSessionEncoderSurface"), writer.sessionRequests)
        assertEquals(1, gpu.encoderSurfaces.size)
        // Every frame blitted; the readback never ran.
        assertEquals(listOf(0, 1), gpu.encoderRendered)
        assertTrue(gpu.rendered.isEmpty())
        // The rotation the dispatch carried is the EFFECTIVE 90.
        assertEquals(90, gpu.lastEncoderRotationDeg)
        assertEquals(2, writer.drainCalls)
        assertEquals(0, writer.rgbFrames)
        assertEquals(0, writer.packedFrames)
        assertEquals(0, lines.count { it.startsWith("encoder surface fallback: ") })
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must say surface-gpu: $backend", backend.contains("encoder-input=surface-gpu"))
        assertEquals(listOf(1L), gpu.closedHandles)
    }

    @Test
    fun non_quarter_turn_job_takes_the_writers_own_surface_mode_not_the_sessions() {
        // The geometry gate is honest: a 45° job (effective 45° — the
        // pixels must be rotated by a non-90 angle before the encoder
        // sees them, which the blit's fold cannot express) is NOT
        // encoderDrawEligible, so the request goes to the writer's OWN
        // Surface mode (the shipped path), never the session's. (The
        // pre-rotation test used the square's effective 90° here; a
        // square's rotated dims equal its unswapped dims, so that case
        // became ELIGIBLE when the gate learned rotation — this 45° case
        // is the honest not-eligible geometry now.)
        val gpu = FakeGpuDevelop()
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, audio = false),
            writer,
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(), "/tmp/out.partial", manualRotationDeg = -45) // 90-45: 45° effective
        assertEquals(listOf("requestEncoderSurfaceInput"), writer.sessionRequests)
        assertEquals(0, gpu.encoderSurfaces.size)
        // The GPU readback developed the frames; the writer's own offer
        // took them (its writeVideoFrameRgb).
        assertEquals(listOf(0, 1), gpu.rendered)
        assertEquals(2, writer.rgbFrames)
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface (the writer's own): $backend", backend.contains("encoder-input=surface"))
        assertFalse(backend.contains("encoder-input=surface-gpu"))
    }

    @Test
    fun quarter_turn_with_non_swapped_dims_takes_the_writers_own_surface_mode() {
        // The gate's dims half: an effective 90° job whose encode dims are
        // NOT the rotated source dims (a resolution bound rides the job —
        // the encode is scaled, so the blit is no longer 1:1 pixels) is
        // NOT encoderDrawEligible. The 8x4 source's rotated dims are 4x8;
        // a 2x2 bound resolves to 2x2 (fits-within, aspect preserved,
        // even-snapped) — not 4x8 — so the request goes to the writer's
        // OWN Surface mode and the readback path with its aspectFit step
        // takes the frames. (frameBytes must match the 8x4 session — the
        // readback's size guard latches CPU on a wrong-shaped buffer.)
        val gpu = FakeGpuDevelop(frameBytes = ByteArray(8 * 4 * 3))
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, width = 8, height = 4, audio = false),
            writer,
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
            resolutionW = 2,
            resolutionH = 2,
        ).run(7, GradeK(), "/tmp/out.partial") // manualRotationDeg 0: 90° effective
        assertEquals(listOf("requestEncoderSurfaceInput"), writer.sessionRequests)
        assertEquals(0, gpu.encoderSurfaces.size)
        assertEquals(0, gpu.encoderRendered.size)
        assertEquals(listOf(0, 1), gpu.rendered)
        assertEquals(2, writer.rgbFrames)
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface (the writer's own): $backend", backend.contains("encoder-input=surface"))
        assertFalse(backend.contains("encoder-input=surface-gpu"))
    }

    @Test
    fun quarter_turn_with_crop_takes_the_writers_own_surface_mode() {
        // The gate's crop half: an effective 90° job with a non-full crop
        // rect is NOT encoderDrawEligible (the blit cannot crop) — the
        // request goes to the writer's OWN Surface mode and the readback
        // path with its crop step takes the frames. (frameBytes must match
        // the 8x4 session — the readback's size guard latches CPU on a
        // wrong-shaped buffer.)
        val gpu = FakeGpuDevelop(frameBytes = ByteArray(8 * 4 * 3))
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, width = 8, height = 4, audio = false),
            writer,
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(
            7,
            GradeK(cropX = 0.1f, cropY = 0.1f, cropW = 0.8f, cropH = 0.8f),
            "/tmp/out.partial",
        ) // manualRotationDeg 0: 90° effective
        assertEquals(listOf("requestEncoderSurfaceInput"), writer.sessionRequests)
        assertEquals(0, gpu.encoderSurfaces.size)
        assertEquals(0, gpu.encoderRendered.size)
        assertEquals(listOf(0, 1), gpu.rendered)
        assertEquals(2, writer.rgbFrames)
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface (the writer's own): $backend", backend.contains("encoder-input=surface"))
        assertFalse(backend.contains("encoder-input=surface-gpu"))
    }

    @Test
    fun quarter_turn_with_straighten_takes_the_writers_own_surface_mode() {
        // The gate's straighten half: an effective 90° job with a non-zero
        // straighten is NOT encoderDrawEligible (the blit cannot express a
        // skew) — the request goes to the writer's OWN Surface mode and
        // the readback path with its straighten step takes the frames.
        // (frameBytes must match the 8x4 session — the readback's size
        // guard latches CPU on a wrong-shaped buffer.)
        val gpu = FakeGpuDevelop(frameBytes = ByteArray(8 * 4 * 3))
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        ExportPipeline(
            FakeSession(frames = 2, width = 8, height = 4, audio = false),
            writer,
            lines::add,
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            gpuDevelop = gpu,
        ).run(7, GradeK(straightenDeg = 1.5f), "/tmp/out.partial") // manualRotationDeg 0: 90° effective
        assertEquals(listOf("requestEncoderSurfaceInput"), writer.sessionRequests)
        assertEquals(0, gpu.encoderSurfaces.size)
        assertEquals(0, gpu.encoderRendered.size)
        assertEquals(listOf(0, 1), gpu.rendered)
        assertEquals(2, writer.rgbFrames)
        val backend = lines.first { it.startsWith("render backend: ") }
        assertTrue("encoder-input must be surface (the writer's own): $backend", backend.contains("encoder-input=surface"))
        assertFalse(backend.contains("encoder-input=surface-gpu"))
    }

    @Test
    fun adopt_failure_restarts_the_whole_export_on_cpu() {
        // The adopt fallback's own guard: the writer could not wrap the
        // input surface in its EGL (SurfaceInputUnavailable from
        // adoptEncoderSurface) -> the exporter's clean whole-export CPU
        // restart — the same contract the writer's start-phase
        // SurfaceInputUnavailable rides. No frame encoded twice, no
        // partial continued mid-format.
        val session = FakeSession(frames = 2, audio = false)
        val gpu = FakeGpuDevelop(
            refuseEncoderSurface = true,
            encoderDetail = "develop session encoder surface: window attach (recovery: the writer's own surface mode)",
        )
        val failWriter = SessionSurfaceWriter(
            adoptUnavailable = Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_CONFIGURE,
                "no EGL display is available",
                "the export restarts on the CPU path",
            ),
            adoptStep = SurfaceInputStep.EGL_DISPLAY,
        )
        val cpuWriter = FakeWriter()
        val writers = ArrayDeque(listOf<Mp4MediaWriter>(failWriter, cpuWriter))
        val lines = ArrayList<String>()
        val exporter = Mp4Exporter(
            session = session,
            writerFactory = { writers.removeFirst() },
            engine = freshEngine(),
            stageLog = lines::add,
            gpuDevelop = gpu,
        )
        val out = File(tmp.root, "clip.mp4")
        val gpuJob = job().copy(
            renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            manualRotationDeg = 270,
        )
        val result = runBlocking { exporter.export(gpuJob, 7, out) }
        assertEquals(State.DONE, result.state)
        // The adopt-path fallback line names the failing step too (the
        // same one-line reason contract; owner bug report 2026-11-13).
        val fallback = lines.first { it.startsWith("render backend fallback: ") }
        assertEquals(
            "render backend fallback: requested=GPU_EXPERIMENTAL effective=CPU " +
                "reason=surface-input-unavailable:egl-display",
            fallback,
        )
        // The CPU re-run encoded every frame through the packed path.
        assertEquals(2, cpuWriter.videoFrames)
        // The failing writer was released; the session ran twice.
        assertTrue(failWriter.released)
        assertEquals(2, session.opened.get())
        assertEquals(2, session.closed.get())
        assertTrue(out.exists())
    }

    @Test
    fun session_encoder_surface_falls_back_when_the_session_refuses_mid_attach() {
        // The attach-time IllegalStateException guard: the .so vanished
        // mid-export (defensive — the session opened, so the lib is
        // present; the guard exists because EVERY seam in this file
        // rides one). The pipeline treats the throw as a refusal: the
        // writer adopts, the export continues on the CPU-RGB Surface
        // mode, ONE line names it.
        val gpu = FakeGpuDevelop()
        val writer = SessionSurfaceWriter()
        val lines = ArrayList<String>()
        runSessionEncoderExport(gpu, writer, lines)
        // The green fake attached; this test locks the GUARD's existence,
        // not its trigger — the guard lives in Mp4Exporter.kt's catch
        // (IllegalStateException -> false -> the adopt branch). The
        // behavior contract it protects is already asserted above; here
        // the green path proves the guard never misfires on a healthy
        // seam (an over-eager catch would have adopted instead).
        assertEquals(1, gpu.encoderSurfaces.size)
        assertEquals(2, gpu.encoderRendered.size)
        assertEquals(0, writer.adoptCalls.size)
    }

    // ---- E1d: 10-bit HEVC Main10 on the GPU encoder-surface path ----

    /** The fake GPU seam plus the 10-bit door: [tenBitSession] is what openSessionTenBit returns. */
    private class TenBitGpu(
        val inner: FakeGpuDevelop,
        private val probeOk: Boolean = true,
        private val tenBitSession: Long = 2L,
    ) : GpuDevelop by inner {
        var tenBitOpens = 0
        override fun tenBitProbe(): Boolean = probeOk
        override fun openSessionTenBit(): Long {
            tenBitOpens++
            return tenBitSession
        }
    }

    private val tenFacts = listOf(
        EncoderFacts(
            "c2.test.hevc", "video/hevc", true, profiles = setOf(1, 2),
            colorFormats = setOf(54, 0x7F00AAA2), bitrateModes = setOf(1, 2),
            bitrateRangeBps = 1_000_000..160_000_000, maxWidth = 8192, maxHeight = 8192,
        ),
    )

    private fun tenRun(settings: ExportSettings = ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10)) =
        ExportPlanRun(settings, { tenFacts })

    /** The eligible 1:1 geometry recipe (container 90 + manual 270 = effective 0 on a 4x4 square). */
    private fun runTen(gpu: GpuDevelop, writer: Mp4MediaWriter, run: ExportPlanRun, backend: RenderBackend = RenderBackend.GPU_EXPERIMENTAL) =
        ExportPipeline(
            FakeSession(frames = 3, audio = false), writer,
            renderBackend = backend, gpuDevelop = gpu, planRun = run,
        ).run(7, GradeK(), "/tmp/out.partial", manualRotationDeg = 270)

    @Test
    fun ten_bit_gpu_path_opens_the_ten_bit_session_and_plans_main10() {
        val inner = FakeGpuDevelop()
        val gpu = TenBitGpu(inner)
        val writer = SessionSurfaceWriter()
        val run = tenRun()
        runTen(gpu, writer, run)
        val plan = run.plan!!
        assertEquals(10, plan.bitDepth)
        assertEquals(2, plan.profile) // HEVC Main10
        assertEquals("c2.test.hevc", plan.encoderName)
        assertEquals(1, gpu.tenBitOpens)
        assertEquals(0, inner.openCalls) // the 8-bit door never ran
        assertEquals(listOf(0, 1, 2), inner.encoderRendered)
        assertEquals(0, writer.adoptCalls.size)
        assertFalse(plan.issues.any { it.kind == ExportIssue.Kind.TEN_BIT_UNAVAILABLE })
    }

    @Test
    fun ten_bit_without_the_gpu_backend_plans_8_bit_with_the_issue() {
        val inner = FakeGpuDevelop()
        val run = tenRun()
        val writer = FakeWriter()
        ExportPipeline(
            FakeSession(frames = 2, audio = false), writer,
            renderBackend = RenderBackend.CPU, gpuDevelop = TenBitGpu(inner), planRun = run,
        ).run(7, GradeK(), "/tmp/out.partial")
        val plan = run.plan!!
        assertEquals(8, plan.bitDepth) // the CPU develop is 8-bit RGB888: no CPU 10-bit
        assertTrue(plan.issues.any { it.kind == ExportIssue.Kind.TEN_BIT_UNAVAILABLE })
        assertEquals(10, plan.requested.bitDepth)
        assertTrue(plan.differsFromRequest)
        assertTrue(run.steps.any { it.startsWith("ten-bit path:") })
    }

    @Test
    fun ten_bit_needs_the_egl_config_probe() {
        val inner = FakeGpuDevelop()
        val gpu = TenBitGpu(inner, probeOk = false)
        val run = tenRun()
        runTen(gpu, SessionSurfaceWriter(), run)
        assertEquals(8, run.plan!!.bitDepth)
        assertEquals(0, gpu.tenBitOpens)
        assertEquals(1, inner.openCalls) // the ordinary 8-bit session served it
    }

    @Test
    fun a_refused_ten_bit_surface_throws_instead_of_adopting_8_bit_frames() {
        val inner = FakeGpuDevelop(refuseEncoderSurface = true)
        val writer = SessionSurfaceWriter()
        val run = tenRun()
        val e = runCatching { runTen(TenBitGpu(inner), writer, run) }.exceptionOrNull()
        assertTrue(e is TenBitUnavailable)
        assertFalse((e as TenBitUnavailable).midStream)
        assertEquals(0, writer.adoptCalls.size) // no 8-bit-in-10-bit adoption
        assertEquals(0, writer.rgbFrames)
    }

    @Test
    fun a_ten_bit_session_that_cannot_open_throws_before_any_frame() {
        val e = runCatching {
            runTen(TenBitGpu(FakeGpuDevelop(), tenBitSession = 0L), SessionSurfaceWriter(), tenRun())
        }.exceptionOrNull()
        assertTrue(e is TenBitUnavailable)
        assertFalse((e as TenBitUnavailable).midStream)
    }

    @Test
    fun a_ten_bit_render_failure_after_frames_fails_the_export_not_a_mixed_file() {
        val inner = FakeGpuDevelop(failEncoderRenderFrom = 1)
        val writer = SessionSurfaceWriter()
        val e = runCatching { runTen(TenBitGpu(inner), writer, tenRun()) }.exceptionOrNull()
        assertTrue(e is TenBitUnavailable)
        assertTrue((e as TenBitUnavailable).midStream)
        assertEquals(0, writer.adoptCalls.size)
    }

    @Test
    fun the_exporter_replans_8_bit_once_with_the_reason_when_the_ten_bit_session_fails() {
        val gpu = TenBitGpu(FakeGpuDevelop(refuseEncoderSurface = true))
        val seen = ArrayList<SessionSurfaceWriter>()
        // the session writer plus a staged file at finish (the exporter renames it)
        class FileWriter(val inner: SessionSurfaceWriter) : Mp4MediaWriter by inner {
            var path: String? = null
            override fun start(outPath: String, width: Int, height: Int, orientationDeg: Int, sampleRate: Int, channels: Int) {
                path = outPath
                inner.start(outPath, width, height, orientationDeg, sampleRate, channels)
            }
            override fun finish(): Boolean { File(path!!).writeBytes(ByteArray(64)); return true }
        }
        val exporter = Mp4Exporter(
            session = FakeSession(frames = 3, audio = false),
            writerFactory = { FileWriter(SessionSurfaceWriter().also { seen += it }) },
            engine = freshEngine(),
            gpuDevelop = gpu,
            encoderFacts = { tenFacts },
            planLog = {},
        )
        val job = ExportJob(
            "j", "src", GradeK(), Target.MP4, State.QUEUED, renderBackend = RenderBackend.GPU_EXPERIMENTAL,
            manualRotationDeg = 270, settings = ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10),
        )
        val out = File(tmp.root, "ten.mp4")
        val r = runBlocking { exporter.export(job, 7, out) }
        assertEquals(r.detail, State.DONE, r.state)
        assertEquals(2, seen.size) // the first attempt, then ONE 8-bit re-plan
        val rep = r.report!!
        assertEquals(8, rep.bitDepth) // honest: the file is 8-bit and says why
        assertTrue(rep.issues.any { it.startsWith("TEN_BIT_UNAVAILABLE:") })
        assertTrue(rep.steps.any { it.startsWith("fix: 10-bit -> 8-bit") })
        assertTrue(ExportText.tenBitDropped(rep))
    }
}
