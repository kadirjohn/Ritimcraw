package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pure morph state of the thumb icon for a thumb position expressed in
 * slots: position 0 = Light (sun), 1 = System ("A"), 2 = Dark (moon),
 * fractional while the thumb travels between them. At an integer
 * position exactly one glyph is drawn, untransformed (alpha 1, scale 1,
 * rotation 0, rays full), so the rest look is the static icon.
 */
internal data class ThemeIconMorphState(
    val sunAlpha: Float,
    val sunDiscScale: Float,
    /** 1 = full rays (rest), 0 = rays fully retracted into the disc. */
    val rayExtent: Float,
    val aAlpha: Float,
    val aScale: Float,
    val aRotationDeg: Float,
    val moonAlpha: Float,
    val moonScale: Float,
    val moonRotationDeg: Float,
)

internal object ThemeIconMorph {

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Thumb left offset to slot position (0..2), clamped. Pure px math. */
    fun positionFromThumb(
        thumbXPx: Float,
        trackInsetPx: Float,
        slotWidthPx: Float,
    ): Float = ((thumbXPx - trackInsetPx) / slotWidthPx)
        .coerceIn(0f, ThemeSliderMath.MAX_INDEX.toFloat())

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun stateAt(position: Float): ThemeIconMorphState {
        val p = position.coerceIn(0f, 2f)
        // Segment 1 (sun -> A): t1 in 0..1; segment 2 (A -> moon): t2.
        val t1 = p.coerceIn(0f, 1f)
        val t2 = (p - 1f).coerceIn(0f, 1f)
        // Outgoing glyph fades out in the first 60% of its segment,
        // the incoming one fades in over the last 60%: a short overlap.
        val out1 = 1f - smoothstep(0f, 0.6f, t1)
        val in1 = smoothstep(0.4f, 1f, t1)
        val out2 = 1f - smoothstep(0f, 0.6f, t2)
        val in2 = smoothstep(0.4f, 1f, t2)
        val s1 = smoothstep(0f, 1f, t1)
        val s2 = smoothstep(0f, 1f, t2)
        return ThemeIconMorphState(
            sunAlpha = out1,
            // Rays retract first (first 55%), the disc then shrinks away.
            rayExtent = 1f - smoothstep(0f, 0.55f, t1),
            sunDiscScale = lerp(1f, 0.55f, s1),
            aAlpha = if (p <= 1f) in1 else out2,
            aScale = if (p <= 1f) lerp(0.55f, 1f, s1) else lerp(1f, 0.55f, s2),
            aRotationDeg = if (p <= 1f) lerp(-25f, 0f, s1) else lerp(0f, 25f, s2),
            moonAlpha = in2,
            moonScale = lerp(0.55f, 1f, s2),
            moonRotationDeg = lerp(-50f, 0f, s2),
        )
    }
}

private val SunDiscPath: Path by lazy {
    PathParser().parsePathString("M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0 -8z").toPath()
}
private val SunRaysPath: Path by lazy {
    PathParser().parsePathString(
        "M12 2.5v2.5M12 19v2.5M2.5 12H5M19 12h2.5M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"
    ).toPath()
}
private val APath: Path by lazy {
    PathParser().parsePathString(
        "M5.48 19.2H7.86L9.15 15.4H14.81L16.14 19.2H18.52L13.3 4.8H10.6Z" +
            "M9.78 13.57L10.58 11.19C10.94 10.09 11.38 8.59 11.94 6.53" +
            "C12.51 8.57 12.95 10.04 13.33 11.19L14.16 13.57Z"
    ).toPath().also { it.fillType = PathFillType.EvenOdd }
}
private val MoonPath: Path by lazy {
    PathParser().parsePathString("M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z").toPath()
}

private const val STROKE_W = 1.75f // viewport units, as RitimIcons
private const val RAY_INNER = 7f
private const val RAY_OUTER = 9.5f

/**
 * The thumb icon: sun, "A" and moon drawn from the same 24-unit paths
 * as RitimIcons, morphed by [position] (0..2, see [ThemeIconMorph]).
 * Rays retract into the disc, the disc shrinks into the A, the A turns
 * and shrinks into the crescent. A pure function of [position], so it
 * works while dragging and reverses cleanly.
 */
@Composable
internal fun ThemeThumbIcon(position: Float, tint: Color, modifier: Modifier) {
    val st = remember(position) { ThemeIconMorph.stateAt(position) }
    Canvas(modifier) {
        val k = size.minDimension / 24f
        val c = Offset(12f, 12f)
        val stroke = Stroke(width = STROKE_W, cap = StrokeCap.Round, join = StrokeJoin.Round)
        withTransform({ scale(k, k, pivot = Offset.Zero) }) {
            if (st.sunAlpha > 0.001f) {
                withTransform({ scale(st.sunDiscScale, st.sunDiscScale, pivot = c) }) {
                    drawPath(SunDiscPath, tint, alpha = st.sunAlpha, style = stroke)
                }
                if (st.rayExtent >= 0.999f) {
                    drawPath(SunRaysPath, tint, alpha = st.sunAlpha, style = stroke)
                } else if (st.rayExtent > 0.02f) {
                    drawRetractedRays(st.rayExtent, tint, st.sunAlpha, c)
                }
            }
            if (st.aAlpha > 0.001f) {
                withTransform({
                    rotate(st.aRotationDeg, pivot = c)
                    scale(st.aScale, st.aScale, pivot = c)
                }) { drawPath(APath, tint, alpha = st.aAlpha) }
            }
            if (st.moonAlpha > 0.001f) {
                withTransform({
                    rotate(st.moonRotationDeg, pivot = c)
                    scale(st.moonScale, st.moonScale, pivot = c)
                }) { drawPath(MoonPath, tint, alpha = st.moonAlpha, style = stroke) }
            }
        }
    }
}

/** Rays shortened toward the disc: the outer end slides from 9.5 to 7. */
private fun DrawScope.drawRetractedRays(extent: Float, tint: Color, alpha: Float, c: Offset) {
    val outer = RAY_INNER + (RAY_OUTER - RAY_INNER) * extent
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0 - 90.0)
        val dx = cos(a).toFloat()
        val dy = sin(a).toFloat()
        drawLine(
            tint,
            Offset(c.x + dx * RAY_INNER, c.y + dy * RAY_INNER),
            Offset(c.x + dx * outer, c.y + dy * outer),
            strokeWidth = STROKE_W,
            cap = StrokeCap.Round,
            alpha = alpha,
        )
    }
}
