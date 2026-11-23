package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.engine.RawHistogram
import com.ritim.ritimcraw.ui.theme.Tnum
import java.util.Locale

/**
 * THE SCOPE LOOK (UI modernization 2026-11-23): the shared visual
 * vocabulary of every scope surface — the dark rounded panel, the soft
 * graticule, the corner readouts and the clip pills — so the strip's
 * histogram, its waveform and the mini overlay read as one instrument.
 *
 * Scopes stay dark in every theme (grading-tool convention: traces are
 * light-on-dark and additive), but the panel tones come from the color
 * scheme's neutral surface ladder so they sit inside the editor chrome.
 */
internal object ScopeLook {
    val PanelShape = RoundedCornerShape(12.dp)
    val MiniShape = RoundedCornerShape(10.dp)

    /** Graticule levels, bottom to top (fractions of the signal range). */
    val GraticuleLevels = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)

    /** The labels drawn beside [GraticuleLevels] — percent of signal. */
    val GraticuleLabels = arrayOf("0", "25", "50", "75", "100")

    val LabelStyle = TextStyle(fontSize = 9.sp, lineHeight = 10.sp).merge(Tnum)

    /** Channel trace colors — the scope's data colors (not chrome). */
    val Red = Color(0xFFFF5A5A)
    val Green = Color(0xFF4FD67A)
    val Blue = Color(0xFF4A8DFF)
    val Luma = Color(0xFFF2F2F2)

    /**
     * The RAW (sensor-domain) clip tint — deliberately NOT the processed
     * image's error red, so the two domains' warnings can never be
     * mistaken for each other (feature N6's never-mix rule).
     */
    val RawClip = Color(0xFFFFB454)
}

/** Theme-derived scope colors, resolved once per composition. */
@Immutable
internal data class ScopeColors(
    val panel: Color,
    val border: Color,
    val grid: Color,
    val gridStrong: Color,
    val label: Color,
    val reference: Color,
    val clip: Color,
    val scrim: Color,
)

@Composable
internal fun rememberScopeColors(mini: Boolean = false): ScopeColors {
    val cs = MaterialTheme.colorScheme
    return remember(cs, mini) {
        ScopeColors(
            panel = if (mini) Color.Black.copy(alpha = 0.74f) else cs.surfaceContainerLowest,
            border = if (mini) Color.White.copy(alpha = 0.14f) else cs.outlineVariant.copy(alpha = 0.55f),
            grid = Color.White.copy(alpha = 0.07f),
            gridStrong = Color.White.copy(alpha = 0.13f),
            label = Color.White.copy(alpha = 0.42f),
            reference = Color(0xFFFFC857).copy(alpha = 0.8f),
            clip = cs.error,
            scrim = Color.Black.copy(alpha = 0.55f),
        )
    }
}

/** The graticule labels, measured once per text measurer (no per-frame layout). */
@Composable
internal fun rememberGraticuleLabels(): Array<TextLayoutResult> {
    val measurer: TextMeasurer = rememberTextMeasurer(cacheSize = 8)
    return remember(measurer) {
        Array(ScopeLook.GraticuleLabels.size) {
            measurer.measure(ScopeLook.GraticuleLabels[it], ScopeLook.LabelStyle)
        }
    }
}

/**
 * The dark rounded scope panel: background, hairline border, clip to the
 * corner radius. [content] stacks the graph and its corner readouts.
 */
@Composable
internal fun ScopePanel(
    colors: ScopeColors,
    modifier: Modifier = Modifier,
    mini: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = if (mini) ScopeLook.MiniShape else ScopeLook.PanelShape
    Box(
        modifier
            .clip(shape)
            .background(colors.panel)
            .border(1.dp, colors.border, shape),
        content = content,
    )
}

/**
 * Horizontal signal-level graticule (waveform): soft lines at 0/25/50/75/100%
 * with the 50% line a touch stronger.
 */
internal fun DrawScope.drawLevelGraticule(colors: ScopeColors) {
    val w = size.width
    val h = size.height
    val levels = ScopeLook.GraticuleLevels
    for (i in levels.indices) {
        val y = (h * (1f - levels[i])).coerceIn(0.5f, h - 0.5f)
        val color = if (i == 2) colors.gridStrong else colors.grid
        drawLine(color, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
    }
}

/**
 * The level labels (25/50/75/100) tucked just under their lines at the
 * left edge; 0 is skipped (its corner belongs to the readouts).
 */
internal fun DrawScope.drawLevelGraticuleLabels(
    colors: ScopeColors,
    labels: Array<TextLayoutResult>,
) {
    val h = size.height
    val pad = 4.dp.toPx()
    val levels = ScopeLook.GraticuleLevels
    for (i in 1 until levels.size) {
        val layout = labels[i]
        val y = (h * (1f - levels[i]) + 1.dp.toPx())
            .coerceIn(1f, h - layout.size.height - 1f)
        drawText(layout, color = colors.label, topLeft = Offset(pad, y))
    }
}

/**
 * Vertical tonal graticule (histogram): soft lines at the quarter stops,
 * the midpoint a touch stronger.
 */
internal fun DrawScope.drawTonalGraticule(colors: ScopeColors, left: Float = 0f, width: Float = size.width) {
    val h = size.height
    for (q in 1..3) {
        val x = left + width * q / 4f
        drawLine(
            if (q == 2) colors.gridStrong else colors.grid,
            Offset(x, 0f), Offset(x, h), strokeWidth = 1f,
        )
    }
}

/** A thin edge bar marking a clipped end of the tonal range. */
internal fun DrawScope.drawClipEdge(
    color: Color,
    atStart: Boolean,
    left: Float = 0f,
    width: Float = size.width,
    barWidth: Float = 1.5.dp.toPx(),
) {
    val bar = barWidth
    val x = if (atStart) left else left + width - bar
    drawRect(color, topLeft = Offset(x, 0f), size = androidx.compose.ui.geometry.Size(bar, size.height))
}

/**
 * CLIP READOUT (pure, JVM-tested): the share of samples crushed to black
 * and blown to white, as the WORST channel among those the current view
 * draws. Null for an end that stays under the pipeline's own 0.1% clip
 * threshold — the readout appears exactly when the old clip dots did,
 * now with the measured magnitude.
 */
@Immutable
internal data class ClipReadout(
    val shadowPercent: Float?,
    val highlightPercent: Float?,
    val shadowChannels: List<Int>,
    val highlightChannels: List<Int>,
) {
    val any: Boolean get() = shadowPercent != null || highlightPercent != null

    companion object {
        val NONE = ClipReadout(null, null, emptyList(), emptyList())
    }
}

internal object ScopeClipReadout {

    /** The channels a processed-histogram [mode] draws (LUMA = index 3). */
    fun channelsFor(mode: HistogramMode): List<FrameHistogram.Channel> = when (mode) {
        HistogramMode.RGB -> FrameHistogram.Channel.entries
        HistogramMode.LUMA -> listOf(FrameHistogram.Channel.LUMA)
        HistogramMode.PARADE -> listOf(
            FrameHistogram.Channel.RED, FrameHistogram.Channel.GREEN, FrameHistogram.Channel.BLUE,
        )
        HistogramMode.RAW -> emptyList()
    }

    fun of(result: FrameHistogram.Result, mode: HistogramMode): ClipReadout {
        if (result.sampled <= 0) return ClipReadout.NONE
        val channels = channelsFor(mode)
        val shadow = channels.filter { result.isBlackClipped(it) }
        val highlight = channels.filter { result.isWhiteClipped(it) }
        val total = result.sampled.toFloat()
        return ClipReadout(
            shadowPercent = shadow.maxOfOrNull { result.blackClipped(it) * 100f / total },
            highlightPercent = highlight.maxOfOrNull { result.whiteClipped(it) * 100f / total },
            shadowChannels = shadow.map { it.ordinal },
            highlightChannels = highlight.map { it.ordinal },
        )
    }

    fun ofRaw(raw: RawHistogram?): ClipReadout {
        if (raw == null) return ClipReadout.NONE
        val channels = RawHistogram.Channel.entries
        fun pct(count: Int, ch: RawHistogram.Channel): Float {
            val n = raw.samples[ch.ordinal]
            return if (n > 0) count * 100f / n else 0f
        }
        val shadow = channels.filter { raw.isBlackClipped(it) }
        val highlight = channels.filter { raw.isWhiteClipped(it) }
        return ClipReadout(
            shadowPercent = shadow.maxOfOrNull { pct(raw.blackClipped(it), it) },
            highlightPercent = highlight.maxOfOrNull { pct(raw.whiteClipped(it), it) },
            shadowChannels = shadow.map { it.ordinal },
            highlightChannels = highlight.map { it.ordinal },
        )
    }

    /** "0.4%", "12%", "<0.1%" — one decimal under 10%, none above. */
    fun format(percent: Float): String = when {
        percent < 0.1f -> "<0.1%"
        percent < 10f -> String.format(Locale.ROOT, "%.1f%%", percent)
        else -> String.format(Locale.ROOT, "%.0f%%", percent)
    }
}

/** Channel index (R, G, B, Luma) to its trace color. */
internal fun scopeChannelColor(index: Int): Color = when (index) {
    0 -> ScopeLook.Red
    1 -> ScopeLook.Green
    2 -> ScopeLook.Blue
    else -> ScopeLook.Luma
}

/**
 * The clip pills in the panel's bottom corners: shadow clip at the start
 * (▼), highlight clip at the end (▲), each with the clipped channels'
 * dots and the measured share. [description] names the warning for
 * accessibility ("Clipped", "RAW clip").
 */
@Composable
internal fun BoxScope.ClipPills(
    readout: ClipReadout,
    colors: ScopeColors,
    description: String,
    tint: Color = colors.clip,
) {
    if (!readout.any) return
    readout.shadowPercent?.let { pct ->
        ClipPill(
            arrow = "▼",
            text = ScopeClipReadout.format(pct),
            channels = readout.shadowChannels,
            colors = colors,
            tint = tint,
            description = description,
            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp),
        )
    }
    readout.highlightPercent?.let { pct ->
        ClipPill(
            arrow = "▲",
            text = ScopeClipReadout.format(pct),
            channels = readout.highlightChannels,
            colors = colors,
            tint = tint,
            description = description,
            modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
        )
    }
}

@Composable
private fun ClipPill(
    arrow: String,
    text: String,
    channels: List<Int>,
    colors: ScopeColors,
    tint: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .clip(CircleShape)
            .background(colors.scrim)
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .semantics { contentDescription = "$description $text" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        channels.forEach { ch ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(scopeChannelColor(ch)))
        }
        if (channels.isNotEmpty()) Spacer(Modifier.width(1.dp))
        Text(
            "$arrow $text",
            style = MaterialTheme.typography.labelSmall.merge(Tnum).copy(fontSize = 10.sp),
            color = tint,
            maxLines = 1,
        )
    }
}

/** A small corner caption inside the panel (signal name, Hz). */
@Composable
internal fun ScopeCaption(text: String, colors: ScopeColors, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.merge(Tnum).copy(fontSize = 10.sp),
        color = colors.label,
        maxLines = 1,
        modifier = modifier.padding(horizontal = 6.dp, vertical = 3.dp),
    )
}
