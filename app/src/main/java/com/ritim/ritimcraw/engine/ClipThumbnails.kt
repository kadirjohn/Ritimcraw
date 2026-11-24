package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.RotationState
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.data.ThumbnailStore
import com.ritim.ritimcraw.export.ExportEngine
import com.ritim.ritimcraw.export.Mp4Session
import com.ritim.ritimcraw.export.Mp4SessionInfo
import com.ritim.ritimcraw.export.NativeMp4Session
import com.ritim.ritimcraw.export.PhotoPixels
import com.ritim.ritimcraw.export.PngWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Pure recorded-stats decisions for the thumbnail pass (owner request
 * 2026-11-11 section 1), JVM-testable without the native lib: the duration
 * from the recorded timestamps, and which probed values a record keeps.
 */
object ThumbnailStats {

    /**
     * The clip's duration from its RECORDED frame timestamps: last pts
     * minus first pts, the same rule the editor's transport uses
     * ([PtsTimeline.totalDurationUs]). A single-frame clip (or missing
     * pts, -1) is 0 — the row HIDES the duration part then, it never
     * fakes one.
     */
    fun durationUs(firstPtsUs: Long, lastPtsUs: Long): Long =
        if (firstPtsUs < 0 || lastPtsUs < 0) 0L
        else PtsTimeline.totalDurationUs(longArrayOf(firstPtsUs, lastPtsUs))
}

/**
 * The Library thumbnail + stats generation pass (owner request
 * 2026-11-11 section 1; the 2026-11-14 full-res MCRAW upgrade). For ONE
 * clip, through the SAME session seams the rest of the app uses:
 *
 *  - MCRAW (owner request 2026-11-14: the import thumbnail was too low
 *    quality — "ilk frame'i FULL çözünürlükte al, DNG'yi develop et,
 *    JPG olarak kaydet, icon'a küçült, ilk frame JPG'sini sil"): the
 *    full-resolution develop the photo export uses ([Mp4Session] on
 *    [ExportEngine.thread], whose one-thread contract the export seam
 *    itself states), then a LIGHT histogram balance
 *    ([ThumbnailBalance] — the EXISTING stage-15 highlights recovery /
 *    stage-11 shadows lift at small strength, applied by a re-develop,
 *    never new tone math), then the JPEG master and the icon:
 *
 *    1. Acquire the fd ONCE, close it in finally (the native side dup's
 *       per open — one fd per pass, never leaked; the ExportQueue
 *       discipline).
 *    2. Open + render frame 0 with a NEUTRAL [GradeK] at FULL recorded
 *       resolution (developCpu -> linearToRec709 -> 8-bit RGB888).
 *    3. Balance check on the developed frame's luma histogram
 *       ([ThumbnailBalance.gradeFor]): the identity for a balanced
 *       frame (no second develop), else a bounded grade whose tone
 *       math is the existing native stages — a second renderFrame
 *       applies it.
 *    4. Rotate upright ([PhotoPixels.rotate] — a JPEG carries no
 *       orientation hint, so the pixels must be). renderFrame returns
 *       NOT-rotated pixels, so the FULL effective angle applies:
 *       [RotationState.effectiveDeg] of the container orientation and
 *       the clip's PERSISTED manual rotation (owner request
 *       2026-11-14: the thumbnail must match the rotated look the user
 *       chose in the editor — the icon is re-developed on every
 *       rotation change, so this is not just an import-time value).
 *    5. Write the JPEG master at quality ~90 (files/thumbnails/<id>.jpg
 *       — [ThumbnailStore.masterPath], the Bitmap.compress precedent
 *       the photo export's PNG adapter set).
 *    6. Extract the icon from the master with a scaled decode
 *       (inSampleSize — the full-res JPEG is NEVER loaded into a list
 *       row) and persist it at the SAME <id>.png name every build has
 *       used (existing icons never re-develop).
 *    7. DELETE the master — the icon persists, the master never does.
 *       A crash between 5 and 7 leaves a stale master the next pass or
 *       [ThumbnailStore.remove] cleans up.
 *
 *  - ENCODED clips keep the original path unchanged apart from the
 *    manual rotation: the small neutral first frame through
 *    [PreviewSessions.open] (one uniform open for MCRAW and encoded
 *    alike) — the full-res develop is an MCRAW upgrade only, and even
 *    an MP4 keeps a properly developed first frame instead of a flat
 *    log frame. The session render is ALREADY upright (the container
 *    orientation is baked in), so only the manual delta applies —
 *    [PhotoPixels.rotate] with [MediaSource.manualRotationDeg] (0 is
 *    the identity there, so the unrotated clip writes exactly as
 *    before).
 *
 * Stats land through [PendingClip.onStats] in both branches from the
 * RECORDED open (width/height/frameCount as recorded, duration from the
 * recorded pts — the row's resolution shows AS RECORDED).
 *
 * Never throws: a failed open or render degrades to "no thumbnail this
 * pass" (the row shows the placeholder; the stats still land when the
 * open succeeded), and the next Library open retries. The pass runs OFF
 * the UI thread (the engines); the row never blocks on it.
 */
class ClipThumbnails(
    private val store: ThumbnailStore,
    private val pngWriter: PngWriter,
    private val engine: ExecutorService = PreviewRenderer.previewEngine,
    private val mp4Session: Mp4Session = NativeMp4Session,
    private val exportEngine: ExecutorService = ExportEngine.thread,
    private val jpegWriter: JpegWriter = BitmapJpegWriter(),
    private val iconExtractor: IconExtractor = BitmapIconExtractor(pngWriter),
    private val scaledFrames: ScaledFrameGrabber = RetrieverFrameGrabber,
) {

    /**
     * The long-edge target of the developed icon. 256px (owner finding
     * 2026-11-14: the icon looked soft — the row shows 76dp at ~420dpi,
     * ~200px physical, so the old 128px target was upscaled on
     * display): a power of two above the display need, so the row's
     * Image scales DOWN, never up. Icons that predate this stay soft
     * until their clip is re-imported (existing icons are never
     * re-developed — [ThumbnailRules.needsThumbnail]).
     */
    val maxDim: Int = 256

    /**
     * Generates the thumbnail for [clip] when it still needs one
     * ([ThumbnailRules.needsThumbnail] — a clip with a persisted file is
     * never re-developed) and reports the recorded stats through
     * [PendingClip.onStats] (called exactly once on a successful open,
     * never on a failed one — the caller keeps its old record then).
     */
    suspend fun generate(clip: PendingClip) {
        if (!ThumbnailRules.needsThumbnail(clip.media, store.has(clip.media.id))) return
        when (clip.path) {
            SourcePath.MCRAW -> generateMcraw(clip)
            SourcePath.ENCODED -> generateEncoded(clip)
        }
    }

    /**
     * The ENCODED path, unchanged from the original pass: the small
     * neutral first frame through the editor's preview seam.
     */
    private suspend fun generateEncoded(clip: PendingClip) {
        val session = PreviewSessions.open(
            path = clip.path,
            fdProvider = clip.fdProvider,
            engine = engine,
        ).getOrNull() ?: return // failed open: retry the next Library open
        session.use { s ->
            // Neutral develop of frame 0 (owner accepts the dark look —
            // no auto-brighten). Small long edge: fast load, still above
            // the row's ~200px physical display need at 420dpi.
            //
            // The CPU encoded develop has no maxDim: it develops the FULL
            // frame and scales afterwards. Above 4K (an 8K clip: a 133 MB
            // ARGB array, twice) that runs out of JVM heap and the clip
            // never got an icon (owner finding 2026-11-24). Such clips, and
            // any clip whose develop fails, take the platform's scaled
            // frame instead: the decoder's own display frame, not the
            // neutral develop — an icon rather than a placeholder.
            val developed = if (ThumbnailRules.encodedDevelopFits(s.width, s.height)) {
                s.render(frameIndex = 0, grade = GradeK(), maxDim = maxDim).getOrNull()
            } else {
                null
            }
            val frame = developed ?: scaledFrames.grab(clip.fdProvider, maxDim)
            if (frame != null) {
                // Manual rotation (owner request 2026-11-14: the thumbnail
                // must match the rotated look the user chose in the
                // editor). The session render is ALREADY upright (the
                // container orientation is baked in), so ONLY the manual
                // delta applies — a quarter turn swaps the written dims,
                // and 0 is PhotoPixels.rotate's identity, so an
                // unrotated clip writes exactly as before.
                val upright = PhotoPixels.rotate(
                    frame.argb,
                    frame.width,
                    frame.height,
                    clip.media.manualRotationDeg,
                )
                store.ensureDir()
                pngWriter.write(
                    upright.argb, upright.width, upright.height,
                    store.targetPath(clip.media.id),
                )
            }
            // Recorded stats land in the record even when the render failed
            // (a half-probed clip shows its resolution; only the thumbnail
            // retries) — the open itself carried the recorded dimensions.
            val first = s.framePtsUs(0)
            val last = if (s.frames > 1) s.framePtsUs(s.frames - 1) else -1L
            clip.onStats(
                s.width,
                s.height,
                s.frames,
                ThumbnailStats.durationUs(first, last),
            )
        }
    }

    /**
     * The MCRAW full-res path (owner request 2026-11-14): the photo
     * export's develop seam, the light balance, the transient JPEG
     * master, the persisted icon, the master deleted. The whole native
     * sequence is confined to [exportEngine] — the Mp4Session one-thread
     * contract (thread-local error state) — and the fd's lifetime is the
     * caller's: acquired once, closed in finally.
     */
    private suspend fun generateMcraw(clip: PendingClip) {
        val fd = clip.fdProvider()
        if (fd < 0) return
        val master = store.masterPath(clip.media.id)
        suspendCoroutine { cont ->
            try {
                exportEngine.execute {
                    val res = try {
                        val info = mp4Session.open(fd)
                        if (info == null) {
                            // Failed open: no thumbnail this pass (the row
                            // shows the placeholder); the next Library open
                            // retries. Not a failure worth surfacing.
                            Result.success(Unit)
                        } else {
                            try {
                                // Full-res neutral develop of frame 0 — the
                                // same 24-stage developCpu the export runs.
                                val neutral = mp4Session.renderFrame(info.handle, 0, GradeK())
                                if (neutral != null) {
                                    // Light balance: the identity for a
                                    // balanced frame (no second develop), else
                                    // the bounded grade applied by the native
                                    // re-develop (the existing stage-15/11
                                    // math — never new tone math).
                                    val balanced = ThumbnailBalance.gradeFor(
                                        ThumbnailBalance.lumaHistogram(neutral)
                                    )
                                    val rgb = if (balanced.highlights != 0f || balanced.shadows != 0f) {
                                        // A failed re-develop falls back to the
                                        // neutral frame: the icon still lands,
                                        // just unbalanced — better than none.
                                        mp4Session.renderFrame(info.handle, 0, balanced) ?: neutral
                                    } else {
                                        neutral
                                    }
                                    writeIconFromMaster(
                                        clip.media.id,
                                        clip.media.manualRotationDeg,
                                        info,
                                        rgb,
                                        master,
                                    )
                                }
                                // Recorded stats from the same open (the row's
                                // resolution shows AS RECORDED; a single-frame
                                // clip hides its duration, never fakes one).
                                val first = mp4Session.framePts(info.handle, 0)
                                val last = if (info.frameCount > 1) mp4Session.framePts(info.handle, info.frameCount - 1) else -1L
                                clip.onStats(
                                    info.width,
                                    info.height,
                                    info.frameCount,
                                    ThumbnailStats.durationUs(first, last),
                                )
                                Result.success(Unit)
                            } finally {
                                mp4Session.close(info.handle)
                            }
                        }
                    } catch (e: Throwable) {
                        // Never throws past this pass: a failed render or
                        // write degrades to "no thumbnail this pass".
                        Result.failure<Unit>(e)
                    }
                    cont.resume(res)
                }
            } catch (e: RejectedExecutionException) {
                cont.resume(Result.failure<Unit>(e))
            }
        }
        // The caller's fd closes on every path (the native side dup'd it
        // per open — one fd per pass, never leaked), and the master NEVER
        // persists: consumed by the icon extraction, then deleted (the
        // icon is the only artifact kept).
        PreviewRenderer.closeFdQuietly(fd)
        if (master.exists()) master.delete()
    }

    /**
     * Steps 4-6 of the MCRAW path: upright ARGB -> JPEG master -> scaled
     * icon extract. [manualRotationDeg] is the clip's PERSISTED manual
     * rotation (owner request 2026-11-14): renderFrame returns
     * NOT-rotated pixels, so the full effective angle —
     * [RotationState.effectiveDeg] of the container orientation plus
     * the manual delta — decides the upright pass (a JPEG carries no
     * orientation hint, so the pixels must be). A failed write or
     * extract leaves no icon (the row shows the placeholder; the pass
     * retries on the next Library open).
     */
    private fun writeIconFromMaster(
        id: String,
        manualRotationDeg: Int,
        info: Mp4SessionInfo,
        rgb: ByteArray,
        master: File,
    ) {
        val upright = PhotoPixels.rotate(
            PhotoPixels.toArgbInts(rgb, info.width, info.height),
            info.width,
            info.height,
            RotationState.effectiveDeg(info.orientationDeg, manualRotationDeg),
        )
        store.ensureDir()
        if (!jpegWriter.write(upright.argb, upright.width, upright.height, master.absolutePath)) return
        iconExtractor.extract(master.absolutePath, store.targetPath(id), maxDim)
    }

    /**
     * One clip to generate: the persisted record, the engine route
     * ([SourcePathDecision.decide] — the same decision the editor makes),
     * a FRESH read-only descriptor provider (the seam's contract), and
     * the stats callback that backfills the record.
     */
    data class PendingClip(
        val media: MediaSource,
        val path: SourcePath,
        val fdProvider: () -> Int,
        val onStats: (width: Int, height: Int, frameCount: Int, durationUs: Long) -> Unit,
    )
}

/**
 * A small upright first frame straight from the platform decoder, scaled
 * on the way out — the fallback icon for encoded clips the CPU develop
 * cannot handle. Returns null on any failure (the row keeps its
 * placeholder and the next Library open retries).
 */
fun interface ScaledFrameGrabber {
    fun grab(fdProvider: () -> Int, maxDim: Int): PreviewFrame?
}

/** [ScaledFrameGrabber] through MediaMetadataRetriever.getScaledFrameAtTime (API 27+). */
object RetrieverFrameGrabber : ScaledFrameGrabber {
    override fun grab(fdProvider: () -> Int, maxDim: Int): PreviewFrame? {
        val fd = fdProvider()
        if (fd < 0) return null
        val pfd = android.os.ParcelFileDescriptor.adoptFd(fd)
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(pfd.fileDescriptor)
            val bmp = mmr.getScaledFrameAtTime(
                0L,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                maxDim,
                maxDim,
            ) ?: return null
            try {
                val w = bmp.width
                val h = bmp.height
                val argb = IntArray(w * h)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                PreviewFrame(width = w, height = h, argb = argb)
            } finally {
                bmp.recycle()
            }
        } catch (_: Throwable) {
            null
        } finally {
            try { mmr.release() } catch (_: Throwable) {}
            try { pfd.close() } catch (_: Throwable) {}
        }
    }
}
