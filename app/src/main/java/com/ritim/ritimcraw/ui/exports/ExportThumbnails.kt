package com.ritim.ritimcraw.ui.exports

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.ritim.ritimcraw.data.ThumbnailStore
import com.ritim.ritimcraw.export.ExportJob
import java.io.File

/**
 * Thin device adapter that loads a finished export card's thumbnail (the
 * decision of WHERE it comes from is the pure [ExportRowModel.thumbSource]).
 * Call [load] from a background dispatcher only.
 *
 * - finished MP4: the output file's frame at ~1 s (the job's internal
 *   file when it still exists, else its published media-store row),
 *   scaled down;
 * - anything else (and an MP4 whose frame could not be read): the library's
 *   existing per-clip PNG from [ThumbnailStore] — MCRAW frames are never
 *   decoded here.
 *
 * Decoded bitmaps live in a small [LruCache] keyed by job id so scrolling
 * the list never decodes twice. Misses (null) are not cached.
 */
object ExportThumbnails {
    private const val MAX_EDGE = 320
    private const val CACHE_ENTRIES = 24

    private val cache = LruCache<String, Bitmap>(CACHE_ENTRIES)

    fun cached(jobId: String): Bitmap? = cache.get(jobId)

    fun load(
        context: Context,
        job: ExportJob,
        exportDir: File?,
        store: ThumbnailStore?,
    ): Bitmap? {
        cache.get(job.id)?.let { return it }
        val bmp = try {
            when (ExportRowModel.thumbSource(job)) {
                ExportRowModel.ThumbSource.OUTPUT_VIDEO ->
                    videoFrame(context, job, exportDir) ?: fromStore(job, store)
                ExportRowModel.ThumbSource.SOURCE_STORE -> fromStore(job, store)
            }
        } catch (e: Exception) {
            null
        }
        if (bmp != null) cache.put(job.id, bmp)
        return bmp
    }

    private fun fromStore(job: ExportJob, store: ThumbnailStore?): Bitmap? {
        val f = store?.fileFor(job.sourceId) ?: return null
        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
        return BitmapFactory.decodeFile(f.absolutePath, opts)
    }

    private fun videoFrame(context: Context, job: ExportJob, exportDir: File?): Bitmap? {
        val name = job.fileName
        val file = exportDir?.let {
            listOf(File(File(it, job.id), name), File(it, name)).firstOrNull { f -> f.exists() }
        }
        val mmr = MediaMetadataRetriever()
        try {
            if (file != null) {
                mmr.setDataSource(file.absolutePath)
            } else {
                val uri = job.publishedUri?.takeIf { it.isNotBlank() } ?: return null
                mmr.setDataSource(context, Uri.parse(uri))
            }
            val raw = mmr.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            val scale = MAX_EDGE.toFloat() / maxOf(raw.width, raw.height)
            if (scale >= 1f) return raw
            val out = Bitmap.createScaledBitmap(
                raw, (raw.width * scale).toInt().coerceAtLeast(1),
                (raw.height * scale).toInt().coerceAtLeast(1), true,
            )
            if (out !== raw) raw.recycle()
            return out
        } finally {
            try { mmr.release() } catch (_: Exception) {}
        }
    }
}
