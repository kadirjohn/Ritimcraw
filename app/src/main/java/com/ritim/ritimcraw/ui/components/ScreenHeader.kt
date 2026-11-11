package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Compact main-screen header (owner request 2026-11-11d §4.1): the title
 * sits in the space the app shell's insets already provide — screens are
 * composed inside AppNav's Scaffold padding, so the header adds only its
 * own compact vertical rhythm (top 12, bottom 8), never a second inset.
 * All four browse screens (Library, Projects, Exports, Settings) share
 * this treatment so their titles align and scale identically.
 *
 * [actions] rides the end (e.g. Exports' Clear) — a conventional toolbar
 * location, not a list footer.
 */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actions != null) {
            actions()
        }
    }
}
