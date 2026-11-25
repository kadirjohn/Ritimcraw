package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeListOps
import java.util.Locale

/**
 * F1-8a: the Color tab's Nodes body. The node strip, then the selected node's Adjust page (saturation, contrast,
 * hue, temperature, tint, mix) and its free-text name (at most [NodeListOps.NAME_MAX] characters). STATELESS over
 * `nodes`: every edit goes out as a new list through [onNodesChange] (the screen persists it and re-renders).
 * Wheels, curves and the node LUT are later slices; the strip and the page are ready for them. Wheels joined
 * in F1-8b, the Curves page in F1-8c; the node LUT (F1-8d per the doc's slice table) follows.
 */
@Composable
internal fun NodesBody(
    nodes: List<GradeNodeV2>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onNodesChange: (List<GradeNodeV2>) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    val sel = selected.coerceIn(0, (nodes.size - 1).coerceAtLeast(0))
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        NodeStrip(
            nodes = nodes,
            selected = sel,
            onSelect = onSelect,
            onAdd = {
                val next = NodeListOps.add(nodes)
                onNodesChange(next)
                onSelect(next.size - 1)
            },
            onToggleEnabled = { onNodesChange(NodeListOps.toggleEnabled(nodes, it)) },
            onReset = { onNodesChange(NodeListOps.reset(nodes, it)) },
            onDuplicate = {
                val next = NodeListOps.duplicate(nodes, it)
                onNodesChange(next)
                onSelect(it + 1)
            },
            onDelete = {
                val next = NodeListOps.delete(nodes, it)
                onNodesChange(next)
                onSelect(NodeListOps.selectionAfterDelete(it, next.size).coerceAtLeast(0))
            },
        )
        if (nodes.isEmpty()) {
            Text(
                stringResource(R.string.nodes_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            return@Column
        }
        val node = nodes[sel]
        fun edit(n: GradeNodeV2) = onNodesChange(nodes.toMutableList().also { it[sel] = n })
        // Page tab and wheel/curve selection are view state (saved across rotation), not part of the grade.
        var tab by rememberSaveable { mutableStateOf(NodeTab.WHEELS) }
        var wheel by rememberSaveable { mutableStateOf(NodeWheelId.LIFT) }
        var curve by rememberSaveable { mutableStateOf(NodeCurveId.Y) }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SegmentedChoice(
                labels = listOf(
                    stringResource(R.string.node_tab_wheels),
                    stringResource(R.string.node_tab_curves),
                    stringResource(R.string.node_tab_adjust),
                ),
                selectedIndex = tab.ordinal,
                onSelect = { tab = NodeTab.entries[it] },
            )
        }
        if (tab == NodeTab.WHEELS) {
            NodeWheelsPage(
                node = node,
                wheel = wheel,
                onWheelSelect = { wheel = it },
                onNodeChange = { edit(it) },
                onDragActiveChange = onDragActiveChange,
            )
            return@Column
        }
        if (tab == NodeTab.CURVES) {
            NodeCurvesPage(
                node = node,
                curve = curve,
                onCurveSelect = { curve = it },
                onNodeChange = { edit(it) },
                onDragActiveChange = onDragActiveChange,
            )
            return@Column
        }
        // Thumb double-tap resets this node's six sliders (node scope, not the clip grade).
        CompositionLocalProvider(
            LocalSliderResetActions provides SliderResetActions(
                resetAll = {
                    edit(node.copy(sat = 1f, contrast = 1f, hue = 0f, temp = 0f, tint = 0f, mix = 1f))
                },
            ),
        ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = node.name ?: "",
                onValueChange = { raw ->
                    // Cut to the limit while typing; blank stays null (the default "Node N").
                    val cut = if (raw.codePointCount(0, raw.length) > NodeListOps.NAME_MAX) {
                        raw.substring(0, raw.offsetByCodePoints(0, NodeListOps.NAME_MAX))
                    } else raw
                    onNodesChange(NodeListOps.rename(nodes, sel, cut))
                },
                singleLine = true,
                label = { Text(stringResource(R.string.node_name_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            // Every control is shown ZERO-CENTRED like the legacy sliders (neutral in the middle, double tap resets).
            GradeSlider(
                label = stringResource(R.string.editor_slider_saturation),
                value = (node.sat - 1f) * 100f, valueRange = -100f..100f, step = 1f,
                valueLabel = pct(node.sat - 1f),
                onChange = { edit(node.copy(sat = (1f + it / 100f).coerceIn(0f, 2f))) },
                onDoubleTapReset = { edit(node.copy(sat = 1f)) },
                onDragActiveChange = onDragActiveChange,
            )
            GradeSlider(
                label = stringResource(R.string.node_adjust_contrast),
                value = (node.contrast - 1f) * 100f, valueRange = -100f..100f, step = 1f,
                valueLabel = pct(node.contrast - 1f),
                onChange = { edit(node.copy(contrast = (1f + it / 100f).coerceIn(0f, 2f))) },
                onDoubleTapReset = { edit(node.copy(contrast = 1f)) },
                onDragActiveChange = onDragActiveChange,
            )
            GradeSlider(
                label = stringResource(R.string.node_adjust_hue),
                value = node.hue, valueRange = -180f..180f, step = 1f,
                valueLabel = String.format(Locale.ROOT, "%+d\u00B0", node.hue.toInt()),
                onChange = { edit(node.copy(hue = it)) },
                onDoubleTapReset = { edit(node.copy(hue = 0f)) },
                onDragActiveChange = onDragActiveChange,
            )
            GradeSlider(
                label = stringResource(R.string.editor_slider_temperature),
                value = node.temp * 100f, valueRange = -100f..100f, step = 1f,
                valueLabel = pct(node.temp),
                onChange = { edit(node.copy(temp = (it / 100f).coerceIn(-1f, 1f))) },
                onDoubleTapReset = { edit(node.copy(temp = 0f)) },
                onDragActiveChange = onDragActiveChange,
                trackGradient = CompactSliderDefaults.TEMPERATURE_GRADIENT,
            )
            GradeSlider(
                label = stringResource(R.string.editor_slider_tint),
                value = node.tint * 100f, valueRange = -100f..100f, step = 1f,
                valueLabel = pct(node.tint),
                onChange = { edit(node.copy(tint = (it / 100f).coerceIn(-1f, 1f))) },
                onDoubleTapReset = { edit(node.copy(tint = 0f)) },
                onDragActiveChange = onDragActiveChange,
                trackGradient = CompactSliderDefaults.TINT_GRADIENT,
            )
            GradeSlider(
                label = stringResource(R.string.node_adjust_mix),
                value = node.mix, valueRange = 0f..1f, step = 0.01f,
                valueLabel = String.format(Locale.ROOT, "%d%%", (node.mix * 100f).toInt()),
                onChange = { edit(node.copy(mix = it)) },
                onDoubleTapReset = { edit(node.copy(mix = 1f)) },
                onDragActiveChange = onDragActiveChange,
            )
        }
        }
    }
}

private fun pct(delta: Float): String = String.format(Locale.ROOT, "%+d", Math.round(delta * 100f))
