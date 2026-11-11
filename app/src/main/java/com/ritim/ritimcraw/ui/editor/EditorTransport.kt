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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.rememberTextMeasurer
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
    precisionReadout: String? = null,
    onPrecisionEngage: () -> Boolean = { false },
    onPrecisionDragDp: (Float) -> Unit = {},
    onPrecisionRelease: () -> Unit = {},
) {
    val seekDescription = stringResource(R.string.editor_seek)
    // Steady readout width (owner round 3a, 2026-11-11): the timecode
    // pair's frame field ticks ~24x/s during playback, and on a device
    // font that ignores tnum the proportional digits oscillate the
    // label's natural width — the label sways and the weighted slider
    // beside it resizes with it. Pin the readout to the width of the
    // WIDEST pair measured with the exact style it renders with. The
    // uniform-digit probes bound every mixed-digit pair from above on
    // any font (see Format.timecodeWidthProbes); on a tabular font all
    // ten measure identical, so this equals the single widest string.
    // The style is hoisted so the measure and the Text share one object.
    val timecodeStyle = MaterialTheme.typography.labelMedium.merge(Tnum)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val timecodeMinWidth = remember(timecodeStyle, density) {
        val probes = Format.timecodeWidthProbes()
        with(density) {
            probes.maxOf { probe ->
                textMeasurer.measure(probe, timecodeStyle).size.width.toDp()
            }
        }
    }
    Row(
        Modifier
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
        Text(
            text = Format.formatTimecodePair(elapsedUs, totalUs),
            style = timecodeStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = timecodeMinWidth),
        )
    }
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
