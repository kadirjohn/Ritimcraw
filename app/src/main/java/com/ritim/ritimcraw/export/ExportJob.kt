package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK

/** Export target format (sprint plan Task 5: CFA DNG or SDR H.264 MP4). */
enum class Target { DNG, MP4 }

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
 * [startedAtMs] (owner request 2026-11-11 §1: ETA after enough data) is the
 * wall clock when the job's drain started it (RUNNING write); null until
 * then and on terminal rows. The ETA is a UI-side linear estimate from the
 * observed rate (see [ExportEta] in ExportsScreen); the queue persists
 * only the raw start time so a restarted UI can still estimate.
 *
 * [publishedUri] (owner request 2026-11-11 §1: DONE rows open on tap) is the
 * content:// URI of the job's row in the system media store, captured when
 * the publish succeeded — null when the job has not (yet) published, when
 * publishing failed (the row then falls back to the app's own output file
 * via FileProvider, see [ExportOpen]), and on every non-DONE row. Stored as
 * a raw string so the value round-trips through project.json without any
 * Android types here.
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
    val startedAtMs: Long? = null,
    val publishedUri: String? = null,
) {
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
            startedAtMs = null,
            publishedUri = null,
        )
}
