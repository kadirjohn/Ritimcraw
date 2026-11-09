# JNI bridge for librawedit_jni.so.
# Adds ../../../../core (which builds the vendored motioncam-decoder as a
# static lib) and links rawedit into the shared library.

cmake_minimum_required(VERSION 3.22.1)
project(rawedit_jni CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# core/ builds rawedit + motioncam_decoder from third_party/.
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../core ${CMAKE_CURRENT_BINARY_DIR}/rawedit-core)

add_library(rawedit_jni SHARED jni_bridge.cpp)
target_link_libraries(rawedit_jni PRIVATE rawedit)
