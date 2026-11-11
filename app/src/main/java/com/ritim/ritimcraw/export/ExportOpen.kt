package com.ritim.ritimcraw.export

import java.io.File

/**
 * Pure decision for "the user tapped a finished export row" (owner request
 * 2026-11-11 §1: DONE MP4 and DNG rows open on tap). Kept free of Android
 * imports so it is JVM-testable; the intent dispatch itself is a thin,
 * device-gated adapter in the app shell (AppNav), like [MediaStorePublisher].
 *
 * The decision order (the spec's own words: the job's MediaStore URI first,
 * the app's own output through FileProvider when the publish failed):
 *  1. only a DONE row opens anything;
 *  2. the job's OWN media-store row ([ExportJob.publishedUri], captured when
 *     this job's publish succeeded) — never a newer export of the same clip;
 *  3. the job's own internal output file, as the FIRST EXISTING candidate
 *     the adapter offers (the job-scoped file, then the legacy flat layout);
 *  4. nothing — the adapter shows an understandable message instead.
 *
 * JOB-ID MATCHING (the spec: an older row must not open a later file): the
 * store URI is per-job, and the internal candidates are per-job directories
 * (files/exports/&lt;jobId&gt;/…), which the adapter builds. Legacy rows
 * written before per-job directories had flat outputs a later job of the
 * same clip could overwrite — for those the URI usually exists (published
 * rows carry it), and the flat candidate is the best remaining guess.
 */
object ExportOpen {

    /** What a DONE row's tap opens. */
    sealed interface Open {
        /**
         * The job's own media-store row: ACTION_VIEW on this content URI
         * with a read grant. MP4 goes to the default player; DNG to a DNG
         * viewer (none installed → message + Share, the adapter's job).
         */
        data class Store(val uri: String) : Open

        /** The job's own internal output file (FileProvider fallback). */
        data class Internal(val file: File) : Open

        /** Nothing openable: the publish failed and no output file exists. */
        object None : Open
    }

    /**
     * Decides what tapping [job] opens. [internalCandidates] are the job's
     * possible internal output files, in priority order; existence is
     * checked here so the adapter stays dumb (it only lists the paths).
     */
    fun open(job: ExportJob, internalCandidates: List<File>): Open {
        if (job.state != State.DONE) return Open.None
        val uri = job.publishedUri
        if (!uri.isNullOrBlank()) return Open.Store(uri)
        val file = internalCandidates.firstOrNull { it.exists() } ?: return Open.None
        return Open.Internal(file)
    }
}
