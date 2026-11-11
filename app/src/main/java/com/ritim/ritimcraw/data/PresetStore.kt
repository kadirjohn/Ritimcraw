package com.ritim.ritimcraw.data

import java.io.File
import java.nio.charset.StandardCharsets

/**
 * One named grade preset (owner request 2026-11-11 section 4): a
 * user-chosen [name] plus the [grade] snapshot it stands for. The name is
 * the user's own text — it is stored and displayed verbatim and is never
 * localized (the UI labels around it are; the name itself is data, like a
 * clip's file name).
 *
 * [id] is the stable persistence key. It is generated once at save time
 * (time + a counter, see [PresetStore.put]) and never changes for the
 * life of the preset, so renaming is impossible-by-design (a "rename" is
 * delete + re-save) and the UI can key rows by id while showing the name.
 */
data class GradePreset(
    val id: String,
    val name: String,
    val grade: GradeK,
)

/**
 * Named grade presets, persisted in the "presets" section of
 * `files/project.json` (schema 1, same file as the other stores;
 * merge-aware writes preserve "grades"/"sources"/"exports"/"luts" — see
 * [ProjectJson]).
 *
 * Shape:
 *   { "version": 1,
 *     "grades": { ... },          // GradeStore
 *     "sources": [ ... ],         // SourceRepository
 *     "exports": [ ... ],         // ExportStore
 *     "luts":    [ ... ],         // LutStore
 *     "presets": [ { "id": "pres-...",
 *                     "name": "Teal & Orange",
 *                     "grade": { ...same fields as a grades entry... } } ] }
 *
 * The grade inside a preset is rendered by GradeStore's own renderer
 * ([GradeStore.renderGrade] — the same function a per-clip grade renders
 * through), so a preset's grade is byte-identical to the same grade saved
 * per clip — one render path, one parse path (GradeStore.parseGrades), no
 * second schema to drift.
 *
 * Old files without a "presets" section load as an empty list (the
 * pre-preset build's exact behavior); a corrupt file falls back to empty
 * the same way GradeStore does (the app stays usable).
 *
 * The preset's grade travels with its LUT selection (GradeK.lutId):
 * applying a preset to a clip whose LUT library lacks that id keeps the
 * id verbatim — the grade renders as no-LUT until the cube is imported
 * again (LutStore.pathFor returns null for a missing file, which the
 * native bridge treats as no LUT), and re-importing the same cube
 * restores the look because the fingerprint id is deterministic. It
 * never crashes and never silently rewrites the user's selection.
 *
 * Thread-safety mirrors GradeStore: synchronized on an internal lock;
 * writes are atomic (tmp + rename) so a crash mid-write never corrupts
 * the previous file.
 */
class PresetStore(private val file: File) {

    private val lock = Any()

    /** All presets, oldest-saved first (insertion order preserved). */
    fun all(): List<GradePreset> = synchronized(lock) { load() }

    /** One preset by id, or null when it does not exist. */
    fun get(id: String): GradePreset? = synchronized(lock) { load().find { it.id == id } }

    /**
     * Persists a preset immediately. The id is minted here (time + counter)
     * and checked against the already-loaded list, so it is unique on disk
     * even across process restarts (the counter resets, the clock does
     * not); the same id can never appear twice.
     */
    fun put(name: String, grade: GradeK): GradePreset {
        synchronized(lock) {
            val presets = load().toMutableList()
            var id = newId()
            while (presets.any { it.id == id }) id = newId()
            val preset = GradePreset(id = id, name = name, grade = grade)
            presets.add(preset)
            write(presets)
            return preset
        }
    }

    /** Removes a preset by id; a missing id is a no-op. */
    fun remove(id: String) {
        synchronized(lock) {
            val presets = load()
            if (presets.any { it.id == id }) {
                write(presets.filterNot { it.id == id })
            }
        }
    }

    // ---- persistence (project.json, schema 1) ----

    private fun load(): List<GradePreset> {
        if (!file.exists()) return emptyList()
        return try {
            parsePresets(file.readText(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable; presets list empty.
            emptyList()
        }
    }

    private fun write(presets: List<GradePreset>) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "presets",
            renderedSection = renderPresetsArray(presets),
            version = GradeK.SCHEMA_VERSION,
        )
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(merged, StandardCharsets.UTF_8)
        if (!tmp.renameTo(file)) {
            // Rename can fail across filesystems; fall back to copy+delete.
            file.writeText(merged, StandardCharsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private val idCounter = java.util.concurrent.atomic.AtomicLong()

        /** Mints a preset id: time-based, unique within a process. */
        internal fun newId(): String =
            "pres-" + System.nanoTime() + "-" + idCounter.incrementAndGet()

        /**
         * Renders the "presets" section:
         * [ { "id": ..., "name": ..., "grade": { ... } }, ... ].
         *
         * The grade object is rendered by GradeStore's own renderer on a
         * single-entry map, so preset grades and per-clip grades share one
         * wire format and one parse path (a preset saved from a clip's
         * grade round-trips through GradeStore.parseGrades identically).
         */
        internal fun renderPresetsArray(presets: List<GradePreset>): String {
            val sb = StringBuilder()
            sb.append('[')
            presets.forEachIndexed { i, p ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(p.id))
                    .append(",\"name\":").append(ProjectJson.quote(p.name))
                    .append(",\"grade\":")
                    .append(GradeStore.renderGrade(p.grade))
                    .append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        /**
         * Parses the "presets" section. Entries with a missing id, name,
         * or grade object are skipped (never crash the app over one bad
         * row); the grade itself parses through GradeStore's per-field
         * fallbacks, so a preset saved by an older build (fewer grade
         * fields) loads with the missing fields neutral — the same
         * contract per-clip grades have.
         */
        internal fun parsePresets(text: String): List<GradePreset> {
            val p = JsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val arr = root["presets"] as? List<*> ?: return emptyList()
            val out = ArrayList<GradePreset>()
            for (item in arr) {
                val m = item as? Map<*, *> ?: continue
                val id = m["id"] as? String ?: continue
                val name = m["name"] as? String ?: continue
                @Suppress("UNCHECKED_CAST")
                val gradeMap = m["grade"] as? Map<String, Any?> ?: continue
                val grades = GradeStore.parseGrades(
                    "{\"grades\":{\"entry\":${renderRawGrade(gradeMap)}}}"
                )
                val grade = grades["entry"] ?: continue
                out.add(GradePreset(id = id, name = name, grade = grade))
            }
            return out
        }

        /** Re-renders a parsed grade object without re-interpreting it. */
        private fun renderRawGrade(map: Map<String, Any?>): String =
            ProjectJson.renderRaw(map)
    }
}
