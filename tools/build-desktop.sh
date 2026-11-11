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
for src in Source.cpp Develop.cpp Transfer.cpp DngWriter.cpp Gyro.cpp Orientation.cpp OrientationEstimate.cpp Thumbnail.cpp Lut.cpp Sync.cpp; do
  obj="$BUILD/core_${src%.cpp}.o"
  "$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" -c "$ROOT/core/src/$src" -o "$obj"
  CORE_OBJS+=("$obj")
done
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

echo
echo "ALL TESTS DONE. Binaries in $BUILD:"
ls -la "$BUILD" | grep -E "rawcli|test_"
