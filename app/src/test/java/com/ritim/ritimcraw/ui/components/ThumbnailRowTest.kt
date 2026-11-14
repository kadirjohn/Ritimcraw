package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ThumbnailRow tests (owner request 2026-11-14, the aspect-ratio
 * extension; the manual-rotation extension the same day): the pure
 * height arithmetic behind the Library row's ratio-true thumbnail —
 * width fixed at 76dp, height 76 * (frameH / frameW) clamped to the
 * 40..120dp band so the list stays scannable, and the 76dp square
 * whenever the record's dims are unknown (0x0 — never probed; the
 * placeholder must not guess a ratio). The rotation-aware overload maps
 * the recorded dims through RotationState.rotatedDims FIRST, so a
 * quarter turn swaps the displayed ratio — the same swap the
 * re-developed icon file carries.
 */
class ThumbnailRowTest {

    // --- the square precedents -------------------------------------------

    @Test
    fun unknown_dims_keep_the_square() {
        // 0x0 (never probed), and each degenerate half: the placeholder
        // box stays the shipped 76dp square — no guessed ratio.
        assertEquals(76f, ThumbnailRow.heightDp(0, 0), 0f)
        assertEquals(76f, ThumbnailRow.heightDp(3840, 0), 0f)
        assertEquals(76f, ThumbnailRow.heightDp(0, 2160), 0f)
        assertEquals(76f, ThumbnailRow.heightDp(-1, -1), 0f)
    }

    @Test
    fun square_frame_keeps_the_square() {
        assertEquals(76f, ThumbnailRow.heightDp(1000, 1000), 0f)
    }

    // --- ratio-true heights -----------------------------------------------

    @Test
    fun vertical_frame_is_taller_than_wide() {
        // 9:16 portrait (2160x3840): raw 135.1dp clamps to the 120dp
        // ceiling — the row stays scannable, the icon letterboxes
        // slightly instead of cropping.
        assertEquals(120f, ThumbnailRow.heightDp(2160, 3840), 0f)
        // 3:4 portrait (3000x4000): exactly 76 * 4/3.
        assertEquals(76f * 4f / 3f, ThumbnailRow.heightDp(3000, 4000), 1e-6f)
    }

    @Test
    fun widescreen_frame_is_shorter_than_tall() {
        // 16:9 (3840x2160): exactly 76 * 9/16 = 42.75 — within the band.
        assertEquals(76f * 9f / 16f, ThumbnailRow.heightDp(3840, 2160), 1e-6f)
        // 4:3 open gate (4000x3000): exactly 76 * 3/4 = 57.
        assertEquals(57f, ThumbnailRow.heightDp(4000, 3000), 0f)
    }

    @Test
    fun extreme_wide_clamps_to_the_floor() {
        // 21:9 anamorphic (4032x1728): raw ~33dp clamps up to the 40dp
        // floor — the row keeps a touchable thumbnail.
        assertEquals(40f, ThumbnailRow.heightDp(4032, 1728), 0f)
    }

    @Test
    fun height_never_leaves_the_band() {
        // Whatever the recorded ratio, the height lands in [40, 120].
        for (ratio in listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 10f)) {
            val h = ThumbnailRow.heightDp(1000, (1000 * ratio).toInt())
            assert(h in 40f..120f) { "ratio $ratio gave height $h" }
        }
    }

    // --- the manual-rotation extension ------------------------------------
    // heightDp(w, h, deg): the recorded dims map through
    // RotationState.rotatedDims FIRST, so a quarter turn swaps the
    // displayed ratio — the same swap the re-developed icon file carries.

    @Test
    fun zero_rotation_matches_the_plain_height() {
        // The default (never rotated) is exactly the two-arg form.
        assertEquals(
            ThumbnailRow.heightDp(4000, 3000),
            ThumbnailRow.heightDp(4000, 3000, 0),
            0f,
        )
        assertEquals(
            ThumbnailRow.heightDp(2160, 3840),
            ThumbnailRow.heightDp(2160, 3840, 0),
            0f,
        )
    }

    @Test
    fun quarter_turns_swap_the_displayed_ratio() {
        // A 4:3 open gate the user rotated 90 (owner request 2026-11-14:
        // the icon must match the rotated look) displays its VERTICAL
        // ratio: 4000x3000 -> 3000x4000, height 76 * 4/3.
        assertEquals(76f * 4f / 3f, ThumbnailRow.heightDp(4000, 3000, 90), 1e-6f)
        // Both quarter turns swap (270 is the other one).
        assertEquals(76f * 4f / 3f, ThumbnailRow.heightDp(4000, 3000, 270), 1e-6f)
        // And the swap is symmetric: a portrait clip rotated upright shows
        // its landscape ratio (2160x3840 -> 3840x2160, 76 * 9/16).
        assertEquals(76f * 9f / 16f, ThumbnailRow.heightDp(2160, 3840, 90), 1e-6f)
    }

    @Test
    fun half_turn_keeps_the_recorded_ratio() {
        // 180 flips the pixels but not the dims: the height is the plain
        // form's, whatever the recorded ratio.
        assertEquals(57f, ThumbnailRow.heightDp(4000, 3000, 180), 0f)
        assertEquals(120f, ThumbnailRow.heightDp(2160, 3840, 180), 0f)
    }

    @Test
    fun rotation_normalizes_before_deciding() {
        // 450 = 90 + a full turn: the swap still applies (the pure
        // RotationState.normalize under rotatedDims).
        assertEquals(
            ThumbnailRow.heightDp(4000, 3000, 90),
            ThumbnailRow.heightDp(4000, 3000, 450),
            0f,
        )
    }

    @Test
    fun rotated_unknown_dims_keep_the_square() {
        // Unknown dims stay the 76dp square whatever the rotation — the
        // placeholder never guesses a ratio (rotatedDims passes
        // non-positive dims through).
        assertEquals(76f, ThumbnailRow.heightDp(0, 0, 90), 0f)
        assertEquals(76f, ThumbnailRow.heightDp(3840, 0, 90), 0f)
    }

    @Test
    fun rotated_height_still_clamps_to_the_band() {
        // The clamps survive the swap: a 21:9 anamorphic rotated to 9:21
        // asks for ~177dp and clamps to the 120dp ceiling; a 9:16
        // portrait rotated to 16:9 lands inside the band.
        assertEquals(120f, ThumbnailRow.heightDp(4032, 1728, 90), 0f)
        assertEquals(76f * 9f / 16f, ThumbnailRow.heightDp(2160, 3840, 90), 1e-6f)
        // And whatever the rotation, the height lands in [40, 120].
        for (deg in listOf(0, 90, 180, 270)) {
            for (ratio in listOf(0.1f, 0.25f, 0.5f, 1f, 2f, 4f, 10f)) {
                val h = ThumbnailRow.heightDp(1000, (1000 * ratio).toInt(), deg)
                assert(h in 40f..120f) { "deg $deg ratio $ratio gave height $h" }
            }
        }
    }
}
