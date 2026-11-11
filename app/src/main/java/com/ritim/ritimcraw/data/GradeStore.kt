package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Per-clip grade (sprint plan Task 4). Schema version 1.
 *
 * Field order mirrors rawedit::Grade (core/include/rawedit/Develop.h). The
 * six tone/color fields added with the second control package (COL-11/12/13)
 * default to neutral (0), so a default-constructed GradeK is a no-op against
 * the ungraded pipeline — bit-identical to the pre-tone-controls look (locked
 * by the C++ golden test) — and old schema-1 files that predate the fields
 * parse them as neutral (parseGrades falls back per field).
 *
 * The three RAW metadata toggles (RAW-07/COL-09/RAW-04/RAW-06) all default
 * to true, which is exactly the pre-toggle pipeline. asShot selects the WB
 * source: true = the camera's recorded as-shot WB (the native pipeline
 * skips its manual temperature/tint stages), false = manual WB from
 * tempK/tint (which stay stored either way, so toggling back never loses
 * them). highlightReconstruction toggles the native highlight-rebuild
 * stage; keepVignetting toggles the embedded lens-shading correction. Old
 * files that predate the booleans parse them true, with asShot DERIVED from
 * temp/tint neutrality (neutral -> true, manual -> false) so old grades keep
 * their exact old look.
 *
 * The detail/tonemap fields (owner request 2026-11-10 section 3, COL-15/
 * COL-16) also default to neutral (sharpness 0, detail 0, tonemap 0 = off),
 * so old schema-1 files that predate them load with the new fields neutral
 * and keep their exact old look. tonemap: 0 = off, 1 = ACES, 2 = AgX (the
 * native stage records the selected transform's version, working space and
 * target display; see Grade in core/include/rawedit/Develop.h).
 *
 * The LUT fields (owner request 2026-11-10 section 3, COL-07) are saved
 * SEPARATELY, per the request: lutId is the selected .cube's id in the
 * project-wide LutStore ("luts" section of this same file, the library of
 * imported cubes), lutStrength is the per-clip Strength percent. lutId null
 * = no LUT selected = native stage 22 bypassed = bit-identical to the
 * pre-LUT look; old schema-1 files that predate the fields parse lutId as
 * null and lutStrength as 100, keeping their exact old look. lutStrength is
 * a UI percent 0..100 (100 = the pure table); GradeMapping.toNative maps it
 * to rawedit::Grade's 0..1 blend.
 *
 * The denoise fields (DN-01..08) also default to neutral (noiseReduction 0,
 * chromaDenoise 0 = both native stages 7/8 skipped), so a default grade is
 * bit-identical to the pre-denoise look and old schema-1 files that predate
 * the fields load with them neutral, keeping their exact old look. Both are
 * UI percents 0..100 passed through at the native scale (the core clamps).
 * The temporal NR fields (spec DN-05..DN-09) default to off/0. temporalNr is
 * the toggle; temporalStrength is the 0..100 slider. Temporal NR is a CFA
 * stage the bridge applies BEFORE developCpu (see Temporal.h), carried on
 * GradeK like any other control so the whole pipeline (store, JNI, UI)
 * moves it as one value. Old schema-1 files parse them off/0 — temporal NR
 * never turns itself on behind the user's back.
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
    val saturation: Float = 0f,
    val blackPoint: Float = 0f,
    val whitePoint: Float = 0f,
    val shadows: Float = 0f,
    val midtones: Float = 0f,
    val contrast: Float = 0f,
    val asShot: Boolean = true,
    val highlightReconstruction: Boolean = true,
    val keepVignetting: Boolean = true,
    val sharpness: Float = 0f,
    val detail: Float = 0f,
    val tonemap: Int = 0,
    /** Selected .cube id in the LutStore, or null = no LUT (stage 22 off). */
    val lutId: String? = null,
    /** Strength percent 0..100; 100 = the pure table. Irrelevant while lutId is null. */
    val lutStrength: Float = 100f,
    /** Spatial luma denoise percent 0..100 (DN-01..08); 0 = stage 8 skipped. */
    val noiseReduction: Float = 0f,
    /** Chroma denoise percent 0..100 (DN-01..08); 0 = stage 7 skipped. */
    val chromaDenoise: Float = 0f,
    val temporalNr: Boolean = false,
    val temporalStrength: Float = 0f,
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
 *                                 "tempK": 5600.0, "tint": 0.0,
 *                                 "saturation": 0.0, "blackPoint": 0.0,
 *                                 "whitePoint": 0.0, "shadows": 0.0,
 *                                 "midtones": 0.0, "contrast": 0.0,
 *                                 "asShot": true,
 *                                 "highlightReconstruction": true,
 *                                 "keepVignetting": true } },
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
         * "sources" and "exports" sections already in [existingText]
         * (see [ProjectJson.withSection]).
         */
        internal fun mergeInto(existingText: String?, grades: Map<String, GradeK>): String {
            return ProjectJson.withSection(
                existingText = existingText,
                section = "grades",
                renderedSection = renderGrades(grades),
                version = GradeK.SCHEMA_VERSION,
            )
        }

        internal fun renderGrades(grades: Map<String, GradeK>): String {
            val sb = StringBuilder()
            sb.append('{')
            var first = true
            for ((id, g) in grades) {
                if (!first) sb.append(',')
                first = false
                sb.append(quote(id)).append(':').append(renderGrade(g))
            }
            sb.append('}')
            return sb.toString()
        }

        /**
         * One grade object — the value side of a grades entry. The single
         * wire-format definition: per-clip grades render through here, and
         * PresetStore renders a preset's grade through the same function so
         * both round-trip through parseGrades identically (one schema, one
         * parse path, no drift).
         */
        internal fun renderGrade(g: GradeK): String {
            val sb = StringBuilder()
            sb.append('{')
                .append("\"version\":").append(g.version).append(',')
                .append("\"exposureEv\":").append(num(g.exposureEv)).append(',')
                .append("\"highlights\":").append(num(g.highlights)).append(',')
                .append("\"tempK\":").append(num(g.tempK)).append(',')
                .append("\"tint\":").append(num(g.tint)).append(',')
                .append("\"saturation\":").append(num(g.saturation)).append(',')
                .append("\"blackPoint\":").append(num(g.blackPoint)).append(',')
                .append("\"whitePoint\":").append(num(g.whitePoint)).append(',')
                .append("\"shadows\":").append(num(g.shadows)).append(',')
                .append("\"midtones\":").append(num(g.midtones)).append(',')
                .append("\"contrast\":").append(num(g.contrast)).append(',')
                .append("\"asShot\":").append(g.asShot).append(',')
                .append("\"highlightReconstruction\":").append(g.highlightReconstruction).append(',')
                .append("\"keepVignetting\":").append(g.keepVignetting).append(',')
                .append("\"sharpness\":").append(num(g.sharpness)).append(',')
                .append("\"detail\":").append(num(g.detail)).append(',')
                .append("\"tonemap\":").append(g.tonemap).append(',')
                // COL-07: the LUT choice and Strength are saved
                // separately, both per clip. lutId null renders as JSON
                // null (no LUT); lutStrength always renders (100 while
                // no LUT is selected — the neutral default).
                .append("\"lutId\":").append(if (g.lutId == null) "null" else quote(g.lutId))
                .append(',')
                .append("\"lutStrength\":").append(num(g.lutStrength))
                .append(',')
                // DN-01..08: the two denoise percents, both per clip.
                // 0 renders too (the neutral default = stages skipped).
                .append("\"noiseReduction\":").append(num(g.noiseReduction))
                .append(',')
                .append("\"chromaDenoise\":").append(num(g.chromaDenoise))
                .append(',')
                // DN-05..09: temporal NR toggle + strength, both per clip.
                // Off/false renders too (the neutral default = stage skipped).
                .append("\"temporalNr\":").append(g.temporalNr)
                .append(',')
                .append("\"temporalStrength\":").append(num(g.temporalStrength))
                .append('}')
            return sb.toString()
        }

        /** Re-renders a parsed sources array without interpreting it (kept for
         *  direct callers/tests; ProjectJson.renderRaw is the shared path). */
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
                // Old schema-1 files written before the tone controls carry
                // none of the six float fields: each falls back to its GradeK
                // default (neutral), which the C++ pipeline treats as a no-op.
                // Old files also predate the RAW toggles: the two stage
                // toggles fall back to true (the pre-toggle behavior), and
                // asShot is DERIVED from temp/tint neutrality so a manually
                // white-balanced old grade keeps its exact old look.
                val parsedTempK = (m["tempK"] as? Double)?.toFloat() ?: 5600f
                val parsedTint = (m["tint"] as? Double)?.toFloat() ?: 0f
                out[id] = GradeK(
                    version = (m["version"] as? Double)?.toInt() ?: GradeK.SCHEMA_VERSION,
                    exposureEv = (m["exposureEv"] as? Double)?.toFloat() ?: 0f,
                    highlights = (m["highlights"] as? Double)?.toFloat() ?: 0f,
                    tempK = parsedTempK,
                    tint = parsedTint,
                    saturation = (m["saturation"] as? Double)?.toFloat() ?: 0f,
                    blackPoint = (m["blackPoint"] as? Double)?.toFloat() ?: 0f,
                    whitePoint = (m["whitePoint"] as? Double)?.toFloat() ?: 0f,
                    shadows = (m["shadows"] as? Double)?.toFloat() ?: 0f,
                    midtones = (m["midtones"] as? Double)?.toFloat() ?: 0f,
                    contrast = (m["contrast"] as? Double)?.toFloat() ?: 0f,
                    asShot = (m["asShot"] as? Boolean)
                        ?: (parsedTempK == 5600f && parsedTint == 0f),
                    highlightReconstruction = m["highlightReconstruction"] as? Boolean ?: true,
                    keepVignetting = m["keepVignetting"] as? Boolean ?: true,
                    // Detail/tonemap fields (COL-15/COL-16) postdate older
                    // schema-1 files: fall back to neutral (0), which the
                    // native pipeline treats as a no-op — the old look is
                    // preserved exactly.
                    sharpness = (m["sharpness"] as? Double)?.toFloat() ?: 0f,
                    detail = (m["detail"] as? Double)?.toFloat() ?: 0f,
                    tonemap = (m["tonemap"] as? Double)?.toInt() ?: 0,
                    // LUT fields (COL-07) postdate older schema-1 files:
                    // absent or JSON-null lutId parses as null (no LUT =
                    // stage 22 bypassed) and absent lutStrength as 100 —
                    // the old look is preserved exactly.
                    lutId = m["lutId"] as? String,
                    lutStrength = (m["lutStrength"] as? Double)?.toFloat() ?: 100f,
                    // Denoise fields (DN-01..08) postdate older schema-1
                    // files: absent parses as 0 (both stages skipped) —
                    // the old look is preserved exactly.
                    noiseReduction = (m["noiseReduction"] as? Double)?.toFloat() ?: 0f,
                    chromaDenoise = (m["chromaDenoise"] as? Double)?.toFloat() ?: 0f,
                    // Temporal NR fields (DN-05..DN-09) postdate older
                    // schema-1 files: fall back to off/0 — temporal NR never
                    // turns itself on behind the user's back, and the old
                    // look (no temporal stage) is preserved exactly.
                    temporalNr = m["temporalNr"] as? Boolean ?: false,
                    temporalStrength = (m["temporalStrength"] as? Double)?.toFloat() ?: 0f,
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
