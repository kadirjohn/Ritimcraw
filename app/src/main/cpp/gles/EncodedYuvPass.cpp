// See EncodedYuvPass.h.

#include "EncodedYuvPass.h"

#include "GlesPipeline.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>

#include <GLES3/gl3.h>

#include <rawedit/Color.h>

namespace gles {
namespace {

const char* kVertex = R"(#version 300 es
void main() {
    // One oversized triangle covering the viewport.
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
)";

// The constants and branches below are core/src/Transfer.cpp and core/src/Color.cpp, line for line.
const char* kFragment = R"(#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;

uniform usampler2D uY;     // R8UI or R16UI: the raw luma code words
uniform usampler2D uC;     // RG8UI or RG16UI: the raw interleaved Cb,Cr code words
uniform int uShift;        // 16 - bitDepth for 16-bit words, 0 for bytes
uniform int uReduce;       // N >= 1: reduce by N first (box mean in the code domain, rounded)
uniform ivec2 uSrcSize;    // luma plane size
uniform ivec2 uChromaSize; // chroma plane size (ceil(w/2), ceil(h/2))
uniform vec4 uRange;       // offY, rangeY, offC, rangeC (code units)
uniform vec4 uCoef;        // rV, gU, gV, bU
uniform int uTransfer;     // 0 MiLog, 1 Rec709, 2 Srgb, 3 Linear
uniform int uGamut;        // 1 = BT.2020 -> Rec.709 matrix
uniform mat3 uGm;          // row-major (uploaded with transpose)
uniform vec3 uGain;        // WB/temp/tint gains
uniform float uExposure;   // 2^ev

out vec4 oCol;

const float kR0 = -0.09023729;
const float kRt = 0.01974185;
const float kA  = 0.09271529;
const float kB  = 0.01384578;
const float kG  = 0.67291850;
const float kC  = 18.10531998;

float miLogToLinear(float x) {
    float kPt = kC * (kRt - kR0) * (kRt - kR0);
    if (x < 0.0) return kR0;
    if (x >= kPt) return pow(2.0, (x - kG) / kA) - kB;
    return sqrt(x / kC) + kR0;
}

float spowf(float x, float e) {
    return x < 0.0 ? -pow(-x, e) : pow(x, e);
}

float decodeOne(float x) {
    if (uTransfer == 0) return miLogToLinear(x);
    if (uTransfer == 1) return x <= 0.081 ? x / 4.5 : spowf((x + 0.099) / 1.099, 1.0 / 0.45);
    if (uTransfer == 2) return x <= 0.040449936 ? x / 12.92 : spowf((x + 0.055) / 1.055, 2.4);
    return x;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    // Reduce first: Y = rounded mean of this pixel's N x N luma block; CbCr = rounded mean of the
    // N x N chroma block of this pixel's 2x2 pixel group (edge blocks average what exists).
    // N = 1 is the identity (one sample, mean = itself), exactly the full-size pass.
    float ySum = 0.0;
    float yCnt = 0.0;
    for (int j = 0; j < uReduce; ++j) {
        for (int i = 0; i < uReduce; ++i) {
            ivec2 s = p * uReduce + ivec2(i, j);
            if (s.x < uSrcSize.x && s.y < uSrcSize.y) {
                ySum += float(texelFetch(uY, s, 0).r >> uint(uShift));
                yCnt += 1.0;
            }
        }
    }
    float uSum = 0.0;
    float vSum = 0.0;
    float cCnt = 0.0;
    ivec2 cg = p / 2;
    for (int j = 0; j < uReduce; ++j) {
        for (int i = 0; i < uReduce; ++i) {
            ivec2 c = cg * uReduce + ivec2(i, j);
            if (c.x < uChromaSize.x && c.y < uChromaSize.y) {
                uvec2 cw = texelFetch(uC, c, 0).rg;
                uSum += float(cw.r >> uint(uShift));
                vSum += float(cw.g >> uint(uShift));
                cCnt += 1.0;
            }
        }
    }
    float yv = floor(ySum / yCnt + 0.5);
    float u = floor(uSum / cCnt + 0.5);
    float v = floor(vSum / cCnt + 0.5);
    float yc = (yv - uRange.x) / uRange.y;
    float cb = (u - uRange.z) / uRange.w;
    float cr = (v - uRange.z) / uRange.w;
    float r = yc + uCoef.x * cr;
    float g = yc - uCoef.y * cb - uCoef.z * cr;
    float b = yc + uCoef.w * cb;
    r = decodeOne(r);
    g = decodeOne(g);
    b = decodeOne(b);
    vec3 rgb = vec3(r, g, b);
    if (uGamut == 1) rgb = uGm * rgb;
    rgb = rgb * uGain * uExposure;
    oCol = vec4(rgb, 1.0);
}
)";

GLuint compile(GLenum type, const char* src, std::string* detail) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[1024] = {0};
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        if (detail) *detail = std::string("shader compile: ") + log;
        glDeleteShader(s);
        return 0;
    }
    return s;
}

}  // namespace

const char* encodedYuvFragmentSource() { return kFragment; }

void setEncodedYuvUniforms(unsigned int program, const EncodedYuvPassParams& pr) {
    const GLuint prog = program;
    const bool wide = pr.bitDepth > 8;
    const int cw = (pr.width + 1) / 2;
    const int ch = (pr.height + 1) / 2;
        // The oracle's constants (core/src/Transfer.cpp rangeFor / kCoeff*, the WB gain formula).
        const float scale = static_cast<float>(1 << (pr.bitDepth - 8));
        float offY, rangeY, offC, rangeC;
        if (pr.fullRange) {
            const float maxCode = static_cast<float>((1 << pr.bitDepth) - 1);
            offY = 0.0f; rangeY = maxCode; offC = 128.0f * scale; rangeC = maxCode;
        } else {
            offY = 16.0f * scale; rangeY = 219.0f * scale; offC = 128.0f * scale; rangeC = 224.0f * scale;
        }
        float coef[4] = {1.5748f, 0.187324f, 0.468124f, 1.8556f};  // BT.709
        if (pr.matrix == 0) { coef[0] = 1.402f;  coef[1] = 0.344136f; coef[2] = 0.714136f; coef[3] = 1.772f; }
        if (pr.matrix == 2) { coef[0] = 1.4746f; coef[1] = 0.164553f; coef[2] = 0.571353f; coef[3] = 1.8814f; }
        float gm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        const bool gamut = pr.primaries == 1;
        if (gamut) rawedit::gamutToGamut(rawedit::GamutId::Rec2020, rawedit::GamutId::Rec709, gm);
        const float t = pr.tempK - 5600.0f;
        const float gains[3] = {1.0f + t * 0.002f + pr.tint * 0.002f,
                                1.0f - pr.tint * 0.002f,
                                1.0f - t * 0.002f + pr.tint * 0.002f};
        const float exposure = std::pow(2.0f, pr.exposureEv);

        glUniform1i(glGetUniformLocation(prog, "uY"), 0);
        glUniform1i(glGetUniformLocation(prog, "uC"), 1);
        glUniform1i(glGetUniformLocation(prog, "uShift"), wide ? 16 - pr.bitDepth : 0);
        glUniform1i(glGetUniformLocation(prog, "uReduce"), pr.reduce);
        glUniform2i(glGetUniformLocation(prog, "uSrcSize"), pr.width, pr.height);
        glUniform2i(glGetUniformLocation(prog, "uChromaSize"), cw, ch);
        glUniform4f(glGetUniformLocation(prog, "uRange"), offY, rangeY, offC, rangeC);
        glUniform4f(glGetUniformLocation(prog, "uCoef"), coef[0], coef[1], coef[2], coef[3]);
        glUniform1i(glGetUniformLocation(prog, "uTransfer"), pr.transfer);
        glUniform1i(glGetUniformLocation(prog, "uGamut"), gamut ? 1 : 0);
        glUniformMatrix3fv(glGetUniformLocation(prog, "uGm"), 1, GL_TRUE, gm);
        glUniform3f(glGetUniformLocation(prog, "uGain"), gains[0], gains[1], gains[2]);
        glUniform1f(glGetUniformLocation(prog, "uExposure"), exposure);
}

namespace {

// One headless pass: EGL context, program, plane textures, a reduced RGBA32F target. Built once;
// [upload] and [draw] can then run per frame (the bench) or once (the parity run).
struct Pass {
    EglSession egl;
    GLuint prog = 0;
    GLuint tex[3] = {0, 0, 0};  // luma, chroma, target
    GLuint fbo = 0;
    bool wide = false;
    int bps = 1, cw = 0, ch = 0, stride = 0, uvStride = 0, outW = 0, outH = 0;
    EncodedYuvPassParams pr;
    const uint8_t* yPlane = nullptr;
    const uint8_t* cPlane = nullptr;

    ~Pass() {
        if (fbo) glDeleteFramebuffers(1, &fbo);
        if (tex[0]) glDeleteTextures(3, tex);
        if (prog) glDeleteProgram(prog);
    }

    bool init(const uint8_t* y, size_t yLen, const uint8_t* c, size_t cLen,
              const EncodedYuvPassParams& params, std::string* detail) {
        auto fail = [&](const std::string& why) { if (detail) *detail = why; return false; };
        pr = params;
        yPlane = y;
        cPlane = c;
        if (pr.width <= 0 || pr.height <= 0 || y == nullptr || c == nullptr) return fail("bad arguments");
        if (pr.bitDepth != 8 && pr.bitDepth != 10) return fail("bit depth is not 8 or 10");
        if (pr.reduce < 1 || pr.reduce > 32) return fail("reduce must be 1..32");
        wide = pr.bitDepth > 8;
        bps = wide ? 2 : 1;
        cw = (pr.width + 1) / 2;
        ch = (pr.height + 1) / 2;
        stride = pr.stride > 0 ? pr.stride : pr.width * bps;
        uvStride = pr.uvStride > 0 ? pr.uvStride : cw * 2 * bps;
        outW = (pr.width + pr.reduce - 1) / pr.reduce;
        outH = (pr.height + pr.reduce - 1) / pr.reduce;
        if (stride % bps != 0 || uvStride % (2 * bps) != 0) return fail("stride is not a whole number of texels");
        const long long yNeed = static_cast<long long>(pr.height - 1) * stride + static_cast<long long>(pr.width) * bps;
        const long long cNeed = static_cast<long long>(ch - 1) * uvStride + static_cast<long long>(cw) * 2 * bps;
        if (static_cast<long long>(yLen) < yNeed || static_cast<long long>(cLen) < cNeed)
            return fail("planes are smaller than the frame");

        std::string eglDetail;
        if (!egl.open(&eglDetail)) return fail("egl: " + eglDetail);
        const char* ext = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
        if (ext == nullptr || std::string(ext).find("GL_EXT_color_buffer_float") == std::string::npos)
            return fail("no EXT_color_buffer_float (RGBA32F not renderable)");

        GLuint vs = compile(GL_VERTEX_SHADER, kVertex, detail);
        if (!vs) return false;
        GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment, detail);
        if (!fs) { glDeleteShader(vs); return false; }
        prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        glDeleteShader(vs);
        glDeleteShader(fs);
        GLint linked = 0;
        glGetProgramiv(prog, GL_LINK_STATUS, &linked);
        if (!linked) return fail("program link failed");

        glGenTextures(3, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glBindTexture(GL_TEXTURE_2D, tex[0]);
        glTexStorage2D(GL_TEXTURE_2D, 1, wide ? GL_R16UI : GL_R8UI, pr.width, pr.height);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, tex[1]);
        glTexStorage2D(GL_TEXTURE_2D, 1, wide ? GL_RG16UI : GL_RG8UI, cw, ch);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, tex[2]);
        glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, outW, outH);
        glGenFramebuffers(1, &fbo);
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[2], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) return fail("framebuffer incomplete");
        return true;
    }

    void upload() {
        auto sub = [&](GLuint t, GLenum fmt, int w, int h, int rowTexels, const uint8_t* data) {
            glBindTexture(GL_TEXTURE_2D, t);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, rowTexels);
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, fmt, wide ? GL_UNSIGNED_SHORT : GL_UNSIGNED_BYTE, data);
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        };
        sub(tex[0], GL_RED_INTEGER, pr.width, pr.height, stride / bps, yPlane);
        sub(tex[1], GL_RG_INTEGER, cw, ch, uvStride / (2 * bps), cPlane);
    }

    void draw() {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, outW, outH);
        glUseProgram(prog);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[0]);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tex[1]);
        setEncodedYuvUniforms(prog, pr);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDrawArrays(GL_TRIANGLES, 0, 3);
    }
};

double nowMsLocal() {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

}  // namespace

bool runEncodedYuvPassHeadless(const uint8_t* yPlane, size_t yLen,
                               const uint8_t* cPlane, size_t cLen,
                               const EncodedYuvPassParams& params,
                               std::vector<float>* out, std::string* detail) {
    if (out == nullptr) { if (detail) *detail = "bad arguments"; return false; }
    Pass pass;
    if (!pass.init(yPlane, yLen, cPlane, cLen, params, detail)) return false;
    pass.upload();
    pass.draw();
    std::vector<float> rgba(static_cast<size_t>(pass.outW) * pass.outH * 4);
    glReadPixels(0, 0, pass.outW, pass.outH, GL_RGBA, GL_FLOAT, rgba.data());
    const GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        if (detail) { char b[48]; std::snprintf(b, sizeof(b), "gl error 0x%x", err); *detail = b; }
        return false;
    }
    const size_t n = static_cast<size_t>(pass.outW) * pass.outH;
    out->assign(n * 3, 0.0f);
    for (size_t i = 0; i < n; ++i) {
        (*out)[i * 3 + 0] = rgba[i * 4 + 0];
        (*out)[i * 3 + 1] = rgba[i * 4 + 1];
        (*out)[i * 3 + 2] = rgba[i * 4 + 2];
    }
    return true;
}

bool benchEncodedYuvPassHeadless(const uint8_t* yPlane, size_t yLen,
                                 const uint8_t* cPlane, size_t cLen,
                                 const EncodedYuvPassParams& params, int frames,
                                 double* uploadMs, double* drawMs, std::string* detail) {
    if (frames < 1 || uploadMs == nullptr || drawMs == nullptr) { if (detail) *detail = "bad arguments"; return false; }
    Pass pass;
    if (!pass.init(yPlane, yLen, cPlane, cLen, params, detail)) return false;
    double up = 0.0, dr = 0.0;
    for (int f = 0; f < frames; ++f) {
        const double t0 = nowMsLocal();
        pass.upload();
        glFinish();
        const double t1 = nowMsLocal();
        pass.draw();
        glFinish();
        const double t2 = nowMsLocal();
        up += t1 - t0;
        dr += t2 - t1;
    }
    *uploadMs = up / frames;
    *drawMs = dr / frames;
    return glGetError() == GL_NO_ERROR;
}

}  // namespace gles
