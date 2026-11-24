package com.ritim.ritimcraw.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
 * 2026-11-11d §4.3): rounded thumbnail whose slot has the clip's OWN
 * aspect ratio ([ThumbnailRow.slotSize] — fitted into at most 96dp wide
 * x 76dp tall, no background, no bands), filename to the right limited
 * to two lines with ellipsis, supporting metadata line beneath it. The
 * row is as tall as max(thumbnail, text block) plus the 8dp top/bottom
 * padding. A plain Row (not ListItem) so the thumbnail and text block
 * size themselves; the caller supplies the supporting line through the
 * pure LibraryRowModel (existing metadata only — nothing is invented).
 *
 * Manual rotation (owner request 2026-11-14) lives in the ICON FILE: the
 * icon is re-developed on every rotation change (the da763c9 contract),
 * so the loaded bitmap's own size gives the slot's ratio. Only the
 * placeholder, shown until the bitmap loads, derives its ratio from the
 * record's [frameWidth]/[frameHeight] and [manualRotationDeg] so rows
 * do not jump when the icon arrives.
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
    frameWidth: Int = 0,
    frameHeight: Int = 0,
    manualRotationDeg: Int = 0,
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
                placeholderAspect = ThumbnailRow.placeholderAspect(
                    frameWidth, frameHeight, manualRotationDeg,
                ),
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
 * [ChipWidths] precedent): the slot has the frame's own aspect ratio,
 * fitted into a [MAX_WIDTH_DP] x [MAX_HEIGHT_DP] box (16:9 -> 96x54,
 * 4:3 -> 96x72, 9:16 -> ~43x76), so landscape rows stay compact and a
 * portrait clip cannot take over the screen (decided 2026-11-14).
 */
internal object ThumbnailRow {

    /** The widest a thumbnail slot gets. */
    const val MAX_WIDTH_DP = 96f

    /** The tallest a thumbnail slot gets. */
    const val MAX_HEIGHT_DP = 76f

    /** The ratio assumed when the frame size is unknown (16:9). */
    const val DEFAULT_ASPECT = 16f / 9f

    /** The screen's 16dp gutter the slot (and the divider) starts at. */
    const val GUTTER_DP = 16f

    /** A slot size in dp. */
    data class Slot(val widthDp: Float, val heightDp: Float)

    /** The slot for a width/height [aspect] (<= 0 or NaN -> 16:9). */
    fun slotSize(aspect: Float): Slot {
        val a = if (aspect > 0f && aspect.isFinite()) aspect else DEFAULT_ASPECT
        val widthByHeight = MAX_HEIGHT_DP * a
        return if (widthByHeight <= MAX_WIDTH_DP) {
            Slot(widthByHeight, MAX_HEIGHT_DP)
        } else {
            Slot(MAX_WIDTH_DP, MAX_WIDTH_DP / a)
        }
    }

    /**
     * The placeholder's aspect from the record's frame size and manual
     * rotation (a quarter turn swaps the axes); unknown size -> 16:9.
     */
    fun placeholderAspect(width: Int, height: Int, manualRotationDeg: Int): Float {
        if (width <= 0 || height <= 0) return DEFAULT_ASPECT
        val swapped = Math.floorMod(manualRotationDeg / 90, 2) == 1
        return if (swapped) height.toFloat() / width else width.toFloat() / height
    }
}

/**
 * The row's leading thumbnail: a rounded slot with the loaded icon's own
 * aspect ratio ([ThumbnailRow.slotSize]), no background, the persisted
 * first-frame PNG filling it; until it loads, a plain placeholder of the
 * [placeholderAspect] size (same size, so rows do not jump), decoded off
 * the UI thread (produceState cancels the load when the row leaves
 * composition). The icon's pixels carry the manual rotation.
 */
@Composable
private fun ClipThumbnail(
    path: String?,
    accessLost: Boolean,
    placeholderAspect: Float,
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
    val b = bitmap
    val slot = ThumbnailRow.slotSize(
        if (b != null) b.width.toFloat() / b.height.coerceAtLeast(1) else placeholderAspect,
    )
    Box(
        modifier = modifier
            .size(width = slot.widthDp.dp, height = slot.heightDp.dp)
            .clip(MaterialTheme.shapes.extraSmall),
    ) {
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
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
