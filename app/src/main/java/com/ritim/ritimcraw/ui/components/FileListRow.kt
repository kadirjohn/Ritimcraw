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
 * 2026-11-11d §4.3; the 2026-11-14 uniform-slot extension): rounded
 * thumbnail in ONE FIXED 76dp square slot ([ThumbnailRow]) for every
 * frame ratio — a vertical, a horizontal and an open-gate clip all
 * render rows of the same height, the icon fitted INSIDE the slot at
 * its own ratio (ContentScale.Fit — letterboxed on the slot's
 * surfaceContainerHighest background, never cropped, never
 * distorted), filename
 * to the right limited to two lines with ellipsis, supporting metadata
 * line beneath it. A plain Row (not ListItem) so the thumbnail and text
 * block size themselves; the caller supplies the supporting line
 * through the pure LibraryRowModel (existing metadata only — nothing
 * is invented).
 *
 * Manual rotation (owner request 2026-11-14: the icon must match the
 * rotated look the user chose in the editor) lives in the ICON FILE,
 * not the row: the icon is re-developed on every rotation change (the
 * da763c9 contract), so the upright pixels display correctly inside
 * the uniform slot as-is — the row passes no dims and no rotation, and
 * the box never swaps its ratio.
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
                // The thumbnail carries the screen's 16dp gutter (owner
                // request 2026-11-14: the icons sat flush against the left
                // edge — "sadece thumbnail'ler için" margin, ONLY the
                // thumbnails) — the SAME [ThumbnailRow.GUTTER_DP] the
                // divider below starts at (owner finding 2026-11-14: a
                // divider that runs to the screen's edge must also begin
                // at the thumbnail's edge).
                modifier = Modifier.padding(
                    start = ThumbnailRow.GUTTER_DP.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
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
            // Starts at the THUMBNAIL's edge, not the text block's (owner
            // finding 2026-11-14: "ekranın sonuna kadar ilerliyorsa
            // thumbnail için de ilerlemeli" — a divider that runs all the
            // way to the screen's edge must begin at the thumbnail's
            // gutter, [ThumbnailRow.GUTTER_DP]), and keeps running to the
            // screen's edge (no end padding).
            HorizontalDivider(
                modifier = Modifier.padding(start = ThumbnailRow.GUTTER_DP.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }
    }
}

/**
 * Pure thumbnail-slot arithmetic (unit-tested in ThumbnailRowTest, the
 * [ChipWidths] precedent): the row's thumbnail slot is ONE FIXED 76x76dp
 * SQUARE for every frame ratio (owner finding 2026-11-14: the ratio-true
 * 40..120dp band made a 9:16 row 120dp tall while a 16:9 row sat at
 * 43dp — the list's rhythm broke between vertical, horizontal and
 * open-gate clips, and the vertical rows ate the screen).
 *
 * The uniform slot is the standard gallery pattern over a tighter clamp
 * band: only a fixed height makes the rhythm literally identical between
 * ratios (a band still leaves every row a different height), and 76dp is
 * the square the list shipped with — inside the 76-96dp band the finding
 * allowed, and it keeps the divider/text geometry stable. The icon stays
 * RATIO-TRUE inside the slot (ContentScale.Fit: a 9:16 icon letterboxes
 * on the surfaceContainerHighest background, a 16:9 one letterboxes top
 * and bottom, neither crops or distorts), so the clip's look survives —
 * only the box stops announcing the ratio.
 */
internal object ThumbnailRow {

    /** The fixed thumbnail slot's width (the shipped 76dp square's width). */
    const val WIDTH_DP = 76f

    /** The fixed thumbnail slot's height: the square, for every ratio. */
    const val HEIGHT_DP = 76f

    /** The screen's 16dp gutter the slot (and the divider) starts at. */
    const val GUTTER_DP = 16f
}

/**
 * The row's leading thumbnail (owner request 2026-11-11d §4.3; the
 * 2026-11-14 uniform-slot extension): rounded FIXED 76x76dp square slot
 * ([ThumbnailRow] — the gallery pattern: one row height for every frame
 * ratio), the persisted first-frame PNG fitted INSIDE at its own ratio
 * (ContentScale.Fit — the icon letterboxes on the slot's
 * surfaceContainerHighest background, never cropped, never distorted),
 * or the same square placeholder while missing — decoded off the UI
 * thread (produceState cancels the load when the row leaves
 * composition). The box keeps the extraSmall shape and the
 * surfaceContainerHighest background: the rounded
 * square thumbnail IS the modern look, no added chrome.
 *
 * The icon's own pixels carry the manual rotation (the da763c9
 * contract: the icon is re-developed on every rotation change), so the
 * row passes no dims and no rotation — the upright icon displays
 * correctly inside the uniform slot as-is.
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
            .size(
                width = ThumbnailRow.WIDTH_DP.dp,
                height = ThumbnailRow.HEIGHT_DP.dp,
            )
            .clip(MaterialTheme.shapes.extraSmall)
            // Owner request 2026-11-14: the letterbox area behind the
            // ratio-true icon reads as a PLAIN GRAY, not the Monet tone.
            // surfaceVariant rides the NeutralVariant palette, which
            // carries the wallpaper's hue (dynamic color) — the slot
            // tinted itself with whatever the wallpaper was.
            // surfaceContainerHighest rides the NEUTRAL palette (the
            // container family the editor scheme already proves gray:
            // 0xFF3b3b3b): light mode keeps the exact tone surfaceVariant
            // had (NeutralVariant90 -> Neutral90), dark mode drops from
            // NV30 to N22 — still clearly a step above the dark surface,
            // now chroma-free in BOTH modes.
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
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
