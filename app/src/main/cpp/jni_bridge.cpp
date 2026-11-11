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
#include <rawedit/Result.h>
#include <rawedit/Source.h>
#include <rawedit/Temporal.h>
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

// Handle registry: a small fixed table keeps the bridge simple and avoids
// passing raw pointers over JNI. Each session confines ITS calls to one
// engine thread (one motioncam decoder is not concurrency-safe), but the
// preview engine thread and the export engine thread share THIS table —
// insert/close from both must not race on slot bookkeeping (the owner's
// "too many open clips" class of failure), so the table itself is guarded.
constexpr int kMaxSources = 8;

// One immutable decoded CFA. Every decoded frame is wrapped in a shared
// const buffer the moment readFrame returns it, and the interactive cache,
// the temporal ring and the merge input all hold shared_ptrs to the SAME
// allocation: a frame is decoded at most once per eviction cycle and never
// copied between caches. Const-by-construction also enforces the Temporal.h
// "pristine inputs" contract — nothing can write a merged frame back into a
// buffer the ring still shares.
using CfaBuffer = std::shared_ptr<const std::vector<uint16_t>>;

// One decoded CFA in the temporal neighbor ring, keyed by frame index.
// PRISTINE by contract: the ring stores only what readFrame returned —
// temporal OUTPUT is never fed back as temporal INPUT (Temporal.h "what the
// caller owns"), so a merged frame can never become another merge's input.
struct RingFrame {
    int index = -1;
    CfaBuffer cfa;
};

// Temporal neighbor ring capacity (DN-09): center + prev + next is 3; the
// 4th slot lets a one-frame scrub back-and-forth stay cached. A 4K CFA is
// ~16.6 MB (uint16), so the ring bound is ~66 MB per open clip at 4K — the
// documented worst case (the ring is cleared on close/reopen and emptied
// when temporal NR turns off).
constexpr size_t kTemporalRingCapacity = 4;

struct Entry {
    std::unique_ptr<rawedit::Source> source;
    bool inUse = false;
    // Interactive slider cache (owner request 2026-11-10 section 1): the
    // last decoded CFA for THIS handle, so a grade-only re-render skips the
    // decode. Frame-keyed; a new handle (reopen) starts with an empty cache.
    // All access is confined to the owner session's engine thread except
    // slot bookkeeping, which the registry mutex already guards.
    CfaBuffer cachedCfa;
    rawedit::FrameInfo cachedInfo{};
    int cachedFrameIndex = -1;
    // Temporal NR state (spec DN-05..DN-09), same threading contract as the
    // interactive cache above (owner session's engine thread only):
    //   ring           — LRU of pristine decoded CFAs, see RingFrame;
    //   mergedCfa      — the last temporal merge OUTPUT, non-null while
    //                    mergedFrameIndex/mergedStrength match the request
    //                    (the merge is a pure function of the pristine
    //                    window + strength, so a grade-only re-render — any
    //                    non-temporal slider — reuses it instead of
    //                    re-merging).
    std::vector<RingFrame> ring;
    int mergedFrameIndex = -1;
    float mergedStrength = -1.0f;
    CfaBuffer mergedCfa;
};

Entry gEntries[kMaxSources];
std::mutex gRegistryMutex;

// Drops every per-session cache of one Entry (interactive + temporal). Call
// sites hold the registry mutex. The shared_ptr resets drop the Entry's
// references so the buffers free with the last owner — a closed 4K clip's
// ~66 MB ring must not stay resident.
void clearEntryCaches(Entry& e) {
    e.cachedCfa = nullptr;
    e.cachedInfo = rawedit::FrameInfo{};
    e.cachedFrameIndex = -1;
    e.ring.clear();
    e.ring.shrink_to_fit();
    e.mergedFrameIndex = -1;
    e.mergedStrength = -1.0f;
    e.mergedCfa = nullptr;
}

int64_t insertSource(std::unique_ptr<rawedit::Source> src) {
    std::lock_guard<std::mutex> lock(gRegistryMutex);
    for (int i = 0; i < kMaxSources; ++i) {
        if (!gEntries[i].inUse) {
            gEntries[i].source = std::move(src);
            gEntries[i].inUse = true;
            // A reopened slot starts with empty caches.
            clearEntryCaches(gEntries[i]);
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
// selector int in the same style; temporal NR (DN-05..DN-09) appends its
// toggle + strength the same way. Parameter order = GradeK field order
// (Develop.h Grade order): exposureEv, highlights, tempK, tint, saturation,
// blackPoint, whitePoint, shadows, midtones, contrast, asShot,
// highlightReconstruction, keepVignetting, sharpness, detail, tonemap,
// temporalNr, temporalStrength. developCpu itself ignores the temporal
// fields (the bridge applies the stage to the CFA before developCpu); they
// ride Grade so the whole grade pipeline carries them like any other
// control.
rawedit::Grade gradeFromKotlin(float exposureEv, float highlights, float tempK, float tint,
                               float saturation, float blackPoint, float whitePoint,
                               float shadows, float midtones, float contrast,
                               bool asShot, bool highlightReconstruction, bool keepVignetting,
                               float sharpness, float detail, int tonemap,
                               bool temporalNr, float temporalStrength) {
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
    g.temporalNr = temporalNr;
    g.temporalStrength = temporalStrength;
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
// two paths stay pixel-identical. Returns nullptr (error state set) on a
// develop failure; a NewIntArray failure propagates as nullptr with a
// pending JNI exception, which the Kotlin wrapper already maps to a render
// failure.
jintArray developToArgb(JNIEnv* env,
                         const std::vector<uint16_t>& cfa,
                         const rawedit::FrameInfo& info,
                         const rawedit::RawMeta& meta,
                         const rawedit::Grade& grade,
                         int maxDim) {
    const int strideEvery = strideFor(info, maxDim);
    rawedit::ImageF img = rawedit::developThumbnail(
        cfa.data(), info, meta, grade, strideEvery);
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

// Temporal NR window assembly (spec DN-05..DN-09) ------------------------------
//
// Everything the JNI entry points need from Temporal.h, gathered behind one
// helper so the preview, cached-preview and export paths run the SAME logic:
//   - an LRU ring of pristine decoded CFAs (kTemporalRingCapacity entries,
//     newest first) so sequential playback/scrub amortizes to ~1 decode per
//     frame (DN-09);
//   - PTS-window eligibility via temporalNeighborEligible against the LOCAL
//     frame duration (median of the center frame's immediate neighbors'
//     PTS deltas; falls back to 1/fps-style spacing from pts[0] when the
//     clip has fewer than three frames);
//   - dimension + CFA-pattern agreement with the center frame (a neighbor
//     from a format change inside the clip is rejected, never blended);
//   - per-frame noise profiles via Source::noiseProfile (the temporal stage
//     itself falls back to its documented fallback sigma when invalid);
//   - a (frameIndex, strength)-keyed merge cache: the merge is a pure
//     function of the pristine window + strength, so a grade-only re-render
//     (any non-temporal slider moved) reuses the previous merge output
//     instead of re-running motion estimation.
//
// Returns the CFA to develop: the merged frame when temporal NR produced
// one, or the PRISTINE center otherwise. NEVER mutates the ring (the ring
// is inputs-only). A nullptr source ring entry (decode failure) is treated
// as "no such neighbor" — the merge then proceeds with the other side, the
// conservative behavior.

// Returns frame i's pristine CFA from the ring, decoding it once on a miss.
// The buffer is shared-const (CfaBuffer), so the returned pointer stays valid
// no matter what later loads do to the ring (eviction frees the RingFrame,
// not a buffer someone else still holds). MRU order is maintained by moving
// a hit to the back. Returns nullptr on a decode failure (error already set).
CfaBuffer loadRingFrame(rawedit::Source& s, Entry& e, int frameIndex) {
    for (size_t i = 0; i < e.ring.size(); ++i) {
        if (e.ring[i].index != frameIndex) continue;
        // Move the hit to the back (MRU) — cheap: shared_ptr swap.
        if (i + 1 != e.ring.size()) {
            RingFrame hit = std::move(e.ring[i]);
            e.ring.erase(e.ring.begin() + static_cast<long>(i));
            e.ring.push_back(std::move(hit));
        }
        return e.ring.back().cfa;
    }
    auto frame = s.readFrame(static_cast<size_t>(frameIndex));
    if (!frame.isOk()) {
        setError(static_cast<int>(frame.code), kStageFrameRead,
                 std::string("frame read: ") + rawedit::errorName(frame.code) +
                     " — " + frame.detail);
        return nullptr;
    }
    RingFrame nf;
    nf.index = frameIndex;
    nf.cfa = CfaBuffer(new std::vector<uint16_t>(std::move(*frame.value)));
    e.ring.push_back(std::move(nf));
    while (e.ring.size() > kTemporalRingCapacity) e.ring.erase(e.ring.begin());
    return e.ring.back().cfa;
}

// Ensures the ALREADY-DECODED center buffer is in the ring WITHOUT a decode:
// a hit just refreshes MRU order; a miss pushes the shared buffer (no copy).
// Used by temporalMergedCfa so a cache-hit center (the interactive cache
// survived, but the ring was cleared by an off->on toggle or evicted during
// a scrub) rejoins its neighbors at zero decode cost.
CfaBuffer ensureRingFrame(Entry& e, int frameIndex, CfaBuffer cfa) {
    for (size_t i = 0; i < e.ring.size(); ++i) {
        if (e.ring[i].index != frameIndex) continue;
        if (i + 1 != e.ring.size()) {
            RingFrame hit = std::move(e.ring[i]);
            e.ring.erase(e.ring.begin() + static_cast<long>(i));
            e.ring.push_back(std::move(hit));
        }
        return e.ring.back().cfa;
    }
    RingFrame nf;
    nf.index = frameIndex;
    nf.cfa = std::move(cfa);
    e.ring.push_back(std::move(nf));
    while (e.ring.size() > kTemporalRingCapacity) e.ring.erase(e.ring.begin());
    return e.ring.back().cfa;
}

// True when neighbor info can be blended with center info: same CFA
// geometry (dimensions + pattern). Orientation change inside one clip would
// also land here; FrameInfo has no per-frame orientation change in this
// container, so geometry is the full check.
bool temporalNeighborCompatible(const rawedit::FrameInfo& center,
                                const rawedit::FrameInfo& neighbor) {
    return center.width == neighbor.width && center.height == neighbor.height &&
           center.cfa == neighbor.cfa;
}

// Local median frame duration around the center frame (microseconds):
// median of {pts[i] - pts[i-1], pts[i+1] - pts[i]} — the local spacing, not
// a whole-clip average, so a VFR clip with a slow intro still uses the
// spacing around THIS frame. 0 when unknown (fewer than two frames); the
// caller then skips the distance decay (dPts fields stay 0 = no decay).
int64_t localFrameDurationUs(rawedit::Source& s, int frameIndex) {
    const size_t n = s.frameCount();
    if (n < 2) return 0;
    const int i = frameIndex;
    int64_t deltas[2] = {0, 0};
    int k = 0;
    if (i - 1 >= 0) deltas[k++] = s.frameInfo(static_cast<size_t>(i)).ptsUs -
                                  s.frameInfo(static_cast<size_t>(i - 1)).ptsUs;
    if (i + 1 < static_cast<int>(n))
        deltas[k++] = s.frameInfo(static_cast<size_t>(i + 1)).ptsUs -
                      s.frameInfo(static_cast<size_t>(i)).ptsUs;
    if (k == 2) return (deltas[0] + deltas[1]) / 2; // median of 2 = mean
    return deltas[0];
}

// The assembled window. Prev/next are shared-const buffers: eviction frees
// the RingFrame, never a buffer this window still holds. Null when that
// side is missing or ineligible. Noise profiles travel by value (8 floats
// each); the temporal stage ignores them when .valid is false (fallback
// sigma).
struct TemporalWindow {
    CfaBuffer prev;
    CfaBuffer next;
    rawedit::NoiseProfile prevNoise{};
    rawedit::NoiseProfile nextNoise{};
    rawedit::NoiseProfile centerNoise{};
    int64_t prevDPtsUs = 0;
    int64_t nextDPtsUs = 0;
    int64_t frameDurationUs = 0;
};

// Loads the eligible temporal neighbors of frameIndex into the ring and
// assembles the window. Returns false ONLY on a hard decode failure of a
// neighbor (error set); missing/ineligible neighbors just leave their side
// null. The center CFA itself is NOT loaded here — the caller routes it
// through loadRingFrame too (the ring is the complete temporal window
// store; sequential playback then decodes only the new frontier frame,
// because frame i+1's prefetch is frame i's neighbor).
bool assembleTemporalWindow(rawedit::Source& s, Entry& e, int frameIndex,
                            const rawedit::FrameInfo& centerInfo,
                            TemporalWindow& w) {
    const int n = static_cast<int>(s.frameCount());
    w.frameDurationUs = localFrameDurationUs(s, frameIndex);
    w.centerNoise = s.noiseProfile(static_cast<size_t>(frameIndex));
    for (int side = 0; side < 2; ++side) {
        const int ni = side == 0 ? frameIndex - 1 : frameIndex + 1;
        if (ni < 0 || ni >= n) continue;
        const rawedit::FrameInfo niInfo = s.frameInfo(static_cast<size_t>(ni));
        const int64_t dPts = niInfo.ptsUs - centerInfo.ptsUs;
        if (!rawedit::temporalNeighborEligible(dPts, w.frameDurationUs)) continue;
        if (!temporalNeighborCompatible(centerInfo, niInfo)) continue;
        const CfaBuffer cfa = loadRingFrame(s, e, ni);
        if (cfa == nullptr) return false; // decode failed: hard error
        if (side == 0) {
            w.prev = cfa;
            w.prevNoise = s.noiseProfile(static_cast<size_t>(ni));
            w.prevDPtsUs = dPts;
        } else {
            w.next = cfa;
            w.nextNoise = s.noiseProfile(static_cast<size_t>(ni));
            w.nextDPtsUs = dPts;
        }
    }
    return true;
}

// Produces the CFA for developCpu with temporal NR applied when enabled:
//   strength <= 0 or temporalNr off  -> the pristine center, untouched, and
//   the temporal caches are emptied (an off->on toggle starts clean);
//   cache hit (frame + strength)     -> the previous merge output;
//   otherwise                        -> temporalMergeCfa over the window,
//   output cached in mergedCfa.
// centerCfa is the ALREADY-DECODED center (the interactive cache or a fresh
// readFrame — the caller routes it through loadRingFrame too, so the ring
// holds every frame of the window). On a neighbor decode failure returns
// nullptr (error already set) so the caller fails the render; the
// conservative alternative (merge with the other side only) would hide a
// broken clip behind a slow render.
CfaBuffer temporalMergedCfa(
        rawedit::Source& s, Entry& e, int frameIndex,
        CfaBuffer centerCfa,
        const rawedit::FrameInfo& centerInfo,
        bool temporalNr, float strength) {
    if (!temporalNr || strength <= 0.0f) {
        // Zero-cost when off: no neighbor decodes, no merge. Drop any
        // temporal caches — including this render's center ring entry, so
        // a clip with temporal NR off never holds temporal memory between
        // renders (the interactive cachedCfa is untouched).
        e.mergedFrameIndex = -1;
        e.mergedStrength = -1.0f;
        e.mergedCfa = nullptr;
        e.ring.clear();
        e.ring.shrink_to_fit();
        return centerCfa;
    }
    if (e.mergedFrameIndex == frameIndex && e.mergedStrength == strength &&
        e.mergedCfa != nullptr) {
        return e.mergedCfa; // grade-only re-render: same merge, reuse
    }
    // The center joins the ring FIRST (no decode: the buffer is shared), so
    // the window assembly below and any same-frame retry read it from there.
    centerCfa = ensureRingFrame(e, frameIndex, std::move(centerCfa));
    TemporalWindow w;
    if (!assembleTemporalWindow(s, e, frameIndex, centerInfo, w)) {
        return nullptr;
    }
    if (w.prev == nullptr && w.next == nullptr) {
        // No eligible neighbor: both-rejected/edge frame. Output is the
        // center bit for bit (the shared pristine buffer itself — no copy);
        // cached so a grade-only re-render reuses it.
        e.mergedFrameIndex = frameIndex;
        e.mergedStrength = strength;
        e.mergedCfa = centerCfa;
        return e.mergedCfa;
    }
    rawedit::TemporalSettings ts;
    ts.strength = strength;
    ts.centerNoise = &w.centerNoise;
    ts.prevNoise = &w.prevNoise;
    ts.nextNoise = &w.nextNoise;
    ts.prevDPtsUs = w.prevDPtsUs;
    ts.nextDPtsUs = w.nextDPtsUs;
    ts.frameDurationUs = w.frameDurationUs;
    auto merged = std::make_shared<std::vector<uint16_t>>(centerCfa->size(), 0);
    rawedit::temporalMergeCfa(centerCfa->data(), centerInfo,
                              w.prev ? w.prev->data() : nullptr,
                              w.next ? w.next->data() : nullptr,
                              s.rawMeta(), ts, merged->data(), nullptr);
    e.mergedFrameIndex = frameIndex;
    e.mergedStrength = strength;
    e.mergedCfa = CfaBuffer(std::move(merged));
    return e.mergedCfa;
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
        // Drop the caches with the Source they belong to (a reopened handle
        // starts fresh) and return their memory.
        clearEntryCaches(e);
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
        jboolean temporalNr, jfloat temporalStrength,
        jint maxDim) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 1. Frame read — through the ring, so with temporal NR on the
        //    center joins its neighbors in one LRU (the merge reads them
        //    all back from it; with NR off the ring is cleared by
        //    temporalMergedCfa and this was a plain decode).
        Entry& e = gEntries[handle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Temporal NR on the CFA (pristine buffer back when off).
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            temporalNr == JNI_TRUE, temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        // 3-4. Develop with CFA decimation + ARGB (shared preview tail).
        return developToArgb(env, *cfa, info, meta,
                             gradeFromKotlin(exposureEv, highlights, tempK, tint,
                                             saturation, blackPoint, whitePoint,
                                             shadows, midtones, contrast,
                                             asShot, highlightReconstruction,
                                             keepVignetting, sharpness, detail,
                                             tonemap, temporalNr == JNI_TRUE,
                                             temporalStrength),
                             maxDim);
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
        jboolean temporalNr, jfloat temporalStrength,
        jint maxDim) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
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
        // 1. Decode only when the requested frame is not the cached one —
        //    through the ring, so a neighbor prefetched during the previous
        //    frame's merge becomes this frame's center with no decode (with
        //    temporal NR off the ring is cleared right after; the shared
        //    buffer survives in the interactive cache).
        if (frameIndex != e.cachedFrameIndex) {
            const CfaBuffer buf = loadRingFrame(*s, e, frameIndex);
            if (buf == nullptr) return nullptr; // decode failed (error set)
            e.cachedCfa = buf;
            e.cachedInfo = s->frameInfo(static_cast<size_t>(frameIndex));
            e.cachedFrameIndex = frameIndex;
        }
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Temporal NR on the CFA. The interactive cache stays PRISTINE
        //    (temporal output never re-enters as input); the merge reads
        //    the center from the cache's shared buffer via the ring.
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, e.cachedCfa, e.cachedInfo,
            temporalNr == JNI_TRUE, temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        // 3-4. Develop the (possibly merged) CFA + ARGB (shared preview tail).
        return developToArgb(env, *cfa, e.cachedInfo, meta,
                             gradeFromKotlin(exposureEv, highlights, tempK, tint,
                                             saturation, blackPoint, whitePoint,
                                             shadows, midtones, contrast,
                                             asShot, highlightReconstruction,
                                             keepVignetting, sharpness, detail,
                                             tonemap, temporalNr == JNI_TRUE,
                                             temporalStrength),
                             maxDim);
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
        jboolean temporalNr, jfloat temporalStrength) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageFrameRead;
    rawedit::Source* s = sourceFor(handle);
    if (!s) {
        setError(static_cast<int>(rawedit::ErrorCode::IoError), kStageFrameRead,
                 gLastError);
        return nullptr;
    }
    try {
        // 1. Frame read — through the ring, like the preview paths (sequential
        //    export amortizes to ~1 decode per frame with temporal NR on).
        Entry& e = gEntries[handle - 1];
        const CfaBuffer center = loadRingFrame(*s, e, frameIndex);
        if (center == nullptr) return nullptr; // decode failed (error set)
        const rawedit::FrameInfo info = s->frameInfo(static_cast<size_t>(frameIndex));
        const rawedit::RawMeta& meta = s->rawMeta();

        // 2. Temporal NR on the CFA (pristine buffer back when off).
        const CfaBuffer cfa = temporalMergedCfa(
            *s, e, frameIndex, center, info,
            temporalNr == JNI_TRUE, temporalStrength);
        if (cfa == nullptr) return nullptr; // neighbor decode failed

        // 3. Full-res develop with the job's grade snapshot (core/Develop.h).
        gLastErrorStage = kStageDevelop;
        rawedit::ImageF img = rawedit::developCpu(
            cfa->data(), info, meta,
            gradeFromKotlin(exposureEv, highlights, tempK, tint,
                            saturation, blackPoint, whitePoint,
                            shadows, midtones, contrast,
                            asShot, highlightReconstruction, keepVignetting,
                            sharpness, detail, tonemap,
                            temporalNr == JNI_TRUE, temporalStrength));
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
// exposure -> sharpness/detail/tonemap, the shared COL-15/COL-16 stages) ->
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
        jboolean temporalNr, jfloat temporalStrength) {
    gLastErrorCode = 0;
    gLastErrorStage = kStageDevelop;
    // Temporal NR (DN-05..DN-09) is a CFA-frame stage; the encoded path has
    // no CFA and no neighbor frames available at this seam, so the two
    // fields travel for signature parity (one signature across every
    // develop entry point) and are ignored here. developEncoded never reads
    // them; the encoded UI keeps the control MCRAW-gated.

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
                            sharpness, detail, tonemap,
                            temporalNr == JNI_TRUE, temporalStrength),
            isBt709 != JNI_FALSE);
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
