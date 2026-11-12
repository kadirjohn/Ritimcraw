package com.ritim.ritimcraw.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transaction + undo tests (TL-07): every transaction type round-trips
 * through apply/undo; split-then-undo restores the exact original clip
 * (ID preserved, not just range); undo order = reverse apply order;
 * redo re-applies; capacity drops the oldest; no-op applies do not
 * record and do not clear redo.
 */
class TimelineEditTest {

    private fun project(clips: List<TimelineClip>): TimelineProject = TimelineProject(
        tracks = listOf(
            TimelineTrack("v", TrackKind.VIDEO, 0),
            TimelineTrack("a", TrackKind.AUDIO, 1),
        ),
        clips = clips,
    )

    private fun clip(
        id: String,
        trackId: String = "v",
        sourceStart: Double = 0.0,
        timelineStart: Double = 0.0,
        duration: Double = 50.0,
        linkedAudioId: String? = null,
    ) = TimelineClip(
        id = id,
        trackId = trackId,
        sourceId = if (trackId == "v" || trackId == "a") "src-$id" else null,
        sourceRange = FrameRange(sourceStart, sourceStart + duration),
        timelineRange = FrameRange(timelineStart, timelineStart + duration),
        linkedAudioId = linkedAudioId,
    )

    // ---------------------------------------------------------------
    // Split-then-undo restores the EXACT original clip
    // ---------------------------------------------------------------

    @Test
    fun split_then_undo_restores_exact_original_clip() {
        val original = clip("c1", "v", sourceStart = 10.0, timelineStart = 5.0, duration = 90.0)
        val h = TimelineHistory(project(listOf(original)))

        h.apply(TimelineEdit.SplitClip("c1", frame = 40.0, rightId = "c2"))
        // Split happened: two clips.
        assertEquals(2, h.project.clips.size)
        assertEquals("c2", h.project.clips[1].id)

        h.undo()

        // The EXACT original clip is back — id AND ranges, not just
        // the range numbers (the ID STABILITY rule).
        assertEquals(listOf(original), h.project.clips)
        assertEquals(10.0, h.project.clip("c1")!!.sourceRange.start, 0.0)
        assertEquals(100.0, h.project.clip("c1")!!.sourceRange.end, 0.0)
        assertEquals(5.0, h.project.clip("c1")!!.timelineRange.start, 0.0)
        assertEquals(95.0, h.project.clip("c1")!!.timelineRange.end, 0.0)
        assertNull(h.project.clip("c2"))
    }

    @Test
    fun split_then_undo_restores_linked_audio_exact() {
        val audio = clip("a1", "a", 0.0, 0.0, 100.0)
        val video = clip("v1", "v", 0.0, 0.0, 100.0, linkedAudioId = "a1")
        val h = TimelineHistory(project(listOf(video, audio)))

        h.apply(TimelineEdit.SplitClip("v1", frame = 40.0, rightId = "v2", audioRightId = "a2"))
        // Both split.
        assertEquals(4, h.project.clips.size)
        assertEquals("a2", h.project.clip("v2")!!.linkedAudioId)

        h.undo()

        // The exact original pair is back: 2 clips, original ids,
        // original ranges, original link.
        assertEquals(2, h.project.clips.size)
        assertEquals(video, h.project.clip("v1"))
        assertEquals(audio, h.project.clip("a1"))
        assertEquals("a1", h.project.clip("v1")!!.linkedAudioId)
        assertNull(h.project.clip("v2"))
        assertNull(h.project.clip("a2"))
    }

    @Test
    fun split_undo_then_redo_round_trips() {
        val original = clip("c1", "v", sourceStart = 0.0, timelineStart = 0.0, duration = 100.0)
        val h = TimelineHistory(project(listOf(original)))

        h.apply(TimelineEdit.SplitClip("c1", frame = 60.0, rightId = "c2"))
        val afterSplit = h.project
        h.undo()
        h.redo()

        // Redo re-applies the ORIGINAL transaction: the same split
        // state (same right id, same ranges).
        assertEquals(afterSplit, h.project)
        assertEquals("c2", h.project.clip("c2")!!.id)
        assertEquals(60.0, h.project.clip("c2")!!.timelineRange.start, 0.0)
    }

    // ---------------------------------------------------------------
    // Trim-then-undo (TL-07: "iptal eski aralığı geri getirir")
    // ---------------------------------------------------------------

    @Test
    fun trim_then_undo_restores_old_range() {
        val original = clip("c1", "v", sourceStart = 10.0, timelineStart = 100.0, duration = 50.0)
        val h = TimelineHistory(project(listOf(original)))

        h.apply(TimelineEdit.TrimClip("c1", TimelineOps.TrimEdge.END, newPosition = 40.0))
        assertEquals(40.0, h.project.clip("c1")!!.sourceRange.end, 0.0)

        h.undo()
        // The old range is back exactly.
        assertEquals(original, h.project.clip("c1"))
        assertEquals(60.0, h.project.clip("c1")!!.sourceRange.end, 0.0)
        assertEquals(150.0, h.project.clip("c1")!!.timelineRange.end, 0.0)
    }

    @Test
    fun trim_undo_follows_linked_audio() {
        val audio = clip("a1", "a", 0.0, 0.0, 100.0)
        val video = clip("v1", "v", 0.0, 0.0, 100.0, linkedAudioId = "a1")
        val h = TimelineHistory(project(listOf(video, audio)))

        h.apply(TimelineEdit.TrimClip("v1", TimelineOps.TrimEdge.END, newPosition = 60.0))
        assertEquals(60.0, h.project.clip("a1")!!.sourceRange.end, 0.0)

        h.undo()
        // The audio's old range is back too (the inverse trim drags
        // the linked audio back by the same delta).
        assertEquals(100.0, h.project.clip("a1")!!.sourceRange.end, 0.0)
        assertEquals(100.0, h.project.clip("a1")!!.timelineRange.end, 0.0)
    }

    @Test
    fun rejected_trim_is_noop_and_records_nothing() {
        val original = clip("c1", "v", 0.0, 0.0, 50.0)
        val h = TimelineHistory(project(listOf(original)))

        // A trim that would empty the clip: rejected, no record.
        h.apply(TimelineEdit.TrimClip("c1", TimelineOps.TrimEdge.END, newPosition = 0.0))
        assertFalse(h.canUndo)
        assertEquals(original, h.project.clip("c1"))
    }

    // ---------------------------------------------------------------
    // Move / add / duplicate / delete round-trips
    // ---------------------------------------------------------------

    @Test
    fun move_then_undo_restores_old_placement() {
        val original = clip("c1", "v", sourceStart = 25.0, timelineStart = 100.0, duration = 40.0)
        val h = TimelineHistory(project(listOf(original)))

        h.apply(TimelineEdit.MoveClip("c1", newTimelineStart = 0.0, newTrackId = "a"))
        assertEquals("a", h.project.clip("c1")!!.trackId)
        assertEquals(0.0, h.project.clip("c1")!!.timelineRange.start, 0.0)

        h.undo()
        assertEquals(original, h.project.clip("c1"))
        assertEquals("v", h.project.clip("c1")!!.trackId)
        assertEquals(100.0, h.project.clip("c1")!!.timelineRange.start, 0.0)
        assertEquals(25.0, h.project.clip("c1")!!.sourceRange.start, 0.0)
    }

    @Test
    fun add_then_undo_removes_clip() {
        val h = TimelineHistory(project(emptyList()))
        h.apply(TimelineEdit.AddClip(clip("c1")))
        assertEquals(1, h.project.clips.size)
        h.undo()
        assertTrue(h.project.clips.isEmpty())
    }

    @Test
    fun delete_then_undo_restores_exact_clip() {
        val original = clip("c1", "v", sourceStart = 7.0, timelineStart = 3.0, duration = 40.0)
        val h = TimelineHistory(project(listOf(original)))
        h.apply(TimelineEdit.RemoveClip("c1"))
        assertTrue(h.project.clips.isEmpty())
        h.undo()
        assertEquals(original, h.project.clip("c1"))
    }

    @Test
    fun duplicate_then_undo_removes_only_the_copy() {
        val original = clip("c1", "v", 5.0, 10.0, 40.0)
        val h = TimelineHistory(project(listOf(original)))
        h.apply(TimelineEdit.DuplicateClip("c1", newId = "c2"))
        assertEquals(2, h.project.clips.size)
        h.undo()
        assertEquals(listOf(original), h.project.clips)
    }

    // ---------------------------------------------------------------
    // Settings transactions (TL-01/TL-02)
    // ---------------------------------------------------------------

    @Test
    fun set_output_then_undo_restores_old_settings() {
        val h = TimelineHistory(project(listOf(clip("c1"))))
        val hdr = TimelineOutput(width = 3840, height = 2160, fps = 24, hdr = true, transfer = "hlg")
        h.apply(TimelineEdit.SetOutput(hdr))
        assertEquals(hdr, h.project.output)
        h.undo()
        assertEquals(TimelineOutput(), h.project.output)
    }

    @Test
    fun set_clip_color_then_undo_restores_old_definition() {
        val original = clip("c1", "v")
        val h = TimelineHistory(project(listOf(original)))
        val def = ClipColorDefinition(encoding = "mi-log", colorSpace = "bt2020")
        h.apply(TimelineEdit.SetClipColor("c1", def))
        assertEquals(def, h.project.clip("c1")!!.colorDefinition)
        h.undo()
        assertEquals(ClipColorDefinition(), h.project.clip("c1")!!.colorDefinition)
    }

    // ---------------------------------------------------------------
    // Sequencing: undo order = reverse apply order
    // ---------------------------------------------------------------

    @Test
    fun undo_order_is_reverse_apply_order() {
        val c1 = clip("c1", "v", 0.0, 0.0, 50.0)
        val c2 = clip("c2", "v", 0.0, 60.0, 50.0)
        val h = TimelineHistory(project(listOf(c1, c2)))

        // 1: trim c1, 2: move c2, 3: split c1.
        h.apply(TimelineEdit.TrimClip("c1", TimelineOps.TrimEdge.END, 30.0))
        h.apply(TimelineEdit.MoveClip("c2", 200.0))
        h.apply(TimelineEdit.SplitClip("c1", frame = 15.0, rightId = "c3"))

        // Undo 3: the split unwinds; c1 is the trimmed clip.
        h.undo()
        assertNull(h.project.clip("c3"))
        assertEquals(30.0, h.project.clip("c1")!!.sourceRange.end, 0.0)
        assertEquals(200.0, h.project.clip("c2")!!.timelineRange.start, 0.0)

        // Undo 2: c2 back at 60.
        h.undo()
        assertEquals(60.0, h.project.clip("c2")!!.timelineRange.start, 0.0)
        assertEquals(30.0, h.project.clip("c1")!!.sourceRange.end, 0.0)

        // Undo 1: c1 back to full.
        h.undo()
        assertEquals(50.0, h.project.clip("c1")!!.sourceRange.end, 0.0)
        assertFalse(h.canUndo)

        // Full round trip: the original project.
        assertEquals(listOf(c1, c2), h.project.clips)
    }

    @Test
    fun new_apply_clears_redo_stack() {
        val c1 = clip("c1", "v", 0.0, 0.0, 50.0)
        val h = TimelineHistory(project(listOf(c1)))
        h.apply(TimelineEdit.MoveClip("c1", 100.0))
        h.undo()
        assertTrue(h.canRedo)
        // A new apply clears redo (the classic undo rule).
        h.apply(TimelineEdit.MoveClip("c1", 200.0))
        assertFalse(h.canRedo)
        assertEquals(200.0, h.project.clip("c1")!!.timelineRange.start, 0.0)
    }

    @Test
    fun noop_apply_does_not_clear_redo() {
        val c1 = clip("c1", "v", 0.0, 0.0, 50.0)
        val h = TimelineHistory(project(listOf(c1)))
        h.apply(TimelineEdit.MoveClip("c1", 100.0))
        h.undo()
        assertTrue(h.canRedo)
        // A no-op apply (missing clip) records nothing and keeps redo.
        h.apply(TimelineEdit.MoveClip("missing", 300.0))
        assertTrue(h.canRedo)
        assertFalse(h.canUndo)
    }

    @Test
    fun redo_after_undo_chain_restores_forward_state() {
        val c1 = clip("c1", "v", 0.0, 0.0, 100.0)
        val h = TimelineHistory(project(listOf(c1)))
        h.apply(TimelineEdit.TrimClip("c1", TimelineOps.TrimEdge.END, 60.0))
        h.apply(TimelineEdit.MoveClip("c1", 50.0))
        val state2 = h.project
        h.undo()
        h.undo()
        h.redo()
        h.redo()
        assertEquals(state2, h.project)
    }

    // ---------------------------------------------------------------
    // Capacity: 100 steps, overflow drops the oldest
    // ---------------------------------------------------------------

    @Test
    fun capacity_is_100_steps_and_drops_oldest() {
        val c1 = clip("c1", "v", 0.0, 0.0, 500.0)
        val h = TimelineHistory(project(listOf(c1)))

        // 100 moves: exactly the capacity, all servable.
        repeat(TimelineHistory.MAX_STEPS) { i ->
            h.apply(TimelineEdit.MoveClip("c1", (i + 1).toDouble()))
        }
        assertEquals(TimelineHistory.MAX_STEPS, 100)
        var undos = 0
        while (h.canUndo) {
            h.undo()
            undos++
        }
        assertEquals(100, undos)
        // All the way back: the clip is at its original position.
        assertEquals(0.0, h.project.clip("c1")!!.timelineRange.start, 0.0)

        // 101 moves: the first falls off; undo bottoms out at move 2.
        val h2 = TimelineHistory(project(listOf(c1)))
        repeat(101) { i ->
            h2.apply(TimelineEdit.MoveClip("c1", (i + 1).toDouble()))
        }
        var undos2 = 0
        while (h2.canUndo) {
            h2.undo()
            undos2++
        }
        assertEquals(100, undos2)
        // Bottomed out at the OLDEST SURVIVING state: position 1 (the
        // first move's result), not the original 0.
        assertEquals(1.0, h2.project.clip("c1")!!.timelineRange.start, 0.0)
    }

    @Test
    fun history_with_store_round_trip_keeps_project_exact() {
        // The session pattern the UI will use: history edits, store
        // saves, reopen loads — the persisted project equals the
        // history's current project (and undoing to the bottom equals
        // the original).
        val original = clip("c1", "v", 10.0, 0.0, 90.0)
        val h = TimelineHistory(project(listOf(original)))
        h.apply(TimelineEdit.SplitClip("c1", frame = 45.0, rightId = "c2"))
        h.apply(TimelineEdit.TrimClip("c2", TimelineOps.TrimEdge.END, 80.0))
        h.apply(TimelineEdit.SetOutput(TimelineOutput(fps = 24)))

        // Round-trip the CURRENT state through the store.
        val rendered = TimelineStore.mergeInto(null, h.project)
        val reloaded = TimelineStore.parseTimeline(rendered)
        assertEquals(h.project, reloaded)
    }
}
