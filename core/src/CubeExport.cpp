// rawedit .cube export — the bake + the writer. See rawedit/CubeExport.h
// for the contract (what is baked, the input/output spaces, the clamp
// policy, determinism).
//
// REUSE, NOT RE-IMPLEMENTATION: every color operation below is the SAME
// function the render pipeline calls — applyToneColorStagesCpu (stages
// 9-20), applyNodes (the node chain), applyManagedLutStage (the legacy
// tonemap/LUT tail), applyOpenDrt (the DRT output). The lattice is just
// a 1 x (n*n) pixel image handed to those functions, one pixel per
// lattice point, so a baked entry and the app's render of the same input
// value run the identical float op order. That is the whole point: the
// LUT must match what the app renders.
//
// The Rec709Code input mode decodes the lattice coordinate through
// decodeTransfer(..., Rec709OETF) at entry (the inverse of the tail's
// linearToRec709) and re-encodes the output with linearToRec709 — the
// same pair of functions, so the display-encoded flavor stays in lockstep
// with the app's own OETF.

#include <rawedit/CubeExport.h>

#include <atomic>
#include <cmath>
#include <cstdio>
#include <string>

#include <rawedit/Parallel.h>
#include <rawedit/Transfer.h>

namespace rawedit {

namespace {

// The parser's own grid bound (Lut.cpp kMaxN): the bake accepts the same
// range the app can read back.
constexpr int kMaxCubeN = 128;

// Clamps one output sample for the FILE: NaN -> 0, then 0..1. The chain
// itself ran unclamped (see the header); this is only the .cube value.
inline float fileValue(float v) {
    if (std::isnan(v))
        return 0.0f;
    return std::min(std::max(v, 0.0f), 1.0f);
}

} // namespace

CubeExportTable bakeGradeCube(const CubeExportSpec& spec, int n) {
    CubeExportTable table;
    if (n < 2 || n > kMaxCubeN)
        return table;
    if (spec.nodes != nullptr && spec.nodeCount > 0) {
        const std::string err = validateNodeChain(spec.nodes, spec.nodeCount);
        if (!err.empty())
            return table; // the honest refusal: no bake, no silent drop
    }
    // A legacy output with no LUT and no binding is fine (applyManagedLutStage
    // takes null pointers); a DRT output never reads them.

    table.n = n;
    table.rgb.assign(static_cast<size_t>(n) * n * n * 3, 0.0f);
    const float inv = 1.0f / static_cast<float>(n - 1);

    // The grade whose COLOR part is baked. The excluded controls are
    // neutralized EXPLICITLY so a caller passing a full Grade cannot
    // smuggle a spatial stage in: sharpness/detail/denoise are zeroed and
    // the RAW-side toggles are irrelevant here (they live before stage 7
    // and never run on a lattice pixel anyway). Stage 9's input IS this
    // function's lattice, so nothing before stage 9 is re-run.
    Grade grade = spec.grade;
    grade.chromaDenoise = 0.0f;
    grade.noiseReduction = 0.0f;
    grade.sharpness = 0.0f;
    grade.detail = 0.0f;

    // The DRT transform, built once per bake (the OpenDrt constructor
    // resolves the presets and the tonescale constants).
    std::unique_ptr<OpenDrt> drt;
    if (spec.output == CubeExportOutput::OpenDrt)
        drt.reset(new OpenDrt(spec.drt));

    // Rows = (g, b) pairs; each row owns its own output slice, so the
    // parallel split never changes a value (the bakeOpenDrtLut pattern).
    parallelForRows(static_cast<size_t>(n) * n, [&](size_t rowBegin, size_t rowEnd) {
        for (size_t row = rowBegin; row < rowEnd; ++row) {
            const int b = static_cast<int>(row / n);
            const int g = static_cast<int>(row % n);
            // One lattice row as a 1-pixel-tall image: the SAME ImageF
            // shape every stage helper already handles (applyNodes and
            // applyManagedLutStage both require w*h*3, which this is).
            ImageF line;
            line.w = static_cast<uint32_t>(n);
            line.h = 1;
            line.rgb.resize(static_cast<size_t>(n) * 3);
            for (int r = 0; r < n; ++r) {
                float* px = &line.rgb[static_cast<size_t>(r) * 3];
                px[0] = static_cast<float>(r) * inv;
                px[1] = static_cast<float>(g) * inv;
                px[2] = static_cast<float>(b) * inv;
                if (spec.input == CubeExportInput::Rec709Code) {
                    // Display-encoded lattice -> the linear working space
                    // (the inverse of the tail's OETF, the same curve).
                    for (int c = 0; c < 3; ++c)
                        px[c] = decodeTransfer(px[c], TransferId::Rec709OETF);
                }
            }

            // 1. stages 9-20 (developCpu's own block).
            applyToneColorStagesCpu(line, grade);

            // 2. the node chain (N1), Rec709 -> Rec709 or Rec709 -> DWG
            //    per the output mode — applyV2ChainCpu's own selection.
            if (spec.nodes != nullptr && spec.nodeCount > 0) {
                const NodesOutput out = spec.output == CubeExportOutput::OpenDrt
                    ? NodesOutput::DwgLinear
                    : NodesOutput::Rec709Linear;
                if (!applyNodes(line, spec.nodes, spec.nodeCount, out))
                    return; // N1 caps: refused (the outer check guards; never hit)
            } else if (spec.output == CubeExportOutput::OpenDrt) {
                // An empty chain still converts 709 -> DWG for the DRT
                // input (applyNodes' own empty-chain rule for DwgLinear).
                applyNodes(line, nullptr, 0, NodesOutput::DwgLinear);
            }

            // 3. the output.
            if (spec.output == CubeExportOutput::LegacyTonemapLut) {
                applyManagedLutStage(line, spec.lut, spec.binding,
                                     grade.tonemap, grade.lutStrength);
                // Legacy output stays LINEAR 709 (the tail OETF is the
                // caller's); the display-encoded input flavor re-encodes
                // it so the cube reads consistently in that convention.
                if (spec.input == CubeExportInput::Rec709Code) {
                    for (size_t i = 0; i < line.rgb.size(); ++i)
                        line.rgb[i] = linearToRec709(line.rgb[i]);
                }
            } else {
                for (size_t p = 0; p < static_cast<size_t>(n); ++p) {
                    float* px = &line.rgb[p * 3];
                    drt->applyDwgLinear(px);
                    for (int c = 0; c < 3; ++c)
                        if (std::isnan(px[c]))
                            px[c] = 0.0f;
                }
                // DRT output is display code; the display-encoded input
                // flavor does NOT re-encode (the values are already code).
            }

            // 4. write the row, clamped for the file.
            float* dst = &table.rgb[(static_cast<size_t>(b) * n + g) * n * 3];
            for (int r = 0; r < n; ++r) {
                const float* px = &line.rgb[static_cast<size_t>(r) * 3];
                dst[0] = fileValue(px[0]);
                dst[1] = fileValue(px[1]);
                dst[2] = fileValue(px[2]);
                dst += 3;
            }
        }
    });

    return table;
}

std::string writeCubeText(const CubeExportTable& table) {
    if (table.n < 2)
        return std::string();
    // .cube is ASCII: "%.6f" keeps the 33/65 grid readable (the parser
    // reads float; six decimals round-trip a 0..1 sample to ~1e-6, far
    // below the lattice resolution of either grid) and matches what LUT
    // authoring tools write. snprintf is locale-independent for the
    // number itself ("C" locale digits with '.'), so the bytes do not
    // depend on the host.
    std::string out;
    out.reserve(static_cast<size_t>(table.n) * table.n * table.n * 24 + 64);
    char buf[96];
    std::snprintf(buf, sizeof(buf), "LUT_3D_SIZE %d\n", table.n);
    out += buf;
    const size_t rows = static_cast<size_t>(table.n) * table.n * table.n;
    for (size_t i = 0; i < rows; ++i) {
        const float* px = &table.rgb[i * 3];
        std::snprintf(buf, sizeof(buf), "%.6f %.6f %.6f\n",
                      static_cast<double>(px[0]),
                      static_cast<double>(px[1]),
                      static_cast<double>(px[2]));
        out += buf;
    }
    return out;
}

std::string exportGradeCube(const CubeExportSpec& spec, int n) {
    const CubeExportTable table = bakeGradeCube(spec, n);
    std::string text = writeCubeText(table);
    if (text.empty())
        return text;
    // The TITLE line goes ABOVE LUT_3D_SIZE (the parser accepts both
    // orders; the Adobe example's convention).
    if (!spec.title.empty()) {
        std::string quoted = "TITLE \"" + spec.title + "\"\n";
        return quoted + text;
    }
    return text;
}

} // namespace rawedit
