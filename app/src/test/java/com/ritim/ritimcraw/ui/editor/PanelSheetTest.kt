package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Panel state machine tests (owner request 2026-11-11d sections 10-11): the
 * three anchors adapt to the measured region and stay ordered on every
 * device, the snap thresholds are the Material sheet conventions, and a
 * category tap while collapsed reopens to normal (section 10.3).
 */
class PanelSheetTest {

    // ---- anchor heights ----

    @Test
    fun anchors_are_ordered_collapsed_normal_expanded() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
    }

    @Test
    fun normal_is_the_44_percent_draft_share() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertEquals(440f, anchors.getValue(PanelState.NORMAL), 0.5f)
    }

    @Test
    fun expanded_is_the_72_percent_share() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertEquals(720f, anchors.getValue(PanelState.EXPANDED), 0.5f)
    }

    @Test
    fun collapsed_anchor_is_the_handle_strip() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        assertEquals(48f, anchors.getValue(PanelState.COLLAPSED), 0f)
    }

    @Test
    fun anchors_never_exceed_the_region() {
        // A tall handle strip near the region size: every anchor is coerced
        // into the region and stays ordered (degenerate but never broken).
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 100, collapsedHeightPx = 90)
        assertTrue(anchors.getValue(PanelState.EXPANDED) <= 100f)
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
    }

    @Test
    fun degenerate_zero_region_collapses_all_anchors_to_zero() {
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 0, collapsedHeightPx = 48)
        assertEquals(0f, anchors.getValue(PanelState.COLLAPSED), 0f)
        assertEquals(0f, anchors.getValue(PanelState.NORMAL), 0f)
        assertEquals(0f, anchors.getValue(PanelState.EXPANDED), 0f)
    }

    @Test
    fun tiny_region_still_yields_ordered_anchors() {
        // 30 px region with a 48 px strip: the strip is coerced in and the
        // fractions still produce ordered, in-region anchors.
        val anchors = PanelSheet.anchorHeights(availableHeightPx = 30, collapsedHeightPx = 48)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= 30f)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
    }

    @Test
    fun anchor_heights_reject_negative_inputs() {
        assertTrue(
            runCatching { PanelSheet.anchorHeights(-1, 48) }.exceptionOrNull()
                is IllegalArgumentException,
        )
        assertTrue(
            runCatching { PanelSheet.anchorHeights(1000, -1) }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    // ---- snap thresholds ----

    @Test
    fun positional_threshold_is_half_the_inter_anchor_distance() {
        assertEquals(50f, PanelSheet.positionalThreshold(100f), 1e-4f)
        assertEquals(0f, PanelSheet.positionalThreshold(0f), 1e-4f)
    }

    @Test
    fun snap_duration_is_inside_the_spec_band() {
        // Section 11.4: "approximately 180-250ms".
        assertTrue(PanelSheet.SNAP_DURATION_MS in 180..250)
    }

    // ---- category tap decision (section 10.3) ----

    @Test
    fun category_tap_while_collapsed_reopens_to_normal() {
        assertEquals(PanelState.NORMAL, PanelSheet.panelAfterCategoryTap(PanelState.COLLAPSED))
    }

    @Test
    fun category_tap_while_open_never_moves_the_panel() {
        assertEquals(PanelState.NORMAL, PanelSheet.panelAfterCategoryTap(PanelState.NORMAL))
        assertEquals(PanelState.EXPANDED, PanelSheet.panelAfterCategoryTap(PanelState.EXPANDED))
    }

    // ---- collapsed strip trim (owner add-on 2026-11-12) ----

    @Test
    fun collapsed_strip_is_trimmed_to_36dp() {
        // Before the trim the collapsed anchor was the 48dp handle
        // target itself; after it the visible strip is 36dp — a 25%
        // reduction of the strip the preview has to give up.
        assertEquals(36f, PanelCollapsedHeight.value, 0f)
    }

    @Test
    fun handle_target_stays_the_48dp_minimum() {
        // The trim shrinks the STRIP, never the touch target: the handle
        // keeps its 48dp minimum touch target (DESIGN.md).
        assertEquals(48f, PanelHandleHeight.value, 0f)
    }

    @Test
    fun handle_overhangs_the_trimmed_strip_by_12dp() {
        // The 48dp target inside the 36dp collapsed sheet overhangs 12dp
        // downward into the navigation bar's empty top chrome —
        // hit-testable because the sheet draws its shape with
        // Modifier.background, not a clipping Surface.
        assertTrue(PanelCollapsedHeight < PanelHandleHeight)
        assertEquals(12f, (PanelHandleHeight - PanelCollapsedHeight).value, 0f)
    }

    // ---- preview claim (owner spec 2026-11-12 section 2, the root-cause
    // fix: the panel's fractions shrank the preview Box below the video's
    // full-width aspect height, so the video's WIDTH collapsed) ----

    @Test
    fun landscape_clip_claims_its_full_width_aspect_height() {
        // The owner's photographed geometry: a portrait-phone free region
        // 393x628dp with a 16:9 clip — full width 393 needs height
        // 393/(16/9) = 221, which fits, so the claim is 221.
        assertEquals(
            221,
            PanelSheet.previewClaimPx(regionWidthPx = 393, regionHeightPx = 628, ratio = 16f / 9f),
        )
    }

    @Test
    fun clip_taller_than_the_region_claims_nothing() {
        // A 9:16 clip in the same region needs height 393/(9/16) = 698 —
        // taller than the region, so it can never be width-bound
        // (claimedSize bounds it by height instead): no width to
        // preserve, legacy flex anchors apply.
        assertEquals(
            0,
            PanelSheet.previewClaimPx(regionWidthPx = 393, regionHeightPx = 628, ratio = 9f / 16f),
        )
    }

    @Test
    fun exact_fit_boundary_takes_the_full_claim() {
        // fullWidthHeight == regionHeight exactly: the claim fits (never
        // a spurious zero at the boundary).
        assertEquals(
            100,
            PanelSheet.previewClaimPx(regionWidthPx = 200, regionHeightPx = 100, ratio = 2f),
        )
    }

    @Test
    fun degenerate_claim_inputs_claim_nothing() {
        assertEquals(0, PanelSheet.previewClaimPx(regionWidthPx = 0, regionHeightPx = 628, ratio = 16f / 9f))
        assertEquals(0, PanelSheet.previewClaimPx(regionWidthPx = 393, regionHeightPx = 0, ratio = 16f / 9f))
        assertEquals(0, PanelSheet.previewClaimPx(regionWidthPx = 393, regionHeightPx = 628, ratio = 0f))
        assertEquals(0, PanelSheet.previewClaimPx(regionWidthPx = 393, regionHeightPx = 628, ratio = -1f))
    }

    // ---- the anchor cap (region minus claim, floored at MIN_PANEL_HEIGHT) ----

    @Test
    fun cap_is_the_region_minus_the_claim() {
        // 628 - 221 = 407: the panel may grow until the preview's
        // width-bound height would no longer fit beneath it.
        assertEquals(407, PanelSheet.panelCapPx(regionHeightPx = 628, previewClaimPx = 221, minPanelHeightPx = 192))
    }

    @Test
    fun zero_claim_leaves_the_cap_at_the_whole_region() {
        // The legacy behavior: no claim, no cap (the wiring's default
        // params keep every pre-fix call byte-identical).
        assertEquals(628, PanelSheet.panelCapPx(regionHeightPx = 628, previewClaimPx = 0, minPanelHeightPx = 0))
    }

    @Test
    fun cap_floors_at_the_minimum_usable_panel_height() {
        // A claim that eats all but 20px of a 200px region: the cap
        // refuses to go below MIN_PANEL_HEIGHT (192) — a three-slider-row
        // panel is worth more than the last few px of an already huge
        // preview.
        assertEquals(192, PanelSheet.panelCapPx(regionHeightPx = 200, previewClaimPx = 180, minPanelHeightPx = 192))
    }

    @Test
    fun cap_floor_is_coerced_into_the_region_for_degenerate_tiny_regions() {
        // minPanelHeight larger than the region: the floor coerces in,
        // ordering wins over the cap (never an out-of-region anchor).
        assertEquals(30, PanelSheet.panelCapPx(regionHeightPx = 30, previewClaimPx = 25, minPanelHeightPx = 192))
    }

    @Test
    fun degenerate_region_caps_to_zero() {
        assertEquals(0, PanelSheet.panelCapPx(regionHeightPx = 0, previewClaimPx = 221, minPanelHeightPx = 192))
    }

    @Test
    fun min_panel_height_is_three_slider_rows_plus_the_handle() {
        // CompactSliderDefaults.ROW_HEIGHT (48dp) x3 + the 48dp handle
        // target: the smallest panel that is still usable for grading.
        assertEquals(192f, PanelSheet.MIN_PANEL_HEIGHT.value, 0f)
    }

    // ---- capped anchors (the fix, end to end) ----

    @Test
    fun normal_anchor_is_byte_identical_when_the_claim_fits_beside_it() {
        // The owner's photographed geometry: region 628, collapsed 36,
        // 16:9 claim 221. NORMAL = 0.44 x 628 = 276 (cap 407 never
        // engages) — the familiar geometry is unchanged on common devices.
        val anchors = PanelSheet.anchorHeights(
            availableHeightPx = 628,
            collapsedHeightPx = 36,
            previewClaimPx = 221,
            minPanelHeightPx = 192,
        )
        assertEquals(276f, anchors.getValue(PanelState.NORMAL), 0.5f)
    }

    @Test
    fun expanded_anchor_caps_where_the_owner_saw_the_shrink() {
        // Same geometry: EXPANDED = 0.72 x 628 = 452 would leave the
        // preview Box 628 - 452 = 176 < the 221 the full width needs —
        // the video's width would collapse. The cap holds the anchor at
        // 628 - 221 = 407, so the video keeps the full width at EVERY
        // panel state.
        val anchors = PanelSheet.anchorHeights(
            availableHeightPx = 628,
            collapsedHeightPx = 36,
            previewClaimPx = 221,
            minPanelHeightPx = 192,
        )
        assertEquals(407f, anchors.getValue(PanelState.EXPANDED), 0f)
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
    }

    @Test
    fun zero_claim_keeps_the_legacy_anchors_byte_identical() {
        // The defaulted params (0, 0) reproduce the pre-fix anchors
        // exactly — acceptance criterion H: no unnecessary rewrite.
        val legacy = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48)
        val defaulted = PanelSheet.anchorHeights(availableHeightPx = 1000, collapsedHeightPx = 48, previewClaimPx = 0, minPanelHeightPx = 0)
        assertEquals(legacy, defaulted)
    }

    @Test
    fun a_tall_claims_leftover_still_yields_ordered_usable_anchors() {
        // Region 300, claim 200, floor 192: cap = 192 (the 100px leftover
        // refused). EXPANDED caps 216 -> 192; NORMAL 132 fits under it —
        // ordered, in-region, and the panel keeps three slider rows.
        val anchors = PanelSheet.anchorHeights(
            availableHeightPx = 300,
            collapsedHeightPx = 36,
            previewClaimPx = 200,
            minPanelHeightPx = 192,
        )
        assertEquals(192f, anchors.getValue(PanelState.EXPANDED), 0f)
        assertEquals(132f, anchors.getValue(PanelState.NORMAL), 0f)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
    }

    @Test
    fun a_claim_of_the_whole_region_still_leaves_the_minimum_panel() {
        // The degenerate extreme: claim == region. The cap floors at
        // MIN_PANEL_HEIGHT; the fractions land under it and stay ordered.
        val anchors = PanelSheet.anchorHeights(
            availableHeightPx = 400,
            collapsedHeightPx = 36,
            previewClaimPx = 400,
            minPanelHeightPx = 192,
        )
        assertEquals(192f, anchors.getValue(PanelState.EXPANDED), 0f)
        assertEquals(176f, anchors.getValue(PanelState.NORMAL), 0f)
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
    }

    @Test
    fun capped_anchors_stay_ordered_in_a_degenerate_tiny_region() {
        // Region 30, claim 25, floor 192 (> region): everything coerces
        // into the region — the same never-broken ordering as the
        // uncapped degenerate cases above.
        val anchors = PanelSheet.anchorHeights(
            availableHeightPx = 30,
            collapsedHeightPx = 48,
            previewClaimPx = 25,
            minPanelHeightPx = 192,
        )
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= 30f)
        assertTrue(anchors.getValue(PanelState.COLLAPSED) <= anchors.getValue(PanelState.NORMAL))
        assertTrue(anchors.getValue(PanelState.NORMAL) <= anchors.getValue(PanelState.EXPANDED))
        assertTrue(anchors.getValue(PanelState.EXPANDED) <= 30f)
    }
}
