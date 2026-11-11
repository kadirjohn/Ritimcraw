package com.ritim.ritimcraw.data

import java.io.File

/**
 * Pure thumbnail-state decisions for the Library row (owner request
 * 2026-11-11 section 1), JVM-testable without any platform piece: which
 * records still need a thumbnail, and what a row shows while one is
 * missing. The generation pass consults [needsThumbnail] before opening a
 * clip; the row UI consults [RowState] for its placeholder.
 */
object ThumbnailRules {

    /**
     * True when [source] still needs a thumbnail generated: no persisted
     * file ([hasFile]) and access to the clip is OK (a LOST row cannot be
     * probed — it retries on the next Library open after the grant
     * returns). A clip that already has its file is never re-developed —
     * decode ONCE, persist, never redevelop per composition.
     */
    fun needsThumbnail(source: MediaSource, hasFile: Boolean): Boolean =
        !hasFile && source.access == Access.OK

    /**
     * The bounded backfill batch (owner request: the pass is bounded so a
     * huge library opens instantly and generates while the user looks at
     * the list): the FIRST [limit] records that still need a thumbnail,
     * in list order — the oldest imports surface first.
     */
    fun <T> batch(pending: List<T>, limit: Int): List<T> =
        if (limit <= 0) emptyList() else pending.take(limit)
}

/**
 * Persisted thumbnails: one small PNG per clip id under `files/thumbnails/`
 * (owner request 2026-11-11 section 1), written ONCE by the generation pass
 * and never re-developed afterwards. The row UI loads the file through the
 * same store. Filesystem-backed like [LutStore]'s copied cubes — the PNGs
 * are app-private and survive restarts.
 *
 * The in-memory cache is deliberately NOT here: the row UI keeps its own
 * per-composition state (see the Library row), because a Bitmap cache is a
 * device-gated concept; this store stays pure JVM (files only).
 */
class ThumbnailStore(private val dir: File) {

    /** The persisted PNG for clip [id], or null when none exists. */
    fun fileFor(id: String): File? {
        val f = File(dir, "$id.png")
        return if (f.exists()) f else null
    }

    /** True when clip [id] has a persisted thumbnail. */
    fun has(id: String): Boolean = fileFor(id) != null

    /** The path the generation pass writes clip [id]'s PNG to. */
    fun targetPath(id: String): String = File(dir, "$id.png").absolutePath

    /** Creates the store's directory (idempotent). */
    fun ensureDir(): File = dir.apply { mkdirs() }
}
