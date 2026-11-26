# Effect templates — the three working recipes

Owner request (2026-11-15): every develop effect added to this codebase
should ride a copy-paste-ready recipe instead of re-deriving the seam
map each time. This document is those recipes, and each one is VALIDATED
BY REFERENCE — every step below was lifted from a real commit that
shipped in this repository, with the commit hash, the file:line, and the
test that proves it. Nothing here is speculative.

Evidence vocabulary (same as GPU_BASELINE.md): a step is **planned**
(written, not yet run), **implemented** (code exists), **compiled**
(assembleDebug green), **host-tested** (JVM suite green),
**device-test-pending** (needs a phone; no adb run recorded), or
**blocked**. The recipes below cite their validation commits as
host-tested; the device pass is a step in every template, not an
afterthought.

Two shape notes, honestly stated:

- **Refreshed 2026-11-23** (the acceptance audit
  docs/ACCEPTANCE_GAPS_2026-11-16.md listed 10 stale entries). Both
  refactors this document originally called "ahead in the queue" are
  MERGED: the **grade parcel** (`engine/GradeParcel.kt` — one packed
  parcel crosses JNI instead of per-field parameters; merge c88521f)
  and the **shared GpuDevelopPipeline**
  (`app/src/main/cpp/gles/GpuDevelopPipeline.{h,cpp}` — both develop
  sessions delegate their whole chain to `pipeline.draw()`; merge
  73ebfd3). The recipes keep their historical validation commits (those
  commits really did cross the old seams), and each step below now says
  where the seam lives TODAY. Sites in churning files (the pipeline,
  jni_bridge.cpp) are cited by SYMBOL — grep the name — because line
  numbers there move nightly.
- The registry (`app/src/main/java/com/ritim/ritimcraw/engine/EffectRegistry.kt`,
  commit 7a2887e) is the checklist for these recipes: **step zero of
  every template is "add the row"**, and the cross-check test
  (`EffectRegistryTest`, 11 tests) fails if the row and the live gates
  drift apart. The row is the checklist; the gates are the truth.

---

## 1. The pointwise color-control template

Validated by reference: the stage-15 highlights port, commit **0efcc75**
("feat: port stage 15 highlights into develop fragment", 12 files,
532 insertions). A pointwise effect is one output pixel per input
pixel — no neighbors, no extra passes, no extra textures. It is the
cheapest effect to add and the best template to learn the seam map on.

### 1.1 The seam map (every file a new knob must cross)

The highlights port crossed exactly these seams, in this order:

1. **GradeK field** — `app/src/main/java/com/ritim/ritimcraw/data/GradeStore.kt`
   (fields at 96-195). A new knob is a new field with a **neutral
   default** and **omit-when-neutral parcel rendering**, so an untouched
   grade stays byte-identical. TODAY this is also one slot in
   `engine/GradeParcel.kt` (the `SLOT_*` constants + the pack/unpack
   pair) — the parcel is how the value crosses.
2. **CPU stage** — `core/src/Develop.cpp`, `developCpu`, the
   24-stage contract. Stage 15 lives at 1380-1420: knee 0.18 (history
   0.75 → 0.40, then 0.18 merged `b884460`; the curve versioning was
   removed in `6b62faa`), the
   monotone shoulder `shoulder = knee + span*(t/(1+t))` with
   `s = -highlights/100`, uniform gain `newMx/mx`. The CPU stage is
   the reference implementation the GPU must match.
3. **Request struct** — TODAY `app/src/main/cpp/gles/GpuDevelopPipeline.h`:
   `struct DevelopFrameRequest` ("THE shared request shape, promoted
   here from GlesDevelopSession.h") gains the field; the pipeline binds
   it per frame. (At 0efcc75 the struct lived in GlesDevelopSession.h.)
4. **JNI** — `app/src/main/cpp/jni_bridge.cpp`: TODAY the value is
   unpacked from the grade parcel (grep the parcel unpack next to
   `temporalMergedCfa`'s "rides the parcel" comment) into the request —
   no JNI signature widens. Historically (0efcc75/4d2a328) it was a
   `jfloat` parameter; the rule that survived: **no default parameter
   values in the JNI layer** — Kotlin synthesizes `$default` overloads
   that break the reflection locks.
5. **Kotlin declarations** — `app/src/main/java/com/ritim/ritimcraw/engine/NativeSource.kt`:
   external declarations + wrappers for every entry point that renders
   (develop session, preview session, exporter).
6. **Dispatch sites** — `GpuDevelop` + `NativeGpuDevelop` + both
   `Mp4Exporter` dispatch sites thread the grade value verbatim.
7. **Shader uniform** — `app/src/main/java/com/ritim/ritimcraw/engine/GlesShaderSources.kt`:
   `uHighlights` (uniform declared at 447), and the GLSL block placed
   at the exact contract position — for stage 15 that is between
   stage-14 exposure and stage-23 tonemap (order 14 → 15 → 23 → OETF
   in the fused pass; the validator locks this four-block order).
8. **Validator** — `app/src/main/java/com/ritim/ritimcraw/engine/GlesShaderValidator.kt`:
   the uniform joins the contract; exact-substring checks pin the GLSL
   block; the const-ban extends to uniform-derived locals (a value
   computed FROM a uniform must not be `const` — the host can't fold
   it).
9. **Coverage gate** — `app/src/main/java/com/ritim/ritimcraw/engine/RenderBackend.kt`:
   `15` joins `FRAGMENT_COVERED_STAGES` (251-252). A pointwise stage
   needs NO runtime fallback leg — its only hardware fact is the
   float-FBO every fragment draw already rides.
10. **Registry row** — `engine/EffectRegistry.kt`: the new row citing
    both live sites (CPU `Develop.cpp:1380-1420`, GPU
    `DEVELOP_FRAGMENT_POST behind uHighlights`). The cross-check test
    fails without it.

### 1.2 The GLSL block (the actual port, verbatim from 0efcc75)

Historical text — the 0efcc75 block had `knee = 0.75`; the shipped knee
is 0.18 today (2026-11-26); nothing else in the block changed.

```glsl
if (uHighlights != 0.0) {
    const float knee = 0.75;
    const float span = 1.0 - knee;
    float s = -uHighlights / 100.0;
    float mx = max(c.r, max(c.g, c.b));
    if (mx > knee) {
        float over = mx - knee;
        float t = over / span;
        float shoulder = knee + span * (t / (1.0 + t));
        float newMx = mx + s * (shoulder - mx);
        float gain = mx > 0.0 ? newMx / mx : 1.0;
        c *= gain;
    }
}
```

Note what the port did NOT do: no new pass, no new texture, no new
draw. The block sits inside the existing fragment pass and reads the
stage's input color `c` — that is the whole pointwise shape.

### 1.3 The host parity harness (the proof the block matches the CPU)

The discipline (comment at `GlesShaderValidatorTest.kt` ~1995): the
CPU loop's Kotlin transcription vs the shipped GLSL block's Kotlin
transcription, compared **after the readback quantize** (clamp [0,1],
`v*255+0.5`), over representative pixels at the knob's extremes and
its neutral. The highlights port ran ten representative pixels at
-55/+55/0: **0 mismatched bytes, 0 LSB**. This is a text-vs-text host
check — it is NOT a device parity claim; the device pass (1.5) is what
closes it.

### 1.4 The tests that locked the port

- `highlights_grade_covers_the_fragment_path`
  (`RenderBackendTest.kt:435`) — `listOf(-55f, 55f).forEach`:
  `fragmentUncoveredStages(grade).isEmpty()` and the effective backend
  resolves `GLES`. This is the gate idiom: an active knob must leave
  NO uncovered stage.
- Four grade-mutation tests (neutral omission, parcel round-trip).
- JNI signature locks: the readback counts moved 7→8 / 9→10 — the
  reflection locks fail loudly when a signature changes without all
  its call sites.
- Host numeric parity (1.3) inside `GlesShaderValidatorTest.kt`.

### 1.5 The honest checklist (end of EVERY template)

The JVM suites cannot catch runtime composition or EGL failures. The
recorded proof is commit **051e635** ("fix: settings crash negative
padding"): `IllegalArgumentException 'Padding must be non-negative'`
from `PaddingElement` via `SettingRowExpandable` — a crash no JVM test
could catch, because no unit test composes UI, and none runs EGL. So
the template is not finished at host-green:

- [ ] host suite green (`:app:testDebugUnitTest`)
- [ ] `:app:assembleDebug` green
- [ ] **device pass**: install, enable the knob at its extremes on a
      real clip, watch a full develop render (preview + export), no
      crash, no GL error, output visually matches the CPU path
- [ ] record the result honestly: `device-test-pending` until the
      device pass runs; never write `owner-accepted` without it

---

## 2. The two-pass neighborhood template

Validated by reference: the chroma-NR multi-pass port **1d93555**
("feat: spatial nr in develop fragment (multi-pass)"), the
sharpness/detail port **c2522f5** ("feat: sharpness detail in develop
fragment", 17 files, 2937 insertions), and the seam-widening commit
**4d2a328** ("feat: sharpness detail cross the seams"). A neighborhood
effect needs neighboring pixels — which the fragment pass cannot see —
so it rides its own H/V pass pair with ping-pong slots.

### 2.1 The pass structure (H then V, ping-pong)

The shared pipeline's draw chain (GpuDevelopPipeline.cpp `Impl::draw`, the
"draw 1".."draw 11" section comments; at 4d2a328 it was the session's
own chain in GlesDevelopSession.cpp) runs a FIXED pass structure; only
the individual draws are gated. The slots ping-pong
via `slotAfter[] = {texB, texC, texA}` and `fboOf`, and the pass
intermediates are allocated **once per resolution** — never per frame.
The NR port split the chain at stages 6/9 (PRE covers 1-6, POST
covers 9-20); the sharpness port then split POST three ways at 20/23
(POST = 9-20, TAIL = 23/24 + OETF + the 11-strong LUT uniform set),
and the shader source count grew NINE → FOURTEEN (TODAY those fourteen
sources are the shared pipeline's `init()` parameters, not a session
`open()` count).

The pass kinds (GlesShaderSources.kt): `NR_CHROMA_H` (1428),
`NR_CHROMA_V` (1575), `NR_LUMA` (1726), `SHARPNESS_H` (1894),
`SHARPNESS_V` (1980), `DETAIL_H` (2065), `DETAIL_V` (2147).

### 2.2 The uGuide pattern (the neighborhood's anchor)

The V pass reads two textures: `uSrc` (the H pass's output — the
half-blurred plane) and `uGuide` (the stage's ORIGINAL input, bound at
texture unit 4). The guide anchors range weights to unfiltered luma so
the second pass cannot compound the first pass's softening. The chroma
V pass uses the same guide discipline; the luma NR is a single-pass
3x3 bilateral (`wd=exp(-1)`, `we=exp(-0.5)`, `sr=0.05`) and needs no
pair. The sharpness V pass FUSES blur completion + high-pass + amount
blend into ONE draw — a two-pass filter does not have to mean two
unbalanced passes; put the work where the data already is.

### 2.3 The kernels stay verbatim

The GPU kernels are the CPU kernels verbatim: `unsharpLumaImpl`
(core/src/Develop.cpp:133) transcribed exactly, and the CPU stage's
`amount = min(max(0.5*(raw/100), -0.5), 0.5)` with radii 1.5/0.75
(stage 21/22 at Develop.cpp:1225-1244). **No [0,100] clamp** —
negative values are softening, and the seam commit proved it with a
negative-detail (-15f) pass-through test. Transcribing the kernel
twice "more GLSL-idiomatically" is how parity dies; transcribe it.

### 2.4 The w/h guards (the gates that keep draws off tiny inputs)

Each neighborhood draw is gated on BOTH the knob and the resolution,
because a 1-pixel-wide plane cannot feed a 3-tap filter:

- chroma NR: `runChroma = chromaStrength > 0.0f && w >= 2 && h >= 2`
- luma NR: `runLuma = lumaStrength > 0.0f && w >= 3 && h >= 3`
- sharpness: `runSharp = req.sharpness != 0.0f && w >= 2 && h >= 2`
- detail: `runDetail = req.detail != 0.0f && w >= 2 && h >= 2`

(all four in GpuDevelopPipeline.cpp `Impl::draw` — grep the variable names)

Note `raw != 0.0f`, not `raw > 0f` — negatives are real work.

### 2.5 The fused-pass recomposition lock

The fused `DEVELOP_FRAGMENT` shader is the parity reference for the
whole chain, and its bytes are locked: after the sharpness port the
recomposed fused source was proven **byte-identical** host-side
(36367 == 36367 bytes). Splitting the chain must not perturb the
fused path. If your pass split changes the fused source by even one
byte, the frozen harness catches it.

### 2.6 The seams this template crosses beyond template 1

HISTORICAL: the seam commit 4d2a328 widened every JNI surface
(renderFrame 35→37, encoder 37→39, preview 36→38). TODAY a new knob
widens NO JNI surface — it rides the grade parcel (`GradeParcel.kt`).
The rules it recorded still hold:

- **No default parameter values** in the JNI layer (the `$default`
  synthetic breaks the reflection locks).
- Every timing token (`ExportStageTiming "unsharp"`) fires on
  `raw != 0` — negatives count as active.
- All three render entry points (preview, export frame, develop
  session) move in ONE commit — a knob that renders in preview but
  not export is a half-born effect.

### 2.7 The tests that locked the port

- Host numeric parity per kernel: `cpuUnsharpStage` /
  `glslUnsharpStage` (`GlesShaderValidatorTest.kt:3007/3067`), run
  over mixed and flat 9x9 neighborhoods at both radii and both
  extremes (`:3129-3149`), compared after the readback quantize.
- Coverage tests in `RenderBackendTest.kt` and the exporter/preview
  suites threading the new pair through every dispatch site.
- The negative-detail pass-through test (no clamp).

### 2.8 The honest checklist

Same as 1.5, plus the neighborhood-specific device steps:

- [ ] host suite green; `:app:assembleDebug` green
- [ ] **device pass**: the knob at 0 / negative / positive extremes
      on a real clip; full-res preview AND export; watch for the
      failures only EGL can produce (shader compile error on the
      vendor's GLSL, FBO incomplete at odd resolutions, the w/h guard
      skipping on a 1-pixel edge crop)
- [ ] record honestly: `device-test-pending` until then

---

## 3. The temporal-effect template

Validated by reference: the routing commit **6e3f59a** ("feat:
temporal merge feeds gpu develop") and the cutover commit **66b02dd**
("feat: temporal nr rides the gpu path"). A temporal effect is NOT a
shader port — the merge math stays the frozen CPU's
(`temporalMergeCfa`, core/src/Temporal.cpp:558), and the GPU leg is
ROUTING: the merged CFA feeds the sessions as THE CFA. The registry
row for the temporal pre-stage says exactly this (`gpuSite` starts
with "none"; `EffectRegistryTest.the_temporal_row_is_routing_not_a_shader_port`
locks it).

### 3.1 The CPU pre-stage merge with per-Entry caches

`temporalMergedCfa` (jni_bridge.cpp — grep `CfaBuffer temporalMergedCfa(`)
wraps the frozen merge with per-Entry caches: the ring LRU
(`kTemporalRingCapacity = 4`), a frame+strength-keyed merged-CFA cache,
and a MAFD pair cache. The caches are the performance contract — without
them the merge re-runs per frame at full res (~85-107ms).

Two honesty rules the routing commit recorded:

- **Off means pristine AND cache-dropped**: when the grade's temporal
  pair is off, the bridge hands back the pristine center and DROPS
  the temporal caches — byte-identical to the pre-hybrid path. An
  off knob must not leave warm caches that make the next on-toggle
  lie about its first frames.
- **Neighbor decode failure FAILS the render** — never a silent
  pristine fallback. A temporal effect that silently degrades to
  no-op on a broken neighbor is lying to the user.

### 3.2 The session-feed routing (all six call sites)

The merged CFA replaces the request's CFA at every entry point —
the session request structs need NOTHING new:

- develop session: a `temporalMergedCfa(` call, then
  `req.cfa = cfa.get()`
- preview session: the merge runs BEFORE `strideFor` so the reduced
  mosaic samples the MERGED CFA, not the raw one
- encoder, export frame, nativePreviewRender step 2, and the cached
  preview render: the same call (grep `temporalMergedCfa(` in
  jni_bridge.cpp — every hit is one routing site; line numbers are
  deliberately not cited)

The JNI signatures gained the pair (`temporalNr jboolean`,
`temporalStrength jfloat`) after the NR pair on all three GPU
session-render entry points — the pair **dies at the bridge**; it
never enters a shader or a request struct.

### 3.3 The cutover (leaving the fallback vocabulary)

The routing commit landed with all dispatch sites threading INERT
(false/0) — the hybrid existed but no grade used it. The cutover
commit 66b02dd then:

- removed the temporal token from
  `FRAGMENT_UNCOVERED_PRE_STAGES` (now `emptySet()`,
  RenderBackend.kt:327) — the pre-stage is no longer a fallback
  reason, it is the path;
- threaded the grade pair verbatim at every dispatch site;
- added the eligibility gate in the editor
  (`a_temporal_nr_grade_is_eligible_since_the_hybrid`,
  EditorPreviewLogicTest.kt:899) with the DN-08 tier fold: the merge
  is tier-independent (~85-107ms at full res), so playback and drag
  SKIP it and settle lands it; `RenderTrace tnr=` computes the same
  fold so the trace and the gate can't disagree; export is NEVER
  tier-gated.

Landing the machinery and the cutover as separate green commits is
part of the template: the inert-routing commit proves the hybrid
changes nothing for existing grades, and the cutover commit is then a
pure dispatch-site diff.

### 3.4 The tests that locked the port

- `temporal_nr_rides_the_gpu_path_since_the_hybrid`
  (`RenderBackendTest.kt:694`) — the coverage lock: an active temporal
  grade leaves no uncovered pre-stage.
- `temporal_grade_rides_the_gpu_path_since_the_hybrid`
  (`Mp4ExporterTest.kt:2826`) — the exporter threads the pair.
- `session_encoder_temporal_threads_the_grade_pair_into_the_blit`
  (`Mp4ExporterTest.kt:3015`) — the blit-level lock.
- Signature locks moved readback 39→41 / encoder 41→43 / preview
  40→42 (the routing commit's reflection lock).

### 3.5 The honest checklist

Same as 1.5, plus the temporal-specific device steps:

- [ ] host suite green; `:app:assembleDebug` green
- [ ] **device pass**: toggle the temporal knob mid-playback (the
      tier fold: playback skips, settle lands — watch for the settle
      frame actually merging on device); export a temporal clip and
      diff a frame against the CPU path's export; verify the off
      toggle returns output AND timing to the pre-hybrid path
- [ ] record honestly: `device-test-pending` until then

---

## Closing note

These three templates cover every `EffectClass` in the registry:
POINTWISE (template 1), NEIGHBORHOOD (template 2), TEMPORAL
(template 3). A new effect picks its class, copies its template, adds
its registry row, and the cross-check test holds the pair together.
The templates are validated by the commits that shipped them —
0efcc75, 1d93555, c2522f5, 4d2a328, 6e3f59a, 66b02dd — and the
device-pass gap they all share is validated by 051e635.
