package com.ritim.ritimcraw.export

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES30
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * E1d device acceptance (no UI, no user data): encodes a smooth horizontal
 * ramp through a HEVC Main10 encoder from an EGL window surface on its input
 * Surface, once on a QUERIED RGBA1010102 config and once on RGBA8888, into
 * the app's cache dir. The host then runs ffprobe (pix_fmt must be
 * yuv420p10le) and counts the distinct luma codes along a row: a true 10-bit
 * surface gives roughly 4x the codes of the same ramp rendered through an
 * 8-bit surface, which is what separates 10-bit from 8-bit-in-a-10-bit-file.
 *
 * It also logs (tag ritim-tenbit) the plan the planner makes against the
 * device's REAL encoder facts, the EGL config the query found, and whether a
 * pbuffer-capable variant exists (what the native session's probe asks).
 */
@RunWith(AndroidJUnit4::class)
class TenBitSurfaceRampTest {

    private val tag = "ritim-tenbit"
    private val w = 1280
    private val h = 720

    @Test
    fun ramp_through_a_10_bit_and_an_8_bit_surface() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val facts = DeviceEncoderFacts.all()
        val plan = ExportPlanner.resolve(
            ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10),
            ExportInput(w, h, 30.0), facts, ExportContext(tenBitPathAvailable = true),
        )
        Log.i(tag, "plan: " + EncoderDiagnostics.planLine("device", plan))
        plan.issues.forEach { Log.i(tag, EncoderDiagnostics.issueLine(it)) }
        assertTrue("the planner must pick a 10-bit HEVC encoder on this device", plan.ok && plan.bitDepth == 10)

        for (bits in listOf(10, 8)) {
            val out = File(ctx.cacheDir, "ramp_${bits}bit_surface.mp4")
            out.delete()
            encode(plan, bits, out)
            Log.i(tag, "wrote ${out.absolutePath} ${out.length()} bytes")
            assertTrue(out.length() > 0)
        }
    }

    /**
     * The NATIVE side of the 10-bit path, without a clip: the probe, a session
     * opened on the RGBA1010102 config, and that session accepting a HEVC
     * Main10 encoder's input Surface (the window surface on the 10-bit
     * config). No frame is rendered: that needs a clip, which belongs to a
     * real export.
     */
    @Test
    fun the_native_session_opens_on_the_10_bit_config_and_takes_the_encoder_surface() {
        val gpu = NativeGpuDevelop
        val probe = gpu.tenBitProbe()
        Log.i(tag, "native tenBitProbe=$probe detail=${gpu.lastDetail()}")
        assertTrue("the device must offer an RGBA1010102 window+pbuffer+recordable config", probe)
        val handle = gpu.openSessionTenBit()
        Log.i(tag, "native openSessionTenBit handle=$handle detail=${gpu.lastDetail()}")
        assertTrue("the 10-bit develop session must open", handle != 0L)
        try {
            val facts = DeviceEncoderFacts.all()
            val plan = ExportPlanner.resolve(
                ExportSettings(codec = ExportCodec.HEVC, bitDepth = 10),
                ExportInput(w, h, 30.0), facts, ExportContext(tenBitPathAvailable = true),
            )
            val spec = ExportFormats.video(plan, surface = true)
            val format = MediaFormat.createVideoFormat(spec.mime, spec.width, spec.height)
            for ((k, v) in spec.values) {
                when (v) {
                    is Int -> format.setInteger(k, v)
                    is Float -> format.setFloat(k, v)
                }
            }
            val codec = MediaCodec.createByCodecName(plan.encoderName!!)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = codec.createInputSurface()
            codec.start()
            val attached = gpu.setEncoderSurface(handle, surface)
            Log.i(tag, "native setEncoderSurface(10-bit session) attached=$attached detail=${gpu.lastDetail()}")
            gpu.setEncoderSurface(handle, null)
            codec.stop(); codec.release(); surface.release()
            assertTrue("the 10-bit session must take the Main10 encoder's input Surface", attached)
        } finally {
            gpu.closeSession(handle)
        }
    }

    private fun encode(plan: ExportPlan, surfaceBits: Int, out: File) {
        // Near-lossless on purpose (high bitrate, every frame intra): a lossy encode
        // smooths 8-bit steps and hides the difference this test is looking for.
        val hi = plan.copy(bitrateBps = 150_000_000, keyframeIntervalSec = 0f)
        val spec = ExportFormats.video(hi, surface = true)
        val format = MediaFormat.createVideoFormat(spec.mime, spec.width, spec.height)
        for ((k, v) in spec.values) {
            when (v) {
                is Int -> format.setInteger(k, v)
                is Float -> format.setFloat(k, v)
            }
        }
        val codec = if (plan.encoderName != null) {
            MediaCodec.createByCodecName(plan.encoderName)
        } else {
            MediaCodec.createEncoderByType(plan.mime)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()

        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1))
        val rgb = if (surfaceBits == 10) 10 else 8
        val alpha = if (surfaceBits == 10) 2 else 8
        fun attribs(extraSurface: Int) = intArrayOf(
            EGL14.EGL_RED_SIZE, rgb, EGL14.EGL_GREEN_SIZE, rgb, EGL14.EGL_BLUE_SIZE, rgb,
            EGL14.EGL_ALPHA_SIZE, alpha,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or extraSurface,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        // Query, never assume: the first candidate whose REPORTED sizes are exact.
        fun pick(extraSurface: Int): EGLConfig? {
            val cands = arrayOfNulls<EGLConfig>(64)
            val n = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs(extraSurface), 0, cands, 0, 64, n, 0)) return null
            val v = IntArray(1)
            fun q(c: EGLConfig, a: Int): Int { EGL14.eglGetConfigAttrib(display, c, a, v, 0); return v[0] }
            for (i in 0 until n[0]) {
                val c = cands[i] ?: continue
                if (q(c, EGL14.EGL_RED_SIZE) == rgb && q(c, EGL14.EGL_GREEN_SIZE) == rgb &&
                    q(c, EGL14.EGL_BLUE_SIZE) == rgb && q(c, EGL14.EGL_ALPHA_SIZE) == alpha
                ) return c
            }
            return null
        }
        val withPbuffer = pick(EGL14.EGL_PBUFFER_BIT)
        Log.i(tag, "surfaceBits=$surfaceBits window+pbuffer+recordable config: ${withPbuffer != null}")
        val config = withPbuffer ?: pick(0) ?: error("no ${surfaceBits}-bit window+recordable config")
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        val window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, window, window, context))

        val vs = "#version 300 es\nvoid main(){ vec2 p = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1)); gl_Position = vec4(p*2.0-1.0, 0.0, 1.0); }"
        // A smooth ramp, display-encoded already (no OETF here, the way the develop tail hands the blit its values).
        val fs = "#version 300 es\nprecision highp float; out vec4 o; void main(){ float v = (gl_FragCoord.x - 0.5) / ${w - 1}.0; o = vec4(v, v, v, 1.0); }"
        fun sh(t: Int, src: String): Int {
            val s = GLES30.glCreateShader(t); GLES30.glShaderSource(s, src); GLES30.glCompileShader(s); return s
        }
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, sh(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(prog, sh(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(prog)
        GLES30.glUseProgram(prog)
        GLES30.glViewport(0, 0, w, h)

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        val info = MediaCodec.BufferInfo()
        fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 100_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) {
                            muxer.writeSampleData(track, buf, info)
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(i, false)
                        if (eos) return
                    }
                }
            }
        }
        codec.start()
        for (f in 0 until 12) {
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, window, f * 33_333_333L)
            EGL14.eglSwapBuffers(display, window)
            drain(false)
        }
        codec.signalEndOfInputStream()
        drain(true)
        muxer.stop(); muxer.release()
        codec.stop(); codec.release()
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, window)
        EGL14.eglDestroyContext(display, context)
        surface.release()
    }
}
