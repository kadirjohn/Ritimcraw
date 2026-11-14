package com.ritim.ritimcraw.engine

import android.graphics.Rect
import com.ritim.ritimcraw.BuildConfig
import java.nio.ByteBuffer
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
 * Kotlin view of rawedit::ContainerIdentity (IN-13 metadata extraction):
 * the container's camera/lens identity keys, with the container's own
 * spellings. Empty string / 0 mean the key is absent — UNKNOWN, never a
 * guess; the extractor maps that onto Field.unknown(). focalLengthMm is
 * RawMeta's 35mm-EQUIVALENT focal (0 = absent), carried here so the
 * identity probe is one read.
 */
data class ContainerIdentityK(
    val uniqueCameraModel: String,
    val deviceModel: String,
    val manufacturer: String,
    val buildModel: String,
    val apertureMm: Double,
    val focalLengthMm: Double,
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
                // Export sub-stage timing (commit (d)): same gate — debug
                // builds collect the six nativeExportFrame sub-stage slots
                // (decode/temporal/develop/warp/oetf/copy) so the export
                // sample lines can split "native=..ms"; release reads one
                // relaxed atomic per boundary and collects nothing.
                if (BuildConfig.DEBUG) nativeSetExportTiming(true)
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

        /**
         * Toggles the export sub-stage timing collection (commit (d)):
         * when on, nativeExportFrame records its six internals — decode,
         * temporal NR, develop, gyro warp, OETF+pack, JNI copy — into
         * thread-local slots read by [nativeExportFrameTimingMs]. Called
         * once from the companion init with BuildConfig.DEBUG (the
         * nativeSetStabDebugLog pattern), so release builds collect
         * nothing.
         */
        @JvmStatic private external fun nativeSetExportTiming(enabled: Boolean)

        /**
         * Toggles the optical sync refinement (M5 commit b): when on, a
         * stabilization plan whose anchor estimate is Anchored runs
         * rawedit::refineOffsetOptically ONCE per plan-cache key (~seconds
         * class, ~480px thumbnails) and the refined offset feeds the warp.
         * UNLIKE the two gates above this is a RUNTIME user toggle, not a
         * build gate: the companion init never calls it — the stab sheet's
         * sync-refine switch owns it through [jniSetSyncRefine]. Default
         * off until the owner's on-device A/B (sync=refined vs
         * sync=anchored in the ritim-stab-timing line) proves the refined
         * offset reduces the vibration.
         */
        @JvmStatic private external fun nativeSetSyncRefine(enabled: Boolean)

        /**
         * Toggles the preview stats readback (trace-dims fix 2026-11-14):
         * when on — the DEFAULT, and today's behavior — the GPU preview
         * session runs draw 3 + the mapped-SSBO readback and publishes the
         * real stats pack; when off it skips BOTH entirely (the trace line
         * reads stats=off, the published pack degrades to the honest 1x1
         * fallback for the measurement run). RUNTIME toggle, the
         * nativeSetSyncRefine pattern: the companion init never calls it —
         * native flips the bridge-level atomic per render through the
         * request. The owner's on-device A/B (the stats= ms span with the
         * readback on vs off) isolates the synchronous readback's cost.
         */
        @JvmStatic private external fun nativeSetPreviewStatsEnabled(enabled: Boolean)

        /**
         * The six sub-stage slots of the frame nativeExportFrame just
         * produced, in milliseconds, in the fixed order
         * [decode, temporal, develop, warp, oetf_pack, jni_copy] — or null
         * when the gate is off (release) or nothing was collected.
         * Thread-local like the error accessors: call on the export thread
         * right after a successful nativeExportFrame.
         */
        @JvmStatic private external fun nativeExportFrameTimingMs(): DoubleArray?

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
         *
         * lutRole..lutOutTransfer (owner request 2026-11-13, managed LUT
         * interpretations): the selected binding's interpretation as the
         * rawedit enum ordinals (LutInterpK's values, passed straight
         * through — the bridge constructs the rawedit::LutInterpretation
         * from them with no lookup table). ALL FIVE -1 = no binding: the
         * legacy COL-07 stage-24 path, byte-identical. lutRole alone -1
         * (a malformed call) also develops legacy.
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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
         * the stabilization package as in nativePreviewRender (gyro Phase 5);
         * the lutRole..lutOutTransfer binding package as in
         * nativePreviewRender (managed interpretations, 2026-11-13).
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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
         * lutRole..lutOutTransfer (managed interpretations, 2026-11-13):
         * the snapshot's binding ordinals as in nativePreviewRender; all
         * five -1 = the legacy stage-24 path, byte-identical.
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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
        ): ByteArray?

        /** ptsUs of frame [index] (frameInfo is throw-free; -1 on a bad handle). */
        @JvmStatic private external fun nativeFramePtsUs(handle: Long, index: Int): Long

        /**
         * [sampleRate, channels] of the clip's audio, or null when the clip
         * has no audio / on failure (read the error accessors). 0/0 means no
         * audio track should be written.
         */
        @JvmStatic private external fun nativeAudioInfo(handle: Long): IntArray?

        /**
         * Stateless RGB888 -> I420 conversion (MP4 export YUV step): the
         * byte-exact native twin of export/YuvConverter.kt's toI420 (the
         * contract lives in core/include/rawedit/Yuv.h). [matrix] is the
         * YuvMatrix ordinal: 0 = BT.601, else BT.709. Returns the tightly
         * packed I420 frame (YuvConverter.toI420 layout), or null on a
         * malformed request (empty frame / short rgb buffer — read the
         * error accessors from the same thread); the Kotlin dispatcher
         * falls back to YuvConverter.toI420, whose require() fails the
         * export the same way it always did.
         */
        @JvmStatic private external fun nativeRgbToI420(
            rgb: ByteArray,
            width: Int,
            height: Int,
            matrix: Int,
        ): ByteArray?

        /**
         * Direct-to-plane RGB888 -> I420 (commit (c)): the same byte-exact
         * native twin as [nativeRgbToI420], writing straight into the
         * encoder Image's plane ByteBuffers at their strides (planar or
         * NV12, padded rows) — no packed intermediate, no second copy.
         * The buffers must be DIRECT (the writer hands over the MediaCodec
         * input Image's buffers). Returns true when the planes hold the
         * frame; false when the caller must take the packed fallback
         * (non-direct buffers, empty frame, short rgb — never an error
         * state; the fallback produces the same bytes).
         */
        @JvmStatic private external fun nativeRgbToI420Planes(
            rgb: ByteArray,
            width: Int,
            height: Int,
            matrix: Int,
            yBuf: ByteBuffer,
            yStride: Int,
            uBuf: ByteBuffer,
            uStride: Int,
            uPixelStride: Int,
            vBuf: ByteBuffer,
            vStride: Int,
            vPixelStride: Int,
        ): Boolean

        /**
         * Fused rotate + aspect-fill (the timeline export's frame loop):
         * rotates the sensor-oriented [rgb] (RGB888, inW*inH*3, row-major)
         * upright by [orientationDeg] (0/90/180/270 CW; any other value is
         * identity — RotationState's else branch) and aspect-fills it to
         * [outW]*[outH] (center-crop cover scale, bilinear, RgbScale's
         * clamp-to-edge) in ONE pass — no intermediate upright buffer.
         * mode 0 = aspect-fill (the only policy v1 ships). Byte-exact to
         * the Kotlin pair RotationState.rotateRgb888 + RgbScale.aspectFill
         * — the degenerate identity pass (no rotation, exact dims) returns
         * the INPUT array object, matching both Kotlin fast paths.
         * Returns null on a malformed request (empty frame, short rgb,
         * unknown mode) with the error state set; the Kotlin dispatcher
         * then falls back to the unchanged Kotlin pair.
         */
        @JvmStatic private external fun nativeRgbRotateScale(
            rgb: ByteArray,
            inW: Int,
            inH: Int,
            orientationDeg: Int,
            outW: Int,
            outH: Int,
            mode: Int,
        ): ByteArray?

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
         * lutRole..lutOutTransfer (managed interpretations, 2026-11-13):
         * the binding ordinals as in nativePreviewRender; all five -1 =
         * the legacy stage-24 path, byte-identical.
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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
         * Container identity probe (IN-13 metadata extraction): statelessly
         * opens the clip behind [fd] and returns the container's
         * camera/lens identity keys as a [ContainerIdentityK] — the fields
         * no other seam exposes (see the bridge comment). Absent keys
         * arrive as ""/0 = UNKNOWN, never a guess. Null = the open failed
         * (the error accessors say why). No handle, no render-cache touch;
         * the fd is dup'd by Source::open so the caller's copy is
         * untouched, safe from any thread.
         */
        @JvmStatic private external fun nativeContainerIdentity(fd: Int): ContainerIdentityK?

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
         *
         * M5 commit b extends the array to six elements: [3] syncKind —
         * 0 = manual trim/no anchor, 1 = anchored, 2 = refined (the optical
         * refinement applied; a rejected refine keeps 1); [4] syncOffsetMs
         * — the model offset the plan actually rode, milliseconds;
         * [5] syncBoundMs — the estimate's error bound. Readers keyed on
         * `size >= 3` keep working (the pre-M5 array ended at [2]); the
         * stab sheet reads [3..5] for the "Refined (optical ±X ms)" status
         * line. With the sync-refine toggle on, a probe MISS pays the
         * once-per-key refinement — deliberately (the probe is the
         * settle-time plan builder); renders after it serve the warmed
         * cache.
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

        /**
         * Single-frame FRAGMENT develop parity (deliverable D): the first
         * REAL GPU render path. Statelessly opens the clip behind [fd]
         * (dup'd — the caller's fd is untouched), decodes frame 0, and
         * runs the CALLER-PROVIDED four-shader fragment chain (the Kotlin
         * single source of truth: FULLSCREEN_VERTEX + DEVELOP_FRAGMENT as
         * the develop program, FULLSCREEN_VERTEX_310 + STORE_FRAGMENT as
         * the readback program). [caseIndex] selects the parity case
         * (gles/GlesPipeline.h documents all three): 0 — the developLinear
         * shape (uHasShading=0, uHr=0) against developLinear itself, the
         * DNG path's own reference, byte-honest regardless of the clip's
         * real metadata; 1 — the DEFAULT grade's real stage list
         * (uHasShading = the clip's map-present gate with the real
         * 4-plane map uploaded, uHr=1 with uHrGain = meta.wbGains with
         * the >0 guard) against a C++-side reference computed from the
         * exported rawedit primitives; 2 — the TONEMAP sweep (stage 23):
         * uTonemap runs modes 1 (ACES) through 5 (AgX Base), one
         * develop+store+readback pass per mode, each mode's reference
         * being developCpu itself with a default Grade (tonemap=mode
         * only) and an identity-matrices meta copy — the real exported
         * stage list (1+2+3+4+5+23). The report carries the WORST case
         * across the five modes and samples = 5 * w * h * 3. Returns
         * [ran, maxDiffLsb, mismatchCount, samples] (see the bridge
         * comment); ran=0 means a GPU stage failed and the error detail
         * names it (EGL step, extension gate, compile log, link log, FBO
         * completeness, draw, map). The PASS VERDICT lives in
         * GpuParityRules — raw numbers only, never a claim. The fragment
         * path needs NO compute (contextOk + floatFboOk gate it). No
         * handle, no render-cache touch, safe on any thread.
         * Device-test-pending.
         */
        @JvmStatic private external fun nativeGlesFragmentParity(
            fd: Int,
            vertexSource: String,
            vertex310Source: String,
            fragmentSource: String,
            storeFragmentSource: String,
            caseIndex: Int,
        ): DoubleArray?

        /**
         * M3 commit (a): the production GPU develop session's three entry
         * points (the persistent counterpart of the one-shot parity
         * harness). Open compiles the caller-provided shader texts and
         * returns a session handle (0 = failure; previewLastError names
         * the step). RenderFrame decodes the clip's frame on the CALLING
         * thread (the clip handle's own ring) and renders it through the
         * session's GL thread — full-res RGB888, row-major, NOT rotated,
         * the same byte shape nativeExportFrame returns. The render takes
         * the fragment-covered grade controls (the five base ones, the
         * two spatial-NR stages' raw values since the spatial-NR port —
         * stages 7/8 — the two unsharp stages' raw values since the
         * sharpness/detail port — stages 21/22, threaded with NO clamp
         * because the CPU's amount mapping makes negative values valid
         * (softening) — plus the ten pointwise stages' raw values); an
         * uncovered grade cannot express itself through this signature.
         * Close is idempotent. Device-test-pending (the owner's
         * covered-grade GPU export is the production proof).
         *
         * Sharpness/detail port: the develop fragment is now the SPLIT
         * three-way pair (pre / pointwise post / tail) plus the four
         * unsharp fragments and the three NR fragments — fourteen
         * sources total (vertex, 310 vertex, pre, post, tail,
         * sharpness-h, sharpness-v, detail-h, detail-v, nr-chroma-h,
         * nr-chroma-v, nr-luma, store, blit). The fused text stays the
         * parity harness's own source.
         */
        @JvmStatic private external fun nativeDevelopSessionOpen(
            vertexSource: String,
            vertex310Source: String,
            preFragmentSource: String,
            postFragmentSource: String,
            tailFragmentSource: String,
            sharpnessHSource: String,
            sharpnessVSource: String,
            detailHSource: String,
            detailVSource: String,
            nrChromaHSource: String,
            nrChromaVSource: String,
            nrLumaSource: String,
            storeFragmentSource: String,
            blitFragmentSource: String,
        ): Long

        @JvmStatic private external fun nativeDevelopSessionRenderFrame(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
        ): ByteArray?

        /**
         * M5 commit (a): the encoder-surface seam pair. SetEncoderSurface
         * hands the MediaCodec input Surface to the session (it wraps the
         * window in an EGL window surface ONCE per export on the session's
         * GL thread); null detaches. The surface is typed Any? for the
         * same JVM-loadability reason as nativePreviewSessionSetSurface
         * (the unit tests reflect over Companion's declared methods).
         * RenderFrameToEncoder develops the frame on the GPU and blits it
         * straight to the encoder surface — no readback bytes cross the
         * seam — stamping [ptsUs] (the SAME normalized encoder pts the
         * writer path uses) as media time via eglPresentationTimeANDROID,
         * and applying [rotationDeg] (the frame's effective orientation,
         * 0/90/180/270) through the blit's uRotation fold — the session
         * fast-refuses anything outside that set.
         * Both return false with the reason in lastError on refusal; the
         * Kotlin dispatcher answers with ONE honest fallback line, never
         * a crash. Device-test-pending (the owner's GPU export is the
         * production proof).
         */
        @JvmStatic private external fun nativeDevelopSessionSetEncoderSurface(
            sessionHandle: Long,
            surface: Any?,
        ): Boolean

        @JvmStatic private external fun nativeDevelopSessionRenderFrameToEncoder(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            ptsUs: Long,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
            rotationDeg: Int,
        ): Boolean

        @JvmStatic private external fun nativeDevelopSessionClose(sessionHandle: Long)

        /**
         * M4 commit (a): the GPU preview session's four entry points —
         * the SAME production session pattern M3 built for export, wired
         * into PREVIEW. Open compiles the nine shader texts (the two
         * vertices, the SPLIT develop pair pre/post, the three spatial-NR
         * fragments, the store pass, plus the window pass's
         * WINDOW_FRAGMENT) and returns a
         * session handle (0 = failure; previewLastError names the step).
         * SetSurface attaches ([surface] non-null) or detaches (null) the
         * SurfaceView's window on the session's GL thread; the surface is
         * typed Any? on purpose — the JVM unit tests reflect over
         * Companion's declared methods, and an android.view.Surface
         * parameter type would fail class resolution on the JVM
         * (NoClassDefFoundError); the UI layer passes the SurfaceView's
         * Surface as Any?, null = destroyed. RenderFrame decodes +
         * decimates the frame on the CALLING thread (Thumbnail.h's
         * cell-aligned math, so the 2x2 Bayer pattern survives the
         * reduction) and renders it through the session's GL thread —
         * develop -> the window present (best-effort) -> the small
         * published stats pack (~256 long edge, upright, the SAME
         * [w, h, pixels...] int-array shape the CPU preview publishes,
         * so the Compose overlays consume either path unchanged). NO
         * full-frame readback exists on this path (the audit's explicit
         * rule; glReadPixels is used nowhere). The render takes the
         * fragment-covered grade controls (the five base ones — exposure,
         * highlights, highlight reconstruction, vignetting, tonemap — the
         * two spatial-NR stages' raw values since the spatial-NR port
         * (chroma denoise, noise reduction — stages 7/8), the two unsharp
         * stages' raw values since the sharpness/detail port (sharpness,
         * detail — stages 21/22, threaded with NO clamp because the CPU's
         * amount mapping makes negative values valid), plus the ten
         * pointwise stages' raw values, highlights joined with the
         * stage-15 port). Close is idempotent.
         * Device-test-pending (the owner's morning playback run is the
         * production proof).
         */
        @JvmStatic private external fun nativePreviewSessionOpen(
            vertexSource: String,
            vertex310Source: String,
            preFragmentSource: String,
            postFragmentSource: String,
            tailFragmentSource: String,
            sharpnessHSource: String,
            sharpnessVSource: String,
            detailHSource: String,
            detailVSource: String,
            nrChromaHSource: String,
            nrChromaVSource: String,
            nrLumaSource: String,
            storeFragmentSource: String,
            windowFragmentSource: String,
        ): Long

        @JvmStatic private external fun nativePreviewSessionSetSurface(
            sessionHandle: Long,
            surface: Any?,
        ): Boolean

        @JvmStatic private external fun nativePreviewSessionRenderFrame(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            maxDim: Int,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
        ): IntArray?

        @JvmStatic private external fun nativePreviewSessionClose(sessionHandle: Long)

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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
            lutRole, lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
            lutRole, lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
            lutRole, lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
        )

        /** JNI bridge: ptsUs of frame [index] (-1 on a bad handle). */
        internal fun jniFramePtsUs(handle: Long, index: Int): Long = nativeFramePtsUs(handle, index)

        /** JNI bridge: [sampleRate, channels] or null. */
        internal fun jniAudioInfo(handle: Long): IntArray? = nativeAudioInfo(handle)

        /** JNI bridge: see nativeRgbToI420 (byte-exact toI420 twin). */
        internal fun jniRgbToI420(
            rgb: ByteArray,
            width: Int,
            height: Int,
            matrix: Int,
        ): ByteArray? = nativeRgbToI420(rgb, width, height, matrix)

        /** JNI bridge: see nativeRgbToI420Planes (direct-to-plane twin). */
        internal fun jniRgbToI420Planes(
            rgb: ByteArray,
            width: Int,
            height: Int,
            matrix: Int,
            yBuf: ByteBuffer,
            yStride: Int,
            uBuf: ByteBuffer,
            uStride: Int,
            uPixelStride: Int,
            vBuf: ByteBuffer,
            vStride: Int,
            vPixelStride: Int,
        ): Boolean = nativeRgbToI420Planes(
            rgb, width, height, matrix,
            yBuf, yStride, uBuf, uStride, uPixelStride, vBuf, vStride, vPixelStride,
        )

        /**
         * JNI bridge: see nativeRgbRotateScale (the fused rotate+scale
         * twin). Throws IllegalStateException when the .so is absent; null
         * with the library loaded is a malformed request — the dispatcher
         * falls back to the Kotlin pair, never a silent wrong frame.
         */
        internal fun jniRgbRotateScale(
            rgb: ByteArray,
            inW: Int,
            inH: Int,
            orientationDeg: Int,
            outW: Int,
            outH: Int,
            mode: Int,
        ): ByteArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeRgbRotateScale(rgb, inW, inH, orientationDeg, outW, outH, mode)
        }

        /**
         * JNI bridge: see nativeExportFrameTimingMs — the six sub-stage
         * slots of the frame nativeExportFrame just produced, read on the
         * export thread right after a successful call.
         */
        internal fun jniExportFrameTimingMs(): DoubleArray? =
            nativeExportFrameTimingMs()

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
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
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
            lutRole, lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer,
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
         * JNI bridge: see nativeContainerIdentity. Throws
         * IllegalStateException when the .so is absent; null otherwise
         * means the open failed (the extractor maps that to UNKNOWN
         * fields, never a fabricated identity).
         */
        internal fun jniContainerIdentity(fd: Int): ContainerIdentityK? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeContainerIdentity(fd)
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
         * JNI bridge: see nativeSetSyncRefine. Throws IllegalStateException
         * when the .so is absent; otherwise the runtime toggle is set and
         * the NEXT stabilization plan (probe or render) is built under it —
         * flipping it invalidates the plan cache, so the once-per-key
         * refinement re-runs under the new flag. Unlike the two build
         * gates this wrapper has a UI caller (the stab sheet's sync-refine
         * switch), so it is internal, not init-private.
         */
        internal fun jniSetSyncRefine(enabled: Boolean) {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            nativeSetSyncRefine(enabled)
        }

        /**
         * JNI bridge: see nativeSetPreviewStatsEnabled. Throws
         * IllegalStateException when the .so is absent; otherwise the
         * bridge-level atomic flips and the NEXT GPU preview render skips
         * (or resumes) the stats draw + readback. The jniSetSyncRefine
         * runtime-toggle pattern: internal because a UI caller (the
         * measurement run's A/B switch) owns it, not the companion init.
         */
        internal fun jniSetPreviewStatsEnabled(enabled: Boolean) {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            nativeSetPreviewStatsEnabled(enabled)
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

        /**
         * JNI bridge: see nativeGlesFragmentParity ([caseIndex] selects the
         * parity case: 0 developLinear shape, 1 the default grade's real
         * stage list, 2 the tonemap sweep — stage 23 modes 1-5, worst case
         * across the five modes). Throws IllegalStateException when the
         * .so is absent; ran=0 otherwise means "the GPU fragment pass
         * failed at a named stage" (previewLastError) — never a fabricated
         * verdict.
         */
        internal fun jniGlesFragmentParity(
            fd: Int,
            vertexSource: String,
            vertex310Source: String,
            fragmentSource: String,
            storeFragmentSource: String,
            caseIndex: Int,
        ): DoubleArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeGlesFragmentParity(
                fd, vertexSource, vertex310Source, fragmentSource, storeFragmentSource,
                caseIndex,
            )
        }

        /** JNI bridge: closeSource for export sessions. */
        internal fun jniCloseSource(handle: Long): Long = nativeCloseSource(handle)

        /**
         * JNI bridge: the production GPU develop session (M3 commit a +
         * the M5 encoder-surface pair). All throw IllegalStateException
         * when the .so is absent. Open returns 0 on failure
         * (previewLastError names the EGL/compile/link step);
         * RenderFrame returns null on failure (the session latches
         * unhealthy with the same detail — the Kotlin dispatcher falls
         * the export back to CPU). The session handle is NOT a clip
         * handle: it addresses the session registry.
         */
        internal fun jniDevelopSessionOpen(
            vertexSource: String,
            vertex310Source: String,
            preFragmentSource: String,
            postFragmentSource: String,
            tailFragmentSource: String,
            sharpnessHSource: String,
            sharpnessVSource: String,
            detailHSource: String,
            detailVSource: String,
            nrChromaHSource: String,
            nrChromaVSource: String,
            nrLumaSource: String,
            storeFragmentSource: String,
            blitFragmentSource: String,
        ): Long {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeDevelopSessionOpen(
                vertexSource, vertex310Source, preFragmentSource, postFragmentSource,
                tailFragmentSource, sharpnessHSource, sharpnessVSource,
                detailHSource, detailVSource,
                nrChromaHSource, nrChromaVSource, nrLumaSource, storeFragmentSource,
                blitFragmentSource)
        }

        internal fun jniDevelopSessionRenderFrame(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
        ): ByteArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeDevelopSessionRenderFrame(
                sessionHandle, clipHandle, frameIndex, chromaDenoise,
                noiseReduction, sharpness, detail, exposureEv, highlights,
                blackPoint, whitePoint, shadows, midtones, contrast, tempK,
                tint, saturation, asShot, wheelShadowX, wheelShadowY,
                wheelMidX, wheelMidY, wheelHighX, wheelHighY, splitShadowHue,
                splitHighlightHue, splitBalance, highlightReconstruction,
                keepVignetting, tonemap, lutPath, lutStrength, lutRole,
                lutInGamut, lutInTransfer, lutOutGamut, lutOutTransfer)
        }

        /**
         * JNI bridge: the encoder-surface seam (M5 commit a). Both throw
         * IllegalStateException when the .so is absent. SetEncoderSurface
         * takes the MediaCodec input Surface as Any? (the JVM-loadability
         * rule); the ANativeWindow reference transfers to the session at
         * call entry, so the caller never releases it. RenderFrameToEncoder
         * returns false on refusal (lastError names the step; the
         * dispatcher answers with ONE honest fallback line).
         */
        internal fun jniDevelopSessionSetEncoderSurface(
            sessionHandle: Long,
            surface: Any?,
        ): Boolean {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeDevelopSessionSetEncoderSurface(sessionHandle, surface)
        }

        /**
         * rotationDeg has NO default value ON PURPOSE: the signature-lock
         * test reflects over declared methods matching this wrapper's
         * mangled name, and a Kotlin default parameter would emit a
         * `$default` synthetic that also matches — the "exactly one
         * wrapper" assertion would see two. Every caller passes it
         * explicitly (0 for the upright case). The same no-default rule
         * holds for every parameter of every wrapper here.
         */
        internal fun jniDevelopSessionRenderFrameToEncoder(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            ptsUs: Long,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
            rotationDeg: Int,
        ): Boolean {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativeDevelopSessionRenderFrameToEncoder(
                sessionHandle, clipHandle, frameIndex, ptsUs, chromaDenoise,
                noiseReduction, sharpness, detail, exposureEv, highlights,
                blackPoint, whitePoint, shadows, midtones,
                contrast, tempK, tint, saturation, asShot, wheelShadowX,
                wheelShadowY, wheelMidX, wheelMidY, wheelHighX, wheelHighY,
                splitShadowHue, splitHighlightHue, splitBalance,
                highlightReconstruction, keepVignetting, tonemap, lutPath,
                lutStrength, lutRole, lutInGamut, lutInTransfer, lutOutGamut,
                lutOutTransfer, rotationDeg)
        }

        internal fun jniDevelopSessionClose(sessionHandle: Long) {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            nativeDevelopSessionClose(sessionHandle)
        }

        /**
         * JNI bridge: the GPU preview session (M4 commit a). All four
         * throw IllegalStateException when the .so is absent. Open returns 0
         * on failure (previewLastError names the step; the CPU preview path
         * stays the default). SetSurface takes the SurfaceView's Surface as
         * Any? (the JVM-loadability rule — see the decl's KDoc) and returns
         * false on failure; the ANativeWindow reference transfers to the
         * session at call entry, so the caller never releases it.
         * RenderFrame returns the published stats pack ([w, h, pixels...],
         * the same shape the CPU preview publishes) or null on failure (the
         * session latches unhealthy with the same detail — the Kotlin gate
         * falls the preview back to CPU). The session handle is NOT a clip
         * handle: it addresses the preview session registry.
         */
        internal fun jniPreviewSessionOpen(
            vertexSource: String,
            vertex310Source: String,
            preFragmentSource: String,
            postFragmentSource: String,
            tailFragmentSource: String,
            sharpnessHSource: String,
            sharpnessVSource: String,
            detailHSource: String,
            detailVSource: String,
            nrChromaHSource: String,
            nrChromaVSource: String,
            nrLumaSource: String,
            storeFragmentSource: String,
            windowFragmentSource: String,
        ): Long {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativePreviewSessionOpen(
                vertexSource, vertex310Source, preFragmentSource, postFragmentSource,
                tailFragmentSource, sharpnessHSource, sharpnessVSource,
                detailHSource, detailVSource,
                nrChromaHSource, nrChromaVSource, nrLumaSource, storeFragmentSource,
                windowFragmentSource)
        }

        internal fun jniPreviewSessionSetSurface(
            sessionHandle: Long,
            surface: Any?,
        ): Boolean {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativePreviewSessionSetSurface(sessionHandle, surface)
        }

        internal fun jniPreviewSessionRenderFrame(
            sessionHandle: Long,
            clipHandle: Long,
            frameIndex: Int,
            maxDim: Int,
            chromaDenoise: Float,
            noiseReduction: Float,
            sharpness: Float,
            detail: Float,
            exposureEv: Float,
            highlights: Float,
            blackPoint: Float,
            whitePoint: Float,
            shadows: Float,
            midtones: Float,
            contrast: Float,
            tempK: Float,
            tint: Float,
            saturation: Float,
            asShot: Boolean,
            wheelShadowX: Float,
            wheelShadowY: Float,
            wheelMidX: Float,
            wheelMidY: Float,
            wheelHighX: Float,
            wheelHighY: Float,
            splitShadowHue: Float,
            splitHighlightHue: Float,
            splitBalance: Float,
            highlightReconstruction: Boolean,
            keepVignetting: Boolean,
            tonemap: Int,
            lutPath: String?,
            lutStrength: Float,
            lutRole: Int,
            lutInGamut: Int,
            lutInTransfer: Int,
            lutOutGamut: Int,
            lutOutTransfer: Int,
        ): IntArray? {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            return nativePreviewSessionRenderFrame(
                sessionHandle, clipHandle, frameIndex, maxDim, chromaDenoise,
                noiseReduction, sharpness, detail, exposureEv, highlights,
                blackPoint, whitePoint, shadows, midtones,
                contrast, tempK, tint, saturation, asShot, wheelShadowX,
                wheelShadowY, wheelMidX, wheelMidY, wheelHighX, wheelHighY,
                splitShadowHue, splitHighlightHue, splitBalance,
                highlightReconstruction, keepVignetting, tonemap, lutPath,
                lutStrength, lutRole, lutInGamut, lutInTransfer, lutOutGamut,
                lutOutTransfer)
        }

        internal fun jniPreviewSessionClose(sessionHandle: Long) {
            if (!nativeAvailable) {
                throw IllegalStateException(nativeUnavailableMessage)
            }
            nativePreviewSessionClose(sessionHandle)
        }

        /** Last-error detail from the engine thread (thread-local in the bridge). */
        internal fun previewLastError(): String? = nativeLastError()
    }
}
