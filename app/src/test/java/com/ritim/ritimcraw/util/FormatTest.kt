package com.ritim.ritimcraw.util

import org.junit.Assert.assertEquals
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
