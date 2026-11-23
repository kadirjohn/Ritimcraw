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
        assertTrue(ExportStore.renderExports(listOf(job(s))).contains("\"settings\":{\"v\":1,"))
    }

    @Test
    fun retry_keeps_the_settings() {
        val s = ExportSettings(codec = ExportCodec.HEVC)
        assertEquals(s, job(s, State.FAILED).requeued().settings)
    }

    @Test
    fun a_queued_job_with_settings_a_newer_app_wrote_is_failed_not_run_as_legacy() {
        val text = ExportStore.renderExports(listOf(job(ExportSettings()))).replace("\"v\":1", "\"v\":2")
        val back = ExportStore.parseExports("{\"exports\":$text}").single()
        assertNull(back.settings)
        assertEquals(State.FAILED, back.state)
        assertTrue(back.detail!!, back.detail!!.contains("settings version 2"))
        assertTrue(back.detail!!.contains("recovery"))
    }

    @Test
    fun a_finished_job_with_unreadable_settings_keeps_its_state() {
        val text = ExportStore.renderExports(listOf(job(ExportSettings(), State.DONE))).replace("\"AVC\"", "\"AV1\"")
        val back = ExportStore.parseExports("{\"exports\":$text}").single()
        assertEquals(State.DONE, back.state)
        assertNull(back.settings)
    }
}
