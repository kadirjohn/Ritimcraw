package com.ritim.ritimcraw.ui.components

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.cbrt

/** Pure Oklab helpers (Bjorn Ottosson), no dependencies. Works on sRGB Colors. */
internal object Oklab {
    private fun toLinear(c: Float): Float =
        if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private fun fromLinear(c: Float): Float {
        val x = c.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f
        else (1.055 * Math.pow(x.toDouble(), 1.0 / 2.4) - 0.055).toFloat()
    }

    /** Returns [L, a, b]. */
    fun fromColor(c: Color): FloatArray {
        val r = toLinear(c.red); val g = toLinear(c.green); val b = toLinear(c.blue)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    fun toColor(lab: FloatArray, alpha: Float = 1f): Color {
        val l = lab[0] + 0.3963377774f * lab[1] + 0.2158037573f * lab[2]
        val m = lab[0] - 0.1055613458f * lab[1] - 0.0638541728f * lab[2]
        val s = lab[0] - 0.0894841775f * lab[1] - 1.2914855480f * lab[2]
        val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
        val r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3
        val g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3
        val b = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3
        return Color(fromLinear(r), fromLinear(g), fromLinear(b), alpha.coerceIn(0f, 1f))
    }

    /** Perceptual lerp, t clamped to 0..1; exact endpoints, safe for equal colours. */
    fun lerp(a: Color, b: Color, t: Float): Color {
        val k = t.coerceIn(0f, 1f)
        if (k <= 0f) return a
        if (k >= 1f) return b
        if (a == b) return a
        val x = fromColor(a); val y = fromColor(b)
        return toColor(
            floatArrayOf(
                x[0] + (y[0] - x[0]) * k,
                x[1] + (y[1] - x[1]) * k,
                x[2] + (y[2] - x[2]) * k,
            ),
            a.alpha + (b.alpha - a.alpha) * k,
        )
    }
}

/**
 * Colour of an approaching slot icon, a pure function of the proximity
 * d = |position - slot|. The icon "charges up": inactive tint, through the
 * theme accent, arriving at exactly the thumb glyph colour.
 */
internal object SlotColorJourney {

    /** Progress (0..1) along the journey where the accent is reached. */
    const val ACCENT_AT = 0.6f

    /** 0 at rest (d >= 1), 1 once d <= NEAR; smooth, symmetric in d. */
    fun progress(d: Float): Float {
        val x = abs(d).let { if (it.isNaN()) 1f else it }
        return 1f - ThemeIconMorph.smoothstep(ThemeIconMorph.NEAR, 1f, x)
    }

    fun colorAt(d: Float, inactive: Color, accent: Color, arrival: Color): Color {
        val p = progress(d)
        return if (p < ACCENT_AT) {
            Oklab.lerp(inactive, accent, ThemeIconMorph.smoothstep(0f, ACCENT_AT, p))
        } else {
            Oklab.lerp(accent, arrival, ThemeIconMorph.smoothstep(ACCENT_AT, 1f, p))
        }
    }
}
