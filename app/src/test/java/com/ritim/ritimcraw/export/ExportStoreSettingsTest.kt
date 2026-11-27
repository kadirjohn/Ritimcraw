package com.ritim.ritimcraw.export

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ExportJob.settings in project.json: null-default, byte-identical legacy rows, honest handling of unreadable ones. */
class ExportStoreSettingsTest {

    private fun job(settings: ExportSettings?, state: State = State.QUEUED) = ExportJob(
        id = "j1", sourceId = "s1", snapshot = GradeK(), target = Target.MP4, state = state, settings = settings,
    )

    private fun roundTrip(j: ExportJob): ExportJob =
        ExportStore.parseExports("{\"exports\":" + ExportStore.renderExports(listOf(j)) + "}").single()

    @Test
    fun a_job_without_settings_writes_no_settings_key_so_old_files_stay_byte_identical() {
        val text = ExportStore.renderExports(listOf(job(null)))
        assertFalse(text, text.contains("settings"))
        // and the same job with the field simply absent parses back as null
        assertNull(roundTrip(job(null)).settings)
    }

    @Test
    fun settings_round_trip_through_the_store() {
        val s = ExportSettings(
            codec = ExportCodec.HEVC, rateMode = ExportRateMode.CBR, bitrateLevel = BitrateLevel.CUSTOM,
            customMbps = 120.0, uncapped = true, keyframe = KeyframeInterval.HALF, profile = 2, audioKbps = 256,
        )
        val back = roundTrip(job(s))
        assertEquals(s, back.settings)
        assertEquals(State.QUEUED, back.state)
        assertTrue(ExportStore.renderExports(listOf(job(s))).contains("\"settings\":{\"v\":3,"))
    }

    @Test
    fun retry_keeps_the_settings() {
        val s = ExportSettings(codec = ExportCodec.HEVC)
        assertEquals(s, job(s, State.FAILED).requeued().settings)
    }

    @Test
    fun a_queued_job_with_settings_a_newer_app_wrote_is_failed_not_run_as_legacy() {
        // The unknown-version case is now v4 (this build writes and reads
        // v3 since the E5 loudness and E1f target fps fields both landed).
        val text = ExportStore.renderExports(listOf(job(ExportSettings()))).replace("\"v\":3", "\"v\":4")
        val back = ExportStore.parseExports("{\"exports\":$text}").single()
        assertNull(back.settings)
        assertEquals(State.FAILED, back.state)
        assertTrue(back.detail!!, back.detail!!.contains("settings version 4"))
        assertTrue(back.detail!!.contains("recovery"))
    }

    @Test
    fun a_queued_job_with_v1_settings_keeps_running_its_exact_old_settings() {
        // A row this app's v1-writing build produced: parses (the reader
        // accepts 1..3), keeps every field, and the E5 loudness chain and
        // the fps conversion both stay OFF — the v1 behavior, exactly
        // (plan E1F §3.2).
        val v1 = "\"settings\":{" +
            "\"v\":1,\"codec\":\"AVC\",\"bitDepth\":8," +
            "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
            "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
            "\"keyframe\":\"AUTO\",\"audioKbps\":192}"
        val row = ExportStore.renderExports(listOf(job(ExportSettings())))
            .replace(Regex("\"settings\":\\{[^}]*\\}"), v1)
        assertTrue(row, row.contains("\"v\":1"))
        val back = ExportStore.parseExports("{\"exports\":$row}").single()
        assertEquals(State.QUEUED, back.state)
        assertEquals(ExportSettings(codec = ExportCodec.AVC, audioKbps = 192), back.settings)
        assertNull(back.settings!!.loudnessTargetLufs)
        assertEquals(TargetFps.SOURCE, back.settings!!.targetFps)
    }

    @Test
    fun a_finished_job_with_unreadable_settings_keeps_its_state() {
        val text = ExportStore.renderExports(listOf(job(ExportSettings(), State.DONE))).replace("\"AVC\"", "\"AV1\"")
        val back = ExportStore.parseExports("{\"exports\":$text}").single()
        assertEquals(State.DONE, back.state)
        assertNull(back.settings)
    }

    // ---- the integrated wire chain (I1): v1, v2 and v3 side by side ----

    /** One store row with the given literal `settings`, in the store's own shape. */
    private fun rowWith(id: String, settings: String, state: State = State.QUEUED): String =
        ExportStore.renderExports(listOf(job(ExportSettings(), state)))
            .removePrefix("[").removeSuffix("]")
            .replace(Regex("\"id\":\"[^\"]*\""), "\"id\":\"$id\"")
            .replace(Regex("\"settings\":\\{[^}]*\\}"), settings)

    @Test
    fun a_mixed_file_with_v1_v2_and_v3_rows_reads_each_row_with_its_own_meaning() {
        // One exports.json from a long-lived install: a v1 row (pre-E5,
        // pre-E1f), a v2 row (the loudness build, no fps key), and a v3
        // row (this build, both fields). Every row keeps its OWN meaning:
        // v1 = loudness OFF + SOURCE fps, v2 = the loudness target with
        // SOURCE fps, v3 = both. No row is failed and nothing is guessed.
        fun settings(v: Int, extra: String = "") =
            "\"settings\":{" +
                "\"v\":$v,\"codec\":\"AVC\",\"bitDepth\":8," +
                "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
                "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
                "\"keyframe\":\"AUTO\",\"audioKbps\":192$extra}"
        val file = "{\"exports\":[" +
            rowWith("j1", settings(1)) + "," +
            rowWith("j2", settings(2, ",\"loudnessTargetLufs\":-14.0,\"truePeakDbtp\":-1.0,\"resolutionW\":1080,\"resolutionH\":1920")) + "," +
            rowWith("j3", settings(3, ",\"loudnessTargetLufs\":-16.0,\"truePeakDbtp\":-1.5,\"resolutionW\":1080,\"resolutionH\":1920,\"targetFps\":\"FPS_30\"")) +
            "]}"
        val back = ExportStore.parseExports(file)
        assertEquals(3, back.size)

        val v1 = back[0]
        assertEquals(State.QUEUED, v1.state)
        assertEquals(ExportSettings(codec = ExportCodec.AVC, audioKbps = 192), v1.settings)
        assertNull(v1.settings!!.loudnessTargetLufs)
        assertEquals(TargetFps.SOURCE, v1.settings!!.targetFps)

        val v2 = back[1]
        assertEquals(State.QUEUED, v2.state)
        assertEquals(-14f, v2.settings!!.loudnessTargetLufs)
        assertEquals(-1f, v2.settings!!.truePeakDbtp)
        assertEquals(1080, v2.settings!!.resolutionW)
        assertEquals(1920, v2.settings!!.resolutionH)
        // A v2 row never had a targetFps key: SOURCE is its own meaning,
        // not a guess.
        assertEquals(TargetFps.SOURCE, v2.settings!!.targetFps)

        val v3 = back[2]
        assertEquals(State.QUEUED, v3.state)
        assertEquals(-16f, v3.settings!!.loudnessTargetLufs)
        assertEquals(-1.5f, v3.settings!!.truePeakDbtp)
        assertEquals(1080, v3.settings!!.resolutionW)
        assertEquals(1920, v3.settings!!.resolutionH)
        assertEquals(TargetFps.FPS_30, v3.settings!!.targetFps)
    }

    @Test
    fun the_too_new_v4_row_is_failed_with_an_honest_reason_not_run() {
        // v4 is beyond this build's READS: a queued v4 row must NEVER run
        // as legacy — it is FAILED with the reason, exactly the honest
        // refusal both branches carried; the store repeats the wire's own
        // rejection.
        fun settings(v: Int) =
            "\"settings\":{" +
                "\"v\":$v,\"codec\":\"AVC\",\"bitDepth\":8," +
                "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
                "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
                "\"keyframe\":\"AUTO\",\"audioKbps\":192}"
        val file = "{\"exports\":[" + rowWith("j4", settings(4)) + "]}"
        val back = ExportStore.parseExports(file).single()
        assertEquals(State.FAILED, back.state)
        assertNull(back.settings)
        assertTrue(back.detail!!, back.detail!!.contains("settings version 4"))
        assertTrue(back.detail!!.contains("recovery"))
    }
}
