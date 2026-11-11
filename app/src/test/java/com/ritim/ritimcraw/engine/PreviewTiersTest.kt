package com.ritim.ritimcraw.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreviewTiers tests: the tier policies. The constants themselves are
 * exercised by every render-path test; this suite locks the policy
 * decisions — temporal NR (DN-08 graceful degradation) and the gyro
 * stabilization warp (gyro Phase 5) apply at the settle/paused tier and
 * above, and are skipped at the interactive drag and playback tiers.
 */
class PreviewTiersTest {

    // ---- temporalNrAppliesAt (DN-08 tier policy) ----

    @Test
    fun temporal_applies_at_paused_tier() {
        // The settle/paused render (1280) is the one that can afford the
        // full-res merge (~85-107 ms measured on the 4K fixture).
        assertTrue(PreviewTiers.temporalNrAppliesAt(PreviewTiers.PAUSED_MAX_DIM))
    }

    @Test
    fun temporal_skipped_at_playback_tier() {
        // Playback (720) targets ~15 fps: the merge alone would eat the
        // whole 66 ms/frame budget. Playback shows the pristine center.
        assertFalse(PreviewTiers.temporalNrAppliesAt(PreviewTiers.PLAYBACK_MAX_DIM))
    }

    @Test
    fun temporal_skipped_at_drag_tier() {
        // The slider-drag tier (480) renders per drag step: a full-res
        // merge per step would stall the finger. The settle render applies
        // the effect.
        assertFalse(PreviewTiers.temporalNrAppliesAt(SliderTiers.DRAG_MAX_DIM))
    }

    @Test
    fun temporal_applies_above_the_paused_tier() {
        // Any caller above the paused tier (e.g. a future full-res preview)
        // keeps temporal NR — the gate floors, it does not cap.
        assertTrue(PreviewTiers.temporalNrAppliesAt(PreviewTiers.PAUSED_MAX_DIM + 1))
        assertTrue(PreviewTiers.temporalNrAppliesAt(3840))
    }

    // ---- stabilizationAppliesAt (gyro Phase 5 warp tier policy) ----

    @Test
    fun stab_applies_at_paused_tier() {
        // The warp runs on the DEVELOPED thumbnail, so its cost scales with
        // maxDim: measured 5.01187 ms/frame at 1280x720 (commit b's warpFrame
        // doctest) — inside the paused tier's budget.
        assertTrue(PreviewTiers.stabilizationAppliesAt(PreviewTiers.PAUSED_MAX_DIM))
    }

    @Test
    fun stab_skipped_at_playback_tier() {
        // Playback (720) shows the unstabilized frame; the effect lands on
        // the settle render (the temporal NR precedent — one policy, one
        // place).
        assertFalse(PreviewTiers.stabilizationAppliesAt(PreviewTiers.PLAYBACK_MAX_DIM))
    }

    @Test
    fun stab_skipped_at_drag_tier() {
        // Slider drags (480) render per drag step; the settle render warps.
        assertFalse(PreviewTiers.stabilizationAppliesAt(SliderTiers.DRAG_MAX_DIM))
    }

    @Test
    fun stab_skipped_at_source_resolution_follow_up() {
        // SOURCE_MAX_DIM (0) FAILS the gate exactly like temporal NR: the
        // single paused follow-up render at the recorded resolution skips
        // the warp too — the full-quality stabilized frame comes from the
        // UNGATED export (nativeExportFrame), never from this gate.
        assertFalse(PreviewTiers.stabilizationAppliesAt(PreviewTiers.SOURCE_MAX_DIM))
    }

    @Test
    fun stab_applies_above_the_paused_tier() {
        // The gate floors, it does not cap (the temporal NR shape).
        assertTrue(PreviewTiers.stabilizationAppliesAt(PreviewTiers.PAUSED_MAX_DIM + 1))
        assertTrue(PreviewTiers.stabilizationAppliesAt(3840))
    }
}
