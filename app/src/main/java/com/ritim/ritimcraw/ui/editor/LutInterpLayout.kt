package com.ritim.ritimcraw.ui.editor

/**
 * The interpretation sheet's pure WINDOW layout rules (owner device
 * feedback 2026-11-13: "source ve transform iki farklı window olarak
 * sunulmalı" — option-heavy operations split into two selectable
 * windows inside ONE sheet; one long list overwhelmed the device).
 * The precedent is [LutInterpPresets]: the decision is extracted from
 * the composable so it is JVM-testable headless, and the sheet only
 * renders what these rules say.
 *
 * THE TWO WINDOWS. The managed model's five fields split along the
 * pipeline itself, not alphabetically:
 *
 *  - [SOURCE] — what the cube READS: the preset suggestion row plus
 *    the input encoding (input gamut + input transfer). The preset
 *    seeds BOTH windows' fields, but it lives here because a user
 *    picking a preset is answering "what is my source?" first.
 *
 *  - [TRANSFORM] — what the cube DOES: the output encoding (output
 *    gamut + output transfer) plus the role that decides where the
 *    pipeline applies it.
 *
 * The name field and the honesty hint belong to NEITHER window: they
 * frame the whole record (the name is the record's identity, the hint
 * is the honesty contract), so they render above the toggle and stay
 * visible in both windows. The Confirm/Cancel row is likewise shared
 * — it commits the whole interpretation, not one window.
 *
 * UNTOUCHED BY THESE RULES: [LutInterpPresets] (preset ->
 * interpretation, interpretation -> preset, the grade-selection rule)
 * and the pure data layer — the split is presentation only.
 */
enum class LutInterpWindow { SOURCE, TRANSFORM }

/**
 * The pure layout decisions for the two-window interpretation sheet:
 * which fields belong to which window, and nothing else. JVM-headless;
 * the composable half renders the windows these rules describe.
 */
object LutInterpLayout {

    /**
     * The window a first-time user should see when the sheet opens:
     * SOURCE — the pipeline reads source-first, and the preset row
     * (the suggested starting point) lives there.
     */
    val INITIAL_WINDOW: LutInterpWindow = LutInterpWindow.SOURCE

    /** The windows in display order (Source | Transform). */
    val WINDOW_ORDER: List<LutInterpWindow> =
        listOf(LutInterpWindow.SOURCE, LutInterpWindow.TRANSFORM)

    /**
     * Whether the PRESET suggestion row belongs to [window]. True only
     * for SOURCE: the preset seeds both windows' fields, but it is a
     * "what is my source?" answer, so it lives with the input rows.
     */
    fun hasPresetRow(window: LutInterpWindow): Boolean =
        window == LutInterpWindow.SOURCE

    /**
     * Whether the INPUT encoding rows (input gamut + input transfer)
     * belong to [window]. True only for SOURCE.
     */
    fun hasInputRows(window: LutInterpWindow): Boolean =
        window == LutInterpWindow.SOURCE

    /**
     * Whether the OUTPUT encoding rows (output gamut + output transfer)
     * belong to [window]. True only for TRANSFORM.
     */
    fun hasOutputRows(window: LutInterpWindow): Boolean =
        window == LutInterpWindow.TRANSFORM

    /**
     * Whether the ROLE row belongs to [window]. True only for
     * TRANSFORM: the role is "what the cube does to the pipeline"
     * (where it is applied), not an encoding fact.
     */
    fun hasRoleRow(window: LutInterpWindow): Boolean =
        window == LutInterpWindow.TRANSFORM
}
