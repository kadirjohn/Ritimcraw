package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.LutInfo
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import java.util.Locale

/**
 * F1-8d: the node's LUT page. The library (the same imported cubes the Look tab lists) as one radio row per cube plus
 * None, an Import button, and the LUT mix slider while a cube is selected. STATELESS: every edit leaves as a new node
 * through [onNodeChange]; the screen persists it like any other node edit. The chain cap (4 nodes with a LUT) is
 * stated in a hint and the unselected rows are inert once it is reached (a node that already has a LUT can always
 * change or clear its choice).
 */
@Composable
internal fun NodeLutPage(
    nodes: List<GradeNodeV2>,
    index: Int,
    lutEntries: List<LutInfo>,
    lutImportError: String?,
    onImportLut: () -> Unit,
    onNodeChange: (GradeNodeV2) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
) {
    val node = nodes[index]
    val assignable = NodeLutOps.canAssign(nodes, index)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (lutEntries.isEmpty()) {
            Text(
                stringResource(R.string.node_lut_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            NodeLutRow(
                label = stringResource(R.string.node_lut_none),
                summary = null,
                selected = node.lutId == null,
                enabled = true,
                onClick = { onNodeChange(NodeLutOps.select(node, null)) },
            )
            lutEntries.forEach { lut ->
                val selected = node.lutId == lut.id
                NodeLutRow(
                    label = lut.name,
                    summary = LutInterpPresets.summaryOf(lut.interp),
                    selected = selected,
                    enabled = selected || assignable,
                    onClick = { onNodeChange(NodeLutOps.select(node, lut.id)) },
                )
            }
            if (!assignable) {
                Text(
                    stringResource(R.string.node_lut_limit, NodeLutOps.MAX_LUT_NODES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        FilledTonalButton(onClick = onImportLut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.editor_lut_import))
        }
        if (lutImportError != null) {
            Text(
                lutImportError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (node.lutId != null) {
            GradeSlider(
                label = stringResource(R.string.node_lut_mix),
                value = node.lutMix, valueRange = 0f..1f, step = 0.01f,
                valueLabel = String.format(Locale.ROOT, "%d%%", (node.lutMix * 100f).toInt()),
                onChange = { onNodeChange(NodeLutOps.withMix(node, it)) },
                onDoubleTapReset = { onNodeChange(NodeLutOps.withMix(node, 1f)) },
                onDragActiveChange = onDragActiveChange,
            )
            TextButton(onClick = { onNodeChange(NodeLutOps.reset(node)) }) {
                Text(stringResource(R.string.node_lut_reset))
            }
        }
    }
}

@Composable
private fun NodeLutRow(
    label: String,
    summary: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
