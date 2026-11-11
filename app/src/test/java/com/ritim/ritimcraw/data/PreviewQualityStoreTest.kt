package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.engine.PreviewQuality
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Preview-quality persistence (owner request 2026-11-11b section 3): the
 * top-level project.json scalar, omit-when-default, old files load as the
 * default, malformed values degrade to the default, and the other stores'
 * sections survive a write.
 */
class PreviewQualityStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh store + its own project.json in a fresh subfolder. */
    private fun newStore(): Pair<PreviewQualityStore, File> {
        val dir = tmp.newFolder()
        val f = File(dir, "project.json")
        return PreviewQualityStore(f) to f
    }

    // ---- old files / fresh state -> default ----

    @Test
    fun missing_file_loads_as_default() {
        assertEquals(PreviewQuality.DEFAULT, newStore().first.get())
    }

    @Test
    fun old_file_without_the_key_loads_as_default() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"grades":{"clip-a":{"version":1}},"sources":[]}""",
            StandardCharsets.UTF_8,
        )
        assertEquals(PreviewQuality.DEFAULT, PreviewQualityStore(f).get())
    }

    // ---- omit-when-default ----

    @Test
    fun writing_default_to_an_old_file_is_byte_identical() {
        val old = """{"version":1,"grades":{"clip-a":{"version":1}},"sources":[]}"""
        val f = tmp.newFile("project.json")
        f.writeText(old, StandardCharsets.UTF_8)
        PreviewQualityStore(f).put(PreviewQuality.DEFAULT)
        assertEquals(old, f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun setting_then_returning_to_default_removes_the_key() {
        val (s, f) = newStore()
        s.put(PreviewQuality.HIGH)
        assertEquals(PreviewQuality.HIGH, s.get())
        s.put(PreviewQuality.DEFAULT)
        // The round trip leaves no key behind and no value changed.
        assertEquals(PreviewQuality.DEFAULT, s.get())
        assertFalse(PreviewQuality.WIRE_NAME in f.readText(StandardCharsets.UTF_8))
    }

    // ---- round trip ----

    @Test
    fun every_level_round_trips_through_the_file() {
        for (q in PreviewQuality.entries) {
            val (s, _) = newStore()
            s.put(q)
            assertEquals(q, s.get())
        }
    }

    @Test
    fun a_set_value_survives_another_store_write() {
        // The other stores rewrite project.json through ProjectJson
        // .withSection, which re-renders unknown top-level entries raw —
        // a grade save must not drop the quality choice.
        val f = tmp.newFile("project.json")
        PreviewQualityStore(f).put(PreviewQuality.LOW)
        val afterQuality = f.readText(StandardCharsets.UTF_8)
        val rewrittenByGradeStore = GradeStore.mergeInto(
            afterQuality,
            mapOf("clip-a" to GradeK()),
        )
        f.writeText(rewrittenByGradeStore, StandardCharsets.UTF_8)
        assertEquals(PreviewQuality.LOW, PreviewQualityStore(f).get())
    }

    @Test
    fun quality_write_preserves_the_other_sections() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"grades":{"clip-a":{"version":1}},"sources":[],"exports":[],"luts":[],"presets":[]}""",
            StandardCharsets.UTF_8,
        )
        PreviewQualityStore(f).put(PreviewQuality.HIGH)
        val text = f.readText(StandardCharsets.UTF_8)
        for (section in listOf("grades", "sources", "exports", "luts", "presets")) {
            assertTrue("missing section $section in $text", section in text)
        }
        assertTrue("\"previewQuality\":\"HIGH\"" in text)
    }

    // ---- malformed -> default ----

    @Test
    fun malformed_file_degrades_to_default() {
        val f = tmp.newFile("project.json")
        f.writeText("""{"version":1,"previewQuality":"HIG""", StandardCharsets.UTF_8)
        assertEquals(PreviewQuality.DEFAULT, PreviewQualityStore(f).get())
    }

    @Test
    fun unknown_value_degrades_to_default() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"previewQuality":"ULTRA"}""",
            StandardCharsets.UTF_8,
        )
        assertEquals(PreviewQuality.DEFAULT, PreviewQualityStore(f).get())
    }

    @Test
    fun non_string_value_degrades_to_default() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"previewQuality":3}""",
            StandardCharsets.UTF_8,
        )
        assertEquals(PreviewQuality.DEFAULT, PreviewQualityStore(f).get())
    }

    @Test
    fun null_value_degrades_to_default() {
        val f = tmp.newFile("project.json")
        f.writeText(
            """{"version":1,"previewQuality":null}""",
            StandardCharsets.UTF_8,
        )
        assertEquals(PreviewQuality.DEFAULT, PreviewQualityStore(f).get())
    }

    // ---- pure render ----

    @Test
    fun merge_into_is_deterministic() {
        val existing = """{"version":1,"grades":{"clip-a":{"version":1}}}"""
        assertEquals(
            PreviewQualityStore.mergeInto(existing, PreviewQuality.HIGH),
            PreviewQualityStore.mergeInto(existing, PreviewQuality.HIGH),
        )
    }

    @Test
    fun fresh_file_write_shape_is_minimal() {
        // No existing file: the store writes version + the key only.
        val (s, f) = newStore()
        s.put(PreviewQuality.HIGH)
        assertEquals(
            """{"version":1,"previewQuality":"HIGH"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }
}
