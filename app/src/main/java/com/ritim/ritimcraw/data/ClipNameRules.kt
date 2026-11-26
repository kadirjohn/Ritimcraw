package com.ritim.ritimcraw.data

/**
 * Pure validation for renaming an already-imported clip (the Library's
 * Rename action and the editor overflow's Rename item share it). The name
 * is the user's own text: trimmed at both ends, inner spacing kept,
 * the extension kept as part of the text (never auto-stripped). Length is
 * counted in code points, the NodeListOps.NAME_MAX style.
 *
 * Owner request 2026-11-26 (the extension is the SYSTEM'S, not the
 * user's): the rename dialog edits the NAME WITHOUT the extension —
 * [namePart] splits a display name, [validateName] validates the name
 * the user actually types (TooLong counts the name part alone, Unchanged
 * compares name-to-name), and the caller re-appends the preserved
 * extension ([extensionOf]) before handing the whole name to the
 * repository — the stored displayName keeps its full form and the
 * legacy whole-name [validate]/[clamp] stay for callers that still edit
 * the whole text.
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

    /**
     * Pure: the extension a display name ends with, lowercase without
     * the dot ("" when there is none, and for a bare/trailing dot).
     */
    fun extensionOf(name: String): String {
        val cut = name.lastIndexOf('.')
        if (cut < 0 || cut == name.length - 1) return ""
        return name.substring(cut + 1).lowercase()
    }

    /**
     * Pure: the name with its LAST extension removed — the part the
     * rename dialog edits (owner request 2026-11-26). A name with no
     * dot or a trailing dot returns unchanged; a ".mcraw"-only name is
     * all extension and strips to "" (the dialog then edits the empty
     * name the id fallback backs).
     */
    fun namePart(name: String): String {
        val cut = name.lastIndexOf('.')
        if (cut < 0 || cut == name.length - 1) return name
        return name.substring(0, cut)
    }

    /**
     * The name-part validation the dialog runs (owner request
     * 2026-11-26): identical rules to [validate] — trim, inner spacing
     * kept, line breaks dropped, NAME_MAX code points — but the CURRENT
     * name is the pre-split name PART (Unchanged compares what the
     * field shows), so the extension never enters any decision.
     */
    fun validateName(input: String, currentNamePart: String): Result =
        validate(input, currentNamePart)

    /** [clamp] for the name part (the same NAME_MAX cut, no extension). */
    fun clampName(raw: String): String = clamp(raw)
}
