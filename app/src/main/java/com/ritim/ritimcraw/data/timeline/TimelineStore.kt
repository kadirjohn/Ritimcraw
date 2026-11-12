package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Persists the timeline editing model into `files/project.json`'s
 * "timeline" section (TL-16: save/load, atomic writes, version
 * migration) — the FIFTH section alongside grades/sources/exports/luts,
 * following the same store pattern as GradeStore/SourceRepository: every
 * save re-renders only the "timeline" section and preserves the other
 * four untouched via [ProjectJson.withSection] (unknown sections keep
 * their encounter order), so stores sharing project.json never clobber
 * each other.
 *
 * SECTION SHAPE (schema v1; the section is an OBJECT, not a list):
 *
 *   { "version": 1,
 *     "tracks": [ { "id": "v", "kind": "VIDEO", "index": 0 }, ... ],
 *     "clips":  [ { "id": "c-1a", "trackId": "v", "sourceId": "src-x",
 *                   "sourceStart": 0.0, "sourceEnd": 90.0,
 *                   "timelineStart": 0.0, "timelineEnd": 90.0,
 *                   "colorEncoding": "mi-log",        // omitted when null
 *                   "colorSpace": "bt2020",           // omitted when null
 *                   "colorTransfer": null -> omitted,
 *                   "text": "Title",                 // omitted when blank
 *                   "linkedAudioId": "a-2b" } ],      // omitted when null
 *     "output": { "width": 1920, "height": 1080, "fps": 30,
 *                 "orientationDeg": 0, "colorSpace": "rec709",
 *                 "transfer": "sdr-video", "hdr": false },
 *     "projectEnd": 300.0,          // omitted when null
 *     "backgroundDuration": 150.0 }  // omitted when null
 *
 * Frame numbers render as Doubles (the model's timebase), integral
 * values without a trailing ".0" (ProjectJson's number convention).
 *
 * MIGRATION STANCE (TL-16 "sürüm migration'ı"). The section carries a
 * version field (currently 1). Parse is FORWARD-TOLERANT: unknown FIELDS
 * are ignored (a newer build's file still loads what this build knows),
 * and ABSENT fields fall back to their defaults (an older v1 file
 * predating a field keeps its old meaning — the GradeStore per-field
 * fallback precedent). A FUTURE v2 will bump the version and migrate
 * in the parse path; v1 parsing never rewrites data it did not write.
 * A missing or unrecognized "timeline" section loads as the DEFAULT
 * empty project (a fresh project starts empty), and a corrupt section
 * (unparseable JSON) throws IllegalArgumentException from the parse
 * helpers — the store's load() catches it and surfaces the default,
 * keeping the app usable (the GradeStore corrupt-store rule).
 *
 * ATOMICITY: writes go to a sibling `.tmp` file then rename over the
 * target (the GradeStore/ExportStore atomic-write approach — a crash
 * mid-write never corrupts the previous file; the rename fallback
 * covers cross-filesystem renames).
 *
 * IDs: persisted verbatim — the model's ID STABILITY rule means the
 * store never regenerates an id; what was saved is what reloads.
 */
class TimelineStore(private val file: File) {

    private val lock = Any()

    /** Loads the timeline section; default empty project when absent. */
    fun load(): TimelineProject = synchronized(lock) { loadOrNull() ?: defaultProject() }

    /** Persists immediately (every save persists — the GradeStore rule). */
    fun save(project: TimelineProject) = synchronized(lock) { write(project) }

    private fun loadOrNull(): TimelineProject? {
        if (!file.exists()) return null
        return try {
            parseTimeline(file.readText(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable; the project loads as
            // empty (the raw text survives on disk untouched).
            null
        }
    }

    private fun write(project: TimelineProject) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "timeline",
            renderedSection = renderTimeline(project),
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

        internal fun defaultProject(): TimelineProject = TimelineProject(
            tracks = listOf(
                TimelineTrack("v", TrackKind.VIDEO, 0),
                TimelineTrack("a", TrackKind.AUDIO, 1),
            ),
        )

        // ---- writer ----

        /**
         * Renders the whole project.json with the given timeline, preserving
         * every other section already in [existingText] (see
         * [ProjectJson.withSection]).
         */
        internal fun mergeInto(existingText: String?, project: TimelineProject): String =
            ProjectJson.withSection(
                existingText = existingText,
                section = "timeline",
                renderedSection = renderTimeline(project),
                version = SCHEMA_VERSION,
            )

        internal fun renderTimeline(p: TimelineProject): String {
            val sb = StringBuilder()
            sb.append("{\"version\":").append(SCHEMA_VERSION)
            sb.append(",\"tracks\":").append(renderTracks(p.tracks))
            sb.append(",\"clips\":").append(renderClips(p.clips))
            sb.append(",\"output\":").append(renderOutput(p.output))
            // Optional fields render OMIT-WHEN-NULL/blank so files stay
            // minimal and pre-field files stay byte-identical.
            p.projectEnd?.let { sb.append(",\"projectEnd\":").append(num(it)) }
            p.backgroundDuration?.let { sb.append(",\"backgroundDuration\":").append(num(it)) }
            sb.append('}')
            return sb.toString()
        }

        private fun renderTracks(tracks: List<TimelineTrack>): String {
            val sb = StringBuilder()
            sb.append('[')
            tracks.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(t.id))
                    .append(",\"kind\":\"").append(t.kind.name).append('"')
                    .append(",\"index\":").append(t.index)
                    .append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        private fun renderClips(clips: List<TimelineClip>): String {
            val sb = StringBuilder()
            sb.append('[')
            clips.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(c.id))
                    .append(",\"trackId\":").append(ProjectJson.quote(c.trackId))
                    .append(",\"sourceId\":")
                if (c.sourceId == null) sb.append("null") else sb.append(ProjectJson.quote(c.sourceId))
                sb.append(",\"sourceStart\":").append(num(c.sourceRange.start))
                    .append(",\"sourceEnd\":").append(num(c.sourceRange.end))
                    .append(",\"timelineStart\":").append(num(c.timelineRange.start))
                    .append(",\"timelineEnd\":").append(num(c.timelineRange.end))
                c.colorDefinition.encoding?.let {
                    sb.append(",\"colorEncoding\":").append(ProjectJson.quote(it))
                }
                c.colorDefinition.colorSpace?.let {
                    sb.append(",\"colorSpace\":").append(ProjectJson.quote(it))
                }
                c.colorDefinition.transfer?.let {
                    sb.append(",\"colorTransfer\":").append(ProjectJson.quote(it))
                }
                if (c.text.isNotBlank()) {
                    sb.append(",\"text\":").append(ProjectJson.quote(c.text))
                }
                c.linkedAudioId?.let {
                    sb.append(",\"linkedAudioId\":").append(ProjectJson.quote(it))
                }
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        private fun renderOutput(o: TimelineOutput): String {
            val sb = StringBuilder()
            sb.append("{\"width\":").append(o.width)
                .append(",\"height\":").append(o.height)
                .append(",\"fps\":").append(o.fps)
                .append(",\"orientationDeg\":").append(o.orientationDeg)
                .append(",\"colorSpace\":").append(ProjectJson.quote(o.colorSpace))
                .append(",\"transfer\":").append(ProjectJson.quote(o.transfer))
                .append(",\"hdr\":").append(o.hdr)
                .append('}')
            return sb.toString()
        }

        private fun num(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

        // ---- reader ----

        /**
         * Parses the "timeline" section of a project.json. Throws
         * IllegalArgumentException on malformed JSON; returns the DEFAULT
         * project when the section is missing (a fresh project).
         */
        internal fun parseTimeline(text: String): TimelineProject {
            val p = JsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val section = root["timeline"] as? Map<*, *>
                ?: return defaultProject() // no section yet: fresh project
            val tracks = (section["tracks"] as? List<*>).orEmpty().mapNotNull { t ->
                val m = t as? Map<*, *> ?: return@mapNotNull null
                TimelineTrack(
                    id = m["id"] as? String ?: return@mapNotNull null,
                    kind = enumOrThrow(m["kind"], TrackKind.VIDEO),
                    index = (m["index"] as? Double)?.toInt() ?: 0,
                )
            }
            // Track kinds referenced by clips must exist for the length
            // resolver's class filter; a clip on an unknown track keeps
            // loading (kind lookups just miss — the resolver ignores it).
            val trackKinds = tracks.associate { it.id to it.kind }
            val clips = (section["clips"] as? List<*>).orEmpty().mapNotNull { c ->
                val m = c as? Map<*, *> ?: return@mapNotNull null
                val id = m["id"] as? String ?: return@mapNotNull null
                val trackId = m["trackId"] as? String ?: return@mapNotNull null
                val sourceStart = (m["sourceStart"] as? Double) ?: 0.0
                val sourceEnd = (m["sourceEnd"] as? Double) ?: 0.0
                val timelineStart = (m["timelineStart"] as? Double) ?: 0.0
                val timelineEnd = (m["timelineEnd"] as? Double) ?: 0.0
                // A clip whose ranges disagree in duration would violate
                // the model invariant: skip it rather than throw, so one
                // bad row never takes the whole project down (the
                // per-row tolerance the export store uses).
                if (timelineEnd - timelineStart != sourceEnd - sourceStart) {
                    return@mapNotNull null
                }
                TimelineClip(
                    id = id,
                    trackId = trackId,
                    sourceId = m["sourceId"] as? String,
                    sourceRange = FrameRange(sourceStart, sourceEnd),
                    timelineRange = FrameRange(timelineStart, timelineEnd),
                    colorDefinition = ClipColorDefinition(
                        encoding = m["colorEncoding"] as? String,
                        colorSpace = m["colorSpace"] as? String,
                        transfer = m["colorTransfer"] as? String,
                    ),
                    text = m["text"] as? String ?: "",
                    linkedAudioId = m["linkedAudioId"] as? String,
                )
            }
            val out = (section["output"] as? Map<*, *>).let { m ->
                if (m == null) TimelineOutput() else TimelineOutput(
                    width = (m["width"] as? Double)?.toInt() ?: 1920,
                    height = (m["height"] as? Double)?.toInt() ?: 1080,
                    fps = (m["fps"] as? Double)?.toInt() ?: 30,
                    orientationDeg = (m["orientationDeg"] as? Double)?.toInt() ?: 0,
                    colorSpace = m["colorSpace"] as? String ?: "rec709",
                    transfer = m["transfer"] as? String ?: "sdr-video",
                    hdr = m["hdr"] as? Boolean ?: false,
                )
            }
            return TimelineProject(
                output = out,
                tracks = tracks.ifEmpty { defaultProject().tracks },
                clips = clips,
                projectEnd = section["projectEnd"] as? Double,
                backgroundDuration = section["backgroundDuration"] as? Double,
            )
        }

        private inline fun <reified T : Enum<T>> enumOrThrow(v: Any?, fallback: T): T =
            when (v) {
                is String -> java.lang.Enum.valueOf(T::class.java, v)
                else -> fallback
            }
    }
}
