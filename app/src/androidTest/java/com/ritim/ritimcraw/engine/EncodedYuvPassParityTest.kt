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

    private companion object {
        /**
         * The mismatch bound for the AgX chain cases ONLY (owner decision 2026-11-25), every other case
         * keeps 0.01%. Both bounds are at most 1 LSB. Why it exists: the AgX GPU shader is a verbatim
         * transcription of the CPU stage, but each channel runs log2 and two pow() calls, and the
         * hardware and std:: implementations of those differ in the last bit, so a rare sample lands on
         * the other side of an 8-bit rounding boundary. Measured on the noise fixture: AgX alone 0.0255%,
         * AgX with exposure and tint 0.0301% (all exactly 1 LSB); ACES, which has no transcendental, is
         * 0. The owner accepted about 0.03%; 0.035% leaves room for the 0.0301% observed here.
         */
        const val AGX_MISMATCH_BOUND_PCT = 0.035
        const val STRICT_MISMATCH_BOUND_PCT = 0.01
    }

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
                spec.nativeMatrix, spec.nativeTransfer, spec.nativePrimaries, k.ev, k.tempK, k.tint, 1,
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

    // ---- E0b-4a: reduce first, then process ----

    /** The code value (8-bit byte, or the top ten bits of a little-endian P010 word) at a byte offset. */
    private fun code(buf: ByteArray, at: Int, bits: Int): Int =
        if (bits > 8) (((buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)) ushr (16 - bits))
        else buf[at].toInt() and 0xFF

    private fun putCode(buf: ByteArray, at: Int, code: Int, bits: Int) {
        if (bits > 8) {
            val word = code shl (16 - bits)
            buf[at] = (word and 0xFF).toByte(); buf[at + 1] = ((word ushr 8) and 0xFF).toByte()
        } else buf[at] = code.toByte()
    }

    /**
     * The CPU reference of the reduce stage: Y = rounded mean (half up) of each N x N luma block, CbCr =
     * rounded mean of each N x N chroma block of the 2x2 pixel group, edge blocks over what exists. It
     * returns a legal 4:2:0 frame of ceil(w/N) x ceil(h/N), the CPU oracle's input shape, with natural strides.
     */
    private fun reduceFrame(f: Fixture, n: Int): Fixture {
        val bps = if (f.bits > 8) 2 else 1
        val cw = (f.w + 1) / 2
        val ch = (f.h + 1) / 2
        val ow = (f.w + n - 1) / n
        val oh = (f.h + n - 1) / n
        val ocw = (ow + 1) / 2
        val och = (oh + 1) / 2
        val y = ByteArray(ow * bps * oh)
        val c = ByteArray(ocw * 2 * bps * och)
        fun mean(sum: Long, cnt: Int) = ((2 * sum + cnt) / (2 * cnt)).toInt()
        for (oy in 0 until oh) for (ox in 0 until ow) {
            var sum = 0L; var cnt = 0
            for (j in 0 until n) for (i in 0 until n) {
                val sx = ox * n + i; val sy = oy * n + j
                if (sx < f.w && sy < f.h) { sum += code(f.y, sy * f.stride + sx * bps, f.bits); cnt++ }
            }
            putCode(y, (oy * ow + ox) * bps, mean(sum, cnt), f.bits)
        }
        for (cy in 0 until och) for (cx in 0 until ocw) {
            var su = 0L; var sv = 0L; var cnt = 0
            for (j in 0 until n) for (i in 0 until n) {
                val sx = cx * n + i; val sy = cy * n + j
                if (sx < cw && sy < ch) {
                    val at = sy * f.uvStride + sx * 2 * bps
                    su += code(f.c, at, f.bits); sv += code(f.c, at + bps, f.bits); cnt++
                }
            }
            val at = (cy * ocw + cx) * 2 * bps
            putCode(c, at, mean(su, cnt), f.bits); putCode(c, at + bps, mean(sv, cnt), f.bits)
        }
        return Fixture(ow, oh, f.bits, y, c, ow * bps, ocw * 2 * bps)
    }

    @Test
    fun the_reduce_first_pass_matches_the_cpu_oracle_on_the_reduced_frame() {
        val picks = listOf(
            cases[2] to 2, cases[2] to 3,   // 8-bit limited 709 MiLog
            cases[4] to 2, cases[4] to 3,   // the owner's Mi-Log profile, 10-bit P010
            cases[5] to 5,                  // 10-bit limited, a larger factor with partial edge blocks
        )
        val lines = ArrayList<String>()
        var worst = 0
        var worstPct = 0.0
        for ((k, n) in picks) {
            val f = fixture(321, 181, k.bits, 0x5151 + 31 * n)   // odd sizes: partial blocks and a half chroma column/row
            val small = reduceFrame(f, n)
            val spec = EncodedColorSpec(
                profile = SourceProfile(
                    primaries = k.primaries, transfer = k.transfer, matrix = k.matrix,
                    range = if (k.full) SourceRange.Full else SourceRange.Limited, bitDepth = k.bits,
                    primariesProvenance = null, transferProvenance = null, matrixProvenance = null,
                    rangeProvenance = null, bitDepthProvenance = null,
                ),
                declaredBitDepth = k.bits,
            )
            val frame = YuvFrameK(small.w, small.h, true, small.y, small.c, ByteArray(0), small.stride, small.uvStride, k.bits)
            val cpu = NativeEncodedDeveloper.develop(frame, spec, 0, GradeK(), null, null)
            assertNotNull("${k.name} N=$n: the CPU oracle returned null", cpu)
            val gpu = NativeSource.jniEncodedYuvPassHeadless(
                f.y, f.c, f.w, f.h, f.stride, f.uvStride, k.bits, k.full,
                spec.nativeMatrix, spec.nativeTransfer, spec.nativePrimaries, 0f, 5600f, 0f, n,
            )
            assertNotNull("${k.name} N=$n: the GPU pass returned null (${NativeSource.jniEncodedYuvPassLastDetail()})", gpu)
            assertTrue("${k.name} N=$n: dims ${gpu!![0]}x${gpu[1]} vs ${small.w}x${small.h}", gpu[0] == small.w && gpu[1] == small.h)
            val cnt = small.w * small.h
            val stats = GradeParitySweep.compare(
                GradeParitySweep.argbBytes(gpu.copyOfRange(2, 2 + cnt)),
                GradeParitySweep.argbBytes(cpu!!.copyOfRange(2, 2 + cnt)),
            )
            val line = String.format(java.util.Locale.ROOT, "%s reduce=%d -> %dx%d: maxDiffLsb=%d mismatch=%.4f%%",
                k.name, n, small.w, small.h, stats.maxDiffLsb, stats.mismatchPct)
            Log.i("ritim-parity", "e0b-yuv-reduce $line")
            lines += line
            if (stats.maxDiffLsb > worst) worst = stats.maxDiffLsb
            if (stats.mismatchPct > worstPct) worstPct = stats.mismatchPct
        }
        assertTrue(
            "reduce-first GPU pass vs CPU oracle: worst maxDiffLsb=$worst mismatch=$worstPct%\n" + lines.joinToString("\n"),
            worst <= 1 && worstPct <= 0.01,
        )
    }

    /** Per-frame GPU-side cost of the reduce-first pass on real-sized frames (diagnostic, logs only). */
    @Test
    fun bench_the_reduce_first_pass_at_4k_and_8k() {
        fun planes(w: Int, h: Int, bits: Int): Triple<ByteArray, ByteArray, IntArray> {
            val bps = if (bits > 8) 2 else 1
            val stride = w * bps
            val uvStride = ((w + 1) / 2) * 2 * bps
            val rnd = java.util.Random(7)
            val y = ByteArray(stride * h).also { rnd.nextBytes(it) }
            val c = ByteArray(uvStride * ((h + 1) / 2)).also { rnd.nextBytes(it) }
            return Triple(y, c, intArrayOf(stride, uvStride))
        }
        val runs = listOf(
            Triple("4K P010 10-bit (Mi-Log 60p clip)", Triple(3840, 2160, 10), 3),
            Triple("8K NV12 8-bit", Triple(7680, 4320, 8), 6),
        )
        for ((name, dims, n) in runs) {
            val (w, h, bits) = dims
            val (y, c, st) = planes(w, h, bits)
            val r = NativeSource.jniEncodedYuvPassBench(y, c, w, h, st[0], st[1], bits, bits == 10, if (bits == 10) 2 else 1, if (bits == 10) 0 else 1, if (bits == 10) 1 else 0, n, 60)
            assertNotNull("$name: bench returned null (${NativeSource.jniEncodedYuvPassLastDetail()})", r)
            Log.i("ritim-parity", String.format(java.util.Locale.ROOT,
                "e0b-yuv-bench %s %dx%d reduce=%d -> %dx%d: upload %.2f ms + draw %.2f ms per frame",
                name, w, h, n, (w + n - 1) / n, (h + n - 1) / n, r!![0], r[1]))
        }
    }

    // ---- E0b-4b: the whole develop chain on an encoded frame, through the develop session ----

    private class ChainCase(val name: String, val caseIndex: Int, val reduce: Int, val grade: GradeK) {
        /** The AgX looks (tonemap 2-5) carry their own, separate, owner-accepted bound: see [AGX_MISMATCH_BOUND_PCT]. */
        val isAgx: Boolean get() = grade.tonemap in 2..5
    }

    @Test
    fun the_whole_chain_on_an_encoded_frame_matches_the_cpu_oracle() {
        val chain = listOf(
            ChainCase("Mi-Log P010, neutral chain", 4, 2, GradeK()),
            ChainCase("Mi-Log P010, sharpness + detail", 4, 2, GradeK().copy(sharpness = 40f, detail = 30f)),
            ChainCase("Mi-Log P010, luma + chroma denoise", 4, 2, GradeK().copy(noiseReduction = 40f, chromaDenoise = 50f)),
            ChainCase("8-bit sRGB, AgX tonemap + exposure + warm tint", 8, 2,
                GradeK().copy(tonemap = 2, exposureEv = 0.5f, tempK = 6200f, tint = 8f)),
            // Single-variable splits of the case above (which sat at 0.030% against the 0.01% bound):
            ChainCase("8-bit sRGB, AgX tonemap only", 8, 2, GradeK().copy(tonemap = 2)),
            ChainCase("8-bit sRGB, ACES tonemap only", 8, 2, GradeK().copy(tonemap = 1)),
            ChainCase("8-bit sRGB, exposure + warm tint only", 8, 2, GradeK().copy(exposureEv = 0.5f, tempK = 6200f, tint = 8f)),
            ChainCase("Mi-Log P010, everything at once", 4, 3,
                GradeK().copy(noiseReduction = 30f, chromaDenoise = 40f, sharpness = 25f, detail = 20f,
                    tonemap = 1, exposureEv = -0.3f, tempK = 5200f, tint = -6f)),
        )
        val session = com.ritim.ritimcraw.export.NativeGpuDevelop.openSession()
        assertTrue("the develop session did not open", session > 0)
        try {
            val lines = ArrayList<String>()
            var worst = 0
            val failures = ArrayList<String>()
            for ((i, k) in chain.withIndex()) {
                val base = cases[k.caseIndex]
                val f = fixture(320, 180, base.bits, 0x7A7A + 13 * i)
                val small = reduceFrame(f, k.reduce)
                val spec = EncodedColorSpec(
                    profile = SourceProfile(
                        primaries = base.primaries, transfer = base.transfer, matrix = base.matrix,
                        range = if (base.full) SourceRange.Full else SourceRange.Limited, bitDepth = base.bits,
                        primariesProvenance = null, transferProvenance = null, matrixProvenance = null,
                        rangeProvenance = null, bitDepthProvenance = null,
                    ),
                    declaredBitDepth = base.bits,
                )
                val frame = YuvFrameK(small.w, small.h, true, small.y, small.c, ByteArray(0), small.stride, small.uvStride, base.bits)
                val cpu = NativeEncodedDeveloper.develop(frame, spec, 0, k.grade, null, null)
                assertNotNull("${k.name}: the CPU oracle returned null", cpu)
                val gpu = NativeSource.jniDevelopSessionRenderYuvParcel(
                    session, f.y, f.c, f.w, f.h, f.stride, f.uvStride, base.bits, base.full,
                    spec.nativeMatrix, spec.nativeTransfer, spec.nativePrimaries, k.reduce,
                    GradeParcel.encode(k.grade),
                )
                assertNotNull("${k.name}: the chain returned null (${NativeSource.previewLastError()})", gpu)
                val n = small.w * small.h
                assertTrue("${k.name}: ${gpu!!.size} bytes for ${small.w}x${small.h}", gpu.size == n * 3)
                val stats = GradeParitySweep.compare(gpu, GradeParitySweep.argbBytes(cpu!!.copyOfRange(2, 2 + n)))
                val line = String.format(java.util.Locale.ROOT, "%s (%dx%d): maxDiffLsb=%d mismatch=%.4f%% over1=%.4f%%",
                    k.name, small.w, small.h, stats.maxDiffLsb, stats.mismatchPct, stats.over1Pct)
                Log.i("ritim-parity", "e0b-chain $line")
                lines += line
                if (stats.maxDiffLsb > worst) worst = stats.maxDiffLsb
                val bound = if (k.isAgx) AGX_MISMATCH_BOUND_PCT else STRICT_MISMATCH_BOUND_PCT
                if (stats.mismatchPct > bound) failures += "${k.name}: ${stats.mismatchPct}% > $bound%"
            }
            assertTrue(
                "encoded chain GPU vs CPU oracle: worst maxDiffLsb=$worst, over-bound cases=$failures\n" + lines.joinToString("\n"),
                worst <= 1 && failures.isEmpty(),
            )
        } finally {
            NativeSource.jniDevelopSessionClose(session)
        }
    }

    // ---- the LUT stage on an encoded frame (encoded GPU door, owner request 2026-11-24) ----

    /** A 17^3 .cube that is clearly not the identity: a gamma lift mixed with a channel rotation. */
    private fun writeTestCube(): java.io.File {
        val n = 17
        val sb = StringBuilder("TITLE \"encoded-parity\"\nLUT_3D_SIZE $n\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val rf = r / (n - 1f); val gf = g / (n - 1f); val bf = b / (n - 1f)
            val lift = { v: Float -> Math.pow(v.toDouble(), 0.85).toFloat() }
            val outR = 0.8f * lift(rf) + 0.2f * gf
            val outG = 0.8f * lift(gf) + 0.2f * bf
            val outB = 0.8f * lift(bf) + 0.2f * rf
            sb.append(String.format(java.util.Locale.ROOT, "%.6f %.6f %.6f\n", outR, outG, outB))
        }
        val f = java.io.File(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "encoded-parity.cube")
        f.writeText(sb.toString())
        return f
    }

    @Test
    fun a_lut_on_an_encoded_frame_matches_the_cpu_oracle() {
        val cube = writeTestCube()
        val session = com.ritim.ritimcraw.export.NativeGpuDevelop.openSession()
        assertTrue("the develop session did not open", session > 0)
        try {
            val lines = ArrayList<String>()
            var worst = 0
            val failures = ArrayList<String>()
            for ((i, caseIndex) in intArrayOf(4, 8).withIndex()) {
                val base = cases[caseIndex]
                val f = fixture(320, 180, base.bits, 0x5151 + 17 * i)
                val reduce = 2
                val small = reduceFrame(f, reduce)
                val spec = EncodedColorSpec(
                    profile = SourceProfile(
                        primaries = base.primaries, transfer = base.transfer, matrix = base.matrix,
                        range = if (base.full) SourceRange.Full else SourceRange.Limited, bitDepth = base.bits,
                        primariesProvenance = null, transferProvenance = null, matrixProvenance = null,
                        rangeProvenance = null, bitDepthProvenance = null,
                    ),
                    declaredBitDepth = base.bits,
                )
                val grade = GradeK().copy(lutStrength = 100f)
                val frame = YuvFrameK(small.w, small.h, true, small.y, small.c, ByteArray(0), small.stride, small.uvStride, base.bits)
                val cpu = NativeEncodedDeveloper.develop(frame, spec, 0, grade, cube.path, null)
                assertNotNull("case $caseIndex: the CPU oracle returned null", cpu)
                val gpu = NativeSource.jniDevelopSessionRenderYuvParcel(
                    session, f.y, f.c, f.w, f.h, f.stride, f.uvStride, base.bits, base.full,
                    spec.nativeMatrix, spec.nativeTransfer, spec.nativePrimaries, reduce,
                    GradeParcel.encode(grade), cube.path, 1.0f,
                )
                assertNotNull("case $caseIndex: the chain returned null (${NativeSource.previewLastError()})", gpu)
                val n = small.w * small.h
                val stats = GradeParitySweep.compare(gpu!!, GradeParitySweep.argbBytes(cpu!!.copyOfRange(2, 2 + n)))
                val line = String.format(java.util.Locale.ROOT, "lut case %d (%dx%d): maxDiffLsb=%d mismatch=%.4f%% over1=%.4f%%",
                    caseIndex, small.w, small.h, stats.maxDiffLsb, stats.mismatchPct, stats.over1Pct)
                Log.i("ritim-parity", "e0b-lut $line")
                lines += line
                if (stats.maxDiffLsb > worst) worst = stats.maxDiffLsb
                if (stats.mismatchPct > STRICT_MISMATCH_BOUND_PCT) failures += "case $caseIndex: ${stats.mismatchPct}%"
            }
            assertTrue(
                "encoded LUT GPU vs CPU oracle: worst maxDiffLsb=$worst, over-bound=$failures\n" + lines.joinToString("\n"),
                worst <= 1 && failures.isEmpty(),
            )
        } finally {
            NativeSource.jniDevelopSessionClose(session)
        }
    }

    // ---- the v2 node chain on an encoded frame (develop session nodes door, owner request 2026-11-24) ----

    @Test
    fun a_node_chain_on_an_encoded_frame_matches_the_cpu_oracle() {
        val session = com.ritim.ritimcraw.export.NativeGpuDevelop.openSession()
        assertTrue("the develop session did not open", session > 0)
        try {
            val init = NativeSource.jniDevelopSessionInitNodes(
                session, GlesShaderSources.NODES, GlesShaderSources.FULLSCREEN_VERTEX,
                GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT,
            )
            assertTrue("the develop session's NODES programs did not compile (${NativeSource.previewLastError()})",
                init != null && init.isNotEmpty() && init[0] == 1)
            val chains = listOf(
                "primaries" to listOf(
                    com.ritim.ritimcraw.data.grade2.GradeNodeV2(
                        sat = 1.3f, contrast = 1.2f,
                        lift = com.ritim.ritimcraw.data.grade2.NodeWheelV2(0.05f, -0.02f, 0.03f),
                    ),
                ),
                "two nodes, temp/tint/hue + log highlight wheel" to listOf(
                    com.ritim.ritimcraw.data.grade2.GradeNodeV2(temp = 0.4f, tint = -0.2f, hue = 15f),
                    com.ritim.ritimcraw.data.grade2.GradeNodeV2(
                        logHi = com.ritim.ritimcraw.data.grade2.NodeWheelV2(0f, 0f, -0.1f), gain = com.ritim.ritimcraw.data.grade2.NodeWheelV2(0.02f, 0.03f, 0.05f),
                    ),
                ),
            )
            val lines = ArrayList<String>()
            var worst = 0
            val failures = ArrayList<String>()
            for ((i, caseIndex) in intArrayOf(4, 8).withIndex()) {
                val base = cases[caseIndex]
                val f = fixture(320, 180, base.bits, 0x6161 + 11 * i)
                val reduce = 2
                val small = reduceFrame(f, reduce)
                val spec = EncodedColorSpec(
                    profile = SourceProfile(
                        primaries = base.primaries, transfer = base.transfer, matrix = base.matrix,
                        range = if (base.full) SourceRange.Full else SourceRange.Limited, bitDepth = base.bits,
                        primariesProvenance = null, transferProvenance = null, matrixProvenance = null,
                        rangeProvenance = null, bitDepthProvenance = null,
                    ),
                    declaredBitDepth = base.bits,
                )
                val frame = YuvFrameK(small.w, small.h, true, small.y, small.c, ByteArray(0), small.stride, small.uvStride, base.bits)
                for ((name, nodes) in chains) {
                    val wire = NodeChainWire.encode(com.ritim.ritimcraw.data.grade2.ClipGradeV2(), nodes)
                    val cpu = NativeEncodedDeveloper.develop(frame, spec, 0, GradeK(), null, wire)
                    assertNotNull("$name: the CPU oracle returned null (${NativeSource.previewLastError()})", cpu)
                    assertTrue("$name: chain door refused (${NativeSource.previewLastError()})",
                        NativeSource.jniDevelopSessionSetColorModelChain(session, wire, null))
                    val gpu = NativeSource.jniDevelopSessionRenderYuvParcel(
                        session, f.y, f.c, f.w, f.h, f.stride, f.uvStride, base.bits, base.full,
                        spec.nativeMatrix, spec.nativeTransfer, spec.nativePrimaries, reduce,
                        GradeParcel.encode(GradeK()),
                    )
                    assertNotNull("$name: the chain returned null (${NativeSource.previewLastError()})", gpu)
                    val n = small.w * small.h
                    val stats = GradeParitySweep.compare(gpu!!, GradeParitySweep.argbBytes(cpu!!.copyOfRange(2, 2 + n)))
                    val line = String.format(java.util.Locale.ROOT, "nodes case %d [%s] (%dx%d): maxDiffLsb=%d mismatch=%.4f%% over1=%.4f%%",
                        caseIndex, name, small.w, small.h, stats.maxDiffLsb, stats.mismatchPct, stats.over1Pct)
                    Log.i("ritim-parity", "e0b-nodes $line")
                    lines += line
                    if (stats.maxDiffLsb > worst) worst = stats.maxDiffLsb
                    if (stats.mismatchPct > STRICT_MISMATCH_BOUND_PCT) failures += "case $caseIndex [$name]: ${stats.mismatchPct}%"
                }
            }
            assertTrue(
                "encoded nodes GPU vs CPU oracle: worst maxDiffLsb=$worst, over-bound=$failures\n" + lines.joinToString("\n"),
                worst <= 1 && failures.isEmpty(),
            )
        } finally {
            NativeSource.jniDevelopSessionClose(session)
        }
    }
}
