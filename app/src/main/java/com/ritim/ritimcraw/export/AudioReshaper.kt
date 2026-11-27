package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.engine.NativeSource

/**
 * The E5-B remix + resample seam (night G1, docs/E5_AUDIO_PORT_PLAN.md
 * §3.7/§3.8 + §4.2's audio-preparation point): the exporter calls
 * through this at the same audio-preparation point as the loudness
 * chain, so the JVM tests can inject a fake and prove the SOURCE path
 * never runs it. The DEFAULT is the native core (core/src/
 * AudioReshape.cpp through NativeSource's JNI bridge): BS.775 remix
 * (stereo->mono [k, k], k = 1/sqrt(2) ~ -3.01 dB; mono->stereo [1; 1]
 * dual mono, the deliberate +3 dB) then the linear StreamResampler.
 *
 * ORDER NOTE (the E5-B plan's own rule): the reshape runs BEFORE the
 * loudness normalization when both are set, so the chain measures the
 * FINAL audio — the reshaped rate/channels are what the target is
 * reached on (§7.E5-B "seçim değişirse I değişir").
 */
fun interface AudioReshaper {

    /** The reshaped PCM plus its honest output shape. */
    data class Result(
        val pcm: ShortArray,
        /** The output frame count (pcm.size / channels). */
        val frames: Int,
        /** The output sample rate in Hz (== [toRate]). */
        val sampleRate: Int,
        /** The output channel count (== [toChannels]). */
        val channels: Int,
    )

    /**
     * Reshapes [pcm] (interleaved 16-bit, [frames] frames of [channels]
     * channels at [sampleRate] Hz) to [toRate] Hz / [toChannels]
     * channels. The frame count follows the rate ratio exactly (plan
     * §4.3's own math), so the duration is honest; an identity request
     * (toRate == sampleRate && toChannels == channels) the caller makes
     * anyway copies the PCM untouched.
     */
    fun reshape(
        pcm: ShortArray,
        frames: Int,
        sampleRate: Int,
        channels: Int,
        toRate: Int,
        toChannels: Int,
    ): Result
}

/**
 * The production seam: the native core through NativeSource's JNI
 * wrappers. On a null (malformed request) the export FAILS loudly with
 * the bridge's own error detail — never a silent un-reshaped copy,
 * which would lie about the 44.1 kHz / mono the settings promised.
 */
internal object NativeAudioReshaper : AudioReshaper {

    override fun reshape(
        pcm: ShortArray,
        frames: Int,
        sampleRate: Int,
        channels: Int,
        toRate: Int,
        toChannels: Int,
    ): AudioReshaper.Result {
        val out = NativeSource.jniAudioReshape(pcm, sampleRate, channels, toRate, toChannels)
            ?: throw IllegalStateException(
                Mp4ExportErrors.kotlinMessage(
                    Mp4ExportErrors.STAGE_ENCODE,
                    "the audio rate/channel conversion could not run on this clip",
                    "export again, or set the sample rate and channels to Source",
                    NativeSource.previewLastError(),
                )
            )
        val outFrames = if (toChannels > 0) out.size / toChannels else 0
        return AudioReshaper.Result(
            pcm = out,
            frames = outFrames,
            sampleRate = if (toRate > 0) toRate else sampleRate,
            channels = if (toChannels > 0) toChannels else channels,
        )
    }
}
