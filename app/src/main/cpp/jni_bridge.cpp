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

#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include <rawedit/DngWriter.h>
#include <rawedit/Lut.h>
#include <rawedit/Result.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>

namespace {

// Last error detail for the most recent failed call on this thread.
thread_local std::string gLastError;

// Numeric rawedit::ErrorCode of the most recent failed call on this thread
// (0 ok, 1 truncated, 2 badHeader, 3 ioError, 4 unsupported), plus the
// pipeline stage token, so the Kotlin PreviewErrors mapper can name the
// stage + cause + recovery per ErrorCode without re-parsing detail strings.
// All three are thread-local: read them from the same engine thread that
// failed. Updated by the preview setError only on failure paths.
thread_local int gLastErrorCode = 0;

// Stage tokens (shared contract with PreviewRenderer.kt / DngExporter.kt).
constexpr int kStageOpen = 0;
constexpr int kStageFrameRead = 1;
constexpr int kStageDevelop = 2;
constexpr int kStageDngWrite = 3;

thread_local int gLastErrorStage = kStageOpen;

void setError(const std::string& message) {
    gLastError = message;
}

void setError(int code, int stage, const std::string& message) {
    gLastErrorCode = code;
    gLastErrorStage = stage;
    gLastError = message;
}

// Stage-20 LUT cache (COL-07) -------------------------------------------------
//
// The preview engine thread and the export engine thread each call the
// develop entry points per frame; both hand the selected .cube's path as a
// jstring. Parsing a cube is expensive (up to 128^3 * 3 floats), so each
// thread keeps ONE parsed table: the path of the last parse and the table
// itself. Same path = reuse; different path = reparse; null/empty path =
// no LUT. A parse failure clears the cache and reports through the
// thread-local error state like every other pipeline failure.
thread_local std::string gLutPath;
thread_local std::unique_ptr<rawedit::Lut> gLut;

// Resolves the Kotlin lutPath argument into a table for this render.
// Returns false ONLY on a parse failure (error state set, cache cleared —
// the render fails loudly rather than silently dropping stage 20). Returns
// true with *out == nullptr when no LUT is selected (null or empty path —
// the old no-LUT look) or the path's file is missing (IoError is treated
// as no-LUT: LutStore.pathFor already checked existence, but a file
// removed between the Kotlin check and the open renders as no LUT rather
// than failing the whole clip — the grade still loads and the user can
// re-import the cube).
bool resolveLut(JNIEnv* env, jstring lutPath, const rawedit::Lut** out) {
    *out = nullptr;
    if (lutPath == nullptr) return true;
    const char* chars = env->GetStringUTFChars(lutPath, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "lut: out of memory reading the path (recovery: free memory and retry)");
        return false;
    }
    const std::string path(chars);
    env->ReleaseStringUTFChars(lutPath, chars);
    if (path.empty()) return true;

    if (gLut != nullptr && path == gLutPath) {
        *out = gLut.get();
        return true;
    }

    auto parsed = rawedit::parseCubeFile(path);
    if (!parsed.isOk()) {
        // Clear the stale cache so the next call with a good path reparses.
        gLut.reset();
        gLutPath.clear();
        setError(static_cast<int>(parsed.code), kStageDevelop,
                 std::string("lut: ") + rawedit::errorName(parsed.code) +
                     " — " + parsed.detail +
                     " (recovery: pick a valid .cube file)");
        return false;
    }
    gLut = std::make_unique<rawedit::Lut>(std::move(*parsed.value));
    gLutPath = path;
    *out = gLut.get();
    return true;
}

// Handle registry: a small fixed table keeps the bridge simple and avoids
// passing raw pointers over JNI. Each session confines ITS calls to one
// engine thread (one motioncam decoder is not concurrency-safe), but the
// preview engine thread and the export engine thread share THIS table —
// insert/close from both must not race on slot bookkeeping (the owner's
// "too many open clips" class of failure), so the table itself is guarded.
constexpr int kMaxSources = 8;

struct Entry {
    std::unique_ptr<rawedit::Source> source;
    bool inUse = false;
    // Interactive slider cache (owner request 2026-11-10 section 1): the
    // last decoded CFA for THIS handle, so a grade-only re-render skips the
    // decode. Frame-keyed; a new handle (reopen) starts with an empty cache.
    // All access is confined to the owner session's engine thread except
    // slot bookkeeping, which the registry mutex already guards.
    std::vector<uint16_t> cachedCfa;
    rawedit::FrameInfo cachedInfo{};
    int cachedFrameIndex = -1;
};

Entry gEntries[kMaxSources];
std::mutex gRegistryMutex;

int64_t insertSource(std::unique_ptr<rawedit::Source> src) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    for (int i = 0; i < kMaxSources; ++i) {
        if (!gEntries[i].inUse) {
            gEntries[i].source = std::move(src);
            gEntries[i].inUse = true;
            // A reopened slot starts with an empty interactive cache.
            gEntries[i].cachedCfa.clear();
            gEntries[i].cachedCfa.shrink_to_fit();
            gEntries[i].cachedInfo = rawedit::FrameInfo{};
            gEntries[i].cachedFrameIndex = -1;
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
    // Only the bookkeeping read is guarded: the returned Source* stays valid
    // because a handle is closed solely from the session's own engine thread
    // (drained after that session's renders), never from another thread.
    std::lock_guard<std::mutex> lock(gRegistryMutex);
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

// Preview pipeline helpers -----------------------------------------------------
//
// Decode -> developThumbnail (CFA decimation + the unchanged developCpu
// reference pipeline, core/Thumbnail.h) -> linearToRec709 -> clamp [0,1]
// -> 8-bit ARGB. Stage tokens + ErrorCode travel through thread-locals so
// the Kotlin layer can map stage + cause + recovery (spec SYS-02).

// Smallest strideEvery N (>= 1) whose thumbnail fits inside maxDim on the
// long edge, per the thumbnail math (outW = 2*ceil(width/2/N)). N = 1 is the
// identity full-res develop.
int strideFor(const rawedit::FrameInfo& info, int maxDim) {
    const long long w = info.width;
    const long long h = info.height;
    const long long longEdge = std::max(w, h);
    if (maxDim <= 0 || longEdge <= maxDim) return 1;
    // N cells of the source map to 1 cell of the thumbnail, so the long edge
    // shrinks by ~N; start from the exact ratio and walk up until it fits.
    int n = static_cast<int>((longEdge + maxDim - 1) / maxDim);
    if (n < 1) n = 1;
    while (n < 64) {
        const uint32_t outW = rawedit::thumbnailWidth(info.width, n);
        const uint32_t outH = rawedit::thumbnailHeight(info.height, n);
        if (outW == 0 || outH == 0) break;
        if (static_cast<long long>(std::max(outW, outH)) <= maxDim) return n;
        ++n;
    }
    return n;
}

// GradeK -> rawedit::Grade: identity field mapping, floats as-is. The grade
// floats travel as flat jfloat parameters (same style as the original four):
// the calls are per-frame and the JVM passes floats in registers, so a
// jfloatArray would only add a GetFloatArrayElements copy per render with no
// safety gain. The three RAW booleans (asShot, highlightReconstruction,
// keepVignetting) follow the same flat-register style as jbooleans; the
// detail/tonemap package (COL-15/COL-16) appends two floats and the tonemap
// selector int in the same style. Parameter order = GradeK field order
// (Develop.h Grade order): exposureEv, highlights, tempK, tint, saturation,
// blackPoint, whitePoint, shadows, midtones, contrast, asShot,
// highlightReconstruction, keepVignetting, sharpness, detail, tonemap. The
// LUT package (COL-07) appends the strength blend float in the same style;
// the Kotlin layer maps GradeK's 0..100 percent to rawedit's 0..1 blend
// before the call, so the jfloat here is already native-scale.
rawedit::Grade gradeFromKotlin(float exposureEv, float highlights, float tempK, float tint,
                               float saturation, float blackPoint, float whitePoint,
                               float shadows, float midtones, float contrast,
                               bool asShot, bool highlightReconstruction, bool keepVignetting,
                               float sharpness, float detail, int tonemap,
                               float lutStrength) {
    rawedit::Grade g;
    g.exposureEv = exposureEv;
    g.highlights = highlights;
    g.tempK = tempK;
    g.tint = tint;
    g.saturation = saturation;
    g.blackPoint = blackPoint;
    g.whitePoint = whitePoint;
    g.shadows = shadows;
    g.midtones = midtones;
    g.contrast = contrast;
    g.asShot = asShot;
    g.highlightReconstruction = highlightReconstruction;
    g.keepVignetting = keepVignetting;
    g.sharpness = sharpness;
    g.detail = detail;
    g.tonemap = tonemap;
    g.lutStrength = lutStrength;
    return g;
}

// linearToRec709 output is unclamped; clamp then quantize to 0..255.
inline uint8_t toByte(float rec709) {
    float v = rec709;
    if (v < 0.0f) v = 0.0f;
    if (v > 1.0f) v = 1.0f;
    // Round-to-nearest with ties resolved up, matching the DNG path's
    // integer quantization style.
    return static_cast<uint8_t>(v * 255.0f + 0.5f);
}

// Develop a decoded CFA into the preview's ARGB_8888 jint array
// ([width, height, pixels...], rotated upright per FrameInfo.orientationDeg).
// Shared by the plain preview render and the cached interactive render so the
// two paths stay pixel-identical. lut is the already-resolved stage-20 table
// (nullptr = no LUT). Returns nullptr (error state set) on a
// develop failure; a NewIntArray failure propagates as nullptr with a
// pending JNI exception, which the Kotlin wrapper already maps to a render
// failure.
jintArray developToArgb(JNIEnv* env,
                         const std::vector<uint16_t>& cfa,
                         const rawedit::FrameInfo& info,
                         const rawedit::RawMeta& meta,
                         const rawedit::Grade& grade,
                         const rawedit::Lut* lut,
                         int maxDim) {
    const int strideEvery = strideFor(info, maxDim);
    rawedit::ImageF img = rawedit::developThumbnail(
        cfa.data(), info, meta, grade, strideEvery, lut);
    if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the frame is too small to develop "
                 "(recovery: re-import the clip)");
        return nullptr;
    }

    const jsize w = static_cast<jsize>(img.w);
    const jsize h = static_cast<jsize>(img.h);
    const int orient = ((info.orientationDeg % 360) + 360) % 360;
    jsize outW = w;
    jsize outH = h;
    if (orient == 90 || orient == 270) {
        outW = h;
        outH = w;
    }
    const jsize n = outW * outH;
    // [width, height, then n ARGB pixels].
    std::vector<jint> out(static_cast<size_t>(n) + 2);
    out[0] = outW;
    out[1] = outH;
    jint* px = out.data() + 2;
    for (jsize y = 0; y < outH; ++y) {
        for (jsize x = 0; x < outW; ++x) {
            // Source pixel under destination (x, y) after the rotation.
            jsize si;
            switch (orient) {
                case 90:  si = static_cast<jsize>(h - 1 - x) * w + y; break;
                case 180: si = static_cast<jsize>(h - 1 - y) * w + (w - 1 - x); break;
                case 270: si = x * w + (w - 1 - y); break;
                default:  si = y * w + x; break;
            }
            const float r = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 0]);
            const float g = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 1]);
            const float b = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 2]);
            px[static_cast<size_t>(y) * outW + x] =
                0xFF000000 |
                (static_cast<jint>(toByte(r)) << 16) |
                (static_cast<jint>(toByte(g)) << 8) |
                static_cast<jint>(toByte(b));
        }
    }
    jintArray arr = env->NewIntArray(n + 2);
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, n + 2, out.data());
    return arr;
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
        std::lock_guard<std::mutex> lock(gRegistryMutex);
        Entry& e = gEntries[handle - 1];
        e.source.reset();
        e.inUse = false;
        // Drop the interactive cache with the Source it belongs to (a
        // reopened handle starts fresh).
        e.cachedCfa.clear();
        e.cachedCfa.shrink_to_fit();
        e.cachedInfo = rawedit::FrameInfo{};
        e.cachedFrameIndex = -1;
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

// Preview pipeline -------------------------------------------------------------

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewOpen(
        JNIEnv* env, jclass, jint fd) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    auto result = rawedit::Source::open(static_cast<int>(fd));
    if (!result.isOk()) {
        setError(static_cast<int>(result.code), kStageOpen,
                 std::string("open: ") + rawedit::errorName(result.code) +
                     " — " + result.detail);
        return nullptr;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*result.value);
    const size_t frames = src->frameCount();
    if (frames == 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 "open: unsupported — the clip holds no video frames "
                 "(recovery: re-import the clip)");
        return nullptr;
    }
    const rawedit::FrameInfo first = src->frameInfo(0);
    const int64_t handle = insertSource(std::move(src));
    if (handle == 0) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageOpen,
                 gLastError);
        return nullptr;
    }
    // [handle, frameCount, firstWidth, firstHeight, firstCfa, firstOrientation]
    jint out[6] = {
        static_cast<jint>(handle),
        static_cast<jint>(frames),
        static_cast<jint>(first.width),
        static_cast<jint>(first.height),
        static_cast<jint>(static_cast<int>(first.cfa)),
        static_cast<jint>(first.orientationDeg),
    };
    jintArray arr = env->NewIntArray(6);
    if (arr == nullptr) return nullptr;
    env->SetIntArrayRegion(arr, 0, 6, out);
    return arr;
}

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewRender(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength,
        jint maxDim) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-20 LUT first (COL-07): a parse failure fails the
    // render with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 1. Frame read.
        auto frame = s->readFrame(static_cast<size_t>(frameIndex));
        if (!frame.isOk()) {
            setError(static_cast<int>(frame.code), kStageFrameRead,
                     std::string("frame read: ") + rawedit::errorName(frame.code) +
                         " — " + frame.detail);
            return nullptr;
        }
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2-3. Develop with CFA decimation + ARGB (shared preview tail).
        return developToArgb(env, *frame.value, info, meta,
                             gradeFromKotlin(exposureEv, highlights, tempK, tint,
                                             saturation, blackPoint, whitePoint,
                                             shadows, midtones, contrast,
                                             asShot, highlightReconstruction,
                                             keepVignetting, sharpness, detail,
                                             tonemap, lutStrength),
                             lut, maxDim);
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// Interactive slider render (owner request 2026-11-10 section 1): same output
// contract as nativePreviewRender, but the handle's Entry caches the last
// decoded CFA. When frameIndex matches the cache the DECODE is skipped and
// only the develop+ARGB tail runs — a grade-only re-render during a slider
// drag. A frameIndex change (or a reopened handle, whose cache starts empty)
// decodes and replaces the cache. Same thread as the session's other calls
// (the single preview engine thread), so the cache needs no extra locking
// beyond the registry's slot bookkeeping.
JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewSessionRenderCached(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength,
        jint maxDim) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-20 LUT first (COL-07): a parse failure fails the
    // render with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    if (handle <= 0 || handle > kMaxSources) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 "invalid clip handle (recovery: reopen the clip)");
        return nullptr;
    }
    Entry& e = gEntries[handle - 1];
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 1. Decode only when the requested frame is not the cached one.
        if (frameIndex != e.cachedFrameIndex) {
            auto frame = s->readFrame(static_cast<size_t>(frameIndex));
            if (!frame.isOk()) {
                setError(static_cast<int>(frame.code), kStageFrameRead,
                         std::string("frame read: ") + rawedit::errorName(frame.code) +
                             " — " + frame.detail);
                return nullptr;
            }
            e.cachedCfa = std::move(*frame.value);
            e.cachedInfo = s->frameInfo(static_cast<size_t>(frameIndex));
            e.cachedFrameIndex = frameIndex;
        }
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2-3. Develop the (possibly cached) CFA + ARGB (shared preview tail).
        return developToArgb(env, e.cachedCfa, e.cachedInfo, meta,
                             gradeFromKotlin(exposureEv, highlights, tempK, tint,
                                             saturation, blackPoint, whitePoint,
                                             shadows, midtones, contrast,
                                             asShot, highlightReconstruction,
                                             keepVignetting, sharpness, detail,
                                             tonemap, lutStrength),
                             lut, maxDim);
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jint JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewErrorCode(JNIEnv*, jclass) {
    return gLastErrorCode;
}

JNIEXPORT jint JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativePreviewErrorStage(JNIEnv*, jclass) {
    return gLastErrorStage;
}

// LUT probe (COL-07) -----------------------------------------------------------
//
// Stateless validation for the import flow: parses the .cube at path and
// returns its TITLE (a non-null jstring, possibly empty when the file has
// no TITLE line), or nullptr on a parse failure with the error state set
// — the Kotlin layer reads nativeLastError() for the cause. No handle, no
// cache: the render-path cache (gLut/gLutPath) is deliberately untouched,
// so probing never poisons a render and is safe to call from any thread
// (the screen probes on the import click; the render threads keep their
// own parsed tables).

JNIEXPORT jstring JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeLutProbe(
        JNIEnv* env, jclass, jstring path) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    const char* chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "lut: out of memory reading the path (recovery: free memory and retry)");
        return nullptr;
    }
    const std::string file(chars);
    env->ReleaseStringUTFChars(path, chars);
    if (file.empty()) {
        setError(static_cast<int>(rawedit::ErrorCode::BadHeader), kStageDevelop,
                 "lut: bad header — the path is empty "
                 "(recovery: pick a valid .cube file)");
        return nullptr;
    }
    auto parsed = rawedit::parseCubeFile(file);
    if (!parsed.isOk()) {
        setError(static_cast<int>(parsed.code), kStageDevelop,
                 std::string("lut: ") + rawedit::errorName(parsed.code) +
                     " — " + parsed.detail +
                     " (recovery: pick a valid .cube file)");
        return nullptr;
    }
    return toJavaString(env, parsed.value->title);
}

// MP4 export pipeline ----------------------------------------------------------
//
// The exporter opens a session with nativePreviewOpen (same handle registry,
// same [handle, frameCount, w, h, cfa, orient] contract) and then calls
// nativeExportFrame per frame: full-res developCpu (NO thumbnail
// downscale) -> linearToRec709 -> clamp [0,1] -> quantize 8-bit RGB888.
// The clamp is inherent to 8-bit quantization (linearToRec709 itself is
// unclamped); values above 1.0 clip at 255, below 0 at 0. A 4K frame is
// ~25 MB of RGB — acceptable for the CPU reference path (a GPU path lands
// later). PTS comes from nativeFramePtsUs; audio description from
// nativeAudioInfo, samples from nativeReadAudio (all existing entry points
// except the two new ones below).

JNIEXPORT jbyteArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeExportFrame(
        JNIEnv* env, jclass, jlong handle, jint frameIndex,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    // 0. Resolve the stage-20 LUT first (COL-07): a parse failure fails the
    // export frame with the lut error instead of a frame-read error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 1. Frame read.
        auto frame = s->readFrame(static_cast<size_t>(frameIndex));
        if (!frame.isOk()) {
            setError(static_cast<int>(frame.code), kStageFrameRead,
                     std::string("frame read: ") + rawedit::errorName(frame.code) +
                         " — " + frame.detail);
            return nullptr;
        }
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Full-res develop with the job's grade snapshot (core/Develop.h).
        gLastErrorStage = kStageDevelop;
        rawedit::ImageF img = rawedit::developCpu(
            frame.value->data(), info, meta,
            gradeFromKotlin(exposureEv, highlights, tempK, tint,
                            saturation, blackPoint, whitePoint,
                            shadows, midtones, contrast,
                            asShot, highlightReconstruction, keepVignetting,
                            sharpness, detail, tonemap, lutStrength),
            lut);
        if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop: unsupported — the frame is too small to develop "
                     "(recovery: re-import the clip)");
            return nullptr;
        }

        // 3. linearToRec709 -> clamp -> 8-bit RGB888, row-major, 3 bytes
        //    per pixel. Not rotated: the exporter passes orientationDeg to
        //    MediaMuxer.setOrientationHint so players rotate at display time.
        const jsize w = static_cast<jsize>(img.w);
        const jsize h = static_cast<jsize>(img.h);
        const jsize n = w * h * 3;
        std::vector<jbyte> out(static_cast<size_t>(n));
        for (jsize i = 0; i < w * h; ++i) {
            out[static_cast<size_t>(i) * 3 + 0] = static_cast<jbyte>(
                toByte(rawedit::linearToRec709(img.rgb[static_cast<size_t>(i) * 3 + 0])));
            out[static_cast<size_t>(i) * 3 + 1] = static_cast<jbyte>(
                toByte(rawedit::linearToRec709(img.rgb[static_cast<size_t>(i) * 3 + 1])));
            out[static_cast<size_t>(i) * 3 + 2] = static_cast<jbyte>(
                toByte(rawedit::linearToRec709(img.rgb[static_cast<size_t>(i) * 3 + 2])));
        }
        jbyteArray arr = env->NewByteArray(n);
        if (arr == nullptr) return nullptr;
        env->SetByteArrayRegion(arr, 0, n, out.data());
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory decoding the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

JNIEXPORT jlong JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeFramePtsUs(
        JNIEnv*, jclass, jlong handle, jint index) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return -1;
    // frameInfo returns a zeroed FrameInfo for out-of-range indices (no
    // throw), so this is always safe; the exporter only walks 0..frameCount.
    return static_cast<jlong>(s->frameInfo(static_cast<size_t>(index)).ptsUs);
}

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeAudioInfo(
        JNIEnv* env, jclass, jlong handle) {
    rawedit::Source* s = sourceFor(handle);
    if (!s) return nullptr;
    try {
        auto info = s->audioInfo();
        if (!info.isOk()) {
            setError(static_cast<int>(info.code), kStageFrameRead,
                     std::string("audio info: ") + rawedit::errorName(info.code) +
                         " — " + info.detail);
            return nullptr;
        }
        jint out[2] = {static_cast<jint>(info.value->sampleRate),
                       static_cast<jint>(info.value->channels)};
        jintArray arr = env->NewIntArray(2);
        if (arr == nullptr) return nullptr;
        env->SetIntArrayRegion(arr, 0, 2, out);
        return arr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 std::string("audio info: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

// DNG export -------------------------------------------------------------------
//
// One-shot: open the clip from fd, decode frame [frameIndex], write the raw
// CFA mosaic as a DNG 1.4 file at outPath (the Kotlin layer passes its
// out.partial staging path in app cache; writeCfaDng already removes a
// partially written file on failure). No grade fields: a CFA DNG is the raw
// mosaic — developCpu is not part of this path. The handle registry is not
// used (the Source lives only for this call), so the function is safe to
// call from any single engine thread.

JNIEXPORT jboolean JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeDngExport(
        JNIEnv* env, jclass, jint fd, jint frameIndex, jstring outPath) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageOpen;
    const char* pathChars = env->GetStringUTFChars(outPath, nullptr);
    if (pathChars == nullptr) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDngWrite,
                 "dng export: out of memory reading the output path "
                 "(recovery: free memory and retry)");
        return JNI_FALSE;
    }
    const std::string path(pathChars);
    env->ReleaseStringUTFChars(outPath, pathChars);

    // 1. Open the clip from the SAF fd (the bridge dups it; the caller's fd
    //    is untouched).
    auto opened = rawedit::Source::open(static_cast<int>(fd));
    if (!opened.isOk()) {
        setError(static_cast<int>(opened.code), kStageOpen,
                 std::string("open: ") + rawedit::errorName(opened.code) +
                     " — " + opened.detail);
        return JNI_FALSE;
    }
    std::unique_ptr<rawedit::Source> src = std::move(*opened.value);

    // 2. Decode the frame (the expensive step — seconds on a 4K frame).
    gLastErrorStage = kStageFrameRead;
    auto frame = src->readFrame(static_cast<size_t>(frameIndex));
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("frame read: ") + rawedit::errorName(frame.code) +
                     " — " + frame.detail);
        return JNI_FALSE;
    }
    const rawedit::FrameInfo info = src->frameInfo(static_cast<size_t>(frameIndex));
    const rawedit::RawMeta& meta = src->rawMeta();

    // 3. Write the CFA DNG to the staging path (writeCfaDng removes a
    //    partial file on failure, so the staging file exists only on
    //    success — the Kotlin layer renames it into place).
    gLastErrorStage = kStageDngWrite;
    rawedit::Result<void> written = rawedit::writeCfaDng(
        path, frame.value->data(), info, meta);
    if (!written.isOk()) {
        setError(static_cast<int>(written.code), kStageDngWrite,
                 std::string("dng write: ") + rawedit::errorName(written.code) +
                     " — " + written.detail);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

// Encoded (Mi-Log MP4) develop ---------------------------------------------------
//
// The encoded decode path (sprint plan Task 6): the Kotlin EncodedSource
// state machine decodes a frame via MediaCodec (MediaExtractor/MediaCodec
// adapters on the Kotlin side), packs it as NV12 (or I420), and hands it
// here. This entry develops it with the same preview tail as the MCRAW path —
// developEncoded (limited-range YUV -> RGB -> Mi-Log decode -> WB/temp/tint/
// exposure -> sharpness/detail/tonemap, the shared COL-15/COL-16 stages,
// then the optional stage-20 LUT, COL-07) ->
// linearToRec709 -> clamp -> 8-bit ARGB, rotated upright by the container's
// rotation hint — the same [width, height, then w*h ARGB_8888 ints] contract
// as nativePreviewRender, so the preview UI is identical for both source
// kinds. No handle registry: the frame is stateless input.
//
// The frame bytes arrive as three flat arrays (mirroring rawedit::YuvFrame):
//   semiPlanar true  = NV12: yPlane + interleaved UV in uPlane (vPlane unused)
//   semiPlanar false = I420: y, u, v planes
// stride/uvStride are bytes per luma/chroma row (0 = width / width/2). The
// develop uses tempK/tint/exposureEv of the grade plus the detail/tonemap
// stages (developEncoded's contract); the other grade fields travel anyway
// (flat-register style, one signature for every develop entry point).

JNIEXPORT jintArray JNICALL
Java_com_ritim_ritimcraw_engine_NativeSource_nativeEncodedDevelop(
        JNIEnv* env, jclass,
        jbyteArray yPlane, jbyteArray uPlane, jbyteArray vPlane,
        jint width, jint height, jint stride, jint uvStride,
        jboolean semiPlanar, jboolean isBt709, jint orientationDeg,
        jfloat exposureEv, jfloat highlights, jfloat tempK, jfloat tint,
        jfloat saturation, jfloat blackPoint, jfloat whitePoint,
        jfloat shadows, jfloat midtones, jfloat contrast,
        jboolean asShot, jboolean highlightReconstruction, jboolean keepVignetting,
        jfloat sharpness, jfloat detail, jint tonemap,
        jstring lutPath, jfloat lutStrength) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    // Resolve the stage-20 LUT first (COL-07): a parse failure fails the
    // develop with the lut error.
    const rawedit::Lut* lut = nullptr;
    if (!resolveLut(env, lutPath, &lut)) return nullptr;

    if (width <= 0 || height <= 0 || yPlane == nullptr || uPlane == nullptr ||
        (semiPlanar == JNI_FALSE && vPlane == nullptr)) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the decoded frame is empty or malformed "
                 "(recovery: re-import the clip)");
        return nullptr;
    }

    // Copy the plane bytes (GetByteArrayElements may pin/copy; the release
    // mode below is abort for read-only use, so no writeback is expected).
    const jsize yLen = env->GetArrayLength(yPlane);
    const jsize uLen = env->GetArrayLength(uPlane);
    const jsize vLen = vPlane != nullptr ? env->GetArrayLength(vPlane) : 0;
    const int s = stride > 0 ? stride : width;
    const int us = uvStride > 0 ? uvStride : width / 2;

    rawedit::YuvFrame frame;
    frame.width = static_cast<uint32_t>(width);
    frame.height = static_cast<uint32_t>(height);
    frame.semiPlanar = semiPlanar != JNI_FALSE;
    frame.stride = s;
    frame.uvStride = us;

    // Size check before any copy: the develop indexes y up to
    // (height-1)*stride + width-1 and the chroma grid up to
    // (ch-1)*uvStride + (cw-1)*2 (+1 for the interleaved V byte).
    const long long cw = (static_cast<long long>(width) + 1) / 2;
    const long long ch = (static_cast<long long>(height) + 1) / 2;
    const long long yNeed = static_cast<long long>(height - 1) * s + width;
    const long long cNeed = frame.semiPlanar
        ? static_cast<long long>(ch - 1) * us + (cw - 1) * 2 + 2
        : static_cast<long long>(ch - 1) * us + cw;
    if (yLen < yNeed || uLen < cNeed ||
        (!frame.semiPlanar && (vLen < cNeed))) {
        setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                 "develop: unsupported — the decoded frame's planes are smaller "
                 "than its dimensions (recovery: re-import the clip)");
        return nullptr;
    }

    {
        jbyte* yBytes = env->GetByteArrayElements(yPlane, nullptr);
        if (yBytes == nullptr) return nullptr;
        frame.y.assign(reinterpret_cast<const uint8_t*>(yBytes),
                       reinterpret_cast<const uint8_t*>(yBytes) + yLen);
        env->ReleaseByteArrayElements(yPlane, yBytes, JNI_ABORT);

        jbyte* uBytes = env->GetByteArrayElements(uPlane, nullptr);
        if (uBytes == nullptr) return nullptr;
        frame.u.assign(reinterpret_cast<const uint8_t*>(uBytes),
                       reinterpret_cast<const uint8_t*>(uBytes) + uLen);
        env->ReleaseByteArrayElements(uPlane, uBytes, JNI_ABORT);

        if (vPlane != nullptr) {
            jbyte* vBytes = env->GetByteArrayElements(vPlane, nullptr);
            if (vBytes == nullptr) return nullptr;
            frame.v.assign(reinterpret_cast<const uint8_t*>(vBytes),
                           reinterpret_cast<const uint8_t*>(vBytes) + vLen);
            env->ReleaseByteArrayElements(vPlane, vBytes, JNI_ABORT);
        }
    }

    try {
        // 1. developEncoded: limited-range YUV -> RGB -> Mi-Log decode ->
        //    WB/temp/tint/exposure -> sharpness/detail/tonemap
        //    (core/Transfer.h).
        rawedit::ImageF img = rawedit::developEncoded(
            frame,
            gradeFromKotlin(exposureEv, highlights, tempK, tint,
                            saturation, blackPoint, whitePoint,
                            shadows, midtones, contrast,
                            asShot, highlightReconstruction, keepVignetting,
                            sharpness, detail, tonemap, lutStrength),
            isBt709 != JNI_FALSE, lut);
        if (img.w == 0 || img.h == 0 || img.rgb.empty()) {
            setError(static_cast<int>(rawedit::ErrorCode::Unsupported), kStageDevelop,
                     "develop: unsupported — the frame is too small to develop "
                     "(recovery: re-import the clip)");
            return nullptr;
        }

        // 2. linearToRec709 -> clamp -> 8-bit ARGB, rotated upright (the
        //    same rotation math as developToArgb, so an encoded clip and an
        //    MCRAW clip of the same scene show identical orientation).
        const jsize w = static_cast<jsize>(img.w);
        const jsize h = static_cast<jsize>(img.h);
        const int orient = ((orientationDeg % 360) + 360) % 360;
        jsize outW = w;
        jsize outH = h;
        if (orient == 90 || orient == 270) {
            outW = h;
            outH = w;
        }
        const jsize n = outW * outH;
        std::vector<jint> out(static_cast<size_t>(n) + 2);
        out[0] = outW;
        out[1] = outH;
        jint* px = out.data() + 2;
        for (jsize y = 0; y < outH; ++y) {
            for (jsize x = 0; x < outW; ++x) {
                jsize si;
                switch (orient) {
                    case 90:  si = static_cast<jsize>(h - 1 - x) * w + y; break;
                    case 180: si = static_cast<jsize>(h - 1 - y) * w + (w - 1 - x); break;
                    case 270: si = x * w + (w - 1 - y); break;
                    default:  si = y * w + x; break;
                }
                const float r = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 0]);
                const float g = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 1]);
                const float b = rawedit::linearToRec709(img.rgb[static_cast<size_t>(si) * 3 + 2]);
                px[static_cast<size_t>(y) * outW + x] =
                    0xFF000000 |
                    (static_cast<jint>(toByte(r)) << 16) |
                    (static_cast<jint>(toByte(g)) << 8) |
                    static_cast<jint>(toByte(b));
            }
        }
        jintArray arr = env->NewIntArray(n + 2);
        if (arr == nullptr) return nullptr;
        env->SetIntArrayRegion(arr, 0, n + 2, out.data());
        return arr;
    } catch (const std::bad_alloc&) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 "develop: out of memory developing the frame "
                 "(recovery: free memory and retry)");
        return nullptr;
    } catch (const std::exception& e) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageDevelop,
                 std::string("develop: ") + e.what() + " (recovery: reopen the clip)");
        return nullptr;
    }
}

} // extern "C"
