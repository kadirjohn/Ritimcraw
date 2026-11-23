package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemStatsMathTest {

    @Test
    fun proc_stat_ticks_survive_spaces_and_parens_in_comm() {
        // pid (comm) state ppid ... utime(14)=250 stime(15)=50
        val line = "1234 (com.ritim (gl) x) S 1 2 3 4 5 6 7 8 9 10 250 50 0 0 20 0 30"
        assertEquals(300L, SystemStatsMath.procStatCpuTicks(line))
    }

    @Test
    fun proc_stat_short_or_garbage_is_null() {
        assertNull(SystemStatsMath.procStatCpuTicks("1234 (x) S 1 2"))
        assertNull(SystemStatsMath.procStatCpuTicks("no parens here"))
    }

    @Test
    fun cpu_percent_is_share_of_all_cores() {
        // 100 ticks at 100 Hz = 1 cpu-second over 1 s wall on 4 cores = 25%
        assertEquals(25f, SystemStatsMath.cpuPercent(100, 1_000_000_000L, 100, 4)!!, 0.001f)
        assertEquals(100f, SystemStatsMath.cpuPercent(10_000, 1_000_000_000L, 100, 4)!!, 0.001f)
        assertNull(SystemStatsMath.cpuPercent(100, 0L, 100, 4))
    }

    @Test
    fun gpu_share_is_gpu_time_over_wall_time() {
        assertEquals(25f, SystemStatsMath.gpuSharePercent(250_000_000L, 1_000_000_000L)!!, 0.001f)
        assertEquals(100f, SystemStatsMath.gpuSharePercent(3_000_000_000L, 1_000_000_000L)!!, 0.001f)
        assertNull(SystemStatsMath.gpuSharePercent(1L, 0L))
    }

    @Test
    fun gpu_busy_shapes() {
        assertEquals(37f, SystemStatsMath.parseGpuBusy("37 %\n")!!, 0.001f)
        assertEquals(12f, SystemStatsMath.parseGpuBusy("12")!!, 0.001f)
        assertEquals(25f, SystemStatsMath.parseGpuBusy("250 1000")!!, 0.001f)
        assertEquals(0f, SystemStatsMath.parseGpuBusy("0 0")!!, 0.001f)
        assertNull(SystemStatsMath.parseGpuBusy(""))
        assertNull(SystemStatsMath.parseGpuBusy("250"))
    }

    @Test
    fun storage_reads_in_decimal_gb() {
        assertEquals("345G", SystemStatsMath.formatGb(345_000_000_000L))
        assertEquals("45G", SystemStatsMath.formatGb(44_600_000_000L))
        assertEquals("7.8G", SystemStatsMath.formatGb(7_800_000_000L))
        assertEquals("345/512G", SystemStatsMath.formatCapacity(345_000_000_000L, 512_000_000_000L))
    }

    @Test
    fun formatting() {
        assertEquals("15.6G", SystemStatsMath.formatBytes((15.6 * 1024 * 1024 * 1024).toLong()))
        assertEquals("212G", SystemStatsMath.formatBytes(212L * 1024 * 1024 * 1024))
        assertEquals("812M", SystemStatsMath.formatBytes(812L * 1024 * 1024))
        assertEquals("—", SystemStatsMath.formatPercent(null))
        assertEquals("42%", SystemStatsMath.formatPercent(42.9f))
        assertNull(SystemStatsMath.percent(1, 0))
    }
}
