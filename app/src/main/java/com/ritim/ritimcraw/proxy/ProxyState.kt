package com.ritim.ritimcraw.proxy

enum class ProxyState { None, Queued, Building, Ready, Failed, Cancelled }

/**
 * Pure, thread-safe state + queue for proxy jobs. No threads or Android here: the (later)
 * job runner asks [next], reports with [building]/[ready]/[failed], and calls [pause]/[resume]
 * (e.g. while an export runs). A running job that is interrupted goes back with [requeue] (front of queue).
 *
 * Queue model (docs/PROXY_PLAN.md section 4, phase E2): ONE build at a time; the queue has two
 * lanes so "the clip the user just opened" jumps every background job without ever reordering
 * the background order itself:
 *  - the FRONT lane holds [enqueue] jobs the user is waiting on (front = true);
 *  - the BACK lane is the automatic/background queue, FIFO as before;
 *  - [next] always drains the front lane first (opening a clip must not wait behind a backlog).
 *
 * Failure policy (plan section 4): a clip that failed [ProxyJobPolicy.MAX_FAILURES_PER_SESSION]
 * times (default 2) is not retried this session — [enqueue] refuses it; the honest reason stays
 * in [failureReason]. [reset] clears the state (entry deleted) but KEEPS the failure count,
 * so the session barrier is count-based, exactly as the plan words it ("bir klip iki kez
 * başarısız olursa o oturumda tekrar denenmez").
 */
class ProxyStateMap {
    private val lock = Any()
    private val states = HashMap<ProxyKey, ProxyState>()
    private val failures = HashMap<ProxyKey, Int>()
    private val reasons = HashMap<ProxyKey, String>()
    private val frontQueue = ArrayDeque<ProxyKey>()
    private val backQueue = ArrayDeque<ProxyKey>()
    private var paused = false

    fun state(key: ProxyKey): ProxyState = synchronized(lock) { states[key] ?: ProxyState.None }
    fun failureCount(key: ProxyKey): Int = synchronized(lock) { failures[key] ?: 0 }
    fun failureReason(key: ProxyKey): String? = synchronized(lock) { reasons[key] }
    fun isPaused(): Boolean = synchronized(lock) { paused }
    /** Keys currently Building: a [ProxyProtectSource] (an entry being rebuilt is never evicted). */
    fun buildingSource(): ProxyProtectSource = ProxyProtectSource {
        synchronized(lock) { states.filterValues { it == ProxyState.Building }.keys.toSet() }
    }

    /** The whole queue, front lane first (front lane preserves its own order, then back FIFO). */
    fun queued(): List<ProxyKey> = synchronized(lock) { frontQueue.toList() + backQueue.toList() }

    /**
     * G3 (storage page): every key that FAILED this session with its honest
     * reason — the page's per-clip "failed" line reads this, keyed by
     * fingerprint so the UI never recomputes a key.
     */
    fun failures(): Map<ProxyKey, String> = synchronized(lock) {
        states.filterValues { it == ProxyState.Failed }.keys.associateWith { reasons[it] ?: "" }
    }

    /** Queue [key] unless it is already Queued/Building/Ready, or it already failed too often this session. */
    fun enqueue(key: ProxyKey, front: Boolean = false): Boolean = synchronized(lock) {
        when (states[key]) {
            ProxyState.Queued -> {
                if (front) promoteToFrontLane(key)
                false
            }
            ProxyState.Building, ProxyState.Ready -> false
            else -> {
                // the session barrier is COUNT-based: a twice-failed clip is not retried this
                // session even after its entry (and state) was deleted (see [reset])
                if ((failures[key] ?: 0) >= ProxyJobPolicy.MAX_FAILURES_PER_SESSION) return@synchronized false
                states[key] = ProxyState.Queued
                (if (front) frontQueue else backQueue).addLast(key)
                true
            }
        }
    }

    /** Pop the next job (front lane first) and mark it Building; null while paused or empty. */
    fun next(): ProxyKey? = synchronized(lock) {
        if (paused) return null
        val k = frontQueue.removeFirstOrNull() ?: backQueue.removeFirstOrNull() ?: return null
        states[k] = ProxyState.Building
        k
    }

    fun building(key: ProxyKey) = synchronized(lock) { forget(key); states[key] = ProxyState.Building }
    fun ready(key: ProxyKey) = synchronized(lock) { forget(key); states[key] = ProxyState.Ready }

    fun failed(key: ProxyKey, reason: String) = synchronized(lock) {
        forget(key)
        states[key] = ProxyState.Failed
        failures[key] = (failures[key] ?: 0) + 1
        reasons[key] = reason
    }

    /**
     * User / policy cancellation: the job stops and does not return on its own. A later
     * [enqueue] can still start it again (e.g. the settings changed and a new signature was
     * queued); the failure count is untouched.
     */
    fun cancelled(key: ProxyKey) = synchronized(lock) { forget(key); states[key] = ProxyState.Cancelled }

    /** An interrupted Building job returns to the head of the queue (no resume, it restarts). */
    fun requeue(key: ProxyKey) = synchronized(lock) {
        forget(key)
        states[key] = ProxyState.Queued
        frontQueue.addFirst(key)
    }

    /** A user-requested retry: forget the session failure count so [enqueue] accepts [key] again. */
    fun forgiveFailures(key: ProxyKey) = synchronized(lock) {
        failures.remove(key)
        reasons.remove(key)
        if (states[key] == ProxyState.Failed) states.remove(key)
    }

    /** Forget [key] (entry deleted). Failure history is kept so a twice-failed clip is not retried this session. */
    fun reset(key: ProxyKey) = synchronized(lock) { forget(key); states.remove(key) }

    fun pause() = synchronized(lock) { paused = true }
    fun resume() = synchronized(lock) { paused = false }

    private fun forget(key: ProxyKey) {
        frontQueue.remove(key)
        backQueue.remove(key)
    }

    /** A queued job the user just opened: pull it out of the back lane into the front one. */
    private fun promoteToFrontLane(key: ProxyKey) {
        if (key in frontQueue) return
        backQueue.remove(key)
        frontQueue.addLast(key)
    }
}

/** Session-wide job rules the queue follows (values are cheap to change, not golden). */
object ProxyJobPolicy {
    /** A clip that failed this many times in one session is not retried this session (plan section 4). */
    const val MAX_FAILURES_PER_SESSION = 2
}
