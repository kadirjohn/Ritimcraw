package com.ritim.ritimcraw.export

/**
 * The video encoder rejected (or could not be created for) the plan's
 * format, before any frame was encoded. A subclass of IllegalStateException
 * so every existing catch keeps working; the exporter treats it as the
 * trigger of the ONE ExportPlan-fix retry ([ExportPlanRun.fixAfterRejection]).
 */
class VideoConfigRejected(message: String) : IllegalStateException(message)

/**
 * The 10-bit GPU path cannot serve this export (the session has no
 * RGBA1010102 config, the encoder surface was refused, or the render failed).
 * 10-bit is GPU-only on purpose: the CPU develop path outputs 8-bit RGB888
 * and 10-bit made from it would be a lie (an 8-bit image in a 10-bit
 * container, still banded), so there is no CPU 10-bit and no silent
 * 8-bit-in-10-bit adoption. Before any frame was encoded ([midStream] false)
 * the exporter re-plans ONCE as 8-bit with the reason on the report; after
 * frames were encoded the export FAILS with this message rather than mix
 * depths.
 */
class TenBitUnavailable(message: String, val midStream: Boolean = false) : IllegalStateException(message)

/** The source frame rate of a single clip, from its own pts (never a setting; plan 7.3). */
object ExportFps {
    private val COMMON = doubleArrayOf(
        23.976, 24.0, 25.0, 29.97, 30.0, 47.952, 48.0, 50.0, 59.94, 60.0,
        95.904, 96.0, 100.0, 119.88, 120.0, 239.76, 240.0,
    )

    /** One clip's fps from its raw pts (microseconds); 30 when there is no span to measure. */
    fun fromPts(ptsUs: List<Long>): Double {
        if (ptsUs.size < 2) return 30.0
        val span = ptsUs.last() - ptsUs.first()
        if (span <= 0L) return 30.0
        return snap((ptsUs.size - 1) * 1_000_000.0 / span)
    }

    /** The nearest common rate within 0.3 %, else the value rounded to 3 decimals. */
    fun snap(fps: Double): Double {
        val near = COMMON.minByOrNull { kotlin.math.abs(it - fps) }
        if (near != null && kotlin.math.abs(near - fps) / near < 0.003) return near
        return Math.round(fps * 1000.0) / 1000.0
    }
}

/**
 * One MP4 export's plan, retry state and report trail (plan 7.5, E1b). Pure
 * Kotlin; the exporter builds one per settings job and hands it to the
 * pipeline, which asks it for the plan once the encode size and fps are
 * known (they are not known when the job is queued).
 *
 * Retry rule (owner brief): Surface -> CPU is the exporter's existing
 * whole-export restart; after it AT MOST ONE plan fix ([fixAfterRejection]).
 * Both happen inside writer.start(), before the first frame is encoded, so
 * no frame is ever encoded twice. Every step lands in [steps] and goes
 * through [diag] (the "ritim-export" log); the final plan, encoder and
 * steps are what the export report shows. Nothing is silent: a fixed plan
 * differs from the request only through issues the report lists.
 */
class ExportPlanRun(
    val settings: ExportSettings,
    private val facts: () -> List<EncoderFacts>,
    private val context: ExportContext = ExportContext(),
    private val diag: (String) -> Unit = {},
) {
    /** The settings the next plan resolves from: the request, or its relaxed copy after the fix. */
    val current: ExportSettings get() = relaxed ?: settings

    private val trail = ArrayList<String>()
    val steps: List<String> get() = trail

    /** The plan the (last) attempt was configured from. */
    var plan: ExportPlan? = null
        private set

    /** What MediaCodec really created (set by the pipeline after start). */
    var encoder: EncoderDiagnostics? = null

    /** The encoded duration in seconds (set by the pipeline once pts are known). */
    var durationSec: Double = 0.0

    var fixUsed: Boolean = false
        private set

    private var relaxed: ExportSettings? = null
    private val extraIssues = ArrayList<ExportIssue>()
    private var lastContext: ExportContext = context
    private val excluded = HashSet<String>()
    private var factsCache: List<EncoderFacts>? = null

    private fun encoders(): List<EncoderFacts> =
        factsCache ?: runCatching { facts() }.getOrDefault(emptyList()).also { factsCache = it }

    fun note(step: String) {
        trail += step
        diag(step)
    }

    /** Resolves (and remembers) the plan for the encode size and fps. */
    fun planFor(width: Int, height: Int, fps: Double, context: ExportContext = this.context): ExportPlan {
        lastContext = context
        val input = ExportInput(width, height, fps)
        val resolved = if (encoders().isEmpty()) {
            ExportPlanner.resolveWithoutFacts(relaxed ?: settings, input, context)
        } else {
            ExportPlanner.resolve(relaxed ?: settings, input, encoders(), context, excluded)
        }
        // The report compares against what the USER asked, not the relaxed copy.
        val p = resolved.copy(requested = settings, issues = resolved.issues + extraIssues)
        plan = p
        note(EncoderDiagnostics.planLine(if (fixUsed) "fix" else "plan", p))
        for (i in p.issues) note(EncoderDiagnostics.issueLine(i))
        return p
    }

    /**
     * The 10-bit GPU path failed before the first frame ([reason]): re-plan
     * as 8-bit, ONCE (this is the run's single plan fix). The issue stays on
     * the report so the file's 8-bit depth is explained, never silent.
     * False when the fix was already used.
     */
    fun fixToEightBit(reason: String): Boolean {
        note("ten-bit unavailable: $reason")
        if (fixUsed) { note("no second fix: the plan was already fixed once"); return false }
        val eight = (relaxed ?: settings).copy(bitDepth = 8)
        relaxed = eight
        fixUsed = true
        extraIssues += ExportIssue(ExportIssue.Kind.TEN_BIT_UNAVAILABLE, ExportIssue.Severity.WARNING, listOf(reason), eight)
        note("fix: 10-bit -> 8-bit")
        return true
    }

    /**
     * The encoder refused the current plan ([reason]). Returns true when a
     * DIFFERENT plan is ready for ONE retry (planFor then returns it);
     * false = no retry (already used, or nothing left to change) and the
     * export fails with the original reason.
     */
    fun fixAfterRejection(reason: String): Boolean {
        val failed = plan ?: return false
        note("rejected: ${failed.encoderName ?: failed.mime} $reason")
        if (fixUsed) { note("no second fix: the plan was already fixed once"); return false }
        val base = settings
        val dims = failed.width to failed.height
        val input = ExportInput(dims.first, dims.second, failed.fps)
        fun differs(a: ExportPlan) = a.ok && (
            a.encoderName != failed.encoderName || a.profile != failed.profile ||
                a.bitrateBps != failed.bitrateBps || a.effective.rateMode != failed.effective.rateMode ||
                a.mime != failed.mime || a.bitDepth != failed.bitDepth
            )
        val relax = base.copy(profile = null, uncapped = false)
        // A: another encoder, the profile left to the encoder.
        val exA = excluded + listOfNotNull(failed.encoderName)
        val a = ExportPlanner.resolve(relax, input, encoders(), lastContext, exA)
        if (failed.encoderName != null && differs(a)) {
            excluded += exA; relaxed = relax; fixUsed = true
            note("fix: another encoder (${a.encoderName}), profile AUTO")
            return true
        }
        // B: the same encoder with a relaxed profile/bitrate cap.
        val b = ExportPlanner.resolve(relax, input, encoders(), lastContext, excluded)
        if (differs(b)) {
            relaxed = relax; fixUsed = true
            note("fix: same encoder, profile AUTO, bitrate capped")
            return true
        }
        note("no fix available: nothing left to relax")
        return false
    }
}
