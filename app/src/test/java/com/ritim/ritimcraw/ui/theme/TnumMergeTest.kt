package com.ritim.ritimcraw.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Insurance for the transport timecode's steady-width fix (owner round
 * 3a, 2026-11-11): the readout pins its minimum width to a string
 * measured with the EXACT style it renders with, so the merge that
 * layers Tnum onto the Material style must never silently drop the
 * "tnum" font feature — a dropped feature would only be visible on
 * devices whose font ignores tabular figures, and the pinned width is
 * what actually holds the box steady there. TextStyle.merge is pure
 * data composition, safe to exercise on the JVM.
 */
class TnumMergeTest {

    @Test
    fun tnum_itself_carries_the_font_feature() {
        assertEquals("tnum", Tnum.fontFeatureSettings)
    }

    @Test
    fun merge_keeps_tnum_font_feature_settings() {
        // The exact merge the transport readout performs
        // (EditorTransport.kt: labelMedium.merge(Tnum)).
        val labelMedium = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        val merged = labelMedium.merge(Tnum)
        assertEquals("tnum", merged.fontFeatureSettings)
    }

    @Test
    fun merge_keeps_the_base_style_and_sets_tnum() {
        // The merge must layer the feature ON TOP of the base style, not
        // replace the style wholesale: base attributes survive, tnum is
        // added, and an explicit base feature (if one ever appears) is
        // overridden by the merge semantics (Tnum wins).
        val base = TextStyle(
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            letterSpacing = 0.4.sp,
        )
        val merged = base.merge(Tnum)
        assertEquals(FontWeight.Medium, merged.fontWeight)
        assertEquals(12.sp, merged.fontSize)
        assertEquals(0.4.sp, merged.letterSpacing)
        assertEquals("tnum", merged.fontFeatureSettings)
    }

    @Test
    fun merge_overrides_a_base_font_feature() {
        // TextStyle.merge semantics: the source's non-null properties win.
        val base = TextStyle(fontFeatureSettings = "onum")
        val merged = base.merge(Tnum)
        assertEquals("tnum", merged.fontFeatureSettings)
    }

    @Test
    fun text_unit_companion_import_survives_jvm() {
        // Unspecified TextUnit stays Unspecified through the merge — the
        // transport measures with the merged style, so a merge bug that
        // materialized Unspecified values would change the measured
        // width. Locks the merge as attribute-preserving.
        val merged = TextStyle().merge(Tnum)
        assertEquals(TextUnit.Unspecified, merged.fontSize)
        assertEquals("tnum", merged.fontFeatureSettings)
    }
}
