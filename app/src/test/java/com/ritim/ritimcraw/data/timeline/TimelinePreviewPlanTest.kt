package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimelinePreviewPlan tests (the timeline preview, commit a): the
 * plan's OWN rules — the walk itself is the export plan's, reused
 * verbatim (the preview-exports-the-same-frames test locks that), so
 * these test the position math the playback loop and scrub live on:
 *  - reuse: the preview plan's entries are the export plan's entries
 *    exactly (what you preview is what you export).
 *  - playhead -> frame: the identity at equal rates, the trimmed-in
 *    source window, gaps answer black.
 *  - without skips: a gated-out clip's span answers BLACK (the
 *    preview's honest rule — never a guess, never a hidden span).
 *  - bounds: the end playhead holds the last frame; an empty timeline
 *    answers black at 0.
 *  - round-trip: outputFrameOf/playheadOf invert each other exactly on
 *    whole frames.
 *  - playback grid: ptsUs is the exporter's uniform i * 1e6 / fps.
 */
class TimelinePreviewPlanTest {

    private fun source(id: String) = MediaSource(
        id = id,
        uri = "content://x/$id",
        fingerprint = "fp-$id",
        kind = SourceKind.MCRAW,
        access = Access.OK,
    )

    private fun project(
        clips: List<TimelineClip>,
        fps: Int = 30,
        projectEnd: Double? = null,
    ) = TimelineProject(
        output = TimelineOutput(fps = fps),
        tracks = listOf(
            TimelineTrack("v", TrackKind.VIDEO, 0),
            TimelineTrack("a", TrackKind.AUDIO, 1),
        ),
        clips = clips,
        projectEnd = projectEnd,
    )

    private fun clip(
        id: String,
        sourceId: String,
        sourceStart: Double,
        timelineStart: Double,
        duration: Double,
    ) = TimelineClip(
        id = id,
        trackId = "v",
        sourceId = sourceId,
        sourceRange = FrameRange(sourceStart, sourceStart + duration),
        timelineRange = FrameRange(timelineStart, timelineStart + duration),
    )

    private fun build(
        p: TimelineProject,
        sources: Map<String, MediaSource>,
        fps: (String) -> Double? = { 30.0 },
        frames: (String) -> Int? = { 240 },
    ) = TimelinePreviewPlan.build(
        project = p,
        sources = sources,
        fpsMeasured = fps,
        frameCount = frames,
    )

    // ---------------------------------------------------------------
    // Reuse: the preview IS the export plan's walk
    // ---------------------------------------------------------------

    @Test
    fun preview_reuses_the_export_plan_entries_exactly() {
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                clip("c2", "s2", 0.0, 50.0, 30.0),
            ),
        )
        val sources = mapOf("s1" to source("s1"), "s2" to source("s2"))
        val preview = build(p, sources)
        val export = TimelineExportPlan.build(p, sources, { 30.0 }, { 240 })
        assertEquals(export.fps, preview.fps)
        assertEquals(export.totalFrames, preview.totalFrames)
        assertEquals(export.entries, preview.entries)
    }

    // ---------------------------------------------------------------
    // Playhead -> (clip, source frame)
    // ---------------------------------------------------------------

    @Test
    fun equal_rates_playhead_maps_the_identity() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = build(p, mapOf("s1" to source("s1")))
        // Playhead 10 shows source frame 10 — the identity.
        val at = plan.atPlayhead(10.0)
        assertEquals(10, at.outputFrame)
        val c = at.frame as TimelinePreviewPlan.Frame.Clip
        assertEquals("c1", c.clipId)
        assertEquals("s1", c.sourceId)
        assertEquals(10, c.sourceFrame)
        // The last frame holds (the end playhead rule).
        val last = plan.atPlayhead(89.0)
        assertEquals(89, (last.frame as TimelinePreviewPlan.Frame.Clip).sourceFrame)
    }

    @Test
    fun trimmed_clip_playhead_maps_the_trimmed_window() {
        // Head trimmed by 10: timeline frame 0 shows source frame 10.
        val p = project(listOf(clip("c1", "s1", 10.0, 0.0, 70.0)))
        val plan = build(p, mapOf("s1" to source("s1")))
        val at = plan.atPlayhead(0.0)
        assertEquals(10, (at.frame as TimelinePreviewPlan.Frame.Clip).sourceFrame)
        // And timeline frame 25 shows source frame 35.
        assertEquals(35, (plan.atPlayhead(25.0).frame as TimelinePreviewPlan.Frame.Clip).sourceFrame)
    }

    @Test
    fun fps_ratio_playhead_maps_frame_nearest() {
        // 24 fps source in a 30 fps project: playhead 30 is one real
        // second — source frame 24 (the export mapping's own answer).
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 150.0)))
        val plan = build(p, mapOf("s1" to source("s1")), fps = { 24.0 })
        assertEquals(24, (plan.atPlayhead(30.0).frame as TimelinePreviewPlan.Frame.Clip).sourceFrame)
    }

    // ---------------------------------------------------------------
    // Gaps and skips: black, honestly
    // ---------------------------------------------------------------

    @Test
    fun gap_playhead_answers_black() {
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                clip("c2", "s2", 0.0, 50.0, 30.0),
            ),
        )
        val plan = build(p, mapOf("s1" to source("s1"), "s2" to source("s2")))
        // The hole 30..50 answers black; both sides answer their clips.
        assertTrue(plan.atPlayhead(40.0).frame is TimelinePreviewPlan.Frame.Black)
        assertTrue(plan.atPlayhead(20.0).frame is TimelinePreviewPlan.Frame.Clip)
        assertTrue(plan.atPlayhead(60.0).frame is TimelinePreviewPlan.Frame.Clip)
    }

    @Test
    fun fps_unknown_span_answers_black_not_a_guess() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = build(p, mapOf("s1" to source("s1")), fps = { null })
        // The whole span is black — the preview never substitutes a
        // guessed rate, and the span is not hidden.
        for (f in listOf(0, 10, 89)) {
            assertTrue(plan.atPlayhead(f.toDouble()).frame is TimelinePreviewPlan.Frame.Black)
        }
        assertEquals(90, plan.totalFrames)
    }

    @Test
    fun source_missing_span_answers_black() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = build(p, emptyMap()) // the Library lost the source
        assertTrue(plan.atPlayhead(45.0).frame is TimelinePreviewPlan.Frame.Black)
    }

    @Test
    fun trailing_black_answers_black() {
        // Audio runs past the last video: the tail resolves longer and
        // answers black (the video-early-end rule, previewed honestly).
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                TimelineClip(
                    id = "a1",
                    trackId = "a",
                    sourceId = "s1",
                    sourceRange = FrameRange(0.0, 100.0),
                    timelineRange = FrameRange(0.0, 100.0),
                ),
            ),
        )
        val plan = build(p, mapOf("s1" to source("s1")))
        assertEquals(100, plan.totalFrames)
        assertTrue(plan.atPlayhead(99.0).frame is TimelinePreviewPlan.Frame.Black)
    }

    // ---------------------------------------------------------------
    // Bounds and round-trip
    // ---------------------------------------------------------------

    @Test
    fun end_playhead_holds_the_last_frame() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = build(p, mapOf("s1" to source("s1")))
        // A playhead at/past the end clamps to the last frame, and the
        // last frame is still the clip's (not black, not out of range).
        val at = plan.atPlayhead(90.0)
        assertEquals(89, at.outputFrame)
        assertEquals(89, (at.frame as TimelinePreviewPlan.Frame.Clip).sourceFrame)
    }

    @Test
    fun negative_playhead_clamps_to_zero() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = build(p, mapOf("s1" to source("s1")))
        assertEquals(0, plan.atPlayhead(-5.0).outputFrame)
    }

    @Test
    fun empty_timeline_answers_black_at_zero() {
        val plan = build(project(emptyList()), emptyMap(), fps = { null }, frames = { null })
        assertEquals(0, plan.totalFrames)
        val at = plan.atPlayhead(0.0)
        assertEquals(0, at.outputFrame)
        assertTrue(at.frame is TimelinePreviewPlan.Frame.Black)
    }

    @Test
    fun output_frame_and_playhead_round_trip_exactly() {
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                clip("c2", "s2", 0.0, 50.0, 30.0),
            ),
        )
        val plan = build(p, mapOf("s1" to source("s1"), "s2" to source("s2")))
        for (f in 0 until plan.totalFrames) {
            assertEquals(f.toDouble(), plan.playheadOf(f), 0.0)
            assertEquals(f, plan.outputFrameOf(plan.playheadOf(f)))
        }
    }

    // ---------------------------------------------------------------
    // The playback grid
    // ---------------------------------------------------------------

    @Test
    fun pts_grid_is_the_exporter_uniform_rule() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = build(p, mapOf("s1" to source("s1")))
        val pts = plan.ptsUs()
        assertEquals(90, pts.size)
        // Frame i at i * 1e6 / fps — the exporter's own pts rule.
        assertEquals(0L, pts[0])
        assertEquals(33_333L, pts[1]) // 1e6 / 30 truncated, as the exporter writes it
        assertEquals(1_000_000L, pts[30])
        assertEquals(2_966_666L, pts[89]) // 89e6 / 30, truncated like the exporter
        // Monotone.
        for (i in 1 until pts.size) {
            assertTrue(pts[i] > pts[i - 1])
        }
    }

    @Test
    fun empty_plan_pts_is_empty() {
        val plan = build(project(emptyList()), emptyMap(), fps = { null }, frames = { null })
        assertEquals(0, plan.ptsUs().size)
    }

    // ---------------------------------------------------------------
    // At-output-frame lookups (the per-tick entry point)
    // ---------------------------------------------------------------

    @Test
    fun at_output_frame_walks_every_frame_of_a_mixed_plan() {
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                clip("c2", "s2", 0.0, 50.0, 30.0),
            ),
        )
        val plan = build(p, mapOf("s1" to source("s1"), "s2" to source("s2")))
        // 0..29 -> c1's frames 0..29; 30..49 black; 50..79 -> c2.
        for (f in 0..29) {
            val c = plan.atOutputFrame(f).frame as TimelinePreviewPlan.Frame.Clip
            assertEquals("c1", c.clipId)
            assertEquals(f, c.sourceFrame)
        }
        for (f in 30..49) {
            assertTrue(plan.atOutputFrame(f).frame is TimelinePreviewPlan.Frame.Black)
        }
        for (f in 50..79) {
            val c = plan.atOutputFrame(f).frame as TimelinePreviewPlan.Frame.Clip
            assertEquals("c2", c.clipId)
            assertEquals(f - 50, c.sourceFrame)
        }
        // Out-of-range lookups clamp into the plan — never an exception.
        assertEquals(0, plan.atOutputFrame(-1).outputFrame)
        assertEquals(79, plan.atOutputFrame(1000).outputFrame)
    }

    @Test
    fun split_pieces_answer_the_unsplit_frames_exactly() {
        // The plan's lookups run through the export mapping, so a split
        // preview rejoins to the unsplit clip's frames — frame-precise
        // edits preview frame-precisely.
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val whole = build(p, mapOf("s1" to source("s1")))
        val split = TimelineOps.split(p, "c1", frame = 37.0, rightId = "c2")!!
        val pieces = build(split, mapOf("s1" to source("s1")))
        for (f in 0 until 90) {
            val w = (whole.atOutputFrame(f).frame as TimelinePreviewPlan.Frame.Clip).sourceFrame
            val s = (pieces.atOutputFrame(f).frame as TimelinePreviewPlan.Frame.Clip).sourceFrame
            assertEquals("frame $f", w, s)
        }
    }
}
