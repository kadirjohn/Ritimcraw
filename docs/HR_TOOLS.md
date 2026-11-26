# Highlight-recovery desktop tools (hr_*)

Four desktop tools measure what the highlight-recovery work needed: where
the sensor actually clips, how the per-frame clip levels behave, what the
re-estimate does to a real frame, and which stage moves a suspicious pixel.
They are plain C++ mains in `core/tests/` (not doctest suites) built by
`tools/build-desktop.sh`; every number they print comes from the frame. The
GPU passes and the CPU oracle share the same `recoveryClipLevels` thresholds
(the GPU pipeline calls the CPU function per frame), so what these tools
measure is what the engine actually runs.

Build (from the repo root):

```
bash tools/build-desktop.sh          # builds everything including the hr tools
```

The binaries land in the desktop build directory the script prints
(`build-desktop/hr_measure` etc.). Optional flags are environment
variables; there are no command-line flags beyond the arguments listed in
each usage line.

---


## hr_measure — M0: where clipping really sits

Usage: `hr_measure <clip.mcraw> <frameIndex>`

The M0 step of docs/HIGHLIGHT_RECOVERY_PLAN.md: on a REAL frame, answers

1. whether channels saturate AT the metadata white level or below it — the
   per-channel share of sites at >= 1.00 / 0.99 / 0.98 / 0.97 / 0.95 of the
   normalized range, plus the most frequent top raw codes;
2. what stage 3's own test (`norm * lens-shading gain >= 1.0` after the
   stage-1 clamp) calls clipped versus the raw `>= white` truth: the sites
   it misses (raw at white but shading gain < 1) and the sites it
   over-flags (raw below white pushed over 1.0 by a gain > 1).

Reports two regions for each channel: the whole frame, and the "bright
tiles" — the top 1% of 64x64 tiles by mean normalized G, so a window or
lamp is found without guessing the orientation. Also prints the frame's
`recoveryClipLevels` thresholds and a Neutral-vs-Recovery comparison
(pixels changed, mean sum(RGB) ratio on changed pixels, values lowered by
matrix cross-terms, pixels with a channel > 1.0, the maximum of both
renders, and the 5x5 neighbourhood around the recovery maximum).

Environment flags:

- `HR_K=<K>` — override the shipped headroom cap for the Neutral-vs-
  Recovery comparison; `HR_K=0` disables the cap.
- `HR_CROP=x,y,w,h` — write `hr_neutral.ppm` / `hr_recovery.ppm` (plus
  `_2ev` variants at exposure -2 EV) of that crop for a visual
  before/after.

Findings with this tool are recorded in docs/HIGHLIGHT_RECOVERY_PLAN.md
(M0 results).


## hr_levels — per-camera clip-level sweep

Usage: `hr_levels <clip.mcraw> [step=20]`

Prints the measured `recoveryClipLevels` over a frame sweep (every `step`
frames): per channel the threshold (1.0 = none), maxNorm, topCount and the
plateau band count. Use it to compare a clipped and an unclipped clip and
to check a new camera: a clip whose frames never show a sharp G peak must
not flag normal highlights as clipped. The device-verification table for
the current cameras lives (untracked) at
`.planning/night-2026-11-25/device/levels-per-camera.txt`.


## hr_headroom — the K cap measurement

Usage: `hr_headroom <clip.mcraw> <frameIndex> [x,y,w,h]`

Measures the re-estimate's headroom before the cap ships:

- the distribution of estimate / clipped-value over every re-estimated
  channel-pixel (uncapped), overall, per channel, and split by how large
  the reference channel is relative to its own clip level;
- how many > 4x channel-pixels are isolated (fewer than 3 other > 4x
  pixels within 4 px);
- a sweep of headroom K x minimum-reference-fraction: pixels changed
  versus the uncapped recovery, the maximum linear value after the camera
  matrix, and — when the optional core box `x,y,w,h` is given — the mean
  linear RGB of that box, so a rule that kills the genuinely recovered
  core shows up.

The shipped cap (K = 4) was chosen from this measurement; the numbers are
in docs/HIGHLIGHT_RECOVERY_PLAN.md.


## hr_trace — per-stage trace of one pixel

Usage: `hr_trace <clip.mcraw> <frame> [x y] [exposureEv highlights chromaDenoise noiseReduction]`

Traces ONE pixel through the develop chain, stage by stage (cumulative,
linear unless noted):

- `raw` — the 2x2 raw cell around the pixel (codes and normalized values);
- `S2` — after stage 3 + white balance (camera RGB, before the camera
  matrix; matrices set to identity so this is the WB-only image);
- `S3` — after the camera -> Rec.709 matrix (stage 6);
- `S4` — + noise reduction (stages 7/8);
- `S5` — + exposure (stage 14);
- `S6` — + highlights (stage 15) = the linear image the tail receives
  (tonemap off);
- `disp` — OETF (linearToRec709) + clamp to [0,1] -> bytes (what the
  screen shows), plus the 13-sample neighbourhood spread along x so a thin
  streak is visible.

Each line prints R G B, the chroma ratios R/G and B/G, and flags a channel
< 0 or > 1. Without `x y` it searches the developed frame for the most
lilac pixel (display R and B above G, bright) and traces that.

Environment flags:

- `HR_BOX=fx0,fy0,fx1,fy1` — restrict the automatic search to a fraction
  box of the frame.
- `HR_LUM=<0..1>` — the display-luma floor for the search (default 0.45).

This is the tool that showed the lilac streak (24 mm window clip) is
already in the white-balanced image: WB gives R/G 1.05-1.16, the camera
matrix raises it to 1.14-1.39, exposure and highlights change nothing
(uniform gain). No negative channel; fix is a separate decision.
