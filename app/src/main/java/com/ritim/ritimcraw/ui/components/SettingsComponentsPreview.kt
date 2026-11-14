package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.ritim.ritimcraw.ui.theme.PreviewDarkColorScheme
import com.ritim.ritimcraw.ui.theme.PreviewLightColorScheme
import com.ritim.ritimcraw.ui.theme.RitimTypography

/**
 * Light + dark previews for the Settings redesign's shared components
 * (owner spec 2026-11-14, workflow step 2): dynamic color disabled —
 * a fixed M3 baseline scheme (PreviewSchemes) so the previews are
 * deterministic and catch compile errors; the device's dynamic scheme
 * supplies the real values at runtime.
 *
 * The two variants are separate @Preview functions (not one function
 * with two annotations): a preview body cannot read which variant it
 * is rendering, so each wrapper pins its scheme explicitly.
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
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                SettingsSectionLabel(text = "Playback", first = true)
                SettingsGroupCard {
                    SettingsGroupCardRow(divider = false) {
                        SettingRowExpandable(
                            icon = Eye,
                            title = "Preview quality",
                            hint = "720 × 480 px",
                            infoContentDescription = "About preview quality",
                            infoNote = "Resolution for playback and scrubbing. Lower is smoother. Paused frames always sharpen to the clip's own resolution.",
                            expandedText = "Expanded",
                            collapsedText = "Collapsed",
                        ) {
                            AppSegmentedControl(
                                options = listOf("Low", "Balanced", "High"),
                                selectedIndex = 1,
                                onSelect = {},
                            )
                        }
                    }
                    SettingsGroupCardRow(divider = true) {
                        SettingRowExpandable(
                            icon = Cpu,
                            title = "Image processing",
                            hint = "Reference path",
                            infoContentDescription = "About image processing",
                            infoNote = "Where the develop pipeline runs. CPU is the reference path. Auto uses the GPU once this device verifies it. GPU is experimental and falls back to CPU. The video encoder is chosen separately.",
                            expandedText = "Expanded",
                            collapsedText = "Collapsed",
                        ) {
                            AppSegmentedControl(
                                options = listOf("CPU", "Auto", "GPU"),
                                selectedIndex = 0,
                                onSelect = {},
                            )
                        }
                    }
                }
                SettingsSectionLabel(text = "Storage & device")
                SettingsGroupCard {
                    SettingsGroupCardRow(divider = false) {
                        SettingRowNavigation(
                            icon = Folder,
                            title = "Gyro log folder",
                            hint = "Documents/MotionCam",
                            onClick = {},
                        )
                    }
                    SettingsGroupCardRow(divider = true) {
                        SettingRowNavigation(
                            icon = Report,
                            title = "Device report",
                            hint = "22 codecs",
                            onClick = {},
                        )
                    }
                }
                SettingsSectionLabel(text = "Advanced")
                SettingsGroupCard {
                    SettingsGroupCardRow(divider = false) {
                        SettingRowSwitch(
                            icon = Activity,
                            title = "Preview stats readback",
                            hint = "Debug · off, no per-frame cost",
                            checked = false,
                            onCheckedChange = {},
                        )
                    }
                }
            }
        }
    }
}
