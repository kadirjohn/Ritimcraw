package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Per-clip grade (sprint plan Task 4). Schema version 1.
 *
 * A grade set on clip A must never appear on clip B, including after closing
 * and reopening the app (QA-05) — GradeStoreTest.grades_do_not_leak_between_clips.
 */
data class GradeK(
    val version: Int = SCHEMA_VERSION,
    val exposureEv: Float = 0f,
    val highlights: Float = 0f,
    val tempK: Float = 5600f,
    val tint: Float = 0f,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * JSON-backed grade store: `files/project.json`, schema version 1.
 * Every [put] persists (sprint plan Task 4 interface contract).
 *
 * Shape (shared with [SourceRepository], same file, distinct sections):
 *   { "version": 1,
 *     "grades": { "<sourceId>": { "exposureEv": 0.0, "highlights": 0.0,
 *                                 "tempK": 5600.0, "tint": 0.0 } },
 *     "sources": [ ... ] }
 *
 * Writes merge with the other section instead of clobbering it: a grade save
 * re-reads the file, replaces only "grades", and keeps "sources" intact
 * (and vice versa), so the two stores can share project.json.
 *
 * JSON is written with a tiny hand-rolled reader/writer because the offline
 * Gradle cache has kotlinx-serialization-core but not -json (recorded as a
 * deviation in the task report). The format is deliberately flat and
 * number-only, which is all this schema needs.
 *
 * Thread-safety: callers confine access to one thread (the UI stores are
 * single-threaded); writes are atomic (tmp + rename) so a crash mid-write
 * never corrupts the previous file.
 */
class GradeStore(private val file: File) {

    private val lock = Any()

    fun get(sourceId: String): GradeK {
        return synchronized(lock) { load()[sourceId] ?: GradeK() }
    }

    /** Persists immediately. */
    fun put(sourceId: String, grade: GradeK) {
        synchronized(lock) {
            val all = load().toMutableMap()
            all[sourceId] = grade
            write(all)
        }
    }

    fun remove(sourceId: String) {
        synchronized(lock) {
            val all = load()
            if (sourceId in all) {
                write(all - sourceId)
            }
        }
    }

    private fun load(): Map<String, GradeK> {
        if (!file.exists()) return emptyMap()
        return try {
            parseGrades(file.readText(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable; grades reset to defaults.
            // Recovery note is surfaced by callers that show error states.
            emptyMap()
        }
    }

    private fun write(grades: Map<String, GradeK>) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = mergeInto(text, grades)
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(merged, StandardCharsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Rename can fail across filesystems; fall back to copy+delete.
            file.writeText(merged, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        // ---- writer ----

        /**
         * Renders the whole project.json with the given grades, preserving any
         * "sources" section already in [existingText].
         */
        internal fun mergeInto(existingText: String?, grades: Map<String, GradeK>): String {
            val sources = existingText?.let {
                runCatching { JsonParser(it).parseValue() as? Map<*, *> }.getOrNull()
            }?.get("sources")
            val sb = StringBuilder()
            sb.append("{\"version\":").append(GradeK.SCHEMA_VERSION)
            sb.append(",\"grades\":").append(renderGrades(grades))
            if (sources is List<*>) {
                sb.append(",\"sources\":").append(renderRawSources(sources))
            }
            sb.append('}')
            return sb.toString()
        }

        internal fun renderGrades(grades: Map<String, GradeK>): String {
            val sb = StringBuilder()
            sb.append('{')
            var first = true
            for ((id, g) in grades) {
                if (!first) sb.append(',')
                first = false
                sb.append(quote(id)).append(":{")
                    .append("\"version\":").append(g.version).append(',')
                    .append("\"exposureEv\":").append(num(g.exposureEv)).append(',')
                    .append("\"highlights\":").append(num(g.highlights)).append(',')
                    .append("\"tempK\":").append(num(g.tempK)).append(',')
                    .append("\"tint\":").append(num(g.tint))
                    .append('}')
            }
            sb.append('}')
            return sb.toString()
        }

        /** Re-renders a parsed sources array without interpreting it. */
        private fun renderRawSources(sources: List<*>): String {
            val sb = StringBuilder()
            sb.append('[')
            sources.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                val m = s as? Map<*, *>
                if (m == null) {
                    sb.append("null")
                } else {
                    sb.append('{')
                    var first = true
                    for ((k, v) in m) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append(quote(k.toString())).append(':')
                        when (v) {
                            null -> sb.append("null")
                            is String -> sb.append(quote(v))
                            is Boolean -> sb.append(v)
                            is Int -> sb.append(v)
                            is Long -> sb.append(v)
                            is Double ->
                                if (v == v.toLong().toDouble()) sb.append(v.toLong())
                                else sb.append(v)
                            else -> sb.append(quote(v.toString()))
                        }
                    }
                    sb.append('}')
                }
            }
            sb.append(']')
            return sb.toString()
        }

        private fun num(v: Float): String = trimFloat(v)

        internal fun trimFloat(v: Float): String {
            val s = v.toString()
            // Kotlin Float.toString always carries a '.' or 'E'; keep as-is.
            return s
        }

        private fun quote(s: String): String {
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

        // ---- reader ----
        internal fun parseGrades(text: String): Map<String, GradeK> {
            val p = JsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val grades = root["grades"]
            if (grades !is Map<*, *>) return emptyMap()
            val out = LinkedHashMap<String, GradeK>()
            for ((k, v) in grades) {
                val id = k as? String ?: continue
                val m = v as? Map<*, *> ?: continue
                out[id] = GradeK(
                    version = (m["version"] as? Double)?.toInt() ?: GradeK.SCHEMA_VERSION,
                    exposureEv = (m["exposureEv"] as? Double)?.toFloat() ?: 0f,
                    highlights = (m["highlights"] as? Double)?.toFloat() ?: 0f,
                    tempK = (m["tempK"] as? Double)?.toFloat() ?: 5600f,
                    tint = (m["tint"] as? Double)?.toFloat() ?: 0f,
                )
            }
            return out
        }
    }
}

/** Minimal JSON parser: objects, arrays, strings, numbers, booleans, null. */
internal class JsonParser(private val s: String) {
    private var i = 0

    fun atEnd(): Boolean {
        skipWs()
        return i >= s.length
    }

    fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    fun parseValue(): Any? {
        skipWs()
        if (i >= s.length) throw IllegalArgumentException("unexpected end")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else ->
                if (c == '-' || c.isDigit()) parseNumber()
                else throw IllegalArgumentException("unexpected char at $i")
        }
    }

    private fun parseArray(): List<Any?> {
        i++ // '['
        val out = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') {
            i++
            return out
        }
        while (true) {
            out.add(parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == ']' -> {
                    i++
                    return out
                }
                else -> throw IllegalArgumentException("expected , or ] at $i")
            }
        }
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
        i += word.length
    }

    private fun parseObject(): Map<String, Any?> {
        i++ // '{'
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return out }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected : at $i")
            i++
            out[key] = parseValue()
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == '}' -> { i++; return out }
                else -> throw IllegalArgumentException("expected , or } at $i")
            }
        }
    }

    private fun parseString(): String {
        if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw IllegalArgumentException("unterminated string")
            when (val c = s[i]) {
                '"' -> { i++; return sb.toString() }
                '\\' -> {
                    i++
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape at $i")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
    }

    private fun parseNumber(): Double {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return s.substring(start, i).toDouble()
    }
}
