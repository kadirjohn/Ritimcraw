# JNI bridge for librawedit_jni.so.
# Adds ../../../../core (which builds the vendored motioncam-decoder as a
# static lib) and links rawedit into the shared library.

cmake_minimum_required(VERSION 3.22.1)
project(rawedit_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Always optimize the native pipeline, including Debug builds (owner finding
# 2026-11-10: AGP passes NO -O flag for the debug native build type — its
# CMAKE_CXX_FLAGS_DEBUG is empty in the cache — so clang defaults to -O0 and
# the develop/demosaic loops ran 5-10x slower than they should (device
# measure: 97-158 ms cached slider renders, ~1 fps playback at 720 px; the
# same Mac-side real-clip test: ~314 ms/frame at -O0 vs ~9.4 ms at -O2).
# Appended AFTER any existing flags so it wins over the empty debug set;
# debug info is unaffected (-O2 does not remove -g). The flag is set BEFORE
# add_subdirectory so the core and the vendored decoder targets inherit it.
add_compile_options(-O2)

# core/ builds rawedit + motioncam_decoder from third_party/.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../core ${CMAKE_CURRENT_BINARY_DIR}/rawedit-core)

add_library(rawedit_jni SHARED jni_bridge.cpp)
# gles/ is the deliverable-C GPU groundwork: the EGL context (grafika
# EglCore topology), the capability probe and the single-frame demosaic
# parity harness, reached through the bridge's stateless probes. It is
# Android-only (EGL/GLES headers exist only in the NDK) — the desktop
# build never compiles this target.
target_sources(rawedit_jni PRIVATE gles/GlesPipeline.cpp)
# log is the Android log library: the bridge's debug-gated "ritim-stab"
# stabilization measurement line (__android_log_print, audit fix
# 2026-11-12) needs it. Release builds never call it (the flag stays
# false), but the symbol must resolve in both build types. EGL + GLESv3
# carry the deliverable-C probes (EglSession + the compute dispatch).
target_link_libraries(rawedit_jni PRIVATE rawedit log EGL GLESv3)
