package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import java.util.Locale

/**
 * F1-8c: the selected node's Curves page (docs/F1_8_COLOR_UI_DESIGN.md section 3, "Eğriler" — a Compose
 * Canvas editor over Y/R/G/B). STATELESS over [node]: every change goes out as a new node through
 * [onNodeChange], which NodesBody turns into a node-list edit (the existing undo path, merged by
 * NODE_GESTURE like the wheels' drags).
 *
 * The canvas: the plot (full panel width, ~200dp tall), the neutral diagonal, the channel's 256-sample
 * polyline (NodeCurveOps.table256 — the engine's own nodeCurveTable resolution, so the drawn line IS the
 * curve the engine samples), and the control points as circles. Gestures:
 *  - a drag that starts ON a point grabs and moves it (one point stays grabbed for the whole gesture);
 *  - a drag that starts OFF every point ADDS a point where it began and grabs it (the design's
 *    "nokta ekle");
 *  - a double tap ON a point deletes it (endpoint deletes refuse; "nokta sil");
 *  - a double tap OFF every point resets the whole channel to the identity (the pad's own affordance).
 *
 * Channel switch: Y/R/G/B chips over the canvas (SegmentedChoice, the wheels' own row); the drawn colour
 * follows the channel — themed primary for Y (neutral chrome, never a raw hex), the channel's own colour
 * for R/G/B. The reset button (the wheels page's own row shape) clears THIS channel; enabled = not
 * identity. The points readout shows the stored count against the wire's 64 cap.
 *
 * Touch targets: the channel row 48dp cells, the reset IconButton 48dp; the canvas is one large target
 * (the whole plot is draggable). The semantics carry the channel's content description only — full
 * per-point editing accessibility would need custom actions (the pad's own later pass).
 */
@Composable
internal fun NodeCurvesPage(
    node: GradeNodeV2,
    curve: NodeCurveId,
    onCurveSelect: (NodeCurveId) -> Unit,
    onNodeChange: (GradeNodeV2) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    val latestNode by rememberUpdatedState(node)
    val latestChange by rememberUpdatedState(onNodeChange)
    val labels = NodeCurveId.ALL.map { curveLabel(it) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SegmentedChoice(labels, NodeCurveId.ALL.indexOf(curve), { onCurveSelect(NodeCurveId.ALL[it]) })
        }
        key(curve) {
            CurveCanvas(
                node = node,
                curve = curve,
                onNodeChange = onNodeChange,
                onDragActiveChange = onDragActiveChange,
                contentDescription = stringResource(R.string.node_curve_canvas_desc, curveLabel(curve)),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                pointsReadout(NodeCurveOps.points(node, curve).size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { latestChange(NodeCurveOps.resetCurve(latestNode, curve)) },
                enabled = !NodeCurveOps.get(latestNode, curve).isIdentity(),
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_restart_alt),
                    stringResource(R.string.node_curve_reset),
                )
            }
        }
    }
}

@Composable
private fun curveLabel(id: NodeCurveId): String = stringResource(
    when (id) {
        NodeCurveId.Y -> R.string.node_curve_y
        NodeCurveId.R -> R.string.node_curve_r
        NodeCurveId.G -> R.string.node_curve_g
        NodeCurveId.B -> R.string.node_curve_b
    },
)

private val PLOT_HEIGHT = 220.dp

private fun pointsReadout(n: Int): String = String.format(Locale.ROOT, "%d / %d", n, NodeCurveOps.MAX_POINTS)

/**
 * The plot canvas. The drag state: the gesture's captured start ([NodeCurveOps.CurveDragStart], remembered
 * so the draw can ring the grabbed point) — every move of the gesture applies to that START snapshot, so
 * the grabbed index resolves against the curve it was computed from even for the move Compose dispatches
 * in the SAME pointer event as the drag start (before any recomposition could refresh a
 * rememberUpdatedState node; the old index-only wiring dragged the wrong point on an add-drag). Both
 * gesture jobs restart per channel change (pointerInput(curve)), which ALSO ends a grab cleanly: a channel
 * switch mid-drag drops the pointer job and the start resets with it.
 */
@Composable
private fun CurveCanvas(
    node: GradeNodeV2,
    curve: NodeCurveId,
    onNodeChange: (GradeNodeV2) -> Unit,
    onDragActiveChange: (Boolean) -> Unit,
    contentDescription: String,
) {
    val latestNode by rememberUpdatedState(node)
    val latestChange by rememberUpdatedState(onNodeChange)
    val onDragActive by rememberUpdatedState(onDragActiveChange)
    var dragStart by remember(curve) { mutableStateOf<NodeCurveOps.CurveDragStart?>(null) }
    val scheme = MaterialTheme.colorScheme
    // The plot keeps its thumb circles inside the boundary: 10px inset (a 8px radius point at the rim).
    val inset = 10.dp
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                // requiredHeight: deterministic, NOT coerced by a bounded parent (the min-only heightIn
                // collapsed on the device); the gestures read the canvas' OWN size, the draw's size.
                .requiredHeight(PLOT_HEIGHT)
                .semantics { this.contentDescription = contentDescription }
                .pointerInput(curve) {
                    detectTapGestures(
                        onDoubleTap = { pos ->
                            val ins = inset.toPx()
                            val (cx, cy) = NodeCurveOps.pointerToCurve(
                                pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), ins,
                            )
                            val hit = NodeCurveOps.hitTest(latestNode, curve, cx, cy)
                            if (hit >= 0) {
                                latestChange(NodeCurveOps.deletePointAt(latestNode, curve, hit))
                            } else {
                                latestChange(NodeCurveOps.resetCurve(latestNode, curve))
                            }
                        },
                    )
                }
                .pointerInput(curve) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            onDragActive(true)
                            val ins = inset.toPx()
                            val (cx, cy) = NodeCurveOps.pointerToCurve(
                                pos.x, pos.y, size.width.toFloat(), size.height.toFloat(), ins,
                            )
                            val start = NodeCurveOps.beginDrag(latestNode, curve, cx, cy)
                            dragStart = start
                            if (start.addedPoint) {
                                // Publish the add immediately: the new dot is visible before the
                                // first move event arrives (and even if the finger never moves again).
                                latestChange(start.node)
                            }
                        },
                        onDragEnd = { onDragActive(false); dragStart = null },
                        onDragCancel = { onDragActive(false); dragStart = null },
                    ) { change, _ ->
                        change.consume()
                        val start = dragStart ?: return@detectDragGestures
                        if (start.index < 0) return@detectDragGestures
                        val ins = inset.toPx()
                        val (cx, cy) = NodeCurveOps.pointerToCurve(
                            change.position.x, change.position.y,
                            size.width.toFloat(), size.height.toFloat(), ins,
                        )
                        latestChange(NodeCurveOps.dragTo(start, curve, cx, cy))
                    }
                },
        ) {
            val w = size.width.toFloat()
            val h = size.height.toFloat()
            val r = NodeCurveOps.plotRect(w, h, inset.toPx())
            val ins = r.left
            val iw = r.width
            val ih = r.height
            fun px(x: Float) = r.left + x * iw
            fun py(y: Float) = r.top + NodeCurveOps.curveToPadY(y) * ih
            // The plot frame: a hairline border (outlineVariant, the pad rim's role).
            drawRect(
                color = scheme.outlineVariant,
                topLeft = Offset(ins, ins),
                size = Size(iw, ih),
                style = Stroke(width = 1.dp.toPx()),
            )
            // Grid: quarter lines, faint (the same outlineVariant, thinner and translucent).
            for (g in 1..3) {
                val t = g / 4f
                drawLine(
                    scheme.outlineVariant.copy(alpha = 0.4f),
                    start = Offset(px(t), py(0f)),
                    end = Offset(px(t), py(1f)),
                    strokeWidth = 0.5f.dp.toPx(),
                )
                drawLine(
                    scheme.outlineVariant.copy(alpha = 0.4f),
                    start = Offset(px(0f), py(t)),
                    end = Offset(px(1f), py(t)),
                    strokeWidth = 0.5f.dp.toPx(),
                )
            }
            // The neutral diagonal (onSurface at low alpha, the identity reference).
            drawLine(
                scheme.onSurface.copy(alpha = 0.35f),
                start = Offset(px(0f), py(0f)),
                end = Offset(px(1f), py(1f)),
                strokeWidth = 1.dp.toPx(),
            )
            // The curve: 256 samples of the engine's own table, as a polyline.
            val table = NodeCurveOps.table256(NodeCurveOps.points(node, curve))
            val path = Path()
            for (k in table.indices) {
                val x = k / 255f
                val o = Offset(px(x), py(table[k]))
                if (k == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
            }
            drawPath(path, color = curveColor(curve, scheme), style = Stroke(width = 2.dp.toPx()))
            // The control points: 6dp dots, the grabbed one ringed to 8dp.
            NodeCurveOps.points(node, curve).forEachIndexed { i, p ->
                val c = Offset(px(p.first), py(p.second))
                if (i == dragStart?.index) {
                    drawCircle(scheme.onSurface, radius = 8.dp.toPx() / 2f, center = c)
                }
                drawCircle(curveColor(curve, scheme), radius = 6.dp.toPx() / 2f, center = c)
                drawCircle(scheme.surface, radius = 2.dp.toPx() / 2f, center = c)
            }
        }
    }
}

/** Y reads as the themed primary (neutral chrome, never a raw hex); R/G/B are the channel's own colour. */
private fun curveColor(id: NodeCurveId, scheme: ColorScheme): Color = when (id) {
    NodeCurveId.Y -> scheme.primary
    NodeCurveId.R -> Color(0xFFF2A0A0)
    NodeCurveId.G -> Color(0xFFA0D9A0)
    NodeCurveId.B -> Color(0xFFA0AEF2)
}
