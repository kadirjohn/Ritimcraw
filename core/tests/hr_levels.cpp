// hr_levels — per-camera verification of recoveryClipLevels (docs/HIGHLIGHT_RECOVERY_PLAN.md, owner request
// 2026-11-25): for a clip, print the measured per-channel clip levels over a frame sweep so a clipped and an
// unclipped frame can be compared. Usage: hr_levels <clip.mcraw> [step=20]
#include <rawedit/Develop.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <cstdlib>
#include <string>

using namespace rawedit;

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: hr_levels <clip.mcraw> [step]\n"); return 2; }
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    const size_t step = argc > 2 ? size_t(std::strtoull(argv[2], nullptr, 10)) : 20;
    const RawMeta meta = src.rawMeta();
    std::printf("clip=%s frames=%zu %ux%u  metaWhite=%u asShotNeutral=%.4f %.4f %.4f\n", argv[1], src.frameCount(),
                src.frameInfo(0).width, src.frameInfo(0).height, meta.white, meta.asShotNeutral[0], meta.asShotNeutral[1],
                meta.asShotNeutral[2]);
    std::printf("frame | threshold R G B (1.0 = none) | maxNorm R G B | topCount R G B | band(<=0.4%% below max) R G B\n");
    for (size_t fi = 0; fi < src.frameCount(); fi += step) {
        auto dec = src.readFrame(fi);
        if (!dec.isOk()) continue;
        const RecoveryClipLevels lv = recoveryClipLevels(dec.value->data(), src.frameInfo(fi), meta);
        std::printf("%5zu | %.5f %.5f %.5f | %.5f %.5f %.5f | %llu %llu %llu | %llu %llu %llu\n", fi, lv.threshold[0],
                    lv.threshold[1], lv.threshold[2], lv.maxNorm[0], lv.maxNorm[1], lv.maxNorm[2],
                    (unsigned long long)lv.topCount[0], (unsigned long long)lv.topCount[1], (unsigned long long)lv.topCount[2],
                    (unsigned long long)lv.plateau[0], (unsigned long long)lv.plateau[1], (unsigned long long)lv.plateau[2]);
    }
    return 0;
}
