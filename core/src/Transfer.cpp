// Mi-Log transfer curves + encoded-frame develop. See rawedit/Transfer.h
// for the interface contract.

#include <rawedit/Transfer.h>

#include <algorithm>
#include <cmath>
#include <rawedit/Lut.h>

namespace rawedit {

// Constants copied verbatim from
// third_party/xiaomi-milog/Mi-Log_to_ACES_AP0.dctl, mlog_to_lin().
namespace {
const float kR0 = -0.09023729f;
const float kRt = 0.01974185f;
const float kA  = 0.09271529f;
const float kB  = 0.01384578f;
const float kG  = 0.67291850f;
const float kC  = 18.10531998f;
const float kPt = kC * (kRt - kR0) * (kRt - kR0); // c*(R_t - R_0)^2
} // namespace

float miLogToLinear(float x) {
    if (x < 0.0f) return kR0;
    if (x >= kPt) {
        return std::pow(2.0f, (x - kG) / kA) - kB;
    }
    return std::sqrt(x / kC) + kR0;
}

float linearToRec709(float lin) {
    if (lin < 0.018f) return 4.5f * lin;
    return 1.099f * std::pow(lin, 0.45f) - 0.099f;
}

// BT.601 limited-range YUV -> full-range RGB (JFIF-style coefficients).
namespace {
void yuv601ToRgb(float y, float u, float v, float& r, float& g, float& b) {
    const float yc = (y - 16.0f) / 219.0f;
    const float cb = (u - 128.0f) / 224.0f;
    const float cr = (v - 128.0f) / 224.0f;
    r = yc                + 1.402f  * cr;
    g = yc - 0.344136f * cb - 0.714136f * cr;
    b = yc + 1.772f   * cb;
}
} // namespace

// BT.709 limited-range YUV -> full-range RGB.
namespace {
void yuv709ToRgb(float y, float u, float v, float& r, float& g, float& b) {
    const float yc = (y - 16.0f) / 219.0f;
    const float cb = (u - 128.0f) / 224.0f;
    const float cr = (v - 128.0f) / 224.0f;
    r = yc                + 1.5748f  * cr;
    g = yc - 0.187324f * cb - 0.468124f * cr;
    b = yc + 1.8556f   * cb;
}
} // namespace

// Noise reduction (DN-01..DN-08, spatial half), detail controls (COL-15)
// and tonemapping (COL-16), shared with the MCRAW path. All genuinely apply
// to a decoded, linearized encoded frame: denoise is a spatial operator on
// the developed RGB (the codec's noise lives in those pixels exactly like
// sensor noise lives in the MCRAW path — and the detail stages below would
// amplify it otherwise), sharpness/detail are spatial operators too (they
// do not care where the pixels came from), and tonemapping compresses the
// linear scene range the Mi-Log decode produced. The implementations live
// in Develop.cpp (denoiseChroma / denoiseLuma / unsharpLuma / tonemapFrame);
// see Develop.h for the full DN-01..08/COL-15/COL-16 record (radii, sigma_r,
// amount mapping, ACES/AgX versions, working space, target display).
namespace {
void encodedDenoiseStages(ImageF& img, const Grade& grade) {
    // Same guards and strength clamps as developCpu stages 7-8.
    if (grade.chromaDenoise != 0.0f)
        denoiseChroma(img, std::min(std::max(grade.chromaDenoise, 0.0f), 100.0f) / 100.0f);
    if (grade.noiseReduction != 0.0f)
        denoiseLuma(img, std::min(std::max(grade.noiseReduction, 0.0f), 100.0f) / 100.0f);
}

void encodedDetailStages(ImageF& img, const Grade& grade) {
    if (grade.sharpness != 0.0f) {
        const float amount =
            std::min(std::max(0.5f * (grade.sharpness / 100.0f), -0.5f), 0.5f);
        unsharpLuma(img, 1.5f, amount);
    }
    if (grade.detail != 0.0f) {
        const float amount =
            std::min(std::max(0.5f * (grade.detail / 100.0f), -0.5f), 0.5f);
        unsharpLuma(img, 0.75f, amount);
    }
}

void encodedTonemapStage(ImageF& img, const Grade& grade) {
    if (grade.tonemap != 0)
        tonemapFrame(img, grade.tonemap);
}

void encodedLutStage(ImageF& img, const Grade& grade, const Lut* lut) {
    if (lut != nullptr && grade.lutStrength != 0.0f)
        applyLut(img, *lut, grade.lutStrength);
}
} // namespace

ImageF developEncoded(const YuvFrame& frame, const Grade& grade, bool isBt709, const Lut* lut) {
    ImageF out;
    out.w = frame.width;
    out.h = frame.height;
    out.rgb.assign(static_cast<size_t>(out.w) * out.h * 3, 0.0f);
    if (frame.width == 0 || frame.height == 0) return out;

    const int stride = frame.stride > 0 ? frame.stride : static_cast<int>(frame.width);
    const int uvStride = frame.uvStride > 0 ? frame.uvStride
                                            : static_cast<int>(frame.width / 2);

    // WB/temp/tint gains: as-shot neutral gains plus the temp/tint offsets,
    // matching the Grade semantics of the MCRAW path (gains >= 1 scale the
    // channel; exposure is applied as 2^ev in the per-pixel loop below).
    float gains[3] = {1.0f, 1.0f, 1.0f};
    // temp/tint: +100 temp warms (R up, B down); +100 tint magenta (R/B up,
    // G down). Range in Grade is unbounded; the UI clamps.
    const float t = grade.tempK - 5600.0f;
    const float ti = grade.tint;
    gains[0] = 1.0f + t * 0.002f + ti * 0.002f;
    gains[1] = 1.0f - ti * 0.002f;
    gains[2] = 1.0f - t * 0.002f + ti * 0.002f;
    const float exposure = std::pow(2.0f, grade.exposureEv);

    for (size_t row = 0; row < out.h; ++row) {
        const size_t uvRow = row / 2;
        for (size_t col = 0; col < out.w; ++col) {
            const size_t uvCol = col / 2;
            const float yv = frame.y[row * stride + col];
            float u = 128.0f, v = 128.0f;
            if (frame.semiPlanar) {
                u = frame.u[uvRow * uvStride + uvCol * 2];
                v = frame.u[uvRow * uvStride + uvCol * 2 + 1];
            } else {
                u = frame.u[uvRow * uvStride + uvCol];
                v = frame.v[uvRow * uvStride + uvCol];
            }
            float r, g, b;
            if (isBt709)
                yuv709ToRgb(yv, u, v, r, g, b);
            else
                yuv601ToRgb(yv, u, v, r, g, b);
            // Mi-Log decode per channel.
            r = miLogToLinear(r);
            g = miLogToLinear(g);
            b = miLogToLinear(b);
            // Grade: WB/temp/tint gains then exposure.
            r = r * gains[0] * exposure;
            g = g * gains[1] * exposure;
            b = b * gains[2] * exposure;
            float* px = &out.rgb[(row * out.w + col) * 3];
            px[0] = r;
            px[1] = g;
            px[2] = b;
        }
    }

    // ---- Noise reduction (DN-01..08), detail (COL-15), tonemap (COL-16),
    // LUT (COL-07). ----
    // The same stages 7-8 and 19-22 the MCRAW path runs, on the decoded and
    // graded RGB: chroma denoise (radius 2 px) and luma denoise (3x3
    // bilateral) FIRST — before the sharpening stages amplify what noise
    // survives — then sharpness (radius 1.5 px) and detail (radius 0.75 px)
    // as luminance-only unsharp masks, then the optional ACES/AgX tonemap,
    // then the optional .cube LUT. All genuinely apply to an encoded clip:
    // its pixels are ordinary linear RGB once the Mi-Log decode and grade
    // have run. Neutral grades skip every stage (exact passthrough, the
    // old look).
    encodedDenoiseStages(out, grade);
    encodedDetailStages(out, grade);
    encodedTonemapStage(out, grade);
    encodedLutStage(out, grade, lut);

    return out;
}

} // namespace rawedit
