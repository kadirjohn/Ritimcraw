package com.ritim.ritimcraw.ui.editor

import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.ritim.ritimcraw.R

/**
 * THE ZEBRA TOGGLE (owner spec PLAY-11): the clip warning's corner switch,
 * a peer of the scope toggle in the preview's scrimmed corner group (the
 * clip warning belongs beside the analysis tools). Same control language
 * as every preview corner control (DESIGN.md): a stock M3 IconButton (the
 * 48dp touch target) with the vendored diagonal-stripes glyph, wrapped in
 * the translucent surfaceContainerLowest scrim at the theme's small shape
 * so the glyph reads over any footage — including the BLOWN footage the
 * zebra exists to flag (owner finding 2026-11-11: white glyphs vanished
 * over near-white frames). The Surface wraps, not replaces, the
 * IconButton — the touch target and the click path are unchanged.
 *
 * [on] is the screen's saveable zebra flag (off by default); the
 * description names show/hide like the scope toggle's.
 */
@Composable
fun ZebraToggleButton(
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(
        if (on) R.string.editor_zebra_hide else R.string.editor_zebra_show
    )
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.6f),
        shape = MaterialTheme.shapes.small,
        modifier = modifier,
    ) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_zebra),
                contentDescription = null,
            )
        }
    }
}
