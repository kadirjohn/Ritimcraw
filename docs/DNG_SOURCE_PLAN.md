# DNG source plan — the decoder decision and the single-file metadata contract

B3, night research 2026-11-16. This document is the FIRST deliverable
of the DNG source package: the decoder decision (LibRaw vs a
hand-rolled minimal reader vs defer) plus the supported-subset
definition and the adapter contract. The pure single-DNG metadata
model is committed alongside it
(`app/src/main/java/com/ritim/ritimcraw/engine/dng/DngFileModel.kt`
+ `DngFileModelTest.kt`, 54 host tests). The model is implemented
and host-tested; THE DECISION IN §2 IS RESEARCH'S RECOMMENDATION —
review-pending, the owner reads it; the integrator executes. Real
pixel decode is a LATER WAVE (this package reads metadata only).

Evidence vocabulary (same as COLOR_ROUTING_PLAN.md): the
current-state map in §1 is **code-verified** (every citation read in
this worktree at HEAD a6910d3); §2's LibRaw facts are **reported**
(the dependency table's own words, the readiness report's findings —
the NDK build is UNTRIED); the integration plan §3 is **planned**.

---

## 1. Current state (code-verified)

### 1.1 What the app already reads and writes

The app has NO DNG reader today. What exists:

- **The writer** (`core/src/DngWriter.cpp`, 607 lines): the app's own
  DNG generator for export (EX-14 / RAW-13). It emits a 21-entry IFD
  with a fixed single-strip layout, both photometric variants
  (CFA 32803 and LinearRaw 34892), uncompressed 16-bit pixels, and
  the exact tag set tabulated in §2.3.
- **The sequence model** (`app/.../engine/DngSequence.kt`, B4's
  deliverable, merged at a6910d3): the name rule, gap/duplicate
  policy, pts grid and WAV binding for a CinemaDNG FOLDER — pure
  Kotlin, host-tested, imported by nothing yet. It knows nothing
  about the bytes INSIDE a .dng file; this package is the other half.
- **The develop contract** (`core/include/rawedit/Source.h:41-100`):
  `FrameInfo` (width/height/bitDepth/cfa/activeArea/orientationDeg)
  and `RawMeta` (black[4]/white/wbGains[3]/colorMatrix[9]/
  asShotNeutral[3] + forward/calibration/lensShading/focalLengthMm).
  A decoded DNG must become EXACTLY this input — §3.2's mapping.
- **The source seam** (`app/.../data/SourceRepository.kt:7`):
  `enum class SourceKind { MCRAW, ENCODED }` — the catalog's clip
  kinds. A RAW photo and a DNG sequence are neither of these; §3.4
  proposes the two new kinds.
- **IN-17's pre-open check**: "DNG/MCRAW'ı açmadan önce overflow,
  offset ve frame count tutarlılığı kontrolü" — the strip-layout
  consistency rule the model implements (stripByteCount must exactly
  fit the frame) before a file is opened as a source.

### 1.2 Why the decision is open at all

IN-08 demands DNG **plus** CR2/CR3, RAF, NEF/NRW, ARW — the camera
RAW formats — via "pinlenen LibRaw sürümü" (the pinned LibRaw
version). But the same spec's IN-10 (RAW photo from Android
share/open-with) and IN-09 (DNG sequence as video source) need only
DNG, and the app's OWN writer already defines the DNG dialect the
reader will most often meet (the app's own exports). The question is
whether the first wave should carry LibRaw's whole camera zoo or a
minimal honest DNG reader. That is §2.

### 1.3 The precedents the plan builds on

- **The injected-boundary seam** (`app/.../engine/EncodedAdapters.kt`):
  `internal class MediaExtractorTrackReader : EncodedTrackReader`
  wraps the platform decoder behind an interface so JVM tests run
  against fakes. The DNG reader follows the same shape: the
  byte-level IO behind an interface, the pure model in front.
- **The vendoring convention** (`third_party/MANIFEST.md`):
  `third_party/<name>` + a MANIFEST row (path / version+commit /
  sha256 / license / source URL), built as a STATIC lib with
  `POSITION_INDEPENDENT_CODE ON` via `add_subdirectory` from
  `app/src/main/cpp/CMakeLists.txt` (the motioncam-decoder
  b931ec0e precedent, NDK 30.0.16248370 / SDK CMake 4.1.2).
- **The error shape** (`DngSequenceException` / `PreviewErrors`):
  stage + cause + recovery, never a bare "failed". The model's
  `DngFileException` mirrors it (stages: header / tags / pixels).

---

## 2. The decoder decision (RESEARCH'S RECOMMENDATION — review-pending)

### 2.1 The three options

| | A. LibRaw | B. hand-rolled minimal reader | C. defer |
|---|---|---|---|
| **What it is** | Vendor LibRaw (LGPL-2.1 OR CDDL-1.0 dual license) into `third_party/libraw` via the upstream-pointed user-contribution CMake wrapper (LibRaw/LibRaw-cmake), pinned to a release tag. | Read the DNG subset of §2.3 in Kotlin/C++ ourselves: the TIFF/IFD walk, the tag set, uncompressed 16-bit (and 10/12/14) pixel copy. `DngFileModel.kt` IS this option's metadata half, already committed and host-tested. | Ship neither; keep the model as the contract and revisit when a real camera RAW actually arrives. |
| **License** | Dual LGPL-2.1 / CDDL-1.0. For an Android app STATIC-LINKED into the APK, LGPL §4/§3's relink obligation is the honest reading: either ship the .so with sources + a build script that lets the user relink, or keep the whole app LGPL-able. CDDL's file-level copyleft is similar in effect. NOT a blocker, but a real obligation to state in the store listing and to honor in the MANIFEST row. | None — our own code. | None. |
| **Build cost** | Real: a new third_party entry, a CMake wrapper we do not own (pin its commit too), an NDK arm64 build that is UNTRIED per the readiness report, plus the wrapper's own quirks. Est. days of build work BEFORE a single pixel decodes, and every LibRaw upgrade re-risks the build. | Small: the metadata math is ALREADY done (this package). The pixel leg is a bounded copy loop (uncompressed strips) plus a JNI surface. Est. a day of C++ for the single-file reader. | Zero now; the cost moves to the day a camera RAW arrives, with the same decision to make then — with better information. |
| **Subset coverage** | Everything IN-08 names: CR2/CR3, RAF, NEF/NRW, ARW, DNG, plus lens shading, opcodes, compressed variants, mosaiced and linear. | DNG-only, and only the §2.3 subset: uncompressed single-strip CFA/LinearRaw, 8-16 bit. CR2/CR3 etc. are OUT until a later wave adds them (honest rejection, named cause). | Same as B's coverage minus the pixel leg. |
| **Maintenance** | Upstream LibRaw moves (camera support, CVEs); every pin is a re-verification of the camera list ("Kamera listesi pinlenen sürüme ve dosya moduna bağlı"). The RCD demosaic is GPL-3.0 — the main tree does not carry it, but any pull of a fork that does is a license contamination risk to check at pin time. | Ours alone: the DNG spec's tag semantics are stable (DNG 1.4/1.6 tag numbers do not move), and the subset is small enough to hold in one file. The writer is the reader's round-trip test — we own both ends. | No maintenance; but IN-09's sequence import and IN-10's photo import stay blocked on it. |
| **Risk** | The NDK build is the named unknown (the readiness report's own flag). A wrapper we do not own + a build we have not tried + a camera list we cannot verify without devices. | The subset is honest but narrow: a camera DNG with JPEG-compressed strips or multi-strip layout is REJECTED, not misread (the model's stage/cause/recovery says exactly that). The spec's warning applies: "Uzantı kadar kamera modeli, kayıt modu ve gerçek decode sonucu önemlidir" — real-device verification stays mandatory either way. | IN-08's camera half stays open; IN-09/IN-10's DNG half stays blocked. |

### 2.2 The recommendation

**B for the first wave (the hand-rolled minimal reader over the
committed model), with A (LibRaw) as the planned SECOND wave for the
camera formats.** Reasons:

1. **The dependency table's own scope split supports it.** IN-08 is a
   2.4.0-era row (B priority) naming LibRaw for the CAMERA formats;
   IN-10 (RAW photo import, A priority, 3.0.1) and IN-09 (DNG
   sequence, B) need only DNG. The A-priority path does not need
   LibRaw — building LibRaw first puts the B-priority dependency in
   front of the A-priority feature.
2. **The metadata half of B is already done and host-tested** — 54
   tests over the tag set, the validation rules, both endians, the
   offset arithmetic. The remaining work is the bounded pixel leg.
3. **The app's own writer is the reader's round-trip oracle.** The
   export path emits the exact dialect the reader must accept
   (DngWriter.cpp's 21-entry IFD, both photometrics); LinearDngContract
   tests already pin the writer's contract. Vendor LibRaw and this
   oracle is still ours but the decode path is theirs — one more
   moving part between the writer's bytes and the develop's input.
4. **LibRaw's honest costs are front-loaded and unverified**: the NDK
   build is untried, the wrapper is a user contribution, and the
   license obligation (LGPL static link) is real. None of these block
   B; all of them precede A's first pixel.
5. **The rejection rules make B safe to ship**: an out-of-subset DNG
   (compressed, multi-strip, exotic CFA) is refused with a named
   cause and recovery — never misread. The subset can widen wave by
   wave (JPEG-lossless next) without touching the contract.

The decision the owner is asked to ratify: **build the first-wave
reader on the committed model (B); schedule LibRaw (A) as its own
wave when the camera formats (CR2/CR3/RAF/NEF/ARW) enter scope, with
the NDK build spike and the license review as that wave's first
tasks.** Deferring entirely (C) is not recommended: it blocks IN-09
and IN-10, both of which only need what B provides.

### 2.3 The supported subset (the minimum honest set)

What the first-wave reader reads — exactly the model's `KNOWN_TAGS`
(23 tags), the union of the app's own writer's tag set and the tags
the develop's contract consumes:

| Tag | # | Type | Required | Notes |
|---|---|---|---|---|
| NewSubfileType | 254 | LONG | no | read, not interpreted (full-resolution image) |
| ImageWidth | 256 | LONG | **yes** | FrameInfo.width |
| ImageLength | 257 | LONG | **yes** | FrameInfo.height |
| BitsPerSample | 258 | SHORT | **yes** | 8/10/12/14/16; FrameInfo.bitDepth |
| Compression | 259 | SHORT | **yes** | 1 (none) accepted; 7 (JPEG-lossless) declared, later wave |
| PhotometricInterpretation | 262 | SHORT | **yes** | 32803 CFA / 34892 LinearRaw |
| ImageDescription | 270 | ASCII | no | the Linear DNG stage record (RAW-13) |
| StripOffsets | 273 | LONG | **yes** | the pixel data's absolute offset |
| Orientation | 274 | SHORT | no | 1/6/3/8 (rotations); mirrors 2/4/5/7 REJECTED |
| SamplesPerPixel | 277 | SHORT | **yes** | 1 for CFA, 3 for LinearRaw |
| RowsPerStrip | 278 | LONG | **yes** | must equal height (single strip) |
| StripByteCounts | 279 | LONG | **yes** | IN-17: must exactly fit the frame |
| CFARepeatPatternDim | 33421 | SHORT[2] | CFA only | must be 2x2 |
| CFAPattern | 33422 | BYTE[4] | CFA only | 0/1/2 codes; the four Bayer arrangements |
| DNGVersion | 50706 | BYTE[4] | **yes** | the DNG marker (rejects plain TIFF) |
| CFAPlaneColor | 50710 | BYTE[3/4] | no | read; RGB assumed (the writer's own values) |
| CFALayout | 50711 | SHORT | no | read; type 1 (rectangular) assumed |
| BlackLevelRepeatDim | 50713 | SHORT[2] | no | the singularity rule's cell dims |
| BlackLevel | 50714 | SRATIONAL | no | 1 value or repeatDim values; RawMeta.black[4] |
| WhiteLevel | 50717 | LONG | no | RawMeta.white; default the bit ceiling |
| ColorMatrix1 | 50721 | SRATIONAL[9] | no | XYZ -> camera RGB; RawMeta.colorMatrix |
| AsShotNeutral | 50728 | RATIONAL[3] | no | r-g-b; wbGains is its RECIPROCAL |
| ActiveArea | 50829 | LONG[4] | no | top/left/bottom/right; FrameInfo.activeArea |

Pixel variants: **uncompressed (1) only**, single strip, 8/10/12/14/16
bit, CFA or LinearRaw photometric. Everything else — compressed
variants, multi-strip, exotic CFA cells, Fuji Super CC — is an honest
rejection with a named cause (the model's stage/cause/recovery), not
a misread.

### 2.4 What the model already pins (the contract's code form)

`DngFileModel.kt` (committed, host-tested) holds: the tag constants
and the required/known sets; the pure TIFF/IFD math (header, IFD
entry offsets, inline-vs-external value offsets, both endians,
rational reads with the zero-denominator NaN rule); the validation
rules (CFA 2x2 + four arrangements, orientation rotations-only,
active-area-inside-frame, white/black level rules, WB gains as
asShotNeutral reciprocals, matrix finiteness, IN-17 strip
consistency); and `DngFileMetadata` — the validated record with the
absent-tag defaults (black 0s, white the bit ceiling, active area
the full frame; unknown, never a guess).

---

## 3. Integration plan (PROPOSALS — the integrator decides)

### 3.1 Where the reader sits

The reader is an `EncodedSource`-shaped seam, not a core change:

```
.dng bytes -> DngFileReader (interface; real impl = byte-level
              TIFF walk using DngFile's pure math + the pixel copy)
           -> DngFileMetadata (this package's validated record)
           -> the §3.2 adapter -> RawMeta/FrameInfo -> develop
```

The pure model stays JVM-pure (no Android imports, no IO) — the same
injected-boundary shape as `EncodedTrackReader` (EncodedAdapters.kt):
the interface carries the device/IO leg, fakes carry the tests. The
byte-level reader itself may be Kotlin (java.nio over the file) or
C++ behind JNI; the READER's choice is the integrator's — the model
works with either because the model only does the math.

### 3.2 The adapter contract (DngFileMetadata -> RawMeta/FrameInfo)

The mapping the adapter fills — every field named from
`core/include/rawedit/Source.h` and the model:

| DngFileMetadata | RawMeta / FrameInfo field | Notes |
|---|---|---|
| width, height | FrameInfo.width, .height | CFA dims (not the active area's) |
| bitDepth | FrameInfo.bitDepth | 16 from the writer; 10-16 from cameras |
| cfa?.coreOrdinal | FrameInfo.cfa | the rawedit::CfaPattern ordinal (0-3); NativeSource.kt's FrameInfoK comment is the vocabulary |
| orientation.degreesCw | FrameInfo.orientationDeg | 0/90/180/270 only (mirrors rejected upstream) |
| effectiveActiveArea | FrameInfo.activeArea | Rect(x=left, y=top, w=width, h=height) |
| effectiveBlackLevels | RawMeta.black[4] | cellSlot order (cellY*2+cellX) — Develop.cpp's black[] indexing; the model's expansion already matches |
| effectiveWhiteLevel | RawMeta.white | single value (the DNG spec's rule) |
| wbGains | RawMeta.wbGains[3] | the model computes 1/asShotNeutral — Source.h: "wbGains is its reciprocal" |
| asShotNeutral | RawMeta.asShotNeutral[3] | r-g-b, verbatim |
| colorMatrix | RawMeta.colorMatrix[9] | ColorMatrix1, XYZ -> camera RGB, row-major; "do NOT multiply it onto camera RGB" (Source.h) |
| — | RawMeta.forwardMatrix / calibration | identity defaults (the first-wave subset reads no ForwardMatrix1/CalibrationMatrix1 — absent, never guessed) |
| — | RawMeta.lensShading / focalLengthMm | empty / 0 (absent — the RawMeta convention) |
| — | FrameInfo.dynamicBlack/dynamicWhite | -1 sentinels (the develop falls back to RawMeta) |

The Linear DNG special case: `isRitiLinearDng` (ImageDescription names
developLinear) means the pixels are ALREADY normalized, demosaiced
and white-balanced (kLinearDngStages) — the adapter must route such a
file to a path that does NOT re-apply those stages (the develop's
developLinear branch), or the file is double-processed. The
export-side contract test (LinearDngContractTest) pins the writer's
half; the reader's half is the §4 acceptance item.

### 3.3 The pixel leg (the later wave's bounded work)

Uncompressed single-strip pixels are a bounded copy: read
`stripByteCount` bytes at `stripOffset`, widen 8/10/12/14-bit
samples to 16 (the develop's native width), hand the buffer to the
develop with the §3.2 metadata. IN-17's checks (the model's
stripLayoutOf) run BEFORE the copy. The compressed variants
(JPEG-lossless 7) need a decoder — that is the natural first
extension, and the point where LibRaw's §2 tradeoffs re-enter.

### 3.4 The SourceKind proposal

`SourceKind` (SourceRepository.kt:7) today: `{ MCRAW, ENCODED }`.
Research proposes **two DISTINCT new kinds**, not one:

- **`RAW_PHOTO`** (IN-10): a single .dng photo from share/open-with —
  one clip, one frame, no timeline, EXIF-adjacent metadata surfaced
  in the editor's info panel.
- **`DNG_SEQUENCE`** (IN-09): a CinemaDNG folder — N single-DNG
  records (this model) + B4's DngSequence ordering/timeline/WAV
  facts, grouped as ONE video source.

Why distinct: the catalog row, the library UI, the share intent
filter and the export path all behave differently (a photo has no
fps, no WAV, no gap policy; a sequence has all three). Folding them
into one kind forces every consumer to branch on a "isSequence"
flag — the exact ambiguity the enum exists to prevent. The seam is
the integrator's to cut; this is the proposal.

### 3.5 The B4 wiring point

DngSequence.kt's own KDoc (the fps grid's rejection rules): "The
rate's PROVENANCE is the caller's to resolve (manifest, B3's
container-detected tag, or a user choice)". The container-detected
tag path is THIS package's to own, and the first wave DOES NOT add
it: the DNG frame-rate tags (CinemaDNG's FrameRate/TimeCodes) are
OUTSIDE the §2.3 subset, so the first wave's provenance resolution
for a sequence's fps is: the manifest (EX-14's time manifest, B4's
own path), else the user choice — the container-detected tag joins
when a later wave widens the subset to carry it. The wiring point:
the integrator's sequence-import pass feeds DngSequence.timelineOf
the resolved rate; B3's model contributes the per-file records only.

### 3.6 What the integrator must decide (the summary)

1. **The SourceKind seam** (§3.4): ratify RAW_PHOTO and
   DNG_SEQUENCE as distinct kinds, or override.
2. **The decoder's build integration** (§2/§3.1/§3.3): ratify B
   (hand-rolled first wave, LibRaw as the camera-format wave), and
   choose the reader's language (Kotlin nio vs C++ JNI) — the model
   supports either.
3. **The B4 wiring point** (§3.5): where the sequence-import pass
   resolves the fps provenance (manifest / container tag / user) and
   calls DngSequence.timelineOf.
4. The Linear DNG routing (§3.2): the develop's developLinear branch
   as the destination for isRitiLinearDng files.

---

## 4. Acceptance tests (the research's list)

The metadata model's 54 host tests (DngFileModelTest) already cover
groups 1-4. The pixel and device legs are the later waves'.

1. **Tag/pixel contract vs the app's own writer**: a DNG the writer
   emits round-trips through the reader into the same
   DngFileMetadata the writer was handed (the 21-entry IFD, both
   photometrics, both endians). Metadata half COVERED
   (host-tested); the pixel half is the later wave. PLANNED.
2. **WB/matrix mapping**: asShotNeutral -> wbGains reciprocals,
   ColorMatrix1 -> colorMatrix verbatim (XYZ -> camera direction,
   never multiplied onto RGB). COVERED (host-tested).
3. **Crop/CFA/orientation**: active-area-inside-frame, the four
   Bayer arrangements, the 2x2 rejection, orientation rotations-only
   (mirrors refused). COVERED (host-tested).
4. **Metadata defaults**: absent black/white/activeArea -> 0s / bit
   ceiling / full frame; unknown, never a guess. COVERED
   (host-tested).
5. **The independent-reader check** (spec IN-08's "gerçek decode
   sonucu"): a real camera DNG decoded by the app vs by an
   independent desktop reader (exiftool/darktable) — the tags and
   the decoded pixels agree. DESKTOP leg first (exiftool is the
   MANIFEST's named "still missing" tool), DEVICE leg with the
   owner's actual sources. PLANNED (later wave).
6. **IN-17 pre-open consistency**: stripByteCount/offset/frame-count
   checks reject a corrupted file BEFORE it opens as a source.
   COVERED at the metadata level (host-tested); the open-path
   wiring is the integrator's. PLANNED.
7. **The Linear DNG no-double-process check**: an isRitiLinearDng
   file routes to the developLinear path and is not re-normalized.
   The writer's half is pinned (LinearDngContractTest); the reader's
   half is the later wave. PLANNED.

Device-pending beyond these: the real-source matrix (the owner's
actual DNGs: Android DngCreator exports, camera-app DNGs, the app's
own exports re-imported) — the subset's honest-refusal rules meet
their real inputs there.

---

## 5. Status

- The pure single-DNG metadata model (DngFileModel.kt + 54 tests):
  **implemented + host-tested** (BUILD SUCCESSFUL, the full suite
  green at this package's commit).
- This decision document: **planned / review-pending** — the owner
  reads §2 and ratifies; the integrator owns §3.6's decisions.
- The pixel decode, the reader's IO leg, the SourceKind seam and the
  B4 wiring: **planned** — later waves; nothing in the app imports
  the model yet, no runtime behavior changed.
- LibRaw: **planned as the camera-format wave** — the NDK build
  spike and the license review are that wave's first tasks (§2.2).
