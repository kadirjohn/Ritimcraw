// rawedit LUT — Adobe .cube 3D LUT parsing and apply (owner request
// 2026-11-10 section 3, COL-07).
//
// Format (Adobe "Cube LUT" specification): a text file of keyword lines
// followed by one 3D table —
//   TITLE "optional name"
//   LUT_3D_SIZE N            (required; N grid points per axis, 2..128)
//   DOMAIN_MIN r g b         (optional, default 0 0 0)
//   DOMAIN_MAX r g b         (optional, default 1 1 1)
//   r g b                    (N^3 rows, red fastest: row m holds the entry
//                             for i = m % N, j = (m / N) % N, k = m / N^2)
// '#' comments and blank lines are skipped anywhere. Unknown keyword
// lines are skipped too (spec advice for forward compatibility). The
// Adobe spec's own example puts DOMAIN_MIN/MAX between LUT_3D_SIZE and
// the table, so keyword lines are accepted both before the size line
// and after it — until the first data row lands; past that point the
// table is size-declared, a keyword line inside it is malformed, and a
// file that size-declares N^3 rows and then keeps going is rejected.
// LUT_1D_SIZE is refused: this app only consumes 3D cubes. Table values are kept unclamped — .cube permits
// out-of-range values (HDR cubes) and the working space is scene-referred
// linear, so >1 outputs are meaningful.
//
// COL-07 color-space definition. applyLut runs as develop stage 24, LAST
// — after all grade stages and tonemapping (23), BEFORE the Rec.709 OETF
// that lives in the JNI bridge tail. Input: the pipeline's linear working
// space, scene-referred linear RGB in Rec.709 primaries (post-grade,
// post-tonemap). Output: the same space, handed to the OETF unchanged.
// Caveat: .cube carries no color-space tag, and most authored cubes
// assume their own input space (commonly display-encoded sRGB/Rec.709,
// sometimes a camera log). This app DEFINES its contract — linear Rec.709
// in, linear Rec.709 out — and applies every table against that
// definition; a cube authored for another space still applies numerically
// but will not reproduce its author's exact intent. The owner's
// acceptance check for COL-07 (preview vs export compared on-device, per
// the request) runs against this definition.
//
// Strength: out = lut(rgb) * strength + rgb * (1 - strength) — 1 is the
// pure table, 0 an exact bypass. Input values outside [DOMAIN_MIN,
// DOMAIN_MAX] clamp to the table edge (the .cube convention); the blend
// still uses the original pixel value.

#pragma once

#include <iosfwd>
#include <string>
#include <vector>

#include <rawedit/Result.h>

namespace rawedit {

struct ImageF; // Develop.h; forward-declared to keep this header a leaf

// A parsed 3D LUT. rgb holds n^3 * 3 floats in .cube row order (red
// fastest): entry (i, j, k) — the table sample for the input
// domainMin[c] + (domainMax[c] - domainMin[c]) * i/(n-1) on each axis —
// lives at rgb[(i + n*j + n*n*k) * 3 .. +2].
struct Lut {
    int n = 0;
    std::vector<float> rgb;
    float domainMin[3] = {0.0f, 0.0f, 0.0f};
    float domainMax[3] = {1.0f, 1.0f, 1.0f};
    std::string title; // TITLE line; empty when absent
};

// Parses .cube text into a Lut. Ok only for a complete, well-formed 3D
// table. Failures follow the app error style (stage: cause):
//   BadHeader   — missing/duplicate/malformed LUT_3D_SIZE, a data row
//                 before it, a row that is not exactly three finite
//                 floats, rows past the N^3 table, a keyword line after
//                 the first data row, or DOMAIN_MAX <= DOMAIN_MIN on any
//                 axis
//   Unsupported — LUT_1D_SIZE, or a size above the 128-point bound
//   Truncated   — fewer than N^3 rows
Result<Lut> parseCube(std::istream& in);

// Same parser over in-memory .cube text.
Result<Lut> parseCubeText(const std::string& text);

// Same parser over a file (IoError when the path cannot be opened).
Result<Lut> parseCubeFile(const std::string& path);

// Stage 24 (COL-07): samples the table trilinearly at every pixel of the
// linear working-space image and blends by strength (see above). No-op
// for a degenerate table (n < 2 or a mismatched row buffer) or a
// strength that clamps to 0 — callers guard both, this keeps a direct
// call safe too.
void applyLut(ImageF& img, const Lut& lut, float strength);

} // namespace rawedit
