package com.ritim.ritimcraw.ui.editor

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import kotlin.math.exp
import kotlin.math.max

/**
 * Toggleable RGB + luma histogram over the preview (owner feature list
 * W5; the RGB extension of the owner's 2026-11-11 histogram spec, with
 * the RapidRAW study's display approach): a dark translucent panel
 * drawing the current frame's four 256-bin histograms
 * ([FrameHistogram.Result]) as filled, smoothed curves with thin crisp
 * outlines, plus shadow/highlight clipping indicators. Pure display —
 * the screen owns the toggle state and the per-frame recompute (on
 * Dispatchers.Default, inside the render pump's isFresh publish guard,
 * so a stale frame's histogram can never overwrite a newer one's);
 * nothing here touches the render pipeline.
 *
 * MODES: compact shows the combined RGB view (the three channel fills
 * + a subtle grey luma outline on top, the owner's Lightroom-style
 * improvement over RapidRAW); tapping the graph expands it with a mode
 * switch — RGB (combined), Luma (the grey curve alone) and Parade
 * (three side-by-side channel graphs, one common scale so relative
 * channel levels read). Mode and expanded state are rememberSaveable
 * INSIDE this composable so the screen's wiring stays one call.
 *
 * CHANNEL COLORS ARE LITERAL, NOT ROLES — the one documented exception
 * to DESIGN.md's roles-never-hex rule: the red/green/blue channel
 * identity IS the information, there is no theme role for "red
 * channel", and the values (RapidRAW's #FF6B6B / #6BCB77 / #4D96FF)
 * are tuned to stay readable on the dark scrim. The luma curve uses
 * the onSurfaceVariant ROLE (neutral grey, like the old bars); the
 * gridlines and dot backing use roles too.
 *
 * SMOOTHING IS DISPLAY-ONLY: the drawn curves run through a Gaussian
 * (sigma 2, 13 taps, edge-replicate) applied to a COPY of the raw bins,
 * then a common-scale normalization. The raw counts and clip counts in
 * [FrameHistogram.Result] are never modified — clipping indicators
 * read the RAW counts, so smoothing can never create or hide a
 * clipping warning (the owner's raw-counts-stay-raw rule; RapidRAW
 * smooths in place, we deliberately do not).
 *
 * COMMON SCALE: all channels drawn together share one denominator —
 * the largest 99th-percentile smoothed count among them (the
 * RapidRAW normalization: the scale ignores the top 1% of bins so one
 * black spike cannot flatten the rest of the curve; values above the
 * denominator clamp at the top like Lightroom's). Per-channel scaling
 * would equalize the curves and lie about relative levels.
 *
 * CLIPPING INDICATORS: two small corner dot rows inside the graph —
 * bottom-left for shadow clipping, bottom-right for highlight
 * clipping, one dot per slot (R/G/B in channel colors, luma in grey)
 * that clips beyond the documented 0.1% threshold. The "Clipped" text
 * badge (the existing string, reused) sits BELOW the panel in both
 * shapes — the old placement, in the bottom band at the Start side
 * where the BottomEnd refresh chip is not.
 *
 * SIZES/PLACEMENT (the screen aligns the overlay at the preview's
 * BottomStart with an 8dp inset): compact is the spec's 160x85dp; the
 * expanded panel's width is capped so the BottomEnd refresh chip keeps
 * its clearance (widest chip label ~175dp + insets -> panel <= screen
 * width - 210dp, floor 160dp, ceiling 280dp) and its height is capped
 * to the bounded preview region (EditorLayout's 0.38 fraction minus
 * the mode row and badge) so portrait, landscape and fullscreen all
 * keep the panel inside the preview and off the transport row.
 * animateContentSize softens the compact/expanded transition.
 *
 * PERFORMANCE: the per-frame work is one pass in FrameHistogram (off
 * the UI thread, commit 1); here the smoothed/normalized display
 * arrays are remembered per result (recomputed once per published
 * frame, not per draw), and the draw itself is Canvas + Path with no
 * chart library. Update frequency is bounded by the render pump's
 * publish rate; drawing never blocks on computation.
 */
@Composable
fun HistogramOverlay(
    result: FrameHistogram.Result,
    modifier: Modifier = Modifier,
) {
    // View state lives INSIDE the overlay so the screen's wiring stays
    // one call (surgical footprint): saveable like the screen's tab and
    // fullscreen flags, so a rotation keeps the mode and expansion.
    var expanded by rememberSaveable { mutableStateOf(false) }
    var modeOrdinal by rememberSaveable { mutableIntStateOf(HistogramMode.RGB.ordinal) }
    val mode = HistogramMode.entries[modeOrdinal]

    // Resolved OUTSIDE the Canvas draw lambda (draw lambdas are not
    // composables). Roles for everything except the channel identity
    // colors (see the KDoc: the documented domain exception).
    val lumaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val dotBackingColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
    val channelColors = mapOf(
        FrameHistogram.Channel.RED to RedChannelColor,
        FrameHistogram.Channel.GREEN to GreenChannelColor,
        FrameHistogram.Channel.BLUE to BlueChannelColor,
        FrameHistogram.Channel.LUMA to lumaColor,
    )

    // Adaptive expanded geometry (see the KDoc SIZES/PLACEMENT): wide
    // enough to read, never wide/tall enough to reach the refresh chip
    // or leave the bounded preview region in any orientation.
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    // Width cap keeps the BottomEnd refresh chip's clearance: the widest
    // chip label ("Onizlemeyi yenile", ~175dp) + its 8dp inset + a gap.
    // The expanded panel also sits ABOVE the chip's band (see the height
    // formula), so at large font scales the two never fight for pixels.
    val expandedWidth = (screenWidthDp - 210).coerceIn(160, 280).dp
    // The expanded panel must fit INSIDE the bounded preview region
    // (EditorLayout.PREVIEW_HEIGHT_FRACTION of the screen) and clear the
    // TopStart toggle's band (8dp inset + 56dp = 64dp): the panel is
    // bottom-anchored at an 8dp inset, so the graph gets the preview
    // height minus that 64dp band, minus the panel's own chrome (mode
    // row ~40dp + padding 12dp + spacer 6dp = 58) and the below-panel
    // badge (~32dp with its gap) when it shows. Typical portrait previews
    // clear everything and hit the 150dp cap; a very short preview
    // (landscape phones) floors the graph at 36dp, where the panel may
    // reach into the toggle's band — the toggle is the histogram's OWN
    // control and redundant while expanded (tapping the graph
    // collapses), and the panel never reaches the top bar (it never
    // leaves the preview Box) nor the refresh chip (the width cap keeps
    // the chip's horizontal clearance; the badge stays at the Start
    // side, where the chip is not).
    val expandedGraphHeight = (
        (screenHeightDp * EditorLayout.PREVIEW_HEIGHT_FRACTION).toInt() -
            130 - if (result.isClipped()) 32 else 0
        ).coerceIn(36, 150).dp
    // Compact panel = the spec's 85dp: 6dp padding top + 73dp graph +
    // 6dp padding bottom. The full 85dp keeps the same footprint as the
    // old luma overlay, so the preview's other corner controls and the
    // refresh chip's clearance are unaffected.
    val compactGraphHeight = 73.dp

    // The compact view always draws the combined RGB set; the saved
    // mode applies once expanded. Keying the display computation on the
    // EFFECTIVE mode keeps both views' scales consistent with what is
    // on screen.
    val effectiveMode = if (expanded) mode else HistogramMode.RGB
    // Display-only smoothing + normalization, remembered per result so
    // it recomputes once per published frame, not per draw. The raw
    // counts are never touched.
    val display = remember(result, effectiveMode) {
        HistogramDisplay.compute(result, effectiveMode)
    }
    // Clip indicators read the RAW counts (not the smoothed display).
    val shadowClipSlots = FrameHistogram.Channel.entries.filter { result.isBlackClipped(it) }
    val highlightClipSlots = FrameHistogram.Channel.entries.filter { result.isWhiteClipped(it) }

    val description = stringResource(
        if (expanded) R.string.editor_histogram_collapse else R.string.editor_histogram_expand
    )

    Column(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.7f),
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .widthIn(max = if (expanded) expandedWidth else 160.dp)
                .animateContentSize(),
        ) {
            Column(Modifier.padding(6.dp)) {
                if (expanded) {
                    // Mode switch: RGB / Luma / Parade. The labels are
                    // literal codes in every locale (owner spec: mode
                    // names stay untranslated), like ACES/AgX do.
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        HistogramMode.entries.forEachIndexed { index, m ->
                            SegmentedButton(
                                selected = m == mode,
                                onClick = { modeOrdinal = m.ordinal },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = HistogramMode.entries.size,
                                ),
                            ) {
                                Text(m.label)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                // The GRAPH is the tap target, not the panel: clickable
                // merges descendant semantics, so a clickable Surface
                // would swallow the mode row's buttons for TalkBack.
                // The graph carries the toggle action and its
                // description; the mode row stays its own nodes.
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(if (expanded) expandedGraphHeight else compactGraphHeight)
                        .clickable { expanded = !expanded }
                        .semantics { contentDescription = description },
                ) {
                    if (effectiveMode == HistogramMode.PARADE) {
                        drawParade(display, channelColors, gridColor, dotBackingColor,
                            shadowClipSlots, highlightClipSlots)
                    } else {
                        drawGrid(gridColor)
                        // Draw order blue -> green -> red: with SrcOver
                        // fills the overlap mixes like Lightroom's, and
                        // the red channel reads on top.
                        if (effectiveMode == HistogramMode.RGB) {
                            drawCurve(display, FrameHistogram.Channel.BLUE, BlueChannelColor)
                            drawCurve(display, FrameHistogram.Channel.GREEN, GreenChannelColor)
                            drawCurve(display, FrameHistogram.Channel.RED, RedChannelColor)
                            // The subtle grey luma outline ON TOP of the
                            // RGB fills (owner's improvement over the
                            // RapidRAW reference): outline only, no fill,
                            // so it never obscures the channels.
                            drawOutline(display, FrameHistogram.Channel.LUMA, lumaColor)
                        } else {
                            drawCurve(display, FrameHistogram.Channel.LUMA, lumaColor)
                        }
                        drawClipDots(shadowClipSlots, channelColors, dotBackingColor,
                            anchorStart = true, boundsWidth = size.width)
                        drawClipDots(highlightClipSlots, channelColors, dotBackingColor,
                            anchorStart = false, boundsWidth = size.width)
                    }
                }
            }
        }
        // The clipped badge (the existing string, reused) sits BELOW the
        // panel in BOTH shapes — the old placement, in the bottom band at
        // the Start side where the BottomEnd refresh chip is not.
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
 * Two faint vertical lines at the tonal thirds — shadows | midtones |
 * highlights (owner spec) — under the curves.
 */
private fun DrawScope.drawGrid(color: Color) {
    val w = size.width
    val h = size.height
    for (x in listOf(w / 3f, w * 2f / 3f)) {
        drawLine(color, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
    }
}

/**
 * One channel's filled curve with a thin crisp outline, across the full
 * canvas width. [display] holds the display-only normalized values.
 */
private fun DrawScope.drawCurve(
    display: Map<FrameHistogram.Channel, FloatArray>,
    channel: FrameHistogram.Channel,
    color: Color,
) {
    val values = display[channel] ?: return
    val fill = Path()
    val outline = Path()
    buildCurvePaths(values, 0f, size.width, size.height, fill, outline)
    // Semi-transparent fill (the spec's 40-60% band): overlapping
    // channels mix under SrcOver and stay readable.
    drawPath(fill, color.copy(alpha = 0.5f))
    drawPath(
        outline, color,
        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** The luma outline drawn on top of the RGB fills: no fill, thin line. */
private fun DrawScope.drawOutline(
    display: Map<FrameHistogram.Channel, FloatArray>,
    channel: FrameHistogram.Channel,
    color: Color,
) {
    val values = display[channel] ?: return
    val outline = Path()
    val sink = Path()
    buildCurvePaths(values, 0f, size.width, size.height, sink, outline)
    drawPath(
        outline, color,
        style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * Parade: three side-by-side channel cells sharing ONE scale (the
 * display map is normalized by the common denominator), so relative
 * channel levels read across cells. Cell boundaries double as the
 * gridlines; each cell carries its own channel's clip dots.
 */
private fun DrawScope.drawParade(
    display: Map<FrameHistogram.Channel, FloatArray>,
    channelColors: Map<FrameHistogram.Channel, Color>,
    gridColor: Color,
    dotBackingColor: Color,
    shadowClipSlots: List<FrameHistogram.Channel>,
    highlightClipSlots: List<FrameHistogram.Channel>,
) {
    val w = size.width
    val h = size.height
    val cellW = w / 3f
    // Cell boundaries as the gridlines (per-cell thirds would crowd a
    // cell this narrow).
    for (x in listOf(cellW, cellW * 2f)) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
    }
    val paradeOrder = listOf(
        FrameHistogram.Channel.RED,
        FrameHistogram.Channel.GREEN,
        FrameHistogram.Channel.BLUE,
    )
    paradeOrder.forEachIndexed { index, channel ->
        val left = index * cellW
        clipRect(left = left, right = left + cellW) {
            val values = display[channel] ?: return@clipRect
            val fill = Path()
            val outline = Path()
            buildCurvePaths(values, left, cellW, h, fill, outline)
            drawPath(fill, channelColors.getValue(channel).copy(alpha = 0.5f))
            drawPath(
                outline, channelColors.getValue(channel),
                style = Stroke(width = 1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // This cell reports only ITS channel's clipping.
            if (channel in shadowClipSlots) {
                drawClipDots(listOf(channel), channelColors, dotBackingColor,
                    anchorStart = true, boundsLeft = left, boundsWidth = cellW)
            }
            if (channel in highlightClipSlots) {
                drawClipDots(listOf(channel), channelColors, dotBackingColor,
                    anchorStart = false, boundsLeft = left, boundsWidth = cellW)
            }
        }
    }
}

/**
 * Builds the fill path (curve closed along the bottom) and the outline
 * path (the curve alone) for [values] (0..1) inside the horizontal
 * span [left, left+width]. 256 samples across the span: the Gaussian
 * smoothing upstream makes the polyline read as a smooth curve.
 */
private fun buildCurvePaths(
    values: FloatArray,
    left: Float,
    width: Float,
    height: Float,
    fill: Path,
    outline: Path,
) {
    val step = width / (FrameHistogram.BINS - 1)
    fill.moveTo(left, height)
    for (i in values.indices) {
        val x = left + i * step
        val y = height * (1f - values[i])
        if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        fill.lineTo(x, y)
    }
    fill.lineTo(left + width, height)
    fill.close()
}

/**
 * One clipping indicator: a small pill of colored dots in the graph's
 * bottom corner — start corner for shadow clipping, end corner for
 * highlight clipping — one dot per slot that clips, on a scrim backing
 * so the dots read over the curves. [boundsLeft]/[boundsWidth] confine
 * the indicator to one parade cell; they default to the full canvas.
 */
private fun DrawScope.drawClipDots(
    slots: List<FrameHistogram.Channel>,
    channelColors: Map<FrameHistogram.Channel, Color>,
    backingColor: Color,
    anchorStart: Boolean,
    boundsLeft: Float = 0f,
    boundsWidth: Float,
) {
    if (slots.isEmpty()) return
    val radius = 2.5.dp.toPx()
    val gap = 3.dp.toPx()
    val pad = 3.dp.toPx()
    val outer = 4.dp.toPx()
    val backingWidth = slots.size * 2 * radius + (slots.size - 1) * gap + 2 * pad
    val backingHeight = 2 * radius + 2 * pad
    val left = if (anchorStart) {
        boundsLeft + outer
    } else {
        boundsLeft + boundsWidth - outer - backingWidth
    }
    val top = size.height - outer - backingHeight
    drawRoundRect(
        color = backingColor,
        topLeft = Offset(left, top),
        size = Size(backingWidth, backingHeight),
        cornerRadius = CornerRadius(backingHeight / 2),
    )
    slots.forEachIndexed { i, slot ->
        val cx = left + pad + radius + i * (2 * radius + gap)
        val cy = top + backingHeight / 2
        drawCircle(channelColors.getValue(slot), radius = radius, center = Offset(cx, cy))
    }
}

/**
 * The histogram's display modes. The labels are literal untranslated
 * codes in every locale (owner spec: RGB / Luma / Parade are product
 * vocabulary, like ACES and AgX).
 */
internal enum class HistogramMode(val label: String) {
    RGB("RGB"),
    LUMA("Luma"),
    PARADE("Parade"),
}

/**
 * The display pipeline for one mode — PURE, no Compose (JVM-testable
 * like FrameHistogram and EditorLayout): Gaussian-smooth a COPY of each
 * drawn channel's raw bins (sigma 2, radius 6, 13 taps, edge-replicate
 * — the RapidRAW look), then normalize all drawn channels by ONE
 * common denominator: the largest 99th-percentile smoothed count among
 * the FILLED channels (the luma outline rides the RGB denominator and
 * clamps at the top; including it would shrink the RGB fills since
 * luma typically peaks highest). The top-1% ignore keeps one black or
 * white spike from flattening the rest of the curve. The returned
 * values are 0..1 display values; the raw counts and clip counts in
 * [result] are untouched — the owner's raw-counts-stay-raw rule.
 */
internal object HistogramDisplay {

    /** The display values (0..1) per drawn channel for [mode]. */
    fun compute(
        result: FrameHistogram.Result,
        mode: HistogramMode,
    ): Map<FrameHistogram.Channel, FloatArray> {
        val drawn = when (mode) {
            HistogramMode.RGB -> listOf(
                FrameHistogram.Channel.RED, FrameHistogram.Channel.GREEN,
                FrameHistogram.Channel.BLUE, FrameHistogram.Channel.LUMA,
            )
            HistogramMode.LUMA -> listOf(FrameHistogram.Channel.LUMA)
            HistogramMode.PARADE -> listOf(
                FrameHistogram.Channel.RED, FrameHistogram.Channel.GREEN,
                FrameHistogram.Channel.BLUE,
            )
        }
        // The scale is set by the FILLED channels only (see the KDoc).
        val filled = when (mode) {
            HistogramMode.RGB -> listOf(
                FrameHistogram.Channel.RED, FrameHistogram.Channel.GREEN,
                FrameHistogram.Channel.BLUE,
            )
            else -> drawn
        }
        val smoothed = HashMap<FrameHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            smoothed[channel] = smooth(result.bins(channel))
        }
        var denominator = 1f
        for (channel in filled) {
            denominator = max(denominator, percentile99(smoothed.getValue(channel)))
        }
        val out = HashMap<FrameHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            val values = smoothed.getValue(channel)
            out[channel] = FloatArray(values.size) { (values[it] / denominator).coerceIn(0f, 1f) }
        }
        return out
    }

    /**
     * Gaussian smoothing, DISPLAY-ONLY: sigma 2.0, radius ceil(3*sigma)
     * = 6 (13 taps), normalized kernel, edge-replicate boundaries — the
     * same parameters as the RapidRAW reference's smoothing, applied to
     * a copy so the raw counts keep their exact values.
     */
    fun smooth(bins: IntArray): FloatArray {
        val sigma = 2f
        val radius = 6
        val kernel = FloatArray(2 * radius + 1) { i ->
            val d = (i - radius).toFloat()
            exp(-d * d / (2f * sigma * sigma))
        }
        val kernelSum = kernel.sum()
        val out = FloatArray(bins.size)
        for (i in bins.indices) {
            var acc = 0f
            for (k in kernel.indices) {
                val j = (i + k - radius).coerceIn(0, bins.size - 1)
                acc += bins[j] * kernel[k]
            }
            out[i] = acc / kernelSum
        }
        return out
    }

    /** The 99th-percentile smoothed count (sorted index 252 of 256). */
    fun percentile99(values: FloatArray): Float {
        val sorted = values.copyOf().also { it.sort() }
        return sorted[(sorted.size - 1) * 99 / 100]
    }
}

// Channel identity colors — the documented exception to roles-never-hex
// (see HistogramOverlay's KDoc): a channel's color IS its meaning, and
// these RapidRAW-studied values are tuned for the dark scrim.
private val RedChannelColor = Color(0xFFFF6B6B)
private val GreenChannelColor = Color(0xFF6BCB77)
private val BlueChannelColor = Color(0xFF4D96FF)

/**
 * The histogram toggle: a stock M3 IconButton (48dp target, DESIGN.md
 * Inputs) with the vendored bar_chart glyph, placed by the screen at
 * the preview's TopStart corner — the mirror of the fullscreen TopEnd
 * toggle, so the preview's corner icon buttons read as one group. The
 * button's content description carries the toggle state for TalkBack
 * (the action it will perform, like the fullscreen toggle's "Exit
 * fullscreen"); the glyph itself stays decorative.
 *
 * Scrim behind the glyph (owner finding 2026-11-11): over overexposed
 * near-white footage the white icon glyphs of ALL preview corner
 * controls vanished into the frame. The toggle carries the same
 * translucent surfaceContainerLowest scrim (roles, never hex —
 * DESIGN.md; this file's own overlay backdrop is the precedent) at the
 * theme's small shape as the fullscreen and refresh controls. The
 * Surface wraps, not replaces, the IconButton — the 48dp touch target
 * and the click path are unchanged.
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_histogram),
                contentDescription = null,
            )
        }
    }
}
