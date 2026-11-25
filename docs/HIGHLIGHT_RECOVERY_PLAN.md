# Highlight Recovery — plan, measured results, GPU cost options

Status: BUILT AND MERGED (2026-11-25/26). CPU reference `ca0d2d0`,
per-frame/per-channel clip levels `b268af2`, GPU twin + grade field +
button `0ec9434`, parcel slot `7aed86d`. Device parity green (below).
The GPU frame-time target (<= 10 ms at 4K) was MISSED: +16 ms
fast clock / +46 ms sustained (measured, 2026-11-26). The cost options
and the morning measurement plan are the open work — appended at the
end. This document keeps the original plan text (as approved) and adds
the measured results under each heading.

## Why

The shortcut button I shipped only set the Highlights tone slider to -70
and was removed. Highlights (stage 15) remaps display-referred tones
after the fact; it cannot bring back information a clipped sensor
channel never stored. (Stage-15 history, kept current elsewhere: the
knee moved 0.75 -> 0.40 -> 0.18, see `core/src/Develop.cpp`'s stage-15
comment; the short-lived curve versioning was removed in `6b62faa`.)

Stage 3 ("Neutral", `grade.highlightReconstruction`) is the opposite of a
recovery: a channel clipped at white is CAPPED DOWN to the level the
unclipped channels of its 2x2 cell carry. That avoids magenta but throws
the clipped channel's headroom away; nothing above 1.0 is ever produced.

Owner evidence (2026-11-25 screenshot, clip 261124_144344, ~00:02, Gain
+1.1 EV, Highlights -100): a lilac streak in the bright window area. A
screenshot cannot prove the cause; M0 measured it on the real frame
(below; the trace tool is `hr_trace`, README in docs/HR_TOOLS.md).

## Behaviour

A Develop mode with three values: Off / Neutral / Recovery.

- Off = `highlightReconstruction=false`. Neutral = stage 3. Recovery =
  real re-estimation (below); may produce values above 1.0.
- The button toggles Recovery on/off. A second press only turns the mode
  off (back to the previous mode); it never touches the Highlights
  slider.
- Old projects keep their image: absent field -> mapped from the
  existing bool (true -> Neutral, false -> Off). Recovery is only
  entered by the user.
- MCRAW only (encoded clips have no CFA; the ISP already clipped them),
  same gate as `showRawStageToggles`.

As built: a second boolean, `highlightRecovery`, grade field + parcel
slot 29 (`7aed86d`); the GPU draw REPLACES the PRE pass (stage 3 + the
camera matrix move after the recovery math) rather than adding a pass.
The button shipped; it has NOT been tried on device yet (owner smoke
test pending).

## Method (we write the code ourselves)

Work in the demosaiced, white-balanced camera RGB (after stage 5 gains,
before the camera matrix), where neutral means R=G=B so channel ratios
behave like luminance. Stage 3's CFA pass is replaced, not stacked, when
mode = Recovery. (As built and in the code: `recoverClippedHighlights`
in core/src/Develop.cpp; scales = 9x9 box at full res + 3x3 block
neighbourhoods of 8/32/128/512-pixel blocks; a scale is used at >= 5%
unclipped-pair support of its area.)

## Measurement (results, measured 2026-11-25/26)

- M0 (real frame, `hr_measure` + `hr_levels`): NO sensor clipping on
  261124_144344 frames 100/125/150 (G <= 0.75, R <= 0.31, B <= 0.45 of
  white; frame 612+ has 37 G sites). The night clip (99 mm) DOES clip:
  G piles up sharply at 99.84% of white (16356/16383), 19,249 sites.
  R and B peak at the frame's neutral ratio x G peak — the camera holds
  the clipped channel NEUTRAL. A fixed "98% of white" threshold is
  therefore invalid: a site at 75% can be clipped, a site at 99.9% may
  not be. This is the frame/channel (kare/kanal) accumulation finding:
  the clip level is MEASURED PER FRAME, PER CHANNEL
  (`recoveryClipLevels`) from the pre-shading raw values — sharp-peak
  rule (merged `b268af2`, owner-approved): maxNorm >= 0.995, >= 16 sites
  at the peak code, peak >= 25% of the 0.4%-of-max band; R/B plateaus
  count only while G is saturated. Cost: stage-3 CPU develop +1.4..5.7%,
  the level scan at 4K about 1.4 ms/frame (raw-integer scan; the naive
  float scan was about 40 ms), and it runs before every recovery frame
  (CPU-side, feeding the GPU's uniform).
- M1 (synthetic ground truth, desktop): one-channel-clip error
  Neutral 0.1746 -> Recovery 0.0105; two-channel 0.2348 -> 0.0257.
  The pyramid was deepened from 3 to 5 scales during M1 (deep clipped
  interiors got no estimate at 3 scales).
- Headroom cap K (`hr_headroom`, night clip frames 0/900): camera-space
  estimate/clipped p99 = 3.4 / 4.1, max = 7.0 / 6.0. Shipped K = 4
  (`kRecoveryHeadroom`, CPU + GPU): clamps 0.42% / 1.1% of recovered
  channel-pixels (frame 0: 46 channel-pixels, max linear 10.5 -> 6.0).
  The reference-fraction rule measured nothing to gate on and ships
  off (0).
- M3 parity (device, sliced night clip): 7 cases, worst <= 1 LSB,
  mismatch <= 0.0001%, over-1 = 0.0000% — neutral, stage-3-off,
  recovery, recovery -2 EV, recovery Highlights -100 (GPU == CPU).
- M2 (visual, Gain -2 EV shows tone where Neutral shows flat white):
  covered on desktop by `hr_measure`'s `HR_CROP` before/after PPMs;
  no on-device screenshot run is recorded. Ölçülmedi (not measured):
  per-pass GPU time breakdown — the debug property exists
  (`setprop debug.ritim.rectime 1`, pass-level glFinish brackets +
  ritim-trace log) but no recorded run exists yet.

## CPU reference and parity

- CPU `Develop.cpp` implements the method first; it is the oracle.
  Merged `ca0d2d0`.
- GPU matches under the usual bounds only: 0.01% mismatch, <= 1 LSB
  (device result above; no loosening).
- Parcel (as built): slot 26 (`highlightReconstruction`) stays a strict
  0/1 bool; Recovery got its OWN boolean slot 29, `highlightRecovery`
  (`7aed86d`) — the 0/1/2 widening was not used. (The short-lived
  `highlightsCurve` slot is gone, `6b62faa`; renumbered then, so the
  parcel carries 30 scalar fields today.)
- Golden hashes for existing modes did NOT change; Neutral and Off stay
  bit-identical.

## GPU cost (measured)

Target: Recovery adds <= 10 ms per frame at 4K30 (4096x3072, 12.6 MP)
on the Xiaomi 25010PN30G, measured, not estimated. RESULT: the target
was missed — the Recovery chain ADDS +16 ms at fast clock and +46 ms
sustained (thermal), on top of the normal develop chain. The original
cost analysis (extra passes: mask+ratio build, two separable blur
levels at reduced resolution, composite) underestimated the number of
full-resolution RGBA32F passes actually required by the parity-exact
design: 4 layers deep (see the arithmetic below). What is below is the
plan-level statement of the pieces; the pass-by-pass arithmetic and the
options to reach <= 10 ms follow in the design note.

## Order of work (completed)

1. M0 + M1 harness (desktop, no device) — done (`hr_measure`,
   `hr_levels`, `hr_headroom`, `hr_trace`).
2. CPU method + tests — merged `ca0d2d0`. 3. Mode field/parcel/validator
   — `7aed86d`. 4. GPU passes + parity on device — merged `0ec9434`
   (parity above). 5. Button + strings + Develop UI — merged with 4.
   6. Frame-time measurement — measured; target missed; options below.

---

# GPU cost design note (night of 2026-11-26)

Arithmetic below is derived from the ACTUAL shipped shader/pipeline code
(`GlesShaderSources.RECOVERY_*`, `GpuDevelopPipeline::recoveryDraw`), at
4K = 4096x3072 = 12,582,912 px (12.6 MP). It is a derivation, not a
measurement — the morning plan at the end measures it. Formats as read
from the code: CFA texture R16UI (2 B/site -> 25 MB/frame uploads);
develop intermediates texA/texB RGBA32F (16 B/px -> 201 MB each); the
ratio pyramid is GL_TEXTURE_2D_ARRAY RGBA32F with 6 layers (ordered
channel pair p = c*2 + (r > c ? r-1 : r)), level grids ceil(w/4) = 1024x768
(4-px blocks, level 0), 512x384 (8-px), 128x96 (32-px), 32x24 (128-px),
8x6 (512-px).

## Where the ~1 GB/frame goes (deepest-DRAM estimate, tile-cache reuse assumed)

| Pass (per recovery frame) | Reads | Writes |
|---|---|---|
| CPU `recoveryClipLevels` scan | 25 MB (raw, once) | 3 floats (uniform) |
| 1. RECOVERY_PRE (demosaic+WB+mask) | CFA ~25-100 MB (site reuse ~4-9x) | texB 201 MB |
| 2. RECOVERY_BLOCK0, per needed pair-group (up to 2: pairs 0-2 / 3-5) | texB 201 MB per group (a 4x4-tap block grid touches every pixel once per group) → up to 403 MB | level 0: 3 layers x 37.7 MB per group (75 MB both) |
| 3. RECOVERY_BLOCK_UP (levels 8/32/128/512, x2 groups) | <= ~80 MB total | ~20 MB |
| 4. RECOVERY_COMPOSITE (ratio select + estimate + matrix) | texB >= 201 MB (every pixel reads its own texel) + 9x9 full-res box: 81 taps x 16 B = 1.3 KB per clipped-channel pair probe (clip-fraction-bounded: <= ~100 MB on the night clip's 19,249 clipped sites) + <= 36 level taps/probe | texA 201 MB |

Worst-case total ≈ 1.0-1.2 GB per 4K frame (night clip ≈ 1.0 GB).
Implied effective bandwidth: 1.0 GB / 16 ms ≈ 65 GB/s at fast clock — at
or near this part's practical peak, i.e. the added cost is
BANDWIDTH-bound; 1.0 GB / 46 ms ≈ 23 GB/s at sustained (thermally
throttled) clock. So the <= 10 ms target is a TRAFFIC BUDGET: roughly
<= 650 MB if the fast-clock bandwidth holds, or <= 230 MB if the
playback-relevant sustained rate is the real constraint. The morning
measurement decides which gate to design against (playback is sustained,
so the sustained number is the shippable one).

## Three options to reach <= 10 ms

**Option 1 — single-pass multi-tap (GPU-only; the CPU oracle, parity
cases and goldens are untouched — no owner decision needed to TRY).**
(a) Merge the two pair-group draws of BLOCK0/BLOCK_UP into ONE draw each
with 6 MRT attachments (ES 3.0 guarantees >= 8; today's level FBOs bind
3): saves one full texB re-read, −201 MB (~−20%). (b) Remove texB from
the pipeline entirely: PRE is folded away; BLOCK0 taps `uCfa` directly
and computes the demosaic+clip-bit per tap in-shader (the shared shader
text already exists: `kDevelopPreHelpers` + `kRecoveryClipHelper`,
wired into a new source string), and the composite multi-taps `uCfa`
for the pixel value + the 9x9 statistic (CFA sites are 2 B vs RGBA32F
16 B, ~8x cheaper per byte). Savings: −201 (PRE write) − 201 (composite
base read) − 403 (BLOCK0's two texB sweeps) + ~110 MB of CFA window
reads → net roughly −45%, ≈ 550-600 MB/frame → ~9-10 ms fast clock
borderline, ~25 ms sustained — necessary but likely not sufficient
ALONE at sustained clock. Quality: none of the five scale definitions
change; validate with the parity sweep first, then measure. Risk:
occupancy/ALU cost of per-tap demosaic on a mobile GPU is the unknown;
measure before building option 2/3.

**Option 2 — coarser ratio level grid (CHANGES THE CPU REFERENCE →
goldens re-baseline → SAHİP KARARI owner sign-off required).** Drop the
finest scale — the full-res 9x9 box — and take the composite's first
scale at the 8-px level (`nbSum(uL0)`, which already composes as
block-neighbourhood sums). Two effects: (1) removes the clip-fraction
bomb — on a heavily clipped scene (say 5% of pixels re-estimated) the
9x9 box traffic reaches ~1.6 GB worst case; on the night clip it is only
50-100 MB, so the saving here is scenario robustness, not tonight's
number; (2) dropping the 512-px top level saves 2 UP draws x2 groups
(~small MB). NOTE the honest limit: BLOCK0's texB read is
PER-GROUP-FULL-FRAME regardless of block size (block sums touch every
pixel), so option 2 by itself does NOT cut the 403 MB/round-trip costs —
it needs option 1's MRT merge with it. CPU reference: the oracle's
scale table 5 → 4, the ratio near clipped-region borders coarsens from
per-pixel-9x9 to 8-px granularity, the M1 fixture numbers move, and
every recovery golden re-baselines (owner-visible change).

**Option 3 — half-res levels (CHANGES THE CPU REFERENCE → SAHİP KARARI
owner sign-off required).** Build the ratio pyramid from a HALF-RES
(2048x1536) demosaic of the same data: PRE(pyr) write 50 MB (−151),
BLOCK0 2 groups x 50 MB = 101 MB (−302), the composite stays full-res:
its per-pixel value + the per-site clip mask are computed in-shader from
`uCfa` (the mask stays EXACT per-site: the clip decision is a 2-byte tap
against the same thresholds — clip semantics unchanged), and the 9x9
full-res box becomes a 5x5 box on the half-res field (same scene area,
1/4 the bytes). Net ≈ −500 MB → ≈ 480-570 MB/frame → ~8-9 ms fast clock,
~21-25 ms sustained. Quality: the ratio field is low-frequency by
construction (the plan's own §Method words), so the estimate itself is
robust; but the CPU oracle would compute ratios on the half-res demosaic,
all recovery goldens re-baseline, and a small parity-design note is
needed first (the exact half-res filters must be pinned so CPU and GPU
say the same thing about WHAT is downsampled).

Recommended order (all measurements before builds): measure the per-pass
breakdown first (below); apply option 1a (MRT merge — mechanical, saves
20%, parity-safe); then option 1b if ALU cost allows; if sustained
clock still misses the 10 ms gate, propose option 3 to the owner
(option 2's box-bomb removal can be folded into 3's design review).

## Morning measurement plan (measure, then decide)

1. Per-pass breakdown, existing tool: `adb shell setprop
   debug.ritim.rectime 1` already brackets each recovery pass with
   glFinish and logs `rectime recovery 4096x3072: levels(cpu) / pre /
   pyramid / composite / total` (ritim-trace). Run the 4K night clip
   (recovery on), frames 0 and 900, cold start AND after a 5-minute
   warm-up. Numbers wanted: per-pass ms in both thermal states → which
   pass dominates the +16/+46.
2. End-to-end re-pin, existing tools: the GPU_BASELINE procedure
   (`dumpsys gfxinfo` p95; `PlaybackTrace` 13-s shown/gap p50/p95),
   Recovery off vs on, battery temp + ThermalStatus before/after.
   Numbers wanted: recovery-added ms re-pinned on current main at both
   clocks; the sustained-clock gap to 10 ms.
3. Attribute to traffic: with (1)+(2) compute per-pass effective GB/s
   and cross-check the ~1.0 GB derivation; if measured total traffic is
   far below the derivation, the cost is NOT purely bandwidth and
   option 1b's ALU risk is real — say so before building.
4. Option prototypes: any option 2/3 work re-runs the M1 fixtures, the
   hr_measure Neutral-vs-Recovery compare and hr_headroom sweep on the
   night clip first (desktop), then device parity sweep — goldens
   re-baseline = owner decision at the moment of the first prototype
   commit, not after.
5. Owner smoke items: the Recovery button has never been pressed on
   device (on/off, second press restores previous mode, no slider
   change).
