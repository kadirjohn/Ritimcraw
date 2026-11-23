package com.ritim.ritimcraw.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * One reading of the system monitor strip (owner request 2026-11-23:
 * "GPU, CPU, RAM, storage'u gösteren minik toolbox"). Every percentage is
 * 0..100 or null when the device does not expose it to an app — the strip
 * shows "—" for null, never a fabricated number.
 *
 * - [cpuPercent]: THIS APP's CPU share of the whole device (all cores),
 *   from /proc/self/stat deltas. Android 8+ blocks /proc/stat for apps, so
 *   system-wide CPU is not readable; the app's own share is the honest
 *   number (and the one that matters while rendering).
 * - [gpuPercent]: the device GPU busy percentage from the vendor sysfs
 *   node (Adreno kgsl / Mali / generic) when readable; SELinux blocks it on
 *   most current phones, so the fallback is THIS APP's GPU share — the
 *   preview session's GL_TIME_ELAPSED query total per wall second
 *   ([gpuIsAppShare] = true). Null when neither exists (CPU backend, no
 *   timer-query extension).
 * - RAM: system-wide used/total from ActivityManager.MemoryInfo.
 * - Storage: the internal data volume used/total from StatFs, shown in
 *   DECIMAL GB (owner request 2026-11-23: GB, not a percentage). On the test
 *   phone this reads 346/512 GB — the data partition is exactly the retail
 *   512 GB. StorageStatsManager.getTotalBytes was tried and rejected: it
 *   reported 527 GB (it adds ~15 GB of system partitions and counts them as
 *   used), which matches neither the box nor Settings.
 */
data class SystemStats(
    val cpuPercent: Float? = null,
    val gpuPercent: Float? = null,
    val gpuIsAppShare: Boolean = false,
    val ramUsedBytes: Long = 0L,
    val ramTotalBytes: Long = 0L,
    val storageUsedBytes: Long = 0L,
    val storageTotalBytes: Long = 0L,
) {
    val ramPercent: Float?
        get() = SystemStatsMath.percent(ramUsedBytes, ramTotalBytes)
    val storagePercent: Float?
        get() = SystemStatsMath.percent(storageUsedBytes, storageTotalBytes)
    val storageFreeBytes: Long
        get() = (storageTotalBytes - storageUsedBytes).coerceAtLeast(0L)
}

/** Pure parsing/formatting — JVM-tested, no Android types. */
object SystemStatsMath {

    fun percent(used: Long, total: Long): Float? =
        if (total <= 0L) null else (used.toFloat() * 100f / total).coerceIn(0f, 100f)

    /**
     * utime + stime (fields 14 and 15) of a /proc/<pid>/stat line, in clock
     * ticks. The comm field (2) can contain spaces and parentheses, so
     * parsing starts after the LAST ')'.
     */
    fun procStatCpuTicks(line: String): Long? {
        val close = line.lastIndexOf(')')
        if (close < 0) return null
        val rest = line.substring(close + 1).trim().split(Regex("\\s+"))
        // rest[0] is field 3 (state); utime = field 14 -> rest[11], stime -> rest[12]
        if (rest.size < 13) return null
        val utime = rest[11].toLongOrNull() ?: return null
        val stime = rest[12].toLongOrNull() ?: return null
        return utime + stime
    }

    /** GPU-time delta over wall-time delta, 0..100 (the app's GPU share). */
    fun gpuSharePercent(deltaGpuNanos: Long, deltaWallNanos: Long): Float? {
        if (deltaWallNanos <= 0L || deltaGpuNanos < 0L) return null
        return (deltaGpuNanos.toDouble() * 100.0 / deltaWallNanos).toFloat().coerceIn(0f, 100f)
    }

    /** The app's share of ALL cores over the interval, 0..100. */
    fun cpuPercent(deltaTicks: Long, deltaNanos: Long, clockTicksPerSec: Long, cores: Int): Float? {
        if (deltaNanos <= 0L || clockTicksPerSec <= 0L || cores <= 0 || deltaTicks < 0L) return null
        val cpuSeconds = deltaTicks.toDouble() / clockTicksPerSec
        val wallSeconds = deltaNanos / 1e9
        return (cpuSeconds * 100.0 / (wallSeconds * cores)).toFloat().coerceIn(0f, 100f)
    }

    /**
     * The GPU sysfs readings in their known shapes:
     * - "37 %" / "37" — a percentage (kgsl gpu_busy_percentage, Mali
     *   utilization, /sys/kernel/gpu/gpu_busy);
     * - "1234 5678" — kgsl gpubusy's busy/total counter pair.
     */
    fun parseGpuBusy(text: String): Float? {
        val nums = Regex("\\d+").findAll(text).map { it.value.toLongOrNull() }.toList()
        if (nums.isEmpty() || nums.any { it == null }) return null
        return when (nums.size) {
            1 -> nums[0]!!.toFloat().takeIf { it in 0f..100f }
            2 -> {
                val busy = nums[0]!!
                val total = nums[1]!!
                if (total <= 0L) 0f else (busy.toFloat() * 100f / total).coerceIn(0f, 100f)
            }
            else -> null
        }
    }

    /** "15.6G", "812M" — one decimal under 100 of the unit, binary units. */
    fun formatBytes(bytes: Long): String {
        val gib = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gib >= 100.0) return "${gib.toLong()}G"
        if (gib >= 1.0) return String.format(java.util.Locale.ROOT, "%.1fG", gib)
        val mib = bytes / (1024.0 * 1024.0)
        return "${mib.toLong()}M"
    }

    fun formatPercent(p: Float?): String = p?.let { "${it.toInt()}%" } ?: "—"

    /**
     * Storage in DECIMAL gigabytes, the unit Android Settings uses for
     * storage ("345 GB used of 512 GB"): "345G", "45G", "7.8G".
     */
    fun formatGb(bytes: Long): String {
        val gb = bytes / 1e9
        return if (gb >= 10.0) "${Math.round(gb)}G"
        else String.format(java.util.Locale.ROOT, "%.1fG", gb)
    }

    /** "345/512G" — used over total capacity, decimal GB. */
    fun formatCapacity(usedBytes: Long, totalBytes: Long): String =
        formatGb(usedBytes).removeSuffix("G") + "/" + formatGb(totalBytes)
}

/**
 * The stateful sampler: call [sample] about once per second from a
 * background thread while the strip is visible. Not thread-safe; one
 * owner (the strip's collecting coroutine).
 */
class SystemStatsSampler(context: Context) {
    private val appContext = context.applicationContext
    private val activityManager =
        appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    private val clockTicks: Long =
        runCatching { Os.sysconf(OsConstants._SC_CLK_TCK) }.getOrDefault(100L)
    private val cores: Int = Runtime.getRuntime().availableProcessors()

    private var lastTicks: Long? = null
    private var lastNanos: Long = 0L

    // The first GPU node that read successfully stays chosen; a node that
    // failed is never retried (SELinux denials do not heal at runtime).
    private var gpuNode: File? = null
    private var gpuProbed = false
    private var lastGpuNanos: Long = -1L
    private var lastGpuWall: Long = 0L

    fun sample(): SystemStats {
        val cpu = sampleCpu()
        val sysGpu = sampleGpu()
        val appGpu = if (sysGpu == null) sampleAppGpu() else null
        val gpu = sysGpu ?: appGpu
        val mem = ActivityManager.MemoryInfo()
        val haveMem = runCatching { activityManager?.getMemoryInfo(mem) }.isSuccess &&
            mem.totalMem > 0L
        val (storageUsed, storageTotal) = runCatching {
            val stat = StatFs(appContext.filesDir.absolutePath.ifEmpty {
                Environment.getDataDirectory().absolutePath
            })
            val total = stat.totalBytes
            total - stat.availableBytes to total
        }.getOrDefault(0L to 0L)
        return SystemStats(
            cpuPercent = cpu,
            gpuPercent = gpu,
            gpuIsAppShare = sysGpu == null && appGpu != null,
            ramUsedBytes = if (haveMem) mem.totalMem - mem.availMem else 0L,
            ramTotalBytes = if (haveMem) mem.totalMem else 0L,
            storageUsedBytes = storageUsed,
            storageTotalBytes = storageTotal,
        )
    }

    private fun sampleCpu(): Float? {
        val ticks = runCatching {
            SystemStatsMath.procStatCpuTicks(File("/proc/self/stat").readText())
        }.getOrNull() ?: return null
        val now = System.nanoTime()
        val prev = lastTicks
        val prevNanos = lastNanos
        lastTicks = ticks
        lastNanos = now
        if (prev == null) return null
        return SystemStatsMath.cpuPercent(ticks - prev, now - prevNanos, clockTicks, cores)
    }

    private fun sampleGpu(): Float? {
        if (!gpuProbed) {
            gpuProbed = true
            gpuNode = GPU_NODES.map(::File).firstOrNull { f ->
                runCatching { SystemStatsMath.parseGpuBusy(f.readText()) != null }
                    .getOrDefault(false)
            }
        }
        val node = gpuNode ?: return null
        return runCatching { SystemStatsMath.parseGpuBusy(node.readText()) }.getOrNull()
    }

    private fun sampleAppGpu(): Float? {
        val total = NativeSource.previewGpuNanos()
        val now = System.nanoTime()
        val prev = lastGpuNanos
        val prevWall = lastGpuWall
        lastGpuNanos = total
        lastGpuWall = now
        if (total < 0L) return null  // no timer-capable GPU preview session
        if (prev < 0L || total < prev) return 0f  // first sample / session restart
        return SystemStatsMath.gpuSharePercent(total - prev, now - prevWall)
    }

    companion object {
        val GPU_NODES = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/devices/platform/gpu/utilization",
        )
    }
}
