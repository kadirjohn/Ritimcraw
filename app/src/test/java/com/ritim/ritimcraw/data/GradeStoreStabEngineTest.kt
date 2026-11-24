package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.engine.StabEngine
import com.ritim.ritimcraw.engine.StabilizationGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The image-stabilization engine field (S4, docs/IMAGE_STAB_PLAN.md 3.3 and
 * 3.7; COLOR_MODEL_V2_PLAN 7): the ONE new GradeK field, additive, schema
 * version unchanged. These tests lock the user-data guarantees:
 *  - no engine (gyro/off) -> the written grade is byte-identical to the
 *    pre-field format;
 *  - the key is written ONLY for the image engine, adds exactly that one
 *    key, and survives reload and sibling-section merges;
 *  - an absent key reads as 0 = GYRO ("no key means gyro"), so every old
 *    project resolves exactly as before;
 *  - a newer-schema file is still rejected and never overwritten.
 */
class GradeStoreStabEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val stabOn = GradeK(
        stabilization = true,
        stabilizationMode = 1,
        stabilizationStrength = 70f,
        stabilizationZoomLimit = 1.15f,
        stabilizationTrimMs = 12f,
        stabilizationFocalMm = 6.5f,
    )

    @Test
    fun gyro_and_off_grades_render_without_the_engine_key_byte_identical() {
        // Pre-field golden for a stab-on gyro grade: the engine=1 render with
        // exactly that one key removed. Any other drift fails this equality.
        val gyro = GradeStore.renderGrade(stabOn)
        val image = GradeStore.renderGrade(stabOn.copy(stabilizationEngine = 1))
        assertFalse(gyro.contains("stabilizationEngine"))
        assertTrue(image.contains(",\"stabilizationEngine\":1"))
        assertEquals(gyro, image.replace(",\"stabilizationEngine\":1", ""))
        // explicit 0 is the default: same bytes as never having set it.
        assertEquals(gyro, GradeStore.renderGrade(stabOn.copy(stabilizationEngine = 0)))
        // off grades never carry any stabilization key, engine included.
        val off = GradeStore.renderGrade(GradeK(stabilization = false, stabilizationEngine = 1))
        assertFalse(off.contains("stabilization"))
        assertEquals(GradeStore.renderGrade(GradeK()), off)
    }

    @Test
    fun default_grade_file_is_unchanged_by_the_new_field() {
        val f = File(tmp.root, "project.json")
        GradeStore(f).put("a", GradeK(exposureEv = 0.5f))
        assertFalse(f.readText().contains("stabilizationEngine"))
        // F1-3: the file speech is schema 2 now (fresh file stamps
        // CURRENT_VERSION = 2); the record carries stab folded (K10) —
        // and no engine key while the grade is gyro/off.
        assertTrue(f.readText().startsWith("{\"version\":2"))
    }

    @Test
    fun image_engine_survives_persist_and_reload_atomically() {
        val f = File(tmp.root, "project.json")
        val store = GradeStore(f)
        store.put("a", stabOn.copy(stabilizationEngine = 1))
        store.put("b", stabOn) // gyro sibling
        assertTrue(!File(tmp.root, "project.json.tmp").exists())

        val reloaded = GradeStore(f)
        assertEquals(1, reloaded.get("a").stabilizationEngine)
        assertEquals(70f, reloaded.get("a").stabilizationStrength, 0f)
        assertEquals(1, reloaded.get("a").stabilizationMode)
        assertEquals(12f, reloaded.get("a").stabilizationTrimMs, 0f) // kept, inert
        assertEquals(0, reloaded.get("b").stabilizationEngine)
        // other sections of the shared file survive the write
        f.writeText(f.readText().replace("\"grades\"", "\"sources\":[],\"grades\""))
        reloaded.put("c", GradeK(exposureEv = 1f))
        assertTrue(f.readText().contains("\"sources\":[]"))
        assertEquals(1, GradeStore(f).get("a").stabilizationEngine)
    }

    @Test
    fun absent_engine_key_reads_as_gyro_and_resolves_as_before() {
        val f = File(tmp.root, "project.json")
        f.writeText(
            "{\"version\":1,\"grades\":{\"old\":{\"version\":1,\"exposureEv\":0.8," +
                "\"stabilization\":true,\"stabilizationMode\":2,\"stabilizationStrength\":85.0}}}",
        )
        val g = GradeStore(f).get("old")
        assertTrue(g.stabilization)
        assertEquals(0, g.stabilizationEngine) // the rule: no key = gyro (0)
        assertEquals(2, g.stabilizationMode) // stored values untouched
        // resolves exactly like a pre-engine record: gyro, gated by the gyro
        // flag and a log — never the image engine, whatever the image flag.
        assertEquals(
            StabEngine.GYRO,
            StabilizationGate.engineFor(g, "/g.gcsv", imageAnalysisReady = true, imageEnabled = true, gyroEnabled = true),
        )
        assertEquals(
            StabEngine.OFF,
            StabilizationGate.engineFor(g, "/g.gcsv", imageAnalysisReady = true, imageEnabled = true, gyroEnabled = false),
        )
        // reading never rewrites the record
        assertEquals(0, GradeStore(f).get("old").stabilizationEngine)
        assertFalse(f.readText().contains("stabilizationEngine"))
    }

    @Test
    fun migrated_v1_parse_keeps_the_rule_and_the_too_new_behavior() {
        val v1 = "{\"version\":1,\"grades\":{\"a\":{\"stabilization\":true},\"b\":{\"stabilization\":true,\"stabilizationEngine\":1}}}"
        val parsed = GradeStore.parseGradesMigrated(v1)
        assertEquals(0, parsed.getValue("a").stabilizationEngine)
        assertEquals(1, parsed.getValue("b").stabilizationEngine)

        // a file from a NEWER schema is rejected whatever it carries, and a
        // store over it never writes (the engine key changes nothing here).
        // F1-3: schema 2 is ours; the probe is 3.
        val newer = "{\"version\":3,\"grades\":{\"a\":{\"stabilization\":true,\"stabilizationEngine\":1}}}"
        var tooNew: Int? = null
        assertTrue(GradeStore.parseGradesMigrated(newer) { tooNew = it }.isEmpty())
        assertEquals(3, tooNew)
        val f = File(tmp.root, "project.json")
        f.writeText(newer)
        val store = GradeStore(f)
        assertEquals(GradeK(), store.get("a"))
        store.put("a", stabOn.copy(stabilizationEngine = 1))
        assertEquals(newer, f.readText())
        assertEquals(3, store.rejectedNewerVersion)
    }

    @Test
    fun schema_version_does_not_move() {
        // The GradeK record's own flat-shape marker is still 1 (the v1 flat
        // renderer writes version:1 — it is the archive-grade API); the
        // FILE schema is 2 (F1-3), carried by the git-record marker `v2`.
        assertEquals(1, GradeK.SCHEMA_VERSION)
        assertEquals(2, ProjectMigrations.CURRENT_VERSION)
    }
}
