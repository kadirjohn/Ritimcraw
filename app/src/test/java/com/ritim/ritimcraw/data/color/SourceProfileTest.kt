package com.ritim.ritimcraw.data.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SourceProfile tests (B1, night research 2026-11-16). The reference
 * vectors mirror core's implementations EXACTLY — the expected values
 * below pin the CPU behavior the integrator implements against:
 *  - Mi-Log: core/src/Transfer.cpp:15-30 (constants verbatim from the
 *    Xiaomi DCTL mlog_to_lin);
 *  - Rec.709 OETF: Transfer.cpp:32-35; its inverse: Color.cpp:430-436
 *    (k709InvLinCut = 0.081) with Color.cpp:83-90's sign-preserving
 *    spow;
 *  - YCbCr: Transfer.cpp:39-58 (limited-range 8-bit is core's exact
 *    current math; the other cells are the pinned analogs);
 *  - Rec.2020 -> Rec.709: Color.cpp's own derivation (npm(kP2020, D65),
 *    Color.cpp:259-279; inv(kM709ToXyz), Color.cpp:185-189/44-64;
 *    gamutToGamut's CAT chain, Color.cpp:534-566).
 *
 * The plan-shape tests pin the ROUTING DECISION, not the bug fix: the
 * Mi-Log-unconditional inverse (Transfer.cpp:147-150) is today's CPU
 * behavior; the plan a non-Mi-Log source produces here is the contract
 * the fix will implement.
 */
class SourceProfileTest {

    // ---- helpers: profiles the tests build plans from ----

    /** A container-declared BT.709/Linear SDR clip (the common non-Mi-Log case). */
    private fun bt709Linear() = SourceProfile(
        primaries = SourcePrimaries.BT709,
        transfer = SourceTransfer.Linear,
        matrix = SourceMatrix.BT709,
        range = SourceRange.Limited,
        bitDepth = 8,
        primariesProvenance = ProfileProvenance.Container,
        transferProvenance = ProfileProvenance.Container,
        matrixProvenance = ProfileProvenance.Container,
        rangeProvenance = ProfileProvenance.Container,
        bitDepthProvenance = ProfileProvenance.Container,
    )

    /**
     * The TRUE Mi-Log contract per the Xiaomi DCTL: Rec.2020 primaries,
     * Mi-Log transfer, BT.709 YCbCr matrix (the decode matrix is
     * independent of the primaries), limited range, 8-bit.
     */
    private fun miLog() = SourceProfile(
        primaries = SourcePrimaries.BT2020,
        transfer = SourceTransfer.MiLog,
        matrix = SourceMatrix.BT709,
        range = SourceRange.Limited,
        bitDepth = 8,
        primariesProvenance = ProfileProvenance.Container,
        transferProvenance = ProfileProvenance.Container,
        matrixProvenance = ProfileProvenance.Container,
        rangeProvenance = ProfileProvenance.Container,
        bitDepthProvenance = ProfileProvenance.Container,
    )

    // ---- identity: source == working ----

    @Test
    fun identity_plan_has_no_decode_transfer_and_no_gamut_step() {
        // A linear BT.709 source into the linear Rec.709 working space:
        // only the YCbCr expansion on the decode side, only the OETF on
        // the display side. NO DecodeTransfer (the source is already
        // linear), NO GamutToWorking (the primaries already match).
        val plan = planFor(bt709Linear())
        assertEquals(WorkingSpace.LinearRec709, plan.workingSpace)
        assertEquals(1, plan.decodeSteps.size)
        assertTrue(plan.decodeSteps[0] is PlanStep.YcbcrToRgb)
        assertEquals(0, plan.decodeSteps.count { it is PlanStep.DecodeTransfer })
        assertEquals(0, plan.decodeSteps.count { it is PlanStep.GamutToWorking })
        assertEquals(1, plan.displaySteps.size)
        assertTrue(plan.displaySteps[0] is PlanStep.WorkingToDisplay)
    }

    @Test
    fun identity_plan_decode_step_carries_the_source_matrix_and_range() {
        // The one decode step carries the profile's own matrix/range —
        // the plan never assumes a matrix the profile did not declare.
        val full601 = bt709Linear().copy(
            matrix = SourceMatrix.BT601,
            range = SourceRange.Full,
        )
        val step = planFor(full601).decodeSteps[0] as PlanStep.YcbcrToRgb
        assertEquals(SourceMatrix.BT601, step.matrix)
        assertEquals(SourceRange.Full, step.range)
        assertEquals(8, step.bitDepth)
    }

    // ---- the Mi-Log plan shape (inverse + gamut) ----

    @Test
    fun milog_plan_carries_the_inverse_and_the_2020_gamut_step() {
        // The assumed encoded profile (Mi-Log, Rec.2020 primaries per
        // the Xiaomi DCTL): DecodeTransfer(MiLog) IS present, and the
        // gamut step BT2020 -> BT709 IS present — the deferred
        // conversion Transfer.h:59-61 names. Today's developEncoded
        // skips that conversion (treats the RGB as the working space
        // directly); this plan is the contract the ACES pass
        // implements, and the plan SHAPE is what the tests pin.
        val plan = planFor(miLog())
        val decode = plan.decodeSteps
        assertEquals(3, decode.size)
        assertTrue(decode[0] is PlanStep.YcbcrToRgb)
        val transferStep = decode.filterIsInstance<PlanStep.DecodeTransfer>().single()
        assertEquals(SourceTransfer.MiLog, transferStep.transfer)
        val gamutStep = decode.filterIsInstance<PlanStep.GamutToWorking>().single()
        assertEquals(SourcePrimaries.BT2020, gamutStep.from)
        assertEquals(SourcePrimaries.BT709, gamutStep.to)
    }

    @Test
    fun milog_plan_step_order_is_ycbcr_then_inverse_then_gamut() {
        // The ORDER is the contract (the integrator's single stage-order
        // decision is answered here): YCbCr -> RGB first, transfer
        // inverse second, gamut third — the same order the current CPU
        // code runs (Transfer.cpp:142-150), with the gamut step the
        // deferred addition between the inverse and the grade.
        val decode = planFor(miLog()).decodeSteps
        assertTrue(decode[0] is PlanStep.YcbcrToRgb)
        assertTrue(decode[1] is PlanStep.DecodeTransfer)
        assertTrue(decode[2] is PlanStep.GamutToWorking)
    }

    // ---- the non-Mi-Log plan shape (NO inverse — the fix's contract) ----

    @Test
    fun non_milog_linear_plan_has_no_decode_transfer_step() {
        // THE FIX'S CONTRACT: a Linear source's plan contains NO
        // DecodeTransfer(MiLog) — the unconditional inverse
        // (Transfer.cpp:147-150) applied to a non-Mi-Log clip is the
        // bug; the plan asserts the inverse's ABSENCE, not a "skip"
        // flag. There is nothing to skip because the step is not there.
        val plan = planFor(bt709Linear())
        assertTrue(
            plan.decodeSteps.none { it is PlanStep.DecodeTransfer },
        )
    }

    @Test
    fun non_milog_curves_get_their_own_named_inverse_never_milog() {
        // Each declared curve gets ITS inverse; none of them silently
        // gets Mi-Log's.
        for (transfer in listOf(SourceTransfer.Srgb, SourceTransfer.Rec709OETF)) {
            val plan = planFor(bt709Linear().copy(transfer = transfer))
            val step = plan.decodeSteps.filterIsInstance<PlanStep.DecodeTransfer>().single()
            assertEquals(transfer, step.transfer)
            assertFalse(step.transfer == SourceTransfer.MiLog)
        }
        // HLG and PQ are named too — the plan carries the decision even
        // though no decoder exists yet (the integrator implements them;
        // the plan never silently substitutes Mi-Log).
        for (transfer in listOf(SourceTransfer.Hlg, SourceTransfer.Pq)) {
            val plan = planFor(bt709Linear().copy(transfer = transfer))
            assertEquals(
                transfer,
                plan.decodeSteps.filterIsInstance<PlanStep.DecodeTransfer>().single().transfer,
            )
        }
    }

    // ---- range/matrix selection ----

    @Test
    fun plan_carries_the_declared_matrix_and_range_for_every_combination() {
        // All four matrix/range combinations round the plan: the step
        // mirrors what the profile declared, so a container's
        // COLOR_RANGE_FULL never gets the limited expansion.
        for (matrix in SourceMatrix.values()) {
            for (range in SourceRange.values()) {
                val plan = planFor(bt709Linear().copy(matrix = matrix, range = range))
                val step = plan.decodeSteps[0] as PlanStep.YcbcrToRgb
                assertEquals(matrix, step.matrix)
                assertEquals(range, step.range)
            }
        }
    }

    @Test
    fun ten_bit_profile_carries_ten_bit_expansion() {
        val plan = planFor(bt709Linear().copy(bitDepth = 10))
        assertEquals(10, (plan.decodeSteps[0] as PlanStep.YcbcrToRgb).bitDepth)
    }

    // ---- NaN / unknown-rejection rules ----

    @Test(expected = IllegalArgumentException::class)
    fun zero_bit_depth_is_rejected() {
        // The rejection rule: a profile with a non-positive bit depth is
        // not a contract — reject at construction (GradeParcel's
        // NaN/Infinity refusal is the precedent: bad values never cross
        // a seam silently).
        SourceProfile(
            primaries = SourcePrimaries.BT709,
            transfer = SourceTransfer.MiLog,
            matrix = SourceMatrix.BT709,
            range = SourceRange.Limited,
            bitDepth = 0,
            primariesProvenance = null,
            transferProvenance = null,
            matrixProvenance = null,
            rangeProvenance = null,
            bitDepthProvenance = null,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun negative_bit_depth_is_rejected() {
        SourceProfile(
            primaries = SourcePrimaries.BT709,
            transfer = SourceTransfer.MiLog,
            matrix = SourceMatrix.BT709,
            range = SourceRange.Limited,
            bitDepth = -8,
            primariesProvenance = null,
            transferProvenance = null,
            matrixProvenance = null,
            rangeProvenance = null,
            bitDepthProvenance = null,
        )
    }

    @Test
    fun unknown_provenance_is_modeled_as_null_never_a_silent_default() {
        // The honesty rule (MetadataFacts' null = UNKNOWN): the assumed
        // baseline profile carries null provenances — the current path
        // does not READ these facts, and the profile says so instead of
        // claiming a container declared them.
        val assumed = SourceProfile.assumedEncoded()
        assertEquals(null, assumed.primariesProvenance)
        assertEquals(null, assumed.transferProvenance)
        assertEquals(null, assumed.matrixProvenance)
        assertEquals(null, assumed.rangeProvenance)
        assertEquals(null, assumed.bitDepthProvenance)
        // But the VALUES are the working assumptions the current code
        // implements (the migration baseline).
        assertEquals(SourcePrimaries.BT709, assumed.primaries)
        assertEquals(SourceTransfer.MiLog, assumed.transfer)
        assertEquals(SourceMatrix.BT709, assumed.matrix)
        assertEquals(SourceRange.Limited, assumed.range)
        assertEquals(8, assumed.bitDepth)
    }

    @Test
    fun ycbcr_expansion_rejects_unpinned_bit_depths() {
        // Only 8 and 10 are the container's set; anything else is a bug
        // upstream — refuse rather than compute a wrong expansion.
        var threw = false
        try {
            SourceProfileVectors.ycbcrToRgb(
                SourceMatrix.BT709, SourceRange.Limited, 12,
                y = 128f, cb = 128f, cr = 128f,
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    // ---- reference vectors: Mi-Log (Transfer.cpp:15-30) ----

    @Test
    fun milog_reference_points_pin_core_math() {
        // Expected values computed from the verbatim DCTL constants in
        // double precision; the float mirrors agree to 1e-6 (core runs
        // float; the JVM mirror runs through Math.pow double -> float).
        assertEquals(-0.09023729f, SourceProfileVectors.miLogToLinear(-0.1f), 1e-6f)
        assertEquals(-0.09023729f, SourceProfileVectors.miLogToLinear(0.0f), 1e-6f)
        // Toe branch (x < P_t): sqrt(x/c) + R_0.
        assertEquals(-0.037686179f, SourceProfileVectors.miLogToLinear(0.05f), 1e-6f)
        assertEquals(0.028504777f, SourceProfileVectors.miLogToLinear(0.25f), 1e-6f)
        // Log branch (x >= P_t): 2^((x-g)/a) - b.
        assertEquals(0.260668221f, SourceProfileVectors.miLogToLinear(0.5f), 1e-6f)
        assertEquals(11.520029261f, SourceProfileVectors.miLogToLinear(1.0f), 1e-5f)
    }

    @Test
    fun milog_branch_cut_is_the_pinned_toe_value() {
        // P_t = c*(R_t - R_0)^2 = 0.218991290702 (Transfer.cpp:21);
        // at exactly P_t the log branch takes over and returns R_t
        // (0.01974185) — the branches agree there to float precision.
        assertEquals(0.21899129f, SourceProfileVectors.MILOG_PT, 1e-7f)
        assertEquals(0.01974185f, SourceProfileVectors.miLogToLinear(SourceProfileVectors.MILOG_PT), 1e-6f)
    }

    // ---- reference vectors: Rec.709 OETF + inverse ----

    @Test
    fun rec709_oetf_reference_points_pin_core_math() {
        // Transfer.cpp:32-35: lin < 0.018 -> 4.5*lin; else
        // 1.099*lin^0.45 - 0.099 (unclamped).
        assertEquals(0.0f, SourceProfileVectors.linearToRec709(0.0f), 0.0f)
        assertEquals(0.0405f, SourceProfileVectors.linearToRec709(0.009f), 1e-6f)
        // The encoded value at the 0.018 breakpoint (log side):
        assertEquals(0.081247944f, SourceProfileVectors.linearToRec709(0.018f), 1e-6f)
        assertEquals(0.705515090f, SourceProfileVectors.linearToRec709(0.5f), 1e-6f)
        assertEquals(1.0f, SourceProfileVectors.linearToRec709(1.0f), 1e-6f)
    }

    @Test
    fun rec709_inverse_reference_points_pin_core_math() {
        // Color.cpp:430-436: V <= 0.081 -> V/4.5; else
        // spow((V+0.099)/1.099, 1/0.45).
        assertEquals(0.0f, SourceProfileVectors.rec709OetfInverse(0.0f), 0.0f)
        assertEquals(0.018f, SourceProfileVectors.rec709OetfInverse(0.081f), 1e-6f)
        assertEquals(0.259589401f, SourceProfileVectors.rec709OetfInverse(0.5f), 1e-6f)
        assertEquals(1.0f, SourceProfileVectors.rec709OetfInverse(1.0f), 1e-6f)
        // The sign-preserving spow (Color.cpp:83-90): a negative code
        // inverts through the LINEAR branch (V <= 0.081) as V/4.5.
        assertEquals(-0.011111111f, SourceProfileVectors.rec709OetfInverse(-0.05f), 1e-6f)
    }

    @Test
    fun rec709_oetf_and_inverse_round_trip() {
        // The pair is reversible at the reference points (the inverse
        // is the OETF's inverse by construction).
        for (lin in listOf(0.009f, 0.018f, 0.25f, 0.5f, 1.0f)) {
            val back = SourceProfileVectors.rec709OetfInverse(
                SourceProfileVectors.linearToRec709(lin),
            )
            assertEquals(lin, back, 1e-5f)
        }
    }

    // ---- reference vectors: YCbCr (Transfer.cpp:39-58 + analogs) ----

    @Test
    fun ycbcr_limited_8bit_gray_is_core_math() {
        // Core's exact current expansion (Transfer.cpp:40-42):
        // (128-16)/219 = 0.5114155251; neutral chroma contributes
        // nothing, so gray is the same for 601 and 709.
        for (matrix in SourceMatrix.values()) {
            val rgb = SourceProfileVectors.ycbcrToRgb(matrix, SourceRange.Limited, 8, 128f, 128f, 128f)
            assertEquals(0.511415525f, rgb[0], 1e-6f)
            assertEquals(0.511415525f, rgb[1], 1e-6f)
            assertEquals(0.511415525f, rgb[2], 1e-6f)
        }
    }

    @Test
    fun ycbcr_full_8bit_gray_jiff_expansion() {
        // Full-range 8-bit: 128/255 = 0.5019607843.
        val rgb = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT601, SourceRange.Full, 8, 128f, 128f, 128f)
        assertEquals(0.501960784f, rgb[0], 1e-6f)
        assertEquals(0.501960784f, rgb[1], 1e-6f)
        assertEquals(0.501960784f, rgb[2], 1e-6f)
    }

    @Test
    fun ycbcr_601_vs_709_coefficients_differ_on_a_color_sample() {
        // The red-bar sample (81, 90, 240) limited 8-bit pins BOTH
        // matrices' coefficients (601: JFIF-style; 709: the 1.5748/
        // 0.187324/0.468124/1.8556 set) — computed from
        // Transfer.cpp:39-58's math in double:
        //   601 -> (0.997803653, -0.001884133, -0.003803490)
        //   709 -> (1.084203653,  0.094519832, -0.017985633)
        val r601 = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT601, SourceRange.Limited, 8, 81f, 90f, 240f)
        assertEquals(0.997803653f, r601[0], 1e-6f)
        assertEquals(-0.001884133f, r601[1], 1e-6f)
        assertEquals(-0.003803490f, r601[2], 1e-6f)
        val r709 = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT709, SourceRange.Limited, 8, 81f, 90f, 240f)
        assertEquals(1.084203653f, r709[0], 1e-6f)
        assertEquals(0.094519832f, r709[1], 1e-6f)
        assertEquals(-0.017985633f, r709[2], 1e-6f)
    }

    @Test
    fun ycbcr_full_range_uses_the_jiff_offsets() {
        // The full-range analog: no 16/128 offsets, /255 scale — the
        // same red-bar sample under full range (the container's
        // COLOR_RANGE_FULL declaration).
        val r709 = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT709, SourceRange.Full, 8, 81f, 90f, 240f)
        assertEquals(1.009323922f, r709[0], 1e-6f)
        assertEquals(0.139954604f, r709[1], 1e-6f)
        assertEquals(0.041126275f, r709[2], 1e-6f)
    }

    @Test
    fun ycbcr_ten_bit_expansions_are_the_scaled_analogs() {
        // 10-bit limited: (512-64)/876 = 0.5114155251 — the SAME
        // normalized gray as 8-bit limited (the ranges are scaled
        // versions of each other).
        val lim = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT709, SourceRange.Limited, 10, 512f, 512f, 512f)
        assertEquals(0.511415525f, lim[0], 1e-6f)
        // 10-bit full: 512/1023 = 0.5004887586.
        val full = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT709, SourceRange.Full, 10, 512f, 512f, 512f)
        assertEquals(0.500488759f, full[0], 1e-6f)
    }

    // ---- reference vectors: Rec.2020 -> Rec.709 (Color.cpp) ----

    @Test
    fun rec2020_to_rec709_matrix_pins_cores_derivation() {
        // The pinned matrix, derived exactly as core's
        // gamutToGamut(Rec2020, Rec709) (Color.cpp:549-566) computes it:
        // inv(kM709ToXyz) * CAT(D65,D65) * npm(kP2020, D65). Expected
        // values from the double-precision derivation of the same
        // construction (npm per Color.cpp:259-279, D65 chromaticity
        // 0.3127/0.3290 per Color.cpp:239-245, kM709ToXyz verbatim per
        // Color.cpp:185-189).
        val m = SourceProfileVectors.REC2020_TO_REC709
        assertEquals(1.660317619f, m[0], 1e-6f)
        assertEquals(-0.587572666f, m[1], 1e-6f)
        assertEquals(-0.072916573f, m[2], 1e-6f)
        assertEquals(-0.124406702f, m[3], 1e-6f)
        assertEquals(1.132800741f, m[4], 1e-6f)
        assertEquals(-0.008348937f, m[5], 1e-6f)
        assertEquals(-0.018111364f, m[6], 1e-6f)
        assertEquals(-0.100596531f, m[7], 1e-6f)
        assertEquals(1.118766482f, m[8], 1e-6f)
    }

    @Test
    fun rec2020_red_maps_to_the_pinned_709_rgb() {
        // The 2020 red primary [1,0,0] through the pinned matrix —
        // the first column, the honest spot-check of the derivation.
        val m = SourceProfileVectors.REC2020_TO_REC709
        assertEquals(1.660317619f, m[0], 1e-6f)
        assertEquals(-0.124406702f, m[3], 1e-6f)
        assertEquals(-0.018111364f, m[6], 1e-6f)
    }

    @Test
    fun rec2020_to_xyz_is_the_npm_derivation() {
        // npm(kP2020, D65) per Color.cpp:259-279 — the derivation the
        // BT.2020 dataset pins. The zero at [6] is exact in the
        // derivation (the 2020 blue primary's X column cancels).
        val m = SourceProfileVectors.REC2020_TO_XYZ
        assertEquals(0.636958048f, m[0], 1e-6f)
        assertEquals(0.262700212f, m[3], 1e-6f)
        assertEquals(0.0f, m[6], 1e-7f)
        assertEquals(1.060985058f, m[8], 1e-6f)
    }

    @Test
    fun rec709_to_rec2020_is_the_inverse_direction() {
        // For a future wide-gamut working space: the same construction
        // in the other direction (inv(npm2020) * CAT * kM709ToXyz).
        val m = SourceProfileVectors.REC709_TO_REC2020
        assertEquals(0.627441372f, m[0], 1e-6f)
        assertEquals(0.919580667f, m[4], 1e-6f)
        assertEquals(0.895564973f, m[8], 1e-6f)
    }

    // ---- the display side ----

    @Test
    fun display_side_is_the_rec709_oetf_from_the_working_space() {
        // Today's display derivation: linear Rec.709 working space ->
        // Rec.709 display, so the ONLY display step is the OETF
        // (jni_bridge.cpp:5142-5149's tail; the shader's uOetf gate).
        for (transfer in SourceTransfer.values()) {
            val plan = planFor(miLog().copy(transfer = transfer))
            val display = plan.displaySteps.single() as PlanStep.WorkingToDisplay
            assertEquals(SourcePrimaries.BT709, display.displayPrimaries)
            assertEquals(SourceTransfer.Rec709OETF, display.displayTransfer)
        }
    }

}
