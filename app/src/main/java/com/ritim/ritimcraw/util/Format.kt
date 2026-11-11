package com.ritim.ritimcraw.util

import java.util.Locale

/**
 * Device-independent value formatting (DESIGN.md: tabular-number-friendly
 * timecodes, EV with explicit sign, Kelvin with a thousands separator,
 * clip format lines). Pure functions, JVM-testable, Locale.ROOT so digits
 * and separators never change with the app language — tnum tabular figures
 * render consistently regardless of locale.
 */
object Format {

    /** Default project frame rate (sprint 1 fixtures are 24 fps). */
    const val DEFAULT_FPS = 24

    /**
     * HH:MM:SS:FF timecode for a presentation timestamp in microseconds at
     * [fps]. Fixed two-digit fields (colon-separated) so the string width
     * never changes while a clip plays — tnum-friendly (DESIGN.md).
     */
    fun formatTimecode(ptsUs: Long, fps: Int = DEFAULT_FPS): String {
        val safeFps = if (fps <= 0) DEFAULT_FPS else fps
        val totalFrames = ((ptsUs.coerceAtLeast(0L)) / 1_000_000.0 * safeFps).toLong()
        val frames = (totalFrames % safeFps).toInt()
        val totalSeconds = totalFrames / safeFps
        val seconds = (totalSeconds % 60).toInt()
        val totalMinutes = totalSeconds / 60
        val minutes = (totalMinutes % 60).toInt()
        val hours = (totalMinutes / 60).toInt()
        return String.format(
            Locale.ROOT,
            "%02d:%02d:%02d:%02d",
            hours, minutes, seconds, frames,
        )
    }

    /**
     * Clip duration "M:SS" (or "H:MM:SS" past an hour) from microseconds.
     * Negative inputs clamp to zero (a clip never has negative duration).
     */
    fun formatDuration(us: Long): String {
        val totalSeconds = (us.coerceAtLeast(0L)) / 1_000_000
        val seconds = (totalSeconds % 60).toInt()
        val totalMinutes = totalSeconds / 60
        val minutes = (totalMinutes % 60).toInt()
        val hours = (totalMinutes / 60).toInt()
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** "+1.0 EV" / "-0.3 EV" — explicit sign, one decimal (DESIGN.md Inputs). */
    fun formatEv(ev: Float): String =
        String.format(Locale.ROOT, "%+.1f EV", ev)

    /** "+12" / "-7" — explicit sign for Highlights/Tint (DESIGN.md Inputs). */
    fun formatSigned(value: Int): String =
        String.format(Locale.ROOT, "%+d", value)

    /** "5600 K" — integer Kelvin, no sign. */
    fun formatKelvin(kelvin: Int): String =
        String.format(Locale.ROOT, "%d K", kelvin)

    /**
     * Library/clip format line: "3840×2160 • 310 frames • RGGB". Segments
     * with non-positive values are omitted so a half-probed clip degrades
     * gracefully instead of showing "0×0"; an empty clip shows "".
     *
     * [framesLabel] is the localized word for "frames" (user-visible, so
     * the UI passes a string resource; the default keeps the pure function
     * handy for tests and non-UI callers).
     */
    fun formatClipLine(
        width: Int,
        height: Int,
        frameCount: Int,
        cfa: String,
        framesLabel: String = "frames",
    ): String {
        val segments = mutableListOf<String>()
        if (width > 0 && height > 0) {
            segments.add("${width}×$height")
        }
        if (frameCount > 0) {
            segments.add("$frameCount $framesLabel")
        }
        if (cfa.isNotBlank()) {
            segments.add(cfa)
        }
        return segments.joinToString(" • ")
    }

    /**
     * Editor timecode strip readout: "00:00:00:07 / 00:00:12:22" —
     * current / total, both padded so the strip never reflows.
     */
    fun formatTimecodePair(currentUs: Long, totalUs: Long, fps: Int = DEFAULT_FPS): String =
        "${formatTimecode(currentUs, fps)} / ${formatTimecode(totalUs, fps)}"

    /**
     * The widest possible transport readout string (owner round 3a,
     * 2026-11-11: the readout swayed left-right during playback).
     * formatTimecode pads every field to two digits, so every pair with
     * hours <= 99 has exactly this 25-char shape and the all-zeros pair
     * is its canonical representative. The transport measures this shape
     * once and pins the readout's minimum width to it, so the label keeps
     * a steady width while the frame field ticks ~24x/s — even on a
     * device font that ignores the tnum feature and renders proportional
     * digits.
     *
     * Documented assumption: formatTimecode pads hours to two digits but
     * does NOT clamp them — a clip past 99:59:59:<fps-1> would render
     * three-digit hours ("100:00:00:00") and grow past this bound. No
     * phone recording reaches 100 hours; if one ever did, the pinned
     * readout would resize once (hours change hourly, not per frame) and
     * hold steady again.
     */
    fun widestTimecodePair(): String = "00:00:00:00 / 00:00:00:00"

    /**
     * Width probes for the transport readout: the uniform-digit variants
     * of [widestTimecodePair], one per digit 0..9, the all-zeros pair
     * itself first. A proportional-digit font (the diagnosed cause of
     * the sway — the device font ignores tnum) has no single widest
     * digit knowable in advance: the all-zeros pair measures the ZERO
     * glyphs, and if another digit renders wider, a real pair would
     * still exceed a min-width pinned to the zeros. Glyph advances add
     * up (digits do not kern), so the widest UNIFORM pair bounds every
     * MIXED pair from above: 16 x the widest digit advance >= any 16
     * actual digit advances. Measuring these ten strings once and
     * taking the maximum therefore pins the readout for ANY font —
     * tabular fonts measure all ten identical, proportional fonts get
     * their true widest digit.
     */
    fun timecodeWidthProbes(): List<String> =
        (0..9).map { digit -> widestTimecodePair().replace('0', '0' + digit) }

    /**
     * Export remaining-time text (owner request 2026-11-11 §1): "45s",
     * "2m 13s", "59m 59s", "2h 1m". Whole seconds in, two-unit max —
     * seconds only under a minute, minutes+seconds under an hour, hours+
     * minutes past it (sub-minute seconds are noise at that scale). A zero
     * second unit is dropped ("2m", not "2m 0s"). Locale.ROOT digits via
     * plain interpolation (ASCII regardless of locale), tnum-friendly.
     * Negative input clamps to zero (a remaining time is never negative).
     */
    fun formatEtaLeft(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        if (s < 60) return "${s}s"
        val m = s / 60
        val rem = s % 60
        if (m < 60) return if (rem == 0L) "${m}m" else "${m}m ${rem}s"
        val h = m / 60
        val mRem = m % 60
        return if (mRem == 0L) "${h}h" else "${h}h ${mRem}m"
    }
}
