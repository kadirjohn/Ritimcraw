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
    fun clear_empties_queue() {
        val file = projectFile()
        val store = ExportStore(file)
        store.add(job(id = "a"))
        store.add(job(id = "b"))

        store.clear()

        assertTrue(ExportStore(file).all().isEmpty())
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
