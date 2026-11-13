package com.ritim.ritimcraw.data

/**
 * Kotlin mirror of rawedit's color-management model (owner request
 * 2026-11-13, core/include/rawedit/Color.h): the gamuts, transfer
 * functions, and LUT roles a managed .cube interpretation declares.
 *
 * The ordinals are LOAD-BEARING: every enum's [value] is the C++ enum's
 * ordinal, and the JNI seam passes those ints straight through — the
 * bridge constructs the rawedit::LutInterpretation from them without a
 * lookup table. The pinning tests (LutInterpTest) assert each value
 * against the documented C++ order, so a reordering on either side
 * fails a JVM test instead of silently mis-rendering.
 */
enum class LutGamut(val value: Int) {
    /** Rec.709 primaries — the SAME identity as sRGB primaries. */
    Rec709(0),
    Rec2020(1),
    DaVinciWideGamut(2),
}

enum class LutTransfer(val value: Int) {
    Linear(0),
    Srgb(1),
    /** The app's EXISTING linearToRec709 curve — never renamed, never altered. */
    Rec709OETF(2),
    /** Pure gamma 2.4 — a separate named curve, NOT the Rec.709 OETF. */
    Gamma24(3),
    DaVinciIntermediate(4),
}

enum class LutRole(val value: Int) {
    /**
     * The COL-07 stage-24 path, byte-identical: linear Rec.709 in, table,
     * linear Rec.709 out, before the single Rec.709 OETF. Old records
     * default here.
     */
    LegacyLinear709(0),
    /** Applied AFTER the render (ACES/AgX) on rendered linear Rec.709. */
    DisplayLook(1),
    /** Applied BEFORE the render on scene-linear; ACES/AgX then applies ONCE. */
    SceneLook(2),
    /**
     * A scene-to-display conversion LUT (e.g. DWG/Intermediate -> Rec.709):
     * entered BEFORE the render, the LUT branch is ALREADY rendered output —
     * ACES/AgX never applies on top of it.
     */
    SceneToDisplay(3),
}

/** One color encoding: a gamut plus a transfer function. */
data class LutEncodingK(
    val gamut: LutGamut = LutGamut.Rec709,
    val transfer: LutTransfer = LutTransfer.Linear,
)

/**
 * A LUT interpretation: input encoding, output encoding, and the role
 * that decides where the pipeline applies it. IMMUTABLE once stored —
 * an edit creates a new binding revision (LutStore), never a mutation.
 *
 * A null LutInterpK in a grade means the legacy selection (GradeK.lutId
 * alone, the COL-07 stage-24 path); a non-null one carries the full
 * managed interpretation BY VALUE, so an export snapshot (a GradeK
 * captured at enqueue) is self-sufficient: a retry develops with exactly
 * the interpretation the user saw even if the library record is later
 * removed.
 */
data class LutInterpK(
    val input: LutEncodingK = LutEncodingK(),
    val output: LutEncodingK = LutEncodingK(),
    val role: LutRole = LutRole.LegacyLinear709,
) {
    companion object {
        /** The all-defaults interpretation old records migrate to. */
        fun legacy(): LutInterpK = LutInterpK()

        internal const val SCHEMA_VERSION = 1

        /**
         * Parses one interpretation object. EVERY field falls back to the
         * legacy default (Rec.709/Linear in and out, LegacyLinear709), so:
         *   - a record written before the managed model ({id, name} only)
         *     migrates to LegacyLinear709 — the mission's migration rule;
         *   - a grade written before lutInterp carries no object at all
         *     (the caller sees null) and one written with a partial object
         *     still develops with the legacy path for anything unspecified;
         *   - an unknown ordinal falls back to the legacy default rather
         *     than crashing a render — a future C++ enum extension can
         *     never wedge an older UI into a wrong-but-silent role.
         */
        internal fun parse(v: Any?): LutInterpK? {
            if (v == null) return null
            val m = v as? Map<*, *> ?: return null
            val inM = m["in"] as? Map<*, *>
            val outM = m["out"] as? Map<*, *>
            return LutInterpK(
                input = LutEncodingK(
                    gamut = gamutOf(inM?.get("g")),
                    transfer = transferOf(inM?.get("t")),
                ),
                output = LutEncodingK(
                    gamut = gamutOf(outM?.get("g")),
                    transfer = transferOf(outM?.get("t")),
                ),
                role = roleOf(m["role"]),
            )
        }

        private fun gamutOf(v: Any?): LutGamut =
            enumValues<LutGamut>().firstOrNull { it.value == (v as? Double)?.toInt() }
                ?: LutGamut.Rec709

        private fun transferOf(v: Any?): LutTransfer =
            enumValues<LutTransfer>().firstOrNull { it.value == (v as? Double)?.toInt() }
                ?: LutTransfer.Linear

        private fun roleOf(v: Any?): LutRole =
            enumValues<LutRole>().firstOrNull { it.value == (v as? Double)?.toInt() }
                ?: LutRole.LegacyLinear709
    }

    /**
     * A stable signature of the interpretation — the ordinal tuple the
     * JNI binding cache keys on (blob path + this tuple, mirrored verbatim
     * in the bridge's resolveBinding): same interpretation = same
     * signature, ANY field change = a different signature = a recompile.
     */
    fun signature(): String =
        "${role.value}|${input.gamut.value}.${input.transfer.value}" +
            ">${output.gamut.value}.${output.transfer.value}"

    /**
     * Renders the interpretation object. The version renders
     * omit-when-[SCHEMA_VERSION] so files this build writes for a legacy
     * record stay byte-identical to the pre-managed format only when the
     * whole object is omitted (the caller's omit-when-null rule); a
     * non-null interpretation always renders the object, version 1
     * included, so a future curve migration can tell which schema wrote
     * it.
     */
    internal fun render(): String {
        val sb = StringBuilder()
        sb.append('{')
            .append("\"v\":").append(SCHEMA_VERSION)
            .append(",\"role\":").append(role.value)
            .append(",\"in\":{\"g\":").append(input.gamut.value)
            .append(",\"t\":").append(input.transfer.value).append('}')
            .append(",\"out\":{\"g\":").append(output.gamut.value)
            .append(",\"t\":").append(output.transfer.value).append('}')
            .append('}')
        return sb.toString()
    }
}
