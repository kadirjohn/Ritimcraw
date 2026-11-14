package com.ritim.ritimcraw.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * ThemeSlider (owner spec 2026-11-14, theme_slider — verbatim geometry):
 * the compact "pill" that replaces the System/Light/Dark segmented
 * control. The user taps OR drags horizontally; the theme preference is
 * written exactly once, on release, and only if the slot changed.
 *
 * Geometry (spec, all dp):
 * - track 168x48dp, RoundedCornerShape(24dp), surfaceContainerHighest,
 *   1dp inset outlineVariant border,
 * - three 54dp slots starting at x = 3dp, each showing its icon (22dp,
 *   onSurfaceVariant): slot 0 = Light (sun), 1 = System (auto),
 *   2 = Dark (moon) — left to right. This is the VISUAL order only;
 *   the stored enum and its values are untouched — the caller maps
 *   slot index ↔ ThemeMode,
 * - thumb 54x40dp, top offset 4dp, RoundedCornerShape(20dp),
 *   primary/onPrimary, 2dp shadow, showing the icon of the slot it is
 *   currently over; resting x = 3dp + index * 54dp. The thumb has NO
 *   pointer input — the track owns all gestures.
 *
 * Gesture (on the track): a custom awaitEachGesture + awaitFirstDown
 * loop, NOT detectHorizontalDragGestures — the spec wants the thumb to
 * follow the finger from the touch DOWN with no touch slop, so a tap
 * on a slot is simply a down+up at the same place and selects it
 * through the same code path. Every pointer change the loop sees is
 * consumed, so the parent column's vertical scroll can never steal a
 * gesture that starts on the pill (and scroll still works when the
 * touch starts outside it). While dragging, the thumb follows the
 * finger 1:1 (no animation — plain synchronous state writes; Compose
 * animation primitives are suspend, and the pointerInput scope may
 * only suspend on the pointer APIs) and the live index/hint update;
 * NOTHING is written to the preference during the drag, so the app
 * theme cannot flicker mid-drag. On release the thumb animates to the
 * slot under the release x (spring, dampingRatio 0.8, medium
 * stiffness, launched in the composable's scope) and the preference
 * commits exactly once — only when the index changed. A consumed
 * change (the system cancel — Compose synthesizes an up-event whose
 * changes start consumed — or a parent that stole the gesture) or a
 * broken pointer stream cancels instead: the thumb animates back to
 * the committed slot and nothing commits.
 *
 * Haptics per the spec's fallback chain: HapticFeedbackType.SegmentTick
 * does NOT exist in this Compose version (ui 1.7.3 has only LongPress
 * and TextHandleMove), so TextHandleMove ticks once per slot crossing
 * during a drag and once more to confirm a commit.
 *
 * Semantics: Role.Slider does NOT exist in this Compose version either
 * (ui 1.7.3's Role has only Button/Checkbox/Switch/RadioButton/Tab/
 * Image/DropdownList — verified from the sources), so the slider
 * exposes the platform's own slider recipe instead, the same one
 * material3's Slider uses: progressBarRangeInfo(index, 0f..2f,
 * steps = 1) + a setProgress action + stateDescription
 * (Light/System/Dark) + contentDescription, plus the spec's custom
 * "Previous theme"/"Next theme" actions. D-pad/keyboard left-right
 * (the track is focusable — foundation's focusableInNonTouchMode is
 * internal in this version, so the public focusable() is used) moves
 * one slot and commits.
 *
 * Pixel math: all geometry converts dp to px through LocalDensity at
 * the composable; the pure functions in [ThemeSliderMath] take px
 * floats and are unit-tested (index from x, thumb offset clamp,
 * resting offset, commit rule) so correctness does not depend on
 * seeing the UI.
 */

// ---- Spec geometry (dp) ----
private val TrackWidth = 168.dp
private val TrackHeight = 48.dp
private val TrackInset = 3.dp
private val SlotWidth = 54.dp
private val ThumbWidth = 54.dp
private val ThumbHeight = 40.dp
private val ThumbTopOffset = 4.dp
private val TrackCorner = 24.dp
private val TrackBorderInset = 1.dp
private val ThumbCorner = 20.dp
private val SlotIconSize = 22.dp

/** Slot icons in visual order: 0 = Light, 1 = System, 2 = Dark. */
private val SlotIcons = listOf(Sun, Auto, Moon)

/** The spec's release/settle animation: spring, dampingRatio ~0.8, medium stiffness. */
private val SettleSpec = spring<Float>(
    dampingRatio = 0.8f,
    stiffness = Spring.StiffnessMedium,
)

/**
 * The theme slider's pure pixel math (spec theme_slider gesture, px
 * floats in and out — density conversion happens at the composable).
 * Kept as pure functions so the boundary/clamp rules are unit-tested
 * without a UI:
 *
 * - [slotIndex]: index = clamp(floor((fingerX - inset) / slotWidth), 0, 2)
 *   — the slot under the finger,
 * - [thumbOffsetPx]: thumb x = clamp(fingerX - thumbWidth/2, inset,
 *   inset + 2*slotWidth) — the thumb's left edge, centered on the
 *   finger and clamped to the track,
 * - [restingOffsetPx]: resting thumb x = inset + index*slotWidth,
 * - [shouldCommit]: commit only when the index changed.
 */
internal object ThemeSliderMath {
    const val MAX_INDEX = 2

    /** The slot under fingerX (spec: floor((fingerX - 3dp) / 54dp), clamped 0..2). */
    fun slotIndex(fingerX: Float, trackInsetPx: Float, slotWidthPx: Float): Int =
        floor((fingerX - trackInsetPx) / slotWidthPx).toInt().coerceIn(0, MAX_INDEX)

    /** The thumb's left offset while dragging (spec: clamp(fingerX - 27dp, 3dp, 3dp + 2*54dp)). */
    fun thumbOffsetPx(
        fingerX: Float,
        trackInsetPx: Float,
        slotWidthPx: Float,
        thumbWidthPx: Float,
    ): Float = (fingerX - thumbWidthPx / 2f)
        .coerceIn(trackInsetPx, trackInsetPx + MAX_INDEX * slotWidthPx)

    /** The thumb's resting left offset for a slot (spec: 3dp + index*54dp). */
    fun restingOffsetPx(index: Int, trackInsetPx: Float, slotWidthPx: Float): Float =
        trackInsetPx + index.coerceIn(0, MAX_INDEX) * slotWidthPx

    /** The commit rule (spec: commit once on release, only if the index changed). */
    fun shouldCommit(startIndex: Int, endIndex: Int): Boolean = startIndex != endIndex
}

/**
 * The theme pill. [selectedIndex] is the COMMITTED slot (visual order
 * 0 = Light, 1 = System, 2 = Dark — the caller maps it to the stored
 * enum). [onCommit] fires exactly once per release/key/a11y action and
 * only when the slot changed. [onDisplayIndexChange] reports the slot
 * under the thumb live (during a drag, on release, and back to the
 * committed slot on cancel) so the row hint can name it — the
 * preference itself is never written during the drag.
 */
@Composable
fun ThemeSlider(
    selectedIndex: Int,
    onCommit: (Int) -> Unit,
    onDisplayIndexChange: (Int) -> Unit,
    sliderLabel: String,
    previousLabel: String,
    nextLabel: String,
    stateLabels: List<String>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    // Density-correct pixel math (spec: never assume 1dp = 1px).
    val trackInsetPx = with(density) { TrackInset.toPx() }
    val slotWidthPx = with(density) { SlotWidth.toPx() }
    val thumbWidthPx = with(density) { ThumbWidth.toPx() }
    val thumbTopPx = with(density) { ThumbTopOffset.roundToPx() }

    // Internal mirror of the committed slot: our own commits update it
    // immediately, so the parameter-sync effect below only animates on
    // EXTERNAL changes (the slider is the sole writer in practice; the
    // guard keeps the component honest if that ever changes).
    var committedIndex by remember { mutableIntStateOf(selectedIndex) }
    // The slot under the thumb, live: drives the thumb icon, the
    // stateDescription, and (through onDisplayIndexChange) the row hint.
    var displayIndex by remember { mutableIntStateOf(selectedIndex) }
    var dragging by remember { mutableStateOf(false) }
    // The thumb's left offset in px. A plain state, NOT an Animatable:
    // the drag writes it synchronously from the restricted
    // pointerInput scope (every Compose animation primitive is
    // suspend, and that scope may only suspend on the pointer APIs),
    // while the settle animations run in the composable's scope below.
    var thumbX by remember(trackInsetPx, slotWidthPx) {
        mutableFloatStateOf(
            ThemeSliderMath.restingOffsetPx(selectedIndex, trackInsetPx, slotWidthPx)
        )
    }
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnDisplayIndexChange by rememberUpdatedState(onDisplayIndexChange)

    // The settle animation (spec: spring, dampingRatio ~0.8, medium
    // stiffness): runs in the composable's scope so the restricted
    // pointerInput scope never has to suspend on it. A new target
    // cancels the previous run (a fast re-grab mid-settle just works).
    fun animateThumbTo(targetPx: Float) {
        scope.launch {
            animate(
                initialValue = thumbX,
                targetValue = targetPx,
                animationSpec = SettleSpec,
            ) { value, _ -> thumbX = value }
        }
    }

    // Sync external committed-value changes (never fires for our own
    // commits — those set committedIndex before the value round-trips).
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != committedIndex) {
            committedIndex = selectedIndex
            displayIndex = selectedIndex
            currentOnDisplayIndexChange(selectedIndex)
            if (!dragging) {
                animateThumbTo(
                    ThemeSliderMath.restingOffsetPx(selectedIndex, trackInsetPx, slotWidthPx)
                )
            }
        }
    }

    // Shared commit path for the keyboard and the accessibility actions:
    // move to the target slot, animate, commit once. Returns whether a
    // move happened (false at the ends of the track, so an edge D-pad
    // press can move focus on instead of being swallowed).
    fun commitTo(target: Int): Boolean {
        if (target < 0 || target > ThemeSliderMath.MAX_INDEX || target == committedIndex) {
            return false
        }
        displayIndex = target
        currentOnDisplayIndexChange(target)
        committedIndex = target
        animateThumbTo(ThemeSliderMath.restingOffsetPx(target, trackInsetPx, slotWidthPx))
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        currentOnCommit(target)
        return true
    }

    Box(
        modifier
            .size(TrackWidth, TrackHeight)
            .semantics(mergeDescendants = true) {
                contentDescription = sliderLabel
                stateDescription =
                    stateLabels[displayIndex.coerceIn(0, ThemeSliderMath.MAX_INDEX)]
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = displayIndex.toFloat(),
                    range = 0f..ThemeSliderMath.MAX_INDEX.toFloat(),
                    steps = 1,
                )
                // TalkBack's slider adjustment: the requested progress is
                // snapped to the nearest slot and committed through the
                // same single-commit path.
                setProgress { target ->
                    val slot = target
                        .coerceIn(0f, ThemeSliderMath.MAX_INDEX.toFloat())
                        .roundToInt()
                    commitTo(slot)
                    true
                }
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) {
                        commitTo(committedIndex - 1)
                    },
                    CustomAccessibilityAction(nextLabel) {
                        commitTo(committedIndex + 1)
                    },
                )
            }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> commitTo(committedIndex - 1)
                        Key.DirectionRight -> commitTo(committedIndex + 1)
                        else -> false
                    }
                }
            }
            .pointerInput(trackInsetPx, slotWidthPx, thumbWidthPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    dragging = true
                    // The thumb follows from the touch DOWN, 1:1, no
                    // animation, no touch slop (spec gesture): a tap on
                    // a slot is a down+up at the same place and runs the
                    // same path as a drag. Plain synchronous state
                    // writes — no suspension needed.
                    thumbX = ThemeSliderMath.thumbOffsetPx(
                        down.position.x, trackInsetPx, slotWidthPx, thumbWidthPx,
                    )
                    val downSlot = ThemeSliderMath.slotIndex(
                        down.position.x, trackInsetPx, slotWidthPx,
                    )
                    if (downSlot != displayIndex) {
                        displayIndex = downSlot
                        currentOnDisplayIndexChange(downSlot)
                    }
                    var released = false
                    var cancelled = false
                    while (!released && !cancelled) {
                        val event = awaitPointerEvent()
                        // The spec's cancel case. Compose has no
                        // PointerEventType.Cancel: ACTION_CANCEL never
                        // becomes a PointerEvent — Compose synthesizes
                        // an up-event whose changes START consumed, and
                        // a parent that steals the gesture consumes the
                        // change in an earlier pass. Either way the
                        // signal is the same one foundation's own
                        // awaitDragOrCancellation uses: a change that is
                        // already consumed before us.
                        if (event.changes.any { it.isConsumed }) {
                            cancelled = true
                        } else {
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                // The tracked pointer vanished without an up.
                                cancelled = true
                            } else if (!change.pressed) {
                                released = true
                                // Release: settle into the slot under the
                                // release x and commit EXACTLY ONCE, only
                                // if the index changed (spec).
                                val target = ThemeSliderMath.slotIndex(
                                    change.position.x, trackInsetPx, slotWidthPx,
                                )
                                dragging = false
                                displayIndex = target
                                currentOnDisplayIndexChange(target)
                                animateThumbTo(
                                    ThemeSliderMath.restingOffsetPx(
                                        target, trackInsetPx, slotWidthPx,
                                    )
                                )
                                if (ThemeSliderMath.shouldCommit(committedIndex, target)) {
                                    committedIndex = target
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                    currentOnCommit(target)
                                }
                            } else {
                                // Still dragging: follow 1:1, tick on
                                // slot crossing, never write the
                                // preference (no theme flicker).
                                thumbX = ThemeSliderMath.thumbOffsetPx(
                                    change.position.x, trackInsetPx, slotWidthPx, thumbWidthPx,
                                )
                                val slot = ThemeSliderMath.slotIndex(
                                    change.position.x, trackInsetPx, slotWidthPx,
                                )
                                if (slot != displayIndex) {
                                    displayIndex = slot
                                    currentOnDisplayIndexChange(slot)
                                    haptics.performHapticFeedback(
                                        HapticFeedbackType.TextHandleMove
                                    )
                                }
                            }
                        }
                        // Consume every change of the event so the
                        // parent column's scroll can never claim a
                        // gesture that started on the pill.
                        event.changes.forEach { it.consume() }
                    }
                    if (cancelled) {
                        // Cancel: animate back to the committed slot,
                        // commit nothing (spec).
                        dragging = false
                        displayIndex = committedIndex
                        currentOnDisplayIndexChange(committedIndex)
                        animateThumbTo(
                            ThemeSliderMath.restingOffsetPx(
                                committedIndex, trackInsetPx, slotWidthPx,
                            )
                        )
                    }
                }
            },
    ) {
        // Track: the spec's container + 1dp INSET border (the border
        // sits 1dp inside the track edge, not on it).
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    shape = RoundedCornerShape(TrackCorner),
                )
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(TrackBorderInset)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        shape = RoundedCornerShape(TrackCorner - TrackBorderInset),
                    )
            )
            // The three slots, from x = 3dp.
            Row(Modifier.padding(start = TrackInset)) {
                SlotIcons.forEach { icon ->
                    Box(
                        modifier = Modifier.size(SlotWidth, TrackHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(SlotIconSize),
                        )
                    }
                }
            }
        }
        // The thumb: no pointer input of its own (the track owns all
        // gestures — the spec's rule), lifted by its shadow above the
        // slot icons. offset{} reads the position state so the settle
        // animation runs in layout, without recomposition.
        Surface(
            modifier = Modifier
                .offset { IntOffset(thumbX.roundToInt(), thumbTopPx) }
                .size(ThumbWidth, ThumbHeight),
            shape = RoundedCornerShape(ThumbCorner),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 2.dp,
        ) {
            Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = SlotIcons[displayIndex.coerceIn(0, ThemeSliderMath.MAX_INDEX)],
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(SlotIconSize),
                )
            }
        }
    }
}
