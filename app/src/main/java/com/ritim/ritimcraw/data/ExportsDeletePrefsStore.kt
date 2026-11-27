package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The delete-from-disk checkbox's REMEMBERED choice (owner request
 * 2026-11-27, M10: "user'ın tıkladığı en son seçenek saklanmalı, eğer
 * delete from the disk seçerse diğer silme isteklerinde de bu seçili
 * gelmeli"): whether the exports delete dialogs' "Also delete the
 * exported file from disk" box opens CHECKED. Stored in the shared
 * files/project.json beside the other scalar stores — the
 * ScrubFramesStore/SystemMonitorStore pattern (one boolean key, merged
 * into the existing object, the default omitted from the file):
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "previewQuality": "HIGH", "renderBackend": "AUTO",
 *     "systemMonitor": true, "scrubFrames": true,
 *     "exportsDeleteFromDisk": true }   <- this store; omitted at default
 *
 * DEFAULT FALSE (the safe start): a first-time user — and any user who
 * never touched the box — still meets the M8 safe side (the record
 * leaves, the file stays), exactly like
 * [com.ritim.ritimcraw.export.ExportDelete.DEFAULT_DELETE_FROM_DISK].
 * The M8 rule ("never inherited from the last dialog") changed BY THE
 * OWNER'S OWN REQUEST: once the user CHECKS the box, every LATER delete
 * dialog (swipe and selection-mode both) opens checked; unchecking it
 * turns the memory back to false. The choice persists the MOMENT the
 * checkbox is toggled — not at the delete press — which is the exact
 * reading of "seçerse diğer silme isteklerinde de bu seçili gelmeli":
 * a checked-then-cancelled dialog still remembers the check.
 *
 * Omit-when-default: checking then unchecking REMOVES the key, so a
 * round trip of an old file through this store leaves it byte-identical.
 * Absent key, JSON null, a non-boolean value, or a corrupt file all
 * resolve to FALSE — a bad file can never silently arm a disk delete.
 *
 * Sharing rules, same as every project.json store: every write
 * re-renders the whole file but touches ONLY this key — the other
 * stores' entries are preserved raw ([ProjectJson.renderRaw]), and
 * their writes preserve this key because [ProjectJson.withSection]
 * keeps unknown top-level entries it re-renders.
 */
class ExportsDeletePrefsStore(private val file: File) {

    fun get(): Boolean = synchronized(lock) { read() }

    /** Persists immediately. Writing the default REMOVES the key. */
    fun put(checked: Boolean) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, checked)
            file.parentFile?.mkdirs()
            // The single write gate (plan §6.5/F1-2): atomic, and an
            // observable no-op when the process is read-only.
            ProjectFile.writeTextIfWritable(file, merged)
        }
    }

    private fun read(): Boolean {
        if (!file.exists()) return DEFAULT
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8))
                .parseValue() as? Map<*, *>
                ?: return DEFAULT
            (root[WIRE_NAME] as? Boolean) ?: DEFAULT
        } catch (e: IllegalArgumentException) {
            DEFAULT
        }
    }

    companion object {
        const val WIRE_NAME = "exportsDeleteFromDisk"

        /** Off: a first delete still asks the safe-side question. */
        const val DEFAULT = false

        private val lock = Any()

        /**
         * Renders project.json with the remembered checkbox value set to
         * [checked] — or ABSENT at the default — preserving every other
         * top-level entry of [existingText] raw. The single wire-format
         * definition; pure and JVM-testable without touching the
         * filesystem. Same shape as ScrubFramesStore.mergeInto.
         */
        internal fun mergeInto(existingText: String?, checked: Boolean): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
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
            if (checked != DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_NAME))
                    .append(':')
                    .append(checked.toString())
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
