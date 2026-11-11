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
import androidx.compose.ui.unit.dp

/**
 * A single-line, horizontally scrollable filter chip row (owner request
 * 2026-11-11d §4.2). Each chip measures at its content's intrinsic width
 * inside a scrollable Row, so no label — including the Turkish
 * "Sıkıştırılmış" (Encoded) — ever wraps into stacked characters; the row
 * scrolls when the chips exceed the screen width instead.
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
    label: @Composable (Int) -> Unit,
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
                label = { label(index) },
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
