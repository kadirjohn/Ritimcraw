package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ClipDeletion tests (owner finding 2026-11-11 E2: Library delete):
 * the composed delete removes the record, the grade and the thumbnail
 * PNG; missing ids are no-ops everywhere; other clips are untouched; the
 * user's original file concept (the SAF uri) is never written through
 * any of it; and delete-then-reimport starts FRESH — the same file keeps
 * its fingerprint id, and with the grade row gone the reimported clip
 * reads the neutral default, never a stale grade.
 */
class ClipDeletionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun projectFile(): File = File(tmp.root, "project.json")

    private fun stores(resolver: FakeResolver, project: File): Triple<SourceRepository, GradeStore, ThumbnailStore> =
        Triple(
            SourceRepository(resolver, project),
            GradeStore(project),
            ThumbnailStore(File(tmp.root, "thumbs")),
        )

    @Test
    fun delete_removes_record_grade_and_thumbnail() {
        val project = projectFile()
        val (sources, grades, thumbs) = stores(FakeResolver("clip".toByteArray()), project)
        val imported = sources.import("content://clip/1")
        grades.put(imported.id, GradeK(exposureEv = 2.5f))
        thumbs.ensureDir()
        File(thumbs.targetPath(imported.id)).writeText("png")
        assertTrue(thumbs.has(imported.id))

        ClipDeletion.delete(listOf(imported.id), sources, grades, thumbs)

        assertTrue(sources.all().isEmpty())
        assertEquals(GradeK(), grades.get(imported.id))
        assertFalse(thumbs.has(imported.id))
    }

    @Test
    fun delete_is_a_noop_for_missing_ids() {
        val project = projectFile()
        val (sources, grades, thumbs) = stores(FakeResolver("clip".toByteArray()), project)
        val imported = sources.import("content://clip/1")

        // A delete raced by another removal: the id is already gone.
        ClipDeletion.delete(listOf("no-such-id"), sources, grades, thumbs)

        assertEquals(listOf(imported), sources.all())
    }

    @Test
    fun delete_leaves_other_clips_untouched() {
        val project = projectFile()
        val (sources, grades, thumbs) = stores(
            FakeResolver(ByteArray(2_000_000) { (it % 251).toByte() }),
            project,
        )
        // Two distinct files -> two distinct fingerprint ids.
        val a = sources.import("content://clip/a")
        val repoB = SourceRepository(FakeResolver(ByteArray(2_000_000) { ((it + 1) % 251).toByte() }), project)
        val b = repoB.import("content://clip/b")
        grades.put(a.id, GradeK(exposureEv = 1f))
        grades.put(b.id, GradeK(exposureEv = 2f))
        thumbs.ensureDir()
        File(thumbs.targetPath(a.id)).writeText("a")
        File(thumbs.targetPath(b.id)).writeText("b")

        ClipDeletion.delete(listOf(a.id), sources, grades, thumbs)

        assertEquals(listOf(b), sources.all())
        assertEquals(2f, grades.get(b.id).exposureEv, 0f)
        assertTrue(thumbs.has(b.id))
        assertFalse(thumbs.has(a.id))
    }

    @Test
    fun delete_then_reimport_starts_fresh_with_no_stale_grade() {
        val project = projectFile()
        val data = ByteArray(2_000_000) { (it % 251).toByte() } // > 1 MiB prefix
        val (sources, grades, thumbs) = stores(FakeResolver(data), project)
        val first = sources.import("content://clip/1")
        grades.put(first.id, GradeK(exposureEv = 3.25f, tempK = 7500f))
        thumbs.ensureDir()
        File(thumbs.targetPath(first.id)).writeText("png")

        ClipDeletion.delete(listOf(first.id), sources, grades, thumbs)

        // Re-import the SAME file: the fingerprint gives the id back, but
        // the grade row is gone — the clip opens neutral, not on the
        // deleted grade.
        val second = sources.import("content://clip/1")
        assertEquals(first.id, second.id)
        assertEquals(GradeK(), grades.get(second.id))
        assertFalse(thumbs.has(second.id))
        assertEquals(listOf(second), sources.all())
    }

    @Test
    fun delete_keeps_export_history_rows() {
        val project = projectFile()
        val (sources, grades, thumbs) = stores(FakeResolver("clip".toByteArray()), project)
        val imported = sources.import("content://clip/1")

        // The clip has export history (a DONE row with its own snapshot).
        val exports = ExportStore(project)
        exports.add(
            ExportJob(
                id = "job-1",
                sourceId = imported.id,
                snapshot = GradeK(exposureEv = 1.5f),
                target = Target.DNG,
                state = State.DONE,
            ),
        )

        ClipDeletion.delete(listOf(imported.id), sources, grades, thumbs)

        // The row survives deletion untouched — export history is the
        // record of what the app wrote, not clip state.
        val jobs = exports.all()
        assertEquals(1, jobs.size)
        assertEquals(imported.id, jobs.single().sourceId)
        assertEquals(1.5f, jobs.single().snapshot.exposureEv, 0f)
        assertEquals(State.DONE, jobs.single().state)
    }
}
