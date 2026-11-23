package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * CompactSegmented (UI modernization 2026-11-23): the editor's dense
 * single-select control — a soft rounded track with a raised pill under
 * the selected option, label-only, no check mark (the stock
 * SegmentedButton spends ~28dp of every segment on the check icon and
 * truncated "Histogram" to "Histogra" on a 308dp phone).
 *
 * TOUCH TARGET vs VISUAL SIZE: the hit area is the full [height] (44dp by
 * default) while the visible track is inset 4dp top and bottom (36dp) and
 * the pill a further 3dp (30dp) — Material's density guidance: the control
 * may look small as long as the touch target stays large.
 *
 * Neutral by construction (DESIGN.md: no hue in editor chrome): track =
 * surfaceContainerHigh, pill = surfaceContainerHighest/secondaryContainer,
 * labels onSurface (selected) / onSurfaceVariant.
 */
@Composable
fun CompactSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    val pill = MaterialTheme.colorScheme.secondaryContainer
    val selectedText = MaterialTheme.colorScheme.onSurface
    val idleText = MaterialTheme.colorScheme.onSurfaceVariant
    val pillShape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .height(height)
            .drawBehind {
                val inset = 4.dp.toPx()
                drawRoundRect(
                    color = track,
                    topLeft = Offset(0f, inset),
                    size = Size(size.width, size.height - 2 * inset),
                    cornerRadius = CornerRadius(11.dp.toPx()),
                )
            }
            .padding(horizontal = 3.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(vertical = 7.dp)
                            .clip(pillShape)
                            .background(pill),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) selectedText else idleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
    }
}
