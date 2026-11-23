package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK

// GENERATED FILE - DO NOT EDIT. Regenerate with:
//   python3 tools/gen-grade-parcel.py
// The schema (the single source of truth) lives in that script; a stale
// mirror is caught by its --check mode and by the JVM suite's
// GradeParcelTest cross-checking the committed C++ header against these
// offsets.

/**
 * The grade parcel: the 29 SCALAR grade fields as ONE flat FloatArray
 * crossing the JNI seam (the per-frame parameter collapse). One source of
 * truth - the schema in tools/gen-grade-parcel.py - generates BOTH this
 * object and the C++ mirror (gles/GradeParcel.h, the gles::DevelopGrade
 * struct + the constexpr slot set + the validating decode + the generated
 * request fold).
 *
 * WIRE RULES
 *   - Slot order is a wire contract: fields APPEND, never reorder, never
 *     reuse an offset. [SLOT_COUNT] is the length the decode accepts.
 *   - Booleans cross as 0.0f/1.0f and decode with != 0.0f, after a strict
 *     0/1 validation (0.37 is a refusal, not a truthy true).
 *   - tonemap crosses as int-as-float; decode casts back.
 *   - Valid NEGATIVE values are preserved verbatim (sharpness/detail to
 *     -100 are legal softening; the decode never clamps).
 *   - NaN and Infinity are refused in any slot.
 *
 * The parcel is built ONCE per grade change (an immutable parameter
 * packet), not re-encoded per frame.
 *
 * WHAT IS DELIBERATELY NOT IN THE PARCEL (the exclusion list):
 *   - the LUT binding: lutPath, lutStrength, lutRole, lutInGamut,
 *     lutInTransfer, lutOutGamut, lutOutTransfer - a per-JOB, session-level
 *     upload, not a per-frame scalar;
 *   - the gyro warp plan input: gyroPath, stabMode, stabStrength,
 *     stabZoomLimit, stabFocalMm, stabTrimMs - per-frame plan data that
 *     feeds the warp resolver, not a grade scalar;
 *   - the warp verdict: warpActive, warpM - per-frame plan output;
 *   - job mechanics: handles, frameIndex, maxDim, ptsUs, rotationDeg;
 *   - GradeK-only fields: version, lutId, lutInterp, the stabilization
 *     group, the crop group, statsMode.
 *
 * REFUSAL CHANNELS (both honest, never a silent misread): the Kotlin
 * decode throws IllegalStateException with a recovery message; the C++
 * decode has no exception channel across the JNI boundary, so the bridge
 * rides its setError contract (error code + stage + message, read back via
 * nativeLastError) and returns the empty value.
 */
object GradeParcel {

    /** The one length the decode accepts; any other length is a refusal. */
    const val SLOT_COUNT = 29

    const val SLOT_CHROMA_DENOISE = 0
    const val SLOT_NOISE_REDUCTION = 1
    const val SLOT_TEMPORAL_NR = 2
    const val SLOT_TEMPORAL_STRENGTH = 3
    const val SLOT_SHARPNESS = 4
    const val SLOT_DETAIL = 5
    const val SLOT_EXPOSURE_EV = 6
    const val SLOT_HIGHLIGHTS = 7
    const val SLOT_BLACK_POINT = 8
    const val SLOT_WHITE_POINT = 9
    const val SLOT_SHADOWS = 10
    const val SLOT_MIDTONES = 11
    const val SLOT_CONTRAST = 12
    const val SLOT_TEMP_K = 13
    const val SLOT_TINT = 14
    const val SLOT_SATURATION = 15
    const val SLOT_AS_SHOT = 16
    const val SLOT_WHEEL_SHADOW_X = 17
    const val SLOT_WHEEL_SHADOW_Y = 18
    const val SLOT_WHEEL_MID_X = 19
    const val SLOT_WHEEL_MID_Y = 20
    const val SLOT_WHEEL_HIGH_X = 21
    const val SLOT_WHEEL_HIGH_Y = 22
    const val SLOT_SPLIT_SHADOW_HUE = 23
    const val SLOT_SPLIT_HIGHLIGHT_HUE = 24
    const val SLOT_SPLIT_BALANCE = 25
    const val SLOT_HIGHLIGHT_RECONSTRUCTION = 26
    const val SLOT_KEEP_VIGNETTING = 27
    const val SLOT_TONEMAP = 28

    /** Slot names, index-aligned, for honest refusal messages. */
    private val SLOT_NAMES = arrayOf(
            "chromaDenoise",
            "noiseReduction",
            "temporalNr",
            "temporalStrength",
            "sharpness",
            "detail",
            "exposureEv",
            "highlights",
            "blackPoint",
            "whitePoint",
            "shadows",
            "midtones",
            "contrast",
            "tempK",
            "tint",
            "saturation",
            "asShot",
            "wheelShadowX",
            "wheelShadowY",
            "wheelMidX",
            "wheelMidY",
            "wheelHighX",
            "wheelHighY",
            "splitShadowHue",
            "splitHighlightHue",
            "splitBalance",
            "highlightReconstruction",
            "keepVignetting",
            "tonemap",
    )

    /** The typed decode target: every scalar field, booleans as Boolean. */
    data class DecodedGrade(
        val chromaDenoise: Float,
        val noiseReduction: Float,
        val temporalNr: Boolean,
        val temporalStrength: Float,
        val sharpness: Float,
        val detail: Float,
        val exposureEv: Float,
        val highlights: Float,
        val blackPoint: Float,
        val whitePoint: Float,
        val shadows: Float,
        val midtones: Float,
        val contrast: Float,
        val tempK: Float,
        val tint: Float,
        val saturation: Float,
        val asShot: Boolean,
        val wheelShadowX: Float,
        val wheelShadowY: Float,
        val wheelMidX: Float,
        val wheelMidY: Float,
        val wheelHighX: Float,
        val wheelHighY: Float,
        val splitShadowHue: Float,
        val splitHighlightHue: Float,
        val splitBalance: Float,
        val highlightReconstruction: Boolean,
        val keepVignetting: Boolean,
        val tonemap: Int,
    )

    /**
     * Encodes the scalar grade fields of [grade] into the flat parcel.
     * The temporal pair rides the parcel but dies at the bridge (consumed
     * by the bridge's temporal merge before the request) - encode the
     * EFFECTIVE value the caller threaded (the preview's tier fold is
     * applied by the caller before encoding, via grade.copy).
     */
    fun encode(grade: GradeK): FloatArray = floatArrayOf(
        grade.chromaDenoise,
        grade.noiseReduction,
        if (grade.temporalNr) 1.0f else 0.0f,
        grade.temporalStrength,
        grade.sharpness,
        grade.detail,
        grade.exposureEv,
        grade.highlights,
        grade.blackPoint,
        grade.whitePoint,
        grade.shadows,
        grade.midtones,
        grade.contrast,
        grade.tempK,
        grade.tint,
        grade.saturation,
        if (grade.asShot) 1.0f else 0.0f,
        grade.wheelShadowX,
        grade.wheelShadowY,
        grade.wheelMidX,
        grade.wheelMidY,
        grade.wheelHighX,
        grade.wheelHighY,
        grade.splitShadowHue,
        grade.splitHighlightHue,
        grade.splitBalance,
        if (grade.highlightReconstruction) 1.0f else 0.0f,
        if (grade.keepVignetting) 1.0f else 0.0f,
        grade.tonemap.toFloat(),
    )

    /**
     * Decodes and validates the parcel. Refuses (IllegalStateException,
     * honest message, never a silent misread) on: a length other than
     * [SLOT_COUNT], a non-finite value in any slot, or a boolean slot
     * outside strict 0.0f/1.0f. Negative values are returned verbatim.
     */
    fun decode(parcel: FloatArray): DecodedGrade {
        if (parcel.size != SLOT_COUNT) {
            refuse(
                "grade parcel: unknown length " + parcel.size + " (expected " + SLOT_COUNT + ") " +
                    "(recovery: re-encode the grade with GradeParcel.encode; a parcel of a different length comes from a different schema version - never a silent misread)",
            )
        }
        for (i in parcel.indices) {
            if (!parcel[i].isFinite()) {
                refuse(
                    "grade parcel: " + SLOT_NAMES[i] + " is not finite " +
                        "(recovery: the parcel must carry finite floats)",
                )
            }
        }
        if (parcel[SLOT_TEMPORAL_NR] != 0.0f && parcel[SLOT_TEMPORAL_NR] != 1.0f) {
            refuse(
                "grade parcel: temporalNr must be 0.0 or 1.0 (got " +
                    parcel[SLOT_TEMPORAL_NR] +
                ") (recovery: booleans cross the parcel as 0.0f/1.0f)",
            )
        }
        if (parcel[SLOT_AS_SHOT] != 0.0f && parcel[SLOT_AS_SHOT] != 1.0f) {
            refuse(
                "grade parcel: asShot must be 0.0 or 1.0 (got " +
                    parcel[SLOT_AS_SHOT] +
                ") (recovery: booleans cross the parcel as 0.0f/1.0f)",
            )
        }
        if (parcel[SLOT_HIGHLIGHT_RECONSTRUCTION] != 0.0f && parcel[SLOT_HIGHLIGHT_RECONSTRUCTION] != 1.0f) {
            refuse(
                "grade parcel: highlightReconstruction must be 0.0 or 1.0 (got " +
                    parcel[SLOT_HIGHLIGHT_RECONSTRUCTION] +
                ") (recovery: booleans cross the parcel as 0.0f/1.0f)",
            )
        }
        if (parcel[SLOT_KEEP_VIGNETTING] != 0.0f && parcel[SLOT_KEEP_VIGNETTING] != 1.0f) {
            refuse(
                "grade parcel: keepVignetting must be 0.0 or 1.0 (got " +
                    parcel[SLOT_KEEP_VIGNETTING] +
                ") (recovery: booleans cross the parcel as 0.0f/1.0f)",
            )
        }
        return DecodedGrade(
            chromaDenoise = parcel[SLOT_CHROMA_DENOISE],
            noiseReduction = parcel[SLOT_NOISE_REDUCTION],
            temporalNr = parcel[SLOT_TEMPORAL_NR] != 0.0f,
            temporalStrength = parcel[SLOT_TEMPORAL_STRENGTH],
            sharpness = parcel[SLOT_SHARPNESS],
            detail = parcel[SLOT_DETAIL],
            exposureEv = parcel[SLOT_EXPOSURE_EV],
            highlights = parcel[SLOT_HIGHLIGHTS],
            blackPoint = parcel[SLOT_BLACK_POINT],
            whitePoint = parcel[SLOT_WHITE_POINT],
            shadows = parcel[SLOT_SHADOWS],
            midtones = parcel[SLOT_MIDTONES],
            contrast = parcel[SLOT_CONTRAST],
            tempK = parcel[SLOT_TEMP_K],
            tint = parcel[SLOT_TINT],
            saturation = parcel[SLOT_SATURATION],
            asShot = parcel[SLOT_AS_SHOT] != 0.0f,
            wheelShadowX = parcel[SLOT_WHEEL_SHADOW_X],
            wheelShadowY = parcel[SLOT_WHEEL_SHADOW_Y],
            wheelMidX = parcel[SLOT_WHEEL_MID_X],
            wheelMidY = parcel[SLOT_WHEEL_MID_Y],
            wheelHighX = parcel[SLOT_WHEEL_HIGH_X],
            wheelHighY = parcel[SLOT_WHEEL_HIGH_Y],
            splitShadowHue = parcel[SLOT_SPLIT_SHADOW_HUE],
            splitHighlightHue = parcel[SLOT_SPLIT_HIGHLIGHT_HUE],
            splitBalance = parcel[SLOT_SPLIT_BALANCE],
            highlightReconstruction = parcel[SLOT_HIGHLIGHT_RECONSTRUCTION] != 0.0f,
            keepVignetting = parcel[SLOT_KEEP_VIGNETTING] != 0.0f,
            tonemap = parcel[SLOT_TONEMAP].toInt(),
        )
    }

    private fun refuse(message: String): Nothing = throw IllegalStateException(message)
}
