package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Slider step semantics and double-tap reset for the Light, Color and Detail
 * tabs (DESIGN.md Inputs: "M3 Slider with label and tabular value; double tap
 * resets"). Pure functions so the mapping is JVM-testable without Compose.
 */
object SliderSteps {

    /** Exposure steps 0.1 EV. */
    const val EXPOSURE_STEP = 0.1f

    /** Temperature steps 50 K. */
    const val TEMP_STEP_K = 50f

    /** Highlights / Tint and the six tone controls are integer steps. */
    const val UNIT_STEP = 1f

    /**
     * Strength the Temporal NR switch seeds on first enable (DN-05..DN-09):
     * the native stage is a no-op at 0, so a toggle that visibly changes
     * nothing would read as broken. Midpoint — conservative but effective
     * (the owner's directive for this stage).
     */
    const val TEMPORAL_STRENGTH_DEFAULT = 50f

    /**
     * Strength the Stabilization switch seeds on first enable (gyro Phase 5):
     * the core's smoothing time constant is tau = 1 s · (1/60)^(1-strength)
     * over the 0..1 dial (Stabilization.h, audit fix 2026-11-12), so the
     * midpoint 0.5 gives tau ≈ 129 ms — visibly corrected on the fixtures
     * (the old map's 0.5 was ≈ 31.6 µs, near-raw: a toggle that visibly
     * changed nothing would read as broken, same reasoning as temporal NR).
     * 50% is the documented default.
     */
    const val STABILIZATION_STRENGTH_DEFAULT = 50f

    /** Zoom limit the Stabilization switch seeds: 1.1x auto-crop ceiling. */
    const val STABILIZATION_ZOOM_DEFAULT = 1.1f

    /** Zoom limit slider step: 1.0..1.5 at 0.05 = 10 intervals, 11 values. */
    const val STABILIZATION_ZOOM_STEP = 0.05f

    /** Sync trim slider step: ±500 ms at 10 ms = 100 intervals. 10 ms is below the anchored ±25 ms bound, so the grid never hides a useful trim. */
    const val STABILIZATION_TRIM_STEP_MS = 10f

    /** Focal override step in mm: 35mm-equivalent focals are half-mm labeled at finest. */
    const val STABILIZATION_FOCAL_STEP_MM = 0.5f

    /** Snaps a single axis to its step grid (slider change events). */
    fun snapExposure(value: Float): Float = quantize(value, EXPOSURE_STEP)
    fun snapHighlights(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapTemperature(value: Float): Float = quantize(value, TEMP_STEP_K)
    fun snapTint(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapSaturation(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapBlackPoint(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapWhitePoint(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapShadows(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapMidtones(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapContrast(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapSharpness(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapDetail(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapLutStrength(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapNoiseReduction(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapChromaDenoise(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapTemporalStrength(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapStabilizationStrength(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapStabilizationZoomLimit(value: Float): Float = quantize(value, STABILIZATION_ZOOM_STEP)
    fun snapStabilizationTrimMs(value: Float): Float = quantize(value, STABILIZATION_TRIM_STEP_MS)
    fun snapStabilizationFocalMm(value: Float): Float = quantize(value, STABILIZATION_FOCAL_STEP_MM)

    /**
     * Snaps a whole grade to the step grid: Exposure to 0.1 EV, Temperature
     * to 50 K, every other axis to whole numbers. Clamping to the slider
     * range is the Compose Slider's job; this only quantizes.
     */
    fun snap(grade: GradeK): GradeK {
        return grade.copy(
            exposureEv = snapExposure(grade.exposureEv),
            highlights = snapHighlights(grade.highlights),
            tempK = snapTemperature(grade.tempK),
            tint = snapTint(grade.tint),
            saturation = snapSaturation(grade.saturation),
            blackPoint = snapBlackPoint(grade.blackPoint),
            whitePoint = snapWhitePoint(grade.whitePoint),
            shadows = snapShadows(grade.shadows),
            midtones = snapMidtones(grade.midtones),
            contrast = snapContrast(grade.contrast),
            sharpness = snapSharpness(grade.sharpness),
            detail = snapDetail(grade.detail),
            lutStrength = snapLutStrength(grade.lutStrength),
            noiseReduction = snapNoiseReduction(grade.noiseReduction),
            chromaDenoise = snapChromaDenoise(grade.chromaDenoise),
            temporalStrength = snapTemporalStrength(grade.temporalStrength),
            stabilizationStrength = snapStabilizationStrength(grade.stabilizationStrength),
            stabilizationZoomLimit = snapStabilizationZoomLimit(grade.stabilizationZoomLimit),
            stabilizationTrimMs = snapStabilizationTrimMs(grade.stabilizationTrimMs),
            stabilizationFocalMm = snapStabilizationFocalMm(grade.stabilizationFocalMm),
        )
    }

    /**
     * Double-tap-to-reset target: the GradeK default for the tapped slider
     * only — every other axis keeps its current value (DESIGN.md "double tap
     * resets" the one control, not the whole grade).
     */
    enum class Axis {
        EXPOSURE, HIGHLIGHTS, TEMPERATURE, TINT,
        SATURATION, BLACK_POINT, WHITE_POINT, SHADOWS, MIDTONES, CONTRAST,
        SHARPNESS, DETAIL, LUT_STRENGTH,
        NOISE_REDUCTION, CHROMA_DENOISE,
        TEMPORAL_STRENGTH,
        STABILIZATION_STRENGTH, STABILIZATION_ZOOM_LIMIT,
        STABILIZATION_TRIM_MS, STABILIZATION_FOCAL_MM,
    }

    fun resetToDefault(axis: Axis, current: GradeK): GradeK = when (axis) {
        Axis.EXPOSURE -> current.copy(exposureEv = GradeK().exposureEv)
        Axis.HIGHLIGHTS -> current.copy(highlights = GradeK().highlights)
        Axis.TEMPERATURE -> current.copy(tempK = GradeK().tempK)
        Axis.TINT -> current.copy(tint = GradeK().tint)
        Axis.SATURATION -> current.copy(saturation = GradeK().saturation)
        Axis.BLACK_POINT -> current.copy(blackPoint = GradeK().blackPoint)
        Axis.WHITE_POINT -> current.copy(whitePoint = GradeK().whitePoint)
        Axis.SHADOWS -> current.copy(shadows = GradeK().shadows)
        Axis.MIDTONES -> current.copy(midtones = GradeK().midtones)
        Axis.CONTRAST -> current.copy(contrast = GradeK().contrast)
        Axis.SHARPNESS -> current.copy(sharpness = GradeK().sharpness)
        Axis.DETAIL -> current.copy(detail = GradeK().detail)
        // COL-07: Strength resets to 100 (full LUT), the GradeK default.
        Axis.LUT_STRENGTH -> current.copy(lutStrength = GradeK().lutStrength)
        // DN-01..08: both denoise controls reset to 0 (neutral, the
        // stages skipped — the old look), the GradeK default.
        Axis.NOISE_REDUCTION -> current.copy(noiseReduction = GradeK().noiseReduction)
        Axis.CHROMA_DENOISE -> current.copy(chromaDenoise = GradeK().chromaDenoise)
        Axis.TEMPORAL_STRENGTH -> current.copy(temporalStrength = GradeK().temporalStrength)
        // Gyro Phase 5: each stabilization slider resets independently to
        // its GradeK default (70 / 1.1 / 0 / 0); the toggle and mode are not
        // slider axes and are untouched by a reset.
        Axis.STABILIZATION_STRENGTH ->
            current.copy(stabilizationStrength = GradeK().stabilizationStrength)
        Axis.STABILIZATION_ZOOM_LIMIT ->
            current.copy(stabilizationZoomLimit = GradeK().stabilizationZoomLimit)
        Axis.STABILIZATION_TRIM_MS ->
            current.copy(stabilizationTrimMs = GradeK().stabilizationTrimMs)
        Axis.STABILIZATION_FOCAL_MM ->
            current.copy(stabilizationFocalMm = GradeK().stabilizationFocalMm)
    }

    /**
     * Compose Slider `steps` argument: the number of snap points strictly
     * between the range endpoints (a Slider with `steps = n` offers n + 2
     * discrete values). Returns 0 for a continuous slider.
     */
    fun composeSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int {
        if (step <= 0f) return 0
        val intervals = ((range.endInclusive - range.start) / step.toDouble()).roundToInt()
        return (intervals - 1).coerceAtLeast(0)
    }

    /**
     * Quantizes [value] to multiples of [step]. The multiply happens in
     * double so grid values land on the float closest to the decimal
     * (3 x 0.1 -> 0.3f, not 0.30000004f).
     */
    internal fun quantize(value: Float, step: Float): Float {
        if (step <= 0f) return value
        val units = (value / step).roundToLong()
        return (units.toDouble() * step.toDouble()).toFloat()
    }
}
