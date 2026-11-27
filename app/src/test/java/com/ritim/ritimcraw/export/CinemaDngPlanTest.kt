package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CinemaDngPlan tests (night E-D3, docs/CINEMADNG_EXPORT_PLAN.md):
 * the size estimate and the storage gate pinned against the plan
 * document's OWN table (4 s / 58 s / 5 dk -> ~1,9 / ~26,9 / ~139 GiB
 * at 3840x2160 @ 30 fps), the frame range rules, and the
 * "süre bilinmez" rule.
 */
class CinemaDngPlanTest {

    // ---- the plan document's own table ----

    @Test
    fun plan_table_4s_58s_5min_matches_the_document() {
        // The document's rows @ 30 fps, 3840x2160, kare başı ~15,8 MiB:
        //   4 s  -> 120 frames  -> ~1,9 GiB
        //   58 s -> 1740 frames -> ~26,9 GiB
        //   5 dk -> 9000 frames -> ~139 GiB
        // The DISPLAYED estimate is the raw frames x frameSize (the
        // document's table column); the 1,05 headroom is the storage
        // rule's, kept apart (see requiredBytes_headrooms_the_display).
        val g = 1024.0 * 1024.0 * 1024.0

        val r4 = CinemaDngPlan.estimatedBytes(120, 3840, 2160)
        assertEquals("1.9 GiB",
            String.format(java.util.Locale.ROOT, "%.1f GiB", r4 / g))

        val r58 = CinemaDngPlan.estimatedBytes(1740, 3840, 2160)
        assertEquals("26.9 GiB",
            String.format(java.util.Locale.ROOT, "%.1f GiB", r58 / g))

        val r5 = CinemaDngPlan.estimatedBytes(9000, 3840, 2160)
        // The document's ~139 GiB: the raw estimate is 139,046 GiB, so
        // both roundings agree with the document's own ~139.
        assertEquals("139.0 GiB",
            String.format(java.util.Locale.ROOT, "%.1f GiB", r5 / g))
        assertEquals("139 GiB",
            String.format(java.util.Locale.ROOT, "%.0f GiB", r5 / g))

        // The per-frame size the document quotes (~15,8 MiB): the raw
        // frame bytes.
        val perFrame = CinemaDngPlan.frameSizeBytes(3840, 2160)
        assertEquals("15.8 MiB",
            String.format(java.util.Locale.ROOT, "%.1f MiB", perFrame / 1048576.0))
    }

    @Test
    fun requiredBytes_headrooms_the_displayed_estimate() {
        // The storage rule's "gerekli = kare x kare_boyutu x 1,05":
        // required = displayed x 1,05, rounded up — the two numbers
        // are one formula with the headroom named (the plan's own
        // wording), and the test pins the relationship, not just the
        // constant, so the sheet and the gate can never drift apart.
        val displayed = CinemaDngPlan.estimatedBytes(120, 3840, 2160)
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160)
        assertEquals(
            kotlin.math.ceil(displayed * CinemaDngPlan.SIZE_HEADROOM).toLong(),
            required,
        )
        assertTrue(required > displayed) // the headroom is real
    }

    // ---- the size estimate's math ----

    @Test
    fun estimate_is_frames_times_framesize_headroom_apart() {
        // Displayed: 1 frame of 2x2 = 8 bytes raw (no headroom — the
        // document's table shape).
        assertEquals(8L, CinemaDngPlan.estimatedBytes(1, 2, 2))
        // Storage-required: the same frame with the 1,05 headroom =
        // 8,4 -> 9 (rounded up — a fraction of a byte cannot be written).
        assertEquals(9L, CinemaDngPlan.requiredBytes(1, 2, 2))
        // Zero frames: nothing, both numbers.
        assertEquals(0L, CinemaDngPlan.estimatedBytes(0, 2, 2))
        assertEquals(0L, CinemaDngPlan.requiredBytes(0, 2, 2))
        // Negative frames: nothing (an unusable range never gets here —
        // of() rejects — but the helpers stay honest).
        assertEquals(0L, CinemaDngPlan.estimatedBytes(-3, 2, 2))
        assertEquals(0L, CinemaDngPlan.requiredBytes(-3, 2, 2))
    }

    @Test
    fun estimate_saturates_instead_of_wrapping() {
        // A frame count/size product that overflows Long when scaled:
        // saturates at Long.MAX_VALUE (a lock, never a nonsense number).
        val huge = CinemaDngPlan.requiredBytes(Int.MAX_VALUE, 100_000, 100_000)
        assertEquals(Long.MAX_VALUE, huge)
        val hugeDisplayed = CinemaDngPlan.estimatedBytes(Int.MAX_VALUE, 100_000, 100_000)
        assertEquals(Long.MAX_VALUE, hugeDisplayed)
    }

    // ---- the frame range ----

    @Test
    fun frame_count_whole_clip_and_span() {
        // lastFrame -1 = the whole clip (the plan's default).
        assertEquals(100, CinemaDngPlan.frameCount(0, -1, 100))
        // An inclusive span: 10..20 = 11 frames.
        assertEquals(11, CinemaDngPlan.frameCount(10, 20, 100))
        // The span clamps to the clip's end: 90..99 (last = first
        // frame 90, clip end 99) = 10 frames.
        assertEquals(10, CinemaDngPlan.frameCount(90, -1, 100))
        assertEquals(10, CinemaDngPlan.frameCount(90, 500, 100))
        // The first frame past the clip's end: nothing usable.
        assertEquals(CinemaDngPlan.REJECT, CinemaDngPlan.frameCount(100, -1, 100))
        assertEquals(CinemaDngPlan.REJECT, CinemaDngPlan.frameCount(101, 200, 100))
    }

    @Test
    fun frame_count_rejects_inverted_and_empty() {
        assertEquals(CinemaDngPlan.REJECT, CinemaDngPlan.frameCount(20, 10, 100))
        assertEquals(CinemaDngPlan.REJECT, CinemaDngPlan.frameCount(-1, 50, 100))
        assertEquals(CinemaDngPlan.REJECT, CinemaDngPlan.frameCount(0, -1, 0))
        assertEquals(CinemaDngPlan.REJECT, CinemaDngPlan.frameCount(0, 5, -2))
    }

    // ---- the storage gate ----

    @Test
    fun storage_gate_locks_when_free_below_required() {
        val required = 1000L
        assertTrue(CinemaDngPlan.storageGate(999, required) is StorageGate.Locked)
        assertTrue(CinemaDngPlan.storageGate(0, required) is StorageGate.Locked)
        // Exactly required: WARN, not LOCK — the plan's rule is a strict
        // "< gerekli" for the lock, and free == required is past it while
        // still under the x1,5 multiple (the WAV rides in the headroom).
        assertTrue(CinemaDngPlan.storageGate(1000, required) is StorageGate.Warn)
    }

    @Test
    fun storage_gate_warns_between_required_and_its_multiple() {
        val required = 1000L
        // free = required + 1: past the lock, under x1,5 -> WARN.
        assertTrue(CinemaDngPlan.storageGate(1001, required) is StorageGate.Warn)
        // free = x1,5 - 1: still warn.
        assertTrue(CinemaDngPlan.storageGate(1499, required) is StorageGate.Warn)
        // free = x1,5 exactly: OK (the rule's strict <).
        assertTrue(CinemaDngPlan.storageGate(1500, required) is StorageGate.Ok)
        assertTrue(CinemaDngPlan.storageGate(10_000, required) is StorageGate.Ok)
    }

    @Test
    fun storage_gate_zero_required_is_ok() {
        assertTrue(CinemaDngPlan.storageGate(0, 0) is StorageGate.Ok)
        assertTrue(CinemaDngPlan.storageGate(-5, 0) is StorageGate.Ok)
    }

    @Test
    fun storage_gate_huge_required_saturates_to_lock() {
        val gate = CinemaDngPlan.storageGate(1L shl 40, Long.MAX_VALUE)
        assertTrue(gate is StorageGate.Locked)
    }

    // ---- the "süre bilinmez" rule ----

    @Test
    fun eta_is_unknown_tonight() {
        // The plan document's own rule: "o zamana kadar arayüz 'süre
        // bilinmiyor' der". No per-frame write has been measured (R1-0
        // owns that), so the ETA is NULL — never a guessed number.
        assertNull(CinemaDngPlan.etaSeconds())
    }

    // ---- the built plan ----

    @Test
    fun plan_of_builds_and_rejects() {
        // A 100-frame clip, whole-clip range, 1080p-ish frame, audio.
        val plan = CinemaDngPlan.of(
            clipFrameCount = 100,
            firstFrame = 0,
            lastFrame = -1,
            width = 1920, height = 1080,
            fps = 30.0,
            sampleRate = 48000, channels = 2,
            freeBytes = Long.MAX_VALUE,
        )
        assertEquals(100, plan.frames)
        assertEquals(
            CinemaDngPlan.requiredBytes(100, 1920, 1080),
            plan.requiredBytes,
        )
        // WAV: 100/30 s x 48000 x 2 ch x 2 bytes, rounded up (Long to
        // Long — the helper's own rounding, not a Double equality).
        assertEquals(
            ceil(100.0 / 30.0 * 48000 * 2 * 2.0),
            plan.wavBytes,
        )
        assertTrue(plan.storage is StorageGate.Ok)
        assertTrue(plan.exportable)
        // The duration rule rides on the built plan too.
        assertFalse(plan.durationKnown)
        assertTrue(plan.frameSizeAssumed)

        // An inverted range: rejected with the store-contract shape.
        val thrown = try {
            CinemaDngPlan.of(100, 50, 10, 1920, 1080, 30.0, 48000, 2, 1000)
            false
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("unusable frame range"))
            true
        }
        assertTrue(thrown)
    }

    @Test
    fun plan_of_locks_when_free_is_short() {
        val plan = CinemaDngPlan.of(
            clipFrameCount = 9000,
            firstFrame = 0,
            lastFrame = -1,
            width = 3840, height = 2160,
            fps = 30.0,
            sampleRate = 48000, channels = 2,
            freeBytes = 10L * 1024 * 1024 * 1024, // 10 GiB free
        )
        // ~139 GiB required: the 10 GiB free is a LOCK, not a WARN —
        // the sheet's ERROR band with the "shorten the range" fix.
        assertTrue(plan.storage is StorageGate.Locked)
        assertFalse(plan.exportable)
    }

    @Test
    fun plan_of_warns_between_the_bounds() {
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160) // ~1,9 GiB
        val plan = CinemaDngPlan.of(
            clipFrameCount = 120,
            firstFrame = 0,
            lastFrame = -1,
            width = 3840, height = 2160,
            fps = 30.0,
            sampleRate = 48000, channels = 2,
            freeBytes = required + 1,
        )
        // Free covers the write but not x1,5: WARN, still exportable.
        assertTrue(plan.storage is StorageGate.Warn)
        assertTrue(plan.exportable)
    }

    private fun ceil(v: Double): Long = kotlin.math.ceil(v).toLong()
}
