package com.ritim.ritimcraw.engine

import android.util.Log
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.data.GradeK
import kotlinx.coroutines.delay

/**
 * Pure fps-over-a-window math with an INJECTED clock (owner request
 * 2026-11-10 section 1: playback FPS over a 5 s window). The unit tests
 * feed fake timestamps — no real clock, no Android classes, so this stays
 * JVM-testable.
 *
 * A frame "counts" when its render COMPLETES; stamps strictly older than
 * now - window drop off the front (the stamp exactly window-old stays).
 */
class FpsWindow(private val windowMs: Long = PreviewMetrics.PLAYBACK_WINDOW_MS) {

    private val stamps = ArrayDeque<Long>()

    /** Records one completed frame at [nowMs] (caller's monotonic clock). */
    fun onFrame(nowMs: Long) {
        stamps.addLast(nowMs)
    }

    /** Drops stamps that left the window at [nowMs]. */
    private fun prune(nowMs: Long) {
        val cutoff = nowMs - windowMs
        while (stamps.isNotEmpty() && stamps.first() < cutoff) stamps.removeFirst()
    }

    /** Frames completed inside the window at [nowMs]. */
    fun count(nowMs: Long): Int {
        prune(nowMs)
        return stamps.size
    }

    /**
     * Frame rate over the OBSERVED span: intervals between the oldest
     * in-window stamp and [nowMs]. 0.0 with fewer than two stamps or a
     * non-positive span (never divides by zero).
     */
    fun fps(nowMs: Long): Double {
        prune(nowMs)
        if (stamps.size < 2) return 0.0
        val spanMs = nowMs - stamps.first()
        if (spanMs <= 0) return 0.0
        return (stamps.size - 1) * 1000.0 / spanMs
    }

    /** Clears all recorded frames (between runs). */
    fun reset() = stamps.clear()
}

/**
 * Pure summary formatting for the harness's logcat block (JVM-testable:
 * every referenced constant is a compile-time const, no Android classes).
 */
object MetricsSummary {

    fun block(
        openMs: Long,
        firstFrameMs: Long,
        sliderLatenciesMs: List<Long>,
        playbackFrames: Int,
        playbackWindowMs: Long,
        playbackFps: Double,
    ): String = buildString {
        appendLine("=== ritim preview metrics (${PreviewMetrics.TAG}) ===")
        appendLine("session open: $openMs ms")
        appendLine("first frame, open -> published: $firstFrameMs ms")
        appendLine(
            "slider change -> image, ${sliderLatenciesMs.size}x cached " +
                "${SliderTiers.DRAG_MAX_DIM}px: " +
                sliderLatenciesMs.joinToString(" ") { "$it ms" }
        )
        appendLine(
            "playback: $playbackFrames frames in $playbackWindowMs ms = " +
                String.format(java.util.Locale.ROOT, "%.1f", playbackFps) +
                " fps @ ${PreviewTiers.PLAYBACK_MAX_DIM}px"
        )
        append("=== end preview metrics ===")
    }
}

/**
 * Debug-only latency/fps measurement harness (owner request 2026-11-10
 * section 1). The Measure action in Settings runs [measure] over the first
 * imported clip and logs ONE summary block under the "ritim-pm" tag:
 *
 *   adb logcat -s ritim-pm        (live)
 *   adb logcat -d -s ritim-pm     (dump after a run)
 *
 * Everything runs through the SAME persistent-session path the editor uses
 * (PreviewSession on the shared preview engine thread), so the numbers
 * reflect the real pipeline:
 *  1. session open + first frame at the paused tier (open -> published),
 *  2. five grade-only re-renders through the cached CFA at the drag tier
 *     (the interactive slider path — decode skipped, only the grade moved),
 *  3. five seconds of pts-paced playback at the playback tier, counting
 *     completed frames for the fps window (drop-late, like the editor),
 *  4. GLES capability probe + single-frame demosaic parity (owner request
 *     2026-11-12, deliverable C): the device entry point for the GPU
 *     groundwork. The probe always runs (an honest no-context report is
 *     a result); parity runs only when the probe's compute gate is green,
 *     on the SAME clip, against the GlesShaderSources shader text. The
 *     verdict feeds the render-backend gates (BackendGates.parityOk) —
 *     nothing about the render path changes on its own.
 */
object PreviewMetrics {

    const val TAG = "ritim-pm"

    /** Wall-clock window the playback leg measures FPS over. */
    const val PLAYBACK_WINDOW_MS = 5000L

    /** Grade-only re-renders the slider leg measures. */
    const val SLIDER_STEPS = 5

    /** Exposure nudge per measured slider step (a realistic drag). */
    const val SLIDER_STEP_EV = 0.25f

    /** Debug builds only — the harness never runs or logs in release. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /**
     * Runs the scripted measurement over the clip at [fd]. TAKES OWNERSHIP
     * of [fd] (closed here, always — success, failure, or cancellation).
     * Returns the summary text (also logged under [TAG]), or null when
     * disabled, the clip cannot be opened, or the first frame fails; the
     * reason is logged either way.
     */
    suspend fun measure(fd: Int): String? {
        if (!enabled) {
            PreviewRenderer.closeFdQuietly(fd)
            return null
        }
        val tOpen0 = System.currentTimeMillis()
        val opened = PreviewSession.open(fd, PreviewRenderer.previewEngine)
        val session = opened.getOrNull()
        // The session dup'd the fd natively; the caller's copy closes here on
        // both branches (the editor's open path follows the same rule).
        PreviewRenderer.closeFdQuietly(fd)
        if (session == null) {
            Log.w(TAG, "measure aborted: open failed: ${opened.exceptionOrNull()?.message}")
            return null
        }
        try {
            return runScript(session, tOpen0)
        } finally {
            session.close()
        }
    }

    private suspend fun runScript(session: PreviewSession, tOpen0: Long): String? {
        // ---- leg 1: session open + first frame at the paused tier ----
        val openMs = System.currentTimeMillis() - tOpen0
        val grade = GradeK()
        val first = session.render(0, grade, PreviewTiers.PAUSED_MAX_DIM)
        if (first.isFailure) {
            Log.w(TAG, "measure aborted: first frame failed: ${first.exceptionOrNull()?.message}")
            return null
        }
        // First-frame time: session open START -> first frame published.
        val firstFrameMs = System.currentTimeMillis() - tOpen0

        // ---- leg 2: grade-only slider steps through the cached CFA ----
        // Warm the cache once (the first cached render decodes + fills it);
        // the measured steps are then the steady-state grade-only path the
        // editor's drags take after the first step.
        session.renderCached(0, grade, SliderTiers.DRAG_MAX_DIM)
        var dragGrade = grade
        val sliderLatencies = ArrayList<Long>(SLIDER_STEPS)
        repeat(SLIDER_STEPS) {
            dragGrade = dragGrade.copy(exposureEv = dragGrade.exposureEv + SLIDER_STEP_EV)
            val t0 = System.currentTimeMillis()
            val r = session.renderCached(0, dragGrade, SliderTiers.DRAG_MAX_DIM)
            if (r.isSuccess) sliderLatencies.add(System.currentTimeMillis() - t0)
        }

        // ---- leg 3: pts-paced playback for the fps window ----
        // Same pacing as the editor's playback loop: pick the frame whose
        // pts is closest to the wall clock, render it, count it when it
        // lands, wait for the next frame's pts. Frames the wall clock has
        // already passed are skipped (drop-late), so a slow pipeline shows
        // up as a lower fps, never as a backlog.
        var playbackFrames = 0
        var playbackFps = 0.0
        if (session.frames > 1) {
            val pts = LongArray(session.frames)
            for (i in pts.indices) pts[i] = session.framePtsUs(i)
            val window = FpsWindow(PLAYBACK_WINDOW_MS)
            val startUs = System.nanoTime() / 1000
            var lastIdx = -1
            while (true) {
                val wallUs = System.nanoTime() / 1000 - startUs
                if (wallUs >= PLAYBACK_WINDOW_MS * 1000) break
                val elapsed = PtsTimeline.elapsedAt(pts, 0, wallUs)
                val idx = PtsTimeline.frameAt(pts, elapsed)
                if (idx >= 0 && idx != lastIdx) {
                    val r = session.render(idx, grade, PreviewTiers.PLAYBACK_MAX_DIM)
                    if (r.isSuccess) {
                        window.onFrame(System.currentTimeMillis())
                        playbackFrames++
                    }
                    lastIdx = idx
                }
                if (idx >= pts.size - 1) break // clip shorter than the window
                val nextDelay = PtsTimeline.delayUntilNextUs(pts, idx, elapsed)
                if (nextDelay > 0) delay(nextDelay / 1000) else delay(1)
            }
            playbackFps = window.fps(System.currentTimeMillis())
        }

        val summary = MetricsSummary.block(
            openMs = openMs,
            firstFrameMs = firstFrameMs,
            sliderLatenciesMs = sliderLatencies,
            playbackFrames = playbackFrames,
            playbackWindowMs = PLAYBACK_WINDOW_MS,
            playbackFps = playbackFps,
        )
        Log.i(TAG, summary)
        return summary
    }

    /**
     * GLES leg (deliverable C): capability probe + single-frame demosaic
     * parity, stateless (no handle, no render-cache touch — the probes
     * are safe from any thread, so the Measure runner runs them off the
     * engine thread). The fd is dup'd natively for the parity frame
     * decode; the caller's copy closes here, always. Returns the logged
     * block (also logged under [TAG]), or null when the leg is skipped
     * (disabled, or the clip cannot be opened for parity).
     *
     * Device-test-pending: no phone has run this leg; the first real run
     * IS the owner's device test.
     */
    internal suspend fun measureGles(fd: Int): String? {
        if (!enabled) {
            PreviewRenderer.closeFdQuietly(fd)
            return null
        }
        val lines = ArrayList<String>()
        try {
            // Leg 4a: capability probe (an honest no-context report is a
            // result, never an error).
            val probeRaw = NativeSource.jniGlesProbe()
            if (probeRaw == null) {
                lines += "probe: jni failure (${NativeSource.previewLastError() ?: "no detail"})"
                Log.i(TAG, GpuParityRules.block(lines))
                return GpuParityRules.block(lines)
            }
            val probe = GlesProbeResult.fromArray(probeRaw)
            lines += GpuParityRules.probeLine(probe)

            // Leg 4b: parity — only when the context + compute gates are
            // green (running the parity shader on a device without compute
            // would only produce a compile error we can already predict).
            // Audit fix 2026-11-13: the probe now reads the per-axis
            // dispatch limits the indexed way (glGetIntegeri_v), so a
            // compute=0 here is a REAL device fact, not the old query
            // artifact that misreported compute-capable hardware.
            if (!probe.contextOk || !probe.computeOk) {
                lines += GpuParityRules.skippedParityLine(
                    if (!probe.contextOk) "no gles context" else "no compute (indexed probe)",
                )
                Log.i(TAG, GpuParityRules.block(lines))
                return GpuParityRules.block(lines)
            }
            val parityRaw = NativeSource.jniGlesParity(
                fd, GlesShaderSources.DEMOSAIC_COMPUTE,
            )
            val parity = if (parityRaw != null) {
                GlesParityResult.fromArray(parityRaw)
            } else {
                lines += "parity: jni failure (${NativeSource.previewLastError() ?: "no detail"})"
                null
            }
            if (parity != null) {
                lines += GpuParityRules.parityLine(parity)
                lines += GpuParityRules.verdictLine(
                    GpuParityRules.verdict(parity, NativeSource.previewLastError()),
                )
            }
        } catch (e: IllegalStateException) {
            lines += "probe: native unavailable (${e.message})"
        } finally {
            PreviewRenderer.closeFdQuietly(fd)
        }
        val block = GpuParityRules.block(lines)
        Log.i(TAG, block)
        return block
    }
}
