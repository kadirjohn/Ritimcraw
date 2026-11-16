package com.ritim.ritimcraw.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Theme slider gesture tests (settings v2 polish C4, spec workflow step 7:
 * "theme slider still commits once on release"). INSTRUMENTED — they need
 * a real window and the density-correct track geometry, so they run only
 * on a device/emulator (connectedDebugAndroidTest). The pure boundary
 * math (slot index, clamps, commit rule) is pinned host-side by
 * ThemeSliderMathTest; these pin the GESTURE contract on top of it:
 *
 * - a full-track drag commits EXACTLY ONCE (the index changed) and lands
 *   on the slot under the release point,
 * - a drag that ends where it started commits NOTHING (index unchanged),
 * - the slider still exposes its a11y recipe (contentDescription), so the
 *   semantics tree stays inspectable.
 *
 * Geometry (dp, from the spec): track 168dp wide, 3 slots of 54dp starting
 * at x=3dp. Slot centers: 3+27=30dp, 3+54+27=84dp, 3+108+27=138dp. The
 * test drags between slot centers so the release point is unambiguous.
 */
@RunWith(AndroidJUnit4::class)
class ThemeSliderTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(
        initialIndex: Int,
        onCommit: (Int) -> Unit,
    ) {
        composeRule.setContent {
            ThemeSlider(
                selectedIndex = initialIndex,
                onCommit = onCommit,
                onDisplayIndexChange = {},
                sliderLabel = "Theme",
                previousLabel = "Previous theme",
                nextLabel = "Next theme",
                stateLabels = listOf("Light", "System", "Dark"),
            )
        }
        composeRule.waitForIdle()
    }

    @Test
    fun fullTrackDragCommitsExactlyOnceAndLandsOnReleaseSlot() {
        var commits = 0
        var committedIndex = -1
        setContent(initialIndex = 0) { index ->
            commits++
            committedIndex = index
        }
        // Drag from slot 0's center to slot 2's center (dp -> px handled
        // by the injection scope's density).
        composeRule
            .onNodeWithContentDescription("Theme")
            .performTouchInput {
                // Slot 0's center -> slot 2's center: the finger moves
                // RIGHT, so this is a swipeRight in the injection scope's
                // terms (startX <= endX).
                swipeRight(
                    startX = 30.dp.toPx(),
                    endX = 138.dp.toPx(),
                )
            }
        composeRule.waitForIdle()
        assertEquals("a full-track drag commits exactly once", 1, commits)
        assertEquals("the commit lands on the release slot", 2, committedIndex)
    }

    @Test
    fun dragEndingWhereItStartedCommitsNothing() {
        var commits = 0
        setContent(initialIndex = 1) { _ -> commits++ }
        composeRule
            .onNodeWithContentDescription("Theme")
            .performTouchInput {
                // A real short drag INSIDE slot 1 (90dp -> 78dp, both
                // within the slot's 57..111dp span): the thumb follows
                // the finger but the index never changes, so the release
                // must not commit (spec: only if the slot changed).
                swipeLeft(
                    startX = 90.dp.toPx(),
                    endX = 78.dp.toPx(),
                )
            }
        composeRule.waitForIdle()
        assertEquals("a drag that ends on its own slot commits nothing", 0, commits)
    }

    @Test
    fun sliderExposesAccessibilityRecipe() {
        setContent(initialIndex = 0) {}
        // The semantics recipe (contentDescription + progressBarRangeInfo)
        // is what TalkBack and the step-7 semantics dump read.
        composeRule.onNodeWithContentDescription("Theme").assertIsEnabled()
    }
}
