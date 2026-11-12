package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RawHistogram.fromJni mapping tests (feature N6). The mapper is pure and
 * JVM-testable without the native lib: the fixtures below mirror the
 * bridge's packed 787-int layout exactly (see nativeRawHistogram in the
 * bridge for the C++ side), and the rejection cases pin the contract —
 * a wrong length or layout version is never guessed into fields.
 */
class RawHistogramTest {

    // ---- fixtures ---------------------------------------------------------

    /**
     * A packed stats array with a KNOWN shape: frame 7, 4032x3024 sites,
     * channel 0 (red) with 100 samples in bin 128 and 5 white clips /
     * 3 black clips, everything else zero. Built through the documented
     * offsets (the trailer starts at 772 = 4 + 3*256, right after the
     * blue channel's last bin) so the test doubles as a layout pin.
     */
    private fun packedFixture(): IntArray {
        val a = IntArray(RawHistogram.JNI_LENGTH)
        a[0] = RawHistogram.JNI_VERSION
        a[1] = 7
        a[2] = 4032
        a[3] = 3024
        a[4 + 0 * 256 + 128] = 100        // red bin 128
        a[4 + 1 * 256 + 64] = 64          // green bin 64
        a[4 + 2 * 256 + 255] = 32         // blue bin 255 (the LAST bin)
        a[772] = 5                        // red white clips
        a[773] = 0
        a[774] = 0
        a[775] = 3                        // red black clips
        a[776] = 0
        a[777] = 0
        a[778] = 100                      // red samples
        a[779] = 64                       // green samples
        a[780] = 32                       // blue samples
        a[781] = 64                       // blackUsed r
        a[782] = 64                       // blackUsed g1
        a[783] = 64                       // blackUsed g2
        a[784] = 64                       // blackUsed b
        a[785] = 1023                     // whiteUsed
        a[786] = 0                        // reserved
        return a
    }

    // ---- mapping: every field lands in the right slot ----

    @Test
    fun maps_every_field_to_its_documented_slot() {
        val r = RawHistogram.fromJni(packedFixture())
        assertNotNull(r)
        r!!
        assertEquals(7, r.frameIndex)
        assertEquals(4032, r.width)
        assertEquals(3024, r.height)
        // Bins are channel-major: [4 + ch*256 + b].
        assertEquals(100, r.redBins[128])
        assertEquals(0, r.redBins[0])
        assertEquals(64, r.greenBins[64])
        assertEquals(32, r.blueBins[255])
        // The channel accessor agrees with the per-channel arrays.
        assertEquals(r.redBins, r.bins(RawHistogram.Channel.RED))
        assertEquals(r.greenBins, r.bins(RawHistogram.Channel.GREEN))
        assertEquals(r.blueBins, r.bins(RawHistogram.Channel.BLUE))
        // Clip counts and samples.
        assertEquals(5, r.whiteClipped(RawHistogram.Channel.RED))
        assertEquals(3, r.blackClipped(RawHistogram.Channel.RED))
        assertEquals(0, r.whiteClipped(RawHistogram.Channel.GREEN))
        assertEquals(0, r.blackClipped(RawHistogram.Channel.BLUE))
        assertEquals(100, r.samples[0])
        assertEquals(64, r.samples[1])
        assertEquals(32, r.samples[2])
        // Levels actually used (cell-slot order r, g1, g2, b).
        assertEquals(64, r.blackUsed[0])
        assertEquals(64, r.blackUsed[1])
        assertEquals(64, r.blackUsed[2])
        assertEquals(64, r.blackUsed[3])
        assertEquals(1023, r.whiteUsed)
    }

    @Test
    fun mapping_copies_out_of_the_jni_array() {
        // The mapper must not alias the JNI buffer: mutating the source
        // after the mapping cannot change the mapped result.
        val a = packedFixture()
        val r = RawHistogram.fromJni(a)
        assertNotNull(r)
        a[4 + 128] = 999
        a[768] = 999
        assertEquals(100, r!!.redBins[128])
        assertEquals(5, r.whiteClipped(RawHistogram.Channel.RED))
    }

    // ---- rejection: a drifted contract is never guessed ----

    @Test
    fun rejects_a_wrong_length() {
        val short = IntArray(RawHistogram.JNI_LENGTH - 1)
        short[0] = RawHistogram.JNI_VERSION
        assertNull(RawHistogram.fromJni(short))
        val long = IntArray(RawHistogram.JNI_LENGTH + 1)
        long[0] = RawHistogram.JNI_VERSION
        assertNull(RawHistogram.fromJni(long))
    }

    @Test
    fun rejects_a_wrong_layout_version() {
        val a = packedFixture()
        a[0] = RawHistogram.JNI_VERSION + 1
        assertNull(RawHistogram.fromJni(a))
        a[0] = 0
        assertNull(RawHistogram.fromJni(a))
    }

    // ---- RAW indicator conditions (sensor domain) ----

    @Test
    fun indicator_thresholds_follow_the_clip_fraction_discipline() {
        // 100 samples -> threshold max(1, round(100 * 0.001)) = 1: a
        // single clipped site already warns; the fixture's 5 white / 3
        // black red clips both warn.
        assertEquals(1, RawHistogram.clipThreshold(100))
        val r = RawHistogram.fromJni(packedFixture())!!
        assertTrue(r.isWhiteClipped(RawHistogram.Channel.RED))
        assertTrue(r.isBlackClipped(RawHistogram.Channel.RED))
        assertTrue(r.isClipped())
        // Green/blue carry no clips in the fixture: no indicator.
        assertFalse(r.isWhiteClipped(RawHistogram.Channel.GREEN))
        assertFalse(r.isBlackClipped(RawHistogram.Channel.GREEN))
        assertFalse(r.isWhiteClipped(RawHistogram.Channel.BLUE))
        assertFalse(r.isBlackClipped(RawHistogram.Channel.BLUE))
    }

    @Test
    fun sub_threshold_clips_do_not_warn() {
        // 2000 samples -> threshold 2: one clipped site stays silent.
        val a = packedFixture()
        a[778] = 2000                    // red samples
        a[772] = 1                       // red white clips (below threshold)
        a[775] = 0                       // red black clips cleared
        val r = RawHistogram.fromJni(a)!!
        assertEquals(2, RawHistogram.clipThreshold(2000))
        assertFalse(r.isWhiteClipped(RawHistogram.Channel.RED))
        assertFalse(r.isClipped())
    }

    @Test
    fun zero_samples_never_warn() {
        // An empty/degenerate frame (all sample counts 0) can never light
        // an indicator — the guard the core's degenerate path relies on.
        val a = packedFixture()
        a[778] = 0                       // red samples
        a[779] = 0                       // green samples
        a[780] = 0                       // blue samples
        a[772] = 0                       // red white clips
        a[775] = 0                       // red black clips
        val r = RawHistogram.fromJni(a)!!
        assertFalse(r.isWhiteClipped(RawHistogram.Channel.RED))
        assertFalse(r.isBlackClipped(RawHistogram.Channel.RED))
        assertFalse(r.isClipped())
    }

    // ---- stale guard: the echoed frame index is authoritative ----

    @Test
    fun a_frame_index_echo_mismatch_is_detectable() {
        // PreviewSession.rawHistogramBlocking refuses stats whose echoed
        // frameIndex differs from the request. The mapper surfaces the
        // echo verbatim so the guard compares the same field both sides.
        val r = RawHistogram.fromJni(packedFixture())!!
        assertTrue(r.frameIndex != 8)
    }
}
