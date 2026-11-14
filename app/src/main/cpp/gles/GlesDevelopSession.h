// Production GPU develop session (M3 commit a) — the audit's "Shared
// processing session", the production counterpart of the parity harness in
// GlesPipeline.cpp.
//
// WHAT THIS IS NOT: a per-frame harness. developFragmentParity (the device
// PROVEN path — fragment parity PASS at 1 LSB / 398 of 24.8M samples) builds
// and tears down the whole pipeline per call, with glTexImage2D per run.
// That shape is Measure-only. THIS session owns, for its lifetime:
//
//   - one EGL context (gles::EglSession from GlesPipeline.h, the grafika
//     topology: display -> RGBA8888/pbuffer config -> ES 3 context -> 1x1
//     pbuffer), confined to ONE dedicated GL thread;
//   - the two programs (develop = FULLSCREEN_VERTEX + DEVELOP_FRAGMENT,
//     store = FULLSCREEN_VERTEX_310 + STORE_FRAGMENT), compiled ONCE at
//     open — the caller provides the Kotlin single-source-of-truth text,
//     exactly like the harness does;
//   - every uniform location, resolved ONCE at open;
//   - the CFA (GL_R16UI), shading (GL_R32F), develop (RGBA32F) and scratch
//     (RGBA8) textures and their FBOs, allocated with immutable
//     glTexStorage2D ONCE per resolution/resource change — per-frame work
//     is glTexSubImage2D uploads and uniform writes ONLY. No glTexImage2D,
//     no shader compilation, no storage allocation per frame (the audit's
//     explicit finding against the one-shot shape).
//
// THREADING (the contract in one paragraph): open() spawns the GL thread;
// every EGL/GL call for the session's lifetime — including teardown — runs
// on that thread (EGL contexts are not portable across threads; the same
// rule GlesPipeline.h documents for the harness). renderFrame() may be
// called from any ONE other thread (the export engine thread): it borrows
// the request's buffers for the duration of the call (it BLOCKS until the
// GL thread finishes — in-flight is bounded at exactly one job by
// construction, there is no queue to overflow), computes the CPU-side
// uniforms on the GL thread from the copied metadata, runs the two-pass
// draw (develop -> RGBA32F, store -> SSBO; glReadPixels is used NOWHERE,
// the recorded baseline invariant), maps the SSBO and quantizes the float
// readback to RGB888 bytes on the GL thread. close() drains (the bounded
// one-job design means at most the in-flight render) and joins the thread;
// it is idempotent and the destructor calls it.
//
// INPUT/OUTPUT: input is the DECODED full-res CFA (the CPU decode stays —
// one motioncam Source belongs to one thread, so the caller decodes on its
// own engine thread and hands the bytes over). Output is full-res RGB888,
// row-major, NOT rotated — the SAME byte shape nativeExportFrame returns,
// so the existing Kotlin rotate/crop/YUV/writer tail is shared verbatim.
//
// COLOR CONTRACT (why the output can replace the CPU develop): the shader
// runs the fused covered stages 1 (black/white), 2 (lens shading), 3
// (highlight reconstruction), 4 (bilinear demosaic), 5 (WB = guarded
// 1/asShotNeutral), 6 (camera -> Rec.709 via the buildCameraToRec709
// matrix, uploaded row-major with transpose=GL_TRUE), 14 (exposure =
// 2^ev), 23 (tonemap modes 0-5) and the OETF tail (uOetf=1 — the shader's
// linearToRec709 is textually identical to core/src/Transfer.cpp's, the
// commit-verified match). The readback quantize is the toByte tail
// verbatim (clamp [0,1], then v*255+0.5) — linearToRec709 is NOT applied
// again: the shader already did. The caller's grade must have every active
// stage inside the fragment-covered set (BackendRules' vocabulary gates
// that on the Kotlin side; this API takes only the four covered grade
// controls precisely so an uncovered grade cannot reach it).
//
// FAILURE SEMANTICS: a GL failure mid-render returns ok=false with the
// step named in [detail] AND LATCHES the session unhealthy — later
// renderFrame() calls fail fast with the same detail. Sticky on purpose:
// a mid-render GL failure is a context/driver event, not a per-frame
// hiccup, and silently switching pipelines mid-export would hide it; the
// Kotlin dispatcher (commit b) falls the whole export back to CPU with the
// reason in the fallback line instead. Context loss at teardown is
// absorbed: close() joins the thread and clears the slot regardless.
//
// Status vocabulary (the mission's honesty rule): this code COMPILES for
// Android but its runtime behavior is DEVICE-TEST-PENDING — the owner's
// covered-grade GPU export is the production proof. The math it runs is
// the math Measure already proved on device (developFragmentParity PASS).

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <rawedit/Source.h>

namespace gles {

// One render request. [cfa] and [meta] are BORROWED: the caller must keep
// them alive until renderFrame() returns (it blocks on the GL thread, so
// stack locals are fine). [info] and the grade controls are copied.
// The grade controls are exactly the fragment-covered set's knobs; see the
// color contract above.
struct DevelopFrameRequest {
    const std::vector<uint16_t>* cfa = nullptr;  // w*h row-major
    rawedit::FrameInfo info{};
    const rawedit::RawMeta* meta = nullptr;
    float exposureEv = 0.0f;
    bool highlightReconstruction = true;
    bool keepVignetting = true;
    int tonemap = 0;  // 0 off, 1 ACES, 2-5 AgX (Grade::tonemap)
};

// One render result. ok=false means the step named in [detail] failed (or
// the session is latched unhealthy — [detail] repeats the latched reason).
struct DevelopFrameResult {
    bool ok = false;
    std::vector<uint8_t> rgb888;  // w*h*3 row-major, NOT rotated
    uint32_t width = 0;
    uint32_t height = 0;
    std::string detail;
};

class DevelopSession {
public:
    // Spawns the GL thread, opens the EGL context on it, checks the gates
    // (ES 3.1+ — STORE_FRAGMENT is 310 es — and GL_EXT_color_buffer_float,
    // re-read in THIS context, never trusted from a probe's throwaway one),
    // compiles both programs and resolves every uniform location. Returns
    // null with *detail naming the first failing step on any failure (the
    // GL thread is joined; nothing leaks). The four sources are the Kotlin
    // single-source-of-truth texts (GlesShaderSources), host-validated.
    static std::unique_ptr<DevelopSession> open(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& fragmentSource,
        const std::string& storeFragmentSource,
        std::string* detail);

    // Drains (at most one in-flight render — the bounded design) and joins
    // the GL thread; all GL teardown happens on that thread. Idempotent.
    ~DevelopSession();

    DevelopSession(const DevelopSession&) = delete;
    DevelopSession& operator=(const DevelopSession&) = delete;

    // False after a latched GL failure (see the failure semantics above).
    // failureDetail() returns a COPY on purpose: the GL thread may latch a
    // new detail concurrently, and a reference would alias a string being
    // reassigned under it.
    bool healthy() const;
    std::string failureDetail() const;

    // Blocking render; in-flight bounded at one by construction. Safe to
    // call from one non-GL thread at a time (the export engine thread).
    DevelopFrameResult renderFrame(const DevelopFrameRequest& req);

    // See the destructor. Callable from the owning thread.
    void close();

private:
    DevelopSession() = default;
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gles
