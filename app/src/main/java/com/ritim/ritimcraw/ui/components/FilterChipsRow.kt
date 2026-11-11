package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * A single-line, horizontally scrollable filter chip row (owner request
 * 2026-11-11d §4.2). Each chip measures at its content's intrinsic width
 * inside a scrollable Row, so no label — including the Turkish
 * "Sıkıştırılmış" (Encoded) — ever wraps into stacked characters; the row
 * scrolls when the chips exceed the screen width instead.
 *
 * Owner finding 2026-11-11 E5: on the device the "Encoded" chip stacked
 * its characters vertically ("En/co/de/d"). The row's horizontalScroll
 * already hands chips unbounded width (Scroll.kt ScrollingLayoutNode:
 * maxWidth = Constraints.Infinity), but the label Text had NO
 * single-line guard, and M3's ChipContent measures the label with
 * constraints.offset(horizontal = -(leadingIconWidth + trailingIconWidth))
 * — the selected chip's check icon subtracts from the label's width — so
 * a squeezed label fell back to per-character line breaks. The guard is
 * BY CONSTRUCTION now: this component emits the label Text itself with
 * maxLines = 1 + softWrap = false, so every caller's chips stay
 * single-line and measure their full intrinsic width (the row scrolls
 * for the overflow instead of ever stacking a label). Callers pass the
 * plain label STRING per index; no caller can reintroduce a stacked
 * label.
 *
 * The chips are stock M3 FilterChips: the Monet-derived secondary
 * container colors carry the active state, and the selected chip shows
 * the check icon (DESIGN.md Library). Selection is a plain
 * [selected] per chip; the owner's existing filter logic lives in the
 * caller and is untouched.
 */
@Composable
fun FilterChipsRow(
    count: Int,
    selected: (Int) -> Boolean,
    onSelect: (Int) -> Unit,
    // A @Composable slot returning the plain label STRING: the row owns
    // the Text (and its single-line guard), the caller owns the wording
    // (stringResource stays at the call site).
    label: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(count) { index ->
            val isSelected = selected(index)
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(index) },
                label = {
                    // E5: the single-line guard lives HERE, once, for every
                    // chip this row emits. softWrap = false keeps the chip
                    // at the label's full intrinsic width (the row scrolls
                    // for overflow); Visible (not Clip) so a label wider
                    // than any mismeasured constraint still paints whole
                    // rather than cutting mid-glyph.
                    Text(
                        label(index),
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Visible,
                    )
                },
                leadingIcon = if (isSelected) {
                    {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                        )
                    }
                } else {
                    null
                },
            )
        }
    }
}
