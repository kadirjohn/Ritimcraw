package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.ProjectJson

/**
 * OpenTimelineIO export of a [TimelineProject] (şartname EX-15: "OTIO ile
 * kesim noktaları, track'ler ve medya referansları. Görsel grade ve model
 * dosyaları ayrı preset/sidecar olarak taşınabilir. İç içe efektlerin
 * aktarımı karşı uygulamanın desteklediği alt kümeye göre doğrulanır").
 * Landed 2026-11-23 as a PURE serializer + tests; the share/save action
 * (a CreateDocument uri + this string) is UI wiring for the timeline
 * owner, and the "Resolve/uyumlu okuyucuya" import check is a desktop leg
 * (desktop-test-pending, never claimed here).
 *
 * WHAT IS WRITTEN (the conservative OTIO core subset every importer
 * reads): Timeline.1 -> Stack.1 -> Track.1 (kind "Video" / "Audio") ->
 * Clip.1 / Gap.1, times as RationalTime.1 at the PROJECT fps (the
 * timeline's own timebase — TimelineModel's frame grid), each clip's
 * source_range from its sourceRange, gaps filling the space between clips
 * so the track's sequential layout puts every clip at its real timeline
 * start. Media: ExternalReference.1 with the resolver's target_url, or
 * MissingReference.1 when the clip has no source / the resolver cannot
 * name one (never an invented path).
 *
 * WHAT IS NOT (stated, not silently lost):
 *  - grades, LUTs, crop/rotation and effects are NOT encoded as OTIO
 *    effects (the "iç içe efektler" clause — importers disagree on them);
 *    each clip carries metadata.ritimcraw.{clipId, sourceId,
 *    linkedAudioId} so the grade travels separately (the preset file /
 *    project.json sidecar is the carrier — COL-18's PresetFile);
 *  - TEXT tracks have no OTIO core equivalent: they are skipped and
 *    counted in the timeline's metadata.ritimcraw.skippedTextClips;
 *  - OVERLAPPING clips on one track (OTIO tracks are strictly sequential)
 *    are split onto extra tracks of the same kind named "<name> (2)" etc.
 *    — the cut points survive, the single-track grouping cannot.
 */
object OtioExport {

    /**
     * @param name the timeline's name (the export row label).
     * @param targetUrl the source's media URL/path for ExternalReference, or
     *   null -> MissingReference (honest: unresolved stays unresolved).
     * @param sourceName the human name of a source (clip names); null -> the id.
     */
    fun render(
        project: TimelineProject,
        name: String,
        targetUrl: (String) -> String? = { null },
        sourceName: (String) -> String? = { null },
    ): String {
        val fps = project.output.fps
        require(fps > 0) { "project fps $fps is not a usable timebase" }
        val rate = fps.toDouble()
        val sb = StringBuilder()
        var skippedText = 0
        val trackJson = ArrayList<String>()
        for (track in project.tracks.sortedBy { it.index }) {
            val clips = project.clips.filter { it.trackId == track.id }
                .sortedWith(compareBy({ it.timelineRange.start }, { it.id }))
            if (track.kind == TrackKind.TEXT) {
                skippedText += clips.size
                continue
            }
            val kind = if (track.kind == TrackKind.AUDIO) "Audio" else "Video"
            // An empty track still exports (the track layout is part of the cut).
            lanes(clips).ifEmpty { listOf(emptyList()) }.forEachIndexed { lane, laneClips ->
                val trackName = if (lane == 0) track.id else "${track.id} (${lane + 1})"
                trackJson.add(renderTrack(trackName, kind, laneClips, rate, targetUrl, sourceName))
            }
        }
        sb.append("{\"OTIO_SCHEMA\":\"Timeline.1\"")
            .append(",\"name\":").append(ProjectJson.quote(name))
            .append(",\"global_start_time\":").append(rt(0.0, rate))
            .append(",\"metadata\":{\"ritimcraw\":{\"fps\":").append(fps)
            .append(",\"width\":").append(project.output.width)
            .append(",\"height\":").append(project.output.height)
            .append(",\"skippedTextClips\":").append(skippedText)
            .append("}}")
            .append(",\"tracks\":{\"OTIO_SCHEMA\":\"Stack.1\",\"name\":\"tracks\",\"metadata\":{}")
            .append(",\"source_range\":null,\"effects\":[],\"markers\":[]")
            .append(",\"children\":[").append(trackJson.joinToString(",")).append("]}}")
        return sb.toString()
    }

    /** Greedy lane split: each lane is non-overlapping and start-sorted. */
    internal fun lanes(sorted: List<TimelineClip>): List<List<TimelineClip>> {
        val lanes = ArrayList<MutableList<TimelineClip>>()
        for (c in sorted) {
            val lane = lanes.firstOrNull { it.last().timelineRange.end <= c.timelineRange.start }
            if (lane != null) lane.add(c) else lanes.add(mutableListOf(c))
        }
        return lanes
    }

    private fun renderTrack(
        name: String,
        kind: String,
        clips: List<TimelineClip>,
        rate: Double,
        targetUrl: (String) -> String?,
        sourceName: (String) -> String?,
    ): String {
        val children = ArrayList<String>()
        var cursor = 0.0
        for (c in clips) {
            val start = c.timelineRange.start
            if (start > cursor) {
                children.add(renderGap(start - cursor, rate))
            }
            // A clip placed before the origin keeps only its visible part.
            val visibleStart = maxOf(start, cursor)
            val trimHead = visibleStart - start
            val dur = c.timelineRange.end - visibleStart
            if (dur > 0.0) {
                children.add(renderClip(c, c.sourceRange.start + trimHead, dur, rate, targetUrl, sourceName))
            }
            cursor = maxOf(cursor, c.timelineRange.end)
        }
        return "{\"OTIO_SCHEMA\":\"Track.1\",\"name\":" + ProjectJson.quote(name) +
            ",\"kind\":\"" + kind + "\",\"metadata\":{},\"source_range\":null" +
            ",\"effects\":[],\"markers\":[],\"children\":[" + children.joinToString(",") + "]}"
    }

    private fun renderGap(duration: Double, rate: Double): String =
        "{\"OTIO_SCHEMA\":\"Gap.1\",\"name\":\"\",\"metadata\":{}" +
            ",\"source_range\":" + tr(0.0, duration, rate) + ",\"effects\":[],\"markers\":[]}"

    private fun renderClip(
        c: TimelineClip,
        sourceStart: Double,
        duration: Double,
        rate: Double,
        targetUrl: (String) -> String?,
        sourceName: (String) -> String?,
    ): String {
        val sid = c.sourceId
        val url = sid?.let(targetUrl)
        val ref = if (url != null) {
            "{\"OTIO_SCHEMA\":\"ExternalReference.1\",\"name\":\"\",\"metadata\":{}" +
                ",\"available_range\":null,\"target_url\":" + ProjectJson.quote(url) + "}"
        } else {
            "{\"OTIO_SCHEMA\":\"MissingReference.1\",\"name\":\"\",\"metadata\":{}" +
                ",\"available_range\":null}"
        }
        val clipName = sid?.let { sourceName(it) ?: it } ?: c.id
        val meta = StringBuilder("{\"ritimcraw\":{\"clipId\":").append(ProjectJson.quote(c.id))
        if (sid != null) meta.append(",\"sourceId\":").append(ProjectJson.quote(sid))
        c.linkedAudioId?.let { meta.append(",\"linkedAudioId\":").append(ProjectJson.quote(it)) }
        meta.append("}}")
        return "{\"OTIO_SCHEMA\":\"Clip.1\",\"name\":" + ProjectJson.quote(clipName) +
            ",\"metadata\":" + meta +
            ",\"source_range\":" + tr(sourceStart, duration, rate) +
            ",\"media_reference\":" + ref + ",\"effects\":[],\"markers\":[]}"
    }

    private fun rt(value: Double, rate: Double): String =
        "{\"OTIO_SCHEMA\":\"RationalTime.1\",\"rate\":" + num(rate) + ",\"value\":" + num(value) + "}"

    private fun tr(start: Double, duration: Double, rate: Double): String =
        "{\"OTIO_SCHEMA\":\"TimeRange.1\",\"duration\":" + rt(duration, rate) +
            ",\"start_time\":" + rt(start, rate) + "}"

    /** OTIO numbers are floats: integral values print as "N.0", others verbatim. */
    private fun num(v: Double): String = if (v == Math.rint(v) && kotlin.math.abs(v) < 1e15) {
        v.toLong().toString() + ".0"
    } else {
        v.toString()
    }
}
