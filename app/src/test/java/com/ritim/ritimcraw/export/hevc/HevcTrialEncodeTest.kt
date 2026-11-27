package com.ritim.ritimcraw.export.hevc

import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcProbeResult
import com.ritim.ritimcraw.export.hevc.HevcPreflight.HevcVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The G4 trial-encode decision layer (HevcTrialEncode.decide): the pure
 * fold over the verdict's trial requirement, the cache's stored outcome
 * and the runner — with the CACHE consulted before any runner call and
 * BOTH outcomes cached (the plan's K1). The runner is a FAKE here (the
 * real short encode is the device leg's; every device claim stays
 * "doğrulanmadı").
 */
class HevcTrialEncodeTest {

    /** A SYNTHETIC Supported verdict with the trial flag set as asked (API 34 = true, API 37 = false). */
    private fun trialVerdict(trial: Boolean = true): HevcVerdict.Supported {
        val probe = HevcProbeResult(
            hevcEncoderName = "c2.qti.hevc.encoder",
            listedProfiles = listOf(1, 2),
            acceptsP010 = true,
            sizeRateAccepted = true,
            // API 34 walks the hdr-editing fallback -> trialEncodeRequired;
            // API 37 asks hlg-editing -> no trial. The decide() fold is the
            // pinned model — this helper only exercises BOTH verdicts.
            deviceApiLevel = if (trial) 34 else 37,
            hdrEditingSupported = true,
            hlgEditingSupported = !trial,
            avcEncoderName = "c2.qti.avc.encoder",
        )
        return HevcPreflight.decide(HevcProfile.HLG, probe) as HevcVerdict.Supported
    }

    private fun key(buildId: String = "AQ3A.240812.001") = HevcTrialKey.of(
        HevcProfile.HLG, 3840, 2160, 34, "c2.qti.hevc.encoder", buildId,
    )

    // ---- NotRequired: the verdict that did not ask ----

    @Test
    fun a_verdict_without_the_trial_flag_never_runs_or_caches() {
        val cache = HevcTrialCache()
        var runs = 0
        val result = HevcTrialEncode.decide(
            trialVerdict(trial = false), key(), cache,
        ) { runs++; HevcTrialEncode.Result.Passed }
        assertEquals(HevcTrialEncode.Result.NotRequired, result)
        assertEquals(0, runs)
        assertEquals(0, cache.size())
    }

    // ---- the runner runs once per key, both outcomes cached ----

    @Test
    fun a_passing_trial_runs_once_and_the_second_ask_is_served_from_the_cache() {
        val cache = HevcTrialCache()
        var runs = 0
        val first = HevcTrialEncode.decide(trialVerdict(), key(), cache) {
            runs++; HevcTrialEncode.Result.Passed
        }
        assertEquals(HevcTrialEncode.Result.Passed, first)
        assertEquals(1, runs)
        val second = HevcTrialEncode.decide(trialVerdict(), key(), cache) {
            runs++; HevcTrialEncode.Result.Passed
        }
        assertEquals(HevcTrialEncode.Result.Passed, second)
        assertEquals("the trial re-heated for the same key", 1, runs)
        assertEquals(1, cache.size())
    }

    @Test
    fun a_refused_trial_is_cached_positively_and_never_re_heats() {
        val cache = HevcTrialCache()
        var runs = 0
        val first = HevcTrialEncode.decide(trialVerdict(), key(), cache) {
            runs++
            HevcTrialEncode.Result.Refused("the decoder reported an 8-bit output")
        }
        assertTrue(first is HevcTrialEncode.Result.Refused)
        assertEquals("the decoder reported an 8-bit output", (first as HevcTrialEncode.Result.Refused).reason)
        assertEquals(1, runs)
        // The SAME shape asks again: the cached refusal answers, the
        // runner never runs (a no-HLG device pays the trial ONCE).
        val second = HevcTrialEncode.decide(trialVerdict(), key(), cache) {
            runs++; HevcTrialEncode.Result.Passed // would be wrong; must not run
        }
        assertTrue(second is HevcTrialEncode.Result.Refused)
        assertEquals(1, runs)
        assertEquals(1, cache.size())
    }

    @Test
    fun a_new_build_id_is_a_new_key_and_the_trial_runs_again() {
        val cache = HevcTrialCache()
        var runs = 0
        HevcTrialEncode.decide(trialVerdict(), key("B.001"), cache) {
            runs++; HevcTrialEncode.Result.Passed
        }
        HevcTrialEncode.decide(trialVerdict(), key("B.002"), cache) {
            runs++; HevcTrialEncode.Result.Passed
        }
        assertEquals(2, runs)
        assertEquals(2, cache.size())
    }

    @Test
    fun a_cached_outcome_wins_before_the_runner_ever_sees_the_ask() {
        val cache = HevcTrialCache()
        cache.record(key(), HevcTrialCache.Outcome(passed = false, reason = "configure refused HLG"))
        var runs = 0
        val result = HevcTrialEncode.decide(trialVerdict(), key(), cache) {
            runs++; HevcTrialEncode.Result.Passed
        }
        assertTrue(result is HevcTrialEncode.Result.Refused)
        assertEquals("configure refused HLG", (result as HevcTrialEncode.Result.Refused).reason)
        assertEquals(0, runs)
    }

    // ---- the trial's MediaFormat contract (what the device leg sets) ----

    @Test
    fun the_trial_format_carries_the_profile_s_six_keys_then_the_job_shape() {
        val entries = HevcTrialEncode.trialFormatEntries(
            HevcProfile.HLG, width = 3840, height = 2160,
            bitrateBps = 75_000_000, fps = 30.0, iFrameIntervalSec = 1f,
        )
        // The profile's own six keys, in the profile's own order:
        val first6 = entries.take(6).map { it.key to it.value }
        assertEquals(
            HevcProfile.HLG.mediaFormatEntries().map { it.key to it.value },
            first6,
        )
        // Then the job shape: width, height, bitrate, frame-rate,
        // i-frame-interval, color-format (COLOR_FormatSurface).
        val byKey = entries.associate { it.key to it.value }
        assertEquals("3840", byKey["width"])
        assertEquals("2160", byKey["height"])
        assertEquals("75000000", byKey["bitrate"])
        assertEquals("30", byKey["frame-rate"])
        assertEquals("1.0", byKey["i-frame-interval"])
        assertEquals(HevcTrialEncode.COLOR_FORMAT_SURFACE.toString(), byKey["color-format"])
        // KEY_PROFILE and KEY_LEVEL travel TOGETHER (the docs' rule):
        assertEquals("2", byKey["profile"])
        assertEquals("16384", byKey["level"])
        // HLG's color tags are the profile's own (BT2020/limited/HLG):
        assertEquals("6", byKey["color-standard"])
        assertEquals("2", byKey["color-range"])
        assertEquals("7", byKey["color-transfer"])
        // HLG never carries hdr-static-info (scene-referred):
        assertEquals(null, byKey["hdr-static-info"])
    }

    @Test
    fun a_fractional_frame_rate_is_a_float_string() {
        val entries = HevcTrialEncode.trialFormatEntries(
            HevcProfile.HLG, 2160, 3840, 50_000_000, fps = 29.97, iFrameIntervalSec = 1f,
        )
        assertEquals("29.970", entries.first { it.key == "frame-rate" }.value)
        assertEquals("2160", entries.first { it.key == "width" }.value)
        assertEquals("3840", entries.first { it.key == "height" }.value)
    }

    // ---- the refusal's honesty: the reason rides the result ----

    @Test
    fun the_refusal_reason_is_the_runner_s_own_sentence_never_rewritten() {
        val cache = HevcTrialCache()
        val reason = "configure: c2.qti.hevc.encoder rejected the color-transfer=7 key"
        val result = HevcTrialEncode.decide(trialVerdict(), key(), cache) {
            HevcTrialEncode.Result.Refused(reason)
        }
        assertEquals(reason, (result as HevcTrialEncode.Result.Refused).reason)
        // And the cache holds the SAME sentence:
        assertEquals(reason, cache.outcome(key())!!.reason)
    }

    @Test
    fun a_cached_pass_without_a_reason_is_a_clean_pass() {
        val cache = HevcTrialCache()
        cache.record(key(), HevcTrialCache.Outcome(passed = true))
        val result = HevcTrialEncode.decide(trialVerdict(), key(), cache) { HevcTrialEncode.Result.Refused("x") }
        assertEquals(HevcTrialEncode.Result.Passed, result)
    }
}
