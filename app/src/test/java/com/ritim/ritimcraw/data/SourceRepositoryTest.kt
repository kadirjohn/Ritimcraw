package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SourceRepository tests (sprint plan Task 4):
 *  - revoked_uri_marks_source_access_lost: SecurityException during
 *    refreshAccess marks the source Access.LOST without dropping it (QA-23).
 *  - reimport_same_file_keeps_id: same bytes + size -> same id (fingerprint).
 */
class SourceRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- revoked_uri_marks_source_access_lost ----

    @Test
    fun revoked_uri_marks_source_access_lost() {
        val file = projectFile()
        val data = "clip-bytes".toByteArray()
        val resolver = FakeResolver(data)
        val repo = SourceRepository(resolver, file)
        val imported = repo.import("content://clip/1")

        assertEquals(Access.OK, imported.access)

        // Permission revoked: open() now throws SecurityException.
        resolver.revoked = true
        val after = repo.refreshAccess().single()

        assertEquals(Access.LOST, after.access)
        assertEquals(imported.id, after.id) // row kept, never dropped
    }

    @Test
    fun refresh_access_recovers_when_permission_returns() {
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        repo.import("content://clip/1")
        resolver.revoked = true
        repo.refreshAccess()

        resolver.revoked = false
        val after = repo.refreshAccess().single()
        assertEquals(Access.OK, after.access)
    }

    // ---- reimport_same_file_keeps_id ----

    @Test
    fun reimport_same_file_keeps_id() {
        val file = projectFile()
        val data = ByteArray(2_000_000) { (it % 251).toByte() } // > 1 MiB prefix
        val resolver = FakeResolver(data)
        val repo = SourceRepository(resolver, file)

        val first = repo.import("content://clip/first")
        val second = repo.import("content://clip/second")

        assertEquals(first.id, second.id)
        assertEquals(1, repo.all().size)
        // The newer uri wins (document id may differ for the same bytes).
        assertEquals("content://clip/second", repo.all().single().uri)
    }

    @Test
    fun different_files_get_different_ids() {
        val file = projectFile()
        val repo = SourceRepository(FakeResolver(ByteArray(10) { 1 }), file)
        val a = repo.import("content://a")

        val repo2 = SourceRepository(FakeResolver(ByteArray(10) { 2 }), file)
        val b = repo2.import("content://b")

        assertTrue(a.id != b.id)
        assertEquals(2, SourceRepository(FakeResolver(), file).all().size)
    }

    @Test
    fun fingerprint_uses_first_mib_plus_size() {
        val file = projectFile()
        val head = ByteArray(1_048_576) { 7 }
        val tail1 = ByteArray(500) { 1 }
        val tail2 = ByteArray(400) { 2 } // different SIZE, not just different tail
        val repo1 = SourceRepository(FakeResolver(head + tail1), file)
        val repo2 = SourceRepository(FakeResolver(head + tail2), file)

        val a = repo1.import("content://same-head")
        val b = repo2.import("content://same-head-2")

        // Same first MiB but different sizes -> different fingerprints.
        assertTrue(a.id != b.id)
    }

    @Test
    fun write_survives_grades_section_in_same_file() {
        val file = projectFile()
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        val imported = repo.import("content://clip/1")

        GradeStore(file).put(imported.id, GradeK(exposureEv = 1.5f))

        // A sources write (import) must not clobber grades.
        SourceRepository(FakeResolver("clip2".toByteArray()), file).import("content://clip/2")
        assertEquals(1.5f, GradeStore(file).get(imported.id).exposureEv, 0f)
    }

    @Test
    fun clip_stats_persist_across_reload() {
        val file = projectFile()
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        repo.import("content://clip/1")

        // Engine pass fills the stats: write them through a copy and reload.
        val withStats = repo.all().single().copy(
            width = 3840,
            height = 2160,
            frameCount = 310,
            cfa = "RGGB",
        )
        file.writeText(
            file.readText().replace(
                "\"access\":\"OK\"}",
                "\"access\":\"OK\",\"width\":3840,\"height\":2160," +
                    "\"frameCount\":310,\"cfa\":\"RGGB\"}",
            ),
        )
        val reloaded = SourceRepository(FakeResolver(), file).all().single()

        assertEquals(3840, reloaded.width)
        assertEquals(2160, reloaded.height)
        assertEquals(310, reloaded.frameCount)
        assertEquals("RGGB", reloaded.cfa)
        assertTrue(reloaded.hasStats)
        assertEquals(withStats.cfa, reloaded.cfa) // copy kept, reload faithful
    }

    @Test
    fun stats_less_sources_stay_byte_identical() {
        val file = projectFile()
        SourceRepository(FakeResolver("clip".toByteArray()), file).import("content://clip/1")
        val before = file.readText()

        // Re-importing the same file (stats unknown) must not add empty
        // stats keys — schema-1 files stay byte-identical.
        SourceRepository(FakeResolver("clip".toByteArray()), file).import("content://clip/1")
        assertEquals(before, file.readText())
    }

    // ---- display names (owner request 2026-11-11 §2b) ----

    @Test
    fun import_captures_the_display_name() {
        val file = projectFile()
        val resolver = FakeResolver(
            "clip".toByteArray(),
            names = mapOf("content://clip/1" to "Sunset Trip.raw"),
        )
        val imported = SourceRepository(resolver, file).import("content://clip/1")

        assertEquals("Sunset Trip.raw", imported.displayName)
        assertEquals("Sunset Trip.raw", imported.displayOrId)
        // The display name persists in project.json across a reload.
        assertEquals("Sunset Trip.raw", SourceRepository(FakeResolver(), file).all().single().displayName)
    }

    @Test
    fun unresolvable_name_falls_back_to_the_id() {
        val file = projectFile()
        // No names in the resolver: DISPLAY_NAME unknown (null).
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")

        assertEquals("", imported.displayName)
        assertEquals(imported.id, imported.displayOrId) // never a blank
    }

    @Test
    fun reimport_backfills_the_display_name_and_keeps_the_id() {
        val file = projectFile()
        // A record imported before §2b: no display name.
        val old = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/first")
        assertEquals("", old.displayName)

        // Re-import of the same file, now with a name available: the name
        // backfills, the id (and its grade) stays — the fingerprint is
        // untouched.
        val named = FakeResolver(
            "clip".toByteArray(),
            names = mapOf("content://clip/second" to "Night Market.raw"),
        )
        val reimported = SourceRepository(named, file).import("content://clip/second")

        assertEquals(old.id, reimported.id)
        assertEquals("Night Market.raw", reimported.displayName)
        assertEquals("Night Market.raw", reimported.displayOrId)
        assertEquals(1, SourceRepository(FakeResolver(), file).all().size)
    }

    @Test
    fun backfill_display_name_fills_a_pre_s2b_record_on_first_open() {
        val file = projectFile()
        SourceRepository(FakeResolver("clip".toByteArray()), file).import("content://clip/1")
        val id = SourceRepository(FakeResolver(), file).all().single().id

        // First open with a resolver that can now name the document.
        val named = FakeResolver("clip".toByteArray(), names = mapOf("content://clip/1" to "Harbor.raw"))
        val filled = SourceRepository(named, file).backfillDisplayName(id)

        assertEquals("Harbor.raw", filled?.displayName)
        assertEquals("Harbor.raw", filled?.displayOrId)
        // Persisted: a fresh repository sees the name.
        assertEquals("Harbor.raw", SourceRepository(FakeResolver(), file).all().single().displayName)
    }

    @Test
    fun backfill_display_name_is_a_noop_when_already_named_missing_or_unresolvable() {
        // Already named: the record keeps its name (no rewrite, same value).
        val file = projectFile()
        val named = FakeResolver(
            "clip".toByteArray(),
            names = mapOf("content://clip/1" to "One.raw"),
        )
        val repo = SourceRepository(named, file)
        val imported = repo.import("content://clip/1")
        assertEquals(
            imported,
            SourceRepository(named, file).backfillDisplayName(imported.id),
        )

        // Missing record: null, nothing written.
        assertNull(SourceRepository(named, file).backfillDisplayName("no-such-id"))

        // Unresolvable name: the record is returned unchanged (still blank),
        // never a crash — the id stays the display fallback.
        val file2 = File(tmp.root, "project2.json")
        val blank = SourceRepository(FakeResolver("clip".toByteArray()), file2)
            .import("content://clip/1")
        val stillBlank = SourceRepository(FakeResolver("clip".toByteArray()), file2)
            .backfillDisplayName(blank.id)
        assertEquals("", stillBlank?.displayName)
        assertEquals(blank.id, stillBlank?.displayOrId)
    }

    private fun projectFile(): File = File(tmp.root, "project.json")
}
