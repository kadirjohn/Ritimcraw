package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.CinemaDngSheetState

/**
 * The CinemaDNG sequence export section (night E-F, docs/
 * CINEMADNG_EXPORT_PLAN.md R1-2, the sheet's CINEMA_DNG tab body —
 * "görsel doğrulanmadı", the owner checks it on device in the morning):
 *
 *   - the title + the RAW note (one honest line: colors and effects are
 *     NOT applied — the sequence is the raw CFA, for DaVinci Resolve);
 *   - the folder row: a Pick button + the selected folder's label (the
 *     GyroTree label rule — the content:// string never shows);
 *   - the estimate row: "<frames> frames · ~<size>" from the pure
 *     [CinemaDngSheetState.estimateText] (the plan's own numbers, never
 *     the request's);
 *   - the warn/lock line when the storage rule fires (the pure
 *     [CinemaDngSheetState.verdict] decides the wording).
 *
 * (Night I5) The enqueue-time storage gate: the SHEET measures the
 * picked folder's free space when the section opens (the probe lives in
 * the sheet, off the main thread) and hands the number in as
 * [freeBytes]; the section renders the pure verdict's own line —
 * locked (the error accent, the IssueBand precedent), warn, or the
 * honest "free space unknown" wording. The Start button's gate reads
 * the SAME verdict (the sheet's own cinemaCanStart), so the line and
 * the button can never disagree.
 *
 * THE ANIMATION RULE (the owner's brief): no new motion language. The
 * rows are static (the SettingRow precedent); nothing animates in this
 * section — the sheet's own tab swap is the existing AnimatedContent
 * (ExportSettingsPanel.kt:122-131, tween(220)-class), and no
 * expand/collapse lives here (SettingsComponents' expandVertically+
 * fadeIn pattern was the alternative; a section this short needs none).
 *
 * Touch targets: the folder row is a 56dp-min row (the SettingRow
 * shape), the buttons ride their own 48dp minimums.
 */
@Composable
internal fun CinemaDngSection(
    clipInfo: ClipInfo,
    folderUri: String?,
    folderLabel: String?,
    freeBytes: Long,
    onPickFolder: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        // Title + the raw note (one line, onSurfaceVariant — the honest
        // scope statement: no grade, no effects, for Resolve).
        Text(
            stringResource(R.string.cinema_dng_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            stringResource(R.string.cinema_dng_raw_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // The folder row: label + Pick (the sheet's SettingRow shape —
        // a 56dp-min row with a trailing action, never a new style).
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { onPickFolder() }
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.cinema_dng_folder),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    // The selected folder's label (the GyroTree rule), or
                    // the honest not-set wording — never a content:// string.
                    folderLabel ?: stringResource(R.string.cinema_dng_folder_not_set),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(R.drawable.ic_folder),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The estimate row (the plan's own numbers).
        val estimate = CinemaDngSheetState.estimateText(
            clipFrameCount = clipInfo.frameCount,
            firstFrame = 0,
            lastFrame = -1,
            width = clipInfo.width,
            height = clipInfo.height,
        )
        if (estimate != null) {
            Text(
                stringResource(R.string.cinema_dng_estimate, estimate),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        // The unknown-duration rule (the plan's own): no per-frame write
        // has been measured, so the section never shows a time estimate.
        Text(
            stringResource(R.string.cinema_dng_time_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )

        // (Night I5) The enqueue-time storage verdict's own line: the
        // pure [CinemaDngSheetState.verdict] -> [verdictMessage] mapping
        // decides IF a line shows and WHAT it says; the section only
        // renders it (the locked case takes the sheet's error accent —
        // the IssueBand precedent; warn/unknown stay advisory colors).
        // A null message shows nothing (a measured, roomy folder needs
        // no line — the estimate row already said the size).
        val verdict = CinemaDngSheetState.verdict(
            folderUri = folderUri,
            clipFrameCount = clipInfo.frameCount,
            firstFrame = 0,
            lastFrame = -1,
            width = clipInfo.width,
            height = clipInfo.height,
            freeBytes = freeBytes,
        )
        CinemaDngSheetState.verdictMessage(verdict)?.let { line ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = if (CinemaDngSheetState.isErrorLine(verdict)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(line.messageRes, *line.args.toTypedArray()),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (CinemaDngSheetState.isErrorLine(verdict)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // The Pick action (a stock OutlinedButton, 48dp min — the
        // secondary-action shape; Start is the sheet's own sticky button).
        OutlinedButton(
            onClick = onPickFolder,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .heightIn(min = 48.dp),
        ) {
            Text(
                if (folderUri == null) {
                    stringResource(R.string.cinema_dng_pick_folder)
                } else {
                    stringResource(R.string.cinema_dng_change_folder)
                }
            )
        }
    }
}
