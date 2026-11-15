package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AppSegmentedControl (owner spec 2026-11-14, segmented_control; v2 polish
 * C2, 2026-11-15): the settings rows' single-select control (Low/Balanced/
 * High, CPU/Auto/GPU, and the device report's All/Decoders/Encoders).
 *
 * v2 C2 replaces the v1 custom pill track with Material's own
 * [SingleChoiceSegmentedButtonRow] + [SegmentedButton] — the spec's stated
 * FALLBACK path: the project's Material3 1.3.0 (BOM 2024.09.00) predates
 * the Expressive ToggleButton + ButtonGroupDefaults, which arrive in
 * 1.4.x, and a dependency bump is forbidden. The stock component draws
 * each segment's shape itself (itemShape: full corners on the ends, none
 * in the middle) and clips its own press indication to that shape, so no
 * hand-made rectangle can appear behind the rounded selected segment (the
 * P3 artifact).
 *
 * The check-mark icon is removed the spec's own way — icon = {} — so each
 * option is label-only. The row's stock overlapping-border look is kept
 * (SegmentedButton's default border is the outline_policy's allowed
 * outlined-button case, drawn by the Material component itself); the
 * selected option's label turns SemiBold, the unselected stay Medium.
 * Role.RadioButton semantics come from the stock component (its Surface
 * applies role + selected), and the row is the stock selectableGroup.
 *
 * The component is presentation only: the selection callback receives the
 * index; the caller maps it to its enum (options keep their existing
 * values and order). heightIn(min = 40.dp) keeps the spec's minimum
 * height on each weighted, equal-width option.
 */
@Composable
fun AppSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = options.size,
                ),
                selected = selected,
                onClick = { onSelect(index) },
                modifier = Modifier
                    .heightIn(min = 40.dp),
                icon = {},
                label = {
                    Text(
                        text = label,
                        style = TextStyle(
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontWeight = if (selected) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Medium
                            },
                        ),
                        maxLines = 1,
                    )
                },
            )
        }
    }
}
