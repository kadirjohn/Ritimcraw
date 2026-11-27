package com.ritim.ritimcraw.ui.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.LibrarySortKey
import com.ritim.ritimcraw.data.LibraryViewMode
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.VideoTree
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.ui.components.Add
import com.ritim.ritimcraw.ui.components.AdaptiveLayout
import com.ritim.ritimcraw.ui.components.FileListRow
import com.ritim.ritimcraw.ui.components.FormatBadge
import com.ritim.ritimcraw.ui.components.FormatBadgeStyle
import com.ritim.ritimcraw.ui.components.ThumbnailRow
import com.ritim.ritimcraw.ui.components.Check
import com.ritim.ritimcraw.ui.components.ExpandMore
import com.ritim.ritimcraw.ui.components.GridView
import com.ritim.ritimcraw.ui.components.ScreenHeader
import com.ritim.ritimcraw.ui.components.Sort
import com.ritim.ritimcraw.ui.components.ViewList
import com.ritim.ritimcraw.ui.theme.Tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Library filter chips (DESIGN.md Library). */
enum class LibraryFilter(val labelRes: Int) {
    ALL(R.string.library_filter_all),
    MCRAW(R.string.library_filter_mcraw),
    DNG(R.string.library_filter_dng),
    MILOG(R.string.library_filter_milog),
    ENCODED(R.string.library_filter_encoded),
    PHOTOS(R.string.library_filter_photos),
}

/*
 * Grid columns: the spec's 3 on phones, more on large screens
 * (AdaptiveMinStaggeredCells' own rule, owner request 2026-11-23 tablet
 * adaptation). The grid now lays out ROWS (owner request 2026-11-26: rows
 * must never interleave; a row's height is set by its tallest clip), so
 * the column count is computed HERE through AdaptiveLayout.gridColumns
 * — the SAME function AdaptiveMinStaggeredCells/AdaptiveMinGridCells
 * use — and drives the pure row chunking.
 */

/**
 * Library of imported clips (DESIGN.md Library): a title-only header, a
 * pinned toolbar (library toolbar redesign 2026-11-16: filter chip, sort
 * button, view-mode toggle, import "+", folder button), and the clips as
 * thumbnail-first list rows (the shared FileListRow) or the grid's square
 * tiles, per the persisted view mode. The toolbar's "+" opens the SAF
 * document picker (ACTION_OPEN_DOCUMENT).
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
 * serves only the list + snackbar.
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
    // Rename of an already-imported clip (display name only): true when
    // the repository accepted it. AppNav refreshes the list.
    onRenameClip: (id: String, name: String) -> Boolean = { _, _ -> false },
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
    // The remembered folder (owner request 2026-11-16): the persisted
    // tree URI the toolbar's folder button reopens DIRECTLY — no
    // picker, no re-asking ("sürekli folder sormak mantıklı bir tasarım
    // mı?"). Null (a first-time user) routes the button to the tree
    // picker; the browser's change-folder button is the one path to a
    // different folder. Every landed pick reports through
    // [onFolderPicked] — the persist and the old grant's release live
    // with the caller (AppNav owns the store and the gyro-folder state
    // the release guard needs). A remembered URI whose grant died still
    // opens the browser: the enumeration reports the honest failure and
    // the recovery points at the change-folder button (the store KEEPS
    // the URI — the user may re-grant the same folder).
    rememberedFolder: String? = null,
    onFolderPicked: (oldUri: String?, newUri: String) -> Unit = { _, _ -> },
    // The toolbar's sort + view state (library toolbar redesign,
    // 2026-11-16): the caller (AppNav) owns the persistence; the screen
    // renders from these values only. Defaults keep every other caller
    // (previews, tests) compiling AND rendering exactly today's screen:
    // null key = import order, ascending, list.
    sortKey: LibrarySortKey? = null,
    sortAscending: Boolean = true,
    viewMode: LibraryViewMode = LibraryViewMode.DEFAULT,
    onSortKeyChange: (LibrarySortKey?) -> Unit = {},
    onSortAscendingChange: (Boolean) -> Unit = {},
    onViewModeChange: (LibraryViewMode) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // The screen's REAL content width (owner request 2026-11-26: the grid
    // chunks its clips into rows at the CURRENT column count, which must
    // match the width the layout actually gives this screen — a wide
    // window's NavigationRail already took its share before this box
    // measures). BoxWithConstraints at the root wraps the whole screen
    // (the folder-browser early return included) so the width is always
    // the layout's own answer, never LocalConfiguration.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val contentWidth = maxWidth
        LibraryScreenContent(
            contentWidth = contentWidth,
            sources = sources,
            onOpenClip = onOpenClip,
            onImport = onImport,
            onRelink = onRelink,
            thumbnailAt = thumbnailAt,
            thumbnailRevision = thumbnailRevision,
            thumbnailBatchLimit = thumbnailBatchLimit,
            onGenerateThumbnails = onGenerateThumbnails,
            onRefresh = onRefresh,
            refreshing = refreshing,
            onOpenRefresh = onOpenRefresh,
            onDeleteClips = onDeleteClips,
            onRenameClip = onRenameClip,
            onImportFromFolder = onImportFromFolder,
            onImportManyFromFolder = onImportManyFromFolder,
            folderPreviewAt = folderPreviewAt,
            onGenerateFolderPreview = onGenerateFolderPreview,
            rememberedFolder = rememberedFolder,
            onFolderPicked = onFolderPicked,
            sortKey = sortKey,
            sortAscending = sortAscending,
            viewMode = viewMode,
            onSortKeyChange = onSortKeyChange,
            onSortAscendingChange = onSortAscendingChange,
            onViewModeChange = onViewModeChange,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreenContent(
    contentWidth: Dp,
    sources: List<MediaSource>,
    onOpenClip: (String) -> Unit,
    onImport: (uri: String) -> Result<MediaSource>,
    onRelink: (sourceId: String, uri: String) -> Result<MediaSource>,
    thumbnailAt: (id: String) -> String?,
    thumbnailRevision: Int,
    thumbnailBatchLimit: Int,
    onGenerateThumbnails: (pending: List<String>) -> Unit,
    onRefresh: () -> Unit,
    refreshing: Boolean,
    onOpenRefresh: () -> Unit,
    onDeleteClips: (ids: List<String>) -> Unit,
    onRenameClip: (id: String, name: String) -> Boolean,
    onImportFromFolder: (uri: String, kind: SourceKind, editedName: String?) -> Result<MediaSource>,
    onImportManyFromFolder: (docs: List<VideoTree.VideoDocument>) -> BulkImportOutcome,
    folderPreviewAt: (uri: String) -> String?,
    onGenerateFolderPreview: suspend (uri: String, displayName: String) -> Unit,
    rememberedFolder: String?,
    onFolderPicked: (oldUri: String?, newUri: String) -> Unit,
    sortKey: LibrarySortKey?,
    sortAscending: Boolean,
    viewMode: LibraryViewMode,
    onSortKeyChange: (LibrarySortKey?) -> Unit,
    onSortAscendingChange: (Boolean) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
) {
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    // The visible list: filter first (the chips' unchanged rule), then the
    // toolbar's ordering (null key = today's import order, returned
    // as-is — the pure LibrarySort rule; the screen stays a thin shell).
    val visible = LibrarySort.apply(sources.filter { matches(filter, it) }, sortKey, sortAscending)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // The two renderings' own scroll states (library toolbar redesign,
    // 2026-11-16): switching views keeps the SAME CLIP at the top. The
    // list's firstVisibleItemIndex is the FLAT item index; the grid's is
    // a ROW index (owner request 2026-11-26: the grid lays out row
    // containers, one item per ROW), so the anchor maps through the
    // current chunking — list -> grid carries the clip at the top of
    // view, grid -> list carries the grid's top row's FIRST clip. A snap
    // (scrollToItem), not an animation: the new view lands already at
    // the anchor instead of visibly catching up. The grid's column
    // count is hoisted to the screen level ([rememberGridColumns]) so
    // the mapping and the chunking can never disagree.
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // The grid's column count from the screen's REAL content width
    // (owner request 2026-11-26): BoxWithConstraints at the screen root
    // measures what the layout actually gives the Library (the nav
    // rail's share on wide windows already subtracted by AppNav), and
    // the pure AdaptiveLayout.gridColumns applies the same rule the
    // old AdaptiveMinStaggeredCells did — 3 on phones, more on tablets.
    val gridColumns by rememberGridColumns(contentWidth)
    LaunchedEffect(viewMode, visible.size) {
        if (viewMode == LibraryViewMode.GRID) {
            // The clip at the top of the list view: its ROW in the
            // current column count (LibraryGridRows maps the flat index
            // to its row; a fresh list shows row 0 either way).
            gridState.scrollToItem(
                LibraryGridRows.rowOf(listState.firstVisibleItemIndex, visible.size, gridColumns),
            )
        } else {
            // The grid's top ROW's first clip, in the list's own flat
            // coordinate (grid row i holds clips from i * gridColumns).
            val row = gridState.firstVisibleItemIndex
            listState.scrollToItem(
                LibraryGridRows.firstClipOf(row, visible.size, gridColumns),
            )
        }
    }

    // Selection mode (E2): active exactly while the set is non-empty —
    // every transition is a pure LibrarySelection rule. Stale ids (rows
    // the list no longer has) are pruned on every sources change so the
    // selection can never reference deleted rows.
    var selectedIds by remember { mutableStateOf(setOf<String>()) }
    val selection = LibrarySelection.prune(selectedIds, visible.map { it.id }.toSet())
    if (selection != selectedIds) selectedIds = selection
    val selecting = selection.isNotEmpty()
    var confirmDeleteOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }

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
    //
    // Remembered folder (owner request 2026-11-16): ONE launcher serves
    // BOTH entry points — the toolbar button's first-time pick and the
    // browser's change-folder button — because a landed pick is the same
    // event either way: take the grant, report it up (the caller
    // persists and releases the old grant under FolderFlow's guard),
    // and re-key the browser onto the new tree.
    var folderUri by rememberSaveable { mutableStateOf<String?>(null) }
    // The browser's re-enumeration nonce: bumped on every landed pick so
    // a SAME-tree re-pick (the dead-grant recovery) also re-runs the
    // enumeration — produceState keys on treeUri AND this.
    var folderListingGeneration by rememberSaveable { mutableStateOf(0) }
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult // user backed out
        try {
            // READ + WRITE: the write half lets a clip rename also rename
            // the real file; a provider that only grants read falls back to
            // the read-only grant (the rename then stays app-only).
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        } catch (e: SecurityException) {
            // Non-fatal: the browser's enumeration reports the honest
            // state if the grant really is required.
        }
        // rememberedFolder (not folderUri) is the old value reported: a
        // change-folder pick releases what the STORE held, which is also
        // the tree the browser is showing.
        onFolderPicked(rememberedFolder, uri.toString())
        folderUri = uri.toString()
        folderListingGeneration++
    }

    // The browser takes over the whole content area IN PLACE of the
    // library list (the brief's full-screen takeover — never a sheet).
    // The library's OWN selection state stays separate: the browser
    // keeps its selection on document URIs, and exiting the browser
    // returns to the library with its state intact.
    if (folderUri != null) {
        FolderImportBrowser(
            treeUri = folderUri!!,
            listingGeneration = folderListingGeneration,
            sources = sources,
            onImport = onImportFromFolder,
            onImportMany = onImportManyFromFolder,
            folderPreviewAt = folderPreviewAt,
            onGenerateFolderPreview = onGenerateFolderPreview,
            // The change-folder affordance (owner request 2026-11-16):
            // "sonrasında folder menüsünde de değiştirmek isterse folder
            // değiştir butonu olmalı" — one button in the top bar, and
            // the SAME launcher handles the re-pick, so the browser
            // re-enumerates in place on the new tree.
            onChangeFolder = { folderPicker.launch(null) },
            onClose = { folderUri = null },
            modifier = Modifier.fillMaxSize(),
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
    Column(Modifier.fillMaxSize()) {
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
                if (selection.size == 1) {
                    val renameDescription = stringResource(R.string.library_selection_rename)
                    IconButton(onClick = { renameOpen = true }) {
                        Icon(
                            painterResource(R.drawable.ic_edit),
                            contentDescription = renameDescription,
                        )
                    }
                }
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
                sortKey = sortKey,
                sortAscending = sortAscending,
                viewMode = viewMode,
                onSortKeySelect = onSortKeyChange,
                onSortAscendingSelect = onSortAscendingChange,
                onViewModeToggle = onViewModeChange,
                onImport = { picker.launch(arrayOf("*/*")) },
                // Routing (owner request 2026-11-16): a remembered
                // folder opens the browser on it DIRECTLY — no picker,
                // no re-asking ("sürekli folder sormak mantıklı bir
                // tasarım mı?"); only a first-time user (no remembered
                // folder) sees the tree picker.
                onOpenFolder = {
                    FolderFlow.browserTree(rememberedFolder)?.let { folderUri = it }
                        ?: folderPicker.launch(null)
                },
            )
        }
        Scaffold(
            Modifier.weight(1f),
            // §4.1 compact header: the app shell's Scaffold already applies
            // the status-bar inset in its innerPadding — a nested Scaffold's
            // default contentWindowInsets would stack a SECOND one on top
            // (the excessive empty space above the title). Zero insets here;
            // this Scaffold exists only for the list + snackbar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(data) } },
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
                        EmptyLibrary(
                            modifier = Modifier.weight(1f),
                            // The FAB is gone, so the empty state carries
                            // the Import entry point itself: the SAME SAF
                            // picker launch the toolbar's "+" runs.
                            onImport = { picker.launch(arrayOf("*/*")) },
                        )
                    } else if (viewMode == LibraryViewMode.GRID) {
                        // Rows, not a staggered grid (owner request
                        // 2026-11-26): a LazyVerticalStaggeredGrid
                        // interleaves columns by design — a tall tile in
                        // the row below slid under a wide tile above,
                        // which read as overlapping rows. The grid now
                        // lays out one ROW container per item; every
                        // cell in a row shares the row's height (set by
                        // the row's tallest clip — the pure
                        // ThumbnailRow.gridRows decision), so no tile can
                        // ever cross into the row below, and the
                        // verticalArrangement spacing keeps rows read as
                        // rows WITHOUT any divider lines.
                        //
                        // The column count must be known at the SCREEN
                        // level (it drives the row chunking AND the
                        // view-switch scroll anchor), so it is computed
                        // from the content width through the SAME pure
                        // rule the old AdaptiveMinStaggeredCells applied
                        // (AdaptiveLayout.gridColumns) — see
                        // [rememberGridColumns], which supplies it; the
                        // chunk size always equals the real column count
                        // in both orientations (3 portrait / more
                        // landscape).
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            // The rows chunk from the PLACEHOLDER aspects
                            // (the record's frame size + manual rotation)
                            // so the layout is stable from the first
                            // frame — no reflow when icons land: the
                            // bitmap's own ratio matches the record's
                            // probed dimensions (the same placeholder
                            // contract the list rows run), and the box
                            // height it dictates is the SAME height
                            // the clip's own aspect would give it.
                            val rows = remember(visible, gridColumns) {
                                LibraryGridRows.of(
                                    sources = visible,
                                    aspects = visible.map {
                                        ThumbnailRow.effectiveAspect(
                                            it.width, it.height,
                                            it.containerOrientationDeg, it.manualRotationDeg,
                                        )
                                    },
                                    columns = gridColumns,
                                )
                            }
                            LazyVerticalGrid(
                                state = gridState,
                                // ONE column of ROW containers: each grid
                                // item is a full-width Row of [gridColumns]
                                // cells (the row's own spacedBy(8.dp)
                                // carries the 8dp cell gap), so a row is
                                // one layout unit and can never interleave
                                // with the next. The grid's own slot math
                                // stays out of the way; the outer padding
                                // keeps the 16dp gutters.
                                columns = GridCells.Fixed(1),
                                modifier = Modifier.fillMaxWidth(),
                                // The spec's grid geometry, kept: 16dp
                                // screen gutters, 4dp to the toolbar above,
                                // 8dp between cells (inside each row),
                                // 12dp between ROWS (the row separator the
                                // owner asked for — pure spacing, no
                                // line), 16dp below.
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 4.dp,
                                    bottom = 16.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                gridItems(
                                    rows,
                                    key = { row -> row.clipIds },
                                ) { row ->
                                    LibraryGridRowCells(
                                        row = row,
                                        columns = gridColumns,
                                        thumbnailAt = thumbnailAt,
                                        thumbnailRevision = thumbnailRevision,
                                        selecting = selecting,
                                        selectedIds = selectedIds,
                                        onToggleSelected = { id ->
                                            selectedIds = LibrarySelection.toggle(selectedIds, id)
                                        },
                                        onOpen = onOpenClip,
                                        onRelink = { id ->
                                            relinkTargetId = id
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
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f),
                            // Vertical rhythm (§4.3 consistent spacing). The
                            // old 96dp bottom clearance existed for the
                            // Import FAB, which is gone (the toolbar's "+"
                            // owns Import) — the bottom keeps only the
                            // screen's 16dp gutter.
                            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
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
            val renameTarget = if (renameOpen && selection.size == 1) {
                visible.firstOrNull { it.id == selection.first() }
            } else {
                null
            }
            if (renameTarget != null) {
                RenameClipDialog(
                    currentName = renameTarget.displayOrId,
                    onRename = { name -> onRenameClip(renameTarget.id, name) },
                    onDismiss = {
                        renameOpen = false
                        selectedIds = emptySet()
                    },
                )
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
 * The Library grid's CURRENT column count (owner request 2026-11-26: the
 * grid lays out ROWS, so the count must be known in composition to chunk
 * the clips). Computed from the screen's REAL content width through
 * [AdaptiveLayout.gridColumns] — the SAME pure rule the old
 * AdaptiveMinStaggeredCells applied (and AdaptiveMinGridCells still
 * applies in the FolderImportBrowser), with the same 16dp gutters and
 * 8dp cell spacing the grid's own geometry carries: the chunk size
 * always equals the real column count, 3 on phones, more on tablets,
 * and it re-derives when an orientation change moves the width. The
 * width is the caller's BoxWithConstraints maxWidth — the screen's own
 * layout box, never LocalConfiguration: a wide window's NavigationRail
 * already took its share before the box gets its width, and the chunk
 * count must track what the grid really has.
 */
@Composable
private fun rememberGridColumns(contentWidth: Dp): State<Int> {
    val density = LocalDensity.current
    return remember(contentWidth, density) {
        mutableStateOf(
            AdaptiveLayout.gridColumns(
                availablePx = with(density) {
                    (contentWidth - 32.dp).roundToPx() // 2 x 16dp gutter
                },
                spacingPx = with(density) { 8.dp.roundToPx() },
                cellMinPx = with(density) { AdaptiveLayout.GRID_CELL_MIN.roundToPx() },
            ),
        )
    }
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
 * Boundary of THIS step (toolbar placement): the filter chip, the sort
 * menu and the view toggle are fully interactive — the filter dropdown
 * lists the SAME [LibraryFilter] options the chip strip did (the enum,
 * not a hardcoded list, so the filter set stays final per QA-26), the
 * sort menu offers the spec's two sections, and the toggle switches the
 * persisted view mode's rendering: the list (the shared FileListRow) or
 * the 3-column grid ([LibraryGridCell]), keeping the first-visible index
 * at the top.
 *
 * The toolbar composes in the screen's outer Column (pinned above the
 * scrolling list, never inside it) and hides during selection mode,
 * exactly like the chip strip did — the selection bar owns the top area.
 */
@Composable
private fun LibraryToolbar(
    filter: LibraryFilter,
    onFilterSelect: (LibraryFilter) -> Unit,
    sortKey: LibrarySortKey?,
    sortAscending: Boolean,
    viewMode: LibraryViewMode,
    onSortKeySelect: (LibrarySortKey?) -> Unit,
    onSortAscendingSelect: (Boolean) -> Unit,
    onViewModeToggle: (LibraryViewMode) -> Unit,
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
        // Sort button: opens the sort menu — the spec's two sections
        // (Sort by: Name/Duration/Resolution single-select with a leading
        // check; Order: Ascending/Descending). Date and File size are
        // NOT offered: MediaSource has no such fields (the store's
        // discovery note).
        LibrarySortMenuButton(
            sortKey = sortKey,
            sortAscending = sortAscending,
            onSortKeySelect = onSortKeySelect,
            onSortAscendingSelect = onSortAscendingSelect,
        )
        // View-mode toggle: the spec's icon pair — the list shows the
        // grid icon ("switch to grid"), the grid shows the list icon
        // ("switch to list"). The switch keeps the first-visible index
        // at the top (the screen's scroll-sync effect).
        IconButton(onClick = { onViewModeToggle(viewMode.other()) }) {
            if (viewMode == LibraryViewMode.GRID) {
                Icon(
                    imageVector = ViewList,
                    contentDescription = stringResource(R.string.view_switch_to_list),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Icon(
                    imageVector = GridView,
                    contentDescription = stringResource(R.string.view_switch_to_grid),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/**
 * The toolbar's sort button + its dropdown (library toolbar redesign,
 * 2026-11-16): the "sort" icon opens a menu with the spec's two sections
 * — "Sort by" (Name, Duration, Resolution; single select with a leading
 * check on the chosen field) and "Order" (Ascending, Descending; single
 * select with a leading check). The sections are plain label rows
 * between the item groups (DropdownMenu's own column does the grouping).
 *
 * Date and File size are NOT offered: MediaSource carries no such fields
 * (no timestamp, no size — only id, name, dimensions, duration, kind),
 * so the menu lists only what a clip record can actually sort by.
 *
 * Selecting a field keeps the current direction; selecting a direction
 * keeps the current field. The persisted pair rides the caller's
 * callbacks (AppNav writes the store); the screen re-renders the list
 * through the pure [LibrarySort] rule on the next composition.
 */
@Composable
private fun LibrarySortMenuButton(
    sortKey: LibrarySortKey?,
    sortAscending: Boolean,
    onSortKeySelect: (LibrarySortKey) -> Unit,
    onSortAscendingSelect: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Sort,
                contentDescription = stringResource(R.string.library_sort),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            // Section: Sort by. The nullable persisted key maps to the
            // menu's check: null (today's order) checks nothing — the
            // never-touched state, not a menu option.
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.sort_by),
                        style = TextStyle(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                enabled = false,
                onClick = {},
            )
            sortKeyOptions().forEach { (key, labelRes) ->
                val selected = key == sortKey
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(labelRes),
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
                        onSortKeySelect(key)
                    },
                    // Radio semantics: at most one field is chosen; null
                    // (today's order) is the state where none is.
                    modifier = Modifier.semantics { role = Role.RadioButton },
                )
            }
            // Section: Order.
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.sort_order),
                        style = TextStyle(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                enabled = false,
                onClick = {},
            )
            listOf(
                true to R.string.order_ascending,
                false to R.string.order_descending,
            ).forEach { (ascending, labelRes) ->
                val selected = ascending == sortAscending
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(labelRes),
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
                        { Spacer(Modifier.size(24.dp)) }
                    },
                    onClick = {
                        open = false
                        onSortAscendingSelect(ascending)
                    },
                    modifier = Modifier.semantics { role = Role.RadioButton },
                )
            }
        }
    }
}

/**
 * The "Sort by" section's options in menu order: the three fields that
 * exist on the record, Name first (the tiebreak field), then Duration,
 * then Resolution. A pure mapping so the menu's order is testable and
 * the composable stays declarative — no filter-name hardcoding beyond
 * the enum itself (the label rides each entry's string resource).
 */
private fun sortKeyOptions(): List<Pair<LibrarySortKey, Int>> = listOf(
    LibrarySortKey.NAME to R.string.sort_name,
    LibrarySortKey.DURATION to R.string.sort_duration,
    LibrarySortKey.RESOLUTION to R.string.sort_resolution,
)

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
        // The clip's name WITHOUT its extension (owner request
        // 2026-11-26: long ".mcraw" names read cleaner; the badge carries
        // the format) — the id when the resolver could not name it. The
        // DISPLAY copy carries zero-width wrap breaks after '_' and
        // before '.' (toolbar redesign O1) so the name's lines break at
        // its separators; the record keeps the real full name (the GRID
        // view and the rename dialog keep it too — this strip is list
        // display only, the pure LibraryRowModel.listDisplayName rule).
        fileName = LibraryRowModel.displayWrapName(LibraryRowModel.listDisplayName(source)),
        badge = LibraryRowModel.kindBadge(source)?.let { b ->
            FormatBadge(
                style = when (b.style) {
                    LibraryRowModel.BadgeStyle.HIGHLIGHTED -> FormatBadgeStyle.HIGHLIGHTED
                    LibraryRowModel.BadgeStyle.NEUTRAL -> FormatBadgeStyle.NEUTRAL
                    // The fixed MotionCam blue rides through untouched
                    // (M2): the pill's MOTIONCAM branch carries the color,
                    // the mapping only forwards the decision.
                    LibraryRowModel.BadgeStyle.MOTIONCAM -> FormatBadgeStyle.MOTIONCAM
                },
                text = b.text,
            )
        },
        supportingLine = LibraryRowModel.supportingLine(
            source,
            LibraryRowModel.durationText(source),
        ),
        thumbnailPath = thumbnailPath,
        // The slot takes the icon's own ratio once loaded; the record's
        // frame size + manual rotation size the placeholder until then.
        frameWidth = source.width,
        frameHeight = source.height,
        manualRotationDeg = source.manualRotationDeg,
        containerOrientationDeg = source.containerOrientationDeg,
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
 * One grid ROW (owner request 2026-11-26: rows must never interleave; a
 * row's height is set by its tallest clip): the row's cells laid out in
 * the grid's own single column slot, each cell rendering the shared
 * [LibraryGridRows.Row.rowAspect] box, so every tile in the row is
 * exactly as tall as the row's tallest clip and NOTHING crosses into
 * the row below. The grid's 12dp verticalArrangement spacing between row
 * containers is the row separator the owner asked for — pure spacing,
 * no line.
 *
 * The cells themselves are the SAME [LibraryGridCell] as before (one
 * target per cell, the 12dp rounded shape-clipped ripple, selection
 * routing, degraded-record honesty) — only the tile's BOX changed: it
 * now takes the row's aspect instead of the clip's own, with the image
 * centered inside at its own ratio (ThumbnailRow.rowTile).
 */
@Composable
private fun LibraryGridRowCells(
    row: LibraryGridRows.Row,
    columns: Int,
    thumbnailAt: (id: String) -> String?,
    thumbnailRevision: Int,
    selecting: Boolean,
    selectedIds: Set<String>,
    onToggleSelected: (id: String) -> Unit,
    onOpen: (id: String) -> Unit,
    onRelink: (id: String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        row.clips.forEachIndexed { i, source ->
            key(source.id) {
                LibraryGridCell(
                    source = source,
                    rowAspect = row.rowAspect,
                    // Equal weight: every cell in the row takes the same
                    // share of the width — the columns stay exactly as
                    // even as the old AdaptiveMinStaggeredCells split.
                    modifier = Modifier.weight(1f),
                    // Pinned to the generation revision — the same owner
                    // finding 2026-11-14 the list rows pin theirs to.
                    thumbnailPath = remember(thumbnailRevision) {
                        thumbnailAt(source.id)
                    },
                    selected = source.id in selectedIds,
                    selecting = selecting,
                    onToggleSelected = { onToggleSelected(source.id) },
                    onOpen = { onOpen(source.id) },
                    onRelink = { onRelink(source.id) },
                )
            }
        }
        // A partial LAST row pads its missing slots with equal-weight
        // spacers: the weight shares then divide the row's width
        // [columns] ways, so the real cells keep exactly their column
        // width instead of stretching (a lone last clip renders at
        // 1/columns width, the same as any other tile — never full-row).
        repeat(columns - row.clips.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * One grid cell (library toolbar redesign, 2026-11-16): a tile — the
 * persisted first-frame PNG fitted inside at its own ratio
 * (ContentScale.Fit, the FileListRow ClipThumbnail contract: letterboxed
 * on the tile's surfaceContainerHigh background, never cropped, never
 * distorted) — with the duration badge at its bottom-end and the file
 * name beneath. The WHOLE cell is one target: it clips to its 12dp
 * rounded-corner shape BEFORE the combinedClickable (the shape-clipped
 * indication rule — the node with the shape is the clickable node), so
 * the ripple stays inside the cell and the tap area is the full cell.
 *
 * Owner request 2026-11-26 (row alignment): the tile's box now renders
 * the ROW's shared aspect ([rowAspect], from the row's tallest clip —
 * ThumbnailRow.rowTile) instead of the clip's own, so all cells in a
 * row share one height; the clip's own-ratio image centers inside the
 * box, filling [ThumbnailRow.rowTile]'s imageWidthFraction of the
 * width — a wider clip letterboxes horizontally, never cropped, never
 * distorted, and the row never interleaves with the next.
 *
 * Selection (E2) keeps its exact behavior — the list rows' routing,
 * unchanged: long-press enters selection with this cell checked, taps
 * toggle while selecting (never open), a tap outside selection opens (or
 * re-links a degraded record — the same routing the list rows run). The
 * trailing CheckCircle pair rides the tile's top-end corner (the
 * FolderImportBrowser cell precedent), the stock icons with the primary
 * tint when checked.
 *
 * Degraded records (LOST / GONE, spec IN-12) keep their honest treatment:
 * the Warning glyph rides the placeholder while the clip cannot be
 * probed and the tap routes to the SAME re-link picker the list rows
 * open. The grid never fakes a preview.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGridCell(
    source: MediaSource,
    rowAspect: Float,
    thumbnailPath: String?,
    selected: Boolean,
    selecting: Boolean,
    onToggleSelected: () -> Unit,
    onOpen: () -> Unit,
    onRelink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status = LibraryRowModel.rowStatus(source)
    val accessLost = status != null
    Column(
        modifier
            .fillMaxWidth()
            // Shape-clipped indication: the 12dp rounded cell is the
            // clickable node (clip BEFORE combinedClickable), so the
            // ripple never escapes the tile's corners.
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = {
                    if (selecting) {
                        onToggleSelected()
                    } else if (accessLost) {
                        onRelink()
                    } else {
                        onOpen()
                    }
                },
                onLongClick = onToggleSelected,
            ),
    ) {
        // The tile's box takes the ROW's shared aspect (all cells in the
        // row are exactly one height — owner request 2026-11-26); the
        // clip's image centers inside it at its OWN ratio, loaded bitmap
        // or placeholder (ThumbnailRow.rowTile decides the width
        // fraction, so a wider clip letterboxes horizontally and nothing
        // is ever cropped). The decode: the SAME produceState/BitmapFactory
        // off-UI-thread pattern the list rows run (cancels when the cell
        // leaves composition).
        // Keyed on the rotation too: a regenerated icon keeps its path, and
        // a bitmap decoded for the OLD orientation must not outlive the
        // record's new one (its aspect would disagree with the row's).
        val bitmap by produceState<Bitmap?>(
            initialValue = null,
            thumbnailPath, source.manualRotationDeg, source.containerOrientationDeg,
        ) {
            value = null
            value = withContext(Dispatchers.IO) {
                try {
                    thumbnailPath?.let { BitmapFactory.decodeFile(it) }
                } catch (e: Exception) {
                    null
                }
            }
        }
        val b = bitmap
        val aspect = if (b != null) {
            b.width.toFloat() / b.height.coerceAtLeast(1)
        } else {
            ThumbnailRow.effectiveAspect(
                source.width, source.height,
                source.containerOrientationDeg, source.manualRotationDeg,
            )
        }
        val tile = ThumbnailRow.rowTile(aspect, rowAspect)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(tile.boxAspect),
            contentAlignment = Alignment.Center,
        ) {
            // The image box: the corner rounding rides the IMAGE itself and
            // the badge / check anchor to ITS corners.
            Box(
                Modifier
                    .fillMaxWidth(tile.imageWidthFraction)
                    .aspectRatio(aspect.takeIf { it > 0f && it.isFinite() } ?: ThumbnailRow.DEFAULT_ASPECT)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                if (b != null) {
                    Image(
                        bitmap = b.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                    )
                } else if (accessLost) {
                    // The access-lost marker rides the placeholder while
                    // the clip cannot be probed (the list row's treatment).
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            // Duration badge (the spec's own): the recorded span when the
            // record carries one (the pure durationText rule — null
            // renders nothing, never a faked 0:00), a fully-rounded
            // opaque surfaceContainerHighest pill at the tile's
            // bottom-end, 6dp inset, 18dp tall with the text centered
            // (a Box wrapper so the pill's height never depends on the
            // text's own placement inside a taller node).
            LibraryRowModel.durationText(source)?.let { text ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .heightIn(min = 18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            if (selecting) {
                Icon(
                    imageVector = if (selected) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.CheckCircle
                    },
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
                )
            }
            }
        }
        // The file name beneath the tile (the spec's own): 12sp, onSurface,
        // two lines with ellipsis, 6dp off the tile. The DISPLAY copy
        // carries zero-width wrap breaks after '_' and before '.' (the
        // same toolbar O1 transform as the list row) so the two lines
        // break at the name's separators, never mid-extension.
        Text(
            text = LibraryRowModel.displayWrapName(source.displayOrId),
            style = TextStyle(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            // The Column above clips to the 12dp rounded shape (ripple), so
            // a label flush at x=0 / the bottom edge had its first glyph
            // shaved by the bottom-start corner arc. Start 8dp and bottom
            // 8dp keep the whole first glyph inside the arc (12dp radius).
            modifier = Modifier.padding(start = 8.dp, top = 6.dp, end = 4.dp, bottom = 8.dp),
        )
    }
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

/**
 * The empty-library state (DESIGN.md Library): centered title + body, and
 * — since the FAB's removal (library toolbar redesign, 2026-11-16) — the
 * screen's own Import entry point: a FILLED, fully-rounded 56dp button
 * (primary container, onPrimary label) that opens the SAME SAF document
 * picker the toolbar's "+" opens. An empty library keeps a direct path
 * to the first import; the toolbar still carries the everyday one.
 */
@Composable
private fun EmptyLibrary(
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        Button(
            onClick = onImport,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .padding(top = 24.dp)
                .heightIn(min = 56.dp),
        ) {
            Text(
                stringResource(R.string.library_import),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
