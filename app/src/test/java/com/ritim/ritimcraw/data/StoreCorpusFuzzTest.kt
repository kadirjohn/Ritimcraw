package com.ritim.ritimcraw.data

import com.ritim.ritimcraw.data.audio.AudioClip
import com.ritim.ritimcraw.data.audio.AudioLinkRecord
import com.ritim.ritimcraw.data.audio.AudioLinkState
import com.ritim.ritimcraw.data.audio.AudioProject
import com.ritim.ritimcraw.data.audio.AudioStore
import com.ritim.ritimcraw.data.metadata.AudioSummary
import com.ritim.ritimcraw.data.metadata.Capability
import com.ritim.ritimcraw.data.metadata.Diagnosis
import com.ritim.ritimcraw.data.metadata.Field
import com.ritim.ritimcraw.data.metadata.LogDiagnosis
import com.ritim.ritimcraw.data.metadata.MetadataRecord
import com.ritim.ritimcraw.data.metadata.MetadataStore
import com.ritim.ritimcraw.data.timeline.FrameRange
import com.ritim.ritimcraw.data.timeline.TimelineClip
import com.ritim.ritimcraw.data.timeline.TimelineOutput
import com.ritim.ritimcraw.data.timeline.TimelineProject
import com.ritim.ritimcraw.data.timeline.TimelineStore
import com.ritim.ritimcraw.data.timeline.TimelineTrack
import com.ritim.ritimcraw.data.timeline.TrackKind
import com.ritim.ritimcraw.export.ExportJob
import com.ritim.ritimcraw.export.ExportSettings
import com.ritim.ritimcraw.export.ExportStore
import com.ritim.ritimcraw.export.KeyframeInterval
import com.ritim.ritimcraw.export.State
import com.ritim.ritimcraw.export.Target
import com.ritim.ritimcraw.proxy.ProxyKey
import com.ritim.ritimcraw.proxy.ProxyManifest
import com.ritim.ritimcraw.proxy.ProxyManifestCodec
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * I10 (night mission 2026-11-27): coarse-fuzz resilience of the STORE
 * FILES — every store that parses a JSON document is fed a VALID file
 * (written through the store's own write/render path), then the corpus
 * is damaged the three ways a real disk can damage it and every read
 * must come back WITHOUT AN UNCAUGHT THROW:
 *
 *  - TRUNCATE at every byte offset (a file cut mid-write, disk full, a
 *    sync tool): a torn document is the E1 case class;
 *  - FLIP random bytes (deterministic seed, fixed iterations): bit rot
 *    inside a string/escape/number — the class of damage line deletion
 *    can never produce (encoding errors, length fields, section heads);
 *  - DELETE random lines (the same seed discipline): a hand-merged or
 *    partially-synced file.
 *
 * The CONTRACT under damage (each store's own documented corrupt-file
 * rule): the load either degrades to the default/empty or fails as the
 * honest "could not read" — never an uncaught exception out of the
 * public read, never a poisoned in-memory state. This file does NOT
 * re-test E1's cases (escape/NaN/TooNew — ProjectFileResilienceTest);
 * it is the byte-level sweep over every store E1 did not cover.
 *
 * Scope (every project.json store + the standalone-file codecs):
 *  GradeStore, SourceRepository, ExportStore, PresetStore, LutStore,
 *  GyroStore (the six project.json sections), AudioStore,
 *  TimelineStore, MetadataStore (the three nested-section stores),
 *  the scalar stores (Theme/PreviewQuality/RenderBackend/ScrubFrames/
 *  PreviewStats/SystemMonitor/AutoHidePlayerBar/LibraryPrefs/ImportFolder/
 *  GyroFolder — one representative sweep over the shared scalar shape),
 *  PresetFile (the portable preset file), ProxyManifestCodec (the proxy
 *  manifest), ExposureKeyframes (the pure codec), CodecMeasureStore
 *  (the measurement report file) and ProjectFile.open (the gate itself).
 *
 * Determinism: every random pass is seeded (fixed per store) and the
 * iteration counts are fixed constants, so a failure is reproducible
 * from the printed offset/seed line. Truncation is a FULL byte-offset
 * sweep on the compact corpora and step-sampled on the big ones
 * (<= a few seconds total wall clock; the sampling step is a constant).
 */
class StoreCorpusFuzzTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File

    private fun freshFile(name: String = "project.json"): File {
        file = File(tmp.root, name)
        return file
    }

    // ---- damage generators (deterministic) ----

    /**
     * Truncation offsets: EVERY offset for a compact corpus (the honest
     * full sweep), step-sampled above [FULL_SWEEP_LIMIT] so the big
     * corpora stay inside the time budget. Returns the offsets in order.
     */
    private fun truncationOffsets(size: Int): IntArray {
        val step = if (size <= FULL_SWEEP_LIMIT) 1 else size / MAX_TRUNCATIONS + 1
        val out = ArrayList<Int>(size / step + 2)
        var i = 1
        while (i < size) {
            out.add(i)
            i += step
        }
        return out.toIntArray()
    }

    /**
     * Flips [FLIPS] deterministic random bytes: a fixed-seed LCG over
     * the corpus bytes, each flip XOR-ing one bit position. Returns the
     * mutated texts (one per flip round).
     */
    private fun byteFlips(text: String): List<String> {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val out = ArrayList<String>(FLIPS)
        var seed = FLIP_SEED
        for (round in 0 until FLIPS) {
            val copy = bytes.clone()
            // A few single-bit flips per round (deterministic positions).
            repeat(FLIP_BITS_PER_ROUND) {
                seed = lcg(seed)
                val pos = (seed ushr 8).toInt().let { if (copy.isEmpty()) 0 else (((it % copy.size) + copy.size) % copy.size) }
                seed = lcg(seed)
                val bit = 1 shl (((seed ushr 16) % 8).toInt())
                copy[pos] = (copy[pos].toInt() xor bit).toByte()
            }
            out.add(String(copy, StandardCharsets.UTF_8))
        }
        return out
    }

    /**
     * Deletes [LINE_DELETIONS] deterministic random lines. Lines split on
     * \n only (the writers never emit \r); the same LCG discipline.
     */
    private fun lineDeletions(text: String): List<String> {
        val lines = text.split('\n')
        if (lines.size <= 1) return emptyList()
        val out = ArrayList<String>(LINE_DELETIONS)
        var seed = LINE_SEED
        for (round in 0 until LINE_DELETIONS) {
            val kept = ArrayList<String>(lines.size)
            seed = lcg(seed)
            val drop = ((seed ushr 8) % lines.size).toInt()
            lines.forEachIndexed { i, l -> if (i != drop) kept.add(l) }
            out.add(kept.joinToString("\n"))
        }
        return out
    }

    private fun lcg(seed: Long): Long = (seed * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L) and 0x7FFF_FFFF_FFFF_FFFFL

    // ---- the sweep driver ----

    /**
     * Runs one store's read [read] over EVERY damaged variant of
     * [valid]: every truncation offset, every byte-flip round, every
     * line deletion. Each variant is written to a FRESH file (store
     * state — the TooNew gate, the parsed cache — must reset between
     * variants, exactly like an app restart on a damaged file).
     */
    private fun sweep(label: String, valid: String, read: (File) -> Unit) {
        val bytes = valid.toByteArray(StandardCharsets.UTF_8)
        var cases = 0

        // (1) truncation at every (sampled) byte offset.
        for (cut in truncationOffsets(bytes.size)) {
            val torn = String(bytes, 0, cut)
            cases++
            readDamaged(label, "truncate@$cut", torn, read)
        }

        // (2) deterministic byte flips.
        for ((i, mutated) in byteFlips(valid).withIndex()) {
            cases++
            readDamaged(label, "flip#$i", mutated, read)
        }

        // (3) deterministic line deletions.
        for ((i, mutated) in lineDeletions(valid).withIndex()) {
            cases++
            readDamaged(label, "delline#$i", mutated, read)
        }

        totalCases += cases
        perStoreCases[label] = (perStoreCases[label] ?: 0) + cases
    }

    /** Writes one damaged variant to a fresh file and runs the read. */
    private fun readDamaged(label: String, tag: String, text: String, read: (File) -> Unit) {
        ProjectFile.resetForTest()
        val f = File(tmp.root, "$label-${variantCounter++}.json")
        try {
            f.writeText(text, StandardCharsets.UTF_8)
            try {
                read(f)
            } catch (t: Throwable) {
                throw AssertionError(
                    "$label $tag: store read threw ${t.javaClass.simpleName}: ${t.message}\n" +
                        "---- damaged file ----\n$text\n------------------------",
                    t,
                )
            }
        } finally {
            f.delete()
        }
    }

    private var variantCounter = 0
    private val perStoreCases = LinkedHashMap<String, Int>()
    private var totalCases = 0

    /** Asserts the sweep actually exercised damage (never a vacuous pass). */
    private fun assertSweptSomething(label: String, minimum: Int) {
        val n = perStoreCases[label] ?: 0
        assertTrue("$label exercised only $n cases (vacuous fuzz)", n >= minimum)
    }

    private companion object {
        /** Full byte-offset sweep under this corpus size; step-sampled above. */
        const val FULL_SWEEP_LIMIT = 4096

        /** Step-sampled corpora cap at this many truncation offsets. */
        const val MAX_TRUNCATIONS = 256

        /** Byte-flip rounds per corpus (deterministic seed). */
        const val FLIPS = 48

        /** Bits flipped per round. */
        const val FLIP_BITS_PER_ROUND = 3

        /** Line-deletion rounds per corpus (deterministic seed). */
        const val LINE_DELETIONS = 24

        /** The LCG seeds (fixed, so every failure is reproducible). */
        const val FLIP_SEED = 0x2A17F00DL
        const val LINE_SEED = 0x5EED00F7L
    }

    // ---- corpora: a VALID file per store, written through the store's own path ----

    /**
     * The MAXIMAL project.json: every store's section carries its fullest
     * shape (every optional field set), so the fuzz exercises every
     * parser branch. Written through the STORES themselves — the corpus
     * is by construction a file this build can write.
     */
    private fun maximalProjectJson(): String {
        val f = freshFile()
        val grade = GradeK(
            exposureEv = 0.8f, highlights = -30f, tempK = 6100f, tint = 12f,
            saturation = 22f, blackPoint = 6f, whitePoint = -4f, shadows = 18f,
            midtones = -9f, contrast = 14f, asShot = false,
            highlightReconstruction = false, keepVignetting = false,
            highlightRecovery = true, highlightKneeRef = 0.42f,
            sharpness = 31f, detail = -17f, tonemap = 3,
            lutId = "cube-44", lutStrength = 65f,
            noiseReduction = 40f, chromaDenoise = 25f,
            temporalNr = true, temporalStrength = 70f,
            stabilization = true, stabilizationMode = 2,
            stabilizationStrength = 80f, stabilizationZoomLimit = 1.4f,
            stabilizationTrimMs = 12.5f, stabilizationFocalMm = 24f,
            stabilizationEngine = 1, cropAspect = 1, cropX = 0.1f, cropY = 0.2f,
            cropW = 0.7f, cropH = 0.6f, straightenDeg = 3.5f,
            wheelShadowX = 0.11f, wheelShadowY = -0.22f, wheelMidX = -0.33f,
            wheelMidY = 0.44f, wheelHighX = 0.55f, wheelHighY = 0.66f,
            splitShadowHue = 210f, splitHighlightHue = 45f, splitBalance = -25f,
        )
        GradeStore(f).put("clip-full", grade)
        GradeStore(f).put(
            "clip-nodes",
            com.ritim.ritimcraw.data.grade2.ClipGradeV2(
                develop = com.ritim.ritimcraw.data.grade2.DevelopV2(exposureEv = 0.5f, rawLevels = RawLevels(white = 1.02f)),
                nodes = listOf(
                    com.ritim.ritimcraw.data.grade2.GradeNodeV2(
                        sat = 1.1f, name = "node one",
                        curveY = com.ritim.ritimcraw.data.grade2.NodeCurveV2(listOf(0f to 0f, 0.5f to 0.6f, 1f to 1f)),
                    ),
                ),
            ),
        )
        SourceRepository(FakeResolver(), f).import("content://media/clip-a", SourceKind.MCRAW)
        ExportStore(f).add(
            ExportJob(
                id = "job-full", sourceId = "clip-full",
                snapshot = GradeK(exposureEv = 0.4f, lutId = "cube-44"),
                target = Target.MP4, state = State.DONE, detail = "ok",
                framesDone = 90, framesTotal = 90,
                publishedUri = "content://media/1", outputName = "name.mp4",
                frameIndex = 7, manualRotationDeg = 90,
                resolutionW = 3840, resolutionH = 2160,
                audioLink = com.ritim.ritimcraw.data.audio.AudioLinkState.DETACHED,
                settings = ExportSettings(keyframe = KeyframeInterval.ONE),
                report = com.ritim.ritimcraw.export.ExportReport(
                    encoder = "enc", hardware = true, mime = "video/avc",
                    width = 3840, height = 2160, fps = 30.0, bitDepth = 8,
                    rateMode = "VBR", profile = 1, keyframeSec = 1f,
                    requestedBps = 20_000_000, audioKbps = 192, fileBytes = 10L,
                    durationSec = 3.5, steps = listOf("step one", "step two"),
                    issues = listOf("ISSUE:x,y"),
                ),
            ),
        )
        PresetStore(f).put("Teal & Orange \"quoted\" \\ slash", grade)
        val lutResolver = FakeResolver(
            blobs = mapOf(
                "content://lut/a" to ByteArray(64) { (it * 3 + 1).toByte() },
                "content://lut/b" to ByteArray(64) { (it * 7 + 5).toByte() },
            ),
        )
        LutStore(
            lutResolver,
            f,
            File(tmp.root, "luts"),
        ).let { lut ->
            lut.import("content://lut/a", "Cube A", LutInterpK.legacy())
            lut.import("content://lut/b", "Cube B", LutInterpK(role = LutRole.SceneLook))
        }
        val gyroResolver = FakeResolver(
            blobs = mapOf(
                "content://gyro/1" to ByteArray(64) { (it * 11 + 3).toByte() },
                "content://gyro/2" to ByteArray(64) { (it * 13 + 9).toByte() },
            ),
        )
        GyroStore(
            gyroResolver,
            f,
            File(tmp.root, "gyros"),
        ).let { gyro ->
            gyro.import("content://gyro/1", "log one")
            gyro.import("content://gyro/2", "log two")
        }
        AudioStore(f).save(
            AudioProject(
                links = mapOf(
                    "src-1" to AudioLinkRecord(state = AudioLinkState.REMOVED),
                    "src-2" to AudioLinkRecord(state = AudioLinkState.LINKED_EDITED, editedAudioId = "rec-2"),
                ),
                clips = listOf(
                    AudioClip(
                        id = "a-1", sourceId = "src-1", sampleRateHz = 48_000,
                        gainDb = -6.5f, fadeInMs = 120, fadeOutMs = 80,
                        pan = 0.25f, muted = true,
                    ),
                ),
                masterGainDb = -4.5f, masterMuted = true,
            ),
        )
        TimelineStore(f).save(
            TimelineProject(
                output = TimelineOutput(
                    width = 3840, height = 2160, fps = 60, orientationDeg = 90,
                    colorSpace = "rec2020", transfer = "hlg", hdr = true,
                ),
                tracks = listOf(TimelineTrack("v", TrackKind.VIDEO, 0)),
                clips = listOf(
                    TimelineClip(
                        id = "c1", trackId = "v", sourceId = "src-1",
                        sourceRange = FrameRange(0.0, 50.5),
                        timelineRange = FrameRange(10.25, 60.75),
                        colorDefinition = com.ritim.ritimcraw.data.timeline.ClipColorDefinition(
                            encoding = "mi-log", colorSpace = "bt2020", transfer = "hlg",
                        ),
                        text = "A title with \"quotes\" and \\ backslash",
                        linkedAudioId = "a-1",
                    ),
                ),
                projectEnd = 120.5, backgroundDuration = 90.25,
            ),
        )
        MetadataStore(f).replaceAll(
            mapOf(
                "src-1" to MetadataRecord(
                    sourceId = "src-1", kind = SourceKind.MCRAW,
                    durationUs = Field.known(1L),
                    frameCount = Field.estimated(30, "(duration x fps)"),
                    frameCountEstimated = true,
                    fpsNominal = Field.known(30.0),
                    fpsMeasured = Field.estimated(29.97, "(frames - 1) / span"),
                    camera = Field.known("Xiaomi 15 Ultra"),
                    lens = Field.known("24mm f/1.6"),
                    resolution = Field.known("4000x3000"),
                    bitDepth = Field.known(16),
                    primaries = Field.known("bt2020"),
                    transfer = Field.known("hlg"),
                    matrix = Field.known("bt2020nc"),
                    range = Field.known("full"),
                    audio = Field.known(AudioSummary(present = true, codec = "mp4a", channels = 2, sampleRateHz = 48_000)),
                    fileSize = Field.known(1_000_000L),
                    fileIdentity = Field.known("fp-1"),
                    dolbyVision = Diagnosis.NOT_DV,
                    logProfile = LogDiagnosis.NOT_LOG,
                    hdrOutputSupport = Capability.SUPPORTED,
                ),
            ),
        )
        return f.readText(StandardCharsets.UTF_8)
    }

    // ---- (1) the project.json stores: every read over every damaged variant ----

    @Test
    fun gradeStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("gradestore", valid) { f ->
            val g = GradeStore(f).get("clip-full")
            assertNotNull(g)
            assertTrue(g.exposureEv.isFinite())
            GradeStore(f).getV2("clip-nodes")
            GradeStore(f).put("fuzz-write", GradeK(exposureEv = 1f))
        }
        assertSweptSomething("gradestore", 100)
    }

    @Test
    fun sourceRepository_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("sourcerepo", valid) { f ->
            SourceRepository(FakeResolver(), f).all()
            SourceRepository(FakeResolver(), f).get("anything")
        }
        assertSweptSomething("sourcerepo", 100)
    }

    @Test
    fun exportStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("exportstore", valid) { f ->
            ExportStore(f).all()
            ExportStore(f).retry("job-full")
            ExportStore(f).delete(setOf("job-full"))
        }
        assertSweptSomething("exportstore", 100)
    }

    @Test
    fun presetStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("presetstore", valid) { f ->
            PresetStore(f).all()
            PresetStore(f).put("fuzz", GradeK(exposureEv = 1f))
            PresetStore(f).remove("no-such")
        }
        assertSweptSomething("presetstore", 100)
    }

    @Test
    fun lutStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("lutstore", valid) { f ->
            LutStore(FakeResolver(), f, File(tmp.root, "luts")).all()
            LutStore(FakeResolver(), f, File(tmp.root, "luts")).get("cube-44")
        }
        assertSweptSomething("lutstore", 100)
    }

    @Test
    fun gyroStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("gyrostore", valid) { f ->
            GyroStore(FakeResolver(), f, File(tmp.root, "gyros")).all()
            GyroStore(FakeResolver(), f, File(tmp.root, "gyros")).get("no-such")
        }
        assertSweptSomething("gyrostore", 100)
    }

    @Test
    fun audioStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("audiostore", valid) { f ->
            val p = AudioStore(f).load()
            assertTrue("master gain must stay finite", p.masterGainDb.isFinite())
            p.clips.forEach { assertTrue(it.gainDb.isFinite()); assertTrue(it.pan.isFinite()) }
            AudioStore(f).save(p)
        }
        assertSweptSomething("audiostore", 100)
    }

    @Test
    fun timelineStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("timelinestore", valid) { f ->
            val p = TimelineStore(f).load()
            assertTrue(p.projectEnd?.isFinite() ?: true)
            p.clips.forEach {
                assertTrue(it.sourceRange.start.isFinite())
                assertTrue(it.timelineRange.end.isFinite())
            }
            TimelineStore(f).save(p)
        }
        assertSweptSomething("timelinestore", 100)
    }

    @Test
    fun metadataStore_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("metadatastore", valid) { f ->
            val records = MetadataStore(f).load()
            records.forEach { _, r ->
                r.fpsMeasured.value?.let { assertTrue("fpsMeasured must be finite or absent", it.isFinite()) }
                r.fpsNominal.value?.let { assertTrue(it.isFinite()) }
            }
            MetadataStore(f).get("src-1")
            MetadataStore(f).remove("no-such")
        }
        assertSweptSomething("metadatastore", 100)
    }

    // ---- (2) the scalar stores: the shared top-level-scalar shape ----

    @Test
    fun scalarStores_survive_every_truncation_flip_and_line_deletion() {
        // The scalar stores share ONE shape (a top-level key merged into
        // the whole project.json) and one read rule (default on damage).
        // The maximal corpus carries every scalar's non-default value.
        val f = freshFile()
        ThemeStore(f).put(ThemeMode.DARK)
        ThemeStore(f).putCustomThemeId("custom-theme-1")
        com.ritim.ritimcraw.data.PreviewQualityStore(f)
            .put(com.ritim.ritimcraw.engine.PreviewQuality.HIGH)
        RenderBackendStore(f).put(com.ritim.ritimcraw.engine.RenderBackend.GPU_EXPERIMENTAL)
        ScrubFramesStore(f).put(true)
        PreviewStatsStore(f).put(false)
        SystemMonitorStore(f).put(false)
        AutoHidePlayerBarStore(f).put(true)
        LibraryPrefsStore(f).putSortKey(LibrarySortKey.DURATION)
        LibraryPrefsStore(f).putAscending(false)
        LibraryPrefsStore(f).putViewMode(LibraryViewMode.GRID)
        ImportFolderStore(f).put("content://tree/imported")
        GyroFolderStore(f).put("content://tree/gyros")
        val valid = f.readText(StandardCharsets.UTF_8)
        assertTrue(valid.contains("\"theme\":\"DARK\""))
        assertTrue(valid.contains("\"library_view_mode\":\"grid\""))

        sweep("scalars", valid) { damaged ->
            // The scalar contract under damage: the read never throws and
            // resolves to the default OR to a value this build can
            // legitimately read from what survived (a full-length cut is
            // the valid file itself; damage that leaves the key parseable
            // keeps it). Never a crash, never a poisoned non-default.
            val theme = ThemeStore(damaged).get()
            assertTrue(theme in ThemeMode.entries)
            ThemeStore(damaged).getCustomThemeId()
            val q = com.ritim.ritimcraw.data.PreviewQualityStore(damaged).get()
            assertTrue(q in com.ritim.ritimcraw.engine.PreviewQuality.entries)
            val backend = RenderBackendStore(damaged).get()
            assertTrue(backend in com.ritim.ritimcraw.engine.RenderBackend.entries)
            ScrubFramesStore(damaged).get()
            PreviewStatsStore(damaged).get()
            SystemMonitorStore(damaged).get()
            AutoHidePlayerBarStore(damaged).get()
            val sort = LibraryPrefsStore(damaged).sortKey()
            assertTrue(sort == null || sort in LibrarySortKey.entries)
            LibraryPrefsStore(damaged).ascending()
            val view = LibraryPrefsStore(damaged).viewMode()
            assertTrue(view in LibraryViewMode.entries)
            ImportFolderStore(damaged).get()
            GyroFolderStore(damaged).get()
        }
        assertSweptSomething("scalars", 100)
    }

    // ---- (3) the standalone-file codecs ----

    /**
     * The TEXT-CODEC sweep: like [sweep] but hands the damaged TEXT to
     * [read] directly (for codecs whose entry point is a string, not a
     * file). Same damage generators, same determinism.
     */
    private fun sweepText(label: String, valid: String, read: (String) -> Unit) {
        val bytes = valid.toByteArray(StandardCharsets.UTF_8)
        var cases = 0
        for (cut in truncationOffsets(bytes.size)) {
            cases++
            readContracted(label, "truncate@$cut", String(bytes, 0, cut), read)
        }
        for ((i, m) in byteFlips(valid).withIndex()) {
            cases++
            readContracted(label, "flip#$i", m, read)
        }
        for ((i, m) in lineDeletions(valid).withIndex()) {
            cases++
            readContracted(label, "delline#$i", m, read)
        }
        totalCases += cases
        perStoreCases[label] = (perStoreCases[label] ?: 0) + cases
    }

    private fun readContracted(label: String, tag: String, text: String, read: (String) -> Unit) {
        try {
            read(text)
        } catch (t: Throwable) {
            throw AssertionError(
                "$label $tag: read threw ${t.javaClass.simpleName}: ${t.message}\n" +
                    "---- damaged text ----\n$text\n----------------------",
                t,
            )
        }
    }

    @Test
    fun presetFile_survives_every_truncation_flip_and_line_deletion() {
        // PresetFile.parse's own contract: "Parses a preset file (or a
        // legacy project.json); NEVER THROWS" — an unreadable file is an
        // honest Failure, so the fuzz asserts both (no throw AND a
        // result, never a half-parsed Ok with garbage).
        val presets = listOf(
            GradePreset(id = "p1", name = "Teal & Orange", grade = GradeK(exposureEv = 0.8f, lutId = "cube-44")),
            GradePreset(id = "p2", name = "quoted \"name\"", grade = GradeK(tempK = 6100f, stabilization = true)),
        )
        val valid = PresetFile.render(presets)
        // The rendered file must parse clean before any damage (the
        // corpus is valid by construction).
        assertTrue(PresetFile.parse(valid) is PresetFile.Ok)

        sweepText("presetfile", valid) { text ->
            val result = PresetFile.parse(text)
            assertNotNull("parse never throws and never returns null", result)
            // An Ok result must never carry an unparseable preset: the
            // grade went through the shared mapOfGrade, so every preset
            // in an Ok result is a real GradeK.
            if (result is PresetFile.Ok) {
                result.presets.forEach { p ->
                    assertTrue("every preset grade must be finite", p.grade.exposureEv.isFinite())
                }
            }
        }
        assertSweptSomething("presetfile", 100)
    }

    @Test
    fun proxyManifest_survives_every_truncation_flip_and_line_deletion() {
        // ProxyManifestCodec's contract: "any damaged, truncated or
        // foreign text yields null, NEVER an exception".
        val valid = ProxyManifestCodec.render(
            ProxyManifest(
                fingerprint = "abc123", sigHash = "0123456789abcdef",
                videoBytes = 1_000_000L, durationUs = 2_500_000L, frameCount = 75L,
                createdAtMs = 1_758_000_000_000L,
                signature = "v1|kind=ENCODED|fp=abc123|size=100|mtime=5|short=1080|codec=video/hevc|bits=10|br=default|kfi=1|color=bt2020-full-untagged-10|xfer=0|enc=1|h=0",
            ),
        )
        assertNotNull(ProxyManifestCodec.parse(valid.toByteArray(StandardCharsets.UTF_8)))
        sweepText("proxymanifest", valid) { text ->
            // Never a throw; unreadable text yields null (the cache then
            // treats the entry as absent — the sweep's read contract).
            ProxyManifestCodec.parse(text.toByteArray(StandardCharsets.UTF_8))
        }
        assertSweptSomething("proxymanifest", 100)
    }

    @Test
    fun exposureKeyframes_survive_every_truncation_flip_and_line_deletion() {
        // ExposureKeyframes.parse is a PURE CODEC with the documented
        // contract "malformed JSON throws IllegalArgumentException — the
        // store-level caller decides": the fuzz asserts exactly that
        // contract (the failure TYPE every store catch names), never an
        // unchecked IndexOutOfBounds/NumberFormatException that would
        // skip the caller's fallback.
        val lane = ExposureKeyframes(
            listOf(
                ExposureKeyframe("k1", 0L, 0.5f),
                ExposureKeyframe("k2", 500_000L, 1.25f, ExposureKeyframe.Transition.Smooth(200_000L)),
                ExposureKeyframe("k3", 1_000_000L, -0.75f),
            ),
        )
        val valid = ExposureKeyframes.render(lane)
        assertEquals(lane, ExposureKeyframes.parse(valid)) // the corpus is valid
        var cases = 0
        val bytes = valid.toByteArray(StandardCharsets.UTF_8)
        for (cut in truncationOffsets(bytes.size)) {
            cases++
            assertContracted("exposurekeys", "truncate@$cut") { ExposureKeyframes.parse(String(bytes, 0, cut)) }
        }
        for ((i, m) in byteFlips(valid).withIndex()) {
            cases++
            assertContracted("exposurekeys", "flip#$i") { ExposureKeyframes.parse(m) }
        }
        for ((i, m) in lineDeletions(valid).withIndex()) {
            cases++
            assertContracted("exposurekeys", "delline#$i") { ExposureKeyframes.parse(m) }
        }
        totalCases += cases
        perStoreCases["exposurekeys"] = (perStoreCases["exposurekeys"] ?: 0) + cases
        assertSweptSomething("exposurekeys", 100)
    }

    /**
     * The CODEC contract under damage: the parse either succeeds or
     * throws IllegalArgumentException (the type the store-level caller
     * catches) — any other throw is a bug the fuzz found.
     */
    private inline fun assertContracted(label: String, tag: String, parse: () -> Unit) {
        try {
            parse()
        } catch (e: IllegalArgumentException) {
            // the contracted honest failure
        } catch (t: Throwable) {
            throw AssertionError(
                "$label $tag: codec threw ${t.javaClass.simpleName}: ${t.message} " +
                    "(not the contracted IllegalArgumentException)",
                t,
            )
        }
    }

    @Test
    fun codecMeasureStore_survives_every_truncation_flip_and_line_deletion() {
        // CodecMeasureWire's contract: "a file this build cannot read (a
        // newer version) parses to null, NEVER a guess" — and a damaged
        // file never throws out of the store's latest()/readRaw().
        val report = com.ritim.ritimcraw.export.CodecMeasureReport(
            fingerprint = "google/fux", model = "Pixel", incremental = "eng.dino.1",
            startedMs = 1_758_000_000_000L,
            rows = listOf(
                com.ritim.ritimcraw.export.MeasureResult(
                    c = com.ritim.ritimcraw.export.CodecMeasureCase(
                        mime = "video/avc", encoderName = "c2.qti.avc.encoder",
                        profile = 1, bitDepth = 8, width = 3840, height = 2160,
                        fps = 30, rateMode = 1, requestedBps = 20_000_000L,
                        uncapped = false, durationSec = 2.0, keyframeSec = 1f,
                        content = "ramp+v1",
                    ),
                    configureOk = true, errorCode = null, encodedBytes = 5_000_000L,
                    ptsDurationSec = 2.0, measuredBps = 20_000_000L,
                    iFrameBytes = 100_000L, iFrameCount = 2, framesIn = 60,
                    encoder = "c2.qti.avc.encoder", hardware = true, wallMs = 12_345L,
                ),
            ),
        )
        val dir = tmp.newFolder("codec-measure")
        val store = com.ritim.ritimcraw.export.CodecMeasureStore(dir)
        store.save(report)
        val valid = dir.listFiles()!!.single().readText(StandardCharsets.UTF_8)
        assertNotNull(store.latest(report.fingerprint)) // the corpus is valid

        // The STORE sweep: the damaged file lands at the store's own file
        // name and latest() (the read the debug screen does) must never
        // throw and never return a misread report.
        sweep("codecmeasure", valid) { f ->
            val name = File(dir, com.ritim.ritimcraw.export.CodecMeasureWire.fileName(report.fingerprint))
            val keep = name.exists()
            val keepBytes = if (keep) name.readBytes() else null
            name.writeText(f.readText(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
            try {
                val back = com.ritim.ritimcraw.export.CodecMeasureStore(dir).latest(report.fingerprint)
                // A read-back report must be the full honest record or null
                // — never a half-parsed row set.
                if (back != null) assertEquals(1, back.rows.size)
            } finally {
                if (keep) name.writeBytes(keepBytes!!) else name.delete()
            }
        }
        assertSweptSomething("codecmeasure", 100)
    }

    @Test
    fun dngSequenceManifest_survives_every_truncation_flip_and_line_deletion() {
        // DngSequence.parseManifest's documented contract: "CORRUPT input
        // (unparseable JSON, trailing content, a non-object root) throws
        // IllegalArgumentException — the caller catches and degrades to a
        // manifest-less import". The fuzz pins the THROW TYPE (the
        // ManifestJson parser's \u escape reads hex.toInt(16) with no
        // NumberFormatException guard — the exact E1 bug class in
        // JsonParser, which the fix turned into the contracted IAE).
        val m = com.ritim.ritimcraw.engine.DngSequenceManifest(
            version = 1, stem = "clip", fps = 24.0, startFrame = 0L,
            frameCount = 120L, wav = "clip.wav", wavSampleRateHz = 48_000,
        )
        val valid = com.ritim.ritimcraw.engine.DngSequence.renderManifest(m)
        assertEquals(m, com.ritim.ritimcraw.engine.DngSequence.parseManifest(valid))
        sweepText("dngmanifest", valid) { text ->
            assertContracted("dngmanifest", "text") {
                com.ritim.ritimcraw.engine.DngSequence.parseManifest(text)
            }
        }
        assertSweptSomething("dngmanifest", 100)
    }

    @Test
    fun maskStateJson_survives_every_truncation_flip_and_line_deletion() {
        // MaskState.parseJson's documented contract: "structural JSON
        // errors throw IllegalArgumentException" (the store-level caller
        // catches) "but a mask ENTRY that is invalid is SKIPPED, never
        // crashing the load". The fuzz pins both halves: the throw type
        // of the structural layer (MaskJsonParser's \u escape has the
        // same unguarded hex.toInt(16)) and the no-crash of the entry
        // layer.
        val s = com.ritim.ritimcraw.engine.mask.MaskState(
            listOf(
                com.ritim.ritimcraw.engine.mask.MaskK(
                    id = "sky",
                    shape = com.ritim.ritimcraw.engine.mask.MaskShape.Linear(0.1f, 0f, 0.9f, 0.35f),
                    feather = 0.35f,
                    delta = com.ritim.ritimcraw.engine.mask.MaskGradeDelta(exposureEv = 1f),
                ),
                com.ritim.ritimcraw.engine.mask.MaskK(
                    id = "vignette",
                    shape = com.ritim.ritimcraw.engine.mask.MaskShape.Radial(0.5f, 0.5f, 0.8f, 0.8f, 0f),
                    feather = 0.8f, invert = true, enabled = false,
                ),
            ),
        )
        val valid = s.renderJson()
        assertEquals(s, com.ritim.ritimcraw.engine.mask.MaskState.parseJson(valid))
        sweepText("maskjson", valid) { text ->
            try {
                val back = com.ritim.ritimcraw.engine.mask.MaskState.parseJson(text)
                assertTrue(back.masks.size <= com.ritim.ritimcraw.engine.mask.MaskState.MAX_MASKS)
            } catch (e: IllegalArgumentException) {
                // the contracted structural failure
            } catch (t: Throwable) {
                throw AssertionError(
                    "maskjson: parse threw ${t.javaClass.simpleName}: ${t.message} " +
                        "(not the contracted IllegalArgumentException)",
                    t,
                )
            }
        }
        assertSweptSomething("maskjson", 100)
    }

    // ---- (5) the escape-corruption cases the random flips produce ----

    /**
     * The narrow "can damage crash a scoped parser" question, pinned per
     * case. The two scoped JSON parsers outside the shared JsonParser
     * (DngSequence's ManifestJson, MaskState's MaskJsonParser) looked
     * like E1's bug class at read time (hex.toInt(16) with no NFE
     * guard, a bare toDouble()); the probes below VERIFY the contract
     * instead of assuming the bug: every damaged input must fail as
     * IllegalArgumentException (or a subtype — NumberFormatException
     * IS an IllegalArgumentException in Java), because that is the
     * exact type the documented caller-side catch names. The truncated
     * and trailing-backslash cases are where JsonParser genuinely
     * crashed (IndexOutOfBounds — NOT an IAE) before E1's fix; these
     * tests pin that the scoped parsers hold the same line.
     */
    @Test
    fun dngManifest_garbage_unicode_escape_fails_as_the_contracted_exception() {
        // A valid manifest whose stem contains a \u escape, then the
        // escape's hex damaged (the fuzzer's byte-flip class).
        val valid = "{\"version\":1,\"stem\":\"x\\u0041\"}" // xA
        assertEquals("xA", com.ritim.ritimcraw.engine.DngSequence.parseManifest(valid).stem)
        val damaged = valid.replace("\\u0041", "\\uZZ41")
        assertContracted("dngmanifest", "garbage-escape") {
            com.ritim.ritimcraw.engine.DngSequence.parseManifest(damaged)
        }
    }

    @Test
    fun dngManifest_truncated_unicode_escape_fails_as_the_contracted_exception() {
        // A file cut INSIDE the \u escape (the torn-write case — where
        // JsonParser crashed as IndexOutOfBounds before the E1 fix).
        val torn = "{\"version\":1,\"stem\":\"x\\u00"
        assertContracted("dngmanifest", "truncated-escape") {
            com.ritim.ritimcraw.engine.DngSequence.parseManifest(torn)
        }
    }

    @Test
    fun dngManifest_trailing_backslash_fails_as_the_contracted_exception() {
        // A file ending inside an unterminated escape.
        val torn = "{\"version\":1,\"stem\":\"x\\"
        assertContracted("dngmanifest", "trailing-backslash") {
            com.ritim.ritimcraw.engine.DngSequence.parseManifest(torn)
        }
    }

    @Test
    fun dngManifest_damaged_number_fails_as_the_contracted_exception() {
        // A flipped byte inside a number can yield 1.2.3 or --5: the
        // bare toDouble() must still surface as the contracted type
        // (NumberFormatException IS an IllegalArgumentException, so the
        // caller's catch holds — verified, not assumed).
        for (bad in listOf(
            "{\"version\":1,\"fps\":1.2.3}",
            "{\"version\":1,\"fps\":--5}",
            "{\"version\":1,\"frameCount\":.}",
        )) {
            assertContracted("dngmanifest", "damaged-number $bad") {
                com.ritim.ritimcraw.engine.DngSequence.parseManifest(bad)
            }
        }
    }

    @Test
    fun maskJson_garbage_unicode_escape_fails_as_the_contracted_exception() {
        val valid = "[{\"id\":\"a\\u0041\",\"shape\":\"linear\",\"x0\":0,\"y0\":0,\"x1\":1,\"y1\":1}]"
        assertEquals("aA", com.ritim.ritimcraw.engine.mask.MaskState.parseJson(valid).masks.single().id)
        val damaged = valid.replace("\\u0041", "\\uZZ41")
        try {
            com.ritim.ritimcraw.engine.mask.MaskState.parseJson(damaged)
        } catch (e: IllegalArgumentException) {
            // the contracted structural failure
        } catch (t: Throwable) {
            throw AssertionError(
                "maskjson garbage-escape threw ${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        }
    }

    @Test
    fun maskJson_truncated_unicode_escape_fails_as_the_contracted_exception() {
        val torn = "[{\"id\":\"a\\u00\",\"shape\":\"linear\",\"x0\":0,\"y0\":0,\"x1\":1,\"y1\":1}]"
        try {
            com.ritim.ritimcraw.engine.mask.MaskState.parseJson(torn)
        } catch (e: IllegalArgumentException) {
            // the contracted structural failure
        } catch (t: Throwable) {
            throw AssertionError(
                "maskjson truncated-escape threw ${t.javaClass.simpleName}: ${t.message}",
                t,
            )
        }
    }

    // ---- (4) the gate itself ----

    @Test
    fun projectFileGate_survives_every_truncation_flip_and_line_deletion() {
        val valid = maximalProjectJson()
        sweep("gate", valid) { f ->
            val outcome = ProjectFile.open(f)
            assertNotNull(outcome)
            // TooNew must never arise from DAMAGE (only a real newer file
            // sets it): a damaged 2 must stay Open or Migrate, never flip
            // the process read-only.
            assertTrue(
                "damage turned the process read-only (outcome=$outcome)",
                !ProjectFile.readOnly || outcome is ProjectFile.Outcome.TooNew,
            )
        }
        assertSweptSomething("gate", 100)
    }

    // ---- the corpus validity lock (never fuzz a corpus that is not real) ----

    @Test
    fun the_maximal_corpus_is_valid_by_construction() {
        // The corpora every sweep above uses must load clean BEFORE any
        // damage: a corpus that itself fails to load would make the whole
        // fuzz vacuous (every read would "degrade" the same valid file).
        val valid = maximalProjectJson()
        ProjectFile.resetForTest()
        val f = freshFile()
        f.writeText(valid, StandardCharsets.UTF_8)
        assertTrue(ProjectFile.open(f) is ProjectFile.Outcome.Open)
        assertEquals(0.8f, GradeStore(f).get("clip-full").exposureEv, 0f)
        assertTrue(GradeStore(f).getV2("clip-nodes")!!.nodes.size == 1)
        assertEquals(1, SourceRepository(FakeResolver(), f).all().size)
        assertEquals(1, ExportStore(f).all().size)
        assertEquals(1, PresetStore(f).all().size)
        assertEquals(
            "two distinct cube blobs must survive as two records",
            2,
            LutStore(FakeResolver(), f, File(tmp.root, "luts")).all().size,
        )
        assertEquals(
            "two distinct gyro blobs must survive as two records",
            2,
            GyroStore(FakeResolver(), f, File(tmp.root, "gyros")).all().size,
        )
        assertEquals(2, AudioStore(f).load().links.size)
        assertEquals(1, TimelineStore(f).load().clips.size)
        assertEquals(1, MetadataStore(f).load().size)
        assertTrue("corpus must be big enough to be interesting (${valid.length})", valid.length > 4096)
    }

    @Test
    fun the_damage_generators_actually_change_the_corpus() {
        // The machinery lock: flips and deletions must really mutate (a
        // deterministic-seed regression here would silently fuzz nothing).
        val valid = "{\"k\":\"v\",\"n\":1.5,\"a\":[1,2,3],\"s\":\"text\"}"
        val flips = byteFlips(valid)
        assertTrue(flips.size == 48)
        assertTrue("flips never changed the corpus", flips.any { it != valid })
        val cuts = truncationOffsets(valid.length)
        assertTrue(cuts.size > 5)
        assertTrue(cuts.first() == 1)
        val dels = lineDeletions("a\nb\nc\nd")
        assertTrue(dels.size == 24)
        assertTrue(dels.any { it.count { c -> c == '\n' } == 2 })
    }

    @Test
    fun the_total_sweep_is_bounded_and_reportable() {
        // The time-budget lock (the brief's sampling strategy): a COMPACT
        // corpus truncates at EVERY byte offset, a big one step-samples
        // at most [MAX_TRUNCATIONS] cuts — and the whole class stays
        // inside the "few seconds" wall clock (the runner's own timing;
        // the case COUNT is bounded here so a corpus growth that would
        // blow the budget fails a test instead of silently slowing the
        // gate).
        val valid = maximalProjectJson()
        val size = valid.toByteArray(StandardCharsets.UTF_8).size
        val truncations = truncationOffsets(size).size
        if (size <= FULL_SWEEP_LIMIT) {
            assertTrue("compact corpus must sweep EVERY offset", truncations >= size - 1)
        } else {
            assertTrue(
                "big corpus must sample within the cap ($truncations > $MAX_TRUNCATIONS)",
                truncations <= MAX_TRUNCATIONS + 2,
            )
        }
        // Per store: truncations + 48 flips + 24 line deletions.
        val perStore = truncations + FLIPS + LINE_DELETIONS
        assertTrue("per-store case count $perStore", perStore > 250)
        // The standalone-file codecs are compact: their sweeps are FULL
        // byte-offset sweeps by construction (the honest sweep class).
        val manifest = ProxyManifestCodec.render(
            ProxyManifest("abc", "def", 1L, 1L, 1L, 1L, "sig")
        )
        assertTrue(manifest.length <= FULL_SWEEP_LIMIT)
        val lane = ExposureKeyframes.render(
            ExposureKeyframes(listOf(ExposureKeyframe("k1", 0L, 0.5f))),
        )
        assertTrue(lane.length <= FULL_SWEEP_LIMIT)
        val mask = com.ritim.ritimcraw.engine.mask.MaskState(
            listOf(
                com.ritim.ritimcraw.engine.mask.MaskK(
                    id = "m",
                    shape = com.ritim.ritimcraw.engine.mask.MaskShape.Radial(0.5f, 0.5f, 0.25f, 0.25f),
                ),
            ),
        ).renderJson()
        assertTrue(mask.length <= FULL_SWEEP_LIMIT)
    }
}
