package com.ritim.ritimcraw.data

/**
 * Pure validation for renaming an already-imported clip (the Library's
 * Rename action and the editor overflow's Rename item share it). The name
 * is the user's own text: trimmed at both ends, inner spacing kept, the
 * extension kept as part of the text (never auto-stripped). Length is
 * counted in code points, the NodeListOps.NAME_MAX style.
 */
object ClipNameRules {
    /** Longest accepted clip name, in code points. */
    const val NAME_MAX = 120

    sealed class Result {
        /** Acceptable: [name] is the trimmed text to hand to the repository. */
        data class Ok(val name: String) : Result()
        object Blank : Result()
        object Unchanged : Result()
        object TooLong : Result()
    }

    /** Line breaks cannot live in a single-line name; they become nothing. */
    fun sanitize(raw: String): String = raw.filterNot { it == '\n' || it == '\r' }

    fun validate(input: String, current: String): Result {
        val trimmed = sanitize(input).trim()
        if (trimmed.isEmpty()) return Result.Blank
        if (trimmed == current.trim()) return Result.Unchanged
        if (trimmed.codePointCount(0, trimmed.length) > NAME_MAX) return Result.TooLong
        return Result.Ok(trimmed)
    }

    /** Cuts typed/pasted text to [NAME_MAX] code points (the field's input filter). */
    fun clamp(raw: String): String {
        val s = sanitize(raw)
        return if (s.codePointCount(0, s.length) <= NAME_MAX) {
            s
        } else {
            s.substring(0, s.offsetByCodePoints(0, NAME_MAX))
        }
    }
}
