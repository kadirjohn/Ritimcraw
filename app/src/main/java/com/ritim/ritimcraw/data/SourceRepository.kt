package com.ritim.ritimcraw.data

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

enum class SourceKind { MCRAW, ENCODED }

enum class Access { OK, LOST }

/**
 * One imported clip. URIs are Strings (not android.net.Uri) so the model and
 * the repository logic stay testable on a plain JVM; the UI converts at the
 * edge (see [UriResolver.toAndroidUri]).
 */
data class MediaSource(
    val id: String,
    val uri: String,
    val fingerprint: String,
    val kind: SourceKind,
    val access: Access,
    /**
     * The clip's real file name (owner request 2026-11-11 §2b), from
     * OpenableColumns.DISPLAY_NAME at import. Blank when the resolver could
     * not name it (or the record predates the field) — display falls back to
     * [displayOrId] then, and the first open backfills it (see
     * [SourceRepository.backfillDisplayName]). The id stays
     * fingerprint-derived either way.
     */
    val displayName: String = "",
    /**
     * Clip stats for the format line (Format.formatClipLine). Zero/blank
     * until the engine pass probes the clip on device; the Library row
     * degrades to the kind line while they are unknown.
     */
    val width: Int = 0,
    val height: Int = 0,
    val frameCount: Int = 0,
    val cfa: String = "",
    /**
     * The clip's duration from its real frame timestamps (owner request
     * 2026-11-11 section 1): last pts minus first pts. 0 when unknown (or
     * a single-frame clip — the row hides the duration part then). Filled
     * by the thumbnail/stats generation pass; omitted from project.json
     * while 0 so schema-1 files stay byte-identical.
     */
    val durationUs: Long = 0L,
) {
    /** True when the engine pass has filled in the format-line stats. */
    val hasStats: Boolean
        get() = (width > 0 && height > 0) || frameCount > 0 ||
            cfa.isNotBlank() || durationUs > 0L

    /**
     * What the UI shows as the clip's name (owner request §2b): the real
     * file name when it is known, the id when it is not — never a blank.
     */
    val displayOrId: String
        get() = if (displayName.isBlank()) id else displayName
}

/**
 * The storage operations the repository needs from the platform. The
 * production implementation wraps a ContentResolver; tests inject a fake.
 */
interface UriResolver {
    /** Opens the content for reading; throws SecurityException when access was revoked. */
    fun open(uri: String): InputStream

    /** File size in bytes, or -1 when unknown. */
    fun size(uri: String): Long

    /**
     * The document's real file name (owner request 2026-11-11 §2b), from
     * OpenableColumns.DISPLAY_NAME. Null when unknown — the repository
     * falls back to the id and backfills on the next import/open.
     */
    fun displayName(uri: String): String?
}

/**
 * Library of imported clips, persisted in `files/project.json` (schema 1,
 * same file the GradeStore lives in; kept in a distinct JSON section so the
 * two stores can split later without a migration).
 *
 * The clip id is derived from the fingerprint (SHA-256 of the first 1 MiB
 * plus the file size), so re-importing the same file keeps the same id and
 * its grade (sprint plan Task 4: reimport_same_file_keeps_id).
 *
 * refreshAccess() catches SecurityException and marks the source Access.LOST
 * without dropping it (spec IN-14 / QA-23: the row stays, with a Grant access
 * action — never a crash).
 */
class SourceRepository(
    private val resolver: UriResolver,
    private val store: File,
) {

    fun import(uri: String, kind: SourceKind = SourceKind.MCRAW): MediaSource {
        val fingerprint = fingerprintOf(uri)
        // The clip's real file name (owner request 2026-11-11 §2b): null when
        // the resolver cannot name it — the id is the display fallback.
        val name = resolver.displayName(uri)
        val sources = load().toMutableList()
        val existing = sources.find { it.fingerprint == fingerprint }
        if (existing != null) {
            // Same file: keep the id (and its grade); refresh the uri in case
            // the picker returned a different document id for the same bytes.
            // A fresh display name backfills records imported before §2b.
            val updated = existing.copy(
                uri = uri,
                access = Access.OK,
                displayName = name ?: existing.displayName,
            )
            sources[sources.indexOf(existing)] = updated
            write(sources)
            return updated
        }
        val source = MediaSource(
            id = idFrom(fingerprint),
            uri = uri,
            fingerprint = fingerprint,
            kind = kind,
            access = Access.OK,
            displayName = name ?: "",
        )
        sources.add(source)
        write(sources)
        return source
    }

    fun all(): List<MediaSource> = load()

    fun get(id: String): MediaSource? = load().find { it.id == id }

    /**
     * Backfills the display name of a record imported before §2b (owner
     * request: old records show the id until the first open fills it in).
     * Returns the updated source, or the unchanged one when the record is
     * missing, already named, or the resolver still cannot name it.
     */
    fun backfillDisplayName(id: String): MediaSource? {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = sources[index]
        if (current.displayName.isNotBlank()) return current
        val name = try {
            resolver.displayName(current.uri)
        } catch (e: Exception) {
            null
        }
        if (name.isNullOrBlank()) return current
        val updated = current.copy(displayName = name)
        sources[index] = updated
        write(sources)
        return updated
    }

    /**
     * Backfills the recorded stats of a record the generation pass probed
     * (owner request 2026-11-11 section 1: the Library row's resolution and
     * duration come from the clip's RECORDED frame data, never faked). No-op
     * when the record is missing, or when every probed value is already
     * present — a re-probe must not overwrite a filled record. Returns the
     * updated source, or the unchanged one.
     */
    fun backfillStats(
        id: String,
        width: Int,
        height: Int,
        frameCount: Int,
        cfa: String,
        durationUs: Long,
    ): MediaSource? {
        val sources = load().toMutableList()
        val index = sources.indexOfFirst { it.id == id }
        if (index < 0) return null
        val current = sources[index]
        val updated = current.copy(
            width = if (width > 0) width else current.width,
            height = if (height > 0) height else current.height,
            frameCount = if (frameCount > 0) frameCount else current.frameCount,
            cfa = if (cfa.isNotBlank()) cfa else current.cfa,
            durationUs = if (durationUs > 0L) durationUs else current.durationUs,
        )
        if (updated == current) return current
        sources[index] = updated
        write(sources)
        return updated
    }

    /**
     * Re-checks every source. A revoked permission or a moved file marks the
     * source [Access.LOST]; the id and fingerprint are kept.
     */
    fun refreshAccess(): List<MediaSource> {
        val sources = load().toMutableList()
        for (i in sources.indices) {
            val s = sources[i]
            val access = try {
                resolver.open(s.uri).use { }
                Access.OK
            } catch (e: SecurityException) {
                Access.LOST
            } catch (e: Exception) {
                // Moved, deleted or unreadable: same user-facing state.
                Access.LOST
            }
            if (access != s.access) {
                sources[i] = s.copy(access = access)
            }
        }
        write(sources)
        return sources
    }

    fun remove(id: String) {
        write(load().filterNot { it.id == id })
    }

    // ---- fingerprint ----

    /**
     * SHA-256 of the first 1 MiB plus the file size, hex. Reading only the
     * first MiB keeps import fast on multi-GB clips; the size disambiguates
     * files that share a MiB prefix.
     */
    private fun fingerprintOf(uri: String): String {
        val head = ByteArray(FINGERPRINT_BYTES)
        var read = 0
        resolver.open(uri).use { input ->
            while (read < FINGERPRINT_BYTES) {
                val n = input.read(head, read, FINGERPRINT_BYTES - read)
                if (n < 0) break
                read += n
            }
        }
        val md = MessageDigest.getInstance("SHA-256")
        md.update(head, 0, read)
        md.update(resolver.size(uri).toString().toByteArray(Charsets.UTF_8))
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun idFrom(fingerprint: String): String = fingerprint.take(ID_HEX_CHARS)

    // ---- persistence (project.json, schema 1) ----

    private fun load(): List<MediaSource> {
        if (!store.exists()) return emptyList()
        return try {
            parseSources2(store.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(sources: List<MediaSource>) {
        store.parentFile?.mkdirs()
        val text = store.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        val merged = mergeInto(text, sources)
        val tmp = File(store.parentFile, store.name + ".tmp")
        tmp.writeText(merged, Charsets.UTF_8)
        if (!tmp.renameTo(store)) {
            store.writeText(merged, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FINGERPRINT_BYTES = 1024 * 1024
        const val ID_HEX_CHARS = 16

        /**
         * Renders the whole project.json with the given sources, preserving any
         * "grades" and "exports" sections already in [existingText]
         * (GradeStore and ExportStore write the same file; see
         * [ProjectJson.withSection]).
         */
        internal fun mergeInto(existingText: String?, sources: List<MediaSource>): String {
            return ProjectJson.withSection(
                existingText = existingText,
                section = "sources",
                renderedSection = renderSourcesArray(sources),
                version = SCHEMA_VERSION,
            )
        }

        internal fun renderSourcesArray(sources: List<MediaSource>): String {
            val sb = StringBuilder()
            sb.append('[')
            sources.forEachIndexed { i, s ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(jsonString(s.id))
                    .append(",\"uri\":").append(jsonString(s.uri))
                    .append(",\"fingerprint\":").append(jsonString(s.fingerprint))
                    .append(",\"kind\":\"").append(s.kind.name).append('"')
                    .append(",\"access\":\"").append(s.access.name).append('"')
                if (s.displayName.isNotBlank()) {
                    // The real file name (owner request §2b), omitted when
                    // blank so schema-1 files written before the field stay
                    // byte-identical (the id is the display fallback).
                    sb.append(",\"displayName\":").append(jsonString(s.displayName))
                }
                if (s.hasStats) {
                    // Format-line stats (filled by the engine pass); omitted
                    // entirely while unknown so older project.json files
                    // stay byte-identical.
                    sb.append(",\"width\":").append(s.width)
                        .append(",\"height\":").append(s.height)
                        .append(",\"frameCount\":").append(s.frameCount)
                        .append(",\"cfa\":").append(jsonString(s.cfa))
                }
                if (s.durationUs > 0L) {
                    // Duration from the real timestamps (owner request
                    // 2026-11-11 section 1); omitted while 0 so schema-1
                    // files stay byte-identical.
                    sb.append(",\"durationUs\":").append(s.durationUs)
                }
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        /** Re-renders a parsed grades object without interpreting it (kept for
         *  direct callers/tests; ProjectJson.renderRaw is the shared path). */
        private fun renderRawGrades(grades: Map<*, *>): String {
            val sb = StringBuilder()
            sb.append('{')
            var first = true
            for ((k, v) in grades) {
                if (!first) sb.append(',')
                first = false
                sb.append(jsonString(k.toString())).append(':')
                val m = v as? Map<*, *>
                if (m == null) {
                    sb.append("null")
                } else {
                    sb.append('{')
                    var f2 = true
                    for ((gk, gv) in m) {
                        if (!f2) sb.append(',')
                        f2 = false
                        sb.append(jsonString(gk.toString())).append(':')
                        when (gv) {
                            null -> sb.append("null")
                            is String -> sb.append(jsonString(gv))
                            is Boolean -> sb.append(gv)
                            is Int -> sb.append(gv)
                            is Long -> sb.append(gv)
                            is Double ->
                                if (gv == gv.toLong().toDouble()) sb.append(gv.toLong())
                                else sb.append(gv)
                            else -> sb.append(jsonString(gv.toString()))
                        }
                    }
                    sb.append('}')
                }
            }
            sb.append('}')
            return sb.toString()
        }

        internal fun parseSources2(text: String): List<MediaSource> {
            val p = JsonParser2(text)
            val root = p.parseValue()
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["sources"] as? List<*> ?: return emptyList()
            return arr.mapNotNull { item ->
                val m = item as? Map<*, *> ?: return@mapNotNull null
                MediaSource(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    uri = m["uri"] as? String ?: return@mapNotNull null,
                    fingerprint = m["fingerprint"] as? String ?: "",
                    kind = runCatching { SourceKind.valueOf(m["kind"] as? String ?: "") }
                        .getOrDefault(SourceKind.MCRAW),
                    access = runCatching { Access.valueOf(m["access"] as? String ?: "") }
                        .getOrDefault(Access.OK),
                    // Absent in records written before §2b: blank display
                    // name, id shown until the first open backfills it.
                    displayName = m["displayName"] as? String ?: "",
                    // Stats are absent in schema-1 files written before the
                    // engine pass; JsonParser2 yields Double for numbers.
                    width = (m["width"] as? Double)?.toInt() ?: 0,
                    height = (m["height"] as? Double)?.toInt() ?: 0,
                    frameCount = (m["frameCount"] as? Double)?.toInt() ?: 0,
                    cfa = m["cfa"] as? String ?: "",
                    // Absent in records written before the thumbnail pass;
                    // 0 = unknown, the duration part of the row hides.
                    durationUs = (m["durationUs"] as? Double)?.toLong() ?: 0L,
                )
            }
        }

        private fun jsonString(s: String): String {
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
}

/** Minimal JSON parser for the sources section (values: object/array/string/number/bool/null). */
internal class JsonParser2(private val s: String) {
    private var i = 0

    fun atEnd(): Boolean {
        skipWs()
        return i >= s.length
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

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
        i += word.length
    }

    private fun parseObject(): Map<String, Any?> {
        i++
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

    private fun parseArray(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return out }
        while (true) {
            out.add(parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == ']' -> { i++; return out }
                else -> throw IllegalArgumentException("expected , or ] at $i")
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
