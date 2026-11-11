package com.ritim.ritimcraw.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Pure layout math for the editor's fixed regions (design review
 * 2026-11-10 "Onerilen ilk yerlesim"): the preview gets a bounded,
 * deterministic height so the frame never shifts when the tab content
 * below changes size, and only the settings panel scrolls.
 *
 * The preview cap is a DRAFT starting point from the review ("ekranin
 * %35-40'i taslakta bir baslangic noktasi olabilir"): portrait clips
 * and short screens decide the final value (device pass).
 */
object EditorLayout {

    /**
     * Preview region height cap as a fraction of the available screen
     * height, per the review's 35-40% draft band. 0.38 is the midpoint.
     */
    const val PREVIEW_HEIGHT_FRACTION = 0.38f

    /**
     * The bounded preview region height in px: the preview area is capped
     * at [PREVIEW_HEIGHT_FRACTION] of the available height, but never
     * exceeds the full height (short screens with 1.3x text keep the
     * panel scrollable, never squeezed to zero).
     */
    fun previewHeightPx(availableHeightPx: Int): Int {
        require(availableHeightPx >= 0) { "availableHeightPx must be >= 0" }
        val capped = (availableHeightPx * PREVIEW_HEIGHT_FRACTION).toInt()
        return capped.coerceIn(0, availableHeightPx)
    }

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

    /**
     * The bounded preview region's max height as a Compose [Dp]: the
     * screen-height cap for the preview Box in ClipEditorScreen
     * (heightIn(max = ...)). Kept here so the 0.38 draft constant and its
     * one consumer stay in one place; the px twin [previewHeightPx] is the
     * JVM-testable half of the same rule.
     */
    @Composable
    fun previewMaxHeight(): Dp {
        val screenHeightDp = LocalConfiguration.current.screenHeightDp
        return (screenHeightDp * PREVIEW_HEIGHT_FRACTION).dp
    }
}
