package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.NativeSource
import java.io.OutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure mapping from a failed CinemaDNG sequence export to the user-facing
 * message (DESIGN.md Error states: name the stage, the cause and the
 * recovery — never a bare "failed"). Kept free of Android imports so it
 * is JVM-testable. The stage names follow the sequence's own flow
 * (plan -> frames -> audio -> finish), the cause/recovery wording shares
 * [DngExportErrors]' ErrorCode table (the same native failure codes).
 */
object CinemaDngExportErrors {

    /** The Kotlin-side stages of the sequence export. */
    const val STAGE_PLAN = "plan"
    const val STAGE_FRAMES = "frames"
    const val STAGE_AUDIO = "audio"
    const val STAGE_FINISH = "finish"

    /** One-line message naming stage + cause + recovery. */
    fun message(stage: String, cause: String, recovery: String, detail: String? = null): String {
        val base = "CinemaDNG export failed (stage: $stage): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }

    /**
     * Maps a native failure (code + stage + detail from the bridge's
     * thread-locals) onto the same wording [DngExportErrors] uses for the
     * single-frame path, under the sequence's stage.
     */
    fun nativeMessage(stage: String, code: Int, nativeStage: Int, detail: String?): String {
        val (cause, recovery) = DngExportErrors.causeAndRecovery(code)
        val named = DngExportErrors.stageName(nativeStage)
        return message(stage, cause, recovery, if (detail != null) "$named: $detail" else null)
    }
}

/**
 * The native + storage seams the sequence exporter needs, as an interface
 * so the export loop is JVM-testable with fakes (the real calls need
 * librawedit_jni.so and SAF — device-gated).
 *
 * THE ONE-SHOT FRAME DOOR (see NativeSource.jniCinemaDngExportFrame):
 * writes one frame's DNG straight to the SAF write fd. The exporter owns
 * the loop, the naming, the cancel/partial rules and the WAV; this seam
 * only turns a frame into a file.
 */
interface CinemaDngIo {
    /**
     * Writes sequence frame [frameIndex] (clip-relative) of the clip at
     * [sourceFd] into the child document's WRITE fd [outFd], with the
     * sequence identity (exact fps rational, START timecode, the frame's
     * offset from frame 0). True on success; on false [code]/[stage]/
     * [detail] say why (the thread-local contract — read from the same
     * engine thread).
     */
    fun writeFrame(
        sourceFd: Int,
        frameIndex: Int,
        fpsNum: Int,
        fpsDen: Int,
        tcHour: Int,
        tcMinute: Int,
        tcSecond: Int,
        tcFrame: Int,
        frameOffset: Int,
        outFd: Int,
    ): Boolean

    /** rawedit::ErrorCode of the last failed call (0 ok, 1 truncated, 2 badHeader, 3 ioError, 4 unsupported). */
    fun code(): Int

    /** Native stage token of the last failed call (0 open, 1 frame read, 3 dng write). */
    fun stage(): Int

    /** Bridge detail of the last failed call. */
    fun detail(): String?

    /**
     * Creates the output folder's child document [name] and returns its
     * WRITE fd, or -1 when it cannot be created (the caller maps the
     * failure honestly — no file is written for that frame).
     */
    fun openChildWriteFd(name: String): Int

    /** Closes an fd [openChildWriteFd] returned (no-op for -1). */
    fun closeFd(fd: Int)

    /**
     * Deletes the child document [name] (a failed/cancelled frame's
     * half-written file must not stay; the WRITTEN frames are never
     * deleted — the partial rule). Best-effort: false never fails the
     * job.
     */
    fun deleteChild(name: String): Boolean

    /** The target folder's free space in bytes, or -1 when unknown. */
    fun freeBytes(): Long

    /**
     * [sampleRate, channels] of the clip behind [sourceFd], or null when
     * the clip has no audio / the read failed (the honest "no audio"
     * state — a WAV is then simply not written, never fabricated).
     */
    fun audioInfo(sourceFd: Int): IntArray?

    /**
     * The whole clip's PCM (interleaved, 16-bit), or null on failure.
     */
    fun readAudio(sourceFd: Int): ShortArray?

    /**
     * Opens the child document [name] as an OutputStream (the WAV's
     * sink), or null when it cannot be created.
     */
    fun openChildStream(name: String): OutputStream?
}

/** The real boundary: JNI calls into librawedit_jni.so + the SAF folder. */
internal class NativeCinemaDngIo(
    private val folder: CinemaDngFolder,
) : CinemaDngIo {

    override fun writeFrame(
        sourceFd: Int,
        frameIndex: Int,
        fpsNum: Int,
        fpsDen: Int,
        tcHour: Int,
        tcMinute: Int,
        tcSecond: Int,
        tcFrame: Int,
        frameOffset: Int,
        outFd: Int,
    ): Boolean = NativeSource.jniCinemaDngExportFrame(
        sourceFd, frameIndex, fpsNum, fpsDen,
        tcHour, tcMinute, tcSecond, tcFrame, frameOffset, outFd,
    )

    override fun code(): Int = NativeSource.jniPreviewErrorCode()
    override fun stage(): Int = NativeSource.jniPreviewErrorStage()
    override fun detail(): String? = NativeSource.previewLastError()

    override fun openChildWriteFd(name: String): Int =
        folder.openChildWriteFd(name)

    override fun closeFd(fd: Int) = folder.closeFd(fd)

    override fun deleteChild(name: String): Boolean = folder.deleteChild(name)

    override fun freeBytes(): Long = folder.freeBytes()

    override fun audioInfo(sourceFd: Int): IntArray? {
        // The MP4 exporter's audio-info path is handle-based; the sequence
        // path is one-shot (the fd), so this opens its own throwaway
        // handle through the same preview-open seam the MP4 session uses.
        val info = NativeSource.jniAudioInfoFor(sourceFd) ?: return null
        return if (info.size >= 2 && info[0] > 0 && info[1] > 0) info else null
    }

    override fun readAudio(sourceFd: Int): ShortArray? =
        NativeSource.jniReadAudioFor(sourceFd)

    override fun openChildStream(name: String): OutputStream? = folder.openChildStream(name)
}

/**
 * The export's result shape (the partial-result carrier, the core
 * DngSequenceWriter's Kotlin twin): what the loop wrote, how it ended,
 * and the honest numbers for the Exports row.
 */
data class CinemaDngResult(
    val state: State,
    /** The frames actually on disk (the partial set counts). */
    val framesWritten: Int,
    val framesTotal: Int,
    /** The written file names, ascending output order (the partial set). */
    val writtenNames: List<String>,
    /** The WAV's name when it was written, else null. */
    val wavName: String? = null,
    /** The failure detail for FAILED (stage + cause + recovery). */
    val failDetail: String? = null,
    /** True when the WAV was skipped because the clip has no audio. */
    val audioAbsent: Boolean = false,
) {
    /** DONE with every frame written; CANCELLED/FAILED keep the partial set. */
    val complete: Boolean get() = state == State.DONE && framesWritten == framesTotal
}


/**
 * Exports one MCRAW clip as a CinemaDNG sequence (night E-F,
 * docs/CINEMADNG_EXPORT_PLAN.md): every frame of the range as an
 * uncompressed CFA DNG in the user's SAF folder, plus the clip's PCM as
 * a WAV beside it.
 *
 * THE CONTRACT (the plan's rules, one to one):
 *  - RAW ONLY (owner decision): no grade, no develop, no NR, no geometry
 *    — the CFA bytes are the source's own. The native door never sees a
 *    grade.
 *  - RANGE: the whole clip by default; [CinemaDngSettings.firstFrame]/
 *    [lastFrame] carry the selected span (the model's inclusive,
 *    clip-relative numbering; lastFrame -1 = whole clip).
 *  - NAMING: [CinemaDngNaming] (the parameterized pattern, default
 *    <prefix>_<frame:06>.dng).
 *  - PROGRESS: [onProgress] after every frame (the queue's throttled
 *    writer persists it).
 *  - CANCEL: [onProgress] is the queue's cooperative-cancel boundary —
 *    a [ExportCancelled] thrown there stops the loop, the WRITTEN frames
 *    STAY (the plan's partial rule: "yazılan kareler korunur, sessizce
 *    silme YOK"), and the one half-written frame's document is deleted.
 *  - FAILURE: a frame write failure stops the loop with the written
 *    frames preserved; the job is FAILED with the honest count.
 *  - AUDIO: the clip's PCM as a WAV (no resampling) — but only when the
 *    clip HAS audio; an absent track skips the WAV honestly (a silent
 *    no-audio state is a lie of omission, a fabricated WAV a lie of
 *    commission — neither).
 *  - STORAGE: the plan's gate runs BEFORE the first frame
 *    ([CinemaDngPlan.storageGate]); a mid-write exhaustion ends the job
 *    PARTIAL (written frames preserved).
 *
 * The whole loop runs on the shared export engine thread ([engine]) —
 * one motioncam decoder, one thread-local error state, one job at a
 * time (the queue serializes before it ever gets here).
 */
class CinemaDngExporter(
    private val io: CinemaDngIo,
    private val engine: ExecutorService = ExportEngine.thread,
) {

    /**
     * Exports the clip at [sourceFd] per [settings] (the job's snapshot).
     * Returns the terminal job: DONE with detail = "<written>/<total>
     * frames · <folder label>", CANCELLED with the partial count, or
     * FAILED with the stage + cause + recovery. Never throws.
     */
    suspend fun export(
        job: ExportJob,
        sourceFd: Int,
        settings: CinemaDngSettings,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)? = null,
    ): ExportJob {
        val outcome: Result<CinemaDngResult> = suspendCoroutine { cont ->
            try {
                engine.execute {
                    cont.resume(runCatching { run(job, sourceFd, settings, onProgress) })
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(
                    Result.failure(
                        IllegalStateException(
                            CinemaDngExportErrors.message(
                                CinemaDngExportErrors.STAGE_PLAN,
                                "export engine unavailable",
                                "retry the export",
                            )
                        )
                    )
                )
            }
        }
        return outcome.fold(
            onSuccess = { r -> job.copy(state = r.state, detail = detailOf(r)) },
            onFailure = { e -> job.copy(state = State.FAILED, detail = e.message ?: "unknown failure") },
        )
    }

    private fun detailOf(r: CinemaDngResult): String = when (r.state) {
        // DONE: the counts, the WAV's name when it was written, and the
        // HONEST no-audio line when it was not (the auditor's
        // lie-of-omission fix: a silent clip reads "no audio", never a
        // bare "N/N frames" that hides the WAV's absence).
        State.DONE -> "${r.framesWritten}/${r.framesTotal} frames" +
            (r.wavName?.let { " · $it" } ?: "") +
            (if (r.audioAbsent) " · no audio" else "")
        State.CANCELLED -> "cancelled after ${r.framesWritten}/${r.framesTotal} frames (written frames kept)"
        State.FAILED -> r.failDetail ?: "unknown failure"
        else -> "${r.framesWritten}/${r.framesTotal} frames"
    }

    /**
     * The loop itself (engine thread). The plan's own gates run first;
     * every frame write is one native door call; the cancel check rides
     * the progress callback (the queue throws ExportCancelled there).
     */
    internal fun run(
        job: ExportJob,
        sourceFd: Int,
        settings: CinemaDngSettings,
        onProgress: ((framesDone: Int, framesTotal: Int) -> Unit)?,
    ): CinemaDngResult {
        // 1. The plan (pure): range, sizes, the storage gate. The caller
        //    (the sheet) already showed these; the run re-checks because
        //    the world may have moved since the tap (the plan's own rule).
        val clipFrames = settings.clipFrameCount
        val frames = CinemaDngPlan.frameCount(
            settings.firstFrame, settings.lastFrame, clipFrames,
        )
        if (frames == CinemaDngPlan.REJECT) {
            throw IllegalStateException(
                CinemaDngExportErrors.message(
                    CinemaDngExportErrors.STAGE_PLAN,
                    "unusable frame range (first=${settings.firstFrame} last=${settings.lastFrame} clip=$clipFrames)",
                    "re-open the export sheet and pick a shorter range",
                )
            )
        }
        // The dims/fps for the plan: the settings snapshot carries them
        // (resolved at enqueue from the clip's own facts, the same
        // snapshot rule as the grade).
        val free = io.freeBytes()
        val plan = CinemaDngPlan.of(
            clipFrameCount = clipFrames,
            firstFrame = settings.firstFrame,
            lastFrame = settings.lastFrame,
            width = settings.frameWidth,
            height = settings.frameHeight,
            fps = settings.fpsNum.toDouble() / settings.fpsDen,
            sampleRate = settings.audioSampleRate,
            channels = settings.audioChannels,
            // An UNMEASURABLE free space (-1: SAF providers expose no
            // portable free-space API) never locks the export — the plan
            // runs with the gate skipped and the honest mid-write
            // exhaustion is the backstop. A KNOWN free space (>= 0)
            // drives the plan's own lock/warn rule.
            freeBytes = if (free >= 0L) free else Long.MAX_VALUE,
        )
        if (plan.storage is StorageGate.Locked) {
            throw IllegalStateException(
                CinemaDngExportErrors.message(
                    CinemaDngExportErrors.STAGE_PLAN,
                    "not enough free space in the folder " +
                        "(${plan.requiredBytes} bytes needed)",
                    "pick a shorter range or free space, then retry",
                )
            )
        }

        val prefix = CinemaDngNaming.safePrefix(settings.prefix)
        val total = frames
        val written = ArrayList<String>(total)
        val first = settings.firstFrame

        // 2. The frames. The FIRST report lands before any write (the
        //    queue's first-progress rule: the row shows 0/N immediately);
        //    every report is the queue's cancel boundary (the queue throws
        //    ExportCancelled there — the cooperative stop folds to the
        //    honest CANCELLED result below, the WRITTEN frames kept).
        fun report(done: Int) {
            try {
                onProgress?.invoke(done, total)
            } catch (e: ExportCancelled) {
                throw e
            }
        }
        try {
            report(0)
        } catch (e: ExportCancelled) {
            return CinemaDngResult(
                state = State.CANCELLED,
                framesWritten = 0,
                framesTotal = total,
                writtenNames = emptyList(),
            )
        }
        for (i in 0 until total) {
            val name = CinemaDngNaming.frameName(prefix, settings.frameDigits, i)
            val fd = io.openChildWriteFd(name)
            if (fd < 0) {
                return failedAfter(
                    written, total,
                    CinemaDngExportErrors.message(
                        CinemaDngExportErrors.STAGE_FRAMES,
                        "the output folder stopped accepting files",
                        "check the folder still exists and has space, then retry",
                        "name=$name",
                    ),
                    settings, sourceFd,
                )
            }
            val ok = try {
                io.writeFrame(
                    sourceFd, first + i,
                    settings.fpsNum, settings.fpsDen,
                    settings.tcHour, settings.tcMinute, settings.tcSecond, settings.tcFrame,
                    i, fd,
                )
            } finally {
                io.closeFd(fd)
            }
            if (!ok) {
                // The half-written frame's document goes (it is NOT a
                // written frame — no bytes of it were completed); the
                // COMPLETED frames stay (the partial rule).
                io.deleteChild(name)
                return failedAfter(
                    written, total,
                    CinemaDngExportErrors.nativeMessage(
                        CinemaDngExportErrors.STAGE_FRAMES,
                        io.code(), io.stage(), io.detail(),
                    ),
                    settings, sourceFd,
                )
            }
            written.add(name)
            try {
                report(written.size)
            } catch (e: ExportCancelled) {
                // Cooperative stop at the frame boundary: the frames
                // already in the folder STAY (the plan's partial rule),
                // the result carries the honest count.
                return CinemaDngResult(
                    state = State.CANCELLED,
                    framesWritten = written.size,
                    framesTotal = total,
                    writtenNames = written,
                )
            }
        }

        // 3. The WAV (only when the clip has audio): the clip's own PCM,
        //    framed by CinemaDngWav into the folder beside the frames.
        //    RUN-TIME AUDIO RESOLUTION (auditor RED fix, 2026-11-27): the
        //    sheet's snapshot carries audioSampleRate=0/audioChannels=0
        //    (the sheet does not read the clip's audio facts), so the
        //    snapshot's own values can NEVER gate this block — the source
        //    is asked HERE, through the io seam, when the snapshot did not
        //    carry a usable pair (>0 = a caller that DID resolve them at
        //    enqueue keeps them, the backward-compatibility rule).
        //    io.audioInfo returning null / a 0-0 pair is the honest
        //    "the clip has no audio" state: the WAV is simply not written
        //    and the DONE detail says so (never a fabricated file, never a
        //    silent lie of omission).
        val audioFromSnapshot = settings.audioSampleRate > 0 && settings.audioChannels > 0
        val audio = if (audioFromSnapshot) {
            Pair(settings.audioSampleRate, settings.audioChannels)
        } else {
            val info = io.audioInfo(sourceFd)
            if (info != null && info.size >= 2 && info[0] > 0 && info[1] > 0) {
                Pair(info[0], info[1])
            } else {
                null // no audio track, or the read failed — honest skip
            }
        }
        var wavName: String? = null
        var audioAbsent = false
        if (audio != null) {
            val pcm = io.readAudio(sourceFd)
            if (pcm == null || pcm.isEmpty()) {
                // HONEST no-audio: the clip reported an audio track but
                // no PCM could be read — the WAV is skipped, the job
                // detail reports it (never a fabricated file).
                audioAbsent = true
            } else {
                val name = CinemaDngNaming.wavName(prefix)
                val out = io.openChildStream(name)
                if (out == null) {
                    return failedAfter(
                        written, total,
                        CinemaDngExportErrors.message(
                            CinemaDngExportErrors.STAGE_AUDIO,
                            "the audio file could not be created",
                            "check the folder, then retry",
                            "name=$name",
                        ),
                        settings, sourceFd,
                    )
                }
                try {
                    CinemaDngWav.write(out, pcm, audio.first, audio.second)
                    wavName = name
                } catch (e: Exception) {
                    out.close()
                    io.deleteChild(name)
                    return failedAfter(
                        written, total,
                        CinemaDngExportErrors.message(
                            CinemaDngExportErrors.STAGE_AUDIO,
                            "the audio file could not be written",
                            "check free space, then retry",
                            e.message,
                        ),
                        settings, sourceFd,
                    )
                }
                out.close()
            }
        } else {
            // The clip has no audio track at all: the frames alone are the
            // honest, complete deliverable — but the DONE detail SAYS so
            // (reported, never guessed; the auditor's lie-of-omission fix).
            audioAbsent = true
        }

        return CinemaDngResult(
            state = State.DONE,
            framesWritten = written.size,
            framesTotal = total,
            writtenNames = written,
            wavName = wavName,
            audioAbsent = audioAbsent,
        )
    }

    /**
     * The failure wrap: the WRITTEN frames stay (never deleted — the
     * partial rule), the result carries the honest counts and the detail.
     */
    private fun failedAfter(
        written: List<String>,
        total: Int,
        detail: String,
        settings: CinemaDngSettings,
        @Suppress("UNUSED_PARAMETER") sourceFd: Int,
    ): CinemaDngResult = CinemaDngResult(
        state = State.FAILED,
        framesWritten = written.size,
        framesTotal = total,
        writtenNames = written,
        failDetail = detail +
            " — ${written.size} frames were written and kept",
    )
}
