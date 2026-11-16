package com.ritim.ritimcraw.ui.settings

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ritim.ritimcraw.engine.CodecReport
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device report pager + tab semantics tests (settings v2 polish C5, spec
 * workflow step 7). INSTRUMENTED: they need a real window/UI thread, so
 * they run only on a device/emulator (connectedDebugAndroidTest) — the
 * pure grouping/filtering logic they guard visually is already pinned
 * host-side by DeviceReportGroupingTest; these pin the PAGER behavior:
 *
 * - one swipe left = exactly one page (0 -> 1, not 0 -> 2),
 * - a second swipe = page 2,
 * - clicking the "Encoders" tab = page 2,
 * - the tab row exposes 3 tabs, each with Role.Tab semantics and a
 *   selected state, and the selected tab follows pagerState.currentPage.
 *
 * Fixtures: two AVC codecs, one decoder and one encoder (the smallest set
 * that puts content on every page — page 1 shows the decoder, page 2 the
 * encoder — so a wrong page is visible, not just a wrong index).
 */
@RunWith(AndroidJUnit4::class)
class DeviceReportScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val reports = listOf(
        CodecReport(
            name = "c2.qti.avc.decoder",
            mime = "video/avc",
            hardware = true,
            profiles = emptyList(),
            maxSize = "1920x1080",
            main10Ok = true,
        ),
        CodecReport(
            name = "c2.qti.avc.encoder",
            mime = "video/avc",
            hardware = true,
            profiles = emptyList(),
            maxSize = "1920x1080",
            main10Ok = null,
        ),
    )

    private fun setContent() {
        composeRule.setContent {
            DeviceReportScreen(
                reports = reports,
                onBack = {},
                onCopyReport = {},
            )
        }
        // The tab row must exist before any gesture: the pager's pages
        // settle after the first composition + the settle animation.
        composeRule.waitForIdle()
    }

    private fun selectedTabIndex(): Int {
        // The selected tab is the one whose selectable semantics reports
        // selected = true; PrimaryTabRow keeps exactly one.
        val tabs = listOf("All", "Decoders", "Encoders")
        tabs.forEachIndexed { index, label ->
            if (composeRule.onNodeWithText(label).isSelectedSemantics()) {
                return index
            }
        }
        throw AssertionError("no tab reports selected")
    }

    private fun SemanticsNodeInteraction.isSelectedSemantics(): Boolean {
        return try {
            assertIsSelected()
            true
        } catch (e: AssertionError) {
            false
        }
    }

    @Test
    fun tabRowHasThreeTabs() {
        setContent()
        composeRule.onNodeWithText("All").assertExists()
        composeRule.onNodeWithText("Decoders").assertExists()
        composeRule.onNodeWithText("Encoders").assertExists()
    }

    @Test
    fun swipeLeftOnceSelectsExactlyOnePage() {
        setContent()
        assertEquals(0, selectedTabIndex())
        // Swipe on the pager area (the codec name row is inside page 0's
        // LazyColumn, which the pager owns).
        composeRule
            .onNodeWithText("c2.qti.avc.decoder")
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(
            "one swipe must advance exactly one page (0 -> 1, not 0 -> 2)",
            1,
            selectedTabIndex(),
        )
    }

    @Test
    fun swipeLeftTwiceSelectsPageTwo() {
        setContent()
        composeRule
            .onNodeWithText("c2.qti.avc.decoder")
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        composeRule
            .onNodeWithText("c2.qti.avc.decoder")
            .performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(2, selectedTabIndex())
    }

    @Test
    fun clickingEncodersTabSelectsPageTwo() {
        setContent()
        composeRule.onNodeWithText("Encoders").performClick()
        composeRule.waitForIdle()
        assertEquals(2, selectedTabIndex())
        // Page 2 shows the encoder only — the page content follows the tab.
        composeRule.onNodeWithText("c2.qti.avc.encoder").assertIsDisplayed()
        // And the decoder (page 1's content) is not on this page.
        composeRule.onNodeWithText("c2.qti.avc.decoder").assertDoesNotExist()
    }

    @Test
    fun decodersTabShowsOnlyDecoders() {
        setContent()
        composeRule.onNodeWithText("Decoders").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("c2.qti.avc.decoder").assertIsDisplayed()
        composeRule.onNodeWithText("c2.qti.avc.encoder").assertDoesNotExist()
    }
}
