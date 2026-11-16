package com.ritim.ritimcraw.ui.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.VideoTree
import com.ritim.ritimcraw.ui.theme.Tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The end-of-batch bulk summary the caller (AppNav) returns: how many
 * files imported, how many failed, and the FIRST failure — its cause
 * rides the all-failed message (the [ImportErrors] shape: stage, cause,
 * recovery).
 */
data class BulkImportOutcome(
    val imported: Int,
    val failed: Int,
    val firstError: Throwable?,
)

/** The enumeration state: loading (null), read, or failed. */
private sealed interface FolderListing {
    data class Ready(val docs: List<VideoTree.VideoDocument>) : FolderListing
    data class Failed(val error: Exception) : FolderListing
}

/**
 * The full-screen folder import browser (owner request 2026-11-15): the
 * granted folder tree's supported video children as a 3-column grid, IN
 * PLACE of the library list — a full-screen takeover, never a sheet.
 *
 * ALREADY-IMPORTED files stay VISIBLE but DIMMED (40% alpha + an
 * "Imported" tag) and take no gestures: the grid's action set is the
 * not-yet-imported files, and a dimmed cell answers "where are my
 * other clips?" without offering a redundant re-import. The
 * classification is [VideoTree.isImported] — the CHEAP same-uri /
 * same-document-id check (fingerprinting every folder file would read
 * each one); a same-bytes-different-file that slips past still lands on
 * [com.ritim.ritimcraw.data.SourceRepository.import]'s existing-record
 * branch and just refreshes.
 *
 * Naming: a TAP opens the import dialog with the file's display name
 * pre-filled in an editable field — confirm imports and the edited name
 * lands on the record through [onImport]'s editedName (the rename seam,
 * applied AFTER the import so the in-flight gyro auto-match pairs the
 * file's REAL name with its sibling .gcsv). A LONG-PRESS enters
 * multi-select (the pure [LibrarySelection] rules, on document URIs);
 * bulk import takes each file's OWN display name — no rename dialog per
 * file in bulk mode.
 *
 * PERMISSIONS: the tree grant (taken by the caller's tree picker) IS the
 * permission; each import ALSO takes its own persistable document grant
 * inside the caller's [onImport] — the clip survives reboots even if
 * the tree grant is later revoked. A failed grant is non-fatal there,
 * the same contract as the single-file picker.
 *
 * Failures stay honest: a single-file failure maps through
 * [ImportErrors.fromException] (stage + cause + recovery snackbar); a
 * bulk run ends with ONE summary — "N imported, M failed", or the
 * all-failed message naming the first cause when nothing landed. A file
 * that failed stays undimmed in the grid: retrying it through the
 * dialog shows its full per-file error.
 *
 * Device-gated (the GyroTree contract): the SAF query needs a real
 * ContentResolver, so this composable carries NO JVM tests — the owner
 * exercises it on device. Every pure rule it rides
 * ([VideoTree.isImported], [VideoTree.kindFor], [LibrarySelection],
 * [ImportErrors]) is JVM-tested.
 */
@Composable
fun FolderImportBrowser(
    treeUri: String,
    // Re-enumeration nonce (owner request 2026-11-16): a same-tree
    // re-pick — the dead-grant recovery, the user re-granting the SAME
    // folder through the change-folder button — leaves [treeUri]
    // unchanged, and produceState restarts only when a KEY changes; the
    // browser would keep showing the stale Failed listing even though
    // the grant now exists. The caller bumps this on EVERY pick, so the
    // enumeration reruns in place.
    listingGeneration: Int = 0,
    sources: List<MediaSource>,
    // One document import: the caller runs the SAME per-file path the
    // single-file picker uses (persistable grant, import with the kind
    // the FILE's extension routes to, thumbnail + metadata probe + gyro
    // auto-match, the combined success toast) and applies editedName as
    // a rename AFTER the import. Null editedName = keep the file's own
    // name (the bulk path).
    onImport: (uri: String, kind: SourceKind, editedName: String?) -> Result<MediaSource>,
    // The bulk path: the caller imports each document in sequence
    // through the same per-file path (quiet — no per-file toast) and
    // returns the summary.
    onImportMany: (docs: List<VideoTree.VideoDocument>) -> BulkImportOutcome,
    // Pre-import first-frame previews (owner request 2026-11-15): the
    // document-keyed generation pass + the store's folder namespace.
    // The pass is the caller's because it owns the engine seams AND the
    // whole-browser bound (the LibraryThumbnailBackfill precedent: the
    // caller's shared suspending gate caps how many previews develop at
    // once); the cell rides it through [folderPreviewAt] (persisted
    // file or null) and [onGenerateFolderPreview] (ONE bounded
    // generation when the file is missing). The LAZY grid bounds the
    // queue to COMPOSED cells only — a cell that never composes never
    // generates, so a 100+ video folder fills progressively, never
    // all at once.
    folderPreviewAt: (uri: String) -> String?,
    onGenerateFolderPreview: suspend (uri: String, displayName: String) -> Unit,
    // Change folder (owner request 2026-11-16): "sonrasında folder
    // menüsünde de değiştirmek isterse folder değiştir butonu olmalı" —
    // the top bar's folder button opens the SAF tree picker again. The
    // browser does NOT own the launcher: the caller (LibraryScreen)
    // routes the re-pick through the SAME launcher as a first-time pick,
    // so one callback handles grant-take, persist, and the old grant's
    // guarded release; the browser just re-keys on the new [treeUri] —
    // produceState re-enumerates in place, selection prunes itself.
    onChangeFolder: () -> Unit = {},
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The enumeration (device-gated): one child query, off the UI thread
    // (the FileListRow thumbnail-decode precedent). produceState cancels
    // when the tree changes or the browser leaves composition.
    val listing by produceState<FolderListing?>(initialValue = null, treeUri, listingGeneration) {
        value = withContext(Dispatchers.IO) {
            try {
                FolderListing.Ready(VideoTree.listVideos(context, treeUri))
            } catch (e: Exception) {
                FolderListing.Failed(e)
            }
        }
    }
    val docs = (listing as? FolderListing.Ready)?.docs.orEmpty()

    // Selection on document URIs — the SAME pure LibrarySelection rules
    // the library list rides. Stale ids (files the folder no longer
    // lists) are pruned on every enumeration change.
    var selectedUris by remember { mutableStateOf(setOf<String>()) }
    val selection = LibrarySelection.prune(selectedUris, docs.map { it.uri }.toSet())
    if (selection != selectedUris) selectedUris = selection
    val selecting = selection.isNotEmpty()

    // The import dialog's target + the editable name draft. The target
    // is the document (not just a uri): the confirm needs the FILE's
    // name for the kind routing while the draft carries the user's edit.
    var nameTarget by remember { mutableStateOf<VideoTree.VideoDocument?>(null) }
    var nameDraft by remember { mutableStateOf("") }

    // Back (the E6 rule shape): rows selected -> back exits SELECTION;
    // no selection -> back closes the browser to the library. The
    // selection handler registers LAST, so it wins while active.
    BackHandler { onClose() }
    BackHandler(enabled = selecting) {
        selectedUris = LibrarySelection.onBack(selectedUris)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        // The app shell's Scaffold already applies the status-bar inset
        // (the LibraryScreen E4 chain); a nested Scaffold would stack a
        // second one. This Scaffold serves only the grid + snackbar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(data) } },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // The takeover header: back to the library + the title, at
            // the Library title's own scale (the takeover swaps the
            // screen's content, not its voice). The change-folder button
            // (owner request 2026-11-16) sits at the row's end — the
            // SAME ic_folder glyph the toolbar's folder button wears,
            // so the affordance reads as "the folder, again" in both
            // places.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.library_folder_browser_back),
                    )
                }
                Text(
                    text = stringResource(R.string.library_folder_browser_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onChangeFolder) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = stringResource(R.string.library_folder_browser_change),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (selecting) {
                // The selection bar mirrors the library's (E2): close
                // exits the mode, the count carries the state, and the
                // action imports everything checked — no confirm dialog
                // (import is not destructive; the original files never
                // leave the folder).
                val closeDescription = stringResource(R.string.library_folder_selection_close)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { selectedUris = emptySet() }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = closeDescription,
                        )
                    }
                    Text(
                        text = stringResource(
                            R.string.library_folder_selection_count,
                            selection.size,
                        ),
                        style = MaterialTheme.typography.titleMedium.merge(Tnum),
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp),
                    )
                    TextButton(
                        onClick = {
                            val batch = docs.filter { it.uri in selection }
                            if (batch.isEmpty()) return@TextButton
                            val outcome = onImportMany(batch)
                            selectedUris = emptySet() // exit selection mode first
                            val text = if (
                                outcome.failed > 0 && outcome.imported == 0
                            ) {
                                // Nothing landed: name the first cause and
                                // the recovery (the ImportErrors shape).
                                val cause = outcome.firstError
                                    ?.let { it.message ?: it.javaClass.simpleName }
                                    .orEmpty()
                                context.getString(
                                    R.string.library_folder_import_summary_all_failed,
                                    outcome.failed,
                                    batch.size,
                                    cause,
                                )
                            } else {
                                context.getString(
                                    R.string.library_folder_import_summary,
                                    outcome.imported,
                                    outcome.failed,
                                )
                            }
                            scope.launch { snackbarHostState.showSnackbar(text) }
                        },
                    ) {
                        Text(stringResource(R.string.library_folder_selection_import))
                    }
                }
            }
            when (val state = listing) {
                // The query is still running: a blank grid (the query
                // completes in milliseconds; a spinner would flash).
                null -> Unit
                is FolderListing.Failed -> FailedFolder(
                    error = state.error,
                    modifier = Modifier.weight(1f),
                )
                is FolderListing.Ready -> {
                    if (state.docs.isEmpty()) {
                        EmptyFolder(Modifier.weight(1f))
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            // 16dp screen gutters (DESIGN.md Layout), 8dp
                            // between cells (8dp groups); no FAB, so no
                            // bottom clearance beyond the gutter.
                            contentPadding = PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = 8.dp,
                                bottom = 16.dp,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.docs, key = { it.uri }) { doc ->
                                FolderVideoCell(
                                    doc = doc,
                                    imported = VideoTree.isImported(doc.uri, sources),
                                    selecting = selecting,
                                    selected = doc.uri in selection,
                                    previewAt = folderPreviewAt,
                                    onGeneratePreview = onGenerateFolderPreview,
                                    onOpen = {
                                        nameTarget = doc
                                        nameDraft = doc.displayName
                                    },
                                    onToggleSelected = {
                                        selectedUris =
                                            LibrarySelection.toggle(selectedUris, doc.uri)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // The import-with-name dialog (owner request 2026-11-15): the file's
    // display name pre-filled, editable; confirm imports. A blank draft
    // imports with the file's own name (the rename seam rejects blank —
    // the record never loses its name to whitespace).
    val target = nameTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { nameTarget = null },
            title = { Text(stringResource(R.string.library_folder_import_dialog_title)) },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    label = {
                        Text(stringResource(R.string.library_folder_import_dialog_name_label))
                    },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        nameTarget = null // close first: the import runs on the caller
                        val edited = nameDraft.trim().takeIf { it.isNotBlank() }
                        val result = onImport(
                            target.uri,
                            // The KIND routes on the FILE's extension —
                            // never the user's edit (a renamed "Sunset"
                            // stays an MCRAW clip).
                            VideoTree.kindFor(target.displayName),
                            edited,
                        )
                        result.onFailure { e ->
                            val msg = ImportErrors.fromException("import", e)
                            val text = context.getString(
                                msg.messageRes,
                                msg.causeArg ?: e.javaClass.simpleName,
                            )
                            scope.launch { snackbarHostState.showSnackbar(text) }
                        }
                    },
                ) {
                    Text(stringResource(R.string.library_folder_import_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { nameTarget = null }) {
                    Text(stringResource(R.string.library_folder_import_dialog_cancel))
                }
            },
        )
    }
}

/**
 * One grid cell: a 1:1 tile with the file's REAL first-frame preview
 * when one is persisted (owner request 2026-11-15: "import edilmemiş
 * videoların bile thumbnailini göstermeli" — even NOT-yet-imported
 * videos show their thumbnail), the video glyph as the placeholder
 * while missing or loading (the Library row's placeholder-while-loading
 * shape), the file name beneath, and the stock CheckCircle pair while
 * selecting (the FileListRow affordance, moved to the tile's corner).
 *
 * The preview rides produceState keyed on the document's uri (the
 * FileListRow thumbnail-decode precedent): the persisted folder-namespace
 * PNG decodes off the UI thread when present; when missing, ONE bounded
 * generation runs through [onGeneratePreview] (the caller's shared
 * suspending gate — the LibraryThumbnailBackfill precedent) and the
 * fresh file decodes the same way. produceState cancels when the cell
 * leaves composition, so a fast scroll through a 100+ video folder
 * never queues the whole folder — only the composed window generates,
 * at most the gate's permits at a time. A failed generation keeps the
 * glyph (honest placeholder, retried on the next browser open).
 *
 * An already-imported cell renders DIMMED (40% alpha) with an
 * "Imported" tag and takes NO gestures — visible for context, outside
 * the action set. Its preview comes from the SAME document-keyed
 * namespace (the cell knows the document, not any library id): a
 * re-import of the same file lands on the existing record either way,
 * and the import cleanup deletes the folder-cache file.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderVideoCell(
    doc: VideoTree.VideoDocument,
    imported: Boolean,
    selecting: Boolean,
    selected: Boolean,
    previewAt: (uri: String) -> String?,
    onGeneratePreview: suspend (uri: String, displayName: String) -> Unit,
    onOpen: () -> Unit,
    onToggleSelected: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(
                if (imported) {
                    // Dimmed, non-interactive: the cell is context
                    // ("already in the library"), not an action.
                    Modifier.alpha(0.4f)
                } else {
                    Modifier.combinedClickable(
                        onClick = { if (selecting) onToggleSelected() else onOpen() },
                        onLongClick = onToggleSelected,
                    )
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            // The preview slot: the persisted folder-namespace PNG when
            // one exists, else ONE bounded generation then the fresh
            // file — the glyph stays underneath as the placeholder
            // (decode ONCE, persisted, never re-developed per
            // composition).
            val bitmap by produceState<Bitmap?>(initialValue = null, doc.uri) {
                var path = previewAt(doc.uri)
                if (path == null) {
                    onGeneratePreview(doc.uri, doc.displayName)
                    path = previewAt(doc.uri)
                }
                if (path != null) {
                    value = withContext(Dispatchers.IO) {
                        try {
                            BitmapFactory.decodeFile(path)
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (imported) {
                Text(
                    text = stringResource(R.string.library_folder_imported),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp),
                )
            }
            if (selecting && !imported) {
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
        Text(
            text = doc.displayName,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun EmptyFolder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.library_folder_browser_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailedFolder(error: Exception, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            // The honest cause + recovery (DESIGN.md Error states), never
            // a bare "failed": a dropped grant or unreachable provider.
            text = stringResource(
                R.string.library_folder_browser_failed,
                error.message ?: error.javaClass.simpleName,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 32.dp, end = 32.dp),
        )
    }
}
