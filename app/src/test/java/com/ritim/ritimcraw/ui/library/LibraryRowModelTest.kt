package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Library row model tests (owner request 2026-11-11 section 1): the
 * supporting line's parts — recorded resolution and duration — with the
 * hide rules for unknown values (0x0 hides the resolution, 0 duration
 * hides the duration; neither is ever faked).
 */
class LibraryRowModelTest {

    private fun source(
        width: Int = 0,
        height: Int = 0,
        durationUs: Long = 0L,
    ) = MediaSource(
        id = "abc123",
        uri = "content://clip/1",
        fingerprint = "f",
        kind = SourceKind.MCRAW,
        access = Access.OK,
        width = width,
        height = height,
        durationUs = durationUs,
    )

    // ---- durationText ----

    @Test
    fun duration_text_formats_the_recorded_span() {
        assertEquals("0:12", LibraryRowModel.durationText(source(durationUs = 12_000_000L)))
    }

    @Test
    fun duration_text_formats_past_an_hour() {
        assertEquals("1:02:03", LibraryRowModel.durationText(source(durationUs = 3_723_000_000L)))
    }

    @Test
    fun duration_text_hides_when_unknown_or_zero() {
        // Unprobed record: the part hides, never a fake "0:00".
        assertNull(LibraryRowModel.durationText(source(durationUs = 0L)))
    }

    // ---- supportingLine ----

    @Test
    fun supporting_line_joins_resolution_and_duration() {
        val s = source(width = 3840, height = 2160, durationUs = 12_000_000L)
        assertEquals("3840×2160 · 0:12", LibraryRowModel.supportingLine(s, LibraryRowModel.durationText(s)))
    }

    @Test
    fun open_gate_resolution_shows_as_recorded() {
        // The owner's open-gate case (section 1): 4000x3000 shows as
        // recorded, exactly what the session open probed.
        val s = source(width = 4000, height = 3000, durationUs = 5_500_000L)
        assertEquals("4000×3000 · 0:05", LibraryRowModel.supportingLine(s, LibraryRowModel.durationText(s)))
    }

    @Test
    fun resolution_hides_for_zero_by_zero() {
        // A 0x0 record never fakes a resolution: the duration alone shows.
        val s = source(width = 0, height = 0, durationUs = 12_000_000L)
        assertEquals("0:12", LibraryRowModel.supportingLine(s, LibraryRowModel.durationText(s)))
    }

    @Test
    fun duration_hides_when_unknown() {
        val s = source(width = 3840, height = 2160, durationUs = 0L)
        assertEquals("3840×2160", LibraryRowModel.supportingLine(s, LibraryRowModel.durationText(s)))
    }

    @Test
    fun supporting_line_null_when_nothing_known() {
        // Fully unprobed: null — the row falls to the kind line.
        val s = source()
        assertNull(LibraryRowModel.supportingLine(s, LibraryRowModel.durationText(s)))
    }

    @Test
    fun half_width_record_hides_the_resolution_part() {
        // Only one dimension probed: the part hides entirely (never
        // "3840×0").
        val s = source(width = 3840, height = 0, durationUs = 12_000_000L)
        assertEquals("0:12", LibraryRowModel.supportingLine(s, LibraryRowModel.durationText(s)))
    }
}
