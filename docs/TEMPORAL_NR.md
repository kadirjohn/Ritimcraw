# Temporal NR — round 2 measurements (night mission B10, 2026-11-26)

Round 2 of the temporal NR stage (DN-05..DN-09) adds what round 1 lacked:
a numeric before/after harness with precisely defined metrics, and one
measured improvement behind a DEFAULT-OFF flag. Nothing in the default
path changed — the round-1 golden checksums still pin the output bit for
bit, and a real-clip control run reproduces round-1 numbers exactly.

Branch: `feat-temporal-nr-v2` (commits `4408171` harness, `6ed562c`
flag, `<docs commit>` this file). Not merged; morning owner reviews.

## Environment

```
$ sysctl -n machdep.cpu.brand_string
Apple M3
```

- macOS (Darwin 27.0.0), `/usr/bin/clang++ -std=c++17 -O2`, desktop build
  via `tools/build-desktop.sh` (single-machine, single-run numbers; the
  merge loop itself is row-parallel).
- Gates at commit time: `bash tools/build-desktop.sh` EXIT=0 (all
  fixtures, incl. the temporal golden checksums) and
  `./gradlew --offline :app:assembleDebug :app:testDebugUnitTest --rerun`
  EXIT=0.
- Timing numbers below are desktop reference only (the interesting
  device numbers live in the 2026-11-25 STATUS; this round does not
  change the cost shape).

## Data

### Real clip: cam72.mcraw

`/tmp/claude-501/m0/cam72.mcraw` — 485 MB, 73 frames, 3840x2160 RGGB,
black 64 / white 1023 per-slot, valid Camera2 noise profile
(variance = S*signal + O; S ~ 2.2e-4, O ~ 5e-7 normalized), uniform
33,275 us frame cadence (continuous ~30 fps handheld). Chosen because
it is the only small continuous-cadence clip under `/tmp/claude-501/m0`;
the 4.2 GB cam24b.mcraw is off-limits (owner rule).

The task brief's primary candidates parse but are unusable BY DESIGN,
documented here so nobody re-litigates it:

- `clip57-f0-300.mcraw` (26 MB): 3 frames, 4096x3072, PTS gaps 10.01 s
  and 20.01 s (frames 0 / ~300 of 30 fps — sparse samples).
- `clip24b-f0.mcraw` (10 MB): 2 frames, 4096x3072, PTS gap 2.08 s.

`temporalNeighborEligible` (the bridge contract: |dPTS| <= 1.5x local
frame duration) rejects both clips' pairs, so the merge's only legal
output there is the bit-exact center copy — there is nothing to measure.
Verified with `rawcli info` + the probe run; the night's measurements
moved to cam72 (real continuous footage) plus the synthetic scene below
with a documented seed.

### Synthetic scene (ghosting metric): seed 20261126

3840x2160 RGGB (cam72 geometry), black 64 / white 1023. Plate: fixed
white-noise texture, level 0.30 + 0.20*u (deterministic LCG,
`1664525*s + 1013904223`, seed 20261126). Object: 384x384 hard-edged
square, level 0.85 (a 431 DN step over the plate mean), moving +vx CFA
px/frame along x (frame k's left edge at 1600 + vx*k, y in [700, 1084)).
Per-frame noise: 4-uniform-sum Gaussian, sigma = 8.0 DN (variance exact,
bounded +-3.46 sigma). Clean reference: the noiseless render of the
center frame. Speeds: 1, 2, 4 (sub-grid: the match grid is 8 CFA px),
8 (grid-aligned), 24 px/frame.

## Metric definitions (precise)

All values in DN (raw code values; cam72 range black 64 -> white 1023).
Commands: `build/desktop/temporal_measure noise <clip> <center>
[strength] [staticBlockCap]` and `temporal_measure ghost
[strength] [staticBlockCap]`.

### Noise (real clip)

Decode 7 frames around the center; merge the 5 interior frames with
bridge-contract windows (`frameDurationUs` = median of the two adjacent
PTS deltas, real dPTS per side, per-frame noise profiles, trend-MAFD
unknown = scrub-start contract). Then:

- **static region**: blocks (16x16 proxy px = 128x128 CFA px grid) whose
  zero-motion proxy MAD0 — mean |center - neighbor - globalMeanOffset|
  over the 256 proxy samples, the production de-bias — is at or below
  2.5x the noise model's expected |proxy pixel difference|
  (E|N(0,s)| with s = sqrt(var/2), var = S*signal + O on slot 1; the
  proxy pixel is a mean of 4 samples) on BOTH the (prev, center) and
  (center, next) pairs; within accepted blocks only the plateau cores
  (u in [32, 96) on both axes — outside every 32-px OBMC feather band)
  are counted. MAD0 <= 2.5x E-noise means the content is temporally
  stable to the precision the noise model can certify.
- **sigma_in / sigma_out (per-pixel temporal std)**: for each pixel of
  the static region, the std across the 5 raw center frames / the 5
  merged outputs; report MEDIAN and STD of those per-pixel stds. Caveat
  (stated, not hidden): adjacent merged outputs share raw frames, so
  the 5-output std reads low; it is still the apples-to-apples A/B
  metric and the numbers below are labelled "(naive)".
- **decorrelated estimator**: |NR_a - NR_e| / sqrt(2) over the static
  region, where NR_a is the merge of frames 0,1,2 and NR_e of frames
  4,5,6 — DISJOINT raw sets (the honest residual-noise number; the
  median sits on the DN lattice, so the mean discriminates).
- mean |NR_center - raw_center| over the static region (how far the
 NR moved the frame it denoised).

### Ghosting (synthetic, clean reference)

- **band**: columns inside the object's rows whose CLEAN content differs
  between the center and either neighbor — exactly where a warped sample
  could inject wrong-time content. Fundamental limit: the freshly
  occluded/revealed columns have no aligned source sample in the
  neighbor; a ghost-free filter MUST keep them raw, so band_std can
  never reach the static level there.
- **G_MAF** = mean |NR - clean_center| over the band; **G_bias** =
  |mean (NR - clean_center)| over the band (the systematic ghost term —
  noise averages out over 384 rows, a trailing smear does not);
  **band_std** = std(NR - clean_center) over the band.
- **edge lag**: sub-pixel 50%-crossing position of the row-mean profile
  (200 rows centered in the object) of the leading/trailing center-frame
  edges, NR minus clean, in CFA px (positive = the NR edge leads the
  truth along the motion direction).
- **staticStdOut**: std(NR - clean) over a [64,512) x [200,1900) strip
  the object never reaches — the plain denoise number for scale; the
  raw-injection control is std(C - clean), which measures the scene's
  injected sigma (8.028 DN vs the nominal 8.0: quantization + clipping).

## Current algorithm (characterization, from Temporal.cpp/h)

- Proxies: 1/8 scale (8 CFA px per proxy px, 4 stride-2 samples, one per
  2x2 cell slot — pattern independent); 16x16 proxy px blocks
  (=128x128 CFA px), integer search +-4 proxy px (±32 CFA px).
- Pass 1 argmin (next side may be seeded from the prev side's backward
  field, EPZS-style early-accept at 2x the previous pair's floor); pass 2
  re-decides with the measured floor: zero-bias tie-break (0.5x floor)
  + spatial-median-predictor regularization (0.5x floor/px).
- Global gates per neighbor: mean-luma shift 4%, MAFD scene score
  <= 10 (absolute + trend jump vs the previous pair's score),
  32-bin histogram TV <= 0.30, accept rate >= 25%.
- Local, STATIC-FIRST: zero-motion residual <= 3 sigma (full taper at
  <= 1.5 sigma) AND <= 3x the MEASURED floor (25th-pct winner MAD) ->
  merge at zero motion; else motion path: F/B consistency (1 proxy px)
  + floor gate + confidence = sigma taper on the warped residual x gain
  ratio, min confidence 0.1.
- Merge: O = (C + wP P + wN N)/(1 + wP + wN) per CFA sample; per-neighbor
  cap 0.40 x strength/100, decayed by temporal distance (1/k); OBMC
  feather over the outer 32 px of each block edge, renormalized over
  accepted blocks; per-pixel occlusion guard at 6 sigma zeroes the
  neighbor weight at that pixel only.

## BEFORE/AFTER

Improvement: the round-2 flag `TemporalSettings::staticBlockCap`
(default **0.40**, identical to the legacy cap -> default path
bit-identical). Full-confidence STATIC blocks (static path at zero
motion, residual at/below the 1.5-sigma full-taper point — both the
sigma taper and the measured floor gate passed with margin) may take a
higher per-neighbor cap; every other block keeps 0.40. Researched
band: 0.35-0.5; the pilot value is 0.50 (the band's top). The flag
moves a WEIGHT, never an admit decision; it is not wired to the bridge
or UI (morning owner).

### Noise, cam72 (real), strength 100

| metric (DN) | BEFORE (0.40) | AFTER (0.50) | delta |
|---|---|---|---|
| center 36: sigma_in median (5 raws) | 4.062 | 4.062 | unchanged (same frames) |
| center 36: sigma_out median (naive) | 2.302 | 2.168 | −5.8% |
| center 36: var ratio out/in (naive) | 0.3212 | 0.2848 | −11.3% |
| center 36: decorr mean (disjoint) | 3.486 | 3.415 | −2.0% |
| center 36: mean abs(NR − raw) | 1.804 | 2.037 | expected: stronger pull toward 3-frame mean |
| center 20: sigma_out median (naive) | 2.408 | 2.302 | −4.4% |
| center 20: var ratio out/in (naive) | 0.3353 | 0.3064 | −8.4% |
| center 20: decorr mean | 3.516 | 3.448 | −1.9% |
| static region | 261/480 blocks, 12.9% (c36); 238/480, 11.8% (c20) | same | flag-independent |
| accept rate / eligibility per window | 0.71-0.82, all eligible | same | decisions unchanged |
| merge ms/frame (desktop M3) | ~25-27 | ~25-27 | no cost (one compare/block) |

Decorrelated medians sit on the DN lattice (median |Δ| = 3 DN) in BOTH
runs — the mean discriminates; both readings agree the residual drops ~2%.

Theory check: static plateau with both neighbors at 0.40 -> variance
(1+2*0.16)/1.8² = 0.407 of center-only; at 0.50 -> 1.5/4 = 0.375; ratio
0.921 (−7.9% var). Measured −11.3% / −8.4% — consistent (feathered
boundaries and the 6-sigma guard explain the spread).

### Ghost, synthetic moving edge (seed 20261126), strength 100

| speed px/frame | accept P/N BEFORE | G_bias DN B→A | edge lag lead/trail B→A (px) | band std DN B→A | static std out DN B→A |
|---|---|---|---|---|---|
| 1 (sub-grid) | .992/1.000 → identical | 0.212 → 0.206 | +0.002/−0.001 → identical | 6.865 → 6.738 | 5.173 → 4.927 |
| 2 (sub-grid) | .992/.992 → identical | 0.104 → 0.109 | +0.001/−0.000 → identical | 7.490 → 7.416 | 5.173 → 4.927 |
| 4 (sub-grid) | .983/1.000 → identical | 0.083 → 0.085 | +0.002/−0.000 → identical | 7.096 → 7.013 | 5.173 → 4.927 |
| 8 (grid) | .983/.983 → identical | 0.040 → 0.040 | +0.001/−0.001 → identical | 7.951 → 7.951 | 5.173 → 4.927 |
| 24 (grid) | .985/.983 → identical | 0.017 → 0.016 | +0.000/−0.002 → identical | 7.934 → 7.908 | 5.173 → 4.927 |

Read: ghost metrics are noise-level in scale (G_bias <= 0.21 DN against
a 431 DN content step; edge lags <= 0.002 px) and UNCHANGED by the
boost — the changed/occluded blocks stay rejected (floor gate, not
weight), the static plate merges 4.8% deeper (5.173 -> 4.927 DN, the
expected 0.40->0.50 static gain), and the band interiors keep their
round-1 alignment behavior. Accept rates identical: the flag moved
weights only.

### Default-off proof (golden rule)

- Golden checksums (test_temporal, 2 scenarios, serial+parallel) pass
  UNCHANGED: 9543035966300208997 / 12554931306165923847.
- Unit test pins explicit-0.40 == default-constructor bit-for-bit.
- Real-clip control run (cam72 c36, default settings, post-flag binary):
  sigma_out 2.302, decorr 2.121/mean 3.486, mean abs 1.804 DN — the
  BEFORE table exactly.

## Weakest axis (how it was picked)

Measured axes: ghosting (excellent — noise-level), static noise (at the
flat-cap optimum: 0.32 var vs the 0.407 bound, aided by the 6-sigma
guard's outlier truncation), coverage (accept 71-82% — the rejects are
the safety gates doing their job), near-motion band noise (structurally
unfilterable at the content-change columns; band_std == raw there is
the ghost-free answer). The one axis with measurable, safe headroom was
the STATIC weight cap: the 0.35-0.5 band's top was unexplored because
the flat 0.40 covered everything. That is what round 2 ships as the
flagged pilot.

## Reproducing

```
bash tools/build-desktop.sh   # builds build/desktop/temporal_measure
build/desktop/temporal_measure noise /tmp/claude-501/m0/cam72.mcraw 36           # BEFORE
build/desktop/temporal_measure noise /tmp/claude-501/m0/cam72.mcraw 36 100 0.50  # AFTER
build/desktop/temporal_measure ghost            # BEFORE
build/desktop/temporal_measure ghost 100 0.50  # AFTER
```

## Sahibe sor (owner decisions, NOT taken tonight)

1. Flip `staticBlockCap` to 0.50 for the pilot on device (preview +
   export path through the bridge; needs a device-quality check — the
   desktop numbers say ~4-6% sigma on static regions, no ghost cost).
   Not wired: the bridge passes TemporalSettings defaults.
2. Beyond the researched band (>0.50) is untested territory — equal-
   noise 3-frame optimum would be w=1.0; the measured gates (1.5-sigma
   static + 3x floor + 6-sigma per-pixel) make a case for it, but the
   band exists for safety reasons; that analysis is above.
3. Window depth (±1 frame only, ring capacity 4) is the next structural
   noise lever — costs neighbor decode + memory; a morning project.
4. Sub-grid motion refinement (half/quarter proxy px = 4/2 CFA px, even
   = slot-preserving) for MORE NR near sub-grid drift on textured
   blocks — the cam72 accept rate (71-82%) is where those rejections
   live. Cost risk on device (match loop is the status-quo cost line);
   round-1 STATUS morning items list it.

## Morning items

- Review + flip (or reject) the flag; decide the pilot value.
- The harness is a permanent evidence tool (`tools: temporal nr
  measurement harness`) — any future temporal change gets its numbers
  from the same commands.
