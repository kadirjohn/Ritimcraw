// Production GPU develop session (M3 commit a). See GlesDevelopSession.h
// for the lifecycle/threading/color contracts. This translation unit is
// Android-only (EGL/GLES headers exist only in the NDK; the desktop build
// never compiles it — host-side coverage lives in the Kotlin shader-source
// validator + the parity decision rules and their JVM tests).
//
// The draw/readback body mirrors the DEVICE-PROVEN harness
// (GlesPipeline.cpp developFragmentParity — fragment parity PASS at 1 LSB on
// the owner's phone): same CPU-computed uniform rules, same texture units
// (CFA GL_TEXTURE0, develop GL_TEXTURE1, scratch GL_TEXTURE2, shading
// GL_TEXTURE3), same SSBO binding 0, same transpose=GL_TRUE row-major
// matrix upload, same store-pass feedback-loop avoidance (the store pass
// draws to the scratch FBO, never to the develop FBO whose texture is the
// source), same mapped-SSBO readback (glReadPixels is used NOWHERE, the
// recorded baseline invariant). The differences are the point of this
// file: a persistent context, programs and uniform locations resolved once
// at open, immutable glTexStorage2D storage allocated once per resolution/
// resource change, glTexSubImage2D per-frame uploads, and a dedicated GL
// thread with blocking (bounded one-job) handoff.
//
// The production uniform VALUES differ from the harness's pinned ones by
// design — the harness pinned uHasProfile=0 / uExposure=1.0 / uOetf=0 to
// isolate the parity cases; production opens the gates the export tail
// needs: uHasProfile/uCam2rgb from the real buildCameraToRec709 matrix,
// uExposure = 2^ev (grade.exposureEv), uHighlights = grade.highlights raw
// (the shader computes s = -highlights/100 — the CPU stage-15 shoulder),
// uOetf = 1 (the export shape — the shader's linearToRec709 is textually
// identical to core/src/Transfer.cpp's, so the readback's toByte quantize
// reproduces nativeExportFrame's pack loop: toByte(linearToRec709(rgb))
// with no second OETF application), and the ten pointwise grade stages'
// raw values (uBlackPoint, uWhitePoint, uShadows, uMidtones, uContrast,
// uTempK, uTint, the six color-wheel offsets, uSplitShadowHue,
// uSplitHighlightHue, uSplitBalance, uSaturation — each 0.0f is the
// CPU's own neutral skip) with the two CPU-computed int gates the shader
// branches on: uWbManual = !grade.asShot (stages 16/17 run the manual
// temp/tint path only when the AS-shot gains are off) and uSplitActive =
// splitShadowHue >= 0 || splitHighlightHue >= 0 (stage 19's own guard,
// hoisted exactly like the shading gate).

#include "GlesDevelopSession.h"

#include <cmath>
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

// ---- Documented copies (each verbatim from its source, which stays the
// ---- owner of the text; see the per-copy notes). -------------------------

// parseGlesVersion — verbatim from GlesPipeline.cpp (the harness's own
// copy; "OpenGL ES 3.2 ..." -> (3, 2), malformed -> (0, 0): never guess a
// version). Copied because the original lives in that TU's anonymous
// namespace; when GlesPipeline.cpp changes the parse, this copy must
// change with it (the harness and the session must agree on the gate).
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
// core/src/Develop.cpp layoutFor): (cell[0][0], cell[0][1], cell[1][0],
// cell[1][1]), the four site codes the shader's uCell expects. Same
// keep-in-sync rule as parseGlesVersion.
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
// ---- core/src/Develop.cpp (the anonymous-namespace originals; core/ is
// ---- READ-ONLY so this is a documented copy, the same pattern
// ---- GlesPipeline.cpp's case-1 reference uses). developCpu's stages 5+6
// ---- run UNCONDITIONALLY on this matrix for every grade (the asShot
// ---- toggle gates only stages 16/17, which the shader now covers behind
// ---- the CPU-computed uWbManual gate), so the session still needs no
// ---- asShot special case here: the Kotlin-side coverage gate already
// ---- guaranteed the grade's active stages are all fragment-covered.
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
    GLuint developProgram = 0;
    GLuint storeProgram = 0;
    // Uniform locations, resolved ONCE at open (36 develop + 2 store +
    // 2 blit — the encoder-surface path's own program). The develop set
    // is the 17 base uniforms plus the pointwise port's 19: five slider
    // floats, two WB floats, six color-wheel offsets, four split-tone
    // values and one saturation float, with uWbManual/uSplitActive as
    // the two CPU-computed int gates.
    GLint cfaLoc = -1, sizeLoc = -1, cellLoc = -1, blackLoc = -1, rangeLoc = -1,
          hasShadingLoc = -1, shadingLoc = -1, shadingSizeLoc = -1, hrLoc = -1,
          hrGainLoc = -1, wbLoc = -1, camLoc = -1, hasProfileLoc = -1,
          exposureLoc = -1, highlightsLoc = -1, tonemapLoc = -1, oetfLoc = -1,
          blackPointLoc = -1, whitePointLoc = -1, shadowsLoc = -1,
          midtonesLoc = -1, contrastLoc = -1, wbManualLoc = -1, tempKLoc = -1,
          tintLoc = -1, wheelShadowXLoc = -1, wheelShadowYLoc = -1,
          wheelMidXLoc = -1, wheelMidYLoc = -1, wheelHighXLoc = -1,
          wheelHighYLoc = -1, splitActiveLoc = -1, splitShadowHueLoc = -1,
          splitHighlightHueLoc = -1, splitBalanceLoc = -1, saturationLoc = -1;
    GLint storeSrcLoc = -1, storeSizeLoc = -1;
    GLint blitSrcLoc = -1, blitSizeLoc = -1, blitRotLoc = -1;
    GLuint blitProgram = 0;
    GLuint cfaTex = 0, shadingTex = 0, developTex = 0, scratchTex = 0;
    GLuint developFbo = 0, scratchFbo = 0;
    GLuint outBuf = 0;
    // The encoder window surface (M5). EGL_NO_SURFACE until a successful
    // setEncoderSurface attach; destroyed on the GL thread (in
    // runSurfaceJob, on detach, and in teardown). GL-thread-owned.
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    // eglPresentationTimeANDROID (EGL_ANDROID_presentation_time), resolved
    // ONCE at init via eglGetProcAddress. Null on a device without the
    // extension — the encoder render then refuses honestly with that
    // reason named (never a crash, never a wall-clock stamp).
    PFNEGLPRESENTATIONTIMEANDROIDPROC presentationTime = nullptr;
    // Immutable storage currently allocated (0 = none yet). Per-frame work
    // touches none of these fields — glTexSubImage2D uploads data into the
    // existing storage, and the SSBO keeps its size while w/h hold.
    uint32_t cfaW = 0, cfaH = 0;
    uint32_t shadingW = 0, shadingH = 0;
    uint64_t outBytes = 0;

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
                        const std::string& fragmentSource,
                        const std::string& storeFragmentSource,
                        const std::string& blitFragmentSource,
                        std::string* detail);
    void runJob(const DevelopFrameRequest& req, DevelopFrameResult& out);
    // The shared develop pass (input contract, CPU uniforms, allocation,
    // upload, the develop draw into developFbo) — the part runJob and
    // runEncoderJob share verbatim. False with out.detail set on failure.
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
        const std::string& fragmentSource,
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

    // Capture list is explicit on purpose: [impl] and the five sources BY
    // VALUE (a `[&]` would dangle — this thread outlives open()'s frame),
    // the init-handshake locals by reference (safe: each is touched
    // exactly once, before the notify, and open() does not return until
    // the notification arrives).
    impl->glThread = std::thread(
        [impl, vertexSource, vertex310Source, fragmentSource,
         storeFragmentSource, blitFragmentSource, &initMutex, &initCv,
         &initReported, &initOk, &initDetail]() {
            std::string d;
            const bool ok = impl->initOnGlThread(
                vertexSource, vertex310Source, fragmentSource,
                storeFragmentSource, blitFragmentSource, &d);
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
        const std::string& fragmentSource,
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
    const GLubyte* v = glGetString(GL_VERSION);
    const std::string version = v != nullptr ? reinterpret_cast<const char*>(v) : "";
    const std::pair<int, int> mm = parseGlesVersion(version);
    // STORE_FRAGMENT needs ES 3.1 (fragment-shader SSBO access); the
    // develop pass itself is 300 es but its readback is not.
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
    // The blit program (M5): the SAME BLIT_FRAGMENT the Surface-mode writer
    // proved on device, linked against the plain 300es fullscreen vertex.
    // It copies the developed texture to the encoder window surface with
    // the Y flip the writer's path established (the develop pass's texel
    // row 0 is the image top; the window surface presents row 0 at the
    // bottom).
    GlShader blitFragShader;
    blitFragShader.id = compileShader(GL_FRAGMENT_SHADER, blitFragmentSource,
                                      "blit-fragment", detail);
    if (blitFragShader.id == 0) return false;
    blitProgram = linkProgram(vertShader.id, blitFragShader.id, "blit", detail);
    if (blitProgram == 0) return false;
    // Program ownership is already in the Impl (linkProgram's result is
    // assigned straight into the member); the GlShader guards deleting the
    // SHADER objects at scope exit is correct OpenGL — an attached shader
    // stays flagged-alive until its program dies.

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
    blackPointLoc = glGetUniformLocation(developProgram, "uBlackPoint");
    whitePointLoc = glGetUniformLocation(developProgram, "uWhitePoint");
    shadowsLoc = glGetUniformLocation(developProgram, "uShadows");
    midtonesLoc = glGetUniformLocation(developProgram, "uMidtones");
    contrastLoc = glGetUniformLocation(developProgram, "uContrast");
    wbManualLoc = glGetUniformLocation(developProgram, "uWbManual");
    tempKLoc = glGetUniformLocation(developProgram, "uTempK");
    tintLoc = glGetUniformLocation(developProgram, "uTint");
    wheelShadowXLoc = glGetUniformLocation(developProgram, "uWheelShadowX");
    wheelShadowYLoc = glGetUniformLocation(developProgram, "uWheelShadowY");
    wheelMidXLoc = glGetUniformLocation(developProgram, "uWheelMidX");
    wheelMidYLoc = glGetUniformLocation(developProgram, "uWheelMidY");
    wheelHighXLoc = glGetUniformLocation(developProgram, "uWheelHighX");
    wheelHighYLoc = glGetUniformLocation(developProgram, "uWheelHighY");
    splitActiveLoc = glGetUniformLocation(developProgram, "uSplitActive");
    splitShadowHueLoc = glGetUniformLocation(developProgram, "uSplitShadowHue");
    splitHighlightHueLoc = glGetUniformLocation(developProgram, "uSplitHighlightHue");
    splitBalanceLoc = glGetUniformLocation(developProgram, "uSplitBalance");
    saturationLoc = glGetUniformLocation(developProgram, "uSaturation");
    tonemapLoc = glGetUniformLocation(developProgram, "uTonemap");
    oetfLoc = glGetUniformLocation(developProgram, "uOetf");
    if (cfaLoc < 0 || sizeLoc < 0 || cellLoc < 0 || blackLoc < 0 ||
        rangeLoc < 0 || hasShadingLoc < 0 || shadingLoc < 0 ||
        shadingSizeLoc < 0 || hrLoc < 0 || hrGainLoc < 0 || wbLoc < 0 ||
        camLoc < 0 || hasProfileLoc < 0 || exposureLoc < 0 ||
        highlightsLoc < 0 || tonemapLoc < 0 || oetfLoc < 0 ||
        blackPointLoc < 0 || whitePointLoc < 0 || shadowsLoc < 0 ||
        midtonesLoc < 0 || contrastLoc < 0 || wbManualLoc < 0 ||
        tempKLoc < 0 || tintLoc < 0 || wheelShadowXLoc < 0 ||
        wheelShadowYLoc < 0 || wheelMidXLoc < 0 || wheelMidYLoc < 0 ||
        wheelHighXLoc < 0 || wheelHighYLoc < 0 || splitActiveLoc < 0 ||
        splitShadowHueLoc < 0 || splitHighlightHueLoc < 0 ||
        splitBalanceLoc < 0 || saturationLoc < 0) {
        *detail = "shader-contract: missing a develop uniform";
        return false;
    }
    storeSrcLoc = glGetUniformLocation(storeProgram, "uSrc");
    storeSizeLoc = glGetUniformLocation(storeProgram, "uSize");
    if (storeSrcLoc < 0 || storeSizeLoc < 0) {
        *detail = "shader-contract: missing uSrc/uSize";
        return false;
    }
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

    // --- static texture-unit bindings (the unit assignment never changes) --
    glUseProgram(developProgram);
    glUniform1i(cfaLoc, 0);      // CFA: GL_TEXTURE0
    glUniform1i(shadingLoc, 3);  // shading: GL_TEXTURE3
    glUseProgram(storeProgram);
    glUniform1i(storeSrcLoc, 1); // store source: GL_TEXTURE1
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
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;
    if (req.cfa == nullptr || req.meta == nullptr ||
        w == 0 || h == 0 ||
        req.cfa->size() != static_cast<size_t>(w) * h) {
        out.detail = "input: cfa size != w*h";
        return false;
    }
    out.width = w;
    out.height = h;

    // --- CPU-computed uniforms (documented copies of developCpu's own
    // stage-1/5/6 math — the harness runs the same rules; the shader
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
    // Stage 2's gate: the grade's keepVignetting AND the map's presence —
    // the same fragmentShadingApplies rule the Kotlin coverage gate uses.
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
    // Stage 14: exposure = 2^ev (developCpu's own convention; ev==0 gives
    // exactly 1.0f, matching the CPU's neutral gate).
    const float exposure = std::pow(2.0f, req.exposureEv);
    // Stages 16/17's asShot gate: the CPU's own `if (!grade.asShot)` branch,
    // hoisted to the int uniform the shader branches on (1 = run the manual
    // temp/tint path). Same shape as the shading gate above.
    const int wbManual = req.asShot ? 0 : 1;
    // Stage 19's own guard: the CPU's `if (splitShadowHue >= 0 ||
    // splitHighlightHue >= 0)` branch, hoisted to the int uniform.
    const int splitActive =
        (req.splitShadowHue >= 0.0f || req.splitHighlightHue >= 0.0f) ? 1 : 0;

    // --- storage allocation ONCE per resolution/resource change ------------
    // Immutable glTexStorage2D + per-frame glTexSubImage2D uploads: the
    // audit's finding against glTexImage2D-per-frame. The shading map is
    // per-CLIP (constant across frames), so its texture uploads once and
    // stays; the CFA is per-frame.
    if (cfaW != w || cfaH != h) {
        // (Re)allocate the resolution-dependent storage. Any previous
        // allocation is deleted first (a resolution change mid-export is
        // impossible for one clip, but the session outlives clips by
        // design).
        if (cfaTex != 0) { glDeleteTextures(1, &cfaTex); cfaTex = 0; }
        if (developTex != 0) { glDeleteTextures(1, &developTex); developTex = 0; }
        if (scratchTex != 0) { glDeleteTextures(1, &scratchTex); scratchTex = 0; }
        if (developFbo != 0) { glDeleteFramebuffers(1, &developFbo); developFbo = 0; }
        if (scratchFbo != 0) { glDeleteFramebuffers(1, &scratchFbo); scratchFbo = 0; }
        if (outBuf != 0) { glDeleteBuffers(1, &outBuf); outBuf = 0; }
        outBytes = 0;
        cfaW = 0; cfaH = 0;

        // CFA texture: GL_R16UI, the RAW samples in their native values
        // (normalization is stage 1 IN-SHADER — that is the point of the
        // fused pass; the CPU-computed uBlack/uRange carry the
        // frame-preferred levels). Row-major CPU order: texture row y =
        // CFA row y.
        glGenTextures(1, &cfaTex);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, cfaTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_R16UI,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(R16UI)", &out.detail) != GL_NO_ERROR) return false;

        // develop FBO: RGBA32F attachment (EXT_color_buffer_float, gated
        // in THIS context at open).
        glGenTextures(1, &developTex);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, developTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(RGBA32F)", &out.detail) != GL_NO_ERROR) return false;
        glGenFramebuffers(1, &developFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               developTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "develop fbo incomplete (RGBA32F not renderable here)";
            return false;
        }

        // scratch RGBA8 (the store pass's throwaway target — never the
        // develop FBO, whose texture is the source: a feedback loop). The
        // RGBA8 contents are never read; the SSBO is the real output.
        glGenTextures(1, &scratchTex);
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(GL_TEXTURE_2D, scratchTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8,
                       static_cast<GLsizei>(w), static_cast<GLsizei>(h));
        if (glErrorAfter("glTexStorage2D(RGBA8)", &out.detail) != GL_NO_ERROR) return false;
        glGenFramebuffers(1, &scratchFbo);
        glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               scratchTex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            out.detail = "store fbo incomplete";
            return false;
        }

        // SSBO: w*h*3 floats, STREAM_READ (the store pass writes it, the
        // map reads it back — the harness's exact sizes and usage).
        const uint64_t bytes = static_cast<uint64_t>(w) * h * 3 * sizeof(float);
        glGenBuffers(1, &outBuf);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
        glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(bytes),
                     nullptr, GL_STREAM_READ);
        if (glErrorAfter("glBufferData(out)", &out.detail) != GL_NO_ERROR) return false;
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf);
        outBytes = bytes;
        cfaW = w;
        cfaH = h;
    }

    // The shading map: per-CLIP, so once per session (the session serves
    // one export's frames; the map is constant across frames of one clip,
    // and a map appearing mid-session is not possible — the meta is
    // per-clip). The 1x1 dummy covers the absent-map case (the shader only
    // fetches when uHasShading==1, so the dummy is never read; the
    // sampler uniform is always set, keeping the program's uniform set
    // complete either way).
    if (shadingTex == 0) {
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
            if (glErrorAfter("glTexStorage2D(R32F shading)", &out.detail) != GL_NO_ERROR) return false;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            static_cast<GLsizei>(req.meta->shadingW),
                            static_cast<GLsizei>(req.meta->shadingH * 4),
                            GL_RED, GL_FLOAT, req.meta->lensShading.data());
            if (glErrorAfter("glTexSubImage2D(R32F shading)", &out.detail) != GL_NO_ERROR) return false;
            shadingW = req.meta->shadingW;
            shadingH = req.meta->shadingH;
        } else {
            const float dummy = 1.0f;
            glTexStorage2D(GL_TEXTURE_2D, 1, GL_R32F, 1, 1);
            if (glErrorAfter("glTexStorage2D(R32F dummy)", &out.detail) != GL_NO_ERROR) return false;
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, 1, 1, GL_RED, GL_FLOAT, &dummy);
            if (glErrorAfter("glTexSubImage2D(R32F dummy)", &out.detail) != GL_NO_ERROR) return false;
            shadingW = 1;
            shadingH = 1;
        }
    }

    // --- per-frame upload: the CFA, into the immutable storage -------------
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, cfaTex);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                    static_cast<GLsizei>(w), static_cast<GLsizei>(h),
                    GL_RED_INTEGER, GL_UNSIGNED_SHORT, req.cfa->data());
    if (glErrorAfter("glTexSubImage2D(R16UI)", &out.detail) != GL_NO_ERROR) return false;

    // --- draw 1: the develop pass (the fused covered stages + OETF tail) ---
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
    // The ten pointwise stages' raw grade values (each 0.0f is the CPU's
    // own neutral skip) + the two hoisted int gates.
    glUniform1f(blackPointLoc, req.blackPoint);
    glUniform1f(whitePointLoc, req.whitePoint);
    glUniform1f(shadowsLoc, req.shadows);
    glUniform1f(midtonesLoc, req.midtones);
    glUniform1f(contrastLoc, req.contrast);
    glUniform1i(wbManualLoc, wbManual);
    glUniform1f(tempKLoc, req.tempK);
    glUniform1f(tintLoc, req.tint);
    glUniform1f(wheelShadowXLoc, req.wheelShadowX);
    glUniform1f(wheelShadowYLoc, req.wheelShadowY);
    glUniform1f(wheelMidXLoc, req.wheelMidX);
    glUniform1f(wheelMidYLoc, req.wheelMidY);
    glUniform1f(wheelHighXLoc, req.wheelHighX);
    glUniform1f(wheelHighYLoc, req.wheelHighY);
    glUniform1i(splitActiveLoc, splitActive);
    glUniform1f(splitShadowHueLoc, req.splitShadowHue);
    glUniform1f(splitHighlightHueLoc, req.splitHighlightHue);
    glUniform1f(splitBalanceLoc, req.splitBalance);
    glUniform1f(saturationLoc, req.saturation);
    glUniform1i(tonemapLoc, req.tonemap);
    glUniform1i(oetfLoc, 1);  // the export shape: linearToRec709 in-shader
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(develop)", &out.detail) != GL_NO_ERROR) return false;
    // The developed frame now lives in developTex (RGBA32F, OETF applied).
    // runJob continues with the store pass + SSBO readback; runEncoderJob
    // blits this texture straight to the encoder window surface instead.
    return true;
}

void DevelopSession::Impl::runJob(const DevelopFrameRequest& req,
                                  DevelopFrameResult& out) {
    const uint32_t w = req.info.width;
    const uint32_t h = req.info.height;

    if (!developPass(req, out)) return;

    // --- draw 2: the store pass into the SSBO (never glReadPixels) ---------
    glUseProgram(storeProgram);
    glActiveTexture(GL_TEXTURE1);  // the develop texture, unit 1 (the same
                                   // unit the develop pass wrote; the sampler
                                   // uniform was set once at open)
    glBindTexture(GL_TEXTURE_2D, developTex);
    glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    glUniform2i(storeSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    // Re-bind the SSBO to indexed binding 0 before EVERY store draw (the
    // harness binds once per call; a persistent session must not rely on
    // the allocation branch's binding surviving unrelated state changes —
    // one redundant glBindBufferBase per frame is free next to the draw).
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(store)", &out.detail) != GL_NO_ERROR) return;

    // --- readback: a MAPPED buffer, NEVER glReadPixels ---------------------
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf);
    void* mapped = glMapBufferRange(
        GL_SHADER_STORAGE_BUFFER, 0, static_cast<GLsizeiptr>(outBytes),
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
        // nativeExportFrame's pack loop does after linearToRec709: the
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
    glBindTexture(GL_TEXTURE_2D, developTex);
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

DevelopFrameResult DevelopSession::renderFrame(const DevelopFrameRequest& req) {
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
    impl_->pendingReq = &req;
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

DevelopFrameResult DevelopSession::renderFrameToEncoder(
        const DevelopFrameRequest& req, int64_t ptsUs, int rotationDeg) {
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
    impl_->pendingReq = &req;
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
    if (scratchFbo != 0) { glDeleteFramebuffers(1, &scratchFbo); scratchFbo = 0; }
    if (developFbo != 0) { glDeleteFramebuffers(1, &developFbo); developFbo = 0; }
    if (scratchTex != 0) { glDeleteTextures(1, &scratchTex); scratchTex = 0; }
    if (developTex != 0) { glDeleteTextures(1, &developTex); developTex = 0; }
    if (shadingTex != 0) { glDeleteTextures(1, &shadingTex); shadingTex = 0; }
    if (cfaTex != 0) { glDeleteTextures(1, &cfaTex); cfaTex = 0; }
    if (outBuf != 0) { glDeleteBuffers(1, &outBuf); outBuf = 0; }
    if (developProgram != 0) { glDeleteProgram(developProgram); developProgram = 0; }
    if (storeProgram != 0) { glDeleteProgram(storeProgram); storeProgram = 0; }
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
