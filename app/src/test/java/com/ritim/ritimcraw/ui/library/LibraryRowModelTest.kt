package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.ui.components.MOTIONCAM_BLUE
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
        access: Access = Access.OK,
        kind: SourceKind = SourceKind.MCRAW,
        displayName: String = "",
    ) = MediaSource(
        id = "abc123",
        uri = "content://clip/1",
        fingerprint = "f",
        kind = kind,
        access = access,
        width = width,
        height = height,
        durationUs = durationUs,
        displayName = displayName,
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

    // ---- rowStatus (spec IN-12/IN-16: degraded rows render distinctly) ----

    @Test
    fun healthy_row_has_no_degraded_status() {
        assertNull(LibraryRowModel.rowStatus(source(access = Access.OK)))
    }

    @Test
    fun lost_row_maps_to_the_lost_status() {
        // A revoked permission keeps its own line + Grant access action.
        assertEquals(LibraryRowModel.RowStatus.LOST, LibraryRowModel.rowStatus(source(access = Access.LOST)))
    }

    @Test
    fun gone_row_maps_to_the_gone_status_distinctly() {
        // A deleted/moved document is NOT a lost permission: different
        // line (File missing), different action (Re-link).
        assertEquals(LibraryRowModel.RowStatus.GONE, LibraryRowModel.rowStatus(source(access = Access.GONE)))
    }

    // ---- displayWrapName (toolbar redesign O1: names wrap at separators) ----

    /** The zero-width space the wrap inserts, spelled for test literals. */
    private val zwsp = "﻿"

    @Test
    fun wrap_name_breaks_after_underscore_and_before_dot() {
        // Spec verbatim: U+200B AFTER '_' and BEFORE '.' — so the second
        // line can start at "1234" or at ".MP4", never inside "MP4".
        assertEquals("IMG_${zwsp}1234${zwsp}.MP4", LibraryRowModel.displayWrapName("IMG_1234.MP4"))
    }

    @Test
    fun wrap_name_only_underscore() {
        assertEquals("beach_${zwsp}sunset", LibraryRowModel.displayWrapName("beach_sunset"))
    }

    @Test
    fun wrap_name_only_dot() {
        assertEquals("clip${zwsp}.mp4", LibraryRowModel.displayWrapName("clip.mp4"))
    }

    @Test
    fun wrap_name_without_separators_returns_unchanged() {
        // The id fallback (no separators): the SAME string, no copies.
        assertEquals("abc123", LibraryRowModel.displayWrapName("abc123"))
    }

    @Test
    fun wrap_name_never_changes_the_visible_characters() {
        // Display only: stripping the zero-width spaces restores the
        // original name exactly — the transform adds nothing visible.
        val original = "IMG_1234.MP4"
        assertEquals(original, LibraryRowModel.displayWrapName(original).replace(zwsp, ""))
    }

    @Test
    fun wrap_name_empty_and_short_names_unchanged() {
        assertEquals("", LibraryRowModel.displayWrapName(""))
        assertEquals("a", LibraryRowModel.displayWrapName("a"))
        assertEquals("ab", LibraryRowModel.displayWrapName("ab"))
    }

    @Test
    fun wrap_name_bare_separator_gains_only_invisible_breaks() {
        // Degenerate names: the break lands before/after the lone
        // separator, but nothing visible changes.
        assertEquals("${zwsp}.", LibraryRowModel.displayWrapName("."))
        assertEquals("_${zwsp}", LibraryRowModel.displayWrapName("_"))
    }

    @Test
    fun wrap_name_consecutive_separators_each_get_their_break() {
        // "clip..MP4" and "a__b": every separator is a wrap opportunity.
        assertEquals("clip${zwsp}.${zwsp}.MP4", LibraryRowModel.displayWrapName("clip..MP4"))
        assertEquals("a_${zwsp}_${zwsp}b", LibraryRowModel.displayWrapName("a__b"))
    }

    // ---- withoutExtension / listDisplayName (owner request 2026-11-26:
    // the list row shows the name WITHOUT the extension) ----

    @Test
    fun without_extension_strips_only_the_last_extension() {
        assertEquals("sunset", LibraryRowModel.withoutExtension("sunset.mcraw"))
        // Double extensions: only the LAST one goes.
        assertEquals("clip.x", LibraryRowModel.withoutExtension("clip.x.mcraw"))
        assertEquals("clip", LibraryRowModel.withoutExtension("clip.mp4"))
    }

    @Test
    fun without_extension_keeps_names_with_no_real_extension() {
        // No dot, a BARE dot, a TRAILING dot: the name carries no
        // extension — unchanged ("clip." keeps its dot: its extension
        // is empty, the name part is "clip.").
        assertEquals("clip", LibraryRowModel.withoutExtension("clip"))
        assertEquals(".", LibraryRowModel.withoutExtension("."))
        assertEquals("clip.", LibraryRowModel.withoutExtension("clip."))
        // ".mcraw" as the WHOLE name is all extension: stripping yields
        // the empty string (the caller falls back to the full name).
        assertEquals("", LibraryRowModel.withoutExtension(".mcraw"))
    }

    @Test
    fun without_extension_is_code_point_safe() {
        // A surrogate pair right before the dot: the cut is a char index
        // AFTER the pair, so the emoji survives intact.
        assertEquals("🎥", LibraryRowModel.withoutExtension("🎥.mcraw"))
        assertEquals("a🎥b", LibraryRowModel.withoutExtension("a🎥b.MP4"))
    }

    @Test
    fun list_display_name_strips_the_extension_for_display_only() {
        // The DISPLAY copy loses the extension; the record keeps it (the
        // badge carries the format, the grid keeps the full name).
        assertEquals("sunset", LibraryRowModel.listDisplayName(source(displayName = "sunset.mcraw")))
        assertEquals("IMG_1234", LibraryRowModel.listDisplayName(source(displayName = "IMG_1234.MP4")))
    }

    @Test
    fun list_display_name_falls_back_when_nothing_usable_remains() {
        // A name that was ONLY the extension: the row never shows a
        // blank line — the FULL name keeps the row real (the badge
        // carries the format either way).
        assertEquals(".mcraw", LibraryRowModel.listDisplayName(source(displayName = ".mcraw")))
        // The id fallback also flows through when the record was never
        // named at all (displayName blank -> displayOrId = id).
        assertEquals("abc123", LibraryRowModel.listDisplayName(source(displayName = "")))
    }

    // ---- kindBadge (owner request 2026-11-26: blue MCRAW / gray MP4;
    // 2026-11-27 M2: the blue is MOTIONCAM's own fixed style, not the
    // shared highlighted treatment) ----

    @Test
    fun badge_is_derived_from_the_kind_not_the_name() {
        // A RENAMED mcraw ("sunset", no extension at all) keeps its
        // MCRAW badge — the kind is the record's own classification.
        assertEquals(
            LibraryRowModel.Badge(LibraryRowModel.BadgeStyle.MOTIONCAM, "MCRAW"),
            LibraryRowModel.kindBadge(source(kind = SourceKind.MCRAW, displayName = "sunset")),
        )
        assertEquals(
            LibraryRowModel.Badge(LibraryRowModel.BadgeStyle.NEUTRAL, "MP4"),
            LibraryRowModel.kindBadge(source(kind = SourceKind.ENCODED, displayName = "clip.mp4")),
        )
    }

    @Test
    fun badge_text_is_never_localized() {
        // The badge token is the file's own format name, the app's
        // "never localized" convention for file-derived text.
        val tr = LibraryRowModel.kindBadge(source(kind = SourceKind.MCRAW))
        assertEquals("MCRAW", tr?.text)
    }

    // ---- M2 pins (owner request 2026-11-27: fixed blue MCRAW / gray MP4) ----

    @Test
    fun mcraw_badge_wears_the_motioncam_style_encoded_stays_neutral() {
        // The two kinds' styles stay distinct and pinned: MCRAW takes the
        // MOTIONCAM treatment (the fixed blue the pill renders, constant
        // in every theme), ENCODED keeps NEUTRAL (the gray squircle).
        val mcraw = LibraryRowModel.kindBadge(source(kind = SourceKind.MCRAW))
        assertEquals(LibraryRowModel.BadgeStyle.MOTIONCAM, mcraw?.style)
        assertEquals("MCRAW", mcraw?.text)
        val encoded = LibraryRowModel.kindBadge(source(kind = SourceKind.ENCODED))
        assertEquals(LibraryRowModel.BadgeStyle.NEUTRAL, encoded?.style)
        assertEquals("MP4", encoded?.text)
    }

    @Test
    fun motioncam_blue_is_the_single_fixed_pill_color() {
        // The M2 contract in numbers: ONE constant, pinned here against
        // the Color literal itself (0xFF1E88E5, a lively blue; white 10sp
        // text reads on it, contrast about 4.0) — a morning brand-color
        // tweak changes the constant AND this expectation together, so
        // the test keeps the two in lockstep and catches accidental
        // drift elsewhere. NOT verified: MotionCam's exact brand tone
        // (image-blind agent, the owner compares on device).
        val expected = androidx.compose.ui.graphics.Color(0xFF1E88E5)
        assertEquals(expected.value, MOTIONCAM_BLUE.value)
    }

    @Test
    fun extension_of_matches_the_last_extension_lowercased() {
        assertEquals("mcraw", LibraryRowModel.extensionOf("sunset.mcraw"))
        assertEquals("mp4", LibraryRowModel.extensionOf("clip.X.MP4"))
        assertEquals("", LibraryRowModel.extensionOf("clip"))
        assertEquals("", LibraryRowModel.extensionOf("clip."))
        assertEquals("", LibraryRowModel.extensionOf(""))
    }
}
