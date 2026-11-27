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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.ExportsDeletePrefsStore
import com.ritim.ritimcraw.export.ExportDelete
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
 * deletable two ways: a swipe LEFT or RIGHT arms a delete
 * (material3 1.3.0's [SwipeToDismissBox], the verified API on this
 * classpath, both directions) and the header's Select icon opens
 * multi-select mode — checkboxes on the deletable rows, trash deletes
 * the checked set. The swipe ARMS, never deletes (owner request
 * 2026-11-27: a page-turn swipe must not lose an export — the
 * confirmValueChange VETO below rolls the row back and opens the
 * confirm dialog; see [ExportsDeleteConfirm]); the dialog's Delete, or
 * the selection trash's confirm, lands in [onDelete] -> the store's
 * delete(ids), which removes the job RECORD only, exactly like
 * Clear-all — UNLESS the dialog's delete-from-disk checkbox is checked
 * (owner request 2026-11-27, M8: "delete from disk box'u da olsun ve
 * bunu işaretlerse diskten de silsin"): the checkbox (default
 * UNCHECKED, the safe side; M10, the owner's same-day follow-up:
 * "user'ın tıkladığı en son seçenek saklanmalı, eğer delete from the
 * disk seçerse diğer silme isteklerinde de bu seçili gelmeli" — the
 * last choice is REMEMBERED through [ExportsDeletePrefsStore], still
 * default unchecked for a first dialog) routes the checked state beside
 * the ids, and the shell's adapter removes the DONE rows' published
 * media-store rows and internal copies best-effort through the pure
 * [com.ritim.ritimcraw.export.ExportDelete] rule. Deletion is
 * irreversible; NO undo snackbar was added — a true undo needs
 * position-true re-insertion this append-only store cannot do, and the
 * owner did not ask. In select mode the swipe is disabled (a tap
 * toggles the checkbox instead, the LibrarySelection behavior); a
 * DONE row's tap keeps its open behavior outside select mode.
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
    // DELETE-FROM-DISK (owner request 2026-11-27, M8): the checkbox's
    // checked state rides beside the ids — the shell's adapter resolves
    // the rows' own disk targets from the ids through the pure
    // [ExportDelete] rule, so the callback stays a thin pair, never a
    // URI list (the screen never learns resolver facts).
    onDelete: (Set<String>, Boolean) -> Unit = { _, _ -> },
    // sourceId -> display name (owner request §2b): rows show the clip's
    // real file name; the id is the fallback for unknown/missing sources.
    sourceNames: Map<String, String> = emptyMap(),
    // The delete-from-disk checkbox's persistence (owner request
    // 2026-11-27, M10: "user'ın tıkladığı en son seçenek saklanmalı"):
    // the shell's ExportsDeletePrefsStore over files/project.json. The
    // checkbox's first value reads from it and every toggle writes back
    // (see the deleteFromDisk state note below). Nullable with a null
    // default so previews and tests compose the screen unchanged — a
    // null store means no memory, the value stays whatever the pure
    // default is (false, the M8 safe side).
    deletePrefs: ExportsDeletePrefsStore? = null,
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

    // Swipe-delete confirmation (owner request 2026-11-27: "silmeden
    // önce pop up çıkıp onay sorulmalı"): the armed row's id, or null
    // when no delete is pending. The pure [ExportsDeleteConfirm] rules
    // carry the transitions; this is only the state the dialog renders
    // from. Pruned like the selection: a row that vanished while its
    // dialog was open (another delete path raced) leaves the dialog
    // composing NOTHING rather than offering to delete a ghost.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    if (pendingDeleteId != null && pendingDeleteId !in jobs.map { it.id }) {
        pendingDeleteId = null
    }
    val pendingDeleteJob = pendingDeleteId?.let { id -> jobs.firstOrNull { it.id == id } }

    // The dialog's delete-from-disk checkbox (owner request 2026-11-27,
    // M8: "diskten de silsin"; M10: "user'ın tıkladığı en son seçenek
    // saklanmalı"): the REMEMBERED choice, read once at composition from
    // the [ExportsDeletePrefsStore] the shell injects, refreshed at
    // every arm through the pure [ExportsDeleteConfirm.armCheckbox] rule
    // (the value routes through unchanged — the arm call exists so the
    // rule owns the "what the dialog opens with" question, not the
    // composable's inline arithmetic), and every toggle WRITES BACK to
    // the store immediately (the exact reading of the owner's
    // "seçerse diğer silme isteklerinde de bu seçili gelmeli": a
    // checked-then-cancelled dialog still remembers the check — the
    // memory is the TOGGLE, not the delete press).
    var deleteFromDisk by remember {
        mutableStateOf(ExportsDeleteConfirm.armCheckbox(deletePrefs?.get() ?: ExportDelete.DEFAULT_DELETE_FROM_DISK))
    }

    // The toggle handler BOTH dialogs share (M10): the state flips AND
    // the choice persists in the same breath — the owner's "seçerse
    // diğer silme isteklerinde de bu seçili gelmeli" is about the
    // checkbox's own touch, not the delete press, so a check followed
    // by Cancel still remembers. A null store (previews, tests) only
    // flips the in-memory state.
    val toggleDeleteFromDisk: (Boolean) -> Unit = { checked ->
        deleteFromDisk = checked
        deletePrefs?.put(checked)
    }

    // Selection-mode delete confirmation (owner request 2026-11-27, M8 —
    // the Library's E2 confirm-dialog precedent): the trash in the
    // selection bar asks before it deletes, exactly like the swipe's
    // dialog asks, and carries the SAME delete-from-disk checkbox so
    // both delete paths offer the same choice. The dialog composes only
    // while the selection is live; Cancel exits selection mode too (the
    // Library's rule: selection exits after delete OR cancel).
    var confirmSelectionDelete by remember { mutableStateOf(false) }

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
                    // M8 (owner request 2026-11-27): the trash ASKS first —
                    // the Library's E2 confirm dialog — and its own checkbox
                    // carries the delete-from-disk choice; the delete itself
                    // lands at the dialog's confirm, not here. M10: the
                    // checkbox opens with the REMEMBERED choice (the store
                    // re-read at the arm — the pure rule routes it through).
                    deleteFromDisk = ExportsDeleteConfirm.armCheckbox(deletePrefs?.get() ?: deleteFromDisk)
                    confirmSelectionDelete = true
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
                            onSwipeArm = {
                                pendingDeleteId = ExportsDeleteConfirm.arm(pendingDeleteId, job)
                                // The checkbox re-reads the REMEMBERED choice
                                // at every arm (M10): the new dialog opens
                                // with the user's LAST disk choice — the
                                // store read routed through the pure rule.
                                deleteFromDisk = ExportsDeleteConfirm.armCheckbox(deletePrefs?.get() ?: deleteFromDisk)
                            },
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

        // The swipe-delete confirm dialog (owner request 2026-11-27:
        // "silmeden önce pop up çıkıp onay sorulmalı" — the Library's E2
        // confirm dialog precedent): the armed row asks before its
        // record leaves. The body names WHAT leaves — the output file
        // for a DONE row, the source clip otherwise (the pure
        // [ExportsDeleteConfirm.subjectLine]) — plus the record-only
        // meaning, so the user knows the exported FILE stays on disk
        // UNLESS the checkbox below opts it out (M8).
        // Delete re-runs the still-deletable check at the press (the
        // pure confirmIds — a Retry racing the swipe re-queued the row
        // and nothing routes); Cancel/dismiss clears the arm and the
        // row has already rolled back at the veto, so no extra reset.
        if (pendingDeleteJob != null) {
            AlertDialog(
                onDismissRequest = { pendingDeleteId = ExportsDeleteConfirm.cancel(pendingDeleteId) },
                title = { Text(stringResource(R.string.exports_delete_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.exports_delete_message,
                                ExportsDeleteConfirm.subjectLine(pendingDeleteJob, sourceNames),
                            )
                        )
                        // DELETE FROM DISK (owner request 2026-11-27, M8:
                        // "delete from disk box'u da olsun ve bunu
                        // işaretlerse diskten de silsin"): the opt-in
                        // checkbox under the record-only meaning. Only a
                        // DONE row with a published file shows it (the
                        // pure [ExportsDeleteConfirm.showsDiskCheckbox] —
                        // a FAILED/CANCELLED row has nothing on disk, and
                        // a checkbox that does nothing would be a lie).
                        // Default UNCHECKED: the safe side keeps the M3
                        // meaning; the user opts the FILE out by checking.
                        // M10: the box opens with the REMEMBERED choice
                        // and every toggle persists immediately (the
                        // toggleDeleteFromDisk note above).
                        if (ExportsDeleteConfirm.showsDiskCheckbox(pendingDeleteJob)) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = deleteFromDisk,
                                    onCheckedChange = toggleDeleteFromDisk,
                                )
                                Text(
                                    stringResource(R.string.exports_delete_from_disk),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onDelete(
                                ExportsDeleteConfirm.confirmIds(pendingDeleteId, jobs),
                                deleteFromDisk,
                            )
                            pendingDeleteId = null
                        },
                    ) {
                        Text(stringResource(R.string.exports_delete_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { pendingDeleteId = ExportsDeleteConfirm.cancel(pendingDeleteId) },
                    ) {
                        Text(stringResource(R.string.exports_delete_cancel))
                    }
                },
            )
        }

        // The selection-mode delete confirm (M8, owner request
        // 2026-11-27: the trash asks too — the Library's E2 precedent,
        // the swipe dialog's own shape): the checked set's still-deletable
        // filter runs at the CONFIRM press (the pure
        // [ExportsSelection.deleteIds] — live rows drop out there), the
        // checkbox offers the same disk choice as the swipe dialog, and
        // the body states HOW MANY rows leave (the Library's multi-select
        // honesty). Cancel exits selection mode too (the Library's rule:
        // selection exits after delete OR cancel).
        if (confirmSelectionDelete && selecting) {
            AlertDialog(
                onDismissRequest = { confirmSelectionDelete = false },
                title = { Text(stringResource(R.string.exports_selection_delete_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(
                                R.string.exports_selection_delete_message,
                                selection.size,
                            )
                        )
                        // The SAME delete-from-disk opt-in (M8): shown only
                        // when at least one checked row has a published
                        // file — the same pure rule the swipe dialog
                        // applies, mapped over the checked set. M10: the
                        // SAME remembered choice and the same
                        // persist-on-toggle handler as the swipe dialog.
                        if (jobs.any {
                                it.id in selection &&
                                    ExportsDeleteConfirm.showsDiskCheckbox(it)
                            }
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = deleteFromDisk,
                                    onCheckedChange = toggleDeleteFromDisk,
                                )
                                Text(
                                    stringResource(R.string.exports_delete_from_disk),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmSelectionDelete = false
                            onDelete(
                                ExportsSelection.deleteIds(selection, jobs),
                                deleteFromDisk,
                            )
                            selectedIds = emptySet() // exit selection mode first
                        },
                    ) {
                        Text(stringResource(R.string.exports_delete_confirm))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            confirmSelectionDelete = false
                            selectedIds = emptySet()
                        },
                    ) {
                        Text(stringResource(R.string.exports_delete_cancel))
                    }
                },
            )
        }
    }
}

/**
 * A terminal row wrapped in the swipe-to-delete box (owner request
 * 2026-11-26): SwipeToDismissBox — material3 1.3.0, the verified API on
 * this classpath — with BOTH directions enabled, so a swipe left OR
 * right on a finished/cancelled/failed row ARMS its delete. The
 * background reveals the trash glyph in the theme's error role as the
 * row slides (start side for a rightward swipe, end side for a
 * leftward one — the [SwipeToDismissBoxState.dismissDirection] read).
 *
 * ARM-THEN-CONFIRM (owner request 2026-11-27: a page-turn swipe must
 * not lose an export): the delete does NOT fire at the dismiss
 * threshold. The state's [confirmValueChange] VETOES every target
 * (returns false) — material3 1.3.0's own AnchoredDraggableState.settle
 * then rolls the row back to Settled with its rollback animation —
 * and the veto calls [onSwipeArm], which opens the confirm dialog.
 * Only the dialog's Delete lands in [onDelete] (re-checked there for
 * still-terminal); Cancel/dismiss deletes nothing, and the row is
 * already back in place from the veto's rollback. The veto must stay
 * unconditional (false for every value, including Settled) so the
 * settle paths can never strand the row between anchors; the row's
 * tap behavior is unchanged (a DONE row opens; FAILED keeps Retry) —
 * the box only adds the horizontal gesture.
 *
 * Deleting is IRREVERSIBLE and there is no undo snackbar: the store's
 * delete removes the RECORD only (exactly what clear-all does — the
 * output file and the published row are never touched UNLESS the
 * dialog's delete-from-disk checkbox was checked, M8), and a true
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
    onDelete: (Set<String>, Boolean) -> Unit,
    onSwipeArm: () -> Unit,
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
    // THE DISMISS THRESHOLD IS HALF THE ROW (owner request 2026-11-27,
    // the M3 polish pass: "sayfalar arası kaydırma yaparken exports
    // kısmında yanlışlıkla video silinebiliyor"). material3's own
    // default is a fixed 56dp ([SwipeToDismissBoxDefaults]) — a small
    // share of this screen's full-width cards, so a short sideways
    // drift (the same axis the page-turn swipe lives on) reached the
    // arm easily. Half the row's width demands a deliberate,
    // half-the-card drag before the confirm dialog even asks; the
    // threshold lambda's [totalDistance] parameter IS the row's width
    // (material3's own computeTarget passes the inter-anchor distance).
    // A FLING still arms below the threshold (material3's
    // computeTarget lets velocity decide past 125dp/s) — intended: a
    // fast purposeful flick is clearly deliberate, and the confirm
    // dialog guards the rest either way.
    val threshold: (Float) -> Float = { totalDistance -> totalDistance * 0.5f }
    // Fresh-callback trampoline (the ThemeSlider / TabSwipeUi pattern):
    // the state below restarts only on its keys, but its arm callback
    // must always be the CURRENT composition's — the caller rebuilds
    // its lambdas every recomposition (AppNav's onDelete/pending state
    // capture), so a remembered stale capture would arm the dialog with
    // an outdated pending id.
    val armCallback by rememberUpdatedState(onSwipeArm)
    val state = remember(job.id, density) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            // ARM-THEN-CONFIRM (the veto): the confirmValueChange runs
            // exactly when the swipe crosses the dismiss threshold (or
            // a fling targets the anchor) — the moment the delete USED
            // to fire. It now returns FALSE for every value, which
            // material3's own settle treats as a veto: the row rolls
            // back to Settled with the box's rollback animation (no
            // stranding between anchors, no dead gesture), and the arm
            // callback routes the row to the confirm dialog. The row's
            // gate of the store's live-guard still holds: the ARM only
            // happens on this terminal-only wrapper (the caller never
            // composes it for a live row), and the DIALOG re-runs the
            // still-deletable check before the store's own write.
            confirmValueChange = { _ ->
                armCallback()
                false
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
                        // (Night E-F) a CinemaDNG sequence job carries the
                        // DNG target name but its own row wording — the marker
                        // (job.cinemaDng) owns the label, the same routing
                        // rule the queue's dispatch uses.
                        if (job.cinemaDng != null) {
                            R.string.exports_format_cinema_dng
                        } else when (job.target) {
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
                                // (Night E-F) the sequence job's own wording
                                // (the marker owns the label).
                                if (job.cinemaDng != null) {
                                    R.string.exports_format_cinema_dng
                                } else when (job.target) {
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
