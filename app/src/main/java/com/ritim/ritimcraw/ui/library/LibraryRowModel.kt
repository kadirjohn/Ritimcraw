package com.ritim.ritimcraw.ui.library

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
 */
object LibraryRowModel {

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
