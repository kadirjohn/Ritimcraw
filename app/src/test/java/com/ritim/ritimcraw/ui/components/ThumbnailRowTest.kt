package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ThumbnailRow tests (owner finding 2026-11-14, the uniform-slot
 * extension): the pure slot arithmetic behind the Library row's
 * thumbnail — ONE FIXED 76x76dp square for every frame ratio, the
 * standard gallery pattern the finding asked for over the ratio-true
 * 40..120dp band (which made a 9:16 row 120dp tall while a 16:9 row sat
 * at 43dp — the list's rhythm broke between vertical, horizontal and
 * open-gate clips). The icon stays ratio-true INSIDE the slot
 * (ContentScale.Fit letterboxing, the row composable's own contract);
 * these tests lock the slot's uniformity and the shared gutter the
 * divider starts at.
 */
class ThumbnailRowTest {

    // --- the uniform slot --------------------------------------------------

    @Test
    fun slot_is_the_shipped_square() {
        // The slot keeps the 76dp square the list shipped with: inside
        // the 76-96dp band the finding allowed, and it keeps the
        // divider/text geometry stable for every ratio.
        assertEquals(76f, ThumbnailRow.WIDTH_DP, 0f)
        assertEquals(76f, ThumbnailRow.HEIGHT_DP, 0f)
    }

    @Test
    fun slot_is_square_so_every_row_has_the_same_height() {
        // The uniformity the finding asked for: WIDTH == HEIGHT, whatever
        // the clip's ratio — a vertical, a horizontal and an open-gate
        // row all render 76dp tall.
        assertEquals(ThumbnailRow.WIDTH_DP, ThumbnailRow.HEIGHT_DP, 0f)
    }

    // --- the shared gutter --------------------------------------------------

    @Test
    fun gutter_is_the_16dp_screen_padding() {
        // The thumbnail's own start gutter (the "sadece thumbnail'ler
        // için" margin) and the divider's start are the SAME constant
        // (owner finding 2026-11-14: a divider that runs to the screen's
        // edge must begin at the thumbnail's edge) — they cannot drift.
        assertEquals(16f, ThumbnailRow.GUTTER_DP, 0f)
    }
}
