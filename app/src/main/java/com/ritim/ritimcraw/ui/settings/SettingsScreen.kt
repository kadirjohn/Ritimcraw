package com.ritim.ritimcraw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R

/**
 * Settings screen: device report placeholder (DESIGN.md Settings). The codec
 * probe output lands with the device-gated pass.
 */
@Composable
fun SettingsScreen(
    versionName: String = "1.0",
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.settings_device_report)) },
            supportingContent = {
                Text(stringResource(R.string.settings_device_report_body))
            },
        )
        HorizontalDivider()
        Text(
            stringResource(R.string.settings_version, versionName),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}
