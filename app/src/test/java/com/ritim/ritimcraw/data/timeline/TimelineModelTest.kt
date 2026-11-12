package com.ritim.ritimcraw.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Timeline core model tests (Stage C, TL-03..TL-07, TL-17):
 *  - split_rejoins_exactly: the two pieces' source ranges REJOIN to the
 *    old range exactly, at frame precision (TL-05).
 *  - trim contract: both edges same time contract (TL-04).
 *  - snap: edges, playhead, defined edit points (TL-07).
 *  - length resolver: last audio/video/title/project end, incl. the
 *    audio-longer-than-video case (TL-17).
 *  - ID stability: ids survive edits; duplicates get fresh ids (TL-03).
 */
class TimelineModelTest {

    // A standard project: one video track, one audio track, one text.
    private fun project(
        clips: List<TimelineClip>,
        projectEnd: Double? = null,
        backgroundDuration: Double? = null,
    ) = TimelineProject(
        tracks = listOf(
            TimelineTrack("v", TrackKind.VIDEO, 0),
            TimelineTrack("a", TrackKind.AUDIO, 1),
            TimelineTrack("x", TrackKind.TEXT, 2),
        ),
        clips = clips,
        projectEnd = projectEnd,
        backgroundDuration = backgroundDuration,
    )

    private fun clip(
        id: String,
        trackId: String,
        sourceStart: Double,
        timelineStart: Double,
        duration: Double,
        linkedAudioId: String? = null,
    ) = TimelineClip(
        id = id,
        trackId = trackId,
        sourceId = if (trackId == "x") null else "src-$id",
        sourceRange = FrameRange(sourceStart, sourceStart + duration),
        timelineRange = FrameRange(timelineStart, timelineStart + duration),
        linkedAudioId = linkedAudioId,
    )

    // ---------------------------------------------------------------
    // TL-05: split — frame precision + exact rejoin
    // ---------------------------------------------------------------

    @Test
    fun split_rejoins_exactly_at_frame_precision() {
        val c = clip("c1", "v", sourceStart = 10.0, timelineStart = 0.0, duration = 90.0)
        val p = project(listOf(c))

        val split = TimelineOps.split(p, "c1", frame = 37.0, rightId = "c2")!!

        val left = split.clip("c1")!!
        val right = split.clip("c2")!!
        // Rejoin: left + right == the old range, exactly.
        assertEquals(10.0, left.sourceRange.start, 0.0)
        assertEquals(47.0, left.sourceRange.end, 0.0)
        assertEquals(47.0, right.sourceRange.start, 0.0)
        assertEquals(100.0, right.sourceRange.end, 0.0)
        // No gap, no overlap, exact frame values.
        assertEquals(left.sourceRange.end, right.sourceRange.start, 0.0)
        assertEquals(90.0, left.sourceRange.duration + right.sourceRange.duration, 0.0)
        // Timeline placement splits at the same frame.
        assertEquals(37.0, left.timelineRange.end, 0.0)
        assertEquals(37.0, right.timelineRange.start, 0.0)
        // The LEFT piece keeps the id (documented convention).
        assertEquals("c1", left.id)
        assertEquals("c2", right.id)
    }

    @Test
    fun split_outside_clip_is_noop() {
        val c = clip("c1", "v", 0.0, 0.0, 50.0)
        val p = project(listOf(c))
        assertNull(TimelineOps.split(p, "c1", 0.0, "c2")) // at the start edge
        assertNull(TimelineOps.split(p, "c1", 50.0, "c2")) // at the end edge
        assertNull(TimelineOps.split(p, "c1", 80.0, "c2")) // outside
        assertNull(TimelineOps.split(p, "missing", 10.0, "c2"))
        assertEquals(1, p.clips.size) // original untouched
    }

    @Test
    fun split_cuts_linked_audio_at_same_edit_point() {
        // Video 0..100 linked to audio 0..100 at the same position.
        val audio = clip("a1", "a", 0.0, 0.0, 100.0)
        val video = clip("v1", "v", 0.0, 0.0, 100.0, linkedAudioId = "a1")
        val p = project(listOf(video, audio))

        val split = TimelineOps.split(p, "v1", frame = 40.0, rightId = "v2")!!

        val audioLeft = split.clips.first { it.id == "a1" }
        val audioRight = split.clips.first { it.id != "a1" && it.trackId == "a" }
        // Audio splits at the SAME edit point (frame 40).
        assertEquals(40.0, audioLeft.timelineRange.end, 0.0)
        assertEquals(40.0, audioRight.timelineRange.start, 0.0)
        // Cross-links: each video piece to its audio piece.
        assertEquals(audioLeft.id, split.clip("v1")!!.linkedAudioId)
        assertEquals(audioRight.id, split.clip("v2")!!.linkedAudioId)
    }

    @Test
    fun split_cuts_linked_audio_with_offset_placement() {
        // Audio placed AFTER the video (offset), same duration: the edit
        // point maps through the shared timebase.
        val video = clip("v1", "v", 0.0, 0.0, 100.0, linkedAudioId = "a1")
        val audio = clip("a1", "a", 500.0, 30.0, 100.0)
        val p = project(listOf(video, audio))

        // Playhead 40 inside the video maps to 70 inside the audio.
        val split = TimelineOps.split(p, "v1", frame = 40.0, rightId = "v2")!!

        val audioLeft = split.clips.first { it.id == "a1" }
        val audioRight = split.clips.first { it.trackId == "a" && it.id != "a1" }
        assertEquals(70.0, audioLeft.timelineRange.end, 0.0)
        assertEquals(70.0, audioRight.timelineRange.start, 0.0)
        // And the audio source range splits at 500 + 40.
        assertEquals(540.0, audioLeft.sourceRange.end, 0.0)
        assertEquals(540.0, audioRight.sourceRange.start, 0.0)
    }

    // ---------------------------------------------------------------
    // TL-04: trim — both edges, one time contract
    // ---------------------------------------------------------------

    @Test
    fun trim_start_moves_both_ranges_by_same_delta() {
        val c = clip("c1", "v", sourceStart = 10.0, timelineStart = 100.0, duration = 50.0)
        val p = project(listOf(c))

        val trimmed = TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.START, newPosition = 25.0)!!

        val t = trimmed.clip("c1")!!
        // Source skips its first 15 frames; timeline start moves +15.
        assertEquals(25.0, t.sourceRange.start, 0.0)
        assertEquals(60.0, t.sourceRange.end, 0.0)
        assertEquals(115.0, t.timelineRange.start, 0.0)
        assertEquals(150.0, t.timelineRange.end, 0.0)
        // Same duration on both ranges — the contract's invariant.
        assertEquals(t.sourceRange.duration, t.timelineRange.duration, 0.0)
    }

    @Test
    fun trim_end_moves_both_ranges_by_same_delta() {
        val c = clip("c1", "v", sourceStart = 10.0, timelineStart = 100.0, duration = 50.0)
        val p = project(listOf(c))

        val trimmed = TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.END, newPosition = 40.0)!!

        val t = trimmed.clip("c1")!!
        assertEquals(10.0, t.sourceRange.start, 0.0)
        assertEquals(40.0, t.sourceRange.end, 0.0)
        assertEquals(100.0, t.timelineRange.start, 0.0)
        assertEquals(130.0, t.timelineRange.end, 0.0)
    }

    @Test
    fun trim_rejects_emptying_and_extending() {
        val c = clip("c1", "v", 10.0, 0.0, 50.0)
        val p = project(listOf(c))
        // START: at/below start empties; beyond end inverts.
        assertNull(TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.START, 10.0))
        assertNull(TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.START, 60.0))
        assertNull(TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.START, -5.0))
        // END: at/above end empties; below start inverts.
        assertNull(TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.END, 60.0))
        assertNull(TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.END, 10.0))
        assertNull(TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.END, 5.0))
        assertNull(TimelineOps.trim(p, "missing", TimelineOps.TrimEdge.END, 20.0))
    }

    @Test
    fun trim_follows_linked_audio_with_same_delta() {
        val video = clip("v1", "v", 0.0, 0.0, 100.0, linkedAudioId = "a1")
        val audio = clip("a1", "a", 0.0, 0.0, 100.0)
        val p = project(listOf(video, audio))

        val trimmed = TimelineOps.trim(p, "v1", TimelineOps.TrimEdge.END, newPosition = 60.0)!!

        val a = trimmed.clip("a1")!!
        assertEquals(60.0, a.sourceRange.end, 0.0)
        assertEquals(60.0, a.timelineRange.end, 0.0)
    }

    // ---------------------------------------------------------------
    // TL-03: add, move, duplicate, delete — ID stability
    // ---------------------------------------------------------------

    @Test
    fun ids_stay_stable_across_edits() {
        val c = clip("c1", "v", 0.0, 0.0, 50.0)
        var p = project(listOf(c))
        p = TimelineOps.add(p, clip("c2", "v", 0.0, 60.0, 50.0))
        p = TimelineOps.move(p, "c1", timelineStart = 200.0)
        p = TimelineOps.trim(p, "c1", TimelineOps.TrimEdge.END, 30.0)!!
        p = TimelineOps.split(p, "c1", 215.0, "c9")!!
        p = TimelineOps.delete(p, "c2")

        // c1 survived every edit with its id.
        assertEquals("c1", p.clip("c1")!!.id)
        // The split right piece kept the caller's id.
        assertTrue(p.clips.any { it.id == "c9" })
        // c2 is gone; others untouched.
        assertNull(p.clip("c2"))
        assertEquals(2, p.clips.size) // c1 + c9
    }

    @Test
    fun duplicate_gets_fresh_id_and_keeps_original() {
        val c = clip("c1", "v", 5.0, 10.0, 40.0)
        val p = project(listOf(c))
        val dup = TimelineOps.duplicate(p, "c1", newId = "c2")
        assertEquals(2, dup.clips.size)
        assertEquals("c1", dup.clip("c1")!!.id)
        val d = dup.clip("c2")!!
        assertEquals(c.sourceRange, d.sourceRange)
        assertEquals(c.timelineRange, d.timelineRange)
        assertNotEquals(c.id, d.id)
        // Missing clip: no-op, nothing added.
        assertEquals(1, TimelineOps.duplicate(p, "missing", "c3").clips.size)
    }

    @Test
    fun move_changes_timeline_placement_not_source_range() {
        val c = clip("c1", "v", 25.0, 100.0, 40.0)
        val p = project(listOf(c))
        val moved = TimelineOps.move(p, "c1", timelineStart = 7.0)
        val m = moved.clip("c1")!!
        assertEquals(7.0, m.timelineRange.start, 0.0)
        assertEquals(47.0, m.timelineRange.end, 0.0)
        assertEquals(25.0, m.sourceRange.start, 0.0) // source range untouched
        // Track change via move.
        val retracked = TimelineOps.move(p, "c1", timelineStart = 0.0, trackId = "x")
        assertEquals("x", retracked.clip("c1")!!.trackId)
        // Missing clip is a no-op.
        assertEquals(1, TimelineOps.move(p, "missing", 5.0).clips.size)
    }

    @Test
    fun delete_track_removes_only_its_clips() {
        val v = clip("v1", "v", 0.0, 0.0, 50.0)
        val a = clip("a1", "a", 0.0, 0.0, 50.0)
        val p = project(listOf(v, a))
        val cleared = TimelineOps.deleteTrack(p, "v")
        assertNull(cleared.clip("v1"))
        assertEquals("a1", cleared.clip("a1")!!.id)
        assertEquals(2, cleared.tracks.size)
    }

    @Test
    fun add_track_rejects_duplicate_id() {
        val p = project(emptyList())
        val withTrack = TimelineOps.addTrack(p, TimelineTrack("v2", TrackKind.VIDEO, 3))
        assertEquals(4, withTrack.tracks.size)
        // Same id again: no-op.
        assertEquals(4, TimelineOps.addTrack(withTrack, TimelineTrack("v2", TrackKind.AUDIO, 1)).tracks.size)
    }

    // ---------------------------------------------------------------
    // TL-07: snap
    // ---------------------------------------------------------------

    @Test
    fun snap_snaps_to_nearest_edge_within_threshold() {
        // Candidates: clip edges at 0, 50, 100.
        val cands = listOf(0.0, 50.0, 100.0)
        assertEquals(50.0, TimelineOps.snap(48.5, cands, threshold = 5.0), 0.0)
        assertEquals(50.0, TimelineOps.snap(52.0, cands, threshold = 5.0), 0.0)
        assertEquals(0.0, TimelineOps.snap(1.5, cands, threshold = 5.0), 0.0)
        assertEquals(100.0, TimelineOps.snap(99.0, cands, threshold = 5.0), 0.0)
        // Beyond threshold: unchanged.
        assertEquals(44.0, TimelineOps.snap(44.0, cands, threshold = 5.0), 0.0)
        // Tie: nearest wins; equal distance picks the earlier point.
        assertEquals(50.0, TimelineOps.snap(52.5, cands, threshold = 5.0), 0.0)
    }

    @Test
    fun snap_candidates_include_all_clip_edges_and_extras() {
        val p = project(
            listOf(
                clip("c1", "v", 0.0, 0.0, 50.0),
                clip("c2", "v", 0.0, 100.0, 25.0),
            ),
        )
        val cands = TimelineOps.snapCandidates(p, extra = listOf(75.0))
        assertEquals(listOf(0.0, 50.0, 75.0, 100.0, 125.0), cands)
    }

    // ---------------------------------------------------------------
    // TL-17: length resolution
    // ---------------------------------------------------------------

    @Test
    fun length_resolver_takes_max_over_all_content_classes() {
        // Video ends 100, audio 120, title 90: length 120 (audio wins).
        val p = project(
            listOf(
                clip("v1", "v", 0.0, 0.0, 100.0),
                clip("a1", "a", 0.0, 0.0, 120.0),
                clip("x1", "x", 0.0, 0.0, 90.0),
            ),
        )
        assertEquals(120.0, TimelineOps.resolveLength(p), 0.0)
    }

    @Test
    fun length_resolver_audio_longer_than_video_case() {
        // The canonical TL-17 case: audio outlasts video; no background
        // defined -> the audio's duration IS the timeline length.
        val p = project(
            listOf(
                clip("v1", "v", 0.0, 0.0, 60.0),
                clip("a1", "a", 0.0, 0.0, 200.0),
            ),
        )
        assertEquals(200.0, TimelineOps.resolveLength(p), 0.0)
    }

    @Test
    fun length_resolver_video_early_end_uses_background_definition() {
        // Video ends 60, audio 80; background definition duration 150:
        // the background covers the video's early end, length 150.
        val p = project(
            listOf(
                clip("v1", "v", 0.0, 0.0, 60.0),
                clip("a1", "a", 0.0, 0.0, 80.0),
            ),
            backgroundDuration = 150.0,
        )
        assertEquals(150.0, TimelineOps.resolveLength(p), 0.0)
    }

    @Test
    fun length_resolver_project_end_extends_timeline() {
        // Explicit project end beyond all content extends the timeline
        // (the "açık proje bitişi" term).
        val p = project(
            listOf(clip("v1", "v", 0.0, 0.0, 50.0)),
            projectEnd = 300.0,
        )
        assertEquals(300.0, TimelineOps.resolveLength(p), 0.0)
    }

    @Test
    fun length_resolver_empty_project_is_zero() {
        assertEquals(0.0, TimelineOps.resolveLength(project(emptyList())), 0.0)
    }

    // ---------------------------------------------------------------
    // TL-01/TL-02: output settings + mixed-source color definitions
    // ---------------------------------------------------------------

    @Test
    fun output_settings_are_editable_data() {
        val out = TimelineOutput(
            width = 3840,
            height = 2160,
            fps = 24,
            orientationDeg = 90,
            colorSpace = "rec2020",
            transfer = "hlg",
            hdr = true,
        )
        assertEquals(3840, out.width)
        assertEquals(2160, out.height)
        assertEquals(24, out.fps)
        assertEquals(90, out.orientationDeg)
        assertEquals("rec2020", out.colorSpace)
        assertEquals("hlg", out.transfer)
        assertTrue(out.hdr)
        // Defaults are the neutral SDR Rec.709 30fps baseline.
        val d = TimelineOutput()
        assertEquals(1920, d.width)
        assertEquals(1080, d.height)
        assertEquals(30, d.fps)
        assertEquals("rec709", d.colorSpace)
        assertEquals("sdr-video", d.transfer)
        assertTrue(!d.hdr)
    }

    @Test
    fun mixed_project_clips_carry_per_clip_color_definitions() {
        // TL-02: MCRAW + Mi-Log + APV in one project, each clip with its
        // own source color definition, one common output target.
        val raw = clip("c1", "v", 0.0, 0.0, 50.0)
            .copy(colorDefinition = ClipColorDefinition(encoding = "mcraw-raw"))
        val miLog = clip("c2", "v", 0.0, 60.0, 50.0)
            .copy(colorDefinition = ClipColorDefinition(encoding = "mi-log", colorSpace = "bt2020"))
        val apv = clip("c3", "v", 0.0, 120.0, 50.0)
            .copy(colorDefinition = ClipColorDefinition(encoding = "apv", transfer = "hlg"))
        val p = project(listOf(raw, miLog, apv))

        assertEquals("mcraw-raw", p.clip("c1")!!.colorDefinition.encoding)
        assertEquals("mi-log", p.clip("c2")!!.colorDefinition.encoding)
        assertEquals("bt2020", p.clip("c2")!!.colorDefinition.colorSpace)
        assertEquals("apv", p.clip("c3")!!.colorDefinition.encoding)
        assertEquals("hlg", p.clip("c3")!!.colorDefinition.transfer)
        // One common output target for the mixed project.
        assertEquals("rec709", p.output.colorSpace)
    }

    @Test
    fun clip_durations_must_match_on_both_ranges() {
        // The model enforces the timebase contract's invariant at
        // construction: sourceRange.duration == timelineRange.duration.
        var threw = false
        try {
            TimelineClip(
                id = "bad",
                trackId = "v",
                sourceId = "s",
                sourceRange = FrameRange(0.0, 10.0),
                timelineRange = FrameRange(0.0, 12.0),
            )
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun ids_are_unique_and_not_position_derived() {
        val a = TimelineIds.newId()
        val b = TimelineIds.newId()
        assertNotEquals(a, b)
        assertTrue(a.startsWith("c-"))
        val t = TimelineIds.newId("t")
        assertTrue(t.startsWith("t-"))
    }
}
