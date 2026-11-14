package com.ritim.ritimcraw.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AppSegmentedControl (owner spec 2026-11-14, segmented_control):
 * replaces the Material SegmentedButton + check-mark pattern with the
 * spec's custom look — a pill track (RoundedCornerShape(14dp),
 * surfaceContainerHighest) with 4dp padding, holding weight(1f) 40dp
 * segments (RoundedCornerShape(10dp), 14sp labels, 4dp gaps).
 *
 * Unselected: transparent container, onSurfaceVariant Medium label.
 * Selected: surfaceBright container (the role exists in this project's
 * Material3 1.3.0 — verified from the sources), onSurface SemiBold
 * label, 1dp shadow elevation plus a 1dp outlineVariant border. NO
 * check mark icon. The container color animates (150ms).
 *
 * Semantics: Role.RadioButton per segment inside a selectableGroup,
 * selected state exposed (selectable's selected parameter).
 *
 * The component is presentation only: the selection callback receives
 * the index; the caller maps it to its enum (options keep their
 * existing values and order).
 */
@Composable
fun AppSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            // 150ms container color animation on selection change.
            val container by animateColorAsState(
                targetValue = if (selected) {
                    MaterialTheme.colorScheme.surfaceBright
                } else {
                    Color.Transparent
                },
                animationSpec = tween(150),
                label = "segmentContainer",
            )
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp)
                    .selectable(
                        selected = selected,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    ),
                shape = RoundedCornerShape(10.dp),
                color = container,
                // Spec: 1dp outlineVariant border + 1dp shadow
                // elevation on the selected segment only.
                border = if (selected) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                } else {
                    null
                },
                shadowElevation = if (selected) 1.dp else 0.dp,
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (selected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
