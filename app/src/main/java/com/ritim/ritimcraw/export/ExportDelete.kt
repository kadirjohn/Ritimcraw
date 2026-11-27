package com.ritim.ritimcraw.export

import java.io.File

/**
 * PURE delete-from-disk decisions (owner request 2026-11-27, the M8 pass:
 * "export menüsünde, dosyaları silmek isterse user, delete from disk box'u
 * da olsun ve bunu işaretlerse diskten de silsin"). The dialog's checkbox
 * is the owner's own chosen switch: unchecked (the default — the safe
 * side) keeps the M3 meaning exactly (the record leaves, the file stays);
 * checked extends the same delete to the PUBLISHED FILE the row stands
 * for. Kept free of Android imports so it is JVM-testable; the resolver
 * delete itself is a thin, device-gated adapter in the app shell (AppNav),
 * like [MediaStorePublisher] and [ExportOpen].
 *
 * WHAT "THE FILE" IS: a DONE row's published media-store row (its
 * [ExportJob.publishedUri] — the file the gallery sees, the thing the
 * owner means by "diskten de silsin") plus the app's own internal copy
 * under files/exports/&lt;jobId&gt;/ (the retry-dedupe source of truth —
 * left there it would silently re-publish on a retried job of the same
 * id, and the open path would keep opening the deleted row's ghost).
 * A FAILED/CANCELLED row wrote nothing and published nothing: there is
 * no file, and it contributes no disk target even when checked — the
 * checkbox never changes WHAT a non-DONE row is.
 *
 * THE GUARDS THE PURE RULE OWNS:
 *  - state: only DONE rows have anything on disk (see above);
 *  - uri honesty: a blank [ExportJob.publishedUri] is no media-store row
 *    (a publish that failed left the row null) — nothing to delete there,
 *    the job is skipped silently, never guessed;
 *  - selection scope: only the ids the caller is ALREADY deleting route
 *    (the store's live-guard is the second gate on the record side; a
 *    live id can never reach here through the UI's own deletable gate,
 *    but the rule repeats it — [diskTargets] filters to terminal rows
 *    again so a racing retry can never lose its freshly queued file).
 *
 * The URI COUNT (not the URIs) is what the dialog shows: the checkbox
 * label states how many files leave, so the user checks it knowing what
 * it does — the same "name what leaves" honesty the M3 subject line
 * carries.
 */
object ExportDelete {

    /**
     * The disk targets a checked delete-from-disk box would remove: one
     * entry per DELETED job that is DONE with a non-blank publishedUri.
     * Blank/missing URIs and non-DONE rows contribute nothing (there is
     * no file to delete — the honest skip, never a guessed path).
     * The store's own live-guard runs on the RECORD side of the same
     * delete; this filter repeats the terminal rule so a row that turned
     * live between the check and the press can never lose a file its new
     * run still needs (a re-queued job publishes afresh on DONE).
     */
    fun diskTargets(jobs: List<ExportJob>, ids: Set<String>): List<ExportJob> =
        jobs.filter { job ->
            job.id in ids &&
                job.state == State.DONE &&
                !job.publishedUri.isNullOrBlank()
        }

    /**
     * The internal copy candidates a checked box would also delete: the
     * per-job directory (the current layout) and the legacy flat file
     * (rows written before per-job directories), the SAME candidates the
     * open path offers ([ExportOpen.open]) so the deleted record can
     * never keep opening a file the dialog said left. The caller passes
     * the app's exports directory; existence is checked by the adapter
     * (this stays pure — a JVM test has no files).
     */
    fun internalTargets(job: ExportJob, exportDir: File): List<File> =
        listOf(File(File(exportDir, job.id), job.fileName), File(exportDir, job.fileName))

    /**
     * The checkbox's default: UNCHECKED (owner request 2026-11-27 — the
     * box is the user's opt-in, never the dialog's assumption; the safe
     * side keeps the M3 meaning exactly).
     */
    const val DEFAULT_DELETE_FROM_DISK: Boolean = false

    /**
     * The checkbox's re-check at the confirm press: the same still-DONE
     * rule [diskTargets] applies, reduced to a boolean the adapter reads.
     * A checkbox armed on a DONE row whose job turned live before the
     * press (a Retry racing the dialog) routes false — the record side's
     * live-guard already drops that row, and the file side must never
     * delete under a live job.
     */
    fun fromDisk(pendingId: String?, jobs: List<ExportJob>, checked: Boolean): Boolean {
        if (!checked || pendingId == null) return false
        return diskTargets(jobs, setOf(pendingId)).isNotEmpty()
    }
}
