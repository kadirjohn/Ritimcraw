package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.engine.PreviewQuality
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * App-level preview-quality persistence (owner request 2026-11-11b
 * section 3): the user's playback/scrub resolution choice, stored in the
 * shared `files/project.json` as a TOP-LEVEL SCALAR next to the stores'
 * sections:
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ],
 *     "previewQuality": "HIGH" }   <- this store; omitted at default
 *
 * Omit-when-default (the GradeStore pattern): files that never chose a
 * level stay byte-identical, and choosing then returning to BALANCED
 * removes the key rather than writing "BALANCED" — a round trip of an old
 * file through this store leaves it unchanged. Old files (no
 * "previewQuality" key) load as [PreviewQuality.DEFAULT] = today's fixed
 * tiers, so the picker changes nothing until the owner moves it.
 *
 * Sharing rules, same as the other project.json stores: every write
 * re-renders the whole file but touches ONLY this store's key — the other
 * stores' sections are preserved raw ([ProjectJson.renderRaw] round-trips
 * them), and THEIR writes preserve this key because
 * [ProjectJson.withSection] keeps unknown top-level entries (scalars
 * included) it re-renders.
 *
 * Not per-clip on purpose: the tradeoff is about the DEVICE's ability to
 * decode in real time, not about any one clip.
 */
class PreviewQualityStore(private val file: File) {

    fun get(): PreviewQuality = synchronized(lock) { read() }

    /** Persists immediately. Writing the default REMOVES the key. */
    fun put(level: PreviewQuality) {
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

    private fun read(): PreviewQuality {
        if (!file.exists()) return PreviewQuality.DEFAULT
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8))
                .parseValue() as? Map<*, *>
                ?: return PreviewQuality.DEFAULT
            // Absent key, JSON null, or a non-string value all resolve to
            // the default through fromWire's null branch.
            PreviewQuality.fromWire(root[PreviewQuality.WIRE_NAME] as? String)
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable at the default (the
            // GradeStore corrupt-file contract).
            PreviewQuality.DEFAULT
        }
    }

    companion object {
        // One lock for every instance: AppNav and tests can each hold a
        // store over the same file without racing.
        private val lock = Any()

        /**
         * Renders project.json with the preview-quality key set to [level]
         * — or ABSENT at the default — preserving every other top-level
         * entry of [existingText] raw. The single wire-format definition;
         * pure and JVM-testable without touching the filesystem.
         *
         * Render order: "version" first (preserved from the file, 1 for a
         * fresh one), then every other existing entry in encounter order
         * (which is the canonical order the other stores write), then this
         * store's key last when set. Deterministic: the same input and
         * level always render the same bytes.
         */
        internal fun mergeInto(existingText: String?, level: PreviewQuality): String {
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
                if (name != "version" && name != PreviewQuality.WIRE_NAME && v != null) {
                    sb.append(',')
                        .append(ProjectJson.quote(name))
                        .append(':')
                        .append(ProjectJson.renderRaw(v))
                }
            }
            if (level != PreviewQuality.DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(PreviewQuality.WIRE_NAME))
                    .append(":\"")
                    .append(level.name)
                    .append('"')
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
