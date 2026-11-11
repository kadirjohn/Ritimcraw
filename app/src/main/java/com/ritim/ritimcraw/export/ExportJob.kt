package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK

/**
 * Export target format (sprint plan Task 5: CFA DNG or SDR H.264 MP4;
 * RAW-11 Frame Picker adds PHOTO — the developed frame as a PNG).
 */
enum class Target { DNG, MP4, PHOTO }

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
     */
    fun requeued(): ExportJob =
        copy(
            state = State.QUEUED,
            detail = null,
            framesDone = null,
            framesTotal = null,
            publishedUri = null,
        )
}
