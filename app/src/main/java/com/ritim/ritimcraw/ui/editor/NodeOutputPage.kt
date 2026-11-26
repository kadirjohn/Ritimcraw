package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.grade2.OpenDrtParamsV2
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import java.util.Locale

/**
 * F1-8d: the Output card's page (docs/F1_8_COLOR_UI_DESIGN.md section 3, "Output kartı" — the strip's
 * end, undeleteable). STATELESS over [output]: every change goes out as a new [OutputTransformV2] through
 * [onOutputChange], which the screen persists through the GradeStore.putOutput seam and re-renders.
 *
 * THE MODE SEGMENT (Legacy | OpenDRT) sits at the top; the branch below it:
 *  - Legacy: one hint line — the legacy tonemap + LUT row already lives in the Look sub-tab (ColorLookBody);
 *    the card does not duplicate it, so "v1 projeler hiç değişmez" keeps one owner for those controls.
 *  - OpenDRT: Look (a 7-name slider, core's look ordinals), Tonescale (14 — from-look + Klipp's list),
 *    Color weight/white point (cwp 0..6 + its limit), Grey (3..25 nit, half steps), Display (3-choice
 *    segment: sRGB 2.2 / Rec.1886 / HLG 1000).
 *
 * Look/tonescale/cwp values are NAME-LABELLED STEPPERS on GradeSlider (a 7-way and a 14-way segment row
 * would truncate on a 308dp panel; the slider's valueLabel carries the preset NAME like the hue degrees
 * it already labels); Display's three choices fit the node pages' own SegmentedChoice. Touch targets:
 * the segment cells and every slider row are 48dp+ (GradeSlider's own row shape).
 *
 * Names stay UNTRANSLATED deliberately (the AgX look names' convention in ColorLookBody — the upstream
 * OpenDRT look names, ACES and Klipp's own labels): only chrome strings localize.
 */
@Composable
internal fun NodeOutputPage(
    output: OutputTransformV2,
    onOutputChange: (OutputTransformV2) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    val latest by rememberUpdatedState(output)
    val latestChange by rememberUpdatedState(onOutputChange)
    fun edit(o: OutputTransformV2) = latestChange(o)
    val drt = output.mode == OutputTransformV2.MODE_OPENDRT
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            SegmentedChoice(
                labels = listOf(
                    stringResource(R.string.node_output_mode_legacy),
                    stringResource(R.string.node_output_mode_opendrt),
                ),
                selectedIndex = if (drt) 1 else 0,
                onSelect = { edit(NodeOutputOps.setMode(latest, if (it == 1) OutputTransformV2.MODE_OPENDRT else OutputTransformV2.MODE_LEGACY)) },
            )
        }
        if (!drt) {
            Text(
                stringResource(R.string.node_output_legacy_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            return@Column
        }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val p = output.opendrt
            GradeSlider(
                label = stringResource(R.string.node_output_look),
                // Steppers read at both ends; named values from the ops' locked ranges.
                value = p.look.toFloat(),
                valueRange = 0f..NodeOutputOps.LOOK_MAX.toFloat(),
                step = 1f,
                valueLabel = lookName(p.look),
                onChange = { edit(NodeOutputOps.setLook(latest, it.toInt())) },
                onDoubleTapReset = { edit(NodeOutputOps.setLook(latest, 0)) },
                onDragActiveChange = onDragActiveChange,
            )
            GradeSlider(
                label = stringResource(R.string.node_output_tonescale),
                value = p.tonescale.toFloat(),
                valueRange = 0f..NodeOutputOps.TONESCALE_MAX.toFloat(),
                step = 1f,
                valueLabel = tonescaleName(p.tonescale),
                onChange = { edit(NodeOutputOps.setTonescale(latest, it.toInt())) },
                onDoubleTapReset = { edit(NodeOutputOps.setTonescale(latest, 0)) },
                onDragActiveChange = onDragActiveChange,
            )
            GradeSlider(
                label = stringResource(R.string.node_output_cwp),
                value = p.cwp.toFloat(),
                valueRange = 0f..NodeOutputOps.CWP_MAX.toFloat(),
                step = 1f,
                valueLabel = cwpName(p.cwp),
                onChange = { edit(NodeOutputOps.setCwp(latest, it.toInt())) },
                onDoubleTapReset = { edit(NodeOutputOps.setCwp(latest, 0)) },
                onDragActiveChange = onDragActiveChange,
            )
            // The limit rides its own slider, only meaningful under a cwp choice (0 = look's own): shown
            // whenever a choice is active, quarter steps, the percent readout (0..1 as 0..100%).
            if (p.cwp != 0) {
                GradeSlider(
                    label = stringResource(R.string.node_output_cwp_limit),
                    value = p.cwpLimit,
                    valueRange = 0f..1f,
                    step = NodeOutputOps.CWP_LIMIT_STEP,
                    valueLabel = String.format(Locale.ROOT, "%d%%", (p.cwpLimit * 100f).toInt()),
                    onChange = { edit(NodeOutputOps.setCwpLimit(latest, it)) },
                    onDoubleTapReset = { edit(NodeOutputOps.setCwpLimit(latest, 0.25f)) },
                    onDragActiveChange = onDragActiveChange,
                )
            }
            GradeSlider(
                label = stringResource(R.string.node_output_grey),
                value = p.grey,
                valueRange = NodeOutputOps.GREY_MIN..NodeOutputOps.GREY_MAX,
                step = NodeOutputOps.GREY_STEP,
                valueLabel = NodeOutputOps.greyReadout(p.grey),
                onChange = { edit(NodeOutputOps.setGrey(latest, it)) },
                onDoubleTapReset = { edit(NodeOutputOps.setGrey(latest, OpenDrtParamsV2().grey)) },
                onDragActiveChange = onDragActiveChange,
            )
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.node_output_display),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            SegmentedChoice(
                labels = displayLabels(),
                selectedIndex = p.display,
                onSelect = { edit(NodeOutputOps.setDisplay(latest, it)) },
            )
        }
    }
}

/** The display choices: sRGB 2.2 (phone/web), Rec.1886 2.4 (TV), HLG 1000 — kept as technical names. */
@Composable
private fun displayLabels(): List<String> = listOf(
    stringResource(R.string.node_output_display_srgb),
    stringResource(R.string.node_output_display_1886),
    stringResource(R.string.node_output_display_hlg),
)

/**
 * The look names, untranslated (upstream OpenDRT's own): Standard, Arriba,
 * Sylvan, Colorful, Aery, Dystopic, Umbra (core OpenDrt.h's ordinal list).
 */
private fun lookName(look: Int): String = when (look) {
    1 -> "Arriba"; 2 -> "Sylvan"; 3 -> "Colorful"; 4 -> "Aery"; 5 -> "Dystopic"; 6 -> "Umbra"
    else -> "Standard"
}

/**
 * The tonescale names, untranslated (Klipp's TONESCALE list, core's ordinal
 * comments): 0 = from the look, then Low/Medium/High, the six look tonescales,
 * ACES 1.x, ACES 2.0, Marvelous Tonescape, DaGrinchi ToneGroan.
 */
private fun tonescaleName(ts: Int): String = when (ts) {
    1 -> "Low"; 2 -> "Medium"; 3 -> "High"; 4 -> "Arriba"; 5 -> "Sylvan"; 6 -> "Colorful"; 7 -> "Aery"
    8 -> "Dystopic"; 9 -> "Umbra"; 10 -> "ACES 1.x"; 11 -> "ACES 2.0"; 12 -> "Marvelous Tonescape"
    13 -> "DaGrinchi ToneGroan"
    else -> "From look"
}

/** The creative white point names, untranslated: look's own (0), then D93..D50 (core OpenDrt.h). */
private fun cwpName(cwp: Int): String = when (cwp) {
    1 -> "D93"; 2 -> "D75"; 3 -> "D65"; 4 -> "D60"; 5 -> "D55"; 6 -> "D50"
    else -> "From look"
}
