package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.data.Access
import com.ritim.ritimcraw.data.MediaSource
import com.ritim.ritimcraw.data.SourceKind
import com.ritim.ritimcraw.ui.components.ThumbnailRow

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
     * The list row's kind badge (owner request 2026-11-26): MCRAW clips
     * wear the highlighted treatment, every other kind the neutral one.
     * The text is NOT localized (the file's own format token, the
     * PresetNameDialog "never localized" convention) and is derived from
     * the record's KIND — never by parsing the display name, which a
     * rename can have changed to anything.
     *
     * MOTIONCAM (owner request 2026-11-27, M2): the MCRAW badge's own
     * style — the fixed MotionCam blue pill (FormatBadgeStyle.MOTIONCAM,
     * FileListRow's one theme-independent treatment), so the native
     * format's badge keeps MotionCam's color in every theme, monet and
     * custom alike.
     */
    enum class BadgeStyle { HIGHLIGHTED, NEUTRAL, MOTIONCAM }

    /** The badge a source's row shows: its style plus its (non-localized) text. */
    data class Badge(val style: BadgeStyle, val text: String)

    /**
     * Pure: the extension a display name ends with, lowercase without
     * the dot ("" when there is none, and for a bare/trailing dot —
     * names like "clip" or "clip." carry no extension).
     */
    fun extensionOf(name: String): String {
        val cut = name.lastIndexOf('.')
        if (cut < 0) return ""
        val ext = name.substring(cut + 1)
        return if (ext.isEmpty()) "" else ext.lowercase()
    }

    /**
     * Pure: the name with its LAST extension removed (owner request
     * 2026-11-26: the list row shows the name without the extension) —
     * the exact inverse of [extensionOf]. Code-point safe — the cut
     * index sits on the '.' separator itself, so no surrogate pair is
     * ever split. A name with no dot, a bare dot, or a TRAILING dot has
     * no extension and returns unchanged; a ".mcraw"-only name is all
     * extension and strips to "" (the row then falls back via
     * [listDisplayName]); double extensions (".x.mcraw") strip only the
     * last (".x" remains).
     */
    fun withoutExtension(name: String): String {
        val cut = name.lastIndexOf('.')
        if (cut < 0) return name
        if (cut == name.length - 1) return name // trailing dot: no extension
        return name.substring(0, cut)
    }

    /**
     * Pure: the list row's name (owner request 2026-11-26): the display
     * name minus its extension; the id fallback when nothing usable
     * remains (a name that was ONLY the extension, or the unnamed-record
     * id) — the row never shows a blank line.
     */
    fun listDisplayName(source: MediaSource): String {
        val name = source.displayOrId
        val stripped = withoutExtension(name)
        return if (stripped.isBlank()) name else stripped
    }

    /**
     * Pure: the badge a source's list row shows. The text rides the KIND
     * (the record's own classification — a renamed "Sunset" MCRAW keeps
     * its MCRAW badge), the extension parsed from the name only when the
     * kind maps to no fixed token (a record the resolver misnamed).
     * Null = no badge (the row keeps its clean look for kinds the badge
     * design did not name).
     */
    fun kindBadge(source: MediaSource): Badge? = when (source.kind) {
        SourceKind.MCRAW -> Badge(BadgeStyle.HIGHLIGHTED, "MCRAW")
        // The encoded containers: the app's own token (VideoTree's
        // "MP4" precedents call every encoded container an MP4 badge —
        // mov/other containers share the same engine path, one token).
        SourceKind.ENCODED -> Badge(BadgeStyle.NEUTRAL, "MP4")
    }


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

    /** The zero-width space the name wrap inserts (display only). */
    private const val WRAP = '﻿'

    /**
     * The name as the Library RENDERS it (library toolbar redesign O1,
     * 2026-11-16): a zero-width space after each '_' and before each '.',
     * so a long file name's two lines break at its separators instead of
     * mid-extension ("IMG_1234.MP4" can wrap after the '_' or before the
     * '.', never inside "MP4").
     *
     * DISPLAY ONLY — the record keeps its real name; this copy is never
     * persisted, compared, or matched, so the invisible characters cannot
     * leak anywhere but the rendered Text. A name without separators (the
     * id fallback) returns unchanged.
     */
    fun displayWrapName(name: String): String {
        if ('_' !in name && '.' !in name) return name
        val sb = StringBuilder(name.length + 4)
        for (ch in name) {
            if (ch == '.') sb.append(WRAP)
            sb.append(ch)
            if (ch == '_') sb.append(WRAP)
        }
        return sb.toString()
    }
}

/**
 * The Library grid's row layout (owner request 2026-11-26 + the dynamic
 * refinement): the visible clips chunked into ROWS at the CURRENT column
 * count, each row carrying the shared box aspect set by its TALLEST clip
 * (the pure [ThumbnailRow.gridRows] decision). The screen renders one row
 * container per [Row], so tiles never interleave and the row heights
 * re-derive when the column count (orientation) changes the membership.
 */
object LibraryGridRows {

    /**
     * One grid row: the chunked clips (the record itself + its own
     * aspect, bitmap-loaded or placeholder) and the shared box aspect
     * every cell in the row renders at.
     */
    data class Row(
        val clips: List<MediaSource>,
        val aspects: List<Float>,
        val rowAspect: Float,
    ) {
        /** The row's identity for LazyVerticalGrid keys: its clip ids. */
        val clipIds: List<String> get() = clips.map { it.id }
    }

    /**
     * Pure: chunk [sources] into rows of [columns] clips, each row's
     * aspect decided by [ThumbnailRow.gridRows] (the row's lowest aspect
     * = its tallest clip, capped at 4:3 portrait, degenerate aspects
     * falling back to 16:9). The [aspects] arrive from the caller in the
     * SAME order as [sources] — the loaded bitmap's own ratio, or the
     * record's placeholder ratio before the icon lands.
     */
    fun of(sources: List<MediaSource>, aspects: List<Float>, columns: Int): List<Row> {
        val layout = ThumbnailRow.gridRows(aspects, columns)
        return layout.mapIndexed { i, r ->
            Row(
                clips = sources.subList(i * columns, minOf(sources.size, (i + 1) * columns)),
                aspects = r.aspects,
                rowAspect = r.rowAspect,
            )
        }
    }

    /**
     * Pure: the row index a flat clip index lands in at [columns] (the
     * view-switch scroll anchor: the list's flat top index -> the grid's
     * row coordinate). Clamped to the last row for an out-of-range index.
     */
    fun rowOf(flatIndex: Int, clipCount: Int, columns: Int): Int {
        val perRow = maxOf(1, columns)
        if (clipCount <= 0) return 0
        return (flatIndex.coerceIn(0, clipCount - 1) / perRow).coerceAtMost(rowCount(clipCount, perRow) - 1)
    }

    /**
     * Pure: the FIRST flat clip index of [row] (the inverse anchor: the
     * grid's top row -> the list's flat coordinate). Clamped for an
     * out-of-range row.
     */
    fun firstClipOf(row: Int, clipCount: Int, columns: Int): Int {
        val perRow = maxOf(1, columns)
        if (clipCount <= 0) return 0
        return (row.coerceIn(0, rowCount(clipCount, perRow) - 1)) * perRow
    }

    private fun rowCount(clipCount: Int, perRow: Int): Int = (clipCount + perRow - 1) / perRow
}
