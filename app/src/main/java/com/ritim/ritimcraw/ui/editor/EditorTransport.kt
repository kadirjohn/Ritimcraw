package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.ui.theme.Tnum
import com.ritim.ritimcraw.util.Format

/**
 * Transport strip under the preview (design review 2026-11-10): a visible
 * Play/Pause button, a thin wide seek line, and the elapsed / total
 * timecode in tabular figures so the strip never reflows while playing.
 * The seek line is the same thin-track treatment as the grade sliders
 * (CompactSliderDefaults.TRACK_HEIGHT) with a plain LTR fill — the
 * transport is unipolar 0..1, so no zero tick.
 *
 * Playback logic is unchanged from the previous in-line TransportRow
 * (owner request 2026-11-10 §1): pause keeps the position, dragging
 * shows the selected frame immediately at the low drag tier, M3's Slider
 * has no onValueChangeStarted so finger-down/up come from the interaction
 * stream (a tap is a zero-length drag: Start + Stop still fire).
 *
 * Pure visual extraction from ClipEditorScreen (2026-11-10): same name,
 * same parameters, same package — the screen's call site compiles
 * unchanged.
 *
 * Precision frame scrub (owner request 2026-11-11 section 2): a
 * press-and-hold on the seek bar that survives the platform long-press
 * window enters PRECISION FRAME MODE — the same physical drag then steps
 * whole frames (~1 cm ≈ 30 frames) instead of seeking by time, and a
 * readout with the exact frame number + timecode appears above the bar
 * (the owner's words: "the player bar becomes more detailed"). The
 * gesture steals the stream from the slider (see precisionScrubGesture);
 * [onPrecisionEngage] returns whether precision mode was entered (the
 * pure decision may decline — no timeline, single frame, no render
 * path), [onPrecisionDragDp] receives the TOTAL drag in dp since the
 * gesture started, [onPrecisionRelease] ends the mode (the frame is
 * kept and settled). [precisionReadout] is the localized line to show
 * while active (null = not active). Defaults keep every other call site
 * unchanged.
 *
 * Steady timecode width (owner round 3a, 2026-11-11): the readout Text
 * is pinned to the measured width of the widest possible pair, so the
 * label and the weighted slider beside it never move while the frame
 * field ticks — font-independent (tnum-ignoring device fonts are the
 * diagnosed cause of the sway; see Format.timecodeWidthProbes).
 *
 * DEFINITIVE fix (owner round 3b, 2026-11-11): the round-3a fix pinned
 * the label's LAYOUT BOX (widthIn min) but the pair stayed ONE text run,
 * and the owner's device test isolated the residual mechanism precisely:
 * the current time's FRAMES field jitters, and the " / " separator and
 * the total jitter WITH it — a glyph's on-screen x is the cumulative
 * advance of every glyph before it, so the fast-ticking frames digits
 * (proportional on the tnum-ignoring device font) breathe the run's
 * width and drag everything after them inside the box. The fix is the
 * split readout: every DIGIT of the current timecode renders in its own
 * measured cell (widest digit advance, glyph CENTERED) with static
 * colon Texts between fields, a static " / " node, and the total in its
 * own fixed box — an unchanged glyph can never move at ANY update rate
 * because its cell's width and position depend on no other glyph's
 * advance. The cell grid synthesizes tabular figures per digit even on
 * a proportional-digit font. Adaptive field count (owner request
 * 2026-11-11b section 7): fieldsFor(totalUs) decides 2/3/4 fields ONCE
 * per clip open (the total is a clip-open constant, so the field count
 * never changes during playback), and both halves share it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransportRow(
    isPlaying: Boolean,
    enabled: Boolean,
    elapsedUs: Long,
    totalUs: Long,
    fraction: Float,
    onPlayPause: () -> Unit,
    onSeekFraction: (Float) -> Unit,
    onSeekStarted: () -> Unit = {},
    onSeekFinished: () -> Unit = {},
    fps: Float = 0f,
    precisionReadout: String? = null,
    onPrecisionEngage: () -> Boolean = { false },
    onPrecisionDragDp: (Float) -> Unit = {},
    onPrecisionRelease: () -> Unit = {},
    // The screen's measurement seam (owner request 2026-11-11d section
    // 11): the editor Column reports this row's height via onSizeChanged
    // for the panel anchor math. Last with a default so the one call site
    // is the only change and the root Row keeps its own padding.
    modifier: Modifier = Modifier,
) {
    val seekDescription = stringResource(R.string.editor_seek)
    // Steady readout, definitive split (owner round 3b): the style is
    // hoisted so the measures and the cells share one object; the cell
    // width is the MAX advance over the ten single-glyph probes
    // (0..9) — content-independent by construction: the box a digit
    // renders in depends on no other glyph, so an unchanged digit
    // keeps its exact x while the frames field ticks ~24x/s. The total
    // half's box is measured from the regime's uniform-digit probes
    // (keyed on the field count, a per-clip constant). All measured
    // ONCE per clip open (remember keyed on style, density, fields).
    val timecodeStyle = MaterialTheme.typography.labelMedium.merge(Tnum)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fields = remember(totalUs) { Format.fieldsFor(totalUs) }
    val cellWidth = remember(timecodeStyle, density) {
        val probes = Format.timecodeDigitProbes()
        with(density) {
            probes.maxOf { probe ->
                textMeasurer.measure(probe, timecodeStyle).size.width.toDp()
            }
        }
    }
    val totalBoxWidth = remember(timecodeStyle, density, fields) {
        val probes = Format.timecodeHalfProbes(fields)
        with(density) {
            probes.maxOf { probe ->
                textMeasurer.measure(probe, timecodeStyle).size.width.toDp()
            }
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onPlayPause, enabled = enabled) {
            if (isPlaying) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = stringResource(R.string.editor_pause),
                )
            } else {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.editor_play),
                )
            }
        }
        val seekInteraction = remember { MutableInteractionSource() }
        LaunchedEffect(seekInteraction) {
            seekInteraction.interactions.collect { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> onSeekStarted()
                    is DragInteraction.Stop, is DragInteraction.Cancel -> onSeekFinished()
                }
            }
        }
        val colors = SliderDefaults.colors(
            thumbColor = MaterialTheme.colorScheme.primary,
            activeTrackColor = MaterialTheme.colorScheme.primary,
            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        )
        val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        // The precision gesture rides the Slider's OWN modifier — outer to
        // its internal press/drag chains, which is what lets it steal the
        // stream after the long-press window (see precisionScrubGesture).
        // The readout column keeps the bar itself at the same height as
        // before when precision is off (no readout above it).
        Column(Modifier.weight(1f)) {
            precisionReadout?.let { PrecisionScrubReadout(it) }
            Slider(
                value = fraction,
                onValueChange = onSeekFraction,
                onValueChangeFinished = onSeekFinished,
                valueRange = 0f..1f,
                enabled = enabled,
                interactionSource = seekInteraction,
                colors = colors,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = seekDescription }
                    .precisionScrubGesture(
                        isRtl = isRtl,
                        onEngage = onPrecisionEngage,
                        onDragDp = onPrecisionDragDp,
                        onRelease = onPrecisionRelease,
                    ),
                thumb = {
                    SliderDefaults.Thumb(
                        interactionSource = seekInteraction,
                        colors = colors,
                        thumbSize = CompactSliderDefaults.THUMB_SIZE,
                    )
                },
                track = { state: SliderState ->
                    SeekTrack(state = state, colors = colors, isRtl = isRtl)
                },
            )
        }
        // The split readout (owner round 3b): per-digit cells for the
        // current half, static colon nodes, a static " / ", and the
        // total in its own fixed box. No node's width depends on any
        // other node's content, so no unchanged glyph can ever move.
        // Pinned LTR: a plain Row follows the layout direction, which
        // would place the digit cells right-to-left under an RTL locale
        // and scramble the timecode's reading order — the single Text
        // this replaces never had that failure (bidi keeps European
        // digits LTR), so the split must not regress it.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val currentFields = Format.adaptiveTimecodeFields(
                    elapsedUs, Format.nominalFps(fps), fields,
                )
                currentFields.forEachIndexed { index, field ->
                    if (index > 0) {
                        Text(
                            text = ":",
                            style = timecodeStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    field.forEach { digit ->
                        TimecodeDigitCell(
                            digit = digit.toString(),
                            cellWidth = cellWidth,
                            style = timecodeStyle,
                        )
                    }
                }
                Text(
                    text = " / ",
                    style = timecodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = Format.timecodeTotalAdaptive(totalUs, Format.nominalFps(fps)),
                    style = timecodeStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(totalBoxWidth),
                )
            }
        }
    }
}

/**
 * One digit of the current timecode in its own measured cell (owner
 * round 3b): the box is the widest single-digit advance (measured once
 * per clip open from Format.timecodeDigitProbes) and the glyph is
 * CENTERED, so the digit's x depends on its own glyph alone — an
 * unchanged digit never moves, whatever its neighbors render as. On a
 * tnum-ignoring proportional font this synthesizes the tabular grid the
 * font refuses to apply: the cell IS the tabular column.
 */
@Composable
private fun TimecodeDigitCell(
    digit: String,
    cellWidth: Dp,
    style: TextStyle,
) {
    Text(
        text = digit,
        style = style,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(cellWidth),
    )
}

/**
 * Thin seek line: plain inactive track + active fill up to the playhead.
 * Fraction comes from the public SliderState (value / valueRange) —
 * coercedValueAsFraction is module-internal in material3.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekTrack(
    state: SliderState,
    colors: SliderColors,
    isRtl: Boolean,
) {
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    val trackHeight = CompactSliderDefaults.TRACK_HEIGHT
    val fraction = EditorLayout.fractionOf(state.value, state.valueRange)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(trackHeight)
            .let { if (isRtl) it.rotate(180f) else it },
    ) {
        val w = size.width
        val y = size.height / 2f
        val stroke = trackHeight.toPx()
        drawLine(
            color = inactiveColor,
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = activeColor,
            start = Offset(0f, y),
            end = Offset(w * fraction, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}
