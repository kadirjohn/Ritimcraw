package com.ritim.ritimcraw.data

/**
 * THE single rotation decision object (owner request 2026-11-11 section 5:
 * "tek kaynak" — one source of truth for orientation). Every consumer —
 * the preview display, the MP4 export pixels, the DNG Orientation tag, the
 * photo export pixels — composes its rotation through THIS object, so the
 * four can never disagree about which way is up.
 *
 * Two inputs, one output:
 *  - CONTAINER orientation: what the source itself declares — MCRAW's
 *    sensorOrientation (per-frame `orientation` key overriding the
 *    container, Source.cpp frameInfo) or, for encoded clips, the
 *    MediaExtractor KEY_ROTATION the develop tail applies. Whatever the
 *    open session reports as `orientationDeg` (0/90/180/270, degrees CW
 *    to upright).
 *  - MANUAL delta: the user's per-clip rotate taps (±90° steps, persisted
 *    on the MediaSource record as `manualRotationDeg`), normalized to
 *    0..359 so it never grows unbounded.
 *
 * The effective orientation is simply their sum, normalized. The split
 * matters because the native develop ALREADY rotates preview/photo pixels
 * upright by the container value: those paths apply only the MANUAL delta
 * on top of upright pixels (never a double rotation), while the export
 * paths that receive NOT-rotated pixels (MP4 renderFrame) apply the full
 * effective value. Which of the two a caller needs is documented per
 * function below.
 *
 * Pure math only — no Android imports, JVM-testable.
 */
object RotationState {

    /** Any degree value normalized to 0..359 (negative-friendly). */
    fun normalize(deg: Int): Int = ((deg % 360) + 360) % 360

    /**
     * The clip's EFFECTIVE orientation: container orientation plus the
     * user's manual delta, normalized. Callers that hold NOT-rotated
     * pixels (the MP4 export's renderFrame output, the DNG Orientation
     * tag) rotate by THIS; callers that hold already-upright pixels (the
     * preview display, the photo export — both rotated by the native
     * develop) must use only the manual delta instead, or they would
     * rotate twice.
     */
    fun effectiveDeg(containerDeg: Int, manualDeltaDeg: Int): Int =
        normalize(containerDeg + manualDeltaDeg)

    /**
     * The next persisted manual delta after a rotate tap ([byDeg] is ±90
     * from the sheet's two rows): deltas accumulate but stay normalized,
     * so four taps in one direction return to 0 and project.json never
     * holds 360 or -90 (the omit-when-default render then writes nothing
     * again, exactly like the untouched clip).
     */
    fun applyManual(currentDeltaDeg: Int, byDeg: Int): Int =
        normalize(currentDeltaDeg + byDeg)

    /**
     * True for quarter turns: rotating by [deg] swaps width and height.
     * Consumers that pre-rotate pixels (MP4) or re-shape a display
     * surface (preview) need this to pick the swapped dimensions.
     */
    fun swapsDimensions(deg: Int): Boolean = normalize(deg) % 180 == 90

    /**
     * The uniform display scale a quarter-turned Image needs to FILL its
     * box (owner request 2026-11-11 section 5, preview display). The
     * preview box is shaped by the ratio of the ALREADY-rotated content
     * (the native develop renders container-upright pixels; the display
     * turns only the manual delta), so a 90°/270° delta puts
     * portrait-shaped pixels into a landscape-shaped box (or the
     * reverse): ContentScale.Fit letterboxes them. Rotating the fitted
     * content by a quarter turn maps its occupied w×h onto h×w, so the
     * scale that covers the box is the ratio between the content's long
     * and short sides, [contentRatio] being width/height of the UPRIGHT
     * frame. When the box is exactly the swapped content ratio (the
     * steady state) this fills EXACTLY — no bars, no crop, no
     * distortion. Half turns and 0 need no scale (1f).
     */
    fun quarterTurnDisplayScale(contentRatio: Float, manualDeltaDeg: Int): Float =
        if (swapsDimensions(manualDeltaDeg)) maxOf(contentRatio, 1f / contentRatio) else 1f

    /**
     * Dimensions after rotating a [width] x [height] buffer by [deg]
     * (either an effective value or a manual delta — the swap math is the
     * same). Non-positive inputs pass through unchanged (the ratio chain
     * upstream treats them as unknown).
     */
    fun rotatedDims(width: Int, height: Int, deg: Int): Dims =
        if (width > 0 && height > 0 && swapsDimensions(deg)) {
            Dims(height, width)
        } else {
            Dims(width, height)
        }

    /**
     * The DNG/TIFF Orientation tag (274) for an effective rotation — the
     * JVM mirror of core/src/DngWriter.cpp tiffOrientation, kept here so
     * the mapping is JVM-tested and the Kotlin layer can reason about
     * what the writer will emit. The DNG itself keeps the CFA unrotated;
     * only this tag tells the reader which way is up.
     */
    fun dngOrientationTag(effectiveDeg: Int): Int = when (normalize(effectiveDeg)) {
        90 -> 6
        180 -> 3
        270 -> 8
        else -> 1
    }

    /**
     * Rotates a row-major RGB888 buffer (3 bytes per pixel — the native
     * renderFrame layout) by [deg], the same CW convention as
     * PhotoPixels.rotate: applying an EFFECTIVE orientation to
     * NOT-rotated pixels yields upright ones. The MP4 export uses this
     * between renderFrame and the I420 conversion (the encoder wants
     * upright pixels, not a muxer hint — owner request 2026-11-11
     * section 5). Throws IllegalArgumentException when the buffer does
     * not hold exactly width*height*3 bytes.
     */
    fun rotateRgb888(rgb: ByteArray, width: Int, height: Int, deg: Int): Rgb888 {
        require(width > 0 && height > 0) {
            "frame must have positive dimensions, got ${width}x$height"
        }
        require(rgb.size == width * height * 3) {
            "rgb buffer holds ${rgb.size} bytes, expected ${width * height * 3} " +
                "(${width}x${height} RGB888)"
        }
        return when (normalize(deg)) {
            0 -> Rgb888(rgb, width, height)
            180 -> {
                val out = ByteArray(rgb.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val src = (y * width + x) * 3
                        val dst = ((height - 1 - y) * width + (width - 1 - x)) * 3
                        out[dst] = rgb[src]
                        out[dst + 1] = rgb[src + 1]
                        out[dst + 2] = rgb[src + 2]
                    }
                }
                Rgb888(out, width, height)
            }
            90 -> {
                // A 90-degree CW turn: the source's last column becomes
                // the output's first row (axis swap — PhotoPixels.rotate's
                // index math, three bytes per pixel).
                val out = ByteArray(rgb.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val src = (y * width + x) * 3
                        val dst = (x * height + (height - 1 - y)) * 3
                        out[dst] = rgb[src]
                        out[dst + 1] = rgb[src + 1]
                        out[dst + 2] = rgb[src + 2]
                    }
                }
                Rgb888(out, height, width)
            }
            270 -> {
                val out = ByteArray(rgb.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val src = (y * width + x) * 3
                        val dst = ((width - 1 - x) * height + y) * 3
                        out[dst] = rgb[src]
                        out[dst + 1] = rgb[src + 1]
                        out[dst + 2] = rgb[src + 2]
                    }
                }
                Rgb888(out, height, width)
            }
            else -> Rgb888(rgb, width, height)
        }
    }

    /** A width/height pair after rotation. */
    data class Dims(val width: Int, val height: Int)

    /** A rotated RGB888 buffer with its post-rotation dimensions. */
    data class Rgb888(val rgb: ByteArray, val width: Int, val height: Int)
}
