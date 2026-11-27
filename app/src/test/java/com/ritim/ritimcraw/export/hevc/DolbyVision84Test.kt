package com.ritim.ritimcraw.export.hevc

import com.ritim.ritimcraw.export.hevc.DolbyVision84.DvLevel
import com.ritim.ritimcraw.export.hevc.DolbyVision84.DvProbeResult
import com.ritim.ritimcraw.export.hevc.DolbyVision84.DvVerdict
import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcProbeResult
import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dolby Vision 8.4 capability-table tests (package E3, night
 * 2026-11-27). Every probe result here is SYNTHETIC — a clearly
 * labeled invented device fact set (the model is host-testable; the
 * real device facts arrive when the device leg probes). The tests pin
 * the DECISION RULES and the CONSTANTS, never any real device's
 * support. Every numeric constant was verified against the installed
 * android-34 AND android-36 SDK jars with javap (see the report); a
 * real video/dolby-vision encode is DOĞRULANMADI (not verified).
 */
class DolbyVision84Test {

    // ---- SYNTHETIC probe builders (the device facts are INVENTED here) ----

    /**
     * A SYNTHETIC all-supporting device: a DV encoder that lists
     * DvheSt, every level the table names, 10-bit input, any size.
     * The shape follows plan §13.13's 15-Ultra dump (c2.qti.dv.encoder,
     * DvheSt/8k60) — but the FACTS here are invented, only the name is
     * quoted from the plan's dump.
     */
    private fun syntheticAllSupported(): DvProbeResult = DvProbeResult(
        dvEncoderName = "c2.qti.dv.encoder",
        listsDvheSt = true,
        listedLevels = setOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048),
        acceptsTenBitInput = true,
        sizeRateAccepted = true,
    )

    private fun syntheticNone(): DvProbeResult = DvProbeResult(
        dvEncoderName = null,
        listsDvheSt = false,
        listedLevels = emptySet(),
        acceptsTenBitInput = false,
        sizeRateAccepted = false,
    )

    /** A SYNTHETIC API-37 HLG gate verdict: hlg-editing probed true. */
    private fun hlgSupported(): HevcVerdict = HevcPreflight.decide(
        HevcProfile.HLG,
        HevcProbeResult(
            hevcEncoderName = "c2.qti.hevc.encoder",
            listedProfiles = listOf(1, 2, 4096),
            acceptsP010 = true,
            sizeRateAccepted = true,
            deviceApiLevel = 37,
            hdrEditingSupported = true,
            hlgEditingSupported = true,
            avcEncoderName = "c2.qti.avc.encoder",
        ),
    )

    /** The same gate facts WITHOUT hlg-editing: the HLG verdict refuses. */
    private fun hlgRefused(): HevcVerdict = HevcPreflight.decide(
        HevcProfile.HLG,
        HevcProbeResult(
            hevcEncoderName = "c2.qti.hevc.encoder",
            listedProfiles = listOf(1, 2, 4096),
            acceptsP010 = true,
            sizeRateAccepted = true,
            deviceApiLevel = 37,
            hdrEditingSupported = false,
            hlgEditingSupported = false,
            avcEncoderName = "c2.qti.avc.encoder",
        ),
    )

    // ---- the constants (verified against the installed SDK jars) ----

    @Test
    fun the_constants_are_the_android_jar_values() {
        // javap android-34 AND android-36: MIMETYPE_VIDEO_DOLBY_VISION =
        // "video/dolby-vision", DolbyVisionProfileDvheSt = 256,
        // Uhd24=32, Uhd30=64, Uhd60=256, Fhd24=4, Fhd30=8, Fhd60=16.
        assertEquals("video/dolby-vision", DolbyVision84.MIME)
        assertEquals(256, DolbyVision84.PROFILE_DVHE_ST)
        assertEquals(32, DolbyVision84.LEVEL_UHD24)
        assertEquals(64, DolbyVision84.LEVEL_UHD30)
        assertEquals(256, DolbyVision84.LEVEL_UHD60)
        assertEquals(4, DolbyVision84.LEVEL_FHD24)
        assertEquals(8, DolbyVision84.LEVEL_FHD30)
        assertEquals(16, DolbyVision84.LEVEL_FHD60)
    }

    // ---- the level table (plan §13.3's rows) ----

    @Test
    fun uhd_landscape_resolves_the_plan_s_own_rows() {
        // Plan 13.3: UHD 3840x2160, 24 fps -> level 06, 30 -> 07, 60 -> 09.
        assertEquals(DvLevel(DolbyVision84.LEVEL_UHD24, "06"), DolbyVision84.levelFor(3840, 2160, 24.0))
        assertEquals(DvLevel(DolbyVision84.LEVEL_UHD30, "07"), DolbyVision84.levelFor(3840, 2160, 30.0))
        assertEquals(DvLevel(DolbyVision84.LEVEL_UHD60, "09"), DolbyVision84.levelFor(3840, 2160, 60.0))
        // The fps BANDS, not equality: 25 fps is the 30-row, 50 the 60-row.
        assertEquals(DolbyVision84.LEVEL_UHD30, DolbyVision84.levelFor(3840, 2160, 25.0)!!.code)
        assertEquals(DolbyVision84.LEVEL_UHD60, DolbyVision84.levelFor(3840, 2160, 50.0)!!.code)
        assertEquals(DolbyVision84.LEVEL_UHD24, DolbyVision84.levelFor(3840, 2160, 23.976)!!.code)
    }

    @Test
    fun portrait_uhd_resolves_the_same_rows_and_fhd_has_its_own() {
        // Portrait 4K (2160x3840 — the brief's portrait case): same rows.
        assertEquals(DolbyVision84.LEVEL_UHD30, DolbyVision84.levelFor(2160, 3840, 30.0)!!.code)
        // FHD: its own rows (Fhd24=4, Fhd30=8, Fhd60=16).
        assertEquals(DolbyVision84.LEVEL_FHD24, DolbyVision84.levelFor(1920, 1080, 24.0)!!.code)
        assertEquals(DolbyVision84.LEVEL_FHD30, DolbyVision84.levelFor(1920, 1080, 30.0)!!.code)
        assertEquals(DolbyVision84.LEVEL_FHD60, DolbyVision84.levelFor(1920, 1080, 60.0)!!.code)
        // A size between FHD and UHD takes the UHD class (the higher row).
        assertEquals(DolbyVision84.LEVEL_UHD30, DolbyVision84.levelFor(2560, 1440, 30.0)!!.code)
    }

    @Test
    fun sizes_above_uhd_and_rates_above_60_answer_null_never_a_guess() {
        // 8K: the plan quotes no row for it -> null, "reported never guessed".
        assertNull(DolbyVision84.levelFor(7680, 4320, 30.0))
        // 120 fps: no row above 60 -> null.
        assertNull(DolbyVision84.levelFor(3840, 2160, 120.0))
        // Broken input is an error, not a null.
        assertThrows(IllegalArgumentException::class.java) { DolbyVision84.levelFor(0, 2160, 30.0) }
        assertThrows(IllegalArgumentException::class.java) { DolbyVision84.levelFor(3840, 2160, 0.0) }
    }

    // ---- the capability/decision table (the brief's "her satır: girdi → beklenti") ----

    @Test
    fun row1_dv_encoder_present_and_everything_probed_true_is_supported() {
        val verdict = DolbyVision84.decide(syntheticAllSupported(), hlgSupported(), 3840, 2160, 30.0)
        assertTrue(verdict is DvVerdict.Supported)
        val supported = verdict as DvVerdict.Supported
        assertEquals("c2.qti.dv.encoder", supported.codecName)
        assertEquals(DolbyVision84.LEVEL_UHD30, supported.level.code)
        assertEquals("07", supported.level.name)
    }

    @Test
    fun row2_no_dv_encoder_falls_back_to_hlg_when_hlg_is_supported() {
        val verdict = DolbyVision84.decide(syntheticNone(), hlgSupported(), 3840, 2160, 30.0)
        assertTrue(verdict is DvVerdict.Refused)
        val refused = verdict as DvVerdict.Refused
        assertEquals(HevcPreflight.HevcRefusalStage.CODEC_LIST, refused.stage)
        assertEquals("none", refused.codecName)
        // The fallback is the USER'S choice: HLG (the gate said Supported).
        assertEquals(DolbyVision84.FallbackRange.HLG, refused.fallback.range)
        assertTrue(refused.fallback.offerText.contains("HLG"))
        assertTrue(refused.fallback.offerText.contains("never a silent fallback"))
    }

    @Test
    fun row3_no_dv_encoder_and_no_hlg_falls_back_to_sdr() {
        // The E-brief's "encoder var mı → DV 8.4 / HLG / SDR geri dönüş":
        // nothing HDR exists -> the honest SDR fallback, still a choice.
        val verdict = DolbyVision84.decide(syntheticNone(), hlgRefused(), 3840, 2160, 30.0)
        val refused = verdict as DvVerdict.Refused
        assertEquals(HevcPreflight.HevcRefusalStage.CODEC_LIST, refused.stage)
        assertEquals(DolbyVision84.FallbackRange.SDR, refused.fallback.range)
        assertTrue(refused.fallback.offerText.contains("SDR"))
    }

    @Test
    fun row4_dv_encoder_without_dvhe_st_refuses_at_the_profile_stage() {
        val probe = syntheticAllSupported().copy(listsDvheSt = false)
        val verdict = DolbyVision84.decide(probe, hlgSupported(), 3840, 2160, 30.0)
        val refused = verdict as DvVerdict.Refused
        assertEquals(HevcPreflight.HevcRefusalStage.PROFILE_LEVEL, refused.stage)
        assertEquals("c2.qti.dv.encoder", refused.codecName)
        assertTrue(refused.reason.contains("DvheSt"))
        assertTrue(refused.reason.contains("256"))
        assertEquals(DolbyVision84.FallbackRange.HLG, refused.fallback.range)
    }

    @Test
    fun row5_level_unlisted_by_the_encoder_refuses_naming_the_level() {
        // The encoder lists DvheSt but not the job's level (07 = 64):
        // PROFILE_LEVEL again, the level named in the reason.
        val probe = syntheticAllSupported().copy(listedLevels = setOf(32, 256))
        val verdict = DolbyVision84.decide(probe, hlgSupported(), 3840, 2160, 30.0)
        val refused = verdict as DvVerdict.Refused
        assertEquals(HevcPreflight.HevcRefusalStage.PROFILE_LEVEL, refused.stage)
        assertTrue(refused.reason.contains("07"))
        assertEquals(DolbyVision84.FallbackRange.HLG, refused.fallback.range)
    }

    @Test
    fun row6_size_rate_refused_refuses_at_the_size_stage() {
        val probe = syntheticAllSupported().copy(sizeRateAccepted = false)
        val verdict = DolbyVision84.decide(probe, hlgSupported(), 3840, 2160, 30.0)
        val refused = verdict as DvVerdict.Refused
        assertEquals(HevcPreflight.HevcRefusalStage.SIZE_RATE, refused.stage)
        assertEquals("c2.qti.dv.encoder", refused.codecName)
    }

    @Test
    fun the_stage_order_names_the_first_failure() {
        // No encoder AND no DvheSt AND size refused: the codec list wins.
        val verdict = DolbyVision84.decide(syntheticNone(), hlgSupported(), 3840, 2160, 30.0)
        assertEquals(HevcPreflight.HevcRefusalStage.CODEC_LIST, (verdict as DvVerdict.Refused).stage)
        // DvheSt missing AND size refused: the profile stage wins.
        val both = syntheticAllSupported().copy(listsDvheSt = false, sizeRateAccepted = false)
        val verdict2 = DolbyVision84.decide(both, hlgSupported(), 3840, 2160, 30.0)
        assertEquals(HevcPreflight.HevcRefusalStage.PROFILE_LEVEL, (verdict2 as DvVerdict.Refused).stage)
        // Level unlisted AND size refused: the level (profile stage) wins.
        val both2 = syntheticAllSupported().copy(listedLevels = emptySet(), sizeRateAccepted = false)
        val verdict3 = DolbyVision84.decide(both2, hlgSupported(), 3840, 2160, 30.0)
        assertEquals(HevcPreflight.HevcRefusalStage.PROFILE_LEVEL, (verdict3 as DvVerdict.Refused).stage)
    }

    @Test
    fun the_decision_is_pure_the_same_facts_give_the_same_verdict() {
        val probe = syntheticNone()
        assertEquals(
            DolbyVision84.decide(probe, hlgSupported(), 3840, 2160, 30.0).toString(),
            DolbyVision84.decide(probe, hlgSupported(), 3840, 2160, 30.0).toString(),
        )
    }

    // ---- the configuration model (the device leg's MediaFormat contract) ----

    @Test
    fun the_configuration_is_the_hlg_base_layer_in_the_dv_mime() {
        val config = DolbyVision84.Dv84Configuration(
            codecName = "c2.qti.dv.encoder",
            level = DolbyVision84.levelFor(3840, 2160, 30.0)!!,
        )
        // DV 8.4 = an HLG base layer: the COLOR VALUES are HevcProfile's
        // own HLG contract (never re-derived), the MIME/profile are DV's.
        assertEquals("video/dolby-vision", config.mime)
        assertEquals(DolbyVision84.PROFILE_DVHE_ST, config.profile)
        assertEquals(HevcProfile.HLG.colorStandard, config.colorStandard)   // BT2020 (6)
        assertEquals(HevcProfile.HLG.colorTransfer, config.colorTransfer)   // HLG (7)
        assertEquals(HevcProfile.HLG.colorRange, config.colorRange)       // limited (2)
        assertEquals(6, config.colorStandard)
        assertEquals(7, config.colorTransfer)
        assertEquals(2, config.colorRange)
        assertEquals(10, config.bitDepth)
        assertEquals(DolbyVision84.LEVEL_UHD30, config.levelCode)
    }

    @Test
    fun media_format_entries_use_the_same_key_order_as_hevc_profile() {
        val config = DolbyVision84.Dv84Configuration(
            "c2.qti.dv.encoder",
            DolbyVision84.levelFor(3840, 2160, 30.0)!!,
        )
        // HevcProfile.mediaFormatEntries' own order, so the device leg
        // sets a DV format with the same code it sets an HLG one.
        assertEquals(
            listOf("mime", "profile", "level", "color-standard", "color-range", "color-transfer"),
            config.mediaFormatEntries().map { it.key },
        )
        val entries = config.mediaFormatEntries().associate { it.key to it.value }
        assertEquals("video/dolby-vision", entries["mime"])
        assertEquals("256", entries["profile"])
        assertEquals("64", entries["level"])
        assertEquals("6", entries["color-standard"])
        assertEquals("2", entries["color-range"])
        assertEquals("7", entries["color-transfer"])
    }

    @Test
    fun the_trial_key_carries_every_component_and_moves_with_each_one() {
        // The HevcTrialCache-compatible component list (plan E2 2.2):
        // the format contract + size + API + codec name + Build.ID.
        val config = DolbyVision84.Dv84Configuration(
            "c2.qti.dv.encoder",
            DolbyVision84.levelFor(3840, 2160, 30.0)!!,
        )
        val base = config.trialKeyComponents(3840, 2160, 37, "AQ3A.240812.001")
        // The six format entries are IN the key.
        for (entry in config.mediaFormatEntries()) {
            assertTrue("missing ${entry.key}", base.contains("${entry.key}=${entry.value}"))
        }
        assertTrue(base.contains("3840x2160"))
        assertTrue(base.contains("api=37"))
        assertTrue(base.contains("codec=c2.qti.dv.encoder"))
        assertTrue(base.contains("build=AQ3A.240812.001"))
        // Each component moves the key: size, API, codec, build, level.
        val size = config.trialKeyComponents(2160, 3840, 37, "AQ3A.240812.001")
        val api = config.trialKeyComponents(3840, 2160, 34, "AQ3A.240812.001")
        val codec = config.trialKeyComponents(3840, 2160, 37, "c2.other.dv.encoder")
        val build = config.trialKeyComponents(3840, 2160, 37, "B.002")
        val level = config.copy(level = DolbyVision84.levelFor(3840, 2160, 60.0)!!)
            .trialKeyComponents(3840, 2160, 37, "AQ3A.240812.001")
        for (other in listOf(size, api, codec, build, level)) {
            assertTrue("a component did not move the key: $other", base != other)
        }
        // The level component specifically: 30-fps (07) vs 60-fps (09).
        assertTrue(level.contains("level=256") && base.contains("level=64"))
    }

    // ---- compatibility with the HLG model (regression pins) ----

    @Test
    fun the_hlg_gate_verdict_decides_the_fallback_and_hlg_decisions_are_unchanged() {
        // The two models share the seam: the SAME HevcPreflight verdict
        // that gated HLG gates DV's fallback. The HLG verdict itself is
        // HevcPreflight's own — this pins that DV consumes it WITHOUT
        // changing it (the pre-decide and post-decide verdicts agree).
        val gate = hlgSupported()
        val before = gate.toString()
        DolbyVision84.decide(syntheticNone(), gate, 3840, 2160, 30.0)
        assertEquals(before, gate.toString())
        assertTrue(gate is HevcVerdict.Supported)
        // And the HLG profile's own contract is what DV's base layer
        // reuses (the values above); HLG needs no static info still.
        assertTrue(!HevcProfile.HLG.requiresHdrStaticInfo)
        // The HLG decision table's API-37 row still holds: no trial flag.
        assertTrue(!(gate as HevcVerdict.Supported).trialEncodeRequired)
    }

    @Test
    fun a_refused_hlg_gate_makes_dv_fall_back_to_sdr_not_hlg() {
        // Integration honesty: when HLG itself refused, DV's fallback
        // must NOT offer HLG (the range it could not gate) — SDR is
        // the honest offer. Every refusing row carries the same rule.
        for (probe in listOf(syntheticNone(), syntheticAllSupported().copy(listsDvheSt = false))) {
            val refused = DolbyVision84.decide(probe, hlgRefused(), 3840, 2160, 30.0) as DvVerdict.Refused
            assertEquals(DolbyVision84.FallbackRange.SDR, refused.fallback.range)
        }
    }
}
