package com.ritim.ritimcraw.ui.editor

/**
 * Pure OPEN/UNFOLD decisions for the adjustment panel (owner device
 * feedback 2026-11-13: the panel opens at its LARGEST size, and a swipe
 * up from the collapsed strip unfolds all the way to it). The precedent
 * is [PanelSheet.panelAfterCategoryTap]: the decision is extracted from
 * the wiring so it is JVM-testable headless, and the composable half
 * only observes the drag state and feeds these rules.
 *
 * WHY A REDIRECT RULE IS NEEDED AT ALL.
 * [androidx.compose.foundation.gestures.AnchoredDraggableState]'s
 * release settle (settle -> computeTarget) can only ever choose between
 * the CURRENT anchor and the single ADJACENT one in the fling direction,
 * so an up-release from COLLAPSED naturally targets NORMAL — never
 * EXPANDED, however fast the fling. The owner wants the full unfold, so
 * the wiring re-targets the state's OWN animation when a GESTURE settle
 * starts moving the panel up from COLLAPSED: the public animateTo
 * extension cancels the settle at the same mutation priority and
 * restarts from the live offset with the settle's velocity, running the
 * state's own snapAnimationSpec — continuous physics, never a
 * hand-rolled spring.
 *
 * UNTOUCHED BY THESE RULES: the three states and their anchors exactly
 * as they are (owner 2026-11-13: the states are good), the category
 * tap's documented open to NORMAL (spec 2026-11-11d section 10.3 — the
 * wiring flags its programmatic open so these rules never see it), and
 * every downward settle.
 */
object PanelBehavior {

    /**
     * The panel's height on a fresh clip open (owner feedback
     * 2026-11-13: "default en yüksek boyda açılsın"): the LARGEST
     * state. The editor opens ready to grade; the user collapses when
     * they want the preview back.
     */
    val DEFAULT_OPEN: PanelState = PanelState.EXPANDED

    /**
     * The first-open state for a clip of [width] x [height] with [rotationDeg] applied: a portrait result
     * (height > width after the quarter turns) opens NORMAL so the tall picture keeps room; everything else
     * (landscape, square, 4:3 open gate, unknown size) opens EXPANDED as before. Only the first open uses this;
     * a saved panel state wins.
     */
    fun defaultOpenFor(width: Int, height: Int, rotationDeg: Int): PanelState {
        if (width <= 0 || height <= 0) return DEFAULT_OPEN
        val turned = ((rotationDeg % 360) + 360) % 360 / 90 % 2 == 1
        val w = if (turned) height else width
        val h = if (turned) width else height
        return if (h > w) PanelState.NORMAL else PanelState.EXPANDED
    }

    /**
     * The state an upward swipe from [current] unfolds to: EXPANDED
     * from every state — from COLLAPSED that is the owner's full
     * unfold, and from NORMAL/EXPANDED an up-release already heads
     * there (the adjacent/top anchor), so the wiring needs no redirect
     * in those cases.
     */
    fun swipeUpTarget(current: PanelState): PanelState = PanelState.EXPANDED

    /**
     * Whether a release that settled at [settled] and is animating
     * toward [settlingTo] must be re-targeted to
     * [swipeUpTarget]. True for exactly the one gap between the
     * component's natural behavior and the owner's ask: a settle moving
     * UP from COLLAPSED, whose only possible natural target is the
     * adjacent NORMAL anchor. Everything else is honest:
     *  - a nudge that failed settle's own positional threshold heads
     *    back to COLLAPSED — an abandoned gesture, never re-targeted;
     *  - an up-release from NORMAL already targets EXPANDED — no
     *    redirect, no double animation;
     *  - every downward settle (EXPANDED -> NORMAL, NORMAL ->
     *    COLLAPSED) keeps the component's own behavior;
     *  - the redirect's own animation (COLLAPSED heading to EXPANDED)
     *    reads as false, so the rule fires once and never self-loops.
     */
    fun swipeUpRetarget(settled: PanelState, settlingTo: PanelState): Boolean =
        settled == PanelState.COLLAPSED && settlingTo == PanelState.NORMAL
}
