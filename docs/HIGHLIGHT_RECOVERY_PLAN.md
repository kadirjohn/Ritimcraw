# Highlight Recovery plan (draft, awaiting owner approval)

Status: PLAN ONLY. No engine code is written until the owner approves this.

## Why

The shortcut button I shipped only set the Highlights tone slider to -70 and was
removed. Highlights (stage 15) remaps display-referred tones after the fact; it
cannot bring back information a clipped sensor channel never stored.

Stage 3 today ("Neutral", `grade.highlightReconstruction`) is the opposite of a
recovery: a channel clipped at white is CAPPED DOWN to the level the unclipped
channels of its 2x2 cell carry. That avoids magenta but throws the clipped
channel's headroom away; nothing above 1.0 is ever produced.

Owner evidence (2026-11-25 screenshot, clip 261124_144344, ~00:02, Gain +1.1 EV,
Highlights -100): a lilac streak remains in the bright window area. A
screenshot cannot prove the cause; step M0 below measures it on the real frame.

## Behaviour

A Develop mode with three values: Off / Neutral / Recovery.

- Off = today's `highlightReconstruction=false`. Neutral = today's stage 3.
- Recovery = real re-estimation (below); may produce values above 1.0.
- The button toggles Recovery on/off. A second press only turns the mode off
  (back to the previous mode); it never touches the Highlights slider.
- Old projects keep their image: absent field -> mapped from the existing bool
  (true -> Neutral, false -> Off). Recovery is only entered by the user.
- MCRAW only (encoded clips have no CFA; the ISP already clipped them), same
  gate as `showRawStageToggles`.

## Method (we write the code ourselves)

Work in the demosaiced, white-balanced camera RGB (after stage 5 gains, before
the camera matrix), where neutral means R=G=B so channel ratios behave like
chrominance. Stage 3's CFA pass is replaced, not stacked, when mode = Recovery.

1. Clip mask per channel: a pixel is clipped in channel c when any CFA site that
   feeds it is >= 1.0 (carry a 0/1 mask through the same bilinear demosaic).
2. One or two channels clipped (the common case): the clipped channel is
   re-estimated from an unclipped channel x a local ratio. The ratio comes from
   a normalized blur of unclipped neighbours only (sum(ratio*w)/sum(w) with
   w = unclipped weight), so it is the ratio the surrounding unclipped pixels
   have. This is the "inpaint opposed" family (darktable) in a GPU-friendly
   separable form.
3. Large clipped regions: the neighbourhood has no unclipped pixels at the
   centre. Use a coarse-to-fine pyramid (blur at 1/4 and 1/16 res, fall back
   to the coarser ratio where the fine weight is ~0). This is the cheap
   stand-in for RawTherapee's iterative "color propagation".
4. All three channels clipped: no information. Keep the stage-3 cap behaviour
   there (neutral), never invent luminance.
5. Output is allowed above 1.0; the existing exposure/Highlights/tonemap stages
   then decide what survives, so pulling Gain or Highlights down reveals the
   recovered tone instead of flat white.

Open choice for the owner: ratio source = unclipped-neighbour blur (above) or
cell-local only (cheaper, weaker on large areas). Recommendation: blur + pyramid.

## CPU reference and parity

- CPU `Develop.cpp` implements the method first; it is the oracle.
- GPU shader/passes match it under the usual bounds only: 0.01% mismatch,
  <= 1 LSB. No loosening.
- New parity cases are appended LAST in the fixture list (the seed depends on
  case index). Fixtures: single-channel clip, two-channel clip, large clipped
  disc with a gradient border, all-clipped patch, no-clip image (must be bit
  identical to Neutral).
- Parcel: slot 26 today is a strict 0/1 bool. Needs either a new slot or a
  widened 0/1/2 with the validator, Kotlin and C++ updated together (the JNI
  appends 6 spec floats after the 77-float pack; the change must keep that).
- Golden hashes for existing modes must NOT change. Neutral and Off stay
  bit-identical to today.

## GPU cost

Target: Recovery adds <= 10 ms per frame at 4K30 on the Xiaomi 25010PN30G,
measured, not estimated. The extra passes are: mask+ratio build, two separable
blur levels at reduced resolution, composite. Reduced-resolution blurs are the
cost lever (the ratio field is low-frequency by construction). Measure with the
GPU_BASELINE procedure (`dumpsys gfxinfo` p95 for preview, per-pass GL timer
queries for export) before and after, Recovery off vs on. If the target is
missed: halve the blur resolution first, then drop the coarse level.

## Measurement (what proves it recovers)

- M0 (real frame): clipped-site fraction per channel on the owner's frame via
  RawStats, to confirm the lilac area is actually channel-clipped.
- M1 (synthetic ground truth): take an unclipped high-range colour scene, clip it
  at white, recover, and report per-channel error vs the original inside the
  clipped region, and hue error. Compare Off, Neutral, Recovery. Proposed
  acceptance: Recovery hue error in single-channel-clip areas clearly below
  Neutral's; numbers to come from the command, not asserted here.
- M2: after Recovery, Gain -2 EV on the real frame must show tone in the window
  where Neutral shows flat white (screenshots, same clip, identity verified).
- M3: CPU/GPU parity as above; frame-time cost as above.

## Order of work (after approval)

1. M0 + M1 harness (desktop, no device).
2. CPU method + tests. 3. Mode field/parcel/validator. 4. GPU passes + parity
on device. 5. Button + en/tr strings + Develop UI. 6. Frame-time measurement.
