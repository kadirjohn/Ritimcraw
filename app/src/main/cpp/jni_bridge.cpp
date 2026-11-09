// JNI bridge: rawedit (core/) <-> Kotlin engine/NativeSource.kt.
//
// Handle-based: nativeOpenSource dups the caller's fd, constructs a
// rawedit::Source and returns an opaque handle; every other call takes that
// handle. The Kotlin side keeps one handle per clip and confines all calls
// to a single engine thread (one motioncam decoder instance is not safe for
// concurrent use).
//
// Errors: no rawedit exception ever crosses the boundary. Calls return
// "empty" values (0 / nullptr) and the caller reads nativeLastError() for a
// stage + cause + recovery message (spec SYS-02).

#include <jni.h>

#include <memory>
#include <new>
#include <string>
#include <vector>

#include <rawedit/Result.h>
#include <rawedit/Source.h>

namespace {

// Last error detail for the most recent failed call on this thread.
thread_local std::string gLastError;

void setError(const std::string& message) {
    gLastError = message;
}

// Handle registry: a small fixed table keeps the bridge simple and avoids
// passing raw pointers over JNI. The Kotlin engine thread is single, so a
// plain array with generation counters is enough.
constexpr int kMaxSources = 8;

struct Entry {
    std::unique_ptr<rawedit::Source> source;
    bool inUse = false;
};

Entry gEntries[kMaxSources];

int64_t insertSource(std::unique_ptr<rawedit::Source> src) {
    for (int i = 0; i < kMaxSources; ++i) {
        if (!gEntries[i].inUse) {
            gEntries[i].source = std::move(src);
            gEntries[i].inUse = true;
            // Handle packs slot index; slots are tiny so this is exact.
            return static_cast<int64_t>(i) + 1;
        }
    }
    setError("openSource: too many open clips (recovery: close a clip and retry)");
    return 0;
}

rawedit::Source* sourceFor(int64_t handle) {
    if (handle <= 0 || handle > kMaxSources) {
        setError("invalid clip handle (recovery: reopen the clip)");
        return nullptr;
    }
    Entry& e = gEntries[handle - 1];
    if (!e.inUse || !e.source) {
        setError("clip is closed (recovery: reopen the clip)");
        return nullptr;
    }
    return e.source.get();
}

jstring toJavaString(JNIEnv* env, const std::string& s) {
    return env->NewStringUTF(s.c_str());
}

} // namespace

extern "C" {

// rawedit::Source* lifecycle -------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeOpenSource(JNIEnv*, jclass, jint fd) {
    auto result = rawedit::Source::open(static_cast<int>(fd));
    if (!result.isOk()) {
        setError(std::string("openSource: ") + rawedit::errorName(result.code) +
                 " — " + result.detail);
        return 0;
    }
    return insertSource(std::move(*result.value));
}

JNIEXPORT void JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeCloseSource(JNIEnv*, jclass, jlong handle) {
    if (handle > 0 && handle <= kMaxSources) {
        Entry& e = gEntries[handle - 1];
        e.source.reset();
        e.inUse = false;
    }
}

JNIEXPORT jint JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeFrameCount(JNIEnv*, jclass, jlong handle) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return -1;
    return static_cast<jint>(s->frameCount());
}

// FrameInfo -> FrameInfoK -----------------------------------------------------

JNIEXPORT jobject JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeFrameInfo(JNIEnv* env, jclass, jlong handle, jint index) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(index));

        jclass rectCls = env->FindClass("android/graphics/Rect");
        if (rectCls == nullptr) return nullptr;
        jmethodID rectCtor = env->GetMethodID(rectCls, "<init>", "(IIII)V");
        if (rectCtor == nullptr) return nullptr;
        jobject active = env->NewObject(
            rectCls, rectCtor,
            static_cast<jint>(info.activeArea.x),
            static_cast<jint>(info.activeArea.y),
            static_cast<jint>(info.activeArea.w),
            static_cast<jint>(info.activeArea.h));

        jclass cls = env->FindClass("com/ritim/ritimcraw/engine/FrameInfoK");
        if (cls == nullptr) return nullptr;
        jmethodID ctor = env->GetMethodID(
            cls, "<init>", "(JIIIILandroid/graphics/Rect;I)V");
        if (ctor == nullptr) return nullptr;
        return env->NewObject(
            cls, ctor,
            static_cast<jlong>(info.ptsUs),
            static_cast<jint>(info.width),
            static_cast<jint>(info.height),
            static_cast<jint>(info.bitDepth),
            static_cast<jint>(static_cast<int>(info.cfa)),
            active,
            static_cast<jint>(info.orientationDeg));
    } catch (const std::exception& e) {
        setError(std::string("frameInfo: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// Frames / audio ---------------------------------------------------------------

JNIEXPORT jshortArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeReadFrame(JNIEnv* env, jclass, jlong handle, jint index) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        auto result = s->readFrame(static_cast<size_t>(index));
        if (!result.isOk()) {
            setError(std::string("readFrame: ") + rawedit::errorName(result.code) +
                     " — " + result.detail);
            return nullptr;
        }
        const std::vector<uint16_t>& frame = *result.value;
        const jsize n = static_cast<jsize>(frame.size());
        jshortArray out = env->NewShortArray(n);
        if (out == nullptr) return nullptr;
        // uint16 samples reinterpreted as int16: bit pattern preserved.
        env->SetShortArrayRegion(
            out, 0, n, reinterpret_cast<const jshort*>(frame.data()));
        return out;
    } catch (const std::bad_alloc&) {
        setError("readFrame: out of memory decoding the frame (recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(std::string("readFrame: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jshortArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeReadAudio(JNIEnv* env, jclass, jlong handle) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        auto result = s->readAudio();
        if (!result.isOk()) {
            setError(std::string("readAudio: ") + rawedit::errorName(result.code) +
                     " — " + result.detail);
            return nullptr;
        }
        const std::vector<int16_t>& audio = *result.value;
        const jsize n = static_cast<jsize>(audio.size());
        jshortArray out = env->NewShortArray(n);
        if (out == nullptr) return nullptr;
        env->SetShortArrayRegion(out, 0, n, audio.data());
        return out;
    } catch (const std::exception& e) {
        setError(std::string("readAudio: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeLastError(JNIEnv* env, jclass) {
    if (gLastError.empty()) return nullptr;
    return toJavaString(env, gLastError);
}

} // extern "C"
