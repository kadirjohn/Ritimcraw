package com.ritim.ritimcraw.ui.library

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.AndroidUriResolver
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.SourceRepository
import com.ritim.ritimcraw.data.ThumbnailRules
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

/**
 * Library of imported clips (DESIGN.md Library): filter chips with a check
 * icon on the selected chip, ListItem rows, and an extended FAB "Import"
 * that opens the SAF document picker (ACTION_OPEN_DOCUMENT).
 *
 * The picked document is imported through [SourceRepository.import] with
 * takePersistableUriPermission so the clip survives reboots. Failures show
 * an error snackbar naming stage + cause + recovery (DESIGN.md Error states)
 * via the pure [ImportErrors] mapping.
 *
 * Rows with revoked access show "Access lost" with a "Grant access" action
 * instead of disappearing (QA-23).
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
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val visible = sources.filter { matches(filter, it) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val importDone = stringResource(R.string.library_import_done)

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
            // Non-fatal: refreshAccess() will surface a lost row if this
            // grant really is required; the import attempt still runs.
        }
        val result = runCatching { onImport(uri.toString()) }
            .getOrElse { e -> Result.failure(e) }
        result.fold(
            onSuccess = { scope.launch { snackbarHostState.showSnackbar(importDone) } },
            onFailure = { e ->
                val msg = ImportErrors.fromException("import", e)
                val text = context.getString(
                    msg.messageRes,
                    msg.causeArg ?: e.javaClass.simpleName,
                )
                // The message resource already begins with "Import failed:"
                // (stage), so the snackbar shows it as-is — no duplicate title.
                scope.launch { snackbarHostState.showSnackbar(text) }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) { data -> Snackbar(data) } },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.library_import)) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            Text(
                text = stringResource(R.string.library_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LibraryFilter.entries.forEach { f ->
                    FilterChip(
                        selected = filter == f,
                        onClick = { filter = f },
                        label = { Text(stringResource(f.labelRes)) },
                        leadingIcon = if (filter == f) {
                            { Icon(Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                    )
                }
            }
            if (visible.isEmpty()) {
                EmptyLibrary(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(visible, key = { it.id }) { source ->
                        LibraryRow(
                            source = source,
                            thumbnailPath = thumbnailAt(source.id),
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
    onOpen: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    val accessLost = source.access == Access.LOST
    // The supporting line's parts (owner request §1): recorded resolution,
    // then duration — each part hides when unknown, never faked. The pure
    // model keeps the hide rules out of the composable.
    val durationText = LibraryRowModel.durationText(source)
    val supporting = LibraryRowModel.supportingLine(source, durationText)
    ListItem(
        // The clip's real file name (owner request §2b); the id when the
        // resolver could not name it — displayOrId is never blank.
        headlineContent = {
            Text(
                source.displayOrId,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                // Format line (body-small per DESIGN.md Typography): recorded
                // resolution + duration (owner request §1); the kind line
                // degrades gracefully until the generation pass probes the
                // clip. Tabular numerals so the digits never reflow.
                if (supporting != null) {
                    Text(
                        supporting,
                        style = MaterialTheme.typography.bodySmall.merge(Tnum),
                    )
                } else {
                    Text(
                        stringResource(
                            if (source.kind == SourceKind.MCRAW) R.string.library_row_kind_mcraw
                            else R.string.library_row_kind_encoded
                        ),
                    )
                }
                if (accessLost) {
                    Text(
                        stringResource(R.string.library_row_access_lost),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        leadingContent = {
            ClipThumbnail(
                path = thumbnailPath,
                accessLost = accessLost,
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
        modifier = Modifier.clickable { if (accessLost) onGrantAccess() else onOpen() },
    )
}

/**
 * The row's leading thumbnail (owner request 2026-11-11 section 1): the
 * persisted first-frame PNG (developed once with the NEUTRAL grade — the
 * owner accepts it looks dark), or the placeholder box while missing —
 * never a crash, never a re-develop per composition. Rounded with the
 * theme's shape, cropped to fill.
 */
@Composable
private fun ClipThumbnail(
    path: String?,
    accessLost: Boolean,
) {
    // Decode off the UI thread (produceState cancels the load when the row
    // leaves composition — a fast scroll never strands decodes).
    val bitmap by path?.let { p ->
        produceState<Bitmap?>(initialValue = null, p) {
            value = withContext(Dispatchers.IO) {
                try {
                    BitmapFactory.decodeFile(p)
                } catch (e: Exception) {
                    null
                }
            }
        }
    } ?: remember { mutableStateOf<Bitmap?>(null) }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else if (accessLost) {
            // The access-lost marker rides the placeholder while the clip
            // cannot be probed (the row keeps its Grant access action).
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.align(androidx.compose.ui.Alignment.Center),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
