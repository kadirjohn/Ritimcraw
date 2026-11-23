package com.ritim.ritimcraw.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveLayoutTest {
    // px at density 1 for readability: cell 168, spacing 8
    @Test
    fun phones_keep_three_columns() {
        assertEquals(3, AdaptiveLayout.gridColumns(380, 8, 168))
        assertEquals(3, AdaptiveLayout.gridColumns(0, 8, 168))
    }

    @Test
    fun tablets_add_columns_per_cell_width() {
        // (1200 + 8) / (168 + 8) = 6
        assertEquals(6, AdaptiveLayout.gridColumns(1200, 8, 168))
        // (800 + 8) / 176 = 4
        assertEquals(4, AdaptiveLayout.gridColumns(800, 8, 168))
    }
}
