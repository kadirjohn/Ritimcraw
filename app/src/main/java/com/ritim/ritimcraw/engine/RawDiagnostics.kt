package com.ritim.ritimcraw.engine

import java.util.Locale

/**
 * D0 per-frame RAW diagnostics (docs/DCG_PLAN.md), mapped from the tail of
 * the RAW histogram's jintArray (slots 787..814). Computed by the core on
 * the pre-develop CFA with Develop stage 1's level resolution, and the
 * plateau verdict is the develop path's own (rawedit::recoveryClipLevels),
 * so the scope and the develop path read one answer. Read-only: nothing
 * here changes a pixel.
 *
 * Position order (4-wide): R, Gr (green on a red row), Gb, B. Channel
 * order (3-wide): R, G (Gr+Gb), B.
 */
data class RawDiagnostics(
    val blackDeclared: List<Int>,
    val whiteDeclared: Int,
    val peakRawPos: List<Int>,
    val peakRaw: List<Int>,
    val atPeak: List<Int>,
    /** Plateau support: sites within 0.4% of the normalized range below the peak (peak included). */
    val nearPeak: List<Int>,
    val aboveWhite: List<Int>,
    val reliablePlateau: List<Boolean>,
    /** The develop path's clip threshold per channel, normalized; 1.0 = no plateau. */
    val plateauThreshold: List<Float>,
) {
    companion object {
        /** First slot of the diagnostics block (right after the legacy 787 slots). */
        const val BASE = 787

        /** Maps the extended array; null when the block is flagged absent or too short. */
        fun fromJni(a: IntArray): RawDiagnostics? {
            if (a.size < BASE + 28 || a[BASE] != 1) return null
            fun ints(from: Int, n: Int) = List(n) { a[from + it] }
            return RawDiagnostics(
                blackDeclared = ints(BASE + 1, 4),
                whiteDeclared = a[BASE + 5],
                peakRawPos = ints(BASE + 6, 4),
                peakRaw = ints(BASE + 10, 3),
                atPeak = ints(BASE + 13, 3),
                nearPeak = ints(BASE + 16, 3),
                aboveWhite = ints(BASE + 19, 3),
                reliablePlateau = ints(BASE + 22, 3).map { it != 0 },
                plateauThreshold = ints(BASE + 25, 3).map { it / 1_000_000f },
            )
        }
    }
}

/**
 * Localized templates for [RawDiagnosticsText] (java.util.Formatter
 * syntax, positional args), filled from string resources by the UI and
 * from fixed English in tests.
 */
data class RawDiagnosticsLabels(
    /** %1$s black text, %2$d white, %3$d sites above white (all channels). */
    val levels: String,
    /** %1$d R, %2$d Gr, %3$d Gb, %4$d B — used when the four blacks differ. */
    val blackSplit: String,
    /** %1$s channel, %2$d peak code, %3$d sites at it, %4$d support, %5$s greens suffix, %6$s plateau text. */
    val channel: String,
    /** %1$d Gr peak, %2$d Gb peak. */
    val greens: String,
    /** %1$s threshold, three decimals. */
    val plateauYes: String,
    val plateauNo: String,
)

/** Pure text builder for the RAW scope's diagnostics block (JVM-testable). */
object RawDiagnosticsText {

    private val CHANNELS = listOf("R", "G", "B")

    /** All four lines in display order: header, R, G, B. */
    fun lines(d: RawDiagnostics, l: RawDiagnosticsLabels): List<String> {
        val loc = Locale.ROOT
        val black = d.blackDeclared
        val blackText = if (black.all { it == black[0] }) black[0].toString()
        else String.format(loc, l.blackSplit, black[0], black[1], black[2], black[3])
        val header = String.format(loc, l.levels, blackText, d.whiteDeclared, d.aboveWhite.sum())
        fun channelLine(c: Int): String {
            val plateau = if (d.reliablePlateau[c])
                String.format(loc, l.plateauYes, String.format(loc, "%.3f", d.plateauThreshold[c]))
            else l.plateauNo
            val greens = if (c == 1) " " + String.format(loc, l.greens, d.peakRawPos[1], d.peakRawPos[2]) else ""
            return String.format(
                loc, l.channel, CHANNELS[c], d.peakRaw[c], d.atPeak[c], d.nearPeak[c], greens, plateau,
            )
        }
        return listOf(header, channelLine(0), channelLine(1), channelLine(2))
    }

    /** The [n] highest-priority lines (header, G, R, B), kept in display order. */
    fun linesFor(d: RawDiagnostics, l: RawDiagnosticsLabels, n: Int): List<String> {
        val all = lines(d, l)
        val keep = when {
            n >= 4 -> listOf(0, 1, 2, 3)
            n == 3 -> listOf(0, 1, 2)
            n == 2 -> listOf(0, 2)
            else -> listOf(0)
        }
        return keep.map { all[it] }
    }
}
