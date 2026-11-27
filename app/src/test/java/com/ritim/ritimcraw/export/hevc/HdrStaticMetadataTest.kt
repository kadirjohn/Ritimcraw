package com.ritim.ritimcraw.export.hevc

import com.ritim.ritimcraw.export.hevc.HdrStaticMetadata.CieChromaticity
import com.ritim.ritimcraw.export.hevc.HdrStaticMetadata.ContentLightLevel
import com.ritim.ritimcraw.export.hevc.HdrStaticMetadata.MasteringDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ST 2086 + CTA-861.3 static metadata tests (package E3, night
 * 2026-11-27). Every encoded value here is pinned against a source the
 * report names: the BT.2020/D65 codes against the ecosystem's own
 * writers (x265/ffmpeg/Dolby string/filmcraft's APV oracle), the
 * 24+4-byte layouts against filmcraft's SEI test, the Android key
 * string against the android-34 jar, the luminance scales against the
 * 24.8 / 18.14 fixed-point forms CTA-861.3 defines. "Uygulanışı
 * doğrulandı, gerçek encode doğrulanmadı" — no real HDR10 encode is
 * claimed anywhere in this file.
 *
 * Pure JVM; no device, no display.
 */
class HdrStaticMetadataTest {

    private val EPS = 1e-9

    // ---- the canonical instance: the ecosystem's encoded values, pinned ----

    @Test
    fun bt2020_d65_carries_the_industry_encoded_primaries() {
        val md = HdrStaticMetadata.BT2020_D65_1000NITS
        // R (0.708, 0.292) -> the canonical pair (46400, 19136): the
        // ecosystem's value, 1 ULP from the 4-decimal round (46399, 19137).
        assertEquals(CieChromaticity(46400, 19136), md.primaries[0])
        // G (0.170, 0.797) -> (11141, 52232), exactly the rounded values.
        assertEquals(CieChromaticity(11141, 52232), md.primaries[1])
        // B (0.131, 0.046) -> (8585, 3015), exactly the rounded values.
        assertEquals(CieChromaticity(8585, 3015), md.primaries[2])
        // D65 (0.3127, 0.3290) -> (20493, 21561), exactly the rounded values.
        assertEquals(CieChromaticity(20493, 21561), md.whitePoint)
    }

    @Test
    fun bt2020_d65_luminances_decode_to_1000_nits_peak_and_the_industry_floor() {
        val md = HdrStaticMetadata.BT2020_D65_1000NITS
        // 1000 cd/m² is exact in 24.8: 1000 * 256 = 256000.
        assertEquals(256000, md.maxLuminanceCode)
        assertEquals(1000.0, md.maxLuminanceNits, EPS)
        // The 18.14 floor: the industry writes code 1 for "0.0001 cd/m²"
        // (the Dolby master-display string "L(10000000,1)"), which decodes
        // to 6.1e-5 — the intent, not the exact decimal.
        assertEquals(HdrStaticMetadata.MIN_LUMINANCE_INDUSTRY_CODE, md.minLuminanceCode)
        assertEquals(1.0 / 16384.0, md.minLuminanceNits, EPS)
    }

    @Test
    fun the_four_decimal_standards_values_encode_within_one_ulp_of_the_canonical_codes() {
        // ITU-R BT.2020-2 Table 2 + D65, the standard's own 4-decimal
        // values, encoded at 0.16 half-up. R differs from the canonical
        // pair by exactly 1 in each coordinate; every other coordinate
        // IS the canonical code — the whole ambiguity is visible here.
        val r = HdrStaticMetadata.encodeChromaticityCode(0.708) to HdrStaticMetadata.encodeChromaticityCode(0.292)
        assertEquals(46399 to 19137, r)
        assertEquals(46400 - r.first, 1)
        assertEquals(19136 - r.second, -1)
        assertEquals(11141, HdrStaticMetadata.encodeChromaticityCode(0.170))
        assertEquals(52232, HdrStaticMetadata.encodeChromaticityCode(0.797))
        assertEquals(8585, HdrStaticMetadata.encodeChromaticityCode(0.131))
        assertEquals(3015, HdrStaticMetadata.encodeChromaticityCode(0.046))
        assertEquals(20493, HdrStaticMetadata.encodeChromaticityCode(0.3127))
        assertEquals(21561, HdrStaticMetadata.encodeChromaticityCode(0.3290))
    }

    @Test
    fun luminance_encoding_scales_are_the_standards_fixed_point_forms() {
        // 24.8: 1 cd/m² = 256; 1000 nits exact, 10 000 nits exact.
        assertEquals(256, HdrStaticMetadata.encodeMaxLuminanceCode(1.0))
        assertEquals(256000, HdrStaticMetadata.encodeMaxLuminanceCode(1000.0))
        assertEquals(2560000, HdrStaticMetadata.encodeMaxLuminanceCode(10000.0))
        // 18.14: 1 cd/m² = 16384; the common 0.0001 floor half-ups to 2
        // (the industry's 1 is the canonical instance's field, not this
        // helper's arithmetic).
        assertEquals(16384, HdrStaticMetadata.encodeMinLuminanceCode(1.0))
        assertEquals(2, HdrStaticMetadata.encodeMinLuminanceCode(0.0001))
        assertEquals(0, HdrStaticMetadata.encodeMinLuminanceCode(0.0))
        // A non-positive peak is not a mastering display.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.encodeMaxLuminanceCode(0.0)
        }
        // Chromaticities live in [0, 1] — outside is an error, never a clamp.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.encodeChromaticityCode(1.0001)
        }
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.encodeChromaticityCode(-0.01)
        }
    }

    // ---- ST 2086 model validation (the constructor's own rules) ----

    @Test
    fun a_mastering_display_needs_three_primaries_and_a_sane_luminance_pair() {
        val p = listOf(
            CieChromaticity(46400, 19136),
            CieChromaticity(11141, 52232),
            CieChromaticity(8585, 3015),
        )
        val good = MasteringDisplay(p, CieChromaticity(20493, 21561), 256000, 1)
        assertEquals(24, good.mdcvBytes().size)
        // Two primaries is not ST 2086.
        assertThrows(IllegalArgumentException::class.java) {
            MasteringDisplay(p.take(2), good.whitePoint, 256000, 1)
        }
        // A zero peak is not a mastering display.
        assertThrows(IllegalArgumentException::class.java) {
            MasteringDisplay(p, good.whitePoint, 0, 0)
        }
        // min >= max is a lie about a display.
        assertThrows(IllegalArgumentException::class.java) {
            MasteringDisplay(p, good.whitePoint, 256000, 256000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MasteringDisplay(p, good.whitePoint, 256000, 256001)
        }
        // Chromaticity codes must fit u16.
        assertThrows(IllegalArgumentException::class.java) {
            CieChromaticity(65536, 0)
        }
    }

    // ---- the mdcv/clli byte layouts, field by field ----

    @Test
    fun mdcv_bytes_lay_out_the_24_bytes_in_the_carriers_order() {
        val md = HdrStaticMetadata.BT2020_D65_1000NITS
        val b = md.mdcvBytes()
        assertEquals(24, b.size)
        fun u16(i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
        // R, G, B, WP as u16 pairs, big-endian.
        assertEquals(46400, u16(0)); assertEquals(19136, u16(2))
        assertEquals(11141, u16(4)); assertEquals(52232, u16(6))
        assertEquals(8585, u16(8)); assertEquals(3015, u16(10))
        assertEquals(20493, u16(12)); assertEquals(21561, u16(14))
        // max (24.8) then min (18.14) as u32, big-endian.
        assertEquals(256000, ((b[16].toInt() and 0xFF) shl 24) or ((b[17].toInt() and 0xFF) shl 16) or
            ((b[18].toInt() and 0xFF) shl 8) or (b[19].toInt() and 0xFF))
        assertEquals(1, ((b[20].toInt() and 0xFF) shl 24) or ((b[21].toInt() and 0xFF) shl 16) or
            ((b[22].toInt() and 0xFF) shl 8) or (b[23].toInt() and 0xFF))
    }

    @Test
    fun clli_bytes_are_two_big_endian_u16s() {
        val b = ContentLightLevel(1000, 400).clliBytes()
        assertEquals(4, b.size)
        assertEquals(1000, ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF))
        assertEquals(400, ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF))
        // The "unknown" pair is two zero words.
        assertEquals(byteArrayOf(0, 0, 0, 0).toList(), ContentLightLevel(0, 0).clliBytes().toList())
        // Above the u16 ceiling is an error at construction.
        assertThrows(IllegalArgumentException::class.java) { ContentLightLevel(65536, 1) }
        assertThrows(IllegalArgumentException::class.java) { ContentLightLevel(1, -1) }
    }

    // ---- round trips (parse <-> serialize, the brief's "gidiş-dönüş") ----

    @Test
    fun mdcv_bytes_round_trip_through_the_parser() {
        val md = HdrStaticMetadata.BT2020_D65_1000NITS
        assertEquals(md, MasteringDisplay.parse(md.mdcvBytes()))
        // A different display round-trips too (P3-D65 4000-nit master).
        val p3 = MasteringDisplay(
            listOf(
                CieChromaticity(44565, 20972),   // (0.680, 0.320) rounded
                CieChromaticity(17375, 45152),   // (0.265, 0.690) rounded
                CieChromaticity(9830, 3932),     // (0.150, 0.060) rounded
            ),
            CieChromaticity(20493, 21561),
            1024000, 1,
        )
        assertEquals(p3, MasteringDisplay.parse(p3.mdcvBytes()))
        assertEquals(4000.0, p3.maxLuminanceNits, EPS)
        // A wrong length is an error, never a guess.
        assertThrows(IllegalArgumentException::class.java) { MasteringDisplay.parse(ByteArray(23)) }
    }

    @Test
    fun the_28_byte_static_info_blob_round_trips() {
        val md = HdrStaticMetadata.BT2020_D65_1000NITS
        val cll = ContentLightLevel(1000, 400)
        val blob = HdrStaticMetadata.staticInfoBytes(md, cll)
        assertEquals(HdrStaticMetadata.STATIC_INFO_BYTES, blob.size)
        assertEquals(28, blob.size)
        val (mdBack, cllBack) = HdrStaticMetadata.parseStaticInfo(blob)
        assertEquals(md, mdBack)
        assertEquals(cll, cllBack)
        // A wrong length is an error.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.parseStaticInfo(ByteArray(27))
        }
    }

    @Test
    fun the_sei_payload_is_filmcraft_s_verified_sequence() {
        // 137 (mastering display) + 24-byte body, 144 (content light) +
        // 4-byte body, closed by the 0x80 rbsp trailing byte — the exact
        // SEI layout filmcraft's encoder test pins for the same metadata.
        val md = HdrStaticMetadata.BT2020_D65_1000NITS
        val cll = ContentLightLevel(1000, 400)
        val sei = HdrStaticMetadata.seiPayloadBytes(md, cll)
        val want = byteArrayOf(137.toByte(), 24.toByte()) + md.mdcvBytes() +
            byteArrayOf(144.toByte(), 4.toByte()) + cll.clliBytes() + byteArrayOf(0x80.toByte())
        assertEquals(want.toList(), sei.toList())
        // 2 (type+size) + 24 (mdcv) + 2 (type+size) + 4 (clli) + 1 (rbsp) = 33.
        assertEquals(33, sei.size)
    }

    // ---- MaxCLL / MaxFALL measurement (the brief's table cases) ----

    @Test
    fun an_all_zero_program_measures_zero_cll_and_zero_fall() {
        val cll = HdrStaticMetadata.measure(listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(0.0)))
        assertEquals(0, cll.maxCll)
        assertEquals(0, cll.maxFall)
        // A measured 0 is honest; on the wire code 0 READS "unknown" —
        // the model reports what was measured, the KDoc tells the story.
        assertEquals(HdrStaticMetadata.CLL_UNKNOWN, cll.maxCll)
    }

    @Test
    fun maxcll_is_the_brightest_single_pixel_across_every_frame() {
        // Frame 1 peak 100, frame 2 peak 900, frame 3 peak 300: MaxCLL 900.
        val cll = HdrStaticMetadata.measure(
            listOf(
                doubleArrayOf(10.0, 100.0, 5.0),
                doubleArrayOf(900.0, 50.0, 50.0),
                doubleArrayOf(300.0, 2.0, 1.0),
            ),
        )
        assertEquals(900, cll.maxCll)
    }

    @Test
    fun maxfall_is_the_brightest_frame_average_not_the_brightest_pixel() {
        // Frame A: one 1000-nit spike over 99 dark pixels -> mean 10.99.
        // Frame B: a flat 400-nit frame -> mean 400. MaxFALL is B's 400,
        // even though A holds the brighter PIXEL (the two differ: this is
        // the standard's own distinction between CLL and FALL).
        val spike = DoubleArray(100) { if (it == 0) 1000.0 else 0.0 }
        val flat = DoubleArray(100) { 400.0 }
        val cll = HdrStaticMetadata.measure(listOf(spike, flat))
        assertEquals(1000, cll.maxCll)
        assertEquals(400, cll.maxFall)
    }

    @Test
    fun maxfall_is_a_plain_arithmetic_mean_of_the_light_levels() {
        // CTA-861.3's "average of the light levels": (100+200+300)/3 = 200.
        val cll = HdrStaticMetadata.measure(listOf(doubleArrayOf(100.0, 200.0, 300.0)))
        assertEquals(200, cll.maxFall)
        assertEquals(300, cll.maxCll)
        // A single-pixel frame: its average IS its pixel.
        assertEquals(750, HdrStaticMetadata.measure(listOf(doubleArrayOf(750.0))).maxFall)
    }

    @Test
    fun rounding_is_half_up_and_clipping_stops_at_the_u16_ceiling() {
        // 400.5 half-ups to 401 for both statistics.
        val cll = HdrStaticMetadata.measure(listOf(doubleArrayOf(400.5, 400.5)))
        assertEquals(401, cll.maxCll)
        assertEquals(401, cll.maxFall)
        // 400.4 rounds down.
        val down = HdrStaticMetadata.measure(listOf(doubleArrayOf(400.4)))
        assertEquals(400, down.maxCll)
        // A value above the u16 ceiling clips to 65535 (defensive: PQ's
        // own domain stops at 10 000 nits, so this cannot be a real pixel).
        val clipped = HdrStaticMetadata.measure(listOf(doubleArrayOf(70000.0)))
        assertEquals(HdrStaticMetadata.CLL_MAX_CODE, clipped.maxCll)
    }

    @Test
    fun many_frames_take_the_global_maxima_in_order() {
        // The brief's "çok kare" row: 5 frames, the maxima land where
        // they land and the function stays pure (same input, same output).
        val frames = (1..5).map { i ->
            doubleArrayOf(i * 10.0, 100.0 - i, if (i == 3) 800.0 else 0.0)
        }
        val a = HdrStaticMetadata.measure(frames)
        val b = HdrStaticMetadata.measure(frames)
        assertEquals(a, b)
        // Frame 3 holds the 800 spike -> MaxCLL 800; frame 5's mean
        // (50 + 95 + 0)/3 = 48.33 -> 48; frame 1's mean (10+99)/3=36.33;
        // frame 2: (20+98)/3 = 39.33; frame 3: (30+97+800)/3 = 309; ->
        // MaxFALL 309.
        assertEquals(800, a.maxCll)
        assertEquals(309, a.maxFall)
    }

    @Test
    fun broken_measurement_inputs_are_errors_never_guessed_numbers() {
        // Empty frame list.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.measure(emptyList())
        }
        // A frame with no pixels: a frame-average cannot be guessed.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.measure(listOf(doubleArrayOf(1.0), DoubleArray(0)))
        }
        // Negative light.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.measure(listOf(doubleArrayOf(100.0, -1.0)))
        }
        // NaN.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.measure(listOf(doubleArrayOf(Double.NaN)))
        }
        // +Infinity.
        assertThrows(IllegalArgumentException::class.java) {
            HdrStaticMetadata.measure(listOf(doubleArrayOf(Double.POSITIVE_INFINITY)))
        }
    }

    // ---- the HDR10 contract tie: the profile that REQUIRES this blob ----

    @Test
    fun hdr10_is_the_profile_whose_contract_requires_this_blob_and_hlg_is_not() {
        // HevcProfile's own contract (its tests pin it): HDR10 requires
        // "hdr-static-info", HLG (scene-referred) never sets it. The tie
        // here: the blob this model builds is THAT key's payload, and the
        // key string is the android-34 jar's exact value.
        assertTrue(HevcProfile.HDR10.requiresHdrStaticInfo)
        assertTrue(!HevcProfile.HLG.requiresHdrStaticInfo)
        assertEquals("hdr-static-info", HdrStaticMetadata.KEY_HDR_STATIC_INFO)
    }
}
