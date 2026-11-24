package com.ritim.ritimcraw.data.grade2

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectFile
import com.ritim.ritimcraw.data.ProjectMigrations
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The golden v1 file + the F1-3 parity proofs (plan §10 layer 2):
 *
 *  - the SHIPPED chain converts the committed golden_v1_project.json into
 *    its v2 twin through the REAL gate (ProjectFile.open — backup, chain,
 *    atomic write), every fiber;
 *  - K2's BIT-EXACTNESS: for every clip in the golden file, the v1 parse
 *    and the migrated-v2 parse produce the SAME GradeK, and their
 *    GradeParcel encodes are bit-identical FloatArrays — the renderer's
 *    parameter packet unchanged, so existing renders cannot change pixels
 *    (the cheap, deterministic, device-free proof §10 layer 2 names);
 *  - §6.3's materialization: the never-graded source gains a NEUTRAL
 *    legacy record whose GradeK equals GradeK() through the adapter.
 */
class GoldenV1MigrationParityTest {

    /** Locates the committed golden fixture the NodeParcelTest way. */
    private fun golden(name: String): String {
        val suffixes = listOf(
            "src/test/resources/data/$name",
            "app/src/test/resources/data/$name",
        )
        val candidates = mutableListOf<File>()
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (s in suffixes) {
                val candidate = File(dir, s)
                candidates.add(candidate)
                if (candidate.exists()) return candidate.readText()
            }
            dir = dir.parentFile
            depth++
        }
        throw AssertionError(
            "cannot locate the committed golden $name; tried " +
                candidates.joinToString(", ") { it.absolutePath },
        )
    }

    @Test
    fun the_shipped_chain_converts_the_golden_v1_file_in_every_fiber() {
        val v1 = golden("golden_v1_project.json")
        @Suppress("UNCHECKED_CAST")
        val root = JsonParser(v1).parseValue() as Map<String, Any?>
        val migrated = when (val r = ProjectMigrations.migrate(root)) {
            is ProjectMigrations.Result.Ok -> r.root
            is ProjectMigrations.Result.TooNew -> throw AssertionError("too new: ${r.fileVersion}")
        }

        // Every grades record is the v2 shape (the five real grades + the
        // materialized never-graded one).
        @Suppress("UNCHECKED_CAST")
        val grades = migrated["grades"] as Map<String, Any?>
        for (id in listOf("full-grade", "gyro-stab", "image-stab", "managed-lut", "minimal", "never-graded")) {
            val record = grades[id] as Map<*, *>
            assertEquals("record $id", 2.0, record["v2"])
            ClipGradeV2.parse(record) // parses cleanly
        }
        // The two presets and the three export snapshots.
        assertEquals(2, (migrated["presets"] as List<*>).size)
        for (p in migrated["presets"] as List<*>) {
            assertEquals(2.0, ((p as Map<*, *>)["grade"] as Map<*, *>)["v2"])
        }
        for (j in migrated["exports"] as List<*>) {
            assertEquals(2.0, ((j as Map<*, *>)["snapshot"] as Map<*, *>)["v2"])
        }
        // The untouched sections ride raw.
        assertEquals(2, (migrated["luts"] as List<*>).size)

        // The committed golden v2 twin: the gate's own bytes for THIS
        // fixture, so any change in the migration's wire output is a diff
        // the reviewer can see (regenerate by deleting the file and
        // re-running: the test rewrites it once, then locks it).
        val twin = goldenFileOrNull("golden_v2_project.json")
        if (twin == null) {
            val out = locateResourcesDir().resolve("golden_v2_project.json")
            val tmpProject = File.createTempFile("f13-gate", ".json")
            try {
                tmpProject.writeText(v1)
                ProjectFile.open(tmpProject, nowMillis = 1_758_000_000_000L)
                java.nio.file.Files.write(out, tmpProject.readText().toByteArray())
            } finally {
                tmpProject.delete()
            }
            return // first run: the twin was just (re)committed
        }
        val tmpProject = File.createTempFile("f13-gate", ".json")
        try {
            tmpProject.writeText(v1)
            ProjectFile.open(tmpProject, nowMillis = 1_758_000_000_000L)
            assertEquals(twin.readText(), tmpProject.readText())
        } finally {
            tmpProject.delete()
        }
    }

    private fun locateResourcesDir(): java.nio.file.Path {
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            val candidate = File(dir, "app/src/test/resources/data")
            if (candidate.isDirectory) return candidate.toPath()
            val candidate2 = File(dir, "src/test/resources/data")
            if (candidate2.isDirectory) return candidate2.toPath()
            dir = dir.parentFile
            depth++
        }
        throw AssertionError("cannot locate the resources data dir")
    }

    private fun goldenFileOrNull(name: String): File? {
        val suffixes = listOf(
            "src/test/resources/data/$name",
            "app/src/test/resources/data/$name",
        )
        var dir: File? = File(".").absoluteFile.canonicalFile
        var depth = 0
        while (dir != null && depth < 8) {
            for (s in suffixes) {
                val candidate = File(dir, s)
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
            depth++
        }
        return null
    }

    @Test
    fun migrated_records_reproduce_the_v1_gradek_bit_for_bit() {
        val v1 = golden("golden_v1_project.json")
        val v1Grades = GradeStore.parseGrades(v1)

        @Suppress("UNCHECKED_CAST")
        val root = JsonParser(v1).parseValue() as Map<String, Any?>
        val migrated = (ProjectMigrations.migrate(root) as ProjectMigrations.Result.Ok).root
        @Suppress("UNCHECKED_CAST")
        val v2Grades = migrated["grades"] as Map<String, Any?>

        for ((id, expected) in v1Grades) {
            val record = v2Grades[id] as Map<*, *>
            val adapter = ClipGradeV2.parse(record).toGradeK()
            assertEquals("gradeK mismatch on $id", expected.copy(version = GradeK.SCHEMA_VERSION), adapter)
            // THE PARCEL PROOF: the renderer's parameter packet is
            // bit-identical FloatArrays — pixels cannot change.
            assertEquals(
                "parcel mismatch on $id",
                com.ritim.ritimcraw.engine.GradeParcel.encode(expected).toList(),
                com.ritim.ritimcraw.engine.GradeParcel.encode(adapter!!).toList(),
            )
        }
        // The materialized record's GradeK is the neutral grade.
        val never = ClipGradeV2.parse(v2Grades["never-graded"] as Map<*, *>).toGradeK()
        assertEquals(GradeK(), never)

        // §7's gate proof: the v2 records fed through the adapter drive the
        // SAME stabilization verdicts the v1 records did (the gate is the
        // unchanged StabilizationGate.engineFor input contract).
        val gate = com.ritim.ritimcraw.engine.StabilizationGate
        for ((id, v1) in v1Grades) {
            val v2 = ClipGradeV2.parse(v2Grades[id] as Map<*, *>).toGradeK()!!
            assertEquals(
                "engine verdict mismatch on $id",
                gate.engineFor(v1, "/g.gcsv", imageAnalysisReady = true,
                    imageEnabled = true, gyroEnabled = true),
                gate.engineFor(v2, "/g.gcsv", imageAnalysisReady = true,
                    imageEnabled = true, gyroEnabled = true),
            )
        }
    }

    @Test
    fun the_gate_upgrades_the_golden_file_end_to_end_with_a_backup() {
        val dir = createTempDir()
        try {
            val file = File(dir, "project.json")
            file.writeText(golden("golden_v1_project.json"))
            val outcome = ProjectFile.open(file, nowMillis = 1_758_000_000_000L)
            assertTrue(outcome is ProjectFile.Outcome.Migrated)

            // The backup holds the golden bytes verbatim.
            assertEquals(
                golden("golden_v1_project.json"),
                (outcome as ProjectFile.Outcome.Migrated).backup.readText(),
            )
            // The live file is v2 and byte-STABLE on a second gate open
            // (the idempotence: Outcome.Open, no second backup).
            val live = file.readText()
            assertTrue(live.startsWith("{\"version\":2"))
            assertEquals(1, ProjectFile.backupDir(file).listFiles()!!.size)
            assertTrue(ProjectFile.open(file, nowMillis = 1_758_000_000_000L) is ProjectFile.Outcome.Open)
            assertEquals(live, file.readText())
            assertEquals(1, ProjectFile.backupDir(file).listFiles()!!.size)

            // Every store reads the migrated file: the QUEUED job kept its
            // queue position and grade; the RUNNING one FAILED honestly.
            val jobs = com.ritim.ritimcraw.export.ExportStore(file).all()
            assertEquals(3, jobs.size)
            assertEquals("job-queued", jobs.single { it.id == "job-queued" }.id)
            assertTrue(jobs.single { it.id == "job-queued" }.state == com.ritim.ritimcraw.export.State.QUEUED)
            assertTrue(jobs.single { it.id == "job-running" }.state == com.ritim.ritimcraw.export.State.FAILED)
            assertEquals(0.8f, GradeStore(file).get("full-grade").exposureEv, 0f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun a_store_write_on_the_migrated_golden_file_keeps_v2_records_raw() {
        // The raw carry: after the gate, a GradeK save on ONE clip must not
        // lose its siblings' v2 records (a node chain, a DRT record — the
        // section is re-rendered from the parsed file, only the target
        // record swapped). The ClipGradeV2 store path proves the same carry
        // in the other direction: a full v2 record survives a GradeK write.
        val dir = createTempDir()
        try {
            val file = File(dir, "project.json")
            file.writeText(golden("golden_v1_project.json"))
            ProjectFile.open(file)
            val fullBefore = GradeStore(file).getV2("full-grade")!!

            GradeStore(file).put("minimal", GradeK(exposureEv = 9f))
            val after = file.readText()
            assertTrue(after.startsWith("{\"version\":2"))
            // The sibling's v2 record survived the write semantically raw:
            // same ClipGradeV2, every group and field.
            assertEquals(fullBefore, GradeStore(file).getV2("full-grade"))
            assertEquals(9f, GradeStore(file).get("minimal").exposureEv, 0f)

            // And the v2 write path keeps a GradeK sibling intact too.
            GradeStore(file).put(
                "minimal",
                ClipGradeV2(
                    nodes = listOf(GradeNodeV2(lift = NodeWheelV2(0.2f, 0.1f))),
                    output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT),
                ),
            )
            assertEquals(GradeK(), GradeStore(file).get("minimal").copy(version = GradeK.SCHEMA_VERSION))
            val minimal = GradeStore(file).getV2("minimal")!!
            assertEquals(1, minimal.nodes.size)
            assertEquals(0.2f, minimal.nodes[0].lift.x, 0f)
            assertEquals(OutputTransformV2.MODE_OPENDRT, minimal.output.mode)

            // THE RAW CARRY ITSELF: a plain GradeK save on ANOTHER clip
            // must not drop the node/opendrt record (the adaptable-subset
            // rewrite would have lost it — the sibling-preservation rule).
            GradeStore(file).put("gyro-stab", GradeK(exposureEv = 3f))
            val afterV1Put = GradeStore(file).getV2("minimal")!!
            assertEquals(1, afterV1Put.nodes.size)
            assertEquals(OutputTransformV2.MODE_OPENDRT, afterV1Put.output.mode)
            assertEquals(3f, GradeStore(file).get("gyro-stab").exposureEv, 0f)
            // remove() carries the same way: deleting one clip keeps the
            // node record.
            GradeStore(file).remove("managed-lut")
            assertEquals(1, GradeStore(file).getV2("minimal")!!.nodes.size)
            // The other clips still parse through the adapter.
            assertEquals(
                0.8f,
                GradeStore(file).get("full-grade").exposureEv,
                0f,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}

private fun createTempDir(): File =
    File(System.getProperty("java.io.tmpdir"), "f13-golden-" + System.nanoTime()).apply { mkdirs() }
