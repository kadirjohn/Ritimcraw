package com.ritim.ritimcraw.export

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * The real [ExportPublisher]: copies a finished export's file into the
 * system media store through the host's [ContentResolver]. This is the ONLY
 * Android-framework class in the publish path — ContentResolver/MediaStore
 * cannot run in JVM unit tests (framework stubs throw), so every decision
 * around it lives in the JVM-tested [MediaPublish] (the placement mapping)
 * and the queue (whether/when to publish); this class stays a thin,
 * device-gated adapter, like [MediaCodecMp4Writer].
 *
 * Flow (the IS_PENDING contract, so the Gallery never sees a partial file):
 *  1. insert a PENDING row (collection + RELATIVE_PATH + DISPLAY_NAME +
 *     MIME_TYPE) — invisible to other apps until it is published,
 *  2. stream the finished internal file into the row,
 *  3. clear IS_PENDING — the file becomes visible atomically.
 *
 * A failed copy DELETES the pending row (no orphaned invisible entry) and
 * returns false: the export's internal copy under files/exports is the
 * source of truth, so losing the publish never fails the job.
 */
internal class MediaStorePublisher(
    private val resolver: ContentResolver,
) : ExportPublisher {

    constructor(context: Context) : this(context.contentResolver)

    override suspend fun publish(job: ExportJob, output: File): String? {
        if (!output.exists()) return null
        val placement = MediaPublish.placement(job.sourceId, job.target)
        val collection = when (placement.collection) {
            MediaPublish.Collection.VIDEO ->
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            MediaPublish.Collection.IMAGES ->
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        // 1. The pending row: invisible until the copy is complete.
        val pending = ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, placement.relativePath)
            put(MediaStore.MediaColumns.DISPLAY_NAME, placement.name)
            put(MediaStore.MediaColumns.MIME_TYPE, placement.mime)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, pending) ?: return null

        // 2. Stream the finished file in. Any failure deletes the row: no
        //    orphaned invisible entry is left behind.
        val copied = try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                false
            } else {
                out.use { output.inputStream().use { input -> input.copyTo(it) } }
                true
            }
        } catch (e: Exception) {
            false
        }
        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        // 3. Publish atomically: IS_PENDING -> 0. The row's URI is the value
        //    the queue stores on the job — a DONE row opens exactly this
        //    media-store file (owner request 2026-11-11 §1), never a newer
        //    export of the same clip.
        return try {
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, published, null, null)
            uri.toString()
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
