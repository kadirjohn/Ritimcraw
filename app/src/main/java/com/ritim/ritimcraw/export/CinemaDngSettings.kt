package com.ritim.ritimcraw.export

/**
 * The CinemaDNG sequence export's choices (night E-F, docs/
 * CINEMADNG_EXPORT_PLAN.md), snapshotted at ENQUEUE time exactly like the
 * grade: a later edit must not change an in-flight export, and Retry
 * re-exports the SAME sequence. Pure Kotlin: no Android types, so the
 * naming/timecode/storage rules are all JVM-tested.
 *
 * A SEPARATE MODEL FROM [ExportSettings], on purpose (supervisor decision
 * 2026-11-27): the MP4 choices ride the ExportSettings wire chain
 * (loudness v2 -> fps v3 -> HLG v4) whose merge order belongs to the
 * coordinator; this wire lives under its OWN key ("cinemaDng") and never
 * touches that chain — a merge conflict between the two features is
 * structurally impossible.
 *
 * WHAT IS NOT A SETTING (owner decisions, carried here as data the sheet
 * resolved at enqueue):
 *  - COMPRESSION: none (uncompressed DNG, the plan's own recommendation —
 *    "ilk sürüm sıkıştırmasız", Resolve compatibility first);
 *  - GRADE/GEOMETRY/NR: never applied (the output IS the raw CFA); the
 *    snapshot's grade is simply not read by this exporter;
 *  - FPS: the SOURCE's own rate, snapshotted as the EXACT rational the
 *    FrameRate tag carries (30000/1001 for 29.97 — never a 1e6
 *    approximation).
 *
 * THE TIMECODE (R1-3): the START code of output frame 0 is a parameter,
 * default 00:00:00:00 (the owner's "başlangıç timecode kaynağı karar
 * bekliyor" question stays OPEN — this model never guesses a wall-clock
 * origin; the sheet could later offer a capture-time or typed start).
 */
data class CinemaDngSettings(
    /** The output folder's SAF tree URI (the user's ACTION_OPEN_DOCUMENT_TREE pick, persisted). */
    val folderUri: String,
    /**
     * The frame range: the whole clip by default ([lastFrame] -1), the
     * clip's own inclusive numbering ([firstFrame]..[lastFrame]).
     */
    val firstFrame: Int = 0,
    val lastFrame: Int = -1,
    /** The file-name prefix (the plan's parameterized pattern, `<önek>_<kare:06>.dng`). */
    val prefix: String = CinemaDngNaming.DEFAULT_PREFIX,
    /** The frame number's zero-pad width (clamped [1, 10] by the naming rule). */
    val frameDigits: Int = CinemaDngNaming.DEFAULT_DIGITS,
    /** The sequence's fps as the EXACT rational the FrameRate tag writes (30000/1001 for 29.97). */
    val fpsNum: Int = 30,
    val fpsDen: Int = 1,
    /** The START timecode of output frame 0 (default 00:00:00:00; the fields are UNPACKED so an out-of-range value is a validation error, never a corrupt tag). */
    val tcHour: Int = 0,
    val tcMinute: Int = 0,
    val tcSecond: Int = 0,
    val tcFrame: Int = 0,
    // ---- the clip facts resolved at ENQUEUE (the same snapshot rule;
    // the exporter re-plans with them, it never re-reads the clip) ----
    /** The clip's whole frame count at queue time. */
    val clipFrameCount: Int = 0,
    /** The CFA frame's dimensions at queue time (the size estimate's input). */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** The clip's audio description at queue time (0 = no audio — the WAV is then simply not written). */
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
) {
    /**
     * The settings are internally usable: a positive fps rational, a
     * usable frame range (the plan's own [CinemaDngPlan.frameCount]
     * decides), a non-empty folder and a SMPTE-range start timecode.
     * [why] names the first broken field (reported, never guessed).
     */
    fun valid(why: StringBuilder? = null): Boolean {
        if (fpsNum <= 0 || fpsDen <= 0) {
            why?.append("fps rational must be positive")
            return false
        }
        if (folderUri.isBlank()) {
            why?.append("no output folder")
            return false
        }
        if (tcHour !in 0..23 || tcMinute !in 0..59 ||
            tcSecond !in 0..59 || tcFrame !in 0..59
        ) {
            why?.append("start timecode out of SMPTE range")
            return false
        }
        val frames = CinemaDngPlan.frameCount(firstFrame, lastFrame, clipFrameCount)
        if (frames == CinemaDngPlan.REJECT) {
            if (why != null) {
                why.append("unusable frame range first=").append(firstFrame)
                    .append(" last=").append(lastFrame)
                    .append(" clip=").append(clipFrameCount)
            }
            return false
        }
        if (frameWidth <= 0 || frameHeight <= 0) {
            why?.append("unknown frame dimensions")
            return false
        }
        return true
    }
}

/**
 * The persisted form of [CinemaDngSettings], one nested object
 * `"cinemaDng":{"v":1,...}` on the job row — the ExportSettings wire
 * chain is deliberately NOT touched (a separate key, a separate version
 * line; the two features cannot conflict in a merge). Omit-when-null
 * keeps every pre-feature row byte-identical. A reader that meets a
 * version or a value it does not know reports [Parsed.Unsupported]: the
 * row must NOT fall back silently (the honest-about-the-device rule —
 * the store fails the job with the reason instead of exporting a
 * different sequence).
 */
object CinemaDngSettingsWire {
    const val VERSION = 1

    sealed class Parsed {
        data class Ok(val settings: CinemaDngSettings) : Parsed()
        data class Unsupported(val reason: String) : Parsed()
    }

    fun render(s: CinemaDngSettings): String {
        val sb = StringBuilder()
        sb.append("{\"v\":").append(VERSION)
            .append(",\"folderUri\":").append(com.ritim.ritimcraw.data.ProjectJson.quote(s.folderUri))
            .append(",\"firstFrame\":").append(s.firstFrame)
            .append(",\"lastFrame\":").append(s.lastFrame)
            .append(",\"prefix\":").append(com.ritim.ritimcraw.data.ProjectJson.quote(s.prefix))
            .append(",\"frameDigits\":").append(s.frameDigits)
            .append(",\"fpsNum\":").append(s.fpsNum)
            .append(",\"fpsDen\":").append(s.fpsDen)
            .append(",\"tcHour\":").append(s.tcHour)
            .append(",\"tcMinute\":").append(s.tcMinute)
            .append(",\"tcSecond\":").append(s.tcSecond)
            .append(",\"tcFrame\":").append(s.tcFrame)
            .append(",\"clipFrameCount\":").append(s.clipFrameCount)
            .append(",\"frameWidth\":").append(s.frameWidth)
            .append(",\"frameHeight\":").append(s.frameHeight)
            .append(",\"audioSampleRate\":").append(s.audioSampleRate)
            .append(",\"audioChannels\":").append(s.audioChannels)
            .append('}')
        return sb.toString()
    }

    /** [raw] is the parsed JSON value of the `cinemaDng` key (a Map from JsonParser). */
    fun parse(raw: Any?): Parsed {
        val m = raw as? Map<*, *> ?: return Parsed.Unsupported("cinemaDng is not an object")
        val v = (m["v"] as? Double)?.toInt() ?: return Parsed.Unsupported("cinemaDng has no version")
        if (v != VERSION) return Parsed.Unsupported("cinemaDng version $v (this app reads $VERSION)")
        val folder = m["folderUri"] as? String
        if (folder.isNullOrBlank()) return Parsed.Unsupported("cinemaDng has no folder")
        fun n(key: String): Int? = (m[key] as? Double)?.toInt()
        val first = n("firstFrame") ?: return Parsed.Unsupported("no firstFrame")
        val last = n("lastFrame") ?: return Parsed.Unsupported("no lastFrame")
        val digits = n("frameDigits") ?: return Parsed.Unsupported("no frameDigits")
        val fpsNum = n("fpsNum") ?: return Parsed.Unsupported("no fpsNum")
        val fpsDen = n("fpsDen") ?: return Parsed.Unsupported("no fpsDen")
        val tcHour = n("tcHour") ?: return Parsed.Unsupported("no tcHour")
        val tcMinute = n("tcMinute") ?: return Parsed.Unsupported("no tcMinute")
        val tcSecond = n("tcSecond") ?: return Parsed.Unsupported("no tcSecond")
        val tcFrame = n("tcFrame") ?: return Parsed.Unsupported("no tcFrame")
        val clipFrames = n("clipFrameCount") ?: return Parsed.Unsupported("no clipFrameCount")
        val width = n("frameWidth") ?: return Parsed.Unsupported("no frameWidth")
        val height = n("frameHeight") ?: return Parsed.Unsupported("no frameHeight")
        val rate = n("audioSampleRate") ?: return Parsed.Unsupported("no audioSampleRate")
        val channels = n("audioChannels") ?: return Parsed.Unsupported("no audioChannels")
        if (fpsNum <= 0 || fpsDen <= 0) return Parsed.Unsupported("cinemaDng fps rational must be positive")
        return Parsed.Ok(
            CinemaDngSettings(
                folderUri = folder,
                firstFrame = first,
                lastFrame = last,
                prefix = (m["prefix"] as? String) ?: CinemaDngNaming.DEFAULT_PREFIX,
                frameDigits = digits,
                fpsNum = fpsNum,
                fpsDen = fpsDen,
                tcHour = tcHour,
                tcMinute = tcMinute,
                tcSecond = tcSecond,
                tcFrame = tcFrame,
                clipFrameCount = clipFrames,
                frameWidth = width,
                frameHeight = height,
                audioSampleRate = rate,
                audioChannels = channels,
            )
        )
    }
}
