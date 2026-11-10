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
}
