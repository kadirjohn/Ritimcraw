package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.ui.theme.Tnum
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The transport's precision frame-scrub gesture + readout (owner request
 * 2026-11-11 section 2): a press-and-hold on the seek bar enters
 * PRECISION FRAME MODE — the bar's drag mapping switches from time
 * (normal: ~1 cm of drag seeks ~3 s) to whole frames (~1 cm ≈ 30
 * frames), so a frame can be parked on exactly; releasing keeps the
 * selected frame.
 *
 * GESTURE STEAL, VERIFIED FROM SOURCE (foundation 1.7.3 TapGestureDetector
 * + DragGestureDetector, material3 1.3.0 Slider): this modifier is passed
 * as the M3 Slider's own `modifier`, which SliderImpl applies BEFORE its
 * internal press (detectTapGestures) and drag (draggable) chains — so it
 * sits OUTER to them: first look at every event in the INITIAL pass,
 * last in the Main pass. The long-press window is therefore watched in
 * the Initial pass — the slider's detectTapGestures consumes the DOWN in
 * its own Main pass (`down.consume()`), so a Main-pass watcher at this
 * level would see the very first event already consumed and never
 * engage; Initial sees it before anyone. Once precision engages, every
 * event of the gesture is consumed in the Initial pass: the slider's tap
 * detector then cancels (its waitForUpOrCancellation bails on consumed
 * changes — no tap-jump to the touch point on release) and its draggable
 * never starts (the slop detector ignores consumed changes — no seek-drag
 * fighting the frame stepping, and no DragInteraction, so the screen's
 * drag-tier machine is untouched).
 *
 * The window rules mirror the before/after hold (the screen's
 * press-hold precedent): the press must SURVIVE the platform long-press
 * window to engage — a quick release, a drag past the touch slop, or an
 * outer handler consuming the stream cancels it, which is also what
 * keeps quick seek-taps and seek-drags on the bar working exactly as
 * before. After engagement the finger moves freely (movement is the
 * feature); the mode ends when the finger lifts or an outer handler
 * takes the stream. If [onEngage] declines (the pure decision rejected
 * the press — no timeline, single frame, no render path), the gesture
 * exits WITHOUT consuming anything and the slider behaves exactly as
 * before.
 *
 * All decisions are pure ([PrecisionScrub]); this file only translates
 * pointer events into those decisions' inputs and the callbacks the
 * transport owns. No frame rate is needed anywhere — the transport is
 * frame-indexed, a step is exactly one index.
 */
@Composable
fun Modifier.precisionScrubGesture(
    isRtl: Boolean,
    onEngage: () -> Boolean,
    onDragDp: (Float) -> Unit,
    onRelease: () -> Unit,
): Modifier {
    // Fresh-callback trampolines: the pointerInput block below restarts
    // only when its key changes, but its callbacks must always be the
    // CURRENT composition's (the screen recreates its lambdas every
    // recomposition) — rememberUpdatedState is the standard M3 pattern
    // (Slider does the same for onValueChange).
    val engage by rememberUpdatedState(onEngage)
    val drag by rememberUpdatedState(onDragDp)
    val release by rememberUpdatedState(onRelease)
    return this.then(
        Modifier.pointerInput(isRtl) {
            // PointerInputScope IS a Density and carries the
            // ViewConfiguration — the slop and the long-press timeout come
            // from the platform, and px→dp conversion from the scope's
            // density, with no composable reads inside the block.
            val longPressTimeout = viewConfiguration.longPressTimeoutMillis
            val slop = viewConfiguration.touchSlop
            awaitEachGesture {
                // Watch the long-press window in the INITIAL pass: this
                // modifier is outer, so it observes each event before the
                // slider's own handlers see it in Main.
                val down = awaitFirstDown(
                    requireUnconsumed = true,
                    pass = PointerEventPass.Initial,
                )
                // The window: true when the press ended early (lift, drag
                // past the touch slop, or an outer handler consumed the
                // stream), null when the window elapsed with the finger
                // still down.
                val endedEarly = withTimeoutOrNull(longPressTimeout) {
                    var lifted = false
                    while (!lifted) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed ||
                            event.changes.any { it.isConsumed } ||
                            (change.position - down.position).getDistance() > slop
                        ) {
                            lifted = true
                        }
                    }
                    true
                }
                // Non-null = the press ended early: a tap or a normal
                // seek-drag, not a hold. Release the stream untouched —
                // the slider handles it exactly as before this feature.
                if (endedEarly != null) return@awaitEachGesture
                // Long press confirmed: ask the screen's pure decision.
                // Declined = leave the stream untouched (the slider's
                // normal tap/drag handling proceeds).
                if (!engage()) return@awaitEachGesture
                // Precision mode: the drag anchor resets HERE (movement
                // inside the long-press window was within the touch slop
                // and is not part of the scrub), and the accumulated dp
                // since the anchor drives the whole-frame stepping — the
                // caller receives the TOTAL, so repeated small drags land
                // identically to one big drag (no drift).
                var draggedDp = 0f
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        // The tracked pointer vanished without an up (a
                        // system cancel): keep whatever frame was parked.
                        release()
                        break
                    }
                    // An outer handler consumed this event BEFORE us in
                    // the Initial pass: the stream is someone else's now —
                    // end the mode with the frame parked so far. Checked
                    // BEFORE the own consumption below, which is per-event
                    // and would otherwise trip it on this very event.
                    if (event.changes.any { it.isConsumed }) {
                        release()
                        break
                    }
                    // Accumulate along the timeline's axis: in RTL the bar
                    // is mirrored (frame 0 on the right), so a rightward
                    // physical drag steps backward.
                    val moved = if (isRtl) {
                        -change.positionChange().x
                    } else {
                        change.positionChange().x
                    }
                    draggedDp += moved / density
                    // Consume EVERY change of the event (not just the
                    // tracked finger) in the Initial pass, BEFORE the
                    // slider's Main-pass handlers run: its tap gesture
                    // cancels, its drag never starts, and a second finger
                    // cannot start a new tap on the bar mid-scrub.
                    event.changes.forEach { it.consume() }
                    if (!change.pressed) {
                        // Finger lifted: commit the lift's own movement,
                        // then end the mode (the screen keeps the frame
                        // and settles it).
                        drag(draggedDp)
                        release()
                        break
                    }
                    drag(draggedDp)
                }
            }
        }
    )
}

/**
 * The precision-mode readout shown above the seek bar while the gesture
 * is active (the owner's words: "the player bar becomes more detailed"):
 * the exact frame number and its timecode, centered over the bar in
 * tabular figures — the same label treatment as the transport's timecode
 * (labelMedium + Tnum, onSurfaceVariant), so the two read as one strip.
 * Pure display: the screen builds the localized line, this only styles
 * it. Not a touch target (DESIGN.md's 48dp rule applies to controls).
 */
@Composable
fun PrecisionScrubReadout(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.merge(Tnum),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
    )
}
