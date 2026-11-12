package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The granted gyro-folder tree URI (owner request 2026-11-12, G1):
 * the SAF ACTION_OPEN_DOCUMENT_TREE grant whose children are queried
 * for .gcsv logs on every video import, persisted in the shared
 * `files/project.json` as a TOP-LEVEL SCALAR next to the stores'
 * sections (the [PreviewQualityStore] pattern):
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ], "gyros": [ ... ],
 *     "gyroFolder": "content://..." }   <- this store; omitted at default
 *
 * Omit-when-default: files that never picked a folder stay
 * byte-identical, and clearing the grant REMOVES the key rather than
 * writing "" — a round trip of an old file through this store leaves it
 * unchanged. Old files (no "gyroFolder" key) load as null = no folder
 * granted, so the auto-match changes nothing until the owner picks one.
 *
 * The URI is persisted as the raw tree string. The persistable grant
 * itself lives with the system (takePersistableUriPermission at pick
 * time, the app's other SAF convention) — this store only remembers
 * WHICH tree; a grant the system later drops reads as a failed query
 * and the import flow degrades to silent no-attach.
 *
 * Sharing rules, same as the other project.json stores: every write
 * re-renders the whole file but touches ONLY this key — the other
 * stores' sections are preserved raw, and THEIR writes preserve this
 * key because [ProjectJson.withSection] keeps unknown top-level entries
 * (scalars included) it re-renders.
 */
class GyroFolderStore(private val file: File) {

    /** The granted tree URI, or null when no folder was picked (or the record is unreadable). */
    fun get(): String? = synchronized(lock) { read() }

    /** Persists [uri] immediately. */
    fun put(uri: String) {
        synchronized(lock) { write(uri) }
    }

    /** Clears the grant record. Writing null REMOVES the key. */
    fun clear() {
        synchronized(lock) {
            // No file: nothing to clear — clear() must not CREATE a store.
            if (!file.exists()) return
            val text = file.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, null)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(merged, StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(merged, StandardCharsets.UTF_8)
                tmp.delete()
            }
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
            // Corrupt store: keep the app usable with no folder granted
            // (the GradeStore corrupt-file contract) — the auto-match
            // simply stays off until a fresh grant overwrites the file.
            null
        }
    }

    private fun write(uri: String) {
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = mergeInto(text, uri)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(merged, StandardCharsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Rename can fail across filesystems; fall back to
            // copy+delete (the GradeStore pattern).
            file.writeText(merged, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        internal const val WIRE_NAME = "gyroFolder"

        // One lock for every instance: AppNav and tests can each hold a
        // store over the same file without racing.
        private val lock = Any()

        /**
         * Renders project.json with the gyro-folder key set to [uri] —
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
