#!/bin/bash
# Desktop build for the rawedit core + CLI + tests (Sprint 1, Tasks 1-2).
#
# No cmake/ninja on PATH tonight, so this is a plain clang++ build.
# The core also has core/CMakeLists.txt for the later NDK build; this script
# does NOT use it.
#
# Usage: bash tools/build-desktop.sh [--no-tests]
# Set RAWEDIT_REALCLIP=1 to also run the real-clip tests (2 GB fixtures).

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CXX="${CXX:-/usr/bin/clang++}"
BUILD="$ROOT/build/desktop"

DECODER_SRC="$ROOT/third_party/motioncam-decoder"
INCLUDES=(
  -I"$ROOT/core/include"
  -I"$DECODER_SRC/lib/include"
  -I"$DECODER_SRC/thirdparty"
  -I"$ROOT/third_party/doctest-2.4.12"
  -I"$ROOT/third_party/vqf"
)
FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)

mkdir -p "$BUILD"

echo "== building vqf (vendored, MIT) =="
# Orientation filter; linked into the binaries that need it
# (OrientationEstimate.cpp consumers, see test_orientation_estimate below).
"$CXX" "${FLAGS[@]}" -c "$ROOT/third_party/vqf/vqf.cpp" -o "$BUILD/third_vqf.o"
VQF_OBJS=("$BUILD/third_vqf.o")

echo "== building motioncam decoder =="
DECODED_OBJS=()
for src in Decoder.cpp RawData.cpp RawData_Legacy.cpp; do
  obj="$BUILD/decoder_${src%.cpp}.o"
  "$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" -c "$DECODER_SRC/lib/$src" -o "$obj"
  DECODED_OBJS+=("$obj")
done

echo "== building rawedit core =="
CORE_OBJS=()
for src in Source.cpp Develop.cpp Transfer.cpp DngWriter.cpp Gyro.cpp Orientation.cpp OrientationEstimate.cpp Thumbnail.cpp Lut.cpp Color.cpp ColorModel.cpp OpenDrt.cpp Sync.cpp OpticalSync.cpp Temporal.cpp Stabilization.cpp Warp.cpp RawStats.cpp Parallel.cpp ImageStab.cpp ImageStabSource.cpp; do
  obj="$BUILD/core_${src%.cpp}.o"
  "$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" -c "$ROOT/core/src/$src" -o "$obj"
  CORE_OBJS+=("$obj")
done
# ImageStabPlan.cpp: the plan is a pure double-precision function that must
# round identically everywhere (preview == export == timeline), so this TU
# also opts out of FMA contraction (Yuv.cpp precedent).
"$CXX" "${FLAGS[@]}" -ffp-contract=off "${INCLUDES[@]}" -c "$ROOT/core/src/ImageStabPlan.cpp" -o "$BUILD/core_ImageStabPlan.o"
CORE_OBJS+=("$BUILD/core_ImageStabPlan.o")
# Resample.cpp (E5-B, unwired): the interpolator's lerp
# (a + (b - a) * frac) is what filmcraft's f32 op sequence rounds; the
# default fp-contract collapses it into one FMA (measured: clang arm64
# emits two fmadd with the default, zero with the opt-out), one rounding
# where the crate has three. The crate-vector tests pin "the numbers
# filmcraft runs", and the one-sample lag must round the same on
# desktop, phone and any later JNI wiring (ImageStabPlan precedent).
"$CXX" "${FLAGS[@]}" -ffp-contract=off "${INCLUDES[@]}" -c "$ROOT/core/src/Resample.cpp" -o "$BUILD/core_Resample.o"
CORE_OBJS+=("$BUILD/core_Resample.o")
# Yuv.cpp is the byte-exact twin of export/YuvConverter.kt: its chroma
# multiply-add chain must round exactly where the Kotlin float ops round,
# so this TU alone opts out of FMA contraction (-ffp-contract=off).
# Everything else keeps the default (FMA where the target allows).
"$CXX" "${FLAGS[@]}" -ffp-contract=off "${INCLUDES[@]}" -c "$ROOT/core/src/Yuv.cpp" -o "$BUILD/core_Yuv.o"
CORE_OBJS+=("$BUILD/core_Yuv.o")
# OrientationEstimate.cpp (in the core list) links against vqf, so every
# core consumer needs it — mirrors core/CMakeLists.txt's
# target_link_libraries(rawedit PUBLIC ... vqf).
CORE_OBJS+=("${VQF_OBJS[@]}")

echo "== building rawcli =="
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/cli/main.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/rawcli"

echo "== building tests =="
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_source.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_source"
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_develop.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_develop"
# test_noise: denoise stages (DN-01..08) on synthetic fixtures — no cwd needs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_noise.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_noise"
# test_transfer runs from the repo root so it finds the vendor cube LUT.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_transfer.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_transfer"
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_dng.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_dng"
# test_lut runs from the repo root so it finds the vendor cube fixture.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_lut.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_lut"
# test_gyro runs from the repo root so it finds the STEPS/media/test fixture.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_gyro.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_gyro"
# test_orientation: axis remap (Phase 2 deliverable 1); no cwd needs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_orientation.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_orientation"
# test_orientation_estimate: estimation (Phase 2 deliverables 2-5); runs
# from the repo root so it finds the STEPS/media/test fixtures.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_orientation_estimate.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_orientation_estimate"
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_thumbnail.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_thumbnail"
# test_sync: frame↔IMU sync (Phase 3); runs from the repo root so it finds
# the STEPS/media/test fixtures.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_sync.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_sync"
# test_temporal's real-clip case is gated behind RAWEDIT_REALCLIP=1 (4K
# fixture); the synthetic suite always runs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_temporal.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_temporal"
# test_stabilization: Phase 4 smoothing/homography/crop/clip API; runs
# from the repo root so it finds the STEPS/media/test fixtures.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_stabilization.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_stabilization"
# Stabilization measurement tools (round 3, not tests — run by hand):
#   stab_measure <log.gcsv> [strength] [zoomLimit]  — shake/crop numbers
#     (env: STAB_LEGACY, STAB_GYRO_ONLY, STAB_OFFSET_MS, STAB_DUMP)
#   sync_measure <clip.mcraw> <log.gcsv> [focalMm]  — anchor vs optical sync
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/stab_measure.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/stab_measure"
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/sync_measure.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/sync_measure"
#   bp_measure <clip.mcraw> [...] — A6 black-point k sweep (owner request
#     2026-11-23, guide A6): % fully black + mean luma for candidate k on
#     real fixtures; run by hand from the repo root, not a test.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/bp_measure.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/bp_measure"
# image_stab_measure: image-stab numbers (S1..S3): stage timings, analysis of
# a real clip, acceleration/jerk + zoom-rate A/B plan sweep, raw-vs-developed
# luma check. Run by hand: `image_stab_measure bench|analyze|plan|lumacheck`.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/image_stab_measure.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/image_stab_measure"
# test_warp: Phase 5 CPU warp (warpFrame); runs from the repo root so
# it finds the STEPS/media/test fixtures (the cost-evidence case).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_warp.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_warp"
# test_chain_slots: the GPU chain's pure slot planner (header-only, no GL;
# 2026-11-23 warp-slot regression).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_chain_slots.cpp" \
  -o "$BUILD/test_chain_slots"
# test_parity_diag: the A5 parity-diagnosis histogram planner
# (header-only, no GL; owner request 2026-11-23, guide A5 step 1 — the
# fragment parity harness's mismatch-population buckets and logcat line
# shapes run on desktop, no CORE_OBJS for the same reason the chain
# planner's test needs none).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_parity_diag.cpp" \
  -o "$BUILD/test_parity_diag"
# test_stats_ring: the GPU preview stats readback 2-slot fence-ring planner
# (header-only, no GL; 2026-11-23 owner request, guide A1 — the async
# readback ring's decisions run on desktop, no CORE_OBJS for the same
# reason the chain planner's test needs none).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_stats_ring.cpp" \
  -o "$BUILD/test_stats_ring"
# test_gpu_crop_map: the GPU window pass's crop/straighten frame plan
# (header-only, no GL; 2026-11-23 owner request, guide A2 — the cropped
# output dims, the auto-zoom and the cos/sin pair are pure, so their
# invariants run on desktop, no CORE_OBJS for the same reason the ring
# planner's test needs none).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_gpu_crop_map.cpp" \
  -o "$BUILD/test_gpu_crop_map"
# test_optical_sync: GY-04 optical sync refinement; the synthetic
# ground-truth suite always runs, the real-clip evidence case is
# RAWEDIT_REALCLIP-gated (2 GB fixtures). Runs from the repo root so it
# finds the STEPS/media/test fixtures.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_optical_sync.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_optical_sync"
# test_image_stab: image-based stabilization core (S1 pairwise motion;
# synthetic ground truth only, no fixtures, no cwd needs).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_image_stab.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_image_stab"
# test_rawstats: RAW histogram stats (feature N6) on synthetic CFA
# fixtures — no cwd needs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_rawstats.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_rawstats"
# test_yuv: byte-exact RGB888->I420 contract with export/YuvConverter.kt
# (NEON path runs on arm64 hosts, so NEON-vs-scalar is proven here too).
# -ffp-contract=off matches the Yuv.cpp build: the reference formulas in
# the test must round exactly like the Kotlin float ops.
"$CXX" "${FLAGS[@]}" -ffp-contract=off "${INCLUDES[@]}" "$ROOT/core/tests/test_yuv.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_yuv"
# test_color: managed LUT color model (gamuts, transfers, CAT, compiled
# bindings, evaluateManagedLut) on synthetic fixtures — no cwd needs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_color.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_color"
# test_color_model: color model v2 (F1-1) — DI/DWG glue, the K1 gate
# (709->DWG->709 round trip, develop keeps negative values) and the CPU node
# reference. Synthetic only, no cwd needs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_color_model.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_color_model"
# test_opendrt: OpenDRT v1.1.0 C++ reference (F1-4) against the Klipp
# reference vectors in core/tests/data (runs from the repo root).
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_opendrt.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_opendrt"
# test_resample: E5-B streaming resampler + stereo/mono downmix core
# (unwired filmcraft audio-dsp port) — synthetic only, no cwd needs.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_resample.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_resample"
# bench_develop: M1 developCpu serial-vs-parallel measurement on the real
# clip. The measured case is RAWEDIT_REALCLIP-gated (2 GB fixture, decode
# is slow); without the gate the doctest prints a SKIP line. Runs from the
# repo root so the default RAWEDIT_CLIP path resolves.
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/bench_develop.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/bench_develop"

if [[ "${1:-}" == "--no-tests" ]]; then
  echo "== done (tests skipped): $BUILD/rawcli =="
  exit 0
fi

echo "== running test_source =="
"$BUILD/test_source"
echo "== running test_develop =="
"$BUILD/test_develop"
echo "== running test_noise =="
"$BUILD/test_noise"
echo "== running test_transfer =="
(cd "$ROOT" && "$BUILD/test_transfer")
echo "== running test_dng =="
"$BUILD/test_dng"
echo "== running test_lut =="
(cd "$ROOT" && "$BUILD/test_lut")
echo "== running test_gyro =="
(cd "$ROOT" && "$BUILD/test_gyro")
echo "== running test_orientation =="
"$BUILD/test_orientation"
echo "== running test_orientation_estimate =="
(cd "$ROOT" && "$BUILD/test_orientation_estimate")
echo "== running test_thumbnail =="
(cd "$ROOT" && "$BUILD/test_thumbnail")
echo "== running test_sync =="
(cd "$ROOT" && "$BUILD/test_sync")
echo "== running test_temporal =="
"$BUILD/test_temporal"
echo "== running test_stabilization =="
(cd "$ROOT" && "$BUILD/test_stabilization")
echo "== running test_warp =="
(cd "$ROOT" && "$BUILD/test_warp")
echo "== running test_chain_slots =="
"$BUILD/test_chain_slots"
echo "== running test_parity_diag =="
"$BUILD/test_parity_diag"
echo "== running test_stats_ring =="
"$BUILD/test_stats_ring"
echo "== running test_gpu_crop_map =="
"$BUILD/test_gpu_crop_map"
echo "== running test_optical_sync =="
(cd "$ROOT" && "$BUILD/test_optical_sync")
echo "== running test_image_stab =="
"$BUILD/test_image_stab"
echo "== running test_rawstats =="
"$BUILD/test_rawstats"
echo "== running test_yuv =="
"$BUILD/test_yuv"
echo "== running test_color =="
"$BUILD/test_color"
echo "== running test_color_model =="
"$BUILD/test_color_model"
echo "== running test_opendrt =="
(cd "$ROOT" && "$BUILD/test_opendrt")
echo "== running test_resample =="
"$BUILD/test_resample"
echo "== running bench_develop (real-clip case needs RAWEDIT_REALCLIP=1) =="
(cd "$ROOT" && "$BUILD/bench_develop")

echo
echo "ALL TESTS DONE. Binaries in $BUILD:"
ls -la "$BUILD" | grep -E "rawcli|test_"
