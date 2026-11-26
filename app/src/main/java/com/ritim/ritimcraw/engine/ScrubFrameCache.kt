package com.ritim.ritimcraw.engine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Where scrub frames come from (the editor adapts a sequential decoder to this). */
interface ScrubFrameSource {
    val frameCount: Int

    /** A frame downscaled so its long side is at most [maxDim], or null if it cannot be produced. */
    suspend fun frameArgb(index: Int, maxDim: Int): ScrubPixels?
}

/** Decoded pixels of a scrub frame. */
class ScrubPixels(val argb: IntArray, val width: Int, val height: Int) {
    init { require(argb.size == width * height) { "argb size != w*h" } }
}

/** JPEG (or any lossy) codec seam; the Android one is [AndroidScrubCodec], JVM tests use a fake. */
interface ScrubCodec {
    fun encode(argb: IntArray, width: Int, height: Int, quality: Int): ByteArray
    fun decode(bytes: ByteArray): ScrubPixels?
}

sealed interface ScrubResult {
    /** A cached (low-res) frame for the requested or nearest index. [frameIndex] is the index actually served. */
    class Cached(val frameIndex: Int, val pixels: ScrubPixels) : ScrubResult
    data object Miss : ScrubResult
}

/**
 * Pure in-memory LRU of encoded scrub frames, keyed by (sourceId, frameIndex, tierMaxDim), capped by total
 * encoded bytes. Thread-safe (one lock; entries are small and few). No Android types.
 */
class ScrubFrameCache(
    private val maxBytes: Long,
    private val clock: () -> Long = System::nanoTime,
) {
    data class Key(val sourceId: String, val frameIndex: Int, val tierMaxDim: Int)

    private class Entry(val bytes: ByteArray, var lastUsed: Long)

    private val lock = Any()
    private val map = LinkedHashMap<Key, Entry>(64, 0.75f, true)
    private var total = 0L

    init { require(maxBytes > 0) }

    val sizeBytes: Long get() = synchronized(lock) { total }
    val count: Int get() = synchronized(lock) { map.size }

    fun put(key: Key, bytes: ByteArray) = synchronized(lock) {
        if (bytes.size > maxBytes) return@synchronized
        map.put(key, Entry(bytes, clock()))?.let { total -= it.bytes.size }
        total += bytes.size
        val it = map.entries.iterator()
        while (total > maxBytes && it.hasNext()) {
            val e = it.next()
            total -= e.value.bytes.size
            it.remove()
        }
    }

    fun get(key: Key): ByteArray? = synchronized(lock) {
        map[key]?.also { it.lastUsed = clock() }?.bytes
    }

    fun contains(key: Key): Boolean = synchronized(lock) { map.containsKey(key) }

    /** Cached frame of ([sourceId], [tierMaxDim]) closest to [frameIndex], at most [tolerance] frames away. */
    fun nearest(sourceId: String, frameIndex: Int, tierMaxDim: Int, tolerance: Int): Pair<Int, ByteArray>? =
        synchronized(lock) {
            var best: Key? = null
            for (k in map.keys) {
                if (k.sourceId != sourceId || k.tierMaxDim != tierMaxDim) continue
                val d = abs(k.frameIndex - frameIndex)
                if (d > tolerance) continue
                if (best == null || d < abs(best.frameIndex - frameIndex)) best = k
            }
            best?.let { k -> map[k]!!.let { it.lastUsed = clock(); k.frameIndex to it.bytes } }
        }

    fun clear(sourceId: String) = synchronized(lock) {
        val it = map.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key.sourceId == sourceId) { total -= e.value.bytes.size; it.remove() }
        }
    }

    fun clearAll() = synchronized(lock) { map.clear(); total = 0 }
}

/**
 * Background filler: decodes frames SEQUENTIALLY from a start index outward (forward first, then backward,
 * alternating) at [strideFor] spacing and stores them as encoded JPEG in a [ScrubFrameCache]. Runs as one
 * coroutine on the supplied dispatcher; [retarget] cancels the previous walk and starts a new one, never
 * blocking the caller.
 */
class ScrubFrameFiller(
    private val cache: ScrubFrameCache,
    private val codec: ScrubCodec,
    dispatcher: CoroutineDispatcher,
    private val tierMaxDim: Int = 320,
    private val quality: Int = 70,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    @Volatile private var job: Job? = null

    /** Cancel any running walk and fill around [center]; at most [maxFrames] frames, [stride] apart. */
    @Synchronized
    fun retarget(sourceId: String, source: ScrubFrameSource, center: Int, stride: Int, maxFrames: Int) {
        job?.cancel()
        job = scope.launch {
            val n = source.frameCount
            val s = stride.coerceAtLeast(1)
            var stored = 0
            var step = 0
            while (stored < maxFrames) {
                val fwd = center + step * s
                val bwd = center - step * s
                if (fwd >= n && bwd < 0) break
                for (idx in if (step == 0) intArrayOf(fwd) else intArrayOf(fwd, bwd)) {
                    if (idx !in 0 until n || stored >= maxFrames) continue
                    ensureActive()
                    val key = ScrubFrameCache.Key(sourceId, idx, tierMaxDim)
                    if (cache.contains(key)) { stored++; continue }
                    val px = source.frameArgb(idx, tierMaxDim) ?: continue
                    ensureActive()
                    cache.put(key, codec.encode(px.argb, px.width, px.height, quality))
                    stored++
                }
                step++
            }
        }
    }

    @Synchronized fun cancel() { job?.cancel(); job = null }

    /** Waits for the current walk (tests; callers do not need this). */
    suspend fun join() { job?.join() }

    /** Stops the filler for good. */
    fun close() { scope.cancel() }

    companion object {
        /** Smallest stride so that [frameCount] frames fit into [maxFrames] cache slots. */
        fun strideFor(frameCount: Int, maxFrames: Int): Int =
            if (maxFrames <= 0 || frameCount <= maxFrames) 1 else (frameCount + maxFrames - 1) / maxFrames
    }
}

/**
 * Editor-facing facade. Intended use while dragging the timeline:
 * ```
 * when (val r = scrub.onScrub(index)) {
 *     is ScrubResult.Cached -> draw(r.pixels)      // small cached frame, no full decode
 *     ScrubResult.Miss -> keep last picture / nearest keyframe
 * }
 * // finger lifted or resting:
 * scrub.onRelease(index)                             // retargets the filler; caller then renders the FULL frame
 * ```
 * [onScrub] only reads the cache and (re)targets the background filler; it never decodes video on the caller
 * thread (only the small JPEG decode of a hit).
 *
 * First version is in-memory only. Follow-up: persist JPEGs to cacheDir behind the same [ScrubFrameCache] API
 * (Klipp keeps them on disk with an index and an LRU trim at 400 MB).
 */
class ScrubFrames(
    private val cache: ScrubFrameCache,
    private val codec: ScrubCodec,
    dispatcher: CoroutineDispatcher,
    private val tierMaxDim: Int = 320,
    private val tolerance: Int = 3,
    private val budgetFrames: Int = 120,
) {
    private val filler = ScrubFrameFiller(cache, codec, dispatcher, tierMaxDim)
    private var sourceId: String? = null
    private var source: ScrubFrameSource? = null

    @Synchronized
    fun attach(sourceId: String, source: ScrubFrameSource) {
        if (this.sourceId != sourceId) filler.cancel()
        this.sourceId = sourceId; this.source = source
    }

    fun onScrub(index: Int): ScrubResult {
        val (id, src) = synchronized(this) { sourceId to source }
        if (id == null || src == null) return ScrubResult.Miss
        retarget(id, src, index)
        val hit = cache.nearest(id, index, tierMaxDim, tolerance) ?: return ScrubResult.Miss
        val px = codec.decode(hit.second) ?: return ScrubResult.Miss
        return ScrubResult.Cached(hit.first, px)
    }

    fun onRelease(index: Int) {
        val (id, src) = synchronized(this) { sourceId to source }
        if (id != null && src != null) retarget(id, src, index)
    }

    /**
     * Stands the background walk DOWN without clearing the cached frames (playback start: the decoder
     * and the engine are needed for the picture being played — Klipp's ScrubFrames.paused-flag semantics,
     * expressed as a plain cancel: the next [onScrub]/[onRelease] retarget resumes the walk around the new
     * center, and the frames already cached keep serving when scrubbing resumes).
     */
    fun standDown() { filler.cancel() }

    private fun retarget(id: String, src: ScrubFrameSource, index: Int) =
        filler.retarget(id, src, index, ScrubFrameFiller.strideFor(src.frameCount, budgetFrames), budgetFrames)

    fun release(sourceId: String) { filler.cancel(); cache.clear(sourceId) }
    fun close() { filler.close(); cache.clearAll() }
}
