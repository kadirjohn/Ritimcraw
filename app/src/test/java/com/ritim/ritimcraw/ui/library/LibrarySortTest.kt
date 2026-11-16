package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.LibrarySortKey
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * LibrarySort tests (library toolbar redesign, 2026-11-16): each key in
 * each direction, the always-ascending name tiebreak, and the default
 * (null key) keeping today's import order. The record helper is the
 * SourceRepositoryTest shape with the sort fields as named args.
 */
class LibrarySortTest {

    private fun source(
        id: String,
        displayName: String = "",
        width: Int = 0,
        height: Int = 0,
        durationUs: Long = 0L,
        access: Access = Access.OK,
    ) = MediaSource(
        id = id,
        uri = "content://clip/$id",
        fingerprint = "fp-$id",
        kind = SourceKind.MCRAW,
        access = access,
        displayName = displayName,
        width = width,
        height = height,
        durationUs = durationUs,
    )

    private fun names(list: List<MediaSource>): List<String> =
        list.map { it.displayOrId }

    // ---- the default: today's order ----

    @Test
    fun null_key_returns_today_order_unchanged() {
        // Import order (the "sources" array order) is the pre-toolbar
        // behavior: no copy, no re-derive — the SAME list instance.
        val imported = listOf(source("c"), source("a"), source("b"))
        val result = LibrarySort.apply(imported, null, ascending = true)
        assertEquals(listOf("c", "a", "b"), names(result))
        assertEquals(imported, result)
    }

    @Test
    fun null_key_ignores_the_direction() {
        val imported = listOf(source("c"), source("a"), source("b"))
        assertEquals(
            names(LibrarySort.apply(imported, null, ascending = false)),
            names(LibrarySort.apply(imported, null, ascending = true)),
        )
    }

    // ---- NAME ----

    @Test
    fun name_ascending_sorts_case_insensitively() {
        val list = listOf(
            source("1", displayName = "beach.mp4"),
            source("2", displayName = "Apple.dng"),
            source("3", displayName = "city.mp4"),
        )
        assertEquals(
            listOf("Apple.dng", "beach.mp4", "city.mp4"),
            names(LibrarySort.apply(list, LibrarySortKey.NAME, ascending = true)),
        )
    }

    @Test
    fun name_falls_back_to_id_when_blank() {
        val list = listOf(
            source("zz", displayName = ""),
            source("2", displayName = "a.mp4"),
        )
        assertEquals(
            listOf("a.mp4", "zz"),
            names(LibrarySort.apply(list, LibrarySortKey.NAME, ascending = true)),
        )
    }

    @Test
    fun name_descending_reverses_the_field_only() {
        val list = listOf(
            source("1", displayName = "a.mp4"),
            source("2", displayName = "b.mp4"),
            source("3", displayName = "c.mp4"),
        )
        assertEquals(
            listOf("c.mp4", "b.mp4", "a.mp4"),
            names(LibrarySort.apply(list, LibrarySortKey.NAME, ascending = false)),
        )
    }

    // ---- DURATION ----

    @Test
    fun duration_ascending_orders_by_recorded_span() {
        val list = listOf(
            source("long", durationUs = 12_000_000),
            source("short", durationUs = 1_000_000),
            source("mid", durationUs = 6_000_000),
        )
        assertEquals(
            listOf("short", "mid", "long"),
            names(LibrarySort.apply(list, LibrarySortKey.DURATION, ascending = true)),
        )
    }

    @Test
    fun duration_descending_reverses_the_field() {
        val list = listOf(
            source("short", durationUs = 1_000_000),
            source("long", durationUs = 12_000_000),
        )
        assertEquals(
            listOf("long", "short"),
            names(LibrarySort.apply(list, LibrarySortKey.DURATION, ascending = false)),
        )
    }

    @Test
    fun duration_ties_break_by_name_ascending() {
        val list = listOf(
            source("3", displayName = "c.mp4", durationUs = 5_000_000),
            source("1", displayName = "a.mp4", durationUs = 5_000_000),
            source("2", displayName = "b.mp4", durationUs = 5_000_000),
        )
        assertEquals(
            listOf("a.mp4", "b.mp4", "c.mp4"),
            names(LibrarySort.apply(list, LibrarySortKey.DURATION, ascending = true)),
        )
    }

    @Test
    fun duration_ties_still_name_ascending_when_descending() {
        // The spec's rule verbatim: ties break by Name ascending in BOTH
        // directions — descending flips the duration, never the tiebreak.
        val list = listOf(
            source("3", displayName = "c.mp4", durationUs = 5_000_000),
            source("1", displayName = "a.mp4", durationUs = 5_000_000),
            source("2", displayName = "b.mp4", durationUs = 5_000_000),
            source("4", displayName = "z.mp4", durationUs = 9_000_000),
        )
        assertEquals(
            listOf("z.mp4", "a.mp4", "b.mp4", "c.mp4"),
            names(LibrarySort.apply(list, LibrarySortKey.DURATION, ascending = false)),
        )
    }

    @Test
    fun unprobed_duration_zero_sorts_first_when_ascending() {
        // 0 is the honest "unknown" value: it orders as 0, never faked.
        val list = listOf(
            source("probed", durationUs = 1_000_000),
            source("unprobed", durationUs = 0),
        )
        assertEquals(
            listOf("unprobed", "probed"),
            names(LibrarySort.apply(list, LibrarySortKey.DURATION, ascending = true)),
        )
    }

    // ---- RESOLUTION ----

    @Test
    fun resolution_ascending_orders_by_pixel_count() {
        val list = listOf(
            source("4k", width = 3840, height = 2160),
            source("hd", width = 1920, height = 1080),
            source("8k", width = 7680, height = 4320),
        )
        assertEquals(
            listOf("hd", "4k", "8k"),
            names(LibrarySort.apply(list, LibrarySortKey.RESOLUTION, ascending = true)),
        )
    }

    @Test
    fun resolution_descending_reverses_the_field() {
        val list = listOf(
            source("hd", width = 1920, height = 1080),
            source("4k", width = 3840, height = 2160),
        )
        assertEquals(
            listOf("4k", "hd"),
            names(LibrarySort.apply(list, LibrarySortKey.RESOLUTION, ascending = false)),
        )
    }

    @Test
    fun resolution_ties_break_by_name_ascending() {
        // Same pixel count via different shapes: 4096x2160 == 2048x4320.
        val list = listOf(
            source("wide", width = 4096, height = 2160),
            source("tall", width = 2048, height = 4320),
        )
        assertEquals(
            listOf("tall", "wide"),
            names(LibrarySort.apply(list, LibrarySortKey.RESOLUTION, ascending = true)),
        )
    }

    @Test
    fun unprobed_resolution_zero_sorts_first_when_ascending() {
        val list = listOf(
            source("probed", width = 1920, height = 1080),
            source("unprobed", width = 0, height = 0),
        )
        assertEquals(
            listOf("unprobed", "probed"),
            names(LibrarySort.apply(list, LibrarySortKey.RESOLUTION, ascending = true)),
        )
    }

    // ---- stability ----

    @Test
    fun full_name_ties_keep_import_order() {
        // Same displayOrId: the comparator returns 0 and the stable sort
        // keeps the catalog's own (import) order (asserted on ids — the
        // names are identical, so only the ids can show a swap).
        val list = listOf(
            source("1", displayName = "dup.mp4"),
            source("2", displayName = "dup.mp4"),
        )
        assertEquals(
            listOf("1", "2"),
            LibrarySort.apply(list, LibrarySortKey.NAME, ascending = true).map { it.id },
        )
    }
}
