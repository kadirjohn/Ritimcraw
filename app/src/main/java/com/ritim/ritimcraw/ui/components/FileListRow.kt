package com.ritim.ritimcraw.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.ui.theme.Tnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Thumbnail-first media row for the main screens (owner request
 * 2026-11-11d §4.3): rounded ~76dp square thumbnail, filename to the
 * right limited to two lines with ellipsis, supporting metadata line
 * beneath it. A plain Row (not ListItem) so the thumbnail and text block
 * size themselves; the caller supplies the supporting line through the
 * pure LibraryRowModel (existing metadata only — nothing is invented).
 *
 * Selection (E2) keeps its exact behavior: long-press enters selection
 * with this row checked, taps toggle while selecting (never open), and
 * the trailing slot carries the stock core CheckCircle pair — filled and
 * primary-tinted when checked. Outside selection mode a tap behaves
 * exactly as before (open, or grant access when the row is lost).
 *
 * [trailingContent] is the end slot for non-selection trailing content
 * (the Grant-access action on access-lost rows).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    fileName: String,
    supportingLine: String?,
    thumbnailPath: String?,
    modifier: Modifier = Modifier,
    supportingFallback: (@Composable () -> Unit)? = null,
    accessLostLine: String? = null,
    accessLost: Boolean = false,
    selecting: Boolean = false,
    selected: Boolean = false,
    onToggleSelected: () -> Unit = {},
    onOpen: () -> Unit = {},
    onGrantAccess: () -> Unit = {},
    trailingContent: (@Composable () -> Unit)? = null,
    onLongClick: () -> Unit = {},
    showDivider: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {
                        if (selecting) {
                            onToggleSelected()
                        } else if (accessLost) {
                            onGrantAccess()
                        } else {
                            onOpen()
                        }
                    },
                    onLongClick = onLongClick,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ClipThumbnail(
                path = thumbnailPath,
                accessLost = accessLost,
                modifier = Modifier.padding(top = 8.dp, bottom = 8.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (supportingLine != null) {
                    Text(
                        supportingLine,
                        style = MaterialTheme.typography.bodySmall.merge(Tnum),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (supportingFallback != null) {
                    supportingFallback()
                }
                if (accessLostLine != null) {
                    Text(
                        accessLostLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            when {
                // While selecting (E2) the trailing slot carries the row's
                // checkbox state — the stock core-icon pair (outlined
                // CheckCircle unchecked, filled CheckCircle checked,
                // primary tint). The whole row toggles
                // (combinedClickable), so the icon needs no own target.
                selecting -> {
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
                        modifier = Modifier.padding(end = 12.dp),
                    )
                }
                trailingContent != null -> trailingContent()
            }
        }
        if (showDivider) {
            // Aligned with the text block (16dp screen padding + 76dp
            // thumbnail + 16dp gap), not the thumbnail's left edge.
            HorizontalDivider(
                modifier = Modifier.padding(start = 108.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * The row's leading thumbnail (owner request 2026-11-11d §4.3): rounded
 * ~76dp square, the persisted first-frame PNG cropped to fill, or the
 * placeholder box while missing — decoded off the UI thread (produceState
 * cancels the load when the row leaves composition).
 */
@Composable
private fun ClipThumbnail(
    path: String?,
    accessLost: Boolean,
    modifier: Modifier = Modifier,
) {
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
        modifier = modifier
            .size(76.dp)
            .clip(MaterialTheme.shapes.extraSmall)
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
                modifier = Modifier.align(Alignment.Center),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
