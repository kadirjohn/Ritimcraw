package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Scrub frames (F2-C3 wiring): the persisted seek-drag cached-frame
 * toggle — the SystemMonitorStore pattern over a boolean scalar
 * (omit-when-default, corrupt file falls back to the default), plus the
 * DEFAULT FALSE pin (the behavior flag: an untouched project.json keeps
 * the pre-wiring editor).
 */
class ScrubFramesStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<ScrubFramesStore, File> {
        val f = File(tmp.newFolder(), "project.json")
        return ScrubFramesStore(f) to f
    }

    @Test
    fun default_is_off_when_file_missing() {
        val (store, _) = newStore()
        assertFalse(store.get())
    }

    @Test
    fun default_is_off_for_every_degenerate_input() {
        // Absent key, JSON null, a non-boolean value: all FALSE — a bad
        // file can never silently enable the path.
        val (store, f) = newStore()
        f.writeText("""{"version":1}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
        f.writeText("""{"version":1,"scrubFrames":null}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
        f.writeText("""{"version":1,"scrubFrames":"true"}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
        f.writeText("""{"version":1,"scrubFrames":1}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
    }

    @Test
    fun on_round_trips_and_default_is_omitted() {
        val (store, f) = newStore()
        store.put(true)
        assertTrue(store.get())
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("\"scrubFrames\":true"))
        store.put(false)
        assertFalse(store.get())
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("scrubFrames"))
    }

    @Test
    fun coexists_with_the_other_scalar_keys() {
        // The one-write-gate contract: this store's write preserves the
        // other scalars raw, and their writes preserve this key.
        val (store, f) = newStore()
        ThemeStore(f).put(ThemeMode.DARK)
        SystemMonitorStore(f).put(false)
        store.put(true)
        assertEquals(ThemeMode.DARK, ThemeStore(f).get())
        assertFalse(SystemMonitorStore(f).get())
        assertTrue(store.get())
        // And the reverse direction: another store's write keeps ours.
        PreviewQualityStore(f).put(com.ritim.ritimcraw.engine.PreviewQuality.HIGH)
        assertTrue(store.get())
    }

    @Test
    fun corrupt_file_falls_back_to_default() {
        val (store, f) = newStore()
        f.writeText("{not json", StandardCharsets.UTF_8)
        assertFalse(store.get())
    }

    @Test
    fun merge_into_preserves_every_other_entry() {
        // The pure wire format: version first, then the other entries in
        // encounter order, then this key last when set — omitting it at
        // the default. Deterministic.
        val existing = """{"version":2,"grades":{"a":{}},"theme":"DARK","systemMonitor":true}"""
        val merged = ScrubFramesStore.mergeInto(existing, true)
        assertEquals(
            """{"version":2,"grades":{"a":{}},"theme":"DARK","systemMonitor":true,"scrubFrames":true}""",
            merged,
        )
        // The default REMOVES the key rather than writing false.
        val onFile = """{"version":2,"scrubFrames":true}"""
        assertEquals(
            """{"version":2}""",
            ScrubFramesStore.mergeInto(onFile, false),
        )
    }
}
