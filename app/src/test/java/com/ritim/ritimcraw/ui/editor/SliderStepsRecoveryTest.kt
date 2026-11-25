package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliderStepsRecoveryTest {
    @Test
    fun recoveryTogglesOnAndOff() {
        val g0 = GradeK(highlightReconstruction = false)
        assertFalse(SliderSteps.isHighlightRecovered(g0))
        val g1 = SliderSteps.toggleHighlightRecovery(g0)
        assertTrue(SliderSteps.isHighlightRecovered(g1))
        assertEquals(SliderSteps.RECOVERY_HIGHLIGHTS, g1.highlights, 0f)
        assertTrue(g1.highlightReconstruction)
        val g2 = SliderSteps.toggleHighlightRecovery(g1)
        assertEquals(0f, g2.highlights, 0f)
    }
}
