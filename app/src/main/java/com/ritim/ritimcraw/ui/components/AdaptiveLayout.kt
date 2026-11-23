package com.ritim.ritimcraw.ui.components

import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Large-screen adaptation (owner request 2026-11-23: "büyük dpi tablet
 * ekranları için de uyarla app'i"). Pure helpers, JVM-testable where they
 * are arithmetic.
 */
object AdaptiveLayout {
    /** Phones keep the spec's 3 columns; wider windows add columns. */
    const val MIN_GRID_COLUMNS = 3

    /** The smallest comfortable thumbnail cell on a large screen. */
    val GRID_CELL_MIN: Dp = 168.dp

    /**
     * Text-heavy screens (settings, reports) stop growing at this width and
     * center — a 1280dp-wide settings row is unreadable.
     */
    val READABLE_MAX_WIDTH: Dp = 760.dp

    /** Column count for [availablePx] with [spacingPx] gaps and [cellMinPx] cells. */
    fun gridColumns(availablePx: Int, spacingPx: Int, cellMinPx: Int): Int {
        if (availablePx <= 0 || cellMinPx <= 0) return MIN_GRID_COLUMNS
        val fit = (availablePx + spacingPx) / (cellMinPx + spacingPx)
        return maxOf(MIN_GRID_COLUMNS, fit)
    }
}

/**
 * GridCells that never drop below [AdaptiveLayout.MIN_GRID_COLUMNS] (the
 * phone layout stays exactly the spec's 3 columns) and add a column for
 * every [minSize] of extra width on tablets. Cells share the width evenly.
 */
class AdaptiveMinGridCells(private val minSize: Dp = AdaptiveLayout.GRID_CELL_MIN) : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val count = AdaptiveLayout.gridColumns(availableSize, spacing, minSize.roundToPx())
        val totalCells = availableSize - spacing * (count - 1)
        val base = totalCells / count
        val remainder = totalCells % count
        return List(count) { i -> base + if (i < remainder) 1 else 0 }
    }

    override fun hashCode(): Int = minSize.hashCode()
    override fun equals(other: Any?): Boolean =
        other is AdaptiveMinGridCells && other.minSize == minSize
}

/** Centers the content and caps it at a readable width on large screens. */
fun Modifier.readableWidth(max: Dp = AdaptiveLayout.READABLE_MAX_WIDTH): Modifier =
    this.wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max)
