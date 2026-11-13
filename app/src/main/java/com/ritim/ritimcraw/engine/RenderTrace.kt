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
 * - backend= names the pipeline that ACTUALLY ran. The production
 *   preview path is the CPU reference (RenderBackend.DEFAULT, all
 *   backend gates off); the GPU groundwork's GLES legs are parity
 *   probes, not the editor's render path, so the honest value today is
 *   cpu. The field exists so the day a backend ships, the SAME line
 *   tells the two pipelines apart without a format change.
 * - grade= lists only the stages that are ACTIVE for this render, in
 *   pipeline order, each with its dial where one exists: vign/hlrec are
 *   the keep- booleans (named for what the stage DOES, true = applied),
 *   tonemap is off/aces/agx, lut names the selected id, nr is the
 *   spatial+chroma pair, tnr carries the class-gated truth (what the
 *   tier gate actually decided — the M0 SOURCE fix lands here), stab is
 *   on only when the grade asked AND a gyro log was attached. A neutral
 *   grade prints grade=neutral — the A/B baseline leg.
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
        append(" backend=cpu ms=")
        append(record.renderMs)
        append(" grade=")
        append(gradeSummary(record))
    }

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
}
