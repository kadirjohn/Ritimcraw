@file:OptIn(ExperimentalFoundationApi::class)

package com.ritim.ritimcraw.ui.editor

import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R

/**
 * The drag state for the adjustment panel, wired to the pure constants
 * in [PanelSheet]: release settles to the nearest anchor past
 * [PanelSheet.positionalThreshold] or on a fling above
 * [PanelSheet.VELOCITY_THRESHOLD], and the settle runs the plain
 * [PanelSheet.SNAP_DURATION_MS] tween (no overshoot, section 11.4).
 *
 * Remembered ONCE per editor open — [initial] is the restored panel
 * state on the first composition (process death / configuration change);
 * later panel moves flow through the drag state itself, never a
 * recreation, so the offset is never lost (section 18).
 */
@Composable
fun rememberPanelDragState(initial: PanelState): AnchoredDraggableState<PanelState> {
    val density = LocalDensity.current
    val decay = rememberSplineBasedDecay<Float>()
    return remember {
        AnchoredDraggableState(
            initialValue = initial,
            positionalThreshold = { distance -> PanelSheet.positionalThreshold(distance) },
            velocityThreshold = { with(density) { PanelSheet.VELOCITY_THRESHOLD.toPx() } },
            snapAnimationSpec = tween(PanelSheet.SNAP_DURATION_MS),
            decayAnimationSpec = decay,
        )
    }
}

/**
 * The fixed Light/Color/Detail bottom navigation (owner request
 * 2026-11-11d section 10): three evenly spaced categories with vector
 * icons and short labels, anchored at the editor's bottom above the
 * system navigation inset, OUTSIDE the draggable panel — it stays
 * visible and accessible in every panel state (section 10.1). The
 * current category stays selected while collapsed (section 10.3).
 *
 * windowInsets are ZEROED: AppNav's Scaffold already applies the system
 * bar insets as padding around the editor, so the default
 * NavigationBar insets would double-pad. Colors are inherited from
 * RitimTheme(editor = true) — the fixed neutral scheme, no dynamic
 * tinting, matching the editor chrome.
 */
@Composable
fun EditorBottomNav(
    selected: Int,
    onCategoryTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(
        modifier = modifier,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        NavigationBarItem(
            selected = selected == 0,
            onClick = { onCategoryTap(0) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_editor_nav_light),
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.editor_tab_light)) },
        )
        NavigationBarItem(
            selected = selected == 1,
            onClick = { onCategoryTap(1) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_editor_nav_color),
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.editor_tab_color)) },
        )
        NavigationBarItem(
            selected = selected == 2,
            onClick = { onCategoryTap(2) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_editor_nav_detail),
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.editor_tab_detail)) },
        )
    }
}

/**
 * The landscape category rail (owner layout-refactor spec 2026-11-12
 * section 5): the SAME three categories, icons, labels, selected state
 * and callback contract as [EditorBottomNav] — moved to a compact
 * vertical rail on the editor's FAR RIGHT edge in the two-column
 * workspace, so landscape stops spending the bottom bar's 80dp of
 * vertical space on navigation. The bottom bar stays for portrait
 * (section 5 keeps it there); BOTH read the same shared category Int,
 * so the selection survives every rotation and never forks.
 *
 * Stock M3 [NavigationRail]/[NavigationRailItem] (DESIGN.md: stock
 * components, color roles): the items stack the compact label under the
 * icon (readable, not truncated), the selected item carries the rail's
 * own pill+indicator treatment, and each item's touch target meets the
 * 48dp minimum. windowInsets are ZEROED for the same reason as
 * [EditorBottomNav]: AppNav's Scaffold already applies the system bar
 * insets around the editor, so the default rail insets would double-pad
 * the right edge. The rail fills the height it is given (the screen's
 * Row column) and centers its items top-aligned per the M3 convention —
 * the screen constrains the WIDTH from [OrientationLayout.railWidthDp],
 * never a hardcoded dp.
 */
@Composable
fun EditorCategoryRail(
    selected: Int,
    onCategoryTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier,
        windowInsets = WindowInsets(0, 0, 0, 0),
    ) {
        NavigationRailItem(
            selected = selected == 0,
            onClick = { onCategoryTap(0) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_editor_nav_light),
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.editor_tab_light)) },
        )
        NavigationRailItem(
            selected = selected == 1,
            onClick = { onCategoryTap(1) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_editor_nav_color),
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.editor_tab_color)) },
        )
        NavigationRailItem(
            selected = selected == 2,
            onClick = { onCategoryTap(2) },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_editor_nav_detail),
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(R.string.editor_tab_detail)) },
        )
    }
}

/**
 * The panel's drag handle (owner request 2026-11-11d section 11.2, trim
 * 2026-11-12): a visible 36x4dp rounded bar, muted gray, inside a
 * [PanelHandleHeight]-tall full-width touch target — "significantly
 * larger" than the visible bar. This is the ONLY node in the editor
 * that carries the panel drag modifier: sliders, the scrollable list,
 * the timeline and the navigation sit on other nodes, so none of them
 * can ever resize the panel (the gesture arbitration contract of
 * section 11.5, recorded in [PanelSheet]).
 *
 * TRIM (owner add-on 2026-11-12): the target measures itself with
 * requiredHeight — it IGNORES the incoming max-height constraint and
 * stays 48dp even when the collapsed sheet is [PanelCollapsedHeight]
 * (36dp) tall, overhanging the 12dp difference DOWNWARD into the
 * navigation bar's empty top chrome (the M3 bar is 80dp with its items'
 * content centered; a drag there is a natural panel-open, a tap still
 * reaches the item below — both open the panel). The overhang is
 * hit-testable because [EditorPanelSheet] draws its shape with
 * Modifier.background instead of a clipping Surface. The VISIBLE bar
 * is biased TopCenter with 16dp top padding so it sits centered in the
 * 36dp visible strip (16..20dp from the sheet top) instead of following
 * the enlarged target's center; open states place it 6dp higher than
 * before the trim, which reads as the same affordance.
 */
@Composable
fun EditorPanelHandle(
    dragState: AnchoredDraggableState<PanelState>,
    modifier: Modifier = Modifier,
) {
    val handleDescription = stringResource(R.string.editor_panel_handle)
    Box(
        modifier
            .fillMaxWidth()
            .requiredHeight(PanelHandleHeight)
            .anchoredDraggable(
                state = dragState,
                orientation = Orientation.Vertical,
                // Offset = panel HEIGHT: a finger-up drag must move toward
                // EXPANDED. anchoredDraggable's default maps up-drag to
                // smaller offsets, so the direction is reversed — the same
                // convention ModalBottomSheet uses for its sheet drag.
                reverseDirection = true,
            )
            .semantics { contentDescription = handleDescription },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            Modifier
                .padding(top = 16.dp)
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
    }
}

/**
 * The draggable, collapsible adjustment panel (owner request
 * 2026-11-11d section 11): a bottom-anchored sheet whose height IS the
 * drag state's offset, settling between the three [PanelSheet] anchors.
 * The handle strip sits at the top; the category content is ALWAYS
 * composed below it — clipped, not removed, when collapsed — so the
 * active category, every adjustment value, the per-category scroll
 * position, playback and the render session all survive
 * collapse/expand by construction (sections 10.3, 11.6 and 18).
 *
 * [scrollStates] carries ONE ScrollState PER CATEGORY (the screen owns
 * them rememberSaveable, section 11.6 "relevant scroll position"): the
 * list restores its own category's offset on a switch instead of
 * inheriting the previous category's, and each category keeps its
 * position while the user is elsewhere. [category] is the same Int the
 * navigation passes; a missing entry falls back to a throwaway local
 * state so the sheet still composes.
 *
 * The screen sizes this sheet by the drag offset (height modifier) and
 * subtracts the fixed rows around it for the anchor math; the sheet
 * itself never measures the panel back — no feedback loop.
 *
 * Landscape two-column workspace (owner request 2026-11-11d sections
 * 17.1-17.2): [landscape] = true composes the SAME sheet as the full-height
 * RIGHT column — no drag handle (section 17.3 does not force the portrait's
 * vertical panel dragging in landscape), the same per-category scroll
 * states, the same content slot. The screen weights the columns from
 * OrientationLayout's responsive 30-40% band; the sheet itself stays
 * layout-agnostic (it just fills the height it is given and scrolls
 * inside, section 17.2: "independently scrollable").
 *
 * [header] composes at the top of the landscape column only (the portrait
 * sheet's affordance is its drag handle): the screen supplies the section
 * 17.3 hide button there — the visibility state is the screen's, the
 * sheet just provides the slot.
 */
@Composable
fun EditorPanelSheet(
    dragState: AnchoredDraggableState<PanelState>,
    category: Int,
    scrollStates: Map<Int, ScrollState>,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    header: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    // SQUIRCLE SHEET EDGES (owner add-on 2026-11-12): the portrait sheet
    // gets 28dp top corners — the DESIGN.md M3 shape scale ("bottom
    // sheets 28dp top corners"); the radius equals the stock M3
    // shapes.extraLarge (28dp), built top-only because the sheet's
    // bottom sits flush against the navigation bar, and applied ONLY in
    // portrait (the landscape sheet is a full-height pane, not a bottom
    // sheet). ContinuousCornerRoundedShape is not in this Compose
    // version, so the token is a plain RoundedCornerShape at the same
    // radius the shape scale names.
    val sheetColor = MaterialTheme.colorScheme.surfaceContainer
    val sheetShape = if (landscape) RectangleShape else {
        RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    }
    // The shape is DRAWN with Modifier.background, NOT applied through a
    // Surface shape param: Surface(shape) clips its content AND bounds
    // its children's pointer hit-testing to the shape, which would kill
    // the handle's collapsed-state overhang (the 12dp of the 48dp touch
    // target that extends below the 36dp collapsed sheet — see
    // [EditorPanelHandle]). background paints the same shape without
    // clipping; content never paints outside the rounded top corners
    // anyway (the transparent handle band covers them, the sheet's
    // bottom is flush against the navigation bar). The Box + explicit
    // LocalContentColor provider replicates Surface's contentColor
    // contract (contentColorFor) so text and icons keep their default
    // on-surface colors exactly as before.
    Box(
        modifier
            .fillMaxWidth()
            .background(sheetColor, sheetShape),
    ) {
        CompositionLocalProvider(
            LocalContentColor provides contentColorFor(sheetColor),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    // Landscape: the sheet IS the full-height right column
                    // (the Row child's fillMaxHeight constraint).
                    .then(if (landscape) Modifier.fillMaxHeight() else Modifier),
            ) {
                if (!landscape) {
                    EditorPanelHandle(dragState)
                }
                // Landscape header (owner request 2026-11-11d section 17.3):
                // the full-height column has no drag handle, so the hide
                // affordance lives in this header row instead — the screen
                // supplies it (the state is the screen's, not the sheet's).
                if (landscape && header != null) {
                    header()
                }
                // weight(1f): the scroll region takes whatever the sheet's
                // height constraint leaves below the handle, so the content
                // SCROLLS INSIDE the panel instead of expanding it (section
                // 11.3 states B/C are scrollable; the screen fixes the height
                // via the drag offset — or the full column height, in
                // landscape).
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scrollStates[category] ?: rememberScrollState()),
                ) {
                    content()
                }
            }
        }
    }
}
