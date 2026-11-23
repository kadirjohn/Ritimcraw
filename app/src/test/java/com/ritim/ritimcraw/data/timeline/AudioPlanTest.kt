package com.ritim.ritimcraw.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioPlan tests (C1, the timeline audio reference model):
 *  - sampleAt identity: the AUD-06 composition equals its closed form
 *    round(g * R / fps), and 30 fps divides 44.1/48 kHz exactly.
 *  - 44.1/48 mapping edges: both rates place a clip's edges on exact
 *    sample counts; the two-rate window arithmetic is honest.
 *  - trim windows: sourceRange trims shrink the SOURCE window and
 *    timelineRange moves shift the MIX span, independently.
 *  - passthrough identity: equal source/mix rates pull source sample
 *    start + offset, sample for sample (no resampler runs).
 *  - cross-rate pull: a 44.1 source in a 48 mix pulls by timestamp
 *    (offset 0 hits the window start exactly).
 *  - fade endpoints: in-ramp 0 -> 1 across fadeIn, out-ramp 1 -> 0 at
 *    the end edge, overlap multiplies, stored intent clamps to the span.
 *  - pan law: constant-power — center sqrt(2)/2 per channel (-3.01 dB,
 *    unity power sum at EVERY pan), full left/right hand 1.0/0.0.
 *  - gain -> pan -> fade chain: mute short-circuits, dB -> linear.
 *  - saturation: hard clip at +/-1.0, never wraps.
 *  - mix sum + master: sum then master gain, unsaturated output,
 *    master mute silences.
 *  - bound vs independent shapes: a bound clip's audio follows the
 *    video's move by id (the join survives the move); an independent
 *    clip places alone; mixPlan places both IDENTICALLY and records
 *    boundVideoId as the join fact.
 *  - honesty gates: no source id -> SOURCE_MISSING; unknown rate ->
 *    RATE_UNKNOWN (never a 48000 guess); a skipped clip's span renders
 *    silent but the movie still runs to the audio-resolved length.
 *  - split rejoin: two split pieces' windows tile the unsplit window.
 */
class AudioPlanTest {

    // --- builders -------------------------------------------------

    private fun videoClip(
        id: String,
        start: Double,
        end: Double,
        linkedAudioId: String? = null,
    ) = TimelineClip(
        id = id,
        trackId = "t0",
        sourceId = "src-$id",
        sourceRange = FrameRange(0.0, end - start),
        timelineRange = FrameRange(start, end),
        linkedAudioId = linkedAudioId,
    )

    private fun audioClip(
        id: String,
        sourceId: String,
        start: Double,
        end: Double,
        trackId: String = "t1",
    ) = TimelineClip(
        id = id,
        trackId = trackId,
        sourceId = sourceId,
        sourceRange = FrameRange(0.0, end - start),
        timelineRange = FrameRange(start, end),
    )

    private fun project(clips: List<TimelineClip>, fps: Int = 30): TimelineProject {
        val tracks = listOf(
            TimelineTrack(id = "t0", kind = TrackKind.VIDEO, index = 0),
            TimelineTrack(id = "t1", kind = TrackKind.AUDIO, index = 1),
            TimelineTrack(id = "t2", kind = TrackKind.TEXT, index = 2),
        )
        return TimelineProject(
            output = TimelineOutput(fps = fps),
            tracks = tracks,
            clips = clips,
        )
    }

    // --- 1. sampleAt: the mapping rule ---------------------------

    @Test
    fun `sampleAt equals the closed form round g times rate over fps`() {
        // The AUD-06 composition (exportStartMs -> msToSamples) and the
        // closed form round(g * R / fps) are the same mapping.
        for (g in listOf(0.0, 1.0, 7.0, 120.5, 999.0)) {
            for (rate in listOf(44100, 48000)) {
                for (fps in listOf(24, 30)) {
                    assertEquals(
                        kotlin.math.round(g * rate / fps).toLong(),
                        AudioPlan.sampleAt(g, rate, fps),
                    )
                }
            }
        }
    }

    @Test
    fun `30 fps divides both tested rates exactly - no edge ever rounds`() {
        // 30 fps: one frame = 1470 samples at 44.1 kHz, 1600 at 48 kHz.
        assertEquals(1470L, AudioPlan.sampleAt(1.0, 44100, 30))
        assertEquals(1600L, AudioPlan.sampleAt(1.0, 48000, 30))
        assertEquals(2940L, AudioPlan.sampleAt(2.0, 44100, 30))
        assertEquals(3200L, AudioPlan.sampleAt(2.0, 48000, 30))
    }

    @Test
    fun `sampleAt is monotonic non-decreasing`() {
        var prev = Long.MIN_VALUE
        for (g in 0..500) {
            val s = AudioPlan.sampleAt(g.toDouble(), 44100, 29)
            assertTrue(s >= prev)
            prev = s
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sampleAt rejects a non-positive rate`() {
        AudioPlan.sampleAt(10.0, 0, 30)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sampleAt rejects a non-positive fps`() {
        AudioPlan.sampleAt(10.0, 48000, 0)
    }

    // --- 2. placement: the two windows ---------------------------

    @Test
    fun `placement maps both windows at 48 kHz - the exact counts`() {
        // A clip at frames [90, 270) using source [0, 180) at 48 kHz:
        // source window [0, 288000), mix span [144000, 432000).
        val clip = audioClip("a1", "src-a", 90.0, 270.0)
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        assertEquals(0L, p.sourceStartSample)
        assertEquals(288_000L, p.sourceEndSample)
        assertEquals(144_000L, p.mixStartSample)
        assertEquals(432_000L, p.mixEndSample)
        assertEquals(288_000L, p.sourceSampleCount)
        assertEquals(288_000L, p.mixSampleCount)
    }

    @Test
    fun `placement maps the same clip at 44_1 kHz - the QA-12 shape`() {
        // Same frames [90, 270), source [0, 180) at 44.1 kHz, mix 48:
        // source window [0, 264600) at the source's own rate; the mix
        // span is still [144000, 432000) at the mix rate.
        val clip = audioClip("a1", "src-a", 90.0, 270.0)
        val p = AudioPlan.placementOf(clip, 44100, 48000, 30)
        assertEquals(0L, p.sourceStartSample)
        assertEquals(264_600L, p.sourceEndSample)
        assertEquals(144_000L, p.mixStartSample)
        assertEquals(432_000L, p.mixEndSample)
    }

    @Test
    fun `a sourceRange trim shrinks the source window and shifts its start`() {
        // Trim 30 frames off the head of the source: sourceRange moves
        // [0,180) -> [30,180); the source window loses its first
        // 30 frames' worth of samples; the timeline slot follows.
        val untrimmed = audioClip("a1", "src-a", 90.0, 270.0)
        val trimmed = untrimmed.copy(
            sourceRange = FrameRange(30.0, 180.0),
            timelineRange = FrameRange(120.0, 270.0),
        )
        val p = AudioPlan.placementOf(trimmed, 48000, 48000, 30)
        assertEquals(48_000L, p.sourceStartSample)
        assertEquals(288_000L, p.sourceEndSample)
        assertEquals(192_000L, p.mixStartSample)
        assertEquals(432_000L, p.mixEndSample)
        // The trim kept exactly 150 frames of source audio.
        assertEquals(240_000L, p.sourceSampleCount)
        assertEquals(240_000L, p.mixSampleCount)
        // And it is the same window the ops' trim would produce.
        val byOps = TimelineOps.trim(
            project(listOf(untrimmed)),
            "a1",
            TimelineOps.TrimEdge.START,
            30.0,
        )!!
        assertEquals(
            p,
            AudioPlan.placementOf(byOps.clip("a1")!!, 48000, 48000, 30),
        )
    }

    @Test
    fun `a timeline move shifts the mix span and never the source window`() {
        // Moving on the timeline changes WHERE it plays, never WHICH
        // source samples it uses (TimelineOps.move's contract).
        val clip = audioClip("a1", "src-a", 90.0, 270.0)
        val moved = TimelineOps.move(project(listOf(clip)), "a1", 150.0).clip("a1")!!
        val p = AudioPlan.placementOf(moved, 48000, 48000, 30)
        assertEquals(0L, p.sourceStartSample)
        assertEquals(288_000L, p.sourceEndSample)
        // The whole clip moved: [90,270) -> [150,330).
        assertEquals(240_000L, p.mixStartSample)
        assertEquals(528_000L, p.mixEndSample)
        assertEquals(288_000L, p.mixSampleCount)
    }

    @Test
    fun `split pieces tile the unsplit window exactly`() {
        // Split at the midpoint: the two pieces' source windows and
        // mix spans are contiguous and reproduce the unsplit windows.
        val clip = audioClip("a1", "src-a", 90.0, 270.0)
        val split = TimelineOps.split(project(listOf(clip)), "a1", 180.0, "a1-r")!!
        val left = split.clip("a1")!!
        val right = split.clip("a1-r")!!
        val pl = AudioPlan.placementOf(left, 48000, 48000, 30)
        val pr = AudioPlan.placementOf(right, 48000, 48000, 30)
        val whole = AudioPlan.placementOf(clip, 48000, 48000, 30)
        assertEquals(whole.sourceStartSample, pl.sourceStartSample)
        assertEquals(whole.sourceEndSample, pr.sourceEndSample)
        assertEquals(whole.mixStartSample, pl.mixStartSample)
        assertEquals(whole.mixEndSample, pr.mixEndSample)
        assertEquals(pl.sourceEndSample, pr.sourceStartSample)
        assertEquals(pl.mixEndSample, pr.mixStartSample)
    }

    // --- 3. the pull: source sample at mix offset ---------------

    @Test
    fun `equal rates are the identity - mix offset pulls start plus offset`() {
        val clip = audioClip("a1", "src-a", 90.0, 270.0)
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        for (offset in listOf(0L, 1L, 1600L, 159_999L)) {
            assertEquals(
                p.sourceStartSample + offset,
                AudioPlan.sourceSampleAtMixOffset(clip, p, offset, 30),
            )
        }
    }

    @Test
    fun `a 44_1 source in a 48 mix pulls by timestamp`() {
        // Offset 0 hits the source window start exactly; offset 1600
        // (one mix frame) lands at the source sample of the same real
        // time: 1600 mix samples = 1/30 s = 1470 source samples.
        val clip = audioClip("a1", "src-a", 90.0, 270.0)
        val p = AudioPlan.placementOf(clip, 44100, 48000, 30)
        assertEquals(0L, AudioPlan.sourceSampleAtMixOffset(clip, p, 0L, 30))
        assertEquals(1470L, AudioPlan.sourceSampleAtMixOffset(clip, p, 1600L, 30))
        // The pull stays inside the source window at the last offset.
        val last = p.mixSampleCount - 1
        assertTrue(AudioPlan.sourceSampleAtMixOffset(clip, p, last, 30) < p.sourceEndSample)
    }

    @Test
    fun `the pull honors a trimmed source head`() {
        // sourceRange [30,180): offset 0 pulls source sample 48000,
        // not 0 — the trims decide which source samples play.
        val clip = audioClip("a1", "src-a", 120.0, 270.0)
            .copy(sourceRange = FrameRange(30.0, 180.0))
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        assertEquals(48_000L, AudioPlan.sourceSampleAtMixOffset(clip, p, 0L, 30))
    }

    // --- 4. fades ------------------------------------------------

    @Test
    fun `fade in ramps 0 to 1 across fadeIn and stays 1 after`() {
        // 100 ms fade at 48 kHz = 4800 samples; clip 200 frames long.
        val clip = audioClip("a1", "src-a", 0.0, 200.0)
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        val state = ClipAudioState(fadeInMs = 100)
        assertEquals(0f, AudioPlan.fadeGain(state, p, 0L))
        assertEquals(0.25f, AudioPlan.fadeGain(state, p, 1200L))
        assertEquals(0.5f, AudioPlan.fadeGain(state, p, 2400L))
        assertEquals(1f, AudioPlan.fadeGain(state, p, 4800L))
        assertEquals(1f, AudioPlan.fadeGain(state, p, 100_000L))
    }

    @Test
    fun `fade out reaches zero at the exclusive end edge`() {
        // 100 ms fade out on a 200-frame clip: the envelope is 1 until
        // end - 4800, then falls; the LAST real sample keeps
        // 1/4800, and the curve is continuous to 0 at the end edge.
        val clip = audioClip("a1", "src-a", 0.0, 200.0)
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        val state = ClipAudioState(fadeOutMs = 100)
        val span = p.mixSampleCount
        assertEquals(1f, AudioPlan.fadeGain(state, p, 0L))
        assertEquals(1f, AudioPlan.fadeGain(state, p, span - 4800L))
        assertTrue(AudioPlan.fadeGain(state, p, span - 2400L) < 1f)
        val last = AudioPlan.fadeGain(state, p, span - 1)
        assertTrue(last > 0f && last <= 1f / 4800f + 1e-9f)
        // At the exclusive end (offset == span) the envelope is 0 —
        // the consumer never renders there, the endpoint pins the curve.
        assertEquals(0f, AudioPlan.fadeGain(state, p, span))
    }

    @Test
    fun `overlapping fades multiply`() {
        // Fade in AND out on a short clip: the envelopes overlap in
        // the middle; the product is the amplitude, never their sum.
        val clip = audioClip("a1", "src-a", 0.0, 30.0) // 1 s span
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        val state = ClipAudioState(fadeInMs = 1000, fadeOutMs = 1000)
        // Midpoint: in = 0.5, out = 0.5 -> product 0.25.
        assertEquals(0.25f, AudioPlan.fadeGain(state, p, 24_000L))
    }

    @Test
    fun `a fade longer than the clip clamps to the span - stored intent`() {
        // 10 s fade on a 1 s clip: the ramp spans the whole clip;
        // the stored value is untouched (trim never rewrites it).
        // The clamped ramp reaches unity AT the exclusive end edge;
        // the last REAL sample keeps (span-1)/span — the honest
        // endpoint (the fade-out test pins the same rule at the tail).
        val clip = audioClip("a1", "src-a", 0.0, 30.0)
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        val state = ClipAudioState(fadeInMs = 10_000)
        val span = p.mixSampleCount
        assertEquals(0f, AudioPlan.fadeGain(state, p, 0L))
        assertEquals(0.5f, AudioPlan.fadeGain(state, p, span / 2), 1e-6f)
        // Float arithmetic: the envelope at span-1 is the float value
        // of (span-1)/span — assert against the same float math.
        assertEquals(
            ((span - 1).toFloat() / span.toFloat()),
            AudioPlan.fadeGain(state, p, span - 1),
        )
        assertEquals(1f, AudioPlan.fadeGain(state, p, span))
    }

    // --- 5. pan law ----------------------------------------------

    @Test
    fun `pan center is constant-power -3_01 dB with unity power sum`() {
        val center = AudioPlan.panGains(0f)
        // Float math: sqrt(2)/2 to float precision, not double.
        assertEquals((kotlin.math.sqrt(2.0) / 2.0).toFloat(), center.left, 1e-8f)
        assertEquals((kotlin.math.sqrt(2.0) / 2.0).toFloat(), center.right, 1e-8f)
        // -3.01 dB per channel: 20*log10(sqrt(2)/2) = -3.0103.
        val db = 20.0 * kotlin.math.log10(center.left.toDouble())
        assertEquals(-3.01, db, 0.01)
    }

    @Test
    fun `pan power sum is unity at every position - constant loudness`() {
        // The REASON for the law: left^2 + right^2 = 1 everywhere, so
        // sweeping the knob never changes perceived loudness.
        var pan = -1.0f
        while (pan <= 1.0f) {
            val g = AudioPlan.panGains(pan)
            assertEquals(1.0, (g.left * g.left + g.right * g.right).toDouble(), 1e-6)
            pan += 0.01f
        }
    }

    @Test
    fun `full left and full right hand the whole signal to one channel`() {
        val left = AudioPlan.panGains(-1f)
        assertEquals(1f, left.left)
        // cos(pi/2) in floating math is ~6e-17, not exact 0 — the
        // float-tolerance assertion is the honest one.
        assertEquals(0f, left.right, 1e-6f)
        val right = AudioPlan.panGains(1f)
        assertEquals(0f, right.left, 1e-6f)
        assertEquals(1f, right.right)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `pan outside -1 to 1 is rejected`() {
        AudioPlan.panGains(1.5f)
    }

    // --- 6. the per-clip chain and saturation --------------------

    @Test
    fun `clipGains chains gain pan fade and mute short-circuits`() {
        val clip = audioClip("a1", "src-a", 0.0, 200.0)
        val p = AudioPlan.placementOf(clip, 48000, 48000, 30)
        // +20 dB (exactly 10x), full left, at fade midpoint 0.5:
        // left = 10 * 1 * 0.5 = 5, right = 10 * ~0 * 0.5 = ~0.
        val state = ClipAudioState(gainDb = 20f, pan = -1f, fadeInMs = 100)
        val g = AudioPlan.clipGains(state, p, 2400L)
        assertEquals(5f, g.left, 1e-5f)
        assertEquals(0f, g.right, 1e-5f)
        // Mute wins over everything.
        val muted = AudioPlan.clipGains(state.copy(muted = true), p, 2400L)
        assertEquals(0f, muted.left)
        assertEquals(0f, muted.right)
    }

    @Test
    fun `linearGain converts dBFS to amplitude`() {
        assertEquals(1f, AudioPlan.linearGain(0f), 1e-9f)
        assertEquals(2f, AudioPlan.linearGain(6.0206f), 1e-4f)
        assertEquals(0.5f, AudioPlan.linearGain(-6.0206f), 1e-4f)
        assertEquals(10f, AudioPlan.linearGain(20f), 1e-6f)
    }

    @Test
    fun `saturation hard-clips at full scale and never wraps`() {
        assertEquals(1f, AudioPlan.saturate(1.5f))
        assertEquals(-1f, AudioPlan.saturate(-1.5f))
        assertEquals(0.5f, AudioPlan.saturate(0.5f))
        assertEquals(1f, AudioPlan.saturate(Float.MAX_VALUE))
        assertEquals(-1f, AudioPlan.saturate(-Float.MAX_VALUE))
    }

    // --- 7. the mix sum and master -------------------------------

    @Test
    fun `mixSum sums then applies master gain and stays unsaturated`() {
        // Two unity clips + two unity clips = 4.0 per channel with a
        // +6 dB master = 8.0 — UNSATURATED (the caller clamps).
        val sum = AudioPlan.mixSum(
            List(4) { StereoSample(1f, 1f) },
            MasterAudioState(gainDb = 6.0206f),
        )
        assertEquals(8f, sum.left, 1e-3f)
        assertEquals(8f, sum.right, 1e-3f)
    }

    @Test
    fun `master mute silences the whole mix`() {
        val sum = AudioPlan.mixSum(
            listOf(StereoSample(1f, -1f)),
            MasterAudioState(muted = true),
        )
        assertEquals(0f, sum.left)
        assertEquals(0f, sum.right)
    }

    // --- 8. bound vs independent shapes --------------------------

    @Test
    fun `a bound clip moves with its video - the join survives the move`() {
        // QA-13: bound audio cut and MOVED. The join is by id, so the
        // video's linkedAudioId still resolves after both halves move;
        // the audio places at its own timelineRange wherever that is.
        val audio = audioClip("a-audio", "src-v", 0.0, 180.0)
        val video = videoClip("a-video", 0.0, 180.0, linkedAudioId = "a-audio")
        var p = project(listOf(video, audio))
        assertTrue(AudioPlan.isIndependent(p, "a-audio").not())
        // Move the VIDEO half; the audio clip's id join still binds.
        p = TimelineOps.move(p, "a-video", 300.0)
        val bound = AudioPlan.boundVideoOf(p, "a-audio")
        assertEquals("a-video", bound?.id)
        // The audio half moved on its own (an independent move of the
        // pair's audio side keeps the link too — binding is by id).
        p = TimelineOps.move(p, "a-audio", 500.0)
        assertEquals("a-video", AudioPlan.boundVideoOf(p, "a-audio")?.id)
        assertTrue(AudioPlan.isIndependent(p, "a-audio").not())
    }

    @Test
    fun `an independent clip places alone with a null bound video`() {
        val music = audioClip("m1", "src-music", 60.0, 600.0)
        val p = project(listOf(music))
        assertNull(AudioPlan.boundVideoOf(p, "m1"))
        assertTrue(AudioPlan.isIndependent(p, "m1"))
    }

    @Test
    fun `duplicate drops the link - the copy is independent`() {
        // TimelineOps.duplicate nulls linkedAudioId: a duplicated video
        // half binds nothing (the model does not guess a second audio
        // clip into existence).
        val video = videoClip("v1", 0.0, 180.0, linkedAudioId = "a1")
        val audio = audioClip("a1", "src-v1", 0.0, 180.0)
        val p = TimelineOps.duplicate(project(listOf(video, audio)), "v1", "v1-copy")
        assertNull(AudioPlan.boundVideoOf(p, "a1")?.takeIf { it.id == "v1-copy" })
        assertEquals("v1", AudioPlan.boundVideoOf(p, "a1")?.id)
        assertTrue(AudioPlan.isIndependent(p, "a1").not())
    }

    @Test
    fun `mixPlan places bound and independent clips identically`() {
        // One bound pair + one independent music clip: BOTH land in the
        // plan's placed list, in timeline order, with boundVideoId the
        // only difference — placement is uniform (the plan doc §4).
        val audio = audioClip("a-audio", "src-v", 0.0, 180.0)
        val video = videoClip("a-video", 0.0, 180.0, linkedAudioId = "a-audio")
        val music = audioClip("m1", "src-music", 90.0, 600.0)
        val plan = AudioPlan.mixPlan(
            project(listOf(video, audio, music)),
            states = { ClipAudioState.UNITY },
            sourceRateHz = { 48000 },
        )
        assertEquals(2, plan.placed.size)
        assertEquals("a-audio", plan.placed[0].clipId)
        assertEquals("a-video", plan.placed[0].boundVideoId)
        assertEquals("m1", plan.placed[1].clipId)
        assertNull(plan.placed[1].boundVideoId)
        assertTrue(plan.skips.isEmpty())
        // The mix length: the music tail (600 frames) resolves the
        // project length — the sound lasts to its own end (QA-12).
        assertEquals(600L * 1600L, plan.totalMixSamples)
    }

    // --- 9. mixPlan honesty gates --------------------------------

    @Test
    fun `a clip with no source id is skipped SOURCE_MISSING`() {
        val orphan = audioClip("o1", "src-o", 0.0, 90.0).copy(sourceId = null)
        val plan = AudioPlan.mixPlan(
            project(listOf(orphan)),
            states = { ClipAudioState.UNITY },
            sourceRateHz = { 48000 },
        )
        assertTrue(plan.placed.isEmpty())
        assertEquals(1, plan.skips.size)
        assertEquals(AudioSkip.Reason.SOURCE_MISSING, plan.skips[0].reason)
        assertEquals("o1", plan.skips[0].clipId)
    }

    @Test
    fun `an unknown source rate is skipped RATE_UNKNOWN - never a 48000 guess`() {
        // AUD-06: never assume a fixed 48 kHz. A null rate lookup
        // skips the clip; its span renders silent, the movie survives.
        val clip = audioClip("a1", "src-a", 0.0, 180.0)
        val plan = AudioPlan.mixPlan(
            project(listOf(clip)),
            states = { ClipAudioState.UNITY },
            sourceRateHz = { null },
        )
        assertTrue(plan.placed.isEmpty())
        assertEquals(AudioSkip.Reason.RATE_UNKNOWN, plan.skips[0].reason)
        // The movie still runs to the resolved length — the skip does
        // not shrink it (the black-skip-span twin).
        assertEquals(180L * 1600L, plan.totalMixSamples)
    }

    @Test
    fun `a zero-duration clip contributes nothing - no entry no skip`() {
        val zero = audioClip("z1", "src-z", 0.0, 0.0)
        val plan = AudioPlan.mixPlan(
            project(listOf(zero)),
            states = { ClipAudioState.UNITY },
            sourceRateHz = { 48000 },
        )
        assertTrue(plan.placed.isEmpty())
        assertTrue(plan.skips.isEmpty())
    }

    @Test
    fun `the mix plan is sorted by timeline start`() {
        val late = audioClip("late", "src-l", 500.0, 600.0)
        val early = audioClip("early", "src-e", 0.0, 100.0)
        val plan = AudioPlan.mixPlan(
            project(listOf(late, early)),
            states = { ClipAudioState.UNITY },
            sourceRateHz = { 48000 },
        )
        assertEquals(listOf("early", "late"), plan.placed.map { it.clipId })
    }

    @Test
    fun `clipAudioState of maps pan and mute from the persisted row`() {
        // pan/muted landed on AudioClip (2026-11-23): they ride the row.
        val row = com.ritim.ritimcraw.data.audio.AudioClip(
            id = "a1",
            sourceId = "src-a",
            sampleRateHz = 48000,
            pan = -0.5f,
            muted = true,
        )
        val state = ClipAudioState.of(row)
        assertEquals(-0.5f, state.pan)
        assertTrue(state.muted)
    }

    @Test
    fun `detached and removed sources skip LINK_NOT_ATTACHED - never resurrect`() {
        // AUD-02 / QA-13: the link gate. DETACHED, REMOVED and
        // LINKED_EDITED never place the ORIGINAL audio; the span renders
        // silent and the movie still runs to its resolved length.
        val det = audioClip("d1", "src-d", 0.0, 30.0)
        val rem = audioClip("r1", "src-r", 30.0, 60.0)
        val edi = audioClip("e1", "src-e", 60.0, 90.0)
        val att = audioClip("a1", "src-a", 90.0, 120.0)
        val links = mapOf(
            "src-d" to com.ritim.ritimcraw.data.audio.AudioLinkState.DETACHED,
            "src-r" to com.ritim.ritimcraw.data.audio.AudioLinkState.REMOVED,
            "src-e" to com.ritim.ritimcraw.data.audio.AudioLinkState.LINKED_EDITED,
        )
        val plan = AudioPlan.mixPlan(
            project(listOf(det, rem, edi, att)),
            states = { ClipAudioState.UNITY },
            sourceRateHz = { 48000 },
            linkState = { links[it] ?: com.ritim.ritimcraw.data.audio.AudioLinkState.ATTACHED },
        )
        assertEquals(listOf("a1"), plan.placed.map { it.clipId })
        assertEquals(listOf("d1", "r1", "e1"), plan.skips.map { it.clipId })
        assertTrue(plan.skips.all { it.reason == AudioSkip.Reason.LINK_NOT_ATTACHED })
        assertEquals(120L * 1600L, plan.totalMixSamples)
    }

    @Test
    fun `the wired mixPlan joins states and link gate from the AudioProject`() {
        val a = audioClip("a1", "src-a", 0.0, 30.0)
        val b = audioClip("b1", "src-b", 30.0, 60.0)
        val audio = com.ritim.ritimcraw.data.audio.AudioProject(
            links = mapOf(
                "src-b" to com.ritim.ritimcraw.data.audio.AudioLinkRecord(
                    state = com.ritim.ritimcraw.data.audio.AudioLinkState.REMOVED,
                ),
            ),
            clips = listOf(
                com.ritim.ritimcraw.data.audio.AudioClip(
                    id = "a1", sourceId = "src-a", sampleRateHz = 48000,
                    gainDb = -6f, pan = 1f,
                ),
            ),
            masterGainDb = -3f,
            masterMuted = true,
        )
        val plan = AudioPlan.mixPlan(project(listOf(a, b)), audio, sourceRateHz = { 48000 })
        assertEquals(1, plan.placed.size)
        assertEquals(-6f, plan.placed[0].state.gainDb)
        assertEquals(1f, plan.placed[0].state.pan)
        assertEquals(AudioSkip.Reason.LINK_NOT_ATTACHED, plan.skips.single().reason)
        assertEquals(MasterAudioState(-3f, true), MasterAudioState.of(audio))
    }

    @Test
    fun `clipAudioState of maps the persisted row and absent means unity`() {
        // gain and fades ride the row; a row that never set pan/muted
        // reads center/unmuted.
        val row = com.ritim.ritimcraw.data.audio.AudioClip(
            id = "a1",
            sourceId = "src-a",
            sampleRateHz = 44100,
            gainDb = -6f,
            fadeInMs = 120,
            fadeOutMs = 80,
        )
        val state = ClipAudioState.of(row)
        assertEquals(-6f, state.gainDb)
        assertEquals(0f, state.pan)
        assertFalse(state.muted)
        assertEquals(120, state.fadeInMs)
        assertEquals(80, state.fadeOutMs)
        assertEquals(ClipAudioState.UNITY, ClipAudioState.of(null))
    }
}
