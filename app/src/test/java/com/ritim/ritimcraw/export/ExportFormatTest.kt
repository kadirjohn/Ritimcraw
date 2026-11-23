package com.ritim.ritimcraw.export

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The MediaFormat decision as data: which key each export choice sets (E1b). */
class ExportFormatTest {

    private val enc = listOf(
        EncoderFacts(
            "c2.test.hevc.encoder", "video/hevc", hardware = true, profiles = setOf(1, 2),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000, maxWidth = 8192, maxHeight = 8192,
        ),
        EncoderFacts(
            "c2.test.avc.encoder", "video/avc", hardware = true, profiles = setOf(1, 2, 8),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..200_000_000, maxWidth = 8192, maxHeight = 8192,
        ),
    )

    private fun plan(s: ExportSettings, fps: Double = 30.0, w: Int = 1920, h: Int = 1080) =
        ExportPlanner.resolve(s, ExportInput(w, h, fps), enc)

    @Test
    fun a_legacy_job_gets_exactly_the_keys_the_writer_always_set() {
        val spec = ExportFormats.video(ExportPlan.legacy(3840, 2160), surface = false)
        assertEquals("video/avc", spec.mime)
        assertEquals(3840, spec.width)
        assertEquals(
            mapOf<String, Number>(
                MediaFormat.KEY_COLOR_FORMAT to MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                MediaFormat.KEY_BIT_RATE to 19_906_560,
                MediaFormat.KEY_FRAME_RATE to 30,
                MediaFormat.KEY_I_FRAME_INTERVAL to 1,
                MediaFormat.KEY_PROFILE to MediaCodecInfo.CodecProfileLevel.AVCProfileHigh,
                MediaFormat.KEY_COLOR_STANDARD to MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.KEY_COLOR_RANGE to MediaFormat.COLOR_RANGE_LIMITED,
                MediaFormat.KEY_COLOR_TRANSFER to MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            ),
            spec.values,
        )
        // no rate mode for a legacy job: the encoder default, as before
        assertFalse(spec.values.containsKey(MediaFormat.KEY_BITRATE_MODE))
        assertEquals(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            ExportFormats.video(ExportPlan.legacy(1920, 1080), surface = true).int(MediaFormat.KEY_COLOR_FORMAT),
        )
        // legacy bitrate = the old formula, 2..25 Mbps
        assertEquals(4_976_640, ExportFormats.video(ExportPlan.legacy(1920, 1080), false).int(MediaFormat.KEY_BIT_RATE))
    }

    @Test
    fun hevc_8bit_sets_the_mime_bitrate_mode_and_no_profile() {
        val p = plan(ExportSettings(codec = ExportCodec.HEVC, rateMode = ExportRateMode.CBR))
        val spec = ExportFormats.video(p, surface = false)
        assertEquals("video/hevc", spec.mime)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR, spec.int(MediaFormat.KEY_BITRATE_MODE))
        assertNull(spec.values[MediaFormat.KEY_PROFILE])
        assertEquals(p.bitrateBps, spec.int(MediaFormat.KEY_BIT_RATE))
        assertEquals(MediaFormat.COLOR_STANDARD_BT709, spec.int(MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(MediaFormat.COLOR_RANGE_LIMITED, spec.int(MediaFormat.KEY_COLOR_RANGE))
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, spec.int(MediaFormat.KEY_COLOR_TRANSFER))
        assertEquals("c2.test.hevc.encoder", p.encoderName)
    }

    @Test
    fun vbr_and_the_avc_profile_come_from_the_plan() {
        val spec = ExportFormats.video(plan(ExportSettings(rateMode = ExportRateMode.VBR)), surface = true)
        assertEquals(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR, spec.int(MediaFormat.KEY_BITRATE_MODE))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh, spec.int(MediaFormat.KEY_PROFILE))
    }

    @Test
    fun a_whole_fps_is_an_int_and_a_fractional_one_a_float() {
        assertEquals(24, ExportFormats.video(plan(ExportSettings(), fps = 24.0), false).int(MediaFormat.KEY_FRAME_RATE))
        val f = ExportFormats.video(plan(ExportSettings(), fps = 29.97), false)
        assertNull(f.int(MediaFormat.KEY_FRAME_RATE))
        assertEquals(29.97f, f.float(MediaFormat.KEY_FRAME_RATE)!!, 0f)
        assertEquals(23.976f, ExportFormats.video(plan(ExportSettings(), fps = 23.976), false).float(MediaFormat.KEY_FRAME_RATE)!!, 0f)
    }

    @Test
    fun keyframe_choices_map_all_intra_to_zero_and_a_half_second_to_a_float() {
        fun key(k: KeyframeInterval) =
            ExportFormats.video(plan(ExportSettings(keyframe = k)), false).values[MediaFormat.KEY_I_FRAME_INTERVAL]
        assertEquals(0, key(KeyframeInterval.ALL_INTRA))
        assertEquals(1, key(KeyframeInterval.AUTO))
        assertEquals(2, key(KeyframeInterval.TWO))
        assertEquals(5, key(KeyframeInterval.FIVE))
        assertEquals(0.5f, key(KeyframeInterval.HALF))
    }

    @Test
    fun audio_bitrate_comes_from_the_setting() {
        val a = ExportFormats.audio(48000, 2, 256)
        assertEquals("audio/mp4a-latm", a.mime)
        assertEquals(256_000, a.int(MediaFormat.KEY_BIT_RATE))
        assertEquals(48000, a.int(MediaFormat.KEY_SAMPLE_RATE))
        assertEquals(2, a.int(MediaFormat.KEY_CHANNEL_COUNT))
        assertEquals(MediaCodecInfo.CodecProfileLevel.AACObjectLC, a.int(MediaFormat.KEY_AAC_PROFILE))
        assertEquals(128_000, ExportFormats.audio(44100, 1, ExportSettings().audioKbps).int(MediaFormat.KEY_BIT_RATE))
    }

    @Test
    fun frame_rate_comes_from_the_clips_own_pts() {
        // 30 fps: 33_333 us steps
        assertEquals(30.0, ExportFps.fromPts(List(10) { it * 33_333L }), 0.0)
        // 29.97 and 23.976 snap to the exact NTSC rates
        assertEquals(29.97, ExportFps.fromPts(List(100) { it * 33_367L }), 0.0)
        assertEquals(23.976, ExportFps.fromPts(List(100) { it * 41_708L }), 0.0)
        assertEquals(60.0, ExportFps.fromPts(List(100) { it * 16_667L }), 0.0)
        // one frame or no span: the old constant 30
        assertEquals(30.0, ExportFps.fromPts(listOf(0L)), 0.0)
        assertEquals(30.0, ExportFps.fromPts(listOf(5L, 5L)), 0.0)
        assertTrue(ExportFps.snap(37.0) == 37.0)
    }
}
