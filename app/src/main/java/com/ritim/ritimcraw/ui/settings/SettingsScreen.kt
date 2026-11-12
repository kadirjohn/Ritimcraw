package com.ritim.ritimcraw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.CodecProbe
import com.ritim.ritimcraw.engine.CodecReport
import com.ritim.ritimcraw.engine.PreviewMetrics
import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.engine.RenderBackend
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.theme.Tnum

/**
 * Settings screen (DESIGN.md Settings): the device report. The codec probe
 * runs on device only; until then the placeholder body stays and the
 * report list renders empty (probe returns nothing on non-Android JVMs and
 * the screen keeps the designed placeholder).
 *
 * The device report's codec list (owner request 2026-11-13: "device
 * report'taki codec çeşitlilikleri ekranda düzgün görüntülenmiyor" — the
 * device report's codec varieties were not displaying properly) moved out
 * of the settings main screen into its own dialog: the "Device report" row
 * opens a dialog that shows every codec as a separate pill in a flowing
 * wrap list (stock M3 AssistChip, one pill per codec — name + hardware/
 * software marker; the full [CodecProbe.formatReport] line rides the
 * chip as its accessibility description). No nav-graph addition — the
 * app's existing AlertDialog pattern (Library delete, gyro picker).
 *
 * The Measure action (debug builds only) runs the preview latency/fps
 * harness over the first imported clip and logs one summary block under
 * the "ritim-pm" tag — see the device test steps in the sprint SUMMARY.
 *
 * Preview quality (owner request 2026-11-11b section 3): the user's
 * playback/scrub resolution choice, a Low/Balanced/High segmented row
 * (DESIGN.md Inputs: segmented buttons for a choice). The screen owns no
 * store: the level and the change callback are parameters, so the pure
 * mapping stays in PreviewQuality and the persistence in AppNav's
 * PreviewQualityStore.
 */
// FlowRow (the codec pills' wrap layout) is stable API since foundation
// 1.7 but still carries the @ExperimentalLayoutApi marker in this
// version's artifacts; the opt-in is scoped to this file's usage.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    versionName: String = "1.0",
    reports: List<CodecReport> = remember { runCatching { CodecProbe.probe() }.getOrDefault(emptyList()) },
    onMeasure: (() -> Unit)? = null,
    measuring: Boolean = false,
    // ---- Preview quality (owner request 2026-11-11b section 3) ----
    // The persisted level (AppNav reads PreviewQualityStore) and the
    // change callback (AppNav persists + refreshes its state). Defaults
    // keep every existing caller and preview working.
    previewQuality: PreviewQuality = PreviewQuality.DEFAULT,
    onPreviewQualityChange: (PreviewQuality) -> Unit = {},
    // ---- Render backend (owner request 2026-11-12, deliverable B) ----
    // The persisted requested backend (AppNav reads RenderBackendStore) and
    // the change callback (AppNav persists + refreshes its state). Defaults
    // keep every existing caller working; the picker is the REQUESTED axis
    // only — the effective backend is computed per job from the runtime
    // gates and reported honestly (RenderBackend/BackendRules).
    renderBackend: RenderBackend = RenderBackend.DEFAULT,
    onRenderBackendChange: (RenderBackend) -> Unit = {},
    // ---- Gyro folder grant (owner request 2026-11-12, G1) ----
    // The granted tree's friendly label (Documents/MotionCam), or null
    // when no folder is granted. The row re-picks on tap; AppNav owns
    // the store + the SAF launcher, the screen only renders + forwards.
    gyroFolderLabel: String? = null,
    onPickGyroFolder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The device report dialog (owner request 2026-11-13): the codec list
    // lives in the dialog, not the main screen. Local open/close state —
    // view-only content, nothing to persist or wire through AppNav.
    var reportOpen by remember { mutableStateOf(false) }
    // Canonical header call site (title consistency, owner finding
    // 2026-11-12 — the contract lives in ScreenHeader's doc): the root
    // Column pads NOTHING, so the title's left edge is ScreenHeader's
    // own 16dp in all four browse screens. The 16dp screen gutter moved
    // into the body container below — every row keeps its exact margins.
    Column(modifier.fillMaxSize()) {
        // Shared header (owner request 2026-11-11d: consistent top area
        // across the main screens) — same title, same compact rhythm as
        // Library/Exports; no toolbar actions on this screen.
        ScreenHeader(title = stringResource(R.string.settings_title))
        // The 16dp screen gutter (DESIGN.md Layout) that used to pad the
        // root Column: moved here so it no longer shifts the title. The
        // body fills the remaining height, so the LazyColumn's weight(1f)
        // keeps working exactly as before.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
        ) {
            // Preview quality row (owner request 2026-11-11b section 3): the
            // headline + supporting text state the tradeoff; the segmented row
            // carries the choice. Owner finding 2026-11-11 E3: an M3
            // SegmentedButton centers ALL of its content at the same spot
            // (SegmentedButtonContent places every child identically), so a
            // label Text plus a dims Text overlapped on the device. Each button
            // now carries ONLY its label; the SELECTED level's dims render as
            // one supporting line UNDER the row in tabular numerals (DESIGN.md
            // Numbers) — the tradeoff stays visible, nothing overlaps. The
            // picker changes ONLY playback + scrub — the supporting text says
            // so (paused frames always settle to the clip's own resolution).
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_preview_quality)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_preview_quality_body))
                },
            )
            val qualityOptions = listOf(
                R.string.settings_preview_quality_low to PreviewQuality.LOW,
                R.string.settings_preview_quality_balanced to PreviewQuality.BALANCED,
                R.string.settings_preview_quality_high to PreviewQuality.HIGH,
            )
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                qualityOptions.forEachIndexed { index, (labelRes, level) ->
                    SegmentedButton(
                        selected = previewQuality == level,
                        onClick = { onPreviewQualityChange(level) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = qualityOptions.size,
                        ),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
            // The selected level's dims, under the row (E3): playback / scrub
            // caps in tabular numerals, changing with the selection.
            Text(
                stringResource(
                    R.string.settings_preview_quality_dims,
                    previewQuality.playbackMaxDim,
                    previewQuality.scrubMaxDim,
                ),
                style = MaterialTheme.typography.labelMedium.merge(Tnum),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            // Render backend row (owner request 2026-11-12, deliverable B):
            // the REQUESTED image-processing backend. Same segmented pattern
            // as preview quality (one label per button, E3). The supporting
            // text states the semantics honestly: CPU is the reference, Auto
            // is capability/parity-gated, GPU is experimental with honest
            // fallback — and the encoder axis is independent of this choice.
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_render_backend)) },
                supportingContent = {
                    Text(stringResource(R.string.settings_render_backend_body))
                },
            )
            val backendOptions = listOf(
                R.string.settings_render_backend_cpu to RenderBackend.CPU,
                R.string.settings_render_backend_auto to RenderBackend.AUTO,
                R.string.settings_render_backend_gpu to RenderBackend.GPU_EXPERIMENTAL,
            )
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            ) {
                backendOptions.forEachIndexed { index, (labelRes, backend) ->
                    SegmentedButton(
                        selected = renderBackend == backend,
                        onClick = { onRenderBackendChange(backend) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = backendOptions.size,
                        ),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
            // Gyro folder grant (G1): the durable home of the grant. The row
            // states what the folder is for and shows the current folder (or
            // the not-set wording); a tap opens the SAF tree picker. The
            // supporting text doubles as the recovery line — the auto-match
            // does nothing until the owner picks the MotionCam folder here
            // (or through the one-time import hint).
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_gyro_folder)) },
                supportingContent = {
                    Text(
                        if (gyroFolderLabel == null) {
                            stringResource(R.string.settings_gyro_folder_none)
                        } else {
                            stringResource(R.string.settings_gyro_folder_set, gyroFolderLabel)
                        }
                    )
                },
                modifier = Modifier.clickable(onClick = onPickGyroFolder),
            )
            // Device report (owner request 2026-11-13): the codec list is no
            // longer the main screen's flat one-liner rows — the row opens a
            // dialog that shows every codec as its own pill (tap only when
            // there is something to show; the probe's empty placeholder
            // keeps its designed wording and no false affordance).
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_device_report)) },
                supportingContent = {
                    Text(
                        if (reports.isEmpty()) {
                            stringResource(R.string.settings_device_report_body)
                        } else {
                            stringResource(R.string.settings_device_report_count, reports.size)
                        }
                    )
                },
                modifier = if (reports.isEmpty()) {
                    Modifier
                } else {
                    Modifier.clickable { reportOpen = true }
                },
            )
            // Debug-only measurement harness entry point (owner request
            // 2026-11-10 section 1). In release builds onMeasure is null and
            // the action does not exist at all.
            if (BuildConfig.DEBUG && onMeasure != null) {
                Button(
                    onClick = onMeasure,
                    enabled = !measuring,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        if (measuring) {
                            stringResource(R.string.settings_measuring)
                        } else {
                            stringResource(R.string.settings_measure)
                        }
                    )
                }
            }
            // The codec list's new home (see the screen KDoc): the dialog
            // below. This spacer keeps the version footer pinned to the
            // screen bottom exactly as before.
            Column(Modifier.weight(1f)) { }
            HorizontalDivider()
            Text(
                stringResource(R.string.settings_version, versionName),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
    // The codec pills dialog (owner request 2026-11-13): one pill per codec
    // in a flowing wrap list — the whole report at a glance instead of the
    // old flat one-liner rows. The app's plain AlertDialog pattern
    // (Library delete, gyro picker); no nav-graph addition.
    if (reportOpen && reports.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { reportOpen = false },
            title = { Text(stringResource(R.string.settings_device_report)) },
            text = {
                // FlowRow wraps the pills to the dialog's width — a codec
                // list of any length reads as flowing rows, not one long
                // column; verticalScroll bounds a very long device's list.
                FlowRow(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    reports.forEach { report ->
                        // One codec, one pill (stock M3 AssistChip — the
                        // editor's PreviewRefreshControl precedent): the
                        // codec's name is the label, the hardware/software
                        // flag is the leading marker. The full formatReport
                        // line (kind, profiles, max size, 10-bit) rides the
                        // chip as its accessibility description — the
                        // on-screen pill stays scannable, the detail stays
                        // one TalkBack step away. The pill is informational,
                        // but AssistChip is a clickable shape by design: the
                        // tap closes the dialog (an honest visible response
                        // that matches TalkBack's "button" announcement,
                        // instead of a dead no-op).
                        val description = CodecProbe.formatReport(report)
                        AssistChip(
                            onClick = { reportOpen = false },
                            label = { Text(report.name) },
                            leadingIcon = {
                                Text(
                                    stringResource(
                                        if (report.hardware) {
                                            R.string.settings_codec_hw
                                        } else {
                                            R.string.settings_codec_sw
                                        }
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = description
                            },
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reportOpen = false }) {
                    Text(stringResource(R.string.settings_report_close))
                }
            },
        )
    }
}
