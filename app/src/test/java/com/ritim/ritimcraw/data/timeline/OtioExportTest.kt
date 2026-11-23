package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OtioExport tests (EX-15, 2026-11-23): the document parses as JSON with
 * the OTIO core schema names, gaps place clips at their real timeline
 * start, source ranges carry the trims, unresolved media stays
 * MissingReference, text tracks are skipped and counted, overlaps split
 * onto extra lanes, and the ritimcraw metadata keeps the ids.
 */
class OtioExportTest {

    private fun parse(s: String): Map<*, *> {
        val p = JsonParser(s)
        val v = p.parseValue()
        p.skipWs()
        assertTrue(p.atEnd())
        return v as Map<*, *>
    }

    @Suppress("UNCHECKED_CAST")
    private fun tracks(doc: Map<*, *>) = ((doc["tracks"] as Map<*, *>)["children"] as List<Map<*, *>>)

    @Suppress("UNCHECKED_CAST")
    private fun children(track: Map<*, *>) = track["children"] as List<Map<*, *>>

    private fun range(item: Map<*, *>): Pair<Double, Double> {
        val r = item["source_range"] as Map<*, *>
        val start = (r["start_time"] as Map<*, *>)["value"] as Double
        val dur = (r["duration"] as Map<*, *>)["value"] as Double
        return start to dur
    }

    private val project = TimelineProject(
        output = TimelineOutput(fps = 24),
        clips = listOf(
            TimelineClip("v1", "t0", "src-a", FrameRange(10.0, 58.0), FrameRange(0.0, 48.0), linkedAudioId = "a1"),
            TimelineClip("v2", "t0", "src-b", FrameRange(0.0, 24.0), FrameRange(72.0, 96.0)),
            TimelineClip("a1", "t1", "src-a", FrameRange(10.0, 58.0), FrameRange(0.0, 48.0)),
            TimelineClip("x1", "t2", null, FrameRange(0.0, 24.0), FrameRange(0.0, 24.0), text = "Title"),
        ),
    )

    @Test
    fun structure_gaps_ranges_and_references() {
        val doc = parse(
            OtioExport.render(
                project, "My cut",
                targetUrl = { if (it == "src-a") "file:///sdcard/a.mcraw" else null },
                sourceName = { if (it == "src-a") "a.mcraw" else null },
            )
        )
        assertEquals("Timeline.1", doc["OTIO_SCHEMA"])
        assertEquals("My cut", doc["name"])
        val ts = tracks(doc)
        assertEquals(listOf("Video", "Audio"), ts.map { it["kind"] })

        val video = children(ts[0])
        assertEquals(listOf("Clip.1", "Gap.1", "Clip.1"), video.map { it["OTIO_SCHEMA"] })
        assertEquals(10.0 to 48.0, range(video[0]))        // trimmed source in
        assertEquals(0.0 to 24.0, range(video[1]))         // the gap 48..72
        assertEquals(0.0 to 24.0, range(video[2]))
        assertEquals("a.mcraw", video[0]["name"])
        val ref0 = video[0]["media_reference"] as Map<*, *>
        assertEquals("ExternalReference.1", ref0["OTIO_SCHEMA"])
        assertEquals("file:///sdcard/a.mcraw", ref0["target_url"])
        val ref2 = video[2]["media_reference"] as Map<*, *>
        assertEquals("MissingReference.1", ref2["OTIO_SCHEMA"]) // unresolved stays missing
        assertEquals("src-b", video[2]["name"])

        val meta = (video[0]["metadata"] as Map<*, *>)["ritimcraw"] as Map<*, *>
        assertEquals("v1", meta["clipId"])
        assertEquals("src-a", meta["sourceId"])
        assertEquals("a1", meta["linkedAudioId"])

        val rate = ((video[0]["source_range"] as Map<*, *>)["duration"] as Map<*, *>)["rate"]
        assertEquals(24.0, rate)
        val tmeta = (doc["metadata"] as Map<*, *>)["ritimcraw"] as Map<*, *>
        assertEquals(1.0, tmeta["skippedTextClips"])
    }

    @Test
    fun overlapping_clips_split_onto_extra_lanes() {
        val p = TimelineProject(
            clips = listOf(
                TimelineClip("a", "t0", "s", FrameRange(0.0, 30.0), FrameRange(0.0, 30.0)),
                TimelineClip("b", "t0", "s", FrameRange(0.0, 30.0), FrameRange(20.0, 50.0)),
                TimelineClip("c", "t0", "s", FrameRange(0.0, 10.0), FrameRange(40.0, 50.0)),
            ),
        )
        val ts = tracks(parse(OtioExport.render(p, "x")))
        assertEquals(listOf("t0", "t0 (2)", "t1"), ts.map { it["name"] }) // t1 = the empty audio track
        assertEquals(listOf("Clip.1", "Gap.1", "Clip.1"), children(ts[0]).map { it["OTIO_SCHEMA"] }) // a, gap, c
        assertEquals(listOf("Gap.1", "Clip.1"), children(ts[1]).map { it["OTIO_SCHEMA"] })           // gap, b
    }

    @Test
    fun clip_before_origin_keeps_only_its_visible_part() {
        val p = TimelineProject(
            clips = listOf(TimelineClip("a", "t0", "s", FrameRange(100.0, 130.0), FrameRange(-10.0, 20.0))),
        )
        val clip = children(tracks(parse(OtioExport.render(p, "x")))[0]).single()
        assertEquals(110.0 to 20.0, range(clip))
    }

    @Test
    fun empty_project_is_a_valid_document() {
        val doc = parse(OtioExport.render(TimelineProject(), "empty"))
        assertEquals(2, tracks(doc).size) // video + audio; text skipped
        assertTrue(tracks(doc).all { children(it).isEmpty() })
        assertNull((tracks(doc)[0])["source_range"])
    }
}
