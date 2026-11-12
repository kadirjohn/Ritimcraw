package com.ritim.ritimcraw.data.audio

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.ProjectJson
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Persists the audio track model into `files/project.json`'s "audio"
 * section (AUD-02/AUD-03) — the SIXTH section alongside grades/
 * sources/exports/luts/timeline, following the same store pattern as
 * TimelineStore: every save re-renders only the "audio" section and
 * preserves the other five untouched via [ProjectJson.withSection]
 * (unknown sections keep their encounter order), so stores sharing
 * project.json never clobber each other.
 *
 * SECTION SHAPE (schema v1; the section is an OBJECT, not a list):
 *
 *   { "version": 1,
 *     "links": [ { "sourceId": "src-x", "state": "REMOVED" },
 *                { "sourceId": "src-y", "state": "LINKED_EDITED",
 *                  "editedAudioId": "rec-2" } ],
 *     "clips":  [ { "id": "a-1", "sourceId": "src-x",
 *                   "sampleRateHz": 44100,
 *                   "gainDb": -6.5,            // omitted when 0
 *                   "fadeInMs": 120,           // omitted when 0
 *                   "fadeOutMs": 80 } ] }      // omitted when 0
 *
 * CANONICAL FORM (AUD-02's persistence half): "links" holds ONLY
 * non-ATTACHED records — ATTACHED is the absent-record default, so an
 * explicit ATTACHED record is normalized to absent on load and never
 * rendered. This is what makes the EXPLICIT-state record durable: a
 * REMOVED (or DETACHED/LINKED_EDITED) record round-trips verbatim, so
 * a removed original audio NEVER falls back to ATTACHED when an old
 * project reopens.
 *
 * MIGRATION STANCE (the TimelineStore precedent). The section
 * carries a version field (currently 1). Parse is FORWARD-TOLERANT:
 * unknown FIELDS are ignored, and ABSENT fields fall back to their
 * defaults. A FUTURE v2 will bump the version and migrate in the
 * parse path; v1 parsing never rewrites data it did not write. A
 * missing or unrecognized "audio" section loads the DEFAULT empty
 * project (a fresh project has no audio state), and a corrupt section
 * (unparseable JSON) throws IllegalArgumentException from the parse
 * helpers — the store's load() catches it and surfaces the default,
 * keeping the app usable (the GradeStore corrupt-store rule).
 *
 * ATOMICITY: writes go to a sibling `.tmp` file then rename over the
 * target (the GradeStore/TimelineStore atomic-write approach — a
 * crash mid-write never corrupts the previous file; the rename
 * fallback covers cross-filesystem renames).
 *
 * IDS: persisted verbatim — AudioClip.id joins the timeline core's
 * audio clip id and editedAudioId joins the future edited-recording
 * store; the store never regenerates an id.
 *
 * TOLERANCE: a link row or clip row that fails its model invariant
 * (an unknown state string, a non-positive sample rate, a target in a
 * non-LINKED_EDITED state) is SKIPPED, not fatal — one bad row never
 * takes the project down (the per-row tolerance the timeline store
 * uses on mismatched-duration clips).
 */
class AudioStore(private val file: File) {

    private val lock = Any()

    /** Loads the audio section; default empty project when absent. */
    fun load(): AudioProject = synchronized(lock) { loadOrNull() ?: AudioProject() }

    /** Persists immediately (every save persists — the GradeStore rule). */
    fun save(project: AudioProject) = synchronized(lock) { write(project) }

    private fun loadOrNull(): AudioProject? {
        if (!file.exists()) return null
        return try {
            parseAudio(file.readText(StandardCharsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            // Corrupt store: keep the app usable; the audio state loads
            // as empty (the raw text survives on disk untouched).
            null
        }
    }

    private fun write(project: AudioProject) {
        file.parentFile?.mkdirs()
        val text = file.takeIf { it.exists() }?.readText(StandardCharsets.UTF_8)
        val merged = ProjectJson.withSection(
            existingText = text,
            section = "audio",
            renderedSection = renderAudio(project),
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
         * Renders the whole project.json with the given audio state,
         * preserving every other section already in [existingText]
         * (see [ProjectJson.withSection]).
         */
        internal fun mergeInto(existingText: String?, project: AudioProject): String =
            ProjectJson.withSection(
                existingText = existingText,
                section = "audio",
                renderedSection = renderAudio(project),
                version = SCHEMA_VERSION,
            )

        internal fun renderAudio(p: AudioProject): String {
            val sb = StringBuilder()
            sb.append("{\"version\":").append(SCHEMA_VERSION)
            sb.append(",\"links\":").append(renderLinks(p.links))
            sb.append(",\"clips\":").append(renderClips(p.clips))
            sb.append('}')
            return sb.toString()
        }

        private fun renderLinks(links: Map<String, AudioLinkRecord>): String {
            val sb = StringBuilder()
            sb.append('[')
            var first = true
            for ((sourceId, record) in links) {
                // Canonical form: only non-ATTACHED records render —
                // ATTACHED is the absent default (see the class KDoc).
                if (record.state == AudioLinkState.ATTACHED) continue
                if (!first) sb.append(',')
                first = false
                sb.append("{\"sourceId\":").append(ProjectJson.quote(sourceId))
                    .append(",\"state\":\"").append(record.state.name).append('"')
                record.editedAudioId?.let {
                    sb.append(",\"editedAudioId\":").append(ProjectJson.quote(it))
                }
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        private fun renderClips(clips: List<AudioClip>): String {
            val sb = StringBuilder()
            sb.append('[')
            clips.forEachIndexed { i, c ->
                if (i > 0) sb.append(',')
                sb.append("{\"id\":").append(ProjectJson.quote(c.id))
                    .append(",\"sourceId\":").append(ProjectJson.quote(c.sourceId))
                    .append(",\"sampleRateHz\":").append(c.sampleRateHz)
                // Optional fields render OMIT-WHEN-DEFAULT so files stay
                // minimal and pre-field files stay byte-identical.
                if (c.gainDb != 0f) sb.append(",\"gainDb\":").append(c.gainDb)
                if (c.fadeInMs != 0) sb.append(",\"fadeInMs\":").append(c.fadeInMs)
                if (c.fadeOutMs != 0) sb.append(",\"fadeOutMs\":").append(c.fadeOutMs)
                sb.append('}')
            }
            sb.append(']')
            return sb.toString()
        }

        // ---- reader ----

        /**
         * Parses the "audio" section of a project.json. Throws
         * IllegalArgumentException on malformed JSON; returns the
         * DEFAULT empty project when the section is missing (a fresh
         * project has no audio state).
         */
        internal fun parseAudio(text: String): AudioProject {
            val p = JsonParser(text)
            val root = p.parseValue()
            p.skipWs()
            if (!p.atEnd()) throw IllegalArgumentException("trailing content")
            if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
            val section = root["audio"] as? Map<*, *>
                ?: return AudioProject() // no section yet: fresh project
            val links = LinkedHashMap<String, AudioLinkRecord>()
            for (entry in (section["links"] as? List<*>).orEmpty()) {
                val m = entry as? Map<*, *> ?: continue
                val sourceId = m["sourceId"] as? String ?: continue
                if (sourceId.isBlank()) continue
                val state = when (m["state"] as? String) {
                    "ATTACHED" -> AudioLinkState.ATTACHED
                    "DETACHED" -> AudioLinkState.DETACHED
                    "REMOVED" -> AudioLinkState.REMOVED
                    "LINKED_EDITED" -> AudioLinkState.LINKED_EDITED
                    else -> continue // unknown state string: skip the row
                }
                // Canonical form: an explicit ATTACHED record is the
                // absent default — normalize it away on load.
                if (state == AudioLinkState.ATTACHED) continue
                val edited = m["editedAudioId"] as? String
                val record = try {
                    AudioLinkRecord(state = state, editedAudioId = edited)
                } catch (e: IllegalArgumentException) {
                    continue // e.g. state LINKED_EDITED without a target
                }
                links[sourceId] = record
            }
            val clips = ArrayList<AudioClip>()
            for (entry in (section["clips"] as? List<*>).orEmpty()) {
                val m = entry as? Map<*, *> ?: continue
                val id = m["id"] as? String ?: continue
                val sourceId = m["sourceId"] as? String ?: continue
                val rate = (m["sampleRateHz"] as? Double)?.toInt() ?: 0
                val clip = try {
                    AudioClip(
                        id = id,
                        sourceId = sourceId,
                        sampleRateHz = rate,
                        gainDb = (m["gainDb"] as? Double)?.toFloat() ?: 0f,
                        fadeInMs = (m["fadeInMs"] as? Double)?.toInt() ?: 0,
                        fadeOutMs = (m["fadeOutMs"] as? Double)?.toInt() ?: 0,
                    )
                } catch (e: IllegalArgumentException) {
                    continue // non-positive rate etc.: skip the row
                }
                clips.add(clip)
            }
            return AudioProject(links = links, clips = clips)
        }
    }
}
