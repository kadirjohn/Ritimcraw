package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Pure color-wheel pad math (COL-13, stage 18) — JVM-testable without Compose.
 * The disc's visual language IS the native gain math (Develop.cpp stage 18):
 * a pad point at (x, y) on the unit disc means the offsets the engine will
 * apply, r *= 2^(0.5x), g *= 2^(0.5y), b *= 2^(-0.5x) — x is the temp idiom
 * (right warm: red up, blue down), y the tint idiom (up green, down
 * magenta). The disc colors below are those gains themselves, scaled to the
 * display range, so the pad never lies about what a drag does.
 */
object ColorWheelMath {

    /**
     * Full deflection is half a stop per axis (the native stage-18 contract):
     * the exponent factor on red at x = 1. Kept here so the UI's readouts and
     * the disc shading quote ONE constant with Develop.cpp's 0.5.
     */
    const val STOPS_PER_UNIT = 0.5f

    /**
     * The pad point for a pointer at [px], [py] inside the disc of pixel
     * [diameter] (the square Canvas side): center-origin, clamped to the
     * unit disc — a drag past the rim holds the rim direction (standard
     * wheel behavior; releasing outside is a rim value, never an
     * out-of-range offset).
     */
    fun offsetFromPointer(px: Float, py: Float, diameter: Float): Pair<Float, Float> {
        if (diameter <= 0f) return 0f to 0f
        val half = diameter / 2f
        var dx = (px - half) / half
        var dy = (py - half) / half
        val r = hypot(dx, dy)
        if (r > 1f) {
            dx /= r
            dy /= r
        }
        return dx to dy
    }

    /**
     * The per-channel display gains a pad offset maps to — the native
     * stage-18 exponents (2^0.5x, 2^0.5y, 2^-0.5x) evaluated at the offset.
     * Pure so a JVM test can lock the mapping the disc renders and the
     * readouts quote: the UI and the engine share one definition.
     */
    fun gainsAt(x: Float, y: Float): FloatArray {
        val rx = exp2(STOPS_PER_UNIT * x)
        val gy = exp2(STOPS_PER_UNIT * y)
        val bx = exp2(-STOPS_PER_UNIT * x)
        return floatArrayOf(rx, gy, bx)
    }

    private fun exp2(e: Float): Float = exp(e * LN2)

    private const val LN2 = 0.6931472f

    /**
     * The disc color at a normalized pad point (|x|, |y| <= 1): the stage-18
     * gains mapped into display range — normalized by the max gain so the
     * dominant channel is full brightness (the rim's warm/cool/green
     * extremes), then mixed toward neutral gray by the offset magnitude
     * (the classic wheel idiom: distance from the center reads as
     * strength). The center is neutral gray — the spec's "merkez
     * nötrdür": the gains there are all 1, and plain max-normalization
     * would render them white, reading as a brightness change the wheel
     * never makes. The mapping is monotone per channel, so the disc reads
     * warm right / cool left / green up / magenta down exactly where the
     * engine warms, cools, greens and magentas.
     */
    fun discColor(x: Float, y: Float): Color {
        val g = gainsAt(x, y)
        val max = maxOf(g[0], g[1], g[2])
        // One exponent is always >= 0 (x and -x bracket 0), so max >= 1:
        // the division scales every channel into (0..1] and lands 1 on a
        // dominant channel — no zero-division at any pad point.
        val nr = g[0] / max
        val ng = g[1] / max
        val nb = g[2] / max
        // Radial saturation: hue direction from the gains, strength from
        // the offset magnitude — gray at the center, the full muted gain
        // color at the rim.
        val strength = min(1f, hypot(x, y))
        return Color(
            red = 0.5f + strength * (nr - 0.5f),
            green = 0.5f + strength * (ng - 0.5f),
            blue = 0.5f + strength * (nb - 0.5f),
        )
    }

    /**
     * The split-tone swatch color for a hue in degrees (COL-13, stage 19):
     * the native hueGains windows — raised-cosine per channel, red peaking
     * at 0, green 120, blue 240, unit circular average — mapped to display
     * range the same way the disc maps gains: divide by the max so the
     * dominant channel lands 1. Off-window channels sit at 0.5 of the
     * dominant (the unit-average property), so the swatch shows a muted
     * tint — exactly the bounded push the engine applies.
     */
    fun splitHueColor(hueDeg: Float): Color {
        val g = splitHueGains(hueDeg)
        val max = maxOf(g[0], g[1], g[2])
        return Color(red = g[0] / max, green = g[1] / max, blue = g[2] / max)
    }

    /**
     * The native stage-19 hue window gains (Develop.cpp hueGains): each
     * channel a raised cosine 120 degrees apart, rescaled so the circular
     * average is 1. Pure so the JVM tests can lock the window shape the
     * swatch renders against the engine's own definition.
     */
    fun splitHueGains(hueDeg: Float): FloatArray {
        val gains = FloatArray(3)
        for (c in 0..2) {
            val center = 120f * c
            var d = abs(hueDeg - center)
            if (d > 180f) d = 360f - d
            gains[c] = 2f * (0.5f + 0.5f * cos(d * PI_DIV_180))
        }
        return gains
    }

    /**
     * The split-tone hue sliders' track gradient (COL-13, stage 19): the
     * [splitHueColor] sweep sampled around the full 0..359 hue circle, so
     * every track position reads as the hue it selects — the hue-window
     * colors ARE the muted pastels (unit circular average: off-window
     * channels sit at 0.5 of the dominant; no extra saturation is
     * invented, section 14.1's "subtle, readable" holds by the math
     * itself). Built once per composition ([remember] at the call site);
     * the TEMPERATURE_GRADIENT precedent, but sampled from the engine's
     * own windows rather than hand-picked endpoints.
     */
    fun hueTrackGradient(samples: Int = 24): List<Color> =
        (0 until samples).map { s -> splitHueColor(360f * s / samples) }

    private const val PI_DIV_180 = PI.toFloat() / 180f
}

/**
 * The three-way color wheel pad (COL-13 UI): one disc, three tonal bands
 * (Shadows / Midtones / Highlights — segmented tabs rendered by the caller
 * above the pad), drag to offset, double-tap to reset the ACTIVE band to
 * center. The pad is the native stage-18 contract made visible: the disc
 * colors are the engine's own gain math (see [ColorWheelMath.discColor]),
 * the thumb sits at the band's stored offset, and the drag snaps to the
 * 0.05 grid via [SliderSteps.snapWheelOffset] — the same grid the grade
 * stores.
 *
 * The disc shading: 72 angular sectors, each a linear gradient from the
 * neutral center to the sector's rim color (the stage-18 gains at the rim
 * direction, max-normalized). The two-dimensional hue field (x temp AND y
 * tint) needs the sector sweep — a ring fill alone cannot express both
 * axes. 72 sectors at the 180dp pad are 2.5° each: visually smooth at any
 * phone density while the per-sector gradient keeps the cost flat.
 *
 * DESIGN.md Inputs: 48dp minimum touch target (the whole pad is one target,
 * 180dp), Material color roles for the chrome (thumb, rim, center dot —
 * never raw hex), tnum for the offset readout (rendered by the caller, not
 * here).
 *
 * The double-tap reset is a DISCRETE edit: the caller's [onDoubleTapReset]
 * rides the reset route (its own undo step, feature N7), never coalesced
 * into the drag gesture. The drag rides the caller's tier machine through
 * [onDragActiveChange] exactly like a GradeSlider drag (low tier while the
 * finger is down, settle on lift).
 */
@Composable
fun ColorWheelPad(
    x: Float,
    y: Float,
    onDragChange: (Float, Float) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
    onDoubleTapReset: () -> Unit = {},
    contentDescription: String = "",
) {
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    val thumbColor = MaterialTheme.colorScheme.primary
    val thumbOutlineColor = MaterialTheme.colorScheme.onSurface
    val rimColor = MaterialTheme.colorScheme.outlineVariant
    val centerColor = MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .heightIn(min = 180.dp)
            .onSizeChanged { padSize = it },
        contentAlignment = Alignment.Center,
    ) {
        // The disc side: the smaller of the box's width/height, so the
        // pad stays a circle at any panel width; floored at 120dp so a
        // zero-measured first pass never renders a degenerate disc.
        val sideDp = with(density) {
            min(padSize.width, padSize.height).toDp() - 4.dp
        }.coerceAtLeast(120.dp)
        Canvas(
            Modifier
                .size(sideDp)
                .semantics {
                    // compose-ui's Role set has no Slider (Button,
                    // Checkbox, DropdownList, Image, RadioButton,
                    // RadioGroup, Switch, Tab), and claiming Image — the
                    // ScopeStrip Canvas precedent — would be false here:
                    // the pad is a control, not a picture. The content
                    // description alone is the honest label; the offset
                    // readout row under the pad carries the current value
                    // to screen-reader users (full 2D-drag accessibility
                    // would need custom actions — a later pass).
                    this.contentDescription = contentDescription
                }
                // The pad handles its own gestures: drag = offset change
                // (the same tier machine as the sliders), double tap =
                // discrete reset. A single tap is a zero-length drag —
                // it moves the offset there, like a slider tap.
                .pointerInput(x, y) {
                    detectTapGestures(onDoubleTap = { onDoubleTapReset() })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onDragActiveChange(true) },
                        onDragEnd = { onDragActiveChange(false) },
                        onDragCancel = { onDragActiveChange(false) },
                    ) { change, _ ->
                        change.consume()
                        val (nx, ny) = ColorWheelMath.offsetFromPointer(
                            change.position.x,
                            change.position.y,
                            sideDp.value * density.density,
                        )
                        onDragChange(
                            SliderSteps.snapWheelOffset(nx),
                            SliderSteps.snapWheelOffset(ny),
                        )
                    }
                },
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            val neutral = ColorWheelMath.discColor(0f, 0f)
            // 72 sectors: each sector's gradient runs from the neutral
            // center to the rim color at the sector's mid-angle — the
            // stage-18 gain field in polar form.
            val sectors = 72
            val sweepRad = 2f * PI.toFloat() / sectors
            val sweepDeg = 360f / sectors
            for (s in 0 until sectors) {
                val midRad = s * sweepRad - PI.toFloat() / 2f
                val rimX = cos(midRad)
                val rimY = sin(midRad)
                val rim = ColorWheelMath.discColor(rimX, rimY)
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(neutral, rim),
                        start = c,
                        end = Offset(c.x + rimX * r, c.y + rimY * r),
                    ),
                    // drawArc's angle origin is 3 o'clock; midRad was
                    // taken from 12 o'clock, so rotate by -90 degrees.
                    startAngle = (s * sweepDeg) - 90f,
                    sweepAngle = sweepDeg + 0.5f, // hairline overlap kills seams
                    useCenter = true,
                )
            }
            // Rim ring: the pad's boundary (outlineVariant, a color role).
            drawCircle(
                color = rimColor,
                radius = r - 1f,
                style = Stroke(width = 2.dp.toPx()),
            )
            // Center dot: the neutral anchor (spec "merkez nötrdür") —
            // onSurfaceVariant so it reads as chrome, not a color sample.
            drawCircle(
                color = centerColor,
                radius = 2.dp.toPx(),
            )
            // The thumb at the stored offset, drawn inset from the rim so
            // a full-deflection offset stays inside the boundary. The
            // drawn thumb is the 16dp visual (the CompactSlider thumb
            // precedent); the 48dp touch target is the whole pad.
            val inset = 8.dp.toPx()
            val tx = c.x + x * (r - inset)
            val ty = c.y + y * (r - inset)
            drawCircle(
                color = thumbOutlineColor,
                radius = 9.dp.toPx(),
                center = Offset(tx, ty),
            )
            drawCircle(
                color = thumbColor,
                radius = 8.dp.toPx(),
                center = Offset(tx, ty),
            )
        }
    }
}
