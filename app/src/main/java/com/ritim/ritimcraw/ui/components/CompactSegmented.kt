package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.runtime.remember
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
    CompactSegmentedRow(modifier, height) {
        options.forEachIndexed { index, label ->
            Segment(selected = index == selectedIndex, onClick = { onSelect(index) }, label = label)
        }
    }
}

/** Labels longer than this render one type step smaller. */
private const val LONG_LABEL_CHARS = 12

/** The scope [CompactSegmentedRow] gives its content: one [Segment] per option. */
interface CompactSegmentedScope : RowScope {
    @Composable
    fun Segment(selected: Boolean, onClick: () -> Unit, label: String)
}

private class CompactSegmentedScopeImpl(row: RowScope) : CompactSegmentedScope, RowScope by row {
    @Composable
    override fun Segment(selected: Boolean, onClick: () -> Unit, label: String) {
        val cs = MaterialTheme.colorScheme
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .selectable(
                    selected = selected,
                    role = Role.RadioButton,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(vertical = 7.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cs.secondaryContainer),
                )
            }
            Text(
                text = label,
                // Long labels ("Lock First Frame") step down one size
                // instead of wrapping into a two-line segment.
                style = if (label.length > LONG_LABEL_CHARS) MaterialTheme.typography.labelMedium
                else MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = if (selected) cs.onSurface else cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * The slot form of [CompactSegmented] — a drop-in for the stock
 * SingleChoiceSegmentedButtonRow + SegmentedButton pattern, so call sites
 * keep their own selected/onClick logic and only swap the visuals.
 */
@Composable
fun CompactSegmentedRow(
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    content: @Composable CompactSegmentedScope.() -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
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
        val scope = remember(this) { CompactSegmentedScopeImpl(this) }
        scope.content()
    }
}
