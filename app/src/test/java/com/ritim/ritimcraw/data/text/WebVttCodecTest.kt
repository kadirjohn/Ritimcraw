package com.ritim.ritimcraw.data.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WebVTT codec tests (F4-0). Expected values are hand-written literals
 * (1 s = 1_000_000 us; 1 min = 60_000_000 us; 1 h = 3_600_000_000 us).
 */
class WebVttCodecTest {

    private fun vtt(body: String): String = "WEBVTT\n\n$body"

    // ---- well-formed ------------------------------------------------

    @Test
    fun `parses a minimal well formed file`() {
        val r = WebVttCodec.parse(vtt("00:01:00.000 --> 00:01:02.500\nHello\n"))
        assertEquals(1, r.cues.size)
        assertEquals(60_000_000L, r.cues[0].startUs)
        assertEquals(62_500_000L, r.cues[0].endUs)
        assertEquals("Hello", r.cues[0].cleanText)
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `compact mm-ss shape parses without hours`() {
        val r = WebVttCodec.parse(vtt("01:02.500 --> 01:04.000\nA\n"))
        assertEquals(62_500_000L, r.cues[0].startUs)
        assertEquals(64_000_000L, r.cues[0].endUs)
    }

    @Test
    fun `cue id lines are tolerated`() {
        val r = WebVttCodec.parse(vtt("intro\n00:00:01.000 --> 00:00:02.000\nA\n\noutro\n00:00:02.000 --> 00:00:03.000\nB\n"))
        assertEquals(listOf("A", "B"), r.cues.map { it.cleanText })
        assertTrue(r.issues.isEmpty())
    }

    // ---- metadata blocks --------------------------------------------

    @Test
    fun `header note style region blocks are skipped`() {
        val file = "WEBVTT\n" +
            "NOTE this file is a test\n" +
            "multi line note\n" +
            "\n" +
            "STYLE\n" +
            "::cue { color: red }\n" +
            "\n" +
            "REGION\n" +
            "id:fred width:40%\n" +
            "\n" +
            "00:00:01.000 --> 00:00:02.000\nA\n"
        val r = WebVttCodec.parse(file)
        assertEquals(listOf("A"), r.cues.map { it.cleanText })
        assertTrue(r.issues.isEmpty())
    }

    // ---- cue settings + markup --------------------------------------

    @Test
    fun `cue settings are stored raw and never interpreted`() {
        val r = WebVttCodec.parse(vtt("00:00:01.000 --> 00:00:02.000 position:50% align:center line:-1\nA\n"))
        assertEquals("position:50% align:center line:-1", r.cues[0].rawSettings)
    }

    @Test
    fun `settings absent yields null rawSettings`() {
        val r = WebVttCodec.parse(vtt("00:00:01.000 --> 00:00:02.000\nA\n"))
        assertNull(r.cues[0].rawSettings)
    }

    @Test
    fun `markup tags are stripped from cleanText but kept in rawText`() {
        val r = WebVttCodec.parse(vtt("00:00:01.000 --> 00:00:02.000\n<v Fred><b>Hello</b> <c.yellow>world</c>!\n"))
        assertEquals("Hello world!", r.cues[0].cleanText)
        assertEquals("<v Fred><b>Hello</b> <c.yellow>world</c>!", r.cues[0].rawText)
    }

    @Test
    fun `inline timestamp tags are stripped from cleanText`() {
        val r = WebVttCodec.parse(vtt("00:00:01.000 --> 00:00:05.000\none\n<00:00:03.000>two\n"))
        assertEquals("one\ntwo", r.cues[0].cleanText)
        assertEquals("one\n<00:00:03.000>two", r.cues[0].rawText)
    }

    // ---- broken input: reported, never guessed ----------------------

    @Test
    fun `missing header yields issue and no cues`() {
        val r = WebVttCodec.parse("00:00:01.000 --> 00:00:02.000\nA\n")
        assertTrue(r.cues.isEmpty())
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("WEBVTT"))
        assertEquals(1, r.issues[0].line)
    }

    @Test
    fun `header only file parses to nothing cleanly`() {
        val r = WebVttCodec.parse("WEBVTT\n")
        assertEquals(0, r.cues.size)
        assertEquals(0, r.issues.size)
    }

    @Test
    fun `bad timing line yields issue and parsing continues`() {
        val r = WebVttCodec.parse(
            vtt("00:00:01.000 --> 00:00:0\nA\n\n00:00:02.000 --> 00:00:03.000\nB\n")
        )
        assertEquals(listOf("B"), r.cues.map { it.cleanText })
        assertEquals(1, r.issues.size)
    }

    @Test
    fun `end not after start yields issue and no cue`() {
        val r = WebVttCodec.parse(vtt("00:00:05.000 --> 00:00:05.000\nA\n\n00:00:06.000 --> 00:00:07.000\nB\n"))
        assertEquals(listOf("B"), r.cues.map { it.cleanText })
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("not after"))
    }

    @Test
    fun `overlapping cues parse and report`() {
        val r = WebVttCodec.parse(
            vtt("00:00:01.000 --> 00:00:05.000\nA\n\n00:00:02.000 --> 00:00:06.000\nB\n")
        )
        assertEquals(2, r.cues.size)
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("before previous"))
    }

    @Test
    fun `BOM and CRLF are normalized`() {
        val r = WebVttCodec.parse("﻿WEBVTT\r\n\r\n00:00:01.000 --> 00:00:02.000\r\nA\r\n")
        assertEquals(listOf("A"), r.cues.map { it.cleanText })
        assertTrue(r.issues.isEmpty())
    }

    @Test
    fun `truncated start timecode is rejected not zero`() {
        val r = WebVttCodec.parse(vtt("00:00:0.000 --> 00:00:02.000\nA\n"))
        assertTrue("cues must be empty: ${r.cues}", r.cues.isEmpty())
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("timing"))
    }

    @Test
    fun `one digit minute and second fields are rejected`() {
        val oneDigitSecond = WebVttCodec.parse(vtt("00:00:01.000 --> 00:00:1.000\nA\n"))
        assertTrue(oneDigitSecond.cues.isEmpty())
        assertEquals(1, oneDigitSecond.issues.size)
        assertTrue(oneDigitSecond.issues[0].reason.contains("timing"))

        val oneDigitMinute = WebVttCodec.parse(vtt("00:0:01.000 --> 00:00:02.000\nA\n"))
        assertTrue(oneDigitMinute.cues.isEmpty())
        assertEquals(1, oneDigitMinute.issues.size)
        assertTrue(oneDigitMinute.issues[0].reason.contains("timing"))
    }

    @Test
    fun `truncated end above start is still rejected`() {
        val r = WebVttCodec.parse(vtt("00:00:01.000 --> 00:00:4\nA\n"))
        assertTrue("cues must be empty: ${r.cues}", r.cues.isEmpty())
        assertEquals(1, r.issues.size)
        assertTrue(r.issues[0].reason.contains("timing"))
    }

    @Test
    fun `one digit hour with leading zero dropped still parses`() {
        val r = WebVttCodec.parse(vtt("0:00:01.000 --> 0:00:02.000\nA\n"))
        assertEquals(1, r.cues.size)
        assertEquals(1_000_000L, r.cues[0].startUs)
        assertEquals(2_000_000L, r.cues[0].endUs)
        assertTrue(r.issues.isEmpty())
    }

    // ---- formatter + round trip -------------------------------------

    @Test
    fun `format emits canonical vtt with settings`() {
        val out = WebVttCodec.format(
            listOf(TextCue(1_000_000, 2_500_000, "Hi", "Hi", "align:center"))
        )
        assertEquals("WEBVTT\n\ncue-1\n00:00:01.000 --> 00:00:02.500 align:center\nHi\n", out)
    }

    @Test
    fun `parse format parse round trips cues`() {
        val original = vtt(
            "00:00:01.500 --> 00:00:02.500 position:50%\n<v Fred><b>A</b></v>\n\n" +
                "01:02.500 --> 01:04.000\nB\nsecond line\n"
        )
        val once = WebVttCodec.parse(original)
        val twice = WebVttCodec.parse(WebVttCodec.format(once.cues))
        assertEquals(once.cues, twice.cues)
    }

    @Test
    fun `ten thousand cues parse under a second`() {
        val sb = StringBuilder("WEBVTT\n")
        for (i in 0 until 10_000) {
            val startMs = i * 100L
            sb.append("\ncue-").append(i + 1).append('\n')
            sb.append("00:").append("%02d".format(startMs / 60_000)).append(':')
            sb.append("%02d".format(startMs / 1000 % 60)).append('.')
            sb.append("%03d".format(startMs % 1000))
            sb.append(" --> ")
            val endMs = startMs + 100
            sb.append("00:").append("%02d".format(endMs / 60_000)).append(':')
            sb.append("%02d".format(endMs / 1000 % 60)).append('.')
            sb.append("%03d".format(endMs % 1000))
            sb.append('\n').append("line ").append(i).append('\n')
        }
        val start = System.nanoTime()
        val r = WebVttCodec.parse(sb.toString())
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertEquals(10_000, r.cues.size)
        assertTrue("parse took ${elapsedMs}ms", elapsedMs < 1_000)
    }
}
