package com.ritim.ritimcraw.ui.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.ui.components.FileListRow
import com.ritim.ritimcraw.ui.components.FilterChipsRow
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.theme.Tnum
import kotlinx.coroutines.launch

/** Library filter chips (DESIGN.md Library). */
enum class LibraryFilter(val labelRes: Int) {
    ALL(R.string.library_filter_all),
    MCRAW(R.string.library_filter_mcraw),
    DNG(R.string.library_filter_dng),
    MILOG(R.string.library_filter_milog),
    ENCODED(R.string.library_filter_encoded),
    PHOTOS(R.string.library_filter_photos),
}

/**
 * Library of imported clips (DESIGN.md Library): a compact header, a
 * single-line scrollable chip row, thumbnail-first rows (the shared
 * FileListRow), and an extended FAB "Import" that opens the SAF document
 * picker (ACTION_OPEN_DOCUMENT).
 *
 * The picked document is imported through [SourceRepository.import] with
 * takePersistableUriPermission so the clip survives reboots. Failures show
 * an error snackbar naming stage + cause + recovery (DESIGN.md Error states)
 * via the pure [ImportErrors] mapping. Import SUCCESS is not shown here at
 * all: the caller (AppNav) announces it as a system toast after the gyro
 * auto-match resolves, so ONE combined message can name gyro data when it
 * attached (owner request 2026-11-12 — no tap to dismiss, no layout space).
 *
 * Rows with revoked access show "Access lost" with a "Grant access" action
 * instead of disappearing (QA-23).
 *
 * Delete (owner finding 2026-11-11 E2: long-press a row, trash the
 * selection): long-press enters selection mode with that row checked,
 * taps toggle rows, the bar's trash opens a confirm dialog stating how
 * many clips will be removed, and [onDeleteClips] does the removal — the
 * app's record + grade + thumbnail only, NEVER the user's original file
 * (see [com.ritim.ritimcraw.data.ClipDeletion]). Selection mode exits on
 * delete, on Cancel, and on the bar's close button; the list refreshes
 * through the caller's [sources] flow, and deleting everything lands on
 * the empty-library state.
 *
 * Title alignment (owner finding 2026-11-11 E4): the header (and the
 * selection bar that replaces it) composes OUTSIDE the nested list
 * Scaffold, so the title's layout chain matches Projects/Exports/Settings
 * exactly — all four titles sit at the same height. The nested Scaffold
 * serves only the list + FAB + snackbar.
 *
 * This screen is the CANONICAL header call site (ScreenHeader's doc
 * contract, owner finding 2026-11-12): the root Column pads NOTHING, the
 * header composes directly under it, and the 16dp gutter belongs to the
 * content below (chips, rows) — never to the header's parent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sources: List<MediaSource>,
    onOpenClip: (String) -> Unit,
    onImport: (uri: String) -> Result<MediaSource>,
    onGrantAccess: (MediaSource) -> Unit,
    thumbnailAt: (id: String) -> String?,
    thumbnailBatchLimit: Int,
    onGenerateThumbnails: (pending: List<String>) -> Unit,
    // E2 delete: the caller (AppNav) composes the store removes through
    // ClipDeletion and refreshes the list. Default keeps previews and any
    // other caller compiling unchanged.
    onDeleteClips: (ids: List<String>) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val visible = sources.filter { matches(filter, it) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Selection mode (E2): active exactly while the set is non-empty —
    // every transition is a pure LibrarySelection rule. Stale ids (rows
    // the list no longer has) are pruned on every sources change so the
    // selection can never reference deleted rows.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selection = LibrarySelection.prune(selectedIds, visible.map { it.id }.toSet())
    if (selection != selectedIds) selectedIds = selection
    val selecting = selection.isNotEmpty()
    var confirmDeleteOpen by remember { mutableStateOf(false) }

    // E6 (owner finding 2026-11-11): the system back gesture/button while
    // rows are selected must CLOSE SELECTION, not the app. Enabled
    // exactly while selection mode is active (the pure
    // [LibrarySelection.onBack] rule); with no selection the handler is
    // disabled and back keeps its system default.
    BackHandler(enabled = selecting) {
        selectedIds = LibrarySelection.onBack(selectedIds)
    }

    // SAF picker: ACTION_OPEN_DOCUMENT with a persistable read grant.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user backed out
        try {
            // Persistable permission: the clip stays readable across reboots.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Non-fatal: the collection refresh will surface a lost row if this
            // grant really is required; the import attempt still runs.
        }
        val result = runCatching { onImport(uri.toString()) }
            .getOrElse { e -> Result.failure(e) }
        // Success is NOT announced here: the caller (AppNav) fires the
        // system toast AFTER the gyro auto-match resolves, so the ONE
        // message can name gyro data when it attached (owner request
        // 2026-11-12 — no tap to dismiss, no layout space). Only the
        // FAILURE keeps this screen's snackbar: an error the user must
        // read and dismiss, per DESIGN.md Error states.
        result.onFailure { e ->
            val msg = ImportErrors.fromException("import", e)
            val text = context.getString(
                msg.messageRes,
                msg.causeArg ?: e.javaClass.simpleName,
            )
            // The message resource already begins with "Import failed:"
            // (stage), so the snackbar shows it as-is — no duplicate title.
            scope.launch { snackbarHostState.showSnackbar(text) }
        }
    }

    // E4 (owner finding 2026-11-11): the title must sit at the SAME height
    // as Projects/Exports/Settings. All four screens hang off AppNav's
    // Scaffold -> Box(padding) -> Crossfade; the other three continue
    // straight into Column -> ScreenHeader, while Library's header
    // previously composed INSIDE the nested Scaffold below — putting its
    // Surface + SubcomposeLayout layers in the title's own layout chain,
    // and the owner measured the Library title visibly lower than the
    // other three screens on the device. Hoisting the header (and the
    // selection bar that replaces it) OUT of the Scaffold makes the
    // title's chain structurally identical to ProjectsScreen's:
    // AppNav Scaffold -> Box(padding) -> Crossfade -> Column ->
    // ScreenHeader.
    Column(modifier.fillMaxSize()) {
        if (selecting) {
            // The selection bar (E2) replaces the title + filter row:
            // close (X) exits the mode, the count carries the state,
            // trash opens the confirm dialog. Plain Row of IconButtons
            // (48dp targets each) — stock M3, browse screens.
            val closeDescription = stringResource(R.string.library_selection_close)
            val deleteDescription = stringResource(R.string.library_selection_delete)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                IconButton(onClick = { selectedIds = emptySet() }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = closeDescription,
                    )
                }
                Text(
                    text = stringResource(R.string.library_selection_count, selection.size),
                    style = MaterialTheme.typography.titleMedium.merge(Tnum),
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                )
                IconButton(onClick = { confirmDeleteOpen = true }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = deleteDescription,
                    )
                }
            }
        } else {
            ScreenHeader(title = stringResource(R.string.library_title))
            // The chips hide while selecting: the selection lives in the
            // visible (filtered) list, so switching filters mid-selection
            // could orphan checked rows — the bar owns the top area
            // instead. Single-line scrollable row (§4.2): chips measure at
            // intrinsic width, "Encoded" never wraps.
            FilterChipsRow(
                count = LibraryFilter.entries.size,
                selected = { index -> filter == LibraryFilter.entries[index] },
                onSelect = { index -> filter = LibraryFilter.entries[index] },
                // E5: the row emits the guarded single-line label Text
                // itself; the caller passes the plain string.
                label = { index ->
                    stringResource(LibraryFilter.entries[index].labelRes)
                },
            )
        }
        // The FAB hides while selecting (the trash bar owns the screen; an
        // Import mid-selection would refresh the list under the selection).
        Scaffold(
            Modifier.weight(1f),
            // §4.1 compact header: the app shell's Scaffold already applies
            // the status-bar inset in its innerPadding — a nested Scaffold's
            // default contentWindowInsets would stack a SECOND one on top
            // (the excessive empty space above the title). Zero insets here;
            // this Scaffold exists only for the list + FAB + snackbar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(data) } },
            floatingActionButton = {
                if (!selecting) {
                    ExtendedFloatingActionButton(
                        onClick = { picker.launch(arrayOf("*/*")) },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text(stringResource(R.string.library_import)) },
                    )
                }
            },
        ) { padding ->
            Column(
                Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                if (visible.isEmpty()) {
                    EmptyLibrary(Modifier.weight(1f))
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        // Vertical rhythm (§4.3 consistent spacing) plus bottom
                        // clearance (§4.4): the last row must scroll fully
                        // clear of the Import FAB — the button never
                        // permanently obstructs a row.
                        contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp),
                    ) {
                        items(visible, key = { it.id }) { source ->
                            LibraryRow(
                                source = source,
                                thumbnailPath = thumbnailAt(source.id),
                                selected = source.id in selection,
                                selecting = selecting,
                                showDivider = source !== visible.lastOrNull(),
                                onToggleSelected = {
                                    selectedIds = LibrarySelection.toggle(selectedIds, source.id)
                                },
                                onOpen = { onOpenClip(source.id) },
                                onGrantAccess = { onGrantAccess(source) },
                            )
                        }
                    }
                    LibraryThumbnailBackfill(
                        sources = visible,
                        thumbnailAt = thumbnailAt,
                        batchLimit = thumbnailBatchLimit,
                        onGenerateThumbnails = onGenerateThumbnails,
                    )
                }
            }
            if (confirmDeleteOpen && selecting) {
                // The E2 confirm dialog: states HOW MANY clips leave, because
                // deletion is multi-select and irreversible (the original SAF
                // file stays on disk — only the app's record of it goes).
                AlertDialog(
                    onDismissRequest = { confirmDeleteOpen = false },
                    title = { Text(stringResource(R.string.library_delete_title)) },
                    text = {
                        Text(pluralStringResource(R.plurals.library_delete_message, selection.size, selection.size))
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                confirmDeleteOpen = false
                                val ids = selection.toList()
                                selectedIds = emptySet() // exit selection mode first
                                onDeleteClips(ids)
                            },
                        ) {
                            Text(stringResource(R.string.library_delete_confirm))
                        }
                    },
                    dismissButton = {
                        // Cancel exits selection mode too (the brief's rule:
                        // selection exits after delete OR cancel).
                        TextButton(
                            onClick = {
                                confirmDeleteOpen = false
                                selectedIds = emptySet()
                            },
                        ) {
                            Text(stringResource(R.string.library_delete_cancel))
                        }
                    },
                )
            }
        }
    }
}

private fun matches(filter: LibraryFilter, s: MediaSource): Boolean = when (filter) {
    LibraryFilter.ALL -> true
    LibraryFilter.MCRAW -> s.kind == SourceKind.MCRAW
    LibraryFilter.ENCODED -> s.kind == SourceKind.ENCODED
    // DNG / Mi-Log / Photos kinds land with their importers (sprint 2+);
    // the chips exist now so the filter set is final (QA-26).
    LibraryFilter.DNG, LibraryFilter.MILOG, LibraryFilter.PHOTOS -> false
}

@Composable
private fun LibraryRow(
    source: MediaSource,
    thumbnailPath: String?,
    selected: Boolean,
    selecting: Boolean,
    showDivider: Boolean,
    onToggleSelected: () -> Unit,
    onOpen: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    val accessLost = source.access == Access.LOST
    // The supporting line's parts (owner request §1): recorded resolution,
    // then duration — each part hides when unknown, never faked. The pure
    // model keeps the hide rules out of the composable.
    val durationText = LibraryRowModel.durationText(source)
    val supporting = LibraryRowModel.supportingLine(source, durationText)
    FileListRow(
        // The clip's real file name (owner request §2b); the id when the
        // resolver could not name it — displayOrId is never blank.
        fileName = source.displayOrId,
        supportingLine = supporting,
        thumbnailPath = thumbnailPath,
        accessLost = accessLost,
        accessLostLine = if (accessLost) {
            stringResource(R.string.library_row_access_lost)
        } else {
            null
        },
        selecting = selecting,
        selected = selected,
        showDivider = showDivider,
        onToggleSelected = onToggleSelected,
        onOpen = onOpen,
        onGrantAccess = onGrantAccess,
        onLongClick = onToggleSelected,
        supportingFallback = {
            // The kind line degrades gracefully until the generation pass
            // probes the clip (never a faked metadata line).
            Text(
                stringResource(
                    if (source.kind == SourceKind.MCRAW) R.string.library_row_kind_mcraw
                    else R.string.library_row_kind_encoded
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = if (accessLost) {
            {
                Text(
                    stringResource(R.string.library_row_grant_access),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            null
        },
    )
}

/**
 * Bounded, cancellable backfill (owner request 2026-11-11 section 1): on
 * every Library composition, records that still lack a thumbnail are
 * handed to [onGenerateThumbnails] — at most [batchLimit] per pass, in
 * list order — and the pass cancels with composition (leaving the Library
 * stops it; the next visit resumes with the remaining records).
 */
@Composable
private fun LibraryThumbnailBackfill(
    sources: List<MediaSource>,
    thumbnailAt: (id: String) -> String?,
    batchLimit: Int,
    onGenerateThumbnails: (pending: List<String>) -> Unit,
) {
    LaunchedEffect(sources, batchLimit) {
        val pending = sources
            .filter { ThumbnailRules.needsThumbnail(it, thumbnailAt(it.id) != null) }
            .map { it.id }
        if (pending.isNotEmpty()) {
            onGenerateThumbnails(ThumbnailRules.batch(pending, batchLimit))
        }
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.library_empty_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            stringResource(R.string.library_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, start = 32.dp, end = 32.dp),
        )
    }
}
