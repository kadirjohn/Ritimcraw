package com.ritim.ritimcraw.proxy

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProxyCacheTest {
    private lateinit var root: File
    private lateinit var storage: DirProxyStorage
    private var now = 1_000_000L

    @Before fun setUp() {
        root = Files.createTempDirectory("proxycache").toFile()
        storage = DirProxyStorage(root)
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun cache(cap: Long = 1000) = ProxyCache(storage, cap) { now += 10_000; now }

    private fun sig(fp: String = "abc123", settings: ProxySettings = ProxySettings()) = ProxySignature(
        sourceFingerprint = fp, sourceSize = 100, sourceModifiedMs = 5,
        settings = settings, colorSpecKey = "bt2020-full-untagged-10", kind = ProxySourceKind.ENCODED,
    )

    private fun put(c: ProxyCache, key: ProxyKey, bytes: Int): ProxyEntry? {
        val h = c.begin(key)
        h.videoFile.writeBytes(ByteArray(bytes) { 1 })
        return h.commit(durationUs = 1_000_000, frameCount = 30)
    }

    @Test fun dirLayoutMatchesThePlan() {
        // the plan pins the path: filesDir (NOT cacheDir, the OS may clear it) /cache/proxies,
        // the same nesting as filesDir/cache/imagestab
        assertEquals(
            File("/data/user/0/app/files/cache/proxies"),
            ProxyDirs.cacheDir(File("/data/user/0/app/files")),
        )
        // the storage sits exactly there and keeps names inside it
        val s = ProxyDirs.storage(root)
        s.write("abc-1.mp4", ByteArray(1))
        assertEquals(listOf("abc-1.mp4"), s.names())
        assertEquals(listOf("abc-1.mp4"), root.resolve("cache/proxies").list()?.toList())
    }

    @Test fun signatureIsStableAndFilenameSafe() {
        val a = sig(); val b = sig()
        assertEquals(a.canonical(), b.canonical())
        assertEquals(a.key(), b.key())
        assertEquals(16, a.hash().length)
        assertTrue(Regex("[0-9a-f]{16}").matches(a.hash()))
        assertEquals("abc123-${a.hash()}", a.key().baseName)
    }

    @Test fun signatureChangesWithEveryField() {
        val base = sig()
        val variants = listOf(
            base.copy(sourceFingerprint = "abc124"), base.copy(sourceSize = 101), base.copy(sourceModifiedMs = 6),
            base.copy(settings = ProxySettings(shortSide = 720)), base.copy(settings = ProxySettings(bitrateClass = "hi")),
            base.copy(settings = ProxySettings(keyFrameIntervalSec = 2)), base.copy(colorSpecKey = "bt709-limited"),
            base.copy(kind = ProxySourceKind.MCRAW), base.copy(mcrawTransferVersion = 1),
            base.copy(encoderVersion = base.encoderVersion + 1), base.copy(headroomAffine = true),
        )
        val hashes = (variants + base).map { it.hash() }
        assertEquals(hashes.size, hashes.toSet().size)
    }

    @Test fun commitPublishesAndLookupResolves() {
        val c = cache(); val k = sig().key()
        val e = put(c, k, 100)
        assertNotNull(e)
        val hit = c.playable(k)
        assertNotNull(hit)
        assertEquals(100L, hit!!.bytes)
        assertEquals(30L, hit.frameCount)
        assertTrue(hit.file.isFile)
        // no tmp left behind, only the published pair
        assertEquals(listOf("${k.baseName}.json", "${k.baseName}.mp4"), storage.names())
    }

    @Test fun abortLeavesNothingAndNeverResolves() {
        val c = cache(); val k = sig().key()
        val h = c.begin(k)
        h.videoFile.writeBytes(ByteArray(50))
        assertNull(c.playable(k)) // a .tmp never resolves
        h.abort()
        assertTrue(storage.names().isEmpty())
        assertNull(c.playable(k))
    }

    @Test fun crashLeftoversNeverResolveAndSweepRemovesThem() {
        val k = sig().key()
        // simulated crash 1: tmp video only. crash 2: video renamed but manifest not published.
        storage.write("${k.baseName}.mp4.tmp", ByteArray(40))
        val k2 = sig("def456").key()
        storage.write("${k2.baseName}.mp4", ByteArray(40))
        storage.write("${k2.baseName}.json.tmp", "fingerprint=def456\n".toByteArray())
        val c = cache() // fresh process: no open handles
        assertNull(c.playable(k))
        assertNull(c.playable(k2))
        assertEquals(3, c.sweep())
        assertTrue(storage.names().isEmpty())
    }

    @Test fun sweepSparesTmpOfOpenHandleAndPlayableRejectsSizeMismatch() {
        val c = cache(); val k = sig().key()
        val h = c.begin(k)
        h.videoFile.writeBytes(ByteArray(10))
        assertEquals(0, c.sweep())
        assertTrue(h.videoFile.isFile)
        h.commit()
        // truncate the published video: manifest size check must reject it
        File(root, "${k.baseName}.mp4").writeBytes(ByteArray(3))
        assertNull(c.playable(k))
    }

    @Test fun recommitReplacesAtomicallyAndEmptyWriteIsRejected() {
        val c = cache(); val k = sig().key()
        put(c, k, 100)
        put(c, k, 60)
        assertEquals(60L, c.playable(k)!!.bytes)
        val h = c.begin(k); // nothing written
        assertNull(h.commit())
        assertEquals(60L, c.playable(k)?.bytes) // old entry untouched by an empty commit
    }

    @Test fun lruEvictsOldestToCapAndSparesJustCommitted() {
        val c = cache(cap = 250)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kc = sig("c1").key()
        put(c, ka, 100); put(c, kb, 100)
        assertEquals(200L, c.totalBytes())
        put(c, kc, 100) // 300 > 250 -> evict least recently used = a
        assertNull(c.peekPlayable(ka))
        assertNotNull(c.peekPlayable(kb)); assertNotNull(c.peekPlayable(kc))
        assertTrue(c.totalBytes() <= 250)
    }

    @Test fun playableTouchesLastAccess() {
        val c = cache(cap = 250)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kc = sig("c1").key()
        put(c, ka, 100); put(c, kb, 100)
        val before = c.peekPlayable(ka)!!.lastAccessMs
        assertNotNull(c.playable(ka)) // a is now newer than b
        assertTrue(c.peekPlayable(ka)!!.lastAccessMs > before)
        put(c, kc, 100)
        assertNotNull(c.peekPlayable(ka)); assertNull(c.peekPlayable(kb)) // b evicted instead
    }

    @Test fun evictionSparesTheEntryBeingRebuiltAndProtectedKeys() {
        // plan section 6: eviction deletes only proxies not playing and not being built.
        // The storage-page eviction hook: the same storage, a tighter user cap.
        val c = cache(cap = 90)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kp = sig("c1").key()
        put(c, ka, 30); put(c, kb, 30); put(c, kp, 30) // 90 <= 90: all published
        val tight = ProxyCache(storage, 50) { now += 10_000; now }
        val h = tight.begin(kb) // kb is being rebuilt: its published entry must survive
        val evicted = tight.evictToCap(protect = setOf(kp)) // kp is playing: spared too
        assertEquals(listOf(ka), evicted) // only the untouched, least recent one goes
        assertNull(tight.peekPlayable(ka))
        assertNotNull(tight.peekPlayable(kb)); assertNotNull(tight.peekPlayable(kp))
        h.abort()
    }

    @Test fun signatureOverloadStoresTheFullSignatureLine() {
        val c = cache()
        val s = sig()
        val h = c.begin(s) // the writer begins from the signature, not just the key
        h.videoFile.writeBytes(ByteArray(50))
        val e = h.commit(durationUs = 1_000_000, frameCount = 30)
        assertNotNull(e)
        assertEquals(s.canonical(), e!!.signatureLine)
        assertEquals(s.canonical(), c.peekPlayable(s.key())!!.signatureLine) // survives a re-read
    }

    @Test fun sourceChangeProtectionIsTheKeyNotTheEntry() {
        // plan section 3.1 "açılışta tekrar bakılır": size + mtime enter the signature, so a changed
        // source resolves a DIFFERENT key — the stale entry is never served for it, the clip plays
        // from the original until the new proxy exists
        val c = cache()
        val before = sig(fp = "abc123", settings = ProxySettings())
        put(c, before.key(), 100)
        val after = before.copy(sourceModifiedMs = before.sourceModifiedMs + 1)
        assertNotEquals(before.key(), after.key())
        assertNull(c.playable(after.key()))
        assertNotNull(c.playable(before.key())) // still on disk; LRU or clearClip reaps it
    }

    @Test fun oversizeEntryIsNotPublished() {
        val c = cache(cap = 100)
        assertNull(put(c, sig().key(), 101))
        assertTrue(storage.names().isEmpty())
    }

    @Test fun sizesPerClipTotalAndClear() {
        val c = cache()
        val s1 = sig("a1"); val s2 = sig("a1", ProxySettings(shortSide = 720)); val s3 = sig("b1")
        put(c, s1.key(), 100); put(c, s2.key(), 50); put(c, s3.key(), 70)
        assertEquals(150L, c.bytesForClip("a1"))
        assertEquals(70L, c.bytesForClip("b1"))
        assertEquals(220L, c.totalBytes())
        assertTrue(c.clear(s2.key()))
        assertEquals(100L, c.bytesForClip("a1"))
        // a leftover tmp plus entries: clearAll removes everything
        storage.write("zzz-1.mp4.tmp", ByteArray(5))
        c.clearAll()
        assertTrue(storage.names().isEmpty())
        assertEquals(0L, c.totalBytes())
    }

    @Test fun policyAutoGenerationDefaultsAndMcrawOptIn() {
        val def = ProxyPolicy()
        assertEquals(PROXY_CAP_DEFAULT_BYTES, def.capBytes)
        fun enc(w: Int, h: Int, fps: Double) = ProxyClipInfo(ProxySourceKind.ENCODED, w, h, fps)
        assertTrue(def.shouldAutoGenerate(enc(7680, 4320, 30.0)))   // 8K
        assertTrue(def.shouldAutoGenerate(enc(3840, 2160, 60.0)))   // 4K60
        assertFalse(def.shouldAutoGenerate(enc(3840, 2160, 30.0)))  // 4K30
        assertFalse(def.shouldAutoGenerate(enc(1920, 1080, 120.0))) // small stays off
        val mcraw = ProxyClipInfo(ProxySourceKind.MCRAW, 4000, 3000, 24.0)
        assertFalse(def.shouldAutoGenerate(mcraw))
        assertTrue(def.copy(autoMcraw = true).shouldAutoGenerate(mcraw))
        assertFalse(def.copy(autoEncoded = false).shouldAutoGenerate(enc(7680, 4320, 30.0)))
    }

    @Test fun stateMapQueuePauseRequeueAndFailures() {
        val m = ProxyStateMap()
        val a = sig("a1").key(); val b = sig("b1").key()
        assertEquals(ProxyState.None, m.state(a))
        assertTrue(m.enqueue(a)); assertTrue(m.enqueue(b, front = true))
        assertFalse(m.enqueue(a))
        m.pause(); assertNull(m.next()); m.resume()
        assertEquals(b, m.next()); assertEquals(ProxyState.Building, m.state(b))
        m.requeue(b) // export started: back to the head
        assertEquals(listOf(b, a), m.queued())
        assertEquals(b, m.next()); m.failed(b, "no hevc"); m.ready(a.also { m.next() })
        assertEquals(ProxyState.Failed, m.state(b)); assertEquals(1, m.failureCount(b))
        assertEquals(ProxyState.Ready, m.state(a))
        assertNotEquals(ProxyState.Queued, m.state(a))
    }

    // ---- decisions 2026-11-26 (docs/PROXY_PLAN.md) ----

    @Test fun capDefaultIs20GibAndIsTheCacheDefault() {
        assertEquals(21_474_836_480L, PROXY_CAP_DEFAULT_BYTES)
        assertEquals(PROXY_CAP_DEFAULT_BYTES, ProxyPolicy().capBytes)
        val c = ProxyCache(storage) // default cap: a small entry publishes
        assertNotNull(put(c, sig().key(), 10))
    }

    @Test fun manifestIsJsonRoundTripsAndEscapes() {
        val m = ProxyManifest("abc123", "0123456789abcdef", 123_456_789_012L, 8_000_000, 240, 1_764_000_000_000L, "v1|a=\"q\"\\x|\u00e9")
        val text = ProxyManifestCodec.render(m)
        assertTrue(text.trimStart().startsWith("{"))
        assertEquals(m, ProxyManifestCodec.parse(text.toByteArray(Charsets.UTF_8)))
        val noSig = m.copy(signature = null)
        assertEquals(noSig, ProxyManifestCodec.parse(ProxyManifestCodec.render(noSig).toByteArray()))
    }

    @Test fun truncatedOrGarbageManifestIsToleratedEverywhere() {
        val full = ProxyManifestCodec.render(
            ProxyManifest("abc123", "0123456789abcdef", 50, 1, 2, 3, "sig\\with\"escapes"),
        )
        // every strict prefix of a valid manifest is unreadable (null), never an exception
        for (n in 0 until full.trimEnd().length) {
            assertNull("prefix $n", ProxyManifestCodec.parse(full.substring(0, n).toByteArray()))
        }
        for (bad in listOf("", "null", "[]", "{}", "{\"v\":2}", "garbage\u0000\u0001", "{\"v\":1,\"fingerprint\":1}", "{} trailing")) {
            assertNull(bad, ProxyManifestCodec.parse(bad.toByteArray()))
        }
    }

    @Test fun truncatedPublishedManifestNeverPlaysAndSweepRemovesIt() {
        val c = cache(); val k = sig().key()
        put(c, k, 40)
        val manFile = File(root, "${k.baseName}.json")
        val full = manFile.readBytes()
        manFile.writeBytes(full.copyOf(full.size / 2)) // torn write
        assertNull(c.playable(k))
        assertNull(c.peekPlayable(k))
        assertTrue(c.entries().isEmpty())
        assertEquals(1, c.sweep()) // the torn manifest goes; the video then has no manifest
        assertEquals(1, c.sweep()) // ... and is removed by the next pass
        assertTrue(storage.names().isEmpty())
    }

    @Test fun manifestIsPublishedViaTmpThenRename() {
        val c = cache(); val k = sig().key()
        val h = c.begin(k)
        h.videoFile.writeBytes(ByteArray(10))
        h.commit()
        assertFalse(storage.names().any { it.endsWith(".tmp") })
        assertTrue(storage.exists("${k.baseName}.json"))
    }

    @Test fun playableForSignatureRejectsAMismatchingStoredSignature() {
        val c = cache(); val s = sig()
        val h = c.begin(s); h.videoFile.writeBytes(ByteArray(20)); h.commit()
        assertNotNull(c.playable(s))
        // a forged same-key / different-settings signature (simulated hash collision) must not resolve
        val other = s.copy(settings = ProxySettings(shortSide = 720))
        val f = File(root, "${s.key().baseName}.json")
        val m = ProxyManifestCodec.parse(f.readBytes())!!
        f.writeBytes(ProxyManifestCodec.render(m.copy(signature = other.canonical())).toByteArray())
        assertNull(c.playable(s))
        assertNotNull(c.playable(s.key())) // the key-only gate does not look at the signature text
    }

    @Test fun hashLengthIs16HexAndCollisionOddsAreNegligible() {
        assertEquals(16, PROXY_SIG_HASH_HEX_CHARS)
        assertEquals(PROXY_SIG_HASH_HEX_CHARS, sig().hash().length)
        // birthday bound p ~ n^2 / 2^65 (numbers in docs/PROXY_PLAN.md decision (d))
        fun p(n: Double) = n * n / 2.0 / Math.pow(2.0, 64.0)
        assertTrue(p(100.0) < 1e-15)  // 100 variants of one clip: the realistic key-space per fingerprint
        assertTrue(p(1.0e6) < 1e-7)   // a million hashes even if compared across ALL clips
    }

    @Test fun protectSourceIsHonouredByEvictionAndComposes() {
        val c0 = cache(cap = 90)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kc = sig("c1").key()
        put(c0, ka, 30); put(c0, kb, 30); put(c0, kc, 30)
        val inUse = ProxyInUse()
        val state = ProxyStateMap()
        state.enqueue(kb); assertEquals(kb, state.next()) // kb is Building
        inUse.acquire(ka)
        val src = ProxyProtectSource.union(inUse, state.buildingSource())
        assertEquals(setOf(ka, kb), src.protectedKeys())
        val tight = ProxyCache(storage, 40, src) { now += 10_000; now }
        assertEquals(listOf(kc), tight.evictToCap()) // only the unprotected one goes; protected stay even over cap
        assertNotNull(tight.peekPlayable(ka)); assertNotNull(tight.peekPlayable(kb))
        inUse.release(ka)
        assertEquals(listOf(ka), tight.evictToCap()) // released: evictable (kb still Building)
        assertNotNull(tight.peekPlayable(kb))
        assertTrue(ProxyProtectSource.NONE.protectedKeys().isEmpty())
    }

    @Test fun inUseIsReferenceCounted() {
        val u = ProxyInUse(); val k = sig().key()
        u.acquire(k); u.acquire(k)
        u.release(k); assertTrue(u.isInUse(k))
        u.release(k); assertFalse(u.isInUse(k))
        u.release(k) // extra release is a no-op
        assertTrue(u.protectedKeys().isEmpty())
    }

    // ---- C3 boundary fill: cap edges, manifest field variants, eviction ties ----

    @Test fun evictToCapAtExactlyTheCapEvictsNothing() {
        // total == capBytes is AT the cap (the loop's `total <= capBytes` break fires first):
        // no evictions, an empty list, everything still resolvable.
        val c = cache(cap = 90)
        val ka = sig("a1").key(); val kb = sig("b1").key(); val kc = sig("c1").key()
        put(c, ka, 30); put(c, kb, 30); put(c, kc, 30)
        assertEquals(90L, c.totalBytes())
        assertEquals(emptyList<ProxyKey>(), c.evictToCap())
        assertNotNull(c.peekPlayable(ka)); assertNotNull(c.peekPlayable(kb)); assertNotNull(c.peekPlayable(kc))
        // one byte under the cap: still nothing to evict
        assertEquals(emptyList<ProxyKey>(), ProxyCache(storage, 89 + 1).evictToCap())
        // one byte OVER: exactly the least-recently-used entry goes
        val tight = ProxyCache(storage, 89) { now += 10_000; now }
        assertEquals(1, tight.evictToCap().size)
        assertEquals(60L, tight.totalBytes())
    }

    @Test fun aZeroCapPublishesNothingAndRejectsEveryCommit() {
        val c = cache(cap = 0)
        assertNull(put(c, sig().key(), 10))
        assertTrue(storage.names().isEmpty())
        // even a 1-byte video is over a 0 cap
        val h = c.begin(sig().key())
        h.videoFile.writeBytes(ByteArray(1))
        assertNull(h.commit())
        assertTrue(storage.names().isEmpty())
    }

    @Test fun manifestWithBadFieldTypesOrNonIntegerNumbersIsRejected() {
        // the parser reads JSON numbers as Doubles; num() demands a finite integral Double.
        // String where a number belongs, a non-integer number, a float "v", extra junk.
        val good = ProxyManifestCodec.render(ProxyManifest("abc123", "0123456789abcdef", 50, 1, 2, 3, null))
        val variants = listOf(
            good.replace("\"videoBytes\":50", "\"videoBytes\":\"50\""), // string, not number
            good.replace("\"videoBytes\":50", "\"videoBytes\":50.5"),  // non-integer
            good.replace("\"v\":1", "\"v\":1.5"),                      // version non-integer
            good.replace("\"v\":1", "\"v\":2.0"),                      // wrong (integral) version
            good.replace("\"fingerprint\":\"abc123\"", "\"fingerprint\":123"),
            good.replace("\"sigHash\":\"0123456789abcdef\"", "\"sigHash\":null"),
            "{\"v\":1,\"fingerprint\":\"abc123\",\"sigHash\":\"0123456789abcdef\",\"videoBytes\":1e300,\"durationUs\":1,\"frameCount\":1,\"createdAtMs\":1}", // 1e300 parses, is integral (finite double) -> ACCEPTED, videoBytes=1e300 as Long
        )
        for ((i, text) in variants.withIndex()) {
            val parsed = ProxyManifestCodec.parse(text.toByteArray())
            if (i == 6) assertNotNull("1e300 is a finite integral double: accepted by parse", parsed)
            else assertNull("variant $i: $text", parsed)
        }
        // a non-finite number literal never parses (JsonParser throws on "Infinity")
        assertNull(ProxyManifestCodec.parse("{\"v\":1,\"videoBytes\":Infinity}".toByteArray()))
    }

    @Test fun manifestNumbersDefaultWhenAbsentButVideoBytesIsRequired() {
        // duration/frame/created default when missing (-1/-1/0); a missing videoBytes or
        // fingerprint/sigHash rejects the manifest outright.
        val base = "{\"v\":1,\"fingerprint\":\"abc123\",\"sigHash\":\"0123456789abcdef\",\"videoBytes\":50"
        assertEquals(
            ProxyManifest("abc123", "0123456789abcdef", 50, -1, -1, 0, null),
            ProxyManifestCodec.parse((base + "}").toByteArray()),
        )
        assertNull(ProxyManifestCodec.parse(("{\"v\":1,\"fingerprint\":\"abc123\",\"sigHash\":\"0123456789abcdef\"," + "\"durationUs\":1}").toByteArray()))
        // a NON-POSITIVE videoBytes parses but never resolves: readEntry's size gate
        val c = cache(); val k = sig().key()
        put(c, k, 40)
        File(root, "${k.baseName}.json").writeBytes(
            ProxyManifestCodec.render(ProxyManifest(k.fingerprint, k.sigHash, 0, 1, 2, 3, null)).toByteArray(),
        )
        assertNull(c.playable(k))
        assertNull(c.peekPlayable(k))
        assertTrue(c.entries().isEmpty())
    }

    @Test fun aManifestWhoseFieldsMismatchTheFileNameNeverResolves() {
        // The manifest parses fine but carries ANOTHER clip's fingerprint/sigHash: the
        // key-match gate (readEntry) rejects it — playable and entries never see it.
        val c = cache(); val k = sig().key()
        put(c, k, 40)
        File(root, "${k.baseName}.json").writeBytes(
            ProxyManifestCodec.render(ProxyManifest("zzz999", "ffffffffffffffff", 40, 1, 2, 3, null)).toByteArray(),
        )
        assertNull(c.playable(k))
        assertTrue(c.entries().isEmpty())
        // Fixed (was night C3 finding): sweep's json branch also reaps a manifest that parses
        // but does not match the file name; the video then has no manifest and goes next pass.
        assertEquals(1, c.sweep())
        assertEquals(1, c.sweep())
        assertEquals(0, storage.names().size)
    }

    @Test fun evictionPrefersTheOldestLastAccessThenTheOldestCreatedThenTheName() {
        // All entries at one lastAccess: the createdAt tie-break runs; with both equal the
        // baseName (alphabetical) order decides. Deterministic and pinned.
        val keys = listOf("c1", "a1", "b1").map { sig(it).key() }
        // one commit per key with the SAME clock reading (createdAt equal, lastAccess equal)
        val fixed = ProxyCache(storage, 1_000_000) { 42L }
        for (k in keys) put(fixed, k, 30)
        // at cap: eviction evicts NONE
        assertEquals(emptyList<ProxyKey>(), fixed.evictToCap())
        // cap 30 with total 90: TWO evictions in name order (a1, b1), leaving c1 (30 <= 30)
        val tight = ProxyCache(storage, 30) { 42L }
        assertEquals(listOf(sig("a1").key(), sig("b1").key()), tight.evictToCap())
        assertNull(tight.peekPlayable(sig("a1").key()))
        assertNull(tight.peekPlayable(sig("b1").key()))
        assertNotNull(tight.peekPlayable(sig("c1").key()))
        assertEquals(30L, tight.totalBytes())
    }

    @Test fun allProtectedEntriesLeaveTheCacheOverCapWithNothingEvicted() {
        // Over cap with EVERY key protected: eviction returns empty, the cache stays
        // honestly over cap (the caller's problem, not silent data loss).
        val c0 = cache(cap = 90)
        val ka = sig("a1").key(); val kb = sig("b1").key()
        put(c0, ka, 30); put(c0, kb, 30)
        val inUse = ProxyInUse()
        inUse.acquire(ka); inUse.acquire(kb)
        val tight = ProxyCache(storage, 10, inUse) { now += 10_000; now }
        assertEquals(emptyList<ProxyKey>(), tight.evictToCap())
        assertEquals(60L, tight.totalBytes()) // still over the 10-byte cap
        assertNotNull(tight.peekPlayable(ka)); assertNotNull(tight.peekPlayable(kb))
        inUse.release(ka); inUse.release(kb)
        // released: the cap is enforced again, LRU order
        assertEquals(listOf(ka, kb), tight.evictToCap())
        assertTrue(tight.entries().isEmpty())
    }

    @Test fun clearClipRemovesEverySignatureOfTheFingerprintAndKeepsOthers() {
        val c = cache()
        val s1 = sig("a1"); val s2 = sig("a1", ProxySettings(shortSide = 720)); val s3 = sig("b1")
        put(c, s1.key(), 100); put(c, s2.key(), 50); put(c, s3.key(), 70)
        assertEquals(2, c.clearClip("a1"))
        assertNull(c.peekPlayable(s1.key())); assertNull(c.peekPlayable(s2.key()))
        assertNotNull(c.peekPlayable(s3.key()))
        assertEquals(0, c.clearClip("a1")) // already gone: 0
        assertEquals(70L, c.totalBytes())
    }

    @Test fun proxyKeyRejectsUnsafeCharactersAndKeepsFilenameSafeNames() {
        // the key IS the file name: anything outside [A-Za-z0-9] must throw, not become a path
        for (bad in listOf("abc/def", "../etc", "a b", "abc-def", "")) {
            try {
                ProxyKey(bad, "0123456789abcdef"); fail("fingerprint '$bad' must be rejected")
            } catch (e: IllegalArgumentException) { /* required */ }
        }
        try {
            ProxyKey("abc123", "../x"); fail("sigHash must be rejected")
        } catch (e: IllegalArgumentException) { /* required */ }
        assertEquals("abc123-0123456789abcdef", ProxyKey("abc123", "0123456789abcdef").baseName)
    }
}
