// rawedit::ImageStab — Source-driven analysis loop (S2). The only place the
// image-stab core touches a decoder; the analyzer itself stays push-style.
#include <rawedit/ImageStab.h>
#include <rawedit/Source.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <mutex>
#include <thread>

namespace rawedit {

namespace {
using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(Clock::now() - t0).count();
}
}  // namespace

AnalyzeResult analyzeSource(Source& src, const ImageStabOptions& opts,
                            const AnalyzeControl& ctl, ImagePath& out,
                            AnalyzeStats* stats) {
    const size_t total = src.frameCount();
    AnalyzeStats local;
    AnalyzeStats& st = stats ? *stats : local;
    if (total < 2) return AnalyzeResult::Failed;
    ImageStabAnalyzer an(opts, static_cast<uint32_t>(total));

    size_t next = 0;
    if (!ctl.checkpointFile.empty()) {
        ImagePath partial;
        if (readImagePathFile(ctl.checkpointFile, partial) == PathParse::Ok &&
            partial.frameCount == total && partial.recs.size() < total &&
            an.resume(partial)) {
            next = partial.recs.size();
            st.resumedFrom = next;
        }
    }
    const RawMeta& meta = src.rawMeta();
    auto lumaOf = [&](size_t i, std::vector<uint8_t>& luma, int& w, int& h,
                      int64_t& pts) -> bool {
        auto t = Clock::now();
        auto cfa = src.readFrame(i);
        st.readMs += msSince(t);
        if (!cfa.isOk()) return false;
        const FrameInfo fi = src.frameInfo(i);
        int32_t black[4];
        const bool dyn = fi.dynamicBlack[0] >= 0;
        for (int k = 0; k < 4; ++k) black[k] = dyn ? fi.dynamicBlack[k] : meta.black[k];
        const int32_t white = fi.dynamicWhite > 0 ? fi.dynamicWhite : meta.white;
        t = Clock::now();
        const bool ok = cfaToLuma(cfa.value->data(), static_cast<int>(fi.width),
                                  static_cast<int>(fi.height), black, white,
                                  ctl.targetLongSide, luma, w, h);
        st.lumaMs += msSince(t);
        pts = fi.ptsUs;
        return ok;
    };

    std::vector<uint8_t> luma;
    int w = 0, h = 0;
    int64_t pts = 0;
    if (next > 0) {  // seed with the last analyzed frame
        if (!lumaOf(next - 1, luma, w, h, pts)) return AnalyzeResult::Failed;
        an.seed(LumaFrame{w, h, w, luma.data()});
    }
    AnalyzeResult res = AnalyzeResult::Done;
    size_t sinceCheckpoint = 0;
    auto afterPush = [&](size_t i) {
        ++st.frames;
        if (ctl.progress) ctl.progress(i + 1, total);
        if (!ctl.checkpointFile.empty() && ++sinceCheckpoint >= ctl.checkpointEvery) {
            writeImagePathFile(ctl.checkpointFile, an.path());
            sinceCheckpoint = 0;
        }
    };
    if (ctl.threads < 2) {
        for (size_t i = next; i < total; ++i) {
            if (ctl.cancelled && ctl.cancelled()) {
                res = AnalyzeResult::Cancelled;
                break;
            }
            if (!lumaOf(i, luma, w, h, pts)) {
                res = AnalyzeResult::Failed;
                break;
            }
            an.push(LumaFrame{w, h, w, luma.data()}, pts);
            afterPush(i);
        }
    } else {
        // Producer: decode + cfaToLuma (the only thread touching `src` from
        // here on). Consumer (this thread): the tracker, strictly in order,
        // so the records equal the single-thread run bit for bit.
        struct Item {
            std::vector<uint8_t> luma;
            int w = 0, h = 0;
            int64_t pts = 0;
        };
        constexpr size_t kDepth = 3;
        std::mutex mu;
        std::condition_variable cv;
        std::deque<Item> q;
        bool producerDone = false, producerFailed = false;
        std::atomic<bool> stop{false};
        std::thread producer([&] {
            for (size_t i = next; i < total && !stop.load(); ++i) {
                Item it;
                if (!lumaOf(i, it.luma, it.w, it.h, it.pts)) {
                    std::lock_guard<std::mutex> lk(mu);
                    producerFailed = true;
                    break;
                }
                std::unique_lock<std::mutex> lk(mu);
                cv.wait(lk, [&] { return q.size() < kDepth || stop.load(); });
                if (stop.load()) break;
                q.push_back(std::move(it));
                cv.notify_all();
            }
            std::lock_guard<std::mutex> lk(mu);
            producerDone = true;
            cv.notify_all();
        });
        for (size_t i = next; i < total; ++i) {
            if (ctl.cancelled && ctl.cancelled()) {
                res = AnalyzeResult::Cancelled;
                break;
            }
            Item it;
            {
                std::unique_lock<std::mutex> lk(mu);
                cv.wait(lk, [&] { return !q.empty() || producerDone; });
                if (q.empty()) {
                    res = AnalyzeResult::Failed;  // producerFailed (or truncated)
                    break;
                }
                it = std::move(q.front());
                q.pop_front();
                cv.notify_all();
            }
            an.push(LumaFrame{it.w, it.h, it.w, it.luma.data()}, it.pts);
            afterPush(i);
        }
        {
            std::lock_guard<std::mutex> lk(mu);
            stop.store(true);
            cv.notify_all();
        }
        producer.join();
        (void)producerFailed;
    }
    st.track = an.times();
    out = an.path();
    if (!ctl.checkpointFile.empty() && res != AnalyzeResult::Failed)
        writeImagePathFile(ctl.checkpointFile, out);
    return res;
}

}  // namespace rawedit
