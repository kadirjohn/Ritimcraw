package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.DevelopV2
import com.ritim.ritimcraw.data.grade2.GradeV2Fiber
import com.ritim.ritimcraw.data.grade2.toGradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The D1 RAW Levels override's JVM locks (night C4, docs/DCG_PLAN.md D1 /
 * report P1): the value type, the Kotlin resolver's equivalence with the
 * core's stage-1 rule, the persistence omission rule, and the revert
 * action — all pure JVM, no .so needed.
 *
 * WHAT THIS FILE LOCKS:
 *   - [RawLevels]'s sentinel contract: DEFAULT is all-negative, isDefault
 *     is exact, reverted() (the "metadata'ya dön" action) yields DEFAULT;
 *   - validate()'s honest refusals (range, white-above-black), the same
 *     rules the core applies;
 *   - [resolveRawLevels]'s chain — override > frame dynamic > container
 *     metadata — giving the SAME answer the core's resolveRawLevels /
 *     developCpu stage-1 expression gives for the same inputs (the
 *     resolver-equivalence proof, mirrored per branch: no frame levels,
 *     dynamic black only, dynamic white only, both, per-slot overrides);
 *   - the WIRE omission rule: a null override writes NO rawLevels key
 *     (byte-identity for untouched clips), a set override writes all
 *     eight values, the round trip is exact;
 *   - a malformed record (half-set, out of range, white below black)
 *     parses as null — the row-tolerant refusal, metadata semantics;
 *   - persistence: putRawLevels writes and reloads, the revert erases the
 *     key, and a slider move (putPreservingChain) does NOT erase it.
 */
class RawLevelsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun projectFile(): File {
        val dir = tmp.newFolder()
        return File(dir, "project.json")
    }

    private fun parseRecord(text: String): ClipGradeV2 {
        val v = JsonParser(text).parseValue()
        @Suppress("UNCHECKED_CAST")
        return ClipGradeV2.parse(v as Map<String, Any?>)
    }

    // ---- the value type ----------------------------------------------------

    @Test
    fun default_is_all_sentinels_and_reverted_yields_it() {
        assertTrue(RawLevels.DEFAULT.isDefault())
        assertTrue(RawLevels().isDefault())
        // A set white, a set black, a set clip: each alone defeats default.
        assertFalse(RawLevels(white = 16383f).isDefault())
        assertFalse(RawLevels(blackR = 1023f).isDefault())
        assertFalse(RawLevels(clipG = 0.998f).isDefault())
        // "metadata'ya dön": the revert is the ERASURE, not a metadata write.
        val set = RawLevels(white = 16352f, blackR = 1023f, clipG = 0.99f)
        assertEquals(RawLevels.DEFAULT, set.reverted())
        assertTrue(set.reverted().isDefault())
    }

    @Test
    fun validate_refuses_honest_and_accepts_valid() {
        // Valid: the DCG-style record (black pedestal 1023, white 16383).
        val valid = RawLevels(
            white = 16383f, blackR = 1023f, blackGr = 1023f, blackGb = 1023f,
            blackB = 1023f, clipR = 0.46f, clipG = 0.998f, clipB = 0.72f,
        )
        assertNull(valid.validate())
        // Range refusals.
        assertTrue(RawLevels(white = 70000f).validate()!!.contains("white"))
        assertTrue(RawLevels(blackB = -0.5f + 0.4f - 0.4f + 70000f).validate()!!.contains("black"))
        assertTrue(RawLevels(clipR = 1.5f).validate()!!.contains("clip"))
        // White must be strictly above every SET black (unset blacks don't gate).
        assertTrue(
            RawLevels(white = 1023f, blackR = 1023f).validate()!!
                .contains("not above"),
        )
        assertNull(RawLevels(white = 1024f, blackR = 1023f, blackGr = -1f).validate())
    }

    // ---- the resolver: the core's chain, mirrored --------------------------

    @Test
    fun resolver_matches_the_core_chain_per_branch() {
        val containerBlack = intArrayOf(1023, 1023, 1023, 1023)
        val containerWhite = 16383

        // (a) no frame levels (the -1 sentinels): container metadata.
        run {
            val (w, b) = resolveRawLevels(
                RawLevels.DEFAULT,
                intArrayOf(-1, -1, -1, -1), -1, containerBlack, containerWhite,
            )
            assertEquals(16383f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(1023f, 1023f, 1023f, 1023f)))
        }
        // (b) dynamic black only: the frame's blacks, container white
        //     (the all-or-nothing useFrameBlack rule, keyed on slot 0).
        run {
            val (w, b) = resolveRawLevels(
                RawLevels.DEFAULT,
                intArrayOf(1100, 1101, 1102, 1103), -1, containerBlack, containerWhite,
            )
            assertEquals(16383f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(1100f, 1101f, 1102f, 1103f)))
        }
        // (c) dynamic white only: frame white, container blacks.
        run {
            val (w, b) = resolveRawLevels(
                RawLevels.DEFAULT,
                intArrayOf(-1, -1, -1, -1), 15000, containerBlack, containerWhite,
            )
            assertEquals(15000f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(1023f, 1023f, 1023f, 1023f)))
        }
        // (d) both dynamic: frame wins over the container everywhere.
        run {
            val (w, b) = resolveRawLevels(
                RawLevels.DEFAULT,
                intArrayOf(900, 901, 902, 903), 16000, containerBlack, containerWhite,
            )
            assertEquals(16000f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(900f, 901f, 902f, 903f)))
        }
        // (e) the override beats the frame, per slot: one black overridden.
        run {
            val lv = RawLevels(blackGb = 2048f)
            val (w, b) = resolveRawLevels(
                lv, intArrayOf(1100, 1101, 1102, 1103), 15000, containerBlack, containerWhite,
            )
            assertEquals(15000f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(1100f, 1101f, 2048f, 1103f)))
        }
        // (f) the white override beats the frame's dynamic white.
        run {
            val lv = RawLevels(white = 15800f)
            val (w, b) = resolveRawLevels(
                lv, intArrayOf(1100, 1101, 1102, 1103), 15000, containerBlack, containerWhite,
            )
            assertEquals(15800f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(1100f, 1101f, 1102f, 1103f)))
        }
        // (g) everything overridden: frame AND container ignored.
        run {
            val lv = RawLevels(white = 16300f, blackR = 100f, blackGr = 101f, blackGb = 102f, blackB = 103f)
            val (w, b) = resolveRawLevels(
                lv, intArrayOf(1100, 1101, 1102, 1103), 15000, containerBlack, containerWhite,
            )
            assertEquals(16300f, w, 0f)
            assertTrue(b.contentEquals(floatArrayOf(100f, 101f, 102f, 103f)))
        }
    }

    // ---- the candidate verdict ---------------------------------------------

    @Test
    fun candidate_verdict_and_apply_refusal() {
        // A measured candidate: the verdict is MEASURED and the override is
        // the measured numbers (the "Ölçümden al" action's pure half).
        val cand = RawLevelsCandidate(
            isCandidate = true,
            white = 16352f,
            black = floatArrayOf(1023f, 1023f, 1023f, 1023f),
            clip = floatArrayOf(0.46f, 0.998f, 0.72f),
            maxNorm = floatArrayOf(0.46f, 0.998f, 0.72f),
            plateau = longArrayOf(182, 500, 152),
            topCount = longArrayOf(182, 500, 152),
        )
        assertEquals(RawLevelsCandidate.Confidence.MEASURED, cand.confidence())
        val override = cand.toOverrideOrNull()!!
        assertEquals(16352f, override.white, 0f)
        assertTrue(override.blackArray().contentEquals(floatArrayOf(1023f, 1023f, 1023f, 1023f)))
        assertTrue(override.clipArray().contentEquals(floatArrayOf(0.46f, 0.998f, 0.72f)))
        assertNull(override.validate())

        // No candidate: INSUFFICIENT, and applying NOTHING is a refusal —
        // never a fabricated override (the report's rule).
        val none = RawLevelsCandidate(isCandidate = false)
        assertEquals(RawLevelsCandidate.Confidence.INSUFFICIENT, none.confidence())
        assertNull(none.toOverrideOrNull())
    }

    // ---- the wire: omission, round trip, tolerance --------------------------

    @Test
    fun null_override_writes_no_key_and_default_record_bytes_unchanged() {
        // Byte-identity: an untouched clip's record carries NO rawLevels key,
        // so every file this build writes for an untouched clip is
        // byte-identical to the pre-D1 format.
        val without = GradeV2Fiber.render(ClipGradeV2())
        assertFalse(without.contains("rawLevels"))
        // The develop group's tail is unchanged (the key appends AFTER detail).
        assertTrue(without.contains("\"detail\":0.0}"))
    }

    @Test
    fun set_override_writes_all_eight_values_and_round_trips() {
        val lv = RawLevels(
            white = 16352f, blackR = 1023f, blackGr = 1024f, blackGb = 1025f,
            blackB = 1026f, clipR = 0.46f, clipG = 0.998f, clipB = 0.72f,
        )
        val record = ClipGradeV2(develop = DevelopV2(rawLevels = lv))
        val rendered = GradeV2Fiber.render(record)
        for (key in listOf("rawLevels", "white", "blackR", "blackGr", "blackGb", "blackB", "clipR", "clipG", "clipB")) {
            assertTrue("missing $key", rendered.contains("\"$key\":"))
        }
        assertEquals(lv, parseRecord(rendered).develop.rawLevels)
        // The whole record still parses equal (the group rides with the rest).
        assertEquals(record, parseRecord(rendered))
    }

    @Test
    fun sentinel_values_ride_verbatim_and_parse_back() {
        // A partially-set override (only the white; blacks/clips at their
        // sentinels) is a REAL record — the sentinels ARE the "not set"
        // values, each slot independent, so the wire carries them
        // verbatim and the parse reproduces them exactly.
        val lv = RawLevels(white = 14335f)
        val parsed = parseRecord(GradeV2Fiber.render(ClipGradeV2(develop = DevelopV2(rawLevels = lv))))
        assertEquals(lv, parsed.develop.rawLevels)
        assertFalse(parsed.develop.rawLevels!!.isDefault())
    }

    @Test
    fun all_sentinel_record_parses_as_null() {
        // A record whose eight values are ALL sentinels (a hand-edited file,
        // or a writer that predates the isDefault short-circuit) parses as
        // null: no override, metadata semantics.
        val text = """{"v2":2,"develop":{"exposureEv":0.0,"rawLevels":{"white":-1.0,"blackR":-1.0,"blackGr":-1.0,"blackGb":-1.0,"blackB":-1.0,"clipR":-1.0,"clipG":-1.0,"clipB":-1.0}}}"""
        val parsed = parseRecord(text)
        assertNull(parsed.develop.rawLevels)
    }

    @Test
    fun malformed_records_parse_as_null_never_half_set() {
        // (a) a partial record (missing keys) is LEGAL: each value is read
        //     independently with the sentinel fallback — a white-only record
        //     overrides only the white (the per-slot semantics the override
        //     itself resolves by).
        run {
            val text = """{"v2":2,"develop":{"rawLevels":{"white":16352.0,"blackR":1023.0}}}"""
            val lv = parseRecord(text).develop.rawLevels!!
            assertEquals(16352f, lv.white, 0f)
            assertEquals(1023f, lv.blackR, 0f)
            assertEquals(-1f, lv.blackGr, 0f)  // absent = not set
        }
        // (b) out of range: null (never clamped, never a guess).
        run {
            val text = """{"v2":2,"develop":{"rawLevels":{"white":70000.0,"blackR":1023.0,"blackGr":1023.0,"blackGb":1023.0,"blackB":1023.0,"clipR":-1.0,"clipG":-1.0,"clipB":-1.0}}}"""
            assertNull(parseRecord(text).develop.rawLevels)
        }
        // (c) white at a set black: null (the core's rule).
        run {
            val text = """{"v2":2,"develop":{"rawLevels":{"white":1023.0,"blackR":1023.0,"blackGr":-1.0,"blackGb":-1.0,"blackB":-1.0,"clipR":-1.0,"clipG":-1.0,"clipB":-1.0}}}"""
            assertNull(parseRecord(text).develop.rawLevels)
        }
    }

    // ---- persistence ---------------------------------------------------------

    @Test
    fun putrawlevels_persists_reloads_and_revert_erases_the_key() {
        val file = projectFile()
        val store = GradeStore(file)
        val lv = RawLevels(white = 16352f, blackR = 1023f, blackGr = 1023f, blackGb = 1023f, blackB = 1023f)

        // Set: the record carries it, reload keeps it.
        store.putRawLevels("c1", lv)
        assertEquals(lv, GradeStore(file).getV2("c1")!!.develop.rawLevels)

        // Revert ("metadata'ya dön"): the KEY is gone (not a metadata write).
        val revertedStore = GradeStore(file)
        revertedStore.putRawLevels("c1", null)
        val reloaded = GradeStore(file)
        assertNull(reloaded.getV2("c1")!!.develop.rawLevels)
        assertFalse(file.readText().contains("rawLevels"))
    }

    @Test
    fun a_slider_move_does_not_erase_the_override() {
        // The editor's slider write goes through GradeK, which cannot
        // express the override — putPreservingChain must carry it like it
        // carries the node chain (the F1-8a discipline).
        val file = projectFile()
        val store = GradeStore(file)
        val lv = RawLevels(white = 16352f)
        store.putRawLevels("c1", lv)
        store.putPreservingChain("c1", GradeK(exposureEv = 1.5f))
        val after = GradeStore(file).getV2("c1")!!
        assertEquals(lv, after.develop.rawLevels)
        assertEquals(1.5f, after.toGradeK()!!.exposureEv, 0f)
    }

    @Test
    fun old_project_file_loads_with_metadata_semantics() {
        // A pre-D1 project.json: no rawLevels key anywhere. The record
        // parses with a null override and the adapter's GradeK is the
        // neutral grade — the old look is preserved exactly.
        val file = projectFile()
        file.parentFile?.mkdirs()
        file.writeText(
            """{"version":1,"grades":{"old-clip":{"v2":2,"develop":{"exposureEv":0.8,"tempK":5600.0,"tint":0.0,"asShot":true,"highlightReconstruction":true,"keepVignetting":true,"noiseReduction":0.0,"chromaDenoise":0.0,"temporalNr":false,"temporalStrength":0.0,"sharpness":0.0,"detail":0.0},"legacy":{"highlights":0.0,"blackPoint":0.0,"whitePoint":0.0,"shadows":0.0,"midtones":0.0,"contrast":0.0,"saturation":0.0,"wheelShadowX":0.0,"wheelShadowY":0.0,"wheelMidX":0.0,"wheelMidY":0.0,"wheelHighX":0.0,"wheelHighY":0.0,"splitShadowHue":-1.0,"splitHighlightHue":-1.0,"splitBalance":0.0},"nodes":[],"output":{"mode":"legacy","legacy":{"tonemap":0.0,"lutId":null,"lutStrength":100.0},"opendrt":{"look":0.0,"tonescale":0.0,"cwp":0.0,"cwpLimit":0.25,"grey":10.0,"display":0.0}},"transform":{}}}}""",
            Charsets.UTF_8,
        )
        val v2 = GradeStore(file).getV2("old-clip")!!
        assertNull(v2.develop.rawLevels)
        assertEquals(0.8f, v2.toGradeK()!!.exposureEv, 0f)
    }
}
