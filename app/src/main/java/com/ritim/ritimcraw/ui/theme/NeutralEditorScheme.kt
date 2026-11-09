package com.ritim.ritimcraw.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Editor scheme: the editor-* tokens from STEPS/DESIGN.md, copied exactly.
// Generated from a chroma-0 gray seed, dark. The editor chrome must never
// bias color judgment, so every role except error* is neutral gray.
//
// DESIGN.md rule: "Neutral around the image. No hue in any editor chrome;
// state color (error) only where it carries meaning."
// ThemeTest.neutralSchemeHasNoChroma enforces |r-g| <= 2/255 and
// |g-b| <= 2/255 for every non-error role.

private val EditorPrimary = Color(0xFFc6c6c6)
private val EditorOnPrimary = Color(0xFF303030)
private val EditorPrimaryContainer = Color(0xFF474747)
private val EditorOnPrimaryContainer = Color(0xFFe2e2e2)
private val EditorSecondary = Color(0xFFc6c6c6)
private val EditorOnSecondary = Color(0xFF303030)
private val EditorSecondaryContainer = Color(0xFF474747)
private val EditorOnSecondaryContainer = Color(0xFFe2e2e2)
private val EditorTertiary = Color(0xFFc6c6c6)
private val EditorOnTertiary = Color(0xFF303030)
private val EditorTertiaryContainer = Color(0xFF474747)
private val EditorOnTertiaryContainer = Color(0xFFe2e2e2)
private val EditorBackground = Color(0xFF1b1b1b)
private val EditorOnBackground = Color(0xFFe2e2e2)
private val EditorSurface = Color(0xFF1b1b1b)
private val EditorOnSurface = Color(0xFFe2e2e2)
private val EditorSurfaceVariant = Color(0xFF262626)
private val EditorOnSurfaceVariant = Color(0xFFc6c6c6)
private val EditorOutline = Color(0xFF919191)
private val EditorOutlineVariant = Color(0xFF474747)
private val EditorSurfaceContainerLowest = Color(0xFF0e0e0e)
private val EditorSurfaceContainerLow = Color(0xFF1b1b1b)
private val EditorSurfaceContainer = Color(0xFF262626)
private val EditorSurfaceContainerHigh = Color(0xFF303030)
private val EditorSurfaceContainerHighest = Color(0xFF3b3b3b)
private val EditorInverseSurface = Color(0xFFe2e2e2)
private val EditorInverseOnSurface = Color(0xFF1b1b1b)
private val EditorInversePrimary = Color(0xFFc6c6c6)
private val EditorSurfaceTint = EditorPrimary
private val EditorSurfaceBright = Color(0xFF1b1b1b)
private val EditorSurfaceDim = Color(0xFF0e0e0e)
private val EditorScrim = Color(0xFF000000)

val NeutralEditorScheme: ColorScheme = darkColorScheme(
    primary = EditorPrimary,
    onPrimary = EditorOnPrimary,
    primaryContainer = EditorPrimaryContainer,
    onPrimaryContainer = EditorOnPrimaryContainer,
    inversePrimary = EditorInversePrimary,
    secondary = EditorSecondary,
    onSecondary = EditorOnSecondary,
    secondaryContainer = EditorSecondaryContainer,
    onSecondaryContainer = EditorOnSecondaryContainer,
    tertiary = EditorTertiary,
    onTertiary = EditorOnTertiary,
    tertiaryContainer = EditorTertiaryContainer,
    onTertiaryContainer = EditorOnTertiaryContainer,
    background = EditorBackground,
    onBackground = EditorOnBackground,
    surface = EditorSurface,
    onSurface = EditorOnSurface,
    surfaceVariant = EditorSurfaceVariant,
    onSurfaceVariant = EditorOnSurfaceVariant,
    surfaceTint = EditorSurfaceTint,
    inverseSurface = EditorInverseSurface,
    inverseOnSurface = EditorInverseOnSurface,
    outline = EditorOutline,
    outlineVariant = EditorOutlineVariant,
    surfaceContainerLowest = EditorSurfaceContainerLowest,
    surfaceContainerLow = EditorSurfaceContainerLow,
    surfaceContainer = EditorSurfaceContainer,
    surfaceContainerHigh = EditorSurfaceContainerHigh,
    surfaceContainerHighest = EditorSurfaceContainerHighest,
    surfaceBright = EditorSurfaceBright,
    surfaceDim = EditorSurfaceDim,
    scrim = EditorScrim,
)
