package com.ritim.ritimcraw.data

/**
 * Shared merge logic for `files/project.json` (schema 1). Three stores
 * persist into the same file, each in its own section:
 *
 *   { "version": 1,
 *     "grades":  { "<sourceId>": { ... } },   // GradeStore
 *     "sources": [ ... ],                      // SourceRepository
 *     "exports": [ ... ] }                      // ExportStore
 *
 * Every store write re-renders only its own section and preserves the other
 * two untouched (parsed and re-rendered raw, never interpreted), so the
 * stores can share project.json without clobbering each other. Section order
 * in the rendered file is fixed (grades, sources, exports, then any unknown
 * section in encounter order) so output is deterministic and testable.
 *
 * JSON is written with the hand-rolled reader/writer because the offline
 * Gradle cache has kotlinx-serialization-core but not -json.
 */
internal object ProjectJson {

    /** Canonical section order in the rendered file. */
    private val SECTION_ORDER = listOf("grades", "sources", "exports")

    /**
     * Renders the whole project.json with [section] set to [renderedSection],
     * preserving every other section already present in [existingText].
     * Unknown sections (future schema) are kept raw rather than dropped.
     */
    fun withSection(
        existingText: String?,
        section: String,
        renderedSection: String,
        version: Int,
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
        sb.append("{\"version\":").append(version)
        for (name in ordered) {
            sb.append(',').append(quote(name)).append(':').append(sections[name])
        }
        sb.append('}')
        return sb.toString()
    }

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
