package com.ritim.ritimcraw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Shared Settings-redesign building blocks (owner spec 2026-11-14,
 * verbatim geometry/roles). One file: the components are small, all
 * presentation-only, and read together as the spec's components section.
 *
 * Every color is a Material 3 role from MaterialTheme.colorScheme — the
 * app's dynamic color (Monet) supplies the actual values, so nothing
 * here hardcodes a hex literal and the result re-tints under any
 * wallpaper, in light and dark.
 *
 * Text styles: the spec's sizes (12sp labels, 13sp hints, 14sp segment
 * labels, 16sp titles) have no exact match in RitimTypography, so they
 * are TextStyle copies/merges of the scale's family (Roboto via
 * FontFamily.Default) with the spec's size/weight/letterSpacing — the
 * app's existing font family, per the spec's hard constraint.
 */

/**
 * Section label (spec section_label): 12sp SemiBold ALL CAPS,
 * letterSpacing 0.08em, onSurfaceVariant. Start/end 20dp, top 24dp
 * (16dp when [first] — the first section sits closer under the title),
 * bottom 8dp. The caller uppercases the label text (the string
 * resource stays the natural-case source for a11y tools that read it).
 */
@Composable
fun SettingsSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
) {
    Text(
        text = text.uppercase(),
        style = TextStyle(
            fontSize = 12.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.08.em,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = 20.dp,
                top = if (first) 16.dp else 24.dp,
                end = 20.dp,
                bottom = 8.dp,
            ),
    )
}

/**
 * Group card (spec group_card): RoundedCornerShape(20dp),
 * surfaceContainerLow, 1dp outlineVariant border, no elevation, clipped
 * content (Surface clips to its shape). 16dp horizontal margin.
 */
@Composable
fun SettingsGroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // No elevation (spec): the card is flat, outlined.
    ) {
        Column(Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * One row slot inside a [SettingsGroupCard], with the spec's inset
 * divider before it when [divider] is true (the first row has none):
 * 1dp outlineVariant, inset 16dp both sides.
 */
@Composable
fun SettingsGroupCardRow(
    divider: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (divider) {
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
    Column(modifier) { content() }
}

/**
 * Icon tile (spec icon_tile): 40x40dp, RoundedCornerShape(12dp),
 * primaryContainer / onPrimaryContainer, 22dp icon, centered.
 * Decorative — contentDescription = null.
 */
@Composable
fun SettingsIconTile(
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * Setting row base (spec setting_row_base): [SettingsIconTile] + a
 * weighted title/hint column + an optional trailing slot. Row padding
 * 16dp horizontal / 14dp vertical, gap 12dp, vertically centered, min
 * height 56dp — and no fixed heights, so text rows respect font scale
 * (spec accessibility: use heightIn(min=...) on text rows).
 *
 * Title: 16sp SemiBold, onSurface, maxLines from [titleMaxLines] (the
 * readback title may wrap to 2). Hint: 13sp Regular, onSurfaceVariant,
 * top padding 2dp, one line.
 */
@Composable
fun SettingRowBase(
    icon: ImageVector,
    title: String,
    hint: String?,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 1,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsIconTile(icon)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontSize = 16.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = titleMaxLines,
            )
            if (hint != null) {
                Text(
                    text = hint,
                    style = TextStyle(
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Expandable setting row (spec setting_row_expandable): the base row's
 * top line plus a trailing info IconButton (48dp target, 22dp icon,
 * tint onSurfaceVariant). The IconButton's 48dp target is offset with a
 * negative end padding so the card's visual padding looks unchanged.
 * The button toggles a rememberSaveable local boolean and exposes the
 * expanded state via semantics (stateDescription Expanded/Collapsed
 * on the button).
 *
 * When expanded (AnimatedVisibility, expandVertically + fadeIn,
 * 200ms): a note box directly under the top line (top margin 12dp,
 * padding 10dp vertical x 12dp horizontal, RoundedCornerShape(12dp),
 * surfaceContainerHighest, 13sp, lineHeight 1.45x = 19sp,
 * onSurfaceVariant). The [content] slot (the segmented control) sits
 * under the note when expanded, directly under the top line when
 * collapsed — 14dp top margin either way, always visible (the spec
 * animates the note, not the control).
 */
@Composable
fun SettingRowExpandable(
    icon: ImageVector,
    title: String,
    hint: String,
    infoContentDescription: String,
    infoNote: String,
    expandedText: String,
    collapsedText: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        SettingRowBase(
            icon = icon,
            title = title,
            hint = hint,
            trailing = {
                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier
                        .size(48.dp)
                        // Negative end padding pulls the 48dp target
                        // back into the row's own 16dp end padding —
                        // the card's visual padding stays unchanged
                        // while the touch target keeps its full size.
                        .padding(end = (-16).dp)
                        .semantics {
                            stateDescription =
                                if (expanded) expandedText else collapsedText
                        },
                ) {
                    Icon(
                        imageVector = Info,
                        contentDescription = infoContentDescription,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = tween(200)) +
                fadeIn(animationSpec = tween(200)),
        ) {
            Text(
                text = infoNote,
                style = TextStyle(
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    fontWeight = FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Box(Modifier.padding(top = 14.dp)) {
            content()
        }
    }
}

/**
 * Navigation row (spec navigation_row): whole row clickable
 * (Role.Button), min height 56dp (from the base row), base layout plus
 * a chevron_right trailing icon (22dp, onSurfaceVariant, decorative).
 */
@Composable
fun SettingRowNavigation(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRowBase(
        icon = icon,
        title = title,
        hint = hint,
        modifier = modifier.clickable(
            onClickLabel = null,
            role = Role.Button,
            onClick = onClick,
        ),
        trailing = {
            Icon(
                imageVector = ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        },
    )
}

/**
 * Switch row (spec switch_row): base layout plus a standard Material 3
 * Switch with default colors (it follows the dynamic theme). The whole
 * row toggles (toggleable modifier, Role.Switch); the Switch itself
 * carries no own click handler so the row speaks as one control.
 */
@Composable
fun SettingRowSwitch(
    icon: ImageVector,
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingRowBase(
        icon = icon,
        title = title,
        hint = hint,
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        ),
        trailing = {
            Switch(checked = checked, onCheckedChange = null)
        },
    )
}
