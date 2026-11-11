package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

// material3 1.3.0 Chip.kt lays a FilterChip out as
// [outer 8dp][leading icon][label Row, 8dp horizontal padding][outer 8dp]
// (its PRIVATE tokens FilterChipPadding / HorizontalElementsPadding).
// Mirrored here because they are private to M3: a material3 upgrade that
// changes them must update these two constants, or every chip pads a few
// dp wrong (see [ChipWidths] — the one place that consumes them).
private val ChipOuterHorizontalPadding = 8.dp
private val ChipLabelRowHorizontalPadding = 8.dp

/**
 * Pure chip-width arithmetic (unit-tested in ChipWidthsTest): the exact
 * width a FilterChip needs so its label fits on ONE line, derived from the
 * material3 1.3.0 ChipContent layout
 * [outer][icon][labelRowPad][label][labelRowPad][outer]. All inputs are
 * measured px — nothing is guessed.
 */
internal object ChipWidths {

    /** The leading check icon takes its width only while selected. */
    fun leadingIconWidthPx(selected: Boolean, iconWidthPx: Int): Int =
        if (selected) iconWidthPx else 0

    /**
     * The chip's full width in px: both outer paddings, the leading icon
     * (0 while unselected), both label-row paddings, and the label's
     * single-line width.
     */
    fun chipWidthPx(
        labelWidthPx: Int,
        leadingIconWidthPx: Int,
        outerPaddingPx: Int,
        labelRowPaddingPx: Int,
    ): Int =
        2 * outerPaddingPx +
            leadingIconWidthPx +
            2 * labelRowPaddingPx +
            labelWidthPx
}

/**
 * A single-line, horizontally scrollable filter chip row (owner request
 * 2026-11-11d §4.2). Each chip is exactly as wide as its label's intrinsic
 * single-line width — measured, never guessed — so no label, including the
 * Turkish "Sıkıştırılmış" (Encoded), can ever wrap into stacked characters;
 * the row scrolls when the chips exceed the screen width instead.
 *
 * WHY A SECOND FIX (owner finding 2026-11-12, after E5): the first guard
 * (973d58f) pinned the label Text's own flags — maxLines = 1, softWrap =
 * false, TextOverflow.Visible. In the compose-foundation 1.7.0 sources
 * that combination is single-line by construction (softWrap = false sends
 * LayoutUtils.finalMaxWidth to Infinity, and StaticLayout with maxLines =
 * 1 cannot wrap), yet the device stacked "Sıkış/tırıl/mış" again on the
 * build that shipped exactly that code. Whatever the device's text stack
 * does, this chip must not TRUST the text layer — so this version
 * enforces the geometry itself, in three steps:
 *
 * 1. MEASURE: the label's single-line width is measured up front with
 *    [rememberTextMeasurer] using the SAME style (labelLarge), font
 *    resolver and density the chip's Text uses inside, so the two
 *    measurements agree exactly. The measured string IS the displayed
 *    string (one hoisted val feeds both).
 * 2. PIN THE LABEL: the label Text gets [Modifier.requiredWidth] of the
 *    measured width. requiredWidth is enforceIncoming = false — the
 *    paragraph receives min = max = its own intrinsic width no matter
 *    what constraints reach it, so there is no spare width to wrap into.
 * 3. PIN THE CHIP: the FilterChip gets requiredWidth of the full chip
 *    width (label + icon + M3's paddings, [ChipWidths]), so no parent
 *    constraint can squeeze the chip narrower than its single-line label.
 *
 * Tradeoffs:
 * - The width math mirrors material3 1.3.0 Chip.kt's private paddings
 *   (the two constants above). A material3 upgrade that changes them must
 *   update those constants; until then chips pad a few dp wrong but stay
 *   single-line (the label pin does not depend on the paddings).
 * - The check icon is sized to FilterChipDefaults.IconSize (18dp, the M3
 *   spec — the official samples size chip icons identically). It
 *   previously rendered at the painter's intrinsic 24dp, so the selected
 *   chip is slightly narrower than before; 18dp also makes the width
 *   arithmetic exact.
 * - requiredWidth means that if a label ever laid out WIDER at draw time
 *   than at pin time (same style + resolver + density make that impossible
 *   here), the glyphs would paint past the chip edge (Visible) rather than
 *   wrap. Wrapping is unconditionally impossible — that is the point.
 *
 * The row scrolls BY DESIGN (DESIGN.md §4.2): with intrinsic-width chips
 * the six labels exceed a portrait screen, so later chips ("Photos") sit
 * off-screen right until the owner scrolls — never hidden or clipped (the
 * Library Column parent does not clip; horizontalScroll owns the
 * overflow).
 *
 * Everything else stays stock M3 (DESIGN.md "stock Material 3"): the
 * secondary-container colors, the check icon on selection, the 32dp
 * height, ripple and semantics are all the FilterChip defaults — only the
 * width is pinned. Selection state and the [onSelect] wiring are the
 * caller's, unchanged.
 */
@Composable
fun FilterChipsRow(
    count: Int,
    selected: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    // A @Composable slot returning the plain label STRING: the row owns the
    // Text (and its measured width pin), the caller owns the wording
    // (stringResource stays at the call site).
    label: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer(cacheSize = 8)
    val density = LocalDensity.current
    val labelStyle = MaterialTheme.typography.labelLarge
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { index ->
            val isSelected = selected(index)
            val text = label(index)
            // Step 1 — the label's intrinsic single-line width. Constraints()
            // defaults to unbounded; softWrap = false + maxLines = 1 make
            // the measurement one full line, whatever the text stack does
            // with the same flags later (cacheSize 8 covers all labels).
            val labelWidthPx = textMeasurer.measure(
                text = text,
                style = labelStyle,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Visible,
            ).size.width
            // Steps 2 + 3 — pin both the label and the whole chip.
            val (labelWidth, chipWidth) = with(density) {
                val iconWidthPx = ChipWidths.leadingIconWidthPx(
                    selected = isSelected,
                    iconWidthPx = FilterChipDefaults.IconSize.roundToPx(),
                )
                val chipPx = ChipWidths.chipWidthPx(
                    labelWidthPx = labelWidthPx,
                    leadingIconWidthPx = iconWidthPx,
                    outerPaddingPx = ChipOuterHorizontalPadding.roundToPx(),
                    labelRowPaddingPx = ChipLabelRowHorizontalPadding.roundToPx(),
                )
                labelWidthPx.toDp() to chipPx.toDp()
            }
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(index) },
                // requiredWidth (not width): enforceIncoming = false, so the
                // chip reports exactly this width whatever constraints any
                // parent layer hands down — the row's scroll gives
                // unbounded width, but the pin does not depend on that.
                modifier = Modifier.requiredWidth(chipWidth),
                label = {
                    // Pinned to the measured single-line width: the
                    // paragraph gets min = max = its own intrinsic width —
                    // no spare width to wrap into, at any layer. The flags
                    // stay as the belt-and-suspenders from the first fix.
                    Text(
                        text,
                        modifier = Modifier.requiredWidth(labelWidth),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                },
                leadingIcon = if (isSelected) {
                    {
                        // 18dp per the M3 spec (FilterChipDefaults.IconSize);
                        // unsized it rendered at the painter's intrinsic 24dp.
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
