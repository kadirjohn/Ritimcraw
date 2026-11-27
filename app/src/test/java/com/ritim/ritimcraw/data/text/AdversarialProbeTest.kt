package com.ritim.ritimcraw.data.text

import org.junit.Assert.assertTrue
import org.junit.Test

/** The inspector's exact adversarial probes, verbatim. */
class AdversarialProbeTest {
    @Test
    fun `probe 1 srt truncated start`() {
        val r = SrtCodec.parse("1\n00:00:0 --> 00:00:02,000\nA\n")
        assertTrue(r.cues.isEmpty())
        assertTrue(r.issues.size == 1 && r.issues[0].reason.contains("timing"))
    }

    @Test
    fun `probe 2 srt one digit second`() {
        val r = SrtCodec.parse("1\n00:00:1,000 --> 00:00:02,000\nA\n")
        assertTrue(r.cues.isEmpty() && r.issues.size == 1)
    }

    @Test
    fun `probe 3 srt one digit minute`() {
        val r = SrtCodec.parse("1\n00:0:01,000 --> 00:00:02,000\nA\n")
        assertTrue(r.cues.isEmpty() && r.issues.size == 1)
    }

    @Test
    fun `probe 4 srt truncated end above start`() {
        val r = SrtCodec.parse("1\n00:00:01,000 --> 00:00:4\nA\n")
        assertTrue(r.cues.isEmpty() && r.issues.size == 1)
    }
}
