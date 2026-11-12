// rawedit::DngWriter — minimal own CFA (Bayer) DNG writer.
//
// Writes an uncompressed, single-strip, little-endian TIFF/DNG 1.4 file from
// a 16-bit CFA buffer plus the FrameInfo/RawMeta the Source layer already
// provides. No libtiff: the tag set is small and fixed, and owning the
// writer keeps the Android .so free of extra dependencies.
//
// Scope (Sprint 1, Task 5): one IFD, one strip, no maker notes, no lens
// shading baked into the pixels (the map is exported as-is; flattening it
// into the CFA is deferred with the develop pipeline's shading pass).

#pragma once

#include <cstdint>
#include <string>

#include <rawedit/Result.h>
#include <rawedit/Source.h>

namespace rawedit {

// Writes cfa (width*height uint16 samples, row-major, 2x2 pattern per
// FrameInfo.cfa) as a DNG at path. On failure returns IoError/Unsupported
// with detail; a partially written file is removed.
Result<void> writeCfaDng(const std::string& path, const uint16_t* cfa,
                         const FrameInfo& frame, const RawMeta& meta);

// Linear DNG (owner spec RAW-13): the demosaiced, scene-referred linear
// frame as a DNG 1.4 file — PhotometricInterpretation 34892 (LinearRaw),
// SamplesPerPixel 3, BitsPerSample 16 per sample, the pixels in the
// camera-native RGB primaries (ColorMatrix1 kept; only the stage-1
// normalization and the as-shot white balance are baked in). The exact
// processing stages applied to the pixels are recorded in the
// ImageDescription tag (270) so a reader can tell what this file is.
// rgb must hold width*height*3 uint16 samples (row-major, r-g-b order);
// the writer never re-develops — it serializes what it is given.
// On failure returns IoError/Unsupported with detail; a partially written
// file is removed.
Result<void> writeLinearDng(const std::string& path, const uint16_t* rgb,
                            const FrameInfo& frame, const RawMeta& meta);

} // namespace rawedit
