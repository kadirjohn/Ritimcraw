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
8. YUV — `YuvConverter.toI420` (Mp4Exporter.kt:550), BT.709 limited range
   (Y [16,235], C [16,240]), chroma from averaged 2x2 RGB blocks.
9. Encode — `MediaCodecMp4Writer.writeVideoFrame` feeds I420 into the
   encoder's input Image (COLOR_FormatYUV420Flexible, video/avc, High
   profile, 30 fps, i-frame 1 s, bitrate (w*h*24/10).coerceIn(2M, 25M)).
10. Mux — MediaMuxer. The writer is started with orientationDeg = 0
    (Mp4Exporter.kt:507) because the pixels are already upright — the
    container hint is intentionally dropped.

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
- NDK gap (verified against NDK 30.0.16248370): glShaderStorageBlockBinding
  — the only core ES 3.1 mechanism for SSBO block binding, since GLSL ES
  3.10 has no layout(binding=) for storage blocks — is declared in NO NDK
  GLES header and exported by NO platform stub libGLESv3.so (checked API
  31/34/35). The parity harness fetches the driver's implementation via
  eglGetProcAddress; a null pointer is reported as an honest parity-run
  failure (the CPU render path stays the default). Later GPU stages must
  use the same workaround (or move to ES 3.20 layout(binding=), which is
  NOT guaranteed on ES 3.1-only devices).
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
  No glReadPixels anywhere.
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
