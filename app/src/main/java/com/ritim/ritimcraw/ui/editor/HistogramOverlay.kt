package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R

/**
 * Toggleable luma histogram over the preview (owner feature list W5):
 * a small overlay drawing the current frame's 256-bin Rec.709 luma
 * histogram ([FrameHistogram.Result]) plus a "Clipped" badge when
 * either end clips beyond the documented 0.1% threshold. Pure display —
 * the screen owns the toggle state and the per-frame recompute;
 * nothing here touches the render pipeline.
 *
 * Design (DESIGN.md): stock M3 on the editor's neutral scheme, color
 * ROLES only, never hex — the bars read onSurfaceVariant over a
 * translucent surfaceContainerLowest scrim (the frame stays visible
 * through the overlay); the badge carries the error role, the one
 * place state color carries meaning. Corner treatment is the theme's
 * shape scale (small), the same treatment as the refresh chip. Sizes:
 * a 64dp graph (inside the 64-96dp band asked for), width capped at
 * 160dp so the overlay never dominates the frame. The screen aligns
 * the overlay at the preview's BottomStart corner with an 8dp inset,
 * mirroring the refresh chip's BottomEnd; the badge stacks BELOW the
 * graph so it never covers the bars it reports on.
 */
@Composable
fun HistogramOverlay(
    result: FrameHistogram.Result,
    modifier: Modifier = Modifier,
) {
    // Resolved OUTSIDE the Canvas draw lambda (draw lambdas are not
    // composables): roles, not hex (DESIGN.md).
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.widthIn(max = 160.dp),
        ) {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                drawLumaBars(result, barColor)
            }
        }
        if (result.isClipped()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    stringResource(R.string.editor_histogram_clipped),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * One bar per luma bin, heights normalized to the tallest bin; bins
 * with zero counts draw nothing (the scrim shows through). An
 * all-zero result (no frame) draws nothing at all.
 */
private fun DrawScope.drawLumaBars(
    result: FrameHistogram.Result,
    barColor: Color,
) {
    val bins = result.bins
    val max = bins.max()
    if (max <= 0) return
    val w = size.width
    val h = size.height
    val barWidth = w / FrameHistogram.BINS
    // Iterate bins, not x positions: the bar's x comes from the bin
    // index directly (no float division round-trip that could round
    // a bin into its neighbor), and the bars tile the width edge to
    // edge — bin k owns exactly [k*w/256, (k+1)*w/256).
    for (bin in 0 until FrameHistogram.BINS) {
        val count = bins[bin]
        if (count <= 0) continue
        val barHeight = (count.toFloat() / max) * h
        drawRect(
            color = barColor,
            topLeft = Offset(bin * barWidth, h - barHeight),
            size = Size(barWidth, barHeight),
        )
    }
}

/**
 * The histogram toggle: a stock M3 IconButton (48dp target, DESIGN.md
 * Inputs) with the vendored bar_chart glyph, placed by the screen at
 * the preview's TopStart corner — the mirror of the fullscreen TopEnd
 * toggle, so the preview's corner icon buttons read as one group. The
 * button's content description carries the toggle state for TalkBack
 * (the action it will perform, like the fullscreen toggle's "Exit
 * fullscreen"); the glyph itself stays decorative.
 */
@Composable
fun HistogramToggleButton(
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        if (visible) R.string.editor_histogram_hide else R.string.editor_histogram
    )
    IconButton(
        onClick = onToggle,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_histogram),
            contentDescription = null,
        )
    }
}
