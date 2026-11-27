package com.ritim.ritimcraw.engine

import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
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

            // Color (E0): the container's declared range / standard / transfer
            // and the track profile become ONE EncodedColorSpec - the value
            // preview and export both develop with. The pre-E0 code read
            // only the standard (and routed BT.2020 to BT.709); range, depth
            // and transfer were never read.
            fun intOrNull(key: String): Int? =
                if (format.containsKey(key)) format.getInteger(key) else null
            val color = EncodedColorSpec.fromMediaFormat(
                mime = mime,
                colorStandard = intOrNull(MediaFormat.KEY_COLOR_STANDARD),
                colorRange = intOrNull(MediaFormat.KEY_COLOR_RANGE),
                colorTransfer = intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                codecProfile = intOrNull(MediaFormat.KEY_PROFILE),
            )

            if (com.ritim.ritimcraw.BuildConfig.DEBUG) {
                android.util.Log.i(
                    "ritim-trace",
                    "encoded open: mime=$mime ${format.getInteger(MediaFormat.KEY_WIDTH)}x${format.getInteger(MediaFormat.KEY_HEIGHT)} " +
                        "raw[standard=${intOrNull(MediaFormat.KEY_COLOR_STANDARD)} range=${intOrNull(MediaFormat.KEY_COLOR_RANGE)} " +
                        "transfer=${intOrNull(MediaFormat.KEY_COLOR_TRANSFER)} profile=${intOrNull(MediaFormat.KEY_PROFILE)}] -> " +
                        "spec[matrix=${color.profile.matrix} primaries=${color.profile.primaries} transfer=${color.profile.transfer} " +
                        "range=${color.profile.range} declaredBits=${color.declaredBitDepth}] notes=${color.approximations}",
                )
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
                    color = color,
                    codecConfig = csd,
                    // G3 (proxy pass-through): the RAW COLOR_* keys the track
                    // carried, null when untagged — the proxy encoder writes
                    // the source's own tags unchanged; [color] above RESOLVES
                    // an untagged transfer to MiLog, so the raw form is the
                    // honest one for re-tagging.
                    rawColor = com.ritim.ritimcraw.engine.EncodedRawColor(
                        standard = intOrNull(MediaFormat.KEY_COLOR_STANDARD),
                        range = intOrNull(MediaFormat.KEY_COLOR_RANGE),
                        transfer = intOrNull(MediaFormat.KEY_COLOR_TRANSFER),
                    ),
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
    // E0b-2/4b: the plane copies come from a pool (a 4K P010 frame is ~25 MB; fresh arrays cost 13 ms to 900 ms
    // under heap pressure). Only the decode thread touches it.
    private val pool = PlaneBufferPool(maxFree = 9)

    override fun recycle(planes: YuvPlanes) {
        pool.release(planes.y.buffer); pool.release(planes.u.buffer)
        if (planes.v.buffer !== planes.u.buffer) pool.release(planes.v.buffer)
    }

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
        // E0: a 10-bit track is decoded to P010 (16-bit words, 10 significant
        // bits) when the decoder offers it - without the request most
        // decoders hand back 8-bit YUV420 and the two low bits are gone. The
        // delivered depth is read back from the Image itself (imageToPlanes),
        // never assumed, so a decoder that ignores the request is reported
        // as 8-bit rather than mis-read.
        if (format.color.declaredBitDepth > 8) {
            val offersP010 = try {
                c.codecInfo.getCapabilitiesForType(format.mime).colorFormats
                    .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010)
            } catch (e: Exception) {
                false
            }
            if (offersP010) {
                mediaFormat.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010,
                )
            }
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

    override fun dequeueFrame(timeoutUs: Long): YuvPlanes? = dequeueFrame(timeoutUs, true)

    override fun dequeueFrame(timeoutUs: Long, copyPixels: Boolean): YuvPlanes? {
        val c = codec ?: throw IllegalStateException("configure the decoder first")
        val info = MediaCodec.BufferInfo()
        val tWait = System.nanoTime()
        val index = c.dequeueOutputBuffer(info, timeoutUs)
        EncodedFrameTrace.decodeNanos.addAndGet(System.nanoTime() - tWait)
        if (index < 0) return null // try again later / format change: nothing ready
        try {
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
            if (!copyPixels) {
                // E0b-4b: a discarded frame (before the seek target): its pts is all the state machine needs.
                val none = YuvPlane(ByteArray(0), 0, 0, 0)
                return YuvPlanes(ptsUs = info.presentationTimeUs, width = 0, height = 0, y = none, u = none, v = none)
            }
            val image = c.getOutputImage(index)
                ?: throw IllegalStateException("decoder gave no output Image")
            val tCopy = System.nanoTime()
            val planes = imageToPlanes(image, info.presentationTimeUs)
            EncodedFrameTrace.copyNanos.addAndGet(System.nanoTime() - tCopy)
            EncodedFrameTrace.planeBytes.set(planes.y.buffer.size.toLong() + planes.u.buffer.size + planes.v.buffer.size)
            return planes
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

    /**
     * NV12/P010 frames carry Cb and Cr in ONE interleaved plane, which the Image API exposes as two
     * overlapping planes (V is U shifted by one component, and each ends one component short of the full
     * plane). Copying both costs a second chroma-sized array per frame (16 MB at 8K). When the layout is
     * that shape, copy the U plane once, append the V plane's final component, and return the complete
     * interleaved array (U and V then view the SAME array, V at offset bps). Null = take the generic copy.
     */
    private fun interleavedChroma(image: Image, depth: Int): Pair<YuvPlane, ByteArray>? {
        val bps = if (depth > 8) 2 else 1
        val y = image.planes[0]; val u = image.planes[1]; val v = image.planes[2]
        if (y.pixelStride != bps || u.pixelStride != 2 * bps || v.pixelStride != 2 * bps || u.rowStride != v.rowStride) return null
        val w = image.width; val h = image.height
        val cw = (w + 1) / 2; val ch = (h + 1) / 2
        val cNeed = (ch - 1) * u.rowStride + cw * 2 * bps
        val ub = u.buffer; val vb = v.buffer
        val un = ub.remaining(); val vn = vb.remaining()
        if (un < bps + 1 || vn < bps || un > cNeed || un < cNeed - bps) return null
        // V must be U shifted by one component (NV12/P010), not NV21.
        if (ub.get(ub.position() + bps) != vb.get(vb.position())) return null
        val yb = y.buffer
        val yn = yb.remaining()
        val yArr = pool.acquire(yn)
        yb.get(yArr, 0, yn)
        val c = pool.acquire(cNeed)
        ub.get(c, 0, un)
        vb.position(vb.limit() - bps)
        vb.get(c, cNeed - bps, bps)
        return YuvPlane(yArr, 0, y.rowStride, y.pixelStride) to c
    }

    /** Copies the codec's Image planes into JVM arrays (the Image dies with its buffer). */
    private fun imageToPlanes(image: Image, ptsUs: Long): YuvPlanes {
        fun plane(p: Image.Plane): YuvPlane {
            val buf = p.buffer
            val n = buf.remaining()
            val bytes = pool.acquire(n)
            buf.get(bytes, 0, n)
            return YuvPlane(bytes, 0, p.rowStride, p.pixelStride)
        }
        // Depth from the delivered layout: P010 is a 16-bit word per sample
        // (luma pixelStride 2); 8-bit YUV420 is one byte (pixelStride 1).
        val depth = if (image.format == ImageFormat.YCBCR_P010 || image.planes[0].pixelStride == 2) 10 else 8
        interleavedChroma(image, depth)?.let { (yPlane, c) ->
            val bps = if (depth > 8) 2 else 1
            return YuvPlanes(
                ptsUs = ptsUs, width = image.width, height = image.height, y = yPlane,
                u = YuvPlane(c, 0, image.planes[1].rowStride, 2 * bps),
                v = YuvPlane(c, bps, image.planes[1].rowStride, 2 * bps),
                bitDepth = depth,
            )
        }
        return YuvPlanes(
            ptsUs = ptsUs,
            width = image.width,
            height = image.height,
            y = plane(image.planes[0]),
            u = plane(image.planes[1]),
            v = plane(image.planes[2]),
            bitDepth = depth,
        )
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000_000L // 10 s: decode is slow, not stuck
    }
}
