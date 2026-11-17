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
// documents, with those two preview differences — plus
// EGL_RECORDABLE_ANDROID, the config-class match to the develop
// session's device-proven encoder window path; white-window round 4,
// see the openEglOnGlThread block comment in the .cpp). setSurface()
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
// COLOR CONTRACT: the develop chain is the shared pipeline's SPLIT shape
// (gles/GpuDevelopPipeline — the same split since the gyro warp port,
// now compiled and drawn once by the pipeline object each session
// owns): PRE runs
// stages 1-6 into an RGBA32F intermediate, the three NR passes sit at
// the CPU's exact stage 7/8 position (gated per grade — the preview
// shares the develop program family, so chroma denoise and noise
// reduction preview too), the pointwise POST runs stages 9-20 into a
// chain slot, the four unsharp passes sit at the CPU's exact 21/22
// position (sharpness radius 1.5, detail radius 0.75 — unsharpLumaImpl
// verbatim as H+V pairs; gated per grade, so sharpness and detail
// preview too), then the gyro warp pass (gated per FRAME, not per
// grade) resamples the chain's live texture through the caller-composed
// inverse-sampling map M — the warp is NOT a develop stage (the CPU
// pipeline runs it after developCpu, on the linear output), so it sits
// between the unsharp passes and the tail and the OETF stays in the
// tail, applied exactly once — and TAIL runs stages 23+24+OETF into the
// develop texture (uOetf=1 — the shader's linearToRec709 is the CPU
// tail's twin, so the stats pack's toByte quantize reproduces
// developToArgb's pack: toByte(linearToRec709(rgb)) with no second
// OETF). The grade controls
// are exactly the fragment-covered set's knobs (exposure, highlights,
// highlight reconstruction, vignetting, tonemap plus the ten pointwise
// stages: black point, white point, shadows, midtones, contrast,
// temperature, tint, the color wheel, split tone, saturation — and the
// two NR strengths, sharpness and detail) — the same narrow-signature
// lock M3 rides (an uncovered grade cannot even be expressed through
// this API).
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

#include "GradeParcel.h"
#include "GpuDevelopPipeline.h"

namespace gles {

// One render request — the SAME struct the develop session rides. The
// struct was promoted VERBATIM into the shared pipeline header
// (gles/GpuDevelopPipeline.h — DevelopFrameRequest) when the develop
// chain became the shared pipeline: the two sessions' requests were
// field-for-field identical (same 34 fields, same order, same types and
// defaults), so the preview keeps the historical name as an alias and the
// JNI bridge's `gles::PreviewFrameRequest` references compile unchanged.
// [cfa] is the REDUCED mosaic (the JNI entry's decimation — Thumbnail.h's
// cell-aligned math, so the 2x2 Bayer pattern and the shading plane order
// stay valid at the preview tier), [info] its FrameInfo (reduced dims; cfa
// pattern, dynamic levels and orientationDeg preserved). [cfa] and [meta]
// are BORROWED: the caller keeps them alive until renderFrame() returns
// (it blocks, so stack locals are fine). The grade controls are the
// fragment-covered set's knobs — every one a RAW grade value the shader
// maps itself (0.0f is the CPU's own neutral skip), with the two CPU-side
// gates hoisted C++-computed exactly like the develop session's request.
// statsEnabled is the PREVIEW-ONLY measurement toggle (the develop session
// ignores it — its readback is the render's contract, not a measurement;
// see the field's own comment in GpuDevelopPipeline.h).
using PreviewFrameRequest = DevelopFrameRequest;

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
    // A developed/stats frame is not proof that the user saw a frame.
    // Set only after the window draw AND eglSwapBuffers succeed.
    bool presented = false;
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
    // it, and hands the Kotlin single-source-of-truth texts to the shared
    // pipeline (which checks the gates — ES 3.1+ — STORE_FRAGMENT is 310
    // es — and GL_EXT_color_buffer_float, re-read in THIS context, never
    // trusted from a probe's throwaway one — compiles the twelve
    // chain+store programs and resolves every chain uniform location)
    // and to the session's OWN window compile (window =
    // FULLSCREEN_VERTEX + WINDOW_FRAGMENT — the pipeline's vertex shader
    // is pipeline-private). OES_texture_float_linear is read in THIS
    // context the same way, but since the NEAREST PIN (owner device
    // evidence 2026-11-15, the white window) the verdict is NOT a Config
    // input anymore: the develop texture's filter is pinned NEAREST — a
    // mis-advertising Adreno fed the window pass's LINEAR reads of the
    // RGBA32F texture white while the same texture's stats draw stayed
    // healthy. Returns null with *detail
    // naming the first failing step on any failure (the GL thread is
    // joined; nothing leaks). The fifteen sources are the Kotlin
    // single-source-of-truth texts (GlesShaderSources), host-validated.
    static std::unique_ptr<PreviewSession> open(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& preFragmentSource,
        const std::string& postFragmentSource,
        const std::string& tailFragmentSource,
        const std::string& sharpnessHSource,
        const std::string& sharpnessVSource,
        const std::string& detailHSource,
        const std::string& detailVSource,
        const std::string& warpFragmentSource,
        const std::string& nrChromaHSource,
        const std::string& nrChromaVSource,
        const std::string& nrLumaSource,
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

    // GRADE PARCEL (commit a + b): blocking render, in-flight bounded at
    // one job by construction. Safe to call from one non-GL thread at a
    // time (the editor's engine thread). Draws the window present (when a
    // window surface is attached) and ALWAYS produces the published stats
    // pack. The grade arrives as the decoded parcel struct, folded onto a
    // stack copy of [req] via the GENERATED applyGradeToRequest
    // (gles/GradeParcel.h — the schema's single source of truth; a schema
    // field with no matching PreviewFrameRequest member fails the compile
    // here, the honest catch). [req] carries only the non-grade fields
    // (cfa/info/meta, the LUT binding group, the warp pair, statsEnabled);
    // the temporal pair never reaches the request — the bridge consumed it
    // at temporalMergedCfa before this call. The flat pre-folded overload
    // died with commit (b): every door into the session threads the grade
    // through the parcel.
    PreviewFrameResult renderFrame(const PreviewFrameRequest& req,
                                   const DevelopGrade& grade);

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

// THE WINDOW PROBE (white-window round 2, 2026-11-15) — the DEBUG-ONLY
// one-shot readback of the window pass, the pragmatic exception to this
// path's no-readback rule. The white window is ASPECT-DEPENDENT (the WIDE
// clips — 3840x2160 and 4096x2560 — paint white; the 4:3 clips are
// healthy), ZOOM-PROOF (pinch-zoom brings nothing — the compositor's
// buffer is white at composition level, not mis-rect'd) and
// STATS-HEALTHY (the same develop texture's stats draw shows real
// content), and the window draw+swap REPORTS SUCCESS — so no host-side
// test can see it and the class (draw-white vs surface-white) is
// undecidable without eyes on the window surface itself. The probe reads
// ONE pixel (glReadPixels 1x1 at the rect center, from the back buffer
// BEFORE eglSwapBuffers) and logs one line per attach per develop
// resolution under the "ritim-trace" tag:
//   window probe: attach win=WxH dots=(h,v) cfg[renderable=0x.. surface=0x..
//     visual=N recordable=R]     (once per attach; the config's class —
//     round 4's confirmation that the config match took effect)
//   window probe: rgb=(r,g,b) win=WxH vp=WxH rect=(x,y,W,H) src=WxH orient=N
//     entry[scissor=S box=(x,y,W,H) blend=B depth=D stencil=T mask=(r,g,b,a)]
// White=(255,255,255) proves the DRAW produced white (the
// shader/uniform/texture-input class); black proves the letterbox arm
// (rect math); real color proves the draw is right and the SURFACE/
// COMPOSITION loses it. The line carries the pass's own uniforms (rect,
// srcSize, orientation) so a white draw's inputs can be compared against
// a healthy clip's without a second build.
//
// ROUND 3 (2026-11-16): the probe answered rgb=(255,255,255) — the draw
// produces white — so the line now ALSO carries the draw-2 ENTRY STATE
// (the entry[...] fields): glIsEnabled/glGetIntegerv for scissor, blend,
// depth, stencil and the color write mask, read BEFORE the pass's clean
// entry resets them. The fields name the poison if the clean entry does
// not fix the white (a nonzero enable/mask is the culprit line), and a
// clean all-defaults entry with the white persisting rules the whole
// state class OUT for round 4.
//
// ROUND 4 (2026-11-17): the owner's replay delivered exactly that
// all-defaults verdict with the white persisting, so the state class is
// dead and the remaining divergence is the CONFIG CLASS — the encoder
// blit's config carries EGL_RECORDABLE_ANDROID, this session's did not.
// The fix rides the session's eglChooseConfig attribs (see the
// openEglOnGlThread block comment); the per-attach line now carries the
// chosen config's cfg[...] fields so the replay CONFIRMS the class
// changed (recordable=1) on the same line that already reports the
// surface geometry.
//
// The gate: a file-scope atomic HERE (the consumer's TU), set once from
// the JNI bridge's nativeSetPreviewWindowProbe — the jni_bridge.cpp
// gStabDebugLog precedent (NativeSource's companion init calls it with
// BuildConfig.DEBUG at library load), relocated next to its consumer.
// Release builds read one relaxed load per window draw and never read a
// pixel; the probe also swallows its own GL error so the stats pass's
// error check stays clean.
void setWindowProbeEnabled(bool enabled);
bool windowProbeEnabled();

} // namespace gles
