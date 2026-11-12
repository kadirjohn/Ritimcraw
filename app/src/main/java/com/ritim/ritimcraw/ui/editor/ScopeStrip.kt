package com.ritim.ritimcraw.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.RawHistogram
import com.ritim.ritimcraw.ui.theme.Tnum

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
 * selector, [ScopeGraphHeight] graph, [ScopeLabelBandHeight] label
 * band — so the strip's height never changes on a content switch or a
 * clipping-state change, and the panel anchors below never churn
 * mid-session. The label band is FIXED: the waveform's signal label and
 * the histogram's "Clipped" badge appear inside the reserved band
 * instead of adding height.
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

/** The fixed label band under the graph (see HEIGHT STABILITY). */
internal val ScopeLabelBandHeight = 26.dp

/**
 * The strip itself: header (content selector + close) + the selected
 * content. [histogram] is the screen's per-frame histogram result and
 * [waveform] its pre-rendered waveform pixels — both computed off the UI
 * thread from the CURRENT published frame by the screen's scope compute
 * effect (spec §7: closed strip = the compute stops entirely), null/empty
 * until the first frame publishes. [rawHistogram] is the RAW mode's
 * PRE-DEVELOP sensor-domain stats (feature N6) — fetched by the same
 * compute effect through the session seam when the histogram mode is
 * RAW; null when unavailable (an encoded clip has no sensor data, or
 * the fetch failed), which the histogram content shows as an honest
 * notice, never a fabricated curve. The histogram mode params
 * ([histogramModeOrdinal]/[onHistogramModeChange]) carry the mode the
 * same way the waveform's do — screen state, keyed where the compute
 * is keyed. The sampling params carry the waveform pass's quality
 * choice (spec §6) and [hz] its measured compute rate (spec §7 pacing
 * validation). [midGrayAvailable]/[midGrayShown]/[onMidGrayChange]
 * carry the spec §8 reference: the toggle shows only while the
 * derivation is honest (the screen's gate), and the line draws only
 * when the user has it on.
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
    // display-only (both shapes draw the SAME fetched stats; the
    // compute effect does not key on it).
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
    // (owner request 2026-11-12 §2): threaded to whichever content view
    // is showing, where it rides the graph's own node — the header,
    // mode rows and label bands never fire it.
    onGraphTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)) {
            // Header: the ONE content selector (Histogram | Waveform) +
            // the close button. The close button and the corner toggle
            // perform the same action (close the strip) and share the
            // same description string.
            Row(verticalAlignment = Alignment.CenterVertically) {
                SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                    ScopeContent.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = content == entry,
                            onClick = { onContentChange(entry) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = ScopeContent.entries.size,
                            ),
                        ) {
                            Text(stringResource(entry.labelRes), maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // The mid-gray reference toggle (spec §8): present only
                // while the derivation is honest (the screen's gate —
                // a tonemap or LUT rewrites the transform and the line
                // would lie). The icon is a value axis with a dashed
                // level line; the description names show/hide. Same
                // IconButton pattern as the close button.
                if (midGrayAvailable) {
                    IconButton(onClick = { onMidGrayChange(!midGrayShown) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_scope_midgray),
                            contentDescription = stringResource(
                                if (midGrayShown) R.string.editor_scope_midgray_hide
                                else R.string.editor_scope_midgray_show
                            ),
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(
                        painter = painterResource(R.drawable.ic_scope_close),
                        contentDescription = stringResource(R.string.editor_scope_hide),
                    )
                }
            }
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
                )
            }
        }
    }
}

/**
 * THE WAVEFORM VIEW (spec §3): the real density trace. The graph is the
 * [WaveformDisplay.Rendered] bitmap — pre-rendered off the UI thread by
 * the screen (density grids -> premultiplied ARGB) — scaled to the
 * canvas with ONE drawImage call: no per-cell canvas work, no
 * computation on the UI thread. The bitmap's transparent cells let the
 * 0/50/100% signal-scale reference lines (spec §5) show through under
 * the trace; the Y axis rises upward inside the bitmap already
 * (WaveformDisplay's contract), so the canvas maps it 1:1 — the top
 * line is 100%, the middle 50%, the bottom 0%.
 *
 * The mode selector (Luma / Overlay / Parade, spec §1) is the screen's
 * saveable state passed down — the screen's compute effect needs the
 * mode to know which grids to sample, so the choice must live where the
 * compute is keyed. The same holds for the sampling quality chip (spec
 * §6: [ScopeSamplingChip]) and the Hz readout (spec §7's measurement
 * tooling, in the fixed label band in Tnum so the ticking digits do not
 * jog). [midGrayLine] is the spec §8 reference's DERIVED signal level
 * (or null = not drawn): a dashed guide line under the trace, valid
 * only when the analyzed signal's luma path is the bare Rec.709 OETF
 * (the strip's gate — see the ScopeStrip KDoc).
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
    // Tapping the graph itself shrinks the strip to the mini overlay
    // (owner request 2026-11-12 §2): only the GRAPH area answers — the
    // mode row above stays mode-switching, the label band stays
    // informational. Nullable so the mini (whose whole container is the
    // tap target) draws the graph with no tap node of its own.
    onGraphTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // Coerced: the ordinal crosses process-death save/restore; a stale
    // value must select the nearest real mode, never crash the strip.
    val mode = WaveformMode.entries[modeOrdinal.coerceIn(0, WaveformMode.entries.lastIndex)]
    val signalLabel = stringResource(R.string.editor_scope_signal)
    Column(modifier) {
        // The mode selector and the sampling chip share ONE row (spec §1
        // + §6): the chip is a peer of the mode buttons, not an extra
        // row — the strip's height never changes when it appears (see
        // HEIGHT STABILITY).
        Row(verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                WaveformMode.entries.forEachIndexed { index, m ->
                    SegmentedButton(
                        selected = m == mode,
                        onClick = { onModeChange(m.ordinal) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = WaveformMode.entries.size,
                        ),
                    ) {
                        // Literal untranslated mode codes in every locale
                        // (the owner spec's product vocabulary, like the
                        // histogram's RGB/Luma/Parade and ACES/AgX).
                        Text(m.label, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            ScopeSamplingChip(
                samplingOrdinal = samplingOrdinal,
                onSamplingChange = onSamplingChange,
            )
        }
        Spacer(Modifier.height(6.dp))
        // The graph itself is [WaveformGraph] — the ONE draw path,
        // shared with the mini overlay (see its KDoc).
        WaveformGraph(
            rendered = rendered,
            midGrayLine = midGrayLine,
            onGraphTap = onGraphTap,
            modifier = Modifier
                .fillMaxWidth()
                .height(ScopeGraphHeight)
                .semantics { contentDescription = mode.label },
        )
        // The fixed label band (see HEIGHT STABILITY): the honest
        // signal label (spec §4: "Analiz alanını 'Preview · BT.709'
        // gibi doğru adlandır" — the analyzed pixels are the processed
        // preview frame, post-grade, post-Rec.709-OETF, pre-UI) at the
        // start, the measured compute rate (spec §7's pacing
        // validation) at the end — Tnum so the ticking digits do not
        // jog. Both fit the reserved 26dp band; no height ever changes.
        Row(
            Modifier.fillMaxWidth().height(ScopeLabelBandHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                signalLabel,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.editor_scope_hz, hz),
                style = MaterialTheme.typography.labelSmall.merge(Tnum),
            )
        }
    }
}

/**
 * THE WAVEFORM GRAPH ITSELF — the one draw path, extracted (owner
 * request 2026-11-12 §2) so the strip's view and the strip's MINI
 * overlay draw the exact same picture from the exact same
 * [WaveformDisplay.Rendered] pixels: the reference lines, the optional
 * mid-gray guide, and ONE scaled drawImage. The mini is display-only
 * BY CONSTRUCTION: it composes this, it does not re-derive anything.
 *
 * [onGraphTap]: the strip's graph-tap shrink gesture (tap the graph ->
 * the strip collapses to the mini). The mini passes NULL — its whole
 * container is the tap target (tap anywhere -> the strip expands), and
 * a second tap node inside it would compete with the container's own
 * clickable.
 *
 * The graph fills the modifier's size — [ScopeGraphHeight] at full
 * width in the strip; a smaller box in the mini. All drawing is
 * resolution-relative, so the same code draws both sizes.
 */
@Composable
internal fun WaveformGraph(
    rendered: WaveformDisplay.Rendered?,
    midGrayLine: Float?,
    onGraphTap: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    // The mid-gray guide reads as a REFERENCE, not grid furniture: the
    // theme's primary, distinct from the neutral grid lines and from
    // the white trace.
    val referenceColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    // The density bitmap, wrapped once per published frame. The pixels
    // are PREMULTIPLIED ARGB (WaveformDisplay's contract) — exactly
    // what Bitmap.createBitmap's colors array is interpreted as, and
    // what android bitmaps store internally; writing straight-alpha
    // ints here would render with color bleeding.
    val bitmap = remember(rendered) {
        rendered?.let {
            Bitmap.createBitmap(it.pixels, it.width, it.height, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
    }
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
        val w = size.width
        val h = size.height
        if (w > 0f && h > 0f) {
            // The 0-100% signal scale's reference lines (spec §5):
            // 100% at the top edge, 50% at the middle, 0% at the
            // bottom — drawn at half-pixel offsets so 1px lines
            // land crisp, under the trace's transparent cells.
            drawLine(gridColor, Offset(0f, 0.5f), Offset(w, 0.5f), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, h - 0.5f), Offset(w, h - 0.5f), strokeWidth = 1f)
            // The mid-gray reference (spec §8): a DASHED guide at
            // the DERIVED level (18% scene linear through the
            // Rec.709 OETF ≈ 41% coded signal — never 18% screen
            // level), drawn under the trace so the density reads
            // through it. Y maps like the trace: signal rises
            // upward, level 0 at the bottom.
            midGrayLine?.let { level ->
                val y = h * (1f - level)
                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
                drawLine(
                    color = referenceColor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                    pathEffect = dash,
                )
            }
            val b = bitmap
            if (b != null) {
                // One scaled draw of the full bitmap: X stretches
                // across the strip (horizontal image position, spec
                // §3), Y maps the 256 levels onto the full height.
                drawImage(
                    image = b,
                    srcSize = IntSize(b.width, b.height),
                    dstSize = IntSize(w.toInt(), h.toInt()),
                )
            }
        }
    }
}

/**
 * The sampling quality chip (spec §6): cycles Low / Medium / High on
 * tap — the scope's OWN read resolution, never the source/preview/
 * export bit depth. The label shows the CURRENT tier; the description
 * names the setting for accessibility. The ordinal is the screen's
 * saveable state keyed into the compute effect, so a change while
 * paused re-renders the current frame at the new quality immediately.
 * Inside the strip the default chip container is fine — no corner
 * scrim needed, the strip is its own surface, not the image.
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
    // Resolved OUTSIDE the semantics block: the lambda is not composable.
    val description = stringResource(R.string.editor_scope_sampling_value, label)
    AssistChip(
        onClick = {
            onSamplingChange(entries[(sampling.ordinal + 1) % entries.size].ordinal)
        },
        label = { Text(label, maxLines = 1) },
        modifier = Modifier.semantics { contentDescription = description },
    )
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
 * CHROME: the same translucent scrim as every preview corner control
 * (surfaceContainerLowest at 60%, roles never hex — the neutral
 * editor theme) at the theme's small shape, so the graph reads over
 * any footage. The description reuses the strip's own "Show scope"
 * string: the mini's tap EXPANDS the scope (the same intent the
 * corner button's icon announces from OFF).
 *
 * SIZE: ~120x70dp — wide enough for the trace's shape to read (the
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
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = modifier
            .size(width = 120.dp, height = 70.dp)
            .clickable(role = Role.Image, onClick = onMiniTap)
            .semantics { contentDescription = description },
    ) {
        if (content == ScopeContent.WAVEFORM) {
            WaveformGraph(
                rendered = waveform,
                midGrayLine = midGrayLine,
                onGraphTap = null,
                modifier = Modifier.fillMaxSize().padding(2.dp),
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
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        }
    }
}
