package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ritim.ritimcraw.ui.theme.PreviewDarkColorScheme
import com.ritim.ritimcraw.ui.theme.PreviewLightColorScheme
import com.ritim.ritimcraw.ui.theme.RitimTypography

/**
 * Light + dark previews for the Settings redesign's shared components
 * (owner spec 2026-11-14, workflow step 2; v2 polish 2026-11-15 C1):
 * dynamic color disabled — a fixed M3 baseline scheme (PreviewSchemes)
 * so the previews are deterministic and catch compile errors; the
 * device's dynamic scheme supplies the real values at runtime.
 *
 * The two variants are separate @Preview functions (not one function
 * with two annotations): a preview body cannot read which variant it
 * is rendering, so each wrapper pins its scheme explicitly.
 *
 * The body paints the surface background itself (the settings screen
 * does the same) so the previews exercise the real stacking: tonal
 * surfaceContainer items on the surface role.
 */
@Preview(name = "Light", showBackground = true)
@Composable
private fun SettingsComponentsLightPreview() {
    ComponentPreviewBody(dark = false)
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun SettingsComponentsDarkPreview() {
    ComponentPreviewBody(dark = true)
}

@Composable
private fun ComponentPreviewBody(dark: Boolean) {
    MaterialTheme(
        colorScheme = if (dark) PreviewDarkColorScheme else PreviewLightColorScheme,
        typography = RitimTypography,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            SettingsSectionLabel(text = "Playback", first = true)
            SettingsGroup {
                SettingRowExpandable(
                    icon = Eye,
                    title = "Preview quality",
                    hint = "720 × 480 px",
                    infoContentDescription = "About preview quality",
                    infoNote = "Resolution for playback and scrubbing. Lower is smoother. Paused frames always sharpen to the clip's own resolution.",
                    expandedText = "Expanded",
                    collapsedText = "Collapsed",
                    index = 0,
                    count = 2,
                ) {
                    AppSegmentedControl(
                        options = listOf("Low", "Balanced", "High"),
                        selectedIndex = 1,
                        onSelect = {},
                    )
                }
                SettingRowExpandable(
                    icon = Cpu,
                    title = "Image processing",
                    hint = "Reference path",
                    infoContentDescription = "About image processing",
                    infoNote = "Where the develop pipeline runs. CPU is the reference path. Auto uses the GPU once this device verifies it. GPU is experimental and falls back to CPU. The video encoder is chosen separately.",
                    expandedText = "Expanded",
                    collapsedText = "Collapsed",
                    index = 1,
                    count = 2,
                ) {
                    AppSegmentedControl(
                        options = listOf("CPU", "Auto", "GPU"),
                        selectedIndex = 0,
                        onSelect = {},
                    )
                }
            }
            SettingsSectionLabel(text = "Storage & device")
            SettingsGroup {
                SettingRowNavigation(
                    icon = Folder,
                    title = "Gyro log folder",
                    hint = "Documents/MotionCam",
                    onClick = {},
                    index = 0,
                    count = 2,
                )
                SettingRowNavigation(
                    icon = Report,
                    title = "Device report",
                    hint = "22 codecs",
                    onClick = {},
                    index = 1,
                    count = 2,
                )
            }
            SettingsSectionLabel(text = "Advanced")
            SettingsGroup {
                SettingRowSwitch(
                    icon = Activity,
                    title = "Preview stats readback",
                    hint = "Debug · off, no per-frame cost",
                    checked = false,
                    onCheckedChange = {},
                    index = 0,
                    count = 1,
                )
            }
        }
    }
}
