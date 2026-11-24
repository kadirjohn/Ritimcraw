package com.ritim.ritimcraw.data

import java.io.File
import java.security.MessageDigest

/**
 * Pure thumbnail-state decisions for the Library row (owner request
 * 2026-11-11 section 1), JVM-testable without any platform piece: which
 * records still need a thumbnail, and what a row shows while one is
 * missing. The generation pass consults [needsThumbnail] before opening a
 * clip; the row UI consults [RowState] for its placeholder.
 */
object ThumbnailRules {

    /** Pixel ceiling for the CPU encoded develop of an icon: DCI 4K (4096x2160). */
    const val ENCODED_DEVELOP_MAX_PIXELS: Long = 4096L * 2160L

    /**
     * True when an encoded clip of [width]x[height] can take the full-frame
     * CPU develop for its icon. Above 4K (5K, 8K) the full-size ARGB
     * arrays exhaust the JVM heap, so the icon comes from the platform's
     * scaled frame instead. Unknown dimensions (0) try the develop.
     */
    fun encodedDevelopFits(width: Int, height: Int): Boolean =
        width.toLong() * height.toLong() <= ENCODED_DEVELOP_MAX_PIXELS

    /**
     * True when [source] still needs a thumbnail generated: no persisted
     * file ([hasFile]) and access to the clip is OK (a LOST or GONE row
     * cannot be probed — it retries on the next Library open after the
     * grant returns or the re-link lands). A clip that already has its
     * file is never re-developed — decode ONCE, persist, never redevelop
     * per composition.
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

    /**
     * The TRANSIENT full-res JPEG master of clip [id] (owner request
     * 2026-11-14: the MCRAW import develops the first frame at FULL
     * resolution and saves it as a JPEG before the icon is extracted).
     * The master NEVER persists: the icon extraction consumes it and the
     * pass deletes it (see [ClipThumbnails]); this path exists so the
     * pass and [remove] agree on the name, and so a crash between write
     * and delete leaves a file the NEXT pass or delete cleans up.
     */
    fun masterPath(id: String): File = File(dir, "$id.jpg")

    /** Creates the store's directory (idempotent). */
    fun ensureDir(): File = dir.apply { mkdirs() }

    /**
     * Deletes clip [id]'s persisted PNG (owner finding 2026-11-11 E2:
     * Library delete) AND its stale JPEG master if one was left behind
     * (a crash between master write and icon extraction — the master is
     * transient by design). A no-op when neither exists — deleting a
     * clip that never got its thumbnail must not throw. Other ids' files
     * are never touched (the id is the file name, so one delete cannot
     * collide).
     */
    fun remove(id: String) {
        val f = File(dir, "$id.png")
        if (f.exists()) f.delete()
        val master = masterPath(id)
        if (master.exists()) master.delete()
    }

    // ---- folder previews (owner request 2026-11-15) ----------------------
    //
    // A NOT-yet-imported folder file has NO MediaSource record — no id,
    // no place in the id-keyed namespace above. Its pre-import preview
    // keys on the DOCUMENT URI instead (stable within the tree grant),
    // hashed to a flat file name and kept in a SEPARATE subfolder under
    // the same store dir, so [remove] and every id-keyed read above
    // structurally cannot touch these files and vice versa.

    /**
     * Pure: the folder-preview key of a document URI — the SHA-256 of
     * the FULL uri string, first 16 hex chars (the SAME width as the
     * library id's hex — [com.ritim.ritimcraw.data.SourceRepository]'s
     * fingerprint cut — so both namespaces carry uniformly-sized names).
     * The full uri (not the document id) is hashed: two grants of the
     * same tree spell the same document uri identically, while the
     * document id alone would collide across authorities. The prefix
     * keeps the name out of the hex id space entirely (an id can never
     * collide with a folder key — different alphabets).
     */
    fun folderKeyFor(documentUri: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hex = md.digest(documentUri.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(FOLDER_HEX_CHARS)
        return "$FOLDER_PREFIX$hex.png"
    }

    /**
     * The folder-preview subdir under the store dir — [FOLDER_DIR]
     * files only, never an id-keyed PNG.
     */
    val folderDir: File get() = File(dir, FOLDER_DIR)

    /** Creates the folder-preview dir (idempotent). */
    fun ensureFolderDir(): File = folderDir.apply { mkdirs() }

    /** The persisted folder-preview PNG for [documentUri], or null. */
    fun folderFileFor(documentUri: String): File? {
        val f = File(folderDir, folderKeyFor(documentUri))
        return if (f.exists()) f else null
    }

    /** True when [documentUri] has a persisted folder preview. */
    fun folderHas(documentUri: String): Boolean = folderFileFor(documentUri) != null

    /** The path a folder-preview pass writes [documentUri]'s PNG to. */
    fun folderTargetPath(documentUri: String): String =
        File(folderDir, folderKeyFor(documentUri)).absolutePath

    /**
     * Deletes [documentUri]'s folder preview — the import cleanup (owner
     * request 2026-11-15): once the per-import pipeline generated the
     * id-keyed icon, the pre-import preview is redundant. A no-op when
     * none exists.
     */
    fun removeFolder(documentUri: String) {
        val f = File(folderDir, folderKeyFor(documentUri))
        if (f.exists()) f.delete()
    }

    /**
     * Bounded retention for the folder namespace: un-imported files'
     * previews accumulate as the owner browses (there is no import to
     * clean them), so a sweep deletes OLDEST-first past [keep]. A no-op
     * at or under the cap. Returns the number deleted.
     */
    fun trimFolder(keep: Int): Int {
        val files = folderDir.listFiles { f -> f.isFile } ?: return 0
        if (files.size <= keep) return 0
        val excess = files.sortedBy { it.lastModified() }
            .take(files.size - keep)
        var deleted = 0
        for (f in excess) {
            if (f.delete()) deleted++
        }
        return deleted
    }

    private companion object {
        /** The folder-preview subdir's name, inside the store dir. */
        const val FOLDER_DIR = "folder"

        /** The folder key's hex chars after the prefix (the id width). */
        const val FOLDER_HEX_CHARS = 16

        /** The alphabet separator: no library id starts with this. */
        const val FOLDER_PREFIX = "doc-"
    }
}
