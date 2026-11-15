# Linear DNG Audit — RAW-13 / QA-17 Gap List (2026-11-16)

Read-only audit of the EXISTING Linear DNG writer (no writer changes in
this package — bugs and gaps found here are ROUTED to the writer's owner).
The writer is audited as it is, not as the spec wishes it to be: every
claim below cites the file and line where the behavior lives.

**Scope.** The Linear DNG export is one native call chain:

```
ExportQueue.runOne (Target.DNG, Target.LINEAR -> dng.export)
  -> DngExporter.export (staging: out.partial -> finalize rename)
    -> DngRender.renderLinear (NativeDngRender)
      -> NativeSource.jniLinearDngExport
        -> JNI nativeLinearDngExport (app/src/main/cpp/jni_bridge.cpp:4910)
          -> dngCropCut (RAW-09 cut, BEFORE the develop)
          -> rawedit::developLinear (core/src/Develop.cpp:1288)
          -> rawedit::writeLinearDng (core/src/DngWriter.cpp:380)
```

**Verdict vocabulary.** COVERED = the clause has a live code path with a
citation. PARTIAL = a real gap exists in a clause (named, cited). MISSING
= the clause has no code path at all. HOST-UNTESTABLE = the clause
depends on device/desktop behavior this worktree audit cannot execute;
the code path is cited but the behavior is device-test-pending, never
counted as passed. The external-reader clause (QA-17's
Lightroom/RawTherapee leg) is a reading of the tags, not a claim of
tested behavior — the real reader pass is device/desktop-pending.

---

## 1. The existing writer's ACTUAL contract

### 1.1 Pixel path (developLinear, core/src/Develop.cpp:1288-1346)

Exactly three stages of the 24-stage developCpu pipeline run, and nothing
else (core/include/rawedit/Develop.h:357-369, the "and NOTHING else"
contract):

- **Stage 1 — black/white normalization** (Develop.cpp:1298-1322):
  frame-preferred levels — `dynamicBlack[0] >= 0` selects the frame's
  per-slot black (Develop.cpp:1301), `dynamicWhite > 0` selects the
  frame's white, else the container `meta.white` (Develop.cpp:1302-1304);
  `range = white - black` per slot, or `1.0f` when degenerate
  (Develop.cpp:1310-1312); each CFA sample is
  `(v - black[slot]) / range[slot]`, clamped to 0..1 (Develop.cpp:1316-1320).
- **Stage 4 — bilinear demosaic** (Develop.cpp:1325, shared
  `demosaicBilinear` with developCpu, Develop.cpp:655).
- **Stage 5 — as-shot white balance** (Develop.cpp:1327-1336):
  `invNeutral[i] = asShotNeutral[i] > 0 ? 1/asShotNeutral[i] : 1.0f`,
  multiplied per channel. No camera->Rec.709 after it — the pixels stay
  in the camera-native primaries.
- **Tail** (Develop.cpp:1338-1344): `lround(clamp(v,0,1) * 65535)` ->
  `width*height*3` uint16 samples, row-major r-g-b.

Deliberately NOT run (Develop.cpp:1271-1278, Develop.h:363-364): lens
shading (2), highlight reconstruction (3), camera->Rec.709 (6), denoise
(7-8), the tone controls (9-20), the detail stages (21-22), tonemap (23),
the LUT (24). The grade snapshot of the job is never applied
(DngExporter.kt:242-244: "The grade snapshot of the job is NOT applied in
either arm").

### 1.2 JNI flow (app/src/main/cpp/jni_bridge.cpp:4910-4989)

1. Open from the dup'ed SAF fd (stage token 0, `kStageOpen`,
   jni_bridge.cpp:70).
2. Decode the frame (stage token 1, `kStageFrameRead`).
3. Effective rotation: `effectiveDeg = ((info.orientationDeg +
   manualRotationDeg) % 360 + 360) % 360`, written into
   `info.orientationDeg` (jni_bridge.cpp:4951-4953) — the tag carries
   container + manual; the pixels stay unrotated.
4. RAW-09 crop cut BEFORE the develop (jni_bridge.cpp:4956-4961) — the
   demosaic runs on the selected region only. `dngCropCut`
   (jni_bridge.cpp:4755-4808): full-frame fractions skip; effective-space
   fractions map through the rotation (90/180/270, jni_bridge.cpp:4769-4774);
   `lround`, 2x2 Bayer alignment, min 2, clamped inside
   (jni_bridge.cpp:4778-4789); plain window copy — never resampled
   (jni_bridge.cpp:4791-4797); after the cut, `info.width/height/activeArea`
   describe the cut window from (0,0) (jni_bridge.cpp:4800-4806).
5. developLinear (stage token 2, `kStageDevelop` — exists ONLY on this
   path; jni_bridge.cpp:4966-4974). An empty result is
   `Unsupported`/stage-2 "linear develop: empty frame".
6. writeLinearDng (stage token 3, `kStageDngWrite`,
   jni_bridge.cpp:4979-4987) to the staging path; the writer removes a
   partial on failure, the Kotlin layer renames on success.

### 1.3 The written file (writeLinearDng, core/src/DngWriter.cpp:380-...)

Little-endian TIFF/DNG, one IFD, 21 entries ascending, external payload,
one strip (DngWriter.cpp:439-450, 490-491). DNGVersion 1.4.0.0
(DngWriter.cpp:540-542). The tag set (DngWriter.cpp:29-51 constants,
495-565 entries):

| Tag | Value | Source |
|---|---|---|
| 254 NewSubfileType | 0 | constant (DngWriter.cpp:495-496) |
| 256/257 ImageWidth/Length | cut frame w/h | `frame.width/height` (post-crop `info`) |
| 258 BitsPerSample | 3x SHORT 16 (external) | constant (DngWriter.cpp:451-452) |
| 259 Compression | 1 (none) | constant (DngWriter.cpp:509-510) |
| 262 PhotometricInterpretation | **34892 LinearRaw** | constant (DngWriter.cpp:513-514) |
| 270 ImageDescription | `kLinearDngStages`, ASCII NUL-terminated (external) | constant string (DngWriter.cpp:373-378) |
| 273 StripOffsets | one strip | layout (DngWriter.cpp:488) |
| 274 Orientation | `tiffOrientation(frame.orientationDeg)`: 90->6, 180->3, 270->8, else 1 | DngWriter.cpp:70-77, 523-524 |
| 277 SamplesPerPixel | 3 | constant (DngWriter.cpp:528-529) |
| 278 RowsPerStrip | h | frame |
| 279 StripByteCounts | w*h*3*2 | computed (DngWriter.cpp:392-395) |
| 50706 DNGVersion | 1.4.0.0 | constant |
| 50710 CFAPlaneColor | {0,1,2} RGB plane order | constant (DngWriter.cpp:545-547) |
| 50711 CFALayout | 1 Rectangular | constant (DngWriter.cpp:550-551) |
| 50713 BlackLevelRepeatDim | {1,1} | constant (DngWriter.cpp:554-555) |
| 50714 BlackLevel | 3x SHORT 0 (external) | constant (DngWriter.cpp:458-462) |
| 50717 WhiteLevel | 65535 (LONG inline) | constant (DngWriter.cpp:560-561) |
| 50721 ColorMatrix1 | 9x SRATIONAL from `meta.colorMatrix` verbatim | RawMeta (DngWriter.cpp:464-466) |
| 50728 AsShotNeutral | 3x RATIONAL **(1,1,1)** | constant by contract (DngWriter.cpp:468-475) |
| 50829 ActiveArea | top/left/bottom/right from `frame.activeArea`, full-frame fallback | FrameInfo (DngWriter.cpp:477-486) |

RATIONAL/SRATIONAL use denominator 1e6 (`putRational`,
DngWriter.cpp:128-130). `kLinearDngStages` verbatim (DngWriter.cpp:373-378):

> ritiMCRAW linear DNG: stages = black/white normalization
> (frame-preferred levels), bilinear demosaic, as-shot white balance
> (divide by AsShotNeutral); no shading, no highlight reconstruction, no
> camera-to-Rec.709, no tone controls, no LUT, no denoise. developed by
> developLinear.

**Tags NOT written** (confirmed by grep over DngWriter.cpp — zero hits):
no OpcodeList1/2/3 (51008/51009/51010), no Make/Model (271/272), no
UniqueCameraModel (50808), no DateTime/DateTimeOriginal (306/36867), no
FocalLength/LensInfo/LensModel (37386/50736/50751), no ApertureValue
(37383), no noise-profile tags (NoiseFunction 48756 / NoiseProfile
51032 / noise-related opcode). The CFA arm of the same writer
(`writeCfaDng`, DngWriter.cpp:140-361) writes the same 21-entry shape
with Photometric 32803 CFA, CFARepeatPatternDim/CFAPattern
(33421/33422) and frame-preferred BlackLevel/WhiteLevel
(DngWriter.cpp:305-312) instead of the linear set.

### 1.4 Kotlin-visible contract (the JVM seam)

- `DngExporter.export` branches on `job.target`: `Target.LINEAR` ->
  `render.renderLinear(...)`, else `render.render(...)` — the same
  staging/finalize/error mapping wraps either file contract
  (DngExporter.kt:279-298).
- The stage/error vocabulary is shared with the CFA arm:
  `DngExportErrors` code = rawedit::ErrorCode (0 ok, 1 truncated, 2
  badHeader, 3 ioError, 4 unsupported), stage = 0 open, 1 frame read,
  **2 develop — the linear path only**, 3 dng write (DngExporter.kt:25-37,
  jni_bridge.cpp:70-73).
- Staging: everything writes to `out.partial`; only a successful render
  is renamed; a failure deletes the partial (DngExporter.kt:205-226, 299-342).
- The queue's LINEAR arm: `Target.DNG, Target.LINEAR -> dng.export(...)`
  (ExportQueue.kt:410-411), output name `<sourceId>-linear.dng`
  (ExportQueue.kt:535-543).

---

## 2. RAW-13 — clause-by-clause

Spec (şartname line 84, verbatim): "Düzenlenmiş fotoğraftan lineer,
demosaic edilmiş DNG. CFA DNG ve Linear DNG ayrı dosya sözleşmeleridir.
Lineer çıktıya hangi aşamaların işlendiğini kaydet. Görüntüleme ton
eşlemesi ile lineer veri çıkışı ayrı yollar." | 2.4.3 deneysel, öneri | B

| # | Clause (spec wording) | Verdict | Evidence |
|---|---|---|---|
| 1 | "Düzenlenmiş fotoğraftan lineer, demosaic edilmiş DNG" (a linear, demosaiced DNG from the edited photo) | **PARTIAL** — the file is a real linear demosaiced DNG, but it is NOT "düzenlenmiş fotoğraf" (the edited photo's grade) | The pixel path IS linear and demosaiced: Photometric 34892 (DngWriter.cpp:513-514), SPP 3, 16-bit RGB, developLinear's demosaic (Develop.cpp:1325). But NO grade is applied — developLinear runs exactly stages 1/4/5 and "NOTHING else" (Develop.h:357-364), and DngExporter.kt:242-244 states "The grade snapshot of the job is NOT applied in either arm: ... a Linear DNG is the linear data deliverable — grading belongs to the reader of the exported file." The honest reading: the current writer delivers the MINIMAL linear develop, not the edited photo's look. The stage list recorded in the file says exactly this, so the file is honest about what it is; the spec's "düzenlenmiş" clause is the gap. ROUTED (decision needed): should Target.LINEAR carry the grade snapshot's develop (stages 9-24) or stay the minimal scene-referred deliverable? The current contract is defensible (scene-referred linear is the interchange format; grading belongs to the reader), but it is not what the clause's first word says. |
| 2 | "CFA DNG ve Linear DNG ayrı dosya sözleşmeleridir" (separate file contracts) | **COVERED** | Separate targets (`Target.DNG` / `Target.LINEAR`, ExportJob.kt:12), separate render methods (`render` vs `renderLinear`, DngExporter.kt:102-137), separate writers (`writeCfaDng` vs `writeLinearDng`, DngWriter.cpp:140/380), distinct Photometric values (32803 vs 34892), distinct tag sets (CFA carries 33421/33422 + frame-preferred levels; linear carries 270/50710 + 0/65535 + identity neutral). Core test `linear_dng_roundtrip_tags` (core/tests/test_dng.cpp:337) pins the linear tag set. |
| 3 | "Lineer çıktıya hangi aşamaların işlendiğini kaydet" (record which stages were processed into the linear output) | **COVERED** | `kLinearDngStages` (DngWriter.cpp:373-378) is written verbatim into ImageDescription (270) (DngWriter.cpp:414-417, 454-456); the string names `developLinear`, and DngWriter.cpp:367-372 states the develop and the tag "must never drift apart". Core test pins the description's key substrings (test_dng.cpp:400-409). |
| 4 | "Görüntüleme ton eşlemesi ile lineer veri çıkışı ayrı yollar" (display tone map and linear data output are separate paths) | **COVERED** | developLinear runs no tonemap/LUT/camera->Rec.709 (Develop.cpp:1271-1278, Develop.h:363-364); the stage list says "no ... no tone controls, no LUT" (DngWriter.cpp:376-377); jni_bridge.cpp:4896-4898 restates it. The display path (developCpu + tonemap) is a different function entirely. |

---

## 3. QA-17 — row verdicts

Spec (şartname line 472, verbatim): "Export edilen CFA DNG ve Linear DNG |
CFA pattern, lens metadata, capture time, orientation ve piksel düzeni
doğrulanır. Lightroom/RawTherapee veya seçilen referans okuyucu ile
görünüm test edilir."

The row covers BOTH files; verdicts are per-axis, and the CFA arm is
included where the axis touches it.

| Axis | Verdict | Evidence |
|---|---|---|
| CFA pattern | **COVERED** for the CFA file / **N/A-by-design** for the linear file | CFA DNG writes CFARepeatPatternDim {2,2} + CFAPattern from `frame.cfa` (DngWriter.cpp:286-290, cfaCodes DngWriter.cpp:81-88); core tests pin it (test_dng.cpp:104 `dng_roundtrip_tags`). The linear file is demosaiced — there is no Bayer pattern to describe; CFAPlaneColor {0,1,2} (50710) carries the RGB plane order instead (DngWriter.cpp:545-547), pinned by test_dng.cpp:411-417. |
| Lens metadata | **MISSING** (both files) | No FocalLength (37386), no LensInfo (50736), no LensModel (50751), no ApertureValue (37383) anywhere in DngWriter.cpp (zero grep hits; the tag constants at DngWriter.cpp:29-51 are the complete set). The DATA EXISTS on the Source side: `RawMeta.focalLengthMm` (35mm-equivalent, 0 = absent, Source.h:99) and `ContainerIdentity.aperture` (0 = absent, Source.h:122) — but neither reaches the writer: both `writeCfaDng` and `writeLinearDng` take only `(path, pixels, FrameInfo, RawMeta)` (DngWriter.h), and RawMeta carries no aperture; ContainerIdentity is not passed at all (jni_bridge.cpp:4954, 4861). ROUTED to the writer's owner: add lens metadata tags to both arms (needs a signature/flow change — single-writer files DngWriter.cpp + jni_bridge.cpp). |
| Capture time | **MISSING** (both files) | No DateTime (306) / DateTimeOriginal (36867) tags (zero grep hits). The data exists: `FrameInfo.ptsUs` (presentation timestamp, Source.h:42) and `FrameInfo.recvdMs` (epoch ms, -1 = absent, Source.h:64) — neither is serialized by either writer. ROUTED to the writer's owner (same signature/flow change as lens metadata). |
| Orientation | **COVERED (code)** — tag contract pinned, rotated values pinned only via the JVM mirror; **device legs device-test-pending** | The tag carries the effective rotation (container + manual, jni_bridge.cpp:4951-4953) via `tiffOrientation` (90->6, 180->3, 270->8, else 1, DngWriter.cpp:70-77); pixels stay unrotated (rotation belongs to the reader, jni_bridge.cpp:4948-4950). The mapping is JVM-tested through the mirror `RotationState.dngOrientationTag` (RotationState.kt:103-108, RotationStateTest.kt:96-105); core test_dng.cpp:384 pins only the unrotated value 1 on the linear file — rotated values in the actual file are NOT pinned by a core test. A reader honoring the tag is the external-reader leg (device/desktop-pending). |
| Pixel layout | **COVERED (code)** | Linear file: 16-bit 3-sample RGB, one strip, `w*h*3*2` bytes, verbatim pixel round-trip pinned by core test (test_dng.cpp:379-390, 468-472); developLinear's output bit layout matches the strip exactly (Develop.h:359-360, Develop.cpp:1338-1344). CFA file: single-sample mosaic, pinned by test_dng.cpp:104. Crop: plain window copy, never resampled (jni_bridge.cpp:4791-4797). |
| External reference reader (Lightroom/RawTherapee) | **HOST-UNTESTABLE** — device/desktop-pending, never "pass" | See section 5. No reader was run in this audit; the verdict there is a READING of the tags, not tested behavior. |

---

## 4. The research's specific checks

**WB-baked / ColorMatrix1 / AsShotNeutral consistency — COVERED
(self-consistent by construction).** The WB is baked into the pixels by
developLinear stage 5 (Develop.cpp:1327-1336, multiply by
1/asShotNeutral), and the file declares identity AsShotNeutral (1,1,1)
"because this multiply already applied the WB ... The identity neutral is
the DNG contract for 'already white-balanced data'" (DngWriter.cpp:468-475).
A reader therefore does NOT re-apply WB (no double-WB neutral shift).
ColorMatrix1 is kept verbatim from the container (DngWriter.cpp:464-466)
and the pixels stay in the camera-native primaries (Develop.cpp:1328-1331,
jni_bridge.cpp:4904-4905) — matrix and pixels describe the same space.
Core test pins ColorMatrix1 kept + neutral identity (test_dng.cpp:444-458).

**Black/white level — COVERED (self-consistent).** Stage-1 normalization
mapped the sensor range to 0..1 before the 65535 scaling
(Develop.cpp:1298-1322, 1338-1344), so the stored samples' black is 0 —
"a reader must not subtract a sensor black again" (DngWriter.cpp:458-462);
WhiteLevel 65535 (DngWriter.cpp:560-561). Both pinned by core test
(test_dng.cpp:429-442).

**Orientation / active area — COVERED (code), one honest caveat.** The
tag carries the effective rotation while the pixels stay unrotated
(jni_bridge.cpp:4948-4953); the crop cut resets width/height/ActiveArea
to the cut window from (0,0) (jni_bridge.cpp:4800-4806), and
writeLinearDng serializes ActiveArea from `frame.activeArea` with a
full-frame fallback (DngWriter.cpp:477-486). Caveat: `FrameInfo.activeArea`
is "full frame today; see Source.cpp (QA-02 pending)" (Source.h:47) — the
container's true active area is not yet extracted (QA-02 routed in
docs/ACCEPTANCE_GAPS_2026-11-16.md), so ActiveArea currently always
describes the whole (cut) frame, never a sensor-margin subset. Consistent,
but not yet informative.

**Capture time — MISSING.** See QA-17 table above.

**Lens / noise metadata — MISSING.** Lens: see QA-17 table above. Noise:
no noise tags in either writer (zero grep hits for OpcodeList/NoiseFunction/
NoiseProfile). `NoiseProfile` (valid, s[4], o[4]) exists on the Source
(Source.h:132-136) and is not passed to the writer. The şartname's "Kare
çıktıları" paragraph (line 84, after RAW-17) requires: "DNG'nin
orientation, active area, camera profile ve noise semantiği çıktının
gerçek piksel düzeniyle tutarlı olsun" — noise semantics must be
consistent with the actual output pixel layout. Writing NO noise tags is
the safe choice (a CFA-domain NoiseProfile would be WRONG for demosaiced
RGB pixels — its S/O describe per-CFA-slot variance), but the spec asks
for a semantics decision, not silence. ROUTED to the writer's owner:
either derive RGB-domain noise semantics for the linear file or document
the deliberate omission in the writer's contract.

**Opcodes — none written; the double-lens-correction hazard is ruled
out.** No OpcodeList1/2/3 tags exist in either writer (zero grep hits).
The CFA arm keeps Bayer pixels verbatim, and the linear arm runs NO lens
shading stage (Develop.cpp:1271-1272 "Deliberately NOT run: lens shading
(2)"; the stage list says "no shading", DngWriter.cpp:376). The hazard
the "Kare çıktıları" paragraph names — a Linear DNG carrying lens
correction baked into pixels PLUS an opcode/metadata that re-applies it
— cannot occur: neither the pixels nor the file carry any lens
correction. (Note: the CFA arm also writes no shading tag — RAW-12's
"lens shading ... koru" clause is a separate row, routed with it.)

**Linear-labeling honesty — COVERED.** PhotometricInterpretation 34892
LinearRaw labels the file as linear (DngWriter.cpp:513-514); the
ImageDescription states the exact stage list including every "no ..."
(what was NOT applied, DngWriter.cpp:373-378); no display transform is
baked (no camera->Rec.709, no tonemap, no LUT — Develop.cpp:1271-1278);
BlackLevel 0/WhiteLevel 65535 match the actual normalization
(DngWriter.cpp:458-462, 560-561); identity AsShotNeutral matches the
baked WB (DngWriter.cpp:468-475). A reader can tell exactly what this
file is from the tags alone.

---

## 5. The independent-reader question (device/desktop-pending)

What would Lightroom / Resolve / dngsdk / RawTherapee CONCLUDE from the
tags as written? This is a reading of the tag set (section 1.3), not a
claim of tested behavior — the real external-reader pass is
device/desktop-pending and is NOT counted as passed anywhere in this
document.

A reader opening the linear file sees: DNG 1.4.0.0,
PhotometricInterpretation 34892 (LinearRaw), 16-bit 3-sample RGB, one
uncompressed strip, BlackLevel 0 / WhiteLevel 65535, AsShotNeutral
(1,1,1), a camera-native ColorMatrix1, CFAPlaneColor RGB, CFALayout
Rectangular, an Orientation tag carrying the effective rotation, an
ActiveArea equal to the full (cut) frame, an ImageDescription naming the
exact develop stages — and NO opcodes, NO Make/Model, NO capture time,
NO lens tags, NO noise tags. From that a compliant reader should:
render the strip as linear scene-referred data, convert via ColorMatrix1
without re-applying WB (identity neutral), not subtract any black,
honor the orientation tag for display, and ignore the (absent) opcode
lists. Expected frictions, readable from the tags alone:

1. **No Make/Model/UniqueCameraModel.** DNG readers use camera identity
   for profile selection and sometimes for basic sanity; its absence may
   push the reader to a default/fallback path (Lightroom historically
   wants Make/Model to even list a file). This is the most likely
   first-run friction and is part of the camera-identity gap routed
   below.
2. **ColorMatrix1 without ForwardMatrix1.** The file carries
   ColorMatrix1 only; `RawMeta.forwardMatrix` exists on the Source
   (Source.h:93) and is not written. Readers handle ColorMatrix1-only
   files (that is the DNG 1.0-1.3 shape), so this should degrade
   gracefully, but the calibration path is less precise than a
   ForwardMatrix1-equipped file.
3. **LinearRaw + 3 samples is the DNG 1.4 shape** — supported by dngsdk
   and current Lightroom; older/lighter readers may not expect
   LinearRaw with SamplesPerPixel 3.
4. **Identity AsShotNeutral is well-formed** (the documented contract
   for already-balanced data) — a reader applying "as shot" WB from the
   tag is a no-op, which is the intent.
5. **The crop case is self-consistent** — dimensions, ActiveArea and
   the strip all describe the cut window; the orientation tag still
   carries the effective rotation, so an upright render of a cropped
   rotated clip is tag-honest.

The actual pass (open both exports in Lightroom/RawTherapee, verify
appearance) is a device/desktop leg: **device-test-pending**, never
"pass".

---

## 6. Routed gaps (for the writer's owner — no fixes made here)

All writer-side changes below live in single-writer files
(core/src/DngWriter.cpp, app/src/main/cpp/jni_bridge.cpp per the queue's
shared-file locks); this audit changed nothing.

| # | Gap | Severity | Where the fix belongs |
|---|---|---|---|
| G1 | Lens metadata tags (FocalLength/LensInfo/LensModel/ApertureValue) absent from BOTH arms while the data exists on Source (`RawMeta.focalLengthMm`, `ContainerIdentity.aperture`) | Medium (QA-17 axis) | DngWriter.cpp + jni_bridge.cpp (signature/flow change: pass identity/lens facts to both writers) |
| G2 | Capture time tags (DateTime/DateTimeOriginal) absent from BOTH arms while `FrameInfo.ptsUs`/`recvdMs` exist | Medium (QA-17 axis) | Same signature/flow change as G1 |
| G3 | Camera identity (Make/Model/UniqueCameraModel) absent — the most likely external-reader friction (section 5.1) | Medium (QA-17 external leg) | Same signature/flow change; `ContainerIdentity` already carries the values (Source.h:117-123) |
| G4 | Noise semantics decision for the linear file: the spec's "Kare çıktıları" paragraph requires noise semantics consistent with the output pixel layout; the writer writes none (safe but silent — a CFA NoiseProfile would be wrong for RGB pixels) | Low (decision + doc) | Writer owner decides: derive RGB-domain semantics or document the deliberate omission in the writer contract |
| G5 | RAW-13 clause 1 wording: the linear file is the minimal scene-referred develop, not the "düzenlenmiş fotoğraf" (the edited photo's grade) — a product decision whether Target.LINEAR should carry the grade | Decision (not a bug; the file is honest about what it is) | Product/writer owner; the current contract is defensible and documented |
| G6 | Rotated Orientation values (6/3/8) in the actual written file are not pinned by a core test (only 1 is; the mapping is pinned via the JVM mirror `RotationState.dngOrientationTag`) | Low (test coverage) | core/tests/test_dng.cpp when a core test slot opens (build-file edits are locked tonight) |
| G7 | ForwardMatrix1 exists on Source (`RawMeta.forwardMatrix`) and is not written; ColorMatrix1-only is valid DNG but less precise | Low | Same signature/flow change as G1 if the owner elects to write it |

Device/desktop-pending legs (not gaps in code, but unpinned behavior):
the external-reader pass (QA-17's Lightroom/RawTherapee clause) over both
exported files, including the crop+rotation composition; real-device
export of a real clip through the LINEAR target.

---

## 7. Test coverage map (what pins the contract today)

- **Native byte contract** — core/tests/test_dng.cpp (doctest, desktop
  build via tools/build-desktop.sh): `linear_dng_roundtrip_tags`
  (test_dng.cpp:337-482) pins Photometric 34892, SPP 3, BPS 3x16,
  ImageDescription substrings, CFAPlaneColor, BlackLevel 3x0,
  WhiteLevel 65535, ColorMatrix1 kept, AsShotNeutral (1,1,1), ActiveArea,
  the verbatim pixel round-trip and ascending tags;
  `linear_dng_rejects_bad_input` (test_dng.cpp:484) pins the null/
  empty/depth rejections.
- **developLinear's pixel math** — NOT pinned by any core test (no
  `developLinear` TEST_CASE exists; grep over core/tests returns zero).
  The math is documented (Develop.h:357-369) and pinned indirectly
  through the GPU byte-honesty reference (jni_bridge.cpp:2596-2678 uses
  developLinear as the reference shape). A core test would need
  build-file edits (locked tonight) — noted as G6-adjacent, not fixed
  here.
- **Kotlin-visible contract** — app/src/test/java/com/ritim/ritimcraw/
  export/DngExporterTest.kt (linear routing, staging, error mapping,
  rotation/crop pass-through, native-unavailable fallback) and
  ExportQueueTest.kt:456-477 (queue dispatch to renderLinear,
  `-linear.dng` naming). The NEW LinearDngContractTest.kt (this package)
  adds the linear-specific pins that were missing: the develop stage
  token's linear-only meaning, the linear arm's failure/cleanup
  contract, and the honesty invariant that the linear path never routes
  through the CFA render — see the test file's header for the seam
  statement (the native byte contract is pinned by core/tests, not
  faked in JVM).
