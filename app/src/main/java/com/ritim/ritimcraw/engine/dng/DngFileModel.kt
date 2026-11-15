package com.ritim.ritimcraw.engine.dng

/**
 * The pure single-DNG metadata model — night research 2026-11-16, B3's
 * SECOND deliverable: the metadata contract a DNG SOURCE adapter will
 * fill before a single pixel is decoded. Spec IN-08/IN-10 (import) —
 * EX-14 (export) is a different deliverable; this is the SOURCE side.
 *
 * THE HOME (the brief's "state the home" question): `engine/dng/`,
 * beside B4's `engine/DngSequence.kt`. The two models are one
 * neighborhood by design — a CinemaDNG sequence import is N of this
 * file's metadata records plus DngSequence's ordering/timeline facts,
 * and the model deliberately does NOT fork DngSequence's halves (the
 * name rule, the gap/duplicate policy, the pts grid and the wav
 * binding stay THERE; this file knows nothing about folders, names
 * or rates).
 *
 * PURE AND DELIBERATELY DECODER-FREE. No Android imports, no file
 * IO, no JNI: the caller hands in decoded tag VALUES (the byte-level
 * reader is the decoder's device/IO leg) and the model validates,
 * normalizes and hands back facts. What IS here is the minimal
 * TIFF/IFD parse MATH as pure functions — the header/IFD/entry offset
 * arithmetic and the little/big-endian readers — testable on
 * synthetic buffers because they never touch IO. The future decoder
 * adapter (the DNG_SOURCE_PLAN.md contract) reads the bytes with
 * these functions and fills [DngFileMetadata] with the results.
 *
 * THE TAG SET (the minimum honest set — DNG_SOURCE_PLAN.md section 2
 * defines the subset; this model is its code form). It is exactly
 * what core's develop consumes (Source.h RawMeta/FrameInfo) plus the
 * layout tags a reader needs to find pixels — the union of the DNG
 * 1.4 tags the app's OWN writer emits (DngWriter.cpp) and the tags
 * the owner's actual sources carry:
 *   254 NewSubfileType      274 Orientation
 *   256 ImageWidth          277 SamplesPerPixel
 *   257 ImageLength        278 RowsPerStrip
 *   258 BitsPerSample       279 StripByteCounts
 *   259 Compression        33421 CFARepeatPatternDim
 *   262 PhotometricInterpretation  33422 CFAPattern
 *   270 ImageDescription   50706 DNGVersion
 *   273 StripOffsets        50710 CFAPlaneColor
 *                           50711 CFALayout
 *                           50713 BlackLevelRepeatDim
 *                           50714 BlackLevel
 *                           50717 WhiteLevel
 *                           50721 ColorMatrix1
 *                           50728 AsShotNeutral
 *                           50829 ActiveArea
 * Nothing outside this set is read in the first wave; unknown tags
 * are skipped by the decoder, never guessed at.
 *
 * ERROR SHAPE (the DngSequenceException precedent — the PreviewErrors
 * vocabulary, DESIGN.md's "never a bare failed" rule): POLICY
 * rejections throw [DngFileException] (stage + cause + recovery).
 * The stage tokens mirror the pipeline stages the facts feed:
 * "header" (a file that is not a TIFF/DNG at all), "tags" (a required
 * tag absent or unusable) and "pixels" (a strip layout that cannot
 * address the frame). A decode-stage failure is the DECODER's error
 * to report — this model stops at the metadata contract.
 */

/** TIFF data types (the DNG spec's tag-type table; DngWriter.cpp's set). */
object TiffTypes {
    const val BYTE = 1
    const val ASCII = 2
    const val SHORT = 3
    const val LONG = 4
    const val RATIONAL = 5
    const val SRATIONAL = 10

    /** The byte width of a type, 0 for unknown types (a reader skips those). */
    fun sizeOf(type: Int): Int = when (type) {
        BYTE, ASCII -> 1
        SHORT -> 2
        LONG -> 4
        RATIONAL, SRATIONAL -> 8
        else -> 0
    }
}

/** TIFF Compression values the first wave reads (DNG_SOURCE_PLAN.md 2.4). */
object DngCompression {
    /** Uncompressed — the app's own writer's variant, and CinemaDNG's most common. */
    const val NONE = 1
    /** Lossless JPEG — the DNG spec's standard compressed variant (a later wave). */
    const val JPEG_LOSSLESS = 7

    /** The values the first wave accepts; everything else is an honest rejection. */
    val SUPPORTED = setOf(NONE)
}

/** TIFF PhotometricInterpretation values (DngWriter.cpp's two). */
object DngPhotometric {
    /** CFA (Bayer) — the sensor-pattern variant (SamplesPerPixel 1). */
    const val CFA = 32803
    /** LinearRaw — demosaiced RGB (SamplesPerPixel 3), the app's own Linear DNG. */
    const val LINEAR_RAW = 34892

    val SUPPORTED = setOf(CFA, LINEAR_RAW)
}

/**
 * The 2x2 CFA pattern — core's OWN enum (rawedit::CfaPattern,
 * Source.h:27-32), the four arrangements the develop's demosaic
 * knows. NOT the CFAPattern tag's 0/1/2 color codes: [DngCfa] holds
 * the PATTERN, and [DngCfa.codes] is its expansion into the tag's
 * per-site codes — the two representations are this model's mapping
 * to keep (the decoder reads the tag, the adapter hands core the
 * enum ordinal).
 */
enum class DngCfa(
    /** The CFAPattern tag codes (0=R, 1=G, 2=B) in 2x2 scan order — DngWriter.cpp cfaCodes verbatim. */
    val codes: IntArray,
) {
    RGGB(intArrayOf(0, 1, 1, 2)),
    GRBG(intArrayOf(1, 0, 2, 1)),
    GBRG(intArrayOf(1, 2, 0, 1)),
    BGGR(intArrayOf(2, 1, 1, 0));

    /** The rawedit::CfaPattern ordinal core's FrameInfo.cfa carries (RGGB=0, GRBG=1, GBRG=2, BGGR=3). */
    val coreOrdinal: Int get() = ordinal

    /**
     * The color code at a 2x2 cell position (cellY*2 + cellX — the
     * cellSlot order Develop.cpp indexes black[4] by).
     */
    fun codeAt(cellY: Int, cellX: Int): Int = codes[cellY * 2 + cellX]

    companion object {
        /**
         * The pattern a CFAPattern tag's codes spell, or null when the
         * codes are not one of the four arrangements the develop's
         * demosaic knows (the pattern enum's rejection of unknown
         * values — a reader must refuse what it cannot honestly map).
         */
        fun fromCodes(codes: IntArray): DngCfa? {
            if (codes.size != 4) return null
            return entries.firstOrNull { it.codes.contentEquals(codes) }
        }
    }
}

/** TIFF Orientation tag values (DngWriter.cpp tiffOrientation's range). */
enum class DngOrientation(
    /** The orientation in degrees CW to upright — FrameInfo.orientationDeg's vocabulary. */
    val degreesCw: Int,
) {
    NORMAL(0),
    ROTATE_90(90),
    ROTATE_180(180),
    ROTATE_270(270);

    companion object {
        /**
         * The orientation a tag value spells, or null for the values
         * outside the writer's set (2/4 mirrors and 5/7 mixed cases —
         * the reader rejects them honestly rather than guessing a
         * rotation for a mirror).
         */
        fun fromTag(tagValue: Int): DngOrientation? = when (tagValue) {
            1 -> NORMAL
            6 -> ROTATE_90
            3 -> ROTATE_180
            8 -> ROTATE_270
            else -> null
        }
    }
}

/** The active area in sensor coordinates (the ActiveArea tag's top/left/bottom/right). */
data class DngActiveArea(
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
) {
    /** The width the area covers. */
    val width: Int get() = right - left

    /** The height the area covers. */
    val height: Int get() = bottom - top

    companion object {
        /** The full-frame area of a width x height image (the absent-tag default). */
        fun fullFrame(width: Int, height: Int) =
            DngActiveArea(top = 0, left = 0, bottom = height, right = width)
    }
}

/**
 * A POLICY rejection of the single-DNG model (the DngSequenceException
 * shape: stage + cause + recovery — never a bare "failed"). Stage
 * tokens: [DngFile.STAGE_HEADER] (not a TIFF/DNG), [DngFile.STAGE_TAGS]
 * (a required tag absent or unusable), [DngFile.STAGE_PIXELS] (a strip
 * layout that cannot address the frame).
 */
class DngFileException(
    val stage: String,
    val causeText: String,
    val recovery: String,
    val detail: String? = null,
) : Exception(DngFile.message(stage, causeText, recovery, detail))

/**
 * The pure single-DNG metadata model's API: the TIFF/IFD parse MATH
 * (pure, synthetic-buffer-testable), the tag constants, and the
 * validation rules that turn raw tag values into [DngFileMetadata].
 * All of it is JVM-testable without the native lib, without Android,
 * and without the decoder.
 */
object DngFile {

    // Stage tokens (the DngSequenceException precedent).
    const val STAGE_HEADER = "header"
    const val STAGE_TAGS = "tags"
    const val STAGE_PIXELS = "pixels"

    // ---- the tag constants (the subset's own numbers) ----

    const val TAG_NEW_SUBFILE_TYPE = 254
    const val TAG_IMAGE_WIDTH = 256
    const val TAG_IMAGE_LENGTH = 257
    const val TAG_BITS_PER_SAMPLE = 258
    const val TAG_COMPRESSION = 259
    const val TAG_PHOTOMETRIC = 262
    const val TAG_IMAGE_DESCRIPTION = 270
    const val TAG_STRIP_OFFSETS = 273
    const val TAG_ORIENTATION = 274
    const val TAG_SAMPLES_PER_PIXEL = 277
    const val TAG_ROWS_PER_STRIP = 278
    const val TAG_STRIP_BYTE_COUNTS = 279
    const val TAG_CFA_REPEAT_PATTERN_DIM = 33421
    const val TAG_CFA_PATTERN = 33422
    const val TAG_DNG_VERSION = 50706
    const val TAG_CFA_PLANE_COLOR = 50710
    const val TAG_CFA_LAYOUT = 50711
    const val TAG_BLACK_LEVEL_REPEAT_DIM = 50713
    const val TAG_BLACK_LEVEL = 50714
    const val TAG_WHITE_LEVEL = 50717
    const val TAG_COLOR_MATRIX1 = 50721
    const val TAG_AS_SHOT_NEUTRAL = 50728
    const val TAG_ACTIVE_AREA = 50829

    /**
     * The tags a first-wave reader REQUIRED-minimally needs to address
     * pixels and fill the develop's contract — the honest minimum: a
     * file missing any of these cannot be decoded by ANY reader and is
     * rejected (stage "tags"). Everything else in the tag set has a
     * documented default (see [DngFileMetadata]).
     */
    val REQUIRED_TAGS = intArrayOf(
        TAG_IMAGE_WIDTH,
        TAG_IMAGE_LENGTH,
        TAG_BITS_PER_SAMPLE,
        TAG_COMPRESSION,
        TAG_PHOTOMETRIC,
        TAG_STRIP_OFFSETS,
        TAG_SAMPLES_PER_PIXEL,
        TAG_ROWS_PER_STRIP,
        TAG_STRIP_BYTE_COUNTS,
        TAG_DNG_VERSION,
    )

    /** The full first-wave tag set (the subset's exact numbers, for the decoder's skip-list). */
    val KNOWN_TAGS: Set<Int> = setOf(
        TAG_NEW_SUBFILE_TYPE, TAG_IMAGE_WIDTH, TAG_IMAGE_LENGTH,
        TAG_BITS_PER_SAMPLE, TAG_COMPRESSION, TAG_PHOTOMETRIC,
        TAG_IMAGE_DESCRIPTION, TAG_STRIP_OFFSETS, TAG_ORIENTATION,
        TAG_SAMPLES_PER_PIXEL, TAG_ROWS_PER_STRIP, TAG_STRIP_BYTE_COUNTS,
        TAG_CFA_REPEAT_PATTERN_DIM, TAG_CFA_PATTERN, TAG_DNG_VERSION,
        TAG_CFA_PLANE_COLOR, TAG_CFA_LAYOUT, TAG_BLACK_LEVEL_REPEAT_DIM,
        TAG_BLACK_LEVEL, TAG_WHITE_LEVEL, TAG_COLOR_MATRIX1,
        TAG_AS_SHOT_NEUTRAL, TAG_ACTIVE_AREA,
    )

    // ---- 1. the TIFF header/IFD parse math (pure) ----

    /** The TIFF magic (both byte orders carry it). */
    const val TIFF_MAGIC = 42

    /** The fixed header size: order(2) + magic(2) + first-IFD offset(4). */
    const val HEADER_SIZE = 8

    /** One IFD entry's serialized size: tag(2) + type(2) + count(4) + value/offset(4). */
    const val IFD_ENTRY_SIZE = 12

    /**
     * The byte order a TIFF header spells, or null when the file is
     * not a TIFF at all. Pure header math: "II" (0x4949) is
     * little-endian, "MM" (0x4D4D) is big-endian — anything else (a
     * JPEG's FFD8, a CR3's FTYP box, random bytes) is not ours to
     * read and the caller reports stage "header".
     */
    fun byteOrderOf(bytes: ByteArray): Boolean? {
        if (bytes.size < 2) return null
        return when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> false
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> true
            else -> null
        }
    }

    /**
     * The first IFD's absolute offset the header carries (read in the
     * header's own byte order), or null when the header is short or
     * the magic is wrong. The DNG spec (and the app's writer) put the
     * IFD at 8; the math does not assume it — the header's word is
     * taken (a file with the IFD elsewhere is still a valid TIFF).
     */
    fun firstIfdOffset(bytes: ByteArray): Int? {
        val bigEndian = byteOrderOf(bytes) ?: return null
        if (bytes.size < HEADER_SIZE) return null
        if (readU16(bytes, 2, bigEndian) != TIFF_MAGIC) return null
        return readU32(bytes, 4, bigEndian)
    }

    /**
     * The entry count of the IFD at [ifdOffset], or null when the
     * offset does not address a readable count word. The count word
     * precedes the entries; an IFD of n entries occupies
     * 2 + 12*n + 4 bytes (count + entries + next-IFD offset).
     */
    fun ifdEntryCount(bytes: ByteArray, ifdOffset: Int, bigEndian: Boolean): Int? {
        if (ifdOffset < 0 || ifdOffset + 2 > bytes.size) return null
        return readU16(bytes, ifdOffset, bigEndian)
    }

    /**
     * The absolute file offset of IFD entry [index] (0-based): the
     * count word plus index*12. Pure arithmetic — the decoder reads
     * the 12 bytes there with [readEntry].
     */
    fun entryOffset(ifdOffset: Int, index: Int): Int =
        ifdOffset + 2 + index * IFD_ENTRY_SIZE

    /**
     * The absolute offset of the next IFD (0 = none), or null when
     * the offset does not address a readable word. The word sits
     * after the last entry.
     */
    fun nextIfdOffset(bytes: ByteArray, ifdOffset: Int, entryCount: Int, bigEndian: Boolean): Int? {
        val at = ifdOffset + 2 + entryCount * IFD_ENTRY_SIZE
        if (at < 0 || at + 4 > bytes.size) return null
        return readU32(bytes, at, bigEndian)
    }

    /**
     * The byte length of an entry's value: count * typeSize, in LONG
     * arithmetic (an overflowing count is caught by the caller's
     * bounds check, not silently wrapped). A value of at most 4
     * bytes TRAVELS INLINE in the entry's value field; a longer one
     * is an absolute file offset — the TIFF rule DngWriter.cpp's
     * `count*typeSize <= 4` encodes on the write side.
     */
    fun valueLength(type: Int, count: Long): Long =
        count * TiffTypes.sizeOf(type).toLong()

    /** Whether an entry's value travels inline (fits the 4-byte value field). */
    fun isInline(type: Int, count: Long): Boolean = valueLength(type, count) <= 4

    /**
     * The absolute offset an entry's value reads from: the entry's
     * own offset plus 8 when inline, else the value field read as a
     * LONG (an external value's offset is ABSOLUTE from the file
     * start — the TIFF rule; never relative to the IFD).
     */
    fun valueOffset(bytes: ByteArray, entryOffset: Int, type: Int, count: Long, bigEndian: Boolean): Int? {
        if (entryOffset < 0 || entryOffset + IFD_ENTRY_SIZE > bytes.size) return null
        return if (isInline(type, count)) {
            entryOffset + 8
        } else {
            readU32(bytes, entryOffset + 8, bigEndian)
        }
    }

    // ---- the little/big-endian readers (pure) ----

    /** Reads an unsigned 16-bit word at [offset] in the file's byte order. */
    fun readU16(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
        val b0 = bytes[offset].toInt() and 0xff
        val b1 = bytes[offset + 1].toInt() and 0xff
        return if (bigEndian) (b0 shl 8) or b1 else (b1 shl 8) or b0
    }

    /** Reads an unsigned 32-bit word at [offset] in the file's byte order (as a signed Int carrier). */
    fun readU32(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int {
        val w0 = readU16(bytes, offset, bigEndian)
        val w1 = readU16(bytes, offset + 2, bigEndian)
        return if (bigEndian) (w0 shl 16) or w1 else (w1 shl 16) or w0
    }

    /** Reads a signed 32-bit word (the SRATIONAL numerator's carrier). */
    fun readS32(bytes: ByteArray, offset: Int, bigEndian: Boolean): Int =
        readU32(bytes, offset, bigEndian)

    /**
     * Reads a TIFF RATIONAL/SRATIONAL at [offset] as a Double:
     * numerator/denominator, IEEE-754 division. A ZERO denominator is
     * the DNG spec's "invalid" — returned as NaN (the validation
     * rules reject it; never silently 0 or 1).
     */
    fun readRational(bytes: ByteArray, offset: Int, bigEndian: Boolean, signed: Boolean): Double {
        val num = if (signed) readS32(bytes, offset, bigEndian).toDouble()
        else readU32(bytes, offset, bigEndian).toLong().toDouble()
        val den = readU32(bytes, offset + 4, bigEndian).toLong().toDouble()
        return if (den == 0.0) Double.NaN else num / den
    }

    // ---- 2. the validation rules (raw tag values -> facts) ----

    /**
     * The bit depths the first wave reads. The app's own writer
     * emits 16; DNG files from real cameras carry 10/12/14/16.
     * Everything else is an honest rejection.
     */
    val SUPPORTED_BIT_DEPTHS = intArrayOf(8, 10, 12, 14, 16)

    /** Whether [bitDepth] is one the first wave reads. */
    fun isSupportedBitDepth(bitDepth: Int): Boolean =
        SUPPORTED_BIT_DEPTHS.contains(bitDepth)

    /**
     * The CFA pattern the CFAPattern/CFARepeatPatternDim tags spell,
     * or a rejection. The repeat dim MUST be 2x2 (core's demosaic
     * knows the four 2x2 arrangements only — a 2x4 or 6x6 CFA is
     * refused rather than misread), and the 4 codes MUST be one of
     * the four arrangements ([DngCfa.fromCodes] — unknown codes are
     * rejected, never guessed).
     */
    fun cfaPatternOf(repeatDimW: Int, repeatDimH: Int, codes: IntArray): DngCfa {
        if (repeatDimW != 2 || repeatDimH != 2) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText = "CFA repeat pattern ${repeatDimW}x$repeatDimH is not the 2x2 cell the demosaic knows",
                recovery = "use a 2x2 Bayer DNG, or export one from the camera's app",
            )
        }
        return DngCfa.fromCodes(codes) ?: throw DngFileException(
            stage = STAGE_TAGS,
            causeText = "CFA pattern codes ${codes.joinToString(",")} are not one of the four Bayer arrangements",
            recovery = "use a standard Bayer DNG, or export one from the camera's app",
        )
    }

    /**
     * The orientation the tag spells, or a rejection: 1/6/3/8 map
     * (DngWriter.cpp tiffOrientation's range); the mirror values
     * (2/4/5/7) and anything else are refused honestly — a mirror is
     * not a rotation and the develop has no flip stage to honor it.
     */
    fun orientationOf(tagValue: Int): DngOrientation =
        DngOrientation.fromTag(tagValue) ?: throw DngFileException(
            stage = STAGE_TAGS,
            causeText = "orientation $tagValue is not one of the four rotations the pipeline honors",
            recovery = "re-export the photo with its rotation baked in, or flip it in a photo editor first",
        )

    /**
     * The active area the tag spells, validated against the frame:
     * the area must sit INSIDE the frame (0 <= top <= bottom <=
     * height, 0 <= left <= right <= width) and be non-empty — the
     * active-area-inside-frame invariant. An ABSENT tag is the
     * caller's signal to use [DngActiveArea.fullFrame] (the writer's
     * own convention: the full frame when the tag is missing).
     */
    fun activeAreaOf(top: Int, left: Int, bottom: Int, right: Int, width: Int, height: Int): DngActiveArea {
        val inside = top in 0..height && bottom in top..height &&
            left in 0..width && right in left..width
        if (!inside || bottom == top || right == left) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText =
                    "active area (top $top, left $left, bottom $bottom, right $right) " +
                    "does not sit inside the ${width}x$height frame",
                recovery = "re-export the DNG without a custom active area, or use the full frame",
            )
        }
        return DngActiveArea(top, left, bottom, right)
    }

    /**
     * The white level the tag spells, validated: a usable level is a
     * FINITE, POSITIVE value at or under the bit depth's ceiling
     * (2^bits - 1). A NaN/negative/zero/overflowing level is an
     * honest rejection — a wrong white level would silently corrupt
     * every normalization the develop performs.
     */
    fun whiteLevelOf(value: Double, bitDepth: Int): Int {
        if (!isSupportedBitDepth(bitDepth)) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText = "bit depth $bitDepth is not one the first wave reads",
                recovery = "use a 8/10/12/14/16-bit DNG",
            )
        }
        if (!value.isFinite() || value <= 0.0) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText = "white level $value is not a usable level",
                recovery = "re-export the DNG from its source app, or pick the white level manually",
            )
        }
        val ceil = ((1L shl bitDepth) - 1).toDouble()
        if (value > ceil) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText = "white level $value exceeds the ${bitDepth}-bit ceiling $ceil",
                recovery = "re-export the DNG from its source app, or pick the white level manually",
            )
        }
        return value.toInt()
    }

    /**
     * The black levels the tag spells, validated per the DNG spec's
     * singularity rules: the tag carries either ONE value (applies to
     * every CFA site) or repeatDimW*repeatDimH values (one per site,
     * in cellY*2+cellX order — the cellSlot order Develop.cpp
     * indexes black[4] by). Any other count is a rejection. A NaN or
     * negative level is a rejection; a level at or above the white
     * level is a rejection (range would be empty or inverted).
     */
    fun blackLevelsOf(values: DoubleArray, repeatDimW: Int, repeatDimH: Int, whiteLevel: Int): IntArray {
        val cellCount = repeatDimW * repeatDimH
        if (values.size != 1 && values.size != cellCount) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText =
                    "black level carries ${values.size} values for a " +
                    "${repeatDimW}x$repeatDimH cell — the DNG spec allows 1 or $cellCount",
                recovery = "re-export the DNG from its source app",
            )
        }
        val expanded = if (values.size == 1) IntArray(cellCount) { values[0].toInt() }
        else IntArray(cellCount) { values[it].toInt() }
        for ((i, v) in expanded.withIndex()) {
            if (!values[if (values.size == 1) 0 else i].isFinite() || v < 0 || v >= whiteLevel) {
                throw DngFileException(
                    stage = STAGE_TAGS,
                    causeText =
                        "black level ${if (values.size == 1) "" else "site $i "}" +
                        "value ${if (values.size == 1) values[0] else values[i]} " +
                        "is not usable against white level $whiteLevel",
                    recovery = "re-export the DNG from its source app, or pick the black level manually",
                )
            }
        }
        return expanded
    }

    /**
     * The white-balance gains the AsShotNeutral tag spells, as the
     * rggb-ordered floats the develop consumes. DNG semantics (the
     * Source.h RawMeta contract): AsShotNeutral[3] is r-g-b, and
     * wbGains is its RECIPROCAL (Develop.cpp: "WB == division by
     * asShotNeutral (reciprocal of wbGains)"). Validation: every
     * component must be FINITE and POSITIVE (a zero/NaN neutral
     * would divide by zero or poison the gains — an absent tag is the
     * caller's signal to use 1,1,1, never a guessed value).
     */
    fun wbGainsOf(asShotNeutral: DoubleArray): DoubleArray {
        if (asShotNeutral.size != 3) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText = "as-shot neutral carries ${asShotNeutral.size} values, expected 3 (r, g, b)",
                recovery = "re-export the DNG from its source app",
            )
        }
        for (v in asShotNeutral) {
            if (!v.isFinite() || v <= 0.0) {
                throw DngFileException(
                    stage = STAGE_TAGS,
                    causeText = "as-shot neutral value $v is not usable (each must be finite and positive)",
                    recovery = "re-export the DNG from its source app, or set the white balance manually",
                )
            }
        }
        return doubleArrayOf(
            1.0 / asShotNeutral[0],
            1.0 / asShotNeutral[1],
            1.0 / asShotNeutral[2],
        )
    }

    /**
     * The color matrix the ColorMatrix1 tag spells, validated: 9
     * SRATIONAL components, each FINITE (a NaN/inf component — a zero
     * denominator — would poison every color the develop produces).
     * Direction (Source.h): XYZ -> native camera RGB, row-major —
     * the adapter fills RawMeta.colorMatrix verbatim; it is NEVER
     * multiplied onto camera RGB.
     */
    fun colorMatrixOf(components: DoubleArray): DoubleArray {
        if (components.size != 9) {
            throw DngFileException(
                stage = STAGE_TAGS,
                causeText = "color matrix carries ${components.size} components, expected 9 (3x3 row-major)",
                recovery = "re-export the DNG from its source app",
            )
        }
        for (v in components) {
            if (!v.isFinite()) {
                throw DngFileException(
                    stage = STAGE_TAGS,
                    causeText = "color matrix component $v is not a usable number",
                    recovery = "re-export the DNG from its source app",
                )
            }
        }
        return components.copyOf()
    }

    /**
     * The strip layout the pixel-addressing tags spell, validated
     * against the frame: one strip covering [rowsPerStrip] rows (the
     * app's writer emits exactly one full-frame strip; multi-strip
     * files are legal TIFF but a FIRST-WAVE refusal — the honest
     * subset), and the strip's byte count must EXACTLY fit the
     * frame: width * rows * samplesPerPixel * bytesPerSample. A
     * mismatch is stage "pixels" — IN-17's overflow/offset/frame-count
     * consistency check, before the file is opened as a source.
     */
    fun stripLayoutOf(
        width: Int,
        height: Int,
        bitsPerSample: Int,
        samplesPerPixel: Int,
        rowsPerStrip: Int,
        stripByteCount: Long,
    ) {
        if (rowsPerStrip != height) {
            throw DngFileException(
                stage = STAGE_PIXELS,
                causeText =
                    "rows per strip $rowsPerStrip does not cover the $height-row frame in one strip " +
                    "(multi-strip files are outside the first wave)",
                recovery = "re-export the DNG as a single-strip file",
            )
        }
        val bytesPerSample = (bitsPerSample + 7) / 8
        if (bytesPerSample <= 0) {
            throw DngFileException(
                stage = STAGE_PIXELS,
                causeText = "bit depth $bitsPerSample cannot address whole bytes",
                recovery = "use a 8/10/12/14/16-bit DNG",
            )
        }
        val expected = width.toLong() * height * samplesPerPixel * bytesPerSample
        if (stripByteCount != expected) {
            throw DngFileException(
                stage = STAGE_PIXELS,
                causeText =
                    "strip byte count $stripByteCount does not match the frame " +
                    "(${width}x${height}x$samplesPerPixel at $bitsPerSample bits = $expected bytes)",
                recovery = "re-export the DNG from its source app — the file's pixel data is inconsistent",
            )
        }
    }

    /**
     * The one-line message, the DngSequenceException/PreviewErrors shape:
     * "DNG rejected (stage: tags): ... — recovery: ... [detail]".
     */
    fun message(stage: String, causeText: String, recovery: String, detail: String? = null): String {
        val base = "DNG rejected (stage: $stage): $causeText — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }
}

/**
 * A single DNG file's validated metadata — the record the future
 * decoder adapter fills and the develop pipeline consumes. Every
 * field is one of the contract's own (Source.h RawMeta/FrameInfo);
 * the KDoc names the exact mapping. ABSENT tags are their documented
 * defaults (the RawMeta convention: unknown, never a guess).
 */
data class DngFileMetadata(
    /** ImageWidth (256) — the CFA width in pixels (FrameInfo.width). */
    val width: Int,
    /** ImageLength (257) — the CFA height in pixels (FrameInfo.height). */
    val height: Int,
    /** BitsPerSample (258) — FrameInfo.bitDepth (the writer's 16, cameras' 10-16). */
    val bitDepth: Int,
    /** Compression (259) — the variant the pixels are stored in (first wave: [DngCompression.NONE]). */
    val compression: Int,
    /** PhotometricInterpretation (262) — CFA or LinearRaw (DngWriter.cpp's two). */
    val photometric: Int,
    /** SamplesPerPixel (277) — 1 for CFA, 3 for LinearRaw. */
    val samplesPerPixel: Int,
    /** Orientation (274) — the validated rotation (FrameInfo.orientationDeg's degreesCw). */
    val orientation: DngOrientation,
    /** StripOffsets (273) — the pixel data's absolute file offset (the decoder reads there). */
    val stripOffset: Long,
    /** StripByteCounts (279) — the pixel data's byte length (IN-17's consistency check applied). */
    val stripByteCount: Long,
    /** CFAPattern (33422) + CFARepeatPatternDim (33421) — the validated 2x2 pattern (CFA files only). */
    val cfa: DngCfa?,
    /** BlackLevel (50714) + BlackLevelRepeatDim (50713) — per-site, cellSlot order (RawMeta.black[4]); null = absent, the caller uses 0s. */
    val blackLevels: IntArray?,
    /** WhiteLevel (50717) — RawMeta.white; null = absent, the caller uses the bit ceiling. */
    val whiteLevel: Int?,
    /** AsShotNeutral (50728) — RawMeta.asShotNeutral[3] r-g-b (the gains are its reciprocal). */
    val asShotNeutral: DoubleArray,
    /** ColorMatrix1 (50721) — RawMeta.colorMatrix[9], XYZ -> native camera RGB, row-major. */
    val colorMatrix: DoubleArray,
    /** ActiveArea (50829) — the sensor-area crop (FrameInfo.activeArea); null = absent, the full frame. */
    val activeArea: DngActiveArea?,
    /** ImageDescription (270) — verbatim (the Linear DNG stage record); blank = absent. */
    val imageDescription: String,
) {
    init {
        // The constructor is the model's LAST line of validation: the
        // factory functions catch policy problems with stage/cause/
        // recovery; these requires keep a directly-built record
        // self-consistent (the DngFrameTimeline.init precedent).
        require(width > 0 && height > 0) { "frame must be non-empty, got ${width}x$height" }
        require(DngFile.isSupportedBitDepth(bitDepth)) { "unsupported bit depth $bitDepth" }
        require(compression in DngCompression.SUPPORTED) { "unsupported compression $compression" }
        require(photometric in DngPhotometric.SUPPORTED) { "unsupported photometric $photometric" }
        val expectedSamples = if (photometric == DngPhotometric.LINEAR_RAW) 3 else 1
        require(samplesPerPixel == expectedSamples) {
            "photometric $photometric expects $expectedSamples samples per pixel, got $samplesPerPixel"
        }
        if (photometric == DngPhotometric.CFA) {
            requireNotNull(cfa) { "a CFA DNG must carry a validated pattern" }
        } else {
            require(cfa == null) { "a LinearRaw DNG carries no CFA pattern" }
        }
        require(asShotNeutral.size == 3) { "as-shot neutral must be r-g-b" }
        require(colorMatrix.size == 9) { "color matrix must be 3x3 row-major" }
        val area = activeArea
        if (area != null) {
            require(
                area.top in 0..height && area.bottom in area.top..height &&
                area.left in 0..width && area.right in area.left..width &&
                area.bottom > area.top && area.right > area.left,
            ) { "active area must sit inside the frame" }
        }
    }

    /** The WB gains the develop consumes — the reciprocal of asShotNeutral (RawMeta.wbGains). */
    val wbGains: DoubleArray
        get() = DngFile.wbGainsOf(asShotNeutral)

    /** The white level with the absent-tag default applied (the bit ceiling — RawMeta's convention). */
    val effectiveWhiteLevel: Int
        get() = whiteLevel ?: ((1L shl bitDepth) - 1).toInt()

    /** The black levels with the absent-tag default applied (0s — never a guessed sensor black). */
    val effectiveBlackLevels: IntArray
        get() = blackLevels ?: IntArray(4) { 0 }

    /** The active area with the absent-tag default applied (the full frame). */
    val effectiveActiveArea: DngActiveArea
        get() = activeArea ?: DngActiveArea.fullFrame(width, height)

    /**
     * The Linear DNG contract the app's own writer records: an
     * ImageDescription naming developLinear means the pixels are
     * ALREADY black-normalized, demosaiced and white-balanced — the
     * reader must not re-apply them (kLinearDngStages, DngWriter.cpp).
     */
    val isRitiLinearDng: Boolean
        get() = photometric == DngPhotometric.LINEAR_RAW &&
            imageDescription.contains("developLinear")
}
