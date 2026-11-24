package com.ritim.ritimcraw.ui.exports

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import com.ritim.ritimcraw.data.ThumbnailStore
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
import com.ritim.ritimcraw.export.ExportReport
import com.ritim.ritimcraw.export.ExportText
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
 *
 * CANCEL (owner request 2026-11-13: "export menüsünde iptal etme kısmı
 * olmalı"): QUEUED and RUNNING rows carry a Cancel action ([onCancel]);
 * terminal rows never do (see [ExportRowModel.isCancellable]). A QUEUED
 * cancel lands instantly (the queue flips the row and never starts the
 * job); a RUNNING cancel flips the row right at the tap and stops the
 * export cooperatively at the next frame boundary — the row settles as
 * CANCELLED within one frame, which reads as immediate on the frame tick
 * cadence (the persisted progress writes are throttled to 500ms anyway).
 */
@Composable
fun ExportsScreen(
    jobs: List<ExportJob> = emptyList(),
    onRetry: (ExportJob) -> Unit = {},
    onClear: () -> Unit = {},
    onOpen: (ExportJob) -> Unit = {},
    // Cancel action for the two live states (see the CANCEL note above):
    // the shell routes it to the queue's cancel(job.id).
    onCancel: (ExportJob) -> Unit = {},
    // sourceId -> display name (owner request §2b): rows show the clip's
    // real file name; the id is the fallback for unknown/missing sources.
    sourceNames: Map<String, String> = emptyMap(),
    // Thumbnail inputs (owner request 2026-11-24): the exports directory
    // (finished MP4 frames) and the library's thumbnail store (DNG/photo).
    exportDir: File? = null,
    thumbnailStore: ThumbnailStore? = null,
    modifier: Modifier = Modifier,
) {
    // Canonical header call site (title consistency, owner finding
    // 2026-11-12 — the contract lives in ScreenHeader's doc): the root
    // Column pads NOTHING, so the title's left edge is ScreenHeader's
    // own 16dp in all four browse screens. The 16dp screen gutter moved
    // into the body container below — the job cards and the empty state
    // keep their exact margins.
    Column(modifier.fillMaxSize()) {
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
                    .weight(1f)
                    .padding(horizontal = 16.dp),
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
                // The 16dp gutter that used to pad the root Column (see the
                // header call-site note above): the cards keep their exact
                // screen margins without shifting the title.
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 4.dp, horizontal = 0.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(jobs, key = { it.id }) { job ->
                    ExportJobCard(job, sourceNames, onRetry, onOpen, onCancel, exportDir, thumbnailStore)
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
    onCancel: (ExportJob) -> Unit,
    exportDir: File?,
    thumbnailStore: ThumbnailStore?,
) {
    if (job.state == State.FAILED) {
        FailedJobCard(job, ExportRowModel.sourceName(job, sourceNames), onRetry)
        return
    }
    if (job.state == State.DONE) {
        DoneJobCard(job, ExportRowModel.sourceName(job, sourceNames), onOpen, exportDir, thumbnailStore)
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
                        Target.LINEAR -> R.string.exports_format_dng_linear
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
                State.QUEUED -> {
                    // The queued row carries the cancel action in the DONE
                    // row's action-row shape (status weighted, action
                    // trailing): the job has not started, so a tap cancels
                    // it instantly and the row settles as CANCELLED.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.exports_job_queued),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        // Plain surface card: the button keeps the default
                        // primary content color (no container fight, unlike
                        // the DONE row's pinned onPrimaryContainer).
                        TextButton(onClick = { onCancel(job) }) {
                            Text(stringResource(R.string.exports_job_cancel))
                        }
                    }
                }
                // The running row keeps its live progress block; the cancel
                // action rides the frames line's Row (the button never
                // stacks under the progress bar). A tap flips the row to
                // CANCELLED right away and the queue stops the export at
                // the next frame boundary — see RunningJobProgress's
                // caller note in the screen KDoc.
                State.RUNNING -> RunningJobProgress(job, onCancel)
                State.DONE -> Unit // handled by DoneJobCard
                State.CANCELLED ->
                    StatusLine(stringResource(R.string.exports_job_cancelled))
                State.FAILED -> Unit // handled by FailedJobCard
            }
        }
    }
}

/**
 * A finished export's card (owner request 2026-11-24): rounded thumbnail
 * on the leading side, the clip name ONCE as the title, the real format
 * as the subtitle, a plain "Done" status, the visible report lines
 * (resolution/fps/codec/depth, bitrate, size) and an action row with an
 * info toggle (technical details: encoder etc.) left of a filled Open
 * button. The whole card still opens on tap.
 */
@Composable
private fun DoneJobCard(
    job: ExportJob,
    sourceName: String,
    onOpen: (ExportJob) -> Unit,
    exportDir: File?,
    thumbnailStore: ThumbnailStore?,
) {
    var expanded by rememberSaveable(job.id) { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(job) },
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExportThumb(job, exportDir, thumbnailStore)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(
                        sourceName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val codec = ExportRowModel.codecLabel(job)
                    Text(
                        if (codec != null) {
                            stringResource(R.string.exports_format_codec_mp4, codec)
                        } else {
                            stringResource(
                                when (job.target) {
                                    Target.DNG -> R.string.exports_format_dng
                                    Target.PHOTO -> R.string.exports_format_photo
                                    Target.MP4 -> R.string.exports_format_mp4
                                    Target.LINEAR -> R.string.exports_format_dng_linear
                                }
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Text(
                        stringResource(R.string.exports_job_done),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            val report = job.report
            if (report != null) {
                ExportReportBlock(report, expanded)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                if (report != null) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            painterResource(R.drawable.ic_info),
                            contentDescription = stringResource(R.string.exports_job_details),
                            tint = if (expanded) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { onOpen(job) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.exports_job_open))
                }
            }
        }
    }
}

/** Rounded 16:9 thumbnail, loaded off the main thread; neutral placeholder meanwhile. */
@Composable
private fun ExportThumb(job: ExportJob, exportDir: File?, store: ThumbnailStore?) {
    val context = LocalContext.current
    val bitmap by produceState(ExportThumbnails.cached(job.id), job.id) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                ExportThumbnails.load(context, job, exportDir, store)
            }
        }
    }
    Box(
        Modifier
            .width(96.dp)
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            Icon(
                painterResource(R.drawable.ic_track_video),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

/**
 * What the export REALLY produced (E1b): size, fps, codec, depth, the encoder
 * that ran, and the bitrate asked beside the real one (file size / duration).
 * Only DONE jobs that carried settings have a report; a retried or fixed run
 * says so instead of silently differing from the request.
 */
@Composable
private fun ExportReportBlock(r: ExportReport, expanded: Boolean) {
    Column(Modifier.padding(top = 4.dp)) {
        Text(
            stringResource(
                R.string.exports_report_format,
                r.width.toString(), r.height.toString(), ExportText.fps(r.fps),
                ExportText.codec(r.mime), r.bitDepth,
            ),
            style = MaterialTheme.typography.bodySmall.merge(Tnum),
        )
        val real = r.actualVideoBps
        val bytes = r.fileBytes
        Text(
            if (real != null && bytes != null) {
                stringResource(
                    R.string.exports_report_bitrate,
                    ExportText.mbps(r.requestedBps.toLong()), ExportText.mbps(real), ExportText.megabytes(bytes),
                )
            } else {
                stringResource(R.string.exports_report_bitrate_asked, ExportText.mbps(r.requestedBps.toLong()))
            },
            style = MaterialTheme.typography.bodySmall.merge(Tnum),
        )
        if (ExportText.tenBitDropped(r)) {
            Text(stringResource(R.string.exports_report_ten_bit_dropped), style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            // Technical details behind the info control (encoder etc.).
            for (line in ExportRowModel.detailLines(r)) {
                Text(
                    when (line) {
                        ExportRowModel.DetailLine.ENCODER -> when {
                            r.encoder == null -> stringResource(R.string.exports_report_encoder_unknown)
                            r.hardware == false -> stringResource(R.string.exports_report_encoder_sw, r.encoder)
                            else -> stringResource(R.string.exports_report_encoder_hw, r.encoder)
                        }
                        ExportRowModel.DetailLine.FIXED -> stringResource(R.string.exports_report_fixed)
                        ExportRowModel.DetailLine.CPU -> stringResource(R.string.exports_report_cpu)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
private fun RunningJobProgress(job: ExportJob, onCancel: (ExportJob) -> Unit) {
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
                // The cancel action stays reachable here too — the finalize
                // is part of the live run the user may stop.
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.exports_job_finalizing),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    CancelButton(job, onCancel)
                }
            } else {
                // One estimator per RUNNING row, keyed by job id: it feeds on
                // the persisted progress writes and holds its estimate —
                // see ExportEta. Remembers across recompositions, resets for
                // a different job (the list recycles rows) and re-seeds on a
                // retry (counts went backwards).
                val eta = remember(job.id) { ExportEta() }.observe(frames, total)
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
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
                    CancelButton(job, onCancel)
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.exports_job_running),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                CancelButton(job, onCancel)
            }
        }
    }
}

/**
 * The cancel action for a live row (owner request 2026-11-13): a stock M3
 * TextButton — text for a tertiary action per DESIGN.md, the same shape
 * as the DONE row's Open (the button's own minimum interactive size is
 * the 48dp touch target; the row padding keeps its separation from the
 * row's other content). The queue makes the tap feel immediate: the row
 * flips to CANCELLED at the tap, the export stops at the next frame
 * boundary.
 */
@Composable
private fun CancelButton(job: ExportJob, onCancel: (ExportJob) -> Unit) {
    TextButton(onClick = { onCancel(job) }) {
        Text(stringResource(R.string.exports_job_cancel))
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
