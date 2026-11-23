package com.ritim.ritimcraw.data.timeline

import com.ritim.ritimcraw.data.audio.AudioClip
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.data.audio.AudioProject
import com.ritim.ritimcraw.data.audio.AudioTrackOps
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The PLACED-PCM reference model for timeline audio (C1, the model half
 * of docs/TIMELINE_AUDIO_PLAN.md): what a placed clip's audio IS on the
 * timeline's sample grid, the per-clip and master audio state, and the
 * exact arithmetic that maps between them. Pure Kotlin, no Android
 * imports — JVM-testable headless (the TimelineOps / AudioTrackOps
 * precedent). Nothing here is wired into playback or export yet; the
 * plan document proposes that wiring and the integrator owns it.
 *
 * OWNERSHIP (the boundary this file keeps): PLACEMENT lives in the
 * timeline core — a placed audio clip is a [TimelineClip] on an AUDIO
 * track, trimmed/split/moved by the SAME frame-grid rules as video
 * (TimelineModel's timebase contract; linked pairs follow via
 * TimelineOps' TL-05 rule). SETTINGS live in data/audio — [AudioClip]
 * rows join by clip id and ride every timeline edit untouched (the
 * PLACEMENT OWNERSHIP BOUNDARY in AudioTrackModel's KDoc). This file
 * adds the MISSING third piece: the sample-grid math that turns a
 * placed TimelineClip + its settings row into the source-sample
 * window, the mix-domain span, and the per-sample mix gains.
 *
 * TIME CONTRACT (reused, not forked): the project frame grid IS the
 * timebase. A grid position g (frames, project fps) is the time
 * g / fps seconds; in samples at rate R that is round(g * R / fps).
 * The conversion goes THROUGH AudioTrackOps.exportStartMs +
 * msToSamples — the AUD-06 single conversion point — never through a
 * private multiply. [AudioPlan.sampleAt] is that composition; the
 * closed form round(g * R / fps) is its algebraic identity (pinned by
 * test), and TimelineExportPlan's video mapping round(pos * sourceFps
 * / projectFps) is its frame-domain twin. PtsTimeline's export pts
 * convention (sample k at k * 1e6 / rate us past the shared origin) is
 * the same clock read in microseconds: one origin, one rate, sample k
 * at time k / rate.
 */
object AudioPlan {

    /**
     * The default project mix rate (the plan doc §2.1): 48000 because
     * the camera's own sources are 48 kHz — the DOMINANT case is
     * passthrough, zero resampling, zero quality loss. It is a
     * PROJECT SETTING, not an assumption: 44.1 kHz sources resample
     * into it by timestamp (AUD-06), and a 44.1-only project can set
     * 44100 and never resample at all. Never DERIVE a time from a
     * sample count using this constant — a source's own rate comes
     * from its AudioClip row / metadata (the AUD-06 rule this default
     * must not quietly violate).
     */
    const val DEFAULT_MIX_RATE_HZ = 48000

    /**
     * THE MAPPING RULE (the plan doc §2.2): a project-grid position
     * [projectFrames] (the timebase — TimelineModel's contract) at
     * sample rate [rateHz] lands on sample round(g * R / fps).
     * Composed THROUGH the AUD-06 single conversion point —
     * exportStartMs (frames -> ms) then msToSamples (ms -> samples,
     * round) — so the future resample mission that slots into those
     * functions is inherited here for free. Monotonic non-decreasing
     * in [projectFrames]; EDGE-NEAREST (round), the audio twin of the
     * video export's frame-nearest mapping.
     */
    fun sampleAt(projectFrames: Double, rateHz: Int, projectFps: Int): Long {
        require(rateHz > 0) { "rate $rateHz must be > 0 (AUD-06: never assumed)" }
        val ms = AudioTrackOps.exportStartMs(projectFrames, projectFps)
            ?: throw IllegalArgumentException(
                "project fps $projectFps is not a usable timebase",
            )
        return AudioTrackOps.msToSamples(ms, rateHz)
    }

    /**
     * A placed clip's two windows (the plan doc §2.3): the SOURCE
     * window from sourceRange (which source samples the trims kept)
     * and the MIX span from timelineRange (where they land), both
     * HALF-OPEN, both mapped through [sampleAt] — the same
     * composition, two rates. EDGE-NEAREST: each edge maps
     * independently (round), the span is the difference; at 30 fps
     * both 44.1 kHz and 48 kHz divide the frame exactly (1470 / 1600
     * samples), so no edge ever rounds, at other fps the span is off
     * by at most one sample (honest, documented).
     *
     * The INTENDED window: the consumer clamps to the source's real
     * decoded PCM length (an unknown length is the consumer's gate,
     * the FRAME_COUNT_UNKNOWN twin — this model never guesses bounds).
     * A clip placed before the origin keeps its raw (negative) mix
     * start; intersecting with [0, totalMixSamples) is the consumer's
     * walk rule, the video walk's outStart/outEnd clamp twin.
     */
    fun placementOf(
        clip: TimelineClip,
        sourceRateHz: Int,
        mixRateHz: Int,
        projectFps: Int,
    ): AudioPlacement = AudioPlacement(
        sourceRateHz = sourceRateHz,
        mixRateHz = mixRateHz,
        sourceStartSample = sampleAt(clip.sourceRange.start, sourceRateHz, projectFps),
        sourceEndSample = sampleAt(clip.sourceRange.end, sourceRateHz, projectFps),
        mixStartSample = sampleAt(clip.timelineRange.start, mixRateHz, projectFps),
        mixEndSample = sampleAt(clip.timelineRange.end, mixRateHz, projectFps),
    )

    /**
     * Within a placed clip: mix sample [mixOffset] (clip-local, 0 =
     * the first sample of the span) pulls which SOURCE sample. The
     * clip-local project-frame position is j = offset * fps / mixRate
     * (exact rational); the source sample is
     * sampleAt(sourceRange.start + j) — the same mapping as the window
     * edges, so offset 0 hits [AudioPlacement.sourceStartSample]
     * exactly and the offset stays honest to the trims.
     *
     * EQUAL RATES are the IDENTITY: source rate == mix rate gives
     * sourceStartSample + offset, sample for sample (passthrough, no
     * resampler runs — pinned by test). Unequal rates pull by
     * TIMESTAMP: the source sample at the same real time as the mix
     * sample (the v1 nearest-sample stand-in; the real interpolation
     * kernel is the later AUD-06 mission — the plan doc §2.1 — and it
     * slots in behind this same contract).
     */
    fun sourceSampleAtMixOffset(
        clip: TimelineClip,
        placement: AudioPlacement,
        mixOffset: Long,
        projectFps: Int,
    ): Long {
        val j = mixOffset.toDouble() * projectFps / placement.mixRateHz
        return sampleAt(clip.sourceRange.start + j, placement.sourceRateHz, projectFps)
    }

    /**
     * The CONSTANT-POWER pan law (the plan doc §3.2 — the choice and
     * the rejected alternatives): pan -1..1 maps to the angle
     * (pan + 1) * pi / 4; left = cos, right = sin. Center is
     * sqrt(2)/2 per channel (-3.01 dB) with UNITY POWER SUM
     * (left^2 + right^2 = 1 at EVERY pan — the perceived loudness
     * stays constant as the knob sweeps); full left / right hands one
     * channel 1.0 and the other 0.
     */
    fun panGains(pan: Float): StereoSample {
        require(pan in -1.0f..1.0f) { "pan $pan outside -1..1" }
        val angle = (pan + 1.0) * (PI / 4.0)
        return StereoSample(cos(angle).toFloat(), sin(angle).toFloat())
    }

    /** dBFS -> linear amplitude (the gain step of the render contract). */
    fun linearGain(db: Float): Float = 10f.pow(db / 20f)

    /**
     * The fade envelope at [mixOffset] (the plan doc §3.3): linear
     * ramps, STORED INTENT clamped to the placed span — a fade longer
     * than the clip fades across the whole clip, and trim never
     * rewrites the stored value (the AudioClip rule). ENDPOINTS: the
     * in-ramp is 0 at offset 0 and exactly 1 at the fade length; the
     * out-ramp is 1 until (end - fadeOut) and reaches 0 AT the end
     * edge — the last real sample keeps 1/fadeOutSamples, the curve
     * is continuous to zero at the exclusive end. The two envelopes
     * MULTIPLY where they overlap (each is an amplitude scalar).
     */
    fun fadeGain(state: ClipAudioState, placement: AudioPlacement, mixOffset: Long): Float {
        val span = placement.mixSampleCount
        if (span <= 0L) return 1f
        val fadeIn = minOf(
            AudioTrackOps.msToSamples(state.fadeInMs.toDouble(), placement.mixRateHz),
            span,
        )
        val fadeOut = minOf(
            AudioTrackOps.msToSamples(state.fadeOutMs.toDouble(), placement.mixRateHz),
            span,
        )
        val into = if (fadeIn <= 0L) 1f
        else (mixOffset.toDouble() / fadeIn).coerceIn(0.0, 1.0).toFloat()
        val outOf = if (fadeOut <= 0L) 1f
        else ((span - mixOffset).toDouble() / fadeOut).coerceIn(0.0, 1.0).toFloat()
        return into * outOf
    }

    /**
     * The per-clip stereo gain at one mix offset — the render
     * contract's per-clip chain, gain -> pan -> fade (all amplitude
     * scalars; the ORDER is the contract's, the product commutes).
     * Mute short-circuits to silence. This is the multiplier the
     * consumer applies to the pulled source sample pair.
     */
    fun clipGains(
        state: ClipAudioState,
        placement: AudioPlacement,
        mixOffset: Long,
    ): StereoSample {
        if (state.muted) return StereoSample(0f, 0f)
        val g = linearGain(state.gainDb)
        val p = panGains(state.pan)
        val f = fadeGain(state, placement, mixOffset)
        return StereoSample(g * p.left * f, g * p.right * f)
    }

    /**
     * The saturation rule (the plan doc §3.3): HARD CLIP at full
     * scale — a summed sample beyond +/-1.0 clamps, it never wraps and
     * no soft limiter runs (v1; AUD-07's loudness measurement is the
     * later mission). The consumer counts clamped samples (AUD-07's
     * clipping measurement) by comparing against the pre-saturation
     * [mixSum] value.
     */
    fun saturate(x: Float): Float = x.coerceIn(-1.0f, 1.0f)

    /**
     * The mix sum + master (the render contract's tail): sum every
     * contribution's channels, apply the master gain, return the
     * UNSATURATED pair — the caller saturates ([saturate]) and can
     * measure clipping against this value. Master mute short-circuits
     * to silence. Summed in double, narrowed once: a long mix's
     * accumulation stays honest.
     */
    fun mixSum(contributions: List<StereoSample>, master: MasterAudioState): StereoSample {
        if (master.muted) return StereoSample(0f, 0f)
        var left = 0.0
        var right = 0.0
        for (c in contributions) {
            left += c.left
            right += c.right
        }
        val m = linearGain(master.gainDb).toDouble()
        return StereoSample((left * m).toFloat(), (right * m).toFloat())
    }

    /**
     * The video clip this audio clip is BOUND to (some video clip's
     * linkedAudioId points here), or null when INDEPENDENT. The link
     * is BY ID, never position: move either half and the join survives
     * — QA-13's "bağlı sesin kesilip taşındığı" case (the plan doc
     * §4.2). Scans the project's clips; the mix plan calls it once per
     * placed clip.
     */
    fun boundVideoOf(project: TimelineProject, audioClipId: String): TimelineClip? =
        project.clips.firstOrNull { it.linkedAudioId == audioClipId }

    /** True when no video clip binds this audio clip (the independent shape). */
    fun isIndependent(project: TimelineProject, audioClipId: String): Boolean =
        boundVideoOf(project, audioClipId) == null

    /**
     * The mix plan for [project]'s AUDIO tracks (the plan doc §5.3's
     * recipe): every AUDIO-track clip in timeline order — bound and
     * independent place IDENTICALLY (boundness is the join fact
     * [PlacedClipAudio.boundVideoId], not a placement rule). HONESTY
     * GATES (never guess): a clip with no source id is skipped
     * SOURCE_MISSING; a source whose rate the lookup cannot answer is
     * skipped RATE_UNKNOWN — NEVER a 48000 substitution (AUD-06). A
     * skipped clip's span renders SILENT (the audio twin of a black
     * skip span); a clip whose window rounds EMPTY contributes
     * nothing (no entry, no skip — the zero-duration rule's twin).
     *
     * [AudioMixPlan.totalMixSamples] is ceil(resolveLength) mapped
     * through [sampleAt] — the TL-17 resolver is the length
     * authority, so an audio tail beyond the last video frame runs
     * the movie to its resolved end (QA-12's "ses sonuna kadar
     * sürer": the sound lasts to its own end).
     *
     * @param states the per-clip settings by clip id — the join into
     *   data/audio (an absent row is UNITY;
     *   ClipAudioState.of(AudioProject.clip(id)) is the wired form).
     * @param sourceRateHz the honest rate lookup: the source's REAL
     *   rate or null when unknown — a null skips the clip, never a
     *   guess (the fpsMeasured twin).
     * @param linkState the AUD-02 link-state gate (TIMELINE_AUDIO_PLAN
     *   §4.2 / §6.3 item 3, landed 2026-11-23): the source's ORIGINAL
     *   audio link state by sourceId. Only ATTACHED places; DETACHED and
     *   REMOVED skip LINK_NOT_ATTACHED (silent in preview AND export —
     *   "kaldırılmış ses tekrar oluşmaz", QA-13), and LINKED_EDITED
     *   skips too: the edited recording replaces the original, and this
     *   plan has no edited-recording source to place (never the original
     *   as a stand-in). Default = every source ATTACHED (the pre-gate
     *   behavior, byte-identical plans for unmanaged projects).
     */
    fun mixPlan(
        project: TimelineProject,
        states: (String) -> ClipAudioState,
        sourceRateHz: (String) -> Int?,
        mixRateHz: Int = DEFAULT_MIX_RATE_HZ,
        linkState: (String) -> AudioLinkState = { AudioLinkState.ATTACHED },
    ): AudioMixPlan {
        require(mixRateHz > 0) { "mix rate $mixRateHz must be > 0" }
        val fps = project.output.fps
        require(fps > 0) { "project fps $fps is not a usable timebase" }
        val audioTrackIds = project.tracks
            .filter { it.kind == TrackKind.AUDIO }
            .map { it.id }
            .toSet()
        val totalMixSamples =
            sampleAt(ceil(TimelineOps.resolveLength(project)), mixRateHz, fps)

        val placed = ArrayList<PlacedClipAudio>()
        val skips = ArrayList<AudioSkip>()
        for (clip in project.clips
            .filter { it.trackId in audioTrackIds }
            .sortedBy { it.timelineRange.start }) {
            val sid = clip.sourceId
            if (sid == null) {
                skips.add(AudioSkip(clip.id, null, AudioSkip.Reason.SOURCE_MISSING))
                continue
            }
            if (linkState(sid) != AudioLinkState.ATTACHED) {
                skips.add(AudioSkip(clip.id, sid, AudioSkip.Reason.LINK_NOT_ATTACHED))
                continue
            }
            val rate = sourceRateHz(sid)
            if (rate == null || rate <= 0) {
                skips.add(AudioSkip(clip.id, sid, AudioSkip.Reason.RATE_UNKNOWN))
                continue
            }
            val placement = placementOf(clip, rate, mixRateHz, fps)
            if (placement.mixSampleCount <= 0L || placement.sourceSampleCount <= 0L) {
                continue // rounds empty: contributes nothing, honestly
            }
            placed.add(
                PlacedClipAudio(
                    clipId = clip.id,
                    sourceId = sid,
                    boundVideoId = boundVideoOf(project, clip.id)?.id,
                    placement = placement,
                    state = states(clip.id),
                ),
            )
        }
        return AudioMixPlan(mixRateHz, totalMixSamples, placed, skips)
    }

    /**
     * The WIRED form of [mixPlan]: the per-clip states and the link gate
     * both read the persisted [audio] project (ClipAudioState.of on its
     * rows, AudioProject.link for the gate) — the one join a preview
     * mixer and the export mix pass must share so what you preview is
     * what you export. The master state is [MasterAudioState.of].
     */
    fun mixPlan(
        project: TimelineProject,
        audio: AudioProject,
        sourceRateHz: (String) -> Int?,
        mixRateHz: Int = DEFAULT_MIX_RATE_HZ,
    ): AudioMixPlan = mixPlan(
        project = project,
        states = { id -> ClipAudioState.of(audio.clip(id)) },
        sourceRateHz = sourceRateHz,
        mixRateHz = mixRateHz,
        linkState = { sid -> audio.link(sid).state },
    )
}

/**
 * One stereo sample pair in the NORMALIZED float domain: -1.0..1.0 is
 * the full-scale span. The mix sums these; [AudioPlan.saturate] clamps
 * the result back into the span (the render contract's saturation
 * rule). The consumer converts to PCM shorts at the edge.
 */
data class StereoSample(val left: Float, val right: Float)

/**
 * The per-clip audio state the mix renders (the reference shape of
 * AUD-07's "klip gain, stereo pan, track mute"): gain dBFS, pan -1..1,
 * mute, and the stored-intent fades. All five mirror
 * [com.ritim.ritimcraw.data.audio.AudioClip]'s persisted fields (pan/mute
 * landed on the row 2026-11-23 — the plan doc §3.1 / §6.3 item 4).
 */
data class ClipAudioState(
    /** Gain in dBFS. 0 = unity; positive boosts, negative attenuates. */
    val gainDb: Float = 0f,
    /**
     * Stereo pan, -1 (full left) .. 0 (center) .. +1 (full right),
     * rendered by the CONSTANT-POWER law ([AudioPlan.panGains]):
     * center = -3.01 dB per channel, unity POWER — the loudness stays
     * constant as the knob sweeps (the plan doc §3.2 states the law
     * and the rejected alternatives).
     */
    val pan: Float = 0f,
    /** Mute: the clip contributes silence (both channels' gain 0). */
    val muted: Boolean = false,
    /** Fade-in duration in ms. 0 = none. STORED INTENT: clamped at render. */
    val fadeInMs: Int = 0,
    /** Fade-out duration in ms. 0 = none. Same stored-intent rule. */
    val fadeOutMs: Int = 0,
) {
    init {
        require(pan in -1.0f..1.0f) { "pan $pan outside -1..1" }
        require(fadeInMs >= 0) { "fadeInMs must be >= 0" }
        require(fadeOutMs >= 0) { "fadeOutMs must be >= 0" }
    }

    companion object {

        /** Unity: no gain, center, unmuted, no fades (the absent-row default). */
        val UNITY = ClipAudioState()

        /**
         * The persisted [row] -> the reference state. An ABSENT row is
         * UNITY (AudioProject's absent-row rule); pan/muted ride the row
         * (pre-field rows load as center/unmuted in AudioStore).
         */
        fun of(row: AudioClip?): ClipAudioState =
            if (row == null) UNITY else ClipAudioState(
                gainDb = row.gainDb,
                pan = row.pan,
                muted = row.muted,
                fadeInMs = row.fadeInMs,
                fadeOutMs = row.fadeOutMs,
            )
    }
}

/**
 * The master mix state (AUD-07's master dBFS): applied AFTER the sum,
 * BEFORE saturation (the render contract, the plan doc §3.4).
 * Proposed to live on AudioProject, persisted omit-when-default —
 * AudioStore's fadeInMs/fadeOutMs precedent.
 */
data class MasterAudioState(
    /** Master gain in dBFS. 0 = unity. */
    val gainDb: Float = 0f,
    /** Master mute: the whole mix renders silence. */
    val muted: Boolean = false,
) {
    companion object {
        /** The persisted master fields of [project] (AudioProject, 2026-11-23). */
        fun of(project: AudioProject): MasterAudioState =
            MasterAudioState(gainDb = project.masterGainDb, muted = project.masterMuted)
    }
}

/**
 * A placed clip's audio mapped onto the two grids: the SOURCE-domain
 * window (at the source's own rate) and the MIX-domain span (at the
 * project mix rate). Both HALF-OPEN [start, end) — the sample-grid twin
 * of FrameRange. Built by [AudioPlan.placementOf]; see its KDoc for
 * the edge-nearest and consumer-clamp rules.
 */
data class AudioPlacement(
    /** The source's own sample rate (its AudioClip row / metadata — never assumed). */
    val sourceRateHz: Int,
    /** The project mix rate (the setting whose default is 48000). */
    val mixRateHz: Int,
    /** First source sample used (inclusive), at [sourceRateHz]. */
    val sourceStartSample: Long,
    /** End of the source window (EXCLUSIVE), at [sourceRateHz]. */
    val sourceEndSample: Long,
    /** First mix sample (inclusive), ABSOLUTE on the project mix grid. */
    val mixStartSample: Long,
    /** End of the mix span (EXCLUSIVE), absolute. */
    val mixEndSample: Long,
) {
    val sourceSampleCount: Long get() = sourceEndSample - sourceStartSample
    val mixSampleCount: Long get() = mixEndSample - mixStartSample
}

/**
 * One clip the mix plan placed: the placement plus the joined state.
 * boundVideoId is the JOIN FACT, not a placement rule — bound and
 * independent audio clips place IDENTICALLY (the walk's uniformity);
 * null = independent (no video clip's linkedAudioId points here).
 */
data class PlacedClipAudio(
    /** The audio TimelineClip's stable id (the join key everywhere). */
    val clipId: String,
    /** The MediaSource the audio came from (the timeline clip's sourceId). */
    val sourceId: String,
    /** The bound video clip's id, or null when independent. */
    val boundVideoId: String?,
    val placement: AudioPlacement,
    val state: ClipAudioState,
)

/** A clip the mix plan refused to guess about (the honesty gates). */
data class AudioSkip(
    /** The timeline clip's stable id. */
    val clipId: String,
    /** The clip's source id, or null for a clip with no source. */
    val sourceId: String?,
    val reason: Reason,
) {
    enum class Reason {
        /** The clip has no source id — nothing to decode. */
        SOURCE_MISSING,

        /**
         * The source's sample rate is UNKNOWN — the plan NEVER assumes
         * 48000 (AUD-06); the clip is skipped and its span renders
         * SILENT (the audio twin of a black skip span).
         */
        RATE_UNKNOWN,

        /**
         * The source's original audio is not ATTACHED (DETACHED, REMOVED
         * or LINKED_EDITED — AUD-02): the clip renders SILENT; a removed
         * audio never resurrects in the mix (QA-13).
         */
        LINK_NOT_ATTACHED,
    }
}

/**
 * The whole project's audio side in mix-domain terms: every placed
 * AUDIO-track clip (bound and independent, in timeline order) plus the
 * honest skips. The mix pass's recipe — the audio twin of
 * TimelineExportPlan: the consumer walks [totalMixSamples] of the mix
 * grid, and for each placed clip pulls source samples per
 * [AudioPlan.sourceSampleAtMixOffset], applies [AudioPlan.clipGains],
 * sums ([AudioPlan.mixSum]), saturates ([AudioPlan.saturate]).
 */
data class AudioMixPlan(
    /** The project mix rate — one grid for preview AND export (the plan doc §2.1). */
    val mixRateHz: Int,
    /** The movie's audio length: ceil(resolveLength) mapped to mix samples. */
    val totalMixSamples: Long,
    /** The placed clips, in timeline order. */
    val placed: List<PlacedClipAudio>,
    /** Clips skipped with the honest reason each (their spans render silent). */
    val skips: List<AudioSkip>,
)
