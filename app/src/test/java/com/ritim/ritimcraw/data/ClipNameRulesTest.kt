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

    // ---- namePart / extensionOf (owner request 2026-11-26: the rename
    // dialog edits the name WITHOUT the extension) ----

    @Test
    fun name_part_splits_the_last_extension() {
        assertEquals("sunset", ClipNameRules.namePart("sunset.mcraw"))
        // Double extensions: only the LAST one is the system's.
        assertEquals("clip.x", ClipNameRules.namePart("clip.x.mcraw"))
        assertEquals("Sunset  Trip", ClipNameRules.namePart("Sunset  Trip.raw"))
    }

    @Test
    fun name_part_keeps_names_without_a_real_extension() {
        // No dot, a bare dot, a trailing dot: nothing to strip.
        assertEquals("clip", ClipNameRules.namePart("clip"))
        assertEquals(".", ClipNameRules.namePart("."))
        assertEquals("clip.", ClipNameRules.namePart("clip."))
        // A name that was ONLY the extension: the part is empty (the
        // dialog edits an empty field; the Unchanged rule still applies).
        assertEquals("", ClipNameRules.namePart(".mcraw"))
    }

    @Test
    fun extension_of_is_lowercase_after_the_last_dot() {
        assertEquals("mcraw", ClipNameRules.extensionOf("sunset.mcraw"))
        assertEquals("mp4", ClipNameRules.extensionOf("clip.X.MP4"))
        assertEquals("", ClipNameRules.extensionOf("clip"))
        assertEquals("", ClipNameRules.extensionOf("clip."))
        assertEquals("", ClipNameRules.extensionOf(""))
    }

    @Test
    fun name_part_is_code_point_safe() {
        // A surrogate pair before the dot survives the split.
        assertEquals("🎥", ClipNameRules.namePart("🎥.mcraw"))
    }

    // ---- validateName / clampName: the dialog's extension-free rules ----

    @Test
    fun validate_name_compares_the_name_part_not_the_whole_name() {
        // The pre-split current name is the NAME PART: retyping the same
        // name is Unchanged (the extension never enters the comparison).
        assertEquals(
            ClipNameRules.Result.Unchanged,
            ClipNameRules.validateName("sunset", "sunset"),
        )
        // The whole current name (with extension) against the part:
        // "sunset" != "sunset.mcraw" — a REAL change (the dialog
        // pre-fills the PART, so this is the honest comparison).
        assertEquals(
            ClipNameRules.Result.Ok("new"),
            ClipNameRules.validateName(" new ", "sunset"),
        )
    }

    @Test
    fun validate_name_counts_length_without_the_extension() {
        // NAME_MAX applies to the NAME the user types — a max-length
        // name plus the extension the system re-appends would exceed
        // NAME_MAX as a whole name, but the rule the dialog runs sees
        // only the name part.
        val max = "a".repeat(ClipNameRules.NAME_MAX)
        assertEquals(ClipNameRules.Result.Ok(max), ClipNameRules.validateName(max, "x"))
        assertEquals(
            ClipNameRules.Result.TooLong,
            ClipNameRules.validateName(max + "a", "x"),
        )
        // Blank still rejects: the record never loses its name.
        assertEquals(ClipNameRules.Result.Blank, ClipNameRules.validateName("  ", "x"))
    }

    @Test
    fun dialog_round_trip_preserves_the_extension_through_the_repository_seam() {
        // The full contract the dialog + repository run: split -> edit ->
        // validate the part -> re-append -> store. A renamed mcraw keeps
        // its extension in the RECORD (the grid and the export name
        // depend on the whole name), while the user never typed it.
        val file = File(tmp.root, "project.json")
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        val imported = repo.import("content://clip/1")
        repo.rename(imported.id, "sunset.mcraw")

        val current = repo.get(imported.id)!!.displayName
        val ext = ClipNameRules.extensionOf(current)
        val part = ClipNameRules.namePart(current)
        val edited = (ClipNameRules.validateName("golden hour", part) as ClipNameRules.Result.Ok).name
        val stored = if (ext.isEmpty()) edited else edited + "." + ext

        assertEquals("golden hour.mcraw", repo.rename(imported.id, stored)?.displayName)
    }
}
