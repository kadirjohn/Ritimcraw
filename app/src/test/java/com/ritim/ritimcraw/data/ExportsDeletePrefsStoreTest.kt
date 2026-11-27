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
 * The M10 store (owner request 2026-11-27: "user'ın tıkladığı en son
 * seçenek saklanmalı, eğer delete from the disk seçerse diğer silme
 * isteklerinde de bu seçili gelmeli"): the delete-from-disk checkbox's
 * remembered choice — the ScrubFramesStore pattern over a boolean
 * scalar (omit-when-default, corrupt file falls back to the default),
 * plus the DEFAULT FALSE pin (the safe start: a first-time user still
 * meets the M8 unchecked box; only the user's own check turns it on).
 */
class ExportsDeletePrefsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<ExportsDeletePrefsStore, File> {
        val f = File(tmp.newFolder(), "project.json")
        return ExportsDeletePrefsStore(f) to f
    }

    @Test
    fun default_is_off_when_file_missing() {
        val (store, _) = newStore()
        assertFalse(store.get())
    }

    @Test
    fun default_is_off_for_every_degenerate_input() {
        // Absent key, JSON null, a non-boolean value: all FALSE — a bad
        // file can never silently arm a disk delete.
        val (store, f) = newStore()
        f.writeText("""{"version":1}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
        f.writeText("""{"version":1,"exportsDeleteFromDisk":null}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
        f.writeText("""{"version":1,"exportsDeleteFromDisk":"true"}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
        f.writeText("""{"version":1,"exportsDeleteFromDisk":1}""", StandardCharsets.UTF_8)
        assertFalse(store.get())
    }

    @Test
    fun checked_round_trips_and_default_is_omitted() {
        // The M10 memory: check persists TRUE (every later dialog opens
        // checked); uncheck returns the memory to FALSE — and the key
        // leaves the file (a round trip is byte-identical).
        val (store, f) = newStore()
        store.put(true)
        assertTrue(store.get())
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("\"exportsDeleteFromDisk\":true"))
        store.put(false)
        assertFalse(store.get())
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("exportsDeleteFromDisk"))
    }

    @Test
    fun coexists_with_the_other_scalar_keys() {
        // The one-write-gate contract: this store's write preserves the
        // other scalars raw, and their writes preserve this key.
        val (store, f) = newStore()
        ThemeStore(f).put(ThemeMode.DARK)
        SystemMonitorStore(f).put(false)
        ScrubFramesStore(f).put(true)
        store.put(true)
        assertEquals(ThemeMode.DARK, ThemeStore(f).get())
        assertFalse(SystemMonitorStore(f).get())
        assertTrue(ScrubFramesStore(f).get())
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
        val existing = """{"version":2,"grades":{"a":{}},"theme":"DARK","systemMonitor":true,"scrubFrames":true}"""
        val merged = ExportsDeletePrefsStore.mergeInto(existing, true)
        assertEquals(
            """{"version":2,"grades":{"a":{}},"theme":"DARK","systemMonitor":true,"scrubFrames":true,"exportsDeleteFromDisk":true}""",
            merged,
        )
        // The default REMOVES the key rather than writing false.
        val onFile = """{"version":2,"exportsDeleteFromDisk":true}"""
        assertEquals(
            """{"version":2}""",
            ExportsDeletePrefsStore.mergeInto(onFile, false),
        )
    }

    @Test
    fun a_round_trip_of_an_old_file_leaves_it_byte_identical() {
        // Omit-when-default both ways: a file this store never touched
        // passes through put(false) unchanged — an old install's
        // project.json never gains a key it does not need.
        val (store, f) = newStore()
        val old = """{"version":1,"systemMonitor":false,"previewStats":false}"""
        f.writeText(old, StandardCharsets.UTF_8)
        store.put(false)
        assertEquals(old, f.readText(StandardCharsets.UTF_8))
    }
}
