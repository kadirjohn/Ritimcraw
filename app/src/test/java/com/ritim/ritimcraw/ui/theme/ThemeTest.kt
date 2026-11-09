package com.ritim.ritimcraw.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
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

    private fun checkNeutral(name: String, color: Color, t: Float, failures: MutableList<String>) {
        val rg = kotlin.math.abs(color.red - color.green)
        val gb = kotlin.math.abs(color.green - color.blue)
        if (rg > t || gb > t) failures.add("$name (r-g=$rg, g-b=$gb)")
    }
}
