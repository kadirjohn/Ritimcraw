package com.ritim.ritimcraw.proxy

import java.io.File

/** A fully committed, validated proxy. */
data class ProxyEntry(
    val key: ProxyKey,
    val file: File,
    val bytes: Long,
    val durationUs: Long,
    val frameCount: Long,
    val createdAtMs: Long,
    val lastAccessMs: Long,
)

/** Write side of one proxy build. Write the video to [videoFile] (a `.tmp` path), then [commit] or [abort]. */
interface ProxyWriteHandle {
    val key: ProxyKey
    val videoFile: File
    /** Publish. Returns the entry, or null if nothing was written / it exceeds the cap (tmp removed). */
    fun commit(durationUs: Long = -1, frameCount: Long = -1): ProxyEntry?
    /** Drop the tmp files. Safe to call repeatedly and after commit (no-op). */
    fun abort()
}

/**
 * Pure-JVM proxy cache (docs/PROXY_PLAN.md section 3).
 *
 * Layout, per key base name B = "<fingerprint>-<sigHash>":
 *  - `B.mp4` + `B.manifest` : a published entry. The manifest (key=value text; the plan's "json" role,
 *    JVM unit tests have no JSON library) holds the signature hash, video byte size, duration, frame count and
 *    creation time. Last access = the manifest file's mtime (touch = setLastModified, no rewrite).
 *  - `B.mp4.tmp` / `B.manifest.tmp` : an in-progress write.
 *
 * Protocol:
 *  1. [begin] removes stale tmp for the key and hands out a handle; the writer fills `videoFile` (`B.mp4.tmp`).
 *  2. commit: old manifest is dropped first, the manifest tmp is written, then `.mp4.tmp -> .mp4`
 *     and, LAST, `.manifest.tmp -> .manifest` (each an atomic Files.move). A reader recognises an entry only through
 *     its manifest, so a crash at any point leaves either nothing or an unreadable leftover.
 *  3. [lookup] is the single gate for playback: only a manifest whose signature hash matches the key and whose
 *     video exists with exactly the recorded size resolves. A `.tmp` never resolves. A hit touches last access.
 *  4. [sweep] (run at startup, before any job) deletes every `*.tmp`, every `.mp4` without a manifest and
 *     every manifest without a `.mp4`, except the tmp of handles currently open.
 *  5. [evictToCap] deletes least-recently-used entries until total <= cap (protected keys, the key of an
 *     open handle and, after each commit, the committed key are never evicted). An entry bigger than the cap
 *     is not published.
 * There is no resume: an interrupted build restarts from scratch.
 *
 * Thread-safe through one lock. No Android classes.
 */
class ProxyCache(
    private val storage: ProxyStorage,
    private val capBytes: Long = ProxyPolicy().capBytes,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val open = HashSet<String>() // base names with a live handle

    private fun mp4(k: ProxyKey) = k.baseName + ".mp4"
    private fun man(k: ProxyKey) = k.baseName + ".manifest"

    fun begin(key: ProxyKey): ProxyWriteHandle = synchronized(lock) {
        storage.delete(mp4(key) + TMP)
        storage.delete(man(key) + TMP)
        open += key.baseName
        Handle(key)
    }

    fun lookup(key: ProxyKey): ProxyEntry? = synchronized(lock) {
        val e = readEntry(key) ?: return null
        val now = clock()
        storage.setLastModified(man(key), now)
        e.copy(lastAccessMs = now)
    }

    /** Like [lookup] but does not touch last access. */
    fun peek(key: ProxyKey): ProxyEntry? = synchronized(lock) { readEntry(key) }

    /** Remove stale leftovers. Returns the number of files deleted. */
    fun sweep(): Int = synchronized(lock) {
        val names = storage.names()
        val set = names.toSet()
        var n = 0
        for (name in names) {
            val base = name.substringBefore('.')
            val stale = when {
                name.endsWith(TMP) -> base !in open
                name.endsWith(".mp4") -> (base + ".manifest") !in set
                name.endsWith(".manifest") -> (base + ".mp4") !in set
                else -> false
            }
            if (stale && storage.delete(name)) n++
        }
        n
    }

    fun entries(): List<ProxyEntry> = synchronized(lock) { allEntries() }

    /** Sum of committed video bytes. */
    fun totalBytes(): Long = synchronized(lock) { allEntries().sumOf { it.bytes } }

    /** Committed video bytes of one source clip (every signature of that fingerprint). */
    fun bytesForClip(fingerprint: String): Long = synchronized(lock) {
        allEntries().filter { it.key.fingerprint == fingerprint }.sumOf { it.bytes }
    }

    fun clear(key: ProxyKey): Boolean = synchronized(lock) {
        var any = storage.delete(man(key))
        any = storage.delete(mp4(key)) or any
        if (key.baseName !in open) {
            any = storage.delete(mp4(key) + TMP) or any
            any = storage.delete(man(key) + TMP) or any
        }
        any
    }

    /** Delete every entry of one source clip. */
    fun clearClip(fingerprint: String): Int = synchronized(lock) {
        allEntries().filter { it.key.fingerprint == fingerprint }.count { clear(it.key) }
    }

    /** Delete everything (entries and leftovers) except the tmp of open handles. */
    fun clearAll(): Unit = synchronized(lock) {
        for (name in storage.names()) {
            if (name.endsWith(TMP) && name.substringBefore('.') in open) continue
            storage.delete(name)
        }
    }

    /**
     * Evict least-recently-used entries until total <= cap. Returns the evicted keys, oldest first.
     * [protect] (e.g. the key playing right now) and the key of a handle currently open (a build
     * in progress, plan section 6: eviction deletes only proxies not playing and not being built)
     * are never evicted.
     */
    fun evictToCap(protect: Set<ProxyKey> = emptySet()): List<ProxyKey> = synchronized(lock) {
        val all = allEntries()
        var total = all.sumOf { it.bytes }
        val evicted = ArrayList<ProxyKey>()
        for (e in all.sortedWith(compareBy({ it.lastAccessMs }, { it.createdAtMs }, { it.key.baseName }))) {
            if (total <= capBytes) break
            if (e.key in protect || e.key.baseName in open) continue
            clear(e.key)
            total -= e.bytes
            evicted += e.key
        }
        evicted
    }

    // ---- internals (lock held) ----

    private fun allEntries(): List<ProxyEntry> = storage.names()
        .filter { it.endsWith(".manifest") }
        .mapNotNull { n ->
            val base = n.removeSuffix(".manifest")
            val i = base.indexOf('-')
            if (i <= 0) return@mapNotNull null
            val key = try {
                ProxyKey(base.substring(0, i), base.substring(i + 1))
            } catch (_: IllegalArgumentException) {
                return@mapNotNull null
            }
            readEntry(key)
        }

    private fun readEntry(key: ProxyKey): ProxyEntry? {
        val bytes = storage.read(man(key)) ?: return null
        val p = java.util.Properties()
        try { p.load(bytes.inputStream()) } catch (_: Exception) { return null }
        if (p.getProperty("sigHash") != key.sigHash || p.getProperty("fingerprint") != key.fingerprint) return null
        val size = p.getProperty("videoBytes")?.toLongOrNull() ?: return null
        if (size <= 0 || !storage.exists(mp4(key)) || storage.size(mp4(key)) != size) return null
        return ProxyEntry(
            key = key,
            file = storage.fileFor(mp4(key)),
            bytes = size,
            durationUs = p.getProperty("durationUs")?.toLongOrNull() ?: -1,
            frameCount = p.getProperty("frameCount")?.toLongOrNull() ?: -1,
            createdAtMs = p.getProperty("createdAtMs")?.toLongOrNull() ?: 0,
            lastAccessMs = storage.lastModified(man(key)),
        )
    }

    private inner class Handle(override val key: ProxyKey) : ProxyWriteHandle {
        private var done = false
        override val videoFile: File = storage.fileFor(mp4(key) + TMP)

        override fun commit(durationUs: Long, frameCount: Long): ProxyEntry? = synchronized(lock) {
            check(!done) { "handle already finished" }
            done = true
            open -= key.baseName
            val tmpV = mp4(key) + TMP
            val tmpM = man(key) + TMP
            val size = if (storage.exists(tmpV)) storage.size(tmpV) else 0L
            if (size <= 0L || size > capBytes) {
                storage.delete(tmpV); storage.delete(tmpM)
                return null
            }
            val now = clock()
            val text = buildString {
                append("fingerprint=").append(key.fingerprint).append('\n')
                append("sigHash=").append(key.sigHash).append('\n')
                append("videoBytes=").append(size).append('\n')
                append("durationUs=").append(durationUs).append('\n')
                append("frameCount=").append(frameCount).append('\n')
                append("createdAtMs=").append(now).append('\n')
            }
            try {
                storage.delete(man(key))                 // old entry stops resolving before its video is replaced
                storage.write(tmpM, text.toByteArray(Charsets.UTF_8))
                storage.moveAtomic(tmpV, mp4(key))
                storage.moveAtomic(tmpM, man(key))       // publish point
            } catch (e: Exception) {
                storage.delete(tmpV); storage.delete(tmpM); storage.delete(mp4(key)); storage.delete(man(key))
                return null
            }
            storage.setLastModified(man(key), now)
            evictToCap(setOf(key))
            readEntry(key)
        }

        override fun abort() = synchronized(lock) {
            if (done) return@synchronized
            done = true
            open -= key.baseName
            storage.delete(mp4(key) + TMP)
            storage.delete(man(key) + TMP)
            Unit
        }
    }

    private companion object { const val TMP = ".tmp" }
}
