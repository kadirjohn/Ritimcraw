package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * SourceRepository tests (sprint plan Task 4, spec IN-12/IN-16):
 *  - revoked_uri_marks_source_access_lost: SecurityException during the
 *    refresh pass marks the source Access.LOST without dropping it (QA-23).
 *  - gone_uri_marks_source_access_gone: a deleted/moved document marks
 *    the source Access.GONE — distinct from a revoked permission.
 *  - relink: a fingerprint match rebinds the uri (same id, grade kept);
 *    a mismatch is rejected and changes nothing.
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

        // Permission revoked: the provider now answers SecurityException.
        resolver.revoked = true
        val after = repo.refreshStatus().single()

        assertEquals(Access.LOST, after.access)
        assertEquals(imported.id, after.id) // row kept, never dropped
    }

    @Test
    fun refresh_status_recovers_when_permission_returns() {
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        repo.import("content://clip/1")
        resolver.revoked = true
        repo.refreshStatus()

        resolver.revoked = false
        val after = repo.refreshStatus().single()
        assertEquals(Access.OK, after.access)
    }

    // ---- gone marking (spec IN-12: deleted/moved clips leave the ghost) ----

    @Test
    fun gone_uri_marks_source_access_gone_distinct_from_lost() {
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        val imported = repo.import("content://clip/1")

        // The document was deleted from the provider: the probe answers
        // GONE (no permission cause) — a DIFFERENT state from LOST, because
        // the recovery differs (re-link vs re-grant).
        resolver.gone = setOf("content://clip/1")
        val after = repo.refreshStatus().single()

        assertEquals(Access.GONE, after.access)
        assertEquals(imported.id, after.id) // row kept for re-link/remove
    }

    @Test
    fun gone_status_persists_across_reload_and_old_values_parse() {
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        val imported = repo.import("content://clip/1")
        resolver.gone = setOf("content://clip/1")
        repo.refreshStatus()

        // The GONE status survives a reload (a fresh process).
        assertEquals(Access.GONE, SourceRepository(FakeResolver(), file).all().single().access)

        // An unknown access value (an older build meeting "GONE") parses
        // through the default — OK, never a crash.
        resolver.gone = emptySet()
        file.writeText(
            file.readText().replace("\"access\":\"GONE\"", "\"access\":\"WEIRD-FUTURE\""),
        )
        assertEquals(Access.OK, SourceRepository(FakeResolver(), file).all().first().access)
        assertEquals(imported.id, SourceRepository(FakeResolver(), file).all().first().id)
    }

    @Test
    fun refresh_status_recovers_when_the_document_returns() {
        // The SSD was unplugged (GONE) and plugged back in: the next pass
        // flips the row back to OK on its own — no user action needed.
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        repo.import("content://clip/1")
        resolver.gone = setOf("content://clip/1")
        repo.refreshStatus()
        assertEquals(Access.GONE, repo.all().single().access)

        resolver.gone = emptySet()
        val after = repo.refreshStatus().single()
        assertEquals(Access.OK, after.access)
    }

    @Test
    fun refresh_status_probes_only_the_requested_ids() {
        // The bounded per-open pass names its slice; records outside it
        // keep their recorded status (a big library pays only the batch).
        // Distinct blobs so the two imports make two records.
        val file = projectFile()
        val resolver = FakeResolver(
            blobs = mapOf(
                "content://clip/a" to "clip-a".toByteArray(),
                "content://clip/b" to "clip-b".toByteArray(),
            ),
        )
        val repo = SourceRepository(resolver, file)
        val a = repo.import("content://clip/a")
        val b = repo.import("content://clip/b")
        assertEquals(2, repo.all().size)
        resolver.gone = setOf("content://clip/a", "content://clip/b")

        val probed = repo.refreshStatus(ids = listOf(a.id))
        assertEquals(listOf(a.id), probed.map { it.id })
        assertEquals(Access.GONE, probed.single().access)
        assertEquals(Access.OK, repo.all().first { it.id == b.id }.access)
    }

    @Test
    fun refresh_status_no_change_writes_nothing() {
        // A pass where every probe agrees with the record must not rewrite
        // project.json (the no-op guard; byte-identical file).
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        repo.import("content://clip/1")
        val before = file.readText()

        repo.refreshStatus()

        assertEquals(before, file.readText())
    }

    // ---- relink (spec IN-16: solve the missing media from identity) ----

    @Test
    fun relink_rebinds_the_uri_on_a_fingerprint_match_and_keeps_the_id() {
        val file = projectFile()
        val resolver = FakeResolver("clip".toByteArray())
        val repo = SourceRepository(resolver, file)
        val imported = repo.import("content://clip/old-location")
        // The clip carried a grade and stats from its first import.
        GradeStore(file).put(imported.id, GradeK(exposureEv = 1.5f))
        repo.backfillStats(imported.id, 3840, 2160, 310, "RGGB", 12_500_000L)
        // Then its document was moved: the refresh marked it GONE.
        resolver.gone = setOf("content://clip/old-location")
        repo.refreshStatus()
        assertEquals(Access.GONE, repo.all().single().access)

        // The user re-links to the SAME bytes at the new location.
        resolver.gone = emptySet()
        resolver.names = mapOf("content://clip/new-location" to "Sunset.raw")
        val relinked = repo.relink(imported.id, "content://clip/new-location")

        assertEquals(imported.id, relinked.id) // SAME source: the grade's anchor
        assertEquals("content://clip/new-location", relinked.uri)
        assertEquals(Access.OK, relinked.access)
        // The grade, stats and display name all survive the rebind.
        assertEquals(1.5f, GradeStore(file).get(imported.id).exposureEv, 0f)
        assertEquals(3840, relinked.width)
        assertEquals(12_500_000L, relinked.durationUs)
        assertEquals("Sunset.raw", relinked.displayName)
        // Persisted: a fresh process sees the rebound uri + OK.
        val reloaded = SourceRepository(FakeResolver(), file).all().single()
        assertEquals("content://clip/new-location", reloaded.uri)
        assertEquals(Access.OK, reloaded.access)
    }

    @Test
    fun relink_mismatch_is_rejected_and_changes_nothing() {
        val file = projectFile()
        val resolver = FakeResolver("clip-bytes".toByteArray())
        val repo = SourceRepository(resolver, file)
        val imported = repo.import("content://clip/1")
        resolver.gone = setOf("content://clip/1")
        repo.refreshStatus()

        // The user picked a DIFFERENT file: identity (size + first-MiB
        // hash) does not match — the catalog must never silently rebind.
        resolver.blobs = mapOf("content://other/file" to "other-clip-entirely".toByteArray())
        val threw = try {
            repo.relink(imported.id, "content://other/file")
            false
        } catch (e: RelinkMismatchException) {
            // The mismatch names both fingerprints: honest evidence.
            assertEquals(imported.fingerprint, e.expectedFingerprint)
            assertTrue(e.actualFingerprint.isNotBlank())
            true
        }
        assertTrue(threw)

        // Nothing changed: still the old uri, still GONE, still one row.
        val after = repo.all().single()
        assertEquals("content://clip/1", after.uri)
        assertEquals(Access.GONE, after.access)
    }

    @Test
    fun relink_missing_record_throws_and_writes_nothing() {
        val file = projectFile()
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        repo.import("content://clip/1")
        val before = file.readText()

        val threw = try {
            repo.relink("no-such-id", "content://clip/1")
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue(threw)
        assertEquals(before, file.readText())
    }

    @Test
    fun relink_uses_the_same_identity_as_import_dedupe() {
        // The fingerprint contract is SHARED: a file that re-imports onto
        // the same record (dedupe) must also relink onto it. Same bytes at
        // a moved location = same fingerprint = a valid re-link target.
        val file = projectFile()
        val data = ByteArray(2_000_000) { (it % 251).toByte() } // > 1 MiB prefix
        val repo = SourceRepository(FakeResolver(data), file)
        val imported = repo.import("content://clip/first")
        val reimported = repo.import("content://clip/second")

        assertEquals(imported.id, reimported.id)
        val relinked = repo.relink(imported.id, "content://clip/third")
        assertEquals(imported.id, relinked.id)
        assertEquals("content://clip/third", relinked.uri)
    }

    // ---- collection refresh cadence (spec IN-12: bounded per-open pass) ----

    @Test
    fun refresh_batch_probes_every_degraded_row_and_a_rotating_ok_slice() {
        // Degraded rows are the pass's purpose: EVERY open probes them.
        // OK rows rotate in slices so a big library never pays a full sweep.
        val degraded = listOf(
            source(id = "gone", access = Access.GONE),
            source(id = "lost", access = Access.LOST),
        )
        val ok = (0 until 7).map { source(id = "ok$it") }

        val first = CollectionRefresh.batch(degraded + ok, cursor = 0)
        assertEquals(setOf("gone", "lost", "ok0", "ok1", "ok2", "ok3", "ok4"), first.toSet())
        assertEquals(7, first.size) // 2 degraded + the 5-record OK slice

        // The cursor advances by the probed OK count; the next open probes
        // the NEXT slice, not the same head again.
        val next = CollectionRefresh.nextCursor(ok, 0)
        assertEquals(5, next)
        val second = CollectionRefresh.batch(degraded + ok, cursor = next)
        assertTrue("ok5" in second)
        assertTrue("ok6" in second)
        assertTrue("ok0" in second) // wraps: 5,6,0,1,2
        assertTrue("gone" in second) // degraded rows every time
        assertTrue("lost" in second)
    }

    @Test
    fun refresh_batch_wraps_and_covers_every_record_over_sweeps() {
        // Rotation guarantee: ceil(n / OK_BATCH) consecutive opens probe
        // every OK record at least once.
        val ok = (0 until 12).map { source(id = "ok$it") }
        val seen = mutableSetOf<String>()
        var cursor = 0
        repeat(3) { // ceil(12 / 5) = 3 opens
            seen += CollectionRefresh.batch(ok, cursor)
            cursor = CollectionRefresh.nextCursor(ok, cursor)
        }
        assertEquals(ok.map { it.id }.toSet(), seen)
    }

    @Test
    fun refresh_batch_handles_empty_degraded_empty_ok_and_zero_limit() {
        // No OK records: only degraded rows (possibly none at all).
        assertEquals(
            emptyList<String>(),
            CollectionRefresh.batch(emptyList(), cursor = 0),
        )
        assertEquals(
            listOf("lost"),
            CollectionRefresh.batch(listOf(source(id = "lost", access = Access.LOST)), cursor = 3),
        )
        // okBatch = 0 disables the OK rotation: degraded rows only.
        val mixed = listOf(
            source(id = "gone", access = Access.GONE),
            source(id = "ok"),
        )
        assertEquals(
            listOf("gone"),
            CollectionRefresh.batch(mixed, cursor = 0, okBatch = 0),
        )
        // Negative cursors normalize (rememberSaveable state is arbitrary):
        // -1 on a 7-record list starts the slice at index 6.
        assertEquals(
            setOf("ok6", "ok0", "ok1", "ok2", "ok3"),
            CollectionRefresh.batch(
                (0 until 7).map { source(id = "ok$it") },
                cursor = -1,
            ).toSet(),
        )
    }

    @Test
    fun next_cursor_returns_zero_without_ok_records() {
        assertEquals(0, CollectionRefresh.nextCursor(emptyList(), cursor = 4))
        assertEquals(
            0,
            CollectionRefresh.nextCursor(listOf(source(id = "gone", access = Access.GONE)), cursor = 4),
        )
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

    // ---- gyro attachment (GYRO PHASE 5) ----

    @Test
    fun set_gyro_persists_across_reload() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")

        // The editor attached a validated sidecar log (a GyroStore id).
        val updated = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setGyro(imported.id, "0123456789abcdef")

        assertEquals("0123456789abcdef", updated?.gyroId)
        // Persisted: a fresh repository (and a fresh process) sees it.
        assertEquals(
            "0123456789abcdef",
            SourceRepository(FakeResolver(), file).all().single().gyroId,
        )
        // The gyro key is written only when non-default.
        assertTrue(file.readText().contains("\"gyroId\":\"0123456789abcdef\""))
    }

    @Test
    fun set_gyro_blank_detaches_and_omits_the_key() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")
        SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setGyro(imported.id, "0123456789abcdef")

        // Detach: storing "" writes no gyro key — pre-gyro files stay
        // byte-identical.
        val reloaded = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setGyro(imported.id, "")

        assertEquals("", reloaded?.gyroId)
        assertTrue(!file.readText().contains("\"gyroId\""))
    }

    @Test
    fun gyro_id_absent_records_parse_as_blank() {
        val file = projectFile()
        SourceRepository(FakeResolver("clip".toByteArray()), file).import("content://clip/1")

        // Records written before GYRO PHASE 5 have no gyroId key: they
        // parse as blank (no attachment), never as a crash.
        val reloaded = SourceRepository(FakeResolver(), file).all().single()
        assertEquals("", reloaded.gyroId)
    }

    @Test
    fun set_gyro_skips_noop_writes_and_is_null_for_a_missing_record() {
        val file = projectFile()
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")
        SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setGyro(imported.id, "0123456789abcdef")
        val before = file.readText()

        // Re-attaching the SAME log must not rewrite (the no-op guard).
        SourceRepository(FakeResolver("clip".toByteArray()), file)
            .setGyro(imported.id, "0123456789abcdef")
        assertEquals(before, file.readText())

        // Missing record: null, nothing written (the editor never attaches
        // a gyro to a phantom clip).
        assertNull(
            SourceRepository(FakeResolver("clip".toByteArray()), file)
                .setGyro("no-such-id", "0123456789abcdef"),
        )
    }

    // ---- gyro auto-match on import (owner request 2026-11-12, G1) ----

    @Test
    fun import_leaves_gyro_id_blank_until_an_attach_sets_it() {
        val file = projectFile()
        // The auto-match attaches AFTER import (AppNav's autoMatchGyro
        // runs on the imported record): a fresh import must carry no
        // attachment — the store never invents one.
        val imported = SourceRepository(FakeResolver("clip".toByteArray()), file)
            .import("content://clip/1")

        assertEquals("", imported.gyroId)
        assertEquals("", SourceRepository(FakeResolver(), file).all().single().gyroId)
    }

    @Test
    fun auto_attach_shape_persists_across_reload() {
        // The full auto-attach store shape: import (the video), then the
        // attach that AppNav's autoMatchGyro performs after a successful
        // probe (setGyro with a GyroStore id) — the attachment must
        // survive a reload exactly like the editor's manual attach.
        val file = projectFile()
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        val imported = repo.import("content://clip/1")

        val updated = repo.setGyro(imported.id, "0123456789abcdef")

        assertEquals("0123456789abcdef", updated?.gyroId)
        assertEquals(
            "0123456789abcdef",
            SourceRepository(FakeResolver(), file).all().single().gyroId,
        )
    }

    @Test
    fun reimport_preserves_an_auto_attached_gyro_id() {
        // The auto-attach runs after EVERY import; the same clip picked
        // again (fingerprint dedupe) hits the existing-record branch,
        // which must keep the attachment rather than reset it.
        val file = projectFile()
        val repo = SourceRepository(FakeResolver("clip".toByteArray()), file)
        val imported = repo.import("content://clip/1")
        repo.setGyro(imported.id, "0123456789abcdef")

        val reimported = repo.import("content://clip/1")

        assertEquals(imported.id, reimported.id)
        assertEquals("0123456789abcdef", reimported.gyroId)
    }

    private fun projectFile(): File = File(tmp.root, "project.json")

    /** A minimal record for the cadence tests (only id + status matter). */
    private fun source(id: String, access: Access = Access.OK) = MediaSource(
        id = id,
        uri = "content://clip/$id",
        fingerprint = "fp-$id",
        kind = SourceKind.MCRAW,
        access = access,
    )
}
