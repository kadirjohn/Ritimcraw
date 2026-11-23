// sync_measure — anchor vs optically refined sync offset on a real clip.
// Usage: sync_measure <clip.mcraw> <log.gcsv> [focalMm=23.95] [frames=75]
#include <rawedit/Gyro.h>
#include <rawedit/OpticalSync.h>
#include <rawedit/Orientation.h>
#include <rawedit/OrientationEstimate.h>
#include <rawedit/Source.h>
#include <rawedit/Stabilization.h>
#include <rawedit/Sync.h>
#include <rawedit/Thumbnail.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
using namespace rawedit;
int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s clip.mcraw log.gcsv [focalMm] [frames]\n", argv[0]); return 2; }
    const double focalMm = argc > 3 ? std::atof(argv[3]) : 23.95;
    const size_t maxFrames = argc > 4 ? std::atoi(argv[4]) : 75;
    auto opened = Source::open(std::string(argv[1]));
    if (!opened.isOk()) { std::fprintf(stderr, "open: %s\n", opened.detail.c_str()); return 1; }
    Source& src = **opened.value;
    auto parsed = parseGcsv(std::string(argv[2]));
    if (!parsed.isOk()) { std::fprintf(stderr, "gcsv: %s\n", parsed.detail.c_str()); return 1; }
    ImuData imu = std::move(*parsed.value);
    const AxisRemap remap = axisRemapForImu(imu);
    std::vector<ImuSample> gyro, accel;
    for (auto s : imu.gyro) { remap.apply(s.x, s.y, s.z); gyro.push_back(s); }
    for (auto s : imu.accel) { remap.apply(s.x, s.y, s.z); accel.push_back(s); }
    VqfOrientation ori = estimateOrientationVqf(gyro, accel);

    const FrameInfo first = src.frameInfo(0);
    const SyncEstimate anchor = estimateOffsetFromAnchors(first.recvdMs, imu.logStartWallMs, first.ptsUs * 1000);
    const size_t frames = src.frameCount();
    const int64_t interval = (src.frameInfo(frames - 1).ptsUs - first.ptsUs) * 1000 / int64_t(frames - 1);

    // min receive lag over all frames vs frame 0's (how noisy the anchor is)
    double lag0 = 0, lagMin = 1e18, lagMax = -1e18;
    for (size_t i = 0; i < frames; ++i) {
        const FrameInfo fi = src.frameInfo(i);
        const double lag = double(fi.recvdMs) - double(fi.ptsUs) / 1000.0;
        if (i == 0) lag0 = lag;
        lagMin = std::min(lagMin, lag); lagMax = std::max(lagMax, lag);
    }
    std::printf("frames=%zu interval=%.2fms anchorOffset=%.2fms bound=%.1fms  recvLag rel(frame0)=%.1f min=%.1f max=%.1f ms\n",
                frames, interval / 1e6, anchor.model.offsetNs / 1e6, anchor.errorBoundMs, 0.0, lagMin - lag0, lagMax - lag0);

    const auto t0 = std::chrono::steady_clock::now();
    const size_t count = std::min(frames, maxFrames);
    std::vector<OpticalSyncFrame> sf;
    int stride = std::max<int>(1, int((std::max(first.width, first.height) + 479) / 480));
    while (std::max(thumbnailWidth(first.width, stride), thumbnailHeight(first.height, stride)) > 480) ++stride;
    for (size_t k = 0; k < count; ++k) {
        const size_t idx = (k * (frames - 1)) / (count - 1);
        auto cfa = src.readFrame(idx);
        if (!cfa.isOk()) { std::fprintf(stderr, "decode fail\n"); return 1; }
        const FrameInfo info = src.frameInfo(idx);
        OpticalSyncFrame f;
        f.captureNs = info.ptsUs * 1000;
        f.repTimeNs = f.captureNs + interval / 2;
        f.image = developThumbnail(cfa.value->data(), info, src.rawMeta(), Grade{}, stride);
        sf.push_back(std::move(f));
    }
    const auto t1 = std::chrono::steady_clock::now();
    OpticalSyncIntrinsics intr;
    intr.focalLengthPx = focalLengthPxFromEquivalent(focalMm, int(sf[0].image.w));
    for (int pass = 0; pass < 2; ++pass) {
        OrientationSeries series = ori.series;
        if (pass == 1) {
            std::vector<ImuSample> g2 = gyro;
            for (auto& s : g2) { s.x -= float(ori.gyroBias[0]); s.y -= float(ori.gyroBias[1]); s.z -= float(ori.gyroBias[2]); }
            series = integrateGyro(g2);
        }
        const OpticalSyncResult r = refineOffsetOptically(anchor, sf, series, intr);
        const double captureEquiv = r.refinedOffsetNs / 1e6 + interval / 2e6;
        std::printf("[%s] refined=%d captureMappedOffset=%.2fms (anchor err %.2fms) bound=%.2fms\n  %s\n",
                    pass ? "gyro-only" : "vqf", r.refined, captureEquiv,
                    captureEquiv - anchor.model.offsetNs / 1e6, r.errorBoundMs, r.provenance.c_str());
    }
    const auto t2 = std::chrono::steady_clock::now();
    std::printf("decode+develop %.0f ms, refine %.0f ms\n",
                std::chrono::duration<double, std::milli>(t1 - t0).count(),
                std::chrono::duration<double, std::milli>(t2 - t1).count());
}
