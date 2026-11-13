package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.JsonParser
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.data.timeline.TimelineStore

/**
 * A timeline snapshotted into an [ExportJob] (the timeline export, TL-EX):
 * the project AS QUEUED, plus the display name the UI shows on the row
 * ("Timeline · <name>").
 *
 * SNAPSHOT SEMANTICS (see ExportJob.timeline): the JSON is captured at
 * ENQUEUE time via [TimelineStore.renderTimeline] and wrapped as its own
 * parseable document — `{"timeline":<rendered>}` — because parseTimeline
 * parses the WHOLE document and reads the "timeline" section. The wrap is
 * what makes the snapshot round-trip standalone, with no dependency on
 * the live project.json (which the user may keep editing after enqueue).
 *
 * PERSISTENCE: [projectJson] is stored as a JSON STRING inside the
 * exports row (ExportStore quotes it), omitted when null so every
 * pre-timeline row stays byte-identical. The parse here is ROW-TOLERANT:
 * a malformed or missing snapshot yields null — it must never throw out
 * of ExportStore's row loop, because load()'s catch-all would then empty
 * the WHOLE queue over one bad row. A degraded row (unparseable
 * snapshot) fails honestly at run instead: the exporter's build step
 * cannot resolve a timeline and the job lands FAILED with the reason.
 */
data class TimelineRef(
    /** The timeline's display name (the row label: "Timeline · <name>"). */
    val name: String,
    /** The wrapped timeline section (see the class KDoc: parseable whole document). */
    val projectJson: String,
) {
    companion object {

        /** Captures [project] as a queue-time snapshot under [name]. */
        fun capture(name: String, project: TimelineProject): TimelineRef =
            TimelineRef(
                name = name,
                projectJson = "{\"timeline\":" +
                    TimelineStore.renderTimeline(project) + "}",
            )

        /**
         * ROW-TOLERANT parse (never throws): the snapshot back, or null
         * when [json] is null/blank/malformed — the caller decides what a
         * null means (the exporter fails the job honestly; the store keeps
         * the rest of the queue).
         */
        fun parseOrNull(json: String?): TimelineRef? {
            if (json.isNullOrBlank()) return null
            return try {
                val root = JsonParser(json).parseValue() as? Map<*, *> ?: return null
                val name = root["name"] as? String ?: return null
                val project = root["projectJson"] as? String ?: return null
                TimelineRef(name = name, projectJson = project)
            } catch (e: Exception) {
                null
            }
        }
    }
}
