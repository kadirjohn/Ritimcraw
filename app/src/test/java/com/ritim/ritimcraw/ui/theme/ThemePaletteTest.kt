package com.ritim.ritimcraw.ui.theme

import com.ritim.ritimcraw.data.ThemeMode
import com.ritim.ritimcraw.data.ThemeStore
import java.io.File
import org.junit.Assert
import org.junit.Test

class ThemePaletteTest {
    private fun assertTrue(c: Boolean, m: String = "") = Assert.assertTrue(m, c)
    private fun assertFalse(c: Boolean, m: String = "") = Assert.assertFalse(m, c)
    private fun assertEquals(e: Any?, a: Any?, m: String = "") = Assert.assertEquals(m, e, a)
    private fun assertNull(a: Any?) = Assert.assertNull(a)

    private val all = ThemePalettes.all

    @Test
    fun idsAreUniqueAndDefaultExists() {
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(ThemePalettes.DEFAULT_ID, all.first().id)
        assertTrue(all.first().isDark)
    }

    @Test
    fun groupSizes() {
        assertTrue(ThemePalettes.dark.size in 10..12, "dark ${ThemePalettes.dark.size}")
        assertTrue(ThemePalettes.light.size in 3..4, "light ${ThemePalettes.light.size}")
        assertTrue(ThemePalettes.byId("mocha") != null && ThemePalettes.byId("latte") != null)
        assertTrue(ThemePalettes.byId("lightroom-dark") != null)
    }

    @Test
    fun everyRoleOpaqueAndNamed() {
        for (p in all) {
            assertTrue(p.name.isNotBlank(), p.id)
            assertEquals(27, p.allColors().size)
            p.allColors().forEach { assertEquals(0xFF, (it ushr 24) and 0xFF, p.id) }
        }
    }

    @Test
    fun darkAndLightLuminance() {
        for (p in all) {
            val l = ColorMath.luminance(p.background)
            if (p.isDark) assertTrue(l < 0.2, "${p.id} $l") else assertTrue(l > 0.6, "${p.id} $l")
        }
    }

    @Test
    fun contrastMinimumEveryTheme() {
        for (p in all) {
            assertTrue(ColorMath.contrast(p.onSurface, p.surface) >= 3.0, p.id)
            assertTrue(ColorMath.contrast(p.onBackground, p.background) >= 3.0, p.id)
            assertTrue(ColorMath.contrast(p.onPrimary, p.primary) >= 3.0, p.id)
            assertTrue(ColorMath.contrast(p.onSurfaceVariant, p.surfaceVariant) >= 3.0, p.id)
        }
    }

    @Test
    fun firstTwoThemesMeetAa() {
        for (id in listOf("camera-dark", "camera-light")) {
            val p = ThemePalettes.byId(id)!!
            assertTrue(ColorMath.contrast(p.onSurface, p.surface) >= 4.5, "$id onSurface")
            assertTrue(ColorMath.contrast(p.onSurface, p.background) >= 4.5, "$id onBackground")
            assertTrue(ColorMath.contrast(p.onSurfaceVariant, p.surfaceVariant) >= 4.5, "$id variant")
            assertTrue(ColorMath.contrast(p.onSurfaceVariant, p.surface) >= 4.5, "$id variant/surface")
            assertTrue(ColorMath.contrast(p.onPrimary, p.primary) >= 4.5, "$id onPrimary")
            assertTrue(ColorMath.contrast(p.onSurface, p.primaryContainer) >= 4.5, "$id container")
        }
    }

    @Test
    fun resolveFallsBackToDefault() {
        assertEquals(ThemePalettes.DEFAULT_ID, ThemePalettes.resolve(null).id)
        assertEquals(ThemePalettes.DEFAULT_ID, ThemePalettes.resolve("nope").id)
        assertEquals("nord-style", ThemePalettes.resolve("nord-style").id)
    }

    // ---- selection ----

    @Test
    fun defaultLastCustomIsFirstTheme() {
        assertEquals("camera-dark", ThemeSelection.lastCustomId(null))
        assertEquals("camera-dark", ThemeSelection.lastCustomId("removed-id"))
    }

    @Test
    fun tapOrDragOntoCActivatesLastCustom() {
        val none = ThemeSelection.choose(ThemeSelection.CUSTOM_SLOT, null)
        assertEquals(ThemeChoice(ThemeMode.CUSTOM, "camera-dark"), none)
        val picked = ThemeSelection.choose(0, "nord-style")
        assertEquals(ThemeChoice(ThemeMode.CUSTOM, "nord-style"), picked)
    }

    @Test
    fun otherSlotsKeepTheirModes() {
        assertEquals(ThemeMode.LIGHT, ThemeSelection.choose(1, "mocha").mode)
        assertEquals(ThemeMode.SYSTEM, ThemeSelection.choose(2, "mocha").mode)
        assertEquals(ThemeMode.DARK, ThemeSelection.choose(3, "mocha").mode)
        assertEquals(ThemeMode.CUSTOM, ThemeSelection.choose(-5, null).mode)
        assertEquals(ThemeMode.DARK, ThemeSelection.choose(99, null).mode)
        ThemeSelection.slots.forEachIndexed { i, m -> assertEquals(i, ThemeSelection.slotOf(m)) }
    }

    @Test
    fun pickCustomAndDarkResolution() {
        assertEquals(ThemeChoice(ThemeMode.CUSTOM, "latte"), ThemeSelection.pickCustom("latte"))
        assertFalse(ThemeSelection.isDark(ThemeMode.CUSTOM, "latte", systemInDarkTheme = true))
        assertTrue(ThemeSelection.isDark(ThemeMode.CUSTOM, null, systemInDarkTheme = false))
        assertTrue(ThemeSelection.isDark(ThemeMode.SYSTEM, "latte", systemInDarkTheme = true))
        assertFalse(ThemeSelection.isDark(ThemeMode.LIGHT, "mocha", systemInDarkTheme = true))
        assertNull(ThemeSelection.activePalette(ThemeMode.DARK, "mocha"))
    }

    // ---- persistence ----

    @Test
    fun persistenceRoundTrip() {
        val f = File.createTempFile("project", ".json")
        try {
            f.delete()
            val store = ThemeStore(f)
            assertEquals(ThemeMode.SYSTEM, store.get())
            assertNull(store.getCustomThemeId())
            store.putCustomThemeId("gruvbox-dark")
            store.put(ThemeMode.CUSTOM)
            assertEquals(ThemeMode.CUSTOM, ThemeStore(f).get())
            assertEquals("gruvbox-dark", ThemeStore(f).getCustomThemeId())
            // Changing the mode keeps the remembered custom id.
            store.put(ThemeMode.DARK)
            assertEquals("gruvbox-dark", ThemeStore(f).getCustomThemeId())
            store.put(ThemeMode.SYSTEM)
            assertEquals(ThemeMode.SYSTEM, ThemeStore(f).get())
            assertEquals("gruvbox-dark", ThemeStore(f).getCustomThemeId())
            store.putCustomThemeId(null)
            assertNull(ThemeStore(f).getCustomThemeId())
        } finally {
            f.delete()
        }
    }

    @Test
    fun defaultFileStaysUntouched() {
        assertEquals("{\"version\":1}", ThemeStore.mergeInto(null, ThemeMode.SYSTEM))
        assertEquals("{\"version\":1}", ThemeStore.mergeCustomInto(null, null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromWire(null))
        assertEquals(ThemeMode.CUSTOM, ThemeMode.fromWire("CUSTOM"))
    }
}
