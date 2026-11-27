package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.data.timeline.TimelineExportPlan
import com.ritim.ritimcraw.data.timeline.TimelineStore
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Exports a TIMELINE as an SDR H.264 MP4 (the timeline's first export,
 * TL-EX): walks the [TimelineExportPlan] built from the job's snapshotted
 * project ([ExportJob.timeline], captured at enqueue — a later edit must
 * not change an in-flight export), rendering the VIDEO track clip by clip
 * in timeline order, honoring trims/splits/order, with BLACK frames for
 * gaps and skipped clips.
 *
 * THE PLAN IS THE RECIPE: [TimelineExportPlan.build] already decided
 * which source frame each output frame shows (frame-nearest mapping, NO
 * resampling — v1, documented there), where the black runs are, and
 * what was honestly skipped ([fpsMeasured] is the ONLY source rate; a
 * source that went missing or UNKNOWN after enqueue skips honestly,
 * never guesses). This exporter only executes it: decode, rotate, scale,
 * convert, submit at the output grid's pts.
 *
 * V1 LIMITS (honest, KDoc'd — the scope of THIS milestone):
 *  - AUDIO: VIDEO-ONLY. The plan's [TimelineExportPlan.audioSkipped]
 *    marker (true when the project holds AUDIO-track clips) is logged to
 *    the stage log — the audio was consciously left out, not lost.
 *    Mixing placed PCM at per-clip rates/gains/fades is AUD-05/07.
 *  - TIMING: output pts are the OUTPUT grid's — frame i lands at
 *    i * 1_000_000 us / plan.fps — because the movie's timebase is the
 *    project's frame grid (TimelineModel's TIMEBASE contract), not any
 *    source's. Frame-nearest mapping already preserved real time; the
 *    pts spacing carries it to the muxer.
 *  - ORIENTATION: each source's frames are rotated UPRIGHT by the
 *    session's container orientation (the per-clip export's pixel
 *    rotation, owner request 2026-11-11 section 5 — even players that
 *    ignore rotation metadata see it right-side up). TimelineOutput
 *    .orientationDeg is NOT applied on top (v1: the output is upright
 *    per source; a whole-timeline turn is a later milestone).
 *  - COLOR: the writer's existing handling is the whole story — SDR
 *    Rec.709 limited range (MediaCodecMp4Writer). TimelineOutput
 *    .colorSpace/.transfer/.hdr are NOT honored beyond that (the same
 *    stance as the per-clip MP4 export; HDR/P3/2020 outputs are later
 *    milestones).
 *  - FRAME SIZE: the reconcile choice lives in [RgbScale] (aspect-fill
 *    center-crop, bilinear, pure Kotlin) — see its KDoc for why.
 *  - BACKEND: CPU only. The packed writeVideoFrame path — no encoder
 *    Surface, no planes, no GPU members. The per-clip export's GPU
 *    affordance does not apply to the timeline's multi-source walk v1.
 *
 * FAILURE SEMANTICS (the honest-completion rule): a source that fails to
 * OPEN renders its entry's frames BLACK with the failure in the stage
 * log — the export COMPLETES and reports, it never dies whole over one
 * clip (the plan's skip philosophy, extended to open-time failures; a
 * mid-render frame failure is the same: that frame renders black and
 * the walk continues). Only failures of the OUTPUT (writer start,
 * write, finish, finalize) fail the job.
 *
 * THE SEAMS (the app wires real ones, tests fakes — the Mp4Exporter
 * pattern): [session] is the shared native boundary; [sourceFdFor]
 * turns a sourceId into its fd, called ONCE per DISTINCT source per
 * run (a timeline may place the same source many times — one open, one
 * session, closed at the end); [sources]/[fpsMeasured]/[frameCount]/[gradeFor]
 * resolve the WORLD at run time (the snapshot froze user intent, not
 * the world — the same live-world semantics as the per-clip export's
 * fps/frame metadata); [writerFactory] builds the MP4 writer;
 * [converter] is the RGB -> I420 step (native twin, Kotlin fallback —
 * byte-identical either way).
 *
 * The whole pipeline runs on the shared export engine thread
 * ([ExportEngine]) — one motioncam decoder is not concurrency-safe and
 * the bridge's error state is thread-local. One job at a time.
 * Staging per the spec: the muxer writes to `out.partial`; only a
 * finished export is renamed to [out] (ExportStaging).
 */
class TimelineMp4Exporter(
    private val session: Mp4Session = NativeMp4Session,
    private val writerFactory: () -> Mp4MediaWriter = { MediaCodecMp4Writer() },
    private val engine: ExecutorService = ExportEngine.thread,
    private val sourceFdFor: (String) -> Int,
    private val sources: () -> Map<String, MediaSource>,
    private val fpsMeasured: (String) -> Double?,
    private val frameCount: (String) -> Int?,
    /** The clip's CURRENT source grade from GradeStore by sourceId. */
    private val gradeFor: (String) -> GradeK,
    /** The LUT path seam (the per-clip export's lutPathFor pattern). */
    private val lutPathFor: (String?) -> String? = { null },
    /** Per-export stage log sink; null = no logging. */
    private val stageLog: ((String) -> Unit)? = null,
    /**
     * The RGB888 -> I420 conversion seam (the per-clip export's
     * [ExportPipeline.converter] pattern): the default dispatches to the
     * byte-exact native twin with the Kotlin YuvConverter.toI420 as
     * fallback (see [NativeRgbToI420]); JVM tests inject a pure fake. The
     * conversion's BYTES are identical either way — that is the seam's
     * whole contract (core/src/Yuv.cpp).
     */
    private val converter: RgbToI420 = NativeRgbToI420,
    /**
     * The rotate + aspect-fill seam (the [converter] pattern, for the two
     * Kotlin passes the frame loop ran before the conversion): the default
     * dispatches to the fused native twin with the unchanged Kotlin pair
     * RotationState.rotateRgb888 + RgbScale.aspectFill as fallback (see
     * [NativeRgbRotateScale]); JVM tests inject a pure fake. The BYTES are
     * identical either way — that is the seam's whole contract.
     */
    private val rotateScale: RgbRotateScale = NativeRgbRotateScale,
    /** (E1b) The live encoder facts the plan resolves against (the device layer; JVM tests inject). */
    private val encoderFacts: () -> List<EncoderFacts> = { DeviceEncoderFacts.all() },
    /** (E1b) Where the plan trail goes: the "ritim-export" log. A failing sink never fails an export. */
    private val planLog: (String) -> Unit = { line -> runCatching { android.util.Log.i("ritim-export", line) } },
    /**
     * (G4/E2) The device's API level — the HLG gate's guard data. The
     * default reads Build.VERSION.SDK_INT (JVM tests inject a fixed value;
     * the Mp4Exporter seam's twin).
     */
    private val apiLevel: () -> Int = { android.os.Build.VERSION.SDK_INT },
) {

    /**
     * Exports the job's timeline as an MP4 at [out]. Returns the updated
     * job: DONE on success (detail = output file name), FAILED with a
     * stage + cause + recovery detail on failure. Never throws; a failure
     * leaves neither [out] nor its partial behind.
     *
     * [onProgress] hears (framesDone, framesTotal = plan.totalFrames) per
     * frame; the queue owns the throttled persistence.
     */
    suspend fun export(
        job: ExportJob,
        out: File,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ): ExportJob {
        val ref = job.timeline
            ?: return failed(
                job,
                "plan", "the job has no timeline snapshot",
                "re-queue the export from the timeline",
            )
        val partial = ExportStaging.partial(out)
        val run = job.settings?.let { ExportPlanRun(it, encoderFacts, ExportContext(), planLog) }
        val startedNs = System.nanoTime()
        val outcome: Result<Unit> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    val res = try {
                        while (true) {
                            try {
                                exportOnce(ref, partial, onProgress, run)
                                break
                            } catch (e: VideoConfigRejected) {
                                // E1b: the encoder refused the plan's format in
                                // start(), before any frame. AT MOST ONE plan-fix
                                // retry with a fresh writer; otherwise the job
                                // fails with the encoder's own reason. (The
                                // timeline has no Surface path, so no Surface->CPU
                                // step precedes it.)
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
                                    kotlinMessage(
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
                            kotlinMessage(
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

    /** One whole-pipeline attempt: plan, walk, encode, finish, release. */
    private fun exportOnce(
        ref: TimelineRef,
        partial: File,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)?,
        planRun: ExportPlanRun? = null,
    ) {
        // The snapshot back to a project (row-tolerant parse upstream; a
        // null here is a degraded row — fail honestly, never guess).
        val project = try {
            TimelineStore.parseTimeline(ref.projectJson)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                kotlinMessage(
                    "plan",
                    "the queued timeline snapshot could not be read",
                    "re-queue the export from the timeline",
                    e.message,
                )
            )
        }
        val plan = TimelineExportPlan.build(
            project = project,
            sources = sources(),
            fpsMeasured = fpsMeasured,
            frameCount = frameCount,
        )
        if (plan.totalFrames <= 0) {
            throw IllegalStateException(
                kotlinMessage(
                    "plan",
                    "the timeline resolves to zero frames (no video clips)",
                    "place a clip on the video track, then retry",
                )
            )
        }
        stageLog?.invoke(
            "timeline plan: frames=${plan.totalFrames} fps=${plan.fps} " +
                "size=${plan.width}x${plan.height} entries=${plan.entries.size} " +
                "skips=${plan.skips.size} audioSkipped=${plan.audioSkipped}"
        )
        for (s in plan.skips) {
            stageLog?.invoke(
                "timeline skip: clip=${s.clipId} source=${s.sourceId} reason=${s.reason}"
            )
        }
        if (plan.audioSkipped) {
            stageLog?.invoke("timeline audio: skipped (v1 exports the video track only)")
        }

        val black = blackI420(plan.width, plan.height)
        val writer = writerFactory()
        try {
            if (planRun != null) {
                // E1b: the project fps and the timeline's output size are the
                // plan's inputs (never settings).
                // G4 (E2 wiring): the HLG gate's probed facts for THIS
                // timeline's shape, the same fold the per-clip export runs
                // (null when the request is not HLG or the facts are empty
                // — the E1 wall then stands, today's behavior).
                val hlgGate = if (planRun.current.dynamicRange == ExportDynamicRange.HLG) {
                    val facts = planRun.currentFacts()
                    if (facts.isEmpty()) null else HevcProbeSource.fromFacts(
                        facts, plan.width, plan.height, plan.fps.toDouble(), apiLevel(),
                    )
                } else {
                    null
                }
                val ep = planRun.planFor(
                    plan.width, plan.height, plan.fps.toDouble(),
                    ExportContext(tenBitPathAvailable = false, hlgGate = hlgGate),
                )
                planRun.durationSec = plan.totalFrames / plan.fps.toDouble()
                if (!ep.ok) throw IllegalStateException(ep.errorMessage())
                writer.expectVideoConfig()
                writer.configureVideo(ep)
            }
            // Video-only: 0/0 sample rate/channels (the writer contract).
            writer.start(
                partial.absolutePath, plan.width, plan.height,
                0, 0, 0,
            )
            planRun?.encoder = writer.encoderIdentity()
            // Open-once cache: a source placed N times opens ONCE. The
            // handles map also REMEMBERS failures (-1 sentinel), the
            // geometry map holds each opened source's session info.
            val handles = HashMap<String, Long>()
            val geometry = HashMap<String, Mp4SessionInfo>()
            try {
                var done = 0
                onProgress?.invoke(0, plan.totalFrames)
                for (entry in plan.entries) {
                    when (entry) {
                        is TimelineExportPlan.Entry.Black -> {
                            for (i in 0 until entry.frameCount) {
                                writer.writeVideoFrame(
                                    pts(done, plan.fps), black,
                                )
                                done++
                                onProgress?.invoke(done, plan.totalFrames)
                            }
                        }
                        is TimelineExportPlan.Entry.Clip -> {
                            val handle = openCached(handles, geometry, entry.sourceId)
                            if (handle == null) {
                                // Failed open: the entry's frames render
                                // BLACK (the honest-completion rule — the
                                // entry's span is never skipped, the
                                // movie keeps its length and timing).
                                for (i in 0 until entry.frameCount) {
                                    writer.writeVideoFrame(pts(done, plan.fps), black)
                                    done++
                                    onProgress?.invoke(done, plan.totalFrames)
                                }
                                continue
                            }
                            val grade = gradeFor(entry.sourceId)
                            val lutPath = lutPathFor(
                                if (grade.lutId.isNullOrBlank()) null else grade.lutId,
                            )
                            var lastFrame: Int = -1
                            var lastRgb: ByteArray? = null
                            for (f in entry.sourceFrames) {
                                val rgb = if (f == lastFrame && lastRgb != null) {
                                    // Repeated source frame (frame-nearest
                                    // mapping repeats): reuse the decoded
                                    // frame — decode once per DISTINCT frame.
                                    lastRgb
                                } else {
                                    val rendered = session.renderFrame(
                                        handle, f, grade, lutPath, null,
                                    )
                                    if (rendered == null) {
                                        // Mid-render failure: this frame
                                        // renders black, the walk continues
                                        // (the honest-completion rule).
                                        stageLog?.invoke(
                                            "timeline render: clip=${entry.clipId} " +
                                                "frame=$f failed code=${session.code()} " +
                                                "stage=${session.stage()} — black"
                                        )
                                        lastFrame = -1
                                        lastRgb = null
                                        writer.writeVideoFrame(pts(done, plan.fps), black)
                                        done++
                                        onProgress?.invoke(done, plan.totalFrames)
                                        continue
                                    }
                                    lastFrame = f
                                    lastRgb = rendered
                                    rendered
                                }
                                val info = geometry[entry.sourceId]
                                val scaled = rotateScaleFrame(
                                    rgb,
                                    info?.width ?: 0,
                                    info?.height ?: 0,
                                    info?.orientationDeg ?: 0,
                                    plan.width, plan.height,
                                )
                                val converted = convertFrame(
                                    scaled.rgb, plan.width, plan.height,
                                )
                                writer.writeVideoFrame(pts(done, plan.fps), converted.i420)
                                done++
                                onProgress?.invoke(done, plan.totalFrames)
                            }
                        }
                    }
                }
            } finally {
                // Close only REAL handles: a -1 sentinel is a remembered
                // failure, never an opened session — closing it would hand
                // a bogus handle to native.
                for (h in handles.values) {
                    if (h > 0L) session.close(h)
                }
            }
            if (!writer.finish()) {
                throw IllegalStateException(
                    kotlinMessage(
                        Mp4ExportErrors.STAGE_MUX,
                        "the muxer could not finish the movie",
                        "check free space and retry",
                    )
                )
            }
        } finally {
            writer.release()
        }
    }

    /**
     * The open-once cache: opens [sourceId] on first need, reuses the
     * handle after. A failed open renders the entry's frames BLACK (the
     * caller's continue) with the failure in the stage log — the export
     * completes honestly. The cache REMEMBERS the failure (a source
     * that failed once does not retry per frame): the -1 sentinel reads
     * back as null on a cache hit, so a later entry with the same source
     * takes the black path instead of rendering against a bogus handle.
     * [geometry] receives the session info of every successful open
     * (the per-source rotation/scaling facts, used by the frame loop).
     */
    private fun openCached(
        handles: HashMap<String, Long>,
        geometry: HashMap<String, Mp4SessionInfo>,
        sourceId: String,
    ): Long? {
        val cached = handles[sourceId]
        if (cached != null) {
            return if (cached > 0L) cached else null
        }
        val fd = sourceFdFor(sourceId)
        if (fd < 0) {
            stageLog?.invoke(
                "timeline open: source=$sourceId unavailable (no fd) — its frames render black"
            )
            handles[sourceId] = -1L
            return null
        }
        val info = session.open(fd)
        if (info == null || info.handle <= 0L) {
            stageLog?.invoke(
                "timeline open: source=$sourceId failed " +
                    "code=${session.code()} stage=${session.stage()} " +
                    "detail=${session.detail()} — its frames render black"
            )
            handles[sourceId] = -1L
            return null
        }
        handles[sourceId] = info.handle
        geometry[sourceId] = info
        return info.handle
    }

    /** Output-grid pts: frame [i] at i seconds / fps, in microseconds. */
    private fun pts(i: Int, fps: Int): Long = i.toLong() * 1_000_000L / fps

    /**
     * One frame's rotate + aspect-fill through the seam, plus the debug
     * dual-run (the [convertFrame] pattern — the byte-exactness
     * affordance): the FIRST frame produced by the native path of an
     * export is also computed by the Kotlin reference pair
     * (RotationState.rotateRgb888 + RgbScale.aspectFill, both UNTOUCHED —
     * they stay the export's fallback AND its reference), and the
     * comparison lands in the stage-log sink (a null stageLog means the
     * dual-run never runs, so release exports pay nothing). The native
     * bytes always win: a mismatch is logged, never applied.
     */
    private fun rotateScaleFrame(
        rgb: ByteArray,
        inW: Int,
        inH: Int,
        orientationDeg: Int,
        outW: Int,
        outH: Int,
    ): RgbRotateScaleResult {
        val traced = rotateScale.rotateScale(rgb, inW, inH, orientationDeg, outW, outH)
        if (stageLog != null &&
            RgbToI420Verify.shouldDualRun(nativeRotateScaleSeen, traced.fromNative)
        ) {
            val upright = RotationState.rotateRgb888(rgb, inW, inH, orientationDeg)
            val kt = RgbScale.aspectFill(
                upright.rgb, upright.width, upright.height, outW, outH,
            )
            RgbRotateScaleVerify.line(outW, outH, traced.rgb, kt)?.let { stageLog(it) }
        }
        if (traced.fromNative) nativeRotateScaleSeen++
        return traced
    }

    /** Native-produced rotate+scale frames so far this export (the dual-run counter). */
    private var nativeRotateScaleSeen = 0

    /**
     * One frame's conversion through the seam, plus the debug dual-run
     * (the per-clip export's [ExportPipeline.convertFrame] pattern — the
     * byte-exactness affordance): the FIRST frame produced by the native
     * path of an export is converted by Kotlin too, and the comparison
     * lands in the stage-log sink (a null stageLog means the dual-run
     * never runs, so release exports pay nothing). The native bytes
     * always win: a mismatch is logged, never applied.
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

    private fun failed(
        job: ExportJob,
        stage: String,
        cause: String,
        recovery: String,
        detail: String? = null,
    ): ExportJob = job.copy(
        state = State.FAILED,
        detail = kotlinMessage(stage, cause, recovery, detail),
    )

    private fun kotlinMessage(
        stage: String,
        cause: String,
        recovery: String,
        detail: String? = null,
    ): String {
        val base = "Timeline export failed (stage: $stage): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }

    companion object {

        /**
         * The packed black I420 frame (YuvConverter.toI420 layout): Y=16
         * (limited-range black), U=V=128 (neutral chroma).
         */
        fun blackI420(width: Int, height: Int): ByteArray {
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            val out = ByteArray(width * height + 2 * cw * ch)
            java.util.Arrays.fill(out, 0, width * height, 16.toByte())
            java.util.Arrays.fill(out, width * height, out.size, 128.toByte())
            return out
        }
    }
}

/**
 * The timeline frame loop's rotate + aspect-fill seam (the [RgbToI420]
 * pattern): the loop calls through this so it stays JVM-testable while
 * production dispatches to the fused native twin. The result carries the
 * bytes' ORIGIN (native vs Kotlin) so the loop's debug dual-run knows when
 * it has a native frame to compare against — without sniffing the
 * implementation. The BYTES are identical either way; that is the seam's
 * whole contract.
 */
fun interface RgbRotateScale {
    /**
     * Rotates [rgb] (packed row-major RGB888, [inW]*[inH]*3, sensor-
     * oriented) upright by [orientationDeg] (0/90/180/270 CW, the
     * session's container orientation) and aspect-fills it to
     * [outW]*[outH] (center-crop cover scale, bilinear). Byte-exact to
     * RotationState.rotateRgb888 followed by RgbScale.aspectFill.
     */
    fun rotateScale(
        rgb: ByteArray,
        inW: Int,
        inH: Int,
        orientationDeg: Int,
        outW: Int,
        outH: Int,
    ): RgbRotateScaleResult
}

/** One rotate + aspect-fill outcome: the bytes plus where they came from. */
class RgbRotateScaleResult(
    /** The upright, output-sized RGB888 frame. */
    val rgb: ByteArray,
    /** True when [rgb] came from the native twin, false from Kotlin. */
    val fromNative: Boolean,
)

/**
 * The production rotate + aspect-fill dispatch (the [RgbRotateScale]
 * seam's default): the fused native twin first (jni_bridge.cpp
 * nativeRgbRotateScale — one bilinear pass, no intermediate upright
 * buffer, reached through NativeSource.jniRgbRotateScale), the unchanged
 * Kotlin pair RotationState.rotateRgb888 + RgbScale.aspectFill as the
 * fallback. The fallback keeps the export working wherever the native
 * entry refuses — a non-NDK build (nativeAvailable false), a malformed
 * request (the native entry's null-return with the error state set; the
 * Kotlin require() then fails the export the same way it always did,
 * never a silent wrong frame).
 *
 * JVM tests never touch this object: the exporter's default is injectable
 * (the rotateScale parameter), and the fakes stay pure.
 */
object NativeRgbRotateScale : RgbRotateScale {

    /** mode 0 = aspect-fill center-crop (the only policy v1 ships). */
    private const val MODE_ASPECT_FILL = 0

    override fun rotateScale(
        rgb: ByteArray,
        inW: Int,
        inH: Int,
        orientationDeg: Int,
        outW: Int,
        outH: Int,
    ): RgbRotateScaleResult {
        if (NativeSource.nativeAvailable) {
            val native = NativeSource.jniRgbRotateScale(
                rgb, inW, inH, orientationDeg, outW, outH, MODE_ASPECT_FILL,
            )
            if (native != null) return RgbRotateScaleResult(native, fromNative = true)
            // A null return with the library loaded is a malformed request
            // (empty frame / short buffer / unknown mode): fall through to
            // Kotlin, whose require() turns it into the export's existing
            // failure mode.
        }
        val upright = RotationState.rotateRgb888(rgb, inW, inH, orientationDeg)
        val scaled = RgbScale.aspectFill(
            upright.rgb, upright.width, upright.height, outW, outH,
        )
        return RgbRotateScaleResult(scaled, fromNative = false)
    }
}

/**
 * Pure decision object for the rotate + scale debug dual-run's LINE (the
 * [RgbToI420Verify] pattern): WHAT to log after the reference pair ran.
 * The WHEN rule is shared — [RgbToI420Verify.shouldDualRun] (the first
 * frame produced by the native path of an export; a Kotlin fallback frame
 * never dual-runs, there is no native output to compare against).
 */
object RgbRotateScaleVerify {

    /**
     * The line logged after a dual-run, or null when nothing should be
     * logged (the sizes differ — a bug that cannot happen: both paths
     * produce outW*outH*3 bytes — but a missing line is better than a
     * crash mid-export).
     */
    fun line(
        width: Int,
        height: Int,
        native: ByteArray,
        kotlin: ByteArray,
    ): String? {
        if (native.size != kotlin.size) return null
        var mismatch = -1
        for (i in native.indices) {
            if (native[i] != kotlin[i]) {
                mismatch = i
                break
            }
        }
        return if (mismatch < 0) {
            "rgbRotateScale dual-run ${width}x$height: native == kotlin (${native.size} bytes)"
        } else {
            "rgbRotateScale dual-run ${width}x$height: MISMATCH at byte $mismatch of ${native.size} " +
                "(native=${native[mismatch]} kotlin=${kotlin[mismatch]})"
        }
    }
}
