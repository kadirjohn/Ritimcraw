package com.ritim.ritimcraw.export

/**
 * One step of the encoder-Surface input bring-up — the closed vocabulary
 * the fallback reason names (owner bug report 2026-11-13: a default-grade
 * GPU export fell back with reason=surface-input-unavailable and the
 * owner could not tell WHICH step failed — the Surface creation, the EGL
 * wrap, or the recordable query; the line named the category, the
 * diagnosis needs the step).
 *
 * Every failure point of the writer's Surface paths throws its
 * [SurfaceInputUnavailable] with the step that failed, and the exporter
 * appends the step's token to the SAME reason string, one line:
 *
 *   reason=surface-input-unavailable:egl-wrap
 *
 * The vocabulary is JVM-side because every throw site is JVM-side (the
 * writer's EGL/MediaCodec bring-up in MediaCodecMp4Writer); the native
 * seam never throws this — a GPU develop session that refuses the
 * encoder surface rides a DIFFERENT fallback line carrying the session's
 * own detail string, so no JNI crossing is needed.
 *
 * The constants are in bring-up order (the §10 sequence), each mapped to
 * its failure points in the writer:
 *  - [EGL_DISPLAY] — eglGetDisplay returned no display (setupEgl).
 *  - [EGL_INIT] — eglInitialize failed (setupEgl).
 *  - [EGL_CONFIG] — the recordable RGBA8888 config query found nothing
 *    (setupEgl; the "recordable-surface query" of the bug report).
 *  - [EGL_CONTEXT] — no GLES 3 context (setupEgl), or the context a
 *    Surface path needed was missing (the defensive guards).
 *  - [CODEC_CREATE] — createEncoderByType found no video/avc encoder.
 *  - [CODEC_CONFIG] — the codec rejected the COLOR_FormatSurface format.
 *  - [CREATE_INPUT_SURFACE] — createInputSurface gave no input Surface
 *    (or the surface the adopt fallback needed was missing).
 *  - [EGL_WRAP] — no EGL window surface could be created on the input
 *    Surface (the writer's own mode and the adopt fallback).
 *  - [EGL_CURRENT] — the GLES 3 context could not be made current.
 *  - [PRESENTATION_TIME_EXT] — eglPresentationTimeANDROID is not
 *    available (the pts stamp is a §10 contract, not a nicety).
 *  - [BLIT_SHADER] — a blit shader object could not be created or
 *    compiled.
 *  - [BLIT_PROGRAM] — the blit program could not be created or linked,
 *    or lost a uniform.
 *  - [CODEC_START] — the codec failed to start in Surface-input mode.
 *
 * The tokens are lowercase + hyphens (the owner's grep shape) and the set
 * is CLOSED: a new bring-up step adds a constant here, never an ad-hoc
 * string at a throw site.
 */
enum class SurfaceInputStep(val token: String) {
    /** eglGetDisplay returned no display (setupEgl). */
    EGL_DISPLAY("egl-display"),

    /** eglInitialize failed (setupEgl). */
    EGL_INIT("egl-init"),

    /** The recordable RGBA8888 config query found nothing (setupEgl). */
    EGL_CONFIG("egl-config"),

    /** No GLES 3 context, or the context a Surface path needed was missing. */
    EGL_CONTEXT("egl-context"),

    /** createEncoderByType found no video/avc encoder. */
    CODEC_CREATE("codec-create"),

    /** The codec rejected the COLOR_FormatSurface format. */
    CODEC_CONFIG("codec-config"),

    /** createInputSurface gave no input Surface (or the adopt surface was missing). */
    CREATE_INPUT_SURFACE("create-input-surface"),

    /** No EGL window surface could be created on the input Surface. */
    EGL_WRAP("egl-wrap"),

    /** The GLES 3 context could not be made current. */
    EGL_CURRENT("egl-current"),

    /** eglPresentationTimeANDROID is not available. */
    PRESENTATION_TIME_EXT("presentation-time-ext"),

    /** A blit shader object could not be created or compiled. */
    BLIT_SHADER("blit-shader"),

    /** The blit program could not be created or linked, or lost a uniform. */
    BLIT_PROGRAM("blit-program"),

    /** The codec failed to start in Surface-input mode. */
    CODEC_START("codec-start"),
}

/**
 * The writer could not set up its encoder-Surface input mode (GPU
 * deliverable F core, owner request 2026-11-12 §10): no EGL display, no
 * recordable RGBA8888 config, no GLES 3 context, the codec rejected
 * COLOR_FormatSurface, no input Surface, no EGL window surface on it, or
 * the blit program failed to build.
 *
 * [step] names the exact bring-up step that failed (the closed
 * [SurfaceInputStep] vocabulary) so the exporter's fallback line can say
 * more than the category (owner bug report 2026-11-13: the line read
 * reason=surface-input-unavailable and the diagnosis could not tell the
 * Surface creation from the EGL wrap from the recordable query). Null
 * (the default) = the step is unknown: the fallback reason keeps the
 * EXACT legacy string — a legacy writer's step-less throw changes
 * nothing in the owner's greps or the tests.
 *
 * The exporter catches THIS and only this for the honest CPU restart: the
 * whole export re-runs from frame 0 on the CPU path with a fresh writer —
 * no frame is encoded twice, no file continues mid-stream in an
 * incompatible format (§10: "fallback sonrasında kareyi iki kere encode
 * etmeme ... gerekirse temiz şekilde baştan başlatma"). Thrown ONLY
 * during writer start, never mid-encode: a mid-stream failure is a plain
 * encode failure (the partial is discarded, the job fails honestly).
 *
 * Pure JVM (java.lang only) so the restart rule is JVM-testable; the
 * message is an [Mp4ExportErrors.kotlinMessage] with the configure stage.
 */
class SurfaceInputUnavailable(
    message: String,
    val step: SurfaceInputStep? = null,
) : IllegalStateException(message) {

    companion object {

        /** The legacy fallback reason — also the full reason when the step is unknown. */
        const val REASON = "surface-input-unavailable"

        /**
         * The fallback line's reason string: the EXACT legacy [REASON]
         * when the step is unknown (null — a step-less throw keeps the
         * owner's greps and the existing tests byte-identical), and
         * `REASON:<step.token>` when the writer named its failing step.
         * ONE string, no newline: the fallback line stays one line.
         */
        fun reason(step: SurfaceInputStep?): String =
            if (step == null) REASON else "$REASON:${step.token}"
    }
}
