package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * LibraryPrefsStore tests (library toolbar redesign, 2026-11-16): the
 * ThemeStoreTest coverage shape for the toolbar's THREE scalars —
 * omit-when-default, round trips, coexistence with the other project.json
 * stores, corrupt files, and deterministic mergeInto — plus the sort/view
 * pair's own rules (null key = today's order, boolean shape, list/grid
 * wire values).
 */
class LibraryPrefsStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A fresh store + its own project.json in a fresh subfolder. */
    private fun newStore(): Pair<LibraryPrefsStore, File> {
        val dir = tmp.newFolder()
        val f = File(dir, "project.json")
        return LibraryPrefsStore(f) to f
    }

    // ---- missing / old files ----

    @Test
    fun missing_file_reads_defaults() {
        val (s, _) = newStore()
        assertNull(s.sortKey())
        assertTrue(s.ascending())
        assertEquals(LibraryViewMode.DEFAULT, s.viewMode())
    }

    @Test
    fun old_file_without_keys_reads_defaults() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"grades":{"a":1}}""", StandardCharsets.UTF_8)
        assertNull(s.sortKey())
        assertTrue(s.ascending())
        assertEquals(LibraryViewMode.DEFAULT, s.viewMode())
    }

    // ---- omit-when-default ----

    @Test
    fun writing_defaults_on_fresh_file_writes_minimal_shape() {
        val (s, f) = newStore()
        s.putSortKey(null)
        s.putAscending(true)
        s.putViewMode(LibraryViewMode.LIST)
        assertEquals("""{"version":1}""", f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun writing_defaults_byte_identical_when_already_default() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1}}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.putSortKey(null)
        s.putAscending(true)
        s.putViewMode(LibraryViewMode.LIST)
        assertEquals(before, f.readText(StandardCharsets.UTF_8))
    }

    @Test
    fun setting_then_default_removes_the_key() {
        val (s, f) = newStore()
        s.putSortKey(LibrarySortKey.DURATION)
        assertTrue(f.readText(StandardCharsets.UTF_8).contains("library_sort_key"))
        s.putSortKey(null)
        assertFalse(f.readText(StandardCharsets.UTF_8).contains("library_sort_key"))
        assertNull(s.sortKey())
    }

    // ---- round trips ----

    @Test
    fun every_key_round_trips_through_the_file() {
        LibrarySortKey.entries.forEach { key ->
            val (s, f) = newStore()
            s.putSortKey(key)
            assertEquals(key, LibraryPrefsStore(f).sortKey())
        }
    }

    @Test
    fun descending_round_trips_and_ascends_back() {
        val (s, f) = newStore()
        s.putAscending(false)
        assertFalse(LibraryPrefsStore(f).ascending())
        s.putAscending(true)
        assertTrue(LibraryPrefsStore(f).ascending())
    }

    @Test
    fun grid_round_trips_and_lists_back() {
        val (s, f) = newStore()
        s.putViewMode(LibraryViewMode.GRID)
        assertEquals(LibraryViewMode.GRID, LibraryPrefsStore(f).viewMode())
        s.putViewMode(LibraryViewMode.LIST)
        assertEquals(LibraryViewMode.LIST, LibraryPrefsStore(f).viewMode())
    }

    // ---- the three keys coexist with each other ----

    @Test
    fun one_put_never_drops_its_sibling_keys() {
        val (s, f) = newStore()
        s.putSortKey(LibrarySortKey.NAME)
        s.putAscending(false)
        s.putViewMode(LibraryViewMode.GRID)
        // The third put re-renders the whole file but must keep the first two.
        s.putSortKey(LibrarySortKey.RESOLUTION)
        assertEquals(LibrarySortKey.RESOLUTION, s.sortKey())
        assertFalse(s.ascending())
        assertEquals(LibraryViewMode.GRID, s.viewMode())
    }

    // ---- coexistence with the other stores ----

    @Test
    fun survives_a_theme_store_write() {
        // A theme save must not drop the toolbar's choices: the stores
        // share project.json and re-render each other's scalars.
        val f = tmp.newFile("project.json")
        LibraryPrefsStore(f).putSortKey(LibrarySortKey.DURATION)
        ThemeStore(f).put(ThemeMode.DARK)
        assertEquals(LibrarySortKey.DURATION, LibraryPrefsStore(f).sortKey())
        assertEquals(ThemeMode.DARK, ThemeStore(f).get())
    }

    @Test
    fun survives_a_preview_quality_store_write() {
        val f = tmp.newFile("project.json")
        LibraryPrefsStore(f).putViewMode(LibraryViewMode.GRID)
        PreviewQualityStore(f).put(com.ritim.ritimcraw.engine.PreviewQuality.HIGH)
        assertEquals(LibraryViewMode.GRID, LibraryPrefsStore(f).viewMode())
    }

    // ---- corrupt / hostile files ----

    @Test
    fun malformed_file_reads_defaults() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"library_sort_key":""", StandardCharsets.UTF_8)
        assertNull(s.sortKey())
        assertTrue(s.ascending())
        assertEquals(LibraryViewMode.DEFAULT, s.viewMode())
    }

    @Test
    fun unknown_values_read_defaults() {
        val (s, f) = newStore()
        f.writeText(
            """{"version":1,"library_sort_key":"DATE","library_view_mode":"carousel"}""",
            StandardCharsets.UTF_8,
        )
        assertNull(s.sortKey())
        assertEquals(LibraryViewMode.DEFAULT, s.viewMode())
    }

    @Test
    fun non_boolean_ascending_reads_default() {
        val (s, f) = newStore()
        f.writeText("""{"version":1,"library_sort_ascending":"false"}""", StandardCharsets.UTF_8)
        assertTrue(s.ascending())
    }

    // ---- mergeInto purity/determinism ----

    @Test
    fun merge_into_is_deterministic() {
        val existing = """{"version":1,"grades":{"a":1}}"""
        val a = LibraryPrefsStore.mergeInto(existing, LibrarySortKey.NAME, false, LibraryViewMode.GRID)
        val b = LibraryPrefsStore.mergeInto(existing, LibrarySortKey.NAME, false, LibraryViewMode.GRID)
        assertEquals(a, b)
    }

    @Test
    fun merge_into_null_existing_renders_fresh_file() {
        assertEquals(
            """{"version":1,"library_sort_key":"NAME","library_sort_ascending":false,"library_view_mode":"grid"}""",
            LibraryPrefsStore.mergeInto(null, LibrarySortKey.NAME, false, LibraryViewMode.GRID),
        )
        assertEquals(
            """{"version":1}""",
            LibraryPrefsStore.mergeInto(null, null, true, LibraryViewMode.DEFAULT),
        )
    }

    @Test
    fun merge_into_replaces_a_previous_value() {
        val existing = """{"version":1,"library_sort_key":"NAME","library_sort_ascending":false}"""
        assertEquals(
            """{"version":1,"library_sort_key":"DURATION","library_sort_ascending":false}""",
            LibraryPrefsStore.mergeInto(existing, LibrarySortKey.DURATION, false, LibraryViewMode.DEFAULT),
        )
    }

    @Test
    fun preserves_other_sections_and_their_order() {
        val (s, f) = newStore()
        val before = """{"version":1,"grades":{"a":1},"sources":["s"]}"""
        f.writeText(before, StandardCharsets.UTF_8)
        s.putSortKey(LibrarySortKey.DURATION)
        // Deterministic order: version first, existing entries in
        // encounter order, this store's keys last.
        assertEquals(
            """{"version":1,"grades":{"a":1},"sources":["s"],"library_sort_key":"DURATION"}""",
            f.readText(StandardCharsets.UTF_8),
        )
    }

    // ---- the wire enums ----

    @Test
    fun sort_key_from_wire_maps_the_names_and_defaults_the_rest() {
        assertEquals(LibrarySortKey.NAME, LibrarySortKey.fromWire("NAME"))
        assertEquals(LibrarySortKey.DURATION, LibrarySortKey.fromWire("DURATION"))
        assertEquals(LibrarySortKey.RESOLUTION, LibrarySortKey.fromWire("RESOLUTION"))
        // Absent, null, and unknown all resolve to the default (null):
        // today's order is not a menu option, only the never-touched state.
        assertNull(LibrarySortKey.fromWire(null))
        assertNull(LibrarySortKey.fromWire("DATE"))
        assertNull(LibrarySortKey.fromWire("FILE_SIZE"))
    }

    @Test
    fun view_mode_from_wire_maps_the_spec_values_and_defaults_the_rest() {
        assertEquals(LibraryViewMode.LIST, LibraryViewMode.fromWire("list"))
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.fromWire("grid"))
        assertEquals(LibraryViewMode.DEFAULT, LibraryViewMode.fromWire(null))
        assertEquals(LibraryViewMode.DEFAULT, LibraryViewMode.fromWire("GRID"))
        assertEquals(LibraryViewMode.DEFAULT, LibraryViewMode.fromWire("carousel"))
    }

    @Test
    fun view_mode_other_flips_between_the_two() {
        assertEquals(LibraryViewMode.GRID, LibraryViewMode.LIST.other())
        assertEquals(LibraryViewMode.LIST, LibraryViewMode.GRID.other())
    }
}
