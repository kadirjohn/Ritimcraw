// M1 bench: developCpu serial vs parallel on the real clip (host).
// Ports the audit's bench shape (STEPS/owner's requests/
// ritim-audit-20261113/bench/bench.cpp — READ-ONLY reference, never
// modified) into a core doctest so the speedup is measured on the same
// machine that runs the tests, with the byte-exactness gate asserted in
// the same run.
//
// Shape (the audit's): decode frame 0 once, decimate to 1280x720 via
// developThumbnail's stride arithmetic, warm up, then N=9 timed
// repetitions and take the median. Three anchor cases from the audit's
// benchmark_host.csv rows: neutral, AgX, AgX + managed 709 LUT 33.
//
// Gated behind RAWEDIT_REALCLIP=1 (the 2 GB fixture; same gate as
// test_temporal.cpp / test_optical_sync.cpp).
//
// Honesty rules: serial_ms/parallel_ms/speedup are REPORTED (MESSAGE
// lines), never asserted — the host core count varies. What IS asserted:
// the serial and parallel outputs are byte-identical (the M1 acceptance
// gate) for every case.

#define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
#include "doctest.h"

#include <rawedit/Color.h>
#include <rawedit/Develop.h>
#include <rawedit/Lut.h>
#include <rawedit/Parallel.h>
#include <rawedit/Source.h>
#include <rawedit/Thumbnail.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

using namespace rawedit;

namespace {

bool realclipEnabled() { return std::getenv("RAWEDIT_REALCLIP") != nullptr; }

std::string realclipPath() {
    if (const char* p = std::getenv("RAWEDIT_CLIP"))
        return p;
    return "/Users/kc4ca/projects/Ritimcraw/STEPS/media/test/261110_004208_VIDEO_24mm.mcraw";
}

// The audit's stride arithmetic: the smallest stride-every that keeps the
// decimated thumbnail within the cap on both axes.
int strideFor(const FrameInfo& f, int cap) {
    int s = std::max(1, int((std::max(f.width, f.height) + cap - 1) / cap));
    while (std::max(thumbnailWidth(f.width, s), thumbnailHeight(f.height, s)) > unsigned(cap))
        s++;
    return s;
}

double medianOf(std::vector<double>& v) {
    std::sort(v.begin(), v.end());
    return v[v.size() / 2];
}

} // namespace

TEST_CASE("m1_bench_develop_serial_vs_parallel"
          * doctest::description("real clip; set RAWEDIT_REALCLIP=1 to run")) {
    if (!realclipEnabled()) {
        MESSAGE("SKIP: set RAWEDIT_REALCLIP=1 (2 GB fixture, decode is slow)");
        return;
    }

    auto res = Source::open(realclipPath());
    REQUIRE(res.isOk());
    Source& src = **res.value;
    REQUIRE(src.frameCount() > 0);

    // Decode frame 0 once (the audit's entry point).
    const FrameInfo info = src.frameInfo(0);
    auto decoded = src.readFrame(0);
    REQUIRE(decoded.isOk());
    const std::vector<uint16_t> cfa = std::move(*decoded.value);
    const RawMeta meta = src.rawMeta();

    const int cap = 1280;
    const int s = strideFor(info, cap);
    const uint32_t tw = thumbnailWidth(info.width, s);
    const uint32_t th = thumbnailHeight(info.height, s);
    REQUIRE(tw > 0);
    REQUIRE(th > 0);
    MESSAGE("M1 bench: frame " << info.width << "x" << info.height
             << " -> " << tw << "x" << th << " (stride " << s << ")");

    // The audit's 33^3 managed LUT (identity cube) + DisplayLook
    // Rec709OETF binding.
    Lut lut;
    lut.n = 33;
    for (int b = 0; b < 33; ++b)
        for (int g = 0; g < 33; ++g)
            for (int r = 0; r < 33; ++r) {
                lut.rgb.push_back(r / 32.0f);
                lut.rgb.push_back(g / 32.0f);
                lut.rgb.push_back(b / 32.0f);
            }
    LutInterpretation interp;
    interp.role = LutRole::DisplayLook;
    interp.input.transfer = TransferId::Rec709OETF;
    interp.output.transfer = TransferId::Rec709OETF;
    const CompiledLutBinding binding = compileLutBinding(interp);

    // The audit's three anchor cases. (std::string, not const char*:
    // doctest stringizes a char pointer as an address.)
    struct Case {
        std::string name;
        Grade grade;
        bool withLut;
    };
    std::vector<Case> cases;
    {
        Grade neutral;
        neutral.highlightReconstruction = false;
        neutral.keepVignetting = false;
        cases.push_back({"neutral", neutral, false});
    }
    {
        Grade agx;
        agx.highlightReconstruction = false;
        agx.keepVignetting = false;
        agx.tonemap = 2;
        cases.push_back({"agx", agx, false});
    }
    {
        Grade agxLut;
        agxLut.highlightReconstruction = false;
        agxLut.keepVignetting = false;
        agxLut.tonemap = 2;
        agxLut.lutStrength = 1.0f;
        cases.push_back({"agx_managed709_lut33", agxLut, true});
    }

    MESSAGE("M1 bench: workers=" << parallelWorkerCount()
             << " (host cores report "
             << std::thread::hardware_concurrency() << ")");

    const int reps = 9;
    for (const Case& c : cases) {
        const Lut* l = c.withLut ? &lut : nullptr;
        const CompiledLutBinding* b = c.withLut ? &binding : nullptr;

        // Warm-up (both paths) so first-touch allocation and the pool's
        // lazy creation stay out of the medians.
        setParallelForcedSerial(true);
        (void)developThumbnail(cfa.data(), info, meta, c.grade, s, l, b);
        setParallelForcedSerial(false);
        (void)developThumbnail(cfa.data(), info, meta, c.grade, s, l, b);

        std::vector<double> serialMs, parallelMs;
        for (int i = 0; i < reps; ++i) {
            setParallelForcedSerial(true);
            auto t0 = std::chrono::steady_clock::now();
            (void)developThumbnail(cfa.data(), info, meta, c.grade, s, l, b);
            auto t1 = std::chrono::steady_clock::now();
            setParallelForcedSerial(false);
            auto t2 = std::chrono::steady_clock::now();
            (void)developThumbnail(cfa.data(), info, meta, c.grade, s, l, b);
            auto t3 = std::chrono::steady_clock::now();
            serialMs.push_back(std::chrono::duration<double, std::milli>(t1 - t0).count());
            parallelMs.push_back(std::chrono::duration<double, std::milli>(t3 - t2).count());
        }

        // Byte-exactness gate, asserted (this is the acceptance criterion;
        // the speedup below is only reported).
        setParallelForcedSerial(true);
        ImageF ser = developThumbnail(cfa.data(), info, meta, c.grade, s, l, b);
        setParallelForcedSerial(false);
        ImageF par = developThumbnail(cfa.data(), info, meta, c.grade, s, l, b);
        REQUIRE(ser.w == par.w);
        REQUIRE(ser.h == par.h);
        REQUIRE(ser.rgb.size() == par.rgb.size());
        REQUIRE(std::memcmp(ser.rgb.data(), par.rgb.data(),
                            ser.rgb.size() * sizeof(float)) == 0);

        const double sm = medianOf(serialMs);
        const double pm = medianOf(parallelMs);
        const double speedup = pm > 0.0 ? sm / pm : 0.0;
        MESSAGE("M1 bench: case=" << c.name << " " << tw << "x" << th
                 << " serial_ms=" << sm << " parallel_ms=" << pm
                 << " speedup=" << speedup);
    }
}
