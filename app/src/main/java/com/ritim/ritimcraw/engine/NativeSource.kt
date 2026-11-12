package com.ritim.ritimcraw.engine

import android.graphics.Rect
import com.ritim.ritimcraw.BuildConfig
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Kotlin view of rawedit::FrameInfo (sprint plan Task 3).
 * cfa: 0=RGGB 1=GRBG 2=GBRG 3=BGGR (rawedit::CfaPattern order).
 */
data class FrameInfoK(
    val ptsUs: Long,
    val width: Int,
    val height: Int,
    val bitDepth: Int,
    val cfa: Int,
    val active: Rect,
    val orientationDeg: Int,
)

/**
 * Result of a native call. `ok` mirrors rawedit::Result: value is meaningful
 * only when code == 0 (ErrorCode::Ok).
 *
 * codes (rawedit::ErrorCode): 0 ok, 1 truncated, 2 badHeader, 3 ioError,
 * 4 unsupported.
 */
data class NativeResult<T>(
    val code: Int,
    val detail: String,
    val value: T?,
) {
    val ok: Boolean get() = code == 0
}

/**
 * Handle-based wrapper over the JNI bridge to librawedit_jni.so.
 *
 * One NativeSource owns one native rawedit::Source, and one motioncam
 * decoder instance is NOT safe for concurrent use (upstream README) — so
 * every native call is dispatched on a single dedicated engine thread
 * ([engine]), and each call runs to completion before the next starts.
 * Callers get results through the [ExecutorService] they pass in (or use
 * the convenience synchronous methods from that thread).
 *
 * The library may be absent (no NDK build yet); [nativeAvailable] reports
 * that and every method fails cleanly with code 3 (ioError) instead of
 * crashing, so the app shell runs without the .so.
 */
class NativeSource private constructor(
    private val handle: Long,
    private val engine: ExecutorService,
) : AutoCloseable {

    val frameCount: Int
        get() = requireOpen { nativeFrameCount(handle) }

    fun frameInfo(i: Int): FrameInfoK =
        requireOpen { nativeFrameInfo(handle, i) ?: error("frameInfo returned null") }

    fun readFrame(i: Int): ShortArray =
        requireOpen { nativeReadFrame(handle, i) ?: error("readFrame returned null") }

    fun readAudio(): ShortArray =
        requireOpen { nativeReadAudio(handle) ?: error("readAudio returned null") }

    override fun close() {
        if (!closed) {
            closed = true
            engine.execute { nativeCloseSource(handle) }
            engine.shutdown()
        }
    }

    @Volatile
    private var closed = false

    private fun <T> requireOpen(block: () -> T): T {
        check(!closed) { "NativeSource is closed" }
        if (!nativeAvailable) {
            throw IllegalStateException(nativeUnavailableMessage)
        }
        return block()
    }

    companion object {
        /**
         * True when librawedit_jni.so loaded. While false (no NDK build in
         * this environment), every operation throws IllegalStateException
         * with a recovery message instead of UnsatisfiedLinkError.
         */
        @Volatile
        var nativeAvailable: Boolean = false
            private set

        val nativeUnavailableMessage: String =
            "The native engine is not available in this build (librawedit_jni.so " +
                "not loaded). Recovery: install a build produced with the NDK."

        init {
            try {
                System.loadLibrary("rawedit_jni")
                nativeAvailable = true
                // Debug measurement logging (audit fix 2026-11-12): debug
                // builds get the "ritim-stab" per-settle-render stabilization
                // line (mode, strength, angle, zooms, IMU overlap, or the
                // skip reason); release keeps the native default (off) and
                // never logs.
                if (BuildConfig.DEBUG) nativeSetStabDebugLog(true)
            } catch (e: UnsatisfiedLinkError) {
                nativeAvailable = false
            } catch (e: SecurityException) {
                nativeAvailable = false
            }
        }

        /**
         * Opens a clip from a POSIX fd (from SAF). The bridge dups the fd.
         * Returns a failed NativeResult (not an exception) when the file is
         * not a valid MCRAW, with stage + cause + recovery in `detail`.
         */
        fun open(fd: Int): NativeResult<NativeSource> {
            if (!nativeAvailable) {
                return NativeResult(3, nativeUnavailableMessage, null)
            }
            val engine = Executors.newSingleThreadExecutor { r ->
                Thread(r, "rawedit-engine").apply { isDaemon = true }
            }
            // The JNI open itself is fast (reads headers/index); run it on
            // the engine thread too so the decoder is constructed where it
            // is used.
            val task = engine.submit<Pair<Int, String?>> {
                val h = nativeOpenSource(fd)
                if (h == 0L) {
                    Pair(0, null)
                } else {
                    Pair(h.toInt(), null)
                }
            }
            return try {
                val (h, _) = task.get()
                if (h == 0) {
                    engine.shutdown()
                    // Fetch the last error detail from the bridge.
                    NativeResult(2, nativeLastError() ?: "open failed", null)
                } else {
                    NativeResult(0, "", NativeSource(h.toLong(), engine))
                }
            } catch (e: RejectedExecutionException) {
                NativeResult(3, "engine shut down", null)
            } catch (e: java.util.concurrent.ExecutionException) {
                engine.shutdown()
                NativeResult(3, "open: ${e.cause?.message ?: e.message}", null)
            }
        }

        // ---- JNI (implemented in app/src/main/cpp/jni_bridge.cpp) ----
        // All external functions are `private`: Kotlin does not append the
        // $app_debug friend-module suffix to private declarations, so the
        // JVM-mangled names match the plain JNI symbols in jni_bridge.cpp
        // in every build type. (internal/ public would mangle to
        // nativeXxx$app_debug in debug builds and UnsatisfiedLinkError.)
        // Cross-module callers go through the wrappers below, not the
        // external declarations themselves.

        @JvmStatic private external fun nativeOpenSource(fd: Int): Long
        @JvmStatic private external fun nativeFrameCount(handle: Long): Int
        @JvmStatic private external fun nativeFrameInfo(handle: Long, i: Int): FrameInfoK?
        @JvmStatic private external fun nativeReadFrame(handle: Long, i: Int): ShortArray?
        @JvmStatic private external fun nativeReadAudio(handle: Long): ShortArray?
        @JvmStatic private external fun nativeCloseSource(handle: Long): Long
        @JvmStatic private external fun nativeLastError(): String?

        /**
         * Toggles the debug stabilization measurement line (audit fix
         * 2026-11-12): when on, every settle-class render logs one line
         * under the "ritim-stab" logcat tag — the applied mode/strength,
         * per-frame and max angles, requiredZoom vs appliedZoom, IMU
         * overlap, or the skip reason (off / no-imu / unsynced) when the
         * warp did not apply. Called once from the companion init with
         * BuildConfig.DEBUG, so release builds stay silent.
         */
        @JvmStatic private external fun nativeSetStabDebugLog(enabled: Boolean)

        // ---- Preview JNI (decoder -> developThumbnail -> Rec709 -> ARGB) ----
        // Every call must run on the renderer's single engine thread
        // (thread-local error state + one motioncam decoder per Source).

        /**
         * Opens a preview source from fd. Returns
         * [handle, frameCount, firstWidth, firstHeight, firstCfa,
         * firstOrientation, containerWhiteLevel] or null (then
         * [previewLastError] / [previewErrorCode] / [previewErrorStage]
         * say why). containerWhiteLevel is the container RAW white level
         * (the clip info sheet's "RAW white level" row; 0 = unknown).
         */
        @JvmStatic private external fun nativePreviewOpen(fd: Int): IntArray?

        /**
         * Decodes frameIndex through the preview pipeline. Returns
         * [width, height, then w*h ARGB_8888 ints] or null on failure
         * (read the error accessors from the same thread).
         *
         * lutPath/lutStrength (COL-07): the selected .cube's path (null =
         * no LUT — stage 24 bypassed, the old look) and the strength blend
         * ALREADY mapped to rawedit's 0..1 scale (GradeK's percent / 100).
         * A parse failure fails the render with the lut error.
         *
         * noiseReduction/chromaDenoise (DN-01..08): the two denoise
         * strengths at GradeK's native 0..100 scale (the core clamps);
         * 0 = the stage is skipped entirely (the old look).
         *
         * wheel/split (COL-13): the three-band color wheel offsets
         * (unit-disc [-1,1], (0,0) = neutral — stage 18) and the split-tone
         * scalars (hue degrees, -1 = off; balance -100..100 — stage 19),
         * all at GradeK's native scale.
         */
        @JvmStatic private external fun nativePreviewRender(
            handle: Long,
            frameIndex: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
            maxDim: Int,
        ): IntArray?

        /**
         * Interactive slider render over the persistent session: same output
         * contract as nativePreviewRender, but the bridge caches the handle's
         * last decoded CFA — a render of the SAME frameIndex skips the decode
         * and only re-develops (grade-only change). A frameIndex change
         * decodes and replaces the cache; a reopened handle starts uncached.
         * lutPath/lutStrength as in nativePreviewRender (COL-07);
         * noiseReduction/chromaDenoise as in nativePreviewRender (DN-01..08);
         * the wheel/split-tone package as in nativePreviewRender (COL-13);
         * the stabilization package as in nativePreviewRender (gyro Phase 5).
         */
        @JvmStatic private external fun nativePreviewSessionRenderCached(
            handle: Long,
            frameIndex: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
            maxDim: Int,
        ): IntArray?

        /** rawedit::ErrorCode of the last failed preview call (0 ok, 1 truncated, 2 badHeader, 3 ioError, 4 unsupported). */
        @JvmStatic private external fun nativePreviewErrorCode(): Int

        /** Pipeline stage of the last failed preview call (0 open, 1 frame read, 2 develop). */
        @JvmStatic private external fun nativePreviewErrorStage(): Int

        /**
         * RAW histogram stats (feature N6): PRE-DEVELOP CFA statistics of
         * [frameIndex] — one compact jintArray (layout version, frame
         * index, dims, 3x256 bins, RAW clip counts, per-channel samples,
         * the black/white levels actually used; 787 ints — see the bridge
         * comment for the exact layout) or null on failure (read the
         * error accessors from the same thread). The stats describe the
         * sensor domain: they do NOT move with exposure/WB/LUT/grade
         * sliders; they change only with the frame. Reuses the handle's
         * interactive CFA cache (never a second decode); must run on the
         * session's engine thread like every preview call.
         */
        @JvmStatic private external fun nativeRawHistogram(handle: Long, frameIndex: Int): IntArray?

        // ---- DNG export JNI (decode frame -> writeCfaDng to a staging path) ----

        /**
         * Decodes frame [frameIndex] from the clip at [fd] and writes it as a
         * CFA DNG 1.4 file at [outPath] (the caller's out.partial staging
         * path in app cache). [manualRotationDeg] (owner request
         * 2026-11-11 section 5) is the job's manual rotation delta
         * snapshot: added to the frame's container orientation before the
         * write, so the DNG Orientation tag carries the effective rotation
         * while the CFA itself stays unrotated.
         *
         * cropX/cropY/cropW/cropH (RAW-09): the job snapshot's crop rect as
         * fractions of the EFFECTIVE-upright frame (the space the user
         * sees). The bridge maps the fractions through the effective
         * rotation into source-CFA space, aligns the cut to the 2x2 Bayer
         * grid and cuts the decoded CFA before the write — the DNG carries
         * the selected region at native resolution, never stretched.
         * Full-frame fractions (0,0,1,1) skip the cut entirely. The
         * straighten dial is NOT part of this path: the raw mosaic must
         * not be resampled (documented RAW-09 skip).
         * True on success; on false read [previewErrorCode] /
         * [previewErrorStage] / [previewLastError] from the same thread.
         * Stages: 0 open, 1 frame read, 3 dng write.
         */
        @JvmStatic private external fun nativeDngExport(
            fd: Int,
            frameIndex: Int,
            manualRotationDeg: Int,
            cropX: Float,
            cropY: Float,
            cropW: Float,
            cropH: Float,
            outPath: String,
        ): Boolean

        /**
         * Decodes frame [frameIndex] from the clip at [fd] and writes it as a
         * LINEAR DNG (RAW-13) at [outPath]: the demosaiced, scene-referred
         * 16-bit RGB in a PhotometricInterpretation 34892 (LinearRaw) DNG —
         * a separate file contract from the CFA DNG above. The exact develop
         * stages applied (black/white normalization, bilinear demosaic,
         * as-shot WB — nothing else; the display tone map is NOT part of
         * this path) are recorded in the file's ImageDescription tag.
         * [manualRotationDeg] carries the job's effective-rotation snapshot
         * exactly like nativeDngExport, and cropX/cropY/cropW/cropH carry
         * the effective-space crop fractions the same way: the bridge cuts
         * the CFA (Bayer-aligned, never stretched) BEFORE developLinear, so
         * the demosaic runs on the selected region only. Straighten is a
         * documented skip here too. True on success; on false read
         * [previewErrorCode] / [previewErrorStage] / [previewLastError] from
         * the same thread. Stages: 0 open, 1 frame read, 2 develop,
         * 3 dng write.
         */
        @JvmStatic private external fun nativeLinearDngExport(
            fd: Int,
            frameIndex: Int,
            manualRotationDeg: Int,
            cropX: Float,
            cropY: Float,
            cropW: Float,
            cropH: Float,
            outPath: String,
        ): Boolean

        // ---- MP4 export JNI (full-res developCpu -> Rec709 -> RGB888) ----

        /**
         * Develops frame [frameIndex] of the opened export session
         * ([handle] from nativePreviewOpen) at FULL resolution with the job's
         * grade snapshot: developCpu -> linearToRec709 -> clamp -> 8-bit
         * RGB888, row-major, 3 bytes per pixel, NOT rotated here: the
         * exporter rotates the pixels upright (RotationState.rotateRgb888)
         * before crop/YUV, so the muxer's orientation hint stays 0.
         * Returns w*h*3 bytes or
         * null on failure (read the error accessors from the same thread).
         *
         * lutPath/lutStrength (COL-07): the job snapshot's LUT path (null =
         * no LUT) and the strength blend already mapped to 0..1 (percent /
         * 100). The SAME stage-24 apply as the preview, so the owner's
         * preview-vs-export acceptance check compares like with like.
         * noiseReduction/chromaDenoise (DN-01..08): the job snapshot's
         * denoise strengths at the native 0..100 scale (0 = stage skipped).
         * wheel/split (COL-13): the job snapshot's wheel offsets and
         * split-tone scalars — the SAME stage-18/19 apply as the preview
         * (identical math, identical order; export parity by construction).
         */
        @JvmStatic private external fun nativeExportFrame(
            handle: Long,
            frameIndex: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
        ): ByteArray?

        /** ptsUs of frame [index] (frameInfo is throw-free; -1 on a bad handle). */
        @JvmStatic private external fun nativeFramePtsUs(handle: Long, index: Int): Long

        /**
         * [sampleRate, channels] of the clip's audio, or null when the clip
         * has no audio / on failure (read the error accessors). 0/0 means no
         * audio track should be written.
         */
        @JvmStatic private external fun nativeAudioInfo(handle: Long): IntArray?

        // ---- Encoded (Mi-Log MP4) develop JNI (developEncoded -> Rec709 -> ARGB) ----

        /**
         * Develops one decoded encoded-frame (NV12 when [semiPlanar] is
         * true: [yPlane] + interleaved UV in [uPlane]; I420 otherwise) with
         * the grade: developEncoded (limited-range YUV -> RGB -> Mi-Log
         * decode -> WB/temp/tint/exposure -> denoise stages 7/8 (DN-01..08,
         * the same kernels as the RAW path; 0 = skipped) ->
         * sharpness/detail/tonemap, the shared COL-15/COL-16 stages, then
         * the optional stage-24 LUT, COL-07) -> linearToRec709 -> clamp ->
         * 8-bit ARGB, rotated upright by [orientationDeg]. Returns
         * [width, height, then w*h ARGB_8888 ints] (the preview contract) or
         * null on failure (read the error accessors from the same thread).
         * Uses tempK/tint/exposureEv plus the detail/tonemap fields of the
         * grade (developEncoded's contract). No handle: the frame is
         * stateless input. lutPath null = no LUT; lutStrength already
         * mapped to rawedit's 0..1 scale (GradeK percent / 100).
         * noiseReduction/chromaDenoise (DN-01..08): the denoise strengths at
         * the native 0..100 scale (0 = stage skipped, the old look).
         * wheel/split (COL-13): travel for signature parity (one flat
         * signature across every develop entry point) but are IGNORED here —
         * the tone-band color stages are MCRAW-only; the encoded path grades
         * via its WB/temp/tint/exposure loop.
         */
        @JvmStatic private external fun nativeEncodedDevelop(
            yPlane: ByteArray,
            uPlane: ByteArray,
            vPlane: ByteArray,
            width: Int,
            height: Int,
            stride: Int,
            uvStride: Int,
            semiPlanar: Boolean,
            isBt709: Boolean,
            orientationDeg: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
        ): IntArray?

        /**
         * LUT probe (COL-07): parses the .cube at [path] statelessly and
         * returns its TITLE ("" when the file has no TITLE line), or null on
         * a parse failure — then the error accessors say why. No handle, no
         * render-cache touch, safe on any thread. The import flow probes
         * after copying the file and removes the entry when this fails, so
         * only valid cubes reach the LUT list.
         */
        @JvmStatic private external fun nativeLutProbe(path: String): String?

        /**
         * Gyro sync probe (GYRO PHASE 5): statelessly validates the .gcsv
         * at [gyroPath] against the clip behind [fd] and returns the sync
         * estimate as [anchoredFlag, offsetMs, errorBoundMs,
         * focalLengthMm, sampleCount] (see the bridge comment), or null on
         * failure — then the error accessors say why. No handle, no
         * render-cache touch, safe on any thread; the fd is dup'd by
         * Source::open so the caller's handle is untouched. The attach
         * flow probes after copying the file and removes the entry when
         * this fails, so only logs that parse (and anchor, or honestly
         * report manual-required) reach the editor.
         */
        @JvmStatic private external fun nativeGyroSyncProbe(
            fd: Int,
            gyroPath: String,
        ): DoubleArray?

        /**
         * Stabilization zoom probe (audit fix 2026-11-12, Fix 5): evaluates
         * the clip-wide stabilization plan for the six flat request params —
         * the identical plan the renders run — and returns
         * [requiredZoom, appliedZoom, statusInt] without warping anything
         * (see the bridge comment). statusInt is frame 0's status in the
         * core's StabilizationStatus order (Ok=0, Off=1, NoImu=2,
         * Unsynced=3, LowOverlap=4, IntrinsicsAssumed=5). Null = no verdict:
         * dead handle, no gyro log, or a .gcsv parse failure (the error
         * accessors say which). The zooms are buffer-independent, so one
         * probe per settle covers every tier. MUST run on the session's
         * engine thread (thread-local gyro cache + error state) — the seam
         * confines it like framePtsUs.
         */
        @JvmStatic private external fun nativeStabZoomProbe(
            handle: Long,
            gyroPath: String,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
        ): DoubleArray?

        /**
         * GLES capability probe (owner request 2026-11-12, deliverable C):
         * statelessly creates a throwaway GLES context, reads what the
         * device reports, releases the context, and returns
         * [contextOk, glesMajor, glesMinor, computeOk, floatFboOk,
         * maxTextureSize] (see the bridge comment). A device with NO
         * context is an honest report, not an error: the array returns
         * with contextOk=0 and the failing EGL step in the error detail
         * (previewLastError from the same thread). Null only on a
         * JNI-level failure. No handle, no render-cache touch, safe on
         * any thread. Runtime behavior is device-test-pending.
         */
        @JvmStatic private external fun nativeGlesProbe(): DoubleArray?

        /**
         * Single-frame demosaic parity (deliverable C): statelessly opens
         * the clip behind [fd] (dup'd — the caller's fd is untouched),
         * decodes frame 0, compiles [computeSource] — the Kotlin single
         * source of truth (GlesShaderSources.DEMOSAIC_COMPUTE) — on a
         * throwaway GLES 3.1 context, and compares the GPU demosaic
         * against developLinear, the DNG path's own reference, on the
         * same frame. Returns [ran, maxDiffLsb, mismatchCount, samples]
         * (see the bridge comment); ran=0 means a GPU stage failed and
         * the error detail names it. The PASS VERDICT lives in
         * GpuParityRules — this reports raw numbers only, never a
         * claim. No handle, no render-cache touch, safe on any thread.
         * Device-test-pending.
         */
        @JvmStatic private external fun nativeGlesParity(
            fd: Int,
            computeSource: String,
        ): DoubleArray?

        // ---- Cross-module wrappers (external decls stay private; see the
        // mangling note at the top of the JNI section) ----

        /** JNI bridge: see nativePreviewOpen. */
        internal fun jniPreviewOpen(fd: Int): IntArray? = nativePreviewOpen(fd)

        /** JNI bridge: see nativePreviewRender. */
        internal fun jniPreviewRender(
            handle: Long,
            frameIndex: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
            maxDim: Int,
        ): IntArray? = nativePreviewRender(
            handle, frameIndex,
            exposureEv, highlights, tempK, tint,
            saturation, blackPoint, whitePoint, shadows, midtones, contrast,
            asShot, highlightReconstruction, keepVignetting,
            sharpness, detail, tonemap,
            lutPath, lutStrength,
            noiseReduction, chromaDenoise,
            temporalNr, temporalStrength,
            wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
            wheelHighX, wheelHighY, splitShadowHue, splitHighlightHue, splitBalance,
            gyroPath, stabMode, stabStrength, stabZoomLimit, stabFocalMm, stabTrimMs,
            maxDim,
        )

        /** JNI bridge: cached interactive render; see nativePreviewSessionRenderCached. */
        internal fun jniPreviewSessionRenderCached(
            handle: Long,
            frameIndex: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
            maxDim: Int,
        ): IntArray? = nativePreviewSessionRenderCached(
            handle, frameIndex,
            exposureEv, highlights, tempK, tint,
            saturation, blackPoint, whitePoint, shadows, midtones, contrast,
            asShot, highlightReconstruction, keepVignetting,
            sharpness, detail, tonemap,
            lutPath, lutStrength,
            noiseReduction, chromaDenoise,
            temporalNr, temporalStrength,
            wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
            wheelHighX, wheelHighY, splitShadowHue, splitHighlightHue, splitBalance,
            gyroPath, stabMode, stabStrength, stabZoomLimit, stabFocalMm, stabTrimMs,
            maxDim,
        )

        /** JNI bridge: rawedit::ErrorCode of the last failed preview call. */
        internal fun jniPreviewErrorCode(): Int = nativePreviewErrorCode()

        /** JNI bridge: pipeline stage of the last failed preview call. */
        internal fun jniPreviewErrorStage(): Int = nativePreviewErrorStage()

        /** JNI bridge: see nativeRawHistogram (engine thread only). */
        internal fun jniRawHistogram(handle: Long, frameIndex: Int): IntArray? =
            nativeRawHistogram(handle, frameIndex)

        /** JNI bridge: see nativeDngExport (crop fractions = RAW-09). */
        internal fun jniDngExport(
            fd: Int,
            frameIndex: Int,
            manualRotationDeg: Int,
            cropX: Float,
            cropY: Float,
            cropW: Float,
            cropH: Float,
            outPath: String,
        ): Boolean =
            nativeDngExport(fd, frameIndex, manualRotationDeg, cropX, cropY, cropW, cropH, outPath)

        /** JNI bridge: see nativeLinearDngExport (RAW-13 Linear DNG, RAW-09 crop). */
        internal fun jniLinearDngExport(
            fd: Int,
            frameIndex: Int,
            manualRotationDeg: Int,
            cropX: Float,
            cropY: Float,
            cropW: Float,
            cropH: Float,
            outPath: String,
        ): Boolean =
            nativeLinearDngExport(fd, frameIndex, manualRotationDeg, cropX, cropY, cropW, cropH, outPath)

        /** JNI bridge: see nativeExportFrame. */
        internal fun jniExportFrame(
            handle: Long,
            frameIndex: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            gyroPath: String?,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
        ): ByteArray? = nativeExportFrame(
            handle, frameIndex,
            exposureEv, highlights, tempK, tint,
            saturation, blackPoint, whitePoint, shadows, midtones, contrast,
            asShot, highlightReconstruction, keepVignetting,
            sharpness, detail, tonemap,
            lutPath, lutStrength,
            noiseReduction, chromaDenoise,
            temporalNr, temporalStrength,
            wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
            wheelHighX, wheelHighY, splitShadowHue, splitHighlightHue, splitBalance,
            gyroPath, stabMode, stabStrength, stabZoomLimit, stabFocalMm, stabTrimMs,
        )

        /** JNI bridge: ptsUs of frame [index] (-1 on a bad handle). */
        internal fun jniFramePtsUs(handle: Long, index: Int): Long = nativeFramePtsUs(handle, index)

        /** JNI bridge: [sampleRate, channels] or null. */
        internal fun jniAudioInfo(handle: Long): IntArray? = nativeAudioInfo(handle)

        /** JNI bridge: see nativeEncodedDevelop. */
        internal fun jniEncodedDevelop(
            yPlane: ByteArray,
            uPlane: ByteArray,
            vPlane: ByteArray,
            width: Int,
            height: Int,
            stride: Int,
            uvStride: Int,
            semiPlanar: Boolean,
            isBt709: Boolean,
            orientationDeg: Int,
            exposureEv: Float,
            highlights: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            asShot: Boolean,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            sharpness: Float,
            detail: Float,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            noiseReduction: Float,
            chromaDenoise: Float,
            temporalNr: Boolean,
            temporalStrength: Float,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
        ): IntArray? = nativeEncodedDevelop(
            yPlane, uPlane, vPlane,
            width, height, stride, uvStride,
            semiPlanar, isBt709, orientationDeg,
            exposureEv, highlights, tempK, tint,
            saturation, blackPoint, whitePoint, shadows, midtones, contrast,
            asShot, highlightReconstruction, keepVignetting,
            sharpness, detail, tonemap,
            lutPath, lutStrength,
            noiseReduction, chromaDenoise,
            temporalNr, temporalStrength,
            wheelShadowX, wheelShadowY, wheelMidX, wheelMidY,
            wheelHighX, wheelHighY, splitShadowHue, splitHighlightHue, splitBalance,
        )

        /** JNI bridge: readAudio for export sessions. */
        internal fun jniReadAudio(handle: Long): ShortArray? = nativeReadAudio(handle)

        /**
         * JNI bridge: see nativeLutProbe. Throws IllegalStateException when
         * the .so is absent (the import flow maps that to a plain failure).
         */
        internal fun jniLutProbe(path: String): String? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeLutProbe(path)
        }

        /**
         * JNI bridge: see nativeGyroSyncProbe. Throws IllegalStateException
         * when the .so is absent (the attach flow maps that to a plain
         * failure).
         */
        internal fun jniGyroSyncProbe(fd: Int, gyroPath: String): DoubleArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeGyroSyncProbe(fd, gyroPath)
        }

        /**
         * JNI bridge: see nativeStabZoomProbe. Throws IllegalStateException
         * when the .so is absent; null otherwise means "no verdict" (the
         * caller shows no readout, never a fabricated zoom).
         */
        internal fun jniStabZoomProbe(
            handle: Long,
            gyroPath: String,
            stabMode: Int,
            stabStrength: Float,
            stabZoomLimit: Float,
            stabFocalMm: Float,
            stabTrimMs: Float,
        ): DoubleArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeStabZoomProbe(
                handle, gyroPath, stabMode, stabStrength,
                stabZoomLimit, stabFocalMm, stabTrimMs,
            )
        }

        /**
         * JNI bridge: see nativeGlesProbe. Throws IllegalStateException
         * when the .so is absent; contextOk=0 otherwise means "no GLES
         * context on this device" — an honest report, with the failing
         * EGL step in previewLastError.
         */
        internal fun jniGlesProbe(): DoubleArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeGlesProbe()
        }

        /**
         * JNI bridge: see nativeGlesParity. Throws IllegalStateException
         * when the .so is absent; ran=0 otherwise means "the GPU pass
         * failed at a named stage" (previewLastError) — never a
         * fabricated verdict.
         */
        internal fun jniGlesParity(fd: Int, computeSource: String): DoubleArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeGlesParity(fd, computeSource)
        }

        /** JNI bridge: closeSource for export sessions. */
        internal fun jniCloseSource(handle: Long): Long = nativeCloseSource(handle)

        /** Last-error detail from the engine thread (thread-local in the bridge). */
        internal fun previewLastError(): String? = nativeLastError()
    }
}
