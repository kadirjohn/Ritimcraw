package com.ritim.ritimcraw.engine

/**
 * Pure mapping from a failed native preview call to the user-facing message
 * (DESIGN.md Error states: name the stage, the cause, and the recovery —
 * never a bare "failed"). Kept free of Android imports so it is
 * JVM-testable; the bridge reports code + stage + detail, this object turns
 * them into one message string.
 *
 * Contract with app/src/main/cpp/jni_bridge.cpp:
 *   code  = rawedit::ErrorCode (0 ok, 1 truncated, 2 badHeader, 3 ioError,
 *           4 unsupported)
 *   stage = pipeline stage token (0 open, 1 frame read, 2 develop)
 */
object PreviewErrors {

    // rawedit::ErrorCode (core/include/rawedit/Result.h).
    const val CODE_OK = 0
    const val CODE_TRUNCATED = 1
    const val CODE_BAD_HEADER = 2
    const val CODE_IO = 3
    const val CODE_UNSUPPORTED = 4

    // Stage tokens (jni_bridge.cpp kStageOpen/kStageFrameRead/kStageDevelop).
    const val STAGE_OPEN = 0
    const val STAGE_FRAME_READ = 1
    const val STAGE_DEVELOP = 2

    /** Stage name shown in the message ("frame read", "open", "develop"). */
    fun stageName(stage: Int): String = when (stage) {
        STAGE_OPEN -> "open"
        STAGE_FRAME_READ -> "frame read"
        STAGE_DEVELOP -> "develop"
        else -> "unknown"
    }

    /**
     * Cause + recovery wording per rawedit::ErrorCode. The recovery always
     * names a user action (DESIGN.md: every error names the recovery).
     */
    fun causeAndRecovery(code: Int): Pair<String, String> = when (code) {
        CODE_TRUNCATED ->
            "truncated clip" to "re-import the clip"
        CODE_BAD_HEADER ->
            "not a supported clip" to "re-import the clip"
        CODE_IO ->
            "the file could not be read" to "check the clip still exists, then reopen it"
        CODE_UNSUPPORTED ->
            "unsupported clip" to "re-import the clip"
        else ->
            "unknown error" to "reopen the clip"
    }

    /**
     * One-line message naming stage + cause + recovery, e.g.
     * "Preview render failed (stage: frame read): truncated clip — recovery: re-import the clip".
     * The native detail (when present) rides along in brackets so the
     * device pass can see exactly what the bridge reported.
     */
    fun message(code: Int, stage: Int, detail: String? = null): String {
        val (cause, recovery) = causeAndRecovery(code)
        val base = "Preview render failed (stage: ${stageName(stage)}): $cause — recovery: $recovery"
        return if (detail.isNullOrBlank()) base else "$base [$detail]"
    }
}
