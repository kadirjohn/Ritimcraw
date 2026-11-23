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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.ritim.ritimcraw.export.ExportResolution
import com.ritim.ritimcraw.export.Target
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
    encodedSource: Boolean = false,
    onExport: (Target, Int?, Int?, ExportSettings?) -> Unit,
    onDismiss: () -> Unit,
) {
    // The selection state lives here, not in the caller: the sheet is the
    // only consumer, and a dismissal forgets the choice (a reopen starts
    // at Original, the recommended default — never a stale pick).
    var selectedKind by remember { mutableStateOf(ExportResolution.Kind.ORIGINAL) }
    var customWidthText by remember { mutableStateOf("") }
    var customHeightText by remember { mutableStateOf("") }
    // (E1c) The MP4 settings start from the LAST ones the user exported with
    // (a per-viewer convenience, SharedPreferences); the job snapshots its
    // own copy at queue time.
    val context = LocalContext.current
    var settings by remember { mutableStateOf(ExportSettingsPrefs.load(context)) }
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
        )
    } else {
        null
    }
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
    )
    val plan = subject?.let { ExportSheetModel.plan(settings, it, facts, exportContext) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.editor_export_sheet_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            // ---- The resolution row (MP4-only) ----
            // The ladder needs a usable source size; 0 dims (unknown) hide the
            // row entirely — the honest degrade, never a fake ladder.
            if (dimsKnown) {
                ResolutionRow(
                    clipInfo = clipInfo,
                    grade = grade,
                    manualRotationDeg = manualRotationDeg,
                    selectedKind = selectedKind,
                    customWidthText = customWidthText,
                    customHeightText = customHeightText,
                    onSelect = { kind -> selectedKind = kind },
                    onCustomWidth = { customWidthText = it },
                    onCustomHeight = { customHeightText = it },
                )
            }
            // ---- The MP4 settings (E1c): presets, the produced summary, issues, Advanced ----
            if (subject != null && plan != null) {
                ExportSettingsPanel(
                    settings = settings,
                    onSettings = { settings = it },
                    plan = plan,
                    subject = subject,
                    facts = facts,
                    rate = exportRate,
                    tenBitAvailable = exportContext.tenBitPathAvailable,
                    onUseResolution = { w, h ->
                        selectedKind = ExportResolution.Kind.CUSTOM
                        customWidthText = w.toString()
                        customHeightText = h.toString()
                    },
                )
            }
            // The Export MP4 action: queues the job with the SELECTED bound and
            // a snapshot of the settings; a plan the device cannot make (ERROR)
            // cannot be queued - its issue says why and offers the fix.
            Button(
                onClick = {
                    ExportSettingsPrefs.save(context, settings)
                    onExport(Target.MP4, bound?.first, bound?.second, settings)
                },
                enabled = plan?.ok != false,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.editor_export_mp4))
            }
            if (!encodedSource) HorizontalDivider(Modifier.padding(vertical = 4.dp))
            if (!encodedSource) ListItem(
                headlineContent = { Text(stringResource(R.string.editor_export_choice_dng)) },
                supportingContent = {
                    // The DNG write format, separate from the RAW source info
                    // (owner request 2026-11-11 section 2): the export always
                    // writes 16-bit uncompressed CFA — that is the writer's
                    // contract, not the sensor's bit depth. No resolution
                    // seam: the DNG develops pixels, it does not encode a
                    // frame size — the nulls are honest, not a fallback.
                    Text(
                        stringResource(R.string.exports_format_dng) + " · " +
                            stringResource(R.string.exports_format_dng_detail)
                    )
                },
                modifier = Modifier.clickable { onExport(Target.DNG, null, null, null) },
            )
            if (!encodedSource) ListItem(
                headlineContent = { Text(stringResource(R.string.editor_export_choice_dng_linear)) },
                supportingContent = {
                    // RAW-13: the second DNG file contract — the demosaiced,
                    // scene-referred linear deliverable, next to the raw
                    // mosaic. Same no-seam honesty as the raw DNG above.
                    Text(
                        stringResource(R.string.exports_format_dng_linear) + " · " +
                            stringResource(R.string.exports_format_dng_linear_detail)
                    )
                },
                modifier = Modifier.clickable { onExport(Target.LINEAR, null, null, null) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * The ladder's chips (Original/UHD/FHD/HD + Custom). Each chip's label
 * carries its RESOLVED size and honest flag; the recommended Original is
 * star-marked; an upscale is EXPLICITLY marked (never the default); a
 * codec-refused entry renders disabled with the reason. Custom opens two
 * small number fields below the chips.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResolutionRow(
    clipInfo: ClipInfo,
    grade: GradeK,
    manualRotationDeg: Int,
    selectedKind: ExportResolution.Kind,
    customWidthText: String,
    customHeightText: String,
    onSelect: (ExportResolution.Kind) -> Unit,
    onCustomWidth: (String) -> Unit,
    onCustomHeight: (String) -> Unit,
) {
    // The effective size: the recorded dims composed with manual
    // rotation and the grade's crop — the exporter's own chain, so the
    // chips show the size the encode will really produce. The container
    // orientation gap is documented on ExportSheet.
    val source = ExportResolution.effectiveSourceSize(
        clipInfo.width, clipInfo.height,
        0, manualRotationDeg,
        grade.cropX, grade.cropY, grade.cropW, grade.cropH,
    )
    // fps feeds ONLY the codec seam (a rate question needs a rate); an
    // unknown fps (<= 0) skips gating entirely — pass null, the honest
    // "cannot ask a rate question without a rate". The seam is queried
    // ONCE per sheet open (remember, no keys: the device's encoders do
    // not change while the sheet is up, and a fresh instance would
    // invalidate the ladder memo below on every chip tap).
    val fps = if (clipInfo.fps > 0f) clipInfo.fps.toDouble() else 0.0
    val codec = remember(clipInfo.fps) {
        if (clipInfo.fps > 0f) deviceCodecSupport() else null
    }
    val ladder = remember(source, fps, codec) {
        ExportResolution.ladder(source, fps, codec)
    }
    // Resolved OUTSIDE the semantics blocks: their lambdas are not
    // composable (the refresh chip's pattern).
    val recommendedLabel = stringResource(R.string.editor_export_recommended)
    val upscaleLabel = stringResource(R.string.editor_export_upscale)
    val downscaleLabel = stringResource(R.string.editor_export_downscale)
    val unsupportedLabel = stringResource(R.string.editor_export_unsupported)

    Text(
        stringResource(R.string.editor_export_resolution_title),
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ladder.forEach { choice ->
            // The chip label: name + resolved size. The recommended entry
            // carries the star; an upscale carries its honest flag; a
            // refusal disables the entry (Original is never codec-gated).
            val name = when (choice.kind) {
                ExportResolution.Kind.ORIGINAL ->
                    stringResource(R.string.editor_export_resolution_original)
                ExportResolution.Kind.UHD -> "UHD"
                ExportResolution.Kind.FHD -> "FHD"
                ExportResolution.Kind.HD -> "HD"
                ExportResolution.Kind.CUSTOM -> stringResource(R.string.editor_export_resolution_custom)
            }
            val label = "$name ${choice.width}×${choice.height}"
            val description = buildString {
                append(label)
                if (choice.recommended) append(". $recommendedLabel")
                when (choice.scale) {
                    ExportResolution.Scale.UPSCALE -> append(". $upscaleLabel")
                    ExportResolution.Scale.DOWNSCALE -> append(". $downscaleLabel")
                    ExportResolution.Scale.EQUAL -> {}
                }
                if (!choice.supported) append(". $unsupportedLabel")
            }
            FilterChip(
                selected = selectedKind == choice.kind,
                onClick = { onSelect(choice.kind) },
                label = { Text(label) },
                leadingIcon = if (choice.recommended) {
                    {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.heightIn(max = 18.dp),
                        )
                    }
                } else {
                    null
                },
                enabled = choice.supported,
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
        // Custom rides the same FlowRow as a chip; its fields open below.
        val customSelected = selectedKind == ExportResolution.Kind.CUSTOM
        FilterChip(
            selected = customSelected,
            onClick = { onSelect(ExportResolution.Kind.CUSTOM) },
            label = { Text(stringResource(R.string.editor_export_resolution_custom)) },
        )
    }
    if (selectedKind == ExportResolution.Kind.CUSTOM) {
        // Two small number fields (the preset-name dialog's OutlinedTextField
        // shape, keyboard-numeric): the typed pair is a BOUND, not a forcing
        // — the resolve preserves the source aspect (no crop, no stretch).
        // Invalid/blank input keeps the Custom chip selected but the MP4
        // tap exports Original (the honest degrade, never a guessed bound).
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = customWidthText,
                onValueChange = { text ->
                    onCustomWidth(text.filter { it.isDigit() }.take(5))
                },
                label = { Text(stringResource(R.string.editor_export_custom_width)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = customHeightText,
                onValueChange = { text ->
                    onCustomHeight(text.filter { it.isDigit() }.take(5))
                },
                label = { Text(stringResource(R.string.editor_export_custom_height)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
    }
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
    onDismiss: () -> Unit,
) {
    var saveDialogOpen by remember { mutableStateOf(false) }
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
