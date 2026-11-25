package com.ritim.ritimcraw.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.ui.components.Check
import com.ritim.ritimcraw.ui.theme.ThemePalette
import com.ritim.ritimcraw.ui.theme.ThemePalettes

/**
 * The custom-theme picker, opened from the Settings "Theme" row. Palettes
 * are grouped Dark / Light, each with a primary/background/surface swatch;
 * the current custom theme carries a check. Picking one stores it as the
 * last custom theme and activates custom mode (the caller's job).
 */
@Composable
fun CustomThemePickerDialog(
    currentId: String,
    customActive: Boolean,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme_picker_title)) },
        text = {
            LazyColumn {
                item { GroupLabel(stringResource(R.string.settings_theme_group_dark)) }
                items(ThemePalettes.dark, key = { it.id }) {
                    PaletteRow(it, selected = customActive && it.id == currentId, onPick)
                }
                item { GroupLabel(stringResource(R.string.settings_theme_group_light)) }
                items(ThemePalettes.light, key = { it.id }) {
                    PaletteRow(it, selected = customActive && it.id == currentId, onPick)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_theme_picker_close))
            }
        },
    )
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PaletteRow(
    palette: ThemePalette,
    selected: Boolean,
    onPick: (String) -> Unit,
) {
    val selectedLabel = stringResource(R.string.settings_theme_selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.RadioButton) { onPick(palette.id) }
            .semantics {
                this.selected = selected
                if (selected) contentDescription = "${palette.name}, $selectedLabel"
            }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Swatch(palette)
        Text(
            text = palette.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Background pill holding the primary and surface dots. */
@Composable
private fun Swatch(palette: ThemePalette) {
    Row(
        modifier = Modifier
            .background(Color(palette.background), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(palette.primary)
        Dot(palette.surface)
        Dot(palette.secondary)
    }
}

@Composable
private fun Dot(argb: Int) {
    Box(
        Modifier
            .size(14.dp)
            .background(Color(argb), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
    )
}
