package com.ritim.ritimcraw.ui.editor

/**
 * Pure landscape-workspace decisions (owner request 2026-11-11d sections
 * 17.1-17.3), JVM-tested, no Compose dependencies.
 *
 * Section 17.1: "Left: approximately 60–70% for video preview and playback;
 * Right: approximately 30–40% for adjustment controls. Use responsive
 * constraints rather than hardcoding one split for every device." The
 * split is therefore a target-width clamp, not one fraction: the panel
 * column aims for [PANEL_TARGET_DP] and the fraction is coerced into the
 * spec's 30-40% band, so narrow landscape devices give the panel a
 * larger share and wide ones a smaller share, without ever leaving the
 * band.
 *
 * Section 17.3: "collapsing the adjustment area may expand the preview
 * horizontally rather than vertically" — [previewWidthFraction] returns
 * the full width when the panel is hidden, which is exactly that
 * horizontal expansion (the preview Box is width-weighted in the
 * landscape Row; see ClipEditorScreen).
 *
 * CATEGORY RAIL (owner layout-refactor spec 2026-11-12 section 5 — it
 * REVERSES the old section 17.1 note that the bottom bar was final): in
 * the two-column workspace the Light/Color/Detail navigation moves to a
 * compact vertical rail on the FAR RIGHT edge, the adjustment panel
 * sits immediately to its left, and the bottom bar becomes
 * portrait-only. [railWidthDp] is the rail's measured width — the same
 * target-and-clamp shape as the panel split, never one hardcoded dp.
 *
 * Everything here is dp-domain arithmetic on the CURRENT configuration
 * (LocalConfiguration's screenWidthDp/screenHeightDp track rotation
 * in place); the editor consults it on every recomposition, so the
 * workspace re-splits responsively at any rotation or window size
 * without any activity recreation.
 */
object OrientationLayout {

    /**
     * Two columns need room for BOTH columns: below this landscape width
     * the editor keeps the single portrait column (split-screen halves,
     * small windows). Real phones in landscape are ~640-915dp wide, so
     * the two-column workspace (section 17.1) covers every real phone;
     * the threshold only guards degenerate widths.
     */
    const val TWO_COLUMN_MIN_WIDTH_DP = 600

    /**
     * The comfortable adjustment-column width the split aims for on wide
     * screens; section 17.1's 30-40% band is the clamp around it.
     */
    const val PANEL_TARGET_DP = 300

    /** Section 17.1 band: the panel's share of the workspace width. */
    const val PANEL_FRACTION_MIN = 0.30f
    const val PANEL_FRACTION_MAX = 0.40f

    /**
     * Landscape = the current width exceeds the current height. Square
     * windows are NOT landscape (the portrait column is the safe
     * default).
     */
    fun isLandscape(widthDp: Int, heightDp: Int): Boolean = widthDp > heightDp

    /**
     * The landscape editing workspace (section 17.1) is two columns only
     * when both columns have room; otherwise the portrait single column
     * carries over unchanged.
     */
    fun isTwoColumn(widthDp: Int, heightDp: Int): Boolean =
        isLandscape(widthDp, heightDp) && widthDp >= TWO_COLUMN_MIN_WIDTH_DP

    /**
     * Responsive panel share of the workspace width (section 17.1:
     * "responsive constraints rather than hardcoding one split"):
     * PANEL_TARGET_DP / width, clamped into the 30-40% band. Degenerate
     * widths stay in band by the clamp (0 or negative width cannot reach
     * the division — see [panelWidthDp]).
     */
    fun panelWidthFraction(widthDp: Int): Float {
        if (widthDp <= 0) return PANEL_FRACTION_MAX
        return (PANEL_TARGET_DP.toFloat() / widthDp.toFloat())
            .coerceIn(PANEL_FRACTION_MIN, PANEL_FRACTION_MAX)
    }

    /**
     * The panel column's width in dp: the fraction applied to the width,
     * never negative and never wider than the workspace itself.
     */
    fun panelWidthDp(widthDp: Int): Int {
        if (widthDp <= 0) return 0
        return (widthDp * panelWidthFraction(widthDp)).toInt().coerceIn(0, widthDp)
    }

    /**
     * The preview column's share of the workspace width. Full width in
     * the single-column layout; in the two-column layout it is what the
     * panel leaves (60-70%) — or the FULL width when the panel is hidden
     * (section 17.3: collapsing the adjustment area expands the preview
     * horizontally).
     */
    fun previewWidthFraction(panelVisible: Boolean, widthDp: Int, heightDp: Int): Float =
        when {
            !isTwoColumn(widthDp, heightDp) -> 1f
            !panelVisible -> 1f
            else -> 1f - panelWidthFraction(widthDp)
        }

    /**
     * The category rail's width band (owner layout-refactor spec 2026-11-12
     * section 5): "A width in the approximate range of 56-72 dp may be a
     * reasonable starting point on suitable landscape phone screens, but
     * measure against actual constraints. Do not hardcode a value that
     * breaks smaller or larger displays."
     */
    const val RAIL_WIDTH_MIN_DP = 56
    const val RAIL_WIDTH_MAX_DP = 72

    /**
     * The category rail's target share of the workspace width — the
     * measured input of [railWidthDp], the analog of [PANEL_TARGET_DP].
     * 8%: the M3 NavigationRail's own default content is 80dp wide, so
     * a hair under 10% of a phone's landscape width keeps the rail a
     * compact sliver while the stock component's icon+label stack stays
     * legible; the band clamps it at both ends.
     */
    const val RAIL_TARGET_FRACTION = 0.08f

    /**
     * The landscape category rail's width in dp (owner layout-refactor
     * spec 2026-11-12 section 5): 0 when the workspace is not two-column
     * (the rail never composes — the bottom bar carries the categories
     * there), else [RAIL_TARGET_FRACTION] of the workspace width clamped
     * into the spec's 56-72dp band. The same target-and-clamp shape as
     * the panel split: the narrowest phones (640dp) get the band's floor
     * (56dp — every pixel saved matters there), wide ones cap at the
     * band's ceiling (72dp — the rail must not grow into a wide column
     * exactly because the display is wide).
     */
    fun railWidthDp(widthDp: Int, heightDp: Int): Int {
        if (!isTwoColumn(widthDp, heightDp)) return 0
        val target = (widthDp * RAIL_TARGET_FRACTION).toInt()
        return target.coerceIn(RAIL_WIDTH_MIN_DP, RAIL_WIDTH_MAX_DP)
    }
}
