package com.ritim.ritimcraw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ritim.ritimcraw.data.ThemeMode
import com.ritim.ritimcraw.data.ThemeStore
import com.ritim.ritimcraw.ui.AppNav
import com.ritim.ritimcraw.ui.theme.RitimTheme
import java.io.File

/**
 * The theme construction site: [RitimTheme] wraps the whole app here, so
 * the light/dark choice must live HERE (a Settings change has to
 * propagate UP through AppNav, not stay inside the Settings screen).
 *
 * Owner request 2026-11-14 ("sistem teması neyse ona göre uyumlu olsun"):
 * the app already followed the system — RitimTheme's darkTheme default
 * is [isSystemInDarkTheme], and the manifest does not opt into uiMode
 * config changes, so the activity recreates on a system theme flip and
 * the flag re-reads. The default behavior is unchanged. What this adds
 * is the MANUAL OVERRIDE: the persisted [ThemeMode] from the shared
 * files/project.json (the ThemeStore, the RenderBackendStore pattern),
 * resolved through [ThemeMode.isDark] — SYSTEM still forwards the
 * system flag, LIGHT/DARK pin it.
 *
 * This activity holds its OWN ThemeStore instance over the same file
 * AppNav's stores use — safe by the store's companion lock (one lock
 * for every instance, the RenderBackendStore contract) — because the
 * store must be readable BEFORE AppNav (and its Stores block) composes:
 * the theme wraps it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // Read once per composition; the Settings row's callback
            // updates this state, recomposes RitimTheme, and the whole
            // tree re-tints immediately (no restart needed).
            val themeStore = remember { ThemeStore(File(filesDir, "project.json")) }
            var themeMode by remember { mutableStateOf(themeStore.get()) }
            RitimTheme(darkTheme = themeMode.isDark(isSystemInDarkTheme())) {
                AppNav(
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        themeStore.put(mode)
                        themeMode = mode
                    },
                )
            }
        }
    }
}
