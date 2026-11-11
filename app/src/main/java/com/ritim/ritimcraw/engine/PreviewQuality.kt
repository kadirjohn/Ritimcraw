package com.ritim.ritimcraw.engine

/**
 * User-selected preview quality (owner request 2026-11-11b section 3): the
 * playback/scrub resolution the editor renders at, chosen as a
 * performance/quality tradeoff. Before this the tiers were fixed constants
 * (PreviewTiers/SliderTiers); the picker lets the owner move them.
 *
 * What each level pins — the long-edge caps the EDITOR's interactive renders
 * use:
 * - [playbackMaxDim] feeds the playback loop's requestRender (today
 *   [PreviewTiers.PLAYBACK_MAX_DIM], 720).
 * - [scrubMaxDim] feeds the seek-bar DRAG renders (today
 *   [SliderTiers.DRAG_MAX_DIM], 480): the cheapest tier, sized to keep up
 *   with the finger.
 *
 * What NO level changes (deliberate, documented owner semantics):
 * - The PAUSED tier ([PreviewTiers.PAUSED_MAX_DIM], 1280) — every paused
 *   render (first frame, pause, seek settle, before/after hold, precision
 *   scrub, grade-edit settle, clip-end) still renders at 1280, then the
 *   pause/clip-end follow-up still progressive-settles to SOURCE
 *   ([PreviewTiers.SOURCE_MAX_DIM] = 0 = recorded resolution). The picker
 *   never touches pause-to-full-res: pausing always ends at the clip's own
 *   resolution whatever the interactive quality is.
 * - The SETTLE tier ([SliderTiers.SETTLE_MAX_DIM] = 1280) — a released
 *   drag settles at the full paused quality.
 * - Export paths — untouched, they never read these constants.
 *
 * The levels sit on the existing tier ladder {480, 720, 1280}; scrub stays
 * one rung below playback except at LOW where both run at 480:
 *
 *   LOW      playback 480, scrub 480
 *   BALANCED playback 720, scrub 480  <- DEFAULT (see below)
 *   HIGH     playback 1280, scrub 720
 *
 * DEFAULT MUST equal today's fixed tiers exactly — [BALANCED] pins
 * [PreviewTiers.PLAYBACK_MAX_DIM] x [SliderTiers.DRAG_MAX_DIM] by reference
 * (not by re-stating the numbers), so an editor opened with no saved
 * preference renders the identical requests it rendered before the picker
 * existed (locked by PreviewQualityTest).
 */
enum class PreviewQuality(
    /** Long-edge cap for playback-loop renders. */
    val playbackMaxDim: Int,
    /** Long-edge cap for seek-bar drag (scrub) renders. */
    val scrubMaxDim: Int,
) {
    LOW(playbackMaxDim = 480, scrubMaxDim = 480),
    BALANCED(
        playbackMaxDim = PreviewTiers.PLAYBACK_MAX_DIM,
        scrubMaxDim = SliderTiers.DRAG_MAX_DIM,
    ),
    HIGH(playbackMaxDim = 1280, scrubMaxDim = 720);

    companion object {
        /**
         * The persisted value's wire name in files/project.json. A top-level
         * SCALAR (not a section), written by PreviewQualityStore with
         * omit-when-default so files that never chose stay byte-identical.
         */
        const val WIRE_NAME = "previewQuality"

        /**
         * The level an editor with no saved preference uses. References the
         * tier constants so the default IS today's behavior, not a copy of
         * it.
         */
        val DEFAULT: PreviewQuality = BALANCED

        /**
         * Parses a persisted wire value: null/blank/unknown strings resolve
         * to [DEFAULT] (an old file predating the setting, or a value
         * written by a future build this one does not know — the editor
         * still opens and renders at the default, never crashes on it).
         */
        fun fromWire(value: String?): PreviewQuality =
            entries.firstOrNull { it.name == value } ?: DEFAULT
    }
}
