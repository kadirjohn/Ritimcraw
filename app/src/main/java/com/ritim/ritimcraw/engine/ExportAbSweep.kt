package com.ritim.ritimcraw.engine

import com.ritim.ritimcraw.BuildConfig
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.NativeGpuDevelop
import java.util.Locale

/**
 * Export A/B regression harness ("A7", GPU roadmap guide §A7, owner
 * request 2026-11-23): the whole-pipeline check that would have caught
 * the warp ping-pong slot bug before it shipped — the same frames of a
 * gyro-attached clip rendered through the CPU export door
 * (NativeSource.jniExportFrameParcel, the reference) and the GPU develop
 * session (the export's GPU arm: warp from jniStabWarpResolve with the
 * WAITING refine), byte-compared with GradeParitySweep's machinery.
 *
 * WHY THIS IS NEXT TO GradeParitySweep AND NOT INSIDE IT: the per-control
 * sweep compares single controls on frame 0 with NO stabilization; the
 * silent export bug class is the COMPOSITION (grade riding on top of
 * warp — a warp that swallows the grade, a grade that silences the
 * warp). This harness pins 4 composite cases at 3 frame indices, so
 * both axes are exercised together, per-frame, at export resolution.
 * GradeParitySweep.kt stays untouched: this object reuses its public
 * TAG / Stats / compare (sibling batch item 261123-g3v may be editing
 * that file; the pure seam here is public API only).
 *
 * The grade axis pins all-pointwise POST stages (exposure/highlights/
 * shadows/contrast/saturation, guide §A7 case 2): the per-control
 * sweep already covers LUT/NR/sharpness alone, and LUT would drag the
 * cube copy into an unrelated-axis failure. The stab sub-values ride
 * GradeK's own UI defaults (strength 50, zoom limit 1.25, Smooth,
 * no trim, focal 0 = container) — exactly what a fresh export snapshot
 * carries when the user only toggles the stab switch.
 *
 * Case list (fixed order, stable ids for the log lines):
 *   neutral      GradeK() all defaults — the baseline pair, pins the
 *                no-grade/no-warp identity of the two doors.
 *   grade        the pinned POST grade above, stabilization OFF.
 *   stab         GradeK() + stabilization ON (sub-values at defaults).
 *   grade-stab   the grade case's grade + stabilization ON — the
 *                composition case, the one the warp slot bug broke.
 *
 * All Android/native calls live in the [run] runner ONLY; everything
 * above it is pure JVM-testable decision logic (the GradeParitySweep
 * split — the host suite never needs the .so).
 */
object ExportAbSweep {

    /** The shared parity tag (GradeParitySweep's, reused — one grep for both harnesses). */
    const val TAG = GradeParitySweep.TAG

    /** Debug builds only — never runs or logs in release (the sweep gate). */
    val enabled: Boolean get() = BuildConfig.DEBUG

    /**
     * One A/B case: a stable [id] for the log lines, a human [label],
     * and the export-snapshot grade. The stab axis rides the grade
     * ([stabilization] is [grade]'s flag) exactly like a real export
     * snapshot — there is no separate stab request; the runner threads
     * grade.stabilization* into BOTH doors per frame.
     */
    data class AbCase(
        val id: String,
        val label: String,
        val grade: GradeK,
    ) {
        val stabilization: Boolean get() = grade.stabilization
    }

    /**
     * The pinned POST-stage grade for the grade-bearing cases (guide
     * §A7, 2026-11-23): every listed control moved a visible amount,
     * no LUT/NR/sharpness (deliberately — see the object KDoc).
     */
    private val EXPORT_GRADE = GradeK(
        exposureEv = 1f,
        highlights = -50f,
        shadows = 50f,
        contrast = 50f,
        saturation = 50f,
    )

    /** The 4 fixed cases in the pinned order (neutral, grade, stab, grade-stab). */
    fun cases(): List<AbCase> = listOf(
        AbCase("neutral", "no grade, no warp", GradeK()),
        AbCase("grade", "grade only", EXPORT_GRADE),
        // GradeK's own defaults for the sub-values ARE the UI defaults
        // (strength 50 / zoom 1.25 / Smooth / trim 0 / focal 0).
        AbCase("stab", "stabilization only", GradeK(stabilization = true)),
        AbCase(
            "grade-stab", "grade + stabilization",
            EXPORT_GRADE.copy(stabilization = true),
        ),
    )

    /**
     * First / middle / near-end frame indices (guide §A7): the warp
     * and the grade interact differently across the clip's motion, so
     * three spread samples beat any one frame. [0, frameCount / 2,
     * frameCount - 1] clamped to >= 0, deduped, order-preserving; empty
     * for frameCount <= 0 (the runner's open-failure path).
     */
    fun frameIndices(frameCount: Int): List<Int> {
        if (frameCount <= 0) return emptyList()
        val raw = listOf(0, frameCount / 2, frameCount - 1)
        val out = ArrayList<Int>(3)
        for (i in raw) {
            if (i >= 0 && i !in out) out += i
        }
        return out
    }

    /**
     * The EXPORT_MISMATCH flag rule (guide §A7 design point 3,
     * pinned): mismatch strictly above 0.5% OR maxDiffLsb strictly
     * above 2. The exact-threshold values are NOT flags — the sweep's
     * rounding jitter lives exactly at 1-2 LSB and must not cry wolf.
     */
    fun isMismatch(s: GradeParitySweep.Stats): Boolean =
        s.mismatchPct > 0.5 || s.maxDiffLsb > 2

    /**
     * Gyro paused (owner decision 2026-11-23, FeatureFlags): a case that
     * turns stabilization on is not a meaningful A/B while the gate forces
     * every real export unstabilized, so it is SKIPPED, never run and
     * never counted as EXPORT_MISMATCH. Non-stab cases are unaffected.
     * [gyroAllowed] defaults to the flag; tests pass both states.
     */
    fun pausedSkip(
        c: AbCase,
        gyroAllowed: Boolean = StabilizationGate.gyroWorkAllowed(),
    ): Boolean = c.stabilization && !gyroAllowed

    /** The log line of a paused skip: SKIPPED (gyro paused), no stats, no flag. */
    fun pausedLine(c: AbCase, frame: Int): String =
        "export-ab ${c.id} frame=$frame SKIPPED (gyro paused)"

    /**
     * One comparison's log line (GradeParitySweep.line's format
     * style, Locale.US pinned): export-ab {id} frame={n}: maxDiffLsb
     * mismatch% over1% meanSigned samples, plus " EXPORT_MISMATCH"
     * when [isMismatch] holds — the flag derives from the stats, so
     * the log line and the summary's flagged count can never disagree.
     */
    fun abLine(c: AbCase, frame: Int, s: GradeParitySweep.Stats): String =
        String.format(
            Locale.US,
            "export-ab %s frame=%d: maxDiffLsb=%d mismatch=%.3f%% over1=%.3f%% " +
                "meanSigned=%.3f samples=%d%s",
            c.id, frame, s.maxDiffLsb, s.mismatchPct, s.over1Pct, s.meanSigned,
            s.samples,
            if (isMismatch(s)) " EXPORT_MISMATCH" else "",
        )

    /**
     * The end-of-run summary (GradeParitySweep.summaryLine's shape):
     * case count, skipped count, flagged count, the worst maxDiffLsb
     * with its owning case id, and the wall-clock tookMs (the owner's
     * under-~2-minutes budget check). [rows] is (caseId, stats) for
     * every COMPARED pair — skips never reach it.
     */
    fun summaryLine(rows: List<Pair<String, GradeParitySweep.Stats>>, skipped: Int, flagged: Int, tookMs: Long): String {
        val worst = rows.maxByOrNull { it.second.maxDiffLsb }
        return String.format(
            Locale.US,
            "export-ab summary: cases=%d skipped=%d flagged=%d worstMaxDiffLsb=%d%s tookMs=%d",
            rows.size, skipped, flagged, worst?.second?.maxDiffLsb ?: 0,
            if (worst != null) " (${worst.first})" else "", tookMs,
        )
    }

    /**
     * Runs the A/B over the clip at [fd] with the gyro log at [gyroPath]
     * (TAKES OWNERSHIP of the fd: closed here, always — the
     * GradeParitySweep.run contract). Everything touching the clip
     * handle runs on the shared preview engine thread (the one-thread
     * rule: the decoder is not concurrency-safe and the stabilization
     * plan/gyro caches are thread-local). Logs one line per compared
     * pair plus this summary under [TAG]; returns the summary line, or
     * null when disabled / unopenable.
     */
    suspend fun run(fd: Int, gyroPath: String): String? {
        if (!enabled) {
            PreviewRenderer.closeFdQuietly(fd)
            return null
        }
        val engine = PreviewRenderer.previewEngine
        return try {
            val future = engine.submit<String?> { runBlocking(fd, gyroPath) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { future.get() }
            } catch (e: java.util.concurrent.ExecutionException) {
                android.util.Log.w(TAG, "export-ab aborted: ${e.cause?.message}")
                null
            }
        } finally {
            PreviewRenderer.closeFdQuietly(fd)
        }
    }

    /**
     * The engine-thread body (GradeParitySweep.runBlocking's shape).
     * ONE sync offset for both doors (guide §A7 design point 4 — the
     * pitfall that shipped the warp bug): the CPU export door waits
     * for the optical refine internally, so the GPU-side warp resolve
     * passes asyncRefine = false EXPLICITLY (the waiting refine;
     * never GpuPreviewSession.resolveWarp — it threads the preview's
     * background-refine value, which rides a different offset).
     */
    private fun runBlocking(fd: Int, gyroPath: String): String? {
        if (!NativeSource.nativeAvailable) {
            android.util.Log.w(TAG, "export-ab aborted: ${NativeSource.nativeUnavailableMessage}")
            return null
        }
        val info = NativeSource.jniPreviewOpen(fd)
        if (info == null || info.size < 7 || info[0] == 0) {
            android.util.Log.w(
                TAG, "export-ab aborted: clip open failed (${NativeSource.previewLastError()})",
            )
            return null
        }
        val handle = info[0].toLong()
        val frameCount = info[1]
        val w = info[2]
        val h = info[3]
        val expectedBytes = w * h * 3
        if (frameCount <= 0) {
            android.util.Log.w(TAG, "export-ab aborted: no frames (${NativeSource.previewLastError()})")
            NativeSource.jniCloseSource(handle)
            return null
        }
        val session = NativeGpuDevelop.openSession()
        if (session == 0L) {
            android.util.Log.w(
                TAG,
                "export-ab aborted: gpu develop session open failed (${NativeSource.previewLastError()})",
            )
            NativeSource.jniCloseSource(handle)
            return null
        }
        val identityM = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        try {
            val started = System.currentTimeMillis()
            var skipped = 0
            var flagged = 0
            val rows = ArrayList<Pair<String, GradeParitySweep.Stats>>()
            val indices = frameIndices(frameCount)
            for (c in cases()) {
                for (i in indices) {
                    // Gyro paused: stab cases are not an A/B (see pausedSkip).
                    if (pausedSkip(c)) {
                        skipped++
                        android.util.Log.i(TAG, pausedLine(c, i))
                        continue
                    }
                    // CPU side — the reference door, full resolution. The
                    // stab request values follow the Mp4Exporter frame-loop
                    // convention: gyro path + strength cross ONLY for stab
                    // cases (a non-null path on a grade-only case warps the
                    // reference and turns the comparison into noise);
                    // strength crosses as percent/100; mode/zoomLimit/
                    // focalMm/trimMs raw. No LUT in this harness: slot null
                    // + 0f and all five interp ints -1.
                    val stabGyroPath = if (c.stabilization) gyroPath else null
                    val stabStrength = if (c.stabilization) c.grade.stabilizationStrength / 100f else 0f
                    val cpu = NativeSource.jniExportFrameParcel(
                        handle, i, GradeParcel.encode(c.grade),
                        null, 0f, stabGyroPath, c.grade.stabilizationMode, stabStrength,
                        c.grade.stabilizationZoomLimit, c.grade.stabilizationFocalMm,
                        c.grade.stabilizationTrimMs, -1, -1, -1, -1, -1,
                    )
                    if (cpu == null || cpu.size != expectedBytes) {
                        skipped++
                        android.util.Log.w(
                            TAG,
                            "export-ab ${c.id} frame=$i skipped: cpu render failed " +
                                "or wrong size (${NativeSource.previewLastError()})",
                        )
                        continue
                    }
                    // GPU side — warp first, from the SAME handle, on the
                    // SAME thread, with the WAITING refine (the sync-offset
                    // rule; maxDim = 0 is the export full-res convention).
                    val warp = if (c.stabilization) {
                        try {
                            NativeSource.jniStabWarpResolve(
                                handle, gyroPath, c.grade.stabilizationMode, stabStrength,
                                c.grade.stabilizationZoomLimit, c.grade.stabilizationFocalMm,
                                c.grade.stabilizationTrimMs, i, 0,
                                // The export's waiting refine, WRITTEN OUT:
                                // both doors must ride one sync offset (§A7
                                // design point 4). The preview arm's
                                // background refine is forbidden here.
                                false,
                            )
                        } catch (e: IllegalStateException) {
                            null // no .so mid-run: the render below fails honestly
                        }
                    } else {
                        null
                    }
                    // A stab case with no warp verdict is NOT an A/B of
                    // stabilization — fold to identity (the session skips
                    // the draw) but count the comparison as SKIPPED with a
                    // log line, never a silent pass (guide §A7 pitfall:
                    // precisely the bug class this harness exists to catch).
                    if (c.stabilization && (warp == null || warp[0] == 0f)) {
                        skipped++
                        android.util.Log.w(
                            TAG,
                            "export-ab ${c.id} frame=$i skipped: no warp verdict " +
                                "(stabilization case rendered without warp)",
                        )
                        continue
                    }
                    val warpActive = warp != null && warp[0] != 0f
                    val warpM = if (warp != null && warp.size >= 10) warp.copyOfRange(1, 10) else identityM
                    val gpu = NativeGpuDevelop.renderFrameParcel(
                        session, handle, i, GradeParcel.encode(c.grade),
                        null, 0f, -1, -1, -1, -1, -1, warpActive, warpM,
                    )
                    // Size guard BEFORE the compare (GradeParitySweep.compare
                    // silently mins the lengths — a truncated GPU readback
                    // must skip, not compare a shape bug into a fake pass).
                    if (gpu == null || gpu.size != expectedBytes) {
                        skipped++
                        android.util.Log.w(
                            TAG,
                            "export-ab ${c.id} frame=$i skipped: gpu render failed " +
                                "or wrong size (expected $expectedBytes, got ${gpu?.size})",
                        )
                        continue
                    }
                    val stats = GradeParitySweep.compare(gpu, cpu)
                    if (isMismatch(stats)) flagged++
                    rows += c.id to stats
                    android.util.Log.i(TAG, abLine(c, i, stats))
                }
            }
            val summary = summaryLine(rows, skipped, flagged, System.currentTimeMillis() - started)
            android.util.Log.i(TAG, summary)
            return summary
        } finally {
            NativeGpuDevelop.closeSession(session)
            NativeSource.jniCloseSource(handle)
        }
    }
}
