package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Whether the top system-monitor strip (GPU/CPU/RAM/storage) is shown —
 * owner request 2026-11-23 ("isterse gizlenebilecek ayarlardan"). Persisted
 * in the shared files/project.json beside the theme (the ThemeStore
 * pattern: one key, merged into the existing object, the default omitted
 * from the file). Default: shown.
 */
class SystemMonitorStore(private val file: File) {

    fun get(): Boolean = synchronized(lock) { read() }

    fun put(visible: Boolean) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, visible)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(merged, StandardCharsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(merged, StandardCharsets.UTF_8)
                tmp.delete()
            }
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
        const val WIRE_NAME = "systemMonitor"
        const val DEFAULT = true

        private val lock = Any()

        internal fun mergeInto(existingText: String?, visible: Boolean): String {
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
            if (visible != DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_NAME))
                    .append(':')
                    .append(visible.toString())
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
