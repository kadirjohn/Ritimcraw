package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TimelineExportPlan tests (the timeline's first export, commit a):
 *  - 30->30 identity: equal rates map output frame i to source frame i,
 *    and trims pick the trimmed-in source window.
 *  - 24->30 frame-nearest mapping: repeats where the output grid is
 *    denser, real time preserved (output 30 -> source 24).
 *  - 29.97 round-trip: the mapping is a pure rate ratio, exact under a
 *    floating fps.
 *  - gaps: a hole between clips renders black, contiguous with both.
 *  - skip reasons: fps UNKNOWN, source missing, frame count unknown —
 *    each skips (never guesses) and the span renders black.
 *  - split rejoin frame precision: two split pieces reproduce the
 *    unsplit clip's source frames exactly.
 *  - audio marker: an AUDIO-track clip sets audioSkipped (v1 video-only).
 *  - total length: resolveLength's tail renders trailing black.
 */
class TimelineExportTest {

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

    // ---------------------------------------------------------------
    // 30 -> 30: the identity mapping, trims included
    // ---------------------------------------------------------------

    @Test
    fun equal_rates_map_identity() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        assertEquals(90, plan.totalFrames)
        assertEquals(1, plan.entries.size)
        val e = plan.entries[0] as TimelineExportPlan.Entry.Clip
        assertEquals(0, e.outputStart)
        assertEquals(90, e.sourceFrames.size)
        // Identity: output frame i shows source frame i.
        assertEquals((0 until 90).toList(), e.sourceFrames)
        assertTrue(plan.skips.isEmpty())
    }

    @Test
    fun trims_pick_the_trimmed_source_window() {
        // Head trimmed by 10, tail trimmed by 10: the clip plays frames
        // 10..80 of the source at timeline 0..70.
        val p = project(listOf(clip("c1", "s1", 10.0, 0.0, 70.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        val e = plan.entries[0] as TimelineExportPlan.Entry.Clip
        assertEquals((10 until 80).toList(), e.sourceFrames)
    }

    // ---------------------------------------------------------------
    // 24 -> 30: frame-nearest mapping, no resampling
    // ---------------------------------------------------------------

    @Test
    fun fps_24_in_project_30_repeats_nearest_source_frame() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 150.0)), fps = 30)
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 24.0 },
            frameCount = { 240 },
        )
        val e = plan.entries[0] as TimelineExportPlan.Entry.Clip
        assertEquals(150, e.sourceFrames.size)
        // Output frame 30 is one real second: source frame 24.
        assertEquals(24, e.sourceFrames[30])
        // Frame-nearest with repeats: 0.8 rounds to 1 at i=1...
        assertEquals(1, e.sourceFrames[1])
        // ...and 1.6 rounds to 2 at i=2 — the source frame REPEATS where
        // the output grid is denser (i=3 also maps to 2): 24 source
        // frames cover 30 output frames exactly, real time preserved.
        assertEquals(2, e.sourceFrames[2])
        assertEquals(2, e.sourceFrames[3])
        // Ten source frames per 12.5 output frames: output 125 -> 100.
        assertEquals(100, e.sourceFrames[125])
    }

    @Test
    fun fps_24_map_is_real_time_correct_across_the_whole_clip() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 300.0)), fps = 30)
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 24.0 },
            frameCount = { 2400 },
        )
        val e = plan.entries[0] as TimelineExportPlan.Entry.Clip
        // Every output frame maps within half a source frame of the
        // real-time position — nearest-frame, never a jump.
        for (i in e.sourceFrames.indices) {
            val exact = i * 24.0 / 30.0
            assertTrue(
                "i=$i mapped ${e.sourceFrames[i]} vs exact $exact",
                kotlin.math.abs(e.sourceFrames[i] - exact) <= 0.5 + 1e-9,
            )
        }
        // Monotone non-decreasing (nearest mapping never goes back).
        for (i in 1 until e.sourceFrames.size) {
            assertTrue(e.sourceFrames[i] >= e.sourceFrames[i - 1])
        }
    }

    // ---------------------------------------------------------------
    // 29.97: a floating rate round-trips through the ratio
    // ---------------------------------------------------------------

    @Test
    fun fps_2997_in_project_30_is_the_rate_ratio() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 1100.0)), fps = 30)
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 29.97 },
            frameCount = { 1200 },
        )
        val e = plan.entries[0] as TimelineExportPlan.Entry.Clip
        // 29.97/30 = 0.999: output 1000 -> source 999 (the ratio, rounded).
        assertEquals(999, e.sourceFrames[1000])
        // Output 0 -> source 0, output 100 -> source 100 (0.999 rounds to 1).
        assertEquals(0, e.sourceFrames[0])
        assertEquals(100, e.sourceFrames[100])
    }

    // ---------------------------------------------------------------
    // Gaps: black frames between clips
    // ---------------------------------------------------------------

    @Test
    fun gap_between_clips_renders_black() {
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                clip("c2", "s2", 0.0, 50.0, 30.0),
            ),
        )
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1"), "s2" to source("s2")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        // Clip, black 30..50, clip, and nothing else — contiguous cover.
        assertEquals(3, plan.entries.size)
        val clip1 = plan.entries[0] as TimelineExportPlan.Entry.Clip
        val black = plan.entries[1] as TimelineExportPlan.Entry.Black
        val clip2 = plan.entries[2] as TimelineExportPlan.Entry.Clip
        assertEquals(0, clip1.outputStart)
        assertEquals(30, black.outputStart)
        assertEquals(20, black.frameCount)
        assertEquals(50, clip2.outputStart)
        assertEquals(80, plan.totalFrames)
        // Contiguity: every entry starts where the previous ended, and
        // together they cover [0, totalFrames) exactly once.
        var cursor = 0
        for (entry in plan.entries) {
            assertEquals(cursor, entry.outputStart)
            cursor = entry.outputEnd
        }
        assertEquals(plan.totalFrames, cursor)
    }

    @Test
    fun trailing_black_covers_the_resolved_length() {
        // Audio runs past the last video: TL-17 resolves longer, and the
        // video-early-end rule (no background) leaves the tail black.
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
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        assertEquals(100, plan.totalFrames)
        val clip1 = plan.entries[0] as TimelineExportPlan.Entry.Clip
        val tail = plan.entries[1] as TimelineExportPlan.Entry.Black
        assertEquals(30, clip1.frameCount)
        assertEquals(30, tail.outputStart)
        assertEquals(70, tail.frameCount)
        // The audio marker is set: v1 exports video-only, honestly.
        assertTrue(plan.audioSkipped)
    }

    // ---------------------------------------------------------------
    // Honest skips: never guess
    // ---------------------------------------------------------------

    @Test
    fun fps_unknown_skips_the_clip_never_guesses() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { null }, // UNKNOWN
            frameCount = { 240 },
        )
        assertEquals(1, plan.skips.size)
        assertEquals(TimelineExportPlan.ClipSkip.Reason.FPS_UNKNOWN, plan.skips[0].reason)
        assertEquals("c1", plan.skips[0].clipId)
        // The clip's whole span renders black — the export completes.
        assertEquals(1, plan.entries.size)
        val black = plan.entries[0] as TimelineExportPlan.Entry.Black
        assertEquals(0, black.outputStart)
        assertEquals(90, black.frameCount)
        assertEquals(90, plan.totalFrames)
    }

    @Test
    fun non_positive_fps_skips_like_unknown() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 0.0 },
            frameCount = { 240 },
        )
        assertEquals(TimelineExportPlan.ClipSkip.Reason.FPS_UNKNOWN, plan.skips[0].reason)
    }

    @Test
    fun source_missing_from_library_skips() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = emptyMap(), // the Library lost the source
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        assertEquals(1, plan.skips.size)
        assertEquals(TimelineExportPlan.ClipSkip.Reason.SOURCE_MISSING, plan.skips[0].reason)
        assertEquals("s1", plan.skips[0].sourceId)
        // Black span, honest total.
        assertEquals(90, (plan.entries[0] as TimelineExportPlan.Entry.Black).frameCount)
    }

    @Test
    fun frame_count_unknown_skips() {
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { null }, // never probed
        )
        assertEquals(TimelineExportPlan.ClipSkip.Reason.FRAME_COUNT_UNKNOWN, plan.skips[0].reason)
    }

    // ---------------------------------------------------------------
    // Split rejoin: frame precision through the export mapping
    // ---------------------------------------------------------------

    @Test
    fun split_pieces_reproduce_the_unsplit_clip_exactly() {
        // The unsplit clip: 90 output frames, source 0..90.
        val whole = project(listOf(clip("c1", "s1", 0.0, 0.0, 90.0)))
        val wholePlan = TimelineExportPlan.build(
            whole,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        val wholeFrames = (wholePlan.entries[0] as TimelineExportPlan.Entry.Clip).sourceFrames

        // The split at frame 37 (TL-05's frame-precise edit): the two
        // pieces' export frames must REJOIN to the whole clip's exactly.
        val split = TimelineOps.split(whole, "c1", frame = 37.0, rightId = "c2")!!
        val splitPlan = TimelineExportPlan.build(
            split,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        assertEquals(2, splitPlan.entries.size)
        val left = splitPlan.entries[0] as TimelineExportPlan.Entry.Clip
        val right = splitPlan.entries[1] as TimelineExportPlan.Entry.Clip
        assertEquals(37, left.frameCount)
        assertEquals(53, right.frameCount)
        assertEquals(37, right.outputStart)
        val rejoined = left.sourceFrames + right.sourceFrames
        assertEquals(wholeFrames, rejoined)
    }

    // ---------------------------------------------------------------
    // Order and overlap
    // ---------------------------------------------------------------

    @Test
    fun clips_render_in_timeline_order_not_list_order() {
        val p = project(
            listOf(
                clip("late", "s2", 0.0, 60.0, 30.0),
                clip("early", "s1", 0.0, 0.0, 30.0),
            ),
        )
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1"), "s2" to source("s2")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        assertEquals(3, plan.entries.size)
        assertEquals("early", (plan.entries[0] as TimelineExportPlan.Entry.Clip).clipId)
        assertEquals("late", (plan.entries[2] as TimelineExportPlan.Entry.Clip).clipId)
    }

    @Test
    fun overlap_drops_the_later_clips_head() {
        // c2 starts at 20 while c1 runs to 30: the earlier clip wins,
        // c2 renders from the cursor (30) and its overlapped head drops.
        val p = project(
            listOf(
                clip("c1", "s1", 0.0, 0.0, 30.0),
                clip("c2", "s2", 0.0, 20.0, 30.0),
            ),
        )
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1"), "s2" to source("s2")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        val c2 = plan.entries.last() as TimelineExportPlan.Entry.Clip
        assertEquals(30, c2.outputStart)
        assertEquals(20, c2.frameCount)
        // Its first frame maps the source frame at the cursor position.
        assertEquals(10, c2.sourceFrames[0])
    }

    // ---------------------------------------------------------------
    // Output settings
    // ---------------------------------------------------------------

    @Test
    fun odd_dimensions_even_snap() {
        val p = TimelineProject(
            output = TimelineOutput(width = 1921, height = 1081, fps = 30),
            tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
            clips = listOf(clip("c1", "s1", 0.0, 0.0, 30.0)),
        )
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 240 },
        )
        assertEquals(1920, plan.width)
        assertEquals(1080, plan.height)
    }

    @Test
    fun empty_project_plans_zero_frames() {
        val plan = TimelineExportPlan.build(
            project(emptyList()),
            sources = emptyMap(),
            fpsMeasured = { null },
            frameCount = { null },
        )
        assertEquals(0, plan.totalFrames)
        assertTrue(plan.entries.isEmpty())
        assertTrue(!plan.audioSkipped)
    }

    @Test
    fun clamp_holds_the_last_real_frame() {
        // A clip claims 240 frames but the source holds 100: the mapping
        // clamps to the last real frame rather than decoding past it.
        val p = project(listOf(clip("c1", "s1", 0.0, 0.0, 150.0)))
        val plan = TimelineExportPlan.build(
            p,
            sources = mapOf("s1" to source("s1")),
            fpsMeasured = { 30.0 },
            frameCount = { 100 },
        )
        val e = plan.entries[0] as TimelineExportPlan.Entry.Clip
        assertEquals(99, e.sourceFrames.max())
        assertTrue(e.sourceFrames.all { it in 0..99 })
    }
}
