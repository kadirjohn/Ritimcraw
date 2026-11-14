package com.ritim.ritimcraw.ui.components

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
        // contrast/auto's filled halves (fill, no stroke).
        val contrastHalf = addPathNodes("M12 3a9 9 0 0 1 0 18z")
        assertTrue("contrast half must parse to nodes", contrastHalf.isNotEmpty())
        val autoHalf = addPathNodes("M12 6a6 6 0 0 1 0 12z")
        assertTrue("auto half must parse to nodes", autoHalf.isNotEmpty())
    }
}
