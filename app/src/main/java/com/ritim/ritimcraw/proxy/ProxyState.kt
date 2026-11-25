package com.ritim.ritimcraw.proxy

enum class ProxyState { None, Queued, Building, Ready, Failed }

/**
 * Pure, thread-safe state + FIFO queue for proxy jobs. No threads or Android here: the (later)
 * job runner asks [next], reports with [building]/[ready]/[failed], and calls [pause]/[resume]
 * (e.g. while an export runs). A running job that is interrupted goes back with [requeue] (front of queue).
 */
class ProxyStateMap {
    private val lock = Any()
    private val states = HashMap<ProxyKey, ProxyState>()
    private val failures = HashMap<ProxyKey, Int>()
    private val reasons = HashMap<ProxyKey, String>()
    private val queue = ArrayDeque<ProxyKey>()
    private var paused = false

    fun state(key: ProxyKey): ProxyState = synchronized(lock) { states[key] ?: ProxyState.None }
    fun failureCount(key: ProxyKey): Int = synchronized(lock) { failures[key] ?: 0 }
    fun failureReason(key: ProxyKey): String? = synchronized(lock) { reasons[key] }
    fun isPaused(): Boolean = synchronized(lock) { paused }
    fun queued(): List<ProxyKey> = synchronized(lock) { queue.toList() }

    /** Queue [key] unless it is already Queued/Building/Ready. [front] = the clip the user just opened. */
    fun enqueue(key: ProxyKey, front: Boolean = false): Boolean = synchronized(lock) {
        when (states[key]) {
            ProxyState.Queued -> {
                if (front && queue.remove(key)) queue.addFirst(key)
                false
            }
            ProxyState.Building, ProxyState.Ready -> false
            else -> {
                states[key] = ProxyState.Queued
                if (front) queue.addFirst(key) else queue.addLast(key)
                true
            }
        }
    }

    /** Pop the next job and mark it Building; null while paused or empty. */
    fun next(): ProxyKey? = synchronized(lock) {
        if (paused) return null
        val k = queue.removeFirstOrNull() ?: return null
        states[k] = ProxyState.Building
        k
    }

    fun building(key: ProxyKey) = synchronized(lock) { queue.remove(key); states[key] = ProxyState.Building }
    fun ready(key: ProxyKey) = synchronized(lock) { queue.remove(key); states[key] = ProxyState.Ready }

    fun failed(key: ProxyKey, reason: String) = synchronized(lock) {
        queue.remove(key)
        states[key] = ProxyState.Failed
        failures[key] = (failures[key] ?: 0) + 1
        reasons[key] = reason
    }

    /** An interrupted Building job returns to the head of the queue (no resume, it restarts). */
    fun requeue(key: ProxyKey) = synchronized(lock) {
        queue.remove(key)
        states[key] = ProxyState.Queued
        queue.addFirst(key)
    }

    /** Forget [key] (entry deleted). Failure history is kept so a twice-failed clip is not retried this session. */
    fun reset(key: ProxyKey) = synchronized(lock) { queue.remove(key); states.remove(key) }

    fun pause() = synchronized(lock) { paused = true }
    fun resume() = synchronized(lock) { paused = false }
}
