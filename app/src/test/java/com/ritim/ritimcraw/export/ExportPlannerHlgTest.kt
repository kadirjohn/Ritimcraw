package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.export.ExportIssue.Kind
import com.ritim.ritimcraw.export.ExportIssue.Severity
import com.ritim.ritimcraw.export.hevc.HevcPreflight
import com.ritim.ritimcraw.export.hevc.HevcProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The planner's HLG branch (E2-a, plan 6.1.4): dynamicRange=HLG crossing
 * the gate into a Main10/BT2020/HLG plan, the E1 wall standing when the
 * gate is absent, and the wire name keeping old rows loadable. Pure JVM;
 * the gate facts are SYNTHETIC (the device leg probes them live).
 */
class ExportPlannerHlgTest {

    private val SURFACE = 0x7F000789
    private val P010 = 54
    private val RGBA1010102 = 0x7F00AAA2

    private fun probe(maxW: Int, maxH: Int, maxFps: Double) = SizeRateProbe { w, h, fps ->
        (w <= maxW && h <= maxH || w <= maxH && h <= maxW) && fps <= maxFps
    }

    /** The 15 Ultra's general HEVC encoder + the AVC baseline (plan 13.13). */
    private val ultra = listOf(
        EncoderFacts(
            "c2.qti.hevc.encoder", "video/hevc", hardware = true,
            profiles = setOf(1, 2, 4096), colorFormats = setOf(SURFACE, P010, RGBA1010102),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000,
            maxWidth = 8192, maxHeight = 8192, features = setOf("hlg-editing", "hdr-editing"),
            sizeRate = probe(8192, 8192, 480.0),
        ),
        EncoderFacts(
            "c2.qti.avc.encoder", "video/avc", hardware = true,
            profiles = setOf(1, 2, 8), colorFormats = setOf(SURFACE, P010, RGBA1010102),
            bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..220_000_000,
            maxWidth = 8192, maxHeight = 4320, sizeRate = probe(8192, 4320, 240.0),
        ),
    )

    private val uhd30 = ExportInput(3840, 2160, 30.0)

    /** A SYNTHETIC API-37 gate verdict: hlg-editing probed true. */
    private fun gate(api: Int = 37, hlg: Boolean = true, hdr: Boolean = true): HevcPreflight.HevcProbeResult =
        HevcPreflight.HevcProbeResult(
            hevcEncoderName = "c2.qti.hevc.encoder",
            listedProfiles = listOf(1, 2, 4096),
            acceptsP010 = true,
            sizeRateAccepted = true,
            deviceApiLevel = api,
            hdrEditingSupported = hdr,
            hlgEditingSupported = hlg,
            avcEncoderName = "c2.qti.avc.encoder",
        )

    private fun hlgSettings() = ExportSettings(
        codec = ExportCodec.HEVC, bitDepth = 10, dynamicRange = ExportDynamicRange.HLG,
    )

    private fun ExportPlan.kinds() = issues.map { it.kind }

    // ---- the gate open: HLG lands in the plan ----

    @Test
    fun hlg_with_the_gate_resolves_to_hevc_main10_with_hlg_color_tags() {
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate())
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ctx)
        // The plan IS HLG: the range survived, the contract landed.
        assertEquals(ExportDynamicRange.HLG, plan.effective.dynamicRange)
        assertEquals(ExportCodec.HEVC, plan.effective.codec)
        assertEquals("video/hevc", plan.mime)
        assertEquals(10, plan.bitDepth)
        assertEquals(2, plan.profile)                  // Main10
        assertEquals(ExportColorTags.Hlg, plan.colorTags)
        assertEquals(6, plan.colorTags.colorStandard)   // BT2020
        assertEquals(7, plan.colorTags.colorTransfer)   // HLG
        assertEquals(2, plan.colorTags.colorRange)
        assertEquals("c2.qti.hevc.encoder", plan.encoderName)
        assertTrue(plan.ok)
        // The request was HLG and the plan delivers HLG: no range issue.
        assertFalse(Kind.DYNAMIC_RANGE_UNAVAILABLE in plan.kinds())
    }

    @Test
    fun hlg_carries_the_hlg_tags_into_the_format_spec() {
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate())
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ctx)
        val spec = ExportFormats.video(plan, surface = true)
        // Plan 4.1's MediaFormat contract, as the writer will set it:
        // the color keys are HLG's, the SDR keys are gone.
        assertEquals(6, spec.int(android.media.MediaFormat.KEY_COLOR_STANDARD))
        assertEquals(2, spec.int(android.media.MediaFormat.KEY_COLOR_RANGE))
        assertEquals(7, spec.int(android.media.MediaFormat.KEY_COLOR_TRANSFER))
        assertEquals(2, spec.int(android.media.MediaFormat.KEY_PROFILE))
        // KEY_LEVEL is NOT a plan field: the writer sets profile alone
        // (today's contract); HevcProfile.HLG's own contract pairs
        // profile+level for the device leg (its entries test pins that).
        assertNull(spec.values[android.media.MediaFormat.KEY_LEVEL])
        // KEY_HDR_STATIC_INFO is never set (HLG is scene-referred).
        assertNull(spec.values[android.media.MediaFormat.KEY_HDR_STATIC_INFO])
    }

    @Test
    fun hlg_overrides_a_lower_request_depth_and_codec_never_silently() {
        // The user asked 8-bit AVC and HLG: HLG IS Main10/HEVC, so the
        // plan moves the request TO the profile — and the issues say so.
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate())
        val plan = ExportPlanner.resolve(
            ExportSettings(codec = ExportCodec.AVC, bitDepth = 8, dynamicRange = ExportDynamicRange.HLG),
            uhd30, ultra, ctx,
        )
        assertEquals(ExportDynamicRange.HLG, plan.effective.dynamicRange)
        assertEquals(ExportCodec.HEVC, plan.effective.codec)
        assertEquals(10, plan.bitDepth)
        assertTrue(plan.differsFromRequest)
    }

    @Test
    fun an_api34_gate_marks_the_plan_with_the_trial_issue() {
        // The fallback path (hdr-editing at API 34): the plan is HLG and
        // carries HLG_TRIAL_REQUIRED so the report is honest that the
        // gate passed only the fallback, not the real feature.
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate(api = 34, hlg = false, hdr = true))
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ctx)
        assertEquals(ExportDynamicRange.HLG, plan.effective.dynamicRange)
        assertEquals(ExportColorTags.Hlg, plan.colorTags)
        val trial = plan.issues.single { it.kind == Kind.HLG_TRIAL_REQUIRED }
        assertEquals(Severity.INFO, trial.severity)
        assertTrue(trial.args.single().contains("c2.qti.hevc.encoder"))
        assertTrue(plan.ok)
    }

    @Test
    fun an_api37_gate_needs_no_trial_issue() {
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate(api = 37))
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ctx)
        assertFalse(Kind.HLG_TRIAL_REQUIRED in plan.kinds())
        assertTrue(plan.issues.isEmpty())
    }

    // ---- the gate absent or refused: the E1 wall stands, unchanged ----

    @Test
    fun hlg_without_a_gate_falls_to_sdr_with_the_existing_issue_and_fix() {
        // No gate probed (hlgGate null): today's behavior, byte for byte —
        // DYNAMIC_RANGE_UNAVAILABLE + the SDR fix.
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ExportContext())
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        val issue = plan.issues.single { it.kind == Kind.DYNAMIC_RANGE_UNAVAILABLE }
        assertEquals(ExportDynamicRange.SDR, issue.fix!!.dynamicRange)
        assertEquals("HLG", issue.args.single())
        assertEquals(ExportColorTags.SDR, plan.colorTags)
        assertTrue(plan.ok)
    }

    @Test
    fun hlg_with_a_refusing_gate_falls_to_sdr_too() {
        // The gate WAS probed and the verdict refuses (no hlg-editing at
        // API 34): the plan does not smuggle HLG through.
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate(api = 34, hlg = false, hdr = false))
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ctx)
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        assertTrue(Kind.DYNAMIC_RANGE_UNAVAILABLE in plan.kinds())
        assertEquals(ExportColorTags.SDR, plan.colorTags)
    }

    @Test
    fun hdr10_and_dv_still_hit_the_wall_exactly_as_before() {
        // E2-a opens HLG ONLY: HDR10/DV keep today's behavior.
        for (r in listOf(ExportDynamicRange.HDR10, ExportDynamicRange.DV)) {
            val plan = ExportPlanner.resolve(
                ExportSettings(dynamicRange = r), uhd30, ultra,
                ExportContext(tenBitPathAvailable = true, hlgGate = gate()),
            )
            assertEquals(r, plan.requested.dynamicRange)
            assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
            assertEquals(ExportColorTags.SDR, plan.colorTags)
            assertTrue(Kind.DYNAMIC_RANGE_UNAVAILABLE in plan.kinds())
        }
    }

    @Test
    fun an_encoder_without_main10_degrades_hlg_to_sdr_never_a_main10_less_hlg() {
        // The gate verdict said Supported but the CHOSEN encoder does not
        // list Main10 (the verdict's probe and the planner's encoder list
        // can disagree in the field): the whole range degrades to SDR —
        // a "Main10-less HLG" file would be a lie.
        val noMain10 = listOf(
            EncoderFacts(
                "c2.x.hevc.encoder", "video/hevc", hardware = true,
                profiles = setOf(1), colorFormats = setOf(SURFACE),
                bitrateModes = setOf(1, 2), maxWidth = 4096, maxHeight = 4096,
            ),
        ) + ultra.filter { it.mime == "video/avc" }
        val ctx = ExportContext(tenBitPathAvailable = true, hlgGate = gate())
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, noMain10, ctx)
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        assertEquals(ExportColorTags.SDR, plan.colorTags)
        assertTrue(Kind.DYNAMIC_RANGE_UNAVAILABLE in plan.kinds())
        assertEquals(8, plan.bitDepth)                    // the SDR fix is 8-bit, as the fix says
        assertTrue(plan.ok)
    }

    // ---- old rows do not break (the wire name round-trip) ----

    @Test
    fun hlg_wire_name_round_trips_and_an_old_sdr_row_parses_unchanged() {
        // The wire carries the enum by NAME (ExportSettingsWire v1):
        // a row written with dynamicRange="HLG" parses back as HLG, and
        // pre-E2 rows (all SDR) parse exactly as they always did. Nothing
        // in the wire changed in E2-a — this pins that.
        val hlg = hlgSettings()
        val rendered = ExportSettingsWire.render(hlg)
        assertTrue(rendered.contains("\"dynamicRange\":\"HLG\""))
        val parsed = com.ritim.ritimcraw.data.JsonParser(rendered).parseValue()
        assertEquals(ExportSettingsWire.Parsed.Ok(hlg), ExportSettingsWire.parse(parsed))
        // An old row (the SDR default, written before E2-a) is unchanged.
        val old = ExportSettings(codec = ExportCodec.AVC, bitDepth = 8)
        val oldParsed = com.ritim.ritimcraw.data.JsonParser(ExportSettingsWire.render(old)).parseValue()
        assertEquals(ExportSettingsWire.Parsed.Ok(old), ExportSettingsWire.parse(oldParsed))
        // A pre-E2 app reading the HLG row refuses it honestly (unknown
        // value rule), never guesses SDR — the honest wire's own test.
        assertNotEquals(ExportSettingsWire.Parsed.Ok(old), ExportSettingsWire.parse(parsed))
    }

    @Test
    fun a_hlg_job_with_a_parsing_failure_falls_to_the_e1_wall_not_hlg() {
        // Integration honesty: without a gate EVERYTHING resolves SDR —
        // an HLG row read from an old store, planned without the gate,
        // still yields the wall, exactly as before E2-a.
        val plan = ExportPlanner.resolve(hlgSettings(), uhd30, ultra, ExportContext())
        assertEquals(ExportDynamicRange.SDR, plan.effective.dynamicRange)
        assertEquals("video/hevc", plan.mime)            // the codec was HEVC already; depth fell to 8
        assertEquals(8, plan.bitDepth)
    }
}
