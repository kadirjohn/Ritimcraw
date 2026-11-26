package com.ritim.ritimcraw.engine

/**
 * THE EFFECT REGISTRY — one row per develop effect, the checklist view of
 * the CPU/GPU parity contract (owner request 2026-11-15).
 *
 * WHAT THIS IS: pure DATA + documentation. Every develop effect in the
 * 24-stage CPU contract (core/src/Develop.cpp's developCpu) plus the one
 * pre-develop temporal stage gets exactly one row naming: its stage
 * number, its neutral value, its input/output color domain, its class
 * (pointwise / neighborhood / temporal), its CPU implementation site
 * (file + function), its GPU implementation site (shader kind + pass),
 * the hardware capabilities its GPU leg needs, and its coverage token
 * (or none). Each row cites the live site it was written from.
 *
 * CITATION RULE (2026-11-23 refresh, after the acceptance audit found the
 * GPU/JNI cites drifted — docs/ACCEPTANCE_GAPS_2026-11-16.md "Adjacent
 * finding"): sites in files that churn nightly (the shared GPU pipeline
 * and jni_bridge.cpp) are cited by SYMBOL — the gate's variable name or
 * the pipeline's "draw N" section comment — which a grep finds no matter
 * how the lines move. Stable sites (Develop.cpp stage blocks, the shader
 * constants in GlesShaderSources.kt) keep file:line.
 *
 * WHAT THIS IS NOT: this object does NOT replace the live gates. The
 * truth lives in the live tables — BackendRules.FRAGMENT_COVERED_STAGES
 * (RenderBackend.kt:251-252), BackendRules.fragmentUncoveredStages'
 * runtime leg (RenderBackend.kt:373-382), the CPU stage guards
 * (core/src/Develop.cpp), and the shared pipeline's draw gating
 * (app/src/main/cpp/gles/GpuDevelopPipeline.cpp Impl::draw — both
 * develop sessions delegate their whole chain to it since the
 * shared-pipeline refactor 73e33fb). The registry is the
 * CHECKLIST; the gates are the TRUTH. No dispatch site reads this
 * object at runtime — it exists so a new effect lands with its row and
 * the cross-check test (EffectRegistryTest) keeps the row honest against
 * the live tables.
 *
 * THE CONTRACT (adding a new effect):
 *   1. implement it for real — the CPU stage in core/src/Develop.cpp
 *      (or the pre-stage in the bridge), the GPU site in
 *      GlesShaderSources + the session chain, the uniform + the gate,
 *      the validator contract, the parity harness shape;
 *   2. add ONE row here citing the live file:line sites;
 *   3. update the LIVE gates (FRAGMENT_COVERED_STAGES or the uncovered
 *      map's token) — the row follows the gates, never the reverse;
 *   4. the cross-check test (EffectRegistryTest) must stay green: every
 *      FRAGMENT_COVERED_STAGES entry has a row, every row's stage is in
 *      the CPU contract's documented range (or is the temporal pre-stage
 *      marker), every token is in the fallback vocabulary.
 *
 * Row status vocabulary (the honest ladder, per the project's honesty
 * rules): planned / implemented / compiled / host-tested /
 * device-test-pending / blocked. Every row below is implemented +
 * host-tested (the validator + JVM suites lock the contracts); the
 * device compile/parity legs are device-test-pending as recorded in
 * GlesShaderSources' own honesty note (this host has no GLSL compiler).
 *
 * THE TEMPORAL PRE-STAGE: the temporal NR is NOT one of the 24 develop
 * stages — it runs BEFORE developCpu, merging neighbor CFAs into the
 * center CFA on the CPU (the bridge's temporalMergedCfa), and the
 * merged CFA feeds BOTH paths as the ordinary stage-1 input. Its row
 * uses [STAGE_TEMPORAL] as the stage marker (outside 1..24 on purpose)
 * and "none" as its shader kind: the GPU leg is ROUTING, not a shader
 * port — the merge math stays the frozen CPU's (core/Temporal.cpp),
 * and the pair (temporalNr, temporalStrength) dies at the JNI bridge.
 *
 * THE GYRO WARP is deliberately NOT a row: it is not a develop stage at
 * all (the CPU runs it after developCpu on the linear output; the
 * session runs the WARP pass between the unsharp passes and the tail,
 * gated per frame by req.warpActive — GpuDevelopPipeline.cpp "draw 10: the gyro
 * warp pass" (`if (req.warpActive)`),
 * "NOT a develop stage" per GlesShaderValidator.Kind.WARP's own doc).
 * Stabilization/crop/straighten are also outside developCpu (they are
 * geometry, applied around the develop, not color stages on the pixel
 * values) and have no rows.
 *
 * HARDWARE CAPABILITIES vocabulary (what a row's GPU leg needs beyond a
 * working ES 3.1 context):
 *   FLOAT_FBO — the RGBA32F intermediates need EXT_color_buffer_float
 *     (BackendGates.floatFboOk; every draw-chain row);
 *   LUT_TEXTURE — stage 24 additionally needs the device's 3D RGBA16F
 *     LUT texture path healthy (lutTexturePathOk, RenderBackend.kt:375);
 *   NONE — no GL requirement at all (the temporal pre-stage: the merge
 *     is CPU-side, before any session exists).
 * The draw chain's own entry gate is BackendGates.fragmentAllowed()
 * (contextOk && floatFboOk && parityOk — RenderBackend.kt:131): the
 * capabilities below are the PER-ROW additions on top of that gate.
 *
 * COVERAGE TOKEN vocabulary: the ONLY runtime fallback token left in
 * the develop pipeline is "lut" (stage 24 while lutTexturePathOk is
 * false — RenderBackend.kt:378-380). FRAGMENT_UNCOVERED_STAGES is
 * empty and FRAGMENT_UNCOVERED_PRE_STAGES is empty (the temporal
 * hybrid closed the last pre-stage), so every other row's token is
 * none. A new row with a token must add that token to the live
 * fragmentUncoveredStages vocabulary — the cross-check test enforces
 * the match.
 */
object EffectRegistry {

    /** The temporal pre-stage's marker: outside the 1..24 develop contract. */
    const val STAGE_TEMPORAL: Int = 0

    /** The CPU develop contract's documented stage range (developCpu). */
    const val CPU_STAGE_FIRST: Int = 1
    const val CPU_STAGE_LAST: Int = 24

    /**
     * The class of an effect: how it computes one output pixel.
     * POINTWISE — output[i] depends only on input[i] (one draw, one
     * texel). NEIGHBORHOOD — output[i] reads a neighborhood (multi-pass
     * with ping-pong slots). TEMPORAL — output[i] reads other FRAMES
     * (the pre-develop merge).
     */
    enum class EffectClass { POINTWISE, NEIGHBORHOOD, TEMPORAL }

    /**
     * One row per develop effect. [stage] is the CPU contract's stage
     * number (1..24) or [STAGE_TEMPORAL] for the temporal pre-stage.
     * [neutral] is the grade value that makes the stage a no-op (the
     * CPU's own skip guard). [inputDomain]/[outputDomain] name the
     * color space the stage reads/writes (CFA = the raw Bayer mosaic;
     * LINEAR_RGB = scene-linear Rec.709; the OETF tail is part of the
     * TAIL pass, not a stage row of its own — it is the bridge tail's
     * own step, listed under stage 24's row).
     */
    data class EffectRow(
        /** CPU stage number (1..24) or [STAGE_TEMPORAL] (0). */
        val stage: Int,
        /** Human name (the GradeK field it rides). */
        val name: String,
        /** The grade value that skips the stage entirely. */
        val neutral: String,
        /** The color domain this stage reads. */
        val inputDomain: String,
        /** The color domain this stage writes. */
        val outputDomain: String,
        /** POINTWISE / NEIGHBORHOOD / TEMPORAL. */
        val effectClass: EffectClass,
        /** CPU implementation site: file + function (live citation). */
        val cpuSite: String,
        /** GPU implementation site: shader kind + pass (live citation). */
        val gpuSite: String,
        /** FLOAT_FBO / LUT_TEXTURE / NONE (per-row additions). */
        val hardware: String,
        /** The fallback token when active and uncovered, or "none". */
        val coverageToken: String,
    )

    /**
     * THE ROWS — one per develop effect, in CPU contract order, then the
     * temporal pre-stage. Every citation below was READ from the live
     * file at the given line (never from memory).
     */
    val ROWS: List<EffectRow> = listOf(

        // ---- Stages 1-3: CFA stages (before the demosaic). ----

        EffectRow(
            stage = 1,
            name = "black/white normalization",
            neutral = "always on (frame-preferred levels)",
            inputDomain = "CFA",
            outputDomain = "CFA",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:724-753 (developCpu stage 1)",
            gpuSite = "GlesShaderSources DEVELOP_FRAGMENT_PRE (GlesShaderSources.kt:1342) — stages 1-6 draw, pipeline 'draw 1: the PRE pass' (GpuDevelopPipeline.cpp Impl::draw)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 2,
            name = "lens shading (keepVignetting)",
            neutral = "keepVignetting=false or no map",
            inputDomain = "CFA",
            outputDomain = "CFA",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:755-774 (developCpu stage 2; bilinear sampleShading)",
            gpuSite = "DEVELOP_FRAGMENT_PRE behind uHasShading (the CPU-side gate is BackendRules.fragmentShadingApplies, RenderBackend.kt:413-414)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 3,
            name = "highlight reconstruction (highlightReconstruction)",
            neutral = "highlightReconstruction=false",
            inputDomain = "CFA",
            outputDomain = "CFA",
            effectClass = EffectClass.NEIGHBORHOOD,
            cpuSite = "core/src/Develop.cpp:776-853 (developCpu stage 3; per-2x2-cell ceiling)",
            gpuSite = "DEVELOP_FRAGMENT_PRE behind uHr (the CPU's own per-cell stats recomputed per site)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 4,
            name = "bilinear demosaic",
            neutral = "always on",
            inputDomain = "CFA",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.NEIGHBORHOOD,
            cpuSite = "core/src/Develop.cpp:855-856 (developCpu stage 4; demosaicBilinear at 655)",
            gpuSite = "DEVELOP_FRAGMENT_PRE (the exact kernel, all four CFA patterns through uCell)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 5,
            name = "white balance (asShotNeutral)",
            neutral = "always on",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:858-897 (developCpu stages 5+6; division by asShotNeutral)",
            gpuSite = "DEVELOP_FRAGMENT_PRE behind uWb (the CPU 1/asShotNeutral vector, computed CPU-side)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 6,
            name = "camera color transform (DNG 1.6 §6)",
            neutral = "always on",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:858-897 (developCpu stages 5+6; buildCameraToRec709, ForwardMatrix1 path)",
            gpuSite = "DEVELOP_FRAGMENT_PRE behind uCam2rgb (the CPU-BUILT matrix uploaded with transpose=true; uHasProfile=0 skips)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stages 7-8: spatial noise reduction (DN-01..DN-08). ----

        EffectRow(
            stage = 7,
            name = "chroma denoise (chromaDenoise)",
            neutral = "chromaDenoise=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.NEIGHBORHOOD,
            cpuSite = "core/src/Develop.cpp:907-908 (developCpu stage 7; denoiseChromaImpl at 215, blurPlane at 248)",
            gpuSite = "NR_CHROMA_H (GlesShaderSources.kt:1428) + NR_CHROMA_V (1575) — pipeline draws 2-3 of 4, gated `runChroma = chromaStrength > 0.0f && w >= 2 && h >= 2` (GpuDevelopPipeline.cpp Impl::draw)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 8,
            name = "luma denoise (noiseReduction)",
            neutral = "noiseReduction=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.NEIGHBORHOOD,
            cpuSite = "core/src/Develop.cpp:909-910 (developCpu stage 8; denoiseLumaImpl at 331, the 3x3 bilateral)",
            gpuSite = "NR_LUMA (GlesShaderSources.kt:1726) — pipeline draw 4 of 4, gated `runLuma = lumaStrength > 0.0f && w >= 3 && h >= 3` (GpuDevelopPipeline.cpp Impl::draw)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stages 9-13: tone controls (owner request 2026-11-10 §2). ----

        EffectRow(
            stage = 9,
            name = "black point (blackPoint)",
            neutral = "blackPoint=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:930-938 (developCpu stage 9; lift = -0.12*bp/100, x' = lift + (1-lift)*x; k=0.12 per A6 owner request 2026-11-23)",
            gpuSite = "DEVELOP_FRAGMENT_POST (GlesShaderSources.kt:1366) behind uBlackPoint — pipeline 'draw 5: the POST pass' (GpuDevelopPipeline.cpp Impl::draw), stages 9-20 draw",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 10,
            name = "white point (whitePoint)",
            neutral = "whitePoint=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:948-958 (developCpu stage 10; the wp>0/wp<=0 uniform scale)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uWhitePoint",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 11,
            name = "shadows (shadows)",
            neutral = "shadows=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:967-982 (developCpu stage 11; wDark = smoothstepf(0.18,0,mx), gain = 2^(strength*wDark))",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uShadows",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 12,
            name = "midtones (midtones)",
            neutral = "midtones=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:990-1006 (developCpu stage 12; the 0.18-peaked tent weight)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uMidtones",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 13,
            name = "contrast (contrast)",
            neutral = "contrast=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1015-1025 (developCpu stage 13; pivot 0.18, slope 1+c)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uContrast",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stage 14: exposure. ----

        EffectRow(
            stage = 14,
            name = "exposure (exposureEv)",
            neutral = "exposureEv=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1027-1036 (developCpu stage 14; scale = 2^ev)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uExposure (1.0 = neutral)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stage 15: highlights (the stage-15 port, 0efcc75). ----

        EffectRow(
            stage = 15,
            name = "highlights (highlights)",
            neutral = "highlights=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1380-1420 (developCpu stage 15; knee 0.18, the monotone shoulder, uniform gain)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uHighlights (GlesShaderSources.kt:447 uniform, 1098-1113 the verbatim GLSL block)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stages 16-17: temperature/tint (behind the asShot gate). ----

        EffectRow(
            stage = 16,
            name = "temperature (tempK)",
            neutral = "asShot=true or tempK=5600f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1076-1098 (developCpu stage 16; !grade.asShot gate, log2(tempK/5600) push)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uWbManual (the CPU-computed !asShot gate) + uTempK",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 17,
            name = "tint (tint)",
            neutral = "asShot=true or tint=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1100-1108 (developCpu stage 17; g *= 2^(0.4*tint/100))",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uWbManual + uTint",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stages 18-19: color wheel + split tone (COL-13). ----

        EffectRow(
            stage = 18,
            name = "color wheel (wheelShadow/Mid/High X/Y)",
            neutral = "all six offsets 0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1110-1148 (developCpu stage 18; the partition-of-unity band weights, wheelActive gate)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind the six uWheel* uniforms",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 19,
            name = "split tone (splitShadowHue/splitHighlightHue/splitBalance)",
            neutral = "both hues -1f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1150-1202 (developCpu stage 19; hueGains raised-cosine windows, splitActive gate at 1163)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uSplitActive (the CPU-computed >= 0 gate; the -1 sentinel cannot ride a float uniform) + uSplitShadowHue/uSplitHighlightHue/uSplitBalance",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stage 20: saturation. ----

        EffectRow(
            stage = 20,
            name = "saturation (saturation)",
            neutral = "saturation=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1204-1223 (developCpu stage 20; f = 2^(0.75*sat/100), chroma around Rec.709 luma)",
            gpuSite = "DEVELOP_FRAGMENT_POST behind uSaturation",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stages 21-22: unsharp masks (COL-15, the sharpness/detail port). ----

        EffectRow(
            stage = 21,
            name = "sharpness (sharpness)",
            neutral = "sharpness=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.NEIGHBORHOOD,
            cpuSite = "core/src/Develop.cpp:1225-1244 (developCpu stage 21; unsharpLuma radius 1.5, unsharpLumaImpl at 133, amount clamp [-0.5,0.5] — negatives = softening)",
            gpuSite = "SHARPNESS_H (GlesShaderSources.kt:1894) + SHARPNESS_V (1980) — pipeline draws 6-7, gated `runSharp = req.sharpness != 0.0f && w >= 2 && h >= 2` (GpuDevelopPipeline.cpp Impl::draw)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 22,
            name = "detail (detail)",
            neutral = "detail=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.NEIGHBORHOOD,
            cpuSite = "core/src/Develop.cpp:1225-1244 (developCpu stage 22; unsharpLuma radius 0.75, negatives = softening)",
            gpuSite = "DETAIL_H (GlesShaderSources.kt:2065) + DETAIL_V (2147) — pipeline draws 8-9, gated `runDetail = req.detail != 0.0f && w >= 2 && h >= 2` (GpuDevelopPipeline.cpp Impl::draw)",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),

        // ---- Stages 23-24: tonemap + LUT (COL-16/COL-07). ----

        EffectRow(
            stage = 23,
            name = "tonemap (tonemap)",
            neutral = "tonemap=0 (off)",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1246-1261 (developCpu stage 23; applyManagedLutStage, Color.cpp:751; tonemapFrameImpl at Develop.cpp:428, acesFit at 392)",
            gpuSite = "DEVELOP_FRAGMENT_TAIL (GlesShaderSources.kt:1393) behind uTonemap — pipeline 'draw 11: the TAIL pass' (GpuDevelopPipeline.cpp Impl::draw), stages 23+24+OETF draw",
            hardware = "FLOAT_FBO",
            coverageToken = "none",
        ),
        EffectRow(
            stage = 24,
            name = "LUT (lutId/lutStrength)",
            neutral = "lutId=null or lutStrength=0f",
            inputDomain = "LINEAR_RGB",
            outputDomain = "LINEAR_RGB (+ the Rec.709 OETF tail, the bridge's own last step, composed once here)",
            effectClass = EffectClass.POINTWISE,
            cpuSite = "core/src/Develop.cpp:1246-1261 (developCpu stage 24; applyManagedLutStage, Color.cpp:751; the OETF tail composes in the JNI bridge after developCpu)",
            gpuSite = "DEVELOP_FRAGMENT_TAIL behind uLutStrength + the uLut* set (GlesShaderSources.kt:472-482; sampler3D unit 5)",
            hardware = "FLOAT_FBO + LUT_TEXTURE",
            coverageToken = "lut",
        ),

        // ---- The temporal pre-stage (outside the 24-stage contract). ----

        EffectRow(
            stage = STAGE_TEMPORAL,
            name = "temporal NR (temporalNr/temporalStrength)",
            neutral = "temporalNr=false or temporalStrength=0f",
            inputDomain = "CFA (neighbor CFAs merged into the center)",
            outputDomain = "CFA (the merged CFA feeds stage 1 of BOTH paths)",
            effectClass = EffectClass.TEMPORAL,
            cpuSite = "core/src/Temporal.cpp:558 temporalMergeCfa (the frozen merge math), driven by jni_bridge.cpp `CfaBuffer temporalMergedCfa(` (window assembly + ring cache, kTemporalRingCapacity)",
            gpuSite = "none — ROUTING, not porting: the merged CFA feeds the sessions as THE CFA (jni_bridge.cpp: every `temporalMergedCfa(` call site — develop session, encoder, preview session, export frame; the pair rides the parcel and dies there)",
            hardware = "NONE",
            coverageToken = "none",
        ),
    )

    /** Rows keyed by stage for the cross-check (1..24 unique; temporal = 0). */
    val ROWS_BY_STAGE: Map<Int, EffectRow> = ROWS.associateBy { it.stage }

    /**
     * The runtime fallback token vocabulary — the tokens a row's
     * [EffectRow.coverageToken] may carry. Mirrors the LIVE vocabulary:
     * fragmentUncoveredStages' single runtime leg ("lut",
     * RenderBackend.kt:378-380) — FRAGMENT_UNCOVERED_STAGES and
     * FRAGMENT_UNCOVERED_PRE_STAGES are both empty, so "lut" is the
     * entire vocabulary today. The cross-check test locks the match.
     */
    val COVERAGE_TOKENS: Set<String> = setOf("lut")

    init {
        // Structural honesty locks, checked at class-load: the registry
        // is data, but its OWN shape must be right before any test runs.
        // 24 develop stages + the temporal pre-stage = 25 rows.
        require(ROWS.size == CPU_STAGE_LAST - CPU_STAGE_FIRST + 2) {
            "EffectRegistry must hold exactly one row per develop stage (1..24) plus the temporal pre-stage"
        }
        // One row per stage, no duplicates, no gaps in 1..24.
        val stages = ROWS.map { it.stage }
        require(stages.size == stages.toSet().size) {
            "EffectRegistry rows must have unique stage numbers"
        }
        require((CPU_STAGE_FIRST..CPU_STAGE_LAST).all { it in stages }) {
            "EffectRegistry is missing a row for a stage in $CPU_STAGE_FIRST..$CPU_STAGE_LAST"
        }
        // The temporal pre-stage marker is exactly one row.
        require(stages.count { it == STAGE_TEMPORAL } == 1) {
            "EffectRegistry must hold exactly one temporal pre-stage row"
        }
        // Every token is in the live vocabulary.
        require(ROWS.all { it.coverageToken == "none" || it.coverageToken in COVERAGE_TOKENS }) {
            "EffectRegistry row carries a token outside the live fallback vocabulary"
        }
    }
}
