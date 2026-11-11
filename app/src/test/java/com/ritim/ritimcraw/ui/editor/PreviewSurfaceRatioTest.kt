package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PreviewSurfaceRatio tests: the pure aspect-ratio fallback chain that
 * shapes the preview surface like the clip itself (owner finding
 * 2026-11-10 — the preview was a fixed box; it must be 4:3, 16:9, 9:16,
 * whatever the clip is).
 */
class PreviewSurfaceRatioTest {

    // ---- tier 1: the rendered frame wins ----

    @Test
    fun rendered_frame_shapes_the_surface() {
        // 4:3 clip: the frame dims (already rotated upright by the renderer)
        // are used as-is.
        assertEquals(
            4f / 3f,
            PreviewSurfaceRatio.of(frameWidth = 4032, frameHeight = 3024, sourceWidth = 0, sourceHeight = 0),
            0f,
        )
    }

    @Test
    fun rendered_frame_wins_over_persisted_stats() {
        // The frame reflects what the renderer actually produced (rotation,
        // tier scaling) — when present it is the truth.
        assertEquals(
            9f / 16f,
            PreviewSurfaceRatio.of(frameWidth = 720, frameHeight = 1280, sourceWidth = 4000, sourceHeight = 3000),
            0f,
        )
    }

    @Test
    fun square_frame_is_square() {
        assertEquals(
            1f,
            PreviewSurfaceRatio.of(frameWidth = 1000, frameHeight = 1000, sourceWidth = 0, sourceHeight = 0),
            0f,
        )
    }

    @Test
    fun anamorphic_frame_ratio_preserved_exactly() {
        // No normalization to "nice" ratios: 2.39:1 stays 2.39:1.
        assertEquals(
            2.39f,
            PreviewSurfaceRatio.of(frameWidth = 2390, frameHeight = 1000, sourceWidth = 0, sourceHeight = 0),
            1e-6f,
        )
    }

    // ---- tier 2: persisted import stats shape the placeholder ----

    @Test
    fun persisted_stats_shape_pre_first_frame_placeholder() {
        // Before the first render lands, the persisted engine-pass dims give
        // the placeholder the clip's shape.
        assertEquals(
            16f / 9f,
            PreviewSurfaceRatio.of(frameWidth = 0, frameHeight = 0, sourceWidth = 3840, sourceHeight = 2160),
            0f,
        )
    }

    @Test
    fun persisted_portrait_stats_shape_portrait_placeholder() {
        assertEquals(
            9f / 16f,
            PreviewSurfaceRatio.of(frameWidth = 0, frameHeight = 0, sourceWidth = 1080, sourceHeight = 1920),
            0f,
        )
    }

    // ---- tier 3: nothing known -> 16:9 ----

    @Test
    fun nothing_known_falls_back_to_16_9() {
        assertEquals(
            PreviewSurfaceRatio.DEFAULT_RATIO,
            PreviewSurfaceRatio.of(frameWidth = 0, frameHeight = 0, sourceWidth = 0, sourceHeight = 0),
            0f,
        )
    }

    // ---- half-known pairs never shape the surface ----

    @Test
    fun half_known_frame_falls_through() {
        // A frame with only a width (renderer bug guard) is not a shape.
        assertEquals(
            4f / 3f,
            PreviewSurfaceRatio.of(frameWidth = 4032, frameHeight = 0, sourceWidth = 4000, sourceHeight = 3000),
            0f,
        )
    }

    @Test
    fun half_known_source_falls_back_to_default() {
        assertEquals(
            PreviewSurfaceRatio.DEFAULT_RATIO,
            PreviewSurfaceRatio.of(frameWidth = 0, frameHeight = 0, sourceWidth = 3840, sourceHeight = 0),
            0f,
        )
    }

    @Test
    fun negative_dims_count_as_unknown() {
        assertEquals(
            PreviewSurfaceRatio.DEFAULT_RATIO,
            PreviewSurfaceRatio.of(frameWidth = -1, frameHeight = -1, sourceWidth = -1, sourceHeight = -1),
            0f,
        )
    }

    // ---- claimedSize (owner finding 2026-11-11 E1: the portrait preview
    // overflowed the capped wrapper and covered the top bar's ⋮ menu) ----

    @Test
    fun portrait_ratio_claims_the_capped_height_not_the_overflowing_full_width_height() {
        // The E1 geometry on a portrait phone: a 16:9 clip quarter-rotated
        // to 9:16 inside a 393x324dp capped region. The old aspectRatio
        // fallback claimed 393x698 — 374dp of overflow painting over the
        // top bar. The claim rule bounds it to the cap instead.
        val (width, height) = PreviewSurfaceRatio.claimedSize(
            regionWidth = 393,
            regionHeight = 324,
            ratio = 9f / 16f,
        )
        assertEquals(182, width)
        assertEquals(324, height) // exactly the cap, never above it
    }

    @Test
    fun fitting_ratio_fills_the_width() {
        // A landscape 16:9 clip in the same portrait-phone region: the
        // full-width height fits the cap, so the surface fills the width.
        val (width, height) = PreviewSurfaceRatio.claimedSize(
            regionWidth = 393,
            regionHeight = 324,
            ratio = 16f / 9f,
        )
        assertEquals(393, width)
        assertEquals(221, height)
    }

    @Test
    fun landscape_device_orientation_bounds_a_landscape_clip_the_same_way() {
        // The latent twin: a landscape phone (852 wide) with the 38% cap
        // (~149dp) and a landscape 16:9 clip — full-width height 479 would
        // overflow; the claim rule bounds by the cap.
        val (width, height) = PreviewSurfaceRatio.claimedSize(
            regionWidth = 852,
            regionHeight = 149,
            ratio = 16f / 9f,
        )
        assertEquals(264, width)
        assertEquals(149, height)
    }

    @Test
    fun square_ratio_fills_the_region() {
        val (width, height) = PreviewSurfaceRatio.claimedSize(
            regionWidth = 400,
            regionHeight = 400,
            ratio = 1f,
        )
        assertEquals(400, width)
        assertEquals(400, height)
    }

    @Test
    fun exact_fit_takes_the_full_width() {
        // Boundary: the full-width height equals the cap exactly — the
        // first branch takes it (never a spurious bound).
        val (width, height) = PreviewSurfaceRatio.claimedSize(
            regionWidth = 200,
            regionHeight = 100,
            ratio = 2f,
        )
        assertEquals(200, width)
        assertEquals(100, height)
    }

    @Test
    fun rounding_keeps_the_claim_inside_the_cap() {
        // 100/3 = 33.33: at cap 33 the full width fits by flooring; at
        // cap 20 the claim bounds by height with the floor on the width.
        val fits = PreviewSurfaceRatio.claimedSize(regionWidth = 100, regionHeight = 33, ratio = 3f)
        assertEquals(100 to 33, fits)
        val bounded = PreviewSurfaceRatio.claimedSize(regionWidth = 100, regionHeight = 20, ratio = 3f)
        assertEquals(60 to 20, bounded)
    }

    @Test
    fun degenerate_region_or_ratio_claims_nothing() {
        assertEquals(0 to 0, PreviewSurfaceRatio.claimedSize(regionWidth = 0, regionHeight = 500, ratio = 1f))
        assertEquals(0 to 0, PreviewSurfaceRatio.claimedSize(regionWidth = 500, regionHeight = 0, ratio = 1f))
        assertEquals(0 to 0, PreviewSurfaceRatio.claimedSize(regionWidth = 500, regionHeight = 500, ratio = 0f))
        assertEquals(0 to 0, PreviewSurfaceRatio.claimedSize(regionWidth = 500, regionHeight = 500, ratio = -1f))
    }
}
