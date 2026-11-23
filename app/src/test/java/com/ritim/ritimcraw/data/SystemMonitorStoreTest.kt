package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SystemMonitorStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<SystemMonitorStore, File> {
        val f = File(tmp.newFolder(), "project.json")
        return SystemMonitorStore(f) to f
    }

    @Test
    fun default_is_visible_when_file_missing() {
        val (store, _) = newStore()
        assertTrue(store.get())
    }

    @Test
    fun hidden_round_trips_and_default_is_omitted() {
        val (store, f) = newStore()
        store.put(false)
        assertFalse(store.get())
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("\"systemMonitor\":false"))
        store.put(true)
        assertTrue(store.get())
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("systemMonitor"))
    }

    @Test
    fun coexists_with_theme_key() {
        val (store, f) = newStore()
        ThemeStore(f).put(ThemeMode.DARK)
        store.put(false)
        assertEquals(ThemeMode.DARK, ThemeStore(f).get())
        assertFalse(store.get())
    }

    @Test
    fun corrupt_file_falls_back_to_default() {
        val (store, f) = newStore()
        f.writeText("{not json", StandardCharsets.UTF_8)
        assertTrue(store.get())
    }
}
