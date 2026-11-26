package com.ritim.ritimcraw.export

import kotlin.math.ceil

/**
 * The pure planning layer of a CinemaDNG sequence export (night E-D3,
 * docs/CINEMADNG_EXPORT_PLAN.md): the numbers the sheet shows and the
 * gates it enforces BEFORE a single frame is written. No Android
 * imports, no file IO, no native calls — everything here is JVM-testable
 * against the plan document's own table.
 *
 * THE PLAN'S RULES, ONE TO ONE:
 *
 * 1. FRAME SIZE (an assumption, flagged): an uncompressed 16-bit CFA
 *    frame is width x height x 2 bytes. The document's "Varsayım
 *    (doğrulanacak)" is preserved VERBATIM in [FRAME_SIZE_UNMEASURED]:
 *    no per-frame DNG write has been measured on any device yet, so the
 *    estimate carries its provenance, and the estimate is what the
 *    storage rule checks against.
 *
 * 2. DISPLAYED SIZE = frames x frameSize, rounded up — the document's
 *    own table ("Toplam" column: 120 x 15,8 MiB ~ 1,9 GiB; the JVM test
 *    pins the exact strings). The 1,05 headroom is NOT in the displayed
 *    number: the document keeps the two apart — the table is the honest
 *    raw estimate, and the storage rule's "gerekli" is the DISPLAYED
 *    number times the headroom (the plan's own formula, "gerekli = kare
 *    x kare_boyutu x 1,05").
 *
 * 3. STORAGE GATE: free < required -> LOCK (the export button refuses;
 *    the plan's ERROR band, "Düzelt" = shorten the range); free <
 *    required x 1,5 -> WARN (exportable, the sheet says why); else OK.
 *    required = displayed x [SIZE_HEADROOM] (1,05), computed in Double
 *    and saturating at Long.MAX_VALUE rather than wrapping (a huge
 *    range is a lock, never a nonsense number).
 *
 * 4. "SÜRE BİLİNMEZ" (the plan's own rule, quoted): no per-frame write
 *    time has been measured on any device (the single-frame DNG export
 *    has never been timed), so the plan NEVER carries a duration
 *    estimate. [etaSeconds] is null exactly when the rule says so, and
 *    the sheet shows the existing "export_estimate_unknown" line — the
 *    ExportSheetModel.durationUnknown precedent. R1-0 (the measurement
 *    task) fills this in later; until then no number is invented.
 *
 * 5. FRAME RANGE: the whole clip is the default (the plan's "Seçili
 *    aralık dışında bütün klip varsayılanı yeterli mi?" stays an owner
 *    question — the model only counts what it is given). The count is
 *    an inclusive span: firstFrame..lastFrame on the clip's own
 *    numbering, clamped to the clip's frame count, and an inverted or
 *    empty span is a REJECTION (reported, never a silent 0 or 1).
 *
 * 6. WAV SIZE rides along when the clip has audio: PCM bytes for the
 *    range span at the source's own rate/channels (no resampling, the
 *    R1-4 rule) — 16-bit samples, one interleave frame per frame of
 *    video. The WAV is inside the 5% headroom: it is small against the
 *    DNG frames (16-bit x 2 channels x 48 kHz ~ 184 KB/s vs a 4K DNG
 *    frame ~ 15,8 MiB), and the plan document's own table carries no
 *    separate WAV term.
 */
object CinemaDngPlan {

    /**
     * The frame-size assumption is UNMEASURED (the plan's own flag).
     * This constant exists so a future R1-0 measurement replaces ONE
     * number, and the tests that pin it say so in their names.
     */
    const val FRAME_SIZE_UNMEASURED = true

    /** The storage rule's headroom factor (the plan's x 1,05). */
    const val SIZE_HEADROOM = 1.05

    /** The storage rule's warn multiple (the plan's x 1,5). */
    const val STORAGE_WARN_MULTIPLE = 1.5

    /** The sentinel [frameCount] returns on an unusable range. */
    const val REJECT = -1

    /** One uncompressed 16-bit CFA frame, bytes. */
    fun frameSizeBytes(width: Int, height: Int): Long = width.toLong() * height * 2L

    /**
     * The frames the range holds: an inclusive span on the clip's own
     * numbering, clamped to [clipFrameCount]; lastFrame < 0 = whole
     * clip (the plan's default). Negative first or an inverted span is
     * a rejection (reported with the reason, never a silent fix-up).
     */
    fun frameCount(firstFrame: Int, lastFrame: Int, clipFrameCount: Int): Int {
        if (firstFrame < 0) return REJECT
        if (clipFrameCount <= 0) return REJECT
        val last = if (lastFrame < 0) clipFrameCount - 1 else lastFrame
        if (last < firstFrame) return REJECT
        val end = minOf(last, clipFrameCount - 1)
        if (end < firstFrame) return REJECT
        return end - firstFrame + 1
    }

    /**
     * The DISPLAYED size estimate: frames x frameSize, rounded up to a
     * whole byte (a fraction of a byte cannot be written). Saturates at
     * Long.MAX_VALUE rather than wrapping. The 1,05 headroom is the
     * STORAGE rule's (see [requiredBytes]) — the sheet shows this raw
     * number, the document's own table shape.
     */
    fun estimatedBytes(frames: Int, width: Int, height: Int): Long {
        if (frames <= 0) return 0L
        val perFrame = frameSizeBytes(width, height)
        val exact = frames.toDouble() * perFrame.toDouble()
        return if (exact >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE
        else ceil(exact).toLong()
    }

    /**
     * The STORAGE rule's required bytes: the displayed estimate times
     * the plan's 1,05 headroom ("gerekli = kare x kare_boyutu x
     * 1,05"), rounded up, saturating at Long.MAX_VALUE.
     */
    fun requiredBytes(frames: Int, width: Int, height: Int): Long {
        if (frames <= 0) return 0L
        val perFrame = frameSizeBytes(width, height)
        val exact = frames.toDouble() * perFrame.toDouble() * SIZE_HEADROOM
        return if (exact >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE
        else ceil(exact).toLong()
    }

    /**
     * The WAV's PCM bytes for the same span when the clip has audio:
     * span seconds x rate x channels x 2 bytes (16-bit), source rate
     * verbatim. 0 when there is no audio ([sampleRate] or [channels]
     * <= 0, the clip's own "no audio" convention).
     */
    fun wavBytes(
        frames: Int,
        fps: Double,
        sampleRate: Int,
        channels: Int,
    ): Long {
        if (frames <= 0 || fps <= 0.0 || sampleRate <= 0 || channels <= 0) return 0L
        val seconds = frames / fps
        val exact = seconds * sampleRate * channels * 2.0
        return if (exact >= Long.MAX_VALUE.toDouble()) Long.MAX_VALUE
        else ceil(exact).toLong()
    }

    /**
     * The storage verdict of the plan's rule:
     *   free < required            -> LOCK ([StorageGate.Locked])
     *   free < required x 1,5      -> WARN ([StorageGate.Warn]) — the
     *                                 write may still fail mid-way; the
     *                                 partial-result rule then preserves
     *                                 what was written (DngSequenceWriter)
     *   else                       -> OK
     * A required of 0 is always OK (nothing to write). Computed in
     * Double with the same saturation guard (a huge required with a
     * small free is a LOCK, never an overflow into a false OK).
     */
    fun storageGate(freeBytes: Long, requiredBytes: Long): StorageGate {
        if (requiredBytes <= 0L) return StorageGate.Ok(requiredBytes)
        val required = requiredBytes.toDouble()
        val warnAt = required * STORAGE_WARN_MULTIPLE
        val free = freeBytes.toDouble()
        if (free < required) return StorageGate.Locked(requiredBytes)
        if (free < warnAt) return StorageGate.Warn(requiredBytes)
        return StorageGate.Ok(requiredBytes)
    }

    /**
     * The ETA seconds the sheet may show. ALWAYS NULL tonight: no
     * per-frame write has been measured (the plan's own "süre
     * ölçülmedi"; R1-0 owns the measurement). The function exists so
     * the sheet's one call site and its test both name the rule —
     * "duration unknown" is a decision with a single source, not a
     * missing number. When R1-0 lands, THIS function gains the
     * measured per-frame seconds and every call site starts reporting
     * without any other change.
     */
    fun etaSeconds(): Long? = null

    /**
     * The whole plan of one sequence export, built from the world
     * facts. Pure; [durationKnown] is always false tonight (the
     * [etaSeconds] rule) and the sheet renders the estimate line from
     * [estimatedBytes] + [storage] exactly like ExportSheetModel's
     * summary.
     */
    data class Plan(
        /** The frames the range holds (never REJECT here — [of] rejects). */
        val frames: Int,
        /** The DISPLAYED size estimate (frames x frameSize, rounded up). */
        val estimatedBytes: Long,
        /** The storage rule's required bytes (displayed x 1,05, rounded up). */
        val requiredBytes: Long,
        /** The WAV's PCM bytes inside the same span (0 = no audio). */
        val wavBytes: Long,
        /** The storage verdict against the target's free space. */
        val storage: StorageGate,
        /** Always false tonight (the "süre bilinmez" rule, [etaSeconds]). */
        val durationKnown: Boolean,
        /** The frame-size assumption's provenance (for the report line). */
        val frameSizeAssumed: Boolean = FRAME_SIZE_UNMEASURED,
    ) {
        /** True when the storage rule says the export may start. */
        val exportable: Boolean get() = storage !is StorageGate.Locked
    }

    /**
     * Builds the plan. An unusable range throws IllegalArgumentException
     * (the store contract shape — the caller reports it, this layer
     * never invents a count).
     */
    fun of(
        clipFrameCount: Int,
        firstFrame: Int,
        lastFrame: Int,
        width: Int,
        height: Int,
        fps: Double,
        sampleRate: Int,
        channels: Int,
        freeBytes: Long,
    ): Plan {
        val frames = frameCount(firstFrame, lastFrame, clipFrameCount)
        require(frames != REJECT) {
            "unusable frame range: first=$firstFrame last=$lastFrame clip=$clipFrameCount"
        }
        val displayed = estimatedBytes(frames, width, height)
        return Plan(
            frames = frames,
            estimatedBytes = displayed,
            requiredBytes = requiredBytes(frames, width, height),
            wavBytes = wavBytes(frames, fps, sampleRate, channels),
            storage = storageGate(freeBytes, requiredBytes(frames, width, height)),
            durationKnown = etaSeconds() != null,
        )
    }
}

/** The plan's storage verdict (the ERROR band / the warn line / OK). */
sealed interface StorageGate {
    /** The required-bytes the verdict is about. */
    val requiredBytes: Long

    /** Free space covers the headroom multiple: start. */
    data class Ok(override val requiredBytes: Long) : StorageGate

    /**
     * Free space covers the write but not the x1,5 multiple: start,
     * but the sheet warns (a mid-write exhaustion then ends the job
     * PARTIAL — the written frames are preserved, never deleted).
     */
    data class Warn(override val requiredBytes: Long) : StorageGate

    /** Free space below required: the export button refuses. */
    data class Locked(override val requiredBytes: Long) : StorageGate
}
