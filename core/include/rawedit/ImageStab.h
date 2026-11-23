// rawedit::ImageStab — image-based (gyro-free) stabilization core.
//
// Design: docs/IMAGE_STAB_PLAN.md (packages S1..S3 live here).
//   S1  pairwise motion estimation: pyramid, Shi-Tomasi grid corners,
//       forward-backward pyramidal Lucas-Kanade, similarity RANSAC + LSQ.
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
#include <vector>

namespace rawedit {

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

}  // namespace rawedit
