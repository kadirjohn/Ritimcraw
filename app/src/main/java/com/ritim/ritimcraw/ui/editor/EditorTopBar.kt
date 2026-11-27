package com.ritim.ritimcraw.ui.editor

import android.media.MediaCodecList
import android.media.MediaFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.ritim.ritimcraw.export.DeviceEncoderFacts
import com.ritim.ritimcraw.export.EncoderFacts
import com.ritim.ritimcraw.export.ExportFps
import com.ritim.ritimcraw.export.HevcProbeSource
import com.ritim.ritimcraw.export.ExportSettings
import com.ritim.ritimcraw.export.ExportSettingsPrefs
import com.ritim.ritimcraw.export.ExportSheetModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.BackendRules
import com.ritim.ritimcraw.export.NativeGpuDevelop
import com.ritim.ritimcraw.data.GradePreset
import com.ritim.ritimcraw.data.GyroTree
import com.ritim.ritimcraw.export.CinemaDngSheetState
import com.ritim.ritimcraw.export.ExportResolution
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.ui.components.AppSegmentedControl
import com.ritim.ritimcraw.util.Format

/**
 * The top bar's pinned height (owner request 2026-11-11d section 7: a
 * compact 52-56dp toolbar). Public because the editor's panel anchor
 * math (ClipEditorScreen, section 11) subtracts the top bar from the
 * measured column — a named constant, not a magic 56, keeps the two
 * places honest. The height is pinned (not heightIn) so the toolbar is
 * deterministic: the preview region below never shifts when the
 * filename wraps or the buttons change.
 */
val EditorTopBarHeight = 56.dp

/**
 * Fixed editor top bar (design review 2026-11-10 "Onerilen ilk
 * yerlesim"): back arrow + single-line clip name + ONE Export icon
 * button + a "..." overflow. The three big buttons leave the prime area
 * (the review's core complaint) — the export targets move into a
 * ModalBottomSheet opened from here, so the sheet reuses the screen's
 * existing onExport(Target) wiring (queue + snackbar semantics
 * unchanged: AppNav enqueues the job with the CURRENT grade snapshot).
 *
 * The clip name: MediaSource has no display-name field, so [sourceId]
 * is the fallback the review anticipated ("tek satır dosya adı" — the
 * id IS the stored name today). Single line with ellipsis.
 *
 * The "..." overflow opens the clip-info sheet (phase 1 scope: file
 * name, resolution, duration, frame count; FPS shows when derivable
 * from the timestamps, else "Unknown"). The sheet also carries the
 * Frame Picker entry (RAW-11): a "Develop frame" action row that hands
 * control to the screen — the transport position (frame number +
 * timecode) lives in the screen, not here, so the screen opens its own
 * picker sheet.
 */
@Composable
fun EditorTopBar(
    sourceId: String,
    onRenameClip: (String) -> Boolean = { false },
    onBack: () -> Unit,
    // (Export resolution, owner request 2026-11-13) the widened export
    // callback: the target plus the RESOLVED resolution bound the sheet's
    // chip showed (null pair = Original = the source's effective size,
    // exactly what every export before the picker produced). DNG/LINEAR
    // taps pass nulls — those targets have no resolution seam; the
    // MP4 tap passes the selected entry's bound.
    onExport: (Target, Int?, Int?, ExportSettings?) -> Unit,
    exportEnabled: Boolean,
    // (E0) an encoded (Mi-Log / MP4) clip: the export sheet offers MP4 only
    // (DNG/Linear DNG are RAW deliverables), and 10-bit is not offered (the
    // encoded decode path is CPU in v1).
    encodedSource: Boolean = false,
    // (E1c) how fast this device exported last time, for the sheet's time estimate; null = no estimate yet.
    exportRate: ExportSheetModel.Rate? = null,
    clipInfo: ClipInfo,
    // (Export resolution) the clip's persisted manual rotation delta —
    // the ladder's effective size composes it (RotationState.effectiveDeg
    // + rotatedDims, the exporter's own chain), so the chips show the
    // size the encode will really produce. Default 0 keeps call sites
    // that never rotate working.
    manualRotationDeg: Int = 0,
    // RAW-11 Frame Picker: opens the screen's frame picker sheet from the
    // clip-info sheet's action row. Default no-op keeps call sites without
    // a picker working.
    onDevelopFrame: () -> Unit = {},
    // ---- Copy/Paste settings + Presets (owner request 2026-11-11 §4) ----
    // The clip's CURRENT grade — what "Copy settings" copies and "Save as
    // preset" snapshots. The screen owns the grade state; the sheet only
    // reads it. (Export resolution: the grade's crop fractions feed the
    // ladder's effective size too.)
    grade: GradeK = GradeK(),
    // True when the in-memory clipboard holds a copied grade (the paste
    // row hides without one — see GradeClipboard's process-death note).
    canPaste: Boolean = false,
    // Copies the current grade into the clipboard (the screen wires
    // GradeClipboard.copy).
    onCopySettings: () -> Unit = {},
    // Applies the clipboard's grade (or a preset's grade) through the
    // screen's onGradeChange path — the exact same write a slider move
    // makes, so persistence and the live preview behave identically.
    onApplyGrade: (GradeK) -> Unit = {},
    // The saved presets (AppNav: PresetStore.all(), refreshed after every
    // save/delete so the rows stay current).
    presets: List<GradePreset> = emptyList(),
    // Saves the current grade under a name (AppNav: PresetStore.put +
    // refresh). Returns the saved preset.
    onSavePreset: (name: String) -> Unit = {},
    // Removes a preset by id (AppNav: PresetStore.remove + refresh).
    onRemovePreset: (id: String) -> Unit = {},
    // ---- Grade undo/redo (feature N7) ----
    // The screen's history gates the buttons: canUndo/canRedo are the
    // GradeHistory snapshot reads (reactive), and the callbacks apply the
    // popped grade through the screen's own grade-write path — the same
    // one a slider move uses — WITHOUT recording, so undo never clears
    // the redo stack. Defaults keep call sites without history working.
    canUndo: Boolean = false,
    canRedo: Boolean = false,
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    // ---- CinemaDNG sequence export (night E-F) ----
    // The sequence's queue action: a job whose cinemaDng snapshot is set
    // routes to the sequence exporter (a marker field, not a Target — the
    // persisted-format pattern). Default no-op keeps every call site
    // without the feature working.
    onExportCinemaDng: (com.ritim.ritimcraw.export.CinemaDngSettings) -> Unit = {},
) {
    var exportSheetOpen by remember { mutableStateOf(false) }
    var infoSheetOpen by remember { mutableStateOf(false) }
    // Owner request 2026-11-11d section 7: a compact toolbar of
    // approximately 52-56dp. The height is pinned (not heightIn) so the
    // toolbar is deterministic — the preview region below never shifts
    // when the filename wraps or the buttons change. The 48dp IconButtons
    // center inside it; the filename keeps weight(1f) + ellipsis so a long
    // name can never push Export or the overflow off-screen.
    Row(
        Modifier
            .fillMaxWidth()
            .height(EditorTopBarHeight)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.editor_back),
            )
        }
        Text(
            text = sourceId,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 8.dp),
        )
        // Grade undo/redo (feature N7): two always-composed IconButtons
        // after the name, disabled at the history's bounds — the same
        // 48dp IconButton convention as Export, vendored glyphs (see
        // ic_undo). The name keeps weight(1f) + ellipsis, so the five
        // buttons can never push each other off-screen.
        IconButton(onClick = onUndo, enabled = canUndo) {
            Icon(
                painterResource(R.drawable.ic_undo),
                contentDescription = stringResource(R.string.editor_undo),
            )
        }
        IconButton(onClick = onRedo, enabled = canRedo) {
            Icon(
                painterResource(R.drawable.ic_redo),
                contentDescription = stringResource(R.string.editor_redo),
            )
        }
        IconButton(
            onClick = { exportSheetOpen = true },
            enabled = exportEnabled,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ExitToApp,
                contentDescription = stringResource(R.string.editor_export),
            )
        }
        IconButton(onClick = { infoSheetOpen = true }) {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.editor_more),
            )
        }
    }
    if (exportSheetOpen) {
        ExportSheet(
            clipInfo = clipInfo,
            grade = grade,
            manualRotationDeg = manualRotationDeg,
            exportRate = exportRate,
            encodedSource = encodedSource,
            onExport = { target, resolutionW, resolutionH, settings ->
                exportSheetOpen = false
                onExport(target, resolutionW, resolutionH, settings)
            },
            onExportCinemaDng = onExportCinemaDng,
            onDevelopFrame = {
                exportSheetOpen = false
                onDevelopFrame()
            },
            onDismiss = { exportSheetOpen = false },
        )
    }
    if (infoSheetOpen) {
        ClipInfoSheet(
            info = clipInfo,
            grade = grade,
            canPaste = canPaste,
            presets = presets,
            onCopySettings = onCopySettings,
            onApplyGrade = onApplyGrade,
            onSavePreset = onSavePreset,
            onRemovePreset = onRemovePreset,
            onDevelopFrame = {
                infoSheetOpen = false
                onDevelopFrame()
            },
            currentName = sourceId,
            onRenameClip = onRenameClip,
            onDismiss = { infoSheetOpen = false },
        )
    }
}

/**
 * Export choices (design review: "MP4 video + DNG kare; mevcut kuyruk /
 * bildirim korunur"): the same actions the old FlowRow buttons carried —
 * MP4 video and the two DNG contracts — now in the top-bar sheet. Each
 * tap dismisses the sheet and calls the screen's widened onExport(Target,
 * resolutionW, resolutionH), which enqueues the job exactly as before.
 *
 * ---- The resolution row (owner request 2026-11-13: "export ederken 4K,
 * FHD vs cozunurluk ya da orijinal videonun cozunurlugune gore bir oneri
 * yok") ----
 *
 * MP4-only, and labeled so: DNG/PHOTO/LINEAR have no resolution seam —
 * they develop pixels, they do not encode a frame size. The row shows
 * the ladder ([ExportResolution.ladder] over the clip's effective size —
 * the recorded dims composed with manual rotation and the grade's crop,
 * the exporter's own chain) as chips: Original (the RECOMMENDED default,
 * star-marked — the source-based recommendation the owner asked for),
 * UHD/FHD/HD with their RESOLVED size and honest scale flag, and Custom
 * (two small number fields). Selection is local to the sheet; the MP4
 * tap hands the SELECTED entry's bound to the job, which re-resolves it
 * against the true effective size at run time (the bound travels; the
 * exporter resolves), so a container rotation the sheet could not see
 * still lands honestly.
 *
 * CONTAINER-ORIENTATION GAP, documented honestly: the ladder here
 * computes with orientationDeg = 0 because the editor's session does
 * not expose the container's rotation (only the native MP4 session
 * reads it, at export time). For a rotated-container clip the chips can
 * therefore show transposed dims; the job carries the RESOLVED BOUND
 * (not the kind), and the exporter re-resolves against the true
 * effective size, so the ENCODE is always the honest fits-within
 * result. The chip label is the only thing that can be off, and only
 * for rotated-container clips.
 *
 * HONEST DEGRADE: when the clip's dims are unknown (0), there is no
 * ladder to show — the row hides rather than faking sizes, and the MP4
 * tap exports Original (nulls), exactly what every export before the
 * picker produced.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ExportSheet(
    clipInfo: ClipInfo,
    grade: GradeK,
    manualRotationDeg: Int,
    exportRate: ExportSheetModel.Rate?,
    // (E0) true = this clip is encoded (Mi-Log / MP4): the sheet offers MP4
    // only and 10-bit stays off (see the tabs list and the context below).
    encodedSource: Boolean = false,
    onExport: (Target, Int?, Int?, ExportSettings?) -> Unit,
    // (Night E-F) the sequence's queue action (the marker field's queue
    // route; default no-op keeps every call site without the feature).
    onExportCinemaDng: (com.ritim.ritimcraw.export.CinemaDngSettings) -> Unit = {},
    onDevelopFrame: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The selection state lives here, not in the caller: the sheet is the
    // only consumer, and a dismissal forgets the choice (a reopen starts
    // at Original, the recommended default — never a stale pick).
    // (E5, night D2) — with one exception: the saved settings' own
    // resolution pair (the SOCIAL preset's 1080×1920) is restored on
    // open, so a Social export reopens showing Social's bound, not a
    // stale Original beside a Social preset row.
    var selectedKind by remember { mutableStateOf(ExportResolution.Kind.ORIGINAL) }
    var customWidthText by remember { mutableStateOf("") }
    var customHeightText by remember { mutableStateOf("") }
    var customDialog by remember { mutableStateOf(false) }
    // (E1c) The MP4 settings start from the LAST ones the user exported with
    // (a per-viewer convenience, SharedPreferences); the job snapshots its
    // own copy at queue time.
    val context = LocalContext.current
    var settings by remember { mutableStateOf(ExportSettingsPrefs.load(context)) }
    LaunchedEffect(Unit) {
        val s = settings
        if (s.resolutionW != null && s.resolutionH != null) {
            selectedKind = ExportResolution.Kind.CUSTOM
            customWidthText = s.resolutionW.toString()
            customHeightText = s.resolutionH.toString()
        }
    }
    // The device's encoder facts load off the UI thread; until they arrive
    // the plan is by MIME (no encoder named) and nothing is claimed.
    val facts by produceState(emptyList<EncoderFacts>()) {
        value = withContext(Dispatchers.Default) { DeviceEncoderFacts.all() }
    }
    val dimsKnown = clipInfo.width > 0 && clipInfo.height > 0
    // What the encode will be: the selected entry's bound, else the effective size.
    val bound = if (dimsKnown) {
        resolvedBound(clipInfo, grade, manualRotationDeg, selectedKind, customWidthText, customHeightText)
    } else {
        null
    }
    // (E5, night D2) The settings' resolution pair mirrors the ladder's
    // resolved bound, so the SOCIAL preset's identity stays honest: a
    // bound that resolves to 1080×1920 keeps SOCIAL listed as Social, a
    // bound that resolves to anything else (or Original) drops it to
    // Custom. One source of truth per direction, never a loop: a preset
    // application drives the ladder, the user's own ladder pick drives
    // the settings.
    fun syncResolutionToSettings(kind: ExportResolution.Kind) {
        val b = if (dimsKnown && kind != ExportResolution.Kind.ORIGINAL) {
            resolvedBound(clipInfo, grade, manualRotationDeg, kind, customWidthText, customHeightText)
        } else {
            null
        }
        settings = settings.copy(resolutionW = b?.first, resolutionH = b?.second)
    }
    val subject = if (dimsKnown) {
        val size = bound ?: ExportResolution.effectiveSourceSize(
            clipInfo.width, clipInfo.height, 0, manualRotationDeg,
            grade.cropX, grade.cropY, grade.cropW, grade.cropH,
        ).let { Pair(it.width, it.height) }
        ExportSheetModel.Subject(
            width = size.first and 1.inv(),
            height = size.second and 1.inv(),
            fps = if (clipInfo.fps > 0f) ExportFps.snap(clipInfo.fps.toDouble()) else 30.0,
            durationSec = clipInfo.totalUs / 1_000_000.0,
            // E1f-2: the source's REAL frame count feeds the conversion's
            // count rule; 0 (unknown) leaves the summary on the source's
            // own facts, never a guess.
            frames = clipInfo.frameCount,
        )
    } else {
        null
    }
    // (E1f-2) The summary answers the movie the encode will really make:
    // a chosen target converts the subject to the OUTPUT's own facts
    // (the target's exact rate, the floor+1 count, the last-grid-point +
    // one-period duration). SOURCE — and any unknown frame count — keeps
    // the subject untouched, byte-identical to the pre-E1f sheet.
    val summarySubject = subject?.let { ExportSheetModel.converted(it, settings.targetFps) }
    // (E1d) 10-bit availability for THIS export: the device offers an
    // RGBA1010102 EGL config (a throwaway native probe, off the UI thread),
    // the size is Original, nothing is cropped/straightened, and every grade
    // stage runs on the GPU. The exporter re-checks it at run time.
    val tenBitProbe by produceState(false) {
        value = withContext(Dispatchers.Default) {
            try {
                NativeGpuDevelop.tenBitProbe()
            } catch (e: Exception) {
                false
            }
        }
    }
    val gradeOnGpu = remember(grade) {
        // (E0) an encoded clip develops on the CPU (v1), so the 10-bit
        // GPU path is never available for it - the setting stays honest.
        !encodedSource &&
            BackendRules.fragmentUncoveredStages(grade, true).isEmpty() &&
            BackendRules.fragmentUncoveredPreStages(grade).isEmpty()
    }
    val exportContext = ExportSheetModel.tenBitContext(
        probeOk = tenBitProbe,
        originalSize = bound == null,
        cropFull = CropState.isFull(CropState.RectF(grade.cropX, grade.cropY, grade.cropW, grade.cropH)),
        straightenZero = grade.straightenDeg == 0f,
        gradeOnGpu = gradeOnGpu,
    ).let { ctx ->
        // G4 (E2 wiring): the HLG gate's probed facts for the subject's
        // shape — the same fold the export runs (HevcProbeSource), from
        // the facts the sheet already loaded. Null (no facts / no
        // subject) keeps the E1 wall and the row's honest "not available"
        // state; the fold never queries the device itself.
        val gate = subject?.let { s ->
            val f = facts
            if (f.isEmpty()) null else HevcProbeSource.fromFacts(
                f, s.width, s.height, s.fps, android.os.Build.VERSION.SDK_INT,
            )
        }
        ctx.copy(hlgGate = gate)
    }
    val plan = subject?.let { ExportSheetModel.plan(settings, it, facts, exportContext) }

    var tab by remember { mutableStateOf(ExportSheetState.Tab.MP4) }
    // (E0) an encoded (Mi-Log / MP4) clip offers MP4 only: DNG and Linear
    // DNG are RAW deliverables and v1 has no encoded frame picker, so
    // their tabs are gone, not disabled. (Night E-F) CINEMA_DNG is
    // MCRAW-only too — an encoded clip has no CFA to sequence.
    val tabs = if (encodedSource) {
        listOf(ExportSheetState.Tab.MP4)
    } else {
        ExportSheetState.Tab.entries.toList()
    }
    // (Night E-F) the CinemaDNG section's own folder pick: the persisted
    // SAF tree grant, the SAME launcher shape the gyro folder uses
    // (OpenDocumentTree + takePersistableUriPermission with the write
    // flag — a sequence WRITES into the folder, so the grant needs
    // FLAG_GRANT_WRITE_URI_PERMISSION too). The URI lives in the sheet's
    // state and travels into the job's cinemaDng snapshot at queue time.
    var cinemaFolderUri by remember { mutableStateOf<String?>(null) }
    // (night I5) The enqueue-time free-space probe: the CinemaDNG
    // section opens -> the picked folder is measured ONCE, off the main
    // thread (StatFs + the resolver query are disk IO; the sheet's own
    // produceState precedent runs probes off the UI thread, and disk
    // work rides Dispatchers.IO — the AppNav trimFolder pattern). The
    // measured value (or -1, unmeasurable — a removable provider)
    // feeds the pure verdict, so the lock/warn line shows BEFORE the
    // queue accepts. The probe re-runs when the folder pick changes
    // (produceState keyed on the URI); no polling, no re-query on tab
    // swaps (a stale number is honest for as long as the sheet is open
    // — the sheet's own dismissal-forget rule). The probe runs
    // unconditionally in composition (never inside an if — Compose's
    // own rule); the lambda skips the IO when nothing is picked.
    val cinemaFreeBytes by produceState(
        initialValue = -1L,
        key1 = cinemaFolderUri,
    ) {
        val uri = cinemaFolderUri
        value = if (uri == null) {
            -1L
        } else {
            withContext(Dispatchers.IO) {
                try {
                    com.ritim.ritimcraw.export.CinemaDngFolder(
                        context, uri,
                    ).freeBytes()
                } catch (e: Exception) {
                    -1L
                }
            }
        }
    }
    val cinemaFolderPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user backed out
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Non-fatal: the export will fail honestly at run time if the
            // grant really is unusable (the same contract as a lost clip
            // grant — the folder stays selected and the user can re-pick).
        }
        cinemaFolderUri = uri.toString()
    }
    // P2: the sheet opens full height (no half-way stop) so the default list fits without scrolling.
    // The sub-page lives here so the system back can close it first. The
    // sheet's own back-dismiss is switched off (it ran before any handler
    // inside the content and sent the owner straight to the timeline); the
    // one handler below closes a sub-page, and only from the list hides the sheet.
    var page by remember { mutableStateOf<ExportPage?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetScope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
    ) {
        BackHandler {
            if (page != null) {
                page = null
            } else {
                sheetScope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            // The scrolling part; the action below it stays put (sticky). The
            // settings list (P2) owns the target choice too: it is the Format row.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
            ) {
                val resolutionUi = if (dimsKnown) {
                    rememberResolutionUi(clipInfo, grade, manualRotationDeg, selectedKind, bound)
                } else {
                    null
                }
                ExportBody(
                    tab = tab,
                    tabs = tabs,
                    onTab = { tab = it },
                    encodedSource = encodedSource,
                    settings = settings,
                    onSettings = { s ->
                        settings = s
                        // (E5, night D2) The SOCIAL preset carries its
                        // vertical 1080×1920 pair IN the settings; the
                        // sheet's own resolution state follows it so the
                        // two never disagree (the preset identity needs
                        // the pair, the encode needs the sheet's bound —
                        // one source of truth, shown twice). A settings
                        // pair that cleared (any other preset) returns
                        // the ladder to Original.
                        if (s.resolutionW != null && s.resolutionH != null) {
                            selectedKind = ExportResolution.Kind.CUSTOM
                            customWidthText = s.resolutionW.toString()
                            customHeightText = s.resolutionH.toString()
                        } else if (selectedKind == ExportResolution.Kind.CUSTOM) {
                            selectedKind = ExportResolution.Kind.ORIGINAL
                        }
                    },
                    plan = plan,
                    subject = summarySubject,
                    facts = facts,
                    rate = exportRate,
                    tenBitAvailable = exportContext.tenBitPathAvailable,
                    resolution = resolutionUi,
                    onPickResolution = { kind ->
                        selectedKind = kind
                        syncResolutionToSettings(kind)
                    },
                    onCustomResolution = { customDialog = true },
                    onUseResolution = { w, h ->
                        selectedKind = ExportResolution.Kind.CUSTOM
                        customWidthText = w.toString()
                        customHeightText = h.toString()
                        syncResolutionToSettings(ExportResolution.Kind.CUSTOM)
                    },
                    page = page,
                    onPage = { page = it },
                    // (E5-B, night G1) The clip's own audio description
                    // for the audio-format row's Source value (0/0 =
                    // unknown; the sheet never guesses a number).
                    clipAudioRate = clipInfo.audioSampleRateHz,
                    clipAudioChannels = clipInfo.audioChannels,
                    sourceFps = if (clipInfo.fps > 0f) clipInfo.fps.toDouble() else null,
                    targetDetail = {
                        // The non-MP4 targets' descriptions (also the Format sub-page's hints).
                        when (tab) {
                            ExportSheetState.Tab.MP4 -> {}
                            ExportSheetState.Tab.DNG -> TargetDetail(
                                R.string.editor_export_choice_dng,
                                stringResource(R.string.exports_format_dng) + " · " +
                                    stringResource(R.string.exports_format_dng_detail),
                            )
                            ExportSheetState.Tab.LINEAR -> TargetDetail(
                                R.string.editor_export_choice_dng_linear,
                                stringResource(R.string.exports_format_dng_linear) + " · " +
                                    stringResource(R.string.exports_format_dng_linear_detail),
                            )
                            ExportSheetState.Tab.PHOTO -> TargetDetail(
                                R.string.exports_format_photo,
                                stringResource(R.string.export_photo_detail),
                            )
                            // (Night E-F) the CinemaDNG section: the
                            // folder/estimate/warn rows + the raw note. The
                            // section is its own body (no MP4 plan rows
                            // apply — the sequence has no encoder plan).
                            // (Night I5) the measured free space rides in:
                            // the pure verdict turns it into the
                            // lock/warn/unknown line the section renders.
                            ExportSheetState.Tab.CINEMA_DNG -> CinemaDngSection(
                                clipInfo = clipInfo,
                                folderUri = cinemaFolderUri,
                                folderLabel = cinemaFolderUri?.let { GyroTree.folderLabel(it) },
                                freeBytes = cinemaFreeBytes,
                                onPickFolder = { cinemaFolderPicker.launch(null) },
                            )
                        }
                    },
                )
                if (customDialog) {
                    CustomResolutionDialog(
                        initialWidth = customWidthText,
                        initialHeight = customHeightText,
                        onDismiss = { customDialog = false },
                        onUse = { w, h ->
                            customDialog = false
                            selectedKind = ExportResolution.Kind.CUSTOM
                            customWidthText = w
                            customHeightText = h
                            syncResolutionToSettings(ExportResolution.Kind.CUSTOM)
                        },
                    )
                }
            }
            HorizontalDivider()
            // The one action of the chosen target, always visible. MP4 queues
            // the job with the SELECTED bound and a snapshot of the settings; a
            // plan the device cannot make (ERROR) cannot be queued - its issue
            // says why and offers the fix. CINEMA_DNG queues the sequence job
            // with the folder + range snapshot (the button's gate is the
            // section's storage verdict — a LOCK refuses here too).
            val cinemaCanStart = if (tab == ExportSheetState.Tab.CINEMA_DNG && !encodedSource) {
                CinemaDngSheetState.canStart(
                    CinemaDngSheetState.verdict(
                        folderUri = cinemaFolderUri,
                        clipFrameCount = clipInfo.frameCount,
                        firstFrame = 0,
                        lastFrame = -1,
                        width = clipInfo.width,
                        height = clipInfo.height,
                        // (Night I5) the section's own measured free space
                        // (the I/O probe above): a real number locks/warns
                        // at ENQUEUE time now; unknown (-1, the SAF folder
                        // could not be measured) never locks — the plan's
                        // honesty rule, and the run-time gate stays the
                        // backstop.
                        freeBytes = cinemaFreeBytes,
                    )
                )
            } else {
                true
            }
            Button(
                onClick = {
                    when (tab) {
                        ExportSheetState.Tab.MP4 -> {
                            ExportSettingsPrefs.save(context, settings)
                            onExport(Target.MP4, bound?.first, bound?.second, settings)
                        }
                        ExportSheetState.Tab.DNG -> onExport(Target.DNG, null, null, null)
                        ExportSheetState.Tab.LINEAR -> onExport(Target.LINEAR, null, null, null)
                        ExportSheetState.Tab.PHOTO -> onDevelopFrame()
                        ExportSheetState.Tab.CINEMA_DNG -> {
                            // The sequence job's snapshot: the folder pick,
                            // the whole-clip default range, the exact source
                            // fps rational (the FrameRate tag's own value —
                            // the clip's measured rate snapped to the common
                            // grid, 30000/1001 for 29.97, never a 1e6
                            // approximation), the default start timecode,
                            // and the clip facts the plan needs. The grade
                            // is snapshotted by the queue like every job.
                            val fps = if (clipInfo.fps > 0f) clipInfo.fps.toDouble() else 30.0
                            val (num, den) = cinemaFpsRational(fps)
                            onExportCinemaDng(
                                com.ritim.ritimcraw.export.CinemaDngSettings(
                                    folderUri = cinemaFolderUri ?: "",
                                    firstFrame = 0,
                                    lastFrame = -1,
                                    fpsNum = num,
                                    fpsDen = den,
                                    clipFrameCount = clipInfo.frameCount,
                                    frameWidth = clipInfo.width,
                                    frameHeight = clipInfo.height,
                                    audioSampleRate = 0, // resolved at run time (the honest no-audio skips the WAV)
                                    audioChannels = 0,
                                )
                            )
                        }
                    }
                },
                enabled = (tab != ExportSheetState.Tab.MP4 || plan?.ok != false) && cinemaCanStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
            ) {
                Text(
                    stringResource(
                        when (tab) {
                            ExportSheetState.Tab.MP4 -> R.string.editor_export_mp4
                            ExportSheetState.Tab.DNG -> R.string.editor_export_dng
                            ExportSheetState.Tab.LINEAR -> R.string.export_action_linear
                            ExportSheetState.Tab.PHOTO -> R.string.export_photo_action
                            ExportSheetState.Tab.CINEMA_DNG -> R.string.cinema_dng_action
                        }
                    )
                )
            }
        }
    }
}

/**
 * (Night E-F) The exact fps rational the FrameRate tag carries: the
 * common rate families map to their canonical pairs (29.97 -> 30000/
 * 1001), everything else keeps the measured integer rate with den 1.
 * Pure; JVM-tested (the sheet's one call site, the plan's "kare hızı
 * kaynak hızı" rule).
 */
internal fun cinemaFpsRational(fps: Double): Pair<Int, Int> = when {
    kotlin.math.abs(fps - 24000.0 / 1001.0) < 1e-6 -> Pair(24000, 1001)
    kotlin.math.abs(fps - 24000.0) < 1e-6 -> Pair(24, 1)
    kotlin.math.abs(fps - 25000.0 / 1001.0) < 1e-6 -> Pair(25000, 1001)
    kotlin.math.abs(fps - 25.0) < 1e-6 -> Pair(25, 1)
    kotlin.math.abs(fps - 30000.0 / 1001.0) < 1e-6 -> Pair(30000, 1001)
    kotlin.math.abs(fps - 30000.0) < 1e-6 -> Pair(30, 1)
    kotlin.math.abs(fps - 48000.0 / 1001.0) < 1e-6 -> Pair(48000, 1001)
    kotlin.math.abs(fps - 48.0) < 1e-6 -> Pair(48, 1)
    kotlin.math.abs(fps - 50000.0 / 1001.0) < 1e-6 -> Pair(50000, 1001)
    kotlin.math.abs(fps - 50.0) < 1e-6 -> Pair(50, 1)
    kotlin.math.abs(fps - 60000.0 / 1001.0) < 1e-6 -> Pair(60000, 1001)
    kotlin.math.abs(fps - 60.0) < 1e-6 -> Pair(60, 1)
    else -> {
        val n = kotlin.math.round(fps).toInt().coerceAtLeast(1)
        Pair(n, 1)
    }
}

/** The test seam for [cinemaFpsRational] (the JVM suite's export-package test). */
internal fun cinemaFpsRationalForTest(fps: Double): Pair<Int, Int> = cinemaFpsRational(fps)

/** A non-MP4 target's one-glance description (title + the file contract). */
@Composable
private fun TargetDetail(title: Int, detail: String) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
        Text(
            detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The resolution choices for the export sheet's settings list (P2): the row's
 * value ("Original 3840×2160") and the sub-page's entries with their flags
 * (recommended, upscale/downscale, not supported). Entries that resolve to
 * the same size as an earlier one are merged
 * ([ExportSheetState.resolutionOptions]: a UHD source shows Original only).
 * Custom is appended by the page and opens [CustomResolutionDialog]; the
 * typed pair is a BOUND, not a forcing - the resolve preserves the source
 * aspect.
 */
@Composable
private fun rememberResolutionUi(
    clipInfo: ClipInfo,
    grade: GradeK,
    manualRotationDeg: Int,
    selectedKind: ExportResolution.Kind,
    bound: Pair<Int, Int>?,
): ResolutionUi {
    // The effective size: the recorded dims composed with manual rotation and
    // the grade's crop - the exporter's own chain, so the entries show the
    // size the encode will really produce. The container orientation gap is
    // documented on ExportSheet.
    val source = ExportResolution.effectiveSourceSize(
        clipInfo.width, clipInfo.height,
        0, manualRotationDeg,
        grade.cropX, grade.cropY, grade.cropW, grade.cropH,
    )
    // fps feeds ONLY the codec seam (a rate question needs a rate); an unknown
    // fps (<= 0) skips gating. The seam is queried ONCE per sheet open.
    val fps = if (clipInfo.fps > 0f) clipInfo.fps.toDouble() else 0.0
    val codec = remember(clipInfo.fps) {
        if (clipInfo.fps > 0f) deviceCodecSupport() else null
    }
    val options = remember(source, fps, codec) {
        ExportSheetState.resolutionOptions(ExportResolution.ladder(source, fps, codec))
    }
    val originalName = stringResource(R.string.editor_export_resolution_original)
    val customName = stringResource(R.string.editor_export_resolution_custom)
    val recommendedLabel = stringResource(R.string.editor_export_recommended)
    val upscaleLabel = stringResource(R.string.editor_export_upscale)
    val downscaleLabel = stringResource(R.string.editor_export_downscale)
    fun nameOf(kind: ExportResolution.Kind) = when (kind) {
        ExportResolution.Kind.ORIGINAL -> originalName
        ExportResolution.Kind.UHD -> "UHD"
        ExportResolution.Kind.FHD -> "FHD"
        ExportResolution.Kind.HD -> "HD"
        ExportResolution.Kind.CUSTOM -> customName
    }
    // The row's value: the chosen name and size (a kind merged away shows Original).
    val valueText = when {
        selectedKind == ExportResolution.Kind.CUSTOM ->
            bound?.let { "$customName ${ExportSheetState.size(it.first, it.second)}" } ?: customName
        else -> options.firstOrNull { it.kind == selectedKind }
            ?.let { "${nameOf(it.kind)} ${ExportSheetState.size(it.width, it.height)}" }
            ?: "$originalName ${ExportSheetState.size(source.width, source.height)}"
    }
    val choices = options.map { choice ->
        val flags = buildList {
            if (choice.recommended) add(recommendedLabel)
            when (choice.scale) {
                ExportResolution.Scale.UPSCALE -> add(upscaleLabel)
                ExportResolution.Scale.DOWNSCALE -> add(downscaleLabel)
                ExportResolution.Scale.EQUAL -> {}
            }
        }
        ResolutionChoiceUi(
            kind = choice.kind,
            title = "${nameOf(choice.kind)} ${ExportSheetState.size(choice.width, choice.height)}",
            hint = flags.joinToString(" · ").ifEmpty { null },
            supported = choice.supported,
        )
    }
    return ResolutionUi(valueText, selectedKind, choices)
}

/** The Custom resolution dialog: two number fields; blank/invalid keeps Use disabled (never a guessed bound). */
@Composable
private fun CustomResolutionDialog(
    initialWidth: String,
    initialHeight: String,
    onDismiss: () -> Unit,
    onUse: (String, String) -> Unit,
) {
    var w by remember { mutableStateOf(initialWidth) }
    var h by remember { mutableStateOf(initialHeight) }
    val valid = (w.toIntOrNull() ?: 0) > 0 && (h.toIntOrNull() ?: 0) > 0
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_custom_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.export_custom_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = w,
                        onValueChange = { w = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.editor_export_custom_width)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = h,
                        onValueChange = { h = it.filter { c -> c.isDigit() }.take(5) },
                        label = { Text(stringResource(R.string.editor_export_custom_height)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onUse(w, h) }, enabled = valid) { Text(stringResource(R.string.export_custom_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.export_custom_cancel)) }
        },
    )
}

/**
 * The device codec seam (the [ExportResolution.CodecSupport] wiring the
 * pure rules leave to the caller): the H.264 encoders' verdict on
 * w x h at fps, via MediaCodecList (the CodecProbe precedent —
 * REGULAR_CODECS, video/avc). Device-only API: a failure here must not
 * break the sheet, so it degrades to null (no gating — the encode-time
 * MediaCodec rejection stays the honest backstop). The seam is consulted
 * once per sheet open, not per frame.
 */
private fun deviceCodecSupport(): ExportResolution.CodecSupport? {
    return try {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val encoders = list.codecInfos.filter { info ->
            info.isEncoder && info.supportedTypes.any { it == MediaFormat.MIMETYPE_VIDEO_AVC }
        }
        if (encoders.isEmpty()) return null
        // Any encoder accepting the size/rate supports it (the writer picks
        // its own encoder; the seam only answers "does one exist").
        ExportResolution.CodecSupport { w, h, fps ->
            encoders.any { info ->
                runCatching {
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .videoCapabilities?.areSizeAndRateSupported(w, h, fps)
                }.getOrNull() == true
            }
        }
    } catch (e: Exception) {
        // Device-only API (JVM previews, exotic builds): no seam, no gating.
        null
    }
}

/**
 * The selected entry's resolved bound (what the MP4 tap carries to the
 * job): null pair for Original (the pre-picker behavior), the resolved
 * w x h for a fixed entry, and for Custom the typed pair resolved through
 * outputSize — INVALID/blank input degrades to null (Original), never a
 * guessed bound. The exporter re-resolves against the true effective
 * size at run time; what travels is the BOUND.
 */
private fun resolvedBound(
    clipInfo: ClipInfo,
    grade: GradeK,
    manualRotationDeg: Int,
    kind: ExportResolution.Kind,
    customWidthText: String,
    customHeightText: String,
): Pair<Int, Int>? {
    if (clipInfo.width <= 0 || clipInfo.height <= 0) return null
    val source = ExportResolution.effectiveSourceSize(
        clipInfo.width, clipInfo.height,
        0, manualRotationDeg,
        grade.cropX, grade.cropY, grade.cropW, grade.cropH,
    )
    return when (kind) {
        ExportResolution.Kind.ORIGINAL -> null
        else -> {
            if (kind == ExportResolution.Kind.CUSTOM) {
                val w = customWidthText.toIntOrNull() ?: return null
                val h = customHeightText.toIntOrNull() ?: return null
                if (w <= 0 || h <= 0) return null
            }
            val out = ExportResolution.outputSize(
                kind, source,
                customWidthText.toIntOrNull() ?: 0,
                customHeightText.toIntOrNull() ?: 0,
            )
            Pair(out.width, out.height)
        }
    }
}

/**
 * The MP4 row's supporting line: the selected entry's resolved size
 * (the tap-time echo of the chip above), or null when there is no honest
 * answer (Custom with blank fields).
 */
private fun resolutionSummary(
    clipInfo: ClipInfo,
    grade: GradeK,
    manualRotationDeg: Int,
    kind: ExportResolution.Kind,
    customWidthText: String,
    customHeightText: String,
): String? {
    val bound = resolvedBound(
        clipInfo, grade, manualRotationDeg,
        kind, customWidthText, customHeightText,
    ) ?: return null
    return "${bound.first}×${bound.second}"
}

/** One clip-info row: label + value, value falls back to "Unknown". */
@Composable
private fun ClipInfoRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(value) },
    )
}

/**
 * Clip info (review phase 1: "kayıtlı dosya adı, çözünürlük, süre, kare
 * sayısı, varsa doğrulanmış FPS"): the stored name, import-time stats,
 * and the transport-derived duration/frame count. FPS derives from the
 * real timestamps when they exist (frames / duration); 0-count or
 * non-positive inputs show "Unknown" rather than fake numbers.
 *
 * The sheet's ACTIONS (owner request 2026-11-11 §4, plus the RAW-11
 * Frame Picker entry): "Copy settings" fills the in-memory clipboard;
 * "Paste settings" (present only when the clipboard holds a grade) and
 * each preset row apply a grade through the screen's onGradeChange
 * path — the same write a slider move makes. "Save as preset" opens a
 * name dialog (M3 TextField inside this ModalBottomSheet) and snapshots
 * the current grade into the PresetStore; each preset row carries a
 * delete affordance. The "Develop frame" row hands control back to the
 * screen, which opens its own picker sheet over the transport position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipInfoSheet(
    info: ClipInfo,
    grade: GradeK,
    canPaste: Boolean,
    presets: List<GradePreset>,
    onCopySettings: () -> Unit,
    onApplyGrade: (GradeK) -> Unit,
    onSavePreset: (String) -> Unit,
    onRemovePreset: (String) -> Unit,
    onDevelopFrame: () -> Unit,
    currentName: String,
    onRenameClip: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    var saveDialogOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    val unknown = stringResource(R.string.editor_clip_info_unknown)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.editor_clip_info),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ClipInfoRow(
            label = stringResource(R.string.editor_clip_info_name),
            value = info.name.ifBlank { unknown },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_rename_clip)) },
            supportingContent = { Text(stringResource(R.string.editor_rename_clip_detail)) },
            leadingContent = {
                Icon(
                    painterResource(R.drawable.ic_edit),
                    contentDescription = null,
                    modifier = Modifier.heightIn(max = 24.dp),
                )
            },
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = androidx.compose.ui.semantics.Role.Button) { renameOpen = true },
        )
        ClipInfoRow(
            label = stringResource(R.string.editor_clip_info_resolution),
            value = if (info.width > 0 && info.height > 0) {
                "${info.width}×${info.height}"
            } else {
                unknown
            },
        )
        ClipInfoRow(
            label = stringResource(R.string.editor_clip_info_duration),
            value = if (info.totalUs > 0) Format.formatDuration(info.totalUs) else unknown,
        )
        ClipInfoRow(
            label = stringResource(R.string.editor_clip_info_frames),
            value = if (info.frameCount > 0) info.frameCount.toString() else unknown,
        )
        ClipInfoRow(
            label = stringResource(R.string.editor_clip_info_fps),
            value = if (info.fps > 0f) {
                // One decimal only when needed: 24 fps reads "24", 29.97
                // reads "29.97" (Locale.ROOT — a number stays a number in
                // every locale, same convention as Format).
                val rounded = (info.fps * 100).toInt() / 100f
                if (rounded % 1f == 0f) {
                    rounded.toInt().toString()
                } else {
                    String.format(java.util.Locale.ROOT, "%.2f", rounded)
                }
            } else {
                unknown
            },
        )
        // RAW source rows (owner request 2026-11-11 section 2): what the
        // source actually is — the sample format and the RAW white level —
        // kept SEPARATE from the DNG write format (16-bit CFA, the export
        // sheet's line). MCRAW-only; encoded clips have no RAW stage, so
        // the rows are absent there rather than showing "Unknown".
        if (info.rawSampleFormat != null) {
            ClipInfoRow(
                label = stringResource(R.string.editor_clip_info_raw_format),
                value = info.rawSampleFormat,
            )
        }
        if (info.rawWhiteLevel > 0) {
            ClipInfoRow(
                label = stringResource(R.string.editor_clip_info_raw_white_level),
                value = info.rawWhiteLevel.toString(),
            )
        }
        // ---- Manual rotation (owner request 2026-11-11 §5) ----
        // The rotate actions MOVED (owner device feedback 2026-11-13):
        // the Frame tab's rotate pair is their only home now — this sheet
        // is read-only clip facts and grade settings actions. The Frame
        // tab keeps the same onRotate route (AppNav's
        // RotationState.applyManual + setManualRotation persistence).
        //
        // ---- Settings actions (owner request 2026-11-11 §4) ----
        // Copy fills the clipboard; Paste applies it. Both are plain
        // ListItems like the export sheet's rows — the sheet stays open
        // so the user sees the grade land in the sliders.
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_copy_settings)) },
            supportingContent = {
                Text(stringResource(R.string.editor_preset_save_detail))
            },
            leadingContent = {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.heightIn(max = 24.dp),
                )
            },
            modifier = Modifier.clickable { onCopySettings() },
        )
        if (canPaste) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.editor_paste_settings)) },
                supportingContent = {
                    Text(stringResource(R.string.editor_paste_settings_detail))
                },
                leadingContent = {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        modifier = Modifier.heightIn(max = 24.dp),
                    )
                },
                modifier = Modifier.clickable { onApplyGrade(GradeClipboard.paste() ?: grade) },
            )
        }
        // ---- Presets (owner request 2026-11-11 §4) ----
        // "Save as preset" opens the name dialog below; the saved rows
        // apply with one tap and remove with the trailing delete button.
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_preset_save)) },
            supportingContent = {
                Text(stringResource(R.string.editor_preset_save_detail))
            },
            leadingContent = {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.heightIn(max = 24.dp),
                )
            },
            modifier = Modifier.clickable { saveDialogOpen = true },
        )
        if (presets.isEmpty()) {
            Text(
                stringResource(R.string.editor_presets_empty),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            Text(
                stringResource(R.string.editor_presets_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            presets.forEach { preset ->
                ListItem(
                    headlineContent = { Text(preset.name) },
                    // The preset's grade travels with its LUT selection:
                    // applying onto a clip whose library lacks that cube
                    // keeps the id (renders as no-LUT until re-imported —
                    // see GradeClipboard/GradePreset docs), never crashes.
                    modifier = Modifier.clickable { onApplyGrade(preset.grade) },
                    trailingContent = {
                        // Resolved OUTSIDE the semantics block: its lambda
                        // is not composable (the refresh chip's pattern).
                        val deleteDescription =
                            stringResource(R.string.editor_preset_delete)
                        IconButton(
                            onClick = { onRemovePreset(preset.id) },
                            modifier = Modifier.semantics {
                                contentDescription = deleteDescription
                            },
                        ) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                            )
                        }
                    },
                )
            }
        }
        // Frame Picker entry (RAW-11): the sheet's one action, visually
        // separated from the read-only rows above by the sheet's own
        // spacing — a clickable ListItem like the export sheet's rows.
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_frame_picker_title)) },
            supportingContent = {
                Text(stringResource(R.string.editor_frame_picker_photo_clip))
            },
            modifier = Modifier.clickable { onDevelopFrame() },
        )
        Spacer(Modifier.height(16.dp))
    }
    if (renameOpen) {
        com.ritim.ritimcraw.ui.library.RenameClipDialog(
            currentName = currentName,
            onRename = onRenameClip,
            onDismiss = { renameOpen = false },
        )
    }
    if (saveDialogOpen) {
        PresetNameDialog(
            onSave = { name ->
                saveDialogOpen = false
                onSavePreset(name)
            },
            onDismiss = { saveDialogOpen = false },
        )
    }
}

/**
 * "Save as preset" naming (owner request 2026-11-11 §4): an M3
 * OutlinedTextField inside a ModalBottomSheet (DESIGN.md: bottom sheets
 * over dialogs), Save disabled until the name is non-blank. The name is
 * the user's own text and is stored/displayed verbatim — never localized
 * (the labels around it are).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PresetNameDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.editor_preset_save),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.editor_preset_name_label)) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_preset_cancel))
            }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(stringResource(R.string.editor_preset_save_action))
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Clip facts for the info sheet (JVM-pure data holder). */
data class ClipInfo(
    val name: String,
    val width: Int,
    val height: Int,
    val totalUs: Long,
    val frameCount: Int,
    val fps: Float,
    /**
     * The RAW sample format straight from the source when the clip is
     * MCRAW (e.g. "raw16"); null on encoded clips (no RAW stage — the row
     * hides) and when the source did not report one.
     */
    val rawSampleFormat: String? = null,
    /**
     * The RAW white level the source reports (e.g. 1023); 0 = unknown
     * (the row hides). The DNG write format is NOT this: the export sheet
     * says "16-bit CFA" separately.
     */
    val rawWhiteLevel: Int = 0,
    /**
     * (E5-B, night G1) The clip's own audio sample rate in Hz from the
     * import metadata (MetadataStore's AudioSummary); 0 = unknown (the
     * clip has no audio, or the record carries no audio facts). The
     * export sheet's sample-rate row shows this as the Source value and
     * the reshape hook needs it never — the exporter reads the REAL
     * description from the native session at run time; this is the
     * SHEET's honest display fact only.
     */
    val audioSampleRateHz: Int = 0,
    /** [audioSampleRateHz]'s pair; 0 = unknown. */
    val audioChannels: Int = 0,
)

/**
 * The small corner refresh control (design review: "manuel 'Render
 * preview' kocaman butonlardan cikar... kucuk, etiketli bir kontrol,
 * mevcut durumu gosterir"): an AssistChip pinned over the preview's
 * corner. The contentDescription carries the CURRENT status from the
 * existing PreviewState data (Ready / Rendering / Error), the tap
 * re-renders the current transport position at the full paused tier
 * (the old "Render preview" button's onClick, unchanged), and the chip
 * stays enabled exactly when the render path can run (canRender).
 */
@Composable
fun PreviewRefreshControl(
    state: PreviewState,
    canRender: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusText = when (state) {
        PreviewState.RENDERING -> stringResource(R.string.editor_preview_state_rendering)
        PreviewState.FAILED -> stringResource(R.string.editor_preview_state_error)
        // IDLE and FRAME both mean "not rendering": the preview either
        // waits for its first render or shows a good frame — either way
        // the control reads Ready and a tap (re)renders.
        else -> stringResource(R.string.editor_preview_state_ready)
    }
    // Resolved OUTSIDE the semantics block: its lambda is not composable.
    val statusDescription = stringResource(R.string.editor_refresh_preview_state, statusText)
    // Scrim behind the chip (owner finding 2026-11-11): over overexposed
    // near-white footage the white glyph/label of ALL preview corner
    // controls vanished into the frame. The chip's default container is
    // Transparent, so it gets the same translucent surfaceContainerLowest
    // scrim (roles, never hex — DESIGN.md; the histogram overlay's
    // backdrop is the precedent) as the fullscreen and histogram toggles,
    // via containerColor so the scrim follows the chip's own shape — no
    // wrapping Surface, corners match automatically. Also when disabled:
    // a disabled chip still sits over the frame and must stay legible.
    val scrim = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f)
    AssistChip(
        onClick = onRefresh,
        enabled = canRender,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = scrim,
            disabledContainerColor = scrim,
        ),
        label = { Text(stringResource(R.string.editor_refresh_preview)) },
        leadingIcon = {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.heightIn(max = 18.dp),
            )
        },
        modifier = modifier.semantics { contentDescription = statusDescription },
    )
}
