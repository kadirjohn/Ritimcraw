package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.engine.PreviewQuality
import com.ritim.ritimcraw.engine.RenderBackend
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * RenderBackendStore tests (owner request 2026-11-12, deliverable B): the
 * same coverage shape as PreviewQualityStoreTest — omit-when-default,
 * round trips, coexistence with the other project.json stores, corrupt
 * files, and deterministic mergeInto. The store is the PreviewQualityStore
 * pattern with its own top-level "renderBackend" scalar.
 */
class RenderBackendStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh store + its own project.json in a fresh subfolder. */
    private fun newStore(): Pair<RenderBackendStore, File> {
        val dir = tmp.newFolder()
        val f = File(dir, "project.json")
        return RenderBackendStore(f) to f
    }

    // ---- missing / old files ----

    @Test
    fun missing_file_reads_default() {
        assertEquals(RenderBackend.DEFAULT, newStore().first.get())
    }

    @Test
    fun old_file_without_key_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"grades":{"a":1}}""", StandardCharsets.UTF_8)
        assertEquals(RenderBackend.DEFAULT, s.get())
    }

    // ---- omit-when-default ----

    @Test
    fun writing_default_on_fresh_file_writes_minimal_shape() {
        val (s, f) = newStore()
        s.put(RenderBackend.DEFAULT)
        assertEquals("""{"version":1}""", f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun writing_default_byte_identical_when_already_default() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1}}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.put(RenderBackend.DEFAULT)
        assertEquals(before, f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun setting_then_default_removes_the_key() {
        val (s, f) = newStore()
        s.put(RenderBackend.AUTO)
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("renderBackend"))
        s.put(RenderBackend.DEFAULT)
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("renderBackend"))
        assertEquals(RenderBackend.DEFAULT, s.get())
    }

    // ---- round trips ----

    @Test
    fun every_backend_round_trips_through_the_file() {
        RenderBackend.entries.forEach { level ->
            val (s, f) = newStore()
            s.put(level)
            assertEquals(level, RenderBackendStore(f).get())
        }
    }

    // ---- coexistence with the other stores ----

    @Test
    fun survives_a_preview_quality_store_write() {
        // A preview-quality save must not drop the backend choice: the
        // stores share project.json and re-render each other's scalars.
        val f = tmp.newFile("project.json")
        RenderBackendStore(f).put(RenderBackend.GPU_EXPERIMENTAL)
        val pq = PreviewQualityStore(f)
        pq.put(PreviewQuality.HIGH)
        assertEquals(RenderBackend.GPU_EXPERIMENTAL, RenderBackendStore(f).get())
        assertEquals(PreviewQuality.HIGH, pq.get())
    }

    @Test
    fun survives_a_grade_store_write() {
        // A grade save must not drop the backend choice.
        val f = tmp.newFile("project.json")
        RenderBackendStore(f).put(RenderBackend.AUTO)
        val afterBackend = f.readText(StandardCharsets.UTF_8)
        val rewrittenByGradeStore = GradeStore.mergeInto(
            afterBackend,
            mapOf("clip-a" to GradeK()),
        )
        f.writeText(rewrittenByGradeStore, StandardCharsets.UTF_8)
        assertEquals(RenderBackend.AUTO, RenderBackendStore(f).get())
    }

    @Test
    fun preserves_other_sections_and_their_order() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1},"sources":["s"]}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.put(RenderBackend.AUTO)
        // Deterministic order: version first, existing entries in
        // encounter order, this store's key last.
        assertEquals(
            """{"version":1,"grades":{"a":1},"sources":["s"],"renderBackend":"AUTO"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }

    // ---- corrupt / hostile files ----

    @Test
    fun malformed_file_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"renderBackend":""", StandardCharsets.UTF_8)
        assertEquals(RenderBackend.DEFAULT, s.get())
    }

    @Test
    fun null_value_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"renderBackend":null}""", StandardCharsets.UTF_8)
        assertEquals(RenderBackend.DEFAULT, s.get())
    }

    @Test
    fun non_string_value_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"renderBackend":42}""", StandardCharsets.UTF_8)
        assertEquals(RenderBackend.DEFAULT, s.get())
    }

    @Test
    fun unknown_value_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"renderBackend":"VULKAN"}""", StandardCharsets.UTF_8)
        assertEquals(RenderBackend.DEFAULT, s.get())
    }

    // ---- mergeInto purity/determinism ----

    @Test
    fun merge_into_is_deterministic() {
        val existing = """{"version":1,"grades":{"a":1}}"""
        val a = RenderBackendStore.mergeInto(existing, RenderBackend.AUTO)
        val b = RenderBackendStore.mergeInto(existing, RenderBackend.AUTO)
        assertEquals(a, b)
    }

    @Test
    fun merge_into_null_existing_renders_fresh_file() {
        assertEquals(
            """{"version":1,"renderBackend":"AUTO"}""",
            RenderBackendStore.mergeInto(null, RenderBackend.AUTO),
        )
        assertEquals(
            """{"version":1}""",
            RenderBackendStore.mergeInto(null, RenderBackend.DEFAULT),
        )
    }

    @Test
    fun merge_into_replaces_a_previous_value() {
        val existing = """{"version":1,"renderBackend":"AUTO"}"""
        assertEquals(
            """{"version":1,"renderBackend":"GPU_EXPERIMENTAL"}""",
            RenderBackendStore.mergeInto(existing, RenderBackend.GPU_EXPERIMENTAL),
        )
    }

    @Test
    fun fresh_file_minimal_shape() {
        val (s, f) = newStore()
        s.put(RenderBackend.AUTO)
        assertEquals(
            """{"version":1,"renderBackend":"AUTO"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }
}
