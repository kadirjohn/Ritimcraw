package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PresetStore tests (owner request 2026-11-11 section 4): CRUD, the
 * persistence round-trip, old-file compatibility, and the shared-
 * project.json merge contract — GradeStoreTest's conventions.
 */
class PresetStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun projectFile(): File = File(tmp.root, "project.json")

    // ---- CRUD ----

    @Test
    fun put_returns_the_preset_and_all_lists_it() {
        val store = PresetStore(projectFile())
        val grade = GradeK(exposureEv = 1f, tempK = 4300f)
        val preset = store.put("Teal & Orange", grade)

        assertEquals("Teal & Orange", preset.name)
        assertEquals(grade, preset.grade)
        val all = store.all()
        assertEquals(1, all.size)
        assertEquals(preset.id, all.single().id)
        assertEquals(preset, store.get(preset.id))
    }

    @Test
    fun put_persists_and_a_new_store_loads_it() {
        // App close + reopen: a NEW store over the same file, not the
        // in-memory instance.
        val file = projectFile()
        val preset = PresetStore(file).put("Warm night", GradeK(contrast = 30f))

        val reloaded = PresetStore(file)
        assertEquals(preset, reloaded.get(preset.id))
        assertEquals(1, reloaded.all().size)
    }

    @Test
    fun ids_are_unique_across_many_saves() {
        val store = PresetStore(projectFile())
        val ids = (1..20).map { store.put("p$it", GradeK()).id }

        assertEquals(20, ids.size)
        assertEquals(20, ids.toSet().size)
    }

    @Test
    fun remove_deletes_only_the_named_preset() {
        val store = PresetStore(projectFile())
        val a = store.put("A", GradeK(exposureEv = 1f))
        val b = store.put("B", GradeK(exposureEv = 2f))

        store.remove(a.id)

        val remaining = store.all()
        assertEquals(1, remaining.size)
        assertEquals(b.id, remaining.single().id)
        assertNull(store.get(a.id))
    }

    @Test
    fun remove_missing_id_is_a_noop() {
        val store = PresetStore(projectFile())
        val a = store.put("A", GradeK())

        store.remove("pres-does-not-exist")

        assertEquals(listOf(a), store.all())
    }

    // ---- round-trip fidelity ----

    @Test
    fun every_grade_field_round_trips_through_a_preset() {
        // A preset's grade is rendered by GradeStore's own renderer and
        // parsed by GradeStore's own parser, so it must round-trip a full
        // grade bit-identically — the same contract a per-clip grade has.
        val file = projectFile()
        val grade = GradeK(
            exposureEv = 0.5f,
            highlights = -20f,
            tempK = 4300f,
            tint = 3f,
            saturation = 42f,
            blackPoint = -18f,
            whitePoint = 63f,
            shadows = -7f,
            midtones = 29f,
            contrast = -96f,
            asShot = false,
            highlightReconstruction = false,
            keepVignetting = false,
            sharpness = 35f,
            detail = -12f,
            tonemap = 2,
            lutId = "abc123def4567890",
            lutStrength = 37f,
            noiseReduction = 42f,
            chromaDenoise = 77f,
        )
        val preset = PresetStore(file).put("Everything", grade)

        assertEquals(grade, PresetStore(file).get(preset.id)?.grade)
    }

    @Test
    fun preset_name_round_trips_verbatim() {
        // The name is the user's own text: quotes, backslashes, unicode —
        // stored and re-read exactly as typed, never localized or mangled.
        val file = projectFile()
        val odd = "Kadir \"keskin\" \\ look • ışık"
        val preset = PresetStore(file).put(odd, GradeK())

        assertEquals(odd, PresetStore(file).get(preset.id)?.name)
    }

    @Test
    fun lut_selection_travels_with_the_preset_grade() {
        // Applying a preset onto a clip whose LUT library lacks the cube
        // keeps the id verbatim (documented semantics): the grade renders
        // as no-LUT until the cube is re-imported, and re-importing the
        // same bytes restores the look because the fingerprint id is
        // deterministic. The store never drops or rewrites the id.
        val file = projectFile()
        val grade = GradeK(lutId = "deadbeefcafe1234", lutStrength = 55f)
        val preset = PresetStore(file).put("With LUT", grade)

        val loaded = PresetStore(file).get(preset.id)!!.grade
        assertEquals("deadbeefcafe1234", loaded.lutId)
        assertEquals(55f, loaded.lutStrength, 0f)
    }

    // ---- old-file compatibility ----

    @Test
    fun old_file_without_presets_section_loads_empty() {
        // A project.json written by the pre-preset build (grades +
        // sources only) must load as an empty list — the old build's
        // exact behavior, no crash, and the other sections stay intact.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"grades":{"clip-a":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0,"saturation":10.0,"blackPoint":-5.0,"whitePoint":4.0,"shadows":-2.0,"midtones":1.0,"contrast":8.0,"asShot":false,"highlightReconstruction":true,"keepVignetting":true,"sharpness":15.0,"detail":-3.0,"tonemap":1,"lutId":null,"lutStrength":100.0,"noiseReduction":0.0,"chromaDenoise":0.0}}}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val store = PresetStore(file)
        assertTrue(store.all().isEmpty())
        assertNull(store.get("pres-anything"))
    }

    @Test
    fun preset_write_preserves_the_grades_section_and_vice_versa() {
        // PresetStore shares project.json with GradeStore: a preset write
        // must not clobber the grades section, and a grade write must not
        // clobber the presets section.
        val file = projectFile()
        GradeStore(file).put("clip-a", GradeK(exposureEv = 2f))
        val preset = PresetStore(file).put("Look 1", GradeK(tempK = 4300f))

        assertEquals(2f, GradeStore(file).get("clip-a").exposureEv, 0f)
        assertEquals(preset, PresetStore(file).get(preset.id))

        // And the reverse: a grade write keeps the preset.
        GradeStore(file).put("clip-b", GradeK(contrast = 9f))
        assertEquals(preset, PresetStore(file).get(preset.id))
        assertEquals(9f, GradeStore(file).get("clip-b").contrast, 0f)
    }

    @Test
    fun preset_write_preserves_the_exports_section() {
        // ExportStore also writes project.json: a preset write must keep
        // the exports section intact.
        val file = projectFile()
        ExportStore(file).add(
            ExportJob(
                id = "job-1",
                sourceId = "clip-a",
                snapshot = GradeK(exposureEv = 1f),
                target = Target.MP4,
                state = State.QUEUED,
            )
        )

        PresetStore(file).put("Look 2", GradeK())

        val reloaded = ExportStore(file).all()
        assertEquals(1, reloaded.size)
        assertEquals(Target.MP4, reloaded.single().target)
    }

    @Test
    fun corrupt_store_falls_back_to_empty() {
        // Same contract as GradeStore: a corrupt file keeps the app
        // usable; the presets list loads empty rather than crashing.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText("{ not json", Charsets.UTF_8)

        assertTrue(PresetStore(file).all().isEmpty())
    }

    @Test
    fun preset_saved_by_an_older_grade_schema_loads_missing_fields_neutral() {
        // A preset written before a later grade-field addition (here:
        // written without any denoise fields) loads with the missing
        // fields neutral — the per-clip grade contract, inherited via
        // the shared parse path.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """
            {"version":1,"presets":[{"id":"pres-old","name":"Old look","grade":{"version":1,"exposureEv":0.8,"highlights":-25.0,"tempK":4600.0,"tint":3.0,"saturation":10.0,"blackPoint":-5.0,"whitePoint":4.0,"shadows":-2.0,"midtones":1.0,"contrast":8.0,"asShot":false,"highlightReconstruction":true,"keepVignetting":true,"sharpness":15.0,"detail":-3.0,"tonemap":1,"lutId":null,"lutStrength":100.0}}]}
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val preset = PresetStore(file).get("pres-old")
        assertEquals("Old look", preset?.name)
        val g = preset?.grade
        assertEquals(0.8f, g?.exposureEv ?: 0f, 0f)
        assertEquals(15f, g?.sharpness ?: 0f, 0f)
        assertEquals(0f, g?.noiseReduction ?: 0f, 0f)
        assertEquals(0f, g?.chromaDenoise ?: 0f, 0f)
    }
}
