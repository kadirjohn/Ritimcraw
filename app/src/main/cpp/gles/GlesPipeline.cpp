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

// Link [vert] + [frag] into one program (the fragment chain's two
// programs each link exactly one vertex + one fragment). Returns the GL
// name on success; 0 on failure (shaders are NOT deleted — the caller's
// GlShader guards own them; the program, when 0, was never created or
// already deleted).
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

    // Compute availability. GL_MAX_COMPUTE_WORK_GROUP_COUNT is an INDEXED
    // enum (per-axis): the GL spec requires querying it with
    // glGetIntegeri_v per axis, NEVER plain glGetIntegerv. The old code
    // queried it non-indexed — an invalid query on many drivers, which
    // can fail and leave the zero-initialized array at 0, reporting
    // compute=0 on hardware that fully supports ES 3.1 compute (the
    // owner's Xiaomi 15 Ultra / Adreno 830 most likely hit exactly
    // this). That device diagnosis was UNRELIABLE; the indexed query
    // re-opens the compute path and makes the probe re-testable.
    // Rule: computeOk = all three per-axis dispatch counts > 0 —
    // glDispatchCompute's grid is (ceil(w/8), ceil(h/8), 1), so a zero
    // limit on ANY axis caps the dispatch on that axis; requiring all
    // three keeps the flag honest without guessing which axis matters.
    // (GL_MAX_COMPUTE_WORK_GROUP_SIZE is queried the same indexed way
    // for the log; the 8x8 local size is tiny, so it is not part of the
    // gate.)
    GLint groups[3] = {0, 0, 0};
    GLint groupSize[3] = {0, 0, 0};
    for (GLint axis = 0; axis < 3; ++axis) {
        glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_COUNT, axis, &groups[axis]);
        glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, axis, &groupSize[axis]);
    }
    caps.computeOk = groups[0] > 0 && groups[1] > 0 && groups[2] > 0;
    caps.maxWorkGroupCount.assign(groups, groups + 3);
    caps.maxWorkGroupSize.assign(groupSize, groupSize + 3);

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

    // SSBO binding: GLSL ES 3.10 supports explicit layout(binding=N) on
    // shader storage blocks, and the shader declares InBuf at binding 0
    // and OutBuf at binding 1 (layout(std430, binding=...)). The C++ side
    // only needs the core ES 3.1 glBindBufferBase to attach the buffers
    // to those SAME binding points. glShaderStorageBlockBinding is NOT an
    // ES command at all (it is desktop GL 4.2+), so the old
    // eglGetProcAddress workaround could only ever return null on ES and
    // stopped the parity run there — the block-index query it fed is
    // gone too (the binding points now come from the shader text).
    const GLint sizeLoc = glGetUniformLocation(program.id, "uSize");
    const GLint cellLoc = glGetUniformLocation(program.id, "uCell");
    if (sizeLoc < 0 || cellLoc < 0) {
        rep.detail = "shader-contract: missing uSize/uCell";
        return rep;
    }
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

ParityReport developFragmentParity(const std::string& vertexSource,
                                    const std::string& vertex310Source,
                                    const std::string& fragmentSource,
                                    const std::string& storeFragmentSource,
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
    const size_t n = static_cast<size_t>(w) * h;

    // --- CPU reference: developLinear itself, untouched ------------------
    const std::vector<uint16_t> ref =
        rawedit::developLinear(cfa.data(), info, meta);
    if (ref.size() != n * 3) {
        rep.detail = "cpu-reference: developLinear returned " +
                     std::to_string(ref.size()) + " samples (expected " +
                     std::to_string(n * 3) + ")";
        return rep;
    }

    // --- CPU-computed uniforms (documented copies of developLinear's own
    // stage-1 / stage-5 math — the shader applies them in-hardware) ------
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
    const float invNeutral[3] = {
        meta.asShotNeutral[0] > 0.0f ? 1.0f / meta.asShotNeutral[0] : 1.0f,
        meta.asShotNeutral[1] > 0.0f ? 1.0f / meta.asShotNeutral[1] : 1.0f,
        meta.asShotNeutral[2] > 0.0f ? 1.0f / meta.asShotNeutral[2] : 1.0f,
    };
    int cell[4];
    cellFor(info, cell);

    // --- EGL + capability gates (THIS context, not the probe's) ----------
    EglSession egl;
    if (!egl.open(&rep.detail)) {
        rep.detail = "egl: " + rep.detail;
        return rep;
    }
    const GLubyte* v = glGetString(GL_VERSION);
    const std::string version = v != nullptr ? reinterpret_cast<const char*>(v) : "";
    const std::pair<int, int> mm = parseGlesVersion(version);
    // STORE_FRAGMENT needs ES 3.1 (fragment-shader SSBO access); the
    // develop pass itself is 300 es but its readback is not.
    if (mm.first < 3 || (mm.first == 3 && mm.second < 1)) {
        rep.detail = "context-not-31: " + version;
        return rep;
    }
    const GLubyte* e = glGetString(GL_EXTENSIONS);
    const std::string extensions = e != nullptr ? reinterpret_cast<const char*>(e) : "";
    if (extensions.find("GL_EXT_color_buffer_float") == std::string::npos) {
        rep.detail = "no EXT_color_buffer_float (RGBA32F not renderable)";
        return rep;
    }

    // --- CPU-computed uniform uploads need the programs first ------------
    GlShader vertShader;
    vertShader.id = compileShader(GL_VERTEX_SHADER, vertexSource, "vertex", &rep.detail);
    if (vertShader.id == 0) return rep;

    GlShader fragShader;
    fragShader.id = compileShader(GL_FRAGMENT_SHADER, fragmentSource, "develop-fragment", &rep.detail);
    if (fragShader.id == 0) return rep;

    GlProgram developProgram;
    developProgram.id = linkProgram(vertShader.id, fragShader.id, "develop", &rep.detail);
    if (developProgram.id == 0) return rep;

    GlShader vert310Shader;
    vert310Shader.id = compileShader(GL_VERTEX_SHADER, vertex310Source, "vertex310", &rep.detail);
    if (vert310Shader.id == 0) return rep;

    GlShader storeFragShader;
    storeFragShader.id = compileShader(GL_FRAGMENT_SHADER, storeFragmentSource, "store-fragment", &rep.detail);
    if (storeFragShader.id == 0) return rep;

    GlProgram storeProgram;
    storeProgram.id = linkProgram(vert310Shader.id, storeFragShader.id, "store", &rep.detail);
    if (storeProgram.id == 0) return rep;

    // --- CFA texture: GL_R16UI, the RAW samples in their native values --
    // (normalization is stage 1 IN-SHADER — that is the point of the fused
    // pass; the CPU-computed uBlack/uRange carry the frame-preferred
    // levels). Row-major CPU order: texture row y = CFA row y.
    GLuint cfaTex = 0;
    glGenTextures(1, &cfaTex);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, cfaTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_R16UI, static_cast<GLsizei>(w),
                 static_cast<GLsizei>(h), 0, GL_RED_INTEGER, GL_UNSIGNED_SHORT,
                 cfa.data());
    if (glErrorAfter("glTexImage2D(R16UI)", &rep.detail) != GL_NO_ERROR) return rep;

    // --- develop FBO: RGBA32F attachment --------------------------------
    GLuint developTex = 0;
    glGenTextures(1, &developTex);
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_2D, developTex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, static_cast<GLsizei>(w),
                 static_cast<GLsizei>(h), 0, GL_RGBA, GL_FLOAT, nullptr);
    if (glErrorAfter("glTexImage2D(RGBA32F)", &rep.detail) != GL_NO_ERROR) return rep;

    GLuint developFbo = 0;
    glGenFramebuffers(1, &developFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, developFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           developTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        rep.detail = "develop fbo incomplete (RGBA32F not renderable here)";
        return rep;
    }

    // --- draw 1: the develop pass (parity shape: profile off, exposure
    // neutral, no OETF — exactly developLinear's stage list) -------------
    glUseProgram(developProgram.id);
    glViewport(0, 0, static_cast<GLsizei>(w), static_cast<GLsizei>(h));
    const GLint cfaLoc = glGetUniformLocation(developProgram.id, "uCfa");
    const GLint sizeLoc = glGetUniformLocation(developProgram.id, "uSize");
    const GLint cellLoc = glGetUniformLocation(developProgram.id, "uCell");
    const GLint blackLoc = glGetUniformLocation(developProgram.id, "uBlack");
    const GLint rangeLoc = glGetUniformLocation(developProgram.id, "uRange");
    const GLint wbLoc = glGetUniformLocation(developProgram.id, "uWb");
    const GLint camLoc = glGetUniformLocation(developProgram.id, "uCam2rgb");
    const GLint hasProfileLoc = glGetUniformLocation(developProgram.id, "uHasProfile");
    const GLint exposureLoc = glGetUniformLocation(developProgram.id, "uExposure");
    const GLint oetfLoc = glGetUniformLocation(developProgram.id, "uOetf");
    if (cfaLoc < 0 || sizeLoc < 0 || cellLoc < 0 || blackLoc < 0 ||
        rangeLoc < 0 || wbLoc < 0 || camLoc < 0 || hasProfileLoc < 0 ||
        exposureLoc < 0 || oetfLoc < 0) {
        rep.detail = "shader-contract: missing a develop uniform";
        return rep;
    }
    glUniform1i(cfaLoc, 0);  // texture unit 0 (GL_TEXTURE0 above)
    glUniform2i(sizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glUniform4i(cellLoc, cell[0], cell[1], cell[2], cell[3]);
    glUniform4f(blackLoc, black[0], black[1], black[2], black[3]);
    glUniform4f(rangeLoc, range[0], range[1], range[2], range[3]);
    glUniform3f(wbLoc, invNeutral[0], invNeutral[1], invNeutral[2]);
    const float identity[9] = {1.0f, 0.0f, 0.0f,
                               0.0f, 1.0f, 0.0f,
                               0.0f, 0.0f, 1.0f};
    glUniformMatrix3fv(camLoc, 1, GL_TRUE, identity);  // transpose=GL_TRUE: row-major upload, the CPU contract
    glUniform1i(hasProfileLoc, 0);
    glUniform1f(exposureLoc, 1.0f);
    glUniform1i(oetfLoc, 0);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(develop)", &rep.detail) != GL_NO_ERROR) return rep;

    // --- draw 2: the store pass, to a THROWAWAY RGBA8 FBO ----------------
    // (never to the develop FBO — the source texture is still attached
    // there; drawing to it would be a feedback loop). The RGBA8 contents
    // are never read; the SSBO is the real output.
    GLuint scratchTex = 0;
    glGenTextures(1, &scratchTex);
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, scratchTex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(w),
                 static_cast<GLsizei>(h), 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
    if (glErrorAfter("glTexImage2D(RGBA8)", &rep.detail) != GL_NO_ERROR) return rep;
    GLuint scratchFbo = 0;
    glGenFramebuffers(1, &scratchFbo);
    glBindFramebuffer(GL_FRAMEBUFFER, scratchFbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                           scratchTex, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        rep.detail = "store fbo incomplete";
        return rep;
    }

    GlBuffer outBuf;
    glGenBuffers(1, &outBuf.id);
    glBindBuffer(GL_SHADER_STORAGE_BUFFER, outBuf.id);
    glBufferData(GL_SHADER_STORAGE_BUFFER,
                 static_cast<GLsizeiptr>(n * 3 * sizeof(float)),
                 nullptr, GL_STREAM_READ);
    if (glErrorAfter("glBufferData(out)", &rep.detail) != GL_NO_ERROR) return rep;

    glUseProgram(storeProgram.id);
    glActiveTexture(GL_TEXTURE1);  // the develop texture, unit 1 (same unit the develop pass wrote; sampler uniform below)
    glBindTexture(GL_TEXTURE_2D, developTex);
    const GLint srcLoc = glGetUniformLocation(storeProgram.id, "uSrc");
    const GLint storeSizeLoc = glGetUniformLocation(storeProgram.id, "uSize");
    if (srcLoc < 0 || storeSizeLoc < 0) {
        rep.detail = "shader-contract: missing uSrc/uSize";
        return rep;
    }
    glUniform1i(srcLoc, 1);  // texture unit 1
    glUniform2i(storeSizeLoc, static_cast<GLint>(w), static_cast<GLint>(h));
    glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, outBuf.id);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (glErrorAfter("glDrawArrays(store)", &rep.detail) != GL_NO_ERROR) return rep;
    glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);

    // --- readback: a MAPPED buffer, NEVER glReadPixels -------------------
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
        // The shader applied WB (uWb) in-hardware; the tail only does what
        // developLinear does AFTER WB: clamp [0,1] and quantize uint16.
        float g = gpu[i];
        g = std::min(std::max(g, 0.0f), 1.0f);
        const int gi = static_cast<int>(std::lround(g * 65535.0f));
        const int diff = gi > static_cast<int>(ref[i])
            ? gi - static_cast<int>(ref[i])
            : static_cast<int>(ref[i]) - gi;
        if (diff > maxDiff) maxDiff = diff;
        if (diff > 0) ++mismatches;
    }
    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);

    // --- teardown (scope guards own the shaders/programs/buffers; the
    // textures + FBOs are deleted here in reverse creation order) --------
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &scratchFbo);
    glDeleteFramebuffers(1, &developFbo);
    glDeleteTextures(1, &scratchTex);
    glDeleteTextures(1, &developTex);
    glDeleteTextures(1, &cfaTex);

    rep.ran = true;
    rep.maxDiffLsb = maxDiff;
    rep.mismatchCount = mismatches;
    rep.samples = static_cast<long long>(n) * 3;
    rep.detail = version;  // device context for the log line
    return rep;
}

} // namespace gles
