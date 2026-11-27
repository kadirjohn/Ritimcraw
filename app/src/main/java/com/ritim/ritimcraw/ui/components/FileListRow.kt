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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 *
 * The format badge (owner request 2026-11-26): a small squircle pill at
 * the text block's BOTTOM-END — MCRAW in the highlighted treatment,
 * other formats neutral — carried through [badge] so the shared row
 * stays caller-driven (the Library's pure LibraryRowModel.kindBadge
 * decides the text and style; null renders nothing).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileListRow(
    fileName: String,
    supportingLine: String?,
    thumbnailPath: String?,
    modifier: Modifier = Modifier,
    badge: FormatBadge? = null,
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
    containerOrientationDeg: Int = -1,
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
                placeholderAspect = ThumbnailRow.effectiveAspect(
                    frameWidth, frameHeight, containerOrientationDeg, manualRotationDeg,
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
                // The format badge (owner request 2026-11-26): the last
                // line of the text block, right-aligned — the row's
                // bottom-right. A small squircle pill (the duration
                // badge's shape language, smaller); decorative text
                // (the row's own click target carries the row's
                // semantics).
                if (badge != null) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        FormatBadgePill(badge)
                    }
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
 *
 * The GRID half (owner request 2026-11-26: rows must never interleave,
 * and a row's height is set by its tallest clip): [gridRows] chunks the
 * clips into rows at the CURRENT column count and picks each row's
 * shared box aspect (the row's lowest aspect = tallest clip, capped at
 * 4:3 portrait); [rowTile] sizes one cell inside that row. The screen
 * renders each row as its own container, so a taller clip can never
 * push into the row below.
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

    /** The tallest a grid tile gets, as a multiple of its column width. */
    const val GRID_MAX_HEIGHT_RATIO = 4f / 3f

    /**
     * A Library grid tile: the cell box has the frame's own aspect
     * ([boxAspect] = width / height, never taller than 4:3 portrait) and
     * the image is fitted and centred in it, filling [imageWidthFraction]
     * of the column width (1 unless the clip is more vertical than 3:4).
     */
    data class GridTile(val boxAspect: Float, val imageWidthFraction: Float)

    /** The grid tile for a width/height [aspect] (<= 0 or NaN -> 16:9). */
    fun gridTile(aspect: Float): GridTile {
        val a = if (aspect > 0f && aspect.isFinite()) aspect else DEFAULT_ASPECT
        val minAspect = 1f / GRID_MAX_HEIGHT_RATIO
        return if (a >= minAspect) GridTile(a, 1f) else GridTile(minAspect, a / minAspect)
    }

    /**
     * One GRID row's geometry (owner request 2026-11-26: rows must never
     * interleave): the clips chunked into this row, and the aspect every
     * tile's box shares — the ROW aspect, the lowest (tallest) aspect
     * among the row's clips.
     *
     * All tiles in the row render with the SAME box height: the box takes
     * the full column width and [rowAspect] as its aspect ratio, so a
     * portrait clip dictates a tall row and the wider clips in the same
     * row center their own-aspect image inside the shared-height box
     * (imageWidthFraction < 1 — the image keeps its own aspect, letterboxed
     * horizontally inside the cell, never cropped, never distorted).
     */
    data class GridRow(val aspects: List<Float>, val rowAspect: Float)

    /**
     * Pure: chunk the clips into grid rows and give each row its shared
     * box aspect (owner request 2026-11-26 + the dynamic refinement: a
     * 4:3 or portrait clip in a row is that row's tallest content, so
     * the ROW's height is set by it). Returns one [GridRow] per row, in
     * list order; [clips] carries each clip's aspect in the same order.
     *
     * [columns] is the CURRENT column count — the chunking changes with
     * orientation (3 portrait / more landscape), and the row heights
     * re-derive with it. Degenerate aspects (<= 0, NaN, infinite) count as
     * the [DEFAULT_ASPECT] 16:9 — the same fallback [gridTile] applies.
     * The row aspect is never taller than 4:3 portrait
     * ([GRID_MAX_HEIGHT_RATIO]) — a portrait row cannot take over the
     * screen. Degenerate [columns] (< 1) is clamped to 1; an empty list
     * yields no rows.
     */
    fun gridRows(clips: List<Float>, columns: Int): List<GridRow> {
        if (clips.isEmpty()) return emptyList()
        val perRow = maxOf(1, columns)
        val minAspect = 1f / GRID_MAX_HEIGHT_RATIO
        return clips.chunked(perRow).map { chunk ->
            GridRow(
                aspects = chunk,
                rowAspect = chunk
                    .map { if (it > 0f && it.isFinite()) it else DEFAULT_ASPECT }
                    .min()
                    .coerceAtLeast(minAspect),
            )
        }
    }

    /**
     * The tile one cell renders inside a row of [rowAspect]: the box
     * fills the column at the ROW's aspect (all cells share one height),
     * and the clip's own [aspect] image is fitted and centered inside it
     * — filling the box's width only when the clip is the row's tallest
     * content; a wider clip uses proportionally less of the box's width
     * ([imageWidthFraction] = clipAspect / rowAspect, letterboxed
     * horizontally, never cropped).
     */
    fun rowTile(aspect: Float, rowAspect: Float): GridTile {
        val a = if (aspect > 0f && aspect.isFinite()) aspect else DEFAULT_ASPECT
        val row = if (rowAspect > 0f && rowAspect.isFinite()) rowAspect else DEFAULT_ASPECT
        return GridTile(boxAspect = row, imageWidthFraction = (a / row).coerceAtMost(1f))
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

    /**
     * The clip's EFFECTIVE aspect: the stored [width]x[height] turned by the
     * container orientation plus the manual rotation
     * ([RotationState.effectiveDeg]; an unknown container, < 0, counts as 0).
     * The one rule the grid rows, the tiles' placeholders and the thumbnail
     * slot share, so a row's height can never disagree with its tiles.
     */
    fun effectiveAspect(
        width: Int,
        height: Int,
        containerOrientationDeg: Int,
        manualRotationDeg: Int,
    ): Float = placeholderAspect(
        width,
        height,
        com.ritim.ritimcraw.data.RotationState.effectiveDeg(
            maxOf(0, containerOrientationDeg),
            manualRotationDeg,
        ),
    )
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

/**
 * A media row's format badge (owner request 2026-11-26): the clip's kind
 * token in a small squircle pill at the row's bottom-end. The style rides
 * the badge (caller-decided, the pure kindBadge rule): HIGHLIGHTED (the
 * theme's primaryContainer pair), NEUTRAL (the surfaceContainerHighest
 * pair the duration badge uses) and MOTIONCAM — the MCRAW badge's fixed
 * MotionCam blue (owner request 2026-11-27, M2: "MCRAW rozeti monet veya
 * custom temaya aldırış etmeden mavi renk olsun, motioncam'in rengi
 * bu"), the ONE deliberately theme-independent style in this component.
 */
enum class FormatBadgeStyle { HIGHLIGHTED, NEUTRAL, MOTIONCAM }

/**
 * The MOTIONCAM badge's fixed container color (M2): a lively blue close
 * to MotionCam's brand blue, constant across every theme (monet and
 * custom alike) because the OWNER asked for the brand color itself, not
 * a theme role — the documented exception to this component's
 * theme-driven contract. One definition; white 10sp text reads on it
 * (contrast about 4.0). MotionCam's exact brand value cannot be
 * verified from code (image-blind agent): a morning tweak is a
 * one-line change HERE.
 */
val MOTIONCAM_BLUE = Color(0xFF1E88E5)

/** The badge a row renders: its style plus its (never localized) text. */
data class FormatBadge(val style: FormatBadgeStyle, val text: String)

/**
 * The badge pill itself (owner request 2026-11-26): a 20dp-tall squircle
 * — RoundedCornerShape(6.dp), the "squircle" reading of the app's
 * smaller pills (the LibraryFilterChip's 40dp/fully-round pill and the
 * duration badge's CircleShape carry the pill language; a 6dp corner on
 * a 20dp pill reads as the same family at badge scale) — with 10sp
 * medium text. Decorative: contentDescription = null (the badge names
 * the format the row's supporting line already describes; screen readers
 * get the row, not the badge).
 */
@Composable
private fun FormatBadgePill(badge: FormatBadge) {
    val colorScheme = MaterialTheme.colorScheme
    val containerColor = when (badge.style) {
        FormatBadgeStyle.HIGHLIGHTED -> colorScheme.primaryContainer
        FormatBadgeStyle.NEUTRAL -> colorScheme.surfaceContainerHighest
        // Fixed brand blue, never a theme role (M2) — the deliberate
        // exception documented on [MOTIONCAM_BLUE].
        FormatBadgeStyle.MOTIONCAM -> MOTIONCAM_BLUE
    }
    val contentColor = when (badge.style) {
        FormatBadgeStyle.HIGHLIGHTED -> colorScheme.onPrimaryContainer
        FormatBadgeStyle.NEUTRAL -> colorScheme.onSurface
        FormatBadgeStyle.MOTIONCAM -> Color.White
    }
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(containerColor)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = badge.text,
            style = TextStyle(
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = contentColor,
            maxLines = 1,
        )
    }
}
