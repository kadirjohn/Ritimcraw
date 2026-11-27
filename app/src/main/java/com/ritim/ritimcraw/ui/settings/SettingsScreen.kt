package com.ritim.ritimcraw.ui.settings

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import com.ritim.ritimcraw.data.ThemeMode
import com.ritim.ritimcraw.ui.theme.ThemePalettes
import com.ritim.ritimcraw.ui.theme.ThemeSelection
import com.ritim.ritimcraw.engine.CodecProbe
import com.ritim.ritimcraw.engine.CodecReport
import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.engine.RenderBackend
import com.ritim.ritimcraw.ui.components.Activity
import com.ritim.ritimcraw.ui.components.readableWidth
import com.ritim.ritimcraw.ui.components.AppSegmentedControl
import com.ritim.ritimcraw.ui.components.Contrast
import com.ritim.ritimcraw.ui.components.Cpu
import com.ritim.ritimcraw.ui.components.Eye
import com.ritim.ritimcraw.ui.components.Folder
import com.ritim.ritimcraw.ui.components.Gauge
import com.ritim.ritimcraw.ui.components.PlayerBar
import com.ritim.ritimcraw.ui.components.Filmstrip
import com.ritim.ritimcraw.ui.components.Report
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.components.SettingsGroup
import com.ritim.ritimcraw.ui.components.SettingsGroupItem
import com.ritim.ritimcraw.ui.components.SettingsSectionLabel
import com.ritim.ritimcraw.ui.components.SettingRowBase
import com.ritim.ritimcraw.ui.components.SettingRowExpandable
import com.ritim.ritimcraw.ui.components.SettingRowNavigation
import com.ritim.ritimcraw.ui.components.SettingRowSwitch
import com.ritim.ritimcraw.ui.components.ThemeSlider

/**
 * Settings screen (owner spec 2026-11-14, target_layout; v2 polish
 * 2026-11-15 C1/C3): the same rows, the same state hoisting and
 * callbacks as before — only the PRESENTATION changed. The screen is a
 * vertically scrolling column over the app shell's Scaffold (the bottom
 * NavigationBar stays below it), composed from the redesign's shared
 * components:
 *
 * - the shared [ScreenHeader] title, identical to Library/Projects/
 *   Exports (owner request 2026-11-17 overrules the spec's own 32sp
 *   SemiBold title — all screen titles share one size and y position),
 * - "Playback" group: Preview quality + Image processing as expandable
 *   rows — the long descriptions moved behind the info buttons, so no
 *   multi-line description renders by default,
 * - "Storage & device" group: Gyro log folder + Device report navigation
 *   rows,
 * - the Measure button outside any group (spec geometry: 56dp pill, the
 *   gauge icon and label centered as ONE group, never a start-aligned
 *   leading slot),
 * - "Advanced" group: the stats-readback switch row (debug only, gated
 *   exactly as before — release builds render neither the button nor
 *   the section),
 * - "Appearance" group: the Theme row with the compact slider pill
 *   (spec theme_slider): tap or drag horizontally, the preference
 *   commits once on release — the segmented control is gone,
 * - the centered version footer (the old divider line is gone).
 *
 * v2 C1: groups are no longer outlined cards — each group is a
 * [SettingsGroup] (16dp margin, 2dp gaps) of separate tonal
 * [SettingsGroupItem] containers (surfaceContainer on the surface
 * background) whose 20dp/4dp corner pattern makes the group read as one
 * rounded block. No borders, no dividers, no elevation anywhere
 * (outline_policy). Each row passes its group position (index/count)
 * so its container gets the right corners; the screen background is
 * colorScheme.surface (the app shell's Scaffold paints `background`,
 * so this column paints `surface` itself — the tonal items need the
 * surface role under them).
 *
 * The Device report row navigates to the full report screen
 * ([DeviceReportScreen], the owner spec's replacement for the old
 * codec AlertDialog) through [onOpenDeviceReport]; the screen's scroll
 * position survives that navigation through [scrollState] (AppNav owns
 * it above the browse Crossfade, so the position outlives the swap and
 * the report back-navigation restores it — the spec's requirement).
 * The Measure action (debug builds only) runs the preview latency/fps
 * harness over the first imported clip and logs one summary block under
 * the "ritim-pm" tag — see the device test steps in the sprint SUMMARY.
 *
 * The screen owns no store: every level and callback is a parameter
 * (AppNav owns the stores), so the pure mappings stay in the enums and
 * the persistence in AppNav's stores — the redesign changes none of
 * that wiring, only the rows' look.
 */
@Composable
fun SettingsScreen(
    versionName: String = BuildConfig.VERSION_NAME,
    reports: List<CodecReport> = remember { runCatching { CodecProbe.probe() }.getOrDefault(emptyList()) },
    // The screen's scroll position, hoisted by AppNav above the browse
    // Crossfade (rememberSaveable(saver = ScrollState.Saver)) so it
    // survives navigating to the Device report screen and back — the
    // spec's restore requirement. The default keeps every other caller
    // (previews, tests) working with a local scroll state.
    scrollState: ScrollState = rememberScrollState(),
    // Opens the Device report full screen (the spec's replacement for
    // the old codec AlertDialog): AppNav owns the route flag.
    onOpenDeviceReport: () -> Unit = {},
    // Opens the dependency-free Licenses page.
    onOpenLicenses: () -> Unit = {},
    onMeasure: (() -> Unit)? = null,
    // Debug-only GPU-vs-CPU per-control parity sweep (2026-11-23, owner
    // report: GPU preview shows no change on slider moves); null in release.
    onGradeSweep: (() -> Unit)? = null,
    // Export A/B regression harness (guide §A7, owner request 2026-11-23):
    // the whole-pipeline check the per-control sweep is not — 4 composite
    // cases (neutral / grade / stab / grade+stab) x 3 frames through the CPU
    // export door AND the GPU develop session, one sync offset, byte-comared
    // with an EXPORT_MISMATCH flag under "ritim-parity". Null in release; the
    // button renders only inside the sweep's debug block below.
    onExportAb: (() -> Unit)? = null,
    // Debug-only codec measurement (E1e, docs/E1E_CODEC_MEASURE_PLAN): the
    // uncapped-ceiling + VBR-drift run over the device's real encoders.
    // Null in release; the button renders only inside the debug block.
    onCodecMeasure: (() -> Unit)? = null,
    // The codec measurement's live progress: runs done of total (0/0 when
    // idle); the button label shows the count while a run is in flight.
    codecMeasureProgress: Pair<Int, Int> = 0 to 0,
    // True while the codec measurement runs, whatever the UI's own `measuring`
    // flag says (that one is lost on an Activity recreate; the runner is not).
    codecMeasuring: Boolean = false,
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
    customThemeId: String = ThemePalettes.DEFAULT_ID,
    onCustomThemePick: (String) -> Unit = {},
    // ---- System monitor strip (owner request 2026-11-23) ----
    // The top GPU/CPU/RAM/storage strip's visibility; AppNav persists it
    // (SystemMonitorStore) and shows/hides the strip above every screen.
    systemMonitorVisible: Boolean = true,
    onSystemMonitorVisibleChange: (Boolean) -> Unit = {},
    // ---- Auto-hide media player bar (owner request 2026-11-26) ----
    autoHidePlayerBar: Boolean = false,
    onAutoHidePlayerBarChange: (Boolean) -> Unit = {},
    // ---- Scrub frames (F2-C3 wiring) ----
    // The persisted toggle (AppNav reads ScrubFramesStore) and the change
    // callback (AppNav persists + refreshes its state). DEFAULT FALSE
    // (the behavior flag): the wiring ships dark — an untouched
    // project.json keeps the editor's seek drag byte-identical.
    scrubFrames: Boolean = false,
    onScrubFramesChange: (Boolean) -> Unit = {},
    // ---- Proxy storage section (G3, plan P4) ----
    // The pure feed's state + the display-name seam + the delete/clear
    // actions. Null state = the section renders nothing (the feature
    // flag off, or the cache is empty and no clip ever built a proxy —
    // the same honest emptiness the Exports tab has). The actions arrive
    // as nullable so the flag-off page is read-only by construction.
    proxyStorage: com.ritim.ritimcraw.proxy.ProxyStorageState? = null,
    proxyNameFor: (String) -> String? = { null },
    onDeleteProxyClip: ((String) -> Unit)? = null,
    onClearProxyAll: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The Device report row's navigation: AppNav owns the route flag
    // (deviceReportOpen) and the full screen; this screen only forwards.
    Column(
        modifier
            .fillMaxSize()
            // C1: the screen background is colorScheme.surface — the
            // tonal surfaceContainer items sit on the surface role.
            // The app shell's Scaffold paints `background` (its m3
            // default), so this column paints the surface itself.
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(scrollState)
            // Large screens: the groups stop at a readable width, centered
            // (the background above still fills the whole window).
            .readableWidth()
    ) {
        // The shared header (owner request 2026-11-17: "settings'i de
        // diğerleriyle uyumlu hale getir. hepsini library projects
        // exports gibi yap" — every screen title matches those three).
        // The spec's own 32sp SemiBold title is overruled: ScreenHeader
        // gives the same headlineSmall size, the same 16dp start, the
        // same 12/8 vertical rhythm — the same y as the other browse
        // titles. This root Column pads nothing (ScreenHeader's
        // call-site contract), so the title's left edge is the header's
        // own 16dp and the groups below keep their own gutters.
        ScreenHeader(title = stringResource(R.string.settings_title))
        // ---- Playback ----
        SettingsSectionLabel(
            text = stringResource(R.string.settings_section_playback),
            first = true,
        )
        SettingsGroup {
            SettingRowExpandable(
                icon = Eye,
                title = stringResource(R.string.settings_preview_quality),
                // The dims of the selected level (spec hint: the
                // current value, not the old caption under the row).
                hint = if (previewQuality == PreviewQuality.FULL) {
                    stringResource(R.string.settings_preview_quality_hint_full)
                } else {
                    stringResource(
                        R.string.settings_preview_quality_hint,
                        previewQuality.playbackMaxDim,
                        previewQuality.scrubMaxDim,
                    )
                },
                infoContentDescription = stringResource(R.string.settings_preview_quality_about),
                infoNote = stringResource(R.string.settings_preview_quality_info),
                expandedText = stringResource(R.string.settings_expanded),
                collapsedText = stringResource(R.string.settings_collapsed),
                index = 0,
                count = 4,
            ) {
                AppSegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_preview_quality_low),
                        stringResource(R.string.settings_preview_quality_balanced),
                        stringResource(R.string.settings_preview_quality_high),
                        stringResource(R.string.settings_preview_quality_full),
                    ),
                    // Options keep the enum's own order (LOW,
                    // BALANCED, HIGH) — the index maps straight back.
                    selectedIndex = previewQuality.ordinal,
                    onSelect = { index ->
                        onPreviewQualityChange(PreviewQuality.entries[index])
                    },
                )
            }
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
                index = 1,
                count = 4,
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
            SettingRowSwitch(
                icon = PlayerBar,
                title = stringResource(R.string.settings_auto_hide_player_bar),
                hint = if (autoHidePlayerBar) {
                    stringResource(R.string.settings_auto_hide_player_bar_hint_on)
                } else {
                    stringResource(R.string.settings_auto_hide_player_bar_hint_off)
                },
                checked = autoHidePlayerBar,
                onCheckedChange = onAutoHidePlayerBarChange,
                index = 2,
                count = 4,
            )
            // Scrub frames (F2-C3 wiring): the seek drag's cached-frame
            // toggle. Ships OFF (ScrubFramesStore.DEFAULT) — the hint's
            // one line states what a flip does (the switch_row spec's
            // state-dependent hint, the systemMonitor row's shape); the
            // semantics live in the row's info note.
            SettingRowSwitch(
                icon = Filmstrip,
                title = stringResource(R.string.settings_scrub_frames),
                hint = if (scrubFrames) {
                    stringResource(R.string.settings_scrub_frames_hint_on)
                } else {
                    stringResource(R.string.settings_scrub_frames_hint_off)
                },
                checked = scrubFrames,
                onCheckedChange = onScrubFramesChange,
                index = 3,
                count = 4,
            )
        }
                // ---- Storage & device ----
        SettingsSectionLabel(text = stringResource(R.string.settings_section_storage_device))
        SettingsGroup {
            SettingRowNavigation(
                icon = Folder,
                title = stringResource(R.string.settings_gyro_folder),
                // The spec's hint: the folder path only (or "Not
                // set") — the chevron carries the affordance, no
                // "Current:" prefix, no "tap to change" suffix.
                hint = gyroFolderLabel
                    ?: stringResource(R.string.settings_gyro_folder_not_set),
                onClick = onPickGyroFolder,
                index = 0,
                count = 3,
            )
            if (reports.isEmpty()) {
                // The probe's empty placeholder keeps its designed
                // wording and no false affordance (the pre-probe
                // contract): no chevron, no click — the report
                // screen would have nothing to show.
                SettingsGroupItem(index = 1, count = 3) {
                    SettingRowBase(
                        icon = Report,
                        title = stringResource(R.string.settings_device_report),
                        hint = stringResource(R.string.settings_device_report_body),
                        // Single-line non-tappable row: the spec's 14dp
                        // vertical exception (the base default).
                    )
                }
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
                    onClick = onOpenDeviceReport,
                    index = 1,
                    count = 3,
                )
            }
            SettingRowNavigation(
                icon = Report,
                title = stringResource(R.string.settings_licenses),
                hint = stringResource(R.string.settings_licenses_hint),
                onClick = onOpenLicenses,
                index = 2,
                count = 3,
            )
        }
        // ---- Proxy storage (G3, plan section 6 / P4) ----
        // The pure feed's rows: usage against the 20 GiB cap, the per-clip
        // bytes, the over-cap line, delete/clear. Renders NOTHING while the
        // feed is null (the flag-off path) — the same honest emptiness the
        // Exports tab's empty placeholder has.
        if (proxyStorage != null) {
            ProxyStorageSection(
                state = proxyStorage,
                nameFor = proxyNameFor,
                onDeleteClip = onDeleteProxyClip,
                onClearAll = onClearProxyAll,
            )
        }
        // ---- Measure preview performance (debug) + Advanced ----
        // Spec button: outside any group, 56dp pill (extraLarge = 28dp on
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
            if (onGradeSweep != null) {
                OutlinedButton(
                    onClick = onGradeSweep,
                    enabled = !measuring,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_grade_sweep),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Export A/B (guide §A7, 2026-11-23): below the sweep button,
            // same debug block, same one-at-a-time `measuring` gate — one
            // tap = one run over the first gyro-attached clip (AppNav
            // picks it; a clip without a gyro is a toast + log skip there,
            // not here).
            if (onExportAb != null) {
                OutlinedButton(
                    onClick = onExportAb,
                    enabled = !measuring,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_export_ab),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Codec measurement (E1e): below Export A/B, same debug block,
            // same one-at-a-time gate. While a run is in flight the label
            // shows the live run count (progress rides AppNav's state; the
            // button disables itself for the whole session). A finished
            // run's table lives in the Device report — the toast the
            // runner emits points there.
            if (onCodecMeasure != null) {
                OutlinedButton(
                    onClick = onCodecMeasure,
                    enabled = !measuring && !codecMeasuring,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 12.dp, end = 16.dp),
                ) {
                    val (done, total) = codecMeasureProgress
                    Text(
                        text = if ((measuring || codecMeasuring) && total > 0) {
                            stringResource(R.string.settings_codec_measure_running, done, total)
                        } else {
                            stringResource(R.string.settings_codec_measure)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SettingsSectionLabel(text = stringResource(R.string.settings_section_advanced))
            SettingsGroup {
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
                    index = 0,
                    count = 1,
                )
            }
        }
        // ---- Appearance ----
        SettingsSectionLabel(text = stringResource(R.string.settings_section_appearance))
        SettingsGroup {
            // The Theme row (owner request 2026-11-14, spec
            // theme_slider): the compact pill replaces the segmented
            // System/Light/Dark control. The row hint names the slot
            // under the thumb LIVE (during a drag the hint follows
            // the finger; the preference itself is written only on
            // release, so the app never flickers mid-drag).
            // The slider's visual order is 0 = Light, 1 = System,
            // 2 = Dark — the spec's VISUAL order only; the stored
            // enum and its values are untouched, the mapping lives
            // here at the call site.
            // Slot 0 is the custom "C" (owner request 2026-11-25): tap or
            // drag onto it activates the last custom theme; the Theme
            // row itself opens the custom-theme picker.
            val themeSlots = ThemeSelection.slots
            var showThemePicker by remember { mutableStateOf(false) }
            val customName = ThemePalettes.resolve(customThemeId).name
            val slotNames = mapOf(
                ThemeMode.CUSTOM to customName,
                ThemeMode.LIGHT to stringResource(R.string.settings_theme_light),
                ThemeMode.SYSTEM to stringResource(R.string.settings_theme_system),
                ThemeMode.DARK to stringResource(R.string.settings_theme_dark),
            )
            var displaySlot by remember(themeMode) {
                mutableIntStateOf(ThemeSelection.slotOf(themeMode))
            }
            SettingsGroupItem(index = 0, count = 2) {
                SettingRowBase(
                    icon = Contrast,
                    title = stringResource(R.string.settings_theme),
                    hint = slotNames[themeSlots[displaySlot.coerceIn(0, themeSlots.lastIndex)]],
                    // Spec row: the 168dp pill fits the group at readable
                    // widths. The spec's "end padding 12dp (not 16dp)"
                    // was expressed as a NEGATIVE padding on the row —
                    // illegal in Compose (runtime assertion; the owner's
                    // 02:51 Settings crash) — so the row keeps the base
                    // component's own 16dp padding and no offset is
                    // applied: the pill's trailing slot simply sits at
                    // the standard inset.
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            onClickLabel = stringResource(R.string.settings_theme_open_picker),
                        ) { showThemePicker = true },
                    // Single-line row: the spec's 14dp
                    // vertical exception (the base default). The slider
                    // rides the TRAILING slot: SettingRowBase has no
                    // content slot, and its last parameter is
                    // verticalPadding — a trailing lambda would bind
                    // THERE (the compile error this comment replaced).
                    trailing = {
                        ThemeSlider(
                            selectedIndex = ThemeSelection.slotOf(themeMode),
                            onCommit = { slot -> onThemeModeChange(themeSlots[slot]) },
                            onDisplayIndexChange = { slot -> displaySlot = slot },
                            sliderLabel = stringResource(R.string.settings_theme_slider),
                            previousLabel = stringResource(R.string.settings_theme_previous),
                            nextLabel = stringResource(R.string.settings_theme_next),
                            stateLabels = listOf(
                                stringResource(R.string.settings_theme_custom),
                                stringResource(R.string.settings_theme_light),
                                stringResource(R.string.settings_theme_system),
                                stringResource(R.string.settings_theme_dark),
                            ),
                        )
                    },
                )
            }
            if (showThemePicker) {
                CustomThemePickerDialog(
                    currentId = customThemeId,
                    customActive = themeMode == ThemeMode.CUSTOM,
                    onPick = { id ->
                        onCustomThemePick(id)
                        showThemePicker = false
                    },
                    onDismiss = { showThemePicker = false },
                )
            }
            SettingRowSwitch(
                icon = Gauge,
                title = stringResource(R.string.settings_system_monitor),
                hint = if (systemMonitorVisible) {
                    stringResource(R.string.settings_system_monitor_hint_on)
                } else {
                    stringResource(R.string.settings_system_monitor_hint_off)
                },
                checked = systemMonitorVisible,
                onCheckedChange = onSystemMonitorVisibleChange,
                index = 1,
                count = 2,
            )
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
}
