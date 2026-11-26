// Mi-Log transfer curves + encoded-frame develop. See rawedit/Transfer.h
// for the interface contract.

#include <rawedit/Transfer.h>

#include <algorithm>
#include <cmath>
#include <rawedit/Color.h>
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

// YCbCr -> RGB. The coefficients are the matrix's (Kr, Kb) standard forms:
//   BT.601 (JFIF-style) Kr .299  Kb .114   -> 1.402 / .344136 / .714136 / 1.772
//   BT.709            Kr .2126 Kb .0722  -> 1.5748 / .187324 / .468124 / 1.8556
//   BT.2020 NCL       Kr .2627 Kb .0593  -> 1.4746 / .164553 / .571353 / 1.8814
// y/u/v arrive as raw CODE values (8..16-bit); offsets and ranges are the
// frame's own (limited: 16/128 with 219/224 scaled to the depth; full: 0/
// 2^(bd-1) with 2^bd-1). With limited 8-bit the float arithmetic is the
// pre-E0 expression exactly, so legacy frames stay bit-identical.
namespace {
struct YuvCoeffs { float rV, gU, gV, bU; };
const YuvCoeffs kCoeff601  = {1.402f,  0.344136f, 0.714136f, 1.772f};
const YuvCoeffs kCoeff709  = {1.5748f, 0.187324f, 0.468124f, 1.8556f};
const YuvCoeffs kCoeff2020 = {1.4746f, 0.164553f, 0.571353f, 1.8814f};

struct YuvRange { float offY, rangeY, offC, rangeC; };

YuvRange rangeFor(int bitDepth, bool fullRange) {
    const float scale = static_cast<float>(1 << (bitDepth - 8));
    if (fullRange) {
        const float maxCode = static_cast<float>((1 << bitDepth) - 1);
        return {0.0f, maxCode, 128.0f * scale, maxCode};
    }
    return {16.0f * scale, 219.0f * scale, 128.0f * scale, 224.0f * scale};
}

void yuvToRgb(const YuvCoeffs& k, const YuvRange& rg, float y, float u, float v,
              float& r, float& g, float& b) {
    const float yc = (y - rg.offY) / rg.rangeY;
    const float cb = (u - rg.offC) / rg.rangeC;
    const float cr = (v - rg.offC) / rg.rangeC;
    r = yc                + k.rV * cr;
    g = yc - k.gU * cb - k.gV * cr;
    b = yc + k.bU * cb;
}

// One sample's CODE value: a byte (8-bit) or the high bits of a 16-bit LE
// word (P010-style MSB alignment).
inline float sampleAt(const std::vector<uint8_t>& buf, size_t byteIndex, bool wide, int shift) {
    if (!wide) return static_cast<float>(buf[byteIndex]);
    const unsigned word = static_cast<unsigned>(buf[byteIndex]) |
                          (static_cast<unsigned>(buf[byteIndex + 1]) << 8);
    return static_cast<float>(word >> shift);
}

float decodeEncodedTransfer(float x, EncodedTransfer t) {
    switch (t) {
        case EncodedTransfer::MiLog:  return miLogToLinear(x);
        case EncodedTransfer::Rec709: return decodeTransfer(x, TransferId::Rec709OETF);
        case EncodedTransfer::Srgb:   return decodeTransfer(x, TransferId::Srgb);
        case EncodedTransfer::Linear: return x;
    }
    return miLogToLinear(x);
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

// The tonemap + LUT pair of developCpu stages 23/24, routed through
// applyManagedLutStage (rawedit/Color.h) so both pipelines stay in
// lockstep: with binding == nullptr (every existing call site and every
// pre-management record) the router runs the legacy pair verbatim —
// tonemapFrame then applyLut, byte-identical to the old sequence.
void encodedRenderStage(ImageF& img, const Grade& grade,
                        const Lut* lut, const CompiledLutBinding* binding) {
    applyManagedLutStage(img, lut, binding, grade.tonemap, grade.lutStrength);
}
} // namespace

ImageF developEncoded(const YuvFrame& frame, const Grade& grade, bool isBt709, const Lut* lut, const CompiledLutBinding* binding) {
    ImageF out;
    out.w = frame.width;
    out.h = frame.height;
    out.rgb.assign(static_cast<size_t>(out.w) * out.h * 3, 0.0f);
    if (frame.width == 0 || frame.height == 0) return out;

    const bool wide = frame.bitDepth > 8;
    const int bps = wide ? 2 : 1;                       // bytes per sample
    const int shift = wide ? (16 - frame.bitDepth) : 0; // MSB-aligned words
    const size_t cw = (static_cast<size_t>(frame.width) + 1) / 2;
    const int stride = frame.stride > 0 ? frame.stride
                                        : static_cast<int>(frame.width) * bps;
    const int uvStride = frame.uvStride > 0
        ? frame.uvStride
        : static_cast<int>(cw) * (frame.semiPlanar ? 2 : 1) * bps;

    const YuvRange range = rangeFor(wide ? frame.bitDepth : 8, frame.fullRange);
    const YuvCoeffs* coeffs = &kCoeff709;
    switch (frame.matrix) {
        case YuvMatrix::BT601:  coeffs = &kCoeff601; break;
        case YuvMatrix::BT709:  coeffs = &kCoeff709; break;
        case YuvMatrix::BT2020: coeffs = &kCoeff2020; break;
        case YuvMatrix::FromFlag: coeffs = isBt709 ? &kCoeff709 : &kCoeff601; break;
    }

    // BT.2020 -> Rec.709 working space, in linear light after the transfer
    // decode (the plan order YcbcrToRgb -> DecodeTransfer -> GamutToWorking).
    const bool gamut = frame.primaries == EncodedPrimaries::BT2020;
    float gm[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    if (gamut) gamutToGamut(GamutId::Rec2020, GamutId::Rec709, gm);

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
            const float yv = sampleAt(frame.y, row * stride + col * bps, wide, shift);
            float u = range.offC, v = range.offC;
            if (frame.semiPlanar) {
                const size_t at = uvRow * uvStride + uvCol * 2 * bps;
                u = sampleAt(frame.u, at, wide, shift);
                v = sampleAt(frame.u, at + bps, wide, shift);
            } else {
                const size_t at = uvRow * uvStride + uvCol * bps;
                u = sampleAt(frame.u, at, wide, shift);
                v = sampleAt(frame.v, at, wide, shift);
            }
            float r, g, b;
            yuvToRgb(*coeffs, range, yv, u, v, r, g, b);
            // Transfer decode per channel (Mi-Log unless the frame says otherwise).
            r = decodeEncodedTransfer(r, frame.transfer);
            g = decodeEncodedTransfer(g, frame.transfer);
            b = decodeEncodedTransfer(b, frame.transfer);
            if (gamut) {
                const float rr = gm[0] * r + gm[1] * g + gm[2] * b;
                const float gg = gm[3] * r + gm[4] * g + gm[5] * b;
                const float bb = gm[6] * r + gm[7] * g + gm[8] * b;
                r = rr; g = gg; b = bb;
            }
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
    // The same stages 7-8 and 21-24 the MCRAW path runs, on the decoded and
    // graded RGB: chroma denoise (radius 2 px) and luma denoise (3x3
    // bilateral) FIRST — before the sharpening stages amplify what noise
    // survives — then sharpness (radius 1.5 px) and detail (radius 0.75 px)
    // as luminance-only unsharp masks, then the optional ACES/AgX tonemap,
    // then the optional .cube LUT. All genuinely apply to an encoded clip:
    // its pixels are ordinary linear RGB once the Mi-Log decode and grade
    // have run. The tone-band color stages (9-20: black/white point,
    // shadows/midtones/highlights, wheel, split tone, saturation) stay
    // MCRAW-only — the encoded path's per-pixel WB/exposure loop covers its
    // grading needs, so the wheel/split fields travel in Grade for
    // signature parity and are ignored here. Neutral grades skip every
    // stage (exact passthrough, the old look).
    encodedDenoiseStages(out, grade);
    encodedDetailStages(out, grade);
    encodedRenderStage(out, grade, lut, binding);

    return out;
}

// A7 night 2026-11-26: the striped emit plan (see Transfer.h). Row counts
// are floored to even (the chroma phase — an odd boundary would shift the
// chroma by one row) with a floor of 2; the ranges tile [0, height) exactly.
std::vector<std::pair<uint32_t, uint32_t>> planEncodedStripes(uint32_t height, uint32_t stripeRows) {
    std::vector<std::pair<uint32_t, uint32_t>> out;
    if (height == 0)
        return out;
    uint32_t rows = stripeRows & ~1u;
    if (rows < 2)
        rows = 2;
    for (uint32_t y0 = 0; y0 < height; y0 += rows)
        out.emplace_back(y0, std::min(y0 + rows, height));
    return out;
}

} // namespace rawedit
