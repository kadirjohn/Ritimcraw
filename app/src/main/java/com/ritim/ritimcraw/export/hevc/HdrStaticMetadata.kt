package com.ritim.ritimcraw.export.hevc

/**
 * THE HDR10 static metadata model — SMPTE ST 2086 + CTA-861.3 content
 * light levels, package E3 (night 2026-11-27, plan §7.6.4). Pure Kotlin,
 * deliberately Android-free (the same rule as [HevcProfile]): the
 * framework constants arrive as DATA, so the whole contract is
 * JVM-testable and the device leg turns [staticInfoBytes] into the real
 * `MediaFormat` "hdr-static-info" ByteBuffer.
 *
 * WHAT ST 2086 IS (the standard's own shape, no more): the MASTERING
 * DISPLAY's color volume — the R/G/B primaries and white point as CIE
 * 1931 xy chromaticities, the display's max and min luminance in
 * cd/m². It describes the display the content was mastered on, NOT the
 * content. CTA-861.3 (originally CEA-861.3, the HDR10 static metadata
 * set) adds the two CONTENT light levels, MaxCLL and MaxFALL, and fixes
 * the wire encoding both share:
 *  - chromaticities: 0.16 fixed point (unsigned 16-bit, 65536 = 1.0)
 *  - max luminance: 24.8 fixed point (unsigned 32-bit, 256 = 1 cd/m²)
 *  - min luminance: 18.14 fixed point (unsigned 32-bit, 16384 = 1 cd/m²)
 *  - MaxCLL / MaxFALL: unsigned 16-bit cd/m²; **0 means "unknown"**
 *  - the mastering display block is 24 bytes (R,G,B,WP pairs then the
 *    two luminances), the content light block is 4 bytes — and the
 *    CTA-861.3 static metadata blob Android's
 *    `MediaFormat.KEY_HDR_STATIC_INFO` ("hdr-static-info", the exact
 *    string verified against the installed android-34 jar) carries is
 *    those 28 bytes back to back.
 *
 * THE ENCODED CONSTANTS (the honesty table: "resmî belgeden alıntı,
 * uygulanışı doğrulandı" — quoted from the official document, the
 * application verified, a real encode NOT verified):
 *  - BT.2020 primaries (ITU-R BT.2020-2 Table 2): R(0.708, 0.292),
 *    G(0.170, 0.797), B(0.131, 0.046); D65 white (0.3127, 0.3290). The
 *    4-decimal values are the standard's own. Encoding them at 0.16
 *    fixed point rounds (46399, 19137) for R — but the pair every
 *    writer in this ecosystem (x265, ffmpeg, dolby tools, filmcraft's
 *    APV oracle) actually writes is **(46400, 19136)**, the nearest
 *    representable pair straddling both coordinates. [MasteringDisplay]
 *    carries ENCODED codes as fields, so the canonical instance
 *    [BT2020_D65] pins the ecosystem pair, never a re-derivation.
 *  - A 1000-nit mastering peak encodes 256000 (1000 x 256), 10 000-nit
 *    2560000; both exact in 24.8. The 18.14 min side is coarser: the
 *    common "0.0001 cd/m²" floor rounds to 2, but the industry (the
 *    Dolby master-display string "L(10000000,1)", filmcraft's oracle)
 *    writes code 1 (= 6.1e-5 cd/m²). [MasteringDisplay.MIN_CODE] pins 1.
 *  - MaxCLL: "the maximum content light level of any single sample
 *    (pixel) in the entire program" (CTA-861.3). MaxFALL: "the maximum
 *    frame-average light level" — the greatest per-frame average of the
 *    samples' light levels, an ARITHMETIC mean, gamma-weighted by
 *    nothing (the standard says "average of the light levels"). Both
 *    measured on the DECODED light in cd/m² (PQ EOTF applied — the
 *    ST 2084 constants live in the PQ curve, not here: this model's
 *    [measure] consumes light levels, the caller produces them).
 *
 * THE MEASUREMENT FUNCTION ([measure]) is the brief's pure synthetic
 * API: frames as pixel light-level arrays (nits in) -> MaxCLL/MaxFALL
 * (nits out). "Reported, never guessed" is the rule:
 *  - an EMPTY frame list, a frame with ZERO pixels, or a light level
 *    that is negative / NaN / infinite is a MEASUREMENT ERROR —
 *    IllegalArgumentException names it; the model never silently
 *    clamps a broken input into a plausible number.
 *  - rounding: half-up to the whole nit (Math.round), the u16 code's
 *    own precision; clipping: a MaxCLL/MaxFALL above 65535 clips to
 *    65535 (the code's ceiling — a >65535-nit pixel cannot exist under
 *    PQ's 10 000-nit domain, the clip is defensive, never a guess).
 *  - a legitimately black program measures MaxCLL 0 — and code 0 READS
 *    as "unknown" on the wire. The measurement still reports 0; the
 *    KDoc here is the whole story (the encoded 0 and the honest 0 are
 *    the same bytes; only the receiver's reading differs).
 *
 * WHAT IS NOT HERE (scope, the brief's own line): PQ signal decoding,
 * real encoders, the muxer's mdcv/clli boxes (the MP4 side is a later
 * package; the SEI shape below is the bitstream-side proof), dynamic
 * metadata (ST 2094-40 HDR10+ is a DIFFERENT standard, not "static" —
 * `KEY_HDR10_PLUS_INFO` is a different key with a different job).
 */
object HdrStaticMetadata {

    /** MediaFormat.KEY_HDR_STATIC_INFO's exact key string (android-34 jar, verified). */
    const val KEY_HDR_STATIC_INFO = "hdr-static-info"

    /** The CTA-861.3 static metadata blob's exact length: 24 (mdcv) + 4 (clli). */
    const val STATIC_INFO_BYTES = 28

    /** MaxCLL/MaxFALL code 0: the CTA-861.3 "unknown/not signalled" value. */
    const val CLL_UNKNOWN = 0

    /** The u16 code ceiling for MaxCLL/MaxFALL (the clipping rule's bound). */
    const val CLL_MAX_CODE = 65535

    /** The 0.16 fixed-point scale of every chromaticity code (1.0 = 65536). */
    const val CHROMA_SCALE = 65536

    /** The 24.8 fixed-point scale of max luminance (1 cd/m² = 256). */
    const val MAX_LUMA_SCALE = 256

    /** The 18.14 fixed-point scale of min luminance (1 cd/m² = 16384). */
    const val MIN_LUMA_SCALE = 16384

    /**
     * One CIE 1931 xy chromaticity, ALREADY ENCODED at 0.16 fixed point
     * (the wire's own precision, so the canonical values below are the
     * bytes the ecosystem writes, not a re-derivation). [asDouble] decodes
     * for display/log only.
     */
    data class CieChromaticity(val codeX: Int, val codeY: Int) {

        /** The decoded x (display/log; re-encoding may differ by 1 ULP). */
        val x: Double get() = codeX / CHROMA_SCALE.toDouble()

        /** The decoded y (display/log; re-encoding may differ by 1 ULP). */
        val y: Double get() = codeY / CHROMA_SCALE.toDouble()

        init {
            require(codeX in 0..65535 && codeY in 0..65535) {
                "chromaticity codes must fit u16: ($codeX, $codeY)"
            }
        }
    }

    /**
     * SMPTE ST 2086 mastering display metadata — the MASTERING DISPLAY's
     * color volume, encoded exactly as the wire carries it: three
     * primaries in R, G, B order, the white point, max luminance at 24.8
     * and min luminance at 18.14. Pure data; [mdcvBytes] serializes and
     * [MasteringDisplay.parse] reads back.
     */
    data class MasteringDisplay(
        val primaries: List<CieChromaticity>,
        val whitePoint: CieChromaticity,
        val maxLuminanceCode: Int,
        val minLuminanceCode: Int,
    ) {
        init {
            require(primaries.size == 3) { "ST 2086 carries exactly 3 primaries (R, G, B), got ${primaries.size}" }
            require(maxLuminanceCode > 0) { "ST 2086 max luminance is a display peak; 0 is not one" }
            require(maxLuminanceCode <= 0xFFFF_FF) { "max luminance code must fit u32: $maxLuminanceCode" }
            require(minLuminanceCode in 0..0xFFFF_FF) { "min luminance code must fit u32: $minLuminanceCode" }
            require(minLuminanceCode < maxLuminanceCode) {
                "min luminance ($minLuminanceNits cd/m²) must be below max ($maxLuminanceNits cd/m²)"
            }
        }

        /** The display's peak in cd/m² (24.8 decode). */
        val maxLuminanceNits: Double get() = maxLuminanceCode / MAX_LUMA_SCALE.toDouble()

        /** The display's floor in cd/m² (18.14 decode). */
        val minLuminanceNits: Double get() = minLuminanceCode / MIN_LUMA_SCALE.toDouble()

        /**
         * The 24-byte mastering display block, big-endian, the order every
         * carrier uses (the mdcv box, the CTA-861.3 blob, the ST 2086 SEI
         * payload): R(x,y), G(x,y), B(x,y), WP(x,y), maxLuminance u32,
         * minLuminance u32. Layout verified against filmcraft's APV
         * metadata PBU writer (RFC 9924 §8.2.3 uses the same 24 bytes).
         */
        fun mdcvBytes(): ByteArray {
            val out = ByteArray(24)
            var i = 0
            for (p in primaries) {
                out[i++] = (p.codeX ushr 8).toByte(); out[i++] = p.codeX.toByte()
                out[i++] = (p.codeY ushr 8).toByte(); out[i++] = p.codeY.toByte()
            }
            out[i++] = (whitePoint.codeX ushr 8).toByte(); out[i++] = whitePoint.codeX.toByte()
            out[i++] = (whitePoint.codeY ushr 8).toByte(); out[i++] = whitePoint.codeY.toByte()
            writeU32(out, i, maxLuminanceCode); i += 4
            writeU32(out, i, minLuminanceCode)
            return out
        }

        companion object {
            /**
             * Parse the 24-byte mastering display block (the mdcv box body,
             * the blob's first half, the SEI payload — one layout, three
             * carriers). A wrong length is an error, never a guess.
             */
            fun parse(bytes: ByteArray): MasteringDisplay {
                require(bytes.size == 24) { "mdcv block is 24 bytes, got ${bytes.size}" }
                fun u16(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val md = MasteringDisplay(
                    primaries = (0..2).map { CieChromaticity(u16(it * 4), u16(it * 4 + 2)) },
                    whitePoint = CieChromaticity(u16(12), u16(14)),
                    maxLuminanceCode = readU32(bytes, 16),
                    minLuminanceCode = readU32(bytes, 20),
                )
                return md
            }
        }
    }

    /**
     * CTA-861.3 content light level: MaxCLL / MaxFALL in whole cd/m²,
     * u16 on the wire. [CLL_UNKNOWN] (0) means "not signalled" — a
     * measured honest 0 encodes the same bytes; see the object KDoc.
     */
    data class ContentLightLevel(val maxCll: Int, val maxFall: Int) {
        init {
            require(maxCll in 0..CLL_MAX_CODE) { "MaxCLL must fit u16: $maxCll" }
            require(maxFall in 0..CLL_MAX_CODE) { "MaxFALL must fit u16: $maxFall" }
        }

        /** The 4-byte content light block: MaxCLL u16, MaxFALL u16, big-endian. */
        fun clliBytes(): ByteArray = byteArrayOf(
            (maxCll ushr 8).toByte(), maxCll.toByte(),
            (maxFall ushr 8).toByte(), maxFall.toByte(),
        )

        companion object {
            /** Parse the 4-byte content light block; wrong length is an error. */
            fun parse(bytes: ByteArray): ContentLightLevel {
                require(bytes.size == 4) { "clli block is 4 bytes, got ${bytes.size}" }
                fun u16(i: Int) = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                return ContentLightLevel(u16(0), u16(2))
            }
        }
    }

    // ---- the canonical instances (industry-quoted, application verified) ----

    /**
     * THE canonical mastering display for HDR10 from a BT.2020 pipeline
     * (the value every writer carries for a BT.2020/D65 grade): primaries
     * (46400, 19136), (11141, 52232), (8585, 3015), D65 white
     * (20493, 21561) — the ecosystem's canonical pair set (x265, ffmpeg,
     * Dolby tools, filmcraft's APV oracle; the 4-decimal standards'
     * values encode within 1 ULP of these) — peak 1000 cd/m² (code
     * 256000), floor code 1 (the industry "0.0001 cd/m²" intent).
     */
    val BT2020_D65_1000NITS = MasteringDisplay(
        primaries = listOf(
            CieChromaticity(46400, 19136),   // R (0.708, 0.292)
            CieChromaticity(11141, 52232),   // G (0.170, 0.797)
            CieChromaticity(8585, 3015),     // B (0.131, 0.046)
        ),
        whitePoint = CieChromaticity(20493, 21561),   // D65 (0.3127, 0.3290)
        maxLuminanceCode = 256000,          // 1000 cd/m², exact in 24.8
        minLuminanceCode = 1,               // the Dolby-string floor intent
    )

    /**
     * The mastering-display min-luminance code the ecosystem writes for
     * "0.0001 cd/m²" — 18.14 rounds to 2, the writers use 1; pinned here
     * so the canonical instance cannot drift from the writers.
     */
    const val MIN_LUMINANCE_INDUSTRY_CODE = 1

    // ---- encoding helpers (float -> code; the canonical instances never use them) ----

    /** Encode a chromaticity coordinate at 0.16 fixed point (half-up). */
    fun encodeChromaticityCode(value: Double): Int {
        require(value in 0.0..1.0) { "chromaticities live in [0, 1], got $value" }
        return Math.round(value * CHROMA_SCALE).toInt().coerceAtMost(65535)
    }

    /** Encode a max luminance in cd/m² at 24.8 (exact for whole-nit peaks). */
    fun encodeMaxLuminanceCode(nits: Double): Int {
        require(nits > 0.0) { "max luminance is a display peak, > 0 cd/m²" }
        return Math.round(nits * MAX_LUMA_SCALE).toInt()
    }

    /**
     * Encode a min luminance in cd/m² at 18.14 — HALF-UP. For the common
     * 0.0001 cd/m² floor this yields 2, while the industry writes 1
     * ([MIN_LUMINANCE_INDUSTRY_CODE]); use the canonical instance for the
     * wire, this helper for arithmetic.
     */
    fun encodeMinLuminanceCode(nits: Double): Int {
        require(nits >= 0.0) { "min luminance cannot be negative" }
        return Math.round(nits * MIN_LUMA_SCALE).toInt()
    }

    // ---- the measurement (the brief's pure synthetic-frame API) ----

    /**
     * MaxCLL / MaxFALL from the program's frames. Input: one entry per
     * frame, each the frame's PIXEL LIGHT LEVELS in cd/m² (already
     * PQ-decoded — the caller's job). Rules, "reported, never guessed":
     *  - empty frame list, a frame with zero pixels, negative / NaN /
     *    infinite light: IllegalArgumentException (a measurement error
     *    names itself; nothing is clamped into a plausible number).
     *  - MaxCLL: the max over every pixel of every frame.
     *  - MaxFALL: the max over frames of each frame's ARITHMETIC mean
     *    light level (CTA-861.3's "average of the light levels" — no
     *    gamma weighting).
     *  - rounding: half-up to whole nits; clipping: 65535 max (the u16
     *    code's own ceiling — PQ tops out at 10 000, so the clip is
     *    defensive).
     */
    fun measure(frames: List<DoubleArray>): ContentLightLevel {
        require(frames.isNotEmpty()) { "no frames to measure: MaxCLL/MaxFALL cannot be guessed" }
        var maxCll = 0.0
        var maxFall = 0.0
        frames.forEachIndexed { frameIndex, frame ->
            require(frame.isNotEmpty()) { "frame $frameIndex has no pixels: a frame-average cannot be guessed" }
            var sum = 0.0
            for (i in frame.indices) {
                val nits = frame[i]
                require(!nits.isNaN() && !nits.isInfinite()) {
                    "frame $frameIndex pixel $i light level is not finite: $nits"
                }
                require(nits >= 0.0) { "frame $frameIndex pixel $i light level is negative: $nits" }
                if (nits > maxCll) maxCll = nits
                sum += nits
            }
            val average = sum / frame.size
            if (average > maxFall) maxFall = average
        }
        return ContentLightLevel(
            maxCll = roundToCode(maxCll),
            maxFall = roundToCode(maxFall),
        )
    }

    /** Half-up to the whole nit, clipped at the u16 code ceiling. */
    private fun roundToCode(nits: Double): Int =
        Math.round(nits).toInt().coerceIn(0, CLL_MAX_CODE)

    // ---- the wire blobs ----

    /**
     * THE CTA-861.3 static metadata blob the device leg puts in
     * `MediaFormat.KEY_HDR_STATIC_INFO`: the 24-byte mastering display
     * block followed by the 4-byte content light block — 28 bytes, no
     * headers, big-endian. ([HevcProfile.HDR10] is the profile whose
     * contract requires this key; HLG never sets it.)
     */
    fun staticInfoBytes(md: MasteringDisplay, cll: ContentLightLevel): ByteArray {
        val mdBytes = md.mdcvBytes()
        val cllBytes = cll.clliBytes()
        return mdBytes + cllBytes
    }

    /**
     * Parse the 28-byte CTA-861.3 static metadata blob back into its two
     * blocks. A wrong length is an error, never a guess.
     */
    fun parseStaticInfo(bytes: ByteArray): Pair<MasteringDisplay, ContentLightLevel> {
        require(bytes.size == STATIC_INFO_BYTES) {
            "CTA-861.3 static metadata blob is $STATIC_INFO_BYTES bytes, got ${bytes.size}"
        }
        return MasteringDisplay.parse(bytes.copyOfRange(0, 24)) to
            ContentLightLevel.parse(bytes.copyOfRange(24, 28))
    }

    /**
     * The SEI message form of the same two blocks (the bitstream side —
     * what an encoder writes into the stream): mastered-display
     * metadata as SEI payload type 137 (24-byte body), content light
     * level as type 144 (4-byte body), closed by the 0x80 rbsp trailing
     * byte. Byte layout verified against filmcraft's SEI test (same
     * expected sequence); a REAL encode writing these is not verified.
     */
    fun seiPayloadBytes(md: MasteringDisplay, cll: ContentLightLevel): ByteArray {
        val mdBytes = md.mdcvBytes()
        val cllBytes = cll.clliBytes()
        return byteArrayOf(137.toByte(), 24.toByte()) + mdBytes +
            byteArrayOf(144.toByte(), 4.toByte()) + cllBytes + byteArrayOf(0x80.toByte())
    }

    // ---- u32 helpers (Byte is signed on the JVM) ----

    private fun writeU32(out: ByteArray, at: Int, value: Int) {
        out[at] = (value ushr 24).toByte()
        out[at + 1] = (value ushr 16).toByte()
        out[at + 2] = (value ushr 8).toByte()
        out[at + 3] = value.toByte()
    }

    private fun readU32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
