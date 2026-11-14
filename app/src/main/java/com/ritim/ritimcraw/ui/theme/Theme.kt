package com.ritim.ritimcraw.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The ritiMCRAW theme. Two color worlds (DESIGN.md):
 *
 *  - Browse screens (editor = false): dynamic color from the user's wallpaper
 *    on API 31+, falling back to the baseline tokens below that. minSdk is 34,
 *    so the dynamic path always runs; the fallback is kept for completeness.
 *    The dark/light axis follows [darkTheme]: the system's dark mode by
 *    default (owner request 2026-11-14: the app follows the system theme),
 *    or the persisted manual override when the construction site passes
 *    [ThemeMode.isDark] of the chosen mode (MainActivity wires the
 *    ThemeStore-backed Settings picker).
 *
 *  - Editors (editor = true): the fixed neutral gray [NeutralEditorScheme], so
 *    the chrome never biases color judgment. Editor chrome is never tinted
 *    with dynamic color AND never follows the light/dark axis — the editor
 *    world is deliberately dark in both modes (legible, chroma-free,
 *    ThemeTest-locked), whatever the browse theme is.
 */
@Composable
fun RitimTheme(
    editor: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = when {
        editor -> NeutralEditorScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkColorScheme
        else -> FallbackLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = RitimTypography,
        content = content,
    )
}

private val FallbackDarkColorScheme = darkColorScheme(
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

private val FallbackLightColorScheme = lightColorScheme(
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
