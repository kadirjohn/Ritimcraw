package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson
import com.ritim.ritimcraw.data.SourceKind
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Persists the per-source metadata records (IN-13 Stage A) into
 * `files/project.json`'s "metadata" section — the SEVENTH section
 * alongside grades/sources/exports/luts/timeline/audio, following the
 * same store pattern as AudioStore: every save re-renders only the
 * "metadata" section and preserves the other six untouched via
 * [ProjectJson.withSection] (unknown sections keep their encounter
 * order), so stores sharing project.json never clobber each other.
 *
 * SECTION SHAPE (schema v1; the section is an OBJECT keyed by sourceId):
 *
 *   { "version": 1,
 *     "src-abc": { "kind": "MCRAW",
 *                  "durationUs": { "v": 966657, "s": "KNOWN" },
 *                  "fpsMeasured": { "v": 30.0, "s": "ESTIMATED",
 *                                   "b": "(frames - 1) / ..." },
 *                  "dolbyVision": "NOT_DV", ... },
 *     "src-def": { ... } }
 *
 * OMIT-UNKNOWN RENDERING: a field whose status is UNKNOWN renders
 * NOTHING — the honest record's unknowns are the ABSENT default, so
 * files stay minimal and a pre-IN-13 project.json stays
 * byte-identical. An ESTIMATED field always renders its basis ("b");
 * the Field constructor already enforces that a basis exists.
 *
 * JOIN: records key by sourceId — the SAME id as the sources section's
 * MediaSource.id (fingerprint-derived), so the record joins the
 * library row without a second index. The store never regenerates an
 * id and never invents a record: sources without a record simply have
 * none (the row degrades to the existing format line).
 *
 * MIGRATION STANCE (the AudioStore/TimelineStore precedent). The
 * section carries a version field (currently 1). Parse is
 * FORWARD-TOLERANT: unknown FIELDS are ignored, and ABSENT fields fall
 * back to UNKNOWN. A missing or unrecognized "metadata" section loads
 * the DEFAULT empty map (a fresh project has no records), and a
 * corrupt section (unparseable JSON) throws IllegalArgumentException
 * from the parse helpers — the store's load() catches it and surfaces
 * the default, keeping the app usable (the GradeStore corrupt-store
 * rule). The store never rewrites data it did not write.
 *
 * ATOMICITY: writes go to a sibling `.tmp` file then rename over the
 * target (the GradeStore/AudioStore atomic-write approach — a crash
 * mid-write never corrupts the previous file; the rename fallback
 * covers cross-filesystem renames).
 *
 * TOLERANCE: a record row that fails its model invariant (an unknown
 * status string, an ESTIMATED field without a basis, a null value with
 * a non-UNKNOWN status) is SKIPPED, not fatal — one bad row never
 * takes the project down (the per-row tolerance AudioStore uses).
 */
class MetadataStore(private val file: File) {

    private val lock = Any()

    /** Loads the records; default empty map when absent or corrupt. */
    fun load(): Map<String, MetadataRecord> = synchronized(lock) {
        loadOrNull() ?: emptyMap()
    }

    /** The one record for [sourceId], or null when none is persisted. */
    fun get(sourceId: String): MetadataRecord? = load()[sourceId]

    /**
     * Upserts one record and persists immediately (every save persists —
     * the GradeStore rule). The caller owns honesty: what it passes is
     * what renders.
     */
    fun put(record: MetadataRecord) = synchronized(lock) {
        val records = loadOrNull() ?: emptyMap()
        write(records + (record.sourceId to record))
    }

    /** Drops one source's record (the remove flow keeps project.json tidy). */
    fun remove(sourceId: String) = synchronized(lock) {
        val records = loadOrNull() ?: emptyMap()
        if (sourceId in records) write(records - sourceId)
    }

    /** Replaces the whole persisted set (the record-extraction pass). */
    fun replaceAll(records: Map<String, MetadataRecord>) = synchronized(lock) {
        write(records)
    }

    private fun loadOrNull(): Map<String, MetadataRecord>? {
        if (!file.exists()) return null
        return try {
            parseMetadata(file.readText(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable; the records load as
            // empty (the raw text survives on disk untouched).
            null
        }
    }

    private fun write(records: Map<String, MetadataRecord>) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "metadata",
            renderedSection = renderMetadata(records),
            version = SCHEMA_VERSION,
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
        /** Section schema version (see the class KDoc, MIGRATION STANCE). */
        const val SCHEMA_VERSION = 1

        // ---- writer ----

        /**
         * Renders the whole project.json with the given records,
         * preserving every other section already in [existingText]
         * (see [ProjectJson.withSection]).
         */
        internal fun mergeInto(
            existingText: String?,
            records: Map<String, MetadataRecord>,
        ): String = ProjectJson.withSection(
            existingText = existingText,
            section = "metadata",
            renderedSection = renderMetadata(records),
            version = SCHEMA_VERSION,
        )

        internal fun renderMetadata(records: Map<String, MetadataRecord>): String {
            val sb = StringBuilder()
            sb.append("{\"version\":").append(SCHEMA_VERSION)
            // Deterministic order: the map's iteration order is stable
            // for LinkedHashMap inputs (the store's own maps), and the
            // id sort keeps rendered files diff-stable regardless.
            for (id in records.keys.sorted()) {
                sb.append(',').append(ProjectJson.quote(id)).append(':')
                    .append(renderRecord(records.getValue(id)))
            }
            sb.append('}')
            return sb.toString()
        }

        private fun renderRecord(r: MetadataRecord): String {
            val sb = StringBuilder()
            sb.append("{\"kind\":\"").append(r.kind.name).append('"')
            // The frame-count estimate flag renders only when set; the
            // flag's invariant (an ESTIMATED count) is enforced by the
            // record itself.
            if (r.frameCountEstimated) sb.append(",\"frameCountEstimated\":true")
            renderLongField(sb, "durationUs", r.durationUs)
            renderIntField(sb, "frameCount", r.frameCount)
            renderDoubleField(sb, "fpsNominal", r.fpsNominal)
            renderDoubleField(sb, "fpsMeasured", r.fpsMeasured)
            renderStringField(sb, "camera", r.camera)
            renderStringField(sb, "lens", r.lens)
            renderStringField(sb, "resolution", r.resolution)
            renderIntField(sb, "bitDepth", r.bitDepth)
            renderStringField(sb, "primaries", r.primaries)
            renderStringField(sb, "transfer", r.transfer)
            renderStringField(sb, "matrix", r.matrix)
            renderStringField(sb, "range", r.range)
            renderAudioField(sb, r.audio)
            renderLongField(sb, "fileSize", r.fileSize)
            renderStringField(sb, "fileIdentity", r.fileIdentity)
            sb.append(",\"dolbyVision\":\"").append(r.dolbyVision.name).append('"')
            sb.append(",\"logProfile\":\"").append(r.logProfile.name).append('"')
            sb.append(",\"hdrOutputSupport\":\"").append(r.hdrOutputSupport.name).append('"')
            sb.append('}')
            return sb.toString()
        }

        private fun renderLongField(sb: StringBuilder, name: String, f: Field<Long>) {
            if (f.status == FieldStatus.UNKNOWN) return
            sb.append(',').append(ProjectJson.quote(name)).append(":{\"v\":")
                .append(f.value)
            if (f.status == FieldStatus.ESTIMATED) {
                sb.append(",\"s\":\"ESTIMATED\",\"b\":")
                    .append(ProjectJson.quote(f.basis!!))
            } else {
                sb.append(",\"s\":\"KNOWN\"")
            }
            sb.append('}')
        }

        private fun renderIntField(sb: StringBuilder, name: String, f: Field<Int>) {
            if (f.status == FieldStatus.UNKNOWN) return
            sb.append(',').append(ProjectJson.quote(name)).append(":{\"v\":")
                .append(f.value)
            if (f.status == FieldStatus.ESTIMATED) {
                sb.append(",\"s\":\"ESTIMATED\",\"b\":")
                    .append(ProjectJson.quote(f.basis!!))
            } else {
                sb.append(",\"s\":\"KNOWN\"")
            }
            sb.append('}')
        }

        private fun renderDoubleField(sb: StringBuilder, name: String, f: Field<Double>) {
            if (f.status == FieldStatus.UNKNOWN) return
            sb.append(',').append(ProjectJson.quote(name)).append(":{\"v\":")
                .append(f.value)
            if (f.status == FieldStatus.ESTIMATED) {
                sb.append(",\"s\":\"ESTIMATED\",\"b\":")
                    .append(ProjectJson.quote(f.basis!!))
            } else {
                sb.append(",\"s\":\"KNOWN\"")
            }
            sb.append('}')
        }

        private fun renderStringField(sb: StringBuilder, name: String, f: Field<String>) {
            if (f.status == FieldStatus.UNKNOWN) return
            sb.append(',').append(ProjectJson.quote(name)).append(":{\"v\":")
                .append(ProjectJson.quote(f.value!!))
            if (f.status == FieldStatus.ESTIMATED) {
                sb.append(",\"s\":\"ESTIMATED\",\"b\":")
                    .append(ProjectJson.quote(f.basis!!))
            } else {
                sb.append(",\"s\":\"KNOWN\"")
            }
            sb.append('}')
        }

        private fun renderAudioField(sb: StringBuilder, f: Field<AudioSummary>) {
            if (f.status == FieldStatus.UNKNOWN) return
            val a = f.value!!
            sb.append(",\"audio\":{\"present\":").append(a.present)
            if (a.codec.isNotBlank()) {
                sb.append(",\"codec\":").append(ProjectJson.quote(a.codec))
            }
            if (a.channels > 0) sb.append(",\"channels\":").append(a.channels)
            if (a.sampleRateHz > 0) {
                sb.append(",\"sampleRateHz\":").append(a.sampleRateHz)
            }
            sb.append('}')
        }

        // ---- reader ----

        /**
         * Parses the "metadata" section of a project.json. Throws
         * IllegalArgumentException on malformed JSON; returns the
         * DEFAULT empty map when the section is missing (a fresh
         * project has no records).
         */
        internal fun parseMetadata(text: String): Map<String, MetadataRecord> {
            val p = JsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val section = root["metadata"] as? Map<*, *>
                ?: return emptyMap() // no section yet: fresh project
            val records = LinkedHashMap<String, MetadataRecord>()
            for ((key, value) in section) {
                val id = key as? String ?: continue
                if (id.isBlank() || id == "version") continue
                val m = value as? Map<*, *> ?: continue
                val record = parseRecord(id, m) ?: continue // bad row: skip
                records[id] = record
            }
            return records
        }

        private fun parseRecord(id: String, m: Map<*, *>): MetadataRecord? {
            val kind = enumValue<SourceKind>(m["kind"]) ?: return null
            val estimated = m["frameCountEstimated"] as? Boolean ?: false
            return try {
                MetadataRecord(
                    sourceId = id,
                    kind = kind,
                    durationUs = longField(m["durationUs"]),
                    frameCount = intField(m["frameCount"]),
                    fpsNominal = doubleField(m["fpsNominal"]),
                    fpsMeasured = doubleField(m["fpsMeasured"]),
                    camera = stringField(m["camera"]),
                    lens = stringField(m["lens"]),
                    resolution = stringField(m["resolution"]),
                    bitDepth = intField(m["bitDepth"]),
                    primaries = stringField(m["primaries"]),
                    transfer = stringField(m["transfer"]),
                    matrix = stringField(m["matrix"]),
                    range = stringField(m["range"]),
                    audio = audioField(m["audio"]),
                    fileSize = longField(m["fileSize"]),
                    fileIdentity = stringField(m["fileIdentity"]),
                    dolbyVision = enumValue<Diagnosis>(m["dolbyVision"]) ?: return null,
                    logProfile = enumValue<LogDiagnosis>(m["logProfile"]) ?: return null,
                    hdrOutputSupport = enumValue<Capability>(m["hdrOutputSupport"]) ?: return null,
                    frameCountEstimated = estimated,
                )
            } catch (e: IllegalArgumentException) {
                null // model invariant violated: skip the row
            }
        }

        /**
         * Reads an enum name from the row; null when the key is absent or
         * the name is not one of the enum's (a bad row, skipped upstream).
         */
        private inline fun <reified E : Enum<E>> enumValue(v: Any?): E? =
            (v as? String)?.let { name -> runCatching { enumValueOf<E>(name) }.getOrNull() }

        private fun longField(v: Any?): Field<Long> {
            val m = v as? Map<*, *> ?: return Field.unknown()
            val value = (m["v"] as? Double)?.toLong() ?: return Field.unknown()
            return buildField(m, value)
        }

        private fun intField(v: Any?): Field<Int> {
            val m = v as? Map<*, *> ?: return Field.unknown()
            val value = (m["v"] as? Double)?.toInt() ?: return Field.unknown()
            return buildField(m, value)
        }

        private fun doubleField(v: Any?): Field<Double> {
            val m = v as? Map<*, *> ?: return Field.unknown()
            val value = m["v"] as? Double ?: return Field.unknown()
            return buildField(m, value)
        }

        private fun stringField(v: Any?): Field<String> {
            val m = v as? Map<*, *> ?: return Field.unknown()
            val value = m["v"] as? String ?: return Field.unknown()
            return buildField(m, value)
        }

        private fun <T : Any> buildField(m: Map<*, *>, value: T): Field<T> =
            when (m["s"] as? String ?: "KNOWN") {
                "KNOWN" -> Field.known(value)
                "ESTIMATED" -> {
                    val basis = m["b"] as? String
                    if (basis.isNullOrBlank()) {
                        // An estimate without a basis is a guess — refuse
                        // to reconstruct it (the Field would reject it
                        // anyway; treat the row as unread).
                        throw IllegalArgumentException("estimate without basis")
                    }
                    Field.estimated(value, basis)
                }
                else -> Field.unknown()
            }

        private fun audioField(v: Any?): Field<AudioSummary> {
            val m = v as? Map<*, *> ?: return Field.unknown()
            val present = m["present"] as? Boolean ?: return Field.unknown()
            return Field.known(
                AudioSummary(
                    present = present,
                    codec = m["codec"] as? String ?: "",
                    channels = (m["channels"] as? Double)?.toInt() ?: 0,
                    sampleRateHz = (m["sampleRateHz"] as? Double)?.toInt() ?: 0,
                ),
            )
        }
    }
}
