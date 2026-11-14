package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The app theme choice (owner request 2026-11-14: "sistem teması neyse
 * ona göre uyumlu olsun" — the app should follow the system theme).
 * SYSTEM is the default and is exactly the pre-setting behavior: the
 * browse theme tracks the device's dark mode ([isDark] forwards the
 * system flag). LIGHT and DARK pin it for users who want the app to
 * disagree with the system.
 */
enum class ThemeMode {
    SYSTEM, LIGHT, DARK;

    /**
     * The dark-theme resolution for this choice: SYSTEM follows the
     * device ([systemInDarkTheme] — what RitimTheme's darkTheme default
     * did before the override existed), LIGHT and DARK are fixed
     * whatever the system says. Pure, JVM-testable; the single
     * definition the theme construction site calls.
     */
    fun isDark(systemInDarkTheme: Boolean): Boolean = when (this) {
        SYSTEM -> systemInDarkTheme
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** This store's top-level key in the shared project.json. */
        const val WIRE_NAME = "theme"

        /** The choice a file that never picked makes — follow the system. */
        val DEFAULT: ThemeMode = SYSTEM

        /** Absent key, JSON null, or an unknown value all resolve to the default. */
        fun fromWire(value: String?): ThemeMode =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}

/**
 * App-level theme persistence (owner request 2026-11-14): the user's
 * light/dark choice, stored in the shared `files/project.json` as a
 * TOP-LEVEL SCALAR next to the other stores' sections — exactly the
 * RenderBackendStore pattern:
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ],
 *     "previewQuality": "HIGH",
 *     "renderBackend": "AUTO",
 *     "theme": "DARK" }        <- this store; omitted at default
 *
 * Omit-when-default: files that never chose stay byte-identical, and
 * choosing then returning to SYSTEM removes the key rather than writing
 * "SYSTEM" — a round trip of an old file through this store leaves it
 * unchanged. Old files (no "theme" key) load as [ThemeMode.DEFAULT], so
 * the app keeps following the system until the owner moves the picker.
 *
 * Sharing rules, same as every other project.json store: every write
 * re-renders the whole file but touches ONLY this store's key — the other
 * stores' sections are preserved raw ([ProjectJson.renderRaw]), and their
 * writes preserve this key because [ProjectJson.withSection] keeps unknown
 * top-level entries it re-renders.
 *
 * The read side never touches Compose: [ThemeMode] is a plain enum and
 * [ThemeMode.isDark] resolves the system flag at the construction site,
 * so the store stays JVM-testable like its siblings.
 */
class ThemeStore(private val file: File) {

    fun get(): ThemeMode = synchronized(lock) { read() }

    /** Persists immediately. Writing the default REMOVES the key. */
    fun put(mode: ThemeMode) {
        synchronized(lock) {
            val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
            val merged = mergeInto(text, mode)
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

    private fun read(): ThemeMode {
        if (!file.exists()) return ThemeMode.DEFAULT
        return try {
            val root = JsonParser(file.readText(StandardCharsets.UTF_8))
                .parseValue() as? Map<*, *>
                ?: return ThemeMode.DEFAULT
            // Absent key, JSON null, or a non-string value all resolve to
            // the default through fromWire's null branch.
            ThemeMode.fromWire(root[ThemeMode.WIRE_NAME] as? String)
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable at the default (the
            // GradeStore corrupt-file contract).
            ThemeMode.DEFAULT
        }
    }

    companion object {
        // One lock for every instance: MainActivity, AppNav and tests can
        // each hold a store over the same file without racing.
        private val lock = Any()

        /**
         * Renders project.json with the theme key set to [mode] — or
         * ABSENT at the default — preserving every other top-level entry
         * of [existingText] raw. The single wire-format definition; pure
         * and JVM-testable without touching the filesystem.
         *
         * Render order: "version" first (preserved from the file, 1 for a
         * fresh one), then every other existing entry in encounter order,
         * then this store's key last when set. Deterministic.
         */
        internal fun mergeInto(existingText: String?, mode: ThemeMode): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
            sb.append("{\"version\":")
                .append(ProjectJson.renderRaw(root?.get("version") ?: 1))
            root?.forEach { (k, v) ->
                val name = k.toString()
                if (name != "version" && name != ThemeMode.WIRE_NAME && v != null) {
                    sb.append(',')
                        .append(ProjectJson.quote(name))
                        .append(':')
                        .append(ProjectJson.renderRaw(v))
                }
            }
            if (mode != ThemeMode.DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(ThemeMode.WIRE_NAME))
                    .append(":\"")
                    .append(mode.name)
                    .append('"')
            }
            sb.append('}')
            return sb.toString()
        }
    }
}
