package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.export.hevc.HevcPreflight
import com.ritim.ritimcraw.export.hevc.HevcProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The G4 probe fold ([HevcProbeSource.fromFacts]): the pure bridge from
 * the export side's probed EncoderFacts to the HLG preflight's
 * HevcProbeResult. Every fact set here is SYNTHETIC (the 15 Ultra's
 * declared shape, invented for the test) — the model's own rule (no
 * support inferred from a name); the JVM pins the FOLD, the device leg
 * proves the real codec.
 */
class HevcProbeSourceTest {

    private val SURFACE = 0x7F000789
    private val P010 = 54

    private fun probe(maxW: Int, maxH: Int) = SizeRateProbe { w, h, _ ->
        (w <= maxW && h <= maxH) || (w <= maxH && h <= maxW)
    }

    /** A SYNTHETIC API-37 all-supporting fact set (the 15 Ultra's shape). */
    private fun ultraFacts(): List<EncoderFacts> = listOf(
        EncoderFacts(
            "c2.qti.hevc.encoder", "video/hevc", hardware = true,
            profiles = setOf(1, 2, 4096), colorFormats = setOf(SURFACE, P010),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000,
            maxWidth = 8192, maxHeight = 8192,
            features = setOf("hlg-editing", "hdr-editing"),
            sizeRate = probe(8192, 8192),
        ),
        EncoderFacts(
            "c2.qti.avc.encoder", "video/avc", hardware = true,
            profiles = setOf(1, 2, 8), colorFormats = setOf(SURFACE),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..220_000_000,
            maxWidth = 8192, maxHeight = 4320, sizeRate = probe(8192, 4320),
        ),
    )

    // ---- the fold copies every fact honestly ----

    @Test
    fun the_fold_copies_the_hevc_encoder_s_name_and_profiles() {
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 3840, 2160, 30.0, apiLevel = 37)
        assertEquals("c2.qti.hevc.encoder", gate.hevcEncoderName)
        assertEquals(listOf(1, 2, 4096), gate.listedProfiles)
        assertEquals("c2.qti.avc.encoder", gate.avcEncoderName)
        assertTrue(gate.avcAvailable)
    }

    @Test
    fun the_fold_carries_the_p010_and_size_rate_facts() {
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 3840, 2160, 30.0, apiLevel = 37)
        assertTrue(gate.acceptsP010)
        assertTrue(gate.sizeRateAccepted)
        // The features the gates consult:
        assertTrue(gate.hlgEditingSupported)
        assertTrue(gate.hdrEditingSupported)
        assertEquals(37, gate.deviceApiLevel)
    }

    @Test
    fun a_refused_size_maps_to_size_rate_accepted_false_never_a_silent_true() {
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 8192, 8192, 30.0, apiLevel = 37)
        // The AVC probe's portrait box is 8192x4320; the HEVC one here
        // accepts 8192x8192, so a landscape 8192x8192 job maps TRUE...
        assertTrue(gate.sizeRateAccepted)
        // ...and a size NO encoder lists maps FALSE (the preflight then
        // refuses at SIZE_RATE — the honest refusal, never a guess).
        val refused = HevcProbeSource.fromFacts(ultraFacts(), 99999, 99999, 30.0, apiLevel = 37)
        assertFalse(refused.sizeRateAccepted)
    }

    @Test
    fun no_facts_refuse_at_the_codec_list_stage_never_assume_support() {
        // The probe failed / a JVM test: the honest "nothing was measured"
        // shape — the verdict refuses at CODEC_LIST, the planner degrades.
        val gate = HevcProbeSource.fromFacts(emptyList(), 3840, 2160, 30.0, apiLevel = 37)
        assertNull(gate.hevcEncoderName)
        assertNull(gate.avcEncoderName)
        val verdict = HevcPreflight.decide(HevcProfile.HLG, gate)
        assertTrue(verdict is HevcPreflight.HevcVerdict.Refused)
        assertEquals(
            HevcPreflight.HevcRefusalStage.CODEC_LIST,
            (verdict as HevcPreflight.HevcVerdict.Refused).stage,
        )
    }

    // ---- the API guard: hlg-editing is only REPORTED at API 35+ ----

    @Test
    fun below_api_35_the_hlg_flag_is_reported_false_even_when_the_string_was_probed() {
        // API 34 cannot have hlg-editing; the fold reports false so the
        // preflight walks the FEATURE_HdrEditing fallback (the guard data
        // HevcProbeResult's KDoc pins).
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 3840, 2160, 30.0, apiLevel = 34)
        assertFalse(gate.hlgEditingSupported)
        assertTrue(gate.hdrEditingSupported)
        val verdict = HevcPreflight.decide(HevcProfile.HLG, gate)
        assertTrue(verdict is HevcPreflight.HevcVerdict.Supported)
        assertTrue((verdict as HevcPreflight.HevcVerdict.Supported).trialEncodeRequired)
    }

    @Test
    fun at_api_35_plus_the_hlg_flag_is_the_probed_string() {
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 3840, 2160, 30.0, apiLevel = 37)
        assertTrue(gate.hlgEditingSupported)
        val verdict = HevcPreflight.decide(HevcProfile.HLG, gate)
        assertTrue(verdict is HevcPreflight.HevcVerdict.Supported)
        assertFalse((verdict as HevcPreflight.HevcVerdict.Supported).trialEncodeRequired)
    }

    @Test
    fun a_device_without_the_hlg_feature_refuses_honestly() {
        val noHlg = ultraFacts().map {
            if (it.mime == "video/hevc") it.let { f -> EncoderFacts(
                f.name, f.mime, f.hardware, f.profiles, f.colorFormats, f.bitrateModes,
                f.bitrateRangeBps, f.maxWidth, f.maxHeight, f.supportedFps,
                f.features - "hlg-editing", f.sizeRate,
            ) } else it
        }
        val gate = HevcProbeSource.fromFacts(noHlg, 3840, 2160, 30.0, apiLevel = 37)
        assertFalse(gate.hlgEditingSupported)
        val verdict = HevcPreflight.decide(HevcProfile.HLG, gate)
        assertTrue(verdict is HevcPreflight.HevcVerdict.Refused)
        val refused = verdict as HevcPreflight.HevcVerdict.Refused
        assertEquals(HevcPreflight.HevcRefusalStage.FEATURE_QUERY, refused.stage)
        // The refusal carries its explicit offer (an AVC encoder exists).
        assertNotNull(refused.degradedOffer)
    }

    // ---- the encoder pick: the verdict names the planner's own choice ----

    @Test
    fun the_hevc_pick_skips_the_hdr_variant_and_the_cq_encoder() {
        // The .hdr and .cq variants are NOT the general video encoder
        // (EncoderFacts.Role); the fold must never name them.
        val withVariants = ultraFacts() + listOf(
            EncoderFacts(
                "c2.qti.hevc.encoder.hdr", "video/hevc", hardware = true,
                profiles = setOf(2, 4096), colorFormats = setOf(SURFACE, P010),
                maxWidth = 4096, maxHeight = 2176,
                features = setOf("hlg-editing", "hdr-editing"),
            ),
            EncoderFacts(
                "c2.qti.hevc.encoder.cq", "video/hevc", hardware = true,
                profiles = setOf(2), colorFormats = setOf(SURFACE, P010),
                bitrateModes = setOf(ExportRateMode.CQ.mediaCodecMode),
                maxWidth = 512, maxHeight = 512,
            ),
        )
        val gate = HevcProbeSource.fromFacts(withVariants, 3840, 2160, 30.0, apiLevel = 37)
        assertEquals("c2.qti.hevc.encoder", gate.hevcEncoderName)
    }

    @Test
    fun a_main10_less_hevc_encoder_is_not_picked_and_hlg_refuses_at_the_codec_stage() {
        // HLG IS Main10: an encoder that does not list it cannot encode
        // HLG either. The fold's pick REQUIRES a 10-bit-capable Main10
        // encoder (the planner's own shape), so a Main10-less list names
        // NO encoder — the verdict refuses at CODEC_LIST (never a silent
        // Main10-less HLG file, and never assuming the encoder could).
        val noMain10 = listOf(
            EncoderFacts(
                "c2.x.hevc.encoder", "video/hevc", hardware = true,
                profiles = setOf(1), colorFormats = setOf(SURFACE),
                maxWidth = 4096, maxHeight = 4096,
                features = setOf("hlg-editing", "hdr-editing"),
            ),
        )
        val gate = HevcProbeSource.fromFacts(noMain10, 3840, 2160, 30.0, apiLevel = 37)
        assertNull(gate.hevcEncoderName)
        val verdict = HevcPreflight.decide(HevcProfile.HLG, gate)
        assertEquals(
            HevcPreflight.HevcRefusalStage.CODEC_LIST,
            (verdict as HevcPreflight.HevcVerdict.Refused).stage,
        )
    }

    // ---- end to end: the fold feeds the planner's HLG branch ----

    @Test
    fun the_gate_from_real_shaped_facts_lets_the_planner_resolve_hlg() {
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 3840, 2160, 30.0, apiLevel = 37)
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate)
        val plan = ExportPlanner.resolve(
            ExportSettings(
                codec = ExportCodec.HEVC, bitDepth = 10,
                dynamicRange = ExportDynamicRange.HLG,
            ),
            ExportInput(3840, 2160, 30.0), ultraFacts(), ctx,
        )
        assertEquals(ExportDynamicRange.HLG, plan.effective.dynamicRange)
        assertEquals(ExportColorTags.Hlg, plan.colorTags)
        assertTrue(plan.ok)
    }

    @Test
    fun an_api_34_gate_from_the_fold_marks_the_plan_with_the_trial_issue() {
        val gate = HevcProbeSource.fromFacts(ultraFacts(), 3840, 2160, 30.0, apiLevel = 34)
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate)
        val plan = ExportPlanner.resolve(
            ExportSettings(
                codec = ExportCodec.HEVC, bitDepth = 10,
                dynamicRange = ExportDynamicRange.HLG,
            ),
            ExportInput(3840, 2160, 30.0), ultraFacts(), ctx,
        )
        assertEquals(ExportDynamicRange.HLG, plan.effective.dynamicRange)
        val trial = plan.issues.single { it.kind == ExportIssue.Kind.HLG_TRIAL_REQUIRED }
        assertEquals(ExportIssue.Severity.INFO, trial.severity)
    }

    @Test
    fun a_featureless_gate_from_the_fold_keeps_the_e1_wall() {
        val noHlg = ultraFacts().map {
            if (it.mime == "video/hevc") EncoderFacts(
                it.name, it.mime, it.hardware, it.profiles, it.colorFormats, it.bitrateModes,
                it.bitrateRangeBps, it.maxWidth, it.maxHeight, it.supportedFps,
                emptySet(), it.sizeRate,
            ) else it
        }
        val gate = HevcProbeSource.fromFacts(noHlg, 3840, 2160, 30.0, apiLevel = 34)
        val plan = ExportPlanner.resolve(
            ExportSettings(
                codec = ExportCodec.HEVC, bitDepth = 10,
                dynamicRange = ExportDynamicRange.HLG,
            ),
            ExportInput(3840, 2160, 30.0), ultraFacts(),
            ExportContext(tenBitPathAvailable = true, hlgGate = gate),
        )
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        assertTrue(
            plan.issues.any { it.kind == ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE },
        )
    }
}
