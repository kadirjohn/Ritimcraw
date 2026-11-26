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

    // ---- gridRows (owner request 2026-11-26 + dynamic refinement: a
    // row's height is set by its TALLEST clip; chunking follows the ACTUAL
    // column count, which changes with orientation) ----

    @Test
    fun grid_rows_chunk_mixed_aspects_and_set_the_row_height_by_the_tallest_clip() {
        // The owner's scene: one 16:9 clip beside open-gate 4:3 clips. At
        // 3 columns the rows are [16:9, 4:3, 16:9], [4:3, ...]: each row's
        // box takes the LOWEST aspect (the tallest clip) — 4:3 here.
        val aspects = listOf(16f / 9f, 4f / 3f, 16f / 9f, 4f / 3f, 16f / 9f)
        val rows = ThumbnailRow.gridRows(aspects, columns = 3)
        assertEquals(2, rows.size)
        assertEquals(listOf(16f / 9f, 4f / 3f, 16f / 9f), rows[0].aspects)
        assertEquals(4f / 3f, rows[0].rowAspect, 0.001f)
        assertEquals(listOf(4f / 3f, 16f / 9f), rows[1].aspects)
        assertEquals(4f / 3f, rows[1].rowAspect, 0.001f)
    }

    @Test
    fun grid_rows_follow_the_column_count_both_orientations() {
        // 8 clips of 16:9: portrait 3 columns -> rows of 3+3+2; landscape
        // 4 columns -> rows of 4+4. The chunking (and therefore which
        // clips share a row) re-derives with the column count.
        val aspects = List(8) { 16f / 9f }
        assertEquals(listOf(3, 3, 2), ThumbnailRow.gridRows(aspects, 3).map { it.aspects.size })
        assertEquals(listOf(4, 4), ThumbnailRow.gridRows(aspects, 4).map { it.aspects.size })
        // A tablet's wider count changes membership again.
        assertEquals(listOf(6, 2), ThumbnailRow.gridRows(aspects, 6).map { it.aspects.size })
    }

    @Test
    fun grid_rows_row_height_changes_with_membership_per_orientation() {
        // The same 7 clips, two orientations: the 4:3 clip lands in a
        // different ROW when the column count changes, and only the rows
        // that actually CONTAIN a tall clip grow tall.
        val aspects = listOf(
            16f / 9f, 16f / 9f, 16f / 9f, // row 1 at 3 columns
            4f / 3f, 16f / 9f, 16f / 9f, 16f / 9f,
        )
        val portrait = ThumbnailRow.gridRows(aspects, 3)
        assertEquals(listOf(16f / 9f, 16f / 9f, 16f / 9f), portrait[0].aspects)
        assertEquals(16f / 9f, portrait[0].rowAspect, 0.001f)
        assertEquals(4f / 3f, portrait[1].rowAspect, 0.001f)
        val landscape = ThumbnailRow.gridRows(aspects, 4)
        // At 4 columns the 4:3 clip joins the FIRST row and the whole
        // row adopts its height.
        assertEquals(listOf(16f / 9f, 16f / 9f, 16f / 9f, 4f / 3f), landscape[0].aspects)
        assertEquals(4f / 3f, landscape[0].rowAspect, 0.001f)
        assertEquals(16f / 9f, landscape[1].rowAspect, 0.001f)
    }

    @Test
    fun grid_rows_all_wide_rows_keep_their_own_aspect() {
        // Nothing tall in a row: the row keeps the clips' shared aspect —
        // uniform 16:9 content looks exactly like today's grid.
        val rows = ThumbnailRow.gridRows(listOf(16f / 9f, 16f / 9f), 3)
        assertEquals(16f / 9f, rows.single().rowAspect, 0.001f)
    }

    @Test
    fun grid_rows_a_single_portrait_clip_alone_in_a_row_sets_its_height() {
        // A lone clip in the last row: its own aspect sets the row —
        // capped at the 3:4 portrait box (the gridTile contract), with
        // the image letterboxed inside the box.
        val rows = ThumbnailRow.gridRows(listOf(16f / 9f, 16f / 9f, 16f / 9f, 9f / 16f), 3)
        assertEquals(2, rows.size)
        assertEquals(listOf(9f / 16f), rows[1].aspects)
        assertEquals(0.75f, rows[1].rowAspect, 0.001f)
    }

    @Test
    fun grid_rows_cap_a_portrait_row_at_4_to_3_box() {
        // A portrait clip sets its row's height but the box never gets
        // taller than the 3:4 cap (the gridTile contract — a portrait
        // clip cannot take over the screen; its image letterboxes).
        val rows = ThumbnailRow.gridRows(listOf(9f / 16f, 9f / 16f), 3)
        assertEquals(0.75f, rows.single().rowAspect, 0.001f)
    }

    @Test
    fun grid_rows_degenerate_aspects_fall_back_to_16_by_9() {
        // 0 / NaN / infinite never poison a row: they count as the
        // DEFAULT 16:9, the same fallback gridTile applies.
        val rows = ThumbnailRow.gridRows(listOf(0f, Float.NaN, Float.POSITIVE_INFINITY), 3)
        assertEquals(16f / 9f, rows.single().rowAspect, 0.001f)
        // Mixed with a real tall clip the fallback stays honest: a 0
        // aspect clip neither grows nor shrinks the row.
        val mixed = ThumbnailRow.gridRows(listOf(4f / 3f, 0f, Float.NaN), 3)
        assertEquals(4f / 3f, mixed.single().rowAspect, 0.001f)
    }

    @Test
    fun grid_rows_empty_list_and_degenerate_columns() {
        assertEquals(0, ThumbnailRow.gridRows(emptyList(), 3).size)
        // columns < 1 clamps to one clip per row (never a crash).
        val rows = ThumbnailRow.gridRows(listOf(16f / 9f, 4f / 3f), 0)
        assertEquals(2, rows.size)
        assertEquals(listOf(16f / 9f), rows[0].aspects)
    }

    // ---- rowTile: one cell inside a shared-height row ----

    @Test
    fun row_tile_shares_the_row_box_and_letterboxes_the_narrower_clip() {
        // The row's tallest clip (4:3) fills the box exactly. A clip
        // NARROWER than the row keeps the row's height and centers at
        // its own aspect using LESS width — the fraction is clipAspect
        // / rowAspect, no crop, no distortion.
        val tallest = ThumbnailRow.rowTile(4f / 3f, rowAspect = 4f / 3f)
        assertEquals(4f / 3f, tallest.boxAspect, 0.001f)
        assertEquals(1f, tallest.imageWidthFraction, 0.001f)
        val narrower = ThumbnailRow.rowTile(3f / 2f, rowAspect = 2f)
        assertEquals(0.75f, narrower.imageWidthFraction, 0.001f)
    }

    @Test
    fun row_tile_wider_than_row_clips_fills_the_width_and_center_vertically() {
        // A clip WIDER than its row's box (16:9 in a 4:3 row — the
        // owner's mixed scene): the image cannot keep the row height
        // without exceeding the cell's width, so it fills the box's
        // WIDTH at its own aspect and the cell's shared-height box
        // centers it — empty bands above/below INSIDE the box, never a
        // crop, never distortion, never a taller tile (the row stays
        // one height).
        val wide = ThumbnailRow.rowTile(16f / 9f, rowAspect = 4f / 3f)
        assertEquals(4f / 3f, wide.boxAspect, 0.001f)
        assertEquals(1f, wide.imageWidthFraction, 0.001f)
    }

    @Test
    fun row_tile_keeps_the_tallest_clips_image_full_width_in_a_portrait_row() {
        // A portrait clip in a portrait row: box capped at 3:4, image at
        // 9:16 inside it (the same fraction gridTile computes).
        val tile = ThumbnailRow.rowTile(9f / 16f, rowAspect = 0.75f)
        assertEquals(0.75f, tile.boxAspect, 0.001f)
        assertEquals(0.75f, tile.imageWidthFraction, 0.001f)
    }

    @Test
    fun row_tile_degenerate_inputs_fall_back_to_the_defaults() {
        // Clip or row aspect 0/NaN -> the DEFAULT 16:9 (the gridTile
        // fallback semantics kept); a degenerate ROW aspect cannot make
        // the box degenerate either — the box ALWAYS takes the row's
        // (or fallback's) aspect, and only the IMAGE's fraction ever
        // varies.
        assertEquals(4f / 3f, ThumbnailRow.rowTile(0f, 4f / 3f).boxAspect, 0.001f)
        assertEquals(1f, ThumbnailRow.rowTile(0f, 4f / 3f).imageWidthFraction, 0.001f)
        assertEquals(16f / 9f, ThumbnailRow.rowTile(16f / 9f, Float.NaN).boxAspect, 0.001f)
        // A clip WIDER than its row (a stale row aspect) never overflows
        // the box: the fraction clamps at 1.
        assertEquals(1f, ThumbnailRow.rowTile(16f / 9f, 9f / 16f).imageWidthFraction, 0.001f)
    }
}
