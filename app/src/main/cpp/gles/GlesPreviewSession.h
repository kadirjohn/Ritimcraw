// Production GPU preview session (M4 commit a) — the audit's E-section
// "SurfaceView GPU preview" deliverable: the SAME production session
// pattern M3 built for export (GlesDevelopSession), wired into PREVIEW.
//
// WHAT IT IS: a persistent GLES session that develops the PREVIEW-TIER
// frame — the caller decimates the CFA on its own engine thread (the
// documented copy of Thumbnail.h's cell-aligned math lives in the JNI
// entry; this session receives the reduced mosaic) — and then:
//
//   - draws the developed texture to the Android Surface (the
//     SurfaceHolder's window) with aspect-fit letterbox geometry and the
//     frame orientation folded in-shader — the window pass
//     (WINDOW_FRAGMENT), one TRIANGLE_STRIP draw + eglSwapBuffers;
//   - ALWAYS ALSO produces the small published frame (~256 long edge,
//     upright, the SAME [w, h, 0xFF000000|(r<<16)|(g<<8)|b] int32 pack
//     shape developToArgb returns) that the Compose overlays (zebra,
//     scopes) consume — through the SAME mapped-SSBO readback the
//     export session uses, on a ~256x144x3-float buffer, NOT the frame.
//     (The ONE exception: the debug-only stats-readback toggle — see
//     PreviewFrameRequest::statsEnabled — skips draw 3 + the readback
//     entirely for the measurement run, and the published frame becomes
//     the honest 1x1 empty fallback.)
//
// THE NO-READBACK RULE (the audit's explicit demand): glReadPixels is
// used NOWHERE on this path, and NO full-frame readback happens — the
// developed frame lands in the session's RGBA32F texture and STAYS on
// the GPU; the only CPU-bound pixels are the small stats pack. (The
// scope/still paths elsewhere MAY read back downsampled data; this
// session's contract is exactly the small pack.)
//
// THE WINDOW SURFACE: unlike M3's pbuffer-only EglSession, this session
// owns its EGL end-to-end with a WINDOW-capable config
// (EGL_WINDOW_BIT | EGL_PBUFFER_BIT, the config KEPT for
// eglCreateWindowSurface — the grafika topology GlesPipeline.h
// documents, with those two preview differences). setSurface()
// marshals the SurfaceHolder's window onto the GL thread as a SECOND
// job kind on the same bounded one-job handoff: the session takes
// OWNERSHIP of the ANativeWindow reference at CALL ENTRY (releasing it
// on every path — refusal, surface failure, replace, teardown), a
// DESTROY (nullptr) always processes (even on a latched-unhealthy
// session — teardown must be able to release the window whatever
// happened to the render path), and a surface-creation failure does NOT
// latch the session unhealthy: the context is fine, the surface isn't;
// the UI retries or falls back. Render failures DO latch (M3's sticky
// rule).
//
// QUEUE SEMANTICS (the audit's bounded newest-request rule, mapped
// honestly): the C++ handoff stays bounded at ONE job by construction —
// renderFrame()/setSurface() block until the GL thread finishes, so
// there is no queue to overflow; the newest-request queue with
// timestamps IS the Kotlin RenderGate the editor already runs (untouched
// by this commit); the anti-starvation rule (a completed frame is never
// dropped merely because a newer request exists) IS the pump's monotonic
// isFresh/lastPublished publication; obsolete clip/grade generations
// are rejected by the JNI's clip-handle validation plus the Kotlin
// sequence gate. This session is the bounded servant under all of that.
//
// THREADING: identical to M3 — open() spawns the GL thread; every
// EGL/GL call for the session's lifetime, including teardown, runs on
// that thread (EGL contexts are not portable across threads).
// renderFrame()/setSurface() may be called from any ONE other thread
// (the editor's engine thread); both block on their job.
//
// COLOR CONTRACT: the develop pass is M3's verbatim (the same 17
// uniforms, uOetf=1 — the shader's linearToRec709 is the CPU tail's
// twin, so the stats pack's toByte quantize reproduces developToArgb's
// pack: toByte(linearToRec709(rgb)) with no second OETF). The grade
// controls are exactly the fragment-covered set's knobs (five since the
// stage-15 highlights port: exposure, highlights, highlight
// reconstruction, vignetting, tonemap) — the same narrow-signature lock
// M3 rides (an uncovered grade cannot even be expressed through this
// API).
//
// FAILURE SEMANTICS: a GL failure mid-render returns ok=false with the
// step named in [detail] AND LATCHES the session unhealthy (M3's sticky
// rule) — later renderFrame() calls fail fast with the same detail and
// the Kotlin gate falls the preview back to CPU. The window PRESENT is
// best-effort: a present failure does NOT fail the frame (the published
// stats pack is the render's contract) and does NOT latch — the surface
// lifecycle (surfaceDestroyed -> setSurface(nullptr)) is the UI's
// signal, not a frame error.
//
// Status vocabulary (the mission's honesty rule): this code COMPILES for
// Android but its runtime behavior is DEVICE-TEST-PENDING — the owner's
// morning playback run is the production proof. The math it runs is the
// math the M0 parity harness already proved on device (fragment parity
// PASS at 1 LSB, 398 of 24.8M samples).

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <android/native_window.h>

#include <rawedit/Source.h>

namespace gles {

// One render request. [cfa] is the REDUCED mosaic (the JNI entry's
// decimation — Thumbnail.h's cell-aligned math, so the 2x2 Bayer pattern
// and the shading plane order stay valid at the preview tier), [info] its
// FrameInfo (reduced dims; cfa pattern, dynamic levels and orientationDeg
// preserved). [cfa] and [meta] are BORROWED: the caller keeps them alive
// until renderFrame() returns (it blocks, so stack locals are fine). The
// grade controls are exactly the fragment-covered set's knobs.
struct PreviewFrameRequest {
    const std::vector<uint16_t>* cfa = nullptr;  // reduced w*h row-major
    rawedit::FrameInfo info{};
    const rawedit::RawMeta* meta = nullptr;
    float exposureEv = 0.0f;
    float highlights = 0.0f;  // raw grade.highlights; the shader computes s
    bool highlightReconstruction = true;
    bool keepVignetting = true;
    int tonemap = 0;  // 0 off, 1 ACES, 2-5 AgX (Grade::tonemap)
    // The stats-readback measurement toggle (trace-dims fix 2026-11-14):
    // true — the DEFAULT, and today's behavior — runs draw 3 + the
    // mapped-SSBO readback and publishes the real stats pack; false skips
    // BOTH entirely, and the published frame becomes the honest 1x1
    // empty fallback (the zebra/scopes consumers degrade for the
    // measurement run — the trace line reads stats=off). The flag RIDES
    // the request (the gSyncRefine precedent) so the session reads it per
    // render without a second native call; the JNI entry copies the
    // bridge-level atomic onto every request. Release builds never flip
    // the atomic, so the pack always publishes there.
    bool statsEnabled = true;
};

// One render result. ok=false means the step named in [detail] failed (or
// the session is latched unhealthy — [detail] repeats the latched
// reason). ok=true may still carry a NON-FATAL window-present diagnostic
// in [detail]: the published frame is the render's contract; the present
// is best-effort (see the failure semantics above).
//
// The measurement fields (trace-dims fix 2026-11-14) carry the dims the
// ritim-trace line logs on the GPU path — the develop/surface dims the
// published pack hides — and the four GL-thread timing spans, measured
// with steady_clock inside runJob. Every span is ALWAYS collected (the
// cost is four clock reads per frame, negligible next to any draw; the
// debug-only discipline lives in the trace gate that PRINTS them, the
// RenderTrace.enabled precedent) so the JNI tail is one stable shape.
struct PreviewFrameResult {
    bool ok = false;
    std::vector<uint32_t> argb8888;  // width*height pixels, row-major,
                                      // upright, top row first
    uint32_t width = 0;              // the published (stats) dims
    uint32_t height = 0;
    // The develop pass's actual output dims (the develop texture — the
    // reduced CFA's developed frame, what the window pass samples).
    uint32_t developW = 0;
    uint32_t developH = 0;
    // The window surface's presented dims (0x0 when no surface attached
    // or the query failed — the present is best-effort).
    uint32_t surfaceW = 0;
    uint32_t surfaceH = 0;
    // The four GL-thread spans in MICROSECONDS (double; the JNI tail
    // carries them as jints — microsecond resolution fits the per-frame
    // scale): upload = the per-frame CFA glTexSubImage2D; develop = the
    // develop draw; present = the window-present block including
    // eglSwapBuffers; stats = draw 3 + the mapped-SSBO readback (0 when
    // the toggle skipped the whole stats stage).
    double uploadUs = 0.0;
    double developUs = 0.0;
    double presentUs = 0.0;
    double statsUs = 0.0;
    std::string detail;
};

class PreviewSession {
public:
    // Spawns the GL thread, opens the session's OWN window-capable EGL on
    // it, checks the gates (ES 3.1+ — STORE_FRAGMENT is 310 es — and
    // GL_EXT_color_buffer_float, re-read in THIS context, never trusted
    // from a probe's throwaway one; OES_texture_float_linear is read the
    // same way to pick the develop texture's filter, NOT a gate),
    // compiles the three programs (develop = FULLSCREEN_VERTEX +
    // DEVELOP_FRAGMENT, store = FULLSCREEN_VERTEX_310 + STORE_FRAGMENT,
    // window = FULLSCREEN_VERTEX + WINDOW_FRAGMENT) and resolves every
    // uniform location. Returns null with *detail naming the first
    // failing step on any failure (the GL thread is joined; nothing
    // leaks). The five sources are the Kotlin single-source-of-truth
    // texts (GlesShaderSources), host-validated.
    static std::unique_ptr<PreviewSession> open(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& fragmentSource,
        const std::string& storeFragmentSource,
        const std::string& windowFragmentSource,
        std::string* detail);

    // Drains (at most one in-flight job — the bounded design) and joins
    // the GL thread; all GL/EGL teardown happens on that thread.
    // Idempotent.
    ~PreviewSession();

    PreviewSession(const PreviewSession&) = delete;
    PreviewSession& operator=(const PreviewSession&) = delete;

    // False after a latched render failure (see the failure semantics).
    // failureDetail() returns a COPY on purpose: the GL thread may latch a
    // new detail concurrently, and a reference would alias a string being
    // reassigned under it.
    bool healthy() const;
    std::string failureDetail() const;

    // Blocking render; in-flight bounded at one job by construction. Safe
    // to call from one non-GL thread at a time (the editor's engine
    // thread). Draws the window present (when a window surface is
    // attached) and ALWAYS produces the published stats pack.
    PreviewFrameResult renderFrame(const PreviewFrameRequest& req);

    // Attaches ([window] != nullptr) or destroys ([window] == nullptr) the
    // SurfaceView's window, on the GL thread, blocking until done.
    // OWNERSHIP of the ANativeWindow reference (the caller's
    // ANativeWindow_fromSurface acquire) transfers AT CALL ENTRY: the
    // session releases it on every path — a fast refusal (a closed
    // session, or an ATTACH refused while latched unhealthy) releases on
    // the calling thread, and the attach job releases on the GL thread
    // whether the surface was created or not (on success
    // eglCreateWindowSurface holds the window's own reference, so ours
    // is redundant; on failure nothing else holds it). The caller NEVER
    // releases it after this call. A call while another job is in flight
    // WAITS for the one-job slot — it does not refuse (the bounded
    // handoff's rule). A DESTROY always processes, even on a
    // latched-unhealthy session; an ATTACH on an unhealthy session fails
    // fast with the latched detail. Returns false with *detail on
    // failure; a surface failure does NOT latch the session unhealthy
    // (the context is fine — the surface isn't; the UI retries or falls
    // back).
    bool setSurface(ANativeWindow* window, std::string* detail);

    // See the destructor. Callable from the owning thread.
    void close();

private:
    PreviewSession() = default;
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gles
