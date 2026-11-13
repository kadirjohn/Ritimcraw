package com.ritim.ritimcraw.ui.timeline

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.data.audio.AudioProject
import com.ritim.ritimcraw.ui.theme.Tnum
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The audio settings sheet (AUD-03's per-clip settings + AUD-02's link
 * controls) — the audio model's first settings UI. FramePickerSheet
 * style: a ModalBottomSheet with a titleSmall title, the whole body one
 * scrollable column.
 *
 * SETTINGS (AUD-03): gain dB, fade-in ms, fade-out ms for ONE audio
 * timeline clip, handed to [onSettings] when a slider settles (the
 * screen applies AudioTrackOps.setSettings and saves the AudioStore —
 * one store write per settled gesture, the timeline screen's own
 * one-gesture-one-commit rule). The settings row joins the timeline BY
 * CLIP ID and carries no range, so trims preserve it by construction;
 * the split flow (TimelineScreen) calls AudioTrackOps.afterSplit so the
 * right piece inherits the row.
 *
 * THE RATE GATE (AUD-06, honest by construction): creating a settings
 * row REQUIRES the source's real sample rate — AudioClip's own
 * invariant throws on a non-positive rate, and this sheet never sets
 * up the throw. The sliders enable only when the row already exists
 * (its persisted rate is the anchor; writes pass 0 = keep it) or the
 * metadata record says the rate is KNOWN. An unknown rate shows the
 * [R.string.timeline_audio_unknown_rate] hint and disables the
 * sliders — the rate is required data and is never guessed.
 *
 * LINK CONTROLS (AUD-02): the source's CURRENT link state, always
 * honest — the sheet renders the state it was handed, and the screen
 * hands it the freshly-saved state after every action. Detach/Restore/
 * Remove enable per the state machine's transition rules: an action
 * with no meaningful transition from the current state renders
 * disabled, never hidden (the state stays visible and legible). The
 * ops underneath are idempotent and self-guarding, so a double tap or
 * a stale render cannot corrupt the record. RE-LINK renders DISABLED
 * with an honest hint: binding an edited audio recording needs the
 * edited-audio store, a later milestone — a fake enabled button would
 * be a lie.
 *
 * UNDO BOUNDARY (v1's documented rule): timeline edits ride
 * TimelineHistory, but these audio writes are EXPLICIT state-machine
 * transitions and settings writes into the AudioStore — they are NOT
 * recorded as undo steps. Every action saves immediately, so the sheet
 * on screen is the record on disk; reopen-survival is the acceptance.
 *
 * @param clipId the audio TIMELINE clip's id — the settings row's join
 *   key (the video clip's linkedAudioId points at the same id).
 * @param sourceId the source both clips of the pair came from — the
 *   link record's key (AUD-02 records state PER SOURCE).
 * @param sampleRateHz the metadata record's KNOWN sample rate, or 0
 *   when the rate is unknown (AUD-06's gate reads this).
 * @param audioProject the CURRENT audio state (the screen reloads it
 *   from the AudioStore after every action and hands it back here).
 * @param onSettings applies gain/fades (the screen runs setSettings +
 *   save). Fires when a drag settles and on a double-tap reset.
 * @param onDetach applies AudioTrackOps.detach + save.
 * @param onRestore applies AudioTrackOps.restore + save.
 * @param onRemove applies AudioTrackOps.remove + save.
 * @param onDismiss closes the sheet (nothing further happens; every
 *   action already saved).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioSettingsSheet(
    clipId: String,
    sourceId: String,
    sampleRateHz: Int,
    audioProject: AudioProject,
    onSettings: (gainDb: Float, fadeInMs: Int, fadeOutMs: Int) -> Unit,
    onDetach: () -> Unit,
    onRestore: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The rate gate (AUD-06): an existing row carries its own persisted
    // rate (writes may pass 0 = keep it); a NEW row needs the KNOWN
    // observed rate or it cannot be created honestly.
    val row = audioProject.clip(clipId)
    val settingsEnabled = row != null || sampleRateHz > 0

    // The sliders' live values: seeded from the row (or the unity
    // default for an absent row) at open, written through on settle.
    // Unkeyed remember: the sheet is short-lived and the row only
    // changes through this sheet's own writes.
    var gainDb by remember { mutableStateOf(row?.gainDb ?: 0f) }
    var fadeInMs by remember { mutableStateOf((row?.fadeInMs ?: 0).toFloat()) }
    var fadeOutMs by remember { mutableStateOf((row?.fadeOutMs ?: 0).toFloat()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.timeline_audio_settings),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (!settingsEnabled) {
                Text(
                    stringResource(R.string.timeline_audio_unknown_rate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            AudioSliderRow(
                label = stringResource(R.string.timeline_audio_gain),
                valueLabel = formatDb(gainDb),
                value = gainDb,
                valueRange = GainRange,
                step = GainStepDb,
                enabled = settingsEnabled,
                onValueChange = { gainDb = it },
                onCommit = {
                    onSettings(gainDb, fadeInMs.roundToInt(), fadeOutMs.roundToInt())
                },
                onReset = {
                    gainDb = 0f
                    onSettings(0f, fadeInMs.roundToInt(), fadeOutMs.roundToInt())
                },
            )
            AudioSliderRow(
                label = stringResource(R.string.timeline_audio_fade_in),
                valueLabel = formatMs(fadeInMs.roundToInt()),
                value = fadeInMs,
                valueRange = FadeRange,
                step = FadeStepMs,
                enabled = settingsEnabled,
                onValueChange = { fadeInMs = it },
                onCommit = {
                    onSettings(gainDb, fadeInMs.roundToInt(), fadeOutMs.roundToInt())
                },
                onReset = {
                    fadeInMs = 0f
                    onSettings(gainDb, 0, fadeOutMs.roundToInt())
                },
            )
            AudioSliderRow(
                label = stringResource(R.string.timeline_audio_fade_out),
                valueLabel = formatMs(fadeOutMs.roundToInt()),
                value = fadeOutMs,
                valueRange = FadeRange,
                step = FadeStepMs,
                enabled = settingsEnabled,
                onValueChange = { fadeOutMs = it },
                onCommit = {
                    onSettings(gainDb, fadeInMs.roundToInt(), fadeOutMs.roundToInt())
                },
                onReset = {
                    fadeOutMs = 0f
                    onSettings(gainDb, fadeInMs.roundToInt(), 0)
                },
            )

            // ---- AUD-02: the source's link state ----
            val link = audioProject.link(sourceId)
            Text(
                stringResource(R.string.timeline_audio_link_title),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Text(
                text = stringResource(linkStateLabel(link.state)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Text(
                text = stringResource(linkStateHint(link.state)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            // The state machine's actions, enabled per its transition
            // rules: Detach (ATTACHED/LINKED_EDITED -> DETACHED),
            // Restore (DETACHED/LINKED_EDITED -> ATTACHED; REFUSED on
            // REMOVED — the no-resurrection invariant), Remove (any
            // state -> REMOVED). Disabled means "no meaningful
            // transition from here", never hidden.
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onDetach,
                    enabled = link.state == AudioLinkState.ATTACHED ||
                        link.state == AudioLinkState.LINKED_EDITED,
                ) {
                    Text(stringResource(R.string.timeline_audio_detach))
                }
                TextButton(
                    onClick = onRestore,
                    enabled = link.state == AudioLinkState.DETACHED ||
                        link.state == AudioLinkState.LINKED_EDITED,
                ) {
                    Text(stringResource(R.string.timeline_audio_restore))
                }
                TextButton(
                    onClick = onRemove,
                    enabled = link.state != AudioLinkState.REMOVED,
                ) {
                    Text(stringResource(R.string.timeline_remove))
                }
            }
            // RE-LINK (AUD-02): rendered, honestly DISABLED — binding
            // an edited audio recording requires the edited-audio
            // store, a later milestone. The hint says so in plain
            // words; no fake entry.
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                TextButton(onClick = {}, enabled = false) {
                    Text(stringResource(R.string.timeline_audio_relink))
                }
            }
            Text(
                stringResource(R.string.timeline_audio_relink_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Gain slider range: ±24 dB — a strong but bounded band (v1). */
private val GainRange = -24f..24f

/** Gain step: 0.5 dB — the audible just-noticeable granularity. */
private const val GainStepDb = 0.5f

/**
 * Fade slider ranges: 0..2000 ms on a 10 ms grid (v1). A fade longer
 * than the clip's current duration clamps at render time — the
 * AudioClip stored-intent rule — so the ceiling is a UI convenience,
 * not a correctness bound.
 */
private val FadeRange = 0f..2000f
private const val FadeStepMs = 10f

/**
 * One settings slider (DESIGN.md Inputs: "M3 Slider with label and
 * tabular value; double tap resets"): label left + tnum value right on
 * one row, the M3 Slider below it. The value updates LIVE during the
 * drag; [onCommit] fires only when the drag settles
 * (onValueChangeFinished) — one write per gesture. Double tap anywhere
 * on the row resets to the default and commits immediately (an explicit
 * action deserves its own save).
 */
@Composable
private fun AudioSliderRow(
    label: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onCommit: () -> Unit,
    onReset: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            // Double tap resets (DESIGN.md Inputs) — the row itself
            // carries the detector so the whole 48dp slider slot is
            // the reset target, same as the editor's grade sliders.
            .pointerInput(label, enabled) {
                detectTapGestures(onDoubleTap = { if (enabled) onReset() })
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            // tnum so the digits never reflow mid-drag (DESIGN.md
            // Typography — same rule as the playhead readout).
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge.merge(Tnum),
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = sliderSteps(valueRange, step),
            enabled = enabled,
            onValueChangeFinished = onCommit,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * M3 Slider steps: the count of INTERIOR tick values between the range
 * endpoints (the endpoints themselves are always reachable). Local twin
 * of the editor's SliderSteps.composeSteps — the sheet keeps its own
 * three-line rule rather than importing an editor-tab helper.
 */
private fun sliderSteps(range: ClosedFloatingPointRange<Float>, step: Float): Int {
    val intervals = ((range.endInclusive - range.start) / step).toInt()
    return (intervals - 1).coerceAtLeast(0)
}

/** "+6.5 dB" / "-3.0 dB" — explicit sign, one decimal (the formatEv convention for bipolar values). */
private fun formatDb(db: Float): String = String.format(Locale.ROOT, "%+.1f dB", db)

/** "120 ms" — unsigned (the range starts at 0; a sign would only add noise). */
private fun formatMs(ms: Int): String = String.format(Locale.ROOT, "%d ms", ms)

/** The link state's display label (AUD-02's four states). */
private fun linkStateLabel(state: AudioLinkState): Int = when (state) {
    AudioLinkState.ATTACHED -> R.string.timeline_audio_state_attached
    AudioLinkState.DETACHED -> R.string.timeline_audio_state_detached
    AudioLinkState.REMOVED -> R.string.timeline_audio_state_removed
    AudioLinkState.LINKED_EDITED -> R.string.timeline_audio_state_linked_edited
}

/** The link state's one-line explanation (what the state means, in plain words). */
private fun linkStateHint(state: AudioLinkState): Int = when (state) {
    AudioLinkState.ATTACHED -> R.string.timeline_audio_state_attached_hint
    AudioLinkState.DETACHED -> R.string.timeline_audio_state_detached_hint
    AudioLinkState.REMOVED -> R.string.timeline_audio_state_removed_hint
    AudioLinkState.LINKED_EDITED -> R.string.timeline_audio_state_linked_edited_hint
}
