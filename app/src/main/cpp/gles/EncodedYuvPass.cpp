// See EncodedYuvPass.h.

#include "EncodedYuvPass.h"

#include "GlesPipeline.h"

#include <algorithm>
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
    float yv = float(texelFetch(uY, p, 0).r >> uint(uShift));
    uvec2 cw = texelFetch(uC, ivec2(p.x / 2, p.y / 2), 0).rg;
    float u = float(cw.r >> uint(uShift));
    float v = float(cw.g >> uint(uShift));
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

bool runEncodedYuvPassHeadless(const uint8_t* yPlane, size_t yLen,
                               const uint8_t* cPlane, size_t cLen,
                               const EncodedYuvPassParams& pr,
                               std::vector<float>* out, std::string* detail) {
    auto fail = [&](const std::string& why) { if (detail) *detail = why; return false; };
    if (pr.width <= 0 || pr.height <= 0 || yPlane == nullptr || cPlane == nullptr || out == nullptr)
        return fail("bad arguments");
    if (pr.bitDepth != 8 && pr.bitDepth != 10) return fail("bit depth is not 8 or 10");
    const bool wide = pr.bitDepth > 8;
    const int bps = wide ? 2 : 1;
    const int cw = (pr.width + 1) / 2;
    const int ch = (pr.height + 1) / 2;
    const int stride = pr.stride > 0 ? pr.stride : pr.width * bps;
    const int uvStride = pr.uvStride > 0 ? pr.uvStride : cw * 2 * bps;
    if (stride % bps != 0 || uvStride % (2 * bps) != 0) return fail("stride is not a whole number of texels");
    const long long yNeed = static_cast<long long>(pr.height - 1) * stride + static_cast<long long>(pr.width) * bps;
    const long long cNeed = static_cast<long long>(ch - 1) * uvStride + static_cast<long long>(cw) * 2 * bps;
    if (static_cast<long long>(yLen) < yNeed || static_cast<long long>(cLen) < cNeed)
        return fail("planes are smaller than the frame");

    EglSession egl;
    std::string eglDetail;
    if (!egl.open(&eglDetail)) return fail("egl: " + eglDetail);
    const char* ext = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (ext == nullptr || std::string(ext).find("GL_EXT_color_buffer_float") == std::string::npos)
        return fail("no EXT_color_buffer_float (RGBA32F not renderable)");

    GLuint vs = compile(GL_VERTEX_SHADER, kVertex, detail);
    if (!vs) return false;
    GLuint fs = compile(GL_FRAGMENT_SHADER, kFragment, detail);
    if (!fs) { glDeleteShader(vs); return false; }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint linked = 0;
    glGetProgramiv(prog, GL_LINK_STATUS, &linked);
    if (!linked) { glDeleteProgram(prog); return fail("program link failed"); }

    // Planes -> integer textures (rows of the source stride via UNPACK_ROW_LENGTH, in texels).
    GLuint tex[3] = {0, 0, 0};  // luma, chroma, target
    glGenTextures(3, tex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    auto uploadPlane = [&](GLuint t, GLenum internalFmt, GLenum fmt, int w, int h, int rowTexels, const uint8_t* data) {
        glBindTexture(GL_TEXTURE_2D, t);
        glTexStorage2D(GL_TEXTURE_2D, 1, internalFmt, w, h);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, rowTexels);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, fmt, wide ? GL_UNSIGNED_SHORT : GL_UNSIGNED_BYTE, data);
        glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    };
    uploadPlane(tex[0], wide ? GL_R16UI : GL_R8UI, GL_RED_INTEGER, pr.width, pr.height, stride / bps, yPlane);
    uploadPlane(tex[1], wide ? GL_RG16UI : GL_RG8UI, GL_RG_INTEGER, cw, ch, uvStride / (2 * bps), cPlane);

    glBindTexture(GL_TEXTURE_2D, tex[2]);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA32F, pr.width, pr.height);
    GLuint fbo = 0;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex[2], 0);
    bool ok = glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    if (!ok) {
        if (detail) *detail = "framebuffer incomplete";
    } else {
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

        glViewport(0, 0, pr.width, pr.height);
        glUseProgram(prog);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, tex[0]);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, tex[1]);
        glUniform1i(glGetUniformLocation(prog, "uY"), 0);
        glUniform1i(glGetUniformLocation(prog, "uC"), 1);
        glUniform1i(glGetUniformLocation(prog, "uShift"), wide ? 16 - pr.bitDepth : 0);
        glUniform4f(glGetUniformLocation(prog, "uRange"), offY, rangeY, offC, rangeC);
        glUniform4f(glGetUniformLocation(prog, "uCoef"), coef[0], coef[1], coef[2], coef[3]);
        glUniform1i(glGetUniformLocation(prog, "uTransfer"), pr.transfer);
        glUniform1i(glGetUniformLocation(prog, "uGamut"), gamut ? 1 : 0);
        glUniformMatrix3fv(glGetUniformLocation(prog, "uGm"), 1, GL_TRUE, gm);
        glUniform3f(glGetUniformLocation(prog, "uGain"), gains[0], gains[1], gains[2]);
        glUniform1f(glGetUniformLocation(prog, "uExposure"), exposure);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        std::vector<float> rgba(static_cast<size_t>(pr.width) * pr.height * 4);
        glReadPixels(0, 0, pr.width, pr.height, GL_RGBA, GL_FLOAT, rgba.data());
        const GLenum err = glGetError();
        if (err != GL_NO_ERROR) {
            ok = false;
            if (detail) { char b[48]; std::snprintf(b, sizeof(b), "gl error 0x%x", err); *detail = b; }
        } else {
            out->assign(static_cast<size_t>(pr.width) * pr.height * 3, 0.0f);
            for (size_t i = 0; i < static_cast<size_t>(pr.width) * pr.height; ++i) {
                (*out)[i * 3 + 0] = rgba[i * 4 + 0];
                (*out)[i * 3 + 1] = rgba[i * 4 + 1];
                (*out)[i * 3 + 2] = rgba[i * 4 + 2];
            }
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo);
    glDeleteTextures(3, tex);
    glDeleteProgram(prog);
    return ok;
}

}  // namespace gles
