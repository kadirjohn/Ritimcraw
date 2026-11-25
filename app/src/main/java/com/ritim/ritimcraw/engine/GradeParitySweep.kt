package com.ritim.ritimcraw.engine

import android.util.Log
import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.data.grade2.toGradeK
import com.ritim.ritimcraw.data.grade2.OutputTransformV2
import com.ritim.ritimcraw.data.grade2.OpenDrtParamsV2
import com.ritim.ritimcraw.data.grade2.NodeWheelV2
import com.ritim.ritimcraw.data.grade2.NodeCurveV2
import com.ritim.ritimcraw.data.grade2.GradeNodeV2
import com.ritim.ritimcraw.data.grade2.ClipGradeV2
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
        /** [LUT_TEST_CUBE] = the sweep's own generated 17^3 .cube (resolved at run time); null = no LUT. */
        val lutPath: String? = null,
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

    /** Marker for [Case.lutPath]: the run writes the sweep's own cube to the cache dir and substitutes its path. */
    const val LUT_TEST_CUBE = "<sweep-test-cube>"

    /** A 17^3 .cube that is clearly not the identity (gamma lift mixed with a channel rotation). */
    private fun writeSweepCube(): java.io.File {
        val n = 17
        val sb = StringBuilder("TITLE \"grade-sweep\"\nLUT_3D_SIZE $n\n")
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val rf = r / (n - 1f); val gf = g / (n - 1f); val bf = b / (n - 1f)
            val lift = { v: Float -> Math.pow(v.toDouble(), 0.85).toFloat() }
            sb.append(String.format(java.util.Locale.ROOT, "%.6f %.6f %.6f\n",
                0.8f * lift(rf) + 0.2f * gf, 0.8f * lift(gf) + 0.2f * bf, 0.8f * lift(bf) + 0.2f * rf))
        }
        // java.io.tmpdir is the app's cache dir on Android.
        val f = java.io.File(System.getProperty("java.io.tmpdir") ?: ".", "grade-sweep.cube")
        f.writeText(sb.toString())
        return f
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
        // Tonemap cases (2026-11-25): the sweep had none, so the AgX GPU/CPU twins were only compared
        // through the encoded-chain test, where AgX alone read 1 LSB on 0.0255% of samples. ACES is
        // rational; the AgX looks add log2 and two pow() per channel.
        add("tonemap", "ACES", GradeK(tonemap = 1))
        add("tonemap", "AgX Punchy", GradeK(tonemap = 2))
        add("tonemap", "AgX Base", GradeK(tonemap = 5))
        // LUT case (2026-11-24): the shared LUT stage became a float32 table read with texelFetch and blended by
        // hand; the sweep had no LUT case, so this is the first device parity number for the MCRAW LUT stage.
        out += Case("lut", "17^3 100%", GradeK(lutStrength = 100f), lutPath = LUT_TEST_CUBE)
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

    // ---- the v2 chain sweep (color model v2 F1-7, plan §10 layer 4) ----
    //
    // The grade sweep rides the DEVELOP session; the v2 chain cases ride
    // the PREVIEW session — the ONLY session with nodes doors (F1-5's
    // additive initNodes/setColorModelChain + F1-6's binding). Each case:
    // one trimmed node chain (or a DRT output) through
    // GradeV2Usage/NodeChainWire, the SAME wire handed to BOTH legs —
    // setColorModelChain + the preview render door (GPU) vs the CPU
    // preview render's chainWire leg (the reference) — at maxDim 256
    // (kStatsLongEdge's shape: the published pack matches dims, the same
    // rule the crop sweep rides). ACCEPTANCE for the nodes-only cases =
    // GradeParitySweep.compare's bucket (byte stats); the drt cases
    // ride the same thresholds (K9: the DRT GPU/CPU threshold is
    // MEASURED on device; the sweep's line reports the numbers, the
    // flag stays the existing rule until the owner pins one).

    /** One v2 chain case: a label + the trimmed chain's wire + the drt pack when the output declares it. */
    data class V2Case(
        val control: String,
        val label: String,
        val chainWire: FloatArray,
        /** The OpenDRT uniform pack (required when the wire's outputMode is 1). */
        val drtUniforms: FloatArray?,
        val grade: GradeK,
    )

    /** The device v2 case grid (pure; JVM-pinned). */
    fun v2Cases(): List<V2Case> {
        fun grade(
            control: String, label: String,
            nodes: List<GradeNodeV2>,
            output: OutputTransformV2 =
                OutputTransformV2(),
        ): V2Case {
            val v2 = ClipGradeV2(
                nodes = nodes, output = output,
                // The legacy develop block STAYS the identity grade (its
                // all-neutral default): the grid measures the chain alone
                // (the same alone-cases rule the grade sweep rides).
            )
            val active = GradeV2Usage.activeNodes(v2)
            val wire = NodeChainWire.encode(v2, active)
            val drt = if (output.mode == OutputTransformV2.MODE_OPENDRT) {
                OpenDrtPacks.resolve(
                    OpenDrtPacks.Spec(
                        look = output.opendrt.look,
                        tonescale = output.opendrt.tonescale,
                        cwp = output.opendrt.cwp,
                        cwpLimit = output.opendrt.cwpLimit,
                        grey = output.opendrt.grey,
                        display = output.opendrt.display,
                    ),
                )
            } else {
                null
            }
            return V2Case(control, label, wire, drt, v2.toGradeK() ?: GradeK())
        }

        return listOf(
            // Primaries (nodes-primary, plan §10 layer 4's list).
            grade("nodes-primary", "lift", listOf(GradeNodeV2(lift = NodeWheelV2(0.3f, 0.1f)))),
            grade("nodes-primary", "gain", listOf(GradeNodeV2(gain = NodeWheelV2(-0.2f, 0.25f, 0.1f)))),
            grade("nodes-primary", "contrast+sat", listOf(GradeNodeV2(contrast = 1.4f, sat = 1.2f))),
            // Log wheels.
            grade("nodes-log", "shadow", listOf(GradeNodeV2(logShadow = NodeWheelV2(0.2f, 0.1f, 0.15f)))),
            grade("nodes-log", "mid", listOf(GradeNodeV2(logMid = NodeWheelV2(-0.1f, 0.1f, 0.05f)))),
            // Curves (nodes-curves).
            grade(
                "nodes-curves", "yS",
                listOf(
                    GradeNodeV2(curveY = NodeCurveV2(
                        listOf(0f to 0f, 0.4f to 0.32f, 1f to 1f),
                    )),
                ),
            ),
            // Hue + WB.
            grade("nodes-hue", "30deg", listOf(GradeNodeV2(hue = 30f))),
            grade("nodes-temp", "warm", listOf(GradeNodeV2(temp = 0.5f, tint = -0.2f))),
            // The multi-node chain + mix.
            grade(
                "nodes-chain", "3-nodes",
                listOf(
                    GradeNodeV2(lift = NodeWheelV2(0.2f, -0.1f)),
                    GradeNodeV2(sat = 1.15f, hue = -15f),
                    GradeNodeV2(gain = NodeWheelV2(0.15f, 0f, -0.1f), mix = 0.7f),
                ),
            ),
            // The OpenDRT outputs (drt-sdr / drt-hlg, plan's list).
            grade(
                "drt-sdr", "standard",
                emptyList(),
                OutputTransformV2(
                    mode = OutputTransformV2.MODE_OPENDRT,
                ),
            ),
            grade(
                "drt-hlg", "standard",
                emptyList(),
                OutputTransformV2(
                    mode = OutputTransformV2.MODE_OPENDRT,
                    opendrt = OpenDrtParamsV2(display = 2),
                ),
            ),
            // nodes + drt (the plan's grade+nodes+drt composition).
            grade(
                "nodes+drt", "chain",
                listOf(GradeNodeV2(sat = 1.2f, contrast = 1.15f)),
                OutputTransformV2(
                    mode = OutputTransformV2.MODE_OPENDRT,
                ),
            ),
        )
    }

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

    /**
     * The frame DIGEST (plan §10 layer 5's karne line): the IEEE CRC32
     * of one rendered frame's bytes. The sweep prints it per case so a
     * device run before/after a merge compares EXACT hex — two builds
     * that agree on maxDiffLsb but disagree on content still disagree
     * here (the stats' own bucket rounds 1-2 LSB jitter to the same
     * line; the CRC does not). Pure Kotlin, core's crc32Ieee's own
     * polynomial (0xEDB88320 reflected) — pinned against a known vector
     * in GradeParitySweepTest.
     */
    fun frameDigest(frame: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (b in frame) {
            crc = (crc xor (b.toLong() and 0xFF)) and 0xFFFFFFFFL
            for (k in 0 until 8) {
                crc = if (crc and 1L != 0L) {
                    ((crc ushr 1) xor 0xEDB88320L) and 0xFFFFFFFFL
                } else {
                    (crc ushr 1) and 0xFFFFFFFFL
                }
            }
        }
        return (crc xor 0xFFFFFFFFL) and 0xFFFFFFFFL
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
        fun gpu(g: GradeK, warpM: FloatArray? = null, lut: String? = null): ByteArray? = NativeGpuDevelop.renderFrameParcel(
            session, clip, 0, GradeParcel.encode(g),
            lut, if (lut != null) g.lutStrength / 100f else 0f, -1, -1, -1, -1, -1, warpM != null, warpM ?: identity,
        )
        fun cpu(g: GradeK, lut: String? = null): ByteArray? = NativeSource.jniExportFrameParcel(
            clip, 0, GradeParcel.encode(g),
            lut, if (lut != null) g.lutStrength / 100f else 0f, null, 0, 0f, 0f, 0f, 0f, -1, -1, -1, -1, -1,
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
                val lut = if (c.lutPath == LUT_TEST_CUBE) writeSweepCube().path else c.lutPath
                val g = gpu(c.grade, c.warpM, lut)
                val cpuRef = cpu(c.grade, lut)
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
                // The frame digest (plan §10 layer 5's karne): one hex
                // line per case — the before/after-merge comparison the
                // stats' bucket cannot make (1-2 LSB jitter rounds to the
                // same line; the CRC does not).
                Log.i(TAG, "grade-sweep ${c.control} ${c.label}: frameDigest=cpu:%08x gpu:%08x"
                    .format(frameDigest(r), frameDigest(g)))
            }
            val summary = summaryLine(rows, skipped)
            Log.i(TAG, summary)
            return summary
        } finally {
            NativeGpuDevelop.closeSession(session)
            NativeSource.jniCloseSource(clip)
        }
    }

    // ---- the A2 crop sweep (owner request 2026-11-23, guide A2) -----------
    //
    // The grade sweep rides the DEVELOP session; the crop sweep must ride
    // the PREVIEW session — the crop lives in WINDOW_FRAGMENT (the window
    // pass the stats draw reuses), so the GPU leg renders through the
    // SAME door the editor's GPU arm rides:
    // nativePreviewSessionRenderFrameParcel with cropActive=true. The
    // published stats pack IS the cropped region at the fixed small size
    // (maxDim = 256 keeps the cropped output under kStatsLongEdge, so
    // the pack lands at EXACTLY the crop output dims — no 256 rescale
    // between the two legs). The CPU reference is the EXACT publish
    // path's math: the uncropped CPU render at the same maxDim,
    // CropState.applyToArgb with spaceDeltaDeg = 0 (the same effective-
    // upright rect, manualDelta = 0 in every case). ACCEPTANCE:
    // maxDiffLsb <= 2 (the guide's twin-readback-vs-CPU bound).

    /** One crop case: the effective-upright rect fractions + the angle. */
    data class CropCase(
        val x: Float,
        val y: Float,
        val w: Float,
        val h: Float,
        val deg: Float,
        /** 0 in every case: the clip's real container orientation still
         *  exercises the shader's uOrientation fold natively. */
        val manualDeltaDeg: Int = 0,
    )

    /** The device crop grid (pure; JVM-pinned). */
    fun cropCases(): List<CropCase> {
        val out = ArrayList<CropCase>()
        val rects = listOf(
            // A centered 16:9.
            CropCase(0.125f, 0.25f, 0.75f, 0.5f, 0f),
            // Off-center, no edge alignment.
            CropCase(0.4f, 0.35f, 0.45f, 0.4f, 0f),
            // The clampRect floor: 5 percent per axis.
            CropCase(0.5f, 0.5f, 0.05f, 0.05f, 0f),
        )
        // The deg grid replaces the placeholder 0 of each rect shape.
        for (r in rects) {
            for (deg in floatArrayOf(0f, 0.5f, -0.5f, 7f, -7f, 15f, -15f)) {
                out += CropCase(r.x, r.y, r.w, r.h, deg, r.manualDeltaDeg)
            }
        }
        return out
    }

    /**
     * DIAGNOSTIC crop cases (2026-11-25, owner request: find WHY the crop
     * parity misses its 2 LSB bound; no threshold moves). One variable at a
     * time over the 256x144 preview the sweep renders (x = n/256, y = m/144
     * land on whole pixels):
     *  - D1  whole-pixel rect at 0 deg: if the 0 deg misses are the CPU's
     *        rounded integer window vs the GPU's continuous rect, this one
     *        must read ~1 LSB.
     *  - D2  the same rect shifted half a pixel in x: the sensitivity of
     *        the compare to a half-pixel origin convention.
     *  - D3  the same whole-pixel rect at 0.05 / 0.1 / 0.25 / 0.5 deg: how
     *        the mismatch scales with the angle (a constant floor points at
     *        the sampler or the zoom; a growing one at the interpolation).
     *  - D4  the whole frame at 0.5 and 7 deg: isolates the auto-zoom and
     *        the clamp-to-edge slivers from the rect arithmetic.
     * Logged as "crop-diag" lines + one "crop-diag summary".
     */
    fun cropDiagnosticCases(): List<CropCase> {
        val x = 104f / 256f
        val y = 45f / 144f
        val w = 120f / 256f
        val h = 64f / 144f
        return listOf(
            CropCase(x, y, w, h, 0f),
            CropCase(x + 0.5f / 256f, y, w, h, 0f),
            CropCase(x, y, w, h, 0.05f),
            CropCase(x, y, w, h, 0.1f),
            CropCase(x, y, w, h, 0.25f),
            CropCase(x, y, w, h, 0.5f),
            CropCase(0f, 0f, 1f, 1f, 0.5f),
            CropCase(0f, 0f, 1f, 1f, 7f),
        )
    }

    /**
     * Where the big disagreements sit (diagnostics only): of the pixels whose
     * worst channel differs by more than [over] LSB, how many lie within
     * [band] pixels of the output border. Returns (total, inBand). All
     * outliers in the band point at an edge/clamp convention; interior ones at
     * the sampling position or the interpolation.
     */
    fun outlierSplit(gpuRgb: ByteArray, cpuRgb: ByteArray, w: Int, h: Int, over: Int = 2, band: Int = 2): Pair<Int, Int> {
        var total = 0
        var inBand = 0
        for (i in 0 until w * h) {
            var worst = 0
            for (c in 0 until 3) {
                val d = Math.abs((gpuRgb[i * 3 + c].toInt() and 0xFF) - (cpuRgb[i * 3 + c].toInt() and 0xFF))
                if (d > worst) worst = d
            }
            if (worst > over) {
                total++
                val x = i % w
                val y = i / w
                if (x < band || y < band || x >= w - band || y >= h - band) inBand++
            }
        }
        return total to inBand
    }

    /** Writes gpu / cpu / amplified-diff PNGs for one diagnostic case (best effort, debug builds). */
    private fun dumpCropDiag(c: CropCase, w: Int, h: Int, gpu: IntArray, cpu: IntArray) {
        try {
            // Debug-only diagnostic: the process's Application via ActivityThread (the sweep runs
            // on an engine thread with no Context in hand).
            val app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as android.app.Application
            val dir = java.io.File(app.filesDir, "crop-diag").apply { mkdirs() }
            val diff = IntArray(w * h) { i ->
                var worst = 0
                for (sh in intArrayOf(16, 8, 0)) {
                    val d = Math.abs(((gpu[i] shr sh) and 0xFF) - ((cpu[i] shr sh) and 0xFF))
                    if (d > worst) worst = d
                }
                val v = (worst * 16).coerceAtMost(255) // x16: 1 LSB = 16, 16 LSB = white
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            val tag = "d${c.deg}_${(c.x * 1000).toInt()}_${w}x$h"
            for ((name, px) in listOf("gpu" to gpu, "cpu" to cpu, "diff" to diff)) {
                val bmp = android.graphics.Bitmap.createBitmap(px.map { it or (0xFF shl 24) }.toIntArray(), w, h, android.graphics.Bitmap.Config.ARGB_8888)
                java.io.FileOutputStream(java.io.File(dir, "$tag-$name.png")).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "crop-diag dump failed: ${e.message}")
        }
    }

    /** ARGB int pack (0xFFRRGGBB) -> RGB bytes (the compare's format). */
    fun argbBytes(argb: IntArray): ByteArray {
        val out = ByteArray(argb.size * 3)
        for (i in argb.indices) {
            out[i * 3] = ((argb[i] shr 16) and 0xFF).toByte()
            out[i * 3 + 1] = ((argb[i] shr 8) and 0xFF).toByte()
            out[i * 3 + 2] = (argb[i] and 0xFF).toByte()
        }
        return out
    }

    fun cropLine(x: Float, y: Float, w: Float, h: Float, deg: Float, s: Stats): String =
        String.format(
            java.util.Locale.US,
            "crop-sweep rect=%.3f,%.3f,%.3f,%.3f deg=%.1f: " +
                "maxDiffLsb=%d mismatch=%.3f%% over1=%.3f%% samples=%d",
            x, y, w, h, deg, s.maxDiffLsb, s.mismatchPct, s.over1Pct, s.samples,
        )

    fun cropSummaryLine(
        lines: List<Pair<CropCase, Stats>>,
        skipped: Int,
    ): String {
        val worst = lines.maxByOrNull { it.second.maxDiffLsb }
        return "crop-sweep summary: cases=${lines.size} skipped=$skipped " +
            "worstMaxDiffLsb=${worst?.second?.maxDiffLsb ?: 0}" +
            (if (worst != null) {
                " (rect=${worst.first.x},${worst.first.y},${worst.first.w},${worst.first.h} " +
                    "deg=${worst.first.deg})"
            } else "")
    }

    /**
     * Runs the crop sweep over the clip at [fd] (TAKES OWNERSHIP of the
     * fd: closed here, always). Frame 0, maxDim = 256 (the cropped output
     * stays under the stats long edge, so the pack lands at exactly the
     * crop output dims), no LUT, warp off, statsMode = SYNC (the paused
     * exact frame). Everything touching the clip handle runs on the
     * shared preview engine thread; logs one crop-sweep line per case
     * plus a summary under [TAG]; returns the summary line, or null when
     * disabled / unopenable.
     */
    suspend fun runCrop(fd: Int): String? {
        if (!enabled) {
            PreviewRenderer.closeFdQuietly(fd)
            return null
        }
        val engine = PreviewRenderer.previewEngine
        return try {
            val future = engine.submit<String?> { runCropBlocking(fd) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { future.get() }
            } catch (e: ExecutionException) {
                Log.w(TAG, "crop-sweep aborted: ${e.cause?.message}")
                null
            }
        } finally {
            PreviewRenderer.closeFdQuietly(fd)
        }
    }

    private fun runCropBlocking(fd: Int): String? {
        if (!NativeSource.nativeAvailable) {
            Log.w(TAG, "crop-sweep aborted: ${NativeSource.nativeUnavailableMessage}")
            return null
        }
        val info = NativeSource.jniPreviewOpen(fd)
        if (info == null || info.size < 7 || info[0] == 0) {
            Log.w(TAG, "crop-sweep aborted: clip open failed (${NativeSource.previewLastError()})")
            return null
        }
        val clip = info[0].toLong()
        // The PREVIEW session (the window pass's own door — the crop rides
        // WINDOW_FRAGMENT): the same fifteen validated single-source-of-truth
        // sources the editor's NativeGpuPreviewDevelop passes.
        val session = NativeSource.jniPreviewSessionOpen(
            GlesShaderSources.FULLSCREEN_VERTEX,
            GlesShaderSources.FULLSCREEN_VERTEX_310,
            GlesShaderSources.DEVELOP_FRAGMENT_PRE,
            GlesShaderSources.DEVELOP_FRAGMENT_POST,
            GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
            GlesShaderSources.SHARPNESS_H,
            GlesShaderSources.SHARPNESS_V,
            GlesShaderSources.DETAIL_H,
            GlesShaderSources.DETAIL_V,
            GlesShaderSources.WARP,
            GlesShaderSources.NR_CHROMA_H,
            GlesShaderSources.NR_CHROMA_V,
            GlesShaderSources.NR_LUMA,
            GlesShaderSources.STORE_FRAGMENT,
            GlesShaderSources.WINDOW_FRAGMENT,
        )
        if (session == 0L) {
            Log.w(TAG, "crop-sweep aborted: gpu preview session open failed " +
                "(${NativeSource.previewLastError()})")
            NativeSource.jniCloseSource(clip)
            return null
        }
        val neutral = GradeK()
        val parcel = GradeParcel.encode(neutral)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val maxDim = 256 // stays under kStatsLongEdge: no rescale between the legs
        // The crop parameters switch the window pass on, and since 2b1e2ff a
        // window draw with no attached surface is a failed present (the JNI
        // seam routes it to the CPU fallback and returns null): before this
        // the leg skipped all 21 cases ("no attached surface"). A throwaway
        // ImageReader surface gives the session a real window; a listener
        // drains every frame so eglSwapBuffers never blocks on a full queue.
        val readerThread = android.os.HandlerThread("crop-sweep-reader").apply { start() }
        val reader = android.media.ImageReader.newInstance(
            maxDim, maxDim, android.graphics.PixelFormat.RGBA_8888, 3,
        )
        reader.setOnImageAvailableListener(
            { r -> r.acquireLatestImage()?.close() },
            android.os.Handler(readerThread.looper),
        )
        val attached = try {
            NativeSource.jniPreviewSessionSetSurface(session, reader.surface)
        } catch (t: Throwable) {
            false
        }
        if (!attached) {
            Log.w(TAG, "crop-sweep aborted: could not attach the reader surface " +
                "(${NativeSource.previewLastError()})")
        }
        try {
            if (!attached) return null
            // The CPU reference ONCE (neutral grade, same frame + maxDim):
            // the uncropped render cropCases crop through CropState below.
            val args = GradeMapping.toNative(neutral)
            fun cpuUncropped(): IntArray? = NativeSource.jniPreviewRender(
                clip, 0,
                args.exposureEv, args.highlights, args.tempK, args.tint,
                args.saturation, args.blackPoint, args.whitePoint,
                args.shadows, args.midtones, args.contrast,
                args.asShot, args.highlightReconstruction, args.keepVignetting,
                args.sharpness, args.detail, args.tonemap,
                null, args.lutStrength,
                args.noiseReduction, args.chromaDenoise,
                false, args.temporalStrength,
                args.wheelShadowX, args.wheelShadowY,
                args.wheelMidX, args.wheelMidY,
                args.wheelHighX, args.wheelHighY,
                args.splitShadowHue, args.splitHighlightHue, args.splitBalance,
                // Stab off: gyroPath null + the five zeroed stab scalars.
                null, 0, 0f, 0f, 0f, 0f,
                maxDim,
                -1, -1, -1, -1, -1,
            )
            val rows = ArrayList<Pair<CropCase, Stats>>()
            val diagRows = ArrayList<Pair<CropCase, Stats>>()
            var skipped = 0
            val standard = cropCases()
            for (c in standard + cropDiagnosticCases()) {
                val diag = c !in standard
                // GPU leg: the preview session's stats pack IS the cropped
                // region (the window pass maps the crop; SYNC readback for
                // the paused exact frame). The parcel is the mapRect'ed
                // rect + raw deg — manualDelta 0 keeps it effective-space,
                // the exact PublishCrop mapping with spaceDeltaDeg = 0.
                val cropParams = floatArrayOf(c.x, c.y, c.w, c.h, c.deg)
                val gpuPack = NativeSource.jniPreviewSessionRenderFrameParcel(
                    session, clip, 0, maxDim, parcel,
                    null, 0f, -1, -1, -1, -1, -1,
                    false, identity, PreviewStatsMode.SYNC.native,
                    true, cropParams,
                    // A3 zebra quad (2026-11-23, guide A3): the sweep's
                    // crop leg compares CLEAN packs — zebra stays OFF
                    // (the draw-3 stats pin native-side would keep the
                    // pack clean anyway, but the honest explicit off
                    // documents the sweep never paints stripes).
                    false, 253, 12, 200f / 255f,
                )
                // CPU leg: the uncropped render at the same maxDim, cropped
                // by the EXACT publish math (applyToArgb, spaceDeltaDeg = 0).
                val cpuUncrop = cpuUncropped()
                if (gpuPack == null || cpuUncrop == null || cpuUncrop.size < 2) {
                    skipped++
                    Log.w(TAG, "crop-sweep rect=${c.x},${c.y},${c.w},${c.h} deg=${c.deg}: " +
                        "render failed gpu=${gpuPack != null} cpu=${cpuUncrop != null} " +
                        "(${NativeSource.previewLastError()})")
                    continue
                }
                val gw = gpuPack[0]
                val gh = gpuPack[1]
                if (gw <= 0 || gh <= 0 || gpuPack.size < 2 + gw * gh) {
                    skipped++
                    Log.w(TAG, "crop-sweep rect=${c.x},${c.y},${c.w},${c.h} deg=${c.deg}: " +
                        "malformed gpu pack ${gpuPack.size} at ${gw}x${gh}")
                    continue
                }
                val gpuArgb = gpuPack.copyOfRange(2, 2 + gw * gh)
                val cw = cpuUncrop[0]
                val ch = cpuUncrop[1]
                if (cw <= 0 || ch <= 0 || cpuUncrop.size < 2 + cw * ch) {
                    skipped++
                    continue
                }
                val cpuCropped = com.ritim.ritimcraw.data.CropState.applyToArgb(
                    cpuUncrop.copyOfRange(2, 2 + cw * ch),
                    cw, ch,
                    com.ritim.ritimcraw.data.CropState.RectF(c.x, c.y, c.w, c.h),
                    c.deg,
                    spaceDeltaDeg = 0,
                )
                if (cpuCropped.width != gw || cpuCropped.height != gh) {
                    // A dims mismatch is ITSELF a finding: the crop's output
                    // dims disagreed (the worst kind of crop bug — different
                    // regions). Report and skip the pixel compare.
                    skipped++
                    Log.w(TAG, "crop-sweep rect=${c.x},${c.y},${c.w},${c.h} deg=${c.deg}: " +
                        "DIMS MISMATCH gpu=${gw}x${gh} cpu=${cpuCropped.width}x${cpuCropped.height}")
                    continue
                }
                val stats = compare(argbBytes(gpuArgb), argbBytes(cpuCropped.argb))
                if (diag) {
                    diagRows += c to stats
                    val (outliers, inBand) = outlierSplit(
                        argbBytes(gpuArgb), argbBytes(cpuCropped.argb), gw, gh,
                    )
                    Log.i(TAG, cropLine(c.x, c.y, c.w, c.h, c.deg, stats).replaceFirst("crop-sweep", "crop-diag") +
                        " out=${gw}x${gh} over2=$outliers inBand=$inBand")
                    // Debug only: the spatial pattern of the disagreement (GPU, CPU and an
                    // amplified |diff| as PNGs under files/crop-diag/), for the rotated cases.
                    if (c.deg != 0f) dumpCropDiag(c, gw, gh, gpuArgb, cpuCropped.argb)
                } else {
                    rows += c to stats
                    Log.i(TAG, cropLine(c.x, c.y, c.w, c.h, c.deg, stats))
                }
            }
            if (diagRows.isNotEmpty()) {
                val worst = diagRows.maxByOrNull { it.second.maxDiffLsb }!!
                Log.i(TAG, "crop-diag summary: cases=${diagRows.size} worstMaxDiffLsb=${worst.second.maxDiffLsb} " +
                    "(rect=${worst.first.x},${worst.first.y},${worst.first.w},${worst.first.h} deg=${worst.first.deg})")
            }
            val summary = cropSummaryLine(rows, skipped)
            Log.i(TAG, summary)
            return summary
        } finally {
            try {
                NativeSource.jniPreviewSessionSetSurface(session, null)
            } catch (t: Throwable) {
                // closing anyway
            }
            NativeSource.jniPreviewSessionClose(session)
            NativeSource.jniCloseSource(clip)
            reader.close()
            readerThread.quitSafely()
        }
    }

    // ---- the v2 chain sweep's runner (device; the preview session's doors) ---

    fun v2Line(c: V2Case, s: Stats): String =
        String.format(
            java.util.Locale.US,
            "v2-sweep %s %s: maxDiffLsb=%d mismatch=%.3f%% over1=%.3f%% samples=%d",
            c.control, c.label, s.maxDiffLsb, s.mismatchPct, s.over1Pct, s.samples,
        )

    fun v2SummaryLine(rows: List<Pair<V2Case, Stats>>, skipped: Int): String {
        val worst = rows.maxByOrNull { it.second.maxDiffLsb }
        return "v2-sweep summary: cases=${rows.size} skipped=$skipped " +
            "worstMaxDiffLsb=${worst?.second?.maxDiffLsb ?: 0}" +
            (if (worst != null) " (${worst.first.control} ${worst.first.label})" else "")
    }

    /**
     * Runs the v2 chain sweep over the clip at [fd] (TAKES OWNERSHIP of
     * the fd: closed here, always). Frame 0, maxDim 256 (the stats pack's
     * own tier: dims stay honest), no LUT, warp off, zebra off, crop off.
     * The GPU leg rides the PREVIEW session — the only session with the
     * F1-5/F1-6 nodes doors; initNodes compiles the two v2 programs when
     * the session opens, setColorModelChain hands each case's chain, and
     * the render door publishes the stats pack. The CPU reference is the
     * CPU PREVIEW door's own chainWire leg at the same maxDim (not the
     * export door: the pack and the CPU preview BOTH scale by maxDim, so
     * the bytes compare 1:1 — the crop sweep's same-dims rule).
     */
    suspend fun runV2(fd: Int): String? {
        if (!enabled) {
            PreviewRenderer.closeFdQuietly(fd)
            return null
        }
        val engine = PreviewRenderer.previewEngine
        return try {
            val future = engine.submit<String?> { runV2Blocking(fd) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { future.get() }
            } catch (e: java.util.concurrent.ExecutionException) {
                Log.w(TAG, "v2-sweep aborted: ${e.cause?.message}")
                null
            }
        } finally {
            PreviewRenderer.closeFdQuietly(fd)
        }
    }

    private fun runV2Blocking(fd: Int): String? {
        if (!NativeSource.nativeAvailable) {
            Log.w(TAG, "v2-sweep aborted: ${NativeSource.nativeUnavailableMessage}")
            return null
        }
        val info = NativeSource.jniPreviewOpen(fd)
        if (info == null || info.size < 7 || info[0] == 0) {
            Log.w(TAG, "v2-sweep aborted: clip open failed (${NativeSource.previewLastError()})")
            return null
        }
        val clip = info[0].toLong()
        val session = NativeSource.jniPreviewSessionOpen(
            GlesShaderSources.FULLSCREEN_VERTEX,
            GlesShaderSources.FULLSCREEN_VERTEX_310,
            GlesShaderSources.DEVELOP_FRAGMENT_PRE,
            GlesShaderSources.DEVELOP_FRAGMENT_POST,
            GlesShaderSources.DEVELOP_FRAGMENT_TAIL,
            GlesShaderSources.SHARPNESS_H,
            GlesShaderSources.SHARPNESS_V,
            GlesShaderSources.DETAIL_H,
            GlesShaderSources.DETAIL_V,
            GlesShaderSources.WARP,
            GlesShaderSources.NR_CHROMA_H,
            GlesShaderSources.NR_CHROMA_V,
            GlesShaderSources.NR_LUMA,
            GlesShaderSources.STORE_FRAGMENT,
            GlesShaderSources.WINDOW_FRAGMENT,
        )
        if (session == 0L) {
            Log.w(TAG, "v2-sweep aborted: gpu preview session open failed " +
                "(${NativeSource.previewLastError()})")
            NativeSource.jniCloseSource(clip)
            return null
        }
        var nodesInit: IntArray? = null
        var v2Reader: android.media.ImageReader? = null
        var v2ReaderThread: android.os.HandlerThread? = null
        try {
            nodesInit = runCatching {
                NativeSource.jniPreviewSessionInitNodes(
                    session,
                    GlesShaderSources.NODES,
                    GlesShaderSources.FULLSCREEN_VERTEX,
                    GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT,
                )
            }.getOrNull()
            if (nodesInit == null || nodesInit.isEmpty() || nodesInit[0] == 0) {
                Log.w(TAG, "v2-sweep aborted: nodes programs did not compile " +
                    "(${NativeSource.previewLastError()})")
                NativeSource.jniPreviewSessionClose(session)
                NativeSource.jniCloseSource(clip)
                return null
            }
            Log.i(TAG, "v2-sweep nodes init: fragmentUniformVectors=${nodesInit[1]} " +
                "textureImageUnits=${nodesInit[2]}")
            val maxDim = 256
            // The window pass present needs an attached surface (a window draw with none is a failed present: every
            // node case reported "no attached surface" on its first run, 2026-11-25). A throwaway ImageReader surface
            // gives the session a real window, drained by a listener so eglSwapBuffers never blocks (the crop sweep's
            // pattern).
            val thread = android.os.HandlerThread("v2-sweep-reader").apply { start() }
            v2ReaderThread = thread
            val reader = android.media.ImageReader.newInstance(maxDim, maxDim, android.graphics.PixelFormat.RGBA_8888, 3)
            v2Reader = reader
            reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, android.os.Handler(thread.looper))
            val attached = runCatching { NativeSource.jniPreviewSessionSetSurface(session, reader.surface) }.getOrDefault(false)
            if (!attached) {
                Log.w(TAG, "v2-sweep aborted: could not attach the reader surface (${NativeSource.previewLastError()})")
                return null
            }
            val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
            fun cpu(c: V2Case): IntArray? = NativeSource.jniPreviewRender(
                clip, 0,
                c.grade.exposureEv, c.grade.highlights, c.grade.tempK, c.grade.tint,
                c.grade.saturation, c.grade.blackPoint, c.grade.whitePoint,
                c.grade.shadows, c.grade.midtones, c.grade.contrast,
                c.grade.asShot, c.grade.highlightReconstruction, c.grade.keepVignetting,
                c.grade.sharpness, c.grade.detail, c.grade.tonemap,
                null, c.grade.lutStrength,
                c.grade.noiseReduction, c.grade.chromaDenoise,
                false, c.grade.temporalStrength,
                c.grade.wheelShadowX, c.grade.wheelShadowY,
                c.grade.wheelMidX, c.grade.wheelMidY,
                c.grade.wheelHighX, c.grade.wheelHighY,
                c.grade.splitShadowHue, c.grade.splitHighlightHue, c.grade.splitBalance,
                null, 0, 0f, 0f, 0f, 0f,
                maxDim,
                -1, -1, -1, -1, -1,
                c.chainWire,
            )
            val rows = ArrayList<Pair<V2Case, Stats>>()
            var skipped = 0
            for (c in v2Cases()) {
                val chainOk = runCatching {
                    NativeSource.jniPreviewSessionSetColorModelChain(
                        session, c.chainWire, c.drtUniforms,
                    )
                }.getOrDefault(false)
                if (!chainOk) {
                    skipped++
                    Log.w(TAG, v2Line(c, Stats(0, 0, 0, 0, 0.0)) + " SKIPPED (chain door refused: " +
                        NativeSource.previewLastError() + ")")
                    continue
                }
                val cpuOut = cpu(c)
                val gpuOut = NativeSource.jniPreviewSessionRenderFrameParcel(
                    session, clip, 0, maxDim, GradeParcel.encode(c.grade),
                    null, 0f, -1, -1, -1, -1, -1,
                    false, identity, PreviewStatsMode.SYNC.native,
                    false, floatArrayOf(0f, 0f, 1f, 1f, 0f),
                    false, 253, 12, 200f / 255f,
                )
                if (cpuOut == null || cpuOut.size < 2 || gpuOut == null || gpuOut.size < 2) {
                    skipped++
                    Log.w(TAG, "v2-sweep ${c.control} ${c.label}: render failed " +
                        "gpu=${gpuOut != null} cpu=${cpuOut != null} " +
                        "(${NativeSource.previewLastError()})")
                    continue
                }
                val gw = gpuOut[0]; val gh = gpuOut[1]
                val cw = cpuOut[0]; val ch = cpuOut[1]
                if (gw != cw || gh != ch) {
                    skipped++
                    Log.w(TAG, "v2-sweep ${c.control} ${c.label}: DIMS MISMATCH " +
                        "gpu=${gw}x${gh} cpu=${cw}x${ch}")
                    continue
                }
                val gpuArgb = gpuOut.copyOfRange(2, 2 + gw * gh)
                val cpuArgb = cpuOut.copyOfRange(2, 2 + cw * ch)
                val stats = compare(argbBytes(gpuArgb), argbBytes(cpuArgb))
                rows += c to stats
                Log.i(TAG, v2Line(c, stats))
                // The frame digest (the grade sweep's karne line, the
                // same shape): the v2 chain's before/after content proof.
                Log.i(TAG, "v2-sweep ${c.control} ${c.label}: frameDigest=cpu:%08x gpu:%08x"
                    .format(frameDigest(argbBytes(cpuArgb)), frameDigest(argbBytes(gpuArgb))))
                // The CLEAR after every case: the next case's chain is
                // handed explicitly, but the sweep's own discipline keeps
                // the session's state obvious per case (K2's shape
                // re-asserted even on the last one).
                NativeSource.jniPreviewSessionSetColorModelChain(
                    session, NodeChainWire.encode(
                        com.ritim.ritimcraw.data.grade2.ClipGradeV2(), emptyList(),
                    ), null,
                )
            }
            val summary = v2SummaryLine(rows, skipped)
            Log.i(TAG, summary)
            return summary
        } finally {
            NativeSource.jniPreviewSessionClose(session)
            runCatching { v2Reader?.close() }
            runCatching { v2ReaderThread?.quitSafely() }
            NativeSource.jniCloseSource(clip)
        }
    }
}
