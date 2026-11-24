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
) : Mp4Session {

    private class Open(val source: EncodedSource, val info: EncodedClipInfo, val sourceFd: Int)

    private var nextHandle = 1L
    private val sessions = HashMap<Long, Open>()
    private var lastMessage: String? = null

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

    override fun renderFrame(handle: Long, frameIndex: Int, grade: GradeK, lutPath: String?, gyroPath: String?, chainWire: FloatArray?): ByteArray? {
        val s = sessions[handle] ?: return fail("render: no open encoded session")
        return try {
            val argb = s.source.renderFrame(frameIndex, grade, lutPath, upright = false, chainWire = chainWire)
            argbToRgb888(argb, s.info.width, s.info.height)
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

/** A fresh descriptor for the same file (the source adopts and closes it). */
internal fun dupDescriptor(fd: Int): Int =
    android.os.ParcelFileDescriptor.fromFd(fd).detachFd()
