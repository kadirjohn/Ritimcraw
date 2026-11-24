package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The export sheet's pure decisions (E1c): produced line, estimates, choices, remembered settings. */
class ExportSheetModelTest {

    private val subject = ExportSheetModel.Subject(1920, 1080, 30.0, 60.0)
    private val hevcFacts = listOf(
        EncoderFacts("c2.t.hevc", "video/hevc", true, maxWidth = 8192, maxHeight = 8192, bitrateRangeBps = 1_000_000..160_000_000),
        EncoderFacts("c2.t.avc", "video/avc", true, maxWidth = 8192, maxHeight = 8192, bitrateRangeBps = 1_000_000..200_000_000),
    )

    @Test
    fun the_default_is_the_compatible_preset_and_the_plan_says_what_is_produced() {
        val s = ExportSheetModel.defaultSettings()
        assertEquals(ExportPreset.COMPATIBLE, ExportPreset.of(s))
        val p = ExportSheetModel.plan(s, subject, hevcFacts)
        assertTrue(p.ok)
        assertEquals("video/avc", p.mime)
        assertEquals(1920, p.width)
        assertEquals(30.0, p.fps, 0.0)
        assertEquals(8, p.bitDepth)
        // size estimate = (video + audio) bits over the clip length
        assertEquals(p.estimatedBytes(60.0), ((p.bitrateBps + 192_000L) * 60 / 8))
    }

    @Test
    fun a_preset_without_hevc_on_the_device_falls_back_to_avc() {
        val avcOnly = hevcFacts.filter { it.mime == "video/avc" }
        assertFalse(ExportSheetModel.hevcAvailable(avcOnly))
        assertTrue(ExportSheetModel.hevcAvailable(hevcFacts))
        assertTrue(ExportSheetModel.hevcAvailable(emptyList())) // unknown facts: the encode decides
        assertEquals(ExportCodec.AVC, ExportSheetModel.applyPreset(ExportSettings(), ExportPreset.SMALLER, avcOnly).codec)
        assertEquals(ExportCodec.HEVC, ExportSheetModel.applyPreset(ExportSettings(), ExportPreset.SMALLER, hevcFacts).codec)
    }

    @Test
    fun a_changed_setting_makes_the_preset_custom() {
        val s = ExportSheetModel.defaultSettings().copy(rateMode = ExportRateMode.CBR)
        assertEquals(ExportPreset.CUSTOM, ExportPreset.of(s))
    }

    @Test
    fun a_device_without_facts_still_plans_by_mime() {
        val p = ExportSheetModel.plan(ExportSettings(codec = ExportCodec.HEVC), subject, emptyList())
        assertTrue(p.ok)
        assertNull(p.encoderName)
        assertEquals("video/hevc", p.mime)
    }

    @Test
    fun the_time_estimate_comes_only_from_a_measured_export() {
        assertNull(ExportSheetModel.estimatedSeconds(subject, null))
        assertNull(ExportSheetModel.rateOf(null))
        // last export: 1080p30 for 30 s took 15 s of wall time -> 2x real time
        val report = ExportReport(
            "e", true, "video/avc", 1920, 1080, 30.0, 8, "VBR", null, 1f, 1, 128, 1, 30.0,
            emptyList(), emptyList(), wallMs = 15_000,
        )
        val rate = ExportSheetModel.rateOf(report)
        assertNotNull(rate)
        // a 60 s clip of the same size: 30 s
        assertEquals(30.0, ExportSheetModel.estimatedSeconds(subject, rate)!!, 1e-6)
        // 4K costs 4x the pixels -> 4x the time
        val uhd = subject.copy(width = 3840, height = 2160)
        assertEquals(120.0, ExportSheetModel.estimatedSeconds(uhd, rate)!!, 1e-6)
        // no wall time recorded -> no rate
        assertNull(ExportSheetModel.rateOf(report.copy(wallMs = null)))
    }

    @Test
    fun the_latest_rate_is_the_newest_done_mp4_with_a_report() {
        fun job(id: String, state: State, t: Target, wall: Long?) = ExportJob(
            id, "s", com.ritim.ritimcraw.data.GradeK(), t, state,
            report = ExportReport("e", true, "video/avc", 1000, 1000, 10.0, 8, "VBR", null, 1f, 1, 128, 1, 10.0, emptyList(), emptyList(), wallMs = wall),
        )
        val jobs = listOf(job("a", State.DONE, Target.MP4, 10_000), job("b", State.DONE, Target.MP4, 20_000), job("c", State.FAILED, Target.MP4, 1_000))
        // c failed, so b (the newest DONE) wins: 1000*1000*10*10 px over 20 s
        assertEquals(1000.0 * 1000 * 10 * 10 / 20.0, ExportSheetModel.latestRate(jobs)!!.pixelsPerSecond, 1e-6)
        assertNull(ExportSheetModel.latestRate(emptyList()))
    }

    @Test
    fun profile_choices_per_codec_start_with_auto() {
        assertEquals(listOf(null, 2, 8), ExportSheetModel.profileChoices(ExportCodec.AVC))
        assertEquals(listOf(null, 1), ExportSheetModel.profileChoices(ExportCodec.HEVC))
        assertEquals("High", ExportSheetModel.profileName(ExportCodec.AVC, 8))
        assertEquals("Main", ExportSheetModel.profileName(ExportCodec.HEVC, 1))
    }

    @Test
    fun remembered_settings_round_trip_and_unreadable_text_is_not_guessed() {
        val s = ExportSettings(
            codec = ExportCodec.HEVC, rateMode = ExportRateMode.CBR, bitrateLevel = BitrateLevel.CUSTOM,
            customMbps = 42.5, keyframe = KeyframeInterval.HALF, profile = 1, audioKbps = 256,
        )
        assertEquals(s, ExportSettingsPrefs.decode(ExportSettingsPrefs.encode(s)))
        assertNull(ExportSettingsPrefs.decode(null))
        assertNull(ExportSettingsPrefs.decode("not json"))
        assertNull(ExportSettingsPrefs.decode("{\"v\":99,\"codec\":\"AVC\"}")) // a newer version
    }

    @Test
    fun the_ten_bit_context_needs_every_condition() {
        fun ctx(probe: Boolean = true, orig: Boolean = true, crop: Boolean = true, straight: Boolean = true, gpu: Boolean = true) =
            ExportSheetModel.tenBitContext(probe, orig, crop, straight, gpu).tenBitPathAvailable
        assertTrue(ctx())
        assertFalse(ctx(probe = false))
        assertFalse(ctx(orig = false))
        assertFalse(ctx(crop = false))
        assertFalse(ctx(straight = false))
        assertFalse(ctx(gpu = false))
    }

    @Test
    fun quality_is_10_bit_only_where_the_path_and_the_encoder_allow_it() {
        val tenEnc = listOf(
            EncoderFacts("c2.t.hevc", "video/hevc", true, profiles = setOf(1, 2), colorFormats = setOf(54), maxWidth = 8192, maxHeight = 8192),
        )
        val noTen = listOf(
            EncoderFacts("c2.t.hevc", "video/hevc", true, profiles = setOf(1), colorFormats = setOf(0x7F420888), maxWidth = 8192, maxHeight = 8192),
        )
        assertEquals(10, ExportSheetModel.applyPreset(ExportSettings(), ExportPreset.QUALITY, tenEnc, tenBit = true).bitDepth)
        assertEquals(8, ExportSheetModel.applyPreset(ExportSettings(), ExportPreset.QUALITY, tenEnc, tenBit = false).bitDepth)
        assertEquals(8, ExportSheetModel.applyPreset(ExportSettings(), ExportPreset.QUALITY, noTen, tenBit = true).bitDepth)
        // the 10-bit and 8-bit variants are the same preset, so a resolution change does not turn it Custom
        val q10 = ExportSheetModel.applyPreset(ExportSettings(), ExportPreset.QUALITY, tenEnc, tenBit = true)
        assertEquals(ExportPreset.QUALITY, ExportPreset.of(q10))
        assertEquals(ExportPreset.QUALITY, ExportPreset.of(q10.copy(bitDepth = 8)))
        // 10-bit without HEVC does not exist
        assertEquals(8, ExportPreset.QUALITY.apply(ExportSettings(), hevcAvailable = false, tenBitAvailable = true).bitDepth)
    }

    @Test
    fun a_ten_bit_request_the_path_cannot_serve_plans_8_bit_with_a_fix() {
        val facts = listOf(
            EncoderFacts("c2.t.hevc", "video/hevc", true, profiles = setOf(1, 2), colorFormats = setOf(54), maxWidth = 8192, maxHeight = 8192),
        )
        val s = ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10)
        val no = ExportSheetModel.plan(s, subject, facts, ExportContext(tenBitPathAvailable = false))
        assertEquals(8, no.bitDepth)
        val issue = no.issues.single { it.kind == ExportIssue.Kind.TEN_BIT_UNAVAILABLE }
        assertEquals(8, issue.fix!!.bitDepth)
        val yes = ExportSheetModel.plan(s, subject, facts, ExportContext(tenBitPathAvailable = true))
        assertEquals(10, yes.bitDepth)
        assertEquals(2, yes.profile)
    }

    // ---- bitrate ladder up to the encoder's ceiling (P2, 2026-11-25) ----

    private val uhd = ExportSheetModel.Subject(3840, 2160, 30.0, 60.0)
    private fun hevcUhd(level: BitrateLevel, extra: ExportSettings.() -> ExportSettings = { this }) =
        ExportSheetModel.plan(
            ExportSettings(codec = ExportCodec.HEVC, bitrateLevel = level).extra(), uhd, hevcFacts,
        )

    @Test
    fun the_sheet_offers_the_extra_steps_when_the_encoder_lists_a_ceiling() {
        val plan = hevcUhd(BitrateLevel.STANDARD)
        assertEquals(
            listOf("LOW", "STANDARD", "HIGH", "MAX", "HIGHER", "CEILING", "CUSTOM"),
            ExportSheetModel.offeredBitrateLevels(plan, hevcFacts, BitrateLevel.STANDARD).map { it.name },
        )
    }

    @Test
    fun without_a_listed_ceiling_the_extra_steps_are_not_offered_unless_already_chosen() {
        val noCeiling = hevcFacts.map { EncoderFacts(it.name, it.mime, it.hardware, maxWidth = 8192, maxHeight = 8192) }
        val plan = ExportSheetModel.plan(ExportSettings(codec = ExportCodec.HEVC), uhd, noCeiling)
        assertEquals(
            listOf("LOW", "STANDARD", "HIGH", "MAX", "CUSTOM"),
            ExportSheetModel.offeredBitrateLevels(plan, noCeiling, BitrateLevel.STANDARD).map { it.name },
        )
        // A saved CEILING pick never vanishes from its own picker.
        assertTrue(BitrateLevel.CEILING in ExportSheetModel.offeredBitrateLevels(plan, noCeiling, BitrateLevel.CEILING))
    }

    @Test
    fun the_ceiling_step_plans_exactly_the_listed_ceiling_with_no_warning() {
        val p = hevcUhd(BitrateLevel.CEILING)
        assertEquals(160_000_000, p.bitrateBps)
        assertTrue(p.issues.none { it.kind == ExportIssue.Kind.BITRATE_ABOVE_CEILING })
        val h = hevcUhd(BitrateLevel.HIGHER)
        assertEquals(117_500_000, h.bitrateBps)
        // MAX itself is untouched (preset meaning does not change).
        assertEquals(75_000_000, hevcUhd(BitrateLevel.MAX).bitrateBps)
    }

    @Test
    fun custom_is_free_up_to_the_ceiling_and_above_it_only_with_uncapped() {
        fun custom(mbps: Double, uncapped: Boolean) =
            hevcUhd(BitrateLevel.CUSTOM) { copy(customMbps = mbps, uncapped = uncapped) }
        assertEquals(160_000_000, custom(160.0, false).bitrateBps)
        assertTrue(custom(160.0, false).issues.none { it.kind == ExportIssue.Kind.BITRATE_ABOVE_CEILING })
        val clamped = custom(200.0, false)
        assertEquals(160_000_000, clamped.bitrateBps)
        assertTrue(clamped.issues.any { it.kind == ExportIssue.Kind.BITRATE_ABOVE_CEILING })
        val over = custom(200.0, true)
        assertEquals(200_000_000, over.bitrateBps)
        assertTrue(over.issues.any { it.kind == ExportIssue.Kind.BITRATE_UNCAPPED_RISK })
    }
}
