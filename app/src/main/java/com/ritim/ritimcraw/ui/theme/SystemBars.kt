package com.ritim.ritimcraw.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Status/navigation bar icon colors (owner report 2026-11-23: light app
 * theme but white status bar icons).
 *
 * Why it was wrong: MainActivity's `enableEdgeToEdge()` default is
 * `SystemBarStyle.auto`, which decides light/dark icons from the SYSTEM's
 * night-mode flag. The app has its own Light/System/Dark choice
 * (ThemeStore), so a phone in dark mode with the app set to Light kept
 * white icons on a light surface — and an in-app theme change never
 * re-ran the detection at all. The fix drives the window's
 * `isAppearanceLightStatusBars` / `isAppearanceLightNavigationBars`
 * (WindowInsetsControllerCompat — "light" means DARK icons, for a light
 * background) from the app's resolved theme.
 *
 * The editor and the timeline chrome are deliberately dark in BOTH modes
 * (RitimTheme(editor = true), NeutralEditorScheme), so on those routes the
 * icons stay light whatever the browse theme is.
 */
object SystemBars {
    /** True when the bars should draw LIGHT (white) icons — a dark backdrop. */
    fun lightIcons(darkTheme: Boolean, darkChrome: Boolean): Boolean =
        darkTheme || darkChrome
}

/**
 * Applies [SystemBars.lightIcons] to the host window, after every
 * composition where it changed. Runs after `enableEdgeToEdge()` (which sets
 * the same flags once in onCreate), so this later write wins.
 */
@Composable
fun SystemBarIconsEffect(lightIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !lightIcons
        controller.isAppearanceLightNavigationBars = !lightIcons
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
