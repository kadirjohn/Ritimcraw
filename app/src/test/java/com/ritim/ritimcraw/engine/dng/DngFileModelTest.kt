package com.ritim.ritimcraw.engine.dng

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure single-DNG metadata model's tests (night research
 * 2026-11-16, B3): the TIFF/IFD offset arithmetic on synthetic
 * buffers (little AND big endian — the header's own order is the
 * file's law), the validation rules and their rejections, and the
 * pattern/matrix mapping onto core's representations (the
 * rawedit::CfaPattern ordinals and cfaCodes, the RawMeta field
 * semantics). Pure JVM tests — no device, no native lib, no IO.
 */
class DngFileModelTest {

    // ---- 1. the TIFF header math ----

    @Test
    fun byte_order_recognizes_both_tiff_markers() {
        // The app's writer emits "II" (little); big-endian DNGs are legal.
        assertFalse(DngFile.byteOrderOf("II".toByteArray())!!) // little
        assertTrue(DngFile.byteOrderOf("MM".toByteArray())!!) // big
    }

    @Test
    fun byte_order_refuses_non_tiff_files() {
        // A JPEG's FFD8, a CR3's FTYP box, a short buffer: not ours.
        assertNull(DngFile.byteOrderOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(DngFile.byteOrderOf("ft".toByteArray()))
        assertNull(DngFile.byteOrderOf("I".toByteArray()))
    }

    @Test
    fun first_ifd_offset_reads_the_header_word_in_its_own_byte_order() {
        // The writer's layout: II 2A 00 08 00 00 00 -> IFD at 8.
        val little = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(),
            0x2A, 0x00, 0x08, 0x00, 0x00, 0x00,
        )
        assertEquals(8, DngFile.firstIfdOffset(little))
        // The same file big-endian: MM 00 2A 00 00 00 08.
        val big = byteArrayOf(
            'M'.code.toByte(), 'M'.code.toByte(),
            0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,
        )
        assertEquals(8, DngFile.firstIfdOffset(big))
    }

    @Test
    fun first_ifd_offset_refuses_a_wrong_magic_and_short_headers() {
        // "II" but magic 43, or a truncated header: null, never a guess.
        val wrongMagic = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(),
            0x2B, 0x00, 0x08, 0x00, 0x00, 0x00,
        )
        assertNull(DngFile.firstIfdOffset(wrongMagic))
        assertNull(DngFile.firstIfdOffset("II".toByteArray()))
    }

    @Test
    fun first_ifd_offset_does_not_assume_eight() {
        // The header's word is the law: an IFD at 100 is still valid.
        val little = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(),
            0x2A, 0x00, 100, 0x00, 0x00, 0x00,
        )
        assertEquals(100, DngFile.firstIfdOffset(little))
    }

    // ---- 2. the endian readers ----

    @Test
    fun u16_reads_little_and_big_endian_words() {
        val bytes = byteArrayOf(0x34, 0x12, 0x34, 0x12)
        assertEquals(0x1234, DngFile.readU16(bytes, 0, bigEndian = false))
        assertEquals(0x3412, DngFile.readU16(bytes, 0, bigEndian = true))
    }

    @Test
    fun u32_reads_little_and_big_endian_words() {
        val bytes = byteArrayOf(0x78, 0x56, 0x34, 0x12)
        assertEquals(0x12345678.toInt(), DngFile.readU32(bytes, 0, bigEndian = false))
        assertEquals(0x78563412.toInt(), DngFile.readU32(bytes, 0, bigEndian = true))
    }

    @Test
    fun rational_reads_numerator_over_denominator_in_both_orders() {
        // The writer's encoding: value * 1e6 / 1e6 (DngWriter.cpp putRational).
        val num = 1_500_000 // 1.5
        val den = 1_000_000
        val little = byteArrayOf(
            (num and 0xff).toByte(), (num shr 8).toByte(),
            (num shr 16).toByte(), (num shr 24).toByte(),
            (den and 0xff).toByte(), (den shr 8).toByte(),
            (den shr 16).toByte(), (den shr 24).toByte(),
        )
        assertEquals(1.5, DngFile.readRational(little, 0, bigEndian = false, signed = false), 0.0)
        val big = byteArrayOf(
            (num shr 24).toByte(), (num shr 16).toByte(),
            (num shr 8).toByte(), num.toByte(),
            (den shr 24).toByte(), (den shr 16).toByte(),
            (den shr 8).toByte(), den.toByte(),
        )
        assertEquals(1.5, DngFile.readRational(big, 0, bigEndian = true, signed = false), 0.0)
    }

    @Test
    fun rational_reads_negative_srational_numerators() {
        // ColorMatrix1 carries negative components (SRATIONAL).
        val num = -2 // -2/1e6 = -0.000002
        val den = 1_000_000
        val little = byteArrayOf(
            (num and 0xff).toByte(), (num shr 8).toByte(),
            (num shr 16).toByte(), (num shr 24).toByte(),
            (den and 0xff).toByte(), (den shr 8).toByte(),
            (den shr 16).toByte(), (den shr 24).toByte(),
        )
        assertEquals(-2.0e-6, DngFile.readRational(little, 0, bigEndian = false, signed = true), 1e-12)
    }

    @Test
    fun rational_zero_denominator_is_nan_never_a_silent_substitute() {
        // The DNG spec's invalid value: NaN, not 0, not 1 — the
        // validation rules reject it downstream.
        val little = byteArrayOf(
            1, 0, 0, 0, // numerator 1
            0, 0, 0, 0, // denominator 0
        )
        assertTrue(DngFile.readRational(little, 0, bigEndian = false, signed = false).isNaN())
    }

    // ---- 3. the IFD entry arithmetic ----

    @Test
    fun ifd_entry_count_reads_the_count_word() {
        // The writer's CFA DNG: 21 entries.
        val ifdOffset = 8
        val little = byteArrayOf(
            'I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0x00,
            ifdOffset.toByte(), 0, 0, 0,
            21, 0, // count word at the IFD offset
        )
        assertEquals(21, DngFile.ifdEntryCount(little, ifdOffset, bigEndian = false))
    }

    @Test
    fun ifd_entry_count_refuses_an_out_of_bounds_offset() {
        assertNull(DngFile.ifdEntryCount(ByteArray(10), ifdOffset = 20, bigEndian = false))
        assertNull(DngFile.ifdEntryCount(ByteArray(10), ifdOffset = 9, bigEndian = false))
    }

    @Test
    fun entry_offset_is_the_count_word_plus_index_times_twelve() {
        // Entry 0 sits at ifd+2, entry 5 at ifd+62 — the 12-byte stride.
        assertEquals(10, DngFile.entryOffset(8, 0))
        assertEquals(22, DngFile.entryOffset(8, 1))
        assertEquals(70, DngFile.entryOffset(8, 5))
    }

    @Test
    fun next_ifd_offset_reads_the_word_after_the_last_entry() {
        // 21 entries: the next-IFD word sits at 8 + 2 + 252 = 262.
        val ifdOffset = 8
        val count = 21
        val at = ifdOffset + 2 + count * 12
        val bytes = ByteArray(at + 4)
        bytes[at] = 0
        bytes[at + 1] = 1 // little-endian 256
        assertEquals(256, DngFile.nextIfdOffset(bytes, ifdOffset, count, bigEndian = false))
    }

    @Test
    fun next_ifd_offset_zero_means_none() {
        // The writer emits 0 (no next IFD) — the arithmetic reads it.
        val ifdOffset = 8
        val count = 21
        val bytes = ByteArray(ifdOffset + 2 + count * 12 + 4)
        assertEquals(0, DngFile.nextIfdOffset(bytes, ifdOffset, count, bigEndian = false))
    }

    @Test
    fun value_length_is_count_times_type_size() {
        // The writer's own sizes: SHORT 4 values = 8 bytes (external),
        // CFAPattern BYTE 4 = 4 bytes (inline), ColorMatrix1 SRATIONAL 9 = 72.
        assertEquals(8L, DngFile.valueLength(TiffTypes.SHORT, 4))
        assertEquals(4L, DngFile.valueLength(TiffTypes.BYTE, 4))
        assertEquals(72L, DngFile.valueLength(TiffTypes.SRATIONAL, 9))
        assertEquals(24L, DngFile.valueLength(TiffTypes.RATIONAL, 3))
        assertEquals(16L, DngFile.valueLength(TiffTypes.LONG, 4))
    }

    @Test
    fun inline_rule_is_four_bytes_or_fewer() {
        // The TIFF rule DngWriter.cpp encodes: count*typeSize <= 4 inline.
        assertTrue(DngFile.isInline(TiffTypes.BYTE, 4))
        assertTrue(DngFile.isInline(TiffTypes.SHORT, 2))
        assertTrue(DngFile.isInline(TiffTypes.LONG, 1))
        assertFalse(DngFile.isInline(TiffTypes.SHORT, 4))
        assertFalse(DngFile.isInline(TiffTypes.SRATIONAL, 9))
    }

    @Test
    fun value_offset_inline_reads_in_the_entry_external_reads_the_word() {
        // A SHORT inline at entry offset E reads at E+8 (the value
        // field); an external SHORT[4] reads the value field AS a LONG
        // — an ABSOLUTE file offset, never IFD-relative.
        val entryOffset = 20
        val bytes = ByteArray(64)
        // Inline: value field at 28.
        bytes[28] = 0x34
        bytes[29] = 0x12
        val inlineAt = DngFile.valueOffset(bytes, entryOffset, TiffTypes.SHORT, 2, bigEndian = false)
        assertEquals(28, inlineAt)
        assertEquals(0x1234, DngFile.readU16(bytes, inlineAt!!, bigEndian = false))
        // External: the value field at 28 carries the absolute offset 40.
        bytes[28] = 40
        bytes[29] = 0
        val externalAt = DngFile.valueOffset(bytes, entryOffset, TiffTypes.SHORT, 4, bigEndian = false)
        assertEquals(40, externalAt)
    }

    @Test
    fun value_offset_refuses_an_entry_that_runs_off_the_buffer() {
        assertNull(DngFile.valueOffset(ByteArray(16), entryOffset = 10, TiffTypes.SHORT, 2, bigEndian = false))
    }

    // ---- 4. the CFA pattern mapping onto core's representation ----

    @Test
    fun cfa_codes_match_the_writers_per_pattern_codes() {
        // DngWriter.cpp cfaCodes verbatim — the (r, g, g, b) scan order.
        assertTrue(DngCfa.RGGB.codes.contentEquals(intArrayOf(0, 1, 1, 2)))
        assertTrue(DngCfa.GRBG.codes.contentEquals(intArrayOf(1, 0, 2, 1)))
        assertTrue(DngCfa.GBRG.codes.contentEquals(intArrayOf(1, 2, 0, 1)))
        assertTrue(DngCfa.BGGR.codes.contentEquals(intArrayOf(2, 1, 1, 0)))
    }

    @Test
    fun cfa_ordinals_match_the_core_enum() {
        // rawedit::CfaPattern order (Source.h:27-32): RGGB=0, GRBG=1,
        // GBRG=2, BGGR=3 — FrameInfoK.cfa's Int vocabulary.
        assertEquals(0, DngCfa.RGGB.coreOrdinal)
        assertEquals(1, DngCfa.GRBG.coreOrdinal)
        assertEquals(2, DngCfa.GBRG.coreOrdinal)
        assertEquals(3, DngCfa.BGGR.coreOrdinal)
    }

    @Test
    fun cfa_from_codes_round_trips_every_pattern() {
        for (p in DngCfa.entries) {
            assertEquals(p, DngCfa.fromCodes(p.codes))
        }
    }

    @Test
    fun cfa_from_codes_rejects_unknown_arrangements() {
        // The pattern enum's rejection of unknown values: a non-Bayer
        // cell or a wrong-size code list is null, never a guess.
        assertNull(DngCfa.fromCodes(intArrayOf(0, 0, 0, 0))) // monochrome
        assertNull(DngCfa.fromCodes(intArrayOf(0, 1, 1, 2, 2))) // 5 sites
        assertNull(DngCfa.fromCodes(intArrayOf(0, 1, 1))) // 3 sites
        assertNull(DngCfa.fromCodes(intArrayOf(3, 1, 1, 0))) // 3 is not R/G/B
    }

    @Test
    fun cfa_code_at_is_the_cell_slot_order() {
        // Develop.cpp cellSlot(y*2+x): RGGB's codes are (0,1,1,2).
        assertEquals(0, DngCfa.RGGB.codeAt(0, 0)) // R
        assertEquals(1, DngCfa.RGGB.codeAt(0, 1)) // G
        assertEquals(1, DngCfa.RGGB.codeAt(1, 0)) // G
        assertEquals(2, DngCfa.RGGB.codeAt(1, 1)) // B
    }

    @Test
    fun cfa_pattern_of_validates_the_repeat_dim_and_the_codes() {
        assertEquals(DngCfa.GBRG, DngFile.cfaPatternOf(2, 2, intArrayOf(1, 2, 0, 1)))
    }

    @Test
    fun cfa_pattern_of_rejects_a_non_2x2_cell() {
        // A 2x4 or 6x6 CFA is refused — the demosaic knows 2x2 only.
        try {
            DngFile.cfaPatternOf(2, 4, intArrayOf(0, 1, 1, 2, 0, 1, 1, 2))
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_TAGS, e.stage)
            assertTrue(e.causeText.contains("2x4"))
            assertTrue(e.recovery.isNotBlank())
        }
    }

    @Test
    fun cfa_pattern_of_rejects_unknown_codes_with_the_error_shape() {
        try {
            DngFile.cfaPatternOf(2, 2, intArrayOf(0, 0, 0, 0))
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_TAGS, e.stage)
            assertTrue(e.causeText.contains("not one of the four"))
            // The DngSequenceException message shape: stage + cause + recovery.
            assertTrue(e.message!!.startsWith("DNG rejected (stage: tags):"))
            assertTrue(e.message!!.contains("— recovery:"))
        }
    }

    // ---- 5. the orientation mapping ----

    @Test
    fun orientation_maps_the_writers_four_tag_values() {
        // DngWriter.cpp tiffOrientation: 1=normal, 6=90 CW, 3=180, 8=270.
        assertEquals(DngOrientation.NORMAL, DngOrientation.fromTag(1))
        assertEquals(DngOrientation.ROTATE_90, DngOrientation.fromTag(6))
        assertEquals(DngOrientation.ROTATE_180, DngOrientation.fromTag(3))
        assertEquals(DngOrientation.ROTATE_270, DngOrientation.fromTag(8))
        // The degrees CW are FrameInfo.orientationDeg's vocabulary.
        assertEquals(0, DngOrientation.NORMAL.degreesCw)
        assertEquals(90, DngOrientation.ROTATE_90.degreesCw)
        assertEquals(180, DngOrientation.ROTATE_180.degreesCw)
        assertEquals(270, DngOrientation.ROTATE_270.degreesCw)
    }

    @Test
    fun orientation_rejects_the_mirror_values() {
        // 2/4 (mirrors) and 5/7 (mixed) are not rotations — refused
        // honestly rather than guessed.
        assertNull(DngOrientation.fromTag(2))
        assertNull(DngOrientation.fromTag(4))
        assertNull(DngOrientation.fromTag(5))
        assertNull(DngOrientation.fromTag(7))
        try {
            DngFile.orientationOf(2)
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_TAGS, e.stage)
        }
    }

    // ---- 6. the active-area invariant ----

    @Test
    fun active_area_accepts_an_area_inside_the_frame() {
        val a = DngFile.activeAreaOf(top = 4, left = 8, bottom = 100, right = 200, width = 216, height = 104)
        assertEquals(4, a.top)
        assertEquals(192, a.width)
        assertEquals(96, a.height)
    }

    @Test
    fun active_area_accepts_the_full_frame() {
        val a = DngFile.activeAreaOf(0, 0, 104, 216, 216, 104)
        assertEquals(216, a.width)
        assertEquals(104, a.height)
    }

    @Test
    fun active_area_rejects_out_of_frame_and_empty_areas() {
        // Bottom below the frame, right past the width, an inverted
        // rect, an empty rect: all stage "tags" rejections.
        val bad = listOf(
            intArrayOf(0, 0, 105, 216), // bottom one past the height
            intArrayOf(0, 0, 104, 217), // right one past the width
            intArrayOf(50, 0, 40, 216), // inverted (bottom < top)
            intArrayOf(0, 0, 0, 216), // empty (bottom == top)
            intArrayOf(-1, 0, 104, 216), // negative top
        )
        for (b in bad) {
            try {
                DngFile.activeAreaOf(b[0], b[1], b[2], b[3], width = 216, height = 104)
                throw AssertionError("expected DngFileException for ${b.joinToString()}")
            } catch (e: DngFileException) {
                assertEquals(DngFile.STAGE_TAGS, e.stage)
            }
        }
    }

    // ---- 7. the level validation rules ----

    @Test
    fun white_level_accepts_the_supported_bit_depths() {
        // The writer's 16-bit 65535; a camera's 14-bit 16383.
        assertEquals(65535, DngFile.whiteLevelOf(65535.0, 16))
        assertEquals(16383, DngFile.whiteLevelOf(16383.0, 14))
        assertEquals(1023, DngFile.whiteLevelOf(1023.0, 10))
    }

    @Test
    fun white_level_rejects_nan_negative_zero_and_overflow() {
        val bad = listOf(
            Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY,
            -1.0, 0.0,
            65536.0, // one past the 16-bit ceiling
            1e9,
        )
        for (v in bad) {
            try {
                DngFile.whiteLevelOf(v, 16)
                throw AssertionError("expected DngFileException for $v")
            } catch (e: DngFileException) {
                assertEquals(DngFile.STAGE_TAGS, e.stage)
            }
        }
    }

    @Test
    fun white_level_rejects_an_unsupported_bit_depth() {
        try {
            DngFile.whiteLevelOf(65535.0, 13)
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertTrue(e.causeText.contains("13"))
        }
    }

    @Test
    fun black_levels_expand_a_single_value_to_every_site() {
        // The DNG spec's singularity rule: one value applies to all.
        val expanded = DngFile.blackLevelsOf(doubleArrayOf(256.0), 2, 2, whiteLevel = 4095)
        assertTrue(expanded.contentEquals(intArrayOf(256, 256, 256, 256)))
    }

    @Test
    fun black_levels_keep_the_per_site_cell_slot_order() {
        // Four values stay in cellY*2+cellX order — the order
        // Develop.cpp indexes black[4] by.
        val expanded = DngFile.blackLevelsOf(
            doubleArrayOf(10.0, 11.0, 12.0, 13.0), 2, 2, whiteLevel = 4095,
        )
        assertTrue(expanded.contentEquals(intArrayOf(10, 11, 12, 13)))
    }

    @Test
    fun black_levels_accept_the_linear_dng_three_value_shape() {
        // The app's own Linear DNG writes THREE values (RGB, 0s) with
        // a 1x1 repeat dim — the singularity rule reads it as one
        // value per sample plane.
        val expanded = DngFile.blackLevelsOf(
            doubleArrayOf(0.0, 0.0, 0.0), 1, 3, whiteLevel = 65535,
        )
        assertTrue(expanded.contentEquals(intArrayOf(0, 0, 0)))
    }

    @Test
    fun black_levels_reject_a_wrong_count() {
        // 2 values for a 2x2 cell: the spec allows 1 or 4 only.
        try {
            DngFile.blackLevelsOf(doubleArrayOf(1.0, 2.0), 2, 2, whiteLevel = 4095)
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_TAGS, e.stage)
            assertTrue(e.causeText.contains("2 values"))
        }
    }

    @Test
    fun black_levels_reject_nan_negative_and_at_white_values() {
        // NaN, negative, and a level at/above the white level: an
        // empty or inverted range is unusable.
        for (v in listOf(doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0, 0.0))) {
            try {
                DngFile.blackLevelsOf(v, 2, 2, whiteLevel = 4095)
                throw AssertionError("expected DngFileException")
            } catch (e: DngFileException) {
                assertEquals(DngFile.STAGE_TAGS, e.stage)
            }
        }
        try {
            DngFile.blackLevelsOf(doubleArrayOf(4095.0, 0.0, 0.0, 0.0), 2, 2, whiteLevel = 4095)
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_TAGS, e.stage)
        }
    }

    // ---- 8. the WB gains / matrix mapping onto core's representations ----

    @Test
    fun wb_gains_are_the_reciprocal_of_as_shot_neutral() {
        // RawMeta's contract (Source.h): wbGains is asShotNeutral's
        // reciprocal (DNG semantics — WB == division by asShotNeutral).
        val gains = DngFile.wbGainsOf(doubleArrayOf(2.0, 1.0, 4.0))
        assertEquals(0.5, gains[0], 0.0)
        assertEquals(1.0, gains[1], 0.0)
        assertEquals(0.25, gains[2], 0.0)
    }

    @Test
    fun wb_gains_reject_wrong_counts_and_unusable_values() {
        // Not r-g-b, or a zero/NaN component (divide-by-zero poison).
        for (v in listOf(
            doubleArrayOf(1.0, 1.0),
            doubleArrayOf(0.0, 1.0, 1.0),
            doubleArrayOf(1.0, Double.NaN, 1.0),
            doubleArrayOf(-1.0, 1.0, 1.0),
        )) {
            try {
                DngFile.wbGainsOf(v)
                throw AssertionError("expected DngFileException")
            } catch (e: DngFileException) {
                assertEquals(DngFile.STAGE_TAGS, e.stage)
            }
        }
    }

    @Test
    fun color_matrix_validates_nine_finite_components() {
        // ColorMatrix1: 9 SRATIONALs, XYZ -> native camera RGB,
        // row-major — the adapter fills RawMeta.colorMatrix verbatim.
        val m = DngFile.colorMatrixOf(
            doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        )
        assertTrue(m.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)))
    }

    @Test
    fun color_matrix_rejects_wrong_counts_and_non_finite_components() {
        for (v in listOf(
            doubleArrayOf(1.0, 0.0, 0.0),
            DoubleArray(10) { 1.0 },
            doubleArrayOf(Double.NaN, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            doubleArrayOf(Double.POSITIVE_INFINITY, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        )) {
            try {
                DngFile.colorMatrixOf(v)
                throw AssertionError("expected DngFileException")
            } catch (e: DngFileException) {
                assertEquals(DngFile.STAGE_TAGS, e.stage)
            }
        }
    }

    // ---- 9. the strip layout / IN-17 consistency ----

    @Test
    fun strip_layout_accepts_the_writers_own_shape() {
        // The writer's CFA DNG: one full-frame strip, w*h*2 bytes.
        DngFile.stripLayoutOf(
            width = 216, height = 104, bitsPerSample = 16,
            samplesPerPixel = 1, rowsPerStrip = 104, stripByteCount = 216L * 104 * 2,
        )
        // The writer's Linear DNG: w*h*3*2 bytes.
        DngFile.stripLayoutOf(
            width = 216, height = 104, bitsPerSample = 16,
            samplesPerPixel = 3, rowsPerStrip = 104, stripByteCount = 216L * 104 * 3 * 2,
        )
    }

    @Test
    fun strip_layout_rejects_a_byte_count_mismatch() {
        // IN-17: the overflow/offset/frame-count consistency check —
        // a count that does not fit the frame is stage "pixels".
        try {
            DngFile.stripLayoutOf(
                width = 216, height = 104, bitsPerSample = 16,
                samplesPerPixel = 1, rowsPerStrip = 104, stripByteCount = 216L * 104 * 2 + 1,
            )
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_PIXELS, e.stage)
            assertTrue(e.causeText.contains("does not match"))
        }
    }

    @Test
    fun strip_layout_rejects_multi_strip_files() {
        // Legal TIFF, but a first-wave refusal — the honest subset.
        try {
            DngFile.stripLayoutOf(
                width = 216, height = 104, bitsPerSample = 16,
                samplesPerPixel = 1, rowsPerStrip = 52, stripByteCount = 216L * 52 * 2,
            )
            throw AssertionError("expected DngFileException")
        } catch (e: DngFileException) {
            assertEquals(DngFile.STAGE_PIXELS, e.stage)
            assertTrue(e.causeText.contains("multi-strip"))
        }
    }

    // ---- 10. the metadata record ----

    /** A valid CFA record in the writer's own shape (the round-trip target). */
    private fun writerCfaMetadata(): DngFileMetadata = DngFileMetadata(
        width = 216,
        height = 104,
        bitDepth = 16,
        compression = DngCompression.NONE,
        photometric = DngPhotometric.CFA,
        samplesPerPixel = 1,
        orientation = DngOrientation.ROTATE_90,
        stripOffset = 390,
        stripByteCount = 216L * 104 * 2,
        cfa = DngCfa.GBRG,
        blackLevels = intArrayOf(10, 11, 12, 13),
        whiteLevel = 65535,
        asShotNeutral = doubleArrayOf(2.0, 1.0, 4.0),
        colorMatrix = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
        activeArea = DngActiveArea(0, 0, 104, 216),
        imageDescription = "",
    )

    @Test
    fun metadata_record_holds_the_writers_own_shape() {
        val m = writerCfaMetadata()
        assertEquals(216, m.width)
        assertEquals(DngCfa.GBRG, m.cfa)
        assertEquals(2, DngCfa.GBRG.coreOrdinal) // FrameInfoK.cfa's Int
        assertEquals(90, m.orientation.degreesCw) // FrameInfo.orientationDeg
        assertEquals(65535, m.effectiveWhiteLevel)
        assertTrue(m.effectiveBlackLevels.contentEquals(intArrayOf(10, 11, 12, 13)))
        assertEquals(0.5, m.wbGains[0], 0.0) // RawMeta.wbGains
        assertEquals(216, m.effectiveActiveArea.width)
    }

    @Test
    fun metadata_defaults_apply_the_absent_tag_conventions() {
        // Absent black/white/activeArea: the RawMeta conventions —
        // 0s, the bit ceiling, the full frame. Never a guess.
        val m = writerCfaMetadata().copy(
            blackLevels = null,
            whiteLevel = null,
            activeArea = null,
        )
        assertTrue(m.effectiveBlackLevels.contentEquals(intArrayOf(0, 0, 0, 0)))
        assertEquals(65535, m.effectiveWhiteLevel)
        assertEquals(DngActiveArea(0, 0, 104, 216), m.effectiveActiveArea)
    }

    @Test
    fun metadata_rejects_an_inconsistent_record() {
        // The constructor's init is the last line of validation: a
        // LinearRaw photometric with 1 sample, or a CFA photometric
        // without a pattern, is an illegal record.
        try {
            writerCfaMetadata().copy(photometric = DngPhotometric.LINEAR_RAW)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            writerCfaMetadata().copy(cfa = null)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            writerCfaMetadata().copy(samplesPerPixel = 3)
            throw AssertionError("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun linear_dng_contract_is_recognized_by_its_stage_record() {
        // The app's own Linear DNG: ImageDescription names developLinear
        // (kLinearDngStages) — the reader must not re-apply black
        // normalization, demosaic or WB.
        val linear = DngFileMetadata(
            width = 216,
            height = 104,
            bitDepth = 16,
            compression = DngCompression.NONE,
            photometric = DngPhotometric.LINEAR_RAW,
            samplesPerPixel = 3,
            orientation = DngOrientation.NORMAL,
            stripOffset = 390,
            stripByteCount = 216L * 104 * 3 * 2,
            cfa = null,
            blackLevels = intArrayOf(0, 0, 0),
            whiteLevel = 65535,
            asShotNeutral = doubleArrayOf(1.0, 1.0, 1.0), // identity: already WB'd
            colorMatrix = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0),
            activeArea = DngActiveArea(0, 0, 104, 216),
            imageDescription = "ritiMCRAW linear DNG: ... developed by developLinear.",
        )
        assertTrue(linear.isRitiLinearDng)
        assertFalse(writerCfaMetadata().isRitiLinearDng)
        // The identity neutral maps to identity gains — no double WB.
        assertTrue(linear.wbGains.contentEquals(doubleArrayOf(1.0, 1.0, 1.0)))
    }

    // ---- 11. the tag set ----

    @Test
    fun known_tags_are_exactly_the_subset_the_plan_defines() {
        // 23 tags: the union of the writer's own set and the owner's
        // actual sources' carry (DNG_SOURCE_PLAN.md section 2).
        assertEquals(23, DngFile.KNOWN_TAGS.size)
        // The writer's own tags are all present (the round-trip target).
        for (t in intArrayOf(
            254, 256, 257, 258, 259, 262, 270, 273, 274, 277, 278, 279,
            33421, 33422, 50706, 50710, 50711, 50713, 50714, 50717,
            50721, 50728, 50829,
        )) {
            assertTrue("tag $t missing", t in DngFile.KNOWN_TAGS)
        }
    }

    @Test
    fun required_tags_are_the_honest_minimum() {
        // A file missing any of these cannot be decoded by ANY reader:
        // the layout tags, the pixel-format tags and the DNG marker.
        assertEquals(
            listOf(256, 257, 258, 259, 262, 273, 277, 278, 279, 50706),
            DngFile.REQUIRED_TAGS.toList(),
        )
        // Every required tag is in the known set.
        for (t in DngFile.REQUIRED_TAGS) assertTrue(t in DngFile.KNOWN_TAGS)
    }

    @Test
    fun error_message_carries_the_stage_cause_recovery_shape() {
        // The DngSequenceException/PreviewErrors vocabulary, verbatim
        // shape: "DNG rejected (stage: X): cause — recovery: Y [detail]".
        val m = DngFile.message("header", "not a DNG file", "pick a .dng photo", "magic 43")
        assertEquals(
            "DNG rejected (stage: header): not a DNG file — recovery: pick a .dng photo [magic 43]",
            m,
        )
    }
}
