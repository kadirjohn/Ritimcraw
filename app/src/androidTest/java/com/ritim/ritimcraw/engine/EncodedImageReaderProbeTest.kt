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
        probe(file, frames, "bytebuffer", null)
        probe(file, frames, "imagereader-yuv420", ImageFormat.YUV_420_888)
        probe(file, frames, "imagereader-p010", ImageFormat.YCBCR_P010)
    }

    private fun describe(img: Image, withHardwareBuffer: Boolean = false): String {
        val p = img.planes.joinToString(" ") { "[rs=${it.rowStride} ps=${it.pixelStride} cap=${it.buffer.capacity()}]" }
        // Image.getHardwareBuffer aborted the process natively on a YUV_420_888 reader image (both a
        // 10-bit stream and 8K), so it is only asked for on the P010 reader.
        val hb = if (!withHardwareBuffer) null else runCatching { img.hardwareBuffer?.let { "hb.format=0x${Integer.toHexString(it.format)} hb.usage=0x${java.lang.Long.toHexString(it.usage)}" } }.getOrNull()
        return "format=${img.format} ${img.width}x${img.height} planes=${img.planes.size} $p ${hb ?: "hb=n/a"}"
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
                        img.close()
                        ready.add(t)
                    }
                }, Handler(thread.looper))
            }
            val codec = MediaCodec.createDecoderByType(mime)
            try {
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
                    codec.releaseOutputBuffer(o, reader != null)
                    out++
                } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && reader != null) {
                    Log.i(tag, "$label output format: ${codec.outputFormat}")
                }
            }
            val ms = (System.nanoTime() - t0) / 1e6
            Log.i(tag, String.format(java.util.Locale.ROOT, "%s %s %dx%d: %d frames in %.0f ms = %.1f ms/frame (%.1f fps) images=%d",
                label, mime, w, h, out, ms, ms / out.coerceAtLeast(1), 1000.0 * out / ms, ready.size))
            codec.stop(); codec.release()
        } finally {
            reader?.close(); thread.quitSafely(); ex.release()
        }
    }
}
