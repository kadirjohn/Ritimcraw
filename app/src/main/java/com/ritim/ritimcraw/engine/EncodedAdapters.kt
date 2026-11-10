package com.ritim.ritimcraw.engine

import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer

/**
 * The real [EncodedTrackReader]: MediaExtractor over the clip's video track.
 * These are the ONLY Android framework classes in the extractor half of the
 * decode path — MediaExtractor cannot run in JVM unit tests (framework stubs
 * throw), so every decision around it lives in the JVM-tested
 * [EncodedSource] state machine, and this class stays a thin, device-gated
 * adapter (the MediaCodecMp4Writer pattern of the export path).
 *
 * The extractor's own sample walk at open() builds the sample index (per-
 * sample pts + sync flags); the reader contract (end of stream after
 * indexing, seek before the first readSample) is [EncodedTrackReader]'s.
 */
internal class MediaExtractorTrackReader : EncodedTrackReader {

    private var extractor: MediaExtractor? = null
    private var pfd: ParcelFileDescriptor? = null
    private var closed = false

    override fun open(fd: Int): EncodedOpenOutcome {
        check(!closed && extractor == null) { "open can be called once; use a new reader" }
        // Ownership of the descriptor passes to this reader (the per-open fd
        // discipline of the MCRAW paths); release() closes it with the
        // extractor. setDataSource keeps reading from it, so both live and
        // die together.
        val descriptor = ParcelFileDescriptor.adoptFd(fd)
        val ex = try {
            MediaExtractor().also { it.setDataSource(descriptor.fileDescriptor) }
        } catch (e: Exception) {
            runCatching { descriptor.close() }
            return EncodedOpenOutcome.Failed(EncodedErrors.OPEN_IO, "setDataSource: ${e.message}")
        }
        extractor = ex
        pfd = descriptor
        try {
            // Track scan: the FIRST video track is the edit target; the first
            // audio track describes the clip's audio (read without selecting —
            // only the video track is ever selected or read).
            var video = -1
            var sampleRate = 0
            var channels = 0
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (video < 0 && mime.startsWith("video/")) {
                    video = i
                    continue
                }
                if (sampleRate == 0 && mime.startsWith("audio/")) {
                    if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                }
            }
            if (video < 0) {
                return EncodedOpenOutcome.Failed(EncodedErrors.OPEN_NO_VIDEO_TRACK)
            }
            val format = ex.getTrackFormat(video)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime !in EncodedSource.SUPPORTED_VIDEO_MIMES) {
                return EncodedOpenOutcome.Failed(EncodedErrors.OPEN_UNSUPPORTED_MIME, "mime $mime")
            }
            if (!format.containsKey(MediaFormat.KEY_WIDTH) ||
                !format.containsKey(MediaFormat.KEY_HEIGHT)
            ) {
                return EncodedOpenOutcome.Failed(
                    EncodedErrors.OPEN_NO_VIDEO_TRACK, "video track without width/height"
                )
            }
            ex.selectTrack(video)

            // Sample index: per-sample pts + sync flag, in container order.
            // selectTrack positions at the track's first sample; the walk ends
            // at end of stream (the reader contract: the pipeline seeks before
            // its first readSample).
            val pts = ArrayList<Long>()
            val sync = ArrayList<Boolean>()
            while (true) {
                val samplePts = ex.sampleTime
                if (samplePts < 0) break
                pts.add(samplePts)
                sync.add(ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                if (!ex.advance()) break
            }
            if (pts.isEmpty()) {
                return EncodedOpenOutcome.Failed(
                    EncodedErrors.OPEN_NO_VIDEO_TRACK, "video track holds no samples"
                )
            }

            // Color tags (sprint plan Task 6): the track's color standard
            // decides the YUV matrix. An untagged SDR stream defaults to
            // BT.709 (the H.264/HEVC SDR convention); only an explicit
            // BT.601 tag switches the matrix. The Mi-Log transfer itself is
            // the developEncoded stage, independent of this choice.
            var isBt709 = true
            if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
                isBt709 = format.getInteger(MediaFormat.KEY_COLOR_STANDARD) !=
                    MediaFormat.COLOR_STANDARD_BT601_NTSC &&
                    format.getInteger(MediaFormat.KEY_COLOR_STANDARD) !=
                    MediaFormat.COLOR_STANDARD_BT601_PAL
            }

            // Rotation hint (absent = 0): the develop tail rotates upright.
            val orientation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                format.getInteger(MediaFormat.KEY_ROTATION)
            } else 0

            // Codec-specific data (csd-0/csd-1: avcC/hvcC -> SPS/PPS) — the
            // decoder cannot start without it; the pipeline never inspects
            // it, it only carries it to the decoder adapter.
            val csd = ArrayList<ByteArray>()
            for (key in listOf("csd-0", "csd-1")) {
                if (format.containsKey(key)) {
                    val buf = format.getByteBuffer(key)
                    if (buf != null) {
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        csd.add(bytes)
                    }
                }
            }

            return EncodedOpenOutcome.Opened(
                format = EncodedVideoFormat(
                    mime = mime,
                    width = format.getInteger(MediaFormat.KEY_WIDTH),
                    height = format.getInteger(MediaFormat.KEY_HEIGHT),
                    orientationDeg = orientation,
                    isBt709 = isBt709,
                    codecConfig = csd,
                ),
                index = EncodedSampleIndex(pts.toLongArray(), sync.toBooleanArray()),
                sampleRate = sampleRate,
                channels = channels,
            )
        } catch (e: Exception) {
            return EncodedOpenOutcome.Failed(EncodedErrors.OPEN_IO, e.message)
        }
    }

    override fun seekTo(ptsUs: Long) {
        val ex = extractor ?: throw IllegalStateException("open the clip first")
        ex.seekTo(ptsUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    }

    override fun readSample(): EncodedSample? {
        val ex = extractor ?: throw IllegalStateException("open the clip first")
        val size = ex.sampleSize // long on modern API levels
        if (size <= 0) return null // end of stream
        val bytes = ByteArray(size.toInt())
        val n = ex.readSampleData(ByteBuffer.wrap(bytes), 0)
        if (n < size) return null // truncated sample: surface as end of stream
        val pts = ex.sampleTime
        if (pts < 0) return null
        val sync = ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
        ex.advance()
        return EncodedSample(bytes, pts, sync)
    }

    override fun release() {
        if (closed) return
        closed = true
        extractor?.let { runCatching { it.release() } }
        extractor = null
        pfd?.let { runCatching { it.close() } }
        pfd = null
    }
}

/**
 * The real [EncodedFrameDecoder]: MediaCodec decode-to-Image of the
 * extractor's samples. Same thin-adapter contract as the reader: no decode
 * decisions live here beyond what MediaCodec itself demands.
 *
 * The decoder is created by MIME (createDecoderByType) and configured with
 * the track's format (which carries the csd buffers); output is read as Image
 * (COLOR_FormatYUV420Flexible planes with the codec's own strides), copied
 * into JVM arrays, and converted to packed NV12 by the JVM-tested
 * [EncodedYuv].
 */
internal class MediaCodecFrameDecoder : EncodedFrameDecoder {

    private var codec: MediaCodec? = null
    private var released = false

    override fun configure(format: EncodedVideoFormat): String? {
        check(!released && codec == null) { "configure can be called once; use a new decoder" }
        val mediaFormat = MediaFormat.createVideoFormat(format.mime, format.width, format.height)
        // csd buffers in container order: csd-0 first, then csd-1.
        var csdIndex = 0
        for (buf in format.codecConfig) {
            mediaFormat.setByteBuffer("csd-$csdIndex", ByteBuffer.wrap(buf))
            csdIndex++
        }
        val c = try {
            MediaCodec.createDecoderByType(format.mime)
        } catch (e: Exception) {
            return "createDecoderByType(${format.mime}): ${e.message}"
        }
        try {
            c.configure(mediaFormat, null, null, 0)
            c.start()
        } catch (e: Exception) {
            runCatching { c.release() }
            return "configure(${format.mime}): ${e.message}"
        }
        codec = c
        return null
    }

    override fun flush() {
        codec?.flush()
    }

    override fun queueSample(sample: EncodedSample) {
        val c = codec ?: throw IllegalStateException("configure the decoder first")
        val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            throw IllegalStateException("no input buffer for the sample at ${sample.ptsUs}")
        }
        val buf = c.getInputBuffer(index) ?: throw IllegalStateException("no input buffer")
        buf.clear()
        buf.put(sample.bytes)
        c.queueInputBuffer(index, 0, sample.bytes.size, sample.ptsUs, 0)
    }

    override fun queueEos() {
        val c = codec ?: throw IllegalStateException("configure the decoder first")
        val index = c.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            c.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    override fun dequeueFrame(timeoutUs: Long): YuvPlanes? {
        val c = codec ?: throw IllegalStateException("configure the decoder first")
        val info = MediaCodec.BufferInfo()
        val index = c.dequeueOutputBuffer(info, timeoutUs)
        if (index < 0) return null // try again later / format change: nothing ready
        try {
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            val image = c.getOutputImage(index)
                ?: throw IllegalStateException("decoder gave no output Image")
            return imageToPlanes(image, info.presentationTimeUs)
        } finally {
            // Every path releases the buffer back to the codec (a leaked
            // output buffer would stall the decoder after a few frames).
            c.releaseOutputBuffer(index, false)
        }
    }

    override fun release() {
        if (released) return
        released = true
        codec?.let { c ->
            runCatching { c.stop() }
            runCatching { c.release() }
        }
        codec = null
    }

    /** Copies the codec's Image planes into JVM arrays (the Image dies with its buffer). */
    private fun imageToPlanes(image: Image, ptsUs: Long): YuvPlanes {
        fun plane(p: Image.Plane): YuvPlane {
            val buf = p.buffer
            val bytes = ByteArray(buf.remaining())
            buf.get(bytes)
            return YuvPlane(bytes, 0, p.rowStride, p.pixelStride)
        }
        return YuvPlanes(
            ptsUs = ptsUs,
            width = image.width,
            height = image.height,
            y = plane(image.planes[0]),
            u = plane(image.planes[1]),
            v = plane(image.planes[2]),
        )
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000_000L // 10 s: decode is slow, not stuck
    }
}
