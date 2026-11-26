#include <rawedit/Thumbnail.h>

#include <cmath>

#include <algorithm>
#include <vector>

#include <rawedit/Parallel.h>

namespace rawedit {

uint32_t thumbnailWidth(uint32_t width, int strideEvery) {
    if (width < 2 || strideEvery < 1)
        return 0;
    const uint32_t cellCols = width / 2;
    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t sampled = (cellCols + n - 1) / n; // ceil
    return 2 * sampled;
}

uint32_t thumbnailHeight(uint32_t height, int strideEvery) {
    if (height < 2 || strideEvery < 1)
        return 0;
    const uint32_t cellRows = height / 2;
    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t sampled = (cellRows + n - 1) / n; // ceil
    return 2 * sampled;
}

bool reduceCfaBinned(const uint16_t* cfa, uint32_t width, uint32_t height,
                     int strideEvery, std::vector<uint16_t>& out) {
    out.clear();
    if (cfa == nullptr || width < 2 || height < 2 || strideEvery < 1)
        return false;
    const uint32_t outW = thumbnailWidth(width, strideEvery);
    const uint32_t outH = thumbnailHeight(height, strideEvery);
    if (outW == 0 || outH == 0)
        return false;
    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t cellCols = width / 2;
    const uint32_t cellRows = height / 2;
    const uint32_t sampledCols = outW / 2;
    const uint32_t sampledRows = outH / 2;

    if (n == 1) {
        // Identity (the export guarantee): the first outW x outH samples,
        // which is the whole frame except an odd last row/column.
        out.resize(static_cast<size_t>(outW) * outH);
        for (uint32_t y = 0; y < outH; ++y)
            std::copy(cfa + static_cast<size_t>(y) * width,
                      cfa + static_cast<size_t>(y) * width + outW,
                      out.begin() + static_cast<size_t>(y) * outW);
        return true;
    }

    out.assign(static_cast<size_t>(outW) * outH, 0);
    // Per output cell row: accumulate the N source cell rows x 2 sample rows
    // into four per-column sums (the four sites), then divide. Rows are
    // independent, so they run in parallel.
    parallelForRows(sampledRows, [&](size_t rowBegin, size_t rowEnd) {
        std::vector<uint32_t> sum(static_cast<size_t>(sampledCols) * 4);
        for (size_t ocy = rowBegin; ocy < rowEnd; ++ocy) {
            std::fill(sum.begin(), sum.end(), 0u);
            const uint32_t cy0 = static_cast<uint32_t>(ocy) * n;
            const uint32_t cyCount = std::min(n, cellRows - cy0);
            for (uint32_t j = 0; j < cyCount; ++j) {
                for (uint32_t dy = 0; dy < 2; ++dy) {
                    const uint16_t* row =
                        cfa + static_cast<size_t>(2 * (cy0 + j) + dy) * width;
                    for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                        const uint32_t cx0 = ocx * n;
                        const uint32_t cxCount = std::min(n, cellCols - cx0);
                        const uint16_t* p = row + 2 * static_cast<size_t>(cx0);
                        uint32_t s0 = 0, s1 = 0;
                        for (uint32_t i = 0; i < cxCount; ++i) {
                            s0 += p[2 * i];
                            s1 += p[2 * i + 1];
                        }
                        sum[static_cast<size_t>(ocx) * 4 + dy * 2 + 0] += s0;
                        sum[static_cast<size_t>(ocx) * 4 + dy * 2 + 1] += s1;
                    }
                }
            }
            for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                const uint32_t cxCount = std::min(n, cellCols - ocx * n);
                const uint32_t cnt = cxCount * cyCount; // cells that exist in this block
                for (uint32_t dy = 0; dy < 2; ++dy) {
                    for (uint32_t dx = 0; dx < 2; ++dx) {
                        const uint32_t v = (sum[static_cast<size_t>(ocx) * 4 + dy * 2 + dx] + cnt / 2) / cnt;
                        out[(static_cast<size_t>(2 * ocy + dy) * outW) + (2 * ocx + dx)] =
                            static_cast<uint16_t>(v);
                    }
                }
            }
        }
    });
    return true;
}

bool reduceCfaBinnedReconstructed(const uint16_t* cfa, const FrameInfo& info,
                                  const RawMeta& meta, int strideEvery,
                                  bool keepVignetting,
                                  const RawLevelsOverride& rawLevels,
                                  std::vector<uint16_t>& out) {
    out.clear();
    const uint32_t width = info.width;
    const uint32_t height = info.height;
    if (cfa == nullptr || width < 2 || height < 2 || strideEvery < 1)
        return false;
    if (strideEvery == 1)
        return reduceCfaBinned(cfa, width, height, 1, out);

    const uint32_t outW = thumbnailWidth(width, strideEvery);
    const uint32_t outH = thumbnailHeight(height, strideEvery);
    if (outW == 0 || outH == 0)
        return false;

    int cellColor[4] = {0, 1, 1, 2};
    switch (info.cfa) {
        case CfaPattern::RGGB: cellColor[0] = 0; cellColor[1] = 1; cellColor[2] = 1; cellColor[3] = 2; break;
        case CfaPattern::GRBG: cellColor[0] = 1; cellColor[1] = 0; cellColor[2] = 2; cellColor[3] = 1; break;
        case CfaPattern::GBRG: cellColor[0] = 1; cellColor[1] = 2; cellColor[2] = 0; cellColor[3] = 1; break;
        case CfaPattern::BGGR: cellColor[0] = 2; cellColor[1] = 1; cellColor[2] = 1; cellColor[3] = 0; break;
    }

    RecoveryClipLevels lv = recoveryClipLevels(cfa, info, meta);
    for (int c = 0; c < 3; ++c) {
        if (rawLevels.overridesThreshold(c))
            lv.threshold[c] = rawLevels.threshold[c];
    }

    // Fast path: if no channel has a site reaching its clip threshold (and no
    // custom level override shifted the range), plain binning is bit-identical.
    if (rawLevels.empty()) {
        bool anyCanClip = false;
        for (int c = 0; c < 3; ++c) {
            if (lv.maxNorm[c] >= lv.threshold[c]) {
                anyCanClip = true;
                break;
            }
        }
        if (!anyCanClip)
            return reduceCfaBinned(cfa, width, height, strideEvery, out);
    }

    float white;
    float black[4];
    resolveRawLevels(rawLevels, info, meta, white, black);
    float range[4];
    for (int slot = 0; slot < 4; ++slot)
        range[slot] = white > black[slot] ? white - black[slot] : 1.0f;

    // Smallest raw code per slot whose normalized value reaches lv.threshold.
    uint16_t floorRaw[4];
    for (int slot = 0; slot < 4; ++slot) {
        const float thr = lv.threshold[cellColor[slot]];
        uint32_t lo = 0, hi = 65536;
        while (lo < hi) {
            const uint32_t mid = (lo + hi) / 2;
            const float normV = std::min(std::max((static_cast<float>(mid) - black[slot]) / range[slot], 0.0f), 1.0f);
            if (normV >= thr) hi = mid; else lo = mid + 1;
        }
        floorRaw[slot] = static_cast<uint16_t>(std::min<uint32_t>(lo, 65535u));
    }

    const float wbGain[3] = {
        meta.wbGains[0] > 0.0f ? meta.wbGains[0] : 1.0f,
        meta.wbGains[1] > 0.0f ? meta.wbGains[1] : 1.0f,
        meta.wbGains[2] > 0.0f ? meta.wbGains[2] : 1.0f,
    };
    const bool hasShading = keepVignetting && !meta.lensShading.empty()
                            && meta.shadingW > 0 && meta.shadingH > 0;
    const float du = width > 1 ? 1.0f / static_cast<float>(width - 1) : 0.0f;
    const float dv = height > 1 ? 1.0f / static_cast<float>(height - 1) : 0.0f;

    const uint32_t n = static_cast<uint32_t>(strideEvery);
    const uint32_t cellCols = width / 2;
    const uint32_t cellRows = height / 2;
    const uint32_t sampledCols = outW / 2;
    const uint32_t sampledRows = outH / 2;

    out.assign(static_cast<size_t>(outW) * outH, 0);
    parallelForRows(sampledRows, [&](size_t rowBegin, size_t rowEnd) {
        std::vector<uint32_t> sum(static_cast<size_t>(sampledCols) * 4);
        for (size_t ocy = rowBegin; ocy < rowEnd; ++ocy) {
            std::fill(sum.begin(), sum.end(), 0u);
            const uint32_t cy0 = static_cast<uint32_t>(ocy) * n;
            const uint32_t cyCount = std::min(n, cellRows - cy0);
            for (uint32_t j = 0; j < cyCount; ++j) {
                const uint32_t y0 = 2 * (cy0 + j);
                const uint16_t* row0 = cfa + static_cast<size_t>(y0) * width;
                const uint16_t* row1 = row0 + width;
                for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                    const uint32_t cx0 = ocx * n;
                    const uint32_t cxCount = std::min(n, cellCols - cx0);
                    uint32_t s0 = 0, s1 = 0, s2 = 0, s3 = 0;
                    for (uint32_t i = 0; i < cxCount; ++i) {
                        const uint32_t x0 = 2 * (cx0 + i);
                        const uint16_t r0 = row0[x0];
                        const uint16_t r1 = row0[x0 + 1];
                        const uint16_t r2 = row1[x0];
                        const uint16_t r3 = row1[x0 + 1];
                        // Fast path: unclipped source 2x2 cell (~98%+ of cells).
                        if (r0 < floorRaw[0] && r1 < floorRaw[1] &&
                            r2 < floorRaw[2] && r3 < floorRaw[3]) {
                            s0 += r0;
                            s1 += r1;
                            s2 += r2;
                            s3 += r3;
                            continue;
                        }
                        // Clipped source 2x2 cell: apply the exact stage-3 cell cap
                        // before binning so averaged bins stay neutral instead of
                        // carrying a green-clipped magenta deficit.
                        // Formula X (same rule as stage 3): the ceiling is the lowest post-WB level among the
                        // CLIPPED sites only; every site of the cell is capped to it, never raised.
                        const uint16_t raw[4] = {r0, r1, r2, r3};
                        float nval[4];
                        float sh[4] = {1.0f, 1.0f, 1.0f, 1.0f};
                        float ceiling = -1.0f;
                        for (int slot = 0; slot < 4; ++slot) {
                            nval[slot] = std::min(std::max(
                                (static_cast<float>(raw[slot]) - black[slot]) / range[slot],
                                0.0f), 1.0f);
                            if (hasShading) {
                                const uint32_t sx = x0 + static_cast<uint32_t>(slot & 1);
                                const uint32_t sy = y0 + static_cast<uint32_t>(slot >> 1);
                                sh[slot] = sampleShading(meta, static_cast<float>(sx) * du,
                                                         static_cast<float>(sy) * dv, slot);
                            }
                            if (raw[slot] >= floorRaw[slot]) {
                                const float level = nval[slot] * sh[slot] * wbGain[cellColor[slot]];
                                if (ceiling < 0.0f || level < ceiling)
                                    ceiling = level;
                            }
                        }
                        uint16_t cv[4] = {r0, r1, r2, r3};
                        for (int slot = 0; slot < 4; ++slot) {
                            const float denom = sh[slot] * wbGain[cellColor[slot]];
                            const float cap = denom > 0.0f ? std::min(std::max(ceiling / denom, 0.0f), 1.0f) : nval[slot];
                            if (nval[slot] > cap) {
                                const float cappedRaw = black[slot] + cap * range[slot];
                                const long rounded = std::lround(cappedRaw);
                                cv[slot] = static_cast<uint16_t>(std::min<long>(std::max<long>(rounded, 0L), 65535L));
                            }
                        }
                        s0 += cv[0];
                        s1 += cv[1];
                        s2 += cv[2];
                        s3 += cv[3];
                    }
                    uint32_t* dst = &sum[static_cast<size_t>(ocx) * 4];
                    dst[0] += s0;
                    dst[1] += s1;
                    dst[2] += s2;
                    dst[3] += s3;
                }
            }
            for (uint32_t ocx = 0; ocx < sampledCols; ++ocx) {
                const uint32_t cxCount = std::min(n, cellCols - ocx * n);
                const uint32_t cnt = cxCount * cyCount;
                const uint32_t* src = &sum[static_cast<size_t>(ocx) * 4];
                out[(static_cast<size_t>(2 * ocy + 0) * outW) + (2 * ocx + 0)] =
                    static_cast<uint16_t>((src[0] + cnt / 2) / cnt);
                out[(static_cast<size_t>(2 * ocy + 0) * outW) + (2 * ocx + 1)] =
                    static_cast<uint16_t>((src[1] + cnt / 2) / cnt);
                out[(static_cast<size_t>(2 * ocy + 1) * outW) + (2 * ocx + 0)] =
                    static_cast<uint16_t>((src[2] + cnt / 2) / cnt);
                out[(static_cast<size_t>(2 * ocy + 1) * outW) + (2 * ocx + 1)] =
                    static_cast<uint16_t>((src[3] + cnt / 2) / cnt);
            }
        }
    });
    return true;
}

ImageF developThumbnail(const uint16_t* cfa, const FrameInfo& info,
                        const RawMeta& meta, const Grade& grade, int strideEvery,
                        const Lut* lut, const CompiledLutBinding* binding,
                        const RawLevelsOverride& rawLevels) {
    ImageF img;

    const uint32_t w = info.width;
    const uint32_t h = info.height;
    if (cfa == nullptr || w < 2 || h < 2 || strideEvery < 1)
        return img;

    const uint32_t outW = thumbnailWidth(w, strideEvery);
    const uint32_t outH = thumbnailHeight(h, strideEvery);
    if (outW == 0 || outH == 0)
        return img;

    const bool reconstructBeforeBin =
        strideEvery > 1 && (grade.highlightReconstruction || grade.highlightRecovery);
    std::vector<uint16_t> small;
    const bool ok = reconstructBeforeBin
        ? reduceCfaBinnedReconstructed(cfa, info, meta, strideEvery, grade.keepVignetting, rawLevels, small)
        : reduceCfaBinned(cfa, w, h, strideEvery, small);
    if (!ok)
        return img;

    // Same pipeline, same order, no duplication: the reduced mosaic goes
    // through the full developCpu reference path. When clipped full-res cells
    // were already reconstructed before binning, skip the post-binning clip
    // passes so recoveryClipLevels cannot misread the capped R/B levels as a
    // new sensor saturation plateau.
    FrameInfo smallInfo = info;
    smallInfo.width = outW;
    smallInfo.height = outH;
    Grade effectiveGrade = grade;
    if (reconstructBeforeBin) {
        effectiveGrade.highlightReconstruction = false;
        effectiveGrade.highlightRecovery = false;
    }
    img = developCpu(small.data(), smallInfo, meta, effectiveGrade, lut, binding, rawLevels);
    return img;
}

float measureHighlightKneeRef(Source& src, int frames, int maxDim) {
    const size_t fc = src.frameCount();
    if (fc == 0 || frames < 1) return 0.0f;
    const int n = static_cast<int>(std::min<size_t>(static_cast<size_t>(frames), fc));
    const RawMeta meta = src.rawMeta();
    std::vector<float> pool;
    for (int i = 0; i < n; ++i) {
        const size_t fi = n == 1 ? fc / 2
            : static_cast<size_t>(static_cast<double>(i) * static_cast<double>(fc - 1) /
                                  static_cast<double>(n - 1) + 0.5);
        auto dec = src.readFrame(fi);
        if (!dec.isOk()) continue;
        const FrameInfo info = src.frameInfo(fi);
        int stride = 1;
        const long long longEdge = std::max<long long>(info.width, info.height);
        while (maxDim > 0 && longEdge / stride > maxDim && stride < 64) ++stride;
        const Grade neutral;  // stages 1-13 only
        const ImageF img = developThumbnail(dec.value->data(), info, meta, neutral, stride);
        if (img.w == 0 || img.h == 0) continue;
        appendLumaSamples(img, 1, pool);
    }
    if (pool.empty()) return 0.0f;
    const float ref = lumaQuantile(pool, kHighlightKneePercentile);
    return (std::isfinite(ref) && ref > 0.0f) ? ref : 0.0f;
}

} // namespace rawedit
