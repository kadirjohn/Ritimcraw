# GPU groundwork baseline — current pipeline and stage contract

Owner request 2026-11-12 (ritiMCRAW GPU implementation prompt, deliverables A-C
scope: baseline doc, backend/encoder axis with real diagnostics + setting,
GLES context + capability probe + single-frame parity harness). This document
records the code-verified current state that the GPU work must preserve, the
stage contract the GPU path must match, and the honest evidence level of each
claim. Written against worktree HEAD 553f324.

Evidence vocabulary (owner prompt §16): planned / implemented / compiled /
host-tested / device-test-pending / owner-accepted / blocked. Every claim below
is code-inspection (the strongest level available without a device); nothing
in this document is device-verified.

## 1. Current export pipeline (code-confirmed)

The full frame path, native then Kotlin, as it exists at 553f324:

1. MCRAW decode — `rawedit::Source::readFrame` (core/src/Source.cpp, thin
   wrapper over the vendored motioncam-decoder) produces a full-res CFA
   buffer (uint16, row-major). One decode per exported frame; no frame cache.
2. Temporal NR (optional) — the JNI bridge applies motion-aligned temporal
   denoise to the CFA before develop when `grade.temporalNr` is set
   (jni_bridge.cpp; the CFA-domain stage, distinct from the spatial stages
   inside developCpu).
3. Full-res develop — `rawedit::developCpu` (core/src/Develop.cpp) runs the
   24-stage contract below and returns linear RGB (ImageF, float, linear
   Rec.709 working space).
4. Gyro warp — `applyStabilization` (jni_bridge.cpp) warps the
   STORED-ORIENTATION buffer after develop. Measured 44.9832 ms/frame at
   3840x2160 on the CPU path (existing doctest); a GPU warp is follow-up
   mission N4, not in scope here.
5. linearToRec709 -> clamp [0,1] -> 8-bit RGB888 — jni_bridge.cpp
   nativeExportFrame tail (toByte: clamp, round-to-nearest ties up, *255).
   Output is NOT rotated.
6. Kotlin upright — `RotationState.rotateRgb888` (Mp4Exporter.kt:538)
   rotates the RGB888 pixels by the effective orientation
   (container + manual, Mp4Exporter.kt:496).
7. Crop — `CropState.applyToRgb` (Mp4Exporter.kt:539), even-snapped dims.
8. YUV — the RGB888 -> I420 seam (the `RgbToI420` fun interface,
   Mp4Exporter.kt): the native twin first (nativeRgbToI420 /
   nativeRgbToI420Planes, jni_bridge.cpp over core/src/Yuv.cpp — scalar +
   NEON, byte-exact against `YuvConverter.toI420` by doctest AND a JVM
   cross-implementation oracle), the Kotlin `YuvConverter.toI420` as the
   packed fallback. BT.709 limited range (Y [16,235], C [16,240]), chroma
   from averaged 2x2 RGB blocks; the BYTES are identical either way.
9. Encode — the writer offers its dequeued input Image's plane buffers
   (`acquireVideoFramePlanes`); when the offer is taken, the native twin
   writes the planes DIRECTLY at their rowStride/pixelStride (no packed
   intermediate, no copy — commit (c)); any refusal falls back to packed
   I420 through `writeVideoFrame` (YuvPlaneIo.copyPackedToPlanes, the
   writer's original strided copy). COLOR_FormatYUV420Flexible, video/avc,
   High profile, 30 fps, i-frame 1 s, bitrate (w*h*24/10).coerceIn(2M, 25M).
10. Mux — MediaMuxer. The writer is started with orientationDeg = 0
    (Mp4Exporter.kt:507) because the pixels are already upright — the
    container hint is intentionally dropped.

Timing (commit (d)): the export stage-timing sample lines split the native
call's wall time into its six internals — the bridge times decode, temporal,
develop, warp, oetf+pack, and the JNI copy into thread-local slots (gated by
BuildConfig.DEBUG at library load, the gStabDebugLog pattern) and the pipeline
reads them right after the call. Debug builds render
"native=472.3ms(decode=..ms temporal=..ms develop=..ms warp=..ms oetf=..ms
copy=..ms)"; release builds and JVM fakes keep the plain "native=..ms" pair.

### Baseline finding: stale orientation comments

Two comments claim the export path passes orientation to
MediaMuxer.setOrientationHint; the code does not:

- jni_bridge.cpp:1866-1868 — "Not rotated: the exporter passes orientationDeg
  to MediaMuxer.setOrientationHint so players rotate at display time."
- NativeSource.kt:407-408 — "NOT rotated (orientation travels as the muxer's
  orientation hint)."

Reality (Mp4Exporter.kt:496-550): effectiveDeg is computed in Kotlin,
pixels are rotated by `rotateRgb888` BEFORE crop and YUV, and the muxer is
started with orientationDeg = 0. The comment is stale from an earlier
pipeline shape. No behavior bug — the pixels are upright and the muxer hint
is deliberately zero — but the doc comments misdocument the contract, and
any GPU work that trusted them would rotate twice. Recorded here as a
baseline finding; comment fixes are folded into the deliverable C commit
(the export tail's comment is adjacent to code that commit touches).

### Baseline finding: the demosaic is bilinear

The owner prompt's §12 parity reference and the package's GPU_RENDERER.md
describe the demosaic as "bilateral/RCD". The code is a plain bilinear
demosaic: `rawedit::demosaicBilinear` (core/src/Develop.cpp:627), exposed in
Develop.h for tests. Per site: own channel direct; missing channels are the
average of the same-color sites among the 8 neighbors (offsets kDx/kDy =
{-1,1,0,0,-1,-1,1,1} / {0,0,-1,1,-1,1,-1,1}); neighbor coordinates are
clamped FIRST, then the Bayer-parity site lookup (layout.cell[y&1][x&1]);
all four CFA patterns (RGGB/GRBG/GBRG/BGGR, CfaPattern in Source.h) are
supported. Any GPU port must reproduce THIS algorithm — a 2x2 cell debayer or
a directional demosaic must not replace it.

## 2. The 24-stage CPU contract (the parity reference)

From the developCpu order comment (core/include/rawedit/Develop.h:5-74).
The GPU path must reproduce this order exactly; a neutral grade must be
bit-identical to the CPU path.

| # | Stage | Guard / neutral |
|---|-------|-----------------|
| 1 | black/white normalization per CFA channel | always (levels) |
| 2 | lens shading gain (bilinear on the shading map; identity when empty) | keepVignetting, map non-empty |
| 3 | highlight reconstruction (clip to lowest clipped channel's level) | highlightReconstruction |
| 4 | bilinear demosaic (all four CFA patterns) | always |
| 5 | WB gains (division by asShotNeutral) | always |
| 6 | camera -> linear Rec.709 (DNG ForwardMatrix/ColorMatrix) | always |
| 7 | chroma denoise (edge-aware blur of chroma offsets; luma intact) | chromaDenoise != 0 |
| 8 | noise reduction (edge-preserving spatial luma smoothing) | noiseReduction != 0 |
| 9 | black point (toe remap; white pinned) | blackPoint != 0 |
| 10 | white point (shoulder remap; black pinned) | whitePoint != 0 |
| 11 | shadows (gain weighted toward black) | shadows != 0 |
| 12 | midtones (gain around midgray 0.18) | midtones != 0 |
| 13 | contrast (linear slope around midgray pivot) | contrast != 0 |
| 14 | exposure as 2^ev | exposureEv != 0 |
| 15 | highlights (monotone soft shoulder above the knee) | highlights != 0 |
| 16 | temperature (warm/cool diagonal gain) | !asShot, tempK != 5600 |
| 17 | tint (green/magenta gain) | !asShot, tint != 0 |
| 18 | color wheel (three tonal bands, per-band diagonal gains) | wheel offsets != 0 |
| 19 | split tone (shadow/highlight hue tinting, balance crossover) | splitShadowHue/HighlightHue != -1 |
| 20 | saturation (chroma scaling around luma; neutrals stay neutral) | saturation != 0 |
| 21 | sharpness (luma unsharp mask, radius 1.5 px) | sharpness != 0 |
| 22 | detail (luma unsharp mask, radius 0.75 px) | detail != 0 |
| 23 | tonemapping (ACES / AgX variants; after grade, before the OETF) | tonemap != 0 |
| 24 | LUT (optional .cube 3D LUT; rawedit/Lut.h) | lut != nullptr, lutStrength > 0 |

Corrections vs the package docs: the color wheel is stage 18 and split tone
stage 19 (the owner's stage list in GPU_RENDERER.md numbers the wheel at
18 — this table follows the code's own numbering). The tone controls 9-13 run
scene-referred (pre-exposure) by design: their pivots classify tones of the
scene, not the post-exposure image. The denoise stages 7-8 run early (right
after the camera->Rec.709 transform, before every tone control) because the
later unsharp masks amplify surviving noise. Every stage is guarded by
`field != neutral`, so a default-constructed Grade executes none of the
toggleable stages and is bit-identical to the pre-grade pipeline (locked by
test_develop.cpp golden values).

Stage-1 level selection note (parity-relevant): normalization uses
frame-preferred levels — per-frame dynamicBlack/dynamicWhite when the frame
carries them (FrameInfo.dynamicBlack[4]/dynamicWhite, Source.h:53-56), else
the container RawMeta.black/white. A GPU port must carry the same
frame-preferred fallback or parity breaks where per-frame levels exist.

## 3. Render backend axis (owner prompt §4)

The setting is the REQUESTED backend; the EFFECTIVE backend is a separate,
reported quantity. The two axes are orthogonal to the video encoder choice:

- requestedRenderBackend: CPU / AUTO / GPU_EXPERIMENTAL (user setting,
  persisted; additive schema, omit-when-default so existing project files
  are untouched).
- effectiveRenderBackend: CPU / GLES / HYBRID (computed per export job from
  the requested backend + the runtime capability probe + the parity gate;
  reported honestly, never silently).

Semantics (owner prompt §14, binding):

- CPU: the current reference path, unchanged. Selecting CPU must NOT
  prevent hardware video-encoder use — the render backend and the encoder
  are independent axes.
- AUTO: GPU only when the capability probe AND the parity gate pass; else
  CPU with a logged fallback reason.
- GPU (Experimental): request GPU; if unavailable, fall back to CPU and
  report the effective backend honestly (the user asked for GPU, the log
  and the job detail say what actually ran).
- The choice is global and persists; an export job snapshots requested
  backend + grade at job start and never changes mid-run when the global
  setting changes.
- CPU stays the safe default until device acceptance.

## 4. Encoder axis (owner prompt §4)

The real encoder is a runtime fact, not a build assumption. The writer must
record, per export, the REAL encoder identity from the created codec:

- codec.codecInfo.name (the instance's codec info)
- codec.canonicalName
- codec.codecInfo.isHardwareAccelerated (API 29+; minSdk 34 — free to use)
- codec.codecInfo.isSoftwareOnly (API 29+)
- distinct from the codec LIST probe (MediaCodecList) — what is available
  vs what was actually created for the export
- max width/height support kept separate from resolution support, not
  merged into one "resolution" flag

The existing CodecProbe.hardware flag is a name heuristic
(!OMX.google. && !c2.android., CodecProbe.kt) — the prompt orders it
replaced with the real API flags. The vendor's hardware flag is recorded
but is not independent proof of dedicated silicon (it reflects the
vendor's declaration); the doc records it as declared-hardware.

Diagnostics separate requested vs effective vs real: the requested
MIME/profile (video/avc, High) is what we ask for; the created encoder's
name/canonical/hardware/softwareOnly is what actually ran.

## 5. GLES backend facts (deliverable C scope)

- GLES 3.1 + compute shaders is the chosen backend (Vulkan not required for
  compute). EXT_color_buffer_float is required only by LATER float-FBO
  stages; the deliverable-C parity harness stays GLES 3.1 core (SSBOs in
  and out, no textures, no image units, no float framebuffers), so it
  runs on any ES 3.1 device. The extension is still probed at runtime for
  the later stages (not assumed).
- EGL context: created/released safely on a background thread; no window
  surface required for compute + readback (pbuffer/offscreen suffices).
  Topology follows the grafika EglCore pattern (Apache-2.0 reference,
  sources/google__grafika — topology only, no verbatim vendoring).
- FP16 vs FP32 intermediates: RGBA16F at 3840x2160 is ~63.3 MiB; RGBA32F
  ~126.6 MiB. Texture-filterable and framebuffer-renderable are separate
  properties (RGBA16F filterable, RGBA32F not filterable on many GPUs).
- highp in shaders; do not early-clamp scene-linear values.
- SSBO binding (CORRECTED 2026-11-13 — the previous "NDK gap" explanation
  was WRONG): glShaderStorageBlockBinding is not an NDK header gap at all
  — the command is NOT part of OpenGL ES in any version (it is desktop GL
  4.2+), so eglGetProcAddress can only ever return null for it on an ES
  context, and the old parity path that required it stopped there on every
  device. The ES-native mechanism (GLSL ES 3.10 spec) is explicit
  layout(std430, binding = N) on the shader storage blocks, matched on the
  C++ side by the core ES 3.1 glBindBufferBase(GL_SHADER_STORAGE_BUFFER,
  N, buf). The parity harness now uses exactly that: InBuf at binding 0
  (readonly), OutBuf at binding 1 (writeonly), both std430 — the packing
  the C++ side's tight float arrays assume, now declared instead of
  implied. GLSL ES 3.10 does support layout(binding=) for storage blocks;
  the earlier claim that it does not was part of the same error.
- Compute=0 device diagnoses before 2026-11-13 are UNRELIABLE: the probe
  queried GL_MAX_COMPUTE_WORK_GROUP_COUNT non-indexed (glGetIntegerv) —
  but that enum is per-axis INDEXED and must be read with
  glGetIntegeri_v per axis; the non-indexed query can fail and leave the
  zero-initialized array at 0, reporting compute=0 on hardware that fully
  supports ES 3.1 compute (the owner's Xiaomi 15 Ultra / Adreno 830 is
  exactly this shape). The probe now queries all three axes of
  GL_MAX_COMPUTE_WORK_GROUP_COUNT (and GL_MAX_COMPUTE_WORK_GROUP_SIZE)
  with glGetIntegeri_v and gates computeOk on all three dispatch-count
  axes being > 0. The device finding is re-testable: re-run Measure after
  this fix — a compute-capable device should now report compute=1.
- glReadPixels is used NOWHERE — not even the single-frame prototype
  parity harness (deliverable C reads back through a MAPPED shader
  storage buffer, glMapBufferRange); production preview/export paths
  keep the result on the GPU, so no readback path exists to migrate.
- Host honesty: this host has no EGL (desktop) and no GLSL compiler
  (glslangValidator/glslc absent). The shader source is validated by a
  self-written host syntax check (balanced delimiters, version/precision
  statements, declared variables) and the real compile is
  device-test-pending. No GLSL compile claim is made on the host.

## 6. Evidence levels for this stage

- Baseline pipeline facts: code-inspection (verified this session, listed
  with file:line above).
- 24-stage contract: code-inspection of Develop.h/Develop.cpp; the CPU
  golden values (test_develop.cpp) are host-tested in the desktop build.
- GPU backend (deliverable C code): compiled + host-tested where the
  language allows (Kotlin/JVM tests for pure decision rules; native pieces
  built by tools/build-desktop.sh); EGL/GLSL runtime behavior is
  device-test-pending.
- No device data exists for any claim in this document. Owner device tests
  for this stage are listed in the final handback.

## 7. Deliverable C status (implemented this stage)

Safe GLES context + capability probe + single-frame bilinear demosaic
parity — device-independent portion complete:

- Native (Android-only, compiled by :app:assembleDebug):
  `app/src/main/cpp/gles/GlesPipeline.{h,cpp}` — grafika-topology
  EglSession (open/release on the calling thread, safe on partial
  sessions), capability probe (context, version, compute,
  EXT_color_buffer_float, max texture size), and the parity harness:
  CPU stage-1 copy -> SSBO in -> GLSL ES 3.10 compute demosaic -> SSBO
  out -> glMapBufferRange readback -> CPU stage-5 tail -> uint16-domain
  comparison against rawedit::developLinear itself (untouched, exported).
  No glReadPixels anywhere. Audit fix 2026-11-13: the probe reads
  GL_MAX_COMPUTE_WORK_GROUP_COUNT/SIZE per axis with glGetIntegeri_v
  (the indexed query the spec requires — the old non-indexed query could
  fail silently and report compute=0 on compute-capable hardware), and
  the parity path binds SSBOs the ES-native way (shader-side
  layout(std430, binding=0/1) + C++ glBindBufferBase — the
  glShaderStorageBlockBinding lookup is gone: that command is desktop GL
  only, never ES).
- JNI seam (stateless, like nativeGyroSyncProbe):
  `nativeGlesProbe` -> [contextOk, major, minor, computeOk, floatFboOk,
  maxTextureSize]; `nativeGlesParity(fd, computeSource)` -> [ran,
  maxDiffLsb, mismatchCount, samples]. The device compiles the exact
  caller-provided Kotlin shader text.
- Kotlin (JVM-tested):
  `GlesShaderSources` (the single GLSL source of truth),
  `GlesShaderValidator` (host syntax + contract check — NOT a compile),
  `GpuParity` (JNI array contracts, PASS <= 1 LSB and <= 0.01% share,
  gatesFrom(probe) never claims parity, log-line formats),
  `PreviewMetrics.measureGles` (Measure leg 4 — the owner's one-tap
  device entry point; logs under "ritim-pm").
- Verdict lives ONLY in Kotlin (GpuParityRules); the C++ harness reports
  raw numbers, never a decision.
- Status: compiled + host-tested (JVM); EGL/GLSL runtime behavior
  device-test-pending. Owner device test: run Measure on a clip, read
  the "ritim-pm" gles block — probe line, then either a parity line +
  verdict or an honest skip/failure line.

## 8. Deliverable D status (implemented this stage)

Fragment-shader develop pipeline + encoder Surface export connection —
the first REAL GPU render path (a draw, not a compute dispatch):

- Native (Android-only, compiled by :app:assembleDebug): the two-pass
  fragment parity harness in `app/src/main/cpp/gles/GlesPipeline.{h,cpp}`
  — DEVELOP_FRAGMENT (one ES 3.00 fused pass, stages
  1+2+3+4+5+6+14+23+OETF behind uniforms; 2, 3 and 23 ported this stage)
  renders into an RGBA32F
  attachment (EXT_color_buffer_float, gated at runtime), STORE_FRAGMENT
  (ES 3.10 + SSBO) moves the attachment to a buffer for
  glMapBufferRange readback; the CPU tail clamps + quantizes uint16
  exactly like the developLinear tail. glReadPixels is used NOWHERE
  (the recorded baseline invariant). THREE parity cases (a caseIndex
  argument selects): case 0 — the developLinear shape (uHasShading=0,
  uHr=0, uHasProfile=0, uExposure=1.0, uOetf=0, uTonemap=0) against
  rawedit::developLinear itself (untouched) — the Measure leg keeps this
  case, byte-identical verdict; case 1 — the DEFAULT grade's real stage
  list: uHasShading = the clip's map-present gate with the real 4-plane
  map uploaded as a GL_R32F texture (planes stacked vertically, the
  layout the shader's texelFetch reads), uHr=1 with uHrGain =
  meta.wbGains with the >0 guard (a DIFFERENT vector from uWb's
  1/asShotNeutral); developLinear cannot serve as that case's reference,
  so the reference is computed C++-side from the exported rawedit
  primitives (rawedit::sampleShading, rawedit::demosaicBilinear) plus
  documented copies of the stage-1/2/3/5 loops — core/src/Develop.cpp
  stays untouched (READ ONLY), and any drift between the two copies
  shows up as a parity mismatch, exactly what the harness exists to
  catch; case 2 — the TONEMAP sweep (stage 23, this stage's port):
  uTonemap runs modes 1 (ACES Narkowicz fit) through 5 (AgX Base), one
  full develop+store+readback pass per mode, and the per-mode reference
  is rawedit::developCpu ITSELF — the real exported stage list, not a
  hand copy — called with a default Grade whose only active control is
  tonemap=mode and a meta copy with forwardMatrix/colorMatrix pinned to
  identity (buildCameraToRec709 returns false for an identity profile,
  so developCpu skips stage 6, matching uHasProfile=0). The verdict
  accumulates the WORST case across the five modes and samples counts
  all five passes (5*w*h*3) — the same PASS rule per mode as ever
  (maxDiffLsb <= 1, mismatchCount*10000 <= samples).
- JNI seam: `nativeGlesFragmentParity(fd, vs, vs310, developFs, storeFs,
  caseIndex)` -> [ran, maxDiffLsb, mismatchCount, samples]; the device
  compiles the exact Kotlin shader text (GlesShaderSources is the
  single source of truth; GlesShaderValidator host-checks the same text
  in JVM tests — the host has no GLSL compiler, so the real compile is
  device-test-pending, honestly).
- Fragment-leg gate (RenderBackend.BackendGates.fragmentAllowed):
  context + floatFbo + parity — NOT compute (the chain is a draw
  pipeline; a compute-less ES 3.1 device can still pass this leg). The
  compute leg's four-gate gpuAllowed() math is untouched (locked by
  RenderBackendTest); the fragment rules are ADDED alongside.
- Stage coverage (BackendRules, pure JVM, host-tested): the fragment
  pass covers stages 1+2+3+4+5 fused (+6/14/23/OETF behind uniforms);
  FRAGMENT_UNCOVERED_STAGES lists every other stage with its CPU guard
  mirrored exactly (field != neutral / toggle). Stages 2, 3 and 23 LEFT
  the uncovered list when they were ported: the DEFAULT grade
  (keepVignetting + highlightReconstruction both true, no other edits)
  now has NO active uncovered stage, so a default-grade GPU request
  stays on GPU — the point of this port. Stage 2's map-state guard
  lives in the shader's uHasShading uniform (the CPU-side gate computed
  where the map state is known:
  BackendRules.fragmentShadingApplies(grade, hasShadingMap) =
  grade.keepVignetting && hasShadingMap — the same guard developCpu
  stage 2 runs; the CPU path keeps its own copy inside developCpu, the
  documented asymmetry), and stage 23's mode lives in uTonemap (the
  ACES Narkowicz fit for mode 1 and the full AgX family — Punchy with
  its legacy operation order preserved, Golden, Greyscale, Base — the
  CPU's exact math, so a tonemap-active grade no longer forces the CPU
  path either). The remaining grade chain (denoise, tone controls,
  wheel/split, LUT) is D+ and NOT ported; active uncovered stages
  force CPU with their token in the line.
- Encoder Surface export (deliverable F's core, MediaCodecMp4Writer):
  COLOR_FormatSurface + createInputSurface + an EGL window surface
  (grafika EglCore topology, Kotlin EGL14/EGLExt); the developed frame
  draws to the encoder surface with eglPresentationTimeANDROID
  (PtsTimeline.ptsNs — ptsUs*1000 with the overflow check, never
  wall-clock) before eglSwapBuffers; drain/mux/audio unchanged. Mode
  selection is EXPLICIT: only renderBackend == GPU_EXPERIMENTAL requests
  the mode (requestEncoderSurfaceInput before start) and only it makes
  the per-frame offer (writeVideoFrameRgb); CPU/AUTO exports make NO
  dispatch at all, byte-identical to before. Writer start failure =
  SurfaceInputUnavailable -> the exporter discards the partial, logs
  the fallback line, and restarts the WHOLE export on CPU with a FRESH
  writer (never a mid-file format switch, never a partial DONE);
  mid-stream GL failure is a plain encode failure (the restart contract
  covers writer START only). RenderBackend.GPU_EXPERIMENTAL is
  snapshotted per export job (ExportJob -> ExportStore omit-when-default
  -> AppNav enqueue -> Mp4Exporter), so a settings change never affects
  a running job.
- Preview stays CPU (preview GPU is deliverable E, device-test-pending,
  documented separately); export jobs pick the backend per the snapshot.
- Status: fragment develop pipeline — implemented + compiled +
  host-tested (parity rules, validator, coverage rules; EGL/GLSL runtime
  behavior device-test-pending — no GL on this host). Stages 2, 3 and
  23 are ported and host-verified at the text level (validator mirrors
  the CPU kernel lines; the C++ case-1 reference copies the Develop.cpp
  loops verbatim; case 2 uses developCpu itself as the reference, so
  the tonemap math has no hand copy to drift), but their GPU run is
  device-test-pending like the rest.
  Encoder Surface mode — implemented + compiled, JVM seam-tested
  (Mp4ExporterTest 70/70); device-test-pending. Owner device test:
  Settings -> GPU Experimental, export a DEFAULT-grade clip (no other
  edits) — expect the Surface-input encoder path with NO fallback line
  (stages 2, 3 and 23 are covered now, so a tonemap grade exports on GPU
  too); a grade with an active uncovered stage (e.g. chroma denoise)
  still honestly logs the fragment fallback line and runs CPU. Run
  Measure for the probe + all three parity legs (the fixed indexed
  probe should now report compute=1 on the owner's Xiaomi 15 Ultra /
  Adreno 830).
