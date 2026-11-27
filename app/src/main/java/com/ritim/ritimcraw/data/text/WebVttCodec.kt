package com.ritim.ritimcraw.data.text

/**
 * WebVTT parser + formatter (F4-0, pure Kotlin).
 *
 * FILE STRUCTURE handled:
 *  - header line "WEBVTT" (optionally followed by text on the same line);
 *    a file whose first line does not start with "WEBVTT" parses to zero
 *    cues + one issue (reported, never guessed);
 *  - NOTE / STYLE / REGION blocks are SKIPPED (their payload ends at the
 *    next blank line);
 *  - a cue is: an optional id line, an "a --> b [settings]" line, then
 *    payload lines until blank/EOF;
 *  - timecodes accept both mm:ss.mmm (legal compact form) and
 *    hh:mm:ss.mmm; fractions of 1..N digits (beyond 3 truncated);
 *  - cue SETTINGS (position:50% align:center ...) are stored VERBATIM in
 *    TextCue.rawSettings and never interpreted;
 *  - payload keeps both fields: rawText (markup intact) and cleanText
 *    (<b>, <i>, <c.x>, <v Name>, <00:00:00.000> tags stripped);
 *  - BOM, CRLF, CR normalized; end<=start and empty payloads produce
 *    issues without fabricating cues; overlaps are reported once.
 *
 * FORMATTER: canonical shape
 *   WEBVTT / blank / [id /] hh:mm:ss.mmm --> hh:mm:ss.mmm [ settings ]
 *   / payload / blank
 * ROUND TRIP: parse -> format -> parse yields equal cues (cleanText,
 * rawText, rawSettings all survive; the optional cue id is renumbered
 * "cue-N" — ids are not payload and are not preserved, reported in the
 * plan doc).
 */
object WebVttCodec {

    fun parse(text: String): ParseResult {
        val ls = SrtCodec.lines(text)
        val cues = ArrayList<TextCue>()
        val issues = ArrayList<ParseIssue>()
        if (ls.isEmpty() || !ls[0].trimStart().startsWith("WEBVTT")) {
            return ParseResult(emptyList(), listOf(ParseIssue(1, "missing WEBVTT header")))
        }
        var i = 1
        // Header text (e.g. "WEBVTT - some text") ends at the first blank line.
        while (i < ls.size && ls[i].isNotBlank()) i++
        var previousEnd = -1L
        var overlapReported = false
        while (i < ls.size) {
            if (ls[i].isBlank()) {
                i++
                continue
            }
            val line = ls[i].trim()
            // Metadata blocks: skip to the end of the block.
            if (line == "NOTE" || line.startsWith("NOTE ") ||
                line == "STYLE" || line.startsWith("STYLE ") ||
                line == "REGION" || line.startsWith("REGION ")
            ) {
                while (i < ls.size && ls[i].isNotBlank()) i++
                continue
            }
            val blockStart = i + 1 // 1-based
            // An id line is anything that is not the arrow line; the
            // arrow line must be this line or the next one.
            var arrowIdx = i
            var range: Pair<Long, Long>? = null
            var settings: String? = null
            var found = SubtitleTimes.toRange(ls[arrowIdx])
            if (found != null) {
                range = found
            } else if (arrowIdx + 1 < ls.size) {
                arrowIdx += 1
                found = SubtitleTimes.toRange(ls[arrowIdx])
                if (found != null) range = found
            }
            if (range == null) {
                issues.add(ParseIssue(blockStart, "missing or malformed cue timing line"))
                while (i < ls.size && ls[i].isNotBlank()) i++
                continue
            }
            // Settings: everything after the end timecode on the arrow line.
            // Scan the end half's tokens right-to-left; the LAST token that
            // parses as a timecode ends the timecodes — the rest is settings.
            val cleaned = ls[arrowIdx].substringBefore('#')
            val endTok = cleaned.split("-->")[1]
            val endTokParts = endTok.trim().split(Regex("\\s+"))
            var settingsStartIdx = -1
            for (k in endTokParts.indices.reversed()) {
                if (SubtitleTimes.toUs(endTokParts[k]) != null) {
                    settingsStartIdx = k + 1
                    break
                }
            }
            settings = if (settingsStartIdx in 1 until endTokParts.size) {
                endTokParts.subList(settingsStartIdx, endTokParts.size).joinToString(" ")
            } else {
                null
            }
            val (startUs, endUs) = range
            if (endUs <= startUs) {
                issues.add(ParseIssue(arrowIdx + 1, "cue end $endUs not after start $startUs"))
                var k = arrowIdx + 1
                while (k < ls.size && ls[k].isNotBlank()) k++
                i = k
                continue
            }
            val payload = ArrayList<String>()
            var j = arrowIdx + 1
            while (j < ls.size && ls[j].isNotBlank()) {
                payload.add(ls[j])
                j++
            }
            if (payload.isEmpty()) {
                issues.add(ParseIssue(arrowIdx + 1, "cue has no text"))
                i = j
                continue
            }
            val raw = payload.joinToString("\n")
            if (startUs < previousEnd && !overlapReported) {
                issues.add(ParseIssue(arrowIdx + 1, "cue starts before previous cue ends"))
                overlapReported = true
            }
            cues.add(
                TextCue(
                    startUs = startUs,
                    endUs = endUs,
                    cleanText = TextMarkup.strip(raw),
                    rawText = raw,
                    rawSettings = settings,
                )
            )
            previousEnd = endUs
            i = j
        }
        return ParseResult(cues, issues)
    }

    /** Formats cues as a WebVTT body (header + cues, blank-line separated). */
    fun format(cues: List<TextCue>): String {
        val sb = StringBuilder("WEBVTT\n")
        cues.forEachIndexed { idx, cue ->
            require(cue.startUs >= 0) { "WebVTT cannot express negative start ${cue.startUs}" }
            sb.append('\n')
            sb.append("cue-").append(idx + 1).append('\n')
            sb.append(SubtitleTimes.formatVtt(cue.startUs, compact = false))
                .append(" --> ")
                .append(SubtitleTimes.formatVtt(cue.endUs, compact = false))
            if (cue.rawSettings != null) sb.append(' ').append(cue.rawSettings)
            sb.append('\n')
            sb.append(cue.rawText).append('\n')
        }
        return sb.toString()
    }
}
