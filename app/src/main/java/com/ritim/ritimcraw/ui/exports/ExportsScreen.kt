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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R

/** Export job states (sprint plan Task 5; UI now, engine wires in Task 4+). */
enum class ExportState { QUEUED, RUNNING, DONE, CANCELLED, FAILED }

/**
 * One export job as the Exports screen renders it. Errors carry stage, cause
 * and a recovery line (DESIGN.md Error states: name the stage, the cause,
 * and the recovery — never a bare "failed").
 */
data class ExportJob(
    val id: String,
    val clipId: String,
    val state: ExportState,
    val progressPercent: Int = 0,
    val failedStage: String? = null,
    val failedCause: String? = null,
)

/**
 * Exports screen (DESIGN.md Exports): job rows with progress, and a failed
 * card naming the stage and cause with a Retry action.
 */
@Composable
fun ExportsScreen(
    jobs: List<ExportJob> = emptyList(),
    onRetry: (ExportJob) -> Unit = {},
    onShowLog: (ExportJob) -> Unit = {},
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
                    ExportJobRow(job, onRetry, onShowLog)
                }
            }
        }
    }
}

@Composable
private fun ExportJobRow(
    job: ExportJob,
    onRetry: (ExportJob) -> Unit,
    onShowLog: (ExportJob) -> Unit,
) {
    if (job.state == ExportState.FAILED) {
        FailedJobCard(job, onRetry, onShowLog)
    } else {
        ListItem(
            headlineContent = { Text(job.clipId) },
            supportingContent = {
                Column {
                    when (job.state) {
                        ExportState.QUEUED ->
                            Text(stringResource(R.string.exports_job_queued))
                        ExportState.RUNNING -> {
                            Text(stringResource(R.string.exports_job_running, job.progressPercent))
                            LinearProgressIndicator(
                                progress = { job.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                            )
                        }
                        ExportState.DONE ->
                            Text(stringResource(R.string.exports_job_done))
                        ExportState.CANCELLED ->
                            Text(stringResource(R.string.exports_job_cancelled))
                        ExportState.FAILED -> Unit // handled by FailedJobCard
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
    onShowLog: (ExportJob) -> Unit,
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
                stringResource(R.string.exports_job_failed_stage, job.failedStage ?: ""),
                style = MaterialTheme.typography.titleMedium,
            )
            if (job.failedCause != null) {
                Text(
                    stringResource(R.string.exports_job_failed_cause, job.failedCause),
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
                TextButton(onClick = { onShowLog(job) }) {
                    Text(stringResource(R.string.exports_job_show_log))
                }
                Button(onClick = { onRetry(job) }) {
                    Text(stringResource(R.string.exports_job_retry))
                }
            }
        }
    }
}
