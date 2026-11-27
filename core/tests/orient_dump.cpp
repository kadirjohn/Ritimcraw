// orient_dump — orientation inventory tool (desktop only, not a test).
//
// Usage: orient_dump <clip.mcraw> [out.ppm]
//
// Prints, from the clip itself: the frame size, the container "sensorOrientation" (read from the container
// metadata JSON the decoder exposes), the DISTINCT per-frame "orientation" values with their frame counts, and
// what FrameInfo.orientationDeg carries (the frame value, falling back to the container's). With out.ppm it
// also writes a neutral-developed, binned middle frame, UN-rotated (sensor orientation), so a human can tell
// which way the scene is really facing. Nothing is assumed.

#include <rawedit/Develop.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>
#include <rawedit/Transfer.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <map>
#include <string>

using namespace rawedit;

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: orient_dump <clip.mcraw> [out.ppm]\n"); return 2; }
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    const size_t fc = src.frameCount();
    std::map<int, size_t> perFrame;
    for (size_t i = 0; i < fc; ++i) perFrame[src.frameInfo(i).orientationDeg]++;
    const FrameInfo first = src.frameInfo(0);
    std::printf("frames=%zu size=%ux%u\n", fc, first.width, first.height);
    std::printf("FrameInfo.orientationDeg values:");
    for (auto& kv : perFrame) std::printf(" %d(x%zu)", kv.first, kv.second);
    std::printf("\n");
    if (argc > 2) {
        const size_t mid = fc / 2;
        auto dec = src.readFrame(mid);
        if (!dec.isOk()) { std::fprintf(stderr, "decode: %s\n", dec.detail.c_str()); return 1; }
        const FrameInfo info = src.frameInfo(mid);
        Grade g; // neutral
        ImageF im = developThumbnail(dec.value->data(), info, src.rawMeta(), g, 4);
        FILE* f = std::fopen(argv[2], "wb");
        if (!f) return 1;
        std::fprintf(f, "P6\n%d %d\n255\n", im.w, im.h);
        for (size_t p = 0; p < size_t(im.w) * im.h * 3; ++p) {
            float v = linearToRec709(im.rgb[p]);
            v = std::min(std::max(v, 0.0f), 1.0f);
            std::fputc(int(v * 255.0f + 0.5f), f);
        }
        std::fclose(f);
        std::printf("wrote %s (%dx%d, frame %zu, sensor orientation, not rotated)\n", argv[2], im.w, im.h, mid);
    }
    return 0;
}
