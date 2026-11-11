package com.ritim.ritimcraw.util

import java.util.Locale
import kotlin.math.roundToInt

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
     * Delegates to [adaptiveTimecode] at 4 fields: the fixed 4-field
     * callers and the adaptive path share ONE math and can never drift.
     */
    fun formatTimecode(ptsUs: Long, fps: Int = DEFAULT_FPS): String =
        adaptiveTimecode(ptsUs, fps, 4)

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
     * Width probes for the transport's per-digit cells (the definitive
     * round-3b fix): the single-glyph strings "0".."9" in the exact
     * style the cells render with. Each cell's width is the maximum
     * advance over these ten probes, so every digit the field can ever
     * show fits its cell on ANY font — tabular fonts measure all ten
     * identical, proportional fonts get their true widest digit. The
     * cells are the widest per-digit boxes, not a per-string bound: a
     * cell's width never depends on the neighboring glyphs at all.
     */
    fun timecodeDigitProbes(): List<String> = (0..9).map { it.toString() }

    /**
     * The canonical all-zeros timecode half at the clip's adaptive field
     * count (2/3/4 fields): every field of every regime is two digits,
     * so this is the exact shape every real half of the regime has —
     * "00:00" / "00:00:00" / "00:00:00:00".
     */
    fun widestTimecodeHalf(fields: Int): String =
        adaptiveTimecode(0L, DEFAULT_FPS, fields)

    /**
     * Width probes for the TOTAL half's fixed box, keyed on the clip's
     * field count (owner round 3b + 2026-11-11b §7): the uniform-digit
     * variants of [widestTimecodeHalf], one per digit 0..9, the
     * all-zeros half first. The widest uniform half bounds every mixed
     * half of the regime from above on any font (glyph advances add up,
     * digits do not kern), so the box measured from these probes fits
     * the total whatever its digits — and the measurement stays
     * content-independent for the whole clip because the field count
     * is a per-clip constant ([fieldsFor] of the total).
     */
    fun timecodeHalfProbes(fields: Int): List<String> =
        (0..9).map { digit -> widestTimecodeHalf(fields).replace('0', '0' + digit) }

    /**
     * Timecode field count for the transport pair (owner request
     * 2026-11-11b §7, adaptive format): the clip's TOTAL duration decides,
     * because the total is the longer half and the format must never switch
     * mid-clip (a switch would reintroduce the width jitter this whole
     * module exists to kill — the current crossing a minute boundary must
     * not change the field count). Boundaries are on the TOTAL:
     *
     *  - total < 1 minute: 2 fields, MM:SS (minutes:seconds)
     *  - 1 minute <= total < 1 hour: 3 fields, MM:SS:FF (minutes:seconds:frames)
     *  - total >= 1 hour: 4 fields, HH:MM:SS:FF
     *
     * The boundary is inclusive on the minute (a total of exactly 60 s
     * needs the frames field to render its last-frame count) and on the
     * hour (exactly 3600 s needs the hours field). Negative totals clamp
     * to the 2-field regime (a clip with no timeline shows MM:SS).
     */
    fun fieldsFor(totalUs: Long): Int = when {
        totalUs < 60_000_000L -> 2
        totalUs < 3_600_000_000L -> 3
        else -> 4
    }

    /**
     * The frames field of a timecode at [fps] for a timestamp in
     * microseconds: the fps-based frame number 00..fps-1 (the same
     * semantics the 4-field formatTimecode always had — NOT centiseconds:
     * MotionCam's salise is the frame slot). Pure helper so the adaptive
     * and the fixed paths compute it identically.
     */
    private fun framesField(ptsUs: Long, fps: Int): String {
        val safeFps = if (fps <= 0) DEFAULT_FPS else fps
        val totalFrames = (ptsUs.coerceAtLeast(0L) / 1_000_000.0 * safeFps).toLong()
        return (totalFrames % safeFps).toString().padStart(2, '0')
    }

    /**
     * Timecode with an adaptive number of [fields] (2/3/4, chosen by
     * [fieldsFor] at the call sites — never a free-form count): 2 fields
     * are MM:SS, 3 are MM:SS:FF, 4 are HH:MM:SS:FF. Every field is
     * two digits, colon-separated, Locale.ROOT — so for one clip every
     * string of one regime has the SAME length and shape (the width
     * stability the transport's measured boxes pin to). The frames
     * field is the fps-based frame number (00..fps-1), the same
     * semantics as the fixed 4-field formatTimecode — one fewer field,
     * not a different kind of field. Fields assume the caller picked the
     * regime from the total ([fieldsFor]); a value beyond its regime
     * wraps within its own field (never outgrows the shape), except the
     * 4-field hours field, which is padded but not clamped (the
     * documented >99h assumption, same as formatTimecode).
     */
    fun adaptiveTimecode(ptsUs: Long, fps: Int, fields: Int): String =
        adaptiveTimecodeFields(ptsUs, fps, fields).joinToString(":")

    /**
     * The per-field digits of an adaptive timecode (the transport's
     * split-readout source, owner round 3b): each entry is ONE field's
     * two digits ("07"), in big-endian order — ["00","12","07"] for a
     * 3-field 12 s + 7 frames. The transport renders every DIGIT of
     * every field in its own measured cell (see EditorTransport), so
     * the fields come as data, not as one string. Field values wrap
     * within their field (see [adaptiveTimecode]).
     */
    fun adaptiveTimecodeFields(ptsUs: Long, fps: Int, fields: Int): List<String> {
        val safeFps = if (fps <= 0) DEFAULT_FPS else fps
        val totalSeconds = (ptsUs.coerceAtLeast(0L)) / 1_000_000
        val seconds = totalSeconds % 60
        val totalMinutes = totalSeconds / 60
        val minutes = totalMinutes % 60
        val hours = totalMinutes / 60
        val ss = seconds.toString().padStart(2, '0')
        val mm = minutes.toString().padStart(2, '0')
        val ff = framesField(ptsUs, safeFps)
        return when (fields) {
            2 -> listOf(mm, ss)
            3 -> listOf(mm, ss, ff)
            4 -> listOf(hours.toString().padStart(2, '0'), mm, ss, ff)
            else -> throw IllegalArgumentException("fields must be 2, 3 or 4 (was $fields)")
        }
    }

    /**
     * Editor timecode strip readout, adaptive (owner request 2026-11-11b
     * §7): current / total at ONE field count, decided by the TOTAL via
     * [fieldsFor] — both halves share it (the total is the longer one, it
     * decides), so the current crossing a minute boundary mid-playback
     * never switches the format. Both halves at [fps] with the frames
     * field 00..fps-1. The transport renders this pair as SPLIT nodes
     * (per-digit cells + static colons + separator + a boxed total, see
     * EditorTransport); this string form is the single-node fallback and
     * the test oracle for the pair's content.
     */
    fun formatTimecodePairAdaptive(currentUs: Long, totalUs: Long, fps: Int = DEFAULT_FPS): String {
        val fields = fieldsFor(totalUs)
        return "${adaptiveTimecode(currentUs, fps, fields)} / " +
            adaptiveTimecode(totalUs, fps, fields)
    }

    /**
     * The total half of the adaptive transport pair. Left-aligned inside
     * its own fixed box; its content never changes during playback
     * (the total is a clip-open constant), so its glyphs never move.
     */
    fun timecodeTotalAdaptive(totalUs: Long, fps: Int = DEFAULT_FPS): String =
        adaptiveTimecode(totalUs, fps, fieldsFor(totalUs))

    // ---- adaptive split readout (owner round 3b) ----
    //
    // The definitive stability argument (the owner's device test narrowed
    // the residual sway to the current time's frames field, with the
    // " / " and the total riding it — see EditorTransport): a glyph's
    // on-screen x is the cumulative advance of every glyph before it, so
    // in ONE text run the fast-ticking frames field moves everything
    // after it. The split readout renders every DIGIT of the current
    // timecode in its own measured cell (widest digit advance, glyph
    // centered) with static colon nodes between fields: an unchanged
    // glyph can never move, at ANY update rate, because its cell's
    // width and position depend on no other glyph's advance. That
    // synthesizes tabular figures per digit even on a font that ignores
    // the tnum feature — the cell grid IS the tabular grid. The " / "
    // and the total are static nodes in fixed boxes (the total is a
    // clip-open constant; it never changes during playback). The
    // separator sits at the same x at ANY two frame values — the
    // acceptance holds by construction, not by font mercy.

    /**
     * The nominal integer fps the readout's frames field uses: a
     * metadata-less clip (fps <= 0) degrades to Format's default, and a
     * fractional rate rounds to the nearest nominal (29.97 -> 30) — the
     * same fallback/rounding PrecisionScrub.readoutTimecode applies, now
     * shared so the transport pair and the precision readout can never
     * disagree on the frame field.
     */
    fun nominalFps(fps: Float): Int =
        if (fps <= 0f) DEFAULT_FPS else fps.roundToInt()

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
