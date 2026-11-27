package com.ritim.ritimcraw.proxy

/**
 * The user's proxy mode (Settings). OFF = proxies are neither built nor used
 * (the existing "flag off ignores proxies" rule); ASK = after an import a
 * non-blocking prompt offers the eligible clips; AUTOMATIC = eligible clips
 * queue by themselves. Default OFF.
 */
enum class ProxyMode {
    OFF, ASK, AUTOMATIC;

    companion object {
        val DEFAULT = OFF
        const val WIRE_NAME = "proxyMode"

        fun fromWire(raw: String?): ProxyMode = entries.firstOrNull { it.name == raw } ?: DEFAULT

        /**
         * The mode the app really runs with: the stored choice, except that the
         * debug-only `setprop debug.ritim.proxy 1` switch (resolved elsewhere,
         * debug builds only) lifts OFF to AUTOMATIC, which is exactly what the
         * old compile-time flag did when it was on.
         */
        fun effective(stored: ProxyMode, debugForcedOn: Boolean): ProxyMode =
            if (stored == OFF && debugForcedOn) AUTOMATIC else stored
    }
}

/** Where a proxy re-check comes from. */
enum class ProxyTrigger { START_SCAN, IMPORT, EDITOR_OPEN }

/** What the app does with one clip after a trigger. */
enum class ProxyAction { NONE, ENQUEUE, ASK }

/** Pure queue/prompt decisions per mode (JVM-tested). */
object ProxyImportDecision {

    /**
     * [clip] = the clip's measured facts (null while unknown); [busy] = a proxy is already
     * built, queued or building for it; [alreadyAsked] = the prompt already named it this session.
     * Eligibility is the existing auto rule ([ProxyQueueDecision]: 8K or 4K above 30 fps encoded,
     * MCRAW never automatic).
     */
    fun decide(
        mode: ProxyMode,
        trigger: ProxyTrigger,
        clip: ProxyClipInfo?,
        busy: Boolean,
        alreadyAsked: Boolean = false,
        policy: ProxyPolicy = ProxyPolicy(),
    ): ProxyAction {
        if (mode == ProxyMode.OFF) return ProxyAction.NONE
        if (clip == null || busy) return ProxyAction.NONE
        if (ProxyQueueDecision.decide(clip, policy) == null) return ProxyAction.NONE
        return when (mode) {
            ProxyMode.AUTOMATIC -> ProxyAction.ENQUEUE
            // The app-start scan never prompts in Ask mode (it would nag on every launch);
            // import and editor-open do, once per clip per session.
            ProxyMode.ASK ->
                if (trigger == ProxyTrigger.START_SCAN || alreadyAsked) ProxyAction.NONE else ProxyAction.ASK
            ProxyMode.OFF -> ProxyAction.NONE
        }
    }

    /** The manual "Create proxy" / "Delete proxy" items show in Ask and Automatic only (any clip kind, MCRAW included). */
    fun manualActionsVisible(mode: ProxyMode): Boolean = mode != ProxyMode.OFF

    /** "Create proxy" makes sense unless a proxy is ready or already queued/building. */
    fun canCreate(status: ProxyStatus): Boolean = status is ProxyStatus.None || status is ProxyStatus.Failed

    /** "Delete proxy" makes sense when there is something to remove or stop. */
    fun canDelete(status: ProxyStatus): Boolean = status !is ProxyStatus.None
}

/** What the library row / editor shows for one clip's proxy. */
sealed interface ProxyStatus {
    data object None : ProxyStatus
    data object Queued : ProxyStatus
    data class Preparing(val percent: Int) : ProxyStatus
    data object Ready : ProxyStatus
    data object Failed : ProxyStatus
}

object ProxyStatusMapper {

    /**
     * Queue state + "a playable proxy exists" + build percent -> the shown status.
     * Nothing for a clip without a proxy that is not queued (also Cancelled).
     */
    fun of(state: ProxyState, hasPlayable: Boolean, percent: Int?): ProxyStatus = when (state) {
        ProxyState.Building -> ProxyStatus.Preparing((percent ?: 0).coerceIn(0, 99))
        ProxyState.Queued -> ProxyStatus.Queued
        ProxyState.Ready -> ProxyStatus.Ready
        ProxyState.Failed -> if (hasPlayable) ProxyStatus.Ready else ProxyStatus.Failed
        ProxyState.None, ProxyState.Cancelled -> if (hasPlayable) ProxyStatus.Ready else ProxyStatus.None
    }

    /** Whole percent of a frame count, clamped; unknown total = 0. */
    fun percent(done: Long, total: Long): Int =
        if (total <= 0) 0 else ((done * 100) / total).toInt().coerceIn(0, 100)
}

/** Logs progress once per [step] percent (the ritim-proxy debug trace), without repeats. */
class ProxyProgressLogThrottle(private val step: Int = 10) {
    private var lastBucket = -1

    /** True when [percent] entered a new bucket since the last true; [reset] starts over. */
    fun shouldLog(percent: Int): Boolean {
        val bucket = percent / step
        if (bucket <= lastBucket) return false
        lastBucket = bucket
        return true
    }

    fun reset() {
        lastBucket = -1
    }
}

/** Debug-only trace, tag `ritim-proxy`. Silent in release; never throws (JVM tests have no Log). */
object ProxyLog {
    const val TAG = "ritim-proxy"

    fun d(message: String) {
        if (!com.ritim.ritimcraw.BuildConfig.DEBUG) return
        runCatching { android.util.Log.d(TAG, message) }
    }
}
