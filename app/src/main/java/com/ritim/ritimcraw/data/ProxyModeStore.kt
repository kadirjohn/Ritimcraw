package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.proxy.ProxyMode
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Persisted proxy mode (Settings: Off / Ask after import / Automatic), a top-level
 * string scalar "proxyMode" in project.json, omitted at the default (OFF) — the
 * PreviewQualityStore pattern. Absent, null, unknown or corrupt resolve to OFF.
 */
class ProxyModeStore(private val file: File) {

    fun get(): ProxyMode = synchronized(lock) { read() }

    fun put(mode: ProxyMode) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, mode)
            file.parentFile?.mkdirs()
            ProjectFile.writeTextIfWritable(file, merged)
        }
    }

    private fun read(): ProxyMode {
        if (!file.exists()) return ProxyMode.DEFAULT
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8)).parseValue() as? Map<*, *>
                ?: return ProxyMode.DEFAULT
            ProxyMode.fromWire(root[ProxyMode.WIRE_NAME] as? String)
        } catch (e: IllegalArgumentException) {
            ProxyMode.DEFAULT
        }
    }

    companion object {
        private val lock = Any()

        internal fun mergeInto(existingText: String?, mode: ProxyMode): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
            sb.append("{\"version\":").append(ProjectJson.renderRaw(root?.get("version") ?: 1))
            root?.forEach { (k, v) ->
                val name = k.toString()
                if (name != "version" && name != ProxyMode.WIRE_NAME && v != null) {
                    sb.append(',').append(ProjectJson.quote(name)).append(':').append(ProjectJson.renderRaw(v))
                }
            }
            if (mode != ProxyMode.DEFAULT) {
                sb.append(',').append(ProjectJson.quote(ProxyMode.WIRE_NAME)).append(":\"").append(mode.name).append('"')
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
