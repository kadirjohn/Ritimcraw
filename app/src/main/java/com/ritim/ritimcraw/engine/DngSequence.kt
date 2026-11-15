package com.ritim.ritimcraw.engine

/**
 * The pure DNG-sequence model — night research 2026-11-16, package B4's
 * FIRST deliverable ("Doğal sayısal sıra, duplicate/gap, fps/PTS ve WAV
 * manifest modeli"): everything a CinemaDNG-style folder import needs to
 * know about a sequence of frame files BEFORE a single pixel is decoded.
 * Spec IN-09 — "DNG dizisini video kaynağı olarak grupla. Sıralama,
 * başlangıç numarası, kayıp kare, fps ve varsa WAV eşlemesi kayıtlı
 * olsun. Bir klasördeki bağımsız fotoğraflarla diziyi ayırt et."
 *
 * PURE AND DELIBERATELY DECODER-FREE. No Android imports, no file IO:
 * the caller hands in the display names a folder listing produced
 * (VideoTree's SAF query on the device) and the model hands back facts.
 * Nothing imports this model yet — the integrator wires it into the
 * source-kind seams when B3's real DNG decoder exists; the seams
 * (SourceKind, VideoTree, SourceRepository) are NOT touched here.
 *
 * THE FOUR HALVES:
 *
 * 1. THE NAME RULE (numeric ordering). A frame file's base name must END
 *    with an ASCII digit run; the run is the frame number, everything
 *    before it is the sequence prefix. That one rule covers the three
 *    conventions the research named — `clip_%06d.dng` (zero-padded, the
 *    CinemaDNG writer convention), `frame0012.dng` (the frameNNN shape)
 *    and `IMG-0042.dng` (plain-N) — and refuses what it cannot parse
 *    honestly (reported as unparsed, never a silent skip). Ordering is
 *    the PARSED NUMBER, never the file string: frame_2 plays before
 *    frame_10 (lexicographic order says the opposite), and zero-padding
 *    width is irrelevant to the slot — clip_1 and clip_000001 claim the
 *    SAME slot (see the duplicate policy).
 *
 * 2. THE GAP/DUPLICATE POLICY. A GAP (frame 4 missing between 3 and 5)
 *    is a REPORTED condition: the model returns the missing runs and the
 *    caller shows them — a gap never silently shortens a clip without a
 *    trace. A gap DOES NOT CONSUME TIMELINE TIME (the explicit CFR policy
 *    spec EX-14 demands): the frames that ARE present play back-to-back
 *    at the declared fps, so a gappy sequence is shorter than its number
 *    span suggests — the gap report is what makes that honest. A
 *    DUPLICATE (two files claiming the same slot) is a HARD ERROR: the
 *    set is ambiguous, the model refuses to guess which file owns the
 *    slot, and the rejection names the stage, the cause and the recovery
 *    (the PreviewErrors vocabulary — never a bare "failed").
 *
 * 3. THE FPS/PTS GRID. Given a declared (manifest) or container-detected
 *    fps, frame i presents at floor(i * 1e6 / fps) microseconds. The
 *    EXACT rule: the numerator i * 1,000,000 is computed first (exact in
 *    double for any real frame count), the division is one IEEE-754
 *    step, and truncation is the FLOOR the app's frame->us precedents
 *    already use (TimelineScreen's frame->us map, export PtsTimeline's
 *    integer-divide audio block pts). The floor-of-quotient grid never
 *    runs AHEAD of the ideal time and lands EXACTLY on whole seconds —
 *    at 24 fps frame 24 is 1,000,000 us, where a uniform
 *    41,666-us-step grid would have drifted to 999,984. Anything that
 *    cannot produce a strictly increasing integer grid is rejected up
 *    front: NaN, infinity, zero, negative rates, and rates above
 *    1,000,000 fps (a frame interval under a microsecond cannot move the
 *    integer grid forward — the export muxer's monotonic-pts contract).
 *
 * 4. THE WAV BINDING + MANIFEST. The companion audio is bound
 *    EXACT-OR-NOTHING (the GyroMatcher rationale: a wrong wav silently
 *    bound is far worse than no wav — it plays the wrong sound against
 *    the right picture). Discovery order: a manifest's DECLARED name
 *    (machine-written next to the frames, exact string, case-sensitive)
 *    when a manifest exists, else the sequence stem + ".wav" matched
 *    case-insensitively against the folder's wav names — one hit binds,
 *    zero binds nothing, two or more defer to the user. The
 *    sample-rate/duration sanity check follows QA-12's shape: audio
 *    LONGER than the video is legal (it plays to the end and trims),
 *    audio SHORTER is a WARNING (the tail runs silent) — reported, never
 *    silently ignored. The manifest is a small hand-rolled JSON shape
 *    (the SourceRepository store style: fixed key order,
 *    omit-when-undeclared, tolerant parse, corrupt input throws
 *    IllegalArgumentException and the caller degrades to a manifest-less
 *    import — the AudioStore corrupt-section rule) so the import path
 *    and a future EX-14 CinemaDNG export agree on one format.
 *
 * ERROR SHAPE. POLICY rejections throw [DngSequenceException] (stage +
 * cause + recovery — the DESIGN.md error rule). PARSE failures of the
 * manifest are the store contract instead: IllegalArgumentException
 * from the JSON reader, caught by the caller. The manifest codec is
 * self-contained on purpose (the JsonParser2 precedent: a store that
 * wants independence writes its own scoped parser) so the model stays
 * liftable when the integrator wires it.
 */

/** One parsed frame file: its slot number and the name it was parsed from. */
data class DngFrame(
    /** The trailing digit run's value — the frame's SLOT, padding-independent. */
    val frameNumber: Long,
    /** The name as given (original case, original extension) — the caller opens by it. */
    val fileName: String,
)

/**
 * One contiguous run of MISSING frame numbers between the lowest and
 * highest present frame (a REPORTED condition, never a silent skip):
 * frames 1,2,5,9 produce the runs 3..4 and 6..8.
 */
data class DngGap(
    /** The first missing number of the run, inclusive. */
    val fromFrame: Long,
    /** The last missing number of the run, inclusive. */
    val toFrame: Long,
) {
    /** How many frames the run is missing. */
    val count: Long get() = toFrame - fromFrame + 1
}

/**
 * One sequence-name pattern observed in a build: the RAW prefix (the
 * base name minus its trailing digit run, separators KEPT — "clip_" and
 * "clip" are different patterns, honestly reported as such) and how
 * many frames carry it. Zero-padding width is NOT part of the pattern:
 * unpadded sequences legally mix widths (frame_1..frame_10).
 */
data class DngPattern(
    val prefix: String,
    val frameCount: Int,
)

/**
 * One duplicate slot of a rejected build: the claimed frame number and
 * every file claiming it, in input order. Typed (not just the message)
 * so the caller's UI can list the clashing files without parsing text.
 */
data class DuplicateSlot(
    val frameNumber: Long,
    val fileNames: List<String>,
)

/**
 * The built sequence: frames in NUMERIC order, the names that did not
 * parse, the name patterns observed, and the reported gap runs.
 * Immutable; every property derives from the four lists.
 */
data class DngFrames(
    /** Frames sorted by [DngFrame.frameNumber] — numeric, NEVER lexicographic. */
    val frames: List<DngFrame>,
    /** Names whose base did not end with an ASCII digit run (or whose run overflowed) — reported, never silently skipped. */
    val unparsed: List<String>,
    /** The prefix groups in first-seen order of the sorted frames (see [DngPattern]). */
    val patterns: List<DngPattern>,
    /** The missing runs between the lowest and highest present frame, ascending. */
    val gaps: List<DngGap>,
) {
    /** How many frames parsed. */
    val frameCount: Int get() = frames.size

    /** The lowest present slot, null when nothing parsed. */
    val firstFrameNumber: Long? get() = frames.firstOrNull()?.frameNumber

    /** The highest present slot, null when nothing parsed. */
    val lastFrameNumber: Long? get() = frames.lastOrNull()?.frameNumber

    /** Total frames the gap runs are missing. */
    val missingFrameCount: Long get() = gaps.sumOf { it.count }

    /** The one pattern when every frame shares a prefix, null when there are zero or several. */
    val singlePattern: DngPattern? get() = if (patterns.size == 1) patterns.first() else null

    /**
     * IN-09's photos-vs-sequence fact ("bir klasördeki bağımsız
     * fotoğraflarla diziyi ayırt et"): at least TWO frames sharing ONE
     * prefix pattern. This is the NAMING fact, not a recording-intent
     * verdict — a photo burst named IMG_0001..IMG_0003 is
     * name-indistinguishable from a 3-frame sequence, and the import
     * flow's declared intent (the user chose "import as sequence")
     * decides; the model's job is to hand the integrator honest facts.
     * Unparsed siblings do NOT break the shape (they are reported
     * separately), and gaps do not either (they are reported, and a
     * gappy sequence is still a sequence).
     */
    val isSequenceShaped: Boolean get() = frames.size >= 2 && patterns.size == 1

    /**
     * The WAV-binding stem when the frames share one pattern (the raw
     * prefix minus its trailing '_'/'-' separators: clip_000001.dng ->
     * "clip"), null when the pattern is not single. An EMPTY stem
     * (pure-digit names, 000123.dng) never binds — those names carry no
     * sequence identity; use a manifest or a manual pick.
     */
    fun wavStem(): String? = singlePattern?.let { pattern ->
        var end = pattern.prefix.length
        while (end > 0 && (pattern.prefix[end - 1] == '_' || pattern.prefix[end - 1] == '-')) end--
        pattern.prefix.substring(0, end)
    }
}

/**
 * The sequence's CFR presentation grid: frame i presents at
 * floor(i * 1e6 / [fps]) microseconds (the exact rule in the file KDoc,
 * half 3). Built through [DngSequence.timelineOf]/[DngSequence.timeline]
 * — the factory owns the rejection rules, so a constructed timeline
 * always carries a usable rate.
 */
data class DngFrameTimeline(
    /** The declared or container-detected rate the grid was derived from. */
    val fps: Double,
    /** Frame i's presentation timestamp in microseconds, strictly increasing, frame 0 at 0. */
    val ptsUs: List<Long>,
) {
    init {
        // Direct construction bypassing the factory is a programming
        // error (the factory reports policy rejections honestly); the
        // invariant here just keeps the record self-consistent.
        require(fps.isFinite() && fps > 0.0 && fps <= DngSequence.MAX_FPS) {
            "fps must be finite and in (0, ${DngSequence.MAX_FPS}], got $fps"
        }
    }

    /**
     * The clip's recorded span: last pts minus first pts — 0 for a single
     * frame or an empty sequence (the PtsTimeline.totalDurationUs rule,
     * called rather than forked).
     */
    val durationUs: Long get() = PtsTimeline.totalDurationUs(ptsUs.toLongArray())

    /** The pts as the LongArray the engine's [PtsTimeline] functions take. */
    fun toPtsUsArray(): LongArray = ptsUs.toLongArray()
}

/**
 * The outcome of a WAV discovery match (the GyroMatcher exact-or-nothing
 * shape): one hit binds, zero bind nothing, two or more defer to the
 * user instead of guessing.
 */
sealed interface WavBinding {

    /** Exactly one candidate matched; [name] is the candidate AS GIVEN (the caller opens by it). */
    data class One(val name: String) : WavBinding

    /** Two or more candidates matched (the same base in different spellings); the caller shows a picker. */
    data class Ambiguous(val names: List<String>) : WavBinding

    /** No candidate matched: bind nothing, show nothing. */
    data object None : WavBinding
}

/**
 * The QA-12-shaped sanity verdict of a BOUND wav against the video's
 * duration: audio past the video's end is LEGAL, audio that ends early
 * is a WARNING — neither is silently ignored.
 */
sealed interface WavSanity {
    val videoDurationUs: Long
    val wavDurationUs: Long

    /**
     * The audio covers the video (equal or longer — QA-12: "video sonunu
     * aşan ses" is legal; the audio simply plays to the end and trims).
     */
    data class Ok(
        override val videoDurationUs: Long,
        override val wavDurationUs: Long,
    ) : WavSanity

    /**
     * The audio ends before the video: a WARNING, not a rejection — the
     * video's tail plays silent, and the caller shows that honestly.
     */
    data class AudioShorter(
        override val videoDurationUs: Long,
        override val wavDurationUs: Long,
    ) : WavSanity {
        /** How much of the video has no audio. */
        val shortByUs: Long get() = videoDurationUs - wavDurationUs
    }
}

/**
 * The sequence's declared identity, one small JSON record a recorder (or
 * a future EX-14 export) writes next to the frames and the import reads
 * back. Every field is OMIT-WHEN-UNDECLARED so a minimal manifest is
 * just {"version":1} and old files stay byte-identical (the store
 * omit-when-default precedent). A dumb record on purpose: the parse is
 * tolerant, and the policy rules (the fps range, the rate check) live
 * where the values are USED.
 */
data class DngSequenceManifest(
    /** The manifest shape version; unknown future versions still parse (known fields read, unknown ignored). */
    val version: Int = 1,
    /** The sequence identity the frames share ("clip") — blank = undeclared. */
    val stem: String = "",
    /** The declared frame rate — 0.0 = undeclared (never silently assumed; the timeline rejects it). */
    val fps: Double = 0.0,
    /** The first frame's slot — -1 = undeclared. */
    val startFrame: Long = -1L,
    /** The declared frame count — -1 = undeclared. */
    val frameCount: Long = -1L,
    /** The companion audio's file name, as the writer recorded it — blank = undeclared. */
    val wav: String = "",
    /** The audio's sample rate in Hz — 0 = undeclared. */
    val wavSampleRateHz: Int = 0,
)

/**
 * A POLICY rejection of the sequence model (the PreviewErrors
 * vocabulary: stage + cause + recovery — DESIGN.md's "never a bare
 * failed" rule). Stage tokens: [DngSequence.STAGE_ORDERING] (two files
 * claiming one slot), [DngSequence.STAGE_TIMELINE] (an unusable fps),
 * [DngSequence.STAGE_AUDIO] (an unusable rate or duration). Manifest
 * parse failures are NOT this — they are IllegalArgumentException and
 * the caller degrades to a manifest-less import.
 */
class DngSequenceException(
    /** The pipeline stage token ("ordering", "timeline", "audio"). */
    val stage: String,
    /** What went wrong, terse ("duplicate frame slot 12 (2 files)"). */
    val causeText: String,
    /** The user action that fixes it — always named (the DESIGN.md rule). */
    val recovery: String,
    /** Machine detail riding along in brackets (file names, values). */
    val detail: String? = null,
    /** The clash list for a duplicate rejection — empty for every other stage. */
    val duplicateSlots: List<DuplicateSlot> = emptyList(),
) : Exception(DngSequenceException.message(stage, causeText, recovery, detail)) {
    companion object {
        /**
         * The one-line message, the PreviewErrors.message shape:
         * "DNG sequence rejected (stage: ordering): duplicate frame slot
         * 12 (2 files) — recovery: remove or rename the duplicate files,
         * then re-import the folder [clip_000012.dng, clip_12.dng]".
         */
        fun message(stage: String, causeText: String, recovery: String, detail: String? = null): String {
            val base = "DNG sequence rejected (stage: $stage): $causeText — recovery: $recovery"
            return if (detail.isNullOrBlank()) base else "$base [$detail]"
        }
    }
}

/**
 * The pure DNG-sequence model's API (see the file KDoc for the four
 * halves and their policies). All of it is JVM-testable without the
 * native lib, without Android, and without B3's decoder.
 */
object DngSequence {

    // Stage tokens for [DngSequenceException] (the PreviewErrors precedent).
    const val STAGE_ORDERING = "ordering"
    const val STAGE_TIMELINE = "timeline"
    const val STAGE_AUDIO = "audio"

    /**
     * The fps ceiling: one frame per microsecond. Above it a frame
     * interval is under 1 us and the integer pts grid cannot strictly
     * increase — the export muxer's monotonic-pts contract would break.
     */
    const val MAX_FPS = 1_000_000.0

    // ---- 1. the name rule ----

    /**
     * Pure: the frame slot a name claims, or null when it claims none.
     * The rule, precisely: strip the extension (the LAST dot — a name
     * with no dot, or a single LEADING dot, has no extension and the
     * whole name is the base, the GyroMatcher.baseName rule); the base
     * must END with a run of ASCII digits ('0'..'9' — Unicode decimal
     * digits do not count); the run is the slot, parsed as a Long (a run
     * too long for Long is null, reported as unparsed). Covers
     * clip_%06d.dng, frame0012.dng and IMG-0042.dng alike; refuses
     * 2026_clip.dng (digits not trailing) and sunset.dng (no digits).
     */
    fun frameNumberOf(name: String): Long? {
        val base = baseName(name)
        var end = base.length
        while (end > 0 && base[end - 1] in '0'..'9') end--
        if (end == base.length) return null
        return base.substring(end).toLongOrNull()
    }

    /**
     * Pure: the sequence's WAV-binding stem — the base minus its trailing
     * digit run minus trailing '_'/'-' separators (clip_000123.dng ->
     * "clip", IMG-0042.dng -> "IMG", clip--0001.dng -> "clip"). Empty when
     * the name claims no slot (sunset.dng) or carries nothing but the
     * number (000123.dng): those names have no bindable identity, and
     * [bindWav] with an empty stem never binds.
     */
    fun stemOf(name: String): String {
        val base = baseName(name)
        var end = base.length
        while (end > 0 && base[end - 1] in '0'..'9') end--
        if (end == base.length) return ""
        var stemEnd = end
        while (stemEnd > 0 && (base[stemEnd - 1] == '_' || base[stemEnd - 1] == '-')) stemEnd--
        return base.substring(0, stemEnd)
    }

    // ---- 2. the build: ordering, gaps, duplicates ----

    /**
     * Builds the ordered sequence from the folder's file names. The
     * frames sort by their PARSED number (numeric, never lexicographic);
     * names that claim no slot are REPORTED in [DngFrames.unparsed];
     * missing runs between the lowest and highest present slot are
     * REPORTED in [DngFrames.gaps] (a gap never silently shortens the
     * clip, and it does not consume timeline time — the explicit CFR
     * policy in the file KDoc).
     *
     * DUPLICATES are a hard error: two names claiming the same slot make
     * the set ambiguous and this throws [DngSequenceException] (stage
     * "ordering") naming the stage, the cause, the recovery and — typed
     * in [DngSequenceException.duplicateSlots] — every clashing file.
     * The slot is the NUMBER ALONE: clip_1.dng and clip_000001.dng
     * clash; padding width is not part of the slot.
     */
    fun build(names: List<String>): DngFrames {
        val frames = ArrayList<DngFrame>(names.size)
        val unparsed = ArrayList<String>()
        for (name in names) {
            val n = frameNumberOf(name)
            if (n == null) unparsed.add(name) else frames.add(DngFrame(n, name))
        }
        // Numeric sort (stable: equal slots keep input order for the
        // duplicate report). NEVER sort by the file string.
        frames.sortBy { it.frameNumber }

        val dups = duplicateSlots(frames)
        if (dups.isNotEmpty()) {
            val first = dups.first()
            throw DngSequenceException(
                stage = STAGE_ORDERING,
                causeText = "duplicate frame slot ${first.frameNumber} (${first.fileNames.size} files)",
                recovery = "remove or rename the duplicate files, then re-import the folder",
                detail = duplicateDetail(first, dups.size),
                duplicateSlots = dups,
            )
        }

        val gaps = ArrayList<DngGap>()
        for (i in 1 until frames.size) {
            val prev = frames[i - 1].frameNumber
            val cur = frames[i].frameNumber
            if (cur > prev + 1) gaps.add(DngGap(fromFrame = prev + 1, toFrame = cur - 1))
        }

        val patternCounts = LinkedHashMap<String, Int>()
        for (f in frames) {
            val p = rawPrefixOf(f.fileName)
            patternCounts[p] = (patternCounts[p] ?: 0) + 1
        }
        return DngFrames(
            frames = frames,
            unparsed = unparsed,
            patterns = patternCounts.map { DngPattern(it.key, it.value) },
            gaps = gaps,
        )
    }

    /** The adjacent equal-slot runs of the sorted frames, in input order within each slot. */
    private fun duplicateSlots(frames: List<DngFrame>): List<DuplicateSlot> {
        val out = ArrayList<DuplicateSlot>()
        var i = 0
        while (i < frames.size) {
            var j = i + 1
            while (j < frames.size && frames[j].frameNumber == frames[i].frameNumber) j++
            if (j - i > 1) {
                out.add(DuplicateSlot(frames[i].frameNumber, frames.subList(i, j).map { it.fileName }))
            }
            i = j
        }
        return out
    }

    /** The message detail for a duplicate rejection: up to 3 file names, then the overflow counts. */
    private fun duplicateDetail(slot: DuplicateSlot, totalSlots: Int): String {
        val names = slot.fileNames.take(3).joinToString(", ")
        val moreFiles = if (slot.fileNames.size > 3) ", +${slot.fileNames.size - 3} more" else ""
        val moreSlots = if (totalSlots > 1) " (+${totalSlots - 1} more duplicate slots)" else ""
        return names + moreFiles + moreSlots
    }

    // ---- 3. the fps/pts grid ----

    /**
     * The presentation grid of a BUILT sequence at [fps]: frame i
     * presents at floor(i * 1e6 / fps) us. The grid is INDEX-based over
     * the frames PRESENT — a gap does not consume time (the CFR policy);
     * the gap report is the honest record of what that shortened.
     */
    fun timeline(frames: DngFrames, fps: Double): DngFrameTimeline =
        timelineOf(frames.frameCount, fps)

    /**
     * The core grid math for a frame count (the manifest's declared count
     * can build a timeline before the folder is even scanned). EXACT
     * RULE: pts(i) = (i * 1_000_000.0 / fps).toLong() — numerator first
     * (exact in double for any real count), one IEEE-754 division,
     * truncation toward zero, which is FLOOR for the non-negative
     * quotient and the precedent's rule (TimelineScreen's frame->us
     * map, export PtsTimeline's integer-divide audio pts). The grid
     * never runs ahead of the ideal time and lands exactly on whole
     * seconds (24 fps frame 24 == 1,000,000 us).
     *
     * REJECTIONS (all [DngSequenceException], stage "timeline"): a NaN
     * or infinite rate, a rate <= 0 (an UNDECLARED rate — the manifest's
     * 0.0 — is rejected, never silently assumed), and a rate above
     * [MAX_FPS] (the integer grid could not strictly increase). The
     * rate's PROVENANCE is the caller's to resolve (manifest, B3's
     * container-detected tag, or a user choice); the model owns the
     * math and the rejection rules only.
     */
    fun timelineOf(frameCount: Int, fps: Double): DngFrameTimeline {
        require(frameCount >= 0) { "frameCount must be >= 0, got $frameCount" }
        if (!fps.isFinite()) {
            throw DngSequenceException(
                stage = STAGE_TIMELINE,
                causeText = "fps is not a usable rate ($fps)",
                recovery = "check the sequence's declared frame rate — the manifest or the camera setting",
            )
        }
        if (fps <= 0.0) {
            throw DngSequenceException(
                stage = STAGE_TIMELINE,
                causeText = "fps $fps must be greater than zero (an undeclared rate is never assumed)",
                recovery = "check the sequence's declared frame rate — the manifest or the camera setting",
            )
        }
        if (fps > MAX_FPS) {
            throw DngSequenceException(
                stage = STAGE_TIMELINE,
                causeText = "fps $fps exceeds the $MAX_FPS limit — one frame per microsecond is the pts grid's resolution",
                recovery = "check the sequence's declared frame rate — the manifest or the camera setting",
            )
        }
        val pts = ArrayList<Long>(frameCount)
        for (i in 0 until frameCount) {
            pts.add((i * 1_000_000.0 / fps).toLong())
        }
        return DngFrameTimeline(fps, pts)
    }

    // ---- 4. the wav binding + sanity ----

    /**
     * Pure: which of [wavNames] is the sequence's companion audio, by
     * SAME-STEM discovery — EXACT-OR-NOTHING (the GyroMatcher rule and
     * rationale: a wrong wav silently bound is far worse than none).
     * Candidates are the names ending .wav (case-insensitive); a
     * candidate binds when its extension-stripped base equals [stem]
     * case-insensitively. One hit -> One (the name AS GIVEN), zero ->
     * None (bind nothing, show nothing), two or more (the same base in
     * different spellings) -> Ambiguous (the caller shows a picker). An
     * EMPTY stem never binds (pure-digit frame names carry no identity).
     */
    fun bindWav(stem: String, wavNames: List<String>): WavBinding {
        if (stem.isEmpty()) return WavBinding.None
        val hits = LinkedHashSet<String>()
        for (name in wavNames) {
            if (!name.endsWith(".wav", ignoreCase = true)) continue
            if (baseName(name).equals(stem, ignoreCase = true)) hits.add(name)
        }
        return when (hits.size) {
            0 -> WavBinding.None
            1 -> WavBinding.One(hits.first())
            else -> WavBinding.Ambiguous(hits.toList())
        }
    }

    /**
     * Pure: which of [wavNames] the MANIFEST's declared name binds. The
     * manifest is machine-written next to the frames by the same tool
     * that wrote them, so the recorded string IS the binding — an EXACT,
     * case-sensitive match. A mismatch (the folder was renamed after the
     * manifest was written) binds NOTHING rather than silently guessing
     * at a near name; a blank declaration is no declaration.
     */
    fun bindDeclaredWav(declaredName: String, wavNames: List<String>): WavBinding {
        if (declaredName.isBlank()) return WavBinding.None
        return if (wavNames.any { it == declaredName }) {
            WavBinding.One(declaredName)
        } else {
            WavBinding.None
        }
    }

    /**
     * Pure: a WAV's duration in microseconds from its sample count and
     * rate — floor(sampleCount * 1e6 / rate) in EXACT integer arithmetic
     * (the export PtsTimeline.audioBlockPts rule, divide-last), so the
     * us grid never drifts the way a per-sample double step would.
     * REJECTS (stage "audio"): a rate <= 0 (AUD-06: the rate is required
     * data, never assumed) and a negative sample count.
     */
    fun wavDurationUs(sampleCount: Long, sampleRateHz: Int): Long {
        if (sampleRateHz <= 0) {
            throw DngSequenceException(
                stage = STAGE_AUDIO,
                causeText = "sample rate $sampleRateHz is not a usable rate",
                recovery = "re-check the WAV file's header, then re-import the clip",
            )
        }
        if (sampleCount < 0) {
            throw DngSequenceException(
                stage = STAGE_AUDIO,
                causeText = "sample count $sampleCount must be non-negative",
                recovery = "re-check the WAV file's header, then re-import the clip",
            )
        }
        return sampleCount * 1_000_000L / sampleRateHz
    }

    /**
     * Pure: the QA-12-shaped sanity verdict of a bound wav against the
     * video's duration. Audio AT LEAST as long as the video -> Ok
     * (audio past the video's end is LEGAL — it plays to the end and
     * trims). Audio SHORTER -> AudioShorter, a WARNING (the tail runs
     * silent) — reported, never silently ignored, never a rejection.
     * REJECTS (stage "audio"): a non-positive sample rate (AUD-06) and
     * negative durations — those are unusable inputs, not warnings.
     */
    fun checkWav(videoDurationUs: Long, wavDurationUs: Long, wavSampleRateHz: Int): WavSanity {
        if (wavSampleRateHz <= 0) {
            throw DngSequenceException(
                stage = STAGE_AUDIO,
                causeText = "sample rate $wavSampleRateHz is not a usable rate",
                recovery = "re-check the WAV file's header, then re-import the clip",
            )
        }
        if (videoDurationUs < 0 || wavDurationUs < 0) {
            throw DngSequenceException(
                stage = STAGE_AUDIO,
                causeText = "durations must be non-negative microseconds (video $videoDurationUs, wav $wavDurationUs)",
                recovery = "re-check the sequence's frame rate and the audio file's length",
            )
        }
        return if (wavDurationUs >= videoDurationUs) {
            WavSanity.Ok(videoDurationUs = videoDurationUs, wavDurationUs = wavDurationUs)
        } else {
            WavSanity.AudioShorter(videoDurationUs = videoDurationUs, wavDurationUs = wavDurationUs)
        }
    }

    // ---- 5. the manifest codec ----

    /**
     * Renders the manifest as one JSON object (the store style: fixed
     * key order version, stem, fps, startFrame, frameCount, wav,
     * wavSampleRateHz; omit-when-undeclared so a minimal manifest is
     * {"version":1} and old files stay byte-identical). A non-finite fps
     * renders as UNDECLARED (omitted) — an unusable rate is as good as
     * no rate, and "NaN" would not even be valid JSON.
     */
    fun renderManifest(m: DngSequenceManifest): String {
        val sb = StringBuilder()
        sb.append("{\"version\":").append(m.version)
        if (m.stem.isNotBlank()) sb.append(",\"stem\":").append(jsonString(m.stem))
        if (m.fps.isFinite() && m.fps != 0.0) {
            sb.append(",\"fps\":").append(jsonNumber(m.fps))
        }
        if (m.startFrame >= 0) sb.append(",\"startFrame\":").append(m.startFrame)
        if (m.frameCount >= 0) sb.append(",\"frameCount\":").append(m.frameCount)
        if (m.wav.isNotBlank()) sb.append(",\"wav\":").append(jsonString(m.wav))
        if (m.wavSampleRateHz > 0) sb.append(",\"wavSampleRateHz\":").append(m.wavSampleRateHz)
        sb.append('}')
        return sb.toString()
    }

    /**
     * Parses the manifest. TOLERANT (the store parse contract): unknown
     * fields are ignored, absent fields fall back to their undeclared
     * defaults, wrong-typed values read as undeclared, and an unknown
     * future version still parses (the model reads the fields it knows).
     * CORRUPT input (unparseable JSON, trailing content, a non-object
     * root) throws IllegalArgumentException — the caller catches and
     * degrades to a manifest-less import (the AudioStore corrupt-section
     * rule); this is NOT a policy rejection.
     */
    fun parseManifest(text: String): DngSequenceManifest {
        val p = ManifestJson(text)
        val root = p.parseValue()
        if (!p.atEnd()) throw IllegalArgumentException("trailing content")
        if (root !is Map<*, *>) throw IllegalArgumentException("root is not an object")
        return DngSequenceManifest(
            version = (root["version"] as? Double)?.toInt() ?: 1,
            stem = root["stem"] as? String ?: "",
            fps = root["fps"] as? Double ?: 0.0,
            startFrame = (root["startFrame"] as? Double)?.toLong() ?: -1L,
            frameCount = (root["frameCount"] as? Double)?.toLong() ?: -1L,
            wav = root["wav"] as? String ?: "",
            wavSampleRateHz = (root["wavSampleRateHz"] as? Double)?.toInt() ?: 0,
        )
    }

    // ---- shared helpers ----

    /**
     * The name without its extension: everything before the LAST dot. A
     * name with no dot — or a single LEADING dot — has no extension; the
     * whole name is the base (the GyroMatcher.baseName rule).
     */
    private fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }

    /** The RAW grouping prefix of a frame name: the base minus its trailing digit run (separators KEPT). */
    private fun rawPrefixOf(name: String): String {
        val base = baseName(name)
        var end = base.length
        while (end > 0 && base[end - 1] in '0'..'9') end--
        return base.substring(0, end)
    }

    /** The store's string-quoting shape (SourceRepository.jsonString): only " and \ are escaped. */
    private fun jsonString(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** The store's number shape (ProjectJson.renderRaw): an integral double renders without ".0". */
    private fun jsonNumber(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}

/**
 * Minimal JSON parser for the manifest (the JsonParser2 precedent: a
 * store that wants independence writes its own scoped parser instead of
 * coupling to another package's internals). Values: object, array,
 * string, number (as Double), boolean, null.
 */
private class ManifestJson(private val s: String) {
    private var i = 0

    fun atEnd(): Boolean {
        skipWs()
        return i >= s.length
    }

    fun parseValue(): Any? {
        skipWs()
        if (i >= s.length) throw IllegalArgumentException("unexpected end")
        return when (val c = s[i]) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else ->
                if (c == '-' || c.isDigit()) parseNumber()
                else throw IllegalArgumentException("unexpected char at $i")
        }
    }

    private fun skipWs() {
        while (i < s.length && s[i].isWhitespace()) i++
    }

    private fun expect(word: String) {
        if (!s.startsWith(word, i)) throw IllegalArgumentException("bad literal at $i")
        i += word.length
    }

    private fun parseObject(): Map<String, Any?> {
        i++
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (i < s.length && s[i] == '}') { i++; return out }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            if (i >= s.length || s[i] != ':') throw IllegalArgumentException("expected : at $i")
            i++
            out[key] = parseValue()
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == '}' -> { i++; return out }
                else -> throw IllegalArgumentException("expected , or } at $i")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        i++
        val out = ArrayList<Any?>()
        skipWs()
        if (i < s.length && s[i] == ']') { i++; return out }
        while (true) {
            out.add(parseValue())
            skipWs()
            when {
                i < s.length && s[i] == ',' -> i++
                i < s.length && s[i] == ']' -> { i++; return out }
                else -> throw IllegalArgumentException("expected , or ] at $i")
            }
        }
    }

    private fun parseString(): String {
        if (i >= s.length || s[i] != '"') throw IllegalArgumentException("expected string at $i")
        i++
        val sb = StringBuilder()
        while (true) {
            if (i >= s.length) throw IllegalArgumentException("unterminated string")
            when (val c = s[i]) {
                '"' -> { i++; return sb.toString() }
                '\\' -> {
                    i++
                    if (i >= s.length) throw IllegalArgumentException("bad escape at $i")
                    when (val e = s[i]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 5 > s.length) throw IllegalArgumentException("bad escape at $i")
                            val hex = s.substring(i + 1, i + 5)
                            sb.append(hex.toInt(16).toChar())
                            i += 4
                        }
                        else -> throw IllegalArgumentException("bad escape at $i")
                    }
                    i++
                }
                else -> { sb.append(c); i++ }
            }
        }
    }

    private fun parseNumber(): Double {
        val start = i
        if (i < s.length && s[i] == '-') i++
        while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
        return s.substring(start, i).toDouble()
    }
}
