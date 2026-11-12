package com.ritim.ritimcraw.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreviewTiers tests: the tier policies. The constants themselves are
 * exercised by every render-path test; this suite locks the policy
 * decisions — temporal NR (DN-08 graceful degradation) applies at the
 * settle/paused tier and above and is skipped at the interactive drag
 * and playback tiers, while the gyro stabilization warp (gyro Phase 5,
 * audit fix 2026-11-12) applies at EVERY tier: its cost scales with the
 * developed buffer (measured ~1-5 ms at the preview tiers), and skipping
 * it at any tier made playback/drag/full-res-follow-up disagree with the
 * paused frame — and made the full-res follow-up visibly REPLACE the
 * stabilized paused frame with an unstabilized one.
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
    //
    // Audit fix 2026-11-12: the warp applies at EVERY tier. The old gate
    // (maxDim >= PAUSED) skipped playback/drag AND the SOURCE_MAX_DIM
    // follow-up, so the paused clip's stabilized 1280 frame was visibly
    // replaced by an unstabilized full-res frame at the settle, and
    // playback disagreed with the paused frame. The warp's cost scales
    // with the developed buffer (measured ~1-5 ms at the preview tiers,
    // test_warp's tier harness), so every tier can afford it — unlike
    // temporal NR whose cost is tier-independent at ~85-107 ms.

    @Test
    fun stab_applies_at_paused_tier() {
        // The warp runs on the DEVELOPED thumbnail, so its cost scales with
        // maxDim: measured plan+warp ≈ 5.3 ms/frame at 1280x720 (test_warp's
        // tier harness, 2026-11-12) — inside the paused tier's budget.
        assertTrue(PreviewTiers.stabilizationAppliesAt(PreviewTiers.PAUSED_MAX_DIM))
    }

    @Test
    fun stab_applies_at_playback_tier() {
        // Playback (720) warps too: plan+warp ≈ 1.9 ms/frame measured —
        // far inside the ~66 ms/frame playback budget, and the only honest
        // preview (what playback shows matches the paused frame and the
        // export up to resolution).
        assertTrue(PreviewTiers.stabilizationAppliesAt(PreviewTiers.PLAYBACK_MAX_DIM))
    }

    @Test
    fun stab_applies_at_drag_tier() {
        // Slider drags (480) warp per drag step: plan+warp ≈ 1.0 ms
        // measured — the drag tier stays interactive and the slider no
        // longer flips the stabilization on/off between drag and settle.
        assertTrue(PreviewTiers.stabilizationAppliesAt(SliderTiers.DRAG_MAX_DIM))
    }

    @Test
    fun stab_applies_at_source_resolution_follow_up() {
        // SOURCE_MAX_DIM (0) is the single paused follow-up at the recorded
        // resolution — it warps like every tier now (measured plan+warp
        // ≈ 45.6 ms at 3840x2160, a one-off settle render that can afford
        // it). The old skip made the follow-up REPLACE the stabilized
        // paused frame with an unstabilized one — the visible jump the
        // audit found.
        assertTrue(PreviewTiers.stabilizationAppliesAt(PreviewTiers.SOURCE_MAX_DIM))
    }

    @Test
    fun stab_applies_above_the_paused_tier() {
        // The gate applies everywhere (no floor, no cap).
        assertTrue(PreviewTiers.stabilizationAppliesAt(PreviewTiers.PAUSED_MAX_DIM + 1))
        assertTrue(PreviewTiers.stabilizationAppliesAt(3840))
    }
}
