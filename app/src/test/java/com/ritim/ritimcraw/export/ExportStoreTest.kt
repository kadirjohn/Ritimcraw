package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.FakeResolver
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.SourceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ExportStore tests (sprint plan Task 5, device-independent half):
 *  - jobs persist across a store reload (app close + reopen).
 *  - state transitions persist.
 *  - retry re-queues with the same snapshot.
 *  - merge-aware writes preserve the grades and sources sections.
 *  - FAILED jobs keep their detail (stage + cause) across persistence.
 */
class ExportStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun job_persists_across_store_reload() {
        val file = projectFile()
        ExportStore(file).add(
            ExportJob(
                id = "job-1",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 1.25f, tempK = 3200f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        // New store over the same file = app close + reopen.
        val reloaded = ExportStore(file).all()

        val job = reloaded.single()
        assertEquals("job-1", job.id)
        assertEquals("clip-a", job.sourceId)
        assertEquals(Target.MP4, job.target)
        assertEquals(State.QUEUED, job.state)
        assertEquals(1.25f, job.snapshot.exposureEv, 0f)
        assertEquals(3200f, job.snapshot.tempK, 0f)
        assertNull(job.detail)
    }

    @Test
    fun queue_order_persists_across_reload() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a", target = Target.DNG))
        store.add(job(id = "b", target = Target.MP4))
        store.add(job(id = "c", target = Target.DNG))

        val reloaded = ExportStore(file).all()

        assertEquals(listOf("a", "b", "c"), reloaded.map { it.id })
        assertEquals(listOf(Target.DNG, Target.MP4, Target.DNG), reloaded.map { it.target })
    }

    @Test
    fun state_transitions_persist() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        store.update(job(id = "a", state = State.RUNNING))
        store.update(
            job(id = "a", state = State.DONE, detail = "clip-a.mp4"),
        )

        val reloaded = ExportStore(file).all()
        assertEquals(State.DONE, reloaded.single().state)
        assertEquals("clip-a.mp4", reloaded.single().detail)
    }

    @Test
    fun update_replaces_in_place_keeping_queue_position() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        store.add(job(id = "b"))
        store.add(job(id = "c"))

        store.update(job(id = "b", state = State.RUNNING))

        val jobs = ExportStore(file).all()
        assertEquals(listOf("a", "b", "c"), jobs.map { it.id })
        assertEquals(State.RUNNING, jobs[1].state)
    }

    @Test
    fun retry_requeues_same_snapshot() {
        val file = projectFile()
        val store = ExportStore(file)
        val snapshot = GradeK(exposureEv = -0.5f, highlights = 30f, tempK = 6500f, tint = 5f)
        store.add(
            ExportJob(
                id = "a",
                sourceId = "clip-a",
                snapshot = snapshot,
                target = Target.DNG,
                state = State.QUEUED,
            )
        )
        store.update(
            ExportJob(
                id = "a",
                sourceId = "clip-a",
                snapshot = snapshot,
                target = Target.DNG,
                state = State.FAILED,
                detail = "encode: MediaCodec configure rejected the encoder",
            )
        )

        store.retry("a")

        val job = ExportStore(file).all().single()
        assertEquals(State.QUEUED, job.state)
        assertNull(job.detail)
        assertEquals(snapshot, job.snapshot) // same grade snapshot
        assertEquals(Target.DNG, job.target)
    }

    @Test
    fun failed_job_keeps_detail_across_reload() {
        val file = projectFile()
        val store = ExportStore(file)
        val detail = "mux: MediaMuxer stopped before the moov box was written"
        store.add(job(id = "a", state = State.FAILED, detail = detail))

        val job = ExportStore(file).all().single()
        assertEquals(State.FAILED, job.state)
        assertEquals(detail, job.detail)
    }

    @Test
    fun running_progress_counts_persist_across_reload() {
        // The throttled progress write must survive a reload (crash mid-
        // export) so a restarted UI can show the last known count — and a
        // recovery pass then clears it with the rest of the row.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a", state = State.RUNNING))
        store.update(
            store.all().single().copy(framesDone = 142, framesTotal = 310)
        )

        val job = ExportStore(file).all().single()
        assertEquals(State.RUNNING, job.state)
        assertEquals(142, job.framesDone)
        assertEquals(310, job.framesTotal)

        // Jobs without counts (the old rows, and terminal rows) still parse
        // with null counts — the fields are optional in the JSON.
        store.update(store.all().single().copy(framesDone = null, framesTotal = null))
        assertNull(ExportStore(file).all().single().framesDone)
        assertNull(ExportStore(file).all().single().framesTotal)
    }

    @Test
    fun merge_aware_write_preserves_grades_and_sources_sections() {
        val file = projectFile()
        val sources = SourceRepository(FakeResolver("clip".toByteArray()), file)
        val imported = sources.import("content://clip/1")
        GradeStore(file).put(imported.id, GradeK(exposureEv = 1.5f))

        // An export write must not clobber grades or sources.
        ExportStore(file).add(job(id = "a", sourceId = imported.id))

        assertEquals(1.5f, GradeStore(file).get(imported.id).exposureEv, 0f)
        assertEquals(1, SourceRepository(FakeResolver(), file).all().size)
        assertEquals(1, ExportStore(file).all().size)

        // And a later grades/sources write must not clobber exports.
        GradeStore(file).put(imported.id, GradeK(exposureEv = 2f))
        assertEquals(1, ExportStore(file).all().size)
        assertEquals(0f, ExportStore(file).all().single().snapshot.exposureEv, 0f)
    }

    @Test
    fun snapshot_is_not_live_grade() {
        // Editing the clip's grade after queueing must not change the
        // snapshot the job will export with.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            ExportJob(
                id = "a",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 0f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )
        GradeStore(file).put("clip-a", GradeK(exposureEv = 3f))

        assertEquals(0f, ExportStore(file).all().single().snapshot.exposureEv, 0f)
        assertEquals(3f, GradeStore(file).get("clip-a").exposureEv, 0f)
    }

    @Test
    fun tone_control_snapshot_fields_survive_reload() {
        // The export snapshot carries the six tone/color fields end to end:
        // what the user graded is exactly what the queued job reloads with.
        val file = projectFile()
        val snapshot = GradeK(
            exposureEv = 0.75f,
            saturation = 50f,
            blackPoint = -30f,
            whitePoint = 70f,
            shadows = -20f,
            midtones = 15f,
            contrast = 85f,
        )
        ExportStore(file).add(
            ExportJob(
                id = "a",
                sourceId = "clip-a",
                snapshot = snapshot,
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        val reloaded = ExportStore(file).all().single().snapshot
        assertEquals(snapshot, reloaded)
    }

    @Test
    fun raw_toggle_snapshot_fields_survive_reload() {
        // The export snapshot carries the three RAW metadata booleans end to
        // end: a queued job exports with the WB source and stage toggles the
        // user actually set, not the defaults.
        val file = projectFile()
        val snapshot = GradeK(
            exposureEv = 0.75f,
            tempK = 4300f,
            tint = -6f,
            asShot = false,
            highlightReconstruction = false,
            keepVignetting = false,
        )
        ExportStore(file).add(
            ExportJob(
                id = "a",
                sourceId = "clip-a",
                snapshot = snapshot,
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        val reloaded = ExportStore(file).all().single().snapshot
        assertEquals(snapshot, reloaded)
        assertEquals(false, reloaded.asShot)
        assertEquals(false, reloaded.highlightReconstruction)
        assertEquals(false, reloaded.keepVignetting)
    }

    @Test
    fun old_snapshot_without_toggles_defaults_stages_true_and_derives_asshot() {
        // A queue written by the pre-RAW-toggles build. The snapshot JSON
        // has no booleans: the stage toggles default true (pre-toggle
        // behavior), and asShot DERIVES from temp/tint neutrality so the
        // queued job re-renders with its exact old look. RAW-07/RAW-04/
        // RAW-06 binding requirement.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"neutral-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.5,"tempK":5600.0,"tint":0.0},"target":"MP4","state":"QUEUED"},
              {"id":"manual-job","sourceId":"clip-b","snapshot":{"version":1,"exposureEv":0.5,"tempK":4600.0,"tint":3.0},"target":"DNG","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val jobs = ExportStore(file).all()
        assertEquals(2, jobs.size)
        val neutral = jobs.first { it.id == "neutral-job" }.snapshot
        assertTrue(neutral.asShot) // neutral manual WB -> as-shot WB
        assertTrue(neutral.highlightReconstruction)
        assertTrue(neutral.keepVignetting)
        val manual = jobs.first { it.id == "manual-job" }.snapshot
        assertTrue(!manual.asShot) // manual WB saved -> manual WB wins
        assertTrue(manual.highlightReconstruction)
        assertTrue(manual.keepVignetting)
        assertEquals(4600f, manual.tempK, 0f)
        assertEquals(3f, manual.tint, 0f)
    }

    @Test
    fun snapshot_round_trips_lut_selection_and_strength() {
        // COL-07: the job snapshot carries the LUT fields with the grade, so
        // the export applies the same selection + Strength the user saw when
        // enqueuing — and an old (pre-LUT) snapshot parses as no LUT.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            ExportJob(
                id = "lut-job",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 1f, lutId = "abc123def4567890", lutStrength = 62f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        val job = ExportStore(file).all().single { it.id == "lut-job" }
        assertEquals("abc123def4567890", job.snapshot.lutId)
        assertEquals(62f, job.snapshot.lutStrength, 0f)

        // A pre-LUT snapshot (no lutId/lutStrength in the JSON) parses
        // neutral: no selection, full strength — the export keeps the old
        // look instead of failing the job.
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-b","snapshot":{"version":1,"exposureEv":0.5,"tempK":5600.0,"tint":0.0},"target":"MP4","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val old = ExportStore(file).all().single { it.id == "old-job" }
        assertNull(old.snapshot.lutId)
        assertEquals(100f, old.snapshot.lutStrength, 0f)
    }

    @Test
    fun snapshot_round_trips_denoise_percents() {
        // DN-01..08: the job snapshot carries both denoise percents with the
        // grade, so the export runs the same stages 7/8 the user saw when
        // enqueuing — and an old (pre-DN) snapshot parses as neutral.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            ExportJob(
                id = "dn-job",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 1f, noiseReduction = 28f, chromaDenoise = 64f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        val job = ExportStore(file).all().single { it.id == "dn-job" }
        assertEquals(28f, job.snapshot.noiseReduction, 0f)
        assertEquals(64f, job.snapshot.chromaDenoise, 0f)

        // A pre-DN snapshot (no noiseReduction/chromaDenoise in the JSON)
        // parses neutral: both 0 = the stages skipped — the queued job keeps
        // its exact old look instead of failing.
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-b","snapshot":{"version":1,"exposureEv":0.5,"tempK":5600.0,"tint":0.0},"target":"MP4","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val old = ExportStore(file).all().single { it.id == "old-job" }
        assertEquals(0f, old.snapshot.noiseReduction, 0f)
        assertEquals(0f, old.snapshot.chromaDenoise, 0f)
    }

    @Test
    fun clear_empties_queue() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        store.add(job(id = "b"))

        store.clear()

        assertTrue(ExportStore(file).all().isEmpty())
    }

    @Test
    fun started_at_persists_across_reload_and_clears_on_terminal() {
        // startedAtMs (ETA anchor, owner request 2026-11-11 §1): set on the
        // RUNNING write, omitted-when-null in the JSON (old files stay
        // byte-identical), cleared on terminal rows and on retry.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        store.update(
            store.all().single().copy(state = State.RUNNING, startedAtMs = 1_730_000_000_000L)
        )

        val running = ExportStore(file).all().single()
        assertEquals(1_730_000_000_000L, running.startedAtMs)

        // Terminal write clears the anchor (a DONE row never shows an ETA).
        store.update(
            store.all().single().copy(state = State.DONE, startedAtMs = null)
        )
        assertNull(ExportStore(file).all().single().startedAtMs)

        // Retry of a terminal row also clears it.
        store.retry("a")
        val requeued = ExportStore(file).all().single()
        assertEquals(State.QUEUED, requeued.state)
        assertNull(requeued.startedAtMs)
    }

    @Test
    fun old_export_row_without_started_at_parses_null() {
        // A project.json written before startedAtMs existed (schema 1, no
        // anchor field): the row parses with a null anchor and the UI simply
        // hides the ETA.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"MP4","state":"RUNNING","framesDone":10,"framesTotal":310}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(State.RUNNING, job.state)
        assertEquals(10, job.framesDone)
        assertEquals(310, job.framesTotal)
        assertNull(job.startedAtMs)
    }

    @Test
    fun published_uri_persists_across_reload_and_clears_on_retry() {
        // publishedUri (DONE rows open on tap, owner request 2026-11-11 §1):
        // stamped when the job's publish succeeded, omitted-when-null (old
        // files stay byte-identical), cleared on retry (the retried job
        // publishes afresh — its old row must not open the old file).
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        store.update(
            store.all().single().copy(state = State.DONE, publishedUri = "content://media/external/video/media/42")
        )

        val done = ExportStore(file).all().single()
        assertEquals("content://media/external/video/media/42", done.publishedUri)

        // Retry clears the stale publish: the job is QUEUED again with no
        // row to open.
        store.retry("a")
        val requeued = ExportStore(file).all().single()
        assertEquals(State.QUEUED, requeued.state)
        assertNull(requeued.publishedUri)
    }

    @Test
    fun old_export_row_without_published_uri_parses_null() {
        // A project.json written before publishedUri existed (schema 1, no
        // URI field): the row parses with a null URI and the open path falls
        // back to the job's own internal output file.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"MP4","state":"DONE","detail":"clip-a-mp4.mp4"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(State.DONE, job.state)
        assertEquals("clip-a-mp4.mp4", job.detail)
        assertNull(job.publishedUri)
    }

    // ---- DISPLAY-DERIVED OUTPUT NAMES (owner request 2026-11-11 §2b) ----

    @Test
    fun output_name_persists_across_reload_and_survives_retry() {
        // outputName is captured at ENQUEUE and stays for the job's whole
        // life: persisted across reloads, kept on retry (the retried job
        // overwrites its own output — one clip, one file name).
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a").copy(outputName = "Sunset Trip.dng"))

        val loaded = ExportStore(file).all().single()
        assertEquals("Sunset Trip.dng", loaded.outputName)
        assertEquals("Sunset Trip.dng", loaded.fileName)

        // Retry keeps the name (requeued() does not clear it).
        store.update(loaded.copy(state = State.FAILED, detail = "boom"))
        store.retry("a")
        val requeued = ExportStore(file).all().single()
        assertEquals(State.QUEUED, requeued.state)
        assertEquals("Sunset Trip.dng", requeued.outputName)
        assertEquals("Sunset Trip.dng", requeued.fileName)
    }

    @Test
    fun output_name_is_omitted_when_null_so_old_files_stay_byte_identical() {
        // omit-when-null: a job without a display-derived name renders
        // exactly as the pre-§2b build wrote it (schema-1 files stay
        // byte-identical), and the row parses back with the legacy name.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        val before = file.readText()

        // A null outputName write must not add the key.
        store.update(store.all().single().copy(state = State.RUNNING))
        val after = file.readText()
        assertTrue(!after.contains("outputName"))
        assertEquals(
            before,
            // Same JSON minus the state value: RUNNING replaced QUEUED.
            after.replace("\"state\":\"RUNNING\"", "\"state\":\"QUEUED\""),
        )

        // The row's file name falls back to the legacy form.
        assertEquals("clip-a-mp4.mp4", ExportStore(file).all().single().fileName)
    }

    @Test
    fun old_export_row_without_output_name_parses_null() {
        // A project.json written before §2b (schema 1, no outputName field):
        // the row parses with a null name and fileName falls back to the
        // legacy <sourceId>-<target> name — old DONE rows still open.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"MP4","state":"DONE","detail":"clip-a-mp4.mp4"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(State.DONE, job.state)
        assertNull(job.outputName)
        assertEquals("clip-a-mp4.mp4", job.fileName)
    }

    @Test
    fun corrupt_store_falls_back_to_empty_queue() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        assertTrue(ExportStore(file).all().isEmpty())
    }

    private fun job(
        id: String,
        sourceId: String = "clip-a",
        target: Target = Target.MP4,
        state: State = State.QUEUED,
        detail: String? = null,
    ) = ExportJob(
        id = id,
        sourceId = sourceId,
        snapshot = GradeK(),
        target = target,
        state = state,
        detail = detail,
    )

    private fun projectFile(): File = File(tmp.root, "project.json")
}
