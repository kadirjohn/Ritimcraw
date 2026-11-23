package com.ritim.ritimcraw.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemBarsTest {
    @Test
    fun light_theme_on_browse_screens_gets_dark_icons() {
        assertFalse(SystemBars.lightIcons(darkTheme = false, darkChrome = false))
    }

    @Test
    fun dark_theme_gets_light_icons() {
        assertTrue(SystemBars.lightIcons(darkTheme = true, darkChrome = false))
    }

    @Test
    fun editor_chrome_is_dark_in_both_modes() {
        assertTrue(SystemBars.lightIcons(darkTheme = false, darkChrome = true))
        assertTrue(SystemBars.lightIcons(darkTheme = true, darkChrome = true))
    }
}
