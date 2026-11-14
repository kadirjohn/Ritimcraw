package com.ritim.ritimcraw.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.CodecReport
import com.ritim.ritimcraw.ui.components.AppSegmentedControl
import com.ritim.ritimcraw.ui.components.ArrowBack
import com.ritim.ritimcraw.ui.components.Copy
import com.ritim.ritimcraw.ui.components.Cpu
import com.ritim.ritimcraw.ui.components.SettingsGroupCard

/**
 * Device report screen (owner spec 2026-11-14, device_report_screen): the
 * full-screen replacement for the codec AlertDialog. Same data source as
 * the dialog — AppNav's one CodecProbe.probe() per Settings-tab
 * composition, shared with the Settings row — only the container changed.
 *
 * Layout (spec, verbatim geometry):
 * - top bar: back IconButton (arrow_back 24dp, 48dp target,
 *   contentDescription "Back"), title "Device report" 22sp SemiBold
 *   onSurface, trailing Copy IconButton (copy icon 22dp, contentDescription
 *   "Copy report"); padding top 8dp, horizontal 8dp. The app shell's
 *   Scaffold already applies the status-bar inset through its innerPadding
 *   (default contentWindowInsets — same as the Settings screen), so the
 *   8dp here sits below that inset: nothing hardcodes a status bar height
 *   and nothing double-pads. The bar stays fixed while the content below
 *   scrolls as one (spec: "the whole screen scrolls as one, avoid nested
 *   scrolling of the same orientation" — one vertical scroll, no nested
 *   LazyColumn inside the scrolling page).
 * - summary card: the group-card container (20dp shape,
 *   surfaceContainerLow + 1dp outlineVariant border) with 20dp padding,
 *   margin top 8dp; a 56dp tile (16dp shape, primaryContainer) with the
 *   cpu icon at 28dp, then the codec count 32sp SemiBold lineHeight 1.0
 *   letterSpacing -0.02em onSurface and "video codecs on this device"
 *   13sp onSurfaceVariant.
 * - filter: the same segmented control (All / Decoders / Encoders), local
 *   state, default All.
 * - the grouped list: one group-card container (bottom margin 32dp)
 *   holding a plain Column — group headers ("AVC · Decoders", 12sp
 *   SemiBold ALL CAPS 0.08em onSurfaceVariant on surfaceContainerHighest)
 *   only on group change, then codec items (16dp/12dp padding, 1dp top
 *   divider outlineVariant): the FULL codec name in FontFamily.Monospace
 *   13sp lineHeight 1.35 onSurface, wrapping at any character, never
 *   truncated; under it a FlowRow of pills (gap 6dp, height 22dp,
 *   horizontal padding 8dp, fully rounded, 12sp SemiBold): HW
 *   (primaryContainer) or SW (neutral), Codec2 for "c2." names, OMX for
 *   "OMX." names, "Low latency" for ".low_latency" names.
 *
 * Kind derivation (an honest deviation, documented for the handback): the
 * spec says the filter uses "the existing codec info (isEncoder flag), not
 * from name parsing" — but CodecReport (engine/) carries NO isEncoder
 * flag (name, mime, hardware, profiles, maxSize, main10Ok, detail), and
 * the engine package is outside this mission's file boundary, so the
 * flag cannot be added there. The kind is therefore derived from the
 * platform's own component-name grammars — the two placements
 * MediaCodecList names actually use: Codec2 names END with the kind
 * (c2.qti.avc.encoder), OMX names carry it as a NAME SEGMENT
 * (OMX.qcom.video.encoder.avc) — a platform convention, not a heuristic
 * guess at vendor naming. A name with neither marker (none exist on real
 * devices) groups as a decoder. The codec LABEL still comes
 * from the MIME type exactly as the spec demands (video/avc -> AVC,
 * video/hevc -> HEVC, else the subtype uppercased) — never from the name.
 *
 * Copy: the screen builds the full plain-text report (one codec name per
 * line under the same group headers, the FULL list — the filter is a
 * view, the copy is the report) and hands it to [onCopyReport]; AppNav
 * owns the clipboard write and shows the shell's existing snackbar.
 *
 * The grouping/sorting/copy-text logic is pure ([DeviceReportGrouping])
 * and pinned by unit tests (DeviceReportGroupingTest) so correctness does
 * not depend on seeing the UI.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceReportScreen(
    reports: List<CodecReport>,
    onBack: () -> Unit,
    onCopyReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    // The filter (spec): local state, default All. 0 = All, 1 = Decoders,
    // 2 = Encoders — the segmented control maps the index straight back.
    var filterIndex by rememberSaveable { mutableIntStateOf(0) }
    val allGroups = remember(reports) { DeviceReportGrouping.group(reports) }
    val visibleGroups = when (filterIndex) {
        1 -> allGroups.filter { !it.encoders }
        2 -> allGroups.filter { it.encoders }
        else -> allGroups
    }
    // The group header formats ("%1$s · Decoders") resolved once; the pure
    // header/copy builders below format the labels with them.
    val decodersFormat = stringResource(R.string.settings_device_report_group_decoders)
    val encodersFormat = stringResource(R.string.settings_device_report_group_encoders)
    // Copy text (spec): the FULL codec list, one name per line, grouped by
    // the same headers — the filter does not change what gets copied.
    val copyText = remember(allGroups, decodersFormat, encodersFormat) {
        DeviceReportGrouping.plainText(allGroups, decodersFormat, encodersFormat)
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- Top bar (fixed while the content below scrolls) ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = ArrowBack,
                        contentDescription = stringResource(R.string.settings_back),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.settings_device_report),
                    style = TextStyle(
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                IconButton(onClick = { onCopyReport(copyText) }) {
                    Icon(
                        imageVector = Copy,
                        contentDescription = stringResource(R.string.settings_device_report_copy),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            // ---- The scrolling page: summary, filter, list as ONE scroll ----
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                // Summary card (spec summary_card): the group-card
                // container with 20dp padding; the 56dp primaryContainer
                // tile with the cpu icon, the count and its caption.
                SettingsGroupCard(modifier = Modifier.padding(top = 8.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(16.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Cpu,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Column {
                            Text(
                                text = reports.size.toString(),
                                style = TextStyle(
                                    fontSize = 32.sp,
                                    lineHeight = 32.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = (-0.02).em,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.settings_device_report_summary),
                                style = TextStyle(
                                    fontSize = 13.sp,
                                    lineHeight = 18.sp,
                                    fontWeight = FontWeight.Normal,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                // Filter (spec filter): the same segmented control, local
                // state, default All.
                AppSegmentedControl(
                    options = listOf(
                        stringResource(R.string.settings_device_report_filter_all),
                        stringResource(R.string.settings_device_report_filter_decoders),
                        stringResource(R.string.settings_device_report_filter_encoders),
                    ),
                    selectedIndex = filterIndex,
                    onSelect = { filterIndex = it },
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp),
                )
                // The grouped list (spec list): one group-card container,
                // bottom margin 32dp. A filter that matches nothing on a
                // device (real devices always have both kinds) renders no
                // stray empty card.
                if (visibleGroups.isNotEmpty()) {
                    SettingsGroupCard(
                        modifier = Modifier.padding(top = 16.dp, bottom = 32.dp)
                    ) {
                        visibleGroups.forEach { group ->
                            // Group header: only on group change — one per
                            // (codec, kind) group by construction.
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
                            ) {
                                Text(
                                    text = DeviceReportGrouping
                                        .headerText(group, decodersFormat, encodersFormat)
                                        .uppercase(),
                                    style = TextStyle(
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.08.em,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            group.codecs.forEach { report ->
                                Column(Modifier.fillMaxWidth()) {
                                    // 1dp top divider (spec: on each item).
                                    HorizontalDivider(
                                        color = MaterialTheme.colorScheme.outlineVariant
                                    )
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp)
                                    ) {
                                        // Line 1: the FULL codec name,
                                        // monospace, wrapping at any
                                        // character, never truncated or
                                        // ellipsized (no maxLines).
                                        Text(
                                            text = report.name,
                                            style = TextStyle(
                                                fontSize = 13.sp,
                                                // Spec lineHeight 1.35x:
                                                // 13 * 1.35 = 17.55 -> 18sp
                                                // (the project's rounding
                                                // precedent: 13 * 1.45 -> 19).
                                                lineHeight = 18.sp,
                                                fontWeight = FontWeight.Normal,
                                                fontFamily = FontFamily.Monospace,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        // Line 2: the pill flow (spec).
                                        FlowRow(
                                            modifier = Modifier.padding(top = 8.dp),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            // HW (highlight) or SW (neutral)
                                            // from the existing flag.
                                            CodecPill(
                                                text = stringResource(
                                                    if (report.hardware) {
                                                        R.string.settings_codec_hw
                                                    } else {
                                                        R.string.settings_codec_sw
                                                    }
                                                ),
                                                highlight = report.hardware,
                                            )
                                            // API pill: Codec2 / OMX from the
                                            // name prefix, omitted otherwise.
                                            // The spec names these as literal
                                            // labels (proper nouns, not in
                                            // its strings list): file-private
                                            // constants, not resources.
                                            when {
                                                report.name.startsWith("c2.") -> CodecPill(
                                                    text = Codec2PillLabel,
                                                    highlight = false,
                                                )
                                                report.name.startsWith("OMX.") -> CodecPill(
                                                    text = OmxPillLabel,
                                                    highlight = false,
                                                )
                                            }
                                            // Low latency: the ".low_latency"
                                            // name suffix (spec).
                                            if (report.name.endsWith(".low_latency")) {
                                                CodecPill(
                                                    text = stringResource(
                                                        R.string.settings_device_report_pill_low_latency
                                                    ),
                                                    highlight = false,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One spec pill (device_report_screen list): height 22dp (heightIn so font
 * scale cannot clip it — the spec's accessibility section forbids fixed
 * heights on text), horizontal padding 8dp, fully rounded (50% corners),
 * 12sp SemiBold. Highlight = the HW pair (primaryContainer /
 * onPrimaryContainer); neutral = surfaceContainerHighest /
 * onSurfaceVariant.
 */
@Composable
private fun CodecPill(
    text: String,
    highlight: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .heightIn(min = 22.dp)
            .background(
                color = if (highlight) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                },
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = TextStyle(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            ),
            color = if (highlight) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** One (codec, kind) group of the device report list. */
internal data class CodecGroup(
    val label: String,
    val encoders: Boolean,
    val codecs: List<CodecReport>,
)

// The API pills' literal labels (spec: "Codec2" for "c2." names, "OMX" for
// "OMX." names — proper nouns the spec names directly, not strings from
// its localization list).
private const val Codec2PillLabel = "Codec2"
private const val OmxPillLabel = "OMX"

/**
 * The device report's pure grouping logic (spec device_report_screen,
 * JVM-testable — no Android types): the codec label from the MIME, the
 * kind from the platform's canonical component-name suffix, the
 * (codec, kind) sort/group, the header text and the copy text.
 */
internal object DeviceReportGrouping {

    /**
     * The CODEC label from the MIME type (spec): video/avc -> "AVC",
     * video/hevc -> "HEVC" (the literal values of
     * MediaFormat.MIMETYPE_VIDEO_AVC/HEVC — kept as literals so this
     * object stays JVM-pure), others -> the MIME subtype uppercased.
     * Never parsed from the codec NAME.
     */
    fun codecLabel(mime: String): String = when (mime) {
        "video/avc" -> "AVC"
        "video/hevc" -> "HEVC"
        else -> mime.substringAfter('/').uppercase()
    }

    /**
     * The codec kind. CodecReport has no isEncoder flag (and the engine
     * package is outside this mission's boundary), so the kind comes
     * from the platform's component-name grammars — the two placements
     * Android's MediaCodecList names actually use, not vendor-name
     * guessing:
     * - Codec2 names end with the kind: c2.qti.avc.encoder,
     *   c2.android.avc.decoder,
     * - OMX names carry the kind as a NAME SEGMENT:
     *   OMX.qcom.video.encoder.avc, OMX.qcom.video.decoder.avc.
     * A name with neither marker (none exist on real devices) counts as
     * a decoder. Case-insensitive: some vendors ship mixed-case OMX
     * names; no codec name contains ".encoder." as anything but the kind.
     */
    fun isEncoder(name: String): Boolean {
        val n = name.lowercase()
        return when {
            n.endsWith(".encoder") -> true
            n.endsWith(".decoder") -> false
            n.contains(".encoder.") -> true
            n.contains(".decoder.") -> false
            else -> false
        }
    }

    /**
     * Groups the reports by (codec label, kind), sorted label ascending
     * (AVC before HEVC) with decoders before encoders within a codec;
     * the codecs inside a group keep the probe's encounter order.
     */
    fun group(reports: List<CodecReport>): List<CodecGroup> =
        reports
            .groupBy { codecLabel(it.mime) to isEncoder(it.name) }
            .map { (key, codecs) -> CodecGroup(label = key.first, encoders = key.second, codecs = codecs) }
            .sortedWith(compareBy({ it.label }, { it.encoders }))

    /**
     * The group header text: "{CODEC} · Decoders"/"{CODEC} · Encoders"
     * from the localized format strings ("%1$s · Decoders").
     */
    fun headerText(
        group: CodecGroup,
        decodersFormat: String,
        encodersFormat: String,
    ): String =
        if (group.encoders) {
            encodersFormat.format(group.label)
        } else {
            decodersFormat.format(group.label)
        }

    /**
     * The copy text (spec): the full codec list as plain text — one codec
     * name per line, each group introduced by its header line.
     */
    fun plainText(
        groups: List<CodecGroup>,
        decodersFormat: String,
        encodersFormat: String,
    ): String = groups.joinToString("\n") { group ->
        headerText(group, decodersFormat, encodersFormat) +
            "\n" +
            group.codecs.joinToString("\n") { it.name }
    }
}
