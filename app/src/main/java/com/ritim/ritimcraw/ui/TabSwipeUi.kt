package com.ritim.ritimcraw.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity

/**
 * The browse container's horizontal swipe-to-flip-tab gesture (owner
 * request 2026-11-26 D3, "like Telegram's folder switch"): a
 * predominantly-horizontal drag anywhere on the browse content flips to
 * the adjacent browse tab, per [TabSwipe]'s direction mapping and
 * thresholds. AppNav applies this modifier to the browse container Box
 * around the AnimatedContent page slide (the else branch AFTER the
 * editor/timeline/report checks — those routes compose OUTSIDE it and
 * keep their own gestures untouched).
 *
 * [onRelease] is the v2 smoothness addition: on the finger's LIFT it
 * reports the measured release velocity (dp/s, signed) and whether the
 * flip rule fired, so the caller's settle spring can start with the
 * finger's real momentum and aim at the flip's direction (pure halves:
 * [TabSwipe.settleInitialVelocityDpPerS] and
 * [TabSwipe.settleTargetFraction]).
 *
 * CHILD PRIORITY — THE WHOLE DESIGN (verified against the foundation
 * 1.7.3 sources this app compiles against — BOM 2024.09.00; files
 * DragGestureDetector.kt, TapGestureDetector.kt, Scrollable.kt):
 * the Main pass dispatches BOTTOM-UP, so every child (rows, buttons,
 * chips, the vertical scroll bodies) sees each event BEFORE this
 * container node, and children CHECK THE SAME EVENT'S FINAL PASS for
 * consumption by parents. This detector never pre-consumes anything in
 * the Initial pass; it engages ONLY through
 * [awaitHorizontalTouchSlopOrCancellation], which returns null — no
 * gesture started, nothing consumed — the moment any child consumed the
 * change first in Main. Children win; the flip happens only on drags no
 * child wanted:
 * - the vertical bodies (LazyColumn, LazyVerticalStaggeredGrid,
 *   Column.verticalScroll) engage through scrollable's own slop
 *   detector and consume from then on — a vertical scroll never flips;
 *   a horizontal drag over the list engages only when the horizontal
 *   slop crosses first (a diagonal finger loses honestly to whichever
 *   slop crosses first), and once this detector engages it consumes in
 *   Main, which the children's Final-pass checks see — their tap and
 *   scroll detectors cancel, so a flip-drag over a row never also
 *   opens the clip;
 * - ThemeSlider (the one horizontal-drag control in the browse tabs —
 *   Settings' theme row) consumes every change of its own gesture from
 *   the down onward, so a drag starting on the pill never flips;
 * - a drag landing where no child consumes (gutters, headers, dead
 *   space on cards, empty screens) reaches this detector and flips;
 * - a child scroll at its own edge that consumes nothing hands the
 *   drag to this detector — Telegram-like (a fling past a scroll's end
 *   moves the folder), intended v1 behavior.
 *
 * ONCE ENGAGED, the loop mirrors foundation's own drag loop
 * (DragGestureDetector.kt `drag()`): the tracked change is consumed in
 * Main after the read (children then cancel through their Final-pass
 * checks), the loop ends when the finger lifts (the lift's own movement
 * is committed — the PrecisionScrubUi precedent), the change is
 * consumed by someone else (silent cancel — children always win, even
 * mid-gesture), or the pointer vanishes without an up (silent cancel,
 * also the path a system CANCEL takes: Compose synthesizes an up whose
 * changes start consumed).
 *
 * All decisions are pure ([TabSwipe]); this modifier only translates
 * pointer events into its inputs and the callbacks. dp conversions
 * happen once at this boundary with the scope's density
 * (PrecisionScrub's dp-at-the-seam precedent); the release velocity is
 * capped by the platform's own [androidx.compose.ui.platform.ViewConfiguration.maximumFlingVelocity]
 * exactly like foundation's Draggable does.
 *
 * THE RELEASE SIGNAL (v2 smoothness pass, owner request 2026-11-27
 * second round): [onRelease] fires BEFORE the legacy `dragDelta(0f)`
 * reset on a finger LIFT only, carrying (velocityX dp/s signed,
 * willFlip). The v1 wiring settled the follow offset with a spring
 * whose comment claimed it inherited the finger's velocity — but the
 * live deltas all went through Animatable.snapTo (which resets the
 * stored velocity), so the spring always started from a standstill and
 * the page parked at its nudge before easing. [onRelease] hands the
 * ACTUAL release velocity to the caller so the settle spring can take
 * it as initialVelocity; [TabSwipe.settleInitialVelocityDpPerS] pins
 * the pass-through contract. A canceled gesture (child stole the
 * stream, pointer vanished, system cancel) never lifts: no onRelease,
 * just the plain reset — the settle stays a no-velocity return home.
 */
@Composable
fun Modifier.tabSwipeGesture(
    currentTab: () -> Destination,
    onFlip: (Destination) -> Unit,
    onDragDeltaDp: (Float) -> Unit,
    onRelease: (velocityX: Float, willFlip: Boolean) -> Unit = { _, _ -> },
): Modifier {
    // Fresh-callback trampolines (the ThemeSlider / PrecisionScrubUi
    // pattern): the pointerInput block below restarts only on its key,
    // but its inputs and callbacks must always be the CURRENT
    // composition's — AppNav recreates its lambdas every recomposition.
    val current by rememberUpdatedState(currentTab)
    val flip by rememberUpdatedState(onFlip)
    val dragDelta by rememberUpdatedState(onDragDeltaDp)
    val release by rememberUpdatedState(onRelease)
    return this.then(
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // The tab the gesture started on (the pure rule's
                // contract): read once here, not at decision time, so a
                // mid-gesture destination change (a second-finger nav
                // bar tap) cannot rebase the flip.
                val startTab = current()
                val tracker = VelocityTracker()
                tracker.addPointerInputChange(down)

                // Slop phase — horizontal only, and only when no child
                // consumed the motion first (children see every event
                // before this node in Main). Null return is silent:
                // this side consumed nothing, the child (or an early
                // lift) owns the gesture. Accepting consumes THIS
                // change in Main — the claim the children's Final-pass
                // checks see, which stops them engaging late.
                val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                    change.consume()
                }
                if (slop == null) return@awaitEachGesture

                // Engaged — the foundation drag-loop shape. Totals are
                // POSITION-based (change.position - down.position), so
                // no drift can accumulate across events and the
                // pre-slop motion counts toward the threshold (it is
                // real finger travel; slop is ~8dp of it).
                var lifted = false
                var liftVelocityX = 0f
                var willFlip = false
                var liftDxDp = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null || change.isConsumed) {
                        // The tracked pointer vanished without an up, or
                        // a child consumed this event's change before
                        // this node (children dispatch first) — the
                        // stream is theirs, or the system cancelled the
                        // gesture (a synthesized up starts consumed).
                        // Silent end: no flip.
                        break
                    }
                    if (!change.pressed) {
                        // The lift: commit its own movement (already in
                        // the position totals below), then decide from
                        // the totals + the release velocity.
                        tracker.addPointerInputChange(change)
                        val maxVelocity = viewConfiguration.maximumFlingVelocity
                        val velocity = tracker.calculateVelocity(
                            Velocity(maxVelocity, maxVelocity),
                        )
                        liftDxDp = (change.position.x - down.position.x) / density
                        liftVelocityX = velocity.x / density
                        val target = TabSwipe.target(
                            current = startTab,
                            dxDp = liftDxDp,
                            dyDp = (change.position.y - down.position.y) / density,
                            velocityX = liftVelocityX,
                        )
                        willFlip = target != null
                        if (target != null) flip(target)
                        lifted = true
                        break
                    }
                    tracker.addPointerInputChange(change)
                    change.consume()
                    dragDelta((change.position.x - down.position.x) / density)
                }
                // Gesture over. A LIFT: onRelease alone — it carries the
                // measured velocity and the flip verdict, and the CALLER
                // now owns the whole settle (v2): a flip snaps the live
                // drag to rest and animates the DEPART layer; a no-flip
                // settles home WITH the finger's velocity. Sending the
                // legacy dragDelta(0f) reset after that would start a
                // SECOND, zero-velocity animateTo(0f) on the same
                // Animatable — the mutator mutex cancels the velocity
                // carry mid-flight (the exact standstill the v2 rework
                // removes), so the reset is reserved for CANCELED
                // gestures only.
                if (lifted) {
                    release(liftVelocityX, willFlip)
                } else {
                    // A CANCELED gesture (child stole the stream, the
                    // pointer vanished, or a system cancel): the plain
                    // reset — settle home from a standstill.
                    dragDelta(0f)
                }
            }
        }
    )
}
