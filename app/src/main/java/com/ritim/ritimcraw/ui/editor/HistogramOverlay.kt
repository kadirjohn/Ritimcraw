package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.RawHistogram
import com.ritim.ritimcraw.ui.components.CompactSegmented
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
 * takes [ScopeGraphHeight] and the clip readouts live inside the graph
 * panel (HEIGHT STABILITY in ScopeStrip's KDoc: the strip's height never
 * changes on a clipping-state change, so the panel anchors below never
 * churn mid-session). The mode row is
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
 * CLIPPING INDICATORS (2026-11-23 redesign): an error-colored edge bar
 * on the clipped end of the graph plus a corner pill — bottom-left for
 * shadow clipping, bottom-right for highlight clipping — carrying one
 * dot per clipped channel and the measured share of samples (the worst
 * drawn channel), lit at the documented 0.1% threshold. The pills live
 * INSIDE the graph panel, so the strip's height still never changes on
 * a clipping-state change.
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
    rawPresentationOrdinal: Int,
    onRawPresentationChange: (Int) -> Unit,
    onGraphTap: () -> Unit = {},
    modifier: Modifier = Modifier,
    graphHeight: Dp = ScopeGraphHeight,
    dataLive: Boolean = true,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val mode = HistogramMode.entries[modeOrdinal.coerceIn(0, HistogramMode.entries.lastIndex)]
    val rawPresentation = RawHistogramModeRules.fromOrdinal(rawPresentationOrdinal)

    Column(modifier) {
        // Mode row: the compact segmented control (label-only, no check
        // icon — the stock one truncated "Parade"/"RAW" on narrow
        // phones) plus, in RAW only, the parade toggle chip as a peer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke(this)
            CompactSegmented(
                options = HistogramMode.entries.map { it.label },
                selectedIndex = mode.ordinal,
                onSelect = { onModeChange(HistogramMode.entries[it].ordinal) },
                modifier = Modifier.weight(1f),
            )
            if (mode == HistogramMode.RAW) {
                val chipDescription = stringResource(
                    if (rawPresentation == RawHistogramPresentation.PARADE) {
                        R.string.editor_histogram_raw_parade_hide
                    } else {
                        R.string.editor_histogram_raw_parade_show
                    }
                )
                Spacer(Modifier.width(6.dp))
                ScopeToggleChip(
                    label = stringResource(R.string.editor_histogram_raw_parade),
                    selected = rawPresentation == RawHistogramPresentation.PARADE,
                    onClick = {
                        onRawPresentationChange(
                            RawHistogramModeRules.onToggle(rawPresentation).ordinal
                        )
                    },
                    description = chipDescription,
                )
            }
            trailing?.invoke(this)
        }
        Spacer(Modifier.height(4.dp))
        HistogramGraph(
            result = result,
            raw = raw,
            mode = mode,
            rawPresentation = rawPresentation,
            onGraphTap = onGraphTap,
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight),
            dataLive = dataLive,
        )
    }
}

/**
 * THE HISTOGRAM GRAPH ITSELF — the one draw path, shared by the strip and
 * the MINI overlay (owner request 2026-11-12 §2) so both draw the exact
 * same picture from the exact same inputs ([HistogramDisplay]'s smoothed,
 * normalized curves). [onGraphTap] non-null = the strip's tap-to-shrink;
 * the mini passes null (its container is the tap target).
 *
 * LOOK (2026-11-23): a dark rounded [ScopePanel] with a soft quarter-stop
 * graticule; channel fills are composited ADDITIVELY (BlendMode.Plus in
 * an offscreen layer) so overlapping R+G+B converge to white like a real
 * light-mixing scope, each with a crisp outline; the luma rides on top
 * as a light outline. Clipping reads as an error-colored edge bar on the
 * clipped end plus a corner pill with the clipped channels and measured
 * share ([ScopeClipReadout] — the pipeline's own counts). The RAW mode's
 * notices stay in its own domain (see the KDoc above).
 *
 * ALLOCATION: the curve paths are remembered and rewound per draw; the
 * display arrays are remembered per published result.
 */
@Composable
internal fun HistogramGraph(
    result: FrameHistogram.Result,
    raw: RawHistogram?,
    mode: HistogramMode,
    rawPresentation: RawHistogramPresentation,
    onGraphTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    dataLive: Boolean = true,
) {
    val colors = rememberScopeColors(mini)
    val display = remember(result, mode) {
        HistogramDisplay.compute(result, mode)
    }
    val rawDisplay = remember(raw, mode, rawPresentation) {
        when {
            mode != HistogramMode.RAW -> null
            rawPresentation == RawHistogramPresentation.PARADE ->
                HistogramDisplay.computeRawParade(raw)
            else -> HistogramDisplay.computeRaw(raw)
        }
    }
    val readout = remember(result, raw, mode) {
        if (mode == HistogramMode.RAW) ScopeClipReadout.ofRaw(raw) else ScopeClipReadout.of(result, mode)
    }
    val clipTint = if (mode == HistogramMode.RAW) ScopeLook.RawClip else colors.clip
    val clipBar = if (mini) 1.dp else 1.5.dp
    val paths = remember { CurvePaths() }
    val tapModifier = if (onGraphTap != null) {
        Modifier.pointerInput(Unit) { detectTapGestures { onGraphTap() } }
    } else {
        Modifier
    }
    ScopePanel(colors = colors, mini = mini, modifier = modifier.then(tapModifier)) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen: the additive channel blend composites inside
                // the graph's own layer, never into the pixels behind it.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            val stroke = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (!dataLive) {
                // No real frame behind the scope (see ScopeStrip's
                // statsLive): the graticule only — never a curve drawn
                // from the 1x1 placeholder pixel.
                drawTonalGraticule(colors)
                return@Canvas
            }
            val parade = mode == HistogramMode.PARADE ||
                (mode == HistogramMode.RAW && rawPresentation == RawHistogramPresentation.PARADE)
            if (parade) {
                val cellW = w / 3f
                for (i in 0 until 3) {
                    val left = i * cellW
                    drawTonalGraticule(colors, left, cellW)
                    if (i > 0) {
                        drawLine(colors.gridStrong, Offset(left, 0f), Offset(left, h), strokeWidth = 1f)
                    }
                    val values = if (mode == HistogramMode.RAW) {
                        rawDisplay?.get(RawHistogram.Channel.entries[i])
                    } else {
                        display[FrameHistogram.Channel.entries[i]]
                    }
                    if (values != null) {
                        clipRect(left = left, right = left + cellW) {
                            drawChannel(paths, i, values, left, cellW, scopeChannelColor(i), stroke, additive = false)
                        }
                    }
                    if (i in readout.shadowChannels) drawClipEdge(clipTint, atStart = true, left, cellW, clipBar.toPx())
                    if (i in readout.highlightChannels) drawClipEdge(clipTint, atStart = false, left, cellW, clipBar.toPx())
                }
            } else {
                drawTonalGraticule(colors)
                when (mode) {
                    HistogramMode.RAW -> rawDisplay?.let { d ->
                        for (i in 2 downTo 0) {
                            d[RawHistogram.Channel.entries[i]]?.let {
                                drawChannel(paths, i, it, 0f, w, scopeChannelColor(i), stroke, additive = true)
                            }
                        }
                    }
                    HistogramMode.RGB -> {
                        for (i in 2 downTo 0) {
                            display[FrameHistogram.Channel.entries[i]]?.let {
                                drawChannel(paths, i, it, 0f, w, scopeChannelColor(i), stroke, additive = true)
                            }
                        }
                        display[FrameHistogram.Channel.LUMA]?.let {
                            buildCurvePaths(it, 0f, w, h, paths.fill[3].also { p -> p.rewind() }, paths.outline[3].also { p -> p.rewind() })
                            drawPath(paths.outline[3], ScopeLook.Luma.copy(alpha = 0.75f), style = stroke)
                        }
                    }
                    else -> display[FrameHistogram.Channel.LUMA]?.let {
                        drawChannel(paths, 3, it, 0f, w, ScopeLook.Luma, stroke, additive = false)
                    }
                }
                if (readout.shadowPercent != null) drawClipEdge(clipTint, atStart = true, barWidth = clipBar.toPx())
                if (readout.highlightPercent != null) drawClipEdge(clipTint, atStart = false, barWidth = clipBar.toPx())
            }
        }
        if (!dataLive) {
            if (!mini) NoStatsHint(colors, Modifier.align(Alignment.Center))
            return@ScopePanel
        }
        if (mode == HistogramMode.RAW && raw == null && !mini) {
            ScopeCaption(
                stringResource(R.string.editor_histogram_raw_unavailable),
                colors,
                Modifier.align(Alignment.Center),
            )
        }
        if (!mini) {
            ClipPills(
                readout = readout,
                colors = colors,
                description = stringResource(
                    if (mode == HistogramMode.RAW) R.string.editor_histogram_raw_clip
                    else R.string.editor_histogram_clipped
                ),
                tint = clipTint,
            )
        }
    }
}

/** Reusable curve paths: index 0-2 = R/G/B, 3 = luma. Rewound per draw. */
internal class CurvePaths {
    val fill = Array(4) { Path() }
    val outline = Array(4) { Path() }
}

/**
 * One channel: a translucent fill (additively blended in combined views
 * so overlaps mix toward white) under a crisp outline in the same color.
 */
private fun DrawScope.drawChannel(
    paths: CurvePaths,
    slot: Int,
    values: FloatArray,
    left: Float,
    width: Float,
    color: Color,
    stroke: Stroke,
    additive: Boolean,
) {
    val fill = paths.fill[slot].also { it.rewind() }
    val outline = paths.outline[slot].also { it.rewind() }
    buildCurvePaths(values, left, width, size.height, fill, outline)
    drawPath(
        fill,
        color.copy(alpha = if (additive) 0.42f else 0.30f),
        blendMode = if (additive) BlendMode.Plus else BlendMode.SrcOver,
    )
    drawPath(outline, color, style = stroke)
}

/**
 * Fills [fill] (closed to the baseline) and [outline] (the open curve)
 * for one channel's normalized values across [left]..[left]+[width].
 */
private fun buildCurvePaths(
    values: FloatArray,
    left: Float,
    width: Float,
    height: Float,
    fill: Path,
    outline: Path,
) {
    if (values.isEmpty()) return
    val step = width / (values.size - 1).coerceAtLeast(1)
    // A hair of headroom so a full-scale peak's stroke is not clipped.
    val top = 1.5f
    val span = height - top
    fill.moveTo(left, height)
    for (i in values.indices) {
        val x = left + i * step
        val y = top + span * (1f - values[i])
        if (i == 0) outline.moveTo(x, y) else outline.lineTo(x, y)
        fill.lineTo(x, y)
    }
    fill.lineTo(left + width, height)
    fill.close()
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

