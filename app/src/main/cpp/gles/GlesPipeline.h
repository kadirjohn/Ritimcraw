// GLES context + capability probe + single-frame demosaic parity
// (owner request 2026-11-12, deliverable C).
//
// Only the TOPOLOGY of Google's grafika EglCore.java is used (Apache-2.0):
// eglGetDisplay(EGL_DEFAULT_DISPLAY) -> eglInitialize -> RGBA8888 config
// with EGL_RENDERABLE_TYPE -> context with EGL_CONTEXT_CLIENT_VERSION ->
// a 1x1 pbuffer surface (NO window is required for compute) ->
// eglMakeCurrent -> work -> release in the exact reverse order:
// eglMakeCurrent(NO_SURFACE, NO_SURFACE, NO_CONTEXT) -> destroy surface ->
// destroy context -> eglReleaseThread -> eglTerminate. Everything is
// created and released on the CALLING thread (EGL contexts are not
// portable across threads); the Kotlin seam confines each probe/parity
// run to one background thread exactly like every other engine call.
//
// Status vocabulary (the mission's honesty rule): this code COMPILES for
// Android but its runtime behavior is DEVICE-TEST-PENDING — no phone has
// run it. The probe reports what the device actually reported; the parity
// harness reports the real sample differences, never a claim.

#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include <EGL/egl.h>

#include <rawedit/Source.h>

namespace gles {

// Grafika EglCore topology confined to one struct: open() builds
// display -> context -> 1x1 pbuffer and makes it current on THIS thread;
// release() tears down in the exact reverse order and is safe on a
// partially-opened session (every handle starts as its *_NO_* value, and
// the destructor always calls release()).
struct EglSession {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    ~EglSession();

    // Returns false on the first failing step (the step name lands in
    // *detail for the fallback log line); the destructor releases
    // whatever was created so far.
    bool open(std::string* detail);

    // Safe to call more than once; a no-op when nothing was created.
    void release();
};

// Everything the runtime capability probe reports (owner request §C: GLES
// version, EXT_color_buffer_float, max texture size, compute
// availability). All fields are "what the device reported", nothing
// inferred: false/empty means the probe failed at that point or the
// device lacks the item — the Kotlin layer turns that into an honest CPU
// fallback with the reason.
struct Capabilities {
    bool contextOk = false;       // EGL context + pbuffer created and current
    std::string version = "";     // glGetString(GL_VERSION) — "OpenGL ES 3.2 ..."
    std::string renderer = "";    // glGetString(GL_RENDERER)
    std::string extensions = "";  // glGetString(GL_EXTENSIONS)
    int glesMajor = 0;            // parsed from the version string
    int glesMinor = 0;
    bool computeOk = false;       // ES 3.1+ context with compute work groups
    bool floatFboOk = false;      // GL_EXT_color_buffer_float advertised
    int maxTextureSize = 0;       // GL_MAX_TEXTURE_SIZE
};

// One-shot probe on the calling thread: creates the context, reads the
// capability strings/limits, releases the context. Never throws; on any
// EGL failure the fields keep their false/empty defaults and *detail
// carries the first failing step for the fallback log line.
Capabilities probeCapabilities(std::string* detail);

// Single-frame demosaic parity (deliverable C). The GPU side runs the
// CALLER-PROVIDED GLSL ES 3.10 compute source [computeSource] — the
// Kotlin single source of truth (GlesShaderSources.DEMOSAIC_COMPUTE),
// host-validated at the syntax level (GlesShaderValidator) — so the
// device compiles the exact text the validator checked. The pipeline:
//
//   stage 1 (black/white normalization) — CPU, a documented copy of
//       developLinear's stage 1 (core/src/Develop.cpp), uploaded as a
//       shader storage buffer (no textures, no samplers, no image
//       units, no float framebuffers: GLES 3.1 core only — float image
//       stores are NOT guaranteed by core ES 3.1, so the design avoids
//       them entirely);
//   stage 4 (bilinear demosaic) — GPU, the compute shader, the exact
//       counterpart of rawedit::demosaicBilinear;
//   stage 5 (white balance) + the uint16 quantize tail — CPU, a
//       documented copy of developLinear's stage 5, applied to the
//       readback (a MAPPED shader storage buffer — glReadPixels is used
//       NOWHERE, not even in this prototype);
//   reference — rawedit::developLinear itself (exported, untouched),
//       the exact function the DNG path ships: zero drift risk in the
//       reference itself.
//
// The comparison is in the uint16 domain: both sides quantize to 16-bit
// exactly like the production path, so sub-LSB driver float noise is
// absorbed by the rounding and a real algorithm deviation (wrong
// neighbor set, wrong pattern cell, wrong clamp order) shows up as
// large, widespread LSB differences. The PASS VERDICT lives in Kotlin
// (GpuParityRules — the single source of truth, JVM-tested): at most 1
// LSB of 65535 and at most 0.01% of samples mismatching — far below the
// 8-bit export's own 1/255 quantization step, while a systematic
// deviation (even one confined to the image borders, ~0.2% of a 4K
// frame's samples) still fails. This struct reports the raw numbers only.
//
// Memory note (honest cost): the harness is debug-Measure-only and peaks
// around 220 MB of host + 130 MB of GPU memory on a 4K frame (the
// normalized input + developLinear's internal ImageF + the uint16
// reference + the GPU in/out buffers).
//
// ran=false means a stage failed and [detail] names it (EGL step, shader
// compile/link log, dispatch error, map failure); ran=true means the
// numbers are a real measurement on this device. Device-test-pending:
// no phone has run this.
struct ParityReport {
    bool ran = false;
    int maxDiffLsb = 0;           // largest |gpu - cpu| in 16-bit LSB units
    long long mismatchCount = 0;  // samples where the 16-bit outputs differ
    long long samples = 0;        // w * h * 3
    std::string detail;           // failure step, or the GL version on success
};

ParityReport demosaicParity(const std::string& computeSource,
                            const std::vector<uint16_t>& cfa,
                            const rawedit::FrameInfo& info,
                            const rawedit::RawMeta& meta);

} // namespace gles
