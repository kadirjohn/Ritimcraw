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
 * PreviewStatsStore tests (the trace-dims instrument's A/B toggle,
 * 2026-11-14): the same coverage shape as RenderBackendStoreTest —
 * omit-when-default, round trips, coexistence with the other
 * project.json stores, corrupt files, and deterministic mergeInto —
 * plus the toggle seam's tests (the recording-fake applier). The store
 * is the RenderBackendStore pattern with its own top-level
 * "previewStats" BOOLEAN scalar; the boolean wire form is the one
 * difference from the enum stores (JsonParser parses true/false
 * literals, ProjectJson.renderRaw re-renders them).
 */
class PreviewStatsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh store + its own project.json in a fresh subfolder. */
    private fun newStore(): Pair<PreviewStatsStore, File> {
        val dir = tmp.newFolder()
        val f = File(dir, "project.json")
        return PreviewStatsStore(f) to f
    }

    /** The recording fake the applier seam's tests ride. */
    private class RecordingApply : (Boolean) -> Unit {
        val calls = mutableListOf<Boolean>()
        override fun invoke(value: Boolean) {
            calls.add(value)
        }
    }

    // ---- missing / old files ----

    @Test
    fun missing_file_reads_default_on() {
        assertEquals(true, newStore().first.get())
    }

    @Test
    fun old_file_without_key_reads_default_on() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"grades":{"a":1}}""", StandardCharsets.UTF_8)
        assertEquals(true, s.get())
    }

    // ---- omit-when-default ----

    @Test
    fun writing_default_on_fresh_file_writes_minimal_shape() {
        val (s, f) = newStore()
        s.put(true)
        assertEquals("""{"version":1}""", f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun writing_default_byte_identical_when_already_default() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1}}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.put(true)
        assertEquals(before, f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun toggling_off_then_on_removes_the_key() {
        val (s, f) = newStore()
        s.put(false)
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("previewStats"))
        s.put(true)
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("previewStats"))
        assertEquals(true, s.get())
    }

    // ---- round trips ----

    @Test
    fun both_values_round_trip_through_the_file() {
        listOf(false, true).forEach { value ->
            val (s, f) = newStore()
            s.put(value)
            assertEquals(value, PreviewStatsStore(f).get())
        }
    }

    @Test
    fun the_wire_value_is_a_json_boolean() {
        // The boolean scalar's wire form: bare false, not "false" — the
        // one difference from the enum stores' quoted strings. JsonParser
        // parses the literal back (the round-trip lock above rides that).
        val (s, f) = newStore()
        s.put(false)
        assertEquals("""{"version":1,"previewStats":false}""", f.readText(StandardCharsets.UTF_8))
    }

    // ---- coexistence with the other stores ----

    @Test
    fun survives_a_preview_quality_store_write() {
        // A preview-quality save must not drop the stats choice: the
        // stores share project.json and re-render each other's scalars.
        val f = tmp.newFile("project.json")
        PreviewStatsStore(f).put(false)
        val pq = PreviewQualityStore(f)
        pq.put(PreviewQuality.HIGH)
        assertEquals(false, PreviewStatsStore(f).get())
        assertEquals(PreviewQuality.HIGH, pq.get())
    }

    @Test
    fun survives_a_grade_store_write() {
        // A grade save must not drop the stats choice.
        val f = tmp.newFile("project.json")
        PreviewStatsStore(f).put(false)
        val afterStats = f.readText(StandardCharsets.UTF_8)
        val rewrittenByGradeStore = GradeStore.mergeInto(
            afterStats,
            mapOf("clip-a" to GradeK()),
        )
        f.writeText(rewrittenByGradeStore, StandardCharsets.UTF_8)
        assertEquals(false, PreviewStatsStore(f).get())
    }

    @Test
    fun preserves_other_sections_and_their_order() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1},"sources":["s"]}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.put(false)
        // Deterministic order: version first, existing entries in
        // encounter order, this store's key last.
        assertEquals(
            """{"version":1,"grades":{"a":1},"sources":["s"],"previewStats":false}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }

    // ---- corrupt / hostile files ----

    @Test
    fun malformed_file_reads_default_on() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"previewStats":""", StandardCharsets.UTF_8)
        assertEquals(true, s.get())
    }

    @Test
    fun null_value_reads_default_on() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"previewStats":null}""", StandardCharsets.UTF_8)
        assertEquals(true, s.get())
    }

    @Test
    fun non_boolean_value_reads_default_on() {
        // A string "false" (a hand-edited or future writer's shape) must
        // NEVER silently disable the readback — only a real boolean
        // carries the off choice.
        val (s, f) = newStore()
        f.writeText("""{"version":1,"previewStats":"false"}""", StandardCharsets.UTF_8)
        assertEquals(true, s.get())
        f.writeText("""{"version":1,"previewStats":42}""", StandardCharsets.UTF_8)
        assertEquals(true, s.get())
    }

    // ---- mergeInto purity/determinism ----

    @Test
    fun merge_into_is_deterministic() {
        val existing = """{"version":1,"grades":{"a":1}}"""
        val a = PreviewStatsStore.mergeInto(existing, false)
        val b = PreviewStatsStore.mergeInto(existing, false)
        assertEquals(a, b)
    }

    @Test
    fun merge_into_null_existing_renders_fresh_file() {
        assertEquals(
            """{"version":1,"previewStats":false}""",
            PreviewStatsStore.mergeInto(null, false),
        )
        assertEquals(
            """{"version":1}""",
            PreviewStatsStore.mergeInto(null, true),
        )
    }

    @Test
    fun merge_into_replaces_a_previous_value() {
        val existing = """{"version":1,"previewStats":false}"""
        assertEquals(
            """{"version":1}""",
            PreviewStatsStore.mergeInto(existing, true),
        )
    }

    @Test
    fun fresh_file_minimal_shape() {
        val (s, f) = newStore()
        s.put(false)
        assertEquals(
            """{"version":1,"previewStats":false}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }

    // ---- the toggle seam (persist + apply, the recording fake) ----

    @Test
    fun change_persists_then_applies_in_order() {
        // The row's change path: the store write happens BEFORE the
        // engine call — a crash between them leaves the file saying
        // what the row said, and the app-start apply re-derives the
        // native state from the file.
        val (s, f) = newStore()
        val apply = RecordingApply()
        val toggle = PreviewStatsToggle(s, apply)
        toggle.change(false)
        assertEquals(listOf(false), apply.calls)
        assertEquals(false, s.get())
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("previewStats"))
    }

    @Test
    fun change_applies_every_flip_not_just_the_off_one() {
        // Both directions reach the engine: off AND back on (the A/B is
        // a comparison, the on leg is the baseline).
        val (s, _) = newStore()
        val apply = RecordingApply()
        val toggle = PreviewStatsToggle(s, apply)
        toggle.change(false)
        toggle.change(true)
        assertEquals(listOf(false, true), apply.calls)
    }

    @Test
    fun apply_persisted_sends_the_file_value_once() {
        // The app-start path: one call, whatever the file says — the
        // restart lock (a restarted process re-applies the stored
        // choice; a fresh default file applies the default true).
        val (s, f) = newStore()
        val apply = RecordingApply()
        val toggle = PreviewStatsToggle(s, apply)
        toggle.applyPersisted()
        assertEquals(listOf(true), apply.calls)
        f.writeText("""{"version":1,"previewStats":false}""", StandardCharsets.UTF_8)
        toggle.applyPersisted()
        assertEquals(listOf(true, false), apply.calls)
    }

    @Test
    fun current_reads_the_store_not_a_cache() {
        // The row's checked state comes from the file every time, so an
        // external write (a future settings-sync path) is visible.
        val (s, f) = newStore()
        val toggle = PreviewStatsToggle(s, RecordingApply())
        assertEquals(true, toggle.current())
        f.writeText("""{"version":1,"previewStats":false}""", StandardCharsets.UTF_8)
        assertEquals(false, toggle.current())
    }
}
