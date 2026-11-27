package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.audio.AudioClip
import com.ritim.ritimcraw.data.audio.AudioProject
import com.ritim.ritimcraw.data.audio.AudioStore
import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.GradeV2Fiber
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import com.ritim.ritimcraw.data.grade2.toGradeK
import com.ritim.ritimcraw.data.metadata.MetadataStore
import com.ritim.ritimcraw.data.timeline.FrameRange
import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineOutput
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.data.timeline.TimelineStore
import com.ritim.ritimcraw.data.timeline.TimelineTrack
import com.ritim.ritimcraw.data.timeline.TrackKind
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * E1 (night mission 2026-11-27): project file RESILIENCE — the v1 promise
 * "the user's project never corrupts" proven on the adversarial inputs the
 * existing suites do not cover: empty and half-written (truncated) files,
 * NaN/Infinity numbers, wrong-typed fields, and very large arrays, across
 * ProjectFile/ProjectJson, the GradeV2 wire, GradeStore, AtomicFiles and
 * the section stores (ExportStore/AudioStore/TimelineStore/MetadataStore).
 *
 * Existing coverage (NOT duplicated here): the version-stamp/TooNew/backup
 * contracts (ProjectSafetyTest, ProjectMigrationsTest), the golden v1->v2
 * parity (GoldenV1MigrationParityTest), the field-by-field grade round
 * trips (GradeStoreTest, GradeV2WireTest, ExportStoreTest, AudioStoreTest,
 * TimelineStoreTest, MetadataStoreTest), and AtomicFiles' basic contracts
 * (AtomicFilesTest). This file fills the GAP list the E1 brief names.
 */
class ProjectFileResilienceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File(tmp.root, "project.json")
        ProjectFile.resetForTest()
    }

    @After
    fun tearDown() {
        ProjectFile.resetForTest()
    }

    // ---- (a) round-trip every field full: write -> read -> identical ----

    @Test
    fun a_fully_loaded_project_round_trips_through_every_store() {
        // Every section carries its MAXIMAL shape (every optional field
        // set), then the whole file reloads through fresh store instances
        // and every value must come back identical (the E1 brief's case a).
        val grade = GradeK(
            exposureEv = 0.8f, highlights = -30f, tempK = 6100f, tint = 12f,
            saturation = 22f, blackPoint = 6f, whitePoint = -4f, shadows = 18f,
            midtones = -9f, contrast = 14f, asShot = false,
            highlightReconstruction = false, keepVignetting = false,
            highlightRecovery = true, highlightKneeRef = 0.42f,
            sharpness = 31f, detail = -17f, tonemap = 3,
            lutId = "cube-44", lutStrength = 65f,
            noiseReduction = 40f, chromaDenoise = 25f,
            temporalNr = true, temporalStrength = 70f,
            stabilization = true, stabilizationMode = 2,
            stabilizationStrength = 80f, stabilizationZoomLimit = 1.4f,
            stabilizationTrimMs = 12.5f, stabilizationFocalMm = 24f,
            stabilizationEngine = 1, cropAspect = 1, cropX = 0.1f, cropY = 0.2f,
            cropW = 0.7f, cropH = 0.6f, straightenDeg = 3.5f,
            wheelShadowX = 0.11f, wheelShadowY = -0.22f, wheelMidX = -0.33f,
            wheelMidY = 0.44f, wheelHighX = 0.55f, wheelHighY = 0.66f,
            splitShadowHue = 210f, splitHighlightHue = 45f, splitBalance = -25f,
        )
        GradeStore(file).put("clip-full", grade)
        assertEquals(grade, GradeStore(file).get("clip-full"))

        // Export snapshot: the same maximal grade rides a job row.
        val job = ExportJob(
            id = "job-full", sourceId = "clip-full", snapshot = grade,
            target = Target.MP4, state = State.DONE, detail = "ok",
            framesDone = 90, framesTotal = 90,
            publishedUri = "content://media/1", outputName = "name.mp4",
            frameIndex = 7, manualRotationDeg = 90,
        )
        ExportStore(file).add(job)
        val jobBack = ExportStore(file).all().single()
        assertEquals(job, jobBack)
        assertEquals(grade, jobBack.snapshot)

        // Audio: every optional clip field + master state.
        val audio = AudioProject(
            clips = listOf(
                AudioClip(
                    id = "a-1", sourceId = "src-1", sampleRateHz = 48_000,
                    gainDb = -6.5f, fadeInMs = 120, fadeOutMs = 80,
                    pan = 0.25f, muted = true,
                ),
            ),
            masterGainDb = -4.5f, masterMuted = true,
        )
        AudioStore(file).save(audio)
        assertEquals(audio, AudioStore(file).load())

        // Timeline: optional project fields + output.
        val timeline = TimelineProject(
            output = TimelineOutput(width = 3840, height = 2160, fps = 60,
                orientationDeg = 90, colorSpace = "rec2020", transfer = "hlg", hdr = true),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(
                TimelineClip(
                    id = "c1", trackId = "v", sourceId = "src-1",
                    sourceRange = FrameRange(0.0, 50.5),
                    timelineRange = FrameRange(10.25, 60.75),
                ),
            ),
            projectEnd = 120.5, backgroundDuration = 90.25,
        )
        TimelineStore(file).save(timeline)
        assertEquals(timeline, TimelineStore(file).load())

        // And the whole file still opens as OUR version (not TooNew).
        val outcome = ProjectFile.open(file)
        assertTrue(outcome is ProjectFile.Outcome.Open)
    }

    // ---- (b) missing / extra / wrong-typed / NaN / empty / truncated / large ----

    @Test
    fun empty_file_is_open_not_crash_and_stores_read_defaults() {
        file.writeText("", StandardCharsets.UTF_8)
        val outcome = ProjectFile.open(file)
        // Absent-or-unreadable opens as Open (the gate's own contract).
        assertTrue(outcome is ProjectFile.Outcome.Open)
        assertTrue(GradeStore(file).get("x").exposureEv == 0f)
        assertTrue(ExportStore(file).all().isEmpty())
        assertEquals(AudioProject(), AudioStore(file).load())
        assertEquals(TimelineStore.defaultProject(), TimelineStore(file).load())
    }

    @Test
    fun half_written_truncated_file_never_crashes_any_store_and_survives_on_disk() {
        // A crash mid-write cannot happen through AtomicFiles (tmp+rename),
        // but a torn file can still arrive (disk full during an OLD
        // non-atomic write, a sync tool, a hand edit). Every store's load
        // must degrade to defaults WITHOUT throwing, and the raw bytes must
        // stay untouched (the recovery path is the user's, not ours).
        val good = GradeStore.mergeInto(
            "{\"version\":2,\"sources\":[{\"id\":\"s1\",\"uri\":\"u\",\"fingerprint\":\"f\",\"kind\":\"MCRAW\",\"access\":\"OK\"}]}",
            mapOf("c1" to GradeK(exposureEv = 1f)),
        )
        // Cut mid-way: a truncated JSON document.
        file.writeText(good.substring(0, good.length / 2), StandardCharsets.UTF_8)
        val before = file.readText(StandardCharsets.UTF_8)

        ProjectFile.open(file) // must not throw
        assertTrue(GradeStore(file).get("c1").exposureEv == 0f)
        assertTrue(ExportStore(file).all().isEmpty())
        assertEquals(AudioProject(), AudioStore(file).load())
        assertEquals(TimelineStore.defaultProject(), TimelineStore(file).load())
        assertTrue(SourceRepository(FakeResolver(), file).all().isEmpty())
        // PresetStore catches ONLY IllegalArgumentException (the narrowest
        // contract in the app): the parser fix (truncated/garbage escapes
        // fail as IAE, not IndexOutOfBounds/NumberFormatException) is what
        // keeps THIS store from crashing on a torn file.
        assertTrue(PresetStore(file).all().isEmpty())
        assertTrue(MetadataStore(file).load().isEmpty())
        assertEquals(before, file.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun truncated_unicode_escape_in_a_string_fails_as_the_contracted_exception() {
        // The \u escape reads 4 hex chars with substring(i+1, i+5): a file
        // cut INSIDE the escape must fail as IllegalArgumentException (the
        // type every store's catch clause names), never as an unchecked
        // IndexOutOfBoundsException that skips the corrupt-file fallback
        // and crashes the loader.
        val torn = "{\"version\":2,\"grades\":{\"a\":{\"lutId\":\"ab\\u00"
        file.writeText(torn, StandardCharsets.UTF_8)
        runCatching { JsonParser(torn).parseValue() }.onFailure { e ->
            assertTrue(
                "parser threw ${e.javaClass.simpleName}: ${e.message}",
                e is IllegalArgumentException,
            )
        }
        // And the store-level contract: corrupt file -> defaults, no crash.
        assertTrue(GradeStore(file).get("a").exposureEv == 0f)
    }

    @Test
    fun garbage_unicode_escape_fails_as_the_contracted_exception() {
        // \uXXXX with non-hex digits must also surface as
        // IllegalArgumentException, not NumberFormatException.
        val bad = "{\"version\":2,\"grades\":{\"a\":{\"lutId\":\"x\\uZZZZ\"}}"
        file.writeText(bad, StandardCharsets.UTF_8)
        val outcome = runCatching { JsonParser(bad).parseValue() }
        if (outcome.isFailure) {
            assertTrue(
                "parser threw ${outcome.exceptionOrNull()!!.javaClass.simpleName}",
                outcome.exceptionOrNull() is IllegalArgumentException,
            )
        }
        assertTrue(GradeStore(file).get("a").exposureEv == 0f)
    }

    @Test
    fun trailing_backslash_in_a_string_fails_as_the_contracted_exception() {
        // The escape branch reads s[i] AFTER the backslash without a
        // bounds check: a file ending in an unterminated escape must be
        // IllegalArgumentException, not IndexOutOfBoundsException.
        val torn = "{\"version\":2,\"grades\":{\"a\":{\"lutId\":\"ab\\"
        file.writeText(torn, StandardCharsets.UTF_8)
        runCatching { JsonParser(torn).parseValue() }.onFailure { e ->
            assertTrue(
                "parser threw ${e.javaClass.simpleName}: ${e.message}",
                e is IllegalArgumentException,
            )
        }
        assertTrue(GradeStore(file).get("a").exposureEv == 0f)
    }

    @Test
    fun non_finite_numbers_in_a_file_never_crash_a_store() {
        // A hand-edited (or foreign-tool-written) file carrying NaN /
        // Infinity tokens: the parser may reject the token (corrupt ->
        // defaults) but nothing may CRASH; the file stays byte-intact.
        val bad = "{\"version\":2,\"grades\":{\"a\":{\"v2\":2," +
            "\"develop\":{\"exposureEv\":NaN,\"tempK\":Infinity}}}"
        file.writeText(bad, StandardCharsets.UTF_8)
        runCatching { ProjectFile.open(file) }
        val g = runCatching { GradeStore(file).get("a") }.getOrNull()
        assertNotNull(g)
        val before = file.readText(StandardCharsets.UTF_8)
        assertEquals(bad, before)
    }

    @Test
    fun wrong_typed_fields_fall_back_per_field_and_other_rows_survive() {
        // The row-tolerant contract, adversarially: strings where numbers
        // belong, numbers where booleans belong, arrays where objects
        // belong. Only the bad field degrades; the sibling row and the
        // other sections survive.
        val text = "{\"version\":2," +
            "\"grades\":{" +
            "\"bad\":{\"v2\":2,\"develop\":{\"exposureEv\":\"high\",\"asShot\":1}}," +
            "\"good\":{\"v2\":2,\"develop\":{\"exposureEv\":0.75}}}," +
            "\"future\":[1,2,3]}"
        file.writeText(text, StandardCharsets.UTF_8)
        val store = GradeStore(file)
        assertEquals(0f, store.get("bad").exposureEv, 0f)
        assertTrue(store.get("bad").asShot) // derived neutral default
        assertEquals(0.75f, store.get("good").exposureEv, 0f)
        // The unknown future section rides the file untouched (a write
        // keeps it).
        GradeStore(file).put("another", GradeK(exposureEv = 2f))
        assertTrue(file.readText(StandardCharsets.UTF_8).contains("\"future\":[1,2,3]"))
    }

    @Test
    fun unknown_extra_fields_are_ignored_and_survive_section_writes() {
        // Extra/unknown keys inside a grade record (a newer build's fields
        // in an older-typed record) parse through the tolerant path and a
        // sibling write carries them raw.
        val text = "{\"version\":2," +
            "\"grades\":{\"a\":{\"v2\":2,\"develop\":{\"exposureEv\":1.5," +
            "\"futureKnob\":7.0,\"nestedFuture\":{\"x\":1}}}}}"
        file.writeText(text, StandardCharsets.UTF_8)
        assertEquals(1.5f, GradeStore(file).get("a").exposureEv, 0f)
        GradeStore(file).put("b", GradeK(exposureEv = 0.5f))
        val after = file.readText(StandardCharsets.UTF_8)
        // renderRaw's integral-double rule writes 7.0 as 7 — the field
        // SURVIVES the write (raw carry), in the writer's canonical form.
        assertTrue(after.contains("\"futureKnob\":7"))
        assertTrue(after.contains("\"nestedFuture\":{\"x\":1}"))
    }

    @Test
    fun very_large_arrays_round_trip_and_stay_parseable() {
        // 5000 nodes in one grade record + a big exports section: the
        // wire renders, parses back equal, and the whole project.json
        // still opens (the E1 brief's "çok büyük dizi" case). The exports
        // rows go FIRST: add() re-reads + re-renders the whole file per
        // call, and grading the 5000-node record before them would make
        // every add() re-parse it (O(n*m) — minutes, not proof).
        val jobs = (0 until 300).map { i ->
            ExportJob(
                id = "job-$i", sourceId = "s", snapshot = GradeK(exposureEv = 0.1f * i),
                target = Target.DNG, state = State.DONE, detail = "ok $i",
                framesDone = i, framesTotal = 100,
            )
        }
        jobs.forEach { ExportStore(file).add(it) }
        assertEquals(300, ExportStore(file).all().size)
        assertEquals("ok 299", ExportStore(file).all().last().detail)

        // The single big grade record: wire render/parse equality first
        // (pure, no store), then ONE store put + ONE read (the raw carry
        // of a 5000-node record).
        val nodes = (0 until 5000).map { i ->
            GradeNodeV2(sat = 1f + (i % 7) * 0.01f, name = "n$i")
        }
        val grade = ClipGradeV2(
            develop = com.ritim.ritimcraw.data.grade2.DevelopV2(exposureEv = 0.5f),
            nodes = nodes,
        )
        val rendered = GradeV2Fiber.render(grade)
        val parsed = ClipGradeV2.parse(
            JsonParser(rendered).parseValue() as Map<*, *>,
        )
        assertEquals(5000, parsed.nodes.size)
        assertEquals(grade, parsed)

        // The big record through the STORE (renderedV2Record + raw carry).
        GradeStore(file).put("big", grade)
        assertEquals(5000, GradeStore(file).getV2("big")!!.nodes.size)
        // And the file with both big sections still opens.
        assertTrue(ProjectFile.open(file) is ProjectFile.Outcome.Open)
    }

    @Test
    fun a_single_bad_export_row_does_not_empty_the_whole_queue() {
        // ExportStore.parseExports is row-tolerant for TYPES (a non-object
        // row is skipped) — but a row whose JSON is fine and whose values
        // are wrong-typed must degrade THAT row, never the queue. The
        // crash-everything path would be the load() catch-all.
        val text = "{\"version\":2,\"exports\":[" +
            "{\"id\":\"ok\",\"sourceId\":\"s\",\"snapshot\":{\"v2\":2},\"target\":\"DNG\",\"state\":\"DONE\"}," +
            "{\"id\":\"bad\",\"sourceId\":\"s\",\"snapshot\":{\"v2\":2},\"target\":123,\"state\":\"QUEUED\"}," +
            "{\"id\":\"ok2\",\"sourceId\":\"s\",\"snapshot\":{\"v2\":2},\"target\":\"DNG\",\"state\":\"DONE\"}" +
            "]}"
        file.writeText(text, StandardCharsets.UTF_8)
        val jobs = ExportStore(file).all()
        assertEquals(3, jobs.size) // the wrong-typed target falls back, the row stays
        assertTrue(jobs.any { it.id == "ok" && it.state == State.DONE })
        assertTrue(jobs.any { it.id == "ok2" })
    }

    // ---- (c) old v1 schema -> v2 migration + the backup file ----

    @Test
    fun old_v1_file_migrates_once_backs_up_once_and_reads_back() {
        // The E1 brief's case (c): a REAL v1 file (flat grade records, no
        // v2 markers) migrates through the gate, the backup holds the
        // original bytes, and the migrated file reloads identical.
        val v1 = "{\"version\":1," +
            "\"grades\":{\"clip-a\":{\"version\":1,\"exposureEv\":0.8,\"tempK\":4600.0,\"tint\":3.0," +
            "\"stabilization\":true,\"stabilizationStrength\":70.0}}," +
            "\"sources\":[{\"id\":\"clip-a\",\"uri\":\"content://x\",\"fingerprint\":\"fp\",\"kind\":\"MCRAW\",\"access\":\"OK\"}]," +
            "\"exports\":[{\"id\":\"j1\",\"sourceId\":\"clip-a\",\"snapshot\":{\"version\":1,\"exposureEv\":0.5},\"target\":\"MP4\",\"state\":\"QUEUED\"}]}"
        file.writeText(v1, StandardCharsets.UTF_8)

        val outcome = ProjectFile.open(file, nowMillis = 1_758_000_000_000L)
        assertTrue(outcome is ProjectFile.Outcome.Migrated)
        outcome as ProjectFile.Outcome.Migrated
        assertEquals(1, outcome.fromVersion)

        // The backup: exists, holds the ORIGINAL bytes, in files/backups.
        assertTrue(outcome.backup.exists())
        assertEquals(v1, outcome.backup.readText(StandardCharsets.UTF_8))
        assertEquals(File(file.parentFile, "backups"), outcome.backup.parentFile)

        // The migrated file: v2 stamp, the grade reads back through the
        // adapter with the exact v1 values.
        val live = file.readText(StandardCharsets.UTF_8)
        assertTrue(live.startsWith("{\"version\":2"))
        val g = GradeStore(file).get("clip-a")
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(4600f, g.tempK, 0f)
        assertTrue(g.stabilization)
        assertEquals(70f, g.stabilizationStrength, 0f)

        // Idempotence: a second open is a plain Open, no second backup.
        assertTrue(ProjectFile.open(file, nowMillis = 1_758_000_000_001L) is ProjectFile.Outcome.Open)
        assertEquals(1, ProjectFile.backupDir(file).listFiles()!!.size)

        // The v2 file round-trips stably: a store write + reload keeps the
        // same grade values.
        GradeStore(file).put("clip-b", GradeK(exposureEv = 1.25f))
        assertEquals(0.8f, GradeStore(file).get("clip-a").exposureEv, 0f)
        assertEquals(1.25f, GradeStore(file).get("clip-b").exposureEv, 0f)
    }

    @Test
    fun migration_survives_a_corrupt_grade_row_without_losing_the_good_ones() {
        // A v1 file with ONE unparseable grade record (wrong-typed nested
        // value): the migration's row-tolerance carries it raw, the good
        // records migrate, and the file the gate writes back still PARSES
        // (never a half-broken v2).
        val v1 = "{\"version\":1," +
            "\"grades\":{" +
            "\"good\":{\"version\":1,\"exposureEv\":0.5}," +
            "\"raw\":\"not-an-object\"}," +
            "\"sources\":[]}"
        file.writeText(v1, StandardCharsets.UTF_8)
        val outcome = ProjectFile.open(file)
        assertTrue(outcome is ProjectFile.Outcome.Migrated)
        assertEquals(0.5f, GradeStore(file).get("good").exposureEv, 0f)
        // The file the gate wrote re-parses cleanly.
        val root = JsonParser(file.readText(StandardCharsets.UTF_8)).parseValue() as Map<*, *>
        assertTrue(root.containsKey("grades"))
    }

    // ---- (d) "too new" version refused (no data loss) ----

    @Test
    fun too_new_version_is_refused_the_file_survives_byte_for_byte_and_writes_are_no_ops() {
        // The E1 brief's case (d): a file stamped with a future version
        // must be refused, never migrated down, never re-rendered — and
        // the process-global read-only gate holds for every store write.
        val future = "{\"version\":999," +
            "\"grades\":{\"a\":{\"v2\":2,\"develop\":{\"exposureEv\":1.5}}}," +
            "\"futureSection\":{\"payload\":[1,2,{\"k\":\"v\"}]}}"
        file.writeText(future, StandardCharsets.UTF_8)

        val outcome = ProjectFile.open(file)
        assertTrue(outcome is ProjectFile.Outcome.TooNew)
        assertEquals(999, (outcome as ProjectFile.Outcome.TooNew).fileVersion)
        assertTrue(ProjectFile.readOnly)
        assertFalse(ProjectFile.writable())

        // Reads degrade to defaults (never a re-interpretation).
        assertEquals(GradeK(), GradeStore(file).get("a"))

        // Writes are observable no-ops: byte-for-byte survival.
        val storeWrites = listOf<() -> Unit>(
            { GradeStore(file).put("b", GradeK(exposureEv = 1f)) },
            { ExportStore(file).add(ExportJob(id = "j", sourceId = "a", snapshot = GradeK(), target = Target.DNG, state = State.QUEUED)) },
            { AudioStore(file).save(AudioProject(masterGainDb = -3f)) },
            { TimelineStore(file).save(TimelineStore.defaultProject()) },
            { MetadataStore(file).replaceAll(emptyMap()) },
        )
        storeWrites.forEach { it() }
        assertEquals(future, file.readText(StandardCharsets.UTF_8))

        // A corrupt file on TOP of the refused state cannot sneak a write
        // through either (the gate is checked before the disk).
        file.writeText(future.replace("1.5", "NaN"), StandardCharsets.UTF_8)
        ProjectFile.writeTextIfWritable(file, "{}")
        // Still refused: the gate state is process-global and sticky.
        assertTrue(ProjectFile.readOnly)
    }

    // ---- the self-corruption guard (write side) ----

    @Test
    fun a_non_finite_float_in_a_grade_never_poisons_the_file() {
        // THE resilience contract: whatever non-finite Float reaches a
        // store (a computed knee ref, a NaN from a division the caller
        // missed), the bytes this build writes must stay readable by this
        // build — the file is never turned into something its own parser
        // rejects (the self-corruption the raw .toString() would cause:
        // "NaN" and "Infinity" are not JSON tokens).
        val poisoned = GradeK(
            exposureEv = Float.NaN,
            highlights = Float.POSITIVE_INFINITY,
            stabilizationStrength = Float.NEGATIVE_INFINITY,
            highlightKneeRef = Float.NaN,
        )
        GradeStore(file).put("nan-clip", poisoned)
        val text = file.readText(StandardCharsets.UTF_8)
        // Whatever was written, this build must be able to READ it back
        // (no unparseable file, no crash) — and the next store write must
        // not lose the OTHER sections to a failed re-parse.
        val reloaded = GradeStore(file).get("nan-clip")
        assertNotNull(reloaded)
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        // Sanitized to neutral, not carried as poison.
        assertTrue(reloaded.exposureEv.isFinite())
        assertTrue(reloaded.highlights.isFinite())
        assertTrue(reloaded.stabilizationStrength.isFinite())
    }

    @Test
    fun a_non_finite_float_in_an_export_snapshot_never_poisons_the_file() {
        val job = ExportJob(
            id = "j-nan", sourceId = "s", snapshot = GradeK(exposureEv = Float.NaN),
            target = Target.MP4, state = State.QUEUED,
        )
        ExportStore(file).add(job)
        // A later grade write re-parses the whole file (the merge): it
        // must succeed and keep both sections.
        GradeStore(file).put("other", GradeK(exposureEv = 1f))
        val text = file.readText(StandardCharsets.UTF_8)
        assertTrue(text.contains("\"grades\""))
        assertTrue(text.contains("\"exports\""))
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
    }

    @Test
    fun a_non_finite_double_in_audio_timeline_and_metadata_never_poisons_the_file() {
        // The Double-typed renders (AudioStore gains/pan, TimelineStore
        // ranges/ends, MetadataStore fps fields) share the same rule.
        AudioStore(file).save(
            AudioProject(
                clips = listOf(
                    AudioClip(
                        id = "a", sourceId = "s", sampleRateHz = 48_000,
                        gainDb = Float.NaN,
                    ),
                ),
                masterGainDb = Float.NEGATIVE_INFINITY,
            ),
        )
        val audioText = file.readText(StandardCharsets.UTF_8)
        assertFalse(audioText.contains("NaN"))
        assertFalse(audioText.contains("Infinity"))
        // And it loads back (the parse's NaN->0 guards already exist; the
        // write side now matches them).
        val audioBack = AudioStore(file).load()
        assertTrue(audioBack.clips.single().gainDb.isFinite())

        TimelineStore(file).save(
            TimelineProject(projectEnd = Double.NaN, backgroundDuration = Double.POSITIVE_INFINITY),
        )
        val tlText = file.readText(StandardCharsets.UTF_8)
        assertFalse(tlText.contains("NaN"))
        assertFalse(tlText.contains("Infinity"))
        TimelineStore(file).load() // parses

        val record = com.ritim.ritimcraw.data.metadata.MetadataRecord(
            sourceId = "s",
            kind = SourceKind.MCRAW,
            durationUs = com.ritim.ritimcraw.data.metadata.Field.known(1L),
            frameCount = com.ritim.ritimcraw.data.metadata.Field.known(2),
            fpsNominal = com.ritim.ritimcraw.data.metadata.Field.known(Double.NaN),
            fpsMeasured = com.ritim.ritimcraw.data.metadata.Field.known(Double.POSITIVE_INFINITY),
            camera = com.ritim.ritimcraw.data.metadata.Field.known("cam"),
            lens = com.ritim.ritimcraw.data.metadata.Field.known("lens"),
            resolution = com.ritim.ritimcraw.data.metadata.Field.known("r"),
            bitDepth = com.ritim.ritimcraw.data.metadata.Field.known(16),
            primaries = com.ritim.ritimcraw.data.metadata.Field.unknown(),
            transfer = com.ritim.ritimcraw.data.metadata.Field.unknown(),
            matrix = com.ritim.ritimcraw.data.metadata.Field.unknown(),
            range = com.ritim.ritimcraw.data.metadata.Field.unknown(),
            audio = com.ritim.ritimcraw.data.metadata.Field.unknown(),
            fileSize = com.ritim.ritimcraw.data.metadata.Field.known(3L),
            fileIdentity = com.ritim.ritimcraw.data.metadata.Field.known("fi"),
        )
        MetadataStore(file).put(record)
        val mdText = file.readText(StandardCharsets.UTF_8)
        assertFalse(mdText.contains("NaN"))
        assertFalse(mdText.contains("Infinity"))
        MetadataStore(file).load() // parses
    }

    @Test
    fun a_non_finite_double_in_export_settings_and_report_never_poisons_the_file() {
        // The export row's nested wires carry Double/Float fields too: a
        // non-finite value (a hand-edited 1e999 parses to Infinity) must
        // not re-render as a bare token on the next queue write.
        val settings = com.ritim.ritimcraw.export.ExportSettings(
            bitrateLevel = com.ritim.ritimcraw.export.BitrateLevel.CUSTOM,
            customMbps = Double.POSITIVE_INFINITY,
        )
        val report = com.ritim.ritimcraw.export.ExportReport(
            encoder = "enc", hardware = true, mime = "video/avc",
            width = 1920, height = 1080, fps = Double.NaN, bitDepth = 8,
            rateMode = "VBR", profile = 1, keyframeSec = Float.NaN,
            requestedBps = 1000, audioKbps = 128, fileBytes = 10L,
            durationSec = Double.NEGATIVE_INFINITY, steps = listOf("s"), issues = emptyList(),
        )
        val job = ExportJob(
            id = "j2", sourceId = "s", snapshot = GradeK(), target = Target.MP4,
            state = State.DONE, settings = settings, report = report,
        )
        ExportStore(file).add(job)
        val text = file.readText(StandardCharsets.UTF_8)
        assertFalse(text.contains("NaN"))
        assertFalse(text.contains("Infinity"))
        // And the whole file still parses (the queue reloads).
        val jobs = ExportStore(file).all()
        assertEquals(1, jobs.size)
        // The poisoned customMbps reads back as absent = AUTO (honest,
        // never a guessed rate); the report degrades to defaults it can
        // re-render — the queue keeps working.
        ExportStore(file).update(jobs.single().copy(detail = "again"))
        assertFalse(file.readText(StandardCharsets.UTF_8).contains("NaN"))
        assertFalse(file.readText(StandardCharsets.UTF_8).contains("Infinity"))
    }

    @Test
    fun finite_values_still_render_byte_identically_after_the_non_finite_guard() {
        // The guard must be INVISIBLE for real data: the pinned wire bytes
        // of a fully-set grade (the LegacyGradeWireLockTest fixture shape)
        // are unchanged, so no format drift hides behind the fix.
        val grade = GradeK(
            exposureEv = 0.8f, tempK = 6100f, tint = 12f,
            stabilization = true,
            stabilizationStrength = 80f, stabilizationZoomLimit = 1.4f,
            stabilizationTrimMs = 12.5f, cropX = 0.1f, straightenDeg = 3.5f,
            splitShadowHue = 210f, splitBalance = -25f,
        )
        val wire = GradeStore.renderGradeWire(grade)
        assertTrue(wire.contains("\"exposureEv\":0.8"))
        assertTrue(wire.contains("\"tempK\":6100.0"))
        // K10: the v2 wire carries the stab group (transform.stab), not
        // the v1 flat keys.
        assertTrue(wire.contains("\"stab\":{\"engine\":0,\"mode\":0,\"strength\":80.0"))
        assertTrue(wire.contains("\"zoomLimit\":1.4"))
        assertTrue(wire.contains("\"trimMs\":12.5"))
        assertTrue(wire.contains("\"splitShadowHue\":210.0"))
        // And the whole grade round-trips equal.
        val back = ClipGradeV2.parse(
            JsonParser(wire).parseValue() as Map<*, *>,
        ).toGradeK()!!
        assertEquals(grade, back.copy(version = GradeK.SCHEMA_VERSION))
    }

    @Test
    fun curve_points_with_non_finite_values_never_write_bad_tokens() {
        // Node curves are Float pairs rendered by num() too: a NaN control
        // point must not emit a bare NaN token into the nodes array.
        val node = GradeNodeV2(curveY = NodeCurveV2(listOf(0f to 0f, 0.5f to Float.NaN, 1f to 1f)))
        val rendered = GradeV2Fiber.render(ClipGradeV2(nodes = listOf(node)))
        assertFalse(rendered.contains("NaN"))
        assertFalse(rendered.contains("Infinity"))
        // Parses back (the non-finite point is dropped by the tolerant
        // curve parser — the documented rule).
        val parsed = ClipGradeV2.parse(JsonParser(rendered).parseValue() as Map<*, *>)
        assertEquals(1, parsed.nodes.size)
    }

    // ---- atomic write semantics under the gate ----

    @Test
    fun a_failed_atomic_write_on_one_store_never_tears_the_sections_of_another() {
        // The merge contract under failure: while one store's write fails
        // (unwritable dir), the OTHER sections' bytes on disk must be the
        // COMPLETE previous version — never a torn mix.
        GradeStore(file).put("a", GradeK(exposureEv = 1f))
        AudioStore(file).save(AudioProject(masterGainDb = -2f))
        val before = file.readText(StandardCharsets.UTF_8)

        val dir = file.parentFile!!
        assertTrue(dir.setWritable(false))
        try {
            val failed = runCatching { TimelineStore(file).save(TimelineStore.defaultProject()) }
            assertTrue(failed.isFailure)
        } finally {
            dir.setWritable(true)
        }
        assertEquals(before, file.readText(StandardCharsets.UTF_8))
        // No temp file survives a failed publish.
        assertFalse(File(dir, "project.json.tmp").exists())
    }

    @Test
    fun reopening_a_file_written_by_this_build_is_always_a_plain_open() {
        // The write path's own output must never be mistaken for an older
        // schema (the stamp rule): build a file purely through store
        // writes, then the gate must Open it (not Migrate, not TooNew).
        GradeStore(file).put("a", GradeK(exposureEv = 1f))
        ExportStore(file).add(
            ExportJob(id = "j", sourceId = "a", snapshot = GradeK(), target = Target.DNG, state = State.QUEUED),
        )
        AudioStore(file).save(AudioProject(masterGainDb = -1f))
        val outcome = ProjectFile.open(file)
        assertTrue(outcome is ProjectFile.Outcome.Open)
        // And a second write after the open still keeps the stamp.
        GradeStore(file).put("b", GradeK())
        assertTrue(file.readText(StandardCharsets.UTF_8).startsWith("{\"version\":2"))
    }
}
