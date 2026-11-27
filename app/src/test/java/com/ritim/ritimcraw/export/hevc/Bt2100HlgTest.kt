package com.ritim.ritimcraw.export.hevc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BT.2100 HLG reference-value tests (E2-a, night 2026-11-27). The pure
 * Kotlin curve functions against the ITU-R BT.2100 tables themselves —
 * the inputs the brief named: 0, the 1/12 knee, 0.5, 1.0 — plus the
 * BT.2408 75 % diffuse-white composite anchors. Every expected value
 * is either the standard's own closed form (sqrt(3*E) at the knee,
 * a*ln(12-b)+c) or a value computed from those forms in double
 * precision; tolerance <= 1e-6 as the brief requires.
 *
 * JVM only; no device, no display — the curve's appearance on an HDR
 * screen is "dogrulanmadi" in the night report.
 */
class Bt2100HlgTest {

    private val EPS = 1e-6

    // ---- BT.2100 Table 5 reference points (the four inputs the brief names) ----

    @Test
    fun oetf_zero_is_zero() {
        assertEquals(0.0, Bt2100Hlg.oetf(0.0), EPS)
    }

    @Test
    fun oetf_at_the_one_twelfth_knee_is_exactly_one_half() {
        // BT.2100 Table 5: E = 1/12 -> signal 1/2; the two branches agree here.
        assertEquals(0.5, Bt2100Hlg.oetf(1.0 / 12.0), EPS)
        // ...and the closed form sqrt(3*E) itself:
        assertEquals(Math.sqrt(3.0 / 12.0), Bt2100Hlg.oetf(1.0 / 12.0), EPS)
    }

    @Test
    fun oetf_half_scene_is_the_standard_s_log_value() {
        // E = 0.5 -> a*ln(12*0.5 - b) + c, the constants' own arithmetic.
        val expected = 0.17883277 * Math.log(12.0 * 0.5 - 0.28466892) + 0.55991073
        assertEquals(expected, Bt2100Hlg.oetf(0.5), EPS)
        // And the standard's Table 8 lists 0.871644 for E=0.5.
        assertEquals(0.871644, Bt2100Hlg.oetf(0.5), 1e-6)
    }

    @Test
    fun oetf_one_is_one_within_the_published_constants_precision() {
        // BT.2100 Table 5: E = 1.0 -> signal 1.0. The published a/b/c
        // constants carry 8 decimals, so the log branch at E=1 lands
        // within ~5e-9 of 1.0 — inside the brief's 1e-6 tolerance.
        assertEquals(1.0, Bt2100Hlg.oetf(1.0), EPS)
    }

    // ---- the inverse OETF: the same four points, algebraically back ----

    @Test
    fun inverse_oetf_zero_is_zero() {
        assertEquals(0.0, Bt2100Hlg.inverseOetf(0.0), EPS)
    }

    @Test
    fun inverse_oetf_half_signal_is_the_one_twelfth_knee() {
        assertEquals(1.0 / 12.0, Bt2100Hlg.inverseOetf(0.5), EPS)
    }

    @Test
    fun inverse_oetf_the_log_value_back_to_half_scene() {
        assertEquals(0.5, Bt2100Hlg.inverseOetf(Bt2100Hlg.oetf(0.5)), EPS)
    }

    @Test
    fun inverse_oetf_one_is_one() {
        assertEquals(1.0, Bt2100Hlg.inverseOetf(1.0), EPS)
    }

    @Test
    fun oetf_and_inverse_oetf_round_trip_across_the_whole_range() {
        // The exact inverse: every point maps back within 1e-8, on both
        // sides of the knee (the inverse must be the INVERSE, not an
        // approximation fitted to a few samples). 1e-8 — not tighter —
        // because the published 8-decimal constants cap the log branch's
        // own round-trip at ~4.5e-9 near E=1 (oetf(1)=0.99999999554).
        for (i in 0..100) {
            val e = i / 100.0
            assertEquals("scene $e", e, Bt2100Hlg.inverseOetf(Bt2100Hlg.oetf(e)), 1e-8)
            val s = i / 100.0
            assertEquals("signal $s", s, Bt2100Hlg.oetf(Bt2100Hlg.inverseOetf(s)), 1e-8)
        }
    }

    @Test
    fun the_two_oetf_branches_agree_at_the_knee_and_below_it_the_sqrt_form() {
        // Continuity at the breakpoint (both closed forms at E=1/12).
        val knee = 1.0 / 12.0
        val sqrtForm = Math.sqrt(3.0 * knee)
        val logForm = 0.17883277 * Math.log(12.0 * knee - 0.28466892) + 0.55991073
        assertEquals(sqrtForm, logForm, EPS)
        assertEquals(sqrtForm, Bt2100Hlg.oetf(knee), EPS)
        // Just below the knee the sqrt branch is exact; just above, the log.
        assertEquals(Math.sqrt(3.0 * (knee - 1e-9)), Bt2100Hlg.oetf(knee - 1e-9), EPS)
    }

    // ---- the OOTF (gamma = 1.2 at 1000 nits) ----

    @Test
    fun ootf_grey_is_scene_light_to_the_1_2_at_1000_nits() {
        assertEquals(0.0, Bt2100Hlg.ootfGrey(0.0), EPS)
        assertEquals(1.0, Bt2100Hlg.ootfGrey(1.0), EPS)
        assertEquals(0.5.pow12(), Bt2100Hlg.ootfGrey(0.5), EPS)
        // The 0.75-signal grey: its SCENE light is 0.26496..., so its
        // display light (gamma 1.2) is 0.20315... = 203.15 nits of the
        // 1000-nit display — the plan's own 203-nit reference chain.
        assertEquals(0.26496255978640015, Bt2100Hlg.inverseOetf(0.75), EPS)
        assertEquals(0.2031521453536661, Bt2100Hlg.ootfGrey(Bt2100Hlg.inverseOetf(0.75)), EPS)
        assertEquals(203.1521453536661, Bt2100Hlg.ootfGrey(Bt2100Hlg.inverseOetf(0.75)) * 1000.0, EPS)
    }

    @Test
    fun inverse_ootf_grey_inverts_the_ootf() {
        for (i in 0..20) {
            val e = i / 20.0
            assertEquals("scene $e", e, Bt2100Hlg.inverseOotfGrey(Bt2100Hlg.ootfGrey(e)), 1e-12)
        }
        assertEquals(0.0, Bt2100Hlg.inverseOotfGrey(0.0), EPS)
    }

    // ---- BT.2408: the 75 % diffuse white (SDR composite anchors) ----

    @Test
    fun sdr_reference_white_203_nits_is_the_75_percent_hlg_signal() {
        // BT.2408: SDR graphics on a 1000-nit HLG display sit at 203
        // cd/m^2 = HLG signal 0.75 (the plan's own 203->0.7499 chain,
        // here in double precision: 0.74988).
        assertEquals(0.75, Bt2100Hlg.hlgSignalForDisplayNits(203.0), 1e-3)
        assertEquals(0.7498773651026114, Bt2100Hlg.hlgSignalForDisplayNits(203.0), EPS)
    }

    @Test
    fun the_diffuse_white_constants_are_the_plan_s_own_anchors() {
        assertEquals(203.0, Bt2100Hlg.SDR_WHITE_NITS, 0.0)
        assertEquals(1000.0, Bt2100Hlg.HLG_PEAK_NITS, 0.0)
        assertEquals(0.75, Bt2100Hlg.DIFFUSE_WHITE_SIGNAL, 0.0)
        // The 10-bit limited code the 0.75 signal lands on (plan 3.3:
        // 64 + 0.75*876 = 721).
        assertEquals(721, Bt2100Hlg.DIFFUSE_WHITE_10_BIT_CODE)
        assertEquals(721, Bt2100Hlg.limitedRange10BitCode(0.75))
    }

    @Test
    fun the_10_bit_limited_quantization_span_is_64_to_940() {
        // The band the encoder maps [0,1] onto (plan 3.4): 64 + s*876.
        assertEquals(64, Bt2100Hlg.limitedRange10BitCode(0.0))
        assertEquals(940, Bt2100Hlg.limitedRange10BitCode(1.0))
        assertEquals(502, Bt2100Hlg.limitedRange10BitCode(0.5))
    }

    @Test
    fun inputs_outside_the_defined_domain_clamp_never_naN_never_extrapolate() {
        // The curve is defined on [0,1]; outside it the functions CLAMP
        // (a negative scene must not produce a NaN sqrt, a signal above 1
        // must not extrapolate the log into nonsense).
        assertEquals(0.0, Bt2100Hlg.oetf(-0.5), EPS)
        assertEquals(1.0, Bt2100Hlg.oetf(1.5), EPS)
        assertEquals(0.0, Bt2100Hlg.inverseOetf(-0.5), EPS)
        assertEquals(1.0, Bt2100Hlg.inverseOetf(2.0), EPS)
        assertEquals(0.0, Bt2100Hlg.ootfGrey(-1.0), EPS)
        assertEquals(1.0, Bt2100Hlg.ootfGrey(2.0), EPS)
        assertTrue(!Bt2100Hlg.oetf(0.25).isNaN())
        assertTrue(!Bt2100Hlg.inverseOetf(0.25).isNaN())
    }

    @Test
    fun the_constants_are_the_published_ones_carried_as_data() {
        assertEquals(0.17883277, Bt2100Hlg.A, 0.0)
        assertEquals(0.28466892, Bt2100Hlg.B, 0.0)
        assertEquals(0.55991073, Bt2100Hlg.C, 0.0)
        assertEquals(1.0 / 12.0, Bt2100Hlg.KNEE_SCENE, 0.0)
        assertEquals(0.5, Bt2100Hlg.KNEE_SIGNAL, 0.0)
        assertEquals(1.2, Bt2100Hlg.OOTF_GAMMA_1000, 0.0)
    }

    private fun Double.pow12(): Double = Math.pow(this, 1.2)
}
