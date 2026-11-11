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
 * CALL-SITE CONTRACT (title consistency, owner finding 2026-11-12): this
 * component owns the title's ENTIRE margin — the caller must compose it
 * directly under a root Column that pads NOTHING (no horizontal padding,
 * no nested Scaffold, no inset), i.e. the canonical chain is exactly
 * AppNav Scaffold -> Box(padding) -> Crossfade -> Column (unpadded) ->
 * ScreenHeader. A caller that pads its root Column horizontally stacks
 * its gutter with this Row's own start = 16dp and shifts the title right
 * of the other screens — the exact bug this contract exists to prevent.
 * The 16dp screen gutter (DESIGN.md Layout) belongs to the BODY below
 * the header, never to the header's parent.
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
