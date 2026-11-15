package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.ThumbnailStore
import com.ritim.ritimcraw.data.VideoTree
import com.ritim.ritimcraw.export.PngWriter
import java.io.File
import java.util.concurrent.ExecutorService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext

/**
 * Pre-import first-frame previews for the folder browser (owner request
 * 2026-11-15: "import edilmemiş videoların bile thumbnailini göstermeli" —
 * even NOT-yet-imported videos show their thumbnail). A folder file has NO
 * MediaSource record — no id, no store path, no per-import pipeline — so
 * this pass derives the icon from the DOCUMENT alone:
 *
 *  - the cache key is the document URI (stable within the grant), hashed
 *    through [ThumbnailStore.folderKeyFor] into the store's SEPARATE
 *    folder namespace (never the library's id-keyed one);
 *  - the generation is the SMALL [PreviewSessions.open] render of frame 0
 *    with a neutral [GradeK] — the [ClipThumbnails] ENCODED-path shape
 *    exactly (fdProvider -> open -> render(frameIndex=0, maxDim=256) ->
 *    PNG write), NOT the full-res MCRAW master path: the browser shows
 *    MANY cells (a 100+ video folder) and the full-res develop is
 *    seconds-class per file, so a SMALL preview render is the only
 *    honest shape here. The KIND routes on the FILE's extension
 *    ([VideoTree.kindFor] — the browser's own routing rule), and the
 *    session render is ALREADY upright (the container orientation is
 *    baked in), so no rotation pass applies (no persisted manual
 *    rotation exists pre-import — 0 is the identity anyway);
 *  - the BOUND: callers hand a suspending [Semaphore] shared by the
 *    whole browser — the pass acquires ONE permit for the duration, so
 *    however many cells compose at once, at most as many frames as
 *    the gate permits are ever being developed concurrently. The gate
 *    is the SUSPENDING kotlinx one (not java.util.concurrent): the
 *    cell's produceState producer runs on the MAIN dispatcher, so a
 *    blocking acquire would jank the UI thread while queued cells
 *    wait — the suspending acquire parks the coroutine instead, and
 *    produceState's cancellation (the cell left composition mid-queue)
 *    unwinds the wait. The browser's queue feeds the gate only the
 *    cells the LAZY grid actually composed (the visible window +
 *    prefetch), so the bound is the composition window itself, not
 *    the folder size.
 *
 * Never throws: a failed open or render degrades to "no preview this
 * pass" (the cell keeps the glyph placeholder; the next browser open
 * retries). No stats callback exists here — no record exists to
 * backfill (the caller passes nothing; the pass writes pixels only).
 */
class FolderThumbnails(
    private val store: ThumbnailStore,
    private val pngWriter: PngWriter,
    private val engine: ExecutorService = PreviewRenderer.previewEngine,
) {

    /** The preview's long-edge target — the [ClipThumbnails] icon width. */
    val maxDim: Int = 256

    /**
     * Generates the folder preview for [uri] when none is persisted yet
     * (decode ONCE, persist, never re-develop per composition — the
     * [com.ritim.ritimcraw.data.ThumbnailRules.needsThumbnail] gate's
     * folder twin) and returns the persisted file, or null when the
     * pass could not produce one (the cell keeps its placeholder).
     *
     * [fdProvider] yields a FRESH read-only descriptor per call (the
     * [PreviewSessions] contract); the session owns its lifetime — the
     * MCRAW open dup's it and the factory closes the caller's copy, the
     * ENCODED open adopts it — so this pass never closes a descriptor
     * itself.
     *
     * [gate] is the whole-browser bound: one permit for the duration of
     * the open + render + write, acquired SUSPENDING (the producer runs
     * on the main dispatcher — a blocking acquire would jank) and
     * released on EVERY path (finally).
     */
    suspend fun preview(
        uri: String,
        displayName: String,
        fdProvider: () -> Int,
        gate: Semaphore,
    ): File? {
        store.folderFileFor(uri)?.let { return it }
        gate.acquire()
        try {
            // The open + render hop to the ENGINE thread through the
            // session seam regardless of the caller's dispatcher; the
            // IO context hosts the resolver's binder call, the store's
            // file reads, and the PNG write — the cell's producer runs
            // on the MAIN dispatcher, and none of that belongs there
            // (the no-jank bound for a 100+ video folder).
            return withContext(Dispatchers.IO) {
                generate(uri, displayName, fdProvider)
            }
        } finally {
            gate.release()
        }
    }

    private suspend fun generate(
        uri: String,
        displayName: String,
        fdProvider: () -> Int,
    ): File? {
        val session = PreviewSessions.open(
            path = SourcePathDecision.decide(
                VideoTree.kindFor(displayName),
                null,
            ),
            fdProvider = fdProvider,
            engine = engine,
        ).getOrNull() ?: return null // failed open: retry the next open
        session.use { s ->
            // Neutral develop of frame 0, small long edge (the
            // generateEncoded precedent — the owner accepts the dark
            // look; the browser's cell is ~120dp wide, so 256px stays
            // above the display need and never upscales).
            val frame = s.render(frameIndex = 0, grade = GradeK(), maxDim = maxDim)
                .getOrNull() ?: return null
            store.ensureFolderDir()
            val ok = pngWriter.write(
                frame.argb, frame.width, frame.height,
                store.folderTargetPath(uri),
            )
            return if (ok) File(store.folderTargetPath(uri)) else null
        }
    }
}
