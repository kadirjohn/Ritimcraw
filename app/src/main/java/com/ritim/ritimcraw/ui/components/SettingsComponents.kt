package com.ritim.ritimcraw.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Shared Settings-redesign building blocks (owner spec 2026-11-14,
 * polished by the 2026-11-15 v2 pass). One file: the components are
 * small, all presentation-only, and read together as the spec's
 * components section.
 *
 * v2 C1: groups are NO outlined cards — every row is its own tonal
 * container ([SettingsGroupItem]) and the group reads as one rounded
 * block through the 20dp/4dp corner pattern ([settingsItemShape]).
 * Categories separate by section labels, spacing, tonal fills and
 * shape, never by strokes (outline_policy).
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
 * The segmented item's shape by group position (owner spec 2026-11-15,
 * C1): a group of N items reads as ONE rounded block — 20dp outer
 * corners, 4dp inner corners:
 * - count 1: 20dp on all four corners,
 * - first of many: 20dp top, 4dp bottom,
 * - middle: 4dp everywhere,
 * - last of many: 4dp top, 20dp bottom.
 *
 * Out-of-range indices clamp to the group's edges (defensive — the
 * call sites pass real positions). Pure, no composition: pinned by
 * SettingsGroupShapesTest so the only/first/mid/last cases cannot
 * regress unseen.
 */
internal fun settingsItemShape(index: Int, count: Int): RoundedCornerShape {
    if (count <= 1) return RoundedCornerShape(20.dp)
    val first = index <= 0
    val last = index >= count - 1
    return if (first && last) {
        RoundedCornerShape(20.dp)
    } else if (first) {
        RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp,
            bottomEnd = 4.dp,
            bottomStart = 4.dp,
        )
    } else if (last) {
        RoundedCornerShape(
            topStart = 4.dp,
            topEnd = 4.dp,
            bottomEnd = 20.dp,
            bottomStart = 20.dp,
        )
    } else {
        RoundedCornerShape(4.dp)
    }
}

/**
 * Segmented group container (owner spec 2026-11-15, C1): a plain
 * Column — 16dp horizontal margin, 2dp between items. The group is NOT
 * a card: no border, no elevation, no outer clip container, no dividers.
 * Every row is its own tonal container ([SettingsGroupItem]) and the
 * group reads as one rounded block through the items' 20dp/4dp corner
 * pattern; the SCREEN supplies the surface background the tonal items
 * sit on (the app shell's Scaffold paints `background` by default, so
 * the settings screen paints colorScheme.surface itself — see
 * SettingsScreen).
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        content()
    }
}

/**
 * One segmented-group item container (owner spec 2026-11-15, C1 + C3):
 * a tonal surfaceContainer fill clipped to the item's group shape —
 * 20dp outer / 4dp inner corners from [settingsItemShape]. No border,
 * no elevation (outline_policy: tonal fill + shape, never a stroke).
 *
 * C3 (the rectangle artifact): the shape, the fill AND the press layer
 * live on ONE node — Modifier.clip(shape) comes BEFORE clickable, so
 * the ripple is clipped to the rounded shape and can never render as a
 * rectangle behind/around a rounded row. (The spec's Surface(shape =
 * ...) sketch cannot carry the click on the same node: a clickable
 * passed through Surface's modifier sits BEFORE Surface's internal
 * clip — exactly the rounded-child-inside-rectangular-clickable-parent
 * pattern C3 forbids — and material3's clickable Surface overload
 * drops the row's v1 Role.Button semantics, so the spec's sanctioned
 * clip-before-clickable pattern is used instead.)
 *
 * [onClick] = null renders the plain, non-interactive container (the
 * expandable rows, the theme row, the device report's codec rows).
 * The item applies NO inner padding — the row content owns its
 * padding (16dp all sides by default, 14dp vertical for single-line
 * non-tappable rows).
 */
@Composable
fun SettingsGroupItem(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(settingsItemShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        onClickLabel = null,
                        role = Role.Button,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                }
            ),
    ) {
        content()
    }
}

/**
 * The switch row's item container (C1 + C3): material3 has no
 * toggleable Surface overload, so the toggle rides the same clip-first
 * one-node pattern as [SettingsGroupItem] — clip(shape) BEFORE
 * toggleable, Role.Switch semantics (the v1 row semantics, unchanged),
 * ripple clipped to the rounded shape.
 */
@Composable
private fun SettingsGroupToggleItem(
    index: Int,
    count: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(settingsItemShape(index, count))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            ),
    ) {
        content()
    }
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
 * 16dp horizontal / [verticalPadding] vertical, gap 12dp, vertically
 * centered, min height 56dp — and no fixed heights, so text rows
 * respect font scale (spec accessibility: use heightIn(min=...) on
 * text rows).
 *
 * C1 padding rule: 16dp on ALL four sides is the item default; 14dp
 * vertical only for single-line rows that are not tappable controls.
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
    verticalPadding: Dp = 14.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = verticalPadding),
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
 * Expandable setting row (spec setting_row_expandable, C1): the row is
 * its own [SettingsGroupItem] container at group position
 * [index]/[count] — the base row's top line plus a trailing info
 * IconButton (48dp target, 22dp icon, tint onSurfaceVariant). The
 * button sits inside the row's own 16dp end padding (the spec's
 * negative-padding offset idiom is illegal in Compose — padding()
 * asserts non-negative at runtime; the owner's 02:51 Settings crash
 * proved it on device — so the inset is the row's own padding, no
 * button offset).
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
 *
 * P2 (owner spec 2026-11-15): the control slot now has 16dp BELOW the
 * segmented control's bottom edge — the v1 slot touched its card's
 * bottom edge. The item's inner padding is 16dp on all four sides
 * (the row is multi-element, not a single-line row).
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
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    SettingsGroupItem(index = index, count = count, modifier = modifier) {
        Column(Modifier.fillMaxWidth()) {
            SettingRowBase(
                icon = icon,
                title = title,
                hint = hint,
                verticalPadding = 16.dp,
                trailing = {
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier
                            .size(48.dp)
                            // The 48dp target sits fully INSIDE the row's own
                            // 16dp end padding (the row pads, the button does
                            // not). The spec's "negative end padding" idiom is
                            // ILLEGAL in Compose — padding() asserts non-
                            // negative AT RUNTIME (PaddingElement, the
                            // owner's 02:51 Settings-tab crash) — so the
                            // visual inset is achieved by NOT offsetting the
                            // button at all: the row's padding already
                            // provides the 16dp inset, and the button's
                            // ripple simply starts 16dp in, which is the
                            // stock M3 row-button look.
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
            // The control slot shares the top line's 16dp gutter so the
            // segmented track aligns with the row's text, and carries
            // the P2 fix: >= 16dp of padding BELOW the control's bottom
            // edge (14dp above keeps the v1 rhythm).
            Box(
                Modifier.padding(
                    start = 16.dp,
                    top = 14.dp,
                    end = 16.dp,
                    bottom = 16.dp,
                )
            ) {
                content()
            }
        }
    }
}

/**
 * Navigation row (spec navigation_row, C1): the whole ITEM is clickable
 * — [SettingsGroupItem] carries the click on the same node that holds
 * the rounded group shape (C3), with the v1 Role.Button semantics. Min
 * height 56dp (from the base row), base layout plus a chevron_right
 * trailing icon (22dp, onSurfaceVariant, decorative). Tappable row:
 * 16dp vertical padding (the C1 item default).
 */
@Composable
fun SettingRowNavigation(
    icon: ImageVector,
    title: String,
    hint: String,
    onClick: () -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    SettingsGroupItem(
        index = index,
        count = count,
        modifier = modifier,
        onClick = onClick,
    ) {
        SettingRowBase(
            icon = icon,
            title = title,
            hint = hint,
            verticalPadding = 16.dp,
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
}

/**
 * Switch row (spec switch_row, C1): the whole ITEM toggles — the
 * toggleable node is the same node that holds the rounded group shape
 * (C3, [SettingsGroupToggleItem]), Role.Switch, the v1 row semantics.
 * Base layout plus a standard Material 3 Switch with default colors
 * (it follows the dynamic theme; the Switch itself carries no own
 * click handler so the row speaks as one control). Tappable row:
 * 16dp vertical padding (the C1 item default).
 */
@Composable
fun SettingRowSwitch(
    icon: ImageVector,
    title: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    SettingsGroupToggleItem(
        index = index,
        count = count,
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
    ) {
        SettingRowBase(
            icon = icon,
            title = title,
            hint = hint,
            verticalPadding = 16.dp,
            trailing = {
                Switch(checked = checked, onCheckedChange = null)
            },
        )
    }
}
