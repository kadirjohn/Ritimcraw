package com.ritim.ritimcraw.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // ---- tier 1 guard: the stats-off 1x1 placeholder is not a shape ----

    @Test
    fun degenerate_1x1_frame_falls_through_to_the_source_pair() {
        // The owner's live bug (2026-11-15, stats off): the GPU path's
        // stats-off fallback publishes a 1x1 opaque-black placeholder, and
        // tier 1 used to take it as truth -> ratio 1.0 -> a SQUARE
        // 1080x1080 surface for a 4:3 clip. The guard skips it to tier 2.
        assertEquals(
            4f / 3f,
            PreviewSurfaceRatio.of(frameWidth = 1, frameHeight = 1, sourceWidth = 4096, sourceHeight = 3072),
            0f,
        )
    }

    @Test
    fun degenerate_1x1_frame_with_no_source_falls_back_to_default_not_square() {
        // 1x1 with nothing else known must NOT become a square surface —
        // it falls to the 16:9 default, same as a null frame.
        assertEquals(
            PreviewSurfaceRatio.DEFAULT_RATIO,
            PreviewSurfaceRatio.of(frameWidth = 1, frameHeight = 1, sourceWidth = 0, sourceHeight = 0),
            0f,
        )
    }

    @Test
    fun degenerate_0x1_and_1x0_frames_fall_through_to_the_source_pair() {
        // The same poison in its half-degenerate forms (0x1 / 1x0): both
        // sides <= 1, no shape either way.
        assertEquals(
            16f / 9f,
            PreviewSurfaceRatio.of(frameWidth = 0, frameHeight = 1, sourceWidth = 3840, sourceHeight = 2160),
            0f,
        )
        assertEquals(
            9f / 16f,
            PreviewSurfaceRatio.of(frameWidth = 1, frameHeight = 0, sourceWidth = 1080, sourceHeight = 1920),
            0f,
        )
    }

    @Test
    fun carries_shape_locks_the_shared_degenerate_rule() {
        // The ONE predicate gating every frame-dims consumer (the ratio
        // chain's tier 1 AND the editor's effectiveDims update): the
        // stats-off placeholder (both sides <= 1) carries no shape; a
        // real square frame does; half-known pairs never do.
        assertTrue(PreviewSurfaceRatio.carriesShape(1000, 1000))
        assertTrue(PreviewSurfaceRatio.carriesShape(2, 1))
        assertTrue(PreviewSurfaceRatio.carriesShape(4032, 3024))
        assertFalse(PreviewSurfaceRatio.carriesShape(1, 1))
        assertFalse(PreviewSurfaceRatio.carriesShape(0, 1))
        assertFalse(PreviewSurfaceRatio.carriesShape(1, 0))
        assertFalse(PreviewSurfaceRatio.carriesShape(0, 0))
        assertFalse(PreviewSurfaceRatio.carriesShape(4032, 0))
        assertFalse(PreviewSurfaceRatio.carriesShape(0, 3024))
        assertFalse(PreviewSurfaceRatio.carriesShape(-1, -1))
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

    // ---- centeredOffset (owner note 1, 2026-11-12: the corner controls
    // must anchor to the video's REAL bounds — the histogram and the two
    // controls used to sit outside the video, on the wrapper's edges) ----

    @Test
    fun height_bounded_video_offsets_in_from_the_wrapper_edge() {
        // The owner's photographed geometry: a 9:16 clip height-bounded in
        // a 393x324 region claims 182x324, so the video's left edge sits
        // 105px in from the wrapper's — the controls anchored to the
        // WRAPPER sat outside the video; anchored to this offset they
        // sit on it.
        assertEquals(
            105 to 0,
            PreviewSurfaceRatio.centeredOffset(regionWidth = 393, regionHeight = 324, ratio = 9f / 16f),
        )
    }

    @Test
    fun width_bound_video_offsets_only_vertically() {
        // A 16:9 clip fills the width (393x221 in the 324-tall region), so
        // the horizontal offset is zero and the video floats 51px below
        // the wrapper's top — the refresh chip anchored to the wrapper's
        // corner sits that far off the video's corner.
        assertEquals(
            0 to 51,
            PreviewSurfaceRatio.centeredOffset(regionWidth = 393, regionHeight = 324, ratio = 16f / 9f),
        )
    }

    @Test
    fun exact_fit_video_is_flush_with_the_wrapper() {
        assertEquals(
            0 to 0,
            PreviewSurfaceRatio.centeredOffset(regionWidth = 400, regionHeight = 400, ratio = 1f),
        )
    }

    @Test
    fun floor_division_never_places_the_rect_outside_the_region() {
        // 100x21 region, 3:1 clip: the claim bounds to 63x21; the half
        // difference 18.5 floors to 18, so offset + size = 81 <= 100 —
        // the rect never exceeds the wrapper on either axis (the
        // wrapper's own centering rounds 18.5 up: at most 1px under a
        // control's 8dp inset, imperceptible).
        val (offsetX, _) = PreviewSurfaceRatio.centeredOffset(regionWidth = 100, regionHeight = 21, ratio = 3f)
        assertEquals(18, offsetX)
        assertTrue(offsetX + 63 <= 100)
    }

    @Test
    fun degenerate_inputs_center_at_zero() {
        // An empty region or unknown ratio has no offset to give: the
        // controls fall back to the wrapper's corners.
        assertEquals(0 to 0, PreviewSurfaceRatio.centeredOffset(regionWidth = 0, regionHeight = 500, ratio = 1f))
        assertEquals(0 to 0, PreviewSurfaceRatio.centeredOffset(regionWidth = 500, regionHeight = 0, ratio = 1f))
        assertEquals(0 to 0, PreviewSurfaceRatio.centeredOffset(regionWidth = 500, regionHeight = 500, ratio = 0f))
        assertEquals(0 to 0, PreviewSurfaceRatio.centeredOffset(regionWidth = 500, regionHeight = 500, ratio = -1f))
    }
}
