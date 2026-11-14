package com.ritim.ritimcraw.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme

/**
 * Public preview baseline schemes (owner spec 2026-11-14, workflow step
 * 2: "@Preview functions in light and dark with dynamic color disabled
 * (fixed M3 baseline scheme)").
 *
 * RitimTheme's fallback schemes are private (Theme.kt) and its dynamic
 * path reads the wallpaper at composition time, which a preview cannot
 * pin. These are the same M3 baseline token sets (Color.kt's fallback
 * values — the stock Material baseline hues, NOT a new palette)
 * exposed publicly so previews compose MaterialTheme directly with a
 * fixed scheme and catch compile errors deterministically.
 *
 * The roles the Settings redesign needs beyond Theme.kt's fallback
 * sets (surfaceContainerLow/Highest, surfaceBright) are supplied from
 * the M3 baseline defaults for those roles — lightColorScheme()/
 * darkColorScheme() fill every unspecified role with the baseline
 * value, so the previews show the true role relationships the dynamic
 * scheme will produce.
 */
val PreviewLightColorScheme: ColorScheme = lightColorScheme(
    primary = FallbackLightPrimary,
    onPrimary = FallbackLightOnPrimary,
    primaryContainer = FallbackLightPrimaryContainer,
    onPrimaryContainer = FallbackLightOnPrimaryContainer,
    secondaryContainer = FallbackLightSecondaryContainer,
    onSecondaryContainer = FallbackLightOnSecondaryContainer,
    surface = FallbackLightSurface,
    surfaceContainer = FallbackLightSurfaceContainer,
    onSurface = FallbackLightOnSurface,
    onSurfaceVariant = FallbackLightOnSurfaceVariant,
    outline = FallbackLightOutline,
    outlineVariant = FallbackLightOutlineVariant,
    error = FallbackLightError,
    errorContainer = FallbackLightErrorContainer,
    onErrorContainer = FallbackLightOnErrorContainer,
)

val PreviewDarkColorScheme: ColorScheme = darkColorScheme(
    primary = FallbackPrimary,
    onPrimary = FallbackOnPrimary,
    primaryContainer = FallbackPrimaryContainer,
    onPrimaryContainer = FallbackOnPrimaryContainer,
    secondaryContainer = FallbackSecondaryContainer,
    onSecondaryContainer = FallbackOnSecondaryContainer,
    surface = FallbackSurface,
    surfaceContainer = FallbackSurfaceContainer,
    onSurface = FallbackOnSurface,
    onSurfaceVariant = FallbackOnSurfaceVariant,
    outline = FallbackOutline,
    outlineVariant = FallbackOutlineVariant,
    error = FallbackError,
    errorContainer = FallbackErrorContainer,
    onErrorContainer = FallbackOnErrorContainer,
)
