package com.ritim.ritimcraw.data

/**
 * Shared merge logic for `files/project.json` (schema 1). Four stores
 * persist into the same file, each in its own section:
 *
 *   { "version": 1,
 *     "grades":  { "<sourceId>": { ... } },   // GradeStore
 *     "sources": [ ... ],                      // SourceRepository
 *     "exports": [ ... ],                      // ExportStore
 *     "luts":    [ ... ] }                     // LutStore
 *
 * Every store write re-renders only its own section and preserves the other
 * three untouched (parsed and re-rendered raw, never interpreted), so the
 * stores can share project.json without clobbering each other. Section order
 * in the rendered file is fixed (grades, sources, exports, luts, then any
 * unknown section in encounter order) so output is deterministic and
 * testable.
 *
 * JSON is written with the hand-rolled reader/writer because the offline
 * Gradle cache has kotlinx-serialization-core but not -json.
 */
internal object ProjectJson {

    /**
     * Canonical section order in the rendered file. "timeline" (the
     * timeline editing model, TimelineStore — Stage C) is appended
     * additively at the end: the four original stores keep their order
     * and their rendered files, and the timeline section renders last.
     * "audio" (the audio track model, AudioStore — Stage C AUD-02/03)
     * follows the same additive precedent, appended after "timeline".
     * "metadata" (the per-source metadata records, MetadataStore — IN-13
     * Stage A) follows the same additive precedent, appended last.
     */
    private val SECTION_ORDER =
        listOf("grades", "sources", "exports", "luts", "timeline", "audio", "metadata")

    /**
     * Renders the whole project.json with [section] set to [renderedSection],
     * preserving every other section already present in [existingText].
     * Unknown sections (future schema) are kept raw rather than dropped.
     *
     * VERSION STAMP POLICY (color model v2 plan §6.5, F1-2): [version] is
     * IGNORED. The stamp is `max(what the file on disk already declares,
     * [ProjectMigrations.CURRENT_VERSION])`, so no store can ever drag the
     * file's version down by writing its own constant — the silent
     * corruption the plan flags: on a v2 file, a v2-unaware store writing
     * `version:1` would make the NEXT open run the v1->v2 migration a
     * second time, over v2 data. A v1 (or unversioned) file stamps
     * CURRENT_VERSION, exactly what every caller passed (all constants are
     * 1 and CURRENT_VERSION is 1), so files this build writes are
     * byte-identical to before. The ONLY sanctioned version changes are
     * [ProjectMigrations.migrate] (a migration step bumps the stamp) and
     * [ProjectFile.open], the single entry gate that runs the chain.
     */
    fun withSection(
        existingText: String?,
        section: String,
        renderedSection: String,
        @Suppress("UNUSED_PARAMETER") version: Int,
    ): String {
        val root = existingText?.let {
            runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
        }
        val sections = LinkedHashMap<String, String>()
        root?.forEach { (k, v) ->
            val name = k.toString()
            if (name != "version" && name != section && v != null) {
                sections[name] = renderRaw(v)
            }
        }
        sections[section] = renderedSection
        val ordered = SECTION_ORDER.filter { it in sections } +
            sections.keys.filterNot { it in SECTION_ORDER }
        val sb = StringBuilder()
        sb.append("{\"version\":").append(stampFor(root))
        for (name in ordered) {
            sb.append(',').append(quote(name)).append(':').append(sections[name])
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * The version a rendered file is stamped with: the file's own version
     * when it is NEWER than this build ([ProjectMigrations.versionOf]:
     * missing/invalid = 1), else CURRENT_VERSION. Never lower than either.
     */
    private fun stampFor(root: Map<*, *>?): Int =
        maxOf(ProjectMigrations.versionOf(root ?: emptyMap<Any?, Any?>()), ProjectMigrations.CURRENT_VERSION)

    /** Re-renders a parsed JSON value without interpreting it. */
    fun renderRaw(value: Any?): String {
        val sb = StringBuilder()
        renderRawTo(value, sb)
        return sb.toString()
    }

    private fun renderRawTo(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.append(quote(value))
            is Boolean -> sb.append(value)
            is Int, is Long -> sb.append(value)
            is Double ->
                // Integral doubles render without a trailing ".0", matching
                // how the writers emit numbers.
                if (value == value.toLong().toDouble()) sb.append(value.toLong())
                else sb.append(value)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    sb.append(quote(k.toString())).append(':')
                    renderRawTo(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    renderRawTo(v, sb)
                }
                sb.append(']')
            }
            else -> sb.append(quote(value.toString()))
        }
    }

    fun quote(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
