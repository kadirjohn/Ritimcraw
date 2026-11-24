package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.BitrateLevel
import com.ritim.ritimcraw.export.EncoderFacts
import com.ritim.ritimcraw.export.ExportCodec
import com.ritim.ritimcraw.export.ExportIssue
import com.ritim.ritimcraw.export.ExportPlan
import com.ritim.ritimcraw.export.ExportPreset
import com.ritim.ritimcraw.export.ExportRateMode
import com.ritim.ritimcraw.export.ExportSettings
import com.ritim.ritimcraw.export.ExportSheetModel
import com.ritim.ritimcraw.export.ExportText
import com.ritim.ritimcraw.export.KeyframeInterval
import com.ritim.ritimcraw.ui.components.AppSegmentedControl
import com.ritim.ritimcraw.ui.theme.Tnum
import java.util.Locale

/**
 * The MP4 settings block of the export sheet (plan 7.3-7.5, E1c): preset
 * chips, the "what will really be produced" summary with size and time
 * estimates, the plan's issues with their fix buttons, and a collapsed
 * Advanced section. Stateless but for the Advanced toggle and the help
 * tooltips: the settings and the plan come from the sheet, which owns them,
 * so the summary can never disagree with what the Export button queues.
 *
 * Frame rate, sample rate and channel count are read-only on purpose
 * (owner decision 2026-11-23): the clip's own rate and the source audio
 * format are carried through; a picker plus resampling is a later package.
 *
 * Every title can be long-pressed for a short explanation (a plain M3
 * tooltip: long press is its stock trigger).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportSettingsPanel(
    settings: ExportSettings,
    onSettings: (ExportSettings) -> Unit,
    plan: ExportPlan,
    subject: ExportSheetModel.Subject,
    facts: List<EncoderFacts>,
    rate: ExportSheetModel.Rate?,
    tenBitAvailable: Boolean,
    onUseResolution: (Int, Int) -> Unit,
) {
    val hevcOk = ExportSheetModel.hevcAvailable(facts)
    val preset = ExportPreset.of(settings, hevcOk)
    var advanced by remember { mutableStateOf(false) }

    HelpTitle(R.string.export_settings_title, R.string.export_help_presets)
    // (E1c-polish) The three presets are one segmented row. Custom is not a
    // segment: when the settings match no preset nothing is selected and the
    // summary card carries the "Custom" label.
    val presets = listOf(ExportPreset.COMPATIBLE, ExportPreset.SMALLER, ExportPreset.QUALITY)
    val presetLabels = presets.map { stringResource(presetName(it)) }
    AppSegmentedControl(
        options = presetLabels,
        selectedIndex = presets.indexOf(preset),
        onSelect = { i ->
            onSettings(ExportSheetModel.applyPreset(settings, presets[i], facts, tenBitAvailable))
        },
        minHeight = 48.dp,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    if (preset == ExportPreset.QUALITY && !tenBitAvailable) {
        Text(
            stringResource(R.string.export_preset_quality_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
        )
    }

    Spacer(Modifier.height(8.dp))
    ProducedSummary(plan, subject, rate, custom = preset == ExportPreset.CUSTOM)

    for (issue in plan.issues) {
        IssueRow(issue, settings, onSettings, onUseResolution)
    }

    TextButton(
        onClick = { advanced = !advanced },
        modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp),
    ) {
        Text(stringResource(R.string.export_advanced))
        Icon(
            if (advanced) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
    if (advanced) {
        AdvancedSection(settings, onSettings, plan, subject, hevcOk, tenBitAvailable && ExportSheetModel.tenBitEncoderAvailable(facts))
    }
}

private fun presetName(p: ExportPreset): Int = when (p) {
    ExportPreset.COMPATIBLE -> R.string.export_preset_compatible
    ExportPreset.SMALLER -> R.string.export_preset_smaller
    ExportPreset.QUALITY -> R.string.export_preset_quality
    ExportPreset.CUSTOM -> R.string.export_preset_custom
}

/**
 * The "what will really be produced" card: the plan's own numbers, not the
 * request's, as one line that reads at a glance; the time estimate under it;
 * the encoder's name as a small secondary line. [custom] shows the "Custom"
 * label when the settings match no preset.
 */
@Composable
private fun ProducedSummary(
    plan: ExportPlan,
    subject: ExportSheetModel.Subject,
    rate: ExportSheetModel.Rate?,
    custom: Boolean,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.export_produced_title),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                if (custom) {
                    Text(
                        stringResource(R.string.export_preset_custom),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            val size = if (subject.durationSec > 0.0) {
                ExportText.megabytes(plan.estimatedBytes(subject.durationSec))
            } else {
                null
            }
            Text(
                ExportSheetState.summaryLine(plan, size),
                style = MaterialTheme.typography.bodyLarge.merge(Tnum),
                modifier = Modifier.padding(top = 4.dp),
            )
            val secs = ExportSheetModel.estimatedSeconds(subject, rate)
            Text(
                when {
                    size == null -> stringResource(R.string.export_estimate_unknown)
                    secs != null -> stringResource(R.string.export_time_estimate, formatEstimate(secs))
                    else -> stringResource(R.string.export_time_unknown)
                },
                style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            plan.encoderName?.let { name ->
                Text(
                    stringResource(
                        if (plan.encoderHardware == false) R.string.exports_report_encoder_sw else R.string.exports_report_encoder_hw,
                        name,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** "about 2 min" figures; the string carries the unit. */
@Composable
private fun formatEstimate(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(1L)
    return if (s < 90L) stringResource(R.string.export_estimate_seconds, s.toInt())
    else stringResource(R.string.export_estimate_minutes, ((s + 30L) / 60L).toInt())
}

/** One plan issue: icon + localized sentence + its fix buttons (settings fix, resolution fix). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IssueRow(
    issue: ExportIssue,
    settings: ExportSettings,
    onSettings: (ExportSettings) -> Unit,
    onUseResolution: (Int, Int) -> Unit,
) {
    val error = issue.severity == ExportIssue.Severity.ERROR
    val tint = when (issue.severity) {
        ExportIssue.Severity.ERROR -> MaterialTheme.colorScheme.error
        ExportIssue.Severity.WARNING -> MaterialTheme.colorScheme.tertiary
        ExportIssue.Severity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                if (issue.severity == ExportIssue.Severity.INFO) Icons.Filled.Info else Icons.Filled.Warning,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(top = 2.dp, end = 12.dp),
            )
            Text(
                issueText(issue),
                style = MaterialTheme.typography.bodyMedium,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 36.dp)) {
            issue.fix?.takeIf { it != settings }?.let { fix ->
                TextButton(onClick = { onSettings(fix) }) { Text(stringResource(R.string.export_issue_fix)) }
            }
            issue.fixResolution?.let { (w, h) ->
                TextButton(onClick = { onUseResolution(w, h) }) {
                    Text(stringResource(R.string.export_issue_fix_resolution, w.toString(), h.toString()))
                }
            }
        }
    }
}

@Composable
private fun issueText(i: ExportIssue): String {
    val a = i.args
    fun arg(n: Int) = a.getOrNull(n) ?: ""
    return when (i.kind) {
        ExportIssue.Kind.NO_ENCODER -> stringResource(R.string.export_issue_no_encoder, arg(0))
        ExportIssue.Kind.CODEC_FALLBACK -> stringResource(R.string.export_issue_codec_fallback, arg(0), arg(1))
        ExportIssue.Kind.CODEC_SWITCHED -> stringResource(R.string.export_issue_codec_switched, arg(0), arg(1))
        ExportIssue.Kind.SIZE_UNSUPPORTED -> stringResource(R.string.export_issue_size_unsupported, arg(0), arg(1))
        ExportIssue.Kind.SIZE_UNKNOWN -> stringResource(R.string.export_issue_size_unknown, arg(0))
        ExportIssue.Kind.RATE_UNLISTED -> stringResource(R.string.export_issue_rate_unlisted, arg(0))
        ExportIssue.Kind.TEN_BIT_UNSUPPORTED -> stringResource(R.string.export_issue_ten_bit_unsupported)
        ExportIssue.Kind.TEN_BIT_UNAVAILABLE -> stringResource(R.string.export_issue_ten_bit_unavailable)
        ExportIssue.Kind.DYNAMIC_RANGE_UNAVAILABLE -> stringResource(R.string.export_issue_range_unavailable, arg(0))
        ExportIssue.Kind.RATE_MODE_UNSUPPORTED -> stringResource(R.string.export_issue_rate_mode, arg(0), arg(1))
        ExportIssue.Kind.BITRATE_ABOVE_CEILING -> stringResource(R.string.export_issue_above_ceiling, arg(0), arg(1))
        ExportIssue.Kind.BITRATE_UNCAPPED_RISK -> stringResource(R.string.export_issue_uncapped, arg(0), arg(1))
        ExportIssue.Kind.BITRATE_BELOW_FLOOR -> stringResource(R.string.export_issue_below_floor, arg(0), arg(1))
        ExportIssue.Kind.PROFILE_UNSUPPORTED -> stringResource(R.string.export_issue_profile)
        ExportIssue.Kind.ENCODER_SOFTWARE -> stringResource(R.string.export_issue_software, arg(0))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AdvancedSection(
    s: ExportSettings,
    set: (ExportSettings) -> Unit,
    plan: ExportPlan,
    subject: ExportSheetModel.Subject,
    hevcOk: Boolean,
    tenBitOk: Boolean,
) {
    // Codec
    HelpTitle(R.string.export_codec, R.string.export_help_codec)
    ChipRow {
        for (c in ExportCodec.entries) {
            FilterChip(
                selected = s.codec == c,
                onClick = { set(s.copy(codec = c, profile = null)) },
                label = { Text(ExportText.codec(c.mime)) },
                enabled = c == ExportCodec.AVC || hevcOk,
            )
        }
    }
    // Bit depth (E1d): 10-bit only where the GPU path can make it
    HelpTitle(R.string.export_bit_depth, R.string.export_help_bit_depth)
    ChipRow {
        for (d in listOf(8, 10)) {
            FilterChip(
                selected = s.bitDepth == d,
                onClick = { set(s.copy(bitDepth = d, profile = null)) },
                label = { Text("$d-bit") },
                enabled = d == 8 || tenBitOk || s.bitDepth == 10,
            )
        }
    }
    if (!tenBitOk) {
        Text(
            stringResource(R.string.export_ten_bit_needs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
    // Bitrate level + custom
    HelpTitle(R.string.export_bitrate, R.string.export_help_bitrate)
    ChipRow {
        for (l in BitrateLevel.entries) {
            FilterChip(
                selected = s.bitrateLevel == l,
                onClick = {
                    set(
                        if (l == BitrateLevel.CUSTOM) {
                            // Start the custom value at what the plan resolved, so nothing jumps.
                            s.copy(bitrateLevel = l, customMbps = plan.bitrateBps / 1_000_000.0)
                        } else {
                            s.copy(bitrateLevel = l, customMbps = null, uncapped = false)
                        }
                    )
                },
                label = { Text(stringResource(levelName(l))) },
            )
        }
    }
    if (s.bitrateLevel == BitrateLevel.CUSTOM) {
        var text by remember { mutableStateOf(s.customMbps?.let { fmt1(it) } ?: "") }
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val t = raw.filter { it.isDigit() || it == '.' }.take(6)
                text = t
                t.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { set(s.copy(customMbps = it)) }
            },
            label = { Text(stringResource(R.string.export_custom_mbps)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.export_uncapped),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = s.uncapped, onCheckedChange = { set(s.copy(uncapped = it)) })
        }
        if (s.uncapped) {
            Text(
                stringResource(R.string.export_uncapped_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
    // Rate mode
    HelpTitle(R.string.export_rate_mode, R.string.export_help_rate_mode)
    ChipRow {
        for (m in listOf(ExportRateMode.VBR, ExportRateMode.CBR)) {
            FilterChip(selected = s.rateMode == m, onClick = { set(s.copy(rateMode = m)) }, label = { Text(m.name) })
        }
    }
    // Keyframes
    HelpTitle(R.string.export_keyframes, R.string.export_help_keyframes)
    ChipRow {
        for (k in KeyframeInterval.entries) {
            FilterChip(
                selected = s.keyframe == k,
                onClick = { set(s.copy(keyframe = k)) },
                label = { Text(keyframeName(k)) },
            )
        }
    }
    // Profile
    HelpTitle(R.string.export_profile, R.string.export_help_profile)
    ChipRow {
        for (p in ExportSheetModel.profileChoices(s.codec)) {
            FilterChip(
                selected = s.profile == p,
                onClick = { set(s.copy(profile = p)) },
                label = {
                    Text(if (p == null) stringResource(R.string.export_auto) else ExportSheetModel.profileName(s.codec, p))
                },
            )
        }
    }
    // Audio bitrate
    HelpTitle(R.string.export_audio, R.string.export_help_audio)
    ChipRow {
        for (k in ExportSettings.AUDIO_KBPS) {
            FilterChip(selected = s.audioKbps == k, onClick = { set(s.copy(audioKbps = k)) }, label = { Text("$k kbps") })
        }
    }
    // Read-only facts
    ReadOnlyRow(R.string.export_frame_rate, stringResource(R.string.export_frame_rate_value, ExportSheetState.fpsText(subject.fps)))
    ReadOnlyRow(R.string.export_audio_format, stringResource(R.string.export_audio_format_value))
}

@Composable
private fun levelName(l: BitrateLevel): Int = when (l) {
    BitrateLevel.LOW -> R.string.export_level_low
    BitrateLevel.STANDARD -> R.string.export_level_standard
    BitrateLevel.HIGH -> R.string.export_level_high
    BitrateLevel.MAX -> R.string.export_level_max
    BitrateLevel.CUSTOM -> R.string.export_level_custom
}

@Composable
private fun keyframeName(k: KeyframeInterval): String = when (k) {
    KeyframeInterval.AUTO -> stringResource(R.string.export_auto)
    KeyframeInterval.ALL_INTRA -> stringResource(R.string.export_keyframe_all_intra)
    else -> stringResource(R.string.export_keyframe_seconds, fmt1(k.seconds!!.toDouble()))
}

private fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

@Composable
private fun ReadOnlyRow(label: Int, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A section title that explains itself on long press (stock M3 tooltip:
 * long press is its trigger). 48dp tall so the press target is generous.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun HelpTitle(title: Int, help: Int) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(stringResource(help)) } },
        state = rememberTooltipState(),
    ) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
