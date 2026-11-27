package com.ritim.ritimcraw.proxy

/**
 * The encode contract ONE proxy build runs under (docs/PROXY_PLAN.md section 2.1,
 * the G3 production layer). Everything the writer needs is decided HERE, as pure
 * data — the MediaCodec adapter only executes it, so the decisions are JVM-tested
 * and the device leg is a thin, gated adapter (the Mp4MediaWriter pattern).
 *
 * Plan decisions carried verbatim:
 *  - codec: HEVC ("video/hevc"); H.264 is the FALLBACK only when the device has
 *    no HEVC encoder — which codec actually ran is written into the signature
 *    (section 4: "HEVC yoksa H.264 geri dönüşü imzaya yazılır");
 *  - bit depth: 10 (Main10) — the proxy is ALWAYS 10-bit HEVC (owner decision
 *    2026-11-25/26, [ProxySettings.bitDepth]); an 8-bit SOURCE is never
 *    upconverted (section 2.1: "8->10 yükseltme yapılmaz") — the ENCODE is
 *    10-bit, the samples keep the source's own depth until the encoder;
 *  - key frame interval: 1 s ([ProxySettings.keyFrameIntervalSec]) — the seek
 *    cost the plan §2.1 row names;
 *  - B-frames: OFF (section 2.1: "B-kare | kapalı | seek/decode gecikmesi,
 *    sıralı PTS");
 *  - audio: none (section 1: "Ses | yok | orijinalden" — the proxy is video-only);
 *  - PTS: the source's own frame timestamps, birebir (section 2.1 "Fps" row:
 *    "PTS'ler orijinalle birebir") — the writer copies each frame's own pts,
 *    never the wall clock;
 *  - color tags: the SOURCE's [EncodedColorSpec] values, unchanged (section
 *    2.1 "10-bit ve renk etiketi": same matrix/range/transfer/primaries; an
 *    untagged Mi-Log transfer stays untagged — never invented here).
 */
data class ProxyBuildSpec(
    /** The signature the build is FOR — the settings/kind/encoder-version identity. */
    val signature: ProxySignature,
    /** The source's frame count; the committed proxy must carry exactly this many frames. */
    val frameCount: Long,
    /** The source's duration (last pts - first pts + one frame), or -1 when the writer cannot know it. */
    val durationUs: Long = -1L,
) {
    val settings: ProxySettings get() = signature.settings
    val key: ProxyKey get() = signature.key()
}

/**
 * The pure downscale decision (plan section 2.1, "Çözünürlük: kısa kenar 1080"):
 * the proxy's size for a [width] x [height] source at [shortSide] target —
 * box-average, never an upscale. Even dimensions (the encoder contract).
 */
object ProxyDownscale {

    /** The proxy size: short side scaled to [shortSide] when the source is bigger; unchanged otherwise. */
    fun outputSize(width: Int, height: Int, shortSide: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty frame (${width}x$height)" }
        require(shortSide > 0) { "shortSide must be positive" }
        val short = minOf(width, height)
        if (short <= shortSide) return even(width, height)
        val scale = shortSide.toDouble() / short
        val w = (width * scale).toInt()
        val h = (height * scale).toInt()
        return even(w, h)
    }

    /** True when the source is LARGER than the proxy target — a build is a downscale. */
    fun isDownscale(width: Int, height: Int, shortSide: Int): Boolean =
        minOf(width, height) > shortSide

    private fun even(w: Int, h: Int): Pair<Int, Int> {
        val ew = w and 1.inv()
        val eh = h and 1.inv()
        return if (ew > 0 && eh > 0) ew to eh else 2 to 2
    }
}

/**
 * The bitrate of one proxy build, as pure data (plan section 2.1 "Bitrate" row:
 * "VBR, başlangıç önerisi 1080p'de ~20 Mbit/s"; the exact value is measured on
 * the device — this is the starting point the plan itself names). The proxy is
 * ALWAYS ~1080p (the downscale contract), so the reference IS the proxy's own
 * size: the bitrate scales with the FRAME RATE only (a 1080p60 proxy carries
 * twice the frames of a 1080p30 one), never with the source's 4K/8K pixels.
 */
object ProxyBitrate {

    /** The plan's stated starting point at 1080p30. */
    const val REFERENCE_MBPS_1080P = 20.0

    /** The reference frame rate the plan's own number assumes. */
    const val REFERENCE_FPS = 30.0

    /** The clamp: proxy bitrates live in 1..100 Mbit/s, never a 0 or a runaway ask. */
    const val MAX_MBPS = 100.0

    /**
     * VBR target in bits per second for a proxy at [fps] (the proxy's own size
     * is the reference's size, so fps is the only variable). An unknown fps
     * (<= 0) falls back to the reference 30 honestly.
     */
    fun bps(fps: Double): Int {
        val f = if (fps > 0.0) fps else REFERENCE_FPS
        val mbps = (REFERENCE_MBPS_1080P * f / REFERENCE_FPS).coerceIn(1.0, MAX_MBPS)
        return (mbps * 1_000_000.0).toInt()
    }
}

/**
 * Which clips a proxy is queued for (plan section 6 "Varsayılanlar": automatic
 * for clips over the threshold, MCRAW opt-in) — the DECISION TABLE of the G3
 * brief's test list, pure and JVM-tested. [ProxyPolicy.shouldAutoGenerate] is
 * the policy; this object turns ONE source's facts into the queue answer and
 * the signature the build would run under.
 */
object ProxyQueueDecision {

    /**
     * The queue answer for one source: should a proxy be built, and with what
     * settings. Null settings = do not queue (the policy said no, or the clip's
     * facts are too incomplete to sign a proxy honestly — a clip without a real
     * fps/size would produce a proxy nobody can validate against the source).
     */
    fun decide(
        clip: ProxyClipInfo,
        policy: ProxyPolicy = ProxyPolicy(),
        settings: ProxySettings = ProxySettings(),
    ): ProxySettings? {
        if (!policy.shouldAutoGenerate(clip)) return null
        if (clip.width <= 0 || clip.height <= 0) return null
        if (clip.fps <= 0.0) return null
        return settings
    }
}
