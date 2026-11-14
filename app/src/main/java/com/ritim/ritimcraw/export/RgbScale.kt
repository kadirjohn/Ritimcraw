package com.ritim.ritimcraw.export

/**
 * Pure-Kotlin RGB888 aspect-fill scaler (JVM-testable, no Android imports).
 *
 * WHY THIS EXISTS (the timeline export's frame-size reconciliation, v1):
 * [com.ritim.ritimcraw.data.timeline.TimelineExportPlan] fixes the output
 * frame size (even-snapped TimelineOutput.width/height), while the shared
 * export session renders each source FULL-SIZE and sensor-oriented — the
 * render path has no scale parameters, RotationState only rotates, and
 * CropState only crops; nothing in the codebase SCALES. The timeline has
 * no composite to match either. So the timeline exporter reconciles sizes
 * itself, and the honest v1 choice is this file:
 *
 *  - UPRIGHT ROTATION happens first (RotationState, per-source
 *    orientationDeg — outside this file).
 *  - Then this scaler does ASPECT-FILL CENTER-CROP to the output frame:
 *    scale by max(outW/inW, outH/inH) — the smallest scale that COVERS the
 *    output — then crop the overflow symmetrically. Nothing stretches:
 *    a 4:3 source in a 16:9 output letterboxes-by-cropping (top/bottom
 *    of the source are kept, left/right overflow is cropped); a 16:9
 *    source in a 4:3 output crops the sides. Content is never distorted
 *    and never edge-replicated; a different framing policy (fit with
 *    bars, pan) is a later milestone's choice, not v1's.
 *
 * ALGORITHM: bilinear interpolation over the source with a
 * center-of-pixel mapping — output pixel (x, y) samples source pixel
 * center
 *
 *   sx = (x + 0.5) * inW / outW - 0.5
 *
 * (the classic inverse mapping with half-pixel centers, so a same-size
 * pass is the identity and up/downscale both land on real samples).
 * Per-channel linear blend of the 2x2 neighborhood, clamped at the
 * edges. Float math per pixel, Int packing back to RGB888 — this is a
 * CPU path on the export thread, sized by the output frame, and simple
 * beats clever for a first export (the same reasoning as the plan's
 * frame-nearest mapping: no hidden resampler, one honest choice).
 */
object RgbScale {

    /**
     * Aspect-fill [rgb] (RGB888, [inW]*[inH]*3, row-major top-row-first)
     * into a new [outW]*[outH] RGB888 buffer, center-cropping the overflow.
     * [inW] == [outW] and [inH] == [outH] returns the input by reference
     * (the identity fast path — no allocation, no copy).
     */
    fun aspectFill(rgb: ByteArray, inW: Int, inH: Int, outW: Int, outH: Int): ByteArray {
        require(inW > 0 && inH > 0) { "input $inW x $inH is empty" }
        require(outW > 0 && outH > 0) { "output $outW x $outH is empty" }
        if (inW == outW && inH == outH) return rgb

        // Cover scale: the smallest uniform scale that covers the output.
        val scale = maxOf(outW.toDouble() / inW, outH.toDouble() / inH)
        // The scaled size and the symmetric crop window inside it.
        val scaledW = outW / scale
        val scaledH = outH / scale
        return bilinear(
            rgb, inW, inH, outW, outH,
            (inW - scaledW) / 2.0, (inH - scaledH) / 2.0, scaledW, scaledH,
        )
    }

    /**
     * Aspect-PRESERVING fit (the export resolution picker, owner request
     * 2026-11-13): scales [rgb] by the largest uniform scale that fits
     * WITHIN [outW]*[outH] — min(outW/inW, outH/inH) — filling the
     * leftover with symmetric black bars. Nothing stretches, nothing
     * crops: a 4:3 source into a 16:9 box keeps every source pixel and
     * letterboxes the sides. The export resolution rules
     * ([ExportResolution.outputSize]) resolve the bound to the source's
     * OWN aspect BEFORE this runs, so the honest export path takes the
     * no-bar branch (the fit is exact); this function exists beside
     * [aspectFill] so the SCALED RESIZE ITSELF is aspect-preserving by
     * construction, never a center-crop — the size decision and the
     * framing decision stay separate (the audit's 4:3 rule). Identity
     * fast path same as aspectFill.
     */
    fun aspectFit(rgb: ByteArray, inW: Int, inH: Int, outW: Int, outH: Int): ByteArray {
        require(inW > 0 && inH > 0) { "input $inW x $inH is empty" }
        require(outW > 0 && outH > 0) { "output $outW x $outH is empty" }
        if (inW == outW && inH == outH) return rgb

        // Fit scale: the largest uniform scale that fits inside the output.
        val scale = minOf(outW.toDouble() / inW, outH.toDouble() / inH)
        val scaledW = inW * scale
        val scaledH = inH * scale
        val out = ByteArray(outW * outH * 3) // zero-filled = black bars
        val barX = (outW - scaledW) / 2.0
        val barY = (outH - scaledH) / 2.0
        var oi = 0
        for (y in 0 until outH) {
            val inY = y >= ((outH - scaledH) / 2.0).toInt() &&
                y < (((outH - scaledH) / 2.0) + scaledH).toInt()
            if (!inY) {
                oi += outW * 3 // whole black row
                continue
            }
            val sy = (y + 0.5 - barY) / scale - 0.5
            val y0 = sy.toInt().coerceIn(0, inH - 1)
            val y1 = (y0 + 1).coerceIn(0, inH - 1)
            val fy = (sy - y0).coerceIn(0.0, 1.0)
            val row0 = y0 * inW * 3
            val row1 = y1 * inW * 3
            for (x in 0 until outW) {
                val sx = (x + 0.5 - barX) / scale - 0.5
                val inX = sx >= -0.5 && sx <= inW - 0.5
                if (!inX) {
                    oi += 3 // black bar pixel
                    continue
                }
                val x0 = sx.toInt().coerceIn(0, inW - 1)
                val x1 = (x0 + 1).coerceIn(0, inW - 1)
                val fx = (sx - x0).coerceIn(0.0, 1.0)
                val p00 = row0 + x0 * 3
                val p01 = row0 + x1 * 3
                val p10 = row1 + x0 * 3
                val p11 = row1 + x1 * 3
                out[oi++] = blend(rgb, p00, p01, p10, p11, fx, fy)
                out[oi++] = blend(rgb, p00 + 1, p01 + 1, p10 + 1, p11 + 1, fx, fy)
                out[oi++] = blend(rgb, p00 + 2, p01 + 2, p10 + 2, p11 + 2, fx, fy)
            }
        }
        return out
    }

    /**
     * The bilinear core aspectFill shares (the same center-of-pixel
     * mapping, unsigned reads, clamp-to-edge): [cropX]/[cropY] is the
     * symmetric crop window's top-left inside the source, [scaledW]/
     * [scaledH] the window's size.
     */
    private fun bilinear(
        rgb: ByteArray,
        inW: Int, inH: Int,
        outW: Int, outH: Int,
        cropX: Double, cropY: Double,
        scaledW: Double, scaledH: Double,
    ): ByteArray {
        val out = ByteArray(outW * outH * 3)
        var oi = 0
        for (y in 0 until outH) {
            // Output row y samples the source row at cropY + (y+0.5)*step.
            val sy = cropY + (y + 0.5) * scaledH / outH - 0.5
            val y0 = sy.toInt().coerceIn(0, inH - 1)
            val y1 = (y0 + 1).coerceIn(0, inH - 1)
            val fy = (sy - y0).coerceIn(0.0, 1.0)
            val row0 = y0 * inW * 3
            val row1 = y1 * inW * 3
            for (x in 0 until outW) {
                val sx = cropX + (x + 0.5) * scaledW / outW - 0.5
                val x0 = sx.toInt().coerceIn(0, inW - 1)
                val x1 = (x0 + 1).coerceIn(0, inW - 1)
                val fx = (sx - x0).coerceIn(0.0, 1.0)
                val p00 = row0 + x0 * 3
                val p01 = row0 + x1 * 3
                val p10 = row1 + x0 * 3
                val p11 = row1 + x1 * 3
                out[oi++] = blend(rgb, p00, p01, p10, p11, fx, fy)
                out[oi++] = blend(rgb, p00 + 1, p01 + 1, p10 + 1, p11 + 1, fx, fy)
                out[oi++] = blend(rgb, p00 + 2, p01 + 2, p10 + 2, p11 + 2, fx, fy)
            }
        }
        return out
    }

    /**
     * Bilinear blend of one channel across the 2x2 neighborhood. The
     * reads are UNSIGNED (Byte and 0xFF): RGB888 bytes hold 0..255, but
     * Kotlin's Byte is signed — an unmasked read turns every value above
     * 127 negative, and the clamp below would coerce it to 0 (a bright
     * pixel would blend to BLACK).
     */
    private fun blend(
        rgb: ByteArray,
        p00: Int, p01: Int, p10: Int, p11: Int,
        fx: Double, fy: Double,
    ): Byte {
        val v00 = rgb[p00].toInt() and 0xFF
        val v01 = rgb[p01].toInt() and 0xFF
        val v10 = rgb[p10].toInt() and 0xFF
        val v11 = rgb[p11].toInt() and 0xFF
        val top = v00 + (v01 - v00) * fx
        val bottom = v10 + (v11 - v10) * fx
        val v = top + (bottom - top) * fy
        return v.toInt().coerceIn(0, 255).toByte()
    }
}
