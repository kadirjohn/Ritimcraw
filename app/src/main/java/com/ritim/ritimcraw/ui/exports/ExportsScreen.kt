package com.ritim.ritimcraw.ui.exports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.ui.theme.Tnum

/**
 * Exports screen (DESIGN.md Export job): job rows with the format line, DONE
 * and CANCELLED stamps, and a failed card naming the stage and cause with
 * "Nothing was written" and a Retry action. Jobs stay until cleared.
 *
 * The engine wires in the device pass; this build renders whatever jobs the
 * [ExportStore][com.ritim.ritimcraw.export.ExportStore] holds.
 */
@Composable
fun ExportsScreen(
    jobs: List<ExportJob> = emptyList(),
    onRetry: (ExportJob) -> Unit = {},
    onClear: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            stringResource(R.string.exports_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        if (jobs.isEmpty()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    stringResource(R.string.exports_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.exports_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    ExportJobRow(job, onRetry)
                }
            }
            TextButton(
                onClick = onClear,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(vertical = 4.dp),
            ) {
                Text(stringResource(R.string.exports_clear))
            }
        }
    }
}

@Composable
private fun ExportJobRow(
    job: ExportJob,
    onRetry: (ExportJob) -> Unit,
) {
    if (job.state == State.FAILED) {
        FailedJobCard(job, onRetry)
    } else {
        ListItem(
            headlineContent = { Text(job.sourceId) },
            supportingContent = {
                Column {
                    // Format line on every non-failed row (DESIGN.md Export job).
                    Text(
                        stringResource(
                            if (job.target == com.ritim.ritimcraw.export.Target.DNG) {
                                R.string.exports_format_dng
                            } else {
                                R.string.exports_format_mp4
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (job.state) {
                        State.QUEUED ->
                            Text(stringResource(R.string.exports_job_queued))
                        State.RUNNING -> {
                            // Frame counts when the exporter has reported
                            // (tabular numerals per DESIGN.md), else the
                            // existing indeterminate line — the count can be
                            // absent briefly between RUNNING and the first
                            // throttled progress write.
                            val frames = job.framesDone
                            val total = job.framesTotal
                            if (frames != null && total != null && total > 0) {
                                Text(
                                    stringResource(
                                        R.string.exports_job_running_frames, frames, total
                                    ),
                                    // Frame counts are numbers: tnum keeps the
                                    // digits from jittering as they tick up.
                                    style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                                )
                            } else {
                                Text(stringResource(R.string.exports_job_running))
                            }
                        }
                        State.DONE ->
                            // DONE stamp with the output name (detail on a DONE
                            // job is the output file name).
                            Text(
                                stringResource(
                                    R.string.exports_job_done_named,
                                    job.detail ?: "",
                                )
                            )
                        State.CANCELLED ->
                            Text(stringResource(R.string.exports_job_cancelled))
                        State.FAILED -> Unit // handled by FailedJobCard
                    }
                }
            },
        )
    }
}

@Composable
private fun FailedJobCard(
    job: ExportJob,
    onRetry: (ExportJob) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.exports_job_failed_stage, job.sourceId),
                style = MaterialTheme.typography.titleMedium,
            )
            if (job.detail != null) {
                Text(
                    stringResource(R.string.exports_job_failed_cause, job.detail),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Text(
                stringResource(R.string.exports_job_failed_written),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Button(onClick = { onRetry(job) }) {
                    Text(stringResource(R.string.exports_job_retry))
                }
            }
        }
    }
}
