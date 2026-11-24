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
     * VERSION STAMP POLICY (color model v2 plan §6.5, F1-2; completed for
     * the real v1->v2 step by F1-3): [version] is IGNORED. The stamp is
     * the FILE'S OWN version, so no store can drag it down by writing its
     * own constant AND no store can leap a v1 file to v2 over still-flat
     * sections (that would make the next [ProjectFile.open] skip the
     * migration — the gate is the only version mover). A FRESH file (no
     * readable root on disk) stamps [ProjectMigrations.CURRENT_VERSION]:
     * its sections are this build's own, nothing needs migrating. The ONLY
     * sanctioned version changes of an existing file are
     * [ProjectMigrations.migrate] (a step bumps the stamp) and
     * [ProjectFile.open], the single entry gate that runs the chain.
     */
    /**
     * Renders a whole ROOT with the canonical section order (version
     * first, then [SECTION_ORDER], then unknowns in encounter order) —
     * the shape a migrated file takes when the gate writes it back, the
     * same ordering [withSection] produces, so a gate-written project.json
     * is byte-stable against a store-written one.
     */
    fun renderRoot(root: Map<String, Any?>): String {
        val sections = LinkedHashMap<String, Any?>()
        root.forEach { (k, v) ->
            if (k.toString() != "version" && v != null) sections[k.toString()] = v
        }
        val ordered = SECTION_ORDER.filter { it in sections } +
            sections.keys.filterNot { it in SECTION_ORDER }
        val sb = StringBuilder()
        // The version renders through the same integral-double rule the
        // writer applies (a parsed 2.0 renders as `2`, the wire's own
        // shape — the chain's re-stamp carries a Double).
        sb.append("{\"version\":")
        renderRawTo(root["version"] ?: ProjectMigrations.CURRENT_VERSION.toDouble(), sb)
        for (name in ordered) {
            sb.append(',').append(quote(name)).append(':')
            renderRawTo(sections[name], sb)
        }
        sb.append('}')
        return sb.toString()
    }

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
     * The version a rendered file is stamped with, the §6.5 rule completed
     * for the real v1->v2 step (F1-3):
     *   - a file at/above CURRENT_VERSION stamps its OWN version (never
     *     dragged down — a newer build's file survives this build's
     *     section writes byte-stamped);
     *   - a file BELOW CURRENT stamps its OWN version too: the v1 file on
     *     disk is still v1 until [ProjectFile.open] — the single sanctioned
     *     migrator — runs the chain. A store write must not leap the stamp
     *     to 2 over still-flat-v1 sections (that would make the NEXT open
     *     skip the migration: the §6.4 gate is the only version mover).
     * Unversioned/invalid files stamp 1 (the pre-version legacy shape).
     */
    private fun stampFor(root: Map<*, *>?): Int =
        if (root == null) {
            ProjectMigrations.CURRENT_VERSION // a fresh file: this build's own sections
        } else {
            ProjectMigrations.versionOf(root) // the file's own, whatever it is
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
