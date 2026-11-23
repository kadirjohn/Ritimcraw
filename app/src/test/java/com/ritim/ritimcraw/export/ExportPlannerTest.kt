package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.export.ExportIssue.Kind
import com.ritim.ritimcraw.export.ExportIssue.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ExportPlan decision table, against the REAL Xiaomi 15 Ultra encoder
 * list (plan 13.13, .planning/device-15u) and a weak-device fixture.
 */
class ExportPlannerTest {

    private val SURFACE = 0x7F000789
    private val YUV = 0x7F420888
    private val P010 = 54
    private val RGBA1010102 = 0x7F00AAA2
    private val CBR_VBR = setOf(2, 1)

    private fun probe(maxW: Int, maxH: Int, maxFps: Double) = SizeRateProbe { w, h, fps ->
        (w <= maxW && h <= maxH || w <= maxH && h <= maxW) && fps <= maxFps
    }

    /** The 15 Ultra's video/hevc + video/avc encoders as dumpsys/CodecProbe report them. */
    private val ultra = listOf(
        EncoderFacts(
            "c2.qti.hevc.encoder", "video/hevc", hardware = true,
            profiles = setOf(1, 2, 4, 4096, 8192, 0x20), colorFormats = setOf(SURFACE, YUV, P010, RGBA1010102),
            bitrateModes = CBR_VBR, bitrateRangeBps = 1_000_000..160_000_000, maxWidth = 8192, maxHeight = 8192,
            features = setOf("hlg-editing", "hdr-editing"), sizeRate = probe(8192, 8192, 480.0),
        ),
        EncoderFacts(
            "OMX.qcom.video.encoder.hevc", "video/hevc", hardware = true,
            profiles = setOf(1, 2), colorFormats = setOf(SURFACE, YUV, P010), bitrateModes = CBR_VBR,
            bitrateRangeBps = 1_000_000..160_000_000, maxWidth = 8192, maxHeight = 8192, sizeRate = probe(8192, 8192, 480.0),
        ),
        EncoderFacts(
            "c2.qti.hevc.encoder.hdr", "video/hevc", hardware = true,
            profiles = setOf(1, 2, 4096), colorFormats = setOf(SURFACE, P010), bitrateModes = CBR_VBR,
            bitrateRangeBps = 1_000_000..160_000_000, maxWidth = 4096, maxHeight = 2176, features = setOf("hdr-editing"),
            sizeRate = probe(4096, 2176, 120.0),
        ),
        EncoderFacts(
            "c2.qti.hevc.encoder.cq", "video/hevc", hardware = true,
            bitrateModes = setOf(0), bitrateRangeBps = 1_000_000..160_000_000, maxWidth = 512, maxHeight = 512,
            sizeRate = probe(512, 512, 30.0),
        ),
        EncoderFacts(
            "c2.qti.avc.encoder", "video/avc", hardware = true,
            profiles = setOf(1, 2, 8), colorFormats = setOf(SURFACE, YUV, P010, RGBA1010102), bitrateModes = CBR_VBR,
            bitrateRangeBps = 1_000_000..220_000_000, maxWidth = 8192, maxHeight = 4320, sizeRate = probe(8192, 4320, 240.0),
        ),
        EncoderFacts(
            "c2.android.avc.encoder", "video/avc", hardware = false,
            profiles = setOf(1, 2), colorFormats = setOf(YUV, SURFACE), bitrateModes = setOf(0, 1, 2),
            bitrateRangeBps = 1_000_000..12_000_000, maxWidth = 4096, maxHeight = 2304, sizeRate = probe(4096, 2304, 60.0),
        ),
    )

    /** A weak phone: one 1080p hardware AVC encoder at 20 Mbps, no HEVC, no 10-bit. */
    private val weak = listOf(
        EncoderFacts(
            "OMX.weak.avc.encoder", "video/avc", hardware = true,
            profiles = setOf(1, 2, 8), colorFormats = setOf(YUV, SURFACE), bitrateModes = setOf(1),
            bitrateRangeBps = 100_000..20_000_000, maxWidth = 1920, maxHeight = 1088, sizeRate = probe(1920, 1088, 30.0),
        ),
    )

    private val uhd30 = ExportInput(3840, 2160, 30.0)
    private val fhd30 = ExportInput(1920, 1080, 30.0)

    private fun ExportPlan.kinds() = issues.map { it.kind }

    // ---- the happy paths on the 15 Ultra ----

    @Test
    fun avc_standard_4k30_picks_the_hardware_encoder_by_name_with_the_table_bitrate() {
        val plan = ExportPlanner.resolve(ExportSettings(), uhd30, ultra)
        assertTrue(plan.ok)
        assertEquals("c2.qti.avc.encoder", plan.encoderName)
        assertEquals(true, plan.encoderHardware)
        assertEquals("video/avc", plan.mime)
        assertEquals(39_000_000, plan.bitrateBps)
        assertEquals(ExportPlan.AVC_PROFILE_HIGH, plan.profile)
        assertEquals(30.0, plan.fps, 0.0)          // the real fps, not a constant
        assertEquals(1f, plan.keyframeIntervalSec, 0f)
        assertEquals(128, plan.audioKbps)
        assertTrue(plan.issues.isEmpty())
        assertFalse(plan.differsFromRequest)
    }

    @Test
    fun hevc_picks_the_general_encoder_never_cq_or_hdr_variants() {
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC), uhd30, ultra)
        assertEquals("c2.qti.hevc.encoder", plan.encoderName)
        // even when the general encoder is missing, .cq and .hdr are not stand-ins for SDR video
        val onlyVariants = ultra.filter { it.name.endsWith(".cq") || it.name.endsWith(".hdr") }
        val none = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC), fhd30, onlyVariants)
        assertNull(none.encoderName)
    }

    @Test
    fun hevc_prefers_codec2_over_omx_when_both_fit() {
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC), fhd30, ultra)
        assertEquals("c2.qti.hevc.encoder", plan.encoderName)
        val omxOnly = ultra.filter { it.name == "OMX.qcom.video.encoder.hevc" }
        assertEquals("OMX.qcom.video.encoder.hevc", ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC), fhd30, omxOnly).encoderName)
    }

    @Test
    fun hevc_quality_4k30_max_uses_the_table_value_under_the_160_ceiling() {
        val s = ExportPreset.QUALITY.apply(ExportSettings())
        val plan = ExportPlanner.resolve(s, uhd30, ultra)
        // 16 * 3.249 * 2.4 * 0.6 = 74.9 -> 75.0
        assertEquals(75_000_000, plan.bitrateBps)
        assertTrue(plan.issues.isEmpty())
        assertEquals(null, plan.profile)            // 8-bit HEVC: the encoder's own default (Main)
    }

    @Test
    fun avc_max_4k60_is_lowered_to_the_220_ceiling_with_a_fix() {
        val plan = ExportPlanner.resolve(
            ExportSettings(bitrateLevel = BitrateLevel.MAX), ExportInput(3840, 2160, 60.0), ultra,
        )
        assertEquals(220_000_000, plan.bitrateBps)
        val issue = plan.issues.single { it.kind == Kind.BITRATE_ABOVE_CEILING }
        assertEquals(Severity.WARNING, issue.severity)
        assertEquals(listOf("225", "220"), issue.args)
        assertEquals(BitrateLevel.CUSTOM, issue.fix!!.bitrateLevel)
        assertEquals(220.0, issue.fix!!.customMbps!!, 0.0)
        assertTrue(plan.ok)
    }

    @Test
    fun custom_above_the_ceiling_is_clamped_unless_uncapped() {
        val custom = ExportSettings(codec = ExportCodec.HEVC, bitrateLevel = BitrateLevel.CUSTOM, customMbps = 200.0)
        val capped = ExportPlanner.resolve(custom, uhd30, ultra)
        assertEquals(160_000_000, capped.bitrateBps)
        assertTrue(Kind.BITRATE_ABOVE_CEILING in capped.kinds())

        val uncapped = ExportPlanner.resolve(custom.copy(uncapped = true), uhd30, ultra)
        assertEquals(200_000_000, uncapped.bitrateBps)       // the user's value passes unchanged
        val risk = uncapped.issues.single { it.kind == Kind.BITRATE_UNCAPPED_RISK }
        assertEquals(Severity.WARNING, risk.severity)
        assertFalse(risk.fix!!.uncapped)
        assertFalse(Kind.BITRATE_ABOVE_CEILING in uncapped.kinds())
        assertTrue(uncapped.ok)
    }

    @Test
    fun custom_below_the_floor_is_raised_with_an_issue() {
        val plan = ExportPlanner.resolve(
            ExportSettings(bitrateLevel = BitrateLevel.CUSTOM, customMbps = 0.5), fhd30, ultra,
        )
        assertEquals(1_000_000, plan.bitrateBps)
        assertTrue(Kind.BITRATE_BELOW_FLOOR in plan.kinds())
    }

    @Test
    fun keyframe_choices_map_to_seconds_and_all_intra_is_zero() {
        fun sec(k: KeyframeInterval) = ExportPlanner.resolve(ExportSettings(keyframe = k), fhd30, ultra).keyframeIntervalSec
        assertEquals(1f, sec(KeyframeInterval.AUTO), 0f)
        assertEquals(0.5f, sec(KeyframeInterval.HALF), 0f)
        assertEquals(2f, sec(KeyframeInterval.TWO), 0f)
        assertEquals(5f, sec(KeyframeInterval.FIVE), 0f)
        assertEquals(0f, sec(KeyframeInterval.ALL_INTRA), 0f)
    }

    @Test
    fun the_bitrate_uses_the_inputs_real_fps() {
        val at30 = ExportPlanner.resolve(ExportSettings(), ExportInput(3840, 2160, 30.0), ultra)
        val at60 = ExportPlanner.resolve(ExportSettings(), ExportInput(3840, 2160, 60.0), ultra)
        assertTrue(at60.bitrateBps > at30.bitrateBps)
        assertEquals(59.94, ExportPlanner.resolve(ExportSettings(), ExportInput(1920, 1080, 59.94), ultra).fps, 0.0)
    }

    // ---- the weak device ----

    @Test
    fun hevc_without_a_hevc_encoder_falls_back_to_avc_with_a_fix() {
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC), fhd30, weak)
        assertTrue(plan.ok)
        assertEquals("video/avc", plan.mime)
        assertEquals("OMX.weak.avc.encoder", plan.encoderName)
        val issue = plan.issues.single { it.kind == Kind.CODEC_FALLBACK }
        assertEquals(ExportCodec.AVC, issue.fix!!.codec)
        assertTrue(plan.differsFromRequest)
        assertEquals(ExportCodec.AVC, plan.effective.codec)
        assertEquals(ExportCodec.HEVC, plan.requested.codec)
    }

    @Test
    fun an_encoder_that_exists_but_cannot_do_the_size_switches_codec_not_falls_back() {
        // HEVC exists on the phone but only at 1080p; AVC can do 4K: CODEC_SWITCHED, not CODEC_FALLBACK.
        val hevc1080 = EncoderFacts("c2.x.hevc.encoder", "video/hevc", true, maxWidth = 1920, maxHeight = 1088, sizeRate = probe(1920, 1088, 60.0))
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC), uhd30, listOf(hevc1080) + ultra.filter { it.mime == "video/avc" })
        assertEquals("video/avc", plan.mime)
        assertTrue(Kind.CODEC_SWITCHED in plan.kinds())
        assertFalse(Kind.CODEC_FALLBACK in plan.kinds())
    }

    @Test
    fun ten_bit_is_8_bit_while_no_render_path_exists() {
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10), fhd30, ultra)
        assertEquals(8, plan.bitDepth)
        val issue = plan.issues.single { it.kind == Kind.TEN_BIT_UNAVAILABLE }
        assertEquals(8, issue.fix!!.bitDepth)
        assertTrue(plan.ok)
    }

    @Test
    fun ten_bit_with_a_render_path_uses_a_main10_capable_encoder_and_profile() {
        val ctx = ExportContext(tenBitPathAvailable = true)
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10), uhd30, ultra, ctx)
        assertEquals(10, plan.bitDepth)
        assertEquals(2, plan.profile)               // HEVC Main10
        assertEquals("c2.qti.hevc.encoder", plan.encoderName)
        assertTrue(plan.issues.isEmpty())
    }

    @Test
    fun avc_ten_bit_asks_for_high10_not_high() {
        val avc10 = listOf(
            EncoderFacts(
                "c2.t.avc", "video/avc", true, profiles = setOf(1, 2, 8, 16),
                colorFormats = setOf(P010, SURFACE), bitrateModes = CBR_VBR, maxWidth = 4096, maxHeight = 4096,
            ),
        )
        val p = ExportPlanner.resolve(
            ExportSettings(codec = ExportCodec.AVC, bitDepth = 10), fhd30, avc10, ExportContext(tenBitPathAvailable = true),
        )
        assertEquals(10, p.bitDepth)
        assertEquals(16, p.profile)
    }

    @Test
    fun ten_bit_the_encoder_cannot_do_degrades_to_8_bit_with_an_issue() {
        val ctx = ExportContext(tenBitPathAvailable = true)
        val plan = ExportPlanner.resolve(ExportSettings(bitDepth = 10), fhd30, weak, ctx)
        assertEquals(8, plan.bitDepth)
        assertTrue(Kind.TEN_BIT_UNSUPPORTED in plan.kinds())
        assertEquals(8, plan.issues.single { it.kind == Kind.TEN_BIT_UNSUPPORTED }.fix!!.bitDepth)
    }

    @Test
    fun an_unsupported_rate_mode_switches_with_an_issue() {
        // the weak encoder is VBR-only
        val plan = ExportPlanner.resolve(ExportSettings(rateMode = ExportRateMode.CBR), fhd30, weak)
        assertEquals(ExportRateMode.VBR, plan.effective.rateMode)
        assertTrue(Kind.RATE_MODE_UNSUPPORTED in plan.kinds())
    }

    @Test
    fun cq_is_not_available_for_video_on_the_ultra_because_the_cq_encoder_is_512_only() {
        val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC, rateMode = ExportRateMode.CQ), uhd30, ultra)
        // no general HEVC encoder offers CQ: the plan moves to a mode that exists
        assertEquals("c2.qti.hevc.encoder", plan.encoderName)
        assertNotNull(plan.issues.firstOrNull { it.kind == Kind.RATE_MODE_UNSUPPORTED })
        assertTrue(plan.effective.rateMode != ExportRateMode.CQ)
    }

    @Test
    fun a_size_no_encoder_makes_is_an_error_with_a_resolution_fix() {
        val plan = ExportPlanner.resolve(ExportSettings(), uhd30, weak)
        assertFalse(plan.ok)
        assertNull(plan.encoderName)
        val issue = plan.issues.single { it.kind == Kind.SIZE_UNSUPPORTED }
        assertEquals(Severity.ERROR, issue.severity)
        assertEquals(1920 to 1080, issue.fixResolution)
    }

    @Test
    fun a_portrait_size_gets_a_portrait_resolution_fix() {
        val plan = ExportPlanner.resolve(ExportSettings(), ExportInput(2160, 3840, 30.0), weak)
        assertEquals(1080 to 1920, plan.issues.single { it.kind == Kind.SIZE_UNSUPPORTED }.fixResolution)
    }

    @Test
    fun no_encoder_at_all_is_an_error() {
        val plan = ExportPlanner.resolve(ExportSettings(), fhd30, emptyList())
        assertFalse(plan.ok)
        assertEquals(Kind.NO_ENCODER, plan.issues.single().kind)
    }

    @Test
    fun an_unlisted_profile_is_dropped_to_auto_with_a_fix() {
        val plan = ExportPlanner.resolve(ExportSettings(profile = 64), fhd30, ultra) // AVC High444
        assertTrue(Kind.PROFILE_UNSUPPORTED in plan.kinds())
        assertNull(plan.effective.profile)
        assertEquals(ExportPlan.AVC_PROFILE_HIGH, plan.profile)      // AUTO for AVC = High, as before
        val ok = ExportPlanner.resolve(ExportSettings(profile = 2), fhd30, ultra)
        assertEquals(2, ok.profile)
        assertTrue(ok.issues.isEmpty())
    }

    @Test
    fun hdr_and_dv_are_not_selectable_in_e1() {
        for (r in listOf(ExportDynamicRange.HLG, ExportDynamicRange.HDR10, ExportDynamicRange.DV)) {
            val plan = ExportPlanner.resolve(ExportSettings(codec = ExportCodec.HEVC, dynamicRange = r), fhd30, ultra)
            assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
            assertEquals(ExportDynamicRange.SDR, plan.issues.single { it.kind == Kind.DYNAMIC_RANGE_UNAVAILABLE }.fix!!.dynamicRange)
            assertEquals(r, plan.requested.dynamicRange)
        }
    }

    @Test
    fun a_software_encoder_is_flagged_and_an_unprobed_size_is_info() {
        val sw = listOf(EncoderFacts("c2.android.avc.encoder", "video/avc", hardware = false))
        val plan = ExportPlanner.resolve(ExportSettings(), fhd30, sw)
        assertTrue(Kind.ENCODER_SOFTWARE in plan.kinds())
        assertEquals(Severity.INFO, plan.issues.single { it.kind == Kind.SIZE_UNKNOWN }.severity)
        assertTrue(plan.ok)
    }

    @Test
    fun the_hardware_encoder_wins_over_a_software_one() {
        val plan = ExportPlanner.resolve(ExportSettings(), fhd30, ultra.reversed())
        assertEquals("c2.qti.avc.encoder", plan.encoderName)
    }

    @Test
    fun an_unlisted_frame_rate_is_info() {
        val e = EncoderFacts("c2.x.avc.encoder", "video/avc", true, supportedFps = 1.0..30.0, maxWidth = 4096, maxHeight = 4096)
        val plan = ExportPlanner.resolve(ExportSettings(), ExportInput(1920, 1080, 60.0), listOf(e))
        assertEquals(Severity.INFO, plan.issues.single { it.kind == Kind.RATE_UNLISTED }.severity)
    }

    @Test
    fun audio_is_snapped_to_the_four_offered_rates() {
        assertEquals(192, ExportPlanner.resolve(ExportSettings(audioKbps = 200), fhd30, ultra).audioKbps)
        assertEquals(320, ExportPlanner.resolve(ExportSettings(audioKbps = 320), fhd30, ultra).audioKbps)
    }

    @Test
    fun the_plan_is_deterministic_and_estimates_size() {
        val a = ExportPlanner.resolve(ExportSettings(), uhd30, ultra)
        assertEquals(a, ExportPlanner.resolve(ExportSettings(), uhd30, ultra))
        // (39 Mbps video + 128 kbps audio) for 10 s
        assertEquals(((39_000_000L + 128_000L) * 10) / 8, a.estimatedBytes(10.0))
    }

    // ---- legacy jobs ----

    @Test
    fun a_job_without_settings_resolves_to_exactly_todays_behavior() {
        val plan = ExportPlan.legacy(3840, 2160)
        assertEquals("video/avc", plan.mime)
        assertNull(plan.encoderName)                 // chosen by MIME, as before
        assertEquals(ExportPlan.AVC_PROFILE_HIGH, plan.profile)
        assertEquals(19_906_560, plan.bitrateBps)
        assertEquals(30.0, plan.fps, 0.0)            // KEY_FRAME_RATE stays 30 for legacy jobs
        assertEquals(1f, plan.keyframeIntervalSec, 0f)
        assertEquals(128, plan.audioKbps)
        assertEquals(8, plan.bitDepth)
        assertTrue(plan.issues.isEmpty())
    }

    // ---- reports -> facts ----

    @Test
    fun facts_come_from_a_probe_report_and_decoders_are_rejected() {
        val report = com.ritim.ritimcraw.engine.CodecReport(
            name = "c2.qti.dv.encoder", mime = "video/dolby-vision", hardware = true,
            profiles = listOf("256"), maxSize = "7680x4320", main10Ok = null, isEncoder = true,
            profileLevels = listOf(com.ritim.ritimcraw.engine.ProfileLevel(256, 0x800)),
            colorFormats = listOf(SURFACE, P010), bitrateModes = listOf(2, 1), bitrateUpper = 160_000_000,
        )
        val f = EncoderFacts.fromReport(report)!!
        assertEquals(setOf(256), f.profiles)
        assertEquals(7680, f.maxWidth)
        assertEquals(1..160_000_000, f.bitrateRangeBps)
        assertEquals(Support.YES, f.sizeSupport(3840, 2160, 60.0))
        assertEquals(Support.NO, f.sizeSupport(8192, 4320, 30.0))
        assertNull(EncoderFacts.fromReport(report.copy(isEncoder = false)))
    }

    @Test
    fun roles_are_told_apart_by_name_and_capability() {
        assertEquals(EncoderFacts.Role.GENERAL, ultra[0].role)
        assertEquals(EncoderFacts.Role.HDR_VARIANT, ultra[2].role)
        assertEquals(EncoderFacts.Role.CQ_ONLY, ultra[3].role)
        // a CQ-only capability is enough even without the .cq suffix
        assertEquals(EncoderFacts.Role.CQ_ONLY, EncoderFacts("x.thumb.encoder", "video/hevc", true, bitrateModes = setOf(0)).role)
    }
}
