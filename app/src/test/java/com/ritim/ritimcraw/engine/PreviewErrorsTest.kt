package com.ritim.ritimcraw.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PreviewErrors mapping tests (spec SYS-02: every error names the stage, the
 * cause, and the recovery). The bridge reports numeric code + stage + detail;
 * these tests pin the per-ErrorCode wording and the message format — pure
 * JVM, no native lib.
 */
class PreviewErrorsTest {

    // ---- stage names ----

    @Test
    fun stage_tokens_have_names() {
        assertEquals("open", PreviewErrors.stageName(PreviewErrors.STAGE_OPEN))
        assertEquals("frame read", PreviewErrors.stageName(PreviewErrors.STAGE_FRAME_READ))
        assertEquals("develop", PreviewErrors.stageName(PreviewErrors.STAGE_DEVELOP))
    }

    @Test
    fun unknown_stage_token_names_unknown() {
        assertEquals("unknown", PreviewErrors.stageName(99))
        assertEquals("unknown", PreviewErrors.stageName(-1))
    }

    // ---- per-ErrorCode cause + recovery ----

    @Test
    fun truncated_names_reimport_recovery() {
        val (cause, recovery) = PreviewErrors.causeAndRecovery(PreviewErrors.CODE_TRUNCATED)
        assertEquals("truncated clip", cause)
        assertEquals("re-import the clip", recovery)
    }

    @Test
    fun bad_header_names_reimport_recovery() {
        val (cause, recovery) = PreviewErrors.causeAndRecovery(PreviewErrors.CODE_BAD_HEADER)
        assertEquals("not a supported clip", cause)
        assertEquals("re-import the clip", recovery)
    }

    @Test
    fun io_names_check_and_reopen_recovery() {
        val (cause, recovery) = PreviewErrors.causeAndRecovery(PreviewErrors.CODE_IO)
        assertEquals("the file could not be read", cause)
        assertEquals("check the clip still exists, then reopen it", recovery)
    }

    @Test
    fun unsupported_names_reimport_recovery() {
        val (cause, recovery) = PreviewErrors.causeAndRecovery(PreviewErrors.CODE_UNSUPPORTED)
        assertEquals("unsupported clip", cause)
        assertEquals("re-import the clip", recovery)
    }

    @Test
    fun unknown_code_names_reopen_recovery() {
        val (cause, recovery) = PreviewErrors.causeAndRecovery(PreviewErrors.CODE_OK)
        assertEquals("unknown error", cause)
        assertEquals("reopen the clip", recovery)
    }

    // ---- message format: stage + cause + recovery ----

    @Test
    fun message_names_stage_cause_and_recovery() {
        val msg = PreviewErrors.message(
            PreviewErrors.CODE_TRUNCATED, PreviewErrors.STAGE_FRAME_READ,
        )
        assertEquals(
            "Preview render failed (stage: frame read): truncated clip — " +
                "recovery: re-import the clip",
            msg,
        )
    }

    @Test
    fun message_appends_detail_in_brackets() {
        val msg = PreviewErrors.message(
            PreviewErrors.CODE_IO, PreviewErrors.STAGE_OPEN, "open: ioError — EBADF",
        )
        assertEquals(
            "Preview render failed (stage: open): the file could not be read — " +
                "recovery: check the clip still exists, then reopen it " +
                "[open: ioError — EBADF]",
            msg,
        )
    }

    @Test
    fun message_omits_blank_detail() {
        val msg = PreviewErrors.message(
            PreviewErrors.CODE_UNSUPPORTED, PreviewErrors.STAGE_DEVELOP, "  ",
        )
        assertFalse(msg.contains("["))
    }

    @Test
    fun every_code_yields_non_empty_cause_and_recovery() {
        for (code in 0..5) {
            val (cause, recovery) = PreviewErrors.causeAndRecovery(code)
            assertTrue(cause.isNotBlank())
            assertTrue(recovery.isNotBlank())
        }
    }
}
