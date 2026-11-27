package com.ritim.ritimcraw.data.text

/**
 * F4-0: the text/subtitle DATA layer — pure Kotlin, no Android imports,
 * JVM-testable headless (same discipline as the timeline model).
 *
 * TIMEBASE CONTRACT: every time value is a signed integral number of
 * MICROSECONDS (startUs/endUs), the same unit the media pipeline uses.
 * SRT/WebVTT express time in milliseconds; ms -> us is a lossless x1000,
 * so no rounding ever happens in this layer. Snapping to the project
 * frame grid is a TIMELINE-layer concern and deliberately NOT done here
 * (this layer's contract is "lossless"; lossy would destroy the
 * parse -> format -> parse round trip).
 *
 * RAW vs CLEAN (the two-field decision, reported in docs/F4_TEXT_PLAN.md):
 * WebVTT (and sloppy SRT) cues can carry markup (<b>, <i>, <c.classname>,
 * <v Speaker>, <00:00:00.000> timestamps) and cue settings
 * (position:50%, align:center, line:-1 ...). This layer does NOT render
 * or interpret any of it — but it must not LOSE it either:
 *   - cleanText: the markup STRIPPED, lines joined with '\n' — what a
 *     naive renderer would show;
 *   - rawText:   the payload exactly as the file had it (markup intact);
 *   - rawSettings: the WebVTT cue-settings line verbatim, or null when
 *     the format has none (SRT) or the cue carried none. Never parsed.
 *
 * "REPORTED, NEVER GUESSED": a block this layer cannot understand is
 * never fabricated into a cue. [ParseResult.issues] carries the line
 * number plus a reason, and parsing continues with the rest of the file.
 */
data class TextCue(
    /** Cue start on the subtitle timebase, microseconds (>= 0 checked by parsers). */
    val startUs: Long,
    /** Cue end on the subtitle timebase, microseconds (exclusive; > startUs). */
    val endUs: Long,
    /** Markup-stripped payload: lines joined with '\n', tags removed. */
    val cleanText: String,
    /** Payload exactly as the file carried it (markup intact), lines joined '\n'. */
    val rawText: String,
    /** Cue settings line verbatim (WebVTT), or null when absent. Uninterpreted. */
    val rawSettings: String? = null,
) {
    init {
        require(endUs > startUs) { "cue end $endUs must be after start $startUs" }
    }
}

/** One non-fatal parse problem: where it happened and why. */
data class ParseIssue(
    /** 1-based line number in the NORMALIZED (LF) line stream. */
    val line: Int,
    /** Human-readable reason, e.g. "bad timecode 'x'". */
    val reason: String,
)

/**
 * Parser output: the cues that could be understood, plus every problem
 * that could not. Cues are in FILE ORDER (parsers do not re-sort — a
 * file with out-of-order cues keeps its order; overlap issues are
 * reported, not "fixed").
 */
data class ParseResult(
    val cues: List<TextCue>,
    val issues: List<ParseIssue>,
) {
    companion object {
        val EMPTY = ParseResult(emptyList(), emptyList())
    }
}

/**
 * Shared timing helpers for the SRT and WebVTT codecs.
 *
 * ACCEPTED TIMECODE SHAPES (tolerant, per D7):
 *   SRT:  hh:mm:ss,mmm  hh:mm:ss.mmm  hh:mm:ss,m  hh:mm:ss,5  mm:ss,mmm  mm:ss.mmm
 *   VTT:  hh:mm:ss.mmm  mm:ss.mmm     (+ trailing fractional digits beyond 3
 *         are accepted and TRUNCATED — never guessed up)
 * Fractional separator: ',' or '.' in both (real-world SRT files mix them).
 * Missing components default to 0 — "no hours" is a legal file shape,
 * not an error. Values beyond 59 minutes/seconds are REJECTED (ambiguity is
 * guessed nowhere: 01:99:99 is a broken timecode, not 2h39m).
 */
internal object SubtitleTimes {

    /**
     * Parses a single timecode token to microseconds, or null when the
     * token is not a legal timecode. Accepts 2 or 3 leading components
     * (mm:ss / hh:mm:ss) and a ',' or '.' fraction of 1..N digits.
     */
    fun toUs(token: String): Long? {
        val t = token.trim()
        if (t.isEmpty()) return null
        val fracIdx = t.indexOfLast { it == ',' || it == '.' }
        val head = if (fracIdx >= 0) t.substring(0, fracIdx) else t
        val frac = if (fracIdx >= 0) t.substring(fracIdx + 1) else ""
        if (fracIdx >= 0 && (frac.isEmpty() || !frac.all { it.isDigit() })) return null
        val parts = head.split(':')
        if (parts.size !in 2..3) return null
        // hh: 1..3 digits (files in the wild drop the leading zero on hours,
        // and >99 h files exist); mm/ss: EXACTLY two digits — the spec shape.
        // A truncated timecode like "00:00:0" is broken, not zero seconds.
        for ((idx, p) in parts.withIndex()) {
            val maxLen = if (parts.size == 3 && idx == 0) 3 else 2
            if (p.isEmpty() || p.length > maxLen || !p.all { it.isDigit() }) return null
        }
        val h: Long
        val m: Long
        val s: Long
        when (parts.size) {
            2 -> {
                h = 0L; m = parts[0].toLong(); s = parts[1].toLong()
            }
            3 -> {
                h = parts[0].toLong(); m = parts[1].toLong(); s = parts[2].toLong()
            }
            else -> return null
        }
        if (m > 59 || s > 59) return null
        val ms = if (fracIdx >= 0) {
            // 1..3 digits are exact; beyond 3 truncate (report, never round up).
            when {
                frac.length <= 3 -> frac.padEnd(3, '0').toLong()
                else -> frac.substring(0, 3).toLong()
            }
        } else 0L
        return (h * 3600 + m * 60 + s) * 1000_000 + ms * 1000
    }

    /** Parses an "a --> b" arrow line into (startUs, endUs), or null when invalid. */
    fun toRange(arrowLine: String): Pair<Long, Long>? {
        val cleaned = arrowLine.substringBefore('#').trim() // VTT: " --> X settings"
        val bits = cleaned.split("-->")
        if (bits.size != 2) return null
        val a = toUs(bits[0]) ?: return null
        val b = toUs(bits[1]) ?: return null
        return a to b
    }

    /** Formats us as an SRT timecode hh:mm:ss,mmm. */
    fun formatSrt(us: Long): String {
        val ms = us / 1000
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val frac = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, frac)
    }

    /** Formats us as a WebVTT timecode hh:mm:ss.mmm (or mm:ss.mmm when small). */
    fun formatVtt(us: Long, compact: Boolean): String {
        val ms = us / 1000
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val frac = ms % 1000
        return if (compact && h == 0L) {
            "%02d:%02d.%03d".format(m, s, frac)
        } else {
            "%02d:%02d:%02d.%03d".format(h, m, s, frac)
        }
    }
}
