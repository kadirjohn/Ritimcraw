package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AutoHidePlayerBarStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): Pair<AutoHidePlayerBarStore, File> {
        val f = File(tmp.newFolder(), "project.json")
        return AutoHidePlayerBarStore(f) to f
    }

    @Test
    fun default_is_off_when_file_missing() {
        assertFalse(newStore().first.get())
    }

    @Test
    fun on_round_trips_and_default_is_omitted() {
        val (store, f) = newStore()
        store.put(true)
        assertTrue(store.get())
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("\"autoHidePlayerBar\":true"))
        store.put(false)
        assertFalse(store.get())
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("autoHidePlayerBar"))
    }

    @Test
    fun coexists_with_theme_key() {
        val (store, f) = newStore()
        ThemeStore(f).put(ThemeMode.DARK)
        store.put(true)
        assertEquals(ThemeMode.DARK, ThemeStore(f).get())
        assertTrue(store.get())
    }

    @Test
    fun corrupt_file_falls_back_to_default() {
        val (store, f) = newStore()
        f.writeText("{not json", StandardCharsets.UTF_8)
        assertFalse(store.get())
    }
}
