package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The gyro auto-attach matching rule (owner request 2026-11-12, G1):
 * exact base-name equality (extension stripped, case-insensitive); one
 * hit attaches, zero attach nothing, two or more defer to the user.
 * The rule is deliberately exact-or-nothing — see [GyroMatcher].
 */
class GyroMatcherTest {

    // ---- exact match ----

    @Test
    fun same_base_name_matches() {
        // The owner's real MotionCam pair shape.
        val m = GyroMatcher.match(
            "261109_172410_VIDEO_99mm.mcraw",
            listOf("261109_172410_VIDEO_99mm.gcsv"),
        )
        assertEquals(GyroMatcher.Match.One("261109_172410_VIDEO_99mm.gcsv"), m)
    }

    @Test
    fun one_match_among_unrelated_logs() {
        val m = GyroMatcher.match(
            "clip_b.mcraw",
            listOf("other.gcsv", "clip_b.gcsv", "third.gcsv"),
        )
        assertEquals(GyroMatcher.Match.One("clip_b.gcsv"), m)
    }

    @Test
    fun match_keeps_the_candidate_as_given() {
        // The caller imports by this string, so the match must not
        // normalize its case or extension.
        val m = GyroMatcher.match("clip.GCSV", listOf("clip.gcsv"))
        assertEquals(GyroMatcher.Match.One("clip.gcsv"), m)
    }

    // ---- case-insensitivity ----

    @Test
    fun matching_is_case_insensitive() {
        val m = GyroMatcher.match("CLIP_B.MCRAW", listOf("clip_b.GCSV"))
        assertEquals(GyroMatcher.Match.One("clip_b.GCSV"), m)
    }

    // ---- extension handling ----

    @Test
    fun extension_is_stripped_before_comparing() {
        val m = GyroMatcher.match("clip_b.mp4", listOf("clip_b.gcsv"))
        assertEquals(GyroMatcher.Match.One("clip_b.gcsv"), m)
    }

    @Test
    fun non_gcsv_candidates_are_ignored() {
        // A mixed listing can never attach a non-log file.
        val m = GyroMatcher.match(
            "clip_b.mcraw",
            listOf("clip_b.txt", "clip_b.csv", "clip_b.gcsv_bak"),
        )
        assertEquals(GyroMatcher.Match.None, m)
    }

    @Test
    fun uppercase_gcsv_extension_still_counts_as_a_log() {
        val m = GyroMatcher.match("clip_b.mcraw", listOf("clip_b.GCSV"))
        assertEquals(GyroMatcher.Match.One("clip_b.GCSV"), m)
    }

    // ---- zero-match -> None ----

    @Test
    fun no_matching_log_is_none() {
        val m = GyroMatcher.match(
            "clip_b.mcraw",
            listOf("other.gcsv", "another.gcsv"),
        )
        assertEquals(GyroMatcher.Match.None, m)
    }

    @Test
    fun empty_candidate_list_is_none() {
        assertEquals(GyroMatcher.Match.None, GyroMatcher.match("clip_b.mcraw", emptyList()))
    }

    @Test
    fun blank_video_name_is_none() {
        // No display name (resolver could not name the clip): never
        // match — attaching by a blank name would be a guess.
        assertEquals(GyroMatcher.Match.None, GyroMatcher.match("", listOf(".gcsv", "b.gcsv")))
    }

    // ---- multi-match -> Ambiguous ----

    @Test
    fun two_hits_are_ambiguous_not_a_guess() {
        // Two logs whose bases collide case-insensitively (Android
        // filenames are case-sensitive, so both can sit in the granted
        // folder): the user picks; the matcher never guesses between
        // them.
        val m = GyroMatcher.match(
            "clip_b.mcraw",
            listOf("clip_b.gcsv", "CLIP_B.GCSV"),
        )
        assertEquals(
            GyroMatcher.Match.Ambiguous(listOf("clip_b.gcsv", "CLIP_B.GCSV")),
            m,
        )
    }

    @Test
    fun duplicate_candidates_dedupe_to_one() {
        // The query layer never produces duplicate display names (names
        // are unique within a folder), but the pure rule stays total:
        // the same name twice is ONE log, not an ambiguity with itself.
        val m = GyroMatcher.match(
            "clip_b.mcraw",
            listOf("x.gcsv", "clip_b.gcsv", "y.gcsv", "clip_b.gcsv"),
        )
        assertEquals(GyroMatcher.Match.One("clip_b.gcsv"), m)
    }

    // ---- names with dots and spaces ----

    @Test
    fun dotted_base_name_matches() {
        // Only the LAST dot splits the extension.
        val m = GyroMatcher.match("clip.2.b.mcraw", listOf("clip.2.b.gcsv"))
        assertEquals(GyroMatcher.Match.One("clip.2.b.gcsv"), m)
    }

    @Test
    fun dotted_base_name_does_not_match_a_shorter_base() {
        val m = GyroMatcher.match("clip.2.mcraw", listOf("clip.gcsv"))
        assertEquals(GyroMatcher.Match.None, m)
    }

    @Test
    fun spaced_names_match() {
        val m = GyroMatcher.match("my clip b.mcraw", listOf("my clip b.gcsv"))
        assertEquals(GyroMatcher.Match.One("my clip b.gcsv"), m)
    }

    @Test
    fun leading_dot_name_has_no_extension_to_strip() {
        // ".gcsv" is a hidden file named "gcsv"-less base; it must not
        // match a video named ".mcraw" by base-name luck.
        assertEquals(GyroMatcher.Match.None, GyroMatcher.match(".mcraw", listOf(".gcsv")))
    }

    @Test
    fun no_extension_names_match_by_whole_name() {
        // MotionCam names always carry extensions, but the rule stays
        // total: an extension-less pair still matches.
        val m = GyroMatcher.match("clip_b", listOf("clip_b.gcsv"))
        assertEquals(GyroMatcher.Match.One("clip_b.gcsv"), m)
    }
}
