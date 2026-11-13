package com.ritim.ritimcraw.data.metadata

import com.ritim.ritimcraw.data.SourceKind

/**
 * IN-13 Stage A — the observed facts the extraction pass gathers, as PURE
 * data. The device-gated probes ([MetadataExtractor]) fill these from the
 * real container/track reads; [MetadataBuilder] turns them into the honest
 * [MetadataRecord]. Splitting facts from assembly keeps every decision
 * JVM-testable: the probes are thin (they only read), the builder owns
 * every KNOWN/ESTIMATED/UNKNOWN verdict.
 *
 * THE HONESTY RULE (IN-13): a fact the probe could not read stays null —
 * the builder maps it to UNKNOWN, never a default. An estimated value
 * always carries its basis.
 */

/**
 * The MCRAW facts, from the existing native seams plus the one new
 * container-identity probe (the only genuinely-unreachable keys). Nulls =
 * the read failed or the key is absent — UNKNOWN, never a guess.
 *
 * @property frameCount container frame count (nativePreviewOpen).
 * @property width height first frame's recorded geometry (nativePreviewOpen).
 * @property bitDepth sample depth (MCRAW stores 16-bit samples; FrameInfo).
 * @property firstPtsUs lastPtsUs real frame timestamps (nativeFramePtsUs);
 *   the measured fps and duration derive from these.
 * @property audioSampleRateHz audioChannels container audio description
 *   (nativeAudioInfo; 0 = the clip has no audio).
 * @property identity the container's camera/lens keys
 *   (nativeContainerIdentity; null = the probe failed).
 */
data class McrawFacts(
    val frameCount: Int? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bitDepth: Int? = null,
    val firstPtsUs: Long? = null,
    val lastPtsUs: Long? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannels: Int? = null,
    val identity: ContainerIdentityFacts? = null,
)

/**
 * The container identity keys (nativeContainerIdentity's payload). Empty
 * string / 0 = the key is absent in THIS container — a fact about the
 * file, mapped to UNKNOWN, never a guess.
 */
data class ContainerIdentityFacts(
    val uniqueCameraModel: String? = null,
    val deviceModel: String? = null,
    val manufacturer: String? = null,
    val buildModel: String? = null,
    val apertureMm: Double? = null,
    val focalLengthMm: Double? = null,
)

/**
 * The encoded-track facts (MediaExtractor over the MP4). Nulls = the key
 * is absent from the track format — UNKNOWN, never a default (the real
 * MP4 fixture's transfer is untagged; its color range IS tagged).
 *
 * @property mime the video track's mime (the DV diagnosis compares it
 *   against the platform's DV mime).
 * @property width height the track's declared geometry.
 * @property frameCount sample count from the index walk.
 * @property firstPtsUs lastPtsUs first/last sample timestamps.
 * @property fpsNominal the track's declared frame rate
 *   (KEY_FRAME_RATE/KEY_CAPTURE_RATE; null = untagged).
 * @property colorStandard colorTransfer colorRange the track's COLOR_*
 *   tags as ints (null = the key is absent — never defaulted to 709:
 *   the decode path's isBt709 default is a DECODE decision, this is a
 *   REPORT of what the container says).
 * @property audioTrackPresent whether the container holds an audio track
 *   at all (a KNOWN present=false when the scan saw none; null = the scan
 *   itself failed — UNKNOWN, never a silent "no audio").
 * @property audioMime audioSampleRateHz audioChannels the first audio
 *   track's description (keys the track format may not carry stay null).
 */
data class EncodedFacts(
    val mime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val frameCount: Int? = null,
    val firstPtsUs: Long? = null,
    val lastPtsUs: Long? = null,
    val fpsNominal: Double? = null,
    val colorStandard: Int? = null,
    val colorTransfer: Int? = null,
    val colorRange: Int? = null,
    val audioTrackPresent: Boolean? = null,
    val audioMime: String? = null,
    val audioSampleRateHz: Int? = null,
    val audioChannels: Int? = null,
)

/**
 * Pure assembly: observed facts -> the honest [MetadataRecord]. Every
 * KNOWN/ESTIMATED/UNKNOWN verdict lives here (JVM-tested); the probes only
 * read. Nothing here touches a platform class.
 */
object MetadataBuilder {

    /** The honest record for one MCRAW source. */
    fun mcraw(sourceId: String, facts: McrawFacts): MetadataRecord {
        // Measured fps and duration derive from the REAL frame timestamps;
        // a clip whose timestamps could not be read keeps both UNKNOWN —
        // never a nominal-rate guess (the container carries no fps key at
        // all; verified against real recordings).
        val pts = measuredPts(facts.frameCount, facts.firstPtsUs, facts.lastPtsUs)
        return MetadataRecord(
            sourceId = sourceId,
            kind = SourceKind.MCRAW,
            durationUs = pts.durationUs,
            frameCount = knownOrUnknown(facts.frameCount),
            fpsNominal = Field.unknown(), // no container key exists
            fpsMeasured = pts.fpsMeasured,
            camera = mcrawCamera(facts.identity),
            lens = mcrawLens(facts.identity),
            resolution = resolutionOf(facts.width, facts.height),
            bitDepth = knownOrUnknown(facts.bitDepth),
            // RAW has no transfer semantics: primaries/transfer/matrix/range
            // describe an ENCODED signal; the record keeps them UNKNOWN
            // rather than inventing container values.
            primaries = Field.unknown(),
            transfer = Field.unknown(),
            matrix = Field.unknown(),
            range = Field.unknown(),
            audio = mcrawAudio(facts.audioSampleRateHz, facts.audioChannels),
            fileSize = Field.unknown(), // filled by the caller from the resolver
            fileIdentity = Field.unknown(), // filled from the repository fingerprint
            // RAW is not DV and not LOG — facts about the format.
            dolbyVision = DiagnosisRules.dolbyVision(
                SourceKind.MCRAW, trackRead = false, trackIsDolbyVision = false,
            ),
            logProfile = DiagnosisRules.logProfile(
                SourceKind.MCRAW, trackRead = false, encodedClassifiedLog = false,
            ),
            hdrOutputSupport = Capability.UNKNOWN, // probed separately, never inferred
        )
    }

    /** The honest record for one encoded (MP4) source. */
    fun encoded(sourceId: String, facts: EncodedFacts): MetadataRecord {
        val trackRead = facts.mime != null
        val pts = measuredPts(facts.frameCount, facts.firstPtsUs, facts.lastPtsUs)
        return MetadataRecord(
            sourceId = sourceId,
            kind = SourceKind.ENCODED,
            durationUs = pts.durationUs,
            frameCount = knownOrUnknown(facts.frameCount),
            fpsNominal = knownOrUnknown(facts.fpsNominal),
            fpsMeasured = pts.fpsMeasured,
            camera = Field.unknown(), // encoded containers carry no camera identity
            lens = Field.unknown(),
            resolution = resolutionOf(facts.width, facts.height),
            bitDepth = Field.unknown(), // not declared by the container
            primaries = colorStandardName(facts.colorStandard),
            transfer = colorTransferName(facts.colorTransfer),
            matrix = colorStandardName(facts.colorStandard),
            range = colorRangeName(facts.colorRange),
            audio = encodedAudio(facts),
            fileSize = Field.unknown(),
            fileIdentity = Field.unknown(),
            dolbyVision = DiagnosisRules.dolbyVision(
                SourceKind.ENCODED,
                trackRead = trackRead,
                trackIsDolbyVision = facts.mime == MetadataExtractor.DOLBY_VISION_MIME,
            ),
            logProfile = DiagnosisRules.logProfile(
                SourceKind.ENCODED,
                trackRead = trackRead,
                // The encoded path's existing classification: the develop
                // stage applies the Mi-Log decode to every encoded clip
                // this app opens (EncodedSource's decode pipeline), so a
                // read track IS the classified-LOG signal.
                encodedClassifiedLog = trackRead,
            ),
            hdrOutputSupport = Capability.UNKNOWN,
        )
    }

    /**
     * Measured fps + duration from the real timestamps. Duration is the
     * recorded span (last - first), KNOWN when both timestamps were read
     * (a single-frame clip has a REAL 0 µs span). The measured fps is
     * the mean frame rate the span implies — (frameCount - 1) intervals
     * in span microseconds — ESTIMATED with its basis (a measurement, not
     * a container key).
     *
     * HONESTY: the rate needs the interval COUNT. A single frame has no
     * rate, and a missing count makes any interval count a pure guess —
     * the rate stays UNKNOWN in both cases (the span is still reported).
     * Unreadable or non-increasing timestamps leave both UNKNOWN.
     */
    private fun measuredPts(frameCount: Int?, first: Long?, last: Long?): MeasuredPts {
        if (first == null || last == null || last < first) return MeasuredPts()
        val span = last - first
        // The interval count is what turns a span into a rate. A single
        // frame has none, and a missing count makes any interval count a
        // pure guess — the rate stays UNKNOWN in both cases; the span
        // itself was still measured.
        if (frameCount == null || frameCount <= 1) {
            return MeasuredPts(Field.known(span), Field.unknown())
        }
        // Non-increasing timestamps across multiple frames would
        // fabricate an infinite rate — both stay UNKNOWN.
        if (span <= 0) return MeasuredPts()
        val fps = Field.estimated(
            (frameCount - 1) * 1_000_000.0 / span,
            "(frames - 1) / (last pts - first pts) over the recorded span",
        )
        return MeasuredPts(Field.known(span), fps)
    }

    private data class MeasuredPts(
        val durationUs: Field<Long> = Field.unknown(),
        val fpsMeasured: Field<Double> = Field.unknown(),
    )

    /**
     * The camera string from the container identity keys, with the honest
     * fallback chain: uniqueCameraModel (empty on the owner's Xiaomi) ->
     * deviceSpecificProfile.deviceModel -> build.manufacturer build.model.
     * Every link names its source; no link invents a name.
     */
    private fun mcrawCamera(id: ContainerIdentityFacts?): Field<String> {
        if (id == null) return Field.unknown()
        val ucm = id.uniqueCameraModel
        if (!ucm.isNullOrBlank()) return Field.known(ucm)
        val dm = id.deviceModel
        if (!dm.isNullOrBlank()) return Field.known(dm)
        val man = id.manufacturer
        val bm = id.buildModel
        if (!man.isNullOrBlank() && !bm.isNullOrBlank()) return Field.known("$man $bm")
        if (!bm.isNullOrBlank()) return Field.known(bm)
        if (!man.isNullOrBlank()) return Field.known(man)
        return Field.unknown()
    }

    /**
     * The lens string: the 35mm-equivalent focal + the aperture, from the
     * container's own keys. The container carries no lens-name key — the
     * record never invents one.
     */
    private fun mcrawLens(id: ContainerIdentityFacts?): Field<String> {
        if (id == null) return Field.unknown()
        val focal = id.focalLengthMm
        val aperture = id.apertureMm
        val focalText = if (focal != null && focal > 0.0) {
            trimDouble(focal)
        } else {
            null
        }
        val apertureText = if (aperture != null && aperture > 0.0) {
            "f/" + trimDouble(aperture)
        } else {
            null
        }
        return when {
            focalText != null && apertureText != null ->
                Field.known("$focalText mm $apertureText")
            focalText != null -> Field.known("$focalText mm")
            apertureText != null -> Field.known(apertureText)
            else -> Field.unknown()
        }
    }

    private fun mcrawAudio(sampleRate: Int?, channels: Int?): Field<AudioSummary> {
        if (sampleRate == null || channels == null) return Field.unknown()
        if (sampleRate <= 0 || channels <= 0) {
            // 0 = the container says the clip HAS no audio — a KNOWN fact.
            return Field.known(AudioSummary(present = false, codec = "", channels = 0, sampleRateHz = 0))
        }
        return Field.known(
            AudioSummary(
                present = true,
                codec = "pcm", // MCRAW stores raw interleaved PCM chunks
                channels = channels,
                sampleRateHz = sampleRate,
            ),
        )
    }

    private fun encodedAudio(facts: EncodedFacts): Field<AudioSummary> {
        // The scan's own verdict first: a container the scan read with NO
        // audio track is a KNOWN present=false; an unreadable scan (null)
        // is UNKNOWN — never a silent "no audio".
        when (facts.audioTrackPresent) {
            false -> return Field.known(
                AudioSummary(present = false, codec = "", channels = 0, sampleRateHz = 0),
            )
            null -> return Field.unknown()
            true -> {}
        }
        val mime = facts.audioMime ?: return Field.unknown()
        return Field.known(
            AudioSummary(
                present = true,
                codec = mime,
                channels = facts.audioChannels ?: 0,
                sampleRateHz = facts.audioSampleRateHz ?: 0,
            ),
        )
    }

    private fun knownOrUnknown(v: Int?): Field<Int> =
        if (v == null) Field.unknown() else Field.known(v)

    private fun knownOrUnknown(v: Double?): Field<Double> =
        if (v == null) Field.unknown() else Field.known(v)

    private fun resolutionOf(width: Int?, height: Int?): Field<String> =
        if (width == null || height == null || width <= 0 || height <= 0) {
            Field.unknown()
        } else {
            Field.known("${width}x${height}")
        }

    /**
     * MediaFormat.COLOR_STANDARD_* ints -> honest names. An UNRECOGNIZED
     * int stays KNOWN with its raw value (the container DID tag it; the
     * name is this app's vocabulary limit, not the file's).
     */
    internal fun colorStandardName(v: Int?): Field<String> = when (v) {
        null -> Field.unknown()
        1 -> Field.known("bt709")
        2 -> Field.known("bt601-6-525") // COLOR_STANDARD_BT601_PAL
        4 -> Field.known("bt601-6-625") // COLOR_STANDARD_BT601_NTSC
        6 -> Field.known("bt2020")
        else -> Field.known("color-standard-$v")
    }

    internal fun colorTransferName(v: Int?): Field<String> = when (v) {
        null -> Field.unknown()
        3 -> Field.known("sdr-video") // COLOR_TRANSFER_SDR_VIDEO
        6 -> Field.known("st2084") // COLOR_TRANSFER_ST2084
        7 -> Field.known("hlg") // COLOR_TRANSFER_HLG
        else -> Field.known("color-transfer-$v")
    }

    internal fun colorRangeName(v: Int?): Field<String> = when (v) {
        null -> Field.unknown()
        1 -> Field.known("full") // COLOR_RANGE_FULL
        2 -> Field.known("limited") // COLOR_RANGE_LIMITED
        else -> Field.known("color-range-$v")
    }

    private fun trimDouble(v: Double): String {
        val s = String.format(java.util.Locale.US, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }
}
