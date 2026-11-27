package com.ritim.ritimcraw.proxy

/** What the policy decided to do with proxy generation. */
enum class ProxyRunAction { RUN, SLOW, PAUSE }

/** Why, honestly (the reason reaches the status line, never a silent pause). */
enum class ProxyRunReason { NONE, EXPORT, THERMAL, BATTERY }

data class ProxyRunDecision(val action: ProxyRunAction, val reason: ProxyRunReason) {
    companion object {
        /** The neutral "keep going" decision, also the neutral history input. */
        val RUNNING = ProxyRunDecision(ProxyRunAction.RUN, ProxyRunReason.NONE)
    }
}

/**
 * One reading of the device's power state. The phase-P2 wiring fills this from PowerManager /
 * BatteryManager; JVM tests fill it directly. Unknown values are null — unknown never blocks.
 */
data class ProxyPowerState(
    /**
     * PowerManager.getThermalHeadroom, 10 s forecast; 1.0 = the throttling threshold
     * (the scale [com.ritim.ritimcraw.engine.ImageStabRunPolicy] already uses). null or NaN =
     * the device does not report one = no thermal objection.
     */
    val thermalHeadroom: Float? = null,
    /** Battery percent 0..100 as the OS reports it; null = unknown / not read. */
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    /** An export is running right now: proxy work always stands down (plan section 4). */
    val exportActive: Boolean = false,
)

/**
 * Threshold VALUES live here, as parameters with defaults — the policy LOGIC never hardcodes a
 * number, and the owner can change any default without touching the ladder. Defaults were
 * picked against the repo's existing thermal precedent ([ImageStabRunPolicy]: pause 0.85 /
 * resume 0.70) and the plan's "conservative defaults" (docs/PROXY_PLAN.md section 4; the
 * owner's phone heats up); each default is reported as a "karar" in the night report.
 *
 * Ladder semantics (headroom = the 10 s thermal forecast, 1.0 = throttling):
 *  - headroom >= [thermalPauseAt]  -> PAUSE (SEVERE)
 *  - headroom >= [thermalSlowAt]   -> SLOW (MODERATE)
 *  - below                        -> RUN
 * with hysteresis so a reading hovering at a boundary never flaps the encoder on and off:
 * once PAUSED it stays paused until the headroom drops below [thermalResumeBelow]; once SLOW
 * (or resumed out of a pause) it stays slow until the headroom drops below [thermalRunBelow].
 */
data class ProxyRunThresholds(
    /** SEVERE: pause when the forecast reaches this. */
    val thermalPauseAt: Float = 0.85f,
    /** Pause hysteresis: once paused, stay paused until below this. */
    val thermalResumeBelow: Float = 0.70f,
    /** MODERATE: slow down when the forecast reaches this. */
    val thermalSlowAt: Float = 0.70f,
    /** Slow hysteresis: full speed only below this. */
    val thermalRunBelow: Float = 0.60f,
    /** Pause when the battery is below this percent and not charging. */
    val batteryPauseBelowPercent: Int = 20,
) {
    init {
        require(thermalRunBelow <= thermalSlowAt && thermalSlowAt <= thermalPauseAt) {
            "thermal thresholds must form a ladder: runBelow <= slowAt <= pauseAt"
        }
        require(thermalResumeBelow <= thermalPauseAt) { "thermalResumeBelow must be <= thermalPauseAt" }
        require(batteryPauseBelowPercent in 0..100) { "batteryPauseBelowPercent must be in 0..100" }
    }
}

/**
 * The thermal/battery/export gate for proxy generation (docs/PROXY_PLAN.md section 4, a PURE
 * function per the E2 brief): one power reading -> RUN / SLOW / PAUSE.
 *
 *  - export active -> PAUSE: an export always wins over proxy generation (plan section 4,
 *    Klipp's holdForExport pattern; "aktif export > oynatma > proxy");
 *  - thermal: the [ProxyRunThresholds] ladder; unknown (null/NaN) = no objection;
 *  - battery: below the threshold percent and not charging -> PAUSE; charging lifts the
 *    battery objection entirely; unknown = no objection;
 *  - the more severe of thermal/battery wins (PAUSE > SLOW > RUN); when both pause, the
 *    reported reason is THERMAL (deterministic, and thermal is the dominant real cause).
 *
 * SLOW means the wired runner paces the encode (yields between batches; the output is
 * unchanged). PAUSE means stand down: a running build is interrupted — there is no resume
 * (plan section 3.3), it restarts from scratch when the decision is RUN/SLOW again. The wired
 * runner gets its step from [ProxyRunner]; this class only decides.
 */
class ProxyRunPolicy(private val thresholds: ProxyRunThresholds = ProxyRunThresholds()) {

    /**
     * [previous] is the decision the runner currently runs under — it carries the hysteresis
     * (a PAUSE holds until clearly cooler; a SLOW holds until clearly below the band).
     */
    fun decide(
        state: ProxyPowerState,
        previous: ProxyRunDecision = ProxyRunDecision.RUNNING,
    ): ProxyRunDecision {
        if (state.exportActive) return ProxyRunDecision(ProxyRunAction.PAUSE, ProxyRunReason.EXPORT)
        val thermal = thermal(state.thermalHeadroom, previous)
        val battery = battery(state)
        return if (battery.action.ordinal > thermal.action.ordinal) battery else thermal
    }

    private fun thermal(headroom: Float?, previous: ProxyRunDecision): ProxyRunDecision {
        if (headroom == null || headroom.isNaN()) return ProxyRunDecision.RUNNING
        // Hysteresis only follows THERMAL states: an EXPORT/PAUSE never had a thermal cause,
        // so leaving one must not keep the thermal pause alive by accident.
        val wasThermal = previous.reason == ProxyRunReason.THERMAL
        return when {
            headroom >= thresholds.thermalPauseAt -> paused()
            // hysteresis: a thermally paused job stays paused while the forecast is still warm
            wasThermal && previous.action == ProxyRunAction.PAUSE &&
                headroom >= thresholds.thermalResumeBelow -> paused()
            headroom >= thresholds.thermalSlowAt -> slowed()
            // a job coming out of a thermal PAUSE or SLOW keeps the slow pace until clearly cool
            wasThermal && previous.action != ProxyRunAction.RUN &&
                headroom >= thresholds.thermalRunBelow -> slowed()
            else -> ProxyRunDecision.RUNNING
        }
    }

    private fun battery(state: ProxyPowerState): ProxyRunDecision {
        val pct = state.batteryPercent ?: return ProxyRunDecision.RUNNING
        return if (!state.charging && pct < thresholds.batteryPauseBelowPercent) {
            ProxyRunDecision(ProxyRunAction.PAUSE, ProxyRunReason.BATTERY)
        } else ProxyRunDecision.RUNNING
    }

    private fun paused() = ProxyRunDecision(ProxyRunAction.PAUSE, ProxyRunReason.THERMAL)
    private fun slowed() = ProxyRunDecision(ProxyRunAction.SLOW, ProxyRunReason.THERMAL)
}
