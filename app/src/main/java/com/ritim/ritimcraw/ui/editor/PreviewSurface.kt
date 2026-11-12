package com.ritim.ritimcraw.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.engine.PreviewFrame
import kotlin.math.abs
import kotlin.math.hypot

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

    /**
     * The surface ratio AFTER the manual rotation delta (owner request
     * 2026-11-11 section 5): quarter turns swap the shape the box takes,
     * half turns do not. Pure so the display decision is JVM-testable.
     */
    fun withManualRotation(
        uprightRatio: Float,
        manualRotationDeg: Int,
    ): Float =
        if (com.ritim.ritimcraw.data.RotationState.swapsDimensions(manualRotationDeg)) {
            1f / uprightRatio
        } else {
            uprightRatio
        }

    /**
     * The size the preview surface may claim inside a bounded region
     * (owner finding 2026-11-11 E1: "dikey görünümü açınca malesef ki
     * sağ üstteki üç nokta ile belirtilen menü kayboldu" — the portrait
     * preview covered the top bar's ⋮ menu). Pure so the claim rule is
     * JVM-testable; the composable half only measures with it.
     *
     * Rule: fill the region's width when the full-width height fits the
     * region's height cap; otherwise bound the surface by the cap and
     * derive the width from the ratio. The result NEVER exceeds either
     * bound — unlike `Modifier.aspectRatio`, whose fallback sizes a
     * child that cannot satisfy the incoming constraints at full width
     * by the UNBOUNDED full-width height (Compose foundation-layout
     * 1.7.3 AspectRatio.kt tryMaxWidth(enforceConstraints = false)),
     * letting a portrait-shaped surface overflow its capped wrapper
     * and paint over the top bar. This rule replaces that fallback.
     *
     * Degenerate inputs collapse to zero (an empty region claims
     * nothing); a non-positive ratio claims nothing (guarded upstream
     * by the ratio chain, which never returns one).
     */
    fun claimedSize(
        regionWidth: Int,
        regionHeight: Int,
        ratio: Float,
    ): Pair<Int, Int> {
        if (regionWidth <= 0 || regionHeight <= 0 || ratio <= 0f) return 0 to 0
        val fullWidthHeight = (regionWidth / ratio).toInt()
        return if (fullWidthHeight <= regionHeight) {
            regionWidth to fullWidthHeight
        } else {
            val boundedWidth = (regionHeight * ratio).toInt().coerceAtMost(regionWidth)
            boundedWidth to regionHeight
        }
    }
}

/**
 * Clip preview surface shaped like the clip itself (owner finding
 * 2026-11-10): the ratio comes from [PreviewSurfaceRatio], so the surface
 * is 4:3 for a 4:3 clip, 9:16 for a 9:16 clip, and so on — never a fixed
 * box. Inside the caller's bounded space it fills the width whenever the
 * ratio fits; a clip taller than the available space bounds the surface
 * by height (centered by the caller's wrapper), keeping the clip's shape
 * instead of overflowing the controls below. That bound is enforced by
 * [PreviewSurfaceRatio.claimedSize] through the [previewBounds] sizer
 * (owner finding 2026-11-11 E1): a portrait-shaped preview used to
 * overflow the capped wrapper and cover the top bar's ⋮ menu — the
 * unenforced aspectRatio fallback — and now never does.
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
    // Manual rotation delta (owner request 2026-11-11 section 5): the
    // pixels arrive ALREADY upright (the native develop rotates by the
    // container orientation — per-frame override included), so the display
    // rotates ONLY this delta, never both. Default 0 keeps every existing
    // call site (and PreviewSurface's own tests) working unchanged.
    manualRotationDeg: Int = 0,
    // Clip identity for the zoom/pan state (owner request 2026-11-11
    // section 6): the transform RESETS when the clip changes, survives
    // configuration changes (saveable), and is never keyed on playback
    // (play/pause preserves it). Default "" keeps existing call sites
    // working unchanged.
    clipKey: String = "",
    // Zoom settle (owner request 2026-11-12 section 3): fired ONCE when a
    // zoom-CHANGING pinch gesture ends (the gesture settles) — including
    // a pinch that settled back at 1x, so the screen can drop its zoomed
    // flag and restore the normal tier policy. NOT fired for a pan-only
    // gesture or a two-finger tap that never crossed the slop gate; NOT
    // fired during the pinch (the gesture is still moving). Receives the
    // final transform. Which gestures owe the full-quality re-render is
    // the screen's pure decision (SliderTiers.owesZoomSettle). Default
    // null keeps PreviewSurface's own tests and any other call site
    // working unchanged.
    onZoomSettled: ((ZoomPan.State) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // §6 zoom/pan state: keyed on the CLIP (a fresh clip starts at 1x
    // centered — the reset rule), saveable (a pinch survives a
    // configuration change), and independent of playback. listSaver
    // because Float is not a Bundle value; the saver is a display-layer
    // detail so ZoomPan stays pure Kotlin.
    var zoomPan by rememberSaveable(clipKey, stateSaver = ZoomPanStateSaver) {
        mutableStateOf(ZoomPan.State())
    }
    val uprightRatio = PreviewSurfaceRatio.of(
        frameWidth = frame?.width ?: 0,
        frameHeight = frame?.height ?: 0,
        sourceWidth = sourceWidth,
        sourceHeight = sourceHeight,
    )
    // Quarter-turn deltas swap the box's shape; the Image inside rotates
    // by the delta and scales to cover (fills exactly in the steady
    // state — see RotationState.quarterTurnDisplayScale).
    val displayRatio = PreviewSurfaceRatio.withManualRotation(uprightRatio, manualRotationDeg)
    val coverScale = RotationState.quarterTurnDisplayScale(uprightRatio, manualRotationDeg)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // E1 sizing: the surface claims exactly the region
        // PreviewSurfaceRatio.claimedSize allows — full width when the
        // ratio fits the caller's height cap, else bounded by that cap —
        // never the over-tall aspectRatio fallback that covered the top
        // bar's ⋮ (owner finding 2026-11-11 E1).
        modifier = modifier.previewBounds(displayRatio).clipToBounds(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // §6 gestures on the content Box: this handler sits
                // INSIDE the surface, so in the Main pass it runs BEFORE
                // the editor's hold handler on the parent surface
                // modifier — whatever this handler consumes, the hold sees
                // as consumed and cancels. One handler for the whole
                // matrix (pinch always consumes; a single finger pans
                // only while zoomed and only after slop; at 1x it is
                // fully passive); the pure decisions are ZoomPan's and
                // the hold handler in ClipEditorScreen is untouched.
                .pointerInput(frame != null) {
                    if (frame == null) return@pointerInput
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // The single-finger drag pointer (the first down;
                        // a second finger switches to the pinch branch).
                        var dragId: PointerId? = down.id
                        // Zoomed-at-gesture-start gate: at 1x the whole
                        // gesture is passive (the hold owns the stream).
                        val zoomed = ZoomPan.isZoomed(zoomPan.zoom)
                        var pastSlop = false
                        // Pinch bookkeeping: the anchor centroid and
                        // spread of the previous event, so the zoom ratio
                        // is event-to-event (no runaway on a late join).
                        var anchorCentroid = Offset.Zero
                        var anchorSpread = -1f
                        var pinchZooming = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break

                            // ---- pinch branch (2+ pointers) ----
                            if (pressed.size >= 2) {
                                // A second finger ALWAYS takes over: consume
                                // every change so the hold can never engage
                                // from a pinch — before it (this is the
                                // first move after the down) or after it
                                // (leftover fingers keep consuming until
                                // every pointer lifts).
                                event.changes.forEach { it.consume() }
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val centroid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                                val spread = hypot(a.x - b.x, a.y - b.y)
                                if (anchorSpread >= 0f && anchorSpread > 0f) {
                                    val ratio = spread / anchorSpread
                                    val newZoom = if (pinchZooming) {
                                        zoomPan.zoom * ratio
                                    } else if (abs(spread - anchorSpread) > slop) {
                                        // First responsive pinch move: the
                                        // slop gate (a tiny jitter does not
                                        // zoom), then the ratio counts
                                        // from here.
                                        pinchZooming = true
                                        zoomPan.zoom * ratio
                                    } else {
                                        zoomPan.zoom
                                    }
                                    if (newZoom != zoomPan.zoom) {
                                        zoomPan = ZoomPan.pinch(
                                            zoomPan,
                                            anchorCentroid.x / size.width,
                                            anchorCentroid.y / size.height,
                                            centroid.x / size.width,
                                            centroid.y / size.height,
                                            newZoom,
                                        )
                                    }
                                }
                                anchorCentroid = centroid
                                anchorSpread = spread
                                continue
                            }

                            // ---- single-pointer branch ----
                            if (!zoomed) continue // at 1x: passive
                            val drag = dragId
                                ?.let { id -> pressed.firstOrNull { it.id == id } }
                                ?: pressed.first().also { dragId = it.id }
                            // Zoomed: pan after slop; consume from the slop
                            // crossing on — a stationary press while
                            // zoomed still reaches the hold handler (it
                            // never sees a consume), and a pan never
                            // triggers the hold.
                            if (!pastSlop) {
                                pastSlop = (drag.position - down.position).getDistance() > slop
                            }
                            if (pastSlop) {
                                drag.consume()
                                zoomPan = ZoomPan.panBy(
                                    zoomPan,
                                    drag.positionChange().x / size.width,
                                    drag.positionChange().y / size.height,
                                )
                            }
                        }

                        // Zoom settle (owner request 2026-11-12 section 3):
                        // the gesture ended. The callback fires for ANY
                        // zoom-changing pinch (pinchZooming) — including
                        // one that settled back at 1x, so the screen can
                        // return its zoomed flag to false and restore the
                        // normal tier policy. A pan-only gesture or a
                        // two-finger tap under slop fires nothing (no zoom
                        // changed). WHICH gestures owe the full-quality
                        // re-render is SliderTiers.owesZoomSettle's pure
                        // decision, applied by the screen (it owns the
                        // render pipeline); this callback is the display
                        // layer's whole obligation.
                        if (pinchZooming) {
                            onZoomSettled?.invoke(zoomPan)
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            val f = frame
            when {
                f != null -> Image(
                    bitmap = remember(f) { f.toImageBitmap() },
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // §6 transform order (the ZoomPan KDoc): the
                            // §5 rotation and cover scale about the
                            // content center, then the zoom about the
                            // center, then the pan translation in the
                            // box's screen space (applied last, so a drag
                            // moves the rotated, zoomed content with the
                            // finger).
                            rotationZ = manualRotationDeg.toFloat()
                            // The cover scale for quarter turns: the fitted
                            // upright content, once turned, would leave
                            // bars; scaling by the ratio between the
                            // content's long and short sides fills the
                            // swapped-ratio box exactly.
                            scaleX = coverScale * zoomPan.zoom
                            scaleY = coverScale * zoomPan.zoom
                            translationX = zoomPan.panX * size.width
                            translationY = zoomPan.panY * size.height
                        },
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

/**
 * Sizer for the preview surface (owner finding 2026-11-11 E1): the
 * surface measures at exactly the size
 * [PreviewSurfaceRatio.claimedSize] allows within the incoming
 * constraints — full width when the ratio's full-width height fits the
 * caller's height cap, else the cap-bounded size. This replaces
 * `Modifier.aspectRatio`, whose unenforced fallback sizes a portrait-
 * shaped surface at the full-width height even when that exceeds the
 * capped wrapper (Compose foundation-layout 1.7.3 AspectRatio.kt
 * tryMaxWidth(enforceConstraints = false) after fillMaxWidth forces
 * minWidth = maxWidth): the overflow painted up over the editor's top
 * bar and hid the ⋮ menu. Measuring with a fixed Constraints never
 * overflows; the caller's Box centers the bounded surface as before.
 */
private fun Modifier.previewBounds(ratio: Float): Modifier = layout { measurable, constraints ->
    val (width, height) = PreviewSurfaceRatio.claimedSize(
        regionWidth = constraints.maxWidth,
        regionHeight = constraints.maxHeight,
        ratio = ratio,
    )
    val placeable = measurable.measure(
        Constraints.fixed(width = width, height = height),
    )
    layout(placeable.width, placeable.height) {
        placeable.placeRelative(0, 0)
    }
}

/**
 * Saver for the §6 zoom/pan state across configuration changes (owner
 * request 2026-11-11 section 6: the transform survives rotation/dark
 * mode). listSaver because Float is not a Bundle value; a display-layer
 * detail, so it lives here instead of polluting the pure ZoomPan object.
 */
private val ZoomPanStateSaver: Saver<ZoomPan.State, *> = listSaver(
    save = { listOf(it.zoom, it.panX, it.panY) },
    restore = { ZoomPan.State(zoom = it[0], panX = it[1], panY = it[2]) },
)

/** PreviewFrame -> Compose ImageBitmap via Android Bitmap (UI layer only). */
private fun PreviewFrame.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
