# rawedit core — CMake build for the later NDK integration (Task 3).
# The desktop build (tools/build-desktop.sh) does NOT use this file; it is
# here so app/src/main/cpp/CMakeLists.txt can add this directory and link
# `rawedit` from the JNI bridge.

cmake_minimum_required(VERSION 3.22.1)
project(rawedit CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Vendored motioncam-decoder (Apache-2.0, commit b931ec0), built from the
# three library sources; BUILD_TESTING stays OFF for the vendored tree.
set(MOTIONCAM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/motioncam-decoder)
add_library(motioncam_decoder STATIC
    ${MOTIONCAM_DIR}/lib/Decoder.cpp
    ${MOTIONCAM_DIR}/lib/RawData.cpp
    ${MOTIONCAM_DIR}/lib/RawData_Legacy.cpp)
target_include_directories(motioncam_decoder PUBLIC
    ${MOTIONCAM_DIR}/lib/include
    ${MOTIONCAM_DIR}/thirdparty)
set_property(TARGET motioncam_decoder PROPERTY POSITION_INDEPENDENT_CODE ON)

# Vendored VQF orientation filter (MIT, commit 86ba56b), plain C++ version
# (vqf/cpp/) verbatim; consumer is core/src/OrientationEstimate.cpp
# (Phase 2 orientation estimation) and its tests. Registered as its own
# static lib so the app can link it alongside rawedit when the gyro path
# moves on-device.
set(VQF_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/vqf)
add_library(vqf STATIC ${VQF_DIR}/vqf.cpp)
target_include_directories(vqf PUBLIC ${VQF_DIR})
set_property(TARGET vqf PROPERTY POSITION_INDEPENDENT_CODE ON)

# rawedit core library.
# Thumbnail.cpp + Transfer.cpp are linked by the app's preview path
# (jni_bridge.cpp: developThumbnail + linearToRec709); DngWriter.cpp is
# linked by the app's DNG export path (jni_bridge.cpp: writeCfaDng);
# RawStats.cpp is linked by the app's RAW histogram scope path
# (jni_bridge.cpp: nativeRawHistogram → rawHistogramStats).
# Gyro.cpp stays desktop-only until the app calls it; Orientation.cpp
# (Phase 2 axis remap, header-only dep on Gyro.h) and
# OrientationEstimate.cpp (Phase 2 estimation, links vqf) are registered
# now so the lib compiles as the gyro pipeline grows. Sync.cpp (Phase 3
# frame↔IMU clock bridge) is pure arithmetic — no decoder dependency —
# but rides in the same lib so the Phase 4 JNI bridge reaches it.
add_library(rawedit STATIC
    src/Source.cpp
    src/Develop.cpp
    src/Thumbnail.cpp
    src/Transfer.cpp
    src/Lut.cpp
    src/Color.cpp
    src/ColorModel.cpp
    src/OpenDrt.cpp
    src/DngWriter.cpp
    src/Gyro.cpp
    src/Orientation.cpp
    src/OrientationEstimate.cpp
    src/Sync.cpp
    src/OpticalSync.cpp
    src/Temporal.cpp
    src/Stabilization.cpp
    src/Warp.cpp
    src/RawStats.cpp
    src/Parallel.cpp
    src/ImageStab.cpp
    src/ImageStabSource.cpp
    src/ImageStabPlan.cpp
    src/Loudness.cpp
    src/Yuv.cpp)
# Yuv.cpp is the byte-exact twin of export/YuvConverter.kt: its chroma
# multiply-add chain must round exactly where the Kotlin float ops round,
# so this TU alone opts out of FMA contraction (-ffp-contract=off).
set_source_files_properties(src/Yuv.cpp PROPERTIES
    COMPILE_OPTIONS "-ffp-contract=off")
# ImageStabPlan.cpp: a pure double-precision plan that must round the same
# on every target (preview == export == timeline); same opt-out.
set_source_files_properties(src/ImageStabPlan.cpp PROPERTIES
    COMPILE_OPTIONS "-ffp-contract=off")
target_include_directories(rawedit PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(rawedit PUBLIC motioncam_decoder vqf)
set_property(TARGET rawedit PROPERTY POSITION_INDEPENDENT_CODE ON)

# Desktop tests + CLI, only when built standalone (not from the NDK tree).
if(PROJECT_NAME STREQUAL "rawedit" AND RAWEDIT_TESTS)
    set(DOCTEST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../third_party/doctest-2.4.12)

    add_executable(test_source tests/test_source.cpp)
    target_include_directories(test_source PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_source PRIVATE rawedit)

    add_executable(test_develop tests/test_develop.cpp)
    target_include_directories(test_develop PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_develop PRIVATE rawedit)

    add_executable(test_noise tests/test_noise.cpp)
    target_include_directories(test_noise PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_noise PRIVATE rawedit)
    add_executable(test_temporal tests/test_temporal.cpp)
    target_include_directories(test_temporal PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_temporal PRIVATE rawedit)
    add_executable(test_stabilization tests/test_stabilization.cpp)
    target_include_directories(test_stabilization PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_stabilization PRIVATE rawedit)
    add_executable(test_warp tests/test_warp.cpp)
    target_include_directories(test_warp PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_warp PRIVATE rawedit)
    # test_optical_sync: GY-04 optical sync refinement; synthetic suites
    # always run, the real-clip evidence case is RAWEDIT_REALCLIP-gated
    # (mirrors tools/build-desktop.sh).
    add_executable(test_optical_sync tests/test_optical_sync.cpp)
    target_include_directories(test_optical_sync PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_optical_sync PRIVATE rawedit)
    # test_image_stab: image-based stabilization core (synthetic only).
    add_executable(test_image_stab tests/test_image_stab.cpp)
    target_include_directories(test_image_stab PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_image_stab PRIVATE rawedit)
    # test_yuv: byte-exact RGB888->I420 contract with export/YuvConverter.kt
    # (on arm64 hosts the NEON path runs, so NEON-vs-scalar is proven too).
    # -ffp-contract=off matches the Yuv.cpp build: the reference formulas in
    # the test must round exactly like the Kotlin float ops.
    add_executable(test_yuv tests/test_yuv.cpp)
    target_include_directories(test_yuv PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_yuv PRIVATE rawedit)
    target_compile_options(test_yuv PRIVATE -ffp-contract=off)
    # test_color: managed LUT color model (gamuts, transfers, Bradford CAT,
    # compiled bindings, evaluateManagedLut) — synthetic fixtures only.
    add_executable(test_color tests/test_color.cpp)
    target_include_directories(test_color PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_color PRIVATE rawedit)
    # test_color_model: color model v2 (F1-1), synthetic only.
    add_executable(test_color_model tests/test_color_model.cpp)
    target_include_directories(test_color_model PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_color_model PRIVATE rawedit)
    # test_opendrt: OpenDRT C++ reference (F1-4); reads core/tests/data, so
    # the ctest entry runs from the repo root.
    add_executable(test_opendrt tests/test_opendrt.cpp)
    target_include_directories(test_opendrt PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_opendrt PRIVATE rawedit)
    # test_loudness: BS.1770-4 meter + true-peak port (E5-A first half);
    # filmcraft audio-dsp test vectors, synthetic signals only, no cwd needs.
    add_executable(test_loudness tests/test_loudness.cpp)
    target_include_directories(test_loudness PRIVATE ${DOCTEST_DIR})
    target_link_libraries(test_loudness PRIVATE rawedit)
    # bench_develop: M1 developCpu serial-vs-parallel measurement on the
    # real clip (RAWEDIT_REALCLIP-gated, mirrors tools/build-desktop.sh);
    # asserts byte-exactness, reports (never asserts) the speedup.
    add_executable(bench_develop tests/bench_develop.cpp)
    target_include_directories(bench_develop PRIVATE ${DOCTEST_DIR})
    target_link_libraries(bench_develop PRIVATE rawedit)

    add_executable(rawcli ../cli/main.cpp)
    target_link_libraries(rawcli PRIVATE rawedit)

    enable_testing()
    add_test(NAME source COMMAND test_source)
    add_test(NAME develop COMMAND test_develop)
    add_test(NAME noise COMMAND test_noise)
    add_test(NAME temporal COMMAND test_temporal)
    add_test(NAME stabilization COMMAND test_stabilization)
    add_test(NAME warp COMMAND test_warp)
    add_test(NAME optical_sync COMMAND test_optical_sync)
    add_test(NAME image_stab COMMAND test_image_stab)
    add_test(NAME yuv COMMAND test_yuv)
    add_test(NAME color COMMAND test_color)
    add_test(NAME color_model COMMAND test_color_model)
    add_test(NAME opendrt COMMAND test_opendrt WORKING_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/..)
    add_test(NAME loudness COMMAND test_loudness)
    add_test(NAME bench_develop COMMAND bench_develop)
endif()
