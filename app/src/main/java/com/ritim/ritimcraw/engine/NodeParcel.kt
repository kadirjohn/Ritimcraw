package com.ritim.ritimcraw.engine

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The NODES pass's uniform pack (color model v2 F1-5) — the Kotlin half of
 * the hand-written mirror pair whose C++ half is
 * app/src/main/cpp/gles/NodeParcel.h. The slot layout (header + per-node
 * stride + the output block) is documented THERE in full; this object
 * carries the same constants, the encoder and the validating decode.
 * The stale-mirror catch is NodeParcelTest reading the committed C++
 * header and asserting every constexpr matches (the GradeParcelTest
 * mechanism, hand-paired because the gen-grade-parcel.py schema owns the
 * 29-slot GRADE parcel only — the inventory §4.1: "üretici betiğe bu
 * pakette DOKUNULMAZ").
 *
 * WHAT RIDES HERE: each node crosses PRE-COMPILED — the exact
 * rawedit::ColorModel compile() formulas (wheelRgb's cosines, the
 * primaries solve, the hue rotation matrix, the WB vector) carried to
 * Kotlin 1:1 and pinned by NodeParcelTest's parity vectors against the
 * same numbers test_color_model holds the CPU to. NodeParcelTest
 * additionally re-derives every value through this object's own
 * compile-for-Kotlin path and locks the expected outputs, so the seam
 * can be audited on the JVM with no core .so and no device.
 *
 * WHAT IS DELIBERATELY NOT HERE (the exclusion list, mirrored from the
 * C++ header): the curve TABLES (they ride the pipeline's 1D R32F atlas,
 * revision-cached — 3 x 512 floats per node never cross the seam) and
 * the node LUT tables + their 12-float binding sets (per-JOB uploads
 * keyed by the lutIndex order this parcel fixes; lutPath-style strings
 * cross separately, as the stage-24 precedent).
 */
object NodeParcel {

    /** The layout's three constants — cross-checked against NodeParcel.h. */
    const val HEADER = 2
    const val STRIDE = 36
    const val OUTPUT = 6

    /** The chain's N1 caps (rawedit kMaxNodes / kMaxNodeLuts). */
    const val MAX_NODES = 8
    const val MAX_NODE_LUTS = 4

    // Per-node record slots (STRIDE of them; see NodeParcel.h's table).
    const val SLOT_ENABLED = 0
    const val SLOT_MIX = 1
    const val SLOT_LIFT = 2
    const val SLOT_GAMMA_EXP = 5
    const val SLOT_GAIN = 8
    const val SLOT_OFFSET = 11
    const val SLOT_Y_SIDE = 14
    const val SLOT_CONTRAST = 17
    const val SLOT_PIVOT = 18
    const val SLOT_WB = 19
    const val SLOT_HUE_MAT = 22
    const val SLOT_SAT = 31
    const val SLOT_HAS_CURVES = 32
    const val SLOT_HAS_LUT = 33
    const val SLOT_LUT_INDEX = 34
    const val SLOT_LUT_MIX = 35

    // The output block's slots (HEADER + n * STRIDE).
    const val SLOT_OUT_LOOK = 0
    const val SLOT_OUT_TONESCALE = 1
    const val SLOT_OUT_CWP = 2
    const val SLOT_OUT_CWP_LIMIT = 3
    const val SLOT_OUT_GREY = 4
    const val SLOT_OUT_EOTF = 5

    /** The DI code of 18% grey (rawedit kDiMidGray): the node pivot default. */
    const val DI_MID_GRAY = 0.336043272f

    /**
     * One compiled node — the encoder's input. Every field is what
     * rawedit::CompiledNode carries; the JVM-side F1-6 binding (the
     * GradeK -> node chain mapping) will fill these through
     * [compileNode], the same formulas.
     */
    data class CompiledNode(
        val enabled: Boolean = true,
        val mix: Float = 1.0f,
        val lift: FloatArray = floatArrayOf(0f, 0f, 0f),
        val gammaExp: FloatArray = floatArrayOf(1f, 1f, 1f),
        val gain: FloatArray = floatArrayOf(1f, 1f, 1f),
        val offset: FloatArray = floatArrayOf(0f, 0f, 0f),
        val ySide: FloatArray = floatArrayOf(0f, 1f, 1f),
        val contrast: Float = 1.0f,
        val pivot: Float = DI_MID_GRAY,
        val wb: FloatArray = floatArrayOf(1f, 1f, 1f),
        val hueMat: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
        val sat: Float = 1.0f,
        val hasCurves: Boolean = false,
        val hasLut: Boolean = false,
        val lutIndex: Int = -1,
        val lutMix: Float = 1.0f,
    )

    /** The output transform block. */
    data class OutputTransform(
        /** 0 = legacy (TAIL's v1 variant, linear 709 in/out); 1 = DwgLinear
         *  into TAIL's OpenDRT variant. */
        val mode: Int = 0,
        val look: Int = 0,
        val tonescale: Int = 0,
        val cwp: Int = 0,
        val cwpLimit: Float = 0.25f,
        val grey: Float = 10.0f,
        /** OpenDrtDisplay ordinals: 0 sRGB 2.2, 1 Rec.1886, 2 HLG 1000. */
        val eotf: Int = 0,
    )

    /**
     * Encodes the chain + the output transform into the flat parcel.
     * [nodes] may exceed [MAX_NODES] only by refusing — encode throws the
     * same honest IllegalStateException the decode uses (never a silent
     * truncation).
     */
    fun encode(nodes: List<CompiledNode>, output: OutputTransform): FloatArray {
        if (nodes.size > MAX_NODES) refuse("node parcel: ${nodes.size} nodes exceed the N1 cap $MAX_NODES (recovery: the editor caps the chain at 8)")
        val lutSlots = nodes.filter { it.hasLut }.size
        if (lutSlots > MAX_NODE_LUTS) refuse("node parcel: $lutSlots node LUTs exceed the N1 cap $MAX_NODE_LUTS")
        val parcel = FloatArray(HEADER + nodes.size * STRIDE + OUTPUT)
        parcel[0] = nodes.size.toFloat()
        parcel[1] = output.mode.toFloat()
        nodes.forEachIndexed { i, n ->
            val b = HEADER + i * STRIDE
            parcel[b + SLOT_ENABLED] = if (n.enabled) 1.0f else 0.0f
            parcel[b + SLOT_MIX] = n.mix.coerceIn(0f, 1f)
            (0..2).forEach { c ->
                parcel[b + SLOT_LIFT + c] = n.lift[c]
                parcel[b + SLOT_GAMMA_EXP + c] = n.gammaExp[c]
                parcel[b + SLOT_GAIN + c] = n.gain[c]
                parcel[b + SLOT_OFFSET + c] = n.offset[c]
                parcel[b + SLOT_Y_SIDE + c] = n.ySide[c]
                parcel[b + SLOT_WB + c] = n.wb[c]
            }
            parcel[b + SLOT_CONTRAST] = n.contrast
            parcel[b + SLOT_PIVOT] = n.pivot
            (0..8).forEach { m -> parcel[b + SLOT_HUE_MAT + m] = n.hueMat[m] }
            parcel[b + SLOT_SAT] = n.sat
            parcel[b + SLOT_HAS_CURVES] = if (n.hasCurves) 1.0f else 0.0f
            parcel[b + SLOT_HAS_LUT] = if (n.hasLut) 1.0f else 0.0f
            parcel[b + SLOT_LUT_INDEX] = if (n.hasLut) n.lutIndex.toFloat() else -1.0f
            parcel[b + SLOT_LUT_MIX] = n.lutMix.coerceIn(0f, 1f)
        }
        val o = HEADER + nodes.size * STRIDE
        parcel[o + SLOT_OUT_LOOK] = output.look.toFloat()
        parcel[o + SLOT_OUT_TONESCALE] = output.tonescale.toFloat()
        parcel[o + SLOT_OUT_CWP] = output.cwp.toFloat()
        parcel[o + SLOT_OUT_CWP_LIMIT] = output.cwpLimit
        parcel[o + SLOT_OUT_GREY] = output.grey
        parcel[o + SLOT_OUT_EOTF] = output.eotf.toFloat()
        return parcel
    }

    /** The typed decode of the header + output block (the per-node stride
     *  is validated slot-by-slot; the JVM consumer re-reads what it wrote). */
    fun validate(parcel: FloatArray) {
        if (parcel.size < HEADER + OUTPUT) refuse("node parcel: too short (${parcel.size}, min ${HEADER + OUTPUT})")
        val n = parcel[0].toInt()
        if (n < 0 || n > MAX_NODES || parcel.size != HEADER + n * STRIDE + OUTPUT) {
            refuse("node parcel: length ${parcel.size} does not match nodeCount $n (recovery: re-encode the chain with NodeParcel.encode)")
        }
        parcel.forEachIndexed { i, v ->
            if (!v.isFinite()) refuse("node parcel: slot $i is not finite (recovery: the parcel must carry finite floats)")
        }
        var luts = 0
        for (i in 0 until n) {
            val b = HEADER + i * STRIDE
            fun bool(slot: Int, name: String) {
                if (parcel[b + slot] != 0.0f && parcel[b + slot] != 1.0f) {
                    refuse("node parcel: node $i $name must be 0.0 or 1.0 (got ${parcel[b + slot]})")
                }
            }
            bool(SLOT_ENABLED, "enabled")
            bool(SLOT_HAS_CURVES, "hasCurves")
            bool(SLOT_HAS_LUT, "hasLut")
            if (parcel[b + SLOT_HAS_LUT] == 1.0f) {
                val idx = parcel[b + SLOT_LUT_INDEX]
                if (idx < 0f || idx > (MAX_NODE_LUTS - 1).toFloat()) {
                    refuse("node parcel: node $i lutIndex $idx outside 0..${MAX_NODE_LUTS - 1}")
                }
                ++luts
            }
        }
        if (luts > MAX_NODE_LUTS) refuse("node parcel: $luts node LUTs exceed the N1 cap $MAX_NODE_LUTS")
    }

    /**
     * rawedit::wheelRgb, verbatim (core/src/ColorModel.cpp:44-55) — the
     * wheel puck -> RGB trigram. Pinned by NodeParcelTest's parity vectors.
     */
    fun wheelRgb(x: Float, y: Float, out: FloatArray) {
        val m = min(1.0f, hypot(x, y))
        if (m < 1e-6f) {
            out[0] = 0.0f; out[1] = 0.0f; out[2] = 0.0f
            return
        }
        val th = atan2(y, x)
        val k = m * 2.0f / 3.0f
        out[0] = k * cos(th)
        out[1] = k * cos(th - 2.0f * PI.toFloat() / 3.0f)
        out[2] = k * cos(th + 2.0f * PI.toFloat() / 3.0f)
    }

    /**
     * rawedit::primaries, verbatim (core/src/ColorModel.cpp:57-64): the
     * lift/gain/gamma triple -> (black, slope, expo). Returns the three.
     */
    fun primaries(lift: Float, gain: Float, gamma: Float): FloatArray {
        val d = max(1.0f + lift * (gain - 1.0f), 0.05f)
        val expo = if (gamma >= 0.0f) 1.0f / (1.0f + gamma) else 1.0f - gamma
        val black = gain * lift / d
        val slope = gain * (1.0f - lift) / d
        return floatArrayOf(black, slope, expo)
    }

    /**
     * One GradeNode-shaped knob set -> the compiled pack, the Kotlin seam's
     * compile() twin (the per-channel loop in core/src/ColorModel.cpp:99-126
     * verbatim; the GradeK -> node mapping is F1-6's job — this function
     * only turns knobs into the pack the parcel carries).
     */
    fun compileNode(
        liftX: Float = 0f, liftY: Float = 0f, liftMaster: Float = 0f,
        gammaX: Float = 0f, gammaY: Float = 0f, gammaMaster: Float = 0f,
        gainX: Float = 0f, gainY: Float = 0f, gainMaster: Float = 0f,
        offsetX: Float = 0f, offsetY: Float = 0f, offsetMaster: Float = 0f,
        contrast: Float = 1.0f,
        pivot: Float = DI_MID_GRAY,
        sat: Float = 1.0f,
        hue: Float = 0.0f,
        temp: Float = 0.0f,
        tint: Float = 0.0f,
        mix: Float = 1.0f,
        enabled: Boolean = true,
    ): CompiledNode {
        val l = FloatArray(3); val g = FloatArray(3)
        val gn = FloatArray(3); val o = FloatArray(3)
        wheelRgb(liftX, liftY, l)
        wheelRgb(gammaX, gammaY, g)
        wheelRgb(gainX, gainY, gn)
        wheelRgb(offsetX, offsetY, o)
        val lift = FloatArray(3); val gain = FloatArray(3)
        val gammaExp = FloatArray(3); val offset = FloatArray(3)
        for (i in 0..2) {
            val p = primaries(
                l[i] * 0.25f + liftMaster * 0.5f,
                max(1.0f + gn[i] * 0.5f + gainMaster, 0.0f),
                g[i] * 0.5f + gammaMaster,
            )
            lift[i] = p[0]; gain[i] = p[1]; gammaExp[i] = p[2]
            offset[i] = o[i] * 0.2f + offsetMaster * 0.25f
        }
        val yP = primaries(liftMaster * 0.5f, max(1.0f + gainMaster, 0.0f), gammaMaster)
        val wb = floatArrayOf(1.0f + 0.18f * temp, 1.0f - 0.12f * tint, 1.0f - 0.18f * temp)
        val a = hue * PI.toFloat() / 180.0f
        val cs = cos(a); val sn = kotlin.math.sin(a)
        val k = 1.0f / 3.0f; val sq = sqrt(k)
        val hueMat = floatArrayOf(
            cs + (1 - cs) * k, k * (1 - cs) - sq * sn, k * (1 - cs) + sq * sn,
            k * (1 - cs) + sq * sn, cs + k * (1 - cs), k * (1 - cs) - sq * sn,
            k * (1 - cs) - sq * sn, k * (1 - cs) + sq * sn, cs + k * (1 - cs),
        )
        return CompiledNode(
            enabled = enabled,
            mix = mix.coerceIn(0f, 1f),
            lift = lift,
            gammaExp = gammaExp,
            gain = gain,
            offset = offset,
            ySide = yP,
            contrast = contrast,
            pivot = pivot,
            wb = wb,
            hueMat = hueMat,
            sat = sat,
        )
    }

    private fun refuse(message: String): Nothing = throw IllegalStateException(message)
}
