package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.engine.RenderBackend

/**
 * Export target format (sprint plan Task 5: CFA DNG or SDR H.264 MP4;
 * RAW-11 Frame Picker adds PHOTO — the developed frame as a PNG; RAW-13
 * adds LINEAR — the demosaiced, scene-referred Linear DNG, a separate file
 * contract from the raw-mosaic DNG).
 */
enum class Target { DNG, MP4, PHOTO, LINEAR }

/** Export job lifecycle (sprint plan Task 5). */
enum class State { QUEUED, RUNNING, DONE, CANCELLED, FAILED }

/**
 * One export job (sprint plan Task 5 interface contract). The grade is a
 * snapshot taken when the job was queued: a later edit of the clip must not
 * change an in-flight export, and Retry re-queues with the same snapshot.
 *
 * [detail] carries the failure stage + cause for FAILED jobs (DESIGN.md
 * Error states: name the stage and the cause, never a bare "failed"); null
 * otherwise. The output name for DONE jobs also lives here.
 *
 * Progress (owner finding 2026-11-10: a RUNNING card must show how far the
 * export is, not just "Exporting…"): [framesDone]/[framesTotal] are the
 * exporter's frame counts while the job runs — framesTotal is the whole
 * clip's frame count (the DNG export is one frame: 1/1). Both are null
 * except while RUNNING (cleared on the terminal write and on Retry), and
 * are written throttled — see ExportQueue — so project.json is not
 * hammered; the persisted value may lag the real count by up to the
 * throttle interval.
 *
 * [publishedUri] (owner request 2026-11-11 §1: DONE rows open on tap) is the
 * content:// URI of the job's row in the system media store, captured when
 * the publish succeeded — null when the job has not (yet) published, when
 * publishing failed (the row then falls back to the app's own output file
 * via FileProvider, see [ExportOpen]), and on every non-DONE row. Stored as
 * a raw string so the value round-trips through project.json without any
 * Android types here.
 *
 * [outputName] (owner request 2026-11-11 §2b: export names derive from the
 * clip's display name) is the file name captured at ENQUEUE time from the
 * clip's display name (cleaned of invalid filename characters — see
 * [ExportQueue.outputName]). Captured, not derived on the fly, because the
 * name must stay stable for the job's whole life: the DONE row opens the
 * file the job actually wrote, and Retry overwrites only its own output.
 * Null on rows queued before §2b — [fileName] then derives the legacy
 * `<sourceId>-<target>` name so old rows still open.
 */
data class ExportJob(
    val id: String,
    val sourceId: String,
    val snapshot: GradeK,
    val target: Target,
    val state: State,
    val detail: String? = null,
    val framesDone: Int? = null,
    val framesTotal: Int? = null,
    val publishedUri: String? = null,
    val outputName: String? = null,
    /**
     * (RAW-11 Frame Picker) the frame this job develops, 0-based, in the
     * clip's own frame numbering — the picker's current transport position.
     * 0 for every job queued before the picker existed and for whole-clip
     * targets where the frame is not a choice (a plain DNG frame export
     * keeps exporting frame 0 exactly as before). Persisted only when
     * non-zero so pre-picker project.json rows stay byte-identical.
     */
    val frameIndex: Int = 0,
    /**
     * The clip's manual rotation delta snapshotted at ENQUEUE time (owner
     * request 2026-11-11 section 5), exactly like the grade: a later
     * rotate tap must not change an in-flight export. The exporters
     * compose it with the session's container orientation through
     * RotationState.effectiveDeg. 0 for every job queued before the
     * feature (and every never-rotated clip) — persisted only when
     * non-zero so pre-rotation project.json rows stay byte-identical
     * (the frameIndex shape).
     */
    val manualRotationDeg: Int = 0,
    /**
     * The render backend this job's export should request (GPU deliverable
     * F, owner request 2026-11-12 §10): snapshotted at ENQUEUE time from
     * the settings picker, exactly like the grade — a later settings
     * change must not flip an in-flight export. CPU (the default) keeps
     * the byte-identical reference path; GPU_EXPERIMENTAL asks the writer
     * for its encoder-Surface input mode and falls back to CPU with a
     * logged reason when the device cannot bring it up
     * ([SurfaceInputUnavailable] — the clean whole-export restart).
     * Persisted only when non-default so pre-GPU project.json rows stay
     * byte-identical (the manualRotationDeg shape).
     */
    val renderBackend: RenderBackend = RenderBackend.CPU,
    /**
     * (Timeline export) the timeline this job renders, when it is a TIMELINE
     * job — null on every per-clip job. The marker is a FIELD, not a Target
     * value: Target is persisted BY NAME, so a new enum constant would be a
     * persisted-enum wire change; a null-defaulted field keeps every existing
     * row byte-identical (the frameIndex/manualRotationDeg shape).
     *
     * SNAPSHOT AT ENQUEUE, exactly like [snapshot] (the grade): the timeline
     * is user intent at the moment the export was queued — a later edit,
     * split or delete must not change an in-flight export, and Retry
     * re-exports the SAME timeline. [TimelineRef.projectJson] holds the
     * rendered timeline section wrapped as its own parseable document
     * (TimelineStore.renderTimeline inside `{"timeline":...}` — parseTimeline
     * reads the whole document and looks up the section).
     *
     * The world facts the snapshot does NOT freeze — the Library's sources,
     * each source's measured fps and real frame count, and the per-source
     * grade from GradeStore — are resolved FRESH at run time by the timeline
     * exporter (a source that went missing or UNKNOWN after enqueue skips
     * honestly, the plan's gates; a grade edited after enqueue is the
     * current grade, the same live-grade semantics the per-clip exporters
     * apply to fps/frame metadata).
     */
    val timeline: TimelineRef? = null,
    /**
     * (Export resolution, owner request 2026-11-13) the OUTPUT
     * resolution bound this job's MP4 encode resolves against — the
     * ladder choice snapshotted at ENQUEUE time, exactly like the grade:
     * a later tap must not change an in-flight export. The pair is the
     * RESOLVED bound (what the sheet's chip showed), not the ladder
     * kind: the exporter runs ExportResolution.outputSize against the
     * source's effective size with it (fits-within, aspect preserved,
     * even-snapped). Null = Original = the source's effective size —
     * byte-identical to every export before this feature, and the
     * recommended default (the frameIndex/manualRotationDeg shape:
     * omit-when-null so pre-resolution project.json rows stay
     * byte-identical and parse as null).
     */
    val resolutionW: Int? = null,
    /** [resolutionW]'s pair; both null or both set, never half a pair. */
    val resolutionH: Int? = null,
    /**
     * The source's audio link state snapshotted at ENQUEUE time (şartname
     * QA-15: "Retry aynı proje snapshot'ı ve ses seçimiyle başlar"; EX-11:
     * "job kimliği, proje snapshot'ı ve ses track seçimi korunur").
     *
     * Before this field the MP4 exporter resolved the audio choice LIVE at
     * run (`Mp4Exporter.audioLinkFor`, the AudioStore's CURRENT record), so
     * a job queued with audio attached, killed, and retried after the user
     * detached the audio exported video-only — the one PARTIAL clause the
     * 2026-11-16 acceptance audit routed (docs/ACCEPTANCE_GAPS_2026-11-16.md
     * QA-15). The snapshot rule is the grade's: user intent at the moment
     * the export was queued; [requeued] keeps it, so Retry starts with the
     * SAME audio choice.
     *
     * NULL = "not snapshotted": every row queued before this field, every
     * non-MP4 job (DNG/LINEAR/PHOTO carry no audio) and the timeline job
     * (its audio leg is the later mix pass). A null keeps the legacy
     * behavior exactly — the exporter falls back to the live lookup — and
     * is omitted from project.json so pre-field rows stay byte-identical
     * (the frameIndex/manualRotationDeg shape).
     */
    val audioLink: AudioLinkState? = null,
    /**
     * (E1, plan 7.3) the MP4 export choices snapshotted at ENQUEUE time,
     * exactly like the grade: a later change in the export sheet must not
     * alter an in-flight export, and Retry re-queues with the same
     * settings. NULL = the job was queued before E1 (or is not an MP4
     * job): it exports with today's fixed behavior (AVC 8-bit High, the
     * old bitrate formula, KEY_FRAME_RATE 30, AAC 128 - see
     * [ExportPlan.legacy]) and stays byte-identical in project.json. A
     * row whose settings a newer app wrote and this one cannot read is
     * NOT run with the legacy behavior: [ExportStore] fails it honestly.
     * Persisted as one nested object (ExportSettingsWire), by enum NAME.
     */
    val settings: ExportSettings? = null,
    /**
     * (E1b) what the MP4 export really produced: the encoder that ran, the
     * final effective settings, the real bitrate (file size / duration)
     * beside the requested one, and the plan steps. Set on DONE jobs that
     * carry [settings]; null otherwise (pre-E1 jobs stay byte-identical).
     * Cleared by [requeued]: a retried job reports its own run.
     */
    val report: ExportReport? = null,
) {

    /**
     * The file name this job's output has: the display-derived name captured
     * at enqueue (owner request §2b), or the legacy `<sourceId>-<target>`
     * name for rows queued before that. Every path that touches the job's
     * file — the output location, the MediaStore DISPLAY_NAME, the open
     * candidates — goes through THIS, so the name can never drift between
     * them.
     */
    val fileName: String
        get() = outputName ?: ExportQueue.outputName(sourceId, target)
    /**
     * Retry source: the same job re-queued with the same snapshot. The id and
     * queued state stay; the old failure detail, the progress and the old
     * publish are cleared — the retried job publishes afresh when it lands.
     * The audio choice ([audioLink]) is part of the snapshot and is KEPT
     * (QA-15: retry starts with the same project snapshot AND audio choice).
     */
    fun requeued(): ExportJob =
        copy(
            state = State.QUEUED,
            detail = null,
            framesDone = null,
            framesTotal = null,
            publishedUri = null,
            report = null,
        )
}
