package com.ritim.ritimcraw.engine

import android.util.Log
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.data.GradeK

/**
 * The production render trace line (audit M0, 2026-11-13): ONE logcat
 * line per completed editor render under the stable "ritim-trace" tag,
 * debug-gated — the owner's A/B instrument on the phone. The audit's
 * host harness already measured the develop pipeline off-device
 * (17.5/61.8/62.4/107.9 ms at the 1280 tier for neutral / AgX /
 * +managed-LUT / +temporal-NR on the 4K fixture); this line carries the
 * SAME story from the production path — the real session, the real
 * grade, the real wall time — so the device numbers and the host
 * numbers can be compared leg for leg:
 *
 *   adb logcat -s ritim-trace       (live)
 *   adb logcat -d -s ritim-trace    (dump after a run)
 *
 * Every frame may log (the ritim-stab-timing precedent): the line is
 * one Log.i on the publish path, cheaper than the frame it describes,
 * and the owner's A/B reads a handful of parked frames per grade state.
 *
 * THE LINE (one, stable field order — the owner greps these):
 *   seq=<request sequence> frame=<index> class=<DRAG|SETTLE|PLAYBACK>
 *   req=<requested maxDim, 0=source> out=<actualWxactualH>
 *   backend=<cpu> ms=<render wall time>
 *   grade=<active stage summary>
 *
 * - out= logs the TRUTH, not the request: the native strideFor caps the
 *   long edge on integer Bayer-cell strides, so a 720 request on a
 *   3840x2160 clip develops 640x360 — the audit's exact finding, and
 *   the number the owner needs next to the requested one.
 * - backend= names the pipeline that ACTUALLY ran: gles when the GPU
 *   window path served the frame (M4 commit c — the SurfaceView draw
 *   with the stats-pack publish), cpu for every other shape (the CPU
 *   reference path, and the GPU path's CPU fallbacks). The default
 *   record stays cpu so every existing call site and the pre-GPU
 *   baseline legs keep their exact lines.
 * - grade= lists only the stages that are ACTIVE for this render, in
 *   pipeline order, each with its dial where one exists: vign/hlrec are
 *   the keep- booleans (named for what the stage DOES, true = applied),
 *   tonemap is off/aces/agx, lut names the selected id, nr is the
 *   spatial+chroma pair, tnr carries the class-gated truth (what the
 *   tier gate actually decided — the M0 SOURCE fix lands here), stab is
 *   on only when the grade asked AND a gyro log was attached. A neutral
 *   grade prints grade=neutral — the A/B baseline leg.
 * - GPU window frames (backend=gles ONLY — the CPU line is
 *   byte-identical to the pre-fix shape) append the measurement block
 *   after grade= (trace-dims fix 2026-11-14): source=<sensor WxH>
 *   develop=<actual developed WxH> surface=<window presented WxH>
 *   stats=<pack WxH|off> statsMode=<off|sync|async> (A1 async stats,
 *   2026-11-23) then dec/cfa/up/dev/present/stats=<ms, one decimal>.
 *   out= on those frames is the published STATS pack's dims
 *   (~256 long edge), NOT the render resolution — the block is where the
 *   real dims and per-stage timings live, the numbers the choppy-12MP
 *   diagnosis needs.
 *
 * Pure formatting lives in [line] (JVM-testable, the MetricsSummary
 * precedent); the screen calls [log] at the render completion point.
 */
object RenderTrace {

    const val TAG = "ritim-trace"

    /** Debug builds only — the trace never logs in release. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /**
     * One completed render, as the publish fold sees it. [outW]/[outH]
     * are the DEVELOPED dimensions (the truth); [renderMs] is the wall
     * time of the render call itself (call -> result, the PreviewMetrics
     * precedent), the number to compare against the audit's host
     * per-stage numbers.
     */
    data class Record(
        val sequence: Long,
        val frameIndex: Int,
        val renderClass: PreviewTiers.RenderClass,
        val requestedMaxDim: Int,
        val outW: Int,
        val outH: Int,
        val renderMs: Long,
        val grade: GradeK,
        /** The temporal NR gate's decision for THIS render (class + tier). */
        val temporalNrApplied: Boolean,
        /** True when a gyro log was attached and the warp ran for this render. */
        val stabilizationApplied: Boolean,
        /**
         * True when the GPU window path served THIS frame (M4 commit c):
         * the SurfaceView drew it and the Compose Image suppressed — the
         * trace's backend= then reads gles. Default false: every CPU
         * render (and every pre-M4 call site) keeps backend=cpu.
         */
        val servedByGpu: Boolean = false,
        // ---- the GPU-path measurement fields (trace-dims fix 2026-11-14) --
        // out= is NOT the render resolution when the GPU window path served
        // the frame: the published pack IS the stats pack (~256 long edge),
        // and out= logs ITS dims. These fields carry the dims the pack
        // hides plus the per-stage timings, so the line can print the
        // truth the owner's "12MP preview is choppy" diagnosis needs. They
        // DEFAULT neutral everywhere except the GPU window path's Record
        // construction; the CPU line stays byte-identical (line() appends
        // the GPU block only when servedByGpu).
        /** The clip's SENSOR dims (FrameInfo before any reduction). */
        val sourceW: Int = 0,
        val sourceH: Int = 0,
        /** The actual developed dims (the GPU develop texture). */
        val developW: Int = 0,
        val developH: Int = 0,
        /** The window surface's presented dims (0x0 = no surface/query failed). */
        val surfaceW: Int = 0,
        val surfaceH: Int = 0,
        /**
         * The stats-readback mode that ACTUALLY served THIS render (A1
         * async stats, 2026-11-23): OFF = the stats stage skipped (the
         * honest 1x1 placeholder), SYNC = the blocking readback (settle/
         * drag), ASYNC = the fence ring (playback — the N-1 pack, or no
         * pack on a miss). Default SYNC keeps the CPU line's neutral
         * construction sites compiling unchanged.
         */
        val statsMode: PreviewStatsMode = PreviewStatsMode.SYNC,
        /** The six per-stage spans, milliseconds. */
        val decMs: Double = 0.0,
        val cfaMs: Double = 0.0,
        val upMs: Double = 0.0,
        val devMs: Double = 0.0,
        val presentMs: Double = 0.0,
        val statsMs: Double = 0.0,
    )

    /**
     * The line for [record]. Pure: no Android classes, every value
     * caller-supplied — the JVM tests pin the field order, the tier
     * naming (0 -> src), the stage summary's contents and its neutral
     * baseline.
     */
    fun line(record: Record): String = buildString {
        append("seq=")
        append(record.sequence)
        append(" frame=")
        append(record.frameIndex)
        append(" class=")
        append(
            when (record.renderClass) {
                PreviewTiers.RenderClass.DRAG -> "drag"
                PreviewTiers.RenderClass.SETTLE -> "settle"
                PreviewTiers.RenderClass.PLAYBACK -> "playback"
            }
        )
        append(" req=")
        append(if (record.requestedMaxDim == PreviewTiers.SOURCE_MAX_DIM) "src" else record.requestedMaxDim)
        append(" out=")
        append(record.outW)
        append("x")
        append(record.outH)
        append(" backend=")
        append(if (record.servedByGpu) "gles" else "cpu")
        append(" ms=")
        append(record.renderMs)
        append(" grade=")
        append(gradeSummary(record))
        // The GPU-path block (trace-dims fix 2026-11-14): appended ONLY
        // when the GPU window path served the frame — the CPU line stays
        // byte-identical, so every existing grep and baseline leg keeps
        // working. out= above is the published stats pack's dims; these
        // fields are the render's truth: the sensor dims, the actual
        // develop dims, the window surface dims, the stats toggle's
        // state, and the six per-stage spans (one decimal, ms).
        if (record.servedByGpu) {
            append(" source=")
            append(record.sourceW)
            append("x")
            append(record.sourceH)
            append(" develop=")
            append(record.developW)
            append("x")
            append(record.developH)
            append(" surface=")
            append(record.surfaceW)
            append("x")
            append(record.surfaceH)
            append(" stats=")
            append(if (record.statsMode == PreviewStatsMode.OFF) "off" else "${record.outW}x${record.outH}")
            // A1 async stats (2026-11-23): the mode field rides immediately
            // after stats=, before dec= — the owner's grep key for which
            // readback path served the frame (off | sync | async).
            append(" statsMode=")
            append(
                when (record.statsMode) {
                    PreviewStatsMode.OFF -> "off"
                    PreviewStatsMode.SYNC -> "sync"
                    PreviewStatsMode.ASYNC -> "async"
                }
            )
            append(" dec=")
            append(ms(record.decMs))
            append("ms cfa=")
            append(ms(record.cfaMs))
            append("ms up=")
            append(ms(record.upMs))
            append("ms dev=")
            append(ms(record.devMs))
            append("ms present=")
            append(ms(record.presentMs))
            append("ms stats=")
            append(ms(record.statsMs))
            append("ms")
        }
    }

    /** 2.345 -> "2.3" — one decimal, ms, ALWAYS the dot (the line is a grep format: a comma-decimal device locale must not rewrite it). */
    private fun ms(v: Double): String = String.format(java.util.Locale.US, "%.1f", v)

    /**
     * The active-stage summary: pipeline order, only the stages this
     * render actually ran, or "neutral" when the grade is the baseline.
     */
    internal fun gradeSummary(record: Record): String {
        val g = record.grade
        val parts = ArrayList<String>(8)
        if (!g.keepVignetting) parts += "vign-off"
        if (!g.highlightReconstruction) parts += "hlrec-off"
        when {
            g.tonemap == 1 -> parts += "tonemap=aces"
            g.tonemap >= 2 -> parts += "tonemap=agx"
        }
        if (g.lutId != null) parts += "lut=" + g.lutId
        if (g.noiseReduction > 0f || g.chromaDenoise > 0f) {
            parts += "nr=" + percent(g.noiseReduction) + "/" + percent(g.chromaDenoise)
        }
        if (g.glitterSuppress) parts += "glitter"
        if (record.temporalNrApplied) parts += "tnr=" + percent(g.temporalStrength)
        if (record.stabilizationApplied) parts += "stab"
        if (parts.isEmpty()) return "neutral"
        return parts.joinToString(" ")
    }

    /** 12.5 -> "13" — dials read as whole percents, no decimal noise. */
    private fun percent(v: Float): Int = Math.round(v)

    /**
     * Logs [record]'s line under [TAG]. Debug-gated; a no-op in release
     * (the record is still built by the caller — one small data class
     * on the publish path, cheaper than any render it describes).
     */
    fun log(record: Record) {
        if (enabled) Log.i(TAG, line(record))
    }

    /**
     * GPU grade-handoff summary (2026-11-23, owner report: the GPU preview
     * shows no change when a develop slider moves). The develop controls
     * the Kotlin GPU arm hands to gpu.render, as one compact line that
     * pairs with the native `pass probe: uniforms ... grade[...]` /
     * `window probe: ... grade[...]` lines (same field set and number
     * formats) — a mismatch between the two sides names the seam that
     * dropped the move. Pure so it is unit-testable.
     */
    fun gpuGradeSummary(g: GradeK, frameIndex: Int, maxDim: Int, sequence: Long): String =
        String.format(
            java.util.Locale.US,
            "gpu grade seq=%d frame=%d maxDim=%d grade[ev=%.3f hi=%.1f bp=%.1f wp=%.1f sh=%.1f " +
                "mid=%.1f con=%.1f sat=%.1f temp=%.0f tint=%.1f asShot=%d sharp=%.1f det=%.1f " +
                "nr=(%.1f,%.1f) tm=%d]",
            sequence, frameIndex, maxDim,
            g.exposureEv, g.highlights, g.blackPoint, g.whitePoint, g.shadows,
            g.midtones, g.contrast, g.saturation, g.tempK, g.tint,
            if (g.asShot) 1 else 0, g.sharpness, g.detail,
            g.chromaDenoise, g.noiseReduction, g.tonemap,
        )

    /** Debug-only, rate-limited by the caller to grade changes. */
    fun logGpuGrade(g: GradeK, frameIndex: Int, maxDim: Int, sequence: Long) {
        if (enabled) Log.i(TAG, gpuGradeSummary(g, frameIndex, maxDim, sequence))
    }
}
