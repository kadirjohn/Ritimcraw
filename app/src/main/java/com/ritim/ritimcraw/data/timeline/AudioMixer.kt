package com.ritim.ritimcraw.data.timeline

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The pure timeline audio MIX PASS reference (şartname AUD-06 "ortak mix
 * rate'e resample", AUD-07 "klip gain, stereo pan, ... master dBFS/RMS,
 * peak hold ve clipping ölçümü"; docs/TIMELINE_AUDIO_PLAN.md §5.2 option A,
 * landed 2026-11-23 as the recipe CONSUMER the plan names): it walks an
 * [AudioMixPlan] block by block and renders interleaved stereo 16-bit PCM
 * at the plan's mix rate, applying the plan's own per-clip chain
 * ([AudioPlan.clipGains]: gain -> pan -> fade, mute) and tail
 * ([AudioPlan.mixSum] + [AudioPlan.saturate]).
 *
 * WHY PURE: the plan's §5.3 puts the Android-bound pass (MediaCodec decode
 * per source, the writer's AAC leg) next to TimelineMp4Exporter, and the
 * playback mixer next to EditorAudio later. Both need the SAME arithmetic;
 * keeping it here, behind [PcmSource], makes it host-testable and makes
 * "what you preview is what you export" a shared function rather than two
 * copies. Nothing in the app calls it yet — the codec-side wiring is the
 * integrator's (device-test-pending: audible click/crackle checks).
 *
 * RESAMPLING (AUD-06 — by timestamp, never by a fixed-48k assumption):
 * mix sample k of a placed clip (clip-local offset o = k - mixStart) reads
 * the SOURCE at the fractional position
 *     sourceStart + o * sourceRate / mixRate
 * — the same real time. EQUAL rates are the exact identity (sourceStart +
 * o, no interpolation runs, bit-exact passthrough — pinned by test).
 * Unequal rates use [Kernel]: LINEAR (default) interpolates the two
 * neighbours; NEAREST is the plan's v1 stand-in. The final kernel (linear
 * vs windowed-sinc) is the plan's §6.3 item 1 listening decision — owner /
 * device; this class only makes the choice explicit.
 *
 * BOUNDS (the plan's consumer-clamp rule): a source position outside the
 * clip's source window [sourceStartSample, sourceEndSample) or outside the
 * decoded PCM [0, frameCount) contributes SILENCE — never a wrapped or
 * repeated sample. A placed clip whose source cannot be opened (the
 * [sources] lookup returns null) is silent and reported in
 * [MixBlock.missingSources] — the honesty gate's runtime twin.
 */
object AudioMixer {

    /** The cross-rate interpolation kernel (see the class KDoc). */
    enum class Kernel { NEAREST, LINEAR }

    /**
     * Decoded PCM of one source at its own rate. [channels] 1 (mono:
     * duplicated to both outputs), 2 (stereo), or more (the first two are
     * used — documented, never a guessed downmix matrix).
     */
    interface PcmSource {
        val rateHz: Int
        val channels: Int
        /** Frames (samples per channel) available. */
        val frameCount: Long
        /** Sample [frame] of [channel]; called only for in-range indices. */
        fun sample(frame: Long, channel: Int): Short
    }

    /** A whole-clip interleaved buffer (what the native readAudio returns today). */
    class InterleavedPcm(
        override val rateHz: Int,
        override val channels: Int,
        private val pcm: ShortArray,
    ) : PcmSource {
        init {
            require(rateHz > 0) { "rate must be > 0 (AUD-06)" }
            require(channels > 0) { "channels must be > 0" }
        }

        override val frameCount: Long = (pcm.size / channels).toLong()
        override fun sample(frame: Long, channel: Int): Short =
            pcm[(frame * channels + channel).toInt()]
    }

    /**
     * One rendered block: [pcm] is interleaved stereo (L, R) at the mix
     * rate, `frames * 2` shorts. The AUD-07 meters ride along: [peakLeft]/
     * [peakRight] are the block's absolute peaks in 0..1 AFTER saturation,
     * [clippedSamples] counts channel samples whose pre-saturation value
     * exceeded full scale (the clip indicator), [sumSquares] lets a caller
     * accumulate RMS over any window.
     */
    data class MixBlock(
        val startMixSample: Long,
        val frames: Int,
        val pcm: ShortArray,
        val peakLeft: Float,
        val peakRight: Float,
        val clippedSamples: Int,
        val sumSquares: Double,
        /** Clip ids that overlapped this block but whose source was unavailable. */
        val missingSources: List<String>,
    ) {
        /** RMS of this block over both channels (0..1); 0 for an empty block. */
        val rms: Double
            get() = if (frames == 0) 0.0 else kotlin.math.sqrt(sumSquares / (frames * 2.0))

        override fun equals(other: Any?): Boolean =
            other is MixBlock && startMixSample == other.startMixSample &&
                frames == other.frames && pcm.contentEquals(other.pcm) &&
                peakLeft == other.peakLeft && peakRight == other.peakRight &&
                clippedSamples == other.clippedSamples && sumSquares == other.sumSquares &&
                missingSources == other.missingSources

        override fun hashCode(): Int = 31 * startMixSample.hashCode() + pcm.contentHashCode()
    }

    /**
     * Renders [frames] mix samples starting at absolute mix sample
     * [startMixSample]. Samples at or beyond [AudioMixPlan.totalMixSamples]
     * (or before 0) render silent — the movie's audio length is the plan's.
     *
     * @param sources the source PCM by sourceId (null = unavailable -> silent + reported).
     */
    fun renderBlock(
        plan: AudioMixPlan,
        master: MasterAudioState,
        startMixSample: Long,
        frames: Int,
        sources: (String) -> PcmSource?,
        kernel: Kernel = Kernel.LINEAR,
    ): MixBlock {
        require(frames >= 0) { "frames must be >= 0" }
        val endMix = startMixSample + frames
        val accL = DoubleArray(frames)
        val accR = DoubleArray(frames)
        val missing = ArrayList<String>()

        for (clip in plan.placed) {
            val pl = clip.placement
            val lo = maxOf(startMixSample, pl.mixStartSample, 0L)
            val hi = minOf(endMix, pl.mixEndSample, plan.totalMixSamples)
            if (lo >= hi) continue
            if (clip.state.muted) continue
            val src = sources(clip.sourceId)
            if (src == null) {
                missing.add(clip.clipId)
                continue
            }
            val sameRate = src.rateHz == pl.mixRateHz
            val ratio = src.rateHz.toDouble() / pl.mixRateHz
            val srcLo = maxOf(pl.sourceStartSample, 0L)
            val srcHi = minOf(pl.sourceEndSample, src.frameCount)
            for (k in lo until hi) {
                val offset = k - pl.mixStartSample
                val g = AudioPlan.clipGains(clip.state, pl, offset)
                if (g.left == 0f && g.right == 0f) continue
                val l: Float
                val r: Float
                if (sameRate) {
                    val f = pl.sourceStartSample + offset
                    if (f < srcLo || f >= srcHi) continue
                    l = left(src, f)
                    r = right(src, f)
                } else {
                    val pos = pl.sourceStartSample + offset * ratio
                    when (kernel) {
                        Kernel.NEAREST -> {
                            val f = floor(pos + 0.5).toLong()
                            if (f < srcLo || f >= srcHi) continue
                            l = left(src, f)
                            r = right(src, f)
                        }
                        Kernel.LINEAR -> {
                            val f0 = floor(pos).toLong()
                            if (f0 < srcLo || f0 >= srcHi) continue
                            val t = (pos - f0).toFloat()
                            // At the window's last sample the right
                            // neighbour would lie outside the trimmed
                            // source: hold the last in-window sample
                            // instead of reaching past the trim.
                            val f1 = if (f0 + 1 < srcHi) f0 + 1 else f0
                            l = left(src, f0) * (1f - t) + left(src, f1) * t
                            r = right(src, f0) * (1f - t) + right(src, f1) * t
                        }
                    }
                }
                val i = (k - startMixSample).toInt()
                accL[i] += (l * g.left).toDouble()
                accR[i] += (r * g.right).toDouble()
            }
        }

        val pcm = ShortArray(frames * 2)
        var peakL = 0f
        var peakR = 0f
        var clipped = 0
        var sumSq = 0.0
        val masterGain = if (master.muted) 0.0 else AudioPlan.linearGain(master.gainDb).toDouble()
        for (i in 0 until frames) {
            // The plan's tail: sum (already accumulated in double) x master,
            // count clipping against the UNSATURATED value, then saturate.
            val ml = (accL[i] * masterGain).toFloat()
            val mr = (accR[i] * masterGain).toFloat()
            if (abs(ml) > 1f) clipped++
            if (abs(mr) > 1f) clipped++
            val sl = AudioPlan.saturate(ml)
            val sr = AudioPlan.saturate(mr)
            peakL = maxOf(peakL, abs(sl))
            peakR = maxOf(peakR, abs(sr))
            sumSq += (sl * sl + sr * sr).toDouble()
            pcm[i * 2] = toPcm(sl)
            pcm[i * 2 + 1] = toPcm(sr)
        }
        return MixBlock(startMixSample, frames, pcm, peakL, peakR, clipped, sumSq, missing)
    }

    /**
     * The block start positions covering the whole movie: 0, n, 2n, ...
     * below [AudioMixPlan.totalMixSamples], each paired with its frame
     * count (the last block is short). [blockFrames] is the consumer's
     * choice (the writer's AAC leg uses 1024-frame blocks).
     */
    fun blocks(plan: AudioMixPlan, blockFrames: Int): List<Pair<Long, Int>> {
        require(blockFrames > 0) { "blockFrames must be > 0" }
        val out = ArrayList<Pair<Long, Int>>()
        var s = 0L
        while (s < plan.totalMixSamples) {
            val n = minOf(blockFrames.toLong(), plan.totalMixSamples - s).toInt()
            out.add(s to n)
            s += n
        }
        return out
    }

    private fun norm(s: Short): Float = s / 32768f

    private fun left(src: PcmSource, f: Long): Float = norm(src.sample(f, 0))

    private fun right(src: PcmSource, f: Long): Float =
        norm(src.sample(f, if (src.channels >= 2) 1 else 0))

    /**
     * Normalized -1..1 (already saturated) -> 16-bit. The inverse of
     * [norm] (x 32768, +1.0 clamps to 32767), so a unity-gain hard-panned
     * passthrough returns every source sample bit-exact (pinned by test).
     */
    internal fun toPcm(x: Float): Short =
        (x * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
}
