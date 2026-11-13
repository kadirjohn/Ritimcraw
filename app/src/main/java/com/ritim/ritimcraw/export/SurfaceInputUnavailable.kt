package com.ritim.ritimcraw.export

/**
 * The writer could not set up its encoder-Surface input mode (GPU
 * deliverable F core, owner request 2026-11-12 §10): no EGL display, no
 * recordable RGBA8888 config, no GLES 3 context, the codec rejected
 * COLOR_FormatSurface, no input Surface, no EGL window surface on it, or
 * the blit program failed to build.
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
class SurfaceInputUnavailable(message: String) : IllegalStateException(message)
