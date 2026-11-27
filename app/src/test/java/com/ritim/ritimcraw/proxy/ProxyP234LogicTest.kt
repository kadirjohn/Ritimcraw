package com.ritim.ritimcraw.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * E2 pure-logic tests: the queue state machine, the thermal/battery policy table, the
 * runner priority (export > playing clip > other), the playable/half-proxy switch rule and
 * the "X GB over cap" feed. Everything JVM-pure; no Android, no threads, no encode.
 */
class ProxyRunPolicyTest {

    // ---- the policy table (docs/PROXY_PLAN.md section 4) ----

    private val p = ProxyRunPolicy()
    private fun run(
        headroom: Float? = null, battery: Int? = null, charging: Boolean = false,
        export: Boolean = false, prev: ProxyRunDecision = ProxyRunDecision.RUNNING,
    ) = p.decide(ProxyPowerState(headroom, battery, charging, export), prev)

    @Test fun exportAlwaysWinsOverEverything() {
        // even a cool, full, charging device stands down while an export runs
        assertEquals(ProxyRunAction.PAUSE, run(export = true, headroom = 0.0f, battery = 100, charging = true).action)
        assertEquals(ProxyRunReason.EXPORT, run(export = true).reason)
    }

    @Test fun thermalLadderPausesAtSevereSlowsAtModerate() {
        assertEquals(ProxyRunAction.RUN, run(headroom = 0.5f).action)
        assertEquals(ProxyRunAction.SLOW, run(headroom = 0.70f).action)   // MODERATE band begins
        assertEquals(ProxyRunAction.SLOW, run(headroom = 0.84f).action)  // still below SEVERE
        assertEquals(ProxyRunAction.PAUSE, run(headroom = 0.85f).action) // SEVERE
        assertEquals(ProxyRunReason.THERMAL, run(headroom = 0.85f).reason)
    }

    @Test fun thermalUnknownNeverObjects() {
        assertEquals(ProxyRunAction.RUN, run(headroom = null).action)
        assertEquals(ProxyRunAction.RUN, run(headroom = Float.NaN).action)
        // unknown thermal does not stop a low-battery pause either (battery still decides)
        assertEquals(ProxyRunAction.PAUSE, run(headroom = null, battery = 10).action)
    }

    @Test fun thermalPauseHasHysteresisAndResumesOnlyBelowTheBand() {
        // paused at 0.9; cooling: stays paused through the slow band...
        val paused = run(headroom = 0.9f)
        assertEquals(ProxyRunAction.PAUSE, run(headroom = 0.75f, prev = paused).action)
        // ...keeps pausing just above the resume line...
        assertEquals(ProxyRunAction.PAUSE, run(headroom = 0.70f, prev = paused).action)
        // ...and only drops to SLOW below it (never straight back to full speed)
        val slow = run(headroom = 0.69f, prev = paused)
        assertEquals(ProxyRunAction.SLOW, slow.action)
        // a slowed job returns to RUN only clearly below the band
        assertEquals(ProxyRunAction.SLOW, run(headroom = 0.60f, prev = slow).action)
        assertEquals(ProxyRunAction.RUN, run(headroom = 0.59f, prev = slow).action)
    }

    @Test fun exportPauseDoesNotPoisonTheThermalHysteresis() {
        // a pause caused by EXPORT carries no thermal history: with a cool device the job
        // goes straight back to RUN, no phantom "thermal pause" survives the export
        val exportPause = run(export = true)
        assertEquals(ProxyRunReason.EXPORT, exportPause.reason)
        assertEquals(ProxyRunAction.RUN, run(headroom = 0.2f, prev = exportPause).action)
    }

    @Test fun batteryPausesBelowTheThresholdOnlyWhenNotCharging() {
        assertEquals(ProxyRunAction.PAUSE, run(battery = 19).action)
        assertEquals(ProxyRunReason.BATTERY, run(battery = 19).reason)
        assertEquals(ProxyRunAction.RUN, run(battery = 20).action)          // at the threshold: run
        assertEquals(ProxyRunAction.RUN, run(battery = 19, charging = true).action) // charging lifts it
        assertEquals(ProxyRunAction.RUN, run(battery = 3, charging = true).action)
        assertEquals(ProxyRunAction.RUN, run(battery = null).action)       // unknown battery: no objection
    }

    @Test fun thermalBeatsBatteryWhenBothPauseAndSlowBeatsRun() {
        // both pause: THERMAL is the reported reason (deterministic)
        assertEquals(ProxyRunReason.THERMAL, run(headroom = 0.9f, battery = 5).reason)
        // thermal SLOW + battery RUN -> SLOW; thermal RUN + battery PAUSE -> PAUSE
        assertEquals(ProxyRunAction.SLOW, run(headroom = 0.75f, battery = 80).action)
        assertEquals(ProxyRunAction.PAUSE, run(headroom = 0.1f, battery = 5).action)
        assertEquals(ProxyRunReason.BATTERY, run(headroom = 0.1f, battery = 5).reason)
    }

    @Test fun thresholdsAreParametersNotConstants() {
        // a conservative owner tuning: pause at 0.5, slow at 0.3; the LOGIC is unchanged
        val custom = ProxyRunPolicy(ProxyRunThresholds(thermalPauseAt = 0.5f, thermalResumeBelow = 0.4f, thermalSlowAt = 0.3f, thermalRunBelow = 0.2f))
        fun r(h: Float?) = custom.decide(ProxyPowerState(h, 80, false, false))
        assertEquals(ProxyRunAction.RUN, r(0.1f).action)
        assertEquals(ProxyRunAction.SLOW, r(0.3f).action)
        assertEquals(ProxyRunAction.PAUSE, r(0.5f).action)
        // battery threshold moves too
        val thirsty = ProxyRunPolicy(ProxyRunThresholds(batteryPauseBelowPercent = 50))
        assertEquals(ProxyRunAction.PAUSE, thirsty.decide(ProxyPowerState(null, 49, false, false)).action)
        assertEquals(ProxyRunAction.RUN, thirsty.decide(ProxyPowerState(null, 50, false, false)).action)
    }

    @Test fun thresholdLadderIsValidated() {
        // an inverted or unordered ladder is a wiring bug: rejected at construction
        fun make(pause: Float = 0.85f, slow: Float = 0.70f, run: Float = 0.60f,
                 resume: Float = 0.70f, battery: Int = 20): ProxyRunThresholds? =
            try {
                ProxyRunThresholds(thermalPauseAt = pause, thermalSlowAt = slow,
                    thermalRunBelow = run, thermalResumeBelow = resume,
                    batteryPauseBelowPercent = battery)
            } catch (e: IllegalArgumentException) { null }
        // each of these ladders violates exactly one rule
        assertNull(make(slow = 0.9f))               // slow above pause
        assertNull(make(run = 0.8f))                // run above slow
        assertNull(make(resume = 0.9f))             // resume above pause
        assertNull(make(battery = 101))
        assertNull(make(battery = -1))
        // the default ladder and an equal-rung ladder are fine
        assertEquals(0.85f, ProxyRunThresholds().thermalPauseAt)
        assertNotNull(make(pause = 0.7f, slow = 0.7f, run = 0.7f, resume = 0.7f))
    }
}

class ProxyStateMapE2Test {

    private fun k(fp: String) = ProxyKey(fp, "0123456789abcdef")

    @Test fun lifecycleRunsQueuedBuildingReadyAndCancelledIsATerminalState() {
        val m = ProxyStateMap()
        val a = k("a1")
        assertEquals(ProxyState.None, m.state(a))
        assertTrue(m.enqueue(a))
        assertEquals(ProxyState.Queued, m.state(a))
        assertEquals(a, m.next())
        assertEquals(ProxyState.Building, m.state(a))
        m.ready(a)
        assertEquals(ProxyState.Ready, m.state(a))
        // cancellation of a queued job
        val b = k("b1")
        m.enqueue(b)
        m.cancelled(b)
        assertEquals(ProxyState.Cancelled, m.state(b))
        assertFalse(b in m.queued())          // it left the queue
        assertEquals(0, m.failureCount(b))   // cancellation is not a failure
        // a cancelled job can be enqueued again (settings changed, the user asked again)
        assertTrue(m.enqueue(b))
        assertEquals(ProxyState.Queued, m.state(b))
    }

    @Test fun frontLaneJumpsTheBacklogWithoutReorderingIt() {
        val m = ProxyStateMap()
        val a = k("a1"); val b = k("b1"); val c = k("c1")
        m.enqueue(a); m.enqueue(b)              // background backlog: a, b
        assertEquals(listOf(a, b), m.queued())
        m.enqueue(c, front = true)             // the user just opened clip c
        assertEquals(listOf(c, a, b), m.queued())
        assertEquals(c, m.next())              // c drains first
        assertEquals(a, m.next()); assertEquals(b, m.next()) // the backlog keeps ITS order
    }

    @Test fun frontReEnqueueOfAnAlreadyQueuedFrontJobKeepsItFront() {
        val m = ProxyStateMap()
        val a = k("a1"); val b = k("b1"); val c = k("c1")
        m.enqueue(a, front = true); m.enqueue(b, front = true) // front lane: a, b
        m.enqueue(c)                                            // back lane: c
        assertFalse(m.enqueue(b, front = true))                 // already queued: refused, no duplicate
        assertEquals(listOf(a, b, c), m.queued())               // b stays in the front lane
        // a back-lane job promoted by the user opening it
        assertFalse(m.enqueue(c, front = true))
        assertEquals(listOf(a, b, c), m.queued()) // already Queued -> stays put (it is next anyway)
    }

    @Test fun promotingABackLaneJobToTheFrontLaneMovesItAheadOfTheBacklog() {
        val m = ProxyStateMap()
        val a = k("a1"); val b = k("b1"); val c = k("c1")
        m.enqueue(a); m.enqueue(b); m.enqueue(c)  // back lane: a, b, c
        assertEquals(listOf(a, b, c), m.queued())
        // the user opens clip c while it waits: the SAME clip arrives for another signature
        // variant (its settings changed) and goes to the front; the waiting variant keeps its place
        val c2 = ProxyKey("c1", "fedcba9876543210")
        assertTrue(m.enqueue(c2, front = true))
        assertEquals(listOf(c2, a, b, c), m.queued())
        assertEquals(c2, m.next())
        // the original c is still queued behind a and b, in ITS order
        assertEquals(a, m.next()); assertEquals(b, m.next()); assertEquals(c, m.next())
    }

    @Test fun aTwiceFailedClipIsNotRetriedThisSession() {
        val m = ProxyStateMap()
        val a = k("a1")
        assertTrue(m.enqueue(a)); assertEquals(a, m.next())
        m.failed(a, "encoder init")
        assertEquals(1, m.failureCount(a))
        assertTrue(m.enqueue(a))                    // first failure: a retry is allowed
        assertEquals(a, m.next())
        m.failed(a, "encoder init again")
        assertEquals(2, m.failureCount(a))
        assertEquals("encoder init again", m.failureReason(a))
        assertFalse(m.enqueue(a))                    // twice failed: this session is done with it
        assertFalse(m.enqueue(a, front = true))     // not even a user-opened retry this session
        assertTrue(m.queueHasNo(a))
    }

    @Test fun cancelledClearsTheQueueEntryAndReadyIsNeverReEnqueued() {
        val m = ProxyStateMap()
        val a = k("a1"); val b = k("b1")
        m.enqueue(a); m.enqueue(b)
        m.cancelled(a)
        assertEquals(listOf(b), m.queued())
        m.next(); m.ready(b)
        assertFalse(m.enqueue(b))                    // Ready is not re-queued
        m.cancelled(b)                               // cancelling a Ready job is a state change only
        assertEquals(ProxyState.Cancelled, m.state(b))
    }

    @Test fun requeueInterruptedBuildingJobGoesToTheVeryFront() {
        val m = ProxyStateMap()
        val a = k("a1"); val b = k("b1"); val c = k("c1"); val d = k("d1")
        m.enqueue(a); m.enqueue(b); m.enqueue(c); m.enqueue(d, front = true)
        assertEquals(d, m.next())                    // d was building
        m.requeue(d)                                  // export interrupted it
        assertEquals(listOf(d, a, b, c), m.queued())
        assertEquals(ProxyState.Queued, m.state(d))
        assertEquals(d, m.next())                    // it runs again before everything else
    }

    @Test fun pauseBlocksNextAndResumeReleasesIt() {
        val m = ProxyStateMap()
        val a = k("a1")
        m.enqueue(a)
        m.pause()
        assertNull(m.next())
        assertEquals(listOf(a), m.queued())
        m.resume()
        assertEquals(a, m.next())
    }

    @Test fun failureHistorySurvivesResetOfOtherKeys() {
        val m = ProxyStateMap()
        val a = k("a1"); val b = k("b1")
        assertTrue(m.enqueue(a)); m.next(); m.failed(a, "boom")
        m.reset(b)                                    // an unrelated delete
        assertEquals(1, m.failureCount(a))
        assertEquals(ProxyState.Failed, m.state(a))
        // resetting the failed key clears ITS state; the session failure COUNT survives
        m.reset(a)
        assertEquals(ProxyState.None, m.state(a))
        assertEquals(1, m.failureCount(a))
        // one failure so far: a retry is still allowed this session (the count barrier is at 2)
        assertTrue(m.enqueue(a))
        assertEquals(a, m.next())
        m.failed(a, "boom again")
        assertFalse(m.enqueue(a))                     // twice failed this session: done
        assertEquals(2, m.failureCount(a))
    }

    private fun ProxyStateMap.queueHasNo(key: ProxyKey) = key !in queued()
}

class ProxyRunnerTest {

    private fun k(fp: String) = ProxyKey(fp, "0123456789abcdef")

    private fun queueWith(vararg keys: ProxyKey): ProxyStateMap {
        val m = ProxyStateMap()
        keys.forEach { m.enqueue(it) }
        return m
    }

    @Test fun idleWhenNothingIsQueuedOrBuilding() {
        val r = ProxyRunner(ProxyStateMap())
        assertEquals(ProxyRunnerStep.IDLE, r.step(ProxyPowerState(), playing = false))
        assertEquals(ProxyRunnerStep.IDLE, r.step(ProxyPowerState(), playing = true))
    }

    @Test fun exportInterruptsABuildingJobAndHoldsAQueuedOne() {
        val q = queueWith(k("a1")); q.next() // a is Building
        val r = ProxyRunner(q)
        val power = ProxyPowerState(exportActive = true)
        assertEquals(ProxyRunnerStep.INTERRUPT, r.step(power, playing = false))
        // nothing building, work waiting: the export holds the queue (no orphan INTERRUPT)
        val q2 = queueWith(k("b1"))
        assertEquals(ProxyRunnerStep.HOLD, ProxyRunner(q2).step(power, playing = false))
        // nothing building and nothing queued: IDLE, not a phantom HOLD
        assertEquals(ProxyRunnerStep.IDLE, ProxyRunner(ProxyStateMap()).step(power, playing = false))
    }

    @Test fun exportWithNothingBuildingHoldsTheQueue() {
        val q = queueWith(k("a1"))
        val ops = ProxyRunnerOps(q)
        val (step, job) = ops.act(ProxyPowerState(exportActive = true), playing = false)
        assertEquals(ProxyRunnerStep.HOLD, step)  // work waiting, no build to interrupt
        assertNull(job)
    }

    @Test fun playingBlocksNewStartsButKeepsARunningBuild() {
        val q = queueWith(k("a1"))
        val r = ProxyRunner(q)
        assertEquals(ProxyRunnerStep.WAIT_PLAYBACK, r.step(ProxyPowerState(), playing = true))
        q.next() // a is Building now
        assertEquals(ProxyRunnerStep.CONTINUE, r.step(ProxyPowerState(), playing = true))
    }

    @Test fun thermalPolicyMapsToRunnerStepsForBuildingAndIdle() {
        val q = queueWith(k("a1")); q.next() // Building
        val r = ProxyRunner(q)
        assertEquals(ProxyRunnerStep.CONTINUE, r.step(ProxyPowerState(thermalHeadroom = 0.2f), playing = false))
        assertEquals(ProxyRunnerStep.YIELD, r.step(ProxyPowerState(thermalHeadroom = 0.75f), playing = false))
        assertEquals(ProxyRunnerStep.INTERRUPT, r.step(ProxyPowerState(thermalHeadroom = 0.9f), playing = false))
        // idle with a queued job
        val q2 = queueWith(k("b1"))
        val r2 = ProxyRunner(q2)
        assertEquals(ProxyRunnerStep.START, r2.step(ProxyPowerState(thermalHeadroom = 0.2f), playing = false))
        assertEquals(ProxyRunnerStep.START, r2.step(ProxyPowerState(thermalHeadroom = 0.75f), playing = false)) // slow start allowed
        assertEquals(ProxyRunnerStep.HOLD, r2.step(ProxyPowerState(thermalHeadroom = 0.9f), playing = false))
        assertEquals(ProxyRunnerStep.HOLD, r2.step(ProxyPowerState(batteryPercent = 5), playing = false))
    }

    @Test fun runnerOpsPopsTheJobOnStartAndNamesTheBuildOnInterrupt() {
        val q = queueWith(k("a1"), k("b1"))
        val ops = ProxyRunnerOps(q)
        val (step, job) = ops.act(ProxyPowerState(), playing = false)
        assertEquals(ProxyRunnerStep.START, step)
        assertEquals(k("a1"), job!!.key)
        assertTrue(job is ProxyJob.Start)
        assertEquals(ProxyState.Building, q.state(k("a1")))
        // the export begins: the building job is named for interruption
        val (iStep, iJob) = ops.act(ProxyPowerState(exportActive = true), playing = false)
        assertEquals(ProxyRunnerStep.INTERRUPT, iStep)
        assertEquals(k("a1"), iJob!!.key)
        assertTrue(iJob is ProxyJob.Interrupt)
    }

    @Test fun pausedQueueHoldsEvenWithPowerAvailable() {
        val q = queueWith(k("a1"))
        q.pause()
        assertEquals(ProxyRunnerStep.HOLD, ProxyRunner(q).step(ProxyPowerState(), playing = false))
        q.resume()
        assertEquals(ProxyRunnerStep.START, ProxyRunner(q).step(ProxyPowerState(), playing = false))
    }

    @Test fun pausedQueueIsHeldEvenWhenPlayingIsFalseAndPowerIsFine() {
        val q = queueWith(k("a1"))
        q.pause()
        val ops = ProxyRunnerOps(q)
        assertEquals(ProxyRunnerStep.HOLD, ops.act(ProxyPowerState(), playing = false).first)
        assertEquals(listOf(k("a1")), q.queued()) // nothing was popped
    }
}

class ProxySwitchTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @org.junit.Before fun setUp() {
        root = Files.createTempDirectory("proxyswitch").toFile()
        storage = DirProxyStorage(root)
    }

    @org.junit.After fun tearDown() { root.deleteRecursively() }

    private fun sig(fp: String = "abc123") = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun fullProxy(cache: ProxyCache, s: ProxySignature): ProxyEntry {
        val h = cache.begin(s)
        h.videoFile.writeBytes(ByteArray(50))
        val e = h.commit(durationUs = 1_000_000, frameCount = 30)!!
        return e
    }

    @Test fun halfProxyNeverPlaysInTheSwitchRule() {
        val c = ProxyCache(storage)
        val s = sig()
        // a build in progress: the tmp exists, playable says null, the switch stays on the original
        val h = c.begin(s)
        h.videoFile.writeBytes(ByteArray(30))
        val decision = ProxySwitch.decide(
            ProxySwitchRequest(c.peekPlayable(s.key()), 1, playing = false, lastPresentedPtsUs = 0),
        )
        assertEquals(ProxySwitchDecision.STAY_ON_ORIGINAL, decision)
        h.abort()
        // crash leftovers: still never
        storage.write("${s.key().baseName}.mp4", ByteArray(30)) // a video without a manifest
        assertEquals(
            ProxySwitchDecision.STAY_ON_ORIGINAL,
            ProxySwitch.decide(ProxySwitchRequest(c.peekPlayable(s.key()), 1, playing = false, lastPresentedPtsUs = 0)),
        )
    }

    @Test fun completeVerifiedProxySwitchesImmediatelyWhenNotPlaying() {
        val c = ProxyCache(storage)
        val s = sig()
        val e = fullProxy(c, s)
        assertEquals(
            ProxySwitchDecision.SWITCH_NOW,
            ProxySwitch.decide(ProxySwitchRequest(c.playable(s.key()), 1, playing = false, lastPresentedPtsUs = 0)),
        )
        // the entry that decided is the one playable returned (complete + post-rename + size-verified)
        assertEquals(50L, e.bytes)
    }

    @Test fun playingSwitchesOnlyAtAKeyframeBoundary() {
        val c = ProxyCache(storage)
        val s = sig()
        fullProxy(c, s)
        val entry = c.playable(s.key())!!
        fun d(pts: Long) = ProxySwitch.decide(ProxySwitchRequest(entry, 1, playing = true, lastPresentedPtsUs = pts))
        // mid-GOP: stay on the original (Klipp's lesson: no live source swap mid-play)
        assertEquals(ProxySwitchDecision.STAY_ON_ORIGINAL, d(33_367))
        assertEquals(ProxySwitchDecision.STAY_ON_ORIGINAL, d(999_999))
        // exactly on a 1 s GOP boundary: the proxy's keyframe can be entered cleanly
        assertEquals(ProxySwitchDecision.SWITCH_AT_BOUNDARY, d(1_000_000))
        assertEquals(ProxySwitchDecision.SWITCH_AT_BOUNDARY, d(2_000_000))
        assertEquals(ProxySwitchDecision.SWITCH_AT_BOUNDARY, d(0))
    }

    @Test fun boundaryGridFollowsTheConfiguredKeyFrameInterval() {
        val c = ProxyCache(storage)
        val s = sig().copy(settings = ProxySettings(keyFrameIntervalSec = 2))
        fullProxy(c, s)
        val entry = c.playable(s.key())!!
        fun d(pts: Long) = ProxySwitch.decide(ProxySwitchRequest(entry, 2, playing = true, lastPresentedPtsUs = pts))
        assertEquals(ProxySwitchDecision.STAY_ON_ORIGINAL, d(1_000_000))   // 1 s: not on a 2 s grid
        assertEquals(ProxySwitchDecision.SWITCH_AT_BOUNDARY, d(2_000_000))
        assertEquals(ProxySwitchDecision.SWITCH_AT_BOUNDARY, d(4_000_000))
        // no frame presented yet while playing: stay (the first boundary will come)
        assertEquals(ProxySwitchDecision.STAY_ON_ORIGINAL, ProxySwitch.decide(ProxySwitchRequest(entry, 1, playing = true, lastPresentedPtsUs = -1)))
    }

    @Test fun nextBoundaryAfterIsTheDistanceToTheNextGridPoint() {
        assertEquals(1_000_000L, ProxySwitch.nextBoundaryAfter(0, 1))
        assertEquals(666_633L, ProxySwitch.nextBoundaryAfter(333_367, 1))
        assertEquals(1_000_000L, ProxySwitch.nextBoundaryAfter(1_000_000, 1)) // at a boundary: the NEXT one
        assertEquals(2_000_000L, ProxySwitch.nextBoundaryAfter(2_000_000, 2))
        assertEquals(1_000_000L, ProxySwitch.nextBoundaryAfter(3_000_000, 2))
        assertEquals(0L, ProxySwitch.nextBoundaryAfter(-5, 1))
    }

    @Test fun switchNeverHappensWhilePlayableIsNullEvenMidPlay() {
        val c = ProxyCache(storage)
        val s = sig()
        // no proxy at all
        assertEquals(
            ProxySwitchDecision.STAY_ON_ORIGINAL,
            ProxySwitch.decide(ProxySwitchRequest(null, 1, playing = true, lastPresentedPtsUs = 1_000_000)),
        )
        assertEquals(
            ProxySwitchDecision.STAY_ON_ORIGINAL,
            ProxySwitch.decide(ProxySwitchRequest(null, 1, playing = false, lastPresentedPtsUs = 0)),
        )
    }
}

class ProxyCapFeedTest {

    private lateinit var root: File
    private lateinit var storage: DirProxyStorage

    @org.junit.Before fun setUp() {
        root = Files.createTempDirectory("proxycapfeed").toFile()
        storage = DirProxyStorage(root)
    }

    @org.junit.After fun tearDown() { root.deleteRecursively() }

    private fun sig(fp: String, settings: ProxySettings = ProxySettings()) = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        settings = settings, colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun put(c: ProxyCache, s: ProxySignature, bytes: Int): ProxyEntry? {
        val h = c.begin(s)
        h.videoFile.writeBytes(ByteArray(bytes) { 1 })
        return h.commit(durationUs = 1_000_000, frameCount = 30)
    }

    @Test fun feedWithinCapShowsNothingAndCountsProtectedBytes() {
        val c = ProxyCache(storage, 1000)
        val ka = sig("a1"); val kb = sig("b1")
        put(c, ka, 100); put(c, kb, 200)
        val inUse = ProxyInUse()
        val states = ProxyStateMap()
        inUse.acquire(ka.key())
        val u = ProxyCapFeed.usage(c, capBytes = 1000, inUse = inUse, building = states.buildingSource())
        assertEquals(300L, u.totalBytes)
        assertEquals(100L, u.protectedBytes)   // exactly the playing proxy's bytes
        assertFalse(u.isOverCap)
        assertEquals(0L, u.overCapBytes)
    }

    @Test fun feedOverCapReportsTheOverageAndTheProtectedShare() {
        val c = ProxyCache(storage, 1000)
        val ka = sig("a1"); val kb = sig("b1"); val kc = sig("c1")
        put(c, ka, 100); put(c, kb, 200); put(c, kc, 300)
        val inUse = ProxyInUse()
        val states = ProxyStateMap()
        inUse.acquire(ka.key())
        val u = ProxyCapFeed.usage(c, capBytes = 400, inUse = inUse, building = states.buildingSource())
        assertTrue(u.isOverCap)
        assertEquals(200L, u.overCapBytes)                    // 600 total - 400 cap
        assertEquals(100L, u.overCapBecauseProtectedBytes)   // bounded by both the overage and the protected bytes
    }

    @Test fun feedCountsTheBuildingKeyAsProtectedAlongsideInUse() {
        val c = ProxyCache(storage, 1000)
        val ka = sig("a1"); val kb = sig("b1"); val kc = sig("c1")
        put(c, ka, 100); put(c, kb, 200); put(c, kc, 300)
        val inUse = ProxyInUse()
        val states = ProxyStateMap()
        // kb is being rebuilt right now
        states.enqueue(kb.key()); assertEquals(kb.key(), states.next())
        inUse.acquire(ka.key())
        val u = ProxyCapFeed.usage(c, capBytes = 350, inUse = inUse, building = states.buildingSource())
        assertEquals(300L, u.protectedBytes)  // 100 playing + 200 building
        assertEquals(600L, u.totalBytes)
        assertEquals(250L, u.overCapBytes)
        assertEquals(250L, u.overCapBecauseProtectedBytes) // min(250 overage, 300 protected)
    }

    @Test fun feedUsesTheCurrentCapNotTheCacheDefault() {
        val c = ProxyCache(storage) // default cap: 20 GiB
        put(c, sig("a1"), 100)
        val u = ProxyCapFeed.usage(c, capBytes = 50, inUse = ProxyProtectSource.NONE, building = ProxyProtectSource.NONE)
        assertTrue(u.isOverCap)               // the user-lowered cap is what the line reports
        assertEquals(50L, u.overCapBytes)
        // with the default 20 GiB cap the same cache is not over
        val u2 = ProxyCapFeed.usage(c, capBytes = PROXY_CAP_DEFAULT_BYTES, inUse = ProxyProtectSource.NONE, building = ProxyProtectSource.NONE)
        assertFalse(u2.isOverCap)
    }

    @Test fun feedWithAVanishedEntryCountsItsBytesAsZero() {
        // a protected key whose entry was deleted under the feed: peekPlayable is null -> 0 bytes,
        // the feed never crashes and never invents a size
        val c = ProxyCache(storage, 1000)
        val ka = sig("a1")
        put(c, ka, 100)
        val inUse = ProxyInUse()
        inUse.acquire(ka.key())
        c.clear(ka.key())
        val u = ProxyCapFeed.usage(c, capBytes = 50, inUse = inUse, building = ProxyProtectSource.NONE)
        assertEquals(0L, u.totalBytes)
        assertEquals(0L, u.protectedBytes)
        assertEquals(0L, u.overCapBytes)
        assertFalse(u.isOverCap)
    }
}
