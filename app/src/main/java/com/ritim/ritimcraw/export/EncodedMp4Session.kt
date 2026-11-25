package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.ClipAudioSource
import com.ritim.ritimcraw.engine.EncodedClipInfo
import com.ritim.ritimcraw.engine.EncodedColorSpec
import com.ritim.ritimcraw.engine.EncodedErrors
import com.ritim.ritimcraw.engine.EncodedSource
import com.ritim.ritimcraw.engine.MediaExtractorAudioSource

/**
 * The encoded (Mi-Log / MP4) clip as an [Mp4Session] (E0): the SAME export
 * pipeline, plan, writer and audio path as an MCRAW clip, fed by
 * MediaExtractor + MediaCodec decode and the SAME developEncoded the editor
 * preview runs (one [EncodedSource], one [EncodedColorSpec] - preview and
 * export cannot disagree about what a clip's pixels are).
 *
 * Contract with the pipeline (identical to [NativeMp4Session]):
 *  - [renderFrame] returns the developed frame as 8-bit RGB888, row-major,
 *    NOT rotated (the pipeline rotates pixels from [Mp4SessionInfo.orientationDeg]
 *    plus the job's manual delta);
 *  - [framePts] is presentation order from the container's sample index;
 *  - audio is the clip's own track decoded to interleaved 16-bit PCM
 *    ([audioInfo] / [readAudio]; absent track = 0/0 = video-only);
 *  - everything runs on the ONE export engine thread; a failure is a thrown
 *    message turned into [code]/[stage]/[detail].
 *
 * Descriptors: [open] receives the queue's fd, which the queue keeps open
 * for the whole job and closes itself. Each consumer (video reader, audio
 * extractor) gets its OWN dup ([dupFd]); the source adopts and closes it.
 * Stabilization is not part of encoded v1: [renderFrame] ignores any gyro path.
 *
 * Seams ([newSource], [newAudio], [dupFd]) are injected so the whole thing
 * is JVM-testable with fakes; the defaults are the real device adapters.
 */
internal class EncodedMp4Session(
    private val newSource: () -> EncodedSource = { EncodedSource() },
    private val newAudio: (fd: Int, sampleRate: Int, channels: Int) -> ClipAudioSource =
        { fd, rate, ch -> MediaExtractorAudioSource(fd, rate, ch) },
    private val dupFd: (Int) -> Int = ::dupDescriptor,
    /** Where the clip's color line goes (the plan trail: "ritim-export"). */
    private val log: (String) -> Unit = {},
    /** GL_MAX_TEXTURE_SIZE when the device reports it (a throwaway-context probe); null = unknown. Injected for JVM tests. */
    private val maxTextureSize: () -> Int? = ::probeMaxTextureSize,
    /** The JVM heap limit the CPU fallback is judged against. Injected for JVM tests. */
    private val heapLimitBytes: () -> Long = { Runtime.getRuntime().maxMemory() },
) : Mp4Session {

    private class Open(val source: EncodedSource, val info: EncodedClipInfo, val sourceFd: Int) {
        /** The planner's verdict for this open clip (set by [encoderSurfaceCapable]); null until asked. */
        var plannedPath: EncodedExportPlanner.Path? = null
    }

    private var nextHandle = 1L
    private val sessions = HashMap<Long, Open>()
    private var lastMessage: String? = null
    private var pathLogged = false

    /** The last clip's color, for reports and tests. */
    var lastColor: EncodedColorSpec? = null
        private set

    override fun open(fd: Int): Mp4SessionInfo? {
        lastMessage = null
        val source = newSource()
        return try {
            val info = source.open(dupFd(fd))
            val handle = nextHandle++
            sessions[handle] = Open(source, info, fd)
            lastColor = info.color
            log("encoded source: ${info.mime} ${info.width}x${info.height} frames=${info.frameCount} " +
                "color=${info.color.describe()}")
            Mp4SessionInfo(handle, info.frameCount, info.width, info.height, 0, info.orientationDeg)
        } catch (e: Throwable) {
            lastMessage = e.message ?: e.javaClass.simpleName
            null
        }
    }

    // ---- E0b-5: the GPU -> encoder-surface door (no full-frame RGB on the CPU side; the 8K path) ----

    override fun encoderSurfaceCapable(handle: Long, grade: GradeK, chainWire: FloatArray?, oneToOne: Boolean): Boolean {
        val s = sessions[handle] ?: return false
        val capable = try { s.source.encoderSurfaceCapable() } catch (e: Throwable) { false }
        val chainRefused = try { !s.source.chainOnGpu(grade, chainWire) } catch (e: Throwable) { true }
        val decision = EncodedExportPlanner.decide(
            EncodedExportPlanner.Inputs(
                width = s.info.width, height = s.info.height, bitDepth = s.info.color.bitDepth,
                surfaceCapable = capable, geometryOneToOne = oneToOne, chainRefusedByGpu = chainRefused,
                maxTextureSize = if (s.info.width > 4096 || s.info.height > 4096) maxTextureSize() else null,
                heapLimitBytes = heapLimitBytes(),
            ),
        )
        s.plannedPath = decision.path
        val b = EncodedExportPlanner.gpuBudget(s.info.width, s.info.height, s.info.color.bitDepth)
        log("encoded export path: ${decision.path.name.lowercase()} - ${decision.reason}; " +
            "gpu budget ${b.total / (1024 * 1024)} MiB (chain ${b.chainTextureBytes / (1024 * 1024)} + yuv ${b.yuvPlaneBytes / (1024 * 1024)} + cfa ${b.cfaTextureBytes / (1024 * 1024)})")
        if (decision.path == EncodedExportPlanner.Path.REFUSED) lastMessage = decision.message
        return decision.path == EncodedExportPlanner.Path.SURFACE
    }

    override fun attachEncoderSurface(handle: Long, surface: Any?): Boolean {
        val s = sessions[handle] ?: return false
        return try {
            val ok = s.source.attachEncoderSurface(surface)
            if (!ok && surface != null) lastMessage = "encoder surface: the develop session refused the input surface"
            ok
        } catch (e: Throwable) {
            lastMessage = e.message ?: e.javaClass.simpleName
            false
        }
    }

    override fun renderFrameToEncoder(
        handle: Long, frameIndex: Int, ptsUs: Long, grade: GradeK, lutPath: String?, chainWire: FloatArray?,
        rotationDeg: Int,
    ): Boolean {
        val s = sessions[handle]
        if (s == null) {
            lastMessage = "render: no open encoded session"
            return false
        }
        return try {
            val ok = s.source.renderFrameToEncoder(frameIndex, grade, lutPath, chainWire, ptsUs, rotationDeg)
            if (!ok) lastMessage = "encoded gpu encoder render refused frame $frameIndex"
            ok
        } catch (e: Throwable) {
            lastMessage = e.message ?: e.javaClass.simpleName
            false
        }
    }

    override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?, gyroPath: String?, chainWire: FloatArray?): ByteArray? {
        val s = sessions[handle] ?: return fail("render: no open encoded session")
        return try {
            // E0b-3: the GPU door first (full-size RGB888 straight from the develop session); null = the CPU oracle.
            val gpuRgb = s.source.renderFrameRgbGpu(frameIndex, grade, lutPath, chainWire)
            if (!pathLogged) {
                pathLogged = true
                log("encoded export develop path: " + (if (gpuRgb != null) "gpu (readback)" else "cpu (oracle)"))
            }
            if (gpuRgb != null) return gpuRgb
            // The CPU oracle allocates the whole frame (planes + ARGB + RGB888): when that cannot fit the heap
            // (8K), say so in one short line instead of dying with an OutOfMemoryError.
            EncodedExportPlanner.cpuFallbackRefusal(
                s.info.width, s.info.height, s.info.color.bitDepth, heapLimitBytes(),
            )?.let { return fail(it) }
            val argb = s.source.renderFrame(frameIndex, grade, lutPath, upright = false, chainWire = chainWire)
            argbToRgb888(argb, s.info.width, s.info.height)
        } catch (e: OutOfMemoryError) {
            fail(
                EncodedExportPlanner.cpuFallbackRefusal(s.info.width, s.info.height, s.info.color.bitDepth, 0L)
                    ?: "out of memory developing a ${s.info.width}x${s.info.height} frame"
            )
        } catch (e: Throwable) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    override fun framePts(handle: Long, frameIndex: Int): Long {
        val s = sessions[handle] ?: return -1
        return try { s.source.ptsUs(frameIndex) } catch (e: Throwable) { -1 }
    }

    override fun audioInfo(handle: Long): IntArray? {
        val s = sessions[handle] ?: return null
        return intArrayOf(s.info.sampleRate, s.info.channels)
    }

    override fun readAudio(handle: Long): ShortArray? {
        val s = sessions[handle]
        if (s == null) {
            lastMessage = "audio: no open encoded session"
            return null
        }
        if (s.info.sampleRate <= 0 || s.info.channels <= 0) return ShortArray(0)
        return try {
            val src = newAudio(dupFd(s.sourceFd), s.info.sampleRate, s.info.channels)
            try {
                src.seekTo(0)
                readAll(src)
            } finally {
                src.release()
            }
        } catch (e: Throwable) {
            lastMessage = "audio: " + (e.message ?: e.javaClass.simpleName)
            null
        }
    }

    override fun close(handle: Long) {
        sessions.remove(handle)?.source?.close()
    }

    // The pipeline reads these only after a null return.
    override fun code(): Int = Mp4ExportErrors.CODE_IO
    override fun stage(): Int = Mp4ExportErrors.STAGE_FRAME_READ
    override fun detail(): String? = lastMessage

    private fun fail(message: String): ByteArray? {
        lastMessage = message
        return null
    }

    companion object {
        /** Reads a [ClipAudioSource] to its end as one interleaved PCM array. */
        fun readAll(src: ClipAudioSource, chunkFrames: Int = 4096): ShortArray {
            var out = ShortArray(0)
            var used = 0
            while (true) {
                val chunk = src.readChunk(chunkFrames) ?: break
                if (chunk.isEmpty()) continue
                if (used + chunk.size > out.size) {
                    out = out.copyOf(maxOf(out.size * 2, used + chunk.size))
                }
                System.arraycopy(chunk, 0, out, used, chunk.size)
                used += chunk.size
            }
            return out.copyOf(used)
        }

        /**
         * [width, height, ARGB...] (the develop's preview contract, NOT
         * rotated) -> RGB888 row-major, the pipeline's frame shape. Fails
         * loudly on a malformed array: a wrong-sized frame must never be
         * encoded.
         */
        fun argbToRgb888(out: IntArray, width: Int, height: Int): ByteArray {
            require(out.size == 2 + width * height && out[0] == width && out[1] == height) {
                "${EncodedErrors.STAGE_DECODE}: developed frame is ${out.getOrNull(0)}x${out.getOrNull(1)}, " +
                    "expected ${width}x$height"
            }
            val rgb = ByteArray(width * height * 3)
            var o = 0
            for (i in 2 until out.size) {
                val p = out[i]
                rgb[o++] = (p shr 16).toByte()
                rgb[o++] = (p shr 8).toByte()
                rgb[o++] = p.toByte()
            }
            return rgb
        }
    }
}

/** GL_MAX_TEXTURE_SIZE from the develop probe (a throwaway EGL context), or null when unknown. */
internal fun probeMaxTextureSize(): Int? = try {
    NativeGpuDevelop.probe()?.let { com.ritim.ritimcraw.engine.GlesProbeResult.fromArray(it) }
        ?.takeIf { it.contextOk }?.maxTextureSize?.takeIf { it > 0 }
} catch (e: Throwable) {
    null
}

/** A fresh descriptor for the same file (the source adopts and closes it). */
internal fun dupDescriptor(fd: Int): Int =
    android.os.ParcelFileDescriptor.fromFd(fd).detachFd()
