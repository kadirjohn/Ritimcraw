package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.ClipAudioSource
import com.ritim.ritimcraw.engine.EncodedColorSpec
import com.ritim.ritimcraw.engine.EncodedDeveloper
import com.ritim.ritimcraw.engine.EncodedFrameDecoder
import com.ritim.ritimcraw.engine.EncodedOpenOutcome
import com.ritim.ritimcraw.engine.EncodedSample
import com.ritim.ritimcraw.engine.EncodedSampleIndex
import com.ritim.ritimcraw.engine.EncodedSource
import com.ritim.ritimcraw.engine.EncodedTrackReader
import com.ritim.ritimcraw.engine.EncodedVideoFormat
import com.ritim.ritimcraw.engine.YuvFrameK
import com.ritim.ritimcraw.engine.YuvPlane
import com.ritim.ritimcraw.engine.YuvPlanes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** E0: the encoded clip as an export [Mp4Session] (fakes at every boundary). */
class EncodedMp4SessionTest {

    private val color = EncodedColorSpec.fromMediaFormat(
        "video/hevc", EncodedColorSpec.COLOR_STANDARD_BT2020, EncodedColorSpec.COLOR_RANGE_FULL, null, 2,
    )

    private class Reader(val frames: Int, val format: EncodedVideoFormat) : EncodedTrackReader {
        private var cursor = 0
        override fun open(fd: Int): EncodedOpenOutcome {
            cursor = frames
            return EncodedOpenOutcome.Opened(
                format,
                EncodedSampleIndex(LongArray(frames) { it * 33_333L }, BooleanArray(frames) { it == 0 }),
                sampleRate = 48_000, channels = 2,
            )
        }
        override fun seekTo(ptsUs: Long) { cursor = (ptsUs / 33_333L).toInt() }
        override fun readSample(): EncodedSample? =
            if (cursor >= frames) null else EncodedSample(byteArrayOf(1), cursor * 33_333L, cursor == 0).also { cursor++ }
        override fun release() {}
    }

    private class Decoder(private val nv12: Boolean = false) : EncodedFrameDecoder {
        private val pending = ArrayList<EncodedSample>()
        private var eos = false
        override fun configure(format: EncodedVideoFormat): String? = null
        override fun flush() { pending.clear(); eos = false }
        override fun queueSample(sample: EncodedSample) { pending.add(sample) }
        override fun queueEos() { eos = true }
        override fun dequeueFrame(timeoutUs: Long): YuvPlanes? {
            if (pending.isEmpty()) return null
            val s = pending.removeAt(0)
            if (nv12) {
                // 4x2 NV12: one interleaved chroma array, U and V views of it (what the GPU door takes)
                val c = ByteArray(4)
                return YuvPlanes(
                    s.ptsUs, 4, 2,
                    YuvPlane(ByteArray(8), 0, 4, 1), YuvPlane(c, 0, 4, 2), YuvPlane(c, 1, 4, 2),
                )
            }
            return YuvPlanes(
                s.ptsUs, 4, 2,
                YuvPlane(ByteArray(8), 0, 4, 1), YuvPlane(ByteArray(2), 0, 2, 1), YuvPlane(ByteArray(2), 0, 2, 1),
            )
        }
        override fun release() {}
    }

    private class Developer : EncodedDeveloper {
        val orientations = ArrayList<Int>()
        val colors = ArrayList<EncodedColorSpec>()
        override fun develop(frame: YuvFrameK, color: EncodedColorSpec, orientationDeg: Int, grade: GradeK, lutPath: String?, chainWire: FloatArray?): IntArray? {
            orientations.add(orientationDeg)
            colors.add(color)
            // 4x2 image: pixel i = 0xFF, R=i, G=i+10, B=i+20
            return IntArray(2 + 8).also {
                it[0] = 4; it[1] = 2
                for (i in 0 until 8) it[2 + i] = (0xFF shl 24) or (i shl 16) or ((i + 10) shl 8) or (i + 20)
            }
        }
        override fun code() = 0
        override fun stage() = 0
        override fun detail(): String? = null
    }

    private class Audio(private val chunks: List<ShortArray>) : ClipAudioSource {
        override val sampleRate = 48_000
        override val channels = 2
        var released = false
        var seeks = ArrayList<Long>()
        private var i = 0
        override fun seekTo(frameOffset: Long) { seeks.add(frameOffset); i = 0 }
        override fun readChunk(maxFrames: Int): ShortArray? = chunks.getOrNull(i++)
        override fun release() { released = true }
    }

    private fun session(developer: Developer, audio: Audio? = null, dups: MutableList<Int> = ArrayList()): EncodedMp4Session {
        val format = EncodedVideoFormat("video/hevc", 4, 2, 90, color)
        return EncodedMp4Session(
            newSource = { EncodedSource(Reader(3, format), Decoder(), developer) },
            newAudio = { _, _, _ -> audio ?: Audio(emptyList()) },
            dupFd = { fd -> dups.add(fd); 1000 + dups.size },
        )
    }

    @Test
    fun open_reports_info_with_the_container_orientation_and_dups_the_fd() {
        val dups = ArrayList<Int>()
        val s = session(Developer(), dups = dups)
        val info = s.open(7)!!
        assertEquals(3, info.frameCount)
        assertEquals(4, info.width)
        assertEquals(2, info.height)
        assertEquals(90, info.orientationDeg) // the pipeline rotates pixels from this
        assertEquals(listOf(7), dups)
        assertEquals(0L, s.framePts(info.handle, 0))
        assertEquals(66_666L, s.framePts(info.handle, 2))
        assertArrayEquals(intArrayOf(48_000, 2), s.audioInfo(info.handle))
        assertEquals(color, s.lastColor)
        s.close(info.handle)
    }

    @Test
    fun render_returns_unrotated_rgb888_and_the_same_color_as_preview() {
        val dev = Developer()
        val s = session(dev)
        val h = s.open(3)!!.handle
        val rgb = s.renderFrame(h, 1, GradeK(), null, null)!!
        assertEquals(4 * 2 * 3, rgb.size)
        assertEquals(listOf(0), dev.orientations) // never rotated: the export pipeline does it
        assertEquals(1, dev.colors.size)
        assertEquals(color.nativeMatrix, dev.colors[0].nativeMatrix)
        assertTrue(dev.colors[0].fullRange)
        // pixel 3 -> R=3 G=13 B=23
        assertEquals(3, rgb[9].toInt())
        assertEquals(13, rgb[10].toInt())
        assertEquals(23, rgb[11].toInt())
    }

    @Test
    fun argb_conversion_rejects_a_wrong_sized_frame() {
        try {
            EncodedMp4Session.argbToRgb888(intArrayOf(2, 2, 0, 0, 0), 2, 2)
            throw AssertionError("must fail")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expected 2x2"))
        }
    }

    @Test
    fun audio_is_the_whole_track_decoded_from_the_start_on_its_own_descriptor() {
        val audio = Audio(listOf(shortArrayOf(1, 2, 3, 4), shortArrayOf(), shortArrayOf(5, 6)))
        val dups = ArrayList<Int>()
        val s = session(Developer(), audio, dups)
        val h = s.open(9)!!.handle
        val pcm = s.readAudio(h)!!
        assertArrayEquals(shortArrayOf(1, 2, 3, 4, 5, 6), pcm)
        assertTrue(audio.released)
        assertEquals(listOf(0L), audio.seeks)
        assertEquals(listOf(9, 9), dups) // one dup for the video reader, one for the audio
    }

    @Test
    fun a_failed_open_reports_through_the_error_accessors() {
        val format = EncodedVideoFormat("video/hevc", 4, 2, 0, color)
        val failing = object : EncodedTrackReader {
            override fun open(fd: Int) = EncodedOpenOutcome.Failed(3, "boom")
            override fun seekTo(ptsUs: Long) {}
            override fun readSample(): EncodedSample? = null
            override fun release() {}
        }
        val s = EncodedMp4Session(newSource = { EncodedSource(failing, Decoder(), Developer()) }, dupFd = { it })
        assertNull(s.open(1))
        assertNotNull(s.detail())
        assertEquals(Mp4ExportErrors.CODE_IO, s.code())
        assertEquals(format.mime, "video/hevc")
    }

    @Test
    fun render_on_an_unknown_handle_fails_cleanly() {
        val s = session(Developer())
        assertNull(s.renderFrame(42, 0, GradeK(), null, null))
        assertTrue(s.detail()!!.contains("no open"))
        assertEquals(-1L, s.framePts(42, 0))
    }

    // ---- E0b-5: the GPU -> encoder-surface door ----

    private class FakeGpu(var available: Boolean = true, var drawOk: Boolean = true, var attachOk: Boolean = true) :
        com.ritim.ritimcraw.engine.EncodedGpuDeveloper {
        val draws = ArrayList<Triple<Int, Long, Int>>() // width, ptsUs, rotation
        var attached: Any? = null
        override fun available() = available
        override fun develop(frame: YuvFrameK, color: EncodedColorSpec, grade: GradeK, reduce: Int, lutPath: String?, chainWire: FloatArray?): IntArray? = null
        override fun attachEncoderSurface(surface: Any?): Boolean {
            if (attachOk) attached = surface
            return attachOk
        }
        override fun developToEncoder(
            frame: YuvFrameK, color: EncodedColorSpec, grade: GradeK, lutPath: String?, chainWire: FloatArray?,
            ptsUs: Long, rotationDeg: Int,
        ): Boolean {
            draws.add(Triple(frame.width, ptsUs, rotationDeg))
            return drawOk
        }
        override fun close() {}
    }

    private fun gpuSession(
        gpu: FakeGpu, w: Int = 4, h: Int = 2, heap: Long = 256L * 1024 * 1024, dev: Developer = Developer(),
        logs: MutableList<String> = ArrayList(),
    ): EncodedMp4Session {
        val format = EncodedVideoFormat("video/hevc", w, h, 0, color)
        return EncodedMp4Session(
            newSource = { EncodedSource(Reader(3, format), Decoder(nv12 = true), dev, gpu) },
            dupFd = { it },
            log = { logs.add(it) },
            maxTextureSize = { 16384 },
            heapLimitBytes = { heap },
        )
    }

    @Test
    fun surface_capable_is_decided_by_the_planner_and_logged() {
        val logs = ArrayList<String>()
        val s = gpuSession(FakeGpu(), w = 7680, h = 4320, logs = logs)
        val h = s.open(1)!!.handle
        assertTrue(s.encoderSurfaceCapable(h, GradeK(), null, true))
        assertTrue(logs.any { it.contains("encoded export path: surface") && it.contains("gpu budget 2183 MiB") })
        // a scaled export is not a 1:1 blit
        assertFalse(s.encoderSurfaceCapable(h, GradeK(), null, false))
    }

    @Test
    fun no_gpu_door_means_no_surface() {
        val s = gpuSession(FakeGpu(available = false))
        val h = s.open(1)!!.handle
        assertFalse(s.encoderSurfaceCapable(h, GradeK(), null, true))
    }

    @Test
    fun frames_go_to_the_encoder_with_pts_and_rotation_and_never_touch_the_cpu_developer() {
        val gpu = FakeGpu()
        val dev = Developer()
        val s = gpuSession(gpu, dev = dev)
        val h = s.open(1)!!.handle
        val surface = Any()
        assertTrue(s.attachEncoderSurface(h, surface))
        assertEquals(surface, gpu.attached)
        assertTrue(s.renderFrameToEncoder(h, 1, 33_333L, GradeK(), null, null, 90))
        assertEquals(listOf(Triple(4, 33_333L, 90)), gpu.draws)
        assertEquals(0, dev.colors.size) // no CPU develop, no RGB frame
    }

    @Test
    fun a_refused_surface_frame_reports_why_and_keeps_the_decoded_frame_for_the_fallback() {
        val gpu = FakeGpu(drawOk = false)
        val dev = Developer()
        val s = gpuSession(gpu, dev = dev)
        val h = s.open(1)!!.handle
        assertFalse(s.renderFrameToEncoder(h, 0, 0L, GradeK(), null, null, 0))
        assertTrue(s.detail()!!.contains("refused frame 0"))
        // the fallback RGB path then develops the frame (decoded once, pending planes reused)
        val rgb = s.renderFrame(h, 0, GradeK(), null, null)
        assertEquals(4 * 2 * 3, rgb!!.size)
    }

    @Test
    fun the_cpu_fallback_refuses_an_8k_frame_with_a_short_message_before_allocating_anything() {
        val gpu = FakeGpu(available = false)
        val dev = Developer()
        val s = gpuSession(gpu, w = 7680, h = 4320, dev = dev)
        val h = s.open(1)!!.handle
        assertNull(s.renderFrame(h, 0, GradeK(), null, null))
        val d = s.detail()!!
        assertTrue(d.contains("7680x4320"))
        assertTrue(d.length < 250)
        assertEquals(0, dev.colors.size) // the oracle never ran
    }

    @Test
    fun a_refused_attach_is_reported() {
        val s = gpuSession(FakeGpu(attachOk = false))
        val h = s.open(1)!!.handle
        assertFalse(s.attachEncoderSurface(h, Any()))
        assertTrue(s.detail()!!.contains("refused the input surface"))
    }
}
