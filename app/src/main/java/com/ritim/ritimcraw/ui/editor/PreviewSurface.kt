package com.ritim.ritimcraw.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.PreviewFrame

/**
 * Pure aspect-ratio fallback chain for the preview surface
 * (JVM-testable without Compose). The preview must be shaped like the
 * clip itself (owner finding 2026-11-10: "video 4:3, 16:9 olabilir...
 * sanki fixed bir box var"), never a fixed box.
 *
 * Chain, first match wins:
 *  1. the current rendered [PreviewFrame] dimensions — already rotated
 *     upright by the renderer, used as-is;
 *  2. the persisted MediaSource import stats (width/height probed by the
 *     engine pass at import), so even the pre-first-frame placeholder has
 *     the right shape;
 *  3. [DEFAULT_RATIO] (16:9) when nothing is known.
 *
 * A pair with a non-positive side counts as unknown and is skipped; a
 * half-known pair never shapes the surface.
 */
object PreviewSurfaceRatio {

    /** Fallback when no dimension source is known: 16:9 landscape video. */
    const val DEFAULT_RATIO = 16f / 9f

    /**
     * The preview surface aspect ratio (width / height) for the given
     * dimension candidates, per the chain in the object KDoc.
     */
    fun of(
        frameWidth: Int,
        frameHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Float = when {
        frameWidth > 0 && frameHeight > 0 -> frameWidth.toFloat() / frameHeight
        sourceWidth > 0 && sourceHeight > 0 -> sourceWidth.toFloat() / sourceHeight
        else -> DEFAULT_RATIO
    }
}

/**
 * Clip preview surface shaped like the clip itself (owner finding
 * 2026-11-10): the ratio comes from [PreviewSurfaceRatio], so the surface
 * is 4:3 for a 4:3 clip, 9:16 for a 9:16 clip, and so on — never a fixed
 * box. Inside the caller's bounded space it fills the width whenever the
 * ratio fits; a clip taller than the available space bounds the surface
 * by height (centered by the caller's wrapper), keeping the clip's shape
 * instead of overflowing the controls below.
 *
 * Every state renders centered INSIDE the same shaped surface:
 * the rendered frame (ContentScale.Fit — letterboxed during any ratio
 * transition, e.g. persisted stats vs the first rendered frame, never
 * stretched), the access-lost notice, the stage+cause+recovery error
 * text (FAILED only happens with no frame on screen, so the error owns
 * the surface with no placeholder underneath), a spinner while the first
 * render is in flight, and the pre-engine placeholder text.
 *
 * Pure layout extraction from ClipEditorScreen (2026-11-10): state
 * hoisting, render tiers, debouncing and session logic are unchanged —
 * the screen still owns all bookkeeping and passes [state] in.
 */
@Composable
fun PreviewSurface(
    frame: PreviewFrame?,
    state: PreviewState,
    errorText: String?,
    accessLost: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    modifier: Modifier = Modifier,
) {
    val ratio = PreviewSurfaceRatio.of(
        frameWidth = frame?.width ?: 0,
        frameHeight = frame?.height ?: 0,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.aspectRatio(ratio),
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            when {
                f != null -> Image(
                    bitmap = remember(f) { f.toImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                // The SAF grant was revoked or the clip moved (the existing
                // access-lost pattern from library rows) — the preview cannot
                // run without the source.
                accessLost -> Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        stringResource(R.string.editor_not_loaded_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        stringResource(R.string.library_row_access_lost),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                // FAILED only happens with no frame on screen (stateOf), so
                // the error text owns the surface alone — the placeholder
                // never renders underneath it.
                state == PreviewState.FAILED && errorText != null -> Text(
                    errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
                // First render in flight and nothing to show yet.
                state == PreviewState.RENDERING -> CircularProgressIndicator()
                else -> Text(
                    stringResource(R.string.editor_preview_device_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
        }
    }
}

/** PreviewFrame -> Compose ImageBitmap via Android Bitmap (UI layer only). */
private fun PreviewFrame.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
