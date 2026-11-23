package com.ritim.ritimcraw.data.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AudioMixer tests (AUD-06/AUD-07, TIMELINE_AUDIO_PLAN §5 option A
 * recipe consumer, 2026-11-23): equal-rate passthrough is bit-exact,
 * placement/trim/bounds render silence outside the window, cross-rate
 * reads by timestamp (LINEAR and NEAREST kernels), the sum saturates and
 * counts clipping, mute/master/missing-source gates, mono duplication,
 * and the block partition covers the movie exactly.
 */
class AudioMixerTest {

    private fun placed(
        id: String,
        sourceId: String,
        sourceRate: Int,
        srcStart: Long,
        srcEnd: Long,
        mixStart: Long,
        mixEnd: Long,
        state: ClipAudioState = ClipAudioState(pan = -1f), // hard left = unity on L
        mixRate: Int = 48000,
    ) = PlacedClipAudio(
        clipId = id,
        sourceId = sourceId,
        boundVideoId = null,
        placement = AudioPlacement(sourceRate, mixRate, srcStart, srcEnd, mixStart, mixEnd),
        state = state,
    )

    private fun plan(total: Long, vararg clips: PlacedClipAudio, mixRate: Int = 48000) =
        AudioMixPlan(mixRate, total, clips.toList(), emptyList())

    private fun mono(rate: Int, n: Int, f: (Int) -> Int) =
        AudioMixer.InterleavedPcm(rate, 1, ShortArray(n) { f(it).toShort() })

    private fun left(b: AudioMixer.MixBlock, i: Int) = b.pcm[i * 2].toInt()
    private fun right(b: AudioMixer.MixBlock, i: Int) = b.pcm[i * 2 + 1].toInt()

    @Test
    fun equal_rate_passthrough_is_bit_exact() {
        val src = mono(48000, 100) { (it * 331 % 65536) - 32768 }
        val p = plan(100, placed("a", "s", 48000, 0, 100, 0, 100))
        val b = AudioMixer.renderBlock(p, MasterAudioState(), 0, 100, { src })
        for (i in 0 until 100) {
            assertEquals(src.sample(i.toLong(), 0).toInt(), left(b, i))
            assertEquals(0, right(b, i)) // hard left: right gain 0
        }
        assertEquals(0, b.clippedSamples)
        assertTrue(b.missingSources.isEmpty())
    }

    @Test
    fun placement_and_trim_shift_the_window_and_silence_outside() {
        val src = mono(48000, 50) { 1000 + it }
        // Source trimmed to [5, 25), placed at mix [10, 30).
        val p = plan(40, placed("a", "s", 48000, 5, 25, 10, 30))
        val b = AudioMixer.renderBlock(p, MasterAudioState(), 0, 40, { src })
        for (i in 0 until 10) assertEquals(0, left(b, i))
        assertEquals(1005, left(b, 10))
        assertEquals(1024, left(b, 29))
        for (i in 30 until 40) assertEquals(0, left(b, i))
    }

    @Test
    fun decoded_length_and_movie_length_clamp_to_silence() {
        val src = mono(48000, 8) { 2000 }
        // The window claims 20 samples but only 8 are decoded; the movie
        // is 12 long — nothing past either bound is rendered or wrapped.
        val p = plan(12, placed("a", "s", 48000, 0, 20, 0, 20))
        val b = AudioMixer.renderBlock(p, MasterAudioState(), 0, 16, { src })
        for (i in 0 until 8) assertEquals(2000, left(b, i))
        for (i in 8 until 16) assertEquals(0, left(b, i))
    }

    @Test
    fun cross_rate_reads_by_timestamp_linear_and_nearest() {
        // 24 kHz ramp into a 48 kHz mix: mix offset 2k lands on source k;
        // offset 2k+1 is halfway between k and k+1.
        val src = mono(24000, 10) { it * 100 }
        val p = plan(20, placed("a", "s", 24000, 0, 10, 0, 20))
        val lin = AudioMixer.renderBlock(p, MasterAudioState(), 0, 20, { src }, AudioMixer.Kernel.LINEAR)
        assertEquals(0, left(lin, 0))
        assertEquals(50, left(lin, 1))
        assertEquals(100, left(lin, 2))
        assertEquals(350, left(lin, 7))
        // The last in-window sample holds instead of reaching past the trim.
        assertEquals(900, left(lin, 19))
        val near = AudioMixer.renderBlock(p, MasterAudioState(), 0, 20, { src }, AudioMixer.Kernel.NEAREST)
        assertEquals(100, left(near, 1)) // floor(0.5 + 0.5) = 1
        assertEquals(100, left(near, 2))
    }

    @Test
    fun sum_saturates_and_counts_clipping() {
        val loud = mono(48000, 4) { 30000 }
        val p = plan(
            4,
            placed("a", "s1", 48000, 0, 4, 0, 4),
            placed("b", "s2", 48000, 0, 4, 0, 4),
        )
        val b = AudioMixer.renderBlock(p, MasterAudioState(), 0, 4, { loud })
        for (i in 0 until 4) assertEquals(32767, left(b, i))
        assertEquals(4, b.clippedSamples) // left only; right is silent
        assertEquals(1f, b.peakLeft)
        assertEquals(0f, b.peakRight)
    }

    @Test
    fun mute_master_and_missing_source_gates() {
        val src = mono(48000, 4) { 1000 }
        val muted = plan(4, placed("a", "s", 48000, 0, 4, 0, 4, ClipAudioState(muted = true)))
        assertTrue(AudioMixer.renderBlock(muted, MasterAudioState(), 0, 4, { src }).pcm.all { it == 0.toShort() })

        val p = plan(4, placed("a", "s", 48000, 0, 4, 0, 4))
        val off = AudioMixer.renderBlock(p, MasterAudioState(muted = true), 0, 4, { src })
        assertTrue(off.pcm.all { it == 0.toShort() })

        val minus6 = AudioMixer.renderBlock(p, MasterAudioState(gainDb = -6.0206f), 0, 4, { src })
        assertEquals(500, left(minus6, 0))

        val missing = AudioMixer.renderBlock(p, MasterAudioState(), 0, 4, { null })
        assertEquals(listOf("a"), missing.missingSources)
        assertTrue(missing.pcm.all { it == 0.toShort() })
    }

    @Test
    fun mono_center_duplicates_at_constant_power() {
        val src = mono(48000, 2) { 10000 }
        val p = plan(2, placed("a", "s", 48000, 0, 2, 0, 2, ClipAudioState()))
        val b = AudioMixer.renderBlock(p, MasterAudioState(), 0, 2, { src })
        // -3.01 dB per channel: 10000 * sqrt(2)/2 = 7071.
        assertEquals(7071, left(b, 0))
        assertEquals(7071, right(b, 0))
        assertTrue(b.rms > 0.0)
    }

    @Test
    fun stereo_source_keeps_its_channels() {
        val pcm = ShortArray(4) { if (it % 2 == 0) 1000 else -2000 }
        val src = AudioMixer.InterleavedPcm(48000, 2, pcm)
        val p = plan(2, placed("a", "s", 48000, 0, 2, 0, 2, ClipAudioState(pan = 1f)))
        val b = AudioMixer.renderBlock(p, MasterAudioState(), 0, 2, { src })
        assertEquals(0, left(b, 0))      // hard right: left gain 0
        assertEquals(-2000, right(b, 0)) // the source's right channel
    }

    @Test
    fun blocks_partition_the_movie_exactly() {
        val p = plan(2500)
        val blocks = AudioMixer.blocks(p, 1024)
        assertEquals(listOf(0L to 1024, 1024L to 1024, 2048L to 452), blocks)
        assertEquals(2500L, blocks.sumOf { it.second.toLong() })
        assertTrue(AudioMixer.blocks(plan(0), 1024).isEmpty())
    }

    @Test
    fun blocks_render_the_same_samples_as_one_big_render() {
        val src = mono(44100, 300) { (it * 97) % 20000 }
        val p = plan(400, placed("a", "s", 44100, 0, 300, 20, 346, ClipAudioState(pan = -0.3f, fadeInMs = 1)))
        val whole = AudioMixer.renderBlock(p, MasterAudioState(), 0, 400, { src })
        val parts = AudioMixer.blocks(p, 64).flatMap { (s, n) ->
            AudioMixer.renderBlock(p, MasterAudioState(), s, n, { src }).pcm.toList()
        }
        assertEquals(whole.pcm.toList(), parts)
    }
}
