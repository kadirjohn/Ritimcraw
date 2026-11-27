package com.ritim.ritimcraw.export.hevc

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * BT.2100 Hybrid Log-Gamma (ARIB STD-B67) as PURE Kotlin, package E2-a
 * (night 2026-11-27). The whole HLG signal math the export side needs,
 * with no android.media and no GPU: the same constants the C++ core's
 * `hlgInverseEotf` (OpenDrt.cpp) already uses, so the JVM layer can be
 * tested against the ITU-R BT.2100 reference tables before E2-b ever
 * touches a shader.
 *
 * THE THREE FUNCTIONS (the standard's own shapes):
 *  - [oetf] — scene light E in [0, 1] -> signal: sqrt(3*E) below the
 *    1/12 knee, the log curve above it. The constants a/b/c are the
 *    standard's EXACT values (a=0.17883277, b=0.28466892, c=0.55991073).
 *  - [inverseOetf] — the exact algebraic inverse (signal in [0, 1] ->
 *    scene light). At the 0.5 breakpoint both branches agree (the curve
 *    is C1-continuous by construction).
 *  - [ootf] / [inverseOotf] — the optical-optical transfer, display
 *    light = scene light^gamma (gamma=1.2 at 1000 nits, BT.2100 Table 5,
 *    applied on the LUMA-weighted signal per the standard; the grey
 *    single-channel form is the compositor's and the test's).
 *
 * BT.2408's SDR-in-HLG composite anchor lives here as DATA: an SDR
 * reference white of 203 cd/m^2 in a 1000-nit HLG signal is signal
 * 0.75 — the diffuse-white level every SDR graphic overlay must sit
 * at (and the 10-bit limited code it lands on is [HLG_DIFFUSE_WHITE_10_BIT_CODE]).
 *
 * PURE: every function is deterministic, total (no NaN for the defined
 * domain, clamps outside it) and side-effect free.
 */
object Bt2100Hlg {

    /** The standard's exact OETF constants (BT.2100 Table 5, precision as published). */
    const val A = 0.17883277
    const val B = 0.28466892
    const val C = 0.55991073

    /** The OETF knee: E <= 1/12 takes the sqrt branch. */
    const val KNEE_SCENE = 1.0 / 12.0

    /** The OETF's value at the knee — both branches agree here exactly. */
    const val KNEE_SIGNAL = 0.5

    /** The OOTF exponent at 1000 nits (BT.2100 Table 5). */
    const val OOTF_GAMMA_1000 = 1.2

    /**
     * BT.2408's SDR reference white mapped into HLG: 203 cd/m^2 of a
     * 1000-nit display, i.e. the diffuse-white HLG SIGNAL of 0.75
     * (plan E2 3.3; the 10-bit limited quantization code it lands on
     * is 721 = 64 + 0.75 * 876).
     */
    const val SDR_WHITE_NITS = 203.0
    const val HLG_PEAK_NITS = 1000.0
    const val DIFFUSE_WHITE_SIGNAL = 0.75
    const val DIFFUSE_WHITE_10_BIT_CODE = 721

    /**
     * The scene-referred OETF: scene light in [0, 1] -> HLG signal in
     * [0, 1]. Values outside [0, 1] clamp first (the curve is only
     * defined there).
     */
    fun oetf(scene: Double): Double {
        val e = scene.coerceIn(0.0, 1.0)
        return if (e <= KNEE_SCENE) sqrt(3.0 * e) else A * ln(12.0 * e - B) + C
    }

    /**
     * The exact inverse OETF: HLG signal in [0, 1] -> scene light.
     * The algebraic inverse of [oetf]; the branches trade at the same
     * 0.5 breakpoint.
     */
    fun inverseOetf(signal: Double): Double {
        val s = signal.coerceIn(0.0, 1.0)
        return if (s < KNEE_SIGNAL) s * s / 3.0 else (exp((s - C) / A) + B) / 12.0
    }

    /**
     * The OOTF: SCENE light -> DISPLAY light at [gamma] (1.2 for the
     * 1000-nit reference display, BT.2100 Table 5). The full standard
     * applies the exponent to the luma-normalized RGB (Yd = Yd^gamma-1
     * * scene RGB); [ootfGrey] is the single-channel/grey form the
     * compositor and the tests use. For a GREY input the two agree.
     */
    fun ootfGrey(scene: Double, gamma: Double = OOTF_GAMMA_1000): Double =
        scene.coerceIn(0.0, 1.0).pow(gamma)

    /**
     * The inverse OOTF for grey: DISPLAY light (in units of the display's
     * peak, 1.0 = 1000 nits) -> scene light.
     */
    fun inverseOotfGrey(display: Double, gamma: Double = OOTF_GAMMA_1000): Double {
        require(gamma > 0.0) { "gamma must be positive" }
        val d = display.coerceIn(0.0, 1.0)
        return if (d == 0.0) 0.0 else d.pow(1.0 / gamma)
    }

    /**
     * The compositor's one number: display grey in nits -> the HLG
     * signal that represents it on a 1000-nit display (inverse OOTF,
     * then OETF). 203 nits -> 0.75 (BT.2408's diffuse white, 1e-3 within
     * the plan's own 203->0.7499 calculation).
     */
    fun hlgSignalForDisplayNits(nits: Double, peakNits: Double = HLG_PEAK_NITS): Double {
        require(peakNits > 0.0) { "peak must be positive" }
        return oetf(inverseOotfGrey(nits / peakNits))
    }

    /**
     * The encoder-quantization side (plan 3.4): HLG signal [0, 1] ->
     * the 10-bit LIMITED-range code (64 + signal * 876). The plan's
     * 0.75 -> 721 anchor computed the same way; kept next to the math
     * so the composite constant and its derivation cannot drift apart.
     */
    fun limitedRange10BitCode(signal: Double): Int {
        val s = signal.coerceIn(0.0, 1.0)
        return (64.0 + s * 876.0).let { Math.round(it).toInt() }
    }
}
