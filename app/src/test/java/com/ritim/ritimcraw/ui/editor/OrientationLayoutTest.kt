package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Landscape workspace decisions (owner request 2026-11-11d sections
 * 17.1-17.3): the two-column layout engages responsively (not one
 * hardcoded split), the panel share stays inside the spec's 30-40% band
 * at every width, and hiding the panel expands the preview horizontally
 * to the full width.
 */
class OrientationLayoutTest {

    // ---- landscape detection ----

    @Test
    fun portrait_is_not_landscape() {
        assertFalse(OrientationLayout.isLandscape(widthDp = 400, heightDp = 800))
    }

    @Test
    fun wide_is_landscape() {
        assertTrue(OrientationLayout.isLandscape(widthDp = 800, heightDp = 400))
    }

    @Test
    fun square_is_not_landscape() {
        // The portrait column is the safe default for square windows.
        assertFalse(OrientationLayout.isLandscape(widthDp = 600, heightDp = 600))
    }

    // ---- two-column engagement (section 17.1) ----

    @Test
    fun narrow_landscape_keeps_the_single_column() {
        // Landscape but under the two-column threshold: the portrait
        // column carries over (both columns would be cramped).
        assertFalse(OrientationLayout.isTwoColumn(widthDp = 500, heightDp = 300))
    }

    @Test
    fun real_phone_landscape_is_two_column() {
        // ~640dp is the narrowest real-phone landscape width.
        assertTrue(OrientationLayout.isTwoColumn(widthDp = 640, heightDp = 360))
        assertTrue(OrientationLayout.isTwoColumn(widthDp = 915, heightDp = 411))
    }

    @Test
    fun wide_portrait_is_never_two_column() {
        // A tall tablet portrait (e.g. 800x1200) is not landscape.
        assertFalse(OrientationLayout.isTwoColumn(widthDp = 800, heightDp = 1200))
    }

    // ---- responsive panel split (section 17.1: 30-40% band) ----

    @Test
    fun panel_share_stays_in_the_spec_band_at_every_width() {
        for (width in 600..2000 step 50) {
            val share = OrientationLayout.panelWidthFraction(width)
            assertTrue(
                "width ${width}dp share $share",
                share in OrientationLayout.PANEL_FRACTION_MIN..OrientationLayout.PANEL_FRACTION_MAX,
            )
        }
    }

    @Test
    fun wide_screens_pin_the_panel_to_a_comfortable_dp_width() {
        // On a very wide screen the 300dp target would fall below the
        // band (300/1200 = 25%), so the band wins: the clamp pins the
        // panel to the 30% floor — the spec's 30-40% share always holds.
        assertTrue(OrientationLayout.panelWidthFraction(1200) <= OrientationLayout.PANEL_FRACTION_MAX)
        assertEquals(
            OrientationLayout.PANEL_FRACTION_MIN,
            OrientationLayout.panelWidthFraction(1200),
            0.001f,
        )
        // On a mid-wide screen (800dp) the target lands inside the band
        // untouched: 300/800 = 37.5%.
        assertEquals(0.375f, OrientationLayout.panelWidthFraction(800), 0.001f)
    }

    @Test
    fun narrow_two_column_screens_give_the_panel_its_full_band_share() {
        // 640dp: 300/640 = 0.469 > 0.40, so the clamp pins the panel to
        // the band's top — the panel keeps 40% on the narrowest phones.
        assertEquals(
            OrientationLayout.PANEL_FRACTION_MAX,
            OrientationLayout.panelWidthFraction(640),
            0.001f,
        )
    }

    @Test
    fun panel_width_dp_never_negative_and_never_wider_than_the_workspace() {
        assertEquals(0, OrientationLayout.panelWidthDp(0))
        assertEquals(0, OrientationLayout.panelWidthDp(-100))
        assertTrue(OrientationLayout.panelWidthDp(800) in 1..800)
    }

    // ---- preview expansion (section 17.3: hide expands horizontally) ----

    @Test
    fun hiding_the_panel_expands_the_preview_to_the_full_width() {
        // Section 17.3: "collapsing the adjustment area may expand the
        // preview horizontally rather than vertically."
        assertEquals(
            1f,
            OrientationLayout.previewWidthFraction(
                panelVisible = false,
                widthDp = 800,
                heightDp = 400,
            ),
            0f,
        )
    }

    @Test
    fun visible_panel_leaves_the_preview_the_complement_share() {
        assertEquals(
            1f - OrientationLayout.panelWidthFraction(800),
            OrientationLayout.previewWidthFraction(panelVisible = true, widthDp = 800, heightDp = 400),
            0.001f,
        )
    }

    @Test
    fun portrait_preview_is_always_full_width() {
        // The single portrait column is full-width whether or not the
        // panel is visible (the panel stacks UNDER it there).
        assertEquals(
            1f,
            OrientationLayout.previewWidthFraction(panelVisible = false, widthDp = 400, heightDp = 800),
            0f,
        )
        assertEquals(
            1f,
            OrientationLayout.previewWidthFraction(panelVisible = true, widthDp = 400, heightDp = 800),
            0f,
        )
    }

    // ---- category rail width (owner layout-refactor spec 2026-11-12
    // section 5: the Light/Color/Detail nav moves to a compact vertical
    // FAR-RIGHT rail in the two-column workspace; the bottom bar becomes
    // portrait-only) ----

    @Test
    fun no_rail_outside_the_two_column_workspace() {
        // Portrait, square, fullscreen-narrow: the rail never composes —
        // the bottom bar carries the categories there.
        assertEquals(0, OrientationLayout.railWidthDp(widthDp = 400, heightDp = 800))
        assertEquals(0, OrientationLayout.railWidthDp(widthDp = 600, heightDp = 600))
        assertEquals(0, OrientationLayout.railWidthDp(widthDp = 500, heightDp = 300))
    }

    @Test
    fun rail_width_stays_in_the_spec_band_at_every_two_column_width() {
        // Section 5: "A width in the approximate range of 56-72 dp may be
        // a reasonable starting point... Do not hardcode a value that
        // breaks smaller or larger displays." The measured target is
        // clamped into the band at every real-phone landscape width.
        for (width in 600..2000 step 50) {
            val rail = OrientationLayout.railWidthDp(width, 400)
            assertTrue(
                "width ${width}dp rail $rail",
                rail in OrientationLayout.RAIL_WIDTH_MIN_DP..OrientationLayout.RAIL_WIDTH_MAX_DP,
            )
        }
    }

    @Test
    fun narrowest_phones_get_the_band_floor() {
        // 640dp (the narrowest real-phone landscape): 8% = 51dp clamps UP
        // to 56 — the rail keeps its readable icon+label stack even where
        // every pixel matters most.
        assertEquals(56, OrientationLayout.railWidthDp(widthDp = 640, heightDp = 360))
    }

    @Test
    fun wide_displays_cap_at_the_band_ceiling() {
        // 8% of 1200dp = 96dp would eat the preview; the band caps the
        // rail at 72dp — it must not grow into a wide column exactly
        // because the display is wide.
        assertEquals(72, OrientationLayout.railWidthDp(widthDp = 1200, heightDp = 500))
    }

    @Test
    fun mid_width_lands_on_the_measured_target_inside_the_band() {
        // 800dp: 8% = 64dp, inside the band untouched — the measured
        // value, not a hardcoded one.
        assertEquals(64, OrientationLayout.railWidthDp(widthDp = 800, heightDp = 400))
    }
}
