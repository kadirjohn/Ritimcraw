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
}
