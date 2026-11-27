package com.ritim.ritimcraw.ui

import kotlin.math.abs
import kotlin.math.sign

/**
 * Pure swipe-navigation decisions for the four browse tabs (owner request
 * 2026-11-26 D3: "users can swipe LEFT or RIGHT anywhere to switch between
 * the four browse tabs — like Telegram's folder switch"), JVM-testable
 * without Compose: whether a completed horizontal drag qualifies, and
 * which tab it flips to.
 *
 * DIRECTION MAPPING (Telegram's folder convention, stated once here and
 * pinned by tests): the tabs behave like horizontally arranged PAGES in
 * display order LIBRARY -> PROJECTS -> EXPORTS -> SETTINGS
 * ([Destination.entries] order — the bottom NavigationBar's order). A
 * finger moving RIGHT (dx > 0) pulls the previous page in from the LEFT:
 * it flips to the tab one step LEFT in display order. A finger moving
 * LEFT (dx < 0) flips to the tab one step RIGHT. The swipe moves the
 * CONTENT, not the selection cursor — the inverse of a bottom-bar tap.
 *
 * THRESHOLD RULES (the Telegram-feel contract, the app's own
 * velocity-aware settle precedent — PanelSheet):
 * - a slow deliberate drag past [DRAG_THRESHOLD_DP] flips on release;
 * - a fast FLING below the positional threshold still flips when the
 *   release velocity's horizontal component exceeds
 *   [VELOCITY_THRESHOLD_DP]; when velocity decides, its SIGN decides the
 *   direction (a drag that slowed before release does not flip against
 *   its own velocity);
 * - both must be horizontal-ish: [HORIZONTAL_BIAS] requires |dx| to
 *   dominate |dy| over the gesture's accumulated motion, so a vertical
 *   list scroll never flips even with ordinary sideways drift;
 * - at the display-order ends the flip clamps to null: a right-swipe on
 *   LIBRARY or a left-swipe on SETTINGS is a no-op (no wrap-around —
 *   Telegram does not wrap either).
 *
 * The gesture wiring (pointer input, child-consumption arbitration)
 * lives in AppNav's browse container ([tabSwipeGesture]); this object
 * only decides. Thresholds are dp so the pure values are readable in
 * JVM tests; the wiring converts with density exactly once at the
 * gesture boundary (PrecisionScrub's dp-at-the-seam precedent).
 */
internal object TabSwipe {

    /**
     * Minimum |dx| (dp) a deliberate slow drag must cover to flip on
     * release. 40dp is well under a tab-span but clearly more than a
     * sloppy tap jitter — Telegram's folder swipe needs the same
     * deliberate pull.
     */
    const val DRAG_THRESHOLD_DP = 40f

    /**
     * Release velocity (dp/s, horizontal component) above which a fling
     * flips even below the drag threshold. 300dp/s is PanelSheet's
     * velocity-aware settle convention — a slow drift stays a no-op, a
     * quick snap flips.
     */
    const val VELOCITY_THRESHOLD_DP = 300f

    /**
     * Required |dx| / |dy| dominance for a qualifying gesture: |dx| must
     * be at least [HORIZONTAL_BIAS] times |dy|. 1.25 means a diagonal
     * drag must be 25% more horizontal than vertical — the vertical
     * lists/grids (Library rows/grid, Exports cards, Settings column)
     * stay untouchable, while a genuinely horizontal swipe still
     * qualifies with ordinary imprecision.
     */
    const val HORIZONTAL_BIAS = 1.25f

    /**
     * The tab a completed horizontal drag flips to, or null when the
     * drag does not qualify (too small AND too slow, not horizontal
     * enough, or aimed past the end of the display order).
     *
     * [current] is the tab the gesture started on; [dxDp] and [dyDp]
     * are the accumulated finger deltas since the gesture start
     * (signed); [velocityX] is the release's horizontal velocity in
     * dp/s (signed). The three thresholds default to the constants and
     * stay parameters so tests can probe the exact boundaries.
     */
    fun target(
        current: Destination,
        dxDp: Float,
        dyDp: Float,
        velocityX: Float,
        dragThresholdDp: Float = DRAG_THRESHOLD_DP,
        velocityThresholdDp: Float = VELOCITY_THRESHOLD_DP,
        horizontalBias: Float = HORIZONTAL_BIAS,
    ): Destination? {
        // Horizontal-ish: |dx| must dominate |dy| by the bias factor,
        // measured on the accumulated motion — a vertical scroll with
        // sideways drift accumulates both and fails honestly.
        if (abs(dxDp) < horizontalBias * abs(dyDp)) return null
        val fast = abs(velocityX) >= velocityThresholdDp
        val dragged = abs(dxDp) >= dragThresholdDp
        if (!fast && !dragged) return null
        // Direction: velocity decides when a fling qualifies (the pager
        // convention — a drag past the threshold that was flung back
        // settles by velocity, never against it); otherwise the
        // position does. Zero (no motion at all) is rejected above, but
        // the guard keeps a degenerate 0/0 call honest.
        val directionSign = if (fast) sign(velocityX) else sign(dxDp)
        if (directionSign == 0f) return null
        val index = Destination.entries.indexOf(current)
        if (index < 0) return null
        // Finger right (sign > 0) = tab one step LEFT in display order;
        // finger left = tab one step RIGHT.
        val targetIndex = if (directionSign > 0f) index - 1 else index + 1
        // Clamp at the display-order ends: null, never wrap-around.
        if (targetIndex < 0 || targetIndex >= Destination.entries.size) return null
        return Destination.entries[targetIndex]
    }

    /**
     * The content offset (fraction of the container width, signed) a
     * still-active drag shifts the current page by — the Telegram
     * "page drags with the finger" feel at the cheapest price: the
     * CURRENT tab's content follows the finger (capped, no adjacent
     * page is composed — the AnimatedContent slide, not a pager,
     * carries the flip itself), and the release settles back to zero
     * with the wiring's spring as the flip's slide plays.
     *
     * [dxDp] is the live accumulated horizontal delta; [widthDp] is the
     * container's width. The cap ([FOLLOW_MAX_FRACTION]) keeps a long
     * drag from pulling the page off-screen — v1 is an affordance
     * signal, not a full pager.
     */
    fun followOffsetFraction(dxDp: Float, widthDp: Float): Float {
        if (widthDp <= 0f) return 0f
        return (dxDp / widthDp).coerceIn(-FOLLOW_MAX_FRACTION, FOLLOW_MAX_FRACTION)
    }

    /**
     * The drag-follow cap: never shift the page more than 15% of its width.
     */
    const val FOLLOW_MAX_FRACTION = 0.15f

    /**
     * The drag-follow's RELEASE settle: the fraction the page still has
     * to travel when the finger lifts. 0f — the page returns to its
     * resting offset. A pure constant (not a lambda) so the wiring's
     * spring target is JVM-readable; the actual spring spec (below)
     * lives in the wiring because AnimationSpec is not JVM-pure API in
     * this project's tests.
     */
    const val FOLLOW_RELEASE_TARGET_FRACTION = 0f

    /**
     * Whether a reported drag delta is the gesture's RELEASE reset (the
     * wiring's own gesture-end signal, `dragDelta(0f)`) rather than a
     * live finger position. The release must ANIMATE back (the spring),
     * while every live delta SNAPS (the finger owns the frame); treating
     * the release like a live snap is exactly the hard-stop the owner
     * reported ("animasyon duruyor, sonra geçiliyor"). Zero is also the
     * pre-slop resting value, where snapping is a no-op — the same
     * answer either way, so one rule covers both.
     */
    fun isReleaseReset(dxDp: Float): Boolean = dxDp == 0f

    /**
     * (v2, owner request 2026-11-27 second round: "biraz daha smooth
     * olabilir") The release velocity (dp/s, signed) the wiring must hand
     * the settle spring as its INITIAL VELOCITY.
     *
     * ROOT CAUSE this pins: the v1 wiring's spec comment claimed "the
     * spring inherits the Animatable's velocity — a release during a fast
     * drag settles proportionally faster". That never happened: every
     * LIVE finger delta went through [Animatable.snapTo], which calls
     * `endAnimation()` → `velocityVector.reset()` (verified against
     * Compose animation-core 1.7.6). The Animatable's velocity was ALWAYS
     * zero at the release, so the settle spring always started from a
     * standstill and the page parked before easing — the remaining
     * "duruyor" feel the owner reported. The fix is wiring-side (the
     * gesture's release signal must carry the velocity); this pure rule
     * exists so the contract is pinned by a JVM test: the velocity passes
     * through RAW, sign included, never filtered by the drag position.
     * The pager convention again: a fling-back release settles in the
     * VELOCITY's direction, exactly like the flip decision itself.
     *
     * [releaseVelocityX] is the release's horizontal velocity in dp/s
     * (signed, exactly what the gesture's VelocityTracker measured);
     * [dxDp] is the gesture's total horizontal travel (present only so
     * the signature states the rule it must NOT use).
     */
    fun settleInitialVelocityDpPerS(releaseVelocityX: Float, dxDp: Float): Float =
        releaseVelocityX

    /**
     * (v2) The direction the EXITING page travels in a flip's slide
     * (shared axis X, DESIGN.md Motion): the new page enters from the
     * side it lives on in DISPLAY ORDER, so the old page exits toward the
     * OPPOSITE side — a flip to a LATER tab (rightward in display order,
     * a leftward drag) slides the old page out to the LEFT (-1); a flip
     * to an EARLIER tab slides it out to the RIGHT (+1); no flip (null
     * target) is 0 — no exit, no travel.
     *
     * This is the direction the flip-release settle must depart in: the
     * settle and the slide must push the page the SAME way. In the
     * drag-driven case it equals the NUDGE's direction (dragged left →
     * forward flip → exits left); in the FLING-BACK case it equals the
     * FLING's direction — the same velocity-decides convention
     * [target] itself uses — so the page follows the hand's reversal
     * instead of fighting the slide that already reversed with it.
     *
     * [current] is the tab the gesture started on; [target] is
     * [target]'s verdict for the same gesture (null = no flip).
     */
    fun exitTravelSign(current: Destination, target: Destination?): Float {
        if (target == null) return 0f
        val from = Destination.entries.indexOf(current)
        val to = Destination.entries.indexOf(target)
        if (from < 0 || to < 0) return 0f
        // Later tab = forward flip = old page exits LEFT.
        return if (to > from) -1f else 1f
    }

    /**
     * (v2) The offset (fraction of the container width, signed) a
     * release settle targets — the direction the nudged page departs in
     * while the AnimatedContent slide carries the flip.
     *
     * ROOT CAUSE this pins: v1's settle always targeted 0f. On a flip,
     * the page had been dragged, say, LEFT (-15%), and the slide was
     * sending it further LEFT — but the spring yanked it back toward 0
     * (+), a visible reversal into the slide (the "duruyor, sonra
     * geçiliyor" jerk). The v2 rule: the settle departs in the SLIDE's
     * exit direction ([exitTravelSign] — toward the side the flip is
     * already sending the page), so the nudge hand-off and the slide
     * push the page the SAME way; a no-flip release (sign 0) settles to
     * 0 — the page was not going anywhere, it returns home.
     *
     * The fraction itself is [FOLLOW_DEPART_FRACTION] (beyond the 15%
     * follow cap, under the slide's full travel): big enough that the
     * page visibly LEAVES during the slide's fade-out, small enough it
     * never drags across the nav bar.
     */
    fun settleTargetFraction(exitTravelSign: Float): Float =
        exitTravelSign * FOLLOW_DEPART_FRACTION

    /**
     * (v2) The flip-release settle's depart target: 60% of the container
     * width. Beyond the 15% follow cap (the page clearly departs, not
     * twitches), under the slide's full 1/3-width travel (no nav-bar
     * crossing). A constant, not a lambda, so tests can pin the
     * relationship to [FOLLOW_MAX_FRACTION].
     */
    const val FOLLOW_DEPART_FRACTION = 0.6f
}
