package com.ritim.ritimcraw.export.hevc

import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcInputPath
import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcProbeResult
import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcRefusalStage
import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcVerdict
import com.ritim.ritimcraw.export.hevc.HevcPreflight.decide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HevcPreflight decision-matrix tests (night research 2026-11-16,
 * package B5). EVERY probe result here is SYNTHETIC — a clearly labeled
 * invented device fact set, because the model is host-testable and the
 * REAL device facts are UNKNOWN until the device leg runs
 * (device-test-pending). The tests pin the DECISION RULES, not any real
 * device's support:
 *
 *  - all-supported -> SUPPORTED (Surface primary; P010 the appended
 *    alternative only when probed)
 *  - no HEVC encoder -> REFUSED with the codec-list stage + codec name
 *  - P010 absent -> the Surface-path verdict, NOT a refusal
 *  - HDR feature absent at API 34 -> the gated refusal
 *  - HLG at API 34 -> the API-gate refusal (the feature constant does
 *    not exist at minSdk 34 — refused BEFORE any query)
 *  - Main10 requested + unavailable -> REFUSED, never a silent AVC
 *  - the degradation offer is EXPLICIT (the user chooses), and absent
 *    when no AVC encoder exists or the request was the baseline
 *
 * Pure JVM; no android.media anywhere.
 */
class HevcPreflightTest {

    // ---- SYNTHETIC probe builders (the device facts are INVENTED here) ----

    /** A SYNTHETIC all-supporting API-36 device: everything probed true. */
    private fun syntheticAllSupported(api: Int = 36): HevcProbeResult =
        HevcProbeResult(
            hevcEncoderName = "c2.qti.hevc.encoder",
            listedProfiles = listOf(1, 2, 4096),   // Main, Main10, Main10HDR10
            acceptsP010 = true,
            sizeRateAccepted = true,
            deviceApiLevel = api,
            hdrEditingSupported = true,
            hlgEditingSupported = true,
            avcEncoderName = "c2.qti.avc.encoder",
        )

    /** A SYNTHETIC API-34 device (minSdk): no hlg-editing possible. */
    private fun syntheticApi34(
        hevc: Boolean = true,
        main10: Boolean = true,
        hdr10Profile: Boolean = false,
        p010: Boolean = true,
        sizeRate: Boolean = true,
        hdrEditing: Boolean = false,
        avc: Boolean = true,
    ): HevcProbeResult =
        HevcProbeResult(
            hevcEncoderName = if (hevc) "c2.qti.hevc.encoder" else null,
            listedProfiles = buildList {
                add(1)                            // Main
                if (main10) add(2)               // Main10
                if (hdr10Profile) add(4096)      // Main10HDR10
            },
            acceptsP010 = p010,
            sizeRateAccepted = sizeRate,
            deviceApiLevel = 34,
            hdrEditingSupported = hdrEditing,
            hlgEditingSupported = false,          // the constant does not exist at 34
            avcEncoderName = if (avc) "c2.qti.avc.encoder" else null,
        )

    // ---- SUPPORTED ----

    @Test
    fun all_supported_hevc10_sdr_is_supported_with_surface_primary_and_p010_appended() {
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticAllSupported())
        assertTrue(verdict is HevcVerdict.Supported)
        val supported = verdict as HevcVerdict.Supported
        assertEquals(HevcProfile.HEVC10_SDR, supported.profile)
        assertEquals("c2.qti.hevc.encoder", supported.codecName)
        // Surface is the PRIMARY (the path being extended); P010 was
        // PROBED accepted -> the buffer alternative is APPENDED after it.
        assertEquals(HevcInputPath.SURFACE, supported.paths.first())
        assertEquals(listOf(HevcInputPath.SURFACE, HevcInputPath.P010_BUFFERS), supported.paths)
    }

    @Test
    fun all_supported_hdr10_at_api36_passes_the_feature_gate() {
        val verdict = decide(HevcProfile.HDR10, syntheticAllSupported(api = 36))
        assertTrue(verdict is HevcVerdict.Supported)
        assertEquals(
            listOf(HevcInputPath.SURFACE, HevcInputPath.P010_BUFFERS),
            (verdict as HevcVerdict.Supported).paths,
        )
    }

    @Test
    fun avc_sdr_request_is_today_s_behavior_supported_when_avc_exists() {
        val verdict = decide(HevcProfile.AVC_SDR, syntheticApi34(hevc = false, avc = true))
        assertTrue(verdict is HevcVerdict.Supported)
        val supported = verdict as HevcVerdict.Supported
        assertEquals(HevcProfile.AVC_SDR, supported.profile)
        assertEquals("c2.qti.avc.encoder", supported.codecName)
        // The 8-bit baseline rides the Surface path only — P010 is a
        // 10-bit alternative, never offered for AVC.
        assertEquals(listOf(HevcInputPath.SURFACE), supported.paths)
    }

    // ---- REFUSED: the codec-list stage ----

    @Test
    fun no_hevc_encoder_refuses_with_the_stage_and_the_codec_name() {
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticApi34(hevc = false, avc = true))
        assertTrue(verdict is HevcVerdict.Refused)
        val refused = verdict as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.CODEC_LIST, refused.stage)
        assertEquals("none", refused.codecName)
        assertTrue(refused.reason.contains("video/hevc"))
        // An AVC encoder exists -> the EXPLICIT offer rides the refusal.
        assertTrue(refused.degradedOffer != null)
    }

    @Test
    fun no_encoder_at_all_refuses_with_nothing_to_offer() {
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticApi34(hevc = false, avc = false))
        assertTrue(verdict is HevcVerdict.Refused)
        val refused = verdict as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.CODEC_LIST, refused.stage)
        assertNull(refused.degradedOffer)
    }

    // ---- REFUSED: the profile stage (Main10 requested + unavailable) ----

    @Test
    fun main10_requested_but_unlisted_refuses_never_silently_encodes_avc() {
        // The research's explicit rule: NO silent AVC/8-bit fallback.
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticApi34(main10 = false, avc = true))
        assertTrue(verdict is HevcVerdict.Refused)
        val refused = verdict as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.PROFILE_LEVEL, refused.stage)
        assertEquals("c2.qti.hevc.encoder", refused.codecName)
        assertTrue(refused.reason.contains("2"))            // Main10's value
        // The refusal is a REFUSAL, not a substitution: the verdict's
        // profile is still the REQUESTED one.
        assertEquals(HevcProfile.HEVC10_SDR, refused.profile)
        // And the AVC alternative is an OFFER the user must choose.
        assertTrue(refused.degradedOffer != null)
        assertEquals(HevcProfile.AVC_SDR, refused.degradedOffer!!.baseline)
        assertEquals("c2.qti.avc.encoder", refused.degradedOffer!!.baselineCodecName)
    }

    @Test
    fun hdr10_requested_but_main10hdr10_unlisted_refuses_at_the_profile_stage() {
        val verdict = decide(HevcProfile.HDR10, syntheticApi34(hdr10Profile = false))
        assertTrue(verdict is HevcVerdict.Refused)
        assertEquals(HevcRefusalStage.PROFILE_LEVEL, (verdict as HevcVerdict.Refused).stage)
    }

    // ---- REFUSED: the API-gated feature (the model's guard data) ----

    @Test
    fun hlg_at_api34_goes_through_the_hdr_editing_fallback_and_needs_a_trial_encode() {
        // FEATURE_HlgEditing cannot exist at API 34 and is never queried
        // there; HLG proceeds on FEATURE_HdrEditing (API 33) and the
        // verdict says a trial encode (package E2) is still required.
        val verdict = decide(HevcProfile.HLG, syntheticApi34(hdrEditing = true))
        assertTrue(verdict is HevcVerdict.Supported)
        val supported = verdict as HevcVerdict.Supported
        assertTrue(supported.trialEncodeRequired)
        assertEquals("c2.qti.hevc.encoder", supported.codecName)
    }

    @Test
    fun hlg_at_api34_without_hdr_editing_refuses_naming_the_fallback_feature() {
        val verdict = decide(HevcProfile.HLG, syntheticApi34(hdrEditing = false))
        assertTrue(verdict is HevcVerdict.Refused)
        val refused = verdict as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.FEATURE_QUERY, refused.stage)
        assertTrue(refused.reason.contains("FEATURE_HdrEditing"))
        assertTrue(refused.reason.contains("API 34 path for"))
        assertTrue(refused.degradedOffer != null)
    }

    @Test
    fun hlg_at_api34_never_consults_the_hlg_flag() {
        // Even a (bogus) true hlg flag must not satisfy the API-34 path:
        // the primary feature is not queried below its API.
        val probe = syntheticApi34(hdrEditing = false).copy(hlgEditingSupported = true)
        val verdict = decide(HevcProfile.HLG, probe)
        assertEquals(HevcRefusalStage.FEATURE_QUERY, (verdict as HevcVerdict.Refused).stage)
    }

    @Test
    fun hlg_at_api35_queries_the_hlg_flag_and_needs_no_trial_encode() {
        val ok = decide(HevcProfile.HLG, syntheticAllSupported(api = 35))
        assertTrue(ok is HevcVerdict.Supported)
        assertFalse((ok as HevcVerdict.Supported).trialEncodeRequired)

        // At API 35 the HLG flag is authoritative: hdr-editing does not stand in.
        val noHlg = syntheticAllSupported(api = 35).copy(hlgEditingSupported = false)
        val refused = decide(HevcProfile.HLG, noHlg) as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.FEATURE_QUERY, refused.stage)
        assertTrue(refused.reason.contains("hlg-editing"))
    }

    @Test
    fun hlg_below_the_fallbacks_own_api_still_refuses_on_the_api_gate() {
        // Not reachable at minSdk 34, but the model must stay honest: a
        // device below FEATURE_HdrEditing's API (33) has no path at all.
        val probe = syntheticApi34(hdrEditing = true).copy(deviceApiLevel = 30)
        val refused = decide(HevcProfile.HLG, probe) as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.FEATURE_API, refused.stage)
        assertTrue(refused.reason.contains("API 35"))
        assertTrue(refused.reason.contains("API 30"))
    }

    @Test
    fun feature_lookup_is_by_string_name() {
        val probe = syntheticApi34(hdrEditing = true)
        assertTrue(probe.supportsFeature("hdr-editing"))
        assertFalse(probe.supportsFeature("hlg-editing"))
        assertFalse(probe.supportsFeature("no-such-feature"))
    }

    @Test
    fun hlg_at_api36_with_the_probed_feature_is_supported() {
        val verdict = decide(HevcProfile.HLG, syntheticAllSupported(api = 36))
        assertTrue(verdict is HevcVerdict.Supported)
    }

    @Test
    fun hdr10_gate_is_readable_at_api34_so_no_api_refusal_only_a_feature_query() {
        // FEATURE_HdrEditing exists at 34: an HDR10 request at API 34
        // with hdr-editing present passes the gate.
        val verdict = decide(
            HevcProfile.HDR10,
            syntheticApi34(hdr10Profile = true, hdrEditing = true),
        )
        assertTrue(verdict is HevcVerdict.Supported)
    }

    @Test
    fun hdr10_verdicts_are_the_same_for_every_api_the_app_can_run_on() {
        // minSdk is 34, so moving the HDR10 gate from 29 to 33 must not
        // change a single verdict on a supported device: compare every
        // API from 34 up, and the probed-absent case, against each other.
        for (hdrEditing in listOf(true, false)) {
            val base = decide(
                HevcProfile.HDR10,
                syntheticApi34(hdr10Profile = true, hdrEditing = hdrEditing),
            )
            for (api in 34..40) {
                val probe = syntheticApi34(hdr10Profile = true, hdrEditing = hdrEditing)
                    .copy(deviceApiLevel = api)
                assertEquals("api $api hdrEditing=$hdrEditing", base.kind(), decide(HevcProfile.HDR10, probe).kind())
            }
        }
    }

    @Test
    fun hdr10_gate_edge_is_api_33() {
        val at33 = syntheticApi34(hdr10Profile = true, hdrEditing = true).copy(deviceApiLevel = 33)
        assertTrue(decide(HevcProfile.HDR10, at33) is HevcVerdict.Supported)
        // 29..32 used to pass under the wrong gate; they now refuse on the
        // API gate (unreachable at minSdk 34, correct for the data).
        val at32 = at33.copy(deviceApiLevel = 32)
        val refused = decide(HevcProfile.HDR10, at32) as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.FEATURE_API, refused.stage)
        assertTrue(refused.reason.contains("API 33"))
    }

    private fun HevcVerdict.kind(): String = when (this) {
        is HevcVerdict.Supported -> "supported"
        is HevcVerdict.Refused -> "refused:" + stage.token
    }

    // ---- REFUSED: the probed feature absent at API 34 ----

    @Test
    fun hdr_feature_absent_at_api34_refuses_with_the_feature_query_stage() {
        val verdict = decide(
            HevcProfile.HDR10,
            syntheticApi34(hdr10Profile = true, hdrEditing = false),
        )
        assertTrue(verdict is HevcVerdict.Refused)
        val refused = verdict as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.FEATURE_QUERY, refused.stage)
        assertEquals("c2.qti.hevc.encoder", refused.codecName)
        assertTrue(refused.reason.contains("FEATURE_HdrEditing"))
        assertTrue(refused.reason.contains("hdr-editing"))
        assertTrue(refused.degradedOffer != null)
    }

    // ---- REFUSED: size/rate ----

    @Test
    fun size_rate_refused_refuses_with_the_stage_and_the_codec() {
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticApi34(sizeRate = false))
        assertTrue(verdict is HevcVerdict.Refused)
        val refused = verdict as HevcVerdict.Refused
        assertEquals(HevcRefusalStage.SIZE_RATE, refused.stage)
        assertEquals("c2.qti.hevc.encoder", refused.codecName)
    }

    // ---- P010 absent: the Surface-path verdict, NOT a refusal ----

    @Test
    fun p010_absent_is_the_surface_path_verdict_not_a_refusal() {
        // The buffer alternative is added ONLY if the encoder truly
        // accepts it — absent, the verdict stays SUPPORTED on SURFACE
        // alone (the path being extended: RGBA32F develop -> encoder
        // Surface).
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticApi34(p010 = false))
        assertTrue(verdict is HevcVerdict.Supported)
        val supported = verdict as HevcVerdict.Supported
        assertEquals(listOf(HevcInputPath.SURFACE), supported.paths)
        assertEquals(HevcProfile.HEVC10_SDR, supported.profile)
    }

    // ---- the degradation offer's explicitness ----

    @Test
    fun the_degraded_offer_names_the_refused_profile_the_baseline_and_the_choice() {
        val verdict = decide(HevcProfile.HEVC10_SDR, syntheticApi34(main10 = false, avc = true))
        val refused = verdict as HevcVerdict.Refused
        val offer = refused.degradedOffer!!
        assertEquals(HevcProfile.HEVC10_SDR, offer.refusedProfile)
        assertEquals(HevcProfile.AVC_SDR, offer.baseline)
        // The one honest sentence: the refused profile, the baseline,
        // the probed AVC codec, and the CHOICE — never a silent
        // substitution.
        assertTrue(offer.offerText.contains("HEVC 10-bit SDR"))
        assertTrue(offer.offerText.contains("AVC 8-bit SDR"))
        assertTrue(offer.offerText.contains("c2.qti.avc.encoder"))
        assertTrue(offer.offerText.contains("Choose"))
    }

    @Test
    fun a_refused_avc_request_has_nothing_to_offer() {
        // AVC_SDR refused (no AVC encoder) -> no offer of itself.
        val verdict = decide(HevcProfile.AVC_SDR, syntheticApi34(hevc = true, avc = false))
        assertTrue(verdict is HevcVerdict.Refused)
        assertNull((verdict as HevcVerdict.Refused).degradedOffer)
    }

    @Test
    fun the_decision_is_pure_the_same_facts_give_the_same_verdict() {
        val probe = syntheticApi34(main10 = false, avc = true)
        assertEquals(
            decide(HevcProfile.HEVC10_SDR, probe).toString(),
            decide(HevcProfile.HEVC10_SDR, probe).toString(),
        )
    }

    // ---- the refusal stages' closed token vocabulary ----

    @Test
    fun refusal_stage_tokens_are_the_owner_s_grep_shape() {
        // Lowercase + hyphens, the SurfaceInputStep precedent.
        assertEquals("codec-list", HevcRefusalStage.CODEC_LIST.token)
        assertEquals("profile-level", HevcRefusalStage.PROFILE_LEVEL.token)
        assertEquals("feature-api", HevcRefusalStage.FEATURE_API.token)
        assertEquals("feature-query", HevcRefusalStage.FEATURE_QUERY.token)
        assertEquals("size-rate", HevcRefusalStage.SIZE_RATE.token)
        assertEquals("surface", HevcInputPath.SURFACE.token)
        assertEquals("p010-buffers", HevcInputPath.P010_BUFFERS.token)
    }

    // ---- the stage ORDER (the first failure names itself) ----

    @Test
    fun the_codec_list_stage_precedes_the_profile_stage() {
        // No encoder AND the profile would fail: the codec list is the
        // named stage (the first failure wins).
        val verdict = decide(
            HevcProfile.HEVC10_SDR,
            syntheticApi34(hevc = false, main10 = false),
        )
        assertEquals(HevcRefusalStage.CODEC_LIST, (verdict as HevcVerdict.Refused).stage)
    }

    @Test
    fun the_feature_stage_precedes_the_size_rate_for_hlg_at_api34() {
        // The feature stage precedes size/rate: HLG at API 34 with
        // hdr-editing absent AND the size refused names the feature
        // stage, not the size.
        val verdict = decide(
            HevcProfile.HLG,
            syntheticApi34(sizeRate = false, hdrEditing = false),
        )
        assertEquals(HevcRefusalStage.FEATURE_QUERY, (verdict as HevcVerdict.Refused).stage)
    }

    @Test
    fun the_feature_query_precedes_the_size_rate() {
        val verdict = decide(
            HevcProfile.HDR10,
            syntheticApi34(hdr10Profile = true, hdrEditing = false, sizeRate = false),
        )
        assertEquals(HevcRefusalStage.FEATURE_QUERY, (verdict as HevcVerdict.Refused).stage)
    }

    @Test
    fun the_profile_stage_precedes_the_feature_gate() {
        // HDR10 with the profile unlisted AND the feature absent: the
        // profile stage is named first.
        val verdict = decide(
            HevcProfile.HDR10,
            syntheticApi34(hdr10Profile = false, hdrEditing = false),
        )
        assertEquals(HevcRefusalStage.PROFILE_LEVEL, (verdict as HevcVerdict.Refused).stage)
    }
}
