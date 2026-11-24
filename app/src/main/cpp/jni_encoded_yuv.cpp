// JNI entry for the E0b-2 GPU YUV pass (gles/EncodedYuvPass). Kept out of jni_bridge.cpp on purpose:
// the symbols resolve by name from the shared library, and the 6000-line bridge stays untouched.
// Contract: returns [w, h, w*h ARGB_8888 ints], the SAME shape and the SAME tail (linearToRec709, clamp,
// round-ties-up x255) as nativeEncodedDevelop, so a test can diff the two int arrays directly.

#include <jni.h>

#include <string>
#include <vector>

#include <rawedit/Transfer.h>

#include "gles/EncodedYuvPass.h"

namespace {
std::string gLastDetail;

inline uint8_t toByteLocal(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}
}  // namespace

extern "C" {

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeEncodedYuvPassHeadless(
        JNIEnv* env, jclass, jbyteArray yPlane, jbyteArray cPlane,
        jint width, jint height, jint stride, jint uvStride, jint bitDepth, jboolean fullRange,
        jint matrix, jint transfer, jint primaries,
        jfloat exposureEv, jfloat tempK, jfloat tint, jint reduce) {
    gLastDetail.clear();
    if (yPlane == nullptr || cPlane == nullptr) { gLastDetail = "null plane"; return nullptr; }
    const jsize yLen = env->GetArrayLength(yPlane);
    const jsize cLen = env->GetArrayLength(cPlane);
    jbyte* y = env->GetByteArrayElements(yPlane, nullptr);
    jbyte* c = env->GetByteArrayElements(cPlane, nullptr);
    if (y == nullptr || c == nullptr) {
        if (y) env->ReleaseByteArrayElements(yPlane, y, JNI_ABORT);
        if (c) env->ReleaseByteArrayElements(cPlane, c, JNI_ABORT);
        gLastDetail = "could not pin the planes";
        return nullptr;
    }
    gles::EncodedYuvPassParams p;
    p.width = width; p.height = height; p.stride = stride; p.uvStride = uvStride;
    p.bitDepth = bitDepth; p.fullRange = fullRange != JNI_FALSE;
    p.matrix = matrix; p.transfer = transfer; p.primaries = primaries;
    p.exposureEv = exposureEv; p.tempK = tempK; p.tint = tint; p.reduce = reduce;
    std::vector<float> linear;
    const bool ok = gles::runEncodedYuvPassHeadless(
        reinterpret_cast<const uint8_t*>(y), static_cast<size_t>(yLen),
        reinterpret_cast<const uint8_t*>(c), static_cast<size_t>(cLen), p, &linear, &gLastDetail);
    env->ReleaseByteArrayElements(yPlane, y, JNI_ABORT);
    env->ReleaseByteArrayElements(cPlane, c, JNI_ABORT);
    if (!ok) return nullptr;

    // Output dims follow the reduction (ceil(width/N) x ceil(height/N)); N = 1 is the full size.
    const jint outW = (width + reduce - 1) / reduce;
    const jint outH = (height + reduce - 1) / reduce;
    const jsize n = static_cast<jsize>(outW) * outH;
    std::vector<jint> out(static_cast<size_t>(n) + 2);
    out[0] = outW;
    out[1] = outH;
    for (jsize i = 0; i < n; ++i) {
        const float r = rawedit::linearToRec709(linear[static_cast<size_t>(i) * 3 + 0]);
        const float g = rawedit::linearToRec709(linear[static_cast<size_t>(i) * 3 + 1]);
        const float b = rawedit::linearToRec709(linear[static_cast<size_t>(i) * 3 + 2]);
        out[static_cast<size_t>(i) + 2] = static_cast<jint>(0xFF000000u |
            (static_cast<uint32_t>(toByteLocal(r)) << 16) |
            (static_cast<uint32_t>(toByteLocal(g)) << 8) |
            static_cast<uint32_t>(toByteLocal(b)));
    }
    jintArray arr = env->NewIntArray(n + 2);
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, n + 2, out.data());
    return arr;
}

// Timing bench: [mean upload ms, mean draw ms] over [frames] frames, or null (detail names why).
JNIEXPORT jdoubleArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeEncodedYuvPassBench(
        JNIEnv* env, jclass, jbyteArray yPlane, jbyteArray cPlane,
        jint width, jint height, jint stride, jint uvStride, jint bitDepth, jboolean fullRange,
        jint matrix, jint transfer, jint primaries, jint reduce, jint frames) {
    gLastDetail.clear();
    if (yPlane == nullptr || cPlane == nullptr) { gLastDetail = "null plane"; return nullptr; }
    const jsize yLen = env->GetArrayLength(yPlane);
    const jsize cLen = env->GetArrayLength(cPlane);
    jbyte* y = env->GetByteArrayElements(yPlane, nullptr);
    jbyte* c = env->GetByteArrayElements(cPlane, nullptr);
    if (y == nullptr || c == nullptr) {
        if (y) env->ReleaseByteArrayElements(yPlane, y, JNI_ABORT);
        if (c) env->ReleaseByteArrayElements(cPlane, c, JNI_ABORT);
        gLastDetail = "could not pin the planes";
        return nullptr;
    }
    gles::EncodedYuvPassParams p;
    p.width = width; p.height = height; p.stride = stride; p.uvStride = uvStride;
    p.bitDepth = bitDepth; p.fullRange = fullRange != JNI_FALSE;
    p.matrix = matrix; p.transfer = transfer; p.primaries = primaries; p.reduce = reduce;
    double upMs = 0.0, drMs = 0.0;
    const bool ok = gles::benchEncodedYuvPassHeadless(
        reinterpret_cast<const uint8_t*>(y), static_cast<size_t>(yLen),
        reinterpret_cast<const uint8_t*>(c), static_cast<size_t>(cLen), p, frames, &upMs, &drMs, &gLastDetail);
    env->ReleaseByteArrayElements(yPlane, y, JNI_ABORT);
    env->ReleaseByteArrayElements(cPlane, c, JNI_ABORT);
    if (!ok) return nullptr;
    jdoubleArray arr = env->NewDoubleArray(2);
    if (arr == nullptr) return nullptr;
    const jdouble v[2] = {upMs, drMs};
    env->SetDoubleArrayRegion(arr, 0, 2, v);
    return arr;
}

JNIEXPORT jstring JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeEncodedYuvPassLastDetail(JNIEnv* env, jclass) {
    return env->NewStringUTF(gLastDetail.c_str());
}

}  // extern "C"
