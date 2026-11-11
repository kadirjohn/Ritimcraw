package com.ritim.ritimcraw.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.ui.components.ScreenHeader

/**
 * Projects screen: designed empty state (DESIGN.md Empty states). Projects
 * group graded clips and their exports; creation lands with timelines.
 */
@Composable
fun ProjectsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        // Shared header (owner request 2026-11-11d: consistent top area
        // across the main screens) — same title, same compact rhythm as
        // Library/Exports; no actions on this screen yet.
        ScreenHeader(title = stringResource(R.string.projects_title))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                stringResource(R.string.projects_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.projects_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
