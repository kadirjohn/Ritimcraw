package com.ritim.ritimcraw.engine

import java.io.File

/**
 * Where the image-stabilization analyses live (docs/IMAGE_STAB_PLAN.md
 * section 3.6): `filesDir/cache/imagestab/<fingerprint>-v<version>.ristab`.
 * `filesDir`, not `cacheDir`: the analysis is expensive and the OS may
 * clear `cacheDir`. The key is the source fingerprint plus
 * [ANALYSIS_VERSION] ONLY — grade, strength, mode and range never enter it,
 * so changing any of them does not re-run the analysis.
 *
 * A `.ristab` file exists only COMPLETE: the native analysis writes it
 * atomically (tmp + rename) after the last frame. Progress lives in the
 * sibling `<name>.ristab.part` checkpoint, which a later run resumes from.
 * Pure JVM (files only), like ThumbnailStore.
 */
class ImageStabCache(private val dir: File) {

    /** The complete analysis file for [fingerprint] at the current version (may not exist). */
    fun fileFor(fingerprint: String): File = File(dir, nameOf(fingerprint, ANALYSIS_VERSION))

    /** True when a complete analysis exists (a stale version is not an analysis). */
    fun isComplete(fingerprint: String): Boolean = fileFor(fingerprint).isFile

    /** True when an interrupted run left a checkpoint to resume from. */
    fun hasPartial(fingerprint: String): Boolean =
        File(dir, nameOf(fingerprint, ANALYSIS_VERSION) + PART).isFile

    /** The path the native analysis writes (and resumes) for [fingerprint]; creates the directory. */
    fun targetPath(fingerprint: String): String {
        dir.mkdirs()
        return fileFor(fingerprint).absolutePath
    }

    /**
     * Removes every analysis artifact of [fingerprint] — complete files and
     * checkpoints of ANY version (a stale-version file is garbage). Missing
     * files are no-ops. Returns the number of files deleted.
     */
    fun delete(fingerprint: String): Int {
        if (fingerprint.isEmpty()) return 0
        var n = 0
        dir.listFiles()?.forEach { f ->
            val name = f.name
            if (name.startsWith("$fingerprint-v") &&
                (name.endsWith(SUFFIX) || name.endsWith(SUFFIX + PART))
            ) {
                if (f.delete()) n++
            }
        }
        return n
    }

    /** Total bytes held (for a storage page row). */
    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    /** Removes every analysis (the "clear stabilization analyses" action). */
    fun clear(): Int {
        var n = 0
        dir.listFiles()?.forEach { if (it.isFile && it.delete()) n++ }
        return n
    }

    companion object {
        /**
         * MUST equal rawedit::kImageStabAnalysisVersion (core/include/rawedit/ImageStab.h):
         * a file written under another version is refused by the native
         * loader (BadVersion), so a drift is "no analysis", never wrong data.
         */
        const val ANALYSIS_VERSION = 1
        const val SUFFIX = ".ristab"
        const val PART = ".part"

        fun nameOf(fingerprint: String, version: Int): String = "$fingerprint-v$version$SUFFIX"
    }
}

/**
 * Which clip handles currently hold a loaded analysis (S4). The native
 * handle table is the truth; this is the Kotlin-side memo the preview pump
 * reads per frame without a native call. [forget] runs when a handle is
 * closed (NativeSource.jniCloseSource), so a reused slot never inherits a
 * stale "loaded".
 */
object ImageStabLoads {
    private val loaded = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    fun isLoaded(handle: Long): Boolean = handle in loaded
    fun markLoaded(handle: Long) {
        loaded.add(handle)
    }
    fun forget(handle: Long) {
        loaded.remove(handle)
    }
}
