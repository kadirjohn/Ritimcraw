package com.ritim.ritimcraw.ui.exports

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.ritim.ritimcraw.util.Format

/**
 * Exports screen (DESIGN.md Export job): job rows with the format line, DONE
 * and CANCELLED stamps, and a failed card naming the stage and cause with
 * "Nothing was written" and a Retry action. Jobs stay until cleared.
 *
 * DONE rows open on tap (owner request 2026-11-11 §1): the whole row and
 * its Open button both fire [onOpen] with the job — the shell decides what
 * that opens (the job's media-store row, the app's own copy through
 * FileProvider, or an understandable message; see ExportOpen).
 *
 * The engine wires in the device pass; this build renders whatever jobs the
 * [ExportStore][com.ritim.ritimcraw.export.ExportStore] holds.
 */
@Composable
fun ExportsScreen(
    jobs: List<ExportJob> = emptyList(),
    onRetry: (ExportJob) -> Unit = {},
    onClear: () -> Unit = {},
    onOpen: (ExportJob) -> Unit = {},
    // sourceId -> display name (owner request §2b): rows show the clip's
    // real file name; the id is the fallback for unknown/missing sources.
    sourceNames: Map<String, String> = emptyMap(),
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
                    ExportJobRow(job, sourceNames[job.sourceId] ?: job.sourceId, onRetry, onOpen)
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
    // The clip's display name (§2b), id fallback resolved by the caller.
    sourceName: String,
    onRetry: (ExportJob) -> Unit,
    onOpen: (ExportJob) -> Unit,
) {
    if (job.state == State.FAILED) {
        FailedJobCard(job, sourceName, onRetry)
    } else {
        ListItem(
            headlineContent = { Text(sourceName) },
            supportingContent = {
                Column {
                    // Format line on every non-failed row (DESIGN.md Export job).
                    // RAW-11 Frame Picker: a PHOTO row (a developed frame)
                    // reads "PNG photo" — its own format line, not the MP4
                    // fallback.
                    Text(
                        stringResource(
                            when (job.target) {
                                com.ritim.ritimcraw.export.Target.DNG -> R.string.exports_format_dng
                                com.ritim.ritimcraw.export.Target.PHOTO -> R.string.exports_format_photo
                                com.ritim.ritimcraw.export.Target.MP4 -> R.string.exports_format_mp4
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (job.state) {
                        State.QUEUED ->
                            Text(stringResource(R.string.exports_job_queued))
                        State.RUNNING -> RunningJobProgress(job)
                        State.DONE -> {
                            // DONE stamp with the output name (detail on a DONE
                            // job is the output file name).
                            Text(
                                stringResource(
                                    R.string.exports_job_done_named,
                                    job.detail ?: "",
                                )
                            )
                            // The whole row opens the finished file (owner
                            // request §1); the button is the visible affordance
                            // for the same action.
                            TextButton(onClick = { onOpen(job) }) {
                                Text(stringResource(R.string.exports_job_open))
                            }
                        }
                        State.CANCELLED ->
                            Text(stringResource(R.string.exports_job_cancelled))
                        State.FAILED -> Unit // handled by FailedJobCard
                    }
                }
            },
            // 48dp targets (DESIGN.md): the row itself is the tap target in
            // addition to the Open button.
            modifier = if (job.state == State.DONE) {
                Modifier.clickable { onOpen(job) }
            } else {
                Modifier
            },
        )
    }
}

/**
 * A RUNNING job's live line (owner request 2026-11-11 §1): a linear
 * progress bar plus the frame-count text. Determinate (percent from
 * done/total, tabular numerals) once the exporter reported a total;
 * indeterminate while the total is still unknown. When the frames are
 * done but the job is still RUNNING, the mux/finalize stage is running —
 * the bar shows full and the text says "Finishing file…" instead of a
 * misleading "310/310". The ETA is a simple linear estimate shown only
 * after enough data (see [Format.etaSeconds] — fewer than 10 frames
 * extrapolates wildly).
 */
@Composable
private fun RunningJobProgress(job: ExportJob) {
    val frames = job.framesDone
    val total = job.framesTotal
    Column(Modifier.padding(top = 2.dp)) {
        if (frames != null && total != null && total > 0) {
            val finalizing = frames >= total
            LinearProgressIndicator(
                progress = {
                    (frames.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
            )
            if (finalizing) {
                // All frames encoded; the muxer/finalize stage is running.
                Text(stringResource(R.string.exports_job_finalizing))
            } else {
                val eta = job.startedAtMs?.let { start ->
                    Format.etaSeconds(frames, total, start, System.currentTimeMillis())
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(R.string.exports_job_running_frames, frames, total),
                        // Frame counts are numbers: tnum keeps the digits
                        // from jittering as they tick up (DESIGN.md).
                        style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                    )
                    if (eta != null) {
                        Text(
                            stringResource(
                                R.string.exports_job_eta,
                                Format.formatDuration(eta * 1000L),
                            ),
                            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            // Total not reported yet (between RUNNING and the first
            // throttled progress write): indeterminate bar + plain line.
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp),
            )
            Text(stringResource(R.string.exports_job_running))
        }
    }
}

@Composable
private fun FailedJobCard(
    job: ExportJob,
    // The clip's display name (§2b), id fallback resolved by the caller.
    sourceName: String,
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
                stringResource(R.string.exports_job_failed_stage, sourceName),
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
