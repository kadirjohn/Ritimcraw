package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * IN-13 Stage A persistence tests: the "metadata" section of
 * project.json. The store follows the AudioStore pattern, so these tests
 * pin the same guarantees:
 *
 *   - round-trip: render -> parse -> the IDENTICAL record (an ESTIMATED
 *     field keeps its basis; UNKNOWN fields stay UNKNOWN)
 *   - omit-unknown: UNKNOWN fields render NOTHING — a pre-IN-13
 *     project.json gains a section, never noise
 *   - coexistence: the other six sections survive a metadata write
 *     byte-identical (the withSection merge)
 *   - tolerance: a corrupt section loads as empty, a bad ROW is skipped
 *     — one bad record never takes the project down
 *   - honesty in persistence: an estimate WITHOUT a basis is refused on
 *     read (the Field would have refused it on write)
 */
class MetadataStoreTest {

    // ---- helpers ----

    private fun tempDir(): File =
        Files.createTempDirectory("metadata-store-test").toFile()

    private fun store(dir: File): MetadataStore =
        MetadataStore(File(dir, "project.json"))

    private fun fullRecord(id: String = "src-1"): MetadataRecord = MetadataRecord(
        sourceId = id,
        kind = SourceKind.MCRAW,
        durationUs = Field.known(966_657L),
        frameCount = Field.known(30),
        fpsNominal = Field.unknown(),
        fpsMeasured = Field.estimated(
            30.0,
            "(frames - 1) / (last pts - first pts) over the recorded span",
        ),
        camera = Field.known("25010pn30g"),
        lens = Field.known("24 mm f/1.6"),
        resolution = Field.known("4608x2592"),
        bitDepth = Field.known(16),
        primaries = Field.unknown(),
        transfer = Field.unknown(),
        matrix = Field.unknown(),
        range = Field.unknown(),
        audio = Field.known(
            AudioSummary(present = true, codec = "pcm", channels = 2, sampleRateHz = 48_000),
        ),
        fileSize = Field.known(2_199_999_999L),
        fileIdentity = Field.known("abc123"),
        dolbyVision = Diagnosis.NOT_DV,
        logProfile = LogDiagnosis.NOT_LOG,
        hdrOutputSupport = Capability.UNKNOWN,
    )

    /** The all-UNKNOWN record: what a failed probe persists. */
    private fun emptyRecord(id: String = "src-1"): MetadataRecord = MetadataRecord(
        sourceId = id,
        kind = SourceKind.MCRAW,
        durationUs = Field.unknown(),
        frameCount = Field.unknown(),
        fpsNominal = Field.unknown(),
        fpsMeasured = Field.unknown(),
        camera = Field.unknown(),
        lens = Field.unknown(),
        resolution = Field.unknown(),
        bitDepth = Field.unknown(),
        primaries = Field.unknown(),
        transfer = Field.unknown(),
        matrix = Field.unknown(),
        range = Field.unknown(),
        audio = Field.unknown(),
        fileSize = Field.unknown(),
        fileIdentity = Field.unknown(),
    )

    // ---- round-trip ----

    @Test
    fun round_trip_preserves_every_known_and_estimated_field() {
        val original = fullRecord()
        val parsed = MetadataStore.parseMetadata(
            MetadataStore.mergeInto(null, mapOf("src-1" to original)),
        )
        val back = parsed["src-1"]!!
        assertEquals(original, back) // data class equality: every field
    }

    @Test
    fun round_trip_keeps_the_estimate_basis() {
        val record = fullRecord()
        val back = MetadataStore.parseMetadata(
            MetadataStore.mergeInto(null, mapOf("src-1" to record)),
        )["src-1"]!!
        assertEquals(FieldStatus.ESTIMATED, back.fpsMeasured.status)
        assertEquals("(frames - 1) / (last pts - first pts) over the recorded span",
            back.fpsMeasured.basis)
    }

    @Test
    fun round_trip_keeps_unknown_fields_unknown() {
        val record = emptyRecord()
        val back = MetadataStore.parseMetadata(
            MetadataStore.mergeInto(null, mapOf("src-1" to record)),
        )["src-1"]!!
        assertEquals(record, back)
        assertEquals(FieldStatus.UNKNOWN, back.camera.status)
        assertEquals(FieldStatus.UNKNOWN, back.durationUs.status)
    }

    @Test
    fun round_trip_preserves_the_frame_count_estimate_flag() {
        // The flag's invariant: it may only mark an ESTIMATED count.
        val record = fullRecord().copy(
            frameCount = Field.estimated(29, "half the container index"),
            frameCountEstimated = true,
        )
        val back = MetadataStore.parseMetadata(
            MetadataStore.mergeInto(null, mapOf("src-1" to record)),
        )["src-1"]!!
        assertTrue(back.frameCountEstimated)
        assertEquals(FieldStatus.ESTIMATED, back.frameCount.status)
        assertEquals(29, back.frameCount.value)
        assertEquals("half the container index", back.frameCount.basis)
    }

    // ---- omit-unknown rendering ----

    @Test
    fun unknown_fields_render_nothing() {
        val record = emptyRecord()
        val text = MetadataStore.mergeInto(null, mapOf("src-1" to record))
        // The section renders kind + the three always-rendered verdicts,
        // and NONE of the unknown fields.
        assertFalse(text.contains("durationUs"))
        assertFalse(text.contains("camera"))
        assertFalse(text.contains("audio"))
        assertTrue(text.contains("\"kind\":\"MCRAW\""))
        assertTrue(text.contains("\"dolbyVision\":\"INSUFFICIENT_DATA\""))
    }

    @Test
    fun known_fields_render_their_value_and_status() {
        val text = MetadataStore.mergeInto(null, mapOf("src-1" to fullRecord()))
        assertTrue(text.contains("\"durationUs\":{\"v\":966657,\"s\":\"KNOWN\"}"))
        // The estimate renders its basis.
        assertTrue(text.contains("\"s\":\"ESTIMATED\",\"b\":\"(frames - 1)"))
    }

    @Test
    fun frame_count_estimate_flag_renders_only_when_true() {
        val unset = MetadataStore.mergeInto(
            null, mapOf("src-1" to fullRecord()),
        )
        assertFalse(unset.contains("frameCountEstimated"))
        // The flag's invariant: it may only mark an ESTIMATED count.
        val estimated = fullRecord().copy(
            frameCount = Field.estimated(29, "half the container index"),
            frameCountEstimated = true,
        )
        val set = MetadataStore.mergeInto(null, mapOf("src-1" to estimated))
        assertTrue(set.contains("\"frameCountEstimated\":true"))
    }

    @Test
    fun empty_store_renders_the_version_only() {
        val text = MetadataStore.renderMetadata(emptyMap())
        assertEquals("{\"version\":1}", text)
    }

    @Test
    fun records_render_in_sorted_id_order_for_diff_stability() {
        val records = mapOf(
            "src-b" to emptyRecord("src-b").copy(kind = SourceKind.ENCODED),
            "src-a" to emptyRecord("src-a"),
        )
        val text = MetadataStore.renderMetadata(records)
        val a = text.indexOf("\"src-a\"")
        val b = text.indexOf("\"src-b\"")
        assertTrue(a in 0 until b)
    }

    // ---- coexistence with the other six sections ----

    @Test
    fun a_metadata_write_preserves_the_other_six_sections() {
        val existing = """
            {"version":1,"grades":{"g1":{"name":"look"}},"sources":
            [{"id":"src-1","uri":"content://x","kind":"MCRAW"}],
            "exports":[{"id":"e1"}],"luts":[{"id":"l1"}],
            "timeline":{"tracks":[]},"audio":{"clips":[]}}
        """.trimIndent()
        val merged = MetadataStore.mergeInto(existing, mapOf("src-1" to fullRecord()))
        val parsed = com.ritim.ritimcraw.data.JsonParser(merged).parseValue() as Map<*, *>
        // All seven sections present, in canonical order.
        assertEquals(
            listOf("version", "grades", "sources", "exports", "luts", "timeline",
                "audio", "metadata"),
            parsed.keys.toList(),
        )
        assertEquals(mapOf("g1" to mapOf("name" to "look")), parsed["grades"])
        assertEquals(mapOf("clips" to emptyList<Any>()), parsed["audio"])
        // And the metadata section itself parses back.
        assertTrue(parsed["metadata"] is Map<*, *>)
    }

    @Test
    fun a_metadata_write_preserves_unknown_future_sections() {
        val existing = """{"version":1,"future":{"x":1}}"""
        val merged = MetadataStore.mergeInto(existing, emptyMap())
        val parsed = com.ritim.ritimcraw.data.JsonParser(merged).parseValue() as Map<*, *>
        assertEquals(mapOf("x" to 1.0), parsed["future"])
        assertEquals(1.0, parsed["version"])
    }

    // ---- load defaults and tolerance ----

    @Test
    fun missing_file_loads_empty() {
        val dir = tempDir()
        assertEquals(emptyMap<String, MetadataRecord>(), store(dir).load())
    }

    @Test
    fun a_project_without_the_section_loads_empty() {
        val text = """{"version":1,"grades":{}}"""
        assertEquals(emptyMap<String, MetadataRecord>(), MetadataStore.parseMetadata(text))
    }

    @Test
    fun a_corrupt_section_loads_empty_but_the_file_survives() {
        val dir = tempDir()
        val file = File(dir, "project.json")
        file.writeText("""{"version":1,"metadata":{"src-1":{""")
        val loaded = MetadataStore(file).load()
        assertEquals(emptyMap<String, MetadataRecord>(), loaded)
        // The corrupt text survives untouched on disk.
        assertTrue(file.readText().startsWith("""{"version":1,"metadata""""))
    }

    @Test
    fun a_bad_row_is_skipped_and_the_good_rows_survive() {
        val text = MetadataStore.mergeInto(
            null,
            mapOf(
                "src-good" to fullRecord("src-good"),
                "src-bad" to fullRecord("src-bad").copy(kind = SourceKind.MCRAW),
            ),
        )
        // Corrupt ONE row's kind in the raw text (an unknown enum name).
        val corrupted = text.replace("\"src-bad\":{\"kind\":\"MCRAW\"", "\"src-bad\":{\"kind\":\"NOT_A_KIND\"")
        assertTrue(corrupted.contains("NOT_A_KIND")) // the edit landed
        val parsed = MetadataStore.parseMetadata(corrupted)
        assertNull(parsed["src-bad"]) // the bad row is skipped, not guessed
        assertEquals(fullRecord("src-good"), parsed["src-good"]) // the good row survives
    }

    @Test
    fun an_estimate_without_a_basis_is_refused_on_read() {
        val good = fullRecord()
        val text = MetadataStore.mergeInto(null, mapOf("src-1" to good))
        // A hand-corrupted estimate: status ESTIMATED, basis removed.
        val corrupted = text.replace(
            "\"s\":\"ESTIMATED\",\"b\":\"(frames - 1) / (last pts - first pts) over the recorded span\"",
            "\"s\":\"ESTIMATED\"",
        )
        assertTrue(corrupted.contains("\"s\":\"ESTIMATED\"")) // the edit landed
        val parsed = MetadataStore.parseMetadata(corrupted)
        assertTrue(parsed.isEmpty()) // the row is skipped, not guessed
    }

    @Test
    fun an_unknown_enum_name_skips_only_that_row() {
        val good = fullRecord("src-good")
        val text = MetadataStore.mergeInto(null, mapOf("src-good" to good))
        val corrupted = text.replace("\"dolbyVision\":\"NOT_DV\"", "\"dolbyVision\":\"MAYBE\"")
        val parsed = MetadataStore.parseMetadata(corrupted)
        assertTrue(parsed.isEmpty())
    }

    // ---- the store API ----

    @Test
    fun put_upserts_and_persists_across_a_new_store_instance() {
        val dir = tempDir()
        val s = store(dir)
        s.put(fullRecord("src-1"))
        // A fresh instance reads the same record from disk.
        assertEquals(fullRecord("src-1"), store(dir).get("src-1"))
        // An upsert replaces.
        s.put(fullRecord("src-1").copy(camera = Field.known("Pixel 9 Pro")))
        assertEquals("Pixel 9 Pro", store(dir).get("src-1")!!.camera.value)
    }

    @Test
    fun remove_drops_the_record_and_keeps_the_rest() {
        val dir = tempDir()
        val s = store(dir)
        s.put(fullRecord("src-1"))
        s.put(fullRecord("src-2"))
        s.remove("src-1")
        val loaded = store(dir).load()
        assertNull(loaded["src-1"])
        assertEquals(fullRecord("src-2"), loaded["src-2"])
    }

    @Test
    fun replaceAll_swaps_the_whole_set() {
        val dir = tempDir()
        val s = store(dir)
        s.put(fullRecord("src-1"))
        s.replaceAll(mapOf("src-2" to fullRecord("src-2")))
        val loaded = store(dir).load()
        assertNull(loaded["src-1"])
        assertEquals(fullRecord("src-2"), loaded["src-2"])
    }

    @Test
    fun put_preserves_sections_written_by_the_other_stores() {
        val dir = tempDir()
        val file = File(dir, "project.json")
        file.writeText("""{"version":1,"grades":{"g1":{"name":"look"}}}""")
        MetadataStore(file).put(fullRecord("src-1"))
        val text = file.readText()
        assertTrue(text.contains("\"grades\":{\"g1\":{\"name\":\"look\"}}"))
        assertTrue(text.contains("\"metadata\""))
    }
}
