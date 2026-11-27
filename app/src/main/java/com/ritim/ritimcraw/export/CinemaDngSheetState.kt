package com.ritim.ritimcraw.export

import kotlin.math.ceil

/**
 * The CinemaDNG export sheet section's decisions as pure functions (night
 * E-F, docs/CINEMADNG_EXPORT_PLAN.md R1): what the section shows (the
 * estimate, the free space, the warn/lock lines), what is enabled (the
 * Start button's gate), and the raw-note line — the section composable
 * only renders it, so every state -> text/enable decision is JVM-tested.
 *
 * THE ONE RULE THE SHEET ENFORCES: the storage gate (the plan's own):
 *   free < required           -> LOCK (the button refuses, the line says why)
 *   free < required x 1,5     -> WARN (the button works, the line warns)
 *   free unknown (-1, the SAF folder could not be measured) -> no verdict,
 *     never a guessed lock — the mid-write exhaustion is the backstop
 */
object CinemaDngSheetState {

    /** What the section's verdict line shows and the button does. */
    sealed interface Verdict {
        /** No folder picked: the button refuses, the line says pick one. */
        data object NoFolder : Verdict

        /** The plan is fine: the button works. */
        data class Ok(val requiredBytes: Long, val freeBytes: Long) : Verdict

        /** Free space covers the write but not x1,5: the button works, the line warns. */
        data class Warn(val requiredBytes: Long, val freeBytes: Long) : Verdict

        /** Free space below required: the button refuses. */
        data class Locked(val requiredBytes: Long, val freeBytes: Long) : Verdict
    }

    /**
     * The verdict from the world facts. [folderUri] blank -> NoFolder;
     * [freeBytes] -1 (unmeasurable) -> Ok with the free part hidden
     * (never a guessed lock); otherwise the plan's own gate
     * ([CinemaDngPlan.storageGate]).
     */
    fun verdict(
        folderUri: String?,
        clipFrameCount: Int,
        firstFrame: Int,
        lastFrame: Int,
        width: Int,
        height: Int,
        freeBytes: Long,
    ): Verdict {
        if (folderUri.isNullOrBlank()) return Verdict.NoFolder
        if (clipFrameCount <= 0 || width <= 0 || height <= 0) {
            // No honest plan is derivable: no verdict (the sheet says the
            // clip facts are missing, never a guessed size).
            return Verdict.Ok(requiredBytes = 0, freeBytes = freeBytes)
        }
        val required = CinemaDngPlan.requiredBytes(
            CinemaDngPlan.frameCount(firstFrame, lastFrame, clipFrameCount),
            width, height,
        )
        if (freeBytes < 0) return Verdict.Ok(required, freeBytes)
        return when (CinemaDngPlan.storageGate(freeBytes, required)) {
            is StorageGate.Locked -> Verdict.Locked(required, freeBytes)
            is StorageGate.Warn -> Verdict.Warn(required, freeBytes)
            else -> Verdict.Ok(required, freeBytes)
        }
    }

    /** True when the Start button may fire ([Verdict] not NoFolder/Locked). */
    fun canStart(v: Verdict): Boolean = v !is Verdict.NoFolder && v !is Verdict.Locked

    /**
     * The displayed estimate: "~1,9 GiB" — bytes rendered in the nearest
     * binary unit (KiB/MiB/GiB/TiB), one decimal under 100, none at/above
     * (the document's own table's shape: "1,9 GiB", "139 GiB").
     * Locale.ROOT digits (a number stays a number in every locale).
     */
    fun sizeText(bytes: Long): String {
        val kib = 1024.0
        val mib = kib * 1024
        val gib = mib * 1024
        val tib = gib * 1024
        val v = bytes.toDouble()
        // One decimal under 100 of the unit, none at/above: "1.9 GiB" and
        // "139 GiB" are the document's own two spellings.
        fun fmt(value: Double, unit: String): String {
            val s = if (value >= 100.0) {
                String.format(java.util.Locale.ROOT, "%.0f", value)
            } else {
                String.format(java.util.Locale.ROOT, "%.1f", value)
            }
            return "$s $unit"
        }
        return when {
            v >= tib -> fmt(v / tib, "TiB")
            v >= gib -> fmt(v / gib, "GiB")
            v >= mib -> fmt(v / mib, "MiB")
            v >= kib -> fmt(v / kib, "KiB")
            else -> "$bytes B"
        }
    }

    /**
     * The estimate line the section shows: "<frames> frames · ~<size>" —
     * or null when no honest estimate exists (unknown dims / unusable
     * range — the line hides rather than guessing, the plan's rule).
     */
    fun estimateText(
        clipFrameCount: Int,
        firstFrame: Int,
        lastFrame: Int,
        width: Int,
        height: Int,
    ): String? {
        val frames = CinemaDngPlan.frameCount(firstFrame, lastFrame, clipFrameCount)
        if (frames == CinemaDngPlan.REJECT || width <= 0 || height <= 0) return null
        val bytes = CinemaDngPlan.estimatedBytes(frames, width, height)
        return "$frames frames · ~${sizeText(bytes)}"
    }

    /** The plan document's own table rows, verified against the formula. */
    fun estimateBytes(frames: Int, width: Int, height: Int): Long =
        ceil(frames.toDouble() * width * height * 2.0).toLong()
}
