package com.ritim.ritimcraw.data

/** One child of a document's parent folder. */
data class SiblingDoc(val name: String, val uri: String)

/**
 * The only file I/O of the "rename also renames the file" feature (owner
 * request 2026-11-26). Production: [AndroidSourceFileRenamer] over SAF;
 * tests: a fake. Never deletes or moves anything; renames in place only.
 */
interface SourceFileRenamer {
    /**
     * The documents in the same folder as [uri] (the clip itself included),
     * or null when the folder cannot be listed (not a tree document, no
     * grant, provider refuses) - the caller then falls back to app-only.
     */
    fun siblings(uri: String): List<SiblingDoc>?

    /** Renames the document in place. The new URI, or null on any failure. */
    fun rename(uri: String, newName: String): String?
}

/** What [SourceFileRename.apply] did to the files. */
sealed class FileRenameOutcome {
    /** Main file (and the sidecar, when one exists) renamed. */
    data class Renamed(
        val newUri: String,
        val newFileName: String,
        val sidecarRenamed: Boolean,
    ) : FileRenameOutcome()

    /**
     * The main file was renamed, the sidecar rename failed AND the
     * rollback failed too: the clip now lives at [newUri]; the caller must
     * follow it so nothing is orphaned, and warn.
     */
    data class Partial(val newUri: String, val newFileName: String) : FileRenameOutcome()

    object InvalidName : FileRenameOutcome()
    object TargetExists : FileRenameOutcome()
    object SidecarAmbiguous : FileRenameOutcome()
    object SidecarFailedRolledBack : FileRenameOutcome()

    /** Files cannot be renamed here: fall back to the app-only rename. */
    object Unsupported : FileRenameOutcome()
}

/** Pure rules + the sequencing (main, sidecar, rollback) of a file rename. */
object SourceFileRename {

    private const val FILE_NAME_MAX_BYTES = 255
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /**
     * The file name a typed clip name maps to: the typed text, plus the
     * current file's extension unless the text already ends with it
     * (case-insensitive). Null when the name is not acceptable as a file
     * name: stricter than [ClipNameRules] (no path/reserved characters, no
     * control characters, no leading dot or trailing dot/space, at most 255
     * UTF-8 bytes including the extension).
     */
    fun targetFileName(typed: String, currentFileName: String): String? {
        val name = ClipNameRules.sanitize(typed).trim()
        if (name.isEmpty()) return null
        if (name.any { it in FORBIDDEN || it.code < 0x20 || it.code == 0x7f }) return null
        if (name.startsWith(".") || name.endsWith(".") || name.endsWith(" ")) return null
        val ext = extensionOf(currentFileName)
        val full = if (ext.isNotEmpty() && name.endsWith(ext, ignoreCase = true) &&
            name.length > ext.length
        ) name else name + ext
        if (full.length == ext.length) return null
        if (full.toByteArray(Charsets.UTF_8).size > FILE_NAME_MAX_BYTES) return null
        return full
    }

    /** ".mcraw" for "a.mcraw"; "" when there is no extension. */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0) "" else fileName.substring(dot)
    }

    private fun stemOf(fileName: String): String =
        extensionOf(fileName).let { fileName.substring(0, fileName.length - it.length) }

    fun apply(
        renamer: SourceFileRenamer,
        uri: String,
        currentFileName: String,
        typed: String,
    ): FileRenameOutcome {
        val target = targetFileName(typed, currentFileName) ?: return FileRenameOutcome.InvalidName
        val siblings = try {
            renamer.siblings(uri)
        } catch (e: Exception) {
            null
        } ?: return FileRenameOutcome.Unsupported
        // A same-name target (typed == current) is rejected by the caller as
        // Unchanged; here an identical name would be a self-collision.
        if (target == currentFileName) return FileRenameOutcome.TargetExists

        val others = siblings.filter { it.uri != uri }
        if (others.any { it.name.equals(target, ignoreCase = true) }) {
            return FileRenameOutcome.TargetExists
        }
        val stem = stemOf(currentFileName)
        val sidecars = others.filter {
            it.name.endsWith(".gcsv", ignoreCase = true) &&
                stemOf(it.name).equals(stem, ignoreCase = true)
        }
        if (sidecars.size > 1) return FileRenameOutcome.SidecarAmbiguous
        val sidecar = sidecars.firstOrNull()
        val sidecarTarget = sidecar?.let { stemOf(target) + extensionOf(it.name) }
        if (sidecarTarget != null &&
            others.any { it.name.equals(sidecarTarget, ignoreCase = true) }
        ) {
            return FileRenameOutcome.TargetExists
        }

        val newUri = try {
            renamer.rename(uri, target)
        } catch (e: Exception) {
            null
        } ?: return FileRenameOutcome.Unsupported
        if (sidecar == null) {
            return FileRenameOutcome.Renamed(newUri, target, sidecarRenamed = false)
        }
        val sidecarOk = try {
            renamer.rename(sidecar.uri, sidecarTarget!!) != null
        } catch (e: Exception) {
            false
        }
        if (sidecarOk) return FileRenameOutcome.Renamed(newUri, target, sidecarRenamed = true)
        val rolledBack = try {
            renamer.rename(newUri, currentFileName) != null
        } catch (e: Exception) {
            false
        }
        return if (rolledBack) {
            FileRenameOutcome.SidecarFailedRolledBack
        } else {
            FileRenameOutcome.Partial(newUri, target)
        }
    }
}

/** How a rename request ended (the UI maps each to one short message). */
enum class RenameStatus {
    /** Unknown clip. */
    MISSING,
    /** Same name as now: nothing done. */
    UNCHANGED,
    /** App name and the real file(s) renamed. */
    FILE_RENAMED,
    /** App name renamed; the file could not be (note shown). */
    APP_ONLY,
    /** Main file renamed, sidecar stuck with the old name (rollback failed). */
    PARTIAL,
    REFUSED_INVALID,
    REFUSED_EXISTS,
    REFUSED_SIDECAR_AMBIGUOUS,
    REFUSED_SIDECAR_FAILED,
}

data class ClipRenameResult(val status: RenameStatus, val source: MediaSource?) {
    /** True when the app-side name changed (the rename "took"). */
    val accepted: Boolean
        get() = status == RenameStatus.FILE_RENAMED || status == RenameStatus.APP_ONLY ||
            status == RenameStatus.PARTIAL
}
