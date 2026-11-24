// P5 (2026-11-25) diagnostic: one real MCRAW frame, reduced to the preview
// tier the OLD way (one cell per NxN block, copied verbatim) and the NEW way
// (rawedit::reduceCfaBinned), both developed with the same developCpu and
// written as 8-bit PPM, so the aliasing / sand the owner saw while playing
// can be compared on the SAME frame. Not part of the gate.
//
//   p5_compare <clip.mcraw> <frameIndex> <maxDim> <outPrefix>
#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace rawedit;

static int strideFor(const FrameInfo& f, int cap) {
    int s = std::max(1, int((std::max(f.width, f.height) + cap - 1) / cap));
    while (std::max(thumbnailWidth(f.width, s), thumbnailHeight(f.height, s)) > unsigned(cap)) s++;
    return s;
}

// The pre-P5 reduction, kept here only for the comparison.
static std::vector<uint16_t> oldDecimate(const std::vector<uint16_t>& cfa, uint32_t w, uint32_t h, int n) {
    const uint32_t outW = thumbnailWidth(w, n), outH = thumbnailHeight(h, n);
    std::vector<uint16_t> small(size_t(outW) * outH);
    for (uint32_t ocy = 0; ocy < outH / 2; ++ocy)
        for (uint32_t ocx = 0; ocx < outW / 2; ++ocx)
            for (int dy = 0; dy < 2; ++dy)
                for (int dx = 0; dx < 2; ++dx)
                    small[size_t(2 * ocy + dy) * outW + 2 * ocx + dx] =
                        cfa[size_t(2 * ocy * n + dy) * w + (2 * ocx * n + dx)];
    return small;
}

static void writePpm(const std::string& path, const ImageF& img) {
    FILE* f = std::fopen(path.c_str(), "wb");
    std::fprintf(f, "P6\n%u %u\n255\n", img.w, img.h);
    std::vector<unsigned char> row(size_t(img.w) * 3);
    for (uint32_t y = 0; y < img.h; ++y) {
        for (uint32_t x = 0; x < img.w; ++x)
            for (int c = 0; c < 3; ++c) {
                float v = img.rgb[(size_t(y) * img.w + x) * 3 + c];
                v = linearToRec709(std::min(1.0f, std::max(0.0f, v)));
                row[size_t(x) * 3 + c] = (unsigned char)std::lround(std::min(1.0f, std::max(0.0f, v)) * 255.0f);
            }
        std::fwrite(row.data(), 1, row.size(), f);
    }
    std::fclose(f);
}

int main(int argc, char** argv) {
    if (argc < 5) { std::fprintf(stderr, "usage: p5_compare clip frame maxDim outPrefix\n"); return 2; }
    auto res = Source::open(argv[1]);
    if (!res.isOk()) { std::fprintf(stderr, "open failed\n"); return 1; }
    Source& src = **res.value;
    const size_t frame = size_t(std::atoi(argv[2]));
    const int cap = std::atoi(argv[3]);
    const FrameInfo info = src.frameInfo(frame);
    auto dec = src.readFrame(frame);
    if (!dec.isOk()) { std::fprintf(stderr, "decode failed\n"); return 1; }
    const std::vector<uint16_t> cfa = std::move(*dec.value);
    const RawMeta meta = src.rawMeta();
    const int n = strideFor(info, cap);
    std::printf("frame %zu %ux%u cap %d stride %d -> %ux%u\n", frame, info.width, info.height, cap, n,
                thumbnailWidth(info.width, n), thumbnailHeight(info.height, n));
    Grade g; g.highlightReconstruction = false; g.keepVignetting = false;

    FrameInfo si = info; si.width = thumbnailWidth(info.width, n); si.height = thumbnailHeight(info.height, n);
    auto oldSmall = oldDecimate(cfa, info.width, info.height, n);
    std::vector<uint16_t> newSmall;
    auto t0 = std::chrono::steady_clock::now();
    reduceCfaBinned(cfa.data(), info.width, info.height, n, newSmall);
    auto t1 = std::chrono::steady_clock::now();
    std::printf("binning wall %.2f ms (desktop, parallel)\n",
                std::chrono::duration<double, std::milli>(t1 - t0).count());
    writePpm(std::string(argv[4]) + "-old.ppm", developCpu(oldSmall.data(), si, meta, g, nullptr, nullptr));
    writePpm(std::string(argv[4]) + "-new.ppm", developCpu(newSmall.data(), si, meta, g, nullptr, nullptr));
    // Full-resolution reference reduced by a clean area filter would be the ideal; the pause render
    // (stride 1) is what the owner calls "clean", so also write it for the eye.
    FrameInfo full = info;
    ImageF ref = developCpu(cfa.data(), full, meta, g, nullptr, nullptr);
    writePpm(std::string(argv[4]) + "-full.ppm", ref);
    return 0;
}
