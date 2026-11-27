package com.ritim.ritimcraw.proxy

import com.ritim.ritimcraw.data.ProxyModeStore
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Proxy UX: mode persistence/default, enqueue-or-ask decision per mode, manual-action visibility, status mapping. */
class ProxyUxTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val clip4k60 = ProxyClipInfo(ProxySourceKind.ENCODED, 3840, 2160, 60.0)
    private val clip4k30 = ProxyClipInfo(ProxySourceKind.ENCODED, 3840, 2160, 30.0)
    private val clip8k = ProxyClipInfo(ProxySourceKind.ENCODED, 7680, 4320, 24.0)
    private val clipFhd = ProxyClipInfo(ProxySourceKind.ENCODED, 1920, 1080, 60.0)
    private val mcraw = ProxyClipInfo(ProxySourceKind.MCRAW, 4000, 3000, 30.0)

    private fun decide(
        mode: ProxyMode,
        clip: ProxyClipInfo?,
        trigger: ProxyTrigger = ProxyTrigger.IMPORT,
        busy: Boolean = false,
        asked: Boolean = false,
    ) = ProxyImportDecision.decide(mode, trigger, clip, busy, asked)

    // ---- settings persistence ----

    @Test
    fun mode_defaults_to_off() {
        assertEquals(ProxyMode.OFF, ProxyMode.DEFAULT)
        val f = File(tmp.newFolder(), "project.json")
        assertEquals(ProxyMode.OFF, ProxyModeStore(f).get())
    }

    @Test
    fun mode_round_trips_and_default_is_omitted() {
        val f = File(tmp.newFolder(), "project.json")
        val store = ProxyModeStore(f)
        store.put(ProxyMode.ASK)
        assertEquals(ProxyMode.ASK, ProxyModeStore(f).get())
        store.put(ProxyMode.AUTOMATIC)
        assertEquals(ProxyMode.AUTOMATIC, ProxyModeStore(f).get())
        store.put(ProxyMode.OFF)
        assertFalse(f.readText().contains("proxyMode"))
        assertEquals(ProxyMode.OFF, ProxyModeStore(f).get())
    }

    @Test
    fun mode_keeps_other_top_level_entries_and_survives_garbage() {
        val f = File(tmp.newFolder(), "project.json")
        f.writeText("""{"version":1,"previewQuality":"HIGH"}""", StandardCharsets.UTF_8)
        val store = ProxyModeStore(f)
        store.put(ProxyMode.ASK)
        assertTrue(f.readText().contains("\"previewQuality\":\"HIGH\""))
        f.writeText("""{"version":1,"proxyMode":"NOPE"}""", StandardCharsets.UTF_8)
        assertEquals(ProxyMode.OFF, store.get())
        f.writeText("{broken", StandardCharsets.UTF_8)
        assertEquals(ProxyMode.OFF, store.get())
    }

    @Test
    fun debug_property_lifts_off_to_automatic_only() {
        assertEquals(ProxyMode.AUTOMATIC, ProxyMode.effective(ProxyMode.OFF, debugForcedOn = true))
        assertEquals(ProxyMode.OFF, ProxyMode.effective(ProxyMode.OFF, debugForcedOn = false))
        assertEquals(ProxyMode.ASK, ProxyMode.effective(ProxyMode.ASK, debugForcedOn = true))
        assertEquals(ProxyMode.ASK, ProxyMode.effective(ProxyMode.ASK, debugForcedOn = false))
    }

    // ---- enqueue / ask decision ----

    @Test
    fun off_never_enqueues_or_asks() {
        for (t in ProxyTrigger.entries) {
            assertEquals(ProxyAction.NONE, decide(ProxyMode.OFF, clip4k60, t))
            assertEquals(ProxyAction.NONE, decide(ProxyMode.OFF, clip8k, t))
        }
    }

    @Test
    fun automatic_enqueues_eligible_clips_only() {
        assertEquals(ProxyAction.ENQUEUE, decide(ProxyMode.AUTOMATIC, clip4k60))
        assertEquals(ProxyAction.ENQUEUE, decide(ProxyMode.AUTOMATIC, clip8k))
        assertEquals(ProxyAction.ENQUEUE, decide(ProxyMode.AUTOMATIC, clip4k60, ProxyTrigger.EDITOR_OPEN))
        assertEquals(ProxyAction.ENQUEUE, decide(ProxyMode.AUTOMATIC, clip4k60, ProxyTrigger.START_SCAN))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, clip4k30))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, clipFhd))
    }

    @Test
    fun mcraw_is_never_automatic_or_offered() {
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, mcraw))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.ASK, mcraw))
    }

    @Test
    fun ask_offers_eligible_clips_on_import_and_open_once() {
        assertEquals(ProxyAction.ASK, decide(ProxyMode.ASK, clip4k60, ProxyTrigger.IMPORT))
        assertEquals(ProxyAction.ASK, decide(ProxyMode.ASK, clip8k, ProxyTrigger.EDITOR_OPEN))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.ASK, clip4k60, ProxyTrigger.IMPORT, asked = true))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.ASK, clip4k30))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.ASK, clipFhd))
    }

    @Test
    fun ask_never_prompts_at_app_start() {
        assertEquals(ProxyAction.NONE, decide(ProxyMode.ASK, clip4k60, ProxyTrigger.START_SCAN))
    }

    @Test
    fun built_or_queued_and_unknown_clips_do_nothing() {
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, clip4k60, busy = true))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.ASK, clip4k60, busy = true))
        // facts not measured yet (no metadata) and a clip with an unknown fps
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, null))
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, ProxyClipInfo(ProxySourceKind.ENCODED, 3840, 2160, 0.0)))
    }

    // ---- manual actions ----

    @Test
    fun manual_actions_hidden_only_when_off_and_work_for_mcraw() {
        assertFalse(ProxyImportDecision.manualActionsVisible(ProxyMode.OFF))
        assertTrue(ProxyImportDecision.manualActionsVisible(ProxyMode.ASK))
        assertTrue(ProxyImportDecision.manualActionsVisible(ProxyMode.AUTOMATIC))
        // the manual route has no eligibility rule: the item shows for every clip kind
        // while the auto rule still says no for MCRAW
        assertEquals(ProxyAction.NONE, decide(ProxyMode.AUTOMATIC, mcraw))
    }

    @Test
    fun create_and_delete_follow_the_status() {
        val none = ProxyStatus.None
        assertTrue(ProxyImportDecision.canCreate(none))
        assertTrue(ProxyImportDecision.canCreate(ProxyStatus.Failed))
        assertFalse(ProxyImportDecision.canCreate(ProxyStatus.Ready))
        assertFalse(ProxyImportDecision.canCreate(ProxyStatus.Queued))
        assertFalse(ProxyImportDecision.canCreate(ProxyStatus.Preparing(10)))
        assertFalse(ProxyImportDecision.canDelete(none))
        assertTrue(ProxyImportDecision.canDelete(ProxyStatus.Ready))
        assertTrue(ProxyImportDecision.canDelete(ProxyStatus.Queued))
        assertTrue(ProxyImportDecision.canDelete(ProxyStatus.Preparing(10)))
        assertTrue(ProxyImportDecision.canDelete(ProxyStatus.Failed))
    }

    // ---- status mapping ----

    @Test
    fun status_mapping() {
        assertEquals(ProxyStatus.None, ProxyStatusMapper.of(ProxyState.None, false, null))
        assertEquals(ProxyStatus.None, ProxyStatusMapper.of(ProxyState.Cancelled, false, null))
        assertEquals(ProxyStatus.Queued, ProxyStatusMapper.of(ProxyState.Queued, false, null))
        assertEquals(ProxyStatus.Preparing(0), ProxyStatusMapper.of(ProxyState.Building, false, null))
        assertEquals(ProxyStatus.Preparing(42), ProxyStatusMapper.of(ProxyState.Building, false, 42))
        // a build never shows 100 before it is Ready
        assertEquals(ProxyStatus.Preparing(99), ProxyStatusMapper.of(ProxyState.Building, false, 100))
        assertEquals(ProxyStatus.Ready, ProxyStatusMapper.of(ProxyState.Ready, true, null))
        assertEquals(ProxyStatus.Failed, ProxyStatusMapper.of(ProxyState.Failed, false, null))
        // a proxy built earlier (previous session) shows as ready without a queue entry
        assertEquals(ProxyStatus.Ready, ProxyStatusMapper.of(ProxyState.None, true, null))
        // a failed REbuild next to a good proxy still has a playable proxy
        assertEquals(ProxyStatus.Ready, ProxyStatusMapper.of(ProxyState.Failed, true, null))
    }

    @Test
    fun percent_is_clamped_and_whole() {
        assertEquals(0, ProxyStatusMapper.percent(0, 0))
        assertEquals(0, ProxyStatusMapper.percent(5, -1))
        assertEquals(50, ProxyStatusMapper.percent(50, 100))
        assertEquals(33, ProxyStatusMapper.percent(1, 3))
        assertEquals(100, ProxyStatusMapper.percent(120, 100))
    }

    @Test
    fun progress_log_fires_once_per_ten_percent() {
        val t = ProxyProgressLogThrottle(10)
        val fired = (0..100).filter { t.shouldLog(it) }
        assertEquals(listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100), fired)
        assertFalse(t.shouldLog(100))
        t.reset()
        assertTrue(t.shouldLog(5))
    }

    @Test
    fun manual_retry_forgives_session_failures() {
        val q = ProxyStateMap()
        val key = ProxyKey("fp", "hash")
        q.enqueue(key)
        q.next()
        q.failed(key, "x")
        q.enqueue(key)
        q.next()
        q.failed(key, "y")
        assertFalse(q.enqueue(key))
        q.forgiveFailures(key)
        assertTrue(q.enqueue(key))
    }
}
