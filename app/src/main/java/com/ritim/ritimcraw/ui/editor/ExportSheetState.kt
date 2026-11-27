package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.export.ExportPlan
import com.ritim.ritimcraw.export.ExportResolution
import com.ritim.ritimcraw.export.ExportText
import java.util.Locale

/**
 * Pure state and format rules of the export sheet (E1c-polish). The
 * composables only render these, so the visible text is JVM-tested. Nothing
 * here decides what the encode produces: the plan, the settings and the
 * ladder stay in the export package; this file only chooses what the sheet SHOWS.
 */
internal object ExportSheetState {

    /** The sheet's top segmented control; one target's settings are visible at a time. */
    enum class Tab { MP4, DNG, LINEAR, PHOTO }

    /**
     * The resolution entries the menu lists: the ladder with every entry
     * that resolves to the same size as an earlier one dropped (Original
     * wins, so a UHD source shows "Original 3840x2160" and no second UHD
     * line). Custom is not here: the menu appends it as the last item.
     */
    fun resolutionOptions(ladder: List<ExportResolution.Choice>): List<ExportResolution.Choice> {
        val seen = HashSet<Pair<Int, Int>>()
        return ladder.filter { seen.add(it.width to it.height) }
    }

    /** "3840×2160". */
    fun size(width: Int, height: Int): String = "$width×$height"

    // ---- frame rate display ----

    /**
     * How far a measured rate may sit from a standard one (relative) and still be shown as it.
     */
    const val FPS_DISPLAY_TOLERANCE = 0.01

    /**
     * The rate the sheet SHOWS for a measured one: the MEASURED value
     * rounded to 2 decimals, never snapped to a standard rate. The
     * owner's ruling (2026-11-24, plan E1F §1.2/K6): a clip that measures
     * 29.83 fps reads "29.83 fps (source)" — rounding it to the standard
     * 29.97 would lie about what the phone recorded, and the E1f-2 frame
     * rate row's honest Source context rides on the same rule. The old
     * snap-within-[FPS_DISPLAY_TOLERANCE] behavior (e1c-polish bde227d)
     * predates the ruling and is removed by it (K6's decision, adopted).
     * Display only: it is never the value the encoder or the plan
     * receives (the map and the plan use the measured rate as-is).
     */
    fun displayFps(measured: Double): Double {
        if (measured <= 0.0) return measured
        return Math.round(measured * 100.0) / 100.0
    }

    /** [displayFps] as text: "29.97", "30". */
    fun fpsText(measured: Double): String = ExportText.fps(displayFps(measured))

    // ---- the summary card ----

    /**
     * The one-glance line: "3840×2160 · 30 fps · HEVC 10-bit · 74.5 Mb/s ·
     * ~523 MB". [sizeText] is null when the clip's length is unknown (the
     * size part drops, never a guess). Numbers use Locale.ROOT; the unit
     * words are the same in en and tr.
     */
    fun summaryLine(plan: ExportPlan, sizeText: String?): String = buildString {
        append(size(plan.width, plan.height))
        append(" · ").append(fpsText(plan.fps)).append(" fps")
        append(" · ").append(ExportText.codec(plan.mime)).append(' ').append(plan.bitDepth).append("-bit")
        append(" · ").append(String.format(Locale.ROOT, "%s Mb/s", ExportText.mbps(plan.bitrateBps.toLong())))
        if (sizeText != null) append(" · ~").append(sizeText)
    }

    // ---- the settings list (P2) ----

    /** The settings-list rows; each is one 56dp line, the tappable ones open a sub-page. */
    enum class Row { FORMAT, PRESET, RESOLUTION, FRAME_RATE, CODEC, BIT_DEPTH, BITRATE, RATE_MODE, KEYFRAME, PROFILE, AUDIO, AUDIO_FORMAT }

    /**
     * The always-visible rows, in order. An encoded clip offers MP4 only, so
     * its Format row is gone (not disabled): one choice is nothing to choose.
     * Bit depth stays (disabled, with its reason) so the owner sees why.
     */
    fun mainRows(encodedSource: Boolean): List<Row> = buildList {
        if (!encodedSource) add(Row.FORMAT)
        add(Row.PRESET)
        add(Row.RESOLUTION)
        add(Row.FRAME_RATE)
        add(Row.CODEC)
        add(Row.BIT_DEPTH)
    }

    /** The rows under the collapsed Advanced section, in order. */
    fun advancedRows(): List<Row> =
        listOf(Row.BITRATE, Row.RATE_MODE, Row.KEYFRAME, Row.PROFILE, Row.AUDIO, Row.AUDIO_FORMAT)

    /** Read-only rows show a value but have no chevron and open nothing. */
    fun isReadOnly(row: Row): Boolean = row == Row.FRAME_RATE || row == Row.AUDIO_FORMAT

    /**
     * The summary's first line: "MP4 · 3840×2160 · 30 fps · HEVC 10-bit · ≈ 420 MB".
     * [sizeText] null (unknown clip length) drops the size part, never a guess.
     * The numbers come from the plan, not from the request.
     */
    fun compactSummaryLine(plan: ExportPlan, sizeText: String?): String = buildString {
        append("MP4 · ").append(size(plan.width, plan.height))
        append(" · ").append(fpsText(plan.fps)).append(" fps")
        append(" · ").append(ExportText.codec(plan.mime)).append(' ').append(plan.bitDepth).append("-bit")
        if (sizeText != null) append(" · ≈ ").append(sizeText)
    }

    /** The bitrate row's value: "75 Mb/s" from the plan (what will really be used). */
    fun bitrateValue(plan: ExportPlan): String = ExportText.mbps(plan.bitrateBps.toLong()) + " Mb/s"
}
