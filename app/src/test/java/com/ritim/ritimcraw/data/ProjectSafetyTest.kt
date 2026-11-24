package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.audio.AudioProject
import com.ritim.ritimcraw.data.audio.AudioStore
import com.ritim.ritimcraw.data.metadata.MetadataStore
import com.ritim.ritimcraw.data.timeline.TimelineStore
import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * F1-2 project-file safety (color model v2 plan §6.4-§6.5): the version
 * stamp can never be dragged down, a TooNew file makes the whole process
 * read-only (observable no-ops, byte-stable file), the migration path
 * takes a never-deleted timestamped backup, and every project.json store
 * is held to the one write gate. The shipped MIGRATIONS chain stays
 * empty here — fake steps exercise the hooks, exactly the way
 * ProjectMigrationsTest does.
 */
class ProjectSafetyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.root, "project.json")
        // The gate is process-global state: start every case clean.
        ProjectFile.resetForTest()
    }

    @After
    fun tearDown() {
        ProjectFile.resetForTest()
    }

    private fun projectFile(): File = file

    /** A full v1 project.json with every section the stores own. */
    private fun fullV1File(): String =
        """{"version":1,"grades":{"clip-a":{"version":1,"exposureEv":0.8,"tempK":4600.0,"tint":3.0}},""" +
            """"sources":[{"id":"src-1","uri":"content://clip/1","kind":"MCRAW"}],""" +
            """"exports":[{"id":"job-1","sourceId":"clip-a","snapshot":{"version":1},"target":"MP4","state":"QUEUED"}],""" +
            """"luts":[{"id":"lut-1","name":"Warm"}],"presets":[{"id":"pres-1","name":"Look","grade":{"version":1,"exposureEv":1.0}}],""" +
            """"timeline":{"version":1,"tracks":[],"clips":[],"output":{"width":1920,"height":1080,"fps":30}},""" +
            """"audio":{"version":1,"links":{},"clips":[]},"metadata":{},"gyros":[{"id":"g-1","name":"log"}],""" +
            """"previewQuality":"HIGH","renderBackend":"AUTO","theme":"DARK","previewStats":false,""" +
            """"gyroFolder":"content://folder/gyro","importFolder":"content://folder/import","systemMonitor":false}"""

    // ---- (a) TooNew: version 999, every store's write is suppressed ----

    @Test
    fun too_new_file_makes_the_process_read_only_and_keeps_the_stamp() {
        val newer = fullV1File().replace("\"version\":1,", "\"version\":999,")
        file.writeText(newer, StandardCharsets.UTF_8)

        val outcome = ProjectFile.open(projectFile())
        assertTrue(outcome is ProjectFile.Outcome.TooNew)
        assertEquals(999, (outcome as ProjectFile.Outcome.TooNew).fileVersion)
        assertTrue(ProjectFile.readOnly)
        assertFalse(ProjectFile.writable())
        assertEquals(999, ProjectFile.rejectedVersion)

        // Every store write is an observable no-op: the file never gets
        // re-rendered, the 999 stamp never becomes 1.
        GradeStore(projectFile()).put("clip-b", GradeK(exposureEv = 1f))
        ExportStore(projectFile()).add(
            ExportJob(id = "job-2", sourceId = "clip-a", snapshot = GradeK(), target = Target.DNG, state = State.QUEUED),
        )
        PresetStore(projectFile()).put("Look 2", GradeK(contrast = 9f))
        TimelineStore(projectFile()).save(TimelineStore.defaultProject())
        AudioStore(projectFile()).save(AudioProject())
        MetadataStore(projectFile()).replaceAll(emptyMap())
        GyroStore(
            FakeResolver(), projectFile(),
            File(tmp.root, "gyros").apply { mkdirs() },
        ).import("content://gyro/1", "log")
        LutStore(
            FakeResolver(), projectFile(),
            File(tmp.root, "luts").apply { mkdirs() },
        ).import("content://lut/1", "cube.cube")
        SourceRepository(FakeResolver(), projectFile()).import("content://clip/2")
        PreviewQualityStore(projectFile()).put(PreviewQuality.HIGH)
        RenderBackendStore(projectFile()).put(com.ritim.ritimcraw.engine.RenderBackend.AUTO)
        ThemeStore(projectFile()).put(ThemeMode.DARK)
        PreviewStatsStore(projectFile()).put(false)
        LibraryPrefsStore(projectFile()).putViewMode(
            com.ritim.ritimcraw.data.LibraryViewMode.GRID,
        )
        GyroFolderStore(projectFile()).put("content://folder/other")
        ImportFolderStore(projectFile()).put("content://folder/other")
        SystemMonitorStore(projectFile()).put(false)

        // Suppressed writes left NO temp file behind, either.
        assertNull(File(tmp.root, "project.json.tmp").let { if (it.exists()) it else null })

        // Byte-identical: the newer build's data survived this one intact.
        assertEquals(newer, file.readText(StandardCharsets.UTF_8))
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":999"))
    }

    @Test
    fun too_new_passes_along_the_grade_store_notification_too() {
        // GradeStore keeps its own per-instance verdict (the onTooNew
        // callback path): the notification fires, reads stay defaults,
        // and the write is refused even when only the store looked.
        ProjectFile.resetForTest()
        file.writeText("{\"version\":999,\"grades\":{\"a\":{\"exposureEv\":1.5}}}", StandardCharsets.UTF_8)
        val store = GradeStore(projectFile())
        assertEquals(GradeK(), store.get("a"))
        assertEquals(999, store.rejectedNewerVersion)
        store.put("b", GradeK(exposureEv = 1f))
        assertEquals(
            "{\"version\":999,\"grades\":{\"a\":{\"exposureEv\":1.5}}}",
            file.readText(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun write_gate_reports_the_suppression_so_it_is_observable() {
        file.writeText("{\"version\":999,\"grades\":{}}", StandardCharsets.UTF_8)
        ProjectFile.open(projectFile())
        assertFalse(ProjectFile.writeTextIfWritable(projectFile(), "{}"))
        assertTrue(ProjectFile.writeTextIfWritable(projectFile(), "{}") == false)
        // The suppressed write did not touch the file.
        assertEquals("{\"version\":999,\"grades\":{}}", file.readText(StandardCharsets.UTF_8))
    }

    // ---- (b) v1 files: the stamp never goes down, behavior identical ----

    @Test
    fun store_writes_on_a_v1_file_keep_the_stamp_at_one_until_the_gate_migrates() {
        // The store-priority call order (no gate open first — the JVM
        // tests' own pattern): a write must not leap a v1 file to 2 over
        // still-flat-v1 sections; the gate is the only version mover, so
        // the NEXT open (the production startup's real first act) still
        // sees schema 1 and migrates.
        file.writeText(fullV1File(), StandardCharsets.UTF_8)

        // The section-writing stores.
        GradeStore(projectFile()).put("clip-new", GradeK(exposureEv = 0.25f))
        ExportStore(projectFile()).add(
            ExportJob(id = "job-2", sourceId = "clip-a", snapshot = GradeK(), target = Target.DNG, state = State.QUEUED),
        )
        PresetStore(projectFile()).put("Look 2", GradeK(tempK = 3200f))
        TimelineStore(projectFile()).save(TimelineStore.defaultProject())
        AudioStore(projectFile()).save(AudioProject())
        MetadataStore(projectFile()).replaceAll(emptyMap())
        val gyros = GyroStore(FakeResolver(), projectFile(), File(tmp.root, "gyros").apply { mkdirs() })
        gyros.import("content://gyro/1", "log")
        val luts = LutStore(FakeResolver(), projectFile(), File(tmp.root, "luts").apply { mkdirs() })
        luts.import("content://lut/1", "cube")
        SourceRepository(FakeResolver(), projectFile()).import("content://clip/2")
        // The scalar-key stores.
        ThemeStore(projectFile()).put(ThemeMode.LIGHT)
        PreviewQualityStore(projectFile()).put(PreviewQuality.BALANCED)
        PreviewStatsStore(projectFile()).put(true)

        // F1-3's stamp rule (§6.5 completed): an OLDER file keeps its OWN
        // stamp through every store write.
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":1"))
        // The gate then migrates it (the production startup's real order).
        val reopened = ProjectFile.open(projectFile())
        assertTrue(reopened is ProjectFile.Outcome.Migrated)
    }

    @Test
    fun the_gate_migrates_a_v1_file_to_v2_with_a_backup_and_all_stores_agree() {
        // THE F1-3 gate case (K4): a real v1 project.json open takes the
        // backup FIRST, runs the shipped chain, writes the migrated file
        // once, and every store then reads v2 records through the shared
        // version-aware parser.
        file.writeText(fullV1File(), StandardCharsets.UTF_8)
        val outcome = ProjectFile.open(
            projectFile(),
            nowMillis = 1_758_000_000_000L,
        )
        assertTrue(outcome is ProjectFile.Outcome.Migrated)
        outcome as ProjectFile.Outcome.Migrated
        assertEquals(1, outcome.fromVersion)

        // The backup holds the ORIGINAL bytes and lives forever (K4).
        assertEquals(fullV1File(), outcome.backup.readText(StandardCharsets.UTF_8))
        assertTrue(outcome.backup.name.startsWith("project-v1-"))

        // The live file: stamped 2 by the chain, every grade record v2.
        val live = file.readText(StandardCharsets.UTF_8)
        assertTrue(live.startsWith("{\"version\":2"))
        assertTrue(live.contains("\"v2\":2"))

        // GradeStore reads the migrated record through the adapter.
        assertEquals(0.8f, GradeStore(projectFile()).get("clip-a").exposureEv, 1e-6f)
        assertEquals(4600f, GradeStore(projectFile()).get("clip-a").tempK, 1e-6f)
        // The export row migrated as a QUEUED job with a v2 snapshot.
        val job = ExportStore(projectFile()).all().single()
        assertEquals(State.QUEUED, job.state)
        assertEquals(0.0f, job.snapshot.exposureEv, 1e-6f)
        // Each store write keeps the 2 stamp now (the merge policy on a
        // current file).
        GradeStore(projectFile()).put("clip-new", GradeK(exposureEv = 1f))
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":2"))
        // Re-opening the migrated file is a plain Open (idempotence).
        val second = ProjectFile.open(projectFile())
        assertTrue(second is ProjectFile.Outcome.Open)
    }

    @Test
    fun running_export_rows_fail_honestly_through_the_gate() {
        // K13 end-to-end: a RUNNING row in a v1 file lands FAILED with the
        // recovery note; nothing else about the row changes.
        val v1 = "{\"version\":1,\"exports\":[" +
            "{\"id\":\"r\",\"sourceId\":\"a\",\"snapshot\":{\"version\":1,\"exposureEv\":0.5}," +
            "\"target\":\"MP4\",\"state\":\"RUNNING\",\"framesDone\":3,\"framesTotal\":90}" +
            "]}"
        file.writeText(v1, StandardCharsets.UTF_8)
        ProjectFile.open(projectFile())
        val job = ExportStore(projectFile()).all().single()
        assertEquals(State.FAILED, job.state)
        assertTrue(job.detail!!.contains("queue the export again"))
        assertEquals(0.5f, job.snapshot.exposureEv, 1e-6f)
    }

    @Test
    fun withSection_ignores_the_caller_version_and_takes_the_file_s_own() {
        // §6.5, completed by F1-3: the stamp is the FILE'S OWN version on
        // both sides — a v2 file stays 2 through any merge (never dragged
        // down), a v1 file stays 1 (never leapt up over flat sections).
        val v2 = "{\"version\":2,\"grades\":{\"a\":{\"exposureEv\":1}},\"future\":{\"x\":1}}"
        val merged = GradeStore.mergeInto(v2, mapOf("b" to GradeK()))
        assertTrue(merged.startsWith("{\"version\":2"))

        val v1 = "{\"version\":1,\"grades\":{}}"
        val stamped = ProjectJson.withSection(v1, "grades", "{}", version = 7)
        assertTrue(stamped.startsWith("{\"version\":1"))

        // An unversioned EXISTING file (the pre-version legacy shape) is
        // schema 1: it keeps stamp 1 until the gate migrates it.
        val unversioned = ProjectJson.withSection("{\"grades\":{}}", "grades", "{}", version = 2)
        assertTrue(unversioned.startsWith("{\"version\":1"))

        // A FRESH file (nothing readable on disk) stamps CURRENT_VERSION.
        val fresh = ProjectJson.withSection(null, "grades", "{}", version = 1)
        assertTrue(fresh.startsWith("{\"version\":2"))
    }

    @Test
    fun unversioned_legacy_file_migrates_on_open() {
        // F1-3: an unversioned file IS schema 1, so the gate migrates it
        // (F1-2 kept it as a no-op Open while the chain was empty).
        file.writeText("{\"grades\":{\"a\":{\"exposureEv\":2}}}", StandardCharsets.UTF_8)
        val outcome = ProjectFile.open(projectFile())
        assertTrue(outcome is ProjectFile.Outcome.Migrated)
        assertEquals(2f, GradeStore(projectFile()).get("a").exposureEv, 1e-6f)
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":2"))
    }

    // ---- (c) K4: the migration path takes a never-deleted backup ----

    /** A fake chain the way ProjectMigrationsTest builds one: v1 -> v2. */
    private fun fakeChain(): List<MigrationStep> = listOf({ root ->
        root + ("trail" to "v1to2")
    })

    @Test
    fun migration_takes_a_backup_before_the_file_changes() {
        val v1 = "{\"version\":1,\"grades\":{\"a\":{\"exposureEv\":1.25}},\"presets\":[]}"
        file.writeText(v1, StandardCharsets.UTF_8)

        val outcome = ProjectFile.open(
            projectFile(),
            nowMillis = 1_758_000_000_000L, // fixed clock: the name is deterministic
            migrations = fakeChain(),
        )
        assertTrue(outcome is ProjectFile.Outcome.Migrated)
        outcome as ProjectFile.Outcome.Migrated
        assertEquals(1, outcome.fromVersion)

        // The backup holds the ORIGINAL bytes, atomically written, in
        // files/backups, named with the rescued version + a timestamp.
        val dir = ProjectFile.backupDir(projectFile())
        assertEquals(File(file.parentFile, "backups"), dir)
        val backup = outcome.backup
        assertTrue(backup.exists())
        assertEquals(dir, backup.parentFile)
        assertEquals(v1, backup.readText(StandardCharsets.UTF_8))
        assertTrue(backup.name.startsWith("project-v1-"))
        assertTrue(backup.name.endsWith(".json"))

        // The live file was migrated: the stamp the CHAIN gave it (2)
        // survives, the fake step's marker is present, and a re-open is
        // a plain Open (the idempotence the chain's steps must have).
        val migrated = file.readText(StandardCharsets.UTF_8)
        assertTrue(migrated.contains("\"trail\":\"v1to2\""))
        assertTrue(migrated.startsWith("{\"version\":2"))
        val second = ProjectFile.open(projectFile(), migrations = fakeChain())
        assertTrue(second is ProjectFile.Outcome.Open)
        // No second backup was taken for the already-current file.
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test
    fun backup_lives_independently_after_further_migrations() {
        // The rescue copy must not be a hardlink-ish view of the live
        // file: after the file keeps evolving (multiple writes, another
        // migration), the backup still holds the ORIGINAL v1 bytes.
        val v1 = "{\"version\":1,\"grades\":{\"a\":{\"exposureEv\":1.25}}}"
        file.writeText(v1, StandardCharsets.UTF_8)
        val first = ProjectFile.open(projectFile(), nowMillis = 1_000L, migrations = fakeChain())
        assertTrue(first is ProjectFile.Outcome.Migrated)
        val backup = (first as ProjectFile.Outcome.Migrated).backup

        // A grade write on the migrated file must not touch the backup.
        GradeStore(projectFile()).put("b", GradeK(exposureEv = 9f))
        assertEquals(v1, backup.readText(StandardCharsets.UTF_8))

        // And a SECOND migration (a longer fake chain: v2 -> v3) still
        // keeps the first backup intact, and by now a second one exists.
        val chain2 = fakeChain() + listOf<MigrationStep>({ root -> root + ("trail2" to "x") })
        val second = ProjectFile.open(projectFile(), nowMillis = 2_000L, migrations = chain2)
        assertTrue(second is ProjectFile.Outcome.Migrated)
        assertEquals(v1, backup.readText(StandardCharsets.UTF_8))
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":3"))
        assertEquals(2, ProjectFile.backupDir(projectFile()).listFiles()!!.size)
    }

    @Test
    fun backup_is_never_overwritten_by_a_same_second_second_run() {
        val v1 = "{\"version\":1,\"grades\":{}}"
        file.writeText(v1, StandardCharsets.UTF_8)
        ProjectFile.resetForTest()
        // Two opens of the SAME v1 file with the SAME fake chain and the
        // SAME clock: the second backup gets a suffix, both survive.
        val a = ProjectFile.open(projectFile(), nowMillis = 5_000L, migrations = fakeChain())
        val migrated = file.readText(StandardCharsets.UTF_8)
        assertTrue(migrated.startsWith("{\"version\":2"))
        // Roll the file back to v1 by hand (the disaster the backup
        // exists for) and reopen at the same instant.
        file.writeText(v1, StandardCharsets.UTF_8)
        val b = ProjectFile.open(projectFile(), nowMillis = 5_000L, migrations = fakeChain())
        assertTrue(b is ProjectFile.Outcome.Migrated)
        assertTrue((a as ProjectFile.Outcome.Migrated).backup.exists())
        assertTrue((b as ProjectFile.Outcome.Migrated).backup.exists())
        // Same second, different files: the second got a -2 suffix.
        assertTrue(a.backup.name.startsWith("project-v1-"))
        assertTrue(b.backup.name.startsWith("project-v1-"))
        assertFalse(a.backup.name == b.backup.name)
        assertEquals(2, ProjectFile.backupDir(projectFile()).listFiles()!!.size)
    }

    // ---- (d) AtomicFiles move: Preset/Timeline write-read parity ----

    @Test
    fun preset_store_round_trips_through_the_atomic_gate() {
        val store = PresetStore(projectFile())
        val grade = GradeK(exposureEv = 0.75f, tempK = 4300f, contrast = 12f, stabilization = true, stabilizationStrength = 60f)
        val preset = store.put("Atomic look", grade)

        assertEquals(preset, PresetStore(projectFile()).get(preset.id))
        assertEquals(grade, PresetStore(projectFile()).get(preset.id)?.grade)
        // Fresh file stamps CURRENT_VERSION (F1-3: 2); the preset's grade
        // rides the v2 wire and parses back through the shared parser.
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":2"))
        // Atomic write: no temp file survives a successful write.
        assertFalse(File(tmp.root, "project.json.tmp").exists())
    }

    @Test
    fun timeline_store_round_trips_through_the_atomic_gate() {
        val store = TimelineStore(projectFile())
        val project = TimelineStoreTestEnv.projectWithOneClip()
        store.save(project)

        assertEquals(project, TimelineStore(projectFile()).load())
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":2"))
        assertFalse(File(tmp.root, "project.json.tmp").exists())
    }

    @Test
    fun failed_atomic_write_keeps_the_old_file_and_throws() {
        // The AtomicFiles contract (see AtomicFilesTest): a failure
        // propagates and the target survives untouched. The gate must
        // not swallow the error into a silent no-op.
        val store = PresetStore(projectFile())
        val first = store.put("Keep me", GradeK(exposureEv = 1f))
        val before = file.readText(StandardCharsets.UTF_8)

        val dir = file.parentFile!!
        assertTrue(dir.setWritable(false))
        try {
            val failed = runCatching { store.put("Crash", GradeK()) };
            assertTrue(failed.isFailure)
            assertEquals(before, file.readText(StandardCharsets.UTF_8))
        } finally {
            dir.setWritable(true)
        }
        assertEquals(first, PresetStore(projectFile()).get(first.id))
    }
}

/** Just enough timeline model for the parity check, kept out of the test body. */
private object TimelineStoreTestEnv {
    fun projectWithOneClip() = com.ritim.ritimcraw.data.timeline.TimelineProject(
        tracks = listOf(
            com.ritim.ritimcraw.data.timeline.TimelineTrack("v", com.ritim.ritimcraw.data.timeline.TrackKind.VIDEO, 0),
        ),
        clips = listOf(
            com.ritim.ritimcraw.data.timeline.TimelineClip(
                id = "c1",
                trackId = "v",
                sourceId = "src-1",
                sourceRange = com.ritim.ritimcraw.data.timeline.FrameRange(0.0, 50.0),
                timelineRange = com.ritim.ritimcraw.data.timeline.FrameRange(0.0, 50.0),
            ),
        ),
    )
}
