package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
) {
    val seekDescription = stringResource(R.string.editor_seek)
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
        Slider(
            value = fraction,
            onValueChange = onSeekFraction,
            onValueChangeFinished = onSeekFinished,
            valueRange = 0f..1f,
            enabled = enabled,
            interactionSource = seekInteraction,
            colors = colors,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = seekDescription },
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
        Text(
            text = Format.formatTimecodePair(elapsedUs, totalUs),
            style = MaterialTheme.typography.labelMedium.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
