// rawedit bounded row-parallel helper (M1; audit section C item 4:
// "Specialize four CFA patterns and parallelize independent output
// rows/tiles with immutable inputs and separate output rows" and "Avoid
// unbounded per-frame thread creation or unrestricted core saturation").
//
// This is the ONLY threading primitive the develop pipeline uses. The
// byte-exactness gate (M1 acceptance) is absolute: a parallelized stage
// must produce output BIT-IDENTICAL to the serial loop — the same float
// ops in the same order per output element. parallelForRows therefore
// changes only WHICH thread computes each output row, never the math:
// no reductions, no atomics, no reassociation, no fast-math.
//
// Caller contract (restated per call site in Develop.cpp):
//   - fn(rowBegin, rowEnd) writes ONLY the output rows [rowBegin, rowEnd)
//     and reads ONLY the pass's immutable input;
//   - fn must not throw.
//
// Mechanism: a process-wide static pool created once (a leaky singleton —
// never destroyed, so no exit-time join races), sized
// min(4, hardware_concurrency) workers on a fixed set. The caller thread
// participates: it runs the first chunk while the workers run the rest.
// Serial fallback, never a failure mode: the platform reports one (or
// unknown) hardware threads, a worker construction fails, another thread
// holds the pool, the call comes from inside a worker, or the row count
// is tiny — the whole range then runs on the caller and the output is
// unchanged.
#pragma once

#include <cstddef>
#include <functional>

namespace rawedit {

// Splits [0, nRows) into at most (workers + 1) contiguous chunks and runs
// fn(rowBegin, rowEnd) over each; returns after all chunks complete.
void parallelForRows(
    size_t nRows,
    const std::function<void(size_t rowBegin, size_t rowEnd)>& fn);

// Tests/bench only: force the serial path (the whole range on the
// caller) so serial and parallel outputs can be compared inside one
// process. Returns the previous setting.
bool setParallelForcedSerial(bool forced);

// The fixed worker count serving this process (0 = every call runs
// serial). For honest bench reporting; do not branch develop math on it.
int parallelWorkerCount();

} // namespace rawedit
