package com.ritim.ritimcraw.ui.editor

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.engine.NativeSource
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.engine.PreviewFrame
import com.ritim.ritimcraw.engine.PreviewRenderer
import com.ritim.ritimcraw.engine.RenderDebounce
import com.ritim.ritimcraw.ui.theme.RitimTheme
import com.ritim.ritimcraw.ui.theme.Tnum
import com.ritim.ritimcraw.util.Format
import kotlinx.coroutines.delay

/**
 * Pure preview-area decision logic (JVM-testable without Compose): which
 * state the preview area shows, and whether rendering is possible at all.
 */
object EditorPreviewLogic {

    /**
     * State for the preview area given the render bookkeeping. A failed
     * render keeps the last good frame when there is one (grading continues
     * on the stale frame rather than yanking the image away); with no frame
     * at all the SAME placeholder shows as before this path existed.
     */
    fun stateOf(
        renderRequested: Boolean,
        renderInFlight: Boolean,
        hasFrame: Boolean,
        lastRenderFailed: Boolean,
    ): PreviewState = when {
        renderInFlight -> PreviewState.RENDERING
        hasFrame -> PreviewState.FRAME
        renderRequested && lastRenderFailed -> PreviewState.FAILED
        else -> PreviewState.IDLE
    }

    /**
     * True when the preview path can run: a usable fd AND the native
     * engine present. When false the placeholder shows and the render
     * button stays disabled — the editor is still a working grade panel.
     */
    fun canRender(fd: Int, nativeAvailable: Boolean): Boolean = fd >= 0 && nativeAvailable
}

enum class PreviewState { IDLE, RENDERING, FRAME, FAILED }

/**
 * Clip editor (DESIGN.md Editor): preview frame on
 * surface-container-lowest with a subtle outline border, Light/Color/Detail
 * tabs, Light tab with Exposure / Highlights / Temperature / Tint sliders.
 *
 * Preview: the screen renders frame [frameIndex] through the native preview
 * pipeline (PreviewRenderer) — once on open, then after the 300 ms trailing
 * debounce on every slider edit (RenderDebounce.WINDOW_MS). Degrades
 * gracefully: with no fd ([sourceFd] < 0), no native engine, or a failed
 * render, the SAME placeholder shows as before this path existed — grading
 * still works, never a crash.
 *
 * The chrome uses the fixed neutral scheme (RitimTheme(editor = true)) so it
 * never biases color judgment. Sliders snap to their step grid (0.1 EV
 * exposure, 50 K temperature) and a double tap on a slider row resets that
 * one control to its GradeK default (DESIGN.md Inputs).
 */
@Composable
fun ClipEditorScreen(
    sourceId: String,
    onBack: () -> Unit,
    initialGrade: GradeK = GradeK(),
    onGradeChange: (GradeK) -> Unit = {},
    onExport: (Target) -> Unit = {},
    sourceFd: Int = -1,
    frameIndex: Int = 0,
    accessLost: Boolean = false,
) {
    RitimTheme(editor = true) {
        var tab by rememberSaveable { mutableStateOf(0) }
        var grade by remember(sourceId) { mutableStateOf(initialGrade) }

        // Preview bookkeeping. renderSeq is the request counter: bumping it
        // triggers the render effect; results whose seq went stale are
        // dropped so an old frame can never overwrite a newer one.
        var frame by remember(sourceId) { mutableStateOf<PreviewFrame?>(null) }
        var renderSeq by remember(sourceId) { mutableStateOf(0L) }
        var renderInFlight by remember(sourceId) { mutableStateOf(false) }
        var lastRenderFailed by remember(sourceId) { mutableStateOf(false) }
        var errorText by remember(sourceId) { mutableStateOf<String?>(null) }
        val nativeAvailable = NativeSource.nativeAvailable
        val canRender = EditorPreviewLogic.canRender(sourceFd, nativeAvailable)

        // First render: once, when the editor opens with a usable fd.
        LaunchedEffect(sourceFd, nativeAvailable) {
            if (canRender) renderSeq += 1
        }

        // Slider edits re-render after the trailing debounce window.
        LaunchedEffect(grade, sourceFd) {
            if (renderSeq == 0L || !canRender) return@LaunchedEffect
            delay(RenderDebounce.WINDOW_MS)
            renderSeq += 1
        }

        // The render itself: one open -> render -> close per request on the
        // shared preview engine thread.
        LaunchedEffect(renderSeq) {
            if (renderSeq == 0L || !canRender) return@LaunchedEffect
            val seq = renderSeq
            renderInFlight = true
            val result = PreviewRenderer.render(
                sourceFd = sourceFd,
                frameIndex = frameIndex,
                grade = grade,
                maxDim = 1280,
            )
            if (!RenderDebounce.isFresh(seq, renderSeq)) {
                // A newer request superseded this one mid-flight; its own
                // effect run will publish the fresh result.
                return@LaunchedEffect
            }
            result.fold(
                onSuccess = { f ->
                    frame = f
                    lastRenderFailed = false
                    errorText = null
                },
                onFailure = { e ->
                    lastRenderFailed = true
                    errorText = e.message
                },
            )
            renderInFlight = false
        }

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
            // Preview frame: rendered image, or the placeholder this area has
            // always shown (no fd / no native engine / render failure all
            // degrade to the same string — never a crash, never a blank hole).
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    // The tested decision matrix drives the overlay: FAILED
                    // (requested, no frame, not in flight) shows the error;
                    // RENDERING with a stale frame keeps the image up.
                    val state = EditorPreviewLogic.stateOf(
                        renderRequested = renderSeq > 0L,
                        renderInFlight = renderInFlight,
                        hasFrame = frame != null,
                        lastRenderFailed = lastRenderFailed,
                    )
                    val f = frame
                    if (f != null) {
                        Image(
                            bitmap = remember(f) { f.toImageBitmap() },
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (accessLost) {
                        // The SAF grant was revoked or the clip moved: the
                        // existing access-lost pattern (library rows) — the
                        // preview cannot run without the source.
                        Column(
                            Modifier.padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                stringResource(R.string.editor_not_loaded_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                stringResource(R.string.library_row_access_lost),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    } else {
                        Text(
                            stringResource(R.string.editor_preview_device_pending),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(32.dp),
                        )
                    }
                    if (state == PreviewState.FAILED && errorText != null) {
                        Text(
                            errorText!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                        )
                    }
                }
            }
            // Render affordance (DESIGN.md: tonal button for a secondary
            // action) + the Export actions (filled button for the one
            // primary action DNG, tonal for the secondary MP4). Export is
            // offered whenever the clip is loaded — the job snapshots the
            // CURRENT grade, so a queue-time snapshot of the user's edit is
            // what gets exported; the queue runs it when the engine is
            // available. Disabled without a fd (nothing to export).
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = { renderSeq += 1 },
                    enabled = canRender,
                ) {
                    Text(stringResource(R.string.editor_render_preview))
                }
                // Export: DNG filled (primary), MP4 tonal (secondary).
                Button(
                    onClick = { onExport(Target.DNG) },
                    enabled = sourceFd >= 0,
                ) {
                    Text(stringResource(R.string.editor_export_dng))
                }
                FilledTonalButton(
                    onClick = { onExport(Target.MP4) },
                    enabled = sourceFd >= 0,
                ) {
                    Text(stringResource(R.string.editor_export_mp4))
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

/** PreviewFrame -> Compose ImageBitmap via Android Bitmap (UI layer only). */
private fun PreviewFrame.toImageBitmap(): androidx.compose.ui.graphics.ImageBitmap =
    Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()

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
            step = SliderSteps.EXPOSURE_STEP,
            valueLabel = Format.formatEv(grade.exposureEv),
            onChange = { v -> onChange(grade.copy(exposureEv = SliderSteps.snapExposure(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.EXPOSURE, grade))
            },
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_highlights),
            value = grade.highlights,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.highlights.toInt()),
            onChange = { v -> onChange(grade.copy(highlights = SliderSteps.snapHighlights(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.HIGHLIGHTS, grade))
            },
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_temperature),
            value = grade.tempK,
            valueRange = 2000f..50000f,
            step = SliderSteps.TEMP_STEP_K,
            valueLabel = Format.formatKelvin(grade.tempK.toInt()),
            onChange = { v -> onChange(grade.copy(tempK = SliderSteps.snapTemperature(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.TEMPERATURE, grade))
            },
        )
        GradeSlider(
            label = stringResource(R.string.editor_slider_tint),
            value = grade.tint,
            valueRange = -100f..100f,
            step = SliderSteps.UNIT_STEP,
            valueLabel = Format.formatSigned(grade.tint.toInt()),
            onChange = { v -> onChange(grade.copy(tint = SliderSteps.snapTint(v))) },
            onDoubleTapReset = {
                onChange(SliderSteps.resetToDefault(SliderSteps.Axis.TINT, grade))
            },
        )
    }
}

@Composable
private fun GradeSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    valueLabel: String,
    onChange: (Float) -> Unit,
    onDoubleTapReset: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            // Double tap anywhere on the slider row resets this control to
            // its GradeK default (DESIGN.md Inputs: "double tap resets").
            .pointerInput(label) {
                detectTapGestures(onDoubleTap = { onDoubleTapReset() })
            },
    ) {
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
            steps = SliderSteps.composeSteps(valueRange, step),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
