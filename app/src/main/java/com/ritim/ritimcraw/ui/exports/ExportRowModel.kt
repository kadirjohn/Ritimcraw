package com.ritim.ritimcraw.ui.exports

import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportReport
import com.ritim.ritimcraw.export.ExportText
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target

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

    /**
     * Whether the row is deletable (owner request 2026-11-26: swipe or
     * select delete of individual export rows): TERMINAL rows only — the
     * exact complement of [isCancellable]. A live row (QUEUED/RUNNING)
     * can only be cancelled; deleting one would orphan the encoder the
     * queue still drives, so neither the swipe (the dismiss never
     * confirms) nor the store's delete ([ExportStore.delete] repeats
     * the rule at its write) can remove it. Terminal rows are history —
     * deleting removes the record only, like clear-all.
     */
    fun isDeletable(state: State): Boolean = !isCancellable(state)

    /** Where a DONE card's thumbnail comes from. */
    enum class ThumbSource { OUTPUT_VIDEO, SOURCE_STORE }

    /**
     * A finished MP4 shows its own output's frame; every other target
     * (DNG, linear DNG, photo) reuses the library's per-clip thumbnail.
     */
    fun thumbSource(job: ExportJob): ThumbSource =
        if (job.target == Target.MP4) ThumbSource.OUTPUT_VIDEO else ThumbSource.SOURCE_STORE

    /**
     * The codec name for a card's MP4 subtitle ("HEVC", "H.264", ...),
     * from the report's mime (the same data the "HEVC · 10-bit" line
     * uses); null when there is no report or the mime is not one the app
     * knows — the caller then falls back to the generic format text.
     */
    fun codecLabel(job: ExportJob): String? {
        if (job.target != Target.MP4) return null
        val mime = job.report?.mime ?: return null
        val label = ExportText.codec(mime)
        return if (label == mime) null else label
    }

    /** Technical report notes that live behind the details control. */
    enum class DetailLine { ENCODER, FIXED, CPU }

    /**
     * Details-only lines of a report (not resolution/fps/codec/bitrate/size,
     * which stay visible): the encoder, plus the retry and CPU-fallback
     * notes. The 10-bit-dropped warning is NOT here: it changes what the
     * file is, so it stays visible.
     */
    fun detailLines(r: ExportReport): List<DetailLine> = buildList {
        add(DetailLine.ENCODER)
        if (ExportText.wasFixed(r)) add(DetailLine.FIXED)
        if (ExportText.fellBackToCpu(r)) add(DetailLine.CPU)
    }
}
