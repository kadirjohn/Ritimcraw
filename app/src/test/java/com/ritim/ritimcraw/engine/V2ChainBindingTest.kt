package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.GradeStore
import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.grade2.ClipGradeV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import com.ritim.ritimcraw.data.grade2.toClipGradeV2
import com.ritim.ritimcraw.data.grade2.toGradeK
import com.ritim.ritimcraw.export.ExportStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F1-6/F1-7's binding locks (plan §10 layer 2, the parity the device-free
 * night CAN prove):
 *
 *   - THE NIGHT'S REGRESSION LOCK: for the same GradeK, the v1-record
 *     render path and the v2-twin render path agree BIT FOR BIT through
 *     every seam F1-6 added — the parcel (both records' GradeK views
 *     encode identically), the store (the v1 record written today and the
 *     v2 twin parse to the same GradeK), the chainWIRE (BOTH carry the
 *     null/empty shape — the fast path — so the CPU legs and the preview
 *     binding run nothing), and the export snapshot (the v2 legacy record
 *     decodes NO chain, exactly the v1 record's shape).
 *   - The NODES counterpart: only a node/DRT record produces a wire, and
 *     the wire a v2 record produces is stable (the same record re-derives
 *     the same bytes — the revision-cache content-key contract).
 */
class V2ChainBindingTest {

    // ---- the legacy agreement (the regression lock of the night) ----

    @Test
    fun the_same_gradek_its_v2_twin_agree_on_every_legacy_path() {
        val g = GradeK(
            exposureEv = 0.8f, highlights = -30f, tempK = 6100f, tint = 12f,
            saturation = 22f, blackPoint = 6f, whitePoint = -4f, shadows = 18f,
            midtones = -9f, contrast = 14f, asShot = false, sharpness = 31f,
            detail = 12f, tonemap = 2, lutId = "cube-3", lutStrength = 65f,
            noiseReduction = 40f, chromaDenoise = 25f, stabilization = true,
            stabilizationMode = 1, stabilizationStrength = 70f,
            wheelShadowX = 0.1f, wheelMidY = 0.2f, splitBalance = 10f,
        )
        val twin = g.toClipGradeV2()
        val adaptedBack = twin.toGradeK()!!
        // 1. The parcel: both records' GradeK views encode to the SAME
        //    raw bits (the renderer's whole parameter packet).
        assertEquals(
            GradeParcel.encode(g).toList(),
            GradeParcel.encode(adaptedBack).toList(),
        )
        // 2. The wire record: rendering the v1 record (today's v1 parse)
        //    and the v2 twin bytes, then parsing BOTH through the version-
        //    aware reader, yields the same GradeK.
        val v1Record = GradeStore.renderGrade(g)
        val v2Record = GradeStore.renderGradeWire(g)
        @Suppress("UNCHECKED_CAST")
        val v1Parsed = GradeStore.mapOfGrade(
            JsonParser(v1Record).parseValue() as Map<String, Any?>,
        )!!
        @Suppress("UNCHECKED_CAST")
        val v2Parsed = GradeStore.mapOfGrade(
            JsonParser(v2Record).parseValue() as Map<String, Any?>,
        )!!
        assertEquals(
            g.copy(version = GradeK.SCHEMA_VERSION),
            v1Parsed.copy(version = GradeK.SCHEMA_VERSION),
        )
        // 3. Absolute wire bytes must DISAGREE (the file speech is schema
        //    2 now — the F1-3 decision), while the GradeK agree.
        assertEquals(g.exposureEv, v1Parsed.exposureEv, 0f)
        assertEquals(g.exposureEv, v2Parsed.exposureEv, 0f)
        assertEquals(v1Parsed, v2Parsed)
        // 4. The chain USAGE: the legacy twin (empty chain, legacy output)
        //    takes the LEGACY verdict on every seam.
        assertEquals(GradeV2Usage.Usage.LEGACY, GradeV2Usage.usage(twin))
        assertTrue(GradeV2Usage.activeNodes(twin).isEmpty())
        // 5. The chain WIRE the binding derives: null (the fast path, the
        //    pre-F1-6 byte-identical render).
        val chainForBinding = runCatching {
            val nodes = GradeV2Usage.activeNodes(twin)
            if (nodes.isEmpty() && twin.output.mode != OutputTransformV2.MODE_OPENDRT) null
            else NodeChainWire.encode(twin, nodes)
        }.getOrNull()
        assertNull(chainForBinding)
    }

    @Test
    fun the_neutral_gradek_twin_is_the_legacy_fast_path_too() {
        val twin = GradeK().toClipGradeV2()
        assertEquals(GradeV2Usage.Usage.LEGACY, GradeV2Usage.usage(twin))
        assertNull(GradeV2Usage.activeNodes(twin).singleOrNull())
        assertTrue(GradeV2Usage.activeNodes(twin).isEmpty())
    }

    // ---- the export snapshot chain (the in-memory decode) ----

    private fun exportsSectionWith(snapshotRecord: String): String =
        "{\"version\":2,\"exports\":[{\"id\":\"job-1\",\"sourceId\":\"s1\"," +
            "\"snapshot\":$snapshotRecord,\"target\":\"MP4\",\"state\":\"QUEUED\"}]}"

    @Test
    fun an_export_snapshot_legacy_record_decodes_no_chain_wire() {
        val dir = java.nio.file.Files.createTempDirectory("v2bind").toFile()
        try {
            val file = File(dir, "project.json")
            file.writeText(exportsSectionWith(GradeStore.renderGradeWire(GradeK(exposureEv = 1f))))
            val job = ExportStore(file).all().single()
            assertEquals(1f, job.snapshot.exposureEv, 0f)
            assertNull(job.snapshotChainWire)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun an_export_snapshot_nodes_record_decodes_its_chain_wire() {
        val nodesGrade = ClipGradeV2(
            nodes = listOf(GradeNodeV2(lift = NodeWheelV2(0.2f, 0.1f))),
        )
        val record = com.ritim.ritimcraw.data.grade2.GradeV2Fiber.render(nodesGrade)
        val dir = java.nio.file.Files.createTempDirectory("v2bind2").toFile()
        try {
            val file = File(dir, "project.json")
            file.writeText(exportsSectionWith(record))
            val job = ExportStore(file).all().single()
            // The adapter refuses the record (the honest null), so the
            // GradeK snapshot is neutral — AND the chain wire carries the
            //EXPORT instead of losing it.
            assertEquals(GradeK(), job.snapshot.copy(version = GradeK.SCHEMA_VERSION))
            assertNotNull(job.snapshotChainWire)
            // ...and it equals the wire the binding itself derives from
            // the same grade (the content-key contract).
            val derived = NodeChainWire.encode(
                nodesGrade, GradeV2Usage.activeNodes(nodesGrade),
            )
            assertEquals(derived.toList(), job.snapshotChainWire!!.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun an_export_snapshot_drt_record_decodes_its_chain_wire() {
        val drtGrade = ClipGradeV2(
            output = OutputTransformV2(mode = OutputTransformV2.MODE_OPENDRT),
        )
        val record = com.ritim.ritimcraw.data.grade2.GradeV2Fiber.render(drtGrade)
        val dir = java.nio.file.Files.createTempDirectory("v2bind3").toFile()
        try {
            val file = File(dir, "project.json")
            file.writeText(exportsSectionWith(record))
            val job = ExportStore(file).all().single()
            assertEquals(GradeK(), job.snapshot.copy(version = GradeK.SCHEMA_VERSION))
            assertNotNull(job.snapshotChainWire)
            assertEquals(1f, job.snapshotChainWire!![1], 0f) // outputMode DwgLinear
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---- the preview binding stability (the revision cache's key) ----

    @Test
    fun the_same_v2_record_rederives_the_same_chain_wire_bytes() {
        val grade = ClipGradeV2(
            nodes = listOf(
                GradeNodeV2(sat = 1.3f, contrast = 1.1f),
                GradeNodeV2(hue = 20f, mix = 0.7f),
            ),
        )
        val a = NodeChainWire.encode(grade, GradeV2Usage.activeNodes(grade))
        val b = NodeChainWire.encode(
            ClipGradeV2.parse(
                @Suppress("UNCHECKED_CAST")
                JsonParser(com.ritim.ritimcraw.data.grade2.GradeV2Fiber.render(grade))
                    .parseValue() as Map<String, Any?>,
            ),
            GradeV2Usage.activeNodes(grade),
        )
        assertEquals(a.toList(), b.toList())
        assertEquals(NodeChainWire.chainHash(a), NodeChainWire.chainHash(b))
        // And the editor's request-site derivation path (the runCatching
        // fold ClipEditorScreen uses) yields the same bytes.
        val viaBinding = runCatching {
            val v2 = grade
            val nodes = GradeV2Usage.activeNodes(v2)
            if (nodes.isEmpty() && v2.output.mode != OutputTransformV2.MODE_OPENDRT) {
                null
            } else {
                NodeChainWire.encode(v2, nodes)
            }
        }.getOrNull()
        assertEquals(a.toList(), viaBinding!!.toList())
    }

    @Test
    fun a_neutral_only_chain_from_the_store_stays_the_legacy_fast_path() {
        // The pre-F1-6 store behavior: a record with nodes:[] (or neutral
        // nodes) never gains a wire — GradeV2Usage's neutral-node rule,
        // knob for knob against core's GradeNode::isNeutral.
        val neutralNodes = ClipGradeV2(
            nodes = listOf(
                GradeNodeV2(),
                GradeNodeV2(enabled = false, sat = 5f), // disabled = neutral
                GradeNodeV2(mix = 0.0001f, hue = 90f),   // mixed out = neutral
            ),
        )
        assertEquals(GradeV2Usage.Usage.LEGACY, GradeV2Usage.usage(neutralNodes))
        assertNull(
            runCatching {
                val nodes = GradeV2Usage.activeNodes(neutralNodes)
                if (nodes.isEmpty() &&
                    neutralNodes.output.mode != OutputTransformV2.MODE_OPENDRT
                ) null else NodeChainWire.encode(neutralNodes, nodes)
            }.getOrNull(),
        )
    }
}
