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
 * ThemeStore tests (owner request 2026-11-14): the same coverage shape as
 * RenderBackendStoreTest — omit-when-default, round trips, coexistence
 * with the other project.json stores, corrupt files, and deterministic
 * mergeInto. The store is the RenderBackendStore pattern with its own
 * top-level "theme" scalar; plus the [ThemeMode.isDark] resolution the
 * theme construction site calls.
 */
class ThemeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh store + its own project.json in a fresh subfolder. */
    private fun newStore(): Pair<ThemeStore, File> {
        val dir = tmp.newFolder()
        val f = File(dir, "project.json")
        return ThemeStore(f) to f
    }

    // ---- missing / old files ----

    @Test
    fun missing_file_reads_default() {
        assertEquals(ThemeMode.DEFAULT, newStore().first.get())
    }

    @Test
    fun old_file_without_key_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"grades":{"a":1}}""", StandardCharsets.UTF_8)
        assertEquals(ThemeMode.DEFAULT, s.get())
    }

    // ---- omit-when-default ----

    @Test
    fun writing_default_on_fresh_file_writes_minimal_shape() {
        val (s, f) = newStore()
        s.put(ThemeMode.DEFAULT)
        assertEquals("""{"version":1}""", f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun writing_default_byte_identical_when_already_default() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1}}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.put(ThemeMode.DEFAULT)
        assertEquals(before, f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun setting_then_default_removes_the_key() {
        val (s, f) = newStore()
        s.put(ThemeMode.DARK)
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("theme"))
        s.put(ThemeMode.SYSTEM)
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("theme"))
        assertEquals(ThemeMode.DEFAULT, s.get())
    }

    // ---- round trips ----

    @Test
    fun every_mode_round_trips_through_the_file() {
        ThemeMode.entries.forEach { mode ->
            val (s, f) = newStore()
            s.put(mode)
            assertEquals(mode, ThemeStore(f).get())
        }
    }

    // ---- coexistence with the other stores ----

    @Test
    fun survives_a_preview_quality_store_write() {
        // A preview-quality save must not drop the theme choice: the
        // stores share project.json and re-render each other's scalars.
        val f = tmp.newFile("project.json")
        ThemeStore(f).put(ThemeMode.DARK)
        val pq = PreviewQualityStore(f)
        pq.put(PreviewQuality.HIGH)
        assertEquals(ThemeMode.DARK, ThemeStore(f).get())
        assertEquals(PreviewQuality.HIGH, pq.get())
    }

    @Test
    fun survives_a_render_backend_store_write() {
        // A backend save must not drop the theme choice either.
        val f = tmp.newFile("project.json")
        ThemeStore(f).put(ThemeMode.LIGHT)
        RenderBackendStore(f).put(com.ritim.ritimcraw.engine.RenderBackend.AUTO)
        assertEquals(ThemeMode.LIGHT, ThemeStore(f).get())
    }

    @Test
    fun preserves_other_sections_and_their_order() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1},"sources":["s"]}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.put(ThemeMode.DARK)
        // Deterministic order: version first, existing entries in
        // encounter order, this store's key last.
        assertEquals(
            """{"version":1,"grades":{"a":1},"sources":["s"],"theme":"DARK"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }

    // ---- corrupt / hostile files ----

    @Test
    fun malformed_file_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"theme":""", StandardCharsets.UTF_8)
        assertEquals(ThemeMode.DEFAULT, s.get())
    }

    @Test
    fun null_value_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"theme":null}""", StandardCharsets.UTF_8)
        assertEquals(ThemeMode.DEFAULT, s.get())
    }

    @Test
    fun non_string_value_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"theme":42}""", StandardCharsets.UTF_8)
        assertEquals(ThemeMode.DEFAULT, s.get())
    }

    @Test
    fun unknown_value_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"theme":"AMOLED"}""", StandardCharsets.UTF_8)
        assertEquals(ThemeMode.DEFAULT, s.get())
    }

    // ---- mergeInto purity/determinism ----

    @Test
    fun merge_into_is_deterministic() {
        val existing = """{"version":1,"grades":{"a":1}}"""
        val a = ThemeStore.mergeInto(existing, ThemeMode.DARK)
        val b = ThemeStore.mergeInto(existing, ThemeMode.DARK)
        assertEquals(a, b)
    }

    @Test
    fun merge_into_null_existing_renders_fresh_file() {
        assertEquals(
            """{"version":1,"theme":"DARK"}""",
            ThemeStore.mergeInto(null, ThemeMode.DARK),
        )
        assertEquals(
            """{"version":1}""",
            ThemeStore.mergeInto(null, ThemeMode.DEFAULT),
        )
    }

    @Test
    fun merge_into_replaces_a_previous_value() {
        val existing = """{"version":1,"theme":"LIGHT"}"""
        assertEquals(
            """{"version":1,"theme":"DARK"}""",
            ThemeStore.mergeInto(existing, ThemeMode.DARK),
        )
    }

    @Test
    fun fresh_file_minimal_shape() {
        val (s, f) = newStore()
        s.put(ThemeMode.LIGHT)
        assertEquals(
            """{"version":1,"theme":"LIGHT"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }

    // ---- the dark-theme resolution the construction site calls ----

    @Test
    fun system_mode_follows_the_device_flag() {
        // SYSTEM is the pre-setting behavior: whatever the system says.
        assertTrue(ThemeMode.SYSTEM.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemInDarkTheme = false))
    }

    @Test
    fun light_and_dark_pin_the_theme() {
        // The override: fixed whatever the system says.
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = true))
        assertFalse(ThemeMode.LIGHT.isDark(systemInDarkTheme = false))
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = true))
        assertTrue(ThemeMode.DARK.isDark(systemInDarkTheme = false))
    }

    // ---- the wire enum ----

    @Test
    fun from_wire_maps_the_names_and_defaults_the_rest() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire("SYSTEM"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromWire("LIGHT"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromWire("DARK"))
        // Absent, null, and unknown all resolve to the default.
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromWire(null))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromWire("system"))
        assertEquals(ThemeMode.DEFAULT, ThemeMode.fromWire("VAPORWAVE"))
    }
}
