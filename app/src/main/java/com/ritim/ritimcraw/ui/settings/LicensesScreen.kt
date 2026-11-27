package com.ritim.ritimcraw.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.ui.components.ArrowBack

/** One third-party component shown on the Licenses page. */
data class LicenseEntry(val name: String, val license: String, val note: String)

/**
 * Third-party components (mirrors the repository NOTICE file). Static text,
 * no dependency: names and license identifiers are not localized.
 */
val LicenseEntries: List<LicenseEntry> = listOf(
    LicenseEntry("motioncam-decoder", "Apache-2.0", "github.com/mirsadm/motioncam-decoder"),
    LicenseEntry("doctest 2.4.12", "MIT", "Copyright (c) 2016-2023 Viktor Kirilov"),
    LicenseEntry("vqf", "MIT", "Copyright (c) 2021 Daniel Laidig"),
    LicenseEntry("nlohmann/json 3.11.3", "MIT", "Copyright (c) 2013-2022 Niels Lohmann"),
    LicenseEntry("tinydng", "MIT", "github.com/syoyo/tinydng"),
    LicenseEntry("AudioFile", "MIT", "Copyright (c) 2017 Adam Stark"),
    LicenseEntry("simde", "MIT", "github.com/simd-everywhere/simde"),
    LicenseEntry("Inter", "SIL OFL 1.1", "Copyright The Inter Project Authors"),
    LicenseEntry("Material Symbols", "Apache-2.0", "Google"),
    LicenseEntry("OpenDRT 1.1.0", "GPL-3.0-or-later", "Jed Smith (port)"),
    LicenseEntry("FilmCraft audio-dsp", "MIT OR Apache-2.0", "C++ port"),
    LicenseEntry("Xiaomi Mi-Log materials", "Xiaomi developer pack terms", ""),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = ArrowBack,
                    contentDescription = stringResource(R.string.settings_back),
                    modifier = Modifier.size(24.dp),
                )
            }
            Text(
                text = stringResource(R.string.settings_licenses),
                style = MaterialTheme.typography.headlineSmall,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.settings_licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(LicenseEntries) { e ->
                Column {
                    Text(e.name, style = MaterialTheme.typography.titleMedium)
                    Text(e.license, style = MaterialTheme.typography.bodyMedium)
                    if (e.note.isNotEmpty()) {
                        Text(e.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
