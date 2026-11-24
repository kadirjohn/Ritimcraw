package com.ritim.ritimcraw.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The measurement mode's pure core (plan §3, E1e-a): matrix generation
 * from encoder facts, the uncapped point rule, the format-value parity
 * with [ExportFormats.video], the summary's VBR/CBR split and the log
 * line. Every encoder here is SYNTHETIC (the same shape the plan's
 * example uses: ceiling 160 M, 220 M, none) — the real device's facts
 * only exist at runtime; the RULES are what these tests pin.
 */
class CodecMeasurePlanTest {

    private val SURFACE = 0x7F000789
    private val YUV = 0x7F420888
    private val P010 = 54

    /** A HEVC encoder with the 15 Ultra's reported ceiling (160 Mb/s) and Main10. */
    private fun hevc160() = EncoderFacts(
        "c2.test.hevc.encoder", "video/hevc", hardware = true,
        profiles = setOf(1, 2), colorFormats = setOf(SURFACE, YUV, P010),
        bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..160_000_000,
        maxWidth = 8192, maxHeight = 8192,
        sizeRate = SizeRateProbe { _, _, fps -> fps <= 480.0 },
    )

    /** An AVC encoder with the 220 Mb/s listed ceiling, 8-bit only. */
    private fun avc220() = EncoderFacts(
        "c2.test.avc.encoder", "video/avc", hardware = true,
        profiles = setOf(1, 8), colorFormats = setOf(SURFACE, YUV),
        bitrateModes = setOf(1, 2), bitrateRangeBps = 1_000_000..220_000_000,
        maxWidth = 8192, maxHeight = 8192,
        sizeRate = SizeRateProbe { _, _, fps -> fps <= 480.0 },
    )

    // ---- 1. matrix generation (plan §3.1) ----

    @Test
    fun hevc_ceiling_160_puts_the_four_points_at_140_160_200_240_mb() {
        val m = CodecMeasurePlanner.matrix(listOf(hevc160(), avc220()), MeasureOptions(tenBit = false))
        val hevc4k30Cbr = m.cases.filter {
            it.mime == "video/hevc" && it.fps == 30 && it.rateMode == ExportRateMode.CBR.mediaCodecMode && it.bitDepth == 8
        }
        assertEquals(
            listOf(140_000_000L, 160_000_000L, 200_000_000L, 240_000_000L),
            hevc4k30Cbr.map { it.requestedBps },
        )
    }

    @Test
    fun avc_ceiling_220_puts_the_four_points_at_192_5_220_275_330_mb() {
        val m = CodecMeasurePlanner.matrix(listOf(hevc160(), avc220()), MeasureOptions(tenBit = false))
        val avc4k60Vbr = m.cases.filter {
            it.mime == "video/avc" && it.fps == 60 && it.rateMode == ExportRateMode.VBR.mediaCodecMode
        }
        assertEquals(
            listOf(
                Math.round(220_000_000 * 0.875),
                220_000_000L,
                Math.round(220_000_000 * 1.25),
                Math.round(220_000_000 * 1.5),
            ),
            avc4k60Vbr.map { it.requestedBps },
        )
        assertEquals(192_500_000L, avc4k60Vbr.map { it.requestedBps }[0])
    }

    @Test
    fun full_8bit_matrix_is_32_runs_and_every_run_gates_the_uncapped_flag_correctly() {
        // 2 codecs x 2 sizes x 2 modes x 4 points = 32 (the plan's §2 table
        // minus the 10-bit group).
        val m = CodecMeasurePlanner.matrix(listOf(hevc160(), avc220()), MeasureOptions(tenBit = false))
        assertEquals(32, m.cases.size)
        // uncapped is exactly the rows above that codec's ceiling
        val hevc = m.cases.filter { it.mime == "video/hevc" }
        assertEquals(16, hevc.size)
        assertEquals(setOf(true), hevc.filter { it.requestedBps > 160_000_000L }.map { it.uncapped }.toSet())
        assertEquals(setOf(false), hevc.filter { it.requestedBps <= 160_000_000L }.map { it.uncapped }.toSet())
    }

    @Test
    fun no_ceiling_encoder_is_skipped_with_a_note_and_empty_device_yields_empty_matrix() {
        val ceilingless = EncoderFacts(
            "c2.android.hevc.encoder", "video/hevc", hardware = false,
            profiles = setOf(1), colorFormats = setOf(YUV),
            bitrateModes = setOf(1, 2), bitrateRangeBps = null,
        )
        val m = CodecMeasurePlanner.matrix(listOf(ceilingless), MeasureOptions())
        assertTrue("no-ceiling encoder must yield no cases", m.cases.isEmpty())
        assertTrue(
            "the note must name the encoder and the reason",
            m.notes.any { it.contains("c2.android.hevc.encoder") && it.contains("ceiling") },
        )
        // Edge case (plan §3.5): a device whose probe returned nothing.
        val empty = CodecMeasurePlanner.matrix(emptyList(), MeasureOptions())
        assertTrue(empty.cases.isEmpty())
        assertTrue(empty.notes.isNotEmpty())
    }

    @Test
    fun ten_bit_rows_drop_without_the_main10_profile_or_p010_input() {
        // Main10 listed + P010 in colorFormats -> the 16-row 10-bit group.
        val with10 = CodecMeasurePlanner.matrix(listOf(hevc160()), MeasureOptions(tenBit = true))
        assertEquals(16, with10.cases.count { it.bitDepth == 10 })
        assertEquals(2, with10.cases.first { it.bitDepth == 10 }.profile) // HEVC Main10
        // No Main10 profile in the profile list -> the 10-bit group drops.
        val noMain10 = hevc160().let {
            EncoderFacts(
                it.name, it.mime, it.hardware, profiles = setOf(1), colorFormats = it.colorFormats,
                bitrateModes = it.bitrateModes, bitrateRangeBps = it.bitrateRangeBps,
                maxWidth = it.maxWidth, maxHeight = it.maxHeight, sizeRate = it.sizeRate,
            )
        }
        val dropped = CodecMeasurePlanner.matrix(listOf(noMain10), MeasureOptions(tenBit = true))
        assertEquals(0, dropped.cases.count { it.bitDepth == 10 })
        assertEquals(16, dropped.cases.size)
    }

    @Test
    fun non_general_encoders_never_enter_the_matrix() {
        // c2.qti.hevc.encoder.cq's shape: CQ-only bitrate mode -> Role.CQ_ONLY.
        val cq = EncoderFacts(
            "c2.test.hevc.encoder.cq", "video/hevc", hardware = true,
            profiles = setOf(1), colorFormats = setOf(YUV),
            bitrateModes = setOf(0), bitrateRangeBps = 1_000_000..160_000_000,
        )
        val m = CodecMeasurePlanner.matrix(listOf(cq), MeasureOptions())
        assertTrue(m.cases.isEmpty())
    }

    // ---- 2. the format VALUES vs ExportFormats.video (plan §3.2) ----

    @Test
    fun format_values_match_exportformats_video_for_the_same_settings() {
        // The same ask routed through the EXPORT plan (the writer's real
        // path) and the MEASUREMENT case: the key/value maps must agree —
        // the measurement mode's single-format-authority contract.
        val enc = listOf(hevc160(), avc220())
        val plan = ExportPlanner.resolve(
            ExportSettings(
                codec = ExportCodec.HEVC, rateMode = ExportRateMode.VBR,
                bitrateLevel = BitrateLevel.CUSTOM, customMbps = 200.0, uncapped = true,
            ),
            ExportInput(3840, 2160, 30.0),
            enc,
        )
        val spec = ExportFormats.video(plan, surface = false)
        val c = CodecMeasureCase(
            mime = "video/hevc", encoderName = "c2.test.hevc.encoder", profile = null,
            bitDepth = 8, width = 3840, height = 2160, fps = 30,
            rateMode = ExportRateMode.VBR.mediaCodecMode, requestedBps = 200_000_000,
            uncapped = true, durationSec = 2.0, keyframeSec = 1f, content = "ramp+v1",
        )
        val v = CodecMeasurePlanner.formatValues(c)
        // Same key SET and same values for every shared key (bitrate is the
        // case's own ask; the map equality is over keys and Number equality).
        assertEquals(spec.values.keys, v.keys)
        for ((k, expected) in spec.values) {
            assertEquals("key $k", expected.toInt(), v[k]!!.toInt())
        }
    }

    @Test
    fun format_values_carry_the_bitrate_mode_key_exactly_as_media_format_spells_it() {
        // "bitrate-mode" IS MediaFormat.KEY_BITRATE_MODE; pinning the
        // literal guards the constant against a typo drift.
        val c = CodecMeasureCase(
            "video/avc", null, profile = 8, bitDepth = 8,
            3840, 2160, 30, ExportRateMode.CBR.mediaCodecMode,
            220_000_000, true, 2.0, 1f, "ramp+v1",
        )
        val v = CodecMeasurePlanner.formatValues(c)
        assertEquals(ExportRateMode.CBR.mediaCodecMode, v["bitrate-mode"])
        assertEquals(220_000_000L, v["bitrate"])
        assertEquals(2 /* BITRATE_MODE_CBR */, v["bitrate-mode"])
        assertEquals(8 /* AVC High */, v["profile"])
    }

    // ---- 3. result measurement derivation (plan §3.3) ----

    @Test
    fun measured_bitrate_is_bytes_times_8_over_pts_duration() {
        val c = CodecMeasureCase(
            "video/hevc", "e", null, 8, 3840, 2160, 30,
            ExportRateMode.VBR.mediaCodecMode, 140_000_000, false, 2.0, 1f, "ramp+v1",
        )
        // The RUNNER's exact formula: (bytes * 8 / ptsSeconds).toLong().
        val r = MeasureResult(c, true, null, encodedBytes = 120_000, ptsDurationSec = 2.0, measuredBps = 480_000L, 0, 0, 60, null, null, 123)
        assertEquals(480_000L, (r.encodedBytes * 8 / r.ptsDurationSec!!).toLong())
        // The plan §3.3 example: 120000 bytes over 2.0 s = 480 kb/s.
        assertEquals(480_000L, (120_000 * 8 / 2.0).toLong())
        // ratio pins the VBR drift fact (the +24% observation's shape).
        assertEquals(480_000.0 / 140_000_000, r.ratio!!, 1e-12)
    }

    @Test
    fun a_configured_refusal_keeps_the_error_class_and_never_measures() {
        val c = CodecMeasureCase(
            "video/avc", "bad.encoder", 8, 10, 3840, 2160, 60,
            ExportRateMode.CBR.mediaCodecMode, 330_000_000, true, 2.0, 1f, "ramp+v1",
        )
        val r = MeasureResult(c, configureOk = false, errorCode = "IllegalStateException", 0, null, null, 0, 0, 0, null, null, 5)
        assertTrue(!r.configureOk)
        assertEquals("IllegalStateException", r.errorCode)
        assertNull(r.measuredBps)
        assertNull(r.ratio)
    }

    // ---- 4. summary (plan §3.4) ----

    @Test
    fun summarize_keeps_cbr_out_of_the_vbr_drift() {
        fun row(mode: Int, measured: Long) = MeasureResult(
            CodecMeasureCase(
                "video/hevc", "e", null, 8, 3840, 2160, 30, mode,
                100_000_000L, false, 2.0, 1f, "ramp+v1",
            ),
            configureOk = true, errorCode = null,
            encodedBytes = measured * 2 / 16, ptsDurationSec = 2.0 * 8 / 16, measuredBps = measured,
            iFrameBytes = 0, iFrameCount = 0, framesIn = 60, encoder = "e", hardware = true, wallMs = 1,
        )
        // 4 VBR runs drifting 10/20/24/30 %:
        val vbr = listOf(
            row(ExportRateMode.VBR.mediaCodecMode, 110_000_000L),
            row(ExportRateMode.VBR.mediaCodecMode, 120_000_000L),
            row(ExportRateMode.VBR.mediaCodecMode, 124_000_000L),
            row(ExportRateMode.VBR.mediaCodecMode, 130_000_000L),
        )
        // 2 CBR runs drifting too — the summary must NOT mix them in.
        val cbr = listOf(
            row(ExportRateMode.CBR.mediaCodecMode, 100_000_000L),
            row(ExportRateMode.CBR.mediaCodecMode, 150_000_000L),
        )
        val s = CodecMeasurePlanner.summarize(vbr + cbr)
        assertEquals(6, s.runs)
        assertEquals(6, s.configuredOk)
        assertEquals(0, s.configureRejections)
        assertEquals(4 + 2, s.measuredRuns)
        // median(1.10, 1.20, 1.24, 1.30) = (1.20 + 1.24)/2
        assertEquals(1.22, s.vbrDriftMedian!!, 1e-9)
        assertEquals(1.30, s.vbrDriftMax!!, 1e-9)
        assertEquals(1.25, s.cbrDriftMedian!!, 1e-9)
        assertEquals(1.5, s.cbrDriftMax!!, 1e-9)
    }

    @Test
    fun summarize_counts_configure_rejections_and_uncapped_acceptance() {
        fun case(uncapped: Boolean) = CodecMeasureCase(
            "video/avc", "e", 8, 8, 3840, 2160, 30,
            ExportRateMode.CBR.mediaCodecMode, 275_000_000L, uncapped, 2.0, 1f, "ramp+v1",
        )
        val rows = listOf(
            MeasureResult(case(true), true, null, 100, 0.1, 8_000_000L, 0, 0, 60, "e", true, 1),
            MeasureResult(case(true), false, "IllegalStateException", 0, null, null, 0, 0, 0, "e", true, 1),
            MeasureResult(case(false), true, null, 100, 0.1, 8_000_000L, 0, 0, 60, "e", true, 1),
        )
        val s = CodecMeasurePlanner.summarize(rows)
        assertEquals(3, s.runs)
        assertEquals(1, s.configureRejections)
        assertEquals(1, s.uncappedAccepted)
        assertEquals(2, s.uncappedTried)
        assertEquals(8_000_000L, s.measuredMaxBps)
    }

    @Test
    fun empty_rows_summarize_to_zero_counts_and_null_drift() {
        val s = CodecMeasurePlanner.summarize(emptyList())
        assertEquals(0, s.runs)
        assertEquals(0, s.uncappedTried)
        assertNull(s.vbrDriftMedian)
        assertNull(s.measuredMaxBps)
    }

    // ---- 5. the log line (plan §4.6) ----

    @Test
    fun the_log_line_carries_the_case_and_the_measurement() {
        val c = CodecMeasureCase(
            "video/hevc", "c2.qti.hevc.encoder", null, 8, 3840, 2160, 30,
            ExportRateMode.VBR.mediaCodecMode, 140_000_000, false, 2.0, 1f, "ramp+v1",
        )
        val r = MeasureResult(
            c, configureOk = true, errorCode = null,
            encodedBytes = 35_000_000, ptsDurationSec = 2.0, measuredBps = 140_000_000L,
            iFrameBytes = 4_000_000, iFrameCount = 2, framesIn = 60,
            encoder = "c2.qti.hevc.encoder", hardware = true, wallMs = 2100,
        )
        val line = CodecMeasurePlanner.line(r)
        assertTrue(line.startsWith("run: HEVC 3840×2160@30 VBR 8b"))
        assertTrue(line.contains("req=140000000"))
        assertTrue(line.contains("ok=1"))
        assertTrue(line.contains("bytes=35000000"))
        assertTrue(line.contains("measured=140000000"))
        assertTrue(line.contains("i=4000000/2"))
        assertTrue(line.contains("frames=60"))
        assertTrue(line.contains("enc=c2.qti.hevc.encoder"))
        assertTrue(line.contains("hw=true"))
        assertTrue(line.contains("wall=2100ms"))
    }

    @Test
    fun a_refused_line_flags_ok0_and_the_error_class() {
        val c = CodecMeasureCase(
            "video/avc", "c2.qti.avc.encoder", 8, 8, 3840, 2160, 60,
            ExportRateMode.CBR.mediaCodecMode, 330_000_000, true, 2.0, 1f, "ramp+v1",
        )
        val line = CodecMeasurePlanner.line(
            MeasureResult(c, false, "IllegalStateException", 0, null, null, 0, 0, 0, null, null, 3),
        )
        assertTrue(line.contains("ok=0"))
        assertTrue(line.contains("err=IllegalStateException"))
        assertTrue(line.contains("AVC 3840×2160@60 CBR"))
    }
}
