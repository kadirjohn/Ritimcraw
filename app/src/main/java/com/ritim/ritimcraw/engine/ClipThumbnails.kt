package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.ThumbnailRules
import com.ritim.ritimcraw.data.ThumbnailStore
import com.ritim.ritimcraw.export.PngWriter
import java.util.concurrent.ExecutorService

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
 * 2026-11-11 section 1). For ONE clip, through the SAME session seam the
 * editor uses ([PreviewSessions.open] — one uniform open for MCRAW and
 * encoded clips alike, so even an MP4 gets a properly developed first
 * frame instead of a flat log frame):
 *
 *  1. Open the clip read-only ONCE (the seam owns the fd's lifetime:
 *     MCRAW dup's it and the factory closes the caller's copy; the encoded
 *     source adopts it — the caller never touches a descriptor itself).
 *  2. Render frame 0 with a NEUTRAL [GradeK] — the owner ACCEPTS that the
 *     neutral develop looks dark; nothing here auto-brightens.
 *  3. Read the recorded first/last pts for the duration
 *     ([ThumbnailStats.durationUs] — hides at 0, never faked).
 *  4. Persist the PNG ONCE (files/thumbnails/<id>.png) — decode once,
 *     never redevelop per composition.
 *  5. Report the recorded stats (width/height/frameCount/duration) so the
 *     caller backfills the record — the row's resolution shows AS
 *     RECORDED (open-gate 4000x3000 and 3840x2160 both display as
 *     recorded).
 *
 * Every native/codec call runs on the shared preview engine thread (the
 * seam submits everything there — one decoder at a time, thread-local
 * error state). Never throws: a failed open or render degrades to "no
 * thumbnail this pass" (the row shows the placeholder; the stats still
 * land when the open succeeded), and the next Library open retries.
 */
class ClipThumbnails(
    private val store: ThumbnailStore,
    private val pngWriter: PngWriter,
    private val engine: ExecutorService = PreviewRenderer.previewEngine,
) {

    /** The long-edge target of the developed thumbnail (fast, small PNG). */
    val maxDim: Int = 128

    /**
     * Generates the thumbnail for [clip] when it still needs one
     * ([ThumbnailRules.needsThumbnail] — a clip with a persisted file is
     * never re-developed) and reports the recorded stats through
     * [PendingClip.onStats] (called exactly once on a successful open,
     * never on a failed one — the caller keeps its old record then).
     */
    suspend fun generate(clip: PendingClip) {
        if (!ThumbnailRules.needsThumbnail(clip.media, store.has(clip.media.id))) return
        val session = PreviewSessions.open(
            path = clip.path,
            fdProvider = clip.fdProvider,
            engine = engine,
        ).getOrNull() ?: return // failed open: retry the next Library open
        session.use { s ->
            // Neutral develop of frame 0 (owner accepts the dark look —
            // no auto-brighten). 128px long edge: small PNG, fast load.
            val frame = s.render(frameIndex = 0, grade = GradeK(), maxDim = maxDim)
                .getOrNull()
            if (frame != null) {
                store.ensureDir()
                pngWriter.write(
                    frame.argb, frame.width, frame.height,
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
