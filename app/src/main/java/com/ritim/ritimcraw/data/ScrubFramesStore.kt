package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * App-level scrub-frames persistence (F2-C3 wiring): whether the
 * editor's seek-drag shows the small cached frames while the finger
 * drags (the [com.ritim.ritimcraw.engine.ScrubFrames] facade). Stored in
 * the shared files/project.json beside the other scalar stores — the
 * SystemMonitorStore pattern (one boolean key, merged into the existing
 * object, the default omitted from the file):
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "previewQuality": "HIGH", "renderBackend": "AUTO",
 *     "systemMonitor": true,
 *     "scrubFrames": true }   <- this store; omitted at default
 *
 * DEFAULT FALSE (the behavior flag): the wiring is new and device
 * validation pending (the night mission's "GPU/visual behavior is a
 * morning device task" rule; the owner's standing convention for new
 * behavior — temporal NR 2's, the autohide player bar — is default
 * OFF). An untouched project.json keeps today's editor byte-identical:
 * every seek drag renders through the existing drag-tier path, exactly
 * as before the wiring existed. A true value turns the cached-frame path
 * on for every clip opened afterwards.
 *
 * Omit-when-default: choosing then returning to false REMOVES the key,
 * so a round trip of an old file through this store leaves it unchanged.
 * Absent key, JSON null, a non-boolean value, or a corrupt file all
 * resolve to FALSE — a bad file can never silently enable the path.
 *
 * Sharing rules, same as every project.json store: every write
 * re-renders the whole file but touches ONLY this key — the other
 * stores' entries are preserved raw ([ProjectJson.renderRaw]), and
 * their writes preserve this key because [ProjectJson.withSection]
 * keeps unknown top-level entries it re-renders.
 */
class ScrubFramesStore(private val file: File) {

    fun get(): Boolean = synchronized(lock) { read() }

    /** Persists immediately. Writing the default REMOVES the key. */
    fun put(enabled: Boolean) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, enabled)
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
        const val WIRE_NAME = "scrubFrames"

        /** Off: the wiring stays dark until the owner flips it. */
        const val DEFAULT = false

        private val lock = Any()

        /**
         * Renders project.json with the scrub-frames key set to [enabled]
         * — or ABSENT at the default — preserving every other top-level
         * entry of [existingText] raw. The single wire-format
         * definition; pure and JVM-testable without touching the
         * filesystem. Same shape as SystemMonitorStore.mergeInto.
         */
        internal fun mergeInto(existingText: String?, enabled: Boolean): String {
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
            if (enabled != DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_NAME))
                    .append(':')
                    .append(enabled.toString())
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
