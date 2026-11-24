package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ThumbnailRow tests: the pure slot arithmetic behind the Library row's
 * thumbnail — the frame's own aspect ratio fitted into at most
 * 96dp wide x 76dp tall — and the placeholder's aspect source.
 */
class ThumbnailRowTest {

    @Test
    fun slot_fits_the_ratio_into_96_by_76() {
        val wide = ThumbnailRow.slotSize(16f / 9f)
        assertEquals(96f, wide.widthDp, 0.01f)
        assertEquals(54f, wide.heightDp, 0.01f)
        val classic = ThumbnailRow.slotSize(4f / 3f)
        assertEquals(96f, classic.widthDp, 0.01f)
        assertEquals(72f, classic.heightDp, 0.01f)
        val tall = ThumbnailRow.slotSize(9f / 16f)
        assertEquals(42.75f, tall.widthDp, 0.01f)
        assertEquals(76f, tall.heightDp, 0.01f)
    }

    @Test
    fun unknown_aspect_is_16_by_9() {
        assertEquals(96f, ThumbnailRow.slotSize(0f).widthDp, 0.01f)
        assertEquals(54f, ThumbnailRow.slotSize(Float.NaN).heightDp, 0.01f)
        assertEquals(ThumbnailRow.DEFAULT_ASPECT, ThumbnailRow.placeholderAspect(0, 0, 0), 0f)
    }

    @Test
    fun placeholder_aspect_swaps_on_quarter_turns() {
        assertEquals(2f, ThumbnailRow.placeholderAspect(200, 100, 0), 0f)
        assertEquals(0.5f, ThumbnailRow.placeholderAspect(200, 100, 90), 0f)
        assertEquals(2f, ThumbnailRow.placeholderAspect(200, 100, 180), 0f)
        assertEquals(0.5f, ThumbnailRow.placeholderAspect(200, 100, 270), 0f)
    }

    @Test
    fun gutter_is_the_16dp_screen_padding() {
        assertEquals(16f, ThumbnailRow.GUTTER_DP, 0f)
    }

    @Test
    fun grid_tile_keeps_the_ratio_and_fits_tall_clips_into_4_by_3_portrait() {
        val wide = ThumbnailRow.gridTile(16f / 9f)
        assertEquals(16f / 9f, wide.boxAspect, 0.001f)
        assertEquals(1f, wide.imageWidthFraction, 0f)
        val classic = ThumbnailRow.gridTile(4f / 3f)
        assertEquals(4f / 3f, classic.boxAspect, 0.001f)
        // 9:16 is more vertical than 3:4: box capped at 3:4, image 0.75 of the width.
        val tall = ThumbnailRow.gridTile(9f / 16f)
        assertEquals(0.75f, tall.boxAspect, 0.001f)
        assertEquals(0.75f, tall.imageWidthFraction, 0.001f)
        assertEquals(16f / 9f, ThumbnailRow.gridTile(0f).boxAspect, 0.001f)
        // A 90-degree rotated 16:9 record places as portrait.
        val rotated = ThumbnailRow.gridTile(ThumbnailRow.placeholderAspect(1920, 1080, 90))
        assertEquals(0.75f, rotated.boxAspect, 0.001f)
    }
}
