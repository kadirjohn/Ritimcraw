package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The persisted per-clip original file name (owner request 2026-11-26). */
class SourceOriginalNameTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val uri = "content://clip/1"
    private val orig = "261125_194553_VIDEO_24mm.mcraw"

    private fun file() = File(tmp.root, "project.json")

    @Test
    fun import_writes_the_original_name_once_and_renames_keep_it() {
        val resolver = FakeResolver("clip".toByteArray(), names = mapOf(uri to orig))
        val repo = SourceRepository(resolver, file())
        val imported = repo.import(uri)
        assertEquals(orig, imported.originalName)

        repo.rename(imported.id, "Sunset")
        assertEquals(orig, repo.get(imported.id)?.originalName)
        repo.rename(imported.id, "Sunset two")
        assertEquals(orig, repo.get(imported.id)?.originalName)
        assertEquals("Sunset two", repo.get(imported.id)?.displayName)

        // Two file renames in a row also keep it (the resolver now reports new names).
        resolver.names = mapOf("content://clip/2" to "Sunset two.mcraw")
        repo.applyFileRename(imported.id, "content://clip/2", "Sunset two")
        resolver.names = mapOf("content://clip/3" to "Third.mcraw")
        repo.applyFileRename(imported.id, "content://clip/3", "Third")
        assertEquals(orig, repo.get(imported.id)?.originalName)
    }

    @Test
    fun reimport_does_not_overwrite_the_original_name() {
        val resolver = FakeResolver("clip".toByteArray(), names = mapOf(uri to orig))
        val repo = SourceRepository(resolver, file())
        val id = repo.import(uri).id
        resolver.names = mapOf(uri to "Renamed.mcraw")
        assertEquals(orig, repo.import(uri).originalName)
        assertEquals(id, repo.get(id)?.id)
    }

    @Test
    fun old_project_backfills_when_app_name_equals_file_name() {
        val f = file()
        f.writeText(
            """{"version":1,"sources":[{"id":"aa","uri":"$uri","fingerprint":"fp","kind":"MCRAW","access":"OK","displayName":"$orig"}]}""",
        )
        val repo = SourceRepository(FakeResolver(names = mapOf(uri to orig)), f)
        assertEquals("", repo.get("aa")?.originalName)
        assertEquals(orig, repo.backfillOriginalName("aa")?.originalName)
        assertEquals(orig, repo.get("aa")?.originalName)
    }

    @Test
    fun old_project_backfills_when_app_name_is_blank() {
        val f = file()
        f.writeText(
            """{"version":1,"sources":[{"id":"aa","uri":"$uri","fingerprint":"fp","kind":"MCRAW","access":"OK"}]}""",
        )
        val repo = SourceRepository(FakeResolver(names = mapOf(uri to orig)), f)
        assertEquals(orig, repo.backfillOriginalName("aa")?.originalName)
    }

    @Test
    fun old_project_stays_empty_when_app_name_differs() {
        val f = file()
        f.writeText(
            """{"version":1,"sources":[{"id":"aa","uri":"$uri","fingerprint":"fp","kind":"MCRAW","access":"OK","displayName":"My name"}]}""",
        )
        val repo = SourceRepository(FakeResolver(names = mapOf(uri to orig)), f)
        assertEquals("", repo.backfillOriginalName("aa")?.originalName)
        assertEquals("", repo.get("aa")?.originalName)
    }

    @Test
    fun backfill_never_overwrites_an_existing_value() {
        val resolver = FakeResolver("clip".toByteArray(), names = mapOf(uri to orig))
        val repo = SourceRepository(resolver, file())
        val id = repo.import(uri).id
        resolver.names = mapOf(uri to "other.mcraw")
        assertEquals(orig, repo.backfillOriginalName(id)?.originalName)
    }

    @Test
    fun json_omits_the_field_when_absent_and_round_trips_when_set() {
        val bare = MediaSource("a", "u", "f", SourceKind.MCRAW, Access.OK)
        assertFalse(SourceRepository.renderSourcesArray(listOf(bare)).contains("originalName"))
        val named = bare.copy(originalName = orig)
        val text = SourceRepository.renderSourcesArray(listOf(named))
        assertTrue(text.contains("\"originalName\":\"$orig\""))
        val parsed = SourceRepository.parseSources2("""{"sources":$text}""")
        assertEquals(orig, parsed.single().originalName)
    }

    @Test
    fun file_rename_keeps_orientation_and_original_name_for_the_import_time_gcsv_match() {
        val resolver = FakeResolver("clip".toByteArray(), names = mapOf(uri to orig))
        val repo = SourceRepository(resolver, file())
        val imported = repo.import(uri)
        // The import-time gcsv lookup matches by the name as imported.
        val matchName = imported.displayName
        assertEquals(orig, matchName)

        // Orientation facts stored at/after import (gcsv gravity, container).
        repo.setManualRotation(imported.id, 90)
        repo.backfillStats(imported.id, 1920, 1080, 10, "RGGB", 1_000_000L, 270)

        // Later file rename moves the uri/name but nothing else.
        resolver.names = mapOf("content://clip/2" to "Sunset.mcraw")
        repo.applyFileRename(imported.id, "content://clip/2", "Sunset")

        val after = repo.get(imported.id)!!
        assertEquals("content://clip/2", after.uri)
        assertEquals("Sunset", after.displayName)
        assertEquals(orig, after.originalName)
        assertEquals(90, after.manualRotationDeg)
        assertEquals(270, after.containerOrientationDeg)
        assertEquals(imported.id, after.id)
        assertEquals(orig, matchName)
    }
}
