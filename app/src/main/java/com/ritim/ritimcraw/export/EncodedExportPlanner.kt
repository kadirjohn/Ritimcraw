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
        if (cpuOracleFits(i.width, i.height, i.bitDepth, i.heapLimitBytes)) {
            return Decision(Path.CPU_ORACLE, "cpu oracle ($whyNotSurface)")
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

    private fun mib(bytes: Long): Long = bytes / (1024L * 1024L)
}
