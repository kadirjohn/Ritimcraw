package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.engine.PtsTimeline
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PrecisionScrub tests (owner request 2026-11-11 section 2): the pure
 * long-press-to-frame-mode decision matrix and the dp→frame drag mapping,
 * JVM-testable without Compose or the native lib.
 */
class PrecisionScrubTest {

    // ---- engage decision matrix ----

    @Test
    fun engage_rejects_when_transport_disabled() {
        // No timeline yet (session still opening): nothing to step on.
        assertEquals(
            PrecisionScrub.Engage.Reject,
            PrecisionScrub.onEngage(
                isPlaying = false, enabled = false,
                frameCount = 300, canRender = true,
            ),
        )
    }

    @Test
    fun engage_rejects_single_frame_clip() {
        // A still photo: frame stepping has no meaning, the long-press is a no-op.
        assertEquals(
            PrecisionScrub.Engage.Reject,
            PrecisionScrub.onEngage(
                isPlaying = false, enabled = true,
                frameCount = 1, canRender = true,
            ),
        )
    }

    @Test
    fun engage_rejects_when_render_path_unavailable() {
        assertEquals(
            PrecisionScrub.Engage.Reject,
            PrecisionScrub.onEngage(
                isPlaying = false, enabled = true,
                frameCount = 300, canRender = false,
            ),
        )
    }

    @Test
    fun engage_while_playing_pauses_first() {
        // The long-press enters precision mode AND pauses the wall clock
        // (the same pause entry as the transport's pause button, so the
        // progressive settle applies) — the before/after hold precedent.
        assertEquals(
            PrecisionScrub.Engage.PauseAndEnter,
            PrecisionScrub.onEngage(
                isPlaying = true, enabled = true,
                frameCount = 300, canRender = true,
            ),
        )
    }

    @Test
    fun engage_while_paused_enters_in_place() {
        assertEquals(
            PrecisionScrub.Engage.Enter,
            PrecisionScrub.onEngage(
                isPlaying = false, enabled = true,
                frameCount = 300, canRender = true,
            ),
        )
    }

    // ---- drag mapping: the owner's example ----

    @Test
    fun one_cm_of_drag_steps_30_frames() {
        // The owner's example: ~1 cm ≈ 30 frames. 1 cm = 62.992 dp
        // (Android: 160 dp = 1 inch = 2.54 cm). From frame 0 of a
        // 300-frame clip, a 1 cm drag lands on frame 30. roundToInt also
        // absorbs the float division noise (DP_PER_CM / DP_PER_FRAME is
        // 30.0000x, never exactly 30.0f).
        assertEquals(
            30,
            PrecisionScrub.frameForDrag(
                startFrame = 0,
                draggedDp = PrecisionScrub.DP_PER_CM,
                frameCount = 300,
            ),
        )
    }

    @Test
    fun sensitivity_is_about_two_dp_per_frame() {
        // The reciprocal sensitivity: at 30 frames/cm, one frame ≈ 2.1 dp.
        assertEquals(2.1f, PrecisionScrub.DP_PER_FRAME, 0.01f)
    }

    @Test
    fun negative_drag_steps_backwards() {
        // Dragging left from frame 100 by 1 cm lands 30 frames earlier.
        assertEquals(
            70,
            PrecisionScrub.frameForDrag(
                startFrame = 100,
                draggedDp = -PrecisionScrub.DP_PER_CM,
                frameCount = 300,
            ),
        )
    }

    @Test
    fun drag_clamps_at_both_ends() {
        // Past the first frame → 0; past the last → frameCount - 1.
        assertEquals(
            0,
            PrecisionScrub.frameForDrag(
                startFrame = 5, draggedDp = -PrecisionScrub.DP_PER_CM * 10f, frameCount = 300,
            ),
        )
        assertEquals(
            299,
            PrecisionScrub.frameForDrag(
                startFrame = 295, draggedDp = PrecisionScrub.DP_PER_CM * 10f, frameCount = 300,
            ),
        )
    }

    // ---- quantization: never between frames ----

    @Test
    fun less_than_half_a_step_holds_the_frame() {
        // Round-to-nearest: under half a DP_PER_FRAME of accumulated drag
        // stays on the current frame — the position can never land between
        // frames, and micro-wiggles do not step.
        assertEquals(
            100,
            PrecisionScrub.frameForDrag(
                startFrame = 100,
                draggedDp = PrecisionScrub.DP_PER_FRAME * 0.4f,
                frameCount = 300,
            ),
        )
    }

    @Test
    fun half_a_step_rounds_to_the_next_frame() {
        // Exactly half a step commits the step (roundToInt, half away from
        // zero) — the first step needs half a DP_PER_FRAME of drag.
        assertEquals(
            101,
            PrecisionScrub.frameForDrag(
                startFrame = 100,
                draggedDp = PrecisionScrub.DP_PER_FRAME * 0.5f,
                frameCount = 300,
            ),
        )
    }

    @Test
    fun zero_drag_stays_put() {
        assertEquals(
            100,
            PrecisionScrub.frameForDrag(startFrame = 100, draggedDp = 0f, frameCount = 300),
        )
    }

    @Test
    fun total_drag_equals_summed_event_deltas() {
        // The caller passes the TOTAL dp since gesture start, so three
        // separate 1 cm drags and one 3 cm drag land identically: no drift
        // can accumulate across events.
        val byEvents = PrecisionScrub.frameForDrag(
            startFrame = 10,
            draggedDp = PrecisionScrub.DP_PER_CM + PrecisionScrub.DP_PER_CM + PrecisionScrub.DP_PER_CM,
            frameCount = 300,
        )
        val asOne = PrecisionScrub.frameForDrag(
            startFrame = 10,
            draggedDp = PrecisionScrub.DP_PER_CM * 3f,
            frameCount = 300,
        )
        assertEquals(asOne, byEvents)
        assertEquals(100, asOne)
    }

    // ---- readout: fps fallbacks and VFR ----

    @Test
    fun readout_timecode_at_24fps() {
        // Uniform 24 fps spacing (multiply before divide so frame 72 is
        // exactly 3,000,000 us): frame 72 reads 3 s in.
        val uniform24 = LongArray(300) { it * 1_000_000L / 24L }
        assertEquals(
            "00:00:03:00",
            PrecisionScrub.readoutTimecode(frameIndex = 72, ptsUs = uniform24, fps = 24f),
        )
    }

    @Test
    fun readout_timecode_at_29_97fps_uses_rounding() {
        // 29.97 (NTSC) rounds to the nominal 30 for the :FF field; the
        // underlying pts spacing is the real 29.97 cadence, so the readout
        // tracks the recorded timeline. Frame 30's pts is 1.001 s → at
        // nominal 30 that is 00:00:01:00.
        val ntsc = LongArray(300) { it * 1_000_000L * 1001L / 30000L }
        assertEquals(
            "00:00:01:00",
            PrecisionScrub.readoutTimecode(frameIndex = 30, ptsUs = ntsc, fps = 29.97f),
        )
    }

    @Test
    fun readout_timecode_at_30fps() {
        val uniform30 = LongArray(300) { it * 1_000_000L / 30L }
        // Frame 90 = 3 s exactly.
        assertEquals(
            "00:00:03:00",
            PrecisionScrub.readoutTimecode(frameIndex = 90, ptsUs = uniform30, fps = 30f),
        )
    }

    @Test
    fun readout_timecode_falls_back_to_24_when_fps_zero() {
        // A metadata-less clip (fps not derivable): the readout still
        // shows a stable timecode, at Format's 24 fps default.
        val uniform24 = LongArray(300) { it * 1_000_000L / 24L }
        assertEquals(
            "00:00:03:00",
            PrecisionScrub.readoutTimecode(frameIndex = 72, ptsUs = uniform24, fps = 0f),
        )
    }

    @Test
    fun readout_timecode_vfr_uses_real_pts() {
        // Variable frame rate: frames 0..3 at 24 fps spacing, then a long
        // gap. The readout follows the RECORDED timestamps (exact), only
        // the :FF field is nominal at the average rate.
        val vfr = longArrayOf(
            0L,
            41_667L,          // ~1/24 s
            83_333L,          // ~2/24 s
            125_000L,         // ~3/24 s
            1_000_000L,       // a 1 s gap — the 5th frame is 1 s in
        )
        // Frame 4's real pts is 1_000_000: the SS field shows 01 even
        // though the frame index suggests ~4/24 s.
        assertEquals(
            "00:00:01:00",
            PrecisionScrub.readoutTimecode(
                frameIndex = 4, ptsUs = vfr, fps = 24f,
            ),
        )
    }

    @Test
    fun readout_timecode_clamps_out_of_range_index() {
        // A defensive clamp: the pipeline indexes are always valid, but
        // the readout never crashes on one that slips through.
        val uniform24 = LongArray(10) { it * 1_000_000L / 24L }
        assertEquals(
            "00:00:00:09",
            PrecisionScrub.readoutTimecode(frameIndex = 99, ptsUs = uniform24, fps = 24f),
        )
    }

    // ---- frame ↔ slider-fraction roundtrip (the seek request carries
    // frame N's position: the fraction the transport derives from frame N
    // maps back to frame N, so a release at frame N leaves the bar exactly
    // on it) ----

    @Test
    fun fraction_roundtrip_at_24fps() {
        val uniform24 = LongArray(300) { it * 1_000_000L / 24L }
        for (frame in intArrayOf(0, 1, 72, 150, 299)) {
            val fraction = PtsTimeline.fractionForFrame(uniform24, frame)
            assertEquals(frame, PtsTimeline.frameForFraction(uniform24, fraction))
        }
    }

    @Test
    fun fraction_roundtrip_at_29_97fps() {
        // Real NTSC cadence: the roundtrip holds on the recorded timeline
        // regardless of the average rate (the transport is frame-indexed).
        val ntsc = LongArray(300) { it * 1_000_000L * 1001L / 30000L }
        for (frame in intArrayOf(0, 1, 30, 150, 299)) {
            val fraction = PtsTimeline.fractionForFrame(ntsc, frame)
            assertEquals(frame, PtsTimeline.frameForFraction(ntsc, fraction))
        }
    }

    @Test
    fun fraction_roundtrip_at_30fps() {
        val uniform30 = LongArray(300) { it * 1_000_000L / 30L }
        for (frame in intArrayOf(0, 1, 90, 150, 299)) {
            val fraction = PtsTimeline.fractionForFrame(uniform30, frame)
            assertEquals(frame, PtsTimeline.frameForFraction(uniform30, fraction))
        }
    }

    // ---- release ----

    @Test
    fun release_keeps_the_frame_and_settles() {
        // The owner's spec: release KEEPS the selected frame — pause
        // behavior, then the existing progressive full-res settle. One
        // decision: unlike the before/after hold there is nothing to
        // restore (no grade swap, no playback resume).
        assertEquals(
            PrecisionScrub.Release.KeepFrameAndSettle,
            PrecisionScrub.onRelease(),
        )
    }
}
