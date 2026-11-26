package com.ritim.ritimcraw.ui.exports

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
 *
 * DELETE (owner request 2026-11-26: individual export rows): a TERMINAL
 * row (DONE/FAILED/CANCELLED — never the two live states; a live job
 * can only be cancelled, deleting one would orphan the encoder) is
 * deletable two ways: a swipe LEFT or RIGHT dismisses it
 * (material3 1.3.0's [SwipeToDismissBox], the verified API on this
 * classpath, both directions anchored to delete through the state's
 * confirmValueChange) and the header's Select icon opens multi-select
 * mode — checkboxes on the deletable rows, trash deletes the checked
 * set. Both land in [onDelete] -> the store's delete(ids), which
 * removes the job RECORD only, exactly like Clear-all (the output file
 * under files/exports and the published media-store row are never
 * touched; see ExportStore.delete). Deletion is irreversible; NO undo
 * snackbar was added — a true undo needs position-true re-insertion
 * this append-only store cannot do, and the owner did not ask. In
 * select mode the swipe is disabled (a tap toggles the checkbox
 * instead, the LibrarySelection behavior); a DONE row's tap keeps its
 * open behavior outside select mode.
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
    // Delete action (owner request 2026-11-26: individual export rows):
    // the shell routes it to the store's delete(ids) — swipe-to-dismiss
    // on a terminal row and the selection mode's trash both land here.
    // The store repeats the live-guard under its own lock.
    onDelete: (Set<String>) -> Unit = {},
    // sourceId -> display name (owner request §2b): rows show the clip's
    // real file name; the id is the fallback for unknown/missing sources.
    sourceNames: Map<String, String> = emptyMap(),
    // Thumbnail inputs (owner request 2026-11-24): the exports directory
    // (finished MP4 frames) and the library's thumbnail store (DNG/photo).
    exportDir: File? = null,
    thumbnailStore: ThumbnailStore? = null,
    modifier: Modifier = Modifier,
) {
    // Multi-select state (owner request 2026-11-26: "select videos"):
    // the pure [ExportsSelection] rules, the LibrarySelection precedent's
    // shape with the header-button entry (the Library's long-press model
    // does not fit a card list — the owner named a Select action). The
    // Select ICON opens the mode by checking the first deletable row;
    // the mode lives exactly while the set is non-empty (the last row
    // unchecked, the close button, back, or the delete exits it). Stale
    // ids are pruned on every jobs change so a deleted/vanished row can
    // never stay checked, and a fully stale selection exits the mode by
    // becoming empty.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selection = ExportsSelection.prune(selectedIds, jobs.map { it.id }.toSet())
    if (selection != selectedIds) selectedIds = selection
    val selecting = selection.isNotEmpty()

    // Back (the LibrarySelection.onBack rule): back while rows are
    // checked exits SELECTION mode — the screen stays.
    BackHandler(enabled = selecting) {
        selectedIds = ExportsSelection.onBack(selectedIds)
    }

    // Canonical header call site (title consistency, owner finding
    // 2026-11-12 — the contract lives in ScreenHeader's doc): the root
    // Column pads NOTHING, so the title's left edge is ScreenHeader's
    // own 16dp in all four browse screens. The 16dp screen gutter moved
    // into the body container below — the job cards and the empty state
    // keep their exact margins.
    Column(modifier.fillMaxSize()) {
        if (selecting) {
            // The selection bar replaces the title (the Library's E2
            // precedent): close (X) exits, the count carries the state,
            // trash deletes the checked set through the pure
            // [ExportsSelection.deleteIds] filter (live rows drop out
            // there even if one was checked). Plain Row of IconButtons
            // (48dp targets each) — stock M3, browse screens.
            val closeDescription = stringResource(R.string.exports_selection_close)
            val deleteDescription = stringResource(R.string.exports_selection_delete)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedIds = emptySet() }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = closeDescription,
                    )
                }
                Text(
                    text = stringResource(R.string.exports_selection_count, selection.size),
                    style = MaterialTheme.typography.titleMedium.merge(Tnum),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = {
                    onDelete(ExportsSelection.deleteIds(selection, jobs))
                    selectedIds = emptySet() // exit selection mode first
                }) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = deleteDescription,
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        } else {
            ScreenHeader(
                title = stringResource(R.string.exports_title),
                actions = {
                    // Header rework (owner request 2026-11-26): Select as
                    // an ICON to the left of Clear-all, Clear-all a trash
                    // icon + red "Clear all" text (the theme's error role
                    // — never a hardcoded hex). Both hide when there is
                    // nothing to act on (the jobs.isEmpty condition the
                    // plain Clear button always had).
                    if (jobs.isNotEmpty()) {
                        // Select opens multi-select mode with the first
                        // deletable row checked (the mode lives while
                        // the set is non-empty — see ExportsSelection).
                        IconButton(onClick = {
                            selectedIds = jobs
                                .firstOrNull { ExportRowModel.isDeletable(it.state) }
                                ?.let { setOf(it.id) } ?: emptySet()
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_checklist),
                                contentDescription = stringResource(R.string.exports_select),
                            )
                        }
                        // Clear-all: the trash glyph + the existing Clear
                        // callback and meaning (job records only — never
                        // the output files on disk), in the error role.
                        TextButton(onClick = onClear) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.exports_clear_content_description),
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                stringResource(R.string.exports_clear),
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                },
            )
        }
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
                    // Swipe-to-delete (owner request 2026-11-26): a
                    // TERMINAL row swipes away in BOTH directions — the
                    // material3 1.3.0 SwipeToDismissBox (the verified API
                    // on this classpath; see the DELETE note on the
                    // screen) with both anchors enabled and the delete
                    // confirmed in the state's confirmValueChange, the
                    // row-level gate of the store's live-guard. A live
                    // row (QUEUED/RUNNING) has NO dismiss anchors and NO
                    // gesture — the plain card, cancel only (deleting a
                    // live job would orphan the encoder). Select mode
                    // disables the gesture too: a tap toggles the
                    // checkbox there, and a swipe would fight the
                    // selection.
                    if (ExportRowModel.isDeletable(job.state) && !selecting) {
                        DismissableJobCard(
                            job = job,
                            sourceNames = sourceNames,
                            onRetry = onRetry,
                            onOpen = onOpen,
                            onCancel = onCancel,
                            onDelete = onDelete,
                            exportDir = exportDir,
                            thumbnailStore = thumbnailStore,
                        )
                    } else {
                        ExportJobCard(
                            job = job,
                            sourceNames = sourceNames,
                            onRetry = onRetry,
                            // In select mode a tap toggles the selection,
                            // never open (the LibrarySelection behavior);
                            // a live row keeps cancel-only taps, and a
                            // non-selecting tap keeps its open behavior.
                            onOpen = if (selecting && ExportRowModel.isDeletable(job.state)) {
                                { selectedIds = ExportsSelection.toggle(selectedIds, job.id) }
                            } else {
                                onOpen
                            },
                            onCancel = onCancel,
                            showCheckbox = selecting && ExportRowModel.isDeletable(job.state),
                            checked = job.id in selection,
                            onToggleSelected = {
                                selectedIds = ExportsSelection.toggle(selectedIds, job.id)
                            },
                            exportDir = exportDir,
                            thumbnailStore = thumbnailStore,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A terminal row wrapped in the swipe-to-delete box (owner request
 * 2026-11-26): SwipeToDismissBox — material3 1.3.0, the verified API on
 * this classpath — with BOTH directions enabled, so a swipe left OR
 * right on a finished/cancelled/failed row deletes it. The background
 * reveals the trash glyph in the theme's error role as the row slides
 * (start side for a rightward swipe, end side for a leftward one —
 * the [SwipeToDismissBoxState.dismissDirection] read); the delete fires
 * through the state's [confirmValueChange] returning true, the row's
 * own gate of the store's live-guard. The row's tap behavior is
 * unchanged (a DONE row opens; FAILED keeps Retry) — the box only adds
 * the horizontal gesture.
 *
 * Deleting is IRREVERSIBLE and there is no undo snackbar: the store's
 * delete removes the RECORD only (exactly what clear-all does — the
 * output file and the published row are never touched), and a true
 * undo would need position-true re-insertion this append-only store
 * cannot do (the owner did not ask; see ExportStore.delete).
 */
@Composable
private fun DismissableJobCard(
    job: ExportJob,
    sourceNames: Map<String, String>,
    onRetry: (ExportJob) -> Unit,
    onOpen: (ExportJob) -> Unit,
    onCancel: (ExportJob) -> Unit,
    onDelete: (Set<String>) -> Unit,
    exportDir: File?,
    thumbnailStore: ThumbnailStore?,
) {
    val deleteDescription = stringResource(R.string.exports_delete_swipe)
    // The state is built through the PUBLIC constructor with plain
    // remember (keyed by the job's id), not
    // rememberSwipeToDismissBoxState: the saveable variant restores
    // currentValue, and a dismissed-then-refused row (a Retry racing the
    // swipe flipped it live — the caller's isDeletable routing then
    // unwraps the box) could resurrect its saved "dismissed" value when
    // the SAME id later turns terminal again — a ghost row slid
    // off-screen with its gesture dead. Plain remember ties the state
    // to the row's terminal STINT: disposed with the wrapper, fresh on
    // the next one; a half-swipe lost to a process death restoring
    // settled is the right outcome anyway.
    val density = LocalDensity.current
    val threshold = SwipeToDismissBoxDefaults.positionalThreshold
    val state = remember(job.id, density) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            // The delete rides confirmValueChange: it runs exactly when
            // the swipe crosses the dismiss threshold (or a fling
            // targets the anchor) and the box settles into the dismissed
            // anchor — the row then leaves with the store's write. The
            // lambda returns TRUE for every value (the settle/finally
            // paths in the box's internal AnchoredDraggableState treat
            // a veto as a rollback, and vetoing Settled can strand the
            // row between anchors with the gesture disabled): a swipe
            // that settles back never reaches the delete, and a delete
            // the store's live-guard refuses simply unwraps the row —
            // the next jobs refresh recomposes it as the plain live
            // card with a fresh state.
            confirmValueChange = { value ->
                if (value != SwipeToDismissBoxValue.Settled) {
                    onDelete(setOf(job.id))
                }
                true
            },
            positionalThreshold = threshold,
        )
    }
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            // The revealed background (the official sample's fillMaxSize
            // Box): the error-container wash with the trash glyph pinned
            // to the side the row slides away from (leading for a
            // rightward swipe, trailing for a leftward one — the
            // [SwipeToDismissBoxState.dismissDirection] read), all in
            // the theme's error roles — never a hardcoded color.
            val direction = state.dismissDirection
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Alignment.CenterStart
                } else {
                    Alignment.CenterEnd
                },
            ) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = deleteDescription,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        },
    ) {
        ExportJobCard(
            job = job,
            sourceNames = sourceNames,
            onRetry = onRetry,
            onOpen = onOpen,
            onCancel = onCancel,
            // Select mode never composes this wrapper (the caller routes
            // selecting rows to the plain card), so no checkbox here.
            showCheckbox = false,
            checked = false,
            onToggleSelected = {},
            exportDir = exportDir,
            thumbnailStore = thumbnailStore,
        )
    }
}

/**
 * One structured job card (§5): source filename headline, export type
 * beneath, then the state-specific block. The status distinctions come
 * from the job's existing state only.
 *
 * [showCheckbox] (owner request 2026-11-26): while the header's Select
 * mode is active, a deletable row's leading edge carries the checkbox
 * (the checked set the selection bar counts) and a row tap toggles
 * instead of opening — [onToggleSelected]; a live row never shows a
 * checkbox (cancel is its only action).
 */
@Composable
private fun ExportJobCard(
    job: ExportJob,
    sourceNames: Map<String, String>,
    onRetry: (ExportJob) -> Unit,
    onOpen: (ExportJob) -> Unit,
    onCancel: (ExportJob) -> Unit,
    showCheckbox: Boolean,
    checked: Boolean,
    onToggleSelected: () -> Unit,
    exportDir: File?,
    thumbnailStore: ThumbnailStore?,
) {
    if (job.state == State.FAILED) {
        FailedJobCard(
            job = job,
            sourceName = ExportRowModel.sourceName(job, sourceNames),
            onRetry = onRetry,
            showCheckbox = showCheckbox,
            checked = checked,
            onToggleSelected = onToggleSelected,
        )
        return
    }
    if (job.state == State.DONE) {
        DoneJobCard(
            job = job,
            sourceName = ExportRowModel.sourceName(job, sourceNames),
            onOpen = onOpen,
            showCheckbox = showCheckbox,
            checked = checked,
            onToggleSelected = onToggleSelected,
            exportDir = exportDir,
            thumbnailStore = thumbnailStore,
        )
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
                // target in addition to the Open button (DONE only). In
                // select mode a deletable row (CANCELLED rides this
                // branch) toggles the checkbox instead; the two live
                // states never show a checkbox and keep their
                // cancel-only taps.
                when {
                    showCheckbox -> Modifier.clickable { onToggleSelected() }
                    ExportRowModel.isOpenable(job.state) ->
                        Modifier.clickable { onOpen(job) }
                    else -> Modifier
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showCheckbox) {
                SelectionCheckbox(checked, onToggleSelected)
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(top = 12.dp, end = 16.dp, bottom = 12.dp),
            ) {
                // Source filename (§5): the clip's display name — the row's
                // headline, medium weight (§3.1 filenames).
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ExportRowModel.sourceName(job, sourceNames),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Queued: Cancel rides the title row's right end.
                    if (job.state == State.QUEUED) {
                        TextButton(onClick = { onCancel(job) }) {
                            Text(stringResource(R.string.exports_job_cancel))
                        }
                    }
                }
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
                        Text(
                            stringResource(R.string.exports_job_queued),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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
}

/**
 * A finished export's card (owner request 2026-11-24): rounded thumbnail
 * on the leading side, the clip name ONCE as the title, the real format
 * as the subtitle, a plain "Done" status, the visible report lines
 * (resolution/fps/codec/depth, bitrate, size) and an action row with an
 * info toggle (technical details: encoder etc.) left of a filled Open
 * button. The whole card still opens on tap — except in Select mode,
 * where the leading checkbox appears and the tap toggles the selection
 * (owner request 2026-11-26, the LibrarySelection behavior).
 */
@Composable
private fun DoneJobCard(
    job: ExportJob,
    sourceName: String,
    onOpen: (ExportJob) -> Unit,
    showCheckbox: Boolean,
    checked: Boolean,
    onToggleSelected: () -> Unit,
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
            // In select mode the whole card toggles the checkbox (the
            // LibrarySelection behavior: never open); otherwise the card
            // still opens on tap.
            .then(
                if (showCheckbox) {
                    Modifier.clickable { onToggleSelected() }
                } else {
                    Modifier.clickable { onOpen(job) }
                },
            ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCheckbox) {
                    SelectionCheckbox(checked, onToggleSelected)
                }
                ExportThumb(job, exportDir, thumbnailStore)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
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
                // In select mode the row's action pair (info toggle, Open)
                // is hidden: the whole row is the toggle target (the
                // LibrarySelection behavior — no mixed tap targets while
                // selecting); outside select mode both keep their roles.
                if (!showCheckbox) {
                    if (job.report != null) {
                        val detailsState = stringResource(
                            if (expanded) R.string.exports_job_details_shown else R.string.exports_job_details_hidden,
                        )
                        IconButton(
                            onClick = { expanded = !expanded },
                            modifier = Modifier
                                .testTag("export_info_${job.id}")
                                .semantics { stateDescription = detailsState },
                        ) {
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
                    }
                    Button(
                        onClick = { onOpen(job) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.exports_job_open))
                    }
                }
            }
            val report = job.report
            if (report != null) {
                // Changes what the file is, so it stays visible by default.
                if (ExportText.tenBitDropped(report)) {
                    Text(
                        stringResource(R.string.exports_report_ten_bit_dropped),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                // Hidden until the info button is tapped.
                AnimatedVisibility(
                    visible = expanded,
                    modifier = Modifier.testTag("export_details_${job.id}"),
                ) {
                    ExportReportBlock(report)
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
private fun ExportReportBlock(r: ExportReport) {
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
        run {
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
    // Select mode (owner request 2026-11-26): the same leading checkbox
    // + toggle-on-tap the DONE row carries — a FAILED row is deletable
    // history too.
    showCheckbox: Boolean,
    checked: Boolean,
    onToggleSelected: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (showCheckbox) {
                    Modifier.clickable { onToggleSelected() }
                } else {
                    Modifier
                },
            ),
    ) {
        Column(Modifier.padding(16.dp)) {
            // Retry rides the title row's right end (compact card).
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (showCheckbox) {
                    SelectionCheckbox(checked, onToggleSelected)
                }
                Text(
                    stringResource(R.string.exports_job_failed_stage, sourceName),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                // In select mode the Retry button is hidden: the whole row
                // is the toggle target (no mixed tap targets while
                // selecting — the DONE row's action-pair rule).
                if (!showCheckbox) {
                    Button(
                        onClick = { onRetry(job) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.exports_job_retry))
                    }
                }
            }
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
        }
    }
}

/**
 * A deletable row's leading checkbox while Select mode is active (owner
 * request 2026-11-26): a stock M3 Checkbox on the row's own tap target —
 * the whole row toggles (the LibrarySelection behavior), the box is the
 * visual state, and [onToggleSelected] runs from either. 48dp minimum
 * height keeps the touch target with the row's own padding.
 */
@Composable
private fun SelectionCheckbox(checked: Boolean, onToggleSelected: () -> Unit) {
    Checkbox(
        checked = checked,
        onCheckedChange = { onToggleSelected() },
        modifier = Modifier.padding(end = 8.dp),
    )
}
