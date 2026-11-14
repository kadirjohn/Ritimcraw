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
//   - the twelve programs (pre = FULLSCREEN_VERTEX + DEVELOP_FRAGMENT_PRE,
//     post = FULLSCREEN_VERTEX + DEVELOP_FRAGMENT_POST — the pointwise
//     half — tail = FULLSCREEN_VERTEX + DEVELOP_FRAGMENT_TAIL, the four
//     unsharp passes sharpnessH/sharpnessV/detailH/detailV =
//     FULLSCREEN_VERTEX + SHARPNESS_H / SHARPNESS_V / DETAIL_H /
//     DETAIL_V, the three spatial-NR passes chromaH/chromaV/luma =
//     FULLSCREEN_VERTEX + NR_CHROMA_H / NR_CHROMA_V / NR_LUMA, store =
//     FULLSCREEN_VERTEX_310 + STORE_FRAGMENT, blit = FULLSCREEN_VERTEX +
//     BLIT_FRAGMENT), compiled ONCE at open — the caller provides the
//     Kotlin single-source-of-truth texts, exactly like the harness does.
//     The develop chain is the SPLIT of the fused DEVELOP_FRAGMENT at the
//     CPU's stage 6/9 and 20/23 boundaries: pre runs stages 1-6 into an
//     RGBA32F intermediate, the NR passes (gated per grade) sit at the
//     CPU's exact stage 7/8 position, post runs the pointwise stages
//     9-20, the four unsharp passes (gated per grade) run the CPU's
//     stages 21/22 — H then V per stage, the V pass reading the stage's
//     own input as its ORIGINAL-luma guide — and tail runs stages
//     23+24+OETF into the develop texture. The three-way split
//     recomposes the fused text BYTE-IDENTICALLY (the host-checked
//     invariant), and the fused text itself stays the frozen parity
//     harness's own source (GlesPipeline.cpp) — the split passes compose
//     the same shared fragments, so the two chains cannot drift;
//   - every uniform location, resolved ONCE at open;
//   - the CFA (GL_R16UI), shading (GL_R32F), develop (RGBA32F), the three
//     chain intermediates (RGBA32F ping-pong textures A/B/C + their FBOs —
//     the pre pass's output, the NR passes' scratch pair; A doubles as the
//     ORIGINAL pre-NR guide the chroma vertical pass reads) and scratch
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
// chain runs the covered stages 1 (black/white), 2 (lens shading), 3
// (highlight reconstruction), 4 (bilinear demosaic), 5 (WB = guarded
// 1/asShotNeutral), 6 (camera -> Rec.709 via the buildCameraToRec709
// matrix, uploaded row-major with transpose=GL_TRUE) in the PRE pass;
// 7 (chroma NR — the CPU's luma-guided two-direction filter as TWO
// passes, horizontal then vertical, the vertical pass keeping the
// ORIGINAL pre-NR luma guide) and 8 (luma NR — the CPU's true 3x3
// bilateral, one pass, center + 8 neighbors) in their own NR passes at
// the CPU's exact stage position, gated per grade (uChromaDenoise /
// uNoiseReduction raw values; the C++ session skips the draws entirely
// for a clamped strength of 0 or a frame under the CPU's own w/h
// guards); and the ten pointwise grade stages (9 black point, 10 white
// point, 11 shadows, 12 midtones, 13 contrast, 16 temperature, 17 tint,
// 18 color wheel, 19 split tone, 20 saturation — each the CPU loop's
// exact math behind a raw-value uniform), 14 (exposure = 2^ev),
// 15 (highlights = the CPU stage-15 shoulder behind uHighlights)
// in the POINTWISE pass; 21 (sharpness) + 22 (detail) — the CPU's
// unsharpLumaImpl verbatim as FOUR passes: the H pass blurs ONLY luma
// (radius 1.5 for sharpness, 0.75 for detail — both step 1, the CPU's
// own `radius < 1 ? 1 : floor(radius)` fallback; the gate-closed
// writes keep the original luma), the V pass finishes the separable
// blur and lifts all three channels by amount*(luma - blur) — reading
// its blurred values from the H pass's output and its ORIGINAL luma +
// RGB from the stage's own input via uGuide (the CPU's tail reads the
// ORIGINAL luma, not the sharpened one). RAW uSharpness/uDetail
// values, NO [0,100] clamp: the CPU's own amount mapping
// min(max(0.5*(raw/100), -0.5), 0.5) runs in-shader, so NEGATIVE
// values are valid softening; the session skips the four draws
// entirely for a raw 0 or a frame under the CPU's own w<2/h<2 guard,
// and the in-shader gate is the second lock — at the CPU's exact stage
// position (AFTER the pointwise stages, BEFORE tonemap); and 23
// (tonemap modes 0-5) and the OETF tail (uOetf=1 — the shader's
// linearToRec709 is textually identical to core/src/Transfer.cpp's, the
// commit-verified match) in the TAIL pass. The readback
// quantize is the toByte tail verbatim (clamp [0,1], then v*255+0.5) —
// linearToRec709 is NOT applied again: the shader already did. The
// caller's grade must have every active stage inside the fragment-covered
// set (BackendRules' vocabulary gates that on the Kotlin side; this API
// takes the fragment-covered grade controls precisely so an uncovered
// grade cannot reach it).
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
//
// ENCODER SURFACE (M5): the session can additionally draw the developed
// frame DIRECTLY to an EGLSurface wrapped around a MediaCodec input
// Surface (eglCreateWindowSurface + eglSwapBuffers) — the readback pass
// (store -> SSBO -> quantize) is skipped entirely on that path, which is
// the point: the GPU pipeline ends at the encoder instead of round-
// tripping through the CPU. The blit reuses the SAME BLIT_FRAGMENT the
// Surface-mode writer proved on device (Y flip included — the develop
// pass's texel row 0 is the image top, the encoder surface wants row 0
// at the bottom). The presentation timestamp goes through
// eglPresentationTimeANDROID (EGL_ANDROID_presentation_time), resolved
// ONCE at open via eglGetProcAddress and honestly gated: a device
// without it refuses the encoder path with that reason named — never a
// crash, never a silent stamp of the wall clock. The encoder path is
// 1:1-pixels only: the caller (Mp4Exporter) gates on no rotation/crop/
// straighten/scale BEFORE requesting it, so the blit is a plain copy.
// A setEncoderSurface failure NEVER latches the session (the context is
// fine — the surface isn't) and the export falls back to the existing
// readback path with the reason in ONE log line. A renderFrameToEncoder
// GL failure DOES latch (it is a mid-render context event, same
// semantics as renderFrame).
//
// ROTATION (the surface-gpu vertical deliverable): the encoder blit
// applies the frame's effective orientation, a multiple of 90 degrees
// (0/90/180/270), as a texture-coordinate fold in the blit fragment —
// uRotation. The developed texture stays linear sdr-rgb; only the
// destination pixel walk rotates. A quarter turn swaps the destination
// viewport to the source's swapped dims (the session does the swap;
// uSize stays the SOURCE's w x h). The caller (Mp4Exporter) still gates
// BEFORE requesting the path — now on "rotation is a multiple of 90 and
// the encode dims equal the ROTATED source dims" — so crop, straighten
// and scale remain outside the fast path and the blit stays a 1:1-pixel
// copy (1:1 through the fold, never resampled).

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <android/native_window.h>

#include <rawedit/Source.h>

namespace gles {

// One render request. [cfa] and [meta] are BORROWED: the caller must keep
// them alive until renderFrame() returns (it blocks on the GL thread, so
// stack locals are fine). [info] and the grade controls are copied.
// The grade controls are the fragment-covered set's knobs — every one a
// RAW grade value the shader maps itself (0.0f is the CPU's own neutral
// skip), with the two CPU-side gates hoisted C++-computed: asShot feeds
// uWbManual (stages 16/17) and the split hues feed uSplitActive (stage
// 19), exactly like keepVignetting feeds the shading gate. See the color
// contract above.
struct DevelopFrameRequest {
    const std::vector<uint16_t>* cfa = nullptr;  // w*h row-major
    rawedit::FrameInfo info{};
    const rawedit::RawMeta* meta = nullptr;
    float exposureEv = 0.0f;
    float highlights = 0.0f;  // raw grade.highlights; the shader computes s
    float blackPoint = 0.0f;  // raw grade.blackPoint; the shader computes the lift
    float whitePoint = 0.0f;  // raw grade.whitePoint; the shader computes the gain
    float shadows = 0.0f;     // raw grade.shadows; the shader computes the gamma
    float midtones = 0.0f;    // raw grade.midtones; the shader computes the curve
    float contrast = 0.0f;    // raw grade.contrast; the shader computes the pivot
    float tempK = 5600.0f;   // raw grade.tempK; the shader computes the gains
    float tint = 0.0f;       // raw grade.tint; the shader computes the gains
    float saturation = 0.0f; // raw grade.saturation; the shader computes the mix
    bool asShot = true;      // grade.asShot; feeds uWbManual (stages 16/17)
    float wheelShadowX = 0.0f;  // raw grade.wheelShadowX; the shader computes the offset
    float wheelShadowY = 0.0f;  // raw grade.wheelShadowY; the shader computes the offset
    float wheelMidX = 0.0f;     // raw grade.wheelMidX; the shader computes the offset
    float wheelMidY = 0.0f;     // raw grade.wheelMidY; the shader computes the offset
    float wheelHighX = 0.0f;    // raw grade.wheelHighX; the shader computes the offset
    float wheelHighY = 0.0f;    // raw grade.wheelHighY; the shader computes the offset
    float splitShadowHue = -1.0f;  // raw grade.splitShadowHue; -1 = off
    float splitHighlightHue = -1.0f;  // raw grade.splitHighlightHue; -1 = off
    float splitBalance = 0.0f;  // raw grade.splitBalance; the shader computes the mask
    bool highlightReconstruction = true;
    bool keepVignetting = true;
    int tonemap = 0;  // 0 off, 1 ACES, 2-5 AgX (Grade::tonemap)
    // Spatial NR (stages 7/8), RAW grade values — 0.0f is the CPU call
    // site's own skip. The session skips the NR draws entirely for a
    // clamped strength of 0 or a frame under the CPU's own w/h guards
    // (chroma w<2/h<2, luma w<3/h<3); the in-shader gate is the second
    // lock. Populated through the JNI seam in commit (b); 0.0f here
    // keeps commit (a)'s sessions byte-identical to the pre-NR chain.
    float chromaDenoise = 0.0f;  // raw grade.chromaDenoise
    float noiseReduction = 0.0f;  // raw grade.noiseReduction
    // Stages 21/22 (sharpness/detail), RAW grade values — 0.0f is the CPU
    // call site's own skip, and NEGATIVE values are valid softening (the
    // CPU's own amount mapping min(max(0.5*(raw/100), -0.5), 0.5) runs
    // in-shader; there is NO [0,100] clamp here, unlike the NR controls).
    // The session skips the four unsharp draws entirely for a raw 0 or a
    // frame under the CPU's own w<2/h<2 guard; the in-shader gate is the
    // second lock. Populated through the JNI seam in commit (b); 0.0f
    // here keeps commit (a)'s sessions byte-identical to the pre-21/22
    // chain.
    float sharpness = 0.0f;  // raw grade.sharpness (stage 21)
    float detail = 0.0f;      // raw grade.detail (stage 22)
    // Stage 24 LUT, the LUT port's session-side shape (commit (a) of the LUT
    // mission). lutStrength is the RAW grade value on rawedit's own [0,1]
    // scale (GradeK's percent / 100, mapped Kotlin-side before the seam —
    // the CPU seam's own contract); 0.0f is the CPU call site's own skip
    // and keeps commit (a)'s sessions byte-identical to the pre-LUT chain.
    // lutPath empty = no LUT. The session parses the .cube ONCE per LUT
    // revision (core's parseCubeFile, cached by path), compiles the
    // managed binding (rawedit::compileLutBinding) and uploads the table
    // as a 3D RGBA16F texture — hardware trilinear, one mip, GL_LINEAR +
    // CLAMP_TO_EDGE on all three axes — through the texel-center mapping
    // the shader carries (uLutSize/uLutDomainMin/uLutDomainMax). The five
    // interpretation ordinals are the rawedit enum ordinals (LutRole,
    // GamutId, TransferId); all five -1 = the legacy stage-24 shape.
    // Populated through the JNI seam in commit (b); the neutral defaults
    // here keep commit (a) honest.
    float lutStrength = 0.0f;  // raw grade.lutStrength, already [0,1]
    std::string lutPath;       // the resolved .cube path; empty = no LUT
    int lutRole = 0;
    int lutInGamut = 0;
    int lutInTransfer = 0;
    int lutOutGamut = 0;
    int lutOutTransfer = 0;
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
    // compiles the twelve programs and resolves every uniform location.
    // Returns null with *detail naming the first failing step on any
    // failure (the GL thread is joined; nothing leaks). The fourteen
    // sources are the Kotlin single-source-of-truth texts
    // (GlesShaderSources), host-validated: the two vertex variants
    // (vertex + vertex310) + the three-way split develop chain (pre /
    // pointwise post / tail) + the four unsharp fragments
    // (sharpnessH/sharpnessV/detailH/detailV) + the three NR fragments +
    // store + the blit fragment that draws the developed texture to
    // the encoder surface.
    static std::unique_ptr<DevelopSession> open(
        const std::string& vertexSource,
        const std::string& vertex310Source,
        const std::string& preFragmentSource,
        const std::string& postFragmentSource,
        const std::string& tailFragmentSource,
        const std::string& sharpnessHSource,
        const std::string& sharpnessVSource,
        const std::string& detailHSource,
        const std::string& detailVSource,
        const std::string& nrChromaHSource,
        const std::string& nrChromaVSource,
        const std::string& nrLumaSource,
        const std::string& storeFragmentSource,
        const std::string& blitFragmentSource,
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

    // ENCODER SURFACE (M5). Hands an ANativeWindow (from a MediaCodec
    // input Surface) to the GL thread: it wraps the window in
    // eglCreateWindowSurface ONCE per export, using the session's OWN
    // config (opened window-capable + recordable for exactly this).
    // Ownership transfers at call entry (the ANativeWindow acquired by
    // ANativeWindow_fromSurface is released on the GL thread on every
    // path — the M4 setSurface shape). A nullptr window detaches and
    // destroys the current encoder surface. Returns true when the
    // attach (or detach) succeeded; a failure returns false with the
    // step named in *detail and NEVER latches the session unhealthy
    // (the EGL context is fine — the window isn't). Callable from a
    // different thread than renderFrame (the export engine thread);
    // safe because both block on the same one-job handoff.
    bool setEncoderSurface(ANativeWindow* window, std::string* detail);

    // ENCODER RENDER (M5): develop the frame on the GPU (the exact M3
    // develop pass) and blit the developed texture straight to the
    // encoder window surface — the store/readback pass is skipped.
    // [ptsUs] is the frame's encoder presentation timestamp in
    // microseconds (the SAME normalized pts the writer path uses); it
    // is stamped via eglPresentationTimeANDROID as ptsUs*1000 ns media
    // time — never the wall clock. [rotationDeg] is the frame's EFFECTIVE
    // orientation (container + manual, normalized): the blit applies it
    // through the uRotation fold — 0/90/180/270 only, anything else is
    // refused on the calling thread with that reason named (the caller
    // gates first; this is the second lock). 0 is the byte-identical
    // upright copy. ok=false latches the session unhealthy (mid-render
    // GL failure semantics, same as renderFrame). Requires a successful
    // setEncoderSurface first; the refusal is honest and named, never a
    // crash.
    DevelopFrameResult renderFrameToEncoder(const DevelopFrameRequest& req,
                                            int64_t ptsUs,
                                            int rotationDeg = 0);

    // See the destructor. Callable from the owning thread.
    void close();

private:
    DevelopSession() = default;
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gles
