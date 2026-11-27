package com.ritim.ritimcraw.ui.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.BitrateLevel
import com.ritim.ritimcraw.export.EncoderFacts
import com.ritim.ritimcraw.export.ExportCodec
import com.ritim.ritimcraw.export.ExportContext
import com.ritim.ritimcraw.export.ExportIssue
import com.ritim.ritimcraw.export.ExportPlan
import com.ritim.ritimcraw.export.ExportPreset
import com.ritim.ritimcraw.export.ExportRateMode
import com.ritim.ritimcraw.export.ExportResolution
import com.ritim.ritimcraw.export.ExportSettings
import com.ritim.ritimcraw.export.ExportSheetModel
import com.ritim.ritimcraw.export.ExportText
import com.ritim.ritimcraw.export.KeyframeInterval
import com.ritim.ritimcraw.ui.theme.Tnum
import java.util.Locale

/**
 * The export sheet's body (P2, 2026-11-25): a Lightroom / Rush style settings
 * list instead of seven stacked chip groups. Every setting is ONE 56dp row
 * (label left, current value right, a thin chevron); a tap slides a sub-page
 * in inside the same sheet (a dropdown inside a bottom sheet is unusable)
 * where the choices are listed with a one-line hint each. Nothing was
 * removed: every option, every issue and every explanation the old sheet had
 * is here, the explanations as the text under a sub-page's title.
 *
 * Pure UI. [ExportSettings], [ExportSheetModel] and [ExportPlan] are
 * untouched: the summary and every hint number come from the plan, never
 * from the request, so they cannot disagree with what Export queues.
 *
 * Frame rate, sample rate and channel count are read-only on purpose (owner
 * decision 2026-11-23): the clip's own rate and the source audio format are
 * carried through.
 */
internal enum class ExportPage { FORMAT, PRESET, RESOLUTION, CODEC, BIT_DEPTH, BITRATE, RATE_MODE, KEYFRAME, PROFILE, AUDIO }

/** One resolution choice as the sub-page lists it (built by [rememberResolutionUi]). */
internal class ResolutionChoiceUi(
    val kind: ExportResolution.Kind,
    val title: String,
    val hint: String?,
    val supported: Boolean,
)

/** The resolution row's value and the sub-page's choices; Custom is appended by the page. */
internal class ResolutionUi(
    val valueText: String,
    val selectedKind: ExportResolution.Kind,
    val choices: List<ResolutionChoiceUi>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ExportBody(
    tab: ExportSheetState.Tab,
    tabs: List<ExportSheetState.Tab>,
    onTab: (ExportSheetState.Tab) -> Unit,
    encodedSource: Boolean,
    settings: ExportSettings,
    onSettings: (ExportSettings) -> Unit,
    plan: ExportPlan?,
    subject: ExportSheetModel.Subject?,
    facts: List<EncoderFacts>,
    rate: ExportSheetModel.Rate?,
    tenBitAvailable: Boolean,
    resolution: ResolutionUi?,
    onPickResolution: (ExportResolution.Kind) -> Unit,
    onCustomResolution: () -> Unit,
    onUseResolution: (Int, Int) -> Unit,
    targetDetail: @Composable () -> Unit,
    page: ExportPage?,
    onPage: (ExportPage?) -> Unit,
) {
    var advanced by remember { mutableStateOf(false) }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally { it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
            } else {
                (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
            }
        },
        label = "export-page",
    ) { p ->
        if (p == null) {
            MainList(
                tab = tab,
                tabs = tabs,
                encodedSource = encodedSource,
                settings = settings,
                onSettings = onSettings,
                plan = plan,
                subject = subject,
                facts = facts,
                rate = rate,
                tenBitAvailable = tenBitAvailable,
                resolution = resolution,
                onUseResolution = onUseResolution,
                advanced = advanced,
                onAdvanced = { advanced = it },
                onOpen = { onPage(it) },
                targetDetail = targetDetail,
            )
        } else {
            SubPage(
                page = p,
                tab = tab,
                tabs = tabs,
                onTab = { onTab(it); onPage(null) },
                settings = settings,
                onSettings = onSettings,
                plan = plan,
                subject = subject,
                facts = facts,
                tenBitAvailable = tenBitAvailable,
                resolution = resolution,
                onPickResolution = { onPickResolution(it); onPage(null) },
                onCustomResolution = onCustomResolution,
                onBack = { onPage(null) },
                onDone = { onPage(null) },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// The list
// ---------------------------------------------------------------------------

@Composable
private fun MainList(
    tab: ExportSheetState.Tab,
    tabs: List<ExportSheetState.Tab>,
    encodedSource: Boolean,
    settings: ExportSettings,
    onSettings: (ExportSettings) -> Unit,
    plan: ExportPlan?,
    subject: ExportSheetModel.Subject?,
    facts: List<EncoderFacts>,
    rate: ExportSheetModel.Rate?,
    tenBitAvailable: Boolean,
    resolution: ResolutionUi?,
    onUseResolution: (Int, Int) -> Unit,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    onOpen: (ExportPage) -> Unit,
    targetDetail: @Composable () -> Unit,
) {
    val hevcOk = ExportSheetModel.hevcAvailable(facts)
    val preset = ExportPreset.of(settings, hevcOk)
    Column(Modifier.fillMaxWidth()) {
        if (tab == ExportSheetState.Tab.MP4 && plan != null && subject != null) {
            Summary(plan, subject, rate)
            for (issue in plan.issues) IssueBand(issue, settings, onSettings, onUseResolution)
            HorizontalDivider(Modifier.padding(top = 4.dp))
        }
        val rows = ExportSheetState.mainRows(encodedSource)
        for (row in rows) {
            // The MP4 settings need a usable plan; the Format row is always there (unless encoded).
            val mp4 = tab == ExportSheetState.Tab.MP4
            if (row != ExportSheetState.Row.FORMAT && !mp4) continue
            when (row) {
                ExportSheetState.Row.FORMAT -> SettingRow(
                    label = stringResource(R.string.export_row_format),
                    value = stringResource(tabName(tab)),
                    onClick = { onOpen(ExportPage.FORMAT) },
                )
                ExportSheetState.Row.PRESET -> SettingRow(
                    label = stringResource(R.string.export_row_preset),
                    value = stringResource(presetName(preset)),
                    onClick = { onOpen(ExportPage.PRESET) },
                )
                ExportSheetState.Row.RESOLUTION -> if (resolution != null) SettingRow(
                    label = stringResource(R.string.export_resolution_field),
                    value = resolution.valueText,
                    onClick = { onOpen(ExportPage.RESOLUTION) },
                )
                ExportSheetState.Row.FRAME_RATE -> if (subject != null) SettingRow(
                    label = stringResource(R.string.export_frame_rate),
                    value = stringResource(R.string.export_frame_rate_value, ExportSheetState.fpsText(subject.fps)),
                    onClick = null,
                )
                ExportSheetState.Row.CODEC -> SettingRow(
                    label = stringResource(R.string.export_codec),
                    value = ExportText.codec(settings.codec.mime),
                    onClick = { onOpen(ExportPage.CODEC) },
                )
                ExportSheetState.Row.BIT_DEPTH -> SettingRow(
                    label = stringResource(R.string.export_bit_depth),
                    value = "${settings.bitDepth}-bit",
                    // An encoded clip develops on the CPU: 10-bit cannot happen, and the row says why.
                    supporting = if (encodedSource) stringResource(R.string.export_bit_depth_encoded_reason) else null,
                    enabled = !encodedSource,
                    onClick = { onOpen(ExportPage.BIT_DEPTH) },
                )
                else -> Unit
            }
        }
        if (tab == ExportSheetState.Tab.MP4 && plan != null) {
            if (preset == ExportPreset.QUALITY && !tenBitAvailable) {
                Text(
                    stringResource(R.string.export_preset_quality_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            // The one-line collapsible Advanced section (starts closed).
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable { onAdvanced(!advanced) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.export_advanced),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painterResource(R.drawable.ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).rotate(if (advanced) 180f else 0f),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (advanced && subject != null) {
                for (row in ExportSheetState.advancedRows()) {
                    when (row) {
                        ExportSheetState.Row.BITRATE -> SettingRow(
                            stringResource(R.string.export_bitrate),
                            "${stringResource(levelName(settings.bitrateLevel))} · ${ExportSheetState.bitrateValue(plan)}",
                            onClick = { onOpen(ExportPage.BITRATE) },
                        )
                        ExportSheetState.Row.RATE_MODE -> SettingRow(
                            stringResource(R.string.export_rate_mode),
                            settings.rateMode.name,
                            onClick = { onOpen(ExportPage.RATE_MODE) },
                        )
                        ExportSheetState.Row.KEYFRAME -> SettingRow(
                            stringResource(R.string.export_keyframes),
                            keyframeName(settings.keyframe),
                            onClick = { onOpen(ExportPage.KEYFRAME) },
                        )
                        ExportSheetState.Row.PROFILE -> SettingRow(
                            stringResource(R.string.export_profile),
                            profileText(settings),
                            onClick = { onOpen(ExportPage.PROFILE) },
                        )
                        ExportSheetState.Row.AUDIO -> SettingRow(
                            stringResource(R.string.export_audio),
                            "${settings.audioKbps} kbps",
                            onClick = { onOpen(ExportPage.AUDIO) },
                        )
                        ExportSheetState.Row.AUDIO_FORMAT -> SettingRow(
                            stringResource(R.string.export_audio_format),
                            stringResource(R.string.export_audio_format_value),
                            onClick = null,
                        )
                        else -> Unit
                    }
                }
            }
        }
        if (tab != ExportSheetState.Tab.MP4) targetDetail()
    }
}

/** The summary: two lines, from the plan (the card's compressed form). */
@Composable
private fun Summary(plan: ExportPlan, subject: ExportSheetModel.Subject, rate: ExportSheetModel.Rate?) {
    val size = if (subject.durationSec > 0.0) ExportText.megabytes(plan.estimatedBytes(subject.durationSec)) else null
    val secs = ExportSheetModel.estimatedSeconds(subject, rate)
    val time = when {
        size == null -> stringResource(R.string.export_estimate_unknown)
        secs != null -> stringResource(R.string.export_time_estimate, formatEstimate(secs))
        else -> stringResource(R.string.export_time_unknown)
    }
    val encoder = plan.encoderName?.let { name ->
        stringResource(
            if (plan.encoderHardware == false) R.string.exports_report_encoder_sw else R.string.exports_report_encoder_hw,
            name,
        )
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            ExportSheetState.compactSummaryLine(plan, size),
            style = MaterialTheme.typography.titleSmall.merge(Tnum),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (encoder != null) "$time · $encoder" else time,
            style = MaterialTheme.typography.bodySmall.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** "about 2 min" figures; the string carries the unit. */
@Composable
private fun formatEstimate(seconds: Double): String {
    val s = seconds.toLong().coerceAtLeast(1L)
    return if (s < 90L) stringResource(R.string.export_estimate_seconds, s.toInt())
    else stringResource(R.string.export_estimate_minutes, ((s + 30L) / 60L).toInt())
}

/** One 56dp setting line: label, current value, thin chevron (none when read-only). */
@Composable
private fun SettingRow(
    label: String,
    value: String,
    onClick: (() -> Unit)?,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    val tap = onClick != null && enabled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .then(if (tap) Modifier.clickable { onClick?.invoke() } else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp).padding(start = 12.dp),
        )
        if (onClick != null) {
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Spacer(Modifier.size(24.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// Issues: compact bands with the fix button; ERROR still locks Export (the sheet's button).
// ---------------------------------------------------------------------------

@Composable
private fun IssueBand(
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(if (issue.severity == ExportIssue.Severity.INFO) R.drawable.ic_info else R.drawable.ic_warning),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp),
        )
        Text(
            issueText(issue),
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Column(horizontalAlignment = Alignment.End) {
            issue.fix?.takeIf { it != settings }?.let { fix ->
                TextButton(onClick = { onSettings(fix) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.export_issue_fix))
                }
            }
            issue.fixResolution?.let { (w, h) ->
                TextButton(onClick = { onUseResolution(w, h) }, modifier = Modifier.heightIn(min = 48.dp)) {
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

// ---------------------------------------------------------------------------
// Sub-pages
// ---------------------------------------------------------------------------

@Composable
private fun SubPage(
    page: ExportPage,
    tab: ExportSheetState.Tab,
    tabs: List<ExportSheetState.Tab>,
    onTab: (ExportSheetState.Tab) -> Unit,
    settings: ExportSettings,
    onSettings: (ExportSettings) -> Unit,
    plan: ExportPlan?,
    subject: ExportSheetModel.Subject?,
    facts: List<EncoderFacts>,
    tenBitAvailable: Boolean,
    resolution: ResolutionUi?,
    onPickResolution: (ExportResolution.Kind) -> Unit,
    onCustomResolution: () -> Unit,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val hevcOk = ExportSheetModel.hevcAvailable(facts)
    val preset = ExportPreset.of(settings, hevcOk)
    val tenBitOk = tenBitAvailable && ExportSheetModel.tenBitEncoderAvailable(facts)
    val unavailable = stringResource(R.string.export_option_unavailable)
    // What an alternative WOULD produce, from the real plan (never an estimate of our own).
    fun planFor(s: ExportSettings): ExportPlan? =
        subject?.let { ExportSheetModel.plan(s, it, facts, ExportContext(tenBitPathAvailable = tenBitAvailable)) }
    fun sizeOf(p: ExportPlan?): String? =
        if (p != null && subject != null && subject.durationSec > 0.0) "≈ " + ExportText.megabytes(p.estimatedBytes(subject.durationSec)) else null
    fun apply(s: ExportSettings) { onSettings(s); onDone() }

    when (page) {
        ExportPage.FORMAT -> PageFrame(stringResource(R.string.export_row_format), null, onBack) {
            for (t in tabs) {
                val (title, hint) = when (t) {
                    ExportSheetState.Tab.MP4 -> stringResource(R.string.export_tab_mp4) to stringResource(R.string.export_format_mp4_hint)
                    // The old per-target description texts live here now.
                    ExportSheetState.Tab.DNG -> stringResource(R.string.editor_export_choice_dng) to
                        (stringResource(R.string.exports_format_dng) + " · " + stringResource(R.string.exports_format_dng_detail))
                    ExportSheetState.Tab.LINEAR -> stringResource(R.string.editor_export_choice_dng_linear) to
                        (stringResource(R.string.exports_format_dng_linear) + " · " + stringResource(R.string.exports_format_dng_linear_detail))
                    ExportSheetState.Tab.PHOTO -> stringResource(R.string.exports_format_photo) to stringResource(R.string.export_photo_detail)
                }
                OptionRow(title, hint, selected = t == tab, enabled = true) { onTab(t) }
            }
        }
        ExportPage.PRESET -> PageFrame(stringResource(R.string.export_row_preset), stringResource(R.string.export_help_presets), onBack) {
            val presets = listOf(ExportPreset.COMPATIBLE, ExportPreset.SMALLER, ExportPreset.QUALITY)
            for (p in presets) {
                val next = ExportSheetModel.applyPreset(settings, p, facts, tenBitAvailable)
                val what = stringResource(presetHint(p))
                val size = sizeOf(planFor(next))
                OptionRow(
                    stringResource(presetName(p)),
                    if (size != null) "$what · $size" else what,
                    selected = preset == p,
                    enabled = true,
                ) { apply(next) }
            }
            // Custom is never a choice: it only shows, selected, when nothing matches a preset.
            if (preset == ExportPreset.CUSTOM) {
                OptionRow(
                    stringResource(R.string.export_preset_custom),
                    stringResource(R.string.export_preset_custom_hint),
                    selected = true,
                    enabled = false,
                ) {}
            }
            if (!tenBitAvailable) {
                Text(
                    stringResource(R.string.export_preset_quality_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        ExportPage.RESOLUTION -> PageFrame(stringResource(R.string.export_resolution_field), stringResource(R.string.export_help_resolution), onBack) {
            if (resolution != null) {
                for (c in resolution.choices) {
                    OptionRow(
                        c.title,
                        if (c.supported) c.hint else listOfNotNull(c.hint, stringResource(R.string.editor_export_unsupported)).joinToString(" · "),
                        selected = resolution.selectedKind == c.kind,
                        enabled = c.supported,
                    ) { onPickResolution(c.kind) }
                }
                // Custom: always last; it opens the two-field dialog.
                OptionRow(
                    stringResource(R.string.editor_export_resolution_custom),
                    stringResource(R.string.export_custom_hint),
                    selected = resolution.selectedKind == ExportResolution.Kind.CUSTOM,
                    enabled = true,
                ) { onCustomResolution() }
            }
        }
        ExportPage.CODEC -> PageFrame(stringResource(R.string.export_codec), stringResource(R.string.export_help_codec), onBack) {
            for (c in ExportCodec.entries) {
                val ok = c == ExportCodec.AVC || hevcOk
                val hint = when {
                    !ok -> unavailable
                    c == ExportCodec.AVC -> stringResource(R.string.export_codec_avc_hint)
                    else -> stringResource(R.string.export_codec_hevc_hint)
                }
                OptionRow(ExportText.codec(c.mime), hint, selected = settings.codec == c, enabled = ok) {
                    apply(settings.copy(codec = c, profile = null))
                }
            }
        }
        ExportPage.BIT_DEPTH -> PageFrame(stringResource(R.string.export_bit_depth), stringResource(R.string.export_help_bit_depth), onBack) {
            for (d in listOf(8, 10)) {
                val ok = d == 8 || tenBitOk || settings.bitDepth == 10
                val hint = when {
                    d == 8 -> stringResource(R.string.export_bit_depth_8_hint)
                    !ok -> stringResource(R.string.export_ten_bit_needs)
                    else -> stringResource(R.string.export_bit_depth_10_hint)
                }
                OptionRow("$d-bit", hint, selected = settings.bitDepth == d, enabled = ok) {
                    apply(settings.copy(bitDepth = d, profile = null))
                }
            }
        }
        ExportPage.BITRATE -> PageFrame(stringResource(R.string.export_bitrate), stringResource(R.string.export_help_bitrate), onBack) {
            for (l in plan?.let { ExportSheetModel.offeredBitrateLevels(it, facts, settings.bitrateLevel) } ?: BitrateLevel.entries) {
                val hint = if (l == BitrateLevel.CUSTOM) null else {
                    planFor(settings.copy(bitrateLevel = l, customMbps = null, uncapped = false))?.let {
                        listOfNotNull(ExportSheetState.bitrateValue(it), sizeOf(it)).joinToString(" · ")
                    }
                }
                OptionRow(stringResource(levelName(l)), hint, selected = settings.bitrateLevel == l, enabled = true) {
                    if (l == BitrateLevel.CUSTOM) {
                        // Start the custom value at what the plan resolved, so nothing jumps; stay for the field.
                        onSettings(settings.copy(bitrateLevel = l, customMbps = (plan?.bitrateBps ?: 0) / 1_000_000.0))
                    } else {
                        apply(settings.copy(bitrateLevel = l, customMbps = null, uncapped = false))
                    }
                }
            }
            if (settings.bitrateLevel == BitrateLevel.CUSTOM) CustomBitrate(settings, onSettings)
        }
        ExportPage.RATE_MODE -> PageFrame(stringResource(R.string.export_rate_mode), stringResource(R.string.export_help_rate_mode), onBack) {
            for (m in listOf(ExportRateMode.VBR, ExportRateMode.CBR)) {
                OptionRow(
                    m.name,
                    stringResource(if (m == ExportRateMode.VBR) R.string.export_rate_vbr_hint else R.string.export_rate_cbr_hint),
                    selected = settings.rateMode == m,
                    enabled = true,
                ) { apply(settings.copy(rateMode = m)) }
            }
        }
        ExportPage.KEYFRAME -> PageFrame(stringResource(R.string.export_keyframes), stringResource(R.string.export_help_keyframes), onBack) {
            for (k in KeyframeInterval.entries) {
                OptionRow(
                    keyframeName(k),
                    if (k == KeyframeInterval.ALL_INTRA) stringResource(R.string.export_keyframe_all_intra_hint) else null,
                    selected = settings.keyframe == k,
                    enabled = true,
                ) { apply(settings.copy(keyframe = k)) }
            }
        }
        ExportPage.PROFILE -> PageFrame(stringResource(R.string.export_profile), stringResource(R.string.export_help_profile), onBack) {
            for (p in ExportSheetModel.profileChoices(settings.codec)) {
                OptionRow(
                    if (p == null) stringResource(R.string.export_auto) else ExportSheetModel.profileName(settings.codec, p),
                    null,
                    selected = settings.profile == p,
                    enabled = true,
                ) { apply(settings.copy(profile = p)) }
            }
        }
        ExportPage.AUDIO -> PageFrame(stringResource(R.string.export_audio), stringResource(R.string.export_help_audio), onBack) {
            for (k in ExportSettings.AUDIO_KBPS) {
                OptionRow("$k kbps", null, selected = settings.audioKbps == k, enabled = true) { apply(settings.copy(audioKbps = k)) }
            }
        }
    }
}

/** Custom bitrate: the Mbps field, the uncapped switch and its warning (all from the old Advanced block). */
@Composable
private fun CustomBitrate(s: ExportSettings, set: (ExportSettings) -> Unit) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.export_uncapped), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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

/** A sub-page: back arrow + title, the old help text as a short paragraph, then the options. */
@Composable
private fun PageFrame(title: String, help: String?, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    contentDescription = stringResource(R.string.export_back),
                    modifier = Modifier.size(20.dp),
                )
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        if (help != null) {
            Text(
                help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        content()
    }
}

/**
 * One choice: title + a one-line hint. The chosen one is told by colour and a
 * thin check, never by bold (the owner finds heavy marks "fat"). A choice the
 * phone cannot make is greyed and the hint says why.
 */
@Composable
private fun OptionRow(title: String, hint: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .alpha(if (enabled || selected) 1f else 0.5f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Icon(
                    painterResource(R.drawable.ic_check),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Names
// ---------------------------------------------------------------------------

private fun tabName(t: ExportSheetState.Tab): Int = when (t) {
    ExportSheetState.Tab.MP4 -> R.string.export_tab_mp4
    ExportSheetState.Tab.DNG -> R.string.export_tab_dng
    ExportSheetState.Tab.LINEAR -> R.string.export_tab_linear
    ExportSheetState.Tab.PHOTO -> R.string.export_tab_photo
}

private fun presetName(p: ExportPreset): Int = when (p) {
    ExportPreset.COMPATIBLE -> R.string.export_preset_compatible
    ExportPreset.SMALLER -> R.string.export_preset_smaller
    ExportPreset.QUALITY -> R.string.export_preset_quality
    ExportPreset.SOCIAL -> R.string.export_preset_social
    ExportPreset.CUSTOM -> R.string.export_preset_custom
}

private fun presetHint(p: ExportPreset): Int = when (p) {
    ExportPreset.COMPATIBLE -> R.string.export_preset_compatible_hint
    ExportPreset.SMALLER -> R.string.export_preset_smaller_hint
    ExportPreset.QUALITY -> R.string.export_preset_quality_hint
    ExportPreset.SOCIAL -> R.string.export_preset_social_hint
    ExportPreset.CUSTOM -> R.string.export_preset_custom_hint
}

private fun levelName(l: BitrateLevel): Int = when (l) {
    BitrateLevel.LOW -> R.string.export_level_low
    BitrateLevel.STANDARD -> R.string.export_level_standard
    BitrateLevel.HIGH -> R.string.export_level_high
    BitrateLevel.MAX -> R.string.export_level_max
    BitrateLevel.QUALITY -> R.string.export_preset_quality
    BitrateLevel.HIGHER -> R.string.export_level_higher
    BitrateLevel.CEILING -> R.string.export_level_ceiling
    BitrateLevel.CUSTOM -> R.string.export_level_custom
}

@Composable
private fun keyframeName(k: KeyframeInterval): String = when (k) {
    KeyframeInterval.AUTO -> stringResource(R.string.export_auto)
    KeyframeInterval.ALL_INTRA -> stringResource(R.string.export_keyframe_all_intra)
    else -> stringResource(R.string.export_keyframe_seconds, fmt1(k.seconds!!.toDouble()))
}

@Composable
private fun profileText(s: ExportSettings): String =
    s.profile?.let { ExportSheetModel.profileName(s.codec, it) } ?: stringResource(R.string.export_auto)

private fun fmt1(v: Double): String = String.format(Locale.ROOT, "%.1f", v).removeSuffix(".0")
