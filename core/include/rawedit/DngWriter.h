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

} // namespace rawedit
