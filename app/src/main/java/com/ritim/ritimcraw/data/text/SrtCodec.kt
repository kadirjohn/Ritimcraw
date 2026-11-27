package com.ritim.ritimcraw.data.text

/**
 * SRT parser + formatter (F4-0, pure Kotlin).
 *
 * BLOCK MODEL: a cue is an optional numeric line, an "a --> b" line, then
 * payload lines until a blank line or EOF. Robustness rules (D7):
 *  - the sequence-number line is OPTIONAL and its VALUE is not checked
 *    (1, 1, 3, "7" twice in a row all parse; "12x" also parses — the
 *    parser treats any non-time line as a cue id);
 *  - the arrow separator accepts ',' and '.' fractions, 1..3 fractional
 *    digits and the "no hours" mm:ss,mmm shape;
 *  - BOM, CR, CRLF, LF are normalized before line splitting;
 *  - a block whose timing line is broken yields an ISSUE (line number +
 *    reason) and NO cue — nothing is fabricated;
 *  - endUs <= startUs yields an issue and no cue;
 *  - a cue with NO payload lines yields an issue and no cue (an empty
 *    cue is a data problem, not an empty cue we would have to guess);
 *  - overlapping/unordered cues parse fine; the overlap is REPORTED as
 *    an issue but the cue is kept ("reported, never guessed" applies to
 *    ambiguity, not to legal-if-odd data).
 *
 * FORMATTER: emits the canonical SRT shape
 *   index / hh:mm:ss,mmm --> hh:mm:ss,mmm / payload lines / blank
 * with a trailing newline after the last cue. ROUND TRIP: parse ->
 * format -> parse yields EQUAL cues (startUs/endUs/cleanText/rawText);
 * rawSettings is dropped by the SRT formatter (it is a WebVTT-only
 * field — reported in the plan doc).
 */
object SrtCodec {

    /** Splits input into lines: strips a UTF-8 BOM, unifies CR/CRLF to LF. */
    internal fun lines(text: String): List<String> {
        val stripped = if (text.startsWith("﻿")) text.substring(1) else text
        return stripped.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    }

    fun parse(text: String): ParseResult {
        val ls = lines(text)
        val cues = ArrayList<TextCue>()
        val issues = ArrayList<ParseIssue>()
        var i = 0
        var previousEnd = -1L
        var overlapReported = false
        while (i < ls.size) {
            // Skip blank lines between blocks.
            if (ls[i].isBlank()) {
                i++
                continue
            }
            val blockStart = i + 1 // 1-based line number of this block
            // A line that parses as a timecode range is the arrow line;
            // otherwise the NEXT line must be it (optional cue id line).
            var arrowIdx = i
            var range = SubtitleTimes.toRange(ls[arrowIdx])
            if (range == null && arrowIdx + 1 < ls.size) {
                arrowIdx += 1
                range = SubtitleTimes.toRange(ls[arrowIdx])
            }
            if (range == null) {
                // Not a cue block: report ONCE per block and skip to the
                // next blank line, so one bad block is one issue and the
                // following cue still parses.
                issues.add(ParseIssue(blockStart, "missing or malformed timing line"))
                var k = i
                while (k < ls.size && ls[k].isNotBlank()) k++
                i = k
                continue
            }
            val (startUs, endUs) = range
            if (endUs <= startUs) {
                issues.add(ParseIssue(arrowIdx + 1, "cue end $endUs not after start $startUs"))
            } else {
                val payload = ArrayList<String>()
                var j = arrowIdx + 1
                while (j < ls.size && ls[j].isNotBlank()) {
                    payload.add(ls[j])
                    j++
                }
                if (payload.isEmpty()) {
                    issues.add(ParseIssue(arrowIdx + 1, "cue has no text"))
                } else {
                    val raw = payload.joinToString("\n")
                    if (startUs < previousEnd && !overlapReported) {
                        issues.add(ParseIssue(arrowIdx + 1, "cue starts before previous cue ends"))
                        overlapReported = true // once per file, not once per cue
                    }
                    cues.add(
                        TextCue(
                            startUs = startUs,
                            endUs = endUs,
                            cleanText = TextMarkup.strip(raw),
                            rawText = raw,
                            rawSettings = null,
                        )
                    )
                    previousEnd = endUs
                    i = j
                    continue
                }
                i = j
                continue
            }
            // Broken-timing block: skip to the next blank line so the
            // following cue still parses.
            var k = arrowIdx + 1
            while (k < ls.size && ls[k].isNotBlank()) k++
            i = k
        }
        return ParseResult(cues, issues)
    }

    /**
     * Formats cues as SRT. startUs must be >= 0 (SRT has no negative
     * times); endUs > startUs is enforced by TextCue.
     */
    fun format(cues: List<TextCue>): String {
        val sb = StringBuilder()
        cues.forEachIndexed { idx, cue ->
            require(cue.startUs >= 0) { "SRT cannot express negative start ${cue.startUs}" }
            if (idx > 0) sb.append('\n')
            sb.append(idx + 1).append('\n')
            sb.append(SubtitleTimes.formatSrt(cue.startUs))
                .append(" --> ")
                .append(SubtitleTimes.formatSrt(cue.endUs))
                .append('\n')
            sb.append(cue.rawText).append('\n')
        }
        return sb.toString()
    }
}

/**
 * WebVTT + SRT payload markup stripping: removes tags like <b>, </i>,
 * <c.className>, <v Roger>, <00:12:34.500> but keeps the text between
 * them. Pure removal — no interpretation, no entity decoding (the raw
 * field keeps the original for anyone who needs it).
 */
internal object TextMarkup {

    private val tag = Regex("<[^<>]*>")

    fun strip(s: String): String {
        var out = tag.replace(s, "")
        // VVT leaves stray whitespace where a tag stood between words
        // ("<b>Hello</b> world" is fine); collapse only inside-line runs
        // of spaces that a removal could not have caused intentionally
        // is NOT done — we do not touch content beyond tags.
        out = out.replace('\u0000', ' ') // defensive; no-op for real files
        return out.trim().trim('\n')
    }
}
