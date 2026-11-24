package com.ritim.ritimcraw.engine

/**
 * A tiny pool of byte arrays for the decoded-plane copies (E0b-2): a 4K P010 frame is 31.6 MB of planes,
 * and copying it into FRESH arrays every frame made the copy 13 ms on the first frames and 800-910 ms once
 * the heap was under pressure (device trace 2026-11-25, 256 MB limit). With a pool the steady state
 * allocates nothing: [acquire] hands out a free array of at least the size asked and [release] takes it back.
 *
 * Arrays are matched by exact size class (the plane sizes of one clip repeat every frame); the pool never
 * grows past [maxFree] idle arrays, the rest are left to the GC. Methods are synchronized (E0b-4b: the decode-ahead
 * thread acquires, the render thread releases). A caller must not touch an array after releasing it.
 */
class PlaneBufferPool(private val maxFree: Int = 6) {
    private val free = ArrayList<ByteArray>()

    /** Arrays this pool had to allocate (a test and the probe read it: steady state must stop counting). */
    var allocations = 0
        private set

    /** An array with at least [size] bytes (contents undefined). */
    @Synchronized
    fun acquire(size: Int): ByteArray {
        require(size >= 0) { "negative size" }
        val at = free.indexOfFirst { it.size == size }
        if (at >= 0) return free.removeAt(at)
        allocations++
        return ByteArray(size)
    }

    /** Gives [array] back for reuse (dropped when the pool is already holding [maxFree] arrays). */
    @Synchronized
    fun release(array: ByteArray) {
        if (free.size < maxFree && free.none { it === array }) free.add(array)
    }

    /** Idle arrays currently held. */
    val idle: Int get() = free.size

    /** Drops everything (clip closed, size class changed). */
    @Synchronized
    fun clear() = free.clear()
}
