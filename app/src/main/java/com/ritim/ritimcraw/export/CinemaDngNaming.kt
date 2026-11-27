package com.ritim.ritimcraw.export

/**
 * The naming decision (R1-1 unresolved: the MotionCam sample does not
 * exist yet, so the pattern is a PARAMETER with `<prefix>_<frame:06>.dng`
 * only as the default). Pure; JVM-tested at the 999999 boundary (the
 * core DngSequencePlan::fileName's twin rule: a wider number widens the
 * name, the ordering stays numeric).
 */
object CinemaDngNaming {
    /** The default prefix ("<önek>_<kare:06>.dng" — the plan's default). */
    const val DEFAULT_PREFIX = "clip"
    const val DEFAULT_DIGITS = 6

    /**
     * The file name of OUTPUT frame [i] (0-based in the write, frame 0
     * is the first written frame): prefix + '_' + zero-padded decimal +
     * ".dng". A number wider than [digits] widens the name (never
     * truncates — the numeric ordering survives, the reader-side
     * DngSequence.frameNumberOf rule).
     */
    fun frameName(prefix: String, digits: Int, i: Int): String {
        val d = digits.coerceIn(1, 10)
        val s = i.toString()
        val pad = (d - s.length).coerceAtLeast(0)
        return prefix + "_" + "0".repeat(pad) + s + ".dng"
    }

    /** The WAV's name (the reader side's WavBinding stem rule: <prefix>.wav). */
    fun wavName(prefix: String): String = "$prefix.wav"

    /**
     * The prefix a job carries, cleaned to a safe file-name base: path
     * separators and the hostile characters removed (ExportQueue's
     * displayOutputName rule), trimmed; blank -> the default. NEVER a
     * guess — a blank prefix falls back to the documented default.
     */
    fun safePrefix(raw: String): String {
        val cleaned = raw.filter { it.code >= 0x20 && "\\/:*?\"<>|".indexOf(it) < 0 }
            .trim(' ', '.')
        return cleaned.ifBlank { DEFAULT_PREFIX }
    }
}
