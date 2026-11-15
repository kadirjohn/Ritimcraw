package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * The Library toolbar's sort + view choices (library toolbar redesign,
 * spec 2026-11-16): which field the list sorts by, which direction, and
 * whether the Library renders as a list or a grid — the ONLY fields that
 * exist on the item are offered ([LibrarySortKey]: Name, Duration,
 * Resolution — the spec's Date and File size do not exist on
 * [MediaSource], so they are omitted from the menu entirely).
 *
 * Stored in the shared `files/project.json` as THREE TOP-LEVEL SCALARS
 * next to the other stores' scalars — the ThemeStore/PreviewStatsStore
 * pattern, one store owning this toolbar's keys:
 *
 *   { "version": 1,
 *     "grades": { ... }, "sources": [ ... ], "exports": [ ... ],
 *     "luts": [ ... ], "presets": [ ... ],
 *     "previewQuality": "HIGH", "renderBackend": "AUTO",
 *     "theme": "DARK",
 *     "library_sort_key": "NAME",
 *     "library_sort_ascending": false,
 *     "library_view_mode": "grid" }   <- this store; each omitted at default
 *
 * Defaults are TODAY'S behavior, so a file that never touched the toolbar
 * stays byte-identical (omit-when-default, additive only — no existing
 * key or value changes):
 *
 *  - [LibrarySortKey] ABSENT = import order: the list keeps the catalog's
 *    own order (the "sources" array order — today's order), which is what
 *    the screen rendered before the toolbar existed. Choosing a key and
 *    then returning to it is not a menu option (the spec's menu lists
 *    only the three fields); the default is the never-touched state.
 *  - [LibrarySortKey] wire values are the enum names ("NAME",
 *    "DURATION", "RESOLUTION" — the ThemeMode pattern); an absent key,
 *    JSON null, or an unknown value all resolve to the default (null).
 *  - "library_sort_ascending" is a JSON boolean, default TRUE (the
 *    menu's Ascending), omitted while true — the PreviewStatsStore
 *    boolean shape.
 *  - [LibraryViewMode] wire values are the spec's own "list"/"grid",
 *    default LIST (today's rendering), omitted while LIST.
 *
 * Sharing rules, same as every other project.json store: every write
 * re-renders the whole file but touches ONLY this store's three keys —
 * the other stores' entries are preserved raw ([ProjectJson.renderRaw]),
 * and their writes preserve these keys because [ProjectJson.withSection]
 * keeps unknown top-level entries it re-renders. A corrupt file reads
 * as all-defaults and the next write self-heals to the minimal valid
 * shape (the ThemeStore corrupt-file contract).
 *
 * The read side never touches Compose: both enums are plain, so the
 * store stays JVM-testable like its siblings, and the ORDERING itself
 * lives in the pure ui-library rule
 * ([com.ritim.ritimcraw.ui.library.LibrarySort]) over these values.
 */
class LibraryPrefsStore(private val file: File) {

    /** The persisted sort field — null means today's order (import order). */
    fun sortKey(): LibrarySortKey? = synchronized(lock) { readPrefs(currentText()).sortKey }

    /** The persisted direction — true is the menu's Ascending (the default). */
    fun ascending(): Boolean = synchronized(lock) { readPrefs(currentText()).ascending }

    /** The persisted view mode — LIST is today's rendering (the default). */
    fun viewMode(): LibraryViewMode = synchronized(lock) { readPrefs(currentText()).viewMode }

    /** Persists immediately. Writing the default (null) REMOVES the key. */
    fun putSortKey(key: LibrarySortKey?) {
        synchronized(lock) { rewrite { it.copy(sortKey = key) } }
    }

    /** Persists immediately. Writing the default (true) REMOVES the key. */
    fun putAscending(ascending: Boolean) {
        synchronized(lock) { rewrite { it.copy(ascending = ascending) } }
    }

    /** Persists immediately. Writing the default (LIST) REMOVES the key. */
    fun putViewMode(mode: LibraryViewMode) {
        synchronized(lock) { rewrite { it.copy(viewMode = mode) } }
    }

    private fun currentText(): String? =
        file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)

    /**
     * One put's whole body: parse the file's CURRENT triple, swap the one
     * component, re-render the whole file with the new triple (preserving
     * every other top-level entry raw), and land it atomically — the
     * ThemeStore tmp-file + rename shape. [Prefs] carries the parsed
     * triple between the read and the render so a put can never drop a
     * sibling key it was not asked to touch.
     */
    private fun rewrite(transform: (Prefs) -> Prefs) {
        val text = currentText()
        val next = transform(readPrefs(text))
        val merged = mergeInto(text, next.sortKey, next.ascending, next.viewMode)
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

    /**
     * The parsed triple. Missing/corrupt text resolves to the defaults —
     * the same branches the public getters run for a missing file, so a
     * rewrite of a bad file lands the app at the defaults (self-heal).
     */
    private fun readPrefs(text: String?): Prefs {
        val root = text?.let {
            runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
        } ?: return Prefs.DEFAULT
        return Prefs(
            // Absent key, JSON null, or an unknown value all resolve to
            // the default through fromWire's null branch.
            sortKey = LibrarySortKey.fromWire(root[WIRE_SORT_KEY] as? String),
            // A non-boolean value (a string "false", a number) resolves
            // to the default — the PreviewStatsStore read shape.
            ascending = root[WIRE_ASCENDING] as? Boolean ?: true,
            viewMode = LibraryViewMode.fromWire(root[WIRE_VIEW_MODE] as? String),
        )
    }

    /** This store's three keys, parsed together (one read, one render). */
    private data class Prefs(
        val sortKey: LibrarySortKey?,
        val ascending: Boolean,
        val viewMode: LibraryViewMode,
    ) {
        companion object {
            val DEFAULT = Prefs(null, true, LibraryViewMode.DEFAULT)
        }
    }

    companion object {
        // One lock for every instance: AppNav and tests can each hold a
        // store over the same file without racing.
        private val lock = Any()

        /** The spec's own key names (library toolbar redesign 2026-11-16). */
        internal const val WIRE_SORT_KEY = "library_sort_key"
        internal const val WIRE_ASCENDING = "library_sort_ascending"
        internal const val WIRE_VIEW_MODE = "library_view_mode"

        /**
         * Renders project.json with this store's three keys set to the
         * given triple — each ABSENT at its default — preserving every
         * other top-level entry of [existingText] raw. The single
         * wire-format definition; pure and JVM-testable without touching
         * the filesystem.
         *
         * Render order: "version" first (preserved from the file, 1 for a
         * fresh one), then every other existing entry in encounter order,
         * then this store's keys last when set, in the spec's order
         * (sort key, ascending, view mode). Deterministic.
         */
        internal fun mergeInto(
            existingText: String?,
            sortKey: LibrarySortKey?,
            ascending: Boolean,
            viewMode: LibraryViewMode,
        ): String {
            val root = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }
            val sb = StringBuilder()
            sb.append("{\"version\":")
                .append(ProjectJson.renderRaw(root?.get("version") ?: 1))
            root?.forEach { (k, v) ->
                val name = k.toString()
                if (name != "version" && name != WIRE_SORT_KEY &&
                    name != WIRE_ASCENDING && name != WIRE_VIEW_MODE && v != null
                ) {
                    sb.append(',')
                        .append(ProjectJson.quote(name))
                        .append(':')
                        .append(ProjectJson.renderRaw(v))
                }
            }
            if (sortKey != null) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_SORT_KEY))
                    .append(":\"")
                    .append(sortKey.wireName)
                    .append('"')
            }
            if (ascending != true) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_ASCENDING))
                    .append(':')
                    .append(ascending)
            }
            if (viewMode != LibraryViewMode.DEFAULT) {
                sb.append(',')
                    .append(ProjectJson.quote(WIRE_VIEW_MODE))
                    .append(":\"")
                    .append(viewMode.wireName)
                    .append('"')
            }
            sb.append('}')
            return sb.toString()
        }
    }
}

/**
 * The Library sort menu's fields (library toolbar redesign, 2026-11-16):
 * ONLY the fields that exist on [MediaSource] — the spec's Date and File
 * size have no counterpart on the record, so they are not offered. The
 * menu maps labels at the call site (the ThemeMode pattern: this enum
 * stays resource-free so the data layer never depends on the UI).
 */
enum class LibrarySortKey(val wireName: String) {
    NAME("NAME"),
    DURATION("DURATION"),
    RESOLUTION("RESOLUTION");

    companion object {
        /**
         * Absent key, JSON null, or an unknown value all resolve to the
         * default: NULL — today's order (import order), which is not a
         * menu option but the never-touched state.
         */
        fun fromWire(value: String?): LibrarySortKey? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * The Library's two renderings (library toolbar redesign, 2026-11-16):
 * the list (today's rendering, the default) and the grid. Wire values
 * are the spec's own "list"/"grid".
 */
enum class LibraryViewMode(val wireName: String) {
    LIST("list"),
    GRID("grid");

    /** The toggle's target: the other rendering. */
    fun other(): LibraryViewMode = if (this == LIST) GRID else LIST

    companion object {
        /** Today's rendering: the list. */
        val DEFAULT: LibraryViewMode = LIST

        /** Absent key, JSON null, or an unknown value all resolve to the default. */
        fun fromWire(value: String?): LibraryViewMode =
            entries.firstOrNull { it.wireName == value } ?: DEFAULT
    }
}
