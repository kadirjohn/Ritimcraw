package com.ritim.ritimcraw.export.hevc

/**
 * The trial-encode cache — the pure key + in-memory store around the
 * API-34 fallback path (plan E2 2.2, package E2-a). When
 * [HevcPreflight.HevcVerdict.Supported.trialEncodeRequired] is true the
 * device leg must run a short trial encode before trusting the HLG
 * profile; the SAME device + SAME job shape must not re-heat the trial
 * on every export. This model defines what "same" means.
 *
 * THE KEY (the plan's own list, verbatim): the profile's FULL
 * MediaFormat contract (mime, profile, level, color-standard,
 * color-range, color-transfer) + the encode size + the device API level
 * + the chosen codec's NAME + Build.ID. Every component is DATA this
 * class composes; nothing queries a device here.
 *
 * WHY Build.ID IS IN THE KEY: the verdict the trial produces is a fact
 * about THIS ROM build, not a user preference. A ROM/kernel update can
 * change the codec's behavior, so a key that left Build.ID out could
 * replay a stale verdict against a new encoder. [HevcTrialKey.buildId]
 * is carried as a field the device leg fills from Build.ID; tests change
 * it to prove the key changes.
 *
 * THE CACHE ITSELF ([HevcTrialCache]) is process-lifetime by design (the
 * plan's recommendation): the codec list does not change inside a
 * process, and Build.ID changes come with a process restart. It is
 * thread-safe (the export engine and the sheet can both consult it).
 * POSITIVE-REFUSAL CACHING is the model's rule: a FAILED trial is cached
 * with its refusal reason exactly like a passed one, so a device that
 * cannot do HLG does not pay the trial cost on every export attempt —
 * the plan's K1 recommendation, owner-reviewed at the morning gate.
 *
 * PURE: no android.os.Build import (the field arrives as data), no
 * MediaFormat (the profile carries the contract), no I/O.
 */
object HevcTrialKey {

    /**
     * The cache key for one trial encode. The plan's components, each
     * one named field so the test can prove which change moves the key:
     * [profile]'s six MediaFormat entries, [width] x [height],
     * [apiLevel], [codecName], [buildId].
     */
    data class Key(
        val profile: HevcProfile,
        val width: Int,
        val height: Int,
        val apiLevel: Int,
        val codecName: String,
        val buildId: String,
    ) {
        /** The plan's key shape as one stable string (rendered, never parsed back). */
        fun render(): String =
            profile.mediaFormatEntries().joinToString(",", prefix = "", postfix = "") { "${it.key}=${it.value}" } +
                "|${width}x${height}|api=$apiLevel|codec=$codecName|build=$buildId"
    }

    /** Build the key the device leg caches a trial under. */
    fun of(
        profile: HevcProfile,
        width: Int,
        height: Int,
        apiLevel: Int,
        codecName: String,
        buildId: String,
    ): Key = Key(profile, width, height, apiLevel, codecName, buildId)
}

/**
 * The in-memory verdict store. One answer per [HevcTrialKey.Key], for
 * the process's lifetime; the same key is answered from the map, never
 * re-run. Both outcomes are cached: a pass and a refusal.
 */
class HevcTrialCache {

    /** What a completed trial concluded. [reason] names a refusal; null reason = passed. */
    data class Outcome(val passed: Boolean, val reason: String? = null)

    private val map = HashMap<HevcTrialKey.Key, Outcome>()

    /** The cached outcome for this key, or null when no trial ran yet. */
    fun outcome(key: HevcTrialKey.Key): Outcome? = synchronized(map) { map[key] }

    /** Record a trial's conclusion (pass or refusal) under [key]. */
    fun record(key: HevcTrialKey.Key, outcome: Outcome) {
        synchronized(map) { map[key] = outcome }
    }

    /** How many trials are cached (the test's no-reheat proof). */
    fun size(): Int = synchronized(map) { map.size }
}
