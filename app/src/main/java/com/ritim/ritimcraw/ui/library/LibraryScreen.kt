package com.ritim.ritimcraw.ui.library

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind

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
 * icon on the selected chip, ListItem rows, and an extended FAB "Import".
 *
 * Rows with revoked access show "Access lost" with a "Grant access" action
 * instead of disappearing (QA-23).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    sources: List<MediaSource>,
    onOpenClip: (String) -> Unit,
    onImport: () -> Unit,
    onGrantAccess: (MediaSource) -> Unit,
    modifier: Modifier = Modifier,
) {
    var filter by remember { mutableStateOf(LibraryFilter.ALL) }
    val visible = sources.filter { matches(filter, it) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
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
                            onOpen = { onOpenClip(source.id) },
                            onGrantAccess = { onGrantAccess(source) },
                        )
                    }
                }
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
    onOpen: () -> Unit,
    onGrantAccess: () -> Unit,
) {
    val accessLost = source.access == Access.LOST
    ListItem(
        headlineContent = { Text(source.id) },
        supportingContent = {
            Column {
                Text(
                    stringResource(
                        if (source.kind == SourceKind.MCRAW) R.string.library_row_kind_mcraw
                        else R.string.library_row_kind_encoded
                    ),
                )
                if (accessLost) {
                    Text(
                        stringResource(R.string.library_row_access_lost),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (accessLost) Icons.Outlined.Warning else Icons.Filled.Add,
                contentDescription = null,
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

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
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
