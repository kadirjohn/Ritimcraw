package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Slider step semantics and double-tap reset for the Light tab (DESIGN.md
 * Inputs: "M3 Slider with label and tabular value; double tap resets").
 * Pure functions so the mapping is JVM-testable without Compose.
 */
object SliderSteps {

    /** Exposure steps 0.1 EV. */
    const val EXPOSURE_STEP = 0.1f

    /** Temperature steps 50 K. */
    const val TEMP_STEP_K = 50f

    /** Highlights / Tint are integer steps. */
    const val UNIT_STEP = 1f

    /** Snaps a single axis to its step grid (slider change events). */
    fun snapExposure(value: Float): Float = quantize(value, EXPOSURE_STEP)
    fun snapHighlights(value: Float): Float = quantize(value, UNIT_STEP)
    fun snapTemperature(value: Float): Float = quantize(value, TEMP_STEP_K)
    fun snapTint(value: Float): Float = quantize(value, UNIT_STEP)

    /**
     * Snaps a whole grade to the step grid: Exposure to 0.1 EV, Temperature
     * to 50 K, Highlights and Tint to whole numbers. Clamping to the slider
     * range is the Compose Slider's job; this only quantizes.
     */
    fun snap(exposureEv: Float, highlights: Float, tempK: Float, tint: Float): GradeK {
        return GradeK(
            exposureEv = snapExposure(exposureEv),
            highlights = snapHighlights(highlights),
            tempK = snapTemperature(tempK),
            tint = snapTint(tint),
        )
    }

    /**
     * Double-tap-to-reset target: the GradeK default for the tapped slider
     * only — every other axis keeps its current value (DESIGN.md "double tap
     * resets" the one control, not the whole grade).
     */
    enum class Axis { EXPOSURE, HIGHLIGHTS, TEMPERATURE, TINT }

    fun resetToDefault(axis: Axis, current: GradeK): GradeK = when (axis) {
        Axis.EXPOSURE -> current.copy(exposureEv = GradeK().exposureEv)
        Axis.HIGHLIGHTS -> current.copy(highlights = GradeK().highlights)
        Axis.TEMPERATURE -> current.copy(tempK = GradeK().tempK)
        Axis.TINT -> current.copy(tint = GradeK().tint)
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
