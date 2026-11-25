package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ClipNameRulesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun blank_and_whitespace_only_are_rejected() {
        assertEquals(ClipNameRules.Result.Blank, ClipNameRules.validate("", "a.mcraw"))
        assertEquals(ClipNameRules.Result.Blank, ClipNameRules.validate("  \t ", "a.mcraw"))
        assertEquals(ClipNameRules.Result.Blank, ClipNameRules.validate("\n\r", "a.mcraw"))
    }

    @Test
    fun unchanged_after_trim_is_rejected() {
        assertEquals(ClipNameRules.Result.Unchanged, ClipNameRules.validate("a.mcraw", "a.mcraw"))
        assertEquals(ClipNameRules.Result.Unchanged, ClipNameRules.validate("  a.mcraw ", "a.mcraw"))
    }

    @Test
    fun trims_edges_keeps_inner_spacing_and_extension() {
        assertEquals(
            ClipNameRules.Result.Ok("my  clip.mcraw"),
            ClipNameRules.validate("  my  clip.mcraw  ", "a.mcraw"),
        )
    }

    @Test
    fun line_breaks_are_dropped() {
        assertEquals(ClipNameRules.Result.Ok("ab"), ClipNameRules.validate("a\nb\r", "x"))
    }

    @Test
    fun length_limit_counts_code_points() {
        val max = "a".repeat(ClipNameRules.NAME_MAX)
        assertEquals(ClipNameRules.Result.Ok(max), ClipNameRules.validate(max, "x"))
        assertEquals(ClipNameRules.Result.TooLong, ClipNameRules.validate(max + "a", "x"))
        val emoji = "🎥".repeat(ClipNameRules.NAME_MAX)
        assertTrue(ClipNameRules.validate(emoji, "x") is ClipNameRules.Result.Ok)
        val clamped = ClipNameRules.clamp(emoji + emoji)
        assertEquals(ClipNameRules.NAME_MAX, clamped.codePointCount(0, clamped.length))
    }

    @Test
    fun rename_keeps_id_grade_and_only_changes_display_name() {
        val file = File(tmp.root, "project.json")
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        val grades = GradeStore(file)
        val imported = repo.import("content://clip/1")
        grades.put(imported.id, GradeK(exposureEv = 1.5f))

        val renamed = repo.rename(imported.id, "  new name.mcraw ")!!

        assertEquals(imported.id, renamed.id)
        // Only displayName differs: uri/fingerprint/gyro (the file link)
        // are untouched, so no source file is ever renamed or rewritten.
        assertEquals(imported.copy(displayName = "new name.mcraw"), renamed)
        assertEquals(1.5f, grades.get(imported.id).exposureEv, 0f)
        // Reversible by renaming again.
        assertEquals("first", repo.rename(imported.id, "first")?.displayName)
        assertEquals(imported.id, repo.all().single().id)
    }
}
