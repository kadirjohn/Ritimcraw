package com.ritim.ritimcraw.export.hevc

import com.ritim.ritimcraw.export.hevc.HevcTrialCache.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The trial-encode cache key + store (plan E2 2.2, E2-a). The plan's
 * key component list, the positive-refusal rule, and the Build.ID
 * invalidation — pure JVM, no device.
 */
class HevcTrialCacheTest {

    private val key = HevcTrialKey.of(
        profile = HevcProfile.HLG,
        width = 3840, height = 2160,
        apiLevel = 34,
        codecName = "c2.qti.hevc.encoder",
        buildId = "AQ3A.240812.001",
    )

    // ---- the key's components (the plan's list, one field at a time) ----

    @Test
    fun the_key_carries_the_profiles_six_format_entries() {
        val rendered = key.render()
        // The full MediaFormat contract is IN the key: mime, profile,
        // level, color-standard, color-range, color-transfer.
        for (entry in HevcProfile.HLG.mediaFormatEntries()) {
            assertTrue("missing ${entry.key}", rendered.contains("${entry.key}=${entry.value}"))
        }
        assertEquals("video/hevc", HevcProfile.HLG.mime)
        // ...the HLG profile's own tags (not the SDR ones).
        assertTrue(rendered.contains("color-transfer=7"))
        assertTrue(rendered.contains("color-standard=6"))
    }

    @Test
    fun changing_any_key_component_changes_the_key() {
        val base = HevcTrialKey.of(HevcProfile.HLG, 3840, 2160, 34, "c2.qti.hevc.encoder", "B.001")
        // size, API level, codec name and Build.ID each move the key.
        assertNotEquals(base, HevcTrialKey.of(HevcProfile.HLG, 2160, 3840, 34, "c2.qti.hevc.encoder", "B.001"))
        assertNotEquals(base, HevcTrialKey.of(HevcProfile.HLG, 3840, 2160, 33, "c2.qti.hevc.encoder", "B.001"))
        assertNotEquals(base, HevcTrialKey.of(HevcProfile.HLG, 3840, 2160, 34, "OMX.qcom.video.encoder.hevc", "B.001"))
        assertNotEquals(base, HevcTrialKey.of(HevcProfile.HLG, 3840, 2160, 34, "c2.qti.hevc.encoder", "B.002"))
        // A different PROFILE (different contract) is a different key.
        assertNotEquals(base, HevcTrialKey.of(HevcProfile.HDR10, 3840, 2160, 34, "c2.qti.hevc.encoder", "B.001"))
        // And an identical request is the same key (purity).
        assertEquals(base, HevcTrialKey.of(HevcProfile.HLG, 3840, 2160, 34, "c2.qti.hevc.encoder", "B.001"))
    }

    @Test
    fun a_new_build_id_invalidates_the_key() {
        // The plan's rule: Build.ID changes the key, so a ROM update
        // never replays an old verdict. The codec name may stay the same.
        val oldRom = HevcTrialKey.of(HevcProfile.HLG, 3840, 2160, 34, "c2.qti.hevc.encoder", "AQ3A.240812.001")
        val newRom = oldRom.copy(buildId = "BQ3A.250101.002")
        assertNotEquals(oldRom, newRom)
        assertNotEquals(oldRom.render(), newRom.render())
        assertTrue(newRom.render().contains("build=BQ3A.250101.002"))
    }

    // ---- the store: one answer per key, both outcomes cached ----

    @Test
    fun a_recorded_pass_is_answered_from_the_cache_without_a_rerun() {
        val cache = HevcTrialCache()
        assertNull(cache.outcome(key))
        cache.record(key, Outcome(passed = true))
        assertEquals(Outcome(passed = true), cache.outcome(key))
        assertEquals(1, cache.size())
    }

    @Test
    fun a_refusal_is_cached_positively_and_replayed_without_a_rerun() {
        // The plan's K1 rule: a FAILED trial is cached too (with its
        // reason), or every export on a no-HLG device re-heats the trial.
        val cache = HevcTrialCache()
        val refused = Outcome(passed = false, reason = "decoder reported 8-bit output")
        cache.record(key, refused)
        assertEquals(refused, cache.outcome(key))
        // Recording it again does not duplicate the entry.
        cache.record(key, refused)
        assertEquals(1, cache.size())
        assertEquals(refused, cache.outcome(key))
    }

    @Test
    fun two_different_jobs_hold_two_entries_but_neither_re_runs() {
        val cache = HevcTrialCache()
        val portrait = key.copy(width = 2160, height = 3840)
        cache.record(key, Outcome(passed = true))
        cache.record(portrait, Outcome(passed = false, reason = "portrait refused"))
        assertEquals(2, cache.size())
        assertEquals(Outcome(passed = true), cache.outcome(key))
        assertEquals(Outcome(passed = false, reason = "portrait refused"), cache.outcome(portrait))
    }

    @Test
    fun the_hlg_fallback_path_only_needs_one_trial_per_job_shape() {
        // The integration shape the plan draws: an API-34 device asks
        // twice for the same HLG export shape; the second ask is served
        // from the cache (the size() proof: still one entry).
        val cache = HevcTrialCache()
        fun ask(): Outcome {
            return cache.outcome(key) ?: run {
                val outcome = Outcome(passed = true) // "the trial would run here"
                cache.record(key, outcome)
                outcome
            }
        }
        assertEquals(Outcome(passed = true), ask())
        assertEquals(Outcome(passed = true), ask())
        assertEquals(1, cache.size())
    }
}
