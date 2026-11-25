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

#include "EncodedYuvPass.h"

#include <rawedit/ColorModel.h>
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

// Highlight recovery's shader texts (GlesShaderSources.RECOVERY_PRE / _BLOCK0 / _BLOCK_UP / _COMPOSITE), set
// once per process by the Kotlin side; pipelines compile them lazily on the first recovery draw.
void setRecoveryShaderSources(const std::string& pre, const std::string& block0, const std::string& blockUp,
                              const std::string& composite);

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
    // E0b-4b: an ENCODED frame instead of a CFA. When set, [cfa] stays null, pass 1 is the YUV pass
    // (gles/EncodedYuvPass: YCbCr -> RGB, transfer decode, primaries, WB gains x exposure, optionally
    // reduced first) writing the same RGBA32F texture the PRE pass would, and the chain runs on
    // unchanged. [info].width/height are the REDUCED output dims (ceil(source/reduce)); [meta] must
    // still be non-null (a default RawMeta is fine, it is not read for the pixels). The WB/exposure
    // are the YUV pass's own, so the request's exposureEv/tempK/tint and the tone-band fields must
    // be neutral (the CPU encoded path ignores stages 9-20; see the plan, section 8).
    const EncodedYuvInput* yuv = nullptr;
    rawedit::FrameInfo info{};
    const rawedit::RawMeta* meta = nullptr;
    float exposureEv = 0.0f;
    int highlightsCurve = 0;  // stage-15 curve version (0 legacy knee 0.75, 1 v1 knee 0.40)
    bool highlightRecovery = false;  // replaces stage 3 with the pyramid re-estimate (CPU oracle: recoverClippedHighlights)
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
    // The A2 crop/straighten pair (owner request 2026-11-23, guide A2):
    // APPEND-ONLY after statsMode, never interleaved. cropParams is the
    // five-float parcel GpuCropMap.requestCrop built Kotlin-side —
    // [rectX, rectY, rectW, rectH, straightenDeg] with the rect
    // ALREADY mapRect'ed through CropState (container-upright
    // fractions; the manual quarter turn rides PreviewSurface's
    // graphicsLayer OUTSIDE the GL draw) and the RAW angle (the ±15
    // clamp + the dims-dependent geometry live in GpuCropMap.h
    // planCrop — Kotlin cannot know the develop dims). The PREVIEW
    // session reads the pair (the window + stats draws set the
    // WINDOW_FRAGMENT crop uniforms from the planned frame); the
    // develop/export session IGNORES it (the export crops in a Kotlin
    // stage after develop, Mp4Exporter STAGE_CROP — guide A2's out of
    // scope). The defaults keep every existing session byte-identical:
    // cropActive false plans the identity (uCropOn = 0 — today's path
    // verbatim) and no call site sends true until A2's editor change.
    bool cropActive = false;
    float cropParams[5] = {0.0f, 0.0f, 1.0f, 1.0f, 0.0f};
    // The A3 zebra quad (owner request 2026-11-23, guide A3): the window
    // draw's zebra knobs, APPEND-ONLY after the crop pair — the SAME
    // single-source rule the crop pair rides, but with the VALUES owned
    // Kotlin-side (ZebraOverlay.STRIPE_PERIOD /
    // ZebraOverlay.STRIPE_ALPHA / FrameHistogram.WHITE_CLIP_VALUE thread
    // through the JNI seam; no zebra literal lives in any C++ default
    // except the honest inert ones here). The PREVIEW session reads the
    // quad: the window draw sets WINDOW_FRAGMENT's four uZebra* uniforms
    // from it, and the stats window pass sets the same three values with
    // uZebraOn FORCED to 0 — the scope compute keeps analyzing the
    // CLEAN frame (guide A3's binding rule). The develop/export session
    // IGNORES it (zebra is session-side display work, never exported).
    // The defaults keep every existing session byte-identical:
    // zebraOn false renders exactly the pre-A3 frame (the shader's gate
    // folds), and the develop session never sets the quad at all.
    bool zebraOn = false;
    int zebraThreshold = 253;  // FrameHistogram.WHITE_CLIP_VALUE's shape
    int zebraPeriod = 12;      // ZebraOverlay.STRIPE_PERIOD's shape
    float zebraAlpha = 200.0f / 255.0f;  // ZebraOverlay.STRIPE_ALPHA / 255
    // ---- color model v2 F1-5: the NODES chain + the output transform ----
    // APPEND-ONLY after the zebra quad, the same single-source rule. The
    // chain arrives as rawedit::GradeNode values BORROWED for the call
    // (the caller resolves lutId -> a const Lut* on its own thread, the
    // plan's "parcel DIŞINDA" strings-separately note): the pipeline
    // compiles them with core's own compileNode() ONCE per chain revision
    // (keyed by the content hash below), fills the curve atlas (unit 6,
    // R32F, node*3+channel rows, 512 columns — the inventory §2.4) and the
    // per-node uniform packs; the NODES draw consumes the cache. The
    // NODES pass runs BETWEEN the warp and the TAIL (plan §3.2 stage 6;
    // kPassNodes in ChainSlots.h) and the request's nodesActive=false
    // keeps the chain exactly the pre-F1-5 draw sequence (the v1
    // regression lock).
    //   outputMode: 0 = the legacy TAIL (the v1 program, linear 709 in /
    //     out); 1 = DWG-linear out, drawn by the TAIL's OpenDRT variant
    //     (needs the drt fields below).
    //   nodeChainHash: a cheap content key the CALLER derives (the same
    //     values in the same order hash the same — the revision cache's
    //     only job is skipping re-uploads when nothing changed).
    //   The OpenDRT output block (mode 1): the spec's presets, resolved
    //     into the DRT program's uniform pack by the pipeline calling
    //     core's OpenDrt once per revision? NO — the Impl's constants are
    //     private; the resolved PACK crosses as drtUniforms[30], the
    //     caller's pre-compiled block (the same pre-compiled-packs rule
    //     the node chain rides; the JVM binding produces it and the
    //     values are pinned by NodeParcelTest's output-block parity).
    bool nodesActive = false;
    int nodeCount = 0;          // 0..8; nodesActive requires count > 0 OR drtMode
    int outputMode = 0;         // 0 = the legacy 709 back; 1 = DwgLinear (DRT)
    const rawedit::GradeNode* nodeChain = nullptr;  // BORROWED, nodeCount long
    uint64_t nodeChainHash = 0;  // the revision key (the caller's content hash)
    // The OpenDRT uniform pack (mode 1): the resolved preset + the Impl
    // constructor's constants, flat — the binding's pre-work. NULL keeps
    // the DRT pass inert (an outputMode-1 request without a pack refuses
    // the frame honestly at the draw).
    const float* drtUniforms = nullptr;  // BORROWED, kDrtUniformSlots floats
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

    // ---- color model v2 F1-5: the NODES/TAIL-DRT addition -----------------
    //
    // The two new programs compile through a SEPARATE, ADDITIVE call so
    // neither session's existing init (nor the JNI open signatures, S4's
    // seam) changes: a session that never calls initNodes() keeps exactly
    // the pre-F1-5 behavior (nodesActive requests are refused by draw()
    // with the honest "nodes not initialized" message, never a silent
    // CPU-less path).
    //
    // [nodesFragmentSource] (GlesShaderSources.NODES) and [tailDrtSource]
    // (GlesShaderSources.DEVELOP_FRAGMENT_TAIL_DRT) are the Kotlin
    // single-source-of-truth texts, host-validated. ALSO answers the
    // inventory's missing-query finding (§1.3: "hiçbir yerde
    // glGetIntegerv sorgusu yok" for the fragment uniform vector and
    // texture-unit limits): the pipeline reads
    // GL_MAX_FRAGMENT_UNIFORM_VECTORS and GL_MAX_TEXTURE_IMAGE_UNITS in
    // THIS context at the same time and records them below. The NODES pass
    // needs ~110-115 vec4 (the inventory §2.6's own arithmetic) and its
    // worst-case texture-unit count is 1 (source) + 1 (atlas) + 4 (node
    // LUTs) = 6 on top of the chain's own assignments; the queries make
    // the verdict this DEVICE's, not a spec guess.
    struct NodesLimits {
        bool ok = false;          // the two programs compiled + linked
        int fragmentUniformVectors = 0;  // GL_MAX_FRAGMENT_UNIFORM_VECTORS
        int textureImageUnits = 0;        // GL_MAX_TEXTURE_IMAGE_UNITS
        std::string detail;       // the first failing step when !ok
    };
    // Compiles the two programs, resolves their uniform locations, pins
    // the atlas unit (6) and the node-LUT units (7..10), allocates the
    // 512x24 R32F atlas and the four 2x2x2 RGBA16F dummy node-LUT tables
    // (the sampler-completeness rule, bindLutDummyTex's own), and reads
    // the two limits. Idempotent (a second call recompiles; the GL-thread
    // contract is the sessions' own).
    bool initNodes(const std::string& nodesFragmentSource,
                   const std::string& vertexSource,
                   const std::string& tailDrtSource,
                   NodesLimits* limits, std::string* detail);
    // The last init's recorded limits (all zeros before initNodes).
    NodesLimits nodesLimits() const;
    // The resolution shape F1-5 requires if the caller wants to hand the
    // DRT uniforms pre-resolved: the uniform count the DRT program reads
    // (the drtUniforms block's length, kDrtUniformSlots). LAYOUT (a flat
    // float block, one consumer, defined HERE — the requestCrop parcel
    // precedent):
    //   [0] eotf     the OpenDrtDisplay ordinal (int-as-float; 2 = HLG)
    //   [1] hdr      1.0 when display == Hlg1000 (uDrtHdr)
    //   [2] cwp      the RESOLVED INTERNAL P.cwp (0..5; 2 = D65 skip)
    //   [3] normScalar     kCWP_NORM_709[cwp].y (the 709 display)
    //   [4] normScalarHdr  kCWP_NORM_P3[cwp].y (the HLG display)
    //   [5..8]   uDrtTn    (tn_con, tn_sh, tn_toe, tn_off)
    //   [9..12]  uDrtRs    (rs_sa, rs_rw, rs_bw, cwp_lm)
    //   [13..16] uDrtToe   (brl, brl_rng, brl_st, tsDsc)
    //   [17..20] uDrtBrlRgb (brl_r, brl_g, brl_b, brlp)
    //   [21..24] uDrtBrlpRgb (brlp_r, brlp_g, brlp_b, lconSc)
    //   [25..28] uDrtLcon  (lconM, lconW, hconP, tn_hcon_pv)
    //   [29..32] uDrtHcon  (tn_hcon_st, tsM2, tsS, tsP)
    //   [33..36] uDrtTs    (tsS1, sLp100, 0, 0)
    //   [37..38] hueR      (hs_r, hs_r_rng)
    //   [39..40] hueG      (hs_g, hs_g_rng)
    //   [41..42] hueB      (hs_b, hs_b_rng)
    //   [43..44] hueC      (hs_c, hs_c_rng)
    //   [45..46] hueM      (hs_m, hs_m_rng)
    //   [47..48] hueY      (hs_y, hs_y_rng)
    //   [49..52] conf0     (brl_en, brlp_en, hc_en, ptl_en)
    //   [53..56] conf1     (ptm_en, hs_rgb_en, hs_cmy_en, lcon_en)
    //   [57..60] conf2     (hcon_en, 0, hc_r, hc_r_rng)
    //   [61..64] pt        (pt_lml, pt_lml_r, pt_lml_g, pt_lml_b)
    //   [65..68] pt2       (pt_lmh, pt_lmh_r, pt_lmh_b, ptl_c)
    //   [69..72] pt3       (ptl_m, ptl_y, ptm_low, ptm_low_rng)
    //   [73..76] pt4       (ptm_low_st, ptm_high, ptm_high_rng, ptm_high_st)
    // 77 floats total. The binding (F1-6) resolves OpenDrtSpec into this
    // block EXACTLY ONCE per output revision — the values pinned against
    // core's own Impl outputs by the JVM parity tests in F1-7.
    // The Kotlin OpenDrtPacks block (legacy layout, still produced and sent) ...
    static constexpr int kDrtPackSlots = 77;
    // ... plus the six spec floats the JNI appends (look, tonescale, cwp, cwpLimit, grey, display): the
    // OpenDRT is BAKED into a 3D table from these (rawedit::bakedOpenDrtLut) and the TAIL variant samples it.
    static constexpr int kDrtUniformSlots = kDrtPackSlots + 6;
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
