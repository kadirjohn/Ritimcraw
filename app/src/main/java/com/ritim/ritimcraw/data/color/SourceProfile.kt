package com.ritim.ritimcraw.data.color

/**
 * The color contract of ONE media source (B1, night research 2026-11-16):
 * what the pixels ARE before the app touches them — primaries, transfer,
 * YCbCr matrix and range, bit depth, and where each fact came from.
 *
 * This is the model the routing decision ([planFor]) turns into an ordered
 * transform plan. It is NOT:
 *  - the LUT's in/out contract (that is [com.ritim.ritimcraw.data.LutInterpK],
 *    the managed-LUT interpretation — a different axis: what a .cube
 *    expects, not what the source carries);
 *  - the export profile (that is the plan's display/export side, derived
 *    from the working space below).
 *
 * PURE MODEL: no Android imports, JVM-testable, nothing in the app imports
 * it yet (the integrator wires it — see docs/COLOR_ROUTING_PLAN.md).
 *
 * The transfer set follows [com.ritim.ritimcraw.data.LutTransfer]'s
 * precedent (the codebase's canonical transfer list) plus the two
 * container-declared curves the encoded path can meet (HLG, PQ —
 * MediaFormat's COLOR_TRANSFER_HLG/ST2084, read by MetadataFacts) and
 * Mi-Log itself (the encoded path's source curve today —
 * core/src/Transfer.cpp's miLogToLinear). The primaries set is the
 * container-declared set (BT.601/709/2020 — MediaFormat's
 * COLOR_STANDARD_* keys), NOT LutGamut's camera-gamut list: a media
 * container declares standards, not camera gamuts.
 */
enum class SourcePrimaries {
    /** ITU-R BT.601 (525/625) — legacy SD; the container's BT601 tags. */
    BT601,
    /** ITU-R BT.709 — the H.264/HEVC SDR convention (EncodedAdapters' untagged default). */
    BT709,
    /** ITU-R BT.2020 — Mi-Log's native primaries (the Xiaomi DCTL) and HDR containers. */
    BT2020,
}

/**
 * The source transfer curve. Mirrors the curves the codebase already
 * names (LutTransfer's precedent) plus the container-declared HDR pair and
 * Mi-Log:
 *  - Linear, Srgb, Rec709OETF: LutTransfer 0/1/2's identities;
 *  - HLG, PQ (ST 2084): MediaFormat COLOR_TRANSFER_HLG/ST2084 —
 *    MetadataFacts maps them today, the develop does not decode them yet;
 *  - MiLog: the encoded path's source curve (core/src/Transfer.cpp:24-30,
 *    constants verbatim from the Xiaomi DCTL).
 */
enum class SourceTransfer {
    Linear,
    Srgb,
    Rec709OETF,
    Hlg,
    Pq,
    MiLog,
}

/** The YCbCr -> RGB matrix the decode uses. */
enum class SourceMatrix {
    BT601,
    BT709,
    /** BT.2020 non-constant-luminance (Mi-Log: matrix_coefficients 9). E0. */
    BT2020,
}

/** The YCbCr range expansion. */
enum class SourceRange {
    /** SMPTE narrow range: 8-bit 16-235 luma / 16-240 chroma (developEncoded's current assumption). */
    Limited,
    /** 0-255 (JFIF): the container's COLOR_RANGE_FULL. */
    Full,
}

/**
 * Where a profile fact came from. The honesty axis MetadataFacts already
 * models (Field.known vs unknown): a fact is only as good as its source,
 * and a user override must be distinguishable from a container
 * declaration so a re-scan can decide what to keep.
 */
enum class ProfileProvenance {
    /** The container declares it (MediaFormat's color keys at open time). */
    Container,
    /** Measured/probed from the bitstream (no container tag — a future prober). */
    Probed,
    /** The user overrode it (a manual clip-profile edit; wins over the above). */
    UserOverride,
}

/**
 * The working space the develop pipeline grades in. EXPLICIT so the
 * current linear-Rec.709 choice is one named decision, and a future
 * wide-gamut switch (the ACES pass, Transfer.h:59-61's deferred
 * conversion) changes ONE value here — not a rewrite.
 *
 * TODAY the only working space is LinearRec709 (core treats the decoded
 * RGB as the working space directly — Transfer.h:59-61; Color.h's
 * managed-LUT bindings likewise compile against Rec709,
 * core/src/Color.cpp:570-584).
 */
enum class WorkingSpace {
    /** Scene-linear Rec.709 primaries — the pipeline's working space today. */
    LinearRec709,
}

/**
 * A media source's color contract. Every field is a FACT about the source
 * plus where the fact came from; UNKNOWN is modeled by a null provenance
 * (the container said nothing and nobody probed or overrode) — the profile
 * never silently defaults a fact the container did not declare
 * (MetadataFacts' honesty rule: null = UNKNOWN, never a default).
 */
data class SourceProfile(
    /** The source primaries. */
    val primaries: SourcePrimaries,
    /** The source transfer curve. */
    val transfer: SourceTransfer,
    /** The YCbCr matrix (only meaningful for YUV sources). */
    val matrix: SourceMatrix,
    /** The YCbCr range (only meaningful for YUV sources). */
    val range: SourceRange,
    /** Sample bit depth (8 today; 10 the container can declare). */
    val bitDepth: Int,
    /** Per-field provenance; null = UNKNOWN (never a silent default). */
    val primariesProvenance: ProfileProvenance?,
    val transferProvenance: ProfileProvenance?,
    val matrixProvenance: ProfileProvenance?,
    val rangeProvenance: ProfileProvenance?,
    val bitDepthProvenance: ProfileProvenance?,
) {
    init {
        // The NaN/unknown-rejection rules: a profile with a non-finite or
        // non-positive bit depth is not a contract, it is a bug upstream —
        // reject at construction, the same way GradeParcel refuses
        // NaN/Infinity rather than shipping them across the seam.
        require(bitDepth > 0) { "bit depth must be positive, got $bitDepth" }
    }

    companion object {
        /**
         * The profile the CURRENT encoded path implements, stated as a
         * profile (the migration baseline): BT.709 primaries, Mi-Log
         * transfer, BT.709 matrix, limited range, 8-bit. Every
         * provenance is null — UNKNOWN — because today's path does not
         * READ these facts (it reads only the BT.601/709 matrix tag,
         * EncodedAdapters.kt:102-113, and assumes the rest: the range
         * by YuvFrame's contract, the Mi-Log inverse for every clip,
         * Transfer.cpp:142-150). The integrator derives REAL profiles
         * at open time; this constant exists so the migration rule
         * ("existing projects keep the current look") has a concrete
         * baseline to test against.
         */
        fun assumedEncoded(): SourceProfile = SourceProfile(
            primaries = SourcePrimaries.BT709,
            transfer = SourceTransfer.MiLog,
            matrix = SourceMatrix.BT709,
            range = SourceRange.Limited,
            bitDepth = 8,
            primariesProvenance = null,
            transferProvenance = null,
            matrixProvenance = null,
            rangeProvenance = null,
            bitDepthProvenance = null,
        )
    }
}

/**
 * ONE step in the ordered transform plan. DATA ONLY — the integrator
 * implements each kind against core's existing functions; this type names
 * WHAT must happen and in WHAT order, never how.
 */
sealed class PlanStep {
    /**
     * Expand the YCbCr samples to full-range floats and apply the
     * matrix -> RGB. [matrix] and [range] come from the profile; the
     * integrator maps them to core's yuv601ToRgb/yuv709ToRgb
     * (Transfer.cpp:39-58, limited-range today) or their full-range
     * analogs.
     */
    data class YcbcrToRgb(
        val matrix: SourceMatrix,
        val range: SourceRange,
        val bitDepth: Int,
    ) : PlanStep()

    /**
     * Apply the source transfer's inverse -> scene linear. [NONE is
     * modeled by the ABSENCE of this step] — the fix shape for the
     * Mi-Log-unconditional bug (Transfer.cpp:147-150): a non-Mi-Log
     * source's plan simply does not contain this step.
     */
    data class DecodeTransfer(val transfer: SourceTransfer) : PlanStep()

    /**
     * Convert the source primaries to the working space's primaries.
     * ABSENT when the source primaries already ARE the working space's
     * (the identity — core's gamutToGamut returns identity for
     * from == to, Color.cpp:549-553). The matrix itself is provided by
     * the reference vectors (SourceProfileVectors.rec2020ToRec709) —
     * the step carries the DECISION, not the numbers.
     */
    data class GamutToWorking(
        val from: SourcePrimaries,
        val to: SourcePrimaries,
    ) : PlanStep()

    /**
     * Convert the working space to the display/export encoding. TODAY
     * this is the Rec.709 OETF the bridge tail applies
     * (jni_bridge.cpp:5142-5149 after developEncoded; the shader's
     * uOetf gate on the GPU side, GlesShaderSources.kt:632-636) — a
     * linear-Rec.709 working space with a Rec.709 display needs no
     * gamut conversion, only the OETF.
     */
    data class WorkingToDisplay(
        val displayPrimaries: SourcePrimaries,
        val displayTransfer: SourceTransfer,
    ) : PlanStep()
}

/**
 * The ordered transform plan for one source into the working space and
 * out to the display. DATA ONLY. The order is the contract:
 * YCbCr -> RGB -> transfer inverse -> gamut to working -> (grade) ->
 * gamut to display -> display transfer. The grade sits BETWEEN the
 * working-space steps by definition (the working space is what the
 * grade operates on) — the plan carries the pre-grade decode side and
 * the post-grade display side; the grade itself is not a plan step.
 */
data class ColorPlan(
    val source: SourceProfile,
    val workingSpace: WorkingSpace,
    /** Pre-grade: decode the source into the working space. */
    val decodeSteps: List<PlanStep>,
    /** Post-grade: the working space out to the display/export encoding. */
    val displaySteps: List<PlanStep>,
)

/**
 * The routing DECISION (pure): given a source profile and the working
 * space, produce the ordered plan. No implementation — the integrator
 * turns each step into core calls; this function is the single place the
 * ORDER lives, so the integrator's "which stage where" question has one
 * authoritative answer to test against.
 *
 * The rules (each pinned by a test):
 *  - Identity: a linear Rec.709 limited/any-range source in the
 *    LinearRec709 working space produces NO decode-transfer and NO
 *    gamut steps — only the YCbCr expansion and the display OETF.
 *  - Mi-Log: the plan CONTAINS DecodeTransfer(MiLog) and, because
 *    Mi-Log's native primaries are Rec.2020, the plan the CURRENT code
 *    implements (assumedEncoded) also carries GamutToWorking(BT2020,
 *    BT709) — the deferred conversion Transfer.h:59-61 names; the model
 *    carries it so the future ACES pass flips the working space, not
 *    the plan shape. NOTE: today's developEncoded SKIPS this conversion
 *    (treats the RGB as the working space directly) — the plan is the
 *    contract the fix implements, and the migration rule (existing
 *    projects keep the current look) is the integrator's gate.
 *  - Non-Mi-Log: NO DecodeTransfer step at all (Linear), or the named
 *    inverse (Srgb/Rec709OETF/Hlg/Pq) — never a silent Mi-Log inverse.
 *  - The display side is derived from the working space (today: Rec.709
 *    OETF only, no gamut step — linear Rec.709 -> Rec.709 display).
 */
fun planFor(source: SourceProfile, working: WorkingSpace = WorkingSpace.LinearRec709): ColorPlan {
    // ---- decode side ----
    val decode = mutableListOf<PlanStep>()

    // YUV sources always expand + matrix first (the encoded path's
    // current order: YCbCr -> RGB -> transfer inverse,
    // Transfer.cpp:142-150).
    decode.add(PlanStep.YcbcrToRgb(source.matrix, source.range, source.bitDepth))

    // The transfer inverse: present only when the source is not already
    // linear — the Mi-Log-unconditional bug's fix shape is the ABSENCE
    // of this step for non-log sources.
    if (source.transfer != SourceTransfer.Linear) {
        decode.add(PlanStep.DecodeTransfer(source.transfer))
    }

    // The gamut conversion to the working space: absent when the source
    // primaries already are the working primaries (identity).
    val workingPrimaries = when (working) {
        WorkingSpace.LinearRec709 -> SourcePrimaries.BT709
    }
    if (source.primaries != workingPrimaries) {
        decode.add(PlanStep.GamutToWorking(source.primaries, workingPrimaries))
    }

    // ---- display side (derived from the working space; the grade runs
    // between the two lists by definition) ----
    val display = mutableListOf<PlanStep>()
    // Today's display is the device's Rec.709-class panel: no gamut
    // conversion from linear Rec.709, only the OETF the bridge tail /
    // shader uOetf applies. A future wide-gamut display changes this
    // derivation, not the plan type.
    display.add(
        PlanStep.WorkingToDisplay(
            displayPrimaries = workingPrimaries,
            displayTransfer = SourceTransfer.Rec709OETF,
        ),
    )

    return ColorPlan(
        source = source,
        workingSpace = working,
        decodeSteps = decode,
        displaySteps = display,
    )
}

/**
 * Reference vectors — pure math mirroring core's implementations
 * EXACTLY, so the JVM tests pin the CPU behavior the integrator
 * implements against. Every constant cites its core source line.
 *
 * These are TEST/INTEGRATION REFERENCES, not a second implementation:
 * the develop stays in core; these numbers exist so a JVM test can
 * assert what core computes without a device.
 */
object SourceProfileVectors {

    // ---- Mi-Log (core/src/Transfer.cpp:15-30, constants verbatim from
    // third_party/xiaomi-milog/Mi-Log_to_ACES_AP0.dctl mlog_to_lin) ----
    const val MILOG_R0 = -0.09023729f
    const val MILOG_RT = 0.01974185f
    const val MILOG_A = 0.09271529f
    const val MILOG_B = 0.01384578f
    const val MILOG_G = 0.67291850f
    const val MILOG_C = 18.10531998f

    /** c*(R_t - R_0)^2 — the toe/log branch cut (Transfer.cpp:21). */
    val MILOG_PT: Float = MILOG_C * (MILOG_RT - MILOG_R0) * (MILOG_RT - MILOG_R0)

    /**
     * Mi-Log encoded -> scene linear, mirroring Transfer.cpp:24-30
     * exactly (the same branches, the same constants, the same order):
     * x < 0 -> R_0; x >= P_t -> 2^((x-g)/a) - b; else sqrt(x/c) + R_0.
     */
    fun miLogToLinear(x: Float): Float = when {
        x < 0.0f -> MILOG_R0
        x >= MILOG_PT -> Math.pow(2.0, ((x - MILOG_G) / MILOG_A).toDouble()).toFloat() - MILOG_B
        else -> Math.sqrt((x / MILOG_C).toDouble()).toFloat() + MILOG_R0
    }

    // ---- Rec.709 OETF (core/src/Transfer.cpp:32-35) and its inverse
    // (core/src/Color.cpp:430-436, k709InvLinCut = 0.081) ----

    /** Scene linear -> Rec.709 display code, unclamped (Transfer.cpp:32-35). */
    fun linearToRec709(lin: Float): Float =
        if (lin < 0.018f) 4.5f * lin
        else (1.099f * Math.pow(lin.toDouble(), 0.45) - 0.099f).toFloat()

    /** The encoded cut of the inverse: 4.5 * 0.018 = 0.081 (Color.cpp:103-106). */
    const val REC709_INV_LIN_CUT = 0.081f

    /**
     * Rec.709 display code -> scene linear, mirroring Color.cpp:430-436:
     * V <= 0.081 -> V/4.5, else spow((V+0.099)/1.099, 1/0.45). The power
     * is SIGN-PRESERVING (Color.cpp:83-90 spow) so negative codes invert
     * instead of producing NaN.
     */
    fun rec709OetfInverse(v: Float): Float =
        if (v <= REC709_INV_LIN_CUT) v / 4.5f
        else spow((v + 0.099f) / 1.099f, 1.0f / 0.45f)

    /** Sign-preserving power (Color.cpp:83-90, colour-science's spow). */
    private fun spow(x: Float, e: Float): Float =
        if (x < 0.0f) -Math.pow(-x.toDouble(), e.toDouble()).toFloat()
        else Math.pow(x.toDouble(), e.toDouble()).toFloat()

    // ---- YCbCr -> RGB (core/src/Transfer.cpp:39-58, limited range) ----
    //
    // The limited-range expansion is what core implements today:
    // yc = (y-16)/219, cb = (u-128)/224, cr = (v-128)/224 (8-bit). The
    // full-range analog (JFIF) divides by 255 with no offset; the 10-bit
    // forms scale the offsets (64/876/896 limited, 1023 full).

    /**
     * One YCbCr sample -> RGB floats, mirroring Transfer.cpp:39-58's
     * coefficients for [matrix] = BT601/BT709 and the range expansion
     * for [range] = Limited/Full at [bitDepth] bits. Limited 8-bit is
     * core's exact current math; the other cells are the pinned
     * analogs the integrator implements when a container declares them.
     */
    fun ycbcrToRgb(
        matrix: SourceMatrix,
        range: SourceRange,
        bitDepth: Int,
        y: Float,
        cb: Float,
        cr: Float,
    ): FloatArray {
        val yc: Float
        val cbN: Float
        val crN: Float
        when (bitDepth) {
            8 -> if (range == SourceRange.Limited) {
                yc = (y - 16.0f) / 219.0f
                cbN = (cb - 128.0f) / 224.0f
                crN = (cr - 128.0f) / 224.0f
            } else {
                yc = y / 255.0f
                cbN = (cb - 128.0f) / 255.0f
                crN = (cr - 128.0f) / 255.0f
            }
            10 -> if (range == SourceRange.Limited) {
                yc = (y - 64.0f) / 876.0f
                cbN = (cb - 512.0f) / 896.0f
                crN = (cr - 512.0f) / 896.0f
            } else {
                yc = y / 1023.0f
                cbN = (cb - 512.0f) / 1023.0f
                crN = (cr - 512.0f) / 1023.0f
            }
            else -> throw IllegalArgumentException(
                "no pinned YCbCr expansion for bit depth $bitDepth (8 and 10 are the container's set)",
            )
        }
        return when (matrix) {
            // Transfer.cpp:39-46 (JFIF-style 601 coefficients).
            SourceMatrix.BT601 -> floatArrayOf(
                yc + 1.402f * crN,
                yc - 0.344136f * cbN - 0.714136f * crN,
                yc + 1.772f * cbN,
            )
            // Transfer.cpp:51-58 (709 coefficients).
            SourceMatrix.BT709 -> floatArrayOf(
                yc + 1.5748f * crN,
                yc - 0.187324f * cbN - 0.468124f * crN,
                yc + 1.8556f * cbN,
            )
            // E0: BT.2020 NCL (Kr .2627, Kb .0593) - Transfer.cpp kCoeff2020.
            SourceMatrix.BT2020 -> floatArrayOf(
                yc + 1.4746f * crN,
                yc - 0.164553f * cbN - 0.571353f * crN,
                yc + 1.8814f * cbN,
            )
        }
    }

    // ---- Gamut conversion: Rec.2020 -> Rec.709 (core/src/Color.cpp) ----
    //
    // The pinned derivation, mirroring core exactly:
    //  - kP2020 primaries {{0.708, 0.292}, {0.170, 0.797}, {0.131, 0.046}}
    //    (Color.cpp:214-216, the BT.2020 dataset);
    //  - D65 from chromaticity (0.3127, 0.3290) (Color.cpp:239-245);
    //  - npm(): columns are unit-luminance XYZ of the primaries, column
    //    scales solve M_raw * c = white (Color.cpp:259-279);
    //  - kM709ToXyz verbatim (Color.cpp:185-189, the sRGB dataset
    //    literal — 4 decimals, so D65 maps to 0.9505 not the derived
    //    0.9504559; that IS core's behavior and these vectors mirror it);
    //  - gamutToGamut: M = inv(M_to_toXYZ) * CAT * M_from_toXYZ
    //    (Color.cpp:549-566); the CAT is Bradford (Color.cpp:534-547)
    //    with D65 -> D65, which is identity to float precision.

    /** Rec.2020 RGB -> XYZ, npm-derived (Color.cpp:259-279, 281-295). */
    val REC2020_TO_XYZ: FloatArray = floatArrayOf(
        0.63695804830129132f, 0.14461690358620835f, 0.16888097516417208f,
        0.26270021201126698f, 0.67799807151887093f, 0.059301716469861952f,
        0.0f, 0.028072693049087435f, 1.0609850577107909f,
    )

    /**
     * Rec.2020 -> Rec.709, the pinned 3x3 (row-major) derived exactly as
     * core's gamutToGamut(Rec2020, Rec709) computes it. This is the
     * deferred conversion Transfer.h:59-61 names: the matrix the
     * Mi-Log path will apply once the gamut conversion lands.
     */
    val REC2020_TO_REC709: FloatArray = floatArrayOf(
        1.6603176191041611f, -0.58757266606617797f, -0.072916573137667723f,
        -0.12440670211719057f, 1.1328007408693026f, -0.0083489374502383612f,
        -0.018111363657381526f, -0.1005965310967451f, 1.1187664817637202f,
    )

    /** Rec.709 -> Rec.2020 (the inverse direction, for a future wide-gamut working space). */
    val REC709_TO_REC2020: FloatArray = floatArrayOf(
        0.62744137205797801f, 0.32929745952190914f, 0.043351458394494544f,
        0.069027617147078085f, 0.91958066688702855f, 0.011361422575400829f,
        0.016364235071680729f, 0.088017162471727015f, 0.89556497272598312f,
    )
}
