package com.ritim.ritimcraw.ui.library

import com.ritim.ritimcraw.R
import com.ritim.ritimcraw.data.RelinkMismatchException
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * ImportErrors mapping tests (sprint plan Task 4 import flow): the pure
 * exception-to-message mapping, JVM-testable without Android.
 */
class ImportErrorsTest {

    @Test
    fun security_exception_maps_to_access_message() {
        val msg = ImportErrors.fromException(
            "import",
            SecurityException("permission revoked for content://clip/1"),
        )
        assertEquals(R.string.library_import_failed_access, msg.messageRes)
        assertEquals("permission revoked for content://clip/1", msg.causeArg)
    }

    @Test
    fun io_exception_maps_to_storage_message() {
        val msg = ImportErrors.fromException(
            "import",
            IOException("read failed: EBADF"),
        )
        assertEquals(R.string.library_import_failed_storage, msg.messageRes)
        assertEquals("read failed: EBADF", msg.causeArg)
    }

    @Test
    fun other_exceptions_map_to_unknown_message() {
        val msg = ImportErrors.fromException(
            "import",
            IllegalStateException("fingerprint digest missing"),
        )
        assertEquals(R.string.library_import_failed_unknown, msg.messageRes)
        assertEquals("fingerprint digest missing", msg.causeArg)
    }

    @Test
    fun null_exception_detail_maps_to_null_cause() {
        val msg = ImportErrors.fromException("import", SecurityException())
        assertEquals(null, msg.causeArg)
    }

    @Test
    fun kind_of_matches_exception_class() {
        assertEquals(ImportErrors.Kind.ACCESS, ImportErrors.kindOf(SecurityException()))
        assertEquals(ImportErrors.Kind.STORAGE, ImportErrors.kindOf(IOException()))
        assertEquals(
            ImportErrors.Kind.UNKNOWN,
            ImportErrors.kindOf(RuntimeException()),
        )
    }

    // ---- re-link failures (spec IN-16: the honest mismatch error) ----

    @Test
    fun relink_mismatch_names_both_fingerprints() {
        // The mismatch message carries expected/actual so the user knows
        // the pick was a DIFFERENT file — and that nothing changed.
        val e = RelinkMismatchException(
            expectedFingerprint = "aaa111",
            actualFingerprint = "bbb222",
        )
        val msg = ImportErrors.fromRelinkException(e)
        assertEquals(R.string.library_relink_failed_mismatch, msg.messageRes)
        assertEquals("aaa111 / bbb222", msg.causeArg)
    }

    @Test
    fun other_relink_failures_keep_the_cause_detail() {
        // The pick could not be read at all: same recovery (pick the
        // original file at its new location), the cause rides along.
        val msg = ImportErrors.fromRelinkException(IOException("read failed: EBADF"))
        assertEquals(R.string.library_relink_failed_unknown, msg.messageRes)
        assertEquals("read failed: EBADF", msg.causeArg)
    }
}
