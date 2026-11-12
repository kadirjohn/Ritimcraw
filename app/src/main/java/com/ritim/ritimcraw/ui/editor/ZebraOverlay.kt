package com.ritim.ritimcraw.ui.editor

/**
 * THE ZEBRA OVERLAY (owner spec PLAY-11): the clip warning painted INTO a
 * derived copy of the displayed frame's pixels, so the stripes ride the
 * preview's exact zoom/pan/rotation transform (the transform lives inside
 * PreviewSurface's graphicsLayer on the image — a sibling canvas could not
 * track it, but stripes baked into the pixels always match).
 *
 * DISPLAY-REFERRED THRESHOLD (spec PLAY-11: "sensor white level ve
 * görüntüleme çıkışı için ayrı eşikler önerilir" — separate thresholds
 * are suggested for the sensor white level and the display output). This
 * pass is the DISPLAY OUTPUT one: a pixel is over-threshold when its
 * Rec.709 luma is >= [FrameHistogram.WHITE_CLIP_VALUE] — the SAME
 * definition the histogram's clip pills use ([FrameHistogram.luma] is the
 * one shared function), so the zebra and the pills can never disagree.
 * The SENSOR-domain threshold is deliberately deferred: it belongs to the
 * RAW-histogram domain (feature N6's sensor stats), where the pre-develop
 * sensor white level is known — painting sensor-domain stripes over the
 * developed display signal would mix two domains in one overlay.
 *
 * STRIPES: the classic 45-degree diagonal zebra — a pixel is striped when
 * `((x + y) / STRIPE_PERIOD) is even` — blended toward BLACK by
 * [STRIPE_ALPHA]/255. Dark, not light: the striped pixels are bright by
 * definition (their luma is at/above the clip value), so a white stripe
 * would vanish over exactly the areas it must flag — the dark diagonal is
 * the classic camera zebra, visible over any blown footage while the
 * clear gap positions keep the footage's own pixels (the warning stays
 * readable as a pattern, not a mask). "Neutral styling" (DESIGN.md) is
 * honored structurally: the pattern is pixel-space contrast (bright
 * footage, dark stripe), not a themed color, and the toggle that reveals
 * it is the standard scrimmed neutral corner control.
 *
 * PURE DECISIONS ONLY (JVM-testable, no Compose): [isOverThreshold] (the
 * clip decision) and [stripeAt] (the stripe pattern). The painting pass
 * [paint] is also pure — pixels in, pixels out — so the device test (the
 * owner's) only verifies the composable wiring: toggle visible, stripes
 * over a blown sky, none over a mid-gray wall, toggle off restores the
 * clean frame.
 *
 * OFF BY DEFAULT: the screen's saveable flag starts false (spec: a
 * preview-affecting tool is opt-in, like the scope strip).
 */
object ZebraOverlay {

    /**
     * The 45-degree stripe period in pixels: two bands (one striped, one
     * clear) span 2 * this many pixels along the x+y diagonal. 12px per
     * band-pair reads as a zebra at preview scale without turning blown
     * areas into noise.
     */
    const val STRIPE_PERIOD = 12

    /**
     * The stripe's strength: 0..255, the fraction of BLACK blended into a
     * striped pixel. Semi-transparent so the clip warning flags the area
     * while the underlying footage stays readable through it (the classic
     * zebra is a warning, not a mask).
     */
    const val STRIPE_ALPHA = 200

    /**
     * The clip decision for one pixel (the zebra's whole threshold
     * logic): true when the pixel's Rec.709 luma is at or above the
     * display-referred clip value — [FrameHistogram.WHITE_CLIP_VALUE], the
     * same threshold the histogram's clip pills use. A null/empty frame
     * clips nothing.
     */
    fun isOverThreshold(r: Int, g: Int, b: Int): Boolean =
        FrameHistogram.luma(r, g, b) >= FrameHistogram.WHITE_CLIP_VALUE

    /**
     * The stripe pattern at (x, y): true when this position of a clipped
     * pixel is striped (a 45-degree diagonal, period [STRIPE_PERIOD] —
     * `((x + y) / STRIPE_PERIOD) is even`). Pure position math; the test
     * pins the diagonal direction and the period's alternation.
     */
    fun stripeAt(x: Int, y: Int): Boolean =
        (((x + y) / STRIPE_PERIOD) and 1) == 0

    /**
     * Paints zebra stripes over [argb] (packed 0xAARRGGBB, row-major,
     * [width] x [height]) and returns a NEW array (the input is never
     * modified — the scope compute keeps analyzing the CLEAN frame, only
     * the display swaps to the striped copy). A clipped pixel's striped
     * positions are blended toward BLACK by [STRIPE_ALPHA]/255 — the
     * compositing happens IN the pixel values (the preview bitmap is
     * opaque; a translucent bitmap pixel would blend against the
     * background, not the footage); every other pixel, clipped or not,
     * is copied verbatim.
     *
     * This is the whole per-frame cost: one pass, int arithmetic, no
     * allocation beyond the output array. The screen runs it off the UI
     * thread (the scope compute's pattern) whenever the zebra toggle is on
     * and a frame publishes.
     */
    fun paint(argb: IntArray?, width: Int, height: Int): IntArray? {
        if (argb == null || width <= 0 || height <= 0 ||
            argb.size < width * height
        ) {
            return null
        }
        val out = argb.copyOf()
        // The blend coefficients: black at STRIPE_ALPHA, the pixel at the
        // complement — hoisted out of the loop (constants).
        val a = STRIPE_ALPHA
        val inv = 255 - a
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                val i = row + x
                val p = out[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (isOverThreshold(r, g, b) && stripeAt(x, y)) {
                    // Black blended over the pixel's own color: the
                    // stripe reads as a warning while the footage under
                    // it stays visible (STRIPE_ALPHA out of 255).
                    val rr = (r * inv) / 255
                    val gg = (g * inv) / 255
                    val bb = (b * inv) / 255
                    out[i] = (p and 0xFF000000.toInt()) or (rr shl 16) or (gg shl 8) or bb
                }
                x++
            }
            y++
        }
        return out
    }
}
