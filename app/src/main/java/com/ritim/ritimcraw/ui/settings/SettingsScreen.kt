package com.ritim.ritimcraw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.CodecProbe
import com.ritim.ritimcraw.engine.CodecReport
import com.ritim.ritimcraw.engine.PreviewMetrics
import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.theme.Tnum

/**
 * Settings screen (DESIGN.md Settings): the device report. The codec probe
 * runs on device only; until then the placeholder body stays and the report
 * list renders empty (probe returns nothing on non-Android JVMs and the
 * screen keeps the designed placeholder).
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
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Shared header (owner request 2026-11-11d: consistent top area
        // across the main screens) — same title, same compact rhythm as
        // Library/Exports; no toolbar actions on this screen.
        ScreenHeader(title = stringResource(R.string.settings_title))
        // Preview quality row (owner request 2026-11-11b section 3): the
        // headline + supporting text state the tradeoff; the segmented row
        // carries the choice. Each button's label line shows the dims in
        // tabular numerals (DESIGN.md Numbers) so the tradeoff is visible,
        // not just named. The picker changes ONLY playback + scrub — the
        // supporting text says so (paused frames always settle to the
        // clip's own resolution).
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_preview_quality)) },
            supportingContent = {
                Text(stringResource(R.string.settings_preview_quality_body))
            },
        )
        val qualityOptions = listOf(
            Triple(R.string.settings_preview_quality_low, R.string.settings_preview_quality_dims, PreviewQuality.LOW),
            Triple(R.string.settings_preview_quality_balanced, R.string.settings_preview_quality_dims, PreviewQuality.BALANCED),
            Triple(R.string.settings_preview_quality_high, R.string.settings_preview_quality_dims, PreviewQuality.HIGH),
        )
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            qualityOptions.forEachIndexed { index, (labelRes, dimsRes, level) ->
                SegmentedButton(
                    selected = previewQuality == level,
                    onClick = { onPreviewQualityChange(level) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = qualityOptions.size,
                    ),
                ) {
                    Text(stringResource(labelRes))
                    Text(
                        stringResource(
                            dimsRes,
                            level.playbackMaxDim,
                            level.scrubMaxDim,
                        ),
                        style = MaterialTheme.typography.labelMedium.merge(Tnum),
                    )
                }
            }
        }
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
        if (reports.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(reports, key = { it.name + it.mime }) { report ->
                    ListItem(
                        headlineContent = { Text(CodecProbe.formatReport(report)) },
                    )
                }
            }
        } else {
            Column(Modifier.weight(1f)) { }
        }
        HorizontalDivider()
        Text(
            stringResource(R.string.settings_version, versionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
