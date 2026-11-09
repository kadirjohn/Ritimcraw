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
)
FLAGS=(-std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter -fvisibility=hidden)

mkdir -p "$BUILD"

echo "== building motioncam decoder =="
DECODED_OBJS=()
for src in Decoder.cpp RawData.cpp RawData_Legacy.cpp; do
  obj="$BUILD/decoder_${src%.cpp}.o"
  "$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" -c "$DECODER_SRC/lib/$src" -o "$obj"
  DECODED_OBJS+=("$obj")
done

echo "== building rawedit core =="
CORE_OBJS=()
for src in Source.cpp Develop.cpp; do
  obj="$BUILD/core_${src%.cpp}.o"
  "$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" -c "$ROOT/core/src/$src" -o "$obj"
  CORE_OBJS+=("$obj")
done

echo "== building rawcli =="
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/cli/main.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/rawcli"

echo "== building tests =="
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_source.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_source"
"$CXX" "${FLAGS[@]}" "${INCLUDES[@]}" "$ROOT/core/tests/test_develop.cpp" \
  "${CORE_OBJS[@]}" "${DECODED_OBJS[@]}" -o "$BUILD/test_develop"

if [[ "${1:-}" == "--no-tests" ]]; then
  echo "== done (tests skipped): $BUILD/rawcli =="
  exit 0
fi

echo "== running test_source =="
"$BUILD/test_source"
echo "== running test_develop =="
"$BUILD/test_develop"

echo
echo "ALL TESTS DONE. Binaries in $BUILD:"
ls -la "$BUILD" | grep -E "rawcli|test_"
