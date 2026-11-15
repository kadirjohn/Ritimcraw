package com.ritim.ritimcraw.ui.library

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.VideoTree
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.ui.components.Add
import com.ritim.ritimcraw.ui.components.Check
import com.ritim.ritimcraw.ui.components.ExpandMore
import com.ritim.ritimcraw.ui.components.FileListRow
import com.ritim.ritimcraw.ui.components.GridView
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.components.Sort
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
 * Library of imported clips (DESIGN.md Library): a title-only header, a
 * pinned toolbar (library toolbar redesign 2026-11-16: filter chip, sort
 * button, view-mode toggle, import "+", folder button), thumbnail-first
 * rows (the shared FileListRow), and an extended FAB "Import" that opens
 * the SAF document picker (ACTION_OPEN_DOCUMENT).
 *
 * The picked document is imported through [SourceRepository.import] with
 * takePersistableUriPermission so the clip survives reboots. Failures show
 * an error snackbar naming stage + cause + recovery (DESIGN.md Error states)
 * via the pure [ImportErrors] mapping. Import SUCCESS is not shown here at
 * all: the caller (AppNav) announces it as a system toast after the gyro
 * auto-match resolves, so ONE combined message can name gyro data when it
 * attached (owner request 2026-11-12 — no tap to dismiss, no layout space).
 *
 * Degraded rows stay visible with a DISTINCT line and action per state
 * (spec IN-12, QA-23): a LOST row (revoked permission) shows "Access lost"
 * with a Grant access action; a GONE row (deleted/moved document) shows
 * "File missing" with a Re-link action. Both states recover through the
 * SAME picker + fingerprint-checked re-link flow (spec IN-16): a revoked
 * grant also only comes back by picking the file again, so the tap opens
 * the SAF picker, the picked file is fingerprinted, and a MATCH rebinds
 * the record (same id — grade and exports survive) while a MISMATCH shows
 * the honest error and changes nothing. The pure [LibraryRowModel] keeps
 * the state mapping testable; the row renders through the shared
 * FileListRow's accessLost slots with per-state strings.
 *
 * The collection refresh (spec IN-12) is bounded per open: the caller
 * (AppNav) probes all degraded rows plus a rotating slice of healthy rows
 * on every Library composition. Pull-to-refresh is the manual full sweep
 * (the whole catalog, one probe per record — still cheap: one metadata
 * query, never an open).
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
 * content below (toolbar, rows) — never to the header's parent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sources: List<MediaSource>,
    onOpenClip: (String) -> Unit,
    onImport: (uri: String) -> Result<MediaSource>,
    // Re-link (spec IN-16): the degraded row's action hands the record to
    // the caller AFTER the SAF pick returns — AppNav runs the
    // fingerprint-checked relink and refreshes the list. Returns the
    // updated record on success; the failure path renders the snackbar
    // here (the screen owns the message surface).
    onRelink: (sourceId: String, uri: String) -> Result<MediaSource>,
    thumbnailAt: (id: String) -> String?,
    // Generation revision (owner finding 2026-11-14: after rotating a clip
    // the Library icon stayed blank until the user re-entered the editor —
    // the async generation lands with NO record change for an
    // already-probed clip, so nothing recomposed the visible row): the
    // caller bumps it after every generation pass; the rows pin their
    // thumbnailAt read to it, so the freshly written PNG swaps in on the
    // CURRENT visit (placeholder -> icon, no re-enter).
    thumbnailRevision: Int,
    thumbnailBatchLimit: Int,
    onGenerateThumbnails: (pending: List<String>) -> Unit,
    // Manual full sweep (spec IN-12): pull-to-refresh hands the whole
    // catalog to the caller's refresh; the caller drives the refreshing
    // indicator through [refreshing] and ends it when the pass lands.
    onRefresh: () -> Unit,
    refreshing: Boolean,
    // Bounded refresh-on-open (spec IN-12): fired once per Library
    // composition (every open, since navigation disposes the screen) —
    // the caller probes every degraded row plus a rotating slice of
    // healthy rows through [com.ritim.ritimcraw.data.CollectionRefresh].
    onOpenRefresh: () -> Unit,
    // E2 delete: the caller (AppNav) composes the store removes through
    // ClipDeletion and refreshes the list. Default keeps previews and any
    // other caller compiling unchanged.
    onDeleteClips: (ids: List<String>) -> Unit = {},
    // Folder import (owner request 2026-11-15): the toolbar's folder button
    // opens the tree picker; a granted tree takes over the screen with
    // the FolderImportBrowser. The single-file path runs the caller's
    // SAME per-file import pipeline (persistable grant, kind routed on
    // the FILE's extension, thumbnail + metadata probe + gyro auto-match,
    // combined success toast) and applies editedName as a rename AFTER
    // the import (the dedupe branch refreshes displayName from the
    // resolver, so the user's name must land last). The bulk path
    // imports each document in sequence through that same pipeline
    // (quiet — no per-file toast) and returns the summary for the
    // browser's ONE end-of-batch message.
    onImportFromFolder: (uri: String, kind: SourceKind, editedName: String?) -> Result<MediaSource> = { _, _, _ -> Result.failure(UnsupportedOperationException()) },
    onImportManyFromFolder: (docs: List<VideoTree.VideoDocument>) -> BulkImportOutcome = { BulkImportOutcome(0, it.size, null) },
    // Pre-import first-frame previews (owner request 2026-11-15): the
    // browser's cells read the document-keyed folder namespace through
    // [folderPreviewAt] and generate a missing one through
    // [onGenerateFolderPreview] (the caller's bounded pass). Defaults
    // keep every other caller compiling unchanged (no previews — the
    // glyph placeholder stays).
    folderPreviewAt: (uri: String) -> String? = { null },
    onGenerateFolderPreview: suspend (uri: String, displayName: String) -> Unit = { _, _ -> },
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

    // The degraded row whose action opened the SAF picker. The id (not the
    // record) is remembered: the callback runs after the picker round-trip,
    // and the caller resolves the CURRENT record from it — a record
    // refreshed mid-flight (refresh pass landed) is never stale.
    var relinkTargetId by rememberSaveable { mutableStateOf<String?>(null) }

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

    // Re-link picker (spec IN-16): same ACTION_OPEN_DOCUMENT contract as
    // the import picker (persistable read grant on the pick), but the
    // result goes through the caller's fingerprint-checked relink instead
    // of an import. A mismatch is the HONEST error — nothing changed.
    val relinkPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val targetId = relinkTargetId
        relinkTargetId = null // consumed either way: success refreshes, failure keeps the row
        if (uri == null || targetId == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Non-fatal for the same reason as the import path: the
            // refresh pass will surface the row's real state.
        }
        val result = runCatching { onRelink(targetId, uri.toString()) }
            .getOrElse { e -> Result.failure(e) }
        result.fold(
            onSuccess = {
                // The record is whole again (same id, grade, exports); the
                // thumbnail it never could generate while missing retries on
                // the next composition through the backfill pass.
                val text = context.getString(R.string.library_relinked)
                scope.launch { snackbarHostState.showSnackbar(text) }
            },
            onFailure = { e ->
                val msg = ImportErrors.fromRelinkException(e)
                val text = context.getString(
                    msg.messageRes,
                    msg.causeArg ?: e.javaClass.simpleName,
                )
                scope.launch { snackbarHostState.showSnackbar(text) }
            },
        )
    }

    // Folder import (owner request 2026-11-15): ACTION_OPEN_DOCUMENT_TREE —
    // the tree picker asks for a FOLDER, the granted tree takes over the
    // screen with the FolderImportBrowser. The persistable grant on the
    // tree IS the permission ("proper ayarlanmış olsun"): no manifest
    // storage permission exists or is needed, and a SecurityException on
    // the take is non-fatal (the browser's child query will surface the
    // real state honestly if the grant truly is missing).
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user backed out
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            // Non-fatal: the browser's enumeration reports the honest
            // state if the grant really is required.
        }
        folderUri = uri.toString()
    }

    // The browser takes over the whole content area IN PLACE of the
    // library list (the brief's full-screen takeover — never a sheet).
    // The library's OWN selection state stays separate: the browser
    // keeps its selection on document URIs, and exiting the browser
    // returns to the library with its state intact.
    if (folderUri != null) {
        FolderImportBrowser(
            treeUri = folderUri!!,
            sources = sources,
            onImport = onImportFromFolder,
            onImportMany = onImportManyFromFolder,
            folderPreviewAt = folderPreviewAt,
            onGenerateFolderPreview = onGenerateFolderPreview,
            onClose = { folderUri = null },
            modifier = modifier,
        )
        return
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
            // Library toolbar redesign (2026-11-16): the title row is
            // title-only — the + and folder actions moved down into the
            // pinned toolbar, with the SAME callbacks (the SAF document
            // picker and the SAF tree picker). The toolbar composes in
            // this outer Column, so it stays pinned above the scrolling
            // list exactly where the chip strip was.
            ScreenHeader(title = stringResource(R.string.library_title))
            // The toolbar hides while selecting, exactly like the chips
            // did: the selection lives in the visible (filtered) list, so
            // switching filters mid-selection could orphan checked rows —
            // the bar owns the top area instead.
            LibraryToolbar(
                filter = filter,
                onFilterSelect = { filter = it },
                onImport = { picker.launch(arrayOf("*/*")) },
                onOpenFolder = { folderPicker.launch(null) },
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
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.padding(padding),
            ) {
                Column(
                    Modifier
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
                                    // Pinned to the generation revision
                                    // (owner finding 2026-11-14): a plain
                                    // thumbnailAt call reads at composition
                                    // time only — when the async icon lands
                                    // there is no record change to
                                    // recompose the row, so the placeholder
                                    // stayed until the NEXT fresh
                                    // composition (the enter-exit dance).
                                    // The revision bump is the change
                                    // signal: it re-reads the store here,
                                    // and the fresh path re-runs the row's
                                    // own decode (placeholder -> icon in
                                    // place).
                                    thumbnailPath = remember(thumbnailRevision) {
                                        thumbnailAt(source.id)
                                    },
                                    selected = source.id in selection,
                                    selecting = selecting,
                                    showDivider = source !== visible.lastOrNull(),
                                    onToggleSelected = {
                                        selectedIds = LibrarySelection.toggle(selectedIds, source.id)
                                    },
                                    onOpen = { onOpenClip(source.id) },
                                    onRelink = {
                                        relinkTargetId = source.id
                                        relinkPicker.launch(arrayOf("*/*"))
                                    },
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
    // Bounded refresh-on-open (spec IN-12): fired ONCE per Library
    // composition — navigation disposes the screen, so every composition
    // is one open. The caller's pass probes every degraded row plus a
    // rotating healthy slice (CollectionRefresh), one cheap metadata
    // query each; a sources change mid-session does NOT re-kick it (the
    // key is Unit, not the list).
    LaunchedEffect(Unit) { onOpenRefresh() }
}

/**
 * The Library toolbar (library toolbar redesign 2026-11-16): one pinned
 * Row that replaces the old scrollable chip strip — the filter chip at
 * start (at most the free share of the row, ellipsizing label), then the
 * sort button, the view-mode toggle, the import "+" and the folder
 * button at the end. All four controls are always visible; every control
 * is a 48dp touch target. The + is the ONE emphasized control
 * (primaryContainer tonal circle); the rest tint onSurfaceVariant.
 *
 * Width budget (the spec's own): at 360dp the fixed controls take
 * 16+8 padding + 4x48 buttons + 8 spacer = 224dp, leaving >= 136dp of
 * flexible space for the chip. The chip therefore rides in the row's
 * ONE weighted slot (a Box that takes the whole leftover), so the chip
 * measures at intrinsic width up to that full share — "MCRAW"/"Encoded"
 * fit at default font scale and the label ellipsizes only past it
 * (font scale ~1.3). The buttons are fixed-size children measured AFTER
 * the weighted slot, so they can never be squeezed by a long label.
 * (A literal chip.weight(1f) + Spacer.weight(1f) pair would split the
 * free space 50/50 and cap the chip at ~68dp — half the spec's stated
 * budget — so the weight sits on the wrapping Box instead; the chip
 * still never stretches, which is what fill = false was for.)
 *
 * Boundary of THIS step (toolbar placement): the filter chip is fully
 * interactive — its dropdown lists the SAME [LibraryFilter] options the
 * chip strip did (the enum, not a hardcoded list, so the filter set stays
 * final per QA-26). The sort button and the view toggle are placed with
 * their icons and content descriptions but stay INERT here; their menus,
 * state and persistence land with the sort/view state step.
 *
 * The toolbar composes in the screen's outer Column (pinned above the
 * scrolling list, never inside it) and hides during selection mode,
 * exactly like the chip strip did — the selection bar owns the top area.
 */
@Composable
private fun LibraryToolbar(
    filter: LibraryFilter,
    onFilterSelect: (LibraryFilter) -> Unit,
    onImport: () -> Unit,
    onOpenFolder: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // The spec's toolbar geometry: 16dp start / 8dp end (the end
            // icon buttons carry their own extra target space), 4dp top
            // separating it from the title, 8dp bottom before the list,
            // and a 48dp minimum height so the row is never shorter than
            // its touch targets.
            .padding(start = 16.dp, top = 4.dp, end = 8.dp, bottom = 8.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row's single flexible slot: everything the fixed controls
        // leave behind belongs to the chip's area (see the width budget
        // note above). The chip inside measures at intrinsic width and
        // ellipsizes past the slot; the empty remainder of the slot is
        // what visually right-aligns the buttons.
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            LibraryFilterChip(
                filter = filter,
                onSelect = onFilterSelect,
            )
        }
        // Sort button: placed with its icon + content description, INERT
        // in this step — the sort menu, its state and its persistence
        // land with the sort/view state step (the empty onClick keeps
        // the 48dp target and ripple without changing anything).
        IconButton(onClick = {}) {
            Icon(
                imageVector = Sort,
                contentDescription = stringResource(R.string.library_sort),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // View-mode toggle: same boundary as the sort button. The default
        // view is the list, so the toggle shows the grid icon with the
        // "switch to grid" action label; the state + persistence land
        // with the sort/view state step.
        IconButton(onClick = {}) {
            Icon(
                imageVector = GridView,
                contentDescription = stringResource(R.string.view_switch_to_grid),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Import "+": the toolbar's ONE emphasized control — a tonal
        // primaryContainer circle (40dp visual centered in the 48dp
        // target by FilledTonalIconButton's own minimum-size handling),
        // same callback as the removed title-row "+" and the FAB.
        FilledTonalIconButton(
            onClick = onImport,
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Icon(
                imageVector = Add,
                contentDescription = stringResource(R.string.library_import),
                modifier = Modifier.size(24.dp),
            )
        }
        // Folder import: the SAME tree-picker callback the removed
        // title-row folder button had (the ic_folder drawable stays for
        // continuity with the rest of the app's folder affordances).
        IconButton(onClick = onOpenFolder) {
            Icon(
                painter = painterResource(R.drawable.ic_folder),
                contentDescription = stringResource(R.string.library_import_folder),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The toolbar's filter chip (library toolbar redesign 2026-11-16): one
 * pill-shaped chip — the CURRENT filter's label plus a trailing
 * expand_more arrow — that opens a dropdown with the SAME options the
 * old chip strip offered ([LibraryFilter.entries], "All" first, straight
 * from the enum). Single select: the chosen entry closes the menu and
 * flows through the same state setter the chips used.
 *
 * Colors follow the spec's two states: "All" reads as a neutral
 * surfaceContainerHigh pill with onSurface text; a specific filter reads
 * as a secondaryContainer pill. The arrow rotates 180° over 150ms while
 * the menu is open. The chip clips to its CircleShape BEFORE the
 * clickable (the shape-clipped indication rule: the node with the shape
 * is the clickable node), exposes the dropdown role with the current
 * filter as its state description, and keeps a 48dp minimum touch target
 * around its 40dp visual.
 */
@Composable
private fun LibraryFilterChip(
    filter: LibraryFilter,
    onSelect: (LibraryFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(filter.labelRes)
    val filterDescription = stringResource(R.string.library_filter_cd)
    val arrow by animateFloatAsState(
        targetValue = if (open) 180f else 0f,
        animationSpec = tween(150),
        label = "libraryFilterArrow",
    )
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = if (filter == LibraryFilter.ALL) {
        colorScheme.surfaceContainerHigh
    } else {
        colorScheme.secondaryContainer
    }
    val labelColor = if (filter == LibraryFilter.ALL) {
        colorScheme.onSurface
    } else {
        colorScheme.onSecondaryContainer
    }
    Box(modifier) {
        Row(
            Modifier
                // 48dp touch target around the 40dp pill (the modifier
                // reserves layout space only — the pill centers in it).
                .minimumInteractiveComponentSize()
                .heightIn(min = 40.dp)
                // Shape-clipped indication: the CircleShape node is the
                // clickable node (clip BEFORE clickable), so the ripple
                // and the pill share one shape.
                .clip(CircleShape)
                .background(containerColor, CircleShape)
                .clickable(role = Role.DropdownList) { open = true }
                .semantics {
                    contentDescription = filterDescription
                    stateDescription = label
                }
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = labelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = ExpandMore,
                contentDescription = null,
                tint = labelColor,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(arrow),
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            LibraryFilter.entries.forEach { entry ->
                val selected = entry == filter
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(entry.labelRes),
                            style = TextStyle(
                                fontWeight = if (selected) FontWeight.SemiBold else null,
                            ),
                        )
                    },
                    leadingIcon = if (selected) {
                        {
                            Icon(
                                imageVector = Check,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    } else {
                        // The unselected rows keep the check's 24dp slot so
                        // every label aligns in one column.
                        { Spacer(Modifier.size(24.dp)) }
                    },
                    onClick = {
                        open = false
                        onSelect(entry)
                    },
                    // Radio semantics: one of the filter options is
                    // always the current value.
                    modifier = Modifier.semantics { role = Role.RadioButton },
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
    onRelink: () -> Unit,
) {
    // Pure row-state decision (spec IN-12): LOST and GONE render through
    // the shared FileListRow's degraded slots with DISTINCT strings per
    // state; a healthy row renders plain. An unexpected future Access
    // value maps to a healthy row (LibraryRowModel.rowStatus) — the list
    // never crashes on a catalog a newer build wrote.
    val status = LibraryRowModel.rowStatus(source)
    val accessLost = status != null
    FileListRow(
        // The clip's real file name (owner request §2b); the id when the
        // resolver could not name it — displayOrId is never blank.
        fileName = source.displayOrId,
        supportingLine = LibraryRowModel.supportingLine(
            source,
            LibraryRowModel.durationText(source),
        ),
        thumbnailPath = thumbnailPath,
        // The uniform 76dp slot needs no dims and no rotation (owner
        // finding 2026-11-14: the ratio-true heights broke the list's
        // rhythm between vertical, horizontal and open-gate rows): the
        // icon FILE carries the ratio and the manual rotation (it is
        // re-developed on every rotation change), and the slot fits it
        // letterboxed at its own ratio.
        accessLost = accessLost,
        accessLostLine = when (status) {
            LibraryRowModel.RowStatus.LOST -> stringResource(R.string.library_row_access_lost)
            LibraryRowModel.RowStatus.GONE -> stringResource(R.string.library_row_file_missing)
            null -> null
        },
        selecting = selecting,
        selected = selected,
        showDivider = showDivider,
        onToggleSelected = onToggleSelected,
        onOpen = onOpen,
        onGrantAccess = onRelink,
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
        trailingContent = when (status) {
            LibraryRowModel.RowStatus.LOST -> {
                {
                    Text(
                        stringResource(R.string.library_row_grant_access),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            LibraryRowModel.RowStatus.GONE -> {
                {
                    Text(
                        stringResource(R.string.library_row_relink),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            null -> null
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
