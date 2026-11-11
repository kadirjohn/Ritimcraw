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
# linked by the app's DNG export path (jni_bridge.cpp: writeCfaDng).
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
    src/DngWriter.cpp
    src/Gyro.cpp
    src/Orientation.cpp
    src/OrientationEstimate.cpp
    src/Sync.cpp
    src/Temporal.cpp)
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

    add_executable(rawcli ../cli/main.cpp)
    target_link_libraries(rawcli PRIVATE rawedit)

    enable_testing()
    add_test(NAME source COMMAND test_source)
    add_test(NAME develop COMMAND test_develop)
    add_test(NAME noise COMMAND test_noise)
    add_test(NAME temporal COMMAND test_temporal)
endif()
