// Production GPU preview session (M4 commit a). See GlesPreviewSession.h
// for the lifecycle/threading/color/no-readback contracts. This
// translation unit is Android-only (EGL/GLES headers exist only in the
// NDK; the desktop build never compiles it — host-side coverage lives in
// the Kotlin shader-source validator + the signature/unavailable-guard
// JVM tests).
//
// The develop body mirrors the DEVICE-ROVEN M3 session
// (GlesDevelopSession.cpp, itself the harness shape — fragment parity
// PASS at 1 LSB on the owner's phone): same CPU-computed uniform rules,
// same texture units (CFA GL_TEXTURE0, develop GL_TEXTURE1, shading
// GL_TEXTURE3), same transpose=GL_TRUE row-major matrix upload, same
// immutable glTexStorage2D-once / glTexSubImage2D-per-frame discipline.
// The PREVIEW differences are the point of this file:
//
//   - the session owns its EGL end-to-end (a window-capable config whose
//     EGLConfig is KEPT for eglCreateWindowSurface; EglSession in
//     GlesPipeline.h is pbuffer-only and cannot serve a window);
//   - a SECOND job kind (setSurface) rides the same bounded one-job
//     handoff — the SurfaceHolder's lifecycle events marshal onto the GL
//     thread, where every EGL call must live;
//   - after the develop pass the frame STAYS ON THE GPU: the window pass
//     (WINDOW_FRAGMENT) draws it to the Android Surface with aspect-fit
//     letterbox geometry + the orientation fold, and the ONLY readback
//     is the small stats pack (~256 long edge) through the same
//     mapped-SSBO path the export session uses. glReadPixels is used
//     NOWHERE, and no full-frame readback exists on this path — the
//     audit's explicit rule for the preview deliverable.
//
// Status vocabulary: DEVICE-TEST-PENDING (the owner's morning playback
// run is the production proof); the math is the math the M0 parity
// harness already proved on device.

#include "GlesPreviewSession.h"

#include <algorithm>
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

namespace gles {
namespace {

// ---- Documented copies (each verbatim from its source, which stays the
// ---- owner of the text; see the per-copy notes). This is the THIRD copy
// ---- of this block in the tree (GlesPipeline.cpp and
// ---- GlesDevelopSession.cpp carry the other two): the originals live in
// ---- anonymous namespaces / READ-ONLY core sources, so a shared header
// ---- would churn merged M3 code; the established convention is a
// ---- documented copy with a keep-in-sync note. When any original
// ---- changes, ALL copies change with it. ------------------------------

// parseGlesVersion — verbatim from GlesPipeline.cpp (the harness's own
// copy; "OpenGL ES 3.2 ..." -> (3, 2), malformed -> (0, 0): never guess a
// version).
std::pair<int, int> parseGlesVersion(const std::string& version) {
    if (version.rfind("OpenGL ES ", 0) != 0) return std::make_pair(0, 0);
    const size_t base = 10;  // strlen("OpenGL ES ")
    int major = 0;
    int minor = 0;
    size_t i = base;
    while (i < version.size() && version[i] >= '0' && version[i] <= '9') {
        major = major * 10 + (version[i] - '0');
        ++i;
    }
    if (i == base || i >= version.size() || version[i] != '.') {
        return std::make_pair(major, 0);
    }
    ++i;
    while (i < version.size() && version[i] >= '0' && version[i] <= '9') {
        minor = minor * 10 + (version[i] - '0');
        ++i;
    }
    return std::make_pair(major, minor);
}

// cellFor — verbatim from GlesPipeline.cpp (itself the flattened
// core/src/Develop.cpp layoutFor): the four site codes uCell expects.
void cellFor(const rawedit::FrameInfo& info, int cell[4]) {
    switch (info.cfa) {
        case rawedit::CfaPattern::GRBG:
            cell[0] = 1; cell[1] = 0; cell[2] = 2; cell[3] = 1;
            return;
        case rawedit::CfaPattern::GBRG:
            cell[0] = 1; cell[1] = 2; cell[2] = 0; cell[3] = 1;
            return;
        case rawedit::CfaPattern::BGGR:
            cell[0] = 2; cell[1] = 1; cell[2] = 1; cell[3] = 0;
            return;
        case rawedit::CfaPattern::RGGB:
        default:
            cell[0] = 0; cell[1] = 1; cell[2] = 1; cell[3] = 2;
            return;
    }
}

// ---- 3x3 row-major matrix helpers + buildCameraToRec709 — verbatim from
// ---- core/src/Develop.cpp (core/ is READ-ONLY; the same documented-copy
// ---- pattern as the other two TUs). developCpu's stages 5+6 run
// ---- UNCONDITIONALLY on this matrix for every grade, so the session
// ---- needs no asShot special case.
struct Mat3 {
    float m[9];
};

Mat3 mul3(const Mat3& a, const Mat3& b) {
    Mat3 r{};
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) {
            float s = 0.0f;
            for (int k = 0; k < 3; ++k)
                s += a.m[i * 3 + k] * b.m[k * 3 + j];
            r.m[i * 3 + j] = s;
        }
    return r;
}

Mat3 inv3(const Mat3& a) {
    const float* m = a.m;
    const float det =
        m[0] * (m[4] * m[8] - m[5] * m[7]) -
        m[1] * (m[3] * m[8] - m[5] * m[6]) +
        m[2] * (m[3] * m[7] - m[4] * m[6]);
    if (std::abs(det) < 1e-12f)
        return Mat3{{1,0,0, 0,1,0, 0,0,1}};
    const float inv = 1.0f / det;
    Mat3 r{};
    r.m[0] =  (m[4] * m[8] - m[5] * m[7]) * inv;
    r.m[1] = -(m[1] * m[8] - m[2] * m[7]) * inv;
    r.m[2] =  (m[1] * m[5] - m[2] * m[4]) * inv;
    r.m[3] = -(m[3] * m[8] - m[5] * m[6]) * inv;
    r.m[4] =  (m[0] * m[8] - m[2] * m[6]) * inv;
    r.m[5] = -(m[0] * m[5] - m[2] * m[3]) * inv;
    r.m[6] =  (m[3] * m[7] - m[4] * m[6]) * inv;
    r.m[7] = -(m[0] * m[7] - m[1] * m[6]) * inv;
    r.m[8] =  (m[0] * m[4] - m[1] * m[3]) * inv;
    return r;
}

Mat3 xyzD50ToRec709() {
    static const float bradford[9] = {
        0.8951f,  0.2664f, -0.1614f,
       -0.7502f,  1.7135f,  0.0367f,
        0.0389f, -0.0685f,  1.0296f,
    };
    const float wpD50[3] = {0.9642f, 1.0f, 0.8249f};
    const float wpD65[3] = {0.9505f, 1.0f, 1.0890f};

    float srcCone[3], dstCone[3];
    for (int i = 0; i < 3; ++i) {
        srcCone[i] = bradford[i*3+0]*wpD50[0] + bradford[i*3+1]*wpD50[1] + bradford[i*3+2]*wpD50[2];
        dstCone[i] = bradford[i*3+0]*wpD65[0] + bradford[i*3+1]*wpD65[1] + bradford[i*3+2]*wpD65[2];
    }
    // D = diag(dstCone/srcCone) folded into Bradford: M = B^-1 * D * B
    Mat3 B{}; for (int i = 0; i < 9; ++i) B.m[i] = bradford[i];
    Mat3 D{}; D.m[0]=dstCone[0]/srcCone[0]; D.m[4]=dstCone[1]/srcCone[1]; D.m[8]=dstCone[2]/srcCone[2];
    const Mat3 adaptM = mul3(inv3(B), mul3(D, B));

    static const float xyz2rgb[9] = {
        3.2406f, -1.5372f, -0.4986f,
       -0.9689f,  1.8758f,  0.0415f,
        0.0557f, -0.2040f,  1.0570f,
    };
    Mat3 RGB{}; for (int i = 0; i < 9; ++i) RGB.m[i] = xyz2rgb[i];
    return mul3(RGB, adaptM);
}

bool isIdentity3(const float* m) {
    return m[0] == 1 && m[4] == 1 && m[8] == 1
        && m[1] == 0 && m[2] == 0 && m[3] == 0
        && m[5] == 0 && m[6] == 0 && m[7] == 0;
}

// Camera-normalized RGB -> linear Rec.709 (DNG 1.6 §6; identity profile ->
// passthrough + false). Verbatim copy; see the note above.
bool buildCameraToRec709(const rawedit::RawMeta& meta, float out[9]) {
    const bool hasForward = !isIdentity3(meta.forwardMatrix);
    const bool hasColor = !isIdentity3(meta.colorMatrix);
    if (!hasForward && !hasColor) {
        for (int i = 0; i < 9; ++i)
            out[i] = (i == 0 || i == 4 || i == 8) ? 1.0f : 0.0f;
        return false; // passthrough: identity profile
    }
    Mat3 cam2xyz{};
    Mat3 FM{}, CAL{}, CM{};
    for (int i = 0; i < 9; ++i) {
        FM.m[i] = meta.forwardMatrix[i];
        CAL.m[i] = meta.calibration[i];
        CM.m[i] = meta.colorMatrix[i];
    }
    if (hasForward) {
        cam2xyz = mul3(FM, CAL);
    } else {
        cam2xyz = inv3(mul3(CAL, CM));
    }
    const Mat3 total = mul3(xyzD50ToRec709(), cam2xyz);
    for (int i = 0; i < 9; ++i)
        out[i] = total.m[i];
    return true;
}

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
    GLuint developProgram = 0;
    GLuint storeProgram = 0;
    GLuint windowProgram = 0;
    // Uniform locations, resolved ONCE at open (17 develop + 2 store +
    // 4 window).
    GLint cfaLoc = -1, sizeLoc = -1, cellLoc = -1, blackLoc = -1, rangeLoc = -1,
          hasShadingLoc = -1, shadingLoc = -1, shadingSizeLoc = -1, hrLoc = -1,
          hrGainLoc = -1, wbLoc = -1, camLoc = -1, hasProfileLoc = -1,
          exposureLoc = -1, highlightsLoc = -1, tonemapLoc = -1, oetfLoc = -1;
    GLint storeSrcLoc = -1, storeSizeLoc = -1;
    GLint windowSrcLoc = -1, windowSrcSizeLoc = -1, windowRectLoc = -1,
          windowOrientationLoc = -1;
    GLuint cfaTex = 0, shadingTex = 0, developTex = 0;
    GLuint developFbo = 0;
    // The stats pack's resources (the ONLY readback on this path):
    // statsTex is the window pass's stats target; statsScratchTex is the
    // store pass's throwaway color target (NEVER statsFbo, whose texture
    // is the source — a feedback loop); statsBuf is the small SSBO.
    GLuint statsTex = 0, statsScratchTex = 0;
    GLuint statsFbo = 0, statsScratchFbo = 0;
    GLuint statsBuf = 0;
    // Immutable storage currently allocated (0 = none yet).
    uint32_t cfaW = 0, cfaH = 0;          // the reduced (develop) tier
    uint32_t shadingW = 0, shadingH = 0; // the uploaded shading map
    size_t shadingUploaded = 0;          // its lensShading().size()
    uint32_t statsAllocW = 0, statsAllocH = 0;
    uint64_t statsBytes = 0;
    // OES_texture_float_linear read at open: RGBA32F is LINEAR-filterable
    // only with it; the develop texture's MIN/MAG filter is set at
    // allocation accordingly (NEAREST otherwise — correct, just aliased).
    bool developLinearFilter = false;

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
                        const std::string& fragmentSource,
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
        const std::string& fragmentSource,
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

    // Capture list is explicit on purpose: [impl] and the five sources BY
    // VALUE (a `[&]` would dangle — this thread outlives open()'s frame),
    // the init-handshake locals by reference (safe: each is touched exactly
    // once, before the notify, and open() does not return until the
    // notification arrives).
    impl->glThread = std::thread(
        [impl, vertexSource, vertex310Source, fragmentSource,
         storeFragmentSource, windowFragmentSource, &initMutex, &initCv,
         &initReported, &initOk, &initDetail]() {
            std::string d;
            const bool ok = impl->initOnGlThread(
                vertexSource, vertex310Source, fragmentSource,
                storeFragmentSource, windowFragmentSource, &d);
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
        const std::string& fragmentSource,
        const std::string& storeFragmentSource,
        const std::string& windowFragmentSource,
        std::string* detail) {
    // --- EGL + capability gates (THIS context, not the probe's) ------------
    std::string d;
    if (!openEglOnGlThread(&d)) {
        *detail = "egl: " + d;
        return false;
    }
    const GLubyte* v = glGetString(GL_VERSION);
    const std::string version = v != nullptr ? reinterpret_cast<const char*>(v) : "";
    const std::pair<int, int> mm = parseGlesVersion(version);
    // STORE_FRAGMENT needs ES 3.1 (fragment-shader SSBO access).
    if (mm.first < 3 || (mm.first == 3 && mm.second < 1)) {
        *detail = "context-not-31: " + version;
        return false;
    }
    const GLubyte* e = glGetString(GL_EXTENSIONS);
    const std::string extensions = e != nullptr ? reinterpret_cast<const char*>(e) : "";
    if (extensions.find("GL_EXT_color_buffer_float") == std::string::npos) {
        *detail = "no EXT_color_buffer_float (RGBA32F not renderable)";
        return false;
    }
    // OES_texture_float_linear is a FILTER choice, not a gate: without it
    // RGBA32F is only NEAREST-filterable (the window pass still draws,
    // aliased); with it, LINEAR. Read HERE, in THIS context — never trust
    // a probe's throwaway one.
    developLinearFilter =
        extensions.find("GL_OES_texture_float_linear") != std::string::npos;

    // --- programs, compiled ONCE -------------------------------------------
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", detail);
    if (vertShader.id == 0) return false;
    GlShader fragShader;
    fragShader.id = compileShader(GL_FRAGMENT_SHADER, fragmentSource, "develop-fragment", detail);
    if (fragShader.id == 0) return false;
    developProgram = linkProgram(vertShader.id, fragShader.id, "develop", detail);
    if (developProgram == 0) return false;
    GlShader vert310Shader;
    vert310Shader.id = compileShader(GL_VERTEX_SHADER, vertex310Source, "vertex310", detail);
    if (vert310Shader.id == 0) return false;
    GlShader storeFragShader;
    storeFragShader.id = compileShader(GL_FRAGMENT_SHADER, storeFragmentSource, "store-fragment", detail);
    if (storeFragShader.id == 0) return false;
    storeProgram = linkProgram(vert310Shader.id, storeFragShader.id, "store", detail);
    if (storeProgram == 0) return false;
    GlShader windowFragShader;
    windowFragShader.id = compileShader(GL_FRAGMENT_SHADER, windowFragmentSource, "window-fragment", detail);
    if (windowFragShader.id == 0) return false;
    windowProgram = linkProgram(vertShader.id, windowFragShader.id, "window", detail);
    if (windowProgram == 0) return false;
    // Program ownership is already in the Impl; the GlShader guards
    // deleting the SHADER objects at scope exit is correct OpenGL — an
    // attached shader stays flagged-alive until its program dies.

    // --- uniform locations, resolved ONCE ----------------------------------
    cfaLoc = glGetUniformLocation(developProgram, "uCfa");
    sizeLoc = glGetUniformLocation(developProgram, "uSize");
    cellLoc = glGetUniformLocation(developProgram, "uCell");
    blackLoc = glGetUniformLocation(developProgram, "uBlack");
    rangeLoc = glGetUniformLocation(developProgram, "uRange");
    hasShadingLoc = glGetUniformLocation(developProgram, "uHasShading");
    shadingLoc = glGetUniformLocation(developProgram, "uShading");
    shadingSizeLoc = glGetUniformLocation(developProgram, "uShadingSize");
    hrLoc = glGetUniformLocation(developProgram, "uHr");
    hrGainLoc = glGetUniformLocation(developProgram, "uHrGain");
    wbLoc = glGetUniformLocation(developProgram, "uWb");
    camLoc = glGetUniformLocation(developProgram, "uCam2rgb");
    hasProfileLoc = glGetUniformLocation(developProgram, "uHasProfile");
    exposureLoc = glGetUniformLocation(developProgram, "uExposure");
    highlightsLoc = glGetUniformLocation(developProgram, "uHighlights");
    tonemapLoc = glGetUniformLocation(developProgram, "uTonemap");
    oetfLoc = glGetUniformLocation(developProgram, "uOetf");
    if (cfaLoc < 0 || sizeLoc < 0 || cellLoc < 0 || blackLoc < 0 ||
        rangeLoc < 0 || hasShadingLoc < 0 || shadingLoc < 0 ||
        shadingSizeLoc < 0 || hrLoc < 0 || hrGainLoc < 0 || wbLoc < 0 ||
        camLoc < 0 || hasProfileLoc < 0 || exposureLoc < 0 ||
        highlightsLoc < 0 || tonemapLoc < 0 || oetfLoc < 0) {
        *detail = "shader-contract: missing a develop uniform";
        return false;
    }
    storeSrcLoc = glGetUniformLocation(storeProgram, "uSrc");
    storeSizeLoc = glGetUniformLocation(storeProgram, "uSize");
    if (storeSrcLoc < 0 || storeSizeLoc < 0) {
        *detail = "shader-contract: missing uSrc/uSize";
        return false;
    }
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
    glUseProgram(developProgram);
    glUniform1i(cfaLoc, 0);      // CFA: GL_TEXTURE0
    glUniform1i(shadingLoc, 3);  // shading: GL_TEXTURE3
    glUseProgram(storeProgram);
    glUniform1i(storeSrcLoc, 1); // store source: GL_TEXTURE1
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
    ANativeWindow_release(window);
    ok = true;
}

// ---- the frame job ------------------------------------------------------------

void PreviewSession::Impl::runJob(const PreviewFrameRequest& req,
                                   PreviewFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;
    if (req.cfa == nullptr || req.meta == nullptr ||
        w == 0 || h == 0 ||
        req.cfa->size() != static_cast<size_t>(w) * h) {
        out.detail = "input: cfa size != w*h";
        return;
    }

    // --- CPU-computed uniforms (documented copies of developCpu's own
    // stage-1/5/6 math — the same rules the M3 session runs; the shader
    // applies them in-hardware) --------------------------------------------
    const bool useFrameBlack = req.info.dynamicBlack[0] >= 0;
    const float white = req.info.dynamicWhite > 0
        ? static_cast<float>(req.info.dynamicWhite)
        : static_cast<float>(req.meta->white);
    float black[4];
    for (int slot = 0; slot < 4; ++slot)
        black[slot] = useFrameBlack
            ? static_cast<float>(req.info.dynamicBlack[slot])
            : static_cast<float>(req.meta->black[slot]);
    float range[4];
    for (int slot = 0; slot < 4; ++slot)
        range[slot] = white > black[slot] ? white - black[slot] : 1.0f;
    const float invNeutral[3] = {
        req.meta->asShotNeutral[0] > 0.0f ? 1.0f / req.meta->asShotNeutral[0] : 1.0f,
        req.meta->asShotNeutral[1] > 0.0f ? 1.0f / req.meta->asShotNeutral[1] : 1.0f,
        req.meta->asShotNeutral[2] > 0.0f ? 1.0f / req.meta->asShotNeutral[2] : 1.0f,
    };
    int cell[4];
    cellFor(req.info, cell);
    // Stage 2's gate: the grade's keepVignetting AND the map's presence.
    const bool shadingPresent = !req.meta->lensShading.empty() &&
                                 req.meta->shadingW > 0 && req.meta->shadingH > 0;
    const bool applyShading = req.keepVignetting && shadingPresent;
    // Stage 3's gain vector: meta.wbGains with the >0 guard (a DIFFERENT
    // vector from uWb's 1/asShotNeutral).
    const float hrGain[3] = {
        req.meta->wbGains[0] > 0.0f ? req.meta->wbGains[0] : 1.0f,
        req.meta->wbGains[1] > 0.0f ? req.meta->wbGains[1] : 1.0f,
        req.meta->wbGains[2] > 0.0f ? req.meta->wbGains[2] : 1.0f,
    };
    // Stage 6's matrix (the documented buildCameraToRec709 copy).
    float cam2rgb[9];
    const bool hasProfile = buildCameraToRec709(*req.meta, cam2rgb);
    // Stage 14: exposure = 2^ev (developCpu's own convention).
    const float exposure = std::pow(2.0f, req.exposureEv);
    // The orientation fold's input, normalized exactly like developToArgb's.
    const int orient = ((req.info.orientationDeg % 360) + 360) % 360;

    // --- develop-tier storage allocation ONCE per resolution change --------
    // (the reduced mosaic's dims change with the tier ladder's maxDim).
    if (cfaW != w || cfaH != h) {
        if (cfaTex != 0) { glDeleteTextures(1, &cfaTex); cfaTex = 0; }
        if (developTex != 0) { glDeleteTextures(1, &developTex); developTex = 0; }
        if (developFbo != 0) { glDeleteFramebuffers(1, &developFbo); developFbo = 0; }
        cfaW = 0; cfaH = 0;

        // CFA texture: GL_R16UI, the RAW samples in their native values.
        // The REDUCED mosaic keeps the 2x2 pattern (cell-aligned
        // decimation — Thumbnail.h's math, done by the JNI entry), so
        // uCell/uBlack/uRange and the shading plane order stay valid at
        // this tier.
        glGenTextures(1, &cfaTex);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, cfaTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(R16UI)", &out.detail) != GL_NO_ERROR) return;

        // develop texture: RGBA32F (EXT_color_buffer_float, gated in THIS
        // context at open). The window pass samples it NORMALIZED, so the
        // filter matters: LINEAR when the device advertises
        // OES_texture_float_linear, NEAREST otherwise (32F is only
        // NEAREST-filterable without it — correct either way, aliased
        // without).
        glGenTextures(1, &developTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, developTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER,
                        developLinearFilter ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER,
                        developLinearFilter ? GL_LINEAR : GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(RGBA32F)", &out.detail) != GL_NO_ERROR) return;
        glGenFramebuffers(1, &developFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               developTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "develop fbo incomplete (RGBA32F not renderable here)";
            return;
        }
        cfaW = w;
        cfaH = h;
    }

    // The shading map: uploaded when its identity changes (dims or size —
    // the session serves one clip's editor, and a clip change swaps the
    // whole map; a same-dims same-size content swap within one session is
    // not detected and is outside the one-clip-per-session contract).
    const size_t mapSize = req.meta->lensShading.size();
    if (shadingTex == 0 || shadingW != req.meta->shadingW ||
        shadingH != req.meta->shadingH || shadingUploaded != mapSize) {
        if (shadingTex != 0) { glDeleteTextures(1, &shadingTex); shadingTex = 0; }
        glGenTextures(1, &shadingTex);
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(GL_TEXTURE_2D, shadingTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (applyShading) {
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F,
                           static_cast<GLsizei>(req.meta->shadingW),
                           static_cast<GLsizei>(req.meta->shadingH * 4));
            if (glErrorAfter("glTexStorage2D(R32F shading)", &out.detail) != GL_NO_ERROR) return;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(req.meta->shadingW),
                            static_cast<GLsizei>(req.meta->shadingH * 4),
                            GL_RED, GL_FLOAT, req.meta->lensShading.data());
            if (glErrorAfter("glTexSubImage2D(R32F shading)", &out.detail) != GL_NO_ERROR) return;
            shadingW = req.meta->shadingW;
            shadingH = req.meta->shadingH;
        } else {
            const float dummy = 1.0f;
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, 1, 1);
            if (glErrorAfter("glTexStorage2D(R32F dummy)", &out.detail) != GL_NO_ERROR) return;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RED, GL_FLOAT, &dummy);
            if (glErrorAfter("glTexSubImage2D(R32F dummy)", &out.detail) != GL_NO_ERROR) return;
            shadingW = 1;
            shadingH = 1;
        }
        shadingUploaded = mapSize;
    }

    // --- per-frame upload: the reduced CFA, into the immutable storage -----
    using SteadyClock = std::chrono::steady_clock;
    const SteadyClock::time_point tUpload0 = SteadyClock::now();
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, cfaTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                    static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                    GL_RED_INTEGER, GL_UNSIGNED_SHORT, req.cfa->data());
    if (glErrorAfter("glTexSubImage2D(R16UI)", &out.detail) != GL_NO_ERROR) return;
    const SteadyClock::time_point tUpload1 = SteadyClock::now();
    out.uploadUs = elapsedUs(tUpload0, tUpload1);

    // --- draw 1: the develop pass (M3's verbatim fused stages + OETF tail) -
    glUseProgram(developProgram);
    glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(sizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform4i(cellLoc, cell[0], cell[1], cell[2], cell[3]);
    glUniform4f(blackLoc, black[0], black[1], black[2], black[3]);
    glUniform4f(rangeLoc, range[0], range[1], range[2], range[3]);
    glUniform1i(hasShadingLoc, applyShading ? 1 : 0);
    glUniform2i(shadingSizeLoc,
                static_cast<GLint>(applyShading ? req.meta->shadingW : 1),
                static_cast<GLint>(applyShading ? req.meta->shadingH : 1));
    glUniform1i(hrLoc, req.highlightReconstruction ? 1 : 0);
    glUniform3f(hrGainLoc, hrGain[0], hrGain[1], hrGain[2]);
    glUniform3f(wbLoc, invNeutral[0], invNeutral[1], invNeutral[2]);
    // transpose=GL_TRUE: row-major upload, the CPU contract.
    glUniformMatrix3fv(camLoc, 1, GL_TRUE, cam2rgb);
    glUniform1i(hasProfileLoc, hasProfile ? 1 : 0);
    glUniform1f(exposureLoc, exposure);
    glUniform1f(highlightsLoc, req.highlights);  // raw; the shader computes s
    glUniform1i(tonemapLoc, req.tonemap);
    glUniform1i(oetfLoc, 1);  // the preview shape: linearToRec709 in-shader
    const SteadyClock::time_point tDevelop0 = SteadyClock::now();
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(develop)", &out.detail) != GL_NO_ERROR) return;
    const SteadyClock::time_point tDevelop1 = SteadyClock::now();
    out.developUs = elapsedUs(tDevelop0, tDevelop1);
    // The develop pass's actual output dims — the trace's develop= truth
    // (the published pack's dims hide them at the ~256 tier).
    out.developW = w;
    out.developH = h;

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
            glBindTexture(GL_TEXTURE_2D, developTex);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);  // the window surface
            glViewport(0, 0, winW, winH);
            glUniform2i(windowSrcSizeLoc,
                        static_cast<GLint>(w), static_cast<GLint>(h));
            glUniform4f(windowRectLoc, rectX, rectY, rectW, rectH);
            glUniform1i(windowOrientationLoc, orient);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            if (glErrorAfter("glDrawArrays(window)", &out.detail) != GL_NO_ERROR) {
                // Non-fatal diagnostic: the published frame still ships.
                // (glGetError above CLEARED the error flag, so the stats
                // pass's checks start clean.)
            }
            if (!eglSwapBuffers(display, windowSurface) && out.detail.empty()) {
                out.detail = "window present: eglSwapBuffers";
            }
        } else if (out.detail.empty()) {
            out.detail = "window present: eglQuerySurface";
        }
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
    glBindTexture(GL_TEXTURE_2D, developTex);
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
    glUseProgram(storeProgram);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, statsTex);
    glBindFramebuffer(GL_FRAMEBUFFER, statsScratchFbo);
    glViewport(0, 0, static_cast<GLsizei>(statsW), static_cast<GLsizei>(statsH));
    glUniform2i(storeSizeLoc,
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

PreviewFrameResult PreviewSession::renderFrame(const PreviewFrameRequest& req) {
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
    impl_->pendingReq = &req;
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
    if (statsScratchFbo != 0) { glDeleteFramebuffers(1, &statsScratchFbo); statsScratchFbo = 0; }
    if (statsFbo != 0) { glDeleteFramebuffers(1, &statsFbo); statsFbo = 0; }
    if (statsScratchTex != 0) { glDeleteTextures(1, &statsScratchTex); statsScratchTex = 0; }
    if (statsTex != 0) { glDeleteTextures(1, &statsTex); statsTex = 0; }
    if (developFbo != 0) { glDeleteFramebuffers(1, &developFbo); developFbo = 0; }
    if (developTex != 0) { glDeleteTextures(1, &developTex); developTex = 0; }
    if (shadingTex != 0) { glDeleteTextures(1, &shadingTex); shadingTex = 0; }
    if (cfaTex != 0) { glDeleteTextures(1, &cfaTex); cfaTex = 0; }
    if (statsBuf != 0) { glDeleteBuffers(1, &statsBuf); statsBuf = 0; }
    if (developProgram != 0) { glDeleteProgram(developProgram); developProgram = 0; }
    if (storeProgram != 0) { glDeleteProgram(storeProgram); storeProgram = 0; }
    if (windowProgram != 0) { glDeleteProgram(windowProgram); windowProgram = 0; }
    releaseEglOnGlThread();
}

} // namespace gles
