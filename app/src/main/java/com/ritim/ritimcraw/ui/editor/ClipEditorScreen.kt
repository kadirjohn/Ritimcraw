package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.ui.theme.RitimTheme
import com.ritim.ritimcraw.ui.theme.Tnum

/**
 * Clip editor (DESIGN.md Editor): preview placeholder on
 * surface-container-lowest, Light/Color/Detail tabs, Light tab with
 * Exposure / Highlights / Temperature / Tint sliders.
 *
 * The chrome uses the fixed neutral scheme (RitimTheme(editor = true)) so it
 * never biases color judgment. Double-tap-to-reset on sliders is deferred to
 * the gesture pass (recorded in the task report).
 */
@Composable
fun ClipEditorScreen(
    sourceId: String,
    onBack: () -> Unit,
    initialGrade: GradeK = GradeK(),
    onGradeChange: (GradeK) -> Unit = {},
) {
    RitimTheme(editor = true) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var grade by remember(sourceId) { mutableStateOf(initialGrade) }

        Column(Modifier.fillMaxSize()) {
            // Top bar: back + clip id.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.editor_back),
                    )
                }
                Text(
                    text = sourceId,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            // Preview placeholder (engine lands in Task 4+).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.editor_preview_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            // Tabs: Light / Color / Detail.
            TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth()) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.editor_tab_light)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.editor_tab_color)) },
                )
                Tab(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    text = { Text(stringResource(R.string.editor_tab_detail)) },
                )
            }
            // Panel with sliders (Light tab complete; Color/Detail deferred).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                when (tab) {
                    0 -> LightPanel(
                        grade = grade,
                        onChange = { g ->
                            grade = g
                            onGradeChange(g)
                        },
                    )
                    else -> Text(
                        // Color/Detail panels land with their controls (sprint 2).
                        stringResource(R.string.editor_preview_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                    )
                }
            }
        }
    }
}

/** Light tab: Exposure / Highlights / Temperature / Tint (DESIGN.md Editor). */
@Composable
private fun LightPanel(
    grade: GradeK,
    onChange: (GradeK) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GradeSlider(
            label = stringResource(R.string.editor_slider_exposure),
            value = grade.exposureEv,
            valueRange = -5f..5f,
            valueLabel = stringResource(R.string.editor_value_ev, grade.exposureEv),
            onChange = { v -> onChange(grade.copy(exposureEv = v)) },
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_highlights),
            value = grade.highlights,
            valueRange = -100f..100f,
            valueLabel = stringResource(R.string.editor_value_signed, grade.highlights.toInt()),
            onChange = { v -> onChange(grade.copy(highlights = v)) },
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_temperature),
            value = grade.tempK,
            valueRange = 2000f..50000f,
            valueLabel = stringResource(R.string.editor_value_kelvin, grade.tempK.toInt()),
            onChange = { v -> onChange(grade.copy(tempK = v)) },
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_tint),
            value = grade.tint,
            valueRange = -100f..100f,
            valueLabel = stringResource(R.string.editor_value_signed, grade.tint.toInt()),
            onChange = { v -> onChange(grade.copy(tint = v)) },
        )
    }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            // tnum for EV / Kelvin / signed values (DESIGN.md Tabular numbers).
            Text(
                valueLabel,
                style = MaterialTheme.typography.labelLarge.merge(Tnum),
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
