package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

/**
 * CodecMeasureWire round-trip (plan §5, E1e-a): the JSON is the E1c data
 * source file — a row that survives render->parse with ANY field changed
 * or lost would poison the size-prediction fix the data feeds, so the
 * round trip is EXACT and an unknown version parses to null, never a
 * guess. Also pins the store's tmp+rename semantics over a REAL file.
 */
class CodecMeasureWireTest {

    private fun case(i: Int) = CodecMeasureCase(
        mime = if (i % 2 == 0) "video/hevc" else "video/avc",
        encoderName = "c2.test.encoder.$i",
        profile = if (i % 2 == 0) null else 8,
        bitDepth = if (i == 2) 10 else 8,
        width = 3840,
        height = 2160,
        fps = if (i == 1) 60 else 30,
        rateMode = if (i % 3 == 0) ExportRateMode.CBR.mediaCodecMode else ExportRateMode.VBR.mediaCodecMode,
        requestedBps = 140_000_000L + i,
        uncapped = i > 1,
        durationSec = 2.0,
        keyframeSec = 1f,
        content = MeasureOptions.CONTENT_RECIPE,
    )

    private fun result(i: Int) = MeasureResult(
        c = case(i),
        configureOk = i != 3,
        errorCode = if (i == 3) "IllegalStateException" else null,
        encodedBytes = 12_345_678L * i,
        ptsDurationSec = if (i == 3) null else 2.0,
        measuredBps = if (i == 3) null else 49_382_712L + i,
        iFrameBytes = 1_000_000L + i,
        iFrameCount = 2 + i,
        framesIn = 60 + i,
        encoder = "c2.real.encoder.$i",
        hardware = i % 2 == 0,
        wallMs = 1234L + i,
    )

    private fun report() = CodecMeasureReport(
        fingerprint = "Xiaomi/shot_15U/śli:15/AL3A.241105/HyperOS-eng.dino.123",
        model = "24101ADB7C",
        incremental = "AL3A.241105.22-eng.dino.20261123",
        startedMs = 1_766_937_600_000,
        rows = (0 until 6).map { result(it) },
    )

    @Test
    fun render_then_parse_is_the_exact_same_report() {
        val r = report()
        val text = CodecMeasureWire.render(r)
        val back = CodecMeasureWire.parse(text)
        assertEquals(r, back)
        // and the round trip is stable across a second render
        assertEquals(text, CodecMeasureWire.render(back!!))
    }

    @Test
    fun the_json_shape_is_v1_with_the_identity_and_the_row_fields() {
        val text = CodecMeasureWire.render(report())
        assertTrue(text.startsWith("{\"v\":1"))
        assertTrue(text.contains("\"fingerprint\":"))
        assertTrue(text.contains("\"model\":\"24101ADB7C\""))
        assertTrue(text.contains("\"incremental\":"))
        assertTrue(text.contains("\"startedMs\":"))
        assertTrue(text.contains("\"rows\":["))
        // the row fields the plan §5 names
        for (k in listOf(
            "configureOk", "errorCode", "encodedBytes", "ptsDurationSec",
            "measuredBps", "iFrameBytes", "iFrameCount", "framesIn",
            "encoder", "hardware", "wallMs",
        )) {
            assertTrue("missing row key $k", text.contains("\"$k\":"))
        }
        assertTrue(text.contains("\"content\":\"ramp+v1\""))
        // nulls stay nulls, never "" or 0
        assertTrue(text.contains("\"errorCode\":null"))
        assertTrue(text.contains("\"measuredBps\":null"))
    }

    @Test
    fun an_unknown_version_parses_to_null_never_a_guess() {
        val v2 = CodecMeasureWire.render(report()).replaceFirst("\"v\":1", "\"v\":2")
        assertNull(CodecMeasureWire.parse(v2))
        assertNull(CodecMeasureWire.parse("{\"rows\":[]}"))
        assertNull(CodecMeasureWire.parse("not json at all"))
        assertNull(CodecMeasureWire.parse("{\"v\":1}")) // no identity -> unreadable
    }

    @Test
    fun fingerprint_specials_survive_the_json_and_the_file_name_is_hex() {
        val r = report().copy(fingerprint = "quote\" back\\ slash/unié")
        val back = CodecMeasureWire.parse(CodecMeasureWire.render(r))
        assertEquals("quote\" back\\ slash/unié", back!!.fingerprint)
        val name = CodecMeasureWire.fileName(r.fingerprint)
        assertTrue("file name must be hex + .json: $name", name.matches(Regex("[0-9a-f]{64}\\.json")))
        // different fingerprints never collide in the file name
        assertNotEquals(name, CodecMeasureWire.fileName("other"))
    }

    // ---- the store (plan §5: files/codec-measure, tmp + rename) ----

    @Test
    fun the_store_round_trips_a_report_and_replaces_on_the_same_fingerprint() {
        val dir = Files.createTempDirectory("codec-measure-test").toFile()
        try {
            val store = CodecMeasureStore(dir)
            val r = report()
            store.save(r)
            assertEquals(r, store.latest(r.fingerprint))
            // same fingerprint: the NEW run replaces the old one
            val r2 = r.copy(rows = r.rows.take(2), startedMs = r.startedMs + 1)
            store.save(r2)
            assertEquals(r2, store.latest(r.fingerprint))
            // a different fingerprint is a different file, the old one intact
            val other = r2.copy(fingerprint = "other/build/OS.1")
            store.save(other)
            assertEquals(r2, store.latest(r.fingerprint))
            assertEquals(other, store.latest("other/build/OS.1"))
            // the raw copy payload is the exact file content
            assertEquals(CodecMeasureWire.render(r2), store.readRaw(r.fingerprint))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun an_empty_dir_reports_no_report_and_a_corrupt_file_degrades_to_null() {
        val dir = Files.createTempDirectory("codec-measure-empty").toFile()
        try {
            val store = CodecMeasureStore(dir)
            assertNull(store.latest("never/run"))
            assertNull(store.readRaw("never/run"))
            val f = File(dir, CodecMeasureWire.fileName("corrupt/build"))
            f.writeText("{ this is not json", StandardCharsets.UTF_8)
            assertNull(store.latest("corrupt/build"))
            // and a NEW save heals the file (tmp + rename overwrites it)
            val r = report().let { it.copy(fingerprint = "corrupt/build") }
            store.save(r)
            assertEquals(r, store.latest("corrupt/build"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
