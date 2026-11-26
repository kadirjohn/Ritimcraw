package com.ritim.ritimcraw.engine

import android.util.Log
import com.ritim.ritimcraw.data.GradeK
import com.ritim.ritimcraw.export.NativeGpuDevelop
import java.util.concurrent.ExecutionException

/**
 * DEBUG steady-state cost bench for the GPU highlight stages (owner request 2026-11-27): the same frame rendered
 * N times through the GPU develop session with four grades, wall time per render (readback included, identical in
 * every mode, so the DIFFERENCE of medians is the stage's added cost):
 *   default        stage 3 (Neutral) on                       - the normal chain
 *   hr-off         stage 3 off                                - default - hr-off = Neutral stage 3's cost
 *   recovery       highlightRecovery = true                   - recovery - default = Recovery's added cost
 * One "recbench" line per grade under [GradeParitySweep.TAG] + one summary line. Per-pass times of the recovery
 * chain come from `setprop debug.ritim.rectime 1` (the pipeline's own glFinish brackets) in the same run.
 * Runs only when the marker file `files/recbench` exists (debug builds; never in release, never by default).
 */
object RecoveryBench {
    const val TAG = GradeParitySweep.TAG
    private const val WARMUP = 6

    suspend fun run(fd: Int, frameIndex: Int, label: String, iterations: Int = 30): String? {
        val engine = PreviewRenderer.previewEngine
        return try {
            val future = engine.submit<String?> { runBlocking(fd, frameIndex, label, iterations) }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { future.get() }
            } catch (e: ExecutionException) {
                Log.w(TAG, "recbench aborted: ${e.cause?.message}")
                null
            }
        } finally {
            PreviewRenderer.closeFdQuietly(fd)
        }
    }

    private fun runBlocking(fd: Int, frameArg: Int, label: String, iterations: Int): String? {
        if (!NativeSource.nativeAvailable) return null
        val info = NativeSource.jniPreviewOpen(fd)
        if (info == null || info.size < 7 || info[0] == 0) {
            Log.w(TAG, "recbench aborted: clip open failed")
            return null
        }
        val clip = info[0].toLong()
        // frameArg < 0 = "find a frame with NO clipped raw site" (the early-exit case): scan the clip's frames
        // with the RAW histogram's white-clip counts.
        var frameIndex = frameArg
        if (frameArg < 0) {
            val fc = info[1]
            val step = maxOf(1, fc / 60)
            frameIndex = -1
            var f = 1
            while (f < fc) {
                val h = NativeSource.jniRawHistogram(clip, f)?.let { RawHistogram.fromJni(it) }
                if (h != null && h.whiteClips.sum() == 0) { frameIndex = f; break }
                f += step
            }
            if (frameIndex < 0) {
                Log.i(TAG, "recbench $label: no unclipped frame found, skipped")
                NativeSource.jniCloseSource(clip)
                return null
            }
            Log.i(TAG, "recbench $label: unclipped frame = $frameIndex")
        }
        val session = NativeGpuDevelop.openSession()
        if (session == 0L) {
            NativeSource.jniCloseSource(clip)
            return null
        }
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        fun render(g: GradeK): ByteArray? = NativeGpuDevelop.renderFrameParcel(
            session, clip, frameIndex, GradeParcel.encode(g),
            null, 0f, -1, -1, -1, -1, -1, false, identity,
        )
        try {
            val modes = listOf(
                "default" to GradeK(),
                "hr-off" to GradeK(highlightReconstruction = false),
                "recovery" to GradeK(highlightRecovery = true),
            )
            val medians = HashMap<String, Double>()
            // Two rounds over the modes (A B C A B C): the first round pays the clock ramp, the second is the
            // steady state; begin/end markers let the log reader attribute the pipeline's own per-pass
            // `rectime` lines (setprop debug.ritim.rectime 1) to the mode that produced them.
            for (round in 1..2) {
                for ((name, g) in modes) {
                    Log.i(TAG, "recbench-mark $label f$frameIndex round=$round $name begin")
                    repeat(WARMUP) { if (render(g) == null) return null }
                    val ms = DoubleArray(iterations)
                    for (i in 0 until iterations) {
                        val t0 = System.nanoTime()
                        if (render(g) == null) return null
                        ms[i] = (System.nanoTime() - t0) / 1e6
                    }
                    Log.i(TAG, "recbench-mark $label f$frameIndex round=$round $name end")
                    ms.sort()
                    val median = ms[iterations / 2]
                    if (round == 2) medians[name] = median
                    Log.i(
                        TAG,
                        "recbench $label f$frameIndex round=$round $name n=$iterations median=%.1f p90=%.1f min=%.1f max=%.1f ms (wall, readback included)"
                            .format(median, ms[(iterations * 9) / 10], ms[0], ms[iterations - 1]),
                    )
                }
            }
            val d = medians["default"] ?: 0.0
            val line = "recbench $label f$frameIndex summary: neutral stage 3 cost = %.1f ms (default - hr-off), recovery added = %.1f ms (recovery - default)"
                .format(d - (medians["hr-off"] ?: d), (medians["recovery"] ?: d) - d)
            Log.i(TAG, line)
            return line
        } finally {
            NativeGpuDevelop.closeSession(session)
            NativeSource.jniCloseSource(clip)
        }
    }
}
