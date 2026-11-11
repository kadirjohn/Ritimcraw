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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.ExportEta
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.theme.Tnum
import com.ritim.ritimcraw.util.Format

/**
 * Exports screen (owner request 2026-11-11d §5): structured job cards —
 * source filename, export type, output filename, status, Open — with the
 * existing status semantics carrying the visual distinctions:
 *
 * - QUEUED / CANCELLED: plain surface card, status line only.
 * - RUNNING: plain surface card + the live progress block (bar, frames,
 *   ETA — exactly the existing RunningJobProgress).
 * - DONE: primary-container tinted card, output filename in tabular
 *   numerals, Open action; the whole card opens on tap (as before).
 * - FAILED: error-container card naming the stage and cause with
 *   "Nothing was written" and Retry (exactly the existing card).
 *
 * Clear moved from the list's footer to the header's end slot (§5.2: a
 * conventional toolbar location). Meaning unchanged: it clears job
 * history only — never the output files on disk.
 *
 * DONE rows open on tap (owner request 2026-11-11 §1): the whole card and
 * its Open button both fire [onOpen] with the job — the shell decides what
 * that opens (the job's media-store row, the app's own copy through
 * FileProvider, or an understandable message; see ExportOpen).
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
        // §5.2: Clear is a header/toolbar action now, not a list footer.
        // Same callback, same meaning — job history only.
        ScreenHeader(
            title = stringResource(R.string.exports_title),
            actions = {
                if (jobs.isNotEmpty()) {
                    TextButton(onClick = onClear) {
                        Text(stringResource(R.string.exports_clear))
                    }
                }
            },
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
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    ExportJobCard(job, sourceNames, onRetry, onOpen)
                }
            }
        }
    }
}

/**
 * One structured job card (§5): source filename headline, export type
 * beneath, then the state-specific block. The status distinctions come
 * from the job's existing state only.
 */
@Composable
private fun ExportJobCard(
    job: ExportJob,
    sourceNames: Map<String, String>,
    onRetry: (ExportJob) -> Unit,
    onOpen: (ExportJob) -> Unit,
) {
    if (job.state == State.FAILED) {
        FailedJobCard(job, ExportRowModel.sourceName(job, sourceNames), onRetry)
        return
    }
    // DONE reads as the positive result: the Monet primary-container tint
    // marks the row that has something to open (a stock M3 role, no new
    // colors). Every other state keeps the plain surface card.
    val done = job.state == State.DONE
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (done) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
            contentColor = if (done) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                // 48dp targets (DESIGN.md): the card itself is the tap
                // target in addition to the Open button (DONE only).
                if (ExportRowModel.isOpenable(job.state)) {
                    Modifier.clickable { onOpen(job) }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 12.dp),
        ) {
            // Source filename (§5): the clip's display name — the row's
            // headline, medium weight (§3.1 filenames).
            Text(
                ExportRowModel.sourceName(job, sourceNames),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Export type (§5): the existing format line. On the DONE card
            // it inherits the container's on-color (onSurfaceVariant is not
            // designed for primaryContainer); size alone separates it from
            // the headline.
            Text(
                stringResource(
                    when (job.target) {
                        Target.DNG -> R.string.exports_format_dng
                        Target.PHOTO -> R.string.exports_format_photo
                        Target.MP4 -> R.string.exports_format_mp4
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (done) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 2.dp),
            )
            when (job.state) {
                State.QUEUED ->
                    StatusLine(stringResource(R.string.exports_job_queued))
                State.RUNNING -> RunningJobProgress(job)
                State.DONE -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Status + output filename in one line (§5): the
                        // existing "Done — <file>" resource names the file
                        // this job wrote — job.fileName, the single source
                        // every path already goes through, so the row can
                        // never name a file the job did not write. Tabular
                        // numerals: digits in file names never reflow.
                        // Inherits the card's onPrimaryContainer content
                        // color (the DONE tint).
                        Text(
                            stringResource(
                                R.string.exports_job_done_named,
                                ExportRowModel.outputFileName(job),
                            ),
                            style = MaterialTheme.typography.bodySmall.merge(Tnum),
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Open stays a discoverable button on every DONE
                        // card (§5.2), not just the tap target. Pinned to
                        // the card's content color: TextButton defaults
                        // to primary, which fights the container tint.
                        TextButton(
                            onClick = { onOpen(job) },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        ) {
                            Text(stringResource(R.string.exports_job_open))
                        }
                    }
                }
                State.CANCELLED ->
                    StatusLine(stringResource(R.string.exports_job_cancelled))
                State.FAILED -> Unit // handled by FailedJobCard
            }
        }
    }
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/**
 * A RUNNING job's live line (owner request 2026-11-11 §1): a linear
 * progress bar plus the frame-count text. Determinate (percent from
 * done/total, tabular numerals) once the exporter reported a total;
 * indeterminate while the total is still unknown. When the frames are
 * done but the job is still RUNNING, the mux/finalize stage is running —
 * the bar shows full and the text says "Finishing file…" instead of a
 * misleading "310/310".
 *
 * The remaining-time estimate is the [ExportEta] EMA: one observation per
 * persisted progress write (the display cadence), no estimate before its
 * seed window, monotone-ish hold so the number never jumps (see ExportEta
 * for why the old wall-clock linear estimate jumped).
 */
@Composable
private fun RunningJobProgress(job: ExportJob) {
    val frames = job.framesDone
    val total = job.framesTotal
    Column(Modifier.padding(top = 4.dp)) {
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
                // One estimator per RUNNING row, keyed by job id: it feeds on
                // the persisted progress writes and holds its estimate —
                // see ExportEta. Remembers across recompositions, resets for
                // a different job (the list recycles rows) and re-seeds on a
                // retry (counts went backwards).
                val eta = remember(job.id) { ExportEta() }.observe(frames, total)
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
                                Format.formatEtaLeft(eta),
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
