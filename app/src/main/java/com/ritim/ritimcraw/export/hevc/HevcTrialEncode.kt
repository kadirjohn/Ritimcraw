package com.ritim.ritimcraw.export.hevc

/**
 * THE trial-encode decision layer (G4, E2-c's JVM half): what the export
 * does BEFORE an HLG plan is trusted when the preflight verdict says
 * [HevcPreflight.HevcVerdict.Supported.trialEncodeRequired] — the API-34
 * fallback path, where the gate passed on FEATURE_HdrEditing and only a
 * REAL short encode proves the encoder makes HLG.
 *
 * THE SHAPE (the plan's K1, HevcPreflight's trial-interface KDoc, the
 * HevcTrialCache contract): the decision is PURE over three inputs — the
 * verdict's trial requirement, the cache's stored outcome for THIS job
 * shape, and a runner the device leg provides. Nothing here constructs a
 * MediaCodec: the runner is the seam (the writer's pattern — a thin
 * device-gated adapter implements it with a real short encode; JVM tests
 * inject a fake).
 *
 * THE HONEST OUTCOMES (a sealed result — no hidden default):
 *  - [NotRequired] — the verdict did not ask for a trial (the API-37
 *    hlg-editing path): the plan proceeds as-is.
 *  - [Passed] — a trial ran (or the cache answered) and the encoder made
 *    HLG: the plan proceeds, cached so the same shape never re-heats.
 *  - [Refused] — the trial failed or the CACHE holds a recorded refusal:
 *    the honest refusal. The caller degrades the plan to SDR (the
 *    planner's own DYNAMIC_RANGE_UNAVAILABLE + SDR fix — never a silent
 *    fallback, the plan's K2 rule) and the refusal reason rides the
 *    report.
 *
 * THE CACHE RULE (HevcTrialCache's own): BOTH outcomes are cached under
 * the plan's key (a failed trial too — the no-reheat rule); the cache is
 * consulted BEFORE any runner call, so a recorded refusal never re-heats
 * the trial on this device.
 */
object HevcTrialEncode {

    /** What a completed trial said (the runner's answer, cache-stored). */
    sealed class Result {
        /** The verdict did not ask for a trial (the API-37 hlg-editing path). */
        data object NotRequired : Result()

        /** The encoder configured, started, produced and re-decoded HLG. */
        data object Passed : Result()

        /**
         * The trial failed. [reason] is the stage's own sentence (the
         * refusal stage vocabulary: configure/start/decode-back/tags —
         * the HevcRefusalStage tokens plus the trial's own steps).
         */
        data class Refused(val reason: String) : Result()
    }

    /**
     * THE decision. Pure: the same verdict + cache + runner answer always
     * give the same result; the runner is consulted ONLY when the verdict
     * requires a trial AND the cache has no outcome for this key.
     *
     * @param verdict the preflight's verdict for the requested HLG profile
     * (the caller ran HevcPreflight.decide; a Refused verdict never gets
     * here — the planner degrades it before a trial is meaningful).
     * @param key the trial's cache key (the plan's own component list:
     * the profile's six MediaFormat entries + size + API + codec name +
     * Build.ID — [HevcTrialKey.of], built by the caller).
     * @param cache the process-lifetime store (positive-refusal caching).
     * @param runTrial the device leg's short encode, consulted at most
     * once per key per process (the cache's own no-reheat proof).
     */
    fun decide(
        verdict: HevcPreflight.HevcVerdict.Supported,
        key: HevcTrialKey.Key,
        cache: HevcTrialCache,
        runTrial: () -> Result,
    ): Result {
        if (!verdict.trialEncodeRequired) return Result.NotRequired
        cache.outcome(key)?.let { stored ->
            return if (stored.passed) Result.Passed else Result.Refused(stored.reason ?: "the cached trial refused")
        }
        val fresh = runTrial()
        cache.record(
            key,
            when (fresh) {
                is Result.Passed -> HevcTrialCache.Outcome(passed = true)
                is Result.Refused -> HevcTrialCache.Outcome(passed = false, reason = fresh.reason)
                Result.NotRequired -> HevcTrialCache.Outcome(passed = true)
            },
        )
        return fresh
    }

    /**
     * The MediaFormat contract the device leg's trial encodes with — the
     * profile's own entries plus the job shape, EXACTLY as the real
     * export's writer will set them (the plan 4.1 table): the six profile
     * keys, the size, bitrate, frame-rate, i-frame-interval and
     * COLOR_FormatSurface. Pure data (the MediaFormatEntry shape); the
     * device leg copies these into a real MediaFormat, the JVM test
     * asserts the exact keys a fake encoder received.
     *
     * KEY_LEVEL is set TOGETHER with KEY_PROFILE (the docs' encoder rule;
     * the writer's plan field does not carry level — the profile's
     * contract does, and the trial is the ONE place the pair is set as a
     * pair before the real encode).
     */
    fun trialFormatEntries(
        profile: HevcProfile,
        width: Int,
        height: Int,
        bitrateBps: Int,
        fps: Double,
        iFrameIntervalSec: Float,
    ): List<MediaFormatEntry> = buildList {
        addAll(profile.mediaFormatEntries())
        add(MediaFormatEntry("width", width.toString()))
        add(MediaFormatEntry("height", height.toString()))
        add(MediaFormatEntry("bitrate", bitrateBps.toString()))
        add(MediaFormatEntry("frame-rate", frameRateValue(fps)))
        add(MediaFormatEntry("i-frame-interval", iFrameIntervalSec.toString()))
        add(MediaFormatEntry("color-format", COLOR_FORMAT_SURFACE.toString()))
    }

    /**
     * A whole frame rate is an Int (the writer's own rule —
     * ExportFormats.frameRateValue), a fractional one a Float rendered
     * with up to 3 decimals (29.97).
     */
    internal fun frameRateValue(fps: Double): String {
        val r = Math.round(fps)
        return if (kotlin.math.abs(fps - r) < 1e-6) r.toString() else String.format(java.util.Locale.ROOT, "%.3f", fps)
    }

    /** COLOR_FormatSurface — the Surface path the trial and the export share. */
    const val COLOR_FORMAT_SURFACE = 2130708361
}
