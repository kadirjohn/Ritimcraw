package com.ritim.ritimcraw.data

/**
 * Pure name matcher for the gyro auto-attach on import (owner request
 * 2026-11-12, G1): given an imported video's display name and the .gcsv
 * display names enumerated from the user's granted gyro folder, decide
 * which log — if any — belongs to the clip.
 *
 * THE RULE IS EXACT-OR-NOTHING: strip each name's extension and compare
 * the base names case-insensitively. MotionCam writes sibling pairs with
 * the SAME base name (`261109_172410_VIDEO_99mm.mcraw` +
 * `261109_172410_VIDEO_99mm.gcsv` on the owner's device), so exact
 * base-name equality is the complete matching rule.
 *
 * Fuzzy matching (prefixes, timestamp proximity, edit distance) is
 * DELIBERATELY NOT IMPLEMENTED: a wrong .gcsv does not fail loudly — it
 * silently stabilizes the clip with the wrong motion data, which is far
 * worse than attaching nothing. Zero matches attach nothing and show
 * nothing; two or more matches (the same base name in different
 * subfolders of the granted tree) defer the choice to the user through
 * the ambiguity picker instead of guessing.
 *
 * Pure on purpose — no Android imports, so the rule is JVM-tested here
 * and the SAF tree query (the device-gated half) stays a thin adapter.
 */
object GyroMatcher {

    /** The outcome of [match]. */
    sealed interface Match {

        /**
         * Exactly one candidate matched. [name] is the candidate AS GIVEN
         * (original case, original extension) — the caller opens/imports
         * the log by this name, so the match must not normalize it.
         */
        data class One(val name: String) : Match

        /**
         * Two or more candidates matched: the caller shows a picker rather
         * than guessing. [names] keeps first-seen input order, de-duped.
         */
        data class Ambiguous(val names: List<String>) : Match

        /** No candidate matched: attach nothing, show nothing. */
        data object None : Match
    }

    /**
     * Matches [videoDisplayName] against [gcsvNames] (the .gcsv display
     * names enumerated from the granted folder). Candidates whose
     * extension is not .gcsv (case-insensitive) are ignored, so a mixed
     * listing can never attach a non-log file.
     */
    fun match(videoDisplayName: String, gcsvNames: List<String>): Match {
        val videoBase = baseName(videoDisplayName)
        if (videoBase.isEmpty()) return Match.None
        val hits = LinkedHashSet<String>()
        for (name in gcsvNames) {
            if (!name.endsWith(".gcsv", ignoreCase = true)) continue
            if (baseName(name).equals(videoBase, ignoreCase = true)) hits.add(name)
        }
        return when (hits.size) {
            0 -> Match.None
            1 -> Match.One(hits.first())
            else -> Match.Ambiguous(hits.toList())
        }
    }

    /**
     * The name without its extension: everything before the LAST dot. A
     * name with no dot — or a single LEADING dot (`.gcsv`) — has no
     * extension; the whole name is the base.
     */
    internal fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}
