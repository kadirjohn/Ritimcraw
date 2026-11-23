package com.ritim.ritimcraw.data

/**
 * The portable grade-preset FILE (şartname COL-18: "özel preset içe/dışa
 * aktarma. Preset şeması sürümlü. Eski Kelvin/tint modelinden geçişte
 * değişikliği açıklayan migration uygula"). Landed 2026-11-23 as a pure
 * codec + [PresetStore.importAll]; the SAF picker that writes/reads the
 * file is UI wiring left for the editor owner (the export is one
 * `render(store.all())` to a `CreateDocument` uri, the import one
 * `parse(text)` + `store.importAll(ok.presets)`).
 *
 * Shape (UTF-8 JSON, one document, `.ritimpreset.json` suggested):
 *
 *   { "format": "ritimcraw-grade-preset",
 *     "formatVersion": 1,
 *     "presets": [ { "name": "Teal & Orange",
 *                    "grade": { ...GradeStore's grade object... } } ] }
 *
 * DESIGN RULES
 *  - ONE grade wire format: the grade object is rendered by
 *    [GradeStore.renderGrade] and parsed by [GradeStore.parseGrades] — the
 *    same path per-clip grades and stored presets use, so the per-field
 *    neutral fallbacks (an older file with fewer fields loads with the
 *    missing stages neutral) apply here too. No second schema to drift.
 *  - Ids are NOT exported: a preset id is a persistence key of ONE
 *    project.json; the importing store mints fresh ids, so importing the
 *    same file twice can never collide with (or overwrite) a local preset.
 *  - VERSIONED, and honest about it: [FORMAT_VERSION] is the newest this
 *    build writes. A file with a NEWER formatVersion is REFUSED
 *    ([Failure.Reason.TOO_NEW]) rather than half-read — a future field
 *    silently dropped would change the look without telling the user.
 *    Older versions run through [migrate], which returns the human-readable
 *    notes of every change it made (the "değişikliği açıklayan migration"
 *    clause): today version 1 is the only version, so the chain is the
 *    identity, and the hook is where a future WB-model change (e.g. a
 *    Kelvin/tint re-parameterization) records its explanation.
 *  - A bare project.json (its "presets" section) is accepted as a legacy
 *    source too (formatVersion absent): sharing a whole project file is the
 *    pre-format way a user could have moved presets, so it imports, with a
 *    note saying so.
 *  - LUT references travel BY ID (GradeK.lutId, the fingerprint id): the
 *    cube itself is not embedded. [Ok.lutIdsReferenced] lists them so the
 *    UI can warn "import the LUT too" — PresetStore's existing contract
 *    already renders a missing LUT as no-LUT and restores the look once the
 *    same cube is imported (deterministic fingerprint id).
 */
object PresetFile {

    /** The format marker every file carries. */
    const val FORMAT = "ritimcraw-grade-preset"

    /** The newest format version this build writes and fully reads. */
    const val FORMAT_VERSION = 1

    /** A preset as it travels in a file: no id (the importer mints one). */
    data class Portable(val name: String, val grade: GradeK)

    sealed class Result

    /** A readable file: the presets, the migration notes, the LUT ids to warn about. */
    data class Ok(
        val presets: List<Portable>,
        /** Human-readable notes about every migration/skip applied (empty = verbatim). */
        val notes: List<String>,
        /** Distinct LUT ids the imported grades reference (the cube is not embedded). */
        val lutIdsReferenced: List<String>,
    ) : Result()

    /** An unreadable file, with the honest reason. */
    data class Failure(val reason: Reason, val detail: String) : Result() {
        enum class Reason {
            /** Not JSON, or not a JSON object. */
            MALFORMED,
            /** JSON, but neither a preset file nor a project.json with presets. */
            NOT_A_PRESET_FILE,
            /** A formatVersion newer than [FORMAT_VERSION] — refused, never half-read. */
            TOO_NEW,
            /** A valid file that holds no usable preset. */
            EMPTY,
        }
    }

    /** Renders [presets] as a portable preset file (ids dropped, order kept). */
    fun render(presets: List<GradePreset>): String {
        val sb = StringBuilder()
        sb.append("{\"format\":").append(ProjectJson.quote(FORMAT))
            .append(",\"formatVersion\":").append(FORMAT_VERSION)
            .append(",\"presets\":[")
        presets.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(ProjectJson.quote(p.name))
                .append(",\"grade\":").append(GradeStore.renderGrade(p.grade))
                .append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Parses a preset file (or a legacy project.json); never throws. */
    fun parse(text: String): Result {
        val root = try {
            val p = JsonParser(text)
            val v = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            v
        } catch (e: IllegalArgumentException) {
            return Failure(Failure.Reason.MALFORMED, e.message ?: "not JSON")
        } catch (e: IndexOutOfBoundsException) {
            return Failure(Failure.Reason.MALFORMED, "truncated JSON")
        }
        if (root !is Map<*, *>) {
            return Failure(Failure.Reason.MALFORMED, "root is not an object")
        }

        val notes = ArrayList<String>()
        val format = root["format"] as? String
        val version: Int
        if (format == null) {
            // Legacy source: a whole project.json carrying a presets section.
            if (root["presets"] !is List<*>) {
                return Failure(Failure.Reason.NOT_A_PRESET_FILE, "no format marker and no presets section")
            }
            version = 1
            notes.add("Imported from a project file (no preset-file header).")
        } else {
            if (format != FORMAT) {
                return Failure(Failure.Reason.NOT_A_PRESET_FILE, "format '$format'")
            }
            val v = (root["formatVersion"] as? Double)?.toInt()
                ?: return Failure(Failure.Reason.NOT_A_PRESET_FILE, "formatVersion missing")
            if (v > FORMAT_VERSION) {
                return Failure(
                    Failure.Reason.TOO_NEW,
                    "formatVersion $v is newer than this app reads ($FORMAT_VERSION)",
                )
            }
            if (v < 1) return Failure(Failure.Reason.NOT_A_PRESET_FILE, "formatVersion $v")
            version = v
        }

        val out = ArrayList<Portable>()
        val arr = root["presets"] as? List<*> ?: emptyList<Any?>()
        arr.forEachIndexed { index, item ->
            val m = item as? Map<*, *>
            val name = (m?.get("name") as? String)?.takeIf { it.isNotBlank() }
            @Suppress("UNCHECKED_CAST")
            val gradeMap = m?.get("grade") as? Map<String, Any?>
            if (name == null || gradeMap == null) {
                notes.add("Skipped entry ${index + 1}: missing name or grade.")
                return@forEachIndexed
            }
            val migrated = migrate(version, gradeMap, notes, name)
            val grade = try {
                GradeStore.parseGrades(
                    "{\"grades\":{\"entry\":${ProjectJson.renderRaw(migrated)}}}"
                )["entry"]
            } catch (e: IllegalArgumentException) {
                null
            }
            if (grade == null) {
                notes.add("Skipped '$name': unreadable grade.")
                return@forEachIndexed
            }
            out.add(Portable(name, grade))
        }
        if (out.isEmpty()) {
            return Failure(Failure.Reason.EMPTY, notes.joinToString(" ").ifEmpty { "no presets" })
        }
        val luts = out.mapNotNull { it.grade.lutId }.distinct()
        return Ok(out, notes, luts)
    }

    /**
     * The migration chain from [fromVersion] up to [FORMAT_VERSION] on one
     * raw grade object. Each step appends a note naming the preset and the
     * change. Version 1 is the only version today: the identity. A future
     * step (say, version 2 re-parameterizing tempK/tint) converts the old
     * fields here AND explains the conversion in [notes] — never silently.
     */
    internal fun migrate(
        fromVersion: Int,
        grade: Map<String, Any?>,
        notes: MutableList<String>,
        presetName: String,
    ): Map<String, Any?> {
        val g = grade
        var v = fromVersion
        while (v < FORMAT_VERSION) {
            // No step exists yet below version 1; this loop is the hook.
            notes.add("'$presetName': migrated from preset format $v.")
            v += 1
        }
        return g
    }
}
