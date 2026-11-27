package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.ui.components.ThumbnailRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's row heights follow the clip's EFFECTIVE orientation: stored
 * frame size + container orientation + manual rotation.
 */
class GridRowOrientationTest {

    private fun clip(
        id: String,
        w: Int = 1920,
        h: Int = 1080,
        container: Int = 0,
        manual: Int = 0,
    ) = MediaSource(
        id = id, uri = "content://$id", fingerprint = id, kind = SourceKind.ENCODED,
        access = Access.OK, width = w, height = h,
        containerOrientationDeg = container, manualRotationDeg = manual,
    )

    private fun aspect(s: MediaSource) = ThumbnailRow.effectiveAspect(
        s.width, s.height, s.containerOrientationDeg, s.manualRotationDeg,
    )

    private fun rows(list: List<MediaSource>, columns: Int = 3) =
        LibraryGridRows.of(list, list.map(::aspect), columns)

    @Test
    fun landscape_clip_turns_with_every_quarter() {
        assertEquals(16f / 9f, ThumbnailRow.effectiveAspect(1920, 1080, 0, 0), 1e-4f)
        assertEquals(9f / 16f, ThumbnailRow.effectiveAspect(1920, 1080, 0, 90), 1e-4f)
        assertEquals(16f / 9f, ThumbnailRow.effectiveAspect(1920, 1080, 0, 180), 1e-4f)
        assertEquals(9f / 16f, ThumbnailRow.effectiveAspect(1920, 1080, 0, 270), 1e-4f)
    }

    @Test
    fun container_orientation_alone_makes_a_stored_landscape_clip_portrait() {
        assertEquals(9f / 16f, ThumbnailRow.effectiveAspect(1920, 1080, 90, 0), 1e-4f)
        assertEquals(16f / 9f, ThumbnailRow.effectiveAspect(1920, 1080, -1, 0), 1e-4f)
    }

    @Test
    fun portrait_clip_rotated_to_landscape() {
        // Stored 1920x1080, container 90 = portrait; a manual 90 turns it back.
        assertEquals(9f / 16f, ThumbnailRow.effectiveAspect(1920, 1080, 90, 0), 1e-4f)
        assertEquals(16f / 9f, ThumbnailRow.effectiveAspect(1920, 1080, 90, 90), 1e-4f)
        assertEquals(16f / 9f, ThumbnailRow.effectiveAspect(1920, 1080, 90, 270), 1e-4f)
        assertEquals(9f / 16f, ThumbnailRow.effectiveAspect(1920, 1080, 90, 180), 1e-4f)
    }

    @Test
    fun landscape_clip_rotated_to_portrait_grows_the_row() {
        val before = rows(listOf(clip("a"), clip("b"), clip("c")))
        val after = rows(listOf(clip("a", manual = 90), clip("b"), clip("c")))
        assertEquals(16f / 9f, before[0].rowAspect, 1e-4f)
        // Capped at 3:4 portrait (GRID_MAX_HEIGHT_RATIO), still taller than before.
        assertEquals(0.75f, after[0].rowAspect, 1e-4f)
    }

    @Test
    fun rotating_one_clip_in_a_row_of_three_moves_only_that_row() {
        val base = (1..6).map { clip("c$it") }
        val rotated = base.mapIndexed { i, c -> if (i == 1) c.copy(manualRotationDeg = 90) else c }
        val a = rows(base)
        val b = rows(rotated)
        assertEquals(2, b.size)
        assertTrue(b[0].rowAspect < a[0].rowAspect)
        assertEquals(a[1].rowAspect, b[1].rowAspect, 0f)
        // Rotating back restores the old height.
        val back = rows(rotated.map { it.copy(manualRotationDeg = 0) })
        assertEquals(a[0].rowAspect, back[0].rowAspect, 0f)
    }

    @Test
    fun container_orientation_change_resizes_the_row() {
        val a = rows(listOf(clip("a"), clip("b"), clip("c")))
        val b = rows(listOf(clip("a", container = 90), clip("b"), clip("c")))
        assertTrue(b[0].rowAspect < a[0].rowAspect)
    }

    @Test
    fun tile_aspect_equals_row_aspect_for_the_tallest_clip() {
        val list = listOf(clip("a", manual = 270, container = 90), clip("b"), clip("c"))
        val row = rows(list)[0]
        val tallest = ThumbnailRow.rowTile(aspect(list[0]), row.rowAspect)
        assertEquals(row.rowAspect, tallest.boxAspect, 0f)
        assertEquals(1f, tallest.imageWidthFraction, 1e-4f)
        assertEquals(row.aspects[0], aspect(list[0]), 1e-4f)
    }

    @Test
    fun legacy_placeholder_rule_is_the_manual_only_case() {
        for (m in listOf(0, 90, 180, 270)) {
            assertEquals(
                ThumbnailRow.placeholderAspect(1920, 1080, m),
                ThumbnailRow.effectiveAspect(1920, 1080, 0, m), 0f,
            )
        }
    }

    @Test
    fun unprobed_orientation_of_a_known_clip_asks_for_a_probe() {
        val legacy = clip("a", container = -1)
        assertTrue(ThumbnailRules.needsThumbnail(legacy, hasFile = true))
        assertFalse(ThumbnailRules.needsThumbnail(clip("a", container = 0), hasFile = true))
    }
}
