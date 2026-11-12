package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradePreset
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
    onExport: (Target) -> Unit,
    exportEnabled: Boolean,
    clipInfo: ClipInfo,
    // RAW-11 Frame Picker: opens the screen's frame picker sheet from the
    // clip-info sheet's action row. Default no-op keeps call sites without
    // a picker working.
    onDevelopFrame: () -> Unit = {},
    // ---- Copy/Paste settings + Presets (owner request 2026-11-11 §4) ----
    // The clip's CURRENT grade — what "Copy settings" copies and "Save as
    // preset" snapshots. The screen owns the grade state; the sheet only
    // reads it.
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
    // ---- Manual rotation (owner request 2026-11-11 §5) ----
    // Rotates the clip 90° per tap: +90 (right/clockwise), -90
    // (left/counter-clockwise). The screen persists it per clip through
    // SourceRepository.setManualRotation (normalized 0..359) and applies
    // only this DELTA to the preview — the native develop already renders
    // container-upright pixels, so the display never double-rotates.
    // Default no-op keeps call sites without rotation working.
    onRotate: (byDeg: Int) -> Unit = {},
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
            onExport = { target ->
                exportSheetOpen = false
                onExport(target)
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
            onRotate = onRotate,
            onDismiss = { infoSheetOpen = false },
        )
    }
}

/**
 * Export choices (design review: "MP4 video + DNG kare; mevcut kuyruk /
 * bildirim korunur"): the same two actions the old FlowRow buttons
 * carried — MP4 video and DNG frame — now in the top-bar sheet. Each
 * tap dismisses the sheet and calls the screen's onExport(Target),
 * which enqueues the job exactly as before.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(
    onExport: (Target) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            stringResource(R.string.editor_export_sheet_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_export_choice_mp4)) },
            supportingContent = { Text(stringResource(R.string.exports_format_mp4)) },
            modifier = Modifier.clickable { onExport(Target.MP4) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_export_choice_dng)) },
            supportingContent = {
                // The DNG write format, separate from the RAW source info
                // (owner request 2026-11-11 section 2): the export always
                // writes 16-bit uncompressed CFA — that is the writer's
                // contract, not the sensor's bit depth.
                Text(
                    stringResource(R.string.exports_format_dng) + " · " +
                        stringResource(R.string.exports_format_dng_detail)
                )
            },
            modifier = Modifier.clickable { onExport(Target.DNG) },
        )
        Spacer(Modifier.height(16.dp))
    }
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
    onRotate: (byDeg: Int) -> Unit,
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
        // Two actions, 90° per tap, in the clip-info sheet (NOT on the
        // preview surface — the preview stays gesture-clean for §6). The
        // sheet stays open so the user sees the preview rotate behind it
        // and can keep tapping; material-icons-core has no rotate glyphs,
        // so these are text ListItems exactly like the "Develop frame"
        // row's shape. The supporting line names the direction in words.
        if (info.manualRotationDeg != 0) {
            // The applied delta (hide-when-default, the rawSampleFormat
            // pattern): only a rotated clip shows its manual delta. Tabular
            // numerals per DESIGN.md (values).
            ClipInfoRow(
                label = stringResource(R.string.editor_clip_info_rotation),
                value = "${info.manualRotationDeg}°",
            )
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_rotate_left)) },
            supportingContent = {
                Text(stringResource(R.string.editor_rotate_left_detail))
            },
            modifier = Modifier.clickable { onRotate(-90) },
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.editor_rotate_right)) },
            supportingContent = {
                Text(stringResource(R.string.editor_rotate_right_detail))
            },
            modifier = Modifier.clickable { onRotate(90) },
        )
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
    /**
     * The clip's persisted MANUAL rotation delta in degrees, 0/90/180/270
     * (owner request 2026-11-11 §5). 0 is the never-rotated default; the
     * preview rotates only this delta (the native develop already renders
     * container-upright pixels) and every export composes it with the
     * container orientation through RotationState.effectiveDeg.
     */
    val manualRotationDeg: Int = 0,
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
