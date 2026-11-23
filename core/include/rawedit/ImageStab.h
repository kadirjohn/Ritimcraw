// rawedit::ImageStab — image-based (gyro-free) stabilization core.
//
// Design: docs/IMAGE_STAB_PLAN.md (packages S1..S3 live here).
//   S1  pairwise motion estimation: pyramid, Shi-Tomasi grid corners,
//       forward-backward pyramidal Lucas-Kanade, similarity RANSAC + LSQ.
//   S2  analyzer (push API), CFA -> luma, the on-disk raw pair record
//       file (.ristab: CRC, checkpoint, resume), Source-driven analysis.
//   S3  ImageStabPlan: path accumulation, Smooth/Lock, dynamic zoom,
//       correction budget, exact corner verification, composeWarpM.
//
// MOTION MODEL (v1): similarity between consecutive frames, 4 DOF
// (tx, ty, log scale, rotation). Every number that leaves this module is
// RESOLUTION INDEPENDENT: positions are normalized by the frame HEIGHT
// (u = (x - w/2) / h, v = (y - h/2) / h, y down). The pair motion maps a
// point of the PREVIOUS frame to the CURRENT frame:
//     p_cur = exp(logS) * R(theta) * p_prev + t        (normalized units)
// i.e. it is the motion of the scene content in the image (the camera
// moves the opposite way). theta is counter-clockwise in the y-down image
// frame's complex-plane sense (z = u + i v, z' = a z + t, a = s e^{i theta}).
//
// Determinism contract: everything here is a pure function of its inputs.
// RANSAC uses a fixed-seed generator re-seeded on every estimatePair call,
// so two runs (and any thread count: no shared state) give bit-identical
// results. No fast-math, no atomics, no hidden globals.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace rawedit {

class Source;

// Read-only 8-bit luma view. stride in bytes (>= w).
struct LumaFrame {
    int w = 0;
    int h = 0;
    int stride = 0;
    const uint8_t* data = nullptr;
};

// Tracking quality of one pair.
enum class PairStatus : uint8_t {
    Ok = 0,    // trusted measurement
    Weak = 1,  // an estimate exists but few / poorly spread inliers
    Lost = 2   // no usable estimate (motion is zeroed)
};

struct PairMotion {
    // Normalized units (see header). Zero when status == Lost.
    double tx = 0.0;
    double ty = 0.0;
    double logS = 0.0;
    double theta = 0.0;  // radians
    float inlierRatio = 0.0f;  // inliers / successfully tracked points
    float coverage = 0.0f;     // fraction of the 3x3 grid holding an inlier
    float residualRms = 0.0f;  // inlier residual RMS, working-res pixels
    uint16_t nTracked = 0;     // points that survived forward-backward
    uint16_t nInliers = 0;
    PairStatus status = PairStatus::Lost;
};

struct ImageStabOptions {
    // Corner grid.
    int gridCols = 12;
    int gridRows = 8;
    int maxPerCell = 3;
    int maxPoints = 150;
    int cornerMinDist = 6;        // px between picks inside one cell
    // Thresholds are RELATIVE to the frame's own luma scale (the p1..p99
    // range of the previous frame, see lumaScale), never to 8-bit counts,
    // so a flat log clip and a contrasty clip use the same settings.
    double qualityRel = 0.02;     // vs. strongest cell-best response
    double floorGradRel = 0.005;  // min gradient (fraction of scale per px)
    // Pyramidal LK.
    int levels = 3;
    int window = 16;
    int maxIter = 15;
    double iterEps = 0.01;        // px
    double fbMax = 1.0;           // forward-backward error limit, px
    // RANSAC.
    int ransacIters = 150;
    double inlierThresh = 1.5;    // px at working resolution
    double minSampleSepRel = 0.15;  // min distance of the 2 sampled points, x h
    // Status thresholds.
    int okMinInliers = 15;
    double okMinRatio = 0.4;
    double okMinCoverage = 0.33;
    int weakMinInliers = 6;
    double weakMinRatio = 0.25;
};

// Wall time per stage of one estimatePair call, milliseconds. The plan
// asks for stage-by-stage timing, not one lump number.
struct StageTimes {
    double pyramidMs = 0.0;  // both pyramids + gradients
    double cornersMs = 0.0;  // Shi-Tomasi response + grid selection
    double lkMs = 0.0;       // forward + backward tracking
    double fitMs = 0.0;      // RANSAC + LSQ + status
    void add(const StageTimes& o) {
        pyramidMs += o.pyramidMs;
        cornersMs += o.cornersMs;
        lkMs += o.lkMs;
        fitMs += o.fitMs;
    }
};

// p1..p99 luma range of a frame (>= 1). The unit for the relative thresholds.
double lumaScale(const LumaFrame& f);

// Image pyramid with per-level central-difference gradients. Level 0 is a
// float copy of the luma; level k+1 is the 2x2 box average of level k.
struct ImagePyramid {
    struct Level {
        int w = 0;
        int h = 0;
        std::vector<float> img;
        std::vector<float> gx;
        std::vector<float> gy;
    };
    std::vector<Level> levels;
    double scale = 1.0;  // lumaScale of level 0's source frame
};
ImagePyramid buildPyramid(const LumaFrame& f, int levels);

// Motion of `cur` relative to `prev`. `prior` (optional) seeds the LK
// search with the previous pair's motion (fast pans); null = zero guess.
// `times` (optional) receives the stage timings.
PairMotion estimatePair(const LumaFrame& prev, const LumaFrame& cur,
                        const ImageStabOptions& opts = ImageStabOptions(),
                        const PairMotion* prior = nullptr,
                        StageTimes* times = nullptr);

// Same, on pyramids built by buildPyramid with the SAME frame size and
// opts.levels. Lets a caller (the S2 analyzer) build each frame's pyramid
// once and use it for two consecutive pairs.
PairMotion estimatePair(const ImagePyramid& prev, const ImagePyramid& cur,
                        const ImageStabOptions& opts = ImageStabOptions(),
                        const PairMotion* prior = nullptr,
                        StageTimes* times = nullptr);

// =========================================================================
// S2 — analyzer, luma, analysis file
// =========================================================================

// Bump when the algorithm, the working resolution or the luma path changes
// in a way that alters stored numbers: stored analyses are then stale.
constexpr uint32_t kImageStabAnalysisVersion = 1;

// CFA -> 8-bit working luma, independent of any grade.
//   - black level: black[4] indexed by 2x2 cell position (cellY*2+cellX,
//     the container's (r,g,g,b) slot order); a frame's dynamic levels win
//     over the container's, like the develop stage;
//   - a 2x2 cell is averaged with equal weights (R + 2G + B)/4 -> the
//     result is green-weighted and independent of the CFA arrangement;
//   - the cells are then area-averaged by an integer factor so the longest
//     side lands near `targetLongSide`;
//   - sqrt() tone curve: lifts shadows so dark scenes keep gradients, and
//     is a fixed function (no per-frame adaptation).
// Returns the output size via outW/outH; out is resized to outW*outH.
// Invalid input returns false.
bool cfaToLuma(const uint16_t* cfa, int cfaW, int cfaH, const int32_t black[4],
               int32_t white, int targetLongSide, std::vector<uint8_t>& out,
               int& outW, int& outH);

// One pair record exactly as stored on disk (32 bytes). Values are
// quantized AT CREATION, so a continuous run, a resumed run and a reloaded
// file are bit-identical.
struct PairRecord {
    int64_t ptsUs = 0;
    float tx = 0, ty = 0, logS = 0, theta = 0;
    uint8_t inlierRatio8 = 0;   // ratio * 255
    uint8_t coverage8 = 0;      // coverage * 255
    uint16_t nInliers = 0;
    uint16_t residualMilliPx = 0;
    uint8_t status = 2;         // PairStatus
};
PairRecord toRecord(int64_t ptsUs, const PairMotion& m);
PairMotion toMotion(const PairRecord& r);

// Raw per-pair records of one clip. recs[0] is the identity (frame 0 has no
// predecessor); recs[i] is the motion of frame i relative to frame i-1.
struct ImagePath {
    uint32_t analysisVersion = kImageStabAnalysisVersion;
    uint32_t workW = 0, workH = 0;
    uint32_t frameCount = 0;    // frames the full clip has (recs.size() when complete)
    bool complete = false;
    std::vector<PairRecord> recs;
};

enum class PathParse { Ok, BadMagic, BadVersion, Truncated, BadCrc, Mismatch };
uint32_t crc32Ieee(const uint8_t* p, size_t n);
std::vector<uint8_t> serializeImagePath(const ImagePath& p);
// BadVersion = unknown formatVersion OR a different analysisVersion (stale).
PathParse parseImagePath(const uint8_t* p, size_t n, ImagePath& out);
// Atomic write (tmp + rename). False on I/O failure.
bool writeImagePathFile(const std::string& file, const ImagePath& p);
PathParse readImagePathFile(const std::string& file, ImagePath& out);

// Push-style analyzer: knows nothing about decoders. Feed frames in order.
class ImageStabAnalyzer {
public:
    ImageStabAnalyzer(const ImageStabOptions& opts, uint32_t expectedFrames);
    // Continue a partial path. After a true return, call seed() with the
    // LAST analyzed frame (index path().recs.size()-1) and then push() the
    // frames after it. False if the partial path does not fit.
    bool resume(const ImagePath& partial);
    void seed(const LumaFrame& lastAnalyzed);
    // Appends one record (frame index = recs.size()).
    void push(const LumaFrame& f, int64_t ptsUs);
    const ImagePath& path() const { return path_; }
    ImagePath& path() { return path_; }
    const StageTimes& times() const { return times_; }
private:
    ImageStabOptions opts_;
    ImagePath path_;
    ImagePyramid prev_;
    bool havePrev_ = false;
    StageTimes times_;
};

// The LK prior of pair i: the previous pair's motion if usable, else the one
// before it (a lost pair keeps the velocity for 2 frames), else none. A pure
// function of the stored records, so resume is exact.
bool priorFromRecords(const std::vector<PairRecord>& recs, size_t i, PairMotion& out);

struct AnalyzeControl {
    std::function<bool()> cancelled;                       // may be empty
    std::function<void(size_t done, size_t total)> progress;  // may be empty
    std::string checkpointFile;     // empty = no checkpointing / resume
    size_t checkpointEvery = 256;   // frames between checkpoint writes
    int targetLongSide = 640;
};
struct AnalyzeStats {
    double readMs = 0;   // Source::readFrame (decode)
    double lumaMs = 0;   // cfaToLuma
    StageTimes track;    // per-stage tracker times
    size_t frames = 0;   // frames pushed in THIS call
    size_t resumedFrom = 0;
};
enum class AnalyzeResult { Done, Cancelled, Failed };
// Analyze (or resume) a whole Source. On Cancelled the partial path has been
// checkpointed (if a file was given). out receives the path in every case.
AnalyzeResult analyzeSource(Source& src, const ImageStabOptions& opts,
                            const AnalyzeControl& ctl, ImagePath& out,
                            AnalyzeStats* stats = nullptr);

// =========================================================================
// S3 — plan: path, smoothing/lock, dynamic zoom, correction budget
// =========================================================================

// A similarity in height-normalized units: p' = s * R(theta) * p + (tx, ty).
struct Sim2 {
    double s = 1.0;
    double theta = 0.0;
    double tx = 0.0;
    double ty = 0.0;
};
Sim2 simCompose(const Sim2& outer, const Sim2& inner);  // outer o inner
Sim2 simInverse(const Sim2& a);
Sim2 simOfMotion(const PairMotion& m);

struct ImageStabParams {
    int mode = 0;               // 0 Smooth, 1 Lock (anything else = Smooth)
    double strength = 0.5;      // 0..1 (the UI's 0..100 / 100)
    double zoomLimit = 1.15;    // crop ceiling, 1.0..1.5
    double zoomWindowSec = 1.0; // rolling-max half window and Gauss support
    double zoomRate = 0.10;     // max |d ln Z / dt| in 1/s; <= 0 = unlimited
    double panSpeed = 0.5;      // heights/s regarded as an intentional pan
    // Margin of the coverage test, in heights (resolution independent, so
    // preview and export tiers get the SAME plan; 0.003 = 2 px at 720 p).
    double edgeMargin = 0.003;
};

struct ImageStabFrame {
    Sim2 corr;        // source -> output correction (normalized units)
    double zoom = 1;  // crop zoom about the frame center (>= 1)
    double a = 1;     // applied fraction of the full correction
};

struct ImageStabPlanStats {
    double meanZoom = 1, maxZoom = 1;
    double meanA = 1;
    double limitedFraction = 0;    // frames whose correction was reduced
    double maxZoomRate = 0;        // max |d ln Z / dt|, 1/s
    int budgetRounds = 0;          // shrink-and-resmooth rounds used
    int hardFallbackFrames = 0;    // frames forced to identity by the exact check
    int invalidSampleFrames = 0;   // frames still failing the exact check (expect 0)
};

class ImageStabPlan {
public:
    // recs: the clip's raw pair records (ImagePath::recs). [rangeStart,
    // rangeEnd] (inclusive) is the used range; it only decides the Lock
    // target. aspect = output width / height.
    static ImageStabPlan build(const std::vector<PairRecord>& recs,
                               const ImageStabParams& params, size_t rangeStart,
                               size_t rangeEnd, double aspect);
    size_t size() const { return frames_.size(); }
    const ImageStabFrame& frame(size_t i) const { return frames_[i]; }
    const ImageStabPlanStats& stats() const { return stats_; }
    const std::vector<Sim2>& rawPath() const { return raw_; }
    const std::vector<Sim2>& targetPath() const { return target_; }
    // Per-frame zoom each frame would need (at the applied correction),
    // before the rolling-max / Gauss / rate-limit envelope; for measurement.
    const std::vector<double>& requiredZoomSeries() const { return zreq_; }
private:
    std::vector<double> zreq_;
    std::vector<ImageStabFrame> frames_;
    std::vector<Sim2> raw_, target_;
    ImageStabPlanStats stats_;
};

// Smallest zoom >= 1 at which the correction keeps every output corner
// inside the (margin-shrunk) source; +infinity when no zoom can.
double requiredZoom(const Sim2& corr, double aspect, double edgeMargin);

// Distance (pixels, >= 0 means inside) by which the worst of the 4 output
// pixel-center corners lands inside the source pixel box [0,w-1]x[0,h-1],
// using EXACTLY the runtime composition (warpFrame / the GPU matrix).
double coverageSlackPx(const Sim2& corr, double zoom, int w, int h);

// Source -> output homography in absolute pixel coordinates (row-major):
// H = T(c) * [s R | t*h] * T(-c), c = (w/2, h/2).
void simToHomographyPx(const Sim2& corr, int w, int h, double H[9]);
// M = H^-1 * S(zoom) (output pixel -> source pixel), the matrix warpFrame
// builds internally and the GPU pass is fed. False when H is singular.
// THE single definition; the planner's edge test, tests and the JNI resolver
// share it.
bool composeWarpM(const double H[9], double zoom, int w, int h, double M[9]);
// Convenience: frame -> M, and whether the warp is a no-op (identity, zoom 1).
bool frameWarpM(const ImageStabFrame& f, int w, int h, double M[9], bool* active);

}  // namespace rawedit
