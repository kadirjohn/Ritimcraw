package com.ritim.ritimcraw.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
 * Projects screen: designed empty state (DESIGN.md Empty states) plus the
 * timeline entry. Projects group graded clips and their exports; v1's one
 * timeline is the shared project.json timeline section, opened here —
 * project creation (multiple named timelines) lands with the projects
 * model, so the empty state keeps its copy and gains the entry button.
 */
@Composable
fun ProjectsScreen(
    onOpenTimeline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Canonical header call site (title consistency, owner finding
    // 2026-11-12 — the contract lives in ScreenHeader's doc): the root
    // Column pads NOTHING, so the title's left edge is ScreenHeader's
    // own 16dp in all four browse screens. The 16dp screen gutter moved
    // into the body container below — the centered empty state keeps
    // its exact geometry.
    Column(modifier.fillMaxSize()) {
        // Shared header (owner request 2026-11-11d: consistent top area
        // across the main screens) — same title, same compact rhythm as
        // Library/Exports; no actions on this screen yet.
        ScreenHeader(title = stringResource(R.string.projects_title))
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp),
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
            // The timeline entry (spec TL-01): the screen's one primary
            // action — a filled button (DESIGN.md Buttons: filled for the
            // one primary action), 48dp tall per the touch-target rule,
            // opening the shared timeline.
            Button(
                onClick = onOpenTimeline,
                modifier = Modifier
                    .padding(top = 24.dp)
                    .heightIn(min = 48.dp),
            ) {
                Text(stringResource(R.string.projects_open_timeline))
            }
        }
    }
}
