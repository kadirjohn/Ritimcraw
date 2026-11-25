// Production GPU preview session (M4 commit a) — the audit's E-section
// "SurfaceView GPU preview" deliverable: the SAME production session
// pattern M3 built for export (GlesDevelopSession), wired into PREVIEW.
//
// WHAT IT IS: a persistent GLES session that develops the PREVIEW-TIER
// frame — the caller decimates the CFA on its own engine thread (the
// documented copy of Thumbnail.h's cell-aligned math lives in the JNI
// entry; this session receives the reduced mosaic) — and then:
//
//   - draws the developed texture into an RGBA8 FBO TWIN at the
//     SurfaceView window's dims and blits the twin to the Android
//     Surface (the SurfaceHolder's window) — the round-6 present path
//     (white-window fix): the SAME window pass (WINDOW_FRAGMENT) with
//     aspect-fit letterbox geometry and the frame orientation folded
//     in-shader, one TRIANGLE_STRIP draw INTO THE TWIN + one
//     shader-less glBlitFramebuffer to the window surface +
//     eglSwapBuffers. No fragment shader executes against the window
//     surface — the five-way elimination (filter / composition / GL
//     state / config / first-present timing, all device-falsified)
//     left the shader-into-window execution as the only suspect, so the
//     window pass now runs the stats path's own proven-healthy shape
//     (an RGBA8 FBO destination) and the twin becomes THE present
//     path;
//   - ALWAYS ALSO produces the small published frame (~256 long edge,
//     upright, the SAME [w, h, 0xFF000000|(r<<16)|(g<<8)|b] int32 pack
//     shape developToArgb returns) that the Compose overlays (zebra,
//     scopes) consume — through the SAME mapped-SSBO readback the
//     export session uses, on a ~256x144x3-float buffer, NOT the frame.
//     (The ONE exception: the per-request stats-readback MODE — see
//     PreviewFrameRequest::statsMode — whose OFF value skips draw 3 + the
//     readback entirely for a no-scope frame, and the published frame
//     becomes the honest 1x1 empty fallback.)
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
#include <functional>
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
// statsMode is the PREVIEW-ONLY readback mode (the develop session
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
    // (cfa/info/meta, the LUT binding group, the warp pair, statsMode, the A2 crop pair: cropActive/cropParams, 2026-11-23, guide A2);
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

    // ---- color model v2 F1-5: the NODES/DRT additive doors ------------------
    //
    // initNodes: compiles the two F1-5 programs through the pipeline's
    // ADDITIVE init door (the session's open() and every existing door stay
    // untouched — a session that never calls this keeps the exact pre-F1-5
    // behavior, and a nodes-bearing request folds to a refusal in
    // pipeline.draw's honest "not initialized" path). Runs on the CALLING
    // thread (the caller's contract is the same as open: the session's GL
    // thread is the owner, but init() itself was called THROUGH
    // open's job handoff — this door is likewise posted to the GL thread
    // internally, blocking until done). [limits] receives the two device
    // values the pipeline queried at the same time
    // (GL_MAX_FRAGMENT_UNIFORM_VECTORS / GL_MAX_TEXTURE_IMAGE_UNITS).
    bool initNodes(const std::string& nodesSource, const std::string& vertexSource,
                   const std::string& tailDrtSource,
                   GpuDevelopPipeline::NodesLimits* limits, std::string* detail);

    // setColorModelChain: the NODES chain's F1-5 seam — the C++-level door
    // the JNI binding (F1-6) wraps. BORROWED rawedit::GradeNode values (the
    // caller keeps them and any node Lut alive until the blocking render
    // returns; the session DEEP-COPIES the knobs for the fold), the output
    // mode, the 77-float DRT uniform block (REQUIRED for mode 1) and the
    // caller's chain hash. The state is LIVE for the session's NEXT
    // renderFrame call(s) until replaced or cleared; nodeCount 0 + mode 0
    // CLEARS it (the plain v1 chain — nodesActive false, the regression
    // lock's shape). Validated here (the N1 caps + the same
    // validateNodeChain the pipeline enforces) with the honest refusal
    // channel.
    //
    // THREADING: called from the caller's engine thread (the same profile
    // as every other non-GL-thread setter); the buffers swap under the
    // session's own mutex so a render in flight never sees them change.
    bool setColorModelChain(const rawedit::GradeNode* nodes, int nodeCount,
                            int outputMode, const float* drtUniforms,
                            uint64_t chainHash, std::string* detail);

    // F1-8e: [cb] runs on the OpenDRT bake thread once per table the async bake published (the owner asks
    // for ONE redraw; it must only post work). Empty clears it. Thread-safe.
    void setDrtReadyCallback(std::function<void()> cb);

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
// ONE 1x1 PIXEL PER SIDE (glReadPixels 1x1: the develop texture itself
// through the probe's FBO, the twin at the rect center pre-blit, and the
// back buffer at the rect center BEFORE eglSwapBuffers) and logs one
// line per attach per develop
// resolution under the "ritim-trace" tag:
//   window probe: attach win=WxH dots=(h,v) cfg[renderable=0x.. surface=0x..
//     visual=N recordable=R]     (once per attach; the config's class —
//     round 4's confirmation that the config match took effect)
//   window probe: shot=N tex=(r,g,b) twin=(r,g,b) rgb=(r,g,b) realloc=R
//     win=WxH vp=WxH rect=(x,y,W,H) src=WxH orient=N entry[scissor=S
//     box=(x,y,W,H) blend=B depth=D stencil=T mask=(r,g,b,a)]     (twice
//     per attach/resolution since round 5: shot=1 the first frame, shot=2
//     the frame after the next swap; since round 6 ONE line carries BOTH
//     the twin's pre-blit color — what the draw produced — and the
//     window's post-blit color; since round 7 the line leads with tex=
//     — the develop texture's OWN center texel read directly through
//     the probe's FBO — and carries realloc=, the frame's once-per-
//     resolution allocation verdict)
// Round 6's verdict reads the PAIR directly (see the ROUND 6 block
// below): twin color + window color -> fixed; twin color + window white
// -> the blit's window write is broken too; twin white -> impossible
// per the stats pack's health on the same frame (STOP — the model is
// wrong). The line still carries the pass's own uniforms (rect,
// srcSize, orientation) and the round-3 entry state so a broken replay
// diffs against a healthy one without a second build.
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
// ROUND 5 (2026-11-17): the replay confirmed recordable=1 with the white
// persisting — the config class is dead too — and the owner's new clue
// decoded the symptom: on zebra toggling "once the CENTER of the video
// (a square) renders, then everything" — THE PICTURE ARRIVES. The fix
// was the vsync-aligned present + the first-frame fence (see
// runSurfaceJob's interval comment and runJob's fence block); the
// probe's pixel line fired TWICE per attach/resolution — the first
// frame AND the frame after the next swap (shot=1 / shot=2).
//
// ROUND 6 (2026-11-17, THE FBO-TWIN + BLIT): the round-5 replay read
// shot=1 AND shot=2 BOTH white — the first-present timing class is dead
// too (the white persists across CONSECUTIVE frames; the healing
// moments the owner saw were RE-ATTACHES, not frame-index
// convergence), and the owner's AUTO note confirmed the class bound
// (AUTO — the CPU path — plays immediately; only the GPU window path
// breaks). FIVE classes are now PROVEN dead on-device: filter (round
// 1's NEAREST pin), composition (round 2 read the white IN the back
// buffer, pre-swap), GL state (round 3 read all spec defaults), config
// (round 4's recordable=1), timing (round 5's shot=1 AND shot=2 both
// white). THE CONSTANT across every round: the SAME windowProgram +
// developTex + uniform family draws HEALTHY into the stats FBO (RGBA8 —
// the zebra strip shows real content on the very frames the window
// reads white) and WHITE into the EGL window surface — the window
// draw's own execution against the window surface is the only suspect
// left. THE PRESENT PATH REWRITE: no fragment shader ever executes
// against the window surface again. The SAME window draw (the SAME
// WINDOW_FRAGMENT, the same uRect/orientation fold, the same texture
// unit) lands in an RGBA8 FBO TWIN at the window's dims — the stats
// path's own proven-healthy shape; the ONLY difference from the old
// draw is the destination, an FBO instead of the window surface — and
// the twin is then glBlitFramebuffer'd to the window surface
// (shader-less, same dims, full-buffer rects, no orientation fold —
// the fold already happened in the draw). The twin is allocated once
// per window-dims change (the stats allocation's once-per-dims shape,
// keyed on the per-frame eglQuerySurface pair). The probe moved with
// the draw: the pixel line now carries BOTH sides on ONE line —
// twin=(r,g,b) (the pre-blit readback of the twin, what the draw
// produced) and rgb=(r,g,b) (the post-blit read of the window
// surface's back buffer, pre-swap; the existing second latch's
// shot=1/shot=2 ladder stays) so the replay's verdict is DIRECT:
//   twin color + window color -> FIXED (the driver's shader-into-window
//     path was the culprit; the app never draws a shader into the
//     window again — the twin becomes THE present path);
//   twin color + window white -> the blit's window write is broken
//     too (round 7: the Kotlin SurfaceView side — the holder's surface
//     format, setZOrder, the buffer transform hint);
//   twin white -> IMPOSSIBLE per the stats pack's health on the same
//     frame — STOP, the whole model is wrong.
//
// ROUND 7 (2026-11-18, THE STOP CONDITION HIT — FIRST THE PROBE'S OWN
// CORRECTNESS): the round-6 replay hit the line the brief called
// impossible: shot=1 AND shot=2 both read twin=(255,255,255)
// rgb=(255,255,255) — the twin drew white on the same frame the stats
// pack (the same develop texture's stats window pass) was healthy. The
// manager's ruling before ANY further fixing: verify the probe itself
// first, then decide. The two candidate readings died IN THE CODE:
//   (1) THE BINDING-ORDER READBACK BUG: dead by citation — the twin's
//       glReadPixels pins GL_READ_FRAMEBUFFER=twinFbo explicitly
//       BEFORE reading, and that read runs BEFORE the blit (the only
//       call that ever moves the READ binding is the blit itself, which
//       pins both sides explicitly, and the window read re-pins READ=0
//       itself). ONE white reading reported twice was impossible; both
//       whites were real readings of their own side.
//   (2) THE REALLOC-FRAME CHAIN SKIP: dead by citation — draw() has NO
//       chain skip anywhere. The per-grade/per-frame gates bypass
//       individual NR/unsharp/warp passes; the TAIL pass
//       (draw 11, "The TAIL pass ALWAYS writes developTex") is
//       UNCONDITIONAL, and the allocation branch and the draws are
//       sequential lines of the one draw() body — a frame that
//       re-allocated the develop texture PAID the chain in the same
//       call (ok=true proves it). dev=0.1ms is the CPU-side ENQUEUE
//       span of eleven async glDrawArrays calls, not a skip signature
//       — the healthy 4:3 clips' own trace lines read dev=0.1ms.
// So the probe was extended INSTEAD of any blind fix — THE THREE-WAY
// LINE: tex=(r,g,b) is the develop texture's OWN center texel, read
// DIRECTLY through the probe's throwaway FBO (attach
// pipeline.developTex() as the color attachment, glReadPixels
// RGBA/GL_FLOAT, toByte quantize — no draw, no sampler, no filter, no
// aspect-fit fold; a DIFFERENT mechanism from both destinations),
// twin= stays (the draw's output), rgb= stays (the blit's window).
// tex=(-1,-1,-1) names a failed read, never a quiet white; realloc=
// names the once-per-resolution frames the develop texture was
// deleted + re-created (the DrawResult flag — the honest reportable
// state where reading 2 imagined a skip). The three-way verdict, every
// combination decisive:
//   tex WHITE + any twin -> the develop texture itself is WHITE on the
//     probe frames (whatever the host-side reading said) — the chain's
//     output on those frames is the wrong thing; the realloc=
//     correlation decides the follow-up;
//   tex COLOR + twin WHITE -> the develop texture is healthy and the
//     WINDOW DRAW itself produces white into FBOs on this driver
//     despite the healthy stats pass of the same texture — the twin
//     draw vs the stats draw line-by-line diff is round 8;
//   tex COLOR + twin COLOR + rgb WHITE -> the draw works, the blit's
//     window write is the broken leg — round 8 goes to the Kotlin
//     SurfaceView side (surface format, z-order, buffer transform).
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

// The app's cumulative preview GPU time in nanoseconds (sum of every
// preview frame's GL_TIME_ELAPSED query), or -1 when no live preview
// session has GL_EXT_disjoint_timer_query. Any thread; relaxed atomics.
// The system monitor strip samples it once a second (delta / wall time =
// the app's GPU share).
int64_t previewGpuNanosTotal();

} // namespace gles
