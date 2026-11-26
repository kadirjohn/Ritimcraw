package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** D0: the diagnostics JNI tail mapping and the pure text builder. */
class RawDiagnosticsTest {

    private val labels = RawDiagnosticsLabels(
        levels = "Black %1\$s | White %2\$d | Above white %3\$d",
        blackSplit = "R%1\$d Gr%2\$d Gb%3\$d B%4\$d",
        channel = "%1\$s peak %2\$d (x%3\$d, near %4\$d)%5\$s | %6\$s",
        greens = "Gr %1\$d Gb %2\$d",
        plateauYes = "plateau yes @%1\$s",
        plateauNo = "no reliable plateau",
    )

    /** The 57 s DCG clip, frame 0: black 1023, white 16383, G peak 16368 x78, no plateau. */
    private fun dcgArray(): IntArray {
        val a = IntArray(RawHistogram.JNI_LENGTH_DIAG)
        a[0] = RawHistogram.JNI_VERSION
        val b = RawDiagnostics.BASE
        a[b] = 1
        for (i in 0..3) a[b + 1 + i] = 1023
        a[b + 5] = 16383
        a[b + 6] = 15000; a[b + 7] = 16368; a[b + 8] = 16352; a[b + 9] = 14000
        a[b + 10] = 15000; a[b + 11] = 16368; a[b + 12] = 14000
        a[b + 13] = 3; a[b + 14] = 78; a[b + 15] = 2
        a[b + 16] = 40; a[b + 17] = 528; a[b + 18] = 30
        // above white 0, reliable 0 (slots stay zero), thresholds 1.0
        for (i in 0..2) a[b + 25 + i] = 1_000_000
        return a
    }

    @Test
    fun maps_the_appended_slots() {
        val d = RawHistogram.fromJni(dcgArray())!!.diagnostics
        assertNotNull(d)
        assertEquals(listOf(1023, 1023, 1023, 1023), d!!.blackDeclared)
        assertEquals(16383, d.whiteDeclared)
        assertEquals(listOf(15000, 16368, 16352, 14000), d.peakRawPos)
        assertEquals(listOf(15000, 16368, 14000), d.peakRaw)
        assertEquals(78, d.atPeak[1])
        assertEquals(528, d.nearPeak[1])
        assertEquals(listOf(false, false, false), d.reliablePlateau)
        assertEquals(1.0f, d.plateauThreshold[1], 0f)
    }

    @Test
    fun legacy_length_has_no_diagnostics_and_flag_zero_is_absent() {
        val legacy = IntArray(RawHistogram.JNI_LENGTH)
        legacy[0] = RawHistogram.JNI_VERSION
        assertNull(RawHistogram.fromJni(legacy)!!.diagnostics)
        val flagOff = dcgArray()
        flagOff[RawDiagnostics.BASE] = 0
        assertNull(RawHistogram.fromJni(flagOff)!!.diagnostics)
        // an in-between length is still rejected
        assertNull(RawHistogram.fromJni(IntArray(RawHistogram.JNI_LENGTH_DIAG - 1).also { it[0] = 1 }))
    }

    @Test
    fun dcg_text_says_no_reliable_plateau_honestly() {
        val d = RawHistogram.fromJni(dcgArray())!!.diagnostics!!
        val lines = RawDiagnosticsText.lines(d, labels)
        assertEquals("Black 1023 | White 16383 | Above white 0", lines[0])
        assertEquals("G peak 16368 (x78, near 528) Gr 16368 Gb 16352 | no reliable plateau", lines[2])
        assertEquals("R peak 15000 (x3, near 40) | no reliable plateau", lines[1])
    }

    @Test
    fun plateau_yes_prints_the_threshold_and_split_blacks_are_spelled_out() {
        val a = dcgArray()
        val b = RawDiagnostics.BASE
        a[b + 1] = 64; a[b + 2] = 65; a[b + 3] = 66; a[b + 4] = 67
        a[b + 22 + 1] = 1
        a[b + 25 + 1] = 994_000
        a[b + 19 + 1] = 2
        val d = RawHistogram.fromJni(a)!!.diagnostics!!
        assertTrue(d.reliablePlateau[1]); assertFalse(d.reliablePlateau[0])
        val lines = RawDiagnosticsText.lines(d, labels)
        assertEquals("Black R64 Gr65 Gb66 B67 | White 16383 | Above white 2", lines[0])
        assertTrue(lines[2].endsWith("| plateau yes @0.994"))
    }

    @Test
    fun small_panels_keep_header_and_green_first() {
        val d = RawHistogram.fromJni(dcgArray())!!.diagnostics!!
        assertEquals(1, RawDiagnosticsText.linesFor(d, labels, 1).size)
        val two = RawDiagnosticsText.linesFor(d, labels, 2)
        assertEquals(2, two.size)
        assertTrue(two[1].startsWith("G peak"))
        assertEquals(3, RawDiagnosticsText.linesFor(d, labels, 3).size)
        assertEquals(4, RawDiagnosticsText.linesFor(d, labels, 9).size)
    }
}
