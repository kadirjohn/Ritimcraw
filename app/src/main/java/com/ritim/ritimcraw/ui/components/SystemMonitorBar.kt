package com.ritim.ritimcraw.ui.components

import android.content.res.Configuration
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.engine.SystemStats
import com.ritim.ritimcraw.engine.SystemStatsMath
import com.ritim.ritimcraw.engine.SystemStatsSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Sampling period of the strip: once a second, only while composed. */
private const val SAMPLE_PERIOD_MS = 1000L

/** Strip height: phones get the minimum, wide (tablet) windows a bit more. */
private val STRIP_HEIGHT_COMPACT: Dp = 22.dp
private val STRIP_HEIGHT_WIDE: Dp = 26.dp

/** Below this width the strip shows percentages only; above, the detail too. */
private const val WIDE_MIN_WIDTH_DP = 600

/**
 * The top system-monitor strip (owner request 2026-11-23): GPU, CPU, RAM
 * and storage in one thin row above every screen. Samples on a background
 * dispatcher once per second ONLY while composed (hiding it from Settings
 * removes it from composition, so a hidden strip costs nothing).
 *
 * Phone widths show "LABEL ▮▮▯ NN%"; wide windows (>= 600dp — tablets,
 * unfolded foldables, landscape) add the absolute amounts (RAM used/total,
 * free storage) and a slightly taller strip so it stays legible at large
 * screen sizes. A value the device does not expose shows "—".
 */
@Composable
fun SystemMonitorBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sampler = remember { SystemStatsSampler(context) }
    var stats by remember { mutableStateOf(SystemStats()) }
    LaunchedEffect(sampler) {
        while (isActive) {
            stats = withContext(Dispatchers.IO) { sampler.sample() }
            delay(SAMPLE_PERIOD_MS)
        }
    }
    SystemMonitorBarContent(stats = stats, modifier = modifier)
}

/** Stateless body — previews and tests drive it with a fixed reading. */
@Composable
fun SystemMonitorBarContent(stats: SystemStats, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        val wide = maxWidth.value >= WIDE_MIN_WIDTH_DP
        val landscape = LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (wide) STRIP_HEIGHT_WIDE else STRIP_HEIGHT_COMPACT)
                .padding(horizontal = if (wide) 24.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (wide) {
                Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally)
            } else {
                Arrangement.SpaceBetween
            },
        ) {
            MonitorCell(
                label = "GPU",
                percent = stats.gpuPercent,
                // The app's own GPU share when the device hides GPU busy
                // (most phones) — named so the number is not misread.
                detail = if (stats.gpuIsAppShare) "app" else null,
                wide = wide,
            )
            MonitorCell(
                label = "CPU",
                percent = stats.cpuPercent,
                detail = null,
                wide = wide,
            )
            MonitorCell(
                label = "RAM",
                percent = stats.ramPercent,
                detail = if (stats.ramTotalBytes > 0L) {
                    SystemStatsMath.formatBytes(stats.ramUsedBytes) + "/" +
                        SystemStatsMath.formatBytes(stats.ramTotalBytes)
                } else {
                    null
                },
                wide = wide,
            )
            // DISK reads in GB, not a percentage (owner request 2026-11-23):
            // used GB in portrait, used/total capacity in landscape or on a
            // wide window. The meter still shows the fraction.
            MonitorCell(
                label = "DISK",
                percent = stats.storagePercent,
                detail = null,
                wide = wide,
                valueOverride = when {
                    stats.storageTotalBytes <= 0L -> null
                    wide || landscape -> SystemStatsMath.formatCapacity(
                        stats.storageUsedBytes, stats.storageTotalBytes,
                    )
                    else -> SystemStatsMath.formatGb(stats.storageUsedBytes)
                },
            )
        }
    }
}

@Composable
private fun MonitorCell(
    label: String,
    percent: Float?,
    detail: String?,
    wide: Boolean,
    valueOverride: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val fontSize = if (wide) 11.sp else 10.sp
    val valueText = valueOverride ?: SystemStatsMath.formatPercent(percent)
    val spoken = if (detail != null) "$label $valueText, $detail" else "$label $valueText"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = spoken },
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.4.sp,
            ),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
        )
        Spacer(Modifier.width(5.dp))
        Meter(
            fraction = (percent ?: 0f) / 100f,
            color = meterColor(percent, scheme.primary, scheme.tertiary, scheme.error),
            track = scheme.outlineVariant.copy(alpha = 0.5f),
            width = if (wide) 36.dp else 22.dp,
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = valueText,
            style = TextStyle(
                fontSize = fontSize,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            ),
            color = scheme.onSurface,
            maxLines = 1,
            // Fixed slot so the strip does not jitter as digits change.
            modifier = Modifier.widthIn(min = if (wide) 30.dp else 26.dp),
        )
        if (wide && detail != null) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = detail,
                style = TextStyle(fontSize = fontSize, fontFamily = FontFamily.Monospace),
                color = scheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun Meter(fraction: Float, color: Color, track: Color, width: Dp) {
    val animated by animateFloatAsState(
        targetValue = fraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 400),
        label = "meter",
    )
    Box(
        Modifier
            .width(width)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
    }
}

/** Calm under 70%, warning 70-90%, alarm above 90%. */
internal fun meterColor(percent: Float?, normal: Color, warn: Color, alarm: Color): Color = when {
    percent == null -> normal
    percent >= 90f -> alarm
    percent >= 70f -> warn
    else -> normal
}
