package com.ritim.ritimcraw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * PresetFile tests (şartname COL-18 preset import/export, 2026-11-23):
 * round trip through the one grade wire format, ids never exported,
 * version gate (newer refused, never half-read), legacy project.json
 * source, per-entry tolerance with notes, LUT references surfaced, and
 * PresetStore.importAll minting fresh ids.
 */
class PresetFileTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val tealOrange = GradeK(
        exposureEv = 0.7f, tempK = 4300f, tint = 12f, saturation = 15f,
        lutId = "lut-abc", lutStrength = 60f,
    )
    private val flat = GradeK(contrast = -20f)

    @Test
    fun render_then_parse_round_trips_names_grades_and_order() {
        val text = PresetFile.render(
            listOf(GradePreset("pres-1", "Teal & Orange", tealOrange), GradePreset("pres-2", "Flat", flat))
        )
        val r = PresetFile.parse(text) as PresetFile.Ok
        assertEquals(listOf("Teal & Orange", "Flat"), r.presets.map { it.name })
        assertEquals(tealOrange, r.presets[0].grade)
        assertEquals(flat, r.presets[1].grade)
        assertTrue(r.notes.isEmpty())
        assertEquals(listOf("lut-abc"), r.lutIdsReferenced)
    }

    @Test
    fun ids_are_not_exported_and_header_is_versioned() {
        val text = PresetFile.render(listOf(GradePreset("pres-secret-1", "A", flat)))
        assertTrue(!text.contains("pres-secret-1"))
        assertTrue(text.startsWith("{\"format\":\"ritimcraw-grade-preset\",\"formatVersion\":1,"))
    }

    @Test
    fun newer_format_version_is_refused_not_half_read() {
        val text = PresetFile.render(listOf(GradePreset("p", "A", flat)))
            .replace("\"formatVersion\":1", "\"formatVersion\":2")
        val r = PresetFile.parse(text) as PresetFile.Failure
        assertEquals(PresetFile.Failure.Reason.TOO_NEW, r.reason)
    }

    @Test
    fun wrong_format_garbage_and_empty_fail_with_their_reason() {
        assertEquals(
            PresetFile.Failure.Reason.MALFORMED,
            (PresetFile.parse("not json {") as PresetFile.Failure).reason,
        )
        assertEquals(
            PresetFile.Failure.Reason.MALFORMED,
            (PresetFile.parse("[1,2]") as PresetFile.Failure).reason,
        )
        assertEquals(
            PresetFile.Failure.Reason.NOT_A_PRESET_FILE,
            (PresetFile.parse("{\"format\":\"something-else\",\"formatVersion\":1}") as PresetFile.Failure).reason,
        )
        assertEquals(
            PresetFile.Failure.Reason.NOT_A_PRESET_FILE,
            (PresetFile.parse("{\"grades\":{}}") as PresetFile.Failure).reason,
        )
        assertEquals(
            PresetFile.Failure.Reason.EMPTY,
            (PresetFile.parse("{\"format\":\"ritimcraw-grade-preset\",\"formatVersion\":1,\"presets\":[]}")
                as PresetFile.Failure).reason,
        )
    }

    @Test
    fun bad_entries_are_skipped_with_notes_good_ones_import() {
        val text = "{\"format\":\"ritimcraw-grade-preset\",\"formatVersion\":1,\"presets\":[" +
            "{\"name\":\"\",\"grade\":{}}," +
            "{\"name\":\"NoGrade\"}," +
            "{\"name\":\"Old\",\"grade\":{\"version\":1,\"exposureEv\":1.5}}]}"
        val r = PresetFile.parse(text) as PresetFile.Ok
        assertEquals(listOf("Old"), r.presets.map { it.name })
        // Fields an older writer did not know load neutral (the shared parser).
        assertEquals(GradeK(exposureEv = 1.5f), r.presets[0].grade)
        assertEquals(2, r.notes.size)
        assertTrue(r.lutIdsReferenced.isEmpty())
    }

    @Test
    fun a_project_json_presets_section_imports_as_legacy_source_with_a_note() {
        val file = File(tmp.root, "project.json")
        PresetStore(file).put("Shared", tealOrange)
        val r = PresetFile.parse(file.readText()) as PresetFile.Ok
        assertEquals("Shared", r.presets.single().name)
        assertEquals(tealOrange, r.presets.single().grade)
        assertEquals(1, r.notes.size)
    }

    @Test
    fun import_all_mints_fresh_ids_and_keeps_existing_presets() {
        val file = File(tmp.root, "project.json")
        val store = PresetStore(file)
        val mine = store.put("Mine", flat)
        val text = PresetFile.render(store.all())

        val ok = PresetFile.parse(text) as PresetFile.Ok
        val added = store.importAll(ok.presets)
        assertEquals(1, added.size)
        assertNotEquals(mine.id, added.single().id)
        val all = PresetStore(file).all()
        assertEquals(listOf("Mine", "Mine"), all.map { it.name })
        assertEquals(all[0].grade, all[1].grade)
        assertEquals(2, all.map { it.id }.distinct().size)

        // An empty import writes nothing.
        val before = file.readText()
        assertTrue(store.importAll(emptyList()).isEmpty())
        assertEquals(before, file.readText())
    }

    @Test
    fun migrate_is_identity_at_the_current_version() {
        val notes = ArrayList<String>()
        val g = mapOf<String, Any?>("tempK" to 4300.0)
        assertEquals(g, PresetFile.migrate(PresetFile.FORMAT_VERSION, g, notes, "x"))
        assertTrue(notes.isEmpty())
    }
}
