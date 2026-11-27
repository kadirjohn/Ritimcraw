package com.ritim.ritimcraw.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.export.CubeExportPlan
import java.util.Locale

/**
 * G5: the Look tab's ".cube LUT export" section (the E7 core's product
 * face). STATELESS over its inputs — the section renders what it is
 * handed and reports every action upward; the screen (ClipEditorScreen)
 * owns the export state, the SAF picker and the JNI adapter, exactly the
 * way the LUT section owns only its rows and AppNav owns the stores.
 *
 * WHAT THE USER SEES (top to bottom, the Look tab's own section rhythm —
 * a labelLarge header, then the controls, the NodeLutPage/Look-section
 * precedent):
 *   - the section header;
 *   - the grid choice (SegmentedChoice, the NodeOutputPage Display row's
 *     own control — 33 vs 65 lattice points, the two sizes the E7 core
 *     report measured);
 *   - the INPUT SPACE line — "Input: linear Rec.709", always visible:
 *     the honest label of what the cube expects on its input axes (the
 *     E7 owner decision; a video-LUT consumer must know the cube is NOT
 *     display-encoded before they apply it);
 *   - the size line ("≈ 7.9 MB") and the ORDER NOTE (what the bake
 *     carries and what it cannot: denoise/sharpen/geometry are excluded
 *     — never a silent promise of "the whole look");
 *   - the Save button (FilledTonalButton, full width, the LUT import
 *     button's own style — a secondary action, not the page's primary);
 *   - the state line: idle/working/done/refused, the refusal naming
 *     its reason (never a bare "failed").
 *
 * STATE FLOW: the section takes the whole [CubeExportPlan.State] and the
 * callbacks ([onGridSelect], [onSave]); the screen drives the actual
 * bake (JNI + SAF write) and hands the new state back, so the section
 * never touches the engine and stays preview-testable as a pure tree.
 *
 * TOUCH TARGETS: the segmented cells are SegmentedChoice's own 48dp
 * cells; the Save button rides FilledTonalButton's own min-height row;
 * the note and state lines are text (not targets). The whole section is
 * ADDITIVE to the Look tab — no existing control moved, renamed or
 * re-ordered.
 *
 * ANIMATION: no new motion language. The section's own appearance is the
 * Look tab's plain column (the tab body does not animate its sections
 * in — the LUT section and the Tone Mapping section appear statically,
 * which is the pattern copied here). The busy state is a stock
 * CircularProgressIndicator inside the button's row (the same still
 * indicator the export sheet's rows use while a job runs), and the state
 * line swaps as plain text — the existing lutImportError line's own
 * behavior (ClipEditorScreen's LUT section error Text, no
 * AnimatedVisibility), copied verbatim.
 */
@Composable
internal fun CubeExportSection(
    grid: CubeExportPlan.Grid,
    state: CubeExportPlan.State,
    clipName: String,
    onSave: () -> Unit,
    onGridSelect: (CubeExportPlan.Grid) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            stringResource(R.string.cube_export_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Column(Modifier.padding(horizontal = 0.dp)) {
            Text(
                stringResource(R.string.cube_export_grid),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            SegmentedChoice(
                labels = listOf(
                    stringResource(R.string.cube_export_grid_33),
                    stringResource(R.string.cube_export_grid_65),
                ),
                selectedIndex = grid.ordinal,
                onSelect = { onGridSelect(CubeExportPlan.Grid.entries[it]) },
            )
        }
        // The honest contract lines. The input space is the one the E7
        // bake declares (scene-referred linear Rec.709, the pipeline's
        // own working space) — stated so a video-LUT consumer never
        // mistakes the cube for a display-encoded one.
        Text(
            stringResource(R.string.cube_export_input_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.cube_export_size,
                CubeExportPlan.sizeReadout(grid, CubeExportPlan.title(clipName).isNotEmpty()),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.cube_export_order_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Save: the section's only action, full width (the LUT import
        // button's own placement, the Look tab's own rhythm).
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalButton(
                onClick = onSave,
                enabled = state !is CubeExportPlan.State.Working,
                modifier = Modifier.weight(1f),
            ) {
                if (state is CubeExportPlan.State.Working) {
                    CircularProgressIndicator(
                        Modifier
                            .padding(end = 8.dp)
                            .size(18.dp, 18.dp),
                        strokeWidth = 2.dp,
                    )
                }
                Text(stringResource(R.string.cube_export_save))
            }
        }
        // The state line: idle/working/done/refused, the refusal with
        // its own reason. The refused lines use the theme's error color
        // (the lutImportError precedent); done uses the default surface
        // text color so success never reads as a warning.
        when (state) {
            CubeExportPlan.State.Idle -> Text(
                stringResource(R.string.cube_export_state_idle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CubeExportPlan.State.Working -> Text(
                stringResource(R.string.cube_export_state_working),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is CubeExportPlan.State.Done -> Text(
                stringResource(
                    R.string.cube_export_state_done,
                    String.format(Locale.ROOT, "%.1f MB", state.bytes / (1024.0 * 1024.0)),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is CubeExportPlan.State.Refused -> {
                val refusal = state.refusal
                when (refusal) {
                    CubeExportPlan.Refusal.EngineUnavailable -> Text(
                        stringResource(R.string.cube_export_state_engine),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is CubeExportPlan.Refusal.ChainRefused -> Text(
                        stringResource(R.string.cube_export_state_chain, refusal.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is CubeExportPlan.Refusal.WriteFailed -> Text(
                        stringResource(R.string.cube_export_state_write, refusal.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is CubeExportPlan.Refusal.BakeRefused -> Text(
                        stringResource(R.string.cube_export_state_bake, refusal.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
