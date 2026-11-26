package com.ritim.ritimcraw.data

/**
 * D1 (docs/DCG_PLAN.md, report P1): the per-clip reversible "RAW Levels"
 * override — the Kotlin mirror of rawedit::RawLevelsOverride
 * (core/include/rawedit/Develop.h). One white plus four blacks (the 2x2
 * cell-slot order r, g, g, b of RawMeta::black / FrameInfo::dynamicBlack)
 * plus an optional per-channel clip-mask threshold set (0..1 normalized,
 * the semantics of rawedit::RecoveryClipLevels::threshold).
 *
 * THE PERSISTENCE SENTINELS ARE THE CONTRACT: a negative value means
 * "not set" — the slot resolves the way the pipeline always resolved
 * (frame dynamic levels > container metadata). [DEFAULT] (all sentinels)
 * is the neutral value and is OMITTED from the wire record
 * (ClipGradeV2.develop.rawLevels, the omit-when-default rule — every
 * file this build writes for an untouched clip stays byte-identical),
 * so old projects load with metadata semantics and their renders are
 * bit-identical (the D1 default = metadata requirement).
 *
 * Values are RAW code units (the uint16 domain). [validate] refuses a
 * half-set record (out of range, or a white at or below a set black)
 * with an honest message — the same rules the core applies — so an
 * invalid override never reaches the develop math.
 */
data class RawLevels(
    /** The overridden white level, RAW code units; negative = not set. */
    val white: Float = -1f,
    /** The four per-slot blacks (r, g, g, b), RAW code units; negative = not set. */
    val blackR: Float = -1f,
    val blackGr: Float = -1f,
    val blackGb: Float = -1f,
    val blackB: Float = -1f,
    /** The clip-mask thresholds per channel (R, G, B), 0..1 normalized; negative = not set. */
    val clipR: Float = -1f,
    val clipG: Float = -1f,
    val clipB: Float = -1f,
) {
    /** The four blacks in cell-slot order (r, g, g, b) — the core's array. */
    fun blackArray(): FloatArray = floatArrayOf(blackR, blackGr, blackGb, blackB)

    /** The three clip thresholds in channel order (R, G, B) — the core's array. */
    fun clipArray(): FloatArray = floatArrayOf(clipR, clipG, clipB)

    /** True when every slot is a sentinel — metadata semantics, nothing overridden. */
    fun isDefault(): Boolean =
        white < 0f && blackR < 0f && blackGr < 0f && blackGb < 0f && blackB < 0f &&
            clipR < 0f && clipG < 0f && clipB < 0f

    /**
     * "metadata'ya dön" (back to metadata): the distinct revert action —
     * NOT a "set the values to the metadata" write, but the ERASURE of
     * the override. The result is [DEFAULT], which the persistence layer
     * omits from the record, so the clip resolves from its frame's
     * dynamic levels / the container metadata exactly as before the
     * override ever existed.
     */
    fun reverted(): RawLevels = DEFAULT

    /**
     * Honest validation, never a silent clamp: a set white must be in
     * [0, 65535], a set black in [0, 65535], a set clip threshold in
     * [0, 1], and a set white must be STRICTLY above every set black.
     * Returns null when valid, the refusal reason when not — the caller
     * surfaces it (the RawHistogram fromJni convention: a wrong record
     * is reported, never guessed around).
     */
    fun validate(): String? {
        if (white >= 0f && (white > 65535f)) return "white $white out of [0, 65535]"
        val blacks = listOf("R" to blackR, "Gr" to blackGr, "Gb" to blackGb, "B" to blackB)
        for ((name, v) in blacks) {
            if (v >= 0f && v > 65535f) return "black $name $v out of [0, 65535]"
            if (white >= 0f && v >= 0f && white <= v)
                return "white $white not above black $name $v"
        }
        for ((name, v) in listOf("R" to clipR, "G" to clipG, "B" to clipB)) {
            if (v >= 0f && (v < 0f || v > 1f)) return "clip $name $v out of [0, 1]"
        }
        return null
    }

    companion object {
        /** The neutral value: every slot a sentinel, metadata semantics. */
        val DEFAULT = RawLevels()
    }
}

/**
 * The D1 stage-1 level resolution — the Kotlin mirror of
 * rawedit::resolveRawLevels, ONE shared rule on this side of the seam:
 * override (when the slot carries one) > the frame's dynamic levels >
 * the container metadata. Used by every Kotlin consumer that must agree
 * with the develop path (the "Ölç" candidate display, the editor's
 * RAW-domain labels, future preview bindings) — never a private copy.
 *
 * The inputs are the same numbers the JNI bridge resolves natively
 * (frameInfo's dynamic levels with -1 sentinels, the container's
 * black[4]/white), so [resolveRawLevels] and the core's rule give the
 * SAME answer for the SAME inputs.
 */
fun resolveRawLevels(
    levels: RawLevels,
    dynamicBlack: IntArray,
    dynamicWhite: Int,
    containerBlack: IntArray,
    containerWhite: Int,
): Pair<Float, FloatArray> {
    require(dynamicBlack.size == 4) { "dynamicBlack must hold 4 slots" }
    require(containerBlack.size == 4) { "containerBlack must hold 4 slots" }
    // The all-or-nothing frame rule, keyed on slot 0 (the core's rule).
    val useFrameBlack = dynamicBlack[0] >= 0
    val white = if (dynamicWhite > 0) dynamicWhite.toFloat() else containerWhite.toFloat()
    val resolvedWhite = if (levels.white >= 0f) levels.white else white
    val black = FloatArray(4) { slot ->
        val resolved = if (useFrameBlack) dynamicBlack[slot].toFloat()
        else containerBlack[slot].toFloat()
        val overridden = levels.blackArray()[slot]
        if (overridden >= 0f) overridden else resolved
    }
    return resolvedWhite to black
}

/**
 * The D1 "Ölç" (measure) candidate generator's verdict — the Kotlin
 * mirror of rawedit::RawLevelsCandidate. The MEASUREMENT itself runs in
 * the core (recoveryClipLevels' per-frame plateau scan over the raw CFA
 * — a JNI surface the UI will call once per Measure tap); this value is
 * what crosses back, and the pure verdict logic (which numbers may be
 * shown, what "no candidate" means) is pinned here on the JVM.
 */
data class RawLevelsCandidate(
    /** Candidate = a measured white exists; NoCandidate = nothing fabricated. */
    val isCandidate: Boolean = false,
    /** The measured white, RAW code units; negative when no candidate. */
    val white: Float = -1f,
    /** The certified blacks (what the frame resolves to), RAW code units. */
    val black: FloatArray = floatArrayOf(-1f, -1f, -1f, -1f),
    /** The measured clip thresholds per channel (R, G, B), normalized; negative = none. */
    val clip: FloatArray = floatArrayOf(-1f, -1f, -1f),
    /** The measured numbers behind the verdict (per channel R, G, B). */
    val maxNorm: FloatArray = FloatArray(3),
    val plateau: LongArray = LongArray(3),
    val topCount: LongArray = LongArray(3),
) {
    /**
     * The pure confidence verdict the UI shows: a candidate is
     * MEASURED (a sharp saturated green plateau produced numbers);
     * anything else is INSUFFICIENT — the honest state the report
     * demands ("yetersiz veri" — insufficient data keeps the metadata
     * view, never a fabricated one).
     */
    enum class Confidence { MEASURED, INSUFFICIENT }

    fun confidence(): Confidence =
        if (isCandidate) Confidence.MEASURED else Confidence.INSUFFICIENT

    /**
     * The candidate as an override the user can apply with ONE decision:
     * the measured white, the certified blacks and the measured clip
     * thresholds — or null when there is no candidate (applying
     * "nothing" is the refusal, the revert action is the honest path).
     */
    fun toOverrideOrNull(): RawLevels? =
        if (!isCandidate) null else RawLevels(
            white = white,
            blackR = black[0],
            blackGr = black[1],
            blackGb = black[2],
            blackB = black[3],
            clipR = if (clip[0] >= 0f) clip[0] else -1f,
            clipG = if (clip[1] >= 0f) clip[1] else -1f,
            clipB = if (clip[2] >= 0f) clip[2] else -1f,
        )
}
