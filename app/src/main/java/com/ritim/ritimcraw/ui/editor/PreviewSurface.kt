package com.ritim.ritimcraw.ui.editor

import android.graphics.Bitmap
import android.view.SurfaceHolder
import android.view.SurfaceView
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
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
 * half-known pair never shapes the surface. The frame tier additionally
 * skips the degenerate placeholder (both sides <= 1, [carriesShape]): the
 * GPU path's stats-off fallback publishes a 1x1 opaque-black frame, and
 * that placeholder must never shape the surface (owner finding
 * 2026-11-15: with stats off a 4:3 clip rendered a SQUARE 1080x1080
 * surface). A real square frame (1000x1000) is still a shape.
 */
object PreviewSurfaceRatio {

    /** Fallback when no dimension source is known: 16:9 landscape video. */
    const val DEFAULT_RATIO = 16f / 9f

    /**
     * True when a dimension pair carries real shape information: both
     * sides positive AND at least one side > 1. The GPU path's stats-off
     * fallback publishes a 1x1 opaque-black placeholder frame (the stats
     * pack is its only pixel payload — GlesPreviewSession.cpp:1092), so
     * "both sides <= 1" is the honest unknown marker, never a square
     * shape. A real square (1000x1000) carries shape. This ONE predicate
     * gates every frame-dims consumer (the ratio chain's tier 1 and the
     * editor's effectiveDims update), so the placeholder is treated the
     * same everywhere.
     */
    fun carriesShape(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && (width > 1 || height > 1)

    /**
     * The preview surface aspect ratio (width / height) for the given
     * dimension candidates, per the chain in the object KDoc. The frame
     * tier requires [carriesShape] — a 1x1 placeholder publish falls
     * through to the source pair instead of shaping the surface square
     * (owner finding 2026-11-15: stats off, source=4096x3072, every
     * render read surface=1080x1080, out=1x1 stats=off).
     */
    fun of(
        frameWidth: Int,
        frameHeight: Int,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Float = when {
        carriesShape(frameWidth, frameHeight) -> frameWidth.toFloat() / frameHeight
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

    /**
     * The video rect's top-left offset inside its centered wrapper (owner
     * note 1, 2026-11-12: "Kontrollerin konumu video yüzeyinin gerçek
     * sınırlarına göre hesaplanmalı" — the corner controls must anchor to
     * the video's REAL bounds, not the wrapper's). The wrapper centers the
     * claimed size ([claimedSize]), so the offset per axis is the half
     * difference of the sides. Floor division, so the offset never places
     * the rect outside the region (offset + size <= region on both axes);
     * the wrapper's own centering rounds the same half-difference — at
     * most a 1px disagreement under a control's 8dp inset, imperceptible.
     *
     * Degenerate inputs center at zero: an empty region has no offset to
     * give, and the controls fall back to the wrapper's corners.
     */
    fun centeredOffset(
        regionWidth: Int,
        regionHeight: Int,
        ratio: Float,
    ): Pair<Int, Int> {
        val (width, height) = claimedSize(regionWidth, regionHeight, ratio)
        if (width <= 0 || height <= 0) return 0 to 0
        return ((regionWidth - width) / 2) to ((regionHeight - height) / 2)
    }
}

/**
 * Pure display-owner decision for the preview's content layer
 * (JVM-testable without Compose, the [PreviewSurfaceRatio] precedent):
 * when a frame exists, WHO serves the pixels — the GPU window (the
 * SurfaceView drawn BEHIND the Compose window through its punched
 * hole) or the Compose Image on top. [Owner.NONE] when no frame has
 * published: the placeholder layer owns the surface then.
 *
 * The owner finding this locks (2026-11-15): a SERVING window with a
 * published frame is the GPU path's steady state — during playback
 * (stateOf keeps flipping RENDERING between frames) and paused (FRAME)
 * alike — and the display layer must show NEITHER the Image (it would
 * cover the window) NOR a placeholder (the RENDERING spinner or the
 * "No preview yet" text over footage the window is visibly presenting
 * reads broken). Before the GPU_WINDOW arm existed, the serving steady
 * state fell through PreviewSurface's when to the placeholder arms, so
 * the GPU-served preview never showed a clean frame even while its
 * trace read healthy — the state machine advanced internally; the
 * display layer discarded the verdict.
 */
object PreviewDisplayOwner {

    /**
     * Who serves the preview's pixels. NONE = no frame published, so the
     * placeholder layer (access-lost / error / spinner / preparing /
     * idle text, by way of [PreviewState]) owns the surface.
     */
    enum class Owner { GPU_WINDOW, COMPOSE_IMAGE, NONE }

    fun ownerOf(frame: PreviewFrame?, gpuWindowServing: Boolean): Owner = when {
        frame == null -> Owner.NONE
        gpuWindowServing -> Owner.GPU_WINDOW
        else -> Owner.COMPOSE_IMAGE
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
    // ---- GPU window path (M4 commit b) ----
    // Compose the AndroidView(SurfaceView) whenever the GPU preview path
    // is ACTIVE (the session opened and has not latched), NOT only when it
    // is serving: surfaceCreated must fire BEFORE the first GPU render, or
    // the session would never receive a window surface and the first frame
    // would have nowhere to present. The SurfaceView composes as a SIBLING
    // of the Image inside the same content Box, transformed by the SAME
    // graphicsLayer math (rotation + cover scale + zoom/pan), so crop
    // geometry, manual rotation, fullscreen and the pinch transforms keep
    // working identically on both display paths. Default false keeps every
    // existing call site (and PreviewSurface's own tests) working
    // unchanged — no SurfaceView is composed on the CPU path.
    gpuWindowActive: Boolean = false,
    // The GPU window path is SERVING the display (a GPU frame published AND
    // a live surface): suppress the Compose Image — the SurfaceView draws
    // BEHIND the Compose window, so a visible Image would cover it. The
    // frame still publishes (the overlays read it for stats), so this is a
    // pure display swap, never a data change. Default false keeps the
    // Image everywhere it renders today.
    gpuWindowServing: Boolean = false,
    // The SurfaceHolder lifecycle → the session's window surface:
    // surfaceCreated → the holder's Surface (the session takes ownership
    // at call entry), surfaceDestroyed → null (the destroy signal always
    // processes, even when a render latched the session unhealthy — a dead
    // surface is a lifecycle signal, never a render failure). Null keeps
    // the SurfaceView inert (never composed) at every existing call site.
    onGpuSurfaceChanged: ((surface: Any?) -> Unit)? = null,
    // DEBUG-only path badge (2026-11-23, owner report: GPU preview shows no
    // change on slider moves — the owner needs to SEE which path rendered
    // the last frame): "GPU" / "CPU", null (the default and every release
    // call site) draws nothing. The caller gates it on BuildConfig.DEBUG.
    debugPathBadge: String? = null,
    modifier: Modifier = Modifier,
) {
    val currentGpuSurfaceChanged by rememberUpdatedState(onGpuSurfaceChanged)
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
            // ---- GPU window path (M4 commit b) ----
            // The SurfaceView composes whenever the GPU path is ACTIVE
            // (not only when serving — surfaceCreated must fire before the
            // first GPU render) and sits BEHIND the Compose window: the
            // session's window pass draws the developed frame into it from
            // the GL thread, aspect-fit letterboxed, with the container
            // orientation folded in-shader. The SAME graphicsLayer math as
            // the Image below (manual rotation delta + cover scale + zoom/
            // pan) rides the AndroidView, so the window content lands
            // EXACTLY where the Image's pixels would: quarter turns, the
            // cover scale, pinch zoom and pan all keep working, and the
            // crop overlay / scope strip / corner controls (Compose, on
            // top) stay aligned with the window's rect. The SurfaceView
            // punches a hole through the Compose surface; the Compose
            // layers above (overlays, controls) still composite over it.
            if (gpuWindowActive && onGpuSurfaceChanged != null) {
                AndroidView(
                    factory = { context ->
                        SurfaceView(context).apply {
                            // Default Z-order (BELOW the window): the
                            // SurfaceView punches a hole through the
                            // Compose surface and the window pass paints
                            // its own letterbox bars (pixels outside uRect
                            // write black), so the surface covers its
                            // bounds opaquely and the Compose overlays
                            // composite ON TOP of it — the audit's rule.
                            setZOrderMediaOverlay(false)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    currentGpuSurfaceChanged?.invoke(holder.surface)
                                }

                                override fun surfaceChanged(
                                    holder: SurfaceHolder,
                                    format: Int,
                                    width: Int,
                                    height: Int,
                                ) {
                                    // A resize may replace/clear the backing
                                    // buffers. Reattach and request a frame
                                    // even when playback is paused; waiting
                                    // for a next playback tick strands black.
                                    if (width > 0 && height > 0) {
                                        currentGpuSurfaceChanged?.invoke(holder.surface)
                                    }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    currentGpuSurfaceChanged?.invoke(null)
                                }
                            })
                        }
                    },
                    // The transform rides the AndroidView exactly as on
                    // the Image: the §6 order (rotation + cover scale
                    // about the content center, then zoom, then pan in the
                    // box's screen space) — the GPU window's pixels land
                    // where the CPU Image's would, so every overlay's
                    // coordinate space is unchanged.
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = manualRotationDeg.toFloat()
                            scaleX = coverScale * zoomPan.zoom
                            scaleY = coverScale * zoomPan.zoom
                            translationX = zoomPan.panX * size.width
                            translationY = zoomPan.panY * size.height
                        },
                )
            }
            val f = frame
            when {
                // The GPU window serves the display (the display-owner
                // decision, PreviewDisplayOwner.ownerOf): with a published
                // frame and a serving window, compose NOTHING here — the
                // SurfaceView behind the Compose window is the picture, and
                // any placeholder arm below (the RENDERING spinner during
                // playback, the PREPARING spinner+text, the idle "No
                // preview yet" text) would draw its spinner/text OVER the
                // footage the window is visibly presenting. This arm MUST
                // outrank them all: stateOf keeps flipping RENDERING between
                // playback frames (renderInFlight outranks hasFrame there),
                // so without it the serving steady state fell through to the
                // spinner/text placeholders and the GPU-served preview never
                // showed a clean frame (owner finding 2026-11-15: black
                // preview + spinner, "No preview yet" text overlapping the
                // loading icon, while the render trace read healthy).
                PreviewDisplayOwner.ownerOf(f, gpuWindowServing) ==
                    PreviewDisplayOwner.Owner.GPU_WINDOW -> Unit
                // The Image serves ONLY when the GPU window is not: the
                // SurfaceView draws behind the Compose window, so a serving
                // window under a visible Image would never show. The frame
                // still publishes either way (the scope strip reads it), so
                // this is a pure display swap.
                f != null && !gpuWindowServing -> Image(
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
                // The GPU session-open window before its first frame (owner
                // finding 2026-11-14): spinner + the honest preparing line —
                // NEVER the idle "No preview yet" text, which reads broken
                // while the editor is in fact preparing (seconds-class on a
                // fresh 12MP clip). A stale frame never reaches here
                // (stateOf keeps it FRAME).
                state == PreviewState.PREPARING -> Column(
                    Modifier.padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.editor_preview_preparing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> Text(
                    stringResource(R.string.editor_preview_device_pending),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(32.dp),
                )
            }
            // The debug path badge: tiny, top-start, over the picture.
            if (debugPathBadge != null && f != null) {
                Surface(
                    color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                ) {
                    Text(
                        debugPathBadge,
                        style = MaterialTheme.typography.labelSmall,
                        color = androidx.compose.ui.graphics.Color.White,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
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
 *
 * INTERNAL (owner note 1, 2026-11-12): the editor's corner-control
 * anchor Box measures with THIS sizer too — same incoming constraints,
 * same [PreviewSurfaceRatio.claimedSize] call, so the anchor Box takes
 * exactly the video rect's size and the wrapper's centering places it at
 * exactly the video rect's position. The controls' align() then resolves
 * against the video's REAL corners, not the wrapper's.
 */
internal fun Modifier.previewBounds(ratio: Float): Modifier = layout { measurable, constraints ->
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
