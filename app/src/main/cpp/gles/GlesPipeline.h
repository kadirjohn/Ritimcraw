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
// EGL_RECORDABLE_ANDROID (the MediaCodec input Surface's producer flag)
// lives only in the extension header — egl.h does not define it.
#include <EGL/eglext.h>

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
    // The config eglChooseConfig picked. Kept so a window-capable
    // session can hand THE SAME config to eglCreateWindowSurface (a
    // mismatched config is one of the classic BufferQueue rejection
    // causes). Nullptr for the default pbuffer-only open (nobody uses
    // it there) and after release().
    EGLConfig config = nullptr;

    ~EglSession();

    // Returns false on the first failing step (the step name lands in
    // *detail for the fallback log line); the destructor releases
    // whatever was created so far.
    //
    // windowCapable: the chosen config must also support EGL_WINDOW_BIT
    // (besides the pbuffer) and is kept in [config] so the caller can
    // wrap an ANativeWindow in eglCreateWindowSurface later. Off by
    // default: the probe and the parity harness open plain pbuffer
    // sessions and stay byte-identical.
    // recordable: adds EGL_RECORDABLE_ANDROID=1 to the config attribs
    // (EGL_ANDROID_recordable) — required when the window surface will
    // wrap a MediaCodec input Surface, so the codec's BufferQueue
    // accepts the producer. Only meaningful with windowCapable.
    bool open(std::string* detail, bool windowCapable = false,
              bool recordable = false);

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
    bool computeOk = false;       // ES 3.1+ context, all 3 indexed dispatch-count axes > 0
    bool floatFboOk = false;      // GL_EXT_color_buffer_float advertised
    int maxTextureSize = 0;       // GL_MAX_TEXTURE_SIZE
    // Per-axis compute limits (glGetIntegeri_v, the ONLY correct query —
    // see the probe). 0 = the axis query failed or the context is pre-3.1.
    std::vector<int> maxWorkGroupCount = {0, 0, 0};  // GL_MAX_COMPUTE_WORK_GROUP_COUNT[x/y/z]
    std::vector<int> maxWorkGroupSize = {0, 0, 0};   // GL_MAX_COMPUTE_WORK_GROUP_SIZE[x/y/z]
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
    long long samples = 0;        // the measured slice's w * h * 3 (see the fixed-slice note)
    std::string detail;           // failure step, or the GL version on success
};

ParityReport demosaicParity(const std::string& computeSource,
                            const std::vector<uint16_t>& cfa,
                            const rawedit::FrameInfo& info,
                            const rawedit::RawMeta& meta);

// Single-frame FRAGMENT develop parity (deliverable D) — the first REAL
// GPU render path: the fused fragment pass (GlesShaderSources
// DEVELOP_FRAGMENT) runs stages 1+2+3+4+5 (+6/14/23/OETF behind runtime
// uniforms) as a draw, not a compute dispatch, and the readback goes
// through a second fragment pass that writes an SSBO (STORE_FRAGMENT —
// glReadPixels is banned, the recorded baseline invariant). The device
// compiles the CALLER-PROVIDED Kotlin text for all four stages:
//
//   [vertexSource]      the 300-es fullscreen vertex (develop program)
//   [vertex310Source]   the 310-es fullscreen vertex — GLSL ES forbids
//                       linking different #version lines into one
//                       program, and STORE_FRAGMENT is 310 es (its SSBO
//                       write needs 3.1 fragment-shader SSBO access)
//   [fragmentSource]    DEVELOP_FRAGMENT (300 es)
//   [storeFragmentSource] STORE_FRAGMENT (310 es)
//   [caseIndex]         0 = the developLinear shape (see below);
//                       1 = the default grade's real stage list;
//                       2 = the tonemap sweep (stage 23, modes 1-5)
//
// THREE parity cases (the fragment pass covers stages 1+2+3+4+5+23 now,
// so ONE reference no longer fits all the shapes):
//
//   case 0 — the developLinear shape, byte-honest regardless of the
//       clip's real metadata: uHasShading=0, uHr=0, uHasProfile=0,
//       uExposure=1.0, uOetf=0, uTonemap=0 — the pass computes exactly
//       stages 1+4+5, and the reference is rawedit::developLinear itself
//       (untouched, exported), so this case keeps its original meaning
//       and verdict.
//
//   case 1 — the DEFAULT grade's real stage list: uHasShading=1 with the
//       clip's actual 4-plane map uploaded as a GL_R32F texture (planes
//       stacked vertically, texel y = plane*shadingH + row — the layout
//       the shader's texelFetch reads), uHr=1 with uHrGain = meta.wbGains
//       with the >0 guard (a DIFFERENT vector from uWb's 1/asShotNeutral),
//       uHasProfile=0, uExposure=1.0, uOetf=0, uTonemap=0. developLinear
//       CANNOT serve as this case's reference (it runs stages 1+4+5 only),
//       so the reference is computed HERE in C++ from the exported rawedit
//       primitives — a local stage-1 copy (frame-preferred levels), the
//       stage-2 loop through rawedit::sampleShading, a verbatim copy of
//       the stage-3 cell pass, rawedit::demosaicBilinear, the WB multiply
//       (1/asShotNeutral with the >0 guard), clamp [0,1], quantize
//       uint16 — core/src/Develop.cpp itself stays untouched.
//
//   case 2 — the TONEMAP sweep (stage 23): the same stage-2/3 gates as
//       case 1 (the reference below runs the same decisions), with
//       uTonemap sweeping modes 1 (ACES) through 5 (AgX Base), one full
//       develop+store+readback pass per mode. The reference per mode is
//       rawedit::developCpu ITSELF — the real exported stage list, not a
//       hand copy — called with a default Grade whose only active control
//       is tonemap=mode and a meta copy whose forwardMatrix/colorMatrix
//       are pinned to identity (buildCameraToRec709 returns false for an
//       identity profile, so developCpu skips stage 6 — matching
//       uHasProfile=0). A default Grade runs exactly 1+2+3+4+5+23: every
//       other stage is gated off by its neutral default (exposureEv 0,
//       saturation 0, sharpness/detail 0, split hues -1, wheels 0,
//       chromaDenoise/noiseReduction 0). The verdict accumulates the
//       WORST case across the five modes and [samples] counts all five
//       passes (5 * w * h * 3) — the same PASS rule per mode as ever
//       (maxDiffLsb <= 1, mismatchCount*10000 <= samples).
//
// The pipeline (all cases):
//
//   CPU-computed uniforms — frame-preferred stage-1 levels (uBlack/
//       uRange, the developLinear stage-1 rule), WB gains (uWb, the
//       1/asShotNeutral vector with the >0 guard);
//   GPU pass 1 (develop) — GL_R16UI CFA texture -> RGBA32F attachment
//       (EXT_color_buffer_float, gated HERE in this context — the probe's
//       report belongs to the probe's throwaway context, this harness
//       re-reads its own extension string and framebuffer-completeness,
//       never guessing past a missing capability);
//   GPU pass 2 (store) — the RGBA32F texture -> SSBO, drawn to a
//       THROWAWAY RGBA8 framebuffer (never to the FBO the source texture
//       is attached to — that would be a feedback loop); the pass also
//       writes a throwaway color output (a zero-output fragment shader is
//       spec-gray on some ES drivers);
//   readback — glMapBufferRange on the SSBO (never glReadPixels), then
//       the CPU tail clamps [0,1] and quantizes uint16 exactly like the
//       developLinear tail (NO WB tail — the shader already applied it);
//   comparison — the uint16 domain, same verdict rules as the compute
//       harness (the verdict itself lives in Kotlin, GpuParityRules).
//
// The fragment path does NOT need compute: the gate is contextOk +
// floatFboOk, NOT computeOk (a compute-less ES 3.1 device can still pass
// this leg). ran=false means a stage failed and [detail] names it.
//
// FIXED SAFE SLICE (owner-approved hardening): the parity run measures
// SHADER MATH, not resolution, so a frame whose long edge exceeds 2048
// runs as a CENTER CROP with even offsets and even dimensions (the crop
// starts on a 2x2 cell boundary, so the CFA pattern alignment and
// cellFor's layout stay valid; the CPU reference computes the SAME crop).
// [samples] counts the slice the harness actually measured — a frame
// within the 2048 budget passes through whole, keeping the
// device-proven full-frame shape exactly. The verdict thresholds are
// unchanged (they live in Kotlin, GpuParityRules).
// Device-test-pending: no phone has run this.
ParityReport developFragmentParity(const std::string& vertexSource,
                                    const std::string& vertex310Source,
                                    const std::string& fragmentSource,
                                    const std::string& storeFragmentSource,
                                    int caseIndex,
                                    const std::vector<uint16_t>& cfa,
                                    const rawedit::FrameInfo& info,
                                    const rawedit::RawMeta& meta);

} // namespace gles
