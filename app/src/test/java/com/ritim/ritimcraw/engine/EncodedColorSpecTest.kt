package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.color.PlanStep
import com.ritim.ritimcraw.data.color.SourceMatrix
import com.ritim.ritimcraw.data.color.SourcePrimaries
import com.ritim.ritimcraw.data.color.SourceProfileVectors
import com.ritim.ritimcraw.data.color.SourceRange
import com.ritim.ritimcraw.data.color.SourceTransfer
import com.ritim.ritimcraw.data.color.planFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E0: the container's color facts -> one EncodedColorSpec, and the P010 pack.
 * The fixture rows are the values `ffprobe` / the HEVC VUI reported for the
 * Mi-Log clip VID_20261110_004247.mp4: HEVC Main10, full range, BT.2020 NCL
 * (matrix 9, primaries 9), transfer code 254 (Android reports unspecified).
 */
class EncodedColorSpecTest {

    private fun miLogFixture() = EncodedColorSpec.fromMediaFormat(
        mime = "video/hevc",
        colorStandard = EncodedColorSpec.COLOR_STANDARD_BT2020,
        colorRange = EncodedColorSpec.COLOR_RANGE_FULL,
        colorTransfer = null, // 254 has no MediaFormat constant
        codecProfile = 2, // HEVCProfileMain10
    )

    @Test
    fun mi_log_fixture_is_10bit_full_bt2020_milog() {
        val c = miLogFixture()
        assertEquals(10, c.declaredBitDepth)
        assertEquals(10, c.bitDepth)
        assertTrue(c.fullRange)
        assertEquals(SourceMatrix.BT2020, c.profile.matrix)
        assertEquals(SourcePrimaries.BT2020, c.profile.primaries)
        assertEquals(SourceTransfer.MiLog, c.profile.transfer)
        // the native projection core reads
        assertEquals(2, c.nativeMatrix)
        assertEquals(0, c.nativeTransfer)
        assertEquals(1, c.nativePrimaries)
    }

    @Test
    fun provenance_is_honest_unknown_transfer_stays_null() {
        val c = miLogFixture()
        assertEquals(com.ritim.ritimcraw.data.color.ProfileProvenance.Container, c.profile.matrixProvenance)
        assertEquals(com.ritim.ritimcraw.data.color.ProfileProvenance.Container, c.profile.rangeProvenance)
        assertEquals(com.ritim.ritimcraw.data.color.ProfileProvenance.Container, c.profile.bitDepthProvenance)
        // Mi-Log is the app's default for an untagged transfer, not a fact the container stated.
        assertNull(c.profile.transferProvenance)
    }

    @Test
    fun untagged_h264_is_the_pre_e0_assumption() {
        val c = EncodedColorSpec.fromMediaFormat("video/avc", null, null, null, null)
        assertEquals(8, c.bitDepth)
        assertFalse(c.fullRange)
        assertEquals(SourceMatrix.BT709, c.profile.matrix)
        assertEquals(SourcePrimaries.BT709, c.profile.primaries)
        assertEquals(SourceTransfer.MiLog, c.profile.transfer)
        // identical to the legacy constant the fakes use
        assertEquals(EncodedColorSpec.legacy8bit().nativeMatrix, c.nativeMatrix)
        assertEquals(EncodedColorSpec.legacy8bit().nativeTransfer, c.nativeTransfer)
        assertEquals(EncodedColorSpec.legacy8bit().nativePrimaries, c.nativePrimaries)
        assertEquals(EncodedColorSpec.legacy8bit().fullRange, c.fullRange)
    }

    @Test
    fun tagged_sdr_709_limited_decodes_with_the_709_inverse_not_milog() {
        val c = EncodedColorSpec.fromMediaFormat(
            "video/avc",
            EncodedColorSpec.COLOR_STANDARD_BT709,
            EncodedColorSpec.COLOR_RANGE_LIMITED,
            EncodedColorSpec.COLOR_TRANSFER_SDR_VIDEO,
            8, // AVCProfileHigh
        )
        assertEquals(SourceTransfer.Rec709OETF, c.profile.transfer)
        assertEquals(1, c.nativeTransfer)
        assertFalse(c.fullRange)
        assertEquals(8, c.bitDepth)
        assertTrue(c.approximations.isEmpty())
    }

    @Test
    fun bt601_tags_select_the_601_matrix_and_say_so() {
        for (std in intArrayOf(EncodedColorSpec.COLOR_STANDARD_BT601_PAL, EncodedColorSpec.COLOR_STANDARD_BT601_NTSC)) {
            val c = EncodedColorSpec.fromMediaFormat("video/avc", std, null, null, null)
            assertEquals(0, c.nativeMatrix)
            assertEquals(0, c.nativePrimaries) // approximated by 709; stated
            assertTrue(c.approximations.any { it.contains("bt601") })
        }
    }

    @Test
    fun hdr_transfers_are_named_and_reported_as_approximated() {
        val pq = EncodedColorSpec.fromMediaFormat(
            "video/hevc", EncodedColorSpec.COLOR_STANDARD_BT2020, EncodedColorSpec.COLOR_RANGE_LIMITED,
            EncodedColorSpec.COLOR_TRANSFER_ST2084, 0x1000,
        )
        assertEquals(SourceTransfer.Pq, pq.profile.transfer)
        assertTrue(pq.approximations.any { it.startsWith("pq") })
        val hlg = EncodedColorSpec.fromMediaFormat(
            "video/hevc", EncodedColorSpec.COLOR_STANDARD_BT2020, EncodedColorSpec.COLOR_RANGE_LIMITED,
            EncodedColorSpec.COLOR_TRANSFER_HLG, 2,
        )
        assertEquals(SourceTransfer.Hlg, hlg.profile.transfer)
        assertTrue(hlg.describe().contains("approx="))
    }

    @Test
    fun declared_depth_per_codec_profile() {
        assertEquals(10, EncodedColorSpec.declaredBitDepth("video/hevc", 2))
        assertEquals(10, EncodedColorSpec.declaredBitDepth("video/hevc", 0x1000))
        assertEquals(8, EncodedColorSpec.declaredBitDepth("video/hevc", 1))
        assertEquals(10, EncodedColorSpec.declaredBitDepth("video/avc", 0x10))
        assertEquals(8, EncodedColorSpec.declaredBitDepth("video/avc", 8))
        assertEquals(10, EncodedColorSpec.declaredBitDepth("video/av01", 2))
        assertEquals(8, EncodedColorSpec.declaredBitDepth("video/mp4v-es", 2))
        assertEquals(8, EncodedColorSpec.declaredBitDepth("video/hevc", null))
    }

    @Test
    fun decoded_depth_wins_over_the_container_claim() {
        val c = miLogFixture()
        assertEquals(c, c.withDecodedBitDepth(10)) // unchanged
        val dropped = c.withDecodedBitDepth(8)
        assertEquals(8, dropped.bitDepth)
        assertEquals(10, dropped.declaredBitDepth)
        assertTrue(dropped.approximations.any { it.contains("8-bit for a 10-bit") })
        // everything else survives
        assertTrue(dropped.fullRange)
        assertEquals(SourceMatrix.BT2020, dropped.profile.matrix)
    }

    @Test
    fun preview_and_export_share_the_color_plan() {
        // The native develop implements exactly the B1 plan for this profile:
        // YCbCr (full, 10-bit, 2020) -> MiLog decode -> 2020 -> 709 gamut.
        val plan = planFor(miLogFixture().profile)
        val ycc = plan.decodeSteps[0] as PlanStep.YcbcrToRgb
        assertEquals(SourceMatrix.BT2020, ycc.matrix)
        assertEquals(SourceRange.Full, ycc.range)
        assertEquals(10, ycc.bitDepth)
        assertEquals(PlanStep.DecodeTransfer(SourceTransfer.MiLog), plan.decodeSteps[1])
        assertEquals(
            PlanStep.GamutToWorking(SourcePrimaries.BT2020, SourcePrimaries.BT709),
            plan.decodeSteps[2],
        )
    }

    @Test
    fun legacy_assumption_still_describes_itself() {
        assertEquals("8-bit limited BT709 MiLog primaries=BT709", EncodedColorSpec.legacy8bit().describe())
        assertEquals("10-bit full BT2020 MiLog primaries=BT2020", miLogFixture().describe())
    }

    // ---- P010 pack (the Kotlin side of the CPU oracle) ----

    /** Builds MediaCodec-style P010 planes: 16-bit LE words, value << 6, Y pixelStride 2, UV pixelStride 4. */
    private fun p010Planes(w: Int, h: Int, y: (Int, Int) -> Int, cb: (Int, Int) -> Int, cr: (Int, Int) -> Int,
                           rowPad: Int = 0): YuvPlanes {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val yStride = w * 2 + rowPad
        val uvStride = cw * 4 + rowPad
        val yBuf = ByteArray(yStride * h)
        val uvBuf = ByteArray(uvStride * ch)
        fun put(buf: ByteArray, at: Int, code10: Int, garbage: Int) {
            val word = (code10 shl 6) or garbage
            buf[at] = (word and 0xFF).toByte()
            buf[at + 1] = (word shr 8).toByte()
        }
        for (r in 0 until h) for (c in 0 until w) put(yBuf, r * yStride + c * 2, y(r, c), (r + c) and 63)
        for (r in 0 until ch) for (c in 0 until cw) {
            put(uvBuf, r * uvStride + c * 4, cb(r, c), 5)
            put(uvBuf, r * uvStride + c * 4 + 2, cr(r, c), 9)
        }
        // Image semantics: the U plane starts at the first Cb byte, the V plane 2 bytes later.
        val uPlane = YuvPlane(uvBuf, 0, uvStride, 4)
        val vPlane = YuvPlane(uvBuf, 2, uvStride, 4)
        return YuvPlanes(
            ptsUs = 0, width = w, height = h,
            y = YuvPlane(yBuf, 0, yStride, 2), u = uPlane, v = vPlane, bitDepth = 10,
        )
    }

    @Test
    fun p010_pack_keeps_both_bytes_and_tightens_strides() {
        val w = 6
        val h = 4
        val planes = p010Planes(
            w, h,
            y = { r, c -> 64 + r * 100 + c * 7 },
            cb = { r, c -> 400 + r * 20 + c },
            cr = { r, c -> 600 - r * 20 - c },
            rowPad = 8, // padded rows must not leak into the pack
        )
        val f = EncodedYuv.toNv12(planes)
        assertEquals(10, f.bitDepth)
        assertEquals(w * 2, f.stride)
        assertEquals(((w + 1) / 2) * 4, f.uvStride)
        assertEquals(w * h * 2, f.y.size)
        fun word(a: ByteArray, i: Int) = (a[i].toInt() and 0xFF) or ((a[i + 1].toInt() and 0xFF) shl 8)
        for (r in 0 until h) for (c in 0 until w) {
            // code = word >> 6 (the develop's extraction); the 6 low bits are the garbage the decoder left
            assertEquals(64 + r * 100 + c * 7, word(f.y, (r * w + c) * 2) shr 6)
        }
        for (r in 0 until h / 2) for (c in 0 until w / 2) {
            assertEquals(400 + r * 20 + c, word(f.u, (r * (w / 2) + c) * 4) shr 6)
            assertEquals(600 - r * 20 - c, word(f.u, (r * (w / 2) + c) * 4 + 2) shr 6)
        }
    }

    @Test
    fun eight_bit_pack_is_unchanged() {
        val w = 4
        val h = 2
        val y = YuvPlane(ByteArray(w * h) { (it + 16).toByte() }, 0, w, 1)
        val uv = ByteArray(4) { (100 + it).toByte() }
        val planes = YuvPlanes(0, w, h, y, YuvPlane(uv, 0, 4, 2), YuvPlane(uv, 1, 4, 2))
        val f = EncodedYuv.toNv12(planes)
        assertEquals(8, f.bitDepth)
        assertEquals(w, f.stride)
        assertEquals(listOf<Byte>(16, 17, 18, 19, 20, 21, 22, 23), f.y.toList())
        assertEquals(listOf<Byte>(100, 101, 102, 103), f.u.toList())
    }

    // ---- Kotlin reference of the spec (cross-checks the C++ oracle's constants) ----

    @Test
    fun reference_vectors_cover_bt2020_full_10bit() {
        // Full-range 10-bit gray 512: 512/1023 on every channel.
        val g = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT2020, SourceRange.Full, 10, 512f, 512f, 512f)
        for (v in g) assertEquals(512f / 1023f, v, 1e-6f)
        // BT.2020 NCL on a saturated Cr sample: R = Y + 1.4746*Cr (exact 2*(1-Kr)).
        val rgb = SourceProfileVectors.ycbcrToRgb(SourceMatrix.BT2020, SourceRange.Full, 10, 300f, 512f, 900f)
        val y = 300f / 1023f
        val cr = (900f - 512f) / 1023f
        assertEquals(y + 1.4746f * cr, rgb[0], 1e-5f)
        // and luma is preserved: Kr*R + Kg*G + Kb*B == Y for the 2020 weights
        val luma = 0.2627f * rgb[0] + 0.6780f * rgb[1] + 0.0593f * rgb[2]
        assertEquals(y, luma, 2e-4f)
    }
}
