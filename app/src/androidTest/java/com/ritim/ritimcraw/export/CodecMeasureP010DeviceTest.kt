package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.CodecMeasurer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One real 10-bit codec-measure case on the device (2026-11-25): the Main10
 * rows used to come back with zero bytes (first 8-bit YUV420Flexible input
 * asked for under a Main10 profile, then a BufferOverflowException writing
 * the last chroma row of a three-plane P010 Image). Skipped on a phone
 * without a Main10 encoder; red if the row yields no bytes again.
 */
class CodecMeasureP010DeviceTest {
    @Test
    fun a_ten_bit_row_produces_encoded_bytes() {
        val facts = DeviceEncoderFacts.all()
        val matrix = CodecMeasurePlanner.matrix(facts, MeasureOptions(tenBit = true, durationSec = 1.0))
        val case = matrix.cases.firstOrNull { it.bitDepth == 10 && it.fps == 30 && it.rateMode == ExportRateMode.CBR.mediaCodecMode }
        assumeTrue("no Main10 encoder row on this device", case != null)
        val r = CodecMeasurer.runCase(case!!)
        assertTrue("configure refused: ${r.errorCode}", r.configureOk)
        assertNull("the case threw: ${r.errorCode}", r.errorCode)
        assertEquals("every frame must be accepted: $r", (case.durationSec * case.fps).toInt(), r.framesIn)
        assertTrue("no encoded bytes (the zero-byte bug): $r", r.encodedBytes > 0)
    }
}
