package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.util.Format

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
 * from the timestamps, else "Unknown").
 */
@Composable
fun EditorTopBar(
    sourceId: String,
    onBack: () -> Unit,
    onExport: (Target) -> Unit,
    exportEnabled: Boolean,
    clipInfo: ClipInfo,
) {
    var exportSheetOpen by remember { mutableStateOf(false) }
    var infoSheetOpen by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 4.dp, vertical = 4.dp),
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
            supportingContent = { Text(stringResource(R.string.exports_format_dng)) },
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipInfoSheet(
    info: ClipInfo,
    onDismiss: () -> Unit,
) {
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
    AssistChip(
        onClick = onRefresh,
        enabled = canRender,
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
