// rawedit::WavWriter — see rawedit/WavWriter.h for the interface
// contract (R1-4: the CinemaDNG sequence's companion PCM WAV).

#include <rawedit/WavWriter.h>

#include <cstdio>
#include <cstring>
#include <fstream>
#include <limits>

namespace rawedit {
namespace {

void put16(std::vector<uint8_t>& b, uint16_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xff));
}

void put32(std::vector<uint8_t>& b, uint32_t v) {
    b.push_back(static_cast<uint8_t>(v & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 8) & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 16) & 0xff));
    b.push_back(static_cast<uint8_t>((v >> 24) & 0xff));
}

void putFourCC(std::vector<uint8_t>& b, const char* s) {
    b.push_back(static_cast<uint8_t>(s[0]));
    b.push_back(static_cast<uint8_t>(s[1]));
    b.push_back(static_cast<uint8_t>(s[2]));
    b.push_back(static_cast<uint8_t>(s[3]));
}

} // namespace

WavSizes wavSizes(size_t dataBytes) {
    WavSizes s;
    // RIFF chunk size = the whole file minus the 8-byte "RIFF"+size
    // header: 4 ("WAVE") + 24 (fmt chunk with size) + 8 ("data"+size)
    // + dataBytes = 36 + dataBytes. Computed in 64-bit; the field is
    // uint32, so anything past 0xffffffff is the SPEC's ceiling —
    // reported as saturation, never wrapped (a wrapped size would make
    // a reader truncate a huge file silently).
    const uint64_t riff = 36ull + static_cast<uint64_t>(dataBytes);
    if (riff > 0xffffffffull) {
        s.riffSize = 0xffffffffu;
        s.dataSize = 0xffffffffu; // data size caps too (same field width)
        s.saturated = true;
        return s;
    }
    s.riffSize = static_cast<uint32_t>(riff);
    s.dataSize = static_cast<uint32_t>(dataBytes);
    return s;
}

size_t wavRangeSampleCount(const std::vector<int16_t>& pcm,
                           const WavRange& range) {
    if (range.startSample >= pcm.size()) return 0;
    const size_t end = (range.endSample == 0 || range.endSample > pcm.size())
        ? pcm.size() : range.endSample;
    return end > range.startSample ? end - range.startSample : 0;
}

size_t wavRangeDataBytes(const std::vector<int16_t>& pcm,
                         const WavRange& range) {
    return wavRangeSampleCount(pcm, range) * sizeof(int16_t);
}

Result<void> writeWav(const std::string& path,
                      const std::vector<int16_t>& pcm,
                      int sampleRate, int channels,
                      const WavRange& range) {
    if (sampleRate <= 0)
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "sample rate must be positive");
    if (channels <= 0)
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "channel count must be positive");

    const size_t count = wavRangeSampleCount(pcm, range);
    if (count == 0 && !pcm.empty())
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "the audio range is empty");
    if (count % static_cast<size_t>(channels) != 0)
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "the audio range cuts a frame");

    const size_t dataBytes = count * sizeof(int16_t);
    const WavSizes sizes = wavSizes(dataBytes);

    // The 44-byte canonical header, little-endian, field by field:
    //   "RIFF" riffSize "WAVE"
    //   "fmt " 16, format=1 (PCM), channels, sampleRate,
    //   byteRate = rate*channels*2, blockAlign = channels*2, bits=16
    //   "data" dataSize
    std::vector<uint8_t> header;
    header.reserve(44);
    putFourCC(header, "RIFF");
    put32(header, sizes.riffSize);
    putFourCC(header, "WAVE");
    putFourCC(header, "fmt ");
    put32(header, 16); // PCM fmt chunk size
    put16(header, 1); // format: PCM (uncompressed)
    put16(header, static_cast<uint16_t>(channels));
    put32(header, static_cast<uint32_t>(sampleRate));
    const uint64_t byteRate64 = static_cast<uint64_t>(sampleRate) *
                                channels * 2ull;
    if (byteRate64 > 0xffffffffull)
        return Result<void>::fail(ErrorCode::Unsupported,
                                  "byte rate exceeds the WAV field");
    put32(header, static_cast<uint32_t>(byteRate64));
    put16(header, static_cast<uint16_t>(channels * 2));
    put16(header, 16); // bits per sample
    putFourCC(header, "data");
    put32(header, sizes.dataSize);

    std::ofstream out(path, std::ios::binary | std::ios::trunc);
    if (!out)
        return Result<void>::fail(ErrorCode::IoError,
                                  "cannot open " + path);
    out.write(reinterpret_cast<const char*>(header.data()),
              static_cast<std::streamsize>(header.size()));
    if (count > 0) {
        // The samples VERBATIM: the source's own bytes, copied, never
        // re-mathed (R1-4's no-resample rule includes no sample math).
        out.write(reinterpret_cast<const char*>(pcm.data() + range.startSample),
                  static_cast<std::streamsize>(dataBytes));
    }
    out.close();
    if (!out) {
        std::remove(path.c_str());
        return Result<void>::fail(ErrorCode::IoError, "writing " + path +
                                   " failed (removed partial file)");
    }
    return Result<void>::ok();
}

} // namespace rawedit
