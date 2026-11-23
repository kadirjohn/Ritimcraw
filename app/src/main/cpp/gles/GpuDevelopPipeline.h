// The shared GPU develop pipeline — the develop chain ONCE, as a
// reusable object. Both production sessions run the SAME split chain:
// PRE (stages 1-6) -> the three NR passes at the CPU's exact stage 7/8
// position (gated per grade) -> POST (the ten pointwise stages 9-20) ->
// the four unsharp passes at the CPU's exact 21/22 position (gated per
// grade) -> the gyro warp pass (gated per FRAME — the warp is NOT a
// develop stage) -> TAIL (stages 23+24+OETF, uOetf=1) into the develop
// texture — over the same texA/texB/texC + fboOf[] slot threading, the
// same uniform sets, the same once-per-resolution allocation.
// GlesDevelopSession (export) and GlesPreviewSession (preview) each own
// ONE instance of this object and delegate; the chain body, the uniform
// tables and the slot logic live HERE and nowhere else.
//
// OWNERSHIP (the rule that makes the sharing safe): the two sessions' GL
// contexts are NEVER shared — EGL contexts are not portable across
// threads, and each session keeps its own (the develop session rides
// gles::EglSession; the preview session manages its raw handles). What
// is shared is the CODE. An instance is initialized on ONE session's GL
// thread with THAT session's context current, drawn on the same thread,
// and torn down on the same thread; every GL object it creates belongs
// to that one context. The pipeline has NO thread of its own and NO
// handoff/latch semantics — the sessions' one-job handoff rules stay
// theirs, verbatim.
//
// WHAT THE PIPELINE OWNS: the twelve chain programs (pre, post, tail,
// sharpnessH, sharpnessV, detailH, detailV, warp, nrChromaH, nrChromaV,
// nrLuma) plus the store program and ALL their uniform locations; the
// static texture-unit bindings (0 = CFA, 1 = chain-pass source (also
// the store draw's source), 2 = scratch, 3 = shading, 4 = the
// vertical-pass guide, 5 = the LUT 3D table); the CFA (GL_R16UI),
// shading (GL_R32F), develop (RGBA32F) and the three chain (RGBA32F)
// textures with their FBOs; the LUT revision cache (the parsed .cube +
// the 3D RGBA16F table + the compiled binding); the CPU-side uniform
// computation; the capability gates (ES 3.1 + GL_EXT_color_buffer_float,
// re-read in the OWNING context, never trusted from a probe's throwaway
// one); and the once-per-resolution (re)allocation.
//
// WHAT THE SESSIONS KEEP: their own EGL context, GL thread and
// handoff/latch semantics; the develop session keeps the store/SSBO
// readback + the encoder blit (the blit program stays session-side);
// the preview session keeps the window pass (the window program stays
// session-side), the stats pack (its own small scratch + SSBO) and the
// statsMode readback mode. The pipeline exposes accessors (developTex; the
// store program + its uniform locations; the withStoreScratch config's
// scratch FBO + outBuf + outBytes) so each session runs its own store
// draw and its own present.
//
// BEHAVIOR FREEZE: the pass math, the gating and the slot threading
// moved here VERBATIM from the two sessions — this TU is a refactoring,
// not a redesign. The 1990-test suite + the byte-identity harness (the
// fused recomposition check) are the proof. The real EGL path stays
// DEVICE-TEST-PENDING (the JVM suites do not run it) — the mission's
// honesty rule.

#pragma once

#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include <GLES3/gl3.h>

#include <rawedit/Source.h>

namespace gles {

// One render request — THE shared request shape, promoted here from
// GlesDevelopSession.h (verbatim, comments included; the preview
// session aliases it as PreviewFrameRequest). [cfa] and [meta] are
// BORROWED: the caller must keep them alive until the session's
// renderFrame() returns (it blocks on the GL thread, so stack locals are
// fine). [info] and the grade controls are copied. The grade controls
// are the fragment-covered set's knobs — every one a RAW grade value the
// shader maps itself (0.0f is the CPU's own neutral skip), with the two
// CPU-side gates hoisted C++-computed: asShot feeds uWbManual (stages
// 16/17) and the split hues feed uSplitActive (stage 19), exactly like
// keepVignetting feeds the shading gate.
struct DevelopFrameRequest;

// DEBUG probe helpers (2026-11-23, owner report: the GPU preview shows no
// change when a develop slider moves). gradeProbeSummary renders the develop
// controls of a request as one compact "ev=.. hi=.. ..." string for the
// ritim-trace probe lines (so moving ONE slider yields a before/after pair
// in logcat); gradeProbeHash hashes the same values (the re-arm key: the
// probes fire again whenever the grade the GPU actually decoded changes).
// Both are pure and only ever called behind windowProbeEnabled().
std::string gradeProbeSummary(const DevelopFrameRequest& req);
uint64_t gradeProbeHash(const DevelopFrameRequest& req);

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
    // Gyro warp (the stabilization resample — NOT a develop stage; the CPU
    // runs it after developCpu on the linear output). The caller resolves
    // the frame's stabilization plan on ITS OWN thread (the plan cache is
    // not GL-thread-safe), composes M = Hinv * S once per frame (S the
    // scale-about-center map at the frame's zoom; the CPU's own loop in
    // rawedit::warpFrame), narrows the 9 doubles to floats and uploads
    // them row-major with transpose=GL_TRUE — the uCam2rgb idiom. The
    // caller folds inactive (no gyro / the grade not stabilizing),
    // identity (H == I and zoom == 1 — the CPU's byte-identical fast path)
    // and degenerate (a singular H, a non-finite zoom) plans into
    // warpActive = false; the session then SKIPS the draw entirely — the
    // pass's absence is the identity, because a resampling pass on an
    // identity map would blur. There is NO in-shader gate: warpActive is
    // the only lock. The identity default keeps warpless requests
    // byte-identical to the pre-warp chain.
    bool warpActive = false;
    float warpM[9] = {1.0f, 0.0f, 0.0f,
                      0.0f, 1.0f, 0.0f,
                      0.0f, 0.0f, 1.0f};  // row-major M = Hinv * S
    // The stats-readback MODE (A1 async stats, 2026-11-23): the
    // StatsRing.h StatsReadbackMode vocabulary — 0 = off (draw 3 + the
    // readback skipped entirely; the honest 1x1 empty fallback publishes,
    // the trace line reads stats=off), 1 = sync (the trace-dims fix's
    // blocking mapped-SSBO readback, 2026-11-14 — the DEFAULT, and
    // today's behavior), 2 = async (the 2-SSBO + fence ring: publish the
    // frame N-1 pack only when its fence already signaled, never block).
    // The Kotlin side computes it per request from scope demand + render
    // class; the JNI entry folds the bridge-level master kill switch
    // (gPreviewStatsEnabled) natively — toggle false forces 0. The mode
    // RIDES the request (the gSyncRefine precedent) so the session reads
    // it per render without a second native call. The PREVIEW session
    // reads it; the develop session IGNORES it (its readback is the
    // render's contract, not a measurement); the pipeline itself never
    // touches it — the stats stage is session-side.
    int statsMode = 1;
};

class GpuDevelopPipeline {
public:
    // The owning session's two construction-time choices — the honest
    // divergences the two sessions already had, carried as parameters so
    // neither session's behavior changes.
    struct Config {
        // The develop texture's filter (BOTH min and mag): the preview
        // session passes its probed OES_texture_float_linear verdict
        // (GL_LINEAR when present — the window pass samples the develop
        // texture through aspect-fit scaling); the develop session
        // passes false (GL_NEAREST — its store draw and encoder blit are
        // 1:1). A FILTER choice, not a gate. SINCE THE NEAREST PIN (owner
        // device evidence 2026-11-15, the white window) the PREVIEW also
        // passes false: on the failing Adreno the extension is advertised
        // yet the window pass's LINEAR reads of the RGBA32F develop
        // texture painted WHITE (the same texture's stats draw healthy),
        // so the pin rides the session side — the probe stays honest, the
        // verdict is simply no longer consumed here.
        bool developLinearFilter = false;
        // Whether the once-per-resolution allocation ALSO creates the
        // store-scratch pair the develop session's readback path needs:
        // scratchTex (RGBA8) + scratchFbo + the outBuf SSBO (w*h*3
        // floats, STREAM_READ) + outBytes. The develop session passes
        // true; the preview session passes false — its stats pack owns
        // its own small scratch + SSBO sized to the published dims,
        // session-side, unchanged.
        bool withStoreScratch = false;
    };

    // One draw's outcome. ok=false means the step named in [detail]
    // failed; the owning session maps that onto its own result + latch
    // semantics. [width]/[height] are the developed frame's dims (the
    // develop texture's size — the CFA's w x h). [uploadUs] is the
    // per-frame CFA glTexSubImage2D span and [developUs] the WHOLE-chain
    // draw span (PRE through TAIL), both measured with steady_clock
    // inside draw() — ALWAYS collected, the preview's "every span is
    // always collected" rule (the cost is four clock reads per frame,
    // negligible next to any draw). The develop session ignores the two
    // span fields; the preview session copies them onto its result.
    //
    // [developReallocated] (white-window round 7, APPEND-ONLY): true when
    // THIS draw entered the once-per-resolution branch (a cfaW/cfaH
    // mismatch at entry) — the frame that DELETED + RE-CREATED the develop
    // texture and its chain siblings. THE HONEST NOTE ON THE ROUND-7
    // BRIEF'S "skip boolean": THERE IS NO CHAIN SKIP ANYWHERE IN draw().
    // The per-grade/per-frame gates bypass individual NR/unsharp/warp
    // passes, but the chain ALWAYS ends in the UNCONDITIONAL TAIL pass
    // (draw 11), which always writes developTex — ok=true PROVES the
    // chain ran THIS frame, after any allocation, in the same call body
    // (the allocation branch and the eleven draws are sequential lines
    // of one function; nothing between them returns). The realloc flag is
    // therefore the only real reportable state near the brief's reading 2
    // (the manager's "chain skips the realloc frame" key): it names the
    // frames the develop texture was re-created on, so the probe line can
    // correlate them with the white directly. The develop session ignores
    // it; the preview session's window probe prints it (realloc=).
    struct DrawResult {
        bool ok = false;
        std::string detail;
        uint32_t width = 0;
        uint32_t height = 0;
        double uploadUs = 0.0;
        double developUs = 0.0;
        bool developReallocated = false;
    };

    GpuDevelopPipeline();
    ~GpuDevelopPipeline();
    GpuDevelopPipeline(const GpuDevelopPipeline&) = delete;
    GpuDevelopPipeline& operator=(const GpuDevelopPipeline&) = delete;

    // Checks the capability gates FIRST (ES 3.1+ — STORE_FRAGMENT is
    // 310 es — and GL_EXT_color_buffer_float, re-read in THIS context),
    // then compiles the thirteen programs (the twelve chain passes +
    // store), resolves every uniform location and makes the static
    // texture-unit bindings — ONCE, on the owning session's GL thread
    // with that session's context current (the session has already
    // opened the context; egl.open stays session-side). The sources are
    // the Kotlin single-source-of-truth texts (GlesShaderSources),
    // host-validated. Returns false with *detail naming the first
    // failing step; nothing leaks (teardown() is safe on a partial
    // init — every delete is a guarded no-op on 0).
    bool init(const std::string& vertexSource,
              const std::string& vertex310Source,
              const std::string& preFragmentSource,
              const std::string& postFragmentSource,
              const std::string& tailFragmentSource,
              const std::string& sharpnessHSource,
              const std::string& sharpnessVSource,
              const std::string& detailHSource,
              const std::string& detailVSource,
              const std::string& warpFragmentSource,
              const std::string& nrChromaHSource,
              const std::string& nrChromaVSource,
              const std::string& nrLumaSource,
              const std::string& storeFragmentSource,
              const Config& config,
              std::string* detail);

    // Runs the whole chain SYNCHRONOUSLY on the calling GL thread (the
    // owning session's GL thread): the input contract, the CPU-side
    // uniform computation, the once-per-resolution (re)allocation, the
    // identity-keyed shading upload, the LUT revision cache, the CFA
    // upload and the eleven draws with their per-grade/per-frame gating
    // and slot threading — into the develop texture. [req] is borrowed
    // for the call's duration (the sessions' own rule). Undefined before
    // a successful init() (the sessions fail fast at open).
    DrawResult draw(const DevelopFrameRequest& req);

    // WW8 per-pass probe (DEBUG, one-shot): arms the NEXT draw() to log
    // the frame's CPU-side uniforms and a GL_FLOAT center readback after
    // every chain pass (PRE, each NR pass, POST, each unsharp pass, warp,
    // TAIL) to ritim-trace as "pass probe:" lines. Raw floats, not
    // toByte — the first pass whose value goes >= 1.0 names the stage
    // that whitens the wide clips. The arm clears itself after one draw.
    // The preview session arms it only on window-probe frames.
    void armPassProbe();

    // --- accessors (GL-thread use; the handles belong to this
    // pipeline's one context) ---

    // The chain's RGBA32F output texture (unit 1 is its binding for the
    // store draw / the session's blit / the window pass).
    GLuint developTex() const;
    // The store program + its uniform locations, so each session runs
    // its own store draw (the develop readback; the preview stats pack).
    GLuint storeProgram() const;
    GLint storeSrcLoc() const;
    GLint storeSizeLoc() const;
    // The develop-config store-scratch pair (0 / 0 when the config did
    // not request it).
    GLuint scratchFbo() const;
    GLuint outBuf() const;
    uint64_t outBytes() const;

    // Deletes every GL object the pipeline owns, in the established
    // teardown order (the FBOs, then the textures, then the SSBO, then
    // the programs). GL thread, owning context current. Idempotent; the
    // owning session calls it inside its own teardownOnGlThread and
    // then deletes its own session-specific resources in its own
    // established order.
    void teardown();

private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

} // namespace gles
