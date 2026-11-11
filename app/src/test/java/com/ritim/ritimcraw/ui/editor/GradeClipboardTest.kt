package com.ritim.ritimcraw.ui.editor

import com.ritim.ritimcraw.data.GradeK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy/paste clipboard's semantics (owner request 2026-11-11 §4):
 * one slot, latest copy wins, paste never consumes, empty is null — the
 * JVM-pure object behind the sheet's Copy/Paste rows.
 */
class GradeClipboardTest {

    @Test
    fun empty_clipboard_pastes_null_and_reports_no_content() {
        GradeClipboard.clear()
        try {
            assertNull(GradeClipboard.paste())
            assertFalse(GradeClipboard.hasContent())
        } finally {
            GradeClipboard.clear()
        }
    }

    @Test
    fun copy_then_paste_returns_the_exact_grade() {
        GradeClipboard.clear()
        try {
            val grade = GradeK(
                exposureEv = 1.5f,
                tempK = 4300f,
                lutId = "abc123def4567890",
                lutStrength = 40f,
            )
            GradeClipboard.copy(grade)
            assertTrue(GradeClipboard.hasContent())
            assertEquals(grade, GradeClipboard.paste())
        } finally {
            GradeClipboard.clear()
        }
    }

    @Test
    fun a_second_copy_overwrites_the_first() {
        // One slot, latest wins — a desktop clipboard's semantics.
        GradeClipboard.clear()
        try {
            GradeClipboard.copy(GradeK(exposureEv = 1f))
            GradeClipboard.copy(GradeK(exposureEv = 2f))
            assertEquals(2f, GradeClipboard.paste()?.exposureEv ?: 0f, 0f)
        } finally {
            GradeClipboard.clear()
        }
    }

    @Test
    fun paste_does_not_consume_the_clipboard() {
        // A copied grade can be pasted onto any number of clips.
        GradeClipboard.clear()
        try {
            val grade = GradeK(contrast = 25f)
            GradeClipboard.copy(grade)
            GradeClipboard.paste()
            GradeClipboard.paste()
            assertEquals(grade, GradeClipboard.paste())
            assertTrue(GradeClipboard.hasContent())
        } finally {
            GradeClipboard.clear()
        }
    }

    @Test
    fun clear_empties_the_slot() {
        // The process-death stand-in: after clear, paste finds nothing
        // and the UI hides the Paste row (hasContent false).
        GradeClipboard.clear()
        GradeClipboard.copy(GradeK(exposureEv = 3f))
        GradeClipboard.clear()
        assertNull(GradeClipboard.paste())
        assertFalse(GradeClipboard.hasContent())
    }
}
