package com.ritim.ritimcraw.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Format utility tests (DESIGN.md: tabular timecodes, explicit-sign EV,
 * Kelvin, clip format line). Known values plus the 0/negative edges.
 */
class FormatTest {

    // ---- formatTimecode ----

    @Test
    fun timecode_zero_is_all_zero_fields() {
        assertEquals("00:00:00:00", Format.formatTimecode(0L))
    }

    @Test
    fun timecode_one_second_at_24fps() {
        // 1 s * 24 fps = frame 24 -> wraps to 00:00:01:00.
        assertEquals("00:00:01:00", Format.formatTimecode(1_000_000L, 24))
    }

    @Test
    fun timecode_first_fixture_frame() {
        // ptsUs 258175962047 at 24 fps: 258175962047us = 258175.962047s
        // -> 258175962047 * 24 / 1e6 = 6196223.08 frames -> frame 6196223
        // => 6196223 / 24 = 258176s r 7 -> 00:71:43:07? no: 258176s =
        // 71h 42m 56s. Recomputed exactly below in the test body.
        val ptsUs = 258_175_962_047L
        val totalFrames = ptsUs / 1_000_000L * 24 + ((ptsUs % 1_000_000L) * 24) / 1_000_000L
        val expectedSeconds = totalFrames / 24
        val expectedFrames = (totalFrames % 24).toInt()
        val expected = String.format(
            java.util.Locale.ROOT,
            "%02d:%02d:%02d:%02d",
            (expectedSeconds / 3600).toInt(),
            ((expectedSeconds % 3600) / 60).toInt(),
            (expectedSeconds % 60).toInt(),
            expectedFrames,
        )
        assertEquals(expected, Format.formatTimecode(ptsUs, 24))
    }

    @Test
    fun timecode_rolls_hours_minutes_seconds() {
        // 3661.5 s at 30 fps: 3661.5 * 30 = 109845 frames -> 01:01:01:15.
        assertEquals("01:01:01:15", Format.formatTimecode(3_661_500_000L, 30))
    }

    @Test
    fun timecode_negative_clamps_to_zero() {
        assertEquals("00:00:00:00", Format.formatTimecode(-5_000_000L))
    }

    @Test
    fun timecode_non_positive_fps_falls_back_to_default() {
        // fps 0 would divide by zero; falls back to 24.
        assertEquals("00:00:01:00", Format.formatTimecode(1_000_000L, 0))
    }

    @Test
    fun timecode_fields_are_fixed_width() {
        // 2-digit zero padding keeps tnum columns aligned.
        assertEquals(11, Format.formatTimecode(123_456_789L).length)
        // 1.047 s at 24 fps = 25.128 frames -> frame 25 -> 00:00:01:01.
        assertEquals("00:00:01:01", Format.formatTimecode(1_047_000L, 24))
    }

    // ---- formatDuration ----

    @Test
    fun duration_under_a_minute() {
        assertEquals("0:42", Format.formatDuration(42_000_000L))
    }

    @Test
    fun duration_over_a_minute() {
        assertEquals("10:19", Format.formatDuration(619_000_000L))
    }

    @Test
    fun duration_over_an_hour() {
        assertEquals("1:02:03", Format.formatDuration(3_723_000_000L))
    }

    @Test
    fun duration_zero() {
        assertEquals("0:00", Format.formatDuration(0L))
    }

    @Test
    fun duration_negative_clamps_to_zero() {
        assertEquals("0:00", Format.formatDuration(-1_000L))
    }

    // ---- formatEv ----

    @Test
    fun ev_positive_has_plus_sign() {
        assertEquals("+1.0 EV", Format.formatEv(1.0f))
    }

    @Test
    fun ev_negative_has_minus_sign() {
        assertEquals("-0.3 EV", Format.formatEv(-0.3f))
    }

    @Test
    fun ev_zero_still_signed() {
        assertEquals("+0.0 EV", Format.formatEv(0f))
    }

    @Test
    fun ev_one_decimal_place() {
        assertEquals("+2.5 EV", Format.formatEv(2.51f))
    }

    // ---- formatSigned ----

    @Test
    fun signed_positive_has_plus() {
        assertEquals("+12", Format.formatSigned(12))
    }

    @Test
    fun signed_negative_has_minus() {
        assertEquals("-7", Format.formatSigned(-7))
    }

    @Test
    fun signed_zero_has_plus() {
        assertEquals("+0", Format.formatSigned(0))
    }

    // ---- formatKelvin ----

    @Test
    fun kelvin_default_white_balance() {
        assertEquals("5600 K", Format.formatKelvin(5600))
    }

    @Test
    fun kelvin_zero() {
        assertEquals("0 K", Format.formatKelvin(0))
    }

    @Test
    fun kelvin_negative_keeps_sign() {
        assertEquals("-200 K", Format.formatKelvin(-200))
    }

    // ---- formatClipLine ----

    @Test
    fun clip_line_full_fixture() {
        assertEquals(
            "3840×2160 • 310 frames • RGGB",
            Format.formatClipLine(3840, 2160, 310, "RGGB"),
        )
    }

    @Test
    fun clip_line_omits_zero_dimensions() {
        assertEquals("310 frames • RGGB", Format.formatClipLine(0, 0, 310, "RGGB"))
    }

    @Test
    fun clip_line_omits_zero_frames() {
        assertEquals("3840×2160 • RGGB", Format.formatClipLine(3840, 2160, 0, "RGGB"))
    }

    @Test
    fun clip_line_omits_blank_cfa() {
        assertEquals("3840×2160 • 310 frames", Format.formatClipLine(3840, 2160, 310, ""))
    }

    @Test
    fun clip_line_empty_when_nothing_known() {
        assertEquals("", Format.formatClipLine(0, 0, 0, ""))
    }

    @Test
    fun clip_line_negative_values_omitted() {
        assertEquals("RGGB", Format.formatClipLine(-1, -1, -5, "RGGB"))
    }

    // ---- formatTimecodePair ----

    @Test
    fun timecode_pair_joins_with_slash() {
        // 10 s at 24 fps = frame 240 -> 00:00:10:00.
        assertEquals(
            "00:00:00:00 / 00:00:10:00",
            Format.formatTimecodePair(0L, 10_000_000L),
        )
    }

    // ---- widestTimecodePair / timecodeWidthProbes (owner round 3a) ----

    @Test
    fun widest_pair_is_the_all_zeros_shape() {
        assertEquals("00:00:00:00 / 00:00:00:00", Format.widestTimecodePair())
    }

    @Test
    fun width_probes_lead_with_the_widest_pair_then_every_uniform_digit() {
        val probes = Format.timecodeWidthProbes()
        assertEquals(10, probes.size)
        assertEquals(Format.widestTimecodePair(), probes.first())
        (0..9).forEach { digit ->
            val d = '0' + digit
            assertEquals("$d$d:$d$d:$d$d:$d$d / $d$d:$d$d:$d$d:$d$d", probes[digit])
        }
    }

    @Test
    fun every_sampled_pair_fits_the_widest_shape_in_length() {
        // Sampled playback positions on the real 261110_004208 fixture
        // (first/mid/last ptsUs from the rawcli info pass) plus the
        // structural edges: zero, one frame in, past an hour, and the
        // last instant that still renders two-digit hours (99:59:59).
        // Every pair with hours <= 99 is exactly the widest shape's
        // 25 chars, so the pinned min-width box never resizes.
        val widest = Format.widestTimecodePair()
        val samples = listOf(
            0L,
            1_000_000L / 24,                 // one frame in
            258_175_962_047L,                // fixture first pts
            258_181_118_675L,                // fixture mid pts
            258_186_242_047L,                // fixture last pts
            3_723_000_000L,                  // past an hour
            359_999_000_000L,               // 99:59:59:00 at 24 fps
        )
        samples.forEach { pts ->
            // A single timecode is the pair's half; both halves must
            // keep the 11-char shape for the pair to stay at 25.
            assertEquals("single timecode at pts $pts outgrew the shape", 11, Format.formatTimecode(pts).length)
            assertEquals(
                "pair at pts $pts outgrew the widest shape",
                widest.length,
                Format.formatTimecodePair(pts, 258_186_242_047L).length,
            )
        }
    }

    @Test
    fun pair_length_never_changes_across_playback() {
        // The owner-visible property under test: as the current timecode
        // ticks frame by frame, the pair string's length is CONSTANT, so
        // a width pinned to the widest shape is never outgrown
        // mid-playback. Frame-by-frame over three 5 s windows (start,
        // mid, end of the fixture clip — the fast-ticking regime the
        // owner sees) plus every 100th frame across the whole clip
        // (covers every digit combination in the slower fields without
        // six million String.format calls).
        val last = 258_186_242_047L
        val frameUs = 1_000_000L / 24
        val window = 5_000_000L
        val starts = listOf(0L, last / 2, (last - window).coerceAtLeast(0L))
        starts.forEach { start ->
            var t = start
            while (t <= start + window && t <= last) {
                assertEquals(25, Format.formatTimecodePair(t, last).length)
                t += frameUs
            }
        }
        var t = 0L
        while (t <= last) {
            assertEquals(25, Format.formatTimecodePair(t, last).length)
            t += frameUs * 100
        }
    }

    @Test
    fun real_pairs_share_the_probe_skeleton() {
        // The font-independent width bound: every real pair (hours <= 99)
        // is 16 digits + colons + " / " — the same skeleton as the
        // uniform-digit probes — so the widest uniform probe bounds any
        // mixed-digit pair on ANY font (glyph advances add up; 16 copies
        // of the widest digit advance >= any 16 actual digit advances).
        val skeleton = Regex("\\d\\d:\\d\\d:\\d\\d:\\d\\d / \\d\\d:\\d\\d:\\d\\d:\\d\\d")
        Format.timecodeWidthProbes().forEach { probe ->
            assertTrue("probe $probe broke the skeleton", skeleton.matches(probe))
        }
        listOf(
            0L,
            1_000_000L / 24,
            258_181_118_675L,
            258_186_242_047L,
            3_723_000_000L,
            359_999_000_000L,
        ).forEach { pts ->
            val pair = Format.formatTimecodePair(pts, 258_186_242_047L)
            assertTrue("pair $pair broke the skeleton", skeleton.matches(pair))
        }
    }

    @Test
    fun past_99_hours_outgrows_the_widest_shape_documented_assumption() {
        // formatTimecode pads hours to two digits but never clamps them:
        // a 100-hour clip renders three-digit hours and outgrows the
        // pinned width. Documented assumption (KDoc on widestTimecodePair):
        // no phone recording reaches 100 hours; if one ever did, the
        // readout would resize once per hour boundary and hold steady
        // again — the frame field still never moves the box.
        val hundredHours = 100L * 3_600 * 1_000_000
        assertTrue(
            "the >99h clamp assumption changed — update widestTimecodePair",
            Format.formatTimecodePair(hundredHours, hundredHours).length >
                Format.widestTimecodePair().length,
        )
    }

    // ---- formatEtaLeft (owner request 2026-11-11 §1) ----

    @Test
    fun eta_left_is_seconds_only_under_a_minute() {
        assertEquals("0s", Format.formatEtaLeft(0L))
        assertEquals("45s", Format.formatEtaLeft(45L))
        assertEquals("59s", Format.formatEtaLeft(59L))
    }

    @Test
    fun eta_left_is_minutes_and_seconds_under_an_hour() {
        // A zero second unit drops: "1m"/"2m", not "1m 0s"/"2m 0s".
        assertEquals("1m", Format.formatEtaLeft(60L))
        assertEquals("2m 13s", Format.formatEtaLeft(133L))
        assertEquals("2m", Format.formatEtaLeft(120L))
    }

    @Test
    fun eta_left_is_hours_and_minutes_past_an_hour() {
        assertEquals("1h 2m", Format.formatEtaLeft(3_720L))
        // A zero minute unit drops: "2h", not "2h 0m".
        assertEquals("2h", Format.formatEtaLeft(7_200L))
        // Seconds never show at this scale (noise).
        assertEquals("59m 59s", Format.formatEtaLeft(3_599L))
    }

    @Test
    fun eta_left_never_negative() {
        assertEquals("0s", Format.formatEtaLeft(-5L))
    }
}
