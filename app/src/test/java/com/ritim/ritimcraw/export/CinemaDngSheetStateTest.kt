package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CinemaDngSheetState tests (night E-F, docs/CINEMADNG_EXPORT_PLAN.md):
 * the section's state -> text/enable decisions, all pure so the sheet's
 * visible text is JVM-tested (the composable only renders these). The
 * plan table's own rows pin the estimate/size wording.
 */
class CinemaDngSheetStateTest {

    // ---- the verdict ----

    @Test
    fun verdict_no_folder_refuses_start() {
        val v = CinemaDngSheetState.verdict(null, 100, 0, -1, 3840, 2160, 10_000)
        assertTrue(v is CinemaDngSheetState.Verdict.NoFolder)
        assertFalse(CinemaDngSheetState.canStart(v))
        // Blank is the same as null (never a guessed folder).
        val v2 = CinemaDngSheetState.verdict("", 100, 0, -1, 3840, 2160, 10_000)
        assertTrue(v2 is CinemaDngSheetState.Verdict.NoFolder)
    }

    @Test
    fun verdict_locks_and_warns_per_the_plan_rule() {
        // 120 frames of 3840x2160 -> required ~1,99 GiB (x1,05 headroom).
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160)
        // Free below required: LOCK.
        val lock = CinemaDngSheetState.verdict(
            "content://tree/x", 120, 0, -1, 3840, 2160, required - 1,
        )
        assertTrue(lock is CinemaDngSheetState.Verdict.Locked)
        assertFalse(CinemaDngSheetState.canStart(lock))
        // Free covers required but not x1,5: WARN, still startable.
        val warn = CinemaDngSheetState.verdict(
            "content://tree/x", 120, 0, -1, 3840, 2160, required + 1,
        )
        assertTrue(warn is CinemaDngSheetState.Verdict.Warn)
        assertTrue(CinemaDngSheetState.canStart(warn))
        // Free past x1,5: OK.
        val ok = CinemaDngSheetState.verdict(
            "content://tree/x", 120, 0, -1, 3840, 2160, (required * 1.5).toLong() + 1,
        )
        assertTrue(ok is CinemaDngSheetState.Verdict.Ok)
        assertTrue(CinemaDngSheetState.canStart(ok))
    }

    @Test
    fun verdict_unknown_free_space_never_locks() {
        // freeBytes -1 (the SAF folder could not be measured): OK with the
        // free part hidden — never a guessed lock; the honest mid-write
        // exhaustion is the backstop.
        val v = CinemaDngSheetState.verdict(
            "content://tree/x", 9000, 0, -1, 3840, 2160, -1L,
        )
        assertTrue(v is CinemaDngSheetState.Verdict.Ok)
        assertTrue(CinemaDngSheetState.canStart(v))
        assertEquals(-1L, (v as CinemaDngSheetState.Verdict.Ok).freeBytes)
    }

    @Test
    fun verdict_unknown_clip_facts_are_ok_with_zero_required() {
        // Unknown dims (0): no honest plan is derivable — the verdict is a
        // zero-required Ok (the section shows no estimate line; the
        // exporter's own plan gate is the backstop at run time).
        val v = CinemaDngSheetState.verdict(
            "content://tree/x", 0, 0, -1, 0, 0, 0,
        )
        assertTrue(v is CinemaDngSheetState.Verdict.Ok)
        assertEquals(0L, (v as CinemaDngSheetState.Verdict.Ok).requiredBytes)
    }

    // ---- the enqueue-time storage gate's verdict line (night I5) ----

    @Test
    fun verdict_table_locks_warns_and_readies_at_the_bounds() {
        // The I5 table against the plan's own rule, one 120-frame
        // 3840x2160 clip (~1,99 GiB required with the 1,05 headroom):
        // every row is a distinct gate boundary, checked from the
        // VERDICT (the sheet's enqueue-time decision), not just the
        // plan — the sheet must fire the lock BEFORE the queue accepts.
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160)

        // free = required - 1: LOCKED, the button refuses.
        val lock = CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required - 1)
        assertTrue(lock is CinemaDngSheetState.Verdict.Locked)
        assertFalse(CinemaDngSheetState.canStart(lock))
        assertEquals(required, (lock as CinemaDngSheetState.Verdict.Locked).requiredBytes)

        // free < required (well under): LOCKED too.
        val lockFar = CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required / 2)
        assertTrue(lockFar is CinemaDngSheetState.Verdict.Locked)

        // free = required exactly: WARN (the strict "< gerekli" lock
        // rule; equal is past the lock, under x1,5).
        val warnAtExact = CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required)
        assertTrue(warnAtExact is CinemaDngSheetState.Verdict.Warn)
        assertTrue(CinemaDngSheetState.canStart(warnAtExact))

        // free < required x 1,5: WARN, still startable.
        val warn = CinemaDngSheetState.verdict(
            "content://tree/x", 120, 0, -1, 3840, 2160,
            (required * CinemaDngPlan.STORAGE_WARN_MULTIPLE).toLong() - 1,
        )
        assertTrue(warn is CinemaDngSheetState.Verdict.Warn)
        assertTrue(CinemaDngSheetState.canStart(warn))

        // free = required x 1,5: READY (the rule's strict <).
        val ready = CinemaDngSheetState.verdict(
            "content://tree/x", 120, 0, -1, 3840, 2160,
            (required * CinemaDngPlan.STORAGE_WARN_MULTIPLE).toLong(),
        )
        assertTrue(ready is CinemaDngSheetState.Verdict.Ok)
        assertTrue(CinemaDngSheetState.canStart(ready))
    }

    @Test
    fun verdict_table_no_folder_when_nothing_is_picked() {
        // No folder: NoFolder — the button refuses and the line says
        // pick one, whatever the free space says.
        val v = CinemaDngSheetState.verdict(null, 120, 0, -1, 3840, 2160, 10L shl 40)
        assertTrue(v is CinemaDngSheetState.Verdict.NoFolder)
        assertFalse(CinemaDngSheetState.canStart(v))
    }

    @Test
    fun verdict_table_unknown_free_never_locks() {
        // freeBytes -1 (the SAF folder could not be measured): the
        // verdict is NEVER a lock (F's honesty rule) — Ok with the free
        // part unknown, startable, and the honest wording says so.
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160)
        // Even a required that WOULD lock with a measured tiny free:
        val v = CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, -1L)
        assertTrue(v is CinemaDngSheetState.Verdict.Ok)
        assertTrue(CinemaDngSheetState.canStart(v))
        assertTrue(required > 0) // the case is real: a lock was possible
    }

    @Test
    fun verdict_table_audio_rides_the_plan_size_not_the_sheet() {
        // The I5 "ses var/yok" row: the SHEET verdict is built from the
        // plan's frame bytes only (the WAV rides inside the 1,05
        // headroom — the plan's own rule, CinemaDngPlan §6), so the
        // audio presence cannot change the verdict's required number.
        // Pinned from the verdict: same clips, same required, whether
        // the caller would carry audio or not — the sheet's verdict
        // signature carries no audio fields at all, and the plan's
        // requiredBytes is the frame-only formula.
        val noAudio = CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, 0)
        assertEquals(
            CinemaDngPlan.requiredBytes(120, 3840, 2160),
            (noAudio as CinemaDngSheetState.Verdict.Locked).requiredBytes,
        )
        // The plan's own audio term is separate and never joins the
        // sheet's required (the headroom covers it — pinned here so a
        // future change that moves the WAV into the gate updates both).
        val wav = CinemaDngPlan.wavBytes(120, 30.0, 48000, 2)
        assertTrue(wav > 0)
        assertTrue(
            wav < CinemaDngPlan.requiredBytes(120, 3840, 2160) -
                CinemaDngPlan.estimatedBytes(120, 3840, 2160),
        )
    }

    @Test
    fun verdict_message_keys_follow_the_verdict() {
        // The pure verdict -> message mapping (ImportErrors' shape): the
        // sheet resolves the resource, the test pins WHICH message and
        // WHICH args each state shows.
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160)

        // NoFolder -> the pick hint, no args.
        val none = CinemaDngSheetState.verdictMessage(
            CinemaDngSheetState.verdict(null, 120, 0, -1, 3840, 2160, 0))!!
        assertEquals(R.string.cinema_dng_no_folder_hint, none.messageRes)
        assertTrue(none.args.isEmpty())

        // Locked -> the lock line with required FIRST, free SECOND
        // (the strings' own %1$s/%2$s order: "needed" before "free").
        val lock = CinemaDngSheetState.verdictMessage(
            CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required - 1))!!
        assertEquals(R.string.cinema_dng_storage_locked, lock.messageRes)
        assertEquals(2, lock.args.size)
        assertEquals(CinemaDngSheetState.sizeText(required), lock.args[0])
        assertEquals(CinemaDngSheetState.sizeText(required - 1), lock.args[1])

        // Warn -> the warn line, same arg order.
        val warn = CinemaDngSheetState.verdictMessage(
            CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required))!!
        assertEquals(R.string.cinema_dng_storage_warn, warn.messageRes)
        assertEquals(2, warn.args.size)

        // Ok with a measured free space: no line (the estimate row
        // already carries the size; nothing to say).
        val ready = CinemaDngSheetState.verdict(
            "content://tree/x", 120, 0, -1, 3840, 2160, 10L shl 40)
        assertNull(CinemaDngSheetState.verdictMessage(ready!!))
        // ...and never an error accent.
        assertFalse(CinemaDngSheetState.isErrorLine(ready))

        // Ok with free < 0 (unknown): the honest unknown-space line,
        // never a guessed verdict, no args.
        val unknown = CinemaDngSheetState.verdictMessage(
            CinemaDngSheetState.verdict("content://tree/x", 9000, 0, -1, 3840, 2160, -1L))!!
        assertEquals(R.string.cinema_dng_free_space_unknown, unknown.messageRes)
        assertTrue(unknown.args.isEmpty())
    }

    @Test
    fun verdict_error_line_only_when_locked() {
        // The accent rule (the IssueBand precedent): only the LOCK gets
        // the error color; warn/unknown are advisories, not failures.
        val required = CinemaDngPlan.requiredBytes(120, 3840, 2160)
        assertTrue(CinemaDngSheetState.isErrorLine(
            CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required - 1)))
        assertFalse(CinemaDngSheetState.isErrorLine(
            CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, required)))
        assertFalse(CinemaDngSheetState.isErrorLine(
            CinemaDngSheetState.verdict("content://tree/x", 120, 0, -1, 3840, 2160, -1L)))
        assertFalse(CinemaDngSheetState.isErrorLine(
            CinemaDngSheetState.verdict(null, 120, 0, -1, 3840, 2160, 0)))
    }

    // ---- the estimate line ----

    @Test
    fun estimate_text_uses_the_plan_numbers() {
        // The plan document's own 4 s row: 120 frames of 3840x2160 @ 30
        // fps -> "~1.9 GiB" (the table's own rounding).
        val t = CinemaDngSheetState.estimateText(120, 0, -1, 3840, 2160)
        assertEquals("120 frames · ~1.9 GiB", t)
        // A partial range: 10..20 of 100 = 11 frames of 64x48 (6144 B
        // each = 66 KiB total).
        assertEquals("11 frames · ~66.0 KiB",
            CinemaDngSheetState.estimateText(100, 10, 20, 64, 48))
    }

    @Test
    fun estimate_text_hides_when_no_honest_estimate_exists() {
        // Unknown dims: the line hides (never a guessed size).
        assertNull(CinemaDngSheetState.estimateText(100, 0, -1, 0, 0))
        // An unusable range: the line hides too.
        assertNull(CinemaDngSheetState.estimateText(100, 50, 10, 64, 48))
        assertNull(CinemaDngSheetState.estimateText(0, 0, -1, 64, 48))
    }

    // ---- the size wording ----

    @Test
    fun size_text_uses_the_plan_tables_units() {
        // The plan table's own 5-min row: the 9000-frame estimate is
        // 139,046 GiB — at/above 100 of the unit the decimal drops (the
        // document's own "~139 GiB" shape).
        assertEquals("139 GiB", CinemaDngSheetState.sizeText(
            CinemaDngPlan.estimatedBytes(9000, 3840, 2160)))
        assertEquals("1 B", CinemaDngSheetState.sizeText(1))
        assertEquals("1.0 KiB", CinemaDngSheetState.sizeText(1024))
        assertEquals("1.0 MiB", CinemaDngSheetState.sizeText(1024L * 1024))
        assertEquals("100 MiB", CinemaDngSheetState.sizeText(100L * 1024 * 1024))
        assertEquals("1.0 TiB", CinemaDngSheetState.sizeText(1024L * 1024 * 1024 * 1024))
        // The 4 s row: 1,86 GiB -> one decimal ("1.9 GiB", the table's own).
        assertEquals("1.9 GiB", CinemaDngSheetState.sizeText(
            CinemaDngPlan.estimatedBytes(120, 3840, 2160)))
    }

    // ---- the fps rational (the FrameRate tag's own value) ----

    @Test
    fun fps_rational_maps_the_common_families_exactly() {
        // 29.97 -> 30000/1001 (the exact rational the tag writes — never
        // a 1e6 approximation).
        assertEquals(Pair(30000, 1001), cinemaFpsRationalOf(30000.0 / 1001.0))
        assertEquals(Pair(24000, 1001), cinemaFpsRationalOf(24000.0 / 1001.0))
        assertEquals(Pair(24, 1), cinemaFpsRationalOf(24.0))
        assertEquals(Pair(25, 1), cinemaFpsRationalOf(25.0))
        assertEquals(Pair(30, 1), cinemaFpsRationalOf(30.0))
        assertEquals(Pair(50, 1), cinemaFpsRationalOf(50.0))
        assertEquals(Pair(60000, 1001), cinemaFpsRationalOf(60000.0 / 1001.0))
        assertEquals(Pair(60, 1), cinemaFpsRationalOf(60.0))
        // An odd rate keeps its rounded integer form with den 1.
        assertEquals(Pair(17, 1), cinemaFpsRationalOf(17.4))
    }

    /** The ui/editor helper reached through the same package test seam. */
    private fun cinemaFpsRationalOf(fps: Double): Pair<Int, Int> =
        com.ritim.ritimcraw.ui.editor.cinemaFpsRationalForTest(fps)
}
