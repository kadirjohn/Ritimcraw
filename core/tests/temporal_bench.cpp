// temporal_bench — D4 (night-e6b, 2026-11-27): frame-cost bench for
// temporalMergeCfa on the real clip + a binned 1080p twin, serial vs
// parallel, median/p90 over N runs. Desktop measurement tool, not a
// test; built by tools/build-desktop.sh, run by hand.
//
// Usage:
//   temporal_bench <clip.mcraw> [center=auto] [runs=7]
//
// Decodes 3 frames once (center-1, center, center+1) with the bridge
// contract (frameDuration = median of the two adjacent PTS deltas,
// real dPTS per side, per-frame noise profiles), then times
// temporalMergeCfa `runs` times in each mode:
//   serial   = setParallelForcedSerial(true)
//   parallel = the default pool (M3, 4 workers + caller)
// Reports, per mode: median and p90 of total merge ms, plus the mean of
// the TemporalReport phase clocks (proxyMs / matchMs / mergeMs), and the
// FNV-1a checksum of the output (serial vs parallel must match; a change
// in the checksum across code versions is the bit-identity tripwire).
// The 1080p twin: the same three frames reduced with reduceCfaBinned
// stride 2 (the documented thumbnail reducer — 3840x2160 -> 1920x1080),
// same noise profiles (normalized-signal domain, resolution independent).
//
// Not measured here: neighbor DECODE (device-specific; the harness holds
// the decoded window in memory, so decode never enters the numbers).

#include <rawedit/Parallel.h>
#include <rawedit/Source.h>
#include <rawedit/Temporal.h>
#include <rawedit/Thumbnail.h>

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

using namespace rawedit;

namespace {

uint64_t fnv1a64(const std::vector<uint16_t>& v) {
    uint64_t h = 1469598103934665603ull;
    for (uint16_t s : v) {
        h ^= static_cast<uint64_t>(s & 0xFF);
        h *= 1099511628211ull;
        h ^= static_cast<uint64_t>(s >> 8);
        h *= 1099511628211ull;
    }
    return h;
}

double medianOf(std::vector<double>& v) {
    const size_t k = v.size() / 2;
    std::nth_element(v.begin(), v.begin() + static_cast<long>(k), v.end());
    double m = v[k];
    if (v.size() % 2 == 0) {
        const double a = *std::max_element(v.begin(), v.begin() + static_cast<long>(k));
        m = (m + a) * 0.5;
    }
    return m;
}

double p90Of(std::vector<double>& v) {
    const size_t k = std::min(v.size() - 1, (v.size() * 9) / 10);
    std::nth_element(v.begin(), v.begin() + static_cast<long>(k), v.end());
    return v[k];
}

struct BenchResult {
    double medianMs = 0.0;
    double p90Ms = 0.0;
    double meanProxy = 0.0;
    double meanMatch = 0.0;
    double meanMergeLoop = 0.0;
    uint64_t checksum = 0;
};

BenchResult bench(const std::vector<uint16_t>& c, const std::vector<uint16_t>& p,
                  const std::vector<uint16_t>& x, const FrameInfo& info,
                  const RawMeta& meta, const TemporalSettings& ts, int runs,
                  bool forcedSerial) {
    std::vector<uint16_t> out(c.size(), 0);
    std::vector<double> totalMs;
    double sumProxy = 0, sumMatch = 0, sumMerge = 0;
    uint64_t checksum = 0;
    const bool before = setParallelForcedSerial(forcedSerial);
    for (int i = 0; i < runs; ++i) {
        TemporalReport rep;
        const auto t0 = std::chrono::steady_clock::now();
        temporalMergeCfa(c.data(), info, p.data(), x.data(), meta, ts,
                         out.data(), &rep);
        const auto t1 = std::chrono::steady_clock::now();
        const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        totalMs.push_back(ms);
        sumProxy += rep.proxyMs;
        sumMatch += rep.matchMs;
        sumMerge += rep.mergeMs;
    }
    setParallelForcedSerial(before);
    checksum = fnv1a64(out);
    BenchResult r;
    r.medianMs = medianOf(totalMs);
    r.p90Ms = p90Of(totalMs);
    r.meanProxy = sumProxy / runs;
    r.meanMatch = sumMatch / runs;
    r.meanMergeLoop = sumMerge / runs;
    r.checksum = checksum;
    return r;
}

} // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: temporal_bench <clip.mcraw> [center=auto] [runs=7]\n");
        return 2;
    }
    const char* path = argv[1];
    const size_t centerArg = argc >= 3 ? std::strtoull(argv[2], nullptr, 10) : 0;
    const int runs = argc >= 4 ? std::atoi(argv[3]) : 7;

    auto res = Source::open(path);
    if (!res.isOk()) {
        std::fprintf(stderr, "open: %s\n", res.detail.c_str());
        return 1;
    }
    Source& src = **res.value;
    const size_t n = src.frameCount();
    if (n < 3) {
        std::fprintf(stderr, "clip has %zu frames; needs 3\n", n);
        return 1;
    }
    size_t center = centerArg;
    if (center == 0 || center + 1 >= n) center = n / 2;
    if (center < 1 || center + 1 >= n) {
        std::fprintf(stderr, "center %zu out of range [1, %zu]\n", center, n - 2);
        return 1;
    }
    std::vector<std::vector<uint16_t>> f(3);
    for (int k = 0; k < 3; ++k) {
        auto d = src.readFrame(center - 1 + static_cast<size_t>(k));
        if (!d.isOk()) {
            std::fprintf(stderr, "decode %zu: %s\n", center - 1 + k, d.detail.c_str());
            return 1;
        }
        f[k] = std::move(*d.value);
    }

    const FrameInfo info = src.frameInfo(center);
    const RawMeta meta = src.rawMeta();
    const NoiseProfile npc = src.noiseProfile(center);
    const NoiseProfile npp = src.noiseProfile(center - 1);
    const NoiseProfile npn = src.noiseProfile(center + 1);
    const int64_t dPrev = src.frameInfo(center - 1).ptsUs - info.ptsUs;
    const int64_t dNext = src.frameInfo(center + 1).ptsUs - info.ptsUs;
    const int64_t dur = (dPrev + dNext) / 2;

    // Warm-up (allocator + pool spin-up) discarded from every number.
    {
        std::vector<uint16_t> out(f[1].size(), 0);
        TemporalSettings ts;
        ts.strength = 100.0f;
        temporalMergeCfa(f[1].data(), info, f[0].data(), f[2].data(), meta, ts,
                        out.data(), nullptr);
    }

    std::printf("== temporal_bench ==\n");
    std::printf("clip=%s center=%zu %ux%u runs=%d workers=%d dur=%lldus "
                "dPrev=%lld dNext=%lld profile=%d\n",
                path, center, info.width, info.height, runs,
                parallelWorkerCount(), static_cast<long long>(dur),
                static_cast<long long>(dPrev), static_cast<long long>(dNext),
                int(npc.valid));

    auto settingsFor = [&](const NoiseProfile* c, const NoiseProfile* p,
                           const NoiseProfile* x) {
        TemporalSettings ts;
        ts.strength = 100.0f;
        ts.centerNoise = c;
        ts.prevNoise = p;
        ts.nextNoise = x;
        ts.prevDPtsUs = dPrev;
        ts.nextDPtsUs = dNext;
        ts.frameDurationUs = dur;
        return ts;
    };

    // 4K (full frame)
    {
        const TemporalSettings ts = settingsFor(&npc, &npp, &npn);
        const BenchResult s = bench(f[1], f[0], f[2], info, meta, ts, runs, true);
        const BenchResult p = bench(f[1], f[0], f[2], info, meta, ts, runs, false);
        std::printf("4k   serial  : median=%7.1f ms p90=%7.1f ms | proxy=%5.2f match=%6.2f mergeLoop=%6.2f | cksum=%llu\n",
                    s.medianMs, s.p90Ms, s.meanProxy, s.meanMatch, s.meanMergeLoop,
                    static_cast<unsigned long long>(s.checksum));
        std::printf("4k   parallel: median=%7.1f ms p90=%7.1f ms | proxy=%5.2f match=%6.2f mergeLoop=%6.2f | cksum=%llu\n",
                    p.medianMs, p.p90Ms, p.meanProxy, p.meanMatch, p.meanMergeLoop,
                    static_cast<unsigned long long>(p.checksum));
        std::printf("4k   bit-identical serial==parallel: %s\n",
                    s.checksum == p.checksum ? "YES" : "NO !!!");
    }

    // 1080p twin (stride-2 binned, the documented reducer)
    if (info.width >= 64 && info.height >= 64) {
        std::vector<std::vector<uint16_t>> b(3);
        for (int k = 0; k < 3; ++k)
            if (!reduceCfaBinned(f[k].data(), info.width, info.height, 2, b[k])) {
                std::fprintf(stderr, "reduceCfaBinned failed\n");
                return 1;
            }
        const uint32_t bw = thumbnailWidth(info.width, 2);
        const uint32_t bh = thumbnailHeight(info.height, 2);
        FrameInfo bi = info;
        bi.width = bw;
        bi.height = bh;
        // Warm-up for the binned size too.
        {
            std::vector<uint16_t> out(b[1].size(), 0);
            TemporalSettings ts;
            ts.strength = 100.0f;
            temporalMergeCfa(b[1].data(), bi, b[0].data(), b[2].data(), meta, ts,
                            out.data(), nullptr);
        }
        const TemporalSettings ts = settingsFor(&npc, &npp, &npn);
        const BenchResult s = bench(b[1], b[0], b[2], bi, meta, ts, runs, true);
        const BenchResult p = bench(b[1], b[0], b[2], bi, meta, ts, runs, false);
        std::printf("1080p serial  : median=%7.1f ms p90=%7.1f ms | proxy=%5.2f match=%6.2f mergeLoop=%6.2f | cksum=%llu\n",
                    s.medianMs, s.p90Ms, s.meanProxy, s.meanMatch, s.meanMergeLoop,
                    static_cast<unsigned long long>(s.checksum));
        std::printf("1080p parallel: median=%7.1f ms p90=%7.1f ms | proxy=%5.2f match=%6.2f mergeLoop=%6.2f | cksum=%llu\n",
                    p.medianMs, p.p90Ms, p.meanProxy, p.meanMatch, p.meanMergeLoop,
                    static_cast<unsigned long long>(p.checksum));
        std::printf("1080p bit-identical serial==parallel: %s\n",
                    s.checksum == p.checksum ? "YES" : "NO !!!");
    }
    return 0;
}
