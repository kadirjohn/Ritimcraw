# DCG RAW video: plan derived from the research report

Source: the owner's research report `DCG-RAW-Video-Arastirma-Raporu.md` (2026-11-26, written against commit b884460), cross-checked with this project's own measurements. This file is a plan only: no code is implied by it, every item that changes pixels is flagged as an owner decision (goldens / old-project images / parity bounds).

## 1. What the report and our measurements agree on

| Fact | Report (section) | Our measurement (command / source) |
|---|---|---|
| 24 mm DCG clip: black 1023, white 16383, brightest green code 16368 | 1 (local decode) | `hr_measure` / `hr_levels` on `261125_200450`: levels black 1023, white 16383, frame 0 G max 16368 |
| 99 mm night clip: black 0, white 16383, green plateau 16356, 19,249 green sites at that code in frame 0 | 1 | `hr_levels night.mcraw`: G maxNorm 0.99835, 19,249 sites at the top code |
| R/B ceilings track (green clip x as-shot neutral ratio) and move with AWB per frame | 1, 2 | R plateau x (1/neutral) = 16357 = G plateau, frame by frame; the ratio drifts (frame 300: R plateau 0.7186 vs 0.6142 expected from container neutral) |
| Code spacing of 16 in the 24 mm DCG clip is not a whole-raster grid | 1 | Dark frames put most pixels on a few repeated codes (the reason the 16-bit parity gate tripped) |
| No sample code above the declared white in any inspected frame | 1 | Same: `maxNorm` never exceeded 1.0 |

Consequence: the per-frame, per-channel plateau detector (`recoveryClipLevels`) is the right shape; the report's caveat is that it is not a general calibration (it assumes a sharp peak and, for R/B, a green plateau).

## 2. Highlights on DCG: why the slider looks dead (measured)

`main` (knee 0.18), CPU, `/255` display units, Highlights -100 vs 0 on the brightest 5% / 0.5% of pixels:

| Clip | -100 top 5% | -100 top 0.5% |
|---|---|---|
| night 99 mm (no tonemap) | -10.3 | -58.1 |
| yavas_pan day | -8.7 | -21.3 |
| 24 mm | -11.6 | -36.9 |
| 72 mm | -25.2 | -46.4 |
| **DCG 57 s, +1.7 EV** | **-0.28** | **-2.8** |

The code is right (device preview agrees with the CPU, parity unchanged). DCG frames are dark: almost no pixel sits above the knee, and the few clipped lamp pixels are a handful of sites (frame 0: 78 green sites at the top code). Highlights works above the knee only; there is nothing for it to act on. This matches the report's section 4 (the knee-0.18 curve is weak and a display transform flattens it further) and its open question 5.

## 3. Plan, mapped to the report's priorities

| ID | Report | Item | Pixel impact | Owner decision needed |
|---|---|---|---|---|
| D0 | P0 | **Raw diagnostics in the RAW scope.** Extend the existing scope (`RawStats.cpp`, editor RAW scope) with: declared black/white, separate Gr/Gb, raw peak code, plateau support (sites within 0.4% of the peak), count of sites above declared white, and a "no reliable plateau" state. Same resolver as the develop path (`recoveryClipLevels`) so both read one answer. | none | no |
| D1 | P1 | **Reversible per-clip "RAW Levels" control:** one white, four blacks, optionally the clip-mask thresholds; "Measure" fills candidates, "Back to metadata" restores; CPU preview, GPU preview and export all resolve it identically; original-CFA export keeps original samples and tags. Default = metadata, so old projects do not change. | only when the user sets it | UI placement; whether DNG export may write calibrated tags |
| D2 | P2 | **Clip mask from the raw sample and active area**, fixed per-clip calibration first, dynamic ceiling / per-frame neutral second; never convert an ambiguous R/B peak into a WB multiplier. Test whether demosaic/NR/LUT assume hard clipping before keeping data above the reference white in the float path (no clip data above white was found in any inspected frame, so this is low urgency). | behind D1 setting | parity experiment on device before enabling |
| D3 | P3 | **Make the Highlights/Recovery effect visible:** show the mask / clipped-channel overlay for both controls; trigger Recovery from the pre-shading raw clip mask (already so); measure the curve with a lamp ROI protocol (section 4 below). If a stronger roll-off is wanted: design a display-referred or scene-referred curve that works below 0.18, with CPU/GPU shared code and golden updates documented. | **yes** (golden/old projects) | product decision: what should the slider do on a dark scene with a bright lamp |
| D4 | P4 | Measure WB/matrix portability between the DCG mode and normal RAW of the same sensor; measure NR needs with step wedge / dark-flat; temporal ghosting and colour flicker on video. | none | no |
| D5 | P5 | Only if a repeatable merge seam is shown by D4 data: sensor response correction (per device/lens/mode). | yes | evidence first |

## 4. Measurements still missing (they need the phone or new files)

1. Lamp ROI protocol on the 57 s DCG clip: pick frames where the lamp beam is large (frame 0 is dark), measure Highlights -100/0 on the ROI at knees 0.18 / 0.25 (numbers, not eyeballing).
2. Exposure sweep at fixed light and lens, dark frames, colour patches: mean response curve and noise variance per tonal range, to see whether a HCG/LCG seam exists (report section 3, 5, 8).
3. Report open questions 1-5 (16-step to dense-code transition range, whether R/B ceilings follow AWB spatially, vivo above-4095 codes, matrix/noise differences between modes, tonemap-before-max-RGB to ROI mapping).

## 5. Suggested first slice (small, no pixel change)

D0: richer raw diagnostics in the RAW scope plus a JVM test on the pure statistics (numbers from `hr_levels` as fixtures: 24 mm DCG, 99 mm night, 24 mm normal). Then D1 behind a default-off, reversible control. Items that change pixels (D3 curve, D5) wait for the owner's product decision and a documented golden update.

## 6. Notes

- The vivo forum claim (white 4095 declared, ~4860 real, LYT-901; 8692 for LYT-818) is a report item ("reported"), not a documented spec, and does not match our Xiaomi files (declared 16383 with a ~0.9984 plateau).
- Parity and rounding: the report's note that CPU and GPU may differ at 16-bit rounding ties on repeated dark codes is consistent with the runtime gate decision (gate checks `maxDiffLsb <= 1`; the 0.01% share stays in the device suites).
