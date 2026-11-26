package com.ritim.ritimcraw.export

/**
 * E0b-5: which develop path an ENCODED (MP4/MOV) clip takes at export, as a pure decision, plus the GPU memory
 * budget of that path. No Android, no GL: JVM-tested.
 *
 * The paths, best first:
 *  - [Path.SURFACE]: YUV planes -> GPU develop chain -> blit onto the encoder's input Surface. No RGB frame ever
 *    exists on the CPU side (the only route that carries 8K: a 7680x4320 RGB888 frame is 99 MB, the JVM heap is
 *    256 MB).
 *  - [Path.GPU_READBACK]: the same chain read back as RGB888 (the E0b-3 door), only while that buffer fits.
 *  - [Path.CPU_ORACLE]: developEncoded on the CPU, only while its whole-frame allocations fit the heap.
 *  - [Path.REFUSED]: nothing fits; [Decision.message] is the short owner-visible reason (never an OOM).
 *
 * Export keeps full resolution: nothing here reduces the frame.
 */
object EncodedExportPlanner {

    enum class Path { SURFACE, GPU_READBACK, CPU_ORACLE, REFUSED }

    /** The RGB888 readback door's cap (4K DCI = 41 MB passes, 8K = 99 MB does not). */
    const val GPU_READBACK_MAX_RGB_BYTES: Long = 48L * 1024 * 1024

    /** The CPU oracle may use at most this share of the heap for its whole-frame buffers. */
    const val CPU_ORACLE_HEAP_SHARE: Double = 0.7

    /** Edge-length limit applied when the device's GL_MAX_TEXTURE_SIZE is unknown (ES 3.1 floor in practice). */
    const val ASSUMED_MAX_TEXTURE_SIZE: Int = 8192

    // ---- A7 (night 2026-11-26): the striped CPU oracle's budget ----
    //
    // The whole-frame oracle holds planes + a full ARGB IntArray + RGB888 (8K:
    // 281 MB over a 256 MB heap — the pre-A7 refusal). The striped oracle drops
    // the ARGB detour and the full-frame native ImageF: what stays ON THE HEAP
    // is the decoded planes (unavoidable on any CPU path — decode produced
    // them) plus the single RGB888 output, plus a second plane-set only when
    // the decoded layout needs the toNv12 repack. The stripe working set
    // (native, RSS-measured — see core/tests/stripe_measure) is bounded by
    // [stripeRowsFor] against [STRIPE_SET_MAX_BYTES].

    /** The striped develop's vertical context per stripe — MUST mirror rawedit kEncodedStripeMargin (core/Transfer.h). */
    const val STRIPE_MARGIN_ROWS: Int = 10

    /** The native stripe working set's budget: 3 ImageF float planes + up to 6 stage planes, rows + 2 * margin. */
    const val STRIPE_SET_MAX_BYTES: Long = 24L * 1024 * 1024

    /** Stripe row-count bounds: a whole small frame is one stripe; never thinner than 16 rows. */
    const val STRIPE_MIN_ROWS: Int = 16
    const val STRIPE_MAX_ROWS: Int = 512

    data class Inputs(
        val width: Int,
        val height: Int,
        /** Decoded depth: 8 or 10 (P010). */
        val bitDepth: Int = 8,
        /** The session can speak the encoder-surface door at all (a GPU develop session + native). */
        val surfaceCapable: Boolean,
        /** The job needs no crop, no straighten and no scale: the blit is a 1:1 copy (rotation is taught). */
        val geometryOneToOne: Boolean,
        /** The job's chain is something the GPU door refuses (an OpenDRT output chain). */
        val chainRefusedByGpu: Boolean = false,
        /** GL_MAX_TEXTURE_SIZE when known. */
        val maxTextureSize: Int? = null,
        /** Total GPU memory this process may use, when the device reports one; null = unknown (not a gate). */
        val gpuMemoryLimitBytes: Long? = null,
        /** The JVM heap limit (Runtime.maxMemory). */
        val heapLimitBytes: Long,
        /** A highlight-recovery grade adds the recovery pyramid to the GPU budget. */
        val highlightRecovery: Boolean = false,
    )

    data class Decision(val path: Path, val reason: String, val message: String? = null)

    /** RGBA32F bytes per pixel (the chain's intermediate format). */
    private const val RGBA32F = 16L

    private fun area(w: Int, h: Int): Long = w.toLong() * h

    /**
     * The GPU memory the develop session allocates for one frame size, itemised. Formula (px = width * height):
     *  - chain textures: 4 x RGBA32F = 4 * 16 * px (developTex + three ping-pong slots A/B/C);
     *  - YUV planes: Y R8UI/R16UI + CbCr RG8UI/RG16UI at half size = 1.5 * px (8-bit) or 3 * px (P010) bytes;
     *  - CFA texture R16UI = 2 * px (the shared pipeline allocates it even for YUV input; unused);
     *  - recovery pyramid (only with highlight recovery): 6 layers x RGBA32F at 1/4, 1/8, 1/32, 1/128, 1/512 edge;
     *  - readback pair (RGBA8 scratch 4 * px + SSBO 12 * px): [readbackBytes], NOT part of [total] because the
     *    encoder-surface path defers it (it is allocated only if a frame is read back).
     * The encoder's own input-surface buffers (a few gralloc frames) are outside this process-GL figure.
     */
    data class GpuBudget(
        val width: Int,
        val height: Int,
        val chainTextureBytes: Long,
        val yuvPlaneBytes: Long,
        val cfaTextureBytes: Long,
        val recoveryPyramidBytes: Long,
        /** Allocated only when a frame is read back (deferred on the surface path). */
        val readbackBytes: Long,
    ) {
        /** What the encoder-surface path holds on the GPU. */
        val total: Long get() = chainTextureBytes + yuvPlaneBytes + cfaTextureBytes + recoveryPyramidBytes

        /** What the readback path holds (surface-path total + the readback pair). */
        val totalWithReadback: Long get() = total + readbackBytes
    }

    /** The recovery pyramid's level divisors (GpuDevelopPipeline: 4, then 8, 32, 128, 512). */
    private val RECOVERY_DIVISORS = intArrayOf(4, 8, 32, 128, 512)

    fun gpuBudget(width: Int, height: Int, bitDepth: Int = 8, highlightRecovery: Boolean = false): GpuBudget {
        val px = area(width, height)
        val chain = 4L * RGBA32F * px
        val yuv = if (bitDepth > 8) 3L * px else px + px / 2
        val cfa = 2L * px
        var pyramid = 0L
        if (highlightRecovery) {
            for (div in RECOVERY_DIVISORS) {
                val lw = (width + div - 1) / div
                val lh = (height + div - 1) / div
                pyramid += lw.toLong() * lh * 6L * RGBA32F
            }
        }
        val readback = 4L * px + 12L * px
        return GpuBudget(width, height, chain, yuv, cfa, pyramid, readback)
    }

    /** Planes (NV12 / P010) + the ARGB IntArray + the RGB888 copy the CPU oracle path holds at once. */
    fun cpuOracleBytes(width: Int, height: Int, bitDepth: Int = 8): Long {
        val px = area(width, height)
        val planes = if (bitDepth > 8) 3L * px else px + px / 2
        return planes + 4L * px + 3L * px
    }

    fun rgb888Bytes(width: Int, height: Int): Long = 3L * area(width, height)

    fun fitsTexture(width: Int, height: Int, maxTextureSize: Int?): Boolean {
        val limit = maxTextureSize ?: ASSUMED_MAX_TEXTURE_SIZE
        return width <= limit && height <= limit
    }

    fun cpuOracleFits(width: Int, height: Int, bitDepth: Int, heapLimitBytes: Long): Boolean =
        cpuOracleBytes(width, height, bitDepth) <= (heapLimitBytes * CPU_ORACLE_HEAP_SHARE).toLong()

    /** The native stripe's working set (floats), rows + 2 * margin — the bounded native footprint (RSS, not heap). */
    fun stripeSetBytes(width: Int, stripeRows: Int): Long =
        9L * width * (stripeRows + 2L * STRIPE_MARGIN_ROWS) * 4L

    /** The stripe row count for a width: even (the chroma phase), the working set kept at/below the budget. */
    fun stripeRowsFor(width: Int): Int {
        if (width <= 0) return STRIPE_MAX_ROWS
        val perRow = 9L * width * 4L
        val rows = (STRIPE_SET_MAX_BYTES / perRow - 2L * STRIPE_MARGIN_ROWS)
            .coerceIn(STRIPE_MIN_ROWS.toLong(), STRIPE_MAX_ROWS.toLong())
        return (rows / 2 * 2).toInt()
    }

    /**
     * The striped CPU oracle's HEAP footprint: the decoded planes + the RGB888 output — the whole-frame
     * ARGB detour is gone. [packed] = true counts the toNv12 repack's second plane-set (a layout the
     * zero-copy view cannot address: NV21, padded pixel strides).
     */
    fun stripedOracleBytes(width: Int, height: Int, bitDepth: Int, packed: Boolean = false, stripeRows: Int = stripeRowsFor(width)): Long {
        val px = area(width, height)
        val planes = if (bitDepth > 8) 3L * px else px + px / 2
        return planes * (if (packed) 2L else 1L) + rgb888Bytes(width, height) +
            // the native set rides the budget only when it is a HEAP cost, which it is not — carried in the count
            // documentation only via stripeSetBytes (native RSS; core/tests/stripe_measure measures it).
            0L
    }

    fun stripedOracleFits(width: Int, height: Int, bitDepth: Int, heapLimitBytes: Long, packed: Boolean = false): Boolean =
        stripedOracleBytes(width, height, bitDepth, packed) <= (heapLimitBytes * CPU_ORACLE_HEAP_SHARE).toLong()

    fun decide(i: Inputs): Decision {
        val texOk = fitsTexture(i.width, i.height, i.maxTextureSize)
        val budget = gpuBudget(i.width, i.height, i.bitDepth, i.highlightRecovery)
        val gpuMemOk = i.gpuMemoryLimitBytes == null || budget.total <= i.gpuMemoryLimitBytes
        if (i.surfaceCapable && i.geometryOneToOne && !i.chainRefusedByGpu && texOk && gpuMemOk) {
            return Decision(Path.SURFACE, "encoder-surface (gpu budget ${mib(budget.total)} MiB)")
        }
        val whyNotSurface = when {
            !i.surfaceCapable -> "no gpu develop session"
            !i.geometryOneToOne -> "crop/straighten/scale"
            i.chainRefusedByGpu -> "chain not on gpu"
            !texOk -> "texture ${i.width}x${i.height} over the device limit"
            else -> "gpu budget ${mib(budget.total)} MiB over the device limit"
        }
        if (i.surfaceCapable && !i.chainRefusedByGpu && texOk && gpuMemOk &&
            rgb888Bytes(i.width, i.height) <= GPU_READBACK_MAX_RGB_BYTES
        ) {
            return Decision(Path.GPU_READBACK, "gpu readback ($whyNotSurface)")
        }
        // The CPU oracle: whole-frame while its buffers fit (byte-identical to
        // the pre-A7 path), otherwise the striped oracle — but only for a 1:1
        // job (geometryOneToOne): a cropped/scaled job's downstream
        // rotate/scale buffers still hold whole frames, so those keep the
        // pre-A7 refusal.
        val cpuWhole = cpuOracleFits(i.width, i.height, i.bitDepth, i.heapLimitBytes)
        val cpuStriped = i.geometryOneToOne &&
            stripedOracleFits(i.width, i.height, i.bitDepth, i.heapLimitBytes)
        if (cpuWhole || cpuStriped) {
            return Decision(
                Path.CPU_ORACLE,
                "cpu oracle (${if (cpuWhole) "whole-frame" else "striped"}; $whyNotSurface)",
            )
        }
        return Decision(Path.REFUSED, "refused ($whyNotSurface)", refusalMessage(i, whyNotSurface))
    }

    /** The short, owner-visible refusal (shown instead of a raw OutOfMemoryError). */
    fun refusalMessage(i: Inputs, whyNotSurface: String): String =
        "This ${i.width}x${i.height} clip cannot be exported with the current settings ($whyNotSurface): " +
            "full-size frames need the GPU path. Export at Original size without crop or straighten."

    /** CPU-fallback guard for the session's frame path: null = fits, else the short message. */
    fun cpuFallbackRefusal(width: Int, height: Int, bitDepth: Int, heapLimitBytes: Long): String? =
        if (cpuOracleFits(width, height, bitDepth, heapLimitBytes)) null
        else "This ${width}x$height frame is too large for the CPU fallback (" +
            "${mib(cpuOracleBytes(width, height, bitDepth))} MiB needed, heap ${mib(heapLimitBytes)} MiB): " +
            "the GPU path was unavailable for this export."

    /**
     * A7: the per-frame guard of the striped-aware fallback — null when EITHER the whole-frame
     * or the striped oracle fits; the short refusal only when even the striped budget (planes +
     * the RGB888 output — the ARGB detour is gone) is over the heap share. 8K 10-bit stays refused
     * (planes + RGB888 = 199 MB > the 179 MB share) — honest: no CPU layout holds 99.5 MB of planes
     * PLUS 99.5 MB of output on this heap.
     */
    fun stripedCpuFallbackRefusal(width: Int, height: Int, bitDepth: Int, heapLimitBytes: Long): String? {
        if (cpuOracleFits(width, height, bitDepth, heapLimitBytes)) return null
        if (stripedOracleFits(width, height, bitDepth, heapLimitBytes)) return null
        return "This ${width}x$height frame is too large for the CPU fallback (striped budget " +
            "${mib(stripedOracleBytes(width, height, bitDepth))} MiB needed, heap ${mib(heapLimitBytes)} MiB): " +
            "the GPU path was unavailable for this export."
    }

    private fun mib(bytes: Long): Long = bytes / (1024L * 1024L)
}
