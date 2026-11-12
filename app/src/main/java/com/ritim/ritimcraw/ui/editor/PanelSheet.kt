package com.ritim.ritimcraw.ui.editor

import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The three vertical states of the editor's adjustment panel (owner request
 * 2026-11-11d section 11.3):
 *
 *  - [COLLAPSED]  — only the drag-handle affordance strip remains above the
 *    bottom navigation; the preview gains the freed space (section 11.7);
 *  - [NORMAL]     — the default on open: a balanced share of the free region,
 *    scrollable category content above the navigation;
 *  - [EXPANDED]   — taller, still scrollable, the preview keeps whatever
 *    share is left (it stays fully visible for width-bound landscape clips).
 */
enum class PanelState { COLLAPSED, NORMAL, EXPANDED }

/**
 * The panel's COLLAPSED anchor height (owner add-on 2026-11-12: trim the
 * collapsed strip). Before the trim this was [PanelHandleHeight] (48dp —
 * the strip was exactly the touch target); after it the visible collapsed
 * strip is 36dp, a 25% reduction. dp so the screen converts it with
 * density for [PanelSheet.anchorHeights]; the handle's touch target is
 * NOT shrunk — see [PanelHandleHeight].
 */
val PanelCollapsedHeight = 36.dp

/**
 * The handle strip's TOUCH TARGET height (owner request 2026-11-11d
 * section 11.3 state A + the 2026-11-12 trim): the 48dp minimum touch
 * target is preserved while the collapsed ANCHOR shrank to
 * [PanelCollapsedHeight] — the handle measures itself with
 * requiredHeight, so in the collapsed strip it overhangs
 * (PanelHandleHeight - PanelCollapsedHeight) = 12dp DOWNWARD into the
 * navigation bar's top chrome. That band is empty space (the M3
 * NavigationBar is 80dp tall with its items' content centered), and a
 * drag starting there is a natural panel-open; a TAP there still reaches
 * the navigation item below (both open the panel —
 * [PanelSheet.panelAfterCategoryTap] — so nothing is stolen). The
 * overhang is hit-testable because the sheet draws its shape with
 * Modifier.background instead of a clipping Surface.
 */
val PanelHandleHeight = 48.dp

/**
 * Pure decisions for the draggable, collapsible adjustment panel and the
 * fixed Light/Color/Detail bottom navigation (owner request 2026-11-11d
 * sections 10-11). Every rule here is JVM-testable without Compose; the
 * composable half (commit that wires this in) only measures, converts dp to
 * px and feeds an [androidx.compose.foundation.gestures.AnchoredDraggableState].
 *
 * ANCHOR MODEL. The panel's height in px IS the draggable offset: the panel
 * is bottom-anchored (it sits directly above the fixed bottom navigation,
 * section 10.1 — the navigation is NOT inside the draggable content), so a
 * larger offset means a taller panel and the preview Box above simply
 * receives whatever height is left. Anchors are therefore heights:
 *
 *  - collapsed: the fixed handle-strip height (the affordance that remains,
 *    section 11.3 state A — "do not leave a large empty panel behind");
 *  - normal: [NORMAL_FRACTION] of the measured free region (preview +
 *    panel together), so the exact height adapts to device size AND
 *    orientation (section 11.3 state B: "do not rely on one fixed
 *    percentage for every Android device" — the reference is the measured
 *    region, never a screen-size constant);
 *  - expanded: [EXPANDED_FRACTION] of the same region, which by
 *    construction leaves the preview its remaining share (section 11.3
 *    state C: the panel must not overlap the toolbar — it cannot, the
 *    Column stacks the regions — and the preview "should remain visible
 *    whenever practical").
 *
 * PREVIEW CLAIM (owner spec 2026-11-12 section 2 — the root-cause fix).
 * The fractions alone caused the regression the owner photographed: at
 * NORMAL/EXPANDED the preview Box's leftover height dropped below the
 * video's full-width aspect height, PreviewSurfaceRatio.claimedSize then
 * bounded the video by HEIGHT and its WIDTH collapsed — "horizontally
 * centered with large unused areas on both sides". The fix is a CAP, not
 * a restructure: the screen computes the preview's width-bound height
 * claim ([previewClaimPx]) and the NORMAL/EXPANDED anchors are capped at
 * the region minus that claim (never below the collapsed strip or
 * [MIN_PANEL_HEIGHT], never above the region), so a width-bound clip
 * keeps the full width at every panel state. On common devices the cap
 * never engages at NORMAL (0.44 share + 16:9 claim both fit), so the
 * familiar geometry is byte-identical; it binds exactly where the owner
 * saw the shrink — tall claims, scope strip open, large font scale.
 *
 * The three heights are always ordered collapsed <= normal <= expanded and
 * never exceed the region, for every input including degenerate tiny ones.
 *
 * SNAP MODEL (section 11.4). Release settles to the nearest anchor with
 * velocity awareness: past [positionalThreshold] of the inter-anchor
 * distance, or on a fling above [VELOCITY_THRESHOLD_DP]; the settle
 * animation is a plain tween of [SNAP_DURATION_MS] — inside the spec's
 * 180-250ms band with no overshoot or spring.
 *
 * GESTURE ARBITRATION CONTRACT (section 11.5). The rules are structural —
 * the wiring satisfies them by placement, not by arithmetic, and this
 * object is the record of the contract:
 *  - ONLY the handle strip carries the panel drag modifier; sliders, the
 *    scrollable settings list, the timeline and the navigation sit on
 *    other nodes, so a slider drag can never resize the panel and the
 *    panel never collapses from one;
 *  - horizontal slider drags and vertical list scrolls live in their own
 *    gesture handlers (scrollable Column, horizontal M3 Sliders) — no
 *    nested-scroll coupling with the panel drag exists at all;
 *  - category taps cannot start a drag: the navigation bar is outside the
 *    draggable node entirely (section 10.1).
 *
 * STATE PRESERVATION (sections 10.3, 11.6). Panel visibility is pure UI
 * state: the wiring keeps the category content COMPOSED while collapsed
 * (clipped, not removed), so the active category, every adjustment value,
 * the per-category scroll position, playback position and state, and the
 * LUT/tonemapping selection all survive collapse/expand by construction —
 * and nothing here touches the render pipeline, so no media processing is
 * retriggered by a drag (sections 11.4, 18).
 */
object PanelSheet {

    /**
     * Normal state: the balanced share of the free region the panel claims
     * (section 11.3 state B). 0.44 leaves the preview the majority share —
     * a width-bound 16:9 clip stays fully visible at full width.
     */
    const val NORMAL_FRACTION = 0.44f

    /**
     * Expanded state: the taller share (section 11.3 state C). 0.72 keeps
     * 28% of the region for the preview — "visible whenever practical"
     * holds for landscape clips (still width-bound, so complete) and gives
     * portrait clips a small but real strip. The 2026-11-12 preview claim
     * caps this share where it would otherwise push a width-bound clip's
     * leftover below its full-width height (the owner's shrink).
     */
    const val EXPANDED_FRACTION = 0.72f

    /**
     * The minimum panel height the anchors guarantee even when the
     * preview's claim eats the whole region (owner spec 2026-11-12 section
     * 3: the panel states stay usable on every device). Three 48dp slider
     * rows + the 48dp handle target (CompactSliderDefaults.ROW_HEIGHT) —
     * dp so the screen converts it with density; the rule below coerces it
     * into the region, so a degenerate tiny region never breaks ordering.
     */
    val MIN_PANEL_HEIGHT = 192.dp

    /**
     * Settle/snap animation duration in ms (section 11.4: "approximately
     * 180-250ms", "avoid overshoot"). A plain tween — no spring, so the
     * panel can never overshoot an anchor.
     */
    const val SNAP_DURATION_MS = 200

    /**
     * Fraction of the distance between two anchors that a drag must pass
     * before release settles to the farther one (section 11.4 "snap
     * naturally to the nearest appropriate panel state"). Halfway is the
     * Material sheet convention.
     */
    const val POSITIONAL_THRESHOLD_FRACTION = 0.5f

    /**
     * Release velocity (dp/s) above which the fling direction, not the
     * position, decides the target anchor (section 11.4 "velocity-aware").
     * 125 dp/s is the Material bottom sheet convention. dp so the pure
     * value is readable in JVM tests; the wiring converts with density.
     */
    val VELOCITY_THRESHOLD = 125.dp

    /** The positional snap threshold in px for an inter-anchor distance. */
    fun positionalThreshold(totalDistancePx: Float): Float =
        totalDistancePx * POSITIONAL_THRESHOLD_FRACTION

    /**
     * The preview's height claim in px (owner spec 2026-11-12 section 2,
     * the root-cause fix): the height a width-bound clip needs inside the
     * free region so the video keeps the FULL WIDTH at every panel state.
     *
     * Rule: the full-width aspect height when it FITS the region (landscape
     * footage — the common .mcraw case), else 0. A clip taller than the
     * region can never be width-bound (PreviewSurfaceRatio.claimedSize
     * bounds it by the region's height instead), so its claim is nothing
     * and the panel anchors keep their legacy flex behavior — the spec's
     * allowed resize reasons (panel state change) still apply, but there
     * is no width to preserve.
     *
     * Degenerate inputs (non-positive region or ratio) claim nothing.
     * Pure so the cap is JVM-testable.
     */
    fun previewClaimPx(
        regionWidthPx: Int,
        regionHeightPx: Int,
        ratio: Float,
    ): Int {
        if (regionWidthPx <= 0 || regionHeightPx <= 0 || ratio <= 0f) return 0
        val fullWidthHeight = (regionWidthPx / ratio).toInt()
        return if (fullWidthHeight <= regionHeightPx) fullWidthHeight else 0
    }

    /**
     * The cap for the NORMAL/EXPANDED anchors in px: the free region minus
     * the preview's width-bound claim ([previewClaimPx]), floored at
     * [minPanelHeightPx] (coerced into the region) so the panel states stay
     * usable even when the claim eats nearly the whole region — a
     * three-slider-row panel ([MIN_PANEL_HEIGHT]) is worth more than the
     * last few px of an already huge preview. Ordering wins over the cap in
     * the fully degenerate case: if even the floor exceeds the region the
     * anchors fall back to the collapsed strip, never out of order.
     */
    fun panelCapPx(
        regionHeightPx: Int,
        previewClaimPx: Int,
        minPanelHeightPx: Int,
    ): Int {
        if (regionHeightPx <= 0) return 0
        val floor = minPanelHeightPx.coerceIn(0, regionHeightPx)
        return (regionHeightPx - previewClaimPx).coerceIn(floor, regionHeightPx)
    }

    /**
     * The expanded anchor height in px: [EXPANDED_FRACTION] of the free
     * region, CAPPED at the region minus the preview's width-bound claim
     * ([previewClaimPx] — owner spec 2026-11-12 section 2), never below
     * the collapsed strip and never above the region.
     */
    fun expandedHeightPx(
        availableHeightPx: Int,
        collapsedHeightPx: Int,
        previewClaimPx: Int = 0,
        minPanelHeightPx: Int = 0,
    ): Int {
        val cap = panelCapPx(availableHeightPx, previewClaimPx, minPanelHeightPx)
        val expanded = (availableHeightPx * EXPANDED_FRACTION)
            .roundToInt()
            .coerceAtMost(cap)
        return expanded.coerceIn(
            collapsedHeightPx.coerceAtMost(availableHeightPx),
            availableHeightPx,
        )
    }

    /**
     * The normal anchor height in px: [NORMAL_FRACTION] of the free
     * region, capped by the same preview claim, never below the collapsed
     * strip and never above the expanded anchor (so the three anchors stay
     * ordered on every device).
     */
    fun normalHeightPx(
        availableHeightPx: Int,
        collapsedHeightPx: Int,
        previewClaimPx: Int = 0,
        minPanelHeightPx: Int = 0,
    ): Int {
        val expanded = expandedHeightPx(
            availableHeightPx, collapsedHeightPx, previewClaimPx, minPanelHeightPx,
        )
        val cap = panelCapPx(availableHeightPx, previewClaimPx, minPanelHeightPx)
        val normal = (availableHeightPx * NORMAL_FRACTION)
            .roundToInt()
            .coerceAtMost(cap)
        return normal.coerceIn(
            collapsedHeightPx.coerceAtMost(availableHeightPx),
            expanded,
        )
    }

    /**
     * The three anchor heights in px (the panel-height offsets the
     * AnchoredDraggableState settles between), always ordered
     * COLLAPSED <= NORMAL <= EXPANDED and never exceeding the region. The
     * collapsed anchor is the handle-strip height, coerced into the region
     * so a degenerate tiny region still yields ordered anchors.
     *
     * The NORMAL/EXPANDED anchors are the same fractions of the region,
     * CAPPED at the region minus the preview's width-bound claim
     * ([previewClaimPx], owner spec 2026-11-12 section 2) — so a
     * width-bound clip keeps the full width at every panel state. A zero
     * claim (tall clips, or the default) keeps the legacy region-fraction
     * anchors byte-identical.
     *
     * @param availableHeightPx the measured free region height (preview +
     *   panel together, below the top bar and transport, above the bottom
     *   navigation) — the device-adaptive reference of section 11.3.
     * @param collapsedHeightPx the handle-strip height in px.
     * @param previewClaimPx the preview's width-bound height claim in px
     *   (0 = uncapped legacy behavior).
     * @param minPanelHeightPx the minimum usable panel height in px
     *   (0 = no floor; the wiring passes [MIN_PANEL_HEIGHT] in px).
     */
    fun anchorHeights(
        availableHeightPx: Int,
        collapsedHeightPx: Int,
        previewClaimPx: Int = 0,
        minPanelHeightPx: Int = 0,
    ): Map<PanelState, Float> {
        require(availableHeightPx >= 0) { "availableHeightPx must be >= 0" }
        require(collapsedHeightPx >= 0) { "collapsedHeightPx must be >= 0" }
        require(previewClaimPx >= 0) { "previewClaimPx must be >= 0" }
        require(minPanelHeightPx >= 0) { "minPanelHeightPx must be >= 0" }
        val collapsed = collapsedHeightPx.coerceAtMost(availableHeightPx)
        return mapOf(
            PanelState.COLLAPSED to collapsed.toFloat(),
            PanelState.NORMAL to normalHeightPx(
                availableHeightPx, collapsedHeightPx, previewClaimPx, minPanelHeightPx,
            ).toFloat(),
            PanelState.EXPANDED to expandedHeightPx(
                availableHeightPx, collapsedHeightPx, previewClaimPx, minPanelHeightPx,
            ).toFloat(),
        )
    }

    /**
     * The panel state a category tap produces (section 10.3: "if a user
     * selects a category while the panel is collapsed, open the panel to its
     * normal editing position"). While open, a category tap only switches
     * the category — the panel height never moves (the current category
     * also stays selected when collapsed, so the nav state is untouched).
     */
    fun panelAfterCategoryTap(current: PanelState): PanelState =
        if (current == PanelState.COLLAPSED) PanelState.NORMAL else current
}
