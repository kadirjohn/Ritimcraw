# Acceptance Gap Audit — 2026-11-16

Device-independent audit of five acceptance rows (PLAY-10, QA-02, QA-15,
QA-20, QA-23) against the şartname's exact wording, plus a staleness
verification of `docs/EFFECT_TEMPLATES.md` (which predates the
grade-parcel and shared-pipeline merges). Read-only over the code; this
file is the only addition.

**Verdict vocabulary.** COVERED = every clause has a live code path with
a citation. PARTIAL = a real gap exists in a clause (named, cited). 
MISSING = the clause has no code path at all. HOST-UNTESTABLE = the
clause depends on device behavior (real footage, real SAF, real kills)
that a worktree audit cannot execute; the code path is cited but the
behavior is device-test-pending, never counted as passed.

## Summary

| Row | Verdict | One-line reason |
|---|---|---|
| PLAY-10 | PARTIAL | Waveform, sampling, same-frame analysis and RAW/encoded scope live; the contrast-pivot reference is honestly absent (documented gap, not fabricated). |
| QA-02 | PARTIAL | Orientation composes identically end to end; the active-16:9-area crop is not implemented (full frame used, code says so). |
| QA-15 | COVERED (code) / device legs HOST-UNTESTABLE | Serial drain, finalize-only visibility, kill recovery and snapshot-preserving retry all live; retry's audio choice resolves at run, not enqueue; real kill/disk-full runs are device-pending. |
| QA-20 | PARTIAL + HOST-UNTESTABLE | Spatial NR is edge-aware and color-preserving on both paths, temporal NR has scene-cut gates on all three clauses; the measurable-detail/color clauses are device-pending. |
| QA-23 | COVERED (code) / device legs HOST-UNTESTABLE | Additive schema-1 persistence, correct-ID missing sources and fingerprint relink all live; real revoke/relink/upgrade runs are device-pending. |
| EFFECT_TEMPLATES.md | STALE (10 entries) | The doc's own "NOT merged" preamble is now false; several seam-map locations and line cites drifted. |

---

## PLAY-10 — Waveform scope

**Spec (line 138, verbatim):** "RGB ve luma waveform, orta gri ve
contrast pivot referansı, Low/Medium/High örnekleme kalitesi. Grading
sırasında aynı kaynak kare üzerinden analiz. Rotation/crop/NR açık RAW
fotoğraf ve encoded LOG üzerinde geçerli."

**Current code.**

- RGB and luma waveform: `WaveformMode` LUMA / OVERLAY / PARADE at
  `app/src/main/java/com/ritim/ritimcraw/ui/editor/WaveformScope.kt:243-247`;
  a 256-column x 256-level density scope (`COLUMNS` at :73, `LEVELS`
  at :76) with sqrt intensity encoding (:317) and position-preserving
  sampling (KDoc :40-59).
- Low/Medium/High sampling: `Sampling.LOW = 16_384`, `MEDIUM = 32_768`,
  `HIGH = 65_536` at `WaveformScope.kt:82-86`.
- Mid-gray reference: `midGraySignal()` at `WaveformScope.kt:107`
  computes the honest coded-signal of 18% gray
  (`1.099f * 0.18f.pow(0.45f) - 0.099f`, ~40.9%, bin 104); the scope
  draws it only while the derivation is honest — gated live at
  `ClipEditorScreen.kt:3699` (`grade.tonemap == 0 && grade.lutId == null`)
  and :3401, with the honesty rationale in `ScopeStrip.kt:87-97`.
- Same-source-frame analysis during grading: the scope compute effect at
  `ClipEditorScreen.kt:2728-2771` keys on the scope presentation and
  collects the SAME published frame the preview shows
  (`snapshotFlow { frame }.collectLatest`, `WaveformScope.of(f.argb, f.width,
  f.height, mode, sampling)` at :2767) — the analysis frame and the
  graded preview frame are the same frame, not a re-render.
- With rotation/crop/NR active: the scope consumes the developed,
  rotated, cropped preview frame (the same `frame` state the preview
  composes), so RAW photo and encoded LOG sources both analyze through
  their real develop chain. Rotation and crop compose through
  `RotationState.effectiveDeg` (`data/RotationState.kt:45`) and
  `CropState` on every render path (see QA-02).
- **Contrast-pivot reference: ABSENT.** The scope deliberately keeps no
  contrast-pivot line: `WaveformScope.kt:100-105` — "contrast-pivot
  'has NO counterpart here... kept absent and the gap is recorded in the
  mission report, never a fabricated fixed line'"; the same stance is
  repeated in `ScopeStrip.kt:93-96`.

**Verdict: PARTIAL.** Every clause except the contrast pivot is live
with citations. The contrast-pivot reference is missing — honestly
documented as absent rather than fabricated, but missing is missing:
the spec names it ("orta gri ve contrast pivot referansı") and the code
provides only the orta gri half.

**Fix owner hint.** The pivot reference is a scope-UI feature decision
(what the pivot means for a display-referred waveform under tonemap)
before it is a drawing task — route to the scope/preview owner.

---

## QA-02 — Padded 4:3 and vertical clips

**Spec (line 457, verbatim):** Input "Aktif 16:9 alanı olan padded 4:3
klip ve dikey klip" / Expected "Thumbnail, ilk frame, crop, preview ve
export aynı orientation ve aktif kadrajı kullanır."

**Current code.**

- Orientation — COVERED end to end. One source of truth:
  `RotationState.effectiveDeg(containerDeg, manualDeltaDeg)` at
  `data/RotationState.kt:45` (normalize(container + manual)). Every
  consumer composes through it:
  - thumbnail: `engine/ClipThumbnails.kt:298`
    (`writeIconFromMaster` rotates by the effective angle);
  - first frame / preview: the preview pipeline and the folder-browser
    first-frame thumbnails ride the same `RotationState` composition
    (per `RotationState.kt:36-44`, callers pass NOT-rotated or
    already-upright pixels explicitly);
  - MP4 export: `export/Mp4Exporter.kt:1327` (effectiveDeg) and :1328
    (rotatedDims), upright rotation at :1869
    (`RotationState.rotateRgb888(rgb, info.width, info.height, effectiveDeg)`),
    crop applied on the upright pixels at :1872-1880 (spaceDeltaDeg = 0);
  - photo export: `export/PhotoExporter.kt:221-235` (PNG pixels rotated
    by the effective angle — a PNG carries no orientation hint), crop on
    upright pixels at :243-255 (spaceDeltaDeg = 0).
  Thumbnail, first frame, preview and export therefore use the SAME
  orientation composition — the clause holds by construction.
- Active 16:9 area (aktif kadraj) — MISSING end to end. The native source
  reports no separate active rect:
  - `core/src/Source.cpp:326-330`: "QA-02: the container format has no
    separate active rect — the recorded CFA is already the crop the
    recorder wrote (e.g. 3840x2160 out of a 4096x3072 sensor;
    originalWidth/originalHeight stay in the frame JSON). // Use the
    full frame until a real padded fixture lands." —
    `info.activeArea = Rect{0, 0, width, height};`
  - `core/include/rawedit/Source.h:47`: `Rect activeArea; // full frame
    today; see Source.cpp (QA-02 pending)`.
  Every downstream consumer (thumbnail, first frame, crop, preview,
  export) renders the full frame, because that is all the source layer
  exposes. The clause "aynı ... aktif kadrajı kullanır" cannot hold for
  a genuinely padded clip — there is no active-area plumbing to be
  consistent with.

**Verdict: PARTIAL.** Orientation: COVERED with citations. Active-area
crop: MISSING (honestly marked pending in the code itself). The
end-to-end consistency the row wants is real for orientation and
impossible to test for active area without a padded fixture — the
device run is HOST-UNTESTABLE until the active rect exists.

**Fix owner hint.** The active-area rect is a native-source question
(MCRAW container metadata: does the format carry an active-rect
record?) before it is a consumer question — route to the native/core
owner; every Kotlin consumer then reads one field.

---

## QA-15 — Export queue under kill, cancel, storage-full

**Spec (line 470, verbatim):** Input "İş kuyruğuna iki export, uygulama
kill, iptal, depolama dolu" / Expected "Aynı anda bir export. Başarılı
dosya yalnız finalize sonrası görünür. Retry aynı proje snapshot'ı ve
ses seçimiyle başlar."

**Current code.**

- "Aynı anda bir export" — COVERED. The queue drains serially:
  `export/ExportQueue.kt:279-295` — `runAll` guards with
  `if (!draining.compareAndSet(false, true)) return 0` (a second kick
  while a drain runs is a no-op) and loops `nextJob() ?: break`; the
  class KDoc (:12, :39-62) states the serial-drain contract, and the
  single shared engine thread is the same one
  `PhotoExporter.kt:170-173` documents ("one motioncam decoder is not
  concurrency-safe... One job at a time").
- "Başarılı dosya yalnız finalize sonrası görünür" — COVERED. Staging on
  every exporter: `export/DngExporter.kt:205-226` (`ExportStaging`:
  partial = `out.name + ".partial"`, finalize = rename after
  `out.delete()`, discard = delete; "a successful file is only visible
  after finalize"); `Mp4Exporter.kt:2280-2292` (same staging finalize);
  `PhotoExporter.kt:256-279` (partial -> finalize or discard; KDoc
  :175-177). A killed or failed job leaves at most a `.partial`, never
  a visible success.
- Kill recovery — COVERED at code level. `ExportQueue.kt:244-260`
  `recoverInterrupted` marks stale RUNNING rows FAILED with
  `INTERRUPTED_DETAIL` (:514); it runs at app start
  (`ui/AppNav.kt:890-894`); the service is `START_NOT_STICKY` at
  `export/ExportForegroundService.kt:255` with the rationale at
  :244-256 ("a killed process's export is dead regardless (the queue
  recovers its RUNNING rows to FAILED on the next drain)") and
  `FOREGROUND_SERVICE_TYPE_DATA_SYNC` at :244.
- Cancel — COVERED. `ExportQueue.kt:186-229`: QUEUED -> CANCELLED on the
  spot; RUNNING -> request + optimistic flip; a DONE that lands before
  the cancel wins (terminal write under controlLock at :455-472).
- "Retry aynı proje snapshot'ı" — COVERED. `export/ExportJob.kt:154-161`
  `requeued()` keeps the same id AND the same grade snapshot, state ->
  QUEUED, clears detail/progress/publishedUri; `ExportStore.retry`
  (`ExportStore.kt:54-64`) re-queues via `requeued()`; retry wiring at
  `AppNav.kt:1922-1924`; the per-job output dir `File(exportDir, job.id)`
  (`AppNav.kt:363-375`) means a retried job overwrites only its own
  output. The snapshot carries the full grade including LUT choice and
  strength (`ExportStore.kt:216-219`), denoise percents (:220-225),
  stabilization omit-when-off (:226-238) and the LUT interpretation by
  value (:239-250 — "a retry (requeued() keeps the same snapshot) is
  invariant even if the library record is later removed or edited").
- "ve ses seçimiyle" (retry with the same audio choice) — PARTIAL. The
  audio link is not part of the job snapshot; it is resolved LIVE at
  run: `Mp4Exporter.kt:2236` `audioAttached` ("resolved ONCE before the
  engine runs") reads the audio store through `audioLinkFor`
  (`Mp4Exporter.kt:2187`), which `AppNav.kt:268-270` wires as
  `stores.audio.load().link(sourceId).state` — the CURRENT audio state,
  not the enqueue-time choice. A retry after the user detached or
  re-attached audio exports with the CURRENT audio state, not the one
  chosen when the job was queued. For the spec's scenario (queue two,
  kill, retry) the snapshot survives and the audio choice survives
  only if the audio link was not edited in between.

**Verdict: COVERED at code level, with one PARTIAL clause and
HOST-UNTESTABLE device legs.** Serial-only, finalize-only visibility,
kill recovery and snapshot-preserving retry are live with citations.
The retry-audio clause is a real gap (live resolution, not snapshot).
The literal scenario — two exports queued, process kill, storage-full
mid-write — is device behavior this audit cannot execute; the code
paths above are the evidence, the runs are device-test-pending.

**Fix owner hint.** Snapshotting the audio choice is a queue-model
decision (job row gains an audio-ref field with legacy-null fallback,
mirroring the timeline ref pattern) — route to the export-queue owner.

---

## QA-20 — Dark noisy footage, fast subject, scene cuts

**Spec (line 475, verbatim):** Input "Karanlık gürültülü RAW/encoded,
hızlı konu ve sahne kesmesi" / Expected "Noise düşerken ölçülebilir
ayrıntı ve renk korunur. Hareket izi, chroma cast ve kesimden kare
sızıntısı oluşmaz."

**Current code.**

- Spatial NR on BOTH paths — COVERED.
  - RAW/develop: `core/src/Develop.cpp:907-910` — chroma first, then
    luma, guarded by `!= 0`, clamped to [0,100]/100, running BEFORE
    every tone control and before the sharpening/detail stages (the
    comment at :899-906: "noise is a property of the sensor signal, and
    the sharpening/detail stages (19-20) amplify what survives").
  - Encoded: `core/src/Transfer.cpp:73-79` `encodedDenoiseStages` runs
    the SAME kernels with the same guards and clamps (detail stages at
    :81-92) — the encoded LOG path is not a second-class citizen.
  - Color preservation by construction: the chroma stage
    (`denoiseChromaImpl`, `Develop.cpp:215`) is a radius-2px 4-tap
    separable Gaussian with a bilateral range gate on luma
    (sigma_r 0.10); the luma stage (`denoiseLumaImpl`, `Develop.cpp:331`)
    is a 3x3 bilateral (sigma_r 0.05, we=exp(-0.5), wd=exp(-1)) whose
    DELTA is added to r,g,b equally — chroma/hue cannot shift because
    the luma correction is identical across channels; the chroma stage
    operates on chroma with a luma-structure gate, so edges survive.
  - Flat regions stay bit-identical (both stages guarded by `!= 0`, so a
    neutral grade allocates nothing — `Develop.cpp:906`).
- Temporal NR — COVERED as designed, MCRAW-only. The encoded path has no
  CFA and no neighbor frames: `engine/EncodedSource.kt:374-377` ("the
  encoded (Mi-Log) path has no CFA and no neighbor frames, so the bridge
  ignores the temporal fields here (DN-05..DN-09 apply only to MCRAW
  clips)"); the editor hides the temporal toggle for encoded clips
  (`ui/editor/ClipEditorScreen.kt:406`, `showTemporalNr =
  path == SourcePath.MCRAW`). For the encoded half of the input the
  spec's clause rides the spatial stages alone.
  - The merge is the frozen CPU merge fed to the GPU sessions as THE CFA
    (`temporalMergeCfa`, `core/src/Temporal.cpp:558`; routing comment
    `jni_bridge.cpp:2811` — the pair "rides the parcel like every other
    scalar and dies at temporalMergedCfa").
  - Scene-cut protection — COVERED. `Temporal.cpp:287-351`
    `globalGatesPass`: mean-luma gate (:326), MAFD absolute gate
    (:328, `if (mad > kSceneCutMafd) return false;` with
    `kSceneCutMafd = 10.0f` at :49 — the FFmpeg scdet convention),
    MAFD pair-to-pair trend gate (:330), and a 32-bin histogram TV gate
    (:350, `return tv * 0.5f <= kHistTvReject;`). A scene cut rejects
    the neighbor before any merge — "kesimden kare sızıntısı" (frame
    leakage across a cut) is gated four ways.
  - Fast subject (hareket izi) — COVERED by the block gates: the merge
    accepts only blocks with F/B-consistent motion within the noise
    model (`Temporal.cpp:680-750`: static-first zero-motion path
    :687-706, motion path with F/B consistency + hard floor gate + gain
    ratio x sigma taper :708-733, confidence floor `kMinConfidence = 0.1`
    at :43 / :738); a block that fails becomes weight 0
    (motion-storm rejection :746-750). Trailing artifacts from
    mis-merged motion are gated, not hoped away.
  - Chroma cast — COVERED by construction: the merge operates on CFA
    (Bayer) data pre-demosaic with the same luma-gated chroma discipline
    as the spatial path; the spatial chroma stage's luma range gate
    (sigma_r 0.10) prevents the chroma smoothing from bleeding across
    edges into color casts.
  - Honesty rules — COVERED. Neighbor decode failure FAILS the render,
    never a silent pristine fallback (`jni_bridge.cpp:1661-1663` —
    `if (!assembleTemporalWindow(...)) return nullptr`); temporal off =
    pristine + cache drop (:1640-1652, byte-identical to the
    pre-hybrid path).
  - Preview/export agreement — COVERED: temporal NR applies at SETTLE
    tier only in preview (`engine/PreviewTiers.kt:108-110`), export is
    NEVER tier-gated (:105-106 — "Export (nativeExportFrame, full-res
    developCpu) is not gated here and always applies temporal at full
    quality"), so the exported file gets the merge the settle preview
    showed.
- The measurable clauses — HOST-UNTESTABLE. "Ölçülebilir ayrıntı ve renk
  korunur / hareket izi, chroma cast ve kesimden kare sızıntısı
  oluşmaz" are claims about real dark noisy footage with a fast subject
  and real scene cuts. The gates above are the mechanism; the
  measurements are device-test-pending and are not counted as passed
  here.

**Verdict: PARTIAL (mechanism complete, measurements device-pending).**
Every mechanism clause has a live, cited gate on both source paths. The
perceptual/measurable outcome clauses are HOST-UNTESTABLE from a
worktree.

**Fix owner hint.** Nothing to route as a bug. The device pass needs a
dark-noisy MCRAW + encoded pair with a hard scene cut and a fast
subject — route the fixture request to the acceptance-test owner.

---

## QA-23 — Version upgrade, SAF revoke, SSD relink

**Spec (line 478, verbatim):** Input "Sürüm yükseltmesi, SAF sağlayıcısı
izin iptali, SSD relink" / Expected "Proje ve LUT/grade değerleri
taşınır. Eksik kaynak doğru ID ile kalır. Yeni izinle çalışmaya döner."

**Current code.**

- "Proje ve LUT/grade değerleri taşınır" (version upgrade) — COVERED by
  construction. One project file, schema 1, additive sections:
  `data/ProjectJson.kt:35-36` (`SECTION_ORDER` grades / sources /
  exports / luts / timeline / audio / metadata); `withSection`
  (:43-69) re-renders ONLY the section being written and preserves
  every other section parsed-and-rerendered raw; unknown sections
  (future schema) are kept raw rather than dropped (:41). Every store
  writes merge-aware (e.g. `ExportStore.kt:83-99` writes through
  `ProjectJson.withSection`). New fields are omit-when-neutral and
  legacy-tolerant in BOTH directions: the writer omits nulls/defaults
  so old files stay byte-identical (`ExportStore.renderExports`
  :106-189 — publishedUri/outputName omit-when-null, frameIndex/
  manualRotationDeg/renderBackend omit-when-default, resolution pair
  together), and the parser falls back for every field that postdates
  an old row (`ExportStore.parseSnapshot` :320-384 — neutral fallbacks
  for tone controls, RAW toggles, detail/tonemap, LUT fields, denoise,
  temporal, stabilization, managed LUT interp; `parseExports` :289-315
  — RenderBackend.fromWire maps unknown future values to CPU). A
  version upgrade that adds fields therefore neither drops old data
  nor breaks old rows.
- "Eksik kaynak doğru ID ile kalır" — COVERED. The access model is
  OK / LOST / GONE (`data/SourceRepository.kt:26`); sources are never
  deleted for losing access — `refreshStatus` (:366-383) is a cheap
  probe that maps to AVAILABLE / PERMISSION / GONE and recovers in BOTH
  directions (permission re-granted or file back -> OK, same id, same
  grade). The id is fingerprint-derived and stable
  (`MediaSource` :33-96 — displayName :47, manualRotationDeg :73,
  gyroId :83 all keyed to the id).
- "Yeni izinle çalışmaya döner" (relink) — COVERED. Relink is
  fingerprint-checked: `SourceRepository.kt:402-423` —
  `fingerprintOf(uri)` (SHA-256 of the first 1 MiB + size) vs the
  recorded fingerprint; a MATCH rebinds the uri and sets access = OK
  on the SAME id ("grade, stats, gyro attachment and exports all
  survive" — :423 context), backfilling displayName; a MISMATCH throws
  `RelinkMismatchException` and changes NOTHING. The UI walks the same
  flow for both lost states: `ui/library/LibraryScreen.kt:84-94` (KDoc:
  "a LOST row... shows 'Access lost' with a Grant access action; a
  GONE row... shows 'File missing' with a Re-link action. Both states
  recover through the SAME picker + fingerprint-checked re-link flow
  (spec IN-16)"); row-status mapping at :569-637 (LOST ->
  `library_row_access_lost` + `library_row_grant_access` at :593/:620;
  GONE -> `library_row_file_missing` + `library_row_relink` at
  :594/:629; strings confirmed at `res/values/strings.xml:21-24`);
  wired at `AppNav.kt:1686-1690`
  (`stores.sources.relink(sourceId, uri)`).

**Verdict: COVERED at code level; the literal scenario is
HOST-UNTESTABLE.** The persistence model, the correct-ID missing-source
behavior and the fingerprint relink are live with citations. An actual
version-upgrade-over-old-install, a real SAF provider revoke and a
real SSD unplug/replug are device runs this audit cannot execute.

**Fix owner hint.** Nothing to route as a bug. The device pass needs an
old-install upgrade path and a revoke/relink script — route the fixture
request to the acceptance-test owner.

---

## EFFECT_TEMPLATES.md staleness verification

The doc's preamble claims it describes a pre-refactor world. Both
refactors it calls "NOT merged" are merged, and several seam-map
locations and line citations drifted. Ten stale entries; the doc is NOT
edited by this audit — this list is the evidence.

1. **Preamble (:20-26)** — "The **grade-parcel** refactor (one packed
   parcel crossing JNI instead of per-field parameters) and the
   **shared-GpuDevelopPipeline** refactor (one draw chain shared by the
   sessions) are AHEAD of this document in the mission queue and are
   NOT merged."
   → Both ARE merged. The parcel:
   `engine/GradeParcel.kt:56-59` (slots SLOT_CHROMA_DENOISE /
   SLOT_NOISE_REDUCTION / SLOT_TEMPORAL_NR / SLOT_TEMPORAL_STRENGTH),
   unpacked at :241-242; the bridge comment at `jni_bridge.cpp:2811`.
   The shared pipeline: `app/src/main/cpp/gles/GpuDevelopPipeline.h`
   and `.cpp`; `GlesDevelopSession.cpp:7-17` — "THE DEVELOP CHAIN LIVES
   IN THE SHARED PIPELINE (gles/GpuDevelopPipeline): the session owns
   ONE pipeline instance... developPass() delegates the whole chain...
   to pipeline.draw()".
2. **Preamble (:24-25)** — "the grade crosses as explicit JNI
   parameters" → one packed parcel crosses instead
   (`GradeParcel.kt:56-59`; `jni_bridge.cpp:2811`).
3. **Preamble (:25-26)** — "each session owns its own draw chain" →
   both sessions delegate the whole chain to `pipeline.draw()`
   (`GlesDevelopSession.cpp:7-17`).
4. **Seam-map step 3 (:57-59)** — "Request struct —
   `app/src/main/cpp/gles/GlesDevelopSession.h`: `DevelopFrameRequest`
   gains the field."
   → The request struct was promoted:
   `GpuDevelopPipeline.h:79` — `DevelopFrameRequest` ("THE shared
   request shape, promoted here from GlesDevelopSession.h"), with
   chromaDenoise/noiseReduction at :112-113.
5. **§2.1 (:166)** — "The session's draw chain (GlesDevelopSession.cpp)
   runs a FIXED pass structure" → the draw chain is the pipeline's;
   the session delegates (`GlesDevelopSession.cpp:7-17`;
   pass structure in `GpuDevelopPipeline.cpp`).
6. **§2.1 (:173)** — "`open()` grew the source count NINE → FOURTEEN."
   → the fourteen sources are the shared pipeline's init() parameters,
   not a session `open()` count (`GpuDevelopPipeline.h` init
   signature; `GlesDevelopSession.cpp` is 985 lines with no such
   open() growth).
7. **§2.4 (:206-210)** — gates cited at "GlesDevelopSession.cpp:1658"
   (runChroma), ":1659" (runLuma), ":1768" (runSharp), ":1769"
   (runDetail) → the gates live in the pipeline:
   `GpuDevelopPipeline.cpp:1435` (`runChroma = chromaStrength > 0.0f &&
   w >= 2 && h >= 2`), :1436 (`runLuma = lumaStrength > 0.0f && w >= 3
   && h >= 3`), :1545 (`runSharp = req.sharpness != 0.0f && w >= 2 &&
   h >= 2`), :1546 (`runDetail = req.detail != 0.0f && w >= 2 &&
   h >= 2`). The cited line numbers do not exist in the 985-line
   `GlesDevelopSession.cpp`.
8. **§2.6 (:225-226)** — "The seam commit 4d2a328 widened every JNI
   surface: renderFrame 35→37, encoder 37→39, preview 36→38." →
   historical (true of that commit), but the CURRENT signatures ride the
   packed parcel (`GradeParcel.kt:56-59`; `jni_bridge.cpp:2811`), so a
   reader adding a knob today does not widen any JNI surface.
9. **§3.1 (:274-276)** — "`temporalMergedCfa` (jni_bridge.cpp:1634-1696)"
   and "`kTemporalRingCapacity = 4`, jni_bridge.cpp:1143" → minor
   drift: the function is at :1635-1697 and the constant at :1144.
10. **§3.2 (:296-301)** — six call-site line numbers "develop session:
    jni_bridge.cpp:3324", "preview: :4012", "encoder: :3547", "export
    frame: :2882", "nativePreviewRender step 2: :2051", "the sixth site
    at :2166" → drifted by the parcel/pipeline merges: the current sites
    are :3353 (develop session), :3945 (preview), :3534 (encoder),
    :2885 (export frame), :2052 (nativePreviewRender), :2167 (sixth).

**Verified still accurate** (checked against the live code, unchanged):
`Develop.cpp` developCpu (:710), stage 15 (:1038-1074), unsharpLumaImpl
(:133), stage 21/22 (:1225-1244); `Temporal.cpp:558`; `RenderBackend.kt`
FRAGMENT_COVERED_STAGES (:251-252) and FRAGMENT_UNCOVERED_PRE_STAGES
`emptySet()` (:327); all `GlesShaderSources.kt` pass-kind lines
(NR_CHROMA_H :1428, NR_CHROMA_V :1575, NR_LUMA :1726, SHARPNESS_H
:1894, SHARPNESS_V :1980, DETAIL_H :2065, DETAIL_V :2147) and
`uHighlights` (:444).

**Adjacent finding (report-only, code not edited by this audit).**
`engine/EffectRegistry.kt` carries the same drifted citations: rows at
:229 and :241 cite "GlesDevelopSession.cpp:1658/1659" and rows at
:418/:430 the :1768/:1769 pair (now `GpuDevelopPipeline.cpp:1435/1436/
1545/1546`); the temporal row at :471-472 cites
"jni_bridge.cpp:1634-1696" and ":3324 ... :4012" (now :1635-1697,
:3353, :3945). The registry's own doc says "the row is the checklist;
the gates are the truth" — the gates moved; the rows did not.

---

*Audit method: read-only over the worktree at commit 576b65b; every
verdict above cites lines read in this audit; device-dependent clauses
are HOST-UNTESTABLE and never counted as passed. The spec quotes are
verbatim from `STEPS/ritim-mcraw_ritiMCRAW_Android_Teknik_Sartname.md`
(lines 138, 457, 470, 475, 478).*

---

## Status update — 2026-11-23 (branch spec-remaining-2026-11-23)

- **QA-15 retry-audio clause — CLOSED at code level.** `ExportJob.audioLink`
  snapshots the source's audio link state at enqueue (MP4 jobs only,
  `AppNav` onExport); `Mp4Exporter.effectiveAudioLink` prefers the
  snapshot over the live AudioStore lookup, `requeued()` keeps it, and
  `ExportStore` persists it omit-when-null (legacy rows keep the live
  lookup). Host-tested (Mp4ExporterTest, ExportStoreTest). The literal
  kill/retry run stays device-test-pending.
- **EFFECT_TEMPLATES.md staleness — FIXED.** All 10 entries refreshed;
  the doc and the EffectRegistry rows now cite the shared pipeline, and
  churning files (GpuDevelopPipeline.cpp, jni_bridge.cpp) are cited by
  symbol instead of line number.
- **QA-17 lens metadata / capture time** (from docs/LINEAR_DNG_GAPS.md) —
  the writer side landed (`DngExtraMeta`); the JNI fill-in is pending
  with the bridge owner, so the exported files do not change yet.
- **Unchanged:** PLAY-10 contrast pivot (scope UI + product decision),
  QA-02 active area (Source.cpp + padded fixture), QA-20 measurements
  (device).
