package com.ritim.ritimcraw.export

import java.io.File

/**
 * Pure MediaStore placement decisions (JVM-testable, no Android imports):
 * where a finished export lands in the system media collections — the
 * collection, the on-device folder, the mime type and the display name,
 * per [Target]:
 *
 *  - MP4 -> the video collection, Movies/ritiMCRAW, video/mp4.
 *  - DNG -> the images collection, Pictures/ritiMCRAW, image/x-adobe-dng.
 *    Some gallery viewers will not RENDER a CFA DNG (they expect a processed
 *    image); the raw mosaic is intact — desktop DNG readers open it fine.
 *
 * The internal files/exports copy stays the source of truth (Retry dedupes
 * by the unchanged output name); the MediaStore row is an additive
 * best-effort copy for other apps, never the export itself.
 */
object MediaPublish {

    /** Which system media collection a target publishes into. */
    enum class Collection { VIDEO, IMAGES }

    /** One finished export's destination in the system media store. */
    data class Placement(
        /** The media collection (video or images) — the adapter turns this
         *  into the collection Uri. */
        val collection: Collection,
        /** RELATIVE_PATH under the collection's volume ("Movies/ritiMCRAW"). */
        val relativePath: String,
        /** The row's MIME_TYPE ("video/mp4", "image/x-adobe-dng"). */
        val mime: String,
        /** The row's DISPLAY_NAME — the same name as the internal copy. */
        val name: String,
    )

    /** The placement of one finished [target] export of [sourceId]. */
    fun placement(sourceId: String, target: Target): Placement =
        placement(sourceId, target, null)

    /**
     * The placement of one finished export (owner request 2026-11-11 §2b):
     * the row's DISPLAY_NAME is the JOB'S OWN output name — the display
     * name captured at enqueue when there is one, the legacy
     * `<sourceId>-<target>` name otherwise (rows queued before §2b). A
     * MediaStore DISPLAY_NAME collision is auto-uniquified by the platform
     * with a suffix; the DONE row opens via the job's own publishedUri, so
     * the suffixed name never misroutes an open.
     */
    fun placement(sourceId: String, target: Target, outputName: String?): Placement =
        Placement(
            collection = when (target) {
                Target.MP4 -> Collection.VIDEO
                Target.DNG -> Collection.IMAGES
            },
            relativePath = when (target) {
                Target.MP4 -> "Movies/ritiMCRAW"
                Target.DNG -> "Pictures/ritiMCRAW"
            },
            mime = when (target) {
                Target.MP4 -> "video/mp4"
                Target.DNG -> "image/x-adobe-dng"
            },
            name = outputName ?: ExportQueue.outputName(sourceId, target),
        )
}

/**
 * What the queue asks its host to do with a finished export's output file:
 * ALSO publish it to the system media store (MediaStore via the host's
 * ContentResolver). An interface like [ExportIo] so the queue stays
 * JVM-testable — tests inject a fake, the app wires the real adapter
 * ([MediaStorePublisher]).
 *
 * Contract: publish is ADDITIVE and best-effort. The internal copy under
 * files/exports is the source of truth; a publish that fails (or throws)
 * must never fail the export job — the queue catches everything around it.
 *
 * The return (owner request 2026-11-11 §1: DONE rows open on tap) is the
 * published row's content:// URI, or null when nothing was published —
 * the queue stores it on the job so a DONE row opens ITS OWN media-store
 * file, and the open path falls back to the internal copy when it is null.
 */
interface ExportPublisher {

    /**
     * Publishes the finished [output] of [job] to the media store. Returns
     * the published row's content:// URI, or null when it was skipped or
     * failed. Never changes the job's own outcome.
     */
    suspend fun publish(job: ExportJob, output: File): String?

    companion object {
        /** The default no-op (paths without a resolver; tests inject fakes). */
        val None: ExportPublisher = object : ExportPublisher {
            override suspend fun publish(job: ExportJob, output: File): String? = null
        }
    }
}
