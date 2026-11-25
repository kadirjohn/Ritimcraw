package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Whether the editor's media player bar auto-hides while playing (compact
 * mode, owner request 2026-11-26). Persisted in the shared files/project.json
 * as one top-level boolean, the SystemMonitorStore pattern: merged into the
 * existing object, the default (false = today's always-visible bar) omitted
 * from the file.
 */
class AutoHidePlayerBarStore(private val file: File) {

    fun get(): Boolean = synchronized(lock) { read() }

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
        const val WIRE_NAME = "autoHidePlayerBar"
        const val DEFAULT = false

        private val lock = Any()

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
