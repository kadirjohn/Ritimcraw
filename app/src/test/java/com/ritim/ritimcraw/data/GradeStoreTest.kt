package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * GradeStore tests (sprint plan Task 4):
 *  - grades_do_not_leak_between_clips: a grade set on clip A never appears on
 *    clip B, including after persist + reload (QA-05).
 */
class GradeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun grades_do_not_leak_between_clips() {
        val file = projectFile()
        val store = GradeStore(file)

        val clipA = "clip-a"
        val clipB = "clip-b"
        store.put(clipA, GradeK(exposureEv = 1f))
        store.put(clipB, GradeK(tempK = 3200f))

        // Reload from disk: a NEW store over the same file (simulating app
        // close + reopen), not the in-memory instance.
        val reloaded = GradeStore(file)
        assertEquals(1f, reloaded.get(clipA).exposureEv, 0f)
        assertEquals(5600f, reloaded.get(clipA).tempK, 0f) // A keeps default temp
        assertEquals(0f, reloaded.get(clipB).exposureEv, 0f) // B keeps default EV
        assertEquals(3200f, reloaded.get(clipB).tempK, 0f)
    }

    @Test
    fun put_persists_every_write() {
        val file = projectFile()
        val store = GradeStore(file)
        store.put("c1", GradeK(exposureEv = 0.5f))
        // Written immediately (no flush/close step).
        assertTrue(file.exists())
        assertEquals(0.5f, GradeStore(file).get("c1").exposureEv, 0f)
    }

    @Test
    fun write_survives_sources_section_in_same_file() {
        // GradeStore and SourceRepository share project.json; a grade write
        // must not clobber the sources section (and vice versa).
        val file = projectFile()
        val sources = SourceRepository(FakeResolver(), file)
        val imported = sources.import("content://clip/1")

        val grades = GradeStore(file)
        grades.put(imported.id, GradeK(exposureEv = 2f))

        assertEquals(1, SourceRepository(FakeResolver(), file).all().size)
        assertEquals(2f, GradeStore(file).get(imported.id).exposureEv, 0f)
    }

    @Test
    fun write_survives_exports_section_in_same_file() {
        // ExportStore also writes project.json; a grade write must not
        // clobber the exports section (and an exports write keeps grades).
        val file = projectFile()
        val exports = ExportStore(file)
        exports.add(
            ExportJob(
                id = "job-1",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 1f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        GradeStore(file).put("clip-a", GradeK(tempK = 4300f))

        val reloaded = ExportStore(file).all()
        assertEquals(1, reloaded.size)
        assertEquals(Target.MP4, reloaded.single().target)
        assertEquals(4300f, GradeStore(file).get("clip-a").tempK, 0f)
    }

    @Test
    fun remove_deletes_grade() {
        val file = projectFile()
        val store = GradeStore(file)
        store.put("c1", GradeK(highlights = -20f))
        store.remove("c1")
        assertEquals(0f, GradeStore(file).get("c1").highlights, 0f)
    }

    @Test
    fun corrupt_store_falls_back_to_defaults() {
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)
        assertEquals(0f, GradeStore(file).get("c1").exposureEv, 0f)
    }

    @Test
    fun old_format_file_loads_tone_controls_as_neutral() {
        // A project.json written by the pre-tone-controls build (schema 1,
        // only the four original fields). The six new fields are absent and
        // must parse as their neutral defaults — the old look is preserved
        // exactly, which is the owner's binding requirement for this package.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"legacy-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("legacy-clip")
        // The four original fields keep their saved values...
        assertEquals(0.8f, g.exposureEv, 0f)
        assertEquals(-25f, g.highlights, 0f)
        assertEquals(4600f, g.tempK, 0f)
        assertEquals(3f, g.tint, 0f)
        // ...and the six tone/color fields are neutral.
        assertEquals(0f, g.saturation, 0f)
        assertEquals(0f, g.blackPoint, 0f)
        assertEquals(0f, g.whitePoint, 0f)
        assertEquals(0f, g.shadows, 0f)
        assertEquals(0f, g.midtones, 0f)
        assertEquals(0f, g.contrast, 0f)
    }

    @Test
    fun tone_control_values_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            saturation = 42f,
            blackPoint = -18f,
            whitePoint = 63f,
            shadows = -7f,
            midtones = 29f,
            contrast = -96f,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
    }

    @Test
    fun raw_toggle_values_survive_persist_and_reload() {
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            asShot = false,
            highlightReconstruction = false,
            keepVignetting = false,
        )
        GradeStore(file).put("c1", grade)

        val reloaded = GradeStore(file).get("c1")
        assertEquals(grade, reloaded)
    }

    @Test
    fun old_format_file_defaults_raw_toggles_to_true() {
        // A project.json written by the pre-RAW-toggles build. The two stage
        // toggles are absent and must parse as true (pre-toggle behavior), so
        // an old project keeps its exact look — the binding requirement of
        // RAW-04/RAW-06. Here temp/tint are neutral, so asShot derives true.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"neutral-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":5600.0,"tint":0.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("neutral-clip")
        assertTrue(g.asShot) // neutral manual WB means as-shot WB
        assertTrue(g.highlightReconstruction)
        assertTrue(g.keepVignetting)
    }

    @Test
    fun old_format_file_derives_asshot_false_from_manual_wb() {
        // The pre-RAW-toggles build had no asShot field, but it DID have
        // manual temp/tint sliders that always ran. A grade saved with
        // manual WB must keep its exact old look, so the missing asShot
        // derives false (stages 14-15 keep running) rather than defaulting
        // to true and silently discarding the user's WB.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"manual-clip":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val g = GradeStore(file).get("manual-clip")
        assertTrue(!g.asShot) // manual WB means manual WB wins
        assertTrue(g.highlightReconstruction)
        assertTrue(g.keepVignetting)
        assertEquals(4600f, g.tempK, 0f)
        assertEquals(3f, g.tint, 0f)
    }

    private fun projectFile(): File = File(tmp.root, "project.json")
}
