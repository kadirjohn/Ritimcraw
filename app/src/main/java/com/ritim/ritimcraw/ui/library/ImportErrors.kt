package com.ritim.ritimcraw.ui.library

/**
 * Pure mapping from an import failure to the user-facing message resource
 * (DESIGN.md Error states: name the stage, the cause, and the recovery —
 * never a bare \"failed\"). Kept free of Android imports so it is
 * JVM-testable; the screen resolves the resource id to a localized string.
 */
object ImportErrors {

    /** Failure kinds the SAF import path can produce. */
    enum class Kind { ACCESS, STORAGE, UNKNOWN }

    data class Message(val messageRes: Int, val causeArg: String?)

    /**
     * Maps an exception thrown during import to the error message resource
     * plus the exception detail for the cause line.
     *
     *  - SecurityException: access was revoked or never granted — recovery
     *    is to pick the clip again so the system re-asks for permission.
     *  - IOException: the file could not be read (moved, deleted, or
     *    storage trouble) — recovery is to check the file and pick again.
     *  - anything else: unknown cause, same recovery.
     */
    fun fromException(stage: String, e: Throwable): Message = when (e) {
        is SecurityException -> Message(
            messageRes = com.ritim.ritimcraw.R.string.library_import_failed_access,
            causeArg = e.message,
        )
        is java.io.IOException -> Message(
            messageRes = com.ritim.ritimcraw.R.string.library_import_failed_storage,
            causeArg = e.message,
        )
        else -> Message(
            messageRes = com.ritim.ritimcraw.R.string.library_import_failed_unknown,
            causeArg = e.message,
        )
    }.let { m ->
        // The stage (\"import\") is fixed in the message wording; the cause
        // detail rides along as the format arg.
        m
    }

    /** Which [Kind] an exception maps to (used by tests and analytics). */
    fun kindOf(e: Throwable): Kind = when (e) {
        is SecurityException -> Kind.ACCESS
        is java.io.IOException -> Kind.STORAGE
        else -> Kind.UNKNOWN
    }
}
