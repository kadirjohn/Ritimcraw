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

    // ---- duration (owner request 2026-11-11 section 1) ----

    @Test
    fun duration_persists_across_reload_and_hides_when_zero() {
        val file = projectFile()
        SourceRepository(FakeResolver("clip".toByteArray()), file).import("content://clip/1")

        // The generation pass probed a 12.5 s recorded timeline.
        file.writeText(
            file.readText().replace(
                "\"access\":\"OK\"}",
                "\"access\":\"OK\",\"durationUs\":12500000}",
            ),
        )
        val reloaded = SourceRepository(FakeResolver(), file).all().single()

        assertEquals(12_500_000L, reloaded.durationUs)
        assertTrue(reloaded.hasStats) // duration alone shows a format line
    }

    @Test
    fun duration_omitted_keeps_schema1_files_byte_identical() {
        val file = projectFile()
        SourceRepository(FakeResolver("clip".toByteArray()), file).import("content://clip/1")
        val before = file.readText()

        // A backfill with NO new values (all unknown) must not rewrite a
        // duration key into the file — schema-1 files stay byte-identical.
        SourceRepository(FakeResolver("clip".toByteArray()), file).backfillStats(
            id = SourceRepository(FakeResolver(), file).all().single().id,
            width = 0, height = 0, frameCount = 0, cfa = "", durationUs = 0L,
        )
        assertEquals(before, file.readText())
    }

    // ---- backfillStats (the generation pass's write path) ----

    @Test
    fun backfill_stats_fills_a_probed_record() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")

        // The pass probed an open-gate clip: 4000x3000, 310 frames,
        // 12.9 s of recorded timeline.
        val filled = SourceRepository(FakeResolver("clip".toByteArray()), file).backfillStats(
            id = imported.id,
            width = 4000, height = 3000, frameCount = 310, cfa = "RGGB",
            durationUs = 12_900_000L,
        )

        assertEquals(4000, filled?.width)
        assertEquals(3000, filled?.height)
        assertEquals(310, filled?.frameCount)
        assertEquals("RGGB", filled?.cfa)
        assertEquals(12_900_000L, filled?.durationUs)
        // Persisted: a fresh repository sees the recorded stats.
        val reloaded = SourceRepository(FakeResolver(), file).all().single()
        assertEquals(4000, reloaded.width)
        assertEquals(3000, reloaded.height)
        assertEquals(12_900_000L, reloaded.durationUs)
    }

    @Test
    fun backfill_stats_never_overwrites_filled_values_with_unknowns() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")
        SourceRepository(FakeResolver("clip".toByteArray()), file).backfillStats(
            imported.id, 4000, 3000, 310, "RGGB", 12_900_000L,
        )

        // A re-probe that failed to read some values (0/blank) must keep
        // the record's filled stats — never clobber them with unknowns.
        val kept = SourceRepository(FakeResolver("clip".toByteArray()), file).backfillStats(
            imported.id, 0, 0, 0, "", 0L,
        )
        assertEquals(4000, kept?.width)
        assertEquals(3000, kept?.height)
        assertEquals(310, kept?.frameCount)
        assertEquals("RGGB", kept?.cfa)
        assertEquals(12_900_000L, kept?.durationUs)
    }

    @Test
    fun backfill_stats_is_a_noop_for_a_missing_record() {
        val file = projectFile()
        assertNull(
            SourceRepository(FakeResolver("clip".toByteArray()), file)
                .backfillStats("no-such-id", 4000, 3000, 310, "RGGB", 12_900_000L),
        )
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

    // ---- manual rotation (owner request 2026-11-11 section 5) ----

    @Test
    fun set_manual_rotation_persists_across_reload_and_normalizes() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")

        // One tap on "Rotate 90° left": the delta is normalized (-90 ->
        // 270) BEFORE it is stored, so the record never holds a negative
        // or out-of-range value.
        val updated = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setManualRotation(imported.id, -90)

        assertEquals(270, updated?.manualRotationDeg)
        // Persisted: a fresh repository (and a fresh process) sees it.
        assertEquals(270, SourceRepository(FakeResolver(), file).all().single().manualRotationDeg)
        // The rotation key is written only when non-default.
        assertTrue(file.readText().contains("\"manualRotationDeg\":270"))
    }

    @Test
    fun set_manual_rotation_zero_omits_the_key_and_keeps_schema1_byte_identical() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")
        val before = file.readText()

        // Four quarter turns return to the default: storing 0 writes no
        // rotation key — pre-rotation files stay byte-identical.
        SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setManualRotation(imported.id, 0)

        assertEquals(before, file.readText())
        assertEquals(0, SourceRepository(FakeResolver(), file).all().single().manualRotationDeg)
    }

    @Test
    fun set_manual_rotation_is_a_noop_for_a_missing_record() {
        val file = projectFile()
        assertNull(
            SourceRepository(FakeResolver("clip".toByteArray()), file)
                .setManualRotation("no-such-id", 90),
        )
    }

    private fun projectFile(): File = File(tmp.root, "project.json")
}
