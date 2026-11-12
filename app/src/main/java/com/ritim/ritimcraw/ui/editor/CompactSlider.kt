package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.ui.theme.Tnum

/**
 * The compact grade slider (design review 2026-11-10, owner request
 * "ince slider"): label left + tabular value right on one row, a thin
 * ([CompactSliderDefaults.TRACK_HEIGHT]) track with a small
 * ([CompactSliderDefaults.THUMB_SIZE]) thumb below it, the whole row
 * exactly [CompactSliderDefaults.ROW_HEIGHT] tall. The M3 Slider
 * underneath keeps its own minimumInteractiveComponentSize, so the
 * VISUAL line thins while the TOUCH area stays 48dp (the review's
 * acceptance: the touch area is preserved while the visual line thins).
 *
 * Compact rows (owner request 2026-11-12: "the vertical spacing between
 * the sliders is too large"): the Slider's 48dp touch slot is mostly
 * whitespace — M3 centers the 4dp track in it (22dp of empty space above
 * the line) — so the label row rides IN that whitespace instead of
 * stacking above the slot. One row is the 48dp touch floor instead of
 * label(~24dp) + slot(48dp) = ~72dp. The pure decision in
 * [CompactSliderDefaults.overlayLabel] falls back to the stacked
 * geometry when the label outgrows the whitespace budget (a large font
 * scale), so the row never crowds itself at any font size.
 *
 * Bipolar sliders (range containing 0) fill from ZERO with a tick at
 * the zero position, so a negative value visibly fills left of center
 * and a positive value right of it (owner request). Track gradients:
 * Temperature blue->yellow, Tint green->magenta, Saturation
 * gray->colorful ([trackGradient]); sliders without one keep the plain
 * active/inactive scheme colors.
 *
 * Behavior is unchanged from the previous in-line GradeSlider: snap
 * grids (SliderSteps via the caller's onChange), double-tap reset on
 * the whole row, and the SliderTiers drag wiring through
 * [onDragActiveChange] (the interaction stream still reports
 * DragInteraction Start/Stop/Cancel; a tap is a zero-length drag).
 *
 * Pure visual extraction from ClipEditorScreen (2026-11-10): same name,
 * same parameters (new ones defaulted), same package — the panels' call
 * sites compile unchanged.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GradeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    valueLabel: String,
    onChange: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    trackGradient: List<Color>? = null,
) {
    // Double tap anywhere on the slider row resets this control to its
    // GradeK default (DESIGN.md Inputs: "double tap resets").
    val rowModifier = Modifier
        .fillMaxWidth()
        .pointerInput(label) {
            detectTapGestures(onDoubleTap = { onDoubleTapReset() })
        }

    // Finger down/up on a GRADE slider drives the same tier machine as
    // the transport: while the finger is down, re-renders run at the low
    // drag tier through the session's CACHED path — the frame is
    // unchanged, only the grade moved, so the decode is skipped entirely
    // (the whole point of the interactive path). A tap is a zero-length
    // drag: Start + Stop both fire, so a tap settles to full quality too.
    val gradeInteraction = remember { MutableInteractionSource() }
    LaunchedEffect(gradeInteraction) {
        gradeInteraction.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> onDragActiveChange(true)
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    onDragActiveChange(false)
            }
        }
    }
    val colors = SliderDefaults.colors(
        thumbColor = MaterialTheme.colorScheme.primary,
        activeTrackColor = MaterialTheme.colorScheme.primary,
        inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    )
    // Fill-from-zero + zero tick, computed pure (EditorLayout) and
    // JVM-tested; recomposed when the value or range changes.
    val fill = remember(value, valueRange) { EditorLayout.bipolarFill(value, valueRange) }
    val zeroFraction = remember(valueRange) {
        val min = valueRange.start
        val max = valueRange.endInclusive
        if (max > min && min <= 0f && max >= 0f) (0f - min) / (max - min) else -1f
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val slider: @Composable () -> Unit = {
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            steps = SliderSteps.composeSteps(valueRange, step),
            interactionSource = gradeInteraction,
            colors = colors,
            modifier = Modifier.fillMaxWidth(),
            thumb = {
                SliderDefaults.Thumb(
                    interactionSource = gradeInteraction,
                    colors = colors,
                    thumbSize = CompactSliderDefaults.THUMB_SIZE,
                )
            },
            track = {
                ThinTrack(
                    fill = fill,
                    zeroFraction = zeroFraction,
                    gradient = trackGradient,
                    isRtl = isRtl,
                )
            },
        )
    }
    val labelRow: @Composable (Dp) -> Unit = { minRowHeight ->
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minRowHeight),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            // tnum for EV / Kelvin / signed values (DESIGN.md Tabular numbers).
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge.merge(Tnum),
            )
        }
    }

    // Compact rows (owner request 2026-11-12): M3 inflates the Slider to a
    // 48dp touch slot and centers the 4dp track and 16dp thumb in it, so a
    // stacked row wastes ~22dp of empty slot below the label. Overlay the
    // label row into the slot's own whitespace instead: one 48dp row that
    // IS the touch floor. The pure decision in
    // [CompactSliderDefaults.overlayLabel] falls back to the stacked
    // geometry when the label's line box would crowd the thumb band
    // (large font scales), so no configuration renders a cramped row.
    val overlay = CompactSliderDefaults.overlayLabel(
        labelLineHeightSp = MaterialTheme.typography.labelLarge.lineHeight.value,
        fontScale = LocalDensity.current.fontScale,
    )
    if (overlay) {
        Box(rowModifier.height(CompactSliderDefaults.ROW_HEIGHT)) {
            slider()
            // Non-consuming overlay: the label never intercepts slider
            // gestures — a tap on the label drives the slider exactly as
            // a tap on that bare slice of slot always has (the whole 48dp
            // row is the touch target; that is what keeps the floor).
            labelRow(Dp.Unspecified)
        }
    } else {
        Column(rowModifier) {
            labelRow(24.dp)
            slider()
        }
    }
}

/**
 * Visual constants for the compact slider (owner request: thin line,
 * small thumb). Token names mirror the M3 slider tokens
 * (SliderTokens TrackHeight / HandleWidth) so the numbers stay
 * traceable to the platform defaults they thin down from.
 */
object CompactSliderDefaults {
    /** Thin track line (M3 default TrackHeight is 16dp; owner request 2026-11-11d section 12.1 band is 3-4dp). */
    val TRACK_HEIGHT = 4.dp

    /**
     * One grade slider row's total height — the 48dp touch floor itself
     * (DESIGN.md Touch targets). With the label overlaid into the slot's
     * own whitespace (see [overlayLabel]) the row IS the M3 Slider's
     * minimumInteractiveComponentSize slot: exactly as tall as the touch
     * target, no taller — owner request 2026-11-12 ("compact, not
     * cramped": the floor, not under it).
     */
    val ROW_HEIGHT = 48.dp

    /**
     * Overlay budget: the largest label line height (sp, after the user's
     * font scale) that may ride inside the slider's 48dp touch slot.
     * Slot geometry at fontScale 1 (M3 1.3.0 SliderImpl measure): the
     * slot is 48dp tall, the 4dp track at y=[22,26], the 16dp thumb band
     * at y=[16,32]. A top-aligned labelLarge line box (20sp/20sp) puts
     * its descender tail at y~18 — ~4dp clear of the track line, grazing
     * the thumb band only where a descender glyph sits exactly under the
     * thumb (text draws on top; momentary while dragging). 22sp keeps
     * that clearance through fontScale 1.1; at 1.2 the tail would touch
     * the track, so beyond the budget the row falls back to the stacked
     * geometry — taller, but uncrowded at any font size.
     */
    const val OVERLAY_LABEL_LINE_HEIGHT_MAX_SP = 22f

    /**
     * Pure decision (JVM-tested): may the label row overlay the slider's
     * 48dp touch slot (compact geometry), or must it stack above it
     * (roomy geometry)? Overlay while the label's line height scaled by
     * the user's font scale stays within
     * [OVERLAY_LABEL_LINE_HEIGHT_MAX_SP] — labelLarge (20sp) overlays
     * through fontScale 1.1 and stacks from 1.2 up. An unspecified line
     * height (NaN) stacks: the safe fallback.
     */
    fun overlayLabel(labelLineHeightSp: Float, fontScale: Float): Boolean =
        // +0.01f absorbs float rounding (20f * 1.1f == 22.00000048f —
        // inside the budget by geometry, outside it by a rounding hair)
        // without admitting any real geometry over the budget.
        labelLineHeightSp * fontScale <= OVERLAY_LABEL_LINE_HEIGHT_MAX_SP + 0.01f

    /**
     * Round thumb (M3 default HandleWidth/Height are 4/44dp). 16dp is the
     * low end of owner request 2026-11-11d section 12.1's 16-20dp visible
     * band; the M3 Slider's minimumInteractiveComponentSize keeps the
     * TOUCH target at 48dp (section 12.1: "the actual touch target must
     * be larger than the visible slider thumb").
     */
    val THUMB_SIZE = DpSize(16.dp, 16.dp)

    /** Zero tick: 2dp wide, taller than the track line so it reads. */
    val ZERO_TICK_WIDTH = 2.dp
    val ZERO_TICK_HEIGHT = 12.dp

    /**
     * Temperature axis sweep: cool blue at low K to warm yellow at high K —
     * the Lightroom/ACR white-balance convention (the slider's EFFECT on
     * the image, not the blackbody's own color). Muted pastels (owner
     * request 2026-11-11d section 14.1: "subtle, readable, and not
     * visually overwhelming") on the 4dp track line; hues pending the
     * device pass, like PREVIEW_HEIGHT_FRACTION.
     */
    val TEMPERATURE_GRADIENT = listOf(Color(0xFF7EA6C4), Color(0xFFC9B458))

    /**
     * Tint axis sweep: green (negative) to magenta (positive), the ACR tint
     * convention — muted pastels per section 14.1 (see TEMPERATURE_GRADIENT).
     */
    val TINT_GRADIENT = listOf(Color(0xFF7FA37E), Color(0xFFB087A8))

    /**
     * Saturation axis sweep: desaturated gray (negative) to a soft warm hue
     * (positive) — muted per section 14.1 (see TEMPERATURE_GRADIENT).
     */
    val SATURATION_GRADIENT = listOf(Color(0xFF949494), Color(0xFFC48A5A))
}

/**
 * Thin track + bipolar fill + optional gradient. Pure draw: the active
 * segment is [fill] (from zero to the value for bipolar ranges, plain
 * min-to-value otherwise), the zero position carries a tick when the
 * range is bipolar, and [gradient] (when present) paints the full track
 * as a horizontal sweep. The canvas spans the Slider's full track slot
 * (thumb-center to thumb-center — SliderImpl places the track box at
 * thumbWidth/2 and measures it minus the thumb width), so no extra
 * horizontal padding is added here.
 */
@Composable
private fun ThinTrack(
    fill: ClosedFloatingPointRange<Float>,
    zeroFraction: Float,
    gradient: List<Color>?,
    isRtl: Boolean,
) {
    // Composable scope: resolve scheme colors ONCE, before the draw lambda
    // (the draw lambda captures plain Color values, not composition reads).
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = MaterialTheme.colorScheme.primary
    val tickColor = MaterialTheme.colorScheme.outlineVariant
    val trackHeight = CompactSliderDefaults.TRACK_HEIGHT
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(trackHeight)
            // Default M3 Track rotates 180deg for RTL; same convention here
            // so fractions stay LTR inside the draw lambda.
            .let { if (isRtl) it.rotate(180f) else it },
    ) {
        val w = size.width
        val y = size.height / 2f
        val stroke = trackHeight.toPx()
        val start = Offset(0f, y)
        val end = Offset(w, y)
        // Full track: gradient sweep when the axis has one, else the plain
        // inactive color.
        if (gradient != null) {
            drawLine(
                brush = Brush.horizontalGradient(colors = gradient, startX = 0f, endX = w),
                start = start,
                end = end,
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        } else {
            drawLine(
                color = inactiveColor,
                start = start,
                end = end,
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
        // Active fill: from zero to the value (bipolar) or from the start to
        // the value (unipolar — EditorLayout.bipolarFill's zeroFraction is
        // 0 there, so the fill is the plain min-to-value segment).
        val fillStartX = w * fill.start
        val fillEndX = w * fill.endInclusive
        drawLine(
            color = activeColor,
            start = Offset(fillStartX, y),
            end = Offset(fillEndX, y),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        // Zero tick for bipolar sliders: a small vertical marker at the
        // zero position, taller than the track line so it reads at a
        // glance (owner request: fill from zero + zero mark).
        if (zeroFraction >= 0f) {
            val zeroX = w * zeroFraction
            val tickW = CompactSliderDefaults.ZERO_TICK_WIDTH.toPx()
            val tickH = CompactSliderDefaults.ZERO_TICK_HEIGHT.toPx()
            drawLine(
                color = tickColor,
                start = Offset(zeroX, y - tickH / 2),
                end = Offset(zeroX, y + tickH / 2),
                strokeWidth = tickW,
                cap = StrokeCap.Round,
            )
        }
    }
}
