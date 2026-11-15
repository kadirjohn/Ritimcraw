// Production GPU develop session (M3 commit a). See GlesDevelopSession.h
// for the lifecycle/threading/color contracts. This translation unit is
// Android-only (EGL/GLES headers exist only in the NDK; the desktop build
// never compiles it — host-side coverage lives in the Kotlin shader-source
// validator + the parity decision rules and their JVM tests).
//
// THE DEVELOP CHAIN LIVES IN THE SHARED PIPELINE (gles/GpuDevelopPipeline):
// the session owns ONE pipeline instance, initialized on this session's GL
// thread with this session's context current, and developPass() delegates
// the whole chain (capability gates, chain+store programs, uniform
// locations, textures/FBOs, the LUT revision cache, the once-per-resolution
// allocation, the PRE -> [NR] -> POST -> [unsharp] -> [WARP] -> TAIL draw
// sequence with the upload/develop span measurement) to
// pipeline.draw(). The chain body's provenance contract (the
// DEVICE-PROVEN harness rules it keeps, the texture-unit map, the
// transpose=GL_TRUE row-major uploads, the per-grade gating) is documented
// in GpuDevelopPipeline.{h,cpp} — it lives there and nowhere else.
//
// WHAT THIS TU KEEPS: the session's own EGL context (EglSession, opened
// window-capable + recordable for the M5 encoder surface), the dedicated
// GL thread with its blocking (bounded one-job) handoff and latched
// failure semantics, the session's own blit program (the SAME
// BLIT_FRAGMENT the Surface-mode writer proved, linked against the
// session's own plain-300es vertex compile — the pipeline's vertex shader
// is pipeline-private), the store/SSBO readback tail (drawn through the
// pipeline's store program + its locations + the withStoreScratch pair,
// exposed by the header's accessors), the encoder blit tail, and the
// toByte quantize of the mapped readback (glReadPixels is used NOWHERE,
// the recorded baseline invariant — the shader applied the OETF, so the
// quantize is the ONLY CPU-side per-pixel work on the readback).

#include "GlesDevelopSession.h"

#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <utility>

// ANativeWindow_release: the surface job releases the transferred window
// reference on the GL thread (the header's ownership contract).
#include <android/native_window.h>

#include <GLES3/gl31.h>

#include "GlesPipeline.h"

namespace gles {
namespace {

// The chain's own documented copies (parseGlesVersion, cellFor, the Mat3
// helpers + buildCameraToRec709, floatToHalfBits, bindLutDummyTex, the LUT
// pins) moved WITH the chain into GpuDevelopPipeline.cpp's anonymous
// namespace (the same keep-in-sync rules; the pipeline TU is now the
// sessions' single carrier of the chain's CPU-side companions). What
// remains here is only what the session's own tails use: the GL compile
// helpers for the session's blit program and the toByte quantize of the
// mapped readback.

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
// text). Returns the GL name on success and writes a compile-failure
// detail into [detail]; returns 0 on failure (no GL object leaks: the
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

// Link [vert]+[frag] into a program, writing a link-failure detail into
// [detail] on failure. Returns 0 on failure (the caller wraps the name).
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
// ONLY CPU-side per-pixel work on the readback.
inline uint8_t toByte(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}

} // namespace

// ---- Impl -----------------------------------------------------------------

struct DevelopSession::Impl {
    // --- GL-thread-owned state (touched ONLY on the GL thread; the mutex
    // --- guards only the handoff/latch fields below) ----------------------
    EglSession egl;
    // The shared develop chain (gles/GpuDevelopPipeline): ONE pipeline
    // instance owned per session, initialized/drawn/torn down on THIS GL
    // thread with this session's context current. It owns the chain+store
    // programs, their uniform locations, the chain textures/FBOs, the LUT
    // revision cache, the once-per-resolution allocation and the whole
    // PRE -> [NR] -> POST -> [unsharp] -> [WARP] -> TAIL draw sequence —
    // the chain body lives there and nowhere else.
    GpuDevelopPipeline pipeline;
    // The session's own blit program (M5): the SAME BLIT_FRAGMENT the
    // Surface-mode writer proved, linked against the session's OWN plain
    // 300es vertex compile (the pipeline's vertex shader is
    // pipeline-private). Copies the developed texture to the encoder
    // window surface with the Y flip the writer's path established.
    GLint blitSrcLoc = -1, blitSizeLoc = -1, blitRotLoc = -1;
    GLuint blitProgram = 0;
    // The encoder window surface (M5). EGL_NO_SURFACE until a successful
    // setEncoderSurface attach; destroyed on the GL thread (in
    // runSurfaceJob, on detach, and in teardown). GL-thread-owned.
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    // eglPresentationTimeANDROID (EGL_ANDROID_presentation_time), resolved
    // ONCE at init via eglGetProcAddress. Null on a device without the
    // extension — the encoder render then refuses honestly with that
    // reason named (never a crash, never a wall-clock stamp).
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;

    // --- Handoff state (guarded by [mutex]) --------------------------------
    // Job kinds (the M4 GlesPreviewSession shape, extended for M5):
    //   Render   — a readback render (runJob); failures LATCH (mid-render
    //              GL failure = context event).
    //   Surface  — an encoder-surface attach/detach (runSurfaceJob);
    //              failures NEVER latch (the context is fine — the window
    //              isn't). A detach (null window) always processes, even
    //              on a latched-unhealthy session, so teardown paths can
    //              release the surface.
    //   Encoder   — a render-to-encoder (runEncoderJob); failures LATCH
    //              (same mid-render semantics as Render).
    enum class JobKind { None, Render, Surface, Encoder };
    std::mutex mutex;
    std::condition_variable cvRequest;
    std::condition_variable cvDone;
    std::thread glThread;
    bool threadRunning = false;
    // The one in-flight job (bounded by construction: every submitter
    // blocks on cvDone until the GL thread clears [pendingKind], so at
    // most one request is ever in flight — regardless of kind).
    JobKind pendingKind = JobKind::None;
    const DevelopFrameRequest* pendingReq = nullptr;
    DevelopFrameResult* pendingRes = nullptr;
    int64_t pendingPtsUs = 0;
    int pendingRotationDeg = 0;
    // The window to attach (or null to detach) — borrowed for the job's
    // duration only; ownership transferred at setEncoderSurface entry.
    ANativeWindow* pendingWindow = nullptr;
    bool pendingSurfaceOk = false;
    std::string pendingSurfaceDetail;
    bool closeRequested = false;
    // Latched failure (the header's failure semantics): set on the GL
    // thread, read through the mutex (a failed session never recovers —
    // the dispatcher falls the export back to CPU with this reason).
    bool healthyFlag = true;
    std::string failureDetail;

    // --- GL-thread methods (called only from the GL thread) ----------------
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
                        const std::string& blitFragmentSource,
                        std::string* detail);
    void runJob(const DevelopFrameRequest& req, DevelopFrameResult& out);
    // The shared develop pass — the pipeline.draw() delegate above; the
    // part runJob and runEncoderJob share verbatim. False with out.detail
    // set on failure.
    bool developPass(const DevelopFrameRequest& req, DevelopFrameResult& out);
    void runSurfaceJob(ANativeWindow* window, bool* ok, std::string* detail);
    void runEncoderJob(const DevelopFrameRequest& req, int64_t ptsUs,
                       int rotationDeg, DevelopFrameResult& out);
    void teardownOnGlThread();
    // The thread's whole life: serve jobs until close (entered on the GL
    // thread itself, right after initOnGlThread reports).
    void glThreadLoop();
};

// ---- The GL thread's whole life ----------------------------------------------

void DevelopSession::Impl::glThreadLoop() {
    // Entered with the context live (or already failed at init) and the
    // lock NOT held. open() waits for init's outcome before returning;
    // close() joins us. Every EGL/GL call for the session's lifetime —
    // including teardown — happens on THIS thread (EGL contexts are not
    // portable across threads; the same rule GlesPipeline.h documents for
    // the harness).
    std::unique_lock<std::mutex> lock(mutex);
    while (true) {
        cvRequest.wait(lock, [&]() { return closeRequested || pendingKind != JobKind::None; });
        if (closeRequested && pendingKind == JobKind::None) break;

        // Run the job with the lock RELEASED (healthy()/close() must stay
        // live from other threads during the render, and the render can
        // take hundreds of milliseconds). The pending* fields still point
        // at the caller's buffers — safe because every submitter is
        // BLOCKED on cvDone until we clear [pendingKind] below, so nothing
        // touches them while the lock is open.
        const JobKind kind = pendingKind;
        const DevelopFrameRequest* req = pendingReq;
        DevelopFrameResult* out = pendingRes;
        const int64_t ptsUs = pendingPtsUs;
        const int rotationDeg = pendingRotationDeg;
        ANativeWindow* window = pendingWindow;
        bool* surfaceOk = &pendingSurfaceOk;
        std::string* surfaceDetail = &pendingSurfaceDetail;
        lock.unlock();
        if (kind == JobKind::Surface) {
            // An attach/detach job. NEVER latches (see the Impl comment):
            // the outcome lands in pendingSurfaceOk/pendingSurfaceDetail.
            runSurfaceJob(window, surfaceOk, surfaceDetail);
        } else if (kind == JobKind::Encoder) {
            runEncoderJob(*req, ptsUs, rotationDeg, *out);
        } else {
            runJob(*req, *out);
        }
        lock.lock();
        // Publish under the lock AND latch RENDER/ENCODER failures (the
        // header's semantics): a mid-render GL failure or an
        // input-contract failure makes the session unhealthy — later
        // renderFrame calls fail fast with the same detail instead of
        // retrying a broken context, and the Kotlin dispatcher falls the
        // export back to CPU with this reason. A SURFACE job's failure is
        // NOT latched (the context is fine — the window isn't).
        if ((kind == JobKind::Render || kind == JobKind::Encoder) && !out->ok) {
            healthyFlag = false;
            if (failureDetail.empty()) failureDetail = out->detail;
        }
        pendingKind = JobKind::None;
        pendingReq = nullptr;
        pendingRes = nullptr;
        pendingPtsUs = 0;
        pendingRotationDeg = 0;
        pendingWindow = nullptr;
        // notify_all: TWO different threads can be parked on cvDone (the
        // engine thread in renderFrame/renderFrameToEncoder, the exporter
        // thread in setEncoderSurface). A notify_one could wake the wrong
        // waiter, which would re-sleep on the predicate and leave the
        // actual waiter stuck until the next spurious wake.
        cvDone.notify_all();
        // Loop: drain any close requested while the job ran.
    }
    // Exit path: closeRequested && no pending job. Tear down the GL state
    // NOW, on this thread, in the lock-free window before close() joins
    // us. teardownOnGlThread releases the EGL context too, so the Impl's
    // egl member's own destructor later finds NO_* handles
    // (EglSession::release is idempotent — safe on a partially-opened
    // session, and safe when the destructor later runs on another thread
    // because the handles are already NO_* and the guard skips all).
    lock.unlock();
    teardownOnGlThread();
}

// ---- open -------------------------------------------------------------------

std::unique_ptr<DevelopSession> DevelopSession::open(
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
        const std::string& blitFragmentSource,
        std::string* detail) {
    std::unique_ptr<DevelopSession> session(new DevelopSession);
    session->impl_ = std::unique_ptr<Impl>(new Impl);
    Impl* impl = session->impl_.get();

    // The GL thread: opens the context + compiles on itself (EGL
    // confinement), reports the outcome through a dedicated init handshake,
    // then serves jobs until close. open() blocks until init reports.
    std::mutex initMutex;
    std::condition_variable initCv;
    bool initReported = false;
    bool initOk = false;
    std::string initDetail;

    // Capture list is explicit on purpose: [impl] and the fifteen sources
    // BY VALUE (a `[&]` would dangle — this thread outlives open()'s frame),
    // the init-handshake locals by reference (safe: each is touched
    // exactly once, before the notify, and open() does not return until
    // the notification arrives).
    impl->glThread = std::thread(
        [impl, vertexSource, vertex310Source, preFragmentSource,
         postFragmentSource, tailFragmentSource, sharpnessHSource,
         sharpnessVSource, detailHSource, detailVSource, warpFragmentSource,
         nrChromaHSource, nrChromaVSource, nrLumaSource, storeFragmentSource,
         blitFragmentSource, &initMutex,
         &initCv, &initReported, &initOk, &initDetail]() {
            std::string d;
            const bool ok = impl->initOnGlThread(
                vertexSource, vertex310Source, preFragmentSource,
                postFragmentSource, tailFragmentSource, sharpnessHSource,
                sharpnessVSource, detailHSource, detailVSource,
                warpFragmentSource,
                nrChromaHSource, nrChromaVSource,
                nrLumaSource, storeFragmentSource, blitFragmentSource, &d);
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
                // Latch the failure (the header's semantics) so the loop
                // exits without ever serving a job (nothing can be
                // pending — renderFrame is unreachable until open returns
                // success) and healthy() reports false with the reason.
                std::lock_guard<std::mutex> lock(impl->mutex);
                impl->closeRequested = true;
                impl->healthyFlag = false;
                impl->failureDetail = d;
            }
            impl->glThreadLoop();
        });

    // Wait for init's outcome. The handshake locals are safe to capture by
    // reference precisely because of this wait: open() cannot leave this
    // frame until initReported flips, and the thread touches each local
    // exactly once before that flip.
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
        // ~unique_ptr<DevelopSession> NOT reached here — the session is
        // returned null; the local [session] destroys with the joined
        // thread member and the released EGL handles. Nothing leaks.
    }
    {
        std::lock_guard<std::mutex> lock(impl->mutex);
        impl->threadRunning = true;
    }
    return session;
}

bool DevelopSession::Impl::initOnGlThread(
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
        const std::string& blitFragmentSource,
        std::string* detail) {
    // --- EGL + capability gates (THIS context, not the probe's) ------------
    std::string d;
    // windowCapable + recordable: the session's config must be able to
    // wrap a MediaCodec input Surface later (M5) — the SAME config is
    // handed to eglCreateWindowSurface in runSurfaceJob. The pbuffer stays
    // the session's own current surface; the recordable flag is what the
    // codec's BufferQueue consumer needs from the producer config
    // (EGL_ANDROID_recordable).
    if (!egl.open(&d, /*windowCapable=*/true, /*recordable=*/true)) {
        *detail = "egl: " + d;
        return false;
    }
    // The ES 3.1 + EXT_color_buffer_float gates now live INSIDE the
    // pipeline's init (the same messages, the same order — the chain owns
    // them because they gate the chain's own renderability, and both
    // sessions get the identical refusal through one door).
    GpuDevelopPipeline::Config config;
    config.developLinearFilter = false;  // NEAREST: the store draw + blit
                                         // are 1:1, and the develop pass's
                                         // own texel fetches are exact
    config.withStoreScratch = true;      // the readback path's scratch FBO +
                                         // SSBO pair
    if (!pipeline.init(vertexSource,
                       vertex310Source,
                       preFragmentSource,
                       postFragmentSource,
                       tailFragmentSource,
                       sharpnessHSource,
                       sharpnessVSource,
                       detailHSource,
                       detailVSource,
                       warpFragmentSource,
                       nrChromaHSource,
                       nrChromaVSource,
                       nrLumaSource,
                       storeFragmentSource,
                       config,
                       detail)) {
        return false;
    }

    // --- the session's OWN blit program (M5) ---------------------------------
    // The SAME BLIT_FRAGMENT the Surface-mode writer proved on device,
    // linked against the session's OWN plain-300es fullscreen vertex
    // compile (the pipeline's vertex shader object is pipeline-private —
    // it compiles its own; this TU compiles its own). It copies the
    // developed texture to the encoder window surface with the Y flip the
    // writer's path established (the develop pass's texel row 0 is the
    // image top; the window surface presents row 0 at the bottom).
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", detail);
    if (vertShader.id == 0) return false;
    GlShader blitFragShader;
    blitFragShader.id = compileShader(GL_FRAGMENT_SHADER, blitFragmentSource,
                                      "blit-fragment", detail);
    if (blitFragShader.id == 0) return false;
    blitProgram = linkProgram(vertShader.id, blitFragShader.id, "blit", detail);
    if (blitProgram == 0) return false;
    // The GlShader guards deleting the SHADER objects at scope exit is
    // correct OpenGL — an attached shader stays flagged-alive until its
    // program dies.
    blitSrcLoc = glGetUniformLocation(blitProgram, "uSrc");
    blitSizeLoc = glGetUniformLocation(blitProgram, "uSize");
    blitRotLoc = glGetUniformLocation(blitProgram, "uRotation");
    if (blitSrcLoc < 0 || blitSizeLoc < 0 || blitRotLoc < 0) {
        *detail = "shader-contract: missing blit uSrc/uSize/uRotation";
        return false;
    }

    // --- presentation-time extension (M5) ------------------------------------
    // eglPresentationTimeANDROID is an EXTENSION: resolved dynamically, and
    // its ABSENCE is legal (older devices). Null is not an init failure —
    // the readback path never needs it; the encoder path refuses honestly
    // with this named reason instead (never a crash, never a wall-clock
    // stamp pretending to be media time).
    presentationTime = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
        eglGetProcAddress("eglPresentationTimeANDROID"));

    // --- the blit's static texture-unit binding ------------------------------
    // The chain's own unit map (0 = CFA, 1 = pass source, 2 = scratch,
    // 3 = shading, 4 = guide, 5 = LUT) is pinned inside the pipeline's
    // init. The blit rides the SAME unit 1 the store pass reads the
    // developed texture from — set once here, never moved.
    glUseProgram(blitProgram);
    glUniform1i(blitSrcLoc, 1);  // blit source: GL_TEXTURE1 (the developed
                                 // texture — the same unit the store pass
                                 // reads it from)
    glUseProgram(0);
    return true;
}

// ---- the frame job ------------------------------------------------------------

bool DevelopSession::Impl::developPass(const DevelopFrameRequest& req,
                                       DevelopFrameResult& out) {
    // The whole chain — the input contract, the CPU-side stage-1/5/6 uniform
    // math, the once-per-resolution allocation, the shading + LUT upload
    // caches, the PRE -> [NR] -> POST -> [unsharp] -> [WARP] -> TAIL draw
    // sequence with the upload/develop span measurement — runs in the
    // shared pipeline (gles/GpuDevelopPipeline.cpp), synchronously on
    // THIS GL thread with this session's context current. The DrawResult's
    // defaults keep the original contract-failure shape: a refused input
    // returns ok=false with width/height 0 and the reason in [detail].
    const GpuDevelopPipeline::DrawResult r = pipeline.draw(req);
    out.width = r.width;
    out.height = r.height;
    out.detail = r.detail;
    return r.ok;
}

void DevelopSession::Impl::runJob(const DevelopFrameRequest& req,
                                  DevelopFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;

    if (!developPass(req, out)) return;

    // --- draw 2: the store pass into the SSBO (never glReadPixels) ---------
    // The store program + its locations + the scratch FBO + the SSBO are
    // PIPELINE-owned (the header's accessors hand them over so this
    // session-side tail keeps its exact original draw + readback shape).
    glUseProgram(pipeline.storeProgram());
    glActiveTexture(GL_TEXTURE1);  // the develop texture, unit 1 (the same
                                   // unit the develop pass wrote; the sampler
                                   // uniform was set once at pipeline init)
    glBindTexture(GL_TEXTURE_2D, pipeline.developTex());
    glBindFramebuffer(GL_FRAMEBUFFER, pipeline.scratchFbo());
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(pipeline.storeSizeLoc(), static_cast<GLint>(w), static_cast<GLint>(h));
    // Re-bind the SSBO to indexed binding 0 before EVERY store draw (the
    // harness binds once per call; a persistent session must not rely on
    // the allocation branch's binding surviving unrelated state changes —
    // one redundant glBindBufferBase per frame is free next to the draw).
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, pipeline.outBuf());
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, pipeline.outBuf());
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(store)", &out.detail) != GL_NO_ERROR) return;

    // --- readback: a MAPPED buffer, NEVER glReadPixels ---------------------
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, pipeline.outBuf());
    void* mapped = glMapBufferRange(
        GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(pipeline.outBytes()),
        GL_MAP_READ_BIT);
    if (mapped == nullptr) {
        glErrorAfter("glMapBufferRange", &out.detail);
        if (out.detail.empty()) out.detail = "glMapBufferRange returned null";
        return;
    }
    const float* gpu = static_cast<const float*>(mapped);
    const size_t n3 = static_cast<size_t>(w) * h * 3;
    out.rgb888.assign(n3, 0);
    for (size_t i = 0; i < n3; ++i) {
        // The shader applied the OETF (uOetf=1); the tail does what
        // nativeExportFrameParcel's pack loop does after linearToRec709: the
        // toByte quantize (clamp [0,1], v*255+0.5).
        out.rgb888[i] = toByte(gpu[i]);
    }
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
    out.ok = true;
}

void DevelopSession::Impl::runSurfaceJob(ANativeWindow* window, bool* ok,
                                         std::string* detail) {
    // Attach/detach the encoder window surface (M4's runSurfaceJob shape).
    // Entered with the pbuffer current and the lock released. NEVER latches
    // (the Impl comment): a failure leaves the session healthy, the encoder
    // path just stays unavailable and the caller falls back with this
    // reason in ONE log line.
    *ok = false;
    if (detail != nullptr) detail->clear();

    // OWNERSHIP (the header's contract, the M4 setSurface shape): the
    // caller transferred its ANativeWindow reference at setEncoderSurface
    // entry; THIS job is the served path, so the release happens here, on
    // the GL thread, on EVERY attach path (success and failure —
    // eglCreateWindowSurface acquires the window's own reference on
    // success, so ours is redundant; on failure nothing else holds it). A
    // DETACH carries no reference. The fast-refusal paths (closed /
    // unhealthy session) release on the CALLING thread instead — see
    // setEncoderSurface().
    if (window == nullptr) {
        // Detach: destroy any attached encoder surface, then nothing more.
        // The pbuffer is current again and the session serves readback jobs
        // as before.
        if (encoderSurface != EGL_NO_SURFACE) {
            eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
            eglDestroySurface(egl.display, encoderSurface);
            encoderSurface = EGL_NO_SURFACE;
        }
        *ok = true;
        return;
    }

    // Destroy any previous encoder surface first (a re-attach replaces it;
    // EGL owns at most one window surface per ANativeWindow here).
    if (encoderSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        eglDestroySurface(egl.display, encoderSurface);
        encoderSurface = EGL_NO_SURFACE;
    }

    // The session's OWN config (opened window-capable + recordable at init)
    // — the SAME config the context was created with, which is what makes
    // eglCreateWindowSurface legal here. EGL_RECORDABLE_ANDROID is a
    // config-selection attribute only (EGL_ANDROID_recordable defines it
    // for eglChooseConfig/eglGetConfigAttrib lists): passing it in the
    // SURFACE attribs is invalid, and the Xiaomi 15 Ultra's EGL rejects
    // the call with EGL_BAD_ATTRIBUTE. The flag rides the CONFIG — the
    // recordable=true egl.open at init already gave the codec's
    // BufferQueue consumer what it needs from the producer config. Null
    // surface attribs, matching the writer path and the preview session's
    // device-proven wrap.
    EGLSurface created = eglCreateWindowSurface(egl.display, egl.config,
                                                 window, nullptr);
    if (created == EGL_NO_SURFACE) {
        // Read the thread-local EGL error HERE, before any cleanup —
        // it does not survive later calls on this thread.
        const EGLint eglErr = eglGetError();
        if (detail != nullptr) {
            char buf[96];
            snprintf(buf, sizeof(buf),
                     "eglCreateWindowSurface(encoder) failed eglError=0x%04x",
                     (unsigned)eglErr);
            *detail = buf;
        }
        ANativeWindow_release(window);
        return;
    }
    // Probe make-current on the new surface BEFORE accepting it: an
    // unusable surface must be refused now, not discovered mid-render.
    if (!eglMakeCurrent(egl.display, created, created, egl.context)) {
        // Same-thread, pre-cleanup eglGetError (thread-local state).
        const EGLint eglErr = eglGetError();
        if (detail != nullptr) {
            char buf[96];
            snprintf(buf, sizeof(buf),
                     "eglMakeCurrent(encoder) failed eglError=0x%04x",
                     (unsigned)eglErr);
            *detail = buf;
        }
        eglDestroySurface(egl.display, created);
        ANativeWindow_release(window);
        return;
    }
    encoderSurface = created;
    // Swap as fast as frames arrive — an export is not a display; pacing
    // would only add latency in front of the codec.
    eglSwapInterval(egl.display, 0);
    // Back to the pbuffer: the session's default current surface (the FBO
    // draws do not care about the window binding, but every job starts
    // from the same state).
    eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
    ANativeWindow_release(window);
    *ok = true;
}

void DevelopSession::Impl::runEncoderJob(const DevelopFrameRequest& req,
                                         int64_t ptsUs,
                                         int rotationDeg,
                                         DevelopFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;

    if (encoderSurface == EGL_NO_SURFACE) {
        out.detail = "encoder surface not attached";
        return;
    }
    if (presentationTime == nullptr) {
        // Honest gate (the header's rule): the extension was absent at
        // init; the frame is NOT stamped with a guess. The caller falls
        // back with this reason.
        out.detail = "eglPresentationTimeANDROID unavailable";
        return;
    }
    if (ptsUs < 0) {
        out.detail = "encoder pts must be non-negative us";
        return;
    }

    // The shared develop pass — the EXACT same draw the readback path
    // runs (same uniforms, same allocation, same FBO); only the tail
    // differs (a blit to the window surface instead of the store pass +
    // SSBO readback).
    if (!developPass(req, out)) return;

    // --- the blit: developTex -> the encoder window surface ----------------
    // Make the ENCODER surface current (the pbuffer stays the session's
    // default; the readback path and this path stay independent).
    if (!eglMakeCurrent(egl.display, encoderSurface, encoderSurface,
                        egl.context)) {
        out.detail = "eglMakeCurrent(encoder) failed";
        return;
    }
    // BLIT_FRAGMENT's Y flip reproduces the writer's Surface-mode
    // orientation verbatim (the develop pass's texel row 0 is the image
    // top; the window surface presents row 0 at the bottom). The rotation
    // fold rides the SAME draw: uSize stays the SOURCE's w x h (the
    // texture's own dims — the fold maps each rotated destination pixel
    // back to its source texel), while a quarter turn's DESTINATION
    // viewport is the swapped h x w (the encoder surface was configured
    // with the rotated dims; the fold's g walks that surface).
    const bool swap = (rotationDeg == 90 || rotationDeg == 270);
    const GLsizei dstW = swap ? static_cast<GLsizei>(h) : static_cast<GLsizei>(w);
    const GLsizei dstH = swap ? static_cast<GLsizei>(w) : static_cast<GLsizei>(h);
    glUseProgram(blitProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, pipeline.developTex());
    glBindFramebuffer(GL_FRAMEBUFFER, 0);  // draw to the window surface
    glViewport(0, 0, dstW, dstH);
    glUniform2i(blitSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform1i(blitRotLoc, rotationDeg);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(blit)", &out.detail) != GL_NO_ERROR) {
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        return;
    }
    // Media time, NEVER the wall clock: the SAME normalized pts the
    // writer path stamps (PtsTimeline.ptsNs's math — us -> ns).
    if (presentationTime(egl.display, encoderSurface,
                         static_cast<EGLnsecsANDROID>(ptsUs) * 1000) !=
        EGL_TRUE) {
        out.detail = "eglPresentationTimeANDROID failed";
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        return;
    }
    if (eglSwapBuffers(egl.display, encoderSurface) != EGL_TRUE) {
        out.detail = "eglSwapBuffers(encoder) failed";
        eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
        return;
    }
    // Back to the pbuffer for the next job (state symmetry).
    eglMakeCurrent(egl.display, egl.surface, egl.surface, egl.context);
    out.width = w;
    out.height = h;
    out.ok = true;
}

// ---- renderFrame / close / health ---------------------------------------------

// GRADE PARCEL (commit a + b): the develop session's ONLY readback render
// entry — the grade arrives as the decoded parcel struct, folded onto a
// stack copy of the request via the GENERATED applyGradeToRequest
// (gles/GradeParcel.h — the schema's single source of truth; a schema
// field with no matching DevelopFrameRequest member fails the compile
// here, the honest catch). The [req] the caller builds carries ONLY the
// non-grade fields (cfa/info/meta, the LUT binding group, the warp pair);
// the temporal pair never reaches the request — the bridge consumed it at
// temporalMergedCfa before this call. The flat pre-folded overload died
// with commit (b): every door into the session threads the grade through
// the parcel.
DevelopFrameResult DevelopSession::renderFrame(
        const DevelopFrameRequest& req, const DevelopGrade& grade) {
    DevelopFrameRequest folded = req;
    applyGradeToRequest(grade, &folded);
    DevelopFrameResult res;
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
    // In-flight guard: renderFrame blocks, so ONE well-behaved caller can
    // never trip this — but a second concurrent caller would overwrite
    // the pending fields and corrupt the first job's handoff. Fail it
    // loudly instead (the export engine thread is the one legal caller).
    if (impl_->pendingKind != Impl::JobKind::None) {
        res.detail = "renderFrame re-entered while a frame was in flight";
        return res;
    }
    impl_->pendingKind = Impl::JobKind::Render;
    impl_->pendingReq = &folded;
    impl_->pendingRes = &res;
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() { return impl_->pendingKind == Impl::JobKind::None; });
    // [res] was filled by the GL thread while we held the wait; the
    // unique_lock releases on return.
    return res;
}

bool DevelopSession::setEncoderSurface(ANativeWindow* window,
                                       std::string* detail) {
    if (impl_ == nullptr) {
        if (detail != nullptr) *detail = "session not open";
        return false;
    }
    // Ownership transfers at call entry (the M4 setSurface shape): the
    // caller acquired (or nulls) the ANativeWindow; the GL thread releases
    // it on every path. Fast refusals below release HERE on the calling
    // thread instead.
    std::unique_lock<std::mutex> lock(impl_->mutex);
    if (!impl_->threadRunning) {
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "session closed";
        return false;
    }
    // A DETACH (null window) always processes — even on a latched-unhealthy
    // session (teardown paths must be able to release the surface). An
    // ATTACH on an unhealthy session is refused: the context is broken and
    // the attach would be a lie.
    if (window != nullptr && !impl_->healthyFlag) {
        ANativeWindow_release(window);
        if (detail != nullptr) {
            *detail = impl_->failureDetail.empty()
                ? std::string("session unhealthy") : impl_->failureDetail;
        }
        return false;
    }
    if (impl_->pendingKind != Impl::JobKind::None) {
        if (window != nullptr) ANativeWindow_release(window);
        if (detail != nullptr) *detail = "setEncoderSurface re-entered while a job was in flight";
        return false;
    }
    impl_->pendingKind = Impl::JobKind::Surface;
    impl_->pendingWindow = window;
    impl_->pendingSurfaceOk = false;
    impl_->pendingSurfaceDetail.clear();
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() { return impl_->pendingKind == Impl::JobKind::None; });
    const bool ok = impl_->pendingSurfaceOk;
    if (detail != nullptr && !ok && !impl_->pendingSurfaceDetail.empty()) {
        *detail = impl_->pendingSurfaceDetail;
    }
    return ok;
}

// GRADE PARCEL (commit a + b): the encoder twin of the struct-taking
// renderFrame above — the ONLY encoder door. Same GENERATED fold onto a
// stack copy, then the encoder handoff. The flat pre-folded overload died
// with commit (b).
DevelopFrameResult DevelopSession::renderFrameToEncoder(
        const DevelopFrameRequest& req, const DevelopGrade& grade,
        int64_t ptsUs, int rotationDeg) {
    DevelopFrameRequest folded = req;
    applyGradeToRequest(grade, &folded);
    DevelopFrameResult res;
    if (impl_ == nullptr) {
        res.detail = "session not open";
        return res;
    }
    // Fast validate on the CALLING thread (never hand the GL thread a job
    // whose contract is already broken): the fold supports exactly the
    // normalize(deg) set — 0/90/180/270. Mp4Exporter gates first; this is
    // the second lock, honest and named (never a sideways encode).
    const int norm = ((rotationDeg % 360) + 360) % 360;
    if (norm != 0 && norm != 90 && norm != 180 && norm != 270) {
        res.detail = "encoder blit rotation must be 0/90/180/270 (got " +
                     std::to_string(rotationDeg) + ")";
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
    if (impl_->pendingKind != Impl::JobKind::None) {
        res.detail = "renderFrameToEncoder re-entered while a job was in flight";
        return res;
    }
    impl_->pendingKind = Impl::JobKind::Encoder;
    impl_->pendingReq = &folded;
    impl_->pendingRes = &res;
    impl_->pendingPtsUs = ptsUs;
    impl_->pendingRotationDeg = norm;
    impl_->cvRequest.notify_one();
    impl_->cvDone.wait(lock, [&]() { return impl_->pendingKind == Impl::JobKind::None; });
    return res;
}

void DevelopSession::close() {
    if (impl_ == nullptr) return;
    std::thread joiner;
    {
        std::lock_guard<std::mutex> lock(impl_->mutex);
        if (!impl_->threadRunning) return;  // already closed (idempotent)
        impl_->closeRequested = true;
        impl_->cvRequest.notify_one();
        // Move the thread handle out so we can join WITHOUT the mutex
        // (joining while holding it would deadlock: the loop's lock
        // acquisition would block forever).
        joiner = std::move(impl_->glThread);
        impl_->threadRunning = false;
    }
    if (joiner.joinable()) joiner.join();
}

DevelopSession::~DevelopSession() {
    close();
    // impl_'s destruction now frees the PODs; every GL object was deleted
    // on the GL thread in teardownOnGlThread (glThreadLoop's exit path),
    // and EglSession::release is idempotent with NO_* handles.
}

bool DevelopSession::healthy() const {
    if (impl_ == nullptr) return false;
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->healthyFlag;
}

std::string DevelopSession::failureDetail() const {
    // By value (the header's contract): the GL thread may latch a new
    // detail under the mutex while a caller held a returned reference.
    if (impl_ == nullptr) return std::string();
    std::lock_guard<std::mutex> lock(impl_->mutex);
    return impl_->failureDetail;
}

void DevelopSession::Impl::teardownOnGlThread() {
    // Called from the GL thread's exit path (glThreadLoop); the reverse of
    // creation. Safe on a partially-initialized session: every handle
    // starts at 0/NO_*.
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    // The chain's own teardown (the pipeline's established order: the
    // scratch/chain/develop FBOs, then the scratch/chain/develop/shading/
    // LUT/CFA textures, the readback SSBO, then the chain+store programs)
    // — the pipeline owns them, so it deletes them, on this same GL
    // thread with this session's context still current.
    pipeline.teardown();
    if (blitProgram != 0) { glDeleteProgram(blitProgram); blitProgram = 0; }
    // The encoder window surface dies BEFORE the EGL session release (it
    // belongs to this display); the pbuffer is current, so destroying the
    // window surface is legal.
    if (encoderSurface != EGL_NO_SURFACE) {
        eglDestroySurface(egl.display, encoderSurface);
        encoderSurface = EGL_NO_SURFACE;
    }
    egl.release();
}

} // namespace gles
