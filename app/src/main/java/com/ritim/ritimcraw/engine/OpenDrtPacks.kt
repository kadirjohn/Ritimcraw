package com.ritim.ritimcraw.engine

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The OpenDRT output's uniform pack (color model v2 F1-6) — the Kotlin
 * resolver for the 77-float block the GPU TAIL's DRT variant consumes
 * (GpuDevelopPipeline::kDrtUniformSlots; the layout is documented THERE
 * and consumed by drtFoldUniforms).
 *
 * This is core's `OpenDrt::Impl` constructor carried to Kotlin 1:1 — the
 * preset tables (core/src/OpenDrt.cpp's P class), the tonescale constant
 * derivation, every spowf/exp2f short-cut — so the JVM binding resolves
 * the pack ONCE per output change (setColorModelChain's contract: "the
 * values are pinned by the JVM tests against core's own Impl outputs").
 * The GLSL shader applies the SAME numbers the C++ reference would; the
 * pack is how both sides hold one truth.
 *
 * The 77-slot layout (GpuDevelopPipeline.h, verbatim):
 *   [0] eotf ordinal (1 sRGB 2.2, 2 Rec.1886, 5 HLG — core's internal
 *       eotf ints, NOT the spec's display ordinals; resolved here)
 *   [1] hdr flag (display == Hlg1000)
 *   [2] the P.cwp (0..5 internal, 2 = D65 skip)
 *   [3] normScalar (kCWP_NORM_709[cwp])    [4] normScalarHdr (kCWP_NORM_P3[cwp])
 *   [5..8] tn (tn_con, tn_sh, tn_toe, tn_off)
 *   [9..12] rs (rs_sa, rs_rw, rs_bw, cwp_lm)
 *   [13..16] toe (brl, brl_rng, brl_st, tsDsc)
 *   [17..20] brlRgb (brl_r, brl_g, brl_b, brlp)
 *   [21..24] brlpRgb (brlp_r, brlp_g, brlp_b, lconSc)
 *   [25..28] lcon (lconM, lconW, hconP, tn_hcon_pv)
 *   [29..32] hcon (tn_hcon_st, tsM2, tsS, tsP)
 *   [33..36] ts (tsS1, sLp100, 0, 0)
 *   [37..38] hueR (hs_r, hs_r_rng) ... [43..44] hueC, [45..46] hueM,
 *   [47..48] hueY  (each hue pair: strength + range)
 *   [49..52] conf0 (brl_en, brlp_en, hc_en, ptl_en)
 *   [53..56] conf1 (ptm_en, hs_rgb_en, hs_cmy_en, lcon_en)
 *   [57..60] conf2 (hcon_en, 0, hc_r, hc_r_rng)
 *   [61..64] pt (pt_lml, pt_lml_r, pt_lml_g, pt_lml_b)
 *   [65..68] pt2 (pt_lmh, pt_lmh_r, pt_lmh_b, ptl_c)
 *   [69..72] pt3 (ptl_m, ptl_y, ptm_low, ptm_low_rng)
 *   [73..76] pt4 (ptm_low_st, ptm_high, ptm_high_rng, ptm_high_st)
 *
 * NOTE on hue ordering: core's P carries hs_c/hs_m/hs_y beside hs_r/g/b —
 * the block ships them in the PIPELINE's own contract order — hueR,
 * hueG, hueB, hueC, hueM, hueY ([37..48]); this resolver fills them in
 * THAT order and the JVM pack test pins every value against a
 * C-side-resolved reference.
 */
object OpenDrtPacks {

    /** GpuDevelopPipeline::kDrtUniformSlots (77), mirrored. */
    const val SLOT_COUNT = 77

    // ---- core's helpers, verbatim (OpenDrt.cpp:47-49, 203-206) ----

    private fun spowf(a: Float, b: Float): Float = if (a <= 0f) a else a.pow(b)
    private fun sdivf(a: Float, b: Float): Float = if (b == 0f) 0f else a / b
    private fun exp2f_(x: Float): Float = 2f.pow(x)

    private fun compressToeQuadratic(x: Float, toe: Float, inv: Boolean): Float {
        if (toe == 0f) return x
        return if (!inv) {
            spowf(x, 2f) / (x + toe)
        } else {
            (x + sqrt(x * (4f * toe + x))) / 2f
        }
    }

    private fun compressToeCubic(x: Float, m: Float, w: Float, inv: Boolean): Float {
        if (m == 1f) return x
        val x2 = x * x
        if (!inv) return x * (x2 + m * w) / (x2 + w)
        val p0 = x2 - 3f * m * w
        val p1 = 2f * x2 + 27f * w - 9f * m * w
        val p2 = (sqrt(x2 * p1 * p1 - 4f * p0 * p0 * p0) / 2f + x * p1 / 2f).pow(1f / 3f)
        return p0 / (3f * p2) + p2 / 3f + x / 3f
    }

    // ---- core's P preset class, verbatim (OpenDrt.cpp:58-190) ----

    private class P {
        var tnCon = 0f; var tnSh = 0f; var tnToe = 0f; var tnOff = 0f
        var tnHconEnable = false; var tnHcon = 0f; var tnHconPv = 0f; var tnHconSt = 0f
        var tnLconEnable = false; var tnLcon = 0f; var tnLconW = 0f
        var cwp = 2; var cwpLm = 0f; var rsSa = 0f; var rsRw = 0f; var rsBw = 0f
        var ptEnable = true
        var ptLml = 0f; var ptLmlR = 0f; var ptLmlG = 0f; var ptLmlB = 0f
        var ptLmh = 0f; var ptLmhR = 0f; var ptLmhB = 0f
        var ptlEnable = true; var ptlC = 0f; var ptlM = 0f; var ptlY = 0f
        var ptmEnable = true
        var ptmLow = 0f; var ptmLowRng = 0f; var ptmLowSt = 0f
        var ptmHigh = 0f; var ptmHighRng = 0f; var ptmHighSt = 0f
        var brlEnable = true
        var brl = 0f; var brlR = 0f; var brlG = 0f; var brlB = 0f; var brlRng = 0f; var brlSt = 0f
        var brlpEnable = true; var brlp = 0f; var brlpR = 0f; var brlpG = 0f; var brlpB = 0f
        var hcEnable = true; var hcR = 0f; var hcRRng = 0f
        var hsRgbEnable = true
        var hsR = 0f; var hsRRng = 0f; var hsG = 0f; var hsGRng = 0f; var hsB = 0f; var hsBRng = 0f
        var hsCmyEnable = true
        var hsC = 0f; var hsCRng = 0f; var hsM = 0f; var hsMRng = 0f; var hsY = 0f; var hsYRng = 0f

        fun ts(con: Float, sh: Float, toe: Float, off: Float,
               hconOn: Boolean, hcon: Float, hconPv: Float, hconSt: Float,
               lconOn: Boolean, lcon: Float, lconW: Float) {
            tnCon = con; tnSh = sh; tnToe = toe; tnOff = off
            tnHconEnable = hconOn; tnHcon = hcon; tnHconPv = hconPv; tnHconSt = hconSt
            tnLconEnable = lconOn; tnLcon = lcon; tnLconW = lconW
        }

        fun preset(look: Int, tonescale: Int, whiteSel: Int, whiteLimit: Float) {
            when (look) {
                1 -> { // Arriba
                    ts(1.05f, 0.5f, 0.1f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 1.5f, 0.2f)
                    cwp = 2; cwpLm = 0.25f; rsSa = 0.35f; rsRw = 0.25f; rsBw = 0.55f
                    ptEnable = true; ptLml = 0.25f; ptLmlR = 0.45f; ptLmlG = 0.0f; ptLmlB = 0.1f
                    ptLmh = 0.25f; ptLmhR = 0.25f; ptLmhB = 0.0f
                    ptlEnable = true; ptlC = 0.06f; ptlM = 0.08f; ptlY = 0.06f
                    ptmEnable = true; ptmLow = 1.0f; ptmLowRng = 0.4f; ptmLowSt = 0.5f
                    ptmHigh = -0.8f; ptmHighRng = 0.66f; ptmHighSt = 0.6f
                    brlEnable = true; brl = 0.0f; brlR = -2.5f; brlG = -1.5f; brlB = -1.5f
                    brlRng = 0.5f; brlSt = 0.35f
                    brlpEnable = true; brlp = 0.0f; brlpR = -1.7f; brlpG = -2.0f; brlpB = -0.5f
                    hcEnable = true; hcR = 1.0f; hcRRng = 0.3f
                    hsRgbEnable = true; hsR = 0.6f; hsRRng = 0.8f
                    hsG = 0.35f; hsGRng = 1.0f; hsB = 0.66f; hsBRng = 1.0f
                    hsCmyEnable = true; hsC = 0.15f; hsCRng = 1.0f; hsM = 0.0f; hsMRng = 1.0f
                    hsY = 0.0f; hsYRng = 1.0f
                }
                2 -> { // Sylvan
                    ts(1.6f, 0.5f, 0.01f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 0.25f, 0.75f)
                    cwp = 2; cwpLm = 0.25f; rsSa = 0.25f; rsRw = 0.25f; rsBw = 0.55f
                    ptEnable = true; ptLml = 0.15f; ptLmlR = 0.5f; ptLmlG = 0.15f; ptLmlB = 0.1f
                    ptLmh = 0.25f; ptLmhR = 0.15f; ptLmhB = 0.15f
                    ptlEnable = true; ptlC = 0.05f; ptlM = 0.08f; ptlY = 0.05f
                    ptmEnable = true; ptmLow = 0.5f; ptmLowRng = 0.5f; ptmLowSt = 0.5f
                    ptmHigh = -0.8f; ptmHighRng = 0.5f; ptmHighSt = 0.5f
                    brlEnable = true; brl = -1.0f; brlR = -2.0f; brlG = -2.0f; brlB = 0.0f
                    brlRng = 0.25f; brlSt = 0.25f
                    brlpEnable = true; brlp = -1.0f; brlpR = -0.5f; brlpG = -0.25f; brlpB = -0.25f
                    hcEnable = true; hcR = 1.0f; hcRRng = 0.4f
                    hsRgbEnable = true; hsR = 0.6f; hsRRng = 1.15f
                    hsG = 0.8f; hsGRng = 1.25f; hsB = 0.6f; hsBRng = 1.0f
                    hsCmyEnable = true; hsC = 0.25f; hsCRng = 0.25f; hsM = 0.25f; hsMRng = 0.5f
                    hsY = 0.35f; hsYRng = 0.5f
                }
                3 -> { // Colorful
                    ts(1.5f, 0.5f, 0.003f, 0.003f, false, 0.0f, 1.0f, 4.0f, true, 0.4f, 0.5f)
                    cwp = 2; cwpLm = 0.25f; rsSa = 0.35f; rsRw = 0.25f; rsBw = 0.55f
                    ptEnable = true; ptLml = 0.5f; ptLmlR = 1.0f; ptLmlG = 0.0f; ptLmlB = 0.5f
                    ptLmh = 0.15f; ptLmhR = 0.15f; ptLmhB = 0.15f
                    ptlEnable = true; ptlC = 0.05f; ptlM = 0.06f; ptlY = 0.05f
                    ptmEnable = true; ptmLow = 0.8f; ptmLowRng = 0.5f; ptmLowSt = 0.4f
                    ptmHigh = -0.8f; ptmHighRng = 0.4f; ptmHighSt = 0.4f
                    brlEnable = true; brl = 0.0f; brlR = -1.25f; brlG = -1.25f; brlB = -0.25f
                    brlRng = 0.3f; brlSt = 0.5f
                    brlpEnable = true; brlp = -0.5f; brlpR = -1.25f; brlpG = -1.25f; brlpB = -0.5f
                    hcEnable = true; hcR = 1.0f; hcRRng = 0.4f
                    hsRgbEnable = true; hsR = 0.5f; hsRRng = 0.8f
                    hsG = 0.35f; hsGRng = 1.0f; hsB = 0.5f; hsBRng = 1.0f
                    hsCmyEnable = true; hsC = 0.25f; hsCRng = 1.0f; hsM = 0.0f; hsMRng = 1.0f
                    hsY = 0.25f; hsYRng = 1.0f
                }
                4 -> { // Aery
                    ts(1.15f, 0.5f, 0.04f, 0.006f, false, 0.0f, 0.0f, 0.5f, true, 0.5f, 2.0f)
                    cwp = 1; cwpLm = 0.25f; rsSa = 0.25f; rsRw = 0.2f; rsBw = 0.5f
                    ptEnable = true; ptLml = 0.0f; ptLmlR = 0.5f; ptLmlG = 0.15f; ptLmlB = 0.1f
                    ptLmh = 0.0f; ptLmhR = 0.1f; ptLmhB = 0.0f
                    ptlEnable = true; ptlC = 0.05f; ptlM = 0.08f; ptlY = 0.05f
                    ptmEnable = true; ptmLow = 0.8f; ptmLowRng = 0.35f; ptmLowSt = 0.5f
                    ptmHigh = -0.9f; ptmHighRng = 0.5f; ptmHighSt = 0.3f
                    brlEnable = true; brl = -3.0f; brlR = 0.0f; brlG = 0.0f; brlB = 1.0f
                    brlRng = 0.8f; brlSt = 0.15f
                    brlpEnable = true; brlp = -1.0f; brlpR = -1.0f; brlpG = -1.0f; brlpB = 0.0f
                    hcEnable = true; hcR = 0.5f; hcRRng = 0.25f
                    hsRgbEnable = true; hsR = 0.6f; hsRRng = 1.0f
                    hsG = 0.35f; hsGRng = 2.0f; hsB = 0.5f; hsBRng = 1.5f
                    hsCmyEnable = true; hsC = 0.35f; hsCRng = 1.0f; hsM = 0.25f; hsMRng = 1.0f
                    hsY = 0.35f; hsYRng = 0.5f
                }
                5 -> { // Dystopic
                    ts(1.6f, 0.5f, 0.01f, 0.008f, true, 0.25f, 0.0f, 1.0f, true, 1.0f, 0.75f)
                    cwp = 3; cwpLm = 0.25f; rsSa = 0.2f; rsRw = 0.25f; rsBw = 0.55f
                    ptEnable = true; ptLml = 0.15f; ptLmlR = 0.0f; ptLmlG = 0.0f; ptLmlB = 0.0f
                    ptLmh = 0.0f; ptLmhR = 0.0f; ptLmhB = 0.0f
                    ptlEnable = true; ptlC = 0.05f; ptlM = 0.08f; ptlY = 0.05f
                    ptmEnable = true; ptmLow = 0.25f; ptmLowRng = 0.25f; ptmLowSt = 0.8f
                    ptmHigh = -0.8f; ptmHighRng = 0.6f; ptmHighSt = 0.25f
                    brlEnable = true; brl = -2.0f; brlR = -2.0f; brlG = -2.0f; brlB = 0.0f
                    brlRng = 0.35f; brlSt = 0.35f
                    brlpEnable = true; brlp = 0.0f; brlpR = -1.0f; brlpG = -1.0f; brlpB = -1.0f
                    hcEnable = true; hcR = 1.0f; hcRRng = 0.25f
                    hsRgbEnable = true; hsR = 0.7f; hsRRng = 1.33f
                    hsG = 1.0f; hsGRng = 2.0f; hsB = 0.75f; hsBRng = 2.0f
                    hsCmyEnable = true; hsC = 1.0f; hsCRng = 0.5f; hsM = 1.0f; hsMRng = 1.0f
                    hsY = 1.0f; hsYRng = 0.765f
                }
                6 -> { // Umbra
                    ts(1.8f, 0.5f, 0.001f, 0.015f, false, 0.0f, 1.0f, 4.0f, true, 1.0f, 1.0f)
                    cwp = 5; cwpLm = 0.25f; rsSa = 0.35f; rsRw = 0.25f; rsBw = 0.55f
                    ptEnable = true; ptLml = 0.0f; ptLmlR = 0.5f; ptLmlG = 0.0f; ptLmlB = 0.15f
                    ptLmh = 0.25f; ptLmhR = 0.25f; ptLmhB = 0.0f
                    ptlEnable = true; ptlC = 0.05f; ptlM = 0.06f; ptlY = 0.05f
                    ptmEnable = true; ptmLow = 0.4f; ptmLowRng = 0.35f; ptmLowSt = 0.66f
                    ptmHigh = -0.6f; ptmHighRng = 0.45f; ptmHighSt = 0.45f
                    brlEnable = true; brl = -2.0f; brlR = -4.5f; brlG = -3.0f; brlB = -4.0f
                    brlRng = 0.35f; brlSt = 0.3f
                    brlpEnable = true; brlp = 0.0f; brlpR = -2.0f; brlpG = -1.0f; brlpB = -0.5f
                    hcEnable = true; hcR = 1.0f; hcRRng = 0.35f
                    hsRgbEnable = true; hsR = 0.66f; hsRRng = 1.0f
                    hsG = 0.5f; hsGRng = 2.0f; hsB = 0.85f; hsBRng = 2.0f
                    hsCmyEnable = true; hsC = 0.0f; hsCRng = 1.0f; hsM = 0.25f; hsMRng = 1.0f
                    hsY = 0.66f; hsYRng = 0.66f
                }
                else -> { // Standard
                    ts(1.66f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, false, 0.0f, 0.5f)
                    cwp = 2; cwpLm = 0.25f; rsSa = 0.35f; rsRw = 0.25f; rsBw = 0.55f
                    ptEnable = true; ptLml = 0.25f; ptLmlR = 0.5f; ptLmlG = 0.0f; ptLmlB = 0.1f
                    ptLmh = 0.25f; ptLmhR = 0.5f; ptLmhB = 0.0f
                    ptlEnable = true; ptlC = 0.06f; ptlM = 0.08f; ptlY = 0.06f
                    ptmEnable = true; ptmLow = 0.4f; ptmLowRng = 0.25f; ptmLowSt = 0.5f
                    ptmHigh = -0.8f; ptmHighRng = 0.35f; ptmHighSt = 0.4f
                    brlEnable = true; brl = 0.0f; brlR = -2.5f; brlG = -1.5f; brlB = -1.5f
                    brlRng = 0.5f; brlSt = 0.35f
                    brlpEnable = true; brlp = -0.5f; brlpR = -1.25f; brlpG = -1.25f; brlpB = -0.25f
                    hcEnable = true; hcR = 1.0f; hcRRng = 0.3f
                    hsRgbEnable = true; hsR = 0.6f; hsRRng = 0.6f
                    hsG = 0.35f; hsGRng = 1.0f; hsB = 0.66f; hsBRng = 1.0f
                    hsCmyEnable = true; hsC = 0.25f; hsCRng = 1.0f; hsM = 0.0f; hsMRng = 1.0f
                    hsY = 0.0f; hsYRng = 1.0f
                }
            }
            when (tonescale) {
                1 -> ts(1.4f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, false, 0.0f, 0.5f)
                2 -> ts(1.66f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, false, 0.0f, 0.5f)
                3 -> ts(1.4f, 0.5f, 0.003f, 0.005f, false, 0.0f, 1.0f, 4.0f, true, 1.0f, 0.5f)
                4 -> ts(1.05f, 0.5f, 0.1f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 1.5f, 0.2f)
                5 -> ts(1.6f, 0.5f, 0.01f, 0.01f, false, 0.0f, 1.0f, 4.0f, true, 0.25f, 0.75f)
                6 -> ts(1.5f, 0.5f, 0.003f, 0.003f, false, 0.0f, 1.0f, 4.0f, true, 0.4f, 0.5f)
                7 -> ts(1.15f, 0.5f, 0.04f, 0.006f, false, 0.0f, 0.0f, 0.5f, true, 0.5f, 2.0f)
                8 -> ts(1.6f, 0.5f, 0.01f, 0.008f, true, 0.25f, 0.0f, 1.0f, true, 1.0f, 0.75f)
                9 -> ts(1.8f, 0.5f, 0.001f, 0.015f, false, 0.0f, 1.0f, 4.0f, true, 1.0f, 1.0f)
                10 -> ts(1.0f, 0.35f, 0.02f, 0.0f, true, 0.55f, 0.0f, 2.0f, true, 1.13f, 1.0f)
                11 -> ts(1.15f, 0.5f, 0.04f, 0.0f, false, 1.0f, 1.0f, 1.0f, false, 1.0f, 0.6f)
                12 -> ts(1.5f, 0.5f, 0.003f, 0.01f, true, 0.25f, 0.0f, 4.0f, true, 1.0f, 1.0f)
                13 -> ts(1.2f, 0.5f, 0.02f, 0.0f, false, 0.0f, 1.0f, 1.0f, false, 0.0f, 0.6f)
                else -> {}
            }
            // Core: if (whiteSel != 0) { cwp_lm = whiteLimit; cwp = whiteSel - 1; }
            if (whiteSel != 0) {
                cwpLm = whiteLimit
                cwp = whiteSel - 1
            }
        }
    }

    // kCWP_NORM_709 / kCWP_NORM_P3 (OpenDrt.cpp:30-31, verbatim).
    private val CWP_NORM_709 =
        floatArrayOf(0.744192699063f, 0.873470832146f, 1.0f, 0.955936992163f, 0.905671332781f, 0.850004385027f)
    private val CWP_NORM_P3 =
        floatArrayOf(0.762687057298f, 0.884054083328f, 1.0f, 0.964320186739f, 0.923076518860f, 0.876572837784f)

    /**
     * The OpenDRT user parameters (the wire's output block — the same
     * ordinals OpenDrtParamsV2 carries).
     */
    data class Spec(
        val look: Int = 0,
        val tonescale: Int = 0,
        val cwp: Int = 0,
        val cwpLimit: Float = 0.25f,
        val grey: Float = 10.0f,
        /** 0 sRGB 2.2, 1 Rec.1886 2.4, 2 HLG 1000 (rawedit OpenDrtDisplay). */
        val display: Int = 0,
    )

    /**
     * Resolves one spec into the 77-float uniform pack — the Impl
     * constructor's own math (OpenDrt.cpp:241-288), carried 1:1. The
     * returned FloatArray is what setColorModelChain threads for
     * outputMode 1; length is asserted [SLOT_COUNT].
     */
    fun resolve(spec: Spec): FloatArray {
        val p = P()
        p.preset(spec.look, spec.tonescale, spec.cwp, spec.cwpLimit)
        val hdr = spec.display == 2
        // Core: eotf = hdr ? 5 : (display == SdrRec1886 ? 2 : 1)
        val eotf = if (hdr) 5 else (if (spec.display == 1) 2 else 1)
        // int rAB = max(0, min(6, int(r)));
        val cwpIn = p.cwp.coerceIn(0, 5)

        // ---- the tonescale constants (Impl's constructor, verbatim) ----
        val tnSu = if (hdr) 0 else (if (spec.display == 1) 1 else 2)
        val tnLp = if (hdr) 1000f else 100f
        val tnLg = spec.grey
        val tnGb = 0.13f
        val ptHdr = 0.5f

        val tsX1 = exp2f_(6f * p.tnSh + 4f)
        val tsY1 = tnLp / 100f
        val tsX0 = 0.18f + p.tnOff
        val tsY0 = tnLg / 100f * (1f + tnGb * (ln(tsY1) / ln(2f)).toFloat())
        val tsS0 = compressToeQuadratic(tsY0, p.tnToe, true)
        val tsP = p.tnCon / (1f + tnSu * 0.05f)
        val tsS10 = tsX0 * (tsS0.pow(-1f / p.tnCon) - 1f)
        val tsM1 = tsY1 / (tsX1 / (tsX1 + tsS10)).pow(p.tnCon)
        val tsM2 = compressToeQuadratic(tsM1, p.tnToe, true)
        val tsS = tsX0 * ((tsS0 / tsM2).pow(-1f / p.tnCon) - 1f)
        val tsDsc = if (eotf == 5) 0.1f else 100f / tnLp
        val ptCmpLf = ptHdr * min(1f, (tnLp - 100f) / 900f)
        val sLp100 = tsX0 * ((tnLg / 100f).pow(-1f / p.tnCon) - 1f)
        val tsS1 = tsS * ptCmpLf + sLp100 * (1f - ptCmpLf)
        val lconM = exp2f_(-p.tnLcon)
        val lw = p.tnLconW / 4f
        val lconW = lw * lw
        val lconSc = if (p.tnLconEnable) {
            compressToeCubic(tsX0, lconM, lconW, true) / tsX0
        } else {
            1f
        }
        val hconP = exp2f_(p.tnHcon)

        val out = FloatArray(SLOT_COUNT)
        out[0] = eotf.toFloat()
        out[1] = if (hdr) 1f else 0f
        out[2] = cwpIn.toFloat()
        out[3] = CWP_NORM_709[cwpIn]
        out[4] = CWP_NORM_P3[cwpIn]
        // [5..8] tn
        out[5] = p.tnCon; out[6] = p.tnSh; out[7] = p.tnToe; out[8] = p.tnOff
        // [9..12] rs
        out[9] = p.rsSa; out[10] = p.rsRw; out[11] = p.rsBw; out[12] = p.cwpLm
        // [13..16] toe: brl, brl_rng, brl_st, tsDsc
        out[13] = p.brl; out[14] = p.brlRng; out[15] = p.brlSt; out[16] = tsDsc
        // [17..20] brlRgb: brl_r, brl_g, brl_b, brlp
        out[17] = p.brlR; out[18] = p.brlG; out[19] = p.brlB; out[20] = p.brlp
        // [21..24] brlpRgb: brlp_r, brlp_g, brlp_b, lconSc
        out[21] = p.brlpR; out[22] = p.brlpG; out[23] = p.brlpB; out[24] = lconSc
        // [25..28] lcon: lconM, lconW, hconP, tn_hcon_pv
        out[25] = lconM; out[26] = lconW; out[27] = hconP; out[28] = p.tnHconPv
        // [29..32] hcon: tn_hcon_st, tsM2, tsS, tsP
        out[29] = p.tnHconSt; out[30] = tsM2; out[31] = tsS; out[32] = tsP
        // [33..36] ts: tsS1, sLp100, 0, 0
        out[33] = tsS1; out[34] = sLp100; out[35] = 0f; out[36] = 0f
        // [37..48] the hue pairs: R, G, B, C, M, Y
        out[37] = p.hsR; out[38] = p.hsRRng
        out[39] = p.hsG; out[40] = p.hsGRng
        out[41] = p.hsB; out[42] = p.hsBRng
        out[43] = p.hsC; out[44] = p.hsCRng
        out[45] = p.hsM; out[46] = p.hsMRng
        out[47] = p.hsY; out[48] = p.hsYRng
        // [49..52] conf0: brl_en, brlp_en, hc_en, ptl_en
        out[49] = if (p.brlEnable) 1f else 0f
        out[50] = if (p.brlpEnable) 1f else 0f
        out[51] = if (p.hcEnable) 1f else 0f
        out[52] = if (p.ptlEnable) 1f else 0f
        // [53..56] conf1: ptm_en, hs_rgb_en, hs_cmy_en, lcon_en
        out[53] = if (p.ptmEnable) 1f else 0f
        out[54] = if (p.hsRgbEnable) 1f else 0f
        out[55] = if (p.hsCmyEnable) 1f else 0f
        out[56] = if (p.tnLconEnable) 1f else 0f
        // [57..60] conf2: hcon_en, 0, hc_r, hc_r_rng
        out[57] = if (p.tnHconEnable) 1f else 0f
        out[58] = 0f
        out[59] = p.hcR; out[60] = p.hcRRng
        // [61..64] pt
        out[61] = p.ptLml; out[62] = p.ptLmlR; out[63] = p.ptLmlG; out[64] = p.ptLmlB
        // [65..68] pt2: pt_lmh, pt_lmh_r, pt_lmh_b, ptl_c
        out[65] = p.ptLmh; out[66] = p.ptLmhR; out[67] = p.ptLmhB; out[68] = p.ptlC
        // [69..72] pt3: ptl_m, ptl_y, ptm_low, ptm_low_rng
        out[69] = p.ptlM; out[70] = p.ptlY; out[71] = p.ptmLow; out[72] = p.ptmLowRng
        // [73..76] pt4: ptm_low_st, ptm_high, ptm_high_rng, ptm_high_st
        out[73] = p.ptmLowSt; out[74] = p.ptmHigh; out[75] = p.ptmHighRng; out[76] = p.ptmHighSt
        return out
    }
}
