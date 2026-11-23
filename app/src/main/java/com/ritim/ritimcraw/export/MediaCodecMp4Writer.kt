package com.ritim.ritimcraw.export

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.util.Log
import android.view.Surface
import com.ritim.ritimcraw.engine.GlesShaderSources
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The real [Mp4MediaWriter]: MediaCodec H.264 (video/avc) + AAC
 * (audio/mp4a-latm) into a MediaMuxer MP4. This is the ONLY Android
 * framework class in the MP4 export path — MediaCodec/MediaMuxer cannot
 * run in JVM unit tests (framework stubs throw), so every decision around
 * it lives in the JVM-tested pipeline, and this class stays a thin,
 * device-gated adapter.
 *
 * Encoder choices (sprint plan Task 5, SDR Rec.709 reference export):
 *   video/avc, COLOR_FormatYUV420Flexible via the Image API (plane strides
 *   are the codec's, not ours), ~20 Mbps at 4K (w*h*2.4 bits/s, clamped),
 *   1 s i-frame interval, profile High, SDR Rec.709 + limited range tags;
 *   audio/mp4a-latm AAC-LC at the clip's own rate/channels, 128 kbps.
 *
 * Track setup follows the MediaMuxer contract: tracks are added from each
 * encoder's INFO_OUTPUT_FORMAT_CHANGED (never the input format), the muxer
 * starts only after every expected track is added, and samples produced
 * before that moment are held in [pending] and flushed on start — so no
 * encoded packet is ever dropped or written before the muxer is ready.
 *
 * Encoder-Surface input mode (GPU deliverable F, owner request 2026-11-12
 * §10): an EXPLICIT request — requestEncoderSurfaceInput, before start,
 * never auto — switches the video encoder to COLOR_FormatSurface +
 * createInputSurface + an EGL window surface on it; frames then arrive as
 * RGB888 through writeVideoFrameRgb (texture upload + fullscreen blit +
 * eglPresentationTimeANDROID of the frame's own normalized pts + swap).
 * The §10 order is honored: the EGL display/config/context fail FIRST
 * (before any codec is created, throwing the JVM-pure
 * SurfaceInputUnavailable the exporter turns into a clean whole-export CPU
 * restart), then configure WITHOUT start, then createInputSurface, the
 * window surface, the context binding, the blit program, and codec.start
 * LAST. The CPU path stays byte-identical whenever the mode is not
 * requested.
 */
internal class MediaCodecMp4Writer : Mp4MediaWriter {

    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var muxer: MediaMuxer? = null

    private var width = 0
    private var height = 0

    /** E1b: the resolved plan ([configureVideo]); null = a pre-E1 job (the legacy configuration). */
    private var plan: ExportPlan? = null

    /** E1b: [expectVideoConfig] was called, so start() needs [plan]. */
    private var planExpected = false

    private val videoMime: String get() = plan?.mime ?: MIME_AVC

    /** The real created video encoder's identity (set by [logEncoderIdentity]). */
    private var identity: EncoderDiagnostics? = null

    private var sampleRate = 0
    private var channels = 0

    private var videoTrack = -1
    private var audioTrack = -1
    private var videoTrackAdded = false
    private var audioTrackAdded = false
    private var muxerStarted = false

    /** Encoded packets that arrived before the muxer could start. */
    private val pending = ArrayList<PendingSample>()

    /**
     * Per-export stage-timing sink (owner request 2026-11-13): attached by
     * the pipeline right before the encode loop, same thread. Null (the
     * default) = timing off — writeVideoFrame then runs its two extra
     * System.nanoTime reads per frame and nothing else changes.
     */
    private var timing: ExportStageTiming? = null

    override fun attachTiming(timing: ExportStageTiming?) {
        this.timing = timing
    }

    // ---- encoder-Surface input mode (GPU deliverable F, §10) ----
    // All false/null/0 until an explicit requestEncoderSurfaceInput BEFORE
    // start and a successful start; release() tears them down in reverse
    // order and is safe at every partial-init point (§11 idempotent).

    /** True once requestEncoderSurfaceInput was called (before start). */
    private var surfaceInputRequested = false

    /**
     * True once requestSessionEncoderSurface was called (before start) —
     * the M5 session-owns-EGL mode: the codec gets its input Surface but
     * the WRAPPER does not wrap it (the GPU develop session is the only
     * producer — the single-producer BufferQueue rule); the surface is
     * handed out unwrapped through [encoderInputSurface] and the writer
     * only drains the codec's output per frame. The writer's own EGL
     * (window surface + blit program) comes up ONLY through
     * [adoptEncoderSurface] — the fallback the pipeline takes when the
     * session refuses the surface.
     */
    private var sessionSurfaceMode = false

    /** True once start() brought the whole Surface path up. */
    private var surfaceInputActive = false

    private var eglDisplay: EGLDisplay? = null
    private var eglConfig: EGLConfig? = null
    private var eglContext: EGLContext? = null
    private var eglWindowSurface: EGLSurface? = null

    /** The codec's input Surface (Surface mode only; null otherwise). */
    private var inputSurface: Surface? = null

    /** The blit program, its uniform locations and the frame texture (0/-1 = none). */
    private var blitProgram = 0
    private var blitTexture = 0
    private var blitSrcLocation = -1
    private var blitSizeLocation = -1

    private class PendingSample(
        val track: Int,
        val bytes: ByteArray,
        val info: MediaCodec.BufferInfo,
    )

    override fun start(
        outPath: String,
        width: Int,
        height: Int,
        orientationDeg: Int,
        sampleRate: Int,
        channels: Int,
    ) {
        this.width = width
        this.height = height
        this.sampleRate = sampleRate
        this.channels = channels
        if (planExpected && plan == null) {
            // A settings export reached start() without configureVideo: a
            // wiring bug. Debug builds fail loudly; release logs and runs
            // the legacy configuration instead of dying on the user.
            val msg = "export carries settings but configureVideo was not called before start"
            if (com.ritim.ritimcraw.BuildConfig.DEBUG) throw IllegalStateException(msg)
            Log.e(TAG, msg + "; using the legacy configuration")
        }
        val p = plan ?: ExportPlan.legacy(width, height)
        if (p.width != width || p.height != height) {
            throw IllegalStateException(
                "the export plan is for ${p.width}x${p.height} but start got ${width}x$height"
            )
        }
        plan = p

        // Encoder-Surface mode (§10 order): the EGL display/config/context
        // come up FIRST — before the muxer and before any codec is created.
        // A no-EGL device fails with SurfaceInputUnavailable here and the
        // exporter turns that into a clean whole-export CPU restart. With
        // no request (the CPU default) this is a no-op: the path below is
        // byte-identical to what it always was. The M5 session-owns-EGL
        // mode needs the SAME display/config/context (adoptEncoderSurface
        // wraps the surface in the writer's own EGL when the session
        // refuses it), so it rides the same setup.
        if (surfaceInputRequested || sessionSurfaceMode) {
            setupEgl()
        }

        try {
            muxer = MediaMuxer(outPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            (muxer as MediaMuxer).setOrientationHint(orientationDeg)
        } catch (e: Exception) {
            release()
            throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_MUX,
                    "the muxer could not open $outPath",
                    "check free space and retry",
                    e.message,
                )
            )
        }

        // §10: the Surface codec is configured WITHOUT start, gets its
        // input Surface, the EGL window surface, the context binding and
        // the blit program — and only then starts. The CPU codec keeps
        // the existing configure+start pair (configure() below) exactly
        // as it was. The M5 session mode stops one step earlier: the
        // codec gets its input Surface and starts, but NO EGL wrap —
        // the surface is handed out unwrapped for the GPU develop
        // session to produce into.
        videoCodec = if (surfaceInputRequested) {
            configureSurfaceVideoCodec(width, height)
        } else if (sessionSurfaceMode) {
            configureSessionSurfaceCodec(width, height)
        } else {
            configure(p.mime, videoMediaFormat(p, surface = false), p)
        }
        if (sampleRate > 0 && channels > 0) {
            audioCodec = configure(MIME_AAC, audioFormat(sampleRate, channels, p.audioKbps))
        }
    }

    override fun configureVideo(plan: ExportPlan) {
        if (videoCodec != null || muxer != null) {
            throw encodeFailure("configureVideo must be called before start")
        }
        this.plan = plan
    }

    override fun expectVideoConfig() {
        planExpected = true
    }

    override fun encoderIdentity(): EncoderDiagnostics? = identity

    /** The codec the plan names (by name), or the MIME's default when the plan has no encoder facts. */
    private fun createVideoEncoder(p: ExportPlan): MediaCodec {
        val name = p.encoderName
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(p.mime)
    }

    private fun videoMediaFormat(p: ExportPlan, surface: Boolean): MediaFormat =
        toMediaFormat(ExportFormats.video(p, surface))

    /**
     * The explicit encoder-Surface input request (GPU deliverable F,
     * §10): call BEFORE start — never auto, never mid-export. After it,
     * start() brings the EGL display/config/context up first
     * (fail-fast SurfaceInputUnavailable), configures the video encoder
     * with COLOR_FormatSurface WITHOUT starting it, creates its input
     * Surface, binds an EGL window surface on it, builds the blit
     * program, and starts the codec LAST. A call after start (or a
     * second call) is a mode-confusion bug and throws.
     */
    override fun requestEncoderSurfaceInput() {
        if (videoCodec != null || muxer != null) {
            throw encodeFailure(
                "encoder-Surface input must be requested before start"
            )
        }
        surfaceInputRequested = true
    }

    override fun surfaceInputActive(): Boolean = surfaceInputActive

    /**
     * Every Surface-mode bring-up failure, as the one type the exporter
     * catches for its clean whole-export CPU restart — plain
     * IllegalStateExceptions from start() fail the job honestly instead.
     *
     * [step] names the bring-up step that failed (the closed
     * [SurfaceInputStep] vocabulary): the exporter's fallback line appends
     * its token to the reason (owner bug report 2026-11-13 — the line read
     * only the category and the diagnosis could not tell the Surface
     * creation from the EGL wrap from the recordable query). No default:
     * every call site states its step, or states null explicitly (the one
     * mode-confusion guard that is a code bug, not a device step — the
     * fallback reason then keeps the exact legacy string).
     */
    private fun surfaceInputUnavailable(
        cause: String,
        step: SurfaceInputStep?,
    ): SurfaceInputUnavailable =
        SurfaceInputUnavailable(
            Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_CONFIGURE,
                cause,
                "the export restarts on the CPU path (encoder Surface input " +
                    "is optional — nothing is lost, nothing is half-encoded)",
            ),
            step,
        )

    /**
     * EGL display + recordable RGBA8888 config + GLES 3 context (§10 order
     * step 1) — BEFORE the muxer and BEFORE any codec exists, so a no-EGL
     * device fails before creating anything the CPU restart would have to
     * clean up. RGBA8888 is honest precision for this blit stage: the input
     * is packed RGB888 and the encode target is 8-bit SDR YUV.
     */
    private fun setupEgl() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == null || display == EGL14.EGL_NO_DISPLAY) {
            throw surfaceInputUnavailable("no EGL display is available", SurfaceInputStep.EGL_DISPLAY)
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw surfaceInputUnavailable("the EGL display could not initialize", SurfaceInputStep.EGL_INIT)
        }
        eglDisplay = display

        // §10: EGL_CONFIG_RECORDABLE_ANDROID so the encoder can consume the
        // window surface's buffers.
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] < 1
        ) {
            throw surfaceInputUnavailable("no recordable RGBA8888 EGL config is available", SurfaceInputStep.EGL_CONFIG)
        }
        val config = configs[0]
            ?: throw surfaceInputUnavailable("no recordable RGBA8888 EGL config is available", SurfaceInputStep.EGL_CONFIG)
        eglConfig = config

        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL14.EGL_NONE,
        )
        val context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0,
        )
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw surfaceInputUnavailable("no GLES 3 context could be created", SurfaceInputStep.EGL_CONTEXT)
        }
        eglContext = context
    }

    /**
     * The §10 Surface bring-up, in the owner's verbatim order — NOT the CPU
     * codec's configure+start pair: configure WITHOUT start →
     * createInputSurface → the EGL window surface → the context binding →
     * the blit program → codec.start LAST. The codec is assigned to
     * [videoCodec] the moment it exists so release() (which the pipeline
     * always calls on a failed start) cleans up every partial state — §11
     * idempotent. Every failure is a [SurfaceInputUnavailable]: none of
     * them can happen mid-encode, so the exporter's CPU restart never
     * double-encodes a frame or continues a partial file.
     */
    private fun configureSurfaceVideoCodec(w: Int, h: Int): MediaCodec {
        val p = plan ?: ExportPlan.legacy(w, h)
        val codec = try {
            createVideoEncoder(p)
        } catch (e: Exception) {
            throw surfaceInputUnavailable("no ${p.mime} encoder is available on this device", SurfaceInputStep.CODEC_CREATE)
        }
        videoCodec = codec
        logEncoderIdentity(codec, p.mime, video = true)
        try {
            codec.configure(
                videoMediaFormat(p, surface = true),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
        } catch (e: Exception) {
            throw surfaceInputUnavailable(
                "${p.mime} rejected the ${w}x$h COLOR_FormatSurface format",
                SurfaceInputStep.CODEC_CONFIG,
            )
        }
        val surface = try {
            codec.createInputSurface()
        } catch (e: Exception) {
            throw surfaceInputUnavailable("${p.mime} gave no input Surface", SurfaceInputStep.CREATE_INPUT_SURFACE)
        }
        inputSurface = surface

        val display = eglDisplay
        val config = eglConfig
        val context = eglContext
        if (display == null || config == null || context == null) {
            throw surfaceInputUnavailable("the EGL context is missing (setupEgl must run first)", SurfaceInputStep.EGL_CONTEXT)
        }
        // §10: an EGL window surface ON the codec's input Surface, with NO
        // surface attribs: the recordable flag rides the CONFIG setupEgl
        // chose (it requires EGL_RECORDABLE_ANDROID), and repeating it in
        // the window-surface attribs returned EGL_NO_SURFACE on the
        // Xiaomi 15 Ultra (owner bug report 2026-11-14). The M4 preview
        // session wraps its window with a null attrib list on the same
        // device and works; Grafika does the same.
        val window = EGL14.eglCreateWindowSurface(display, config, surface, null, 0)
        if (window == null || window == EGL14.EGL_NO_SURFACE) {
            throw surfaceInputUnavailable(
                "no EGL window surface could be created on the input Surface",
                SurfaceInputStep.EGL_WRAP,
            )
        }
        eglWindowSurface = window
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            throw surfaceInputUnavailable("the GLES 3 context could not be made current", SurfaceInputStep.EGL_CURRENT)
        }
        // Fail fast at START, not per frame: a device without the
        // presentation-time extension restarts on CPU before any frame
        // exists (the pts stamp is a §10 contract, not a nicety).
        if (!EGLExt.eglPresentationTimeANDROID(display, window, 0L)) {
            throw surfaceInputUnavailable("eglPresentationTimeANDROID is not available", SurfaceInputStep.PRESENTATION_TIME_EXT)
        }
        buildBlitProgram(w, h)
        try {
            codec.start()
        } catch (e: Exception) {
            throw surfaceInputUnavailable("${p.mime} failed to start in Surface-input mode", SurfaceInputStep.CODEC_START)
        }
        surfaceInputActive = true
        return codec
    }

    /**
     * The M5 session-owns-EGL bring-up: the SAME §10 order as
     * [configureSurfaceVideoCodec] but stopping one step earlier — the
     * codec is configured WITHOUT start, gets its input Surface, and
     * starts LAST; the writer does NOT wrap the surface in EGL (the GPU
     * develop session will be the only producer — the single-producer
     * BufferQueue rule: two EGL window surfaces on one Surface's
     * BufferQueue would deadlock). The surface is handed out unwrapped
     * through [encoderInputSurface]; the writer's own EGL wrap comes up
     * only if the pipeline calls [adoptEncoderSurface] (the session
     * refused the surface — the fallback). Every failure is a
     * [SurfaceInputUnavailable]: none can happen mid-encode, so the
     * exporter's CPU restart never double-encodes a frame.
     */
    private fun configureSessionSurfaceCodec(w: Int, h: Int): MediaCodec {
        val p = plan ?: ExportPlan.legacy(w, h)
        val codec = try {
            createVideoEncoder(p)
        } catch (e: Exception) {
            throw surfaceInputUnavailable("no ${p.mime} encoder is available on this device", SurfaceInputStep.CODEC_CREATE)
        }
        videoCodec = codec
        logEncoderIdentity(codec, p.mime, video = true)
        try {
            codec.configure(
                videoMediaFormat(p, surface = true),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE,
            )
        } catch (e: Exception) {
            throw surfaceInputUnavailable(
                "${p.mime} rejected the ${w}x$h COLOR_FormatSurface format",
                SurfaceInputStep.CODEC_CONFIG,
            )
        }
        val surface = try {
            codec.createInputSurface()
        } catch (e: Exception) {
            throw surfaceInputUnavailable("${p.mime} gave no input Surface", SurfaceInputStep.CREATE_INPUT_SURFACE)
        }
        inputSurface = surface
        try {
            codec.start()
        } catch (e: Exception) {
            throw surfaceInputUnavailable("${p.mime} failed to start in Surface-input mode", SurfaceInputStep.CODEC_START)
        }
        return codec
    }

    /**
     * The M5 session-owns-EGL request (see the interface KDoc): call
     * BEFORE start — never auto, never mid-export. After it, start()
     * brings the EGL display/config/context up FIRST (adoptEncoderSurface
     * needs them), configures the video encoder with COLOR_FormatSurface
     * WITHOUT starting it, creates its input Surface, and starts the codec
     * LAST — WITHOUT wrapping the surface in the writer's EGL. A call
     * after start (or a second call) is a mode-confusion bug and throws.
     */
    override fun requestSessionEncoderSurface() {
        if (videoCodec != null || muxer != null) {
            throw encodeFailure(
                "session encoder surface must be requested before start"
            )
        }
        sessionSurfaceMode = true
    }

    /**
     * The codec input Surface [requestSessionEncoderSurface] created,
     * handed out UNWRAPPED (the session wraps it in its OWN EGL on its GL
     * thread). Null when the mode was not requested or the surface was
     * already handed out — the pipeline reads this exactly once, right
     * after the session opens.
     */
    override fun encoderInputSurface(): Any? {
        val surface = inputSurface
        return if (sessionSurfaceMode && surface != null) surface else null
    }

    /**
     * Per-frame codec drain for the session-owns-EGL mode (the
     * Surface-input contract: the producer must dequeue the codec's
     * output buffers or the pipeline stalls). Runs the SAME drain the
     * writer's own Surface mode runs after each swap — the codec does
     * not care WHO produced the frame, only that its output is drained.
     */
    override fun drainEncoderInput() {
        val codec = videoCodec ?: throw notStarted()
        drain(codec, isVideo = true, timeoutUs = 0)
    }

    /**
     * The ADOPT fallback (M5): the GPU develop session refused the
     * surface, so the writer does the steps [configureSessionSurfaceCodec]
     * skipped — the exact §10 steps [configureSurfaceVideoCodec] runs
     * AFTER createInputSurface: wrap the SAME input surface in the
     * writer's own EGL (no surface attribs — the recordable flag rides
     * the config), make it current, probe
     * eglPresentationTimeANDROID, build the blit program. After a
     * successful adopt [surfaceInputActive] is true and frames flow
     * through the EXISTING [writeVideoFrameRgb] offer — the CPU-RGB
     * Surface mode, byte-for-byte the shipped path. Failure throws
     * [SurfaceInputUnavailable]: the exporter's clean whole-export CPU
     * restart, never a mid-encode death.
     */
    override fun adoptEncoderSurface() {
        if (surfaceInputActive) return
        if (!sessionSurfaceMode) {
            throw surfaceInputUnavailable("the writer is not in session encoder-surface mode", null)
        }
        val surface = inputSurface
            ?: throw surfaceInputUnavailable("the codec input Surface is missing", SurfaceInputStep.CREATE_INPUT_SURFACE)
        val display = eglDisplay
        val config = eglConfig
        val context = eglContext
        if (display == null || config == null || context == null) {
            throw surfaceInputUnavailable("the EGL context is missing (setupEgl must run first)", SurfaceInputStep.EGL_CONTEXT)
        }
        // §10: an EGL window surface ON the codec's input Surface, with NO
        // surface attribs — the exact call configureSurfaceVideoCodec makes
        // (the device DISPROVED the recordable-in-attribs form: it returned
        // EGL_NO_SURFACE on the Xiaomi 15 Ultra, owner bug report
        // 2026-11-14; the flag rides the CONFIG setupEgl chose).
        val window = EGL14.eglCreateWindowSurface(display, config, surface, null, 0)
        if (window == null || window == EGL14.EGL_NO_SURFACE) {
            throw surfaceInputUnavailable(
                "no EGL window surface could be created on the input Surface",
                SurfaceInputStep.EGL_WRAP,
            )
        }
        eglWindowSurface = window
        if (!EGL14.eglMakeCurrent(display, window, window, context)) {
            throw surfaceInputUnavailable("the GLES 3 context could not be made current", SurfaceInputStep.EGL_CURRENT)
        }
        // Fail fast at adopt, not per frame: a device without the
        // presentation-time extension restarts on CPU before any frame
        // exists (the pts stamp is a §10 contract, not a nicety).
        if (!EGLExt.eglPresentationTimeANDROID(display, window, 0L)) {
            throw surfaceInputUnavailable("eglPresentationTimeANDROID is not available", SurfaceInputStep.PRESENTATION_TIME_EXT)
        }
        buildBlitProgram(width, height)
        surfaceInputActive = true
    }

    /**
     * The blit program (GlesShaderSources.FULLSCREEN_VERTEX + BLIT_FRAGMENT
     * — the same host-validated text the parity harness uses; the device
     * compiles the exact same strings) plus the frame texture object. Runs
     * on the EGL context made current just above; the writer is confined to
     * the export thread, so the GL state set here (viewport, unpack
     * alignment 1 for RGB rows) holds for the whole export.
     */
    private fun buildBlitProgram(w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        // RGB rows are w*3 bytes — the default unpack alignment of 4 would
        // misread any row whose length is not a multiple of 4.
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, GlesShaderSources.FULLSCREEN_VERTEX)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, GlesShaderSources.BLIT_FRAGMENT)
        val program = GLES30.glCreateProgram()
        if (program == 0) {
            throw surfaceInputUnavailable("the blit program could not be created", SurfaceInputStep.BLIT_PROGRAM)
        }
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        val linked = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES30.GL_TRUE) {
            val info = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw surfaceInputUnavailable("the blit program failed to link: $info", SurfaceInputStep.BLIT_PROGRAM)
        }
        blitProgram = program
        blitSrcLocation = GLES30.glGetUniformLocation(program, "uSrc")
        blitSizeLocation = GLES30.glGetUniformLocation(program, "uSize")
        if (blitSrcLocation < 0 || blitSizeLocation < 0) {
            throw surfaceInputUnavailable("the blit program lost a uniform (uSrc/uSize)", SurfaceInputStep.BLIT_PROGRAM)
        }
        val textures = IntArray(1)
        GLES30.glGenTextures(1, textures, 0)
        blitTexture = textures[0]
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blitTexture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        if (shader == 0) {
            throw surfaceInputUnavailable("a blit shader object could not be created", SurfaceInputStep.BLIT_SHADER)
        }
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES30.GL_TRUE) {
            val info = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw surfaceInputUnavailable("a blit shader failed to compile: $info", SurfaceInputStep.BLIT_SHADER)
        }
        return shader
    }

    /**
     * Configures and starts one encoder. A rejected format surfaces as an
     * IllegalStateException naming the stage ("configure") and the codec —
     * the rejected-encoder contract of the pipeline.
     *
     * Once the codec is created, the REAL encoder identity is recorded and
     * logged (owner request 2026-11-12 §4): name, canonical name, the
     * vendor's declared hardware/software flags, plus the requested MIME —
     * requested vs actually-created never conflated (see
     * [EncoderDiagnostics]). The video encoder's line is what the owner
     * greps to answer "which encoder really ran".
     */
    private fun configure(type: String, format: MediaFormat, videoPlan: ExportPlan? = null): MediaCodec {
        val codec = try {
            if (videoPlan != null) createVideoEncoder(videoPlan) else MediaCodec.createEncoderByType(type)
        } catch (e: Exception) {
            throw rejected(
                videoPlan != null,
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_CONFIGURE,
                    if (videoPlan?.encoderName != null) {
                        "the $type encoder ${videoPlan.encoderName} could not be created"
                    } else {
                        "no $type encoder is available on this device"
                    },
                    "retry the export on a device with a $type encoder",
                    e.message,
                )
            )
        }
        logEncoderIdentity(codec, type, video = videoPlan != null)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw rejected(
                videoPlan != null,
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_CONFIGURE,
                    "$type rejected the ${format.getInteger(MediaFormat.KEY_WIDTH)}x" +
                        "${format.getInteger(MediaFormat.KEY_HEIGHT)} format",
                    "retry the export (the clip may exceed this device's encoder limits)",
                    e.message,
                )
            )
        }
        return codec
    }

    /** A video-encoder refusal is the typed trigger of the one plan-fix retry; audio stays a plain failure. */
    private fun rejected(video: Boolean, message: String): IllegalStateException =
        if (video) VideoConfigRejected(message) else IllegalStateException(message)

    /**
     * Records and logs the created encoder's real identity (owner request
     * 2026-11-12 §4): the vendor's codecInfo name, the canonical name, the
     * declared hardware/software flags — the actually-created encoder, not
     * the MediaCodecList probe. Best-effort: an identity read failure never
     * fails the export (the encode itself is the contract; the log line is
     * diagnostics). minSdk 34 makes the API-29 fields readable everywhere
     * this app runs.
     */
    private fun logEncoderIdentity(codec: MediaCodec, requestedMime: String, video: Boolean = false) {
        try {
            val info = codec.codecInfo
            val record = EncoderDiagnostics(
                name = codec.name,
                canonicalName = codec.canonicalName,
                hardwareAccelerated = info.isHardwareAccelerated,
                softwareOnly = info.isSoftwareOnly,
            )
            if (video) identity = record
            Log.i(TAG, EncoderDiagnostics.logLine(record, requestedMime))
        } catch (e: Exception) {
            // Identity is diagnostics, not the contract: log the miss and
            // keep encoding (an export must not fail because a vendor
            // returned an odd codecInfo).
            Log.w(TAG, "encoder identity unavailable: ${e.message}")
        }
    }

    /** Copies a [FormatSpec] (the pure format decision) into a real MediaFormat. */
    private fun toMediaFormat(spec: FormatSpec): MediaFormat {
        val f = if (spec.width > 0) {
            MediaFormat.createVideoFormat(spec.mime, spec.width, spec.height)
        } else {
            MediaFormat.createAudioFormat(
                spec.mime,
                spec.values.getValue(MediaFormat.KEY_SAMPLE_RATE).toInt(),
                spec.values.getValue(MediaFormat.KEY_CHANNEL_COUNT).toInt(),
            )
        }
        for ((k, v) in spec.values) {
            when (v) {
                is Int -> f.setInteger(k, v)
                is Float -> f.setFloat(k, v)
                else -> throw IllegalStateException("format key $k has an unsupported value type")
            }
        }
        return f
    }

    private fun audioFormat(rate: Int, ch: Int, kbps: Int): MediaFormat =
        toMediaFormat(ExportFormats.audio(rate, ch, kbps))

    override fun writeVideoFrame(ptsUs: Long, i420: ByteArray) {
        val codec = videoCodec ?: throw notStarted()
        if (surfaceInputActive || sessionSurfaceMode) {
            throw encodeFailure(
                "the writer is in encoder-Surface mode; packed I420 frames are not accepted"
            )
        }
        val timing = this.timing
        val tWait0 = System.nanoTime()
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            throw encodeFailure("$videoMime returned no input buffer")
        }
        val tCopy0 = System.nanoTime()
        try {
            val image = codec.getInputImage(index)
                ?: throw encodeFailure("$videoMime gave no input Image for this frame")
            copyI420ToImage(i420, image, width, height)
            // The size must carry the frame's filled byte count computed from
            // the same plane strides the copy just used — the codec does NOT
            // infer it from the Image (CTS EncodeDecodeTest pattern; see
            // imageInputSize). Size 0 tells the encoder the buffer is empty.
            val size = imageInputSize(
                width = width,
                height = height,
                yRowStride = image.planes[0].rowStride,
                uRowStride = image.planes[1].rowStride,
                uPixelStride = image.planes[1].pixelStride,
                vRowStride = image.planes[2].rowStride,
                vPixelStride = image.planes[2].pixelStride,
            )
            codec.queueInputBuffer(index, 0, size, ptsUs, 0)
        } catch (e: IllegalStateException) {
            throw e
        }
        val tDrain0 = System.nanoTime()
        drain(codec, isVideo = true, timeoutUs = 0)
        // Stage timing (owner request 2026-11-13): the three writer
        // internals of this frame, reported into the pipeline's per-frame
        // slots (same thread — this call runs inside the pipeline's frame
        // iteration, before its endFrame reads the slots).
        timing?.markWriterStages(
            encWaitNanos = tCopy0 - tWait0,
            planeCopyNanos = tDrain0 - tCopy0,
            drainNanos = System.nanoTime() - tDrain0,
        )
    }

    /**
     * Per-frame Surface-mode path (§10). The real writer never returns
     * false: the pipeline only calls this after requestEncoderSurfaceInput,
     * and a start that could not bring the mode up threw
     * [SurfaceInputUnavailable] before the first frame — so reaching this
     * method in CPU mode is a mode-confusion bug and throws. A mid-stream
     * GL/swap failure is a plain encode failure (the partial is discarded;
     * the restart contract covers writer START only, never mid-encode).
     *
     * The frame arrives as top-row-first RGB888; the Y flip to the
     * encoder's top-first Surface consumption lives in BLIT_FRAGMENT
     * (texelFetch integer coordinates — exact, no half-pixel math).
     */
    override fun writeVideoFrameRgb(ptsUs: Long, rgb: ByteArray, w: Int, h: Int): Boolean {
        if (!surfaceInputActive) {
            throw encodeFailure(
                "writeVideoFrameRgb needs the encoder-Surface mode " +
                    "(requestEncoderSurfaceInput before start)"
            )
        }
        val codec = videoCodec ?: throw notStarted()
        if (w != width || h != height) {
            throw encodeFailure(
                "frame ${w}x$h does not match the encoder surface ${width}x$height"
            )
        }
        if (rgb.size != w * h * 3) {
            throw encodeFailure(
                "RGB888 frame must hold ${w * h * 3} bytes, got ${rgb.size}"
            )
        }
        val timing = this.timing
        val tUpload0 = System.nanoTime()
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, blitTexture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGB8, w, h, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(rgb),
        )
        GLES30.glUseProgram(blitProgram)
        GLES30.glUniform1i(blitSrcLocation, 0)
        GLES30.glUniform2i(blitSizeLocation, w, h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        // The frame's OWN normalized pts — never the wall clock (§10);
        // PtsTimeline.ptsNs is overflow-checked and rejects negatives.
        if (!EGLExt.eglPresentationTimeANDROID(
                eglDisplay, eglWindowSurface, PtsTimeline.ptsNs(ptsUs),
            )
        ) {
            throw encodeFailure("the Surface pts could not be set")
        }
        if (!EGL14.eglSwapBuffers(eglDisplay, eglWindowSurface)) {
            throw encodeFailure("the encoder Surface swap failed")
        }
        val tDrain0 = System.nanoTime()
        drain(codec, isVideo = true, timeoutUs = 0)
        // Stage timing, honestly mapped: Surface input has NO input-buffer
        // wait (enc_wait = 0 — there is no dequeueInputBuffer); the upload +
        // blit + swap span is the plane work; the drain is the drain.
        timing?.markWriterStages(
            encWaitNanos = 0,
            planeCopyNanos = tDrain0 - tUpload0,
            drainNanos = System.nanoTime() - tDrain0,
        )
        return true
    }

    // ---- direct-to-plane path (commit (c)) ----
    //
    // The pipeline asks for the encoder input Image's plane buffers BEFORE
    // the conversion; the native twin then writes them directly (one pass,
    // no packed intermediate, no second copy). The writer holds the
    // dequeued input buffer between acquire and queue/abandon — the same
    // buffer index writeVideoFrame would have used, so the packed fallback
    // (acquire -> refuse -> abandon -> writeVideoFrame) re-dequeues a
    // fresh buffer and stays byte-identical to the pre-(c) stream.

    /** The dequeued input buffer awaiting its frame (null = none held). */
    private var heldInputIndex: Int = -1

    /** The Image of [heldInputIndex] (kept for the size computation at queue). */
    private var heldImage: Image? = null

    /**
     * How long the acquire for the held buffer waited for the codec to
     * free an input buffer (reported as enc_wait at queue time).
     */
    private var heldAcquireWaitNanos: Long = 0

    override fun acquireVideoFramePlanes(): YuvFramePlanes? {
        val codec = videoCodec ?: throw notStarted()
        if (surfaceInputActive || sessionSurfaceMode) {
            throw encodeFailure(
                "the writer is in encoder-Surface mode; plane acquire is not available"
            )
        }
        if (heldInputIndex >= 0) {
            throw encodeFailure("acquireVideoFramePlanes called twice without queue/abandon")
        }
        val tWait0 = System.nanoTime()
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            throw encodeFailure("$videoMime returned no input buffer")
        }
        val image = codec.getInputImage(index)
            ?: throw encodeFailure("$videoMime gave no input Image for this frame")
        heldInputIndex = index
        heldImage = image
        heldAcquireWaitNanos = System.nanoTime() - tWait0
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        return YuvFramePlanes(
            y = y.buffer,
            yStride = y.rowStride,
            u = u.buffer,
            uStride = u.rowStride,
            uPixelStride = u.pixelStride,
            v = v.buffer,
            vStride = v.rowStride,
            vPixelStride = v.pixelStride,
        )
    }

    override fun queueVideoFrame(ptsUs: Long) {
        val codec = videoCodec ?: throw notStarted()
        if (surfaceInputActive || sessionSurfaceMode) {
            throw encodeFailure(
                "the writer is in encoder-Surface mode; plane queue is not available"
            )
        }
        val index = heldInputIndex
        val image = heldImage
        if (index < 0 || image == null) {
            throw encodeFailure("queueVideoFrame called without a held input buffer")
        }
        heldInputIndex = -1
        heldImage = null
        // The size must carry the frame's filled byte count computed from
        // the same plane strides the conversion just honored — the codec
        // does NOT infer it from the Image (imageInputSize).
        val size = imageInputSize(
            width = width,
            height = height,
            yRowStride = image.planes[0].rowStride,
            uRowStride = image.planes[1].rowStride,
            uPixelStride = image.planes[1].pixelStride,
            vRowStride = image.planes[2].rowStride,
            vPixelStride = image.planes[2].pixelStride,
        )
        codec.queueInputBuffer(index, 0, size, ptsUs, 0)
        val tDrain0 = System.nanoTime()
        drain(codec, isVideo = true, timeoutUs = 0)
        // Stage timing: the acquire wait lands in enc_wait; the conversion's
        // own cost lives in the pipeline's yuv slot (it ran before queue,
        // outside this writer), and the drain is the drain. plane_copy is
        // an honest zero on the direct path — that is the whole point.
        timing?.markWriterStages(
            encWaitNanos = heldAcquireWaitNanos,
            planeCopyNanos = 0,
            drainNanos = System.nanoTime() - tDrain0,
        )
        heldAcquireWaitNanos = 0
    }

    override fun abandonVideoFrame() {
        if (surfaceInputActive || sessionSurfaceMode) {
            throw encodeFailure(
                "the writer is in encoder-Surface mode; plane abandon is not available"
            )
        }
        // The conversion refused the planes: hand the buffer back to the
        // codec without queueing a frame (size 0 = empty, the same
        // handback signalEos uses), then clear the stash. The packed
        // fallback's writeVideoFrame dequeues a fresh buffer next.
        val codec = videoCodec ?: return
        val index = heldInputIndex
        if (index >= 0) {
            heldInputIndex = -1
            heldImage = null
            heldAcquireWaitNanos = 0
            codec.queueInputBuffer(index, 0, 0, 0, 0)
        }
    }

    override fun writeAudioBlock(ptsUs: Long, pcm: ShortArray) {
        val codec = audioCodec ?: throw notStarted()
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) {
            throw encodeFailure("$MIME_AAC returned no input buffer")
        }
        val buf = codec.getInputBuffer(index)
            ?: throw encodeFailure("$MIME_AAC gave no input buffer for this block")
        buf.clear()
        buf.order(ByteOrder.nativeOrder())
        for (s in pcm) buf.putShort(s)
        codec.queueInputBuffer(index, 0, pcm.size * 2, ptsUs, 0)
        drain(codec, isVideo = false, timeoutUs = 0)
    }

    override fun finish(): Boolean {
        val v = videoCodec
        val a = audioCodec
        if (v != null) {
            if (surfaceInputActive || sessionSurfaceMode) {
                // §10: Surface input has no input buffers to carry an EOS
                // flag — signalEndOfInputStream is the Surface contract, then
                // drainUntilEos below is unchanged. sessionSurfaceMode rides
                // the same branch (M5): the codec was configured for Surface
                // input either way, whether the writer's own EGL or the GPU
                // develop session produced the frames.
                v.signalEndOfInputStream()
            } else {
                signalEos(v)
            }
        }
        if (a != null) signalEos(a)
        v?.let { drainUntilEos(it, isVideo = true) }
        a?.let { drainUntilEos(it, isVideo = false) }
        val m = muxer ?: return false
        if (!muxerStarted) return false
        return try {
            m.stop()
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun release() {
        // ---- Surface-mode teardown (§10/§11) ----
        // Reverse bring-up order, idempotent, safe at every partial-init
        // point: the GL objects go first (the context may still be current),
        // then the context is unbound, the window surface, the context and
        // the display are destroyed, and the codec's input Surface is
        // released before the codec that created it. On a CPU-mode writer
        // every field is null/0 and this whole block is skipped.
        val display = eglDisplay
        val window = eglWindowSurface
        val context = eglContext
        val program = blitProgram
        val texture = blitTexture
        val surface = inputSurface
        if (program != 0) {
            try {
                GLES30.glDeleteProgram(program)
            } catch (_: Exception) {
            }
            blitProgram = 0
        }
        if (texture != 0) {
            try {
                GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            } catch (_: Exception) {
            }
            blitTexture = 0
        }
        blitSrcLocation = -1
        blitSizeLocation = -1
        if (display != null) {
            if (window != null) {
                try {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    EGL14.eglDestroySurface(display, window)
                } catch (_: Exception) {
                }
                eglWindowSurface = null
            }
            if (context != null) {
                try {
                    EGL14.eglDestroyContext(display, context)
                } catch (_: Exception) {
                }
                eglContext = null
            }
            try {
                EGL14.eglTerminate(display)
            } catch (_: Exception) {
            }
            eglDisplay = null
            eglConfig = null
        }
        if (surface != null) {
            try {
                surface.release()
            } catch (_: Exception) {
            }
            inputSurface = null
        }
        surfaceInputRequested = false
        surfaceInputActive = false
        sessionSurfaceMode = false

        // ---- the CPU-path teardown, unchanged ----
        videoCodec?.let { c ->
            try {
                c.stop()
            } catch (_: Exception) {
            }
            c.release()
        }
        videoCodec = null
        audioCodec?.let { c ->
            try {
                c.stop()
            } catch (_: Exception) {
            }
            c.release()
        }
        audioCodec = null
        muxer?.let { m ->
            try {
                m.release()
            } catch (_: Exception) {
            }
        }
        muxer = null
        pending.clear()
    }

    // ---- internals ----

    private fun signalEos(codec: MediaCodec) {
        val index = codec.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index >= 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    /** Drains until this codec's end-of-stream buffer appears (finish path). */
    private fun drainUntilEos(codec: MediaCodec, isVideo: Boolean) {
        val deadline = System.nanoTime() + EOS_DEADLINE_NS
        while (System.nanoTime() < deadline) {
            if (drain(codec, isVideo, timeoutUs = 1_000_000)) return
        }
        throw encodeFailure(
            (if (isVideo) videoMime else MIME_AAC) + " did not finish within the deadline"
        )
    }

    /**
     * Drains whatever output is ready. Returns true when this codec's
     * end-of-stream buffer was seen. Samples that surface before the muxer
     * can start are held in [pending] and flushed the moment it starts.
     */
    private fun drain(codec: MediaCodec, isVideo: Boolean, timeoutUs: Long): Boolean {
        val m = muxer ?: throw notStarted()
        while (true) {
            val info = MediaCodec.BufferInfo()
            val index = codec.dequeueOutputBuffer(info, timeoutUs)
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val track = m.addTrack(codec.outputFormat)
                if (isVideo) {
                    videoTrack = track
                    videoTrackAdded = true
                } else {
                    audioTrack = track
                    audioTrackAdded = true
                }
                maybeStartMuxer(m)
                continue
            }
            if (index >= 0) {
                var eos = false
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val track = if (isVideo) videoTrack else audioTrack
                        if (muxerStarted && track >= 0) {
                            m.writeSampleData(track, buf, info)
                        } else {
                            // The other track's format has not arrived yet:
                            // hold a COPY of the bytes (the codec buffer dies
                            // at releaseOutputBuffer below) with offset 0.
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            val held = cloneInfo(info)
                            held.set(0, info.size, info.presentationTimeUs, info.flags)
                            pending.add(PendingSample(track, bytes, held))
                        }
                    }
                }
                eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(index, false)
                if (eos) return true
            }
        }
    }

    private fun maybeStartMuxer(m: MediaMuxer) {
        if (muxerStarted) return
        val audioExpected = audioCodec != null
        if (videoTrackAdded && (!audioExpected || audioTrackAdded)) {
            m.start()
            muxerStarted = true
            // Flush the packets that were produced before the second track's
            // format arrived, in arrival order (per-track pts order).
            for (p in pending) {
                if (p.track >= 0) {
                    m.writeSampleData(p.track, ByteBuffer.wrap(p.bytes), p.info)
                }
            }
            pending.clear()
        }
    }

    /** Copies a packed I420 frame into the codec's Image planes (its strides). */
    private fun copyI420ToImage(i420: ByteArray, image: Image, w: Int, h: Int) {
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val yOff = 0
        val uOff = w * h
        val vOff = w * h + cw * ch

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer
        val yRow = yPlane.rowStride
        for (row in 0 until h) {
            yBuf.position(row * yRow)
            yBuf.put(i420, yOff + row * w, w)
        }

        copyChroma(image.planes[1], i420, uOff, cw, ch)
        copyChroma(image.planes[2], i420, vOff, cw, ch)
    }

    private fun copyChroma(
        plane: Image.Plane,
        i420: ByteArray,
        srcOffset: Int,
        cw: Int,
        ch: Int,
    ) {
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        for (row in 0 until ch) {
            var src = srcOffset + row * cw
            var dst = row * rowStride
            for (col in 0 until cw) {
                buf.position(dst)
                buf.put(i420[src])
                src++
                dst += pixelStride
            }
        }
    }

    private fun cloneInfo(info: MediaCodec.BufferInfo): MediaCodec.BufferInfo {
        val out = MediaCodec.BufferInfo()
        out.set(info.offset, info.size, info.presentationTimeUs, info.flags)
        return out
    }

    private fun encodeFailure(cause: String): IllegalStateException =
        IllegalStateException(
            Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_ENCODE,
                cause,
                "retry the export (free memory and close other apps first)",
            )
        )

    private fun notStarted(): IllegalStateException =
        IllegalStateException(
            Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_ENCODE,
                "the writer was used before start",
                "retry the export",
            )
        )

    private companion object {
        const val MIME_AVC = "video/avc"
        const val MIME_AAC = ExportFormats.MIME_AAC
        const val INPUT_TIMEOUT_US = 10_000_000L // 10 s: decode is slow, not stuck
        const val EOS_DEADLINE_NS = 10_000_000_000L // 10 s to drain after EOS
        // Owner request 2026-11-12 §4: the export diagnostics tag. Same
        // "ritim-*" style as ritim-pm/ritim-scope; the prompt's "riti-export"
        // is adapted to the app's tag convention.
        const val TAG = "ritim-export"
    }
}
