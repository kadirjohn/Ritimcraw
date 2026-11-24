package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The remembered import-folder tree URI (owner request 2026-11-16): the
 * SAF ACTION_OPEN_DOCUMENT_TREE grant the folder-import browser reopens
 * on, persisted in the shared `files/project.json` as a TOP-LEVEL SCALAR
 * next to the stores' sections (the [GyroFolderStore] pattern):
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ], "gyros": [ ... ],
 *     "importFolder": "content://..." }   <- this store; omitted at default
 *
 * SEPARATE from [GyroFolderStore] on purpose — do not merge the two: the
 * tree grants have DIFFERENT LIFECYCLES. The gyro folder is a BACKGROUND
 * auto-match source queried for .gcsv logs after every video import;
 * the import folder is an INTERACTIVE browsing destination the user
 * opens, changes and re-opens. Swapping or clearing one must never
 * touch the other's URI or grant.
 *
 * Omit-when-default: files that never picked a folder stay
 * byte-identical, and clearing the record REMOVES the key rather than
 * writing "" — a round trip of an old file through this store leaves it
 * unchanged. Old files (no "importFolder" key) load as null = no
 * remembered folder, so the Library folder button routes to the tree
 * picker until the owner picks one (the first-time flow).
 *
 * The URI is persisted as the raw tree string. The persistable grant
 * itself lives with the system (takePersistableUriPermission at pick
 * time; releasePersistableUriPermission on a CHANGE, best-effort — the
 * caller's seams) — this store only remembers WHICH tree. A grant the
 * system later revoked surfaces as the browser's SecurityException
 * enumeration failure, and the store KEEPS the URI: the user may
 * re-grant the same folder through the browser's change-folder button,
 * and clearing would drop them back to the first-time picker flow for
 * a folder they already chose. The browser's failed state carries the
 * recovery.
 *
 * Sharing rules, same as the other project.json stores: every write
 * re-renders the whole file but touches ONLY this key — the other
 * stores' sections are preserved raw, and THEIR writes preserve this
 * key because [ProjectJson.withSection] keeps unknown top-level entries
 * (scalars included) it re-renders.
 */
class ImportFolderStore(private val file: File) {

    /** The remembered tree URI, or null when no folder was picked (or the record is unreadable). */
    fun get(): String? = synchronized(lock) { read() }

    /** Persists [uri] immediately. */
    fun put(uri: String) {
        synchronized(lock) { write(uri) }
    }

    /** Clears the record. Writing null REMOVES the key. */
    fun clear() {
        synchronized(lock) {
            // No file: nothing to clear — clear() must not CREATE a store.
            if (!file.exists()) return
            val text = file.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, null)
            // The single write gate (plan §6.5/F1-2): atomic, and an
            // observable no-op when the process is read-only.
            ProjectFile.writeTextIfWritable(file, merged)
        }
    }

    private fun read(): String? {
        if (!file.exists()) return null
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8))
                .parseValue() as? Map<*, *>
                ?: return null
            (root[WIRE_NAME] as? String)?.takeIf { it.isNotBlank() }
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable with no remembered folder
            // (the GradeStore corrupt-file contract) — the folder button
            // routes to the picker until a fresh pick overwrites the file.
            null
        }
    }

    private fun write(uri: String) {
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = mergeInto(text, uri)
        file.parentFile?.mkdirs()
        // The single write gate (plan §6.5/F1-2): atomic, and an
        // observable no-op when the process is read-only.
        ProjectFile.writeTextIfWritable(file, merged)
    }

    companion object {
        internal const val WIRE_NAME = "importFolder"

        // One lock for every instance: AppNav and tests can each hold a
        // store over the same file without racing.
        private val lock = Any()

        /**
         * Renders project.json with the import-folder key set to [uri] —
         * or ABSENT at null — preserving every other top-level entry of
         * [existingText] raw. The single wire-format definition; pure
         * and JVM-testable without touching the filesystem.
         */
        internal fun mergeInto(existingText: String?, uri: String?): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
            // The parser reads JSON numbers as Double; renderRaw emits
            // integral doubles without the ".0" so the version stays "1".
            sb.append("{\"version\":")
                .append(ProjectJson.renderRaw(root?.get("version") ?: 1))
            root?.forEach { (k, v) ->
                val name = k.toString()
                if (name != "version" && name != WIRE_NAME && v != null) {
                    sb.append(',')
                        .append(ProjectJson.quote(name))
                        .append(':')
                        .append(ProjectJson.renderRaw(v))
                }
            }
            if (uri != null) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_NAME))
                    .append(':')
                    .append(ProjectJson.quote(uri))
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
