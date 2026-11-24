package com.ritim.ritimcraw.engine

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * E0b-2's first task (docs/ENCODED_GPU_DECODE_PLAN.md): what does the device's decoder really hand
 * an ImageReader for the owner's clips? Diagnostic, not a gate: it logs ("ritim-probe") the Image
 * format, plane count, strides and the HardwareBuffer format of the first frames for three outputs
 * (ByteBuffer + getOutputImage as today, an ImageReader YUV_420_888, an ImageReader P010) and the
 * decode throughput of each, so the plane-upload shader is written against measured layouts.
 * Clips come from files/probe/<name>.mp4 inside the app's data (copied with run-as; deleted after).
 *   am instrument -w -e class ...EncodedImageReaderProbeTest -e clip log.mp4 ...
 */
class EncodedImageReaderProbeTest {
    private val tag = "ritim-probe"

    @Test
    fun probe_the_decoder_output_layouts() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val name = InstrumentationRegistry.getArguments().getString("clip") ?: "log.mp4"
        val file = File(ctx.filesDir, "probe/$name")
        assumeTrue("copy the clip to ${file.path} first", file.exists())
        val frames = (InstrumentationRegistry.getArguments().getString("frames") ?: "40").toInt()
        // `-e variants imagereader-p010` runs one variant: the YUV_420_888 reader aborts the process on a
        // 10-bit stream and on 8K (2026-11-25), which would otherwise end the run before the next variant.
        val wanted = (InstrumentationRegistry.getArguments().getString("variants")
            ?: "bytebuffer,imagereader-yuv420,imagereader-p010").split(',')
        if ("bytebuffer" in wanted) probe(file, frames, "bytebuffer", null)
        if ("imagereader-yuv420" in wanted) probe(file, frames, "imagereader-yuv420", ImageFormat.YUV_420_888)
        if ("imagereader-p010" in wanted) probe(file, frames, "imagereader-p010", ImageFormat.YCBCR_P010)
        // The production path: MediaCodec ByteBuffer output with P010 requested, planes via getOutputImage.
        if ("bytebuffer-p010" in wanted) probe(file, frames, "bytebuffer-p010", null)
    }

    private fun describe(img: Image, withHardwareBuffer: Boolean = false): String {
        val p = img.planes.joinToString(" ") { "[rs=${it.rowStride} ps=${it.pixelStride} cap=${it.buffer.capacity()}]" }
        // Image.getHardwareBuffer aborted the process natively on a YUV_420_888 reader image (both a
        // 10-bit stream and 8K), so it is only asked for on the P010 reader.
        val hb = if (!withHardwareBuffer) null else runCatching { img.hardwareBuffer?.let { "hb.format=0x${Integer.toHexString(it.format)} hb.usage=0x${java.lang.Long.toHexString(it.usage)}" } }.getOrNull()
        return "format=${img.format} ${img.width}x${img.height} planes=${img.planes.size} $p ${hb ?: "hb=n/a"}"
    }

    /**
     * One frame's plane copy, pooled arrays vs freshly allocated ones, for the P010 reader images.
     * Copies the frame's real extent, NOT buffer.remaining(): the Y plane reports 7680x2176 bytes of
     * capacity for a 2160-row frame and reading all of it faulted (SIGSEGV, SEGV_ACCERR) because the
     * last rows are not mapped.
     */
    private fun measureCopy(
        img: Image, pool: com.ritim.ritimcraw.engine.PlaneBufferPool,
        pooledNs: MutableList<Long>, freshNs: MutableList<Long>,
    ) {
        val w = img.width
        val h = img.height
        val yPlane = img.planes[0]
        val cPlane = img.planes[1]
        val yNeed = (h - 1) * yPlane.rowStride + w * yPlane.pixelStride
        val cNeed = ((h + 1) / 2 - 1) * cPlane.rowStride + ((w + 1) / 2) * cPlane.pixelStride - 2 // the Cb view lacks the last Cr word
        val src = listOf(yPlane.buffer to yNeed, cPlane.buffer to cNeed)
        val t0 = System.nanoTime()
        val got = src.map { (b, n) -> val d = b.duplicate(); val a = pool.acquire(n); d.get(a, 0, n); a }
        pooledNs.add(System.nanoTime() - t0)
        got.forEach { pool.release(it) }
        val t1 = System.nanoTime()
        src.forEach { (b, n) -> val d = b.duplicate(); val a = ByteArray(n); d.get(a, 0, n) }
        freshNs.add(System.nanoTime() - t1)
    }

    private fun probe(file: File, frames: Int, label: String, readerFormat: Int?) {
        val ex = MediaExtractor()
        ex.setDataSource(file.path)
        var track = -1
        for (i in 0 until ex.trackCount) if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")) { track = i; break }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val w = fmt.getInteger(MediaFormat.KEY_WIDTH)
        val h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
        val mime = fmt.getString(MediaFormat.KEY_MIME)!!
        var reader: ImageReader? = null
        val thread = HandlerThread("probe-reader").apply { start() }
        val ready = LinkedBlockingQueue<Long>()
        val pool = com.ritim.ritimcraw.engine.PlaneBufferPool()
        val copyPooledNs = ArrayList<Long>()
        val copyFreshNs = ArrayList<Long>()
        try {
            if (readerFormat != null) {
                reader = try {
                    ImageReader.newInstance(w, h, readerFormat, 4)
                } catch (e: Exception) {
                    Log.w(tag, "$label: ImageReader format $readerFormat not creatable: ${e.message}")
                    return
                }
                reader.setOnImageAvailableListener({ r ->
                    val t = System.nanoTime()
                    r.acquireNextImage()?.let { img ->
                        if (ready.size < 2) Log.i(tag, "$label first image: ${describe(img, readerFormat == ImageFormat.YCBCR_P010)}")
                        if (readerFormat == ImageFormat.YCBCR_P010) measureCopy(img, pool, copyPooledNs, copyFreshNs)
                        img.close()
                        ready.add(t)
                    }
                }, Handler(thread.looper))
            }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
                if (label == "bytebuffer-p010") {
                    fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
                }
                codec.configure(fmt, reader?.surface, null, 0)
            } catch (e: Exception) {
                Log.w(tag, "$label: configure failed: ${e.message}")
                codec.release(); return
            }
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inDone = false
            var out = 0
            var firstLogged = false
            val t0 = System.nanoTime()
            val deadline = t0 + 60_000_000_000L
            while (out < frames && System.nanoTime() < deadline) {
                if (!inDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                if (o >= 0) {
                    if (reader == null && !firstLogged) {
                        codec.getOutputImage(o)?.let { Log.i(tag, "$label first image: ${describe(it)}"); firstLogged = true }
                        Log.i(tag, "$label output format: ${codec.outputFormat}")
                    }
                    if (label == "bytebuffer-p010") codec.getOutputImage(o)?.let { measureCopy(it, pool, copyPooledNs, copyFreshNs) }
                    codec.releaseOutputBuffer(o, reader != null)
                    out++
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && reader != null) {
                    Log.i(tag, "$label output format: ${codec.outputFormat}")
                }
            }
            val ms = (System.nanoTime() - t0) / 1e6
            Log.i(tag, String.format(java.util.Locale.ROOT, "%s %s %dx%d: %d frames in %.0f ms = %.1f ms/frame (%.1f fps) images=%d",
                label, mime, w, h, out, ms, ms / out.coerceAtLeast(1), 1000.0 * out / ms, ready.size))
            if (copyPooledNs.isNotEmpty()) {
                fun med(l: List<Long>) = l.sorted()[l.size / 2] / 1e6
                Log.i(tag, String.format(java.util.Locale.ROOT,
                    "%s plane copy (Y + CbCr into arrays): pooled median %.1f ms (n=%d, pool allocations=%d) vs fresh arrays median %.1f ms (n=%d)",
                    label, med(copyPooledNs), copyPooledNs.size, pool.allocations, med(copyFreshNs), copyFreshNs.size))
            }
            codec.stop(); codec.release()
        } finally {
            reader?.close(); thread.quitSafely(); ex.release()
        }
    }
}
