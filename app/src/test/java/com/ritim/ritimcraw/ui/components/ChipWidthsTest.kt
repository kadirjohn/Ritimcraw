package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ChipWidths tests (owner finding 2026-11-12, the repeat of E5): the pure
 * width arithmetic behind the Library filter chips' measured single-line
 * width pin. material3 1.3.0 Chip.kt lays a chip out as
 * [outer 8dp][leading icon][label Row, 8dp horizontal padding][outer 8dp],
 * so the chip must be exactly that wide — the label's measured width plus
 * every padding it sits inside — or some parent constraint could squeeze
 * the label into wrapping (the stacked "Sıkış/tırıl/mış" the owner saw
 * twice on device).
 */
class ChipWidthsTest {

    // material3 1.3.0 private tokens mirrored in FilterChipsRow.kt; at
    // density 1.0 they are 8px each in these tests.
    private val outer = 8
    private val labelRowPad = 8
    private val icon = 18 // FilterChipDefaults.IconSize at density 1.0

    @Test
    fun unselected_chip_is_label_plus_both_outer_and_label_paddings() {
        // 8 + 0 + 8 + text + 8 + 8 — no icon while unselected.
        assertEquals(
            32 + 100,
            ChipWidths.chipWidthPx(
                labelWidthPx = 100,
                leadingIconWidthPx = ChipWidths.leadingIconWidthPx(
                    selected = false,
                    iconWidthPx = icon,
                ),
                outerPaddingPx = outer,
                labelRowPaddingPx = labelRowPad,
            ),
        )
    }

    @Test
    fun selected_chip_adds_exactly_one_icon_width() {
        // Selecting swaps in the leading icon: the chip grows by the icon
        // width and NOTHING else (the label Row's 8dp start padding is
        // already counted — it is the icon-label gap).
        val unselected = ChipWidths.chipWidthPx(
            labelWidthPx = 100,
            leadingIconWidthPx = ChipWidths.leadingIconWidthPx(false, icon),
            outerPaddingPx = outer,
            labelRowPaddingPx = labelRowPad,
        )
        val selectedChip = ChipWidths.chipWidthPx(
            labelWidthPx = 100,
            leadingIconWidthPx = ChipWidths.leadingIconWidthPx(true, icon),
            outerPaddingPx = outer,
            labelRowPaddingPx = labelRowPad,
        )
        assertEquals(icon, selectedChip - unselected)
    }

    @Test
    fun leading_icon_width_is_zero_when_unselected() {
        // The label must not reserve space for a check icon it is not
        // showing — an unselected chip is exactly its label's width.
        assertEquals(0, ChipWidths.leadingIconWidthPx(selected = false, iconWidthPx = icon))
    }

    @Test
    fun leading_icon_width_is_the_icon_width_when_selected() {
        assertEquals(icon, ChipWidths.leadingIconWidthPx(selected = true, iconWidthPx = icon))
    }

    @Test
    fun chip_width_tracks_the_measured_label_exactly() {
        // The pin's core promise: whatever the label measures, the chip is
        // that plus the fixed paddings — a longer label widens the chip 1:1
        // (the row scrolls), it can never squeeze the label.
        val short = ChipWidths.chipWidthPx(50, 0, outer, labelRowPad)
        val long = ChipWidths.chipWidthPx(500, 0, outer, labelRowPad)
        assertEquals(450, long - short)
    }

    @Test
    fun zero_width_label_still_covers_all_paddings() {
        // Degenerate label (empty string): the arithmetic stays exact —
        // 32dp of paddings, never negative.
        assertEquals(2 * outer + 2 * labelRowPad, ChipWidths.chipWidthPx(0, 0, outer, labelRowPad))
    }
}
