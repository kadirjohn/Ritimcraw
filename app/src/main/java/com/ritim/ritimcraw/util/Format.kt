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

    /** "55" — unsigned readout for 0..100 amount sliders (Temporal NR). */
    fun formatUnsigned(value: Int): String =
        String.format(Locale.ROOT, "%d", value)

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
}
