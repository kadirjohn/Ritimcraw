package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.engine.RenderBackend
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * App-level render-backend persistence (owner request 2026-11-12,
 * deliverable B): the user's image-processing backend choice, stored in
 * the shared `files/project.json` as a TOP-LEVEL SCALAR next to the other
 * stores' sections — exactly the PreviewQualityStore pattern:
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ],
 *     "previewQuality": "HIGH",
 *     "renderBackend": "AUTO" }   <- this store; omitted at default
 *
 * Omit-when-default: files that never chose stay byte-identical, and
 * choosing then returning to CPU removes the key rather than writing
 * "CPU" — a round trip of an old file through this store leaves it
 * unchanged. Old files (no "renderBackend" key) load as
 * [RenderBackend.DEFAULT] = CPU, so the picker changes nothing until the
 * owner moves it.
 *
 * Sharing rules, same as every other project.json store: every write
 * re-renders the whole file but touches ONLY this store's key — the other
 * stores' sections are preserved raw ([ProjectJson.renderRaw]), and their
 * writes preserve this key because [ProjectJson.withSection] keeps unknown
 * top-level entries it re-renders.
 *
 * Not per-clip on purpose (owner prompt §14: the choice is global and
 * persists); an export job snapshots the value at job start so a mid-run
 * change of the global setting never changes an ongoing export.
 */
class RenderBackendStore(private val file: File) {

    fun get(): RenderBackend = synchronized(lock) { read() }

    /** Persists immediately. Writing the default REMOVES the key. */
    fun put(level: RenderBackend) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, level)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(merged, StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Rename can fail across filesystems; fall back to
                // copy+delete (GradeStore's pattern).
                file.writeText(merged, StandardCharsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun read(): RenderBackend {
        if (!file.exists()) return RenderBackend.DEFAULT
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8))
                .parseValue() as? Map<*, *>
                ?: return RenderBackend.DEFAULT
            // Absent key, JSON null, or a non-string value all resolve to
            // the default through fromWire's null branch.
            RenderBackend.fromWire(root[RenderBackend.WIRE_NAME] as? String)
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable at the default (the
            // GradeStore corrupt-file contract).
            RenderBackend.DEFAULT
        }
    }

    companion object {
        // One lock for every instance: AppNav and tests can each hold a
        // store over the same file without racing.
        private val lock = Any()

        /**
         * Renders project.json with the render-backend key set to [level]
         * — or ABSENT at the default — preserving every other top-level
         * entry of [existingText] raw. The single wire-format definition;
         * pure and JVM-testable without touching the filesystem.
         *
         * Render order: "version" first (preserved from the file, 1 for a
         * fresh one), then every other existing entry in encounter order,
         * then this store's key last when set. Deterministic.
         */
        internal fun mergeInto(existingText: String?, level: RenderBackend): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
            sb.append("{\"version\":")
                .append(ProjectJson.renderRaw(root?.get("version") ?: 1))
            root?.forEach { (k, v) ->
                val name = k.toString()
                if (name != "version" && name != RenderBackend.WIRE_NAME && v != null) {
                    sb.append(',')
                        .append(ProjectJson.quote(name))
                        .append(':')
                        .append(ProjectJson.renderRaw(v))
                }
            }
            if (level != RenderBackend.DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(RenderBackend.WIRE_NAME))
                    .append(":\"")
                    .append(level.name)
                    .append('"')
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
