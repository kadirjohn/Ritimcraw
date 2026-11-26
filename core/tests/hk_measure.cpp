// hk_measure — D3 (adaptive Highlights knee) measurement tool, desktop only, not a test.
//
// Usage: hk_measure <clip.mcraw> [gainEv=0] [frames=8]
//
// 1. Measures the clip's luma P85 at unit gain: `frames` evenly spaced frames, developCpu with a neutral
//    grade (stages 1-13 only, tonemap off), every 16th pixel pooled, Rec.709 luma. Prints P50/P85/P95/P99,
//    the stored ref (P85 at unit gain) and the knee at the given Gain.
// 2. For three frames, develops at the given Gain with Highlights 0 / -100 / +100, OLD (ref unset = fixed
//    0.18) and NEW (measured ref), and reports the change in the brightest 5% / 0.5% of pixels (selected
//    on the Highlights=0 output) in /255 Rec.709 display units: mean signed change and mean |change|.
// Everything printed comes from the clip; nothing is assumed.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <numeric>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

float to255(float lin) {
    float v = linearToRec709(lin);
    v = std::min(std::max(v, 0.0f), 1.0f);
    return std::floor(v * 255.0f + 0.5f);
}

ImageF dev(const std::vector<uint16_t>& cfa, const FrameInfo& info, const RawMeta& meta, const Grade& g) {
    return developCpu(cfa.data(), info, meta, g);
}

float lumaOf(const ImageF& im, size_t p) {
    return 0.2126f * im.rgb[p * 3] + 0.7152f * im.rgb[p * 3 + 1] + 0.0722f * im.rgb[p * 3 + 2];
}

struct Delta { double mean = 0, meanAbs = 0; size_t n = 0; };

// Brightest `frac` of pixels by the base output's luma; change of the encoded luma-ish (mean of rgb 8-bit).
Delta topDelta(const ImageF& base, const ImageF& test, double frac) {
    const size_t px = size_t(base.w) * base.h;
    std::vector<float> y(px);
    for (size_t p = 0; p < px; ++p) y[p] = lumaOf(base, p);
    std::vector<size_t> idx(px);
    std::iota(idx.begin(), idx.end(), size_t(0));
    const size_t k = std::max<size_t>(1, size_t(frac * double(px)));
    std::nth_element(idx.begin(), idx.begin() + std::ptrdiff_t(k), idx.end(),
                     [&](size_t a, size_t b) { return y[a] > y[b]; });
    Delta d;
    for (size_t i = 0; i < k; ++i) {
        const size_t p = idx[i];
        double c = 0;
        for (int ch = 0; ch < 3; ++ch) c += double(to255(test.rgb[p * 3 + ch])) - double(to255(base.rgb[p * 3 + ch]));
        c /= 3.0;
        d.mean += c;
        d.meanAbs += std::fabs(c);
        d.n++;
    }
    d.mean /= double(d.n);
    d.meanAbs /= double(d.n);
    return d;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: hk_measure <clip.mcraw> [gainEv] [frames]\n"); return 2; }
    const float ev = argc > 2 ? float(std::atof(argv[2])) : 0.0f;
    const int nFrames = argc > 3 ? std::max(1, std::atoi(argv[3])) : 8;
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    const size_t fc = src.frameCount();
    const RawMeta meta = src.rawMeta();

    std::vector<size_t> pick;
    for (int i = 0; i < nFrames; ++i)
        pick.push_back(nFrames == 1 ? fc / 2 : size_t(double(i) * double(fc - 1) / double(nFrames - 1) + 0.5));

    std::vector<float> samples;
    for (size_t fi : pick) {
        auto dec = src.readFrame(fi);
        if (!dec.isOk()) { std::fprintf(stderr, "decode %zu: %s\n", fi, dec.detail.c_str()); return 1; }
        const FrameInfo info = src.frameInfo(fi);
        Grade g; // neutral: stages 1-13 only
        ImageF im = dev(*dec.value, info, meta, g);
        appendLumaSamples(im, 16, samples);
    }
    std::vector<float> s2 = samples;
    const float p50 = lumaQuantile(s2, 0.50f), p95 = lumaQuantile(s2, 0.95f), p99 = lumaQuantile(s2, 0.99f);
    // The value the app stores: the SAME core function (binned thumbnail develop, pooled P85).
    const float ref = measureHighlightKneeRef(src, nFrames, 480);
    std::printf("measureHighlightKneeRef (app path, 480px, %d frames) = %.5f ; full-res stride-16 P85 = %.5f\n",
                nFrames, ref, lumaQuantile(samples, kHighlightKneePercentile));
    const float scale = std::pow(2.0f, ev);
    std::printf("clip=%s frames=%zu sampled=%d gainEv=%+.2f samples=%zu\n", argv[1], fc, nFrames, ev, samples.size());
    std::printf("unit-gain luma: P50=%.5f P85(ref)=%.5f P95=%.5f P99=%.5f\n", p50, ref, p95, p99);
    std::printf("knee at gain: NEW=%.5f  OLD=0.18000 (clamp %.2f..%.2f)\n", highlightKnee(ref, scale),
                kHighlightKneeMin, kHighlightKneeMax);

    const size_t tests[3] = {pick[0], pick[pick.size() / 2], pick.back()};
    const float hl[2] = {-100.0f, 100.0f};
    for (size_t fi : tests) {
        auto dec = src.readFrame(fi);
        const FrameInfo info = src.frameInfo(fi);
        Grade g0; g0.exposureEv = ev;
        ImageF base = dev(*dec.value, info, meta, g0);
        for (float h : hl) {
            Grade go = g0; go.highlights = h;                       // OLD: ref unset -> 0.18
            Grade gn = g0; gn.highlights = h; gn.highlightKneeRef = ref; // NEW
            ImageF io = dev(*dec.value, info, meta, go);
            ImageF in = dev(*dec.value, info, meta, gn);
            for (double frac : {0.05, 0.005}) {
                const Delta a = topDelta(base, io, frac), b = topDelta(base, in, frac);
                std::printf("frame %5zu H%+4.0f top%4.1f%%  OLD mean %+7.2f |%6.2f|   NEW mean %+7.2f |%6.2f|  (/255)\n", fi,
                            h, frac * 100.0, a.mean, a.meanAbs, b.mean, b.meanAbs);
            }
        }
    }
    return 0;
}
