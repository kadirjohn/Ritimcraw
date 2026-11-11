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

    // ---- etaSeconds (owner request 2026-11-11 §1) ----

    @Test
    fun eta_is_null_before_the_warm_up_gate() {
        // Fewer than MIN_ETA_FRAMES done: a one-frame sample extrapolates
        // wildly (the first frame pays codec setup), so no ETA yet.
        assertEquals(
            null,
            Format.etaSeconds(9, 310, startedAtMs = 0L, nowMs = 5_000L),
        )
    }

    @Test
    fun eta_is_null_when_nothing_measured_yet() {
        // Zero elapsed time: nothing measured, no rate.
        assertEquals(
            null,
            Format.etaSeconds(20, 310, startedAtMs = 1_000L, nowMs = 1_000L),
        )
    }

    @Test
    fun eta_is_null_at_or_past_the_total() {
        // The frames-done == frames-total case is the FINALIZING state
        // (the muxer is running), not an ETA of 0 — and past-total (a late
        // report) returns null rather than a negative ETA.
        assertEquals(
            null,
            Format.etaSeconds(310, 310, startedAtMs = 0L, nowMs = 60_000L),
        )
        assertEquals(
            null,
            Format.etaSeconds(320, 310, startedAtMs = 0L, nowMs = 60_000L),
        )
    }

    @Test
    fun eta_is_a_linear_estimate_from_the_observed_rate() {
        // 100 frames in 10 s -> 10 frames/s; 210 remaining -> 21 s.
        assertEquals(
            21L,
            Format.etaSeconds(100, 310, startedAtMs = 0L, nowMs = 10_000L),
        )
    }

    @Test
    fun eta_rounds_to_whole_seconds() {
        // 100 frames in 10.5 s -> ~9.52 frames/s; 210 remaining -> 22.05 s
        // -> 22 (whole seconds; a sub-second ETA would flicker 0↔1).
        assertEquals(
            22L,
            Format.etaSeconds(100, 310, startedAtMs = 0L, nowMs = 10_500L),
        )
    }

    @Test
    fun eta_long_math_survives_a_long_clip() {
        // 600 frames done of a 60,000-frame clip after 600 s: remaining
        // 59,400 * 600,000 ms = 35.64e9 — overflows Int, fine in Long.
        assertEquals(
            59_400L,
            Format.etaSeconds(600, 60_000, startedAtMs = 0L, nowMs = 600_000L),
        )
    }
}
