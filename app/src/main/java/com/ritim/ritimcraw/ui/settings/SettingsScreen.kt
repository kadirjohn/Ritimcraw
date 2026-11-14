package com.ritim.ritimcraw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.ThemeMode
import com.ritim.ritimcraw.engine.CodecProbe
import com.ritim.ritimcraw.engine.CodecReport
import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.engine.RenderBackend
import com.ritim.ritimcraw.ui.components.Activity
import com.ritim.ritimcraw.ui.components.AppSegmentedControl
import com.ritim.ritimcraw.ui.components.Contrast
import com.ritim.ritimcraw.ui.components.Cpu
import com.ritim.ritimcraw.ui.components.Eye
import com.ritim.ritimcraw.ui.components.Folder
import com.ritim.ritimcraw.ui.components.Gauge
import com.ritim.ritimcraw.ui.components.Report
import com.ritim.ritimcraw.ui.components.SettingsGroupCard
import com.ritim.ritimcraw.ui.components.SettingsGroupCardRow
import com.ritim.ritimcraw.ui.components.SettingsSectionLabel
import com.ritim.ritimcraw.ui.components.SettingRowBase
import com.ritim.ritimcraw.ui.components.SettingRowExpandable
import com.ritim.ritimcraw.ui.components.SettingRowNavigation
import com.ritim.ritimcraw.ui.components.SettingRowSwitch

/**
 * Settings screen (owner spec 2026-11-14, target_layout, rebuilt): the
 * same rows, the same state hoisting and callbacks as before — only the
 * PRESENTATION changed. The screen is a vertically scrolling column over
 * the app shell's Scaffold (the bottom NavigationBar stays below it),
 * composed from the redesign's shared components:
 *
 * - the spec's own 32sp SemiBold title (the redesign replaces the title
 *   on THIS screen only; the other browse screens keep ScreenHeader),
 * - "Playback" card: Preview quality + Image processing as expandable
 *   rows — the long descriptions moved behind the info buttons, so no
 *   multi-line description renders by default,
 * - "Storage & device" card: Gyro log folder + Device report navigation
 *   rows,
 * - the Measure button outside any card (spec geometry: 56dp pill, the
 *   gauge icon and label centered as ONE group, never a start-aligned
 *   leading slot),
 * - "Advanced" card: the stats-readback switch row (debug only, gated
 *   exactly as before — release builds render neither the button nor
 *   the section),
 * - "Appearance" card: the Theme row (the segmented control stays until
 *   the theme-slider step replaces it with the compact pill),
 * - the centered version footer (the old divider line is gone).
 *
 * The device report dialog (owner request 2026-11-13) is unchanged in
 * this step — the device-report step replaces it with a full screen and
 * this dialog + its state go away then. The Measure action (debug
 * builds only) runs the preview latency/fps harness over the first
 * imported clip and logs one summary block under the "ritim-pm" tag —
 * see the device test steps in the sprint SUMMARY.
 *
 * The screen owns no store: every level and callback is a parameter
 * (AppNav owns the stores), so the pure mappings stay in the enums and
 * the persistence in AppNav's stores — the redesign changes none of
 * that wiring, only the rows' look.
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
    // ---- Preview stats readback (debug A/B toggle, 2026-11-14) ----
    // The persisted choice (AppNav reads PreviewStatsStore) and the
    // change callback (AppNav persists + applies it to the engine's
    // jniSetPreviewStatsEnabled). Debug-only row: it renders inside
    // the BuildConfig.DEBUG block below (beside Measure), so release
    // builds pass the values but never show or flip the toggle — the
    // native default (on) is exactly the pre-toggle behavior there.
    // Defaults keep every existing caller and preview working.
    previewStats: Boolean = true,
    onPreviewStatsChange: (Boolean) -> Unit = {},
    // ---- Theme (owner request 2026-11-14) ----
    // The persisted light/dark choice (MainActivity reads ThemeStore —
    // the theme construction site owns the state because RitimTheme
    // wraps the whole app there) and the change callback (MainActivity
    // persists + updates its state, so the recomposition re-tints the
    // whole app immediately). Defaults keep every existing caller and
    // preview working.
    themeMode: ThemeMode = ThemeMode.DEFAULT,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The device report dialog (owner request 2026-11-13): the codec list
    // lives in the dialog, not the main screen. Local open/close state —
    // view-only content, nothing to persist or wire through AppNav. The
    // device-report step replaces this dialog with a full screen.
    var reportOpen by remember { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // The spec's title (spec section_screen): 32sp SemiBold,
        // letterSpacing -0.01em, onSurface. The app shell's Scaffold
        // already applies the status-bar inset through its innerPadding
        // (default contentWindowInsets), so the 16dp top padding here
        // sits below that inset — nothing hardcodes a status bar height.
        // 20dp horizontal padding: the spec's title/section gutter.
        Text(
            text = stringResource(R.string.settings_title),
            style = TextStyle(
                fontSize = 32.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).em,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp),
        )
        // ---- Playback ----
        SettingsSectionLabel(
            text = stringResource(R.string.settings_section_playback),
            first = true,
        )
        SettingsGroupCard {
            SettingsGroupCardRow(divider = false) {
                SettingRowExpandable(
                    icon = Eye,
                    title = stringResource(R.string.settings_preview_quality),
                    // The dims of the selected level (spec hint: the
                    // current value, not the old caption under the row).
                    hint = stringResource(
                        R.string.settings_preview_quality_hint,
                        previewQuality.playbackMaxDim,
                        previewQuality.scrubMaxDim,
                    ),
                    infoContentDescription = stringResource(R.string.settings_preview_quality_about),
                    infoNote = stringResource(R.string.settings_preview_quality_info),
                    expandedText = stringResource(R.string.settings_expanded),
                    collapsedText = stringResource(R.string.settings_collapsed),
                ) {
                    AppSegmentedControl(
                        options = listOf(
                            stringResource(R.string.settings_preview_quality_low),
                            stringResource(R.string.settings_preview_quality_balanced),
                            stringResource(R.string.settings_preview_quality_high),
                        ),
                        // Options keep the enum's own order (LOW,
                        // BALANCED, HIGH) — the index maps straight back.
                        selectedIndex = previewQuality.ordinal,
                        onSelect = { index ->
                            onPreviewQualityChange(PreviewQuality.entries[index])
                        },
                    )
                }
            }
            SettingsGroupCardRow(divider = true) {
                SettingRowExpandable(
                    icon = Cpu,
                    title = stringResource(R.string.settings_render_backend),
                    // State-dependent hint (spec): the requested axis in
                    // one line — the semantics stay in the info note.
                    hint = when (renderBackend) {
                        RenderBackend.CPU ->
                            stringResource(R.string.settings_render_backend_hint_cpu)
                        RenderBackend.AUTO ->
                            stringResource(R.string.settings_render_backend_hint_auto)
                        RenderBackend.GPU_EXPERIMENTAL ->
                            stringResource(R.string.settings_render_backend_hint_gpu)
                    },
                    infoContentDescription = stringResource(R.string.settings_render_backend_about),
                    infoNote = stringResource(R.string.settings_render_backend_info),
                    expandedText = stringResource(R.string.settings_expanded),
                    collapsedText = stringResource(R.string.settings_collapsed),
                ) {
                    AppSegmentedControl(
                        options = listOf(
                            stringResource(R.string.settings_render_backend_cpu),
                            stringResource(R.string.settings_render_backend_auto),
                            stringResource(R.string.settings_render_backend_gpu),
                        ),
                        // Options keep the enum's own order (CPU, AUTO,
                        // GPU_EXPERIMENTAL) — the index maps straight back.
                        selectedIndex = renderBackend.ordinal,
                        onSelect = { index ->
                            onRenderBackendChange(RenderBackend.entries[index])
                        },
                    )
                }
            }
        }
        // ---- Storage & device ----
        SettingsSectionLabel(text = stringResource(R.string.settings_section_storage_device))
        SettingsGroupCard {
            SettingsGroupCardRow(divider = false) {
                SettingRowNavigation(
                    icon = Folder,
                    title = stringResource(R.string.settings_gyro_folder),
                    // The spec's hint: the folder path only (or "Not
                    // set") — the chevron carries the affordance, no
                    // "Current:" prefix, no "tap to change" suffix.
                    hint = gyroFolderLabel
                        ?: stringResource(R.string.settings_gyro_folder_not_set),
                    onClick = onPickGyroFolder,
                )
            }
            SettingsGroupCardRow(divider = true) {
                if (reports.isEmpty()) {
                    // The probe's empty placeholder keeps its designed
                    // wording and no false affordance (the pre-probe
                    // contract): no chevron, no click — the dialog below
                    // would have nothing to show.
                    SettingRowBase(
                        icon = Report,
                        title = stringResource(R.string.settings_device_report),
                        hint = stringResource(R.string.settings_device_report_body),
                    )
                } else {
                    SettingRowNavigation(
                        icon = Report,
                        title = stringResource(R.string.settings_device_report),
                        // "{n} codecs" from a plurals resource (spec).
                        hint = pluralStringResource(
                            R.plurals.settings_device_report_codec_count,
                            reports.size,
                            reports.size,
                        ),
                        onClick = { reportOpen = true },
                    )
                }
            }
        }
        // ---- Measure preview performance (debug) + Advanced ----
        // Spec button: outside any card, 56dp pill (extraLarge = 28dp on
        // a 56dp height), primary/onPrimary, ONE centered Row (gauge 30dp
        // + 14dp spacer + 16sp SemiBold label) — the icon+label group
        // centers as a whole, never a start-aligned leading slot. The
        // gate is unchanged from before the redesign: release builds pass
        // onMeasure as null and neither the button nor the Advanced
        // section exists at all.
        if (BuildConfig.DEBUG && onMeasure != null) {
            Button(
                onClick = onMeasure,
                enabled = !measuring,
                shape = MaterialTheme.shapes.extraLarge,
                contentPadding = PaddingValues(horizontal = 24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 20.dp, end = 16.dp)
                    .height(56.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Gauge,
                        contentDescription = null,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = if (measuring) {
                            stringResource(R.string.settings_measuring)
                        } else {
                            stringResource(R.string.settings_measure)
                        },
                        style = TextStyle(
                            fontSize = 16.sp,
                            lineHeight = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SettingsSectionLabel(text = stringResource(R.string.settings_section_advanced))
            SettingsGroupCard {
                SettingsGroupCardRow(divider = false) {
                    SettingRowSwitch(
                        icon = Activity,
                        title = stringResource(R.string.settings_preview_stats),
                        // State-dependent hint (spec switch_row): the old
                        // long debug description is dropped from the
                        // screen — one line states what the toggle does.
                        hint = if (previewStats) {
                            stringResource(R.string.settings_preview_stats_hint_on)
                        } else {
                            stringResource(R.string.settings_preview_stats_hint_off)
                        },
                        checked = previewStats,
                        onCheckedChange = onPreviewStatsChange,
                    )
                }
            }
        }
        // ---- Appearance ----
        SettingsSectionLabel(text = stringResource(R.string.settings_section_appearance))
        SettingsGroupCard {
            SettingsGroupCardRow(divider = false) {
                // The Theme row (owner request 2026-11-14): the segmented
                // System/Light/Dark control stays in this step — the
                // theme-slider step replaces it with the compact pill
                // (spec theme_slider). The hint is the current choice's
                // name; the row keeps the same hoisting as before.
                Column(Modifier.fillMaxWidth()) {
                    SettingRowBase(
                        icon = Contrast,
                        title = stringResource(R.string.settings_theme),
                        hint = when (themeMode) {
                            ThemeMode.SYSTEM -> stringResource(R.string.settings_theme_system)
                            ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
                            ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                        },
                    )
                    Box(
                        Modifier.padding(
                            start = 16.dp,
                            top = 14.dp,
                            end = 16.dp,
                        )
                    ) {
                        val themeOptions = listOf(
                            ThemeMode.SYSTEM,
                            ThemeMode.LIGHT,
                            ThemeMode.DARK,
                        )
                        AppSegmentedControl(
                            options = listOf(
                                stringResource(R.string.settings_theme_system),
                                stringResource(R.string.settings_theme_light),
                                stringResource(R.string.settings_theme_dark),
                            ),
                            selectedIndex = themeOptions.indexOf(themeMode),
                            onSelect = { index ->
                                onThemeModeChange(themeOptions[index])
                            },
                        )
                    }
                }
            }
        }
        // ---- Version footer (spec footer) ----
        // Centered, 12sp, onSurfaceVariant, top 28dp / bottom 24dp; the
        // old divider line is gone. Same version source as before.
        Text(
            text = stringResource(R.string.settings_version, versionName),
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Normal,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 24.dp),
        )
    }
    // The codec pills dialog (owner request 2026-11-13): one pill per codec
    // in a flowing wrap list — the whole report at a glance instead of the
    // old flat one-liner rows. The app's plain AlertDialog pattern
    // (Library delete, gyro picker); no nav-graph addition. The
    // device-report step replaces this with the full screen.
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
