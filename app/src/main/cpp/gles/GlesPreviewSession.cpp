// Production GPU preview session — the live-view counterpart of the
// develop session, riding the SAME develop chain through the shared
// pipeline.
//
// THE DEVELOP CHAIN LIVES IN THE SHARED PIPELINE (gles/GpuDevelopPipeline.
// {h,cpp}): the twelve chain programs (pre/post/tail + the four unsharp
// passes + the three NR passes + warp + store), every chain uniform
// location, the static texture-unit map (0 CFA / 1 pass source, also store
// + window / 2 scratch / 3 shading / 4 guide / 5 LUT), the texA/texB/texC +
// fboOf[] threading, the CFA/shading/LUT upload caches, the CPU-side
// stage-1/5/6 uniform math, the per-grade gating, the once-per-resolution
// allocation, the ES 3.1 + GL_EXT_color_buffer_float gates (checked in
// pipeline.init, in THIS context), and the whole PRE -> [NR] -> POST ->
// [unsharp] -> [WARP] -> TAIL draw sequence with its upload/develop span
// measurement. The pipeline object is constructed with THIS session's
// context current and is drawn SYNCHRONOUSLY on THIS session's GL thread —
// the two sessions' GL contexts and resources are never shared; the shared
// thing is the CODE.
//
// WHAT THIS TU KEEPS (the session's own tail, everything the pipeline
// cannot own because it is preview-specific): the session's own window-
// capable EGL (raw display/config/context/pbufferSurface handles, the
// config KEPT for eglCreateWindowSurface), the GL thread + the bounded
// one-job handoff (Render + Surface kinds) with the latched failure
// semantics (Render failures latch, Surface failures never), the session's
// OWN window program (the pipeline's vertex shader is pipeline-private),
// the window present (best-effort, never fails the frame), the stats pack
// (the session's own small RGBA8 scratch pair + FBOs + SSBO readback +
// toByte quantize + row-flip pack; glReadPixels is used NOWHERE — the
// recorded baseline invariant), and the statsEnabled toggle (false skips
// the stats draws + readback and publishes the honest 1x1 empty fallback).
//
// THREADING (the contract in one paragraph): open() spawns the GL thread;
// every EGL/GL call for the session's lifetime — including teardown — runs
// on that thread. renderFrame()/setSurface() may be called from any one
// other thread at a time (the engine thread + the UI thread); each BLOCKS
// until the GL thread finishes the one job — in-flight is bounded at
// exactly one by construction, there is no queue to overflow, and the
// preview wakes ALL waiters (two waiter threads). close() drains and
// joins the thread; it is idempotent and the destructor calls it.
//
// COLOR CONTRACT: the math is the pipeline's — unchanged from the SPLIT
// shape the develop session proved (the three-way split recomposes the
// fused DEVELOP_FRAGMENT BYTE-IDENTICALLY, the host-checked invariant; the
// full stage-by-stage contract lives in GlesDevelopSession.h). The window
// pass samples the developed texture (linear sdr-rgb, the tail's OETF
// already applied) through the uOrientation fold; the stats pass renders
// the SAME window pass at the small dims into the stats texture and
// quantizes with the SAME toByte tail.
//
// FAILURE SEMANTICS: a GL failure mid-render returns ok=false with the
// step named in [detail] AND LATCHES the session unhealthy — later
// renderFrame() calls fail fast with the same detail. A surface failure
// (setSurface) NEVER latches (the context is fine — the window isn't).
//
// Status vocabulary (the mission's honesty rule): this code COMPILES for
// Android but its runtime behavior is DEVICE-TEST-PENDING — the owner's
// GPU preview is the production proof. The math it runs is the math the
// harness already proved on device (developFragmentParity PASS).

#include "GlesPreviewSession.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

#include <EGL/egl.h>
#include <GLES3/gl31.h>
#include <android/log.h>

namespace gles {
namespace {

// ---- GL helpers — verbatim from GlesPipeline.cpp's anonymous namespace
// ---- (same keep-in-sync rule). -------------------------------------------

struct GlShader {
    GLuint id = 0;
    ~GlShader() { if (id != 0) glDeleteShader(id); }
};

struct GlProgram {
    GLuint id = 0;
    ~GlProgram() { if (id != 0) glDeleteProgram(id); }
};

// First GL error after [op], or GL_NO_ERROR. Used only to name a failed
// step honestly in the report.
GLenum glErrorAfter(const char* op, std::string* detail) {
    const GLenum err = glGetError();
    if (err != GL_NO_ERROR && detail != nullptr && detail->empty()) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%s: gl error 0x%04x", op, (unsigned)err);
        *detail = buf;
    }
    return err;
}

// Compile one shader of [type] from [source] (the caller-provided Kotlin
// text). Returns the GL name on success; 0 on failure (no leaks: the
// caller wraps the name in GlShader).
GLuint compileShader(GLenum type, const std::string& source, const char* stage,
                     std::string* detail) {
    GLuint id = glCreateShader(type);
    if (id == 0) {
        if (detail != nullptr && detail->empty()) *detail = std::string("glCreateShader ") + stage;
        return 0;
    }
    const char* src = source.c_str();
    glShaderSource(id, 1, &src, nullptr);
    glCompileShader(id);
    GLint compiled = GL_FALSE;
    glGetShaderiv(id, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512] = {0};
        GLsizei len = 0;
        glGetShaderInfoLog(id, sizeof(log) - 1, &len, log);
        if (detail != nullptr && detail->empty()) {
            *detail = std::string(stage) + "-compile: " + log;
        }
        glDeleteShader(id);
        return 0;
    }
    return id;
}

// Link [vert]+[frag] into a program. Returns 0 on failure (the caller
// wraps the name).
GLuint linkProgram(GLuint vert, GLuint frag, const char* stage, std::string* detail) {
    GLuint id = glCreateProgram();
    if (id == 0) {
        if (detail != nullptr && detail->empty()) *detail = std::string("glCreateProgram ") + stage;
        return 0;
    }
    glAttachShader(id, vert);
    glAttachShader(id, frag);
    glLinkProgram(id);
    GLint linked = GL_FALSE;
    glGetProgramiv(id, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {0};
        GLsizei len = 0;
        glGetProgramInfoLog(id, sizeof(log) - 1, &len, log);
        if (detail != nullptr && detail->empty()) {
            *detail = std::string(stage) + "-link: " + log;
        }
        glDeleteProgram(id);
        return 0;
    }
    return id;
}

// linearToRec709 output is unclamped; clamp then quantize to 0..255 — the
// toByte tail verbatim from jni_bridge.cpp (which owns the text). The
// shader already applied linearToRec709 (uOetf=1); this quantize is the
// ONLY CPU-side per-pixel work on the stats readback.
inline uint8_t toByte(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}

// The published frame's long edge (the small stats pack the Compose
// overlays — zebra, scopes — consume). ~256 keeps the pack at a few
// hundred KB while giving the scopes a stable grid; the WINDOW present
// is the full preview-tier frame and never crosses this constant.
constexpr uint32_t kStatsLongEdge = 256;

// Microseconds between two steady_clock points — the span arithmetic for
// the result's four timing fields (the jni_bridge.cpp elapsedMs precedent,
// at microsecond resolution because the per-frame spans are ms-scale).
inline double elapsedUs(const std::chrono::steady_clock::time_point& a,
                        const std::chrono::steady_clock::time_point& b) {
    return std::chrono::duration<double, std::micro>(b - a).count();
}

} // namespace

// ---- THE WINDOW PROBE GATE (white-window round 2) -----------------------------
//
// The debug gate for the one-shot window readback (see GlesPreviewSession.h's
// setWindowProbeEnabled comment for the full evidence chain). The atomic
// lives HERE — next to its only consumer — and the JNI bridge's
// nativeSetPreviewWindowProbe stores it (the jni_bridge.cpp gStabDebugLog
// precedent, relocated to the consumer's TU): NativeSource's companion init
// calls the setter with BuildConfig.DEBUG at library load, so a release
// build reads ONE relaxed load per window draw and never reads a pixel.
std::atomic<bool> gWindowProbe{false};

void setWindowProbeEnabled(bool enabled) {
    gWindowProbe.store(enabled, std::memory_order_relaxed);
}

bool windowProbeEnabled() {
    return gWindowProbe.load(std::memory_order_relaxed);
}

// ---- Impl -----------------------------------------------------------------

struct PreviewSession::Impl {
    // --- GL-thread-owned state (touched ONLY on the GL thread; the mutex
    // --- guards only the handoff/latch fields below) ----------------------
    // The session's OWN EGL (EglSession in GlesPipeline.h is pbuffer-only;
    // a window needs the config KEPT — see openEglOnGlThread).
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface pbufferSurface = EGL_NO_SURFACE;
    EGLSurface windowSurface = EGL_NO_SURFACE;

    // The shared develop chain, constructed with THIS session's context
    // current. The pipeline owns the twelve chain+store programs, every
    // chain uniform location, the static texture-unit map, the CFA/shading/
    // LUT upload caches, the CPU-side uniform math, the per-grade gating,
    // the once-per-resolution allocation, and the whole draw sequence —
    // drawn synchronously on THIS GL thread. The session reads ONLY the
    // pipeline's public accessors (developTex, storeProgram, storeSizeLoc)
    // for its own window + stats passes.
    GpuDevelopPipeline pipeline;

    // The session's OWN window program (the pipeline's vertex shader is
    // pipeline-private): developed texture -> the window surface.
    GLuint windowProgram = 0;
    GLint windowSrcLoc = -1, windowSrcSizeLoc = -1, windowRectLoc = -1,
          windowOrientationLoc = -1;

    // The stats pack's resources (the ONLY readback on this path):
    // statsTex is the window pass's stats target; statsScratchTex is the
    // store pass's throwaway color target (NEVER statsFbo, whose texture
    // is the source — a feedback loop); statsBuf is the small SSBO.
    GLuint statsTex = 0, statsScratchTex = 0;
    GLuint statsFbo = 0, statsScratchFbo = 0;
    GLuint statsBuf = 0;

    uint32_t statsAllocW = 0, statsAllocH = 0;
    uint64_t statsBytes = 0;

    // The window probe's one-shot latches (GL-thread state; the
    // white-window round-2 debug instrument — see setWindowProbeEnabled's
    // header comment). [probeGeometryLogged] fires the per-attach geometry
    // line once (runSurfaceJob resets it on every attach); [probeW/H]
    // re-arm the pixel line when the develop resolution changes — the
    // session is clip-agnostic (the clip rides each request), so the
    // resolution is the honest per-stream key: one probe line per clip
    // shape per attach, none per frame.
    bool probeGeometryLogged = false;
    uint32_t probeW = 0, probeH = 0;

    // --- Handoff state (guarded by [mutex]) --------------------------------
    std::mutex mutex;
    std::condition_variable cvRequest;
    std::condition_variable cvDone;
    std::thread glThread;
    bool threadRunning = false;
    enum class JobKind { None, Render, Surface };
    // The ONE in-flight job (bounded by construction: both callers wait
    // for the slot to be free, fill it under the mutex, and block on
    // cvDone until the GL thread clears it — at most one job is ever in
    // flight, and waiting callers are contenders, not a queue).
    JobKind pendingKind = JobKind::None;
    const PreviewFrameRequest* pendingReq = nullptr;
    PreviewFrameResult* pendingRes = nullptr;
    ANativeWindow* pendingWindow = nullptr;      // attach: non-null; destroy: null
    bool* pendingSurfaceOk = nullptr;
    std::string* pendingSurfaceDetail = nullptr;
    bool closeRequested = false;
    // Latched RENDER failure (the header's failure semantics; surface
    // failures never touch this).
    bool healthyFlag = true;
    std::string failureDetail;

    // --- GL-thread methods (called only from the GL thread) ----------------
    bool openEglOnGlThread(std::string* detail);
    void releaseEglOnGlThread();
    bool initOnGlThread(const std::string& vertexSource,
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
    void runSurfaceJob(ANativeWindow* window, bool& ok, std::string& detail);
    void runJob(const PreviewFrameRequest& req, PreviewFrameResult& out);
    void teardownOnGlThread();
    // The thread's whole life: serve jobs until close.
    void glThreadLoop();
};
// ---- The session's own EGL ---------------------------------------------------
//
// Mirrors EglSession::open/release (GlesPipeline.cpp — the grafika
// topology) with exactly two preview differences: EGL_SURFACE_TYPE asks
// for EGL_WINDOW_BIT | EGL_PBUFFER_BIT, and the CHOSEN CONFIG IS KEPT
// (eglCreateWindowSurface needs it later; EglSession discards it because
// a pbuffer-only session never creates a window surface).

bool PreviewSession::Impl::openEglOnGlThread(std::string* detail) {
    const char* step = "eglGetDisplay";
    display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display == EGL_NO_DISPLAY) goto fail;
    step = "eglInitialize";
    if (!eglInitialize(display, nullptr, nullptr)) goto fail;
    step = "eglChooseConfig";
    {
        const EGLint configAttribs[] = {
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_NONE,
        };
        EGLint n = 0;
        if (!eglChooseConfig(display, configAttribs, &config, 1, &n) || n < 1) {
            goto fail;
        }
        step = "eglCreateContext";
        const EGLint ctxAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_NONE,
        };
        context = eglCreateContext(display, config, EGL_NO_CONTEXT, ctxAttribs);
        if (context == EGL_NO_CONTEXT) goto fail;
        step = "eglCreatePbufferSurface";
        const EGLint surfAttribs[] = {
            EGL_WIDTH, 1,
            EGL_HEIGHT, 1,
            EGL_NONE,
        };
        pbufferSurface = eglCreatePbufferSurface(display, config, surfAttribs);
        if (pbufferSurface == EGL_NO_SURFACE) goto fail;
    }
    step = "eglMakeCurrent";
    if (!eglMakeCurrent(display, pbufferSurface, pbufferSurface, context)) goto fail;
    return true;
fail:
    if (detail != nullptr) *detail = step;
    return false;
}

void PreviewSession::Impl::releaseEglOnGlThread() {
    // Exact reverse of open (grafika release topology), extended for the
    // window surface. Safe on a partially-opened session: every handle
    // starts as its *_NO_* value.
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (windowSurface != EGL_NO_SURFACE) eglDestroySurface(display, windowSurface);
        if (pbufferSurface != EGL_NO_SURFACE) eglDestroySurface(display, pbufferSurface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglReleaseThread();
        eglTerminate(display);
    }
    windowSurface = EGL_NO_SURFACE;
    pbufferSurface = EGL_NO_SURFACE;
    context = EGL_NO_CONTEXT;
    display = EGL_NO_DISPLAY;
    config = nullptr;
}

// ---- The GL thread's whole life ----------------------------------------------

void PreviewSession::Impl::glThreadLoop() {
    // Entered with the context live (or already failed at init) and the
    // lock NOT held. open() waits for init's outcome before returning;
    // close() joins us. Every EGL/GL call for the session's lifetime —
    // including teardown — happens on THIS thread.
    std::unique_lock<std::mutex> lock(mutex);
    while (true) {
        cvRequest.wait(lock, [&]() {
            return closeRequested || pendingKind != JobKind::None;
        });
        if (closeRequested && pendingKind == JobKind::None) break;

        // Run the job with the lock RELEASED (healthy()/close() must stay
        // live from other threads during the render, and the render can
        // take tens of milliseconds). The pending pointers still point
        // into the blocked caller's frame — safe because that caller is
        // parked on cvDone until we clear the slot below.
        const JobKind kind = pendingKind;
        const PreviewFrameRequest* req = pendingReq;
        PreviewFrameResult* res = pendingRes;
        ANativeWindow* window = pendingWindow;
        bool* surfaceOk = pendingSurfaceOk;
        std::string* surfaceDetail = pendingSurfaceDetail;
        lock.unlock();
        if (kind == JobKind::Surface) {
            runSurfaceJob(window, *surfaceOk, *surfaceDetail);
        } else {
            runJob(*req, *res);
        }
        lock.lock();
        // Publish under the lock AND latch RENDER failures (the header's
        // semantics): a mid-render GL failure makes the session unhealthy
        // — later renderFrame() calls fail fast with the same detail, and
        // the Kotlin gate falls the preview back to CPU with this reason.
        // A SURFACE job's failure is NOT latched (the context is fine —
        // the surface isn't; the UI retries or falls back).
        if (kind == JobKind::Render && !res->ok) {
            healthyFlag = false;
            if (failureDetail.empty()) failureDetail = res->detail;
        }
        pendingKind = JobKind::None;
        pendingReq = nullptr;
        pendingRes = nullptr;
        pendingWindow = nullptr;
        pendingSurfaceOk = nullptr;
        pendingSurfaceDetail = nullptr;
        // notify_ALL, not notify_one: unlike M3's single-caller handoff,
        // TWO threads can be parked on cvDone at once (the engine thread
        // waiting for the slot in renderFrame, the UI thread waiting for
        // the slot in setSurface). notify_one could wake the wrong waiter
        // and leave the other asleep past a now-true predicate (a lost
        // wakeup until the next job — possibly forever). All waiters
        // re-check their predicates; the losers go back to sleep.
        cvDone.notify_all();
        // Loop: drain any close requested while the job ran.
    }
    // Exit path: closeRequested && the slot is free. Tear down the GL
    // state NOW, on this thread, in the lock-free window before close()
    // joins us (releaseEglOnGlThread is idempotent — safe for the Impl's
    // POD members afterwards).
    lock.unlock();
    teardownOnGlThread();
}

// ---- open -------------------------------------------------------------------

std::unique_ptr<PreviewSession> PreviewSession::open(
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
        std::string* detail) {
    std::unique_ptr<PreviewSession> session(new PreviewSession);
    session->impl_ = std::unique_ptr<Impl>(new Impl);
    Impl* impl = session->impl_.get();

    // The GL thread: opens the context + compiles on itself (EGL
    // confinement), reports the outcome through a dedicated init
    // handshake, then serves jobs until close. open() blocks until init
    // reports.
    std::mutex initMutex;
    std::condition_variable initCv;
    bool initReported = false;
    bool initOk = false;
    std::string initDetail;

    // Capture list is explicit on purpose: [impl] and the fifteen sources
    // BY VALUE (a `[&]` would dangle — this thread outlives open()'s frame),
    // the init-handshake locals by reference (safe: each is touched exactly
    // once, before the notify, and open() does not return until the
    // notification arrives).
    impl->glThread = std::thread(
        [impl, vertexSource, vertex310Source, preFragmentSource,
         postFragmentSource, tailFragmentSource, sharpnessHSource,
         sharpnessVSource, detailHSource, detailVSource, warpFragmentSource,
         nrChromaHSource, nrChromaVSource, nrLumaSource, storeFragmentSource,
         windowFragmentSource, &initMutex,
         &initCv, &initReported, &initOk, &initDetail]() {
            std::string d;
            const bool ok = impl->initOnGlThread(
                vertexSource, vertex310Source, preFragmentSource,
                postFragmentSource, tailFragmentSource, sharpnessHSource,
                sharpnessVSource, detailHSource, detailVSource,
                warpFragmentSource, nrChromaHSource, nrChromaVSource,
                nrLumaSource, storeFragmentSource, windowFragmentSource, &d);
            // Report init's outcome to open() FIRST, then serve the loop
            // (or exit it on failure — open() joins the thread below).
            {
                std::lock_guard<std::mutex> lock(initMutex);
                initOk = ok;
                initDetail = d;
                initReported = true;
            }
            initCv.notify_one();
            if (!ok) {
                // Latch the failure so the loop exits without ever serving
                // a job (nothing can be pending — renderFrame/setSurface
                // are unreachable until open returns success) and
                // healthy() reports false with the reason.
                std::lock_guard<std::mutex> lock(impl->mutex);
                impl->closeRequested = true;
                impl->healthyFlag = false;
                impl->failureDetail = d;
            }
            impl->glThreadLoop();
        });

    // Wait for init's outcome (the handshake locals are safe to capture
    // by reference precisely because of this wait).
    {
        std::unique_lock<std::mutex> lock(initMutex);
        initCv.wait(lock, [&]() { return initReported; });
    }
    if (!initOk) {
        if (detail != nullptr) *detail = initDetail;
        // The thread latched closeRequested itself; join it (its teardown
        // already ran inside glThreadLoop's exit path).
        if (impl->glThread.joinable()) impl->glThread.join();
        return nullptr;
    }
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->threadRunning = true;
    }
    return session;
}

bool PreviewSession::Impl::initOnGlThread(
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
        std::string* detail) {
    // --- EGL (THIS context, not the probe's) ---------------------------------
    std::string d;
    if (!openEglOnGlThread(&d)) {
        *detail = "egl: " + d;
        return false;
    }
    // OES_texture_float_linear is a FILTER choice, not a gate: without it
    // RGBA32F is only NEAREST-filterable. Read HERE, in THIS context —
    // never trust a probe's throwaway one. THE NEAREST PIN (owner device
    // evidence 2026-11-15, the white window): the verdict is deliberately
    // NOT fed to the develop texture's filter anymore — the Config below
    // pins NEAREST unconditionally. The failing Adreno ADVERTISES the
    // extension (the probe reads true), yet the WINDOW pass's LINEAR
    // reads of the RGBA32F develop texture painted WHITE frame after
    // frame (771 renders, zero GL errors, healthy stats packs) while the
    // SAME texture through the SAME shader fed draw 3's stats pack real
    // content — the two draws differ ONLY in the destination framebuffer
    // (runJob draw 2: GL_FRAMEBUFFER 0, the window surface; draw 3:
    // statsFbo), and the destination-dependent sampler path is the one
    // leg LINEAR broke. NEAREST is the encoder blit's proven sampling
    // shape. The probe read stays as the honest capability record for
    // the day the pin lifts.
    const GLubyte* e = glGetString(GL_EXTENSIONS);
    const std::string extensions = e != nullptr ? reinterpret_cast<const char*>(e) : "";
    const bool floatLinearAdvertised =
        extensions.find("GL_OES_texture_float_linear") != std::string::npos;
    (void)floatLinearAdvertised;  // recorded above; never fed — the pin

    // --- the shared develop chain, compiled ONCE by the pipeline ------------
    // The pipeline owns the twelve chain+store programs, every chain uniform
    // location, the static texture-unit map, the upload caches, the CPU-side
    // uniform math, the per-grade gating, the once-per-resolution allocation,
    // and the whole draw sequence. This preview passes withStoreScratch=false:
    // its stats store pass runs at the SMALL dims against the session's OWN
    // stats scratch FBO + SSBO, so the pipeline's full-res store scratch is
    // never allocated.
    GpuDevelopPipeline::Config config;
    config.developLinearFilter = false;  // THE NEAREST PIN — see the probe
                                         // comment above; the LINEAR read of
                                         // the RGBA32F develop texture painted
                                         // the window WHITE on the failing
                                         // Adreno (device evidence
                                         // 2026-11-15), NEAREST is the blit's
                                         // proven shape
    config.withStoreScratch = false;  // the stats pack is session-owned
    if (!pipeline.init(vertexSource, vertex310Source, preFragmentSource,
                      postFragmentSource, tailFragmentSource, sharpnessHSource,
                      sharpnessVSource, detailHSource, detailVSource,
                      warpFragmentSource, nrChromaHSource, nrChromaVSource,
                      nrLumaSource, storeFragmentSource, config, detail)) {
        return false;
    }

    // --- the session's OWN window program, compiled ONCE --------------------
    // The pipeline's vertex shader is pipeline-private; the window pass
    // links against this session's own plain 300es vertex compile.
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", detail);
    if (vertShader.id == 0) return false;
    GlShader windowFragShader;
    windowFragShader.id = compileShader(GL_FRAGMENT_SHADER, windowFragmentSource, "window-fragment", detail);
    if (windowFragShader.id == 0) return false;
    windowProgram = linkProgram(vertShader.id, windowFragShader.id, "window", detail);
    if (windowProgram == 0) return false;
    // Program ownership is already in the Impl; the GlShader guards
    // deleting the SHADER objects at scope exit is correct OpenGL — an
    // attached shader stays flagged-alive until its program dies.

    windowSrcLoc = glGetUniformLocation(windowProgram, "uSrc");
    windowSrcSizeLoc = glGetUniformLocation(windowProgram, "uSrcSize");
    windowRectLoc = glGetUniformLocation(windowProgram, "uRect");
    windowOrientationLoc = glGetUniformLocation(windowProgram, "uOrientation");
    if (windowSrcLoc < 0 || windowSrcSizeLoc < 0 || windowRectLoc < 0 ||
        windowOrientationLoc < 0) {
        *detail = "shader-contract: missing a window uniform";
        return false;
    }

    // --- static texture-unit bindings (the unit assignment never changes) --
    // The chain's unit map (0 CFA / 1 pass source / 2 scratch / 3 shading /
    // 4 guide / 5 LUT) is pinned by pipeline.init. The preview pins ONLY its
    // own window program's uSrc: the same pass-source unit — the texture
    // bound there at draw time is the pipeline's develop texture.
    glUseProgram(windowProgram);
    glUniform1i(windowSrcLoc, 1); // window source: GL_TEXTURE1 (the same
                                    // "pass source texture" unit; the
                                    // texture bound there at draw time is
                                    // whichever pass reads it)
    glUseProgram(0);
    return true;
}

// ---- the surface job ------------------------------------------------------------
//
// OWNERSHIP: the caller transferred its ANativeWindow reference at
// setSurface() entry; THIS job is the served path, so the release happens
// here, on the GL thread, on EVERY attach path (success and failure —
// eglCreateWindowSurface acquires the window's own reference on success,
// so ours is redundant; on failure nothing else holds it). A DESTROY
// carries no reference. The fast-refusal paths (closed / unhealthy
// session) release on the CALLING thread instead — see setSurface().

void PreviewSession::Impl::runSurfaceJob(ANativeWindow* window,
                                         bool& ok, std::string& detail) {
    if (window == nullptr) {
        // DESTROY: always processes (even on a latched-unhealthy session —
        // teardown must be able to release the window whatever happened
        // to the render path). Honor the SurfaceHolder contract: by the
        // time this returns, the EGL window surface is gone.
        if (windowSurface != EGL_NO_SURFACE) {
            eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
            eglDestroySurface(display, windowSurface);
            windowSurface = EGL_NO_SURFACE;
        }
        ok = true;
        return;
    }
    // ATTACH (replace semantics when one is already attached).
    eglMakeCurrent(display, pbufferSurface, pbufferSurface, context);
    if (windowSurface != EGL_NO_SURFACE) {
        eglDestroySurface(display, windowSurface);
        windowSurface = EGL_NO_SURFACE;
    }
    EGLSurface created = eglCreateWindowSurface(display, config, window, nullptr);
    if (created == EGL_NO_SURFACE) {
        // NOT latched (the header's rule): the context is fine — the
        // surface isn't; the UI retries or falls back.
        detail = "eglCreateWindowSurface";
        ANativeWindow_release(window);
        ok = false;
        return;
    }
    if (!eglMakeCurrent(display, created, created, context)) {
        eglDestroySurface(display, created);
        detail = "eglMakeCurrent(window)";
        ANativeWindow_release(window);
        ok = false;
        return;
    }
    windowSurface = created;
    // Non-blocking presents: the preview pump is the rate authority (the
    // bounded handoff must not park the engine thread on vsync); the
    // compositor latches the latest frame.
    eglSwapInterval(display, 0);
    // The window probe re-arms on every attach (white-window round 2): a
    // new surface is a new stream, so the first window draw after this
    // attach logs the geometry line and probes the pixel again. The
    // latches are GL-thread state and this runs on the GL thread.
    probeGeometryLogged = false;
    probeW = 0;
    probeH = 0;
    ANativeWindow_release(window);
    ok = true;
}

// ---- the frame job ------------------------------------------------------------

void PreviewSession::Impl::runJob(const PreviewFrameRequest& req,
                                   PreviewFrameResult& out) {
    // The whole chain — the input contract, the CPU-side uniform math, the
    // once-per-resolution allocation, the shading + LUT upload caches, the
    // PRE -> [NR] -> POST -> [unsharp] -> [WARP] -> TAIL draw sequence with
    // the upload/develop span measurement — runs in the shared pipeline
    // (gles/GpuDevelopPipeline.cpp), synchronously on THIS GL thread with
    // this session's context current.
    const GpuDevelopPipeline::DrawResult r = pipeline.draw(req);
    out.uploadUs = r.uploadUs;  // set right after the pipeline's CFA upload —
                               // survives a mid-chain draw failure, 0 only
                               // on the input-contract refusal (the original
                               // shape)
    if (!r.ok) {
        out.detail = r.detail;
        return;
    }
    const uint32_t w = r.width;
    const uint32_t h = r.height;
    out.developW = w;
    out.developH = h;
    out.developUs = r.developUs;
    // The orientation fold's input, normalized exactly like
    // developToArgb's.
    const int orient = ((req.info.orientationDeg % 360) + 360) % 360;
    // The timing spans' clock (the original's function-scope alias,
    // kept at the same scope the kept tail uses it in).
    using SteadyClock = std::chrono::steady_clock;

    // --- draw 2: the WINDOW present (only when a window surface is current);
    // --- BEST-EFFORT — never fails the frame, never latches (the stats pack
    // --- below is the render's contract; a dead/dying surface is the UI's
    // --- lifecycle signal, not a pipeline error) ---------------------------
    const SteadyClock::time_point tPresent0 = SteadyClock::now();
    if (windowSurface != EGL_NO_SURFACE) {
        EGLint winW = 0, winH = 0;
        if (eglQuerySurface(display, windowSurface, EGL_WIDTH, &winW) &&
            eglQuerySurface(display, windowSurface, EGL_HEIGHT, &winH) &&
            winW > 0 && winH > 0) {
            out.surfaceW = static_cast<uint32_t>(winW);
            out.surfaceH = static_cast<uint32_t>(winH);
            // Upright dims (90/270 swap), aspect-fit letterbox rect.
            const uint32_t uprightW = (orient == 90 || orient == 270) ? h : w;
            const uint32_t uprightH = (orient == 90 || orient == 270) ? w : h;
            const float scale = std::fmin(
                static_cast<float>(winW) / static_cast<float>(uprightW),
                static_cast<float>(winH) / static_cast<float>(uprightH));
            const float rectW = static_cast<float>(uprightW) * scale;
            const float rectH = static_cast<float>(uprightH) * scale;
            const float rectX = (static_cast<float>(winW) - rectW) * 0.5f;
            const float rectY = (static_cast<float>(winH) - rectH) * 0.5f;
            glUseProgram(windowProgram);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, pipeline.developTex());
            glBindFramebuffer(GL_FRAMEBUFFER, 0);  // the window surface
            glViewport(0, 0, winW, winH);
            glUniform2i(windowSrcSizeLoc,
                        static_cast<GLint>(w), static_cast<GLint>(h));
            glUniform4f(windowRectLoc, rectX, rectY, rectW, rectH);
            glUniform1i(windowOrientationLoc, orient);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            // The draw's own error check runs FIRST — the probe below
            // drains GL errors (its own), and a drain before this check
            // would swallow a draw error and fake drawOk.
            const bool drawOk =
                glErrorAfter("glDrawArrays(window)", &out.detail) == GL_NO_ERROR;
            // THE WINDOW PROBE (white-window round 2, DEBUG-only — the
            // pragmatic one-pixel exception to this path's no-readback
            // rule; see setWindowProbeEnabled's header comment). ONE
            // glReadPixels 1x1 at the rect CENTER, from the BACK buffer
            // BEFORE eglSwapBuffers (post-swap the back buffer is
            // undefined — the brief's own correction). One line per
            // attach per develop resolution fires; the latches keep it
            // one-shot so a playback run logs nothing per frame.
            if (windowProbeEnabled() &&
                (!probeGeometryLogged || probeW != w || probeH != h)) {
                // The geometry line, once per attach: the pass's own
                // values — the claimed uRect, the viewport, the surface's
                // reported geometry and its dots-per-inch pair.
                if (!probeGeometryLogged) {
                    EGLint dotsH = 0, dotsV = 0;
                    eglQuerySurface(display, windowSurface,
                                    EGL_HORIZONTAL_RESOLUTION, &dotsH);
                    eglQuerySurface(display, windowSurface,
                                    EGL_VERTICAL_RESOLUTION, &dotsV);
                    __android_log_print(
                        ANDROID_LOG_INFO, "ritim-trace",
                        "window probe: attach win=%dx%d dots=(%d,%d)",
                        static_cast<int>(winW), static_cast<int>(winH),
                        static_cast<int>(dotsH), static_cast<int>(dotsV));
                    probeGeometryLogged = true;
                }
                // The pixel line, once per develop resolution: the draw's
                // own inputs ride the line (rect/srcSize/orientation) so a
                // white draw's uniforms can be diffed against a healthy
                // clip's without a second build. glReadPixels reads the
                // CURRENT read surface = the window surface's back
                // buffer; the read is centered in uRect so the letterbox
                // arm never covers it. The probe swallows its own GL
                // error — the draw's error check above stays the only
                // error the frame reports.
                const GLint probeX = static_cast<GLint>(rectX + rectW * 0.5f);
                const GLint probeY = static_cast<GLint>(rectY + rectH * 0.5f);
                GLubyte probePx[4] = {0, 0, 0, 0};
                glReadPixels(probeX, probeY, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE,
                             probePx);
                while (glGetError() != GL_NO_ERROR) {}  // swallow the probe's own
                __android_log_print(
                    ANDROID_LOG_INFO, "ritim-trace",
                    "window probe: rgb=(%d,%d,%d) win=%dx%d vp=%dx%d "
                    "rect=(%.1f,%.1f,%.1f,%.1f) src=%ux%u orient=%d",
                    static_cast<int>(probePx[0]), static_cast<int>(probePx[1]),
                    static_cast<int>(probePx[2]),
                    static_cast<int>(winW), static_cast<int>(winH),
                    static_cast<int>(winW), static_cast<int>(winH),
                    static_cast<double>(rectX), static_cast<double>(rectY),
                    static_cast<double>(rectW), static_cast<double>(rectH),
                    static_cast<unsigned>(w), static_cast<unsigned>(h),
                    orient);
                probeW = w;
                probeH = h;
            }
            // The swap comes AFTER the probe — the probe reads the back
            // buffer pre-swap (post-swap it is undefined, the brief's own
            // correction).
            const bool swapOk = eglSwapBuffers(display, windowSurface) == EGL_TRUE;
            out.presented = drawOk && swapOk;
            if (!swapOk && out.detail.empty()) {
                out.detail = "window present: eglSwapBuffers";
            }
        } else if (out.detail.empty()) {
            out.detail = "window present: eglQuerySurface";
        }
    } else {
        out.detail = "window present: no attached surface";
    }
    const SteadyClock::time_point tPresent1 = SteadyClock::now();
    out.presentUs = elapsedUs(tPresent0, tPresent1);

    // --- draw 3: the stats pack — ALWAYS (the published frame the Compose
    // --- overlays consume). ~256 long edge, upright via the same fold. ------
    // --- THE MEASUREMENT TOGGLE (trace-dims fix 2026-11-14): statsEnabled
    // --- false skips draw 3 + the readback ENTIRELY — the honest 1x1 empty
    // --- fallback publishes instead (the zebra/scopes consumers degrade for
    // --- the run; the trace line reads stats=off), and the stats span stays
    // --- 0. The dims stay valid so the publish fold's crop math survives.
    if (!req.statsEnabled) {
        out.argb8888.assign(1, 0xFF000000u);
        out.width = 1;
        out.height = 1;
        out.ok = true;
        return;
    }
    const uint32_t uprightW = (orient == 90 || orient == 270) ? h : w;
    const uint32_t uprightH = (orient == 90 || orient == 270) ? w : h;
    const uint32_t longEdge = uprightW > uprightH ? uprightW : uprightH;
    uint32_t statsW = uprightW;
    uint32_t statsH = uprightH;
    if (longEdge > kStatsLongEdge) {
        const float scale =
            static_cast<float>(kStatsLongEdge) / static_cast<float>(longEdge);
        statsW = uprightW > 0
            ? std::max<uint32_t>(1, static_cast<uint32_t>(uprightW * scale + 0.5f))
            : 1;
        statsH = uprightH > 0
            ? std::max<uint32_t>(1, static_cast<uint32_t>(uprightH * scale + 0.5f))
            : 1;
    }

    // The stats span covers the WHOLE draw-3 stage: the once-per-dims
    // allocation branch (a tier change pays it on its first frame — the
    // honest cost for that frame), the window pass into the stats FBO, the
    // store pass, and the mapped-SSBO readback wait.
    const SteadyClock::time_point tStats0 = SteadyClock::now();
    if (statsAllocW != statsW || statsAllocH != statsH) {
        if (statsTex != 0) { glDeleteTextures(1, &statsTex); statsTex = 0; }
        if (statsScratchTex != 0) { glDeleteTextures(1, &statsScratchTex); statsScratchTex = 0; }
        if (statsFbo != 0) { glDeleteFramebuffers(1, &statsFbo); statsFbo = 0; }
        if (statsScratchFbo != 0) { glDeleteFramebuffers(1, &statsScratchFbo); statsScratchFbo = 0; }
        if (statsBuf != 0) { glDeleteBuffers(1, &statsBuf); statsBuf = 0; }
        statsBytes = 0;
        statsAllocW = 0; statsAllocH = 0;

        // The window pass's stats target: RGBA8 at the stats dims.
        glGenTextures(1, &statsTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, statsTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8,
                       static_cast<GLsizei>(statsW), static_cast<GLsizei>(statsH));
        if (glErrorAfter("glTexStorage2D(RGBA8 stats)", &out.detail) != GL_NO_ERROR) return;
        glGenFramebuffers(1, &statsFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, statsFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               statsTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "stats fbo incomplete";
            return;
        }

        // The store pass's throwaway color target (NEVER the stats FBO,
        // whose texture is the source: a feedback loop). Its contents are
        // never read; the SSBO is the real output.
        glGenTextures(1, &statsScratchTex);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, statsScratchTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8,
                       static_cast<GLsizei>(statsW), static_cast<GLsizei>(statsH));
        if (glErrorAfter("glTexStorage2D(RGBA8 statsScratch)", &out.detail) != GL_NO_ERROR) return;
        glGenFramebuffers(1, &statsScratchFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, statsScratchFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               statsScratchTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "stats store fbo incomplete";
            return;
        }

        // The small SSBO: statsW*statsH*3 floats (a few hundred KB at
        // 256x144 — NEVER the frame-sized buffer; the audit's
        // no-full-frame-readback rule).
        const uint64_t bytes =
            static_cast<uint64_t>(statsW) * statsH * 3 * sizeof(float);
        glGenBuffers(1, &statsBuf);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, statsBuf);
        glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(bytes),
                     nullptr, GL_STREAM_READ);
        if (glErrorAfter("glBufferData(stats)", &out.detail) != GL_NO_ERROR) return;
        statsBytes = bytes;
        statsAllocW = statsW;
        statsAllocH = statsH;
    }

    // The window pass into the stats FBO: the SAME aspect-fit fold at the
    // stats dims (uRect covers the whole target — no letterbox bars; the
    // orientation fold makes it upright).
    glUseProgram(windowProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, pipeline.developTex());
    glBindFramebuffer(GL_FRAMEBUFFER, statsFbo);
    glViewport(0, 0, static_cast<GLsizei>(statsW), static_cast<GLsizei>(statsH));
    glUniform2i(windowSrcSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform4f(windowRectLoc, 0.0f, 0.0f,
                static_cast<GLfloat>(statsW), static_cast<GLfloat>(statsH));
    glUniform1i(windowOrientationLoc, orient);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(stats window)", &out.detail) != GL_NO_ERROR) return;

    // The store pass: statsTex -> SSBO (the same mapped-SSBO readback the
    // export session uses, at the SMALL dims only).
    glUseProgram(pipeline.storeProgram());
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, statsTex);
    glBindFramebuffer(GL_FRAMEBUFFER, statsScratchFbo);
    glViewport(0, 0, static_cast<GLsizei>(statsW), static_cast<GLsizei>(statsH));
    glUniform2i(pipeline.storeSizeLoc(),
                static_cast<GLint>(statsW), static_cast<GLint>(statsH));
    // Re-bind the SSBO to indexed binding 0 before EVERY store draw (the
    // M3 rule: a persistent session must not rely on the allocation
    // branch's binding surviving unrelated state changes).
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, statsBuf);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, statsBuf);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(stats store)", &out.detail) != GL_NO_ERROR) return;

    // --- readback: a MAPPED buffer at the SMALL dims, NEVER glReadPixels ---
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, statsBuf);
    void* mapped = glMapBufferRange(
        GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(statsBytes),
        GL_MAP_READ_BIT);
    if (mapped == nullptr) {
        glErrorAfter("glMapBufferRange", &out.detail);
        if (out.detail.empty()) out.detail = "glMapBufferRange returned null";
        return;
    }
    const float* gpu = static_cast<const float*>(mapped);
    // ROW-FLIP PACK. The stats FBO's texture holds the upright image with
    // row 0 = upright BOTTOM (gl_FragCoord counts rows bottom-up); the
    // store pass reads and writes DIRECT (no flip either side), so SSBO
    // row 0 = upright bottom. The pack flips to the CPU convention the
    // published frame's consumers expect (developToArgb's shape): out
    // row 0 = upright TOP:
    //   out[y][x] = ssbo[(statsH-1-y)*statsW + x]
    // with the SAME 0xFF000000|(r<<16)|(g<<8)|b pixel pack and the same
    // toByte quantize (the shader already applied linearToRec709 —
    // uOetf=1 — so this is the ONLY CPU-side per-pixel work).
    out.argb8888.assign(static_cast<size_t>(statsW) * statsH, 0xFF000000u);
    for (uint32_t y = 0; y < statsH; ++y) {
        const uint32_t srcY = statsH - 1 - y;
        const float* row = gpu + static_cast<size_t>(srcY) * statsW * 3;
        uint32_t* dst = out.argb8888.data() + static_cast<size_t>(y) * statsW;
        for (uint32_t x = 0; x < statsW; ++x) {
            const uint32_t r = toByte(row[x * 3 + 0]);
            const uint32_t g = toByte(row[x * 3 + 1]);
            const uint32_t b = toByte(row[x * 3 + 2]);
            dst[x] = 0xFF000000u | (r << 16) | (g << 8) | b;
        }
    }
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    const SteadyClock::time_point tStats1 = SteadyClock::now();
    out.statsUs = elapsedUs(tStats0, tStats1);
    out.width = statsW;
    out.height = statsH;
    out.ok = true;
}

// ---- renderFrame / setSurface / close / health ---------------------------------

// GRADE PARCEL (commit a + b): the preview session's ONLY render entry —
// the grade arrives as the decoded parcel struct, folded onto a stack copy
// of the request via the GENERATED applyGradeToRequest (gles/GradeParcel.h
// — the schema's single source of truth; a schema field with no matching
// PreviewFrameRequest member fails the compile here, the honest catch).
// The [req] the caller builds carries ONLY the non-grade fields
// (cfa/info/meta, the LUT binding group, the warp pair); the temporal pair
// never reaches the request — the bridge consumed it at temporalMergedCfa
// before this call. The flat pre-folded overload died with commit (b):
// every door into the session threads the grade through the parcel.
PreviewFrameResult PreviewSession::renderFrame(
        const PreviewFrameRequest& req, const DevelopGrade& grade) {
    PreviewFrameRequest folded = req;
    applyGradeToRequest(grade, &folded);
    PreviewFrameResult res;
    if (impl_ == nullptr) {
        res.detail = "session not open";
        return res;
    }
    std::unique_lock<std::mutex> lock(impl_->mutex);
    if (!impl_->threadRunning) {
        res.detail = "session closed";
        return res;
    }
    if (!impl_->healthyFlag) {
        res.detail = impl_->failureDetail.empty()
            ? std::string("session unhealthy") : impl_->failureDetail;
        return res;
    }
    // Wait for the single slot (a surface job may be in flight — the
    // UI thread's rare lifecycle event). Contenders WAIT, they do not
    // queue: the bound stays "at most one job in flight".
    impl_->cvDone.wait(lock, [&]() {
        return impl_->pendingKind == Impl::JobKind::None || !impl_->threadRunning;
    });
    if (!impl_->threadRunning) {
        res.detail = "session closed";
        return res;
    }
    // Post under the lock: atomic with the threadRunning check above, so
    // a concurrent close() either sees the job (and the GL thread serves
    // it before exiting — its exit condition requires the slot free) or
    // we saw it first and failed above. No posted job is ever abandoned.
    impl_->pendingKind = Impl::JobKind::Render;
    impl_->pendingReq = &folded;
    impl_->pendingRes = &res;
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() {
        return impl_->pendingKind == Impl::JobKind::None || !impl_->threadRunning;
    });
    // [res] was filled by the GL thread while we held the wait; the
    // unique_lock releases on return.
    return res;
}

bool PreviewSession::setSurface(ANativeWindow* window, std::string* detail) {
    if (impl_ == nullptr) {
        // Ownership was transferred at entry: release it HERE (the only
        // paths where the session releases on the CALLING thread — the
        // GL thread never sees the window).
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "session not open";
        return false;
    }
    std::unique_lock<std::mutex> lock(impl_->mutex);
    if (!impl_->threadRunning) {
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "session closed";
        return false;
    }
    // An ATTACH on an unhealthy session fails fast with the latched
    // detail; a DESTROY always proceeds (teardown must be able to
    // release the window whatever happened to the render path).
    if (window != nullptr && !impl_->healthyFlag) {
        ANativeWindow_release(window);
        if (detail != nullptr) {
            *detail = impl_->failureDetail.empty()
                ? std::string("session unhealthy") : impl_->failureDetail;
        }
        return false;
    }
    // Wait for the single slot (a render may be in flight).
    impl_->cvDone.wait(lock, [&]() {
        return impl_->pendingKind == Impl::JobKind::None || !impl_->threadRunning;
    });
    if (!impl_->threadRunning) {
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "session closed";
        return false;
    }
    bool ok = false;
    std::string surfaceDetail;
    impl_->pendingKind = Impl::JobKind::Surface;
    impl_->pendingWindow = window;
    impl_->pendingSurfaceOk = &ok;
    impl_->pendingSurfaceDetail = &surfaceDetail;
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() {
        return impl_->pendingKind == Impl::JobKind::None || !impl_->threadRunning;
    });
    if (!ok && detail != nullptr) *detail = surfaceDetail;
    return ok;
}

void PreviewSession::close() {
    if (impl_ == nullptr) return;
    std::thread joiner;
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->threadRunning) return;  // already closed (idempotent)
        impl_->closeRequested = true;
        impl_->cvRequest.notify_one();
        // Wake every cvDone waiter too: their predicates include
        // !threadRunning, which just flipped — without this notify a
        // caller parked waiting for the job slot would sleep past the
        // flip and hang (the GL thread never notifies cvDone on its exit
        // path; it only notifies after serving a job).
        impl_->cvDone.notify_all();
        // Move the thread handle out so we can join WITHOUT the mutex
        // (joining while holding it would deadlock: the loop's lock
        // acquisition would block forever).
        joiner = std::move(impl_->glThread);
        impl_->threadRunning = false;
    }
    if (joiner.joinable()) joiner.join();
}

PreviewSession::~PreviewSession() {
    close();
    // impl_'s destruction now frees the PODs; every GL object was deleted
    // on the GL thread in teardownOnGlThread, and releaseEglOnGlThread is
    // idempotent with NO_* handles.
}

bool PreviewSession::healthy() const {
    if (impl_ == nullptr) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->healthyFlag;
}

std::string PreviewSession::failureDetail() const {
    // By value (the header's contract): the GL thread may latch a new
    // detail under the mutex while a caller held a returned reference.
    if (impl_ == nullptr) return std::string();
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->failureDetail;
}
void PreviewSession::Impl::teardownOnGlThread() {
    // Called from the GL thread's exit path (glThreadLoop); the reverse of
    // creation. Safe on a partially-initialized session: every handle
    // starts at 0/NO_*.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    // The chain's own objects (the twelve programs, the CFA/shading/LUT/
    // develop textures, the texA/texB/texC + fboOf[] intermediates — the
    // pipeline's established order) are pipeline-owned: the pipeline
    // deletes them, on this same GL thread with this session's context
    // still current.
    pipeline.teardown();
    if (statsScratchFbo != 0) { glDeleteFramebuffers(1, &statsScratchFbo); statsScratchFbo = 0; }
    if (statsFbo != 0) { glDeleteFramebuffers(1, &statsFbo); statsFbo = 0; }
    if (statsScratchTex != 0) { glDeleteTextures(1, &statsScratchTex); statsScratchTex = 0; }
    if (statsTex != 0) { glDeleteTextures(1, &statsTex); statsTex = 0; }
    if (statsBuf != 0) { glDeleteBuffers(1, &statsBuf); statsBuf = 0; }
    if (windowProgram != 0) { glDeleteProgram(windowProgram); windowProgram = 0; }
    releaseEglOnGlThread();
}

} // namespace gles
