// GpuCropMap.h — the GPU window pass's crop/straighten frame plan (pure,
// no GL).
//
// 2026-11-23 owner request (guide A2 — crop/straighten on the GPU preview):
// today any active crop forces the whole preview to the CPU
// (EditorPreviewLogic.gpuPreviewEligible returns false), because the GPU
// window pass draws the UNCROPPED aspect-fit frame while PublishCrop
// resamples the published pixels on the CPU. A2 moves the crop INTO the
// window pass: WINDOW_FRAGMENT gains uCropOn/uCropRect/uCropOut/
// uCropZoom/uCropCos/uCropSin and maps each destination pixel through
// CropState.applyToArgb's exact inverse sampling map, with MANUAL
// bilinear (4x texelFetch) because the develop texture is pinned NEAREST
// (owner device evidence 2026-11-15, the white window).
//
// This header is the dims-dependent HALF of the mapping, the StatsRing.h
// precedent: GL code is Android-only, so the frame plan (the cropped
// output dims, the auto-zoom, the cos/sin pair) is decided and doctested
// here on desktop, and GlesPreviewSession.cpp only uploads the values.
// The crop params arrive ALREADY mapRect'ed by Kotlin (GpuCropMap.
// requestCrop — the mapping lives in CropState, single-sourced JVM-side;
// the JNI door carries [rectX, rectY, rectW, rectH, straightenDeg] with
// the rect in CONTAINER-upright fractions). Kotlin cannot own the
// dims-dependent geometry because the develop dims exist ONLY natively
// (the JNI bridge's CFA reduction computes them per render — Kotlin has
// only the previous frame's developW/H tail), which is why outW/outH,
// zoom and the trig pair live HERE, mirroring CropState's own formulas.
#pragma once

#include <cmath>
#include <cstdint>

namespace gles {

// CropState.kt's own clamps, mirrored natively (the JVM object stays the
// single source; these constants must agree with it — the doctest pins
// the documented zoom value against both).
constexpr float kCropStraightenMaxDeg = 15.0f;  // CropState.STRAIGHTEN_MAX_DEG
constexpr float kCropStraightenZoomMax = 8.0f;  // CropState.STRAIGHTEN_ZOOM_MAX

// The window pass's crop plan for ONE frame (what runJob computes right
// after the develop dims are known and uploads at the window/stats
// draws). Everything the shader's crop branch needs, nothing else.
struct CropFramePlan {
    bool active = false;  // uCropOn: false = the identity path (uCropOn 0)
    float rectX = 0.0f;   // uCropRect.xy: container-upright fractions
    float rectY = 0.0f;
    float rectW = 1.0f;   // uCropRect.zw
    float rectH = 1.0f;
    int outW = 0;         // uCropOut: the CROPPED output dims
    int outH = 0;
    float zoom = 1.0f;    // uCropZoom: the auto-zoom (>= 1)
    float cos = 1.0f;     // uCropCos: cos/sin of the (clamped) angle,
    float sin = 0.0f;     // uCropSin: computed from a DOUBLE theta cast
                          // to float — CropState's exact precision. GLSL's
                          // own sin/cos on the angle would drift.
};

// Round-half-away-from-zero for positive values — Kotlin's roundToInt
// (Math.rint is half-even; Kotlin roundToInt is half-away on the
// .5 boundary). outW/outH mirror CropState.outputDims (even=false: the
// preview never snaps even; the MP4 encoder path does, separately).
inline int cropRoundDim(float v) {
    return static_cast<int>(std::lround(v));
}

// The straighten auto-zoom — CropState.straightenZoom verbatim, natively:
// the smallest scale-up >= 1 for which every output corner's inverse-
// mapped sample stays inside the [uprightW] x [uprightH] frame; each
// corner axis gives one LINEAR LOWER bound on zoom, the max wins, the
// degenerate cases (0 deg, non-positive rect/frame) return 1.
inline float cropStraightenZoom(
    uint32_t uprightW, uint32_t uprightH,
    float rectX, float rectY, float rectW, float rectH,
    float deg) {
    // Same clamp as CropState (deg may arrive outside the slider range).
    float d = deg;
    if (d > kCropStraightenMaxDeg) d = kCropStraightenMaxDeg;
    if (d < -kCropStraightenMaxDeg) d = -kCropStraightenMaxDeg;
    if (uprightW == 0 || uprightH == 0 || d == 0.0f) return 1.0f;
    const float cx = (rectX + rectW * 0.5f) * static_cast<float>(uprightW);
    const float cy = (rectY + rectH * 0.5f) * static_cast<float>(uprightH);
    const float ax = rectW * static_cast<float>(uprightW) * 0.5f;
    const float ay = rectH * static_cast<float>(uprightH) * 0.5f;
    if (ax <= 0.0f || ay <= 0.0f) return 1.0f;
    const double theta = static_cast<double>(d) * 3.14159265358979323846 / 180.0;
    const double c = std::cos(theta);
    const double s = std::sin(theta);
    const float w = static_cast<float>(uprightW);
    const float h = static_cast<float>(uprightH);
    float zoom = 1.0f;
    const float sx[2] = {-1.0f, 1.0f};
    const float sy[2] = {-1.0f, 1.0f};
    for (int i = 0; i < 2; ++i) {
        for (int j = 0; j < 2; ++j) {
            // R(theta) * (dx, dy): where this output corner samples.
            const float dx = ax * sx[i];
            const float dy = ay * sy[j];
            const float tx = static_cast<float>(dx * c - dy * s);
            const float ty = static_cast<float>(dx * s + dy * c);
            if (tx > 0.0f) {
                const float z = tx / (w - cx);
                if (z > zoom) zoom = z;
            }
            if (tx < 0.0f) {
                const float z = -tx / cx;
                if (zoom < z) zoom = z;
            }
            if (ty > 0.0f) {
                const float z = ty / (h - cy);
                if (zoom < z) zoom = z;
            }
            if (ty < 0.0f) {
                const float z = -ty / cy;
                if (zoom < z) zoom = z;
            }
        }
    }
    if (zoom < 1.0f) zoom = 1.0f;
    if (zoom > kCropStraightenZoomMax) zoom = kCropStraightenZoomMax;
    return zoom;
}

// The neutral-set test (the shader's identity path): identical to
// GpuCropMap.requestCrop's null verdict and PublishCrop.plan's inline
// test — deg 0 AND the full rect. The cropActive FLAG is NOT an input:
// the caller folds it (planCrop: active = cropActive && !neutral), so
// the two inactive reasons (flag off vs neutral grade) stay distinct
// where they matter.
inline bool cropNeutral(const float cropParams[5]) {
    return cropParams[4] == 0.0f &&
        cropParams[0] == 0.0f && cropParams[1] == 0.0f &&
        cropParams[2] == 1.0f && cropParams[3] == 1.0f;
}

// The plan for one preview frame. The develop/upright dims ([uprightW] x
// [uprightH]) come from THIS frame's CFA reduction (the runJob draw-2
// swap); cropParams is the JNI door's [rectX, rectY, rectW, rectH, deg]
// with the rect ALREADY mapRect'ed into container-upright fractions.
// An inactive/neutral request plans the identity (active=false, the
// full-frame out dims, zoom 1) — the session still uploads the uniform
// values, the shader's uCropOn == 0 gate never reads them.
inline CropFramePlan planCrop(
    uint32_t uprightW, uint32_t uprightH,
    bool cropActive, const float cropParams[5]) {
    CropFramePlan p;
    p.rectX = cropParams[0];
    p.rectY = cropParams[1];
    p.rectW = cropParams[2];
    p.rectH = cropParams[3];
    // Degenerate frame guard: no aspect math on an empty texture.
    if (uprightW == 0 || uprightH == 0) {
        p.active = false;
        p.outW = 0;
        p.outH = 0;
        p.zoom = 1.0f;
        p.cos = 1.0f;
        p.sin = 0.0f;
        return p;
    }
    // The deg clamp (CropState's own): the angle may arrive outside the
    // slider range (a stale grade snapshot) — clamp BEFORE the trig so
    // the shader's rotation matches the CPU reference exactly.
    float deg = cropParams[4];
    if (deg > kCropStraightenMaxDeg) deg = kCropStraightenMaxDeg;
    if (deg < -kCropStraightenMaxDeg) deg = -kCropStraightenMaxDeg;
    // The active verdict: the request must carry cropActive AND a
    // non-neutral crop group (straighten OR a sub-rect) — the exact
    // PublishCrop.plan inline test, so a neutral grade can never pay the
    // crop path even if a stale cropActive flag crosses the seam.
    p.active = cropActive && !cropNeutral(cropParams);
    // The cropped output dims: CropState.outputDims (even=false — the
    // preview path; round-half-away == Kotlin roundToInt), min 1. The
    // identity plan keeps the FULL upright dims, so the session's
    // crop-off verbatim paths (aspect rect, stats dims) read plan.outW/
    // outH the same way for both arms.
    p.outW = cropRoundDim(p.rectW * static_cast<float>(uprightW));
    p.outH = cropRoundDim(p.rectH * static_cast<float>(uprightH));
    if (p.outW < 1) p.outW = 1;
    if (p.outH < 1) p.outH = 1;
    if (!p.active) {
        // Identity: full rect regardless of cropParams (the neutral-set
        // test passed), zoom 1, cos/sin the 0-angle pair.
        p.rectX = 0.0f;
        p.rectY = 0.0f;
        p.rectW = 1.0f;
        p.rectH = 1.0f;
        p.outW = static_cast<int>(uprightW);
        p.outH = static_cast<int>(uprightH);
        p.zoom = 1.0f;
        p.cos = 1.0f;
        p.sin = 0.0f;
        return p;
    }
    // The zoom (the auto-zoom) and the trig pair — CropState's own
    // double-theta-then-float precision, the CropState KDoc's documented
    // value (full rect, 1920x1080, 15 deg -> ~1.4260) is doctested so
    // the C++ twin and the Kotlin object provably implement the same
    // formula.
    p.zoom = cropStraightenZoom(
        uprightW, uprightH, p.rectX, p.rectY, p.rectW, p.rectH, deg);
    const double theta = static_cast<double>(deg) * 3.14159265358979323846 / 180.0;
    p.cos = static_cast<float>(std::cos(theta));
    p.sin = static_cast<float>(std::sin(theta));
    if (deg == 0.0f) {
        // The CPU twin's 0-degree arm (CropState.applyToArgb) does not resample: it
        // copies a WHOLE-PIXEL window (pixelRect: round x/y, round w/h, shift to fit;
        // x0 = min(px.x, width - outW)). The continuous rect mapped here lands between
        // texels whenever x*W, y*H, w*W or h*H is fractional, which the device crop sweep
        // measured as up to 57 LSB / 78% of pixels on the same frame (2026-11-25;
        // a rect on whole pixels read 0 LSB, the same rect moved half a pixel 53 LSB).
        // Snap the rect to that window so the preview shows exactly the pixels the CPU
        // preview and the export copy; rotated crops keep the continuous map.
        const int W = static_cast<int>(uprightW);
        const int H = static_cast<int>(uprightH);
        auto clampi = [](int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); };
        int x = clampi(cropRoundDim(p.rectX * static_cast<float>(W)), 0, W - 1);
        int y = clampi(cropRoundDim(p.rectY * static_cast<float>(H)), 0, H - 1);
        int w = cropRoundDim(p.rectW * static_cast<float>(W));
        int h = cropRoundDim(p.rectH * static_cast<float>(H));
        if (w < 1) w = 1;
        if (h < 1) h = 1;
        if (x + w > W) x = (W - w > 0) ? W - w : 0;
        if (y + h > H) y = (H - h > 0) ? H - h : 0;
        int x0 = x < W - p.outW ? x : W - p.outW;
        if (x0 < 0) x0 = 0;
        p.rectX = static_cast<float>(x0) / static_cast<float>(W);
        p.rectY = static_cast<float>(y) / static_cast<float>(H);
        p.rectW = static_cast<float>(p.outW) / static_cast<float>(W);
        p.rectH = static_cast<float>(p.outH) / static_cast<float>(H);
    }
    return p;
}

}  // namespace gles
