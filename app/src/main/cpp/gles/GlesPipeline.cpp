// GLES context + capability probe + single-frame demosaic parity
// (owner request 2026-11-12, deliverable C). See GlesPipeline.h for the
// grafika-topology contract and the honesty rules. This translation unit
// is Android-only: EGL/GLES headers exist only in the NDK, so the desktop
// build never compiles it (host-side coverage lives in the Kotlin
// shader-source validator + parity decision rules and their JVM tests).

#include "GlesPipeline.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <utility>

#include <GLES3/gl31.h>

#include <rawedit/Develop.h>

namespace gles {
namespace {

// "OpenGL ES 3.2 ..." -> (3, 2). A malformed string leaves (0, 0): the
// probe never guesses a version.
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

// layoutFor (core/src/Develop.cpp) flattened row-major:
// (cell[0][0], cell[0][1], cell[1][0], cell[1][1]). The shader receives
// exactly these four site codes (0=R 1=G 2=B), one per 2x2 cell slot.
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

// GL object guards: each deletes on scope exit. They are always declared
// AFTER the EglSession in a scope, so they are destroyed BEFORE it (C++
// destroys in reverse declaration order) — GL calls in a destructor must
// still have a current context.
struct GlShader {
    GLuint id = 0;
    ~GlShader() { if (id != 0) glDeleteShader(id); }
};

struct GlProgram {
    GLuint id = 0;
    ~GlProgram() { if (id != 0) glDeleteProgram(id); }
};

struct GlBuffer {
    GLuint id = 0;
    ~GlBuffer() { if (id != 0) glDeleteBuffers(1, &id); }
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

// glShaderStorageBlockBinding is a CORE ES 3.1 command but a known NDK
// gap: this NDK's gl31.h does not declare it and the platform stub
// libGLESv3.so does not export it (verified against NDK 30.0.16248370 —
// no header declaration at any API level, no stub symbol at 31/34/35).
// GLSL ES 3.10 has no layout(binding=) for shader storage blocks, so the
// driver's implementation is fetched through eglGetProcAddress — the
// standard Android workaround. A null pointer is reported honestly and
// the parity run stops there (the CPU render path stays the default).
using ShaderStorageBlockBindingFn = void (*)(GLuint, GLuint, GLuint);

ShaderStorageBlockBindingFn loadShaderStorageBlockBinding() {
    return reinterpret_cast<ShaderStorageBlockBindingFn>(
        eglGetProcAddress("glShaderStorageBlockBinding"));
}

} // namespace

EglSession::~EglSession() { release(); }

bool EglSession::open(std::string* detail) {
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
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_NONE,
        };
        EGLConfig config = nullptr;
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
        surface = eglCreatePbufferSurface(display, config, surfAttribs);
        if (surface == EGL_NO_SURFACE) goto fail;
    }
    step = "eglMakeCurrent";
    if (!eglMakeCurrent(display, surface, surface, context)) goto fail;
    return true;
fail:
    if (detail != nullptr) *detail = step;
    return false;
}

void EglSession::release() {
    // Exact reverse of open() (grafika release() topology). Safe on a
    // partially-opened session: every handle starts as the *_NO_* value.
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
        if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
        eglReleaseThread();
        eglTerminate(display);
    }
    surface = EGL_NO_SURFACE;
    context = EGL_NO_CONTEXT;
    display = EGL_NO_DISPLAY;
}

Capabilities probeCapabilities(std::string* detail) {
    Capabilities caps;
    EglSession egl;
    if (!egl.open(detail)) {
        // The context never came up: every capability stays false/empty —
        // the honest "probe failed at <step>" report, never a guess.
        return caps;
    }
    caps.contextOk = true;

    const GLubyte* v = glGetString(GL_VERSION);
    const GLubyte* r = glGetString(GL_RENDERER);
    const GLubyte* e = glGetString(GL_EXTENSIONS);
    if (v != nullptr) caps.version = reinterpret_cast<const char*>(v);
    if (r != nullptr) caps.renderer = reinterpret_cast<const char*>(r);
    if (e != nullptr) caps.extensions = reinterpret_cast<const char*>(e);

    const std::pair<int, int> mm = parseGlesVersion(caps.version);
    caps.glesMajor = mm.first;
    caps.glesMinor = mm.second;

    GLint maxTex = 0;
    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTex);
    caps.maxTextureSize = maxTex;

    // Compute availability: an ES 3.1+ context with a nonzero dispatch
    // limit (GL_MAX_COMPUTE_WORK_GROUP_COUNT is core 3.1; on a 3.0
    // context the query is invalid and groups stay 0).
    GLint groups[3] = {0, 0, 0};
    glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_COUNT, groups);
    caps.computeOk = groups[0] > 0;

    caps.floatFboOk = caps.extensions.find("GL_EXT_color_buffer_float") != std::string::npos;

    return caps;
}

ParityReport demosaicParity(const std::string& computeSource,
                            const std::vector<uint16_t>& cfa,
                            const rawedit::FrameInfo& info,
                            const rawedit::RawMeta& meta) {
    ParityReport rep;
    const uint32_t w = info.width;
    const uint32_t h = info.height;
    if (w == 0 || h == 0 || cfa.size() != static_cast<size_t>(w) * h) {
        rep.detail = "input: cfa size != w*h";
        return rep;
    }

    // --- CPU stage 1: black/white normalization -------------------------
    // A documented copy of developLinear stage 1 (core/src/Develop.cpp):
    // same frame-preferred rule, same slot mapping, same clamp. The GPU
    // path must see exactly what demosaicBilinear sees in the reference.
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> norm(n);
    const bool useFrameBlack = info.dynamicBlack[0] >= 0;
    const float white = info.dynamicWhite > 0
        ? static_cast<float>(info.dynamicWhite)
        : static_cast<float>(meta.white);
    float black[4];
    for (int slot = 0; slot < 4; ++slot)
        black[slot] = useFrameBlack
            ? static_cast<float>(info.dynamicBlack[slot])
            : static_cast<float>(meta.black[slot]);
    float range[4];
    for (int slot = 0; slot < 4; ++slot)
        range[slot] = white > black[slot] ? white - black[slot] : 1.0f;
    for (uint32_t y = 0; y < h; ++y) {
        for (uint32_t x = 0; x < w; ++x) {
            const int slot = (static_cast<int>(y & 1) << 1) + static_cast<int>(x & 1);
            const float v =
                (static_cast<float>(cfa[y * static_cast<size_t>(w) + x]) -
                 black[slot]) / range[slot];
            norm[y * static_cast<size_t>(w) + x] =
                std::min(std::max(v, 0.0f), 1.0f);
        }
    }

    // CPU stage 5 + quantize tail (also a documented copy of developLinear
    // stage 5), applied to the GPU readback below: gains, clamp, x65535,
    // lround. The comparison domain is uint16 — the production DNG path's
    // own domain — so sub-LSB driver float noise is absorbed by rounding.
    const float invNeutral[3] = {
        meta.asShotNeutral[0] > 0.0f ? 1.0f / meta.asShotNeutral[0] : 1.0f,
        meta.asShotNeutral[1] > 0.0f ? 1.0f / meta.asShotNeutral[1] : 1.0f,
        meta.asShotNeutral[2] > 0.0f ? 1.0f / meta.asShotNeutral[2] : 1.0f,
    };

    // --- CPU reference: developLinear itself, untouched ------------------
    // The exact function the DNG path ships — zero drift risk in the
    // reference (stages 1 + 4 + 5 + the same quantize).
    const std::vector<uint16_t> ref =
        rawedit::developLinear(cfa.data(), info, meta);
    if (ref.size() != n * 3) {
        rep.detail = "cpu-reference: developLinear returned " +
                     std::to_string(ref.size()) + " samples (expected " +
                     std::to_string(n * 3) + ")";
        return rep;
    }

    // --- GPU pass ---------------------------------------------------------
    EglSession egl;
    if (!egl.open(&rep.detail)) {
        rep.detail = "egl: " + rep.detail;
        return rep;
    }

    // Compute needs an ES 3.1+ context (GL_COMPUTE_SHADER +
    // glDispatchCompute are 3.1 core). Anything less is reported, never
    // guessed past.
    const GLubyte* v = glGetString(GL_VERSION);
    const std::string version = v != nullptr ? reinterpret_cast<const char*>(v) : "";
    const std::pair<int, int> mm = parseGlesVersion(version);
    if (mm.first < 3 || (mm.first == 3 && mm.second < 1)) {
        rep.detail = "context-not-31: " + version;
        return rep;
    }

    // The ES 3.1 block-binding command, fetched through the NDK-gap
    // workaround above. The lookup is driver-global; a null pointer is
    // a real, reportable device fact, never guessed past.
    const ShaderStorageBlockBindingFn ssboBind =
        loadShaderStorageBlockBinding();
    if (ssboBind == nullptr) {
        rep.detail = "eglGetProcAddress: glShaderStorageBlockBinding";
        return rep;
    }

    GlShader shader;
    shader.id = glCreateShader(GL_COMPUTE_SHADER);
    if (shader.id == 0) {
        rep.detail = "glCreateShader(compute)";
        return rep;
    }
    const char* src = computeSource.c_str();
    glShaderSource(shader.id, 1, &src, nullptr);
    glCompileShader(shader.id);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader.id, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[512] = {0};
        GLsizei len = 0;
        glGetShaderInfoLog(shader.id, sizeof(log) - 1, &len, log);
        rep.detail = std::string("shader-compile: ") + log;
        return rep;
    }

    GlProgram program;
    program.id = glCreateProgram();
    if (program.id == 0) {
        rep.detail = "glCreateProgram";
        return rep;
    }
    glAttachShader(program.id, shader.id);
    glLinkProgram(program.id);
    GLint linked = GL_FALSE;
    glGetProgramiv(program.id, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[512] = {0};
        GLsizei len = 0;
        glGetProgramInfoLog(program.id, sizeof(log) - 1, &len, log);
        rep.detail = std::string("shader-link: ") + log;
        return rep;
    }
    glUseProgram(program.id);

    // Input SSBO: the normalized CFA (scalar float array — std430 packs
    // scalars tightly; no textures, no samplers, no image units, no float
    // framebuffers: GLES 3.1 core only).
    GlBuffer inBuf;
    glGenBuffers(1, &inBuf.id);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, inBuf.id);
    glBufferData(GL_SHADER_STORAGE_BUFFER,
                 static_cast<GLsizeiptr>(n * sizeof(float)),
                 norm.data(), GL_STATIC_DRAW);

    // Output SSBO: scene-linear RGB, 3 tightly packed floats per pixel,
    // white balance NOT applied (that tail runs on the CPU readback so
    // the comparison isolates stage 4).
    GlBuffer outBuf;
    glGenBuffers(1, &outBuf.id);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf.id);
    glBufferData(GL_SHADER_STORAGE_BUFFER,
                 static_cast<GLsizeiptr>(n * 3 * sizeof(float)),
                 nullptr, GL_STREAM_READ);
    {
        std::string errDetail;
        if (glErrorAfter("glBufferData", &errDetail) != GL_NO_ERROR) {
            rep.detail = errDetail;
            return rep;
        }
    }

    // GLSL ES 3.10 has no layout(binding=) for shader storage blocks, so
    // the block indices are bound from here with the core ES 3.1 command
    // (fetched via eglGetProcAddress — see the NDK-gap note above).
    const GLuint inIndex = glGetProgramResourceIndex(
        program.id, GL_SHADER_STORAGE_BLOCK, "InBuf");
    const GLuint outIndex = glGetProgramResourceIndex(
        program.id, GL_SHADER_STORAGE_BLOCK, "OutBuf");
    const GLint sizeLoc = glGetUniformLocation(program.id, "uSize");
    const GLint cellLoc = glGetUniformLocation(program.id, "uCell");
    if (inIndex == GL_INVALID_INDEX || outIndex == GL_INVALID_INDEX ||
        sizeLoc < 0 || cellLoc < 0) {
        rep.detail = "shader-contract: missing InBuf/OutBuf/uSize/uCell";
        return rep;
    }
    ssboBind(program.id, inIndex, 0);
    ssboBind(program.id, outIndex, 1);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, inBuf.id);
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, outBuf.id);

    int cell[4];
    cellFor(info, cell);
    glUniform2i(sizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform4i(cellLoc, cell[0], cell[1], cell[2], cell[3]);

    // The compute shader declares local_size_x/y = 8.
    glDispatchCompute((GLuint)((w + 7) / 8), (GLuint)((h + 7) / 8), 1);
    {
        std::string errDetail;
        if (glErrorAfter("glDispatchCompute", &errDetail) != GL_NO_ERROR) {
            rep.detail = errDetail;
            return rep;
        }
    }
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);

    // --- readback: a MAPPED buffer, NOT glReadPixels ---------------------
    // The prototype uses no framebuffer and no glReadPixels anywhere; the
    // production GPU path (later deliverables) keeps the result on the
    // GPU, so no readback path exists to migrate.
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf.id);
    void* mapped = glMapBufferRange(GL_SHADER_STORAGE_BUFFER, 0,
                                    static_cast<GLsizeiptr>(n * 3 * sizeof(float)),
                                    GL_MAP_READ_BIT);
    if (mapped == nullptr) {
        glErrorAfter("glMapBufferRange", &rep.detail);
        if (rep.detail.empty()) rep.detail = "glMapBufferRange returned null";
        return rep;
    }
    const float* gpu = static_cast<const float*>(mapped);
    int maxDiff = 0;
    long long mismatches = 0;
    for (size_t i = 0; i < n * 3; ++i) {
        float g = gpu[i] * invNeutral[i % 3];
        g = std::min(std::max(g, 0.0f), 1.0f);
        const int gi = static_cast<int>(std::lround(g * 65535.0f));
        const int diff = gi > static_cast<int>(ref[i])
            ? gi - static_cast<int>(ref[i])
            : static_cast<int>(ref[i]) - gi;
        if (diff > maxDiff) maxDiff = diff;
        if (diff > 0) ++mismatches;
    }
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);

    rep.ran = true;
    rep.maxDiffLsb = maxDiff;
    rep.mismatchCount = mismatches;
    rep.samples = static_cast<long long>(n) * 3;
    rep.detail = version;  // device context for the log line
    return rep;
}

} // namespace gles
