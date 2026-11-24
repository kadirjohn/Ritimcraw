package com.ritim.ritimcraw.data.grade2

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectMigrations
import com.ritim.ritimcraw.data.grade2.GradeV2Migration.fromV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIGRATIONS[0] (schema 1 -> 2, F1-3): the whole-project conversion, its
 * field mapping, the §6.3 materialization rule, the K13 export-queue rule,
 * row tolerance and idempotence — all on plain maps (the step is pure, the
 * way ProjectMigrationsTest tests its chain).
 */
class GradeV2MigrationTest {

    private fun parseRoot(text: String): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return JsonParser(text).parseValue() as Map<String, Any?>
    }

    private fun parseV1(text: String): Map<String, Any?> = parseRoot(text)

    @Test
    fun the_project_chain_now_ships_the_v2_step() {
        assertEquals(2, ProjectMigrations.CURRENT_VERSION)
        assertEquals(1, ProjectMigrations.MIGRATIONS.size)
        val result = ProjectMigrations.migrate(
            mapOf("version" to 1.0, "grades" to mapOf<String, Any?>()),
        ) as ProjectMigrations.Result.Ok
        // The chain re-stamps the version AFTER the step returns.
        assertEquals(2.0, result.root["version"])
        assertTrue(result.migrated)
    }

    @Test
    fun v1_root_converts_every_fiber() {
        val root = parseV1(
            """{"version":1,
               "grades":{"clip-a":{"version":1,"exposureEv":0.8,"tempK":4600.0,"tint":3.0,
                 "stabilization":true,"stabilizationStrength":70.0,"stabilizationEngine":1}},
               "sources":[{"id":"src-1","uri":"content://x","kind":"MCRAW"}],
               "presets":[{"id":"pres-1","name":"Look","grade":{"version":1,"contrast":9.0}}],
               "exports":[{"id":"job-1","sourceId":"clip-a","snapshot":{"version":1,"exposureEv":0.5},
                 "target":"MP4","state":"QUEUED"}]}""",
        )
        val out = fromV1(root)

        // The grades fiber: nested shape, K10's stab with engine explicit.
        @Suppress("UNCHECKED_CAST")
        val grades = out["grades"] as Map<String, Any?>
        val a = grades["clip-a"] as Map<*, *>
        assertEquals(2.0, a["v2"])
        val develop = a["develop"] as Map<*, *>
        assertEquals(0.8, develop["exposureEv"])
        assertEquals(4600.0, develop["tempK"])
        val stab = (a["transform"] as Map<*, *>)["stab"] as Map<*, *>
        assertEquals(1.0, stab["engine"]) // carried, explicit
        assertEquals(70.0, stab["strength"])

        // The materialized inputless clip (§6.3.1): src-1 has no record, so
        // an explicit neutral legacy record exists.
        val materialized = grades["src-1"] as Map<*, *>
        assertEquals(2.0, materialized["v2"])
        assertEquals("legacy", ((materialized["output"] as Map<*, *>)["mode"]))

        // The presets fiber.
        val preset = (out["presets"] as List<*>).single() as Map<*, *>
        assertEquals(2.0, ((preset["grade"] as Map<*, *>)["v2"]))

        // The exports fiber: a QUEUED job keeps its state, snapshot migrated.
        val job = (out["exports"] as List<*>).single() as Map<*, *>
        assertEquals("QUEUED", job["state"])
        assertEquals(2.0, ((job["snapshot"] as Map<*, *>)["v2"]))
    }

    @Test
    fun k13_running_jobs_fail_with_reason_queued_jobs_wait() {
        val root = parseV1(
            """{"exports":[
               {"id":"r","sourceId":"a","snapshot":{"version":1},"target":"MP4","state":"RUNNING","framesDone":3,"framesTotal":90},
               {"id":"q","sourceId":"a","snapshot":{"version":1},"target":"MP4","state":"QUEUED"},
               {"id":"d","sourceId":"a","snapshot":{"version":1},"target":"DNG","state":"DONE","detail":"ok"}]}""",
        )
        val jobs = (fromV1(root)["exports"] as List<*>).map { it as Map<*, *> }
        val running = jobs.first { it["id"] == "r" }
        assertEquals("FAILED", running["state"])
        assertTrue((running["detail"] as String).contains("queue the export again"))
        assertEquals("QUEUED", jobs.first { it["id"] == "q" }["state"])
        assertEquals("DONE", jobs.first { it["id"] == "d" }["state"])
        // DONE keeps its own detail; the migration never overwrites it.
        assertEquals("ok", jobs.first { it["id"] == "d" }["detail"])
    }

    /**
     * §7's test vector (v), the exports snapshot leg: the SAME stab
     * folding rules (i)-(iii) apply to exports[].snapshot — carried engine,
     * explicit 0 when absent, no group when off — and read back through
     * the GradeK adapter (the export path's StabilizationGate input).
     */
    @Test
    fun exports_snapshot_stab_folding_follows_the_same_rules() {
        val root = parseV1(
            """{"exports":[
               {"id":"gyro","sourceId":"a","snapshot":{"version":1,"stabilization":true,
                  "stabilizationMode":2,"stabilizationStrength":70.0},"target":"MP4","state":"QUEUED"},
               {"id":"image","sourceId":"a","snapshot":{"version":1,"stabilization":true,
                  "stabilizationEngine":1},"target":"MP4","state":"QUEUED"},
               {"id":"off","sourceId":"a","snapshot":{"version":1,"stabilization":false,
                  "stabilizationStrength":99.0},"target":"MP4","state":"QUEUED"}]}""",
        )
        @Suppress("UNCHECKED_CAST")
        val records = (fromV1(root)["exports"] as List<*>)
            .associate { j -> (j as Map<*, *>)["id"] as String to j }

        // (i) engine absent -> explicit 0, all sub values carried.
        val gyroStab = stabOfSnapshot(records.getValue("gyro"))!!
        assertEquals(0.0, gyroStab["engine"])
        assertEquals(2.0, gyroStab["mode"])
        assertEquals(70.0, gyroStab["strength"])
        // (ii) engine 1 carried.
        assertEquals(1.0, stabOfSnapshot(records.getValue("image"))!!["engine"])
        // (iii) off -> no group at all.
        assertNull(stabOfSnapshot(records.getValue("off")))
    }

    private fun stabOfSnapshot(job: Map<*, *>): Map<*, *>? {
        val snapshot = job["snapshot"] as Map<*, *>
        return ((snapshot["transform"] as Map<*, *>?)?.get("stab")) as Map<*, *>?
    }

    @Test
    fun stab_folding_follows_the_absent_key_rules() {
        // (i) stabilization true, engine ABSENT -> engine 0 explicit.
        val gyro = fromV1(parseV1("""{"grades":{"a":{"stabilization":true,"stabilizationMode":2}}}"""))
        val gyroStab = stabOf(gyro, "a")!!
        assertEquals(0.0, gyroStab["engine"])
        assertEquals(2.0, gyroStab["mode"])
        // Sub-values the v1 record never wrote default honestly.
        assertEquals(50.0, gyroStab["strength"])

        // (ii) engine 1 -> carried.
        val image = fromV1(parseV1("""{"grades":{"a":{"stabilization":true,"stabilizationEngine":1}}}"""))
        assertEquals(1.0, stabOf(image, "a")!!["engine"])

        // (iii) off / absent -> no group at all.
        val off = fromV1(parseV1("""{"grades":{"a":{"stabilization":false,"stabilizationStrength":99.0}}}"""))
        assertNull(stabOf(off, "a"))
        val absent = fromV1(parseV1("""{"grades":{"a":{"exposureEv":1.0}}}"""))
        assertNull(stabOf(absent, "a"))
    }

    private fun stabOf(root: Map<String, Any?>, id: String): Map<*, *>? {
        @Suppress("UNCHECKED_CAST")
        val grades = root["grades"] as Map<String, Any?>
        val record = grades[id] as Map<*, *>
        return (record["transform"] as Map<*, *>)["stab"] as Map<*, *>?
    }

    @Test
    fun crop_and_wheel_fields_fold_into_their_groups() {
        val root = fromV1(
            parseV1(
                """{"grades":{"a":{"cropAspect":1,"cropX":0.25,"cropW":0.5,"straightenDeg":7.5,
                   "wheelShadowX":0.3,"splitShadowHue":210.0,"splitBalance":-30.0}}}""",
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val record = (root["grades"] as Map<String, Any?>)["a"] as Map<*, *>
        val transform = record["transform"] as Map<*, *>
        assertEquals(1.0, transform["cropAspect"])
        assertEquals(0.25, transform["cropX"])
        assertEquals(7.5, transform["straightenDeg"])
        val legacy = record["legacy"] as Map<*, *>
        assertEquals(0.3, legacy["wheelShadowX"])
        assertEquals(210.0, legacy["splitShadowHue"])
        assertEquals(-30.0, legacy["splitBalance"])
        // Neutral crop + no stab: the group renders empty.
        val neutral = fromV1(parseV1("""{"grades":{"a":{"exposureEv":1.0}}}"""))
        @Suppress("UNCHECKED_CAST")
        val n = (neutral["grades"] as Map<String, Any?>)["a"] as Map<*, *>
        assertTrue((n["transform"] as Map<*, *>).isEmpty())
    }

    @Test
    fun lut_fields_fold_into_output_legacy() {
        val root = fromV1(
            parseV1("""{"grades":{"a":{"lutId":"cube1","lutStrength":40.0,"tonemap":2}}}"""),
        )
        @Suppress("UNCHECKED_CAST")
        val record = (root["grades"] as Map<String, Any?>)["a"] as Map<*, *>
        val output = record["output"] as Map<*, *>
        assertEquals("legacy", output["mode"])
        val legacyOut = output["legacy"] as Map<*, *>
        assertEquals("cube1", legacyOut["lutId"])
        assertEquals(40.0, legacyOut["lutStrength"])
        assertEquals(2.0, legacyOut["tonemap"])
        // lutInterp (a nested object) rides raw.
        val interp = fromV1(
            parseV1("""{"grades":{"a":{"lutInterp":{"role":3,"in":{"g":2,"t":4}}}}}"""),
        )
        @Suppress("UNCHECKED_CAST")
        val carried = ((interp["grades"] as Map<String, Any?>)["a"] as Map<*, *>)
            .let { it["output"] as Map<*, *> }["legacy"] as Map<*, *>
        assertEquals(3.0, (carried["lutInterp"] as Map<*, *>)["role"])
    }

    @Test
    fun as_shot_derives_from_wb_neutrality_like_the_v1_parser() {
        val manual = fromV1(parseV1("""{"grades":{"a":{"tempK":4600.0,"tint":3.0}}}"""))
        @Suppress("UNCHECKED_CAST")
        val manualDev = ((manual["grades"] as Map<String, Any?>)["a"] as Map<*, *>)["develop"] as Map<*, *>
        assertEquals(false, manualDev["asShot"])
        val neutral = fromV1(parseV1("""{"grades":{"a":{}}}"""))
        @Suppress("UNCHECKED_CAST")
        val neutralDev = ((neutral["grades"] as Map<String, Any?>)["a"] as Map<*, *>)["develop"] as Map<*, *>
        assertEquals(true, neutralDev["asShot"])
    }

    @Test
    fun the_step_is_idempotent() {
        val v1 = parseV1("""{"grades":{"a":{"exposureEv":0.8,"stabilization":true}}}""")
        val once = fromV1(v1)
        val twice = fromV1(once)
        assertEquals(once, twice)
        // And through the chain a second time (the version guard stops the
        // step from running again, but a hand-assembled longer chain that
        // would must not double-convert).
        val chain = listOf<(Map<String, Any?>) -> Map<String, Any?>>(::fromV1)
        val throughChain = ProjectMigrations.migrate(v1, chain) as ProjectMigrations.Result.Ok
        assertEquals(once + ("version" to 2.0), throughChain.root)
    }

    @Test
    fun row_tolerance_carries_unreadable_records_raw() {
        // A grade record that is not an object, a job that is not an object:
        // carried raw, never dropped, never guessed.
        val root = fromV1(
            mapOf(
                "grades" to mapOf("bad" to "not an object", "good" to mapOf("v2" to 2.0)),
                "exports" to listOf("just a string", mapOf("id" to "ok")),
                "presets" to listOf(42),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val grades = root["grades"] as Map<String, Any?>
        assertEquals("not an object", grades["bad"])
        assertEquals(2.0, (grades["good"] as Map<*, *>)["v2"]) // v2 untouched
        assertEquals("just a string", (root["exports"] as List<*>)[0])
        assertEquals(42, (root["presets"] as List<*>)[0])
    }

    @Test
    fun missing_sections_pass_through_untouched() {
        val root = mapOf(
            "version" to 1.0,
            "luts" to listOf(mapOf("id" to "lut-1")),
            "future" to mapOf("x" to 1.0),
        )
        val out = fromV1(root)
        assertEquals(root["luts"], out["luts"])
        assertEquals(root["future"], out["future"])
        assertNull(out["grades"])
    }

    @Test
    fun materialization_only_fills_real_missing_ids() {
        val root = parseV1(
            """{"grades":{"a":{"v2":2.0},"raw":"carried"},
               "sources":[{"id":"a"},{"id":"b"},{"id":42},{"noid":"x"}]}""",
        )
        val out = fromV1(root)
        @Suppress("UNCHECKED_CAST")
        val grades = out["grades"] as Map<String, Any?>
        // 'a' keeps its v2 record; 'b' gains a materialized one; a source
        // with no string id does not.
        assertEquals(2.0, (grades["a"] as Map<*, *>)["v2"])
        assertEquals(2.0, (grades["b"] as Map<*, *>)["v2"])
        assertEquals("carried", grades["raw"])
        assertNull(grades["42"])
        assertNull(grades["noid"])
    }
}
