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
        // v3 since the E5-B audio shape fields landed).
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
        // the E5-B audio shape stay SOURCE — the v1 behavior, exactly.
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
        assertNull(back.settings!!.audioSampleRateHz)
        assertNull(back.settings!!.audioChannels)
    }

    @Test
    fun a_queued_job_with_v2_settings_keeps_running_its_exact_old_settings() {
        // (E5-B, night G1) A row this app's v2-writing build produced
        // (the D2 loudness fields present, no audio shape keys): the
        // reader accepts 1..3 and the loudness choice SURVIVES while the
        // audio shape parses as SOURCE — never a guess either way.
        val v2 = "\"settings\":{" +
            "\"v\":2,\"codec\":\"AVC\",\"bitDepth\":8," +
            "\"dynamicRange\":\"SDR\",\"rateMode\":\"VBR\"," +
            "\"bitrateLevel\":\"STANDARD\",\"uncapped\":false," +
            "\"keyframe\":\"AUTO\",\"audioKbps\":192," +
            "\"loudnessTargetLufs\":-14.0,\"truePeakDbtp\":-1.0}"
        val row = ExportStore.renderExports(listOf(job(ExportSettings())))
            .replace(Regex("\"settings\":\\{[^}]*\\}"), v2)
        assertTrue(row, row.contains("\"v\":2"))
        val back = ExportStore.parseExports("{\"exports\":$row}").single()
        assertEquals(State.QUEUED, back.state)
        assertEquals(-14f, back.settings!!.loudnessTargetLufs)
        assertEquals(-1f, back.settings!!.truePeakDbtp)
        assertNull(back.settings!!.audioSampleRateHz)
        assertNull(back.settings!!.audioChannels)
    }

    @Test
    fun a_finished_job_with_unreadable_settings_keeps_its_state() {
        val text = ExportStore.renderExports(listOf(job(ExportSettings(), State.DONE))).replace("\"AVC\"", "\"AV1\"")
        val back = ExportStore.parseExports("{\"exports\":$text}").single()
        assertEquals(State.DONE, back.state)
        assertNull(back.settings)
    }
}
