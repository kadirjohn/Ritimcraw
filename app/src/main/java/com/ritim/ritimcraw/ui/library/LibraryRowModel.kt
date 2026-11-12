package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource

/**
 * Pure row-display decisions for the Library (owner request 2026-11-11
 * section 1), JVM-testable: which text the row's supporting line shows and
 * which parts hide when unknown. The composable stays a thin shell — all
 * the hide rules live here.
 *
 * Resolution: the RECORDED dimensions from the record (the generation pass
 * backfills them from the clip's first frame — open-gate 4000x3000 and
 * 3840x2160 both show as recorded). A 0x0 record hides the resolution part
 * entirely; it is never faked.
 *
 * Duration: the recorded timeline's span (last pts - first pts), hidden
 * when 0 (single-frame clip or unprobed record) — never faked either.
 *
 * Degraded rows (spec IN-12/IN-16): [rowStatus] maps the record's
 * [Access] to the row's degraded presentation — the two degraded states
 * stay DISTINCT (a revoked permission and a deleted file have different
 * recoveries), and an unexpected future value renders as a plain OK row
 * rather than crashing the list.
 */
object LibraryRowModel {

    /** The row's degraded presentation (spec IN-12): a distinct line and
     *  action per state, or null for a healthy row. */
    enum class RowStatus { LOST, GONE }

    /**
     * The row's degraded state from the record's [Access]: LOST (revoked
     * permission — the "Access lost" line, Grant access action) and GONE
     * (deleted/moved document — the "File missing" line, Re-link action)
     * stay distinct because their recoveries differ; OK (and any unknown
     * future value an old build must still render) is a healthy row.
     */
    fun rowStatus(source: MediaSource): RowStatus? = when (source.access) {
        Access.LOST -> RowStatus.LOST
        Access.GONE -> RowStatus.GONE
        else -> null
    }

    /** The row's supporting line: "3840×2160 · 0:12", or fewer parts when
     *  some are unknown; null when nothing is known (the kind line shows
     *  instead). */
    fun supportingLine(source: MediaSource, durationText: String?): String? {
        val segments = mutableListOf<String>()
        if (source.width > 0 && source.height > 0) {
            segments.add("${source.width}×${source.height}")
        }
        if (!durationText.isNullOrBlank()) {
            segments.add(durationText)
        }
        return if (segments.isEmpty()) null else segments.joinToString(" · ")
    }

    /**
     * The duration part's text: [Format.formatDuration] of the recorded
     * span; null (the part hides) when the span is unknown or 0.
     */
    fun durationText(source: MediaSource): String? =
        if (source.durationUs > 0L) {
            com.ritim.ritimcraw.util.Format.formatDuration(source.durationUs)
        } else {
            null
        }
}
