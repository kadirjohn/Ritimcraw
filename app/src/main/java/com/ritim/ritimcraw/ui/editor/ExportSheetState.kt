package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.export.ExportPlan
import com.ritim.ritimcraw.export.ExportResolution
import com.ritim.ritimcraw.export.ExportText
import com.ritim.ritimcraw.export.TargetFps
import java.util.Locale

/**
 * Pure state and format rules of the export sheet (E1c-polish). The
 * composables only render these, so the visible text is JVM-tested. Nothing
 * here decides what the encode produces: the plan, the settings and the
 * ladder stay in the export package; this file only chooses what the sheet SHOWS.
 */
internal object ExportSheetState {

    /**
     * The sheet's top segmented control; one target's settings are visible
     * at a time. CINEMA_DNG (night E-F) is MCRAW-only (the encoded-source
     * sheet does not list it) and its section carries its own folder/
     * estimate rows (no MP4 plan rows apply).
     */
    enum class Tab { MP4, DNG, LINEAR, PHOTO, CINEMA_DNG }

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
    enum class Row { FORMAT, PRESET, RESOLUTION, FRAME_RATE, CODEC, BIT_DEPTH, BITRATE, RATE_MODE, KEYFRAME, PROFILE, AUDIO, LOUDNESS, AUDIO_FORMAT }

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
        listOf(Row.BITRATE, Row.RATE_MODE, Row.KEYFRAME, Row.PROFILE, Row.AUDIO, Row.LOUDNESS, Row.AUDIO_FORMAT)

    /**
     * (E5, night D2) The loudness row's value number: null for a null
     * target (Off), else the target rounded to whole LUFS ("−14").
     * Plain numbers, Locale.ROOT; the row composable pairs it with the
     * localized "Off"/"LUFS" words.
     */
    fun loudnessValue(targetLufs: Float?): String? =
        targetLufs?.let { String.format(Locale.ROOT, "%.0f", it) }

    /**
     * (E5-B, night G1) The audio-format row's value: the source pair
     * ("48 kHz · Stereo"), the choice pair, or the honest mix ("Source →
     * 44.1 kHz"). [sourceRate]/[sourceChannels] 0 = unknown (the clip has
     * no audio, or the metadata carries no facts): the value says what it
     * can, never a guessed number.
     */
    fun audioFormatValue(
        sourceRate: Int,
        sourceChannels: Int,
        chosenRate: Int?,
        chosenChannels: Int?,
    ): String {
        val src = sourceText(sourceRate, sourceChannels)
        // Both SOURCE (or unknown): the one honest word, nothing else.
        if (chosenRate == null && chosenChannels == null) return src
        val rate = chosenRate?.let { rateText(it) } ?: rateText(sourceRate)
        val ch = chosenChannels?.let { channelText(it) } ?: channelText(sourceChannels)
        val picked = "$rate · $ch"
        // A choice that lands on the clip's own shape is SOURCE in
        // effect: show the plain value, not a fake conversion.
        if (chosenRate == sourceRate && chosenChannels == sourceChannels) return picked
        if (chosenRate == null || chosenChannels == null) {
            // Half a choice: show the source's other side beside the pick.
            return "$src → $picked"
        }
        return picked
    }

    /** "48 kHz" / "44.1 kHz"; "unknown" never appears — the caller omits. */
    fun rateText(hz: Int): String =
        if (hz == 44100) "44.1 kHz" else String.format(Locale.ROOT, "%d kHz", hz / 1000)

    /** "Mono" / "Stereo" — the words are the same in en and tr. */
    fun channelText(ch: Int): String = if (ch == 1) "Mono" else "Stereo"

    /** The row's Source value: the clip's own pair, or the honest unknown. */
    fun sourceText(sourceRate: Int, sourceChannels: Int): String = when {
        sourceRate <= 0 || sourceChannels <= 0 -> "Source"
        else -> "${rateText(sourceRate)} · ${channelText(sourceChannels)}"
    }

    /**
     * Read-only rows show a value but have no chevron and open nothing.
     * AUDIO_FORMAT was one of these before E5-B (G1) and FRAME_RATE
     * before E1f-2 (G2) — both are pickers now, so no row is read-only
     * anymore; the function stays for the rows' shared contract.
     */
    fun isReadOnly(row: Row): Boolean = false

    // ---- frame rate picker (E1f-2) ----

    /** What the FRAME_RATE row's value text says. */
    enum class FrameRateValue { TARGET, SOURCE_WITH_FPS, SOURCE_UNKNOWN }

    /**
     * The FRAME_RATE row's honest value choice (E1f-2, pure — the composable
     * only localizes it): a ladder target shows its own label; SOURCE shows
     * the clip's MEASURED rate as its context (never a snapped standard,
     * the owner's rule) and, when the rate is unknown, says just "Source" —
     * never a guess.
     */
    fun frameRateValue(target: TargetFps, sourceFps: Double?): FrameRateValue = when {
        target != TargetFps.SOURCE -> FrameRateValue.TARGET
        sourceFps != null && sourceFps > 0.0 -> FrameRateValue.SOURCE_WITH_FPS
        else -> FrameRateValue.SOURCE_UNKNOWN
    }

    /** The fps target ladder the sub-page lists, in the E1f-1 enum's order. */
    fun fpsLadder(): List<TargetFps> = TargetFps.entries.filter { it != TargetFps.SOURCE }

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
