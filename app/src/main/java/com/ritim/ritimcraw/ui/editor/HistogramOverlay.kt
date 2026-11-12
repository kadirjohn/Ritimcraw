package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.RawHistogram
import kotlin.math.exp
import kotlin.math.max

/**
 * The histogram scope content (owner spec PLAY-10, 2026-11-12 §1 + the
 * manager's design decision): the corner overlay's histogram UI moved
 * INTO the scope strip — the feature is preserved whole (RGB/Luma/
 * Parade modes, display-only smoothing, clipping indicators), but it
 * is no longer an overlay on the image (§2: "Görüntünün üzerine
 * bindirme"). [ScopeStrip] composes this view as its HISTOGRAM content
 * under the strip's Histogram | Waveform selector; the corner scope
 * toggle opens the strip. Pure display — the screen owns the open
 * flag and the per-frame recompute (its scope compute effect, off the
 * UI thread); nothing here touches the render pipeline.
 *
 * WHAT CHANGED from the overlay shape: the strip is a full-width row
 * of the workspace, so the adaptive size caps, the scrimmed panel, the
 * expand/collapse tap and the below-panel badge are gone. The graph
 * takes [ScopeGraphHeight] and the clipped badge moves into the fixed
 * [ScopeLabelBandHeight] band (HEIGHT STABILITY in ScopeStrip's KDoc:
 * the strip's height never changes on a clipping-state change, so the
 * panel anchors below never churn mid-session). The mode row is
 * always visible — the strip IS the expanded scope; there is no
 * compact shape to save space for. The mode now lives in the SCREEN
 * (saveable there — feature N6, the waveform's precedent): the RAW
 * mode's sensor-domain stats are fetched by the screen's scope compute
 * keyed to the mode, so the choice must live where the compute is
 * keyed. The screen passes the ordinal + callback down, exactly like
 * WaveformScopeView.
 *
 * MODES: RGB (combined) — the three channel fills + a subtle grey luma
 * outline on top, the owner's Lightroom-style improvement over
 * RapidRAW; Luma — the grey curve alone; Parade — three side-by-side
 * channel graphs, one common scale so relative channel levels read;
 * RAW (feature N6) — the PRE-DEVELOP sensor-domain CFA histogram of
 * the frame, the same combined-channel shape over the develop-
 * normalized bins.
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
 * badge (the existing string, reused) sits in the fixed label band at
 * the Start side — the strip's own bottom row, where the waveform's
 * signal label also sits.
 *
 * THE TWO DOMAINS NEVER MIX (feature N6, the owner's mandate): the
 * RGB/Luma/Parade modes describe the PROCESSED displayed image
 * (post-grade, post-OETF) and light the processed "Clipped" badge; the
 * RAW mode describes the PRE-DEVELOP sensor data and lights its OWN
 * "RAW clip" badge — visually distinct (a different string AND a
 * different color treatment, never the errorContainer the processed
 * badge uses, so the two can never be mistaken for each other). A
 * processed clip never lights the RAW badge and a RAW clip never
 * lights the processed badge; each mode shows ONLY its own domain's
 * indicators. When the RAW stats are unavailable (an encoded clip has
 * no sensor data, or the fetch failed) the RAW mode shows an honest
 * "RAW: no sensor data" notice in the label band — never a fabricated
 * curve, never the processed histogram in its place.
 *
 * PERFORMANCE: the per-frame work is one pass in FrameHistogram (off
 * the UI thread, in the screen's scope compute effect); here the
 * smoothed/normalized display arrays are remembered per result
 * (recomputed once per published frame, not per draw), and the draw
 * itself is Canvas + Path with no chart library. Update frequency is
 * bounded by the scope compute effect's cadence; drawing never blocks
 * on computation.
 */
@Composable
fun HistogramScopeView(
    result: FrameHistogram.Result,
    modeOrdinal: Int,
    onModeChange: (Int) -> Unit,
    raw: RawHistogram?,
    // The RAW mode's presentation (owner answer #6): COMBINED (the
    // overlaid shape) or PARADE (three side-by-side sensor cells).
    // Screen saveable state like the mode ordinal — display-only, the
    // compute effect does NOT key on it (both shapes draw the SAME
    // fetched stats; no new decode, no new fetch).
    rawPresentationOrdinal: Int,
    onRawPresentationChange: (Int) -> Unit,
    // Tapping the graph itself shrinks the strip to the mini overlay
    // (owner request 2026-11-12 §2): only the GRAPH area answers — the
    // mode row above stays mode-switching, the label band stays
    // informational. Default {} keeps the view compiling unchanged for
    // any caller that does not opt into the shrink gesture.
    onGraphTap: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The mode now arrives from the screen (see the KDoc): the RAW
    // fetch is keyed to it there. Coerced the same way — the ordinal
    // crosses process-death save/restore; a stale value must select
    // the nearest real mode.
    val mode = HistogramMode.entries[modeOrdinal.coerceIn(0, HistogramMode.entries.lastIndex)]
    val rawPresentation = RawHistogramModeRules.fromOrdinal(rawPresentationOrdinal)

    Column(modifier) {
        // Mode switch row: RGB / Luma / Parade / RAW segmented buttons —
        // and in the RAW mode, the parade chip as a PEER of the mode
        // buttons (the waveform's sampling-chip pattern: one row, no
        // height change — HEIGHT STABILITY). The labels are literal
        // codes in every locale (owner spec: mode names stay
        // untranslated), like ACES/AgX do.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                HistogramMode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = m == mode,
                        onClick = { onModeChange(m.ordinal) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = HistogramMode.entries.size,
                        ),
                    ) {
                        Text(m.label)
                    }
                }
            }
            if (mode == HistogramMode.RAW) {
                // The RAW parade toggle (owner answer #6): a compact
                // chip, present ONLY in the RAW mode — a display-only
                // choice over the already-fetched stats. The
                // description names show/hide for accessibility; the
                // visible label is the distinct "RAW Parade" name
                // (never the processed Parade's).
                val chipLabel = stringResource(R.string.editor_histogram_raw_parade)
                val chipDescription = stringResource(
                    if (rawPresentation == RawHistogramPresentation.PARADE) {
                        R.string.editor_histogram_raw_parade_hide
                    } else {
                        R.string.editor_histogram_raw_parade_show
                    }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = rawPresentation == RawHistogramPresentation.PARADE,
                    onClick = {
                        onRawPresentationChange(
                            RawHistogramModeRules.onToggle(rawPresentation).ordinal
                        )
                    },
                    label = { Text(chipLabel, maxLines = 1) },
                    modifier = Modifier.semantics { contentDescription = chipDescription },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // The graph itself is [HistogramGraph] — the ONE draw path,
        // shared with the mini overlay (see its KDoc).
        HistogramGraph(
            result = result,
            raw = raw,
            mode = mode,
            rawPresentation = rawPresentation,
            onGraphTap = onGraphTap,
            modifier = Modifier
                .fillMaxWidth()
                .height(ScopeGraphHeight),
        )
        // The fixed label band (HEIGHT STABILITY): the clipped badge —
        // the existing string, reused — at the Start side, where the
        // waveform's signal label also sits. The band is reserved even
        // when nothing clips, so the strip's height never changes on a
        // clipping-state change and the panel anchors below never
        // churn.
        Box(
            Modifier.fillMaxWidth().height(ScopeLabelBandHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (mode == HistogramMode.RAW) {
                // The RAW label band: the sensor-domain badge ONLY —
                // the processed "Clipped" badge is never shown in this
                // mode, and this badge is never shown in the processed
                // modes. Visually distinct (the KDoc): a different
                // string AND a different color treatment than the
                // processed badge's errorContainer.
                if (raw != null && raw.isClipped()) {
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            stringResource(R.string.editor_histogram_raw_clip),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                } else if (raw == null) {
                    // Honest unavailability: an encoded clip has no
                    // sensor data, or the fetch failed — NEVER a
                    // fabricated curve or the processed histogram in
                    // the RAW mode's place.
                    Text(
                        stringResource(R.string.editor_histogram_raw_unavailable),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (result.isClipped()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
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
}

/**
 * THE HISTOGRAM GRAPH ITSELF — the one draw path, extracted (owner
 * request 2026-11-12 §2) so the strip's view and the strip's MINI
 * overlay draw the exact same picture from the exact same inputs:
 * same display pipeline ([HistogramDisplay] — smoothed, normalized),
 * same channel colors, same clip indicators. The mini is display-only
 * BY CONSTRUCTION: it composes this, it does not re-derive anything.
 *
 * [onGraphTap]: the strip's graph-tap shrink gesture — when non-null
 * the graph carries tap detection (tap the graph itself -> the strip
 * collapses to the mini). The mini passes NULL: the mini's whole
 * container is the tap target (tap anywhere -> the strip expands
 * again), and a second tap node inside it would compete with the
 * container's own clickable.
 *
 * The graph fills the width and height its modifier gives it — the
 * strip gives it [ScopeGraphHeight] at full width; the mini gives it a
 * smaller box. All drawing is resolution-relative (paths built from
 * size.*), so the same code draws both sizes.
 */
@Composable
internal fun HistogramGraph(
    result: FrameHistogram.Result,
    raw: RawHistogram?,
    mode: HistogramMode,
    rawPresentation: RawHistogramPresentation,
    onGraphTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    // Resolved OUTSIDE the Canvas draw lambda (draw lambdas are not
    // composables). Roles for everything except the channel identity
    // colors (see the file KDoc: the documented domain exception).
    val lumaColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    val dotBackingColor = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)
    val channelColors = mapOf(
        FrameHistogram.Channel.RED to RedChannelColor,
        FrameHistogram.Channel.GREEN to GreenChannelColor,
        FrameHistogram.Channel.BLUE to BlueChannelColor,
        FrameHistogram.Channel.LUMA to lumaColor,
    )
    // The RAW channel colors: the same three site colors (a CFA site's
    // color IS its meaning, the same documented exception), keyed by
    // the RAW domain's own Channel type — the two enums never share a
    // map, so a processed-slot lookup can never answer a RAW question.
    val rawChannelColors = mapOf(
        RawHistogram.Channel.RED to RedChannelColor,
        RawHistogram.Channel.GREEN to GreenChannelColor,
        RawHistogram.Channel.BLUE to BlueChannelColor,
    )

    // Display-only smoothing + normalization, remembered per result so
    // it recomputes once per published frame, not per draw. The raw
    // counts are never touched. RAW has its own pipeline (computeRaw)
    // over the sensor-domain bins — the two domains never share a
    // compute path.
    val display = remember(result, mode) {
        HistogramDisplay.compute(result, mode)
    }
    // The RAW pipeline is presentation-dependent (owner answer #6):
    // COMBINED keeps computeRaw's count-based common denominator
    // (relative CFA levels across channels are the point of the
    // overlay); PARADE uses computeRawParade's per-site density (the
    // Bayer 2x green geometry is divided out — see its KDoc). Both are
    // display-only over the SAME fetched stats.
    val rawDisplay = remember(raw, mode, rawPresentation) {
        when {
            mode != HistogramMode.RAW -> null
            rawPresentation == RawHistogramPresentation.PARADE ->
                HistogramDisplay.computeRawParade(raw)
            else -> HistogramDisplay.computeRaw(raw)
        }
    }
    // Clip indicators read the RAW counts (not the smoothed display).
    val shadowClipSlots = FrameHistogram.Channel.entries.filter { result.isBlackClipped(it) }
    val highlightClipSlots = FrameHistogram.Channel.entries.filter { result.isWhiteClipped(it) }
    // The RAW indicators read the SENSOR-domain counts — never the
    // processed result's (see the KDoc: the domains never mix).
    val rawShadowClipSlots = raw?.let { r ->
        RawHistogram.Channel.entries.filter { r.isBlackClipped(it) }
    } ?: emptyList()
    val rawHighlightClipSlots = raw?.let { r ->
        RawHistogram.Channel.entries.filter { r.isWhiteClipped(it) }
    } ?: emptyList()

    val tapModifier = if (onGraphTap != null) {
        // The graph-tap shrink (owner request 2026-11-12 §2): tap
        // detection rides the graph's own node — the mode row and the
        // label band never fire it.
        Modifier.pointerInput(Unit) {
            detectTapGestures { onGraphTap() }
        }
    } else {
        Modifier
    }
    Canvas(modifier.then(tapModifier)) {
        if (mode == HistogramMode.RAW) {
            // PRE-DEVELOP sensor domain: the RAW curves over the
            // develop-normalized CFA bins, with the RAW domain's
            // OWN clip dots. No processed indicator is drawn here,
            // ever (the owner's mandate). The presentation picks the
            // shape: COMBINED (the overlaid channels) or PARADE
            // (three side-by-side cells, owner answer #6) — both over
            // the SAME fetched stats.
            if (rawPresentation == RawHistogramPresentation.PARADE) {
                drawRawParade(
                    rawDisplay ?: emptyMap(),
                    rawChannelColors,
                    gridColor,
                    dotBackingColor,
                    rawShadowClipSlots,
                    rawHighlightClipSlots,
                )
            } else {
                drawGrid(gridColor)
                if (rawDisplay != null) {
                    // Same draw order as the processed RGB mode: blue
                    // -> green -> red, SrcOver fills mix, red reads on
                    // top.
                    drawRawCurve(rawDisplay, RawHistogram.Channel.BLUE, BlueChannelColor)
                    drawRawCurve(rawDisplay, RawHistogram.Channel.GREEN, GreenChannelColor)
                    drawRawCurve(rawDisplay, RawHistogram.Channel.RED, RedChannelColor)
                }
                drawRawClipDots(rawShadowClipSlots, rawChannelColors, dotBackingColor,
                    anchorStart = true, boundsWidth = size.width)
                drawRawClipDots(rawHighlightClipSlots, rawChannelColors, dotBackingColor,
                    anchorStart = false, boundsWidth = size.width)
            }
        } else if (mode == HistogramMode.PARADE) {
            drawParade(display, channelColors, gridColor, dotBackingColor,
                shadowClipSlots, highlightClipSlots)
        } else {
            drawGrid(gridColor)
            // Draw order blue -> green -> red: with SrcOver fills
            // the overlap mixes like Lightroom's, and the red
            // channel reads on top.
            if (mode == HistogramMode.RGB) {
                drawCurve(display, FrameHistogram.Channel.BLUE, BlueChannelColor)
                drawCurve(display, FrameHistogram.Channel.GREEN, GreenChannelColor)
                drawCurve(display, FrameHistogram.Channel.RED, RedChannelColor)
                // The subtle grey luma outline ON TOP of the RGB
                // fills (owner's improvement over the RapidRAW
                // reference): outline only, no fill, so it never
                // obscures the channels.
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
 * One RAW channel's filled curve — the same shape as [drawCurve] over
 * the sensor-domain display values (feature N6). A separate function
 * (not a shared generic) so the two domains' draw paths stay visibly
 * separate in the source: the RAW canvas block never calls a
 * processed-channel helper and vice versa.
 */
private fun DrawScope.drawRawCurve(
    display: Map<RawHistogram.Channel, FloatArray>,
    channel: RawHistogram.Channel,
    color: Color,
) {
    val values = display[channel] ?: return
    val fill = Path()
    val outline = Path()
    buildCurvePaths(values, 0f, size.width, size.height, fill, outline)
    drawPath(fill, color.copy(alpha = 0.5f))
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
 * The RAW PARADE (owner answer #6): the sensor-domain mirror of
 * [drawParade] — three side-by-side CFA channel cells over the
 * per-site-density display values ([HistogramDisplay.computeRawParade],
 * which divides out the Bayer 2x green geometry), with the RAW domain's
 * OWN clip dots per cell. A separate function for the same reason as
 * [drawRawCurve]: the RAW draw path never calls a processed-channel
 * helper and vice versa — a slot of one domain can never be looked up
 * in the other's color map.
 */
private fun DrawScope.drawRawParade(
    display: Map<RawHistogram.Channel, FloatArray>,
    channelColors: Map<RawHistogram.Channel, Color>,
    gridColor: Color,
    dotBackingColor: Color,
    shadowClipSlots: List<RawHistogram.Channel>,
    highlightClipSlots: List<RawHistogram.Channel>,
) {
    val w = size.width
    val h = size.height
    val cellW = w / 3f
    // Cell boundaries as the gridlines (the processed parade's layout).
    for (x in listOf(cellW, cellW * 2f)) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
    }
    val paradeOrder = listOf(
        RawHistogram.Channel.RED,
        RawHistogram.Channel.GREEN,
        RawHistogram.Channel.BLUE,
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
            // This cell reports only ITS channel's sensor-domain clips.
            if (channel in shadowClipSlots) {
                drawRawClipDots(listOf(channel), channelColors, dotBackingColor,
                    anchorStart = true, boundsLeft = left, boundsWidth = cellW)
            }
            if (channel in highlightClipSlots) {
                drawRawClipDots(listOf(channel), channelColors, dotBackingColor,
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
 * The RAW domain's clip-dot indicator — the same pill shape as
 * [drawClipDots] over the sensor-domain slots (feature N6). Separate
 * for the same reason as [drawRawCurve]: the RAW draw path never calls
 * the processed helper, so a slot of one domain can never be looked up
 * in the other's color map.
 */
private fun DrawScope.drawRawClipDots(
    slots: List<RawHistogram.Channel>,
    channelColors: Map<RawHistogram.Channel, Color>,
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
 * vocabulary, like ACES and AgX; RAW is the same kind of product
 * code — the sensor domain's name).
 */
internal enum class HistogramMode(val label: String) {
    RGB("RGB"),
    LUMA("Luma"),
    PARADE("Parade"),
    RAW("RAW"),
}

/**
 * The RAW mode's own presentation choice (owner answer #6, the RAW
 * parade): COMBINED — the existing three-channel-overlaid shape over
 * the sensor bins — or PARADE — three side-by-side per-channel cells,
 * the sensor-domain mirror of the processed Parade mode's layout.
 * A SEPARATE axis from [HistogramMode] (not a fifth entry): every RAW
 * shape needs the SAME fetched [RawHistogram] (no new decode, no new
 * fetch — display-only), so the choice must not re-key the screen's
 * scope compute effect the way the mode itself does. The chip state
 * is screen saveable exactly like the mode ordinal; the pure decision
 * rules below are JVM-tested.
 */
internal enum class RawHistogramPresentation {
    COMBINED,
    PARADE,
}

/**
 * The pure mode-decision rules for the RAW parade toggle (the same
 * pattern as [ScopePresentationRules]): the screen stores the enum and
 * calls these — no transition logic in the composables.
 */
internal object RawHistogramModeRules {

    /** The chip toggle: flip between the two RAW presentations. */
    fun onToggle(state: RawHistogramPresentation): RawHistogramPresentation =
        if (state == RawHistogramPresentation.COMBINED) {
            RawHistogramPresentation.PARADE
        } else {
            RawHistogramPresentation.COMBINED
        }

    /** The ordinal is saveable state: a stale value coerces to a real one. */
    fun fromOrdinal(ordinal: Int): RawHistogramPresentation =
        RawHistogramPresentation.entries[ordinal.coerceIn(0, RawHistogramPresentation.entries.lastIndex)]
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
            // The RAW mode's display is computed by [computeRaw] from
            // the sensor-domain RawHistogram — a DIFFERENT input type,
            // never this processed-image pipeline. This branch exists
            // only to satisfy the exhaustive when; it never runs (the
            // view calls computeRaw directly for RAW).
            HistogramMode.RAW -> emptyList()
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
     * The RAW mode's display pipeline (feature N6) — the same
     * display-only discipline over the PRE-DEVELOP sensor-domain bins:
     * Gaussian-smooth a COPY of each CFA channel's bins (the same
     * kernel as the processed pipeline — [smooth] is domain-agnostic),
     * then normalize all three by ONE common denominator (the largest
     * 99th-percentile smoothed count). The raw counts and clip counts
     * in [raw] are untouched — the RAW indicators read the exact
     * sensor clips, not bin-edge approximations, so smoothing can
     * never create or hide a RAW clip warning.
     *
     * A SEPARATE entry point from [compute], not a mode branch inside
     * it: the RAW mode's input type is [RawHistogram] (the sensor
     * domain), not [FrameHistogram.Result] (the processed domain) —
     * the two domains never share a compute path, so a processed
     * result can never be drawn as RAW or the reverse. Returns an
     * EMPTY map when [raw] is null (stats unavailable): the caller
     * shows the honest unavailability notice, never a fabricated
     * curve.
     */
    fun computeRaw(raw: RawHistogram?): Map<RawHistogram.Channel, FloatArray> {
        if (raw == null) return emptyMap()
        val drawn = listOf(
            RawHistogram.Channel.RED,
            RawHistogram.Channel.GREEN,
            RawHistogram.Channel.BLUE,
        )
        val smoothed = HashMap<RawHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            smoothed[channel] = smooth(raw.bins(channel))
        }
        var denominator = 1f
        for (channel in drawn) {
            denominator = max(denominator, percentile99(smoothed.getValue(channel)))
        }
        val out = HashMap<RawHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            val values = smoothed.getValue(channel)
            out[channel] = FloatArray(values.size) { (values[it] / denominator).coerceIn(0f, 1f) }
        }
        return out
    }

    /**
     * The RAW PARADE's display pipeline (owner answer #6) — the same
     * display-only discipline, but a DIFFERENT normalization than the
     * combined RAW view, because the sensor domain has a geometry
     * artifact the processed domain does not: the Bayer 2x2 cell has
     * TWO green sites and one red, one blue (RawStats counts each site
     * into its channel's bins, so green's ~2x sample count is CFA
     * GEOMETRY, not signal content). Normalizing raw counts by one
     * denominator — [computeRaw]'s discipline, correct for the combined
     * view where relative CFA levels are the point — would draw green
     * ~2x taller than the same content in R/B in a per-cell parade.
     *
     * THE NORMALIZATION (per-site density, the owner's approved option):
     * each channel's smoothed counts are divided by ITS OWN sample count
     * (the sites that fed the channel), giving a per-bin density — the
     * fraction of that channel's sites in that bin — then all three are
     * scaled by ONE common denominator (the largest 99th-percentile
     * density). The count-based 2x green artifact is divided out exactly
     * (G's samples ≈ 2x R's, per the native stats layout), relative
     * densities still read across cells through the common scale, and
     * the top-1% spike ignore is preserved (percentile99 over densities).
     * A channel with zero samples (an impossible sensor state, but the
     * honest floor) draws flat zero — its density is defined as zero,
     * never a division by zero.
     *
     * The raw counts, clip counts and sample counts in [raw] are
     * untouched (the owner's raw-counts-stay-raw rule — the RAW clip
     * indicators keep reading the exact sensor clips); the division
     * happens on the smoothed COPY. Reuses the SAME fetched stats —
     * display-only, no new decode or fetch.
     */
    fun computeRawParade(raw: RawHistogram?): Map<RawHistogram.Channel, FloatArray> {
        if (raw == null) return emptyMap()
        val drawn = listOf(
            RawHistogram.Channel.RED,
            RawHistogram.Channel.GREEN,
            RawHistogram.Channel.BLUE,
        )
        val smoothed = HashMap<RawHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            smoothed[channel] = smooth(raw.bins(channel))
        }
        // Per-site density: the smoothed count divided by the channel's
        // OWN site count — the CFA geometry's 2x green is divided out
        // exactly, so a cell's curve reads the channel's tonal
        // DISTRIBUTION, not its site count.
        val densities = HashMap<RawHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            val values = smoothed.getValue(channel)
            val samples = raw.samples[channel.ordinal].toFloat()
            densities[channel] = if (samples <= 0f) {
                FloatArray(values.size)
            } else {
                FloatArray(values.size) { values[it] / samples }
            }
        }
        // ONE common denominator over the densities (the same common-
        // scale discipline: per-cell scaling would equalize the curves
        // and lie about relative densities).
        var denominator = 1e-12f
        for (channel in drawn) {
            denominator = max(denominator, percentile99(densities.getValue(channel)))
        }
        val out = HashMap<RawHistogram.Channel, FloatArray>(drawn.size)
        for (channel in drawn) {
            val values = densities.getValue(channel)
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
// (see HistogramScopeView's KDoc): a channel's color IS its meaning, and
// these RapidRAW-studied values are tuned for the dark scrim.
private val RedChannelColor = Color(0xFFFF6B6B)
private val GreenChannelColor = Color(0xFF6BCB77)
private val BlueChannelColor = Color(0xFF4D96FF)
