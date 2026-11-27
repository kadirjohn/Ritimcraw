package com.ritim.ritimcraw.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SRT codec tests (F4-0). Every expected value is a hand-written
 * literal — no expectation is derived by calling the code under test
 * (no self-fulfilling assertions).
 *
 * Timecheck helper: ms values below are converted by hand: 1 s = 1000 ms
 * = 1_000_000 us; 1 h = 3_600_000_000 us.
 */
class SrtCodecTest {

    // ---- well-formed ------------------------------------------------

    @Test
    fun `parses a minimal well formed file`() {
        val r = SrtCodec.parse(
            "1\n" +
                "00:00:01,000 --> 00:00:02,500\n" +
                "Hello\n"
        )
        assertEquals(1, r.cues.size)
        assertEquals(1_000_000L, r.cues[0].startUs)
        assertEquals(2_500_000L, r.cues[0].endUs)
        assertEquals("Hello", r.cues[0].cleanText)
        assertEquals("Hello", r.cues[0].rawText)
        assertEquals(null, r.cues[0].rawSettings)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `parses multi-line text with trailing blank separation`() {
        val r = SrtCodec.parse(
            "1\n00:00:00,000 --> 00:00:01,000\nfirst\nsecond line\n\n" +
                "2\n00:00:01,000 --> 00:00:02,000\nthird\n"
        )
        assertEquals(2, r.cues.size)
        assertEquals("first\nsecond line", r.cues[0].rawText)
        assertEquals("third", r.cues[1].rawText)
    }

    // ---- tolerance --------------------------------------------------

    @Test
    fun `missing sequence numbers still parse`() {
        val r = SrtCodec.parse(
            "00:00:01,000 --> 00:00:02,000\nA\n\n" +
                "00:00:02,000 --> 00:00:03,000\nB\n"
        )
        assertEquals(listOf("A", "B"), r.cues.map { it.cleanText })
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `wrong sequence numbers are tolerated`() {
        val r = SrtCodec.parse(
            "9\n00:00:01,000 --> 00:00:02,000\nA\n\n" +
                "9\n00:00:02,000 --> 00:00:03,000\nB\n"
        )
        assertEquals(2, r.cues.size)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `one-digit and dot fractions parse like ms`() {
        val r = SrtCodec.parse(
            "1\n00:00:01,5 --> 00:00:02,500\nA\n\n" +
                "2\n00:00:03.500 --> 00:00:04.750\nB\n"
        )
        assertEquals(1_500_000L, r.cues[0].startUs) // ",5" = ,500
        assertEquals(3_500_000L, r.cues[1].startUs) // "." accepted
        assertEquals(4_750_000L, r.cues[1].endUs)
    }

    @Test
    fun `no-hours mm-ss shape parses`() {
        val r = SrtCodec.parse("1\n01:02,500 --> 01:04,000\nA\n")
        assertEquals(62_500_000L, r.cues[0].startUs) // 1 min 2.5 s
        assertEquals(64_000_000L, r.cues[0].endUs)
    }

    @Test
    fun `BOM and CRLF and lone CR are normalized`() {
        val r = SrtCodec.parse(
            "﻿1\r\n" +
                "00:00:01,000 --> 00:00:02,000\r\n" +
                "A\r\n" +
                "\r\n" +
                "2\r" +
                "\n00:00:02,000 --> 00:00:03,000\nB\n"
        )
        assertEquals(2, r.cues.size)
        assertTrue(r.issues.isEmpty())
    }

    // ---- broken input: reported, never guessed ----------------------

    @Test
    fun `half-written timing line yields issue and no cue`() {
        val r = SrtCodec.parse(
            "1\n00:00:01,000 --> 00:00:0\nA\n\n" +
                "2\n00:00:02,000 --> 00:00:03,000\nB\n"
        )
        assertEquals(listOf("B"), r.cues.map { it.cleanText })
        assertEquals(1, r.issues.size)
        // Contract: a truncated END timecode is a malformed timing line —
        // exactly one reason, never silently reparsed as 0 (which would
        // fabricate a cue or masquerade as an end<=start issue).
        assertTrue(r.issues[0].reason.contains("timing"))
        assertEquals(1, r.issues[0].line) // 1-based: the block's first line
    }

    @Test
    fun `truncated start hour field is rejected not zero`() {
        // "00:00:0" as the START would parse as 0 us and fabricate a cue
        // at the file origin — must be a timing issue instead.
        val r = SrtCodec.parse("1\n00:00:0 --> 00:00:02,000\nA\n")
        assertTrue("cues must be empty: ${r.cues}", r.cues.isEmpty())
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("timing"))
    }

    @Test
    fun `one digit minute and second fields are rejected`() {
        val oneDigitSecond = SrtCodec.parse("1\n00:00:01,000 --> 00:00:1,000\nA\n")
        assertTrue(oneDigitSecond.cues.isEmpty())
        assertEquals(1, oneDigitSecond.issues.size)
        assertTrue(oneDigitSecond.issues[0].reason.contains("timing"))

        val oneDigitMinute = SrtCodec.parse("1\n00:0:01,000 --> 00:00:02,000\nA\n")
        assertTrue(oneDigitMinute.cues.isEmpty())
        assertEquals(1, oneDigitMinute.issues.size)
        assertTrue(oneDigitMinute.issues[0].reason.contains("timing"))
    }

    @Test
    fun `truncated end above start is still rejected`() {
        // "00:00:4" would fabricate endUs=4s (> startUs=1s) — the end<=start
        // guard cannot catch this; the two-digit rule must.
        val r = SrtCodec.parse("1\n00:00:01,000 --> 00:00:4\nA\n")
        assertTrue("cues must be empty: ${r.cues}", r.cues.isEmpty())
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("timing"))
    }

    @Test
    fun `end before or at start yields issue and no cue`() {
        val r = SrtCodec.parse(
            "1\n00:00:05,000 --> 00:00:05,000\nA\n\n" +
                "2\n00:00:06,000 --> 00:00:05,000\nB\n"
        )
        assertTrue(r.cues.isEmpty())
        assertEquals(2, r.issues.size)
        assertTrue(r.issues.all { it.reason.contains("not after") })
    }

    @Test
    fun `empty cue yields issue`() {
        val r = SrtCodec.parse("1\n00:00:01,000 --> 00:00:02,000\n\n2\n00:00:02,000 --> 00:00:03,000\nB\n")
        assertEquals(listOf("B"), r.cues.map { it.cleanText })
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("no text"))
    }

    @Test
    fun `empty file parses to nothing with no issues`() {
        val r = SrtCodec.parse("")
        assertEquals(0, r.cues.size)
        assertEquals(0, r.issues.size)
    }

    @Test
    fun `garbage line yields issue and parsing continues`() {
        val r = SrtCodec.parse("just some text\nno cue here\n\n1\n00:00:01,000 --> 00:00:02,000\nA\n")
        assertEquals(listOf("A"), r.cues.map { it.cleanText })
        assertEquals(1, r.issues.size)
    }

    @Test
    fun `overlapping cues parse and report once`() {
        val r = SrtCodec.parse(
            "1\n00:00:01,000 --> 00:00:05,000\nA\n\n" +
                "2\n00:00:02,000 --> 00:00:06,000\nB\n"
        )
        assertEquals(2, r.cues.size) // kept, reported — not guessed away
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("before previous"))
    }

    @Test
    fun `one digit hour with leading zero dropped still parses`() {
        // Real files write "0:00:01,000"; hours stay 1..3 digits.
        val r = SrtCodec.parse("1\n0:00:01,000 --> 0:00:02,000\nA\n")
        assertEquals(1, r.cues.size)
        assertEquals(1_000_000L, r.cues[0].startUs)
        assertEquals(2_000_000L, r.cues[0].endUs)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `four digit hour field is rejected`() {
        val r = SrtCodec.parse("1\n0000:00:01,000 --> 0000:00:02,000\nA\n")
        assertTrue(r.cues.isEmpty())
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("timing"))
    }

    // ---- formatter + round trip -------------------------------------

    @Test
    fun `format emits canonical srt shape`() {
        val out = SrtCodec.format(
            listOf(
                TextCue(1_000_000, 2_500_000, "Hello", "Hello"),
                TextCue(62_500_000, 64_000_000, "a\nb", "a\nb"),
            )
        )
        assertEquals(
            "1\n00:00:01,000 --> 00:00:02,500\nHello\n" +
                "\n2\n00:01:02,500 --> 00:01:04,000\na\nb\n",
            out
        )
    }

    @Test
    fun `parse format parse round trips cues`() {
        val original = "1\n00:00:01,500 --> 00:00:02,500\nA\n\n2\n00:00:03,000 --> 00:00:04,000\nB\nline2\n"
        val once = SrtCodec.parse(original)
        val twice = SrtCodec.parse(SrtCodec.format(once.cues))
        assertEquals(once.cues, twice.cues)
    }

    @Test
    fun `ten thousand cues parse under a second`() {
        val sb = StringBuilder()
        for (i in 0 until 10_000) {
            sb.append(i).append('\n')
            sb.append("00:").append("%02d".format(i / 1000 % 60)).append(':')
            sb.append("%02d".format(i % 60)).append(",000 --> 00:")
            sb.append("%02d".format((i / 1000 % 60) + 1)).append(':')
            sb.append("%02d".format(i % 60)).append(",000\n")
            sb.append("line ").append(i).append('\n')
            sb.append('\n')
        }
        val start = System.nanoTime()
        val r = SrtCodec.parse(sb.toString())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, r.cues.size)
        assertTrue("parse took ${elapsedMs}ms", elapsedMs < 1_000)
    }
}
