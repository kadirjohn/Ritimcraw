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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R

/**
 * The handle strip's height — also the panel's COLLAPSED anchor height
 * (owner request 2026-11-11d section 11.3 state A: collapsed leaves only
 * the affordance strip). dp so the screen converts it with density for
 * [PanelSheet.anchorHeights]; the visible bar inside is much smaller.
 */
val PanelHandleHeight = 48.dp

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
 * The panel's drag handle (owner request 2026-11-11d section 11.2): a
 * visible 36x4dp rounded bar, muted gray, centered inside a
 * [PanelHandleHeight]-tall full-width touch target — "significantly
 * larger" than the visible bar. This is the ONLY node in the editor
 * that carries the panel drag modifier: sliders, the scrollable list,
 * the timeline and the navigation sit on other nodes, so none of them
 * can ever resize the panel (the gesture arbitration contract of
 * section 11.5, recorded in [PanelSheet]).
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
            .height(PanelHandleHeight)
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
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
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
 * The screen sizes this sheet by the drag offset (height modifier) and
 * subtracts the fixed rows around it for the anchor math; the sheet
 * itself never measures the panel back — no feedback loop.
 */
@Composable
fun EditorPanelSheet(
    dragState: AnchoredDraggableState<PanelState>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            EditorPanelHandle(dragState)
            // weight(1f): the scroll region takes whatever the sheet's
            // height constraint leaves below the handle, so the content
            // SCROLLS INSIDE the panel instead of expanding it (section
            // 11.3 states B/C are scrollable; the screen fixes the height
            // via the drag offset).
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                content()
            }
        }
    }
}
