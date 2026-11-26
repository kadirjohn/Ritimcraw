package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.FakeResolver
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.LutEncodingK
import com.ritim.ritimcraw.data.LutGamut
import com.ritim.ritimcraw.data.LutInterpK
import com.ritim.ritimcraw.data.LutRole
import com.ritim.ritimcraw.data.LutTransfer
import com.ritim.ritimcraw.data.SourceRepository
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.engine.RenderBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun temporal_nr_snapshot_fields_survive_reload() {
        // The export snapshot carries the temporal NR fields (DN-05..DN-09)
        // end to end: a queued job exports with the temporal stage the user
        // actually set, and an old queue without the fields reloads with
        // temporal NR off (never on behind the user's back).
        val file = projectFile()
        val snapshot = GradeK(
            exposureEv = 0.75f,
            temporalNr = true,
            temporalStrength = 55f,
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
        assertEquals(true, reloaded.temporalNr)
        assertEquals(55f, reloaded.temporalStrength, 0f)
    }

    @Test
    fun stabilization_snapshot_fields_survive_reload() {
        // Gyro Phase 5: the export snapshot carries the stabilization fields
        // end to end, so a queued job exports with the warp the user saw when
        // enqueuing — and an old queue without the fields reloads with
        // stabilization off (never on behind the user's back).
        val file = projectFile()
        val snapshot = GradeK(
            exposureEv = 0.75f,
            stabilization = true,
            stabilizationMode = 1, // Lock First Frame
            stabilizationStrength = 80f,
            stabilizationZoomLimit = 1.3f,
            stabilizationTrimMs = -30f,
            stabilizationFocalMm = 23.5f,
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
        assertEquals(true, reloaded.stabilization)
        assertEquals(1, reloaded.stabilizationMode)
        assertEquals(80f, reloaded.stabilizationStrength, 0f)
        assertEquals(1.3f, reloaded.stabilizationZoomLimit, 0f)
        assertEquals(-30f, reloaded.stabilizationTrimMs, 0f)
        assertEquals(23.5f, reloaded.stabilizationFocalMm, 0f)
    }

    @Test
    fun stab_engine_snapshot_writes_only_for_image_and_old_rows_read_gyro() {
        // S4 + F1-3: the snapshot grade rides the v2 wire (the folded
        // transform.stab group with engine explicit), an image grade's
        // snapshot carries engine 1, and an old flat row (no stab group)
        // parses as engine 0 = gyro. The K10 wire shape replaces the flat
        // stabilizationEngine key; the parse-side contract is unchanged.
        val file = projectFile()
        val image = GradeK(stabilization = true, stabilizationEngine = 1)
        ExportStore(file).add(
            ExportJob(
                id = "image-job",
                sourceId = "clip-a",
                snapshot = image,
                target = Target.MP4,
                state = State.QUEUED,
            )
        )
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text, text.contains("\"stab\":{\"engine\":1"))
        assertEquals(1, ExportStore(file).all().single().snapshot.stabilizationEngine)

        // A gyro grade writes engine 0 EXPLICIT (the K10 rule: the group
        // always names the engine) and reloads as engine 0.
        file.delete()
        val gyro = GradeK(stabilization = true)
        ExportStore(file).add(
            ExportJob(
                id = "gyro-job",
                sourceId = "clip-a",
                snapshot = gyro,
                target = Target.MP4,
                state = State.QUEUED,
            )
        )
        val gyroText = file.readText(Charsets.UTF_8)
        assertTrue(gyroText, gyroText.contains("\"stab\":{\"engine\":0"))
        assertEquals(0, ExportStore(file).all().single().snapshot.stabilizationEngine)
    }

    @Test
    fun old_snapshot_without_stabilization_fields_loads_them_off() {
        // A queue written by the pre-stabilization build: the snapshot JSON has
        // no stabilization fields, so they parse as off and the queued job
        // keeps its exact old look (the warp is a no-op when off).
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.5,"tonemap":1,"temporalNr":true,"temporalStrength":40.0},"target":"MP4","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val snapshot = ExportStore(file).all().single().snapshot
        assertEquals(false, snapshot.stabilization)
        assertEquals(0, snapshot.stabilizationMode)
        assertEquals(50f, snapshot.stabilizationStrength, 0f)
        assertEquals(1.25f, snapshot.stabilizationZoomLimit, 0f)
        assertEquals(0f, snapshot.stabilizationTrimMs, 0f)
        assertEquals(0f, snapshot.stabilizationFocalMm, 0f)
    }

    @Test
    fun off_snapshot_renders_without_stabilization_keys() {
        // Omit-when-off at the QUEUE level: a job queued with stabilization
        // off must render no stabilization keys, so rows written by this
        // build for off jobs stay byte-identical to the pre-stabilization
        // format (the same binding rule as the grade store).
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "plain"))
        val text = file.readText()
        assertTrue(!text.contains("stabilization"))
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
              {"id":"old-dn-job","sourceId":"clip-b","snapshot":{"version":1,"exposureEv":0.5,"tempK":5600.0,"tint":0.0},"target":"MP4","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val old = ExportStore(file).all().single { it.id == "old-dn-job" }
        assertEquals(0f, old.snapshot.noiseReduction, 0f)
        assertEquals(0f, old.snapshot.chromaDenoise, 0f)
    }

    @Test
    fun old_snapshot_without_temporal_fields_loads_them_off() {
        // A queue written by the pre-temporal-NR build: the snapshot JSON has
        // no temporal fields, so they parse as off/0 and the queued job keeps
        // its exact old look (temporal stage is a no-op when off).
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.5,"tonemap":1},"target":"MP4","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val snapshot = ExportStore(file).all().single().snapshot
        assertEquals(false, snapshot.temporalNr)
        assertEquals(0f, snapshot.temporalStrength, 0f)
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

    // ---- delete(ids) (owner request 2026-11-26: individual rows) ----

    @Test
    fun delete_removes_only_the_named_terminal_rows() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a", state = State.DONE))
        store.add(job(id = "b", state = State.FAILED))
        store.add(job(id = "c", state = State.CANCELLED))

        val removed = store.delete(setOf("a", "c"))

        assertEquals(2, removed)
        assertEquals(listOf("b"), ExportStore(file).all().map { it.id })
        assertEquals(State.FAILED, ExportStore(file).all().single().state)
    }

    @Test
    fun delete_never_removes_the_two_live_states() {
        // LIVE-SAFETY: a QUEUED or RUNNING row can only be cancelled —
        // deleting a live row would orphan the encoder the queue still
        // drives. The store repeats the rule at the write, under its
        // lock, so a race can never remove a live row.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "q", state = State.QUEUED))
        store.add(job(id = "r", state = State.RUNNING))
        store.add(job(id = "d", state = State.DONE))

        val removed = store.delete(setOf("q", "r", "d"))

        assertEquals(1, removed)
        assertEquals(
            listOf("q", "r"),
            ExportStore(file).all().map { it.id },
        )
        assertEquals(State.QUEUED, ExportStore(file).all()[0].state)
        assertEquals(State.RUNNING, ExportStore(file).all()[1].state)
    }

    @Test
    fun delete_is_a_noop_for_unknown_and_missing_ids() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a", state = State.DONE))

        // Unknown id: nothing removed, no write, queue unchanged.
        assertEquals(0, store.delete(setOf("nope")))
        // Empty set: no write at all.
        assertEquals(0, store.delete(emptySet()))
        // An already-deleted id asked twice: the second call is a no-op.
        assertEquals(1, store.delete(setOf("a")))
        assertEquals(0, store.delete(setOf("a")))

        assertTrue(ExportStore(file).all().isEmpty())
    }

    @Test
    fun delete_persists_and_keeps_the_survivors_order() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a", state = State.DONE))
        store.add(job(id = "b", state = State.DONE))
        store.add(job(id = "c", state = State.DONE))
        store.add(job(id = "d", state = State.DONE))

        store.delete(setOf("b", "d"))

        // New store over the same file = app close + reopen: the removal
        // persisted and the queue order of the survivors is intact.
        assertEquals(listOf("a", "c"), ExportStore(file).all().map { it.id })
    }

    @Test
    fun delete_preserves_the_grades_and_sources_sections() {
        // The same merge-aware write clear() runs: deleting rows must not
        // touch the sibling sections of project.json.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"clip-a":{"exposureEv":0.5}},"sources":[]}
            """.trimIndent(),
        )
        val store = ExportStore(file)
        store.add(job(id = "a", state = State.DONE))

        store.delete(setOf("a"))

        val text = ExportStore(file).let { file.readText() }
        assertTrue(text.contains("\"grades\""))
        assertTrue(text.contains("clip-a"))
        assertTrue(text.contains("\"sources\""))
        assertTrue(ExportStore(file).all().isEmpty())
    }

    @Test
    fun old_export_row_with_started_at_still_parses() {
        // A project.json written while the ETA was anchored on startedAtMs
        // (schema 1, the field since removed — the estimate is display-side
        // EMA now, see ExportEta): the unknown field is ignored and the row
        // parses with its counts intact.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"MP4","state":"RUNNING","framesDone":10,"framesTotal":310,"startedAtMs":1730000000000}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(State.RUNNING, job.state)
        assertEquals(10, job.framesDone)
        assertEquals(310, job.framesTotal)
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
    fun frame_index_round_trips_and_omits_when_zero() {
        // RAW-11 Frame Picker: the picked frame persists with the job (a
        // crash between queue and drain still develops the right frame),
        // and omit-when-zero keeps pre-picker files byte-identical — the
        // same convention as outputName.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            job(id = "picked", target = Target.PHOTO).copy(frameIndex = 7)
        )
        store.add(job(id = "plain", target = Target.DNG)) // frame 0 = default

        val jobs = ExportStore(file).all()
        val picked = jobs.single { it.id == "picked" }
        assertEquals(7, picked.frameIndex)
        val plain = jobs.single { it.id == "plain" }
        assertEquals(0, plain.frameIndex)

        // Only the non-zero index is written: the plain row's JSON carries
        // no frameIndex key (legacy rows stay byte-identical).
        val text = file.readText()
        assertTrue(text.contains("\"frameIndex\":7"))
        val plainRow = text.substringAfter("\"id\":\"plain\"")
        assertTrue(!plainRow.substringBefore("}").contains("frameIndex"))

        // Retry keeps the picked frame: the retried photo develops the
        // SAME frame, not frame 0 (requeued() preserves it via copy()).
        store.retry("picked")
        assertEquals(7, ExportStore(file).all().single { it.id == "picked" }.frameIndex)
    }

    @Test
    fun old_export_row_without_frame_index_parses_zero() {
        // A project.json written before the Frame Picker (schema 1, no
        // frameIndex field): the row parses with frame 0 — the exact frame
        // every pre-picker job always exported, so nothing changes.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"DNG","state":"DONE","detail":"clip-a-dng.dng"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(0, job.frameIndex)
    }

    @Test
    fun manual_rotation_round_trips_and_omits_when_zero() {
        // §5 (owner request 2026-11-11): the manual rotation delta the job
        // snapshotted at enqueue persists with it (a crash between queue
        // and drain still exports the rotated composition), and
        // omit-when-zero keeps pre-rotation files byte-identical — the
        // same convention as frameIndex.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            job(id = "rotated", target = Target.MP4).copy(manualRotationDeg = 270)
        )
        store.add(job(id = "plain", target = Target.DNG)) // 0 = default

        val jobs = ExportStore(file).all()
        assertEquals(270, jobs.single { it.id == "rotated" }.manualRotationDeg)
        assertEquals(0, jobs.single { it.id == "plain" }.manualRotationDeg)

        // Only the non-zero delta is written: the plain row's JSON carries
        // no manualRotationDeg key (legacy rows stay byte-identical).
        val text = file.readText()
        assertTrue(text.contains("\"manualRotationDeg\":270"))
        val plainRow = text.substringAfter("\"id\":\"plain\"")
        assertTrue(!plainRow.substringBefore("}").contains("manualRotationDeg"))

        // Retry keeps the rotation: the retried export composes the same
        // effective orientation (requeued() preserves it via copy()).
        store.retry("rotated")
        assertEquals(270, ExportStore(file).all().single { it.id == "rotated" }.manualRotationDeg)
    }

    @Test
    fun old_export_row_without_manual_rotation_parses_zero() {
        // A project.json written before manual rotation (schema 1, no
        // manualRotationDeg field): the row parses with 0 — container
        // orientation alone, exactly what every pre-rotation job always
        // exported.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"DNG","state":"DONE","detail":"clip-a-dng.dng"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(0, job.manualRotationDeg)
    }

    @Test
    fun render_backend_round_trips_and_omits_when_default() {
        // GPU deliverable F (owner request 2026-11-12 §10): the backend the
        // job snapshotted at enqueue persists with it (a crash between queue
        // and drain still exports with the requested backend), and
        // omit-when-default keeps pre-GPU files byte-identical — the same
        // convention as manualRotationDeg.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            job(id = "gpu", target = Target.MP4).copy(renderBackend = RenderBackend.GPU_EXPERIMENTAL)
        )
        store.add(job(id = "cpu", target = Target.MP4)) // CPU = default

        val jobs = ExportStore(file).all()
        assertEquals(RenderBackend.GPU_EXPERIMENTAL, jobs.single { it.id == "gpu" }.renderBackend)
        assertEquals(RenderBackend.CPU, jobs.single { it.id == "cpu" }.renderBackend)

        // Only the non-default backend is written: the CPU row's JSON carries
        // no renderBackend key (legacy rows stay byte-identical).
        val text = file.readText()
        assertTrue(text.contains("\"renderBackend\":\"GPU_EXPERIMENTAL\""))
        val cpuRow = text.substringAfter("\"id\":\"cpu\"")
        assertTrue(!cpuRow.substringBefore("}").contains("renderBackend"))

        // Retry keeps the backend: the retried export asks for the same
        // render path (requeued() preserves it via copy()).
        store.retry("gpu")
        assertEquals(
            RenderBackend.GPU_EXPERIMENTAL,
            ExportStore(file).all().single { it.id == "gpu" }.renderBackend,
        )
    }

    @Test
    fun old_export_row_without_render_backend_parses_cpu() {
        // A project.json written before the GPU setting (schema 1, no
        // renderBackend field): the row parses with CPU — the reference
        // path, exactly what every pre-GPU job always ran.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"DNG","state":"DONE","detail":"clip-a-dng.dng"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(RenderBackend.CPU, job.renderBackend)
    }

    @Test
    fun unknown_render_backend_value_parses_cpu() {
        // A value this build does not know (written by a future build with
        // more backends): fromWire maps it to CPU — the reference path,
        // never a crash, never a silent behavior change beyond that.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"future-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"MP4","state":"QUEUED","renderBackend":"SOME_FUTURE_BACKEND"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertEquals(RenderBackend.CPU, job.renderBackend)
    }

    @Test
    fun resolution_round_trips_and_omits_when_null() {
        // Export resolution (owner request 2026-11-13): the resolved bound
        // the job snapshotted at enqueue persists with it (a crash between
        // queue and drain still exports the chosen size), and
        // omit-when-null keeps pre-picker files byte-identical — the same
        // convention as manualRotationDeg and renderBackend.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            job(id = "fhd", target = Target.MP4).copy(resolutionW = 1920, resolutionH = 1080)
        )
        store.add(job(id = "plain", target = Target.MP4)) // null/null = Original

        val jobs = ExportStore(file).all()
        assertEquals(1920, jobs.single { it.id == "fhd" }.resolutionW)
        assertEquals(1080, jobs.single { it.id == "fhd" }.resolutionH)
        assertNull(jobs.single { it.id == "plain" }.resolutionW)
        assertNull(jobs.single { it.id == "plain" }.resolutionH)

        // Only a chosen bound is written, and the pair travels TOGETHER:
        // the plain row's JSON carries neither key (legacy rows stay
        // byte-identical). The plain row is the file's LAST row and this
        // fresh project.json holds only the exports section, so the full
        // remainder after its id is exactly the plain row's tail.
        val text = file.readText()
        assertTrue(text.contains("\"resolutionW\":1920,\"resolutionH\":1080"))
        val plainRow = text.substringAfter("\"id\":\"plain\"")
        assertTrue(!plainRow.contains("resolutionW"))
        assertTrue(!plainRow.contains("resolutionH"))

        // Retry keeps the bound: the retried export encodes the same
        // chosen size (requeued() preserves it via copy()).
        store.retry("fhd")
        val retried = ExportStore(file).all().single { it.id == "fhd" }
        assertEquals(1920, retried.resolutionW)
        assertEquals(1080, retried.resolutionH)
    }

    @Test
    fun audio_link_snapshot_round_trips_omits_when_null_and_survives_retry() {
        // QA-15 / EX-11: the audio choice snapshotted at enqueue persists
        // with the job (a kill between queue and drain keeps it) and Retry
        // keeps it; omit-when-null keeps pre-snapshot rows byte-identical.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            job(id = "det", target = Target.MP4).copy(audioLink = AudioLinkState.DETACHED)
        )
        store.add(job(id = "plain", target = Target.MP4))

        val jobs = ExportStore(file).all()
        assertEquals(AudioLinkState.DETACHED, jobs.single { it.id == "det" }.audioLink)
        assertNull(jobs.single { it.id == "plain" }.audioLink)

        val text = file.readText()
        assertTrue(text.contains("\"audioLink\":\"DETACHED\""))
        assertTrue(!text.substringAfter("\"id\":\"plain\"").contains("audioLink"))

        store.retry("det")
        assertEquals(
            AudioLinkState.DETACHED,
            ExportStore(file).all().single { it.id == "det" }.audioLink,
        )
    }

    @Test
    fun unknown_audio_link_value_parses_null_not_a_guess() {
        // A future link state this build does not know parses as null —
        // the exporter then uses the live lookup (never a guessed state).
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "x", target = Target.MP4).copy(audioLink = AudioLinkState.REMOVED))
        file.writeText(file.readText().replace("\"REMOVED\"", "\"SOME_FUTURE_STATE\""))
        assertNull(ExportStore(file).all().single().audioLink)
    }

    @Test
    fun old_export_row_without_resolution_parses_null() {
        // A project.json written before the resolution picker (schema 1,
        // no resolutionW/H fields): the row parses with null/null —
        // Original, the source's effective size, exactly what every
        // pre-picker job always exported.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.0},"target":"MP4","state":"DONE","detail":"clip-a.mp4"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val job = ExportStore(file).all().single()
        assertNull(job.resolutionW)
        assertNull(job.resolutionH)
    }

    @Test
    fun half_pair_renders_neither_key() {
        // The render's both-or-neither gate: a job that somehow carries
        // only half a pair (W set, H null — never written by the sheet,
        // but the store must not guess a bound) renders NEITHER key. The
        // pair is one decision; half a decision is no decision, and the
        // exporter's own both-non-null gate treats the reloaded row as
        // Original — the honest fallback, never a guessed bound.
        val file = projectFile()
        val store = ExportStore(file)
        store.add(
            job(id = "half", target = Target.MP4).copy(resolutionW = 1920) // H stays null
        )

        val text = file.readText()
        // The half row is the file's only row, so the full remainder after
        // its id is exactly its tail.
        val halfRow = text.substringAfter("\"id\":\"half\"")
        assertTrue(!halfRow.contains("resolutionW"))
        assertTrue(!halfRow.contains("resolutionH"))

        // And the reloaded row is a clean null pair.
        val job = ExportStore(file).all().single()
        assertNull(job.resolutionW)
        assertNull(job.resolutionH)
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

    @Test
    fun snapshot_round_trips_the_managed_lut_interpretation() {
        // Owner request 2026-11-13: the snapshot carries the FULL
        // interpretation BY VALUE at enqueue — the job exports exactly
        // what the user saw.
        val file = projectFile()
        val interp = LutInterpK(
            input = LutEncodingK(LutGamut.DaVinciWideGamut, LutTransfer.DaVinciIntermediate),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneToDisplay,
        )
        val snapshot = GradeK(lutId = "abc123def4567890", lutInterp = interp)
        ExportStore(file).add(
            ExportJob(
                id = "managed-lut-job",
                sourceId = "clip-a",
                snapshot = snapshot,
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        val job = ExportStore(file).all().single { it.id == "managed-lut-job" }
        assertEquals(interp, job.snapshot.lutInterp)
        assertEquals(snapshot, job.snapshot)
    }

    @Test
    fun old_snapshot_without_interp_parses_null_and_renders_identical() {
        // Byte-identity both ways: a pre-managed queue row parses with a
        // null interpretation (the legacy stage-24 path keyed on lutId
        // alone), and a null-interpretation snapshot renders back WITHOUT
        // the lutInterp key — the old look and the old file both survive.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"exports":[
              {"id":"old-job","sourceId":"clip-b","snapshot":{"version":1,"exposureEv":0.5,"tempK":5600.0,"tint":0.0,"lutId":"abc123def4567890","lutStrength":80.0},"target":"MP4","state":"QUEUED"}
            ]}
            """.trimIndent(),
            Charsets.UTF_8,
        )
        val old = ExportStore(file).all().single()
        assertNull(old.snapshot.lutInterp)
        assertEquals("abc123def4567890", old.snapshot.lutId)

        // Rewriting the parsed row keeps the file's lutInterp absent.
        ExportStore(file).update(old)
        val rewritten = file.readText(Charsets.UTF_8)
        assertEquals(false, rewritten.contains("lutInterp"))
    }

    @Test
    fun retry_keeps_the_snapshot_interpretation_invariant() {
        // The retry contract: requeued() keeps the SAME snapshot, so a
        // retried job develops with exactly the interpretation captured
        // at enqueue — even if the library record is later removed or
        // "edited" (an edit creates a new revision; records are
        // immutable). The interpretation rides INSIDE the snapshot by
        // value, so nothing outside the job can change it.
        val file = projectFile()
        val interp = LutInterpK(
            input = LutEncodingK(LutGamut.Rec2020, LutTransfer.Linear),
            output = LutEncodingK(LutGamut.Rec709, LutTransfer.Rec709OETF),
            role = LutRole.SceneLook,
        )
        ExportStore(file).add(
            ExportJob(
                id = "retry-job",
                sourceId = "clip-a",
                snapshot = GradeK(lutId = "abc123def4567890", lutInterp = interp),
                target = Target.MP4,
                state = State.FAILED,
            )
        )

        ExportStore(file).retry("retry-job")

        val requeued = ExportStore(file).all().single { it.id == "retry-job" }
        assertEquals(interp, requeued.snapshot.lutInterp)
        assertEquals(State.QUEUED, requeued.state)
    }

    private fun projectFile(): File = File(tmp.root, "project.json")
}
