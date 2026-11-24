package com.ritim.ritimcraw.engine

import android.util.Log
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.color.SourceMatrix
import com.ritim.ritimcraw.data.color.SourcePrimaries
import com.ritim.ritimcraw.data.color.SourceProfile
import com.ritim.ritimcraw.data.color.SourceRange
import com.ritim.ritimcraw.data.color.SourceTransfer
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E0b-2 parity: the GPU YUV pass (gles/EncodedYuvPass) against the CPU oracle, rawedit::developEncoded
 * reached through NativeEncodedDeveloper, over synthetic 8-bit NV12 and P010 frames that cover the whole
 * code range (out-of-legal codes too) with padded row strides, every matrix / range / transfer /
 * primaries the EncodedColorSpec can express, and the WB/exposure gains. The bound is the grade sweep's:
 * at most 1 LSB and at most 0.01% of samples off by one. The frame is neutral past stage 1 (no denoise,
 * detail, tonemap or LUT), so the two arms run the identical stages.
 */
class EncodedYuvPassParityTest {

    private class Fixture(
        val w: Int, val h: Int, val bits: Int,
        val y: ByteArray, val c: ByteArray, val stride: Int, val uvStride: Int,
    )

    private fun fixture(w: Int, h: Int, bits: Int, seed: Int): Fixture {
        val wide = bits > 8
        val bps = if (wide) 2 else 1
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val stride = w * bps + 64          // padded rows: the stride handling is part of the test
        val uvStride = cw * 2 * bps + 64
        val y = ByteArray(stride * h)
        val c = ByteArray(uvStride * ch)
        var x = seed
        fun next(): Int { x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5); return x and 0x7FFFFFFF }
        val max = (1 shl bits) - 1
        fun put(buf: ByteArray, at: Int, code: Int) {
            if (wide) {
                val word = code shl (16 - bits)
                buf[at] = (word and 0xFF).toByte(); buf[at + 1] = ((word ushr 8) and 0xFF).toByte()
            } else buf[at] = code.toByte()
        }
        for (row in 0 until h) for (col in 0 until w) {
            // a diagonal ramp over the full code range plus noise, with occasional extreme codes
            val base = ((row + col) * max / (w + h)) + (next() % 17) - 8
            val code = when (next() % 61) { 0 -> 0; 1 -> max; else -> base.coerceIn(0, max) }
            put(y, row * stride + col * bps, code)
        }
        for (row in 0 until ch) for (col in 0 until cw) {
            val at = row * uvStride + col * 2 * bps
            put(c, at, (next() % (max + 1)))
            put(c, at + bps, (next() % (max + 1)))
        }
        return Fixture(w, h, bits, y, c, stride, uvStride)
    }

    private class Case(
        val name: String, val bits: Int, val full: Boolean,
        val matrix: SourceMatrix, val transfer: SourceTransfer, val primaries: SourcePrimaries,
        val ev: Float = 0f, val tempK: Float = 5600f, val tint: Float = 0f,
    )

    private val cases = listOf(
        Case("8-bit limited 709 Rec709 709", 8, false, SourceMatrix.BT709, SourceTransfer.Rec709OETF, SourcePrimaries.BT709),
        Case("8-bit full 601 sRGB 709", 8, true, SourceMatrix.BT601, SourceTransfer.Srgb, SourcePrimaries.BT709),
        Case("8-bit limited 709 MiLog 709", 8, false, SourceMatrix.BT709, SourceTransfer.MiLog, SourcePrimaries.BT709),
        Case("8-bit limited 2020 Linear 2020", 8, false, SourceMatrix.BT2020, SourceTransfer.Linear, SourcePrimaries.BT2020),
        Case("10-bit full 2020 MiLog 2020 (the owner's Mi-Log clip)", 10, true, SourceMatrix.BT2020, SourceTransfer.MiLog, SourcePrimaries.BT2020),
        Case("10-bit limited 2020 Rec709 2020", 10, false, SourceMatrix.BT2020, SourceTransfer.Rec709OETF, SourcePrimaries.BT2020),
        Case("10-bit limited 709 Linear 709", 10, false, SourceMatrix.BT709, SourceTransfer.Linear, SourcePrimaries.BT709),
        Case("10-bit full 2020 MiLog 2020 +1EV warm tint", 10, true, SourceMatrix.BT2020, SourceTransfer.MiLog, SourcePrimaries.BT2020,
            ev = 1f, tempK = 6400f, tint = 12f),
        Case("8-bit limited 709 sRGB 709 -0.5EV cool", 8, false, SourceMatrix.BT709, SourceTransfer.Srgb, SourcePrimaries.BT709,
            ev = -0.5f, tempK = 4800f, tint = -9f),
    )

    @Test
    fun the_gpu_pass_matches_the_cpu_oracle() {
        val lines = ArrayList<String>()
        var worst = 0
        var worstPct = 0.0
        for ((i, k) in cases.withIndex()) {
            val f = fixture(320, 180, k.bits, 0x1234 + 77 * i)
            val spec = EncodedColorSpec(
                profile = SourceProfile(
                    primaries = k.primaries, transfer = k.transfer, matrix = k.matrix,
                    range = if (k.full) SourceRange.Full else SourceRange.Limited, bitDepth = k.bits,
                    primariesProvenance = null, transferProvenance = null, matrixProvenance = null,
                    rangeProvenance = null, bitDepthProvenance = null,
                ),
                declaredBitDepth = k.bits,
            )
            val grade = GradeK().copy(exposureEv = k.ev, tempK = k.tempK, tint = k.tint)
            val frame = YuvFrameK(f.w, f.h, true, f.y, f.c, ByteArray(0), f.stride, f.uvStride, k.bits)
            val cpu = NativeEncodedDeveloper.develop(frame, spec, 0, grade, null, null)
            assertNotNull("${k.name}: the CPU oracle returned null", cpu)
            val gpu = NativeSource.jniEncodedYuvPassHeadless(
                f.y, f.c, f.w, f.h, f.stride, f.uvStride, k.bits, k.full,
                spec.nativeMatrix, spec.nativeTransfer, spec.nativePrimaries, k.ev, k.tempK, k.tint,
            )
            assertNotNull("${k.name}: the GPU pass returned null (${NativeSource.jniEncodedYuvPassLastDetail()})", gpu)
            val n = f.w * f.h
            val stats = GradeParitySweep.compare(
                GradeParitySweep.argbBytes(gpu!!.copyOfRange(2, 2 + n)),
                GradeParitySweep.argbBytes(cpu!!.copyOfRange(2, 2 + n)),
            )
            val line = String.format(
                java.util.Locale.ROOT, "%s: maxDiffLsb=%d mismatch=%.4f%% over1=%.4f%%",
                k.name, stats.maxDiffLsb, stats.mismatchPct, stats.over1Pct,
            )
            Log.i("ritim-parity", "e0b-yuv-pass $line")
            lines += line
            if (stats.maxDiffLsb > worst) worst = stats.maxDiffLsb
            if (stats.mismatchPct > worstPct) worstPct = stats.mismatchPct
        }
        assertTrue(
            "GPU YUV pass vs CPU oracle: worst maxDiffLsb=$worst mismatch=$worstPct%\n" + lines.joinToString("\n"),
            worst <= 1 && worstPct <= 0.01,
        )
    }
}
