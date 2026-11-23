package com.ritim.ritimcraw.engine

import android.util.Log
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.NativeGpuDevelop
import java.util.concurrent.ExecutionException

/**
 * GPU-vs-CPU PER-CONTROL parity sweep ("parity case 3", owner report
 * 2026-11-23: with Render backend = GPU the preview does not change when a
 * develop slider moves, while the CPU preview does).
 *
 * WHY THIS IS NOT INSIDE nativeGlesFragmentParity: cases 0/1/2 of that
 * harness drive the single FUSED DEVELOP_FRAGMENT shader and only the
 * default grade / a tonemap sweep, so (a) a non-default control was never
 * compared on a device and (b) the fused shader is not the production
 * chain (PRE -> [NR] -> POST -> [unsharp] -> TAIL — sharpness and detail
 * are separate passes the fused shader does not even contain). This sweep
 * therefore compares the PRODUCTION GPU pipeline (the develop session:
 * the same GpuDevelopPipeline class the preview session draws with, full
 * resolution, 8-bit RGB888 readback) against the CPU export door
 * (nativeExportFrameParcel — the reference the GPU export is gated
 * against), each control ALONE on frame 0, plus a combined extreme grade.
 * Cases 0/1/2 and their native harness are untouched.
 *
 * What a line tells the owner (tag [TAG] = "ritim-parity"):
 *   maxDiffLsb   largest |gpu - cpu| over all RGB bytes (8-bit LSB)
 *   mismatch     share of bytes where gpu != cpu
 *   over1        share of bytes differing by MORE than 1 LSB
 *   cpuMoved     share of bytes this control changed on the CPU render
 *                vs the CPU's own neutral render (the control did something)
 *   gpuMoved     the same on the GPU render vs the GPU's neutral render.
 *                cpuMoved > 0 with gpuMoved == 0 is the owner's bug in one
 *                number: the control is inert on the GPU pipeline.
 */
object GradeParitySweep {

    const val TAG = "ritim-parity"

    /** Debug builds only — never runs or logs in release. */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /**
     * One control at one value: a label for the log line + the grade.
     * [warpM] != null runs the GPU side with warpActive=true and that
     * row-major 3x3 output->source map (2026-11-23: the warp pass wrote the
     * wrong ping-pong slot, so with stabilization on the TAIL read the
     * un-graded PRE image and no grade control did anything).
     */
    data class Case(
        val control: String,
        val label: String,
        val grade: GradeK,
        val warpM: FloatArray? = null,
    ) {
        /** Warp cases compared against the CPU door: identity is the CPU's
         *  own no-warp fast path, so the CPU render IS the reference. */
        val isWarpIdentity: Boolean get() = control == CONTROL_WARP_IDENTITY
        /** A real warp: the CPU door takes a gyro FILE, not a matrix, so
         *  there is no CPU reference; the stats compare GPU(warp) vs
         *  GPU(no warp) (expected to differ) and the INERT flag uses
         *  GPU(warp, grade) vs GPU(warp, neutral). */
        val isWarpReal: Boolean get() = warpM != null && !isWarpIdentity
    }

    const val CONTROL_WARP_IDENTITY = "warp-identity"
    const val CONTROL_WARP_ROT = "warp-rot"

    private val IDENTITY_M = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    // ~1.15 deg rotation about the origin + a (12, -8) px translation.
    private val ROT_M = floatArrayOf(0.9998f, -0.0200f, 12f, 0.0200f, 0.9998f, -8f, 0f, 0f, 1f)

    /** Raw numbers for one compared pair (pure; JVM-tested). */
    data class Stats(
        val samples: Long,
        val maxDiffLsb: Int,
        val mismatch: Long,
        val over1: Long,
        val meanSigned: Double,
    ) {
        val mismatchPct: Double get() = if (samples == 0L) 0.0 else mismatch * 100.0 / samples
        val over1Pct: Double get() = if (samples == 0L) 0.0 else over1 * 100.0 / samples
    }

    /** Alone-cases: every develop control at the owner's +/-50, +/-100 (exposure +/-1, +/-3 EV). */
    fun cases(): List<Case> {
        val out = ArrayList<Case>()
        fun add(control: String, label: String, g: GradeK) {
            out += Case(control, label, g)
        }
        for (ev in floatArrayOf(-3f, -1f, 1f, 3f)) {
            add("exposure", fmt(ev) + "EV", GradeK(exposureEv = ev))
        }
        val percentControls: List<Pair<String, (GradeK, Float) -> GradeK>> = listOf(
            "highlights" to { g, v -> g.copy(highlights = v) },
            "blackPoint" to { g, v -> g.copy(blackPoint = v) },
            "whitePoint" to { g, v -> g.copy(whitePoint = v) },
            "shadows" to { g, v -> g.copy(shadows = v) },
            "midtones" to { g, v -> g.copy(midtones = v) },
            "contrast" to { g, v -> g.copy(contrast = v) },
            "saturation" to { g, v -> g.copy(saturation = v) },
            // Temperature rides tempK with the manual-WB gate open: +-50 ->
            // +-1500 K, +-100 -> +-3000 K around the 5600 K neutral (30 K
            // per slider unit; the slider's own span is not assumed).
            "temp" to { g, v -> g.copy(asShot = false, tempK = 5600f + 30f * v) },
            "tint" to { g, v -> g.copy(asShot = false, tint = v) },
            "sharpness" to { g, v -> g.copy(sharpness = v) },
            "detail" to { g, v -> g.copy(detail = v) },
        )
        for ((name, apply) in percentControls) {
            for (v in floatArrayOf(-100f, -50f, 50f, 100f)) {
                add(name, (if (v > 0f) "+" else "") + fmt(v), apply(GradeK(), v))
            }
        }
        out += Case(
            "combined", "extreme",
            GradeK(
                exposureEv = 1f, highlights = -100f, blackPoint = 50f, whitePoint = -30f,
                shadows = 100f, midtones = 50f, contrast = 50f, saturation = 50f,
                asShot = false, tempK = 7100f, tint = 30f, sharpness = 50f, detail = 50f,
            ),
        )
        // Warp cases (grade non-neutral so an un-graded TAIL input is visible):
        // identity must equal the CPU render; a small rotation+translation
        // must still follow the grade.
        out += Case(CONTROL_WARP_IDENTITY, "+1EV", GradeK(exposureEv = 1f), IDENTITY_M)
        out += Case(CONTROL_WARP_ROT, "+1EV", GradeK(exposureEv = 1f), ROT_M)
        out += Case(CONTROL_WARP_ROT, "-1EV", GradeK(exposureEv = -1f), ROT_M)
        return out
    }

    private fun fmt(v: Float): String =
        if (v == Math.rint(v.toDouble()).toFloat()) v.toInt().toString() else v.toString()

    /** Byte-wise compare of two equal-length RGB buffers (unsigned). */
    fun compare(gpu: ByteArray, cpu: ByteArray): Stats {
        val n = minOf(gpu.size, cpu.size)
        var maxD = 0
        var mism = 0L
        var over1 = 0L
        var signed = 0L
        for (i in 0 until n) {
            val g = gpu[i].toInt() and 0xFF
            val c = cpu[i].toInt() and 0xFF
            val d = g - c
            if (d != 0) {
                mism++
                signed += d
                val a = if (d < 0) -d else d
                if (a > maxD) maxD = a
                if (a > 1) over1++
            }
        }
        return Stats(n.toLong(), maxD, mism, over1, if (n == 0) 0.0 else signed.toDouble() / n)
    }

    /** Share (percent) of bytes where [a] and [b] differ at all. */
    fun movedPct(a: ByteArray, b: ByteArray): Double {
        val n = minOf(a.size, b.size)
        if (n == 0) return 0.0
        var diff = 0L
        for (i in 0 until n) if (a[i] != b[i]) diff++
        return diff * 100.0 / n
    }

    fun line(c: Case, s: Stats, cpuMovedPct: Double, gpuMovedPct: Double): String =
        String.format(
            java.util.Locale.US,
            "grade-sweep %s %s: maxDiffLsb=%d mismatch=%.3f%% over1=%.3f%% meanSigned=%.3f " +
                "cpuMoved=%.2f%% gpuMoved=%.2f%% samples=%d%s",
            c.control, c.label, s.maxDiffLsb, s.mismatchPct, s.over1Pct, s.meanSigned,
            cpuMovedPct, gpuMovedPct, s.samples,
            // The one flag worth grepping: the control moves the CPU image
            // but not the GPU one (the owner's no-visible-change bug).
            if (cpuMovedPct >= 0.5 && gpuMovedPct < 0.05) " INERT_ON_GPU" else "",
        )

    fun summaryLine(lines: List<Triple<Case, Stats, Pair<Double, Double>>>, skipped: Int): String {
        // warp-rot stats are GPU-vs-GPU(no warp): large by design, not a parity number.
        val worst = lines.filter { !it.first.isWarpReal }.maxByOrNull { it.second.maxDiffLsb }
        val inert = lines.count { it.third.first >= 0.5 && it.third.second < 0.05 }
        return "grade-sweep summary: cases=${lines.size} skipped=$skipped " +
            "worstMaxDiffLsb=${worst?.second?.maxDiffLsb ?: 0}" +
            (if (worst != null) " (${worst.first.control} ${worst.first.label})" else "") +
            " inertOnGpu=$inert"
    }

    /**
     * Runs the sweep over the clip at [fd] (TAKES OWNERSHIP of the fd:
     * closed here, always). Frame 0, full resolution, no LUT; warp only in the warp-* cases.
     * Everything touching the clip handle runs on the shared preview engine
     * thread (the one-thread rule). Logs one line per case plus a summary
     * under [TAG]; returns the summary line, or null when disabled /
     * unopenable.
     */
    suspend fun run(fd: Int): String? {
        if (!enabled) {
            PreviewRenderer.closeFdQuietly(fd)
            return null
        }
        val engine = PreviewRenderer.previewEngine
        return try {
            val future = engine.submit<String?> { runBlocking(fd) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { future.get() }
            } catch (e: ExecutionException) {
                Log.w(TAG, "grade-sweep aborted: ${e.cause?.message}")
                null
            }
        } finally {
            PreviewRenderer.closeFdQuietly(fd)
        }
    }

    private fun runBlocking(fd: Int): String? {
        if (!NativeSource.nativeAvailable) {
            Log.w(TAG, "grade-sweep aborted: ${NativeSource.nativeUnavailableMessage}")
            return null
        }
        val info = NativeSource.jniPreviewOpen(fd)
        if (info == null || info.size < 7 || info[0] == 0) {
            Log.w(TAG, "grade-sweep aborted: clip open failed (${NativeSource.previewLastError()})")
            return null
        }
        val clip = info[0].toLong()
        val session = NativeGpuDevelop.openSession()
        if (session == 0L) {
            Log.w(TAG, "grade-sweep aborted: gpu develop session open failed " +
                "(${NativeSource.previewLastError()})")
            NativeSource.jniCloseSource(clip)
            return null
        }
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        fun gpu(g: GradeK, warpM: FloatArray? = null): ByteArray? = NativeGpuDevelop.renderFrameParcel(
            session, clip, 0, GradeParcel.encode(g),
            null, 0f, -1, -1, -1, -1, -1, warpM != null, warpM ?: identity,
        )
        fun cpu(g: GradeK): ByteArray? = NativeSource.jniExportFrameParcel(
            clip, 0, GradeParcel.encode(g),
            null, 0f, null, 0, 0f, 0f, 0f, 0f, -1, -1, -1, -1, -1,
        )
        try {
            val neutralGrade = GradeK()
            val gpuNeutral = gpu(neutralGrade)
            val cpuNeutral = cpu(neutralGrade)
            if (gpuNeutral == null || cpuNeutral == null) {
                Log.w(TAG, "grade-sweep aborted: neutral render failed gpu=${gpuNeutral != null} " +
                    "cpu=${cpuNeutral != null} (${NativeSource.previewLastError()})")
                return null
            }
            val base = compare(gpuNeutral, cpuNeutral)
            Log.i(TAG, line(Case("neutral", "default", neutralGrade), base, 0.0, 0.0))
            val rows = ArrayList<Triple<Case, Stats, Pair<Double, Double>>>()
            var skipped = 0
            for (c in cases()) {
                val g = gpu(c.grade, c.warpM)
                val cpuRef = cpu(c.grade)
                // Real warp: reference for the stats is the GPU's own
                // no-warp render (no CPU door for a matrix).
                val r = if (c.isWarpReal) gpu(c.grade) else cpuRef
                if (g == null || r == null || cpuRef == null) {
                    skipped++
                    Log.w(TAG, "grade-sweep ${c.control} ${c.label}: render failed " +
                        "gpu=${g != null} cpu=${r != null} (${NativeSource.previewLastError()})")
                    continue
                }
                val stats = compare(g, r)
                // gpuMoved: the grade moved the GPU image, measured against the
                // GPU's neutral render WITH THE SAME WARP (so a warp that
                // swallows the grade shows up as INERT_ON_GPU).
                val gpuBase = if (c.warpM != null) gpu(neutralGrade, c.warpM) ?: gpuNeutral else gpuNeutral
                val moved = movedPct(cpuRef, cpuNeutral) to movedPct(g, gpuBase)
                rows += Triple(c, stats, moved)
                Log.i(TAG, line(c, stats, moved.first, moved.second))
            }
            val summary = summaryLine(rows, skipped)
            Log.i(TAG, summary)
            return summary
        } finally {
            NativeGpuDevelop.closeSession(session)
            NativeSource.jniCloseSource(clip)
        }
    }
}
