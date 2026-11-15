# Color routing plan — the source/output contract and how it reaches the pipeline

B1, night research 2026-11-16. This document is the SECOND deliverable
of the B1 package: the integration plan for the pure model committed
alongside it (`app/src/main/java/com/ritim/ritimcraw/data/color/SourceProfile.kt`
+ `SourceProfileTest.kt`, 27 host tests). The model is implemented and
host-tested; everything in THIS document is a proposal for the
integrator — review-pending, the owner reads it.

Evidence vocabulary (same as GPU_BASELINE.md / EFFECT_TEMPLATES.md):
the current-state map below is **code-verified** (every citation was
read in this worktree at HEAD 576b65b + commit 9527948); the
integration plan is **planned**.

---

## 1. Current state (code-verified)

### 1.1 The encoded (Mi-Log MP4) CPU path — where the two gaps live

**The unconditional inverse.** `developEncoded`
(core/src/Transfer.cpp:105) decodes each pixel's YUV with the 601/709
matrix chosen by the `isBt709` flag (Transfer.cpp:143-146), then
applies the Mi-Log inverse to EVERY channel of EVERY clip
unconditionally:

```
core/src/Transfer.cpp:147-150
    // Mi-Log decode per channel.
    r = miLogToLinear(r);
    g = miLogToLinear(g);
    b = miLogToLinear(b);
```

There is no transfer field on `YuvFrame` (core/include/rawedit/Transfer.h:36-45),
no branch on the clip's actual curve — a plain BT.709/OETF or
sRGB-encoded MP4 gets the Mi-Log inverse and comes out wrong. The
develop's own contract comment (Transfer.h:47-61) describes the frame
as "one decoded Mi-Log frame"; the Mi-Log assumption is baked into the
stage, not read from the source.

**The deferred gamut conversion.** Mi-Log's native primaries are
Rec.2020 (the vendored Xiaomi DCTL, third_party/xiaomi-milog/
Mi-Log_to_ACES_AP0.dctl; Transfer.h:3-10 documents this). The
conversion Rec.2020 -> working space is explicitly DEFERRED:

```
core/include/rawedit/Transfer.h:59-61
    // ... Mi-Log clips carry Rec.2020 primaries (see the DCTL); the
    // gamut conversion to the working space lands with the ACES pass,
    // so today the RGB is treated as the working space directly.
```

So today the working space is linear Rec.709 BY ASSUMPTION: the
Rec.2020-encoded RGB is graded as if it were Rec.709. The working
space is nowhere explicit — it is an implicit property of "whatever
developEncoded leaves in the buffer."

**Grade order inside the develop** (Transfer.cpp:151-154, 177-179):
WB/temp/tint gains then exposure per pixel, then the shared
chroma-denoise / luma-denoise / sharpness / detail / tonemap / LUT
stages (the same kernels the MCRAW path runs, Transfer.cpp:62-79).

### 1.2 Where the profile facts are (and are not) read today

At open time, `EncodedAdapters.kt:102-113` reads exactly one color
fact from the container: `MediaFormat.KEY_COLOR_STANDARD`. An untagged
SDR stream defaults to BT.709; only an explicit BT.601 tag switches
the matrix. That single boolean (`isBt709`) is the ENTIRE color
contract that flows to the develop:

```
app/.../engine/EncodedAdapters.kt:107-113
    var isBt709 = true
    if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) { ... }
```

- `EncodedSource.open` returns it in `EncodedClipInfo.isBt709`
  (EncodedSource.kt:529); `renderFrame` passes it to
  `developer.develop(frame, fmt.isBt709, ...)` (EncodedSource.kt:555);
  the JNI entry `nativeEncodedDevelop` (jni_bridge.cpp:5031-5051)
  carries it into `developEncoded` (jni_bridge.cpp:5137-5151).
- The transfer, range, primaries and bit depth are NOT read. The
  develop assumes Mi-Log (1.1), limited range (YuvFrame's contract,
  Transfer.h:33-35) and 8-bit.
- The container's HDR pair and range ARE readable — MediaFormat's
  `COLOR_TRANSFER_HLG`/`COLOR_TRANSFER_ST2084` and `COLOR_RANGE_*` —
  and `MetadataFacts` already maps them honestly
  (MetadataFacts.kt:335-348: 3=sdr-video, 6=st2084, 7=hlg;
  1=full, 2=limited; null=unknown). MetadataFacts also maps
  `COLOR_STANDARD_*` (MetadataFacts.kt:326-333: 1=bt709,
  2/4=bt601, 6=bt2020). The FACTS exist; the pipeline ignores them.

**The honest symptom:** `MetadataFacts.encoded` classifies every
read-track encoded clip as LOG, because the develop applies the Mi-Log
inverse to every clip (MetadataFacts.kt:171-179,
`encodedClassifiedLog = trackRead`). The classification is a mirror of
the bug: "LOG" means "the pipeline will Mi-Log-decode it," not "the
container declared a log curve."

### 1.3 The display tail

After `developEncoded` returns linear RGB, the bridge applies the
Rec.709 OETF and packs ARGB (jni_bridge.cpp:5159-5194,
`rawedit::linearToRec709` at 5185-5187 — the curve of
Transfer.cpp:32-35). This is the display-side conversion today:
linear Rec.709 -> Rec.709 OETF -> 8-bit ARGB. No gamut step (the
working space is already "Rec.709" by assumption).

### 1.4 The preview path

`ClipEditorScreen` opens one session per editor lifetime
(ClipEditorScreen.kt:1854-1883): `PreviewSessions.open` routes by
source kind (EditorPreviewSession.kt:180-211) — MCRAW -> the native
`PreviewSession`, encoded -> `EncodedPreviewSession`
(EditorPreviewSession.kt:289+), which wraps `EncodedSource.renderFrame`
(EditorPreviewSession.kt:334-358) and therefore inherits the entire
1.1-1.3 chain. The encoded session has NO cached re-develop:
`renderCached` maps 1:1 onto `render` (EditorPreviewSession.kt:318-332)
— every render decodes. The paused tier is PreviewTiers.PAUSED_MAX_DIM
= 1280 (PreviewTiers.kt:13); the editor's requestRender at
ClipEditorScreen.kt:1845-1848 is the paused-frame entry.

### 1.5 The export path

Export is MCRAW-ONLY today. `Mp4Exporter`'s native session opens via
`NativeSource.jniPreviewOpen` and renders via
`NativeSource.jniExportFrameParcel` (Mp4Exporter.kt:196, 228) — the
CFA path; no `EncodedSource`, no `nativeEncodedDevelop` call anywhere
under export/** (grep-verified). The encoded clip's export story is
future work and out of this plan's scope; the plan's display/export
side is written so it covers both when that lands.

### 1.6 The GPU chain

The GPU develop pipeline is CFA-only: `DevelopFrameRequest` carries
`const std::vector<uint16_t>* cfa` (GpuDevelopPipeline.h:80) — there
is no encoded-frame request shape. There is NO Mi-Log curve in the
shader sources (grep-verified: no miLog/Mi-Log token in
GlesShaderSources.kt). The display-side equivalent of 1.3 is the
shader's OETF helper (GlesShaderSources.kt:631-637, the verbatim
CPU curve) applied when `uOetf == 1` (GlesShaderSources.kt:1227) —
the export shape; 0 is the linear endpoint used by the parity
harness. So the GPU chain today has the display tail but neither
encoded gap: no source-transfer decode and no gamut conversion,
because it only ever sees MCRAW CFA.

### 1.7 The precedents the plan builds on

- **GradeParcel** (engine/GradeParcel.kt): the 29-slot flat
  FloatArray JNI wire contract — fields append-only, NaN/Infinity
  refused, booleans strict 0/1. The exclusion list
  (GradeParcel.kt:33-43) documents what deliberately does NOT ride
  the parcel.
- **The LUT binding** (GradeParcel.kt:34-36): lutPath/strength/role/
  in-out gamut/transfer cross as per-JOB, session-level uploads —
  NOT parcel slots — because they are per-LUT, not per-grade-scalar.
  The five interpretation ordinals ride the render call
  (PreviewSession.kt:179-185) and the GPU request
  (GpuDevelopPipeline.h:140-146).
- **LutInterpK travels BY VALUE in GradeK** (GradeStore.kt:190, and
  the renderer's omit-when-null + BY-VALUE comment at
  GradeStore.kt:425-435): an export snapshot is self-sufficient
  because the interpretation is inside the grade, not a session
  lookup.
- **The managed binding compiles against Rec709**
  (core/src/Color.cpp:570-584: `compileLutBinding` builds
  workingToInput/outputToWorking with `GamutId::Rec709` hard-coded)
  — the working-space assumption again, this time inside the LUT
  machinery. A working-space switch must update this too.
- **The gamut math exists**: `gamutToGamut` (Color.cpp:549-566,
  Bradford CAT at 534-547, identity when from == to), the Rec.2020
  npm derivation (Color.cpp:214-216 primaries, 259-279 npm, 281-295
  gamutToXyzMat). The model's reference vectors mirror these exactly
  (SourceProfile.kt:432-471).

---

## 2. The model (committed, host-tested — the contract this plan wires)

`app/src/main/java/com/ritim/ritimcraw/data/color/SourceProfile.kt`:

- **The enums**: `SourcePrimaries { BT601, BT709, BT2020 }` (the
  container-declared set — MediaFormat's COLOR_STANDARD_* vocabulary,
  NOT LutGamut's camera list); `SourceTransfer { Linear, Srgb,
  Rec709OETF, Hlg, Pq, MiLog }` (LutTransfer's precedent plus the
  container HDR pair plus Mi-Log); `SourceMatrix { BT601, BT709 }`;
  `SourceRange { Limited, Full }`; `ProfileProvenance { Container,
  Probed, UserOverride }`; `WorkingSpace { LinearRec709 }` — the
  working space made EXPLICIT, so a future wide-gamut switch is one
  enum value, not a rewrite.
- **The honesty rule**: `SourceProfile` carries per-field provenance;
  null = UNKNOWN, never a silent default (MetadataFacts' Field
  model). `assumedEncoded()` states today's path as a profile:
  BT709/MiLog/BT709/Limited/8-bit with EVERY provenance null —
  the migration baseline.
- **The plan type** (DATA ONLY): `ColorPlan(source, workingSpace,
  decodeSteps, displaySteps)` over `sealed class PlanStep` —
  `YcbcrToRgb(matrix, range, bitDepth)`, `DecodeTransfer(transfer)`
  (ABSENCE = NONE — the unconditional-inverse fix shape),
  `GamutToWorking(from, to)` (absent when identity),
  `WorkingToDisplay(displayPrimaries, displayTransfer)`. The grade
  sits between the two lists by definition.
- **The decision function**:
  `fun planFor(source: SourceProfile, working: WorkingSpace =
  WorkingSpace.LinearRec709): ColorPlan` — the single place the ORDER
  lives.
- **The reference vectors** (`SourceProfileVectors`): Mi-Log constants
  + curve mirroring Transfer.cpp:15-30; the Rec.709 OETF
  (Transfer.cpp:32-35) and its inverse (Color.cpp:430-436 with the
  sign-preserving spow of Color.cpp:83-85, cut 0.081 =
  Color.cpp:103-106); 601/709 YCbCr both ranges (Transfer.cpp:39-58);
  REC2020_TO_XYZ / REC2020_TO_REC709 / REC709_TO_REC2020 mirroring
  Color.cpp:214-216 + 239-245 + 259-279 + 534-566 — including the
  honest quirk that core's pinned 4-decimal `kM709ToXyz`
  (Color.cpp:185-189) maps D65 to 0.9505, so the vectors mirror
  core's behavior, not the textbook's.

27 tests pin: the identity plan, the Mi-Log plan shape (Ycbcr ->
DecodeTransfer(MiLog) -> GamutToWorking(BT2020, BT709)), the
non-Mi-Log shape (NO DecodeTransfer for Linear; the named inverse for
Srgb/Rec709OETF/Hlg/Pq — never a silent Mi-Log), matrix/range/bit-depth
selection, the rejection rules (non-positive bit depth throws;
unpinned depths throw), the provenance honesty of assumedEncoded(),
and the reference points.

---

## 3. Integration plan (PROPOSALS — the integrator decides)

### 3.1 Where the SourceProfile is DERIVED

**Proposal: at open time, per clip, inside the existing adapter read —
extending EncodedAdapters.kt:102-113, not adding a new pass.**

The container tags are already in hand at exactly that point
(MediaFormat from the extractor); the profile is a per-CLIP constant
like `isBt709` and orientation — it does not change per frame. The
mapping is direct and honest:

| Container key | Profile field | Provenance |
|---|---|---|
| KEY_COLOR_STANDARD (1/2/4/6) | primaries + matrix | Container |
| KEY_COLOR_TRANSFER (3/6/7) | transfer (sdr-video -> Rec709OETF, st2084 -> Pq, hlg -> Hlg) | Container |
| KEY_COLOR_RANGE (1/2) | range | Container |
| (absent) | the field stays with null provenance | UNKNOWN |

Two open questions the integrator settles:

- **The untagged-SDR default.** Today the matrix defaults to BT.709
  (the H.264/HEVC convention, EncodedAdapters.kt:104-106 — keep
  that), but the TRANSFER must NOT default to Mi-Log. The honest
  reading of an untagged SDR stream is Rec709OETF with provenance
  UNKNOWN (the container said nothing; the app's current behavior of
  Mi-Log-decoding it is the bug). The one exception: Xiaomi Mi-Log
  clips — how they actually tag their containers is a
  device-verification question (the Mi-Log whitepaper/DCTL describe
  the curve, not the container tagging). Until verified, a
  `ProfileProvenance.Probed`-style heuristic (mime/brand sniffing)
  or a per-clip UserOverride is the migration escape hatch.
- **MCRAW sources.** The MCRAW path has its own contract (the CFA +
  RawMeta the develop already consumes); it derives a profile the same
  way — at open, from the container — but with a different reader.
  The model covers it (Linear transfer, the demosaic's output
  primaries); the reader is integrator work.

Tradeoffs considered: deriving at IMPORT time would persist the
profile into project.json, but then a re-imported clip whose tags
changed would silently keep stale facts, and the honesty model wants
the container re-read. Per-FRAME derivation is pure waste — the tags
are stream-level. Open time is where the existing code already reads
them.

### 3.2 How the profile flows to the CPU develop

**Proposal: NOT the parcel.** The profile is per-CLIP, not a grade
scalar — the exact category GradeParcel's exclusion list
(GradeParcel.kt:33-43) already names for the LUT binding ("per-JOB,
session-level upload, not a per-frame scalar"). Three mechanisms,
with tradeoffs:

1. **Widen the `isBt709: Boolean` JNI parameter into the profile's
   ordinals** (a `SourceProfileK`-shaped parameter set on
   `nativeEncodedDevelop`, mirroring how the five LUT-binding
   ordinals ride the same call at jni_bridge.cpp:5049-5050).
   - For: the smallest seam change; the encoded call site is ONE
     function; the ordinals are validated ints, the LUT precedent.
   - Against: the parameter list is already enormous
     (jni_bridge.cpp:5031-5050); appending five more ints deepens a
     wart. But the parcel refactor (EFFECT_TEMPLATES.md's shape
     note) will collapse this anyway — profile slots can ride THAT.
2. **Session-level upload at open** (the profile set once on the
   native side when the clip opens, before the first render).
   - For: zero per-frame cost; the profile IS a session constant.
   - Against: a new native entry point and a hidden per-session
     state the render call can't see — the codebase's discipline is
     that the render call carries everything the render needs
     (the GradeParcel rationale).
3. **Parcel slots** (append five ordinals to the 29).
   - Against: violates the parcel's own exclusion list — the parcel
     is the GRADE, and the same grade applied to two clips must not
     carry two profiles. REJECTED by the existing contract.

**Recommendation: (1) now, folded into the parcel when that refactor
lands — the profile rides the JOB (the render call), never the
grade.** The distinction that matters: GradeK (the grade, persisted,
per-clip-editable) must stay profile-FREE, because a preset/undo
entry that baked in a source profile would lie when applied to a
different clip.

### 3.3 How the plan reaches the stages (the integrator's ONE decision)

The research names the stage order as the integrator's single
decision. The model already fixes the SHAPE —
`planFor`'s output is the ordered contract:

```
YcbcrToRgb -> [DecodeTransfer] -> [GamutToWorking] -> (grade) -> display
```

What the integrator decides is WHERE that decode side sits relative
to the existing develop stages. The two candidate orders:

- **(A) Decode side INSIDE developEncoded, before the WB/exposure
  loop** (where the Mi-Log inverse sits today, Transfer.cpp:147-150):
  the plan's steps replace the unconditional three lines; the grade
  loop and everything after it is untouched. The gamut step slots in
  immediately after the transfer inverse (linear Rec.2020 ->
  linear Rec.709 before any gains touch the channels — gains are
  channel scalars, order-safe, but the tonemap/LUT stages assume
  the working space, so the gamut step MUST precede
  encodedDenoiseStages at Transfer.cpp:177).
- **(B) A separate decode pass before the develop** (YUV -> RGB ->
  linear working space as its own stage, the develop receiving
  ready-made linear RGB): cleaner layering, but it changes
  developEncoded's contract for every caller at once and moves the
  per-pixel work out of the fused loop (a second full-frame pass).

**The doc proposes (A)** — it is the minimal-diff shape, it keeps
the fused per-pixel loop, and the plan's data-only steps map 1:1
onto branch points inside the loop. But the tradeoff is real: (A)
leaves the transfer/gamut decisions inside core's developEncoded
(more parameters down the seam), (B) puts them in Kotlin (the
develop becomes honest about receiving linear RGB). If the ACES
pass (the wide-gamut working space) lands soon, (B) is the better
foundation; if the working space stays linear Rec.709 for now, (A)
ships faster. THE INTEGRATOR DECIDES; the model supports both (the
plan type is the same, only the consumer differs).

### 3.4 The GPU chain

No encoded frames reach the GPU today (1.6), so the GPU plan is:
when an encoded GPU path ever exists, the SAME `planFor` output is
the shader contract — DecodeTransfer becomes a shader function gate
(the uOetf precedent: a uniform int selecting the curve),
GamutToWorking becomes a mat3 uniform (the uCam2rgb idiom,
GpuDevelopPipeline.h:152-153). Nothing to do now; the plan doc's
job is to say the model already covers it.

### 3.5 The migration rule (binding)

**"Mevcut lineer Rec.709 çalışma alanını migration ile korumak" —
existing projects keep the current look.** Concretely:

- A clip whose profile equals `assumedEncoded()` (the null-provenance
  baseline) renders EXACTLY as today: Mi-Log inverse applied,
  Rec.2020 treated as Rec.709 (the deferred conversion stays
  deferred). The plan this profile produces must be recognizable as
  "the current behavior" — `planFor(assumedEncoded())` says
  DecodeTransfer(MiLog) + GamutToWorking(BT2020, BT709), and the
  migration gate is that the integrator's stage wiring SKIPS the
  gamut step for the baseline until the ACES pass lands (the plan
  carries the step; the wiring honors the deferral — the model's
  KDoc says this explicitly, SourceProfile.kt:250-255).
- A clip with REAL container tags (the honest profile) gets the
  correct plan — and looks DIFFERENT from today if today was wrong.
  That difference is the bug fix, not a regression: the acceptance
  test is the plan shape, not pixel equality with the old output.
- Grades, presets, undo entries never change: they carry no profile
  (3.2's decision), so every saved grade/preset/undo applies
  identically before and after.

### 3.6 The paused-frame invalidation rule

A profile change (a re-read finding different tags, or a
UserOverride) must invalidate every cached render of that clip:

- The MCRAW preview cache keys on the frame + grade
  (PreviewSession.renderCached, PreviewSession.kt:190-197 — a
  grade-only change skips the decode). The profile is NOT in that
  key today because no profile exists; when it lands, **the
  profile's identity must join every cache key** the way the grade
  already does. The encoded session is simpler: it has NO cache
  (EditorPreviewSession.kt:318-332 — every render decodes), so an
  encoded profile change invalidates nothing; the next render just
  uses the new plan.
- **Presets/undo**: carry the GRADE only (GradeHistory.kt:93-97
  entries are GradeK snapshots; PresetStore's GradePreset wraps
  GradeK). No profile rides them (3.2) — so nothing to invalidate;
  the rule is a test that asserts a preset applied before and
  after a profile change produces the SAME grade object.
- **Export snapshots**: `ExportJob.snapshot` is a GradeK
  (ExportJob.kt:52-55) resolved fresh per run for the world facts
  (ExportJob.kt:111-117). The profile belongs with the "world
  facts" — resolved at run time from the clip, NOT frozen at
  enqueue — because the profile is a FACT about the source, not
  user intent. An in-flight export whose clip's profile changes
  mid-run is the same class of question as the live-grade
  semantics there; the doc proposes resolving the profile ONCE at
  export start (the job's own open), so one export is internally
  consistent even if the tags change mid-run.

### 3.7 What the integrator must decide (the summary)

1. **The stage order** (3.3): decode side inside developEncoded (A)
   or a separate decode pass (B). THE research's single named
   decision — everything else follows from it.
2. The untagged-SDR transfer default and the Mi-Log detection
   heuristic (3.1) — needs device verification of how Xiaomi tags.
3. Whether the profile ordinals ride the render call now and fold
   into the parcel later (3.2's recommendation), or wait for the
   parcel refactor entirely.

---

## 4. Acceptance tests (the research's list)

All host-testable against the pure model + the integrator's wiring;
the model's 27 tests already cover the first three groups.

1. **Source-profile identity/transfer references**: the Mi-Log,
   OETF and OETF-inverse reference points
   (SourceProfileTest's pinned values, mirroring Transfer.cpp:24-35
   and Color.cpp:430-436). COVERED (host-tested).
2. **Range/matrix selection**: every matrix/range/bitDepth cell the
   container can declare produces the right YcbcrToRgb step and
   the right expansion math. COVERED (host-tested).
3. **Rec.2020 -> Rec.709 vectors**: the three pinned matrices vs
   the npm/CAT derivation to 1e-6. COVERED (host-tested).
4. **LUT/tonemap applied exactly once**: once the wiring lands, a
   plan-driven render with a LUT selected must apply the LUT and
   tonemap exactly once each — the plan's steps must not compose
   with the existing stage-22/23/24 calls into a double
   application. This is an INTEGRATION test (needs the wired
   develop; the model cannot test it alone). PLANNED.
5. **Paused-frame invalidation**: a profile change on a cached
   session re-renders (the profile joins the cache key); a preset/
   undo entry is profile-free (applying it before and after a
   profile change yields the identical grade). PLANNED (needs the
   cache-key wiring).

Device-pending beyond these: the Xiaomi container-tag verification
(3.1) and the visual pass on a real Mi-Log clip vs a plain H.264
clip — the two inputs the bug actually confuses.

---

## 5. Status

- The pure model (SourceProfile.kt + 27 tests): **implemented +
  host-tested** (BUILD SUCCESSFUL, suite 2149 tests 0 failures at
  commit 9527948).
- This plan document: **planned / review-pending** — the owner reads
  it; the integrator owns every decision in §3.7.
- Nothing in the app imports the model yet; no runtime behavior
  changed. The two gaps of §1.1 are CITED, not fixed — fixing them
  is the integrator's work against this contract.
