package com.ritim.ritimcraw.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.RawHistogram
import com.ritim.ritimcraw.ui.components.CompactSegmented

/**
 * THE SCOPE STRIP (owner spec PLAY-10, 2026-11-12 §2 + the manager's
 * design decision): the scope area sits BELOW the preview and transport,
 * ABOVE the settings panel — a strip, never an overlay on the image. It
 * carries ONE content selector, Histogram | Waveform (the spec's "user
 * picks Histogram or Waveform in ONE scope area"); the corner scope
 * toggle opens this strip. Closing the strip returns its space: the
 * strip is simply not composed, so the panel anchors re-measure to the
 * taller free region (the screen subtracts the strip's measured height
 * only while it is open).
 *
 * The strip composes in BOTH layouts: in the portrait single column
 * between the transport and the draggable panel; in the landscape
 * two-column workspace inside the PREVIEW column (under the transport)
 * — the scope belongs with the preview it describes, and the panel
 * column keeps its full height. Never in fullscreen (the immersive
 * preview owns the screen; the strip returns with the workspace).
 *
 * CONTENT. Histogram: the corner overlay's histogram UI moved INTO the
 * strip (the manager's decision — the feature is preserved: modes,
 * smoothing, clip points; see [HistogramScopeView]). Waveform: the real
 * density trace ([WaveformScopeView]) — Luma / Overlay / Parade (spec
 * §1), the "Preview · BT.709" signal label (spec §4) over the 0-100%
 * SDR scale (spec §5).
 *
 * HEIGHT STABILITY: both contents lay out the same rows — mode
 * selector and a [ScopeGraphHeight] graph panel — so the strip's height
 * never changes on a content switch or a clipping-state change, and the
 * panel anchors below never churn mid-session. Every readout (the
 * waveform's signal label + Hz, the histogram's clip pills, the RAW
 * notice) lives INSIDE the graph panel's corners instead of adding height.
 *
 * LOOK (2026-11-23 modernization): compact label-only segmented controls
 * ([CompactSegmented]), small icon buttons, and a dark rounded graph
 * panel with a soft graticule ([ScopeLook]) — one instrument language
 * across histogram, waveform and the mini overlay.
 *
 * STATE: the open flag, the content choice, the waveform mode AND the
 * histogram mode live in the SCREEN (saveable there because the strip
 * leaves composition when closed — screen-level state keeps the
 * user's choices across close/open); the histogram's mode moved up
 * with feature N6 (the waveform's precedent: the RAW mode's
 * sensor-domain stats are fetched by the screen's scope compute keyed
 * to the mode, so the choice must live where the compute is keyed).
 * First open shows Waveform · Luma (spec §1: "ilk açılışta Luma
 * seçilsin").
 *
 * REFERENCES (spec §8): the mid-gray guide line is TOGGLEABLE from the
 * header and drawn only while the derivation is honest — [midGraySignal]
 * is valid only when the analyzed signal's luma path is the bare Rec.709
 * OETF, so the screen passes [midGrayAvailable] false (and the toggle
 * disappears) whenever a tonemap curve or a LUT rewrites the transform;
 * no fabricated fixed line ever draws over a signal it does not
 * describe. The contrast-pivot reference has no reliable mapping (its
 * application lives in the prebuilt native engine, invisible source) —
 * per the spec it is kept absent and the gap recorded in the mission
 * report, not drawn as an invented constant.
 */

/**
 * The strip's content selector (spec §1: ONE scope area, Histogram or
 * Waveform). [labelRes] is the selector's own label.
 */
enum class ScopeContent(val labelRes: Int) {
    HISTOGRAM(R.string.editor_histogram),
    WAVEFORM(R.string.editor_scope_waveform),
}

/** The scope graph's height in both contents (see HEIGHT STABILITY). */
internal val ScopeGraphHeight = 148.dp

/**
 * The graph height in the landscape two-column workspace: the strip sits
 * inside the preview column there, and the full 148dp graph plus two
 * control rows used to push the preview itself off screen.
 */
internal val ScopeGraphHeightCompact = 72.dp

/**
 * The strip itself: header (content selector + close) + the selected
 * content. [histogram] is the screen's per-frame histogram result and
 * [waveform] its pre-rendered waveform pixels — both computed off the UI
 * thread from the CURRENT published frame by the screen's scope compute
 * effect (spec §7: closed strip = the compute stops entirely), null/empty
 * until the first frame publishes. [rawHistogram] is the RAW mode's
 * PRE-DEVELOP sensor-domain stats (feature N6) — null when unavailable,
 * which the histogram content shows as an honest notice, never a
 * fabricated curve. The sampling params carry the waveform pass's quality
 * choice (spec §6) and [hz] its measured compute rate (spec §7). The
 * mid-gray params carry the spec §8 reference (toggle only while the
 * derivation is honest).
 *
 * [compact] (landscape two-column): ONE control row — content selector,
 * mode selector, chip and the icon buttons side by side — over a
 * [ScopeGraphHeightCompact] graph, so the preview keeps most of its column.
 */
@Composable
fun ScopeStrip(
    content: ScopeContent,
    onContentChange: (ScopeContent) -> Unit,
    onClose: () -> Unit,
    histogram: FrameHistogram.Result,
    rawHistogram: RawHistogram?,
    histogramModeOrdinal: Int,
    onHistogramModeChange: (Int) -> Unit,
    // The RAW mode's presentation (owner answer #6): COMBINED or
    // PARADE — screen saveable state exactly like the mode ordinal,
    // display-only (both shapes draw the SAME fetched stats).
    rawPresentationOrdinal: Int,
    onRawPresentationChange: (Int) -> Unit,
    waveform: WaveformDisplay.Rendered?,
    waveformModeOrdinal: Int,
    onWaveformModeChange: (Int) -> Unit,
    samplingOrdinal: Int,
    onSamplingChange: (Int) -> Unit,
    hz: Int,
    midGrayAvailable: Boolean,
    midGrayShown: Boolean,
    onMidGrayChange: (Boolean) -> Unit,
    // Tapping the graph itself shrinks the strip to the mini overlay
    // (owner request 2026-11-12 §2) — only the graph node fires it.
    onGraphTap: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val contentLabels = ScopeContent.entries.map { stringResource(it.labelRes) }
    val contentSelector: @Composable RowScope.(Float) -> Unit = { weight ->
        CompactSegmented(
            options = contentLabels,
            selectedIndex = content.ordinal,
            onSelect = { onContentChange(ScopeContent.entries[it]) },
            modifier = Modifier.weight(weight),
        )
    }
    // The header's icon buttons: the mid-gray reference toggle (spec §8,
    // present only while the derivation is honest) and close. Visual 20dp
    // glyphs, 40dp targets inside the 44dp control row.
    val headerActions: @Composable RowScope.() -> Unit = {
        if (midGrayAvailable) {
            ScopeIconButton(
                iconRes = R.drawable.ic_scope_midgray,
                description = stringResource(
                    if (midGrayShown) R.string.editor_scope_midgray_hide
                    else R.string.editor_scope_midgray_show
                ),
                active = midGrayShown,
                onClick = { onMidGrayChange(!midGrayShown) },
            )
        }
        ScopeIconButton(
            iconRes = R.drawable.ic_scope_close,
            description = stringResource(R.string.editor_scope_hide),
            onClick = onClose,
        )
    }
    val graphHeight = if (compact) ScopeGraphHeightCompact else ScopeGraphHeight
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 8.dp)) {
            if (!compact) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    contentSelector(1f)
                    Spacer(Modifier.width(4.dp))
                    headerActions()
                }
            }
            val leading: (@Composable RowScope.() -> Unit)? =
                if (compact) ({ contentSelector(0.8f); Spacer(Modifier.width(6.dp)) }) else null
            val trailing: (@Composable RowScope.() -> Unit)? = if (compact) headerActions else null
            Column(Modifier.padding(end = 6.dp)) {
                if (content == ScopeContent.WAVEFORM) {
                    WaveformScopeView(
                        rendered = waveform,
                        modeOrdinal = waveformModeOrdinal,
                        onModeChange = onWaveformModeChange,
                        samplingOrdinal = samplingOrdinal,
                        onSamplingChange = onSamplingChange,
                        hz = hz,
                        midGrayLine = if (midGrayAvailable && midGrayShown) {
                            WaveformScope.midGraySignal()
                        } else {
                            null
                        },
                        onGraphTap = onGraphTap,
                        graphHeight = graphHeight,
                        leading = leading,
                        trailing = trailing,
                    )
                } else {
                    HistogramScopeView(
                        result = histogram,
                        modeOrdinal = histogramModeOrdinal,
                        onModeChange = onHistogramModeChange,
                        raw = rawHistogram,
                        rawPresentationOrdinal = rawPresentationOrdinal,
                        onRawPresentationChange = onRawPresentationChange,
                        onGraphTap = onGraphTap,
                        modifier = Modifier.fillMaxWidth(),
                        graphHeight = graphHeight,
                        leading = leading,
                        trailing = trailing,
                    )
                }
            }
        }
    }
}

/**
 * The waveform content (spec §1-§7): the mode selector + sampling chip on
 * ONE row, then the [WaveformGraph] panel. The signal label ("Preview ·
 * BT.709", spec §4) and the measured compute rate (spec §7) ride inside
 * the panel's top-end corner, so no extra band adds height.
 * [leading]/[trailing] let the compact strip fold its header into this row.
 */
@Composable
fun WaveformScopeView(
    rendered: WaveformDisplay.Rendered?,
    modeOrdinal: Int,
    onModeChange: (Int) -> Unit,
    samplingOrdinal: Int,
    onSamplingChange: (Int) -> Unit,
    hz: Int,
    midGrayLine: Float?,
    // Only the GRAPH answers the tap-to-shrink; null in the mini.
    onGraphTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    graphHeight: Dp = ScopeGraphHeight,
    leading: (@Composable RowScope.() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    // Coerced: the ordinal crosses process-death save/restore; a stale
    // value must select the nearest real mode, never crash the strip.
    val mode = WaveformMode.entries[modeOrdinal.coerceIn(0, WaveformMode.entries.lastIndex)]
    val caption = stringResource(R.string.editor_scope_signal) + "  ·  " +
        stringResource(R.string.editor_scope_hz, hz)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke(this)
            // Literal untranslated mode codes in every locale (the owner
            // spec's product vocabulary).
            CompactSegmented(
                options = WaveformMode.entries.map { it.label },
                selectedIndex = mode.ordinal,
                onSelect = { onModeChange(WaveformMode.entries[it].ordinal) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            ScopeSamplingChip(
                samplingOrdinal = samplingOrdinal,
                onSamplingChange = onSamplingChange,
            )
            trailing?.invoke(this)
        }
        Spacer(Modifier.height(4.dp))
        WaveformGraph(
            rendered = rendered,
            midGrayLine = midGrayLine,
            onGraphTap = onGraphTap,
            caption = caption,
            parade = mode == WaveformMode.PARADE,
            modifier = Modifier
                .fillMaxWidth()
                .height(graphHeight)
                .semantics { contentDescription = mode.label },
        )
    }
}

/**
 * THE WAVEFORM GRAPH — the one draw path, shared by the strip and the
 * mini overlay. A dark rounded [ScopePanel]; a soft 0/25/50/75/100%
 * graticule with sparse labels (strip only); the mid-gray reference as a
 * dashed amber guide at the DERIVED level (spec §8, 18% scene linear
 * through the Rec.709 OETF ≈ 41% coded signal); then the density bitmap
 * stretched over the panel (X = image position, Y = the 256 levels).
 *
 * The bitmap pixels are PREMULTIPLIED ARGB (WaveformDisplay's contract) —
 * exactly what Bitmap.createBitmap's colors array expects. [caption]
 * (strip only) draws in the top-end corner.
 */
@Composable
internal fun WaveformGraph(
    rendered: WaveformDisplay.Rendered?,
    midGrayLine: Float?,
    onGraphTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
    caption: String? = null,
    mini: Boolean = false,
    parade: Boolean = false,
) {
    val colors = rememberScopeColors(mini)
    val labels = if (mini) null else rememberGraticuleLabels()
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 7f)) }
    val bitmap = remember(rendered) {
        rendered?.let {
            Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
    }
    val tapModifier = if (onGraphTap != null) {
        Modifier.pointerInput(Unit) { detectTapGestures { onGraphTap() } }
    } else {
        Modifier
    }
    ScopePanel(colors = colors, mini = mini, modifier = modifier.then(tapModifier)) {
        Canvas(
            Modifier
                .fillMaxSize()
                // Offscreen: the additive trace composites inside the
                // graph's own layer, never into the footage behind the mini.
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen),
        ) {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas
            drawLevelGraticule(colors)
            if (parade) {
                // Cell separators: the three channel cells read as three graphs.
                for (k in 1..2) {
                    val x = w * k / 3f
                    drawLine(colors.gridStrong, Offset(x, 0f), Offset(x, h), strokeWidth = 1f)
                }
            }
            midGrayLine?.let { level ->
                val y = h * (1f - level)
                drawLine(
                    color = colors.reference,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = dash,
                )
            }
            val b = bitmap
            if (b != null) {
                drawImage(
                    image = b,
                    srcSize = IntSize(b.width, b.height),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                    blendMode = BlendMode.Plus,
                )
            }
            // Labels last so the trace never hides the scale.
            if (labels != null) drawLevelGraticuleLabels(colors, labels)
        }
        if (caption != null) {
            ScopeCaption(caption, colors, Modifier.align(Alignment.TopEnd))
        }
    }
}

/**
 * The sampling chip (spec §6): a peer of the mode selector that cycles
 * Low -> Medium -> High; the description names the current value.
 */
@Composable
private fun ScopeSamplingChip(
    samplingOrdinal: Int,
    onSamplingChange: (Int) -> Unit,
) {
    val entries = WaveformScope.Sampling.entries
    val sampling = entries[samplingOrdinal.coerceIn(0, entries.lastIndex)]
    val label = stringResource(
        when (sampling) {
            WaveformScope.Sampling.LOW -> R.string.editor_scope_sampling_low
            WaveformScope.Sampling.MEDIUM -> R.string.editor_scope_sampling_medium
            WaveformScope.Sampling.HIGH -> R.string.editor_scope_sampling_high
        }
    )
    ScopeToggleChip(
        label = label,
        selected = false,
        onClick = { onSamplingChange(entries[(sampling.ordinal + 1) % entries.size].ordinal) },
        description = stringResource(R.string.editor_scope_sampling_value, label),
    )
}

/**
 * A compact outlined chip for the scope's mode-row peers (sampling, RAW
 * parade): 30dp visual pill inside a 44dp touch row, filled when
 * [selected].
 */
@Composable
internal fun ScopeToggleChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    description: String,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .height(44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier
                .clip(shape)
                .background(if (selected) cs.secondaryContainer else Color.Transparent)
                .border(1.dp, cs.outlineVariant, shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** A 40dp icon button with a 20dp glyph, tinted brighter when [active]. */
@Composable
private fun ScopeIconButton(
    iconRes: Int,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The scope toggle: a stock M3 IconButton (48dp target, DESIGN.md
 * Inputs) with the vendored waveform-trace glyph, placed by the screen
 * at the preview's TopStart corner — the mirror of the fullscreen TopEnd
 * toggle, so the preview's corner icon buttons read as one group. The
 * button opens/closes the SCOPE STRIP below the transport (the manager's
 * decision: the histogram's old corner overlay moved INTO the strip, so
 * the glyph is the scope's own trace, not the histogram's bars).
 *
 * Scrim behind the glyph (owner finding 2026-11-11): over overexposed
 * near-white footage the white icon glyphs of ALL preview corner
 * controls vanished into the frame. The toggle carries the same
 * translucent surfaceContainerLowest scrim (roles, never hex —
 * DESIGN.md) at the theme's small shape as the fullscreen and refresh
 * controls. The Surface wraps, not replaces, the IconButton — the 48dp
 * touch target and the click path are unchanged.
 */
@Composable
fun ScopeToggleButton(
    visible: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        if (visible) R.string.editor_scope_hide else R.string.editor_scope_show
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
                painter = painterResource(R.drawable.ic_scope),
                contentDescription = null,
            )
        }
    }
}

/**
 * THE MINI SCOPE OVERLAY (owner request 2026-11-12 §2): the strip
 * shrunk to a small display-only box pinned INSIDE the preview's
 * BottomStart corner. Tapping the graph in the open strip shrinks it
 * here (the screen's ScopePresentationRules.onGraphTap); tapping the
 * mini expands the full strip again (onMiniTap); the corner toggle
 * hides it entirely (onCornerToggle — the third way in/out).
 *
 * WHAT IT SHOWS: whatever scope/content/mode the user last selected —
 * the SAME screen state the strip reads, the SAME computed results
 * (histogram: FrameHistogram.Result; waveform: WaveformDisplay.
 * Rendered) through the SAME graph composables the strip uses
 * ([HistogramGraph]/[WaveformGraph]). Display-only by construction:
 * there is no second compute path, no mode rows, no sampling chip, no
 * Hz readout — the mini never edits anything, it only draws.
 *
 * PLACEMENT (documented choice): BottomStart inside the preview Box —
 * the only corner no other control owns (TopStart = the scope toggle,
 * TopEnd = fullscreen, BottomEnd = refresh, CenterEnd = the landscape
 * panel-reopen chip). It rides the corner-control gate the screen
 * gives it (hidden with the fullscreen chrome), sits INSIDE the
 * preview like the other corner controls (scrimmed, never a layout
 * row), and consumes NOTHING but its own bounds: a small Box with a
 * clickable, not a full-surface gesture handler — preview gestures
 * (pinch-zoom, pan, the hold-to-compare) flow around it, and its tap
 * is the only gesture it answers.
 *
 * CHROME: the shared scope panel in its mini variant (translucent black
 * with a hairline light border, 10dp corners — [ScopeLook.MiniShape]),
 * so the graph reads over any footage. The description reuses the strip's own "Show scope"
 * string: the mini's tap EXPANDS the scope (the same intent the
 * corner button's icon announces from OFF).
 *
 * SIZE: 136x80dp — wide enough for the trace's shape to read (the
 * waveform keeps its horizontal structure, the histogram its
 * curves), small enough to leave the image unobstructed. All drawing
 * inside is resolution-relative (the shared graphs), so the same code
 * that draws the 148dp strip graph draws this box.
 *
 * HONESTY IN THE RAW MODE: the histogram's RAW mode with no sensor
 * data draws an honest EMPTY grid here (the shared graph draws no
 * fabricated curve) — the strip's "RAW: no sensor data" notice lives
 * in its label band, which the mini has none of; the empty grid is
 * the mini's silent equivalent, and the tap expands to the full
 * notice.
 */
@Composable
fun ScopeMiniOverlay(
    content: ScopeContent,
    histogram: FrameHistogram.Result,
    rawHistogram: RawHistogram?,
    histogramModeOrdinal: Int,
    // The RAW presentation rides along (owner answer #6): the mini
    // shows the SAME shape the strip shows — same screen state, the
    // shared graph draws it; display-only by construction.
    rawPresentationOrdinal: Int,
    waveform: WaveformDisplay.Rendered?,
    waveformModeOrdinal: Int,
    midGrayAvailable: Boolean,
    midGrayShown: Boolean,
    onMiniTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.editor_scope_show)
    // The honesty gate is derived by the screen and passed here as
    // midGrayAvailable; the derived level is the same pure function
    // the strip uses.
    val midGrayLine = if (midGrayAvailable && midGrayShown) {
        WaveformScope.midGraySignal()
    } else {
        null
    }
    Box(
        modifier
            .size(width = 136.dp, height = 80.dp)
            .clip(ScopeLook.MiniShape)
            .clickable(role = Role.Image, onClick = onMiniTap)
            .semantics { contentDescription = description },
    ) {
        if (content == ScopeContent.WAVEFORM) {
            WaveformGraph(
                rendered = waveform,
                midGrayLine = midGrayLine,
                onGraphTap = null,
                modifier = Modifier.fillMaxSize(),
                mini = true,
                parade = waveformModeOrdinal == WaveformMode.PARADE.ordinal,
            )
        } else {
            HistogramGraph(
                result = histogram,
                raw = rawHistogram,
                mode = HistogramMode.entries[
                    histogramModeOrdinal.coerceIn(0, HistogramMode.entries.lastIndex)
                ],
                rawPresentation = RawHistogramModeRules.fromOrdinal(rawPresentationOrdinal),
                onGraphTap = null,
                modifier = Modifier.fillMaxSize(),
                mini = true,
            )
        }
    }
}
