package com.ritim.ritimcraw.ui.editor

/**
 * Pure layout math for the editor (design review 2026-11-10 "Onerilen
 * ilk yerlesim", superseded for the preview region by owner request
 * 2026-11-11d §11.7: the preview now flexes with the draggable panel
 * instead of carrying a height cap — see PanelSheet for the panel's
 * anchor math; the histogram overlay's height cap went with the overlay,
 * which is now the scope strip's content). What remains here is the
 * compact slider's track math.
 */
object EditorLayout {

    /**
     * Fraction (0..1) of [value] within [range], saturated at the
     * endpoints. Used by the compact slider's bipolar fill-from-zero
     * track: the active fill runs from the ZERO position to the thumb,
     * so a negative value fills left-of-zero and a positive value
     * right-of-zero.
     */
    fun fractionOf(value: Float, range: ClosedFloatingPointRange<Float>): Float {
        val min = range.start
        val max = range.endInclusive
        if (max <= min) return 0f
        val saturated = value.coerceIn(min, max)
        return (saturated - min) / (max - min)
    }

    /**
     * The active track segment for a bipolar slider: fill runs from the
     * zero position to the value position. Returns start <= endInclusive
     * fractions; when the range has no zero inside it (unipolar sliders
     * like Temperature), the fill is the plain min-to-value segment so
     * every slider keeps a visible active fill.
     */
    fun bipolarFill(
        value: Float,
        range: ClosedFloatingPointRange<Float>,
    ): ClosedFloatingPointRange<Float> {
        val valueFraction = fractionOf(value, range)
        val min = range.start
        val max = range.endInclusive
        val zeroFraction = if (max > min && min <= 0f && max >= 0f) {
            (0f - min) / (max - min)
        } else {
            0f
        }
        return if (valueFraction < zeroFraction) {
            valueFraction..zeroFraction
        } else {
            zeroFraction..valueFraction
        }
    }
}
