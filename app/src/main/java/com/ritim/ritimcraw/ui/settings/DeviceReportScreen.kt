package com.ritim.ritimcraw.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.engine.CodecReport
import com.ritim.ritimcraw.ui.components.ArrowBack
import com.ritim.ritimcraw.ui.components.Copy
import com.ritim.ritimcraw.ui.components.Cpu
import com.ritim.ritimcraw.ui.components.SettingsGroupItem
import kotlinx.coroutines.launch

/**
 * Device report screen (owner spec 2026-11-14, device_report_screen; v2
 * polish C5+C6, 2026-11-16): the full-screen replacement for the codec
 * AlertDialog. Same data source as the dialog — AppNav's one
 * CodecProbe.probe() per Settings-tab composition, shared with the
 * Settings row — only the container changed.
 *
 * Layout (v2 polish spec, verbatim geometry — EXCEPT the title, see the
 * top-bar note):
 * - top bar: back IconButton (arrow_back 24dp,
 *   contentDescription "Back"), title "Device report", trailing Copy
 *   IconButton (copy icon 22dp, contentDescription "Copy report");
 *   padding horizontal 8dp. TITLE (owner request 2026-11-17, overruling
 *   the v2 spec's 22sp SemiBold: "hepsini library projects exports gibi
 *   yap"): ScreenHeader's headlineSmall size and vertical rhythm (top
 *   12dp / bottom 8dp, Top-aligned — the ScreenHeader 48dp-child rule),
 *   so the title sits at the same size and y as every other screen
 *   title. The app shell's Scaffold already applies the status-bar
 *   inset through its innerPadding (default contentWindowInsets), so
 *   the 12dp here sits below that inset. System back and the back
 *   arrow are unchanged.
 * - summary card (C5, fixed above the tabs, outside the pager): no
 *   border; container surfaceContainer, shape 20dp; margin 16dp
 *   horizontal / 8dp top / 12dp bottom; padding 14dp vertical x 16dp
 *   horizontal; a 48dp x 48dp tile (14dp corners, primaryContainer) with
 *   the cpu icon at 24dp on the left; to its right the codec count at
 *   28sp SemiBold and "video codecs on this device" 13sp onSurfaceVariant
 *   on the same baseline row, gap 8dp between the row's elements.
 * - tabs (C5): [PrimaryTabRow] under the summary card — 3 tabs (All /
 *   Decoders / Encoders), container transparent (the screen's surface
 *   shows through), label 14sp Medium; the selected label is
 *   colorScheme.primary SemiBold, the unselected onSurfaceVariant. The
 *   stock Material indicator, the stock divider (PrimaryTabRow's own
 *   default divider param — the outline policy's allowed stock case, not
 *   a call in this file) and the default tab ripple are used. Tab click
 *   animates the pager to the page.
 * - pager (C5): [HorizontalPager] below the tab row filling the rest of
 *   the screen. val pagerState = rememberPagerState { 3 } (saveable —
 *   DefaultPagerState carries its own Saver, which replaces the v1
 *   filterIndex's rememberSaveable). selectedTabIndex =
 *   pagerState.currentPage. One swipe = one category: the default
 *   snapping, constrained explicitly with PagerDefaults.flingBehavior(
 *   state = pagerState, pagerSnapDistance = PagerSnapDistance.atMost(1)).
 *   No custom page size, no free-scroll. Each page has its OWN
 *   LazyColumn with its own LazyListState (rememberLazyListState() per
 *   page inside the pager's page lambda), so scroll position persists
 *   per tab. Pages: 0 = all codecs, 1 = decoders only, 2 = encoders
 *   only. Edge swipes that start at the screen edge still trigger the
 *   system back gesture: the pager only intercepts drags within its own
 *   bounds and nothing here consumes system gesture insets.
 * - the list (C6): each pager page is a LazyColumn (contentPadding
 *   bottom 32dp). No outline box, no header band, no dividers. Per
 *   group: a header that is a plain text row on the screen background —
 *   12sp SemiBold ALL CAPS letterSpacing 0.08em onSurfaceVariant,
 *   padding start/end 20dp, top 20dp, bottom 8dp, no container/fill;
 *   text format unchanged ("AVC · Decoders", uppercased). Items: the
 *   same segmented-item component as C1 ([SettingsGroupItem], index and
 *   count computed PER GROUP), horizontal margin 16dp, 2dp gap,
 *   container surfaceContainer, padding 16dp horizontal x 12dp vertical.
 *   Item content unchanged from v1: the FULL codec name in
 *   FontFamily.Monospace 13sp (wraps, never truncated), then a FlowRow
 *   of pills (HW or SW, Codec2 or OMX, Low latency). Pill neutral
 *   container = surfaceContainerHighest; HW pill container =
 *   primaryContainer, text onPrimaryContainer. stickyHeader is not
 *   used, so no header background is needed.
 *
 * Kind derivation (an honest deviation, documented for the handback): the
 * spec says the filter uses "the existing codec info (isEncoder flag),
 * not from name parsing" — but CodecReport (engine/) carries NO isEncoder
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
 * line under the same group headers, the FULL list — the pager is a
 * view, the copy is the report) and hands it to [onCopyReport]; AppNav
 * owns the clipboard write and shows the shell's existing snackbar.
 *
 * The grouping/sorting/copy-text logic is pure ([DeviceReportGrouping])
 * and pinned by unit tests (DeviceReportGroupingTest) so correctness does
 * not depend on seeing the UI.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DeviceReportScreen(
    reports: List<CodecReport>,
    onBack: () -> Unit,
    onCopyReport: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }
    // The pager (C5): 3 pages — 0 = all, 1 = decoders, 2 = encoders.
    // rememberPagerState's DefaultPagerState is saveable, so the page
    // survives process death like the v1 filterIndex did.
    val pagerState = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val allGroups = remember(reports) { DeviceReportGrouping.group(reports) }
    // The group header formats ("%1$s · Decoders") resolved once; the pure
    // header/copy builders below format the labels with them.
    val decodersFormat = stringResource(R.string.settings_device_report_group_decoders)
    val encodersFormat = stringResource(R.string.settings_device_report_group_encoders)
    // Copy text (spec): the FULL codec list, one name per line, grouped by
    // the same headers — the pager does not change what gets copied.
    val copyText = remember(allGroups, decodersFormat, encodersFormat) {
        DeviceReportGrouping.plainText(allGroups, decodersFormat, encodersFormat)
    }
    // The tab labels, resolved once for both the tab row and the pages.
    val tabLabels = listOf(
        stringResource(R.string.settings_device_report_filter_all),
        stringResource(R.string.settings_device_report_filter_decoders),
        stringResource(R.string.settings_device_report_filter_encoders),
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- Top bar (title consistency, owner request 2026-11-17:
            // "hepsini library projects exports gibi yap" — every screen
            // title the same size at the same y) ----
            // The v2 spec's 22sp SemiBold title is overruled: the title
            // wears ScreenHeader's own headlineSmall and the row applies
            // ScreenHeader's vertical rhythm (top 12 / bottom 8) with
            // Top alignment — the exact shape ScreenHeader's KDoc
            // prescribes for a row with 48dp children: the back and copy
            // IconButtons' 48dp targets grow the row, and CenterVertically
            // would drop the 32dp title line ~8dp lower than the browse
            // screens; Top pins the title's line box at the row top in
            // both cases, so it lands at the same y as the Library title.
            // The 8dp horizontal padding stays (the buttons' inset, not
            // the title's); the title carries the row's weight +
            // single-line ellipsis like ScreenHeader's title.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
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
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onCopyReport(copyText) }) {
                    Icon(
                        imageVector = Copy,
                        contentDescription = stringResource(R.string.settings_device_report_copy),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            // ---- Summary card (C5: fixed above the tabs, outside the
            // pager). No border — a tonal surfaceContainer fill on the
            // surface background, 20dp shape, 16dp horizontal margin,
            // 8dp top / 12dp bottom, 14dp vertical x 16dp horizontal
            // padding. 48dp tile (14dp corners, primaryContainer) with
            // the cpu icon at 24dp; count 28sp SemiBold and the summary
            // text 13sp onSurfaceVariant on one baseline row, 8dp gaps.
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(14.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Cpu,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    // The count and the summary text share a baseline row
                    // (C5). alignByBaseline groups just these two texts;
                    // the tile keeps the row's CenterVertically.
                    Text(
                        text = reports.size.toString(),
                        style = TextStyle(
                            fontSize = 28.sp,
                            lineHeight = 32.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.alignByBaseline(),
                    )
                    Text(
                        text = stringResource(R.string.settings_device_report_summary),
                        style = TextStyle(
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.Normal,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            // ---- Tabs (C5): PrimaryTabRow under the summary card.
            // containerColor transparent — the screen's surface shows
            // through. The stock indicator, the stock divider (the
            // default divider param, drawn by the Material component
            // itself — the outline policy's allowed case) and the
            // default ripple; only the label typography and the two
            // content colors are set.
            PrimaryTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Color.Transparent,
                tabs = {
                    tabLabels.forEachIndexed { index, label ->
                        val selected = index == pagerState.currentPage
                        Tab(
                            selected = selected,
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(index)
                                }
                            },
                            text = {
                                // 14sp Medium, SemiBold when selected
                                // (C5); the tab's own ProvideTextStyle
                                // (TitleSmall) is overridden here.
                                Text(
                                    text = label,
                                    style = TextStyle(
                                        fontSize = 14.sp,
                                        lineHeight = 20.sp,
                                        fontWeight = if (selected) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Medium
                                        },
                                    ),
                                    maxLines = 1,
                                )
                            },
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            // ---- The pager (C5): one page per category, filling the
            // rest of the screen. One swipe = one category: default
            // snapping constrained explicitly with atMost(1); no custom
            // page size, no free-scroll. Each page owns its LazyColumn
            // and LazyListState, so scroll position persists per tab.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                flingBehavior = PagerDefaults.flingBehavior(
                    state = pagerState,
                    pagerSnapDistance = PagerSnapDistance.atMost(1),
                ),
            ) { page ->
                val pageGroups = when (page) {
                    1 -> allGroups.filter { !it.encoders }
                    2 -> allGroups.filter { it.encoders }
                    else -> allGroups
                }
                // Per-page list state (C5): remembered inside the page,
                // so each tab's scroll position survives page switches.
                // The pager keeps pages composed while swiping, so the
                // state is retained across the round trip.
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                ) {
                    pageGroups.forEach { group ->
                        item(key = "header-${group.label}-${group.encoders}") {
                            // Group header (C6): a PLAIN text row on the
                            // screen background — no container, no
                            // border, no fill. 12sp SemiBold ALL CAPS
                            // 0.08em onSurfaceVariant, padding 20/20/20/8.
                            // Text format unchanged from v1, uppercased.
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
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        start = 20.dp,
                                        top = 20.dp,
                                        end = 20.dp,
                                        bottom = 8.dp,
                                    ),
                            )
                        }
                        // The group's codec items: the same segmented-item
                        // component as C1, index/count computed PER GROUP,
                        // 16dp horizontal margin, 2dp gap (C6). One
                        // LazyColumn item holds the whole group's column
                        // so the 2dp gap and the per-group shape stay
                        // exact without spacing leaking into the header.
                        item(
                            key = "group-${group.label}-${group.encoders}",
                            contentType = "codec-group",
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                group.codecs.forEachIndexed { index, report ->
                                    SettingsGroupItem(
                                        index = index,
                                        count = group.codecs.size,
                                    ) {
                                        // Item content unchanged from v1
                                        // (C6): padding 16dp horizontal x
                                        // 12dp vertical (SettingsGroupItem
                                        // applies none of its own).
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = 16.dp,
                                                    vertical = 12.dp,
                                                )
                                        ) {
                                            // Line 1: the FULL codec name,
                                            // monospace, wrapping at any
                                            // character, never truncated
                                            // or ellipsized (no maxLines).
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
