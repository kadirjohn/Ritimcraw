package com.ritim.ritimcraw.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TabSwipe tests (owner request 2026-11-26 D3): the pure direction
 * mapping and flip decision behind the browse tabs' horizontal swipe —
 * "like Telegram's folder switch". Everything here is JVM-pure (no
 * Compose): the same [TabSwipe.target] the AppNav gesture wiring calls
 * at the finger's lift. The gesture WIRING itself (child-consumption
 * arbitration, the drag-follow offset actually painting) is
 * device-only — "doğrulanmadı", see the night report's checklist.
 *
 * The mapping under test, stated once: the four tabs are pages in
 * display order LIBRARY -> PROJECTS -> EXPORTS -> SETTINGS; a finger
 * moving RIGHT flips one page LEFT (previous tab), a finger moving LEFT
 * flips one page RIGHT (next tab). No wrap-around at either end.
 */
class TabSwipeTest {

    // ---- direction mapping (the Telegram convention) ----

    @Test
    fun finger_left_on_library_flips_to_projects() {
        // dx < 0 (finger moves left) = next tab to the RIGHT.
        assertEquals(
            Destination.PROJECTS,
            TabSwipe.target(
                current = Destination.LIBRARY,
                dxDp = -80f, dyDp = 0f, velocityX = 0f,
            ),
        )
    }

    @Test
    fun finger_left_on_projects_flips_to_exports() {
        assertEquals(
            Destination.EXPORTS,
            TabSwipe.target(Destination.PROJECTS, dxDp = -80f, dyDp = 0f, velocityX = 0f),
        )
    }

    @Test
    fun finger_left_on_exports_flips_to_settings() {
        assertEquals(
            Destination.SETTINGS,
            TabSwipe.target(Destination.EXPORTS, dxDp = -80f, dyDp = 0f, velocityX = 0f),
        )
    }

    @Test
    fun finger_right_on_settings_flips_to_exports() {
        // dx > 0 (finger moves right) = previous tab to the LEFT.
        assertEquals(
            Destination.EXPORTS,
            TabSwipe.target(Destination.SETTINGS, dxDp = 80f, dyDp = 0f, velocityX = 0f),
        )
    }

    @Test
    fun finger_right_on_projects_flips_to_library() {
        assertEquals(
            Destination.LIBRARY,
            TabSwipe.target(Destination.PROJECTS, dxDp = 80f, dyDp = 0f, velocityX = 0f),
        )
    }

    @Test
    fun finger_right_on_exports_flips_to_projects() {
        assertEquals(
            Destination.PROJECTS,
            TabSwipe.target(Destination.EXPORTS, dxDp = 80f, dyDp = 0f, velocityX = 0f),
        )
    }

    // ---- the ends: no wrap-around ----

    @Test
    fun finger_right_on_leftmost_library_is_a_no_op() {
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = 120f, dyDp = 0f, velocityX = 2000f),
        )
    }

    @Test
    fun finger_left_on_rightmost_settings_is_a_no_op() {
        assertNull(
            TabSwipe.target(Destination.SETTINGS, dxDp = -120f, dyDp = 0f, velocityX = -2000f),
        )
    }

    // ---- velocity: the fling rules ----

    @Test
    fun a_fling_below_the_drag_threshold_still_flips() {
        // 30dp of drag, 600dp/s release velocity — the quick snap case.
        assertEquals(
            Destination.PROJECTS,
            TabSwipe.target(Destination.LIBRARY, dxDp = -30f, dyDp = 0f, velocityX = -600f),
        )
    }

    @Test
    fun a_slow_drift_below_both_thresholds_flips_nothing() {
        // 30dp of drag at 100dp/s: neither rule qualifies.
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = -30f, dyDp = 0f, velocityX = -100f),
        )
    }

    @Test
    fun the_velocity_sign_decides_a_fling_even_against_the_position() {
        // Dragged LEFT 50dp past the threshold, but flung back RIGHT at
        // release: the pure rule follows the velocity (the pager
        // convention — settling never fights the fling). On LIBRARY a
        // rightward fling has nowhere to go: null (the end clamp).
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = -50f, dyDp = 0f, velocityX = 800f),
        )
        // The same call one tab in flips LEFT (back toward LIBRARY):
        assertEquals(
            Destination.LIBRARY,
            TabSwipe.target(Destination.PROJECTS, dxDp = -50f, dyDp = 0f, velocityX = 800f),
        )
    }

    @Test
    fun velocity_at_exactly_the_threshold_flips() {
        // >= is the rule: 300dp/s is the boundary and qualifies.
        assertEquals(
            Destination.PROJECTS,
            TabSwipe.target(Destination.LIBRARY, dxDp = -10f, dyDp = 0f, velocityX = -300f),
        )
    }

    // ---- the positional threshold boundary ----

    @Test
    fun drag_at_exactly_the_threshold_flips() {
        assertEquals(
            Destination.PROJECTS,
            TabSwipe.target(Destination.LIBRARY, dxDp = -40f, dyDp = 0f, velocityX = 0f),
        )
        assertEquals(
            Destination.LIBRARY,
            TabSwipe.target(Destination.PROJECTS, dxDp = 40f, dyDp = 0f, velocityX = 0f),
        )
    }

    @Test
    fun drag_just_under_the_threshold_without_velocity_flips_nothing() {
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = -39.9f, dyDp = 0f, velocityX = 0f),
        )
    }

    // ---- vertical-dominated drags never flip ----

    @Test
    fun a_vertical_drag_flips_nothing_even_when_fast() {
        // A vertical list scroll: dy dominates, huge dy velocity.
        assertNull(
            TabSwipe.target(
                Destination.LIBRARY,
                dxDp = -20f, dyDp = -300f, velocityX = -800f,
            ),
        )
    }

    @Test
    fun a_diagonal_drag_just_under_the_bias_flips_nothing() {
        // |dx| must be >= 1.25 * |dy|: 60 vs 50 fails (60 < 62.5).
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = -60f, dyDp = -50f, velocityX = 0f),
        )
    }

    @Test
    fun a_diagonal_drag_at_the_bias_flips() {
        // 62.5 vs 50 is exactly the boundary: >= qualifies.
        assertEquals(
            Destination.PROJECTS,
            TabSwipe.target(Destination.LIBRARY, dxDp = -62.5f, dyDp = -50f, velocityX = 0f),
        )
    }

    @Test
    fun sideways_drift_during_a_long_vertical_scroll_accumulates_honestly() {
        // The wiring accumulates TOTALS: a 300dp vertical scroll with
        // 50dp of sideways drift still fails the bias (50 < 1.25*300).
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = -50f, dyDp = -300f, velocityX = -80f),
        )
    }

    // ---- degenerate inputs ----

    @Test
    fun a_zero_drag_is_a_no_op() {
        assertNull(
            TabSwipe.target(Destination.LIBRARY, dxDp = 0f, dyDp = 0f, velocityX = 0f),
        )
    }

    // ---- the drag-follow offset (the Telegram page-nudge) ----

    @Test
    fun the_follow_offset_is_proportional_to_the_drag() {
        // Half the width of drag, capped at 15%: the fraction rule.
        assertEquals(
            0.15f,
            TabSwipe.followOffsetFraction(dxDp = 200f, widthDp = 400f),
            0f,
        )
        // A 20dp nudge on a 400dp page: 5% of the width.
        assertEquals(
            0.05f,
            TabSwipe.followOffsetFraction(dxDp = 20f, widthDp = 400f),
            0f,
        )
    }

    @Test
    fun the_follow_offset_caps_in_both_directions() {
        assertEquals(
            0.15f,
            TabSwipe.followOffsetFraction(dxDp = 2000f, widthDp = 400f),
            0f,
        )
        assertEquals(
            -0.15f,
            TabSwipe.followOffsetFraction(dxDp = -2000f, widthDp = 400f),
            0f,
        )
    }

    @Test
    fun the_follow_offset_is_signed_by_the_drag_direction() {
        // Finger right = positive fraction (the page moves WITH the
        // finger, telegram-style).
        assertEquals(
            0.025f,
            TabSwipe.followOffsetFraction(dxDp = 20f, widthDp = 800f),
            0f,
        )
        assertEquals(
            -0.025f,
            TabSwipe.followOffsetFraction(dxDp = -20f, widthDp = 800f),
            0f,
        )
    }

    @Test
    fun a_degenerate_width_nudges_nothing() {
        // onSizeChanged has not run (0 width): no offset, ever.
        assertEquals(
            0f,
            TabSwipe.followOffsetFraction(dxDp = -200f, widthDp = 0f),
            0f,
        )
    }

    // ---- the release reset signal (the smoothness rework, 2026-11-27) ----

    @Test
    fun zero_is_the_release_reset_and_must_animate_not_snap() {
        // The wiring's gesture-end signal is dragDelta(0f): the release
        // must settle with the spring, while every live delta snaps.
        // Zero is also the resting value pre-slop, where a snap is a
        // no-op — one rule covers both.
        assertTrue(TabSwipe.isReleaseReset(0f))
        assertFalse(TabSwipe.isReleaseReset(0.1f))
        assertFalse(TabSwipe.isReleaseReset(-80f))
        assertFalse(TabSwipe.isReleaseReset(2000f))
    }

    // ---- the v2 smoothness pass (owner request 2026-11-27, second round:
    // "biraz daha smooth olabilir") — the pure halves of the two root
    // causes found in the v1 wiring. The ANIMATION halves (spring specs,
    // easing, slide distance) are visual tuning, JVM-untestable — device
    // checklist carries them. ----

    @Test
    fun a_live_drag_delta_needs_no_release_velocity() {
        // ROOT CAUSE 1 (v1): the settle spring claimed to inherit the
        // finger's velocity, but every live delta went through
        // Animatable.snapTo, which RESETS the internal velocity — the
        // release spring always started from a standstill, the page
        // visibly parked ("animasyon duruyor"). The v2 contract: the
        // gesture wiring must hand the RELEASE VELOCITY (dp/s, signed) to
        // the settle as initialVelocity — this pure rule only shapes what
        // that number MEANS so the wiring passes it raw (sign included):
        // nothing here zeroes or filters it.
        assertEquals(
            -600f,
            TabSwipe.settleInitialVelocityDpPerS(
                releaseVelocityX = -600f,
                dxDp = -80f,
            ),
            0f,
        )
        // A slow deliberate drag releases near zero velocity: the settle
        // starts from a standstill — still passed through raw.
        assertEquals(
            0f,
            TabSwipe.settleInitialVelocityDpPerS(
                releaseVelocityX = 0f,
                dxDp = 120f,
            ),
            0f,
        )
        // The sign travels with the release velocity, never with the
        // capped follow offset's sign — a fling-back release (finger left
        // but flung right) settles in the VELOCITY's direction, the pager
        // convention the pure flip rule already follows.
        assertEquals(
            800f,
            TabSwipe.settleInitialVelocityDpPerS(
                releaseVelocityX = 800f,
                dxDp = -50f,
            ),
            0f,
        )
    }

    @Test
    fun the_flip_release_settle_flies_toward_the_slide_not_back_to_zero() {
        // ROOT CAUSE 2 (v1): on a flip, the spring pulled the nudged page
        // BACK toward offset 0 (opposite the slide's travel) — a visible
        // jerk into the slide, the "duruyor, sonra geçiliyor" feel. The
        // v2 rule: the settle departs in the SLIDE's exit direction
        // (exitTravelSign), so the nudge hand-off and the slide push the
        // page the SAME way — the slide's own pixels carry the arrival.
        // A NO-flip release still settles to zero (the page returns
        // home).
        // Dragged LEFT past the threshold on PROJECTS: forward flip, the
        // old page exits LEFT — settle target LEFT (negative fraction).
        assertEquals(
            -TabSwipe.FOLLOW_DEPART_FRACTION,
            TabSwipe.settleTargetFraction(
                TabSwipe.exitTravelSign(Destination.PROJECTS, Destination.EXPORTS),
            ),
            0f,
        )
        // Dragged RIGHT on PROJECTS (flips back to LIBRARY): backward
        // flip, the old page exits RIGHT — settle target positive.
        assertEquals(
            TabSwipe.FOLLOW_DEPART_FRACTION,
            TabSwipe.settleTargetFraction(
                TabSwipe.exitTravelSign(Destination.PROJECTS, Destination.LIBRARY),
            ),
            0f,
        )
        // A non-flip release: home, zero — the page comes back to rest.
        assertEquals(
            0f,
            TabSwipe.settleTargetFraction(
                TabSwipe.exitTravelSign(Destination.PROJECTS, null),
            ),
            0f,
        )
    }

    @Test
    fun the_fling_back_flip_settles_with_the_fling_not_against_the_slide() {
        // The edge case the drag-sign rule got wrong: the finger dragged
        // LEFT 50dp on PROJECTS but flung back RIGHT at release —
        // [target] follows the VELOCITY (the pager convention), flipping
        // BACK to LIBRARY: a BACKWARD flip whose slide exits the old page
        // to the RIGHT. The settle must depart RIGHT (with the fling and
        // the slide), not LEFT (with the stale nudge sign).
        assertEquals(
            1f,
            TabSwipe.exitTravelSign(Destination.PROJECTS, Destination.LIBRARY),
            0f,
        )
        // And the ordinary drag-driven case: flung LEFT on LIBRARY flips
        // forward to PROJECTS, exit travel LEFT.
        assertEquals(
            -1f,
            TabSwipe.exitTravelSign(Destination.LIBRARY, Destination.PROJECTS),
            0f,
        )
    }

    @Test
    fun the_depart_fraction_is_larger_than_the_follow_cap_so_the_page_leaves() {
        // The depart target must sit BEYOND the 15% follow cap, or the
        // settle would be a tiny correction the eye reads as a twitch,
        // not a departure. 60% is nearly half the slide's full 1/3-width
        // travel — the nudged page visibly leaves while the slide fades
        // it out; it never drags across the nav bar (the full-slide
        // concern the v1 comment pinned).
        assertTrue(TabSwipe.FOLLOW_DEPART_FRACTION > TabSwipe.FOLLOW_MAX_FRACTION)
        assertEquals(0.6f, TabSwipe.FOLLOW_DEPART_FRACTION, 0f)
    }
}
