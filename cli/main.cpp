// rawcli — desktop CLI over the rawedit core.
//
//   rawcli info <file.mcraw>     prints JSON: frameCount plus ptsUs/width/
//                                height for frames 0, mid and last
//   rawcli wav  <file> <out.wav> writes the clip's audio as 16-bit PCM WAV
//                                (RIFF header written manually — no audiofile
//                                dependency)
//
// Exit codes: 0 ok, 1 usage, 2 source error (message on stderr).

#include <rawedit/Result.h>
#include <rawedit/Source.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

// Canonical 44-byte RIFF/WAVE PCM16 header (the tested reference lives in
// core/tests/test_source.cpp; the CLI keeps its own copy so the binary does
// not depend on the test file).
std::vector<uint8_t> wavHeader(uint32_t frames, int channels, int sampleRate) {
    const uint32_t numChannels = static_cast<uint32_t>(channels);
    const uint32_t dataBytes = frames * numChannels * 2;
    std::vector<uint8_t> h(44, 0);
    auto put32 = [&](size_t off, uint32_t v) { std::memcpy(&h[off], &v, 4); };
    auto put16 = [&](size_t off, uint16_t v) { std::memcpy(&h[off], &v, 2); };

    std::memcpy(&h[0], "RIFF", 4);
    put32(4, 36 + dataBytes);
    std::memcpy(&h[8], "WAVE", 4);
    std::memcpy(&h[12], "fmt ", 4);
    put32(16, 16);                                    // fmt chunk size
    put16(20, 1);                                      // PCM
    put16(22, static_cast<uint16_t>(numChannels));
    put32(24, static_cast<uint32_t>(sampleRate));
    put32(28, static_cast<uint32_t>(sampleRate) * numChannels * 2); // byte rate
    put16(32, static_cast<uint16_t>(numChannels * 2)); // block align
    put16(34, 16);                                     // bits per sample
    std::memcpy(&h[36], "data", 4);
    put32(40, dataBytes);
    return h;
}

int cmdInfo(const std::string& path) {
    auto res = Source::open(path);
    if (!res.isOk()) {
        std::fprintf(stderr, "error: %s (%s): %s\n",
                     path.c_str(), errorName(res.code), res.detail.c_str());
        return 2;
    }
    auto& src = *res.value;
    const size_t n = src->frameCount();

    // JSON with frameCount and the first/mid/last frames. Escaping: paths are
    // not embedded, so no string escaping is needed.
    std::printf("{\n  \"frameCount\": %zu,\n", n);
    std::printf("  \"frames\": [\n");
    if (n > 0) {
        const size_t indices[3] = {0, n / 2, n - 1};
        const char* names[3] = {"first", "mid", "last"};
        for (int k = 0; k < 3; ++k) {
            const FrameInfo f = src->frameInfo(indices[k]);
            std::printf("    {\"slot\": \"%s\", \"index\": %zu, \"ptsUs\": %lld, "
                        "\"width\": %u, \"height\": %u, \"cfa\": \"%s\", "
                        "\"orientationDeg\": %d}%s\n",
                        names[k], indices[k],
                        static_cast<long long>(f.ptsUs), f.width, f.height,
                        [&]{ switch (f.cfa) { case CfaPattern::RGGB: return "rggb";
                                              case CfaPattern::GRBG: return "grbg";
                                              case CfaPattern::GBRG: return "gbrg";
                                              case CfaPattern::BGGR: return "bggr"; }
                              return "rggb"; }(),
                        f.orientationDeg,
                        k == 2 ? "" : ",");
        }
    }
    std::printf("  ]\n}\n");

    const RawMeta& m = src->rawMeta();
    std::printf("meta: {\"blackLevel\": [%u, %u, %u, %u], \"whiteLevel\": %u, "
                "\"asShotNeutral\": [%.6f, %.6f, %.6f], \"shading\": %ux%u}\n",
                m.black[0], m.black[1], m.black[2], m.black[3], m.white,
                m.asShotNeutral[0], m.asShotNeutral[1], m.asShotNeutral[2],
                m.shadingW, m.shadingH);
    return 0;
}

int cmdWav(const std::string& path, const std::string& outPath) {
    auto res = Source::open(path);
    if (!res.isOk()) {
        std::fprintf(stderr, "error: %s (%s): %s\n",
                     path.c_str(), errorName(res.code), res.detail.c_str());
        return 2;
    }
    auto& src = *res.value;

    auto info = src->audioInfo();
    if (!info.isOk()) {
        std::fprintf(stderr, "error: audioInfo (%s): %s\n",
                     errorName(info.code), info.detail.c_str());
        return 2;
    }
    if (info->channels <= 0 || info->sampleRate <= 0) {
        std::fprintf(stderr, "error: clip has no audio track\n");
        return 2;
    }

    auto audio = src->readAudio();
    if (!audio.isOk()) {
        std::fprintf(stderr, "error: readAudio (%s): %s\n",
                     errorName(audio.code), audio.detail.c_str());
        return 2;
    }

    const uint32_t frames = static_cast<uint32_t>(audio->size() / info->channels);
    const auto header = wavHeader(frames, info->channels, info->sampleRate);

    std::ofstream out(outPath, std::ios::binary);
    if (!out) {
        std::fprintf(stderr, "error: cannot open output %s\n", outPath.c_str());
        return 2;
    }
    out.write(reinterpret_cast<const char*>(header.data()),
              static_cast<std::streamsize>(header.size()));
    if (!audio->empty())
        out.write(reinterpret_cast<const char*>(audio->data()),
                  static_cast<std::streamsize>(audio->size() * sizeof(int16_t)));
    out.close();
    if (!out) {
        std::fprintf(stderr, "error: writing %s failed\n", outPath.c_str());
        return 2;
    }
    std::fprintf(stderr, "wrote %s: %u frames, %d ch, %d Hz\n",
                 outPath.c_str(), frames, info->channels, info->sampleRate);
    return 0;
}

} // namespace

int main(int argc, char** argv) {
    if (argc >= 3 && std::strcmp(argv[1], "info") == 0 && argc == 3)
        return cmdInfo(argv[2]);
    if (argc >= 4 && std::strcmp(argv[1], "wav") == 0 && argc == 4)
        return cmdWav(argv[2], argv[3]);

    std::fprintf(stderr,
        "usage:\n"
        "  rawcli info <file.mcraw>\n"
        "  rawcli wav  <file.mcraw> <out.wav>\n");
    return 1;
}
