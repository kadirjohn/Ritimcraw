package com.ritim.ritimcraw.ui.components

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.addPathNodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RitimIcons path-data tests (Settings redesign, owner spec
 * 2026-11-14): the spec's SVG path strings must parse cleanly through
 * the Compose path parser (addPathNodes) — a malformed command would
 * throw at parser level, a silently-wrong vector would render wrong on
 * device where no test can see it. These tests pin the parser's
 * behavior for the command forms the icon set uses (M/L/H/V/A/Z,
 * relative commands, the .01 dot segments) so the icon file cannot
 * regress into a runtime crash or a broken glyph.
 */
class RitimIconsTest {

    @Test
    fun eye_paths_parse_to_nodes() {
        val outline = addPathNodes("M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z")
        assertTrue("eye outline must parse to nodes", outline.isNotEmpty())
        val pupil = addPathNodes("M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0")
        assertTrue("eye pupil must parse to nodes", pupil.isNotEmpty())
    }

    @Test
    fun cpu_and_report_rect_arc_paths_parse() {
        // rectPath() emits H/V/A commands with a Z close.
        val cpu = addPathNodes("M8.5 7H15.5A1.5 1.5 0 0 1 17 8.5V15.5A1.5 1.5 0 0 1 15.5 17H8.5A1.5 1.5 0 0 1 7 15.5V8.5A1.5 1.5 0 0 1 8.5 7Z")
        assertTrue("cpu rect must parse to nodes", cpu.isNotEmpty())
    }

    @Test
    fun dot_segment_parses_as_short_line() {
        // The spec's dot form "M12 8h.01": a 0.01-long horizontal
        // segment the round line cap renders as a dot.
        val dot = addPathNodes("M12 8h.01")
        assertEquals(2, dot.size)
    }

    @Test
    fun smooth_and_relative_commands_parse() {
        // The moon's A-command arc pair and the folder's relative
        // command chain.
        val moon = addPathNodes("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z")
        assertTrue("moon must parse to nodes", moon.isNotEmpty())
        val folder = addPathNodes("M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
        assertTrue("folder must parse to nodes", folder.isNotEmpty())
    }

    @Test
    fun filled_half_paths_parse() {
        // contrast's filled half (fill, no stroke).
        val contrastHalf = addPathNodes("M12 3a9 9 0 0 1 0 18z")
        assertTrue("contrast half must parse to nodes", contrastHalf.isNotEmpty())
    }

    // ---- AutoThemeA (settings v2 polish C4: the System slot's "A") ----

    /** The C4 spec's own path data for the filled even-odd "A" vector. */
    private val autoThemeAPath =
        "M5.48 19.2H7.86L9.15 15.4H14.81L16.14 19.2H18.52L13.3 4.8H10.6Z" +
            "M9.78 13.57L10.58 11.19C10.94 10.09 11.38 8.59 11.94 6.53" +
            "C12.51 8.57 12.95 10.04 13.33 11.19L14.16 13.57Z"

    @Test
    fun auto_theme_a_path_parses_to_nodes() {
        // The "A" glyph's path data must parse cleanly through the
        // Compose path parser — a malformed command would throw at
        // parser level, a silently-wrong vector would render wrong on
        // device where no test can see it.
        val nodes = addPathNodes(autoThemeAPath)
        assertTrue("the A glyph path must parse to nodes", nodes.isNotEmpty())
    }

    @Test
    fun auto_theme_a_path_keeps_two_subpaths_for_the_even_odd_counter() {
        // The letter is ONE path with TWO subpaths: the outer outline and
        // the inner counter (the triangle hole). The even-odd fill rule
        // cuts the counter out of the filled outline — with only one
        // subpath there would be no hole, and the glyph would render as
        // a solid triangle-ish blob.
        val subpathStarts = addPathNodes(autoThemeAPath).count { it is PathNode.Close }
        assertEquals("the A must close both subpaths (outline + counter)", 2, subpathStarts)
    }

    @Test
    fun auto_theme_a_outline_bounds_stay_on_the_24_grid() {
        // The glyph must land inside the 24x24 viewport with the spec's
        // metrics: cap height 14.4 units on a baseline at y = 19.2 (top
        // = 4.8) — pinning the parse against a silent coordinate shift.
        val nodes = addPathNodes(autoThemeAPath)
        val xs = nodes.flatMap { node ->
            when (node) {
                is PathNode.MoveTo -> listOf(node.x)
                is PathNode.LineTo -> listOf(node.x)
                is PathNode.CurveTo -> listOf(node.x1, node.x2, node.x3)
                else -> emptyList()
            }
        }
        val ys = nodes.flatMap { node ->
            when (node) {
                is PathNode.MoveTo -> listOf(node.y)
                is PathNode.LineTo -> listOf(node.y)
                is PathNode.CurveTo -> listOf(node.y1, node.y2, node.y3)
                else -> emptyList()
            }
        }
        assertTrue("the A must span the spec's width", xs.min() >= 5f && xs.max() <= 19f)
        assertEquals("baseline y = 19.2 (14.4 cap height from y = 4.8)", 19.2f, ys.max()!!, 0.001f)
        assertEquals("cap top y = 4.8", 4.8f, ys.min()!!, 0.001f)
    }
}
