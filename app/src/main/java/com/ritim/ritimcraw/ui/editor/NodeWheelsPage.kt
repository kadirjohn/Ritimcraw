package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import java.util.Locale

/** A segmented choice: equal-width labelled cells, 48 dp tall, the selected one tinted (no bold). */
@Composable
internal fun SegmentedChoice(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .semantics { selected = on }
                    .clickable(role = Role.Tab) { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun wheelLabel(id: NodeWheelId): String = stringResource(
    when (id) {
        NodeWheelId.LIFT -> R.string.node_wheel_lift
        NodeWheelId.GAMMA -> R.string.node_wheel_gamma
        NodeWheelId.GAIN -> R.string.node_wheel_gain
        NodeWheelId.OFFSET -> R.string.node_wheel_offset
        NodeWheelId.LOG_SHADOW -> R.string.node_wheel_log_shadow
        NodeWheelId.LOG_MID -> R.string.node_wheel_log_mid
        NodeWheelId.LOG_HI -> R.string.node_wheel_log_high
    },
)

/**
 * F1-8b: the selected node's Wheels page. A Primary | Log switch, one chip per wheel, one pad (the rawedit
 * wheelRgb field, y up), a master slider and a reset button. STATELESS over [node]: every change goes out as a
 * new node through [onNodeChange], which NodesBody turns into a node-list edit (the existing undo path).
 * Reset behaviour: pad double-tap centres the puck; master label/thumb double-tap zeroes the master (single
 * reset, no reset-all: the wheels are not a slider group); the reset button clears puck and master.
 */
@Composable
internal fun NodeWheelsPage(
    node: GradeNodeV2,
    wheel: NodeWheelId,
    onWheelSelect: (NodeWheelId) -> Unit,
    onNodeChange: (GradeNodeV2) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    // The pad's gesture lambdas outlive a recomposition, so they read the LATEST node and edit callback.
    val latestNode by rememberUpdatedState(node)
    val latestChange by rememberUpdatedState(onNodeChange)
    val w = NodeWheelOps.get(node, wheel)
    val groups = listOf(stringResource(R.string.node_wheels_primary), stringResource(R.string.node_wheels_log))
    val ids = if (wheel.primary) NodeWheelId.PRIMARY else NodeWheelId.LOG
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedChoice(groups, if (wheel.primary) 0 else 1, {
                onWheelSelect(NodeWheelOps.selectionForGroup(wheel, it == 0))
            })
            SegmentedChoice(ids.map { wheelLabel(it) }, ids.indexOf(wheel), { onWheelSelect(ids[it]) })
        }
        key(wheel) {
            ColorWheelPad(
                x = w.x,
                y = NodeWheelOps.wheelToPadY(w.y),
                onDragChange = { px, py ->
                    latestChange(NodeWheelOps.setPuck(latestNode, wheel, px, NodeWheelOps.padToWheelY(py)))
                },
                onDragActiveChange = onDragActiveChange,
                onDoubleTapReset = { latestChange(NodeWheelOps.resetPuck(latestNode, wheel)) },
                contentDescription = stringResource(R.string.node_wheel_pad_desc, wheelLabel(wheel)),
                discColorAt = NodeWheelOps::padDiscColor,
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                NodeWheelOps.puckReadout(w),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { latestChange(NodeWheelOps.resetWheel(latestNode, wheel)) },
                enabled = !w.isNeutral(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_restart_alt),
                    stringResource(R.string.node_wheel_reset),
                )
            }
        }
        Column(Modifier.padding(horizontal = 16.dp)) {
            GradeSlider(
                label = stringResource(R.string.node_wheel_master),
                value = w.master * 100f, valueRange = -100f..100f, step = 1f,
                valueLabel = NodeWheelOps.masterReadout(w.master),
                onChange = { latestChange(NodeWheelOps.setMaster(latestNode, wheel, it / 100f)) },
                onDoubleTapReset = { latestChange(NodeWheelOps.resetMaster(latestNode, wheel)) },
                onDragActiveChange = onDragActiveChange,
            )
        }
    }
}
