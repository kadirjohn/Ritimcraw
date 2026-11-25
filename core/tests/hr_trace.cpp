// hr_trace — per-stage trace of ONE pixel through the develop chain (owner request 2026-11-25: the lilac streak on
// 261124_144344 at ~00:02; measure instead of guessing which stage pushes a channel below 0 / above 1).
//
// Usage: hr_trace <clip.mcraw> <frame> [x y] [exposureEv highlights chromaDenoise noiseReduction]
// Defaults are the clip's saved grade at the time of the screenshot: Gain +1.1 EV, Highlights -100 (knee was 0.75 then; it
// is 0.18 since 2026-11-26, so re-running the same grade today traces a slightly different S6/disp than the original run),
// chroma denoise 61, noise reduction 63, as-shot WB, Neutral stage 3 on. Without x y it searches the developed frame for
// the most lilac pixel (display R and B above G, bright) and traces that. Stages (cumulative, linear unless noted):
//   raw  : the 2x2 raw cell around the pixel (codes and normalised)
//   S2   : after stage 3 + white balance (camera RGB, before the camera matrix; matrices set to identity)
//   S3   : after the camera -> Rec.709 matrix (stage 6)
//   S4   : + noise reduction (stages 7/8)
//   S5   : + exposure (stage 14)
//   S6   : + highlights (stage 15) = the linear image the tail receives (tonemap off)
//   disp : OETF (linearToRec709) + clamp to [0,1] -> bytes (what the screen shows)
// Each stage prints R G B, the chroma ratios R/G and B/G, and flags a channel < 0 or > 1.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

struct Px { float v[3]; };

Px at(const ImageF& im, long x, long y) {
    Px p;
    const size_t i = (size_t(y) * im.w + size_t(x)) * 3;
    for (int c = 0; c < 3; ++c) p.v[c] = im.rgb[i + c];
    return p;
}

void line(const char* name, const Px& p) {
    const float g = std::max(std::fabs(p.v[1]), 1e-6f);
    std::printf("%-5s R=%9.5f G=%9.5f B=%9.5f | R/G=%6.3f B/G=%6.3f", name, p.v[0], p.v[1], p.v[2], p.v[0] / g, p.v[2] / g);
    for (int c = 0; c < 3; ++c) {
        if (p.v[c] < 0.0f) std::printf("  [%c < 0]", "RGB"[c]);
        if (p.v[c] > 1.0f) std::printf("  [%c > 1]", "RGB"[c]);
    }
    std::printf("\n");
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: hr_trace <clip> <frame> [x y] [ev hl chroma nr]\n"); return 2; }
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    const size_t fi = size_t(std::strtoull(argv[2], nullptr, 10));
    auto dec = src.readFrame(fi);
    if (!dec.isOk()) { std::fprintf(stderr, "decode: %s\n", dec.detail.c_str()); return 1; }
    const std::vector<uint16_t>& cfa = *dec.value;
    const FrameInfo info = src.frameInfo(fi);
    const RawMeta meta = src.rawMeta();
    long px = -1, py = -1;
    int ai = 3;
    if (argc >= 5) { px = std::atol(argv[3]); py = std::atol(argv[4]); ai = 5; }
    const float ev = argc > ai ? float(std::atof(argv[ai])) : 1.1f;
    const float hl = argc > ai + 1 ? float(std::atof(argv[ai + 1])) : -100.0f;
    const float chroma = argc > ai + 2 ? float(std::atof(argv[ai + 2])) : 61.0f;
    const float nr = argc > ai + 3 ? float(std::atof(argv[ai + 3])) : 63.0f;

    Grade base;
    base.highlightReconstruction = true;
    Grade gNr = base; gNr.chromaDenoise = chroma; gNr.noiseReduction = nr;
    Grade gEv = gNr; gEv.exposureEv = ev;
    Grade gHl = gEv; gHl.highlights = hl;
    RawMeta metaId = meta;  // identity matrices -> stage 6 is a passthrough: the image after WB only
    for (int i = 0; i < 9; ++i) { metaId.forwardMatrix[i] = metaId.colorMatrix[i] = metaId.calibration[i] = (i % 4 == 0) ? 1.0f : 0.0f; }

    const ImageF s6 = developCpu(cfa.data(), info, meta, gHl);
    auto disp = [](float x) { return std::min(std::max(linearToRec709(x), 0.0f), 1.0f); };
    if (px < 0) {  // search: bright pixels whose display R and B both exceed G
        double best = 0;
        // HR_BOX=fx0,fy0,fx1,fy1 (fractions of the frame) and HR_LUM (display luma floor) narrow the search.
        double bx0 = 0, by0 = 0, bx1 = 1, by1 = 1;
        if (const char* b = std::getenv("HR_BOX")) std::sscanf(b, "%lf,%lf,%lf,%lf", &bx0, &by0, &bx1, &by1);
        const float lumFloor = std::getenv("HR_LUM") ? float(std::atof(std::getenv("HR_LUM"))) : 0.45f;
        const long x0 = std::max(8L, long(bx0 * s6.w)), y0 = std::max(8L, long(by0 * s6.h));
        const long x1 = std::min(long(s6.w) - 8, long(bx1 * s6.w)), y1 = std::min(long(s6.h) - 8, long(by1 * s6.h));
        for (long y = y0; y < y1; y += 2)
            for (long x = x0; x < x1; x += 2) {
                const Px p = at(s6, x, y);
                const float r = disp(p.v[0]), g = disp(p.v[1]), b = disp(p.v[2]);
                const float lum = (r + g + b) / 3.0f;
                const double score = (std::min(r, b) - g) * (lum > lumFloor ? 1.0 : 0.0);
                if (score > best) { best = score; px = x; py = y; }
            }
        std::printf("search: most lilac pixel (min(R,B) - G in display, lum > 0.45) at (%ld,%ld), score %.3f\n", px, py, best);
    }
    std::printf("clip=%s frame=%zu pixel=(%ld,%ld) grade: ev=%.2f highlights=%.0f chroma=%.0f nr=%.0f; asShotNeutral=%.4f %.4f %.4f\n",
                argv[1], fi, px, py, ev, hl, chroma, nr, meta.asShotNeutral[0], meta.asShotNeutral[1], meta.asShotNeutral[2]);

    // raw cell
    {
        const long cx = px & ~1L, cy = py & ~1L;
        std::printf("raw 2x2 cell at (%ld,%ld) codes: %u %u / %u %u   white=%d black0=%d\n", cx, cy, cfa[size_t(cy) * info.width + size_t(cx)],
                    cfa[size_t(cy) * info.width + size_t(cx) + 1], cfa[size_t(cy + 1) * info.width + size_t(cx)],
                    cfa[size_t(cy + 1) * info.width + size_t(cx) + 1], info.dynamicWhite > 0 ? info.dynamicWhite : int(meta.white),
                    info.dynamicBlack[0] >= 0 ? info.dynamicBlack[0] : int(meta.black[0]));
    }
    { const ImageF im = developCpu(cfa.data(), info, metaId, base); line("S2", at(im, px, py)); }
    { const ImageF im = developCpu(cfa.data(), info, meta, base); line("S3", at(im, px, py)); }
    { const ImageF im = developCpu(cfa.data(), info, meta, gNr); line("S4", at(im, px, py)); }
    { const ImageF im = developCpu(cfa.data(), info, meta, gEv); line("S5", at(im, px, py)); }
    const Px p6 = at(s6, px, py);
    line("S6", p6);
    std::printf("disp  R=%3d G=%3d B=%3d (bytes after OETF + clamp)\n", int(disp(p6.v[0]) * 255.0f + 0.5f), int(disp(p6.v[1]) * 255.0f + 0.5f),
                int(disp(p6.v[2]) * 255.0f + 0.5f));
    // neighbourhood spread on S6 (is it a thin streak?)
    std::printf("S6 along x (display bytes R G B):");
    for (long d = -6; d <= 6; d += 2) {
        const long x = std::min(std::max(px + d, 0L), long(s6.w) - 1);
        const Px q = at(s6, x, py);
        std::printf(" [%d %d %d]", int(disp(q.v[0]) * 255.0f + 0.5f), int(disp(q.v[1]) * 255.0f + 0.5f), int(disp(q.v[2]) * 255.0f + 0.5f));
    }
    std::printf("\n");
    return 0;
}
