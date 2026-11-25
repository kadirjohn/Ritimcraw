package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeListOps

/**
 * F1-8a: the colour node strip (docs/F1_8_COLOR_UI_DESIGN.md section 21), adapted from Klipp's NodeRow/NodeChip
 * to the v2 schema. STATELESS: it shows `nodes`, reports taps and menu choices, and never edits the list (the
 * caller applies [NodeListOps] and hands the result to the grade store, so undo/redo and the preview wire keep
 * their one path). A disabled node is dimmed; the selected chip changes colour (no bold); "+" is replaced by
 * a short reason text at [NodeListOps.MAX_NODES]. Every target is at least 48 dp.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NodeStrip(
    nodes: List<GradeNodeV2>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onToggleEnabled: (Int) -> Unit,
    onReset: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        nodes.forEachIndexed { i, n ->
            NodeChip(
                number = i + 1,
                node = n,
                selected = i == selected,
                canDuplicate = NodeListOps.canAdd(nodes),
                onClick = { onSelect(i) },
                onToggleEnabled = { onToggleEnabled(i) },
                onReset = { onReset(i) },
                onDuplicate = { onDuplicate(i) },
                onDelete = { onDelete(i) },
            )
        }
        if (NodeListOps.canAdd(nodes)) {
            val desc = stringResource(R.string.node_strip_add)
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
                    .combinedClickable(role = Role.Button, onClick = onAdd)
                    .testTag("addNode"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_node_add), desc, tint = MaterialTheme.colorScheme.onSurface)
            }
        } else {
            Text(
                stringResource(R.string.node_strip_add_limit, NodeListOps.MAX_NODES),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp).testTag("addNodeLimit"),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeChip(
    number: Int,
    node: GradeNodeV2,
    selected: Boolean,
    canDuplicate: Boolean,
    onClick: () -> Unit,
    onToggleEnabled: () -> Unit,
    onReset: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Box {
        Row(
            Modifier.alpha(if (node.enabled) 1f else 0.45f)
                .height(48.dp).widthIn(min = 48.dp)
                .clip(RoundedCornerShape(if (selected) 22.dp else 14.dp))
                .background(if (selected) scheme.primaryContainer else scheme.surfaceVariant)
                .combinedClickable(role = Role.Button, onLongClick = { menu = true }, onClick = onClick)
                .padding(horizontal = 14.dp)
                .testTag("node:$number"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.node_strip_node, number),
                color = if (selected) scheme.onPrimaryContainer else scheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (node.enabled) R.string.node_menu_disable else R.string.node_menu_enable)) },
                onClick = { menu = false; onToggleEnabled() },
            )
            DropdownMenuItem(text = { Text(stringResource(R.string.node_menu_reset)) }, onClick = { menu = false; onReset() })
            if (canDuplicate) {
                DropdownMenuItem(text = { Text(stringResource(R.string.node_menu_duplicate)) }, onClick = { menu = false; onDuplicate() })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.node_menu_delete)) }, onClick = { menu = false; onDelete() })
        }
    }
}
