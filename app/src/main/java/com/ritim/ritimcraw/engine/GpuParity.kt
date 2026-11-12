package com.ritim.ritimcraw.engine

/**
 * GLES probe/parity results + decision rules (owner request 2026-11-12,
 * deliverable C). Pure JVM: the JNI seam returns plain DoubleArrays and
 * THIS file owns the mapping and the verdict, so the decision rules are
 * JVM-tested while the C++ harness (gles/GlesPipeline.cpp) stays a dumb
 * reporter of raw numbers — it never decides pass/fail.
 */
data class GlesProbeResult(
    val contextOk: Boolean,
    val glesMajor: Int,
    val glesMinor: Int,
    val computeOk: Boolean,
    val floatFboOk: Boolean,
    val maxTextureSize: Int,
) {
    companion object {
        /**
         * JNI contract (nativeGlesProbe):
         * [contextOk, glesMajor, glesMinor, computeOk, floatFboOk,
         * maxTextureSize]. Throws IllegalArgumentException on any other
         * size — a contract drift must be loud, never papered over.
         */
        fun fromArray(a: DoubleArray): GlesProbeResult {
            require(a.size == 6) { "gles probe array must hold 6 values (got ${a.size})" }
            return GlesProbeResult(
                contextOk = a[0] != 0.0,
                glesMajor = a[1].toInt(),
                glesMinor = a[2].toInt(),
                computeOk = a[3] != 0.0,
                floatFboOk = a[4] != 0.0,
                maxTextureSize = a[5].toInt(),
            )
        }
    }
}

/**
 * Raw parity numbers from one GPU-vs-CPU run (the C++ harness reports,
 * never decides). The comparison domain is uint16 — the DNG path's own
 * quantize — so sub-LSB driver float noise is absorbed by rounding.
 */
data class GlesParityResult(
    val ran: Boolean,
    val maxDiffLsb: Int,
    val mismatchCount: Long,
    val samples: Long,
) {
    companion object {
        /**
         * JNI contract (nativeGlesParity):
         * [ran, maxDiffLsb, mismatchCount, samples]. Throws
         * IllegalArgumentException on any other size.
         */
        fun fromArray(a: DoubleArray): GlesParityResult {
            require(a.size == 4) { "gles parity array must hold 4 values (got ${a.size})" }
            return GlesParityResult(
                ran = a[0] != 0.0,
                maxDiffLsb = a[1].toInt(),
                mismatchCount = a[2].toLong(),
                samples = a[3].toLong(),
            )
        }
    }
}

data class ParityVerdict(val status: Status, val reason: String) {
    enum class Status { PASS, FAIL, NOT_RUN }

    val pass: Boolean get() = status == Status.PASS
}

/**
 * The parity pass/fail thresholds + the log-line formats (JVM-tested; the
 * Measure harness logs these lines under the "ritim-pm" tag).
 *
 * Tolerance rationale: both sides compute float32, but the GPU's
 * division/fused-op rounding can differ from the CPU's by ~1 ulp, which
 * after the x65535 quantize lands within 1 LSB of 65535 and only for
 * samples sitting exactly on a rounding boundary. So: at most 1 LSB and
 * at most 0.01% of samples mismatching is driver noise; anything beyond
 * that is a real algorithm deviation (wrong neighbor set, wrong pattern
 * cell, wrong clamp order) and FAILS — far below the 8-bit export's own
 * 1/255 step, while a systematic deviation (even confined to the image
 * borders, ~0.2% of a 4K frame) still fails.
 */
object GpuParityRules {

    /** Largest tolerated |gpu - cpu| in 16-bit LSB units. */
    const val PASS_MAX_LSB = 1

    /** Largest tolerated mismatch share: mismatchCount * 10000 <= samples (0.01%). */
    const val PASS_MISMATCH_PER_MYRIAD = 1

    /**
     * The verdict for one parity run. ran=false -> NOT_RUN with the
     * harness's error detail ([errorDetail], from nativeLastError); the
     * thresholds apply only to a run that produced real numbers.
     */
    fun verdict(parity: GlesParityResult, errorDetail: String? = null): ParityVerdict {
        if (!parity.ran) {
            return ParityVerdict(
                ParityVerdict.Status.NOT_RUN,
                errorDetail ?: "gpu stage failed",
            )
        }
        if (parity.maxDiffLsb > PASS_MAX_LSB) {
            return ParityVerdict(
                ParityVerdict.Status.FAIL,
                "maxDiffLsb ${parity.maxDiffLsb} > $PASS_MAX_LSB",
            )
        }
        if (parity.mismatchCount * 10000 > parity.samples * PASS_MISMATCH_PER_MYRIAD) {
            return ParityVerdict(
                ParityVerdict.Status.FAIL,
                "mismatch ${parity.mismatchCount}/${parity.samples} > 0.01%",
            )
        }
        return ParityVerdict(
            ParityVerdict.Status.PASS,
            "maxDiffLsb ${parity.maxDiffLsb} <= $PASS_MAX_LSB, " +
                "mismatch ${parity.mismatchCount}/${parity.samples} <= 0.01%",
        )
    }

    /**
     * The render-backend gates a probe supports. parityOk is ALWAYS
     * false here on purpose: parity comes only from a real parity run
     * (verdict above), never from a capability probe — the BackendGates
     * contract (RenderBackend.kt) keeps the two honest and separate.
     */
    fun gatesFrom(probe: GlesProbeResult): BackendGates = BackendGates(
        contextOk = probe.contextOk,
        computeOk = probe.computeOk,
        floatFboOk = probe.floatFboOk,
        parityOk = false,
    )

    fun probeLine(p: GlesProbeResult): String =
        "probe: context=${bit(p.contextOk)} gles=${p.glesMajor}.${p.glesMinor}" +
            " compute=${bit(p.computeOk)} floatFbo=${bit(p.floatFboOk)}" +
            " maxTextureSize=${p.maxTextureSize}"

    fun parityLine(p: GlesParityResult): String =
        "parity: ran=${bit(p.ran)} maxDiffLsb=${p.maxDiffLsb}" +
            " mismatch=${p.mismatchCount} samples=${p.samples}"

    fun verdictLine(v: ParityVerdict): String =
        "verdict: ${v.status.name} (${v.reason})"

    fun skippedParityLine(reason: String): String = "parity: skipped ($reason)"

    /**
     * The Measure log block wrapper (the "ritim-pm" preview-metrics
     * block's shape; PreviewMetricsTest locks that sibling block's exact
     * strings — this one is the GLES leg's own).
     */
    fun block(lines: List<String>): String = buildString {
        appendLine("=== ritim gles (${PreviewMetrics.TAG}) ===")
        lines.forEach { appendLine(it) }
        append("=== end gles ===")
    }

    private fun bit(v: Boolean) = if (v) 1 else 0
}
