package com.ritim.ritimcraw.ui.editor

import kotlin.math.sqrt

/**
 * The video color waveform scope's pure computation (owner spec PLAY-10,
 * 2026-11-12): a REAL waveform — horizontal-position × signal-level pixel
 * density — NOT a line graph of per-column min/max and NOT derived from
 * the 256-bin [FrameHistogram] (the spec's critical note: the histogram
 * has no horizontal-position information; the waveform needs its own
 * density grid).
 *
 * GRID. The result is one density grid per drawn channel: [COLUMNS]
 * horizontal bins × [LEVELS] signal bins, each cell the count of sampled
 * pixels at that horizontal position and that signal level. The X axis
 * preserves horizontal image position (spec §3: a pixel's column bin is
 * derived from its TRUE x — see SAMPLING below); the Y axis is the
 * 0-100% signal level scale (spec §5: SDR initial scale).
 *
 * SIGNAL SPACE. The analyzed pixels are the PROCESSED preview frame's
 * packed 8-bit ARGB (the same array [FrameHistogram] reads): after the
 * grade and monitoring transform, after the Rec.709 OETF, clamped to
 * 8 bits — the "Preview · BT.709" signal the scope honestly labels
 * (spec §4). The luma is therefore the CODED signal luma in that space,
 * the same integer Rec.709 weights as the histogram
 *     Y = (54*R + 183*G + 19*B) >> 8
 * — never the linear luminance the OETF consumed (spec §5: do not
 * confuse the two). The per-channel traces index each channel's own
 * 8-bit value, exactly like the histogram's channel bins.
 *
 * MODES (spec §1): [WaveformMode.LUMA] — one neutral white/gray density
 * trace; [WaveformMode.OVERLAY] — the R/G/B traces stacked in one grid;
 * [WaveformMode.PARADE] — the three channels side by side. Overlay and
 * Parade share the same three channel grids; only the display splits
 * them into cells ([WaveformDisplay]). The mode selects which grids
 * this pass computes, so an open scope never pays for channels it is
 * not showing.
 *
 * SAMPLING (spec §6). Three quality tiers cap the pixels read
 * ([Sampling]); the cap affects ONLY this scope's sampling — never the
 * source, preview or export bit depth. The pass is POSITION-PRESERVING
 * — every sample is credited to the bin its TRUE position falls in,
 * never to an aggregate: a flat-array stride is rejected (it samples
 * the same columns on every row and leaves vertical stripes), and so
 * is a uniform column stride on wide frames (width 1920, stride 8
 * skips whole bins: x=112 lands in bin 14, x=120 in bin 16 — bin 15
 * would render as a permanent empty stripe). Instead the pass walks
 * the frame by rows: the row stride targets [Sampling.maxSamples] /
 * [COLUMNS] sampled rows, and each sampled row credits exactly one
 * true pixel to EVERY column bin —
 *   - width >= [COLUMNS]: the bin's left-edge pixel x = j*width/COLUMNS
 *     (the classic scope column sampling: a fixed column set, every
 *     bin covered on every row, exactly COLUMNS samples per row);
 *   - narrower frames: every pixel of the row, binned by
 *     x*COLUMNS/width (exact counts; bins between pixels stay empty,
 *     which only occurs below 256px-wide analyzed frames — the preview
 *     tiers render wider).
 * Sampled count is therefore rows × min(width, COLUMNS) ≤ the tier cap.
 *
 * PERFORMANCE (spec §7): one pass over the already-rendered preview
 * frame — no file reopen, no extra RAW render; the caller runs it off
 * the UI thread and paces it latest-wins (the screen's sampled flow).
 * The grids are [ShortArray]: the worst per-cell count is bounded by
 * the sampling cap (a degenerate 1-column frame concentrates at most
 * [Sampling.maxSamples] samples, still far inside Short range), and the
 * smaller arrays keep the per-frame allocation modest at the ~10 Hz
 * scope cadence.
 */
object WaveformScope {

    /** Horizontal grid resolution: one bin per 1/256 of the image width. */
    const val COLUMNS = 256

    /** Vertical grid resolution: one bin per 8-bit signal level. */
    const val LEVELS = 256

    /**
     * Sampling quality tiers (spec §6): the maximum pixels read per
     * frame. Scope-only — no other pipeline stage sees these values.
     */
    enum class Sampling(val maxSamples: Int) {
        LOW(16_384),
        MEDIUM(32_768),
        HIGH(65_536),
    }

    /**
     * One pass's density grids. Only the drawn channels are non-null
     * (see the object KDoc); each grid is [columns] × [levels] cells,
     * row-major by level (index = level * columns + column), the count
     * of sampled pixels at that position and signal level.
     */
    data class Result(
        val red: ShortArray?,
        val green: ShortArray?,
        val blue: ShortArray?,
        val luma: ShortArray?,
        val columns: Int,
        val levels: Int,
        /** The number of pixels actually sampled. */
        val sampled: Int,
    ) {
        /** The density grid of [channel], or null when not drawn. */
        fun grid(channel: FrameHistogram.Channel): ShortArray? = when (channel) {
            FrameHistogram.Channel.RED -> red
            FrameHistogram.Channel.GREEN -> green
            FrameHistogram.Channel.BLUE -> blue
            FrameHistogram.Channel.LUMA -> luma
        }

        /** True when nothing was sampled (null/empty frame input). */
        val isEmpty: Boolean get() = sampled == 0

        override fun equals(other: Any?): Boolean =
            other is Result && other.columns == columns && other.levels == levels &&
                other.sampled == sampled &&
                other.red.contentEquals(red) && other.green.contentEquals(green) &&
                other.blue.contentEquals(blue) && other.luma.contentEquals(luma)

        override fun hashCode(): Int {
            var hash = columns
            hash = 31 * hash + levels
            hash = 31 * hash + sampled
            hash = 31 * hash + (red?.contentHashCode() ?: 0)
            hash = 31 * hash + (green?.contentHashCode() ?: 0)
            hash = 31 * hash + (blue?.contentHashCode() ?: 0)
            hash = 31 * hash + (luma?.contentHashCode() ?: 0)
            return hash
        }

        companion object {
            /** The result for a null/empty frame: all grids null, nothing drawn. */
            val EMPTY = Result(null, null, null, null, COLUMNS, LEVELS, 0)
        }
    }

    /**
     * One position-preserving pass over [argb] (packed 0xAARRGGBB,
     * row-major, [width] × [height]): the mode's density grids. Null,
     * empty or degenerate input yields [Result.EMPTY] — the scope draws
     * nothing rather than crashing.
     */
    fun of(
        argb: IntArray?,
        width: Int,
        height: Int,
        mode: WaveformMode,
        sampling: Sampling,
    ): Result {
        if (argb == null || argb.isEmpty() || width <= 0 || height <= 0) {
            return Result.EMPTY
        }
        val wantLuma = mode == WaveformMode.LUMA
        val lumaGrid = if (wantLuma) ShortArray(COLUMNS * LEVELS) else null
        val redGrid = if (!wantLuma) ShortArray(COLUMNS * LEVELS) else null
        val greenGrid = if (!wantLuma) ShortArray(COLUMNS * LEVELS) else null
        val blueGrid = if (!wantLuma) ShortArray(COLUMNS * LEVELS) else null

        // The sampling walk (see the object KDoc SAMPLING). The row stride
        // targets maxSamples/COLUMNS sampled rows; each sampled row
        // credits one true pixel to every column bin (wide frames: the
        // bins' left-edge columns; narrow frames: every pixel).
        val targetRows = (sampling.maxSamples / COLUMNS).coerceAtLeast(1)
        val rowStride = ((height + targetRows - 1) / targetRows).coerceAtLeast(1)

        var sampled = 0
        var y = 0
        while (y < height) {
            val rowBase = y * width
            if (width >= COLUMNS) {
                // One true pixel per column bin: the bin's left-edge
                // column — every bin covered on every sampled row.
                for (j in 0 until COLUMNS) {
                    val p = argb[rowBase + j * width / COLUMNS]
                    // The alpha byte is ignored: the renderer writes
                    // opaque pixels.
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (wantLuma) {
                        // Rec.709 coded signal luma — the same integer
                        // weights as FrameHistogram (SIGNAL SPACE above).
                        // (Parenthesized: infix shr binds looser than *.)
                        lumaGrid!![((54 * r + 183 * g + 19 * b) shr 8) * COLUMNS + j]++
                    } else {
                        redGrid!![r * COLUMNS + j]++
                        greenGrid!![g * COLUMNS + j]++
                        blueGrid!![b * COLUMNS + j]++
                    }
                }
                sampled += COLUMNS
            } else {
                // Narrow frame: every pixel of the row, binned by true x.
                for (x in 0 until width) {
                    val p = argb[rowBase + x]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    val column = x * COLUMNS / width
                    if (wantLuma) {
                        lumaGrid!![((54 * r + 183 * g + 19 * b) shr 8) * COLUMNS + column]++
                    } else {
                        redGrid!![r * COLUMNS + column]++
                        greenGrid!![g * COLUMNS + column]++
                        blueGrid!![b * COLUMNS + column]++
                    }
                }
                sampled += width
            }
            y += rowStride
        }
        return Result(redGrid, greenGrid, blueGrid, lumaGrid, COLUMNS, LEVELS, sampled)
    }
}

/**
 * The waveform's display modes (spec §1). The labels are literal
 * untranslated codes in every locale (the owner spec's product
 * vocabulary, like the histogram's RGB/Luma/Parade and ACES/AgX).
 */
enum class WaveformMode(val label: String) {
    LUMA("Luma"),
    OVERLAY("Overlay"),
    PARADE("Parade"),
    ;

    /** The channels this mode draws (and [WaveformScope.of] computes). */
    val channels: List<FrameHistogram.Channel>
        get() = when (this) {
            LUMA -> listOf(FrameHistogram.Channel.LUMA)
            OVERLAY, PARADE -> listOf(
                FrameHistogram.Channel.RED,
                FrameHistogram.Channel.GREEN,
                FrameHistogram.Channel.BLUE,
            )
        }
}

/**
 * The waveform's pure display render: density grids → premultiplied
 * ARGB pixels ready for a bitmap (the strip wraps them in an
 * ImageBitmap off the UI thread and draws it scaled — one draw call,
 * no per-cell canvas rectangles). JVM-testable without Compose or
 * android.graphics.
 *
 * DENSITY → INTENSITY. A waveform trace reads by pixel density, so the
 * per-cell intensity is the cell's count against the frame's PEAK cell
 * count under a square root — the sparse traces of a mostly-empty
 * column stay visible while the dense rows saturate, and the curve is
 * monotone (no density is ever created or hidden). The peak is ONE
 * common denominator across the mode's channels (the histogram's
 * common-scale rule: per-channel scaling would equalize the traces and
 * lie about relative levels).
 *
 * CHANNEL COLORS ARE LITERAL, NOT ROLES — the same documented exception
 * as the histogram overlay (a channel's color IS its meaning; there is
 * no theme role for "red channel"). Luma draws as neutral white (spec
 * §3: a neutral white/gray density trace); Overlay composites the R/G/B
 * contributions per channel so overlapping density converges toward
 * white, like light; Parade gives each third of the width to one
 * channel.
 *
 * PREMULTIPLIED. The pixels are written premultiplied (each channel ≤
 * alpha) because android.graphics bitmaps treat their buffer as
 * premultiplied by default; writing straight-alpha ints would render
 * with color bleeding. Empty cells are fully transparent, so the scope
 * background (gridlines, reference lines) shows through.
 *
 * Y AXIS. Bitmap row 0 is the TOP, the 100% signal end; the grid is
 * level-major (index = level * columns + column), so a cell at level L
 * lands on bitmap row levels-1-L — the waveform convention: signal
 * rises upward (spec §5).
 */
object WaveformDisplay {

    /** One rendered frame: premultiplied ARGB pixels, [width] × [height]. */
    data class Rendered(val pixels: IntArray, val width: Int, val height: Int)

    /**
     * The parade cell width: floor([WaveformScope.COLUMNS] / 3) display
     * columns per channel cell (85; the 256th column is dropped — the
     * canvas scales the bitmap to the graph, so a one-column shortfall
     * is invisible and the cells keep an exact integer width).
     */
    val PARADE_CELL_COLUMNS = WaveformScope.COLUMNS / 3

    /**
     * The display intensity 0..255 for one cell's count against the
     * frame's [peak] count: sqrt-scaled (see the KDoc), monotone, 0 for
     * empty cells and any degenerate peak.
     */
    fun intensity(count: Int, peak: Int): Int {
        if (count <= 0 || peak <= 0) return 0
        val d = count.toFloat() / peak
        return (255f * sqrt(d.coerceAtMost(1f))).toInt().coerceIn(0, 255)
    }

    /**
     * Renders [result] for [mode] into premultiplied ARGB pixels
     * (Luma/Overlay: [WaveformScope.COLUMNS] wide; Parade: three cells
     * side by side, [PARADE_CELL_COLUMNS] each). An empty result renders
     * fully transparent pixels.
     */
    fun render(result: WaveformScope.Result, mode: WaveformMode): Rendered {
        val columns = result.columns
        val levels = result.levels
        return when (mode) {
            WaveformMode.LUMA -> {
                val out = IntArray(columns * levels)
                val grid = result.luma
                if (grid != null && !result.isEmpty) {
                    val peak = peak(grid)
                    for (level in 0 until levels) {
                        val row = (levels - 1 - level) * columns
                        val base = level * columns
                        for (column in 0 until columns) {
                            val a = intensity(grid[base + column].toInt(), peak)
                            if (a > 0) {
                                // Neutral white at density alpha, premultiplied.
                                out[row + column] = (a shl 24) or (a shl 16) or (a shl 8) or a
                            }
                        }
                    }
                }
                Rendered(out, columns, levels)
            }
            WaveformMode.OVERLAY -> {
                val out = IntArray(columns * levels)
                if (!result.isEmpty) {
                    val red = result.red
                    val green = result.green
                    val blue = result.blue
                    if (red != null && green != null && blue != null) {
                        val peak = maxOf(peak(red), peak(green), peak(blue))
                        for (level in 0 until levels) {
                            val row = (levels - 1 - level) * columns
                            val base = level * columns
                            for (column in 0 until columns) {
                                val ar = intensity(red[base + column].toInt(), peak)
                                val ag = intensity(green[base + column].toInt(), peak)
                                val ab = intensity(blue[base + column].toInt(), peak)
                                val a = maxOf(ar, ag, ab)
                                if (a > 0) {
                                    // Additive light: each channel contributes its
                                    // own density; overlap converges to white.
                                    out[row + column] = (a shl 24) or (ar shl 16) or (ag shl 8) or ab
                                }
                            }
                        }
                    }
                }
                Rendered(out, columns, levels)
            }
            WaveformMode.PARADE -> {
                val cell = PARADE_CELL_COLUMNS
                val out = IntArray(cell * 3 * levels)
                if (!result.isEmpty) {
                    val grids = listOf(result.red, result.green, result.blue)
                    val peak = grids.maxOfOrNull { it?.let(::peak) ?: 0 } ?: 0
                    if (peak > 0) {
                        for ((index, grid) in grids.withIndex()) {
                            if (grid == null) continue
                            val cellBase = index * cell
                            for (level in 0 until levels) {
                                val row = (levels - 1 - level) * (cell * 3)
                                val base = level * columns
                                for (j in 0 until cell) {
                                    // Max-pool the grid columns this display column
                                    // covers, so narrowing into the cell keeps the
                                    // densest trace rather than an average.
                                    val from = j * columns / cell
                                    val to = ((j + 1) * columns - 1) / cell
                                    var count = 0
                                    for (gc in from..to) {
                                        val c = grid[base + gc].toInt()
                                        if (c > count) count = c
                                    }
                                    val a = intensity(count, peak)
                                    if (a > 0) {
                                        val color = ChannelIdentityColors.argb[index]
                                        val r = (color shr 16) and 0xFF
                                        val g = (color shr 8) and 0xFF
                                        val b = color and 0xFF
                                        out[row + cellBase + j] =
                                            (a shl 24) or
                                                (r * a / 255 shl 16) or
                                                (g * a / 255 shl 8) or
                                                (b * a / 255)
                                    }
                                }
                            }
                        }
                    }
                }
                Rendered(out, cell * 3, levels)
            }
        }
    }

    /** The largest cell count in [grid]. */
    private fun peak(grid: ShortArray): Int {
        var peak = 0
        for (v in grid) {
            val c = v.toInt()
            if (c > peak) peak = c
        }
        return peak
    }
}

/**
 * The R/G/B channel identity colors, packed opaque ARGB — the same
 * RapidRAW-studied values as the histogram overlay's documented
 * roles-never-hex exception (see HistogramOverlay's KDoc): a channel's
 * color IS its meaning. Packed ints (not Compose Color) so the pure
 * render stays JVM-testable without Compose.
 */
internal object ChannelIdentityColors {
    val argb = intArrayOf(0xFFFF6B6B.toInt(), 0xFF6BCB77.toInt(), 0xFF4D96FF.toInt())
}
