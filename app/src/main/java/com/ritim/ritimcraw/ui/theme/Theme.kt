package com.ritim.ritimcraw.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The ritiMCRAW theme. Two color worlds (DESIGN.md):
 *
 *  - Browse screens (editor = false): dynamic color from the user's wallpaper
 *    on API 31+, falling back to the baseline tokens below that. minSdk is 34,
 *    so the dynamic path always runs; the fallback is kept for completeness.
 *
 *  - Editors (editor = true): the fixed neutral gray [NeutralEditorScheme], so
 *    the chrome never biases color judgment. Editor chrome is never tinted
 *    with dynamic color.
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

private val FallbackLightColorScheme = darkColorScheme(
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
