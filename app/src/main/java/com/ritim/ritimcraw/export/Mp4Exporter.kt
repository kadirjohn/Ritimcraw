package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.CropState
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.engine.StabilizationGate
import com.ritim.ritimcraw.engine.BackendGates
import com.ritim.ritimcraw.engine.BackendRules
import com.ritim.ritimcraw.engine.EffectiveRenderBackend
import com.ritim.ritimcraw.engine.GlesParityResult
import com.ritim.ritimcraw.engine.GlesProbeResult
import com.ritim.ritimcraw.engine.GlesShaderSources
import com.ritim.ritimcraw.engine.GpuParityRules
import com.ritim.ritimcraw.engine.GradeParcel
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.engine.RenderBackend
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure mapping from a failed MP4 export to the user-facing message
 * (DESIGN.md Error states: name the stage, the cause and the recovery —
 * never a bare "failed"). Kept free of Android imports so it is
 * JVM-testable.
 *
 * Two kinds of stages appear:
 *  - NATIVE stages (int tokens from jni_bridge.cpp, same contract as the
 *    preview/DNG paths): 0 open, 1 frame read, 2 develop.
 *  - KOTLIN stages (string constants below): everything the pipeline does
 *    on top of the native calls — encoder configure, encode, mux, finalize.
 */
object Mp4ExportErrors {

    // rawedit::ErrorCode (core/include/rawedit/Result.h) — native failures.
    const val CODE_OK = 0
    const val CODE_TRUNCATED = 1
    const val CODE_BAD_HEADER = 2
    const val CODE_IO = 3
    const val CODE_UNSUPPORTED = 4

    // Native stage tokens (jni_bridge.cpp kStageOpen/kStageFrameRead/kStageDevelop).
    const val STAGE_OPEN = 0
    const val STAGE_FRAME_READ = 1
    const val STAGE_DEVELOP = 2

    /** Kotlin-side stages of the export pipeline. */
    const val STAGE_CONFIGURE = "configure"
    const val STAGE_ENCODE = "encode"
    const val STAGE_MUX = "muxer"
    const val STAGE_FINALIZE = "finalize"

    /** Native stage name shown in the message. */
    fun stageName(stage: Int): String = when (stage) {
        STAGE_OPEN -> "open"
        STAGE_FRAME_READ -> "frame read"
        STAGE_DEVELOP -> "develop"
        else -> "unknown"
    }

    /** Cause + recovery wording per rawedit::ErrorCode (native failures). */
    fun causeAndRecovery(code: Int): Pair<String, String> = when (code) {
        CODE_TRUNCATED ->
            "truncated clip" to "re-import the clip"
        CODE_BAD_HEADER ->
            "not a supported clip" to "re-import the clip"
        CODE_IO ->
            "the clip or the output could not be read or written" to
                "check free space and the clip, then retry"
        CODE_UNSUPPORTED ->
            "unsupported clip" to "re-import the clip"
        else ->
            "unknown error" to "retry the export"
    }

    /**
     * Message for a NATIVE failure, e.g.
     * "MP4 export failed (stage: frame read): truncated clip — recovery: re-import the clip [detail]".
     */
    fun message(code: Int, stage: Int, detail: String? = null): String {
        val (cause, recovery) = causeAndRecovery(code)
        return kotlinMessage(stageName(stage), cause, recovery, detail)
    }

    /**
     * Message for a KOTLIN-stage failure, e.g.
     * "MP4 export failed (stage: configure): video/avc rejected the format — recovery: ...".
     */
    fun kotlinMessage(stage: String, cause: String, recovery: String, detail: String? = null): String {
        val base = "MP4 export failed (stage: $stage): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }
}

/** Session info from nativePreviewOpen: [handle, frameCount, w, h, cfa, orientation]. */
data class Mp4SessionInfo(
    val handle: Long,
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val cfa: Int,
    val orientationDeg: Int,
)

/**
 * The native MP4 export boundary, as an interface so the pipeline is
 * JVM-testable with a fake (the real calls need librawedit_jni.so).
 *
 * Contract with app/src/main/cpp/jni_bridge.cpp: every method must run on
 * ONE thread (the shared export engine) — the decoder is not
 * concurrency-safe and the bridge error state is thread-local.
 */
interface Mp4Session {
    /** Opens the clip; null on failure (then [code]/[stage]/[detail] say why). */
    fun open(fd: Int): Mp4SessionInfo?

    /**
     * Develops frame [frameIndex] at FULL resolution with [grade]:
     * developCpu -> linearToRec709 -> clamp -> 8-bit RGB888 (row-major,
     * NOT rotated — orientation travels as the muxer hint). [lutPath] is
     * the job snapshot's selected .cube path (null = no LUT, the old
     * look; COL-07) — the SAME stage-22 apply as the preview, so the
     * owner's preview-vs-export acceptance check compares like with like.
     * The snapshot's denoise percents (DN-01..08) apply with the same
     * native 0..100 scale as the preview. [gyroPath] is the attached gyro
     * log's path (gyro Phase 5): when the snapshot has stabilization on
     * AND a log is attached, the frame is WARPED at full resolution
     * before the RGB888 pack — the same per-frame warp the paused
     * preview shows, so the exported clip matches the preview. Export is
     * NOT tier-gated (the temporal NR precedent): the export can afford
     * the measured full-res warp cost offline. Null gyroPath (or
     * stabilization off) = no warp. Null on failure (read the error
     * accessors from the same thread).
     */
    fun renderFrame(
        handle: Long,
        frameIndex: Int,
        grade: GradeK,
        lutPath: String? = null,
        gyroPath: String? = null,
    ): ByteArray?

    /** ptsUs of frame [frameIndex] (-1 on a bad handle). */
    fun framePts(handle: Long, frameIndex: Int): Long

    /**
     * The six native sub-stage times of the frame [renderFrame] just
     * produced, in milliseconds, in the fixed order
     * [decode, temporal, develop, warp, oetf_pack, jni_copy] — or null
     * when there is nothing to report (a fake, a non-NDK build, or a
     * release build's gate off). Commit (d): the ExportStageTiming sample
     * lines use this to split "native=..ms" into its internals. Default
     * null so every JVM fake stays untouched — only NativeMp4Session
     * reports. The pipeline reads this only after a SUCCESSFUL renderFrame:
     * a failed frame leaves the previous frame's slots in place, which
     * nobody reads.
     */
    fun lastFrameSubStagesMs(): DoubleArray? = null

    /** [sampleRate, channels], or null/0-0 when the clip has no audio. */
    fun audioInfo(handle: Long): IntArray?

    /** The whole clip's PCM (interleaved), or null on failure. */
    fun readAudio(handle: Long): ShortArray?

    /** Closes the session (best effort; safe to call twice). */
    fun close(handle: Long)

    /** rawedit::ErrorCode of the last failed call. */
    fun code(): Int

    /** Native stage token of the last failed call. */
    fun stage(): Int

    /** Bridge detail of the last failed call. */
    fun detail(): String?
}

/** The real boundary: JNI calls into librawedit_jni.so. */
internal object NativeMp4Session : Mp4Session {

    private fun requireNative() {
        if (!NativeSource.nativeAvailable) {
            // No NDK build: fail cleanly with the unavailable message
            // instead of UnsatisfiedLinkError. The exporter's catch-all
            // turns this into a FAILED job.
            throw IllegalStateException(NativeSource.nativeUnavailableMessage)
        }
    }

    override fun open(fd: Int): Mp4SessionInfo? {
        requireNative()
        val r = NativeSource.jniPreviewOpen(fd) ?: return null
        return Mp4SessionInfo(
            handle = r[0].toLong(),
            frameCount = r[1],
            width = r[2],
            height = r[3],
            cfa = r[4],
            orientationDeg = r[5],
        )
    }

    override fun renderFrame(
        handle: Long,
        frameIndex: Int,
        grade: GradeK,
        lutPath: String?,
        gyroPath: String?,
    ): ByteArray? {
        requireNative()
        // Gyro Phase 5: export is NOT tier-gated (the temporal NR precedent
        // — PreviewTiers gates the PREVIEW, never the export), so this is
        // its own on+path gate rather than GradeMapping.stabilizationArgs
        // (which carries PreviewTiers.stabilizationAppliesAt — a preview
        // policy the export must not depend on, even though the audit fix
        // of 2026-11-12 made that gate pass at every tier). Strength maps
        // 0..100 -> 0..1 exactly like lutStrength; mode/zoomLimit/focalMm/
        // trimMs pass through raw (the C++ side owns their composition).
        val stab = if (StabilizationGate.active(grade.stabilization, gyroPath)) {
            grade.stabilizationStrength / 100f
        } else {
            0f
        }
        return NativeSource.jniExportFrameParcel(
            handle, frameIndex,
            // GRADE PARCEL (commit b): the 29 scalar fields cross as ONE
            // packet (encoded here per call — the CPU session's own seam,
            // one call per frame; the encode loop's export-grade parcel is
            // the GPU twins' packet). The bridge decodes once and folds
            // onto rawedit::Grade via the generated applyGradeToRawedit —
            // field-for-field the same construction the flat door threaded.
            GradeParcel.encode(grade),
            // COL-07: percent 0..100 -> rawedit's 0..1 blend, same mapping
            // as GradeMapping.toNative (every JNI call site passes 0..1).
            lutPath, grade.lutStrength / 100f,
            // Gyro Phase 5: null gyroPath (or stabilization off) = the
            // bridge's no-warp default (null path -> resolveStabilization
            // returns the default StabInput -> applyStabilization no-ops).
            if (StabilizationGate.active(grade.stabilization, gyroPath)) gyroPath else null,
            grade.stabilizationMode,
            stab,
            grade.stabilizationZoomLimit,
            grade.stabilizationFocalMm,
            grade.stabilizationTrimMs,
            // Managed LUT interpretation (owner request 2026-11-13): the
            // grade's binding BY VALUE; null = the legacy stage-24 path
            // (all five -1, byte-identical).
            grade.lutInterp?.role?.value ?: -1,
            grade.lutInterp?.input?.gamut?.value ?: -1,
            grade.lutInterp?.input?.transfer?.value ?: -1,
            grade.lutInterp?.output?.gamut?.value ?: -1,
            grade.lutInterp?.output?.transfer?.value ?: -1,
        )
    }

    override fun framePts(handle: Long, frameIndex: Int): Long =
        NativeSource.jniFramePtsUs(handle, frameIndex)

    override fun lastFrameSubStagesMs(): DoubleArray? {
        // The gate is off in release builds (null from the bridge), and the
        // pipeline only calls this after a successful renderFrame — the
        // slots then hold THIS frame's numbers (thread-local, same thread).
        return if (NativeSource.nativeAvailable) {
            NativeSource.jniExportFrameTimingMs()
        } else {
            null
        }
    }

    override fun audioInfo(handle: Long): IntArray? = NativeSource.jniAudioInfo(handle)

    override fun readAudio(handle: Long): ShortArray? = NativeSource.jniReadAudio(handle)

    override fun close(handle: Long) {
        NativeSource.jniCloseSource(handle)
    }

    override fun code(): Int = NativeSource.jniPreviewErrorCode()

    override fun stage(): Int = NativeSource.jniPreviewErrorStage()

    override fun detail(): String? = NativeSource.previewLastError()
}

/**
 * The production RGB888 -> I420 dispatch (the [RgbToI420] seam's default):
 * the native twin first (the byte-exact converter in core/src/Yuv.cpp,
 * reached through NativeSource.jniRgbToI420), the Kotlin
 * [YuvConverter.toI420] as the fallback. The fallback keeps the export
 * working wherever the native entry refuses — a non-NDK build
 * (nativeAvailable false), a malformed frame (the native entry's
 * null-return with the error state set; the Kotlin require() then fails
 * the export the same way it always did, never a silent wrong frame).
 *
 * JVM tests never touch this object: the pipeline's default is injectable
 * (ExportPipeline's converter parameter), and the fakes stay pure.
 */
object NativeRgbToI420 : RgbToI420 {

    private const val MATRIX_BT709 = 1

    override fun convert(rgb: ByteArray, width: Int, height: Int): RgbToI420Result {
        if (NativeSource.nativeAvailable) {
            val native = NativeSource.jniRgbToI420(rgb, width, height, MATRIX_BT709)
            if (native != null) return RgbToI420Result(native, fromNative = true)
            // A null return with the library loaded is a malformed request
            // (empty frame / short buffer): fall through to Kotlin, whose
            // require() turns it into the export's existing failure mode.
        }
        return RgbToI420Result(YuvConverter.toI420(rgb, width, height), fromNative = false)
    }

    /**
     * The direct-to-plane form (commit (c)): the native twin writes the
     * encoder Image's plane buffers directly — no packed intermediate,
     * no second copy. Null (packed fallback) whenever the native entry
     * cannot: library unavailable, non-direct buffers, malformed frame,
     * or a stride geometry it refuses. The fallback produces the SAME
     * bytes (the seam's contract), so a refusal never changes the
     * encoded output.
     */
    override fun convertToPlanes(
        rgb: ByteArray,
        width: Int,
        height: Int,
        planes: YuvFramePlanes,
    ): RgbToI420PlanesResult? {
        if (!NativeSource.nativeAvailable) return null // packed fallback
        val ok = NativeSource.jniRgbToI420Planes(
            rgb, width, height, MATRIX_BT709,
            planes.y, planes.yStride,
            planes.u, planes.uStride, planes.uPixelStride,
            planes.v, planes.vStride, planes.vPixelStride,
        )
        return if (ok) RgbToI420PlanesResult(fromNative = true) else null
    }
}

/**
 * The GPU develop leg's JNI seam (M3 commit b) — the [Mp4Session] pattern
 * again: a dumb reporter interface, the real object calling the NativeSource
 * wrappers, JVM tests injecting a fake. The DISPATCHER (ExportPipeline)
 * owns every decision — fromArray, the parity verdict, the gates — so the
 * seam never decides pass/fail and the decision rules stay JVM-tested
 * (the same philosophy as the C++ harness: it reports raw numbers).
 *
 * The probe/parity half runs BEFORE writer.start() on the export engine
 * thread: the harness brings its own one-shot EGL context up and down
 * inside the call (the PreviewMetrics precedent), and the writer binds ITS
 * context only in start() — running the parity after start would put two
 * contexts on one thread. The session half wraps the M3-commit-a trio:
 * open() returns 0 on failure (previewLastError names the step), render
 * returns null on failure (the session latches unhealthy with the same
 * detail), close is idempotent. All three throw IllegalStateException when
 * the .so is absent — the dispatcher catches that and stays on CPU (the
 * same guard NativeMp4Session.requireNative rides).
 *
 * GRADE PARCEL (commit b): the render doors carry the grade as ONE flat
 * FloatArray — GradeParcel.encode(grade), the GENERATED 29-slot layout
 * (tools/gen-grade-parcel.py is the single source of truth for both
 * sides of the seam; the parcel is built ONCE per grade change, the
 * immutable parameter packet, not per frame). The parcel covers the
 * fragment-covered grade controls — the five base ones (exposure,
 * highlights, highlight reconstruction, vignetting, tonemap), the two
 * spatial-NR stages' raw values (chroma denoise, noise reduction —
 * stages 7/8, the multi-pass fragment chain), the temporal pair (the
 * SAME raw values the CPU export's temporal merge receives; the pair
 * dies at the bridge), the two unsharp stages' raw values (sharpness,
 * detail) and the ten pointwise stages' raw values (black point, white
 * point, shadows, midtones, contrast, temperature, tint, saturation,
 * the color wheel, split tone, with asShot as the 16/17 gate's input) —
 * beside the plumbing values, the second lock against uncovered grades
 * (the Kotlin coverage gate is the first). A stage the fragment pass
 * does not cover cannot even be expressed into the parcel; a new grade
 * field is one schema row + a regenerate, never a signature widening.
 */
interface GpuDevelop {
    /** The capability probe: raw [contextOk, glesMajor, glesMinor, computeOk, floatFboOk, maxTextureSize]; null = JNI failure. */
    fun probe(): DoubleArray?

    /**
     * The case-0 fragment parity run (the developLinear shape — the
     * device-PROVEN case): raw [ran, maxDiffLsb, mismatchCount, samples];
     * null = JNI failure. [fd] is the export's own clip fd (the harness
     * dups it for its independent read; the fd stays the caller's to close).
     */
    fun fragmentParity(fd: Int): DoubleArray?

    /** Opens the develop session: a nonzero handle, or 0 on failure (then [lastDetail] names the step). */
    fun openSession(): Long

    /**
     * (E1d) Opens the develop session on an RGBA1010102 + window +
     * recordable EGL config, found by query in native - the session whose
     * encoder surface takes 10-bit frames. 0 on failure ([lastDetail] names
     * the step). The default 0 keeps every fake and 8-bit-only seam honest:
     * "no 10-bit session".
     */
    fun openSessionTenBit(): Long = 0L

    /** (E1d) Can this device make that 10-bit EGL config at all? A throwaway probe; default false. */
    fun tenBitProbe(): Boolean = false

    /** Closes the session (idempotent; absorbs context loss at teardown). */
    fun closeSession(sessionHandle: Long)

    /**
     * Renders one frame; null on failure (the session latches unhealthy).
     * GRADE PARCEL (commit b): the 29 scalar grade fields cross as ONE
     * flat FloatArray — GradeParcel.encode(grade), the GENERATED slot
     * layout (tools/gen-grade-parcel.py is the single source of truth for
     * both sides of the seam). The parcel is the dispatcher's ONE
     * immutable parameter packet, built once per export before the frame
     * loop — never re-encoded per frame. NO default parameter values on
     * purpose (the parcel wrappers mirror the JNI seam exactly).
     */
    fun renderFrameParcel(
        sessionHandle: Long,
        clipHandle: Long,
        frameIndex: Int,
        gradeParcel: FloatArray,
        lutPath: String?,
        lutStrength: Float,
        lutRole: Int,
        lutInGamut: Int,
        lutInTransfer: Int,
        lutOutGamut: Int,
        lutOutTransfer: Int,
        warpActive: Boolean,
        warpM: FloatArray,
    ): ByteArray?

    /**
     * M5: hands the MediaCodec input Surface to the session, which wraps
     * it in an EGL window surface ONCE per export on its GL thread. The
     * surface is typed Any? (the JVM-loadability rule — see
     * nativePreviewSessionSetSurface). False names the step in
     * [lastDetail]; the dispatcher answers with ONE fallback line.
     */
    fun setEncoderSurface(sessionHandle: Long, surface: Any?): Boolean = false

    /**
     * M5: develops one frame on the GPU and blits it straight to the
     * encoder surface — no readback bytes cross the seam. [ptsUs] is the
     * frame's normalized encoder presentation timestamp (the same value
     * the writer path stamps). [rotationDeg] is the frame's EFFECTIVE
     * orientation (container + manual, normalized): the blit applies it
     * through the uRotation fold — 0/90/180/270 only (the gate checks
     * that before dispatch; the session refuses again on the calling
     * thread). 0 is the byte-identical upright copy. False names the
     * step in [lastDetail].
     *
     * GRADE PARCEL (commit b): the 29 scalar grade fields cross as ONE
     * flat FloatArray — GradeParcel.encode(grade), the GENERATED slot
     * layout (tools/gen-grade-parcel.py is the single source of truth for
     * both sides of the seam), built once per export before the frame
     * loop. NO default parameter values on purpose (the parcel wrappers
     * mirror the JNI seam exactly).
     */
    fun renderFrameToEncoderParcel(
        sessionHandle: Long,
        clipHandle: Long,
        frameIndex: Int,
        ptsUs: Long,
        gradeParcel: FloatArray,
        lutPath: String?,
        lutStrength: Float,
        lutRole: Int,
        lutInGamut: Int,
        lutInTransfer: Int,
        lutOutGamut: Int,
        lutOutTransfer: Int,
        warpActive: Boolean,
        warpM: FloatArray,
        rotationDeg: Int,
    ): Boolean

    /**
     * The gyro warp resolver (the GPU warp port's commit b): the
     * per-frame plan verdict the dispatch threads into the renders.
     * Returns the 10-element array ([0] active flag, [1..9] the
     * composite M) or null = no verdict; the dispatcher folds null to
     * warpActive=false + identity M. The default body keeps fakes
     * honest: a fake that does not override it never fabricates a warp.
     */
    fun resolveWarp(
        clipHandle: Long,
        gyroPath: String?,
        stabMode: Int,
        stabStrength: Float,
        stabZoomLimit: Float,
        stabFocalMm: Float,
        stabTrimMs: Float,
        frameIndex: Int,
        maxDim: Int,
    ): FloatArray? = null

    /** The bridge's thread-local last-error detail (same thread as the failed call). */
    fun lastDetail(): String?
}

/** The real boundary: the NativeSource wrappers with the validated single-source-of-truth shader texts. */
internal object NativeGpuDevelop : GpuDevelop {
    override fun probe(): DoubleArray? = NativeSource.jniGlesProbe()

    override fun fragmentParity(fd: Int): DoubleArray? =
        NativeSource.jniGlesFragmentParity(
            fd,
            GlesShaderSources.FULLSCREEN_VERTEX,
            GlesShaderSources.FULLSCREEN_VERTEX_310,
            GlesShaderSources.DEVELOP_FRAGMENT,
            GlesShaderSources.STORE_FRAGMENT,
            0, // case 0 — the developLinear shape, the device-proven case
        )

    override fun openSession(): Long = NativeSource.jniDevelopSessionOpen(
        GlesShaderSources.FULLSCREEN_VERTEX,
        GlesShaderSources.FULLSCREEN_VERTEX_310,
        GlesShaderSources.DEVELOP_FRAGMENT_PRE,
        GlesShaderSources.DEVELOP_FRAGMENT_POST,
        GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
        GlesShaderSources.SHARPNESS_H,
        GlesShaderSources.SHARPNESS_V,
        GlesShaderSources.DETAIL_H,
        GlesShaderSources.DETAIL_V,
        GlesShaderSources.WARP,
        GlesShaderSources.NR_CHROMA_H,
        GlesShaderSources.NR_CHROMA_V,
        GlesShaderSources.NR_LUMA,
        GlesShaderSources.STORE_FRAGMENT,
        GlesShaderSources.BLIT_FRAGMENT,
    )

    override fun openSessionTenBit(): Long = NativeSource.jniDevelopSessionOpenTenBit(
        GlesShaderSources.FULLSCREEN_VERTEX,
        GlesShaderSources.FULLSCREEN_VERTEX_310,
        GlesShaderSources.DEVELOP_FRAGMENT_PRE,
        GlesShaderSources.DEVELOP_FRAGMENT_POST,
        GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
        GlesShaderSources.SHARPNESS_H,
        GlesShaderSources.SHARPNESS_V,
        GlesShaderSources.DETAIL_H,
        GlesShaderSources.DETAIL_V,
        GlesShaderSources.WARP,
        GlesShaderSources.NR_CHROMA_H,
        GlesShaderSources.NR_CHROMA_V,
        GlesShaderSources.NR_LUMA,
        GlesShaderSources.STORE_FRAGMENT,
        GlesShaderSources.BLIT_FRAGMENT,
    )

    // One probe per process: the EGL config set does not change while the app runs.
    private val tenBitProbeResult: Boolean by lazy {
        try {
            NativeSource.jniDevelopTenBitProbe()
        } catch (e: IllegalStateException) {
            false // no .so: no 10-bit
        }
    }

    override fun tenBitProbe(): Boolean = tenBitProbeResult

    /**
     * GRADE PARCEL (commit b): the REAL parcel door — the JNI seam takes
     * the parcel; the bridge decodes ONCE per call. The parcel is
     * caller-built (GradeParcel.encode, once per grade change).
     */
    override fun renderFrameParcel(
        sessionHandle: Long,
        clipHandle: Long,
        frameIndex: Int,
        gradeParcel: FloatArray,
        lutPath: String?,
        lutStrength: Float,
        lutRole: Int,
        lutInGamut: Int,
        lutInTransfer: Int,
        lutOutGamut: Int,
        lutOutTransfer: Int,
        warpActive: Boolean,
        warpM: FloatArray,
    ): ByteArray? = NativeSource.jniDevelopSessionRenderFrameParcel(
        sessionHandle, clipHandle, frameIndex, gradeParcel,
        lutPath, lutStrength, lutRole, lutInGamut, lutInTransfer,
        lutOutGamut, lutOutTransfer, warpActive, warpM,
    )

    override fun setEncoderSurface(sessionHandle: Long, surface: Any?): Boolean =
        NativeSource.jniDevelopSessionSetEncoderSurface(sessionHandle, surface)

    /**
     * GRADE PARCEL (commit b): the REAL encoder parcel door — the JNI seam
     * takes the parcel; the bridge decodes ONCE per call.
     */
    override fun renderFrameToEncoderParcel(
        sessionHandle: Long,
        clipHandle: Long,
        frameIndex: Int,
        ptsUs: Long,
        gradeParcel: FloatArray,
        lutPath: String?,
        lutStrength: Float,
        lutRole: Int,
        lutInGamut: Int,
        lutInTransfer: Int,
        lutOutGamut: Int,
        lutOutTransfer: Int,
        warpActive: Boolean,
        warpM: FloatArray,
        rotationDeg: Int,
    ): Boolean = NativeSource.jniDevelopSessionRenderFrameToEncoderParcel(
        sessionHandle, clipHandle, frameIndex, ptsUs, gradeParcel,
        lutPath, lutStrength, lutRole, lutInGamut, lutInTransfer,
        lutOutGamut, lutOutTransfer, warpActive, warpM, rotationDeg,
    )

    override fun resolveWarp(
        clipHandle: Long,
        gyroPath: String?,
        stabMode: Int,
        stabStrength: Float,
        stabZoomLimit: Float,
        stabFocalMm: Float,
        stabTrimMs: Float,
        frameIndex: Int,
        maxDim: Int,
    ): FloatArray? = NativeSource.jniStabWarpResolve(
        clipHandle, gyroPath, stabMode, stabStrength,
        stabZoomLimit, stabFocalMm, stabTrimMs, frameIndex, maxDim,
    )

    override fun closeSession(sessionHandle: Long) =
        NativeSource.jniDevelopSessionClose(sessionHandle)

    override fun lastDetail(): String? = NativeSource.previewLastError()
}

/**
 * The encoder + muxer boundary (MediaCodec + MediaMuxer in the real app),
 * as an interface so the pipeline is JVM-testable with a fake codec.
 *
 * One writer per export. [start] configures and starts everything (it
 * throws when the encoder rejects the format — that is the
 * rejected-encoder case); the write methods feed frames/blocks in
 * presentation order; [finish] drains and stops the muxer; [release] is
 * always called, success or failure.
 */
interface Mp4MediaWriter {
    /**
     * (E1b) The resolved export plan the video encoder is configured from:
     * encoder by name, MIME, profile, bitrate, rate mode, real fps,
     * I-frame interval, AAC kbps. Call BEFORE [start]. The default no-op
     * keeps every JVM fake compiling; a writer that never gets a plan
     * (a pre-E1 job) configures exactly what it always did
     * ([ExportPlan.legacy]).
     */
    fun configureVideo(plan: ExportPlan) {}

    /**
     * (E1b) Declares that this export carries [ExportSettings]: [start]
     * must then be preceded by [configureVideo]. Calling start without it
     * is a wiring bug - an exception in debug builds (so tests and the
     * owner's debug installs catch it), a log line in release (the export
     * then runs on the legacy configuration rather than dying).
     */
    fun expectVideoConfig() {}

    /** What MediaCodec really created for the video track (after start), or null. */
    fun encoderIdentity(): EncoderDiagnostics? = null

    /**
     * Configures both encoders and the muxer for [outPath]. [sampleRate]/
     * [channels] 0 = clip has no audio (video track only). Throws
     * IllegalStateException naming the stage ("configure") and the codec
     * when something rejects the format.
     */
    fun start(
        outPath: String,
        width: Int,
        height: Int,
        orientationDeg: Int,
        sampleRate: Int,
        channels: Int,
    )

    /** Feeds one I420 frame (YuvConverter.toI420 layout) at [ptsUs]. */
    fun writeVideoFrame(ptsUs: Long, i420: ByteArray)

    /**
     * The direct-to-plane offer (commit (c)): dequeues the encoder input
     * buffer and returns its plane geometry so the conversion can write
     * the encoder's memory directly. Null (the default) = this writer
     * only speaks packed frames — the pipeline then uses
     * [writeVideoFrame] exactly as before, and every JVM fake keeps its
     * existing behavior untouched. A non-null return MUST be followed by
     * exactly one [queueVideoFrame] (success) or [abandonVideoFrame]
     * (conversion refused the planes) for the SAME frame — the acquired
     * input buffer is held until then.
     */
    fun acquireVideoFramePlanes(): YuvFramePlanes? = null

    /**
     * Queues the frame whose planes [acquireVideoFramePlanes] handed
     * over (the conversion wrote them directly). The size is computed
     * from the same plane strides (imageInputSize) — the codec does NOT
     * infer it from the Image. The default throws: a writer that offers
     * planes must implement the acquire/queue/abandon triple.
     */
    fun queueVideoFrame(ptsUs: Long) {
        throw IllegalStateException(
            Mp4ExportErrors.kotlinMessage(
                Mp4ExportErrors.STAGE_ENCODE,
                "the writer offered video frame planes but cannot queue them",
                "retry the export",
            )
        )
    }

    /**
     * Returns the held input buffer without queueing (the conversion
     * refused the planes — the packed fallback takes over). The default
     * no-op matches [acquireVideoFramePlanes]'s null default: a writer
     * that never offers planes never has anything to abandon.
     */
    fun abandonVideoFrame() {}

    /** Feeds one block of interleaved PCM frames at [ptsUs]. */
    fun writeAudioBlock(ptsUs: Long, pcm: ShortArray)

    /** Drains the encoders and stops the muxer. True when the file is complete. */
    fun finish(): Boolean

    /** Releases everything (best effort; safe to call twice). */
    fun release()

    /**
     * Optional per-export stage-timing sink (owner request 2026-11-13,
     * export stage timing): the pipeline attaches its [ExportStageTiming]
     * right before the encode loop, and a writer that measures its
     * internals reports them per frame through
     * [ExportStageTiming.markWriterStages] (same thread — writeVideoFrame
     * runs inside the pipeline's loop iteration). Default no-op: the JVM
     * fakes and any timing-unaware writer keep compiling and behave
     * identically; a null argument turns the writer's timing off.
     */
    fun attachTiming(timing: ExportStageTiming?) {}

    /**
     * The encoder-Surface input request (GPU deliverable F, owner request
     * 2026-11-12 §10): the pipeline calls this BEFORE start when the export
     * job's render backend is GPU_EXPERIMENTAL — an explicit request, never
     * auto. The default no-op = this writer does not speak Surface input:
     * start() behaves exactly as before and every JVM fake keeps its
     * existing behavior untouched. A writer that accepts the request brings
     * the mode up inside start(); one that CANNOT bring it up throws
     * [SurfaceInputUnavailable] from start (the exporter's clean
     * whole-export CPU restart).
     */
    fun requestEncoderSurfaceInput() {}

    /**
     * Per-frame Surface-mode offer: the developed frame as packed RGB888
     * (top-row-first, width*height*3 bytes) at its own normalized [ptsUs].
     * True = the frame was taken (the pipeline then skips the I420
     * conversion entirely — the encoder consumes the frame through its
     * input Surface, no full-frame GPU->CPU->encoder round trip, §10's
     * zero-copy goal). False (the default) = this writer does not speak
     * Surface input — the pipeline falls back to the I420 path for this
     * frame, exactly as before. A writer in Surface mode that fails
     * mid-stream throws a plain encode failure: the partial is discarded
     * honestly (no mid-file format change, no double-encoded frame).
     */
    fun writeVideoFrameRgb(ptsUs: Long, rgb: ByteArray, width: Int, height: Int): Boolean = false

    /**
     * True once start() brought the encoder-Surface input mode up (frames
     * feed the encoder through its input Surface). The default false = a
     * CPU-input writer; the pipeline reads this only for honest stage-log
     * lines, never for a decision — the per-frame [writeVideoFrameRgb]
     * offer is the decision.
     */
    fun surfaceInputActive(): Boolean = false

    /**
     * M5: the session-owns-EGL request. The pipeline calls this BEFORE
     * start when the export job's render backend is GPU_EXPERIMENTAL AND
     * the frame is 1:1-pixels eligible (no rotation/crop/straighten/
     * scale): the writer configures the codec and creates its input
     * Surface WITHOUT wrapping it in EGL and WITHOUT starting the codec
     * (the single-producer BufferQueue rule — the GPU develop session
     * will be the only producer), and start() finishes by starting the
     * codec. The default no-op = this writer does not speak the mode:
     * every existing writer (and every JVM fake) keeps its exact
     * behavior; the pipeline then takes the writer's own Surface mode
     * ([requestEncoderSurfaceInput]) or the byte path as before.
     */
    fun requestSessionEncoderSurface() {}

    /**
     * The codec input Surface created by [requestSessionEncoderSurface]
     * (handed out UNWRAPPED, before the codec starts), or null when the
     * mode was not requested/failed. The pipeline hands it to the GPU
     * develop session's setEncoderSurface; the writer never touches it
     * again until the export ends.
     */
    fun encoderInputSurface(): Any? = null

    /**
     * Per-frame codec drain for the session-owns-EGL mode (the
     * Surface-input contract: the producer must dequeue the codec's
     * output buffers or the pipeline stalls). The pipeline calls this
     * after each session encoder blit. Default no-op.
     */
    fun drainEncoderInput() {}

    /**
     * The ADOPT fallback (M5): the session refused the encoder surface
     * (its setEncoderSurface returned false), so the pipeline asks the
     * writer to do the steps requestSessionEncoderSurface skipped —
     * wrap the SAME input surface in the writer's own EGL (the recordable
     * config path the writer already proved on device), make it current,
     * probe eglPresentationTimeANDROID, build the blit program. After a
     * successful adopt, frames flow through the EXISTING per-frame
     * [writeVideoFrameRgb] offer (the CPU-RGB Surface mode, byte-for-
     * byte the shipped path) — the export continues instead of dying.
     * Failure throws [SurfaceInputUnavailable] (the exporter's clean
     * whole-export CPU restart). Default no-op: a writer without the
     * mode has nothing to adopt.
     */
    fun adoptEncoderSurface() {}
}

/** AAC input: one block = 1024 PCM frames per channel (the AAC frame size). */
internal const val AAC_BLOCK_FRAMES = 1024

/**
 * The byte count one queued video input buffer holds after an Image's planes
 * are filled — the `size` argument of `MediaCodec.queueInputBuffer` on the
 * Image input path (owner request 2026-11-11 §1, "the MP4 really decodes").
 *
 * THE BUG this replaces: the writer used to call
 * `queueInputBuffer(index, 0, 0, ptsUs, 0)` — size 0 — believing the codec
 * would "read the planes it handed out". It does not: `queueInputBuffer`'s
 * `size` is "the number of bytes of valid input data" (MediaCodec javadoc)
 * and travels to the codec's native layer with no Java-side validation. A
 * size of 0 tells the encoder the buffer is EMPTY, so real devices drop or
 * corrupt the frame — a DONE row with an MP4 that ffprobe cannot fully
 * decode.
 *
 * THE CTS PATTERN this follows (Android's compatibility suite, which every
 * shipped encoder must pass):
 *  - EncodeDecodeTest (tests/tests/media/src/android/media/cts, Android 6.0
 *    CTS): "the size of a frame of video data, in the formats we handle, is
 *    stride*sliceHeight for Y, and (stride/2)*(sliceHeight/2) for each of
 *    the Cb and Cr channels" — with tight strides that algebra is
 *    `mWidth * mHeight * 3 / 2`, and the test queues exactly that:
 *    `encoder.queueInputBuffer(inputBufIndex, 0, frameData.length, ptsUsec, 0)`.
 *  - CodecEncoderTestBase (modern CTS, tests/media/common): the Image input
 *    path fills the planes via `fillImage(img)` and then queues
 *    `size = mBytesPerSample * mWidth * mHeight * 3 / 2` with offset 0.
 * Both compute the FILLED byte count from the frame geometry — never 0.
 * (The EOS buffer is the one exception: size 0 together with
 * BUFFER_FLAG_END_OF_STREAM — which the writer's signalEos already does.)
 *
 * GEOMETRY: one plane's filled extent is the bytes up to its last sample,
 *
 *     1 + (rows - 1) * rowStride + (cols - 1) * pixelStride
 *
 * — exactly how the framework's own `MediaCodec.MediaImage` constructor
 * limits each plane's buffer slice (SDK sources:
 * `buffer.limit(buffer.position() + bytesPerSample + (rows/vert - 1) *
 * rowInc + (cols/horiz - 1) * colInc)`), and what `Image.Plane.getBuffer`'s
 * javadoc guarantees ("each plane is only guaranteed to contain data up to
 * the last pixel in the last row").
 *
 * The buffer's valid byte count is the MAXIMUM end offset across the three
 * planes, NOT the sum of their extents: in semiplanar layouts (NV12 — most
 * hardware encoders) U and V share one interleaved plane, so summing
 * double-counts the chroma and can report more bytes than the codec's
 * buffer even holds. Plane placement follows the standard YUV 4:2:0 layout
 * the codec's own buffer is built on: Y at offset 0, chroma after the luma
 * block; in planar layouts V follows U's whole block, in semiplanar layouts
 * V starts one sample into the shared plane. On tight strides the maximum
 * collapses to CTS's `width * height * 3 / 2` for BOTH layouts; padded
 * strides grow it to the true filled extent, which can never exceed the
 * buffer because the codec sized that buffer for exactly these strides.
 *
 * Pure geometry — no Android imports, JVM-testable. The writer hands the
 * live `Image`'s strides to this function right before queueing.
 */
fun imageInputSize(
    width: Int,
    height: Int,
    yRowStride: Int,
    uRowStride: Int,
    uPixelStride: Int,
    vRowStride: Int,
    vPixelStride: Int,
): Int {
    require(width > 0 && height > 0) { "image must have positive dimensions, got ${width}x$height" }
    // YUV 4:2:0: luma is full-size, chroma planes are half (rounded up on
    // odd dimensions — the same cw/ch MediaCodecMp4Writer.copyI420ToImage
    // fills and MediaImage's /2 subsampling crops to).
    val cw = (width + 1) / 2
    val ch = (height + 1) / 2
    require(yRowStride >= width) { "luma row stride $yRowStride cannot hold a $width-wide row" }
    require(uRowStride >= cw * uPixelStride) { "u row stride $uRowStride cannot hold $cw samples at pixel stride $uPixelStride" }
    require(vRowStride >= cw * vPixelStride) { "v row stride $vRowStride cannot hold $cw samples at pixel stride $vPixelStride" }

    // Filled extent of one chroma plane: bytes up to its last sample — the
    // same arithmetic MediaImage uses to limit each plane's slice.
    fun extent(rowStride: Int, pixelStride: Int) = 1 + (ch - 1) * rowStride + (cw - 1) * pixelStride

    // Y sits at offset 0; the chroma planes follow the luma block (the
    // standard layout the codec's buffer is built on).
    val chromaBase = height * yRowStride
    // Planar: V follows U's whole block. Semiplanar: V starts one byte into
    // the plane U shares.
    val vStart = if (uPixelStride == 1) chromaBase + ch * uRowStride else chromaBase + 1

    return maxOf(
        width + (height - 1) * yRowStride, // luma end
        chromaBase + extent(uRowStride, uPixelStride),
        vStart + extent(vRowStride, vPixelStride),
    )
}

/**
 * Per-export stage timing (owner request 2026-11-13, "export stage
 * timing"): a pure accumulator + formatter, JVM-testable end to end — the
 * only impure inputs are System.nanoTime at the call sites and the
 * INJECTED log sink, so this class carries no Android imports.
 *
 * BOUNDARY HONESTY: the "native" stage times ONE JNI call,
 * Mp4Session.renderFrame. Inside that single call the bridge runs the
 * MCRAW decode (+ temporal NR when on) + the 24-stage developCpu + the
 * gyro warp (when on) + the Rec.709 OETF + the RGB888 pack. Commit (d)
 * made those sub-stages separable: the bridge times its six internals
 * into thread-local slots (gated by the same BuildConfig.DEBUG flag as
 * the stab debug log) and the pipeline reads them through
 * [Mp4Session.lastFrameSubStagesMs] right after the call, handing them
 * to [markNativeSubStages]. When the slots exist (a debug build with the
 * native library), the native pair renders as
 * "native=472.3ms(decode=..ms temporal=..ms develop=..ms warp=..ms
 * oetf=..ms copy=..ms)"; when they do not (a JVM fake, a release build,
 * a non-NDK build), the line is exactly what it always was. The other
 * stages are the Kotlin ones (rotate / crop / yuv) and the writer
 * internals (encoder input wait / plane copy / drain-mux, reported by
 * the writer through [markWriterStages]).
 *
 * OUTPUT — all through the injected sink, one line each (never one line
 * per frame):
 *  - a per-frame SAMPLE line at frames 1, 30, 60, 90 (the first frame +
 *    steady-state checkpoints),
 *  - one STAGE AVERAGES line over the whole export,
 *  - one EXPORT SUMMARY line (source -> upright -> encoded dims, active
 *    effect groups, frame count, total encode time).
 *
 * COST: two LongArrays per export, ~8 System.nanoTime reads per frame,
 * strings built only at the sample checkpoints and at close — no
 * hot-loop allocations. A null sink (timing off) is never even
 * constructed by the pipeline, so timing-off exports collect nothing
 * and are byte-identical.
 */
class ExportStageTiming(private val log: ((String) -> Unit)?) {

    /** Clears the per-frame slots at the top of a video frame iteration. */
    fun startFrame() {
        for (i in frameNanos.indices) frameNanos[i] = 0
        nativeSubStagesMs = null
        frameWallStartNanos = System.nanoTime()
    }

    /** Adds [nanos] to the frame's [stage] slot (each call site times its own block). */
    fun mark(stage: Int, nanos: Long) {
        if (stage in 0 until STAGE_COUNT) frameNanos[stage] += nanos
    }

    /**
     * Writer-internal stages, reported by the writer at the END of its
     * writeVideoFrame (same thread — the call runs inside the pipeline's
     * frame iteration, so the slots are filled before [endFrame] reads
     * them). A writer that does not measure (the JVM fakes, any
     * timing-unaware writer) simply never calls this and the slots stay
     * at honest zeros.
     */
    fun markWriterStages(encWaitNanos: Long, planeCopyNanos: Long, drainNanos: Long) {
        frameNanos[STAGE_ENC_WAIT] += encWaitNanos
        frameNanos[STAGE_PLANE_COPY] += planeCopyNanos
        frameNanos[STAGE_DRAIN_MUX] += drainNanos
    }

    /**
     * The native call's own sub-stage split, read through
     * [Mp4Session.lastFrameSubStagesMs] right after a successful
     * renderFrame (commit (d)). Null / wrong length = no data (a fake,
     * release build, or non-NDK build): the frame's native pair renders
     * without the parenthetical, exactly as before. The slots are
     * milliseconds — the native side already did the conversion.
     */
    fun markNativeSubStages(subStagesMs: DoubleArray?) {
        nativeSubStagesMs = if (subStagesMs != null && subStagesMs.size == SUB_STAGE_COUNT) {
            subStagesMs.copyOf()
        } else {
            null
        }
    }

    /** Closes one video frame: accumulates, emits a sample line at checkpoints. */
    fun endFrame() {
        for (i in 0 until STAGE_COUNT) sums[i] += frameNanos[i]
        frames++
        if (isSampleFrame(frames)) log?.invoke(sampleLine())
    }

    /**
     * Emits the per-export lines (call once, after the last frame): stage
     * averages over [frames] frames, then the one-line EXPORT SUMMARY
     * with the three dimension pairs ([sourceW]x[sourceH] as decoded,
     * [rotatedW]x[rotatedH] after the upright rotation, [encodedW]x
     * [encodedH] after the crop — what the encoder received), the active
     * effect groups, the frame count and the whole-encode [encodeNanos].
     */
    fun close(
        sourceW: Int,
        sourceH: Int,
        rotatedW: Int,
        rotatedH: Int,
        encodedW: Int,
        encodedH: Int,
        grade: GradeK,
        gyroActive: Boolean,
        encodeNanos: Long,
    ) {
        val sink = log ?: return
        if (frames > 0) {
            sink(
                "export stages avg over $frames frames: " +
                    stagePairs { sums[it] / frames.toDouble() } + " total=" +
                    ms(encodeNanos / frames.toDouble()) + "ms"
            )
        }
        sink(
            "EXPORT SUMMARY: source ${sourceW}x$sourceH -> upright ${rotatedW}x$rotatedH" +
                " -> encoded ${encodedW}x$encodedH, effects " +
                effects(grade, gyroActive) + ", $frames frames, " +
                ms(encodeNanos.toDouble()) + " ms"
        )
    }

    // ---- internals ----

    private val frameNanos = LongArray(STAGE_COUNT)
    private val sums = LongArray(STAGE_COUNT)
    private var frames = 0
    private var frameWallStartNanos = 0L
    /** The native call's sub-stage split for THIS frame, or null (see [markNativeSubStages]). */
    private var nativeSubStagesMs: DoubleArray? = null

    private fun isSampleFrame(f: Int): Boolean =
        f == SAMPLE_FRAMES[0] || f == SAMPLE_FRAMES[1] ||
            f == SAMPLE_FRAMES[2] || f == SAMPLE_FRAMES[3]

    /** "native=45.2ms rotate=1.1ms ... drain_mux=0.8ms" for the given per-stage source. */
    private fun stagePairs(nanos: (Int) -> Double): String {
        val sb = StringBuilder(96)
        for (s in 0 until STAGE_COUNT) {
            if (s > 0) sb.append(' ')
            sb.append(stageName(s)).append('=').append(ms(nanos(s))).append("ms")
            if (s == STAGE_NATIVE) sb.append(nativeSubStagesSuffix())
        }
        return sb.toString()
    }

    /**
     * The native pair's parenthetical — "(decode=..ms temporal=..ms
     * develop=..ms warp=..ms oetf=..ms copy=..ms)" — or empty when this
     * frame has no sub-stage data (see [markNativeSubStages]). Per-frame
     * values only: the averages line stays the plain stage pairs, because
     * a sum over frames where some frames report and others do not is a
     * guess, not a measurement.
     */
    private fun nativeSubStagesSuffix(): String {
        val t = nativeSubStagesMs ?: return ""
        val sb = StringBuilder(64)
        sb.append('(')
        for (i in 0 until SUB_STAGE_COUNT) {
            if (i > 0) sb.append(' ')
            // The slots are already milliseconds (the native side did the
            // conversion) — format directly, never through ms().
            sb.append(subStageName(i)).append('=')
                .append(String.format(Locale.ROOT, "%.1f", t[i])).append("ms")
        }
        sb.append(')')
        return sb.toString()
    }

    /** The per-frame sample line: stages + the frame's true wall time. */
    private fun sampleLine(): String =
        "export frame $frames: " + stagePairs { frameNanos[it].toDouble() } +
            " total=" + ms((System.nanoTime() - frameWallStartNanos).toDouble()) + "ms"

    private fun ms(nanos: Double): String = String.format(Locale.ROOT, "%.1f", nanos / 1_000_000.0)

    companion object {
        /** The export diagnostics tag (MediaCodecMp4Writer logs here too). */
        const val TAG = "ritim-export"

        /** renderFrame: ONE JNI call — decode + temporal NR + develop + gyro warp + OETF + pack (see class KDoc). */
        const val STAGE_NATIVE = 0
        /** Kotlin upright rotation (RotationState.rotateRgb888). */
        const val STAGE_ROTATE = 1
        /** Kotlin crop/straighten (CropState.applyToRgb). */
        const val STAGE_CROP = 2
        /** RGB888 -> I420 conversion (the RgbToI420 seam: native twin, Kotlin fallback). */
        const val STAGE_YUV = 3
        /** Writer: the dequeueInputBuffer wait for a free encoder input buffer. */
        const val STAGE_ENC_WAIT = 4
        /** Writer: getInputImage + copyI420ToImage + imageInputSize + queueInputBuffer. */
        const val STAGE_PLANE_COPY = 5
        /** Writer: drain — encoded packets out of the codec into the muxer. */
        const val STAGE_DRAIN_MUX = 6
        const val STAGE_COUNT = 7

        /**
         * The native call's internal split (commit (d)) — the bridge's own
         * sub-stage slots, in the FIXED order both sides render/read:
         * decode (MCRAW decode + frame info), temporal (temporal NR merge),
         * develop (the 24-stage developCpu), warp (gyro stabilization),
         * oetf (Rec.709 OETF + RGB888 pack), copy (the JNI ByteArray
         * copy). A debug build with the native library reports all six;
         * anything else reports null and the sample line stays plain.
         */
        const val SUB_STAGE_COUNT = 6

        fun subStageName(i: Int): String = when (i) {
            0 -> "decode"
            1 -> "temporal"
            2 -> "develop"
            3 -> "warp"
            4 -> "oetf"
            5 -> "copy"
            else -> "sub$i"
        }

        /** Per-frame sample checkpoints: the first frame + steady-state points. */
        val SAMPLE_FRAMES = intArrayOf(1, 30, 60, 90)

        fun stageName(stage: Int): String = when (stage) {
            STAGE_NATIVE -> "native"
            STAGE_ROTATE -> "rotate"
            STAGE_CROP -> "crop"
            STAGE_YUV -> "yuv"
            STAGE_ENC_WAIT -> "enc_wait"
            STAGE_PLANE_COPY -> "plane_copy"
            STAGE_DRAIN_MUX -> "drain_mux"
            else -> "stage$stage"
        }

        /**
         * Active-effect tokens for the summary line: the effect groups
         * whose per-frame cost the timing is meant to attribute — spatial
         * NR (luma or chroma percent), temporal NR, the unsharp pair
         * (sharpness or detail percent — the four-pass chain the fragment
         * path rides, active whenever EITHER percent is nonzero, matching
         * the session's own raw != 0 gate), gyro stabilization (on AND a
         * log attached — [gyroActive] is the pipeline's own gate),
         * tonemap, LUT. A neutral grade is "none", never an empty list.
         */
        fun effects(grade: GradeK, gyroActive: Boolean): String {
            val parts = ArrayList<String>(6)
            if (grade.noiseReduction > 0f || grade.chromaDenoise > 0f) parts.add("nr")
            if (grade.sharpness != 0f || grade.detail != 0f) parts.add("unsharp")
            if (grade.temporalNr) parts.add("temporal")
            if (gyroActive) parts.add("gyro")
            if (grade.tonemap != 0) parts.add("tonemap")
            if (grade.lutId != null) parts.add("lut")
            return if (parts.isEmpty()) "none" else parts.joinToString("+")
        }
    }
}

/**
 * The MP4 export pipeline state machine (JVM-testable over fake
 * [Mp4Session]/[Mp4MediaWriter]; no Android imports):
 *
 *   open -> (read timeline) -> configure -> encode loop -> mux -> finalize
 *
 * Each phase advances the stage; a failure throws IllegalStateException
 * with the full stage + cause + recovery message (DESIGN.md Error states),
 * so the caller never sees a bare "failed". The finalize/rename decision
 * lives in the exporter, not here.
 */
class ExportPipeline(
    private val session: Mp4Session,
    private val writer: Mp4MediaWriter,
    /**
     * Optional per-export stage-timing log sink (owner request
     * 2026-11-13): non-null constructs one [ExportStageTiming] for the
     * encode, attaches it to the writer, and emits the sample/average/
     * summary lines through it. Null (the default) = timing fully off —
     * nothing is constructed, nothing is collected, the encoded bytes
     * are identical (see ExportStageTiming).
     */
    private val stageLog: ((String) -> Unit)? = null,
    /**
     * The RGB888 -> I420 conversion seam (the native YUV step): the
     * default dispatches to the byte-exact native twin with the Kotlin
     * YuvConverter.toI420 as fallback (see [NativeRgbToI420]); JVM tests
     * inject a pure fake. The conversion's BYTES are identical either
     * way — that is the seam's whole contract (core/src/Yuv.cpp).
     */
    private val converter: RgbToI420 = NativeRgbToI420,
    /**
     * The render backend the export job snapshotted (GPU deliverable F
     * core, owner request 2026-11-12 §10): GPU_EXPERIMENTAL asks the writer
     * for its encoder-Surface input mode BEFORE start (an explicit request
     * from the job, never auto) and offers each developed frame through
     * [Mp4MediaWriter.writeVideoFrameRgb] — when the writer takes it, the
     * I420 conversion is skipped entirely (the encoder consumes the
     * frame through its input Surface). CPU (the default) and AUTO both
     * take the byte-identical CPU path: AUTO stays CPU until the owner's
     * device parity verdict. A writer that cannot bring the Surface mode
     * up throws [SurfaceInputUnavailable] from start — the exporter turns
     * that into a clean whole-export CPU restart.
     */
    private val renderBackend: RenderBackend = RenderBackend.CPU,
    /**
     * The GPU develop leg's seam (M3 commit b): when renderBackend is
     * GPU_EXPERIMENTAL AND BackendRules says the grade's active stages are
     * ALL fragment-covered (a gyro-stabilized grade rides GLES like any
     * other covered grade since the gyro-warp port's seam leg — the
     * per-frame warp verdict threads the renders), the per-frame develop
     * runs
     * through the GPU develop session (M3 commit a) instead of
     * session.renderFrame's CPU develop — the SAME rotate/crop/YUV/writer
     * tail consumes the bytes either way (the GPU output is full-res
     * RGB888, row-major, NOT rotated — the same byte shape). The dispatch
     * order is cost-aware: the FREE coverage check runs first (an
     * uncovered grade never pays the probe/parity), then the probe, then
     * ONE case-0 parity run whose verdict fills BackendGates.parityOk
     * (NEVER GpuParityRules.gatesFrom — that one keeps parityOk false on
     * purpose; parity comes only from a real run). CPU and AUTO never touch
     * the seam: CPU is the byte-identical reference path, AUTO stays CPU
     * until the owner's device verdict (an honest policy line names it).
     * The default is the real native seam; JVM tests inject a fake
     * (nativeAvailable=false there, so the real object would throw ISE —
     * the dispatcher catches that and stays on CPU, the same guard every
     * seam in this file rides).
     */
    private val gpuDevelop: GpuDevelop = NativeGpuDevelop,
    /**
     * AUD-02's export gate: true (the default) = the source's audio link
     * state is ATTACHED, so the encode reads the clip's audio track exactly
     * as before — the encoded bytes are identical. False = DETACHED,
     * REMOVED, or LINKED_EDITED: the encode skips the audioInfo/readAudio
     * branch entirely (the same path a clip with no audio track takes),
     * producing a video-only file — the honest output for a source whose
     * audio the user has detached, removed, or replaced with an edited
     * recording (the edited recording itself is NOT muxed here; binding
     * edited audio needs the TIMELINE export, a later milestone — AUD-03's
     * exportStartMs truth rides that future export, not this clip export).
     * The exporter resolves this ONCE from the source's link record before
     * the engine runs; tests inject it directly.
     */
    private val audioAttached: Boolean = true,
    /**
     * The export resolution bound (owner request 2026-11-13): the job's
     * ladder choice, snapshotted at ENQUEUE and resolved here against the
     * source's effective size through ExportResolution.outputSize
     * (fits-within, aspect preserved, even-snapped) — the SAME rules the
     * sheet's chips showed, so the encode can never disagree with the
     * choice. Null (the default) = Original = the source's effective
     * size, byte-identical to every export before this feature: the scale
     * step is skipped entirely (not even a method dispatch on the
     * reference path — the RgbScale identity fast path never runs).
     * When set, the frame path gains one aspect-preserving
     * RgbScale.aspectFit step between crop and the surface-offer/I420
     * tail, and the writer's encoder dims are the resolved size. The
     * develop still runs at SOURCE resolution either way — a smaller
     * output changes the encode size, not the develop cost (no speed
     * promises).
     */
    private val resolutionW: Int? = null,
    /** [resolutionW]'s pair; both null or both set, never half a pair. */
    private val resolutionH: Int? = null,
    /**
     * (E1b) The job's plan run, non-null only for a job that carries
     * [ExportSettings]: the pipeline resolves the plan from it once the
     * encode size and the source fps are known and configures the writer
     * from it before start. Null (the default, every pre-E1 job) = the
     * writer's legacy configuration, byte-identical to before.
     */
    private val planRun: ExportPlanRun? = null,
) {

    /**
     * Runs the whole encode for the clip at [sourceFd] with [grade] into
     * [outPath] (the caller's staging path). [lutPath] is the job
     * snapshot's selected .cube path (null = no LUT, the old look;
     * COL-07) — resolved ONCE by the exporter and applied to every frame,
     * so the whole export is one consistent look. [gyroPath] is the
     * attached gyro log's path (gyro Phase 5) — resolved ONCE by the
     * exporter and applied to every frame: when the snapshot has
     * stabilization on AND a log is attached, every frame is warped at
     * full resolution before the RGB888 pack (the same per-frame warp the
     * paused preview shows; export is not tier-gated). Throws
     * IllegalStateException with a complete message on any failure.
     *
     * [onProgress] hears (videoFramesDone, totalVideoFrames) — reported
     * once with 0/total right after open, then after every encoded VIDEO
     * frame (audio blocks do not move the counter; the user thinks in
     * frames). The queue throttles what reaches the store.
     */
    fun run(
        sourceFd: Int,
        grade: GradeK,
        outPath: String,
        lutPath: String? = null,
        gyroPath: String? = null,
        manualRotationDeg: Int = 0,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ) {
        val info = session.open(sourceFd)
            ?: throw IllegalStateException(
                Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
            )
        try {
            encode(
                sourceFd, info, grade, outPath, lutPath, gyroPath, manualRotationDeg,
                onProgress,
            )
        } finally {
            session.close(info.handle)
        }
    }

    private fun encode(
        sourceFd: Int,
        info: Mp4SessionInfo,
        grade: GradeK,
        outPath: String,
        lutPath: String?,
        gyroPath: String?,
        manualRotationDeg: Int,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)?,
    ) {
        // 1. Timeline: video pts from the source, normalized to 0.
        val rawPts = ArrayList<Long>(info.frameCount)
        for (i in 0 until info.frameCount) {
            rawPts.add(session.framePts(info.handle, i))
        }
        if (rawPts.isEmpty()) {
            throw IllegalStateException(
                Mp4ExportErrors.message(
                    Mp4ExportErrors.CODE_UNSUPPORTED, Mp4ExportErrors.STAGE_OPEN,
                    "open: the clip holds no video frames"
                )
            )
        }
        val videoPts = PtsTimeline.videoPts(rawPts)

        // 2. Audio description + PCM (absent audio = video-only file).
        // AUD-02: audioAttached=false (DETACHED/REMOVED/LINKED_EDITED)
        // takes the null path on purpose — same as a clip with no audio
        // track, the writer gets sampleRate 0 and muxes no audio track.
        var sampleRate = 0
        var channels = 0
        var blocks: List<ShortArray> = emptyList()
        var audioPts: List<Long> = emptyList()
        val audio = if (audioAttached) session.audioInfo(info.handle) else null
        if (audio != null && audio.size >= 2 && audio[0] > 0 && audio[1] > 0) {
            sampleRate = audio[0]
            channels = audio[1]
            val pcm = session.readAudio(info.handle)
                ?: throw IllegalStateException(
                    Mp4ExportErrors.message(session.code(), session.stage(), session.detail())
                )
            blocks = chunk(pcm, channels)
            audioPts = (0 until blocks.size).map {
                PtsTimeline.audioBlockPts(it, AAC_BLOCK_FRAMES, sampleRate)
            }
        }

        // 3. Configure encoders + muxer (throws on a rejected format). A
        // throw here must still release the writer (the muxer may exist).
        // Rotation (owner request 2026-11-11 section 5): frames are
        // rotated UPRIGHT in the pixels below, so the encoder is
        // configured with the POST-rotation dimensions and NO muxer
        // orientation hint — a hint would make players rotate upright
        // pixels a second time. The effective orientation composes the
        // session's container value with the job's manual delta through
        // the one rotation source of truth.
        val effectiveDeg = RotationState.effectiveDeg(info.orientationDeg, manualRotationDeg)
        val outDims = RotationState.rotatedDims(info.width, info.height, effectiveDeg)
        // Crop + straighten (RAW-09): the writer's dims are the rotated
        // dims cut to the crop rect (even-snapped — the encoder wants
        // even YUV sizes; a neutral crop passes the rotated dims
        // through unchanged, byte-identical to the pre-crop stream).
        val cropRect = CropState.RectF(
            grade.cropX, grade.cropY, grade.cropW, grade.cropH,
        )
        val cropOutDims = CropState.outputDims(outDims.width, outDims.height, cropRect, even = true)
        // Export resolution (owner request 2026-11-13): when the job
        // carries a bound, the encoder dims come from it — resolved
        // against the crop/rotation-EFFECTIVE size through the SAME
        // rules the sheet's chips showed (ExportResolution.outputSize:
        // fits-within, aspect preserved, even-snapped — the size decision
        // never makes a framing decision, so a 4:3 source keeps 4:3).
        // Null = Original = cropOutDims, byte-identical to every export
        // before this feature (the chips' default IS today's behavior).
        val encodeDims = if (resolutionW != null && resolutionH != null) {
            ExportResolution.outputSize(
                ExportResolution.Kind.CUSTOM,
                cropOutDims,
                customWidth = resolutionW,
                customHeight = resolutionH,
            )
        } else {
            cropOutDims
        }
        // M3 commit b — the develop dispatch: WHICH pipeline develops the
        // frames. A GPU_EXPERIMENTAL job whose grade's active stages are
        // ALL fragment-covered (the SAME BackendRules vocabulary the
        // visibility line below uses — no second truth) develops through
        // the GPU session; every other shape develops through the CPU
        // session. Since the gyro warp port the warp is NO LONGER an
        // uncovered leg: the coverage check below says nothing about it,
        // and the frame loop resolves the per-frame plan on this thread
        // (gpuDevelop.resolveWarp) and threads the verdict into the GPU
        // renders — a gyro-stabilized export rides GLES like any other
        // covered grade. The order is
        // cost-aware: the coverage check is FREE and runs first (an
        // uncovered grade never pays the probe or the parity run — the
        // ONE exception is stage 24's runtime leg above, where the LUT
        // fact can only come from the probe itself); only a
        // covered grade pays the probe, then ONE case-0 fragment parity
        // run (the developLinear shape — the case the device PROVED at
        // 1 LSB; the grade's own shape is gated by the coverage check, so
        // the parity run is a capability gate, not a grade check). The
        // verdict fills BackendGates.parityOk directly — NEVER
        // GpuParityRules.gatesFrom, which keeps parityOk false on purpose
        // (parity comes only from a real run). CPU and AUTO never touch
        // the seam at all: CPU is the byte-identical reference path, AUTO
        // stays CPU until the owner's device verdict (its honest policy
        // line says so, never silent). The probe and the parity run
        // BEFORE writer.start() on this same engine thread: the harness
        // brings its own one-shot EGL context up and down INSIDE the
        // call (the PreviewMetrics precedent), and the writer binds ITS
        // context only in start() — running the parity after start would
        // put two contexts on one thread.
        // Stage 24's runtime leg (commit b): a LUT-active grade's coverage
        // depends on a fact only the probe knows — the RGBA16F 3D-texture
        // path is GLES 3.0 core, so contextOk && glesMajor >= 3. A
        // GPU_EXPERIMENTAL + LUT-active grade therefore pays the probe
        // BEFORE the coverage decision and the SAME fetched probe feeds
        // the gate block below (paid exactly once). Every other grade
        // keeps the M3 order: the coverage check is FREE and runs first,
        // so a non-LUT uncovered grade never pays the probe. The fact
        // defaults to false (CPU) and is flipped only by a real, parseable
        // probe — a malformed array never fabricates a healthy path.
        val lutActive = grade.lutId != null && grade.lutStrength > 0f
        var lutTexturePathOk = false
        var lutProbeRaw: DoubleArray? = null
        var lutProbeFetched = false
        if (renderBackend == RenderBackend.GPU_EXPERIMENTAL && lutActive) {
            lutProbeFetched = true
            try {
                lutProbeRaw = gpuDevelop.probe()
                if (lutProbeRaw != null) {
                    lutTexturePathOk = try {
                        val probe = GlesProbeResult.fromArray(lutProbeRaw)
                        probe.contextOk && probe.glesMajor >= 3
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                }
            } catch (e: IllegalStateException) {
                lutProbeRaw = null // no .so: the fact stays false, gate re-guards
            }
        }
        val uncoveredTokens = mutableListOf<String>()
        uncoveredTokens += BackendRules.fragmentUncoveredStages(grade, lutTexturePathOk).map { it.second }
        uncoveredTokens += BackendRules.fragmentUncoveredPreStages(grade)
        var developBackend = EffectiveRenderBackend.CPU
        var developFallback: String? = null
        var parityGateDetail: String? = null
        if (renderBackend == RenderBackend.GPU_EXPERIMENTAL && uncoveredTokens.isEmpty()) {
            var gates = BackendGates()
            try {
                val probeRaw = if (lutProbeFetched) lutProbeRaw else gpuDevelop.probe()
                if (probeRaw != null) {
                    val probe = GlesProbeResult.fromArray(probeRaw)
                    if (probe.contextOk && probe.floatFboOk) {
                        val parityRaw = gpuDevelop.fragmentParity(sourceFd)
                        val parity = if (parityRaw != null) GlesParityResult.fromArray(parityRaw) else null
                        // The parity verdict's own detail (the parity-fix
                        // observability): the gate keeps ONLY .pass, so the
                        // harness's why — the GL step + error for a run that
                        // refused, the thresholds' numbers for a run that
                        // failed them — is captured HERE into the companion
                        // line and logged beside the fallback line. The
                        // verdict itself is computed EXACTLY as before
                        // (same call, same inputs): parityOk's semantics
                        // stay byte-identical, the gates decide nothing new.
                        val parityDetail = gpuDevelop.lastDetail()
                        val parityVerdict =
                            parity?.let { GpuParityRules.verdict(it, parityDetail) }
                        parityGateDetail = GpuParityRules.gateDetailLine(
                            parityPaid = true,
                            parity = parity,
                            errorDetail = parityDetail,
                        )
                        gates = BackendGates(
                            contextOk = true,
                            computeOk = probe.computeOk,
                            floatFboOk = true,
                            parityOk = parityVerdict?.pass ?: false,
                        )
                    } else {
                        // Parity is skipped when the context/float-FBO gates
                        // are red (running it would only produce a
                        // predictable failure) — parityOk stays false.
                        gates = BackendGates(
                            contextOk = probe.contextOk,
                            computeOk = probe.computeOk,
                            floatFboOk = probe.floatFboOk,
                        )
                    }
                }
            } catch (e: IllegalStateException) {
                // No .so (JVM / non-NDK build): the honest all-false
                // default gates keep the export on CPU — the same guard
                // every native seam in this file rides.
                gates = BackendGates()
            }
            developBackend = BackendRules.fragmentEffectiveBackend(
                renderBackend, gates, grade, lutTexturePathOk,
            )
            developFallback = BackendRules.fragmentFallbackLine(
                renderBackend, gates, grade, lutTexturePathOk,
            )
        } else if (renderBackend == RenderBackend.GPU_EXPERIMENTAL) {
            // An active uncovered stage/pre-stage forces CPU
            // BEFORE any probe is paid. The line names the stage tokens
            // ONLY — never gate tokens, because no gate was measured
            // (naming "no-egl-context" for a probe that never ran would
            // fabricate a measurement).
            developFallback = developFallbackLine(uncoveredTokens.joinToString(","))
        } else if (renderBackend == RenderBackend.AUTO) {
            // AUTO stays CPU for the develop until the owner's device
            // verdict moves the picker — the honest policy line, never a
            // silent skip.
            developFallback = developFallbackLine("auto-stays-cpu")
        }
        // M5 — the encoder-surface geometry gate, computed BEFORE the
        // writer request/start (the single decision point): the session
        // blit is 1:1-pixels only, so the frame must reach the encoder
        // with NO crop, NO straighten and NO scale — rotation is now
        // TAUGHT (the uRotation fold, 0/90/180/270 — the normalize(deg)
        // set), so the eligibility is "rotation is a multiple of 90 AND
        // the develop output, rotated, IS the encode input": for 90/270
        // the encode dims are the source's SWAPPED dims (outDims —
        // RotationState's rotatedDims, the single rotation source of
        // truth), and the session blit swaps the destination viewport to
        // match. At 0/180 outDims IS the source dims, so this reduces to
        // the pre-rotation gate byte-for-byte. gpuDevelopActive is
        // not known yet (the probe/parity run above fills developBackend
        // synchronously — it IS known: developBackend was computed before
        // this line), so the gate uses the same developBackend the frame
        // loop will. A not-eligible GPU_EXPERIMENTAL job takes the
        // writer's OWN Surface mode (requestEncoderSurfaceInput, the
        // shipped path) or the byte path — byte-identical to before.
        val encoderDrawEligible = developBackend == EffectiveRenderBackend.GLES &&
            effectiveDeg % 90 == 0 &&
            CropState.isFull(cropRect) &&
            grade.straightenDeg == 0f &&
            encodeDims.width == outDims.width &&
            encodeDims.height == outDims.height
        // GPU deliverable F (§10): the Surface request goes to the writer
        // BEFORE start — an explicit request from the job's render-backend
        // snapshot, never auto. A writer that cannot bring the mode up
        // throws SurfaceInputUnavailable from start (the exporter's clean
        // CPU restart); the default no-op means CPU bytes, unchanged.
        // M5: an encoderDrawEligible job requests the session-owns-EGL
        // mode INSTEAD — the writer hands the codec input Surface out
        // unwrapped and the GPU develop session becomes the producer.
        if (renderBackend == RenderBackend.GPU_EXPERIMENTAL) {
            if (encoderDrawEligible) {
                writer.requestSessionEncoderSurface()
            } else {
                writer.requestEncoderSurfaceInput()
            }
        }
        // E1d: true when the resolved plan is a 10-bit one (GPU encoder-surface only).
        var tenBit = false
        try {
            val run = planRun
            if (run != null) {
                // E1b: the plan for THIS encode size and the clip's own fps
                // (never a setting). An unresolvable plan fails here, before
                // any encoder exists, with the planner's reason.
                val fps = ExportFps.fromPts(rawPts)
                // 10-bit is GPU-only (see TenBitUnavailable): it needs the GPU
                // develop session on the encoder-surface fast path (1:1 pixels,
                // every grade stage on the GPU) AND an RGBA1010102 EGL config
                // the device actually offers (probed by query, never assumed).
                // Anything else plans 8-bit with TEN_BIT_UNAVAILABLE on the report.
                val wantsTen = run.current.bitDepth == 10
                val tenBitPath = wantsTen && renderBackend == RenderBackend.GPU_EXPERIMENTAL &&
                    encoderDrawEligible && try {
                        gpuDevelop.tenBitProbe()
                    } catch (e: IllegalStateException) {
                        false
                    }
                if (wantsTen) {
                    run.note(
                        "ten-bit path: gpu=${renderBackend == RenderBackend.GPU_EXPERIMENTAL} " +
                            "encoderSurface=$encoderDrawEligible available=$tenBitPath"
                    )
                }
                val plan = run.planFor(
                    encodeDims.width, encodeDims.height, fps,
                    ExportContext(tenBitPathAvailable = tenBitPath),
                )
                run.durationSec = (videoPts.last() / 1_000_000.0) + 1.0 / fps
                tenBit = plan.bitDepth == 10
                if (!plan.ok) throw IllegalStateException(plan.errorMessage())
                writer.expectVideoConfig()
                writer.configureVideo(plan)
            }
            writer.start(outPath, encodeDims.width, encodeDims.height, 0, sampleRate, channels)
            run?.encoder = writer.encoderIdentity()
        } catch (e: Throwable) {
            writer.release()
            throw e
        }
        // M3 commit b — the GPU develop session: opened ONCE per export
        // when the dispatch chose GLES (compile/allocate happen there —
        // per-frame work is uploads and uniforms only, the commit-a
        // contract). Open failure (0 or a throw) is NOT an export failure:
        // the export continues on the CPU develop with the honest fallback
        // line — the JNI error string itself says "(recovery: the CPU
        // develop path stays the default)". The handle is declared BEFORE
        // the try so the finally below closes it on EVERY path;
        // closeSession is idempotent and absorbs context loss at teardown.
        var gpuSessionHandle = 0L
        var gpuDevelopActive = developBackend == EffectiveRenderBackend.GLES
        // M5 — the session encoder path's state: requested (the
        // session-owns-EGL mode went to the writer), attached (the
        // session accepted the input surface). The attach happens ONCE
        // below, after openSession; a refusal falls back to the writer's
        // adopt path (the CPU-RGB Surface mode) with ONE honest line —
        // the export NEVER dies on this seam.
        var encoderSurfaceRequested = encoderDrawEligible
        var encoderSurfaceAttached = false
        try {
            if (gpuDevelopActive) {
                try {
                    gpuSessionHandle = if (tenBit) gpuDevelop.openSessionTenBit() else gpuDevelop.openSession()
                } catch (e: IllegalStateException) {
                    // No .so: the honest CPU continue (the seam's guard).
                }
                if (gpuSessionHandle == 0L && tenBit) {
                    // No CPU continue for a 10-bit plan: the CPU develop is 8-bit.
                    throw TenBitUnavailable(
                        "the 10-bit GPU session could not open (" +
                            (gpuDevelop.lastDetail() ?: "no detail") + ")"
                    )
                }
                if (gpuSessionHandle == 0L) {
                    gpuDevelopActive = false
                    stageLog?.invoke(
                        developFallbackLine(gpuDevelop.lastDetail() ?: "develop session open failed")
                    )
                }
            }
            // M5 — attach the codec input Surface to the session, ONCE
            // per export. Only when the geometry gate passed AND the GPU
            // develop session actually opened (a session that never
            // opened cannot blit). The request already went to the
            // writer, so a session-mode writer holds a Surface-configured
            // codec whose input buffers NOBODY can feed on the byte path
            // (dequeueInputBuffer fails on a Surface-configured codec) —
            // the else-if below ADOPTS the surface into the writer's own
            // EGL so frames flow through the EXISTING writeVideoFrameRgb
            // offer instead. The export NEVER dies on this seam.
            if (encoderSurfaceRequested && gpuDevelopActive) {
                val inputSurface = writer.encoderInputSurface()
                if (inputSurface == null && tenBit) {
                    throw TenBitUnavailable("the writer had no encoder input surface for the 10-bit path")
                }
                if (inputSurface == null) {
                    // The writer could not create the codec input surface
                    // (its start already logged/throws per its contract);
                    // fall back to the writer's own modes with ONE line.
                    encoderSurfaceRequested = false
                    stageLog?.invoke(
                        "encoder surface fallback: requested=surface-gpu effective=bytes " +
                            "reason=writer-had-no-input-surface"
                    )
                } else {
                    val attached = try {
                        gpuDevelop.setEncoderSurface(gpuSessionHandle, inputSurface)
                    } catch (e: IllegalStateException) {
                        false // no .so mid-export: the adopt path below
                    }
                    if (!attached && tenBit) {
                        // The adopt fallback below feeds 8-bit RGB: for 10-bit that
                        // would be an 8-bit image in a 10-bit file. Refuse instead.
                        throw TenBitUnavailable(
                            "the 10-bit session refused the encoder surface (" +
                                (gpuDevelop.lastDetail() ?: "no detail") + ")"
                        )
                    }
                    if (attached) {
                        encoderSurfaceAttached = true
                    } else {
                        // The session refused (its GL thread named the
                        // step). The ADOPT fallback: the writer does the
                        // steps its session mode skipped — wraps the SAME
                        // input surface in its own EGL (the recordable
                        // path it proved on device) and frames flow
                        // through the EXISTING writeVideoFrameRgb offer
                        // (the CPU-RGB Surface mode, byte-identical to
                        // the shipped path). adoptEncoderSurface throws
                        // SurfaceInputUnavailable on failure = the
                        // exporter's clean whole-export CPU restart.
                        writer.adoptEncoderSurface()
                        encoderSurfaceRequested = false
                        stageLog?.invoke(
                            "encoder surface fallback: requested=surface-gpu effective=surface " +
                                "reason=" + (gpuDevelop.lastDetail()
                                    ?: "session refused the encoder surface") +
                                " (writer adopted the input surface)"
                        )
                    }
                }
            } else if (encoderSurfaceRequested && tenBit) {
                throw TenBitUnavailable("the GPU develop session was not active for the 10-bit path")
            } else if (encoderSurfaceRequested) {
                // The gap case: the geometry gate passed and the request
                // went to the writer, but the GPU develop session never
                // opened (openSession returned 0 — the honest CPU-continue
                // line above already named it). The writer holds a
                // Surface-configured codec that the byte path CANNOT feed
                // (dequeueInputBuffer fails on a Surface-configured
                // codec), so the writer ADOPTS the surface into its own
                // EGL here and the frames flow through the EXISTING
                // writeVideoFrameRgb offer — the CPU-RGB Surface mode,
                // byte-for-byte the shipped path. adoptEncoderSurface
                // throws SurfaceInputUnavailable on failure = the
                // exporter's clean whole-export CPU restart.
                writer.adoptEncoderSurface()
                encoderSurfaceRequested = false
                stageLog?.invoke(
                    "encoder surface fallback: requested=surface-gpu effective=surface " +
                        "reason=develop-session-unavailable (writer adopted the input surface)"
                )
            }
            // 4. Encode loop, in presentation order across both tracks.
            // Progress counts VIDEO frames only (the frame counter the user
            // sees); it starts once the encoders are actually configured —
            // a rejected configure would otherwise show 0/N "progress".
            // Stage timing (owner request 2026-11-13): one sink per export,
            // attached to the writer BEFORE the loop so its internals are
            // attributed to the right frame; null = timing off entirely.
            val timing = if (stageLog != null) ExportStageTiming(stageLog) else null
            timing?.let { writer.attachTiming(it) }
            val tEncode0 = System.nanoTime()
            onProgress?.invoke(0, videoPts.size)
            var done = 0
            // GRADE PARCEL (commit b): the grade crosses the encode loop as
            // ONE immutable packet, encoded ONCE before the first frame —
            // never re-encoded per frame. The grade is fixed for the whole
            // export (the snapshot captured at enqueue), so the parcel is
            // too. The parcel twins below decode it once per call on the
            // native side; the flat per-field threading is gone (the old
            // door is deleted).
            val gradeParcel = GradeParcel.encode(grade)
            for (s in PtsTimeline.interleave(videoPts, audioPts)) {
                when (s.track) {
                    PtsTimeline.Sample.Track.VIDEO -> {
                        timing?.startFrame()
                        val t0 = System.nanoTime()
                        // The gyro warp port's commit b — the per-frame plan
                        // resolution. The GPU renders consume the warp verdict
                        // (active flag + the composite M), so it is resolved
                        // HERE, per frame, on this engine thread (the plan
                        // cache and the thread-local gyro cache are not
                        // GL-thread-safe; the render only READS the pair).
                        // The six request values are the IDENTICAL ones the
                        // CPU seam threads (jniExportFrameParcel above): the gyro
                        // path gated on stabilization-on + attached log,
                        // strength /100, mode/zoomLimit/focalMm/trimMs raw.
                        // maxDim = 0 = full resolution (the export renders at
                        // the clip's own dims — the applyStabilization
                        // convention). Null verdict (no log, dead handle,
                        // parse failure) folds to warpActive=false + identity
                        // M — the session then SKIPS draw 10 entirely (the
                        // identity no-draw rule), byte-identical to the CPU's
                        // honest no-op. The CPU branches below never touch
                        // this: they keep threading the six values into
                        // jniExportFrameParcel, whose bridge folds the same way.
                        val warp = if (gpuDevelopActive || encoderSurfaceAttached) {
                            try {
                                gpuDevelop.resolveWarp(
                                    info.handle,
                                    if (StabilizationGate.active(grade.stabilization, gyroPath)) gyroPath else null,
                                    grade.stabilizationMode,
                                    if (StabilizationGate.active(grade.stabilization, gyroPath)) {
                                        grade.stabilizationStrength / 100f
                                    } else {
                                        0f
                                    },
                                    grade.stabilizationZoomLimit,
                                    grade.stabilizationFocalMm,
                                    grade.stabilizationTrimMs,
                                    s.frameIndex,
                                    0,
                                )
                            } catch (e: IllegalStateException) {
                                null // no .so mid-export: the renders latch below
                            }
                        } else {
                            null
                        }
                        val warpActive = warp != null && warp[0] != 0.0f
                        val warpM = if (warp != null && warp.size >= 10) {
                            warp.copyOfRange(1, 10)
                        } else {
                            floatArrayOf(
                                1f, 0f, 0f,
                                0f, 1f, 0f,
                                0f, 0f, 1f,
                            )
                        }
                        // M5 — the encoder-surface fast path: the session
                        // develops AND blits straight to the codec input
                        // surface on its GL thread, stamping the frame's
                        // normalized pts as media time. NO readback bytes
                        // cross the seam, no crop/scale runs (the geometry
                        // gate guaranteed 1:1 pixels), and rotation rides
                        // the blit's uRotation fold (the gate guaranteed a
                        // 90-multiple; effectiveDeg is the same normalized
                        // value the gate checked — 0 is the byte-identical
                        // upright copy). The codec drain follows per the
                        // Surface-input contract. A refusal latches the
                        // fast path OFF
                        // with ONE honest fallback line naming the reason.
                        // An encoder-render failure ALSO latches the
                        // session unhealthy natively (mid-render GL
                        // failure semantics) — so the GPU readback below
                        // dies with it and the CPU develop takes the
                        // rest of the frames. The writer still holds a
                        // Surface-configured codec the byte path CANNOT
                        // feed, and the session's EGL window surface is
                        // still the BufferQueue's producer (the
                        // single-producer rule): detach the session's
                        // surface first (a Surface job always processes,
                        // even on a latched session), then ADOPT the
                        // surface into the writer's own EGL — the frames
                        // then flow through the EXISTING writeVideoFrameRgb
                        // offer, byte-for-byte the shipped CPU-RGB Surface
                        // mode. adoptEncoderSurface throws
                        // SurfaceInputUnavailable on failure = the
                        // exporter's clean whole-export CPU restart; the
                        // export NEVER dies on this seam. Later frames
                        // stay silent (the same one-line rule the develop
                        // latch rides).
                        if (encoderSurfaceAttached) {
                            val drew = try {
                                gpuDevelop.renderFrameToEncoderParcel(
                                    gpuSessionHandle, info.handle, s.frameIndex, s.ptsUs,
                                    gradeParcel,
                                    lutPath, grade.lutStrength / 100f,
                                    grade.lutInterp?.role?.value ?: -1,
                                    grade.lutInterp?.input?.gamut?.value ?: -1,
                                    grade.lutInterp?.input?.transfer?.value ?: -1,
                                    grade.lutInterp?.output?.gamut?.value ?: -1,
                                    grade.lutInterp?.output?.transfer?.value ?: -1,
                                    warpActive, warpM,
                                    effectiveDeg,
                                )
                            } catch (e: IllegalStateException) {
                                false // no .so mid-export: latch below
                            }
                            if (drew) {
                                writer.drainEncoderInput()
                                timing?.endFrame()
                                done++
                                onProgress?.invoke(done, videoPts.size)
                                continue
                            }
                            if (tenBit) {
                                // No adoption of an 8-bit CPU feed into a 10-bit file.
                                throw TenBitUnavailable(
                                    "the 10-bit GPU render failed (" +
                                        (gpuDevelop.lastDetail() ?: "no detail") + ")",
                                    midStream = done > 0,
                                )
                            }
                            encoderSurfaceAttached = false
                            try {
                                gpuDevelop.setEncoderSurface(gpuSessionHandle, null)
                            } catch (e: IllegalStateException) {
                                // No .so: nothing to detach (unreachable
                                // when a session opened — defensive only).
                            }
                            writer.adoptEncoderSurface()
                            stageLog?.invoke(
                                "encoder surface fallback: requested=surface-gpu " +
                                    "effective=surface reason=" +
                                    (gpuDevelop.lastDetail()
                                        ?: "gpu develop encoder render failed") +
                                    " (writer adopted the input surface after " +
                                    "the encoder render failure)"
                            )
                        }
                        // Commit b — the develop dispatch per frame: the GPU
                        // session renders when it is active; the bytes flow
                        // through the SAME rotate/crop/offer/I420 tail below
                        // (the GPU output is the same full-res RGB888,
                        // row-major, NOT-rotated byte shape). Mid-render
                        // null (or a size mismatch — a wrong-shaped buffer
                        // is a GPU failure, never a corrupt frame) latches
                        // the CPU develop for the REST of the export with
                        // ONE fallback line: the session latches unhealthy
                        // natively (sticky), the first failure already
                        // named its step, and the ≤1-LSB parity contract
                        // makes the mid-stream switch visually invisible.
                        // The CPU branch stays byte-identical to the
                        // reference path (the same call, the same args).
                        var rgb: ByteArray
                        if (gpuDevelopActive) {
                            val gpu = try {
                                gpuDevelop.renderFrameParcel(
                                    gpuSessionHandle, info.handle, s.frameIndex,
                                    gradeParcel,
                                    lutPath, grade.lutStrength / 100f,
                                    grade.lutInterp?.role?.value ?: -1,
                                    grade.lutInterp?.input?.gamut?.value ?: -1,
                                    grade.lutInterp?.input?.transfer?.value ?: -1,
                                    grade.lutInterp?.output?.gamut?.value ?: -1,
                                    grade.lutInterp?.output?.transfer?.value ?: -1,
                                    warpActive, warpM,
                                )
                            } catch (e: IllegalStateException) {
                                null // no .so mid-export: latch CPU below
                            }
                            if (gpu == null || gpu.size != info.width * info.height * 3) {
                                gpuDevelopActive = false
                                stageLog?.invoke(
                                    developFallbackLine(
                                        gpuDevelop.lastDetail() ?: "gpu develop render failed"
                                    )
                                )
                                rgb = session.renderFrame(info.handle, s.frameIndex, grade, lutPath, gyroPath)
                                    ?: throw IllegalStateException(
                                        Mp4ExportErrors.message(
                                            session.code(), session.stage(), session.detail()
                                        )
                                    )
                            } else {
                                rgb = gpu
                            }
                        } else {
                            rgb = session.renderFrame(info.handle, s.frameIndex, grade, lutPath, gyroPath)
                                ?: throw IllegalStateException(
                                    Mp4ExportErrors.message(
                                        session.code(), session.stage(), session.detail()
                                    )
                                )
                        }
                        timing?.mark(ExportStageTiming.STAGE_NATIVE, System.nanoTime() - t0)
                        // Commit (d): the bridge timed its six internals
                        // into thread-local slots during that call (debug
                        // builds only) — read them NOW, on this same
                        // thread, before anything else can overwrite them.
                        // Null (fake / release / non-NDK) renders the
                        // sample line exactly as before. The GPU develop
                        // path does NOT read them: the develop session
                        // does not populate the CPU develop's timing slots
                        // (stale numbers would be dishonest), so the
                        // sample line stays plain — the develop stage is
                        // attributed to STAGE_NATIVE above either way.
                        if (!gpuDevelopActive) {
                            timing?.markNativeSubStages(session.lastFrameSubStagesMs())
                        }
                        // Section 5: renderFrame returns NOT-rotated RGB888;
                        // rotating by the EFFECTIVE orientation makes the
                        // pixels themselves upright (the encoder wants
                        // upright video — players that ignore rotation
                        // metadata still show it right), then the I420
                        // conversion runs on the rotated buffer/dims.
                        // RAW-09: the crop (and straighten) applies on the
                        // upright pixels — same space the rect lives in.
                        // Neutral crop returns the upright buffer by
                        // reference (no copy, no visible change).
                        val t1 = System.nanoTime()
                        val upright = RotationState.rotateRgb888(rgb, info.width, info.height, effectiveDeg)
                        timing?.mark(ExportStageTiming.STAGE_ROTATE, System.nanoTime() - t1)
                        val t2 = System.nanoTime()
                        val cropped = CropState.applyToRgb(
                            rgb = upright.rgb,
                            width = upright.width,
                            height = upright.height,
                            rect = cropRect,
                            straightenDeg = grade.straightenDeg,
                            spaceDeltaDeg = 0,
                            even = true,
                        )
                        timing?.mark(ExportStageTiming.STAGE_CROP, System.nanoTime() - t2)
                        // Export resolution (owner request 2026-11-13): the
                        // scale step — ONLY when the job carries a bound.
                        // Aspect-PRESERVING fit (RgbScale.aspectFit, never
                        // aspectFill's center-crop: the size decision never
                        // makes a framing decision), and because
                        // encodeDims was resolved through the SAME
                        // fits-within rules against the same effective
                        // size, the fit is exact — no bars, no crop, just
                        // the resize. Null bound = Original: the step is
                        // skipped entirely, byte-identical to the reference
                        // path (not even a method dispatch).
                        val scaled = if (resolutionW != null && resolutionH != null &&
                            (cropped.width != encodeDims.width || cropped.height != encodeDims.height)
                        ) {
                            RgbScale.aspectFit(
                                cropped.rgb, cropped.width, cropped.height,
                                encodeDims.width, encodeDims.height,
                            )
                        } else {
                            cropped.rgb
                        }
                        val frameW = if (resolutionW != null && resolutionH != null) {
                            encodeDims.width
                        } else {
                            cropped.width
                        }
                        val frameH = if (resolutionW != null && resolutionH != null) {
                            encodeDims.height
                        } else {
                            cropped.height
                        }
                        val t3 = System.nanoTime()
                        // GPU deliverable F (§10): offer the frame to the
                        // writer's encoder-Surface mode FIRST — but only
                        // for a job that requested the mode (the same
                        // explicit GPU_EXPERIMENTAL request, never auto):
                        // the real writer treats an offer in CPU mode as a
                        // mode-confusion bug, so a CPU export must not make
                        // the call at all (byte-identical: not even a
                        // method dispatch on the reference path). True = the
                        // frame was taken (the encoder consumes it through
                        // its input Surface — no I420 conversion, no
                        // full-frame GPU->CPU->encoder round trip; STAGE_YUV
                        // stays 0, honestly: no conversion ran). False (a
                        // writer without the mode) falls through to the
                        // byte-identical I420 path.
                        if (renderBackend == RenderBackend.GPU_EXPERIMENTAL &&
                            writer.writeVideoFrameRgb(s.ptsUs, scaled, frameW, frameH)
                        ) {
                            timing?.endFrame()
                            done++
                            onProgress?.invoke(done, videoPts.size)
                            continue
                        }
                        // Direct-to-plane first (commit (c)): when the writer
                        // offers the encoder Image's plane buffers, the
                        // conversion writes them directly — no packed
                        // intermediate, no second copy. Any refusal falls
                        // back to the packed path (writeVideoFrame), which
                        // produces the same bytes through the writer's
                        // copy. The dual-run rides whichever path ran.
                        val planes = writer.acquireVideoFramePlanes()
                        if (planes != null) {
                            val tAcquire = System.nanoTime() - t3
                            val wrote = converter.convertToPlanes(scaled, frameW, frameH, planes)
                            if (wrote != null) {
                                // The debug dual-run reads the encoder memory
                                // back and compares against the Kotlin oracle
                                // — proving the stride mapping too.
                                if (stageLog != null && RgbToI420Verify.shouldDualRun(nativeFramesSeen, wrote.fromNative)) {
                                    val kt = YuvConverter.toI420(scaled, frameW, frameH)
                                    val got = YuvPlaneIo.readPlanesToPacked(frameW, frameH, planes)
                                    RgbToI420Verify.line(frameW, frameH, got, kt)?.let { stageLog(it) }
                                }
                                if (wrote.fromNative) nativeFramesSeen++
                                // The yuv slot covers ONLY the conversion — the
                                // writer's own stages (enc_wait / plane_copy /
                                // drain) are marked by the writer at queue
                                // time, so the slots stay disjoint.
                                timing?.mark(ExportStageTiming.STAGE_YUV, System.nanoTime() - t3 - tAcquire)
                                writer.queueVideoFrame(s.ptsUs)
                            } else {
                                writer.abandonVideoFrame()
                                // Packed fallback: identical bytes, and the
                                // same dual-run/counter bookkeeping.
                                val converted = convertFrame(scaled, frameW, frameH)
                                timing?.mark(ExportStageTiming.STAGE_YUV, System.nanoTime() - t3 - tAcquire)
                                writer.writeVideoFrame(s.ptsUs, converted.i420)
                            }
                        } else {
                            val converted = convertFrame(scaled, frameW, frameH)
                            timing?.mark(ExportStageTiming.STAGE_YUV, System.nanoTime() - t3)
                            writer.writeVideoFrame(s.ptsUs, converted.i420)
                        }
                        // The writer's own stages (enc_wait / plane_copy /
                        // drain_mux) are marked INTO this timing object by
                        // whichever writer call just ran (writeVideoFrame or
                        // queueVideoFrame), before endFrame reads them.
                        timing?.endFrame()
                        done++
                        onProgress?.invoke(done, videoPts.size)
                    }
                    PtsTimeline.Sample.Track.AUDIO ->
                        writer.writeAudioBlock(s.ptsUs, blocks[s.frameIndex])
                }
            }
            // 5. Drain + stop the muxer.
            if (!writer.finish()) {
                throw IllegalStateException(
                    Mp4ExportErrors.kotlinMessage(
                        Mp4ExportErrors.STAGE_MUX,
                        "the movie could not be finished",
                        "retry the export",
                    )
                )
            }
            timing?.close(
                sourceW = info.width,
                sourceH = info.height,
                rotatedW = outDims.width,
                rotatedH = outDims.height,
                encodedW = encodeDims.width,
                encodedH = encodeDims.height,
                grade = grade,
                gyroActive = StabilizationGate.active(grade.stabilization, gyroPath),
                encodeNanos = System.nanoTime() - tEncode0,
            )
        } finally {
            // Render-backend visibility (owner request 2026-11-13, deliverable
            // (c)): ONE honest line per export, emitted HERE — after the
            // encode loop — because the develop axis is only knowable then:
            // effective=GLES means the GPU develop session rendered the
            // frames (gpuDevelopActive survived to the end); effective=CPU
            // covers every other shape, including a GPU request whose probe,
            // parity, session open, or a mid-render failure latched the CPU
            // develop (each of those already logged its own fallback line
            // with the reason — this line reports the final state, the two
            // are complementary, never duplicated truth). The finally covers
            // success, mid-encode failure, and finish() failure alike; the
            // one path it stays silent on is a writer.start() throw — that
            // export attempt never began, and the exporter's
            // SurfaceInputUnavailable restart re-runs with its own line, so
            // the restart still reads one line per export. The unsupported=
            // tokens are the exact BackendRules fragment vocabulary for THIS
            // grade — the SAME [uncoveredTokens] list the develop dispatch
            // gated on (no second truth) — so the line doubles as the
            // coverage statement: "none" is the default grade after the
            // stage 2+3+23 ports. CPU exports log the same line
            // (effective=CPU): the requested axis is visible per export,
            // never only on fallback. The encoder-input mode rides the
            // SAME line (M5, append-only: encoder-input=surface-gpu /
            // surface / bytes) — surface-gpu when the session blitted
            // straight to the codec input surface, surface when the
            // writer's own EGL took the CPU-RGB frames, bytes otherwise —
            // read BEFORE writer.release() below (release tears the mode
            // down). Null stageLog = nothing emitted, bytes identical,
            // release pays nothing.
            if (stageLog != null) {
                val effective = if (gpuDevelopActive) {
                    EffectiveRenderBackend.GLES
                } else {
                    EffectiveRenderBackend.CPU
                }
                stageLog(
                    "render backend: requested=${renderBackend.name} " +
                        "effective=${effective.name} " +
                        "unsupported=${uncoveredTokens.joinToString(",").ifEmpty { "none" }} " +
                        // M5 (append-only): the encoder input mode this
                        // export actually used — surface-gpu when the
                        // session blitted straight to the codec input
                        // surface, surface when the writer's own EGL took
                        // the CPU-RGB frames, bytes otherwise. The state
                        // is read HERE (the finally) because the frame
                        // loop is what proves it: attached survived means
                        // every frame blitted; a mid-stream refusal
                        // already logged its own fallback line.
                        "encoder-input=" + when {
                            encoderSurfaceAttached -> "surface-gpu"
                            writer.surfaceInputActive() -> "surface"
                            else -> "bytes"
                        }
                )
                // The develop dispatch's honest fallback line (commit b):
                // null when the GPU develop ran (or CPU was requested — no
                // fallback happened). The reason names the coverage tokens,
                // the gate tokens, or the AUTO policy — whichever forced the
                // CPU develop, never silently.
                developFallback?.let { stageLog(it) }
                // The parity gate's companion line (the parity-fix
                // observability): the verdict's own detail the fallback
                // line's token vocabulary cannot carry — the GL step +
                // error for a run that refused, the thresholds' numbers
                // for a run that failed them, or the seam's own error
                // when the call returned no array. Null when the parity
                // leg was never paid or the verdict passed — no fallback
                // happened, so nothing rides beside nothing.
                parityGateDetail?.let { stageLog(it) }
                // The encoder-input mode's own line (commit (c), kept
                // verbatim for log parsers): surface when the writer's own
                // EGL took the frames, bytes otherwise. The M5 surface-gpu
                // mode rides the render-backend line above (encoder-input=)
                // — this line reports the WRITER's axis only.
                stageLog(
                    if (writer.surfaceInputActive()) "encoder input: surface" else "encoder input: bytes"
                )
            }
            // The GPU develop session closes on EVERY path — success,
            // mid-encode failure, writer failure — beside the writer's
            // release (the commit-a contract: close drains the one
            // in-flight render, joins the GL thread, and is idempotent; a
            // 0 handle means no session was ever opened). The session NEVER
            // outlives the export: the registry slot is freed for the next
            // export before this method returns.
            if (gpuSessionHandle != 0L) {
                try {
                    gpuDevelop.closeSession(gpuSessionHandle)
                } catch (e: IllegalStateException) {
                    // No .so at teardown: nothing to close.
                }
                gpuSessionHandle = 0L
            }
            writer.release()
        }
    }

    /**
     * The develop dispatch's fallback line (commit b): the SAME format the
     * exporter's surface restart uses, so the export log reads uniformly.
     * The reason is the honest why — coverage tokens, gate tokens, the
     * AUTO policy, or the develop session's own failure detail.
     */
    private fun developFallbackLine(reason: String): String =
        "render backend fallback: requested=${renderBackend.name} " +
            "effective=CPU reason=$reason"

    /**
     * One frame's conversion through the seam, plus the debug dual-run
     * (the byte-exactness affordance): the FIRST frame produced by the
     * native path of an export is converted by Kotlin too, and the
     * comparison lands in the stage-timing sink (the app wires it to
     * Log.i under "ritim-export", DEBUG builds only — a null stageLog
     * means the dual-run never runs, so release exports pay nothing).
     * The native bytes always win: a mismatch is logged, never applied.
     */
    private fun convertFrame(rgb: ByteArray, width: Int, height: Int): RgbToI420Result {
        val traced = converter.convert(rgb, width, height)
        if (stageLog != null && RgbToI420Verify.shouldDualRun(nativeFramesSeen, traced.fromNative)) {
            val kt = YuvConverter.toI420(rgb, width, height)
            RgbToI420Verify.line(width, height, traced.i420, kt)?.let { stageLog(it) }
        }
        if (traced.fromNative) nativeFramesSeen++
        return traced
    }

    /** Native-produced frames so far this export (the dual-run counter). */
    private var nativeFramesSeen = 0

    /** Splits interleaved PCM into AAC-sized blocks (last one may be short). */
    private fun chunk(pcm: ShortArray, channels: Int): List<ShortArray> {
        val frame = channels * AAC_BLOCK_FRAMES
        val out = ArrayList<ShortArray>((pcm.size + frame - 1) / frame)
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + frame, pcm.size)
            out.add(pcm.copyOfRange(i, end))
            i = end
        }
        return out
    }
}

/**
 * Exports a clip as an SDR H.264/AAC MP4 through the native decode path
 * (full-res developCpu per frame -> RGB888 -> I420 -> MediaCodec video/avc;
 * clip PCM -> audio/mp4a-latm; MediaMuxer). Rotation (owner request
 * 2026-11-11 section 5): every frame is rotated UPRIGHT in the pixels by
 * the clip's effective orientation (container + manual delta,
 * RotationState) and the muxer hint is dropped — the encoder receives
 * post-rotation dimensions, so even players that ignore rotation
 * metadata show the video right-side up.
 *
 * The whole pipeline runs on the shared export engine thread
 * ([ExportEngine]) — one motioncam decoder is not concurrency-safe and the
 * bridge's error state is thread-local. One job at a time.
 *
 * Staging per the spec: the muxer writes to `out.partial`; only a finished
 * export is renamed to [out]. A failure deletes the partial and leaves no
 * file at all.
 */
class Mp4Exporter(
    private val session: Mp4Session = NativeMp4Session,
    private val writerFactory: () -> Mp4MediaWriter = { MediaCodecMp4Writer() },
    private val engine: ExecutorService = ExportEngine.thread,
    private val lutPathFor: (String?) -> String? = { null },
    private val gyroPathFor: (String) -> String? = { null },
    /**
     * AUD-02's link-state seam (the lut/gyro pattern again): turns the
     * job's sourceId into the source's CURRENT audio link state. The app
     * wires it to the AudioStore's record (AudioProject.link — an absent
     * record is ATTACHED, the default, so an unmanaged project keeps the
     * byte-identical current output); tests inject a fake. Only ATTACHED
     * exports the clip's audio track — DETACHED, REMOVED, and
     * LINKED_EDITED export video-only (the edited recording is not muxed
     * here; that needs the TIMELINE export, a later milestone).
     */
    private val audioLinkFor: (String) -> AudioLinkState = { AudioLinkState.ATTACHED },
    /**
     * Per-export stage-timing log sink (owner request 2026-11-13): each
     * line the timing emits lands here (the app wires it to Log.i under
     * the "ritim-export" tag, DEBUG builds only — see [ExportStageTiming]
     * for the line formats). Null (the default) = timing off: nothing is
     * collected, the encoded bytes are identical.
     */
    private val stageLog: ((String) -> Unit)? = null,
    /**
     * The GPU develop leg's seam, threaded through to every ExportPipeline
     * attempt (M3 commit b) — the same injectability rule as [session] and
     * [writerFactory]: the default is the real native seam, JVM tests
     * inject a fake, and the app's construction site (AppNav) compiles
     * untouched.
     */
    private val gpuDevelop: GpuDevelop = NativeGpuDevelop,
    /** (E1b) The live encoder facts the plan resolves against (the device layer; JVM tests inject). */
    private val encoderFacts: () -> List<EncoderFacts> = { DeviceEncoderFacts.all() },
    /** (E1b) Where the plan trail goes: the "ritim-export" log. A failing sink never fails an export. */
    private val planLog: (String) -> Unit = { line -> runCatching { android.util.Log.i("ritim-export", line) } },
    /**
     * (E0) Whether the job's source is an encoded (Mi-Log / MP4) clip: the
     * app wires it to the source's stored kind; the default (false) keeps
     * every MCRAW caller and test unchanged.
     */
    private val isEncoded: (String) -> Boolean = { false },
    /**
     * (E0) The encoded clip's session (MediaCodec decode + developEncoded);
     * one per export, built lazily so MCRAW exports never touch it.
     */
    private val encodedSessionFactory: () -> Mp4Session = {
        EncodedMp4Session(log = { line -> runCatching { android.util.Log.i("ritim-export", line) } })
    },
) {

    /**
     * Exports the clip at [sourceFd] as an MP4 at [out], applying the
     * job's grade snapshot — including its LUT selection (COL-07):
     * [lutPathFor] turns the snapshot's lutId into the copied cube's path
     * (null = no LUT), resolved ONCE here so every frame of the export
     * runs the same stage-22 apply as the preview. Gyro Phase 5:
     * [gyroPathFor] turns the job's sourceId into the attached gyro log's
     * path (null = no warp), resolved ONCE here — when the snapshot has
     * stabilization on AND a log is attached, every frame is warped at
     * full resolution, the same per-frame warp the paused preview shows.
     * DNG and photo exports never reach this seam (no gyro warp there —
     * the documented skip; a gyro-stabilized DNG would be a raw
     * resample, a different project). Returns the updated job: DONE on
     * success (detail = output file name), FAILED with a stage + cause +
     * recovery detail on failure. Never throws; a failure leaves neither
     * [out] nor its partial behind.
     *
     * [onProgress] hears (videoFramesDone, totalVideoFrames) per frame; the
     * queue owns the throttled persistence (see ExportQueue.ProgressWriter).
     */
    /**
     * The audio link state this [job] exports with (QA-15 / EX-11): the
     * enqueue-time snapshot [ExportJob.audioLink] when present, else the
     * live [audioLinkFor] lookup (rows queued before the snapshot field —
     * their legacy behavior, unchanged).
     */
    internal fun effectiveAudioLink(job: ExportJob): AudioLinkState =
        job.audioLink ?: audioLinkFor(job.sourceId)

    suspend fun export(
        job: ExportJob,
        sourceFd: Int,
        out: File,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ): ExportJob {
        val lutPath = lutPathFor(job.snapshot.lutId)
        // (E0) An encoded source decodes through MediaCodec and develops with
        // developEncoded; the rest of the pipeline (plan, writer, audio) is
        // shared. v1: CPU develop only (a GPU/10-bit request is re-planned
        // by the pipeline with the reason on the report) and no gyro warp
        // (stabilization stays hidden for encoded clips).
        val encoded = isEncoded(job.sourceId)
        val sessionForJob = if (encoded) encodedSessionFactory() else session
        val gyroPath = if (encoded) null else gyroPathFor(job.sourceId)
        // AUD-02 gate, resolved ONCE before the engine runs (the same
        // rule as lut/gyro): only ATTACHED carries the audio track.
        // QA-15/EX-11: the job's ENQUEUE-time snapshot wins (a retry
        // starts with the same audio choice the user queued); only a
        // legacy/un-snapshotted row (null) falls back to the live lookup.
        val audioAttached = effectiveAudioLink(job) == AudioLinkState.ATTACHED
        val partial = ExportStaging.partial(out)
        val run = job.settings?.let { ExportPlanRun(it, encoderFacts, ExportContext(), planLog) }
        val startedNs = System.nanoTime()
        val outcome: Result<Unit> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val res = try {
                        var backend = if (encoded) RenderBackend.CPU else job.renderBackend
                        while (true) {
                            try {
                                exportOnce(
                                    sessionForJob,
                                    backend, sourceFd, job.snapshot,
                                    partial.absolutePath, lutPath, gyroPath,
                                    job.manualRotationDeg, audioAttached, onProgress,
                                    job.resolutionW, job.resolutionH, run,
                                )
                                break
                            } catch (e: SurfaceInputUnavailable) {
                                if (backend == RenderBackend.CPU) throw e
                                // §10: the writer could not bring its
                                // encoder-Surface mode up (no EGL display, no
                                // recordable config, no GLES 3 context, the
                                // codec refused COLOR_FormatSurface...). The
                                // whole export restarts on the CPU path from
                                // frame 0 with a FRESH writer — no frame is
                                // encoded twice, no partial file continues
                                // mid-stream in an incompatible format. The
                                // discard + fresh writerFactory() in the next
                                // loop turn is that clean restart; the
                                // fallback is logged, never silent. The reason
                                // names the failing BRING-UP STEP (owner bug
                                // report 2026-11-13):
                                // reason=surface-input-unavailable:<step>, one
                                // line — and exactly the legacy reason string
                                // when the step is unknown, so the owner's
                                // existing greps and the tests stay
                                // byte-identical.
                                ExportStaging.discard(partial)
                                val reason = SurfaceInputUnavailable.reason(e.step)
                                stageLog?.invoke(
                                    "render backend fallback: " +
                                        "requested=${job.renderBackend.name} " +
                                        "effective=CPU reason=" + reason
                                )
                                run?.note("surface->cpu: $reason")
                                backend = RenderBackend.CPU
                            } catch (e: TenBitUnavailable) {
                                // E1d: the 10-bit GPU path could not serve the
                                // export. Before the first frame: ONE re-plan as
                                // 8-bit (this is the run's single plan fix), the
                                // reason on the report. After frames were
                                // encoded: the job FAILS with the reason - a file
                                // never mixes depths and is never silently 8-bit.
                                if (e.midStream || run == null || !run.fixToEightBit(e.message ?: "unavailable")) throw e
                                ExportStaging.discard(partial)
                            } catch (e: VideoConfigRejected) {
                                // E1b: the encoder refused the plan's format
                                // before any frame (start() failed). AT MOST ONE
                                // plan-fix retry, after the Surface->CPU step
                                // above; anything else fails with the encoder's
                                // own reason. Never a silent different output:
                                // the fixed plan and its issues are in the report.
                                if (run == null || !run.fixAfterRejection(e.message ?: "rejected")) throw e
                                ExportStaging.discard(partial)
                            }
                        }
                        if (ExportStaging.finalize(partial, out)) {
                            Result.success(Unit)
                        } else {
                            ExportStaging.discard(partial)
                            Result.failure<Unit>(
                                IllegalStateException(
                                    Mp4ExportErrors.kotlinMessage(
                                        Mp4ExportErrors.STAGE_FINALIZE,
                                        "the finished movie could not be moved",
                                        "check free space and retry",
                                    )
                                )
                            )
                        }
                    } catch (e: Throwable) {
                        ExportStaging.discard(partial)
                        Result.failure<Unit>(e)
                    }
                    cont.resume(res)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(
                    Result.failure<Unit>(
                        IllegalStateException(
                            Mp4ExportErrors.kotlinMessage(
                                "open",
                                "export engine unavailable",
                                "retry the export",
                            )
                        )
                    )
                )
            }
        }
        val wallMs = (System.nanoTime() - startedNs) / 1_000_000L
        return outcome.fold(
            onSuccess = {
                job.copy(
                    state = State.DONE,
                    detail = out.name,
                    report = run?.plan?.let { ExportReport.from(it, run, out.length().takeIf { n -> n > 0 }, wallMs) },
                )
            },
            onFailure = { e -> job.copy(state = State.FAILED, detail = e.message) },
        )
    }

    /**
     * One whole-pipeline attempt (GPU deliverable F, §10): a FRESH writer
     * per attempt — the CPU restart contract is a clean re-run from frame
     * 0, never a mid-stream continuation, so the writer (its codecs, its
     * muxer, its EGL state) is never reused across attempts. [renderBackend]
     * is the attempt's backend: the job's snapshot for the first attempt,
     * CPU for the restart. [audioAttached] is AUD-02's resolved gate —
     * the same value for BOTH attempts (a backend restart never changes
     * what audio the export carries).
     */
    private fun exportOnce(
        session: Mp4Session,
        renderBackend: RenderBackend,
        sourceFd: Int,
        grade: GradeK,
        outPath: String,
        lutPath: String?,
        gyroPath: String?,
        manualRotationDeg: Int,
        audioAttached: Boolean,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)?,
        resolutionW: Int? = null,
        resolutionH: Int? = null,
        planRun: ExportPlanRun? = null,
    ) {
        ExportPipeline(
            session,
            writerFactory(),
            stageLog,
            renderBackend = renderBackend,
            gpuDevelop = gpuDevelop,
            audioAttached = audioAttached,
            resolutionW = resolutionW,
            resolutionH = resolutionH,
            planRun = planRun,
        ).run(
            sourceFd, grade, outPath, lutPath, gyroPath, manualRotationDeg, onProgress
        )
    }
}
