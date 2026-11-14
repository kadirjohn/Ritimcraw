package com.ritim.ritimcraw.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor scheme must be chroma-free (sprint plan Task 4):
 * every role except error* satisfies |r-g| <= 2/255 and |g-b| <= 2/255,
 * so editor chrome never biases color judgment.
 */
class ThemeTest {

    @Test
    fun neutralSchemeHasNoChroma() {
        val scheme = NeutralEditorScheme
        val tolerance = 2f / 255f
        val failures = mutableListOf<String>()

        listOf(
            "primary" to scheme.primary,
            "onPrimary" to scheme.onPrimary,
            "primaryContainer" to scheme.primaryContainer,
            "onPrimaryContainer" to scheme.onPrimaryContainer,
            "inversePrimary" to scheme.inversePrimary,
            "secondary" to scheme.secondary,
            "onSecondary" to scheme.onSecondary,
            "secondaryContainer" to scheme.secondaryContainer,
            "onSecondaryContainer" to scheme.onSecondaryContainer,
            "tertiary" to scheme.tertiary,
            "onTertiary" to scheme.onTertiary,
            "tertiaryContainer" to scheme.tertiaryContainer,
            "onTertiaryContainer" to scheme.onTertiaryContainer,
            "background" to scheme.background,
            "onBackground" to scheme.onBackground,
            "surface" to scheme.surface,
            "onSurface" to scheme.onSurface,
            "surfaceVariant" to scheme.surfaceVariant,
            "onSurfaceVariant" to scheme.onSurfaceVariant,
            "surfaceDim" to scheme.surfaceDim,
            "surfaceBright" to scheme.surfaceBright,
            "surfaceContainerLowest" to scheme.surfaceContainerLowest,
            "surfaceContainerLow" to scheme.surfaceContainerLow,
            "surfaceContainer" to scheme.surfaceContainer,
            "surfaceContainerHigh" to scheme.surfaceContainerHigh,
            "surfaceContainerHighest" to scheme.surfaceContainerHighest,
            "inverseSurface" to scheme.inverseSurface,
            "inverseOnSurface" to scheme.inverseOnSurface,
            "outline" to scheme.outline,
            "outlineVariant" to scheme.outlineVariant,
            "scrim" to scheme.scrim,
        ).forEach { (name, color) ->
            checkNeutral(name, color, tolerance, failures)
        }

        assertTrue(
            "roles with chroma: $failures",
            failures.isEmpty(),
        )
    }

    @Test
    fun fallbackSchemesAreNotTheNeutralEditorScheme() {
        // Fallback tokens are the chromatic M3 baseline for browse screens
        // (only reachable below API 31 — minSdk 34 makes them dead code) and
        // must stay distinct from the neutral editor scheme so the two color
        // worlds never collapse into one.
        assertTrue(FallbackPrimary != NeutralEditorScheme.primary)
        assertTrue(FallbackSurface != NeutralEditorScheme.surface)
    }

    @Test
    fun fallbackLightTokensAreGenuinelyLight() {
        // The light fallback (owner request 2026-11-14: the theme override
        // made the light axis a first-class concern): a LIGHT theme must
        // read as light — light surfaces carry DARK on-colors and a
        // high-luminance surface, the opposite polarity of the dark set.
        // This locks the pre-existing bug the override surfaced: the light
        // scheme used to be built from the DARK tokens (darkColorScheme
        // over the dark surface values), so a "light" theme rendered dark.
        assertTrue(FallbackLightSurface.luminance() > 0.9f)
        assertTrue(FallbackLightOnSurface.luminance() < 0.1f)
        assertTrue(FallbackLightOnPrimary.luminance() > 0.9f)
        assertTrue(FallbackLightPrimary.luminance() < 0.5f)
        // And the two fallbacks never collapse into one: dark stays dark
        // (dark surface, clearly-light on-color — the dark set's
        // on-surface is light gray, ~0.76 WCAG luminance, not near-white).
        assertTrue(FallbackSurface.luminance() < 0.1f)
        assertTrue(FallbackOnSurface.luminance() > 0.5f)
    }

    private fun checkNeutral(name: String, color: Color, t: Float, failures: MutableList<String>) {
        val rg = kotlin.math.abs(color.red - color.green)
        val gb = kotlin.math.abs(color.green - color.blue)
        if (rg > t || gb > t) failures.add("$name (r-g=$rg, g-b=$gb)")
    }
}
