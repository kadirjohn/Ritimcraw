// encoded_stripe_frame.hpp — shared fixtures of the striped encoded-develop
// proofs (A7 night 2026-11-26): test_develop_stripes (doctest) and
// stripe_measure (the hand-run 8K equality/peak-memory driver). No fixtures
// on disk. The synthetic frame carries STRONG VERTICAL variation (per-row
// gradient + sinusoids + deterministic LCG noise, chroma included) so any
// stripe phase or margin error shows up as a byte diff. The grade exercises
// every WINDOWED stage of developEncoded (chroma denoise +-4 rows, luma
// denoise +-1, sharpness/detail +-2) plus tonemap and exposure/temp/tint.
#pragma once

#include <rawedit/ColorModel.h>
#include <rawedit/Develop.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace stripefix {

// One synthetic NV12 (8-bit) or P010 (10-bit, 16-bit MSB-aligned words)
// frame, limited range, Mi-Log, BT.709 matrix — the encoded default shape.
inline rawedit::YuvFrame makeFrame(uint32_t w, uint32_t h, uint32_t seed = 1, int bitDepth = 8) {
    rawedit::YuvFrame f;
    f.width = w;
    f.height = h;
    f.semiPlanar = true;
    f.bitDepth = bitDepth;
    const int bps = bitDepth > 8 ? 2 : 1;
    f.stride = (int)w * bps;
    f.uvStride = (int)(((w + 1) / 2) * 2) * bps;
    f.y.assign((size_t)h * (size_t)f.stride, 0);
    f.u.assign((size_t)((h + 1) / 2) * (size_t)f.uvStride, 0);
    const bool wide = bitDepth > 8;

    // Writes one code value at a byte index (8-bit: a byte; 10-bit: the
    // MSB-aligned little-endian 16-bit word).
    const auto put = [&](std::vector<uint8_t>& buf, size_t at, float code) {
        const int c = (int)(code < 0.0f ? 0.0f : code > 1023.0f ? 1023.0f : code);
        if (!wide) {
            buf[at] = (uint8_t)c;
        } else {
            const unsigned word = (unsigned)c << 6;
            buf[at] = (uint8_t)(word & 0xFFu);
            buf[at + 1] = (uint8_t)((word >> 8) & 0xFFu);
        }
    };

    uint32_t s = seed * 2654435761u + 7u;
    const auto rnd = [&]() {
        s = s * 1664525u + 1013904223u;
        return (float)((s >> 16) & 0xFFu);
    };
    const float scale = wide ? 4.0f : 1.0f; // 10-bit codes are 4x the 8-bit range
    for (uint32_t r = 0; r < h; ++r) {
        const float t = h > 1 ? (float)r / (float)(h - 1) : 0.0f;
        for (uint32_t c = 0; c < w; ++c) {
            const float u = w > 1 ? (float)c / (float)(w - 1) : 0.0f;
            float v = (40.0f + 170.0f * t + 20.0f * std::sin(t * 25.0f)
                        + 10.0f * std::sin(u * 31.0f + t * 47.0f) + rnd() * (15.0f / 255.0f))
                       * scale;
            put(f.y, (size_t)r * (size_t)f.stride + (size_t)c * bps, std::min(v, wide ? 1023.0f : 255.0f));
        }
    }
    const uint32_t ch = (h + 1) / 2, cw = (w + 1) / 2;
    for (uint32_t r = 0; r < ch; ++r) {
        const float t = ch > 1 ? (float)r / (float)(ch - 1) : 0.0f;
        for (uint32_t c = 0; c < cw; ++c) {
            const float u = cw > 1 ? (float)c / (float)(cw - 1) : 0.0f;
            float uu = (128.0f + 60.0f * std::sin(t * 19.0f) + 25.0f * std::cos(u * 17.0f - t * 29.0f)
                         + rnd() * (7.0f / 255.0f) - 3.5f) * scale;
            float vv = (128.0f + 60.0f * std::cos(t * 23.0f) + 25.0f * std::sin(u * 13.0f + t * 37.0f)
                         + rnd() * (7.0f / 255.0f) - 3.5f) * scale;
            const float lim = wide ? 1023.0f : 255.0f;
            const size_t at = (size_t)r * (size_t)f.uvStride + (size_t)c * 2 * bps;
            put(f.u, at, std::min(std::max(uu, wide ? 64.0f : 16.0f), lim));
            put(f.u, at + bps, std::min(std::max(vv, wide ? 64.0f : 16.0f), lim));
        }
    }
    return f;
}

// The windowed-stage-heavy grade (the case where margins are load-bearing).
inline rawedit::Grade stageHeavyGrade() {
    rawedit::Grade g;
    g.exposureEv = 0.7f;
    g.tempK = 6100.0f;
    g.tint = -13.0f;
    g.chromaDenoise = 37.0f;
    g.noiseReduction = 23.0f;
    g.sharpness = 63.0f;
    g.detail = -41.0f;
    g.tonemap = 2;
    return g;
}

// A view of source rows [top, bot): fresh plane buffers holding exactly
// those rows, widths/strides/shape preserved (chroma rows [top/2,
// (bot+1)/2)). This is the bounded per-stripe copy the JNI striped oracle
// performs with GetByteArrayRegion — the doctest and the driver go through
// the SAME shape to prove exactly what the device path runs.
inline rawedit::YuvFrame sliceRows(const rawedit::YuvFrame& f, uint32_t top, uint32_t bot) {
    rawedit::YuvFrame v;
    v.width = f.width;
    v.height = bot - top;
    v.semiPlanar = f.semiPlanar;
    v.stride = f.stride;
    v.uvStride = f.uvStride;
    v.bitDepth = f.bitDepth;
    v.fullRange = f.fullRange;
    v.matrix = f.matrix;
    v.transfer = f.transfer;
    v.primaries = f.primaries;
    v.y.assign(f.y.begin() + (size_t)top * (size_t)f.stride,
               f.y.begin() + (size_t)bot * (size_t)f.stride);
    const uint32_t cTop = top / 2, cBot = (bot + 1) / 2;
    v.u.assign(f.u.begin() + (size_t)cTop * (size_t)f.uvStride,
               f.u.begin() + (size_t)cBot * (size_t)f.uvStride);
    if (f.semiPlanar) {
        // keep v empty: developEncoded reads interleaved UV from u only
    } else {
        v.v.assign(f.v.begin() + (size_t)cTop * (size_t)f.uvStride,
                   f.v.begin() + (size_t)cBot * (size_t)f.uvStride);
    }
    return v;
}

// The striped reference loop: develops the frame exactly like the JNI
// striped oracle — for each planned emit range [y0,y1), a kEncodedStripeMargin
// context view, developEncoded on it, keep the emitted rows — and assembles
// the full developed frame. The optional node chain (per-pixel, F1-6)
// composes per stripe exactly like the bridge's applyV2ChainCpu.
inline rawedit::ImageF developStriped(const rawedit::YuvFrame& frame, const rawedit::Grade& grade,
                                      bool isBt709, const rawedit::Lut* lut,
                                      const rawedit::CompiledLutBinding* binding, uint32_t stripeRows,
                                      const rawedit::GradeNode* chainNodes = nullptr,
                                      size_t chainCount = 0) {
    rawedit::ImageF out;
    out.w = frame.width;
    out.h = frame.height;
    out.rgb.assign((size_t)frame.width * (size_t)frame.height * 3, 0.0f);
    const uint32_t M = rawedit::kEncodedStripeMargin;
    for (const auto& range : rawedit::planEncodedStripes(frame.height, stripeRows)) {
        const uint32_t y0 = range.first, y1 = range.second;
        const uint32_t inTop = y0 > M ? y0 - M : 0;
        const uint32_t inBot = std::min(frame.height, y1 + M);
        rawedit::YuvFrame view = sliceRows(frame, inTop, inBot);
        rawedit::ImageF img = rawedit::developEncoded(view, grade, isBt709, lut, binding);
        if (chainNodes != nullptr && chainCount > 0)
            rawedit::applyNodes(img, chainNodes, chainCount, rawedit::NodesOutput::Rec709Linear);
        const size_t srcRow = y0 - inTop;
        for (uint32_t r = 0; r < y1 - y0; ++r) {
            std::copy_n(img.rgb.begin() + ((size_t)(srcRow + r) * (size_t)frame.width) * 3,
                        (size_t)frame.width * 3,
                        out.rgb.begin() + (((size_t)y0 + r) * (size_t)frame.width) * 3);
        }
    }
    return out;
}

// The develop tail the JNI bridge runs (linearToRec709 -> clamp -> round),
// as packed RGB888 — the driver hashes this shape so `equal` proves the
// FULL striped pipeline byte-identical to the whole-frame one.
inline void tailToRgb888(const rawedit::ImageF& img, std::vector<uint8_t>* rgb) {
    rgb->assign(img.rgb.size(), 0);
    for (size_t i = 0; i < img.rgb.size(); ++i) {
        float v = rawedit::linearToRec709(img.rgb[i]);
        if (v < 0.0f) v = 0.0f;
        if (v > 1.0f) v = 1.0f;
        (*rgb)[i] = (uint8_t)(v * 255.0f + 0.5f);
    }
}

} // namespace stripefix
