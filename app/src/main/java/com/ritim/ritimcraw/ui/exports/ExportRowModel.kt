package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.State

/**
 * Pure row-display decisions for the Exports screen (owner request
 * 2026-11-11d §5), JVM-testable: which name each job row shows, which
 * file a DONE row names, and which rows open on tap. The composable stays
 * a thin shell — the rules live here.
 *
 * Every value comes from the job's existing fields; nothing is invented
 * (§5: "The exact content must reflect actual existing job information").
 */
object ExportRowModel {

    /**
     * The row's headline: the source clip's display name (owner request
     * §2b) from the caller's map, or the source id when the record is
     * unknown/missing — the same fallback the screen always used.
     */
    fun sourceName(job: ExportJob, sourceNames: Map<String, String>): String =
        sourceNames[job.sourceId] ?: job.sourceId

    /**
     * The output file a DONE row names and opens: [ExportJob.fileName] —
     * the display-derived name captured at enqueue, or the legacy
     * `<sourceId>-<target>` name for rows queued before §2b. The single
     * source every path already goes through, so the row can never name
     * a file the job did not write.
     */
    fun outputFileName(job: ExportJob): String = job.fileName

    /**
     * Whether the row itself is a tap target for opening the finished
     * file (owner request 2026-11-11 §1): DONE rows only — the same rule
     * the screen always had.
     */
    fun isOpenable(state: State): Boolean = state == State.DONE

    /**
     * Whether the row offers a Cancel action (owner request 2026-11-13:
     * "export menüsünde iptal etme kısmı olmalı" — the exports screen
     * must have a cancel action): the two LIVE states only. A QUEUED job
     * has not started (the queue skips it — it never runs); a RUNNING
     * job stops cooperatively at the next frame boundary. Terminal rows
     * (DONE/FAILED/CANCELLED) have nothing to cancel — the queue's
     * cancel() is a no-op on them by the same rule.
     */
    fun isCancellable(state: State): Boolean =
        state == State.QUEUED || state == State.RUNNING
}
